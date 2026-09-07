package org.matsim.agentic.scenario;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PersonUtils;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/** Configurable fallback for cities that use standard MATSim conventions. */
public final class GenericCityScenarioAdapter implements CityScenarioAdapter {

    private final Set<String> configuredModes;

    public GenericCityScenarioAdapter() {
        configuredModes = parseModes(System.getenv("AGENTIC_ALLOWED_MODES"));
    }

    @Override
    public String id() {
        return "generic";
    }

    @Override
    public void validate(Scenario scenario) {
        if (scenario.getNetwork().getLinks().isEmpty()
                || scenario.getPopulation().getPersons().isEmpty()) {
            throw new IllegalArgumentException(
                    "A city scenario requires a non-empty network and population");
        }
    }

    @Override
    public Set<String> allowedModes(Person person) {
        Set<String> modes = new LinkedHashSet<>(configuredModes);
        if (!PersonUtils.canUseCar(person)) {
            modes.remove("car");
        }
        if (modes.isEmpty()) {
            throw new IllegalArgumentException(
                    "No feasible AGENTIC_ALLOWED_MODES for person " + person.getId());
        }
        return modes;
    }

    private static Set<String> parseModes(String raw) {
        String value = raw == null || raw.isBlank() ? "car,pt,walk" : raw;
        Set<String> modes = new LinkedHashSet<>();
        for (String token : value.split(",")) {
            String mode = token.trim().toLowerCase(Locale.ROOT);
            if (!mode.isBlank()) {
                modes.add(mode);
            }
        }
        if (modes.isEmpty()) {
            throw new IllegalArgumentException("AGENTIC_ALLOWED_MODES must not be empty");
        }
        return Collections.unmodifiableSet(modes);
    }
}
