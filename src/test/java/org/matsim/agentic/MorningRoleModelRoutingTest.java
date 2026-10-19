package org.matsim.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP requests and QSim; no external model or paid API is used. */
class MorningRoleModelRoutingTest {
    @TempDir Path directory;

    @Test void twentyFiveDayJointRunRoutesBothRolesAndTheirMemoriesToSeparateServices() throws Exception {
        try (var qwen = new ChatFixture(); var manager = new ChatFixture()) {
            var settings = settings(Map.of("AGENTIC_MANAGER_API_KEY", "manager-test-key"));
            settings.llmConfig.apiKey = "qwen-test-key";
            var out = directory.resolve("separate");
            int exit = new CommandLine(settings).execute("--config", scenario().toString(), "--output", out.toString(),
                    "--policy", "llm", "--llm-agents", "all", "--manager", "--days", "25", "--concurrency", "1",
                    "--model", "qwen-fixture", "--base-url", qwen.url(), "--thinking", "omit",
                    "--response-format", "json_schema", "--generation-params", "{\"chat_template_kwargs\":{\"enable_thinking\":false}}",
                    "--manager-model", "deepseek-fixture", "--manager-base-url", manager.url(),
                    "--manager-thinking", "disabled", "--manager-response-format", "json_object",
                    "--manager-timeout-seconds", "5", "--max-requests", "100");
            assertEquals(0, exit);
            assertEquals(50, qwen.requests.size(), "25 decisions and 25 private-memory reflections");
            assertEquals(26, manager.requests.size(), "25 daily memories and the Day-20 policy");
            for (var request : qwen.requests) {
                assertEquals("qwen-fixture", request.get("model").asText());
                assertEquals("json_schema", request.at("/response_format/type").asText());
                assertEquals(1024, request.get("max_tokens").asInt());
                assertFalse(request.at("/chat_template_kwargs/enable_thinking").asBoolean(true));
                assertFalse(request.has("thinking"));
            }
            for (var request : manager.requests) {
                assertEquals("deepseek-fixture", request.get("model").asText());
                assertEquals("json_object", request.at("/response_format/type").asText());
                assertEquals("disabled", request.at("/thinking/type").asText());
                assertFalse(request.has("chat_template_kwargs"));
                assertTrue(request.at("/messages/0/content").asText().contains("morning-json-object-v1"));
                assertEquals(managerMemory(request) ? 2048 : 8192, request.get("max_tokens").asInt());
            }
            assertTrue(qwen.authorization.stream().allMatch("Bearer qwen-test-key"::equals));
            assertTrue(manager.authorization.stream().allMatch("Bearer manager-test-key"::equals));
            var manifest = Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
            assertEquals("qwen-fixture", manifest.at("/llmSettings/model").asText());
            assertEquals("qwen-fixture", manifest.at("/memoryModelSettings/model").asText());
            assertEquals("deepseek-fixture", manifest.at("/managerSettings/model").asText());
            assertEquals("deepseek-fixture", manifest.at("/managerMemorySettings/model").asText());
            assertTrue(manifest.at("/managerServiceRouting/separateEndpoint").asBoolean());
            assertEquals("full_day", manifest.at("/pricing/chargingMode").asText());
            assertEquals(108000, manifest.at("/pricing/chargingWindow/end").asDouble());
            String archive = manifest.toString();
            assertFalse(archive.contains("qwen-test-key")); assertFalse(archive.contains("manager-test-key"));
            var status = Json.readTree(Files.readString(out.resolve("morning/status.json")));
            assertTrue(status.get("complete").asBoolean()); assertEquals(76, status.get("apiCalls").asInt());
            var policies = JsonlTestSupport.readLines(out.resolve("morning/manager-decisions.jsonl.gz"));
            assertEquals(1, policies.size()); assertTrue(Json.readTree(policies.getFirst()).get("pricingChanged").asBoolean());
            var memoryRows = JsonlTestSupport.readLines(out.resolve("morning/manager-memory-updates.jsonl.gz"));
            assertEquals(25, memoryRows.size());
            assertTrue(memoryRows.stream().map(Json::readTree).allMatch(r -> r.get("memoryStatus").asText().equals("updated")));
            assertTrue(manager.requests.stream().anyMatch(r -> !managerMemory(r) &&
                    Json.readTree(r.at("/messages/1/content").asText()).path("managerMemory").asText().contains("Day 19")));
        }
    }

