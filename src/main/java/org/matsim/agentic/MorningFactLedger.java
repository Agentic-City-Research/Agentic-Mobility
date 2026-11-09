package org.matsim.agentic;

import java.util.*;

/** A deterministic view of this trip's executed personal observations. */
public final class MorningFactLedger {
    public record Event(int day, String routeId, Double carSeconds, Double doorSeconds,
                        Double arrivalSeconds, Double lateSeconds, boolean stuck) {}
    public record RouteStats(String routeId, int observedTripCount, int completedTripCount,
                             int incompleteTripCount, int unknownOutcomeCount, int lateTripCount,
                             int latenessKnownCount, Double meanCarSeconds, int carTimeKnownCount,
                             Double meanDoorSeconds, int doorTimeKnownCount, int lastObservedDay,
                             List<Event> notableEvents) {}
    public record View(String schemaVersion, int asOfDay, List<RouteStats> routes) {
        public View { routes = List.copyOf(routes); }
    }

    public static View build(List<MorningMemory.Experience> experiences, int asOfDay) {
        if (asOfDay < -1) throw new IllegalArgumentException("Invalid ledger cutoff");
        Map<String, List<MorningMemory.Experience>> grouped = new TreeMap<>();
        Set<Integer> days = new HashSet<>();
        for (var e : experiences) {
            if (e.day() > asOfDay) continue;
            if (e.day() < 0 || !days.add(e.day())) throw new IllegalArgumentException("Duplicate or invalid experience day");
            grouped.computeIfAbsent(e.routeId(), x -> new ArrayList<>()).add(e);
        }
        List<RouteStats> routes = new ArrayList<>();
        grouped.forEach((route, facts) -> {
            int completed = 0, incomplete = 0, unknown = 0, late = 0, lateKnown = 0, cars = 0, doors = 0, last = -1;
            double carSum = 0, doorSum = 0;
            MorningMemory.Experience recent = null, worst = null;
            for (var e : facts) {
                last = Math.max(last, e.day());
                if (e.arrivalSeconds() != null && !e.stuck()) {
                    completed++;
                    if (recent == null || e.day() > recent.day()) recent = e;
                } else if (e.stuck()) incomplete++; else unknown++;
                if (e.lateSeconds() != null) {
                    lateKnown++; if (e.lateSeconds() > 0) late++;
                    if (worst == null || e.lateSeconds() > worst.lateSeconds() ||
                            e.lateSeconds().equals(worst.lateSeconds()) && e.day() > worst.day()) worst = e;
                }
                if (e.carSeconds() != null) { cars++; carSum += e.carSeconds(); }
                if (e.doorSeconds() != null) { doors++; doorSum += e.doorSeconds(); }
            }
            List<Event> events = new ArrayList<>();
            if (recent != null) events.add(event(recent));
            if (worst != null && (recent == null || worst.day() != recent.day())) events.add(event(worst));
            routes.add(new RouteStats(route, facts.size(), completed, incomplete, unknown, late, lateKnown,
                    cars == 0 ? null : carSum / cars, cars, doors == 0 ? null : doorSum / doors, doors, last, List.copyOf(events)));
        });
        return new View("traveler-ledger-v1", asOfDay, routes);
    }
    private static Event event(MorningMemory.Experience e) {
        return new Event(e.day(), e.routeId(), e.carSeconds(), e.doorSeconds(), e.arrivalSeconds(), e.lateSeconds(), e.stuck());
    }
    static void validate(View view, int maxDay) {
        if (view == null) return;
        if (!"traveler-ledger-v1".equals(view.schemaVersion()) || view.asOfDay() != maxDay)
            throw new IllegalArgumentException("Ledger cutoff or schema mismatch");
        for (var route : view.routes()) {
            if (route.lastObservedDay() > maxDay || route.notableEvents().stream().anyMatch(e -> e.day() > maxDay))
                throw new IllegalArgumentException("Future ledger fact");
        }
    }
    static void append(StringBuilder target, View view, int cutoff) {
        if (view == null) return;
        validate(view, cutoff);
        target.append("YOUR STRUCTURED PERSONAL EXECUTION LEDGER\n")
                .append("Counts are computed from unique trips and include overlapping recent records; do not add those records again. ")
                .append("Ledger facts take precedence over notes. Null means unknown. Past observations do not guarantee future outcomes.\n")
                .append(Json.write(view)).append('\n');
    }
}
