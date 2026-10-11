package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.ConfigUtils;
import picocli.CommandLine;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MorningBasicJointTest {
    @TempDir Path directory;

    @Test void departureGridHasNineOriginalAnchoredOptionsAndClipsOnlyScheduleAndSimulationBounds() {
        assertEquals(List.of(19800d,20700d,21600d,22500d,23400d,24300d,25200d,26100d,27000d),
                MorningDepartureOptions.generate(25200,0,108000,5400,1800,900));
        assertEquals(List.of(0d,900d,1800d,2700d), MorningDepartureOptions.generate(900,0,108000,5400,1800,900));
        assertEquals(List.of(24300d,25200d,26100d), MorningDepartureOptions.generate(25200,24300,27000,5400,1800,900));
        assertThrows(IllegalArgumentException.class, () -> MorningDepartureOptions.generate(25200,0,108000,5000,1800,900));
    }

    @Test void strictJointSchemaRepairsMissingOrOutOfRangeTimeAndKeepsSingleRouteTimeChoice() throws Exception {
        var context = context(); var calls = new AtomicInteger();
        try (var output = new OutputWriter(directory)) {
            var policy = new MorningLlmPolicy((r,s,u) -> {
                assertFalse(s.contains("Only the route can be changed"));
                assertTrue(u.contains("departure-time options are still available"));
                return response(switch(calls.incrementAndGet()) {
                    case 1 -> "{\"routeId\":\"c0\",\"reason\":\"missing time\"}";
                    case 2 -> "{\"routeId\":\"c0\",\"departureSeconds\":26000,\"reason\":\"outside grid\"}";
                    default -> "{\"routeId\":\"c0\",\"departureSeconds\":23400,\"reason\":\"Earlier arrival\"}";
                });
            }, output, 3);
            assertEquals(23400d, policy.choose(context).departureSeconds()); assertEquals(3, calls.get());
            var schema = Json.readTree(Json.write(MorningOutputContract.choice(List.of("c0"),240,List.of(23400d,25200d))));
            assertEquals(2, schema.path("properties").path("departureSeconds").path("enum").size());
            assertThrows(IllegalArgumentException.class, () -> MorningRoutePolicy.validate(context, new MorningRoutePolicy.Decision("c0","missing")));
            MorningPromptRenderer.validate(MorningPromptRenderer.order(context,"reverse",0));
        }
    }

    @Test void basicAcceptsFortyRulesAndUnrestrictedPricesButRejectsFortyOneAndMisalignedWindows() throws Exception {
        var network = MorningPricingTest.network(); var controls = new MorningManagerControls();
        var env = new MorningManagerEnvironment(network,ConfigUtils.createConfig(),1,1,true,"hybrid",0,Map.of());
        var rules = new ArrayList<TollPolicy.TollRule>();
        for(int i=0;i<40;i++) rules.add(new TollPolicy.TollRule(Id.createLinkId("road"), i*1800, i*1800+900,10000.123456789));
        var policy = new TollPolicy(List.copyOf(rules),20);
        assertTrue(policy.validate(network,108000).isEmpty());
        assertTrue((Boolean)controls.check(TollPolicy.empty(-1),policy,MorningManagerTestResponses.decision("test"),env).get("pricingChanged"));
        rules.add(new TollPolicy.TollRule(Id.createLinkId("road"),72000,72900,1));
        assertThrows(IllegalArgumentException.class, () -> controls.check(TollPolicy.empty(-1),new TollPolicy(rules,20),MorningManagerTestResponses.decision("test"),env));
        assertThrows(IllegalArgumentException.class, () -> controls.check(TollPolicy.empty(-1),
                new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("road"),1,900,1)),20),MorningManagerTestResponses.decision("test"),env));
        try(var output = new OutputWriter(directory)) {
            var manager = manager(output,(r,s,u) -> response(Json.write(Map.of("rules",rules.stream().map(t -> Map.of("link_id",t.linkId().toString(),
                    "start",t.startSec(),"end",t.endSec(),"amount",t.amount())).toList(),"decision",MorningManagerTestResponses.decision("test"),"memory","notes"))),1);
            assertFalse(manager.update(20,0,initial()).accepted());
        }
    }

    @Test void managerMemoryUsesEnglishWordsWithLockedPolicyRepairAndNoAutomaticIntervention() throws Exception {
        var calls = new AtomicInteger();
        try(var output = new OutputWriter(directory)) {
            var manager = manager(output,(r,s,u) -> {
                if(calls.incrementAndGet()==1) {
                    assertTrue(s.contains("at most 1000 words")); assertFalse(s.contains("routing-minute caps"));
                    return response(Json.write(Map.of("rules",List.of(),"decision",MorningManagerTestResponses.decision("hold"),"memory","word ".repeat(1001))));
                }
                assertEquals("manager-memory",r); assertTrue(Json.readTree(u).has("validatedPolicyFingerprint"));
                return response(Json.write(Map.of("memory","word ".repeat(1000))));
            },2);
            assertTrue(manager.update(20,0,initial()).accepted()); assertEquals(2,calls.get());
            var context=Json.readTree(Json.write(manager.context(21,0,initial())));
            assertEquals(1000,MorningManager.wordCount(context.get("managerMemory").asText()));
            assertFalse(context.has("safetyMonitoring")); assertFalse(context.has("routeAlternatives")); assertFalse(context.has("chargingSuitability"));
            assertFalse(context.get("publicEnvironment").has("routingCostScale"));
            assertFalse(context.get("executionSchedule").get("automaticWithdrawalEffectiveNextIteration").asBoolean());
            assertNull(manager.automaticRollback(22,initial())); assertFalse(manager.inCooldown(22));
            manager.observe(21,List.of(),initial());
            assertThrows(IllegalArgumentException.class, () -> manager.context(21,0,initial()));
        }
    }

    @Test void uninformedLlmCannotRetrievePersistentExactPaymentsWhileNativeKnowledgeRemainsSeparate() {
        var knowledge=new MorningTravelerInformation(List.of("p"),0,1,3600);
        knowledge.observe(0,"p",new MorningTollSystem.Receipt("road",25200,2.125,"policy0",1));
        assertEquals(1,knowledge.personalPayments("p").size());
        assertTrue(knowledge.visibleForLlm("p").personalPayments().isEmpty());
        assertFalse(knowledge.visibleForLlm("p").currentAnnouncementVisible());
    }

    @Test void jointQsimExecutesEarlyAndLateChoicesBillsActualWindowsAndMonitorsWithdrawnRoad() throws Exception {
        var fixture = new MorningPricingTest(); fixture.directory=directory;
        Path config=fixture.writeScenario(), out=directory.resolve("joint-run");
        var settings=new MorningExperiment(); settings.llmConfig.apiKey="offline-only";
        var managerCalls=new AtomicInteger(); var travelerCalls=new AtomicInteger();
        settings.clientFactory=(c,usage) -> (role,system,user) -> {
            if(role.equals("manager")) {
                int call=managerCalls.incrementAndGet(); var input=Json.readTree(user);
                assertEquals(call==1?20:27,input.get("day").asInt());
                assertTrue(input.get("publicEnvironment").get("travelerResponse").get("departureTimeChoiceEnabled").asBoolean());
                assertFalse(user.contains("private-profile-marker"));
                var rules=call==1?List.of(Map.of("link_id","road","start",19800,"end",20700,"amount",.123456789)):List.of();
                return response(Json.write(Map.of("rules",rules,"decision",MorningManagerTestResponses.decision(call==1?"test":"remove"),"memory","Observe executed traffic, revise after the scheduled review.")));
            }
            if(role.equals("traveler_memory")) return response("{\"memory\":\"My departure and road choices affect arrival and payments.\"}");
            int day=travelerCalls.getAndIncrement();
            var grid=java.util.regex.Pattern.compile("Allowed departureSeconds: (\\[[^\\n]+?\\])").matcher(user); assertTrue(grid.find());
            var times=Json.readTree(grid.group(1)); assertEquals(9,times.size());
            assertFalse(user.contains("YOUR OWN ACTUAL PAYMENT OBSERVATIONS BEFORE TODAY"));
            return response(Json.write(Map.of("routeId","c0","departureSeconds",day==1?27000:day==2?25200:19800,"reason","Offline joint action execution fixture")));
        };
        assertEquals(0,new CommandLine(settings).execute("--config",config.toString(),"--output",out.toString(),"--policy","llm",
                "--llm-agents","all","--manager","--manager-update-interval","7","--days","29","--informed-share","0","--max-requests","60"));
        assertEquals(29,travelerCalls.get()); assertEquals(2,managerCalls.get());
        var trips=JsonlTestSupport.readLines(out.resolve("morning/trips.jsonl.gz")).stream().map(Json::readTree).toList();
        for(int day=0;day<29;day++) {
            var t=trips.get(day); double expected=day==1?27000:day==2?25200:19800;
            assertEquals(25200,t.get("plannedDepartureSeconds").asDouble());
            assertEquals(expected,t.get("selectedDepartureSeconds").asDouble());
            assertEquals(expected,t.get("actualDepartureSeconds").asDouble(),1);
            assertEquals(day>=20&&day<27?.123456789:0,t.get("tollPaid").asDouble());
        }
        var contexts=JsonlTestSupport.readLines(out.resolve("morning/decision-contexts.jsonl.gz")).stream().map(Json::readTree).toList();
        for(var c:contexts) {
            assertTrue(c.get("information").get("personalPayments").isEmpty());
            assertTrue(c.get("candidates").get(0).get("estimatedToll").isNull());
        }
        var evaluations=JsonlTestSupport.readLines(out.resolve("morning/manager-policy-evaluations.jsonl.gz")).stream().map(Json::readTree).toList();
        var phases=evaluations.getLast().get("feedback").get("trackedChargingWindows").get(0).get("phases");
        assertEquals(List.of("before_first_pricing","during_pricing","after_withdrawal"),
                java.util.stream.StreamSupport.stream(phases.spliterator(),false).map(p -> p.get("stage").asText()).toList());
        assertEquals(2,phases.get(2).get("observedDays").asInt());
        assertTrue(phases.get(2).get("observations").get("pricedChargingWindow").get("totals").get("enteredFullLinkTraversals").asDouble()>0);
        var manifest=Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
        assertFalse(manifest.get("plannedTimesFixed").asBoolean()); assertEquals(900,manifest.get("linkMeasurementBinSeconds").asInt());
        assertEquals(0, new CommandLine(new MorningReplay()).execute("--input",out.resolve("morning/decision-contexts.jsonl.gz").toString(),
                "--output",directory.resolve("joint-replay").toString(),"--mock","--limit","3","--orders","original,reverse"));
    }

    private MorningManager manager(OutputWriter output,LlmClient client,int attempts) {
        var n=MorningPricingTest.network(); var env=new MorningManagerEnvironment(n,ConfigUtils.createConfig(),1,1,true,"hybrid",0,Map.of());
        return new MorningManager(n,108000,env,client,output,new MorningRequestBudget(5),attempts,Map.of());
    }

    @Test void enrichedScenarioJointShiftsSurviveNativeReroutingAndAccessEgressWalking() throws Exception {
        var settings = new MorningExperiment(); settings.llmConfig.apiKey="offline-only";
        var calls = new AtomicInteger();
        settings.clientFactory=(c,usage) -> (role,system,user) -> {
            if(role.equals("traveler_memory")) return response("{\"memory\":\"I remember actual departures, arrivals and delays.\"}");
            String route=MorningJointTestSupport.firstRoute(user);
            var grid=java.util.regex.Pattern.compile("Allowed departureSeconds: (\\[[^\\n]+?\\])").matcher(user); assertTrue(grid.find());
            var times=Json.readTree(grid.group(1)); int index=calls.getAndIncrement()%2==0?0:times.size()-1;
            return response(Json.write(Map.of("routeId",route,"departureSeconds",times.get(index).asDouble(),"reason","Offline early or late choice")));
        };
        var out=directory.resolve("enriched-joint");
        assertEquals(0,new CommandLine(settings).execute("--policy","llm","--llm-agents","2","--demand","100",
                "--sample-persons","40","--days","3","--concurrency","1","--seed","4711","--output",out.toString()));
        var checks=JsonlTestSupport.readLines(out.resolve("morning/executed-route-checks.jsonl.gz")).stream().map(Json::readTree).toList();
        assertTrue(checks.stream().allMatch(r -> r.get("planTimeMatchesChoice").asBoolean() && r.get("followsSelectedRoutePrefix").asBoolean()));
        assertTrue(checks.stream().anyMatch(r -> r.get("selectedDepartureSeconds").asDouble() < r.get("originalPlannedDepartureSeconds").asDouble()));
        assertTrue(checks.stream().anyMatch(r -> r.get("selectedDepartureSeconds").asDouble() > r.get("originalPlannedDepartureSeconds").asDouble()));
        assertTrue(checks.stream().filter(r -> !r.get("actualDepartureSeconds").isNull()).allMatch(r ->
                r.get("actualDepartureSeconds").asDouble()+1>=r.get("selectedDepartureSeconds").asDouble()));
        var metrics=JsonlTestSupport.readLines(out.resolve("morning/metrics.jsonl.gz")).stream().map(Json::readTree).toList();
        assertTrue(metrics.stream().allMatch(r -> r.get("fullDayLinkTraffic").get("completedFullLinkTraversals").asDouble()>0));
    }
    private static MorningTollSystem.State initial() { return new MorningTollSystem.State(TollPolicy.empty(-1),"initial",0); }
    private static LlmResponse response(String text) { return new LlmResponse(text,0,0,0,0); }
    private static MorningRoutePolicy.Context context() {
        var route=new MorningRoutePolicy.Option("c0",List.of("road"),900,26100,1000,0,"static_free_flow",0,0);
        var earlier=new MorningRoutePolicy.Option("c0",List.of("road"),900,24300,1000,0,"static_free_flow",0,0);
        return new MorningRoutePolicy.Context(1,"p/0","p","home","work",25200,32400d,Map.of(),Map.of(),List.of(route),List.of(),
                null,"",null,null,List.of(new MorningRoutePolicy.DepartureOption(23400,List.of(earlier)),new MorningRoutePolicy.DepartureOption(25200,List.of(route))));
    }
}
