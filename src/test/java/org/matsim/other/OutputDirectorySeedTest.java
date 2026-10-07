package org.matsim.other;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputDirectorySeedTest {
    @TempDir Path directory;

    @Test void resolvesXmlTemplateUsingOverriddenSeedAndKeepsOverwriteProtection() {
        var config = ConfigUtils.createConfig();
        config.global().setRandomSeed(4711);
        config.controller().setOutputDirectory("output/siouxfalls-seed${randomSeed}");
        config.controller().setOverwriteFileSetting(OverwriteFileSetting.failIfDirectoryExists);
        var path = directory.resolve("config.xml");
        new ConfigWriter(config).write(path.toString());
        var loaded = ConfigUtils.loadConfig(new String[]{path.toString(),
                "--config:global.randomSeed", "42"});

        RunMatsimWithoutApplication.resolveOutputDirectory(loaded);

        assertEquals("output/siouxfalls-seed42", loaded.controller().getOutputDirectory());
        assertEquals(OverwriteFileSetting.failIfDirectoryExists,
                loaded.controller().getOverwriteFileSetting());
    }

    @Test void leavesLiteralDirectoriesUnchanged() {
        var config = ConfigUtils.createConfig();
        config.controller().setOutputDirectory("output/run01");
        RunMatsimWithoutApplication.resolveOutputDirectory(config);
        assertEquals("output/run01", config.controller().getOutputDirectory());
    }

    @Test void replacesEveryOccurrence() {
        var config = ConfigUtils.createConfig();
        config.global().setRandomSeed(4711);
        config.controller().setOutputDirectory("output/${randomSeed}/seed${randomSeed}");
        RunMatsimWithoutApplication.resolveOutputDirectory(config);
        assertEquals("output/4711/seed4711", config.controller().getOutputDirectory());
    }
}
