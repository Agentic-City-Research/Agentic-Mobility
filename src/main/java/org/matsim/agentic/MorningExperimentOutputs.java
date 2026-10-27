package org.matsim.agentic;

import org.matsim.core.config.Config;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;

/** Compact display names; full parameters remain in the manifest and collisions receive a sequence number. */
final class MorningExperimentOutputs {
    private MorningExperimentOutputs() {}

    record Reservation(Path path, String baseName, int repetition) {}

    static String parameterName(MorningExperiment options, Config config, int controlledPersons) {
        int llmPersons = options.policyName.equals("llm") ? controlledPersons : 0;
        var parts = new ArrayList<String>();
        parts.add(ExperimentRunName.date(options.runDate).toString());
        parts.add("day" + (config.controller().getLastIteration() - config.controller().getFirstIteration() + 1));
        parts.add("llm" + llmPersons);
        if (controlledPersons > 0 && llmPersons == 0) parts.add("ctrl" + controlledPersons);
        parts.add(options.managerEnabled ? "mgr" : "noMgr");
        if (options.fixedTollsEnabled()) parts.add("fixedToll");
        for (var strategy : config.replanning().getStrategySettings()) {
            if (strategy.getStrategyName().equals("ReRoute")) {
                parts.add("rr" + decimal(strategy.getWeight()));
            }
            if (strategy.getStrategyName().equals("TimeAllocationMutator") && strategy.getWeight() > 0) {
                parts.add("tm" + decimal(strategy.getWeight()));
            }
        }
        if (controlledPersons > 0 && options.policyName.equals("mnl")) parts.add("beta" + decimal(options.beta));
        if (controlledPersons > 0 && options.jointMnl()) parts.add("mnl-joint");
        if (llmPersons > 0) parts.add("mem-llm");
        if ((options.pricingEnabled() || llmPersons > 0) && (controlledPersons > 0 || options.informationScope.equals("all")))
            parts.add("info-" + options.informationScope + percent(options.informedShare) + "pct");
        String body = String.join("_", parts);
        String tail = (options.validateOnly ? "_validate" : "") + "_seed" + config.global().getRandomSeed();
        // Keep deep MATSim filenames usable on Windows. The hash preserves the
        // distinction between long names; the date and seed remain readable.
        if (body.length() + tail.length() > 160)
            body = body.substring(0, 160 - tail.length() - 11) + "_h" + hash(body).substring(0, 9);
        return body + tail;
    }

    static Path projectOutputRoot(Path configFile) {
        for (Path parent = configFile.toAbsolutePath().normalize().getParent(); parent != null; parent = parent.getParent())
            if (Files.isRegularFile(parent.resolve("pom.xml"))) return parent.resolve("output");
        return Path.of("output").toAbsolutePath().normalize();
    }

    static Reservation reserve(Path requested) throws IOException {
        Path base = requested.toAbsolutePath().normalize();
        if (base.getFileName() == null || base.getParent() == null)
            throw new IllegalArgumentException("Output must name an experiment directory: " + requested);
        Files.createDirectories(base.getParent());
        for (int repetition = 1; repetition > 0; repetition++) {
            Path candidate = repetition == 1 ? base : base.resolveSibling(base.getFileName() + "-" + repetition);
            try {
                // MATSim accepts an empty reserved directory with failIfDirectoryExists.
                // Atomic creation also separates experiments launched concurrently.
                Files.createDirectory(candidate);
                return new Reservation(candidate, base.getFileName().toString(), repetition);
            } catch (FileAlreadyExistsException occupied) {
                // Preserve existing experiments, including empty directories and files.
            }
        }
        throw new IOException("No available repetition number for " + base);
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String percent(double value) {
        return BigDecimal.valueOf(value).movePointRight(2).stripTrailingZeros().toPlainString();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
