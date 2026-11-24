package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class V9ReasonablenessExperimentsTest {
    @TempDir Path temp;

    @Test void syntheticCasesAndFaultInjectionProduceAuditWithoutNetwork() throws Exception {
        Path out=temp.resolve("b1");
        int result=new CommandLine(new V9ReasonablenessExperiments()).execute("--experiment","b1","--mode","mock","--output",out.toString());
        assertEquals(0,result);
        assertEquals(12,Files.readAllLines(out.resolve("b1/cases.jsonl")).size());
        var stage=Json.readTree(Files.readString(out.resolve("stages.jsonl")));
        assertTrue(stage.path("faultInjectionPassed").asBoolean());
        var manifest=Json.readTree(Files.readString(out.resolve("manifest.json")));
        assertFalse(manifest.path("mockIsBehavioralEvidence").asBoolean());
    }

    @Test void branchesRerenderFactsAndMemorySeparatelyAndExpireOldFact() {
        var option=new MorningRoutePolicy.Option("c0",List.of("origin","destination"),600,26000,1000,5,"synthetic",0,0);
        var event=new MorningMemory.Experience(0,"c0",1800.0,28000.0,100.0,false,25200.0,2000.0,0.0,25200.0,List.of());
        var grid=List.of(new MorningRoutePolicy.DepartureOption(25200,List.of(option)));
        var c=new MorningRoutePolicy.Context(1,"trip","person","home","work",25200,27000.0,Map.of(),Map.of(),List.of(option),List.of(event),null,"Remembered delay.",0,null,grid);
        var none=V9ReasonablenessExperiments.branch(c,false,false);
        var facts=V9ReasonablenessExperiments.branch(c,true,false);
        var memo=V9ReasonablenessExperiments.branch(c,false,true);
        assertNull(none.memorySummaryDay()); assertEquals("",none.memorySummary()); assertTrue(none.history().isEmpty());
        String factPrompt=new MorningPromptRenderer().render(facts), memoryPrompt=new MorningPromptRenderer().render(memo);
        assertTrue(factPrompt.contains("1800.0")); assertFalse(memoryPrompt.contains("1800.0"));
        assertTrue(memoryPrompt.contains("Remembered delay.")); assertFalse(factPrompt.contains("Remembered delay."));
        var day4=new MorningRoutePolicy.Context(4,c.tripId(),c.personId(),c.originPurpose(),c.destinationPurpose(),c.plannedDepartureSeconds(),c.latestArrivalSeconds(),c.personAttributes(),c.schedule(),c.candidates(),MorningPromptRenderer.recentFacts(c.history(),4,false,3),c.profile(),c.memorySummary(),3,c.information(),c.departureOptions());
        assertTrue(MorningPromptRenderer.recentFacts(day4.history(),4,false,3).isEmpty());
        assertFalse(new MorningPromptRenderer().render(day4).contains("1800.0"));
    }
}
