package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.router.Dijkstra;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Basic deterministic BFS-LE (DOI 10.1080/18128602.2012.671383), followed by
 * a project-specific cost/diversity selection from a larger initial pool.
 * Each state is a set of eliminated interior links. MATSim Dijkstra finds its
 * free-flow shortest path; children eliminate each link of THAT state's path.
 * States and full route sequences are deduplicated, not just root exclusions.
 * Not the paper's optimized/pass-node variant or MATSim's default ReRoute.
 */
public final class MatsimOdCandidates {
    public static final int MAX_CANDIDATES = 4;

    public record Settings(int maxSearches, int initialPoolSize, double maxTimeRatio, double timeSlackSeconds,
                           double maxDistanceRatio, double distanceSlackMeters, double maxOverlap) {
        public Settings {
            if (maxSearches < 1 || initialPoolSize < MAX_CANDIDATES
                    || !(maxTimeRatio >= 1) || !(maxDistanceRatio >= 1)
                    || !Double.isFinite(timeSlackSeconds) || timeSlackSeconds < 0
                    || !Double.isFinite(distanceSlackMeters) || distanceSlackMeters < 0
                    || !Double.isFinite(maxOverlap) || maxOverlap < 0 || maxOverlap > 1)
                throw new IllegalArgumentException("Invalid candidate limits (searches >= 1, initial pool >= 4, ratios >= 1, finite slacks >= 0, overlap in [0,1])");
        }
        /** Explicit ratio limits retain their strict meaning for existing callers. */
        public Settings(int maxSearches, double maxTimeRatio, double maxDistanceRatio, double maxOverlap) {
            this(maxSearches, 16, maxTimeRatio, 0, maxDistanceRatio, 0, maxOverlap);
        }
        public static Settings defaults() { return new Settings(10000, 16, 2.5, 300, 2.5, 2000, .85); }
        Map<String, Object> report() {
            // These defaults are configurable experimental starting points, not literature calibration.
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("maxSearchesPerOd", maxSearches);
            row.put("initialPoolSize", initialPoolSize);
            row.put("maxFreeFlowTimeRatio", Double.isInfinite(maxTimeRatio) ? null : maxTimeRatio);
            row.put("freeFlowTimeSlackSeconds", timeSlackSeconds);
            row.put("maxDistanceRatio", Double.isInfinite(maxDistanceRatio) ? null : maxDistanceRatio);
            row.put("distanceSlackMeters", distanceSlackMeters);
            row.put("maxPairwiseOverlap", maxOverlap);
            row.put("detourRule", "Time and distance each must pass: ratio <= limit OR extra cost <= slack; Infinity disables that dimension");
            row.put("selectionRule", "Keep shortest-time c0; greedy increasing free-flow time, then distance, then full link sequence; enforce pairwise overlap; retain 1..4 without padding");
            return row;
        }
    }

    private record Pool(List<RouteCandidate> routes, Map<String, Object> diagnostics) { }
    private record Search(int evaluated, int disconnected, int duplicates, boolean limited, boolean exhausted,
                          boolean targetReached) { }
    private record Selection(List<RouteCandidate> routes, int costRejected, int overlapRejected, int omitted) { }
    private final Network network;
    private final EliminationDijkstra router;
    private final Settings settings;
    private final Map<String, Pool> cache = new LinkedHashMap<>();
    private final Map<String, List<RouteCandidate>> imported;

    public MatsimOdCandidates(Network source, Config config) { this(source, config, Settings.defaults(), null); }

    public MatsimOdCandidates(Network source, Config config, Settings settings, Path candidateFile) {
        Objects.requireNonNull(config); this.settings = Objects.requireNonNull(settings);
        network = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(source).filter(network, Set.of("car"));
        network.getLinks().keySet().forEach(this::carLink);
        // No mutation of the simulation network, random costs or epsilon cost floor.
        router = new EliminationDijkstra(network);
        imported = candidateFile == null ? null : readPools(candidateFile);
    }

    /** Static free-flow costs make the pool independent of person, seed and departure. */
    public synchronized List<RouteCandidate> compute(Id<Link> from, Id<Link> to, double departure, Person person) {
        String od = from + ">" + to;
        return cache.computeIfAbsent(od, key -> imported == null ? generate(from, to, departure, person)
                : importPool(key, from, to, departure, person)).routes();
    }

