package org.matsim.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;

/**
 * Command-line entry point for the agentic-mobility prototype. Loads the
 * (unchanged) MATSim scenario, validates the population, selects and profiles
 * the LLM travelers, samples information groups, and runs the experiment.
 * Artifacts are written under {@code <output>/agentic/}; {@code --validate-only}
 * writes the report directly to {@code <output>/} and runs neither the API nor
 * the mobility simulation.
 */
@Command(name = "agentic-mobility", mixinStandardHelpOptions = true, version = "0.2.0-v1",
        description = "LLM travelers + LLM congestion-pricing manager on a MATSim scenario.")
public final class AgenticMobilityApplication implements Callable<Integer> {

    @Option(names = "--config", required = true, paramLabel = "<file>",
            description = "Path to the MATSim config.xml (scenario inputs are not modified).")
    private String configPath;

    @Option(names = "--output", paramLabel = "<dir>",
            description = "Output directory (default: the config's outputDirectory).")
    private String outputDirectory;

    @Option(names = "--days", description = "Number of simulated days (iterations 0..days-1).")
    private int days = 100;

    @Option(names = "--llm-agents", description = "Number of LLM travelers.")
    private int llmAgents = 100;

    @Option(names = "--seed", description = "Random seed for LLM selection, profiles, and information groups.")
    private long seed = 42;

    @Option(names = "--run-date", description = "Run label date YYYY-MM-DD; default today in Asia/Shanghai")
    private String runDate;

    @Option(names = "--sample", paramLabel = "<fraction>", description = "Keep this fraction of the population, in (0, 1]. Default 1.0 (everyone). Capacity scales by --capacity-factor (defaults to this value).")
    private double sample = 1.0;

    @Option(names = "--capacity-factor", paramLabel = "<fraction>", description = "Scale network flow+storage capacity by this factor (>0). Defaults to --sample, keeping density at the scenario's native 200% sample. Use --sample 0.5 --capacity-factor 1.0 for a 100%-density scenario.")
    private Double capacityFactor;

    @Option(names = "--informed-share", description = "Informed share within LLM and background populations.")
    private double informedShare = 0.5;

    @Option(names = "--concurrency", description = "Concurrent LLM traveler decisions.")
    private int concurrency = 8;

    @Option(names = "--repair-attempts", description = "Max attempts per decision (including the first).")
    private int repairAttempts = 3;

    @Option(names = "--no-manager", description = "Disable the manager and all tolls (control group).")
    private boolean noManager;

    @Option(names = "--mock", description = "Use deterministic mock LLM outputs (integration test only).")
    private boolean mock;

    @Option(names = "--validate-only", description = "Validate population and write the report; no API, no simulation.")
    private boolean validateOnly;

    @Option(names = "--profiles", paramLabel = "<file>", description = "Path to a profile-weights JSON file.")
    private String profilesJson;

