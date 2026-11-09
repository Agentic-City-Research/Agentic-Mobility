package org.matsim.agentic;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;

import java.util.*;

/** Public model parameters and car topology only; no person-level input is retained. */
final class MorningManagerEnvironment {
    static final String VERSION = "morning-public-environment-v4-native-time";
    private final Network network;
    private final List<List<Link>> chains;
    private final Map<String, Object> sampling, routingCosts, behavior;
    private final Double minutesPerMoney;
    private final int populationSize;
    private final double flowFactor;
    private record NativeStrategy(String name, double weight, int disableAfter) {}
    private final List<NativeStrategy> nativeStrategies;
    private final int firstIteration, innovationDisableAfter, controlledPersons;
    private final boolean fixedTimes;
    private boolean llmDepartureChoice;
    private boolean mnlDepartureChoice;
    private MorningManagerDemand demand = MorningManagerDemand.empty();
    private Integer referencePlanCaptureIteration;

    static MorningManagerEnvironment from(Scenario scenario, MorningExperiment settings, Map<String, Object> manifest) {
        var environment = new MorningManagerEnvironment(scenario.getNetwork(), scenario.getConfig(),
                ((Number) manifest.get("loadedPopulation")).intValue(), scenario.getPopulation().getPersons().size(),
                settings.fixesTimes(), settings.mode, ((Number) manifest.get("controlledPersons")).intValue(),
                (Map<?, ?>) manifest.get("travelerInformation"));
        if (!settings.managerControls.basic()) environment.demand = MorningManagerDemand.from(scenario);
        environment.llmDepartureChoice = settings.policyName.equals("llm") && settings.hybrid() && environment.controlledPersons > 0;
        environment.mnlDepartureChoice = settings.policyName.equals("mnl") && settings.hybrid() && environment.controlledPersons > 0;
        return environment;
    }

