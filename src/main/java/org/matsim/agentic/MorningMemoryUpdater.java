package org.matsim.agentic;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Post-QSim reflection; returns a proposal, never mutates trip state inside a worker. */
final class MorningMemoryUpdater {
    static final String VERSION = "morning-reflection-v7-joint-bounded-tolls";
    static final int RECENT_EXPERIENCE_LIMIT = 5;
    static final int TARGET_CHARACTERS = 240;
    static final int MAX_CHARACTERS = 500;
    static final String SYSTEM = """
            You are the individual traveler reflecting on a trip.
            Update your own first-person long-term memory from your previous notes and the supplied actual experiences.
            Decide what to retain, revise or forget: familiarity, important events, uncertainty and your current interpretations.
            Update your previous memory into compact first-person notes.
            Rewrite and consolidate it; do not append a daily diary.
            All lasting information, including remembered charging roads and payments, shares this one summary budget.
            Recent factual experiences may contain selected and actual departure times and actual paid road entries.
            Remember their implications in your own words; exact tariff reconstruction or a permanent receipt list is not required.
            
            Use one paragraph of 3–4 short sentences, targeting no more than
            240 characters. A much shorter memory is acceptable, especially
            during the first few days.
            
            Describe what you now remember, your familiarity,
            revised beliefs and remaining uncertainty.
            Merge repeated observations and remove redundant explanations.
            Do not repeat your fixed profile, schedule or the full recent trip log.
            Do not force a preference for familiar routes, a switching rule, an exploration rate or a numerical habit weight.
            You may preserve earlier important events mentioned in your old notes, even when outside the recent factual window.
            Use the supplied inputs to form your own interpretation. Your fixed profile and schedules do not change.
            Do not propose or execute a route choice here. Return exactly one JSON object with only a nonempty memory string:
            {"memory":"<your updated personal notes>"}
            """;
    record Context(int day, String tripId, String personId, String destinationPurpose,
                   Double latestArrivalSeconds, Map<String, Object> personAttributes, Profile profile,
                   String previousMemory, Integer previousMemoryDay,
                   List<MorningMemory.Experience> recentExperiences, List<String> availableRouteIds,
                   String informationGroup, List<MorningRoutePolicy.TollObservation> personalPayments) {
        public Context(int day, String tripId, String personId, String destinationPurpose,
                       Double latestArrivalSeconds, Map<String, Object> personAttributes, Profile profile,
                       String previousMemory, Integer previousMemoryDay,
                       List<MorningMemory.Experience> recentExperiences, List<String> availableRouteIds) {
            this(day, tripId, personId, destinationPurpose, latestArrivalSeconds, personAttributes, profile,
                    previousMemory, previousMemoryDay, recentExperiences, availableRouteIds, null, List.of());
        }
    }
    record Update(String memory, int repairCount, double elapsedMillis) {}

    private final LlmClient client;
    private final OutputWriter output;
    private final int attempts, maxChars;
    private final Map<String, Object> modelSettings;
    private final MorningRequestBudget budget;
    private final AtomicLong calls = new AtomicLong();

    MorningMemoryUpdater(LlmClient client, OutputWriter output, int attempts, int maxChars,
                         Map<String, Object> modelSettings, MorningRequestBudget budget) {
        if (attempts < 1 || maxChars < 1 || maxChars > MAX_CHARACTERS)
            throw new IllegalArgumentException("Memory limit must be between 1 and " + MAX_CHARACTERS + " Unicode characters; attempts must be positive");
        this.client = client; this.output = output; this.attempts = attempts; this.maxChars = maxChars;
        this.modelSettings = Map.copyOf(modelSettings); this.budget = budget;
    }
    long attemptedCalls() { return calls.get(); }
    Map<String, Object> modelSettings() {
        return MorningOutputContract.requestSettings(modelSettings, MorningOutputContract.memory(maxChars));
    }

