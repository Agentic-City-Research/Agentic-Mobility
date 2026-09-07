package org.matsim.agentic.provider;

import org.junit.jupiter.api.Test;
import org.matsim.agentic.api.AgentExperience;
import org.matsim.agentic.api.DecisionContext;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MockDecisionProviderTest {

    private final MockDecisionProvider provider = new MockDecisionProvider();

    @Test
    void choosesOnlyAllowedActions() {
        DecisionContext context = context(Set.of("walk"), Set.of(-15, 0), null);
        var action = provider.decide(context);

        assertEquals("walk", action.mode());
        assertEquals(0, action.departureShiftMinutes());
        assertFalse(action.fallback());
    }

    @Test
    void adaptsAfterLongCarTripWhenTransitIsAvailable() {
        AgentExperience experience = new AgentExperience(
                4, "car", 70.0, 20.0, -10.0, 8.0);
        DecisionContext context = context(
                Set.of("car", "pt", "walk"), Set.of(-15, 0), experience);

        var action = provider.decide(context);

        assertEquals("pt", action.mode());
        assertEquals(-15, action.departureShiftMinutes());
    }

    private static DecisionContext context(
            Set<String> modes,
            Set<Integer> shifts,
            AgentExperience experience) {
        return new DecisionContext("p1", 5, 30, "x", true, "car",
                modes, shifts, 10.0, "home -> work", experience,
                "", "");
    }
}
