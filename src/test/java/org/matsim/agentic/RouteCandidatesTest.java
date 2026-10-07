package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RouteCandidatesTest {
    @Test
    void generatesFiveDistinctConnectedPathsWithStableIdsAndCaching() {
        Network network = networkWithAlternatives(true);
        RouteCandidates generator = new RouteCandidates(network);
        var candidates = generator.compute(Id.createLinkId("origin"), Id.createLinkId("destination"));

        assertEquals(List.of("c0", "c1", "c2", "c3", "c4"),
                candidates.stream().map(RouteCandidate::id).toList());
        assertEquals(5, new HashSet<>(candidates.stream().map(RouteCandidate::linkIds).toList()).size());
        for (var candidate : candidates) {
            var ids = candidate.linkIds();
            assertEquals(Id.createLinkId("origin"), ids.getFirst());
            assertEquals(Id.createLinkId("destination"), ids.getLast());
            for (int i = 1; i < ids.size(); i++) {
                assertEquals(network.getLinks().get(ids.get(i - 1)).getToNode(),
                        network.getLinks().get(ids.get(i)).getFromNode());
            }
        }
        assertSame(candidates, generator.compute(Id.createLinkId("origin"), Id.createLinkId("destination")));
    }

    @Test
    void keepsOnlyAvailablePathsWhenAlternativesDoNotExist() {
        var generator = new RouteCandidates(networkWithAlternatives(false));
        var candidates = generator.compute(Id.createLinkId("origin"), Id.createLinkId("destination"));
        assertEquals(1, candidates.size());
        assertEquals("c0", candidates.getFirst().id());
    }

    private Network networkWithAlternatives(boolean alternatives) {
        Network network = NetworkUtils.createNetwork();
        var factory = network.getFactory();
        for (int i = 0; i < 8; i++) {
            network.addNode(factory.createNode(Id.createNodeId("n" + i), new Coord(i * 100, 0)));
        }
        addLink(network, "origin", 0, 1, 1);
        for (int i = 1; i <= 5; i++) {
            addLink(network, "main" + i, i, i + 1, 10 - i);
            if (alternatives) addLink(network, "alternative" + i, i, i + 1, 100);
        }
        addLink(network, "destination", 6, 7, 1);
        return network;
    }

    private void addLink(Network network, String id, int from, int to, double length) {
        var link = network.getFactory().createLink(Id.createLinkId(id),
                network.getNodes().get(Id.createNodeId("n" + from)),
                network.getNodes().get(Id.createNodeId("n" + to)));
        link.setLength(length);
        link.setFreespeed(1);
        link.setAllowedModes(Set.of("car"));
        network.addLink(link);
    }
}
