package org.matsim.agentic;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared request/token budget across both roles, output repair and HTTP retries.
 * The cap is a request-count cap, not a monetary budget cap.
 */
public final class LlmUsageTracker {

    private final int maxRequests;
    private long requests;
    private long promptTokens;
    private long completionTokens;
    private long promptCacheHitTokens;
    private long promptCacheMissTokens;
    private long responsesWithoutUsage;

    public LlmUsageTracker(int maxRequests) {
        this.maxRequests = maxRequests;
    }

    public synchronized void record(LlmResponse response) {
        requests++;
        if (!response.usageReported()) responsesWithoutUsage++;
        promptTokens += response.promptTokens();
        completionTokens += response.completionTokens();
        promptCacheHitTokens += response.promptCacheHitTokens();
        promptCacheMissTokens += response.promptCacheMissTokens();
    }

    public synchronized void check() {
        if (maxRequests > 0 && requests >= maxRequests) {
            throw new LlmException("shared request budget exhausted (max " + maxRequests + ")");
        }
    }

    public synchronized long requests() {
        return requests;
    }

    public synchronized Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_requests", maxRequests);
        m.put("requests", requests);
        m.put("responses_without_usage", responsesWithoutUsage);
        m.put("prompt_tokens", promptTokens);
        m.put("prompt_cache_hit_tokens", promptCacheHitTokens);
        m.put("prompt_cache_miss_tokens", promptCacheMissTokens);
        m.put("completion_tokens", completionTokens);
        m.put("total_tokens", promptTokens + completionTokens);
        return m;
    }
}