    public static void main(String[] args) {
        int exit = new CommandLine(new AgenticMobilityApplication()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() throws Exception {
        AgenticConfig config = new AgenticConfig();
        config.configPath = configPath;
        config.days = days;
        config.llmAgents = llmAgents;
        config.seed = seed;
        config.sample = sample;
        config.capacityFactor = capacityFactor;
        config.informedShare = informedShare;
        config.concurrency = concurrency;
        config.repairAttempts = repairAttempts;
        config.noManager = noManager;
        config.mock = mock;
        config.validateOnly = validateOnly;
        config.profilesJson = profilesJson;
        config.profileWeights = loadProfiles(profilesJson);
        if (config.timeoutSeconds <= 0 || config.llmStallSeconds <= 0) {
            throw new IllegalArgumentException("LLM timeout and stall seconds must be positive");
        }

        Config matsimConfig = ConfigUtils.loadConfig(config.configPath);
        if (outputDirectory != null) {
            matsimConfig.controller().setOutputDirectory(outputDirectory);
        } else {
            // No --output given: auto-generate a dated "bundle" directory under the
            // config's outputDirectory, e.g. output/2026-09-28_day500_llm50_noMgr_seed4711/.
            String bundle = uniqueBundle(matsimConfig.controller().getOutputDirectory(),
                    ExperimentRunName.format(ExperimentRunName.date(runDate), days, llmAgents, noManager, seed));
            matsimConfig.controller().setOutputDirectory(bundle);
            System.out.println("OUTPUT BUNDLE: " + bundle);
        }
        config.outputDirectory = matsimConfig.controller().getOutputDirectory();
        matsimConfig.controller().setLastIteration(Math.max(0, config.days - 1));

        Path outputDir = Path.of(config.outputDirectory);
        if (Files.isDirectory(outputDir)) {
            throw new LlmException("output directory already exists; choose a new one: " + outputDir);
        }
        // The on-disk config keeps failIfDirectoryExists; we relax it in memory because
        // we pre-create the agentic/ subfolder (our own check above enforces freshness).
        matsimConfig.controller().setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles);

        // Car routing needs a nonzero value of travel time: with time free in the
        // router, a toll cannot be traded against the time it saves and the pricing
        // channel has nothing to act on. Restore the standard value of time.
        applyCarValueOfTime(matsimConfig);

        Scenario scenario = ScenarioUtils.loadScenario(matsimConfig);
        Population population = scenario.getPopulation();

        // Optional population downsampling: keeps a deterministic (seeded) random
        // subset so smaller experiments are reproducible. The scenario input files
        // are left untouched; 1.0 (the default) is a no-op.
        double capF = (config.capacityFactor != null) ? config.capacityFactor : config.sample;
        if (config.sample < 1.0 || capF != 1.0) {
            downSample(population, matsimConfig, config.sample, capF, config.seed);
        }

        RouteCandidates routeCandidates = new RouteCandidates(scenario.getNetwork());
        ExpectedTravelTimes expectedTravelTimes = new ExpectedTravelTimes(scenario.getNetwork());
        CarScenario carScenario = new CarScenario(scenario, routeCandidates);
        CarScenario.Result validation = carScenario.validate(population);

        // Remove excluded persons so they take no part in the simulation.
        for (Map.Entry<String, Integer> e : validation.exclusionReasons.entrySet()) {
            System.out.println("EXCLUDED " + e.getKey() + ": " + e.getValue());
        }
        List<Id<Person>> excluded = new ArrayList<>();
        for (Id<Person> pid : population.getPersons().keySet()) {
            if (!validation.validated.containsKey(pid)) excluded.add(pid);
        }
        for (Id<Person> pid : excluded) {
            population.removePerson(pid);
        }

        Random rng = new Random(config.seed);

        // 1. LLM selection (deterministic given the seed).
        List<Id<Person>> eligible = new ArrayList<>(validation.llmEligible);
        Collections.shuffle(eligible, rng);
        int llmCount = Math.min(config.llmAgents, eligible.size());
        List<Id<Person>> llmSelected = new ArrayList<>(eligible.subList(0, llmCount));

        InformationModel infoModel = new InformationModel();
        for (Id<Person> pid : llmSelected) infoModel.addLlm(pid);

        // 2. Information groups (informed share, rounded, fixed for the experiment).
        List<Id<Person>> llmInformed = sampleInformed(llmSelected, config.informedShare, rng);
        for (Id<Person> pid : llmInformed) infoModel.addLlmInformed(pid);
        List<Id<Person>> bgIds = new ArrayList<>();
        for (Id<Person> pid : validation.validated.keySet()) {
            if (!infoModel.isLlm(pid)) bgIds.add(pid);
        }
        for (Id<Person> pid : sampleInformed(bgIds, config.informedShare, rng)) infoModel.addBgInformed(pid);

        // 3. Output writer.
        OutputWriter output = validateOnly
                ? null
                : new OutputWriter(outputDir.resolve("agentic"));

        // 4. Profiles + traveler agents.
        List<TravelerAgent> travelers = new ArrayList<>();
        for (Id<Person> pid : llmSelected) {
            Profile profile = Profile.sample(rng, config.profileWeights);
            CarScenario.PersonCheck check = validation.validated.get(pid);
            List<Id<Link>> links = check.activityLinkIds();
            Id<Link> home = links.get(0);
            Id<Link> work = links.get(1);
            travelers.add(new TravelerAgent(pid, profile, infoModel.isInformed(pid),
                    routeCandidates.compute(home, work), routeCandidates.compute(work, home),
                    check.outboundAnchor(), check.returnAnchor(), config.repairAttempts,
                    expectedTravelTimes));
        }

        writeManifest(config, matsimConfig, validation, llmCount, infoModel, bgIds.size(), outputDir);
        writeRoster(travelers, outputDir, config.validateOnly);

        if (config.validateOnly) {
            System.out.println("validation complete: raw=" + population.getPersons().size()
                    + " validated=" + validation.validated.size()
                    + " llm=" + llmCount
                    + " excluded=" + excluded.size());
            return 0;
        }

        // 5. LLM client + usage tracker + manager.
        LlmUsageTracker usage = new LlmUsageTracker(config.maxRequests);
        LlmClient client = config.mock ? new MockLlmClient() : new DeepSeekClient(config, usage);
        ManagerAgent manager = config.noManager ? null
                : new ManagerAgent(scenario.getNetwork(), client, output, config.repairAttempts);
        for (TravelerAgent agent : travelers) {
            agent.setClient(client, usage, output);
        }

        // 6. Freeze LLM travelers via the "llm" subpopulation; keep background strategies unchanged.
        for (Id<Person> pid : llmSelected) {
            PopulationUtils.putSubpopulation(population.getPersons().get(pid), "llm");
        }
        ReplanningConfigGroup.StrategySettings freeze = new ReplanningConfigGroup.StrategySettings();
        freeze.setStrategyName("KeepLastSelected");
        freeze.setSubpopulation("llm");
        freeze.setWeight(1.0);
        matsimConfig.replanning().addStrategySettings(freeze);

        // 7. Controler + wiring.
        Controler controler = new Controler(scenario);
        TollSystem tollSystem = new TollSystem(controler.getEvents(), output, infoModel);
        TollAwareTravelDisutilityFactory tollAwareFactory =
                new TollAwareTravelDisutilityFactory(matsimConfig, tollSystem);
        controler.addOverridingModule(new AgenticMobilityModule(tollAwareFactory));

        // Realized, time-dependent link travel times (recomputed each day) feed the
        // congestion-aware expected-travel-time table shown to the LLM travelers.
        TravelTimeCalculator travelTimeCalculator =
                new TravelTimeCalculator(scenario.getNetwork(), matsimConfig.travelTimeCalculator());
        controler.getEvents().addHandler(travelTimeCalculator);

        Metrics metrics = new Metrics(population, scenario.getNetwork(), infoModel);
        DailyLoop dailyLoop = new DailyLoop(config, scenario, output, usage, infoModel,
                tollSystem, metrics, travelers, manager, validation.validated.size(),
                travelTimeCalculator, expectedTravelTimes);

        controler.getEvents().addHandler(tollSystem);
        controler.getEvents().addHandler(metrics);
        controler.addControllerListener(dailyLoop);

        controler.run();
        return 0;
    }

    private static List<Id<Person>> sampleInformed(List<Id<Person>> ids, double share, Random rng) {
        List<Id<Person>> copy = new ArrayList<>(ids);
        Collections.shuffle(copy, rng);
        int n = (int) Math.round(share * ids.size());
        return new ArrayList<>(copy.subList(0, Math.min(n, copy.size())));
    }

    /**
     * Deterministically keeps {@code fraction} of the population and scales network
     * flow+storage capacity by {@code capacityFactor}. When {@code capacityFactor}
     * equals {@code fraction}, density (cars per unit of capacity) matches the
     * scenario's native 200% sample; set {@code capacityFactor} independently to
     * change density (e.g. fraction 0.5 + capacityFactor 1.0 = 100% density).
     * Uses a private seed so it does not touch MATSim's global RNG. At this point
     * all persons are still in the default subpopulation, so a flat sample is correct.
     */
    private static void downSample(Population population, Config matsimConfig, double fraction,
                                   double capacityFactor, long seed) {
        if (fraction <= 0.0 || fraction > 1.0) {
            throw new LlmException("--sample must be in (0, 1], got " + fraction);
        }
        if (capacityFactor <= 0.0) {
            throw new LlmException("--capacity-factor must be > 0, got " + capacityFactor);
        }
        List<Id<Person>> ids = new ArrayList<>(population.getPersons().keySet());
        Collections.shuffle(ids, new Random(seed));
        int keep = (int) Math.round(ids.size() * fraction);
        for (int i = keep; i < ids.size(); i++) {
            population.removePerson(ids.get(i));
        }
        matsimConfig.qsim().setFlowCapFactor(capacityFactor);
        matsimConfig.qsim().setStorageCapFactor(capacityFactor);
        System.out.println("DOWNSAMPLED population " + ids.size() + " -> " + keep
                + " (sample=" + fraction + ", seed=" + seed
                + ", flow/storage cap factor -> " + capacityFactor + ")");
    }

    /**
     * Gives car routing a nonzero value of travel time. The Sioux Falls scenario
     * config ships with car {@code marginalUtilityOfTraveling_util_hr = 0.0} (travel
     * time is free in the router), so background replanning sees only the distance
     * cost and cannot weigh a toll against the time it saves — the pricing channel
     * has nothing to act on. We restore the conventional value of time.
     *
     * <p>The value of -6 utils/h is the standard MATSim default for car, in line with
     * the value-of-travel-time literature: VOT is roughly half the wage rate
     * (Small 2012, "Valuation of Travel Time", Economics of Transportation
     * 1(1-2):2-14; Wardman 2004 meta-analysis) and rises with income less than
     * proportionally (Small & Verhoef, The Economics of Urban Transportation, ch. 2).
     * This config already uses -6 for bike/ride/other; car is the only mode left at 0.
     *
     * <p>Magnitude note: with {@code marginalUtilityOfMoney = 0.062}, -6 utils/h
     * equals about 97 money-units per hour, so a 100-unit toll ≈ 1 hour of travel
     * time — the scale at which the manager's congestion tolls can actually trade
     * against detours.
     */
    private static void applyCarValueOfTime(Config matsimConfig) {
        matsimConfig.scoring().getOrCreateModeParams("car").setMarginalUtilityOfTraveling(-6.0);
    }

    /** Returns parent/<name>, appending -2, -3, ... if that directory already exists. */
    private static String uniqueBundle(String parent, String name) {
        String candidate = parent + "/" + name;
        int i = 2;
        while (Files.isDirectory(Path.of(candidate))) {
            candidate = parent + "/" + name + "-" + i;
            i++;
        }
        return candidate;
    }

    private static Map<String, Map<String, Double>> loadProfiles(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            JsonNode root = Json.readTree(Files.readString(Path.of(path)));
            Map<String, Map<String, Double>> weights = new LinkedHashMap<>();
            for (String dim : List.of("income", "decisionStyle", "workTimeliness")) {
                JsonNode dimNode = root.get(dim);
                if (dimNode == null || !dimNode.isObject()) continue;
                Map<String, Double> dimWeights = new LinkedHashMap<>();
                dimNode.fields().forEachRemaining(e -> {
                    if (e.getValue().isNumber()) dimWeights.put(e.getKey(), e.getValue().asDouble());
                });
                weights.put(dim, dimWeights);
            }
            return weights;
        } catch (Exception e) {
            throw new LlmException("failed to load profiles file: " + path, e);
        }
    }

