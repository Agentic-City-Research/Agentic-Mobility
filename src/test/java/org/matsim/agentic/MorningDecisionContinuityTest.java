package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MorningDecisionContinuityTest {
    @Test void decisionHistoryKeepsRecentAndEveryUnsummarizedFactWithoutDuplicates() {
        var memory=new MorningMemory(3);
        assertEquals(List.of(),days(MorningPromptRenderer.decisionFacts(memory,0,3,5)));
        for(int day=0;day<10;day++) {
            var visible=MorningPromptRenderer.decisionFacts(memory,day,3,5);
            if(day==4) assertEquals(List.of(0,1,2,3),days(visible));
            if(day==5) assertEquals(List.of(2,3,4),days(visible));
            if(day==9) assertEquals(List.of(5,6,7,8),days(visible));
            assertEquals(visible.size(),new HashSet<>(days(visible)).size());
            int currentDay=day;
            assertTrue(visible.stream().allMatch(e -> e.day()<currentDay));
            memory.observe(new MorningMemory.Experience(day,"c0",100,25200.0,0.0));
            if(day==4 || day==9) memory.updateSummary(day,"Summary through day "+day);
        }
    }
    @Test void sparseExperiencesAndDailyReflectionKeepConfiguredRecentWindow() {
        var memory=new MorningMemory(3);
        memory.observe(new MorningMemory.Experience(7,"c0",100,25200.0,0.0));
        memory.observe(new MorningMemory.Experience(9,"c0",100,25200.0,0.0));
        assertEquals(List.of(7,9),days(MorningPromptRenderer.decisionFacts(memory,10,3,5)));
        memory.updateSummary(9,"Summary through day 9");
        assertEquals(List.of(7,9),days(MorningPromptRenderer.decisionFacts(memory,10,3,1)));
        assertThrows(IllegalArgumentException.class,() -> MorningPromptRenderer.decisionFacts(memory,9,3,5));
    }
    private static List<Integer> days(List<MorningMemory.Experience> values) {
        return values.stream().map(MorningMemory.Experience::day).toList();
    }
}
