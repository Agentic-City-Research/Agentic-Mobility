package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Daily orchestration. Per iteration (day): reset state, optionally update the
 * manager (days 21, 26, ..., 96), ask every LLM traveler for its outbound+return
 * decision (in parallel), write accepted decisions and plans, then after the
 * mobsim record experiences, metrics, progress and usage. Background replanning
 * happens in notifyBeforeMobsim via the scenario's existing strategies.
 */
public final class DailyLoop implements StartupListener, IterationStartsListener,
        BeforeMobsimListener, AfterMobsimListener, IterationEndsListener, ShutdownListener {

    private final AgenticConfig config;
    private final Population population;
    private final OutputWriter output;
    private final LlmUsageTracker usage;
    private final InformationModel infoModel;
    private final TollSystem tollSystem;
    private final Metrics metrics;
    private final List<TravelerAgent> travelers;
    private final ManagerAgent manager;
    private final int validatedCount;
    private final TravelTimeCalculator travelTimeCalculator;
    private final ExpectedTravelTimes expectedTravelTimes;

    private final Map<Id<Person>, TravelerAgent> agentByPerson = new ConcurrentHashMap<>();
    private final Map<Id<Person>, Map<String, Object>> experienceByPerson = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> metricsSinceUpdate = new ArrayList<>();

    private volatile TollPolicy currentPolicy = TollPolicy.empty(-1);
    private ExecutorService pool;
    private int daysCompleted = 0;
    private volatile boolean failed = false;

    public DailyLoop(AgenticConfig config, Scenario scenario, OutputWriter output, LlmUsageTracker usage,
                     InformationModel infoModel, TollSystem tollSystem, Metrics metrics,
                     List<TravelerAgent> travelers, ManagerAgent manager, int validatedCount,
                     TravelTimeCalculator travelTimeCalculator, ExpectedTravelTimes expectedTravelTimes) {
        this.config = config;
        this.population = scenario.getPopulation();
        this.output = output;
        this.usage = usage;
        this.infoModel = infoModel;
        this.tollSystem = tollSystem;
        this.metrics = metrics;
        this.travelers = travelers;
        this.manager = manager;
        this.validatedCount = validatedCount;
        this.travelTimeCalculator = travelTimeCalculator;
        this.expectedTravelTimes = expectedTravelTimes;
        for (TravelerAgent agent : travelers) {
            agentByPerson.put(agent.personId(), agent);
        }
    }

    @Override
    public void notifyStartup(StartupEvent event) {
        try {
            tollSystem.setPolicy(currentPolicy);
            int llm = infoModel.llmCount();
            int llmInf = infoModel.llmInformedCount();
            int bgInf = infoModel.bgInformedCount();
            metrics.setPlanned(InformationModel.Group.LLM_INFORMED, 2L * llmInf);
            metrics.setPlanned(InformationModel.Group.LLM_UNINFORMED, 2L * (llm - llmInf));
            metrics.setPlanned(InformationModel.Group.BG_INFORMED, 2L * bgInf);
            metrics.setPlanned(InformationModel.Group.BG_UNINFORMED, 2L * (validatedCount - llm - bgInf));
            pool = Executors.newFixedThreadPool(Math.max(1, config.concurrency), task -> {
                Thread thread = new Thread(task, "llm-decision");
                // A provider that ignores interruption must not keep the JVM alive after shutdown.
                thread.setDaemon(true);
                return thread;
            });
            writeProgressStatus();
            output.apiUsage(usage.toJson());
            output.flush();
        } catch (RuntimeException e) {
            recordFailure(e);
            throw e;
        }
    }

    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        int day = event.getIteration();
        try {
            tollSystem.resetForDay();
            metrics.resetForDay();

            if (isManagerDay(day)) {
                TollPolicy newPolicy = LlmDecisionBatch.run(pool,
                        List.<Callable<TollPolicy>>of(() -> manager.decide(day, currentPolicy, metricsSinceUpdate,
                                validatedCount, config.informedShare)), List.of("manager"),
                        Duration.ofSeconds(config.llmStallSeconds), day, "manager",
                        count -> writeLlmProgress(day, "manager", count, 1)).getFirst();
                currentPolicy = newPolicy;
                tollSystem.setPolicy(newPolicy);
                metricsSinceUpdate.clear();
                output.policy(newPolicy.toJson());
            }

            List<TravelerDecision> decisions = decideTravelers(day);
            for (int i = 0; i < travelers.size(); i++) {
                TravelerAgent agent = travelers.get(i);
                TravelerDecision d = decisions.get(i);
                writeDecision(day, agent, d);
                applyDecision(agent, d);
            }
            output.flush();
        } catch (RuntimeException e) {
            recordFailure(e);
            throw e;
        }
    }

    @Override
    public void notifyBeforeMobsim(BeforeMobsimEvent event) {
        // Background replanning (scenario strategies) runs here; LLM travelers are frozen
        // via the "llm" subpopulation's KeepLastSelected strategy. Clear the realized
        // travel-time accumulator so the mobsim only records this day's observations.
        travelTimeCalculator.reset(event.getIteration());
    }

    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {
        int day = event.getIteration();
        try {
            metrics.foldCompletedTrips();
            // Publish today's realized travel times for tomorrow's LLM decisions.
            expectedTravelTimes.setObserved(travelTimeCalculator.getLinkTravelTimes());
            for (TravelerAgent agent : travelers) {
                Map<String, Object> exp = buildExperience(agent.personId(), day);
                experienceByPerson.put(agent.personId(), exp);
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("day", day);
                rec.put("person_id", agent.personId().toString());
                rec.putAll(exp);
                output.experience(rec);
            }

            Map<String, Object> snap = metrics.snapshot(day);
            snap.put("network", networkRollup((Map<String, Object>) snap.get("groups")));
            output.metric(snap);
            metricsSinceUpdate.add(snap);

            daysCompleted = day + 1;
            writeProgressStatus();
            output.apiUsage(usage.toJson());
            output.flush();
        } catch (RuntimeException e) {
            recordFailure(e);
            throw e;
        }
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        if (!failed) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("status", event.isUnexpected() ? "failed" : "completed");
            status.put("days_completed", daysCompleted);
            status.put("days_total", config.days);
            event.getException().ifPresent(t -> status.put("error", t.toString()));
            output.status(status);
        }
        output.apiUsage(usage.toJson());
        output.flush();
        if (pool != null) pool.shutdownNow();
        output.close();
    }

    private boolean isManagerDay(int day) {
        return manager != null && !config.noManager && day >= 21 && (day - 21) % 5 == 0;
    }

    private List<TravelerDecision> decideTravelers(int day) {
        TollPolicy policy = currentPolicy;
        List<Callable<TravelerDecision>> tasks = new ArrayList<>(travelers.size());
        List<String> labels = new ArrayList<>(travelers.size());
        for (TravelerAgent agent : travelers) {
            Map<String, Object> exp = experienceByPerson.get(agent.personId());
            tasks.add(() -> agent.decide(day, policy, exp));
            labels.add(agent.personId().toString());
        }
        return LlmDecisionBatch.run(pool, tasks, labels, Duration.ofSeconds(config.llmStallSeconds),
                day, "travelers", count -> writeLlmProgress(day, "travelers", count, travelers.size()));
    }

    private void writeLlmProgress(int day, String phase, int completed, int total) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("days_completed", daysCompleted);
        progress.put("days_total", config.days);
        progress.put("day", day);
        progress.put("phase", phase);
        progress.put("llm_completed", completed);
        progress.put("llm_total", total);
        progress.put("last_progress_at", Instant.now().toString());
        progress.put("llm_stall_seconds", config.llmStallSeconds);
        output.progress(progress);
        output.flush();
    }

    private void applyDecision(TravelerAgent agent, TravelerDecision d) {
        Person person = population.getPersons().get(agent.personId());
        Plan plan = person.getSelectedPlan();
        // The scenario routes car with walk access/egress, so at runtime each plan is
        // home -> walk -> car-interaction -> car -> car-interaction -> walk -> work -> ...
        // We target the two "car" legs and the real (non "car interaction") activities.
        List<Activity> realActivities = new ArrayList<>();
        List<Leg> carLegs = new ArrayList<>();
        for (PlanElement e : plan.getPlanElements()) {
            if (e instanceof Activity a) {
                if (!"car interaction".equals(a.getType())) realActivities.add(a);
            } else if (e instanceof Leg l) {
                if ("car".equals(l.getMode())) carLegs.add(l);
            }
        }
        if (realActivities.size() != 3 || carLegs.size() != 2) {
            StringBuilder dump = new StringBuilder();
            for (PlanElement e : plan.getPlanElements()) {
                if (e instanceof Activity a) dump.append("[act ").append(a.getType()).append("] ");
                else if (e instanceof Leg l) dump.append("[leg ").append(l.getMode()).append("] ");
            }
            throw new LlmException("traveler " + agent.personId()
                    + " plan not home-work-home (realActs=" + realActivities.size()
                    + " carLegs=" + carLegs.size() + "): " + dump);
        }
        RouteCandidate out = agent.outboundCandidate(d.outboundRouteId());
        RouteCandidate ret = agent.returnCandidate(d.returnRouteId());
        carLegs.get(0).setRoute(toNetworkRoute(out));
        carLegs.get(1).setRoute(toNetworkRoute(ret));
        // realActivities = [home, work, home]; shift the whole departure by the offset.
        realActivities.get(0).setEndTime(agent.outboundAnchor() + d.outboundOffsetMinutes() * 60.0);
        realActivities.get(1).setEndTime(agent.returnAnchor() + d.returnOffsetMinutes() * 60.0);
    }

    private NetworkRoute toNetworkRoute(RouteCandidate c) {
        List<Id<Link>> ids = c.linkIds();
        Id<Link> start = ids.get(0);
        Id<Link> end = ids.get(ids.size() - 1);
        List<Id<Link>> mids = ids.size() > 2 ? ids.subList(1, ids.size() - 1) : List.of();
        NetworkRoute route = (NetworkRoute) population.getFactory().getRouteFactories()
                .createRoute(NetworkRoute.class, start, end);
        route.setLinkIds(start, mids, end);
        return route;
    }

    private void writeDecision(int day, TravelerAgent agent, TravelerDecision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day);
        m.put("person_id", agent.personId().toString());
        m.put("profile", agent.profile().toJson());
        m.put("informed", agent.informed());
        m.put("outbound", Map.of("route_id", d.outboundRouteId(), "offset", d.outboundOffsetMinutes()));
        m.put("return", Map.of("route_id", d.returnRouteId(), "offset", d.returnOffsetMinutes()));
        m.put("memory", d.memory());
        m.put("reason", d.reason());
        output.decision(m);
    }

    private Map<String, Object> buildExperience(Id<Person> personId, int day) {
        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("day", day);
        Metrics.PersonTrip trip = metrics.tripOf(personId);
        if (trip != null) {
            exp.putAll(trip.toJson());
        } else {
            exp.put("outbound_completed", false);
            exp.put("return_completed", false);
            exp.put("stuck", false);
        }
        exp.put("toll_paid", tollSystem.tollPaidOf(personId));
        List<Map<String, Object>> receipts = new ArrayList<>();
        for (TollSystem.Receipt r : tollSystem.receiptsOf(personId)) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("link_id", r.linkId().toString());
            rm.put("time", r.time());
            rm.put("amount", r.amount());
            receipts.add(rm);
        }
        exp.put("toll_receipts", receipts);
        return exp;
    }

    private void writeProgressStatus() {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("days_completed", daysCompleted);
        progress.put("days_total", config.days);
        output.progress(progress);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", failed ? "failed" : "running");
        status.put("days_completed", daysCompleted);
        status.put("days_total", config.days);
        output.status(status);
    }

    private void recordFailure(Throwable t) {
        if (failed) return;
        failed = true;
        if (pool != null) pool.shutdownNow();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "failed");
        status.put("days_completed", daysCompleted);
        status.put("days_total", config.days);
        status.put("error", t.toString());
        output.status(status);
        output.apiUsage(usage.toJson());
        output.flush();
        output.close();
    }

    private Map<String, Object> networkRollup(Map<String, Object> groups) {
        long planned = 0, started = 0, completed = 0, stuck = 0;
        double totalDelay = 0;
        if (groups != null) {
            for (Object v : groups.values()) {
                if (v instanceof Map<?, ?> gm) {
                    planned += lnum(gm.get("planned"));
                    started += lnum(gm.get("started"));
                    completed += lnum(gm.get("completed"));
                    stuck += lnum(gm.get("stuck"));
                    totalDelay += dnum(gm.get("total_delay"));
                }
            }
        }
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("planned", planned);
        n.put("started", started);
        n.put("completed", completed);
        n.put("stuck", stuck);
        n.put("completion_rate", planned == 0 ? 0.0 : (double) completed / planned);
        n.put("total_delay", totalDelay);
        return n;
    }

    private static long lnum(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double dnum(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
