package org.matsim.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.*;
import java.util.zip.*;

/** Small, explicitly controlled Java v9 experiments; no Python agent service. */
@Command(name="v9-reasonableness", mixinStandardHelpOptions=true)
public final class V9ReasonablenessExperiments implements Callable<Integer> {
    @Option(names="--experiment", defaultValue="all", description="b1 | memory | all") String experiment;
    @Option(names="--mode", defaultValue="mock", description="mock (plumbing only) | api (DeepSeek)") String mode;
    @Option(names="--output", required=true) Path output;
    @Option(names="--model", defaultValue="deepseek-flash") String model;
    @Option(names="--base-url", defaultValue="https://api.deepseek.com") String baseUrl;
    @Option(names="--temperature", defaultValue="0") double temperature;
    @Option(names="--attempts", defaultValue="3") int attempts;
    @Option(names="--max-requests", defaultValue="200") int maxRequests;
    @Option(names="--timeout-seconds", defaultValue="180") int timeout;
    @Option(names="--min-excess-seconds", defaultValue="600") double minExcess;
    @Option(names="--source-run", description="Reuse a completed five-day Java source directory with v9 contexts, trips and memory updates") Path sourceRun;
    private static final String TARGET="36261_1/0", ROUTE="c0";
    private static final double DEPARTURE=27574.0;
    private AgenticConfig config;
    private LlmUsageTracker usage;
    private MorningRequestBudget budget;

    public static void main(String[] args) { System.exit(new CommandLine(new V9ReasonablenessExperiments()).execute(args)); }

    @Override public Integer call() throws Exception {
        if (!Set.of("b1","memory","all").contains(experiment) || !Set.of("mock","api").contains(mode)
                || attempts<1 || maxRequests<20 || timeout<1 || minExcess<0 || temperature<0 || temperature>2)
            throw new IllegalArgumentException("Invalid experiment, mode, limit or temperature");
        if (Files.exists(output)) throw new IllegalArgumentException("Output exists; use a new directory");
        if (mode.equals("api") && (System.getenv("AGENTIC_API_KEY")==null || System.getenv("AGENTIC_API_KEY").isBlank()))
            throw new IllegalArgumentException("Set AGENTIC_API_KEY in the current process");
        Files.createDirectories(output);
        config=MorningModelOptions.travelerDefaults();
        config.model=model; config.baseUrl=baseUrl; config.thinking="disabled"; config.temperature=temperature;
        config.responseFormat="json_object"; config.maxOutputTokens=1024; config.timeoutSeconds=timeout;
        config.apiKey=mode.equals("mock") ? "offline-only" : System.getenv("AGENTIC_API_KEY");
        usage=new LlmUsageTracker(0); budget=new MorningRequestBudget(maxRequests);
        try (var root=new OutputWriter(output)) {
            root.manifest(Map.of("schemaVersion",1,"mode",mode,"model",model,"settings",config.publicModelSettings(),
                    "v9Template",MorningPromptRenderer.VERSION,"mockIsBehavioralEvidence",false,
                    "maxBranchAndB1ClientCalls",maxRequests,"sourceHasSeparateRequestLimit",true,
                    "scope","synthetic B1; MATSim source and same-checkpoint Java v9 branches; no human validation"));
            try {
                boolean ok=true;
                if (!experiment.equals("memory")) ok=runB1(root);
                if (!experiment.equals("b1") && ok) ok=runMemory(root);
                else if (experiment.equals("all") && !ok) root.appendLine("stages.jsonl",Map.of("stage","memory","complete",false,"reason","B1 protocol checks did not complete"));
                root.apiUsage(usage.toJson());
                root.status(Map.of("complete",ok,"mode",mode,"experiment",experiment));
                return ok ? 0 : 2;
            } catch(Exception failure) {
                root.appendLine("stages.jsonl",Map.of("stage","exception","complete",false,"errorType",failure.getClass().getSimpleName(),"error",failure.toString()));
                root.apiUsage(usage.toJson());
                root.status(Map.of("complete",false,"mode",mode,"experiment",experiment,"errorType",failure.getClass().getSimpleName()));
                throw failure;
            }
        }
    }

