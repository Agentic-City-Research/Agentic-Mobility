package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.population.routes.RouteUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MorningManagerSafetyTest {
    @TempDir Path directory;

    @Test void sustainedWorseningWithdrawsOnNextDayAndVersionOnlyHoldsDoNotRestartGrace() throws Exception {
        var controls=MorningManagerControls.controlled();
        try(var output=new OutputWriter(directory)) {
            var safety=new MorningManagerSafety(controls,output); warmup(safety);
            var p=priced(20,.1);safety.applied(20,p,false);
            for(int day=20;day<=28;day++) {
                if(day==28) safety.applied(day,priced(day,.1),false);
                safety.observe(day,rows(200,1400,100),trips(100,100),state(p,1));
                assertNull(safety.rollback(day+1,state(p,1)),"Needs grace and three consecutive reviews");
            }
            safety.observe(29,rows(200,1400,100),trips(100,100),state(p,1));
            assertNull(safety.rollback(29,state(p,1)));
            var withdrawal=safety.rollback(30,state(p,1));assertNotNull(withdrawal);assertFalse(withdrawal.hasRules());
            safety.applied(30,withdrawal,true);assertTrue(safety.cooldown(30));assertTrue(safety.cooldown(36));assertFalse(safety.cooldown(37));
            assertNull(safety.rollback(31,state(withdrawal,2)));
        }
    }

    @Test void seriousCompletionLossWithdrawsDuringAdaptationWithoutWaitingForSevenDays() throws Exception {
        try(var output=new OutputWriter(directory)) {
            var safety=new MorningManagerSafety(MorningManagerControls.controlled(),output);warmup(safety);
            var p=priced(20,.1);safety.applied(20,p,false);
            safety.observe(20,rows(200,1000,100),trips(100,80),state(p,1));
            assertNotNull(safety.rollback(21,state(p,1)));
            assertTrue(Json.write(safety.context()).contains("serious_carCompletion_drop"));
        }
    }

    @Test void referenceNoiseToleranceAvoidsRollbackForOrdinaryFluctuations() throws Exception {
        try(var output=new OutputWriter(directory)) {
            var safety=new MorningManagerSafety(MorningManagerControls.controlled(),output);
            for(int day=13;day<20;day++) safety.observe(day,rows(200,day%2==0?500:1500,100),trips(100,100),state(TollPolicy.empty(-1),0));
            var p=priced(20,.1);safety.applied(20,p,false);
            for(int day=20;day<40;day++) {
                safety.observe(day,rows(200,1400,100),trips(100,100),state(p,1));assertNull(safety.rollback(day+1,state(p,1)));
            }
        }
    }

    @Test void localAverageDelayAloneDoesNotWithdrawAUsefulNetworkImprovement() throws Exception {
        try(var output=new OutputWriter(directory)) {
            var safety=new MorningManagerSafety(MorningManagerControls.controlled(),output);warmup(safety);
            var p=priced(20,.1);safety.applied(20,p,false);
            for(int day=20;day<40;day++) {
                safety.observe(day,rows(100,500,25),trips(100,100),state(p,1));assertNull(safety.rollback(day+1,state(p,1)));
            }
        }
    }

    @Test void lowerNetworkTotalCannotHideWorsePricedAverageWithoutMaterialNetworkBenefit() throws Exception {
        try(var output=new OutputWriter(directory)) {
            var safety=new MorningManagerSafety(MorningManagerControls.controlled(),output);warmup(safety);
            var p=priced(20,.1);safety.applied(20,p,false);
            for(int day=20;day<30;day++) safety.observe(day,rows(100,1050,25),trips(100,100),state(p,1));
            assertNotNull(safety.rollback(30,state(p,1)));
            assertTrue(Json.write(safety.context()).contains("priced_average_delay_increase_without_network_benefit"));
        }
    }

    @Test void constraintsEnforceRealActionsStepCapsCorridorCapsAndWholeHourWindows() {
        var environment=environment();var c=MorningManagerControls.controlled();var zero=TollPolicy.empty(-1);
        assertDoesNotThrow(() -> c.check(zero,priced(20,.2),MorningManagerTestResponses.decision("test"),environment));
        assertThrows(IllegalArgumentException.class,() -> c.check(zero,priced(20,1),MorningManagerTestResponses.decision("test"),environment));
        assertThrows(IllegalArgumentException.class,() -> c.check(zero,priced(20,2),MorningManagerTestResponses.decision("test"),environment));
        assertThrows(IllegalArgumentException.class,() -> c.check(zero,priced(20,.2),MorningManagerTestResponses.decision("hold"),environment));
        assertThrows(IllegalArgumentException.class,() -> c.check(zero,zero,MorningManagerTestResponses.decision("adjust"),environment));
        assertThrows(IllegalArgumentException.class,() -> c.check(priced(20,.1),priced(50,.2),MorningManagerTestResponses.decision("remove"),environment));
        assertDoesNotThrow(() -> c.check(priced(20,.2),zero,MorningManagerTestResponses.decision("remove"),environment));
        var two=new TollPolicy(List.of(rule("a",.1),rule("b",.1)),20);
        assertThrows(IllegalArgumentException.class,() -> c.check(zero,two,MorningManagerTestResponses.decision("test"),environment));
        var partial=new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("a"),10,3600,.1)),20);
        assertThrows(IllegalArgumentException.class,() -> c.check(zero,partial,MorningManagerTestResponses.decision("test"),environment));
        assertEquals(1,((List<?>)c.check(zero,priced(20,.1),MorningManagerTestResponses.decision("test"),environment).get("changes")).size());
    }

    @Test void spatialFeedbackSeparatesChargingHoursAndUsesPooledCompletedTraversals() {
        var p=priced(20,.1);var first=new ArrayList<>(rows(100,300,100));
        var outside=new LinkedHashMap<>(row("a",50,10));outside.put("start",3600);outside.put("end",7200);first.add(outside);
        var second=rows(10,300,1);
        var stats=MorningManagerMeasurements.statistics(List.of(MorningManagerMeasurements.spatial(first,p),MorningManagerMeasurements.spatial(second,p)));
        var json=Json.readTree(Json.write(stats));
        assertEquals(110.0/101,json.get("pricedChargingWindow").get("delaySecondsPerCompletedTraversal").asDouble(),1e-12);
        assertEquals(160,json.get("pricedLinksFullDay").get("totals").get("totalLinkDelaySeconds").asDouble());
        assertEquals(50,json.get("pricedOutsideWindow").get("totals").get("totalLinkDelaySeconds").asDouble());
        assertEquals(600,json.get("otherLinksFullDay").get("totals").get("totalLinkDelaySeconds").asDouble());
    }

    @Test void aggregateOdDiagnosticFindsMandatoryOriginLinksAndAvoidableIntermediateLinksWithoutPrivateData() {
        var scenario=ScenarioUtils.createScenario(ConfigUtils.createConfig());var network=scenario.getNetwork();
        var n=network();n.getNodes().values().forEach(network::addNode);n.getLinks().values().forEach(network::addLink);
        add(network,"origin",4,0);add(network,"destination",2,5);
        var f=scenario.getPopulation().getFactory();
        for(int i=0;i<2;i++) {
            var person=f.createPerson(Id.createPersonId("PRIVATE_PERSON_"+i));person.getAttributes().putAttribute("memory","PRIVATE_MEMORY");
            var plan=f.createPlan();var leg=f.createLeg("car");
            var route=RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId(i==0?"origin":"a"),Id.createLinkId("destination"));
            route.setLinkIds(route.getStartLinkId(),i==0?List.of(Id.createLinkId("a"),Id.createLinkId("b")):List.of(Id.createLinkId("b")),route.getEndLinkId());
            leg.setRoute(route);plan.addLeg(leg);person.addPlan(plan);scenario.getPopulation().addPerson(person);
        }
        var demand=MorningManagerDemand.from(scenario);
        assertEquals(.5,demand.unavoidableShare("a"));assertEquals(1,demand.unavoidableShare("origin"));
        assertEquals(.5,demand.unavoidableShare("b"));assertFalse(Json.write(demand.context()).contains("PRIVATE"));
        var settings=new MorningExperiment();new picocli.CommandLine(settings).parseArgs("--manager","--manager-profile","controlled");
        var environment=MorningManagerEnvironment.from(scenario,settings,Map.of("loadedPopulation",2,"controlledPersons",0,"travelerInformation",Map.of()));
        var limits=MorningManagerControls.controlled();limits.maxUnavoidableShare=.4;
        assertThrows(IllegalArgumentException.class,() -> limits.check(TollPolicy.empty(-1),priced(20,.1),MorningManagerTestResponses.decision("test"),environment));
        environment.captureReferenceDemand(scenario,0);
        assertTrue(Json.write(environment.context(20,TollPolicy.empty(-1))).contains("first completed simulation iteration"));
        assertThrows(IllegalStateException.class,() -> environment.captureReferenceDemand(scenario,1));
    }

    @Test void publicCarTripCountersHandleAbortNotificationsOnceAndIncludeBackgroundCars() {
        var scenario=ScenarioUtils.createScenario(ConfigUtils.createConfig());var n=network();
        n.getNodes().values().forEach(scenario.getNetwork()::addNode);n.getLinks().values().forEach(scenario.getNetwork()::addLink);
        var cohort=new MorningCohort(scenario,0,3600,1);var obs=new MorningObservations(n,cohort,0,3600);obs.reset(0);
        var p=Id.createPersonId("background");var v=org.matsim.api.core.v01.Id.createVehicleId("car");var link=Id.createLinkId("a");
        obs.handleEvent(new VehicleEntersTrafficEvent(1,p,link,v,"car",.5));
        obs.handleEvent(new PersonStuckEvent(2,p,link,"car"));
        obs.handleEvent(new VehicleAbortsEvent(2,v,link));
        assertEquals(Map.of("carTripsStarted",1,"carTripsCompleted",0,"carTripsAborted",1,"carTripsUnfinished",0),obs.publicCarTrips());
    }

    private static void warmup(MorningManagerSafety safety) {
        for(int day=13;day<20;day++) safety.observe(day,rows(200,1000,100),trips(100,100),state(TollPolicy.empty(-1),0));
    }
    private static Map<String,Object> trips(int started,int completed) {return Map.of("carTripsStarted",started,"carTripsCompleted",completed,"carTripsAborted",started-completed,"carTripsUnfinished",0);}
    private static List<Map<String,Object>> rows(double pricedDelay,double otherDelay,int pricedCompletions) {return List.of(row("a",pricedDelay,pricedCompletions),row("c",otherDelay,100));}
    private static Map<String,Object> row(String id,double delay,int completed) {
        var r=new LinkedHashMap<String,Object>();r.put("link_id",id);r.put("start",0);r.put("end",3600);r.put("entered",completed);r.put("completed",completed);
        r.put("aborted",0);r.put("unfinished",0);r.put("partial",0);r.put("total_delay",delay);r.put("total_travel_time",delay+completed*10);return r;
    }
    private static TollPolicy.TollRule rule(String id,double amount) {return new TollPolicy.TollRule(Id.createLinkId(id),0,3600,amount);}
    private static TollPolicy priced(int day,double amount) {return new TollPolicy(List.of(rule("a",amount)),day);}
    private static MorningTollSystem.State state(TollPolicy p,int version) {return new MorningTollSystem.State(p,"policy"+version,version);}
    private static MorningManagerEnvironment environment() {var n=network();return new MorningManagerEnvironment(n,ConfigUtils.createConfig(),10,10,true,"hybrid",0,Map.of());}
    private static Network network() {
        var n=NetworkUtils.createNetwork();var f=n.getFactory();
        for(int i=0;i<6;i++) n.addNode(f.createNode(Id.createNodeId(i),new Coord(i*100,0)));
        add(n,"a",0,1);add(n,"b",1,2);add(n,"c",0,3);add(n,"d",3,2);return n;
    }
    private static void add(Network n,String id,int from,int to) {
        var l=n.getFactory().createLink(Id.createLinkId(id),n.getNodes().get(Id.createNodeId(from)),n.getNodes().get(Id.createNodeId(to)));
        l.setAllowedModes(Set.of("car"));l.setLength(100);l.setFreespeed(10);l.setCapacity(1000);l.setNumberOfLanes(1);n.addLink(l);
    }
}
