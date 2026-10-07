package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class MorningCohortSelectionTest {
    @TempDir Path directory;

    @Test void plannedArrivalIncludesEarlyDeparturesAndSecondaryButExcludesBoundaryAndUnknown() {
        var scenario = scenario();
        person(scenario, "early", 24300, 25200.0, "work");
        person(scenario, "late", 30000, 32700.0, "work");
        person(scenario, "boundary", 31500, 32400.0, "work");
        var missing = person(scenario, "missing", 28800, null, "work");
        MorningCohort.carLeg(TripStructureUtils.getTrips(missing.getSelectedPlan()).getFirst()).setTravelTime(60);
        person(scenario, "secondary", 27000, 28800.0, "secondary");
        person(scenario, "invalid", 27000, 26000.0, "work");
        var cohort = arrivals(scenario);
        assertEquals(Set.of("early/0", "secondary/0"), cohort.members.keySet());
        assertEquals(1, cohort.excludedMissingPlannedArrivalTrips);
        assertEquals(1, cohort.excludedInvalidPlannedArrivalTrips);
        var early = cohort.members.get("early/0");
        assertEquals(24300, early.plannedDeparture);
        assertEquals(25200, early.plannedArrival);
        assertEquals(32400, early.deadline, "The scoring deadline is not the planned arrival selector");
        assertEquals(25200.0, early.roster().get("plannedArrivalSeconds"));
        var legacy = new MorningCohort(scenario, 25200, 32400, 2);
        assertEquals(Set.of("late/0", "boundary/0", "missing/0", "secondary/0", "invalid/0"), legacy.members.keySet());
    }

    @Test void membershipAndPlannedArrivalStayFixedAfterPlanCopyAndScheduleChanges() {
        var scenario = scenario();
        var p = person(scenario, "p", 24300, 25200.0, "work");
        var f = scenario.getPopulation().getFactory();
        var plan = p.getSelectedPlan();
        var work = TripStructureUtils.getTrips(plan).getFirst().getDestinationActivity();
        work.setEndTime(31500);
        plan.addLeg(f.createLeg("car"));
        var later = f.createActivityFromLinkId("secondary", Id.createLinkId("l0"));
        later.setStartTime(32700); plan.addActivity(later);
        var cohort = arrivals(scenario);
        cohort.selectControlledPeople(1, 268);
        assertEquals(Set.of("p/0"), cohort.members.keySet());
        assertTrue(cohort.members.get("p/0").controlled);
        var copied = f.createPlan(); PopulationUtils.copyFromTo(plan, copied);
        p.addPlan(copied); p.setSelectedPlan(copied);
        var trip = TripStructureUtils.getTrips(copied).getFirst();
        trip.getOriginActivity().setEndTime(27000);
        trip.getDestinationActivity().setStartTime(40000);
        cohort.restorePlannedTimes();
        assertEquals(24300, trip.getOriginActivity().getEndTime().seconds());
        assertEquals(25200, cohort.members.get("p/0").plannedArrival);
        assertEquals(Set.of("p/0"), cohort.members.keySet());
        assertEquals(1, cohort.members.values().stream().filter(m -> m.controlled).count());
    }

    @Test void defaultCliExportsOriginalPlannedArrivalsWithoutAnyModelCalls() throws Exception {
        Path out = directory.resolve("validation");
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = null;
        settings.clientFactory = (config, usage) -> { throw new AssertionError("Validation must not call any model"); };
        assertEquals(0, new CommandLine(settings).execute("--config", Path.of("scenarios/SiouxFalls-public/config.xml").toAbsolutePath().toString(),
                "--mode", "hybrid", "--policy", "llm", "--llm-agents", "all", "--demand", "100",
                "--sample-persons", "40", "--seed", "4711", "--days", "2", "--validate-only", "--output", out.toString()));
        out = out.resolve("morning");
        var manifest = Json.readTree(Files.readString(out.resolve("manifest.json")));
        assertEquals("planned-arrival", manifest.get("cohortSelection").asText());
        assertEquals(0, manifest.get("cohortWindow").get(0).asInt());
        assertEquals(32400, manifest.get("cohortWindow").get(1).asInt());
        assertEquals(manifest.get("cohortPersons").asInt(), manifest.get("llmPersons").asInt());
        assertTrue(manifest.get("llmPersons").asInt() > 0);
        for (String line : readLines(out.resolve("cohort.jsonl.gz"))) {
            var row = Json.readTree(line);
            assertTrue(row.get("controlled").asBoolean());
            assertTrue(row.get("plannedArrivalSeconds").asDouble() < 32400);
            assertTrue(row.get("plannedArrivalSeconds").asDouble() >= row.get("plannedDepartureSeconds").asDouble());
        }
        assertEquals(0, Json.readTree(Files.readString(out.resolve("status.json"))).get("apiCalls").asInt());
    }

    @Test void invalidArrivalCutoffsAndSelectionModesAreRejected() {
        var scenario = scenario();
        var generator = new MatsimOdCandidates(scenario.getNetwork(), scenario.getConfig());
        for (double cutoff : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new MorningCohort(scenario, 25200, 32400, 2, generator, "planned-arrival", cutoff));
        assertThrows(IllegalArgumentException.class, () -> new MorningCohort(scenario, 25200, 32400, 2, generator, "actual-arrival", 32400));
    }

    private static MorningCohort arrivals(Scenario scenario) {
        return new MorningCohort(scenario, 25200, 32400, 2,
                new MatsimOdCandidates(scenario.getNetwork(), scenario.getConfig()), "planned-arrival", 32400);
    }

    private static Scenario scenario() {
        var config = ConfigUtils.createConfig();
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("work").setLatestStartTime(32400));
        var s = ScenarioUtils.createScenario(config); var n = s.getNetwork(); var f = n.getFactory();
        n.addNode(f.createNode(Id.createNodeId("a"), new Coord(0, 0)));
        n.addNode(f.createNode(Id.createNodeId("b"), new Coord(100, 0)));
        for (int i = 0; i < 2; i++) {
            var link = f.createLink(Id.createLinkId("l" + i), n.getNodes().get(Id.createNodeId(i == 0 ? "a" : "b")),
                    n.getNodes().get(Id.createNodeId(i == 0 ? "b" : "a")));
            link.setLength(100); link.setFreespeed(10); link.setAllowedModes(Set.of("car")); n.addLink(link);
        }
        return s;
    }

    private static Person person(Scenario s, String id, double departure, Double arrival, String purpose) {
        var f = s.getPopulation().getFactory(); var p = f.createPerson(Id.createPersonId(id)); var plan = f.createPlan();
        var origin = f.createActivityFromLinkId("home", Id.createLinkId("l0")); origin.setEndTime(departure);
        plan.addActivity(origin); plan.addLeg(f.createLeg("car"));
        var destination = f.createActivityFromLinkId(purpose, Id.createLinkId("l1"));
        if (arrival != null) destination.setStartTime(arrival);
        plan.addActivity(destination); p.addPlan(plan); s.getPopulation().addPerson(p); return p;
    }
}
