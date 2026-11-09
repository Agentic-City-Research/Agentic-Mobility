package org.matsim.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MorningMnlPolicyTest {
    @TempDir Path directory;

    @Test void explicitSourcesUnitsAndStrictJsonAreRequiredAndLoadedValuesAreFrozen() throws Exception {
        Path file = parameters();
        var loaded = MorningMnlParameters.load(file);
        var report = Json.MAPPER.valueToTree(loaded.report(7, 3));
        assertFalse(report.at("/document/calibrated").asBoolean());
        assertEquals(64, report.at("/sourceFile/sha256").asText().length());
        String good = Files.readString(file);
        var invalids = new ArrayList<String>(List.of(good + "{}", "{\"schemaVersion\":1," + good.substring(good.indexOf('{') + 1)));
        for (String field : List.of("source", "unit", "value")) {
            ObjectNode doc = (ObjectNode)Json.readTree(good);
            var p = (ObjectNode)doc.at("/parameters/timeWeight");
            if (field.equals("source")) p.put(field, " ");
            if (field.equals("unit")) p.put(field, "utility/second");
            if (field.equals("value")) p.put(field, -1);
            invalids.add(doc.toString());
        }
        ObjectNode invalidBlend = (ObjectNode)Json.readTree(good);
        ((ObjectNode)invalidBlend.at("/parameters/experienceWeight")).put("value", 1.1);
        invalids.add(invalidBlend.toString());
        for (String invalid : invalids) {
            Files.writeString(file, invalid);
            assertThrows(IllegalArgumentException.class, () -> MorningMnlParameters.load(file));
        }
        assertEquals(.3, loaded.timeWeight);
        assertEquals(report, Json.MAPPER.valueToTree(loaded.report(7, 3)));

    }

    @Test void costsUseOnlyRecentMatchingPersonalFactsAndPreserveAllJointActions() throws Exception {
        var history = List.of(experience(0, 25200, 99999d, false, 100d),
                experience(2, 25200, 1500d, false, 2d), experience(3, 25200, null, true, 1d),
                experience(4, 26100, 3000d, false, 3d));
        var context = context(5, history, false);
        try (var out = new OutputWriter(directory.resolve("costs"))) {
            var policy = new MorningMnlPolicy(MorningMnlParameters.load(parameters()), 5, 3, out);
            MorningRoutePolicy.validate(context, policy.choose(context));
        }
        var audit = first(directory.resolve("costs/baseline-choices.jsonl"));
        assertEquals(List.of(2,3,4), ints(audit.get("historyDaysUsed")));
        assertEquals(4, audit.get("actions").size());
        assertEquals(1, stream(audit.get("actions")).stream().filter(a -> a.get("selected").asBoolean()).count());
        assertEquals(1, stream(audit.get("actions")).stream().mapToDouble(a -> a.get("probability").asDouble()).sum(), 1e-12);
        var original = action(audit, "c0", 25200);
        assertEquals(1050, original.get("estimatedCarSeconds").asDouble());
        assertEquals(26310, original.get("predictedArrivalSeconds").asDouble());
        assertEquals(310d/60, original.get("predictedLateMinutes").asDouble(), 1e-12);
        assertEquals(List.of(2), ints(original.get("timeExperienceDays")));
        assertEquals(List.of(3), ints(original.get("excludedIncompleteExperienceDays")));
        assertEquals(1.5, original.get("estimatedTotalCost").asDouble());
        assertEquals(-.3*1050/60 - .3*1.5 - .9*310/60, original.get("utility").asDouble(), 1e-12);
        assertEquals(600, action(audit, "c0", 24300).get("estimatedCarSeconds").asDouble());
        assertTrue(original.get("probability").asDouble() > 0, "Predicted lateness is not an illegal action");
    }

    @Test void seededChoicesIgnoreDisplayOrderOtherCallsAndQualitativeProfileOrSummaryChanges() throws Exception {
        var context = context(0, List.of(), false);
        try (var out = new OutputWriter(directory.resolve("seeds"))) {
            var policy = new MorningMnlPolicy(MorningMnlParameters.load(parameters()), 6021, 3, out);
            var first = policy.choose(context);
            var nextDay = context(1, List.of(), false);
            var nextChoice = policy.choose(nextDay);
            var altered = new MorningRoutePolicy.Context(nextDay.day(), nextDay.tripId(), nextDay.personId(),
                    nextDay.originPurpose(), nextDay.destinationPurpose(), nextDay.plannedDepartureSeconds(), nextDay.latestArrivalSeconds(),
                    nextDay.personAttributes(), nextDay.schedule(), nextDay.candidates(), nextDay.history(),
                    new Profile(Profile.Income.LOW, Profile.DecisionStyle.AGGRESSIVE, Profile.WorkTimeliness.MUST_ON_TIME),
                    "Qualitative memory is not a numeric preference estimate.", 0, nextDay.information(), nextDay.departureOptions());
            assertEquals(nextChoice, policy.choose(altered));
            assertEquals(first, policy.choose(MorningPromptRenderer.order(context, "reverse", 9)));
            assertEquals(first, policy.choose(MorningPromptRenderer.order(context, "shuffle", 77)));
        }
        var audits = JsonlTestSupport.readLines(directory.resolve("seeds/baseline-choices.jsonl")).stream().map(Json::readTree).toList();
        assertEquals(audits.getFirst().get("actions"), audits.getLast().get("actions"));
        assertNotEquals(MorningMnlPolicy.localSeed(6021, 0, "p/0"), MorningMnlPolicy.localSeed(6021, 0, "p/1"));
    }

    @Test void fiveDayHistoryIncludesFifthPriorDayButExcludesOlderDays() throws Exception {
        var history = List.of(experience(0,25200,1000d,false,0d), experience(1,25200,1200d,false,0d),
                experience(2,25200,1400d,false,0d), experience(5,25200,1600d,false,0d));
        try (var out = new OutputWriter(directory.resolve("five-day"))) {
            new MorningMnlPolicy(MorningMnlParameters.load(parameters()),5,5,out).choose(context(6,history,false));
        }
        assertEquals(List.of(1,2,5), ints(first(directory.resolve("five-day/baseline-choices.jsonl")).get("historyDaysUsed")));
    }

    @Test void qsimExecutesJointBaselineWithoutAnyModelAndStartsFromTheSameLlmContext() throws Exception {
        var fixture = new MorningPricingTest(); fixture.directory = directory;
        Path config = fixture.writeScenario(), baseline = directory.resolve("baseline"), llm = directory.resolve("llm");
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = null;
        settings.clientFactory = (c,u) -> { throw new AssertionError("Baseline must not create a model client"); };
        assertEquals(0, run(settings, config, baseline, "--policy", "mnl", "--mnl-parameters", parameters().toString(), "--days", "4"));
        var audits = JsonlTestSupport.readLines(baseline.resolve("morning/baseline-choices.jsonl.gz"));
        assertEquals(4, audits.size());
        assertEquals(6, Json.readTree(audits.getFirst()).get("actions").size(), "One route retains six default departure options");
        assertTrue(Json.readTree(audits.get(1)).get("historyDaysUsed").size() > 0);
        var status = Json.readTree(Files.readString(baseline.resolve("morning/status.json")));
        assertTrue(status.get("complete").asBoolean()); assertEquals(0, status.get("apiCalls").asInt());
        assertTrue(JsonlTestSupport.readLines(baseline.resolve("morning/executed-route-checks.jsonl.gz")).stream().map(Json::readTree)
                .allMatch(r -> r.get("planTimeMatchesChoice").asBoolean() && r.get("followsSelectedRoutePrefix").asBoolean()));
        assertEquals(4, JsonlTestSupport.readLines(baseline.resolve("morning/experiences.jsonl.gz")).size());
        assertFalse(Files.exists(baseline.resolve("morning/memory-updates.jsonl.gz")));
        var llmSettings = new MorningExperiment(); llmSettings.llmConfig.apiKey = "offline-test";
        llmSettings.clientFactory = (c,u) -> (role,system,user) -> new LlmResponse(role.equals("traveler_memory")
                ? "{\"memory\":\"Remember my own trip.\"}" : MorningJointTestSupport.withDeparture("{\"routeId\":\"c0\",\"reason\":\"Test choice\"}", user), 0,0,0,0);
        assertEquals(0, run(llmSettings, config, llm, "--policy", "llm", "--days", "1"));
        var baselineContext = first(baseline.resolve("morning/baseline-contexts.jsonl.gz"));
        var llmContext = first(llm.resolve("morning/decision-contexts.jsonl.gz"));
        assertEquals(llmContext, baselineContext, "Day-zero information/action context must be identical to LLM");
        var manifest = Json.readTree(Files.readString(baseline.resolve("morning/manifest.json")));
        assertEquals("structured-recent-experience-and-personal-toll-observations", manifest.get("memoryMode").asText());
        assertTrue(manifest.get("memoryTemplate").isNull());
        assertTrue(manifest.get("memoryReflectionInputs").isEmpty());
        assertTrue(manifest.get("singleCandidateTreatment").asText().contains("every legal departure-time option"));
        assertTrue(manifest.at("/departureProtocol/enabledForMnlTargetTrips").asBoolean());
    }

    @Test void managerCanObserveTheJointBaselineWithoutCallingATravelerModel() throws Exception {
        var fixture = new MorningPricingTest(); fixture.directory = directory;
        Path config = fixture.writeScenario(), out = directory.resolve("baseline-manager");
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = "offline-manager-only";
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        settings.clientFactory = (c,u) -> (role,system,user) -> {
            assertEquals("manager-memory", role);
            calls.incrementAndGet();
            return new LlmResponse("{\"memory\":\"Observe the public traffic results under the effective policy.\"}",0,0,0,0);
        };
        assertEquals(0, new CommandLine(settings).execute("--config",config.toString(),"--output",out.toString(),
                "--policy","mnl","--llm-agents","all","--mnl-parameters",parameters().toString(),
                "--manager","--informed-share","1","--days","2","--concurrency","2","--seed","9261"));
        assertEquals(2, calls.get());
        assertEquals(2, JsonlTestSupport.readLines(out.resolve("morning/baseline-choices.jsonl.gz")).size());
        var publicContext = Json.readTree(Files.readString(out.resolve("morning/manager-public-environment.json")));
        assertTrue(publicContext.at("/publicEnvironment/travelerResponse/mnlJointDepartureAndRouteChoiceEnabled").asBoolean());
        assertFalse(publicContext.at("/publicEnvironment/travelerResponse/llmJointDepartureAndRouteChoiceEnabled").asBoolean());
    }

    @Test void fixedTollActuallyChargesBothGroupsWhileOnlyInformedKnowItInitially() throws Exception {
        var fixture = new MorningInformationTest(); fixture.directory=directory;
        Path config=fixture.writeScenario(), output=directory.resolve("fixed-toll-mnl"), toll=directory.resolve("fixed-toll.json");
        Files.writeString(toll,"{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":108000,\"amount\":2}]}");
        var settings=new MorningExperiment(); settings.llmConfig.apiKey=null;
        settings.clientFactory=(c,u)->{throw new AssertionError("Fixed-toll MNL must not call a model");};
        assertEquals(0,new CommandLine(settings).execute("--config",config.toString(),"--output",output.toString(),
                "--policy","mnl","--llm-agents","all","--fixed-toll-policy",toll.toString(),"--fixed-toll-free-days","0",
                "--informed-share","0.5","--days","3","--seed","268","--max-requests","0"));
        var audits=JsonlTestSupport.readLines(output.resolve("morning/baseline-choices.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(6,audits.size());
        for(var a:audits) if(a.path("day").asInt()==0) {
            boolean informed=a.path("informationGroup").asText().equals("informed");
            for(var option:a.path("actions")) assertEquals(informed ? 2d : 0d,option.path("estimatedToll").asDouble());
        }
        var trips=JsonlTestSupport.readLines(output.resolve("morning/trips.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(6,trips.size());
        assertTrue(trips.stream().allMatch(t -> t.path("tollPaid").asDouble()==2d));
        assertEquals(0,Json.readTree(Files.readString(output.resolve("morning/status.json"))).path("apiCalls").asInt());
    }

    @Test void tollBeliefsAffectUtilityButUninformedNeverSeeTheNewPolicy() throws Exception {
        var network = org.matsim.core.network.NetworkUtils.createNetwork();
        var tolls = new MorningTollSystem(network, org.matsim.core.events.EventsUtils.createEventsManager(), null);
        var hidden = new MorningTravelerInformation(List.of("p"), 0, 1, 3600);
        var informed = new MorningTravelerInformation(List.of("p"), 1, 1, 3600);
        tolls.setPolicy(tollPolicy(0, 2), 1);
        Path folder = directory.resolve("toll-beliefs");
        try (var out = new OutputWriter(folder)) {
            var policy = new MorningMnlPolicy(MorningMnlParameters.load(parameters()), 3, 3, out);
            policy.choose(tollContext(hidden,tolls,0));
            hidden.observe(0,"p",new MorningTollSystem.Receipt("road",25200,2,"policy",1));
            tolls.setPolicy(tollPolicy(1,9),2);
            policy.choose(tollContext(hidden,tolls,1));
            policy.choose(tollContext(informed,tolls,1));
            hidden.observe(1,"p",new MorningTollSystem.Receipt("road",25200,9,"policy",2));
            policy.choose(tollContext(hidden,tolls,2));
            hidden.observe(2,"p",new MorningTollSystem.Receipt("road",25200,0,"policy",3));
            policy.choose(tollContext(hidden,tolls,3));
        }
        var audits=JsonlTestSupport.readLines(folder.resolve("baseline-choices.jsonl")).stream().map(Json::readTree).toList();
        double[] tollsExpected={0,2,9,9,0};
        for(int i=0;i<tollsExpected.length;i++) {
            var a=action(audits.get(i),"c0",25200);
            assertEquals(tollsExpected[i],a.path("estimatedToll").asDouble());
            assertEquals(-.3*(1+tollsExpected[i]),a.at("/utilityComponents/money").asDouble(),1e-12);
            assertEquals(i!=2,a.path("currentTollUnknown").asBoolean());
        }
        assertEquals(1,action(audits.getFirst(),"c0",25200).path("unobservedTollLinks").size());
        assertTrue(action(audits.get(1),"c0",25200).path("unobservedTollLinks").isEmpty());
    }

    @Test void priceChangesChoiceProbabilitiesUnderIdenticalTravelTimes() throws Exception {
        var tolls=new MorningTollSystem(org.matsim.core.network.NetworkUtils.createNetwork(),org.matsim.core.events.EventsUtils.createEventsManager(),null);
        tolls.setPolicy(tollPolicy(0,2),1);
        var informed=new MorningTravelerInformation(List.of("p"),1,1,3600);
        var hidden=new MorningTravelerInformation(List.of("p"),0,1,3600);
        Path folder=directory.resolve("price-choice");
        try(var out=new OutputWriter(folder)) {
            var policy=new MorningMnlPolicy(MorningMnlParameters.load(parameters()),3,3,out);
            policy.choose(twoRouteContext(informed,tolls)); policy.choose(twoRouteContext(hidden,tolls));
        }
        var a=JsonlTestSupport.readLines(folder.resolve("baseline-choices.jsonl")).stream().map(Json::readTree).toList();
        double awarePriced=action(a.get(0),"c0",25200).path("probability").asDouble();
        double awareFree=action(a.get(0),"c1",25200).path("probability").asDouble();
        assertTrue(awarePriced < awareFree);
        assertEquals(.5,action(a.get(1),"c0",25200).path("probability").asDouble(),1e-12);
    }

    private static TollPolicy tollPolicy(int day,double amount) {
        return new TollPolicy(List.of(new TollPolicy.TollRule(org.matsim.api.core.v01.Id.createLinkId("road"),0,108000,amount)),day);
    }
    private MorningRoutePolicy.Context tollContext(MorningTravelerInformation info, MorningTollSystem tolls, int day) {
        var knowledge=info.pricingForLlm("p",List.of("road"),List.of(25200d),1,tolls,day);
        double estimated=knowledge.knownTollSubtotal();
        var option=new MorningRoutePolicy.Option("c0",List.of("road"),600,25800,1000,1+estimated,"static_free_flow",0,0,
                null,null,estimated,info.informed("p")?tolls.state().policyId():null,info.informed("p")?tolls.state().version():0,knowledge);
        var departures=List.of(new MorningRoutePolicy.DepartureOption(25200,List.of(option)));
        return new MorningRoutePolicy.Context(day,"p/0","p","home","work",25200,26000d,Map.of(),Map.of(),
                List.of(option),List.of(),null,"",null,info.visibleForLlm("p",day,tolls),departures);
    }
    private MorningRoutePolicy.Context twoRouteContext(MorningTravelerInformation info, MorningTollSystem tolls) {
        var options=new ArrayList<MorningRoutePolicy.Option>();
        for(String[] pair:List.of(new String[]{"c0","road"},new String[]{"c1","free"})) {
            var k=info.pricingForLlm("p",List.of(pair[1]),List.of(25200d),1,tolls,0);
            double estimate=k.knownTollSubtotal();
            options.add(new MorningRoutePolicy.Option(pair[0],List.of(pair[1]),600,25800,1000,1+estimate,"static_free_flow",0,0,
                    null,null,estimate,info.informed("p")?tolls.state().policyId():null,info.informed("p")?tolls.state().version():0,k));
        }
        return new MorningRoutePolicy.Context(0,"p/0","p","home","work",25200,26000d,Map.of(),Map.of(),options,List.of(),null,"",null,
                info.visibleForLlm("p",0,tolls),List.of(new MorningRoutePolicy.DepartureOption(25200,options)));
    }

    private int run(MorningExperiment settings, Path config, Path out, String... extra) {
        var args = new ArrayList<>(List.of("--config",config.toString(),"--output",out.toString(),"--mode","hybrid","--llm-agents","all",
                "--seed","9261","--population-seed","6021","--informed-share","0","--concurrency","1"));
        args.addAll(Arrays.asList(extra)); return new CommandLine(settings).execute(args.toArray(String[]::new));
    }
    private Path parameters() throws Exception {
        Path file = directory.resolve("parameters.json");
        if (!Files.exists(file)) Files.copy(Path.of("config/baselines/mnl-joint.example.json"), file);
        return file;
    }
    private MorningRoutePolicy.Context context(int day, List<MorningMemory.Experience> history, boolean unknown) {
        var departures = List.of(24300d, 25200d).stream().map(d -> new MorningRoutePolicy.DepartureOption(d,
                List.of(option("c0", d, 600, unknown), option("c1", d, 900, unknown)))).toList();
        return new MorningRoutePolicy.Context(day,"p/0","p","home","work",25200,26000d,Map.of(),Map.of(),
                departures.getLast().routes(),history,null,"",null,new MorningRoutePolicy.Information("uninformed",false,false,3600,List.of()),departures);
    }
    private MorningRoutePolicy.Option option(String id, double departure, double duration, boolean unknown) {
        var links = List.of("origin", id);
        var knowledge = new MorningRoutePolicy.PricingKnowledge("uninformed",unknown ? "bounded_personal_memory" : "charging_disabled",
                1.5,0,unknown ? links : List.of(),List.of(),List.of());
        return new MorningRoutePolicy.Option(id,links,duration,departure+60+duration,1000,unknown ? null : 1.5,
                "static_free_flow",0,0,null,null,unknown ? null : 0d,null,0,knowledge);
    }
    private MorningMemory.Experience experience(int day, double departure, Double time, boolean stuck, Double toll) {
        return new MorningMemory.Experience(day,"c0",time,time == null ? null : departure+time,0d,stuck,departure,
                time,toll,departure,List.of());
    }
    private JsonNode first(Path file) throws Exception { return Json.readTree(JsonlTestSupport.readLines(file).getFirst()); }
    private List<JsonNode> stream(JsonNode node) { var rows = new ArrayList<JsonNode>(); node.forEach(rows::add); return rows; }
    private List<Integer> ints(JsonNode node) { return stream(node).stream().map(JsonNode::asInt).toList(); }
    private JsonNode action(JsonNode audit, String route, double departure) {
        return stream(audit.get("actions")).stream().filter(a -> a.get("routeId").asText().equals(route)
                && a.get("departureSeconds").asDouble() == departure).findFirst().orElseThrow();
    }
}
