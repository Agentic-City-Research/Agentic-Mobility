package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MorningManagerDailyMemoryTest {
    @TempDir Path directory;

    @Test void freeDaysReflectAtDayEndAndPolicyCallReadsOnlyPreviousCompletedDays() throws Exception {
        for(var controls:List.of(new MorningManagerControls(),MorningManagerControls.controlled())) {
            var memoryCalls=new AtomicInteger(); var policyCalls=new AtomicInteger();
            try(var output=new OutputWriter(directory.resolve(controls.profile))) {
                var manager=manager(output,(role,system,user) -> {
                    var context=Json.readTree(user);
                    if(role.equals("manager-memory")) {
                        int day=memoryCalls.getAndIncrement(); assertEquals(day,context.get("day").asInt());
                        assertEquals(day,context.get("observationsThroughDay").asInt());
                        assertFalse(context.has("network")); assertFalse(user.contains("PRIVATE_MARKER"));
                        assertTrue(system.contains("Target 400-600 words"));
                        return response(Map.of("memory","Observed completed day "+day));
                    }
                    policyCalls.incrementAndGet(); assertEquals(19,context.get("observationsThroughDay").asInt());
                    assertEquals("Observed completed day 19",context.get("managerMemory").asText());
                    assertEquals(19,context.at("/managerMemoryMetadata/writtenAtDay").asInt());
                    return response(Map.of("rules",List.of(),"decision",MorningManagerTestResponses.decision("hold")));
                },25,2,controls);
                for(int day=0;day<20;day++) {
                    observe(manager,day,initial(),10); reflect(manager,day,0,initial());
                }
                assertTrue(manager.update(20,0,initial()).accepted());
                assertEquals(20,memoryCalls.get()); assertEquals(1,policyCalls.get());
                assertEquals("Observed completed day 19",manager.context(20,0,initial()).get("managerMemory"));
                output.flush();
                assertEquals(20,JsonlTestSupport.readLines(directory.resolve(controls.profile).resolve("manager-memory-updates.jsonl")).size());
            }
        }
    }

    @Test void failedReflectionPreservesMemoryAndNextInputIncludesMissingDaysAcrossPolicyUpdate() throws Exception {
        var calls=new AtomicInteger();
        try(var output=new OutputWriter(directory)) {
            var manager=manager(output,(r,s,u) -> {
                if(r.equals("manager")) return response(Map.of("rules",List.of(rule(2)),"decision",MorningManagerTestResponses.decision("test")));
                if(calls.incrementAndGet()==2) throw new LlmException("offline reflection failure");
                return response(Map.of("memory","Successful observation "+Json.readTree(u).get("day").asInt()));
            },8,1,new MorningManagerControls());
            observe(manager,18,initial(),10); reflect(manager,18,0,initial());
            observe(manager,19,initial(),20); reflect(manager,19,0,initial());
            assertEquals("Successful observation 18",manager.context(20,0,initial()).get("managerMemory"));
            var accepted=manager.update(20,0,initial());
            var state=new MorningTollSystem.State(accepted.policy(),"v1",1);
            observe(manager,20,state,30);
            var context=manager.memoryContext(20,0,state); var json=Json.readTree(Json.write(context));
            assertEquals(2,json.at("/feedback/newObservations/observedDays").asInt());
            assertEquals(19,json.at("/feedback/newObservations/firstObservedDay").asInt());
            assertEquals(50,json.at("/feedback/newObservations/totals/0").asDouble());
            assertEquals(2,json.at("/executedPolicy/policy/rules/0/amount").asDouble());
            manager.commitMemory(20,manager.reflect(context));
            assertEquals("Successful observation 20",manager.context(21,0,state).get("managerMemory"));
            output.flush(); var log=Json.readTree(JsonlTestSupport.readLines(directory.resolve("manager-memory-fallbacks.jsonl")).getFirst());
            assertFalse(log.get("completeReplacement").asBoolean()); assertEquals(18,log.get("memoryWrittenAtDay").asInt());
        }
    }

    @Test void holdVersionChangesPreserveEpisodeAndPublicRevenueAndCompletionFacts() throws Exception {
        try(var output=new OutputWriter(directory)) {
            var manager=manager(output,(r,s,u) -> response(Map.of("memory","Facts retained")),10,1,new MorningManagerControls());
            for(int day=100;day<104;day++) {
                var state=new MorningTollSystem.State(new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("road"),0,3600,2)),day),"v"+day,day);
                observe(manager,day,state,day-100+10);
            }
            var state=new MorningTollSystem.State(new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("road"),0,3600,2)),103),"v103",103);
            var json=Json.readTree(Json.write(manager.memoryContext(103,100,state)));
            assertEquals(4,json.get("simulationDay").asInt());
            assertEquals(4,json.at("/feedback/currentPriceEpisode/observations/observedDays").asInt());
            assertEquals(100,json.at("/feedback/currentPriceEpisode/firstPolicyVersion").asInt());
            assertEquals(103,json.at("/feedback/currentPriceEpisode/lastPolicyVersion").asInt());
            assertEquals(8,json.at("/feedback/currentPriceEpisode/observations/tollRevenue/total").asDouble());
            assertEquals(1,json.at("/feedback/today/completionRateAmongStartedCarTrips").asDouble());
            assertFalse(Json.write(json).contains("PRIVATE_MARKER"));
        }
    }

    @Test void reflectionRepairsOverlongNotesButRejectsPolicyCommandsAndTruncatedJson() throws Exception {
        var replies=new ArrayDeque<>(List.of(
                response(Map.of("memory","word ".repeat(1001))),
                response(Map.of("memory","Short evidence, no padding.")),
                response(Map.of("memory","bad","rules",List.of(rule(99)))),
                new LlmResponse("{\"memory\":\"truncated\"}",0,0,0,0,true,"length",0),
                new LlmResponse("{\"memory\":\"one\",\"memory\":\"two\"}",0,0,0,0),
                new LlmResponse("{\"memory\":\"one\"}{}",0,0,0,0)));
        try(var output=new OutputWriter(directory)) {
            var manager=manager(output,(r,s,u) -> replies.remove(),6,2,new MorningManagerControls());
            observe(manager,0,initial(),10); reflect(manager,0,0,initial());
            observe(manager,1,initial(),20); reflect(manager,1,0,initial());
            observe(manager,2,initial(),30); reflect(manager,2,0,initial());
            var context=manager.context(3,0,initial());
            assertEquals("Short evidence, no padding.",context.get("managerMemory"));
            assertEquals(6,manager.attemptedCalls());
            assertEquals(0,initial().policy().rules().size());
            assertThrows(IllegalArgumentException.class,() -> manager.memoryContext(2,0,initial()));
            assertThrows(IllegalArgumentException.class,() -> manager.memoryContext(3,0,initial()));
        }
    }

    @Test void exhaustedBudgetRetainsMemoryWithoutDispatchAndWorkerDoesNotCommitLiveState() throws Exception {
        var calls=new AtomicInteger();
        try(var output=new OutputWriter(directory)) {
            var manager=manager(output,(r,s,u) -> { calls.incrementAndGet(); return response(Map.of("memory","Latest notes")); },1,1,new MorningManagerControls());
            observe(manager,0,initial(),10); var context=manager.memoryContext(0,0,initial());
            var update=manager.reflect(context);
            assertEquals("",manager.context(1,0,initial()).get("managerMemory"));
            manager.commitMemory(0,update);
            observe(manager,1,initial(),20); reflect(manager,1,0,initial());
            assertEquals(1,calls.get()); assertEquals("Latest notes",manager.context(2,0,initial()).get("managerMemory"));
        }
    }

    @Test void timedOutReflectionDoesNotCommitLateNotesAndTheNextDayRepairsTheGap() throws Exception {
        var fixture=new MorningPricingTest(); fixture.directory=directory;
        var settings=new MorningExperiment(); settings.llmConfig.apiKey="offline-only";
        var calls=new AtomicInteger();
        settings.clientFactory=(config,usage) -> (role,system,user) -> {
            assertEquals("manager-memory",role); assertEquals(2048,config.maxOutputTokens);
            int day=Json.readTree(user).get("day").asInt(); calls.incrementAndGet();
            if(day==0) {
                try { Thread.sleep(2000); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return response(Map.of("memory","Late day zero response must not become memory"));
            }
            assertEquals(2,Json.readTree(user).at("/feedback/newObservations/observedDays").asInt());
            assertEquals("",Json.readTree(user).get("managerMemory").asText());
            return response(Map.of("memory","Day one includes both completed days"));
        };
        var out=directory.resolve("timeout-run");
        assertEquals(0,new picocli.CommandLine(settings).execute("--config",fixture.writeScenario().toString(),"--output",out.toString(),
                "--manager","--llm-agents","0","--days","2","--llm-stall-seconds","1","--max-requests","2"));
        var updates=JsonlTestSupport.readLines(out.resolve("morning/manager-memory-updates.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(2,calls.get()); assertEquals(2,updates.size());
        assertEquals("retained_previous",updates.getFirst().get("memoryStatus").asText());
        assertEquals("Day one includes both completed days",updates.getLast().get("memory").asText());
        var manifest=Json.readTree(java.nio.file.Files.readString(out.resolve("morning/manifest.json")));
        assertEquals(2,manifest.get("minimumManagerMemoryCalls").asInt()); assertEquals(0,manifest.get("minimumManagerCalls").asInt());
        assertEquals(2048,manifest.at("/managerMemorySettings/generation/max_tokens").asInt());
        assertEquals(8192,manifest.at("/managerSettings/generation/max_tokens").asInt());
        var usage=Json.readTree(java.nio.file.Files.readString(out.resolve("morning/api-usage.json")));
        assertEquals(2,usage.get("attemptedManagerMemoryCalls").asInt()); assertEquals(0,usage.get("attemptedManagerPolicyCalls").asInt());
    }

    @Test void requestCountsIncludeFreeDaysAndNonzeroFirstIteration() {
        assertEquals(100,MorningManager.minimumMemoryCalls(0,99));
        assertEquals(35,MorningManager.minimumMemoryCalls(100,134));
        assertEquals(3,MorningManager.minimumCalls(100,134,7));
        assertEquals(0,MorningManager.minimumMemoryCalls(5,4));
    }

    private MorningManager manager(OutputWriter output,LlmClient client,int budget,int attempts,MorningManagerControls controls) {
        var network=NetworkUtils.createNetwork(); var factory=network.getFactory();
        var a=factory.createNode(Id.createNodeId("a"),new Coord(0,0)); var b=factory.createNode(Id.createNodeId("b"),new Coord(100,0));
        network.addNode(a); network.addNode(b); var link=factory.createLink(Id.createLinkId("road"),a,b);
        link.setLength(100); link.setFreespeed(10); link.setCapacity(1000); link.setNumberOfLanes(1); link.setAllowedModes(Set.of("car")); network.addLink(link);
        var env=new MorningManagerEnvironment(network,ConfigUtils.createConfig(),10,10,true,"hybrid",0,Map.of());
        return new MorningManager(network,3600,env,client,output,new MorningRequestBudget(budget),attempts,Map.of(),30,199,controls);
    }
    private void observe(MorningManager manager,int day,MorningTollSystem.State state,double delay) {
        var row=new LinkedHashMap<String,Object>(Map.of("link_id","road","start",0,"end",3600,"entered",1,"completed",1,
                "aborted",0,"unfinished",0,"partial",0,"total_travel_time",delay+10,"total_delay",delay));
        row.put("personId","PRIVATE_MARKER"); row.put("memory","PRIVATE_MARKER");
        manager.observe(day,List.of(row),state,Map.of("carTripsStarted",1,"carTripsCompleted",1,"carTripsAborted",0,"carTripsUnfinished",0),state.policy().amountAt(Id.createLinkId("road"),1));
    }
    private void reflect(MorningManager manager,int day,int first,MorningTollSystem.State state) {
        var context=manager.memoryContext(day,first,state); manager.commitMemory(day,manager.reflect(context));
    }
    private static Map<String,Object> rule(double amount) { return Map.of("link_id","road","start",0,"end",3600,"amount",amount); }
    private static MorningTollSystem.State initial() { return new MorningTollSystem.State(TollPolicy.empty(-1),"no-toll",0); }
    private static LlmResponse response(Map<String,?> value) { return new LlmResponse(Json.write(value),0,0,0,0); }
}
