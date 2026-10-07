package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class MorningExperimentOutputsTest {
    @TempDir Path directory;

    private MorningExperiment options(String... extra) {
        var result = new MorningExperiment();
        var args = new ArrayList<>(List.of("--run-date", "2026-10-06", "--demand", "100", "--sample-rate", "0.1"));
        args.addAll(List.of(extra));
        new CommandLine(result).parseArgs(args.toArray(String[]::new));
        return result;
    }

    private Config config() {
        var result = ConfigUtils.createConfig();
        result.global().setRandomSeed(268);
        result.controller().setFirstIteration(0);
        result.controller().setLastIteration(499);
        result.qsim().setFlowCapFactor(.1);
        result.qsim().setStorageCapFactor(.1);
        result.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setStrategyName("ReRoute").setWeight(.1).setDisableAfter(450));
        return result;
    }

    @Test void baselineNameRetainsOnlyDisplayedParameters() {
        assertEquals("2026-10-06_day500_llm0_noMgr_rr0.1_seed268",
                MorningExperimentOutputs.parameterName(options(), config(), 0));
        var twentyNine = options(); twentyNine.sampleRate = .29;
        assertEquals(MorningExperimentOutputs.parameterName(options(), config(), 0),
                MorningExperimentOutputs.parameterName(twentyNine, config(), 0));
    }

    @Test void managerRemainsVisibleWhileSamplingCapacityAndUpdateIntervalAreHidden() {
        var parameters = options(); var config = config();
        String noManager = MorningExperimentOutputs.parameterName(parameters, config, 0);
        parameters.managerEnabled = true;
        String combined = MorningExperimentOutputs.parameterName(parameters, config, 0);
        assertTrue(combined.contains("_mgr_"));
        assertEquals("2026-10-06_day500_llm0_mgr_rr0.1_seed268", combined);
        parameters.managerUpdateInterval = 7;
        assertEquals(combined, MorningExperimentOutputs.parameterName(parameters, config, 0));
        assertEquals(2, Set.of(noManager, combined).size());
        parameters.managerEnabled = false;
        parameters.sampleRate = null;
        String fullUnscaled = MorningExperimentOutputs.parameterName(parameters, config, 0);
        parameters.sampleRate = 1.0;
        config.qsim().setFlowCapFactor(1); config.qsim().setStorageCapFactor(1);
        String fullScaled = MorningExperimentOutputs.parameterName(parameters, config, 0);
        assertEquals(noManager, fullUnscaled);
        assertEquals(noManager, fullScaled);
        parameters.demand = 200;
        parameters.mode = "reference";
        config.replanning().getStrategySettings().iterator().next().setDisableAfter(2900);
        assertEquals(fullScaled, MorningExperimentOutputs.parameterName(parameters, config, 0));
    }

    @Test void allUsesActualControlledCountAndOfflinePolicyDoesNotClaimLlmCalls() {
        var llm = options("--policy", "llm", "--llm-agents", "all");
        String actual = MorningExperimentOutputs.parameterName(llm, config(), 50);
        assertTrue(actual.contains("_llm50_")); assertTrue(actual.contains("_mem-llm_"));
        assertTrue(llm.reflects());
        llm.llmConfig.model = "other-model";
        assertEquals(actual, MorningExperimentOutputs.parameterName(llm, config(), 50));
        String offline = MorningExperimentOutputs.parameterName(options("--llm-agents", "50"), config(), 50);
        assertTrue(offline.contains("_llm0_ctrl50_noMgr_")); assertTrue(offline.contains("_beta0.3_"));
    }

    @Test void validationRemainsVisibleWhileDiagnosticSamplingIsHidden() {
        var parameters = options();
        String simulation = MorningExperimentOutputs.parameterName(parameters, config(), 0);
        parameters.validateOnly = true;
        String validation = MorningExperimentOutputs.parameterName(parameters, config(), 0);
        assertNotEquals(simulation, validation); assertTrue(validation.endsWith("_validate_seed268"));
        parameters.sampleRate = null; parameters.samplePersons = 40;
        assertEquals(validation, MorningExperimentOutputs.parameterName(parameters, config(), 0));
    }

    @Test void fixedPricingIsDistinctFromUnpricedAndManagerNames() {
        String base = MorningExperimentOutputs.parameterName(options(), config(), 0);
        var fixed = options("--fixed-toll-policy", "fixed.json");
        String name = MorningExperimentOutputs.parameterName(fixed, config(), 0);
        assertEquals("2026-10-06_day500_llm0_noMgr_fixedToll_rr0.1_seed268", name);
        assertNotEquals(base, name);
        assertNotEquals(MorningExperimentOutputs.parameterName(options("--manager"), config(), 0), name);
        fixed.fixedTollFreeDays = 0;
        assertEquals(name, MorningExperimentOutputs.parameterName(fixed, config(), 0), "Full fixed-policy details remain in the manifest");
        fixed.informationScope = "all";
        assertTrue(MorningExperimentOutputs.parameterName(fixed, config(), 0).contains("info-all50pct"));
    }

    @Test void longAndUnsafeModelNamesDoNotEnterDisplayName() {
        var parameters = options("--policy", "llm", "--manager", "--validate-only");
        parameters.llmConfig.model = "vendor/" + "very-long-model-name-".repeat(10) + "a";
        String a = MorningExperimentOutputs.parameterName(parameters, config(), 5000);
        parameters.llmConfig.model = "vendor/" + "very-long-model-name-".repeat(10) + "b";
        String b = MorningExperimentOutputs.parameterName(parameters, config(), 5000);
        assertEquals(a, b); assertTrue(a.length() <= 160); assertTrue(b.length() <= 160);
        assertFalse(a.contains("model-")); assertFalse(a.contains("vendor"));
        assertTrue(a.startsWith("2026-10-06_")); assertTrue(a.endsWith("_validate_seed268"));
        assertTrue(a.matches("[A-Za-z0-9_.-]+"));
    }

    @Test void defaultRootIsProjectOutputEvenWithNestedConfig() throws Exception {
        Path root = directory.resolve("project"); Files.createDirectories(root.resolve("scenarios/public"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path outputRoot = MorningExperimentOutputs.projectOutputRoot(root.resolve("scenarios/public/config.xml"));
        assertEquals(root.resolve("output"), outputRoot);
        var reserved = MorningExperimentOutputs.reserve(outputRoot.resolve("2026-10-06_example"));
        assertEquals(outputRoot, reserved.path().getParent());
    }

    @Test void repeatsSkipExistingFilesAndDirectoriesWithoutTouchingTheirContents() throws Exception {
        Path base = directory.resolve("experiment"); Files.writeString(base, "existing file");
        Path second = directory.resolve("experiment-2"); Files.createDirectory(second);
        Files.writeString(second.resolve("result.txt"), "finished results");
        var third = MorningExperimentOutputs.reserve(base);
        assertEquals(directory.resolve("experiment-3"), third.path()); assertEquals(3, third.repetition());
        var fourth = MorningExperimentOutputs.reserve(base);
        assertEquals(4, fourth.repetition());
        assertEquals("existing file", Files.readString(base));
        assertEquals("finished results", Files.readString(second.resolve("result.txt")));
    }

    @Test void concurrentStartsReserveSeparateConsecutiveDirectories() throws Exception {
        Path base = directory.resolve("output/shared-name");
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<MorningExperimentOutputs.Reservation>> requests = IntStream.range(0, 24)
                    .mapToObj(i -> (Callable<MorningExperimentOutputs.Reservation>) () -> MorningExperimentOutputs.reserve(base)).toList();
            var names = new HashSet<Path>(); var numbers = new TreeSet<Integer>();
            for (var result : executor.invokeAll(requests)) {
                var reservation = result.get();
                assertTrue(Files.isDirectory(reservation.path()));
                assertTrue(names.add(reservation.path())); numbers.add(reservation.repetition());
            }
            assertEquals(new TreeSet<>(IntStream.rangeClosed(1, 24).boxed().toList()), numbers);
        }
    }
}