    @Test void noManagerOverridesKeepQwenSchemaParametersForAllFourCallTypes() throws Exception {
        try (var api = new ChatFixture()) {
            var settings = settings(Map.of()); settings.llmConfig.apiKey = "legacy-key";
            var out = directory.resolve("legacy");
            assertEquals(0, new CommandLine(settings).execute("--config", scenario().toString(), "--output", out.toString(),
                    "--policy", "llm", "--llm-agents", "all", "--manager", "--days", "1", "--concurrency", "1",
                    "--model", "legacy-qwen", "--base-url", api.url(), "--thinking", "omit",
                    "--response-format", "json_schema", "--generation-params", "{\"chat_template_kwargs\":{\"enable_thinking\":false}}"));
            assertEquals(3, api.requests.size());
            assertTrue(api.authorization.stream().allMatch("Bearer legacy-key"::equals));
            for (var request : api.requests) {
                assertEquals("legacy-qwen", request.get("model").asText());
                assertEquals("json_schema", request.at("/response_format/type").asText());
                assertTrue(request.has("chat_template_kwargs")); assertFalse(request.has("thinking"));
            }
            var manifest = Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
            assertEquals("traveler-defaults", manifest.at("/managerServiceRouting/settingsSource").asText());
            assertFalse(manifest.at("/managerServiceRouting/separateEndpoint").asBoolean());
        }
    }

    @Test void managerOnlyCanUseItsOwnCredentialWithoutAnyTravelerCredential() throws Exception {
        try (var api = new ChatFixture()) {
            var settings = settings(Map.of("AGENTIC_MANAGER_API_KEY", "only-manager-key"));
            settings.llmConfig.apiKey = null;
            assertEquals(0, new CommandLine(settings).execute("--config", scenario().toString(),
                    "--output", directory.resolve("manager-only").toString(), "--manager", "--days", "1",
                    "--base-url", "http://127.0.0.1:9/v1", "--manager-base-url", api.url(),
                    "--manager-model", "only-manager", "--manager-response-format", "json_object"));
            assertEquals(1, api.requests.size()); assertTrue(managerMemory(api.requests.getFirst()));
            assertEquals("Bearer only-manager-key", api.authorization.getFirst());
        }
    }

    @Test void missingIndependentCredentialStopsBeforeAnyRequestButValidateOnlyNeedsNoKeys() throws Exception {
        var settings = settings(Map.of()); settings.llmConfig.apiKey = "must-not-leak";
        settings.clientFactory = (config, usage) -> { throw new AssertionError("No HTTP client should be created"); };
        var command = new CommandLine(settings);
        var args = new String[]{"--config", scenario().toString(), "--output", directory.resolve("missing-key").toString(),
                "--manager", "--days", "1", "--base-url", "http://127.0.0.1:8/v1",
                "--manager-base-url", "http://127.0.0.1:9/v1", "--manager-model", "separate"};
        command.parseArgs(args);
        assertTrue(assertThrows(IllegalArgumentException.class, settings::call).getMessage().contains("AGENTIC_MANAGER_API_KEY"));
        settings.validateOnly = true;
        assertEquals(0, settings.call());
    }

    private Path scenario() throws Exception {
        var fixture = new MorningPricingTest(); fixture.directory = directory;
        return fixture.writeScenario();
    }

    private static MorningExperiment settings(Map<String, String> environment) {
        var settings = new MorningExperiment();
        settings.managerModelOptions = new MorningManagerModelOptions(environment);
        return settings;
    }

    private static boolean managerMemory(JsonNode request) {
        return request.at("/messages/0/content").asText().startsWith("Reflect");
    }

    private static final class ChatFixture implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final List<String> authorization = new CopyOnWriteArrayList<>();

        ChatFixture() throws Exception {
            server.createContext("/v1/chat/completions", exchange -> {
                var request = Json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.add(request); authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                String system = request.at("/messages/0/content").asText();
                String user = request.at("/messages/1/content").asText();
                boolean badJson = request.at("/response_format/type").asText().equals("json_object") &&
                        !(system + user).toLowerCase(Locale.ROOT).contains("json");
                String content;
                if (managerMemory(request)) {
                    var context = Json.readTree(user);
                    content = Json.write(Map.of("memory", "Day " + context.get("day").asInt() + "; previous: " + context.path("managerMemory").asText()));
                } else if (system.startsWith("You manage congestion pricing")) {
                    content = Json.write(Map.of("rules", List.of(Map.of("link_id", "road", "start", 0, "end", 108000, "amount", .1)),
                            "decision", MorningManagerTestResponses.decision("test")));
                } else if (request.at("/response_format/json_schema/schema/properties").has("memory")) {
                    content = "{\"memory\":\"My own Qwen trip experience remains private.\"}";
                } else {
                    content = MorningJointTestSupport.withDeparture("{\"routeId\":\"c0\",\"reason\":\"Offline routed choice\"}", user);
                }
                String response = badJson ? Json.write(Map.of("error", Map.of("message", "Prompt must contain json")))
                        : Json.write(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
                                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5)));
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(badJson ? 400 : 200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        @Override public void close() { server.stop(0); }
    }
}
