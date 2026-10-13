package org.matsim.agentic;

import java.util.*;
import java.util.regex.Pattern;

/** Output constraints restrict syntax/actions, not a traveler's preferences or reasoning. */
final class MorningOutputContract {
    static final String VERSION = "morning-output-v3-strict-json-audit";
    private static final Pattern ROUTE_REFERENCE = Pattern.compile("(?i)(?<![A-Za-z0-9_-])c\\d+(?![A-Za-z0-9_-])");

    static Map<String, Object> choice(List<String> routeIds, int reasonLimit) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("routeId", Map.of("type", "string", "enum", List.copyOf(routeIds)));
        properties.put("reason", text(reasonLimit));
        return object(Collections.unmodifiableMap(properties), List.of("routeId", "reason"));
    }

    static Map<String, Object> memory(int maxChars) {
        return object(Map.of("memory", text(maxChars)), List.of("memory"));
    }

    static Map<String, Object> choice(List<String> routeIds, int reasonLimit, List<Double> departures) {
        if (departures.isEmpty()) return choice(routeIds, reasonLimit);
        var properties = new LinkedHashMap<String, Object>();
        properties.put("routeId", Map.of("type", "string", "enum", List.copyOf(routeIds)));
        properties.put("departureSeconds", Map.of("type", "number", "enum", List.copyOf(departures)));
        properties.put("reason", text(reasonLimit));
        return object(properties, List.of("routeId", "departureSeconds", "reason"));
    }

    private static Map<String, Object> text(int limit) {
        // No pattern: some grammar backends ignore length bounds when pattern is also specified.
        return Map.of("type", "string", "minLength", 1, "maxLength", limit);
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "object"); result.put("properties", properties);
        result.put("required", required); result.put("additionalProperties", false);
        return Collections.unmodifiableMap(result);
    }

    static Map<String, Object> responseFormat(Map<String, Object> schema) {
        return Map.of("type", "json_schema", "json_schema",
                Map.of("name", "morning_output", "strict", true, "schema", schema));
    }

    /** Audit the actual per-call generation options, not a generic route schema shared with reflection. */
    static Map<String, Object> requestSettings(Map<String, Object> settings, Map<String, Object> schema) {
        var result = new LinkedHashMap<>(settings);
        if ("json_schema".equals(settings.get("responseFormat"))) {
            var generation = new LinkedHashMap<String, Object>();
            if (settings.get("generation") instanceof Map<?, ?> original)
                original.forEach((k, v) -> generation.put(String.valueOf(k), v));
            generation.put("response_format", responseFormat(schema));
            result.put("generation", generation);
        }
        return result;
    }

    static void responseMetadata(Map<String, Object> log, LlmResponse response) {
        log.put("promptCacheUsageReported",response.promptCacheUsageReported());
        log.put("finishReason", response.finishReason());
        log.put("reasoningCharacters", response.reasoningCharacters());
        String content=response.content();
        int characters=content==null?0:content.codePointCount(0,content.length());
        long whitespace=content==null?0:content.codePoints().filter(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)).count();
        log.put("outputCharacters",characters); log.put("outputWhitespaceCharacters",whitespace);
        log.put("outputWhitespaceRatio",characters==0?null:(double)whitespace/characters);
        log.put("blankOutput",content==null || content.isBlank());
    }

    static Map<String,Object> usage(LlmResponse response) {
        if (!response.usageReported()) return null;
        var result = new LinkedHashMap<String,Object>();
        result.put("promptTokens",response.promptTokens()); result.put("completionTokens",response.completionTokens());
        result.put("promptCacheHitTokens",response.promptCacheUsageReported()?response.promptCacheHitTokens():null);
        result.put("promptCacheMissTokens",response.promptCacheUsageReported()?response.promptCacheMissTokens():null);
        return result;
    }

    static void requireNotTruncated(LlmResponse response) {
        if ("length".equals(response.finishReason()) || "max_tokens".equals(response.finishReason()))
            throw new IllegalArgumentException("Provider stopped at the output token limit; compress the text and finish the JSON object");
        if(response.content()==null || response.content().isBlank())
            throw new IllegalArgumentException("Empty/whitespace-only output; return one complete compact JSON object, without formatting padding");
    }

    /** Narrow guard for the reserved cN route identifiers; not a general semantic/factual validator. */
    static List<String> unknownRouteReferences(String memory, Collection<String> knownIds) {
        var known = new HashSet<String>();
        knownIds.forEach(id -> known.add(id.toLowerCase(Locale.ROOT)));
        var unknown = new LinkedHashSet<String>();
        var match = ROUTE_REFERENCE.matcher(memory);
        while (match.find()) if (!known.contains(match.group().toLowerCase(Locale.ROOT))) unknown.add(match.group());
        return List.copyOf(unknown);
    }
}
