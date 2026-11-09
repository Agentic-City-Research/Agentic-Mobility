package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MorningFactLedgerTest {
    @TempDir Path directory;
    private MorningMemory.Experience done(int day, String route, double late) {
        return new MorningMemory.Experience(day, route, 539.0, 28000.0, late, false, 26000.0, 1451.0);
    }
    @Test void countsDistinctExperiencesAndEnforcesCutoff() {
        var history = List.of(done(1,"c1",0),done(2,"c1",0),done(3,"c1",120),done(4,"c1",0));
        for (int cutoff = 1; cutoff <= 4; cutoff++) {
            var view = MorningFactLedger.build(history, cutoff);
            assertEquals(cutoff, view.routes().getFirst().completedTripCount());
            assertEquals(cutoff, view.routes().getFirst().observedTripCount());
            MorningFactLedger.validate(view, cutoff);
        }
        var day4Decision = MorningFactLedger.build(history, 3);
        assertEquals(3, day4Decision.routes().getFirst().completedTripCount());
        assertThrows(IllegalArgumentException.class, () -> MorningFactLedger.validate(day4Decision, 2));
        assertThrows(IllegalArgumentException.class, () -> MorningFactLedger.build(List.of(done(1,"c0",0),done(1,"c1",0)), 1));
    }
    @Test void unknownAndUnfinishedAreNotSuccessOrZero() {
        var unknown = new MorningMemory.Experience(0,"c0",539.0,null,null,false,26000.0,null);
        var stuck = new MorningMemory.Experience(1,"c0",null,null,null,true,26000.0,null);
        var stats = MorningFactLedger.build(List.of(unknown,stuck), 1).routes().getFirst();
        assertEquals(0, stats.completedTripCount());
        assertEquals(1, stats.incompleteTripCount());
        assertEquals(1, stats.unknownOutcomeCount());
        assertEquals(0, stats.latenessKnownCount());
        assertNull(stats.meanDoorSeconds());
        assertEquals(1, stats.carTimeKnownCount());
        assertEquals(539.0, stats.meanCarSeconds());
    }
    @Test void promptShowsOnlyStructuredVisibleFacts() {
        var view = MorningFactLedger.build(List.of(done(0,"c0",20)), 0);
        var rendered = new StringBuilder(); MorningFactLedger.append(rendered, view, 0);
        assertTrue(rendered.toString().contains("unique trips"));
        assertTrue(rendered.toString().contains("completedTripCount"));
        assertFalse(rendered.toString().contains("tollPaid"));
        assertThrows(IllegalArgumentException.class, () -> MorningFactLedger.append(new StringBuilder(), view, -1));
    }
    @Test void branchMasksAndReorderingPreserveExactlyVisibleLedger() {
        var experience = done(0, "c0", 20);
        var option = new MorningRoutePolicy.Option("c0", List.of("a", "b"), 600, 26000, 1000, 5, "synthetic", 0, 0);
        var view = MorningFactLedger.build(List.of(experience), 0);
        var context = new MorningRoutePolicy.Context(1, "trip", "person", "home", "work", 25200, 27000.0,
                Map.of(), Map.of(), List.of(option), List.of(experience), null, "note", 0, null, List.of(), view);
        var branch = V9ReasonablenessExperiments.branch(context, false, false, true);
        assertTrue(branch.history().isEmpty());
        assertEquals("", branch.memorySummary());
        assertTrue(new MorningPromptRenderer().render(branch).contains("STRUCTURED PERSONAL EXECUTION LEDGER"));
        assertNotNull(MorningPromptRenderer.order(branch, "reverse", 1).ledger());
        assertFalse(new MorningPromptRenderer().render(V9ReasonablenessExperiments.branch(context, true, true, false))
                .contains("STRUCTURED PERSONAL EXECUTION LEDGER"));
    }
    @Test void javaLoopPassesLedgerToBothStagesWithoutFutureFacts() throws Exception {
        var fixture = new MorningPricingTest(); fixture.directory = directory;
        var settings = new MorningExperiment(); settings.llmConfig.apiKey = "offline-test";
        settings.clientFactory = (config, usage) -> (role, system, user) -> {
            assertTrue(user.contains("STRUCTURED PERSONAL EXECUTION LEDGER"));
            if (role.equals("traveler_memory")) return new LlmResponse("{\"memory\":\"I remember my trip.\"}",0,0,0,0);
            return new LlmResponse(MorningJointTestSupport.withDeparture("{\"routeId\":\"c0\",\"reason\":\"Offline\"}", user),0,0,0,0);
        };
        Path out = directory.resolve("ledger-loop");
        assertEquals(0, new CommandLine(settings).execute("--config", fixture.writeScenario().toString(), "--output", out.toString(),
                "--policy", "llm", "--llm-agents", "all", "--days", "3", "--traveler-memory-variant", "ledger", "--traveler-reflection-interval", "1", "--max-requests", "12"));
        var rows = JsonlTestSupport.readLines(out.resolve("morning/ledger-views.jsonl.gz")).stream().map(Json::readTree).toList();
        assertEquals(6, rows.size());
        for (var row : rows) assertEquals(row.get("day").asInt() - (row.get("phase").asText().equals("decision") ? 1 : 0), row.at("/view/asOfDay").asInt());
        assertEquals("ledger", Json.readTree(Files.readString(out.resolve("morning/manifest.json"))).get("travelerMemoryVariant").asText());
    }
}
