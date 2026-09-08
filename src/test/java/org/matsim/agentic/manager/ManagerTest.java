package org.matsim.agentic.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.vehicles.Vehicle;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagerTest {
    @TempDir Path output;
    static ManagerSettings settings(String mode,String provider) {
        return new ManagerSettings(mode,provider,List.of("tolled"),100,300,100,
                List.of(0.0,2.0,4.0),1,1,0,2,30,10,1,3);
    }
    static Scenario scenario() {
        var s=ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var n=s.getNetwork();
        var a=NetworkUtils.createAndAddNode(n,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(n,Id.createNodeId("b"),new Coord(100,0));
        NetworkUtils.createAndAddLink(n,Id.createLinkId("tolled"),a,b,100,10,1000,1);
        var p=s.getPopulation().getFactory().createPerson(Id.createPersonId("p"));
        s.getPopulation().addPerson(p);
        return s;
    }
    @Test void boundariesAndPermissions() {
        var s=settings("active","rule");
        var p=new TollPolicy(1,1,Map.of("tolled",List.of(1,2)));
        assertEquals(0,p.amount(s,"tolled",99)); assertEquals(2,p.amount(s,"tolled",100));
        assertEquals(4,p.amount(s,"tolled",200)); assertEquals(0,p.amount(s,"tolled",300));
        assertEquals(0,p.amount(s,"elsewhere",150));
        assertThrows(IllegalArgumentException.class,()->p.validate(s,TollPolicy.zero(s),false));
        assertDoesNotThrow(()->p.validate(s,TollPolicy.zero(s),true));
        assertThrows(IllegalArgumentException.class,()->new TollPolicy(1,1,Map.of("fake",List.of(0,0)))
                .validate(s,TollPolicy.zero(s),false));
        assertThrows(IllegalArgumentException.class,()->new TollPolicy(1,1,Map.of("tolled",List.of(-1,0)))
                .validate(s,TollPolicy.zero(s),false));
        assertThrows(IllegalArgumentException.class,()->new TollPolicy(1,1,Map.of("tolled",List.of(1)))
                .validate(s,TollPolicy.zero(s),false));
        assertThrows(UnsupportedOperationException.class,()->p.levels().get("tolled").set(0,0));
        assertThrows(IllegalArgumentException.class,()->s.validate(ScenarioUtils.createScenario(ConfigUtils.createConfig()).getNetwork()));
    }
    @Test void ruleUsesEvidenceAndHysteresis() throws Exception {
        var s=settings("active","rule"); var p=new TollPolicy(1,1,Map.of("tolled",List.of(1,1)));
        var obs=new ManagerDecisionProvider.Observation(0,0,0,0,0,0,0,0,List.of(
                new ManagerDecisionProvider.Cell("tolled",0,2,31.0),
                new ManagerDecisionProvider.Cell("tolled",1,2,9.0)));
        assertEquals(List.of(2,0),ManagerDecisionProvider.rule().decide(s,p,obs,"",List.of()).levels().get("tolled"));
        obs=new ManagerDecisionProvider.Observation(0,0,0,0,0,0,0,0,List.of(
                new ManagerDecisionProvider.Cell("tolled",0,1,100.0),
                new ManagerDecisionProvider.Cell("tolled",1,0,null)));
        assertEquals(p.levels(),ManagerDecisionProvider.rule().decide(s,p,obs,"",List.of()).levels());
    }
    @Test void activeChargesOnceScoresMoneyAndAddsRoutingCost() throws Exception {
        var s=settings("active","fixed"); var scenario=scenario(); var events=EventsUtils.createEventsManager();
        var money=new ArrayList<PersonMoneyEvent>();
        events.addHandler((PersonMoneyEventHandler)money::add);
        var runtime=new ManagerRuntime(s,scenario,events,ManagerDecisionProvider.fixed());
        runtime.start(output); runtime.reset(0); runtime.iterationEnded(0,3); runtime.reset(1);
        assertEquals(1,runtime.policy().effectiveIteration());
        var person=Id.createPersonId("p"); var vehicle=Id.createVehicleId("v"); var link=Id.createLinkId("tolled");
        runtime.handleEvent(new VehicleEntersTrafficEvent(100,person,link,vehicle,"car",1));
        assertTrue(money.isEmpty(),"origin link is exempt");
        runtime.handleEvent(new LinkEnterEvent(110,vehicle,link));
        runtime.handleEvent(new LinkEnterEvent(110,vehicle,link));
        runtime.handleEvent(new LinkLeaveEvent(150,vehicle,link));
        runtime.handleEvent(new VehicleLeavesTrafficEvent(160,person,link,vehicle,"car",1));
        assertEquals(1,money.size()); assertEquals(-2,money.getFirst().getAmount());
        assertEquals(30,runtime.observation(1).cells().getFirst().meanDelaySeconds());
        assertEquals(60,runtime.observation(1).carTravelSeconds());
        assertTrue(runtime.travelerContext("p").contains("\"last_actual_payment\":2.0"));
        runtime.handleEvent(new VehicleEntersTrafficEvent(100,person,link,vehicle,"bike",1));
        runtime.handleEvent(new LinkEnterEvent(120,vehicle,link)); assertEquals(1,money.size());
        TravelDisutility base=new TravelDisutility() {
            public double getLinkTravelDisutility(Link l,double t,Person p,Vehicle v) {return 5;}
            public double getLinkMinimumTravelDisutility(Link l) {return 3;}
        };
        var cost=runtime.withToll(base); var networkLink=scenario.getNetwork().getLinks().get(link);
        assertEquals(5+2*scenario.getConfig().scoring().getMarginalUtilityOfMoney(),
                cost.getLinkTravelDisutility(networkLink,110,scenario.getPopulation().getPersons().get(person),null));
        assertEquals(5,cost.getLinkTravelDisutility(networkLink,300,null,null));
        assertEquals(3,cost.getLinkMinimumTravelDisutility(networkLink)); runtime.close();
    }
    @Test void shadowAndInvalidProposalsNeverChangeActualPolicy() throws Exception {
        var scenario=scenario();
        var shadow=new ManagerRuntime(settings("shadow","fixed"),scenario,EventsUtils.createEventsManager(),ManagerDecisionProvider.fixed());
        shadow.start(output); shadow.reset(0); shadow.iterationEnded(0,3);
        assertEquals(0,shadow.policy().version()); assertEquals("",shadow.travelerContext("p")); shadow.close();
        var bad=new ManagerRuntime(settings("active","llm"),scenario,EventsUtils.createEventsManager(),
                (s,p,o,sc,m)->new ManagerDecisionProvider.Decision(Map.of("not-allowed",List.of(1,1)),"invalid"));
        var second=Files.createDirectory(output.resolve("bad")); bad.start(second); bad.reset(0); bad.iterationEnded(0,3);
        assertEquals(0,bad.policy().version()); bad.close();
        assertTrue(Files.readString(second.resolve("manager-decisions.jsonl")).contains("KEEP"));
    }
    @Test void llmParserRejectsNonIntegerAndMissingValues() throws Exception {
        assertEquals(List.of(0,1),LlmManagerProvider.parse("{\"levels\":{\"tolled\":[0,1]},\"reason\":\"test\"}").levels().get("tolled"));
        assertThrows(Exception.class,()->LlmManagerProvider.parse("{\"levels\":{\"tolled\":[0,1.2]},\"reason\":\"test\"}"));
        assertThrows(Exception.class,()->LlmManagerProvider.parse("{}"));
        assertThrows(Exception.class,()->LlmManagerProvider.parse("{\"levels\":{\"tolled\":[0],\"tolled\":[1]},\"reason\":\"test\"}"));
        assertThrows(Exception.class,()->LlmManagerProvider.parse("{\"levels\":{},\"reason\":\"test\"} {}"));
    }
    @Test void schedulingFreezesLastIterationsAndBoundsHistory() throws Exception {
        var s=new ManagerSettings("active","fixed",List.of("tolled"),100,300,100,
                List.of(0.0,2.0),2,2,2,1,30,10,1,1);
        var calls=new ArrayList<Integer>();
        var runtime=new ManagerRuntime(s,scenario(),EventsUtils.createEventsManager(),(settings,p,o,scene,m)->{
            calls.add(o.lastIteration()); assertTrue(m.size()<=1);
            return ManagerDecisionProvider.fixed().decide(settings,p,o,scene,m);
        });
        runtime.start(output);
        for(int it=0;it<=7;it++) {runtime.reset(it);runtime.iterationEnded(it,7);}
        assertEquals(List.of(1,3),calls,"iterations 6 and 7 are frozen, no decision after 5");
        assertEquals(2,runtime.policy().effectiveIteration()); runtime.close();
        assertEquals(2,Files.readAllLines(output.resolve("toll-policies.jsonl")).size(),"unchanged policy creates no new regime");
    }
    @Test void noEvidenceAndProviderExceptionsKeepPolicy() throws Exception {
        var runtime=new ManagerRuntime(settings("active","llm"),scenario(),EventsUtils.createEventsManager(),
                (s,p,o,scene,m)->new ManagerDecisionProvider.Decision(Map.of("tolled",List.of(1,1)),"unsupported change"));
        runtime.start(output); runtime.reset(0); runtime.iterationEnded(0,3);
        assertEquals(0,runtime.policy().version()); runtime.close();
        var second=Files.createDirectory(output.resolve("failure"));
        runtime=new ManagerRuntime(settings("active","llm"),scenario(),EventsUtils.createEventsManager(),
                (s,p,o,scene,m)->{throw new java.io.IOException("remote failure");});
        runtime.start(second); runtime.reset(0); runtime.iterationEnded(0,3);
        assertEquals(0,runtime.policy().version()); runtime.close();
        assertTrue(Files.readString(second.resolve("manager-decisions.jsonl")).contains("IOException"));
    }
    @Test void routerChoosesAlternativeAfterTollPublication() {
        var scenario=scenario(); var n=scenario.getNetwork();
        var a=n.getNodes().get(Id.createNodeId("a")); var b=n.getNodes().get(Id.createNodeId("b"));
        var c=NetworkUtils.createAndAddNode(n,Id.createNodeId("c"),new Coord(50,10));
        NetworkUtils.createAndAddLink(n,Id.createLinkId("alternative-1"),a,c,50.5,10,1000,1);
        NetworkUtils.createAndAddLink(n,Id.createLinkId("alternative-2"),c,b,50.5,10,1000,1);
        scenario.getConfig().scoring().setMarginalUtilityOfMoney(1);
        var runtime=new ManagerRuntime(settings("active","fixed"),scenario,EventsUtils.createEventsManager(),ManagerDecisionProvider.fixed());
        TravelDisutility base=new TravelDisutility() {
            public double getLinkTravelDisutility(Link l,double t,Person p,Vehicle v) {return l.getLength();}
            public double getLinkMinimumTravelDisutility(Link l) {return l.getLength();}
        };
        var router=new org.matsim.core.router.DijkstraFactory().createPathCalculator(n,runtime.withToll(base),
                (l,t,p,v)->l.getLength()/l.getFreespeed());
        var person=scenario.getPopulation().getPersons().get(Id.createPersonId("p"));
        assertEquals(List.of("tolled"),router.calcLeastCostPath(a,b,150,person,null).links.stream().map(l->l.getId().toString()).toList());
        runtime.start(output); runtime.reset(0); runtime.iterationEnded(0,3);
        assertEquals(List.of("alternative-1","alternative-2"),router.calcLeastCostPath(a,b,150,person,null).links.stream().map(l->l.getId().toString()).toList());
        runtime.close();
    }
}