    static String render(Context c) {
        validate(c);
        var s = new StringBuilder("REFLECT AFTER SIMULATION DAY ").append(c.day()).append("\n");
        s.append("AVAILABLE ROUTE IDENTIFIERS FOR THIS TRIP: ").append(Json.write(c.availableRouteIds())).append(".\n")
                .append("This fixed route pool does not expand because a trip was slow or late. Untried alternatives have no personal outcome.\n");
        if (c.availableRouteIds().size() == 1) s.append("Only one route is available; do not plan to switch to an invented alternative.\n");
        MorningPromptRenderer.appendPersonalInformation(s, c.personAttributes(), c.profile(), c.destinationPurpose());
        if (c.latestArrivalSeconds() != null)
            s.append("The fixed required arrival time is ").append(c.latestArrivalSeconds()).append(" seconds after midnight; it is unchanged.\n");
        else s.append("No arrival deadline is supplied for this destination.\n");
        s.append("YOUR PREVIOUS SELF-WRITTEN MEMORY\n");
        if (c.previousMemory().isBlank()) s.append("Empty; no pre-experiment experiences are assumed.\n");
        else s.append("Last updated after simulation day ").append(c.previousMemoryDay()).append(".\n").append(c.previousMemory()).append('\n');
        s.append("RECENT ACTUAL EXPERIENCES INCLUDING TODAY, OLDEST FIRST\n");
        c.recentExperiences().forEach(e -> MorningPromptRenderer.appendExperience(s, e));
        if (c.personalPayments() != null && !c.personalPayments().isEmpty()) {
            s.append("YOUR OWN ACTUAL PAID ROAD ENTRIES, INCLUDING OTHER TRIPS\n");
            c.personalPayments().forEach(e -> MorningPromptRenderer.appendPayment(s, e));
        }
        s.append("Paid amounts are past observations at those entry times, not current tariffs or evidence that other roads are free. No latest policy or network traffic is supplied here.\n");
        s.append("The last experience above is today's observed result, not a traffic-service forecast. Update your notes for future days.\n");
        return s.toString();
    }

    static void validate(Context c) {
        if (c.day() < 0 || c.previousMemory() == null || c.recentExperiences() == null || c.recentExperiences().isEmpty()
                || c.recentExperiences().size() > RECENT_EXPERIENCE_LIMIT
                || c.latestArrivalSeconds() != null && (!Double.isFinite(c.latestArrivalSeconds()) || c.latestArrivalSeconds() < 0)
                || c.availableRouteIds() == null || c.availableRouteIds().isEmpty()
                || c.availableRouteIds().size() > 4 || new HashSet<>(c.availableRouteIds()).size() != c.availableRouteIds().size()
                || c.availableRouteIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
                || c.previousMemory().isBlank() != (c.previousMemoryDay() == null)
                || c.previousMemoryDay() != null && (c.previousMemoryDay() < 0 || c.previousMemoryDay() >= c.day()))
            throw new IllegalArgumentException("Invalid post-simulation memory context");
        int last = -1;
        for (var e : c.recentExperiences()) {
            if (e.day() <= last || e.day() > c.day() || !c.availableRouteIds().contains(e.routeId()))
                throw new IllegalArgumentException("Unordered/future memory experience or unknown route");
            last = e.day();
            for (Double t : Arrays.asList(e.carSeconds(), e.arrivalSeconds(), e.lateSeconds(), e.departureSeconds(), e.doorSeconds()))
                if (t != null && (!Double.isFinite(t) || t < 0)) throw new IllegalArgumentException("Invalid actual memory time");
            if (e.tollPaid() != null && (!Double.isFinite(e.tollPaid()) || e.tollPaid() < 0))
                throw new IllegalArgumentException("Invalid actual toll payment in memory");
        }
        if (last != c.day()) throw new IllegalArgumentException("Today's actual result is required before reflection");
        if (c.personalPayments() != null) for (var payment : c.personalPayments())
            MorningPromptRenderer.validatePayment(payment, c.day() + 1);
    }