    MorningManagerEnvironment(Network network, Config config, int loadedPopulation, int populationSize,
                             boolean fixedTimes, String mode, int controlledPersons, Map<?, ?> information) {
        if (loadedPopulation < populationSize || populationSize < 0 || loadedPopulation < 1
                || controlledPersons < 0 || controlledPersons > populationSize)
            throw new IllegalArgumentException("Invalid public population counts");
        this.network = network; this.populationSize = populationSize;
        this.fixedTimes = fixedTimes; this.controlledPersons = controlledPersons;
        firstIteration = config.controller().getFirstIteration();
        innovationDisableAfter = (int) ((config.controller().getLastIteration() - firstIteration)
                * config.replanning().getFractionOfIterationsToDisableInnovation() + firstIteration);
        nativeStrategies = config.replanning().getStrategySettings().stream()
                .map(s -> new NativeStrategy(s.getStrategyName(), s.getWeight(), s.getDisableAfter())).toList();
        flowFactor = config.qsim().getFlowCapFactor();
        var sample = new LinkedHashMap<String, Object>();
        sample.put("loadedPopulationSize", loadedPopulation); sample.put("populationSize", populationSize);
        sample.put("actualPopulationSamplingRate", (double) populationSize / loadedPopulation);
        sample.put("flowCapacityFactor", flowFactor); sample.put("storageCapacityFactor", config.qsim().getStorageCapFactor());
        sample.put("networkCapacityPeriodSeconds", network.getCapacityPeriod());
        sample.put("networkEffectiveCellSizeMeters", network.getEffectiveCellSize());
        sample.put("capacityMeaning", "Raw capacity is vehicles per network capacity period. Nominal scaled flow capacity is rawCapacity * flowCapacityFactor * 3600 / capacityPeriod; QSim discretization and bottlenecks still apply. Storage uses a separate factor; population sampling and capacity factors need not match.");
        sampling = Collections.unmodifiableMap(sample);

        var car = config.scoring().getModes().get("car");
        double money = config.scoring().getMarginalUtilityOfMoney();
        double time = (config.scoring().getPerforming_utils_hr() - car.getMarginalUtilityOfTraveling()) / 3600;
        minutesPerMoney = time > 0 && money > 0 && Double.isFinite(money / time) ? money / time / 60 : null;
        var costs = new LinkedHashMap<String, Object>();
        costs.put("performingUtilsPerHour", config.scoring().getPerforming_utils_hr());
        costs.put("carTravelingUtilsPerHour", car.getMarginalUtilityOfTraveling());
        costs.put("moneyUtilsPerMonetaryUnit", money); costs.put("routingTimeCostUtilsPerSecond", time);
        costs.put("routingMinutesPerMonetaryUnit", minutesPerMoney);
        costs.put("monetaryUnitsPerRoutingMinute", minutesPerMoney == null ? null : 1 / minutesPerMoney);
        costs.put("carMonetaryDistanceRatePerMeter", car.getMonetaryDistanceRate());
        costs.put("routingDistanceCostUtilsPerMeter", -car.getMonetaryDistanceRate() * money - car.getMarginalUtilityOfDistance());
        costs.put("routingRandomness", config.routing().getRoutingRandomness());
        costs.put("lateArrivalUtilsPerHour", config.scoring().getLateArrival_utils_hr());
        costs.put("interpretation", "Default scoring parameters used by native car routing: time cost=(performing-carTraveling)/3600; added toll cost=moneyUtility*amount. Time equivalence is not real currency calibration or full-plan personal value of time. Native routing randomness applies to distance cost, not the added toll term. A nonpositive time or money coefficient has no positive time equivalence.");
        routingCosts = Collections.unmodifiableMap(costs);

        var strategies = config.replanning().getStrategySettings().stream().map(s -> Map.<String, Object>of(
                "strategy", s.getStrategyName(), "weight", s.getWeight(), "disableAfterIteration", s.getDisableAfter())).toList();
        var publicBehavior = new LinkedHashMap<String, Object>();
        publicBehavior.put("mode", mode); publicBehavior.put("controlledPersons", controlledPersons);
        publicBehavior.put("backgroundPersons", populationSize - controlledPersons);
        publicBehavior.put("nativeStrategies", strategies);
        publicBehavior.put("nativePlanMemorySize", config.replanning().getMaxAgentPlanMemorySize());
        publicBehavior.put("nativeLearningRate", config.scoring().getLearningRate());
        publicBehavior.put("plannedActivityEndTimesFixed", fixedTimes);
        publicBehavior.put("nativeTimeMutationRangeSeconds", config.timeAllocationMutator().getMutationRange());
        publicBehavior.put("nativeTimeMutationAroundInitialEndTimeOnly", config.timeAllocationMutator().isMutateAroundInitialEndTimeOnly());
        publicBehavior.put("tripCancellationEnabled", false); publicBehavior.put("populationAndOdDemandFixed", true);
        publicBehavior.put("actualTiming", "Actual departures/arrivals may still be delayed by preceding activities and congestion; fixed planned times do not guarantee actual times.");
        publicBehavior.put("priceResponse", "Prices can change routes and native plan selection. Where time/mode choice and cancellation are disabled, raising prices cannot actively remove fixed OD trips or induce deliberate departure-time or mode changes. Stored native plans may retain scores from earlier policies until executed again.");
        var visibility = new LinkedHashMap<String, Object>();
        for (String key : List.of("scope", "persons", "informedPersons", "uninformedPersons", "tollHistoryBinSeconds"))
            if (information.containsKey(key)) visibility.put(key, information.get(key));
        visibility.put("nativeRouting", "Persons outside the information assignment use public current tolls and native congestion travel times. Assigned persons use their announced/personal toll visibility and free-flow routing times; controlled morning route choice uses its configured decision policy.");
        publicBehavior.put("travelerInformation", Collections.unmodifiableMap(visibility));
        behavior = Collections.unmodifiableMap(publicBehavior);
        chains = chains(network);
    }

    int populationSize() { return populationSize; }
    List<List<Link>> carCorridors() { return chains; }
    double nominalFlowCapacityPerHour(Link link) { return link.getCapacity() * flowFactor * 3600 / network.getCapacityPeriod(); }
    Double routingMinutesPerMoney() { return minutesPerMoney; }
    Double referenceUnavoidableShare(String link) { return demand.unavoidableShare(link); }
    void captureReferenceDemand(Scenario scenario,int day) {
        if(referencePlanCaptureIteration!=null) throw new IllegalStateException("Reference demand is captured once after native initial routing");
        demand=MorningManagerDemand.from(scenario);referencePlanCaptureIteration=day;
    }
    String corridorId(String link) {
        return chains.stream().filter(c -> c.stream().anyMatch(l -> l.getId().toString().equals(link)))
                .map(c -> "car-chain:"+c.getFirst().getId()).findFirst().orElseThrow();
    }

