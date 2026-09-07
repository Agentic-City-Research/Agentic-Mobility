package org.matsim.agentic.scenario;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PersonUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/** Built-in adapter for the enriched Sioux Falls MATSim scenario. */
public final class SiouxFallsScenarioAdapter implements CityScenarioAdapter {

    @Override
    public String id() {
        return "sioux-falls";
    }

    @Override
    public void validate(Scenario scenario) {
        if (scenario.getNetwork().getLinks().isEmpty()) {
            throw new IllegalArgumentException("Sioux Falls network is empty");
        }
        if (scenario.getPopulation().getPersons().isEmpty()) {
            throw new IllegalArgumentException("Sioux Falls population is empty");
        }
        if (scenario.getTransitSchedule().getTransitLines().isEmpty()) {
            throw new IllegalArgumentException(
                    "Sioux Falls adapter requires a transit schedule");
        }
    }

    @Override
    public Set<String> allowedModes(Person person) {
        Set<String> modes = new LinkedHashSet<>(Set.of("pt", "walk"));
        if (PersonUtils.canUseCar(person)) {
            modes.add("car");
        }
        return modes;
    }
}
