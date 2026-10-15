package org.matsim.agentic;

import java.util.Map;

/**
 * Shared model-service settings. MorningModelOptions applies traveler defaults.
 */
public final class AgenticConfig {

    public String configPath;
    public String outputDirectory = "output";

    public int days = 100;
    public int llmAgents = 100;
    public long seed = 42;
    /** Fraction of the population to keep, in (0, 1]. 1.0 keeps everyone (default, unchanged). */
    public double sample = 1.0;
    /** Capacity scale factor; null means "follow sample". Explicit 1.0 = full network capacity. */
    public Double capacityFactor = null;
    public double informedShare = 0.5;
    public int concurrency = 8;
    public int repairAttempts = 3;

    public String model = envOr("AGENTIC_MODEL", "deepseek-flash");
    public String baseUrl = envOr("AGENTIC_BASE_URL", "https://api.deepseek.com");
    public String apiKey = System.getenv("AGENTIC_API_KEY");
    public String thinking = envOr("AGENTIC_THINKING", "auto");
    public Double temperature = envDouble("AGENTIC_TEMPERATURE");
    public Double topP = envDouble("AGENTIC_TOP_P");
    public Integer generationSeed = optionalEnvInt("AGENTIC_GENERATION_SEED");
    public String tokenLimitField = envOr("AGENTIC_TOKEN_LIMIT_FIELD", "max_tokens");
    public String responseFormat = envOr("AGENTIC_RESPONSE_FORMAT", "omit");
    public String generationParams = envOr("AGENTIC_GENERATION_PARAMS", "{}");
    public int reasonMaxChars = envInt("AGENTIC_REASON_MAX_CHARS", 240);
    public int timeoutSeconds = envInt("AGENTIC_TIMEOUT_SECONDS", 300);
    public int llmStallSeconds = envInt("AGENTIC_LLM_STALL_SECONDS", 600);
    public int maxOutputTokens = envInt("AGENTIC_MAX_OUTPUT_TOKENS", 4096);
    public int maxRequests = envInt("AGENTIC_MAX_REQUESTS", 25000);

    public boolean noManager = false;
    public boolean mock = false;
    public boolean validateOnly = false;
    public String profilesJson = null;
    public Map<String, Map<String, Double>> profileWeights = null;

    /** @return the 13 departure offsets (minutes) allowed by the grid: -60..+60 in steps of 10. */
    public static int[] offsets() {
        int[] o = new int[13];
        for (int i = 0; i < 13; i++) o[i] = (i - 6) * 10;
        return o;
    }

    /** @return true iff {@code offset} is a valid grid offset in minutes. */
    public static boolean isGridOffset(int offset) {
        return offset >= -60 && offset <= 60 && offset % 10 == 0;
    }

    /** Effective request options, without credentials. Omit options unsupported by a provider. */
    public Map<String, Object> generationSettings() {
        if (!java.util.Set.of("auto", "omit", "enabled", "disabled").contains(thinking)
                || !java.util.Set.of("max_tokens", "max_completion_tokens").contains(tokenLimitField)
                || !java.util.Set.of("omit", "json_object", "json_schema").contains(responseFormat)
                || maxOutputTokens < 1 || reasonMaxChars < 1 || model == null || model.isBlank()
                || temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)
                || topP != null && (!Double.isFinite(topP) || topP <= 0 || topP > 1))
            throw new IllegalArgumentException("Invalid model generation settings");
        var uri = java.net.URI.create(baseUrl);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("API base URL must be HTTP(S), without credentials/query/fragment");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put(tokenLimitField, maxOutputTokens);
        if (temperature != null) result.put("temperature", temperature);
        if (topP != null) result.put("top_p", topP);
        if (generationSeed != null) result.put("seed", generationSeed);
        String effectiveThinking = thinking.equals("auto")
                ? (uri.getHost().equals("api.deepseek.com") ? "disabled" : "omit") : thinking;
        if (!effectiveThinking.equals("omit")) result.put("thinking", Map.of("type", effectiveThinking));
        if (responseFormat.equals("json_object")) result.put("response_format", Map.of("type", responseFormat));
        var extras = Json.readTree(generationParams);
        if (!extras.isObject()) throw new IllegalArgumentException("Generation params must be a JSON object");
        extras.fields().forEachRemaining(e -> {
            if (java.util.Set.of("model", "messages", "stream", "max_tokens", "max_completion_tokens", "api_key", "Authorization").contains(e.getKey())
                    || responseFormat.equals("json_schema") && java.util.Set.of("response_format", "structured_outputs").contains(e.getKey())
                    || result.containsKey(e.getKey())) throw new IllegalArgumentException("Conflicting/reserved generation parameter: " + e.getKey());
            result.put(e.getKey(), Json.MAPPER.convertValue(e.getValue(), Object.class));
        });
        return result;
    }
    /** json_schema is opt-in; its schema must be supplied for this specific decision/reflection. */
    Map<String, Object> generationSettings(Map<String, Object> outputSchema) {
        var settings = new java.util.LinkedHashMap<>(generationSettings());
        if (responseFormat.equals("json_schema")) {
            if (outputSchema == null || outputSchema.isEmpty())
                throw new IllegalArgumentException("json_schema requires a per-request output schema; supported by morning choices/reflections/replay");
            settings.put("response_format", MorningOutputContract.responseFormat(outputSchema));
        }
        return settings;
    }
    public Map<String, Object> publicModelSettings() {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("model", model); result.put("baseUrl", baseUrl); result.put("generation", generationSettings());
        result.put("responseFormat", responseFormat);
        result.put("reasonMaxChars", reasonMaxChars); result.put("timeoutSeconds", timeoutSeconds);
        return result;
    }
    /** Same provider/sampling settings and credentials, with a role-specific output limit. */
    AgenticConfig withOutputLimit(int tokens) {
        var copy = new AgenticConfig();
        copy.model = model; copy.baseUrl = baseUrl; copy.apiKey = apiKey; copy.thinking = thinking;
        copy.temperature = temperature; copy.topP = topP; copy.generationSeed = generationSeed;
        copy.tokenLimitField = tokenLimitField; copy.responseFormat = responseFormat; copy.generationParams = generationParams;
        copy.reasonMaxChars = reasonMaxChars; copy.timeoutSeconds = timeoutSeconds;
        copy.llmStallSeconds = llmStallSeconds; copy.maxRequests = maxRequests; copy.maxOutputTokens = tokens;
        copy.concurrency = concurrency; copy.repairAttempts = repairAttempts;
        copy.generationSettings();
        return copy;
    }
    private static Double envDouble(String name) {
        String v = System.getenv(name); return v == null || v.isBlank() ? null : Double.valueOf(v);
    }
    private static Integer optionalEnvInt(String name) {
        String v = System.getenv(name); return v == null || v.isBlank() ? null : Integer.valueOf(v);
    }
    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static int envInt(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
