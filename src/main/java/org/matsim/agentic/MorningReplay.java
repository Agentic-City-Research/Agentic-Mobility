package org.matsim.agentic;

import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;

/** Same saved factual inputs, independent model calls; never runs QSim or updates personal memory. */
@Command(name = "morning-replay", mixinStandardHelpOptions = true)
public final class MorningReplay implements Callable<Integer> {
    @Option(names = "--input", required = true, description = "decision-contexts.jsonl.gz or context-samples.jsonl[.gz]") Path input;
    @Option(names = "--output", required = true) Path output;
    @Option(names = "--models", description = "JSON array of model definitions; credentials use apiKeyEnv, never literal keys") Path modelsFile;
    @Mixin MorningModelOptions options = new MorningModelOptions();
    @Option(names = "--orders", split = ",", defaultValue = "original,reverse") List<String> orders;
    @Option(names = "--order-seed", defaultValue = "0") long orderSeed;
    @Option(names = "--repetitions", defaultValue = "1") int repetitions;
    @Option(names = "--limit", defaultValue = "100") int limit;
    @Option(names = "--repair-attempts", defaultValue = "3") int outputAttempts;
    @Option(names = "--max-requests", defaultValue = "25000", description = "Global cap across all model/order/repeat calls including repairs; 0 unlimited") int maxRequests;
    @Option(names = "--timeout-seconds", defaultValue = "300") int timeout;
    @Option(names = "--mock", description = "Offline plumbing check only; not model evidence") boolean mock;
    BiFunction<AgenticConfig, LlmUsageTracker, LlmClient> clientFactory = DEFAULT_FACTORY;

    private record Model(String id, AgenticConfig config) {}
    private record Run(String id, String order, int repeat, LlmClient client, OutputWriter writer, LlmUsageTracker usage) {}

    public static void main(String[] args) { System.exit(new CommandLine(new MorningReplay()).execute(args)); }

