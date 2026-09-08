package org.matsim.llmprocess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.agentic.api.AgentAction;
import org.matsim.agentic.api.AgentExperience;
import org.matsim.agentic.api.DecisionContext;
import org.matsim.agentic.api.DecisionProvider;
import org.matsim.agentic.manager.ManagerRuntime;
import org.matsim.agentic.provider.MockDecisionProvider;
import org.matsim.agentic.provider.OpenAICompatibleDecisionProvider;
import org.matsim.agentic.scenario.CityScenarioAdapter;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.XY2LinksForFacilities;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.ExperiencedPlansService;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.VehicleType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class IterativeLlmDecisionController
        implements StartupListener, IterationEndsListener, ShutdownListener {

    private static final Logger LOG = LogManager.getLogger(IterativeLlmDecisionController.class);
    private static final int HISTORY_LINES = 10;
    static final String LLM_SUBPOPULATION = "llm";

    private final Controler controler;
    private final CityScenarioAdapter scenarioAdapter;
    private final ManagerRuntime manager;
    private final Map<Id<Person>, AgentState> agents = new LinkedHashMap<>();
    private final List<DecisionRow> decisions = new ArrayList<>();
    private final List<ExperienceRow> experiences = new ArrayList<>();

    private ExperimentSettings settings;
    private DecisionProvider provider;
    private ExecutorService executor;
    private TripRouter tripRouter;
    private Path decisionFile;
    private Path experienceFile;
    private Path selectionFile;
    private Path actionMemoryFile;
    private Path runManifestFile;

    IterativeLlmDecisionController(
            Controler controler,
            CityScenarioAdapter scenarioAdapter, ManagerRuntime manager) {
        this.controler = controler;
        this.scenarioAdapter = scenarioAdapter;
        this.manager = manager;
    }

    @Override
    public void notifyStartup(StartupEvent event) {
        settings = ExperimentSettings.fromEnvironment();
        Scenario scenario = controler.getScenario();
        XY2LinksForFacilities.run(scenario.getNetwork(), scenario.getActivityFacilities());
        tripRouter = controler.getTripRouterProvider().get();
        executor = Executors.newFixedThreadPool(settings.maxConcurrency());
        provider = createProvider(settings);

        Path output = Path.of(controler.getControllerIO().getOutputPath());
        if (manager != null) manager.start(output);
        decisionFile = output.resolve("llm-decisions.csv");
        experienceFile = output.resolve("llm-experiences.csv");
        selectionFile = output.resolve("llm-agent-selection.csv");
        actionMemoryFile = output.resolve("llm-action-memory.csv");
        runManifestFile = output.resolve("run-manifest.json");

        int agentCount = resolveAgentCount(
                scenario.getPopulation().getPersons().size());
        List<Person> selected = selectStratified(
                scenario.getPopulation().getPersons().values(),
                agentCount, settings.sampleSeed());
        Id<VehicleType> defaultVehicleTypeId = Id.create(
                "defaultVehicleType", VehicleType.class);
        VehicleType defaultVehicleType = scenario.getVehicles()
                .getVehicleTypes().get(defaultVehicleTypeId);
        if (defaultVehicleType == null) {
            defaultVehicleType = VehicleUtils.createDefaultVehicleType();
            scenario.getVehicles().addVehicleType(defaultVehicleType);
        }
        for (Person person : selected) {
            if (!LLM_SUBPOPULATION.equals(PopulationUtils.getSubpopulation(person))) {
                throw new IllegalStateException(
                        "LLM subpopulation was not assigned during scenario preparation: "
                                + person.getId());
            }
            if (PersonUtils.canUseCar(person)) {
                var vehicleId = VehicleUtils.createVehicleId(person, "car");
                if (!scenario.getVehicles().getVehicles().containsKey(vehicleId)) {
                    scenario.getVehicles().addVehicle(
                            VehicleUtils.createVehicle(vehicleId, defaultVehicleType));
                }
                VehicleUtils.insertVehicleIdsIntoAttributes(
                        person, Map.of("car", vehicleId));
            }
            AgentState state = AgentState.from(
                    person, scenarioAdapter.allowedModes(person));
            agents.put(person.getId(), state);
        }
        writeSelection();
        writeRunManifest(scenario);

        LOG.info("Agentic Mobility experiment: agents={}, MATSim iterations={}..{}, "
                        + "provider={}, model={}, maxConcurrency={}, shifts={}, "
                        + "exploration={}..{}, rollbackAfter={}, modeFailureThreshold={}",
                agents.size(), controler.getConfig().controller().getFirstIteration(),
                controler.getConfig().controller().getLastIteration(), provider.name(),
                providerDisplayModel(), settings.maxConcurrency(),
                settings.allowedDepartureShifts(), settings.explorationRateStart(),
                settings.explorationRateEnd(), settings.rollbackAfterDeclines(),
                settings.modeFailureThreshold());

        decideAndApply(0, null);
        writeTables();
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        int iteration = event.getIteration();
        ExperiencedPlansService service = controler.getInjector()
                .getInstance(ExperiencedPlansService.class);
        Map<Id<Person>, Plan> experiencedPlans = service.getExperiencedPlans();
        Map<Id<Person>, AgentExperience> observed = new HashMap<>();

        for (AgentState state : agents.values()) {
            Plan experiencedPlan = experiencedPlans.get(state.person().getId());
            Plan selectedPlan = state.person().getSelectedPlan();
            Double scoredPlanScore = selectedPlan == null ? null : selectedPlan.getScore();
            // The selected plan at IterationEnds is the plan that has just been
            // executed and scored. Some iteration-0 experienced plans do not retain
            // routing-mode attributes, so they are unsuitable for identifying mode.
            Plan executedModePlan = selectedPlan == null ? experiencedPlan : selectedPlan;
            String executedMode = mainMode(executedModePlan);
            AgentExperience experience = summarizeExperience(
                    iteration, experiencedPlan, selectedPlan,
                    executedMode, scoredPlanScore);
            observed.put(state.person().getId(), experience);
            experiences.add(ExperienceRow.from(
                    state.person().getId(), experience,
                    state.lastAppliedMode(), state.lastDepartureShiftMinutes(),
                    state.baselineFirstDepartureSeconds()));
            state.observe(experience, settings);
            state.addHistory((manager == null ? "" : "policy=" + manager.policy().version() + " ")
                    + "outcome it=%d mode=%s time=%.2fmin distance=%.2fkm score=%.3f"
                    .formatted(iteration, experience.mode(), experience.travelTimeMinutes(),
                            experience.distanceKm(), experience.score()));
        }

        int lastIteration = controler.getConfig().controller().getLastIteration();
        if (manager != null) {
            int previousPolicy = manager.policy().version();
            manager.iterationEnded(iteration, lastIteration);
            if (previousPolicy != manager.policy().version()) {
                // Preserve the old regime's aggregate memory before resetting score comparisons.
                writeTables();
                try {
                    Files.copy(actionMemoryFile, actionMemoryFile.resolveSibling(
                            "llm-action-memory-policy-" + previousPolicy + ".csv"));
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
                for (AgentState state : agents.values()) {
                    state.actionMemory.clear();
                    state.bestExperiencedScore = Double.NEGATIVE_INFINITY;
                    state.consecutiveScoreDeclines = 0;
                    state.addHistory("policy_change " + previousPolicy + " -> "
                            + manager.policy().version()
                            + "; newest outcome belongs to previous policy; action statistics reset");
                }
            }
        }
        if (iteration < lastIteration) {
            decideAndApply(iteration + 1, observed);
        }
        writeTables();
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        writeTables();
        if (executor != null) {
            executor.shutdownNow();
        }
        if (provider != null) {
            provider.close();
        }
        if (manager != null) manager.close();
    }

    private static DecisionProvider createProvider(ExperimentSettings settings) {
        if ("mock".equals(settings.providerType())) {
            return new MockDecisionProvider();
        }
        if ("openai-compatible".equals(settings.providerType())) {
            return new OpenAICompatibleDecisionProvider(
                    new OpenAICompatibleDecisionProvider.Settings(
                            settings.apiKey(), settings.baseUrl(), settings.model(),
                            settings.timeoutSeconds(), 3, 0.2, 350,
                            "/prompts/travel-decision-v1.txt"));
        }
        throw new IllegalArgumentException(
                "Unsupported AGENTIC_PROVIDER: " + settings.providerType());
    }

    private void decideAndApply(
            int targetIteration,
            Map<Id<Person>, AgentExperience> observed) {
        List<AgentState> states = new ArrayList<>(agents.values());
        List<Future<DecisionResult>> futures = new ArrayList<>();

        for (AgentState state : states) {
            AgentExperience experience = observed == null
                    ? null : observed.get(state.person().getId());
            DecisionContext context = state.context(
                    targetIteration, experience, settings).withPolicy(manager == null ? ""
                    : manager.travelerContext(state.person().getId().toString()));
            Callable<DecisionResult> task = () -> requestDecision(state, context);
            futures.add(executor.submit(task));
        }

        for (int index = 0; index < states.size(); index++) {
            AgentState state = states.get(index);
            DecisionResult result;
            try {
                result = futures.get(index).get();
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String currentMode = mainMode(state.person().getSelectedPlan());
                result = new DecisionResult(
                        new AgentAction(
                                currentMode, state.lastDepartureShiftMinutes(), 0.0,
                                "Decision task failed; retained prior action", "fallback",
                                true, exception.toString()),
                        0L);
            }

            AgentAction proposedAction = result.action();
            PolicyDecision policy = state.applyPolicy(
                    proposedAction, targetIteration,
                    controler.getConfig().controller().getLastIteration(), settings);
            AgentAction action = policy.action();
            String previousMode = mainMode(state.person().getSelectedPlan());
            boolean applied = false;
            String applyError = "";
            try {
                applyAction(state, action);
                applied = true;
                state.lastDepartureShiftMinutes(action.departureShiftMinutes());
            } catch (Exception exception) {
                applyError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                LOG.warn("Could not apply LLM action for person {} at iteration {}: {}",
                        state.person().getId(), targetIteration, applyError);
            }

            decisions.add(new DecisionRow(
                    state.person().getId().toString(), targetIteration,
                    observed == null ? -1 : targetIteration - 1,
                    previousMode, proposedAction.mode(),
                    proposedAction.departureShiftMinutes(), action.mode(),
                    action.departureShiftMinutes(), policy.policy(),
                    policy.exploration(), policy.rollback(), action.confidence(),
                    proposedAction.source(), proposedAction.fallback(), applied,
                    result.latencyMs(),
                    proposedAction.tokenUsage().promptTokens(),
                    proposedAction.tokenUsage().promptCacheHitTokens(),
                    proposedAction.tokenUsage().promptCacheMissTokens(),
                    proposedAction.tokenUsage().completionTokens(),
                    proposedAction.tokenUsage().totalTokens(),
                    proposedAction.reason(), policy.reason(),
                    combineErrors(action.error(), applyError)));
            state.addHistory("decision it=%d proposed=%s@%d applied=%s@%d policy=%s confidence=%.2f reason=%s"
                    .formatted(targetIteration, proposedAction.mode(),
                            proposedAction.departureShiftMinutes(), action.mode(),
                            action.departureShiftMinutes(), policy.policy(),
                            action.confidence(), proposedAction.reason()));
        }
    }

    private DecisionResult requestDecision(
            AgentState state,
            DecisionContext context) {
        long started = System.nanoTime();
        AgentAction action;
        try {
            action = provider.decide(context);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ActionKey fallback = state.fallbackAction(settings);
            action = new AgentAction(
                    fallback.mode(), fallback.departureShiftMinutes(), 0.0,
                    "API failure; retained previous action", "fallback", true,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        return new DecisionResult(action, latencyMs);
    }

    private void applyAction(AgentState state, AgentAction action) {
        Person person = state.person();
        Plan plan = person.getSelectedPlan();
        List<Activity> mainActivities = TripStructureUtils.getActivities(
                plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
        if (mainActivities.size() < 2) {
            throw new IllegalStateException("Selected plan has fewer than two main activities");
        }

        double targetDeparture = state.baselineFirstDepartureSeconds()
                + action.departureShiftMinutes() * 60.0;
        List<TripStructureUtils.Trip> trips = new ArrayList<>(TripStructureUtils.getTrips(plan));
        List<List<? extends PlanElement>> newRoutes = new ArrayList<>();
        for (int index = 0; index < trips.size(); index++) {
            TripStructureUtils.Trip trip = trips.get(index);
            double departure = index == 0
                    ? targetDeparture
                    : TripStructureUtils.getDepartureTime(trip).orElse(targetDeparture);
            newRoutes.add(tripRouter.calcRoute(
                    action.mode(),
                    FacilitiesUtils.toFacility(
                            trip.getOriginActivity(), controler.getScenario().getActivityFacilities()),
                    FacilitiesUtils.toFacility(
                            trip.getDestinationActivity(), controler.getScenario().getActivityFacilities()),
                    departure,
                    person,
                    trip.getTripAttributes()));
        }

        mainActivities.getFirst().setEndTime(targetDeparture);
        for (int index = 0; index < trips.size(); index++) {
            TripStructureUtils.Trip trip = trips.get(index);
            TripRouter.insertTrip(plan, trip.getOriginActivity(),
                    newRoutes.get(index), trip.getDestinationActivity());
        }
        state.lastAppliedMode(action.mode());
    }

    private static AgentExperience summarizeExperience(
            int iteration, Plan experiencedPlan, Plan fallbackPlan,
            String executedMode, Double eventScore) {
        Plan plan = experiencedPlan == null ? fallbackPlan : experiencedPlan;
        double travelTimeSeconds = 0.0;
        double distanceMeters = 0.0;
        double firstDeparture = Double.NaN;
        for (Leg leg : TripStructureUtils.getLegs(plan)) {
            double legTime = leg.getTravelTime().orElse(
                    leg.getRoute() == null ? 0.0
                            : leg.getRoute().getTravelTime().orElse(0.0));
            if (Double.isFinite(legTime) && legTime >= 0.0) {
                travelTimeSeconds += legTime;
            }
            Route route = leg.getRoute();
            if (route != null && Double.isFinite(route.getDistance())
                    && route.getDistance() >= 0.0) {
                distanceMeters += route.getDistance();
            }
            if (!Double.isFinite(firstDeparture) && leg.getDepartureTime().isDefined()) {
                firstDeparture = leg.getDepartureTime().seconds();
            }
        }
        double score = eventScore != null
                ? eventScore
                : plan.getScore() == null ? Double.NaN : plan.getScore();
        return new AgentExperience(
                iteration, executedMode, travelTimeSeconds / 60.0,
                distanceMeters / 1000.0, score,
                Double.isFinite(firstDeparture) ? firstDeparture / 3600.0 : Double.NaN);
    }

    private static String mainMode(Plan plan) {
        boolean pt = false;
        String nonTransitMode = "walk";
        for (Leg leg : TripStructureUtils.getLegs(plan)) {
            String mode = leg.getMode();
            if ("car".equals(mode)) {
                return "car";
            }
            if ("pt".equals(mode) || "transit_walk".equals(mode)) {
                pt = true;
            } else if (mode != null && !mode.isBlank()
                    && !"walk".equals(mode) && !"non_network_walk".equals(mode)) {
                nonTransitMode = mode;
            }
        }
        return pt ? "pt" : nonTransitMode;
    }

    static List<Person> selectStratified(
            Collection<? extends Person> population,
            int count,
            long seed) {
        List<Person> carAvailable = new ArrayList<>();
        List<Person> noCar = new ArrayList<>();
        for (Person person : population) {
            if (person.getSelectedPlan() == null
                    || TripStructureUtils.getLegs(person.getSelectedPlan()).isEmpty()) {
                continue;
            }
            (PersonUtils.canUseCar(person) ? carAvailable : noCar).add(person);
        }
        Comparator<Person> comparator = Comparator
                .comparingLong((Person person) -> sampleKey(person, seed))
                .thenComparing(person -> person.getId().toString());
        carAvailable.sort(comparator);
        noCar.sort(comparator);

        int eligiblePopulation = carAvailable.size() + noCar.size();
        int carTarget = Math.min(carAvailable.size(), (int) Math.round(
                (double) count * carAvailable.size() / eligiblePopulation));
        int noCarTarget = Math.min(noCar.size(), count - carTarget);
        List<Person> selected = new ArrayList<>();
        selected.addAll(carAvailable.subList(0, carTarget));
        selected.addAll(noCar.subList(0, noCarTarget));
        if (selected.size() < count) {
            List<Person> remaining = new ArrayList<>();
            remaining.addAll(carAvailable.subList(carTarget, carAvailable.size()));
            remaining.addAll(noCar.subList(noCarTarget, noCar.size()));
            remaining.sort(comparator);
            selected.addAll(remaining.subList(0, Math.min(
                    count - selected.size(), remaining.size())));
        }
        selected.sort(Comparator.comparing(person -> person.getId().toString()));
        return selected;
    }

    static int resolveAgentCount(int totalPopulation) {
        if (totalPopulation < 1) {
            throw new IllegalArgumentException("Population must contain at least one person");
        }
        String explicitCount = System.getenv("LLM_AGENT_COUNT");
        if (explicitCount != null && !explicitCount.isBlank()) {
            int count = Integer.parseInt(explicitCount.trim());
            if (count < 1) {
                throw new IllegalArgumentException("LLM_AGENT_COUNT must be at least 1");
            }
            return Math.min(count, totalPopulation);
        }

        String rawShare = System.getenv("LLM_AGENT_SHARE");
        double share = Double.parseDouble(
                rawShare == null || rawShare.isBlank() ? "0.01" : rawShare.trim());
        if (!Double.isFinite(share) || share <= 0.0 || share > 1.0) {
            throw new IllegalArgumentException(
                    "LLM_AGENT_SHARE must be greater than 0 and at most 1");
        }
        return Math.min(totalPopulation,
                Math.max(1, (int) Math.round(totalPopulation * share)));
    }

    private static long sampleKey(Person person, long seed) {
        long value = Integer.toUnsignedLong(person.getId().toString().hashCode()) ^ seed;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private void writeSelection() {
        List<String> lines = new ArrayList<>();
        lines.add("person_id,age,sex,car_available,initial_mode,daily_beeline_km,"
                + "baseline_first_departure_hour,activity_chain");
        for (AgentState state : agents.values()) {
            lines.add(String.join(",",
                    csv(state.person().getId().toString()),
                    number(PersonUtils.getAge(state.person())),
                    csv(PersonUtils.getSex(state.person())),
                    Boolean.toString(PersonUtils.canUseCar(state.person())),
                    csv(mainMode(state.person().getSelectedPlan())),
                    format(state.dailyBeelineKm()),
                    format(state.baselineFirstDepartureSeconds() / 3600.0),
                    csv(state.activityChain())));
        }
        writeLines(selectionFile, lines);
    }

    private void writeRunManifest(Scenario scenario) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schema_version", "1");
        manifest.put("project_version", "0.2.0-SNAPSHOT");
        manifest.put("matsim_version", "2026.0");
        manifest.put("java_version", System.getProperty("java.version"));
        manifest.put("provider", provider.name());
        manifest.put("manager_mode", manager == null ? "off" : manager.mode());
        manifest.put("city_adapter", scenarioAdapter.id());
        manifest.put("model", providerDisplayModel());
        manifest.put("prompt", "/prompts/travel-decision-v1.txt");
        manifest.put("population_size", scenario.getPopulation().getPersons().size());
        manifest.put("agent_count", agents.size());
        manifest.put("first_iteration",
                controler.getConfig().controller().getFirstIteration());
        manifest.put("last_iteration",
                controler.getConfig().controller().getLastIteration());
        manifest.put("matsim_seed", controler.getConfig().global().getRandomSeed());
        manifest.put("agent_sample_seed", settings.sampleSeed());
        manifest.put("exploration_seed", settings.explorationSeed());
        manifest.put("max_concurrency", settings.maxConcurrency());
        manifest.put("git_commit", ExperimentSettings.env("GIT_COMMIT", "unknown"));
        try {
            Files.createDirectories(runManifestFile.toAbsolutePath().getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    runManifestFile.toFile(), manifest);
        } catch (IOException exception) {
            throw new RuntimeException("Could not write " + runManifestFile, exception);
        }
    }

    private String providerDisplayModel() {
        return "mock".equals(provider.name())
                ? "deterministic-mock-v1" : settings.model();
    }

    private void writeTables() {
        if (decisionFile == null) {
            return;
        }
        List<String> decisionLines = new ArrayList<>();
        decisionLines.add("person_id,target_iteration,observed_iteration,previous_mode,"
                + "proposed_mode,proposed_departure_shift_min,chosen_mode,"
                + "departure_shift_min,policy,exploration,rollback,confidence,source,"
                + "fallback,applied,latency_ms,prompt_tokens,"
                + "prompt_cache_hit_tokens,prompt_cache_miss_tokens,"
                + "completion_tokens,total_tokens,reason,policy_reason,error");
        decisions.forEach(row -> decisionLines.add(row.toCsv()));
        writeLines(decisionFile, decisionLines);

        List<String> experienceLines = new ArrayList<>();
        experienceLines.add("person_id,iteration,executed_mode,travel_time_min,"
                + "distance_km,score,first_departure_hour,requested_mode,"
                + "requested_departure_shift_min,mode_matches_request,"
                + "departure_shift_error_min");
        experiences.forEach(row -> experienceLines.add(row.toCsv()));
        writeLines(experienceFile, experienceLines);

        List<String> memoryLines = new ArrayList<>();
        memoryLines.add("person_id,mode,departure_shift_min,observations,avg_score,"
                + "best_score,avg_travel_time_min,last_score,last_iteration,blocked_mode");
        for (AgentState state : agents.values()) {
            memoryLines.addAll(state.memoryRows());
        }
        writeLines(actionMemoryFile, memoryLines);
    }

    private static void writeLines(Path path, List<String> lines) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(
                    path, StandardCharsets.UTF_8)) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException exception) {
            throw new RuntimeException("Could not write " + path, exception);
        }
    }

    private static String combineErrors(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + " | " + second;
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private static String format(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.ROOT, "%.6f", value)
                : "";
    }

    private static String number(Integer value) {
        return value == null ? "" : Integer.toString(value);
    }

    private record DecisionResult(AgentAction action, long latencyMs) {
    }

    private record PolicyDecision(
            AgentAction action,
            String policy,
            boolean exploration,
            boolean rollback,
            String reason) {
    }

    private record DecisionRow(
            String personId, int targetIteration, int observedIteration,
            String previousMode, String proposedMode, int proposedDepartureShiftMinutes,
            String chosenMode, int departureShiftMinutes, String policy,
            boolean exploration, boolean rollback, double confidence, String source,
            boolean fallback, boolean applied, long latencyMs,
            long promptTokens, long promptCacheHitTokens,
            long promptCacheMissTokens, long completionTokens, long totalTokens,
            String reason,
            String policyReason, String error) {

        String toCsv() {
            return String.join(",",
                    csv(personId), Integer.toString(targetIteration),
                    Integer.toString(observedIteration), csv(previousMode), csv(proposedMode),
                    Integer.toString(proposedDepartureShiftMinutes), csv(chosenMode),
                    Integer.toString(departureShiftMinutes), csv(policy),
                    Boolean.toString(exploration), Boolean.toString(rollback),
                    format(confidence), csv(source), Boolean.toString(fallback),
                    Boolean.toString(applied), Long.toString(latencyMs),
                    Long.toString(promptTokens), Long.toString(promptCacheHitTokens),
                    Long.toString(promptCacheMissTokens),
                    Long.toString(completionTokens), Long.toString(totalTokens), csv(reason),
                    csv(policyReason), csv(error));
        }
    }

    private record ExperienceRow(
            String personId, int iteration, String mode, double travelTimeMinutes,
            double distanceKm, double score, double firstDepartureHour,
            String requestedMode, int requestedDepartureShiftMinutes,
            boolean modeMatchesRequest, double departureShiftErrorMinutes) {

        static ExperienceRow from(
                Id<Person> personId, AgentExperience experience,
                String requestedMode, int requestedDepartureShiftMinutes,
                double baselineFirstDepartureSeconds) {
            double actualShiftMinutes = Double.isFinite(experience.firstDepartureHour())
                    ? (experience.firstDepartureHour() * 3600.0
                    - baselineFirstDepartureSeconds) / 60.0
                    : Double.NaN;
            return new ExperienceRow(
                    personId.toString(), experience.iteration(), experience.mode(),
                    experience.travelTimeMinutes(), experience.distanceKm(),
                    experience.score(), experience.firstDepartureHour(), requestedMode,
                    requestedDepartureShiftMinutes,
                    requestedMode.equals(experience.mode()),
                    actualShiftMinutes - requestedDepartureShiftMinutes);
        }

        String toCsv() {
            return String.join(",",
                    csv(personId), Integer.toString(iteration), csv(mode),
                    format(travelTimeMinutes), format(distanceKm), format(score),
                    format(firstDepartureHour), csv(requestedMode),
                    Integer.toString(requestedDepartureShiftMinutes),
                    Boolean.toString(modeMatchesRequest),
                    format(departureShiftErrorMinutes));
        }
    }

    private record ActionKey(String mode, int departureShiftMinutes) {
        String label() {
            return mode + "@" + (departureShiftMinutes >= 0 ? "+" : "")
                    + departureShiftMinutes;
        }
    }

    private static final class ActionStats {
        private int observations;
        private double scoreSum;
        private double bestScore = Double.NEGATIVE_INFINITY;
        private double travelTimeSum;
        private double lastScore = Double.NaN;
        private int lastIteration = -1;

        void observe(AgentExperience experience) {
            observations++;
            scoreSum += experience.score();
            bestScore = Math.max(bestScore, experience.score());
            travelTimeSum += experience.travelTimeMinutes();
            lastScore = experience.score();
            lastIteration = experience.iteration();
        }

        double averageScore() {
            return observations == 0 ? Double.NEGATIVE_INFINITY : scoreSum / observations;
        }

        double averageTravelTime() {
            return observations == 0 ? Double.POSITIVE_INFINITY
                    : travelTimeSum / observations;
        }
    }

    private static final class AgentState {
        private final Person person;
        private final Set<String> baseAllowedModes;
        private final double baselineFirstDepartureSeconds;
        private final double dailyBeelineKm;
        private final String activityChain;
        private final Deque<String> history = new ArrayDeque<>();
        private final Map<ActionKey, ActionStats> actionMemory = new LinkedHashMap<>();
        private final Map<String, Integer> consecutiveModeMismatches = new HashMap<>();
        private final Set<String> blockedModes = new LinkedHashSet<>();
        private int lastDepartureShiftMinutes;
        private String lastAppliedMode;
        private double bestExperiencedScore = Double.NEGATIVE_INFINITY;
        private int consecutiveScoreDeclines;
        private int lastRollbackIteration = Integer.MIN_VALUE;

        private AgentState(
                Person person,
                Set<String> baseAllowedModes,
                double baselineFirstDepartureSeconds,
                double dailyBeelineKm,
                String activityChain) {
            this.person = person;
            this.baseAllowedModes = Collections.unmodifiableSet(
                    new LinkedHashSet<>(baseAllowedModes));
            this.baselineFirstDepartureSeconds = baselineFirstDepartureSeconds;
            this.dailyBeelineKm = dailyBeelineKm;
            this.activityChain = activityChain;
            this.lastAppliedMode = mainMode(person.getSelectedPlan());
        }

        static AgentState from(Person person, Set<String> baseAllowedModes) {
            Plan plan = person.getSelectedPlan();
            List<Activity> activities = TripStructureUtils.getActivities(
                    plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
            double departure = activities.getFirst().getEndTime().orElse(8.0 * 3600.0);
            double distance = 0.0;
            List<String> chain = new ArrayList<>();
            for (int index = 0; index < activities.size(); index++) {
                Activity activity = activities.get(index);
                chain.add(activity.getType());
                if (index > 0) {
                    Coord previous = activities.get(index - 1).getCoord();
                    Coord current = activity.getCoord();
                    if (previous != null && current != null) {
                        distance += Math.hypot(
                                current.getX() - previous.getX(),
                                current.getY() - previous.getY());
                    }
                }
            }
            return new AgentState(person, baseAllowedModes, departure, distance / 1000.0,
                    String.join(" -> ", chain));
        }

        DecisionContext context(
                int targetIteration, AgentExperience experience,
                ExperimentSettings settings) {
            Set<String> modes = feasibleModes();
            return new DecisionContext(
                    person.getId().toString(), targetIteration,
                    PersonUtils.getAge(person), PersonUtils.getSex(person),
                    PersonUtils.canUseCar(person), mainMode(person.getSelectedPlan()), modes,
                    settings.allowedDepartureShifts(), dailyBeelineKm, activityChain,
                    experience, String.join(System.lineSeparator(), history),
                    longTermMemory(settings));
        }

        void observe(
                AgentExperience experience,
                ExperimentSettings settings) {
            String requestedMode = lastAppliedMode;
            if (!requestedMode.equals(experience.mode())) {
                int failures = consecutiveModeMismatches.merge(
                        requestedMode, 1, Integer::sum);
                addHistory("feasibility it=%d requested=%s executed=%s consecutive_failures=%d"
                        .formatted(experience.iteration(), requestedMode,
                                experience.mode(), failures));
                if (failures >= settings.modeFailureThreshold()
                        && !"walk".equals(requestedMode)
                        && baseAllowedModes.size() > 1) {
                    blockedModes.add(requestedMode);
                    addHistory("blocked mode=%s after %d consecutive execution failures"
                            .formatted(requestedMode, failures));
                }
                return;
            }
            consecutiveModeMismatches.put(requestedMode, 0);
            if (!Double.isFinite(experience.score())
                    || experience.iteration() < settings.memoryWarmupIterations()) {
                return;
            }

            double previousBest = bestExperiencedScore;
            ActionKey action = new ActionKey(
                    requestedMode, lastDepartureShiftMinutes);
            actionMemory.computeIfAbsent(action, ignored -> new ActionStats())
                    .observe(experience);

            if (Double.isFinite(previousBest)
                    && experience.score() < previousBest - settings.rollbackScoreTolerance()) {
                consecutiveScoreDeclines++;
            } else {
                consecutiveScoreDeclines = 0;
            }
            bestExperiencedScore = Math.max(bestExperiencedScore, experience.score());
        }

        PolicyDecision applyPolicy(
                AgentAction proposed,
                int targetIteration,
                int lastIteration,
                ExperimentSettings settings) {
            ActionKey proposedKey = new ActionKey(
                    proposed.mode(), proposed.departureShiftMinutes());
            if (blockedModes.contains(proposed.mode())
                    || !settings.allowedDepartureShifts().contains(
                    proposed.departureShiftMinutes())) {
                ActionKey replacement = bestFeasibleAction(settings);
                return policyDecision(proposed, replacement, "feasibility_guard",
                        false, false, "Proposed action was infeasible or blocked");
            }

            if (consecutiveScoreDeclines >= settings.rollbackAfterDeclines()
                    && targetIteration - lastRollbackIteration
                    >= settings.rollbackCooldownIterations()) {
                ActionKey best = bestFeasibleAction(settings);
                consecutiveScoreDeclines = 0;
                lastRollbackIteration = targetIteration;
                return policyDecision(proposed, best, "rollback", false, true,
                        "Restored highest-average-score feasible action after sustained decline");
            }

            double progress = lastIteration <= 0 ? 1.0
                    : Math.min(1.0, Math.max(0.0,
                    targetIteration / (double) lastIteration));
            double explorationRate = settings.explorationRateStart()
                    + (settings.explorationRateEnd() - settings.explorationRateStart())
                    * progress;
            if (targetIteration >= settings.memoryWarmupIterations()
                    && deterministicUnit(
                    settings.explorationSeed() + targetIteration * 104729L)
                    < explorationRate) {
                ActionKey exploration = leastObservedAction(settings, targetIteration);
                return policyDecision(proposed, exploration, "exploration", true, false,
                        "Controlled exploration of a least-observed feasible action");
            }
            return policyDecision(proposed, proposedKey, "provider", false, false,
                    "Accepted score-oriented provider proposal");
        }

        private PolicyDecision policyDecision(
                AgentAction proposed,
                ActionKey selected,
                String policy,
                boolean exploration,
                boolean rollback,
                String reason) {
            AgentAction applied = new AgentAction(
                    selected.mode(), selected.departureShiftMinutes(),
                    proposed.confidence(), proposed.reason(), proposed.source(),
                    proposed.fallback(), proposed.error());
            return new PolicyDecision(applied, policy, exploration, rollback, reason);
        }

        ActionKey fallbackAction(ExperimentSettings settings) {
            ActionKey current = new ActionKey(lastAppliedMode, lastDepartureShiftMinutes);
            if (!blockedModes.contains(current.mode())
                    && settings.allowedDepartureShifts().contains(
                    current.departureShiftMinutes())) {
                return current;
            }
            return bestFeasibleAction(settings);
        }

        private ActionKey bestFeasibleAction(ExperimentSettings settings) {
            return actionMemory.entrySet().stream()
                    .filter(entry -> !blockedModes.contains(entry.getKey().mode()))
                    .max(Comparator
                            .comparingDouble((Map.Entry<ActionKey, ActionStats> entry)
                                    -> entry.getValue().averageScore())
                            .thenComparingDouble(entry
                                    -> -entry.getValue().averageTravelTime()))
                    .map(Map.Entry::getKey)
                    .orElseGet(() -> {
                        String mode = feasibleModes().contains(lastAppliedMode)
                                ? lastAppliedMode : feasibleModes().iterator().next();
                        int shift = settings.allowedDepartureShifts().contains(
                                lastDepartureShiftMinutes)
                                ? lastDepartureShiftMinutes
                                : settings.allowedDepartureShifts().iterator().next();
                        return new ActionKey(mode, shift);
                    });
        }

        private ActionKey leastObservedAction(
                ExperimentSettings settings, int targetIteration) {
            List<ActionKey> candidates = new ArrayList<>();
            for (String mode : feasibleModes()) {
                for (int shift : settings.allowedDepartureShifts()) {
                    candidates.add(new ActionKey(mode, shift));
                }
            }
            candidates.sort(Comparator
                    .comparingInt((ActionKey action) -> actionMemory
                            .getOrDefault(action, new ActionStats()).observations)
                    .thenComparingLong(action -> sampleKey(
                            person, settings.explorationSeed()
                                    + targetIteration * 1009L
                                    + action.hashCode())));
            return candidates.getFirst();
        }

        private Set<String> feasibleModes() {
            Set<String> modes = new LinkedHashSet<>(baseAllowedModes);
            modes.removeAll(blockedModes);
            if (modes.isEmpty()) {
                modes.addAll(baseAllowedModes);
            }
            return modes;
        }

        private double deterministicUnit(long seed) {
            long bits = sampleKey(person, seed) >>> 11;
            return bits * 0x1.0p-53;
        }

        private String longTermMemory(ExperimentSettings settings) {
            List<Map.Entry<ActionKey, ActionStats>> ranked = new ArrayList<>(
                    actionMemory.entrySet());
            ranked.sort(Comparator
                    .comparingDouble((Map.Entry<ActionKey, ActionStats> entry)
                            -> entry.getValue().averageScore()).reversed());
            List<String> lines = new ArrayList<>();
            ActionKey bestFeasible = bestFeasibleAction(settings);
            lines.add("blocked_modes=" + blockedModes
                    + ", consecutive_score_declines=" + consecutiveScoreDeclines);
            lines.add("best_feasible_by_average_score=" + bestFeasible.label());
            for (Map.Entry<ActionKey, ActionStats> entry : ranked) {
                ActionStats stats = entry.getValue();
                lines.add("action=%s blocked=%s n=%d avg_score=%.4f best_score=%.4f "
                                .formatted(entry.getKey().label(),
                                        blockedModes.contains(entry.getKey().mode()),
                                        stats.observations,
                                        stats.averageScore(), stats.bestScore)
                        + "avg_time_min=%.3f last_score=%.4f last_it=%d"
                        .formatted(stats.averageTravelTime(), stats.lastScore,
                                stats.lastIteration));
            }
            lines.add("allowed_departure_shifts=" + settings.allowedDepartureShifts());
            return String.join(System.lineSeparator(), lines);
        }

        List<String> memoryRows() {
            List<String> rows = new ArrayList<>();
            Set<String> representedModes = new LinkedHashSet<>();
            for (Map.Entry<ActionKey, ActionStats> entry : actionMemory.entrySet()) {
                ActionKey action = entry.getKey();
                ActionStats stats = entry.getValue();
                representedModes.add(action.mode());
                rows.add(String.join(",",
                        csv(person.getId().toString()), csv(action.mode()),
                        Integer.toString(action.departureShiftMinutes()),
                        Integer.toString(stats.observations),
                        format(stats.averageScore()), format(stats.bestScore),
                        format(stats.averageTravelTime()), format(stats.lastScore),
                        Integer.toString(stats.lastIteration),
                        Boolean.toString(blockedModes.contains(action.mode()))));
            }
            for (String blockedMode : blockedModes) {
                if (!representedModes.contains(blockedMode)) {
                    rows.add(String.join(",",
                            csv(person.getId().toString()), csv(blockedMode), "", "0",
                            "", "", "", "", "", "true"));
                }
            }
            return rows;
        }

        void addHistory(String line) {
            history.addLast(line);
            while (history.size() > HISTORY_LINES) {
                history.removeFirst();
            }
        }

        Person person() {
            return person;
        }

        double baselineFirstDepartureSeconds() {
            return baselineFirstDepartureSeconds;
        }

        double dailyBeelineKm() {
            return dailyBeelineKm;
        }

        String activityChain() {
            return activityChain;
        }

        int lastDepartureShiftMinutes() {
            return lastDepartureShiftMinutes;
        }

        void lastDepartureShiftMinutes(int value) {
            lastDepartureShiftMinutes = value;
        }

        String lastAppliedMode() {
            return lastAppliedMode;
        }

        void lastAppliedMode(String value) {
            lastAppliedMode = value;
        }
    }

    private record ExperimentSettings(
            String providerType, String apiKey, String baseUrl, String model,
            long sampleSeed, int timeoutSeconds, int maxConcurrency,
            Set<Integer> allowedDepartureShifts,
            double explorationRateStart, double explorationRateEnd,
            long explorationSeed, int rollbackAfterDeclines,
            double rollbackScoreTolerance, int rollbackCooldownIterations,
            int modeFailureThreshold, int memoryWarmupIterations) {

        static ExperimentSettings fromEnvironment() {
            String provider = env("AGENTIC_PROVIDER",
                    Boolean.parseBoolean(env("DEEPSEEK_DRY_RUN", "true"))
                            ? "mock" : "openai-compatible").toLowerCase(Locale.ROOT);
            String key = firstNonBlank(
                    System.getenv("AGENTIC_API_KEY"),
                    System.getenv("DEEPSEEK_API_KEY"));
            if ("openai-compatible".equals(provider)
                    && (key == null || key.isBlank())) {
                throw new IllegalStateException(
                        "Set AGENTIC_API_KEY or use AGENTIC_PROVIDER=mock");
            }
            return new ExperimentSettings(
                    provider,
                    key,
                    env("AGENTIC_BASE_URL",
                            env("DEEPSEEK_BASE_URL", "https://api.deepseek.com")),
                    env("AGENTIC_MODEL",
                            env("DEEPSEEK_MODEL", "deepseek-chat")),
                    Long.parseLong(env("LLM_SAMPLE_SEED", "20260805")),
                    Math.max(5, Integer.parseInt(env("DEEPSEEK_TIMEOUT_SECONDS", "45"))),
                    Math.max(1, Integer.parseInt(env("LLM_MAX_CONCURRENCY", "5"))),
                    parseDepartureShifts(env(
                            "LLM_ALLOWED_DEPARTURE_SHIFTS", "-30,-15,0,15,30")),
                    probability(env("LLM_EXPLORATION_RATE_START", "0.20")),
                    probability(env("LLM_EXPLORATION_RATE_END", "0.02")),
                    Long.parseLong(env("LLM_EXPLORATION_SEED", "20260806")),
                    Math.max(1, Integer.parseInt(env(
                            "LLM_ROLLBACK_AFTER_DECLINES", "3"))),
                    Math.max(0.0, Double.parseDouble(env(
                            "LLM_ROLLBACK_SCORE_TOLERANCE", "0.05"))),
                    Math.max(1, Integer.parseInt(env(
                            "LLM_ROLLBACK_COOLDOWN_ITERATIONS", "5"))),
                    Math.max(1, Integer.parseInt(env(
                            "LLM_MODE_FAILURE_THRESHOLD", "2"))),
                    Math.max(0, Integer.parseInt(env(
                            "LLM_MEMORY_WARMUP_ITERATIONS", "3"))));
        }

        private static Set<Integer> parseDepartureShifts(String raw) {
            Set<Integer> shifts = new LinkedHashSet<>();
            for (String token : raw.split(",")) {
                int shift = Integer.parseInt(token.trim());
                if (shift < -60 || shift > 60) {
                    throw new IllegalArgumentException(
                            "Departure shift outside [-60,60]: " + shift);
                }
                shifts.add(shift);
            }
            if (shifts.isEmpty()) {
                throw new IllegalArgumentException(
                        "LLM_ALLOWED_DEPARTURE_SHIFTS must not be empty");
            }
            return shifts;
        }

        private static double probability(String raw) {
            return Math.max(0.0, Math.min(1.0, Double.parseDouble(raw)));
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static String firstNonBlank(String first, String second) {
            if (first != null && !first.isBlank()) {
                return first.trim();
            }
            return second == null || second.isBlank() ? null : second.trim();
        }
    }
}
