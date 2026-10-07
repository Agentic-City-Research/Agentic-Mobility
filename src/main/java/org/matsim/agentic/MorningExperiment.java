package org.matsim.agentic;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.*;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;

/** Public-scenario runner; native full-day replanning with an optional morning policy overlay. */
@Command(name = "morning-experiment", mixinStandardHelpOptions = true,
        description = "Native full-day MATSim with optional morning LLM choices; reference and frozen-route controls.")
public final class MorningExperiment implements Callable<Integer> {
    @Option(names = "--config", defaultValue = "scenarios/SiouxFalls-public/config.xml") Path configFile;
    @Option(names = "--mode", defaultValue = "hybrid", description = "hybrid (main experiment) | reference | fixed-routes (auxiliary control)") String mode;
    @Option(names = "--policy", defaultValue = "mnl", description = "Morning overlay: llm | mnl | shortest | mock; llm requires hybrid") String policyName;
    @Option(names = "--llm-agents", defaultValue = "0", description = "Hybrid: 0 for native baseline, all or a person count for morning policy control") String llmAgents;
    @Option(names = "--manager", description = "Enable autonomous congestion pricing in hybrid after 20 free days") boolean managerEnabled;
    @Option(names = "--fixed-toll-policy", description = "JSON rules file for fixed congestion pricing in hybrid, without a manager; mutually exclusive with --manager") Path fixedTollPolicyFile;
    @Option(names = "--fixed-toll-free-days", defaultValue = "20", description = "Initial unpriced simulation days before fixed tolls activate; 0 prices from the first day") int fixedTollFreeDays = 20;
    MorningFixedTollPolicy fixedTollPolicy;
    @Option(names = "--manager-update-interval", defaultValue = "30", description = "Simulation days between manager updates; policies persist until the next accepted update") int managerUpdateInterval = MorningManager.UPDATE_INTERVAL;
    @Option(names = "--manager-max-output-tokens", defaultValue = "8192", description = "Manager policy/management-memory response limit") int managerMaxOutputTokens = 8192;
    @Mixin MorningManagerControls managerControls = new MorningManagerControls();
    @Option(names = "--informed-share", defaultValue = "0.5", description = "Fixed informed share among information-scope persons, 0..1") double informedShare = .5;
    @Option(names = "--information-scope", defaultValue = "controlled", description = "controlled | all; others retain native public-policy routing") String informationScope = "controlled";
    @Option(names = "--information-seed", description = "Independent stable information-group seed; defaults to population seed") Long informationSeed;
    @Option(names = "--toll-history-bin-seconds", defaultValue = "3600", description = "Match personal paid link observations in the same time-of-day bin") int tollHistoryBinSeconds = 3600;
    final AgenticConfig llmConfig = new AgenticConfig();
    @Mixin MorningModelOptions modelOptions = new MorningModelOptions();
    @Option(names = "--concurrency", defaultValue = "8") int concurrency;
    @Option(names = "--repair-attempts", defaultValue = "3") int repairAttempts;
    @Option(names = "--timeout-seconds") int timeoutSeconds = llmConfig.timeoutSeconds;
    @Option(names = "--llm-stall-seconds") int stallSeconds = llmConfig.llmStallSeconds;
    @Option(names = "--max-requests") int maxRequests = llmConfig.maxRequests;
    MorningRoutePolicy policyOverride; // Package-level injection for deterministic integration tests, not a CLI option.
    BiFunction<AgenticConfig, LlmUsageTracker, LlmClient> clientFactory = DeepSeekClient::new;
    @Option(names = "--seed") Long seed;
    @Option(names = {"--population-seed", "--sample-seed"}, defaultValue = "4711", description = "Fixed population sampling and default roster/profile/information seed; independent of --seed")
    long populationSeed = 4711;
    @Option(names = "--run-date", description = "Run label date YYYY-MM-DD; default today in Asia/Shanghai") String runDate;
    private String runName;
    private String parameterRunName;
    private MorningExperimentOutputs.Reservation outputReservation;
    @Option(names = "--days", description = "Number of simulated days; iteration 0 is included") Integer days;
    @Option(names = "--output", description = "Explicit directory override; default project output/<date and parameters>; existing names get -2, -3, ...") Path outputDirectory;
    @Option(names = "--demand", defaultValue = "200", description = "200: published doubled demand; 100: restored original persons") int demand;
    @Option(names = "--population", description = "Explicit population file override") Path populationFile;
    @Option(names = "--cohort-selection", defaultValue = "planned-arrival", description = "planned-arrival (default) | planned-departure; fixed membership from original input plans") String cohortSelection = "planned-arrival";
    @Option(names = "--planned-arrival-before", defaultValue = "32400", description = "Exclusive original destination activity start cutoff, seconds (09:00); no departure-window restriction") double plannedArrivalBefore = 32400;
    @Option(names = "--departure-before-seconds", defaultValue = "5400", description = "Earlier departure allowance relative to original input departure (90 minutes)") int departureBeforeSeconds = 5400;
    @Option(names = "--departure-after-seconds", defaultValue = "1800", description = "Later departure allowance relative to original input departure (30 minutes)") int departureAfterSeconds = 1800;
    @Option(names = "--departure-step-seconds", defaultValue = "900", description = "Departure option spacing (15 minutes), anchored to original departure") int departureStepSeconds = 900;
    @Option(names = "--peak-start", defaultValue = "25200", description = "Original planned cohort departure start, seconds (07:00)") double peakStart;
    @Option(names = "--peak-end", defaultValue = "32400", description = "Exclusive cohort departure end, seconds (09:00)") double peakEnd;
    @Option(names = "--analysis-start", defaultValue = "23400", description = "Network observation start, seconds (06:30)") double analysisStart;
    @Option(names = "--analysis-end", defaultValue = "39600", description = "Network observation end, seconds (11:00)") double analysisEnd;
    @Option(names = {"--history-days", "--history-window"}, defaultValue = "5", description = "Recent pre-choice experiences; reflection always uses up to 5 including today; full history retained") int historyDays;
    @Option(names = "--memory-mode", defaultValue = "llm", description = "Only llm is supported: post-QSim self-written summary") String memoryMode = "llm";
    @Option(names = "--memory-max-chars", defaultValue = "500", description = "Hard summary limit, 1..500 Unicode characters; prompt target 240") int memoryMaxChars = MorningMemoryUpdater.MAX_CHARACTERS;
    @Option(names = "--memory-max-output-tokens", defaultValue = "1024", description = "Reflection response limit; choice uses --max-output-tokens") int memoryMaxOutputTokens = 1024;
    @Option(names = "--personal-profiles", defaultValue = "legacy", description = "legacy: fixed synthetic legacy traits; input: input attributes only") String personalProfiles = "legacy";
    @Option(names = "--profile-seed", description = "Independent profile seed; defaults to population seed") Long profileSeed;
    @Option(names = "--profile-file", description = "Reuse legacy roster.json or profiles/cohort JSONL[.gz]; all controlled people must be present") Path profileFile;
    @Option(names = "--candidate-order", defaultValue = "original", description = "original | reverse | shuffle; display only") String candidateOrder = "original";
    @Option(names = "--candidate-order-seed", defaultValue = "0") long candidateOrderSeed;
    @Option(names = "--candidate-search-limit", defaultValue = "10000", description = "Maximum BFS-LE subnetwork states per OD") int candidateSearchLimit;
    @Option(names = "--candidate-pool-size", defaultValue = "16", description = "Distinct BFS-LE paths to collect before selecting up to four; >=4") int candidatePoolSize;
    @Option(names = "--candidate-max-time-ratio", defaultValue = "2.5", description = "Free-flow time/c0 limit; a route also passes if its extra time is within the slack; Infinity disables") double candidateMaxTimeRatio;
    @Option(names = "--candidate-time-slack-seconds", defaultValue = "300", description = "Allow this extra free-flow time even when the ratio is exceeded; 0 makes the ratio strict") double candidateTimeSlackSeconds;
    @Option(names = "--candidate-max-distance-ratio", defaultValue = "2.5", description = "Distance/c0 limit; a route also passes if its extra distance is within the slack; Infinity disables") double candidateMaxDistanceRatio;
    @Option(names = "--candidate-distance-slack-meters", defaultValue = "2000", description = "Allow this extra distance even when the ratio is exceeded; 0 makes the ratio strict") double candidateDistanceSlackMeters;
    @Option(names = "--candidate-max-overlap", defaultValue = "0.85", description = "Max shared directed-link length / shorter route length between any two selected routes; 1 disables") double candidateMaxOverlap;
    @Option(names = "--candidate-file", description = "Reuse candidates.jsonl.gz or legacy candidates.jsonl; missing ODs fail rather than regenerate") Path candidateFile;
    @Option(names = "--logit-beta", defaultValue = "0.3", description = "Sensitivity per minute, research baseline parameter") double beta;
    @Option(names = "--context-samples", defaultValue = "20", description = "Number of LLM-ready contexts logged per day; 0 disables") int contextSamples;
    @Option(names = "--trip-output-interval", defaultValue = "1") int tripOutputInterval;
    @Option(names = "--innovation-stop-day", description = "Native replanning modes: override innovative strategy disable day") Integer innovationStopDay;
    @Option(names = "--native-time-mutation", negatable = true, defaultValue = "true", fallbackValue = "true",
            description = "Hybrid: retain TimeAllocationMutator (default); --no-native-time-mutation restores original activity ends")
    boolean nativeTimeMutation = true;
    @Option(names = "--sample-persons", defaultValue = "0", description = "Diagnostic ONLY: seeded subsample without capacity scaling; 0 means all") int samplePersons;
    @Option(names = "--sample-rate", description = "Fixed population sample in (0,1]; set QSim flow/storage capacity factors to the same rate, relative to original network capacities") Double sampleRate;
    @Option(names = "--validate-only", description = "Validate inputs and export cohort/candidates, no QSim") boolean validateOnly;

