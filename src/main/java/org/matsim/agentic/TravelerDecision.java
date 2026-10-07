package org.matsim.agentic;

/**
 * An accepted daily traveler decision: one route choice + one departure offset
 * for the outbound (home-&gt;work) leg and one for the return (work-&gt;home) leg.
 * Offsets are minutes within the grid -60..+60 in steps of 10. The route id must
 * come from the traveler's candidate set.
 */
public record TravelerDecision(
        String outboundRouteId,
        int outboundOffsetMinutes,
        String returnRouteId,
        int returnOffsetMinutes,
        String memory,
        String reason) {
}
