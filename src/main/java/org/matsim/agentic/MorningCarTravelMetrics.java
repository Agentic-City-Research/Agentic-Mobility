package org.matsim.agentic;

import org.matsim.api.core.v01.events.*;

import java.util.*;

/** Full-day car-leg clock, including the queue before VehicleEntersTraffic. */
final class MorningCarTravelMetrics {
    private static final class Journey {
        final double departure;
        Double entry;
        Journey(double departure) { this.departure = departure; }
    }
    private final Map<String, Journey> active = new HashMap<>();
    private final Map<String, String> vehicles = new HashMap<>();
    private long expected, departed, completed, aborted, entryObserved;
    private double completedSeconds, completedPreTrafficSeconds, abortedSeconds;

    void reset() {
        active.clear(); vehicles.clear(); expected = departed = completed = aborted = entryObserved = 0;
        completedSeconds = completedPreTrafficSeconds = abortedSeconds = 0;
    }

    void expectCarLegs(long count) { expected = count; }

    void handle(Event event) {
        if (event instanceof PersonDepartureEvent e && "car".equals(e.getLegMode())) {
            if (active.putIfAbsent(e.getPersonId().toString(), new Journey(e.getTime())) != null)
                throw new IllegalStateException("Overlapping car departures: " + e.getPersonId());
            departed++;
        } else if (event instanceof VehicleEntersTrafficEvent e && "car".equals(e.getNetworkMode())) {
            vehicles.put(e.getVehicleId().toString(), e.getPersonId().toString());
            var journey = active.get(e.getPersonId().toString());
            if (journey != null) journey.entry = e.getTime();
        } else if (event instanceof PersonArrivalEvent e && "car".equals(e.getLegMode())) {
            var journey = active.remove(e.getPersonId().toString());
            if (journey != null) {
                completed++; completedSeconds += elapsed(e.getTime(), journey.departure);
                if (journey.entry != null) {
                    entryObserved++; completedPreTrafficSeconds += elapsed(journey.entry, journey.departure);
                }
            }
        } else if (event instanceof PersonStuckEvent e) {
            abort(e.getPersonId().toString(), e.getTime());
        } else if (event instanceof VehicleAbortsEvent e) {
            var person = vehicles.remove(e.getVehicleId().toString());
            if (person != null) abort(person, e.getTime());
        } else if (event instanceof VehicleLeavesTrafficEvent e) {
            vehicles.remove(e.getVehicleId().toString());
        }
    }

    private void abort(String person, double time) {
        var journey = active.remove(person);
        if (journey != null) { aborted++; abortedSeconds += elapsed(time, journey.departure); }
    }

    private static double elapsed(double end, double start) {
        if (end < start) throw new IllegalStateException("Negative car journey elapsed time");
        return end - start;
    }

    Map<String, Object> summary(double horizon) {
        double unfinishedSeconds = active.values().stream().mapToDouble(j -> elapsed(horizon, j.departure)).sum();
        long notStarted = Math.max(0, expected - departed);
        boolean complete = completed == expected && departed == expected && aborted == 0 && active.isEmpty();
        var row = new LinkedHashMap<String, Object>();
        row.put("expectedCarLegs", expected); row.put("departedCarLegs", departed);
        row.put("completedCarLegs", completed); row.put("abortedCarLegs", aborted);
        row.put("unfinishedCarLegs", active.size()); row.put("notStartedCarLegs", notStarted);
        row.put("completionFractionAll", expected == 0 ? null : (double) completed / expected);
        row.put("allPlannedCarLegsCompleted", complete);
        row.put("carPersonHours", complete ? completedSeconds / 3600 : null);
        row.put("completedCarPersonHours", completedSeconds / 3600);
        row.put("meanCompletedCarSeconds", completed == 0 ? null : completedSeconds / completed);
        row.put("completedCarLegsWithTrafficEntry", entryObserved);
        row.put("completedPreTrafficWaitPersonHours", completedPreTrafficSeconds / 3600);
        row.put("meanCompletedPreTrafficWaitSeconds", entryObserved == 0 ? null : completedPreTrafficSeconds / entryObserved);
        row.put("abortedObservedCarPersonHours", abortedSeconds / 3600);
        row.put("unfinishedObservedCarPersonHours", unfinishedSeconds / 3600);
        row.put("observedCarPersonHoursLowerBound", (completedSeconds + abortedSeconds + unfinishedSeconds) / 3600);
        row.put("definition", "PersonDeparture(car) to PersonArrival(car); pre-traffic waiting included. Failed/not-started trips prevent a complete total cost; observed elapsed time is only a lower bound, without a failure penalty.");
        return row;
    }
}
