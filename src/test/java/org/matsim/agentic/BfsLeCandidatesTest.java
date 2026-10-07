package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BfsLeCandidatesTest {
    @TempDir Path directory;

    private Network parallel(int count) {
        Network network = NetworkUtils.createNetwork();
        for (int i = 0; i < 4; i++) network.addNode(network.getFactory().createNode(Id.createNodeId(i), new Coord(i, 0)));
        link(network, "origin", 0, 1, 0, "car"); link(network, "end", 2, 3, 10, "car");
        for (int i = 0; i < count; i++) link(network, "a" + i, 1, 2, 10 + i * 10, "car");
        return network;
    }

    private void link(Network network, String id, int from, int to, double length, String mode) {
        var link = network.getFactory().createLink(Id.createLinkId(id), network.getNodes().get(Id.createNodeId(from)), network.getNodes().get(Id.createNodeId(to)));
        link.setLength(length); link.setFreespeed(10); link.setAllowedModes(Set.of(mode)); network.addLink(link);
    }

    private List<RouteCandidate> routes(MatsimOdCandidates generator) {
        return generator.compute(Id.createLinkId("origin"), Id.createLinkId("end"), 25200, null);
    }

    @Test void exploresMoreThanRootLinkExclusionsWithoutChangingNetwork() {
        var network = parallel(4);
        var generator = new MatsimOdCandidates(network, ConfigUtils.createConfig());
        var paths = routes(generator);
        assertEquals(4, paths.size());
        for (int i = 0; i < 4; i++) assertEquals(List.of(Id.createLinkId("origin"), Id.createLinkId("a" + i), Id.createLinkId("end")), paths.get(i).linkIds());
        assertEquals(4, generator.diagnostics("origin>end").get("shortestPathSearches"));
        assertEquals(false, generator.diagnostics("origin>end").get("searchLimitReached"));
        assertEquals(6, network.getLinks().size());
        assertEquals(4, network.getNodes().get(Id.createNodeId(1)).getOutLinks().size());
    }

    @Test void limitAndExhaustionAreDifferentAndCarRestrictionIsEnforced() {
        var network = parallel(2); link(network, "walk", 1, 2, 1, "walk");
        var limited = new MatsimOdCandidates(network, ConfigUtils.createConfig(), new MatsimOdCandidates.Settings(1, 2, 2, 1), null);
        assertEquals(1, routes(limited).size());
        assertEquals(true, limited.diagnostics("origin>end").get("searchLimitReached"));
        assertEquals(false, limited.diagnostics("origin>end").get("searchSpaceExhausted"));
        var exhausted = new MatsimOdCandidates(network, ConfigUtils.createConfig());
        assertEquals(2, routes(exhausted).size());
        assertEquals(false, exhausted.diagnostics("origin>end").get("searchLimitReached"));
        assertEquals(true, exhausted.diagnostics("origin>end").get("searchSpaceExhausted"));
        assertThrows(IllegalArgumentException.class, () -> exhausted.compute(Id.createLinkId("walk"), Id.createLinkId("end"), 0, null));
        assertThrows(IllegalArgumentException.class, () -> routes(new MatsimOdCandidates(parallel(0), ConfigUtils.createConfig())));
    }

    @Test void optionalTimeAndOverlapFiltersAreExplicitAndMetricsAreLengthWeighted() {
        var network = parallel(4);
        var limited = new MatsimOdCandidates(network, ConfigUtils.createConfig(), new MatsimOdCandidates.Settings(100, 1.6, 1.6, 1), null);
        assertEquals(2, routes(limited).size());
        assertEquals(2, limited.diagnostics("origin>end").get("qualityRejectedPaths"));
        assertEquals(true, limited.diagnostics("origin>end").get("searchSpaceExhausted"));
        var overlap = new MatsimOdCandidates(network, ConfigUtils.createConfig(), new MatsimOdCandidates.Settings(100, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, .4), null);
        assertEquals(1, routes(overlap).size()); // Common end is 10m: overlap with c0 is 10/20 = .5.
        var generator = new MatsimOdCandidates(network, ConfigUtils.createConfig()); routes(generator);
        var row = Json.readTree(Json.write(generator.diagnostics("origin>end")));
        assertEquals(.5, row.get("routes").get(0).get("overlapWithOtherRoutes").get("c1").asDouble(), 1e-9);
        assertEquals(1.5, row.get("routes").get(1).get("freeFlowTimeRatioToC0").asDouble(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new MatsimOdCandidates.Settings(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MatsimOdCandidates.Settings(1, Double.NaN, 1, 1));
    }

    @Test void largerInitialPoolFindsDistinctAlternativesBeyondTheFirstFourPaths() {
        var network = parallel(0);
        network.addNode(network.getFactory().createNode(Id.createNodeId(4), new Coord(4, 0)));
        // The first four BFS discoveries include a redundant branch; the useful fourth
        // selected route is only discovered later. Small detours all pass cost limits.
        link(network, "stem", 1, 4, 90, "car");
        for (int i = 0; i < 4; i++) link(network, "x" + i, 4, 2, 10 + i, "car");
        for (int i = 0; i < 3; i++) link(network, "z" + i, 1, 2, 120 + i * 10, "car");
        var small = new MatsimOdCandidates(network, ConfigUtils.createConfig(),
                new MatsimOdCandidates.Settings(1000, 4, 2.5, 300, 2.5, 2000, .85), null);
        assertEquals(3, routes(small).size());
        var expanded = new MatsimOdCandidates(network, ConfigUtils.createConfig());
        var selected = routes(expanded);
        assertEquals(4, selected.size());
        assertTrue(selected.getLast().linkIds().contains(Id.createLinkId("z2")));
        var diagnostics = expanded.diagnostics("origin>end");
        assertEquals(7, diagnostics.get("generatedCandidateCount"));
        assertEquals(3, diagnostics.get("overlapRejectedPaths"));
        assertEquals("search_space_exhausted", diagnostics.get("searchStopReason"));
        assertEquals(4, diagnostics.get("candidateCount"));
    }

    @Test void selectionPrefersLowerCostRatherThanBfsDiscoveryOrder() {
        var network = parallel(0);
        network.addNode(network.getFactory().createNode(Id.createNodeId(4), new Coord(4, 0)));
        link(network, "a0", 1, 4, 10, "car"); link(network, "a1", 1, 4, 100, "car");
        link(network, "b0", 4, 2, 10, "car"); link(network, "b1", 4, 2, 15, "car");
        var generator = new MatsimOdCandidates(network, ConfigUtils.createConfig());
        var selected = routes(generator);
        assertEquals(3, selected.size()); // The last combination overlaps its cheaper alternative by >85%.
        assertEquals(3, selected.getFirst().freeFlowTravelTime());
        assertEquals(3.5, selected.get(1).freeFlowTravelTime());
        assertTrue(selected.get(1).linkIds().contains(Id.createLinkId("b1")));
        assertFalse(selected.get(1).linkIds().contains(Id.createLinkId("a1")));
        assertEquals(List.of("c0", "c1", "c2"), selected.stream().map(RouteCandidate::id).toList());
        assertEquals(1, generator.diagnostics("origin>end").get("overlapRejectedPaths"));
    }

    @Test void timeAndDistanceSlacksProtectShortTripsButEachDimensionMustPass() {
        var network = parallel(2); network.getLinks().get(Id.createLinkId("a1")).setLength(90);
        assertEquals(2, routes(new MatsimOdCandidates(network, ConfigUtils.createConfig())).size());
        for (double[] slacks : List.of(new double[]{0, 0}, new double[]{7.9, 80}, new double[]{8, 79.9})) {
            var strict = new MatsimOdCandidates(network, ConfigUtils.createConfig(),
                    new MatsimOdCandidates.Settings(100, 16, 2.5, slacks[0], 2.5, slacks[1], .85), null);
            assertEquals(1, routes(strict).size());
            assertEquals(1, strict.diagnostics("origin>end").get("costRejectedPaths"));
            assertEquals(2, strict.diagnostics("origin>end").get("generatedCandidateCount"));
        }
        assertEquals(2, routes(new MatsimOdCandidates(network, ConfigUtils.createConfig(),
                new MatsimOdCandidates.Settings(100, 16, 2.5, 8, 2.5, 80, .85), null)).size());
        assertThrows(IllegalArgumentException.class, () -> new MatsimOdCandidates.Settings(100, 3, 2.5, 300, 2.5, 2000, .85));
        assertThrows(IllegalArgumentException.class, () -> new MatsimOdCandidates.Settings(100, 16, 2.5, -1, 2.5, 2000, .85));
        assertThrows(IllegalArgumentException.class, () -> new MatsimOdCandidates.Settings(100, 16, 2.5, 300, 2.5, Double.POSITIVE_INFINITY, .85));
    }

    @Test void zeroCostBaselineUsesAbsoluteSlackAndDisabledRatiosRemainUnbounded() {
        var network = parallel(2);
        network.getLinks().get(Id.createLinkId("a0")).setLength(0);
        network.getLinks().get(Id.createLinkId("a1")).setLength(10);
        network.getLinks().get(Id.createLinkId("end")).setLength(0);
        assertEquals(2, routes(new MatsimOdCandidates(network, ConfigUtils.createConfig(),
                new MatsimOdCandidates.Settings(100, 16, 2.5, 1, 2.5, 10, .85), null)).size());
        assertEquals(1, routes(new MatsimOdCandidates(network, ConfigUtils.createConfig(),
                new MatsimOdCandidates.Settings(100, 16, 2.5, 0, 2.5, 0, .85), null)).size());
        assertEquals(2, routes(new MatsimOdCandidates(network, ConfigUtils.createConfig(),
                new MatsimOdCandidates.Settings(100, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 1), null)).size());
    }

    @Test void searchLimitIsReportedEvenIfFourSelectedRoutesAlreadyExist() {
        var generator = new MatsimOdCandidates(parallel(8), ConfigUtils.createConfig(),
                new MatsimOdCandidates.Settings(4, 16, 2.5, 300, 2.5, 2000, .85), null);
        assertEquals(4, routes(generator).size());
        var diagnostics = generator.diagnostics("origin>end");
        assertEquals(true, diagnostics.get("targetReached"));
        assertEquals(false, diagnostics.get("initialPoolTargetReached"));
        assertEquals(true, diagnostics.get("searchLimitReached"));
        assertEquals(false, diagnostics.get("searchSpaceExhausted"));
        assertEquals("search_limit", diagnostics.get("searchStopReason"));
    }

    @Test void duplicatePathStatesStillExpandToReachFourthCombination() {
        Network network = NetworkUtils.createNetwork();
        for (int i = 0; i < 5; i++) network.addNode(network.getFactory().createNode(Id.createNodeId(i), new Coord(i, 0)));
        link(network, "origin", 0, 1, 0, "car"); link(network, "end", 3, 4, 10, "car");
        link(network, "a0", 1, 2, 10, "car"); link(network, "a1", 1, 2, 20, "car");
        link(network, "b0", 2, 3, 10, "car"); link(network, "b1", 2, 3, 20, "car");
        var generator = new MatsimOdCandidates(network, ConfigUtils.createConfig());
        var routes = routes(generator);
        assertEquals(4, routes.size()); assertEquals(4, new HashSet<>(routes.stream().map(RouteCandidate::linkIds).toList()).size());
        assertTrue(routes.stream().anyMatch(c -> c.linkIds().contains(Id.createLinkId("a1")) && c.linkIds().contains(Id.createLinkId("b1"))));
    }

    @Test void zeroLengthInteriorLinksNeedNoArtificialCostFloor() {
        var network = parallel(4);
        network.getLinks().values().forEach(l -> l.setLength(0));
        var generator = new MatsimOdCandidates(network, ConfigUtils.createConfig());
        assertEquals(4, routes(generator).size());
        assertTrue(routes(generator).stream().allMatch(r -> r.freeFlowTravelTime() == 0));
        assertDoesNotThrow(() -> Json.readTree(Json.write(generator.diagnostics("origin>end"))));
    }

    @Test void importedInternalLoopIsRejectedEvenWhenLinksAreConnected() throws Exception {
        var network = parallel(2);
        link(network, "back", 2, 1, 10, "car");
        Path file = directory.resolve("loop.jsonl");
        Files.writeString(file, "{\"od\":\"origin>end\",\"routes\":[{\"routeId\":\"c0\",\"links\":[\"origin\",\"a0\",\"back\",\"a1\",\"end\"],\"freeFlowSeconds\":5}]}\n");
        assertThrows(IllegalArgumentException.class, () -> new MatsimOdCandidates(network, ConfigUtils.createConfig(), MatsimOdCandidates.Settings.defaults(), file));
    }

    @Test void compressedCandidateFileRoundTripsAndRejectsTruncatedGzip() throws Exception {
        var network = parallel(4); var config = ConfigUtils.createConfig();
        var original = new MatsimOdCandidates(network, config); var expected = routes(original);
        try (var output = new OutputWriter(directory, true)) {
            output.appendLine("candidates.jsonl", Map.of("od", "origin>end", "routes", original.diagnostics("origin>end").get("routes")));
        }
        Path file = directory.resolve("candidates.jsonl.gz");
        assertEquals(expected, routes(new MatsimOdCandidates(network, config, MatsimOdCandidates.Settings.defaults(), file)));
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 8));
        assertThrows(IllegalArgumentException.class, () -> new MatsimOdCandidates(network, config, MatsimOdCandidates.Settings.defaults(), file));
    }

    @Test void candidateFileRoundTripsAndRejectsMissingOdsBadTimeAndDisconnectedRoutes() throws Exception {
        var network = parallel(4); var config = ConfigUtils.createConfig();
        var original = new MatsimOdCandidates(network, config); var expected = routes(original);
        Path file = directory.resolve("candidates.jsonl");
        var report = original.diagnostics("origin>end");
        Files.writeString(file, Json.write(Map.of("od", "origin>end", "routes", report.get("routes"))) + "\n");
        var reused = new MatsimOdCandidates(network, config, MatsimOdCandidates.Settings.defaults(), file);
        assertEquals(expected, routes(reused));
        assertEquals("candidate-file", reused.diagnostics("origin>end").get("source"));
        assertNull(reused.diagnostics("origin>end").get("generatedCandidateCount"));
        assertEquals("imported", reused.diagnostics("origin>end").get("searchStopReason"));
        assertEquals("validate-frozen-file", reused.settingsReport().get("appliedMode"));
        assertThrows(IllegalArgumentException.class, () -> reused.compute(Id.createLinkId("a0"), Id.createLinkId("end"), 0, null));
        var json = Json.MAPPER.readTree(Files.readString(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("routes").get(0)).put("freeFlowSeconds", 999);
        Files.writeString(file, Json.write(json));
        assertThrows(IllegalArgumentException.class, () -> new MatsimOdCandidates(network, config, MatsimOdCandidates.Settings.defaults(), file));
        Files.writeString(file, "{\"od\":\"origin>end\",\"routes\":[{\"routeId\":\"c0\",\"links\":[\"origin\",\"end\"],\"freeFlowSeconds\":1}]}\n");
        assertThrows(IllegalArgumentException.class, () -> new MatsimOdCandidates(network, config, MatsimOdCandidates.Settings.defaults(), file));
    }

    @Test void frozenImportedPoolIsValidatedWithoutReorderingOrPruning() throws Exception {
        var network = parallel(4);
        var unlimited = new MatsimOdCandidates.Settings(100, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 1);
        var generator = new MatsimOdCandidates(network, ConfigUtils.createConfig(), unlimited, null);
        routes(generator);
        var row = Json.readTree(Json.write(Map.of("od", "origin>end", "routes", generator.diagnostics("origin>end").get("routes"))));
        var savedRoutes = (com.fasterxml.jackson.databind.node.ArrayNode) row.get("routes");
        var one = savedRoutes.get(1).deepCopy(); var three = savedRoutes.get(3).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) three).put("routeId", "c1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) one).put("routeId", "c3");
        savedRoutes.set(1, three); savedRoutes.set(3, one);
        Path file = directory.resolve("frozen.jsonl"); Files.writeString(file, Json.write(row) + "\n");
        var reused = new MatsimOdCandidates(network, ConfigUtils.createConfig(), unlimited, file);
        assertTrue(routes(reused).get(1).linkIds().contains(Id.createLinkId("a3")));
        var strict = new MatsimOdCandidates(network, ConfigUtils.createConfig(), new MatsimOdCandidates.Settings(100, 1.6, 1.6, 1), file);
        assertThrows(IllegalArgumentException.class, () -> routes(strict));
    }
}
