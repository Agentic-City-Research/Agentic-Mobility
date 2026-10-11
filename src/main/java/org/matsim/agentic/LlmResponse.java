package org.matsim.agentic;

/**
 * One LLM completion, plus the usage counters returned by the provider.
 * {@code promptCacheHitTokens} + {@code promptCacheMissTokens} == {@code promptTokens}
 * for OpenAI-compatible providers that report prompt caching (e.g. DeepSeek).
 * The mock client reports 0 cache hits (see {@link MockLlmClient}).
 */
public record LlmResponse(String content, long promptTokens, long completionTokens,
                          long promptCacheHitTokens, long promptCacheMissTokens, boolean usageReported,
                          String finishReason, int reasoningCharacters, boolean promptCacheUsageReported) {
    public LlmResponse(String content, long promptTokens, long completionTokens,
                       long promptCacheHitTokens, long promptCacheMissTokens, boolean usageReported,
                       String finishReason, int reasoningCharacters) {
        this(content,promptTokens,completionTokens,promptCacheHitTokens,promptCacheMissTokens,usageReported,
                finishReason,reasoningCharacters,usageReported);
    }
    public LlmResponse(String content, long promptTokens, long completionTokens,
                       long promptCacheHitTokens, long promptCacheMissTokens, boolean usageReported) {
        this(content, promptTokens, completionTokens, promptCacheHitTokens, promptCacheMissTokens, usageReported, null, 0);
    }
    public LlmResponse(String content, long promptTokens, long completionTokens, long promptCacheHitTokens, long promptCacheMissTokens) {
        this(content, promptTokens, completionTokens, promptCacheHitTokens, promptCacheMissTokens, true);
    }
}
