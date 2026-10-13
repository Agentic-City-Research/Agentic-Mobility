package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.core.gbl.MatsimRandom;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class MorningExperimentTest {
    @TempDir Path directory;

    @Test void memoryRetainsCumulativeHabitBeyondRecentHistory() {
        var memory = new MorningMemory(2);
        for (int day = 0; day < 4; day++) {
            memory.chose("c1"); memory.observe(new MorningMemory.Experience(day, "c1", day == 0 ? 100 : 200, null, null));
        }
        assertEquals(2, memory.recent().size());
        assertEquals(4, memory.all().size()); assertEquals(0, memory.all().getFirst().day());
        assertEquals(4, memory.route("c1").chosenCount()); assertEquals(4, memory.route("c1").completedCount());
        assertEquals(2, memory.recent().getFirst().day());
        assertEquals(200, memory.recent().getFirst().carSeconds());
        assertEquals(3, memory.route("c1").lastExperiencedDay());
        memory.chose("c2"); memory.observe(new MorningMemory.Experience(4, "c2", null, null, null, true));
        assertTrue(memory.recent().getLast().stuck()); assertEquals(0, memory.route("c2").completedCount());
        assertNull(memory.route("c2").lastExperiencedDay());
    }

    @Test void policyCannotInventRouteOrDepartureOffset() {
        var options = List.of(new MorningRoutePolicy.Option("c0", List.of("origin", "end"), 100, 28000, 1000, .4, "free_flow", 0, 0));
        var context = new MorningRoutePolicy.Context(0, "p/0", "p", "home", "work", 27900, 32400.0, Map.of(), Map.of(), options, List.of());
        assertThrows(IllegalArgumentException.class, () -> MorningRoutePolicy.validate(context, new MorningRoutePolicy.Decision("other", "invented")));
        assertEquals("c0", MorningRoutePolicy.validate(context, MorningRoutePolicy.baseline("mnl", 1, .3).choose(context)).routeId());
    }

    @Test void matsimCandidatesAreOdSpecificAndHandleZeroLengthOrigin() {
        var network = NetworkUtils.createNetwork(); var f = network.getFactory();
        for (int i = 0; i < 4; i++) network.addNode(f.createNode(Id.createNodeId(i), new Coord(i * 100, 0)));
        for (int i = 0; i < 7; i++) {
            int from = i == 0 ? 0 : i == 6 ? 2 : 1;
            int to = i == 0 ? 1 : i == 6 ? 3 : 2;
            var link = f.createLink(Id.createLinkId("l" + i), network.getNodes().get(Id.createNodeId(from)), network.getNodes().get(Id.createNodeId(to)));
            link.setLength(i == 0 ? 0 : i * 10); link.setFreespeed(10); link.setAllowedModes(Set.of("car")); network.addLink(link);
        }
        var config = ConfigUtils.createConfig(); config.routing().setRoutingRandomness(0);
        var person = ScenarioUtils.createScenario(config).getPopulation().getFactory().createPerson(Id.createPersonId("p"));
        var generator = new MatsimOdCandidates(network, config,
                new MatsimOdCandidates.Settings(10000, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 1), null);
        var routes = generator.compute(Id.createLinkId("l0"), Id.createLinkId("l6"), 25200, person);
        // Deeper BFS-LE exclusions recover the parallel alternatives, including c3.
        assertEquals(4, MatsimOdCandidates.MAX_CANDIDATES); assertEquals(4, routes.size());
        assertEquals(7, network.getLinks().size(), "Eliminations must not mutate the simulation network");
        assertEquals(List.of(Id.createLinkId("l0"), Id.createLinkId("l1"), Id.createLinkId("l6")), routes.getFirst().linkIds());
        assertSame(routes, generator.compute(Id.createLinkId("l0"), Id.createLinkId("l6"), 25200, person));
        assertEquals(List.of(Id.createLinkId("l6")), generator.compute(Id.createLinkId("l6"), Id.createLinkId("l6"), 25200, person).getFirst().linkIds());
        for (var c : routes) {
            assertEquals(Id.createLinkId("l0"), c.linkIds().getFirst()); assertEquals(Id.createLinkId("l6"), c.linkIds().getLast());
            assertTrue(c.freeFlowTravelTime() >= 6);
        }
    }

    @Test void publicBfsCandidatesAreConnectedDeduplicatedAndSeedIndependent() {
        var network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(Path.of("scenarios/Souix Falls 2014/Siouxfalls_network_Car.xml.gz").toAbsolutePath().toString());
        var config = ConfigUtils.loadConfig(Path.of("scenarios/SiouxFalls-public/config.xml").toAbsolutePath().toString());
        var person = ScenarioUtils.createScenario(config).getPopulation().getFactory().createPerson(Id.createPersonId("p"));
        MatsimRandom.reset(4711);
        var routes = new MatsimOdCandidates(network, config).compute(Id.createLinkId("18_4"), Id.createLinkId("50_2"), 25200, person);
        assertTrue(routes.size() >= 1 && routes.size() <= 4);
        assertEquals(routes.size(), new HashSet<>(routes.stream().map(RouteCandidate::linkIds).toList()).size());
        MatsimRandom.reset(9261);
        assertEquals(routes, new MatsimOdCandidates(network, config).compute(Id.createLinkId("18_4"), Id.createLinkId("50_2"), 25200, person));
        for (var route : routes) for (int i = 1; i < route.linkIds().size(); i++) {
            assertEquals(network.getLinks().get(route.linkIds().get(i - 1)).getToNode().getId(),
                    network.getLinks().get(route.linkIds().get(i)).getFromNode().getId());
        }
    }

    @Test void completedDayTravelSnapshotSurvivesActualEventHandlerReset() {
        var config = ConfigUtils.createConfig(); var network = NetworkUtils.createNetwork(); var f = network.getFactory();
        var a = f.createNode(Id.createNodeId("a"), new Coord(0, 0)); var b = f.createNode(Id.createNodeId("b"), new Coord(100, 0));
        network.addNode(a); network.addNode(b);
        var link = f.createLink(Id.createLinkId("slow"), a, b); link.setLength(100); link.setFreespeed(10); link.setAllowedModes(Set.of("car")); network.addLink(link);
        var times = new TravelTimeCalculator(network, config.travelTimeCalculator());
        var p = Id.createPersonId("p"); var v = Id.createVehicleId("v");
        times.handleEvent(new VehicleEntersTrafficEvent(25199, p, link.getId(), v, "car", 1));
        times.handleEvent(new LinkEnterEvent(25200, v, link.getId()));
        times.handleEvent(new LinkLeaveEvent(25300, v, link.getId()));
        var snapshot = new PreviousDayTravelTimes(network, config.travelTimeCalculator().getTraveltimeBinSize(), 108000);
        snapshot.capture(times.getLinkTravelTimes(), 0);
        assertEquals(100, snapshot.seconds(link, 25200), .001);
        times.reset(1);
        assertEquals(10, times.getLinkTravelTimes().getLinkTravelTime(link, 25200, null, null), .001);
        assertEquals(100, snapshot.seconds(link, 25200), .001); assertEquals(0, snapshot.observedDay());
    }

    @Test void runNameUsesActualDaysLlmCountManagerAndExplicitDate() {
        assertEquals("2026-09-28_day500_llm50_noMgr_seed4711",
                ExperimentRunName.format(ExperimentRunName.date("2026-09-28"), 500, 50, true, 4711));
        assertEquals("2026-09-28_day500_llm0_noMgr_seed4711",
                ExperimentRunName.format(ExperimentRunName.date("2026-09-28"), 500, 0, true, 4711));
    }

    @Test void cohortIncludesSecondaryTripsAndStageWalkDoesNotShiftTripIndex() {
        var config = ConfigUtils.createConfig(); var scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork(); var nf = network.getFactory();
        for (int i = 0; i < 3; i++) network.addNode(nf.createNode(Id.createNodeId(i), new Coord(i * 100, 0)));
        for (int i = 0; i < 3; i++) {
            var link = nf.createLink(Id.createLinkId("l" + i), network.getNodes().get(Id.createNodeId(i)), network.getNodes().get(Id.createNodeId((i + 1) % 3)));
            link.setLength(100); link.setFreespeed(10); link.setAllowedModes(Set.of("car")); network.addLink(link);
        }
        var pf = scenario.getPopulation().getFactory(); var person = pf.createPerson(Id.createPersonId("p")); var plan = pf.createPlan();
        var home = pf.createActivityFromLinkId("home", Id.createLinkId("l0")); home.setEndTime(25200); plan.addActivity(home); plan.addLeg(pf.createLeg("car"));
        var secondary = pf.createActivityFromLinkId("secondary", Id.createLinkId("l1")); secondary.setEndTime(27000); plan.addActivity(secondary); plan.addLeg(pf.createLeg("car"));
        var work = pf.createActivityFromLinkId("work", Id.createLinkId("l2")); work.setEndTime(61200); plan.addActivity(work); plan.addLeg(pf.createLeg("car"));
        plan.addActivity(pf.createActivityFromLinkId("home", Id.createLinkId("l0"))); person.addPlan(plan); scenario.getPopulation().addPerson(person);
        var cohort = new MorningCohort(scenario, 25200, 32400, 20);
        assertEquals(2, cohort.members.size()); assertEquals("secondary", cohort.members.get("p/0").destinationPurpose);
        var observer = new MorningObservations(network, cohort, 25000, 28000); observer.reset(0);
        observer.handleEvent(new ActivityEndEvent(25200, person.getId(), home.getLinkId(), null, "home"));
        observer.handleEvent(new ActivityEndEvent(25210, person.getId(), home.getLinkId(), null, "car interaction"));
        observer.handleEvent(new PersonDepartureEvent(25210, person.getId(), home.getLinkId(), "car", "car"));
        observer.handleEvent(new PersonArrivalEvent(25230, person.getId(), secondary.getLinkId(), "car"));
        observer.handleEvent(new ActivityStartEvent(25240, person.getId(), secondary.getLinkId(), null, "secondary"));
        assertEquals(20, observer.trips.get("p/0").carSeconds()); assertEquals(40, observer.trips.get("p/0").doorSeconds());
        observer.handleEvent(new ActivityEndEvent(27000, person.getId(), secondary.getLinkId(), null, "secondary"));
        assertEquals(27000, observer.trips.get("p/1").departure);
        home.setEndTime(28000); cohort.restorePlannedTimes(); assertEquals(25200, home.getEndTime().seconds());
    }

    @Test void publicConfigAndBothModesRunWithoutApiAndDoNotOverwrite() throws Exception {
        Path config = Path.of("scenarios/SiouxFalls-public/config.xml").toAbsolutePath();
        var scoring = ConfigUtils.loadConfig(config.toString()).scoring();
        assertEquals(32400, scoring.getActivityParams("work").getLatestStartTime().seconds());
        assertTrue(scoring.getActivityParams("work").isScoringThisActivityAtAll());
        for (String mode : List.of("fixed-routes", "reference")) {
            Path output = directory.resolve(mode);
            int exit = new CommandLine(new MorningExperiment()).execute("--config", config.toString(), "--mode", mode,
                    "--demand", "100", "--sample-persons", "40", "--days", "2", "--seed", "4711", "--output", output.toString());
            assertEquals(0, exit);
            var rows = readLines(output.resolve("morning/metrics.jsonl.gz")); assertEquals(2, rows.size());
            var first = Json.readTree(rows.getFirst()); var last = Json.readTree(rows.getLast());
            assertTrue(first.get("cohortTrips").asInt() > 0); assertTrue(first.get("completedOdTrips").asInt() > 0);
            assertTrue(first.get("dsr").isNull()); assertTrue(last.get("dsr").asDouble() >= 0 && last.get("dsr").asDouble() <= 1);
            assertTrue(Json.readTree(Files.readString(output.resolve("morning/status.json"))).get("complete").asBoolean());
            assertEquals(0, Json.readTree(Files.readString(output.resolve("morning/manifest.json"))).get("apiCalls").asInt());
            var manifest = Json.readTree(Files.readString(output.resolve("morning/manifest.json")));
            assertEquals(23, manifest.get("schemaVersion").asInt());
            assertEquals(MorningPromptRenderer.VERSION,manifest.at("/promptEncoding/travelerVersion").asText());
            assertEquals("gzip", manifest.get("jsonlOutput").get("compression").asText());
            assertEquals(1, manifest.get("tripOutputInterval").asInt());
            assertEquals(scoring.getLateArrival_utils_hr(), manifest.get("lateArrivalUtilsPerHour").asDouble());
            assertEquals(scoring.getModes().get("car").getMarginalUtilityOfTraveling(), manifest.get("carTravelUtilsPerHour").asDouble());
            assertEquals("10.1080/18128602.2012.671383", manifest.get("candidateMethodReference").get("doi").asText());
            assertEquals("cost-diversity-v1", manifest.get("candidateSelectionVersion").asText());
            assertEquals(16, manifest.get("candidateSettings").get("initialPoolSize").asInt());
            assertEquals(2.5, manifest.get("candidateSettings").get("maxFreeFlowTimeRatio").asDouble());
            assertEquals(300, manifest.get("candidateSettings").get("freeFlowTimeSlackSeconds").asDouble());
            assertEquals(.85, manifest.get("candidateSettings").get("maxPairwiseOverlap").asDouble());
            assertEquals(readLines(output.resolve("morning/candidates.jsonl.gz")).size(),
                    readLines(output.resolve("morning/candidate-quality.jsonl.gz")).size());
            assertFalse(Files.exists(output.resolve("morning/trips.jsonl")));
            if (mode.equals("fixed-routes")) {
                var contexts = readLines(output.resolve("morning/context-samples.jsonl.gz"));
                assertTrue(Json.readTree(contexts.getLast()).get("history").size() > 0);
                assertFalse(contexts.getFirst().contains("matsimScore"));
                assertTrue(contexts.stream().noneMatch(c -> c.toLowerCase(Locale.ROOT).contains("ewma")));
                assertTrue(contexts.stream().map(Json::readTree).allMatch(c -> c.get("candidates").size() <= 4));
                var nextDayContexts = contexts.stream().map(Json::readTree).filter(c -> c.get("day").asInt() == 1).toList();
                assertTrue(nextDayContexts.stream().flatMap(c -> java.util.stream.StreamSupport.stream(c.get("candidates").spliterator(), false))
                        .allMatch(o -> o.get("forecastObservedDay").isNull() && o.get("estimateSource").asText().equals("static_free_flow")));
                var candidateFreeFlow = new HashMap<String, Double>();
                for (var line : readLines(output.resolve("morning/candidates.jsonl.gz"))) {
                    var od = Json.readTree(line);
                    for (var route : od.get("routes")) candidateFreeFlow.put(od.get("od").asText() + "/" + route.get("routeId").asText(), route.get("freeFlowSeconds").asDouble());
                }
                boolean includesObservedSlowdown = nextDayContexts.stream().anyMatch(c -> java.util.stream.StreamSupport.stream(c.get("candidates").spliterator(), false).anyMatch(o -> {
                    String key = o.get("links").get(0).asText() + ">" + o.get("links").get(o.get("links").size() - 1).asText() + "/" + o.get("routeId").asText();
                    return o.get("expectedCarSeconds").asDouble() > candidateFreeFlow.get(key) + .001;
                }));
                assertFalse(includesObservedSlowdown, "Static path information must not leak historical network congestion");
                var savedPlans = new HashMap<Integer, org.matsim.api.core.v01.population.Population>();
                for (int day = 0; day < 2; day++) {
                    var saved = ScenarioUtils.createScenario(ConfigUtils.createConfig());
                    new PopulationReader(saved).readFile(output.resolve("ITERS/it." + day + "/" + day + ".plans.xml.zst").toString());
                    savedPlans.put(day, saved.getPopulation());
                }
                Map<String, Double> departures = new HashMap<>();
                var tripRows = readLines(output.resolve("morning/trips.jsonl.gz"));
                assertEquals(2 * manifest.get("cohortTrips").asInt(), tripRows.size(), "Compression must preserve every trip on every day");
                for (String line : tripRows) {
                    var trip = Json.readTree(line); String id = trip.get("tripId").asText();
                    double planned = trip.get("plannedDepartureSeconds").asDouble();
                    assertEquals(planned, departures.getOrDefault(id, planned)); departures.put(id, planned);
                    String[] parts = id.split("/"); int day = trip.get("day").asInt();
                    var person = savedPlans.get(day).getPersons().get(Id.createPersonId(parts[0]));
                    var savedTrip = TripStructureUtils.getTrips(person.getSelectedPlan()).get(Integer.parseInt(parts[1]));
                    var route = (NetworkRoute) MorningCohort.carLeg(savedTrip).getRoute();
                    var ids = new ArrayList<>(); ids.add(route.getStartLinkId()); ids.addAll(route.getLinkIds());
                    if (!route.getStartLinkId().equals(route.getEndLinkId()) || !route.getLinkIds().isEmpty()) ids.add(route.getEndLinkId());
                    assertEquals(trip.get("routeFingerprint").asText(), ids.toString(), "Saved day plan must match that day's chosen route");
                }
            }
            String originalStatus = Files.readString(output.resolve("morning/status.json"));
            assertEquals(0, new CommandLine(new MorningExperiment()).execute("--config", config.toString(), "--mode", mode,
                    "--demand", "100", "--sample-persons", "40", "--days", "2", "--seed", "4711", "--output", output.toString(), "--validate-only"));
            Path repeated = output.resolveSibling(output.getFileName() + "-2");
            var repeatedManifest = Json.readTree(Files.readString(repeated.resolve("morning/manifest.json")));
            assertEquals(2, repeatedManifest.get("outputNaming").get("repetition").asInt());
            assertEquals(repeated.getFileName().toString(), repeatedManifest.get("runName").asText());
            assertEquals(originalStatus, Files.readString(output.resolve("morning/status.json")), "Repeat must preserve the original simulation");
            if (mode.equals("fixed-routes")) {
                Path reused = directory.resolve("reused");
                assertEquals(0, new CommandLine(new MorningExperiment()).execute("--config", config.toString(), "--mode", mode,
                        "--demand", "100", "--sample-persons", "40", "--days", "2", "--seed", "4711", "--output", reused.toString(),
                        "--candidate-file", output.resolve("morning/candidates.jsonl.gz").toString(), "--validate-only"));
                assertEquals(readLines(output.resolve("morning/candidates.jsonl.gz")), readLines(reused.resolve("morning/candidates.jsonl.gz")));
                assertTrue(Json.readTree(Files.readString(reused.resolve("morning/manifest.json"))).has("candidateFileHash"));
                assertEquals("frozen-file-validation-v1", Json.readTree(Files.readString(reused.resolve("morning/manifest.json")))
                        .get("candidateSelectionVersion").asText());
            }
        }
    }

    @Test void networkWindowIncludesBackgroundCarsButExcludesWaitingAndParking() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var cohort = new MorningCohort(scenario, 100, 200, 20);
        var observer = new MorningObservations(scenario.getNetwork(), cohort, 100, 200); observer.reset(0);
        var p1 = Id.createPersonId("background1"); var p2 = Id.createPersonId("background2");
        var v1 = Id.createVehicleId("v1"); var v2 = Id.createVehicleId("v2"); var link = Id.createLinkId("origin");
        observer.handleEvent(new PersonDepartureEvent(80, p1, link, "car", "car"));
        observer.handleEvent(new VehicleEntersTrafficEvent(90, p1, link, v1, "car", 1));
        observer.handleEvent(new VehicleEntersTrafficEvent(150, p2, link, v2, "car", 1));
        observer.handleEvent(new VehicleLeavesTrafficEvent(190, p1, link, v1, "car", 1));
        observer.handleEvent(new VehicleLeavesTrafficEvent(230, p2, link, v2, "car", 1));
        var metrics = observer.networkSummary(300);
        assertEquals(1.4, ((Number) metrics.get("meanActiveCarVehicles")).doubleValue(), 1e-9);
        assertEquals(2, metrics.get("maximumActiveCarVehicles")); assertEquals(1, metrics.get("activeCarVehiclesAtWindowEnd"));
        assertEquals(1, metrics.get("carVehiclesEnteredInWindow")); assertEquals(1, metrics.get("carVehiclesExitedInWindow"));
    }
}
