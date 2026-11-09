package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end contract for periodic traveler reflections and concurrent role memory. */
class MorningV2ReflectionTest {
    @TempDir Path directory;

    @Test void fiveDayReflectionsKeepAllInterveningFactsAndOverlapManager() throws Exception {
        var fixture = new MorningPricingTest(); fixture.directory=directory;
        var settings = new MorningExperiment(); settings.llmConfig.apiKey="offline-only";
        var entered = new CountDownLatch(2);
        var choices = new AtomicInteger(); var memories = new AtomicInteger();
        var managerMemories = new AtomicInteger(); var contextByDay = new ConcurrentHashMap<Integer,String>();
        var choiceByDay = new ConcurrentHashMap<Integer,String>();
        settings.clientFactory=(config,usage) -> (role,system,user) -> {
            if (role.equals("manager-memory")) {
                int d = Json.readTree(user).path("day").asInt(); managerMemories.incrementAndGet();
                if(d==4) awaitBoth(entered);
                return response("{\"memory\":\"Public traffic observed.\"}");
            }
            if (role.equals("traveler_memory")) {
                var day=java.util.regex.Pattern.compile("REFLECT AFTER SIMULATION DAY (\\d+)").matcher(user);
                if(!day.find()) throw new AssertionError("Missing reflection day");
                int d=Integer.parseInt(day.group(1)); contextByDay.put(d,user); memories.incrementAndGet();
                if(d==4) awaitBoth(entered);
                return response("{\"memory\":\"I remember my prior trips.\"}");
            }
            if(role.equals("manager")) throw new AssertionError("No manager policy in first eleven days");
            choices.incrementAndGet();
            var decisionDay=java.util.regex.Pattern.compile("SIMULATION DAY (\\d+)").matcher(user);
            assertTrue(decisionDay.find()); choiceByDay.put(Integer.parseInt(decisionDay.group(1)),user);
            var grid=java.util.regex.Pattern.compile("Allowed departureSeconds: (\\[[^\\n]+?\\])").matcher(user);
            if (!grid.find()) throw new AssertionError("Missing departure grid");
            var actual=new ArrayList<Double>(); Json.readTree(grid.group(1)).forEach(x -> actual.add(x.asDouble()));
            assertEquals(List.of(21600d,22500d,23400d,24300d,25200d,26100d),actual);
            String route=MorningJointTestSupport.firstRoute(user);
            return response(MorningJointTestSupport.withDeparture(Json.write(Map.of("routeId",route,"reason","Choose available route")),user));
        };
        var out=directory.resolve("v2");
        assertEquals(0,new CommandLine(settings).execute("--config",fixture.writeScenario().toString(),"--output",out.toString(),
                "--policy","llm","--llm-agents","all","--manager","--days","11","--concurrency","2",
                "--v2","--llm-stall-seconds","15","--max-requests","100"));
        assertEquals(11,choices.get()); assertEquals(2,memories.get()); assertEquals(11,managerMemories.get());
        var manifest = Json.readTree(java.nio.file.Files.readString(out.resolve("morning/manifest.json")));
        assertEquals(5, manifest.path("historyDays").asInt());
        assertEquals(Set.of(4,9),contextByDay.keySet()); assertEquals(0,entered.getCount(),"reflections must overlap");
        assertEquals(List.of(0,1,2,3), decisionDays(choiceByDay.get(4)), "First reflection must not hide the first day before its summary exists");
        assertEquals(List.of(0,1,2,3,4), decisionDays(choiceByDay.get(5)), "Five recent facts remain visible after reflection");
        assertEquals(List.of(4,5,6,7,8), decisionDays(choiceByDay.get(9)), "Five recent days include all unsummarized facts");
        var contexts=JsonlTestSupport.readLines(out.resolve("morning/memory-contexts.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(2,contexts.size());
        assertEquals(List.of(0,1,2,3,4),days(contexts.getFirst()));
        assertEquals(List.of(5,6,7,8,9),days(contexts.getLast()));
        assertEquals(4,contexts.getLast().path("previousMemoryDay").asInt());
        assertFalse(contexts.getLast().path("previousMemory").asText().isBlank());
    }
    @Test void explicitFiveDayHistoryOverridesDailyReflectionDuringValidation() throws Exception {
        var fixture = new MorningPricingTest(); fixture.directory=directory;
        var settings = new MorningExperiment();
        var out = directory.resolve("override");
        assertEquals(0,new CommandLine(settings).execute("--config",fixture.writeScenario().toString(),"--output",out.toString(),
                "--policy","mnl","--llm-agents","all","--days","2","--traveler-reflection-interval","1",
                "--history-days","5","--validate-only"));
        var manifest=Json.readTree(java.nio.file.Files.readString(out.resolve("morning/manifest.json")));
        assertEquals(5,manifest.path("historyDays").asInt());
    }
    @Test void managerReflectionFailureDoesNotPreventTravelerCommit() throws Exception {
        var fixture=new MorningPricingTest(); fixture.directory=directory;
        var settings=new MorningExperiment(); settings.llmConfig.apiKey="offline-only";
        settings.clientFactory=(config,usage) -> (role,system,user) -> {
            if(role.equals("manager-memory")) {
                if(Json.readTree(user).path("day").asInt()==4) throw new LlmException("offline failure");
                return response("{\"memory\":\"Previous public observations.\"}");
            }
            if(role.equals("traveler_memory")) return response("{\"memory\":\"My trip experience.\"}");
            if(role.equals("manager")) throw new AssertionError("Unexpected policy call");
            return response(MorningJointTestSupport.withDeparture(Json.write(Map.of("routeId",MorningJointTestSupport.firstRoute(user),"reason","Choice")),user));
        };
        var out=directory.resolve("manager-failure");
        assertEquals(0,new CommandLine(settings).execute("--config",fixture.writeScenario().toString(),"--output",out.toString(),
                "--policy","llm","--llm-agents","all","--manager","--days","6","--concurrency","2","--v2","--repair-attempts","1","--max-requests","80"));
        var updates=JsonlTestSupport.readLines(out.resolve("morning/memory-updates.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(1,updates.size()); assertEquals(4,updates.getFirst().path("day").asInt());
        assertTrue(updates.getFirst().path("committed").asBoolean());
        var manager=JsonlTestSupport.readLines(out.resolve("morning/manager-memory-updates.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals("retained_previous",manager.get(4).path("memoryStatus").asText());
    }
    private static List<Integer> days(com.fasterxml.jackson.databind.JsonNode context) {
        var result=new ArrayList<Integer>(); context.path("recentExperiences").forEach(x -> result.add(x.path("day").asInt())); return result;
    }
    private static List<Integer> decisionDays(String prompt) {
        return MorningJointTestSupport.tableRows(prompt,"YOUR MOST RECENT PERSONAL EXPERIENCES").stream().map(r -> r.get(0).asInt()).toList();
    }
    private static void awaitBoth(CountDownLatch latch) {
        latch.countDown();
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("Role memory calls did not overlap"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static LlmResponse response(String text) { return new LlmResponse(text,0,0,0,0); }
}
