package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MorningPersonalTollPriorTest {
    @TempDir Path dir;
    @Test void chargesTeachOnlyTheirOwnerAndZeroEntriesUpdateStaleMemory() {
        var network=network(); var info=new MorningTravelerInformation(List.of("alice","bob"),0,1,3600);
        var tolls=new MorningTollSystem(network,EventsUtils.createEventsManager(),null); tolls.setInformation(info);
        tolls.setPolicy(policy(0,2),1); tolls.reset(0);
        assertEstimate(info,tolls,"alice",0,0,1);
        enter(tolls,"alice",25200); assertEquals(2.0,((Number)tolls.summary().get("totalPaid")).doubleValue());
        assertEstimate(info,tolls,"bob",1,0,1); assertEstimate(info,tolls,"alice",1,2,0);
        tolls.setPolicy(policy(1,3),2); assertEstimate(info,tolls,"alice",1,2,0);
        var visible=info.visibleForLlm("alice",1,tolls); assertFalse(visible.currentAnnouncementVisible()); assertTrue(visible.currentNetworkTolls().isEmpty());
        assertEquals(2,visible.personalPayments().getFirst().amount());
        tolls.reset(1); enter(tolls,"alice",25200); assertEquals(3.0,((Number)tolls.summary().get("totalPaid")).doubleValue());
        assertEstimate(info,tolls,"alice",2,3,0);
        tolls.setPolicy(TollPolicy.empty(2),3); tolls.reset(2); enter(tolls,"alice",25200);
        assertEquals(0.0,((Number)tolls.summary().get("totalPaid")).doubleValue()); assertEstimate(info,tolls,"alice",3,0,0);
        assertEquals(0,info.visibleForLlm("alice",3,tolls).personalPayments().getFirst().amount());
    }
    @Test void informedReceivesAllNetworkRulesAndUninformedReceivesNone() {
        var tolls=new MorningTollSystem(network(),EventsUtils.createEventsManager(),null); tolls.setPolicy(policy(0,7),1);
        var yes=new MorningTravelerInformation(List.of("in"),1,1,3600); var no=new MorningTravelerInformation(List.of("out"),0,1,3600);
        assertEquals("road",yes.visibleForLlm("in",0,tolls).currentNetworkTolls().getFirst().linkId());
        assertEquals(7,yes.visibleForLlm("in",0,tolls).currentNetworkTolls().getFirst().amount());
        assertTrue(no.visibleForLlm("out",0,tolls).currentNetworkTolls().isEmpty());
        assertEquals(0,no.pricingForLlm("out",List.of("road"),List.of(25200d),1,tolls,0).knownTollSubtotal());
        var k = yes.pricingForLlm("in",List.of("other"),List.of(25200d),1,tolls,0);
        var option = new MorningRoutePolicy.Option("c0",List.of("other"),10,25210,100,1d,"static_free_flow",0,0,null,null,0d,"no-toll",0,k);
        var context = new MorningRoutePolicy.Context(0,"in/0","in","home","work",25200,null,Map.of(),Map.of(),List.of(option),List.of(),null,"",null,yes.visibleForLlm("in",0,tolls));
        assertTrue(new MorningPromptRenderer().render(context).contains("[\"road\",0.0,108000.0,7.0]"), "The complete network announcement includes roads outside the route");
    }
    @Test void sharedFreePeriodHasIdenticalInputsWithAndWithoutManager() throws Exception {
        var fixture=new MorningInformationTest(); fixture.directory=dir; Path config=fixture.writeScenario();
        var contexts=new ArrayList<Map<String,String>>();
        for(boolean manager:List.of(false,true)) {
            Path out=dir.resolve(manager?"manager":"control"); var settings=new MorningExperiment(); settings.llmConfig.apiKey="offline";
            settings.clientFactory=(cfg,usage)->(role,system,user)->{
                if(role.equals("manager-memory")) return response("{\"memory\":\"Observed aggregate traffic.\"}");
                if(role.equals("traveler_memory")) return response("{\"memory\":\"My trips.\"}");
                if(role.equals("manager")) throw new AssertionError("No policy call in free period");
                return response(MorningJointTestSupport.withDeparture("{\"routeId\":\"c0\",\"reason\":\"Available route\"}",user));
            };
            var args=new ArrayList<>(List.of("--config",config.toString(),"--output",out.toString(),"--policy","llm","--llm-agents","all","--days","2","--informed-share","0.5","--max-requests","100"));
            if(manager)args.add("--manager");
            assertEquals(0,new CommandLine(settings).execute(args.toArray(String[]::new)));
            var byKey=new TreeMap<String,String>();
            for(String row:JsonlTestSupport.readLines(out.resolve("morning/decision-contexts.jsonl.gz"))) {
                var x=Json.readTree(row); var c=Json.MAPPER.treeToValue(x,MorningRoutePolicy.Context.class);
                byKey.put(x.path("day").asText()+"/"+x.path("tripId").asText(),new MorningPromptRenderer().render(c));
            }
            assertEquals(4,byKey.size()); contexts.add(byKey);
            var manifest=Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
            assertEquals("standard",manifest.path("experimentProtocolVersion").asText());
            assertEquals(5,manifest.path("travelerReflectionInterval").asInt());
            assertTrue(manifest.path("parallelRoleReflection").asBoolean());
            assertEquals(3600,manifest.path("departureProtocol").path("earlierSeconds").asInt());
            assertTrue(manifest.path("departureProtocol").path("upperBoundExclusive").asBoolean());
        }
        assertEquals(contexts.get(0),contexts.get(1));
    }
    private static void assertEstimate(MorningTravelerInformation i,MorningTollSystem t,String person,int day,double amount,int unknown) {
        var k=i.pricingForLlm(person,List.of("road"),List.of(25200d),1,t,day);
        assertEquals("personal_history_zero_prior",k.source()); assertEquals(amount,k.knownTollSubtotal()); assertEquals(unknown,k.unknownLinks().size());
    }
    private static void enter(MorningTollSystem t,String person,double time) {
        t.handleEvent(new VehicleEntersTrafficEvent(time,Id.createPersonId(person),Id.createLinkId("road"),Id.create(person,Vehicle.class),"car",.5));
    }
    private static TollPolicy policy(int day,double amount) {return new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("road"),0,108000,amount)),day);}
    private static Network network() {var n=NetworkUtils.createNetwork();var f=n.getFactory();var a=f.createNode(Id.createNodeId("a"),new Coord(0,0));var b=f.createNode(Id.createNodeId("b"),new Coord(100,0));n.addNode(a);n.addNode(b);var l=f.createLink(Id.createLinkId("road"),a,b);l.setAllowedModes(Set.of("car"));l.setLength(100);l.setFreespeed(10);l.setCapacity(1000);l.setNumberOfLanes(1);n.addLink(l);return n;}
    private static LlmResponse response(String v) {return new LlmResponse(v,0,0,0,0);}
}
