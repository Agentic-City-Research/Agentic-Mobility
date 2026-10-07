package org.matsim.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One LLM traveler. Each day it is asked once for the outbound and return
 * route-and-offset decision; the raw prompt, raw output, parsed decision and any
 * legality error are appended to attempts.jsonl. Repair retries feed the error
 * back to the same role; after repairAttempts failures the experiment aborts.
 */
public final class TravelerAgent {

    private final Id<Person> personId;
    private final Profile profile;
    private final boolean informed;
    private final List<RouteCandidate> outboundCandidates;
    private final List<RouteCandidate> returnCandidates;
    private final double outboundAnchor;
    private final double returnAnchor;
    private final int repairAttempts;
    private final ExpectedTravelTimes expected;

    private LlmClient client;
    private LlmUsageTracker usage;
    private OutputWriter output;

    private String memory = "";

    /** Upper bound on the "memory" field the model may carry forward, in words. */
    private static final int MAX_MEMORY_WORDS = 80;

    public TravelerAgent(Id<Person> personId, Profile profile, boolean informed,
                         List<RouteCandidate> outboundCandidates, List<RouteCandidate> returnCandidates,
                         double outboundAnchor, double returnAnchor, int repairAttempts,
                         ExpectedTravelTimes expected) {
        this.personId = personId;
        this.profile = profile;
        this.informed = informed;
        this.outboundCandidates = outboundCandidates;
        this.returnCandidates = returnCandidates;
        this.outboundAnchor = outboundAnchor;
        this.returnAnchor = returnAnchor;
        this.repairAttempts = repairAttempts;
        this.expected = expected;
    }

    /** Wire the LLM client, usage tracker and output writer (set after construction). */
    public void setClient(LlmClient client, LlmUsageTracker usage, OutputWriter output) {
        this.client = client;
        this.usage = usage;
        this.output = output;
    }

    public Id<Person> personId() {
        return personId;
    }

    public boolean informed() {
        return informed;
    }

    public Profile profile() {
        return profile;
    }

    public List<RouteCandidate> outboundCandidates() {
        return outboundCandidates;
    }

    public List<RouteCandidate> returnCandidates() {
        return returnCandidates;
    }

    public double outboundAnchor() {
        return outboundAnchor;
    }

    public double returnAnchor() {
        return returnAnchor;
    }

    public RouteCandidate outboundCandidate(String id) {
        return find(outboundCandidates, id);
    }

    public RouteCandidate returnCandidate(String id) {
        return find(returnCandidates, id);
    }

    /**
     * Produce an accepted decision for the given day, retrying illegal outputs.
     * Transport failures from the client propagate (terminate the experiment).
     */
    public TravelerDecision decide(int day, TollPolicy currentPolicy, Map<String, Object> experience) {
        String system = systemPrompt();
        String error = null;
        for (int attempt = 1; attempt <= repairAttempts; attempt++) {
            String user = userPrompt(day, currentPolicy, experience, error);
            LlmResponse response = client.complete("traveler", system, user);

            TravelerDecision decision;
            String parseError = null;
            try {
                decision = parse(response.content());
            } catch (LlmException e) {
                parseError = e.getMessage();
                decision = null;
            }
            if (decision == null) {
                error = "unparseable output: " + parseError;
                output.attempt(attemptJson(day, attempt, system, user, response, null, error));
                continue;
            }
            List<String> errors = validate(decision);
            if (errors.isEmpty()) {
                memory = decision.memory();
                output.attempt(attemptJson(day, attempt, system, user, response, decision, null));
                return decision;
            }
            error = String.join("; ", errors);
            output.attempt(attemptJson(day, attempt, system, user, response, decision, error));
        }
        throw new LlmException("traveler " + personId + " exhausted " + repairAttempts
                + " repair attempts on day " + day + ": " + error);
    }

    private String systemPrompt() {
        return "You are a commuter in a MATSim traffic simulation of Sioux Falls. Each simulated day "
                + "you choose a route and a departure-time adjustment for your morning commute (home -> work) "
                + "and your evening return (work -> home). You decide for yourself alone, using only the "
                + "information you are given; you do not see other travelers' choices or the live network state.\n\n"
                +"These descriptions express your circumstances and preferences; they do not prescribe a particular "
                +"route or departure time. Make your own choices using the information available to you and your "
                +"personal experience. When your priorities conflict, decide how to balance them."
                + "Travelers have a fixed profile drawn from these dimensions; your own values are stated in each message:\n"
                + Profile.traitCatalog()
                + "\n"
                + "Respond with a single JSON object and nothing else. Exact shape:\n"
                + "{\n"
                + "  \"outbound\": {\"route_id\": \"<candidate id>\", \"offset\": <minutes>},\n"
                + "  \"return\":  {\"route_id\": \"<candidate id>\", \"offset\": <minutes>},\n"
                + "  \"memory\": \"<the notes you want to remember for the next day, at most 80 words>\",\n"
                + "  \"reason\": \"<brief reason for your choices>\"\n"
                + "}\n"
                + "\"offset\" is an integer from -60 to +60 in steps of 10; positive leaves later than your "
                + "anchor, negative leaves earlier.\n\n"
                + "Some days you receive an \"Expected travel times\" table: for each candidate route it lists, "
                + "at every departure offset, the expected trip duration in seconds, estimated from yesterday's "
                + "realized congestion. A larger number means that option was slower.\n\n"
                + "About \"memory\": you decide on your own what to keep, revise, or forget. Write the complete "
                + "notes you want to carry into the next day. The next call will not receive your full history — "
                + "only this memory plus the new daily experience. Memory may not be empty and must be at most "
                + "30 words. Only JSON.";
    }

