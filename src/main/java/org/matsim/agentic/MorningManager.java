package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** The manager sees an explicit public-traffic projection, never the cohort, profiles or traveler memory. */
final class MorningManager {
    static final String VERSION = "morning-pricing-v6-basic";
    static final String FEEDBACK_MODE = "combined";
    static final int FREE_DAYS = 20, UPDATE_INTERVAL = 30, MEMORY_MAX_CHARS = 2000;
    static final int MEMORY_MAX_WORDS = 1000;
    static final String BASIC_SYSTEM = """
            You manage congestion pricing for this car network. Your single objective is to reduce total network congestion delay.
            World model: fixed OD demand uses a capacity-constrained road network. Queues can propagate upstream.
            Route and permitted departure-time choices redistribute demand across roads and times; effects may be delayed and uncertain.
            A link's raw capacity and sampled simulation flow capacity differ; read publicEnvironment for the actual environment.
            Total delay and travel seconds measure completed full-link traversals, not door-to-door journeys. Aborted, unfinished,
            partial traversals and aggregate car-leg completions are reported separately; missing completions cannot establish improvement.
            Traversals are not persons. Your observations contain only completed past simulation days, with the executed policy identified.
            Compare policyFeedback and trackedChargingWindows with your notes; period changes are descriptive, not matched causal effects.
            Travelers learn from their own executed outcomes. You cannot read individual travel information, payments, profiles or memory.
            Actions: return the complete policy for today, choosing hold, test, adjust or remove. You decide when to wait, where and when
            to charge, prices, and when to withdraw. An empty policy is allowed. No price ladder, minimum positive price or prescribed search order.
            Prices are finite nonnegative scenario monetary units, not calibrated real currency. At most 40 rules; existing car links only.
            Rule windows are within-day seconds 0 <= start < end <= simulationEndSeconds, aligned to the 900-second observation bins.
            Rules on the same link cannot overlap. Each actual car-link entry is charged once, including origin partial-link entries;
            repeated traversals incur new charges. Windows are [start,end); actual billing uses executed entry times.
            The policy repeats until another accepted manager update. Read executionSchedule for available decision dates and remaining days.
            There is no automatic withdrawal or performance-triggered policy change. Invalid output leaves the previous policy in force.
            hold preserves the actual price function; test/adjust change it; remove reduces or withdraws prices without increases.
            Interpret effects with uncertainty and revise your understanding from executed evidence. No weight learning is performed.
            Return exactly one JSON object with rules, decision and memory. rules is an array (maximum 40) of objects containing only
            link_id (string), start (number), end (number), amount (number).
            decision contains only action (hold|test|adjust|remove), evidenceBasis (untested|observational|observedTrial),
            hypothesis, expectedNetworkEffect, uncertainty, evaluationPlan and revisitCondition; explanations are nonempty strings,
            at most 400 Unicode characters each. Keep review plans consistent with the scheduled decision opportunities.
            memory is your complete replacement management memory in English, at most 1000 words, including retained unfinished ideas
            and revised interpretations. Separate measured facts from hypotheses. Do not merely append to the old memory.
            """;
    static final String SYSTEM = """
            You manage congestion pricing for the supplied car network. Reduce total network congestion delay.
            Consider aborted and unfinished traffic as well as completed-traversal delay; missing completions are not successful delay reduction.
            Choose car links, charging windows and amounts subject to pricingControls. There is no predefined link list or price ladder.
            Use small, interpretable trials: one corridor and one entry link per corridor by default. Never stack subdivision tolls.
            Corridor cost and upward price steps have routing-minute caps. Full withdrawal and price reductions are permitted.
            Align charging windows to the hourly measurement bins. Change one aspect of a trial at a time where possible.
            Read chargingSuitability: actual link-OD reachability is aggregated from reference car plans including background traffic.
            Prefer avoidable entry links; high unavoidable reference-route shares are rejected by the declared limit.
            These full-day reference-plan counts are not actual peak payers and do not certify spare capacity or useful diversion.
            You may return no charging rules. Multiple non-overlapping windows on a link are allowed.
            Traffic observations are feedback, not restrictions on which links you may price.
            Legal rules require an existing car link, finite seconds 0 <= start < end <= simulationEndSeconds,
            and a finite nonnegative amount. Windows on the same link must not overlap.
            Amounts are in scenario monetary units, not an asserted real-world currency, and are not rounded to price levels.
            Each actual car-link entry is charged once, including entry into traffic on an origin partial link.
            A repeated traversal is charged again. Duplicate notifications of one entry are not charged twice.
            Windows are left-closed and right-open. Read executionSchedule for the actual update interval and remaining run days.
            This complete policy takes effect today until another accepted policy or deterministic daily safety withdrawal.
            Failed model updates retain the current policy, but do not disable daily safety reviews.
            Rule start/end are seconds WITHIN EACH simulated day, not a policy lifetime in simulation days.
            Travelers can respond to public prices and their own payments; capacity, demand, OD and modes are not policy actions.
            Read publicEnvironment for actual population/capacity scaling, routing money/time units and enabled traveler responses.
            Raw network capacity is not sampled QSim flow capacity. Do not infer active demand reduction, mode switching or deliberate
            departure-time changes when those traveler choices are disabled. Actual timing may still be delayed by congestion.
            With fixed OD demand, route redistribution is an available hypothesis for reducing network delay when route choice is enabled.
            It can help or hurt; evaluate it empirically rather than equating fixed demand with no possible pricing benefit.
            Read roadCorridors: subdivision links on a road without intermediate car-choice junctions accumulate entry charges.
            Cumulative cost intervals are conditional on all priced entries occurring within that interval, not actual personal payments.
            Your inputs combine publicEnvironment, roadCorridors, aggregate traffic, the current policy, structured policyFeedback and your own management notes.
            network and linkTrafficTotals are compact tables: each row follows networkColumns or linkTrafficTotalsColumns respectively.
            Every car link is retained; table encoding removes repeated field names, not links or measurements.
            Use policyFeedback's machine-computed factual observations to assess your interventions independently of your notes.
            Read pricingSpatial and publicCarTrips in each observed period: distinguish total delay, mean delay per traversal,
            priced charging windows, outside-window delay, other roads and completion among all started car legs.
            Fewer entries or less revenue alone is not success. Improvement on priced roads can be outweighed by other-road delay.
            managerMemoryMetadata identifies retained older notes after a failed memory repair; previousDecision still records the latest accepted decision.
            Period changes and the early free-period reference are descriptive, not matched no-pricing counterfactuals; native learning can improve
            traffic without tolls. More link traversals need not mean more OD demand. Consider full-link travel time as well as delay and failures.
            Read routeAlternatives for public same-direction, same-bin path diagnostics. They are illustrative, not charging candidates,
            measured OD routes, proven spare capacity or predicted causal outcomes. Missing observations use explicitly labelled free-flow estimates.
            Choose to hold, test, adjust or remove prices. An untried policy is not a policy shown to be ineffective.
            Missing matched counterfactuals limits causal claims; it does not prohibit a policy experiment or require external approval.
            A test needs a hypothesis, expected redistribution, uncertainty and an executable review plan, not proof of benefit before action.
            You may retain zero prices. Distinguish an unexplored choice from a judgment based on observed priced trials.
            Do not require traversal counts to fall as a success criterion. Total network delay is the primary objective;
            assess full-link travel time and failures alongside it. These inputs do not measure complete OD journey time or personal payments.
            Payment is a routing mechanism; do not silently replace the congestion objective with minimizing payments.
            Read safetyMonitoring and executionSchedule. Daily automatic reviews can withdraw all tolls on the next iteration without
            calling you; the cooldown can skip a scheduled model update. Only promise model reviews on declared scheduled dates.
            Daily safety uses recent pre-change observations and noise tolerances, not a matched causal counterfactual.
            Seven-day tails do not certify adaptation. Old native plan scores can delay adaptation.
            action=hold must preserve the price function; test/adjust must change it; remove must actually reduce prices without any increase.
            executionDiff is calculated and validated by the program. Describe the rules you actually return.
            You do not have access to any traveler's private memory, individual payment history or personal profile.
            Keep memory around 800-1200 Unicode characters; hard maximum 2000. Record new judgments and unfinished tests,
            not repeated public parameters or bottleneck lists. Distinguish interpretations from observed facts.
            Return exactly one JSON object with rules, decision and memory. rules is an array of objects containing only
            link_id (string), start (number), end (number), amount (number); an empty array is allowed.
            decision contains only action (hold|test|adjust|remove), evidenceBasis (untested|observational|observedTrial),
            hypothesis, expectedNetworkEffect, uncertainty, evaluationPlan and revisitCondition.
            Each decision explanation is a nonempty string of at most 400 Unicode characters. memory is a string.
            """;
    record Result(TollPolicy policy, boolean accepted, int repairCount) {}
    private final Network network;
    private final double horizon;
    private final LlmClient client;
    private final OutputWriter output;
    private final MorningRequestBudget budget;
    private final int attempts;
    private final Map<String, Object> modelSettings;
    private final MorningManagerEnvironment environment;
    private final MorningManagerFeedback feedback;
    private final int updateInterval, lastIteration;
    private final MorningManagerControls controls;
    private final MorningManagerSafety safety;
    private final List<Map<String, Object>> window = new ArrayList<>();
    private String memory = "";
    private String memoryStatus = "initial";
    private Integer memoryWrittenAtDay;
    private Map<String, String> lastDecision = Map.of();
    private final AtomicLong attemptedCalls = new AtomicLong();

