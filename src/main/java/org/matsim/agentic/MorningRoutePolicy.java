package org.matsim.agentic;

import java.util.*;

/** Traveler policy boundary: choices use this trip's supplied routes and, when enabled, departure options.
 * Context contains real information, not a MATSim utility score. Missing personal
 * preferences remain absent rather than being invented from age or sex.
 */
@FunctionalInterface
public interface MorningRoutePolicy {
    record TollObservation(int day, String linkId, double time, double amount) {}
    record AnnouncedToll(String linkId, double startSeconds, double endSeconds, double amount) {}
    record PricingKnowledge(String group, String source, double baseMonetaryCost, double knownTollSubtotal,
                            List<String> unknownLinks, List<TollObservation> observations,
                            List<AnnouncedToll> announcement) {}
    record Information(String group, boolean currentAnnouncementVisible, boolean networkTrafficVisible,
                       int tollHistoryBinSeconds, List<TollObservation> personalPayments) {}
    record Option(String routeId, List<String> links, double expectedCarSeconds,
                  double expectedArrivalSeconds, double distanceMeters, Double estimatedMonetaryCost,
                  String estimateSource, int experiencedCount, int chosenCount,
                  Integer forecastObservedDay, Integer lastPersonalExperienceDay,
                  Double estimatedToll, String tollPolicyId, int tollPolicyVersion, PricingKnowledge pricingKnowledge) {
        public Option {
            // Saved pre-pricing contexts had no toll field. New unknown costs
            // always carry explicit PricingKnowledge and must remain null.
            if (pricingKnowledge == null && estimatedToll == null) estimatedToll = 0.0;
        }
        public Option(String routeId, List<String> links, double expectedCarSeconds, double expectedArrivalSeconds,
                      double distanceMeters, double estimatedMonetaryCost, String estimateSource, int experiencedCount,
                      int chosenCount, Integer forecastObservedDay, Integer lastPersonalExperienceDay,
                      double estimatedToll, String tollPolicyId, int tollPolicyVersion) {
            this(routeId, links, expectedCarSeconds, expectedArrivalSeconds, distanceMeters, estimatedMonetaryCost,
                    estimateSource, experiencedCount, chosenCount, forecastObservedDay, lastPersonalExperienceDay,
                    estimatedToll, tollPolicyId, tollPolicyVersion, null);
        }
        public Option(String routeId, List<String> links, double expectedCarSeconds, double expectedArrivalSeconds,
                      double distanceMeters, double estimatedMonetaryCost, String estimateSource, int experiencedCount,
                      int chosenCount, Integer forecastObservedDay, Integer lastPersonalExperienceDay) {
            this(routeId, links, expectedCarSeconds, expectedArrivalSeconds, distanceMeters, estimatedMonetaryCost,
                    estimateSource, experiencedCount, chosenCount, forecastObservedDay, lastPersonalExperienceDay, 0, null, 0);
        }
        public Option(String routeId, List<String> links, double expectedCarSeconds, double expectedArrivalSeconds,
                      double distanceMeters, double estimatedMonetaryCost, String estimateSource, int experiencedCount,
                      int chosenCount) {
            this(routeId, links, expectedCarSeconds, expectedArrivalSeconds, distanceMeters, estimatedMonetaryCost,
                    estimateSource, experiencedCount, chosenCount, null, null);
        }
    }
    record DepartureOption(double departureSeconds, List<Option> routes) {}
    record Context(int day, String tripId, String personId, String originPurpose, String destinationPurpose,
                   double plannedDepartureSeconds, Double latestArrivalSeconds, Map<String, Object> personAttributes,
                   Map<String, Object> schedule, List<Option> candidates, List<MorningMemory.Experience> history,
                   Profile profile, String memorySummary, Integer memorySummaryDay, Information information,
                   List<DepartureOption> departureOptions) {
        public Context {
            memorySummary = memorySummary == null ? "" : memorySummary;
            departureOptions = departureOptions == null ? List.of() : List.copyOf(departureOptions);
        }
        public Context(int day, String tripId, String personId, String originPurpose, String destinationPurpose,
                       double plannedDepartureSeconds, Double latestArrivalSeconds, Map<String, Object> personAttributes,
                       Map<String, Object> schedule, List<Option> candidates, List<MorningMemory.Experience> history,
                       Profile profile, String memorySummary, Integer memorySummaryDay, Information information) {
            this(day, tripId, personId, originPurpose, destinationPurpose, plannedDepartureSeconds, latestArrivalSeconds,
                    personAttributes, schedule, candidates, history, profile, memorySummary, memorySummaryDay, information, List.of());
        }
        public Context(int day, String tripId, String personId, String originPurpose, String destinationPurpose,
                       double plannedDepartureSeconds, Double latestArrivalSeconds, Map<String, Object> personAttributes,
                       Map<String, Object> schedule, List<Option> candidates, List<MorningMemory.Experience> history,
                       Profile profile, String memorySummary, Integer memorySummaryDay) {
            this(day, tripId, personId, originPurpose, destinationPurpose, plannedDepartureSeconds, latestArrivalSeconds,
                    personAttributes, schedule, candidates, history, profile, memorySummary, memorySummaryDay, null);
        }
        /** Legacy saved contexts contain neither a generated profile nor a summary. */
        public Context(int day, String tripId, String personId, String originPurpose, String destinationPurpose,
                       double plannedDepartureSeconds, Double latestArrivalSeconds, Map<String, Object> personAttributes,
                       Map<String, Object> schedule, List<Option> candidates, List<MorningMemory.Experience> history) {
            this(day, tripId, personId, originPurpose, destinationPurpose, plannedDepartureSeconds, latestArrivalSeconds,
                    personAttributes, schedule, candidates, history, null, "", null);
        }
    }
    record Decision(String routeId, String reason, Double departureSeconds) {
        public Decision(String routeId, String reason) { this(routeId, reason, null); }
    }

