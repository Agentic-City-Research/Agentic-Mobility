package org.matsim.agentic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new LlmException("serialization failed", e);
        }
    }

    public static JsonNode readTree(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (JsonProcessingException e) {
            throw new LlmException("unparseable JSON: " + truncate(s), e);
        }
    }

    /**
     * Extract the first JSON object/array from an LLM response, tolerating
     * markdown code fences and surrounding prose. Falls back to progressively
     * trimming trailing characters so a trailing markdown fence or sentence
     * does not break parsing.
     */
    public static JsonNode readJson(String content) {
        String s = content == null ? "" : content;
        s = s.replaceAll("(?m)^\\s*```[a-zA-Z]*\\s*$", "");
        s = s.trim();
        int brace = s.indexOf('{');
        int bracket = s.indexOf('[');
        int start = brace < 0 ? bracket : (bracket < 0 ? brace : Math.min(brace, bracket));
        if (start < 0) {
            throw new LlmException("no JSON object found in: " + truncate(content));
        }
        s = s.substring(start);
        try {
            return MAPPER.readTree(s);
        } catch (JsonProcessingException first) {
            for (int end = s.length(); end > 0; end--) {
                try {
                    return MAPPER.readTree(s.substring(0, end));
                } catch (JsonProcessingException ignored) {
                    // keep trimming
                }
            }
            throw new LlmException("unparseable JSON: " + truncate(content), first);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
