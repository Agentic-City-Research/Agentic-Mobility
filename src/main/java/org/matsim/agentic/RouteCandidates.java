package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Up to five car paths per OD, generated with a small self-contained
 * free-flow Dijkstra plus static link exclusion. Candidate sets are computed
 * once and held fixed for the experiment with stable ids ("c0" through "c4").
 * Free-flow only: current tolls and live congestion never influence generation
 * or ordering. Reachability uses a cached directed BFS over nodes, so neither
 * helper relies on MATSim's router (whose Dijkstra can loop on networks that
 * contain zero-length subdivision links).
 */
public final class RouteCandidates {

    private static final int MAX_CANDIDATES = 5;

    private final Network network;
    private final Map<Id<Node>, Set<Id<Node>>> nodeReachCache = new HashMap<>();
    private final Map<String, List<RouteCandidate>> candidateCache = new HashMap<>();

    public RouteCandidates(Network network) {
        this.network = network;
    }

    public boolean isReachable(Id<Link> from, Id<Link> to) {
        if (from.equals(to)) return true;
        Link fromLink = network.getLinks().get(from);
        Link toLink = network.getLinks().get(to);
        if (fromLink == null || toLink == null) return false;
        Node fromNode = fromLink.getToNode();
        Node toNode = toLink.getFromNode();
        if (fromNode.getId().equals(toNode.getId())) return true;
        return nodeReach(fromNode).contains(toNode.getId());
    }

    public List<RouteCandidate> compute(Id<Link> from, Id<Link> to) {
        String key = from + ">" + to;
        return candidateCache.computeIfAbsent(key, k -> computeUncached(from, to));
    }

    private List<RouteCandidate> computeUncached(Id<Link> from, Id<Link> to) {
        Link fromLink = network.getLinks().get(from);
        Link toLink = network.getLinks().get(to);
        if (fromLink == null || toLink == null) return List.of();

        List<RouteCandidate> result = new ArrayList<>();
        List<Link> first = shortestPath(fromLink, toLink, Set.of());
        if (first == null || first.isEmpty()) return result;
        result.add(toCandidate(0, first));

        Set<String> seen = new HashSet<>();
        seen.add(linkIdList(first));

        // static link exclusion: exclude the slowest free-flow links first
        List<Link> excludable = new ArrayList<>(first);
        excludable.sort(Comparator.comparingDouble(this::freeFlow).reversed());
        for (Link excluded : excludable) {
            if (result.size() >= MAX_CANDIDATES) break;
            List<Link> alt = shortestPath(fromLink, toLink, Set.of(excluded.getId()));
            if (alt == null || alt.isEmpty()) continue;
            if (!seen.add(linkIdList(alt))) continue;
            result.add(toCandidate(result.size(), alt));
        }
        return result;
    }

    /** Free-flow shortest path from the end of fromLink to the start of toLink. */
    private List<Link> shortestPath(Link fromLink, Link toLink, Set<Id<Link>> excluded) {
        if (fromLink.getId().equals(toLink.getId())) {
            return new ArrayList<>(List.of(fromLink));
        }
        Node fromNode = fromLink.getToNode();
        Node toNode = toLink.getFromNode();
        if (fromNode.getId().equals(toNode.getId())) {
            return new ArrayList<>(List.of(fromLink, toLink));
        }

        Map<Id<Node>, Double> dist = new HashMap<>();
        Map<Id<Node>, Link> prevLink = new HashMap<>();
        Map<Id<Node>, Node> prevNode = new HashMap<>();
        PriorityQueue<Node> pq = new PriorityQueue<>(
                Comparator.comparingDouble(n -> dist.getOrDefault(n.getId(), Double.POSITIVE_INFINITY)));
        Set<Id<Node>> settled = new HashSet<>();

        dist.put(fromNode.getId(), 0.0);
        pq.add(fromNode);
        while (!pq.isEmpty()) {
            Node u = pq.poll();
            if (!settled.add(u.getId())) continue;
            if (u.getId().equals(toNode.getId())) break;
            double du = dist.getOrDefault(u.getId(), Double.POSITIVE_INFINITY);
            for (Link l : u.getOutLinks().values()) {
                if (excluded.contains(l.getId())) continue;
                if (!l.getAllowedModes().contains("car")) continue;
                Node v = l.getToNode();
                if (settled.contains(v.getId())) continue;
                double nd = du + freeFlow(l);
                if (nd < dist.getOrDefault(v.getId(), Double.POSITIVE_INFINITY)) {
                    dist.put(v.getId(), nd);
                    prevLink.put(v.getId(), l);
                    prevNode.put(v.getId(), u);
                    pq.add(v);
                }
            }
        }
        if (!prevNode.containsKey(toNode.getId())) return null;

        Deque<Link> rev = new ArrayDeque<>();
        Node cur = toNode;
        while (!cur.getId().equals(fromNode.getId())) {
            Link l = prevLink.get(cur.getId());
            if (l == null) return null;
            rev.addFirst(l);
            cur = prevNode.get(cur.getId());
        }
        List<Link> full = new ArrayList<>();
        full.add(fromLink);
        full.addAll(rev);
        full.add(toLink);
        return full;
    }

    /** Directed node reachability, computed once per source node and cached. */
    private Set<Id<Node>> nodeReach(Node start) {
        return nodeReachCache.computeIfAbsent(start.getId(), id -> bfs(start));
    }

    private Set<Id<Node>> bfs(Node start) {
        Set<Id<Node>> visited = new HashSet<>();
        Deque<Node> queue = new ArrayDeque<>();
        visited.add(start.getId());
        queue.add(start);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            for (Link out : n.getOutLinks().values()) {
                if (!out.getAllowedModes().contains("car")) continue;
                if (visited.add(out.getToNode().getId())) queue.add(out.getToNode());
            }
        }
        return visited;
    }

    private RouteCandidate toCandidate(int index, List<Link> path) {
        double tt = 0;
        List<Id<Link>> ids = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            Link l = path.get(i);
            // The start link is the car-interaction link; the vehicle enters
            // traffic at its end, so it is not actually driven and is excluded
            // from the free-flow time (but kept in linkIds, since the route
            // still starts there).
            if (i > 0) tt += freeFlow(l);
            ids.add(l.getId());
        }
        return new RouteCandidate("c" + index, ids, tt);
    }

    private String linkIdList(List<Link> path) {
        StringBuilder sb = new StringBuilder();
        for (Link l : path) sb.append(l.getId()).append(',');
        return sb.toString();
    }

    private double freeFlow(Link link) {
        return link.getLength() / Math.max(link.getFreespeed(), 1e-9);
    }
}
