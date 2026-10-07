package org.matsim.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The congestion-pricing manager. Runs once per update day (6, 11, ..., 96),
 * producing a full replacement toll policy from the car network summary, the
 * current policy, and the daily aggregate metrics since the previous update.
 * Repair retries feed legality errors back; after repairAttempts failures the
 * previous policy and memory are retained so the simulation can continue.
 */
public final class ManagerAgent {

    static final String PROMPT_VERSION = "principle-based-v1";
    static final int MAX_RULES = 40;
    /** Upper bound on the "memory" field the manager may carry forward, in words. */
    static final int MAX_MEMORY_WORDS = 300;

    private final Network network;
    private final LlmClient client;
    private final OutputWriter output;
    private final int repairAttempts;

    private String memory = "";

    public ManagerAgent(Network network, LlmClient client, OutputWriter output, int repairAttempts) {
        this.network = network;
        this.client = client;
        this.output = output;
        this.repairAttempts = repairAttempts;
    }

    private record Parsed(TollPolicy policy, String memory) {
    }

    public TollPolicy decide(int day, TollPolicy currentPolicy, List<Map<String, Object>> metricsSinceUpdate,
                             int populationSize, double informedShare) {
        String system = systemPrompt();
        String error = null;
        for (int attempt = 1; attempt <= repairAttempts; attempt++) {
            String user = userPrompt(day, currentPolicy, metricsSinceUpdate, populationSize, informedShare, error);
            LlmResponse response = client.complete("manager", system, user);

            Parsed parsed;
            String parseError = null;
            try {
                parsed = parse(response.content(), day);
            } catch (LlmException e) {
                parseError = e.getMessage();
                parsed = null;
            }
            if (parsed == null) {
                error = "unparseable output: " + parseError;
                output.attempt(attemptJson(day, attempt, system, user, response, null, error));
                continue;
            }
            List<String> errors = validate(parsed);
            if (errors.isEmpty()) {
                memory = parsed.memory();
                output.attempt(attemptJson(day, attempt, system, user, response, parsed.policy(), null));
                return parsed.policy();
            }
            error = String.join("; ", errors);
            output.attempt(attemptJson(day, attempt, system, user, response, parsed.policy(), error));
        }
        TollPolicy retained = currentPolicy == null ? TollPolicy.empty(-1) : currentPolicy;
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("day", day);
        fallback.put("role", "manager");
        fallback.put("manager_prompt_version", PROMPT_VERSION);
        fallback.put("event", "policy_update_skipped");
        fallback.put("repair_attempts", repairAttempts);
        fallback.put("error", error);
        fallback.put("fallback", "retain_previous_policy_and_memory");
        fallback.put("policy", retained.toJson());
        output.appendLine("manager-fallbacks.jsonl", fallback);
        org.apache.logging.log4j.LogManager.getLogger(ManagerAgent.class).warn(
                "Manager update on day {} rejected after {} attempts; retaining policy from day {}: {}",
                day, repairAttempts, retained.day(), error);
        return retained;
    }

    private List<String> validate(Parsed parsed) {
        List<String> errors = new ArrayList<>(parsed.policy().validate(network));
        if (parsed.policy().rules().size() > MAX_RULES) {
            errors.add("too many rules (" + parsed.policy().rules().size() + " > " + MAX_RULES + ")");
        }
        java.util.Set<Id<Link>> seen = new java.util.HashSet<>();
        for (var rule : parsed.policy().rules()) {
            if (!seen.add(rule.linkId())) errors.add("each link may appear at most once: " + rule.linkId());
        }
        if (parsed.memory() == null || parsed.memory().isBlank()) {
            errors.add("memory must be text");
        } else {
            int words = parsed.memory().trim().split("\\s+").length;
            if (words > MAX_MEMORY_WORDS) {
                errors.add("memory must be at most " + MAX_MEMORY_WORDS + " words (got " + words + ")");
            }
        }
        return errors;
    }