    private static void writeManifest(AgenticConfig config, Config matsimConfig, CarScenario.Result validation,
                                      int llmCount, InformationModel infoModel, int bgCount, Path outputDir) throws Exception {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("config_path", config.configPath);
        run.put("output_directory", config.outputDirectory);
        run.put("days", config.days);
        run.put("llm_agents", config.llmAgents);
        run.put("seed", config.seed);
        run.put("population_sample", config.sample);
        run.put("informed_share", config.informedShare);
        run.put("concurrency", config.concurrency);
        run.put("repair_attempts", config.repairAttempts);
        run.put("manager_prompt_version", ManagerAgent.PROMPT_VERSION);
        run.put("manager_max_rules", ManagerAgent.MAX_RULES);
        run.put("manager_max_memory_words", ManagerAgent.MAX_MEMORY_WORDS);
        run.put("no_manager", config.noManager);
        run.put("mock", config.mock);
        run.put("validate_only", config.validateOnly);
        run.put("model", config.model);
        run.put("base_url", config.baseUrl);
        run.put("thinking", config.thinking);
        run.put("timeout_seconds", config.timeoutSeconds);
        run.put("connect_timeout_seconds", DeepSeekClient.CONNECT_TIMEOUT_SECONDS);
        run.put("transport_attempts", DeepSeekClient.MAX_TRANSPORT_ATTEMPTS);
        run.put("batch_transport_retries", LlmDecisionBatch.MAX_TASK_RETRIES);
        run.put("llm_stall_seconds", config.llmStallSeconds);
        run.put("max_output_tokens", config.maxOutputTokens);
        run.put("max_requests", config.maxRequests);
        run.put("api_key_set", config.apiKey != null && !config.apiKey.isBlank());

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("network_file", matsimConfig.network().getInputFile());
        scenario.put("plans_file", matsimConfig.plans().getInputFile());
        scenario.put("facilities_file", matsimConfig.facilities().getInputFile());
        scenario.put("first_iteration", matsimConfig.controller().getFirstIteration());
        scenario.put("last_iteration", matsimConfig.controller().getLastIteration());
        Object endTime = matsimConfig.qsim().getEndTime().isDefined()
                ? matsimConfig.qsim().getEndTime().seconds()
                : "undefined";
        scenario.put("end_time", endTime);
        scenario.put("stuck_time", matsimConfig.qsim().getStuckTime());
        scenario.put("flow_capacity_factor", matsimConfig.qsim().getFlowCapFactor());
        scenario.put("storage_capacity_factor", matsimConfig.qsim().getStorageCapFactor());
        scenario.put("marginal_utility_of_money", matsimConfig.scoring().getMarginalUtilityOfMoney());
        var carScoring = matsimConfig.scoring().getModes().get("car");
        if (carScoring != null) {
            scenario.put("performing_utility_per_hour", matsimConfig.scoring().getPerforming_utils_hr());
            scenario.put("car_traveling_utility_per_hour", carScoring.getMarginalUtilityOfTraveling());
            scenario.put("car_monetary_distance_rate_per_meter", carScoring.getMonetaryDistanceRate());
            scenario.put("car_distance_utility_per_meter", carScoring.getMarginalUtilityOfDistance());
        }

        Map<String, Object> population = new LinkedHashMap<>();
        population.put("raw", validation.validated.size() + validation.exclusionReasons.values().stream()
                .mapToInt(Integer::intValue).sum());
        population.put("valid_car", validation.validated.size());
        population.put("excluded_by_reason", validation.exclusionReasons);
        population.put("llm_count", llmCount);
        population.put("llm_eligible", validation.llmEligible.size());
        population.put("llm_informed", infoModel.llmInformedCount());
        population.put("llm_uninformed", infoModel.llmCount() - infoModel.llmInformedCount());
        population.put("bg_count", bgCount);
        population.put("bg_informed", infoModel.bgInformedCount());
        population.put("bg_uninformed", bgCount - infoModel.bgInformedCount());

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("run", run);
        manifest.put("scenario", scenario);
        manifest.put("population", population);
        manifest.put("behavioral_results", !config.mock);

        Path target = config.validateOnly
                ? outputDir.resolve(OutputWriter.MANIFEST)
                : outputDir.resolve("agentic").resolve(OutputWriter.MANIFEST);
        Files.createDirectories(target.getParent());
        Files.writeString(target, Json.write(manifest));
    }

    private static void writeRoster(List<TravelerAgent> travelers, Path outputDir, boolean validateOnly) throws Exception {
        List<Map<String, Object>> roster = new ArrayList<>();
        for (TravelerAgent agent : travelers) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("person_id", agent.personId().toString());
            r.put("profile", agent.profile().toJson());
            r.put("informed", agent.informed());
            r.put("outbound_anchor", TravelerAgent.hms(agent.outboundAnchor()));
            r.put("return_anchor", TravelerAgent.hms(agent.returnAnchor()));
            r.put("outbound_candidates", agent.outboundCandidates().size());
            r.put("return_candidates", agent.returnCandidates().size());
            roster.add(r);
        }
        Path target = validateOnly
                ? outputDir.resolve(OutputWriter.ROSTER)
                : outputDir.resolve("agentic").resolve(OutputWriter.ROSTER);
        Files.createDirectories(target.getParent());
        Files.writeString(target, Json.write(roster));
    }
}
