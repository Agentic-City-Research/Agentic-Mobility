package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.population.routes.NetworkRoute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Daily trip/link metrics accumulated from events. Counts planned/started/
 * completed/stuck trips per information group, per-person trip times (for LLM
 * experiences), and per-link entry counts (congestion proxy). Completed-trip
 * time and delay use completed trips only; delay is actual duration minus the
 * free-flow time of the route actually taken, truncated to non-negative.
 */
public final class Metrics implements BasicEventHandler {

    public static final class GroupCounters {
        public final AtomicLong planned = new AtomicLong();
        public final AtomicLong started = new AtomicLong();
        public final AtomicLong completed = new AtomicLong();
        public final AtomicLong stuck = new AtomicLong();
        public final DoubleAdder totalDuration = new DoubleAdder();
        public final DoubleAdder totalDelay = new DoubleAdder();
    }

    /** Per-person, per-day trip state (two car legs). */
    public static final class PersonTrip {
        private double dep0 = -1, dep1 = -1, arr0 = -1, arr1 = -1;
        private double ff0 = 0, ff1 = 0;
        private double dur0 = 0, dur1 = 0, delay0 = 0, delay1 = 0;
        private boolean stuckFlag = false;
        private int departures = 0, arrivals = 0;

        public synchronized int nextDepartureIndex() {
            return departures;
        }

        public synchronized int nextArrivalIndex() {
            return arrivals;
        }

        public synchronized void onDeparture(int legIndex, double time, double freeFlow) {
            departures++;
            if (legIndex == 0) { dep0 = time; ff0 = freeFlow; } else { dep1 = time; ff1 = freeFlow; }
        }

        public synchronized void onArrival(int legIndex, double time) {
            arrivals++;
            if (legIndex == 0) {
                arr0 = time;
                if (dep0 >= 0) { dur0 = time - dep0; delay0 = Math.max(0, dur0 - ff0); }
            } else {
                arr1 = time;
                if (dep1 >= 0) { dur1 = time - dep1; delay1 = Math.max(0, dur1 - ff1); }
            }
        }

        public synchronized void markStuck() {
            stuckFlag = true;
        }

