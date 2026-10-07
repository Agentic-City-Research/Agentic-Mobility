package org.matsim.agentic;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.population.routes.NetworkRoute;
import java.util.*;

/** Public, aggregated reference-plan diagnostics. No person identifiers or private attributes are retained. */
final class MorningManagerDemand {
    record Od(String origin,String destination) {}
    private final Map<String,Map<String,Object>> links;
    private final int routedTrips, unroutedTrips;
    private MorningManagerDemand(Map<String,Map<String,Object>> links,int routedTrips,int unroutedTrips) {
        this.links=links; this.routedTrips=routedTrips; this.unroutedTrips=unroutedTrips;
    }
    static MorningManagerDemand empty() { return new MorningManagerDemand(Map.of(),0,0); }
    static MorningManagerDemand from(Scenario scenario) {
        var network=scenario.getNetwork();
        Map<String,Map<Od,Integer>> users=new HashMap<>(); int routed=0,unrouted=0;
        for (var person : scenario.getPopulation().getPersons().values()) {
            if (person.getSelectedPlan()==null) continue;
            for (var e : person.getSelectedPlan().getPlanElements()) if (e instanceof Leg leg && leg.getMode().equals("car")) {
                if (!(leg.getRoute() instanceof NetworkRoute route) || route.getStartLinkId()==null || route.getEndLinkId()==null) { unrouted++; continue; }
                routed++; var od=new Od(route.getStartLinkId().toString(),route.getEndLinkId().toString());
                Set<String> used=new HashSet<>(); used.add(od.origin()); used.add(od.destination());
                route.getLinkIds().forEach(id -> used.add(id.toString()));
                for (String id : used) users.computeIfAbsent(id,k -> new HashMap<>()).merge(od,1,Integer::sum);
            }
        }
        var result=new TreeMap<String,Map<String,Object>>();
        for (var link : network.getLinks().values()) if (link.getAllowedModes().contains("car")) {
            String id=link.getId().toString(); var ods=users.getOrDefault(id,Map.of());
            int total=0,compulsory=0; Map<String,Set<String>> reachable=new HashMap<>();
            for (var entry : ods.entrySet()) {
                var od=entry.getKey(); int n=entry.getValue(); total+=n;
                boolean mandatory=od.origin().equals(id) || od.destination().equals(id);
                if (!mandatory && !od.origin().equals(od.destination())) {
                    var origin=network.getLinks().get(Id.createLinkId(od.origin()));
                    var dest=network.getLinks().get(Id.createLinkId(od.destination()));
                    if (origin==null || dest==null) continue;
                    var seen=reachable.computeIfAbsent(origin.getToNode().getId().toString(),s -> reachable(network,s,id));
                    mandatory=!seen.contains(dest.getFromNode().getId().toString());
                }
                if (mandatory) compulsory+=n;
            }
            var row=new LinkedHashMap<String,Object>(); row.put("linkId",id); row.put("referenceCarTripsUsingLink",total);
            row.put("structurallyUnavoidableReferenceTrips",compulsory);
            row.put("unavoidableShare",total==0?null:(double)compulsory/total); result.put(id,row);
        }
        return new MorningManagerDemand(Collections.unmodifiableMap(result),routed,unrouted);
    }
    private static Set<String> reachable(Network network,String start,String excluded) {
        var seen=new HashSet<String>(); var pending=new ArrayDeque<Node>();
        seen.add(start); pending.add(network.getNodes().get(Id.createNodeId(start)));
        while (!pending.isEmpty()) for (var l : pending.removeFirst().getOutLinks().values()) {
            String next=l.getToNode().getId().toString();
            if (l.getAllowedModes().contains("car") && !l.getId().toString().equals(excluded) && seen.add(next)) pending.add(l.getToNode());
        }
        return seen;
    }
    Double unavoidableShare(String link) {
        var row=links.get(link); return row==null?(Double)null:(Double)row.get("unavoidableShare");
    }
    Map<String,Object> context() {
        return Map.of("referenceRoutedCarTrips",routedTrips,"unroutedCarTrips",unroutedTrips,"links",List.copyOf(links.values()),
                "scope","Selected reference plans at the capture stage reported by referenceStage, all-day car legs including background population. Counts use actual link OD and reference routes; removing a link tests directed car reachability between origin.to and destination.from. Origin/destination links are compulsory. Same-link OD does not require a graph cycle.",
                "limitations","Structural avoidability does not prove free capacity, feasible peak timing, actual payment or beneficial diversion. Reference routes can differ from subsequent executed routes. No individual identities, profiles or memories are exposed.");
    }
}
