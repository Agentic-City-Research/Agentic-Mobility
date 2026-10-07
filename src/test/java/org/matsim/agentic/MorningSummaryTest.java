package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class MorningSummaryTest {
    @TempDir Path directory;

    @Test void summaryCommitsAfterObservationAndSurvivesWindowWithoutChangingFactsOrOtherTrips() {
        var memory = new MorningMemory(5); var other = new MorningMemory(5);
        assertEquals("", memory.summary()); assertNull(memory.summaryDay());
        assertThrows(IllegalArgumentException.class, () -> memory.updateSummary(0, "Invented result"));
        for (int day = 0; day < 500; day++) {
            memory.chose("c0"); memory.observe(new MorningMemory.Experience(day, "c0", 100, 25300.0, 0.0));
            memory.updateSummary(day, "I retain an important event from day zero. Current day " + day);
        }
        assertEquals(500, memory.size()); assertEquals(500, memory.summaryVersion()); assertEquals(499, memory.summaryDay());
        assertTrue(memory.summary().contains("day zero")); assertEquals(495, memory.recent().getFirst().day());
        assertEquals(500, memory.route("c0").chosenCount()); assertEquals(500, memory.route("c0").completedCount());
        assertThrows(IllegalArgumentException.class, () -> memory.updateSummary(499, "duplicate"));
        assertThrows(IllegalArgumentException.class, () -> memory.updateSummary(500, "future"));
        assertEquals("", other.summary()); assertEquals(0, other.size());
    }

    @Test void routePromptCarriesSubjectiveNotesAndProfileButNeverSameDayMemory() {
        var c = enrichedContext(3, 2);
        var renderer = new MorningPromptRenderer(); String text = renderer.render(c);
        assertTrue(text.contains("important day-zero event")); assertTrue(text.contains("Last updated after simulation day 2"));
        assertTrue(text.contains("FIXED SYNTHETIC PROFILE")); assertTrue(text.contains("CONSERVATIVE"));
        assertTrue(text.contains("Required arrival by: 09:00:00")); assertTrue(text.contains("any arrival after it is still late"));
        assertFalse(text.contains("secret-person")); assertFalse(text.contains("secret-link"));
        assertEquals(c.memorySummary(), MorningPromptRenderer.order(c, "reverse", 4711).memorySummary());
        assertEquals(c.profile(), MorningPromptRenderer.order(c, "shuffle", 4711).profile());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(enrichedContext(3, 3)));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(enrichedContext(3, null)));
        var nonwork = new StringBuilder();
        MorningPromptRenderer.appendPersonalInformation(nonwork, Map.of(), c.profile(), "secondary");
        assertTrue(nonwork.toString().contains("do not create a work deadline"));
    }

    @Test void updaterUsesOldNotesAndActualIncompleteResultWithStrictRepairsAndNoLiveMutation() throws Exception {
        var memory = new MorningMemory(1); memory.chose("c0");
        memory.observe(new MorningMemory.Experience(0, "c0", null, null, null, true));
        var c = summaryContext(0, memory);
        AtomicInteger calls = new AtomicInteger();
        try (var writer = new OutputWriter(directory.resolve("repairs"))) {
            var updater = new MorningMemoryUpdater((r,s,u) -> {
                assertEquals("traveler_memory", r); assertTrue(u.contains("unknown (driving not completed)"));
                assertFalse(u.contains("predicted driving time")); assertFalse(u.contains("secret-link"));
                return new LlmResponse(calls.incrementAndGet() == 1 ? "{\"memory\":\"bad\",\"routeId\":\"c0\"}"
                        : "{\"memory\":\"I did not complete this trip; its duration is unknown.\"}", 10, 5, 0, 0);
            }, writer, 3, 200, Map.of("model", "fixture"), new MorningRequestBudget(10));
            var update = updater.update(c); assertEquals(1, update.repairCount());
            assertEquals("", memory.summary(), "Worker must not modify live state");
            memory.updateSummary(0, update.memory()); writer.flush();
            assertEquals(2, readLines(directory.resolve("repairs/memory-attempts.jsonl")).size());
            assertEquals(1, readLines(directory.resolve("repairs/memory-contexts.jsonl")).size());
            var log = Json.readTree(readLines(directory.resolve("repairs/memory-attempts.jsonl")).getLast());
            assertTrue(log.get("valid").asBoolean()); assertEquals(10, log.get("usage").get("promptTokens").asInt());
        }
    }

    @Test void reflectionUsesOnlyOldSummaryLastFiveFactsAndPersonalInformationNotLifetimeCounts() {
        var memory = new MorningMemory(1);
        for (int day = 0; day < 8; day++) {
            String route = day % 2 == 0 ? "c0" : "c1";
            memory.chose(route);
            memory.observe(new MorningMemory.Experience(day, route, 100 + day, 25300.0 + day, 0.0));
            if (day == 6) memory.updateSummary(day, "I retain an important event from day zero.");
        }
        var profile = new Profile(Profile.Income.LOW, Profile.DecisionStyle.CONSERVATIVE, Profile.WorkTimeliness.MUST_ON_TIME);
        var c = new MorningMemoryUpdater.Context(7, "secret-person/0", "secret-person", "work", 32400.0,
                Map.of("age", 35, "matsimScore", 999, "internalLink", "secret-link"), profile,
                memory.summary(), memory.summaryDay(), memory.recent(MorningMemoryUpdater.RECENT_EXPERIENCE_LIMIT), List.of("c0", "c1"));
        String prompt = MorningMemoryUpdater.render(c);
        assertEquals(1, memory.recent().size(), "Choice window remains independently configurable");
        assertEquals(5, c.recentExperiences().size());
        assertEquals(3, c.recentExperiences().getFirst().day());
        assertEquals(7, c.recentExperiences().getLast().day());
        assertTrue(prompt.contains("important event from day zero"));
        assertTrue(prompt.contains("Age: 35")); assertTrue(prompt.contains("FIXED SYNTHETIC PROFILE"));
        assertTrue(prompt.contains("32400.0 seconds after midnight"));
        assertTrue(prompt.contains("AVAILABLE ROUTE IDENTIFIERS FOR THIS TRIP: [\"c0\",\"c1\"]"));
        assertFalse(prompt.contains("Simulation day 2, route"));
        assertTrue(prompt.contains("Simulation day 7, route c1"));
        assertFalse(prompt.contains("CUMULATIVE")); assertFalse(prompt.contains("completed driving"));
        assertFalse(prompt.contains("secret-person")); assertFalse(prompt.contains("secret-link"));
        assertFalse(prompt.contains("predicted driving time")); assertFalse(prompt.contains("matsimScore"));
        var saved = Json.readTree(Json.write(c));
        assertFalse(saved.has("routeUsage"));
        assertFalse(Json.write(c).contains("chosenCount")); assertFalse(Json.write(c).contains("completedCount"));
        for (int i = 0; i < 100; i++) memory.chose("c0");
        assertEquals(prompt, MorningMemoryUpdater.render(c), "Analysis-only counts cannot affect the reflection prompt");
        assertEquals(8, memory.all().size()); assertEquals(0, memory.all().getFirst().day());
        assertThrows(IllegalArgumentException.class, () -> memory.recent(0));
        var tooMany = new MorningMemoryUpdater.Context(7, "p/0", "p", "work", 32400.0, Map.of(), null,
                memory.summary(), memory.summaryDay(), memory.recent(6), List.of("c0", "c1"));
        assertThrows(IllegalArgumentException.class, () -> MorningMemoryUpdater.render(tooMany));
        var wrongPool = new MorningMemoryUpdater.Context(7, "p/0", "p", "work", 32400.0, Map.of(), null,
                memory.summary(), memory.summaryDay(), memory.recent(5), List.of("c0"));
        assertThrows(IllegalArgumentException.class, () -> MorningMemoryUpdater.render(wrongPool));
    }

    @Test void summaryValidationRejectsFutureResultsAndBadJsonWithoutChangingState() throws Exception {
        var memory = new MorningMemory(1); memory.chose("c0");
        memory.observe(new MorningMemory.Experience(0, "c0", 100, 25300.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> MorningMemoryUpdater.render(summaryContext(1, memory)));
        try (var writer = new OutputWriter(directory.resolve("invalid"))) {
            for (String bad : List.of("{\"memory\":\"\"}", "{\"memory\":\"123456789\"}",
                    "{\"memory\":\"a\",\"memory\":\"b\"}", "{\"memory\":\"a\"}{\"memory\":\"b\"}", "```json\n{\"memory\":\"a\"}\n```")) {
                var updater = new MorningMemoryUpdater((r,s,u) -> new LlmResponse(bad, 0,0,0,0), writer, 1, 8, Map.of(), new MorningRequestBudget(5));
                assertThrows(LlmException.class, () -> updater.update(summaryContext(0, memory)));
                assertEquals("", memory.summary());
            }
            var updater = new MorningMemoryUpdater((r,s,u) -> { throw new LlmTransportException("fixture", null); },
                    writer, 3, 100, Map.of(), new MorningRequestBudget(5));
            assertThrows(LlmTransportException.class, () -> updater.update(summaryContext(0, memory)));
            writer.flush();
            var failed = Json.readTree(readLines(directory.resolve("invalid/memory-attempts.jsonl")).getLast());
            assertEquals("call", failed.get("failureKind").asText()); assertTrue(failed.get("usage").isNull());
        }
    }

    @Test void summaryTargetIsSoftAndFiveHundredUnicodeCharactersIsTheHardLimit() throws Exception {
        var memory = new MorningMemory(5); memory.chose("c0");
        memory.observe(new MorningMemory.Experience(0, "c0", 100, 25300.0, 0.0));
        try (var writer = new OutputWriter(directory.resolve("length-limits"))) {
            String withinLimit = "😀".repeat(500);
            var updater = new MorningMemoryUpdater((r,s,u) -> {
                assertTrue(s.contains("240 characters")); assertTrue(s.contains("Keep memory within 500 characters"));
                return new LlmResponse(Json.write(Map.of("memory", withinLimit)), 0,0,0,0);
            }, writer, 1, 500, Map.of(), new MorningRequestBudget(5));
            assertEquals(withinLimit, updater.update(summaryContext(0, memory)).memory(), "240 is a target, not an acceptance cutoff");
            var tooLong = new MorningMemoryUpdater((r,s,u) -> new LlmResponse(Json.write(Map.of("memory", "😀".repeat(501))), 0,0,0,0),
                    writer, 1, 500, Map.of(), new MorningRequestBudget(5));
            assertThrows(LlmException.class, () -> tooLong.update(summaryContext(0, memory)));
            assertThrows(IllegalArgumentException.class, () -> new MorningMemoryUpdater((r,s,u) -> null,
                    writer, 1, 501, Map.of(), new MorningRequestBudget(5)));
            writer.flush();
            var rows = readLines(directory.resolve("length-limits/memory-attempts.jsonl")).stream().map(Json::readTree).toList();
            assertEquals(500, rows.getFirst().get("memoryCharacters").asInt());
            assertTrue(rows.getFirst().get("valid").asBoolean());
            assertEquals(501, rows.getLast().get("memoryCharacters").asInt());
            assertFalse(rows.getLast().get("valid").asBoolean());
        }
    }

    @Test void choiceAndReflectionShareOneBudgetIncludingRepairs() throws Exception {
        var budget = new MorningRequestBudget(2); var calls = new AtomicInteger();
        var memory = new MorningMemory(1); memory.chose("c0"); memory.observe(new MorningMemory.Experience(0, "c0", 100, 25300.0, 0.0));
        try (var writer = new OutputWriter(directory.resolve("budget"))) {
            var choice = new MorningLlmPolicy((r,s,u) -> { calls.incrementAndGet();
                return new LlmResponse("{\"routeId\":\"c0\",\"reason\":\"fixture\"}", 0,0,0,0);
            }, writer, 3, 240, Map.of(), budget);
            choice.choose(MorningFactsTest.context());
            var updater = new MorningMemoryUpdater((r,s,u) -> { calls.incrementAndGet(); return new LlmResponse("{\"memory\":\"\"}", 0,0,0,0); },
                    writer, 3, 100, Map.of(), budget);
            assertThrows(LlmException.class, () -> updater.update(summaryContext(0, memory)));
            assertEquals(2, calls.get()); assertEquals(2, budget.attemptedCalls());
            assertEquals(1, choice.attemptedCalls()); assertEquals(1, updater.attemptedCalls());
        }
    }

    @Test void legacyProfileImportsAndGeneratedAssignmentsAreReproducible() throws Exception {
        var profile = new Profile(Profile.Income.LOW, Profile.DecisionStyle.CONSERVATIVE, Profile.WorkTimeliness.MUST_ON_TIME);
        assertEquals(MorningProfiles.sample("p", 4711), MorningProfiles.sample("p", 4711));
        var file = directory.resolve("legacy-roster.json");
        Files.writeString(file, Json.write(List.of(Map.of("person_id", "p", "profile", profile, "informed", true))));
        assertEquals(profile, MorningProfiles.read(file).get("p"));
        try (var writer = new OutputWriter(directory.resolve("profiles"), true)) {
            writer.appendLine("profiles.jsonl", Map.of("personId", "p", "profile", profile));
        }
        assertEquals(profile, MorningProfiles.read(directory.resolve("profiles/profiles.jsonl.gz")).get("p"));
        Files.writeString(file, "[{\"person_id\":\"p\",\"profile\":{\"income\":\"LOW\"}}]");
        assertThrows(IllegalArgumentException.class, () -> MorningProfiles.read(file));
    }

    @Test void enrichedReplayPreservesSummaryAndProfileWithoutCallingReflection() throws Exception {
        var c = enrichedContext(3, 2); var file = directory.resolve("contexts.jsonl");
        Files.writeString(file, Json.write(c) + "\n"); Path out = directory.resolve("replay");
        assertEquals(0, new CommandLine(new MorningReplay()).execute("--input", file.toString(), "--output", out.toString(), "--mock", "--limit", "1"));
        var saved = Json.readTree(readLines(out.resolve("source-contexts.jsonl.gz")).getFirst());
        assertEquals(c.memorySummary(), saved.get("memorySummary").asText());
        assertEquals("LOW", saved.get("profile").get("income").asText());
        assertFalse(Json.readTree(Files.readString(out.resolve("manifest.json"))).get("memoryUpdated").asBoolean());
    }

    @Test void sevenDayClosedLoopCarriesEachTripsPreviousSummaryAndFixedProfile() throws Exception {
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = "offline-fixture";
        var decisions = new AtomicInteger(); var reflections = new AtomicInteger();
        settings.clientFactory = (config, usage) -> (r,s,u) -> {
            String content;
            if (r.equals("traveler_memory")) {
                int n = reflections.incrementAndGet();
                var day = Pattern.compile("REFLECT AFTER SIMULATION DAY (\\d+)").matcher(u); assertTrue(day.find());
                content = Json.write(Map.of("memory", "Retained important earlier event; day " + day.group(1) + ", note " + n));
                assertEquals(1024, config.maxOutputTokens); assertFalse(u.contains("predicted driving time"));
            } else {
                decisions.incrementAndGet();
                var route = Pattern.compile("- Route ([A-Za-z0-9_-]+): predicted").matcher(u); assertTrue(route.find());
                content = Json.write(Map.of("routeId", route.group(1), "reason", "Offline plumbing fixture, not model evidence"));
            }
            var response = new LlmResponse(MorningJointTestSupport.withDeparture(content, u), 10, 5, 0, 0); usage.record(response); return response;
        };
        Path out = directory.resolve("closed-loop");
        assertEquals(0, new CommandLine(settings).execute("--policy", "llm", "--llm-agents", "3", "--demand", "100",
                "--sample-persons", "40", "--days", "7", "--seed", "4711", "--concurrency", "2", "--max-requests", "100",
                "--response-format", "json_schema", "--history-window", "2", "--trip-output-interval", "3", "--output", out.toString()));
        var updates = new HashMap<String, String>();
        for (String line : readLines(out.resolve("morning/memory-updates.jsonl.gz"))) {
            var row = Json.readTree(line); int day = row.get("day").asInt(); String trip = row.get("tripId").asText();
            assertTrue(row.get("committed").asBoolean()); assertEquals(day + 1, row.get("version").asInt());
            assertEquals(day == 0 ? "" : updates.get((day-1) + "/" + trip), row.get("previousMemory").asText());
            updates.put(day + "/" + trip, row.get("memory").asText());
        }
        var profiles = new HashMap<String, String>();
        var fixedPools = new HashMap<String, String>();
        for (String line : readLines(out.resolve("morning/decision-contexts.jsonl.gz"))) {
            var row = Json.readTree(line); int day = row.get("day").asInt(); String trip = row.get("tripId").asText();
            assertEquals(day == 0 ? "" : updates.get((day-1) + "/" + trip), row.get("memorySummary").asText());
            if (day > 0) assertEquals(day-1, row.get("memorySummaryDay").asInt());
            assertEquals(Math.min(day, 2), row.get("history").size());
            String person = row.get("personId").asText(), profile = row.get("profile").toString();
            assertEquals(profiles.computeIfAbsent(person, k -> profile), profile);
            var paths = new LinkedHashMap<String, String>();
            for (var candidate : row.get("candidates")) paths.put(candidate.get("routeId").asText(), candidate.get("links").toString());
            String pool = Json.write(paths);
            assertEquals(fixedPools.computeIfAbsent(trip, k -> pool), pool, "Candidate identifiers must keep the same paths across days");
            for (var experience : row.get("history")) assertTrue(experience.get("day").asInt() < day);
        }
        assertEquals(decisions.get(), reflections.get()); assertEquals(21, reflections.get());
        for (String line : readLines(out.resolve("morning/memory-contexts.jsonl.gz"))) {
            var row = Json.readTree(line); int day = row.get("day").asInt(); String trip = row.get("tripId").asText();
            assertFalse(row.has("routeUsage"));
            var recent = row.get("recentExperiences");
            assertEquals(Math.min(day + 1, 5), recent.size());
            assertEquals(Math.max(0, day - 4), recent.get(0).get("day").asInt());
            assertEquals(day, recent.get(recent.size() - 1).get("day").asInt());
            assertEquals(day == 0 ? "" : updates.get((day - 1) + "/" + trip), row.get("previousMemory").asText());
            assertTrue(row.get("availableRouteIds").size() > 0);
        }
        for (String line : readLines(out.resolve("morning/attempts.jsonl.gz"))) {
            var row = Json.readTree(line);
            assertEquals(row.get("outputSchema"), row.at("/modelSettings/generation/response_format/json_schema/schema"));
            assertEquals(row.get("candidateCount").asInt(), row.at("/outputSchema/properties/routeId/enum").size());
        }
        for (String line : readLines(out.resolve("morning/memory-attempts.jsonl.gz"))) {
            var row = Json.readTree(line);
            assertTrue(row.at("/outputSchema/properties").has("memory"));
            assertFalse(row.at("/outputSchema/properties").has("routeId"));
            assertFalse(row.get("user").asText().contains("CUMULATIVE FACTUAL ROUTE USE"));
            assertEquals(MorningMemoryUpdater.VERSION, row.get("templateVersion").asText());
        }
        for (String line : readLines(out.resolve("morning/attempts.jsonl.gz"))) {
            var row = Json.readTree(line);
            assertFalse(row.get("user").asText().contains("YOUR LONG-TERM ROUTE USE"));
            assertEquals(MorningPromptRenderer.VERSION, row.get("templateVersion").asText());
        }
        for (String line : readLines(out.resolve("morning/metrics.jsonl.gz"))) {
            var row = Json.readTree(line); var controlled = row.at("/groups/controlled");
            assertEquals(controlled.get("cohortTrips").asInt(), controlled.get("singleCandidateTrips").asInt() + controlled.get("multiCandidateTrips").asInt());
            if (row.get("day").asInt() == 0) assertTrue(controlled.get("dsrMultiCandidate").isNull());
        }
        var usage = Json.readTree(Files.readString(out.resolve("morning/api-usage.json")));
        assertEquals(42, usage.get("attemptedTotalCalls").asInt()); assertEquals(42, usage.get("requests").asInt());
        assertEquals(3, readLines(out.resolve("morning/profiles.jsonl.gz")).size());
        for (String line : readLines(out.resolve("morning/memory-final.jsonl.gz"))) {
            var row = Json.readTree(line); assertEquals(7, row.get("version").asInt()); assertEquals(7, row.get("experiences").asInt());
        }
        for (String line : readLines(out.resolve("morning/route-use.jsonl.gz"))) {
            var row = Json.readTree(line); assertEquals(7, row.get("experiences").asInt());
            int choices = 0; for (var use : row.get("routes")) choices += use.get("chosenCount").asInt();
            assertEquals(7, choices, "Cumulative counts remain available for analysis, not model input");
        }
        var manifest = Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
        assertEquals(5, manifest.get("memoryReflectionExperienceLimit").asInt());
        assertFalse(manifest.get("cumulativeRouteCountsSuppliedToLlm").asBoolean());
        assertEquals(6, manifest.get("memoryReflectionInputs").size());
        assertEquals(240, manifest.get("memoryTargetChars").asInt());
        assertEquals(500, manifest.get("memoryMaxChars").asInt());
        assertFalse(manifest.get("memorySemanticCorrectionEnabled").asBoolean());
        assertFalse(manifest.get("memoryRecoveryEnabled").asBoolean());
        assertTrue(Json.readTree(Files.readString(out.resolve("morning/status.json"))).get("complete").asBoolean());
    }

    @Test void failedReflectionStopsExperimentAndNeverCommitsSummary() throws Exception {
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = "offline-fixture";
        var reflectionCalls = new AtomicInteger();
        settings.clientFactory = (config, usage) -> (r,s,u) -> {
            String content;
            if (r.equals("traveler_memory")) content = reflectionCalls.incrementAndGet() == 1
                    ? "{\"memory\":\"Valid proposal from one member\"}" : "{\"memory\":\"\"}";
            else { var route = Pattern.compile("- Route ([A-Za-z0-9_-]+): predicted").matcher(u); assertTrue(route.find());
                content = Json.write(Map.of("routeId", route.group(1), "reason", "Fixture")); }
            var response = new LlmResponse(MorningJointTestSupport.withDeparture(content, u), 0,0,0,0); usage.record(response); return response;
        };
        Path out = directory.resolve("failed-reflection");
        assertNotEquals(0, new CommandLine(settings).execute("--policy", "llm", "--llm-agents", "2", "--demand", "100",
                "--sample-persons", "40", "--days", "2", "--seed", "4711", "--concurrency", "1", "--repair-attempts", "1", "--output", out.toString()));
        var status = Json.readTree(Files.readString(out.resolve("morning/status.json")));
        assertFalse(status.get("complete").asBoolean()); assertEquals(0, status.get("daysCompleted").asInt());
        assertFalse(Files.exists(out.resolve("morning/memory-updates.jsonl.gz")));
        for (String line : readLines(out.resolve("morning/memory-final.jsonl.gz")))
            assertEquals("", Json.readTree(line).get("memory").asText());
        assertEquals(2, reflectionCalls.get());
    }

    @Test void unsupportedMemoryModeStopsBeforeLoadingScenarioOrCallingModel() {
        var settings = new MorningExperiment();
        settings.clientFactory = (config, usage) -> { fail("Rejected memory mode must not create a model client"); return null; };
        Path out = directory.resolve("rejected-memory-mode");
        var errors = new java.io.StringWriter();
        var command = new CommandLine(settings).setErr(new java.io.PrintWriter(errors));
        assertNotEquals(0, command.execute("--memory-mode", "none", "--config",
                directory.resolve("missing-config.xml").toString(), "--output", out.toString()));
        assertTrue(errors.toString().contains("--memory-mode only supports llm"));
        assertFalse(Files.exists(out));
    }

    @Test void llmMemoryReflectsEveryDayAndCanReuseProfiles() throws Exception {
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = "offline-fixture";
        var calls = new AtomicInteger();
        var reflections = new AtomicInteger();
        settings.clientFactory = (config, usage) -> (r,s,u) -> {
            String content;
            if (r.equals("traveler_memory")) {
                reflections.incrementAndGet();
                content = Json.write(Map.of("memory", "I retain my latest travel experience."));
            } else {
                assertEquals("traveler", r); calls.incrementAndGet();
                var route = Pattern.compile("- Route ([A-Za-z0-9_-]+): predicted").matcher(u); assertTrue(route.find());
                content = Json.write(Map.of("routeId", route.group(1), "reason", "Fixture"));
            }
            var response = new LlmResponse(MorningJointTestSupport.withDeparture(content, u), 0,0,0,0);
            usage.record(response); return response;
        };
        Path out = directory.resolve("llm-memory");
        assertEquals(0, new CommandLine(settings).execute("--policy", "llm", "--llm-agents", "1", "--demand", "100",
                "--sample-persons", "40", "--days", "2", "--seed", "4711", "--memory-mode", "llm", "--output", out.toString()));
        assertEquals(2, calls.get()); assertEquals(2, reflections.get());
        assertEquals(2, readLines(out.resolve("morning/memory-updates.jsonl.gz")).size());
        var manifest = Json.readTree(Files.readString(out.resolve("morning/manifest.json")));
        assertEquals("llm", manifest.get("memoryMode").asText());
        assertTrue(manifest.get("selfWrittenMemoryEnabled").asBoolean()); assertEquals(2, manifest.get("llmCallsPerControlledTripDay").asInt());
        var imported = MorningProfiles.read(out.resolve("morning/profiles.jsonl.gz"));
        Path reused = directory.resolve("reuse-profiles");
        assertEquals(0, new CommandLine(new MorningExperiment()).execute("--policy", "mock", "--llm-agents", "1", "--demand", "100",
                "--sample-persons", "40", "--days", "1", "--seed", "4711", "--profile-seed", "99",
                "--profile-file", out.resolve("morning/profiles.jsonl.gz").toString(), "--validate-only", "--output", reused.toString()));
        assertEquals(imported, MorningProfiles.read(reused.resolve("morning/profiles.jsonl.gz")));
    }

    private static MorningRoutePolicy.Context enrichedContext(int day, Integer summaryDay) {
        var c = MorningFactsTest.context();
        return new MorningRoutePolicy.Context(day, c.tripId(), c.personId(), c.originPurpose(), c.destinationPurpose(),
                c.plannedDepartureSeconds(), c.latestArrivalSeconds(), c.personAttributes(), c.schedule(), c.candidates(), c.history(),
                new Profile(Profile.Income.LOW, Profile.DecisionStyle.CONSERVATIVE, Profile.WorkTimeliness.SLIGHT_LATE),
                "I retain an important day-zero event.", summaryDay);
    }
    private static MorningMemoryUpdater.Context summaryContext(int day, MorningMemory memory) {
        return new MorningMemoryUpdater.Context(day, "p/0", "p", "work", 32400.0, Map.of(), null,
                memory.summary(), memory.summaryDay(), memory.recent(5), List.of("c0"));
    }
}