    private String systemPrompt() {
        return """
                You manage congestion pricing in a MATSim simulation of Sioux Falls over repeated policy updates.

                Your objective is to reduce total network delay.

                At each update, you receive the car network, the current toll policy, outcomes observed since your
                previous update, and your saved memory. Use this feedback to form and revise your own understanding
                of the network and your pricing strategy.

                You may retain, add, adjust, or remove tolls, choosing the links, time windows, and amounts yourself.
                You may also leave the policy unchanged.

                All travelers pay applicable tolls. Informed travelers know tolls when choosing routes;
                uninformed travelers discover them after paying.

                Environment:
                - Congestion results from interactions between traffic demand and network capacity. Travel times
                  can increase substantially when parts of the network become heavily loaded.
                - Traffic conditions on connected links can affect one another. Downstream congestion may create
                  queues and delay on upstream links.
                - Delay observed on a link therefore does not necessarily identify the causal source of congestion.
                - The set of trips is fixed. Tolls do not remove trips; they affect traffic through the traveler
                  choices available in the simulation.
                - Tolls do not directly change road capacity or free-flow travel time. They may affect network
                  conditions by changing traveler choices.
                - Changes in traveler choices may redistribute traffic across the network, so improvement in
                  one location may coincide with deterioration elsewhere.
                - Travelers may adapt over time. A change following a policy intervention does not by itself
                  establish causation.

                Data:
                - Link delay ratio is realized travel time divided by free-flow travel time.
                - Hourly observations describe delay by link and entry hour; daily values aggregate completed traversals.
                - Parking/activity time is excluded from link travel time; missing estimates are marked NA.
                - Reported network delay sums max(0, actual trip time minus the free-flow time of its chosen route)
                  for completed trips only. Uncompleted trips do not contribute to this sum.

                Use observations from successive updates to test and revise your previous beliefs and strategies.

                At each update, return a complete updated memory within %d words, replacing the previous memory.
                Memory is your persistent decision state. Keep only observations, hypotheses, lessons, uncertainties,
                and ideas that remain useful for future decisions; discard or revise conclusions contradicted by
                later evidence. The full raw history is not available to future updates.

                Return one JSON object:
                {
                  "rules": [
                    {"link_id": "<link id>", "start": <seconds>, "end": <seconds>, "amount": <number>}
                  ],
                  "memory": "..."
                }

                Requirements:
                - Use only car links listed in the network.
                - Return at most %d rules, each link at most once.
                - 0 <= start < end <= 86400.
                - Amounts must be finite and non-negative.
                - Return the complete replacement policy; an empty list removes all tolls.
                - Memory must be non-empty and at most %d words.
                - Output only JSON.
                """.formatted(MAX_MEMORY_WORDS, MAX_RULES, MAX_MEMORY_WORDS);

    }

    private String userPrompt(int day, TollPolicy currentPolicy, List<Map<String, Object>> metricsSinceUpdate,
                              int populationSize, double informedShare, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("Update day ").append(day).append(".\n\n");

        sb.append("Car network (link id: length m | freespeed m/s | nominal capacity veh/h | from -> to | delay ratio):\n");
        sb.append(networkSummary(metricsSinceUpdate)).append('\n');
        sb.append(new CongestionSummary(metricsSinceUpdate).text(network, currentPolicy)).append('\n');

        sb.append("Current toll policy being replaced (");
        int n = currentPolicy == null ? 0 : currentPolicy.rules().size();
        sb.append(n).append(" rules):\n");
        if (currentPolicy != null && currentPolicy.day() >= 0) {
            sb.append("  Effective from the start of day ").append(currentPolicy.day()).append(".\n");
        } else {
            sb.append("  Initial no-toll policy; no manager update has taken effect yet.\n");
        }
        if (n == 0) {
            sb.append("  (none)\n");
        } else {
            for (TollPolicy.TollRule r : currentPolicy.rules()) {
                sb.append("  link ").append(r.linkId()).append(": ").append(TravelerAgent.round2(r.amount()))
                        .append(" from ").append(TravelerAgent.hms(r.startSec()))
                        .append(" to ").append(TravelerAgent.hms(r.endSec())).append('\n');
            }
        }
        sb.append('\n');

        sb.append("Population size: ").append(populationSize)
                .append(". Informed share: ").append(informedShare).append(".\n\n");

        sb.append("Daily delay observations since the last update (network total, then per-group average; seconds):\n");
        if (metricsSinceUpdate == null || metricsSinceUpdate.isEmpty()) {
            sb.append("  (none yet)\n");
        } else {
            for (Map<String, Object> dayMetrics : metricsSinceUpdate) {
                sb.append(metricsLine(dayMetrics));
            }
        }
        sb.append('\n');

        if (memory == null || memory.isBlank()) {
            sb.append("You have no notes yet.\n");
        } else {
            sb.append("Your notes from last update:\n").append(memory).append('\n');
        }
        sb.append('\n');

        if (error != null) {
            sb.append("Your previous answer was rejected: ").append(error)
                    .append(". Correct it and try again.\n\n");
        }

        sb.append("Return only the JSON object.");
        return sb.toString();
    }

