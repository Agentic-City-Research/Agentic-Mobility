package org.matsim.agentic;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Post-QSim reflection; returns a proposal, never mutates trip state inside a worker. */
final class MorningMemoryUpdater {
    static final String VERSION = "morning-reflection-v10-period-window";
    static final int RECENT_EXPERIENCE_LIMIT = 5;
    static final int TARGET_CHARACTERS = 240;
    static final int MAX_CHARACTERS = 500;
    static final String SYSTEM = MorningPromptRenderer.FACT_RULES + """
            You are the individual traveler reflecting on a trip.
            Update the supplied previous first-person memory using the last three days of actual executed experiences, including today.
            The previous summary carries longer-term experience and habits across days. Retain useful earlier lessons, revise judgments
            contradicted by visible facts, and integrate new experience within the same length limit. Do not restart memory from zero
            or merely retell the three-day log. Only the first reflection begins with empty memory; no pre-experiment history is assumed.
            Consolidate repeated observations; do not append a daily diary. Unverifiable older experience is not automatically false.
            All lasting information, including remembered charging roads and payments, shares this one summary budget.
            Remember the implications of selected/actual departures and paid road entries in your own words; exact tariff reconstruction or a permanent receipt list is not required.
            Use one paragraph of 3–4 short sentences, targeting no more than 240 characters. Much shorter notes are acceptable, especially during the first few days.
            Do not repeat your fixed profile, schedule or the full recent trip log.
            Do not force a preference for familiar routes, a switching rule, an exploration rate or a numerical habit weight.
            You may preserve earlier important events mentioned in your old notes, even when outside the recent factual window.
            Your profile, schedules and route pool remain fixed. Untried routes have no personal outcome; slow or late trips do not create new routes.
            No latest policy or network traffic is supplied here. The last experience is today's actual result, not a traffic-service forecast.
            Do not propose or execute a route choice here. Return exactly one JSON object with only a nonempty memory string:
            {"memory":"<your updated personal notes>"}
            Do not include Markdown, text outside JSON, or unnecessary formatting whitespace. Finish the JSON object.
            """;
    record Context(int day, String tripId, String personId, String destinationPurpose,
                   Double latestArrivalSeconds, Map<String, Object> personAttributes, Profile profile,
                   String previousMemory, Integer previousMemoryDay,
                   List<MorningMemory.Experience> recentExperiences, List<String> availableRouteIds,
                   String informationGroup, List<MorningRoutePolicy.TollObservation> personalPayments, MorningFactLedger.View ledger) {
        public Context(int day, String tripId, String personId, String destinationPurpose, Double latestArrivalSeconds, Map<String, Object> personAttributes, Profile profile, String previousMemory, Integer previousMemoryDay, List<MorningMemory.Experience> recentExperiences, List<String> availableRouteIds, String informationGroup, List<MorningRoutePolicy.TollObservation> personalPayments) {
            this(day, tripId, personId, destinationPurpose, latestArrivalSeconds, personAttributes, profile, previousMemory, previousMemoryDay, recentExperiences, availableRouteIds, informationGroup, personalPayments, null);
        }
        public Context(int day, String tripId, String personId, String destinationPurpose,
                       Double latestArrivalSeconds, Map<String, Object> personAttributes, Profile profile,
                       String previousMemory, Integer previousMemoryDay,
                       List<MorningMemory.Experience> recentExperiences, List<String> availableRouteIds) {
            this(day, tripId, personId, destinationPurpose, latestArrivalSeconds, personAttributes, profile,
                    previousMemory, previousMemoryDay, recentExperiences, availableRouteIds, null, List.of());
        }
    }
    record Update(String memory,int repairCount,double elapsedMillis,String semanticReviewStatus) {
        Update(String memory,int repairCount,double elapsedMillis) { this(memory,repairCount,elapsedMillis,"disabled"); }
    }

    private final LlmClient client;
    private final OutputWriter output;
    private final int attempts, maxChars;
    private final Map<String, Object> modelSettings;
    private final MorningRequestBudget budget;
    private MorningSemanticReview semanticReview;
    private String memoryVariant = "original";
    private int reflectionInterval = 5;
    void setMemoryVariant(String variant) { memoryVariant = variant; }
    void setReflectionInterval(int interval) { reflectionInterval = interval; }
    private final AtomicLong calls = new AtomicLong();