    MorningManager(Network network, double horizon, MorningManagerEnvironment environment,
                   LlmClient client, OutputWriter output, MorningRequestBudget budget,
                   int attempts, Map<String, Object> modelSettings) {
        this(network, horizon, environment, client, output, budget, attempts, modelSettings, UPDATE_INTERVAL, Integer.MAX_VALUE);
    }

    MorningManager(Network network, double horizon, MorningManagerEnvironment environment,
                   LlmClient client, OutputWriter output, MorningRequestBudget budget,
                   int attempts, Map<String, Object> modelSettings, int updateInterval, int lastIteration) {
        this(network,horizon,environment,client,output,budget,attempts,modelSettings,updateInterval,lastIteration,new MorningManagerControls());
    }
    MorningManager(Network network, double horizon, MorningManagerEnvironment environment,
                   LlmClient client, OutputWriter output, MorningRequestBudget budget,
                   int attempts, Map<String,Object> modelSettings,int updateInterval,int lastIteration,MorningManagerControls controls) {
        if (!(horizon > 0 && Double.isFinite(horizon)) || attempts < 1)
            throw new IllegalArgumentException("Invalid manager execution horizon or output attempts");
        if (updateInterval < 1 || lastIteration < 0) throw new IllegalArgumentException("Invalid manager update schedule");
        this.network = network; this.horizon = horizon; this.client = client; this.output = output;
        this.budget = budget; this.attempts = attempts; this.modelSettings = modelSettings;
        this.environment = environment;
        this.updateInterval = updateInterval; this.lastIteration = lastIteration;
        controls.validate(); this.controls=controls; safety=controls.basic() ? null : new MorningManagerSafety(controls,output);
        feedback = new MorningManagerFeedback(environment.populationSize());
    }
    static boolean updateDay(int day, int firstIteration) {
        return updateDay(day, firstIteration, UPDATE_INTERVAL);
    }
    static boolean updateDay(int day, int firstIteration, int interval) {
        if (interval < 1) throw new IllegalArgumentException("Manager update interval must be positive");
        long offset = (long) day - firstIteration;
        return offset >= FREE_DAYS && (offset - FREE_DAYS) % interval == 0;
    }
    static long minimumCalls(int first, int last) {
        return minimumCalls(first, last, UPDATE_INTERVAL);
    }
    static long minimumCalls(int first, int last, int interval) {
        if (interval < 1) throw new IllegalArgumentException("Manager update interval must be positive");
        long duration = (long) last - first + 1;
        return duration <= FREE_DAYS ? 0 : 1L + (duration - FREE_DAYS - 1L) / interval;
    }
    long attemptedCalls() { return attemptedCalls.get(); }
    TollPolicy automaticRollback(int day,MorningTollSystem.State state) { return controls.basic() ? null : safety.rollback(day,state); }
    boolean inCooldown(int day) { return !controls.basic() && safety.cooldown(day); }
    void policyApplied(int day,TollPolicy next,boolean automatic) { if (!controls.basic()) safety.applied(day,next,automatic); }
    void captureReferenceDemand(org.matsim.api.core.v01.Scenario scenario,int day,MorningTollSystem.State state) {
        if (controls.basic()) return;
        environment.captureReferenceDemand(scenario,day);
        var publicEnvironment=new LinkedHashMap<>(environment.context(day,state.policy(),controls.basic()));
        publicEnvironment.put("pricingControls",controls.context(environment));output.writeDoc("manager-public-environment.json",publicEnvironment);
    }

