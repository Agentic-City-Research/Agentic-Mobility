package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MorningManagerCompactContextTest {
    @TempDir Path directory;

    @Test void fullSiouxFallsTopologyAndExactCapacitiesSurviveCompressionAndInputShrinks() throws Exception {
        var config = ConfigUtils.loadConfig("scenarios/SiouxFalls-public/config.xml");
        var network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(Path.of("scenarios/SiouxFalls-public/config.xml").toAbsolutePath()
                .getParent().resolve(config.network().getInputFile()).normalize().toString());
        config.qsim().setFlowCapFactor(.05); config.qsim().setStorageCapFactor(Math.pow(.05, .75));
        var environment = new MorningManagerEnvironment(network, config, 84110, 4206, true, "hybrid", 0, Map.of());
        var request = new AtomicReference<String>();
        Path out = directory.resolve("network");
        try (var output = new OutputWriter(out)) {
            var manager = new MorningManager(network, 108000, environment, (role, system, user) -> {
                assertTrue(system.contains("Target 400-600 words")); request.set(user); return response("hold", "Brief evidence is sufficient.");
            }, output, new MorningRequestBudget(2), 1, Map.of());
            var initial = state(TollPolicy.empty(-1), 0);
            for (int day = 0; day < 20; day++) {
                var rows = new ArrayList<Map<String, Object>>(); int i = 0;
                for (var link : environment.topology()) {
                    rows.add(row(link.get("linkId").toString(), 1 + i % 3, 100 + i, i % 9, i % 17 == 0 ? 1 : 0)); i++;
                }
                manager.observe(day, rows, initial, trips(100, 98));
            }
            String full = Json.write(manager.context(20, 0, initial));
            assertTrue(manager.update(20, 0, initial).accepted());
            var actual = Json.readTree(request.get());
            assertEquals(334, actual.get("network").size());
            assertFalse(actual.has("dailyTraffic")); assertFalse(actual.has("hourlyTrafficSummary"));
            assertEquals(20, actual.get("trafficSummary").get("period").get("observedDays").asInt());
            assertEquals(7, actual.get("trafficSummary").get("recentUpTo7ObservedDays").get("observedDays").asInt());
            assertEquals(.05, actual.at("/publicEnvironment/populationAndCapacity/flowCapacityFactor").asDouble());
            assertEquals(Math.pow(.05, .75), actual.at("/publicEnvironment/populationAndCapacity/storageCapacityFactor").asDouble());
            var expected = environment.topology();
            for (int i = 0; i < expected.size(); i++) {
                var source = expected.get(i); var link = actual.get("network").get(i);
                assertEquals(source.get("linkId"), link.get(0).asText());
                assertEquals(source.get("fromNode"), link.get(1).asText()); assertEquals(source.get("toNode"), link.get(2).asText());
                assertEquals(((Number) source.get("lengthMeters")).doubleValue(), actual.get("lengthsMeters").get(link.get(3).asInt()).asDouble());
                var type = actual.get("roadTypes").get(link.get(4).asInt());
                for (int j = 0; j < MorningManagerCompactContext.ROAD_TYPE_COLUMNS.size(); j++)
                    assertEquals(((Number) source.get(MorningManagerCompactContext.ROAD_TYPE_COLUMNS.get(j))).doubleValue(), type.get(j).asDouble());
            }
            var covered = new HashSet<Integer>();
            for (var chain : actual.at("/roadCorridors/networkRowChains")) for (var index : chain) assertTrue(covered.add(index.asInt()));
            assertEquals(334, covered.size());
            assertFalse(request.get().contains("PRIVATE_PERSON"));
            assertTrue(request.get().length() < full.length() * .65, "Compact=" + request.get().length() + ", full=" + full.length());
            output.flush();
            var archived = Json.readTree(JsonlTestSupport.readLines(out.resolve("manager-contexts-full.jsonl")).getFirst());
            assertEquals(20, archived.get("dailyTraffic").size()); assertEquals(334, archived.get("linkTrafficTotals").size());
            var sizes = Json.readTree(JsonlTestSupport.readLines(out.resolve("manager-input-sizes.jsonl")).getFirst());
            assertEquals(request.get().length(), sizes.get("sentContextCharacters").asInt());
            assertEquals(MorningPromptRenderer.hash(request.get()), sizes.get("contextHash").asText());
            var cachedUsage = Json.readTree(JsonlTestSupport.readLines(out.resolve("manager-attempts.jsonl")).getFirst());
            assertTrue(cachedUsage.get("promptCacheUsageReported").asBoolean());
            assertEquals(0,cachedUsage.at("/usage/promptCacheHitTokens").asInt());
            String firstRequest = request.get();
            assertTrue(firstRequest.indexOf("\"network\":")<firstRequest.indexOf("\"day\":"));
            assertTrue(firstRequest.indexOf("\"network\":")<firstRequest.indexOf("\"currentPolicy\":"));
            assertTrue(manager.update(21,0,initial).accepted());
            assertEquals(firstRequest.substring(0,firstRequest.indexOf("\"day\":")),
                    request.get().substring(0,request.get().indexOf("\"day\":")),"Changing evaluation day/memory must leave the full network prefix identical");
            assertEquals(21,Json.readTree(request.get()).at("/publicEnvironment/travelerResponse/evaluatedAtIteration").asInt());
            // Optional, local audit fixture; no model request or committed runtime data.
            String auditPath = System.getProperty("manager.compact.auditDir");
            if (auditPath != null) {
                Path audit = Path.of(auditPath); Files.createDirectories(audit);
                Files.writeString(audit.resolve("full.json"), full); Files.writeString(audit.resolve("compact.json"), firstRequest);
                Files.writeString(audit.resolve("system.txt"), MorningManager.BASIC_SYSTEM);
                var attempt = Json.readTree(JsonlTestSupport.readLines(out.resolve("manager-attempts.jsonl")).getFirst());
                Files.writeString(audit.resolve("schema.json"), Json.write(attempt.get("outputSchema")));
            }
        }
    }

    @Test void periodTotalsAndRecentTrendUseRecordedDaysAndPooledDenominators() throws Exception {
        var request = new AtomicReference<String>();
        try (var output = new OutputWriter(directory.resolve("trend"))) {
            var network = network(); var manager = manager(network, output, request, 1);
            var initial = state(TollPolicy.empty(-1), 0);
            for (int i = 0; i < 14; i++) {
                // Days need not be consecutive. Daily ratios differ, so averaging them is incorrect.
                manager.observe(i * 2, List.of(row("a", i < 7 ? 8 : 2, i < 7 ? 800 : 120, i < 7 ? 720 : 90, 0)), initial,
                        trips(i < 7 ? 100 : 50, i < 7 ? 99 : 48));
            }
            assertTrue(manager.update(28, 0, initial).accepted());
            var summary = Json.readTree(request.get()).get("trafficSummary");
            var period = summary.get("period");
            assertEquals(14, period.get("observedDays").asInt()); assertEquals(27, period.get("calendarSpanDays").asInt());
            assertEquals(5670, period.get("totals").get(0).asDouble());
            assertEquals(70, period.get("totals").get(3).asDouble());
            assertEquals(81, period.get("totals").get(0).asDouble() / period.get("totals").get(3).asDouble());
            assertEquals(1050, period.get("publicCarTrips").get("carTripsStarted").asInt());
            assertEquals(1029, period.get("publicCarTrips").get("carTripsCompleted").asInt());
            assertEquals(-630, summary.at("/recentChangeFromPrevious/absoluteDifference/0").asDouble());
            assertEquals(-87.5, summary.at("/recentChangeFromPrevious/relativePercent/0").asDouble());
        }
    }

    @Test void keepTwoEffectsPoolAllOlderDaysAndRetainWithdrawnAndUnobservedChargedRoads() throws Exception {
        var request = new AtomicReference<String>(); Path out = directory.resolve("history");
        try (var output = new OutputWriter(out)) {
            var network = network();
            var manager = new MorningManager(network, 108000, environment(network), (role, system, user) -> {
                request.set(user); return response("remove", "The last two effects differ; keep older observations separate.");
            }, output, new MorningRequestBudget(1), 1, Map.of());
            manager.observe(0, List.of(row("a", 1, 100, 10, 0)), state(TollPolicy.empty(-1), 0));
            manager.observe(1, List.of(row("a", 1, 100, 20, 0)), state(price("a", 1), 1));
            manager.observe(2, List.of(row("a", 2, 200, 30, 0)), state(price("a", 1), 1));
            manager.observe(3, List.of(row("b", 10, 1000, 40, 0)), state(price("b", 3), 2));
            manager.observe(4, List.of(row("b", 10, 1000, 100, 1)), state(TollPolicy.empty(4), 3));
            manager.observe(5, List.of(row("a", 1, 100, 0, 0)), state(price("a", 5), 4));
            // c has no recorded traffic; a was recently priced but has zero delay in the final period.
            assertTrue(manager.update(6, 0, state(price("c", 6), 5)).accepted());
            var actual = Json.readTree(request.get()); var feedback = actual.get("policyFeedback");
            assertEquals(2, feedback.get("policyPeriods").size());
            assertEquals(3, feedback.get("policyPeriods").get(0).get("policyVersion").asInt());
            assertEquals(4, feedback.get("policyPeriods").get(1).get("policyVersion").asInt());
            var older = feedback.get("olderPolicySummary");
            assertEquals(3, older.get("policyPeriods").asInt()); assertEquals(4, older.at("/observations/observedDays").asInt());
            assertEquals(100, older.at("/observations/totals/0").asInt()); assertEquals(14, older.at("/observations/totals/3").asInt());
            assertTrue(actual.at("/roadSelection/recentWithdrawnLinks").toString().contains("a"));
            assertEquals("c", actual.at("/roadSelection/selectedLinksWithoutRecordedRows/0").asText());
            assertTrue(actual.get("linkTrafficTotals").toString().contains("b")); // Failures are independently retained.
            assertEquals("a", feedback.at("/trackedChargingWindows/0/linkId").asText());
            assertTrue(feedback.at("/trackedChargingWindows/0/phases").size() <= 3);
            output.flush();
            var archive = Json.readTree(JsonlTestSupport.readLines(out.resolve("manager-contexts-full.jsonl")).getFirst());
            assertEquals(5, archive.at("/policyFeedback/policyPeriods").size());
            assertEquals(2, archive.at("/policyFeedback/trackedChargingWindows").size());
        }
    }

    @Test void memoryRepairKeepsTheFourHundredToSixHundredWordTargetWithoutMakingItAMinimum() throws Exception {
        var network = network(); var systems = new ArrayList<String>();
        try (var output = new OutputWriter(directory.resolve("memory"))) {
            var manager = new MorningManager(network, 108000, environment(network), (role, system, user) -> {
                systems.add(system);
                return role.equals("manager") ? response("hold", "word ".repeat(1001))
                        : new LlmResponse("{\"memory\":\"Short evidence, no padding.\"}", 0, 0, 0, 0);
            }, output, new MorningRequestBudget(2), 2, Map.of());
            assertTrue(manager.update(20, 0, state(TollPolicy.empty(-1), 0)).accepted());
            assertEquals(2, systems.size()); systems.forEach(s -> assertTrue(s.contains("Target 400-600 words")));
        }
    }

    private MorningManager manager(Network network, OutputWriter output, AtomicReference<String> request, int attempts) {
        return new MorningManager(network, 108000, environment(network), (role, system, user) -> {
            request.set(user); return response("hold", "Concise notes.");
        }, output, new MorningRequestBudget(attempts), attempts, Map.of());
    }

    private static LlmResponse response(String action, String memory) {
        return new LlmResponse(Json.write(Map.of("rules", List.of(), "decision", MorningManagerTestResponses.decision(action), "memory", memory)), 0, 0, 0, 0);
    }
    private static MorningTollSystem.State state(TollPolicy policy, int version) { return new MorningTollSystem.State(policy, "v" + version, version); }
    private static TollPolicy price(String link, int day) { return new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId(link), 0, 900, 1)), day); }
    private static Map<String, Object> trips(int started, int completed) { return Map.of("carTripsStarted", started, "carTripsCompleted", completed, "carTripsAborted", started - completed, "carTripsUnfinished", 0); }
    private static Map<String, Object> row(String link, int completed, double travel, double delay, int aborted) {
        var row = new LinkedHashMap<String, Object>(); row.put("link_id", link); row.put("start", 0); row.put("end", 900);
        row.put("entered", completed + aborted); row.put("completed", completed); row.put("aborted", aborted); row.put("unfinished", 0);
        row.put("partial", 0); row.put("total_travel_time", travel); row.put("total_delay", delay); row.put("personId", "PRIVATE_PERSON"); return row;
    }
    private static MorningManagerEnvironment environment(Network network) {
        var config = ConfigUtils.createConfig(); return new MorningManagerEnvironment(network, config, 100, 10, true, "hybrid", 0, Map.of());
    }
    private static Network network() {
        var network = NetworkUtils.createNetwork();
        for (int i = 0; i < 4; i++) network.addNode(network.getFactory().createNode(Id.createNodeId(i), new Coord(i * 100, 0)));
        for (int i = 0; i < 3; i++) {
            var link = network.getFactory().createLink(Id.createLinkId(String.valueOf((char) ('a' + i))),
                    network.getNodes().get(Id.createNodeId(i)), network.getNodes().get(Id.createNodeId(i + 1)));
            link.setLength(100.123456789); link.setFreespeed(10.987654321); link.setCapacity(1000.123456789);
            link.setNumberOfLanes(1); link.setAllowedModes(Set.of("car")); network.addLink(link);
        }
        return network;
    }
}
