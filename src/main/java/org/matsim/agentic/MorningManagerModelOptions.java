package org.matsim.agentic;

import picocli.CommandLine.Option;

import java.net.URI;
import java.util.Map;

/** Optional manager service overrides; credentials never enter CLI arguments or public settings. */
final class MorningManagerModelOptions {
    @Option(names = "--manager-model") String model;
    @Option(names = "--manager-base-url") String baseUrl;
    @Option(names = "--manager-thinking", description = "auto | omit | enabled | disabled") String thinking;
    @Option(names = "--manager-response-format", description = "omit | json_object | json_schema") String responseFormat;
    @Option(names = "--manager-generation-params", description = "Manager-only provider parameters as a JSON object") String generationParams;
    @Option(names = "--manager-token-limit-field", description = "max_tokens | max_completion_tokens") String tokenLimitField;
    @Option(names = "--manager-temperature") Double temperature;
    @Option(names = "--manager-top-p") Double topP;
    @Option(names = "--manager-generation-seed") Integer generationSeed;
    @Option(names = "--manager-timeout-seconds") Integer timeoutSeconds;
    private final String apiKey;

    MorningManagerModelOptions() { this(System.getenv()); }

    MorningManagerModelOptions(Map<String, String> environment) {
        model = value(environment, "MODEL");
        baseUrl = value(environment, "BASE_URL");
        thinking = value(environment, "THINKING");
        responseFormat = value(environment, "RESPONSE_FORMAT");
        generationParams = value(environment, "GENERATION_PARAMS");
        tokenLimitField = value(environment, "TOKEN_LIMIT_FIELD");
        temperature = decimal(environment, "TEMPERATURE");
        topP = decimal(environment, "TOP_P");
        generationSeed = integer(environment, "GENERATION_SEED");
        timeoutSeconds = integer(environment, "TIMEOUT_SECONDS");
        apiKey = value(environment, "API_KEY");
    }

    AgenticConfig resolve(AgenticConfig traveler) {
        AgenticConfig resolved = traveler.withOutputLimit(traveler.maxOutputTokens);
        if (separateEndpoint(traveler)) {
            if (model == null || model.isBlank())
                throw new IllegalArgumentException("An independent manager endpoint requires --manager-model or AGENTIC_MANAGER_MODEL");
            // A different service must not receive the traveler's key or provider-specific options.
            resolved.apiKey = null;
            resolved.thinking = "auto";
            resolved.temperature = null;
            resolved.topP = null;
            resolved.generationSeed = null;
            resolved.tokenLimitField = "max_tokens";
            resolved.responseFormat = "api.deepseek.com".equalsIgnoreCase(URI.create(baseUrl).getHost()) ? "json_object" : "omit";
            resolved.generationParams = "{}";
        }
        if (model != null) resolved.model = model;
        if (baseUrl != null) resolved.baseUrl = baseUrl;
        if (apiKey != null) resolved.apiKey = apiKey;
        if (thinking != null) resolved.thinking = thinking;
        if (responseFormat != null) resolved.responseFormat = responseFormat;
        if (generationParams != null) resolved.generationParams = generationParams;
        if (tokenLimitField != null) resolved.tokenLimitField = tokenLimitField;
        if (temperature != null) resolved.temperature = temperature;
        if (topP != null) resolved.topP = topP;
        if (generationSeed != null) resolved.generationSeed = generationSeed;
        if (timeoutSeconds != null) resolved.timeoutSeconds = timeoutSeconds;
        if (resolved.timeoutSeconds < 1) throw new IllegalArgumentException("--manager-timeout-seconds must be positive");
        resolved.generationSettings();
        return resolved;
    }

    boolean separateEndpoint(AgenticConfig traveler) {
        return baseUrl != null && !URI.create(trimSlash(baseUrl)).equals(URI.create(trimSlash(traveler.baseUrl)));
    }

    boolean hasOverrides() {
        return model != null || baseUrl != null || apiKey != null || thinking != null || responseFormat != null
                || generationParams != null || tokenLimitField != null || temperature != null || topP != null
                || generationSeed != null || timeoutSeconds != null;
    }

    private static String trimSlash(String url) {
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static String value(Map<String, String> environment, String suffix) {
        String value = environment.get("AGENTIC_MANAGER_" + suffix);
        return value == null || value.isBlank() ? null : value;
    }

    private static Double decimal(Map<String, String> environment, String suffix) {
        String value = value(environment, suffix);
        return value == null ? null : Double.valueOf(value);
    }

    private static Integer integer(Map<String, String> environment, String suffix) {
        String value = value(environment, suffix);
        return value == null ? null : Integer.valueOf(value);
    }
}
