package org.matsim.agentic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Legacy synthetic traits, fixed by person rather than cohort size or traversal order. */
final class MorningProfiles {
    private MorningProfiles() {}

    static Profile sample(String personId, long seed) {
        long personSeed = Long.parseUnsignedLong(MorningPromptRenderer.hash("morning-profile-v1/" + personId).substring(0, 16), 16);
        return Profile.sample(new Random(seed ^ personSeed), null);
    }

    /** Accept legacy roster.json arrays and new profiles/cohort JSONL, including gzip. */
    static Map<String, Profile> read(Path file) throws IOException {
        Map<String, Profile> result = new LinkedHashMap<>();
        try (var reader = JsonlIO.newReader(file)) {
            String first;
            do { first = reader.readLine(); } while (first != null && first.isBlank());
            if (first == null) throw new IllegalArgumentException("Empty profile file");
            if (first.stripLeading().startsWith("[")) {
                StringBuilder document = new StringBuilder(first); String line;
                while ((line = reader.readLine()) != null) document.append('\n').append(line);
                var array = Json.readTree(document.toString());
                if (!array.isArray()) throw new IllegalArgumentException("Profile roster must be an array");
                for (var row : array) add(result, row);
            } else {
                add(result, Json.readTree(first)); String line;
                while ((line = reader.readLine()) != null) if (!line.isBlank()) add(result, Json.readTree(line));
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Profile file contains no profiles");
        return Map.copyOf(result);
    }

    private static void add(Map<String, Profile> result, com.fasterxml.jackson.databind.JsonNode row) throws IOException {
        var id = row.has("personId") ? row.get("personId") : row.get("person_id");
        if (id == null || !id.isTextual() || id.asText().isBlank() || !row.hasNonNull("profile"))
            throw new IllegalArgumentException("Each profile row needs personId (or person_id) and profile");
        var profile = Json.MAPPER.treeToValue(row.get("profile"), Profile.class);
        if (profile.income() == null || profile.decisionStyle() == null || profile.workTimeliness() == null)
            throw new IllegalArgumentException("Incomplete profile for " + id.asText());
        var prior = result.putIfAbsent(id.asText(), profile);
        if (prior != null && !prior.equals(profile)) throw new IllegalArgumentException("Conflicting profiles for " + id.asText());
    }
}
