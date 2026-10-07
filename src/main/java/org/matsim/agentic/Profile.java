package org.matsim.agentic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * A traveler's fixed profile. Three dimensions, sampled independently from a
 * seed and held fixed for the whole experiment. Weights are engineering
 * defaults (equal probability), not calibrated Sioux Falls attributes.
 */
public record Profile(Income income, DecisionStyle decisionStyle, WorkTimeliness workTimeliness) {

    public enum Income { HIGH, MEDIUM, LOW }
    public enum DecisionStyle { CONSERVATIVE, AGGRESSIVE }
    public enum WorkTimeliness { MUST_ON_TIME, SLIGHT_LATE, EARLY_ARRIVAL }

    public static Profile sample(Random rng, Map<String, Map<String, Double>> weights) {
        return new Profile(
                pick(rng, Income.class, weights == null ? null : weights.get("income")),
                pick(rng, DecisionStyle.class, weights == null ? null : weights.get("decisionStyle")),
                pick(rng, WorkTimeliness.class, weights == null ? null : weights.get("workTimeliness")));
    }

    private static <E extends Enum<E>> E pick(Random rng, Class<E> type, Map<String, Double> weights) {
        E[] values = type.getEnumConstants();
        double total = 0;
        for (E v : values) total += weightOf(weights, v);
        if (total <= 0) total = values.length;
        double r = rng.nextDouble() * total;
        double acc = 0;
        for (E v : values) {
            double w = weightOf(weights, v);
            if (total == values.length) w = 1.0; // equal-probability fallback
            acc += w;
            if (r < acc) return v;
        }
        return values[values.length - 1];
    }

    private static <E extends Enum<E>> double weightOf(Map<String, Double> weights, E value) {
        if (weights == null) return 1.0;
        Double w = weights.get(value.name());
        return (w == null || w < 0) ? 0.0 : w;
    }

    /**
     * Natural-language descriptions. Kept qualitative on purpose (no numeric
     * value-of-time or cost-sensitivity), but written as "you"-voice traits so the
     * model role-plays a distinct personality. The static forms let the shared
     * system prompt enumerate every value once (cache-friendly); each traveler then
     * references its profile by enum name in its own message.
     */
    public String incomeDescription() { return incomeDescription(income); }
    public String decisionStyleDescription() { return decisionStyleDescription(decisionStyle); }
    public String workTimelinessDescription() { return workTimelinessDescription(workTimeliness); }

    public static String incomeDescription(Income v) {
        return switch (v) {
            case HIGH -> "high income — You have a high income and a comfortable household budget. Commuting expenses place relatively little pressure on your finances, although you still care about whether an expense is worthwhile.";
            case MEDIUM -> "medium income — You have a middle income and a household budget that requires some planning. Commuting expenses are a noticeable part of your regular spending, alongside other everyday needs.";
            case LOW -> "low income — You have a low income and a tight household budget. Commuting expenses compete with other everyday needs, so changes in these expenses matter to you.";
        };
    }

    public static String decisionStyleDescription(DecisionStyle v) {
        return switch (v) {
            case CONSERVATIVE -> "conservative — You value familiarity and predictability in your daily commute. You feel more comfortable with options you know from experience, while remaining willing to reconsider them when new information or experience gives you a reason.";
            case AGGRESSIVE -> "aggressive — You are comfortable considering unfamiliar commuting options and making decisions under uncertainty. You are curious about alternatives, while still taking their possible costs and consequences seriously";
        };
    }

    public static String workTimelinessDescription(WorkTimeliness v) {
        return switch (v) {
            case MUST_ON_TIME -> " must arrive on time —Your work requires you to arrive by the stated work start time. Arriving late has serious consequences for your work, and meeting this requirement is a high priority for you.";
            case SLIGHT_LATE -> "a little lateness is acceptable — Your workplace allows a small amount of flexibility around the stated work start time. Arriving within the stated lateness allowance is acceptable, but arriving beyond that allowance creates problems for your work.";
            case EARLY_ARRIVAL -> "prefers to arrive early — You prefer to arrive before the stated work start time so that you have time to settle in and prepare. Your preferred preparation period is given in your schedule information. Arriving earlier than that does not necessarily offer you additional benefit.";
        };
    }

    /** Every trait value + its description, once, for the shared system prompt. */
    public static String traitCatalog() {
        StringBuilder sb = new StringBuilder();
        sb.append("Income:\n");
        for (Income v : Income.values()) sb.append("  ").append(v.name()).append(": ").append(incomeDescription(v)).append('\n');
        sb.append("Decision style:\n");
        for (DecisionStyle v : DecisionStyle.values()) sb.append("  ").append(v.name()).append(": ").append(decisionStyleDescription(v)).append('\n');
        sb.append("Work timeliness:\n");
        for (WorkTimeliness v : WorkTimeliness.values()) sb.append("  ").append(v.name()).append(": ").append(workTimelinessDescription(v)).append('\n');
        return sb.toString();
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("income", income.name());
        m.put("decisionStyle", decisionStyle.name());
        m.put("workTimeliness", workTimeliness.name());
        return m;
    }
}
