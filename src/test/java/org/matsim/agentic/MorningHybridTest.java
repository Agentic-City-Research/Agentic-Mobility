package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.*;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.*;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class MorningHybridTest {
    @TempDir Path directory;

    @Test void hybridKeepsNativePlanMemoryAndTimeMutationByDefault() {
        Config config = publicConfig();
        double originalCarScore = config.scoring().getModes().get("car").getMarginalUtilityOfTraveling();
        double originalLateScore = config.scoring().getLateArrival_utils_hr();
        double originalRerouteWeight = config.replanning().getStrategySettings().stream()
                .filter(s -> s.getStrategyName().equals("ReRoute")).findFirst().orElseThrow().getWeight();
        var settings = new MorningExperiment(); settings.mode = "hybrid";
        settings.configureReplanning(config);
        assertEquals(6, config.replanning().getMaxAgentPlanMemorySize());
        assertEquals(Set.of("ReRoute", "ChangeExpBeta", "TimeAllocationMutator"), config.replanning().getStrategySettings().stream()
                .map(s -> s.getStrategyName()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(originalRerouteWeight, config.replanning().getStrategySettings().stream()
                .filter(s -> s.getStrategyName().equals("ReRoute")).findFirst().orElseThrow().getWeight());
        assertEquals(originalCarScore, config.scoring().getModes().get("car").getMarginalUtilityOfTraveling());
        assertEquals(originalLateScore, config.scoring().getLateArrival_utils_hr());
        assertFalse(settings.fixesTimes());
        assertTrue(settings.overlaysChoices());
        var fixedTimeSettings = new MorningExperiment();
        new CommandLine(fixedTimeSettings).parseArgs("--no-native-time-mutation");
        fixedTimeSettings.configureReplanning(config);
        assertTrue(fixedTimeSettings.fixesTimes());
        assertEquals(Set.of("ReRoute", "ChangeExpBeta"), config.replanning().getStrategySettings().stream()
                .map(s -> s.getStrategyName()).collect(java.util.stream.Collectors.toSet()));
        var enabledSettings = new MorningExperiment(); enabledSettings.mode = "hybrid"; enabledSettings.innovationStopDay = 2;
        enabledSettings.configureReplanning(config);
        var added = config.replanning().getStrategySettings().stream()
                .filter(s -> s.getStrategyName().equals("TimeAllocationMutator")).findFirst().orElseThrow();
        assertEquals(.1, added.getWeight()); assertEquals(2, added.getDisableAfter());
    }

    @Test void restoredTimesFollowCopiedSelectedPlanAndOverlayDoesNotTouchReturn() throws Exception {
        var scenario = smallScenario(); var person = scenario.getPopulation().getPersons().values().iterator().next();
        var original = person.getSelectedPlan();
        var cohort = new MorningCohort(scenario, 25200, 32400, 20);
        cohort.selectControlledPeople(1, 4711);
        var copied = scenario.getPopulation().getFactory().createPlan(); PopulationUtils.copyFromTo(original, copied);
        person.addPlan(copied); person.setSelectedPlan(copied);
        TripStructureUtils.getTrips(copied).getFirst().getOriginActivity().setEndTime(30000);
        var returnLeg = MorningCohort.carLeg(TripStructureUtils.getTrips(copied).get(1));
        var nativeReturn = returnLeg.getRoute();
        var settings = new MorningExperiment(); settings.mode = "hybrid"; settings.policyName = "mock";
        settings.nativeTimeMutation = false;
        var observations = new MorningObservations(scenario.getNetwork(), cohort, 23400, 39600);
        var times = new TravelTimeCalculator(scenario.getNetwork(), scenario.getConfig().travelTimeCalculator());
        var loop = new MorningLoop(scenario, settings, cohort,
                c -> new MorningRoutePolicy.Decision(c.candidates().getLast().routeId(), "Test distinct candidate"),
                observations, times, Map.of());
        var controller = new Controler(scenario);
        loop.notifyStartup(new StartupEvent(controller));
        try {
            // Simulate the state left by native plan copying/selection and ReRoute.
            loop.notifyReplanning(new ReplanningEvent(controller, 1, false));
            assertEquals(25200, TripStructureUtils.getTrips(copied).getFirst().getOriginActivity().getEndTime().seconds());
            assertEquals(61200, TripStructureUtils.getTrips(copied).get(1).getOriginActivity().getEndTime().seconds());
            assertSame(nativeReturn, returnLeg.getRoute(), "The morning overlay must preserve the native return route");
            var selected = (NetworkRoute) MorningCohort.carLeg(TripStructureUtils.getTrips(copied).getFirst()).getRoute();
            assertEquals("a3", selected.getLinkIds().getFirst().toString());
            var originalRoute = (NetworkRoute) MorningCohort.carLeg(TripStructureUtils.getTrips(original).getFirst()).getRoute();
            assertEquals("a0", originalRoute.getLinkIds().getFirst().toString(), "An unselected plan must not be mutated");
        } finally { loop.close(); }
    }

    @Test void selectionIsFixedPersonBasedAndIncludesAllTheirMorningTrips() {
        var scenario = smallScenario(); var original = scenario.getPopulation().getPersons().values().iterator().next();
        TripStructureUtils.getTrips(original.getSelectedPlan()).get(1).getOriginActivity().setEndTime(27000);
        for (int i = 1; i < 6; i++) {
            var person = scenario.getPopulation().getFactory().createPerson(Id.createPersonId("p" + i));
            var plan = scenario.getPopulation().getFactory().createPlan(); PopulationUtils.copyFromTo(original.getSelectedPlan(), plan);
            person.addPlan(plan); scenario.getPopulation().addPerson(person);
        }
        var cohort = new MorningCohort(scenario, 25200, 32400, 20); cohort.selectControlledPeople(3, 4711);
        var selected = Set.copyOf(cohort.controlledPersons); cohort.selectControlledPeople(3, 4711);
        assertEquals(selected, cohort.controlledPersons); assertEquals(3, selected.size());
        assertEquals(6, cohort.members.values().stream().filter(m -> m.controlled).count());
        for (var member : cohort.members.values()) assertEquals(selected.contains(member.person.getId().toString()), member.controlled);
        assertThrows(IllegalArgumentException.class, () -> cohort.selectControlledPeople(7, 4711));
    }

    @Test void realLlmAdapterRepairsInvalidRouteAndRejectsDepartureChangesWithoutHttp() throws Exception {
        var context = context(); var calls = new AtomicInteger();
        LlmClient client = (role, system, user) -> {
            assertFalse(user.contains("matsimScore")); assertFalse(user.contains("plannedDepartureSeconds"));
            assertTrue(user.contains("Planned departure: 07:00:00"));
            int call = calls.incrementAndGet();
            return new LlmResponse(call == 1 ? "{\"routeId\":\"invented\",\"reason\":\"bad\"}"
                    : call == 2 ? "{\"routeId\":\"c0\",\"reason\":\"bad\",\"offset\":10}"
                    : "{\"routeId\":\"c0\",\"reason\":\"Fits my schedule\"}", 0, 0, 0, 0);
        };
        try (var output = new OutputWriter(directory.resolve("llm-policy"))) {
            assertEquals("c0", new MorningLlmPolicy(client, output, 3).choose(context).routeId());
            assertEquals(3, calls.get()); output.flush();
            assertEquals(3, Files.readAllLines(directory.resolve("llm-policy/attempts.jsonl")).size());
            assertThrows(LlmException.class, () -> new MorningLlmPolicy((r, s, u) ->
                    new LlmResponse("{\"routeId\":\"other\",\"reason\":\"bad\"}", 0, 0, 0, 0), output, 1).choose(context));
            assertThrows(LlmTransportException.class, () -> new MorningLlmPolicy((r, s, u) -> {
                throw new LlmTransportException("No network in this test", null);
            }, output, 3).choose(context));
        }
    }

    @Test void llmCallBudgetIsReservedBeforeConcurrentDispatchAndCountsRepairs() throws Exception {
        var calls = new AtomicInteger();
        try (var output = new OutputWriter(directory.resolve("llm-budget"));
             var pool = Executors.newFixedThreadPool(4)) {
            var policy = new MorningLlmPolicy((role, system, user) -> {
                calls.incrementAndGet();
                return new LlmResponse("{\"routeId\":\"c0\",\"reason\":\"Fits my schedule\"}", 0, 0, 0, 0);
            }, output, 3, 2);
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(() -> {
                try { policy.choose(context()); return true; }
                catch (LlmException expected) { assertTrue(expected.getMessage().contains("budget exhausted")); return false; }
            });
            int successful = 0;
            for (var result : pool.invokeAll(tasks)) if (result.get()) successful++;
            assertEquals(2, successful); assertEquals(2, calls.get()); assertEquals(2, policy.attemptedCalls());
            var invalid = new MorningLlmPolicy((role, system, user) -> {
                calls.incrementAndGet();
                return new LlmResponse("{\"routeId\":\"invented\",\"reason\":\"bad\"}", 0, 0, 0, 0);
            }, output, 3, 1);
            assertThrows(LlmException.class, () -> invalid.choose(context()));
            assertEquals(3, calls.get()); assertEquals(1, invalid.attemptedCalls());
        }
    }

    @Test void hybridNativeBaselineAndPartialOverlayRunAfterReplanningBeforeSavedPlans() throws Exception {
        Config config = publicConfig();
        for (var strategy : config.replanning().getStrategySettings()) {
            if (strategy.getStrategyName().equals("ReRoute")) strategy.setWeight(1);
            if (strategy.getStrategyName().equals("ChangeExpBeta")) strategy.setWeight(0);
        }
        config.controller().setWritePlansInterval(1);
        Path file = directory.resolve("native-reroute.xml"); new ConfigWriter(config).write(file.toString());
        for (String controlled : List.of("0", "5", "all")) {
            Path output = directory.resolve("hybrid-" + controlled);
            var settings = new MorningExperiment();
            settings.policyOverride = c -> new MorningRoutePolicy.Decision(c.candidates().getLast().routeId(), "Forced test alternative");
            assertEquals(0, new CommandLine(settings).execute("--config", file.toString(), "--mode", "hybrid", "--policy", "mock",
                    "--llm-agents", controlled, "--demand", "100", "--sample-persons", "40", "--days", "3", "--seed", "4711",
                    "--output", output.toString(), "--no-native-time-mutation"));
            var manifest = Json.readTree(Files.readString(output.resolve("morning/manifest.json")));
            assertEquals(6, manifest.get("nativePlanMemorySize").asInt()); assertTrue(manifest.get("plannedTimesFixed").asBoolean());
            int count = controlled.equals("all") ? manifest.get("cohortPersons").asInt() : Integer.parseInt(controlled);
            assertEquals(count, manifest.get("controlledPersons").asInt()); assertEquals(0, manifest.get("llmPersons").asInt());
            assertEquals(0, Json.readTree(Files.readString(output.resolve("morning/api-usage.json"))).get("requests").asInt());
            var populations = new HashMap<Integer, Population>();
            for (int day = 0; day < 3; day++) {
                var saved = ScenarioUtils.createScenario(ConfigUtils.createConfig());
                new PopulationReader(saved).readFile(output.resolve("ITERS/it." + day + "/" + day + ".plans.xml.zst").toString());
                populations.put(day, saved.getPopulation());
            }
            assertTrue(populations.get(1).getPersons().values().stream().allMatch(p -> p.getPlans().size() > 1), "Native ReRoute must copy plans for background AND controlled persons");
            for (var person : populations.get(0).getPersons().values()) {
                var initialTrips = TripStructureUtils.getTrips(person.getSelectedPlan());
                for (int day = 1; day < 3; day++) {
                    var trips = TripStructureUtils.getTrips(populations.get(day).getPersons().get(person.getId()).getSelectedPlan());
                    for (int i = 0; i < trips.size(); i++) assertEquals(initialTrips.get(i).getOriginActivity().getEndTime().seconds(),
                            trips.get(i).getOriginActivity().getEndTime().seconds(), "No time mutation in any group");
                }
            }
            var metrics = readLines(output.resolve("morning/metrics.jsonl.gz"));
            assertEquals(3, metrics.size());
            for (var line : metrics) {
                var row = Json.readTree(line);
                assertEquals(row.get("cohortTrips").asInt(), row.get("groups").get("native").get("cohortTrips").asInt()
                        + row.get("groups").get("controlled").get("cohortTrips").asInt());
            }
            if (count == 0) {
                assertFalse(Files.exists(output.resolve("morning/decisions.jsonl.gz"))); continue;
            }
            var decisions = readLines(output.resolve("morning/decisions.jsonl.gz"));
            assertTrue(decisions.stream().map(Json::readTree).anyMatch(d -> d.get("day").asInt() > 0
                    && !d.get("nativeRouteFingerprint").asText().equals(d.get("appliedRouteFingerprint").asText())));
            Map<String, String> applied = new HashMap<>();
            for (String line : decisions) {
                var decision = Json.readTree(line); applied.put(decision.get("day").asInt() + "/" + decision.get("tripId").asText(), decision.get("appliedRouteFingerprint").asText());
            }
            for (String line : readLines(output.resolve("morning/trips.jsonl.gz"))) {
                var trip = Json.readTree(line); String id = trip.get("tripId").asText(); int day = trip.get("day").asInt();
                var parts = id.split("/");
                var person = populations.get(day).getPersons().get(Id.createPersonId(parts[0]));
                var route = (NetworkRoute) MorningCohort.carLeg(TripStructureUtils.getTrips(person.getSelectedPlan()).get(Integer.parseInt(parts[1]))).getRoute();
                assertEquals(trip.get("routeFingerprint").asText(), MorningLoop.fingerprint(route));
                if (trip.get("controlled").asBoolean()) assertEquals(applied.get(day + "/" + id), MorningLoop.fingerprint(route), "Saved plans and execution must use the overlay, not overwritten native routes");
                else assertEquals("native", trip.get("routeId").asText());
            }
        }
    }

    private Config publicConfig() {
        Path root = Path.of("scenarios/SiouxFalls-public/config.xml").toAbsolutePath();
        var config = ConfigUtils.loadConfig(root.toString());
        config.network().setInputFile(root.getParent().resolve(config.network().getInputFile()).normalize().toString());
        config.facilities().setInputFile(root.getParent().resolve(config.facilities().getInputFile()).normalize().toString());
        config.plans().setInputFile(root.getParent().resolve(config.plans().getInputFile()).normalize().toString());
        return config;
    }

    private Scenario smallScenario() {
        var config = ConfigUtils.createConfig(); config.qsim().setEndTime(108000);
        config.controller().setOutputDirectory(directory.resolve("unit-loop").toString());
        var scenario = ScenarioUtils.createScenario(config); var network = scenario.getNetwork(); var nf = network.getFactory();
        for (int i = 0; i < 4; i++) network.addNode(nf.createNode(Id.createNodeId(i), new Coord(i * 100, 0)));
        Map<String, int[]> links = new LinkedHashMap<>(); links.put("home", new int[]{0,1});
        for (int i = 0; i < 4; i++) links.put("a" + i, new int[]{1,2});
        links.put("work", new int[]{2,3}); links.put("return", new int[]{3,0});
        links.forEach((id, nodes) -> {
            var link = nf.createLink(Id.createLinkId(id), network.getNodes().get(Id.createNodeId(nodes[0])), network.getNodes().get(Id.createNodeId(nodes[1])));
            link.setLength(id.startsWith("a") ? 100 + Integer.parseInt(id.substring(1)) * 10 : 100); link.setFreespeed(10); link.setAllowedModes(Set.of("car")); network.addLink(link);
        });
        var pf = scenario.getPopulation().getFactory(); var person = pf.createPerson(Id.createPersonId("p0")); var plan = pf.createPlan();
        var home = pf.createActivityFromLinkId("home", Id.createLinkId("home")); home.setEndTime(25200); plan.addActivity(home);
        var outward = pf.createLeg("car"); var route = pf.getRouteFactories().createRoute(NetworkRoute.class, Id.createLinkId("home"), Id.createLinkId("work"));
        route.setLinkIds(route.getStartLinkId(), List.of(Id.createLinkId("a0")), route.getEndLinkId()); outward.setRoute(route); plan.addLeg(outward);
        var work = pf.createActivityFromLinkId("work", Id.createLinkId("work")); work.setEndTime(61200); plan.addActivity(work);
        var back = pf.createLeg("car"); var backRoute = pf.getRouteFactories().createRoute(NetworkRoute.class, Id.createLinkId("work"), Id.createLinkId("home"));
        backRoute.setLinkIds(backRoute.getStartLinkId(), List.of(Id.createLinkId("return")), backRoute.getEndLinkId()); back.setRoute(backRoute); plan.addLeg(back);
        plan.addActivity(pf.createActivityFromLinkId("home", Id.createLinkId("home"))); person.addPlan(plan); scenario.getPopulation().addPerson(person);
        return scenario;
    }

    private static MorningRoutePolicy.Context context() {
        return new MorningRoutePolicy.Context(0, "p/0", "p", "home", "work", 25200, 32400.0, Map.of(), Map.of(),
                List.of(new MorningRoutePolicy.Option("c0", List.of("start", "end"), 100, 25300, 1000, .4, "free_flow", 0, 0)), List.of());
    }
}
