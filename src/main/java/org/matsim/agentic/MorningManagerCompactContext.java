package org.matsim.agentic;

import java.util.*;

/** Deterministic public-input compression; full observations remain in the research archive. */
final class MorningManagerCompactContext {
    static final String VERSION = "morning-manager-compact-v2-prefix";
    static final int TOP_DELAY_LINKS = 20, TOP_FAILURE_LINKS = 10, TOP_DELAY_BINS = 20, TOP_FAILURE_BINS = 10;
    static final List<String> NETWORK_COLUMNS = List.of("linkId", "fromNode", "toNode", "lengthType", "roadType");
    static final List<String> ROAD_TYPE_COLUMNS = List.of("freeSpeedMetersPerSecond", "numberOfLanes", "rawCapacity");
    private static final List<String> LINK_COLUMNS = List.of("linkId", "entered", "completed", "aborted", "unfinished",
            "partial", "totalTravelSeconds", "totalDelaySeconds");
    private record Bin(String link, double start, double end) {}

    static Map<String, Object> render(Map<String, Object> full, Map<String, Object> trafficSummary,
                                      Map<String, Object> feedback, List<Map<String, Object>> window,
                                      List<TollPolicy.TollRule> trackedWindows, TollPolicy policy) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("day", "simulationDay", "observationsThroughDay", "simulationEndSeconds",
                "monetaryUnits", "capacityUnits", "templateVersion", "managerFeedbackMode", "executionSchedule",
                "currentPolicyId", "currentPolicyVersion", "currentPolicy", "currentPolicyFingerprint",
                "managerMemory", "managerMemoryMetadata", "previousDecision", "pricingControls", "managerProfile"))
            result.put(key, full.get(key));
        result.put("inputProjectionVersion", VERSION);
        result.put("publicEnvironment", shortEnvironment((Map<String, Object>) full.get("publicEnvironment")));
        network(result, full);
        result.put("roadCorridors", corridors((Map<String, Object>) full.get("roadCorridors"), (List<List<Object>>) full.get("network")));
        result.put("trafficMetricColumns", MorningManagerMeasurements.FIELDS);
        var summary = new LinkedHashMap<String, Object>();
        for (String key : List.of("period", "recentUpTo7ObservedDays", "previousUpTo7ObservedDays"))
            summary.put(key, statistics((Map<String, Object>) trafficSummary.get(key)));
        summary.put("recentChangeFromPrevious", changes((Map<String, Object>) trafficSummary.get("recentChangeFromPrevious")));
        result.put("trafficSummary", summary);
        result.put("policyFeedback", feedback(feedback));
        roads(result, full, trackedWindows, policy);
        result.put("entryTimeTraffic", bins(window, trackedWindows));
        result.put("measurementScope", "Completed full-link travel/delay; separate partial/aborted/unfinished traversals and all-started car-leg completions. Periods and trends are descriptive, not matched causal effects.");
        var ordered = new LinkedHashMap<String,Object>();
        // Exact static topology/dictionaries precede policy/day/traffic. Do not move
        // the whole publicEnvironment here: evaluated traveler responses can change.
        for (String key : List.of("networkColumns", "lengthsMeters", "roadTypeColumns", "roadTypes", "network",
                "roadCorridors", "trafficMetricColumns", "capacityUnits", "monetaryUnits", "templateVersion",
                "inputProjectionVersion", "managerFeedbackMode", "measurementScope")) ordered.put(key,result.get(key));
        result.forEach(ordered::putIfAbsent);
        ordered.replaceAll((key,value) -> normalize(value));
        return ordered;
    }

    private static void network(Map<String, Object> result, Map<String, Object> full) {
        var columns = (List<String>) full.get("networkColumns");
        var types = new LinkedHashMap<List<Object>, Integer>();
        var lengths = new LinkedHashMap<Object, Integer>();
        var rows = new ArrayList<List<Object>>();
        for (var row : (List<List<Object>>) full.get("network")) {
            var type = ROAD_TYPE_COLUMNS.stream().map(c -> row.get(columns.indexOf(c))).toList();
            int index = types.computeIfAbsent(type, ignored -> types.size());
            int lengthIndex = lengths.computeIfAbsent(row.get(columns.indexOf("lengthMeters")), ignored -> lengths.size());
            rows.add(List.of(row.get(columns.indexOf("linkId")), row.get(columns.indexOf("fromNode")),
                    row.get(columns.indexOf("toNode")), lengthIndex, index));
        }
        result.put("networkColumns", NETWORK_COLUMNS); result.put("network", rows);
        result.put("roadTypeColumns", ROAD_TYPE_COLUMNS); result.put("roadTypes", new ArrayList<>(types.keySet()));
        result.put("lengthsMeters", new ArrayList<>(lengths.keySet()));
    }

    private static Map<String, Object> shortEnvironment(Map<String, Object> full) {
        var result = new LinkedHashMap<>(full);
        var capacity = new LinkedHashMap<>((Map<String, Object>) full.get("populationAndCapacity"));
        capacity.remove("capacityMeaning"); result.put("populationAndCapacity", capacity);
        var response = new LinkedHashMap<>((Map<String, Object>) full.get("travelerResponse"));
        response.remove("actualTiming"); // Explained once in the system prompt.
        result.put("travelerResponse", response);
        return result;
    }

    private static Map<String, Object> corridors(Map<String, Object> full, List<List<Object>> network) {
        var indices = new HashMap<String, Integer>();
        for (int i = 0; i < network.size(); i++) indices.put(network.get(i).getFirst().toString(), i);
        var rows = new ArrayList<List<Integer>>();
        for (var row : (List<Map<String, Object>>) full.get("corridors"))
            rows.add(((List<String>) row.get("linkIds")).stream().map(indices::get).toList());
        return Map.of("networkRowChains", rows,
                "definition", "Directed chains with no intermediate car-route choice; cumulative entry charges apply only when each entry is inside its charged window.");
    }

    /** Sufficient statistics are retained exactly; daily means and weighted ratios can be recovered. */
    static Map<String, Object> statistics(Map<String, Object> full) {
        if (full == null) return null;
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("firstObservedDay", "lastObservedDay", "observedDays", "calendarSpanDays"))
            if (full.containsKey(key)) result.put(key, full.get(key));
        result.put("totals", vector((Map<?, ?>) full.get("totals")));
        var spatial = new LinkedHashMap<String, Object>();
        for (var entry : ((Map<String, Map<String, Object>>) full.getOrDefault("pricingSpatial", Map.of())).entrySet())
            if (!entry.getKey().equals("network")) spatial.put(entry.getKey(), vector((Map<?, ?>) entry.getValue().get("totals")));
        result.put("pricingSpatialTotals", spatial);
        var trips = (Map<String, Object>) full.getOrDefault("publicCarTrips", Map.of());
        result.put("publicCarTrips", trips.getOrDefault("totals", Map.of()));
        return result;
    }

    private static List<Object> vector(Map<?, ?> metrics) {
        return MorningManagerMeasurements.FIELDS.stream().map(field -> (Object) metrics.get(field)).toList();
    }

    private static Map<String, Object> feedback(Map<String, Object> full) {
        var result = new LinkedHashMap<>(full);
        result.put("freePeriodReference", statistics((Map<String, Object>) full.get("freePeriodReference")));
        var periods = new ArrayList<Map<String, Object>>();
        for (var source : (List<Map<String, Object>>) full.get("policyPeriods")) {
            var row = new LinkedHashMap<>(source);
            row.put("observations", statistics((Map<String, Object>) source.get("observations")));
            row.put("lastUpTo7ObservedDays", statistics((Map<String, Object>) source.get("lastUpTo7ObservedDays")));
            for (String key : List.of("changeFromPreviousObservedPolicyPeriod", "changeFromFreePeriodReference"))
                row.put(key, changes((Map<String, Object>) source.get(key)));
            periods.add(row);
        }
        result.put("policyPeriods", periods);
        var older = new LinkedHashMap<>((Map<String, Object>) full.get("olderPolicySummary"));
        older.put("observations", statistics((Map<String, Object>) older.get("observations")));
        result.put("olderPolicySummary", older);
        var tracked = new ArrayList<Map<String, Object>>();
        for (var source : (List<Map<String, Object>>) full.get("trackedChargingWindows")) {
            var row = new LinkedHashMap<>(source); row.remove("interpretation");
            var phases = new ArrayList<Map<String, Object>>();
            for (var sourcePhase : (List<Map<String, Object>>) source.get("phases")) {
                var phase = new LinkedHashMap<>(sourcePhase);
                var prices = new ArrayList<List<Object>>();
                for (var rule : (List<Map<String, Object>>) sourcePhase.get("actualRulesOnTrackedLink"))
                    if (((Number) rule.get("start")).doubleValue() < ((Number) source.get("endSeconds")).doubleValue()
                            && ((Number) rule.get("end")).doubleValue() > ((Number) source.get("startSeconds")).doubleValue())
                        prices.add(List.of(rule.get("start"), rule.get("end"), rule.get("amount")));
                phase.remove("actualRulesOnTrackedLink");
                phase.put("executedOverlappingRules", prices);
                var stats = (Map<String, Map<String, Object>>) sourcePhase.get("observations");
                var totals = new LinkedHashMap<String, Object>();
                for (String group : List.of("pricedChargingWindow", "pricedOutsideWindow"))
                    if (stats.containsKey(group)) totals.put(group, vector((Map<?, ?>) stats.get(group).get("totals")));
                phase.put("observations", totals); phases.add(phase);
            }
            row.put("phases", phases); tracked.add(row);
        }
        result.put("executedOverlappingRulesColumns", List.of("start", "end", "amount"));
        result.put("trackedChargingWindows", tracked);
        return result;
    }

    private static Map<String, Object> changes(Map<String, Object> full) {
        if (full == null) return null;
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("absoluteDifference", "relativePercent"))
            result.put(key, MorningManagerMeasurements.FIELDS.stream().map(f -> ((Map<?, ?>) full.get(f)).get(key)).toList());
        return result;
    }

    private static void roads(Map<String, Object> result, Map<String, Object> full,
                              List<TollPolicy.TollRule> trackedWindows, TollPolicy policy) {
        var columns = (List<String>) full.get("linkTrafficTotalsColumns");
        var totals = new TreeMap<String, List<Object>>();
        for (var row : (List<List<Object>>) full.get("linkTrafficTotals")) totals.put(row.getFirst().toString(), row);
        var selected = new TreeSet<String>();
        int delay = columns.indexOf("totalDelaySeconds"), aborted = columns.indexOf("aborted"), unfinished = columns.indexOf("unfinished");
        totals.entrySet().stream().filter(e -> value(e.getValue(), delay) > 0)
                .sorted(Comparator.<Map.Entry<String, List<Object>>>comparingDouble(e -> value(e.getValue(), delay)).reversed().thenComparing(Map.Entry::getKey))
                .limit(TOP_DELAY_LINKS).forEach(e -> selected.add(e.getKey()));
        totals.entrySet().stream().filter(e -> value(e.getValue(), aborted) + value(e.getValue(), unfinished) > 0)
                .sorted(Comparator.<Map.Entry<String, List<Object>>>comparingDouble(e -> value(e.getValue(), aborted) + value(e.getValue(), unfinished)).reversed().thenComparing(Map.Entry::getKey))
                .limit(TOP_FAILURE_LINKS).forEach(e -> selected.add(e.getKey()));
        var current = new TreeSet<String>(); policy.rules().stream().filter(r -> r.amount() > 0).forEach(r -> current.add(r.linkId().toString()));
        var withdrawn = new TreeSet<String>(); trackedWindows.forEach(r -> withdrawn.add(r.linkId().toString())); withdrawn.removeAll(current);
        selected.addAll(current); selected.addAll(withdrawn);
        var rows = new ArrayList<List<Object>>(); var noRows = new ArrayList<String>();
        for (String id : selected) {
            if (!totals.containsKey(id)) { noRows.add(id); continue; }
            var row = totals.get(id); rows.add(LINK_COLUMNS.stream().map(c -> row.get(columns.indexOf(c))).toList());
        }
        result.put("linkTrafficTotalsColumns", LINK_COLUMNS); result.put("linkTrafficTotals", rows);
        result.put("roadSelection", Map.of("topDelayLimit", TOP_DELAY_LINKS, "topFailureLimit", TOP_FAILURE_LINKS,
                "observedLinks", totals.size(), "selectedLinks", selected.size(), "currentPricedLinks", current,
                "recentWithdrawnLinks", withdrawn, "selectedLinksWithoutRecordedRows", noRows,
                "interpretation", "Period totals; retain current and recently withdrawn prices even below rankings. No recorded rows do not establish spare capacity; unlisted roads remain valid pricing actions."));
    }

    private static Map<String, Object> bins(List<Map<String, Object>> window, List<TollPolicy.TollRule> trackedWindows) {
        var bins = new HashMap<Bin, double[]>();
        for (var day : window) for (var row : (List<Map<String, Object>>) day.get("link_hourly")) {
            var key = new Bin((String) row.get("link_id"), ((Number) row.get("start")).doubleValue(), ((Number) row.get("end")).doubleValue());
            var sums = bins.computeIfAbsent(key, ignored -> new double[7]);
            var fields = List.of("entered", "completed", "aborted", "unfinished", "partial", "total_travel_time", "total_delay");
            for (int i = 0; i < fields.size(); i++) sums[i] += ((Number) row.get(fields.get(i))).doubleValue();
        }
        Comparator<Bin> order = Comparator.comparing(Bin::link).thenComparingDouble(Bin::start).thenComparingDouble(Bin::end);
        var selected = new TreeSet<>(order);
        bins.keySet().stream().filter(k -> bins.get(k)[6] > 0)
                .sorted(Comparator.<Bin>comparingDouble(k -> bins.get(k)[6]).reversed().thenComparing(order))
                .limit(TOP_DELAY_BINS).forEach(selected::add);
        bins.keySet().stream().filter(k -> bins.get(k)[2] + bins.get(k)[3] > 0)
                .sorted(Comparator.<Bin>comparingDouble(k -> bins.get(k)[2] + bins.get(k)[3]).reversed().thenComparing(order))
                .limit(TOP_FAILURE_BINS).forEach(selected::add);
        var peaks = new ArrayList<List<Object>>();
        for (var key : selected) peaks.add(binRow(key.link(), key.start(), key.end(), bins.get(key)));
        var tracked = new ArrayList<List<Object>>();
        var orderedBins = bins.entrySet().stream().sorted(Map.Entry.comparingByKey(order)).toList();
        for (var rule : trackedWindows) {
            var sums = new double[7]; int observedBins = 0;
            for (var entry : orderedBins) {
                var key = entry.getKey();
                if (key.link().equals(rule.linkId().toString()) && key.start() >= rule.startSec() && key.end() <= rule.endSec()) {
                    for (int i = 0; i < sums.length; i++) sums[i] += entry.getValue()[i];
                    observedBins++;
                }
            }
            var row = new ArrayList<>(binRow(rule.linkId().toString(), rule.startSec(), rule.endSec(), sums));
            row.add(observedBins); tracked.add(row);
        }
        return Map.of("observedDays", window.size(), "columns", List.of("linkId", "startSeconds", "endSeconds", "entered", "completed",
                        "aborted", "unfinished", "partial", "totalTravelSeconds", "totalDelaySeconds"),
                "peakBins", peaks, "trackedWindowColumns", List.of("linkId", "startSeconds", "endSeconds", "entered", "completed",
                        "aborted", "unfinished", "partial", "totalTravelSeconds", "totalDelaySeconds", "recordedDistinctBins"),
                "trackedWindowTotals", tracked);
    }

    private static List<Object> binRow(String link, double start, double end, double[] sums) {
        var row = new ArrayList<Object>(List.of(link, start, end)); for (double value : sums) row.add(value); return row;
    }

    private static double value(List<Object> row, int column) { return ((Number) row.get(column)).doubleValue(); }

    /** Remove redundant .0 suffixes, without rounding non-integral observations or capacities. */
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().toString()))
                    .forEach(e -> result.put(e.getKey().toString(), normalize(e.getValue())));
            return result;
        }
        if (value instanceof Collection<?> rows) return rows.stream().map(MorningManagerCompactContext::normalize).toList();
        if (value instanceof Double d && d >= Long.MIN_VALUE && d < Long.MAX_VALUE && d == Math.rint(d)) return d.longValue();
        return value;
    }
}