    private LlmClient live() { return mode.equals("mock") ? V9ReasonablenessExperiments::mockComplete : new DeepSeekClient(config,usage); }
    private static LlmResponse mockComplete(String role,String system,String user) {
        if (role.equals("traveler_memory")) return new LlmResponse("{\"memory\":\"Mock note: I experienced a trip.\"}",0,0,0,0);
        Matcher r=Pattern.compile("AVAILABLE ACTIONS FOR THIS TRIP: (\\[[^\\n]+?\\])").matcher(user);
        if (!r.find()) throw new IllegalArgumentException("Mock cannot find routes");
        JsonNode routes=Json.readTree(r.group(1));
        Matcher t=Pattern.compile("Allowed departureSeconds: (\\[[^\\n]+?\\])").matcher(user);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("routeId",routes.get(0).asText());
        if(t.find()) result.put("departureSeconds",Json.readTree(t.group(1)).get(0).asDouble());
        result.put("reason","Offline mock, not behavioral evidence.");
        return new LlmResponse(Json.write(result),0,0,0,0);
    }

    private record Scenario(String name, MorningRoutePolicy.Context context, String expected, boolean manualReview) {}
    private static MorningRoutePolicy.Option option(String id,double drive,double arrival,double price) {
        return new MorningRoutePolicy.Option(id,List.of("origin",id,"destination"),drive,arrival,1000,price,"synthetic",0,0);
    }
    private static MorningRoutePolicy.Context context(String id,int day,List<MorningRoutePolicy.Option> options,
                                                       List<MorningMemory.Experience> history,Double deadline,
                                                       String preference, List<Double> departures) {
        var attrs=new LinkedHashMap<String,Object>(); if(preference!=null) attrs.put("preferences",preference);
        var grid=new ArrayList<MorningRoutePolicy.DepartureOption>();
        for(double t:departures) {
            var rows=new ArrayList<MorningRoutePolicy.Option>();
            for(var x:options) rows.add(option(x.routeId(),x.expectedCarSeconds(),t+(x.expectedArrivalSeconds()-25200),x.estimatedMonetaryCost()));
            grid.add(new MorningRoutePolicy.DepartureOption(t,rows));
        }
        return new MorningRoutePolicy.Context(day,id,"synthetic", "home","work",25200,deadline,attrs,Map.of(),
                options,history,null,"",null,null,grid);
    }
    private static MorningRoutePolicy.Context unknownCost(String id) {
        var links=List.of("origin","road","destination");
        var knowledge=new MorningRoutePolicy.PricingKnowledge("uninformed","bounded_personal_memory",5,0,links,List.of(),List.of());
        var option=new MorningRoutePolicy.Option("c0",links,600,26000,1000,null,"static_free_flow",0,0,null,null,null,null,0,knowledge);
        var info=new MorningRoutePolicy.Information("uninformed",false,false,3600,List.of());
        return new MorningRoutePolicy.Context(0,id,"synthetic","home","work",25200,27000.0,Map.of(),Map.of(),List.of(option),List.of(),null,"",null,info,
                List.of(new MorningRoutePolicy.DepartureOption(25200,List.of(option))));
    }
    private static List<Scenario> scenarios() {
        var a=option("c0",600,26000,5); var same=option("c1",600,26000,5);
        var expensive=option("c1",600,26000,10); var slow=option("c1",900,26300,5);
        var unfinished=new MorningMemory.Experience(0,"c0",null,null,null,true,25200.0,null,null,25200.0,List.of());
        return List.of(
            new Scenario("01_single_action",context("01",0,List.of(a),List.of(),27000.0,null,List.of(25200.0)),"c0",false),
            new Scenario("02_cheaper_dominates",context("02",0,List.of(a,expensive),List.of(),27000.0,"All relevant route attributes including reliability and familiarity are equal except the listed cost; minimize the known total cost.",List.of(25200.0)),"c0",false),
            new Scenario("03_faster_dominates",context("03",0,List.of(a,slow),List.of(),27000.0,"All relevant route attributes including reliability and familiarity are equal except travel duration; minimize travel duration.",List.of(25200.0)),"c0",false),
            new Scenario("04_unknown_toll",unknownCost("04"),"c0",true),
            new Scenario("05_unfinished",context("05",1,List.of(a,same),List.of(unfinished),27000.0,null,List.of(25200.0)),null,true),
            new Scenario("06_one_route_two_times",context("06",0,List.of(a),List.of(),27000.0,null,List.of(25200.0,26100.0)),"c0",false),
            new Scenario("07_reverse_cheaper",context("07",0,List.of(expensive,a),List.of(),27000.0,"All relevant route attributes including reliability and familiarity are equal except cost; minimize the known total cost.",List.of(25200.0)),"c0",false),
            new Scenario("08_deadline",context("08",0,List.of(a,option("c1",600,28000,5)),List.of(),27000.0,"Prefer predicted on-time arrival; listed arrival estimates include access and egress.",List.of(25200.0)),"c0",true),
            new Scenario("09_estimate_not_actual",context("09",0,List.of(a,same),List.of(),27000.0,null,List.of(25200.0)),null,true),
            new Scenario("10_equal_options",context("10",0,List.of(a,same),List.of(),27000.0,null,List.of(25200.0)),null,false),
            new Scenario("11_unknown_deadline",context("11",0,List.of(a,same),List.of(),null,null,List.of(25200.0)),null,true),
            new Scenario("12_two_times_no_deadline",context("12",0,List.of(a),List.of(),null,null,List.of(25200.0,26100.0)),"c0",false));
    }
    private boolean runB1(OutputWriter root) throws Exception {
        boolean complete=true;
        try(var writer=new OutputWriter(output.resolve("b1"))) {
            var policy=new MorningLlmPolicy(live(),writer,attempts,120,config.publicModelSettings(),budget);
            int total=0,valid=0,firstValid=0,retried=0,expected=0,violations=0;
            for(var scenario:scenarios()) {
                total++;long before=policy.attemptedCalls();
                var row=new LinkedHashMap<String,Object>(); row.put("case",scenario.name());row.put("kind","synthetic_java_v9");
                row.put("expectedRoute",scenario.expected());row.put("manualReasonAuditRequired",scenario.manualReview());
                try { var d=policy.choose(scenario.context());row.put("status","completed");row.put("decision",d);
                    valid++;if(scenario.expected()!=null) {expected++;if(!scenario.expected().equals(d.routeId())) violations++;}
                    row.put("expectedRouteMet",scenario.expected()==null?null:scenario.expected().equals(d.routeId())); }
                catch(RuntimeException e) {row.put("status","failed");row.put("error",e.toString());complete=false;}
                long calls=policy.attemptedCalls()-before;row.put("clientCalls",calls);
                if(calls>1) retried++;if("completed".equals(row.get("status")) && calls==1) firstValid++;
                writer.appendLine("cases.jsonl",row);writer.flush();
            }
            var summary=new LinkedHashMap<String,Object>();
            summary.put("casesAttempted",total);summary.put("validFinalChoices",valid);summary.put("finalValidRate",(double)valid/total);
            summary.put("firstAttemptValid",firstValid);summary.put("firstAttemptValidRate",(double)firstValid/total);
            summary.put("casesRetried",retried);summary.put("caseRetryRate",(double)retried/total);
            summary.put("predefinedRouteExpectedCompleted",expected);summary.put("predefinedRouteMismatch",violations);
            summary.put("actualMatSimExecutionConsistency",null);summary.put("manualReasonAuditRequired",true);
            writer.writeDoc("summary.json",summary);
            writer.writeDoc("scope.json",Map.of("cases",12,"synthetic",true,"actualMatSimExecutionChecked",false,
                    "manualReview","Inspect reasons for null price, unfinished trip and forecasts. A legal action alone does not establish comprehension."));
        }
        boolean faults=faultChecks();
        root.appendLine("stages.jsonl",Map.of("stage","B1","complete",complete,"faultInjectionPassed",faults));
        return complete && faults;
    }
    private boolean faultChecks() throws Exception {
        var c=scenarios().getFirst().context();
        try(var writer=new OutputWriter(output.resolve("b1/fault-injection"))) {
            int[] n={0}; LlmClient bad=(role,s,u)-> {n[0]++;return n[0]==1?new LlmResponse("{bad",0,0,0,0):new LlmResponse("{\"routeId\":\"c0\",\"departureSeconds\":25200,\"reason\":\"ok\"}",0,0,0,0);};
            var p=new MorningLlmPolicy(bad,writer,2,0);p.choose(c);if(n[0]!=2) return false;
            int[] m={0};LlmClient truncated=(role,s,u)-> {m[0]++;return m[0]==1?new LlmResponse("{\"routeId\":\"c0\",\"departureSeconds\":25200,\"reason\":\"ok\"}",0,0,0,0,true,"length",0):new LlmResponse("{\"routeId\":\"c0\",\"departureSeconds\":25200,\"reason\":\"ok\"}",0,0,0,0);};
            new MorningLlmPolicy(truncated,writer,2,0).choose(c);
            int[] exhausted={0}; LlmClient invalid=(role,s,u)-> {exhausted[0]++;return new LlmResponse("{bad",0,0,0,0);};
            boolean rejected=false;
            try {new MorningLlmPolicy(invalid,writer,2,0).choose(c);} catch(LlmException expected) {rejected=true;}
            return m[0]==2 && rejected && exhausted[0]==2;
        }
    }

