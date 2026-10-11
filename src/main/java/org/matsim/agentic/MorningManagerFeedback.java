package org.matsim.agentic;

import java.util.*;

/** Factual public aggregates are independent of the manager's self-written notes. */
final class MorningManagerFeedback {
    static final String VERSION = "morning-public-policy-feedback-v4-continuous-windows";
    private static final List<String> METRICS = List.of("totalLinkDelaySeconds", "totalLinkTravelSeconds",
            "enteredFullLinkTraversals", "completedFullLinkTraversals", "abortedFullLinkTraversals",
            "unfinishedFullLinkTraversals", "partialLinkTraversals");
    private record Sample(int day, MorningTollSystem.State state, Map<String, Double> metrics,
                          Map<String,Map<String,Double>> spatial, Map<String,Object> carTrips,
                          List<Map<String,Object>> linkRows) {}
    private final List<Sample> samples = new ArrayList<>();
    private final int populationSize;

    MorningManagerFeedback(int populationSize) { this.populationSize = populationSize; }

    void observe(int day, MorningTollSystem.State state, Map<String, Object> publicDaily) {
        observe(day, state, publicDaily, null);
    }

    void observe(int day, MorningTollSystem.State state, Map<String, Object> publicDaily, List<Map<String,Object>> rows) {
        if (!samples.isEmpty() && day <= samples.getLast().day())
            throw new IllegalArgumentException("Manager observations must be chronological completed days");
        if (state.policy().day() > day) throw new IllegalArgumentException("Observed traffic precedes its policy");
        var clean = new LinkedHashMap<String, Double>();
        for (String metric : METRICS) {
            if (!(publicDaily.get(metric) instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0)
                throw new IllegalArgumentException("Invalid public daily traffic: " + metric);
            clean.put(metric, n.doubleValue());
        }
        var spatial = (Map<String,Map<String,Double>>)publicDaily.getOrDefault("pricingSpatial",Map.of("network",Collections.unmodifiableMap(clean)));
        var carTrips = (Map<String,Object>)publicDaily.getOrDefault("publicCarTrips",Map.of());
        samples.add(new Sample(day, state, Collections.unmodifiableMap(clean),spatial,carTrips,
                rows == null ? null : rows.stream().map(r -> Collections.unmodifiableMap(new LinkedHashMap<>(r))).toList()));
    }

    int observationsThroughDay() { return samples.isEmpty() ? -1 : samples.getLast().day(); }

    Map<String, Object> context(int day, int firstIteration) {
        if (samples.stream().anyMatch(s -> s.day() >= day || s.day() < firstIteration))
            throw new IllegalArgumentException("Feedback requires completed past days within this run");
        var free = samples.stream().filter(s -> s.day() < firstIteration + MorningManager.FREE_DAYS
                && !s.state().policy().hasRules()).toList();
        var periods = new ArrayList<List<Sample>>();
        for (var sample : samples) {
            if (periods.isEmpty() || !samePolicy(periods.getLast().getLast().state(), sample.state()))
                periods.add(new ArrayList<>());
            periods.getLast().add(sample);
        }
        var freeStatistics = free.isEmpty() ? null : statistics(free);
        var rows = new ArrayList<Map<String, Object>>(); Map<String, Object> previous = null;
        for (var period : periods) {
            var state = period.getFirst().state();
            var stats = statistics(period);
            var row = new LinkedHashMap<String, Object>();
            row.put("policyId", state.policyId()); row.put("policyVersion", state.version());
            row.put("policyFingerprint", state.policy().fingerprint());
            row.put("effectiveFromDay", state.policy().day());
            row.put("ruleCount", state.policy().rules().size());
            row.put("pricedLinks", state.policy().rules().stream().filter(r -> r.amount() > 0).map(r -> r.linkId()).distinct().count());
            row.put("maximumLinkEntryAmount", state.policy().rules().stream().mapToDouble(r -> r.amount()).max().orElse(0));
            row.put("observations", stats);
            var tail = period.subList(Math.max(0, period.size() - 7), period.size());
            row.put("lastUpTo7ObservedDays", statistics(tail));
            row.put("changeFromPreviousObservedPolicyPeriod", previous == null ? null : changes(stats, previous));
            row.put("changeFromFreePeriodReference", freeStatistics == null ? null : changes(stats, freeStatistics));
            rows.add(row); previous = stats;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("version", VERSION); result.put("observationsThroughDay", samples.isEmpty() ? null : observationsThroughDay());
        result.put("observedDays", samples.size()); result.put("freePeriodReference", freeStatistics);
        result.put("policyPeriods", rows); result.put("matchedNoPricingCounterfactualAvailable", false);
        result.put("trackedChargingWindows", trackedWindows());
        result.put("comparisonInterpretation", "Changes compare observed period means, not same-state no-pricing counterfactuals. Native learning, adaptation and unequal observation windows can explain changes, including improvement after the early free period. Consecutive days are not independent replications. Tail statistics describe up to seven observed days and do not certify convergence. Zero-reference percentage changes are null.");
        result.put("measurementScope", "Full-day car aggregate link traffic only. Travel/delay cover completed full links, not complete OD journeys or origin/destination partial time; aborted, unfinished and partial counts are separate. Traversals are not persons or OD trips.");
        result.put("successCriteria",List.of("Reduce total network delay without loss of car-trip completions",
                "Check mean delay per completed priced-link traversal, not only lower priced traffic or revenue",
                "Inspect other-link and outside-charging-window delay for displacement",
                "Early free-period improvements do not establish a causal pricing benefit"));
        return result;
    }

    /** Keep exactly the same road and time window visible before, during and after withdrawal. */
    private List<Map<String,Object>> trackedWindows() {
        var definitions = new LinkedHashMap<String,TollPolicy.TollRule>();
        for (var s : samples) for (var rule : s.state().policy().rules()) if (rule.amount() > 0)
            definitions.putIfAbsent(rule.linkId()+"/"+rule.startSec()+"/"+rule.endSec(), rule);
        var result = new ArrayList<Map<String,Object>>();
        for (var definition : definitions.values()) {
            var policy = new TollPolicy(List.of(new TollPolicy.TollRule(definition.linkId(), definition.startSec(), definition.endSec(), 1)), 0);
            int firstCharge = samples.stream().filter(s -> s.state().policy().rules().stream().anyMatch(r -> r.amount()>0
                    && r.linkId().equals(definition.linkId()) && r.startSec()<definition.endSec() && r.endSec()>definition.startSec()))
                    .mapToInt(Sample::day).min().orElseThrow();
            var phases = new ArrayList<Map<String,Object>>();
            String priorKey = null;
            List<Map<String,Map<String,Double>>> observations = new ArrayList<>();
            Map<String,Object> phase = null;
            for (var s : samples) {
                if (s.linkRows() == null) continue; // Historical tests/legacy logs lack window-level evidence.
                boolean priced = s.state().policy().rules().stream().anyMatch(r -> r.amount()>0 && r.linkId().equals(definition.linkId())
                        && r.startSec()<definition.endSec() && r.endSec()>definition.startSec());
                String status = priced ? "during_pricing" : s.day()<firstCharge ? "before_first_pricing" : "after_withdrawal";
                String key = status+"/"+s.state().policy().fingerprint();
                if (!key.equals(priorKey)) {
                    if (phase != null) phase.put("observations", MorningManagerMeasurements.statistics(observations));
                    observations = new ArrayList<>(); phase = new LinkedHashMap<>(); phases.add(phase);
                    phase.put("stage", status); phase.put("firstObservedDay", s.day());
                    phase.put("executedPolicyFingerprint", s.state().policy().fingerprint());
                    phase.put("positivePricingOverlapsTrackedWindow", priced);
                    phase.put("actualRulesOnTrackedLink", s.state().policy().rules().stream()
                            .filter(r -> r.linkId().equals(definition.linkId())).map(r -> Map.of("link_id", r.linkId().toString(),
                                    "start", r.startSec(), "end", r.endSec(), "amount", r.amount())).toList());
                }
                phase.put("lastObservedDay", s.day()); observations.add(MorningManagerMeasurements.spatial(s.linkRows(), policy));
                phase.put("observedDays", observations.size()); priorKey = key;
            }
            if (phase != null) phase.put("observations", MorningManagerMeasurements.statistics(observations));
            result.add(Map.of("linkId", definition.linkId().toString(), "startSeconds", definition.startSec(),
                    "endSeconds", definition.endSec(), "firstPricedObservedDay", firstCharge, "phases", phases,
                    "interpretation", "Same entry-window and link across executed-policy phases. Zero observed traffic is included. Withdrawal does not remove monitoring. Changes are descriptive, not matched causal effects."));
        }
        return result;
    }

    /** Keep recent versions plus all distinct consecutive price episodes; archive every version separately. */
    Map<String, Object> managerContext(int day, int firstIteration) {
        var result = new LinkedHashMap<>(context(day, firstIteration));
        var allPeriods = (List<?>) result.get("policyPeriods");
        result.put("totalObservedPolicyPeriods", allPeriods.size());
        result.put("omittedOlderPolicyPeriods", Math.max(0, allPeriods.size() - 3));
        result.put("policyPeriods", allPeriods.subList(Math.max(0, allPeriods.size() - 3), allPeriods.size()));
        var episodes = new ArrayList<List<Sample>>();
        for (var sample : samples) {
            if (episodes.isEmpty() || !episodes.getLast().getLast().state().policy().fingerprint().equals(sample.state().policy().fingerprint()))
                episodes.add(new ArrayList<>());
            episodes.getLast().add(sample);
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (var episode : episodes) {
            var first = episode.getFirst().state(); var last = episode.getLast().state();
            rows.add(Map.of("policyFingerprint", first.policy().fingerprint(), "firstPolicyVersion", first.version(),
                    "lastPolicyVersion", last.version(), "hasPositivePrices", first.policy().rules().stream().anyMatch(r -> r.amount() > 0),
                    "observations", statistics(episode), "lastUpTo7ObservedDays", statistics(episode.subList(Math.max(0, episode.size() - 7), episode.size()))));
        }
        result.put("equivalentPriceEpisodes", rows);
        result.put("pricedObservedDays", samples.stream().filter(s -> s.state().policy().rules().stream().anyMatch(r -> r.amount() > 0)).count());
        result.put("historyPresentation", "Latest three actual policy-version periods plus all consecutive episodes of the same price function. New version IDs alone are not interventions. Full version periods remain in manager-policy-evaluations. Episodes describe time evolution, not matched causal effects.");
        return result;
    }

    /** Recent executed versions plus a pooled summary of every older, non-overlapping observed day. */
    Map<String, Object> compactContext(Map<String, Object> full, TollPolicy current) {
        var periods = (List<Map<String, Object>>) full.get("policyPeriods");
        int omitted = Math.max(0, periods.size() - 2);
        var recent = periods.subList(omitted, periods.size());
        int olderEnd = recent.isEmpty() ? -1
                : ((Number) ((Map<?, ?>) recent.getFirst().get("observations")).get("firstObservedDay")).intValue();
        var older = samples.stream().filter(s -> s.day() < olderEnd).toList();
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("version", "observationsThroughDay", "observedDays", "freePeriodReference",
                "matchedNoPricingCounterfactualAvailable")) result.put(key, full.get(key));
        result.put("totalObservedPolicyPeriods", periods.size());
        result.put("policyPeriods", recent);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("policyPeriods", omitted);
        summary.put("pricedObservedDays", older.stream().filter(s -> positivePrices(s.state().policy())).count());
        summary.put("observations", older.isEmpty() ? null : statistics(older));
        result.put("olderPolicySummary", summary);
        var definitions = recentPricingWindows(current);
        var tracked = new ArrayList<Map<String, Object>>();
        for (var source : (List<Map<String, Object>>) full.get("trackedChargingWindows")) {
            if (!definitions.stream().anyMatch(r -> r.linkId().toString().equals(source.get("linkId"))
                    && r.startSec() == ((Number) source.get("startSeconds")).doubleValue()
                    && r.endSec() == ((Number) source.get("endSeconds")).doubleValue())) continue;
            var row = new LinkedHashMap<>(source);
            var phases = (List<Map<String, Object>>) source.get("phases");
            var kept = new ArrayList<Map<String, Object>>();
            // Retain the original pre-pricing reference as well as the last two executed phases.
            if (phases.size() > 2 && phases.getFirst().get("stage").equals("before_first_pricing")) kept.add(phases.getFirst());
            kept.addAll(phases.subList(Math.max(0, phases.size() - 2), phases.size()));
            row.put("phases", kept); row.put("omittedPhases", phases.size() - kept.size());
            tracked.add(row);
        }
        result.put("trackedChargingWindows", tracked);
        result.put("historyPresentation", "Last two executed policy-version periods; older days pooled once. Version changes alone are not price interventions. Tracked windows cover current prices and the last two distinct consecutive price episodes; retained pre-pricing reference and last two phases. Full history archived separately.");
        return result;
    }

    /** Failed updates do not clear the input window; summarize exactly the same observed days. */
    Map<String, Object> windowSummary(int firstObservedDay) {
        var period = samples.stream().filter(s -> s.day() >= firstObservedDay).toList();
        var result = new LinkedHashMap<String, Object>();
        if (period.isEmpty()) return result;
        int split = Math.max(0, period.size() - 7);
        var recent = period.subList(split, period.size());
        var previous = period.subList(Math.max(0, split - 7), split);
        result.put("period", statistics(period));
        result.put("recentUpTo7ObservedDays", statistics(recent));
        result.put("previousUpTo7ObservedDays", previous.isEmpty() ? null : statistics(previous));
        result.put("recentChangeFromPrevious", previous.isEmpty() ? null : changes(statistics(recent), statistics(previous)));
        return result;
    }

    List<TollPolicy.TollRule> recentPricingWindows(TollPolicy current) {
        var episodes = new ArrayList<List<Sample>>();
        for (var sample : samples) {
            if (episodes.isEmpty() || !episodes.getLast().getLast().state().policy().fingerprint().equals(sample.state().policy().fingerprint()))
                episodes.add(new ArrayList<>());
            episodes.getLast().add(sample);
        }
        var definitions = new TreeMap<String, TollPolicy.TollRule>();
        var policies = new ArrayList<TollPolicy>(); policies.add(current);
        for (var episode : episodes.subList(Math.max(0, episodes.size() - 2), episodes.size()))
            policies.add(episode.getLast().state().policy());
        for (var policy : policies) for (var rule : policy.rules()) if (rule.amount() > 0)
            definitions.putIfAbsent(rule.linkId() + "/" + rule.startSec() + "/" + rule.endSec(), rule);
        return List.copyOf(definitions.values());
    }

    private static boolean positivePrices(TollPolicy policy) {
        return policy.rules().stream().anyMatch(r -> r.amount() > 0);
    }

    private static boolean samePolicy(MorningTollSystem.State a, MorningTollSystem.State b) {
        return a.version() == b.version() && a.policyId().equals(b.policyId());
    }

    private Map<String, Object> statistics(List<Sample> period) {
        var totals = new LinkedHashMap<String, Double>(); var means = new LinkedHashMap<String, Double>();
        for (String metric : METRICS) {
            double sum = period.stream().mapToDouble(s -> s.metrics().get(metric)).sum();
            totals.put(metric, sum); means.put(metric, sum / period.size());
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("firstObservedDay", period.getFirst().day()); result.put("lastObservedDay", period.getLast().day());
        result.put("observedDays", period.size()); result.put("calendarSpanDays", period.getLast().day() - period.getFirst().day() + 1);
        result.put("totals", totals); result.put("meanDaily", means);
        double completed = totals.get("completedFullLinkTraversals");
        result.put("delaySecondsPerCompletedTraversal", completed == 0 ? null : totals.get("totalLinkDelaySeconds") / completed);
        result.put("travelSecondsPerCompletedTraversal", completed == 0 ? null : totals.get("totalLinkTravelSeconds") / completed);
        result.put("delaySecondsPerPopulationDay", populationSize == 0 ? null : means.get("totalLinkDelaySeconds") / populationSize);
        result.put("travelSecondsPerPopulationDay", populationSize == 0 ? null : means.get("totalLinkTravelSeconds") / populationSize);
        result.put("pricingSpatial",MorningManagerMeasurements.statistics(period.stream().map(Sample::spatial).toList()));
        var trips=new LinkedHashMap<String,Double>();
        for(String field:List.of("carTripsStarted","carTripsCompleted","carTripsAborted","carTripsUnfinished")) {
            if(period.stream().allMatch(s -> s.carTrips().get(field) instanceof Number))
                trips.put(field,period.stream().mapToDouble(s -> ((Number)s.carTrips().get(field)).doubleValue()).sum());
        }
        var tripStats=new LinkedHashMap<String,Object>();tripStats.put("totals",trips);
        double started=trips.getOrDefault("carTripsStarted",0.0);
        tripStats.put("completionRateAmongStartedCarTrips",started==0?null:trips.get("carTripsCompleted")/started);
        tripStats.put("scope","All started car legs including background traffic; not a cohort or the completion rate of all planned OD trips.");
        result.put("publicCarTrips",tripStats);
        return result;
    }

    private static Map<String, Object> changes(Map<String, Object> current, Map<String, Object> reference) {
        var now = (Map<?, ?>) current.get("meanDaily"); var before = (Map<?, ?>) reference.get("meanDaily");
        var changes = new LinkedHashMap<String, Object>();
        for (String metric : METRICS) {
            double a = ((Number) now.get(metric)).doubleValue(), b = ((Number) before.get(metric)).doubleValue();
            var change = new LinkedHashMap<String, Object>(); change.put("absoluteDifference", a - b);
            change.put("relativePercent", b == 0 ? null : 100 * (a / b - 1)); changes.put(metric, change);
        }
        return changes;
    }
}
