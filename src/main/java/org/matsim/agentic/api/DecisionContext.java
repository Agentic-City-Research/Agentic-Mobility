package org.matsim.agentic.api;

import java.util.Set;

public record DecisionContext(
        String personId,
        int targetIteration,
        Integer age,
        String sex,
        boolean carAvailable,
        String currentMode,
        Set<String> allowedModes,
        Set<Integer> allowedDepartureShifts,
        double dailyBeelineKm,
        String activityChain,
        AgentExperience experience,
        String recentHistory,
        String longTermMemory) {

    public DecisionContext withPolicy(String policy) {
        if (policy == null || policy.isBlank()) return this;
        return new DecisionContext(personId, targetIteration, age, sex, carAvailable,
                currentMode, allowedModes, allowedDepartureShifts, dailyBeelineKm,
                activityChain, experience, recentHistory,
                (longTermMemory == null ? "" : longTermMemory) + policy);
    }

    public String toPrompt() {
        String observed = experience == null
                ? "none; this is the initial decision"
                : ("iteration=%d, executed_mode=%s, travel_time_min=%.3f, "
                + "distance_km=%.3f, score=%.4f, first_departure_hour=%.3f")
                .formatted(experience.iteration(), experience.mode(),
                        experience.travelTimeMinutes(), experience.distanceKm(),
                        experience.score(), experience.firstDepartureHour());
        return """
                TRAVELLER_PROFILE_V2
                person_id=%s
                age=%s
                sex=%s
                car_available=%s
                daily_beeline_km=%.3f
                activity_chain=%s
                END_TRAVELLER_PROFILE

                ITERATION_STATE
                target_iteration=%d
                current_mode=%s
                allowed_modes=%s
                allowed_departure_shifts=%s
                newest_outcome=%s
                recent_history=%s
                top_action_statistics=%s
                END_ITERATION_STATE
                """.formatted(
                personId, age == null ? "unknown" : age,
                sex == null ? "unknown" : sex, carAvailable, dailyBeelineKm,
                activityChain, targetIteration, currentMode, allowedModes,
                allowedDepartureShifts, observed,
                recentHistory == null || recentHistory.isBlank()
                        ? "none" : recentHistory.replace(System.lineSeparator(), " | "),
                longTermMemory == null || longTermMemory.isBlank()
                        ? "none" : longTermMemory);
    }
}
