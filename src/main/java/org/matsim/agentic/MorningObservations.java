package org.matsim.agentic;

import org.matsim.api.core.v01.events.*;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.TripStructureUtils;

import java.util.*;

/** Full-day events, original-trip cohort and a separate clock-time network window. */
final class MorningObservations implements BasicEventHandler {
    static final class TripResult {
        Double departure, carDeparture, carArrival, carTrafficEntry, arrival;
        boolean stuck;
        double tollPaid;
        final List<MorningTollSystem.Receipt> tollReceipts = new ArrayList<>();
        final List<String> executedLinks = new ArrayList<>(); // Filled only for controlled trips.
        Double carSeconds() { return carDeparture == null || carArrival == null ? null : carArrival - carDeparture; }
        Double doorSeconds() { return departure == null || arrival == null ? null : arrival - departure; }
        Double preTrafficWaitSeconds() { return carDeparture == null || carTrafficEntry == null ? null : carTrafficEntry - carDeparture; }
    }
    private final MorningCohort cohort;
    final Map<String, TripResult> trips = new LinkedHashMap<>();
    private final Map<String, Integer> nextTrip = new HashMap<>();
    private final Map<String, String> activeTrip = new HashMap<>();
    private final Map<String, String> cars = new HashMap<>();
    private final Map<String, String> vehicleByPerson = new HashMap<>();
    final LinkTrafficMetrics links;
    final MorningCarTravelMetrics carTravel = new MorningCarTravelMetrics();
    private final double windowStart, windowEnd;
    private double lastTime, vehicleSeconds;
    private int maximumVehicles, vehiclesAtEnd, networkEntered, networkExited;
    private int carTripsStarted, carTripsCompleted, carTripsAborted;

    MorningObservations(Network network, MorningCohort cohort, double start, double end) {
        this(network, cohort, start, end, LinkTrafficMetrics.BIN_SECONDS);
    }
    MorningObservations(Network network, MorningCohort cohort, double start, double end, int binSeconds) {
        this.cohort = cohort; windowStart = start; windowEnd = end; links = new LinkTrafficMetrics(network, binSeconds);
    }

    @Override public synchronized void reset(int iteration) {
        trips.clear(); cohort.members.keySet().forEach(k -> trips.put(k, new TripResult()));
        nextTrip.clear(); activeTrip.clear(); cars.clear(); vehicleByPerson.clear(); links.reset();
        carTravel.reset();
        lastTime = 0; vehicleSeconds = 0; maximumVehicles = vehiclesAtEnd = networkEntered = networkExited = 0;
        carTripsStarted = carTripsCompleted = carTripsAborted = 0;
    }

    private void integrate(double time) {
        if (time < lastTime) throw new IllegalStateException("Events not time ordered; use one event-handler thread");
        double from = Math.max(lastTime, windowStart), to = Math.min(time, windowEnd);
        if (to > from) {
            vehicleSeconds += (to - from) * cars.size();
            maximumVehicles = Math.max(maximumVehicles, cars.size());
        }
        if (lastTime < windowEnd && time >= windowEnd) vehiclesAtEnd = cars.size();
        lastTime = time;
    }

