package org.matsim.agentic;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class MorningOutputContractTest {
    @TempDir Path directory;

    @Test void soleRouteIsStillChosenByModelAndInvalidMemoryCannotExpandActions() throws Exception {
        var calls = new AtomicInteger(); var c = singleRouteContext();
        try (var writer = new OutputWriter(directory.resolve("single"))) {
            LlmClient client = new LlmClient() {
                @Override public LlmResponse complete(String role, String system, String user) { throw new AssertionError("Missing per-trip schema"); }
                @Override public LlmResponse complete(String role, String system, String user, Map<String, Object> schema) {
                    assertEquals(List.of("c0"), routeEnum(schema));
                    assertTrue(user.contains("There is exactly one available route: c0"));
                    if (calls.incrementAndGet() == 1)
                        return response("{\"routeId\":\"c1\",\"reason\":\"My old memory recommends c1\"}");
                    assertTrue(user.contains("Allowed routeId values are exactly [\"c0\"]"));
                    return response("{\"routeId\":\"c0\",\"reason\":\"Only the listed route is available\"}");
                }
            };
            var choice = new MorningLlmPolicy(client, writer, 3, 100, 240, Map.of()).choose(c);
            assertEquals("c0", choice.routeId()); assertEquals(2, calls.get()); writer.flush();
            var logs = readLines(directory.resolve("single/attempts.jsonl")).stream().map(Json::readTree).toList();
            assertEquals("c1", logs.getFirst().get("memoryUnknownRouteReferences").get(0).asText());
            assertFalse(logs.getFirst().get("valid").asBoolean());
            var result = Json.readTree(readLines(directory.resolve("single/llm-results.jsonl")).getFirst());
            assertEquals(1, result.get("candidateCount").asInt()); assertFalse(result.get("routeChoiceEligible").asBoolean());
            assertEquals(1, result.get("repairCount").asInt()); assertEquals("c0", result.get("routeId").asText());
        }
    }

    @Test void repeatedUnavailableRouteNeverBecomesImplicitSoleRouteFallback() throws Exception {
        var calls = new AtomicInteger();
        try (var writer = new OutputWriter(directory.resolve("no-fallback"))) {
            var policy = new MorningLlmPolicy((r,s,u) -> { calls.incrementAndGet();
                return response("{\"routeId\":\"c1\",\"reason\":\"Invented alternative\"}");
            }, writer, 3);
            var failure = assertThrows(LlmException.class, () -> policy.choose(singleRouteContext()));
            assertTrue(failure.getMessage().contains("[\"c0\"]")); assertEquals(3, calls.get());
            writer.flush(); assertFalse(Files.exists(directory.resolve("no-fallback/llm-results.jsonl")));
            assertEquals(3, readLines(directory.resolve("no-fallback/attempts.jsonl")).size());
        }
    }

    @Test void providerLengthStopRequiresRepairEvenForSyntacticallyCompleteObject() throws Exception {
        var calls = new AtomicInteger();
        try (var writer = new OutputWriter(directory.resolve("length"))) {
            var policy = new MorningLlmPolicy((r,s,u) -> {
                if (calls.incrementAndGet() == 1) return new LlmResponse("{\"routeId\":\"c0\",\"reason\":\"ok\"}",
                        100, 512, 0, 100, true, "length", 200);
                assertTrue(u.contains("compress the text and finish the JSON object"));
                return new LlmResponse("{\"routeId\":\"c0\",\"reason\":\"ok\"}", 100, 20, 0, 100, true, "stop", 0);
            }, writer, 3);
            assertEquals("c0", policy.choose(MorningFactsTest.context()).routeId()); writer.flush();
            var logs = readLines(directory.resolve("length/attempts.jsonl")).stream().map(Json::readTree).toList();
            assertEquals("length", logs.getFirst().get("finishReason").asText());
            assertEquals(200, logs.getFirst().get("reasoningCharacters").asInt());
            assertFalse(logs.getFirst().get("valid").asBoolean()); assertEquals(2, calls.get());
        }
    }

    @Test void unicodeCharacterLimitAndMalformedJsonAreValidatedWithoutSalvagingRouteId() throws Exception {
        var calls = new AtomicInteger();
        try (var writer = new OutputWriter(directory.resolve("short"))) {
            var policy = new MorningLlmPolicy((r,s,u) -> response(switch (calls.incrementAndGet()) {
                case 1 -> "{\"routeId\":\"c0\",\"reason\":\"unfinished";
                case 2 -> "{\"routeId\":\"c0\",\"reason\":\"😀😀😀\"}";
                default -> "{\"routeId\":\"c0\",\"reason\":\"😀😀\"}";
            }), writer, 3, 0, 2, Map.of());
            assertEquals("😀😀", policy.choose(MorningFactsTest.context()).reason()); assertEquals(3, calls.get());
        }
    }

    @Test void reflectionRetainsDistortedMemoryAndOnlyDiagnosesInventedRoutesWithoutChangingFacts() throws Exception {
        var memory = new MorningMemory(5); memory.chose("c0"); memory.observe(new MorningMemory.Experience(0, "c0", 1200, 26400.0, 0.0));
        var c = new MorningMemoryUpdater.Context(0, "p/0", "p", "work", 32400.0, Map.of(), null,
                "", null, memory.recent(), List.of("c0"));
        var calls = new AtomicInteger();
        try (var writer = new OutputWriter(directory.resolve("memory"))) {
            var updater = new MorningMemoryUpdater((r,s,u) -> {
                assertTrue(u.contains("AVAILABLE ROUTE IDENTIFIERS FOR THIS TRIP: [\"c0\"]"));
                calls.incrementAndGet();
                return response("{\"memory\":\"I drove c1 in five minutes today and should switch to c2.\"}");
            }, writer, 3, 500, Map.of(), new MorningRequestBudget(10));
            var update = updater.update(c); assertEquals(0, update.repairCount()); assertEquals(1, calls.get());
            assertEquals("I drove c1 in five minutes today and should switch to c2.", update.memory());
            assertEquals("", memory.summary());
            assertEquals(1, memory.size()); assertEquals(1200.0, memory.all().getFirst().carSeconds().doubleValue());
            memory.updateSummary(0, update.memory());
            assertEquals(update.memory(), memory.summary(), "Subjective distortions must survive commit unchanged");
            assertEquals("c0", memory.all().getFirst().routeId(), "Actual experience is kept separately");
            writer.flush(); var first = Json.readTree(readLines(directory.resolve("memory/memory-attempts.jsonl")).getFirst());
            assertEquals(2, first.get("unknownRouteReferences").size()); assertTrue(first.get("valid").asBoolean());
            assertTrue(first.get("error").isNull());
        }
    }

    @Test void routeReferenceGuardAllowsUntriedAvailableRoutesAndHistoricalFactsOnly() {
        assertEquals(List.of("c2"), MorningOutputContract.unknownRouteReferences("C0 is familiar; c1 is untried; c2 is imaginary", List.of("c0", "c1")));
        assertTrue(MorningOutputContract.unknownRouteReferences("I might try c1, which I have not used", List.of("c0", "c1")).isEmpty());
        assertTrue(MorningOutputContract.unknownRouteReferences("I retain an important early event", List.of("c0")).isEmpty());
    }

    @Test void httpRequestsHavePerTripAndSeparateReflectionSchemasAndProviderMetadata() throws Exception {
        List<String> requests = new ArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", x -> {
            requests.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\",\"reasoning\":\"😀abc\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes); x.close();
        });
        server.start();
        try {
            var config = localConfig(server); var client = new DeepSeekClient(config, new LlmUsageTracker(10));
            var firstSchema = MorningOutputContract.choice(List.of("c0"), 240);
            var secondSchema = MorningOutputContract.choice(List.of("c0", "c1", "c2"), 240);
            var response = client.complete("traveler", "sys", "usr", firstSchema);
            client.complete("traveler", "sys", "usr", secondSchema);
            var reflectionConfig = config.withOutputLimit(1024);
            var memorySchema = MorningOutputContract.memory(500);
            new DeepSeekClient(reflectionConfig, new LlmUsageTracker(10)).complete("traveler_memory", "sys", "usr", memorySchema);
            assertEquals("stop", response.finishReason()); assertEquals(4, response.reasoningCharacters());
            var one = Json.readTree(requests.get(0)); var two = Json.readTree(requests.get(1)); var memory = Json.readTree(requests.get(2));
            assertEquals(1, one.at("/response_format/json_schema/schema/properties/routeId/enum").size());
            assertEquals(3, two.at("/response_format/json_schema/schema/properties/routeId/enum").size());
            assertTrue(one.at("/response_format/json_schema/strict").asBoolean());
            assertEquals(240, one.at("/response_format/json_schema/schema/properties/reason/maxLength").asInt());
            assertFalse(one.at("/response_format/json_schema/schema/properties/reason").has("pattern"));
            assertFalse(memory.at("/response_format/json_schema/schema/properties").has("routeId"));
            assertEquals(500, memory.at("/response_format/json_schema/schema/properties/memory/maxLength").asInt());
            assertEquals(512, one.get("max_tokens").asInt()); assertEquals(1024, memory.get("max_tokens").asInt());
            var audited = Json.MAPPER.valueToTree(MorningOutputContract.requestSettings(config.publicModelSettings(), firstSchema));
            assertEquals(one.get("response_format"), audited.at("/generation/response_format"));
            assertFalse(Json.write(config.publicModelSettings()).contains("local-secret"));
            assertThrows(IllegalArgumentException.class, () -> client.complete("traveler", "sys", "usr"));
            assertEquals(3, requests.size());
        } finally { server.stop(0); }
    }

    @Test void unsupportedSchemaIsNotSilentlyDowngradedAndConflictingGlobalSchemaIsRejected() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", x -> {
            requests.incrementAndGet(); x.getRequestBody().readAllBytes();
            byte[] bytes = "schema unsupported".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(400, bytes.length); x.getResponseBody().write(bytes); x.close();
        });
        server.start();
        try {
            var config = localConfig(server);
            assertThrows(LlmException.class, () -> new DeepSeekClient(config, new LlmUsageTracker(10))
                    .complete("traveler", "sys", "usr", MorningOutputContract.choice(List.of("c0"), 240)));
            assertEquals(1, requests.get());
            for (String key : List.of("response_format", "structured_outputs")) {
                config.generationParams = Json.write(Map.of(key, Map.of()));
                assertThrows(IllegalArgumentException.class, config::generationSettings);
            }
        } finally { server.stop(0); }
    }

    @Test void replayForwardsSchemaRatherThanDroppingItInBudgetWrapper() throws Exception {
        Path input = directory.resolve("input.jsonl"), out = directory.resolve("replay");
        Files.writeString(input, Json.write(singleRouteContext()) + "\n");
        var calls = new AtomicInteger(); var replay = new MorningReplay();
        replay.clientFactory = (config, usage) -> new LlmClient() {
            @Override public LlmResponse complete(String r, String s, String u) { throw new AssertionError("Lost schema"); }
            @Override public LlmResponse complete(String r, String s, String u, Map<String, Object> schema) {
                assertEquals(List.of("c0"), routeEnum(schema)); calls.incrementAndGet();
                return response("{\"routeId\":\"c0\",\"reason\":\"Only offered route\"}");
            }
        };
        assertEquals(0, new CommandLine(replay).execute("--input", input.toString(), "--output", out.toString(),
                "--response-format", "json_schema", "--orders", "original", "--limit", "1"));
        assertEquals(1, calls.get());
    }

    private static LlmResponse response(String content) { return new LlmResponse(content, 10, 5, 0, 10); }
    private static List<String> routeEnum(Map<String, Object> schema) {
        var result = new ArrayList<String>();
        Json.MAPPER.valueToTree(schema).at("/properties/routeId/enum").forEach(x -> result.add(x.asText()));
        return result;
    }
    private static AgenticConfig localConfig(HttpServer server) {
        var config = new AgenticConfig(); config.apiKey = "local-secret"; config.model = "fixture";
        config.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        config.thinking = "omit"; config.responseFormat = "json_schema"; config.maxOutputTokens = 512; config.generationParams = "{}";
        return config;
    }
    private static MorningRoutePolicy.Context singleRouteContext() {
        var c = MorningFactsTest.context();
        return new MorningRoutePolicy.Context(c.day(), c.tripId(), c.personId(), c.originPurpose(), c.destinationPurpose(),
                c.plannedDepartureSeconds(), c.latestArrivalSeconds(), c.personAttributes(), c.schedule(), List.of(c.candidates().getFirst()),
                c.history(), null, "I must diversify to c1", 2);
    }
}
