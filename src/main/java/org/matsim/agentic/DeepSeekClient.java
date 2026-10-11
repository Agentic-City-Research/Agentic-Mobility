package org.matsim.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI-compatible chat-completions client (DeepSeek). Uses the JDK HTTP
 * client and Jackson, both bundled in the shaded jar, so no extra dependencies.
 * Retries transient transport errors up to 5 times. Thread-safe: HttpClient and the usage tracker
 * are safe for concurrent use.
 */
public final class DeepSeekClient implements LlmClient {

    static final int CONNECT_TIMEOUT_SECONDS = 30;
    static final int MAX_TRANSPORT_ATTEMPTS = 5;
    private static final long MAX_BACKOFF_MILLIS = 30_000L;

    private final AgenticConfig config;
    private final LlmUsageTracker usage;
    private final HttpClient http;

    public DeepSeekClient(AgenticConfig config, LlmUsageTracker usage) {
        if (config.timeoutSeconds <= 0) throw new IllegalArgumentException("timeout_seconds must be positive");
        this.config = config;
        config.generationSettings();
        this.usage = usage;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();
    }

    @Override
    public LlmResponse complete(String role, String system, String user) {
        return complete(role, system, user, null);
    }

    @Override
    public LlmResponse complete(String role, String system, String user, Map<String, Object> outputSchema) {
        usage.check();
        String base = stripTrailingSlash(config.baseUrl);
        String endpoint = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        String body = buildBody(system, user, outputSchema);

        IOException last = null;
        for (int attempt = 0; attempt < MAX_TRANSPORT_ATTEMPTS; attempt++) {
            usage.check();
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(config.timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = sendWithDeadline(request);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    LlmResponse parsed = parse(response.body());
                    usage.record(parsed);
                    return parsed;
                }
                last = new IOException("HTTP " + response.statusCode() + ": " + truncate(response.body()));
                if (!isRetryableStatus(response.statusCode())) {
                    throw new LlmException("LLM API rejected the request: " + last.getMessage(), last);
                }
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("interrupted during LLM call", e);
            }
            if (attempt + 1 < MAX_TRANSPORT_ATTEMPTS) sleepBackoff(attempt);
        }
        throw new LlmTransportException("LLM transport failed after " + MAX_TRANSPORT_ATTEMPTS + " attempts: "
                + (last == null ? "unknown" : last.getMessage()), last);
    }

    private HttpResponse<String> sendWithDeadline(HttpRequest request) throws IOException, InterruptedException {
        // Bound the entire exchange, including receipt of the full response body.
        CompletableFuture<HttpResponse<String>> pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return pending.get(config.timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("LLM full-response timeout after " + config.timeoutSeconds + " seconds", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("LLM HTTP exchange failed", e.getCause());
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private String buildBody(String system, String user, Map<String, Object> outputSchema) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("model", config.model);
        ArrayNode messages = root.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", system);
        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", user);
        config.generationSettings(outputSchema).forEach((key, value) -> root.set(key, Json.MAPPER.valueToTree(value)));
        return root.toString();
    }

    private LlmResponse parse(String body) {
        JsonNode root = Json.readTree(body);
        String content = "";
        String finishReason = null;
        int reasoningCharacters = 0;
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode finish = choices.get(0).get("finish_reason");
            if (finish != null && !finish.isNull()) finishReason = finish.asText();
            JsonNode message = choices.get(0).get("message");
            if (message != null) {
                JsonNode c = message.get("content");
                if (c != null && !c.isNull()) content = c.asText();
                JsonNode reasoning = message.get("reasoning_content");
                if (reasoning == null || reasoning.isNull()) reasoning = message.get("reasoning");
                if (reasoning != null && reasoning.isTextual()) {
                    String text = reasoning.asText();
                    reasoningCharacters = text.codePointCount(0, text.length());
                }
            }
        }
        long prompt = 0, completion = 0, cacheHit = 0, cacheMiss = 0;
        JsonNode usageNode = root.get("usage");
        if (usageNode != null) {
            if (usageNode.get("prompt_tokens") != null) prompt = usageNode.get("prompt_tokens").asLong();
            if (usageNode.get("completion_tokens") != null) completion = usageNode.get("completion_tokens").asLong();
            if (usageNode.get("prompt_cache_hit_tokens") != null) cacheHit = usageNode.get("prompt_cache_hit_tokens").asLong();
            if (usageNode.get("prompt_cache_miss_tokens") != null) cacheMiss = usageNode.get("prompt_cache_miss_tokens").asLong();
        }
        if (usageNode != null && usageNode.path("prompt_tokens_details").has("cached_tokens"))
            cacheHit = usageNode.path("prompt_tokens_details").path("cached_tokens").asLong();
        boolean cacheReported = usageNode != null && (usageNode.path("prompt_cache_hit_tokens").isNumber()
                || usageNode.path("prompt_cache_miss_tokens").isNumber() || usageNode.path("prompt_tokens_details").path("cached_tokens").isNumber());
        if (cacheReported && !usageNode.path("prompt_cache_miss_tokens").isNumber()
                && usageNode.path("prompt_tokens").isNumber()) cacheMiss = Math.max(0,prompt-cacheHit);
        return new LlmResponse(content, prompt, completion, cacheHit, cacheMiss,
                usageNode != null && !usageNode.isNull(), finishReason, reasoningCharacters, cacheReported);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private static void sleepBackoff(int failedAttempt) {
        long base = Math.min(MAX_BACKOFF_MILLIS, 1_000L << Math.min(failedAttempt, 5));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 2L + 1L));
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted during LLM retry backoff", e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }
}
