package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;

import java.util.*;

/** Public path diagnostics; link aggregates never imply a measured OD route or spare capacity. */
final class MorningManagerAlternatives {
    static final String VERSION = "morning-public-route-alternatives-v1";
    private record Bin(int start, int end) {}
    private record Key(String link, Bin bin) {}
    private record Hotspot(List<Link> corridor, Bin bin, double delay) {}
    private record Visit(Id<Node> node, double cost) {}
    private final Network network;
    private final MorningManagerEnvironment environment;
    private final Map<Key, double[]> observations = new HashMap<>();
    private final Map<Id<Node>, List<Link>> outgoing = new HashMap<>();
    private final int days;

    MorningManagerAlternatives(Network network, MorningManagerEnvironment environment, List<Map<String, Object>> window) {
        this.network = network; this.environment = environment; days = window.size();
        for (var day : window) for (var value : (List<?>) day.get("link_hourly")) {
            var row = (Map<?, ?>) value;
            var key = new Key((String) row.get("link_id"), new Bin(((Number) row.get("start")).intValue(), ((Number) row.get("end")).intValue()));
            var sums = observations.computeIfAbsent(key, k -> new double[5]);
            String[] fields = {"entered", "completed", "total_travel_time", "total_delay", "partial"};
            for (int i = 0; i < fields.length; i++) sums[i] += ((Number) row.get(fields[i])).doubleValue();
        }
        network.getLinks().values().stream().filter(l -> l.getAllowedModes().contains("car"))
                .sorted(Comparator.comparing(l -> l.getId().toString()))
                .forEach(l -> outgoing.computeIfAbsent(l.getFromNode().getId(), k -> new ArrayList<>()).add(l));
    }

    Map<String, Object> context(TollPolicy policy) {
        var bins = observations.keySet().stream().map(Key::bin).distinct()
                .sorted(Comparator.comparingInt(Bin::start).thenComparingInt(Bin::end)).toList();
        var hotspots = new ArrayList<Hotspot>();
        for (var corridor : environment.carCorridors()) {
            Hotspot peak = null;
            for (var bin : bins) {
                double delay = corridor.stream().mapToDouble(l -> sums(l, bin)[3]).sum();
                if (delay > 0 && (peak == null || delay > peak.delay())) peak = new Hotspot(corridor, bin, delay);
            }
            if (peak != null) hotspots.add(peak);
        }
        hotspots.sort(Comparator.comparingDouble(Hotspot::delay).reversed().thenComparing(h -> h.corridor().getFirst().getId().toString()));
        var rows = new ArrayList<Map<String, Object>>();
        for (var hotspot : hotspots.stream().limit(8).toList()) {
            var corridor = hotspot.corridor(); var bin = hotspot.bin();
            var from = corridor.getFirst().getFromNode().getId(); var to = corridor.getLast().getToNode().getId();
            var excluded = new HashSet<Id<Link>>(); corridor.forEach(l -> excluded.add(l.getId()));
            var alternatives = new ArrayList<Map<String, Object>>(); var seen = new HashSet<List<String>>();
            if (!from.equals(to)) for (boolean observed : List.of(true, false)) {
                var path = shortestPath(from, to, excluded, bin, observed);
                var ids = path.stream().map(l -> l.getId().toString()).toList();
                if (!path.isEmpty() && seen.add(ids)) {
                    var row = path(path, bin, policy);
                    row.put("extraDistanceMetersComparedWithCorridor", path.stream().mapToDouble(Link::getLength).sum() - corridor.stream().mapToDouble(Link::getLength).sum());
                    row.put("extraFreeFlowSecondsComparedWithCorridor", path.stream().mapToDouble(l -> l.getLength() / l.getFreespeed()).sum() - corridor.stream().mapToDouble(l -> l.getLength() / l.getFreespeed()).sum());
                    row.put("selection", observed ? "minimum_observed_link_time_sum_with_free_flow_fallback" : "minimum_free_flow_time");
                    alternatives.add(row);
                }
            }
            rows.add(Map.of("corridorId", "car-chain:" + corridor.getFirst().getId(), "fromNode", from.toString(), "toNode", to.toString(),
                    "start", bin.start(), "end", bin.end(), "corridor", path(corridor, bin, policy), "alternativeRoutes", alternatives,
                    "alternativeStatus", from.equals(to) ? "closed_cycle_not_compared" : alternatives.isEmpty() ? "no_directed_car_path_avoiding_corridor" : "illustrative_directed_paths"));
        }
        return Map.of("version", VERSION, "observedDays", days, "diagnostics", rows,
                "selection", "Peak observed-delay bin for each of up to eight highest-delay car corridors. This display does not restrict charging actions.",
                "interpretation", "Alternatives connect the same directed corridor endpoints while avoiding its links. They are not measured OD routes or evidence all users can reroute. Each link uses its own entry-time-bin observations; summing them is a proxy, not end-to-end journey time. Missing completed traversals use labelled free-flow estimates, not evidence of uncongested traffic. Entry rates and nominal capacities do not establish spare capacity. Partial-link time is excluded; failures remain in other public feedback. Price sums sample a common bin midpoint, conditional on all entries at that time, not actual payments. Link-time path selection omits native distance penalties and routing randomness.");
    }

