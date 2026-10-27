package org.matsim.agentic;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Explicit, unit-checked assumptions; never inferred from qualitative profiles or LLM outcomes. */
final class MorningMnlParameters {
    static final String VERSION = "morning-joint-mnl-v1";
    private static final Map<String, String> UNITS = Map.of(
            "timeWeight", "utility/minute", "moneyWeight", "utility/scenario-money-unit",
            "lateWeight", "utility/minute", "departureShiftWeight", "utility/minute",
            "experienceWeight", "fraction", "experienceBinSeconds", "seconds",
            "unknownTollPriorPerLink", "scenario-money-unit/link", "unknownTollMode", "category");
    final double timeWeight, moneyWeight, lateWeight, departureShiftWeight, experienceWeight, unknownTollPriorPerLink;
    final int experienceBinSeconds;
    final String unknownTollMode;
    private final JsonNode document;
    private final Map<String, Object> source;

    private MorningMnlParameters(Path file, byte[] bytes, JsonNode doc) {
        exact(doc, Set.of("schemaVersion", "description", "calibrated", "parameters"), "document");
        if (!doc.path("schemaVersion").isIntegralNumber() || !doc.path("schemaVersion").canConvertToInt() || doc.path("schemaVersion").asInt() != 1
                || !doc.path("calibrated").isBoolean()) throw invalid("schemaVersion must be 1 and calibrated must be boolean");
        text(doc, "description");
        var p = doc.get("parameters");
        exact(p, UNITS.keySet(), "parameters");
        UNITS.forEach((name, unit) -> {
            var entry = p.get(name);
            exact(entry, Set.of("value", "unit", "source"), name);
            if (!unit.equals(text(entry, "unit"))) throw invalid("Wrong unit for " + name + ": expected " + unit);
            text(entry, "source");
        });
        timeWeight = number(p, "timeWeight"); moneyWeight = number(p, "moneyWeight");
        lateWeight = number(p, "lateWeight"); departureShiftWeight = number(p, "departureShiftWeight");
        experienceWeight = number(p, "experienceWeight");
        if (experienceWeight > 1) throw invalid("experienceWeight must be in [0,1]");
        var bin = p.get("experienceBinSeconds").get("value");
        if (!bin.isIntegralNumber() || !bin.canConvertToInt() || bin.asInt() < 1 || bin.asInt() > 86400)
            throw invalid("experienceBinSeconds must be an integer in [1,86400]");
        experienceBinSeconds = bin.asInt();
        unknownTollPriorPerLink = number(p, "unknownTollPriorPerLink");
        unknownTollMode = text(p.get("unknownTollMode"), "value");
        if (!Set.of("reject", "personal-with-prior").contains(unknownTollMode))
            throw invalid("unknownTollMode must be reject or personal-with-prior");
        document = doc.deepCopy();
        try {
            source = Map.of("path", file.toAbsolutePath().normalize().toString(), "bytes", bytes.length,
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static MorningMnlParameters load(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            return new MorningMnlParameters(file, bytes, mapper.readTree(bytes));
        } catch (IOException e) { throw new IllegalArgumentException("Cannot read --mnl-parameters " + file, e); }
    }

    Map<String, Object> report(long seed, int historyDays) {
        var report = new LinkedHashMap<String, Object>();
        report.put("version", VERSION); report.put("sourceFile", source); report.put("document", document);
        report.put("choiceSeed", seed); report.put("historyDays", historyDays);
        report.put("choiceSeedDerivation", "SHA-256(version, seed, simulation day, tripId), first 64 bits; local Java Random; canonical departure/route order");
        report.put("utility", "-timeWeight*expectedCarMinutes-moneyWeight*estimatedMoney-lateWeight*predictedLateMinutes-departureShiftWeight*absoluteOriginalDepartureShiftMinutes");
        report.put("scale", "Softmax scale fixed at 1; no additional logit-beta multiplier");
        report.put("experienceUpdate", "Recomputed before each choice from supplied recent own facts only: blend free-flow with mean completed car time for the same route and selected-departure bin. No matched fact: free-flow. Failed/stuck trips are retained in audit but excluded from duration/payment means.");
        report.put("tollBelief", "Current announced/disabled costs take precedence. Unknown costs remain null in shared context; personal-with-prior uses mean completed same-route/same-selected-departure-bin recent own total payments, otherwise explicit per-link prior times route link count. Historical payments are beliefs, not current prices.");
        report.put("profileMapping", "None; homogeneous coefficients, no numeric preference inferred from qualitative profiles");
        report.put("memorySummaryUsed", false); report.put("networkTrafficUsed", false);
        report.put("outputs", List.of("morning/baseline-parameters.json", "morning/baseline-contexts.jsonl.gz", "morning/baseline-choices.jsonl.gz", "morning/experiences.jsonl.gz"));
        return report;
    }

    Map<String, Object> source() { return source; }

    private static double number(JsonNode p, String name) {
        var value = p.get(name).get("value");
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0)
            throw invalid(name + " must be a finite nonnegative number");
        return value.asDouble();
    }
    private static String text(JsonNode node, String key) {
        var value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid("Missing nonblank " + key);
        return value.asText();
    }
    private static void exact(JsonNode node, Set<String> expected, String label) {
        if (node == null || !node.isObject()) throw invalid(label + " must be an object");
        var keys = new HashSet<String>(); node.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(expected)) throw invalid(label + " fields must be exactly " + new TreeSet<>(expected));
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Invalid --mnl-parameters: " + message); }
}