    @Override public Integer call() throws Exception {
        if (limit < 1 || repetitions < 1 || outputAttempts < 1 || timeout < 1 || maxRequests < 0
                || orders.isEmpty() || orders.size() != new HashSet<>(orders).size()
                || !Set.of("original", "reverse", "shuffle").containsAll(orders))
            throw new IllegalArgumentException("Invalid replay parameters");
        if (Files.exists(output)) throw new IllegalArgumentException("Replay output already exists; choose a new directory");
        List<Model> models = models();
        if (models.stream().map(m -> m.config.reasonMaxChars).distinct().count() != 1)
            throw new IllegalArgumentException("All replay models must use the same reasonMaxChars to keep the system prompt identical");
        List<MorningRoutePolicy.Context> contexts = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        try (var reader = JsonlIO.newReader(input)) {
            String line;
            while (contexts.size() < limit && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                var c = Json.MAPPER.treeToValue(Json.readTree(line), MorningRoutePolicy.Context.class);
                MorningPromptRenderer.validate(c);
                if (!keys.add(c.day() + "/" + c.tripId())) throw new IllegalArgumentException("Duplicate day/trip in replay input");
                contexts.add(c);
            }
        }
        if (contexts.isEmpty()) throw new IllegalArgumentException("Replay input contains no contexts");
        long minimum = (long)contexts.size() * models.size() * orders.size() * repetitions;
        if (maxRequests > 0 && minimum > maxRequests) throw new IllegalArgumentException("Minimum replay calls exceed --max-requests");
        List<Run> runs = new ArrayList<>();
        long attempted = 0, failed = 0, modelDisagreements = 0, orderSensitive = 0;
        int contextsCompleted = 0;
        try (var root = new OutputWriter(output, true)) {
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("schemaVersion", 1); manifest.put("templateVersion", MorningPromptRenderer.VERSION);
            manifest.put("outputContractVersion", MorningOutputContract.VERSION);
            manifest.put("input", input.toAbsolutePath().toString());
            manifest.put("sourceContextsHash", MorningPromptRenderer.hash(Json.write(contexts)));
            manifest.put("contexts", contexts.size()); manifest.put("orders", orders); manifest.put("orderSeed", orderSeed);
            manifest.put("repetitions", repetitions); manifest.put("mock", mock); manifest.put("memoryUpdated", false);
            manifest.put("trafficSimulated", false); manifest.put("maxRequests", maxRequests);
            manifest.put("models", models.stream().map(m -> Map.of("id", m.id, "settings", m.config.publicModelSettings())).toList());
            root.manifest(manifest);
            for (var m : models) for (var order : orders) for (int repeat = 0; repeat < repetitions; repeat++) {
                var writer = new OutputWriter(output.resolve("models").resolve(m.id).resolve(order).resolve("repeat" + repeat), true);
                var usage = new LlmUsageTracker(0);
                LlmClient client = mock ? (role, system, user) -> new LlmResponse(mockDecision(user), 0, 0, 0, 0)
                        : clientFactory.apply(m.config, usage);
                runs.add(new Run(m.id, order, repeat, client, writer, usage));
            }
            try {
                for (var c : contexts) {
                    root.appendLine("source-contexts.jsonl", c);
                    List<Map<String, Object>> choices = new ArrayList<>();
                    for (var run : runs) {
                        var ordered = MorningPromptRenderer.order(c, run.order, orderSeed);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("day", c.day()); result.put("tripId", c.tripId()); result.put("modelId", run.id);
                        result.put("order", run.order); result.put("repeat", run.repeat);
                        result.put("sourceContextHash", MorningPromptRenderer.hash(Json.write(c)));
                        result.put("candidateOrder", ordered.candidates().stream().map(MorningRoutePolicy.Option::routeId).toList());
                        // The wrapper caps each invocation before dispatch, including output repairs.
                        long[] globalCalls = {attempted};
                        try {
                            LlmClient bounded = new LlmClient() {
                                @Override public LlmResponse complete(String role, String system, String user) {
                                    return complete(role, system, user, null);
                                }
                                @Override public LlmResponse complete(String role, String system, String user, Map<String, Object> schema) {
                                    if (maxRequests > 0 && globalCalls[0] >= maxRequests) throw new LlmException("Replay global request budget exhausted");
                                    globalCalls[0]++;
                                    return run.client.complete(role, system, user, schema);
                                }
                            };
                            // One adapter per context retains exactly the same renderer and output validation.
                            var policy = new MorningLlmPolicy(bounded, run.writer, outputAttempts, 0,
                                    models.getFirst().config.reasonMaxChars, settingsForRun(run, models));
                            var decision = policy.choose(ordered);
                            result.put("routeId", decision.routeId()); result.put("reason", decision.reason()); result.put("valid", true);
                            result.put("departureSeconds", decision.departureSeconds());
                        } catch (RuntimeException error) {
                            result.put("valid", false); result.put("error", error.toString()); failed++;
                        } finally { attempted = globalCalls[0]; }
                        choices.add(result); root.appendLine("results.jsonl", result);
                        run.writer.apiUsage(run.usage.toJson()); run.writer.flush();
                        root.progress(Map.of("contextsCompleted", contextsCompleted, "attemptedCalls", attempted, "failed", failed));
                        if (maxRequests > 0 && attempted >= maxRequests && choices.size() < runs.size())
                            throw new LlmException("Replay budget exhausted before all comparisons completed");
                    }
                    boolean betweenModels = false, betweenOrders = false;
                    for (int repeat = 0; repeat < repetitions; repeat++) {
                        final int r = repeat;
                        for (var order : orders) {
                            long distinct = choices.stream().filter(x -> Boolean.TRUE.equals(x.get("valid"))
                                    && x.get("repeat").equals(r) && x.get("order").equals(order)).map(x -> Arrays.asList(x.get("routeId"),x.get("departureSeconds"))).distinct().count();
                            betweenModels |= distinct > 1;
                        }
                        for (var m : models) {
                            long distinct = choices.stream().filter(x -> Boolean.TRUE.equals(x.get("valid"))
                                    && x.get("repeat").equals(r) && x.get("modelId").equals(m.id)).map(x -> Arrays.asList(x.get("routeId"),x.get("departureSeconds"))).distinct().count();
                            betweenOrders |= distinct > 1;
                        }
                    }
                    if (betweenModels) modelDisagreements++;
                    if (betweenOrders) orderSensitive++;
                    root.appendLine("comparisons.jsonl", Map.of("day", c.day(), "tripId", c.tripId(), "choices", choices,
                            "modelsDisagreeAtSameOrderAndRepeat", betweenModels, "choiceChangesAcrossDisplayOrders", betweenOrders));
                    contextsCompleted++;
                    root.flush();
                }
                root.status(Map.of("complete", failed == 0, "failedDecisions", failed, "attemptedCalls", attempted, "mock", mock,
                        "contexts", contexts.size(), "contextsWithModelDisagreement", modelDisagreements, "contextsWithOrderChange", orderSensitive));
                return failed == 0 ? 0 : 1;
            } catch (Exception error) {
                root.status(Map.of("complete", false, "attemptedCalls", attempted, "error", error.toString()));
                throw error;
            }
        } finally { for (var run : runs) run.writer.close(); }
    }