    /** Allowlist numeric link measurements; passing research logs here cannot expose private fields. */
    void observe(int day, List<Map<String, Object>> linkHours, MorningTollSystem.State state) {
        observe(day,linkHours,state,Map.of());
    }
    void observe(int day,List<Map<String,Object>> linkHours,MorningTollSystem.State state,Map<String,Object> publicCarTrips) {
        if (day < 0 || day <= feedback.observationsThroughDay())
            throw new IllegalArgumentException("Manager observations must be chronological completed days");
        var publicRows = new ArrayList<Map<String, Object>>();
        for (var row : linkHours) {
            var clean = new LinkedHashMap<String, Object>();
            if (!(row.get("link_id") instanceof String linkId) || !network.getLinks().containsKey(Id.createLinkId(linkId)))
                throw new IllegalArgumentException("Public measurement refers to a missing network link");
            clean.put("link_id", row.get("link_id"));
            for (String field : List.of("start", "end", "entered", "completed", "aborted", "unfinished", "partial", "total_travel_time", "total_delay")) {
                if (!(row.get(field) instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0)
                    throw new IllegalArgumentException("Invalid public link measurement: " + field);
                clean.put(field, n);
            }
            publicRows.add(Collections.unmodifiableMap(clean));
        }
        var trips=new LinkedHashMap<String,Object>();
        for(String field:List.of("carTripsStarted","carTripsCompleted","carTripsAborted","carTripsUnfinished")) {
            if(publicCarTrips.get(field) instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue()>=0) trips.put(field,n);
        }
        if(!trips.isEmpty() && trips.size()!=4) throw new IllegalArgumentException("Incomplete public car-trip measurements");
        if(!trips.isEmpty() && ((Number)trips.get("carTripsStarted")).doubleValue()!=
                ((Number)trips.get("carTripsCompleted")).doubleValue()+((Number)trips.get("carTripsAborted")).doubleValue()+((Number)trips.get("carTripsUnfinished")).doubleValue())
            throw new IllegalArgumentException("Public car-trip starts must equal completed + aborted + unfinished");
        var daily=dailyTraffic(day,publicRows);
        daily.put("pricingSpatial",MorningManagerMeasurements.spatial(publicRows,state.policy()));daily.put("publicCarTrips",trips);
        feedback.observe(day,state,daily,publicRows);
        if (!controls.basic()) safety.observe(day,List.copyOf(publicRows),Collections.unmodifiableMap(trips),state);
        window.add(Map.of("day",day,"link_metrics_version",3,"link_hourly",List.copyOf(publicRows),"publicTraffic",daily));
    }

    Map<String, Object> context(int day, int firstIteration, MorningTollSystem.State state) {
        if (window.stream().anyMatch(row -> ((Number) row.get("day")).intValue() >= day))
            throw new IllegalArgumentException("Manager cannot see same-day or future traffic outcomes");
        var history = feedback.managerContext(day, firstIteration);
        if (controls.basic()) history.remove("successCriteria");
        Map<String, double[]> byLink = new TreeMap<>();
        var daily = new ArrayList<Map<String, Object>>();
        for (var observed : window) {
            var publicRows = new ArrayList<Map<String, Object>>();
            for (var value : (List<?>) observed.get("link_hourly")) {
                var row = (Map<?, ?>) value;
                publicRows.add((Map<String, Object>) row);
                double[] sums = byLink.computeIfAbsent((String) row.get("link_id"), id -> new double[7]);
                String[] fields = {"entered", "completed", "aborted", "unfinished", "partial", "total_travel_time", "total_delay"};
                for (int i = 0; i < fields.length; i++) sums[i] += ((Number) row.get(fields[i])).doubleValue();
            }
            daily.add((Map<String,Object>)observed.get("publicTraffic"));
        }
        var totals = new ArrayList<Map<String, Object>>();
        byLink.forEach((id, sums) -> {
            var row = new LinkedHashMap<String, Object>(); row.put("linkId", id);
            String[] fields = {"entered", "completed", "aborted", "unfinished", "partial", "totalTravelSeconds", "totalDelaySeconds"};
            for (int i = 0; i < fields.length; i++) row.put(fields[i], sums[i]);
            row.put("meanTravelSeconds", sums[1] == 0 ? null : sums[5] / sums[1]); totals.add(row);
            row.put("meanDelaySeconds",sums[1]==0?null:sums[6]/sums[1]);
        });
        var context = new LinkedHashMap<String, Object>();
        context.put("day", day); context.put("simulationDay", day - firstIteration + 1);
        context.put("observationsThroughDay", window.isEmpty() ? null : window.getLast().get("day"));
        context.put("simulationEndSeconds", horizon); context.put("monetaryUnits", "scenario monetary units");
        context.put("capacityUnits", "vehicles per network capacityPeriod of " + network.getCapacityPeriod() + " seconds");
        context.put("templateVersion", VERSION); context.put("managerFeedbackMode", FEEDBACK_MODE);
        context.putAll(environment.context(day, state.policy(),controls.basic()));
        var topology = environment.topology();
        var networkColumns = List.of("linkId", "fromNode", "toNode", "lengthMeters", "freeSpeedMetersPerSecond",
                "numberOfLanes", "rawCapacity", "nominalScaledFlowCapacityPerHour");
        context.put("networkColumns", networkColumns); context.put("network", table(topology, networkColumns));
        var schedule = new LinkedHashMap<String, Object>();
        schedule.put("freeSimulationDays", FREE_DAYS); schedule.put("updateIntervalDays", updateInterval);
        schedule.put("lastSimulationIteration", lastIteration);
        long firstUpdate = (long) firstIteration + FREE_DAYS;
        long nextUpdate = day < firstUpdate ? firstUpdate : firstUpdate + (((long) day - firstUpdate) / updateInterval + 1) * updateInterval;
        schedule.put("scheduledUpdateToday", updateDay(day, firstIteration, updateInterval));
        schedule.put("nextScheduledUpdateDay", nextUpdate <= lastIteration ? (int) nextUpdate : null);
        schedule.put("nextScheduledUpdateSimulationDay", nextUpdate <= lastIteration ? nextUpdate - firstIteration + 1 : null);
        schedule.put("plannedPolicyObservationThroughDay", Math.min((long) lastIteration, nextUpdate - 1));
        schedule.put("remainingSimulationDaysIncludingToday", (long) lastIteration - day + 1);
        schedule.put("automaticReviewEveryCompletedDay",!controls.basic());schedule.put("automaticWithdrawalEffectiveNextIteration",!controls.basic());
        schedule.put("modelUpdateSuppressedDuringSafetyCooldown",!controls.basic());
        schedule.put("policyPersistence", controls.basic() ? "Policy repeats until another accepted manager update; no automatic strategy intervention. start/end are within-day seconds."
                : "Policy repeats until an accepted update or next-iteration safety withdrawal. Failed model updates do not disable daily reviews. start/end are within-day seconds.");
        context.put("executionSchedule", schedule);
        context.put("dailyTraffic", daily);
        var totalColumns = List.of("linkId", "entered", "completed", "aborted", "unfinished", "partial", "totalTravelSeconds", "totalDelaySeconds", "meanTravelSeconds","meanDelaySeconds");
        context.put("linkTrafficTotalsColumns", totalColumns); context.put("linkTrafficTotals", table(totals, totalColumns));
        context.put("hourlyTrafficSummary", new CongestionSummary(window).text(network, state.policy()));
        if (!controls.basic()) context.put("routeAlternatives", new MorningManagerAlternatives(network, environment, window).context(state.policy()));
        context.put("measurementScope", "Full-day car traffic; completed full-link delay, with partial/aborted/unfinished separately recorded; no individual records");
        context.put("currentPolicyId", state.policyId()); context.put("currentPolicyVersion", state.version());
        context.put("currentPolicy", state.policy().toJson()); context.put("managerMemory", memory);
        var memoryMetadata = new LinkedHashMap<String, Object>();
        memoryMetadata.put("status", memoryStatus); memoryMetadata.put("writtenAtDay", memoryWrittenAtDay);
        context.put("managerMemoryMetadata", memoryMetadata);
        context.put("currentPolicyFingerprint", state.policy().fingerprint());
        context.put("previousDecision", lastDecision);
        context.put("policyFeedback", history);
        context.put("pricingControls",controls.context(environment));
        context.put("managerProfile", controls.profile);
        if (!controls.basic()) context.put("safetyMonitoring",safety.context());
        return context;
    }

    private static List<List<Object>> table(List<Map<String, Object>> rows, List<String> columns) {
        return rows.stream().map(row -> columns.stream().map(row::get).toList()).toList();
    }

    static Map<String, Object> dailyTraffic(int day, List<Map<String, Object>> rows) {
        var daily = new LinkedHashMap<String, Object>(); daily.put("day", day);
        String[] source = {"total_delay", "total_travel_time", "entered", "completed", "aborted", "unfinished", "partial"};
        String[] target = {"totalLinkDelaySeconds", "totalLinkTravelSeconds", "enteredFullLinkTraversals", "completedFullLinkTraversals",
                "abortedFullLinkTraversals", "unfinishedFullLinkTraversals", "partialLinkTraversals"};
        for (int i = 0; i < source.length; i++) {
            String field = source[i];
            daily.put(target[i], rows.stream().mapToDouble(r -> ((Number) r.get(field)).doubleValue()).sum());
        }
        daily.put("abortedOrUnfinishedTraversals", ((Number) daily.get("abortedFullLinkTraversals")).doubleValue()
                + ((Number) daily.get("unfinishedFullLinkTraversals")).doubleValue());
        return daily;
    }

    private void writeEvaluation(int day, int firstIteration, String reason) {
        output.appendLine("manager-policy-evaluations.jsonl", Map.of("day", day, "simulationDay", day - firstIteration + 1,
                "reason", reason, "includedInManagerContext", false, "scheduledManagerUpdate", reason.equals("scheduled_update"),
                "historyPresentation", "Full version history archive. The actual manager input uses recent versions plus equivalent-price episodes; see manager-contexts.",
                "feedback", feedback.context(day, firstIteration)));
    }

    void writeFinalEvaluation(int firstIteration) {
        if (feedback.observationsThroughDay() >= firstIteration)
            writeEvaluation(feedback.observationsThroughDay() + 1, firstIteration, "shutdown_after_last_observed_day");
    }

    Result update(int day, int firstIteration, MorningTollSystem.State state) {
        var context = context(day, firstIteration, state);
        String base = Json.write(context), hash = MorningPromptRenderer.hash(base);
        String system = controls.basic() ? BASIC_SYSTEM : SYSTEM;
        output.appendLine("manager-contexts.jsonl", context);
        writeEvaluation(day, firstIteration, "scheduled_update");
        String error = null;
        Pending pending = null;
        int calls = 0;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            boolean memoryOnly = pending != null;
            try { budget.reserve(); }
            catch (LlmException exhausted) {
                if (!memoryOnly) throw exhausted;
                error = exhausted.getMessage(); break;
            }
            attemptedCalls.incrementAndGet(); calls++;
            var schema = memoryOnly ? memorySchema() : schema();
            String requestSystem = memoryOnly ? "Rewrite management notes only. Preserve the decision and uncertainty. Do not repeat public facts. "
                    + (controls.basic() ? "Complete replacement English memory, maximum 1000 words. " : "Target 800-1200 Unicode characters; hard maximum 2000. ")
                    + "Return exactly {\"memory\":\"notes\"}." : system;
            String user = memoryOnly ? Json.write(Map.of("validatedPolicyFingerprint", pending.policy().fingerprint(),
                    "decision", pending.decision(), "notesToShorten", pending.notes(), "previousError", error))
                    : base + (error == null ? "" : "\nPrevious output was invalid: " + error
                    + ". Correct the execution or JSON error; choose the legal monetary amounts yourself. Return complete JSON only.");
            var log = new LinkedHashMap<String, Object>();
            log.put("day", day); log.put("role", memoryOnly ? "manager-memory" : "manager");
            log.put("attempt", attempt); log.put("templateVersion", VERSION);
            log.put("contextHash", hash); log.put("system", requestSystem); log.put("user", user);
            log.put("policyLocked", memoryOnly);
            if (memoryOnly) log.put("lockedPolicyFingerprint", pending.policy().fingerprint());
            log.put("modelSettings", MorningOutputContract.requestSettings(modelSettings, schema)); log.put("outputSchema", schema);
            long start = System.nanoTime();
            try {
                var response = client.complete(memoryOnly ? "manager-memory" : "manager", requestSystem, user, schema);
                log.put("response", response.content()); MorningOutputContract.responseMetadata(log, response);
                log.put("usageReported", response.usageReported());
                log.put("usage", response.usageReported() ? Map.of("promptTokens", response.promptTokens(), "completionTokens", response.completionTokens()) : null);
                try {
                    MorningOutputContract.requireNotTruncated(response);
                    var node = strictJson(response.content());
                    if (memoryOnly) {
                        if (node == null || !node.isObject() || node.size() != 1 || !node.path("memory").isTextual())
                            throw new IllegalArgumentException("Memory repair must contain only memory; validated policy cannot change");
                        String nextMemory = validMemory(node.get("memory").asText());
                        log.put("valid", true); log.put("policy", pending.policy().toJson());
                        log.put("decision", pending.decision()); log.put("elapsedMillis", elapsed(start));
                        output.appendLine("manager-attempts.jsonl", log);
                        return accept(day, state, pending, nextMemory, "repaired", calls);
                    }
                    if (node == null || !node.isObject() || node.size() != 3 || !node.path("rules").isArray()
                            || !node.path("memory").isTextual() || !node.path("decision").isObject())
                        throw new IllegalArgumentException("Expected only rules array, decision object and memory string");
                    var rules = new ArrayList<TollPolicy.TollRule>();
                    if (node.get("rules").size() > MorningManagerControls.MAX_RULES)
                        throw new IllegalArgumentException("Manager policy exceeds 40 rules");
                    for (var r : node.get("rules")) {
                        if (!r.isObject() || r.size() != 4 || !r.path("link_id").isTextual() || r.get("link_id").asText().isBlank()
                                || !r.path("start").isNumber() || !r.path("end").isNumber() || !r.path("amount").isNumber())
                            throw new IllegalArgumentException("Each rule needs only link_id, numeric start, end and amount");
                        rules.add(new TollPolicy.TollRule(Id.createLinkId(r.get("link_id").asText()), r.get("start").asDouble(),
                                r.get("end").asDouble(), r.get("amount").asDouble()));
                    }
                    var policy = new TollPolicy(List.copyOf(rules), day);
                    var violations = policy.validate(network, horizon);
                    if (!violations.isEmpty()) throw new IllegalArgumentException(String.join("; ", violations));
                    var decision = decision(node.get("decision"));
                    var executionDiff=controls.check(state.policy(),policy,decision,environment);
                    pending = new Pending(policy, decision, node.get("memory").asText(), attempt,executionDiff);
                    String nextMemory = validMemory(pending.notes());
                    log.put("valid", true); log.put("policy", policy.toJson()); log.put("decision", decision);
                    log.put("elapsedMillis", elapsed(start));
                    output.appendLine("manager-attempts.jsonl", log);
                    return accept(day, state, pending, nextMemory, "updated", calls);
                } catch (java.io.IOException | IllegalArgumentException invalid) { error = invalid.getMessage(); }
                log.put("valid", false); log.put("error", error); log.put("failureKind", "output");
                if (pending != null) {
                    log.put("policyValidated", true); log.put("policy", pending.policy().toJson());
                    log.put("decision", pending.decision());
                }
            } catch (RuntimeException failure) {
                log.put("valid", false); log.put("error", failure.toString()); log.put("failureKind", "call");
                log.put("elapsedMillis", elapsed(start)); output.appendLine("manager-attempts.jsonl", log); output.flush();
                if (!memoryOnly) throw failure;
                error = failure.toString(); break;
            }
            log.put("elapsedMillis", elapsed(start)); output.appendLine("manager-attempts.jsonl", log);
        }
        if (pending != null) {
            output.appendLine("manager-memory-fallbacks.jsonl", Map.of("day", day,
                    "event", "validated_policy_accepted_with_previous_memory", "error", error,
                    "policyFingerprint", pending.policy().fingerprint(), "rulesSourceAttempt", pending.attempt()));
            return accept(day, state, pending, memory, "retained_previous", calls);
        }
        output.appendLine("manager-fallbacks.jsonl", Map.of("day", day, "event", "policy_update_skipped", "error", error,
                "fallback", "retain_previous_policy_and_manager_memory", "policyId", state.policyId(), "policyVersion", state.version()));
        return new Result(state.policy(), false, attempts - 1);
    }

