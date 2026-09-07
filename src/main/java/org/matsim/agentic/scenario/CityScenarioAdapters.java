package org.matsim.agentic.scenario;

import java.util.Locale;
import java.util.ServiceLoader;

/** Resolves built-in and third-party city adapters by identifier. */
public final class CityScenarioAdapters {

    private CityScenarioAdapters() {
    }

    public static CityScenarioAdapter load(String requestedId) {
        String id = requestedId == null || requestedId.isBlank()
                ? "sioux-falls" : requestedId.trim().toLowerCase(Locale.ROOT);
        CityScenarioAdapter builtIn = switch (id) {
            case "sioux-falls" -> new SiouxFallsScenarioAdapter();
            case "generic" -> new GenericCityScenarioAdapter();
            default -> null;
        };
        if (builtIn != null) {
            return builtIn;
        }
        for (CityScenarioAdapter adapter : ServiceLoader.load(CityScenarioAdapter.class)) {
            if (adapter.id().equalsIgnoreCase(id)) {
                return adapter;
            }
        }
        throw new IllegalArgumentException(
                "Unknown city adapter '" + id
                        + "'. Use sioux-falls, generic, or register a ServiceLoader implementation.");
    }
}
