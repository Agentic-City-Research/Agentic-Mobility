package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.*;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class MorningPricingTest {
    @TempDir Path directory;

    @Test void warmupAndThirtyDayScheduleUseSimulationDaysEvenWithNonzeroFirstIteration() {
        assertEquals(List.of(20, 50, 80), java.util.stream.IntStream.range(0, 100)
                .filter(d -> MorningManager.updateDay(d, 0)).boxed().toList());
        assertEquals(List.of(120, 150), java.util.stream.IntStream.range(100, 152)
                .filter(d -> MorningManager.updateDay(d, 100)).boxed().toList());
        assertEquals(0, MorningManager.minimumCalls(0, 19));
        assertEquals(1, MorningManager.minimumCalls(0, 20));
        assertEquals(1, MorningManager.minimumCalls(0, 49));
        assertEquals(2, MorningManager.minimumCalls(0, 50));
    }

    @Test void unroundedPricesAndHourlyWindowsWithinTrialLimitsAreAcceptedWithoutPrivateInputs() throws Exception {
        Network network = network();
        var rules = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 15; i++) rules.add(Map.of("link_id", "road", "start", i * 7200, "end", i * 7200 + 3600, "amount", .123456789));
        var users = new ArrayList<String>();
        try (var output = new OutputWriter(directory.resolve("manager"))) {
            var manager = new MorningManager(network, 108000, environment(network), (role, system, user) -> {
                users.add(user); assertEquals("manager", role);
                assertTrue(system.contains("single objective"));
                return response(Json.write(Map.of("rules", rules, "memory", "Public congestion observations only")));
            }, output, new MorningRequestBudget(3), 3, Map.of());
            var dirty = new LinkedHashMap<>(linkHour());
            dirty.put("personId", "PRIVATE_PERSON"); dirty.put("memory", "PRIVATE_MEMORY"); dirty.put("profile", "PRIVATE_PROFILE");
            manager.observe(19, List.of(dirty), initial());
            var result = manager.update(20, 0, initial());
            assertTrue(result.accepted()); assertEquals(15, result.policy().rules().size());
            assertEquals(.123456789, result.policy().amountAt(Id.createLinkId("road"), 100));
            var context = Json.readTree(users.getFirst());
            assertEquals(19, context.get("observationsThroughDay").asInt());
            assertEquals(network.getLinks().size(), context.get("network").size());
            for (String privateValue : List.of("PRIVATE_PERSON", "PRIVATE_MEMORY", "PRIVATE_PROFILE")) assertFalse(users.getFirst().contains(privateValue));
            assertFalse(users.getFirst().contains("personAttributes")); assertFalse(users.getFirst().contains("routeUsage"));
        }
    }

    @Test void illegalRulesRetainPolicyAndMemoryWhileFutureTrafficAndBudgetAreRejected() throws Exception {
        var replies = new ArrayDeque<>(List.of(
                "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":108000,\"amount\":0.123}],\"memory\":\"accepted notes\"}",
                "{\"rules\":[{\"link_id\":\"missing\",\"start\":0,\"end\":108000,\"amount\":1}],\"memory\":\"rejected notes\"}",
                "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":108001,\"amount\":-1}],\"memory\":\"rejected notes\"}"));
        try (var output = new OutputWriter(directory.resolve("repairs"))) {
            var budget = new MorningRequestBudget(3);
            var network = network();
            var manager = new MorningManager(network, 108000, environment(network), (r,s,u) -> response(replies.remove()), output, budget, 2, Map.of());
            manager.observe(19, List.of(linkHour()), initial());
            var accepted = manager.update(20, 0, initial());
            var current = new MorningTollSystem.State(accepted.policy(), "pricing-v1-it20", 1);
            manager.observe(49, List.of(linkHour()), current);
            var rejected = manager.update(50, 0, current);
            assertFalse(rejected.accepted()); assertSame(current.policy(), rejected.policy());
            assertEquals(.123, rejected.policy().amountAt(Id.createLinkId("road"), 5));
            assertEquals("accepted notes", manager.context(51, 0, current).get("managerMemory"));
            assertEquals(3, budget.attemptedCalls());
            assertThrows(LlmException.class, () -> manager.update(80, 0, current));
            manager.observe(51, List.of(linkHour()), current);
            assertThrows(IllegalArgumentException.class, () -> manager.context(51, 0, current));
            output.flush(); assertTrue(Files.exists(directory.resolve("repairs/manager-fallbacks.jsonl")));
        }
        var overlap = new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("road"), 0, 10, 1),
                new TollPolicy.TollRule(Id.createLinkId("road"), 5, 20, 1)), 20);
        assertFalse(overlap.validate(network(), 108000).isEmpty());
    }

    @Test void eachTraversalBillsOnceWithLeftClosedRightOpenWindowsAndActualMoneyEvents() throws Exception {
        var events = EventsUtils.createEventsManager(); var money = new ArrayList<PersonMoneyEvent>();
        events.addHandler((BasicEventHandler) e -> { if (e instanceof PersonMoneyEvent m) money.add(m); });
        var receipts = new ArrayList<MorningTollSystem.Receipt>();
        var system = new MorningTollSystem(network(), events, (p,r) -> receipts.add(r));
        try (var output = new OutputWriter(directory.resolve("billing"))) {
            system.setOutput(output); system.reset(20);
            system.setPolicy(new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("road"), 10, 20, 2.125)), 20), 1);
            Id<Person> p = Id.createPersonId("driver"); Id<Vehicle> v = Id.createVehicleId("car"); Id<Link> road = Id.createLinkId("road");
            system.handleEvent(new VehicleEntersTrafficEvent(10, p, road, v, "car", .5));
            system.handleEvent(new VehicleEntersTrafficEvent(10, p, road, v, "car", .5));
            system.handleEvent(new LinkEnterEvent(10, v, road));
            assertEquals(1, money.size(), "Origin entry and duplicate notifications must be billed once");
            system.handleEvent(new LinkLeaveEvent(11, v, road));
            system.handleEvent(new LinkEnterEvent(12, v, road));
            system.handleEvent(new LinkEnterEvent(12, v, road));
            system.handleEvent(new LinkLeaveEvent(13, v, road));
            system.handleEvent(new LinkEnterEvent(20, v, road));
            assertEquals(2, money.size()); assertEquals(2, receipts.size());
            assertEquals(-2.125, money.getFirst().getAmount()); assertEquals(MorningTollSystem.PURPOSE, money.getFirst().getPurpose());
            assertEquals("pricing-v1-it20", receipts.getFirst().policyId());
            assertEquals(4.25, ((Number) system.summary().get("totalPaid")).doubleValue());
            system.handleEvent(new VehicleLeavesTrafficEvent(21, p, road, v, "car", 1));
            system.handleEvent(new LinkEnterEvent(22, v, road)); assertEquals(2, money.size());
            system.reset(21); assertEquals(0, ((Number) system.summary().get("totalPaid")).doubleValue());
            assertEquals(1, system.state().version(), "Daily reset must retain the active policy");
        }
    }

    @Test void nativeRoutingUsesPublicTollsAndManagerCallsAreIncludedInPreflightBudget() throws Exception {
        var config = ConfigUtils.createConfig(); var network = network();
        var tolls = new MorningTollSystem(network, EventsUtils.createEventsManager(), null);
        var disutility = new MorningTollDisutilityFactory(config, tolls).createTravelDisutility((l,t,p,v) -> l.getLength() / l.getFreespeed());
        var person = org.matsim.core.population.PopulationUtils.getFactory().createPerson(Id.createPersonId("native"));
        var link = network.getLinks().get(Id.createLinkId("road"));
        double free = disutility.getLinkTravelDisutility(link, 100, person, null);
        tolls.setPolicy(new TollPolicy(List.of(new TollPolicy.TollRule(link.getId(), 0, 108000, 12.3456)), 20), 1);
        assertEquals(12.3456 * config.scoring().getMarginalUtilityOfMoney(),
                disutility.getLinkTravelDisutility(link, 100, person, null) - free, 1e-9);
        Path file = writeScenario(); var settings = new MorningExperiment(); settings.llmConfig.apiKey = "offline-test-placeholder";
        var calls = new AtomicInteger(); settings.clientFactory = (c,u) -> (r,s,p) -> { calls.incrementAndGet(); return response("{}"); };
        var error = new java.io.StringWriter();
        int code = new CommandLine(settings).setErr(new java.io.PrintWriter(error)).execute("--config", file.toString(), "--manager", "--days", "51",
                "--max-requests", "1", "--llm-agents", "0", "--output", directory.resolve("budget-rejected").toString());
        assertEquals(1, code); assertTrue(error.toString().contains("Minimum LLM calls 2")); assertEquals(0, calls.get());
        var validation = new MorningExperiment(); validation.llmConfig.apiKey = null; validation.clientFactory = settings.clientFactory;
        Path validationOut = directory.resolve("validation");
        assertEquals(0, new CommandLine(validation).execute("--config", file.toString(), "--manager", "--days", "51", "--validate-only",
                "--llm-agents", "0", "--output", validationOut.toString()));
        assertEquals(0, calls.get());
        assertTrue(Json.readTree(Files.readString(validationOut.resolve("morning/manifest.json"))).get("managerEnabled").asBoolean());
        assertTrue(Files.exists(validationOut.resolve("morning/manager-public-environment.json")));
    }

    @Test void hybridRunsFiftyTwoDaysWithPricingAndPersonalPaymentFeedbackWithoutExternalApi() throws Exception {
        Path config = writeScenario(); Path out = directory.resolve("integration");
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = "offline-test-placeholder";
        var managerCalls = new AtomicInteger(); var travelerCalls = new AtomicInteger(); var memoryCalls = new AtomicInteger();
        settings.clientFactory = (c, usage) -> (role, system, user) -> {
            if (role.equals("manager")) {
                int call = managerCalls.incrementAndGet();
                assertFalse(user.contains("private-profile-marker")); assertFalse(user.contains("private-memory-marker"));
                var input = Json.readTree(user);
                assertTrue(input.has("simulationEndSeconds"));
                assertTrue(input.has("publicEnvironment")); assertTrue(input.has("roadCorridors"));
                assertTrue(input.has("policyFeedback"));
                assertEquals("combined", input.get("managerFeedbackMode").asText());
                assertEquals(call == 1 ? 20 : 50, input.get("policyFeedback").get("observedDays").asInt());
                return response("{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":108000,\"amount\":" + (call == 1 ? "2.125" : "3.375")
                        + "}],\"memory\":\"Only public traffic feedback\"}");
            }
            if (role.equals("traveler_memory")) {
                memoryCalls.incrementAndGet();
                if (user.contains("SIMULATION DAY 20")) assertEquals(2.125,
                        MorningJointTestSupport.tableRows(user,"RECENT ACTUAL EXPERIENCES").getLast().get(10).asDouble());
                return response("{\"memory\":\"private-memory-marker\"}");
            }
            travelerCalls.incrementAndGet();
            if (user.contains("SIMULATION DAY 21")) assertEquals(2.125,
                    MorningJointTestSupport.tableRows(user,"YOUR MOST RECENT PERSONAL EXPERIENCES").getLast().get(10).asDouble());
            return response(MorningJointTestSupport.withDeparture("{\"routeId\":\"c0\",\"reason\":\"Choose my available route\"}", user));
        };
        assertEquals(0, new CommandLine(settings).execute("--config", config.toString(), "--output", out.toString(), "--mode", "hybrid",
                "--policy", "llm", "--llm-agents", "all", "--manager", "--days", "52", "--seed", "4711", "--max-requests", "110",
                "--manager-max-corridor-minutes","1000","--manager-max-step-minutes","1000","--manager-max-unavoidable-share","1"));
        assertEquals(2, managerCalls.get()); assertEquals(52, travelerCalls.get()); assertEquals(52, memoryCalls.get());
        var applications = readLines(out.resolve("morning/policy-applications.jsonl.gz")).stream().map(Json::readTree).toList();
        for (int day = 0; day < 52; day++) assertEquals(day < 20 ? 0 : day < 50 ? 1 : 2, applications.get(day).get("policyVersion").asInt());
        var experienceRows = readLines(out.resolve("morning/experiences.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(52, experienceRows.size());
        for (int day = 0; day < 52; day++) {
            var row = experienceRows.get(day); assertTrue(row.get("completedCar").asBoolean());
            assertEquals(day < 20 ? 0 : day < 50 ? 2.125 : 3.375, row.get("experience").get("tollPaid").asDouble());
        }
        var contexts = readLines(out.resolve("morning/decision-contexts.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(2.125, contexts.get(20).get("candidates").get(0).get("estimatedToll").asDouble());
        assertEquals(2.125, contexts.get(21).get("history").get(contexts.get(21).get("history").size() - 1).get("tollPaid").asDouble());
        var usage = Json.readTree(Files.readString(out.resolve("morning/api-usage.json")));
        assertEquals(106, usage.get("attemptedTotalCalls").asInt()); assertEquals(2, usage.get("attemptedManagerCalls").asInt());
        var manifest = Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
        assertTrue(manifest.get("managerEnabled").asBoolean()); assertEquals(2, manifest.get("minimumManagerCalls").asInt());
        assertEquals("combined", manifest.get("managerContext").get("feedbackMode").asText());
        assertEquals(MorningManager.VERSION, manifest.get("managerContext").get("templateVersion").asText());
        var managerContexts = readLines(out.resolve("morning/manager-contexts.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(20, managerContexts.get(0).get("policyFeedback").get("freePeriodReference").get("observedDays").asInt());
        assertEquals(2, managerContexts.get(1).get("policyFeedback").get("policyPeriods").size());
        assertEquals(30, managerContexts.get(1).get("policyFeedback").get("policyPeriods").get(1).get("observations").get("observedDays").asInt());
        var evaluations = readLines(out.resolve("morning/manager-policy-evaluations.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(3, evaluations.size());
        assertEquals(2, evaluations.getLast().get("feedback").get("policyPeriods").get(2).get("observations").get("observedDays").asInt());
        assertEquals(32, readLines(out.resolve("morning/toll-payments.jsonl.gz")).size());
        assertEquals(3, readLines(out.resolve("morning/policies.jsonl.gz")).size());
    }

    @Test void sevenDayUpdatesRepairMemoryAndRemovePricesOnTheExecutedSchedule() throws Exception {
        Path config = writeScenario(), out = directory.resolve("seven-day-integration");
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = "offline-test-placeholder";
        var policyCalls = new AtomicInteger(); var memoryCalls = new AtomicInteger();
        settings.clientFactory = (c,usage) -> (role,system,user) -> {
            if (role.equals("manager-memory")) {
                memoryCalls.incrementAndGet(); return response("{\"memory\":\"Observe rerouting until iteration 27\"}");
            }
            assertEquals("manager",role);
            int call = policyCalls.incrementAndGet(); var input = Json.readTree(user);
            assertEquals(call == 1 ? 20 : 27,input.get("day").asInt());
            assertEquals(7,input.get("executionSchedule").get("updateIntervalDays").asInt());
            if (call == 1) assertEquals(27,input.get("executionSchedule").get("nextScheduledUpdateDay").asInt());
            else assertTrue(input.get("executionSchedule").get("nextScheduledUpdateDay").isNull());
            var rules = call == 1 ? List.of(Map.of("link_id","road","start",0,"end",108000,"amount",1.25)) : List.of();
            return response(Json.write(Map.of("rules",rules,"decision",MorningManagerTestResponses.decision(call == 1 ? "test" : "remove"),
                    "memory",call == 1 ? "word ".repeat(1001) : "Prices removed after review")));
        };
        assertEquals(0,new CommandLine(settings).execute("--config",config.toString(),"--output",out.toString(),"--manager",
                "--manager-update-interval","7","--days","29","--llm-agents","0","--max-requests","3",
                "--manager-max-corridor-minutes","1000","--manager-max-step-minutes","1000","--manager-max-unavoidable-share","1"));
        assertEquals(2,policyCalls.get()); assertEquals(1,memoryCalls.get());
        var manifest=Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
        assertEquals(2,manifest.get("minimumManagerCalls").asInt()); assertEquals(7,manifest.get("pricing").get("updateIntervalDays").asInt());
        var applications=readLines(out.resolve("morning/policy-applications.jsonl.gz")).stream().map(Json::readTree).toList();
        for (int day=0;day<29;day++) {
            assertEquals(day<20 ? 0 : day<27 ? 1 : 2,applications.get(day).get("policyVersion").asInt());
            assertEquals(day==20 || day==27,applications.get(day).get("scheduledUpdate").asBoolean());
        }
        var experiences=readLines(out.resolve("morning/experiences.jsonl.gz")).stream().map(Json::readTree).toList();
        for (int day=0;day<29;day++) assertEquals(day>=20 && day<27 ? 1.25 : 0,experiences.get(day).get("experience").get("tollPaid").asDouble());
        var accepted=readLines(out.resolve("morning/manager-decisions.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(2,accepted.size()); assertEquals("repaired",accepted.getFirst().get("memoryStatus").asText());
        assertTrue(accepted.getFirst().get("pricingChanged").asBoolean()); assertTrue(accepted.getLast().get("pricingChanged").asBoolean());
        assertEquals(7,readLines(out.resolve("morning/toll-payments.jsonl.gz")).size());
        assertEquals(3,Json.readTree(Files.readString(out.resolve("morning/api-usage.json"))).get("attemptedManagerCalls").asInt());
    }

    @Test void dailyWithdrawalChangesActualBillingAndSkipsAScheduledModelCallDuringCooldown() throws Exception {
        Path out=directory.resolve("automatic-withdrawal");
        var scenario=configuredScenario();var config=scenario.getConfig();
        config.controller().setOutputDirectory(out.toString());config.controller().setLastIteration(23);
        var person=scenario.getPopulation().getPersons().values().iterator().next();
        var car=(Leg)person.getSelectedPlan().getPlanElements().get(1);
        var route=org.matsim.core.population.routes.RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("home"),Id.createLinkId("work"));
        route.setLinkIds(route.getStartLinkId(),List.of(Id.createLinkId("road")),route.getEndLinkId());route.setDistance(300);route.setTravelTime(30);car.setRoute(route);
        var settings=new MorningExperiment();new CommandLine(settings).parseArgs("--manager","--manager-profile","controlled","--manager-update-interval","2",
                "--manager-review-days","2","--manager-adaptation-days","0","--manager-breach-days","1","--manager-max-unavoidable-share","1");
        var calls=new AtomicInteger();settings.clientFactory=(c,u) -> (role,system,user) -> {
            calls.incrementAndGet();return response("{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":108000,\"amount\":0.125}],\"memory\":\"Observe the one-road trial\"}");
        };
        var cohort=new MorningCohort(scenario,25200,32400,2);cohort.selectControlledPeople(0,4711);
        var observations=new MorningObservations(scenario.getNetwork(),cohort,23400,39600);var events=EventsUtils.createEventsManager();
        var tolls=new MorningTollSystem(scenario.getNetwork(),events,observations::recordToll);events.addHandler(observations);events.addHandler(tolls);
        var manifest=Map.<String,Object>of("loadedPopulation",1,"controlledPersons",0,"travelerInformation",Map.of());
        var times=new org.matsim.core.trafficmonitoring.TravelTimeCalculator(scenario.getNetwork(),config.travelTimeCalculator());
        var loop=new MorningLoop(scenario,settings,cohort,null,observations,times,manifest,tolls);
        var controller=new org.matsim.core.controler.Controler(scenario);loop.notifyStartup(new org.matsim.core.controler.events.StartupEvent(controller));
        try {
            for(int day=0;day<24;day++) {
                observations.reset(day);loop.notifyIterationStarts(new org.matsim.core.controler.events.IterationStartsEvent(controller,day,false));
                if(day>0) loop.notifyReplanning(new org.matsim.core.controler.events.ReplanningEvent(controller,day,false));
                var p=person.getId();var v=Id.createVehicleId("actual-car");double leave=25201+(day>=20?100:10);
                events.processEvent(new ActivityEndEvent(25200,p,Id.createLinkId("home"),null,"home"));
                events.processEvent(new PersonDepartureEvent(25200,p,Id.createLinkId("home"),"car","car"));
                events.processEvent(new VehicleEntersTrafficEvent(25200,p,Id.createLinkId("home"),v,"car",.5));
                events.processEvent(new LinkEnterEvent(25201,v,Id.createLinkId("road")));
                events.processEvent(new LinkLeaveEvent(leave,v,Id.createLinkId("road")));
                events.processEvent(new PersonArrivalEvent(leave+1,p,Id.createLinkId("work"),"car"));
                events.processEvent(new VehicleLeavesTrafficEvent(leave+1,p,Id.createLinkId("work"),v,"car",1));
                events.processEvent(new ActivityStartEvent(leave+1,p,Id.createLinkId("work"),null,"work",null));
                loop.notifyAfterMobsim(new org.matsim.core.controler.events.AfterMobsimEvent(controller,day,false));
            }
        } finally {loop.close();}
        assertEquals(1,calls.get(),"Iteration 22 must not reintroduce prices during withdrawal cooldown");
        var paid=readLines(out.resolve("morning/toll-payments.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(List.of(20,21),paid.stream().map(r -> r.get("day").asInt()).toList());
        assertEquals(.125,paid.getFirst().get("receipt").get("amount").asDouble());
        var apps=readLines(out.resolve("morning/policy-applications.jsonl.gz")).stream().map(Json::readTree).toList();
        assertTrue(apps.get(22).get("automaticSafetyWithdrawalToday").asBoolean());
        assertEquals(2,apps.get(22).get("policyVersion").asInt());
        var policies=readLines(out.resolve("morning/policies.jsonl.gz")).stream().map(Json::readTree).toList();
        assertTrue(policies.getLast().get("policy").get("rules").isEmpty());
        assertEquals(1,readLines(out.resolve("morning/manager-safety-actions.jsonl.gz")).size());
        assertEquals(1,readLines(out.resolve("morning/manager-safety-skipped-updates.jsonl.gz")).size());
        var suitability=Json.readTree(Files.readString(out.resolve("morning/manager-public-environment.json"))).get("chargingSuitability");
        assertEquals(0,suitability.get("referencePlanCaptureIteration").asInt());
        assertEquals(1,suitability.get("referenceRoutedCarTrips").asInt());
        assertTrue(suitability.get("referenceStage").asText().contains("first completed simulation iteration"));
    }

    private Scenario configuredScenario() {
        var config = ConfigUtils.createConfig(); config.qsim().setEndTime(108000); config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1); config.routing().setAccessEgressType(org.matsim.core.config.groups.RoutingConfigGroup.AccessEgressType.none);
        config.scoring().addActivityParams(new org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams("home").setTypicalDuration(12 * 3600));
        config.scoring().addActivityParams(new org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams("work").setTypicalDuration(8 * 3600));
        config.replanning().setMaxAgentPlanMemorySize(6);
        config.replanning().addStrategySettings(new org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(.1));
        config.replanning().addStrategySettings(new org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(.9));
        config.controller().setWriteEventsInterval(0); config.controller().setWritePlansInterval(0);
        config.network().setInputFile(directory.resolve("network.xml").toString());
        config.plans().setInputFile(directory.resolve("population.xml").toString());
        config.facilities().setInputFile(directory.resolve("facilities.xml").toString());
        var scenario = ScenarioUtils.createScenario(config); var network = scenario.getNetwork();
        var source = network(); source.getNodes().values().forEach(network::addNode); source.getLinks().values().forEach(network::addLink);
        var factory = scenario.getPopulation().getFactory(); var person = factory.createPerson(Id.createPersonId("traveler"));
        person.getAttributes().putAttribute("preferences", "private-profile-marker"); var plan = factory.createPlan();
        var home = factory.createActivityFromLinkId("home", Id.createLinkId("home")); home.setEndTime(25200); plan.addActivity(home);
        plan.addLeg(factory.createLeg("car")); var work = factory.createActivityFromLinkId("work", Id.createLinkId("work"));
        work.setStartTime(25300); plan.addActivity(work);
        person.addPlan(plan); scenario.getPopulation().addPerson(person);
        return scenario;
    }
    Path writeScenario() {
        var scenario=configuredScenario();var config=scenario.getConfig();
        Path net = directory.resolve("network.xml"), pop = directory.resolve("population.xml"), cfg = directory.resolve("config.xml"), facilities = directory.resolve("facilities.xml");
        var network=scenario.getNetwork();
        new NetworkWriter(network).write(net.toString()); new PopulationWriter(scenario.getPopulation()).write(pop.toString());
        new org.matsim.facilities.FacilitiesWriter(scenario.getActivityFacilities()).write(facilities.toString());
        new ConfigWriter(config).write(cfg.toString()); return cfg;
    }
    static Network network() {
        var n = NetworkUtils.createNetwork(); var f = n.getFactory();
        for (int i = 0; i < 4; i++) n.addNode(f.createNode(Id.createNodeId(i), new Coord(i * 100, 0)));
        String[] names = {"home", "road", "work"};
        for (int i = 0; i < 3; i++) {
            var l = f.createLink(Id.createLinkId(names[i]), n.getNodes().get(Id.createNodeId(i)), n.getNodes().get(Id.createNodeId(i + 1)));
            l.setLength(100); l.setFreespeed(10); l.setCapacity(1000); l.setNumberOfLanes(1); l.setAllowedModes(Set.of("car")); n.addLink(l);
        }
        var back = f.createLink(Id.createLinkId("return"), n.getNodes().get(Id.createNodeId(3)), n.getNodes().get(Id.createNodeId(0)));
        back.setLength(300); back.setFreespeed(10); back.setCapacity(1000); back.setNumberOfLanes(1); back.setAllowedModes(Set.of("car")); n.addLink(back);
        return n;
    }
    private static Map<String, Object> linkHour() {
        var row = new LinkedHashMap<String, Object>(); row.put("link_id", "road"); row.put("start", 25200); row.put("end", 28800);
        row.put("entered", 10); row.put("completed", 8); row.put("aborted", 1); row.put("unfinished", 1); row.put("partial", 0);
        row.put("total_travel_time", 800); row.put("total_delay", 720); return row;
    }
    private static LlmResponse response(String content) { return new LlmResponse(MorningManagerTestResponses.withDecision(content), 0, 0, 0, 0); }
    private static MorningManagerEnvironment environment(Network network) {
        return new MorningManagerEnvironment(network, ConfigUtils.createConfig(), 1, 1, true, "hybrid", 0,
                Map.of("scope", "controlled", "persons", 0, "informedPersons", 0, "uninformedPersons", 0, "tollHistoryBinSeconds", 3600));
    }
    private static MorningTollSystem.State initial() { return new MorningTollSystem.State(TollPolicy.empty(-1), "no-toll", 0); }
}
