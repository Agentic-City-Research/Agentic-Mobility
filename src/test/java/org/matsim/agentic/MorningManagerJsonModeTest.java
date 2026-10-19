package org.matsim.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.ConfigUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class MorningManagerJsonModeTest {
    @TempDir Path directory;

    @Test void jsonObjectMemorySurvivesAcrossDaysAndReachesAnAllDayPolicyDecision() throws Exception {
        try (var api = new ChatService(); var output = new OutputWriter(directory)) {
            var config = config(api, "json_object");
            var manager = manager(output, config, "basic");
            var initial = initial();
            for (int day = 18; day <= 19; day++) {
                observe(manager, day, initial);
                manager.commitMemory(day, manager.reflect(manager.memoryContext(day, 0, initial)));
            }
            String notes = (String) manager.context(20, 0, initial).get("managerMemory");
            assertTrue(notes.contains("Day 19"));
            assertTrue(notes.contains("Day 18"), "The next reflection must receive the previous private memory");

            var update = manager.update(20, 0, initial);
            assertTrue(update.accepted());
            assertEquals(.1, update.policy().amountAt(Id.createLinkId("road"), 29 * 3600));
            assertEquals(0, update.policy().amountAt(Id.createLinkId("road"), 108000));
            var priced = new MorningTollSystem.State(update.policy(), "v1", 1);
            observe(manager, 20, priced);
            manager.commitMemory(20, manager.reflect(manager.memoryContext(20, 0, priced)));
            assertTrue(((String) manager.context(21, 0, priced).get("managerMemory")).contains("Day 20"));

            assertEquals(4, api.requests.size());
            for (var request : api.requests) {
                String system = request.at("/messages/0/content").asText();
                assertTrue(system.contains("JSON output contract (morning-json-object-v1)"));
                assertTrue(system.contains("not the schema itself"));
                assertEquals("json_object", request.at("/response_format/type").asText());
                assertFalse(request.get("response_format").has("json_schema"));
            }
            assertTrue(api.requests.get(2).at("/messages/0/content").asText().contains("\"maxLength\":400"));
            assertEquals(notes, Json.readTree(api.requests.get(2).at("/messages/1/content").asText()).get("managerMemory").asText());

            output.flush();
            var memories = JsonlTestSupport.readLines(directory.resolve("manager-memory-attempts.jsonl")).stream().map(Json::readTree).toList();
            assertEquals(3, memories.size());
            var policy = Json.readTree(JsonlTestSupport.readLines(directory.resolve("manager-attempts.jsonl")).getFirst());
            assertLoggedRequest(memories.get(0), api.requests.get(0));
            assertLoggedRequest(memories.get(1), api.requests.get(1));
            assertLoggedRequest(policy, api.requests.get(2));
            assertLoggedRequest(memories.get(2), api.requests.get(3));
            assertEquals(8192, api.requests.get(2).get("max_tokens").asInt());
            assertEquals(2048, api.requests.get(0).get("max_tokens").asInt());
        }
    }

    @Test void hpcSchemaAndOmittedFormatKeepOriginalPromptsAndRequestOptions() throws Exception {
        for (String format : List.of("json_schema", "omit")) {
            for (String profile : List.of("basic", "controlled")) {
                try (var api = new ChatService(); var output = new OutputWriter(directory.resolve(format + "-" + profile))) {
                    var config = config(api, format);
                    var manager = manager(output, config, profile);
                    observe(manager, 19, initial());
                    manager.commitMemory(19, manager.reflect(manager.memoryContext(19, 0, initial())));
                    assertTrue(manager.update(20, 0, initial()).accepted());
                    assertEquals(2, api.requests.size());
                    var memory = api.requests.get(0);
                    var policy = api.requests.get(1);
                    assertEquals(MorningManagerMemory.SYSTEM, memory.at("/messages/0/content").asText());
                    assertEquals(profile.equals("basic") ? MorningManager.BASIC_SYSTEM : MorningManager.SYSTEM,
                            policy.at("/messages/0/content").asText());
                    for (var request : api.requests) {
                        assertEquals(config.model, request.get("model").asText());
                        assertFalse(request.has("thinking"));
                        if (format.equals("omit")) assertFalse(request.has("response_format"));
                        else {
                            assertEquals("json_schema", request.at("/response_format/type").asText());
                            assertTrue(request.at("/response_format/json_schema/strict").asBoolean());
                        }
                    }
                    if (format.equals("json_schema")) {
                        var schema = memory.at("/response_format/json_schema/schema");
                        assertEquals(Set.of("memory"), fieldNames(schema.get("properties")));
                        assertEquals(Set.of("rules", "decision"), fieldNames(policy.at("/response_format/json_schema/schema/properties")));
                    }
                }
            }
        }
    }

    private static void assertLoggedRequest(JsonNode log, JsonNode request) {
        String system = request.at("/messages/0/content").asText();
        String user = request.at("/messages/1/content").asText();
        assertTrue(log.get("valid").asBoolean());
        assertEquals(system, log.get("system").asText());
        assertEquals(user, log.get("user").asText());
        assertEquals(MorningPromptRenderer.hash(system + "\n" + user), log.get("requestHash").asText());
        assertEquals(system.length(), log.get("systemCharacters").asInt());
    }

    private static Set<String> fieldNames(JsonNode node) {
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static AgenticConfig config(ChatService api, String format) {
        var config = new AgenticConfig();
        config.baseUrl = "http://127.0.0.1:" + api.server.getAddress().getPort() + "/v1";
        config.model = "local-provider-fixture";
        config.apiKey = "offline-test-only";
        config.thinking = "omit";
        config.responseFormat = format;
        config.generationParams = "{}";
        config.maxOutputTokens = 8192;
        config.timeoutSeconds = 5;
        return config;
    }

    private static MorningManager manager(OutputWriter output, AgenticConfig config, String profile) {
        var network = MorningPricingTest.network();
        var scenario = ConfigUtils.createConfig();
        scenario.qsim().setEndTime(108000);
        var environment = new MorningManagerEnvironment(network, scenario, 1, 1, true, "hybrid", 0, Map.of());
        var controls = new MorningManagerControls();
        controls.profile = profile;
        var usage = new LlmUsageTracker(20);
        var manager = new MorningManager(network, 108000, environment, new DeepSeekClient(config, usage), output,
                new MorningRequestBudget(20), 1, config.publicModelSettings(), 30, 99, controls);
        var memoryConfig = config.withOutputLimit(2048);
        manager.setMemoryClient(new DeepSeekClient(memoryConfig, usage), memoryConfig.publicModelSettings());
        return manager;
    }

    private static void observe(MorningManager manager, int day, MorningTollSystem.State state) {
        manager.observe(day, List.of(Map.of("link_id", "road", "start", 0, "end", 3600, "entered", 1,
                "completed", 1, "aborted", 0, "unfinished", 0, "partial", 0,
                "total_travel_time", 20, "total_delay", 10)), state);
    }

    private static MorningTollSystem.State initial() {
        return new MorningTollSystem.State(TollPolicy.empty(-1), "no-toll", 0);
    }

    /** Applies DeepSeek's JSON-word check to real HTTP request bodies without external API calls. */
    private static final class ChatService implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();

        ChatService() throws Exception {
            server.createContext("/v1/chat/completions", exchange -> {
                var request = Json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.add(request);
                String system = request.at("/messages/0/content").asText();
                String user = request.at("/messages/1/content").asText();
                boolean missingJson = request.at("/response_format/type").asText().equals("json_object")
                        && !(system + user).toLowerCase(Locale.ROOT).contains("json");
                String body;
                if (missingJson) body = Json.write(Map.of("error", Map.of("message", "Prompt must contain the word 'json'")));
                else {
                    var context = Json.readTree(user);
                    Map<String, ?> data = system.startsWith("Reflect")
                            ? Map.of("memory", "Day " + context.get("day").asInt() + "; previous: " + context.path("managerMemory").asText())
                            : Map.of("rules", List.of(Map.of("link_id", "road", "start", 0, "end", 108000, "amount", .1)),
                                    "decision", MorningManagerTestResponses.decision("test"));
                    body = Json.write(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", Json.write(data)))),
                            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 10)));
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(missingJson ? 400 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        @Override public void close() { server.stop(0); }
    }
}
