package org.matsim.agentic.scenario;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CityScenarioAdaptersTest {

    @Test
    void loadsBuiltInAdapters() {
        assertEquals("sioux-falls", CityScenarioAdapters.load("sioux-falls").id());
        assertEquals("generic", CityScenarioAdapters.load("generic").id());
    }

    @Test
    void rejectsUnknownAdapter() {
        assertThrows(IllegalArgumentException.class,
                () -> CityScenarioAdapters.load("missing-city"));
    }
}
