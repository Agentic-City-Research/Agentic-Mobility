package org.matsim.agentic.api;

public record AgentAction(
        String mode,
        int departureShiftMinutes,
        double confidence,
        String reason,
        String source,
        boolean fallback,
        String error,
        TokenUsage tokenUsage) {

    public AgentAction(
            String mode,
            int departureShiftMinutes,
            double confidence,
            String reason,
            String source,
            boolean fallback,
            String error) {
        this(mode, departureShiftMinutes, confidence, reason, source, fallback,
                error, TokenUsage.ZERO);
    }
}
