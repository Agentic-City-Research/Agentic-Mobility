package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.vehicles.Vehicle;

import java.util.*;
import java.util.function.BiConsumer;

/** One payment for each actual car-link entry, including an origin partial link.
 * A second traversal is a second use; duplicate notifications of one entry are not.
 */
final class MorningTollSystem implements BasicEventHandler {
    static final String PURPOSE = "congestion_toll";
    record State(TollPolicy policy, String policyId, int version) {
        Map<String, Object> toJson() { return Map.of("policyId", policyId, "policyVersion", version, "policy", policy.toJson()); }
    }
    record Receipt(String linkId, double time, double amount, String policyId, int policyVersion) {}
    private record Entry(Id<Link> link, double time) {}
    private final Network network;
    private final EventsManager events;
    private final BiConsumer<String, Receipt> feedback;
    private final Map<Id<Vehicle>, Id<Person>> drivers = new HashMap<>();
    private final Map<Id<Person>, Id<Vehicle>> vehicles = new HashMap<>();
    private final Map<Id<Vehicle>, Entry> entries = new HashMap<>();
    private final Map<String, Double> paid = new LinkedHashMap<>();
    private volatile State state = new State(TollPolicy.empty(-1), "no-toll", 0);
    private OutputWriter output;
    private MorningTravelerInformation information;
    private int day;
    private long chargedEntries, duplicateEntries, observedEntries, zeroObservedEntries;

    MorningTollSystem(Network network, EventsManager events, BiConsumer<String, Receipt> feedback) {
        this.network = network; this.events = events; this.feedback = feedback;
    }
    void setOutput(OutputWriter output) { this.output = output; }
    void setInformation(MorningTravelerInformation information) { this.information = information; }
    State state() { return state; }
    void setPolicy(TollPolicy policy, int version) {
        state = new State(policy, "pricing-v" + version + "-it" + policy.day(), version);
    }
    double amountAt(Id<Link> link, double time) { return state.policy().amountAt(link, time); }

    @Override public synchronized void reset(int iteration) {
        day = iteration; drivers.clear(); vehicles.clear(); entries.clear(); paid.clear();
        chargedEntries = duplicateEntries = observedEntries = zeroObservedEntries = 0;
    }
    @Override public synchronized void handleEvent(Event event) {
        if (event instanceof VehicleEntersTrafficEvent e) {
            if (!"car".equals(e.getNetworkMode())) return;
            drivers.put(e.getVehicleId(), e.getPersonId()); vehicles.put(e.getPersonId(), e.getVehicleId());
            charge(e.getVehicleId(), e.getLinkId(), e.getTime());
        } else if (event instanceof LinkEnterEvent e) {
            charge(e.getVehicleId(), e.getLinkId(), e.getTime());
        } else if (event instanceof LinkLeaveEvent e) {
            var entry = entries.get(e.getVehicleId());
            if (entry != null && entry.link().equals(e.getLinkId())) entries.remove(e.getVehicleId());
        } else if (event instanceof VehicleLeavesTrafficEvent e) clear(e.getVehicleId());
        else if (event instanceof VehicleAbortsEvent e) clear(e.getVehicleId());
        else if (event instanceof PersonStuckEvent e) {
            var vehicle = vehicles.get(e.getPersonId()); if (vehicle != null) clear(vehicle);
        }
    }
    private void clear(Id<Vehicle> vehicle) {
        entries.remove(vehicle); var person = drivers.remove(vehicle);
        if (person != null) vehicles.remove(person, vehicle);
    }
    private void charge(Id<Vehicle> vehicle, Id<Link> linkId, double time) {
        var person = drivers.get(vehicle);
        if (person == null) return;
        var link = network.getLinks().get(linkId);
        if (link == null || !link.getAllowedModes().contains("car")) return;
        var entry = new Entry(linkId, time);
        if (entry.equals(entries.put(vehicle, entry))) { duplicateEntries++; return; }
        State active = state;
        double amount = active.policy().amountAt(linkId, time);
        var receipt = new Receipt(linkId.toString(), time, amount, active.policyId(), active.version());
        if (information != null) information.observe(day, person.toString(), receipt);
        // Audit only the population whose personal knowledge is actually updated.
        // This stream is an internal execution ledger, never an input to traveler prompts.
        Long observationSequence = null;
        if (information != null && information.participates(person.toString())) {
            observationSequence = ++observedEntries;
            if (amount == 0) zeroObservedEntries++;
            if (output != null) {
                var observation = new LinkedHashMap<String,Object>();
                observation.put("day",day); observation.put("observationSequence",observationSequence);
                observation.put("personId",person.toString()); observation.put("vehicleId",vehicle.toString());
                observation.put("linkId",linkId.toString()); observation.put("entrySeconds",time);
                observation.put("amount",amount); observation.put("timeBinSeconds",information.binSeconds());
                observation.put("timeBin",(long)Math.floor(time / information.binSeconds()));
                observation.put("informationGroup",information.group(person.toString()));
                observation.put("policyId",active.policyId()); observation.put("policyVersion",active.version());
                observation.put("event","car_link_entry");
                output.tollObservation(observation);
            }
        }
        if (amount == 0) return;
        paid.merge(person.toString(), amount, Double::sum); chargedEntries++;
        if (feedback != null) feedback.accept(person.toString(), receipt);
        if (output != null) {
            var row = new LinkedHashMap<String, Object>();
            row.put("day", day); row.put("personId", person.toString()); row.put("vehicleId", vehicle.toString());
            row.put("receipt", receipt); row.put("event", "car_link_entry");
            if (information != null) row.put("informationGroup", information.group(person.toString()));
            if (observationSequence != null) row.put("observationSequence", observationSequence);
            output.appendLine("toll-payments.jsonl", row);
        }
        events.processEvent(new PersonMoneyEvent(time, person, -amount, PURPOSE, active.policyId(), ""));
    }
    synchronized Map<String, Object> summary() {
        return Map.of("totalPaid", paid.values().stream().mapToDouble(Double::doubleValue).sum(),
                "chargedEntries", chargedEntries, "payingPersons", paid.size(), "duplicateEntriesIgnored", duplicateEntries,
                "personalObservationEntries", observedEntries, "zeroPersonalObservationEntries", zeroObservedEntries);
    }
    synchronized void writePersonalTotals() {
        if (output != null) paid.forEach((person, amount) -> output.appendLine("person-payments.jsonl",
                Map.of("day", day, "personId", person, "tollPaid", amount, "policyId", state.policyId(), "policyVersion", state.version())));
    }
}
