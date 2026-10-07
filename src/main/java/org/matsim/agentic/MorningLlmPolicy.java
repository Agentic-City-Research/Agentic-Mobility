package org.matsim.agentic;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** The live and replay adapter share prompts, validation, repair and audit logging. */
final class MorningLlmPolicy implements MorningRoutePolicy {
    static final String SYSTEM = MorningPromptRenderer.SYSTEM;
    private final LlmClient client;
    private final OutputWriter output;
    private final int repairAttempts, reasonMaxChars;
    private final MorningRequestBudget budget;
    private final Map<String, Object> modelSettings;
    private final MorningPromptRenderer renderer = new MorningPromptRenderer();
    private final AtomicLong attemptedCalls = new AtomicLong();

    MorningLlmPolicy(LlmClient client, OutputWriter output, int repairAttempts) {
        this(client, output, repairAttempts, 0);
    }
    MorningLlmPolicy(LlmClient client, OutputWriter output, int repairAttempts, int maxRequests) {
        this(client, output, repairAttempts, maxRequests, 240, Map.of());
    }
    MorningLlmPolicy(LlmClient client, OutputWriter output, int repairAttempts, int maxRequests,
                     int reasonMaxChars, Map<String, Object> modelSettings) {
        this(client, output, repairAttempts, reasonMaxChars, modelSettings, new MorningRequestBudget(maxRequests));
    }
    MorningLlmPolicy(LlmClient client, OutputWriter output, int repairAttempts,
                     int reasonMaxChars, Map<String, Object> modelSettings, MorningRequestBudget budget) {
        if (repairAttempts < 1 || reasonMaxChars < 1) throw new IllegalArgumentException("Invalid LLM policy limits");
        this.client = client; this.output = output; this.repairAttempts = repairAttempts;
        this.budget = budget; this.reasonMaxChars = reasonMaxChars;
        this.modelSettings = Collections.unmodifiableMap(new LinkedHashMap<>(modelSettings));
    }
    long attemptedCalls() { return attemptedCalls.get(); }

