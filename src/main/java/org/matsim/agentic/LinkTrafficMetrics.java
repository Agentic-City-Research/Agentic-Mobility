package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.vehicles.Vehicle;

import java.util.*;

/** Car-link measurements, grouped by entry hour; parking is never a traversal. */
final class LinkTrafficMetrics {
    static final int BIN_SECONDS = 3600;
    private record Stamp(Id<Link> link, double time) {}
    private record Bin(Id<Link> link, int hour) {}
    private static final class Totals {
        long entered, completed, aborted, partial;
        double travel, delay;
    }

    private final Network network;
    private final int binSeconds;
    private final Map<Id<Vehicle>, Stamp> active = new HashMap<>();
    private final Map<Id<Vehicle>, Id<Person>> drivers = new HashMap<>();
    private final Map<Id<Person>, Id<Vehicle>> vehicles = new HashMap<>();
    private final Map<Bin, Totals> bins = new HashMap<>();

    LinkTrafficMetrics(Network network) { this(network, BIN_SECONDS); }
    LinkTrafficMetrics(Network network, int binSeconds) {
        if (binSeconds < 1) throw new IllegalArgumentException("Invalid traffic measurement bin");
        this.network = network; this.binSeconds = binSeconds;
    }

    private Totals totals(Stamp s) {
        return bins.computeIfAbsent(new Bin(s.link(), (int) (s.time() / binSeconds)), k -> new Totals());
    }

    synchronized void handle(Event event) {
        if (event instanceof VehicleEntersTrafficEvent e) {
            discard(e.getVehicleId());
            if ("car".equals(e.getNetworkMode())) {
                drivers.put(e.getVehicleId(), e.getPersonId());
                vehicles.put(e.getPersonId(), e.getVehicleId());
            }
            // No full-link entry: the vehicle starts partway along its origin link.
        } else if (event instanceof LinkEnterEvent e) {
            if (!drivers.containsKey(e.getVehicleId())) return;
            Link link = network.getLinks().get(e.getLinkId());
            if (link == null || !link.getAllowedModes().contains("car")) return;
            Stamp previous = active.remove(e.getVehicleId());
            if (previous != null) totals(previous).aborted++;
            Stamp s = new Stamp(e.getLinkId(), e.getTime());
            active.put(e.getVehicleId(), s);
            totals(s).entered++;
        } else if (event instanceof LinkLeaveEvent e) {
            finish(e.getVehicleId(), e.getLinkId(), e.getTime(), true);
        } else if (event instanceof VehicleLeavesTrafficEvent e) {
            finish(e.getVehicleId(), e.getLinkId(), e.getTime(), e.getRelativePositionOnLink() == 1.0);
            discard(e.getVehicleId());
        } else if (event instanceof VehicleAbortsEvent e) {
            discard(e.getVehicleId());
        } else if (event instanceof PersonStuckEvent e) {
            Id<Vehicle> vehicle = vehicles.get(e.getPersonId());
            if (vehicle != null) discard(vehicle);
        }
    }

    private void finish(Id<Vehicle> vehicle, Id<Link> linkId, double time, boolean fullLink) {
        Stamp s = active.remove(vehicle);
        if (s == null) return;
        Totals t = totals(s);
        if (!s.link().equals(linkId) || time < s.time()) { t.aborted++; return; }
        if (!fullLink) { t.partial++; return; }
        Link link = network.getLinks().get(linkId);
        double freeFlow = link.getLength() / link.getFreespeed();
        if (!(freeFlow > 0) || !Double.isFinite(freeFlow)) { t.aborted++; return; }
        double duration = time - s.time();
        t.completed++;
        t.travel += duration;
        t.delay += Math.max(0, duration - freeFlow);
    }

    private void discard(Id<Vehicle> vehicle) {
        Stamp s = active.remove(vehicle);
        if (s != null) totals(s).aborted++;
        Id<Person> person = drivers.remove(vehicle);
        if (person != null) vehicles.remove(person, vehicle);
    }

    synchronized List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        bins.entrySet().stream().sorted(Comparator
                .comparing((Map.Entry<Bin, Totals> e) -> e.getKey().link().toString())
                .thenComparingInt(e -> e.getKey().hour())).forEach(e -> {
            Bin b = e.getKey(); Totals t = e.getValue();
            Link link = network.getLinks().get(b.link());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("link_id", b.link().toString());
            row.put("start", b.hour() * binSeconds);
            row.put("end", (b.hour() + 1) * binSeconds);
            row.put("entered", t.entered);
            row.put("completed", t.completed);
            row.put("aborted", t.aborted);
            row.put("partial", t.partial);
            row.put("unfinished", t.entered - t.completed - t.aborted - t.partial);
            row.put("total_travel_time", t.travel);
            row.put("total_delay", t.delay);
            row.put("avg_travel_time", t.completed == 0 ? null : t.travel / t.completed);
            row.put("avg_delay", t.completed == 0 ? null : t.delay / t.completed);
            row.put("delay_ratio", t.completed == 0 ? null : t.travel / t.completed / (link.getLength() / link.getFreespeed()));
            result.add(row);
        });
        return result;
    }

    synchronized void reset() { active.clear(); drivers.clear(); vehicles.clear(); bins.clear(); }
}