    public Map<String, Object> settingsReport() {
        var report = settings.report();
        report.put("appliedMode", imported == null ? "generate-and-select" : "validate-frozen-file");
        if (imported != null) report.put("selectionRule", "Validate costs and pairwise overlap; reuse exact file paths, route IDs and order without generation or reselection");
        return report;
    }
    public boolean usesImportedPools() { return imported != null; }
    public Map<String, Object> diagnostics(String od) { return Objects.requireNonNull(cache.get(od), "Unprepared OD " + od).diagnostics(); }

    public Map<String, Object> summary() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("uniqueOds", cache.size());
        row.put("candidateCountDistributionByOd", cache.values().stream().collect(java.util.stream.Collectors.groupingBy(
                p -> p.routes().size(), TreeMap::new, java.util.stream.Collectors.counting())));
        row.put("targetReachedOds", cache.values().stream().filter(p -> p.routes().size() == MAX_CANDIDATES).count());
        row.put("searchLimitedOds", cache.values().stream().filter(p -> Boolean.TRUE.equals(p.diagnostics().get("searchLimitReached"))).count());
        row.put("searchSpaceExhaustedOds", cache.values().stream().filter(p -> Boolean.TRUE.equals(p.diagnostics().get("searchSpaceExhausted"))).count());
        row.put("totalShortestPathSearches", cache.values().stream().mapToLong(p -> ((Number) p.diagnostics().get("shortestPathSearches")).longValue()).sum());
        row.put("initialPoolTargetReachedOds", cache.values().stream().filter(p -> Boolean.TRUE.equals(p.diagnostics().get("initialPoolTargetReached"))).count());
        for (String field : List.of("generatedCandidateCount", "costRejectedPaths", "overlapRejectedPaths", "selectionOmittedPaths"))
            row.put("total" + Character.toUpperCase(field.charAt(0)) + field.substring(1), cache.values().stream()
                    .map(p -> p.diagnostics().get(field)).filter(Number.class::isInstance).map(Number.class::cast).mapToLong(Number::longValue).sum());
        return row;
    }

    private Pool generate(Id<Link> from, Id<Link> to, double departure, Person person) {
        Link origin = carLink(from), destination = carLink(to);
        if (from.equals(to)) return pool(from + ">" + to,
                new Selection(List.of(new RouteCandidate("c0", List.of(from), 0)), 0, 0, 0),
                new Search(0, 0, 0, false, true, false), 1, "bfs-le");
        ArrayDeque<Set<Id<Link>>> queue = new ArrayDeque<>();
        Set<Set<Id<Link>>> seenStates = new HashSet<>();
        queue.add(Set.of()); seenStates.add(Set.of());
        Set<List<Id<Link>>> seenPaths = new HashSet<>();
        List<RouteCandidate> generated = new ArrayList<>();
        int searches = 0, disconnected = 0, duplicates = 0;
        boolean omitted = false;
        while (!queue.isEmpty() && searches < settings.maxSearches() && generated.size() < settings.initialPoolSize()) {
            var removed = queue.removeFirst(); searches++;
            var path = shortest(origin, destination, removed, departure, person);
            if (path == null) { disconnected++; continue; }
            var sequence = new ArrayList<Id<Link>>(); sequence.add(from);
            path.links.forEach(l -> sequence.add(l.getId())); sequence.add(to);
            var candidate = checked("raw" + generated.size(), sequence, from, to);
            if (!seenPaths.add(candidate.linkIds())) duplicates++;
            else generated.add(candidate);
            if (generated.size() == settings.initialPoolSize()) break;
            // Expand duplicate paths too: deeper exclusions can yield new routes.
            for (Id<Link> id : path.links.stream().map(Link::getId).distinct().sorted(Comparator.comparing(Object::toString)).toList()) {
                Set<Id<Link>> child = new HashSet<>(removed); child.add(id); child = Set.copyOf(child);
                if (seenStates.contains(child)) continue;
                if (seenStates.size() >= settings.maxSearches()) { omitted = true; continue; }
                seenStates.add(child); queue.addLast(child);
            }
        }
        if (generated.isEmpty()) throw new IllegalArgumentException("Unreachable car OD: " + from + ">" + to);
        boolean targetReached = generated.size() == settings.initialPoolSize();
        boolean limited = !targetReached && (omitted || !queue.isEmpty());
        return pool(from + ">" + to, select(generated),
                new Search(searches, disconnected, duplicates, limited, !targetReached && !limited && queue.isEmpty(), targetReached),
                generated.size(), "bfs-le");
    }

    private org.matsim.core.router.util.LeastCostPathCalculator.Path shortest(Link from, Link to, Set<Id<Link>> removed,
                                                                            double departure, Person person) {
        Set<Id<Link>> blocked = new HashSet<>(removed); blocked.add(from.getId()); blocked.add(to.getId());
        // An eliminated subnetwork can legitimately disconnect. Check reachability
        // first so MATSim does not log each expected BFS branch as a scenario error.
        if (!reachable(from.getToNode(), to.getFromNode(), blocked)) return null;
        router.blocked = blocked;
        try { return router.calcLeastCostPath(from, to, departure, person, null); }
        finally { router.blocked = Set.of(); }
    }

    private boolean reachable(Node from, Node to, Set<Id<Link>> blocked) {
        if (from == to) return true;
        ArrayDeque<Node> queue = new ArrayDeque<>(); queue.add(from);
        Set<Id<Node>> visited = new HashSet<>(); visited.add(from.getId());
        while (!queue.isEmpty()) for (Link link : queue.removeFirst().getOutLinks().values()) {
            if (blocked.contains(link.getId())) continue;
            if (link.getToNode() == to) return true;
            if (visited.add(link.getToNode().getId())) queue.addLast(link.getToNode());
        }
        return false;
    }

    private boolean acceptableCost(RouteCandidate candidate, RouteCandidate shortest) {
        return withinDetour(candidate.freeFlowTravelTime(), shortest.freeFlowTravelTime(), settings.maxTimeRatio(), settings.timeSlackSeconds())
                && withinDetour(distance(candidate), distance(shortest), settings.maxDistanceRatio(), settings.distanceSlackMeters());
    }

    private static boolean withinDetour(double value, double base, double maxRatio, double slack) {
        return ratio(value, base) <= maxRatio || value - base <= slack;
    }

    private Selection select(List<RouteCandidate> generated) {
        RouteCandidate shortest = generated.getFirst(); // Preserve the unrestricted Dijkstra root path as c0.
        Comparator<RouteCandidate> order = Comparator.comparingDouble(RouteCandidate::freeFlowTravelTime)
                .thenComparingDouble(this::distance).thenComparing(c -> c.linkIds().toString());
        List<RouteCandidate> accepted = new ArrayList<>(); accepted.add(shortest);
        int costRejected = 0, overlapRejected = 0, omitted = 0;
        for (var candidate : generated.stream().skip(1).sorted(order).toList()) {
            if (!acceptableCost(candidate, shortest)) { costRejected++; continue; }
            if (accepted.size() == MAX_CANDIDATES) { omitted++; continue; }
            if (accepted.stream().anyMatch(other -> overlap(candidate, other) > settings.maxOverlap())) { overlapRejected++; continue; }
            accepted.add(candidate);
        }
        List<RouteCandidate> named = new ArrayList<>();
        for (int i = 0; i < accepted.size(); i++) {
            var candidate = accepted.get(i);
            named.add(new RouteCandidate("c" + i, candidate.linkIds(), candidate.freeFlowTravelTime()));
        }
        return new Selection(List.copyOf(named), costRejected, overlapRejected, omitted);
    }

    private Pool pool(String od, Selection selection, Search search, Integer generatedCount, String source) {
        List<RouteCandidate> fixed = List.copyOf(selection.routes());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("od", od); row.put("source", source); row.put("candidateCount", fixed.size());
        row.put("targetReached", fixed.size() == MAX_CANDIDATES); row.put("evaluatedSubnetworks", search.evaluated());
        row.put("shortestPathSearches", search.evaluated() - search.disconnected());
        row.put("disconnectedStates", search.disconnected()); row.put("duplicatePaths", search.duplicates());
        row.put("generatedCandidateCount", generatedCount);
        row.put("initialPoolTargetReached", generatedCount == null ? null : search.targetReached());
        row.put("costRejectedPaths", selection.costRejected()); row.put("overlapRejectedPaths", selection.overlapRejected());
        row.put("selectionOmittedPaths", selection.omitted());
        row.put("qualityRejectedPaths", selection.costRejected() + selection.overlapRejected());
        row.put("searchLimitReached", search.limited()); row.put("searchSpaceExhausted", search.exhausted());
        row.put("searchStopReason", generatedCount == null ? "imported" : search.targetReached() ? "initial_pool_target"
                : search.limited() ? "search_limit" : "search_space_exhausted");
        row.put("routes", fixed.stream().map(c -> {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("routeId", c.id()); metrics.put("links", c.linkIds().stream().map(Object::toString).toList());
            metrics.put("freeFlowSeconds", c.freeFlowTravelTime()); metrics.put("distanceMeters", distance(c));
            metrics.put("freeFlowTimeRatioToC0", finiteRatio(c.freeFlowTravelTime(), fixed.getFirst().freeFlowTravelTime()));
            metrics.put("distanceRatioToC0", finiteRatio(distance(c), distance(fixed.getFirst())));
            metrics.put("overlapWithOtherRoutes", fixed.stream().filter(other -> other != c)
                    .collect(java.util.stream.Collectors.toMap(RouteCandidate::id, other -> overlap(c, other), (a, b) -> a, LinkedHashMap::new)));
            return metrics;
        }).toList());
        return new Pool(fixed, Collections.unmodifiableMap(row));
    }

    private RouteCandidate checked(String id, List<Id<Link>> ids, Id<Link> from, Id<Link> to) {
        if (ids.isEmpty() || !ids.getFirst().equals(from) || !ids.getLast().equals(to))
            throw new IllegalArgumentException("Wrong candidate endpoints for " + from + ">" + to);
        if (from.equals(to)) {
            if (ids.size() != 1) throw new IllegalArgumentException("Same-link OD must use a single link");
            return new RouteCandidate(id, List.copyOf(ids), 0);
        }
        if (ids.size() < 2) throw new IllegalArgumentException("Incomplete candidate route");
        Set<Id<Node>> interiorNodes = new HashSet<>(); interiorNodes.add(carLink(from).getToNode().getId());
        double seconds = 0;
        for (int i = 1; i < ids.size(); i++) {
            Link previous = carLink(ids.get(i - 1)), link = carLink(ids.get(i));
            if (!previous.getToNode().getId().equals(link.getFromNode().getId()))
                throw new IllegalArgumentException("Disconnected candidate links: " + previous.getId() + ", " + link.getId());
            if (i < ids.size() - 1 && (ids.get(i).equals(from) || ids.get(i).equals(to) || !interiorNodes.add(link.getToNode().getId())))
                throw new IllegalArgumentException("Candidate has a repeated routing node or endpoint link: " + ids);
            seconds += freeFlow(link);
        }
        return new RouteCandidate(id, List.copyOf(ids), seconds);
    }

    private Link carLink(Id<Link> id) {
        Link link = network.getLinks().get(id);
        if (link == null) throw new IllegalArgumentException("Missing car link: " + id);
        if (!(link.getLength() >= 0 && Double.isFinite(link.getLength()) && link.getFreespeed() > 0 && Double.isFinite(link.getFreespeed())))
            throw new IllegalArgumentException("Invalid link length/free speed: " + id);
        return link;
    }

    private double distance(RouteCandidate c) { return c.linkIds().stream().skip(1).map(this::carLink).mapToDouble(Link::getLength).sum(); }
    private double overlap(RouteCandidate a, RouteCandidate b) {
        Set<Id<Link>> bLinks = new HashSet<>(b.linkIds().subList(1, b.linkIds().size()));
        double shared = a.linkIds().stream().skip(1).filter(bLinks::contains).map(this::carLink).mapToDouble(Link::getLength).sum();
        double denominator = Math.min(distance(a), distance(b));
        return denominator == 0 ? (a.linkIds().equals(b.linkIds()) ? 1 : 0) : Math.min(1, shared / denominator);
    }
    private static double ratio(double value, double base) { return base == 0 ? (value == 0 ? 1 : Double.POSITIVE_INFINITY) : value / base; }
    private static Double finiteRatio(double value, double base) { double r = ratio(value, base); return Double.isFinite(r) ? r : null; }

    static double freeFlow(Link link) { return link.getLength() / link.getFreespeed(); }

    /** Load only stable OD/routes fields; metrics are recomputed, never trusted. */
    private Map<String, List<RouteCandidate>> readPools(Path file) {
        Map<String, List<RouteCandidate>> pools = new LinkedHashMap<>();
        try (var reader = JsonlIO.newReader(file)) {
            String line; int number = 0;
            while ((line = reader.readLine()) != null) {
                number++; if (line.isBlank()) continue;
                var row = Json.readTree(line);
                String od = row.path("od").asText(); String[] endpoints = od.split(">", -1);
                if (endpoints.length != 2 || endpoints[0].isBlank() || endpoints[1].isBlank() || !row.path("routes").isArray())
                    throw new IllegalArgumentException("Invalid candidate file row " + number);
                List<RouteCandidate> routes = new ArrayList<>(); Set<String> names = new HashSet<>(); Set<List<Id<Link>>> paths = new HashSet<>();
                for (var route : row.get("routes")) {
                    String id = route.path("routeId").asText();
                    if (!id.equals("c" + routes.size()) || !names.add(id) || !route.path("links").isArray())
                        throw new IllegalArgumentException("Candidate IDs must be ordered c0..c3: " + od);
                    List<Id<Link>> ids = new ArrayList<>();
                    for (var link : route.get("links")) { if (!link.isTextual()) throw new IllegalArgumentException("Non-text link ID: " + od); ids.add(Id.createLinkId(link.asText())); }
                    var candidate = checked(id, ids, Id.createLinkId(endpoints[0]), Id.createLinkId(endpoints[1]));
                    if (!paths.add(candidate.linkIds())) throw new IllegalArgumentException("Duplicate candidate path: " + od);
                    if (!route.path("freeFlowSeconds").isNumber() || Math.abs(route.get("freeFlowSeconds").asDouble() - candidate.freeFlowTravelTime()) > 1e-6)
                        throw new IllegalArgumentException("Candidate free-flow time differs from network: " + od);
                    routes.add(candidate);
                }
                if (routes.isEmpty() || routes.size() > MAX_CANDIDATES || pools.putIfAbsent(od, List.copyOf(routes)) != null)
                    throw new IllegalArgumentException("Empty/oversized/duplicate OD pool: " + od);
            }
        } catch (IOException e) { throw new IllegalArgumentException("Cannot read candidate file " + file, e); }
        return Collections.unmodifiableMap(pools);
    }

    private Pool importPool(String od, Id<Link> from, Id<Link> to, double departure, Person person) {
        var routes = imported.get(od);
        if (routes == null) throw new IllegalArgumentException("Candidate file lacks cohort OD: " + od + " (no automatic regeneration)");
        int searches = from.equals(to) ? 0 : 1;
        if (searches > 0) {
            var path = shortest(carLink(from), carLink(to), Set.of(), departure, person);
            if (path == null || Math.abs(path.travelTime + freeFlow(carLink(to)) - routes.getFirst().freeFlowTravelTime()) > 1e-6)
                throw new IllegalArgumentException("Imported c0 is not a free-flow shortest route: " + od);
        }
        List<RouteCandidate> accepted = new ArrayList<>();
        for (var c : routes) {
            if (!acceptableCost(c, routes.getFirst()) || accepted.stream().anyMatch(other -> overlap(c, other) > settings.maxOverlap()))
                throw new IllegalArgumentException("Imported pool violates requested quality limits: " + od);
            accepted.add(c);
        }
        // Validate and reuse identifiers/order exactly; importing must never silently reselect a frozen pool.
        return pool(od, new Selection(routes, 0, 0, 0), new Search(searches, 0, 0, false, false, false), null, "candidate-file");
    }

    /** Use MATSim's protected traversal hook, not infinite costs (which can
     * still produce an infinite-cost path in native Dijkstra).
     */
    private static final class EliminationDijkstra extends Dijkstra {
        Set<Id<Link>> blocked = Set.of();
        EliminationDijkstra(Network network) {
            super(network, new TravelDisutility() {
                @Override public double getLinkTravelDisutility(Link l, double time, Person p, org.matsim.vehicles.Vehicle v) { return freeFlow(l); }
                @Override public double getLinkMinimumTravelDisutility(Link l) { return freeFlow(l); }
            }, new FreeSpeedTravelTime());
        }
        @Override protected boolean canPassLink(Link link) { return !blocked.contains(link.getId()) && super.canPassLink(link); }
    }
}
