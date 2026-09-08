package org.matsim.agentic.manager;

import java.util.*;

/** Immutable policy; bins are [start,end). Zero is an explicit no-charge action. */
public record TollPolicy(int version, int effectiveIteration, Map<String,List<Integer>> levels) {
    public TollPolicy {
        var copy=new LinkedHashMap<String,List<Integer>>();
        levels.forEach((k,v)->copy.put(k,List.copyOf(v)));
        levels=Collections.unmodifiableMap(copy);
    }
    public static TollPolicy zero(ManagerSettings s) {
        var levels=new LinkedHashMap<String,List<Integer>>();
        s.candidateLinks().forEach(id->levels.put(id,Collections.nCopies(s.bins(),0)));
        return new TollPolicy(0,0,levels);
    }
    public void validate(ManagerSettings s, TollPolicy previous, boolean fixed) {
        if (!levels.keySet().equals(previous.levels.keySet()))
            throw new IllegalArgumentException("Policy must contain exactly all configured candidates");
        levels.forEach((id,values)->{
            if (values.size()!=s.bins()) throw new IllegalArgumentException("Wrong bin count: "+id);
            for (int i=0;i<values.size();i++) {
                int v=values.get(i);
                if (v<0 || v>=s.priceLevels().size()
                        || (!fixed && Math.abs(v-previous.levels.get(id).get(i))>1))
                    throw new IllegalArgumentException("Invalid price level or step: "+id);
            }
        });
    }
    public double amount(ManagerSettings s,String link,double time) {
        int bin=s.bin(time);
        return bin<0 || !levels.containsKey(link) ? 0 : s.priceLevels().get(levels.get(link).get(bin));
    }
}
