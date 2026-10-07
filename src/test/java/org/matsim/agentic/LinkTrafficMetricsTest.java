package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.network.NetworkUtils;
import org.matsim.vehicles.Vehicle;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LinkTrafficMetricsTest {
    private final Network network = NetworkUtils.createNetwork();
    private final Id<Link> link = Id.createLinkId("a");
    private final Id<Person> person = Id.createPersonId("p");
    private final Id<Vehicle> vehicle = Id.createVehicleId("v");

    LinkTrafficMetricsTest() {
        var f = network.getFactory();
        var a = f.createNode(Id.createNodeId("n1"), new Coord(0, 0));
        var b = f.createNode(Id.createNodeId("n2"), new Coord(100, 0));
        network.addNode(a); network.addNode(b);
        var l = f.createLink(link, a, b);
        l.setLength(100); l.setFreespeed(10); l.setAllowedModes(Set.of("car"));
        network.addLink(l);
    }
    private void start(LinkTrafficMetrics m, double time) {
        m.handle(new VehicleEntersTrafficEvent(time, person, link, vehicle, "car", 1.0));
    }
    private double number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).doubleValue();
    }

    @Test void parkingNeverBecomesTravelTimeAndTerminalTraversalCountsOnce() {
        var m = new LinkTrafficMetrics(network);
        start(m, 0);
        m.handle(new LinkEnterEvent(10, vehicle, link));
        m.handle(new VehicleLeavesTrafficEvent(25, person, link, vehicle, "car", 1.0));
        start(m, 30000);
        m.handle(new LinkLeaveEvent(30001, vehicle, link));
        var row = m.snapshot().getFirst();
        assertEquals(1, number(row, "completed"));
        assertEquals(15, number(row, "total_travel_time"));
        assertEquals(5, number(row, "total_delay"));
        assertEquals(1.5, number(row, "delay_ratio"));
    }

    @Test void usesEntryHourAndPreservesUnfinishedAndAbortedCounts() {
        var m = new LinkTrafficMetrics(network);
        start(m, 3500);
        m.handle(new LinkEnterEvent(3595, vehicle, link));
        m.handle(new LinkLeaveEvent(3615, vehicle, link));
        m.handle(new LinkEnterEvent(3620, vehicle, link));
        m.handle(new VehicleAbortsEvent(3650, vehicle, link));
        start(m, 3700);
        m.handle(new LinkEnterEvent(3710, vehicle, link));
        var rows = m.snapshot();
        assertEquals(0, number(rows.getFirst(), "start"));
        assertEquals(20, number(rows.getFirst(), "avg_travel_time"));
        assertEquals(1, number(rows.getLast(), "aborted"));
        assertEquals(1, number(rows.getLast(), "unfinished"));
        assertNull(rows.getLast().get("delay_ratio"));
        m.reset();
        assertTrue(m.snapshot().isEmpty());
    }

    @Test void partialDestinationsAndStuckVehiclesDoNotCreateCompletedSamples() {
        var m = new LinkTrafficMetrics(network);
        start(m, 0);
        m.handle(new LinkEnterEvent(10, vehicle, link));
        m.handle(new VehicleLeavesTrafficEvent(15, person, link, vehicle, "car", 0.5));
        start(m, 20);
        m.handle(new LinkEnterEvent(25, vehicle, link));
        m.handle(new PersonStuckEvent(100, person, link, "car"));
        m.handle(new LinkLeaveEvent(200, vehicle, link));
        var row = m.snapshot().getFirst();
        assertEquals(1, number(row, "partial"));
        assertEquals(1, number(row, "aborted"));
        assertEquals(0, number(row, "completed"));
        assertEquals(0, number(row, "unfinished"));
    }

    @Test void summaryWeightsTraversalsAndRejectsLegacyRatios() {
        var m = new LinkTrafficMetrics(network);
        start(m, 0);
        m.handle(new LinkEnterEvent(10, vehicle, link));
        m.handle(new LinkLeaveEvent(30, vehicle, link));
        var first = Map.<String, Object>of("link_metrics_version", 2, "link_hourly", m.snapshot());
        m.reset(); start(m, 0);
        for (int i = 0; i < 3; i++) {
            m.handle(new LinkEnterEvent(100 + i * 20, vehicle, link));
            m.handle(new LinkLeaveEvent(110 + i * 20, vehicle, link));
        }
        var second = Map.<String, Object>of("link_metrics_version", 2, "link_hourly", m.snapshot());
        var summary = new CongestionSummary(List.of(first, second));
        assertEquals(1.25, summary.ratios(network).get("a"));
        assertTrue(summary.text(network).contains("entries/h=2.0"));
        assertTrue(summary.text(network).contains("total_delay_s/day=5.0"));
        assertTrue(new CongestionSummary(List.of(Map.of("link_delay_ratio", Map.of("a", 500))))
                .ratios(network).isEmpty());
    }

    @Test void unfinishedAfterMidnightIsVisibleAndNonCarTrafficIsExcluded() {
        var m = new LinkTrafficMetrics(network);
        m.handle(new VehicleEntersTrafficEvent(0, person, link, vehicle, "bike", 1.0));
        m.handle(new LinkEnterEvent(10, vehicle, link));
        m.handle(new LinkLeaveEvent(100, vehicle, link));
        assertTrue(m.snapshot().isEmpty());
        start(m, 90000);
        m.handle(new LinkEnterEvent(90010, vehicle, link));
        var summary = new CongestionSummary(List.of(Map.of("link_metrics_version", 2, "link_hourly", m.snapshot())));
        assertTrue(summary.ratios(network).isEmpty());
        assertTrue(summary.text(network).contains("[90000,93600)"));
        assertTrue(summary.text(network).contains("unfinished=1"));
        assertTrue(summary.text(network).contains("ratio=NA"));
    }
}
