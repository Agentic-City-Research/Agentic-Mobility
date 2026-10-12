package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.*;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MorningManagerPublicContextTest {
    @TempDir Path directory;

    @Test void nativeTimeMutationIsAdvertisedIndependentlyAndStopsAtItsConfiguredIteration() {
        var config = config();
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setStrategyName("TimeAllocationMutator").setWeight(.1).setDisableAfter(2));
        config.timeAllocationMutator().setMutationRange(600);
        var environment = new MorningManagerEnvironment(network(), config, 100, 10, false, "hybrid", 0, Map.of());
        var active = Json.readTree(Json.write(environment.parameters(2))).get("travelerResponse");
        assertTrue(active.get("departureTimeChoiceEnabled").asBoolean());
        assertTrue(active.get("nativeDepartureTimeMutationEnabled").asBoolean());
        assertFalse(active.get("llmJointDepartureAndRouteChoiceEnabled").asBoolean());
        assertFalse(active.get("plannedActivityEndTimesFixed").asBoolean());
        assertEquals(600, active.get("nativeTimeMutationRangeSeconds").asDouble());
        var stopped = Json.readTree(Json.write(environment.parameters(3))).get("travelerResponse");
        assertFalse(stopped.get("nativeDepartureTimeMutationEnabled").asBoolean());
        assertFalse(stopped.get("departureTimeChoiceEnabled").asBoolean());
    }

    @Test void capacityUsesActualFactorsAndPeriodWhileMoneyScaleIncludesActivityOpportunityCost() {
        var network = network(); network.setCapacityPeriod(1800);
        var config = config(); config.qsim().setFlowCapFactor(.1); config.qsim().setStorageCapFactor(.25);
        var dirtyVisibility = new LinkedHashMap<String, Object>(Map.of("scope", "controlled", "persons", 0));
        dirtyVisibility.put("personId", "private-person-marker"); dirtyVisibility.put("memory", "private-memory-marker");
        var environment = new MorningManagerEnvironment(network, config, 100, 10, true, "hybrid", 0, dirtyVisibility);
        var json = Json.readTree(Json.write(environment.parameters()));
        var population = json.get("populationAndCapacity");
        assertEquals(.1, population.get("actualPopulationSamplingRate").asDouble());
        assertEquals(.1, population.get("flowCapacityFactor").asDouble());
        assertEquals(.25, population.get("storageCapacityFactor").asDouble());
        var link = environment.topology().stream().filter(l -> l.get("linkId").equals("a")).findFirst().orElseThrow();
        assertEquals(1000.0, link.get("rawCapacity")); assertEquals(200.0, link.get("nominalScaledFlowCapacityPerHour"));
        assertEquals(3.875, json.get("routingCostScale").get("routingMinutesPerMonetaryUnit").asDouble(), 1e-12);
        assertEquals(1 / 3.875, json.get("routingCostScale").get("monetaryUnitsPerRoutingMinute").asDouble(), 1e-12);
        assertTrue(json.get("travelerResponse").get("routeChoiceEnabled").asBoolean());
        assertFalse(json.get("travelerResponse").get("departureTimeChoiceEnabled").asBoolean());
        assertFalse(json.get("travelerResponse").get("modeChoiceEnabled").asBoolean());
        assertFalse(json.get("travelerResponse").get("tripCancellationEnabled").asBoolean());
        assertFalse(json.toString().contains("private-person-marker")); assertFalse(json.toString().contains("private-memory-marker"));
        assertFalse(Json.readTree(Json.write(environment.parameters(1001))).get("travelerResponse").get("routeChoiceEnabled").asBoolean());

        config.scoring().setPerforming_utils_hr(0);
        var zero = new MorningManagerEnvironment(network, config, 100, 10, true, "hybrid", 0, Map.of());
        assertTrue(Json.readTree(Json.write(zero.parameters())).get("routingCostScale").get("routingMinutesPerMonetaryUnit").isNull());
    }

    @Test void carCorridorsStopAtChoiceJunctionsAndAccumulateOverlappingLinkPricesWithBoundarySemantics() {
        var network = network(); var environment = environment(network);
        var policy = new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("a"), 0, 10, 1),
                new TollPolicy.TollRule(Id.createLinkId("b"), 5, 15, 2)), 20);
        var json = Json.readTree(Json.write(environment.corridorContext(policy)));
        assertEquals(3, json.get("corridorCount").asInt());
        var covered = new HashSet<String>(); int count = 0;
        for (var chain : json.get("corridors")) for (var link : chain.get("linkIds")) {
            assertTrue(covered.add(link.asText())); count++;
        }
        assertEquals(4, count); assertFalse(covered.contains("walk-branch"));
        var chain = json.get("corridors").get(0);
        assertEquals(List.of("a", "b"), Json.MAPPER.convertValue(chain.get("linkIds"), List.class));
        var costs = chain.get("currentPolicyCumulativeEntryCosts");
        assertEquals(3, costs.size());
        assertEquals(0, costs.get(0).get("start").asDouble()); assertEquals(5, costs.get(0).get("end").asDouble());
        assertEquals(1, costs.get(0).get("sumOfLinkEntryAmounts").asDouble());
        assertEquals(3, costs.get(1).get("sumOfLinkEntryAmounts").asDouble());
        assertEquals(3 * 3.875, costs.get(1).get("equivalentRoutingMinutes").asDouble(), 1e-12);
        assertEquals(10, costs.get(2).get("start").asDouble());
        assertEquals(2, costs.get(2).get("sumOfLinkEntryAmounts").asDouble());
        assertTrue(json.get("chargingInterpretation").asText().contains("crossing a boundary"));

        var tolls = new MorningTollSystem(network, EventsUtils.createEventsManager(), null);
        tolls.setPolicy(policy, 1);
        var config = config(); var person = org.matsim.core.population.PopulationUtils.getFactory().createPerson(Id.createPersonId("native"));
        var base = new MorningTollDisutilityFactory(config, new MorningTollSystem(network, EventsUtils.createEventsManager(), null))
                .createTravelDisutility((l,t,p,v) -> 10);
        var priced = new MorningTollDisutilityFactory(config, tolls).createTravelDisutility((l,t,p,v) -> 10);
        double delta = 0;
        for (String id : List.of("a", "b")) {
            var link = network.getLinks().get(Id.createLinkId(id));
            delta += priced.getLinkTravelDisutility(link, 5, person, null) - base.getLinkTravelDisutility(link, 5, person, null);
        }
        assertEquals(.062 * 3, delta, 1e-12);
    }

    @Test void isolatedCarCyclesAreCoveredExactlyOnce() {
        var network = NetworkUtils.createNetwork(); addNodes(network, 2);
        addLink(network, "forward", 0, 1, "car"); addLink(network, "back", 1, 0, "car");
        var json = Json.readTree(Json.write(environment(network).corridorContext(TollPolicy.empty(-1))));
        assertEquals(1, json.get("corridorCount").asInt());
        assertEquals(2, json.get("corridors").get(0).get("linkIds").size());
        assertTrue(json.get("corridors").get(0).get("closedCycle").asBoolean());
    }

    @Test void publicSiouxFallsNetworkHasCompleteCorridorsAndCorrectScaledCapacityAndPriceSum() throws Exception {
        var config = ConfigUtils.loadConfig("scenarios/SiouxFalls-public/config.xml");
        var scenario = org.matsim.core.scenario.ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork();
        new MatsimNetworkReader(network).readFile(Path.of("scenarios/SiouxFalls-public/config.xml").toAbsolutePath()
                .getParent().resolve(config.network().getInputFile()).normalize().toString());
        config.qsim().setFlowCapFactor(.1); config.qsim().setStorageCapFactor(.1);
        var environment = new MorningManagerEnvironment(network, config, 84110, 8411, true, "hybrid", 0, Map.of());
        var policy = new TollPolicy(List.of(
                new TollPolicy.TollRule(Id.createLinkId("56_3"), 28800,32400,44),
                new TollPolicy.TollRule(Id.createLinkId("56_4"), 28800,32400,56),
                new TollPolicy.TollRule(Id.createLinkId("56_5"), 28800,32400,74),
                new TollPolicy.TollRule(Id.createLinkId("56_6"), 28800,32400,74),
                new TollPolicy.TollRule(Id.createLinkId("56_7"), 28800,32400,74),
                new TollPolicy.TollRule(Id.createLinkId("56_8"), 28800,32400,78)), 470);
        var snapshot = environment.context(470, policy);
        var json = Json.readTree(Json.write(snapshot));
        assertEquals(334, json.get("network").size());
        assertEquals(76, json.get("roadCorridors").get("corridorCount").asInt());
        int covered = 0; Set<String> ids = new HashSet<>();
        for (var chain : json.get("roadCorridors").get("corridors")) {
            for (var link : chain.get("linkIds")) { assertTrue(ids.add(link.asText())); covered++; }
            if (chain.get("linkIds").get(0).asText().equals("56_1")) {
                assertEquals(8, chain.get("linkIds").size());
                var interval = chain.get("currentPolicyCumulativeEntryCosts").get(0);
                assertEquals(400, interval.get("sumOfLinkEntryAmounts").asDouble());
                assertEquals(1550, interval.get("equivalentRoutingMinutes").asDouble(),1e-9);
            }
        }
        assertEquals(334, covered);
        var link = environment.topology().stream().filter(l -> l.get("linkId").equals("10_1")).findFirst().orElseThrow();
        assertEquals(196.4, ((Number)link.get("nominalScaledFlowCapacityPerHour")).doubleValue(),1e-9);
        java.nio.file.Files.writeString(directory.resolve("public-environment-example.json"), Json.write(snapshot));
    }

    @Test void factualHistorySurvivesPolicyUpdatesAndNotesAndUsesActualPolicyAndPooledTraversalDenominators() throws Exception {
        var network = network();
        try (var output = new OutputWriter(directory.resolve("history"))) {
            var manager = new MorningManager(network, 108000, environment(network),
                    (r,s,u) -> new LlmResponse(MorningManagerTestResponses.withDecision("{\"rules\":[],\"memory\":\"Subjective notes claim twelve updates\"}"),0,0,0,0),
                    output, new MorningRequestBudget(2), 1, Map.of());
            var initial = new MorningTollSystem.State(TollPolicy.empty(-1), "no-toll", 0);
            manager.observe(119, List.of(row(8, 800, 720)), initial);
            assertTrue(manager.update(120, 100, initial).accepted());
            var current = new MorningTollSystem.State(TollPolicy.empty(120), "pricing-v1-it120", 1);
            assertThrows(IllegalArgumentException.class, () -> manager.observe(119, List.of(row(8,800,720)), current));
            manager.observe(120, List.of(row(2, 120, 90)), current);
            manager.observe(149, List.of(row(10, 300, 200)), current);
            assertThrows(IllegalArgumentException.class, () -> manager.context(149, 100, current));
            var context = Json.readTree(Json.write(manager.context(150, 100, current)));
            var feedback = context.get("policyFeedback"); var periods = feedback.get("policyPeriods");
            assertEquals(3, feedback.get("observedDays").asInt()); assertEquals(2, periods.size());
            assertEquals(119, feedback.get("freePeriodReference").get("lastObservedDay").asInt());
            assertEquals(1, periods.get(1).get("policyVersion").asInt());
            assertEquals(2, periods.get(1).get("observations").get("observedDays").asInt());
            assertEquals(30, periods.get(1).get("observations").get("calendarSpanDays").asInt());
            assertEquals(145, periods.get(1).get("observations").get("meanDaily").get("totalLinkDelaySeconds").asDouble());
            assertEquals(290.0 / 12, periods.get(1).get("observations").get("delaySecondsPerCompletedTraversal").asDouble(), 1e-12);
            assertEquals(14.5, periods.get(1).get("observations").get("delaySecondsPerPopulationDay").asDouble());
            assertFalse(feedback.get("matchedNoPricingCounterfactualAvailable").asBoolean());
            assertTrue(feedback.get("comparisonInterpretation").asText().contains("not same-state"));
            assertEquals("",context.get("managerMemory").asText());
            assertTrue(manager.update(150, 100, current).accepted());
            var updated = new MorningTollSystem.State(TollPolicy.empty(150), "pricing-v2-it150", 2);
            manager.observe(150, List.of(row(0,0,0)), updated);
            var after = Json.readTree(Json.write(manager.context(151, 100, updated))).get("policyFeedback");
            assertEquals(3, after.get("policyPeriods").size());
            assertTrue(after.get("policyPeriods").get(2).get("observations").get("delaySecondsPerCompletedTraversal").isNull());
            manager.writeFinalEvaluation(100);
            output.flush(); assertEquals(3, JsonlTestSupport.readLines(directory.resolve("history/manager-policy-evaluations.jsonl")).size());
        }
    }

    @Test void eachManagerRequestCombinesPublicEnvironmentAndPolicyHistoryWithoutASelector() throws Exception {
        var network = network();
        try (var output = new OutputWriter(directory.resolve("combined"))) {
            var manager = new MorningManager(network, 108000, environment(network),
                    (r,s,u) -> {
                        var actual = Json.readTree(u);
                        assertTrue(actual.has("publicEnvironment")); assertTrue(actual.has("roadCorridors"));
                        assertTrue(actual.has("policyFeedback"));
                        assertEquals(1, actual.get("policyFeedback").get("observedDays").asInt());
                        assertFalse(u.contains("PRIVATE_PERSON"));
                        return new LlmResponse(MorningManagerTestResponses.withDecision("{\"rules\":[],\"memory\":\"notes\"}"),0,0,0,0);
                    },
                    output, new MorningRequestBudget(1), 1, Map.of());
            var initial = new MorningTollSystem.State(TollPolicy.empty(-1), "no-toll", 0);
            manager.observe(19, List.of(row(8,800,720)), initial);
            assertTrue(manager.update(20, 0, initial).accepted());
            var context = manager.context(21, 0, initial);
            assertTrue(context.containsKey("publicEnvironment")); assertTrue(context.containsKey("roadCorridors"));
            assertTrue(context.containsKey("policyFeedback"));
            assertEquals("combined", context.get("managerFeedbackMode"));
            assertEquals("", context.get("managerMemory"));
            manager.writeFinalEvaluation(0); output.flush();
            var evaluations = JsonlTestSupport.readLines(directory.resolve("combined/manager-policy-evaluations.jsonl"))
                    .stream().map(Json::readTree).toList();
            assertEquals(2, evaluations.size());
            assertTrue(evaluations.getFirst().get("scheduledManagerUpdate").asBoolean());
            assertFalse(evaluations.getFirst().get("includedInManagerContext").asBoolean());
            assertFalse(evaluations.getLast().get("includedInManagerContext").asBoolean());
        }
    }

    private static Config config() {
        var config = ConfigUtils.createConfig(); config.scoring().setPerforming_utils_hr(.96);
        config.scoring().setMarginalUtilityOfMoney(.062); config.scoring().getModes().get("car").setMarginalUtilityOfTraveling(0);
        config.routing().setRoutingRandomness(0);
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(.1).setDisableAfter(1000));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(.7));
        return config;
    }

    private static MorningManagerEnvironment environment(Network network) {
        return new MorningManagerEnvironment(network, config(), 100, 10, true, "hybrid", 0, Map.of());
    }

    private static Map<String, Object> row(double completed, double travel, double delay) {
        var row = new LinkedHashMap<String, Object>(); row.put("link_id", "a"); row.put("start", 0); row.put("end", 3600);
        row.put("entered", completed); row.put("completed", completed); row.put("aborted", 0); row.put("unfinished", 0); row.put("partial", 0);
        row.put("total_travel_time", travel); row.put("total_delay", delay); row.put("personId", "PRIVATE_PERSON"); return row;
    }

    private static Network network() {
        var network = NetworkUtils.createNetwork(); addNodes(network, 6);
        addLink(network, "a", 0, 1, "car"); addLink(network, "b", 1, 2, "car");
        addLink(network, "c", 2, 3, "car"); addLink(network, "d", 2, 4, "car"); addLink(network, "walk-branch", 1, 5, "walk");
        return network;
    }

    private static void addNodes(Network network, int count) {
        for (int i=0; i<count; i++) network.addNode(network.getFactory().createNode(Id.createNodeId(i), new Coord(100 * i, 0)));
    }
    private static void addLink(Network network, String id, int from, int to, String mode) {
        var link = network.getFactory().createLink(Id.createLinkId(id), network.getNodes().get(Id.createNodeId(from)), network.getNodes().get(Id.createNodeId(to)));
        link.setLength(100); link.setFreespeed(10); link.setCapacity(1000); link.setNumberOfLanes(1); link.setAllowedModes(Set.of(mode));
        network.addLink(link);
    }
}
