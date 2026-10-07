package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Loaded once before simulation; the file cannot change an active fixed policy. */
record MorningFixedTollPolicy(TollPolicy policy, Map<String, Object> source) {
    static MorningFixedTollPolicy load(Path file, Network network, double horizon, int effectiveFromIteration) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        byte[] bytes = Files.readAllBytes(absolute);
        final com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = Json.MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid fixed toll policy JSON: " + absolute, e);
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.path("rules").isArray())
            throw new IllegalArgumentException("Fixed toll policy must contain only a rules array");
        var rules = new ArrayList<TollPolicy.TollRule>();
        for (var rule : root.get("rules")) {
            if (!rule.isObject() || rule.size() != 4 || !rule.path("link_id").isTextual() || rule.get("link_id").asText().isBlank()
                    || !rule.path("start").isNumber() || !rule.path("end").isNumber() || !rule.path("amount").isNumber())
                throw new IllegalArgumentException("Each fixed toll rule needs only link_id (text), start, end and amount (numbers)");
            rules.add(new TollPolicy.TollRule(Id.createLinkId(rule.get("link_id").asText()),
                    rule.get("start").doubleValue(), rule.get("end").doubleValue(), rule.get("amount").doubleValue()));
        }
        var policy = new TollPolicy(List.copyOf(rules), effectiveFromIteration);
        var violations = policy.validate(network, horizon);
        if (!violations.isEmpty()) throw new IllegalArgumentException("Invalid fixed toll rules: " + String.join("; ", violations));
        try {
            return new MorningFixedTollPolicy(policy, Map.of("path", absolute.toString(), "bytes", bytes.length,
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A reusable input file; activation is recorded separately in the manifest. */
    Map<String, Object> inputJson() { return Map.of("rules", policy.toJson().get("rules")); }
}
