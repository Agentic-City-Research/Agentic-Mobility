package org.matsim.agentic.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.matsim.agentic.api.AgentAction;
import org.matsim.agentic.api.DecisionContext;
import org.matsim.agentic.api.DecisionProvider;
import org.matsim.agentic.api.TokenUsage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Minimal adapter for providers exposing the OpenAI chat-completions protocol. */
public final class OpenAICompatibleDecisionProvider implements DecisionProvider {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final Settings settings;
    private final String systemPrompt;

    public OpenAICompatibleDecisionProvider(Settings settings) {
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            throw new IllegalArgumentException("An API key is required for the openai-compatible provider");
        }
        this.settings = settings;
        this.systemPrompt = loadPrompt(settings.promptResource());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public AgentAction decide(DecisionContext context) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", settings.model());
        body.put("temperature", settings.temperature());
        body.put("max_tokens", settings.maxTokens());
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", context.toPrompt());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(settings.baseUrl().replaceAll("/+$", "")
                        + "/chat/completions"))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        IOException last = null;
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return parse(response.body(), context);
                }
                last = new IOException("Provider HTTP " + response.statusCode()
                        + ": " + compact(response.body(), 400));
                if (!Set.of(429, 500, 502, 503, 504).contains(response.statusCode())
                        || attempt == settings.maxAttempts()) {
                    throw last;
                }
            } catch (IOException exception) {
                last = exception;
                if (attempt == settings.maxAttempts()) {
                    throw exception;
                }
            }
            Thread.sleep(attempt * 1_000L);
        }
        throw last == null ? new IOException("Provider request failed") : last;
    }

    private AgentAction parse(String responseBody, DecisionContext context) throws IOException {
        JsonNode envelope = mapper.readTree(responseBody);
        JsonNode content = envelope.at("/choices/0/message/content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new IOException("Provider returned empty content");
        }
        JsonNode json = mapper.readTree(content.asText());
        String mode = json.path("mode").asText("").trim().toLowerCase(Locale.ROOT);
        if (!context.allowedModes().contains(mode)) {
            throw new IOException("Provider returned disallowed mode: " + mode);
        }
        int shift = json.path("departure_shift_min").asInt(Integer.MIN_VALUE);
        if (!context.allowedDepartureShifts().contains(shift)) {
            throw new IOException("Provider returned disallowed departure shift: " + shift);
        }
        double confidence = Math.max(0.0, Math.min(1.0,
                json.path("confidence").asDouble(0.0)));
        JsonNode usage = envelope.path("usage");
        TokenUsage tokenUsage = new TokenUsage(
                usage.path("prompt_tokens").asLong(0L),
                usage.path("prompt_cache_hit_tokens").asLong(0L),
                usage.path("prompt_cache_miss_tokens").asLong(0L),
                usage.path("completion_tokens").asLong(0L),
                usage.path("total_tokens").asLong(0L));
        return new AgentAction(mode, shift, confidence,
                compact(json.path("reason").asText(""), 240),
                name(), false, "", tokenUsage);
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    private static String loadPrompt(String resource) {
        try (InputStream input = OpenAICompatibleDecisionProvider.class
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Prompt resource not found: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load prompt: " + resource, exception);
        }
    }

    private static String compact(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() <= maxLength ? compact : compact.substring(0, maxLength);
    }

    public record Settings(
            String apiKey,
            String baseUrl,
            String model,
            int timeoutSeconds,
            int maxAttempts,
            double temperature,
            int maxTokens,
            String promptResource) {
    }
}
