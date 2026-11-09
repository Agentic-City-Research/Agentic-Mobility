package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.MorningJointTestSupport.tableRows;

class MorningPromptCompactionTest {
    @Test void jointTableRetainsEveryOrderedCombinationExactNumbersAndVisibleRulesOnce() {
        var c=context(10,true); var renderer=new MorningPromptRenderer(); String user=renderer.render(c);
        assertEquals(1,user.split("AVAILABLE ACTIONS FOR THIS TRIP:",-1).length-1);
        assertEquals(1,user.split("Allowed departureSeconds:",-1).length-1);
        var rows=tableRows(user,"JOINT TIME AND ROUTE OPTIONS");
        assertEquals(c.departureOptions().size()*c.candidates().size(),rows.size());
        int index=0;
        for(var departure:c.departureOptions()) for(var option:departure.routes()) {
            var row=rows.get(index++);
            assertEquals(departure.departureSeconds(),row.get(0).asDouble());
            assertEquals(option.routeId(),row.get(1).asText());
            assertEquals(option.expectedCarSeconds(),row.get(2).asDouble());
            assertEquals(option.expectedArrivalSeconds(),row.get(3).asDouble());
            assertEquals(option.estimatedToll(),row.get(4).asDouble());
            assertEquals(option.estimatedMonetaryCost(),row.get(5).asDouble());
            assertEquals(6,row.size());
        }
        assertTrue(user.contains("Public policy (id,version) for this estimate table: [\"pricing-v10\",10]"));
        var details=tableRows(user,"VISIBLE ROUTE COST DETAILS"); assertEquals(2,details.size());
        for(int i=0;i<details.size();i++) {
            assertEquals("all_listed",details.get(i).get(1).asText());
            var rule=c.candidates().get(i).pricingKnowledge().announcement().getFirst();
            assertEquals(rule.linkId(),details.get(i).get(6).get(0).get(0).asText());
            assertEquals(rule.amount(),details.get(i).get(6).get(0).get(3).asDouble());
        }
        assertFalse(user.contains("ORIGINAL PLAN REFERENCE"));
        assertFalse(user.contains("PRIVATE_LINK")); assertFalse(user.contains("PRIVATE_PERSON"));
        var reversed=MorningPromptRenderer.order(c,"reverse",4711);
        assertEquals("c1",tableRows(renderer.render(reversed),"JOINT TIME AND ROUTE OPTIONS").getFirst().get(1).asText());
        assertEquals(c.history(),reversed.history());
        assertTrue(renderer.system(240,true).contains("other planned activity end times remain fixed"));
    }

    @Test void fixedFactsPrecedeChangingDayPricesAndSubjectiveMemory() {
        var renderer=new MorningPromptRenderer();
        String a=renderer.render(context(10,true)),b=renderer.render(context(11,true));
        assertEquals(a.substring(0,a.indexOf("SIMULATION DAY ")),b.substring(0,b.indexOf("SIMULATION DAY ")));
        assertNotEquals(a,b); assertTrue(a.indexOf("ROUTE ATTRIBUTES")<a.indexOf("SIMULATION DAY"));
        assertTrue(a.indexOf("SELF-WRITTEN LONG-TERM MEMORY")>a.indexOf("SIMULATION DAY"));
        String reordered=renderer.render(MorningPromptRenderer.order(context(10,true),"reverse",4711));
        assertEquals(a.substring(0,a.indexOf("AVAILABLE ACTIONS")),reordered.substring(0,reordered.indexOf("AVAILABLE ACTIONS")),
                "Changing candidate display order must not cut off the fixed personal prefix");
        assertTrue(MorningMemoryUpdater.SYSTEM.startsWith(MorningPromptRenderer.FACT_RULES));
    }

    @Test void historyRetainsSelectedAndActualTimesUnfinishedNullsAndEveryPayment() {
        var c=context(10,false); String user=new MorningPromptRenderer().render(c);
        var row=tableRows(user,"YOUR MOST RECENT PERSONAL EXPERIENCES").getFirst(); var e=c.history().getFirst();
        assertEquals(e.selectedDepartureSeconds(),row.get(2).asDouble());
        assertEquals(e.departureSeconds(),row.get(3).asDouble());
        assertTrue(row.get(4).isNull()); assertTrue(row.get(5).isNull()); assertTrue(row.get(6).isNull()); assertTrue(row.get(7).isNull());
        assertTrue(row.get(8).asBoolean()); assertTrue(row.get(9).asBoolean());
        assertEquals(e.tollPaid(),row.get(10).asDouble()); assertEquals(2,row.get(11).size());
        for(int i=0;i<2;i++) {
            var payment=e.tollObservations().get(i); var value=row.get(11).get(i);
            assertEquals(payment.day(),value.get(0).asInt()); assertEquals(payment.linkId(),value.get(1).asText());
            assertEquals(payment.time(),value.get(2).asDouble()); assertEquals(payment.amount(),value.get(3).asDouble());
        }
    }

    @Test void hiddenPricesRemainNullAndExactLongTermKnowledgeDoesNotLeak() {
        String user=new MorningPromptRenderer().render(context(10,false));
        for(var row:tableRows(user,"JOINT TIME AND ROUTE OPTIONS")) {
            assertEquals(6,row.size()); assertTrue(row.get(4).isNull()); assertTrue(row.get(5).isNull());
        }
        assertFalse(user.contains("pricing-v10")); assertFalse(user.contains("SECRET_CURRENT_ROAD"));
        assertFalse(user.contains("YOUR OWN ACTUAL PAYMENT OBSERVATIONS BEFORE TODAY"));
        assertFalse(user.contains("VISIBLE ROUTE COST DETAILS"));
        assertTrue(user.contains("Unknown does not mean free"));
    }