    @Override public synchronized void handleEvent(Event event) {
        links.handle(event);
        integrate(event.getTime());
        carTravel.handle(event);
        boolean inWindow = event.getTime() >= windowStart && event.getTime() < windowEnd;
        if (event instanceof VehicleEntersTrafficEvent e && "car".equals(e.getNetworkMode())) {
            if (cars.put(e.getVehicleId().toString(), e.getPersonId().toString()) == null) carTripsStarted++;
            recordLink(e.getPersonId().toString(), e.getLinkId().toString(), false);
            vehicleByPerson.put(e.getPersonId().toString(), e.getVehicleId().toString());
            var result = trips.get(activeTrip.get(e.getPersonId().toString()));
            if (result != null) result.carTrafficEntry = e.getTime();
            if (inWindow) { networkEntered++; maximumVehicles = Math.max(maximumVehicles, cars.size()); }
        } else if (event instanceof VehicleLeavesTrafficEvent e) {
            recordLink(cars.get(e.getVehicleId().toString()), e.getLinkId().toString(), true);
            String person = cars.remove(e.getVehicleId().toString());
            if (person != null) { carTripsCompleted++; vehicleByPerson.remove(person); if (inWindow) networkExited++; }
        } else if (event instanceof VehicleAbortsEvent e) {
            String person = cars.remove(e.getVehicleId().toString());
            if (person != null) {carTripsAborted++; vehicleByPerson.remove(person);}
        } else if (event instanceof LinkEnterEvent e) {
            recordLink(cars.get(e.getVehicleId().toString()), e.getLinkId().toString(), false);
        }

        String person = null;
        if (event instanceof ActivityEndEvent e) person = e.getPersonId().toString();
        else if (event instanceof ActivityStartEvent e) person = e.getPersonId().toString();
        else if (event instanceof PersonDepartureEvent e) person = e.getPersonId().toString();
        else if (event instanceof PersonArrivalEvent e) person = e.getPersonId().toString();
        else if (event instanceof PersonStuckEvent e) {
            person = e.getPersonId().toString();
            String vehicle = vehicleByPerson.remove(person);
            if (vehicle != null && cars.remove(vehicle) != null) carTripsAborted++;
        }
        if (person == null || !cohort.persons.contains(person)) return;
        if (event instanceof ActivityEndEvent e && !TripStructureUtils.isStageActivityType(e.getActType())) {
            int index = nextTrip.getOrDefault(person, 0);
            nextTrip.put(person, index + 1);
            String key = person + "/" + index;
            activeTrip.put(person, key);
            if (trips.containsKey(key)) trips.get(key).departure = e.getTime();
        }
        TripResult result = trips.get(activeTrip.get(person));
        if (result == null) return;
        if (event instanceof PersonDepartureEvent e && "car".equals(e.getLegMode())) result.carDeparture = e.getTime();
        else if (event instanceof PersonArrivalEvent e && "car".equals(e.getLegMode())) result.carArrival = e.getTime();
        else if (event instanceof ActivityStartEvent e && !TripStructureUtils.isStageActivityType(e.getActType())) result.arrival = e.getTime();
        else if (event instanceof PersonStuckEvent) result.stuck = true;
    }

    private void recordLink(String person, String link, boolean finalLink) {
        var member = cohort.members.get(activeTrip.get(person));
        if (member == null || !member.controlled) return;
        var visited = trips.get(member.key).executedLinks;
        if (!finalLink || visited.isEmpty() || !visited.getLast().equals(link)) visited.add(link);
    }

    /** Payments belong to the active original trip, never to another trip of the same person. */
    synchronized void recordToll(String person, MorningTollSystem.Receipt receipt) {
        var result = trips.get(activeTrip.get(person));
        if (result != null) { result.tollPaid += receipt.amount(); result.tollReceipts.add(receipt); }
    }

    synchronized Map<String, Object> networkSummary(double simulationEnd) {
        integrate(Math.max(lastTime, simulationEnd));
        return Map.of("windowStartSeconds", windowStart, "windowEndSeconds", windowEnd,
                "meanActiveCarVehicles", vehicleSeconds / (windowEnd - windowStart),
                "maximumActiveCarVehicles", maximumVehicles, "activeCarVehiclesAtWindowEnd", vehiclesAtEnd,
                "carVehiclesEnteredInWindow", networkEntered, "carVehiclesExitedInWindow", networkExited);
    }
    synchronized Map<String,Object> publicCarTrips() {
        return Map.of("carTripsStarted",carTripsStarted,"carTripsCompleted",carTripsCompleted,
                "carTripsAborted",carTripsAborted,"carTripsUnfinished",cars.size());
    }
}
