package org.matsim.agentic.api;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionContextTest {

    @Test
    void rendersBoundedActionSpaceAndLatestOutcome() {
        DecisionContext context = new DecisionContext(
                "traveller-7", 3, 41, "female", true, "car",
                Set.of("car", "walk"), Set.of(-15, 0, 15), 8.2,
                "home -> work", new AgentExperience(
                2, "car", 31.5, 12.0, -4.2, 8.0),
                "decision it=2", "action=car@0 n=2");

        String prompt = context.toPrompt();

        assertTrue(prompt.contains("person_id=traveller-7"));
        assertTrue(prompt.contains("target_iteration=3"));
        assertTrue(prompt.contains("travel_time_min=31.500"));
        assertTrue(prompt.contains("allowed_departure_shifts="));
    }
}
