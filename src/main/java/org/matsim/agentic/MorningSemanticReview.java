package org.matsim.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Optional single model review. Evidence is quoted from visible facts; judgments are audited, not treated as ground truth. */
final class MorningSemanticReview {
    static final String VERSION="morning-semantic-review-v1-single";
    static final String SYSTEM="""
            Review the proposed traveler's text for clear factual contradictions only. Use only visibleFacts as factual evidence.
            PreviousMemory contains subjective personal judgments and potentially older experiences; it is not verified evidence.
            Do not reject older experience merely because it is absent from the supplied execution window.
            Check explicit claims about fixed personal facts or recorded recent executions. Predictions, preferences, habits,
            uncertainty and future plans are not executed facts. Actual/predicted lateness is not an illegal action or output error.
            Do not demand an earlier departure or a route switch. Preserve every chosen routeId and departureSeconds exactly.
            No current network traffic, other travelers' experiences, hidden manager prices or historical archive are available.
            Return exactly one complete compact JSON object: status, conflicts, output. No Markdown or formatting padding.
            status is no_conflict, corrected or unresolved. Each conflict contains claimQuote (verbatim from proposed text),
            evidenceQuote (verbatim from visibleFacts), and a brief explanation of the contradiction. At most three conflicts.
            If no clear contradiction exists, conflicts is empty and output must equal proposedOutput exactly. If one exists,
            rewrite only reason or memory yourself within the supplied original output contract; keep useful older experience.
            Use corrected if you corrected all identified contradictions; unresolved if any remain. Do not claim factual certainty
            beyond visible evidence. This is a single review, not a multi-round optimization or a new travel choice.
            """;
    record Outcome(JsonNode output,String status,boolean applied) {}
    private final LlmClient client;
    private final OutputWriter output;
    private final MorningRequestBudget budget;
    private final Map<String,Object> settings;
    private final AtomicLong calls=new AtomicLong();
    MorningSemanticReview(LlmClient client,OutputWriter output,MorningRequestBudget budget,Map<String,Object> settings) {
        this.client=client; this.output=output; this.budget=budget; this.settings=settings;
    }
    long attemptedCalls(){return calls.get();}

