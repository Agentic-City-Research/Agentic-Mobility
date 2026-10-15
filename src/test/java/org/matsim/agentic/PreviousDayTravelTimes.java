package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.router.util.TravelTime;
import java.util.*;

/** Completed-day copy which survives the event manager's accumulator reset. */
final class PreviousDayTravelTimes {
    private final Network network;
    private final double binSeconds;
    private final int bins;
    private Map<Id<Link>, double[]> snapshot = Map.of();
    private Integer day;

    PreviousDayTravelTimes(Network network, double binSeconds, double horizon) {
        if (!(binSeconds > 0 && horizon > 0)) throw new IllegalArgumentException("Invalid travel-time bins");
        this.network = network; this.binSeconds = binSeconds; bins = Math.max(1, (int) Math.ceil(horizon / binSeconds));
    }
    void capture(TravelTime observed, int completedDay) {
        Map<Id<Link>, double[]> copy = new HashMap<>();
        for (Link link : network.getLinks().values()) {
            if (!link.getAllowedModes().contains("car")) continue;
            double[] values = new double[bins];
            for (int b = 0; b < bins; b++) {
                double value = observed.getLinkTravelTime(link, b * binSeconds, null, null);
                values[b] = Double.isFinite(value) ? Math.max(MatsimOdCandidates.freeFlow(link), value) : MatsimOdCandidates.freeFlow(link);
            }
            copy.put(link.getId(), values);
        }
        snapshot = Collections.unmodifiableMap(copy); day = completedDay;
    }
    Integer observedDay() { return day; }
    double seconds(Link link, double departure) {
        var values = snapshot.get(link.getId());
        if (values == null) return MatsimOdCandidates.freeFlow(link);
        return values[Math.max(0, Math.min(bins - 1, (int) Math.floor(departure / binSeconds)))];
    }
}