    boolean fixed() { return mode.equals("fixed-routes"); }
    boolean hybrid() { return mode.equals("hybrid"); }
    boolean overlaysChoices() { return fixed() || hybrid(); }
    boolean fixesTimes() { return fixed() || hybrid() && !nativeTimeMutation; }
    boolean reflects() { return policyName.equals("llm"); }
    boolean fixedTollsEnabled() { return fixedTollPolicyFile != null; }
    boolean pricingEnabled() { return managerEnabled || fixedTollsEnabled(); }
    String pricingMode() { return managerEnabled ? "manager" : fixedTollsEnabled() ? "fixed" : "none"; }
    public static void main(String[] args) { System.exit(new CommandLine(new MorningExperiment()).execute(args)); }

    @Override public Integer call() throws Exception {
        validateOptions();
        Config config = ConfigUtils.loadConfig(configFile.toAbsolutePath().toString());
        if (seed != null) config.global().setRandomSeed(seed);
        long actualSeed = config.global().getRandomSeed();
        if (days != null) { config.controller().setFirstIteration(0); config.controller().setLastIteration(days - 1); }
        runDate = ExperimentRunName.date(runDate).toString();
        if (innovationStopDay != null) for (var strategy : config.replanning().getStrategySettings())
            if (strategy.getStrategyName().equals("ReRoute") || strategy.getStrategyName().equals("TimeAllocationMutator"))
                strategy.setDisableAfter(innovationStopDay);
        if (populationFile != null) config.plans().setInputFile(populationFile.toAbsolutePath().toString());
        else if (demand == 100) config.plans().setInputFile(config.plans().getInputFile().replace("200pct", "100pct"));
        // Use native absolute paths so the existing source folder's spaces do not
        // become unescaped file: URLs inside MATSim's scenario readers.
        config.network().setInputFile(resolveInput(config.network().getInputFile()).toString());
        config.plans().setInputFile(resolveInput(config.plans().getInputFile()).toString());
        config.facilities().setInputFile(resolveInput(config.facilities().getInputFile()).toString());
        configureReplanning(config);
        // Event-time accumulation must be ordered; this does not reduce QSim routing threads.
        config.eventsManager().setNumberOfThreads(1);
        if (!config.qsim().getEndTime().isDefined() || config.qsim().getEndTime().seconds() < analysisEnd)
            throw new IllegalArgumentException("QSim endTime must cover the analysis window");
        Scenario scenario = ScenarioUtils.loadScenario(config);
        if (fixedTollsEnabled()) {
            final int activation;
            try { activation = Math.addExact(config.controller().getFirstIteration(), fixedTollFreeDays); }
            catch (ArithmeticException e) { throw new IllegalArgumentException("Fixed toll activation iteration overflows", e); }
            fixedTollPolicy = MorningFixedTollPolicy.load(fixedTollPolicyFile, scenario.getNetwork(), config.qsim().getEndTime().seconds(), activation);
        }
        int fullPopulation = applySampling(scenario, actualSeed);
        if (fixed()) for (var person : scenario.getPopulation().getPersons().values()) {
            var selected = person.getSelectedPlan();
            person.getPlans().removeIf(plan -> plan != selected);
        }
        MatsimRandom.reset(actualSeed);
        System.out.println(candidateFile == null ? "Preparing at most four fixed routes per morning OD using BFS-LE + MATSim Dijkstra ..."
                : "Validating and reusing fixed per-OD candidate pools from " + candidateFile);
        var generator = new MatsimOdCandidates(scenario.getNetwork(), config, candidateSettings(), candidateFile);
        MorningCohort cohort = new MorningCohort(scenario, peakStart, peakEnd, historyDays, generator,
                cohortSelection, plannedArrivalBefore);
        if (cohort.members.isEmpty()) throw new IllegalArgumentException("No car trips match " + cohortSelection
                + " in [" + cohort.windowStart + "," + cohort.windowEnd + "); planned-arrival requires an original destination activity start time");
        int controlled = fixed() || hybrid() && llmAgents.equalsIgnoreCase("all") ? cohort.persons.size()
                : hybrid() ? Integer.parseInt(llmAgents) : 0;
        cohort.selectControlledPeople(controlled, populationSeed);
        cohort.configureDepartureOptions(this, config.qsim().getEndTime().seconds());
        var informationPeople = informationScope.equals("all")
                ? scenario.getPopulation().getPersons().keySet().stream().map(Object::toString).toList()
                : cohort.controlledPersons;
        cohort.configureInformation(informationPeople, informedShare,
                informationSeed == null ? populationSeed : informationSeed, tollHistoryBinSeconds);
        cohort.information.beginDay(config.controller().getFirstIteration());
        cohort.assignProfiles(personalProfiles, profileSeed == null ? populationSeed : profileSeed,
                profileFile == null ? null : MorningProfiles.read(profileFile));
        long minimumCalls = policyName.equals("llm") ? cohort.members.values().stream().filter(m -> m.controlled).count()
                * (config.controller().getLastIteration() - config.controller().getFirstIteration() + 1L) * 2L : 0;
        minimumCalls += managerEnabled ? MorningManager.minimumCalls(config.controller().getFirstIteration(), config.controller().getLastIteration(), managerUpdateInterval) : 0;
        if (!validateOnly && minimumCalls > 0) {
            if (llmConfig.apiKey == null || llmConfig.apiKey.isBlank()) throw new IllegalArgumentException("Set AGENTIC_API_KEY for live LLM traveler/manager calls; --manager also needs an API key when its update days are reached");
            if (maxRequests > 0 && minimumCalls > maxRequests) throw new IllegalArgumentException("Minimum LLM calls " + minimumCalls
                    + " exceed --max-requests " + maxRequests + "; explicitly choose a sufficient request budget");
        }
        llmConfig.concurrency = concurrency; llmConfig.repairAttempts = repairAttempts;
        llmConfig.timeoutSeconds = timeoutSeconds; llmConfig.llmStallSeconds = stallSeconds; llmConfig.maxRequests = maxRequests;
        parameterRunName = MorningExperimentOutputs.parameterName(this, config, controlled);
        outputReservation = MorningExperimentOutputs.reserve(outputDirectory != null ? outputDirectory
                : MorningExperimentOutputs.projectOutputRoot(configFile).resolve(parameterRunName));
        Path outputPath = outputReservation.path();
        runName = outputPath.getFileName().toString();
        config.controller().setOutputDirectory(outputPath.toString());
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists);
        Map<String, Object> manifest = manifest(config, scenario, cohort, fullPopulation);
        System.out.println(Json.write(Map.of("candidatePreparation", generator.summary())));
        System.out.println(Json.write(Map.of("mode", mode, "persons", scenario.getPopulation().getPersons().size(),
                "morningPersons", cohort.persons.size(), "morningTrips", cohort.members.size(), "controlledPersons", controlled,
                "minimumLlmCalls", minimumCalls, "apiCalls", 0, "output", outputPath.toString())));
        if (validateOnly) {
            try (var writer = new OutputWriter(outputPath.resolve("morning"), true)) {
                MorningLoop.writePreparation(writer, cohort, manifest);
                if (fixedTollsEnabled()) writer.writeDoc("fixed-toll-policy.json", fixedTollPolicy.inputJson());
                if (managerEnabled) {
                    var environment=MorningManagerEnvironment.from(scenario,this,manifest);
                    var publicEnvironment=new LinkedHashMap<>(environment.context(config.controller().getFirstIteration(),TollPolicy.empty(-1),managerControls.basic()));
                    publicEnvironment.put("pricingControls",managerControls.context(environment));writer.writeDoc("manager-public-environment.json",publicEnvironment);
                }
                new ConfigWriter(config).write(outputPath.resolve("effective-config.xml").toString());
                writer.status(Map.of("validated", true, "simulationRun", false, "apiCalls", 0));
            }
            return 0;
        }
        MorningObservations observations = new MorningObservations(scenario.getNetwork(), cohort, analysisStart, analysisEnd,
                managerEnabled && managerControls.basic() ? MorningManagerControls.BASIC_BIN_SECONDS : LinkTrafficMetrics.BIN_SECONDS);
        TravelTimeCalculator times = new TravelTimeCalculator(scenario.getNetwork(), config.travelTimeCalculator());
        Controler controller = new Controler(scenario);
        controller.getEvents().addHandler(observations);
        controller.getEvents().addHandler(times);
        MorningTollSystem tolls = pricingEnabled() ? new MorningTollSystem(scenario.getNetwork(), controller.getEvents(), observations::recordToll) : null;
        // PrepareForSim routes the first iteration before IterationStartsListener.
        // With no warmup, the fixed price must already be visible to that router.
        if (fixedTollsEnabled() && fixedTollFreeDays == 0) tolls.setPolicy(fixedTollPolicy.policy(), 1);
        if (tolls != null) tolls.setInformation(cohort.information);
        if (tolls != null) {
            controller.getEvents().addHandler(tolls);
            var factory = new MorningTollDisutilityFactory(config, tolls, cohort.information);
            controller.addOverridingModule(new AbstractModule() {
                @Override public void install() { bindCarTravelDisutilityFactory().toInstance(factory); }
            });
        }
        var loop = new MorningLoop(scenario, this, cohort, policyOverride != null ? policyOverride
                : policyName.equals("llm") ? null : MorningRoutePolicy.baseline(policyName, actualSeed, beta), observations, times, manifest, tolls);
        controller.addControlerListener(loop);
        try { controller.run(); }
        finally { loop.close(); OutputDirectoryLogging.closeOutputDirLogging(); }
        return 0;
    }

    int applySampling(Scenario scenario, long actualSeed) {
        int fullPopulation = scenario.getPopulation().getPersons().size();
        int selected = samplePersons > 0 ? Math.min(samplePersons, fullPopulation) : fullPopulation;
        if (sampleRate != null) {
            selected = BigDecimal.valueOf(fullPopulation).multiply(BigDecimal.valueOf(sampleRate)).intValue();
            if (selected < 1) throw new IllegalArgumentException("--sample-rate selects zero persons; choose a larger rate");
            // Absolute factors of the original network, not a multiplier of an
            // already sampled configuration (0.1 must not become 0.01).
            scenario.getConfig().qsim().setFlowCapFactor(sampleRate);
            scenario.getConfig().qsim().setStorageCapFactor(sampleRate);
        }
        if (selected < fullPopulation) {
            var ids = new ArrayList<>(scenario.getPopulation().getPersons().keySet());
            ids.sort(Comparator.comparing(Object::toString)); Collections.shuffle(ids, new Random(populationSeed));
            for (var id : ids.subList(selected, ids.size())) scenario.getPopulation().removePerson(id);
        }
        System.out.println(Json.write(Map.of("loadedPopulation", fullPopulation, "simulatedPopulation", selected,
                "populationSeed", populationSeed,
                "flowCapacityFactor", scenario.getConfig().qsim().getFlowCapFactor(),
                "storageCapacityFactor", scenario.getConfig().qsim().getStorageCapFactor())));
        return fullPopulation;
    }

    private void validateOptions() {
        modelOptions.apply(llmConfig);
        if (!Set.of("original", "reverse", "shuffle").contains(candidateOrder)) throw new IllegalArgumentException("Invalid --candidate-order");
        if (!mode.equals("reference") && !fixed() && !hybrid()) throw new IllegalArgumentException("--mode must be hybrid, reference or fixed-routes");
        if (!Set.of("llm", "mnl", "shortest", "mock").contains(policyName)) throw new IllegalArgumentException("Unknown --policy");
        if (!llmAgents.equalsIgnoreCase("all") && !llmAgents.matches("[0-9]+")) throw new IllegalArgumentException("--llm-agents must be 0, all or a nonnegative person count");
        if (!llmAgents.equalsIgnoreCase("all")) Integer.parseInt(llmAgents);
        if (!hybrid() && (!llmAgents.equals("0") || policyName.equals("llm"))) throw new IllegalArgumentException("--llm-agents and --policy llm require --mode hybrid");
        if (managerEnabled && !hybrid()) throw new IllegalArgumentException("--manager requires --mode hybrid");
        if (managerEnabled && fixedTollsEnabled()) throw new IllegalArgumentException("--manager and --fixed-toll-policy are mutually exclusive");
        if (fixedTollsEnabled() && !hybrid()) throw new IllegalArgumentException("--fixed-toll-policy requires --mode hybrid");
        if (fixedTollFreeDays < 0) throw new IllegalArgumentException("--fixed-toll-free-days must be nonnegative");
        if (managerMaxOutputTokens < 1) throw new IllegalArgumentException("Invalid manager output limit");
        if (managerUpdateInterval < 1) throw new IllegalArgumentException("--manager-update-interval must be positive");
        managerControls.validate();
        if (departureBeforeSeconds < 0 || departureAfterSeconds < 0 || departureStepSeconds < 1
                || departureBeforeSeconds % departureStepSeconds != 0 || departureAfterSeconds % departureStepSeconds != 0
                || (long)departureBeforeSeconds + departureAfterSeconds > 86400)
            throw new IllegalArgumentException("Invalid departure range/spacing: bounds must be step multiples within one day");
        if (!Set.of("controlled", "all").contains(informationScope) || !Double.isFinite(informedShare)
                || informedShare < 0 || informedShare > 1 || tollHistoryBinSeconds < 1)
            throw new IllegalArgumentException("Invalid traveler information scope/share/history-bin settings");
        if (concurrency < 1 || repairAttempts < 1 || timeoutSeconds < 1 || stallSeconds < 1 || maxRequests < 0)
            throw new IllegalArgumentException("Invalid LLM concurrency, repair, timeout or request budget");
        if (!"llm".equals(memoryMode)) throw new IllegalArgumentException("--memory-mode only supports llm");
        if (memoryMaxChars < 1 || memoryMaxOutputTokens < 1)
            throw new IllegalArgumentException("Invalid self-written memory settings");
        if (memoryMaxChars > MorningMemoryUpdater.MAX_CHARACTERS)
            throw new IllegalArgumentException("--memory-max-chars must not exceed " + MorningMemoryUpdater.MAX_CHARACTERS + " Unicode characters");
        if (!Set.of("legacy", "input").contains(personalProfiles) || profileFile != null && !personalProfiles.equals("legacy"))
            throw new IllegalArgumentException("Profile file requires --personal-profiles legacy");
        if (demand != 100 && demand != 200) throw new IllegalArgumentException("--demand must be 100 or 200");
        if (sampleRate != null && (!Double.isFinite(sampleRate) || sampleRate <= 0 || sampleRate > 1))
            throw new IllegalArgumentException("--sample-rate must be finite and in (0,1]");
        if (sampleRate != null && samplePersons != 0)
            throw new IllegalArgumentException("Choose either --sample-rate or --sample-persons, not both");
        if (!(peakStart >= 0 && peakEnd > peakStart && analysisStart >= 0 && analysisEnd > analysisStart
                && Double.isFinite(peakEnd) && Double.isFinite(analysisEnd))) throw new IllegalArgumentException("Invalid time windows");
        if (!Set.of("planned-arrival", "planned-departure").contains(cohortSelection)
                || !Double.isFinite(plannedArrivalBefore) || plannedArrivalBefore <= 0)
            throw new IllegalArgumentException("Invalid cohort selection or planned arrival cutoff");
        if (days != null && days < 1 || samplePersons < 0 || contextSamples < 0 || tripOutputInterval < 1
                || historyDays < 1 || !(beta >= 0 && Double.isFinite(beta)))
            throw new IllegalArgumentException("Invalid experiment parameter");
        if (fixed() && innovationStopDay != null) throw new IllegalArgumentException("No innovation strategy exists in fixed-routes mode");
        candidateSettings();
    }

    void configureReplanning(Config config) {
        if (fixed()) {
            config.replanning().clearStrategySettings();
            config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                    .setStrategyName("KeepLastSelected").setWeight(1));
            config.replanning().setMaxAgentPlanMemorySize(1);
        } else if (hybrid()) {
            var nativeStrategies = config.replanning().getStrategySettings().stream()
                    .filter(s -> nativeTimeMutation || !s.getStrategyName().contains("TimeAllocationMutator")).toList();
            if (nativeStrategies.stream().anyMatch(s -> !Set.of("ReRoute", "ChangeExpBeta", "TimeAllocationMutator").contains(s.getStrategyName())
                    || s.getSubpopulation() != null))
                throw new IllegalArgumentException("Hybrid supports default-population ReRoute, ChangeExpBeta and TimeAllocationMutator only");
            if (nativeStrategies.stream().noneMatch(s -> s.getStrategyName().equals("ReRoute") && s.getWeight() > 0)
                    || nativeStrategies.stream().noneMatch(s -> s.getStrategyName().equals("ChangeExpBeta")))
                throw new IllegalArgumentException("Hybrid requires native ReRoute and ChangeExpBeta strategies");
            if (config.replanning().getMaxAgentPlanMemorySize() == 1)
                throw new IllegalArgumentException("Hybrid needs multiple native plans, not maxAgentPlanMemorySize=1");
            config.replanning().clearStrategySettings();
            nativeStrategies.forEach(config.replanning()::addStrategySettings);
            if (nativeTimeMutation && nativeStrategies.stream().noneMatch(s -> s.getStrategyName().equals("TimeAllocationMutator"))) {
                config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                        .setStrategyName("TimeAllocationMutator").setWeight(.1)
                        .setDisableAfter(innovationStopDay == null ? 2900 : innovationStopDay));
            }
        }
    }

    private MatsimOdCandidates.Settings candidateSettings() {
        return new MatsimOdCandidates.Settings(candidateSearchLimit, candidatePoolSize, candidateMaxTimeRatio,
                candidateTimeSlackSeconds, candidateMaxDistanceRatio, candidateDistanceSlackMeters, candidateMaxOverlap);
    }

    private Map<String, Object> manifest(Config config, Scenario scenario, MorningCohort cohort, int fullPopulation) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 17); result.put("matsimVersion", "2026.0"); result.put("mode", mode);
        result.put("requestedDemandPercent", demand); result.put("requestedChoicePolicy", policyName);
        result.put("jsonlOutput", Map.of("compression", "gzip", "suffix", ".jsonl.gz", "encoding", "UTF-8",
                "streaming", true, "rowContentChanged", true));
        result.put("tripOutputInterval", tripOutputInterval); result.put("contextSamplesPerDay", contextSamples);
        result.put("runName", runName); result.put("runDateLabel", runDate);
        result.put("outputNaming", Map.of("version", 5, "automatic", outputDirectory == null,
                "parameterBaseName", parameterRunName, "baseName", outputReservation.baseName(),
                "repetition", outputReservation.repetition(), "directory", outputReservation.path().toString()));
        result.put("llmPersons", policyName.equals("llm") ? cohort.controlledPersons.size() : 0); result.put("managerEnabled", managerEnabled);
        var pricing = new LinkedHashMap<String, Object>();
        pricing.put("enabled", pricingEnabled()); pricing.put("mode", pricingMode());
        pricing.put("freeSimulationDays", fixedTollsEnabled() ? fixedTollFreeDays : managerEnabled ? MorningManager.FREE_DAYS : 0);
        pricing.put("updateIntervalDays", managerEnabled ? managerUpdateInterval : null);
        Long firstChargedSimulationDay = null;
        if (fixedTollsEnabled()) firstChargedSimulationDay = (long) fixedTollFreeDays + 1;
        else if (managerEnabled) firstChargedSimulationDay = 21L;
        pricing.put("firstChargedSimulationDay", firstChargedSimulationDay);
        pricing.put("schedule", fixedTollsEnabled() ? "After " + fixedTollFreeDays + " free days the file-loaded policy repeats each day unchanged; no manager calls or updates"
                : managerEnabled ? "first possible model update at simulation day 21, then every " + managerUpdateInterval + " days; " + (managerControls.basic() ? "no automatic withdrawal or strategy intervention" : "legacy daily withdrawal and cooldown enabled") : "No congestion tolls");
        pricing.put("billing", "Each car-link entry including origin partial link; repeat traversals billed again, duplicate entry notifications ignored; [start,end)");
        pricing.put("units", "scenario monetary units");
        if (managerEnabled) {
            pricing.put("actionSpace", managerControls.basic() ? "Existing car links; at most 40 non-overlapping same-link rules, continuous finite nonnegative amounts, 900-second-aligned windows within simulation horizon; no price/corridor/step/avoidability limits" : "Legacy controlled trials with declared price/corridor/step/avoidability limits");
            pricing.put("managerPrivateInformation", "No traveler memory, individual payments or personal profiles; public model parameters, aggregate reference-plan OD diagnostics, all-car completion counts and aggregate link traffic");
        }
        if (fixedTollsEnabled()) {
            pricing.put("policySource", fixedTollPolicy.source()); pricing.put("ruleCount", fixedTollPolicy.policy().rules().size());
            pricing.put("policyFingerprint", fixedTollPolicy.policy().fingerprint());
            pricing.put("effectiveFromIteration", fixedTollPolicy.policy().day());
            pricing.put("policyOutput", "morning/fixed-toll-policy.json");
            pricing.put("policyLifecycle", "Load and validate once before simulation; changing the source file during a run has no effect");
        }
        result.put("pricing", pricing);
        if (managerEnabled) result.put("managerSettings", llmConfig.withOutputLimit(managerMaxOutputTokens).publicModelSettings());
        if (managerEnabled) {
            result.put("managerContext", Map.of("templateVersion", MorningManager.VERSION,
                    "environmentVersion", MorningManagerEnvironment.VERSION, "feedbackVersion", MorningManagerFeedback.VERSION,
                    "feedbackMode", MorningManager.FEEDBACK_MODE, "matchedNoPricingCounterfactualAvailable", false,
                    "informationComponents", managerControls.basic() ? List.of("publicEnvironment", "roadCorridors", "currentTraffic", "executionSchedule", "policyFeedback", "trackedChargingWindows", "pricingControls", "managerMemory", "previousDecision") : List.of("publicEnvironment", "roadCorridors", "chargingSuitability", "currentTraffic", "routeAlternatives", "executionSchedule", "policyFeedback", "pricingControls", "safetyMonitoring", "managerMemory", "previousDecision"),
                    "environmentOutput", "morning/manager-public-environment.json",
                    "evaluationOutput", "morning/manager-policy-evaluations.jsonl.gz",
                    "decisionOutput", "morning/manager-decisions.jsonl.gz",
                    "historySemantics", "Combined public observations: latest three version periods and all equivalent-price episodes; every version is archived in evaluations. Structured decisions are interpretations, not measurements."));
            result.put("managerOutputContract", Map.of("requiredFields", List.of("rules", "decision", "memory"),
                    "memoryLimitUnit", managerControls.basic() ? "English words" : "Unicode characters", "memoryMaximum", managerControls.basic() ? MorningManager.MEMORY_MAX_WORDS : MorningManager.MEMORY_MAX_CHARS,
                    "maximumRules", MorningManagerControls.MAX_RULES,
                    "memoryRepair", "Validated rules and decision are locked; shorten memory only within repair-attempt/request budget. If repair fails retain old memory and accept validated policy with explicit audit.",
                    "alternativesVersion", MorningManagerAlternatives.VERSION));
        }
        result.put("minimumManagerCalls", managerEnabled ? MorningManager.minimumCalls(config.controller().getFirstIteration(), config.controller().getLastIteration(), managerUpdateInterval) : 0);
        if(managerEnabled) result.put("managerRequestBudgetInterpretation","Reserve capacity for all scheduled model updates and repairs. Basic has no safety cooldown or automatic policy changes.");
        result.put("controlledPersons", cohort.controlledPersons.size());
        result.put("travelerInformation", Map.of("scope", informationScope, "requestedInformedShare", informedShare,
                "seed", informationSeed == null ? populationSeed : informationSeed,
                "persons", cohort.information.assignments().size(),
                "informedPersons", cohort.information.assignments().values().stream().filter(Boolean::booleanValue).count(),
                "uninformedPersons", cohort.information.assignments().values().stream().filter(v -> !v).count(),
                "assignment", "Fixed seeded person-level split; rounded informed count, all trips of one person share visibility",
                "networkTrafficVisible", false, "tollHistoryBinSeconds", tollHistoryBinSeconds,
                "historicalToll", "LLM: recent factual experiences plus bounded 500-character summary only; no separate persistent exact-payment retrieval. Native assigned routing retains own link/bin observations. Unknown current tolls remain null, never assumed free"));
        result.put("nativeOnlyPersons", scenario.getPopulation().getPersons().size() - cohort.controlledPersons.size());
        result.put("nativeOtherTripsForControlledPersons", hybrid());
        result.put("controlledTrips", cohort.members.values().stream().filter(m -> m.controlled).count());
        result.put("choicePolicy", cohort.controlledPersons.isEmpty() ? "native MATSim strategies" : policyName);
        result.put("controlSelection", "Fixed seeded subset of original schedule-selected cohort persons; only their qualifying cohort trips controlled; other trips retain native routing");
        result.put("executionOrder", overlaysChoices()
                ? "Iteration starts: scheduled manager update or one-time fixed toll activation -> native replanning -> "
                + (fixesTimes() ? "restore original activity ends" : "retain native activity-time choices")
                + " and apply controlled joint time/route choices -> native plans dump -> QSim and payments -> factual results and policy-linked observations -> personal memory reflection. Automatic strategy intervention only in explicit controlled manager profile."
                : "Native selection/replanning -> native plans dump -> observation preparation -> QSim; no route overlay");
        result.put("seed", config.global().getRandomSeed());
        result.put("firstIteration", config.controller().getFirstIteration()); result.put("lastIteration", config.controller().getLastIteration());
        result.put("loadedPopulation", fullPopulation); result.put("simulatedPopulation", scenario.getPopulation().getPersons().size());
        if(managerEnabled) result.put("managerControls",managerControls.context(MorningManagerEnvironment.from(scenario,this,result)));
        result.put("sampleRate", sampleRate);
        result.put("populationSeed", populationSeed);
        result.put("actualPopulationSampleRate", (double) scenario.getPopulation().getPersons().size() / fullPopulation);
        result.put("flowCapacityFactor", config.qsim().getFlowCapFactor());
        result.put("storageCapacityFactor", config.qsim().getStorageCapFactor());
        result.put("populationSampling", Map.of("mode", sampleRate != null ? "population-and-capacity" : samplePersons > 0 ? "diagnostic-person-count" : "none",
                "seed", populationSeed,
                "selection", "Sort person IDs, shuffle with Java Random(populationSeed), retain a fixed subset and all its plans",
                "rounding", "floor(loadedPopulation * sampleRate)",
                "capacityBasis", sampleRate != null ? "Absolute QSim factors of original network capacities; override configured factors" : "Keep configured QSim factors",
                "capacityScaling", sampleRate != null ? Map.of("scheme", "linear", "flowFormula", "r", "storageFormula", "r", "storageExponent", 1.0) : Map.of("scheme", "configured")));
        result.put("diagnosticSubsample", samplePersons > 0); result.put("cohortTrips", cohort.members.size()); result.put("cohortPersons", cohort.persons.size());
        result.put("cohortWindow", List.of(cohort.windowStart, cohort.windowEnd)); result.put("networkWindow", List.of(analysisStart, analysisEnd));
        result.put("cohortSelection", cohort.selection);
        result.put("cohortDefinition", cohort.selection.equals("planned-arrival")
                ? "Original input destination activity start time in [0,cutoff), all purposes, one car leg per trip; no departure-window restriction; membership fixed before simulation; missing/invalid planned arrivals excluded, never estimated"
                : "Original planned activity-end time in [start,end), all purposes, one car leg per trip; membership held fixed");
        result.put("cohortSelectionDiagnostics", Map.of("excludedMissingPlannedArrivalTrips", cohort.excludedMissingPlannedArrivalTrips,
                "excludedInvalidPlannedArrivalTrips", cohort.excludedInvalidPlannedArrivalTrips));
        result.put("departurePeakWindow", List.of(peakStart, peakEnd));
        result.put("candidateGenerator", cohort.generator.usesImportedPools() ? "Reused candidate file; topology, free-flow time, shortest c0 and requested quality limits validated"
                : "Basic deterministic BFS-LE initial pool; native MATSim Dijkstra free-flow paths; deterministic cost-ordered detour/overlap selection retaining shortest c0 and up to four routes; frozen per OD");
        result.put("candidateMethodReference", Map.of("title", "Route choice sets for very high-resolution data",
                "authors", "Rieser-Schussler, Balmer and Axhausen", "doi", "10.1080/18128602.2012.671383",
                "variant", "Basic BFS-LE with deterministic link-ID expansion plus project-specific cost/diversity selection; not optimized PO/pass-node reduction, LP or K-shortest"));
        result.put("candidateSelectionVersion", cohort.generator.usesImportedPools() ? "frozen-file-validation-v1" : "cost-diversity-v1");
        result.put("candidateSettings", cohort.generator.settingsReport());
        result.put("candidatePreparation", cohort.generator.summary());
        result.put("candidateRoutingAlgorithm", "MATSim Dijkstra with link traversal exclusions on a separate car graph");
        result.put("candidateRoutingCost", "Static free-flow seconds; no scoring disutility or routingRandomness; excludes origin link, includes destination link");
        result.put("candidateQualityDefinition", "Ratios to shortest-time c0 (distance is not shortest-distance baseline); pairwise overlap = shared directed-link length / min(route distances), excluding origin; zero denominators reported as null for unbounded ratios; loopless interior between origin.to and destination.from");
        result.put("maxCandidatesPerOd", MatsimOdCandidates.MAX_CANDIDATES);
        result.put("candidatePoolLifecycle", "Generated or imported once per OD before day zero; immutable routes/identifiers throughout a run; native ReRoute does not expand the LLM pool");
        result.put("dayToDayDesignReference", Map.of("doi", "10.1016/j.trc.2025.105307",
                "alignment", "Predefined per-OD route alternatives, daily choice and post-simulation experience update",
                "differences", "MATSim QSim rather than link performance functions; at most four BFS-LE alternatives rather than five Yen paths; self-written memory without EWMA"));
        result.put("routingAlgorithm", config.controller().getRoutingAlgorithmType().toString());
        result.put("routingRandomness", config.routing().getRoutingRandomness()); // Native replanning only, not BFS-LE.
        if (candidateFile != null) result.put("candidateFileHash", inputHash(candidateFile.toAbsolutePath()));
        result.put("forecastModel", "Identical static free-flow route time/distance for both information groups on every day; no network historical/live traffic supplied; personal actual experiences remain visible");
        result.put("personalMemory", "Complete chronological facts and cumulative counts retained for analysis only; LLM prompts use self-written memory and recent experiences, not cumulative route counts; no smoothing or habit weight");
        result.put("memoryMode", memoryMode); result.put("selfWrittenMemoryEnabled", reflects() && !cohort.controlledPersons.isEmpty());
        result.put("memoryMaxChars", memoryMaxChars); result.put("memoryTemplate", MorningMemoryUpdater.VERSION);
        result.put("memoryTargetChars", MorningMemoryUpdater.TARGET_CHARACTERS);
        result.put("memoryScope", "Private per person and cohort trip; no cross-agent sharing");
        result.put("memorySemanticCorrectionEnabled", false);
        result.put("memoryDistortionPolicy", "Retain model-generated omissions and distortions verbatim; no factual correction, archive retrieval or semantic repair");
        result.put("memoryRecoveryEnabled", false);
        result.put("memoryUpdateTiming", "After actual QSim outcomes are recorded, before iteration completion; next day only");
        result.put("memoryReflectionInputs", List.of("previous self-written summary", "recent actual experiences including today",
                "personal attributes", "fixed profile", "arrival deadline", "available route identifiers"));
        result.put("memoryReflectionExperienceLimit", MorningMemoryUpdater.RECENT_EXPERIENCE_LIMIT);
        result.put("cumulativeRouteCountsSuppliedToLlm", false);
        result.put("memoryFailurePolicy", "Fail experiment after bounded repairs/retries; no silent reuse of stale summary");
        result.put("llmCallsPerControlledTripDay", policyName.equals("llm") ? 2 : 0);
        result.put("personalProfiles", Map.of("mode", personalProfiles, "source", personalProfiles.equals("input") ? "input attributes only"
                        : profileFile == null ? "seeded synthetic legacy categories" : "imported fixed profiles",
                "seed", profileSeed == null ? populationSeed : profileSeed,
                "assignment", "SHA-256 personId-derived independent RNG; same person across cohort sizes; imported profiles override sampling",
                "calibrated", false, "changesNativeScoring", false, "changesDeadlines", false));
        if (profileFile != null) result.put("profileFileHash", inputHash(profileFile.toAbsolutePath()));
        result.put("forecastBinSeconds", config.travelTimeCalculator().getTraveltimeBinSize());
        result.put("baselineObjective", cohort.controlledPersons.isEmpty() ? "native MATSim scoring"
                : policyName.equals("llm") ? "LLM joint departure-time and route choice from available environment facts and personal memory; no MATSim score supplied"
                : "offline policy; mnl/shortest use expected car time only, mock uses choice counts");
        result.put("candidateCountDistribution", cohort.members.values().stream().collect(java.util.stream.Collectors.groupingBy(m -> m.candidates.size(), java.util.TreeMap::new, java.util.stream.Collectors.counting())));
        result.put("controlledCandidateCountDistribution", cohort.members.values().stream().filter(m -> m.controlled)
                .collect(java.util.stream.Collectors.groupingBy(m -> m.candidates.size(), java.util.TreeMap::new, java.util.stream.Collectors.counting())));
        result.put("singleCandidateTreatment", "Keep traffic and LLM calls; only the sole route is legal; exclude from multi-candidate route-switch metric, not from overall DSR or traffic metrics");
        result.put("outputContractVersion", MorningOutputContract.VERSION);
        result.put("outputValidation", "Exact JSON and local route/departure-enum/character validation always; per-request route enum and text length schema when response-format=json_schema; no silent fallback");
        result.put("memoryRouteReferenceGuard", "Diagnostic only: log unavailable reserved cN identifiers without rejecting or rewriting the summary; executable route choices remain restricted to available candidates");
        result.put("historyDays", historyDays); result.put("logitBetaPerMinute", beta);
        result.put("promptTemplate", MorningPromptRenderer.VERSION);
        result.put("candidatePresentation", Map.of("order", candidateOrder, "seed", candidateOrderSeed));
        result.put("memoryRetention", "Complete per-trip chronological facts in MorningMemory and experiences.jsonl.gz for every cohort trip every day, independent of trip-output-interval; recent prompt window only");
        result.put("initialMemory", "Empty factual history and empty self-written summary; synthetic profile is a preference assumption, not a past experience");
        result.put("accessEgressType", config.routing().getAccessEgressType().toString());
        result.put("lateArrivalUtilsPerHour", config.scoring().getLateArrival_utils_hr());
        result.put("carTravelUtilsPerHour", config.scoring().getModes().get("car").getMarginalUtilityOfTraveling());
        result.put("insertingWaitingVehiclesBeforeDrivingVehicles", config.qsim().isInsertingWaitingVehiclesBeforeDrivingVehicles());
        result.put("nativeStrategies", config.replanning().getStrategySettings().stream().map(s -> Map.of(
                "strategy", s.getStrategyName(), "weight", s.getWeight(), "disableAfterIteration", s.getDisableAfter())).toList());
        result.put("nativePlanMemorySize", config.replanning().getMaxAgentPlanMemorySize());
        result.put("nativeTimeMutation", Map.of("enabled", !fixesTimes() && config.replanning().getStrategySettings().stream()
                .anyMatch(s -> s.getStrategyName().equals("TimeAllocationMutator") && s.getWeight() > 0),
                "mutationRangeSeconds", config.timeAllocationMutator().getMutationRange(),
                "mutationRangeStepSeconds", config.timeAllocationMutator().getMutationRangeStep(),
                "affectingDuration", config.timeAllocationMutator().isAffectingDuration(),
                "aroundInitialEndTimeOnly", config.timeAllocationMutator().isMutateAroundInitialEndTimeOnly(),
                "scope", "Native full-day plans; controlled target departures are overwritten by the configured morning policy"));
        result.put("plannedTimesFixed", fixesTimes() && !(policyName.equals("llm") && !cohort.controlledPersons.isEmpty()));
        result.put("departureProtocol", Map.of("originalPlanAnchor", true, "earlierSeconds", departureBeforeSeconds,
                "laterSeconds", departureAfterSeconds, "stepSeconds", departureStepSeconds,
                "enabledForLlmTargetTrips", policyName.equals("llm") && !cohort.controlledPersons.isEmpty(),
                "boundaryTreatment", "Clip before original origin activity start or day zero and at exclusive QSim end; do not constrain by arrival deadline; retain original departure",
                "otherActivityEndTimes", fixesTimes() ? "Original plan ends restored; actual times can be delayed by earlier activities or queues"
                        : "Native selected-plan activity ends retained; actual times can be delayed by earlier activities or queues",
                "measurement", "Original, selected and actual departure separately recorded; full-day traffic objective, fixed analysis/peak windows are auxiliary"));
        result.put("linkMeasurementBinSeconds", managerEnabled && managerControls.basic() ? MorningManagerControls.BASIC_BIN_SECONDS : LinkTrafficMetrics.BIN_SECONDS);
        result.put("deadlineTreatment", "Latest activity start is observed, not a hard guarantee; scoring parameters are unchanged");
        result.put("apiCalls", 0);
        if (policyName.equals("llm")) {
            var modelSettings = new LinkedHashMap<>(llmConfig.publicModelSettings());
            modelSettings.put("concurrency", concurrency); modelSettings.put("maxOutputAttempts", repairAttempts);
            modelSettings.put("stallSeconds", stallSeconds); modelSettings.put("maxRequests", maxRequests);
            result.put("llmSettings", modelSettings);
            if (reflects()) result.put("memoryModelSettings", llmConfig.withOutputLimit(memoryMaxOutputTokens).publicModelSettings());
        }
        result.put("source", "Local enriched Sioux Falls files, public car-only doubled-demand lineage DOI 10.17632/2332kg48c8.3; local hashes identify inputs, not a claim of byte-for-byte download verification");
        result.put("designChanges", fixed() ? List.of("KeepLastSelected for everyone", "only morning per-OD route choice replaced",
                "original planned activity end times restored", "return/background initial routes retained; actual timings may be delayed by traffic")
                : hybrid() ? List.of("native full-day ReRoute and ChangeExpBeta for everyone, including controlled persons' other trips",
                nativeTimeMutation ? "TimeAllocationMutator retained; native activity end times are not restored; controlled target departures follow their morning policy"
                        : "TimeAllocationMutator disabled; original planned activity end times restored on current selected plans",
                "only selected persons' cohort actions overwritten after native replanning; LLM chooses departure and route jointly", "native plan memory retained; no global freeze")
                : List.of("modern MATSim 2026 config/API compatibility", "analysis listener", "explicit access/egress walking", "run-length/seed overrides only if requested"));
        var inputHashes = new LinkedHashMap<String, Object>();
        inputHashes.put("config", inputHash(configFile.toAbsolutePath()));
        inputHashes.put("network", inputHash(resolveInput(config.network().getInputFile())));
        inputHashes.put("population", inputHash(resolveInput(config.plans().getInputFile())));
        inputHashes.put("facilities", inputHash(resolveInput(config.facilities().getInputFile())));
        if (fixedTollsEnabled()) inputHashes.put("fixedTollPolicy", fixedTollPolicy.source());
        result.put("inputHashes", inputHashes);
        return result;
    }
    private Path resolveInput(String file) { return configFile.toAbsolutePath().getParent().resolve(file).normalize(); }
    private static Map<String, Object> inputHash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = stream.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return Map.of("path", file.toString(), "bytes", Files.size(file), "sha256", HexFormat.of().formatHex(digest.digest()));
    }
}
