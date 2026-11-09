package org.matsim.agentic;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Joint logit using exactly the bounded traveler context; no scenario/network/archive access. */
final class MorningMnlPolicy implements MorningRoutePolicy {
    private final MorningMnlParameters parameters;
    private final long seed;
    private final int historyDays;
    private final OutputWriter output;

    MorningMnlPolicy(MorningMnlParameters parameters, long seed, int historyDays, OutputWriter output) {
        this.parameters = Objects.requireNonNull(parameters); this.seed = seed;
        if (historyDays < 1 || historyDays > MorningPromptRenderer.RECENT_FACT_DAYS) throw new IllegalArgumentException("Invalid MNL history window");
        this.historyDays = historyDays; this.output = Objects.requireNonNull(output);
    }

    @Override public Decision choose(Context context) {
        MorningPromptRenderer.validate(context);
        if (context.departureOptions().isEmpty()) throw new IllegalArgumentException("MNL requires joint departure options");
        // Defensive filtering: even a hand-written/replayed context cannot supply older archive facts.
        var history = MorningPromptRenderer.recentFacts(context.history(), context.day(), false, historyDays);
        var actions = new ArrayList<Map<String, Object>>();
        for (var departure : context.departureOptions().stream().sorted(Comparator.comparingDouble(DepartureOption::departureSeconds)).toList()) {
            var routes = departure.routes().stream().sorted(Comparator.comparing(Option::routeId)).toList();
            for (var route : routes) actions.add(evaluate(context, departure.departureSeconds(), route, history));
        }
        double maximum = actions.stream().mapToDouble(a -> (double)a.get("utility")).max().orElseThrow();
        double total = actions.stream().mapToDouble(a -> Math.exp((double)a.get("utility") - maximum)).sum();
        long localSeed = localSeed(seed, context.day(), context.tripId());
        double draw = new Random(localSeed).nextDouble(), cumulative = 0;
        Map<String, Object> selected = actions.getLast();
        boolean found = false;
        for (var action : actions) {
            double probability = Math.exp((double)action.get("utility") - maximum) / total;
            action.put("probability", probability);
            cumulative += probability;
            if (!found && draw < cumulative) { selected = action; found = true; }
        }
        for (var action : actions) action.put("selected", action == selected);
        var result = MorningRoutePolicy.validate(context, new Decision((String)selected.get("routeId"),
                "Seeded joint logit on visible costs, schedule and bounded own experience", (Double)selected.get("departureSeconds")));
        var audit = new LinkedHashMap<String, Object>();
        audit.put("day", context.day()); audit.put("personId", context.personId()); audit.put("tripId", context.tripId());
        audit.put("version", MorningMnlParameters.VERSION); audit.put("informationGroup", context.information().group());
        audit.put("choiceSeed", seed); audit.put("derivedChoiceSeed", localSeed); audit.put("uniformDraw", draw);
        audit.put("historyDaysUsed", history.stream().map(MorningMemory.Experience::day).toList());
        audit.put("actions", actions); audit.put("decision", result);
        output.appendLine("baseline-contexts.jsonl", context);
        output.appendLine("baseline-choices.jsonl", audit);
        return result;
    }

    private Map<String, Object> evaluate(Context context, double departure, Option route, List<MorningMemory.Experience> history) {
        var matched = history.stream().filter(e -> e.routeId().equals(route.routeId())
                && e.selectedDepartureSeconds() != null && bin(e.selectedDepartureSeconds()) == bin(departure)).toList();
        var completed = matched.stream().filter(e -> !e.stuck() && e.carSeconds() != null && e.arrivalSeconds() != null).toList();
        double learnedSeconds = completed.stream().mapToDouble(MorningMemory.Experience::carSeconds).average().orElse(route.expectedCarSeconds());
        double carSeconds = (1 - parameters.experienceWeight) * route.expectedCarSeconds() + parameters.experienceWeight * learnedSeconds;
        double nonCarSeconds = route.expectedArrivalSeconds() - departure - route.expectedCarSeconds();
        double arrival = departure + nonCarSeconds + carSeconds;
        double lateMinutes = context.latestArrivalSeconds() == null ? 0 : Math.max(0, arrival - context.latestArrivalSeconds()) / 60;
        var knowledge = route.pricingKnowledge();
        double baseCost = knowledge.baseMonetaryCost();
        if (route.estimatedToll() == null) throw new IllegalArgumentException("MNL requires the shared current/own-observation toll estimate");
        double toll = route.estimatedToll();
        String tollSource = knowledge.source();
        List<Integer> paymentDays = knowledge.observations().stream().map(TollObservation::day).toList();
        double money = baseCost + toll;
        double shiftMinutes = Math.abs(departure - context.plannedDepartureSeconds()) / 60;
        var terms = new LinkedHashMap<String, Double>();
        terms.put("travelTime", -parameters.timeWeight * carSeconds / 60);
        terms.put("money", -parameters.moneyWeight * money);
        terms.put("lateness", -parameters.lateWeight * lateMinutes);
        terms.put("departureShift", -parameters.departureShiftWeight * shiftMinutes);
        double utility = terms.values().stream().mapToDouble(Double::doubleValue).sum();
        if (!Double.isFinite(utility) || !Double.isFinite(arrival) || !Double.isFinite(money))
            throw new IllegalArgumentException("Nonfinite MNL cost/utility for " + context.tripId());
        var row = new LinkedHashMap<String, Object>();
        row.put("routeId", route.routeId()); row.put("departureSeconds", departure);
        row.put("freeFlowCarSeconds", route.expectedCarSeconds()); row.put("estimatedCarSeconds", carSeconds);
        row.put("nonCarSeconds", nonCarSeconds); row.put("predictedArrivalSeconds", arrival);
        row.put("predictedLateMinutes", lateMinutes); row.put("departureShiftMinutes", shiftMinutes);
        row.put("distanceMeters", route.distanceMeters()); row.put("baseMonetaryCost", baseCost);
        row.put("estimatedToll", toll); row.put("estimatedTotalCost", money); row.put("tollEstimateSource", tollSource);
        row.put("currentTollUnknown", !context.information().currentAnnouncementVisible());
        row.put("unobservedTollLinks", knowledge.unknownLinks());
        row.put("tollObservations", knowledge.observations()); row.put("paymentExperienceDays", paymentDays);
        row.put("timeEstimateSource", completed.isEmpty() ? "static_free_flow" : "free_flow_blended_with_recent_own_same_route_and_bin");
        row.put("timeExperienceDays", completed.stream().map(MorningMemory.Experience::day).toList());
        row.put("timeExperienceCarSeconds", completed.stream().map(MorningMemory.Experience::carSeconds).toList());
        row.put("excludedIncompleteExperienceDays", matched.stream().filter(e -> !completed.contains(e)).map(MorningMemory.Experience::day).toList());
        row.put("utilityComponents", terms); row.put("utility", utility);
        return row;
    }

    private long bin(double time) { return (long)Math.floor(time / parameters.experienceBinSeconds); }
    static long localSeed(long seed, int day, String trip) {
        try {
            byte[] bytes = (MorningMnlParameters.VERSION + "\n" + seed + "\n" + day + "\n" + trip).getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes)).getLong();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
