package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Charges tolls on car-link entry and keeps the per-person observation table
 * used by uninformed background re-routing. Billing is left-closed right-open;
 * repeated entries are billed separately, duplicate events of the same entry
 * are not double-billed. Positive amounts emit a PersonMoneyEvent.
 */
public final class TollSystem implements BasicEventHandler {

    public record LinkSlot(Id<Link> linkId, int slot) {
    }

    public record Receipt(Id<Link> linkId, double time, double amount) {
    }

    private final EventsManager events;
    private final OutputWriter output;
    private final InformationModel infoModel;

    private volatile TollPolicy policy = TollPolicy.empty(-1);
    private volatile Set<Id<Link>> tolledLinks = Set.of();

    // uninformed-background observation table: person -> (link, 600s slot) -> last observed amount
    private final Map<Id<Person>, Map<LinkSlot, Double>> observations = new ConcurrentHashMap<>();
    // per-day LLM-traveler receipts + payments (fed into experiences.jsonl)
    private final Map<Id<Person>, List<Receipt>> dayReceipts = new ConcurrentHashMap<>();
    private final Map<Id<Person>, Double> dayTollPaid = new ConcurrentHashMap<>();
    // MATSim 2026.0 LinkEnterEvent no longer carries the driver id; reconstruct
    // vehicle -> person from traffic-enter events for the billing path.
    private final Map<Id<Vehicle>, Id<Person>> vehicleToPerson = new ConcurrentHashMap<>();

    public TollSystem(EventsManager events, OutputWriter output, InformationModel infoModel) {
        this.events = events;
        this.output = output;
        this.infoModel = infoModel;
    }

    public synchronized void setPolicy(TollPolicy p) {
        this.policy = p;
        Set<Id<Link>> links = ConcurrentHashMap.newKeySet();
        for (TollPolicy.TollRule r : p.rules()) links.add(r.linkId());
        this.tolledLinks = links;
    }

    public TollPolicy policy() {
        return policy;
    }

    public static int slot(double time) {
        return (int) (time / 600.0);
    }

    /** Toll cost a given person perceives for routing a given link at a given time. */
    public double routingToll(Id<Person> personId, Id<Link> linkId, double time) {
        switch (infoModel.groupOf(personId)) {
            case LLM_INFORMED:
            case LLM_UNINFORMED:
                return 0.0;
            case BG_INFORMED:
                return policy.amountAt(linkId, time);
            case BG_UNINFORMED:
            default: {
                Map<LinkSlot, Double> obs = observations.get(personId);
                if (obs == null) return 0.0;
                Double v = obs.get(new LinkSlot(linkId, slot(time)));
                return v == null ? 0.0 : v;
            }
        }
    }

    @Override
    public void handleEvent(Event event) {
        if (event instanceof VehicleEntersTrafficEvent vete) {
            vehicleToPerson.put(vete.getVehicleId(), vete.getPersonId());
            return;
        }
        if (!(event instanceof LinkEnterEvent le)) return;
        Id<Link> linkId = le.getLinkId();
        if (!tolledLinks.contains(linkId)) return;

        double time = le.getTime();
        Id<Person> personId = vehicleToPerson.get(le.getVehicleId());
        if (personId == null) return; // defensive: no driver known for this vehicle
        double amount = policy.amountAt(linkId, time);

        // 1. population-wide toll observation, including zero amounts
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("person_id", personId.toString());
        obs.put("link_id", linkId.toString());
        obs.put("time", time);
        obs.put("slot", slot(time));
        obs.put("amount", amount);
        output.tollObservation(obs);

        // 2. observation table for uninformed background routing
        if (infoModel.groupOf(personId) == InformationModel.Group.BG_UNINFORMED) {
            observations.computeIfAbsent(personId, k -> new ConcurrentHashMap<>())
                    .put(new LinkSlot(linkId, slot(time)), amount);
        }

        // 3. bill on entry
        if (amount > 0) {
            events.processEvent(new PersonMoneyEvent(time, personId, -amount, "toll", "", ""));
        }

        // 4. per-day LLM receipts/payments
        if (infoModel.isLlm(personId)) {
            dayReceipts.computeIfAbsent(personId, k -> new CopyOnWriteArrayList<>())
                    .add(new Receipt(linkId, time, amount));
            dayTollPaid.merge(personId, amount, Double::sum);
        }
    }

    public void resetForDay() {
        dayReceipts.clear();
        dayTollPaid.clear();
    }

    public List<Receipt> receiptsOf(Id<Person> id) {
        List<Receipt> r = dayReceipts.get(id);
        return r == null ? List.of() : new ArrayList<>(r);
    }

    public double tollPaidOf(Id<Person> id) {
        return dayTollPaid.getOrDefault(id, 0.0);
    }
}