    private static String readGzip(Path p) throws IOException { try(var in=new GZIPInputStream(Files.newInputStream(p))) {return new String(in.readAllBytes(),StandardCharsets.UTF_8);} }
    private static void writeGzip(Path p,String s) throws IOException {try(var out=new GZIPOutputStream(Files.newOutputStream(p))) {out.write(s.getBytes(StandardCharsets.UTF_8));}}
    private static String replaceOne(String text,String regex,String replacement) {
        var matcher=Pattern.compile(regex,Pattern.DOTALL).matcher(text);
        if(!matcher.find()) throw new IllegalArgumentException("Scenario source missing expected pattern: "+regex);
        if(matcher.find()) throw new IllegalArgumentException("Scenario source replacement not unique");
        return matcher.reset().replaceFirst(replacement);
    }
    private Path prepareScenario() throws Exception {
        Path original=Path.of("scenarios/SiouxFalls-public/config.xml").toAbsolutePath();
        var cfg=ConfigUtils.loadConfig(original.toString());Path base=original.getParent();
        Path net=base.resolve(cfg.network().getInputFile()).normalize();
        Path fac=base.resolve(cfg.facilities().getInputFile()).normalize();
        Path pop=base.resolve(cfg.plans().getInputFile().replace("200pct","100pct")).normalize();
        Path dir=output.resolve("scenario").toAbsolutePath();Files.createDirectories(dir);
        String n=readGzip(net);n=replaceOne(n,"(<link\\s+id=\"74_1\"\\s+[^>]*?\\bcapacity=\")[^\"]+(\")","$1"+"1.0"+"$2");
        String p=readGzip(pop);p=replaceOne(p,"(<person id=\"18101_2\">.*?<activity type=\"home\"[^>]*?end_time=\")07:26:42(\")","$1"+"07:06:42"+"$2");
        Path newNet=dir.resolve("network-bottleneck.xml.gz"), newPop=dir.resolve("population_100pct.xml.gz");writeGzip(newNet,n);writeGzip(newPop,p);
        cfg.network().setInputFile(newNet.toString());cfg.facilities().setInputFile(fac.toString());cfg.plans().setInputFile(newPop.toString());
        Path cfgPath=dir.resolve("config.xml");new ConfigWriter(cfg).write(cfgPath.toString());
        Files.writeString(dir.resolve("intervention.json"),Json.write(Map.of("targetTrip",TARGET,"forcedRoute",ROUTE,"forcedDepartureSeconds",DEPARTURE,"capacityLink","74_1","capacityPerHour",1.0,"backgroundPerson","18101_2","backgroundDeparture","07:06:42","appliedAllDays",true)),StandardCharsets.UTF_8);
        return cfgPath;
    }