    Update update(Context context) {
        String base = render(context), system = SYSTEM + "Keep memory within " + maxChars + " characters.\n";
        var schema = MorningOutputContract.memory(maxChars);
        var requestSettings = MorningOutputContract.requestSettings(modelSettings, schema);
        var knownRouteIds = new LinkedHashSet<>(context.availableRouteIds());
        String error = null; long started = System.nanoTime();
        String contextHash = MorningPromptRenderer.hash(Json.write(context));
        output.appendLine("memory-contexts.jsonl", context);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            budget.reserve(); calls.incrementAndGet();
            String user = base + (error == null ? "" : "\nYour previous output was invalid: " + error
                    + ". Consolidate shorter notes, at most " + maxChars + " characters."
                    + " Return only a complete memory JSON object; all factual inputs remain unchanged.");
            var log = new LinkedHashMap<String, Object>();
            log.put("day", context.day()); log.put("tripId", context.tripId()); log.put("personId", context.personId());
            log.put("attempt", attempt); log.put("repairCount", attempt - 1); log.put("templateVersion", VERSION);
            log.put("contextHash", contextHash); log.put("system", system); log.put("user", user);
            log.put("targetCharacters", TARGET_CHARACTERS); log.put("maxCharacters", maxChars);
            log.put("modelSettings", requestSettings); log.put("outputContractVersion", MorningOutputContract.VERSION);
            log.put("outputSchema", schema); log.put("availableRouteIds", context.availableRouteIds());
            long callStart = System.nanoTime(); LlmResponse response;
            try { response = client.complete("traveler_memory", system, user, schema); }
            catch (RuntimeException failure) {
                log.put("elapsedMillis", elapsed(callStart)); log.put("error", failure.toString());
                log.put("response", null); log.put("usage", null); log.put("valid", false); log.put("failureKind", "call");
                output.appendLine("memory-attempts.jsonl", log); output.flush(); throw failure;
            }
            log.put("elapsedMillis", elapsed(callStart)); log.put("response", response.content());
            MorningOutputContract.responseMetadata(log, response);
            log.put("usageReported", response.usageReported());
            log.put("usage", response.usageReported() ? Map.of("promptTokens", response.promptTokens(), "completionTokens", response.completionTokens(),
                    "promptCacheHitTokens", response.promptCacheHitTokens(), "promptCacheMissTokens", response.promptCacheMissTokens()) : null);
            String text = null;
            try {
                MorningOutputContract.requireNotTruncated(response);
                var node = Json.MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(response.content());
                if (node == null || !node.isObject() || node.size() != 1 || !node.has("memory") || !node.get("memory").isTextual()
                        || node.get("memory").asText().isBlank()) throw new IllegalArgumentException("Only one nonempty memory string is allowed");
                text = node.get("memory").asText();
                int characters = text.codePointCount(0, text.length());
                log.put("memoryCharacters", characters);
                if (characters > maxChars) throw new IllegalArgumentException("Memory exceeds " + maxChars + " characters");
                var unknown = MorningOutputContract.unknownRouteReferences(text, knownRouteIds);
                log.put("unknownRouteReferences", unknown);
                // Retain model-generated memory distortions verbatim. References are
                // diagnostics only; executable route choices are validated separately.
                error = null;
            } catch (java.io.IOException | IllegalArgumentException invalid) { error = invalid.getMessage(); }
            log.put("error", error); log.put("valid", error == null); log.put("failureKind", error == null ? null : "output");
            output.appendLine("memory-attempts.jsonl", log);
            if (error == null) return new Update(text, attempt - 1, elapsed(started));
        }
        output.flush();
        throw new LlmException("Morning memory for " + context.tripId() + " exhausted " + attempts
                + " output attempts on day " + context.day() + ": " + error);
    }

    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
}