    private record Pending(TollPolicy policy, Map<String, String> decision, String notes, int attempt,Map<String,Object> executionDiff) {}

    private Result accept(int day, MorningTollSystem.State previous, Pending pending, String notes, String memoryStatus, int calls) {
        memory = notes; lastDecision = pending.decision(); window.clear();
        this.memoryStatus = memoryStatus;
        if (!memoryStatus.equals("retained_previous")) memoryWrittenAtDay = day;
        output.appendLine("manager-decisions.jsonl", Map.of("day", day, "policy", pending.policy().toJson(),
                "policyFingerprint", pending.policy().fingerprint(),
                "pricingChanged", !previous.policy().fingerprint().equals(pending.policy().fingerprint()),
                "decision", lastDecision, "memoryStatus", memoryStatus, "memoryCharacters", memory.codePointCount(0, memory.length()),
                "rulesSourceAttempt", pending.attempt(), "attemptedCalls", calls,"executionDiff",pending.executionDiff()));
        output.appendLine("manager-memory-updates.jsonl", Map.of("day", day, "memory", memory, "wordCount", wordCount(memory),
                "memoryStatus", memoryStatus, "managerProfile", controls.profile, "completeReplacement", !memoryStatus.equals("retained_previous")));
        return new Result(pending.policy(), true, Math.max(0, calls - 1));
    }

