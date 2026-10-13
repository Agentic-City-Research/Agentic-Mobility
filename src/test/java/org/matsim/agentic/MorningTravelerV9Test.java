package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class MorningTravelerV9Test {
    @TempDir Path directory;

    @Test void decisionAndReflectionWindowsAreThreeDaysButOldMemorySurvives() {
        var c=MorningFactsTest.context(); var facts=new ArrayList<MorningMemory.Experience>();
        for(int day=0;day<8;day++) facts.add(new MorningMemory.Experience(day,"c0",100,25300.0,0.0));
        var context=new MorningRoutePolicy.Context(8,c.tripId(),c.personId(),c.originPurpose(),c.destinationPurpose(),
                c.plannedDepartureSeconds(),c.latestArrivalSeconds(),c.personAttributes(),c.schedule(),c.candidates(),facts,
                null,"I remember an important event from day zero.",7,null);
        String decision=new MorningPromptRenderer().render(context);
        var rows=MorningJointTestSupport.tableRows(decision,"YOUR MOST RECENT PERSONAL EXPERIENCES");
        assertEquals(List.of(5,6,7),rows.stream().map(r -> r.get(0).asInt()).toList());
        assertTrue(decision.contains(context.memorySummary()));
        var recent=new ArrayList<>(facts.subList(6,8));recent.add(new MorningMemory.Experience(8,"c0",200,25400.0,0.0));
        var memory=new MorningMemoryUpdater.Context(8,c.tripId(),c.personId(),"work",32400.0,Map.of(),null,
                context.memorySummary(),7,recent,List.of("c0","c1"));
        String reflection=MorningMemoryUpdater.render(memory);
        assertEquals(List.of(6,7,8),MorningJointTestSupport.tableRows(reflection,"RECENT ACTUAL EXPERIENCES").stream().map(r -> r.get(0).asInt()).toList());
        assertTrue(reflection.contains(context.memorySummary()));
        assertTrue(MorningMemoryUpdater.SYSTEM.contains("Do not restart memory from zero"));
        assertTrue(new MorningPromptRenderer().system(120,true).contains("unnecessary formatting whitespace"));
    }

    @Test void blankAndTruncatedChoicesHaveThreeTotalAttemptsWithWhitespaceAudit() throws Exception {
        var calls=new AtomicInteger();
        try(var writer=new OutputWriter(directory)) {
            var policy=new MorningLlmPolicy((r,s,u) -> {
                calls.incrementAndGet(); return new LlmResponse(" ".repeat(1024),0,1024,0,0,true,"length",0);
            },writer,3);
            assertThrows(LlmException.class,() -> policy.choose(MorningFactsTest.context())); assertEquals(3,calls.get());
            writer.flush(); var logs=JsonlTestSupport.readLines(directory.resolve("attempts.jsonl")).stream().map(Json::readTree).toList();
            assertEquals(3,logs.size()); assertEquals(1,logs.getFirst().get("outputWhitespaceRatio").asDouble());
            assertTrue(logs.getFirst().get("blankOutput").asBoolean()); assertFalse(logs.getFirst().get("valid").asBoolean());
        }
        assertThrows(IllegalArgumentException.class,() -> MorningOutputContract.requireNotTruncated(new LlmResponse("\n\t ",0,0,0,0)));
    }

    @Test void oneReviewCanCorrectReasonAfterStructuralRepairWithoutChangingRoute() throws Exception {
        var baseCalls=new AtomicInteger(); var reviewCalls=new AtomicInteger(); var budget=new MorningRequestBudget(3);
        try(var writer=new OutputWriter(directory)) {
            LlmClient client=(r,s,u) -> {
                if(r.equals("traveler")) return response(baseCalls.incrementAndGet()==1?"{":"{\"routeId\":\"c0\",\"reason\":\"My deadline is 10:00\"}");
                assertEquals("traveler_review",r); reviewCalls.incrementAndGet();
                var input=Json.readTree(u); String facts=input.get("visibleFacts").asText();
                assertFalse(facts.contains("SELF-WRITTEN LONG-TERM MEMORY"));
                String evidence=facts.lines().filter(l -> l.startsWith("Required arrival by:")).findFirst().orElseThrow();
                return review("corrected",input.get("proposedOutput"),"My deadline is 10:00",evidence,"My deadline is 09:00","reason");
            };
            var policy=new MorningLlmPolicy(client,writer,3,120,Map.of(),budget);
            policy.setSemanticReview(new MorningSemanticReview(client,writer,budget,Map.of()));
            var chosen=policy.choose(MorningFactsTest.context());
            assertEquals("c0",chosen.routeId()); assertEquals("My deadline is 09:00",chosen.reason());
            assertEquals(2,baseCalls.get()); assertEquals(1,reviewCalls.get()); assertEquals(3,budget.attemptedCalls());
            writer.flush();var log=Json.readTree(JsonlTestSupport.readLines(directory.resolve("semantic-reviews.jsonl")).getFirst());
            assertEquals("My deadline is 10:00",log.at("/originalOutput/reason").asText());
            assertEquals("corrected",log.get("reviewStatus").asText());assertTrue(log.get("revisionApplied").asBoolean());
        }
    }

    @Test void illegalReviewActionAndUngroundedEvidenceRetainTheOriginalLegalChoice() throws Exception {
        for(boolean illegalAction:List.of(true,false)) {
            var folder=directory.resolve("review-"+illegalAction);
            try(var writer=new OutputWriter(folder)) {
                var budget=new MorningRequestBudget(2);
                LlmClient client=(r,s,u) -> {
                    if(r.equals("traveler")) return response("{\"routeId\":\"c0\",\"reason\":\"My deadline is 10:00\"}");
                    var input=Json.readTree(u);var proposed=input.get("proposedOutput").deepCopy();
                    String evidence=illegalAction?input.get("visibleFacts").asText().lines().filter(l -> l.startsWith("Required arrival by:")).findFirst().orElseThrow():"NOT_VISIBLE_NETWORK_FACT";
                    if(illegalAction) ((com.fasterxml.jackson.databind.node.ObjectNode)proposed).put("routeId","c1");
                    return review("corrected",proposed,"My deadline is 10:00",evidence,"My deadline is 09:00","reason");
                };
                var policy=new MorningLlmPolicy(client,writer,3,120,Map.of(),budget);
                policy.setSemanticReview(new MorningSemanticReview(client,writer,budget,Map.of()));
                var chosen=policy.choose(MorningFactsTest.context());
                assertEquals("c0",chosen.routeId());assertEquals("My deadline is 10:00",chosen.reason());
                writer.flush();var log=Json.readTree(JsonlTestSupport.readLines(folder.resolve("semantic-reviews.jsonl")).getFirst());
                assertEquals("review_failed",log.get("reviewStatus").asText());assertTrue(log.get("semanticUnresolved").asBoolean());
            }
        }
    }

    @Test void unresolvedMemoryIsFlaggedAndOlderUnverifiableExperienceIsRetained() throws Exception {
        for(boolean unresolved:List.of(false,true)) {
            var folder=directory.resolve("memory-"+unresolved);var budget=new MorningRequestBudget(2);
            try(var writer=new OutputWriter(folder)) {
                String notes=unresolved?"My deadline is 10:00":"I retain a useful experience from day zero.";
                LlmClient client=(r,s,u) -> {
                    if(r.equals("traveler_memory")) return response(Json.write(Map.of("memory",notes)));
                    assertEquals("traveler_memory_review",r); var input=Json.readTree(u);
                    assertEquals("I retain an older useful event.",input.get("previousMemory").asText());
                    assertFalse(input.get("visibleFacts").asText().contains("I retain an older useful event."));
                    if(!unresolved) return response(Json.write(Map.of("status","no_conflict","conflicts",List.of(),"output",input.get("proposedOutput"))));
                    String evidence=input.get("visibleFacts").asText().lines().filter(l -> l.startsWith("The fixed required arrival time")).findFirst().orElseThrow();
                    return review("unresolved",input.get("proposedOutput"),notes,evidence,notes,"memory");
                };
                var updater=new MorningMemoryUpdater(client,writer,3,500,Map.of(),budget);
                updater.setSemanticReview(new MorningSemanticReview(client,writer,budget,Map.of()));
                var update=updater.update(memoryContext()); assertEquals(notes,update.memory());
                assertEquals(unresolved?"unresolved":"no_conflict",update.semanticReviewStatus()); assertEquals(2,budget.attemptedCalls());
            }
        }
    }

    @Test void unavailableReviewBudgetDoesNotInventAChoiceOrAbortLegalOutput() throws Exception {
        try(var writer=new OutputWriter(directory)) {
            var budget=new MorningRequestBudget(1);var calls=new AtomicInteger();
            LlmClient client=(r,s,u) -> {calls.incrementAndGet();return response("{\"routeId\":\"c0\",\"reason\":\"My choice\"}");};
            var policy=new MorningLlmPolicy(client,writer,3,120,Map.of(),budget);
            policy.setSemanticReview(new MorningSemanticReview(client,writer,budget,Map.of()));
            assertEquals("c0",policy.choose(MorningFactsTest.context()).routeId());assertEquals(1,calls.get());
            writer.flush(); var log=Json.readTree(JsonlTestSupport.readLines(directory.resolve("semantic-reviews.jsonl")).getFirst());
            assertFalse(log.get("reviewCallMade").asBoolean());assertEquals("review_failed",log.get("reviewStatus").asText());
        }
    }

    @Test void optionalReviewWiresBothStagesAndCountsItsBudgetInARealFourDayLoop() throws Exception {
        var fixture=new MorningPricingTest();fixture.directory=directory;
        var settings=new MorningExperiment();settings.llmConfig.apiKey="offline-test";
        var calls=new AtomicInteger();var reviewCalls=new AtomicInteger();
        settings.clientFactory=(config,usage) -> (role,system,user) -> {
            calls.incrementAndGet();
            if(role.endsWith("_review")) {
                reviewCalls.incrementAndGet();assertEquals(2048,config.maxOutputTokens);
                return response(Json.write(Map.of("status","no_conflict","conflicts",List.of(),"output",Json.readTree(user).get("proposedOutput"))));
            }
            assertEquals(1024,config.maxOutputTokens);
            if(role.equals("traveler_memory")) return response("{\"memory\":\"I retain useful prior experience and revise it from today's outcome.\"}");
            return response(MorningJointTestSupport.withDeparture("{\"routeId\":\"c0\",\"reason\":\"Offline choice\"}",user));
        };
        Path out=directory.resolve("loop");
        assertEquals(0,new CommandLine(settings).execute("--config",fixture.writeScenario().toString(),"--output",out.toString(),
                "--policy","llm","--llm-agents","all","--days","4","--traveler-semantic-review","--max-requests","16"));
        assertEquals(16,calls.get());assertEquals(8,reviewCalls.get());
        var manifest=Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
        assertTrue(manifest.get("memorySemanticCorrectionEnabled").asBoolean()); assertEquals(3,manifest.get("historyDays").asInt());
        assertEquals(120,manifest.at("/llmSettings/reasonMaxChars").asInt());assertEquals(23,manifest.get("schemaVersion").asInt());
        var usage=Json.readTree(Files.readString(out.resolve("morning/api-usage.json")));
        assertEquals(8,usage.get("attemptedTravelerSemanticReviewCalls").asInt());assertEquals(16,usage.get("attemptedTotalCalls").asInt());
        assertEquals(8,JsonlTestSupport.readLines(out.resolve("morning/semantic-reviews.jsonl.gz")).size());
    }

    private static MorningMemoryUpdater.Context memoryContext() {
        return new MorningMemoryUpdater.Context(8,"p/0","p","work",32400.0,Map.of(),null,"I retain an older useful event.",7,
                List.of(new MorningMemory.Experience(8,"c0",100,25300.0,0.0)),List.of("c0"));
    }
    private static LlmResponse review(String status,com.fasterxml.jackson.databind.JsonNode original,String claim,String evidence,String revised,String field) {
        var output=original.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)output).put(field,revised);
        return response(Json.write(Map.of("status",status,"conflicts",List.of(Map.of("claimQuote",claim,"evidenceQuote",evidence,"explanation","Explicit contradiction with the supplied fact")),"output",output)));
    }
    private static LlmResponse response(String value){return new LlmResponse(value,0,0,0,0);}
}