    private String metricsLine(Map<String, Object> dayMetrics) {
        Object day = dayMetrics.get("day");
        StringBuilder sb = new StringBuilder("  day ").append(day).append(":");

        // Whole-network outcomes first, followed by group outcomes for policy review.
        Object network = dayMetrics.get("network");
        if (network instanceof Map<?, ?> n) {
            Object delay = n.get("total_delay");
            sb.append(" network=")
                    .append(delay instanceof Number ? TravelerAgent.round1(((Number) delay).doubleValue()) : "?")
                    .append("s total delay");
        }

        Object groups = dayMetrics.get("groups");
        if (groups instanceof Map<?, ?> g) {
            for (Object key : g.keySet()) {
                Object gm = g.get(key);
                if (gm instanceof Map<?, ?> m) {
                    Object delay = m.get("avg_delay");
                    sb.append(" | ").append(key).append("=")
                            .append(delay instanceof Number ? TravelerAgent.round1(((Number) delay).doubleValue()) : "?")
                            .append("s");
                }
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private Parsed parse(String content, int day) {
        JsonNode root = Json.readJson(content);
        if (!root.isObject() || !root.has("rules") || !root.get("rules").isArray()
                || !root.has("memory") || !root.get("memory").isTextual())
            throw new LlmException("expected an object with rules array and text memory");
        List<TollPolicy.TollRule> rules = new ArrayList<>();
        JsonNode rulesNode = root.get("rules");
        if (rulesNode != null && rulesNode.isArray()) {
            for (JsonNode r : rulesNode) {
                String linkId = r.hasNonNull("link_id") ? r.get("link_id").asText() : "";
                double start = r.hasNonNull("start") ? r.get("start").asDouble() : Double.NaN;
                double end = r.hasNonNull("end") ? r.get("end").asDouble() : Double.NaN;
                double amount = r.hasNonNull("amount") ? r.get("amount").asDouble() : Double.NaN;
                if (!r.isObject() || !r.path("link_id").isTextual() || linkId.isBlank()
                        || !r.path("start").isNumber() || !r.path("end").isNumber() || !r.path("amount").isNumber())
                    throw new LlmException("each rule requires a text link_id and numeric start, end, amount");
                rules.add(new TollPolicy.TollRule(Id.createLinkId(linkId), start, end, amount));
            }
        }
        String memory = root.hasNonNull("memory") ? root.get("memory").asText() : "";
        return new Parsed(new TollPolicy(rules, day), memory);
    }

    private Map<String, Object> attemptJson(int day, int attempt, String system, String user,
                                            LlmResponse response, TollPolicy policy, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day);
        m.put("role", "manager");
        m.put("manager_prompt_version", PROMPT_VERSION);
        m.put("attempt", attempt);
        m.put("system_prompt", system);
        m.put("user_prompt", user);
        m.put("output", response.content());
        m.put("prompt_tokens", response.promptTokens());
        m.put("completion_tokens", response.completionTokens());
        m.put("prompt_cache_hit_tokens", response.promptCacheHitTokens());
        m.put("prompt_cache_miss_tokens", response.promptCacheMissTokens());
        if (policy != null) m.put("policy", policy.toJson());
        if (error != null) m.put("error", error);
        return m;
    }

    private String networkSummary(List<Map<String, Object>> metricsSinceUpdate) {
        Map<String, Double> avgRatio = new CongestionSummary(metricsSinceUpdate).ratios(network);
        StringBuilder sb = new StringBuilder();
        for (Link l : network.getLinks().values()) {
            if (!l.getAllowedModes().contains("car")) continue;
            sb.append("  ").append(l.getId()).append(": ")
                    .append(TravelerAgent.round1(l.getLength())).append(" m | ")
                    .append(TravelerAgent.round1(l.getFreespeed())).append(" m/s | ")
                    .append(TravelerAgent.round1(l.getCapacity() * 3600.0 / network.getCapacityPeriod())).append(" veh/h | ")
                    .append(l.getFromNode().getId()).append(" -> ").append(l.getToNode().getId());
            Double ratio = avgRatio.get(l.getId().toString());
            if (ratio != null) {
                sb.append(" | delay ratio ").append(TravelerAgent.round2(ratio));
            } else {
                sb.append(" | delay ratio NA");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

}
