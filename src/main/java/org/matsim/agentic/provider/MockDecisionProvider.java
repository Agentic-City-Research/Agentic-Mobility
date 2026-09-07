package org.matsim.agentic.provider;

import org.matsim.agentic.api.AgentAction;
import org.matsim.agentic.api.DecisionContext;
import org.matsim.agentic.api.DecisionProvider;

/** Deterministic, network-free provider used by examples, tests and CI. */
public final class MockDecisionProvider implements DecisionProvider {

    @Override
    public AgentAction decide(DecisionContext context) {
        String mode;
        if (context.experience() != null
                && context.experience().travelTimeMinutes() > 55.0
                && context.currentMode().equals("car")
                && context.allowedModes().contains("pt")) {
            mode = "pt";
        } else if (context.dailyBeelineKm() <= 3.5
                && context.allowedModes().contains("walk")) {
            mode = "walk";
        } else if (context.carAvailable()
                && context.allowedModes().contains("car")) {
            mode = "car";
        } else if (context.allowedModes().contains(context.currentMode())) {
            mode = context.currentMode();
        } else {
            mode = context.allowedModes().iterator().next();
        }
        int preferredShift = context.experience() != null
                && context.experience().travelTimeMinutes() > 45.0 ? -15 : 0;
        int shift = context.allowedDepartureShifts().contains(preferredShift)
                ? preferredShift : context.allowedDepartureShifts().iterator().next();
        return new AgentAction(mode, shift, 1.0,
                "Deterministic adaptive mock rule", name(), false, "");
    }

    @Override
    public String name() {
        return "mock";
    }
}