    MorningMemoryUpdater(LlmClient client, OutputWriter output, int attempts, int maxChars,
                         Map<String, Object> modelSettings, MorningRequestBudget budget) {
        if (attempts < 1 || maxChars < 1 || maxChars > MAX_CHARACTERS)
            throw new IllegalArgumentException("Memory limit must be between 1 and " + MAX_CHARACTERS + " Unicode characters; attempts must be positive");
        this.client = client; this.output = output; this.attempts = attempts; this.maxChars = maxChars;
        this.modelSettings = Map.copyOf(modelSettings); this.budget = budget;
    }
    long attemptedCalls() { return calls.get(); }
    void setSemanticReview(MorningSemanticReview review) { semanticReview=review; }
    Map<String, Object> modelSettings() {
        return MorningOutputContract.requestSettings(modelSettings, MorningOutputContract.memory(maxChars));
    }

    static String render(Context c) {
        return render(c, RECENT_EXPERIENCE_LIMIT);
    }
    static String render(Context c, int window) {
        validate(c, window);
        var s = new StringBuilder();
        s.append("AVAILABLE ROUTE IDENTIFIERS FOR THIS TRIP: ").append(Json.write(c.availableRouteIds())).append(".\n");
        if (c.availableRouteIds().size() == 1) s.append("Only one route is available; do not plan to switch to an invented alternative.\n");
        MorningPromptRenderer.appendPersonalInformation(s, c.personAttributes(), c.profile(), c.destinationPurpose());
        if (c.latestArrivalSeconds() != null)
            s.append("The fixed required arrival time is ").append(c.latestArrivalSeconds()).append(" seconds after midnight; it is unchanged.\n");
        else s.append("No arrival deadline is supplied for this destination.\n");
        s.append("REFLECT AFTER SIMULATION DAY ").append(c.day()).append('\n');
        s.append("Recent factual window includes today: simulation days ").append(Math.max(0,c.day()-window+1))
                .append(" through ").append(c.day()).append(". The previous summary is continuous personal memory, not part of this window.\n");
        s.append("YOUR PREVIOUS SELF-WRITTEN MEMORY\n");
        if (c.previousMemory().isBlank()) s.append("Empty; no pre-experiment experiences are assumed.\n");
        else s.append("Last updated after simulation day ").append(c.previousMemoryDay()).append(".\n").append(c.previousMemory()).append('\n');
        MorningPromptRenderer.appendExperiences(s,c.recentExperiences(),"RECENT ACTUAL EXPERIENCES INCLUDING TODAY, OLDEST FIRST");
        if (c.personalPayments() != null && !c.personalPayments().isEmpty()) {
            s.append("YOUR OWN ACTUAL PAID ROAD ENTRIES, INCLUDING OTHER TRIPS\ncolumns=[day,linkId,entry_s,amount]\n");
            c.personalPayments().forEach(e -> MorningPromptRenderer.appendPayment(s, e));
        }
        MorningFactLedger.append(s, c.ledger(), c.day());
        s.append("Update your notes for future days.\n");
        return s.toString();
    }