    Map<String, Object> context(int iteration, TollPolicy policy) {
        var suitability=new LinkedHashMap<>(demand.context());suitability.put("referencePlanCaptureIteration",referencePlanCaptureIteration);
        suitability.put("referenceStage",referencePlanCaptureIteration==null?"pre-simulation selected plans; unrouted legs may be present":"selected plans after native routing and the first completed simulation iteration; fixed thereafter");
        return Map.of("publicEnvironment", parameters(iteration), "network", topology(), "roadCorridors", corridorContext(policy),
                "chargingSuitability", suitability);
    }

    Map<String, Object> context(int iteration, TollPolicy policy, boolean basic) {
        if (!basic) return context(iteration, policy);
        var publicParameters = new LinkedHashMap<>(parameters(iteration));
        publicParameters.remove("routingCostScale");
        var corridors = new LinkedHashMap<>(corridorContext(policy));
        var rows = new ArrayList<Map<String, Object>>();
        for (var source : (List<Map<String, Object>>)corridors.get("corridors")) {
            var row = new LinkedHashMap<>(source);
            row.put("currentPolicyCumulativeEntryCosts", ((List<Map<String,Object>>)source.get("currentPolicyCumulativeEntryCosts"))
                    .stream().map(r -> {var cost = new LinkedHashMap<>(r); cost.remove("equivalentRoutingMinutes"); return cost;}).toList());
            rows.add(row);
        }
        corridors.put("corridors", rows);
        return Map.of("publicEnvironment", publicParameters, "network", topology(), "roadCorridors", corridors);
    }

    Map<String, Object> parameters() {
        return parameters(firstIteration);
    }

    Map<String, Object> parameters(int iteration) {
        var response = new LinkedHashMap<>(behavior);
        response.put("evaluatedAtIteration", iteration);
        response.put("nativeInnovationDisableAfterIteration", innovationDisableAfter);
        response.put("routeChoiceEnabled", controlledPersons > 0 || innovationActive("ReRoute", iteration));
        response.put("departureTimeChoiceEnabled", llmDepartureChoice || mnlDepartureChoice || !fixedTimes && innovationActive("TimeAllocationMutator", iteration));
        response.put("nativeDepartureTimeMutationEnabled", !fixedTimes && innovationActive("TimeAllocationMutator", iteration));
        response.put("llmJointDepartureAndRouteChoiceEnabled", llmDepartureChoice);
        response.put("mnlJointDepartureAndRouteChoiceEnabled", mnlDepartureChoice);
        if (llmDepartureChoice || mnlDepartureChoice) {
            response.put("plannedActivityEndTimesFixed", false);
            response.put("priceResponse", "Controlled target trips choose listed departure times and routes jointly. Other trips retain native routing and "
                    + (fixedTimes ? "original planned activity ends" : "native selected-plan activity ends, including enabled time mutation")
                    + ". Fixed OD and mode; no trip cancellation. Native plan selection may respond with lag because scores refresh after execution.");
        }
        response.put("modeChoiceEnabled", innovationActive("ModeChoice", iteration)
                || innovationActive("ChangeTripMode", iteration) || innovationActive("ChangeLegMode", iteration));
        return Map.of("version", VERSION, "populationAndCapacity", sampling, "routingCostScale", routingCosts,
                "travelerResponse", response);
    }

    private boolean innovationActive(String name, int iteration) {
        return iteration <= innovationDisableAfter && nativeStrategies.stream().anyMatch(s -> s.weight() > 0
                && s.name().contains(name) && (s.disableAfter() < 0 || iteration <= s.disableAfter()));
    }