    private Map<String, Object> settingsForRun(Run run, List<Model> models) {
        return models.stream().filter(m -> m.id.equals(run.id)).findFirst().orElseThrow().config.publicModelSettings();
    }

    private List<Model> models() throws Exception {
        List<Model> result = new ArrayList<>();
        if (modelsFile == null) {
            var c = new AgenticConfig(); options.apply(c); c.timeoutSeconds = timeout;
            result.add(new Model("model", c));
        } else {
            var array = Json.readTree(Files.readString(modelsFile));
            if (!array.isArray() || array.isEmpty()) throw new IllegalArgumentException("--models must contain a nonempty JSON array");
            for (var node : array) {
                if (node.has("apiKey")) throw new IllegalArgumentException("Use apiKeyEnv instead of storing API keys");
                Set<String> supported = Set.of("id", "model", "baseUrl", "apiKeyEnv", "thinking", "temperature", "topP",
                        "generationSeed", "maxOutputTokens", "reasonMaxChars", "tokenLimitField", "responseFormat", "generationParams");
                node.fieldNames().forEachRemaining(k -> { if (!supported.contains(k)) throw new IllegalArgumentException("Unknown model field: " + k); });
                String id = node.path("id").asText();
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || id.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")
                        || result.stream().anyMatch(m -> m.id.equalsIgnoreCase(id)))
                    throw new IllegalArgumentException("Model ids must be unique safe directory names");
                var c = new AgenticConfig();
                c.model = node.path("model").asText(c.model); c.baseUrl = node.path("baseUrl").asText(c.baseUrl);
                c.thinking = node.path("thinking").asText("auto");
                c.temperature = node.hasNonNull("temperature") ? node.get("temperature").asDouble() : null;
                c.topP = node.hasNonNull("topP") ? node.get("topP").asDouble() : null;
                c.generationSeed = node.hasNonNull("generationSeed") ? node.get("generationSeed").asInt() : null;
                c.maxOutputTokens = node.path("maxOutputTokens").asInt(c.maxOutputTokens);
                c.reasonMaxChars = node.path("reasonMaxChars").asInt(c.reasonMaxChars);
                c.tokenLimitField = node.path("tokenLimitField").asText(c.tokenLimitField);
                c.responseFormat = node.path("responseFormat").asText("omit");
                c.generationParams = node.has("generationParams") ? node.get("generationParams").toString() : "{}";
                c.apiKey = System.getenv(node.path("apiKeyEnv").asText("AGENTIC_API_KEY"));
                options.apply(c); c.timeoutSeconds = timeout; result.add(new Model(id, c));
            }
        }
        for (var m : result) {
            m.config.generationSettings();
            if (!mock && clientFactory == DEFAULT_FACTORY && (m.config.apiKey == null || m.config.apiKey.isBlank()))
                throw new IllegalArgumentException("Set the model's API-key environment variable");
        }
        return result;
    }
    private static final BiFunction<AgenticConfig, LlmUsageTracker, LlmClient> DEFAULT_FACTORY = DeepSeekClient::new;
    private static String mockDecision(String user) {
        var result = new LinkedHashMap<String,Object>();
        result.put("routeId", firstRoute(user)); result.put("reason", "Offline first-listed mock, not model evidence");
        var grid = java.util.regex.Pattern.compile("Allowed departureSeconds: (\\[[^\\n]+?\\])").matcher(user);
        if (grid.find()) result.put("departureSeconds", Json.readTree(grid.group(1)).get(0).asDouble());
        return Json.write(result);
    }

    private static String firstRoute(String user) {
        int section = user.indexOf("TRAFFIC INFORMATION SERVICE: AVAILABLE ROUTES");
        var match = java.util.regex.Pattern.compile("(?m)^- Route ([A-Za-z0-9_-]+): predicted driving time")
                .matcher(user.substring(Math.max(0, section)));
        if (!match.find()) throw new IllegalArgumentException("No displayed candidate in replay prompt");
        return match.group(1);
    }
}
