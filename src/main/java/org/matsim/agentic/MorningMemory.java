package org.matsim.agentic;

import java.util.*;

/** Raw chronological experiences and cumulative route-use counts; no smoothing. */
public final class MorningMemory {
    public record Experience(int day, String routeId, Double carSeconds, Double arrivalSeconds, Double lateSeconds,
                             boolean stuck, Double departureSeconds, Double doorSeconds, Double tollPaid,
                             Double selectedDepartureSeconds, List<MorningRoutePolicy.TollObservation> tollObservations) {
        public Experience {
            tollObservations = tollObservations == null ? List.of() : List.copyOf(tollObservations);
        }
        public Experience(int day, String routeId, Double carSeconds, Double arrivalSeconds, Double lateSeconds,
                          boolean stuck, Double departureSeconds, Double doorSeconds, Double tollPaid) {
            this(day, routeId, carSeconds, arrivalSeconds, lateSeconds, stuck, departureSeconds, doorSeconds, tollPaid, null, List.of());
        }
        public Experience(int day, String routeId, Double carSeconds, Double arrivalSeconds, Double lateSeconds,
                          boolean stuck, Double departureSeconds, Double doorSeconds) {
            this(day, routeId, carSeconds, arrivalSeconds, lateSeconds, stuck, departureSeconds, doorSeconds, null);
        }
        public Experience(int day, String routeId, Double carSeconds, Double arrivalSeconds, Double lateSeconds, boolean stuck) {
            this(day, routeId, carSeconds, arrivalSeconds, lateSeconds, stuck, null, null);
        }
        public Experience(int day, String routeId, double carSeconds, Double arrivalSeconds, Double lateSeconds) {
            this(day, routeId, Double.valueOf(carSeconds), arrivalSeconds, lateSeconds, false);
        }
    }
    public static final class RouteHistory {
        int chosen, completed;
        final int index;
        Integer lastDay;
        private RouteHistory(int index) { this.index = index; }
        public int chosenCount() { return chosen; }
        public int completedCount() { return completed; }
        public Integer lastExperiencedDay() { return lastDay; }
    }
    private final Map<String, RouteHistory> routes = new LinkedHashMap<>();
    // Lossless primitive storage avoids retaining five boxed Doubles and a record per
    // person/day in large 500-day populations. It is storage compression, not smoothing.
    private static final int WIDTH = 8;
    private final List<List<MorningRoutePolicy.TollObservation>> receipts = new ArrayList<>();
    private long[] facts = new long[0];
    private int size;
    private final List<String> routeIds = new ArrayList<>();
    private final int limit;
    private String summary = "";
    private Integer summaryDay;
    private int summaryVersion;

    public MorningMemory(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid memory limit");
        this.limit = limit;
    }
    public RouteHistory route(String id) {
        return routes.computeIfAbsent(id, k -> {
            var h = new RouteHistory(routeIds.size()); routeIds.add(k); return h;
        });
    }
    public void chose(String id) { route(id).chosen++; }
    public void observe(Experience experience) {
        if (experience.day() < 0 || experience.routeId() == null || experience.routeId().isBlank()
                || size > 0 && experience.day() <= (int)(facts[(size - 1) * WIDTH] >>> 32))
            throw new IllegalArgumentException("Experiences must be unique, chronological days for this trip");
        if (experience.carSeconds() != null && (!Double.isFinite(experience.carSeconds()) || experience.carSeconds() < 0))
            throw new IllegalArgumentException("Invalid car travel time");
        if (experience.tollPaid() != null && (!Double.isFinite(experience.tollPaid()) || experience.tollPaid() < 0))
            throw new IllegalArgumentException("Invalid actual toll payment");
        for (Double t : Arrays.asList(experience.arrivalSeconds(), experience.lateSeconds(), experience.departureSeconds(), experience.doorSeconds(), experience.selectedDepartureSeconds()))
            if (t != null && (!Double.isFinite(t) || t < 0)) throw new IllegalArgumentException("Invalid experience time");
        var h = route(experience.routeId());
        if (experience.carSeconds() != null) {
            h.completed++;
            h.lastDay = experience.day();
        }
        if ((size + 1) * WIDTH > facts.length) {
            int capacity = facts.length == 0 ? 8 : facts.length / WIDTH + Math.max(1, facts.length / WIDTH / 2);
            facts = Arrays.copyOf(facts, Math.multiplyExact(capacity, WIDTH));
        }
        int offset = size * WIDTH;
        facts[offset] = ((long)experience.day() << 32) | ((long)h.index << 1) | (experience.stuck() ? 1 : 0);
        facts[offset + 1] = bits(experience.carSeconds()); facts[offset + 2] = bits(experience.arrivalSeconds());
        facts[offset + 3] = bits(experience.lateSeconds()); facts[offset + 4] = bits(experience.departureSeconds());
        facts[offset + 5] = bits(experience.doorSeconds()); facts[offset + 6] = bits(experience.tollPaid());
        facts[offset + 7] = bits(experience.selectedDepartureSeconds()); receipts.add(experience.tollObservations()); size++;
    }
    /** Complete per-trip facts, independent of the prompt window. */
    public List<Experience> all() { return java.util.stream.IntStream.range(0, size).mapToObj(this::experienceAt).toList(); }
    public int size() { return size; }
    public String summary() { return summary; }
    public Integer summaryDay() { return summaryDay; }
    public int summaryVersion() { return summaryVersion; }
    /** Commit only after this trip's factual result has been observed. */
    public void updateSummary(int day, String text) {
        if (text == null || text.isBlank() || size == 0
                || day != (int)(facts[(size - 1) * WIDTH] >>> 32)
                || summaryDay != null && day <= summaryDay)
            throw new IllegalArgumentException("Summary must describe the latest observed day, once per trip/day");
        summary = text; summaryDay = day; summaryVersion++;
    }
    public record Usage(int chosenCount, int completedCount, Integer lastExperiencedDay) {}
    public Map<String, Usage> usage() {
        Map<String, Usage> result = new LinkedHashMap<>();
        routes.forEach((id, h) -> result.put(id, new Usage(h.chosen, h.completed, h.lastDay)));
        return Collections.unmodifiableMap(result);
    }
    public List<Experience> recent() { return recent(limit); }
    /** Reflection has its own window; neither window truncates the complete facts. */
    public List<Experience> recent(int maximumExperiences) {
        if (maximumExperiences < 1) throw new IllegalArgumentException("Invalid recent experience limit");
        return java.util.stream.IntStream.range(Math.max(0, size - maximumExperiences), size).mapToObj(this::experienceAt).toList();
    }
    private Experience experienceAt(int index) {
        int offset = index * WIDTH; long meta = facts[offset];
        return new Experience((int)(meta >>> 32), routeIds.get((int)((meta & 0xffff_ffffL) >>> 1)),
                value(facts[offset + 1]), value(facts[offset + 2]), value(facts[offset + 3]), (meta & 1) != 0,
                value(facts[offset + 4]), value(facts[offset + 5]), value(facts[offset + 6]), value(facts[offset + 7]), receipts.get(index));
    }
    private static long bits(Double v) { return Double.doubleToRawLongBits(v == null ? Double.NaN : v); }
    private static Double value(long bits) {
        double v = Double.longBitsToDouble(bits); return Double.isNaN(v) ? null : v;
    }
}
