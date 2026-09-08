package org.matsim.agentic.manager;

import java.util.*;

public interface ManagerDecisionProvider {
    record Decision(Map<String,List<Integer>> levels,String reason) {}
    record Cell(String linkId,int bin,long observations,Double meanDelaySeconds) {}
    record Observation(int firstIteration,int lastIteration,long completedCarLegs,
            long unfinishedCarLegs,double carTravelSeconds,long completedLinkTraversals,
            double linkDelaySeconds,double revenue,List<Cell> cells) {}
    Decision decide(ManagerSettings settings,TollPolicy current,Observation observation,
                    String scene,List<Object> memory) throws Exception;

    static ManagerDecisionProvider fixed() {
        return (s,p,o,scene,memory)->{
            var levels=new LinkedHashMap<String,List<Integer>>();
            s.candidateLinks().forEach(id->levels.put(id,Collections.nCopies(s.bins(),s.fixedLevel())));
            return new Decision(levels,"Fixed configured price table");
        };
    }
    static ManagerDecisionProvider rule() {
        return (s,p,o,scene,memory)->{
            var levels=new LinkedHashMap<String,List<Integer>>();
            p.levels().forEach((k,v)->levels.put(k,new ArrayList<>(v)));
            for (Cell cell:o.cells()) {
                if(cell.observations()<s.minimumObservations() || cell.meanDelaySeconds()==null) continue;
                var row=levels.get(cell.linkId()); int value=row.get(cell.bin());
                if(cell.meanDelaySeconds()>s.raiseDelaySeconds()) value=Math.min(value+1,s.priceLevels().size()-1);
                else if(cell.meanDelaySeconds()<s.lowerDelaySeconds()) value=Math.max(0,value-1);
                row.set(cell.bin(),value);
            }
            return new Decision(levels,"Threshold controller with hysteresis; insufficient data means KEEP");
        };
    }
}
