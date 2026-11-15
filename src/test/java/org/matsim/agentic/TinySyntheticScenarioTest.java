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
import org.matsim.facilities.ActivityFacility;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
