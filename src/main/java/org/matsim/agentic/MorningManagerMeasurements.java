package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import java.util.*;

/** Full-day and exactly aligned entry-window statistics on the same policy-selected links. */
final class MorningManagerMeasurements {
    static final List<String> FIELDS=List.of("totalLinkDelaySeconds","totalLinkTravelSeconds","enteredFullLinkTraversals",
            "completedFullLinkTraversals","abortedFullLinkTraversals","unfinishedFullLinkTraversals","partialLinkTraversals");
    private static final List<String> RAW=List.of("total_delay","total_travel_time","entered","completed","aborted","unfinished","partial");
    static Map<String,Map<String,Double>> spatial(List<Map<String,Object>> rows,TollPolicy policy) {
        var sums=new LinkedHashMap<String,double[]>();
        for (String group : List.of("network","pricedLinksFullDay","pricedChargingWindow","pricedOutsideWindow","otherLinksFullDay")) sums.put(group,new double[FIELDS.size()]);
        Set<String> priced=new HashSet<>(); policy.rules().stream().filter(r -> r.amount()>0).forEach(r -> priced.add(r.linkId().toString()));
        for (var row : rows) {
            String link=(String)row.get("link_id"); boolean selected=priced.contains(link);
            double start=((Number)row.get("start")).doubleValue(),end=((Number)row.get("end")).doubleValue();
            boolean active=policy.rules().stream().anyMatch(r -> r.amount()>0 && r.linkId().toString().equals(link) && r.startSec()<=start && r.endSec()>=end);
            if (selected && !active && policy.rules().stream().anyMatch(r -> r.amount()>0 && r.linkId().toString().equals(link) && r.startSec()<end && r.endSec()>start))
                throw new IllegalArgumentException("Pricing measurements require windows aligned with observed entry bins");
            for (int i=0;i<RAW.size();i++) {
                double value=((Number)row.get(RAW.get(i))).doubleValue();
                sums.get("network")[i]+=value; sums.get(selected?"pricedLinksFullDay":"otherLinksFullDay")[i]+=value;
                if (selected) sums.get(active?"pricedChargingWindow":"pricedOutsideWindow")[i]+=value;
            }
        }
        var result=new LinkedHashMap<String,Map<String,Double>>();
        sums.forEach((group,values) -> {var r=new LinkedHashMap<String,Double>(); for(int i=0;i<values.length;i++) r.put(FIELDS.get(i),values[i]); result.put(group,Collections.unmodifiableMap(r));});
        return Collections.unmodifiableMap(result);
    }
    static Map<String,Object> statistics(List<Map<String,Map<String,Double>>> days) {
        var result=new LinkedHashMap<String,Object>(); if (days.isEmpty()) return result;
        for (String group : days.getFirst().keySet()) {
            var total=new LinkedHashMap<String,Double>(); var mean=new LinkedHashMap<String,Double>();
            for (String field:FIELDS) {double v=days.stream().mapToDouble(d -> d.get(group).get(field)).sum();total.put(field,v);mean.put(field,v/days.size());}
            var r=new LinkedHashMap<String,Object>(); r.put("totals",total);r.put("meanDaily",mean);
            double completed=total.get("completedFullLinkTraversals");
            r.put("delaySecondsPerCompletedTraversal",completed==0?null:total.get("totalLinkDelaySeconds")/completed);
            r.put("travelSecondsPerCompletedTraversal",completed==0?null:total.get("totalLinkTravelSeconds")/completed);
            result.put(group,r);
        }
        return result;
    }
}