    private static com.fasterxml.jackson.databind.JsonNode strictJson(String value) throws java.io.IOException {
        return Json.MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(value);
    }

    static int wordCount(String notes) {
        return (int) java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+(?:['’_-][\\p{L}\\p{N}]+)*").matcher(notes).results().count();
    }
    private String validMemory(String notes) {
        if (notes.isBlank()) throw new IllegalArgumentException("Manager memory must be nonempty");
        if (controls.basic() && wordCount(notes) > MEMORY_MAX_WORDS)
            throw new IllegalArgumentException("Manager memory exceeds 1000 words");
        if (!controls.basic() && notes.codePointCount(0, notes.length()) > MEMORY_MAX_CHARS)
            throw new IllegalArgumentException("Manager memory exceeds " + MEMORY_MAX_CHARS + " characters");
        return notes;
    }

    private static final List<String> DECISION_FIELDS = List.of("action", "evidenceBasis", "hypothesis",
            "expectedNetworkEffect", "uncertainty", "evaluationPlan", "revisitCondition");

    private static Map<String, String> decision(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.size() != DECISION_FIELDS.size()) throw new IllegalArgumentException("Invalid manager decision fields");
        var result = new LinkedHashMap<String, String>();
        for (String key : DECISION_FIELDS) {
            if (!node.path(key).isTextual()) throw new IllegalArgumentException("Decision " + key + " must be text");
            String value = node.get(key).asText();
            if (value.isBlank() || value.codePointCount(0, value.length()) > 400)
                throw new IllegalArgumentException("Decision " + key + " must contain 1..400 characters");
            result.put(key, value);
        }
        if (!List.of("hold", "test", "adjust", "remove").contains(result.get("action")))
            throw new IllegalArgumentException("Invalid manager decision action");
        if (!List.of("untested", "observational", "observedTrial").contains(result.get("evidenceBasis")))
            throw new IllegalArgumentException("Invalid manager evidence basis");
        return Collections.unmodifiableMap(result);
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private Map<String, Object> memoryTextSchema() {
        return controls.basic() ? Map.of("type", "string", "minLength", 1)
                : Map.of("type", "string", "minLength", 1, "maxLength", MEMORY_MAX_CHARS);
    }
    private Map<String, Object> schema() {
        var rule = Map.of("type", "object", "properties", Map.of("link_id", Map.of("type", "string"),
                        "start", Map.of("type", "number"), "end", Map.of("type", "number"), "amount", Map.of("type", "number")),
                "required", List.of("link_id", "start", "end", "amount"), "additionalProperties", false);
        var explanations = new LinkedHashMap<String, Object>();
        for (String key : DECISION_FIELDS) explanations.put(key, Map.of("type", "string", "minLength", 1, "maxLength", 400));
        explanations.put("action", Map.of("type", "string", "enum", List.of("hold", "test", "adjust", "remove")));
        explanations.put("evidenceBasis", Map.of("type", "string", "enum", List.of("untested", "observational", "observedTrial")));
        return Map.of("type", "object", "properties", Map.of("rules", Map.of("type", "array", "items", rule, "maxItems", MorningManagerControls.MAX_RULES),
                        "decision", Map.of("type", "object", "properties", explanations, "required", DECISION_FIELDS, "additionalProperties", false),
                        "memory", memoryTextSchema()),
                "required", List.of("rules", "decision", "memory"), "additionalProperties", false);
    }
    private Map<String, Object> memorySchema() {
        return Map.of("type", "object", "properties", Map.of("memory", memoryTextSchema()),
                "required", List.of("memory"), "additionalProperties", false);
    }
}
