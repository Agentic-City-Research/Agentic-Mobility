package org.matsim.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Python proposes actions; MATSim validates and executes them. */
final class PythonTravelerBridge {
    private final URI root;
    private final HttpClient http;
    private final String runId = UUID.randomUUID().toString();
    private final Duration timeout;
    private final int reasonMaxChars;
    private final Map<String,String> proposals = new HashMap<>();
    private static final Set<String> VISIBLE_ATTRIBUTES = Set.of("age", "sex", "employment", "employed", "carAvail", "income", "incomeUnits", "lateToleranceMinutes", "valueOfTime", "preferences", "scheduleRequirements");

    private static void removeAnalysisFields(JsonNode node) {
        if (node.isObject()) {
            ((ObjectNode)node).remove(List.of("experiencedCount", "chosenCount", "lastPersonalExperienceDay"));
            node.elements().forEachRemaining(PythonTravelerBridge::removeAnalysisFields);
        } else if (node.isArray()) node.elements().forEachRemaining(PythonTravelerBridge::removeAnalysisFields);
    }

    PythonTravelerBridge(String url, int timeoutSeconds, int reasonMaxChars) {
        var parsed = URI.create(url);
        if (!Set.of("http", "https").contains(parsed.getScheme()) || parsed.getHost() == null || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null)
            throw new IllegalArgumentException("Python service URL must be an HTTP(S) base URL without credentials, query, or fragment");
        root = URI.create(url.replaceAll("/+$", "") + "/");
        timeout = Duration.ofSeconds(timeoutSeconds);
        this.reasonMaxChars = reasonMaxChars;
        http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    String runId() { return runId; }
    JsonNode describe() {
        var request = HttpRequest.newBuilder(root.resolve("health")).timeout(timeout).GET().build();
        try {
            var response = http.send(request,HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode()!=200) throw new IllegalStateException("Python agent health returned HTTP "+response.statusCode());
            return Json.readTree(response.body());
        } catch (java.io.IOException e) { throw new IllegalStateException("Python agent health request failed", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Python agent health request interrupted", e); }
    }
    private String requestId(int day, String trip) { return "decision:" + day + ":" + trip; }
    private Map<String,Object> common(int day, String person, String trip, String phase, String req) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("run_id",runId); m.put("agent_id",person); m.put("trip_id",trip);
        m.put("simulation_day",day); m.put("phase",phase); m.put("request_id",req);
        m.put("observation_version","day-"+day+"-"+phase); m.put("action_space_version","morning-v1");
        return m;
    }
    private JsonNode post(String endpoint, Object body) {
        var request = HttpRequest.newBuilder(root.resolve(endpoint)).timeout(timeout)
                .header("Content-Type","application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), java.nio.charset.StandardCharsets.UTF_8)).build();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var response=http.send(request,HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                if(response.statusCode()!=200) {
                    String code = "UNAVAILABLE";
                    try {
                        String candidate = Json.readTree(response.body()).path("error_code").asText();
                        if (candidate.matches("[A-Z_]{1,80}")) code = candidate;
                    } catch (RuntimeException ignored) { /* Do not expose arbitrary response bodies. */ }
                    throw new IllegalStateException("Python agent returned HTTP "+response.statusCode()+"; error_code="+code);
                }
                return Json.readTree(response.body());
            } catch (java.io.IOException e) {
                // Same payload and request IDs: a late completed response is returned from the Python cache.
                if (attempt == 1) throw new IllegalStateException("Python agent request failed after retry", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Python agent request interrupted", e);
            }
        }
        throw new IllegalStateException("Unreachable Python agent retry state");
    }

    List<MorningRoutePolicy.Decision> decide(List<MorningRoutePolicy.Context> contexts) {
        if (contexts.isEmpty()) return List.of();
        List<Map<String,Object>> requests=new ArrayList<>();
        List<List<Map<String,Object>>> spaces=new ArrayList<>();
        for(var c:contexts) {
            var req=common(c.day(),c.personId(),c.tripId(),"traveler_decision",requestId(c.day(),c.tripId()));
            // Use the environment's already filtered information, and the same attribute allowlist as v9.
            ObjectNode visible=(ObjectNode)Json.MAPPER.valueToTree(c);
            ObjectNode attrs=(ObjectNode)visible.path("personAttributes");
            List<String> remove=new ArrayList<>(); attrs.fieldNames().forEachRemaining(k -> { if (!VISIBLE_ATTRIBUTES.contains(k)) remove.add(k); });
            remove.forEach(attrs::remove);
            removeAnalysisFields(visible.path("candidates"));
            removeAnalysisFields(visible.path("departureOptions"));
            // Personal summaries are supplied by the Python backend, never recovered from the research archive.
            visible.remove(List.of("memorySummary","memorySummaryDay"));
            req.put("observations",List.of(Map.of("observation_id","context:"+c.day()+":"+c.tripId(),"occurred_at","day-"+c.day(),"visible_at","day-"+c.day(),"source","matsim","content",visible)));
            var renderer = new MorningPromptRenderer();
            req.put("v9", Map.of("system", renderer.system(reasonMaxChars, !c.departureOptions().isEmpty()),
                    "prompt", renderer.render(c), "previous_memory", c.memorySummary(), "reason_max_chars", reasonMaxChars,
                    "joint", !c.departureOptions().isEmpty()));
            List<Map<String,Object>> actions=new ArrayList<>();
            var departures=c.departureOptions().isEmpty()?List.of(new MorningRoutePolicy.DepartureOption(c.plannedDepartureSeconds(),c.candidates())):c.departureOptions();
            int index=0;
            for(var d:departures) for(var route:d.routes()) {
                var action=new LinkedHashMap<String,Object>();
                action.put("action_id","action-"+(++index)); action.put("route_id",route.routeId());
                action.put("departure_seconds",d.departureSeconds()); action.put("expected_car_seconds",route.expectedCarSeconds());
                action.put("estimated_monetary_cost",route.estimatedMonetaryCost());
                actions.add(action);
            }
            req.put("actions",actions); requests.add(req); spaces.add(actions);
        }
        // All contexts are captured before the first request. Send one trip per
        // request to bound payload size and the deadline for serial model calls.
        var rows=Json.MAPPER.createArrayNode();
        for (var request:requests) {
            JsonNode part=post("v1/decisions",Map.of("requests",List.of(request))).path("responses");
            if(!part.isArray() || part.size()!=1) throw new IllegalStateException("Python decision response count mismatch");
            rows.add(part.get(0));
        }
        List<MorningRoutePolicy.Decision> result=new ArrayList<>();
        for(int i=0;i<contexts.size();i++) {
            var c=contexts.get(i); var row=rows.get(i); var req=requests.get(i);
            for(String k:List.of("run_id","agent_id","trip_id","phase","request_id","observation_version","action_space_version"))
                if (!Objects.equals(req.get(k), row.path(k).asText())) throw new IllegalStateException("Python response identity mismatch: "+k);
            if(!row.path("simulation_day").isIntegralNumber() || row.path("simulation_day").asInt()!=c.day()) throw new IllegalStateException("Python response day mismatch");
            var a=row.path("action");
            var offered=spaces.get(i).stream().filter(x -> Objects.equals(x.get("action_id"),a.path("action_id").asText())).findFirst().orElseThrow(() -> new IllegalStateException("Python action was not offered"));
            if(!Objects.equals(offered.get("route_id"),a.path("route_id").asText()) || !a.path("departure_seconds").isNumber() || ((Number)offered.get("departure_seconds")).doubleValue()!=a.path("departure_seconds").asDouble())
                throw new IllegalStateException("Python action differs from offered route/time");
            String reason=row.path("reason").asText("Python traveler action");
            var decision=new MorningRoutePolicy.Decision(a.path("route_id").asText(),reason,c.departureOptions().isEmpty()?null:a.path("departure_seconds").asDouble());
            result.add(MorningRoutePolicy.validate(c,decision));
        }
        // Register proposals only after the entire response has been validated.
        for(var c:contexts) proposals.put(c.day()+":"+c.tripId(),requestId(c.day(),c.tripId()));
        return result;
    }

    String feedback(int day, MorningCohort.Member member, MorningMemory.Experience experience, int maxChars) {
        String proposal=proposals.get(day+":"+member.key);
        if(proposal==null) throw new IllegalStateException("Missing Python proposal for "+member.key);
        String person=member.person.getId().toString();
        var req=common(day,person,member.key,"feedback","feedback:"+day+":"+member.key);
        req.put("receipt",Map.of("proposal_request_id",proposal,"accepted",true,"route_id",member.routeId,"departure_seconds",member.selectedDeparture));
        req.put("observations",List.of(Map.of("observation_id","outcome:"+day+":"+member.key,"occurred_at","day-"+day,"visible_at","day-"+(day+1),"source","matsim","content",experience)));
        var context = new MorningMemoryUpdater.Context(day, member.key, person, member.destinationPurpose,
                member.deadline, member.originalAttributes, member.profile, member.memory.summary(), member.memory.summaryDay(),
                MorningPromptRenderer.recentFacts(member.memory.recent(1),day,true,1),
                member.candidates.stream().map(RouteCandidate::id).toList(), member.informationGroup, List.of());
        req.put("v9", reflectionInput(context, maxChars));
        JsonNode response=post("v1/feedback",req);
        if(!runId.equals(response.path("run_id").asText()) || !Objects.equals(req.get("request_id"),response.path("request_id").asText()) || !"recorded".equals(response.path("status").asText()))
            throw new IllegalStateException("Python feedback response mismatch");
        if (response.has("memory")) {
            if (!response.path("memory").isTextual() || response.path("memory").asText().isBlank() || response.path("memory").asText().codePointCount(0,response.path("memory").asText().length())>maxChars)
                throw new IllegalStateException("Python memory response is invalid");
            return response.path("memory").asText();
        }
        return null;
    }
    static Map<String,Object> reflectionInput(MorningMemoryUpdater.Context context, int maxChars) {
        return Map.of("system", MorningMemoryUpdater.systemForInterval(1,maxChars),
                "prompt", MorningMemoryUpdater.render(context,1), "previous_memory", context.previousMemory(), "max_chars", maxChars);
    }
}
