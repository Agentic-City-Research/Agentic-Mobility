package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.*;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class MorningNativeTimeMutationTest {
    @TempDir Path directory;

    @Test void nativeChangesExecuteAndAreSavedWhileOverlayAndFixedTimeControlRemainValid() throws Exception {
        Config config = ConfigUtils.loadConfig(Path.of("scenarios/SiouxFalls-public/config.xml").toAbsolutePath().toString());
        Path source = Path.of("scenarios/SiouxFalls-public").toAbsolutePath();
        config.network().setInputFile(source.resolve(config.network().getInputFile()).normalize().toString());
        config.facilities().setInputFile(source.resolve(config.facilities().getInputFile()).normalize().toString());
        config.plans().setInputFile(source.resolve(config.plans().getInputFile()).normalize().toString());
        // Force innovation in a tiny real QSim run so this test does not depend on rare selections.
        for (var strategy : config.replanning().getStrategySettings()) {
            strategy.setWeight(switch (strategy.getStrategyName()) {
                case "TimeAllocationMutator" -> 100;
                case "ReRoute" -> .000001;
                default -> 0;
            });
        }
        config.timeAllocationMutator().setMutationRange(600);
        config.controller().setWritePlansInterval(1);
        Path cfg = directory.resolve("time-mutation.xml");
        new ConfigWriter(config).write(cfg.toString());
        for (String variant : List.of("native", "partial-overlay", "fixed-time")) {
            Path out = directory.resolve(variant);
            var settings = new MorningExperiment();
            settings.policyOverride = c -> new MorningRoutePolicy.Decision(c.candidates().getLast().routeId(), "Offline overlay");
            var args = new ArrayList<>(List.of("--config", cfg.toString(), "--output", out.toString(), "--policy", "mock",
                    "--llm-agents", variant.equals("partial-overlay") ? "5" : "0", "--demand", "100",
                    "--sample-persons", "40", "--days", "3", "--seed", "4711"));
            if (variant.equals("fixed-time")) args.add("--no-native-time-mutation");
            assertEquals(0, new CommandLine(settings).execute(args.toArray(String[]::new)));
            boolean enabled = !variant.equals("fixed-time");
            var manifest = Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
            assertEquals(enabled, manifest.get("nativeTimeMutation").get("enabled").asBoolean());
            assertEquals(!enabled, manifest.get("plannedTimesFixed").asBoolean());
            assertEquals(600, manifest.get("nativeTimeMutation").get("mutationRangeSeconds").asDouble());
            assertEquals(0, Json.readTree(Files.readString(out.resolve("morning/api-usage.json"))).get("requests").asInt());
            var metrics = readLines(out.resolve("morning/metrics.jsonl.gz")).stream().map(Json::readTree).toList();
            assertEquals(0, metrics.getFirst().get("allDepartureTimeSwitches").asInt());
            assertEquals(enabled, metrics.stream().anyMatch(m -> m.get("nativeDepartureTimeSwitches").asInt() > 0));
            assertEquals(enabled, metrics.stream().anyMatch(m -> m.get("nativeDeparturesShiftedFromOriginal").asInt() > 0));
            var trips = readLines(out.resolve("morning/trips.jsonl.gz")).stream().map(Json::readTree).toList();
            assertEquals(enabled, trips.stream().anyMatch(t -> !t.get("controlled").asBoolean()
                    && Math.abs(t.get("actualDepartureSeconds").asDouble() - t.get("plannedDepartureSeconds").asDouble()) > 1));
            for (int day = 0; day < 3; day++) {
                var saved = ScenarioUtils.createScenario(ConfigUtils.createConfig());
                new PopulationReader(saved).readFile(out.resolve("ITERS/it." + day + "/" + day + ".plans.xml.zst").toString());
                for (var t : trips) {
                    if (t.get("day").asInt() != day) continue;
                    String[] key = t.get("tripId").asText().split("/");
                    var person = saved.getPopulation().getPersons().get(org.matsim.api.core.v01.Id.createPersonId(key[0]));
                    var trip = TripStructureUtils.getTrips(person.getSelectedPlan()).get(Integer.parseInt(key[1]));
                    assertEquals(t.get("selectedDepartureSeconds").asDouble(), trip.getOriginActivity().getEndTime().seconds());
                    if (t.get("controlled").asBoolean() || !enabled)
                        assertEquals(t.get("plannedDepartureSeconds").asDouble(), t.get("selectedDepartureSeconds").asDouble());
                    assertTrue(t.get("completedOd").asBoolean());
                }
            }
        }
    }

    @Test void compactNameDistinguishesTimeMutationFromTheOldFixedTimeBaseline() {
        Config config = ConfigUtils.loadConfig(Path.of("scenarios/SiouxFalls-public/config.xml").toAbsolutePath().toString());
        var settings = new MorningExperiment(); new CommandLine(settings).parseArgs("--run-date", "2026-10-07");
        settings.configureReplanning(config);
        String enabled = MorningExperimentOutputs.parameterName(settings, config, 0);
        assertTrue(enabled.contains("_tm0.1_"));
        settings.nativeTimeMutation = false; settings.configureReplanning(config);
        String disabled = MorningExperimentOutputs.parameterName(settings, config, 0);
        assertFalse(disabled.contains("_tm")); assertNotEquals(enabled, disabled);
    }
}
