package org.matsim.agentic.api;

public record AgentExperience(
        int iteration,
        String mode,
        double travelTimeMinutes,
        double distanceKm,
        double score,
        double firstDepartureHour) {
}
