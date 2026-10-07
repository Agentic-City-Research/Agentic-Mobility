package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.util.TravelTime;

/**
 * Expected (time-dependent) travel times used to make the LLM travelers
 * congestion-aware. Holds the most recent day's realized link travel times
 * (from {@link org.matsim.core.trafficmonitoring.TravelTimeCalculator}) and uses
 * them to estimate a candidate route's travel time at a given departure time.
 * Falls back to free-flow when no dynamic data is available yet (day 0) or when
 * a link has no observation in the queried time slot.
 */
public final class ExpectedTravelTimes {

    private final Network network;
    private volatile TravelTime observed = null;

    public ExpectedTravelTimes(Network network) {
        this.network = network;
    }

    /** Replace the observed travel times after each mobsim. */
    public void setObserved(TravelTime observed) {
        this.observed = observed;
    }

    /** @return true once at least one day of realized travel times is available. */
    public boolean hasData() {
        return observed != null;
    }

    /**
     * Expected travel time of the whole route when departing at {@code departureTime},
     * accumulated link-by-link so congestion later in the trip is evaluated at the
     * (shifted) time the vehicle would actually reach that link.
     */
    public double routeTravelTime(RouteCandidate candidate, double departureTime) {
        double t = departureTime;
        for (Id<Link> id : candidate.linkIds()) {
            Link link = network.getLinks().get(id);
            if (link == null) continue;
            t += linkTravelTime(link, t);
        }
        return t - departureTime;
    }

    private double linkTravelTime(Link link, double time) {
        double freeFlow = link.getLength() / Math.max(link.getFreespeed(), 1e-9);
        if (observed == null) return freeFlow;
        double tt = observed.getLinkTravelTime(link, time, null, null);
        if (!Double.isFinite(tt) || tt <= 0) return freeFlow;
        // Realized travel time can never be below free-flow; clamp as a safety net.
        return Math.max(tt, freeFlow);
    }
}
