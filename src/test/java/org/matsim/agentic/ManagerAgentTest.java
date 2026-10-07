package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.core.network.NetworkUtils;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagerAgentTest {
    @TempDir Path temp;

    @Test void exhaustedDuplicateRepairsRetainPolicyAndMemoryThenRecover() throws Exception {
        var network = NetworkUtils.createNetwork();
        var f = network.getFactory();
        var a = f.createNode(Id.createNodeId("a"), new Coord(0, 0));
        var b = f.createNode(Id.createNodeId("b"), new Coord(100, 0));
        network.addNode(a); network.addNode(b);
        var link = f.createLink(Id.createLinkId("50_1"), a, b);
        link.setAllowedModes(Set.of("car")); network.addLink(link);
        String valid = "{\"rules\":[{\"link_id\":\"50_1\",\"start\":0,\"end\":10,\"amount\":2}],\"memory\":\"accepted memory\"}";
        String duplicate = "{\"rules\":[{\"link_id\":\"50_1\",\"start\":0,\"end\":10,\"amount\":3},{\"link_id\":\"50_1\",\"start\":20,\"end\":30,\"amount\":4}],\"memory\":\"rejected memory\"}";
        var replies = new ArrayDeque<>(List.of(valid, duplicate, duplicate, duplicate,
                "{\"rules\":[],\"memory\":\"recovered\"}"));
        var users = new ArrayList<String>();
        try (var output = new OutputWriter(temp)) {
            LlmClient client = (role, system, user) -> {
                users.add(user);
                return new LlmResponse(replies.remove(), 0, 0, 0, 0);
            };
            var manager = new ManagerAgent(network, client, output, 3);
            var initial = manager.decide(6, TollPolicy.empty(-1), List.of(), 1, 0);
            var retained = manager.decide(11, initial, List.of(), 1, 0);
            assertSame(initial, retained);
            assertEquals(6, retained.day());
            assertEquals(2.0, retained.amountAt(link.getId(), 5));
            assertEquals(0.0, retained.amountAt(link.getId(), 25));
            var recovered = manager.decide(16, retained, List.of(), 1, 0);
            assertEquals(16, recovered.day());
            assertTrue(recovered.rules().isEmpty());
            assertTrue(users.get(4).contains("accepted memory"));
            assertFalse(users.get(4).contains("rejected memory"));
        }
        assertTrue(replies.isEmpty());
        assertEquals(5, Files.readAllLines(temp.resolve("attempts.jsonl")).size());
        var fallback = Json.readJson(Files.readString(temp.resolve("manager-fallbacks.jsonl")));
        assertEquals(11, fallback.path("day").asInt());
        assertEquals("policy_update_skipped", fallback.path("event").asText());
        assertEquals(6, fallback.path("policy").path("day").asInt());
        assertTrue(fallback.path("error").asText().contains("50_1"));
    }

    @Test void malformedFirstUpdateRetainsNoTolls() throws Exception {
        try (var output = new OutputWriter(temp)) {
            LlmClient client = (role, system, user) -> new LlmResponse("not JSON", 0, 0, 0, 0);
            var manager = new ManagerAgent(NetworkUtils.createNetwork(), client, output, 3);
            var retained = manager.decide(6, null, List.of(), 1, 0);
            assertEquals(-1, retained.day());
            assertTrue(retained.rules().isEmpty());
        }
        assertEquals(3, Files.readAllLines(temp.resolve("attempts.jsonl")).size());
        assertTrue(Files.exists(temp.resolve("manager-fallbacks.jsonl")));
    }

    @Test void singleManagerUsesWorldModelWithoutCostGuidance() throws Exception {
        var systems = new ArrayList<String>();
        var users = new ArrayList<String>();
            try (var output = new OutputWriter(temp)) {
                LlmClient client = (role, system, user) -> {
                    systems.add(system); users.add(user);
                    return new LlmResponse("{\"rules\":[],\"memory\":\"observed\"}", 0, 0, 0, 0);
                };
                var manager = new ManagerAgent(NetworkUtils.createNetwork(), client, output, 1);
                manager.decide(6, TollPolicy.empty(-1),
                        List.of(Map.of("day", 5, "network", Map.of("total_delay", 123, "completion_rate", 0.75))), 10, 0.5);
            }
        assertEquals(1, systems.size());
        assertTrue(systems.get(0).contains("Your objective is to reduce total network delay."));
        assertTrue(systems.get(0).contains("Downstream congestion"));
        assertFalse(systems.get(0).contains("When choosing toll amounts"));
        assertFalse(users.get(0).contains("0.062"));
        assertFalse(users.get(0).contains("cost parameters"));
        for (var user : users) {
            assertFalse(user.contains("completion"));
            assertFalse(user.contains("equivalents"));
            assertFalse(user.contains("toll 5"));
        }
        assertTrue(systems.get(0).contains("at most 40 rules"));
        assertTrue(systems.get(0).contains("at most 300 words"));
    }

    @Test void duplicateLinksAreRejectedAndMemoryIsReplaced() throws Exception {
        var network = NetworkUtils.createNetwork();
        var f = network.getFactory();
        var a = f.createNode(Id.createNodeId("a"), new Coord(0, 0));
        var b = f.createNode(Id.createNodeId("b"), new Coord(100, 0));
        network.addNode(a); network.addNode(b);
        var link = f.createLink(Id.createLinkId("road"), a, b);
        link.setAllowedModes(Set.of("car")); network.addLink(link);
        String duplicate = "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":10,\"amount\":1},{\"link_id\":\"road\",\"start\":20,\"end\":30,\"amount\":1}],\"memory\":\"invalid\"}";
        var users = new ArrayList<String>();
        var replies = new ArrayDeque<>(List.of(duplicate,
                "{\"rules\":[],\"memory\":\"first accepted\"}",
                "{\"rules\":[],\"memory\":\"replacement\"}",
                "{\"rules\":[],\"memory\":\"final\"}"));
        try (var output = new OutputWriter(temp)) {
            LlmClient client = (role, system, user) -> {
                users.add(user); return new LlmResponse(replies.remove(), 0, 0, 0, 0);
            };
            var manager = new ManagerAgent(network, client, output, 2);
            var policy = manager.decide(6, null, List.of(), 1, 0);
            policy = manager.decide(11, policy, List.of(), 1, 0);
            manager.decide(16, policy, List.of(), 1, 0);
            assertTrue(users.get(1).contains("each link may appear at most once"));
            assertTrue(users.get(2).contains("first accepted"));
            assertTrue(users.get(3).contains("replacement"));
            assertFalse(users.get(3).contains("first accepted"));
        }
    }
}