    static void validate(Context c) {
        validate(c, RECENT_EXPERIENCE_LIMIT);
    }
    static void validate(Context c, int window) {
        if (c.day() < 0 || c.previousMemory() == null || c.recentExperiences() == null || c.recentExperiences().isEmpty()
                || c.recentExperiences().size() > window
                || c.latestArrivalSeconds() != null && (!Double.isFinite(c.latestArrivalSeconds()) || c.latestArrivalSeconds() < 0)
                || c.availableRouteIds() == null || c.availableRouteIds().isEmpty()
                || c.availableRouteIds().size() > 4 || new HashSet<>(c.availableRouteIds()).size() != c.availableRouteIds().size()
                || c.availableRouteIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
                || c.previousMemory().isBlank() != (c.previousMemoryDay() == null)
                || c.previousMemoryDay() != null && (c.previousMemoryDay() < 0 || c.previousMemoryDay() >= c.day()))
            throw new IllegalArgumentException("Invalid post-simulation memory context");
        MorningFactLedger.validate(c.ledger(), c.day());
        int last = -1;
        for (var e : c.recentExperiences()) {
            if (e.day() <= last || e.day() > c.day() || e.day()<Math.max(0,c.day()-window+1) || !c.availableRouteIds().contains(e.routeId()))
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

    static String systemForInterval(int interval, int maxChars) {
        return (SYSTEM + "Keep memory within " + maxChars + " Unicode characters, including spaces and punctuation.\n")
                .replace("last three days", interval == 1 ? "last day" : "last " + interval + " days")
                .replace("three-day log", interval + "-day log");
    }

    Update update(Context context) {
        int window = reflectionInterval;
        String base = render(context, window), system = systemForInterval(reflectionInterval, maxChars);
        if (!memoryVariant.equals("original")) system += " Count each simulation day only once; the old summary and recent records can overlap. Ledger facts, when present, take precedence over notes.\n";
        if (context.ledger() != null) system = system.replace("All lasting information, including remembered charging roads and payments, shares this one summary budget.", "Personal notes share one summary budget; the supplied ledger separately records past trip outcomes.");
        var schema = MorningOutputContract.memory(maxChars);
        var requestSettings = MorningOutputContract.requestSettings(modelSettings, schema);
        var knownRouteIds = new LinkedHashSet<>(context.availableRouteIds());
        String error = null; long started = System.nanoTime();
        String contextHash = MorningPromptRenderer.hash(Json.write(context));
        output.appendLine("memory-contexts.jsonl", context);
        if (context.ledger() != null) output.appendLine("ledger-views.jsonl", Map.of("day",context.day(),"personId",context.personId(),"tripId",context.tripId(),"phase","reflection","view",context.ledger(),"viewHash",MorningPromptRenderer.hash(Json.write(context.ledger()))));
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
            log.put("usage", MorningOutputContract.usage(response));
            String text = null;
            try {
                MorningOutputContract.requireNotTruncated(response);
                var raw=MorningSemanticReview.strict(response.content());
                if(raw!=null && raw.path("memory").isTextual()) log.put("memoryCharacters",raw.get("memory").asText().codePointCount(0,raw.get("memory").asText().length()));
                text=parseMemory(response.content(),maxChars);
                log.put("memoryCharacters",text.codePointCount(0,text.length()));
                var unknown = MorningOutputContract.unknownRouteReferences(text, knownRouteIds);
                log.put("unknownRouteReferences", unknown);
                // Preserve raw notes in this attempt log. Optional one-shot review has
                // its own audit; these route-reference diagnostics do not trigger repairs.
                error = null;
            } catch (java.io.IOException | IllegalArgumentException invalid) { error = invalid.getMessage(); }
            log.put("error", error); log.put("valid", error == null); log.put("failureKind", error == null ? null : "output");
            output.appendLine("memory-attempts.jsonl", log);
            if (error == null) {
                String reviewStatus="disabled";
                if(semanticReview!=null) {
                    String facts=base.substring(0,base.indexOf("REFLECT AFTER SIMULATION DAY"))
                            +base.substring(base.indexOf("RECENT ACTUAL EXPERIENCES INCLUDING TODAY"));
                    var reviewed=semanticReview.review(context.day(),context.tripId(),context.personId(),"memory",response.content(),facts,
                            context.previousMemory(),schema,"memory",node -> parseMemory(Json.write(node),maxChars));
                    text=parseMemory(Json.write(reviewed.output()),maxChars); reviewStatus=reviewed.status();
                }
                return new Update(text,attempt-1,elapsed(started),reviewStatus);
            }
        }
        output.flush();
        throw new LlmException("Morning memory for " + context.tripId() + " exhausted " + attempts
                + " output attempts on day " + context.day() + ": " + error);
    }

    static String parseMemory(String content,int maxChars) {
        com.fasterxml.jackson.databind.JsonNode node;
        try { node=MorningSemanticReview.strict(content); }
        catch(java.io.IOException invalid) { throw new IllegalArgumentException("Memory must be one complete JSON object without duplicate keys, prose or code fences"); }
        if(node==null || !node.isObject() || node.size()!=1 || !node.path("memory").isTextual() || node.get("memory").asText().isBlank())
            throw new IllegalArgumentException("Only one nonempty memory string is allowed");
        String text=node.get("memory").asText();
        if(text.codePointCount(0,text.length())>maxChars) throw new IllegalArgumentException("Memory exceeds "+maxChars+" characters");
        return text;
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
}
