package org.matsim.agentic;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates every retained person's car OD (all persons first, then LLM
 * selection), classifies excluded persons by reason, and maps activities to car
 * links. Activities in this scenario reference facilities with coordinates but
 * no links, so links are resolved to the nearest network link (the same mapping
 * MATSim applies via XY2Links).
 */
public final class CarScenario {

    /** A successfully validated person, with the info needed later. */
    public record PersonCheck(
            Id<Person> personId,
            List<Id<Link>> activityLinkIds,
            double outboundAnchor,
            double returnAnchor,
            boolean llmEligible) {
    }

    public static final class Result {
        public final Map<Id<Person>, PersonCheck> validated = new LinkedHashMap<>();
        public final Map<String, Integer> exclusionReasons = new LinkedHashMap<>();
        public final List<Id<Person>> llmEligible = new ArrayList<>();
    }

    private final Network network;
    private final ActivityFacilities facilities;
    private final RouteCandidates routeCandidates;
    private final Map<Id<ActivityFacility>, Id<Link>> facilityLinkCache = new HashMap<>();

    public CarScenario(Scenario scenario, RouteCandidates routeCandidates) {
        this.network = scenario.getNetwork();
        this.facilities = scenario.getActivityFacilities();
        this.routeCandidates = routeCandidates;
    }

    public Result validate(Population population) {
        Result result = new Result();
        for (Person person : population.getPersons().values()) {
            String reason = classify(person);
            if (reason != null) {
                result.exclusionReasons.merge(reason, 1, Integer::sum);
                continue;
            }
            PersonCheck check = buildCheck(person);
            result.validated.put(person.getId(), check);
            if (check.llmEligible()) result.llmEligible.add(person.getId());
        }
        return result;
    }

    /**
     * Resolve the car link an activity is located on, mirroring MATSim's own
     * facility-to-link assignment: a direct link if present, otherwise the
     * nearest network link to the referenced facility's coordinate (MATSim's
     * {@code XY2Links} copies the facility coordinate onto the activity before
     * taking the nearest link, so we resolve from the facility coordinate, not
     * the activity coordinate, to stay bit-consistent with the runtime mapping).
     */
    public Id<Link> resolveLink(Activity activity) {
        Id<Link> linkId = activity.getLinkId();
        if (linkId != null) return linkId;
        Id<ActivityFacility> fid = activity.getFacilityId();
        if (fid != null) {
            Id<Link> cached = facilityLinkCache.get(fid);
            if (cached != null) return cached;
            ActivityFacility facility = facilities.getFacilities().get(fid);
            Coord coord = facility != null ? facility.getCoord() : activity.getCoord();
            if (coord != null) {
                Link nearest = NetworkUtils.getNearestLink(network, coord);
                facilityLinkCache.put(fid, nearest.getId());
                return nearest.getId();
            }
        }
        Coord coord = activity.getCoord();
        if (coord != null) {
            Link nearest = NetworkUtils.getNearestLink(network, coord);
            return nearest.getId();
        }
        return null;
    }

    private String classify(Person person) {
        Plan plan = person.getSelectedPlan();
        if (plan == null) return "no-selected-plan";
        List<Activity> activities = new ArrayList<>();
        List<Leg> legs = new ArrayList<>();
        for (PlanElement e : plan.getPlanElements()) {
            if (e instanceof Activity a) activities.add(a);
            else if (e instanceof Leg l) legs.add(l);
        }
        if (activities.size() != legs.size() + 1) return "malformed-plan";
        for (Leg l : legs) {
            if (!"car".equals(l.getMode())) return "non-car-mode";
        }
        List<Id<Link>> linkIds = new ArrayList<>();
        for (Activity a : activities) {
            Id<Link> linkId = resolveLink(a);
            if (linkId == null) return "activity-no-link";
            Link link = network.getLinks().get(linkId);
            if (link == null) return "activity-link-not-in-network";
            if (!link.getAllowedModes().contains("car")) return "activity-link-not-car";
            linkIds.add(linkId);
        }
        for (int i = 0; i < legs.size(); i++) {
            if (!routeCandidates.isReachable(linkIds.get(i), linkIds.get(i + 1))) {
                return "od-unreachable";
            }
        }
        return null;
    }

    private PersonCheck buildCheck(Person person) {
        Plan plan = person.getSelectedPlan();
        List<Activity> activities = new ArrayList<>();
        List<Leg> legs = new ArrayList<>();
        for (PlanElement e : plan.getPlanElements()) {
            if (e instanceof Activity a) activities.add(a);
            else if (e instanceof Leg l) legs.add(l);
        }
        List<Id<Link>> linkIds = new ArrayList<>();
        for (Activity a : activities) linkIds.add(resolveLink(a));

        double outbound = endTimeSeconds(activities.get(0), legs.get(0));
        double returnTime = activities.size() >= 3 ? endTimeSeconds(activities.get(1), legs.get(1)) : -1;

        boolean llmEligible = activities.size() == 3
                && "home".equals(activities.get(0).getType())
                && "work".equals(activities.get(1).getType())
                && "home".equals(activities.get(2).getType())
                && linkIds.get(0).equals(linkIds.get(2))
                && outbound >= 0 && outbound < 86400
                && returnTime >= 0 && returnTime < 86400
                && outbound < returnTime;

        return new PersonCheck(person.getId(), linkIds, outbound, returnTime, llmEligible);
    }

    private double endTimeSeconds(Activity activity, Leg leg) {
        OptionalTime end = activity.getEndTime();
        if (end.isDefined()) return end.seconds();
        OptionalTime dep = leg.getDepartureTime();
        return dep.isDefined() ? dep.seconds() : -1.0;
    }

    public Network network() {
        return network;
    }
}
