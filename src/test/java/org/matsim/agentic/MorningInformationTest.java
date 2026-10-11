package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.*;
import org.matsim.core.config.groups.*;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MorningInformationTest {
    @TempDir Path directory;

    @Test void splitIsStableBalancedPersonLevelAndRejectsInvalidParameters() {
        var ids = List.of("a", "b", "c", "d");
        var first = new MorningTravelerInformation(ids, .5, 4711, 3600);
        var reverse = new ArrayList<>(ids); Collections.reverse(reverse);
        var second = new MorningTravelerInformation(reverse, .5, 4711, 3600);
        assertEquals(first.assignments(), second.assignments());
        assertEquals(2, first.assignments().values().stream().filter(v -> v).count());
        assertEquals("background", first.group("outside"));
        assertTrue(new MorningTravelerInformation(ids, 1, 0, 3600).assignments().values().stream().allMatch(v -> v));
        assertTrue(new MorningTravelerInformation(ids, 0, 0, 3600).assignments().values().stream().noneMatch(v -> v));
        assertThrows(IllegalArgumentException.class, () -> new MorningTravelerInformation(ids, Double.NaN, 0, 3600));
        assertThrows(IllegalArgumentException.class, () -> new MorningTravelerInformation(ids, .5, 0, 0));
    }

    @Test void allScopeIncludesBackgroundAndValidationMakesNoApiCalls() throws Exception {
        Path config = writeScenario(), out = directory.resolve("validation");
        var settings = new MorningExperiment(); settings.clientFactory = (cfg,usage) -> (role,system,user) -> {
            fail("Validation must not make API calls"); return response("{}");
        };
        assertEquals(0, new CommandLine(settings).execute("--config", config.toString(), "--output", out.toString(),
                "--manager", "--days", "52", "--llm-agents", "0", "--information-scope", "all", "--informed-share", "0.5", "--validate-only"));
        var manifest = Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
        assertEquals(0, manifest.get("llmPersons").asInt());
        assertEquals("all", manifest.get("travelerInformation").get("scope").asText());
        assertEquals(1, manifest.get("travelerInformation").get("informedPersons").asInt());
        assertEquals(1, manifest.get("travelerInformation").get("uninformedPersons").asInt());
        for (var row : rows(out.resolve("morning/information-groups.jsonl.gz"))) assertFalse(row.get("controlled").asBoolean());
    }

    @Test void oldSavedContextsRemainReadableAndDisabledChargingIsExplicit() throws Exception {
        var legacyOption = new MorningRoutePolicy.Option("c0",List.of("road"),10,25210,100,1,"free_flow_initial_day",0,0);
        var legacy = new MorningRoutePolicy.Context(1,"own/0","own","home","work",25200,null,
                Map.of(),Map.of(),List.of(legacyOption),List.of());
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) Json.readTree(Json.write(legacy)); json.remove("information");
        var candidate = (com.fasterxml.jackson.databind.node.ObjectNode) json.get("candidates").get(0);
        candidate.remove(List.of("estimatedToll","tollPolicyId","tollPolicyVersion","pricingKnowledge"));
        var saved = Json.MAPPER.treeToValue(json,MorningRoutePolicy.Context.class);
        MorningPromptRenderer.validate(saved); assertEquals(0.0,saved.candidates().getFirst().estimatedToll());
        var hidden = new MorningTravelerInformation(List.of("own"),0,1,3600);
        var k = hidden.pricing("own",List.of("road"),List.of(25210.0),1,null,0);
        var disabled = new MorningRoutePolicy.Option("c0",List.of("road"),10,25210,100,1.0,"static_free_flow",0,0,
                null,null,0.0,null,0,k);
        String prompt = new MorningPromptRenderer().render(context(hidden,"own",0,disabled));
        assertTrue(prompt.contains("Charging is explicitly disabled")); assertFalse(prompt.contains("Current tolls are unknown"));
    }

    @Test void unknownCostsStayNullAndOnlyOwnPastSameBinPaymentsEnterEstimates() throws Exception {
        var information = new MorningTravelerInformation(List.of("own", "other"), 0, 1, 3600);
        var tolls = tolls(); tolls.setPolicy(policy(20, 71.123456), 1);
        var links = List.of("road"); var times = List.of(25210.0);
        var unknown = option(information, "own", links, times, tolls, 20);
        assertNull(unknown.estimatedToll()); assertNull(unknown.estimatedMonetaryCost());
        String prompt = new MorningPromptRenderer().render(context(information, "own", 20, unknown));
        assertTrue(MorningJointTestSupport.tableRows(prompt,"ROUTE ESTIMATES").getFirst().get(5).isNull());
        assertTrue(prompt.contains("Unknown does not mean free"));
        assertFalse(prompt.contains("71.123456")); assertFalse(prompt.contains("pricing-v1"));
        information.observe(20, "other", receipt("road", 25210, 71.123456));
        assertNull(option(information, "own", links, times, tolls, 21).estimatedToll());
        information.observe(20, "own", receipt("road", 25210, 2.125));
        assertNull(option(information, "own", links, times, tolls, 20).estimatedToll(), "Never use same-day/future receipts before departure");
        var learned = option(information, "own", links, times, tolls, 21);
        assertEquals(2.125, learned.estimatedToll()); assertEquals(3.125, learned.estimatedMonetaryCost());
        assertNull(learned.tollPolicyId()); assertEquals(0, learned.tollPolicyVersion());
        assertNull(option(information, "own", links, List.of(30000.0), tolls, 21).estimatedToll(), "Do not infer unseen tariff windows");
        var partial = option(information, "own", List.of("road", "work"), List.of(25210.0,25220.0), tolls, 21);
        assertNull(partial.estimatedMonetaryCost()); assertEquals(2.125, partial.pricingKnowledge().knownTollSubtotal());
        tolls.setPolicy(policy(50, 98.876543), 2);
        var stale = option(information, "own", links, times, tolls, 50);
        assertEquals(2.125, stale.estimatedToll());
        prompt = new MorningPromptRenderer().render(context(information, "own", 50, stale));
        assertFalse(prompt.contains("98.876543")); assertFalse(prompt.contains("pricing-v2"));
        assertTrue(prompt.contains("Current toll: unknown"));
        assertEquals(3.125,MorningJointTestSupport.tableRows(prompt,"ROUTE ESTIMATES").getFirst().get(5).asDouble());
        var saved = Json.MAPPER.readValue(Json.write(context(information, "own", 50, stale)), MorningRoutePolicy.Context.class);
        assertEquals(prompt, new MorningPromptRenderer().render(saved));
        information.observe(50, "own", receipt("road", 25210, 98.876543));
        assertEquals(98.876543, option(information, "own", links, times, tolls, 51).estimatedToll());
    }

    @Test void informedSeesAnnouncementsAndBoundaryValidationRejectsLeaksAndFakeZeros() {
        var yes = new MorningTravelerInformation(List.of("in"), 1, 1, 3600);
        var no = new MorningTravelerInformation(List.of("out"), 0, 1, 3600);
        var tolls = tolls(); tolls.setPolicy(policy(20, 71.123456), 1);
        var announced = option(yes, "in", List.of("road"), List.of(25210.0), tolls, 20);
        String prompt = new MorningPromptRenderer().render(context(yes, "in", 20, announced));
        assertTrue(prompt.contains("71.123456")); assertTrue(prompt.contains("pricing-v1-it20"));
        assertTrue(prompt.contains("announcedRuleRows"));
        assertThrows(IllegalArgumentException.class, () -> MorningPromptRenderer.validate(context(no, "out", 20, announced)));
        var hidden = option(no, "out", List.of("road"), List.of(25210.0), tolls, 20);
        var zero = new MorningRoutePolicy.Option("c0", hidden.links(), 10, 25210, 100, 1.0, "static_free_flow", 0, 0,
                null, null, 0.0, null, 0, hidden.pricingKnowledge());
        assertThrows(IllegalArgumentException.class, () -> MorningPromptRenderer.validate(context(no, "out", 20, zero)));
        var trafficLeak = new MorningRoutePolicy.Option("c0", hidden.links(), 200, 25400, 100, null, "previous_day_link_times", 0, 0,
                19, null, null, null, 0, hidden.pricingKnowledge());
        assertThrows(IllegalArgumentException.class, () -> MorningPromptRenderer.validate(context(no, "out", 20, trafficLeak)));
    }

    @Test void actualRepeatedEntriesTeachPrivateKnowledgeAndNativeOtherTripsCannotSeeCurrentTolls() {
        var information = new MorningTravelerInformation(List.of("uninformed"), 0, 1, 3600);
        var tolls = tolls(); tolls.setInformation(information); tolls.reset(20); information.beginDay(20);
        tolls.setPolicy(policy(20, 2.125), 1);
        var p = Id.createPersonId("uninformed"); var v = Id.<Vehicle>create("car", Vehicle.class); var road = Id.createLinkId("road");
        tolls.handleEvent(new VehicleEntersTrafficEvent(25210, p, road, v, "car", .5));
        tolls.handleEvent(new LinkEnterEvent(25210, v, road));
        assertEquals(1, information.personalPayments("uninformed").size());
        tolls.handleEvent(new LinkLeaveEvent(25211, v, road)); tolls.handleEvent(new LinkEnterEvent(25212, v, road));
        assertEquals(4.25, ((Number)tolls.summary().get("totalPaid")).doubleValue());
        assertEquals(25212, information.personalPayments("uninformed").getFirst().time());
        tolls.reset(21); information.beginDay(21); tolls.setPolicy(policy(50, 98.876543), 2);
        var config = ConfigUtils.createConfig(); config.routing().setRoutingRandomness(0);
        var disutility = new MorningTollDisutilityFactory(config, tolls, information).createTravelDisutility((l,t,person,vehicle) -> 9000);
        var person = org.matsim.core.population.PopulationUtils.getFactory().createPerson(p);
        var base = new MorningTollDisutilityFactory(config, tolls()).createTravelDisutility((l,t,person1,vehicle) -> MatsimOdCandidates.freeFlow(l));
        var link = network().getLinks().get(road);
        double difference = disutility.getLinkTravelDisutility(link, 25210, person, null) - base.getLinkTravelDisutility(link, 25210, person, null);
        assertEquals(config.scoring().getMarginalUtilityOfMoney() * 2.125, difference, 1e-9);
        assertEquals(2.125, information.past("uninformed", "road", 25210, 21).amount());
    }

    @Test void fiftyTwoDayQsimKeepsGroupsAndAnnouncementsSeparateAcrossPolicyChange() throws Exception {
        Path config = writeScenario(); Path out = directory.resolve("integration");
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = "offline-test";
        var managerCalls = new AtomicInteger(); var prompts = Collections.synchronizedList(new ArrayList<String>());
        settings.clientFactory = (cfg,usage) -> (role,system,user) -> {
            if (role.equals("manager")) {
                assertFalse(user.contains("PRIVATE_PERSON")); assertFalse(user.contains("OWN_NOTES"));
                double price = managerCalls.incrementAndGet() == 1 ? 2.125 : 98.876543;
                return response("{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":108000,\"amount\":" + price + "}],\"memory\":\"Public network only\"}");
            }
            if (role.equals("traveler_memory")) {
                assertFalse(user.contains("Current announced rules"));
                return response("{\"memory\":\"OWN_NOTES: only personal observations\"}");
            }
            assertFalse(user.contains("Based on traffic observed")); prompts.add(user);
            return response(MorningJointTestSupport.withDeparture("{\"routeId\":\"c0\",\"reason\":\"My available route\"}", user));
        };
        assertEquals(0, new CommandLine(settings).execute("--config", config.toString(), "--output", out.toString(),
                "--mode", "hybrid", "--policy", "llm", "--llm-agents", "all", "--manager", "--days", "52", "--seed", "4711", "--max-requests", "220",
                "--manager-max-corridor-minutes", "1000", "--manager-max-step-minutes", "1000", "--manager-max-unavoidable-share", "1"));
        var assignments = rows(out.resolve("morning/information-groups.jsonl.gz"));
        assertEquals(2, assignments.size()); assertEquals(1, assignments.stream().filter(r -> r.get("informationGroup").asText().equals("informed")).count());
        String uninformed = assignments.stream().filter(r -> r.get("informationGroup").asText().equals("uninformed")).findFirst().orElseThrow().get("personId").asText();
        var contexts = rows(out.resolve("morning/decision-contexts.jsonl.gz")); assertEquals(104, contexts.size());
        for (var row : contexts) {
            int day = row.get("day").asInt(); boolean hidden = row.get("personId").asText().equals(uninformed);
            var option = row.get("candidates").get(0); assertTrue(option.get("forecastObservedDay").isNull());
            assertEquals("static_free_flow", option.get("estimateSource").asText());
            assertEquals(20, option.get("expectedCarSeconds").asDouble(), 1e-9);
            assertEquals(hidden ? "uninformed" : "informed", row.get("information").get("group").asText());
            var context = Json.MAPPER.treeToValue(row, MorningRoutePolicy.Context.class); String prompt = new MorningPromptRenderer().render(context);
            if (hidden) {
                assertTrue(option.get("tollPolicyId").isNull()); assertEquals(0, option.get("tollPolicyVersion").asInt());
                assertTrue(option.get("estimatedMonetaryCost").isNull()); assertTrue(option.get("estimatedToll").isNull());
                assertFalse(prompt.contains("pricing-v"));
                if (day <= 50) assertFalse(prompt.contains("98.876543"), "New price must not appear before this person's actual payment");
                assertEquals(0, option.get("pricingKnowledge").get("knownTollSubtotal").asDouble());
                assertEquals("bounded_personal_memory", option.get("pricingKnowledge").get("source").asText());
                assertTrue(row.get("information").get("personalPayments").isEmpty());
            } else assertEquals(day < 20 ? 0 : day < 50 ? 2.125 : 98.876543, option.get("estimatedToll").asDouble(), 1e-9);
        }
        var experiences = rows(out.resolve("morning/experiences.jsonl.gz")); assertEquals(104, experiences.size());
        for (var row : experiences) {
            int day = row.get("day").asInt(); assertEquals(day < 20 ? 0 : day < 50 ? 2.125 : 98.876543, row.get("experience").get("tollPaid").asDouble());
        }
        assertEquals(64, rows(out.resolve("morning/toll-payments.jsonl.gz")).size());
        for (var metric : rows(out.resolve("morning/metrics.jsonl.gz"))) {
            assertEquals(1, metric.get("informationGroups").get("informed").get("cohortTrips").asInt());
            assertEquals(1, metric.get("informationGroups").get("uninformed").get("cohortTrips").asInt());
        }
        assertEquals(2, managerCalls.get()); assertEquals(104, prompts.size());
        assertTrue(Json.readTree(Files.readString(out.resolve("morning/status.json"))).get("complete").asBoolean());
    }

    private static MorningRoutePolicy.Option option(MorningTravelerInformation information, String person, List<String> links,
                                                    List<Double> times, MorningTollSystem tolls, int day) {
        var k = information.pricing(person, links, times, 1, tolls, day);
        Double amount = k.unknownLinks().isEmpty() ? k.knownTollSubtotal() : null;
        Double cost = amount == null ? null : 1 + amount;
        boolean visible = information.informed(person);
        return new MorningRoutePolicy.Option("c0", links, 10, 25210, 100, cost, "static_free_flow", 0, 0,
                null, null, amount, visible ? tolls.state().policyId() : null, visible ? tolls.state().version() : 0, k);
    }
    private static MorningRoutePolicy.Context context(MorningTravelerInformation info, String person, int day, MorningRoutePolicy.Option option) {
        return new MorningRoutePolicy.Context(day, person+"/0", person, "home", "work", 25200, 32400.0,
                Map.of(), Map.of(), List.of(option), List.of(), null, "", null, info.visible(person, day));
    }
    private static MorningTollSystem.Receipt receipt(String link, double time, double amount) { return new MorningTollSystem.Receipt(link,time,amount,"own-receipt",1); }
    private static TollPolicy policy(int day, double amount) { return new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("road"),0,108000,amount)),day); }
    private static MorningTollSystem tolls() { return new MorningTollSystem(network(), EventsUtils.createEventsManager(), null); }
    private static LlmResponse response(String value) { return new LlmResponse(MorningManagerTestResponses.withDecision(value),0,0,0,0); }
    private static List<com.fasterxml.jackson.databind.JsonNode> rows(Path file) throws Exception {
        try (var reader = JsonlIO.newReader(file)) { return reader.lines().filter(l -> !l.isBlank()).map(Json::readTree).toList(); }
    }
    private static Network network() {
        var network = NetworkUtils.createNetwork(); var f = network.getFactory();
        for (int i=0;i<4;i++) network.addNode(f.createNode(Id.createNodeId(i),new Coord(i*100,0)));
        String[] ids={"home","road","work","return"};
        for(int i=0;i<4;i++) {
            var link=f.createLink(Id.createLinkId(ids[i]),network.getNodes().get(Id.createNodeId(i)),network.getNodes().get(Id.createNodeId((i+1)%4)));
            link.setLength(100);link.setFreespeed(10);link.setCapacity(1000);link.setNumberOfLanes(1);link.setAllowedModes(Set.of("car"));network.addLink(link);
        }
        return network;
    }
    Path writeScenario() {
        var config=ConfigUtils.createConfig(); config.qsim().setEndTime(108000); config.global().setNumberOfThreads(1);config.qsim().setNumberOfThreads(1);
        config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.none);
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("home").setTypicalDuration(12*3600));
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("work").setTypicalDuration(8*3600));
        config.replanning().setMaxAgentPlanMemorySize(6);
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(.1));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(.9));
        config.controller().setWriteEventsInterval(0);config.controller().setWritePlansInterval(0);
        Path net=directory.resolve("network.xml"),pop=directory.resolve("population.xml"),fac=directory.resolve("facilities.xml"),cfg=directory.resolve("config.xml");
        config.network().setInputFile(net.toString());config.plans().setInputFile(pop.toString());config.facilities().setInputFile(fac.toString());
        var scenario=ScenarioUtils.createScenario(config);var source=network();source.getNodes().values().forEach(scenario.getNetwork()::addNode);source.getLinks().values().forEach(scenario.getNetwork()::addLink);
        var f=scenario.getPopulation().getFactory();
        for(String id:List.of("p0","p1")) {
            var person=f.createPerson(Id.createPersonId(id));person.getAttributes().putAttribute("preferences","PRIVATE_PERSON "+id);var plan=f.createPlan();
            var home=f.createActivityFromLinkId("home",Id.createLinkId("home"));home.setEndTime(25200);plan.addActivity(home);plan.addLeg(f.createLeg("car"));
            var work=f.createActivityFromLinkId("work",Id.createLinkId("work"));work.setStartTime(25300);
            plan.addActivity(work);person.addPlan(plan);scenario.getPopulation().addPerson(person);
        }
        new NetworkWriter(scenario.getNetwork()).write(net.toString());new PopulationWriter(scenario.getPopulation()).write(pop.toString());
        new org.matsim.facilities.FacilitiesWriter(scenario.getActivityFacilities()).write(fac.toString());new ConfigWriter(config).write(cfg.toString());return cfg;
    }
}