    private double[] sums(Link link, Bin bin) { return observations.getOrDefault(new Key(link.getId().toString(), bin), new double[5]); }
    private double estimate(Link link, Bin bin, boolean observed) {
        double[] s = sums(link, bin);
        return observed && s[1] > 0 ? s[2] / s[1] : link.getLength() / link.getFreespeed();
    }

    private List<Link> shortestPath(Id<Node> from, Id<Node> to, Set<Id<Link>> excluded, Bin bin, boolean observed) {
        var costs = new HashMap<Id<Node>, Double>(); var previous = new HashMap<Id<Node>, Link>();
        var queue = new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::cost).thenComparing(v -> v.node().toString()));
        costs.put(from, 0.0); queue.add(new Visit(from, 0));
        while (!queue.isEmpty()) {
            var visit = queue.remove();
            if (visit.cost() > costs.getOrDefault(visit.node(), Double.POSITIVE_INFINITY)) continue;
            if (visit.node().equals(to)) break;
            for (var link : outgoing.getOrDefault(visit.node(), List.of())) {
                if (excluded.contains(link.getId())) continue;
                double next = visit.cost() + estimate(link, bin, observed);
                var target = link.getToNode().getId();
                if (next < costs.getOrDefault(target, Double.POSITIVE_INFINITY)) {
                    costs.put(target, next); previous.put(target, link); queue.add(new Visit(target, next));
                }
            }
        }
        if (!costs.containsKey(to)) return List.of();
        var path = new LinkedList<Link>(); var node = to;
        while (!node.equals(from)) {
            var link = previous.get(node);
            if (link == null || path.size() >= network.getNodes().size()) return List.of();
            path.addFirst(link); node = link.getFromNode().getId();
        }
        return path;
    }

    private LinkedHashMap<String, Object> path(List<Link> path, Bin bin, TollPolicy policy) {
        var links = new ArrayList<Map<String, Object>>(); double travel = 0, amount = 0; int missing = 0;
        double midpoint = bin.start() + (bin.end() - bin.start()) / 2.0;
        for (var link : path) {
            double[] s = sums(link, bin); var row = new LinkedHashMap<String, Object>();
            row.put("linkId", link.getId().toString());
            row.put("entriesPerHour", days == 0 ? null : s[0] / days / ((bin.end() - bin.start()) / 3600.0));
            row.put("completedTraversals", s[1]); row.put("partialTraversalsExcluded", s[4]);
            row.put("meanObservedTravelSeconds", s[1] > 0 ? s[2] / s[1] : null);
            row.put("meanObservedDelaySeconds", s[1] > 0 ? s[3] / s[1] : null);
            row.put("timeEstimateSource", s[1] > 0 ? "completed_link_observations" : "free_flow_fallback_no_completed_observations");
            row.put("nominalScaledFlowCapacityPerHour", environment.nominalFlowCapacityPerHour(link));
            links.add(row); travel += estimate(link, bin, true); amount += policy.amountAt(link.getId(), midpoint);
            if (s[1] == 0) missing++;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("linkIds", path.stream().map(l -> l.getId().toString()).toList()); result.put("links", links);
        result.put("lengthMeters", path.stream().mapToDouble(Link::getLength).sum());
        result.put("freeFlowSeconds", path.stream().mapToDouble(l -> l.getLength() / l.getFreespeed()).sum());
        result.put("linkTimeEstimateSumSeconds", travel); result.put("missingCompletedObservationLinks", missing);
        result.put("observedLinkTimeSumSeconds", missing == 0 ? travel : null);
        result.put("conditionalEntryPriceSumAtBinMidpoint", amount);
        result.put("equivalentRoutingMinutes", environment.routingMinutesPerMoney() == null ? null : amount * environment.routingMinutesPerMoney());
        return result;
    }
}
