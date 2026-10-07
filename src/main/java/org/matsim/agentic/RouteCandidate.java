package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.util.List;

/**
 * A precomputed, stable car route between an origin and destination link.
 * {@code linkIds} is the full ordered path including start and end links.
 * Computed from free-flow information only; never reordered or replaced by the
 * framework once accepted.
 */
public record RouteCandidate(String id, List<Id<Link>> linkIds, double freeFlowTravelTime) {
}