    @Override public Decision choose(Context context) {
        boolean joint = !context.departureOptions().isEmpty();
        String basePrompt = renderer.render(context), system = renderer.system(reasonMaxChars, joint);
        List<String> routeIds = context.candidates().stream().map(Option::routeId).toList();
        var departures = context.departureOptions().stream().map(DepartureOption::departureSeconds).toList();
        var schema = MorningOutputContract.choice(routeIds, reasonMaxChars, departures);
        var requestSettings = MorningOutputContract.requestSettings(modelSettings, schema);
        String error = null;
        long started = System.nanoTime();
        String contextHash = MorningPromptRenderer.hash(Json.write(context));
        String promptHash = MorningPromptRenderer.hash(system + "\n" + basePrompt);
        output.appendLine("decision-contexts.jsonl", context);
        for (int attempt = 1; attempt <= repairAttempts; attempt++) {
            budget.reserve(); attemptedCalls.incrementAndGet();
            String user = basePrompt + (error == null ? "" : "\nYour previous response was invalid. " + error
                    + "\nAllowed routeId values are exactly " + Json.write(routeIds)
                    + ". Do not follow old notes that suggest an unavailable route. "
                    + (joint ? "Allowed departureSeconds: " + Json.write(departures) + ". Return routeId, departureSeconds and reason" : "Return only routeId and one short reason")
                    + " (at most " + reasonMaxChars + " characters); finish the JSON object. All trip facts remain unchanged.");
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("day", context.day()); log.put("tripId", context.tripId()); log.put("personId", context.personId());
            log.put("attempt", attempt); log.put("repairCount", attempt - 1); log.put("templateVersion", MorningPromptRenderer.VERSION);
            log.put("contextHash", contextHash); log.put("basePromptHash", promptHash);
            log.put("system", system); log.put("user", user); log.put("modelSettings", requestSettings);
            log.put("outputContractVersion", MorningOutputContract.VERSION); log.put("outputSchema", schema);
            log.put("candidateOrder", routeIds); log.put("candidateCount", routeIds.size());
            log.put("routeChoiceEligible", routeIds.size() > 1);
            log.put("memoryUnknownRouteReferences", MorningOutputContract.unknownRouteReferences(context.memorySummary(), routeIds));
            long callStart = System.nanoTime();
            LlmResponse response;
            try { response = client.complete("traveler", system, user, schema); }
            catch (RuntimeException failure) {
                log.put("elapsedMillis", elapsed(callStart)); log.put("error", failure.toString());
                log.put("response", null); log.put("usage", null); log.put("valid", false); log.put("failureKind", "call");
                output.attempt(log); output.flush();
                throw failure;
            }
            log.put("elapsedMillis", elapsed(callStart)); log.put("response", response.content());
            MorningOutputContract.responseMetadata(log, response);
            log.put("usage", response.usageReported() ? Map.of("promptTokens", response.promptTokens(), "completionTokens", response.completionTokens(),
                    "promptCacheHitTokens", response.promptCacheHitTokens(), "promptCacheMissTokens", response.promptCacheMissTokens()) : null);
            log.put("usageReported", response.usageReported());
            Decision decision = null;
            try {
                MorningOutputContract.requireNotTruncated(response);
                com.fasterxml.jackson.databind.JsonNode node;
                try {
                    node = Json.MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(response.content());
                } catch (java.io.IOException invalidJson) {
                    throw new IllegalArgumentException("Response must be exactly one JSON object without duplicate keys, prose or code fences");
                }
                if (node == null) throw new IllegalArgumentException("Response must contain a JSON object");
                if (!node.isObject() || node.size() != (joint ? 3 : 2) || !node.has("routeId") || !node.has("reason")
                        || joint && !node.path("departureSeconds").isNumber()
                        || !node.get("routeId").isTextual() || !node.get("reason").isTextual() || node.get("reason").asText().isBlank())
                    throw new IllegalArgumentException(joint ? "Required fields: routeId, numeric departureSeconds, nonempty reason only" : "Only string routeId and nonempty reason are allowed; no other fields");
                String reason = node.get("reason").asText();
                if (reason.codePointCount(0, reason.length()) > reasonMaxChars)
                    throw new IllegalArgumentException("Reason exceeds " + reasonMaxChars + " characters");
                String routeId = node.get("routeId").asText();
                if (context.candidates().stream().noneMatch(o -> o.routeId().equals(routeId)))
                    throw new IllegalArgumentException("routeId must be one of " + Json.write(routeIds)
                            + "; unavailable routes are not actions, regardless of memory or punctuality preferences");
                decision = MorningRoutePolicy.validate(context, new Decision(routeId, reason,
                        joint ? node.get("departureSeconds").asDouble() : null)); error = null;
            } catch (LlmException | IllegalArgumentException invalid) { error = invalid.getMessage(); }
            log.put("error", error); log.put("valid", error == null); log.put("failureKind", error == null ? null : "output");
            output.attempt(log);
            if (error == null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("day", context.day()); result.put("tripId", context.tripId()); result.put("personId", context.personId());
                result.put("routeId", decision.routeId()); result.put("reason", decision.reason());
                result.put("departureSeconds", decision.departureSeconds());
                result.put("repairCount", attempt - 1); result.put("calls", attempt); result.put("elapsedMillis", elapsed(started));
                result.put("contextHash", contextHash); result.put("basePromptHash", promptHash);
                result.put("modelSettings", requestSettings); result.put("candidateOrder", routeIds);
                result.put("outputContractVersion", MorningOutputContract.VERSION); result.put("outputSchema", schema);
                result.put("candidateCount", routeIds.size()); result.put("routeChoiceEligible", routeIds.size() > 1);
                MorningOutputContract.responseMetadata(result, response);
                output.appendLine("llm-results.jsonl", result);
                return decision;
            }
        }
        output.flush();
        throw new LlmException("Morning traveler " + context.tripId() + " exhausted " + repairAttempts
                + " output attempts on day " + context.day() + ": " + error);
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
}
