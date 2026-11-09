package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.vehicles.Vehicle;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MorningTollObservationAuditTest {
    @TempDir Path directory;
    @Test void observationsIncludeRealZerosAndPaidEntriesOnlyOnceWithoutChangingBillingOrVisibility() throws Exception {
        var events=EventsUtils.createEventsManager(); var money=new ArrayList<PersonMoneyEvent>();
        events.addHandler((BasicEventHandler)e->{if(e instanceof PersonMoneyEvent m)money.add(m);});
        var knowledge=new MorningTravelerInformation(List.of("p"),0,1,3600);
        var system=new MorningTollSystem(network(),events,null); system.setInformation(knowledge);
        try(var out=new OutputWriter(directory,true)) {
            system.setOutput(out); system.reset(0);
            system.setPolicy(new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("road"),0,100,2)),0),1);
            var p=Id.createPersonId("p");var v=Id.createVehicleId("v");var road=Id.createLinkId("road");
            system.handleEvent(new VehicleEntersTrafficEvent(10,p,road,v,"car",.5));
            system.handleEvent(new LinkEnterEvent(10,v,road)); // duplicate origin notification
            system.handleEvent(new LinkLeaveEvent(11,v,road));
            system.handleEvent(new LinkEnterEvent(100,v,road)); // first zero after price window
            system.handleEvent(new LinkEnterEvent(100,v,road)); // duplicate zero
            system.handleEvent(new LinkLeaveEvent(101,v,road));
            system.handleEvent(new LinkEnterEvent(102,v,road)); // distinct re-entry
            assertEquals(1,money.size()); assertEquals(-2,money.getFirst().getAmount());
            assertEquals(3,((Number)system.summary().get("personalObservationEntries")).intValue());
            assertEquals(2,((Number)system.summary().get("zeroPersonalObservationEntries")).intValue());
            assertEquals(2,((Number)system.summary().get("duplicateEntriesIgnored")).intValue());
            assertEquals(0,knowledge.past("p","road",10,1).amount());
            assertTrue(knowledge.visibleForLlm("p",1,system).currentNetworkTolls().isEmpty());
            var other=Id.createPersonId("background"); var ov=Id.createVehicleId("other");
            system.handleEvent(new VehicleEntersTrafficEvent(20,other,road,ov,"car",.5));
            assertEquals(2,money.size(),"Billing remains network-wide, including outside the information scope");
            assertEquals(3,((Number)system.summary().get("personalObservationEntries")).intValue());
            system.reset(1); system.setPolicy(new TollPolicy(List.of(new TollPolicy.TollRule(road,0,108000,3)),1),2);
            system.handleEvent(new VehicleEntersTrafficEvent(10,p,road,v,"car",.5));
            assertEquals(3,knowledge.past("p","road",10,2).amount());
        }
        var observations=JsonlTestSupport.readLines(directory.resolve("toll-observations.jsonl.gz")).stream().map(Json::readTree).toList();
        var payments=JsonlTestSupport.readLines(directory.resolve("toll-payments.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(4,observations.size()); assertEquals(3,payments.size());
        assertEquals(List.of(2d,0d,0d,3d),observations.stream().map(x->x.path("amount").asDouble()).toList());
        assertEquals(List.of(1,2,3,1),observations.stream().map(x->x.path("observationSequence").asInt()).toList());
        for(var observation:observations) {
            assertEquals("p",observation.path("personId").asText());
            assertEquals("uninformed",observation.path("informationGroup").asText());
            assertEquals(3600,observation.path("timeBinSeconds").asInt());
            assertEquals((long)Math.floor(observation.path("entrySeconds").asDouble()/3600),observation.path("timeBin").asLong());
        }
        for(var payment:payments) if(payment.path("personId").asText().equals("p")) {
            var matched=observations.stream().filter(o->o.path("day").asInt()==payment.path("day").asInt()
                    && o.path("observationSequence").asInt()==payment.path("observationSequence").asInt()).toList();
            assertEquals(1,matched.size()); assertEquals(payment.at("/receipt/amount").asDouble(),matched.getFirst().path("amount").asDouble());
            assertEquals(payment.at("/receipt/time").asDouble(),matched.getFirst().path("entrySeconds").asDouble());
        }
        assertFalse(payments.stream().filter(x->x.path("personId").asText().equals("background")).findFirst().orElseThrow().has("observationSequence"));
    }
    private static Network network() {
        var n=NetworkUtils.createNetwork();var f=n.getFactory();var a=f.createNode(Id.createNodeId("a"),new Coord(0,0));
        var b=f.createNode(Id.createNodeId("b"),new Coord(100,0));n.addNode(a);n.addNode(b);
        var l=f.createLink(Id.createLinkId("road"),a,b);l.setAllowedModes(Set.of("car"));l.setLength(100);l.setFreespeed(10);
        l.setCapacity(1000);l.setNumberOfLanes(1);n.addLink(l);return n;
    }
}
