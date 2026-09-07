package org.matsim.agentic.api;

public record TokenUsage(
        long promptTokens,
        long promptCacheHitTokens,
        long promptCacheMissTokens,
        long completionTokens,
        long totalTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0, 0);
}