    Outcome review(int day,String tripId,String personId,String phase,String original,String facts,String priorMemory,
                   Map<String,Object> outputSchema,String textField,Consumer<JsonNode> validate) {
        var initial=Json.readTree(original); var finalOutput=initial;
        String status="review_failed",error=null; boolean applied=false;
        var context=Map.of("visibleFacts",facts,"previousMemory",priorMemory,"proposedOutput",initial,"outputContract",outputSchema);
        String user=Json.write(context); var schema=schema(outputSchema);
        var log=new LinkedHashMap<String,Object>();
        log.put("day",day); log.put("tripId",tripId); log.put("personId",personId); log.put("phase",phase);
        log.put("templateVersion",VERSION); log.put("maximumReviewCalls",1); log.put("originalOutput",initial); log.put("originalResponse",original);
        log.put("system",SYSTEM); log.put("user",user); log.put("contextHash",MorningPromptRenderer.hash(user));
        log.put("requestHash",MorningPromptRenderer.hash(SYSTEM+"\n"+user)); log.put("outputSchema",schema);
        log.put("modelSettings",MorningOutputContract.requestSettings(settings,schema)); log.put("judgmentsAreGroundTruth",false);
        log.put("conflicts",List.of()); log.put("reviewCallMade",false); log.put("response",null); log.put("reviewOutput",null); log.put("usage",null);
        long start=System.nanoTime();
        try {
            budget.reserve(); calls.incrementAndGet(); log.put("reviewCallMade",true);
            var response=client.complete(phase.equals("decision")?"traveler_review":"traveler_memory_review",SYSTEM,user,schema);
            log.put("response",response.content()); MorningOutputContract.responseMetadata(log,response);
            log.put("usageReported",response.usageReported()); log.put("usage",MorningOutputContract.usage(response));
            MorningOutputContract.requireNotTruncated(response);
            var node=strict(response.content());
            if(node==null || !node.isObject() || node.size()!=3 || !node.path("status").isTextual()
                    || !node.path("conflicts").isArray() || node.get("conflicts").size()>3 || !node.path("output").isObject())
                throw new IllegalArgumentException("Expected status, conflicts and output only");
            String proposedStatus=node.get("status").asText();
            if(!List.of("no_conflict","corrected","unresolved").contains(proposedStatus)) throw new IllegalArgumentException("Invalid review status");
            log.put("conflicts",node.get("conflicts"));
            for(var conflict:node.get("conflicts")) {
                if(!conflict.isObject() || conflict.size()!=3) throw new IllegalArgumentException("Invalid conflict evidence fields");
                for(String field:List.of("claimQuote","evidenceQuote","explanation")) {
                    if(!conflict.path(field).isTextual() || conflict.get(field).asText().isBlank()
                            || conflict.get(field).asText().codePointCount(0,conflict.get(field).asText().length())>400)
                        throw new IllegalArgumentException("Review evidence must contain nonempty text");
                }
                if(!initial.path(textField).asText().contains(conflict.get("claimQuote").asText())
                        || !facts.contains(conflict.get("evidenceQuote").asText()))
                    throw new IllegalArgumentException("Conflict quotes must occur in the original text and visible factual input");
            }
            boolean hasConflicts=!node.get("conflicts").isEmpty();
            if(proposedStatus.equals("no_conflict")!=!hasConflicts) throw new IllegalArgumentException("Review status and evidence disagree");
            var candidate=node.get("output"); log.put("reviewOutput",candidate); validate.accept(candidate);
            if(phase.equals("decision")) for(String field:List.of("routeId","departureSeconds"))
                if(!sameValue(initial.get(field),candidate.get(field))) throw new IllegalArgumentException("Semantic review cannot alter executable choices");
            boolean textChanged=!initial.path(textField).asText().equals(candidate.path(textField).asText());
            if(!hasConflicts && textChanged) throw new IllegalArgumentException("No-conflict review cannot rewrite the output");
            if(proposedStatus.equals("corrected") && !textChanged)
                throw new IllegalArgumentException("A corrected review must actually revise the text; otherwise mark unresolved");
            finalOutput=candidate; status=proposedStatus; applied=textChanged;
        }catch(Exception failure){error=failure.toString(); log.put("failureKind",failure instanceof LlmException?"call_or_budget":"review_output");}
        log.put("reviewStatus",status); log.put("reviewValid",error==null); log.put("error",error);
        log.put("finalOutput",finalOutput); log.put("revisionApplied",applied);
        log.put("semanticUnresolved",status.equals("unresolved") || status.equals("review_failed"));
        log.put("elapsedMillis",(System.nanoTime()-start)/1_000_000.0);
        output.appendLine("semantic-reviews.jsonl",log);
        return new Outcome(finalOutput,status,applied);
    }

    static JsonNode strict(String value) throws java.io.IOException {
        return Json.MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(value);
    }
    private static boolean sameValue(JsonNode a,JsonNode b) {
        if(a!=null && b!=null && a.isNumber() && b.isNumber()) return Double.compare(a.doubleValue(),b.doubleValue())==0;
        return Objects.equals(a,b);
    }
    private static Map<String,Object> schema(Map<String,Object> output) {
        var evidence=Map.of("type","string","minLength",1,"maxLength",400);
        var conflict=Map.of("type","object","properties",Map.of("claimQuote",evidence,"evidenceQuote",evidence,"explanation",evidence),
                "required",List.of("claimQuote","evidenceQuote","explanation"),"additionalProperties",false);
        return Map.of("type","object","properties",Map.of("status",Map.of("type","string","enum",List.of("no_conflict","corrected","unresolved")),
                        "conflicts",Map.of("type","array","items",conflict,"maxItems",3),"output",output),
                "required",List.of("status","conflicts","output"),"additionalProperties",false);
    }
}
