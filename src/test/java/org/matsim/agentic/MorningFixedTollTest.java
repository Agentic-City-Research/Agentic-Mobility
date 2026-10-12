package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.*;
import org.matsim.core.config.groups.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class MorningFixedTollTest {
    @TempDir Path directory;

    @Test void legalWindowsAmountsAndReusableExportAreValidatedAgainstTheNetwork() throws Exception {
        Path file = writePolicy("legal.json", "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":10,\"amount\":12345.678901},"
                + "{\"link_id\":\"road\",\"start\":10,\"end\":108000,\"amount\":0.000012345}]}");
        var loaded = MorningFixedTollPolicy.load(file, network(false), 108000, 120);
        assertEquals(120, loaded.policy().day());
        assertEquals(12345.678901, loaded.policy().amountAt(Id.createLinkId("road"), 0));
        assertEquals(.000012345, loaded.policy().amountAt(Id.createLinkId("road"), 10));
        assertEquals(0, loaded.policy().amountAt(Id.createLinkId("road"), 108000));
        assertEquals(file.toAbsolutePath().toString(), loaded.source().get("path"));
        assertEquals(64, loaded.source().get("sha256").toString().length());
        Path exported = writePolicy("exported.json", Json.write(loaded.inputJson()));
        assertEquals(loaded.policy().fingerprint(), MorningFixedTollPolicy.load(exported, network(false), 108000, 0).policy().fingerprint());
        Files.writeString(file, "{\"rules\":[]}");
        assertEquals(12345.678901, loaded.policy().amountAt(Id.createLinkId("road"), 0), "Changing the file must not mutate the already loaded policy");
        assertThrows(UnsupportedOperationException.class, () -> loaded.policy().rules().clear());
    }

    @Test void malformedAndIllegalPoliciesFailBeforeSimulation() throws Exception {
        var net = network(false);
        net.getLinks().get(Id.createLinkId("return")).setAllowedModes(Set.of("walk"));
        for (String invalid : List.of(
                "{\"rules\":[]}{\"rules\":[]}", "{\"rules\":[],\"rules\":[]}",
                "{\"rules\":[],\"memory\":\"ignored\"}", "{\"rules\":null}", "[]",
                "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":10,\"amount\":\"1\"}]}",
                "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":10,\"amount\":1,\"extra\":1}]}",
                "{\"rules\":[{\"link_id\":\"missing\",\"start\":0,\"end\":10,\"amount\":1}]}",
                "{\"rules\":[{\"link_id\":\"return\",\"start\":0,\"end\":10,\"amount\":1}]}",
                "{\"rules\":[{\"link_id\":\"road\",\"start\":-1,\"end\":10,\"amount\":1}]}",
                "{\"rules\":[{\"link_id\":\"road\",\"start\":10,\"end\":10,\"amount\":1}]}",
                "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":108001,\"amount\":1}]}",
                "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":10,\"amount\":-1}]}",
                "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":10,\"amount\":1e500}]}",
                "{\"rules\":[{\"link_id\":\"road\",\"start\":0,\"end\":10,\"amount\":1},{\"link_id\":\"road\",\"start\":9,\"end\":20,\"amount\":2}]}")) {
            Path file = writePolicy("invalid.json", invalid);
            assertThrows(IllegalArgumentException.class, () -> MorningFixedTollPolicy.load(file, net, 108000, 20), invalid);
        }
    }

    @Test void fixedAndManagerModesAreMutuallyExclusiveAndInvalidOptionsDoNotReserveOutput() {
        for (String[] bad : List.of(new String[]{"--manager", "--fixed-toll-policy", "missing.json"},
                new String[]{"--mode", "reference", "--fixed-toll-policy", "missing.json"},
                new String[]{"--fixed-toll-free-days", "-1"})) {
            Path out = directory.resolve("not-created");
            var args = new ArrayList<>(List.of("--config", "missing-config.xml", "--output", out.toString()));
            args.addAll(Arrays.asList(bad));
            assertNotEquals(0, new CommandLine(offline()).execute(args.toArray(String[]::new)));
            assertFalse(Files.exists(out));
        }
    }

    @Test void validationExportsTheExactPolicyAndRequiresNoApiKey() throws Exception {
        Path config = writeScenario(false), file = allDayPolicy(2.125), out = directory.resolve("validation");
        assertEquals(0, execute(offline(), config, out, "--fixed-toll-policy", file.toString(), "--days", "22", "--validate-only"));
        var manifest = manifest(out);
        assertFalse(manifest.get("managerEnabled").asBoolean());
        assertTrue(manifest.get("pricing").get("enabled").asBoolean());
        assertEquals("fixed", manifest.get("pricing").get("mode").asText());
        assertEquals(20, manifest.get("pricing").get("freeSimulationDays").asInt());
        assertEquals(21, manifest.get("pricing").get("firstChargedSimulationDay").asInt());
        assertTrue(manifest.get("pricing").get("updateIntervalDays").isNull());
        assertEquals(0, manifest.get("minimumManagerCalls").asInt());
        assertFalse(manifest.has("managerSettings")); assertFalse(manifest.has("managerContext"));
        assertEquals(manifest.get("inputHashes").get("fixedTollPolicy"), manifest.get("pricing").get("policySource"));
        assertEquals(MorningFixedTollPolicy.load(file, network(false), 108000, 20).policy().fingerprint(),
                MorningFixedTollPolicy.load(out.resolve("morning/fixed-toll-policy.json"), network(false), 108000, 20).policy().fingerprint());
        assertEquals(0, Json.readTree(Files.readString(out.resolve("morning/status.json"))).get("apiCalls").asInt());
    }

    @Test void nativeFixedPricingBillsAfterTwentyFreeDaysAndNeverCallsManager() throws Exception {
        Path config = writeScenario(false), file = allDayPolicy(2.125), out = directory.resolve("fixed-integration");
        assertEquals(0, execute(offline(), config, out, "--fixed-toll-policy", file.toString(), "--days", "22"));
        var metrics = readLines(out.resolve("morning/metrics.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(22, metrics.size());
        for (int day = 0; day < metrics.size(); day++) {
            var row = metrics.get(day);
            assertEquals(day < 20 ? 0 : 2.125, row.get("pricing").get("totalPaid").asDouble());
            assertEquals(day < 20 ? 0 : 1, row.get("policyVersion").asInt());
            assertEquals(0, row.get("apiCalls").asInt());
            assertEquals(1, row.get("completionRate").asDouble());
        }
        assertEquals(2, readLines(out.resolve("morning/toll-payments.jsonl.gz")).size());
        var policies = readLines(out.resolve("morning/policies.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(2, policies.size()); assertEquals(20, policies.getLast().get("policy").get("day").asInt());
        var applications = readLines(out.resolve("morning/policy-applications.jsonl.gz")).stream().map(Json::readTree).toList();
        for (int day = 0; day < applications.size(); day++) {
            assertFalse(applications.get(day).get("scheduledUpdate").asBoolean());
            assertEquals(day == 20, applications.get(day).get("fixedPolicyActivatedToday").asBoolean());
        }
        var experiences = readLines(out.resolve("morning/experiences.jsonl.gz")).stream().map(Json::readTree).toList();
        for (int day = 0; day < experiences.size(); day++) assertEquals(day < 20 ? 0 : 2.125, experiences.get(day).get("tollPaid").asDouble());
        assertNoManager(out);
        var status = Json.readTree(Files.readString(out.resolve("morning/status.json")));
        assertTrue(status.get("complete").asBoolean()); assertEquals(22, status.get("daysCompleted").asInt());
    }

    @Test void zeroWarmupPricingIsVisibleToInitialNativeRouting() throws Exception {
        Path config = writeScenario(true), file = allDayPolicy(1000), base = directory.resolve("base"), out = directory.resolve("first-day-fixed");
        assertEquals(0, execute(offline(), config, base, "--days", "1"));
        assertEquals(0, execute(offline(), config, out, "--fixed-toll-policy", file.toString(), "--fixed-toll-free-days", "0", "--days", "1"));
        var baseline = Json.readTree(readLines(base.resolve("morning/experiences.jsonl.gz")).getFirst());
        var priced = Json.readTree(readLines(out.resolve("morning/experiences.jsonl.gz")).getFirst());
        assertTrue(baseline.get("routeFingerprint").asText().contains("road"));
        assertTrue(priced.get("routeFingerprint").asText().contains("alternate"), "The initial router must see the fixed price, not just the QSim billing handler");
        var policies = readLines(out.resolve("morning/policies.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(1, policies.size()); assertEquals(1, policies.getFirst().get("policyVersion").asInt());
        assertEquals(0, policies.getFirst().get("policy").get("day").asInt());
        assertNoManager(out);
        assertEquals("none", manifest(base).get("pricing").get("mode").asText());
        assertFalse(manifest(base).get("pricing").get("enabled").asBoolean());
    }

    @Test void fixedFreeDaysCountFromNonzeroFirstIterationAndLongWarmupCanRemainFree() throws Exception {
        Path config = writeScenario(false), file = allDayPolicy(2), out = directory.resolve("nonzero-first");
        var cfg = ConfigUtils.loadConfig(config.toString()); cfg.controller().setFirstIteration(100); cfg.controller().setLastIteration(103);
        new ConfigWriter(cfg).write(config.toString());
        assertEquals(0, execute(offline(), config, out, "--fixed-toll-policy", file.toString(), "--fixed-toll-free-days", "2"));
        var metrics = readLines(out.resolve("morning/metrics.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(4, metrics.size());
        for (int i = 0; i < metrics.size(); i++) {
            assertEquals(100 + i, metrics.get(i).get("day").asInt());
            assertEquals(i < 2 ? 0 : 2, metrics.get(i).get("pricing").get("totalPaid").asDouble());
        }
        assertEquals(102, manifest(out).get("pricing").get("effectiveFromIteration").asInt());
        Path shortOut = directory.resolve("not-activated");
        assertEquals(0, execute(offline(), config, shortOut, "--fixed-toll-policy", file.toString(), "--fixed-toll-free-days", "20", "--days", "2"));
        assertEquals(1, readLines(shortOut.resolve("morning/policies.jsonl.gz")).size());
        for (String row : readLines(shortOut.resolve("morning/metrics.jsonl.gz"))) assertEquals(0, Json.readTree(row).get("pricing").get("totalPaid").asDouble());
        assertNoManager(shortOut);
    }

    private MorningExperiment offline() {
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = null;
        settings.clientFactory = (config, usage) -> { throw new AssertionError("Fixed/no-toll native experiment must never create an LLM client"); };
        return settings;
    }
    private int execute(MorningExperiment settings, Path config, Path out, String... extra) {
        var args = new ArrayList<>(List.of("--config", config.toString(), "--output", out.toString(), "--mode", "hybrid",
                "--policy", "mnl", "--llm-agents", "0", "--seed", "4711", "--demand", "100"));
        args.addAll(Arrays.asList(extra));
        return new CommandLine(settings).execute(args.toArray(String[]::new));
    }
    private void assertNoManager(Path out) throws Exception {
        for (String name : List.of("manager-attempts", "manager-contexts", "manager-decisions", "manager-policy-evaluations"))
            assertFalse(Files.exists(out.resolve("morning/" + name + ".jsonl.gz")));
        var usage = Json.readTree(Files.readString(out.resolve("morning/api-usage.json")));
        assertEquals(0, usage.get("attemptedManagerCalls").asInt()); assertEquals(0, usage.get("attemptedTotalCalls").asInt());
    }
    private com.fasterxml.jackson.databind.JsonNode manifest(Path out) throws Exception {
        return Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
    }
    private Path allDayPolicy(double amount) throws Exception {
        return writePolicy("fixed.json", Json.write(Map.of("rules", List.of(Map.of("link_id", "road", "start", 0, "end", 108000, "amount", amount)))));
    }
    private Path writePolicy(String name, String json) throws Exception {
        Path file = directory.resolve(name); Files.writeString(file, json); return file;
    }
    private Path writeScenario(boolean alternate) throws java.io.IOException {
        var config = ConfigUtils.createConfig(); config.qsim().setEndTime(108000); config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1); config.routing().setRoutingRandomness(0);
        config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.none);
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("home").setTypicalDuration(12 * 3600));
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("work").setTypicalDuration(8 * 3600));
        config.replanning().setMaxAgentPlanMemorySize(6);
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(.1));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(.9));
        config.controller().setWriteEventsInterval(0); config.controller().setWritePlansInterval(0);
        Path net = directory.resolve("network.xml"), pop = directory.resolve("population.xml"), cfg = directory.resolve("config.xml"), facilities = directory.resolve("facilities.xml");
        config.network().setInputFile(net.toString()); config.plans().setInputFile(pop.toString()); config.facilities().setInputFile(facilities.toString());
        var scenario = ScenarioUtils.createScenario(config); var source = network(alternate);
        source.getNodes().values().forEach(scenario.getNetwork()::addNode); source.getLinks().values().forEach(scenario.getNetwork()::addLink);
        var f = scenario.getPopulation().getFactory(); var p = f.createPerson(Id.createPersonId("traveler")); var plan = f.createPlan();
        var home = f.createActivityFromLinkId("home", Id.createLinkId("home")); home.setEndTime(25200); plan.addActivity(home);
        plan.addLeg(f.createLeg("car")); var work = f.createActivityFromLinkId("work", Id.createLinkId("work"));
        work.setStartTime(25300); plan.addActivity(work);
        p.addPlan(plan); scenario.getPopulation().addPerson(p);
        new NetworkWriter(scenario.getNetwork()).write(net.toString()); new PopulationWriter(scenario.getPopulation()).write(pop.toString());
        new org.matsim.facilities.FacilitiesWriter(scenario.getActivityFacilities()).write(facilities.toString());
        new ConfigWriter(config).write(cfg.toString());
        for (Path file : List.of(net,pop,cfg,facilities)) MorningJointTestSupport.useBundledDtd(file);
        return cfg;
    }
    private static Network network(boolean alternate) {
        var n = NetworkUtils.createNetwork(); var f = n.getFactory();
        for (int i = 0; i < 4; i++) n.addNode(f.createNode(Id.createNodeId(i), new Coord(i * 100, 0)));
        String[] names = {"home", "road", "work", "return"};
        for (int i = 0; i < names.length; i++) addLink(n, names[i], i, (i + 1) % 4, i == 3 ? 300 : 100);
        if (alternate) addLink(n, "alternate", 1, 2, 150);
        return n;
    }
    private static void addLink(Network n, String name, int from, int to, double length) {
        var l = n.getFactory().createLink(Id.createLinkId(name), n.getNodes().get(Id.createNodeId(from)), n.getNodes().get(Id.createNodeId(to)));
        l.setLength(length); l.setFreespeed(10); l.setCapacity(1000); l.setNumberOfLanes(1); l.setAllowedModes(Set.of("car")); n.addLink(l);
    }
}
