package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.FacilitiesWriter;
import org.matsim.core.config.ConfigWriter;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ManifestSourceTest {
    @TempDir Path directory;

    private record ScenarioFiles(Path config, Path population) {}

    private ScenarioFiles writeScenario() {
        Config config = ConfigUtils.createConfig();
        Path net = directory.resolve("network.xml"), pop = directory.resolve("population.xml");
        Path facilities = directory.resolve("facilities.xml"), cfg = directory.resolve("config.xml");
        config.network().setInputFile(net.toString());
        config.plans().setInputFile(pop.toString());
        config.facilities().setInputFile(facilities.toString());
        config.qsim().setEndTime(108000);
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1);
        config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.none);
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("home").setTypicalDuration(13 * 3600));
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("work").setTypicalDuration(9 * 3600));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(.1));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(.9));
        config.controller().setWriteEventsInterval(0);
        config.controller().setWritePlansInterval(0);
        config.controller().setCreateGraphsInterval(0);

        Scenario scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork();
        var factory = network.getFactory();
        var n0 = factory.createNode(Id.createNodeId("n0"), new Coord(0, 0));
        var n1 = factory.createNode(Id.createNodeId("n1"), new Coord(1000, 0));
        network.addNode(n0);
        network.addNode(n1);
        var out = factory.createLink(Id.createLinkId("out"), n0, n1);
        var back = factory.createLink(Id.createLinkId("back"), n1, n0);
        for (var link : java.util.List.of(out, back)) {
            link.setLength(1000);
            link.setFreespeed(10);
            link.setCapacity(3600);
            link.setNumberOfLanes(1);
            link.setAllowedModes(Set.of("car"));
            network.addLink(link);
        }

        var population = scenario.getPopulation();
        var personFactory = population.getFactory();
        var person = personFactory.createPerson(Id.createPersonId("p"));
        var plan = personFactory.createPlan();
        var home = personFactory.createActivityFromLinkId("home", out.getId());
        home.setEndTime(25200);
        plan.addActivity(home);
        plan.addLeg(personFactory.createLeg("car"));
        var work = personFactory.createActivityFromLinkId("work", back.getId());
        work.setStartTime(28800);
        work.setEndTime(61200);
        plan.addActivity(work);
        plan.addLeg(personFactory.createLeg("car"));
        plan.addActivity(personFactory.createActivityFromLinkId("home", out.getId()));
        person.addPlan(plan);
        population.addPerson(person);

        new NetworkWriter(network).write(net.toString());
        new PopulationWriter(population).write(pop.toString());
        new FacilitiesWriter(scenario.getActivityFacilities()).write(facilities.toString());
        new ConfigWriter(config).write(cfg.toString());
        return new ScenarioFiles(cfg, pop);
    }

    private int runQuietly(Path config, Path population, Path output, String... extra) {
        var commandLine = new CommandLine(new MorningExperiment());
        commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> 2);
        return execute(commandLine, config, population, output, extra);
    }

    private int run(Path config, Path population, Path output, String... extra) {
        return execute(new CommandLine(new MorningExperiment()), config, population, output, extra);
    }

    private int execute(CommandLine commandLine, Path config, Path population, Path output, String... extra) {
        var args = new java.util.ArrayList<>(java.util.List.of(
                "--config", config.toString(),
                "--mode", "hybrid", "--policy", "shortest", "--llm-agents", "0",
                "--days", "1", "--seed", "4711", "--output", output.toString(), "--validate-only"));
        if (population != null) {
            args.add("--population");
            args.add(population.toString());
        }
        args.addAll(java.util.List.of(extra));
        return commandLine.execute(args.toArray(String[]::new));
    }

    private com.fasterxml.jackson.databind.JsonNode manifest(Path output) throws Exception {
        return Json.readTree(Files.readString(output.resolve("morning/manifest.json")));
    }

    @Test void defaultRunKeepsLegacySourceAndSchemaVersion() throws Exception {
        var files = writeScenario();
        Path output = directory.resolve("default");
        assertEquals(0, run(files.config(), null, output));
        var manifest = manifest(output);
        assertEquals(23, manifest.get("schemaVersion").asInt());
        assertEquals("Local enriched Sioux Falls files, public car-only doubled-demand lineage DOI 10.17632/2332kg48c8.3; local hashes identify inputs, not a claim of byte-for-byte download verification",
                manifest.get("source").asText());
        assertFalse(manifest.has("sourceBasis"));
        assertFalse(manifest.has("sourceWarning"));
    }

    @Test void explicitSourceReplacesSourceAndAddsBasis() throws Exception {
        var files = writeScenario();
        Path output = directory.resolve("explicit");
        assertEquals(0, run(files.config(), files.population(), output,
                "--scenario-source", "TinySynthetic: synthetic and uncalibrated"));
        var manifest = manifest(output);
        assertEquals(23, manifest.get("schemaVersion").asInt());
        assertEquals("TinySynthetic: synthetic and uncalibrated", manifest.get("source").asText());
        assertEquals("explicit-scenario-declaration", manifest.get("sourceBasis").asText());
        assertFalse(manifest.has("sourceWarning"));
    }

    @Test void blankSourceIsRejected() {
        var files = writeScenario();
        Path output = directory.resolve("blank");
        assertNotEquals(0, runQuietly(files.config(), null, output, "--scenario-source", "   "));
        assertFalse(Files.exists(output));
    }

    @Test void populationWithoutSourceWarnsButKeepsLegacySource() throws Exception {
        var files = writeScenario();
        Path output = directory.resolve("warning");
        var err = new ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            assertEquals(0, run(files.config(), files.population(), output));
        } finally {
            System.setErr(original);
        }
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--scenario-source"),
                "Population override without a source must warn on the console");
        var manifest = manifest(output);
        assertEquals(23, manifest.get("schemaVersion").asInt());
        assertEquals("Local enriched Sioux Falls files, public car-only doubled-demand lineage DOI 10.17632/2332kg48c8.3; local hashes identify inputs, not a claim of byte-for-byte download verification",
                manifest.get("source").asText());
        assertTrue(manifest.get("sourceWarning").asText().contains("--scenario-source"));
        assertFalse(manifest.has("sourceBasis"));
    }
}