        public synchronized Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outbound_departure", dep0);
            m.put("outbound_arrival", arr0);
            m.put("outbound_completed", arr0 >= 0);
            m.put("outbound_duration", dur0);
            m.put("outbound_delay", delay0);
            m.put("return_departure", dep1);
            m.put("return_arrival", arr1);
            m.put("return_completed", arr1 >= 0);
            m.put("return_duration", dur1);
            m.put("return_delay", delay1);
            m.put("stuck", stuckFlag);
            return m;
        }
    }

    private final Population population;
    private final Network network;
    private final InformationModel infoModel;

    private final GroupCounters[] counters = new GroupCounters[InformationModel.Group.values().length];
    private final Map<Id<Person>, PersonTrip> personTrips = new ConcurrentHashMap<>();
    private final LinkTrafficMetrics linkMetrics;

    public Metrics(Population population, Network network, InformationModel infoModel) {
        this.population = population;
        this.network = network;
        this.infoModel = infoModel;
        for (InformationModel.Group g : InformationModel.Group.values()) {
            counters[g.ordinal()] = new GroupCounters();
        }
        this.linkMetrics = new LinkTrafficMetrics(network);
    }

    public void setPlanned(InformationModel.Group group, long planned) {
        counters[group.ordinal()].planned.set(planned);
    }

    public PersonTrip tripOf(Id<Person> personId) {
        return personTrips.get(personId);
    }

    @Override
    public void handleEvent(Event event) {
        linkMetrics.handle(event);
        if (event instanceof PersonDepartureEvent dep) {
            if (!"car".equals(dep.getLegMode())) return;
            Person person = population.getPersons().get(dep.getPersonId());
            if (person == null) return;
            InformationModel.Group g = infoModel.groupOf(dep.getPersonId());
            counters[g.ordinal()].started.incrementAndGet();
            PersonTrip t = personTrips.computeIfAbsent(dep.getPersonId(), k -> new PersonTrip());
            int legIndex = t.nextDepartureIndex();
            double freeFlow = legIndex < 2 ? freeFlowOfLeg(person, legIndex) : 0;
            t.onDeparture(legIndex, dep.getTime(), freeFlow);
            return;
        }
        if (event instanceof PersonArrivalEvent arr) {
            if (!"car".equals(arr.getLegMode())) return;
            InformationModel.Group g = infoModel.groupOf(arr.getPersonId());
            counters[g.ordinal()].completed.incrementAndGet();
            PersonTrip t = personTrips.computeIfAbsent(arr.getPersonId(), k -> new PersonTrip());
            int legIndex = t.nextArrivalIndex();
            t.onArrival(legIndex, arr.getTime());
            return;
        }
        if (event instanceof PersonStuckEvent stuck) {
            InformationModel.Group g = infoModel.groupOf(stuck.getPersonId());
            counters[g.ordinal()].stuck.incrementAndGet();
            personTrips.computeIfAbsent(stuck.getPersonId(), k -> new PersonTrip()).markStuck();
        }
    }

    private double freeFlowOfLeg(Person person, int legIndex) {
        Plan plan = person.getSelectedPlan();
        if (plan == null) return 0;
        int carLeg = 0;
        for (PlanElement e : plan.getPlanElements()) {
            if (e instanceof Leg leg && "car".equals(leg.getMode())) {
                if (carLeg == legIndex) return freeFlowOfRoute(leg.getRoute());
                carLeg++;
            }
        }
        return 0;
    }

    private double freeFlowOfRoute(Route route) {
        if (!(route instanceof NetworkRoute nr)) return 0;
        List<Id<Link>> ids = fullPathIds(nr);
        double sum = 0;
        // The vehicle enters traffic at the END of the route's start link (the
        // car-interaction link), so the start link is not actually traversed and
        // must not count toward the free-flow baseline.
        for (int i = 1; i < ids.size(); i++) {
            Link l = network.getLinks().get(ids.get(i));
            if (l != null) sum += l.getLength() / Math.max(l.getFreespeed(), 1e-9);
        }
        return sum;
    }

    private List<Id<Link>> fullPathIds(NetworkRoute nr) {
        List<Id<Link>> ids = new ArrayList<>();
        Id<Link> start = nr.getStartLinkId();
        if (start != null) ids.add(start);
        for (Id<Link> id : nr.getLinkIds()) {
            if (ids.isEmpty() || !ids.get(ids.size() - 1).equals(id)) ids.add(id);
        }
        Id<Link> end = nr.getEndLinkId();
        if (end != null && (ids.isEmpty() || !ids.get(ids.size() - 1).equals(end))) ids.add(end);
        return ids;
    }

    public void resetForDay() {
        personTrips.clear();
        linkMetrics.reset();
        for (GroupCounters c : counters) {
            c.started.set(0);
            c.completed.set(0);
            c.stuck.set(0);
            c.totalDuration.reset();
            c.totalDelay.reset();
        }
    }

    /** Snapshot of the day's measurements, for metrics.jsonl. */
    public Map<String, Object> snapshot(int day) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("day", day);
        Map<String, Object> groups = new LinkedHashMap<>();
        for (InformationModel.Group g : InformationModel.Group.values()) {
            GroupCounters c = counters[g.ordinal()];
            long planned = c.planned.get();
            long started = c.started.get();
            long completed = c.completed.get();
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("planned", planned);
            gm.put("started", started);
            gm.put("completed", completed);
            gm.put("not_started", Math.max(0, planned - started));
            gm.put("incomplete", Math.max(0, started - completed));
            gm.put("stuck", c.stuck.get());
            gm.put("completion_rate", planned == 0 ? 0.0 : (double) completed / planned);
            gm.put("total_travel_time", c.totalDuration.sum());
            gm.put("avg_travel_time", completed == 0 ? 0.0 : c.totalDuration.sum() / completed);
            gm.put("total_delay", c.totalDelay.sum());
            gm.put("avg_delay", completed == 0 ? 0.0 : c.totalDelay.sum() / completed);
            groups.put(g.name().toLowerCase(), gm);
        }
        root.put("groups", groups);

        List<Map<String, Object>> hourly = linkMetrics.snapshot();
        Map<String, double[]> sums = new LinkedHashMap<>();
        for (Map<String, Object> row : hourly) {
            double[] sum = sums.computeIfAbsent((String) row.get("link_id"), k -> new double[2]);
            sum[0] += ((Number) row.get("total_travel_time")).doubleValue();
            sum[1] += ((Number) row.get("completed")).doubleValue();
        }
        Map<String, Double> links = new LinkedHashMap<>();
        sums.forEach((id, sum) -> {
            Link link = network.getLinks().get(Id.createLinkId(id));
            if (sum[1] > 0) links.put(id, sum[0] / sum[1] / (link.getLength() / link.getFreespeed()));
        });
        root.put("link_metrics_version", 2);
        root.put("link_bin_seconds", LinkTrafficMetrics.BIN_SECONDS);
        root.put("link_hourly", hourly);
        root.put("link_delay_ratio", links);
        return root;
    }

    /** Fold completed-trip durations/delays into the group totals for the day. */
    public void foldCompletedTrips() {
        for (Map.Entry<Id<Person>, PersonTrip> e : personTrips.entrySet()) {
            InformationModel.Group g = infoModel.groupOf(e.getKey());
            PersonTrip t = e.getValue();
            Map<String, Object> j = t.toJson();
            boolean outOk = (Boolean) j.get("outbound_completed");
            boolean retOk = (Boolean) j.get("return_completed");
            if (outOk) {
                counters[g.ordinal()].totalDuration.add((double) j.get("outbound_duration"));
                counters[g.ordinal()].totalDelay.add((double) j.get("outbound_delay"));
            }
            if (retOk) {
                counters[g.ordinal()].totalDuration.add((double) j.get("return_duration"));
                counters[g.ordinal()].totalDelay.add((double) j.get("return_delay"));
            }
        }
    }
}