    private Path runSource() throws Exception {
        Path cfg=prepareScenario();Path dest=output.resolve("source-matsim").toAbsolutePath();
        var runner=new MorningExperiment();
        new CommandLine(runner).parseArgs("--config",cfg.toString(),"--mode","hybrid","--policy","llm","--llm-agents","1",
                "--demand","100","--sample-persons","40","--days","5","--concurrency","1","--seed","4711",
                "--population-seed","4711","--informed-share","1","--departure-before-seconds","3600","--departure-after-seconds","900",
                "--traveler-reflection-interval","1","--history-window","3",
                "--output",dest.toString(),"--model",model,"--base-url",baseUrl,"--thinking","disabled","--temperature",Double.toString(temperature),
                "--response-format","json_object","--max-output-tokens","1024","--memory-max-output-tokens","1024",
                "--timeout-seconds",Integer.toString(timeout),"--repair-attempts",Integer.toString(attempts),"--max-requests",Integer.toString(maxRequests));
        runner.llmConfig.apiKey=config.apiKey;
        runner.clientFactory=(cfgModel,tracker)-> {
            LlmClient delegate=mode.equals("mock") ? V9ReasonablenessExperiments::mockComplete : new DeepSeekClient(cfgModel,tracker);
            return new LlmClient() {
                public LlmResponse complete(String role,String system,String user) {return complete(role,system,user,null);}
                public LlmResponse complete(String role,String system,String user,Map<String,Object> schema) {
                    if(role.equals("traveler") && user.contains("SIMULATION DAY 0\n")) {
                        if(!user.contains("\"c0\"") || !user.contains("27574.0")) throw new IllegalStateException("Pre-registered forced day-0 action not available");
                        return new LlmResponse(Json.write(Map.of("routeId",ROUTE,"departureSeconds",DEPARTURE,"reason","Experimentally imposed day-0 action.")),0,0,0,0);
                    }
                    return delegate.complete(role,system,user,schema);
                }
            };
        };
        int exit=runner.call();if(exit!=0) throw new IllegalStateException("MATSim source failed: "+exit);
        Files.writeString(dest.resolve("v9-pilot-provenance.json"),Json.write(Map.of("schemaVersion",1,"mode",mode,
                "modelSettings",config.publicModelSettings(),"forcedDay0",true,"targetTrip",TARGET,
                "forcedRoute",ROUTE,"forcedDepartureSeconds",DEPARTURE)),StandardCharsets.UTF_8);
        return dest;
    }
    private static List<JsonNode> rows(Path p) throws IOException {
        var result=new ArrayList<JsonNode>();try(var reader=JsonlIO.newReader(p)) {String s;while((s=reader.readLine())!=null)if(!s.isBlank())result.add(Json.readTree(s));}return result;
    }
    private static JsonNode pick(List<JsonNode> rows,int day,String trip) {return rows.stream().filter(x->x.path("day").asInt(-1)==day && x.path("tripId").asText().equals(trip)).findFirst().orElseThrow();}
    static MorningRoutePolicy.Context branch(MorningRoutePolicy.Context c,boolean history,boolean memory) {
        return branch(c, history, memory, true);
    }
    static MorningRoutePolicy.Context branch(MorningRoutePolicy.Context c,boolean history,boolean memory,boolean ledger) {
        return new MorningRoutePolicy.Context(c.day(),c.tripId(),c.personId(),c.originPurpose(),c.destinationPurpose(),c.plannedDepartureSeconds(),c.latestArrivalSeconds(),c.personAttributes(),c.schedule(),c.candidates(),history?c.history():List.of(),c.profile(),memory?c.memorySummary():"",memory?c.memorySummaryDay():null,c.information(),c.departureOptions(),ledger?c.ledger():null);
    }
    private static void verifyOnlyMemoryHistory(MorningRoutePolicy.Context a,MorningRoutePolicy.Context b) {
        var x=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(a);var y=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(b);
        x.remove(List.of("history","memorySummary","memorySummaryDay"));y.remove(List.of("history","memorySummary","memorySummaryDay"));
        if(!x.equals(y)) throw new IllegalStateException("Branch changed other visible inputs");
    }
    private boolean runMemory(OutputWriter root) throws Exception {
        Path src=sourceRun==null ? runSource() : sourceRun.toAbsolutePath();Path morning=src.resolve("morning");
        Path provenancePath=src.resolve("v9-pilot-provenance.json");
        if(!Files.isRegularFile(provenancePath)) throw new IllegalArgumentException("Source missing v9-pilot-provenance.json; generate with this Java experiment");
        var provenance=Json.readTree(Files.readString(provenancePath));
        if(!mode.equals(provenance.path("mode").asText()) || !provenance.path("modelSettings").equals(Json.MAPPER.valueToTree(config.publicModelSettings())))
            throw new IllegalArgumentException("Source mode/model settings differ; do not mix mock and API or model configurations");
        JsonNode status=Json.readTree(Files.readString(morning.resolve("status.json")));
        if(!status.path("complete").asBoolean() || status.path("daysCompleted").asInt()<5) throw new IllegalArgumentException("Source run must complete at least five days");
        var contexts=rows(morning.resolve("decision-contexts.jsonl.gz")); var trips=rows(morning.resolve("trips.jsonl.gz"));var executions=rows(morning.resolve("executed-route-checks.jsonl.gz"));
        var decisions=rows(morning.resolve("decisions.jsonl.gz"));var updates=rows(morning.resolve("memory-updates.jsonl.gz"));
        JsonNode trip=pick(trips,0,TARGET),decision=pick(decisions,0,TARGET),check=pick(executions,0,TARGET);
        var d0=Json.MAPPER.treeToValue(pick(contexts,0,TARGET),MorningRoutePolicy.Context.class);
        var chosen=d0.departureOptions().stream().filter(d->d.departureSeconds()==DEPARTURE).flatMap(d->d.routes().stream()).filter(o->o.routeId().equals(ROUTE)).findFirst().orElseThrow();
        boolean verified=trip.path("completedCar").asBoolean() && trip.path("completedOd").asBoolean() && !trip.path("stuck").asBoolean()
                && trip.path("carSeconds").isNumber() && trip.path("routeId").asText().equals(ROUTE)
                && decision.path("selectedDepartureSeconds").asDouble()==DEPARTURE
                && check.path("completedRouteMatches").asBoolean() && check.path("planTimeMatchesChoice").asBoolean();
        double excess=trip.path("carSeconds").asDouble()-chosen.expectedCarSeconds();
        Map<String,Object> fact=new LinkedHashMap<>();fact.put("sourceRun",src.toString());fact.put("day0Trip",trip);fact.put("day0Decision",decision);fact.put("day0Execution",check);fact.put("estimatedCarSeconds",chosen.expectedCarSeconds());fact.put("actualExcessSeconds",excess);fact.put("minExcessSeconds",minExcess);fact.put("interventionVerified",verified && excess>=minExcess);fact.put("forcedAction",true);fact.put("forcedActionReason",decision.path("reason").asText());
        Files.createDirectories(output.resolve("memory"));Files.writeString(output.resolve("memory/source-verification.json"),Json.write(fact),StandardCharsets.UTF_8);
        if(!verified || excess<minExcess) {root.appendLine("stages.jsonl",Map.of("stage","memory","complete",false,"reason","actual severe delay or execution not verified","actualExcessSeconds",excess));return false;}
        if(!decision.path("reason").asText().equals("Experimentally imposed day-0 action.")) throw new IllegalArgumentException("Source lacks documented forced first-day action");
        var c1=Json.MAPPER.treeToValue(pick(contexts,1,TARGET),MorningRoutePolicy.Context.class);
        var c4=Json.MAPPER.treeToValue(pick(contexts,4,TARGET),MorningRoutePolicy.Context.class);
        for(var c:List.of(c1,c4)) {
            if(c.memorySummary().isBlank()) throw new IllegalArgumentException("Source summary is empty");
            if(c.information()==null || !c.information().personalPayments().isEmpty() || c.candidates().stream().anyMatch(o->o.pricingKnowledge()==null || !o.pricingKnowledge().source().equals("charging_disabled")))
                throw new IllegalArgumentException("Memory pilot requires no charging or payment side channel");
            if(c.departureOptions().stream().flatMap(d->d.routes().stream()).anyMatch(o->o.pricingKnowledge()==null || !o.pricingKnowledge().source().equals("charging_disabled")))
                throw new IllegalArgumentException("Departure options contain a pricing side channel");
            if(c.candidates().size()<2 || c.departureOptions().size()<2) throw new IllegalArgumentException("Memory pilot needs alternatives");
            if(!c.memorySummary().equals(pick(updates,c.day()-1,TARGET).path("memory").asText())) throw new IllegalArgumentException("Source memory chain does not match preceding update");
        }
        if(MorningPromptRenderer.decisionVisibleFacts(c1).stream().noneMatch(e->e.day()==0)
                || MorningPromptRenderer.decisionVisibleFacts(c4).stream().anyMatch(e->e.day()==0))
            throw new IllegalArgumentException("Recent-fact window mismatch");
        var actualHistory=c1.history().stream().filter(e->e.day()==0).findFirst().orElseThrow();
        if(!actualHistory.routeId().equals(trip.path("routeId").asText()) || actualHistory.carSeconds()==null
                || actualHistory.carSeconds()!=trip.path("carSeconds").asDouble() || actualHistory.arrivalSeconds()==null
                || actualHistory.arrivalSeconds()!=trip.path("arrivalSeconds").asDouble())
            throw new IllegalArgumentException("Visible day-0 experience does not match MATSim trip facts");
        for(int day=1;day<=4;day++) {
            var c=Json.MAPPER.treeToValue(pick(contexts,day,TARGET),MorningRoutePolicy.Context.class);
            if(!c.memorySummary().equals(pick(updates,day-1,TARGET).path("memory").asText())) throw new IllegalArgumentException("Source summary chain is broken on day "+day);
        }
        boolean ok=true;
        try(var writer=new OutputWriter(output.resolve("memory/branches"))) {
            var policy=new MorningLlmPolicy(live(),writer,attempts,120,config.publicModelSettings(),budget);
            Map<String,MorningRoutePolicy.Decision> selected=new LinkedHashMap<>();
            var specs=List.of(new Object[]{"day1_H+M+",c1,true,true},new Object[]{"day1_H+M-",c1,true,false},new Object[]{"day1_H-M+",c1,false,true},new Object[]{"day1_H-M-",c1,false,false},new Object[]{"day4_H+M+",c4,true,true},new Object[]{"day4_H+M-",c4,true,false});
            for(var spec:specs) {
                String name=(String)spec[0];var original=(MorningRoutePolicy.Context)spec[1];var altered=branch(original,(Boolean)spec[2],(Boolean)spec[3]);verifyOnlyMemoryHistory(original,altered);
                var r=new LinkedHashMap<String,Object>();r.put("branch",name);r.put("sourceDay",original.day());r.put("context",altered);r.put("prompt",new MorningPromptRenderer().render(altered));
                try {var action=policy.choose(altered);selected.put(name,action);r.put("decision",action);r.put("status","completed");}catch(RuntimeException e){r.put("status","failed");r.put("error",e.toString());ok=false;}
                writer.appendLine("branches.jsonl",r);writer.flush();
            }
            for(var pair:List.of(new String[]{"day1_H+M+","day1_H+M-"},new String[]{"day1_H-M+","day1_H-M-"},new String[]{"day4_H+M+","day4_H+M-"})) {
                var a=selected.get(pair[0]);var b=selected.get(pair[1]);var result=new LinkedHashMap<String,Object>();
                result.put("memoryPresent",pair[0]);result.put("memoryAbsent",pair[1]);result.put("bothCompleted",a!=null && b!=null);
                if(a!=null && b!=null) {result.put("routeChanged",!a.routeId().equals(b.routeId()));result.put("departureDifferenceSeconds",a.departureSeconds()-b.departureSeconds());}
                writer.appendLine("comparisons.jsonl",result);
            }
            writer.writeDoc("audit-instructions.json",Map.of("day0Outcome",trip,"day0Memory",pick(updates,0,TARGET).path("memory").asText(),"day3Memory",pick(updates,3,TARGET).path("memory").asText(),"oldEventRetention","manual review required; no automatic claim of retention", "factsAudit","compare summary claims to day0–3 MATSim outcomes; classify contradictions, unsupported assertions, forecast/actual confusion and omissions"));
        }
        root.appendLine("stages.jsonl",Map.of("stage","memory","complete",ok,"interventionVerified",true,"branches",6,"summaryAudit","manual_required"));return ok;
    }
}
