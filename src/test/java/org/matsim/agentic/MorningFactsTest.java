package org.matsim.agentic;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class MorningFactsTest {
    @TempDir Path directory;

    @Test void completeFactsSurviveFiveExperienceWindowAndAreIsolated() {
        var a = new MorningMemory(5); var b = new MorningMemory(5);
        assertTrue(a.all().isEmpty()); assertTrue(a.recent().isEmpty());
        for (int day = 0; day < 500; day++) {
            String route = day % 2 == 0 ? "c0" : "c1"; a.chose(route);
            a.observe(new MorningMemory.Experience(day, route, day == 499 ? null : 100.0,
                    day == 499 ? null : 25300.0, day == 499 ? null : 0.0, day == 499, 25200.0,
                    day == 499 ? null : 100.0));
        }
        assertEquals(500, a.all().size()); assertEquals(0, a.all().getFirst().day());
        assertEquals(5, a.recent().size()); assertEquals(495, a.recent().getFirst().day());
        assertEquals(250, a.route("c0").chosenCount()); assertEquals(249, a.route("c1").completedCount());
        assertTrue(a.recent().getLast().stuck()); assertNull(a.recent().getLast().carSeconds());
        assertTrue(b.all().isEmpty()); assertEquals(0, b.route("c1").chosenCount());
        assertThrows(IllegalArgumentException.class, () -> a.observe(new MorningMemory.Experience(499, "c0", 100, null, null)));
        assertThrows(UnsupportedOperationException.class, () -> a.all().clear());
    }

    @Test void promptShowsFaithfulFactsNotInternalDataOrHabitInstructions() {
        var c = context(); var renderer = new MorningPromptRenderer();
        String text = renderer.render(c);
        assertEquals(text, renderer.render(c));
        assertTrue(text.contains("Planned departure: 07:00:00"));
        assertTrue(text.contains("Required arrival by: 09:00:00"));
        assertTrue(text.contains("predicted driving time 10.00 minutes"));
        assertTrue(text.contains("predicted arrival 07:12:00"));
        assertTrue(text.contains("estimated cost 1.5000"));
        assertTrue(text.contains("Based on traffic observed on simulation day 2"));
        assertFalse(text.contains("YOUR LONG-TERM ROUTE USE"));
        assertFalse(text.contains("chosen 3 times"));
        assertFalse(text.contains("completed driving 2 times"));
        assertTrue(text.contains("actual driving time 20.00 minutes"));
        assertTrue(text.contains("unknown (driving not completed)"));
        assertTrue(text.contains("Income (units as supplied): 1000"));
        assertFalse(text.contains("secret-person")); assertFalse(text.contains("secret-link"));
        assertFalse(text.contains("matsimScore")); assertFalse(text.contains("expectedCarSeconds"));
        assertFalse(text.contains("prefer familiar")); assertFalse(text.contains("exploration probability"));
        assertTrue(text.contains("No additional daily schedule is supplied"));
        var empty = new MorningRoutePolicy.Context(0, "p/0", "p", "home", "secondary", 25200, null,
                Map.of(), Map.of(), List.of(new MorningRoutePolicy.Option("c0", List.of(), 600, 25800, 1000, 0, "free_flow", 0, 0)), List.of());
        assertTrue(renderer.render(empty).contains("No previous personal experiences"));
        assertTrue(renderer.render(empty).contains("No arrival deadline"));
        assertFalse(renderer.render(empty).contains("Required arrival by:"));
    }

    @Test void sameDayAndFutureFactsNeverReachClient() throws Exception {
        var c = context(); AtomicInteger calls = new AtomicInteger();
        try (var writer = new OutputWriter(directory.resolve("no-leak"))) {
            var policy = new MorningLlmPolicy((r,s,u) -> { calls.incrementAndGet(); return null; }, writer, 3);
            var sameDay = new MorningRoutePolicy.Context(2, c.tripId(), c.personId(), c.originPurpose(), c.destinationPurpose(),
                    c.plannedDepartureSeconds(), c.latestArrivalSeconds(), c.personAttributes(), c.schedule(), c.candidates(), c.history());
            assertThrows(IllegalArgumentException.class, () -> policy.choose(sameDay));
            var historyLeak = new MorningRoutePolicy.Context(3, c.tripId(), c.personId(), c.originPurpose(), c.destinationPurpose(),
                    c.plannedDepartureSeconds(), c.latestArrivalSeconds(), c.personAttributes(), c.schedule(), c.candidates(),
                    List.of(new MorningMemory.Experience(3, "c0", 100, null, null)));
            assertThrows(IllegalArgumentException.class, () -> policy.choose(historyLeak));
            assertEquals(0, calls.get());
        }
    }

    @Test void repairLatencyUsageAndTransportFailuresAreAudited() throws Exception {
        AtomicInteger calls = new AtomicInteger(); Path path = directory.resolve("logs");
        try (var writer = new OutputWriter(path)) {
            var policy = new MorningLlmPolicy((r,s,u) -> new LlmResponse(calls.incrementAndGet() == 1
                    ? "{\"routeId\":\"c9\",\"reason\":\"bad\"}" : "{\"routeId\":\"c1\",\"reason\":\"Less costly\"}", 100, 12, 50, 50),
                    writer, 3, 10, 240, Map.of("model", "local-test"));
            assertEquals("c1", policy.choose(context()).routeId()); writer.flush();
            var attempts = readLines(path.resolve("attempts.jsonl")).stream().map(Json::readTree).toList();
            assertEquals(2, attempts.size()); assertFalse(attempts.getFirst().get("valid").asBoolean());
            assertEquals(1, attempts.getLast().get("repairCount").asInt());
            assertEquals(100, attempts.getLast().get("usage").get("promptTokens").asInt());
            assertTrue(attempts.getLast().get("elapsedMillis").asDouble() >= 0);
            assertFalse(attempts.getLast().get("user").asText().contains("secret-person"));
            var result = Json.readTree(readLines(path.resolve("llm-results.jsonl")).getFirst());
            assertEquals(1, result.get("repairCount").asInt());
            assertEquals(1, readLines(path.resolve("decision-contexts.jsonl")).size());
            assertThrows(LlmTransportException.class, () -> new MorningLlmPolicy((r,s,u) -> {
                throw new LlmTransportException("local test failure", null);
            }, writer, 3).choose(context()));
            writer.flush();
            var last = Json.readTree(readLines(path.resolve("attempts.jsonl")).getLast());
            assertEquals("call", last.get("failureKind").asText()); assertTrue(last.get("usage").isNull());
            assertEquals(1, last.get("attempt").asInt());
        }
    }

    @Test void genericClientOmitsVendorFieldsAndRecordsActualGenerationParameters() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", x -> {
            request.set(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, body.length); x.getResponseBody().write(body); x.close();
        });
        server.start();
        try {
            var config = new AgenticConfig(); config.apiKey = "local-only";
            config.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            config.thinking = "auto"; config.model = "test"; config.temperature = .2; config.topP = .8;
            config.generationSeed = 9; config.maxOutputTokens = 256; config.tokenLimitField = "max_completion_tokens";
            config.responseFormat = "omit"; config.generationParams = "{\"chat_template_kwargs\":{\"enable_thinking\":false}}";
            var response = new DeepSeekClient(config, new LlmUsageTracker(10)).complete("traveler", "sys", "usr");
            var node = Json.readTree(request.get());
            assertFalse(node.has("thinking")); assertFalse(node.has("max_tokens")); assertFalse(node.has("response_format"));
            assertEquals(256, node.get("max_completion_tokens").asInt()); assertEquals(.2, node.get("temperature").asDouble());
            assertEquals(9, node.get("seed").asInt()); assertTrue(response.usageReported());
            assertFalse(Json.write(config.publicModelSettings()).contains("local-only"));
            config.generationParams = "{\"messages\":[]}";
            assertThrows(IllegalArgumentException.class, config::generationSettings);
        } finally { server.stop(0); }
    }

    @Test void replayUsesSameFactsAcrossModelsAndDetectsDisplayOrderChangesWithoutMutatingMemory() throws Exception {
        Path input = directory.resolve("input.jsonl"), models = directory.resolve("models.json"), out = directory.resolve("replay");
        Files.writeString(input, Json.write(context()) + "\n");
        Files.writeString(models, "[{\"id\":\"a\",\"model\":\"a\",\"baseUrl\":\"http://localhost:1\"},"
                + "{\"id\":\"b\",\"model\":\"b\",\"baseUrl\":\"http://localhost:1\"}]");
        List<String> prompts = new ArrayList<>(); var replay = new MorningReplay();
        replay.clientFactory = (config, usage) -> (r,s,u) -> {
            prompts.add(s + u);
            int begin = u.indexOf("- Route ") + 8; String first = u.substring(begin, u.indexOf(":", begin));
            return new LlmResponse("{\"routeId\":\"" + (config.model.equals("a") ? first : "c1")
                    + "\",\"reason\":\"Test choice\"}", 0, 0, 0, 0);
        };
        assertEquals(0, new CommandLine(replay).execute("--input", input.toString(), "--models", models.toString(),
                "--output", out.toString(), "--orders", "original,reverse", "--limit", "1"));
        assertEquals(4, prompts.size()); assertEquals(prompts.get(0), prompts.get(2)); assertEquals(prompts.get(1), prompts.get(3));
        var comparison = Json.readTree(readLines(out.resolve("comparisons.jsonl.gz")).getFirst());
        assertTrue(comparison.get("modelsDisagreeAtSameOrderAndRepeat").asBoolean());
        assertTrue(comparison.get("choiceChangesAcrossDisplayOrders").asBoolean());
        assertEquals(3, context().history().size());
        var manifest = Json.readTree(Files.readString(out.resolve("manifest.json")));
        assertFalse(manifest.get("memoryUpdated").asBoolean()); assertFalse(manifest.get("trafficSimulated").asBoolean());
        assertEquals(readLines(out.resolve("models/a/original/repeat0/decision-contexts.jsonl.gz")),
                readLines(out.resolve("models/b/original/repeat0/decision-contexts.jsonl.gz")));
    }

    @Test void displayShuffleUsesSeparateDeterministicRandomnessAndPreservesRouteIds() {
        var c = context(); var ordered = MorningPromptRenderer.order(c, "reverse", 0);
        assertEquals("c1", ordered.candidates().getFirst().routeId()); assertEquals("c0", c.candidates().getFirst().routeId());
        assertEquals(MorningPromptRenderer.order(c, "shuffle", 17), MorningPromptRenderer.order(c, "shuffle", 17));
        assertEquals(c.history(), ordered.history());
    }

    @Test void packedStorageRoundTripsExactTimesNullsRouteIdsAndUnfinishedFlags() {
        var memory = new MorningMemory(1);
        var first = new MorningMemory.Experience(0, "c0", 123.4567890123, 32400.25, .25, false, 32276.7932109877, 123.4567890123);
        var second = new MorningMemory.Experience(1, "c3", null, null, null, true, 25200.0, null);
        memory.chose("c0"); memory.observe(first); memory.chose("c3"); memory.observe(second);
        assertEquals(List.of(first, second), memory.all()); assertEquals(List.of(second), memory.recent());
        assertThrows(IllegalArgumentException.class, () -> memory.observe(new MorningMemory.Experience(2, "c0", 100.0, Double.NaN, null, false)));
    }

    @Test void outputWithDuplicateKeysOrTrailingObjectsNeedsRepair() throws Exception {
        try (var writer = new OutputWriter(directory.resolve("strict-json"))) {
            for (String content : List.of("{\"routeId\":\"c0\",\"routeId\":\"c1\",\"reason\":\"ambiguous\"}",
                    "{\"routeId\":\"c0\",\"reason\":\"first\"}{\"routeId\":\"c1\",\"reason\":\"second\"}"))
                assertThrows(LlmException.class, () -> new MorningLlmPolicy((r,s,u) -> new LlmResponse(content, 0,0,0,0), writer, 1).choose(context()));
        }
    }

    static MorningRoutePolicy.Context context() {
        return new MorningRoutePolicy.Context(3, "secret-person/0", "secret-person", "home", "work", 25200, 32400.0,
                Map.of("income", 1000, "matsimScore", 999, "internalLink", "secret-link"), Map.of(),
                List.of(new MorningRoutePolicy.Option("c0", List.of("secret-link"), 600, 25920, 1000, 1.5, "previous_day_link_times", 2, 3, 2, 1),
                        new MorningRoutePolicy.Option("c1", List.of("secret-link"), 900, 26220, 1200, .5, "previous_day_link_times", 0, 0, 2, null)),
                List.of(new MorningMemory.Experience(0, "c0", 1200, 26400.0, 0.0),
                        new MorningMemory.Experience(1, "c0", 1100, 26300.0, 0.0),
                        new MorningMemory.Experience(2, "c0", null, null, null, true)));
    }
}
