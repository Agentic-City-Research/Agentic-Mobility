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
        Path file = parameters("reject");
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
        var settings = new MorningExperiment();
        var cli = new CommandLine(settings);
        cli.parseArgs("--policy", "mnl-joint", "--llm-agents", "1", "--config", "missing.xml");
        assertTrue(assertThrows(IllegalArgumentException.class, settings::call).getMessage().contains("--mnl-parameters"));
    }

    @Test void costsUseOnlyRecentMatchingPersonalFactsAndPreserveAllJointActions() throws Exception {
        var history = List.of(experience(0, 25200, 99999d, false, 100d),
                experience(2, 25200, 1500d, false, 2d), experience(3, 25200, null, true, 1d),
                experience(4, 26100, 3000d, false, 3d));
        var context = context(5, history, false);
        try (var out = new OutputWriter(directory.resolve("costs"))) {
            var policy = new MorningMnlPolicy(MorningMnlParameters.load(parameters("reject")), 5, 3, out);
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
            var policy = new MorningMnlPolicy(MorningMnlParameters.load(parameters("reject")), 6021, 3, out);
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

    @Test void unknownPricesRequireAnExplicitBeliefAndNeverMutateTheSharedContext() throws Exception {
        try (var out = new OutputWriter(directory.resolve("prices"))) {
            var rejecting = new MorningMnlPolicy(MorningMnlParameters.load(parameters("reject")), 3, 3, out);
            assertThrows(IllegalArgumentException.class, () -> rejecting.choose(context(0, List.of(), true)));
            var believing = new MorningMnlPolicy(MorningMnlParameters.load(parameters("personal-with-prior")), 3, 3, out);
            believing.choose(context(0, List.of(), true));
            var experienced = context(5, List.of(experience(4, 25200, 1200d, false, 2.5)), true);
            believing.choose(experienced);
            assertNull(experienced.candidates().getFirst().estimatedToll());
            assertTrue(experienced.information().personalPayments().isEmpty());
        }
        var audits = JsonlTestSupport.readLines(directory.resolve("prices/baseline-choices.jsonl")).stream().map(Json::readTree).toList();
        assertEquals(.2, action(audits.getFirst(), "c0", 25200).get("estimatedToll").asDouble(), 1e-12);
        var learned = action(audits.getLast(), "c0", 25200);
        assertEquals(2.5, learned.get("estimatedToll").asDouble());
        assertTrue(learned.get("currentTollUnknown").asBoolean());
        assertEquals("recent_own_route_payments_not_current_tariff", learned.get("tollEstimateSource").asText());
        assertEquals(List.of(4), ints(learned.get("paymentExperienceDays")));
        assertEquals("explicit_per_link_prior", action(audits.getLast(), "c1", 25200).get("tollEstimateSource").asText());
    }

    @Test void qsimExecutesJointBaselineWithoutAnyModelAndStartsFromTheSameLlmContext() throws Exception {
        var fixture = new MorningPricingTest(); fixture.directory = directory;
        Path config = fixture.writeScenario(), baseline = directory.resolve("baseline"), llm = directory.resolve("llm");
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = null;
        settings.clientFactory = (c,u) -> { throw new AssertionError("Baseline must not create a model client"); };
        assertEquals(0, run(settings, config, baseline, "--policy", "mnl-joint", "--mnl-parameters", parameters("reject").toString(), "--days", "4"));
        var audits = JsonlTestSupport.readLines(baseline.resolve("morning/baseline-choices.jsonl.gz"));
        assertEquals(4, audits.size());
        assertEquals(9, Json.readTree(audits.getFirst()).get("actions").size(), "One route still has nine time options");
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
        assertEquals("structured-recent-experience", manifest.get("memoryMode").asText());
        assertTrue(manifest.get("memoryTemplate").isNull());
        assertTrue(manifest.get("memoryReflectionInputs").isEmpty());
        assertTrue(manifest.get("singleCandidateTreatment").asText().contains("every legal departure-time option"));
        assertTrue(manifest.at("/departureProtocol/enabledForBaselineTargetTrips").asBoolean());
    }

    @Test void pricingRejectionHappensBeforeFreeDaysOrOutputAndValidationExportsProvenance() throws Exception {
        var fixture = new MorningPricingTest(); fixture.directory = directory;
        Path config = fixture.writeScenario(), out = directory.resolve("reject-early");
        Path toll = directory.resolve("toll.json"); Files.writeString(toll, "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":108000,\"amount\":2}]}");
        assertNotEquals(0, run(new MorningExperiment(), config, out, "--policy", "mnl-joint", "--days", "1", "--mnl-parameters", parameters("reject").toString(), "--fixed-toll-policy", toll.toString()));
        assertFalse(Files.exists(out));
        Path valid = directory.resolve("validate");
        assertEquals(0, run(new MorningExperiment(), config, valid, "--policy", "mnl-joint", "--days", "1", "--mnl-parameters", parameters("reject").toString(), "--validate-only"));
        assertTrue(Files.isRegularFile(valid.resolve("morning/baseline-parameters.json")));
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
                "--policy","mnl-joint","--llm-agents","all","--mnl-parameters",parameters("reject").toString(),
                "--manager","--informed-share","1","--days","2","--concurrency","2","--seed","9261"));
        assertEquals(2, calls.get());
        assertEquals(2, JsonlTestSupport.readLines(out.resolve("morning/baseline-choices.jsonl.gz")).size());
        var publicContext = Json.readTree(Files.readString(out.resolve("morning/manager-public-environment.json")));
        assertTrue(publicContext.at("/publicEnvironment/travelerResponse/baselineJointDepartureAndRouteChoiceEnabled").asBoolean());
        assertFalse(publicContext.at("/publicEnvironment/travelerResponse/llmJointDepartureAndRouteChoiceEnabled").asBoolean());
    }

    @Test void legacyMnlAndManagerUseStableCohortOrderInsteadOfManagerWorkers() throws Exception {
        var fixture = new MorningPricingTest(); fixture.directory = directory;
        Path config = fixture.writeScenario();
        var scenario = org.matsim.core.scenario.ScenarioUtils.loadScenario(
                org.matsim.core.config.ConfigUtils.loadConfig(config.toString()));
        var network = scenario.getNetwork();
        var road = network.getLinks().get(org.matsim.api.core.v01.Id.createLinkId("road"));
        var alternative = network.getFactory().createLink(org.matsim.api.core.v01.Id.createLinkId("alternative"), road.getFromNode(), road.getToNode());
        alternative.setLength(120); alternative.setFreespeed(10); alternative.setCapacity(1000);
        alternative.setNumberOfLanes(1); alternative.setAllowedModes(Set.of("car")); network.addLink(alternative);
        var population = scenario.getPopulation(); var factory = population.getFactory();
        var original = population.getPersons().values().iterator().next();
        for (int i = 0; i < 31; i++) {
            var person = factory.createPerson(org.matsim.api.core.v01.Id.createPersonId("copy-" + i));
            var plan = factory.createPlan();
            org.matsim.core.population.PopulationUtils.copyFromTo(original.getSelectedPlan(), plan);
            person.addPlan(plan); population.addPerson(person);
        }
        new org.matsim.core.network.io.NetworkWriter(network).write(directory.resolve("network.xml").toString());
        new org.matsim.core.population.io.PopulationWriter(population).write(directory.resolve("population.xml").toString());
        MorningJointTestSupport.useBundledDtd(directory.resolve("network.xml"));
        MorningJointTestSupport.useBundledDtd(directory.resolve("population.xml"));
        Map<String, String> first = null;
        for (int repetition = 0; repetition < 2; repetition++) {
            var settings = new MorningExperiment(); settings.llmConfig.apiKey = "offline-manager-only";
            settings.clientFactory = (c,u) -> (role,system,user) -> {
                assertEquals("manager-memory", role);
                return new LlmResponse("{\"memory\":\"Observe the public traffic results.\"}",0,0,0,0);
            };
            var baseline = MorningRoutePolicy.baseline("mnl", 268, .3);
            var threads = Collections.synchronizedList(new ArrayList<String>());
            settings.policyOverride = context -> {
                threads.add(Thread.currentThread().getName());
                return baseline.choose(context);
            };
            Path out = directory.resolve("legacy-manager-" + repetition);
            assertEquals(0, new CommandLine(settings).execute("--config", config.toString(), "--output", out.toString(),
                    "--policy", "mnl", "--llm-agents", "all", "--manager", "--days", "1", "--concurrency", "8", "--seed", "268"));
            var choices = new TreeMap<String, String>();
            for (String line : JsonlTestSupport.readLines(out.resolve("morning/decisions.jsonl.gz"))) {
                var row = Json.readTree(line); choices.put(row.get("tripId").asText(), row.get("routeId").asText());
            }
            assertEquals(32, choices.size()); assertEquals(32, threads.size());
            assertEquals(2, new HashSet<>(choices.values()).size(), "Fixture should exercise random route selection");
            assertTrue(threads.stream().noneMatch(name -> name.startsWith("morning-llm")), "Offline choices must not share the manager worker pool");
            if (first == null) first = choices; else assertEquals(first, choices, "Same seed and context must assign the same choices");
        }
    }

    private int run(MorningExperiment settings, Path config, Path out, String... extra) {
        var args = new ArrayList<>(List.of("--config",config.toString(),"--output",out.toString(),"--mode","hybrid","--llm-agents","all",
                "--seed","9261","--population-seed","6021","--informed-share","0","--concurrency","1"));
        args.addAll(Arrays.asList(extra)); return new CommandLine(settings).execute(args.toArray(String[]::new));
    }
    private Path parameters(String mode) throws Exception {
        var doc = (ObjectNode)Json.readTree(Files.readString(Path.of("config/baselines/mnl-joint.example.json")));
        ((ObjectNode)doc.at("/parameters/unknownTollMode")).put("value", mode);
        Path file = directory.resolve("parameters-" + mode + ".json"); Files.writeString(file, doc.toPrettyString()); return file;
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