    @Test void reflectionRetainsTodayButMovesDayAfterFixedFacts() {
        var a=reflection(10); var b=reflection(11);
        String x=MorningMemoryUpdater.render(a),y=MorningMemoryUpdater.render(b);
        assertEquals(x.substring(0,x.indexOf("REFLECT AFTER SIMULATION DAY")),y.substring(0,y.indexOf("REFLECT AFTER SIMULATION DAY")));
        assertEquals(10,tableRows(x,"RECENT ACTUAL EXPERIENCES").getFirst().get(0).asInt());
        assertEquals(2,tableRows(x,"RECENT ACTUAL EXPERIENCES").getFirst().get(11).size());
        assertFalse(x.contains("pricing-v")); assertFalse(x.contains("SECRET_CURRENT_ROAD"));
        assertEquals(240,MorningMemoryUpdater.TARGET_CHARACTERS); assertEquals(500,MorningMemoryUpdater.MAX_CHARACTERS);
        assertEquals(5,MorningMemoryUpdater.RECENT_EXPERIENCE_LIMIT);
    }

    @Test void differentOriginalReferenceEstimateIsRetainedWithoutAddingAnAction() {
        var c=context(10,true); var o=c.candidates().getFirst();
        var changed=new MorningRoutePolicy.Option(o.routeId(),o.links(),1234.56789,o.expectedArrivalSeconds(),o.distanceMeters(),
                o.estimatedMonetaryCost(),o.estimateSource(),0,0,null,null,o.estimatedToll(),o.tollPolicyId(),o.tollPolicyVersion(),o.pricingKnowledge());
        var reference=new MorningRoutePolicy.Context(c.day(),c.tripId(),c.personId(),c.originPurpose(),c.destinationPurpose(),c.plannedDepartureSeconds(),
                c.latestArrivalSeconds(),c.personAttributes(),c.schedule(),List.of(changed,c.candidates().get(1)),c.history(),c.profile(),c.memorySummary(),c.memorySummaryDay(),c.information(),c.departureOptions());
        String user=new MorningPromptRenderer().render(reference);
        assertEquals(1234.56789,tableRows(user,"ORIGINAL PLAN REFERENCE ESTIMATES").getFirst().get(2).asDouble());
        assertEquals(6,tableRows(user,"JOINT TIME AND ROUTE OPTIONS").size());
        assertEquals(List.of("c0","c1"),MorningJointTestSupport.routeIds(user));
    }

    private static MorningMemoryUpdater.Context reflection(int day) {
        var c=context(day+1,false);
        return new MorningMemoryUpdater.Context(day,c.tripId(),c.personId(),c.destinationPurpose(),c.latestArrivalSeconds(),c.personAttributes(),c.profile(),
                "I retain earlier uncertainty.",day-1,c.history(),List.of("c0","c1"));
    }

    private static MorningRoutePolicy.Context context(int day,boolean informed) {
        var info=new MorningRoutePolicy.Information(informed?"informed":"uninformed",informed,false,3600,List.of());
        var grid=new ArrayList<MorningRoutePolicy.DepartureOption>();
        for(double time:List.of(30600d,31500d,32400d)) {
            var options=new ArrayList<MorningRoutePolicy.Option>();
            for(int i=0;i<2;i++) {
                double base=1.123456789+i,toll=day*.1+i*.123456789;
                var links=List.of("SECRET_CURRENT_ROAD");
                var knowledge=new MorningRoutePolicy.PricingKnowledge(info.group(),informed?"current_announcement":"bounded_personal_memory",base,informed?toll:0,
                        informed?List.of():links,List.of(),informed?List.of(new MorningRoutePolicy.AnnouncedToll(links.getFirst(),0,108000,toll)):List.of());
                options.add(new MorningRoutePolicy.Option("c"+i,links,600.123456789+i,time+720.123456789+i,1000.123456789+i,
                        informed?Double.valueOf(base+toll):null,"static_free_flow",0,0,null,null,informed?Double.valueOf(toll):null,informed?"pricing-v"+day:null,informed?day:0,knowledge));
            }
            grid.add(new MorningRoutePolicy.DepartureOption(time,options));
        }
        var payments=List.of(new MorningRoutePolicy.TollObservation(day-1,"OWN_ROAD",31600.125,2.125),new MorningRoutePolicy.TollObservation(day-1,"OWN_ROAD",31601.25,2.125));
        var e=new MorningMemory.Experience(day-1,"c0",null,null,null,true,31510.25,null,4.25,31500.125,payments);
        return new MorningRoutePolicy.Context(day,"PRIVATE_PERSON/0","PRIVATE_PERSON","home","work",31500,32400d,
                Map.of("age",35,"income",1000,"internalLink","PRIVATE_LINK"),
                Map.of("activities",List.of(Map.of("purpose","work","plannedStartSeconds",32400.125,"plannedEndSeconds",61200.5,"openingSeconds",28800,"closingSeconds",64800,"latestStartSeconds",32400))),
                grid.get(1).routes(),List.of(e),new Profile(Profile.Income.LOW,Profile.DecisionStyle.CONSERVATIVE,Profile.WorkTimeliness.SLIGHT_LATE),
                "I remember my own payments; day "+(day-1),day-1,info,grid);
    }
}
