package org.matsim.agentic;

import picocli.CommandLine.Option;

/** Explicit model controls, independent of traffic simulation parameters. */
final class MorningModelOptions {
    @Option(names = "--model") String model;
    @Option(names = "--base-url") String baseUrl;
    @Option(names = "--thinking", description = "auto | omit | enabled | disabled") String thinking;
    @Option(names = "--temperature") Double temperature;
    @Option(names = "--top-p") Double topP;
    @Option(names = "--generation-seed", description = "Provider generation seed, independent of MATSim seed") Integer generationSeed;
    @Option(names = "--max-output-tokens") Integer maxOutputTokens;
    @Option(names = "--reason-max-chars") Integer reasonMaxChars;
    @Option(names = "--token-limit-field", description = "max_tokens | max_completion_tokens") String tokenLimitField;
    @Option(names = "--response-format", description = "omit | json_object | json_schema (per-trip enum and length constraints); enable only if supported") String responseFormat;
    @Option(names = "--generation-params", description = "Provider-supported extra parameters as a JSON object") String generationParams;
    void apply(AgenticConfig c) {
        if (model != null) c.model = model;
        if (baseUrl != null) c.baseUrl = baseUrl;
        if (thinking != null) c.thinking = thinking;
        if (temperature != null) c.temperature = temperature;
        if (topP != null) c.topP = topP;
        if (generationSeed != null) c.generationSeed = generationSeed;
        if (maxOutputTokens != null) c.maxOutputTokens = maxOutputTokens;
        if (reasonMaxChars != null) c.reasonMaxChars = reasonMaxChars;
        if (tokenLimitField != null) c.tokenLimitField = tokenLimitField;
        if (responseFormat != null) c.responseFormat = responseFormat;
        if (generationParams != null) c.generationParams = generationParams;
        c.generationSettings();
    }
}
