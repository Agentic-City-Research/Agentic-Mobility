package org.matsim.llmprocess;

import org.apache.logging.log4j.core.tools.picocli.CommandLine;
import org.matsim.application.MATSimApplication;
import org.matsim.agentic.scenario.CityScenarioAdapter;
import org.matsim.agentic.scenario.CityScenarioAdapters;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.population.PopulationUtils;

@CommandLine.Command(
        name = "agentic-matsim",
        header = ":: Agentic Mobility: MATSim + LLM agents ::",
        version = "0.1.0")
public final class AgenticMobilityApplication extends MATSimApplication {

    private static final String DEFAULT_CONFIG = "scenarios/sioux-falls/config.xml";
    private CityScenarioAdapter scenarioAdapter;

    public static void main(String[] args) {
        String[] effectiveArgs = args.length == 0
                ? new String[]{"--config", DEFAULT_CONFIG}
                : args;
        MATSimApplication.execute(AgenticMobilityApplication.class, effectiveArgs);
    }

    @Override
    protected Config prepareConfig(Config config) {
        config.controller().setOverwriteFileSetting(
                OverwriteFileSetting.deleteDirectoryIfExists);
        return config;
    }

    @Override
    protected void prepareScenario(Scenario scenario) {
        scenarioAdapter = CityScenarioAdapters.load(
                env("AGENTIC_CITY_ADAPTER", "sioux-falls"));
        scenarioAdapter.validate(scenario);
        int agentCount = IterativeLlmDecisionController.resolveAgentCount(
                scenario.getPopulation().getPersons().size());
        long sampleSeed = Long.parseLong(env("LLM_SAMPLE_SEED", "20260805"));
        for (Person person : IterativeLlmDecisionController.selectStratified(
                scenario.getPopulation().getPersons().values(), agentCount, sampleSeed)) {
            PopulationUtils.putSubpopulation(
                    person, IterativeLlmDecisionController.LLM_SUBPOPULATION);
        }
    }

    @Override
    protected void prepareControler(Controler controler) {
        if (scenarioAdapter == null) {
            scenarioAdapter = CityScenarioAdapters.load(
                    env("AGENTIC_CITY_ADAPTER", "sioux-falls"));
        }
        controler.addControllerListener(
                new IterativeLlmDecisionController(controler, scenarioAdapter));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
