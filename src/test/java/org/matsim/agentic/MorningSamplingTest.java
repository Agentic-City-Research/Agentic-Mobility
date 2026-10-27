package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.facilities.FacilitiesWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.matsim.agentic.JsonlTestSupport.readLines;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import picocli.CommandLine;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MorningSamplingTest {
    @TempDir Path directory;
    private Scenario population(int size, boolean reverse) {
        var config = ConfigUtils.createConfig();
        config.qsim().setFlowCapFactor(0.1);
        config.qsim().setStorageCapFactor(0.3);
        var scenario = ScenarioUtils.createScenario(config);
        var factory = scenario.getPopulation().getFactory();
        for (int i = 0; i < size; i++) {
            int index = reverse ? size - 1 - i : i;
            var person = factory.createPerson(Id.createPersonId("p" + index));
            person.getAttributes().putAttribute("original", index);
            // Retaining a person must retain all plans, not only a morning leg.
            for (int plan = 0; plan < 2; plan++) person.addPlan(factory.createPlan());
            scenario.getPopulation().addPerson(person);
        }
        return scenario;
    }

    @Test void tenPercentIsFixedAcrossInputOrderAndManagerAndScalesBothCapacities() {
        Set<Id<org.matsim.api.core.v01.population.Person>> selected = null;
        for (boolean manager : List.of(false, true)) {
            var scenario = population(100, manager);
            var experiment = new MorningExperiment();
            var args = new ArrayList<>(List.of("--sample-rate", "0.1"));
            if (manager) args.add("--manager");
            new CommandLine(experiment).parseArgs(args.toArray(String[]::new));
            assertEquals(100, experiment.applySampling(scenario, 9261));
            assertEquals(10, scenario.getPopulation().getPersons().size());
            assertEquals(0.1, scenario.getConfig().qsim().getFlowCapFactor());
            assertEquals(0.1778279410038923, scenario.getConfig().qsim().getStorageCapFactor(), 1e-12);
            assertDoesNotThrow(scenario.getConfig()::checkConsistency);
            var ids = Set.copyOf(scenario.getPopulation().getPersons().keySet());
            if (selected != null) assertEquals(selected, ids);
            selected = ids;
            for (var person : scenario.getPopulation().getPersons().values()) {
                assertEquals(2, person.getPlans().size());
                assertNotNull(person.getAttributes().getAttribute("original"));
            }
        }
    }

    @Test void roundingFullRateAndLegacyCapacitySemanticsAreDistinct() {
        var experiment = new MorningExperiment();
        new CommandLine(experiment).parseArgs("--sample-rate", "0.25");
        var rounded = population(11, false);
        experiment.applySampling(rounded, 9261);
        assertEquals(2, rounded.getPopulation().getPersons().size());
        assertEquals(0.25, rounded.getConfig().qsim().getFlowCapFactor());
        assertEquals(0.3535533905932738, rounded.getConfig().qsim().getStorageCapFactor(), 1e-12);
        // Binary double arithmetic makes 100 * 0.29 slightly smaller than 29.
        new CommandLine(experiment).parseArgs("--sample-rate", "0.29");
        var decimalRate = population(100, false);
        experiment.applySampling(decimalRate, 9261);
        assertEquals(29, decimalRate.getPopulation().getPersons().size());
        new CommandLine(experiment).parseArgs("--sample-rate", "1");
        var full = population(11, false);
        experiment.applySampling(full, 9261);
        assertEquals(11, full.getPopulation().getPersons().size());
        assertEquals(1, full.getConfig().qsim().getFlowCapFactor());
        assertEquals(1, full.getConfig().qsim().getStorageCapFactor());
        experiment = new MorningExperiment();
        new CommandLine(experiment).parseArgs("--sample-persons", "2");
        var diagnostic = population(11, false);
        experiment.applySampling(diagnostic, 9261);
        assertEquals(2, diagnostic.getPopulation().getPersons().size());
        assertEquals(0.1, diagnostic.getConfig().qsim().getFlowCapFactor());
        assertEquals(0.3, diagnostic.getConfig().qsim().getStorageCapFactor());
        var unchanged = population(11, false);
        new MorningExperiment().applySampling(unchanged, 9261);
        assertEquals(11, unchanged.getPopulation().getPersons().size());
        assertEquals(0.3, unchanged.getConfig().qsim().getStorageCapFactor());
    }

    @Test void samplingSeedFixesPopulationAcrossSimulationSeedsAndInputOrder() {
        Set<Id<org.matsim.api.core.v01.population.Person>> selected = null;
        for (long simulationSeed : List.of(4711L, 9261L)) {
            var scenario = population(100, simulationSeed == 9261);
            scenario.getConfig().global().setRandomSeed(simulationSeed);
            var experiment = new MorningExperiment();
            new CommandLine(experiment).parseArgs("--sample-rate", "0.1", "--sample-seed", "20261008");
            experiment.applySampling(scenario, simulationSeed);
            var ids = Set.copyOf(scenario.getPopulation().getPersons().keySet());
            assertEquals(10, ids.size());
            if (selected != null) assertEquals(selected, ids);
            selected = ids;
            assertEquals(simulationSeed, scenario.getConfig().global().getRandomSeed());
        }
        var implicit = new MorningExperiment();
        var explicit = new MorningExperiment();
        new CommandLine(implicit).parseArgs("--sample-rate", "0.1");
        new CommandLine(explicit).parseArgs("--sample-rate", "0.1", "--population-seed", "4711");
        var legacyPopulation = population(100, false);
        var explicitPopulation = population(100, true);
        implicit.applySampling(legacyPopulation, 9261);
        explicit.applySampling(explicitPopulation, 4711);
        assertEquals(legacyPopulation.getPopulation().getPersons().keySet(), explicitPopulation.getPopulation().getPersons().keySet());
    }

    @Test void largerRateWithSameSampleSeedRetainsTheSmallerPopulation() {
        var small = population(100, false);
        var large = population(100, true);
        var experiment = new MorningExperiment();
        new CommandLine(experiment).parseArgs("--sample-rate", "0.1", "--sample-seed", "20261008");
        experiment.applySampling(small, 4711);
        new CommandLine(experiment).parseArgs("--sample-rate", "0.25", "--sample-seed", "20261008");
        experiment.applySampling(large, 9261);
        assertEquals(10, small.getPopulation().getPersons().size());
        assertEquals(25, large.getPopulation().getPersons().size());
        assertTrue(large.getPopulation().getPersons().keySet().containsAll(small.getPopulation().getPersons().keySet()));
        assertEquals(0.25, large.getConfig().qsim().getFlowCapFactor());
        assertEquals(0.3535533905932738, large.getConfig().qsim().getStorageCapFactor(), 1e-12);
    }

    @Test void invalidRatesAndConflictingPopulationSamplingFailBeforeLoading() {
        for (String rate : List.of("0", "-0.1", "1.1", "NaN", "Infinity"))
            assertNotEquals(0, new CommandLine(new MorningExperiment()).execute("--sample-rate", rate));
        assertNotEquals(0, new CommandLine(new MorningExperiment()).execute("--sample-rate", "0.1", "--sample-persons", "10"));
        var experiment = new MorningExperiment();
        new CommandLine(experiment).parseArgs("--sample-rate", "0.01");
        assertThrows(IllegalArgumentException.class, () -> experiment.applySampling(population(10, false), 9261));
    }

    @Test void compatibilityToleranceStillRejectsLargerCapacityMismatches() {
        var experiment = new MorningExperiment();
        new CommandLine(experiment).parseArgs("--sample-rate", "0.2");
        var scenario = population(100, false);
        experiment.applySampling(scenario, 268);
        assertEquals(0.29906975624424414, scenario.getConfig().qsim().getStorageCapFactor(), 1e-12);
        assertDoesNotThrow(scenario.getConfig()::checkConsistency);
        scenario.getConfig().qsim().setStorageCapFactor(0.7);
        var error = assertThrows(RuntimeException.class, scenario.getConfig()::checkConsistency);
        assertTrue(error.getMessage().contains("storageCapFactor"));
    }

    @Test void sampledQSimRunsExportCompleteEvaluationAndStablePopulationFingerprints() throws Exception {
        Path config = writeSmallScenario();
        var rates = List.of("0.1", "0.2", "0.3", "1.0", "0.1");
        String firstPopulationHash = null;
        for (int run = 0; run < rates.size(); run++) {
            String rate = rates.get(run);
            long simulationSeed = run == 4 ? 4711 : 268;
            Path output = directory.resolve("run-" + rate + "-" + simulationSeed);
            var experiment = new MorningExperiment();
            experiment.clientFactory = (settings, usage) -> { throw new AssertionError("Native sampling must not call an LLM"); };
            var args = new ArrayList<>(List.of("--config", config.toString(), "--mode", "hybrid", "--llm-agents", "0",
                    "--days", "1", "--sample-rate", rate, "--seed", Long.toString(simulationSeed), "--output", output.toString()));
            if (run == 4) args.addAll(List.of("--profile-seed", "99", "--information-seed", "88"));
            assertEquals(0, new CommandLine(experiment).execute(args.toArray(String[]::new)));
            int persons = (int) (20 * Double.parseDouble(rate));
            var status = Json.readTree(Files.readString(output.resolve("morning/status.json")));
            assertTrue(status.get("complete").asBoolean()); assertEquals(1, status.get("daysCompleted").asInt());
            var metrics = readLines(output.resolve("morning/metrics.jsonl.gz"));
            assertEquals(1, metrics.size());
            var day = Json.readTree(metrics.getFirst());
            var travel = day.get("fullDayCarTravel");
            assertEquals(persons * 2, travel.get("expectedCarLegs").asInt());
            assertEquals(persons * 2, travel.get("completedCarLegs").asInt());
            assertEquals(1.0, travel.get("completionFractionAll").asDouble());
            assertTrue(travel.get("allPlannedCarLegsCompleted").asBoolean());
            assertEquals(travel.get("completedCarPersonHours").asDouble(), travel.get("carPersonHours").asDouble());
            assertEquals(0, travel.get("notStartedCarLegs").asInt());
            assertTrue(day.get("slowestFivePercentMeanCarSeconds").asDouble() >= day.get("p95CarSeconds").asDouble());
            assertEquals(1000.0, day.get("meanSelectedRouteDistanceMetersCompleted").asDouble());
            assertEquals(1.0, day.get("onTimePlannedArrivalFractionAll").asDouble());
            assertTrue(day.get("meanPreTrafficWaitSecondsCompleted").asDouble() >= 0);
            var manifest = Json.readTree(Files.readString(output.resolve("morning/manifest.json")));
            assertEquals(persons, manifest.get("simulatedPopulation").asInt());
            assertEquals(Double.parseDouble(rate), manifest.get("flowCapacityFactor").asDouble());
            assertEquals(Math.pow(Double.parseDouble(rate), .75), manifest.get("storageCapacityFactor").asDouble(), 1e-12);
            double tolerance = manifest.get("populationSampling").get("capacityScaling").get("consistencyRelativeTolerance").asDouble();
            assertTrue(tolerance >= 0 && tolerance < 1);
            if (rate.equals("1.0")) assertEquals(0.0, tolerance);
            var seeds = manifest.get("selectionSeeds");
            assertEquals(4711, seeds.get("population").asLong());
            assertEquals(4711, seeds.get("controlledRoster").asLong());
            assertEquals(simulationSeed, seeds.get("simulation").asLong());
            assertEquals(run == 4 ? 99 : 4711, seeds.get("profiles").asLong());
            assertEquals(run == 4 ? 88 : 4711, seeds.get("informationGroups").asLong());
            String populationHash = manifest.get("simulatedPersonIdsSha256").asText();
            assertTrue(populationHash.matches("[a-f0-9]{64}"));
            if (run == 0) firstPopulationHash = populationHash;
            else if (run == 4) assertEquals(firstPopulationHash, populationHash);
            else assertNotEquals(firstPopulationHash, populationHash);
            assertEquals(0, manifest.get("apiCalls").asInt());
        }
    }

    private Path writeSmallScenario() throws java.io.IOException {
        var config = ConfigUtils.createConfig();
        Path net = directory.resolve("network.xml"), pop = directory.resolve("population.xml"), facilities = directory.resolve("facilities.xml"), cfg = directory.resolve("config.xml");
        config.network().setInputFile(net.toString()); config.plans().setInputFile(pop.toString()); config.facilities().setInputFile(facilities.toString());
        config.qsim().setEndTime(108000); config.global().setNumberOfThreads(1); config.qsim().setNumberOfThreads(1);
        config.qsim().setInsertingWaitingVehiclesBeforeDrivingVehicles(false);
        config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.none);
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("home").setTypicalDuration(13 * 3600));
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("work").setTypicalDuration(9 * 3600));
        config.replanning().setMaxAgentPlanMemorySize(6);
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(.1));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(.9));
        config.controller().setWriteEventsInterval(0); config.controller().setWritePlansInterval(1); config.controller().setCreateGraphsInterval(0);
        var scenario = ScenarioUtils.createScenario(config); var network = scenario.getNetwork(); var nf = network.getFactory();
        var n0 = nf.createNode(Id.createNodeId("n0"), new Coord(0, 0));
        var n1 = nf.createNode(Id.createNodeId("n1"), new Coord(1000, 0));
        network.addNode(n0); network.addNode(n1);
        var out = nf.createLink(Id.createLinkId("out"), n0, n1);
        var back = nf.createLink(Id.createLinkId("back"), n1, n0);
        for (var link : List.of(out, back)) {
            link.setLength(1000); link.setFreespeed(10); link.setCapacity(3600); link.setNumberOfLanes(1); link.setAllowedModes(Set.of("car"));
            network.addLink(link);
        }
        var pf = scenario.getPopulation().getFactory();
        for (int i = 0; i < 20; i++) {
            var person = pf.createPerson(Id.createPersonId("p" + i)); var plan = pf.createPlan();
            var home = pf.createActivityFromLinkId("home", out.getId()); home.setEndTime(25200); plan.addActivity(home);
            plan.addLeg(pf.createLeg("car"));
            var work = pf.createActivityFromLinkId("work", back.getId()); work.setStartTime(28800); work.setEndTime(61200); plan.addActivity(work);
            plan.addLeg(pf.createLeg("car")); plan.addActivity(pf.createActivityFromLinkId("home", out.getId()));
            person.addPlan(plan); scenario.getPopulation().addPerson(person);
        }
        new NetworkWriter(network).write(net.toString()); new PopulationWriter(scenario.getPopulation()).write(pop.toString());
        new FacilitiesWriter(scenario.getActivityFacilities()).write(facilities.toString());
        new ConfigWriter(config).write(cfg.toString());
        for (Path file : List.of(net, pop, facilities, cfg)) MorningJointTestSupport.useBundledDtd(file);
        return cfg;
    }
}
