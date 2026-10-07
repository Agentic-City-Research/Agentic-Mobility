package org.matsim.agentic;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.controler.events.*;
import org.matsim.core.controler.listener.*;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

final class MorningLoop implements StartupListener, IterationStartsListener, ReplanningListener, BeforeMobsimListener, AfterMobsimListener,
        IterationEndsListener, ShutdownListener, AutoCloseable {
    private final Scenario scenario;
    private final MorningExperiment settings;
    private final MorningCohort cohort;
    private MorningRoutePolicy policy;
    private final LlmUsageTracker usage;
    private final MorningRequestBudget budget;
    private MorningMemoryUpdater memoryUpdater;
    private MorningManager manager;
    private final MorningTollSystem tolls;
    private ExecutorService pool;
    private final MorningObservations observations;
    private final TravelTimeCalculator travelTimes;
    private final Map<String, Object> manifest;
    private OutputWriter output;
    private int daysCompleted;
    private boolean prepared;
    private int preparedDay = -1;

    MorningLoop(Scenario scenario, MorningExperiment settings, MorningCohort cohort,
                MorningRoutePolicy policy, MorningObservations observations,
                TravelTimeCalculator times, Map<String, Object> manifest) {
        this(scenario, settings, cohort, policy, observations, times, manifest, null);
    }

    MorningLoop(Scenario scenario, MorningExperiment settings, MorningCohort cohort,
                MorningRoutePolicy policy, MorningObservations observations,
                TravelTimeCalculator times, Map<String, Object> manifest, MorningTollSystem tolls) {
        this.scenario = scenario; this.settings = settings; this.cohort = cohort; this.policy = policy;
        this.tolls = tolls;
        if (cohort.information == null) cohort.configureInformation(cohort.controlledPersons, settings.informedShare,
                settings.informationSeed == null ? scenario.getConfig().global().getRandomSeed() : settings.informationSeed,
                settings.tollHistoryBinSeconds);
        if (tolls != null) tolls.setInformation(cohort.information);
        this.observations = observations; travelTimes = times; this.manifest = new LinkedHashMap<>(manifest);
        usage = new LlmUsageTracker(settings.maxRequests);
        budget = new MorningRequestBudget(settings.maxRequests);
    }

    @Override public void notifyStartup(StartupEvent event) {
        try { output = new OutputWriter(Path.of(scenario.getConfig().controller().getOutputDirectory(), "morning"), true); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
        writePreparation(output, cohort, manifest);
        if (tolls != null) {
            tolls.setOutput(output);
            output.appendLine("policies.jsonl", tolls.state().toJson());
        }
        if (settings.fixedTollsEnabled()) output.writeDoc("fixed-toll-policy.json", settings.fixedTollPolicy.inputJson());
        if (settings.managerEnabled) {
            if (tolls == null) throw new IllegalStateException("Manager pricing needs a registered toll event handler");
            var managerConfig = settings.llmConfig.withOutputLimit(settings.managerMaxOutputTokens);
            var environment = MorningManagerEnvironment.from(scenario, settings, manifest);
            var publicEnvironment=new LinkedHashMap<>(environment.context(scenario.getConfig().controller().getFirstIteration(),tolls.state().policy(),settings.managerControls.basic()));
            publicEnvironment.put("pricingControls",settings.managerControls.context(environment));
            output.writeDoc("manager-public-environment.json",publicEnvironment);
            manager = new MorningManager(scenario.getNetwork(), scenario.getConfig().qsim().getEndTime().seconds(),
                    environment,
                    settings.clientFactory.apply(managerConfig, usage), output, budget, settings.repairAttempts, managerConfig.publicModelSettings(),
                    settings.managerUpdateInterval, scenario.getConfig().controller().getLastIteration(),settings.managerControls);
        }
        if (settings.policyName.equals("llm") && !cohort.controlledPersons.isEmpty()) {
            if (policy == null) policy = new MorningLlmPolicy(settings.clientFactory.apply(settings.llmConfig, usage), output,
                    settings.repairAttempts, settings.llmConfig.reasonMaxChars, settings.llmConfig.publicModelSettings(), budget);
            var memoryConfig = settings.llmConfig.withOutputLimit(settings.memoryMaxOutputTokens);
            memoryUpdater = new MorningMemoryUpdater(settings.clientFactory.apply(memoryConfig, usage), output,
                    settings.repairAttempts, settings.memoryMaxChars, memoryConfig.publicModelSettings(), budget);
        }
        if (manager != null || settings.policyName.equals("llm") && !cohort.controlledPersons.isEmpty()) {
            pool = Executors.newFixedThreadPool(settings.concurrency, task -> {
                Thread thread = new Thread(task, "morning-llm"); thread.setDaemon(true); return thread;
            });
        }
    }

    static void writePreparation(OutputWriter output, MorningCohort cohort, Map<String, Object> manifest) {
        output.manifest(manifest);
        if (cohort.information != null) cohort.information.assignments().forEach((person, informed) ->
                output.appendLine("information-groups.jsonl", Map.of("personId", person,
                        "informationGroup", informed ? "informed" : "uninformed", "controlled", cohort.controlledPersons.contains(person))));
        Set<String> odWritten = new HashSet<>();
        Set<String> profilesWritten = new HashSet<>();
        for (var member : cohort.members.values()) {
            output.appendLine("cohort.jsonl", member.roster());
            String personId = member.person.getId().toString();
            if (member.profile != null && profilesWritten.add(personId))
                output.appendLine("profiles.jsonl", Map.of("personId", personId, "profile", member.profile));
            var first = member.candidates.getFirst().linkIds();
            String od = first.getFirst() + ">" + first.getLast();
            if (odWritten.add(od)) {
                var diagnostics = cohort.generator.diagnostics(od);
                output.appendLine("candidates.jsonl", Map.of("od", od, "routes", diagnostics.get("routes")));
                output.appendLine("candidate-quality.jsonl", diagnostics);
            }
        }
        output.flush();
    }

    @Override public void notifyIterationStarts(IterationStartsEvent event) {
        int day = event.getIteration(), first = scenario.getConfig().controller().getFirstIteration();
        cohort.information.beginDay(day);
        if (tolls != null) {
            tolls.reset(day);
            boolean safetyWithdrawal=false;
            if(manager!=null) {
                var rollback=manager.automaticRollback(day,tolls.state());
                if(rollback!=null) {
                    tolls.setPolicy(rollback,tolls.state().version()+1);manager.policyApplied(day,rollback,true);
                    output.appendLine("policies.jsonl",tolls.state().toJson());safetyWithdrawal=true;
                }
            }
            boolean scheduled=manager!=null && MorningManager.updateDay(day,first,settings.managerUpdateInterval);
            if(scheduled && manager.inCooldown(day)) output.appendLine("manager-safety-skipped-updates.jsonl",
                    Map.of("day",day,"reason","automatic_withdrawal_cooldown","policyId",tolls.state().policyId(),"apiCallMade",false));
            if (scheduled && !manager.inCooldown(day)) {
                var result = LlmDecisionBatch.run(pool,
                        List.<Callable<MorningManager.Result>>of(() -> manager.update(day, first, tolls.state())), List.of("manager"),
                        Duration.ofSeconds(settings.stallSeconds), day, "manager", count -> {
                            output.progress(Map.of("day", day, "phase", "manager", "llm_completed", count, "llm_total", 1,
                                    "daysCompleted", daysCompleted, "apiCalls", usage.requests()));
                            output.apiUsage(usageSummary());
                        }).getFirst();
                if (result.accepted()) {
                    tolls.setPolicy(result.policy(), tolls.state().version() + 1);
                    manager.policyApplied(day,result.policy(),false);
                    output.appendLine("policies.jsonl", tolls.state().toJson());
                }
            }
            boolean fixedActivation = settings.fixedTollsEnabled() && tolls.state().version() == 0
                    && day >= settings.fixedTollPolicy.policy().day();
            if (fixedActivation) {
                tolls.setPolicy(settings.fixedTollPolicy.policy(), 1);
                output.appendLine("policies.jsonl", tolls.state().toJson());
            }
            output.appendLine("policy-applications.jsonl", Map.of("day", day, "simulationDay", day - first + 1,
                    "policyId", tolls.state().policyId(), "policyVersion", tolls.state().version(),
                    "policyFingerprint", tolls.state().policy().fingerprint(),
                    "effectiveFromDay", tolls.state().policy().day(), "pricingMode", settings.pricingMode(),
                    "fixedPolicyActivatedToday", settings.fixedTollsEnabled() && day == settings.fixedTollPolicy.policy().day(),
                    "automaticSafetyWithdrawalToday",safetyWithdrawal,
                    "scheduledUpdate", manager != null && MorningManager.updateDay(day, first, settings.managerUpdateInterval)));
            output.flush();
        }
        // The first iteration has no ReplanningEvent. PrepareForSim has already routed it.
        if (settings.overlaysChoices() && event.getIteration() == scenario.getConfig().controller().getFirstIteration())
            prepareForDay(event.getIteration());
    }

    @Override public void notifyReplanning(ReplanningEvent event) {
        // Native core PlansReplanningImpl runs before ordinary ReplanningListeners.
        // This overlay therefore follows both selection and ReRoute, but precedes
        // the core BeforeMobsim plans dump. Listener priority cannot cross core/ordinary lists.
        if (settings.overlaysChoices()) prepareForDay(event.getIteration());
    }

    @Override public void notifyBeforeMobsim(BeforeMobsimEvent event) {
        if (!settings.overlaysChoices()) prepareForDay(event.getIteration());
        if (!prepared || preparedDay != event.getIteration()) throw new IllegalStateException("Day routes were not prepared");
        output.progress(Map.of("day", event.getIteration(), "phase", "mobsim", "daysCompleted", daysCompleted,
                "cohortTrips", cohort.members.size(), "apiCalls", usage.requests()));
        output.flush();
    }

    private void prepareForDay(int day) {
        prepared = false;
        if (settings.fixesTimes()) cohort.restorePlannedTimes();
        Map<MorningCohort.Member, MorningRoutePolicy.Context> contexts = new LinkedHashMap<>();
        for (var member : cohort.members.values()) {
            var trip = member.currentTrip();
            if (!trip.getOriginActivity().getType().equals(member.originPurpose)
                    || !trip.getDestinationActivity().getType().equals(member.destinationPurpose))
                throw new IllegalStateException("Trip purpose changed: " + member.key);
            Leg car = MorningCohort.carLeg(trip);
            if (!(car.getRoute() instanceof NetworkRoute routed)) throw new IllegalStateException("Car trip not routed: " + member.key);
            member.nonCarSeconds = trip.getLegsOnly().stream().filter(l -> !"car".equals(l.getMode()))
                    .mapToDouble(l -> l.getTravelTime().orElse(0)).sum();
            if (member.controlled) {
                var endpoints = member.candidates.getFirst().linkIds();
                if (!routed.getStartLinkId().equals(endpoints.getFirst()) || !routed.getEndLinkId().equals(endpoints.getLast()))
                    throw new IllegalStateException("Candidate OD differs from routed OD for " + member.key);
                var context = MorningPromptRenderer.order(context(day, member), settings.candidateOrder, settings.candidateOrderSeed);
                MorningPromptRenderer.validate(context);
                contexts.put(member, context);
            } else {
                member.routeId = "native";
                member.selectedDeparture = trip.getOriginActivity().getEndTime().seconds();
                member.memory.chose("native");
            }
        }
        var controlled = new ArrayList<>(contexts.keySet());
        List<MorningRoutePolicy.Decision> decisions;
        if (pool != null) {
            var tasks = controlled.stream().<Callable<MorningRoutePolicy.Decision>>map(m -> () -> policy.choose(contexts.get(m))).toList();
            decisions = LlmDecisionBatch.run(pool, tasks, controlled.stream().map(m -> m.key).toList(),
                    Duration.ofSeconds(settings.stallSeconds), day, "morning_routes", count -> {
                        output.progress(Map.of("day", day, "phase", "llm_decisions", "daysCompleted", daysCompleted,
                                "llm_completed", count, "llm_total", controlled.size(), "last_progress_at", Instant.now().toString(),
                                "llm_stall_seconds", settings.stallSeconds, "apiCalls", usage.requests()));
                        output.apiUsage(usageSummary());
                    });
        } else decisions = controlled.stream().map(m -> policy.choose(contexts.get(m))).toList();
        int sampled = 0;
        for (int index = 0; index < controlled.size(); index++) {
            var member = controlled.get(index);
            var trip = member.currentTrip();
            Leg car = MorningCohort.carLeg(trip);
            NetworkRoute routed = (NetworkRoute) car.getRoute();
            var context = contexts.get(member);
            var decision = MorningRoutePolicy.validate(context, decisions.get(index));
            member.selectedDeparture = decision.departureSeconds() == null ? member.plannedDeparture : decision.departureSeconds();
            trip.getOriginActivity().setEndTime(member.selectedDeparture);
            // The controlled choice can replace native time mutation; refresh leg timestamps.
            double legDeparture = member.selectedDeparture;
            for (var leg : trip.getLegsOnly()) {
                leg.setDepartureTime(legDeparture);
                legDeparture += "car".equals(leg.getMode()) ? member.candidates.stream()
                        .filter(c -> c.id().equals(decision.routeId())).findFirst().orElseThrow().freeFlowTravelTime() : leg.getTravelTime().orElse(0);
            }
            String nativeFingerprint = fingerprint(routed);
            RouteCandidate selected = member.candidates.stream().filter(c -> c.id().equals(decision.routeId())).findFirst().orElseThrow();
            var ids = selected.linkIds();
            NetworkRoute replacement = scenario.getPopulation().getFactory().getRouteFactories()
                    .createRoute(NetworkRoute.class, ids.getFirst(), ids.getLast());
            replacement.setLinkIds(ids.getFirst(), ids.size() > 2 ? ids.subList(1, ids.size() - 1) : List.of(), ids.getLast());
            replacement.setVehicleId(routed.getVehicleId());
            replacement.setDistance(distance(selected));
            replacement.setTravelTime(selected.freeFlowTravelTime());
            car.setRoute(replacement);
            car.setTravelTime(selected.freeFlowTravelTime());
            member.person.getSelectedPlan().setScore(null); // The modified composite plan has not been scored yet.
            member.routeId = selected.id();
            Map<String, Object> transition = new LinkedHashMap<>();
            var previous = member.memory.recent();
            var last = previous.isEmpty() ? null : previous.getLast();
            transition.put("day", day); transition.put("tripId", member.key); transition.put("personId", member.person.getId().toString());
            transition.put("previousExperience", last); transition.put("selectedRouteId", selected.id());
            transition.put("selectedDepartureSeconds", member.selectedDeparture);
            transition.put("departureOptions", context.departureOptions());
            transition.put("switchedFromLastChoice", last == null ? null : !last.routeId().equals(selected.id()));
            transition.put("currentForecasts", context.candidates());
            transition.put("previousForecasts", member.previousContext == null ? null : member.previousContext.candidates());
            output.appendLine("choice-transitions.jsonl", transition);
            member.previousContext = context;
            member.memory.chose(selected.id());
            output.appendLine("decisions.jsonl", Map.of("day", day, "tripId", member.key,
                    "routeId", selected.id(), "plannedDepartureSeconds", member.plannedDeparture, "reason", decision.reason(),
                    "selectedDepartureSeconds", member.selectedDeparture,
                    "policy", settings.policyName, "nativeRouteFingerprint", nativeFingerprint,
                    "appliedRouteFingerprint", fingerprint(replacement), "informationGroup", member.informationGroup));
            if (sampled++ < settings.contextSamples) output.appendLine("context-samples.jsonl", context);
        }
        for (var member : cohort.members.values()) {
            Leg car = MorningCohort.carLeg(member.currentTrip());
            member.timeSwitched = member.previousSelectedDeparture != null && member.previousSelectedDeparture != member.selectedDeparture;
            member.previousSelectedDeparture = member.selectedDeparture;
            NetworkRoute actual = (NetworkRoute) car.getRoute();
            List<org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link>> ids = new ArrayList<>();
            ids.add(actual.getStartLinkId()); ids.addAll(actual.getLinkIds());
            if (!actual.getStartLinkId().equals(actual.getEndLinkId()) || !actual.getLinkIds().isEmpty()) ids.add(actual.getEndLinkId());
            member.actualRouteFreeFlow = ids.stream().skip(1).map(scenario.getNetwork().getLinks()::get)
                    .mapToDouble(MatsimOdCandidates::freeFlow).sum();
            String fingerprint = ids.toString();
            member.switched = member.previousFingerprint != null && !member.previousFingerprint.equals(fingerprint);
            member.previousFingerprint = fingerprint;
        }
        // Research traffic accumulators do not provide traveler forecasts.
        travelTimes.reset(day);
        observations.reset(day);
        output.progress(Map.of("day", day, "phase", "routes_prepared", "daysCompleted", daysCompleted,
                "cohortTrips", cohort.members.size(), "apiCalls", usage.requests()));
        output.apiUsage(usageSummary());
        output.flush();
        prepared = true;
        preparedDay = day;
    }

    static String fingerprint(NetworkRoute route) {
        var ids = new ArrayList<>(); ids.add(route.getStartLinkId()); ids.addAll(route.getLinkIds());
        if (!route.getStartLinkId().equals(route.getEndLinkId()) || !route.getLinkIds().isEmpty()) ids.add(route.getEndLinkId());
        return ids.toString();
    }

    private MorningRoutePolicy.Context context(int day, MorningCohort.Member member) {
        var options = optionsForDeparture(day, member, member.plannedDeparture);
        var departures = settings.policyName.equals("llm") ? member.departureOptions.stream()
                .map(t -> new MorningRoutePolicy.DepartureOption(t, optionsForDeparture(day, member, t))).toList()
                : List.<MorningRoutePolicy.DepartureOption>of();
        return new MorningRoutePolicy.Context(day, member.key, member.person.getId().toString(), member.originPurpose,
                member.destinationPurpose, member.plannedDeparture, member.deadline, member.originalAttributes,
                Map.of("activities", member.originalSchedule, "deadlineIsHardConstraint", false,
                        "personalPreferencesSource", member.profile == null ? "input attributes only" : "input attributes plus fixed synthetic legacy profile; no numeric tolerance/value-of-time inferred",
                        "costUnits", "scenario monetary units, not an asserted real-world currency"),
                options, member.memory.recent(), member.profile, member.memory.summary(), member.memory.summaryDay(),
                settings.policyName.equals("llm") ? cohort.information.visibleForLlm(member.person.getId().toString())
                        : cohort.information.visible(member.person.getId().toString(), day), departures);
    }

    private List<MorningRoutePolicy.Option> optionsForDeparture(int day, MorningCohort.Member member, double departure) {
        var trip = member.currentTrip();
        double accessSeconds = 0;
        for (Leg leg : trip.getLegsOnly()) {
            if ("car".equals(leg.getMode())) break;
            accessSeconds += leg.getTravelTime().orElse(0);
        }
        double carDeparture = departure + accessSeconds;
        List<MorningRoutePolicy.Option> options = new ArrayList<>();
        double monetaryRate = scenario.getConfig().scoring().getModes().get("car").getMonetaryDistanceRate();
        for (var candidate : member.candidates) {
            var remembered = member.memory.route(candidate.id());
            double time = carDeparture;
            var entryTimes = new ArrayList<Double>(); entryTimes.add(time);
            for (var id : candidate.linkIds().subList(1, candidate.linkIds().size())) {
                var link = scenario.getNetwork().getLinks().get(id);
                entryTimes.add(time);
                time += MatsimOdCandidates.freeFlow(link);
            }
            double estimate = time - carDeparture;
            var links = candidate.linkIds().stream().map(Object::toString).toList();
            var knowledge = settings.policyName.equals("llm") ? cohort.information.pricingForLlm(member.person.getId().toString(), links, entryTimes,
                    -monetaryRate * distance(candidate), tolls, day) : cohort.information.pricing(member.person.getId().toString(), links, entryTimes,
                    -monetaryRate * distance(candidate), tolls, day);
            Double estimatedToll = knowledge.unknownLinks().isEmpty() ? knowledge.knownTollSubtotal() : null;
            Double estimatedCost = estimatedToll == null ? null : knowledge.baseMonetaryCost() + estimatedToll;
            boolean announcement = tolls != null && cohort.information.informed(member.person.getId().toString());
            options.add(new MorningRoutePolicy.Option(candidate.id(), links,
                    estimate, departure + member.nonCarSeconds + estimate, distance(candidate),
                    estimatedCost, "static_free_flow", remembered.completedCount(), remembered.chosenCount(),
                    null, remembered.lastExperiencedDay(), estimatedToll,
                    announcement ? tolls.state().policyId() : null, announcement ? tolls.state().version() : 0, knowledge));
        }
        return List.copyOf(options);
    }

    private double distance(RouteCandidate route) {
        return route.linkIds().stream().skip(1).map(scenario.getNetwork().getLinks()::get).mapToDouble(l -> l.getLength()).sum();
    }

    @Override public void notifyAfterMobsim(AfterMobsimEvent event) {
        if (!prepared) return;
        int day = event.getIteration(), completedCar = 0, completedOd = 0, departed = 0, stuck = 0, changed = 0;
        int workCompleted = 0, workLate = 0, actuallyInPeak = 0;
        List<Double> carTimes = new ArrayList<>(), doorTimes = new ArrayList<>(), delays = new ArrayList<>();
        for (var member : cohort.members.values()) {
            var result = observations.trips.get(member.key);
            if (result.departure != null) { departed++; if (result.departure >= settings.peakStart && result.departure < settings.peakEnd) actuallyInPeak++; }
            if (result.stuck) stuck++;
            if (member.switched) changed++;
            Double carSeconds = result.carSeconds(), doorSeconds = result.doorSeconds();
            if (member.controlled) {
                var selected = member.candidates.stream().filter(c -> c.id().equals(member.routeId)).findFirst().orElseThrow();
                var expected = selected.linkIds().stream().map(Object::toString).toList();
                var executed = result.executedLinks;
                boolean prefix = executed.size() <= expected.size() && expected.subList(0, Math.min(expected.size(), executed.size())).equals(executed);
                boolean completeMatch = expected.equals(executed);
                Map<String, Object> check = new LinkedHashMap<>();
                check.put("day", day); check.put("tripId", member.key); check.put("routeId", member.routeId);
                check.put("expectedLinks", expected); check.put("executedLinks", executed);
                check.put("observedInTraffic", !executed.isEmpty()); check.put("followsSelectedRoutePrefix", prefix);
                check.put("completedCar", carSeconds != null); check.put("completedRouteMatches", carSeconds == null ? null : completeMatch);
                check.put("originalPlannedDepartureSeconds", member.plannedDeparture);
                check.put("selectedDepartureSeconds", member.selectedDeparture);
                check.put("planActivityEndSeconds", member.currentTrip().getOriginActivity().getEndTime().seconds());
                check.put("actualDepartureSeconds", result.departure);
                check.put("departureDelaySeconds", result.departure == null ? null : result.departure - member.selectedDeparture);
                check.put("planTimeMatchesChoice", member.currentTrip().getOriginActivity().getEndTime().seconds() == member.selectedDeparture);
                output.appendLine("executed-route-checks.jsonl", check);
                if (!prefix || carSeconds != null && !completeMatch)
                    throw new IllegalStateException("Executed route differs from selected candidate: " + member.key + " on day " + day);
                if (member.currentTrip().getOriginActivity().getEndTime().seconds() != member.selectedDeparture
                        || result.departure != null && result.departure + 1 < member.selectedDeparture)
                    throw new IllegalStateException("Selected departure not applied: " + member.key + " on day " + day);
            }
            Double delay = carSeconds == null ? null : Math.max(0, carSeconds - member.actualRouteFreeFlow);
            Double lateness = result.arrival == null || member.deadline == null ? null : Math.max(0, result.arrival - member.deadline);
            if (carSeconds != null) {
                completedCar++; carTimes.add(carSeconds); delays.add(delay);
            }
            // Failed/unfinished trips remain visible to future policies, but must
            // never enter completed-travel-time statistics as zero or an invented duration.
            var experience = new MorningMemory.Experience(day, member.routeId, carSeconds, result.arrival, lateness,
                    result.stuck, result.departure, doorSeconds, tolls == null ? null : result.tollPaid,
                    member.selectedDeparture, result.tollReceipts.stream()
                    .map(r -> new MorningRoutePolicy.TollObservation(day, r.linkId(), r.time(), r.amount())).toList());
            member.memory.observe(experience);
            Map<String, Object> archive = new LinkedHashMap<>();
            archive.put("day", day); archive.put("personId", member.person.getId().toString()); archive.put("tripId", member.key);
            archive.put("controlled", member.controlled); archive.put("decisionMaker", member.controlled ? settings.policyName : "native");
            archive.put("informationGroup", member.informationGroup);
            archive.put("experience", experience); archive.put("routeFingerprint", member.previousFingerprint);
            archive.put("plannedDepartureSeconds", member.plannedDeparture); archive.put("latestArrivalSeconds", member.deadline);
            archive.put("selectedDepartureSeconds", member.selectedDeparture);
            archive.put("plannedArrivalSeconds", member.plannedArrival);
            archive.put("completedCar", carSeconds != null); archive.put("completedOd", doorSeconds != null);
            archive.put("excessCarSeconds", delay);
            if (tolls != null) {
                archive.put("tollPaid", result.tollPaid); archive.put("tollReceipts", result.tollReceipts);
                archive.put("executedPolicyId", tolls.state().policyId()); archive.put("executedPolicyVersion", tolls.state().version());
                archive.put("executedPolicyFingerprint", tolls.state().policy().fingerprint());
            }
            output.experience(archive); // Every cohort trip, every day, even when trips.jsonl is thinned.
            if (doorSeconds != null) { completedOd++; doorTimes.add(doorSeconds); }
            if (member.destinationPurpose.equals("work") && result.arrival != null && member.deadline != null) {
                workCompleted++; if (lateness > 0) workLate++;
            }
            if (day % settings.tripOutputInterval == 0 || day == scenario.getConfig().controller().getLastIteration()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("day", day); row.put("tripId", member.key); row.put("routeId", member.routeId);
                row.put("controlled", member.controlled); row.put("decisionMaker", member.controlled ? settings.policyName : "native");
                row.put("informationGroup", member.informationGroup);
                row.put("routeFingerprint", member.previousFingerprint); row.put("destinationPurpose", member.destinationPurpose);
                row.put("plannedDepartureSeconds", member.plannedDeparture); row.put("actualDepartureSeconds", result.departure);
                row.put("selectedDepartureSeconds", member.selectedDeparture);
                row.put("plannedArrivalSeconds", member.plannedArrival);
                row.put("carDepartureSeconds", result.carDeparture); row.put("carArrivalSeconds", result.carArrival);
                row.put("arrivalSeconds", result.arrival); row.put("carSeconds", carSeconds); row.put("doorSeconds", doorSeconds);
                row.put("selectedRouteFreeFlowSeconds", member.actualRouteFreeFlow); row.put("excessCarSeconds", delay);
                row.put("latestArrivalSeconds", member.deadline); row.put("lateSeconds", lateness); row.put("stuck", result.stuck);
                row.put("completedCar", carSeconds != null); row.put("completedOd", doorSeconds != null);
                if (tolls != null) { row.put("tollPaid", result.tollPaid); row.put("tollReceipts", result.tollReceipts); }
                output.appendLine("trips.jsonl", row);
            }
        }
        // All factual outcomes are stored before any reflection worker is dispatched.
        output.flush();
        updateMemories(day);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("day", day); metrics.put("cohortTrips", cohort.members.size()); metrics.put("departedTrips", departed);
        metrics.put("actualDeparturesInPeak", actuallyInPeak); metrics.put("completedCarTrips", completedCar);
        metrics.put("completedOdTrips", completedOd); metrics.put("uncompletedOdTrips", cohort.members.size() - completedOd);
        metrics.put("completionRate", cohort.members.isEmpty() ? null : (double) completedOd / cohort.members.size());
        metrics.put("stuckTrips", stuck); metrics.put("meanCarSeconds", mean(carTimes)); metrics.put("p95CarSeconds", p95(carTimes));
        metrics.put("meanDoorSeconds", mean(doorTimes)); metrics.put("meanExcessCarSeconds", mean(delays));
        metrics.put("routeSwitches", changed); metrics.put("dsr", daysCompleted == 0 || cohort.members.isEmpty() ? null : (double) changed / cohort.members.size());
        metrics.put("departureTimeSwitches", cohort.members.values().stream().filter(m -> m.controlled && m.timeSwitched).count());
        metrics.put("nativeDepartureTimeSwitches", cohort.members.values().stream().filter(m -> !m.controlled && m.timeSwitched).count());
        metrics.put("allDepartureTimeSwitches", cohort.members.values().stream().filter(m -> m.timeSwitched).count());
        var nativeDepartureShifts = cohort.members.values().stream().filter(m -> !m.controlled)
                .mapToDouble(m -> m.selectedDeparture - m.plannedDeparture).summaryStatistics();
        metrics.put("meanNativeSelectedDepartureShiftSeconds", nativeDepartureShifts.getCount() == 0 ? null : nativeDepartureShifts.getAverage());
        metrics.put("nativeDeparturesShiftedFromOriginal", cohort.members.values().stream()
                .filter(m -> !m.controlled && m.selectedDeparture != m.plannedDeparture).count());
        var departureShifts = cohort.members.values().stream().filter(m -> m.controlled)
                .mapToDouble(m -> m.selectedDeparture - m.plannedDeparture).summaryStatistics();
        metrics.put("meanSelectedDepartureShiftSeconds", departureShifts.getCount() == 0 ? null : departureShifts.getAverage());
        metrics.put("completedWorkTripsWithDeadline", workCompleted); metrics.put("lateWorkTrips", workLate);
        metrics.put("lateWorkFraction", workCompleted == 0 ? null : (double) workLate / workCompleted);
        long workTotal = cohort.members.values().stream().filter(m -> m.destinationPurpose.equals("work") && m.deadline != null).count();
        metrics.put("workTripsWithDeadline", workTotal);
        metrics.put("onTimeWorkFractionAll", workTotal == 0 ? null : (double) (workCompleted - workLate) / workTotal);
        metrics.put("network", observations.networkSummary(scenario.getConfig().qsim().getEndTime().seconds()));
        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("native", groupMetrics(false)); groups.put("controlled", groupMetrics(true));
        metrics.put("informationGroups", Map.of("informed", groupMetrics(null, "informed"),
                "uninformed", groupMetrics(null, "uninformed")));
        metrics.put("groups", groups); metrics.put("apiCalls", usage.requests());
        if (tolls != null) {
            metrics.put("pricing", tolls.summary()); metrics.put("policyId", tolls.state().policyId());
            metrics.put("policyVersion", tolls.state().version()); tolls.writePersonalTotals();
        }
        metrics.put("publicCarTrips",observations.publicCarTrips());
        var linkHours = observations.links.snapshot();
        metrics.put("fullDayLinkTraffic", MorningManager.dailyTraffic(day, linkHours));
        output.metric(metrics);
        if(manager!=null && daysCompleted==0) manager.captureReferenceDemand(scenario,day,tolls.state());
        if (manager != null) manager.observe(day, linkHours, tolls.state(),observations.publicCarTrips());
        for (var link : linkHours) {
            Map<String, Object> row = new LinkedHashMap<>(link); row.put("day", day);
            output.appendLine("link-hours.jsonl", row);
        }
        daysCompleted++;
    }

    private void updateMemories(int day) {
        if (memoryUpdater == null) return;
        var controlled = cohort.members.values().stream().filter(m -> m.controlled).toList();
        var contexts = controlled.stream().map(m -> new MorningMemoryUpdater.Context(day, m.key, m.person.getId().toString(),
                m.destinationPurpose, m.deadline, m.originalAttributes, m.profile, m.memory.summary(), m.memory.summaryDay(),
                m.memory.recent(MorningMemoryUpdater.RECENT_EXPERIENCE_LIMIT), m.candidates.stream().map(RouteCandidate::id).toList(),
                m.informationGroup, List.of())).toList();
        var tasks = contexts.stream().<Callable<MorningMemoryUpdater.Update>>map(c -> () -> memoryUpdater.update(c)).toList();
        var updates = LlmDecisionBatch.run(pool, tasks, controlled.stream().map(m -> m.key).toList(),
                Duration.ofSeconds(settings.stallSeconds), day, "memory_updates", count -> {
                    output.progress(Map.of("day", day, "phase", "memory_updates", "daysCompleted", daysCompleted,
                            "llm_completed", count, "llm_total", controlled.size(), "last_progress_at", Instant.now().toString(),
                            "llm_stall_seconds", settings.stallSeconds, "apiCalls", usage.requests()));
                    output.apiUsage(usageSummary());
                });
        // Commit only when the entire batch is valid; retries do not mutate live summaries.
        for (int i = 0; i < controlled.size(); i++) {
            var member = controlled.get(i); var update = updates.get(i); var context = contexts.get(i);
            member.memory.updateSummary(day, update.memory());
            var row = new LinkedHashMap<String, Object>();
            row.put("day", day); row.put("tripId", member.key); row.put("personId", member.person.getId().toString());
            row.put("previousMemory", context.previousMemory()); row.put("previousMemoryDay", context.previousMemoryDay());
            row.put("memory", update.memory()); row.put("version", member.memory.summaryVersion()); row.put("committed", true);
            row.put("repairCount", update.repairCount()); row.put("elapsedMillis", update.elapsedMillis());
            row.put("contextHash", MorningPromptRenderer.hash(Json.write(context)));
            row.put("modelSettings", memoryUpdater.modelSettings());
            output.appendLine("memory-updates.jsonl", row);
        }
        output.flush();
    }

    private Map<String, Object> groupMetrics(boolean controlled) {
        return groupMetrics(Boolean.valueOf(controlled), null);
    }

    private Map<String, Object> groupMetrics(Boolean controlled, String informationGroup) {
        int planned = 0, departed = 0, complete = 0, stuck = 0, work = 0, workComplete = 0, late = 0, switches = 0;
        int singleCandidate = 0, multiCandidate = 0, multiCandidateSwitches = 0;
        List<Double> cars = new ArrayList<>(), doors = new ArrayList<>(), delays = new ArrayList<>();
        for (var member : cohort.members.values()) {
            if (controlled != null && member.controlled != controlled || informationGroup != null
                    && (!cohort.information.participates(member.person.getId().toString()) || !member.informationGroup.equals(informationGroup))) continue;
            planned++; var trip = observations.trips.get(member.key);
            if (member.controlled) {
                if (member.candidates.size() == 1) singleCandidate++;
                else { multiCandidate++; if (member.switched) multiCandidateSwitches++; }
            }
            if (trip.departure != null) departed++;
            if (trip.stuck) stuck++;
            if (member.switched) switches++;
            if (trip.carSeconds() != null) { cars.add(trip.carSeconds()); delays.add(Math.max(0, trip.carSeconds() - member.actualRouteFreeFlow)); }
            if (trip.doorSeconds() != null) { complete++; doors.add(trip.doorSeconds()); }
            if (member.destinationPurpose.equals("work") && member.deadline != null) {
                work++;
                if (trip.arrival != null) { workComplete++; if (trip.arrival > member.deadline) late++; }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cohortTrips", planned); result.put("departedTrips", departed); result.put("completedOdTrips", complete);
        result.put("uncompletedOdTrips", planned - complete); result.put("stuckTrips", stuck);
        result.put("completionRate", planned == 0 ? null : (double) complete / planned);
        result.put("meanCarSeconds", mean(cars)); result.put("meanDoorSeconds", mean(doors)); result.put("meanExcessCarSeconds", mean(delays));
        result.put("dsr", daysCompleted == 0 || planned == 0 ? null : (double) switches / planned);
        if (Boolean.TRUE.equals(controlled)) {
            result.put("singleCandidateTrips", singleCandidate); result.put("multiCandidateTrips", multiCandidate);
            result.put("multiCandidateRouteSwitches", multiCandidateSwitches);
            result.put("dsrMultiCandidate", daysCompleted == 0 || multiCandidate == 0 ? null : (double) multiCandidateSwitches / multiCandidate);
        }
        result.put("workTripsWithDeadline", work); result.put("completedWorkTripsWithDeadline", workComplete); result.put("lateWorkTrips", late);
        result.put("lateWorkFractionAmongArrivals", workComplete == 0 ? null : (double) late / workComplete);
        result.put("onTimeWorkFractionAll", work == 0 ? null : (double) (workComplete - late) / work);
        return result;
    }

    private static Double mean(List<Double> values) { return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(); }
    private static Double p95(List<Double> values) {
        if (values.isEmpty()) return null;
        values.sort(Double::compareTo); return values.get(Math.max(0, (int) Math.ceil(values.size() * .95) - 1));
    }
    @Override public void notifyIterationEnds(IterationEndsEvent event) {
        output.progress(Map.of("day", event.getIteration(), "daysCompleted", daysCompleted, "phase", "iteration_complete", "apiCalls", usage.requests()));
        output.apiUsage(usageSummary());
        manifest.put("apiCalls", usage.requests()); output.manifest(manifest);
        output.flush();
    }
    @Override public void notifyShutdown(ShutdownEvent event) {
        if (output != null) {
            if (manager != null) manager.writeFinalEvaluation(scenario.getConfig().controller().getFirstIteration());
            cohort.information.assignments().forEach((person, informed) -> output.appendLine("payment-memory-final.jsonl",
                    Map.of("personId", person, "informationGroup", cohort.information.group(person),
                            "tollHistoryBinSeconds", cohort.information.binSeconds(), "observations", cohort.information.personalPayments(person))));
            for (var member : cohort.members.values()) if (member.controlled) {
                output.appendLine("route-use.jsonl", Map.of("tripId", member.key, "personId", member.person.getId().toString(),
                        "experiences", member.memory.size(), "routes", member.memory.usage()));
                var row = new LinkedHashMap<String, Object>();
                row.put("tripId", member.key); row.put("personId", member.person.getId().toString());
                row.put("memory", member.memory.summary()); row.put("day", member.memory.summaryDay());
                row.put("version", member.memory.summaryVersion()); row.put("experiences", member.memory.size());
                output.appendLine("memory-final.jsonl", row);
            }
            Map<String, Object> status = new LinkedHashMap<>(Map.of("daysCompleted", daysCompleted, "complete", !event.isUnexpected()
                    && daysCompleted == scenario.getConfig().controller().getLastIteration()
                    - scenario.getConfig().controller().getFirstIteration() + 1,
                    "unexpectedShutdown", event.isUnexpected(), "apiCalls", usage.requests()));
            event.getException().ifPresent(error -> status.put("error", error.toString()));
            output.status(status); output.apiUsage(usageSummary());
            manifest.put("apiCalls", usage.requests()); output.manifest(manifest);
        }
        close();
    }

    @Override public void close() {
        if (pool != null) pool.shutdownNow();
        if (output != null) output.close();
    }

    private Map<String, Object> usageSummary() {
        var result = new LinkedHashMap<>(usage.toJson());
        result.put("attemptedDecisionCalls", policy instanceof MorningLlmPolicy llm ? llm.attemptedCalls() : 0L);
        result.put("attemptedMemoryCalls", memoryUpdater == null ? 0L : memoryUpdater.attemptedCalls());
        result.put("attemptedManagerCalls", manager == null ? 0L : manager.attemptedCalls());
        result.put("attemptedTotalCalls", budget.attemptedCalls());
        result.put("requestBudgetUnit", "client.complete invocations including output repair/task retry; HTTP-level retries are internal");
        return result;
    }
}
