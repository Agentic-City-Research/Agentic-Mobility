package org.matsim.agentic;

import java.util.*;

/** A reflection worker returns notes only; the simulation thread commits them after a successful call. */
final class MorningManagerMemory {
    static final String VERSION = "morning-manager-memory-v2-full-day-car";
    static final String SYSTEM = """
            Reflect on the completed simulation day as the congestion-pricing manager. Update management memory only.
            Return exactly {"memory":"notes"}; no policy, rules or decision fields. This call cannot change prices.
            Write complete replacement English memory. Target 400-600 words; hard maximum 1000 words.
            Use fewer words when evidence is limited. Do not pad, append a daily diary, or repeat supplied tables.
            Organize notes under: traffic state; observed policy outcomes; retained lessons; uncertainty; next review considerations.
            Keep the latest two policy experiences and summarize older useful lessons; revise or delete interpretations contradicted by evidence.
            The supplied executed policy and measured aggregates are authoritative facts; memory is interpretation, not an execution record.
            All input observations are completed days. newObservations covers days since the last successful reflection, including missed days.
            currentPriceEpisode counts consecutive days with the same actual price function; a hold/version change does not restart the trial.
            trafficMetricColumns declares totals vector order. Divide totals by observedDays for daily means and by completed full-link
            traversals for weighted traversal means. Car-leg completion rate is completed/started; missing data is unknown, not zero.
            Delay/travel measure completed full links, not complete OD journeys. Aborted, unfinished and partial counts are separate.
            Full-day car person-hours include entry waiting and are null if any planned leg is incomplete. Completed pre-traffic wait covers only completed legs with observed traffic entry; observed lower bounds are not complete totals.
            Lower delay with fewer completed car legs or more aborted/unfinished trips does not establish improvement.
            Check priced-link and other-road outcomes for congestion displacement. Revenue is an aggregate receipt, not the objective.
            One day is preliminary evidence. Seven-day trends do not certify convergence; consecutive days are not independent replications.
            Describe changes observed during a policy, without asserting matched causal effects. Native route/time adaptation and background
            variation can explain improvement, including during the first 20 free observation days. Free-period references can overlap history.
            Keep unresolved hypotheses and uncertainty; future adjustments are considerations for scheduled policy reviews only.
            Preserve distinctions between expected effects in previousDecision and actual outcomes. You cannot see traveler profiles,
            private memories or individual payments. The absence of a road from selected statistics does not establish spare capacity.
            """;
    record Update(boolean accepted, String memory, String error, int calls, String contextHash) {}
    private final LlmClient client;
    private final OutputWriter output;
    private final MorningRequestBudget budget;
    private final int attempts;
    private final Map<String, Object> settings;
    private final java.util.concurrent.atomic.AtomicLong attemptedCalls=new java.util.concurrent.atomic.AtomicLong();

    MorningManagerMemory(LlmClient client, OutputWriter output, MorningRequestBudget budget, int attempts,
                         Map<String, Object> settings) {
        this.client=client; this.output=output; this.budget=budget; this.attempts=attempts; this.settings=settings;
    }

    Update update(Map<String, Object> context) {
        String base=Json.write(context), hash=MorningPromptRenderer.hash(base), error=null;
        var outputSchema=schema();
        String system=MorningOutputContract.jsonObjectSystem(SYSTEM,settings,outputSchema);
        int day=((Number)context.get("day")).intValue(), calls=0;
        output.appendLine("manager-memory-contexts.jsonl", context);
        for (int attempt=1; attempt<=attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) return new Update(false,null,"Reflection interrupted",calls,hash);
            try { budget.reserve(); } catch (LlmException exhausted) { error=exhausted.getMessage(); break; }
            calls++; attemptedCalls.incrementAndGet();
            String user=base+(error==null ? "" : "\nPrevious output was invalid: "+error+". Return only valid replacement memory JSON.");
            var log=new LinkedHashMap<String,Object>();
            log.put("day",day); log.put("role","manager-memory"); log.put("attempt",attempt); log.put("templateVersion",VERSION);
            log.put("contextHash",hash); log.put("system",system); log.put("user",user);
            log.put("requestHash",MorningPromptRenderer.hash(system+"\n"+user));
            log.put("systemCharacters",system.length()); log.put("userCharacters",user.length());
            log.put("outputSchema",outputSchema); log.put("modelSettings",MorningOutputContract.requestSettings(settings,outputSchema));
            long start=System.nanoTime();
            try {
                var response=client.complete("manager-memory",system,user,outputSchema);
                if (Thread.currentThread().isInterrupted()) return new Update(false,null,"Reflection interrupted",calls,hash);
                log.put("response",response.content()); MorningOutputContract.responseMetadata(log,response);
                log.put("usageReported",response.usageReported()); log.put("usage",MorningOutputContract.usage(response));
                try {
                    MorningOutputContract.requireNotTruncated(response);
                    var node=Json.MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(response.content());
                    if(node==null || !node.isObject() || node.size()!=1 || !node.path("memory").isTextual())
                        throw new IllegalArgumentException("Reflection must contain only a memory string; it cannot change policy");
                    String notes=node.get("memory").asText();
                    if(notes.isBlank() || MorningManager.wordCount(notes)>MorningManager.MEMORY_MAX_WORDS)
                        throw new IllegalArgumentException("Manager memory must be nonempty and at most 1000 words");
                    log.put("valid",true); log.put("elapsedMillis",(System.nanoTime()-start)/1_000_000.0);
                    output.appendLine("manager-memory-attempts.jsonl",log);
                    return new Update(true,notes,null,calls,hash);
                } catch(java.io.IOException | IllegalArgumentException invalid) {
                    error=invalid.getMessage(); log.put("failureKind","output");
                }
            } catch(RuntimeException failure) {
                error=failure.toString(); log.put("failureKind","call");
                if(Thread.currentThread().isInterrupted()) return new Update(false,null,error,calls,hash);
                log.put("valid",false); log.put("error",error); log.put("elapsedMillis",(System.nanoTime()-start)/1_000_000.0);
                output.appendLine("manager-memory-attempts.jsonl",log);
                return new Update(false,null,error,calls,hash);
            }
            log.put("valid",false); log.put("error",error); log.put("elapsedMillis",(System.nanoTime()-start)/1_000_000.0);
            output.appendLine("manager-memory-attempts.jsonl",log);
        }
        return new Update(false,null,error,calls,hash);
    }

    static Map<String,Object> schema() {
        return Map.of("type","object","properties",Map.of("memory",Map.of("type","string","minLength",1)),
                "required",List.of("memory"),"additionalProperties",false);
    }
    long attemptedCalls() { return attemptedCalls.get(); }
}
