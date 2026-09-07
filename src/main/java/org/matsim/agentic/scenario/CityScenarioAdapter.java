package org.matsim.agentic.scenario;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;

import java.util.Set;

/**
 * Adapts city-specific scenario conventions to the generic agent loop.
 * Implementations may validate required scenario elements and determine the
 * feasible high-level modes for each controlled person.
 */
public interface CityScenarioAdapter {

    String id();

    void validate(Scenario scenario);

    Set<String> allowedModes(Person person);
}
