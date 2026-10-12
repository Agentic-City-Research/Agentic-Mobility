package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MorningManagerExplorationTest {
    @TempDir Path directory;

    @Test void illegalPolicyDoesNotEnterMemoryRepairAndMalformedJsonCannotExecute() throws Exception {
        var network = network(); var calls = new AtomicInteger();
        try (var output = new OutputWriter(directory)) {
            var manager = manager(network, output, (role,s,u) -> {
                assertEquals("manager", role);
                if (calls.incrementAndGet() == 1) return response(policy(-1, "x".repeat(2100)));
                return response(policy(2, "legal corrected policy"));
            }, 2, 2);
            var result = manager.update(20, 0, initial());
            assertTrue(result.accepted()); assertEquals(2, result.policy().amountAt(Id.createLinkId("a"), 10));
        }
        try (var output = new OutputWriter(directory.resolve("bad-json"))) {
            var manager = manager(network, output, (r,s,u) -> response(policy(8, "notes") + "{}"), 1, 1);
            assertFalse(manager.update(20, 0, initial()).accepted());
        }
        try (var output = new OutputWriter(directory.resolve("duplicate"))) {
            var manager = manager(network, output, (r,s,u) -> response(policy(8, "notes").replace("\"rules\":", "\"rules\":[],\"rules\":")), 1, 1);
            assertFalse(manager.update(20, 0, initial()).accepted());
        }
        try (var output = new OutputWriter(directory.resolve("truncated"))) {
            var manager = manager(network, output, (r,s,u) -> new LlmResponse(policy(8, "notes"), 0, 0, 0, 0, true, "length", 0), 1, 1);
            assertFalse(manager.update(20, 0, initial()).accepted());
        }
    }

    @Test void policyOutputRequiresDecisionAndRejectsMemoryFields() throws Exception {
        var network=network();
        try(var output=new OutputWriter(directory)) {
            var manager=manager(network,output,(r,sys,u) -> new LlmResponse("{\"rules\":[],\"memory\":\"notes\"}",0,0,0,0),1,1);
            assertFalse(manager.update(20,0,initial()).accepted());
        }
    }

    @Test void configurableScheduleBudgetAndLastUpdateAgree() throws Exception {
        assertEquals(List.of(120,127,134), java.util.stream.IntStream.rangeClosed(100,134).filter(d -> MorningManager.updateDay(d,100,7)).boxed().toList());
        assertEquals(3, MorningManager.minimumCalls(100,134,7));
        assertEquals(69, MorningManager.minimumCalls(0,499,7));
        assertThrows(IllegalArgumentException.class, () -> MorningManager.minimumCalls(0,100,0));
        try (var output = new OutputWriter(directory)) {
            var network = network();
            var manager = new MorningManager(network,3600,environment(network),(r,s,u) -> response(policy(0,"notes")),output,new MorningRequestBudget(1),1,Map.of(),7,89);
            var schedule = Json.readTree(Json.write(manager.context(83,0,initial()))).get("executionSchedule");
            assertTrue(schedule.get("nextScheduledUpdateDay").isNull());
            assertEquals(89,schedule.get("plannedPolicyObservationThroughDay").asInt());
            assertEquals(7,schedule.get("remainingSimulationDaysIncludingToday").asInt());
            assertEquals(83,Json.readTree(Json.write(manager.context(82,0,initial()))).get("executionSchedule").get("nextScheduledUpdateDay").asInt());
        }
    }

    @Test void equivalentPriceEpisodesIgnoreVersionOrderZeroRulesAndAdjacentEqualWindows() {
        var id = Id.createLinkId("a");
        var whole = new TollPolicy(List.of(new TollPolicy.TollRule(id,0,20,2)),20);
        var split = new TollPolicy(List.of(new TollPolicy.TollRule(id,10,20,2),new TollPolicy.TollRule(id,0,10,2),new TollPolicy.TollRule(id,30,40,0)),50);
        assertEquals(whole.fingerprint(),split.fingerprint());
        assertNotEquals(whole.fingerprint(),new TollPolicy(List.of(new TollPolicy.TollRule(id,0,20,3)),20).fingerprint());
        assertEquals(TollPolicy.empty(0).fingerprint(),new TollPolicy(List.of(new TollPolicy.TollRule(id,0,20,0)),40).fingerprint());
        var feedback = new MorningManagerFeedback(10);
        for (int day=0;day<9;day++) {
            var policy = day<6 || day==8 ? TollPolicy.empty(day) : new TollPolicy(whole.rules(),day);
            feedback.observe(day,new MorningTollSystem.State(policy,"version"+day,day),daily(100+day));
        }
        var full = Json.readTree(Json.write(feedback.context(9,0)));
        var compact = Json.readTree(Json.write(feedback.managerContext(9,0)));
        assertEquals(9,full.get("policyPeriods").size()); assertEquals(3,compact.get("policyPeriods").size());
        assertEquals(6,compact.get("omittedOlderPolicyPeriods").asInt());
        assertEquals(3,compact.get("equivalentPriceEpisodes").size());
        assertEquals(6,compact.get("equivalentPriceEpisodes").get(0).get("observations").get("observedDays").asInt());
        assertEquals(2,compact.get("pricedObservedDays").asInt());
        assertFalse(compact.get("matchedNoPricingCounterfactualAvailable").asBoolean());
    }

    @Test void alternativesHaveDirectedEndpointsExcludeEntireCorridorAndLabelMissingSameBinObservations() {
        var network = network(); var environment = environment(network);
        var rows = new ArrayList<Map<String,Object>>();
        rows.add(row("a",10,500)); rows.add(row("b",10,500)); rows.add(row("c",5,60));
        var privateRow = new LinkedHashMap<>(row("d",2,20)); privateRow.put("memory","PRIVATE_PERSONAL_NOTES");
        // Only a different time bin was measured for d, so its peak-bin time must remain unknown.
        privateRow.put("start",3600); privateRow.put("end",7200); rows.add(privateRow);
        var window = List.of(Map.<String,Object>of("day",19,"link_hourly",rows));
        var policy = new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("a"),0,3600,1),new TollPolicy.TollRule(Id.createLinkId("b"),0,3600,2)),20);
        var result = Json.readTree(Json.write(new MorningManagerAlternatives(network,environment,window).context(policy)));
        var hotspot = result.get("diagnostics").get(0);
        assertEquals("0",hotspot.get("fromNode").asText()); assertEquals("2",hotspot.get("toNode").asText());
        var alternate = hotspot.get("alternativeRoutes").get(0);
        assertEquals(List.of("c","d"),Json.MAPPER.convertValue(alternate.get("linkIds"),List.class));
        assertEquals(1,alternate.get("missingCompletedObservationLinks").asInt());
        assertTrue(alternate.get("observedLinkTimeSumSeconds").isNull());
        assertTrue(alternate.get("links").get(1).get("meanObservedTravelSeconds").isNull());
        assertEquals(3,hotspot.get("corridor").get("conditionalEntryPriceSumAtBinMidpoint").asDouble());
        assertFalse(result.toString().contains("PRIVATE_PERSONAL_NOTES"));
        assertTrue(result.get("interpretation").asText().contains("not measured OD"));
    }

    @Test void compactTablesRetainAllCarLinksAndPublicMeasurementsIncludingNullMeans() throws Exception {
        var network=network();
        try (var output=new OutputWriter(directory)) {
            var manager=manager(network,output,(r,s,u) -> response(policy(0,"notes")),1,1);
            manager.observe(19,List.of(row("a",10,100),row("d",0,0)),initial());
            var context=manager.context(20,0,initial());
            var columns=(List<String>)context.get("networkColumns"); var values=(List<List<Object>>)context.get("network");
            var decoded=new ArrayList<Map<String,Object>>();
            for (var valuesRow:values) {
                var row=new LinkedHashMap<String,Object>();
                for (int i=0;i<columns.size();i++) row.put(columns.get(i),valuesRow.get(i));
                decoded.add(row);
            }
            assertEquals(environment(network).topology(),decoded);
            var totals=(List<List<Object>>)context.get("linkTrafficTotals"); var fields=(List<String>)context.get("linkTrafficTotalsColumns");
            assertEquals(2,totals.size());
            assertNull(totals.get(1).get(fields.indexOf("meanTravelSeconds")));
            assertEquals(100.0,totals.get(0).get(fields.indexOf("totalTravelSeconds")));
            assertFalse(Json.write(context).contains("PRIVATE"));
        }
    }

    private MorningManager manager(Network network,OutputWriter output,LlmClient client,int attempts,int budget) {
        // These tests isolate memory/JSON repairs; pricing limits are tested in MorningManagerSafetyTest.
        var controls=MorningManagerControls.controlled();controls.maxCorridorMinutes=1000;controls.maxStepMinutes=1000;
        return new MorningManager(network,7200,environment(network),client,output,new MorningRequestBudget(budget),attempts,Map.of(),30,99,controls);
    }
    private com.fasterxml.jackson.databind.JsonNode decisionLog() throws Exception {
        return Json.readTree(JsonlTestSupport.readLines(directory.resolve("manager-decisions.jsonl")).getLast());
    }
    private static String policy(double amount,String memory) {
        return Json.write(Map.of("rules",List.of(Map.of("link_id","a","start",0,"end",7200,"amount",amount)),
                "decision",MorningManagerTestResponses.decision(amount==0?"hold":"test")));
    }
    private static LlmResponse response(String value) { return new LlmResponse(value,0,0,0,0); }
    private static MorningTollSystem.State initial() { return new MorningTollSystem.State(TollPolicy.empty(-1),"no-toll",0); }
    private static MorningManagerEnvironment environment(Network network) {
        return new MorningManagerEnvironment(network,ConfigUtils.createConfig(),10,10,true,"hybrid",0,Map.of());
    }
    private static Map<String,Object> row(String id,int completed,double travel) {
        return Map.of("link_id",id,"start",0,"end",3600,"entered",completed,"completed",completed,"aborted",0,"unfinished",0,"partial",0,
                "total_travel_time",travel,"total_delay",Math.max(0,travel-completed*10));
    }
    private static Map<String,Object> daily(double delay) {
        return Map.of("totalLinkDelaySeconds",delay,"totalLinkTravelSeconds",delay+100,"enteredFullLinkTraversals",10,
                "completedFullLinkTraversals",10,"abortedFullLinkTraversals",0,"unfinishedFullLinkTraversals",0,"partialLinkTraversals",0);
    }
    private static Network network() {
        var network = NetworkUtils.createNetwork(); var factory = network.getFactory();
        for (int i=0;i<4;i++) network.addNode(factory.createNode(Id.createNodeId(i),new Coord(i*100,0)));
        String[] ids={"a","b","c","d","reverse"}; int[] from={0,1,0,3,2},to={1,2,3,2,0};
        for (int i=0;i<ids.length;i++) {
            var link=factory.createLink(Id.createLinkId(ids[i]),network.getNodes().get(Id.createNodeId(from[i])),network.getNodes().get(Id.createNodeId(to[i])));
            link.setLength(100); link.setFreespeed(10); link.setCapacity(1000); link.setNumberOfLanes(1); link.setAllowedModes(Set.of("car")); network.addLink(link);
        }
        return network;
    }
}
