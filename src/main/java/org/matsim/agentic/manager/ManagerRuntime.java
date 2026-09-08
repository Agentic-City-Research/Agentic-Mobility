package org.matsim.agentic.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.costcalculators.RandomizingTimeDistanceTravelDisutilityFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.vehicles.Vehicle;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Explicitly invoked by the traveler controller: score old policy, publish new policy, replan. */
public final class ManagerRuntime implements VehicleEntersTrafficEventHandler,
        VehicleLeavesTrafficEventHandler, LinkEnterEventHandler, LinkLeaveEventHandler {
    private final ManagerSettings settings;
    private final Scenario scenario;
    private final EventsManager events;
    private final ManagerDecisionProvider provider;
    private final ObjectMapper json=new ObjectMapper();
    private final Map<Id<Vehicle>,Driver> drivers=new HashMap<>();
    private final Map<Id<Vehicle>,Entry> entries=new HashMap<>();
    private final Map<String,Stats> cells=new HashMap<>();
    private final Map<String,Double> paid=new HashMap<>();
    private final List<Object> memory=new ArrayList<>();
    private volatile TollPolicy policy;
    private BufferedWriter observations,decisions,policies,payments;
    private Path output;
    private String scene;
    private int iteration,cycleFirst,cycleRounds,nextVersion=1;
    private long completed,unfinished,traversals;
    private double travelSeconds,delaySeconds,revenue;
    private record Driver(Id<Person> person,double start) {}
    private record Entry(String link,double time) {}
    private static final class Stats { long n; double delay; }

    public static ManagerRuntime configure(Controler c) {
        String file=System.getenv("MANAGER_CONFIG");
        if(file==null || file.isBlank()) return null;
        try {
            ManagerSettings s=ManagerSettings.load(Path.of(file));
            if(s.mode().equals("off")) return null;
            return configure(c,s,
                    switch(s.provider()) { case "fixed"->ManagerDecisionProvider.fixed();
                        case "rule"->ManagerDecisionProvider.rule(); default->new LlmManagerProvider(); });
        } catch(IOException e) {throw new IllegalArgumentException("Cannot read MANAGER_CONFIG",e);}
    }
    public static ManagerRuntime configure(Controler c,ManagerSettings s,ManagerDecisionProvider provider) {
            if (s.mode().equals("off")) return null;
            if (c.getConfig().controller().getFirstIteration()!=0)
                throw new IllegalArgumentException("Manager v1 requires firstIteration=0; checkpoint resume is not supported");
            var runtime=new ManagerRuntime(s,c.getScenario(),c.getEvents(),provider);
            c.getEvents().addHandler(runtime);
            if(s.mode().equals("active")) {
                c.addOverridingModule(new AbstractModule() {
                    @Override public void install() {
                        var base=new RandomizingTimeDistanceTravelDisutilityFactory("car",c.getConfig());
                        addTravelDisutilityFactoryBinding("car").toInstance(time->runtime.withToll(base.createTravelDisutility(time)));
                    }
                });
            }
            return runtime;
    }
    public ManagerRuntime(ManagerSettings settings,Scenario scenario,EventsManager events,
                          ManagerDecisionProvider provider) {
        this.settings=settings; this.scenario=scenario; this.events=events; this.provider=provider;
        settings.validate(scenario.getNetwork()); policy=TollPolicy.zero(settings);
    }
    public TollPolicy policy() { return policy; }
    public String mode() { return settings.mode(); }
    public TravelDisutility withToll(TravelDisutility base) {
        return new TravelDisutility() {
            public double getLinkTravelDisutility(Link l,double t,Person p,Vehicle v) {
                var scoring=scenario.getConfig().scoring().getScoringParameters(
                        p==null?null:PopulationUtils.getSubpopulation(p));
                return base.getLinkTravelDisutility(l,t,p,v)
                        + policy.amount(settings,l.getId().toString(),t)*scoring.getMarginalUtilityOfMoney();
            }
            public double getLinkMinimumTravelDisutility(Link l) {
                // Zero toll is a valid lower bound for nonnegative prices.
                return base.getLinkMinimumTravelDisutility(l);
            }
        };
    }
    public void start(Path directory) {
        try {
            output=directory;
            observations=writer("manager-observations.jsonl"); decisions=writer("manager-decisions.jsonl");
            policies=writer("toll-policies.jsonl"); payments=writer("toll-payments.csv");
            payments.write("iteration,policy_version,person_id,vehicle_id,link_id,time_seconds,amount\n");
            var links=new ArrayList<Object>();
            for(String id:settings.candidateLinks()) {
                Link l=scenario.getNetwork().getLinks().get(Id.createLinkId(id));
                links.add(Map.of("id",id,"from",l.getFromNode().getId().toString(),
                        "to",l.getToNode().getId().toString(),"length_m",l.getLength(),
                        "free_speed_m_s",l.getFreespeed(),"effective_capacity_veh_h",
                        l.getCapacity()*3600/scenario.getNetwork().getCapacityPeriod()*scenario.getConfig().qsim().getFlowCapFactor(),
                        "upstream",l.getFromNode().getInLinks().keySet().stream().map(Object::toString).sorted().toList(),
                        "downstream",l.getToNode().getOutLinks().keySet().stream().map(Object::toString).sorted().toList()));
            }
            var sceneInfo=Map.of("network_links",scenario.getNetwork().getLinks().size(),
                    "population",scenario.getPopulation().getPersons().size(),"candidates",links,
                    "money_unit","scenario money unit; not calibrated currency",
                    "scoring_money_utility",scenario.getConfig().scoring().getMarginalUtilityOfMoney());
            scene=json.writeValueAsString(sceneInfo);
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("manager-scene.json").toFile(),sceneInfo);
            var manifest=new LinkedHashMap<String,Object>(); manifest.put("settings",settings);
            manifest.put("model",provider instanceof LlmManagerProvider l?l.model():settings.provider());
            manifest.put("prompt_version","manager-pricing-v1"); manifest.put("knowledge_version","congestion-pricing-v1");
            manifest.put("travelers_feedback",false); manifest.put("manager_count",1);
            manifest.put("charging","car network-mode population drivers excluding transit fleet; LinkEnter only; trip origin link exempt; [start,end)");
            manifest.put("routing","car base disutility plus toll; MATSim endpoint conventions retained");
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("manager-manifest.json").toFile(),manifest);
            line(policies,policy); flush();
        } catch(IOException e) {throw new UncheckedIOException(e);}
    }
    private BufferedWriter writer(String name) throws IOException {
        return Files.newBufferedWriter(output.resolve(name));
    }
    private void line(BufferedWriter out,Object value) throws IOException { out.write(json.writeValueAsString(value)); out.newLine(); }
    private static String csv(Object value) {return "\""+value.toString().replace("\"","\"\"")+"\"";}
    @Override public void reset(int it) {
        iteration=it; drivers.clear(); entries.clear(); paid.clear();
        if(cycleRounds==0) cycleFirst=it;
    }
    @Override public void handleEvent(VehicleEntersTrafficEvent e) {
        if("car".equals(e.getNetworkMode()) && scenario.getPopulation().getPersons().containsKey(e.getPersonId())
                && !scenario.getTransitVehicles().getVehicles().containsKey(e.getVehicleId()))
            drivers.put(e.getVehicleId(),new Driver(e.getPersonId(),e.getTime()));
        // Starting on a link is not a LinkEnter traversal; consistent exemption every leg.
    }
    @Override public void handleEvent(LinkEnterEvent e) {
        Driver driver=drivers.get(e.getVehicleId()); if(driver==null) return;
        Entry prior=entries.get(e.getVehicleId());
        if(prior!=null && prior.link().equals(e.getLinkId().toString()) && prior.time()==e.getTime()) return;
        entries.put(e.getVehicleId(),new Entry(e.getLinkId().toString(),e.getTime()));
        double amount=settings.mode().equals("active")?policy.amount(settings,e.getLinkId().toString(),e.getTime()):0;
        if(amount<=0) return;
        paid.merge(driver.person().toString(),amount,Double::sum); revenue+=amount;
        events.processEvent(new PersonMoneyEvent(e.getTime(),driver.person(),-amount,"congestionPricing",
                "single-manager","policy="+policy.version()+";link="+e.getLinkId()));
        if(payments!=null) try {
            payments.write(iteration+","+policy.version()+","+csv(driver.person())+","+csv(e.getVehicleId())+","
                    +csv(e.getLinkId())+","+e.getTime()+","+amount+"\n");
        } catch(IOException ex) {throw new UncheckedIOException(ex);}
    }
    @Override public void handleEvent(LinkLeaveEvent e) {
        Entry entry=entries.remove(e.getVehicleId());
        if(entry==null || !entry.link().equals(e.getLinkId().toString())) return;
        Link l=scenario.getNetwork().getLinks().get(e.getLinkId());
        double delay=Math.max(0,e.getTime()-entry.time()-l.getLength()/l.getFreespeed(entry.time()));
        traversals++; delaySeconds+=delay;
        int bin=settings.bin(entry.time());
        if(bin>=0 && policy.levels().containsKey(entry.link())) {
            Stats stats=cells.computeIfAbsent(entry.link()+"\u0000"+bin,unused->new Stats());
            stats.n++; stats.delay+=delay;
        }
    }
    @Override public void handleEvent(VehicleLeavesTrafficEvent e) {
        Driver driver=drivers.remove(e.getVehicleId()); entries.remove(e.getVehicleId());
        if(driver!=null) { completed++; travelSeconds+=Math.max(0,e.getTime()-driver.start()); }
    }
    public ManagerDecisionProvider.Observation observation(int end) {
        var rows=new ArrayList<ManagerDecisionProvider.Cell>();
        for(String id:settings.candidateLinks()) for(int bin=0;bin<settings.bins();bin++) {
            Stats stat=cells.get(id+"\u0000"+bin);
            rows.add(new ManagerDecisionProvider.Cell(id,bin,stat==null?0:stat.n,
                    stat==null || stat.n==0?null:stat.delay/stat.n));
        }
        return new ManagerDecisionProvider.Observation(cycleFirst,end,completed,unfinished,travelSeconds,
                traversals,delaySeconds,revenue,rows);
    }
    public void iterationEnded(int it,int last) {
        unfinished+=drivers.size(); cycleRounds++;
        boolean warmup=it+1<=settings.warmupIterations();
        boolean due=it+1>=settings.warmupIterations()
                && (it+1-settings.warmupIterations())%settings.updateInterval()==0;
        try {
            // Always record a cumulative window snapshot tagged with the actually applied policy.
            var observation=observation(it);
            line(observations,Map.of("policy",policy,"window",observation,"warmup",warmup));
            if(due && it<last-settings.freezeLastIterations()) {
                var record=new LinkedHashMap<String,Object>();
                record.put("observation",observation); record.put("observed_policy",policy);
                record.put("mode",settings.mode()); record.put("target_iteration",it+1);
                TollPolicy accepted=null;
                try {
                    var decision=provider.decide(settings,policy,observation,scene,List.copyOf(memory));
                    record.put("proposal",decision);
                    var proposed=new TollPolicy(nextVersion++,it+1,decision.levels());
                    proposed.validate(settings,policy,settings.provider().equals("fixed"));
                    if (!settings.provider().equals("fixed")) {
                        for (var cell:observation.cells()) {
                            if ((cell.observations()<settings.minimumObservations() || cell.meanDelaySeconds()==null)
                                    && !proposed.levels().get(cell.linkId()).get(cell.bin())
                                    .equals(policy.levels().get(cell.linkId()).get(cell.bin())))
                                throw new IllegalArgumentException("Insufficient evidence: keep cell unchanged");
                        }
                    }
                    record.put("validated",true);
                    accepted=proposed;
                } catch(Exception e) {
                    if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                    record.put("validated",false); record.put("fallback","KEEP");
                    record.put("error_type",e.getClass().getSimpleName());
                }
                if(accepted!=null && settings.mode().equals("active") && !accepted.levels().equals(policy.levels())) {
                    // Audit I/O errors must abort; they are not model failures and must not claim KEEP.
                    line(policies,accepted); policy=accepted;
                }
                record.put("applied_policy",policy);
                line(decisions,record); memory.add(record);
                while(memory.size()>settings.memoryCycles()) memory.removeFirst();
                json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("manager-memory.json").toFile(),memory);
                clearWindow();
            } else if(warmup && it+1==settings.warmupIterations()) clearWindow();
            flush();
        } catch(IOException e) {throw new UncheckedIOException(e);}
    }
    private void clearWindow() {
        cells.clear(); completed=unfinished=traversals=0; travelSeconds=delaySeconds=revenue=0; cycleRounds=0;
    }
    public String travelerContext(String person) {
        if(!settings.mode().equals("active")) return "";
        try {
            return "\nCONGESTION_POLICY\n"+json.writeValueAsString(Map.of("policy",policy,
                    "price_levels",settings.priceLevels(),"start_seconds",settings.startTimeSeconds(),
                    "bin_seconds",settings.timeBinSeconds(),"last_actual_payment",paid.getOrDefault(person,0.0)))
                    +"\nPrices are per LinkEnter in scenario money units; origin link is exempt. Only car is charged. "
                    +"This is not a route-specific price prediction. Historical scores belong to their policy regime.\n";
        } catch(IOException e) {throw new UncheckedIOException(e);}
    }
    private void flush() throws IOException {for(var w:List.of(observations,decisions,policies,payments)) w.flush();}
    public void close() {
        try {for(var w:new BufferedWriter[]{observations,decisions,policies,payments}) if(w!=null) w.close();}
        catch(IOException e) {throw new UncheckedIOException(e);}
    }
}