    Decision choose(Context context);

    static Decision validate(Context context, Decision decision) {
        if (decision == null || context.candidates().stream().noneMatch(c -> c.routeId().equals(decision.routeId())))
            throw new IllegalArgumentException("Invalid route decision for " + context.tripId() + ": " + decision);
        if (!context.departureOptions().isEmpty() && (decision.departureSeconds() == null
                || !Double.isFinite(decision.departureSeconds()) || context.departureOptions().stream()
                .noneMatch(o -> o.departureSeconds() == decision.departureSeconds())))
            throw new IllegalArgumentException("Departure must be one of the supplied original-plan options");
        if (context.departureOptions().isEmpty() && decision.departureSeconds() != null
                && decision.departureSeconds() != context.plannedDepartureSeconds())
            throw new IllegalArgumentException("Departure change is unavailable in this route-only context");
        return decision;
    }

    /** beta is per minute; this research baseline is NOT MATSim ChangeExpBeta. */
    static MorningRoutePolicy baseline(String name, long seed, double beta) {
        Random random = new Random(seed);
        return context -> {
            List<Option> options = context.candidates();
            if (name.equals("mock")) {
                Option habitual = options.stream().max(Comparator.comparingInt(Option::chosenCount)).orElseThrow();
                return new Decision(habitual.routeId(), "Offline mock: retain most frequently chosen route");
            }
            double minimum = options.stream().mapToDouble(Option::expectedCarSeconds).min().orElseThrow();
            if (name.equals("shortest")) return new Decision(options.stream()
                    .min(Comparator.comparingDouble(Option::expectedCarSeconds)).orElseThrow().routeId(), "Minimum expected car time");
            if (!name.equals("mnl")) throw new IllegalArgumentException("Unknown policy: " + name);
            double total = options.stream().mapToDouble(o -> Math.exp(-beta * (o.expectedCarSeconds() - minimum) / 60)).sum();
            double draw = random.nextDouble() * total;
            for (Option option : options) {
                draw -= Math.exp(-beta * (option.expectedCarSeconds() - minimum) / 60);
                if (draw <= 0) return new Decision(option.routeId(), "Seeded multinomial logit on expected car time");
            }
            return new Decision(options.getLast().routeId(), "Seeded multinomial logit on expected car time");
        };
    }
}