    private String userPrompt(int day, TollPolicy currentPolicy, Map<String, Object> experience, String error) {
        StringBuilder sb = new StringBuilder();

        // Stable, per-traveler content first: this stays byte-identical across a
        // traveler's days, so it can serve as a shared cache prefix.
        sb.append("Your fixed profile:\n")
                .append("- income: ").append(profile.income().name()).append("\n")
                .append("- decision style: ").append(profile.decisionStyle().name()).append("\n")
                .append("- work timeliness: ").append(profile.workTimeliness().name()).append("\n\n");

        sb.append("Your fixed departure anchors: outbound ")
                .append(hms(outboundAnchor))
                .append(", return ")
                .append(hms(returnAnchor))
                .append(".\n\n");

        sb.append("Outbound (home -> work) route candidates:\n");
        for (RouteCandidate c : outboundCandidates) {
            sb.append(candidateStaticLine(c));
        }
        sb.append("\nReturn (work -> home) route candidates:\n");
        for (RouteCandidate c : returnCandidates) {
            sb.append(candidateStaticLine(c));
        }
        sb.append('\n');

        // Constant per-traveler toll awareness: uninformed travelers never see tolls,
        // so this line is byte-identical across all their days (lengthens their stable prefix).
        if (!informed) {
            sb.append("You have no information about any current congestion tolls.\n\n");
        }

        // Daily-varying content last, so the stable prefix above is what gets cached.
        if (expected.hasData()) {
            sb.append("Expected travel times:\n");
            for (RouteCandidate c : outboundCandidates) {
                sb.append(candidateOffsetLine("outbound", c, outboundAnchor));
            }
            for (RouteCandidate c : returnCandidates) {
                sb.append(candidateOffsetLine("return", c, returnAnchor));
            }
            sb.append('\n');
        }

        sb.append("Day ").append(day).append(".\n\n");

        if (informed) {
            sb.append("Current toll policy (monetary units; only links you would pass matter):\n");
            sb.append(policyText(currentPolicy));
            sb.append('\n');
        }

        if (memory == null || memory.isBlank()) {
            sb.append("You have no notes yet.\n");
        } else {
            sb.append("Your notes from yesterday:\n").append(memory).append('\n');
        }
        sb.append('\n');

        if (experience != null) {
            sb.append(experienceText(experience)).append('\n');
        }

        if (error != null) {
            sb.append("Your previous answer was rejected: ").append(error)
                    .append(". Correct it and try again.\n\n");
        }

        return sb.toString();
    }

    private String candidateStaticLine(RouteCandidate c) {
        return "  " + c.id() + ": free-flow " + round1(c.freeFlowTravelTime()) + "s, "
                + c.linkIds().size() + " links\n";
    }