    List<Map<String, Object>> topology() {
        return carLinks(network).stream().map(l -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("linkId", l.getId().toString()); row.put("fromNode", l.getFromNode().getId().toString());
            row.put("toNode", l.getToNode().getId().toString()); row.put("lengthMeters", l.getLength());
            row.put("freeSpeedMetersPerSecond", l.getFreespeed()); row.put("numberOfLanes", l.getNumberOfLanes());
            row.put("rawCapacity", l.getCapacity());
            row.put("nominalScaledFlowCapacityPerHour", l.getCapacity() * flowFactor * 3600 / network.getCapacityPeriod());
            return (Map<String, Object>) row;
        }).toList();
    }

    Map<String, Object> corridorContext(TollPolicy policy) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var chain : chains) {
            var row = new LinkedHashMap<String, Object>();
            row.put("corridorId", "car-chain:" + chain.getFirst().getId());
            row.put("linkIds", chain.stream().map(l -> l.getId().toString()).toList());
            row.put("fromNode", chain.getFirst().getFromNode().getId().toString());
            row.put("toNode", chain.getLast().getToNode().getId().toString());
            row.put("closedCycle", chain.getFirst().getFromNode().getId().equals(chain.getLast().getToNode().getId()));
            row.put("lengthMeters", chain.stream().mapToDouble(Link::getLength).sum());
            row.put("freeFlowSeconds", chain.stream().mapToDouble(l -> l.getLength() / l.getFreespeed()).sum());
            Set<Id<Link>> ids = new HashSet<>(); chain.forEach(l -> ids.add(l.getId()));
            var boundaries = new TreeSet<Double>();
            policy.rules().stream().filter(r -> ids.contains(r.linkId())).forEach(r -> {
                boundaries.add(r.startSec()); boundaries.add(r.endSec());
            });
            var intervals = new ArrayList<Map<String, Object>>();
            var times = new ArrayList<>(boundaries);
            for (int i = 1; i < times.size(); i++) {
                double start = times.get(i - 1), end = times.get(i);
                double amount = chain.stream().mapToDouble(l -> policy.amountAt(l.getId(), start)).sum();
                if (amount == 0) continue;
                var cost = new LinkedHashMap<String, Object>(); cost.put("start", start); cost.put("end", end);
                cost.put("sumOfLinkEntryAmounts", amount);
                cost.put("equivalentRoutingMinutes", minutesPerMoney == null ? null : amount * minutesPerMoney);
                intervals.add(cost);
            }
            row.put("currentPolicyCumulativeEntryCosts", intervals); rows.add(row);
        }
        return Map.of("corridors", rows, "corridorCount", rows.size(),
                "definition", "Maximal directed car-link chains through nodes with exactly one car in-link and one car out-link; isolated cycles are also included. This groups topology, not a restricted candidate list.",
                "chargingInterpretation", "Charges accumulate on consecutive priced links, including subdivision links without route-choice junctions. Each interval sums per-link amounts at a common time; it applies only if every priced link entry is in that interval. Actual entries crossing a boundary, or starting/ending inside a chain, have different payments. These are conditional path costs, not observed personal payments. Link traversal counts must not be summed and interpreted as OD trip counts.");
    }

    private static List<Link> carLinks(Network network) {
        return network.getLinks().values().stream().filter(l -> l.getAllowedModes().contains("car"))
                .<Link>map(l -> l).sorted(Comparator.comparing(l -> l.getId().toString())).toList();
    }

    private static List<List<Link>> chains(Network network) {
        var links = carLinks(network);
        Map<Id<Node>, List<Link>> incoming = new HashMap<>(), outgoing = new HashMap<>();
        for (var link : links) {
            incoming.computeIfAbsent(link.getToNode().getId(), k -> new ArrayList<>()).add(link);
            outgoing.computeIfAbsent(link.getFromNode().getId(), k -> new ArrayList<>()).add(link);
        }
        Set<Id<Link>> visited = new HashSet<>(); var chains = new ArrayList<List<Link>>();
        for (var link : links) {
            var node = link.getFromNode().getId();
            if (incoming.getOrDefault(node, List.of()).size() != 1 || outgoing.getOrDefault(node, List.of()).size() != 1)
                follow(link, incoming, outgoing, visited, chains);
        }
        for (var link : links) follow(link, incoming, outgoing, visited, chains);
        return List.copyOf(chains);
    }

    private static void follow(Link first, Map<Id<Node>, List<Link>> incoming,
                               Map<Id<Node>, List<Link>> outgoing,
                               Set<Id<Link>> visited, List<List<Link>> chains) {
        var chain = new ArrayList<Link>(); Link link = first;
        while (visited.add(link.getId())) {
            chain.add(link); var node = link.getToNode().getId();
            var next = outgoing.getOrDefault(node, List.of());
            if (incoming.getOrDefault(node, List.of()).size() != 1 || next.size() != 1) break;
            link = next.getFirst();
        }
        if (!chain.isEmpty()) chains.add(List.copyOf(chain));
    }
}
