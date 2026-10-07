package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import picocli.CommandLine;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MorningSamplingTest {
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
            assertEquals(0.1, scenario.getConfig().qsim().getStorageCapFactor());
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
        assertEquals(0.25, rounded.getConfig().qsim().getStorageCapFactor());
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
        assertEquals(0.25, large.getConfig().qsim().getStorageCapFactor());
    }

    @Test void invalidRatesAndConflictingPopulationSamplingFailBeforeLoading() {
        for (String rate : List.of("0", "-0.1", "1.1", "NaN", "Infinity"))
            assertNotEquals(0, new CommandLine(new MorningExperiment()).execute("--sample-rate", rate));
        assertNotEquals(0, new CommandLine(new MorningExperiment()).execute("--sample-rate", "0.1", "--sample-persons", "10"));
        var experiment = new MorningExperiment();
        new CommandLine(experiment).parseArgs("--sample-rate", "0.01");
        assertThrows(IllegalArgumentException.class, () -> experiment.applySampling(population(10, false), 9261));
    }
}
