package org.matsim.agentic;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

/** Explicit opt-in, full traffic background; the LLM endpoint is a loopback test fixture, not a real model. */
class MorningFullTrafficValidationTest {
    @TempDir Path directory;

    @Test
    @EnabledIfSystemProperty(named = "morning.fullTrafficValidation", matches = "true")
    void sevenDaysThreeControlledPersonsAndFullPopulation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger decisionCalls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = Json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String prompt = body.get("messages").get(1).get("content").asText();
            assertFalse(prompt.contains("plannedDepartureSeconds")); assertFalse(prompt.contains("matsimScore"));
            calls.incrementAndGet();
            boolean reflection = body.get("messages").get(0).get("content").asText().contains("reflecting on a trip");
            String content;
            if (reflection) content = Json.write(Map.of("memory", "Loopback fixture notes; not evidence of model habits."));
            else {
                var match = Pattern.compile("- Route ([A-Za-z0-9_-]+): predicted").matcher(prompt);
                List<String> ids = new ArrayList<>(); while (match.find()) ids.add(match.group(1));
                String selected = ids.get(decisionCalls.getAndIncrement() % ids.size());
                content = Json.write(Map.of("routeId", selected, "reason", "Loopback validation fixture; not model evidence"));
            }
            byte[] response = Json.write(Map.of("choices", List.of(Map.of("message", Map.of("content",
                    content))),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        Path output = System.getProperty("morning.fullTrafficOutput") == null ? directory.resolve("full-traffic")
                : Path.of(System.getProperty("morning.fullTrafficOutput"));
        try {
            var settings = new MorningExperiment(); settings.llmConfig.apiKey = "loopback-only-test-key";
            var args = new ArrayList<>(List.of("--mode", "hybrid", "--policy", "llm", "--llm-agents", "3",
                    "--demand", "100", "--days", "7", "--seed", "4711", "--concurrency", "1",
                    "--model", "offline-loopback-validation", "--base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "--thinking", "omit", "--output", output.toString(), "--trip-output-interval", "3"));
            if (System.getProperty("morning.validationCandidates") != null)
                args.addAll(List.of("--candidate-file", System.getProperty("morning.validationCandidates")));
            assertEquals(0, new CommandLine(settings).execute(args.toArray(String[]::new)));
            var manifest = Json.readTree(Files.readString(output.resolve("morning/manifest.json")));
            assertEquals(84110, manifest.get("simulatedPopulation").asInt());
            assertEquals(58450, manifest.get("cohortTrips").asInt());
            assertFalse(manifest.get("diagnosticSubsample").asBoolean());
            assertEquals(3, manifest.get("controlledPersons").asInt());
            assertEquals(42, calls.get()); assertEquals(21, decisionCalls.get());
            assertEquals(21, readLines(output.resolve("morning/memory-updates.jsonl.gz")).size());
            var executed = readLines(output.resolve("morning/executed-route-checks.jsonl.gz")).stream().map(Json::readTree).toList();
            assertEquals(21, executed.size());
            assertTrue(executed.stream().allMatch(r -> r.get("followsSelectedRoutePrefix").asBoolean()));
            assertTrue(executed.stream().filter(r -> r.get("completedCar").asBoolean()).allMatch(r -> r.get("completedRouteMatches").asBoolean()));
            var contexts = readLines(output.resolve("morning/decision-contexts.jsonl.gz")).stream().map(Json::readTree).toList();
            assertEquals(21, contexts.size());
            for (var c : contexts) {
                int day = c.get("day").asInt(); assertEquals(Math.min(day, 5), c.get("history").size());
                for (var e : c.get("history")) assertTrue(e.get("day").asInt() < day);
                assertEquals(day, java.util.stream.StreamSupport.stream(c.get("candidates").spliterator(), false)
                        .mapToInt(o -> o.get("chosenCount").asInt()).sum());
            }
            var counts = new HashMap<String, Integer>();
            try (var archive = JsonlIO.newReader(output.resolve("morning/experiences.jsonl.gz"))) {
                String line; long total = 0;
                while ((line = archive.readLine()) != null) {
                    var row = Json.readTree(line); total++;
                    if (row.get("controlled").asBoolean()) counts.merge(row.get("tripId").asText(), 1, Integer::sum);
                }
                assertEquals(7L * 58450, total); assertEquals(3, counts.size());
                assertTrue(counts.values().stream().allMatch(n -> n == 7));
            }
            var use = readLines(output.resolve("morning/route-use.jsonl.gz")).stream().map(Json::readTree).toList();
            assertEquals(3, use.size()); assertTrue(use.stream().allMatch(r -> r.get("experiences").asInt() == 7));
            var decisions = readLines(output.resolve("morning/decisions.jsonl.gz")).stream().map(Json::readTree).toList();
            Map<String, String> applied = new HashMap<>();
            for (var d : decisions) applied.put(d.get("day").asInt() + "/" + d.get("tripId").asText(), d.get("appliedRouteFingerprint").asText());
            try (var trips = JsonlIO.newReader(output.resolve("morning/trips.jsonl.gz"))) {
                String line; while ((line = trips.readLine()) != null) {
                    var r = Json.readTree(line);
                    if (r.get("controlled").asBoolean()) assertEquals(applied.get(r.get("day").asInt() + "/" + r.get("tripId").asText()),
                            r.get("routeFingerprint").asText());
                }
            }
            var metrics = readLines(output.resolve("morning/metrics.jsonl.gz")).stream().map(Json::readTree).toList();
            assertEquals(7, metrics.size());
            for (var r : metrics) assertEquals((double)(r.get("completedWorkTripsWithDeadline").asLong() - r.get("lateWorkTrips").asLong())
                    / r.get("workTripsWithDeadline").asLong(), r.get("onTimeWorkFractionAll").asDouble(), 1e-12);
            assertTrue(Json.readTree(Files.readString(output.resolve("morning/status.json"))).get("complete").asBoolean());
        } finally { server.stop(0); }
    }
}
