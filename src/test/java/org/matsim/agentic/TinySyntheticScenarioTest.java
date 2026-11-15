package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.facilities.ActivityFacility;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class TinySyntheticScenarioTest {
    @TempDir Path directory;

    private Path copyScenario() throws Exception {
        Path source = Path.of("scenarios/TinySynthetic");
        Path target = directory.resolve("scenario");
        Files.createDirectories(target);
        for (String name : List.of("config.xml", "network.xml", "population.xml", "facilities.xml")) {
            Path file = target.resolve(name);
            Files.copy(source.resolve(name), file);
            MorningJointTestSupport.useBundledDtd(file);
        }
        return target.resolve("config.xml");
    }

    private Scenario load() throws Exception {
        Config config = ConfigUtils.loadConfig(copyScenario().toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        ScenarioUtils.loadScenario(scenario);
        return scenario;
    }

    @Test void networkHasSixNodesAndTwelveDirectedCarLinks() throws Exception {
        Scenario scenario = load();
        var network = scenario.getNetwork();
        assertEquals(6, network.getNodes().size());
        assertEquals(12, network.getLinks().size());
        assertEquals(3600, network.getCapacityPeriod(), .001);

        record Expected(String id, String from, String to, double length, double speed) {}
        List<Expected> links = List.of(
                new Expected("hs", "H", "S", 100, 10), new Expected("sh", "S", "H", 100, 10),
                new Expected("su", "S", "U", 500, 10), new Expected("us", "U", "S", 500, 10),
                new Expected("ut", "U", "T", 500, 10), new Expected("tu", "T", "U", 500, 10),
                new Expected("sl", "S", "L", 500, 8), new Expected("ls", "L", "S", 500, 8),
                new Expected("lt", "L", "T", 500, 8), new Expected("tl", "T", "L", 500, 8),
                new Expected("tw", "T", "W", 100, 10), new Expected("wt", "W", "T", 100, 10));
        for (Expected expected : links) {
            Link link = network.getLinks().get(Id.createLinkId(expected.id()));
            assertNotNull(link, expected.id());
            assertEquals(expected.from(), link.getFromNode().getId().toString());
            assertEquals(expected.to(), link.getToNode().getId().toString());
            assertEquals(expected.length(), link.getLength(), .001);
            assertEquals(expected.speed(), link.getFreespeed(), .001);
            assertEquals(1000, link.getCapacity(), .001);
            assertEquals(1, link.getNumberOfLanes(), .001);
            assertEquals(Set.of("car"), link.getAllowedModes());
        }
    }

    @Test void populationHasTenCompleteCommutesWithExplicitLinkEndpoints() throws Exception {
        Scenario scenario = load();
        assertEquals(10, scenario.getPopulation().getPersons().size());

        for (int i = 0; i < 10; i++) {
            Person person = scenario.getPopulation().getPersons().get(Id.createPersonId(String.format("p%02d", i + 1)));
            assertNotNull(person);
            Plan plan = person.getSelectedPlan();
            assertNotNull(plan);
            List<Activity> activities = plan.getPlanElements().stream()
                    .filter(Activity.class::isInstance).map(Activity.class::cast).toList();
            List<Leg> legs = plan.getPlanElements().stream()
                    .filter(Leg.class::isInstance).map(Leg.class::cast).toList();
            assertEquals(3, activities.size());
            assertEquals(2, legs.size());
            assertEquals("home", activities.get(0).getType());
            assertEquals("work", activities.get(1).getType());
            assertEquals("home", activities.get(2).getType());
            assertEquals(Id.createLinkId("sh"), activities.get(0).getLinkId());
            assertEquals(Id.createLinkId("tw"), activities.get(1).getLinkId());
            assertEquals(Id.createLinkId("sh"), activities.get(2).getLinkId());
            assertEquals(Id.create("home", ActivityFacility.class), activities.get(0).getFacilityId());
            assertEquals(Id.create("work", ActivityFacility.class), activities.get(1).getFacilityId());
            assertEquals(Id.create("home", ActivityFacility.class), activities.get(2).getFacilityId());
            assertEquals(new Coord(0, 0), activities.get(0).getCoord());
            assertEquals(new Coord(1000, 0), activities.get(1).getCoord());
            assertEquals(28800 + 30 * i, activities.get(0).getEndTime().seconds(), .001);
            assertEquals(29700, activities.get(1).getStartTime().seconds(), .001);
            assertEquals(61200 + 30 * i, activities.get(1).getEndTime().seconds(), .001);
            assertFalse(activities.get(2).getStartTime().isDefined());
            assertFalse(activities.get(2).getEndTime().isDefined());
            assertEquals("car", legs.get(0).getMode());
            assertEquals("car", legs.get(1).getMode());
        }
    }

    @Test void facilitiesMatchTheActivityEndpoints() throws Exception {
        Scenario scenario = load();
        ActivityFacility home = scenario.getActivityFacilities().getFacilities().get(Id.create("home", ActivityFacility.class));
        ActivityFacility work = scenario.getActivityFacilities().getFacilities().get(Id.create("work", ActivityFacility.class));
        assertNotNull(home);
        assertNotNull(work);
        assertEquals(Id.createLinkId("sh"), home.getLinkId());
        assertEquals(Id.createLinkId("tw"), work.getLinkId());
        assertEquals(new Coord(0, 0), home.getCoord());
        assertEquals(new Coord(1000, 0), work.getCoord());
    }

    @Test void configPinsTheNativeTwoDayGzipSetup() throws Exception {
        Config config = ConfigUtils.loadConfig(copyScenario().toString());
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(1, config.controller().getLastIteration());
        assertEquals(108000, config.qsim().getEndTime().seconds(), .001);
        assertEquals(1, config.global().getNumberOfThreads());
        assertEquals(1, config.qsim().getNumberOfThreads());
        assertEquals(RoutingConfigGroup.AccessEgressType.none, config.routing().getAccessEgressType());
        assertEquals(0, config.routing().getRoutingRandomness(), .001);
        assertEquals(1, config.controller().getWriteEventsInterval());
        assertEquals(1, config.controller().getWritePlansInterval());
        assertEquals(ControllerConfigGroup.CompressionType.gzip, config.controller().getCompressionType());
        assertEquals(6, config.replanning().getMaxAgentPlanMemorySize());
        assertEquals(32400, config.scoring().getActivityParams("work").getLatestStartTime().seconds(), .001);
        assertEquals(13 * 3600, config.scoring().getActivityParams("home").getTypicalDuration().seconds(), .001);
        assertEquals(9 * 3600, config.scoring().getActivityParams("work").getTypicalDuration().seconds(), .001);
        var strategies = config.replanning().getStrategySettings().stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.getStrategyName(), s -> s.getWeight()));
        assertEquals(.7, strategies.get("ChangeExpBeta"), .001);
        assertEquals(.1, strategies.get("ReRoute"), .001);
        assertEquals(.1, strategies.get("TimeAllocationMutator"), .001);
    }

    @Test void demandAndCapacityXmlEditsRemainStructurallyValid() throws Exception {
        Path config = copyScenario();
        Config cfg = ConfigUtils.loadConfig(config.toString());
        Scenario scenario = ScenarioUtils.createScenario(cfg);
        ScenarioUtils.loadScenario(scenario);

        var population = scenario.getPopulation();
        population.removePerson(Id.createPersonId("p10"));
        assertEquals(9, population.getPersons().size());

        var factory = population.getFactory();
        var source = population.getPersons().get(Id.createPersonId("p01")).getSelectedPlan();
        var added = factory.createPerson(Id.createPersonId("p11"));
        var plan = factory.createPlan();
        for (var element : source.getPlanElements()) {
            if (element instanceof Activity activity) {
                var copy = factory.createActivityFromLinkId(activity.getType(), activity.getLinkId());
                copy.setFacilityId(activity.getFacilityId());
                copy.setCoord(activity.getCoord());
                if (activity.getStartTime().isDefined()) copy.setStartTime(activity.getStartTime().seconds());
                if (activity.getEndTime().isDefined()) copy.setEndTime(activity.getEndTime().seconds());
                plan.addActivity(copy);
            } else if (element instanceof Leg leg) {
                plan.addLeg(factory.createLeg(leg.getMode()));
            }
        }
        plan.getPlanElements().stream().filter(Activity.class::isInstance).map(Activity.class::cast).toList()
                .get(0).setEndTime(29085);
        plan.getPlanElements().stream().filter(Activity.class::isInstance).map(Activity.class::cast).toList()
                .get(1).setStartTime(29700);
        plan.getPlanElements().stream().filter(Activity.class::isInstance).map(Activity.class::cast).toList()
                .get(1).setEndTime(61485);
        added.addPlan(plan);
        population.addPerson(added);

        Path editedPopulation = config.getParent().resolve("population-edited.xml");
        new PopulationWriter(population).write(editedPopulation.toString());
        MorningJointTestSupport.useBundledDtd(editedPopulation);
        var reloaded = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new org.matsim.core.population.io.PopulationReader(reloaded).readFile(editedPopulation.toString());
        assertEquals(10, reloaded.getPopulation().getPersons().size());
        assertEquals(10, new java.util.HashSet<>(reloaded.getPopulation().getPersons().keySet()).size());
        for (var person : reloaded.getPopulation().getPersons().values()) {
            var activities = person.getSelectedPlan().getPlanElements().stream()
                    .filter(Activity.class::isInstance).map(Activity.class::cast).toList();
            var carLegs = person.getSelectedPlan().getPlanElements().stream()
                    .filter(Leg.class::isInstance).map(Leg.class::cast)
                    .filter(leg -> leg.getMode().equals("car")).count();
            assertEquals(3, activities.size(), person.getId().toString());
            assertEquals(2, carLegs, person.getId().toString());
        }
        var p11 = reloaded.getPopulation().getPersons().get(Id.createPersonId("p11"));
        var p11Activities = p11.getSelectedPlan().getPlanElements().stream()
                .filter(Activity.class::isInstance).map(Activity.class::cast).toList();
        assertEquals(29085, p11Activities.get(0).getEndTime().seconds(), .001);
        assertEquals(29700, p11Activities.get(1).getStartTime().seconds(), .001);
        assertEquals(61485, p11Activities.get(1).getEndTime().seconds(), .001);

        var network = scenario.getNetwork();
        network.getLinks().get(Id.createLinkId("su")).setCapacity(500);
        network.getLinks().get(Id.createLinkId("us")).setCapacity(500);
        Path editedNetwork = config.getParent().resolve("network-edited.xml");
        new NetworkWriter(network).write(editedNetwork.toString());
        MorningJointTestSupport.useBundledDtd(editedNetwork);
        var reloadedNetwork = org.matsim.core.network.NetworkUtils.createNetwork();
        new org.matsim.core.network.io.MatsimNetworkReader(reloadedNetwork).readFile(editedNetwork.toString());
        assertEquals(500, reloadedNetwork.getLinks().get(Id.createLinkId("su")).getCapacity(), .001);
        assertEquals(500, reloadedNetwork.getLinks().get(Id.createLinkId("us")).getCapacity(), .001);
        assertEquals(1000, reloadedNetwork.getLinks().get(Id.createLinkId("hs")).getCapacity(), .001);
        assertEquals(3600, reloadedNetwork.getCapacityPeriod(), .001);
    }

    @Test void twoDayNativeRunCompletesWithoutModelClients() throws Exception {
        Path config = copyScenario();
        Path output = directory.resolve("two-day");
        var experiment = new MorningExperiment();
        experiment.clientFactory = (cfg, usage) -> {
            throw new AssertionError("A model client must not be created for a native run");
        };
        int exit = new CommandLine(experiment).execute(
                "--config", config.toString(),
                "--population", config.getParent().resolve("population.xml").toString(),
                "--demand", "100",
                "--scenario-source", "TinySynthetic: synthetic and uncalibrated",
                "--mode", "hybrid", "--policy", "mnl", "--llm-agents", "0",
                "--days", "2", "--seed", "4711", "--population-seed", "4711",
                "--native-time-mutation", "--trip-output-interval", "1",
                "--output", output.toString());
        assertEquals(0, exit);

        var status = Json.readTree(Files.readString(output.resolve("morning/status.json")));
        assertTrue(status.get("complete").asBoolean());
        assertEquals(2, status.get("daysCompleted").asInt());
        assertTrue(status.get("integrityVerified").asBoolean());
        assertEquals(0, status.get("apiCalls").asInt());

        var manifest = Json.readTree(Files.readString(output.resolve("morning/manifest.json")));
        assertEquals(23, manifest.get("schemaVersion").asInt());
        assertEquals("TinySynthetic: synthetic and uncalibrated", manifest.get("source").asText());
        assertEquals("explicit-scenario-declaration", manifest.get("sourceBasis").asText());
        assertEquals(0, manifest.get("apiCalls").asInt());
        assertFalse(manifest.has("sourceWarning"));

        assertEquals(10, readLines(output.resolve("morning/cohort.jsonl.gz")).size());
        assertEquals(1, readLines(output.resolve("morning/candidates.jsonl.gz")).size());
        assertEquals(1, readLines(output.resolve("morning/candidate-quality.jsonl.gz")).size());
        var trips = readLines(output.resolve("morning/trips.jsonl.gz")).stream().map(Json::readTree).toList();
        var experiences = readLines(output.resolve("morning/experiences.jsonl.gz")).stream().map(Json::readTree).toList();
        var metrics = readLines(output.resolve("morning/metrics.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(20, trips.size());
        assertEquals(20, experiences.size());
        assertEquals(2, metrics.size());
        assertEquals(20, trips.stream().map(t -> t.get("day").asInt() + "/" + t.get("tripId").asText()).distinct().count());
        assertEquals(20, experiences.stream().map(t -> t.get("day").asInt() + "/" + t.get("tripId").asText()).distinct().count());
        for (var metric : metrics) {
            var travel = metric.get("fullDayCarTravel");
            assertEquals(20, travel.get("expectedCarLegs").asInt());
            assertEquals(20, travel.get("completedCarLegs").asInt());
            assertEquals(0, travel.get("notStartedCarLegs").asInt());
            assertEquals(0, travel.get("abortedCarLegs").asInt());
            assertEquals(0, travel.get("unfinishedCarLegs").asInt());
            assertTrue(travel.get("allPlannedCarLegsCompleted").asBoolean());
        }
        for (String name : List.of("profiles.jsonl.gz", "information-groups.jsonl.gz",
                "decisions.jsonl.gz", "executed-route-checks.jsonl.gz")) {
            assertFalse(Files.exists(output.resolve("morning/" + name)), name);
        }

        for (int day = 0; day < 2; day++) {
            Path eventsFile = output.resolve("ITERS/it." + day + "/" + day + ".events.xml.gz");
            Path plansFile = output.resolve("ITERS/it." + day + "/" + day + ".plans.xml.gz");
            assertTrue(Files.exists(eventsFile), eventsFile.toString());
            assertTrue(Files.exists(plansFile), plansFile.toString());

            var events = org.matsim.core.events.EventsUtils.createEventsManager();
            var departures = new java.util.HashMap<String, Integer>();
            var arrivals = new java.util.HashMap<String, Integer>();
            events.addHandler((org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler) event -> {
                if ("car".equals(event.getLegMode())) {
                    departures.merge(event.getPersonId().toString(), 1, Integer::sum);
                }
            });
            events.addHandler((org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler) event -> {
                if ("car".equals(event.getLegMode())) {
                    arrivals.merge(event.getPersonId().toString(), 1, Integer::sum);
                }
            });
            new org.matsim.core.events.MatsimEventsReader(events).readFile(eventsFile.toString());
            assertEquals(10, departures.size());
            assertEquals(20, departures.values().stream().mapToInt(Integer::intValue).sum());
            for (int i = 1; i <= 10; i++) {
                String person = String.format("p%02d", i);
                assertEquals(2, departures.get(person), person);
                assertEquals(2, arrivals.get(person), person);
            }

            var saved = ScenarioUtils.createScenario(ConfigUtils.createConfig());
            new org.matsim.core.population.io.PopulationReader(saved).readFile(plansFile.toString());
            assertEquals(10, saved.getPopulation().getPersons().size());
            for (var person : saved.getPopulation().getPersons().values()) {
                long carLegs = person.getSelectedPlan().getPlanElements().stream()
                        .filter(Leg.class::isInstance).map(Leg.class::cast)
                        .filter(leg -> leg.getMode().equals("car")).count();
                assertEquals(2, carLegs, person.getId().toString());
            }
        }
    }

    private record Loaded(Scenario scenario, MorningCohort cohort) {}

    private Loaded loadCohort(Path configPath) throws Exception {
        Config config = ConfigUtils.loadConfig(configPath.toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        ScenarioUtils.loadScenario(scenario);
        var cohort = new MorningCohort(scenario, 0, 32400, 20,
                new MatsimOdCandidates(scenario.getNetwork(), scenario.getConfig()), "planned-arrival", 32400);
        return new Loaded(scenario, cohort);
    }

    private List<String> ids(RouteCandidate route) {
        return route.linkIds().stream().map(Object::toString).toList();
    }

    private double distance(RouteCandidate route, Scenario scenario) {
        return route.linkIds().stream().skip(1)
                .map(scenario.getNetwork().getLinks()::get).mapToDouble(Link::getLength).sum();
    }

    private double overlap(RouteCandidate a, RouteCandidate b, Scenario scenario) {
        var bLinks = new java.util.HashSet<>(b.linkIds().subList(1, b.linkIds().size()));
        double shared = a.linkIds().stream().skip(1).filter(bLinks::contains)
                .map(scenario.getNetwork().getLinks()::get).mapToDouble(Link::getLength).sum();
        return shared / Math.min(distance(a, scenario), distance(b, scenario));
    }

    private void assertExpectedEndpoints(MorningCohort cohort) {
        assertEquals(10, cohort.members.size());
        for (var member : cohort.members.values()) {
            assertEquals(Id.createLinkId("sh"), member.candidates.getFirst().linkIds().getFirst(), member.key);
            assertEquals(Id.createLinkId("tw"), member.candidates.getFirst().linkIds().getLast(), member.key);
            assertTrue(member.candidates.size() >= 2, member.key);
        }
    }

    private void assertExplicitActivityLinks(Path populationXml) throws Exception {
        String text = Files.readString(populationXml);
        var matcher = java.util.regex.Pattern.compile("<activity\\b[^>]*>").matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
            assertTrue(matcher.group().contains(" link=\""), "Activity lacks explicit link: " + matcher.group());
        }
        assertEquals(30, count);
    }

    private Path variant(String name, boolean missingHomeLink, boolean reversedLinks) throws Exception {
        Path source = Path.of("scenarios/TinySynthetic");
        Path target = directory.resolve(name);
        Files.createDirectories(target);
        for (String file : List.of("config.xml", "network.xml", "population.xml", "facilities.xml")) {
            Files.copy(source.resolve(file), target.resolve(file));
        }
        Path population = target.resolve("population.xml");
        String text = Files.readString(population);
        if (missingHomeLink) text = text.replace(" link=\"sh\"", "");
        if (reversedLinks) {
            text = text.replace(" link=\"sh\"", " link=\"hs\"")
                    .replace(" link=\"tw\"", " link=\"wt\"");
        }
        Files.writeString(population, text);
        Path facilities = target.resolve("facilities.xml");
        String facilityText = Files.readString(facilities);
        if (reversedLinks) {
            facilityText = facilityText.replace("linkId=\"sh\"", "linkId=\"hs\"")
                    .replace("linkId=\"tw\"", "linkId=\"wt\"");
        }
        Files.writeString(facilities, facilityText);
        for (String file : List.of("config.xml", "network.xml", "population.xml", "facilities.xml")) {
            MorningJointTestSupport.useBundledDtd(target.resolve(file));
        }
        return target.resolve("config.xml");
    }

    @Test void loadedCohortUsesExplicitOdAndTwoCandidates() throws Exception {
        var loaded = loadCohort(copyScenario());
        var cohort = loaded.cohort();
        assertEquals(10, cohort.members.size());
        assertTrue(cohort.members.containsKey("p01/0"));
        assertExpectedEndpoints(cohort);

        var p01 = cohort.members.get("p01/0");
        assertEquals(28800, p01.plannedDeparture, .001);
        assertEquals(29700, p01.plannedArrival, .001);
        var c0 = p01.candidates.getFirst();
        assertEquals(List.of("sh", "hs", "su", "ut", "tw"), ids(c0));
        assertEquals(120, c0.freeFlowTravelTime(), .001);
        assertEquals(1200, distance(c0, loaded.scenario()), .001);
        var c1 = p01.candidates.stream()
                .filter(route -> ids(route).equals(List.of("sh", "hs", "sl", "lt", "tw")))
                .findFirst().orElseThrow();
        assertEquals(145, c1.freeFlowTravelTime(), .001);
        assertEquals(1200, distance(c1, loaded.scenario()), .001);
        assertEquals(1.0 / 6, overlap(c0, c1, loaded.scenario()), .001);

        var returnRoutes = new MatsimOdCandidates(loaded.scenario().getNetwork(), loaded.scenario().getConfig())
                .compute(Id.createLinkId("tw"), Id.createLinkId("sh"), 61200,
                        loaded.scenario().getPopulation().getPersons().get(Id.createPersonId("p01")));
        assertFalse(returnRoutes.isEmpty());
        assertEquals(Id.createLinkId("tw"), returnRoutes.getFirst().linkIds().getFirst());
        assertEquals(Id.createLinkId("sh"), returnRoutes.getFirst().linkIds().getLast());
    }

    @Test void missingOrReversedLinksAreCaughtByTheScenarioConstraint() throws Exception {
        Path missing = variant("missing-link", true, false);
        assertThrows(AssertionError.class, () -> assertExplicitActivityLinks(missing.getParent().resolve("population.xml")));

        Path reversed = variant("reversed-link", false, true);
        assertThrows(AssertionError.class, () -> assertExpectedEndpoints(loadCohort(reversed).cohort()));

        assertExplicitActivityLinks(Path.of("scenarios/TinySynthetic/population.xml"));
    }
}