    private String candidateOffsetLine(String direction, RouteCandidate c, double anchor) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(direction).append(' ').append(c.id()).append(":");
        for (int off : AgenticConfig.offsets()) {
            double dep = anchor + off * 60.0;
            double tt = expected.routeTravelTime(c, dep);
            sb.append(' ').append(off >= 0 ? "+" : "").append(off).append(':').append(Math.round(tt));
        }
        sb.append('\n');
        return sb.toString();
    }

    private String policyText(TollPolicy policy) {
        if (policy == null || !policy.hasRules()) {
            return "  (no toll currently in effect)\n";
        }
        StringBuilder sb = new StringBuilder();
        for (TollPolicy.TollRule r : policy.rules()) {
            sb.append("  link ").append(r.linkId()).append(": ")
                    .append(round2(r.amount())).append(" from ").append(hms(r.startSec()))
                    .append(" to ").append(hms(r.endSec())).append('\n');
        }
        return sb.toString();
    }

    private String experienceText(Map<String, Object> exp) {
        StringBuilder sb = new StringBuilder();
        sb.append("Yesterday's experience:\n");
        Object outDelay = exp.get("outbound_delay");
        Object retDelay = exp.get("return_delay");
        Object outComp = exp.get("outbound_completed");
        Object retComp = exp.get("return_completed");
        sb.append("  outbound completed=").append(outComp).append(", delay=").append(num(outDelay)).append("s\n");
        sb.append("  return completed=").append(retComp).append(", delay=").append(num(retDelay)).append("s\n");
        sb.append("  toll paid=").append(num(exp.get("toll_paid"))).append('\n');
        Object receipts = exp.get("toll_receipts");
        if (receipts instanceof List<?> list && !list.isEmpty()) {
            sb.append("  toll receipts (link, amount, at HH:MM:SS):\n");
            for (Object o : list) {
                if (o instanceof Map<?, ?> r) {
                    sb.append("    ").append(r.get("link_id")).append(", ")
                            .append(num(r.get("amount"))).append(", ")
                            .append(hms(num(r.get("time")))).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private TravelerDecision parse(String content) {
        JsonNode root = Json.readJson(content);
        JsonNode out = root.get("outbound");
        JsonNode ret = root.get("return");
        String outRoute = text(out, "route_id");
        int outOffset = integer(out, "offset");
        String retRoute = text(ret, "route_id");
        int retOffset = integer(ret, "offset");
        String memory = root.hasNonNull("memory") ? root.get("memory").asText() : "";
        String reason = root.hasNonNull("reason") ? root.get("reason").asText() : "";
        return new TravelerDecision(outRoute, outOffset, retRoute, retOffset, memory, reason);
    }

    private List<String> validate(TravelerDecision d) {
        List<String> errors = new ArrayList<>();
        RouteCandidate out = find(outboundCandidates, d.outboundRouteId());
        RouteCandidate ret = find(returnCandidates, d.returnRouteId());
        if (out == null) errors.add("outbound route_id '" + d.outboundRouteId() + "' not in candidates");
        if (ret == null) errors.add("return route_id '" + d.returnRouteId() + "' not in candidates");
        if (!AgenticConfig.isGridOffset(d.outboundOffsetMinutes())) errors.add("outbound offset not in grid");
        if (!AgenticConfig.isGridOffset(d.returnOffsetMinutes())) errors.add("return offset not in grid");
        double outDep = outboundAnchor + d.outboundOffsetMinutes() * 60.0;
        double retDep = returnAnchor + d.returnOffsetMinutes() * 60.0;
        if (outDep < 0 || outDep >= 86400) errors.add("outbound departure outside the simulation day");
        if (retDep < 0 || retDep >= 86400) errors.add("return departure outside the simulation day");
        if (out != null && retDep < outDep + out.freeFlowTravelTime()) {
            errors.add("return departs before the outbound can physically arrive");
        }
        if (d.memory() == null || d.memory().isBlank()) {
            errors.add("memory must be text");
        } else {
            int words = d.memory().trim().split("\\s+").length;
            if (words > MAX_MEMORY_WORDS) {
                errors.add("memory must be at most " + MAX_MEMORY_WORDS + " words (got " + words + ")");
            }
        }
        if (d.reason() == null || d.reason().isBlank()) errors.add("reason must be text");
        return errors;
    }

    private RouteCandidate find(List<RouteCandidate> candidates, String id) {
        for (RouteCandidate c : candidates) {
            if (c.id().equals(id)) return c;
        }
        return null;
    }

    private Map<String, Object> attemptJson(int day, int attempt, String system, String user,
                                            LlmResponse response, TravelerDecision decision, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day);
        m.put("person_id", personId.toString());
        m.put("role", "traveler");
        m.put("attempt", attempt);
        m.put("system_prompt", system);
        m.put("user_prompt", user);
        m.put("output", response.content());
        m.put("prompt_tokens", response.promptTokens());
        m.put("completion_tokens", response.completionTokens());
        m.put("prompt_cache_hit_tokens", response.promptCacheHitTokens());
        m.put("prompt_cache_miss_tokens", response.promptCacheMissTokens());
        if (decision != null) {
            Map<String, Object> dj = new LinkedHashMap<>();
            dj.put("outbound", Map.of("route_id", decision.outboundRouteId(), "offset", decision.outboundOffsetMinutes()));
            dj.put("return", Map.of("route_id", decision.returnRouteId(), "offset", decision.returnOffsetMinutes()));
            dj.put("memory", decision.memory());
            dj.put("reason", decision.reason());
            m.put("decision", dj);
        }
        if (error != null) m.put("error", error);
        return m;
    }

    private static String text(JsonNode node, String field) {
        if (node != null && node.hasNonNull(field)) return node.get(field).asText();
        return "";
    }

    private static int integer(JsonNode node, String field) {
        if (node != null && node.hasNonNull(field)) {
            JsonNode v = node.get(field);
            if (v.isNumber()) return v.asInt();
            if (v.isTextual()) {
                try {
                    return Integer.parseInt(v.asText().trim());
                } catch (NumberFormatException ignored) {
                    return Integer.MIN_VALUE;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    static String hms(double sec) {
        long s = Math.round(sec);
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    static String round1(double v) {
        return String.format("%.1f", v);
    }

    static String round2(double v) {
        return String.format("%.2f", v);
    }

    static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
