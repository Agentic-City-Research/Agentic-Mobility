package org.matsim.agentic;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.algorithms.XY2Links;
import org.matsim.core.router.TripStructureUtils;

import java.util.*;

/** Fixed membership by the original input schedule, including all trip purposes. */
final class MorningCohort {
    static final class Member {
        final Person person;
        final int tripIndex;
        final String key, originPurpose, destinationPurpose;
        final double plannedDeparture;
        List<Double> departureOptions = List.of();
        double selectedDeparture;
        Double previousSelectedDeparture;
        boolean timeSwitched;
        final Double plannedArrival;
        final Double deadline;
        final List<RouteCandidate> candidates;
        final MorningMemory memory;
        final Map<String, Object> originalAttributes;
        Profile profile;
        List<Map<String, Object>> originalSchedule = List.of();
        MorningRoutePolicy.Context previousContext;
        String routeId, previousFingerprint;
        boolean switched, controlled;
        String informationGroup = "informed";
        double actualRouteFreeFlow, actualRouteDistance, nonCarSeconds;

        Member(Person person, int index, TripStructureUtils.Trip trip, List<RouteCandidate> candidates,
               Double deadline, int history) {
            this.person = person; tripIndex = index; key = person.getId() + "/" + index;
            originPurpose = trip.getOriginActivity().getType(); destinationPurpose = trip.getDestinationActivity().getType();
            plannedDeparture = trip.getOriginActivity().getEndTime().seconds();
            selectedDeparture = plannedDeparture;
            plannedArrival = trip.getDestinationActivity().getStartTime().isDefined()
                    ? trip.getDestinationActivity().getStartTime().seconds() : null;
            this.candidates = candidates; this.deadline = deadline; memory = new MorningMemory(history);
            originalAttributes = Collections.unmodifiableMap(new LinkedHashMap<>(person.getAttributes().getAsMap()));
        }

        TripStructureUtils.Trip currentTrip() {
            return TripStructureUtils.getTrips(person.getSelectedPlan()).get(tripIndex);
        }

        Map<String, Object> roster() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tripId", key); row.put("personId", person.getId().toString()); row.put("tripIndex", tripIndex);
            row.put("originPurpose", originPurpose); row.put("destinationPurpose", destinationPurpose);
            row.put("plannedDepartureSeconds", plannedDeparture); row.put("plannedArrivalSeconds", plannedArrival);
            row.put("departureOptionsSeconds", departureOptions);
            row.put("latestArrivalSeconds", deadline);
            row.put("originLink", candidates.getFirst().linkIds().getFirst().toString());
            row.put("destinationLink", candidates.getFirst().linkIds().getLast().toString());
            row.put("candidateCount", candidates.size()); row.put("personAttributes", originalAttributes);
            row.put("profile", profile);
            row.put("controlled", controlled);
            row.put("informationGroup", informationGroup);
            return row;
        }
    }

    final Map<String, Member> members = new LinkedHashMap<>();
    final Set<String> persons = new HashSet<>();
    final Set<String> controlledPersons = new TreeSet<>();
    final MatsimOdCandidates generator;
    final String selection;
    final double windowStart, windowEnd;
    int excludedMissingPlannedArrivalTrips, excludedInvalidPlannedArrivalTrips;
    MorningTravelerInformation information;
    private record ActivityAnchor(String purpose, org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> link,
                                  Double endTime) {}
    private final Map<Person, List<ActivityAnchor>> anchors = new LinkedHashMap<>();

    MorningCohort(Scenario scenario, double start, double end, int history) {
        this(scenario, start, end, history, new MatsimOdCandidates(scenario.getNetwork(), scenario.getConfig()));
    }

    MorningCohort(Scenario scenario, double start, double end, int history, MatsimOdCandidates generator) {
        this(scenario, start, end, history, generator, "planned-departure", end);
    }

    MorningCohort(Scenario scenario, double start, double end, int history, MatsimOdCandidates generator,
                  String selection, double plannedArrivalBefore) {
        this.generator = generator;
        if (!Set.of("planned-arrival", "planned-departure").contains(selection))
            throw new IllegalArgumentException("Unknown cohort selection: " + selection);
        this.selection = selection;
        windowStart = selection.equals("planned-arrival") ? 0 : start;
        windowEnd = selection.equals("planned-arrival") ? plannedArrivalBefore : end;
        if (!Double.isFinite(windowStart) || !Double.isFinite(windowEnd) || windowStart < 0 || windowEnd <= windowStart)
            throw new IllegalArgumentException("Invalid cohort selection window");
        XY2Links links = new XY2Links(scenario);
        var sorted = scenario.getPopulation().getPersons().values().stream()
                .sorted(Comparator.comparing(p -> p.getId().toString())).toList();
        for (Person person : sorted) {
            links.run(person.getSelectedPlan());
            anchors.put(person, activities(person.getSelectedPlan()).stream().map(a -> new ActivityAnchor(
                    a.getType(), a.getLinkId(), a.getEndTime().isDefined() ? a.getEndTime().seconds() : null)).toList());
            var trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (int i = 0; i < trips.size(); i++) {
                var trip = trips.get(i);
                Activity origin = trip.getOriginActivity();
                var carLegs = trip.getLegsOnly().stream().filter(l -> l.getMode().equals("car")).toList();
                if (carLegs.isEmpty()) continue;
                if (selection.equals("planned-arrival")) {
                    var arrival = trip.getDestinationActivity().getStartTime();
                    if (!arrival.isDefined()) { excludedMissingPlannedArrivalTrips++; continue; }
                    if (!Double.isFinite(arrival.seconds()) || arrival.seconds() < 0) {
                        excludedInvalidPlannedArrivalTrips++; continue;
                    }
                    if (arrival.seconds() >= windowEnd) continue;
                }
                if (!origin.getEndTime().isDefined())
                    throw new IllegalArgumentException("Car trip lacks planned activity end time: " + person.getId() + "/" + i);
                double departure = origin.getEndTime().seconds();
                if (!Double.isFinite(departure) || departure < 0)
                    throw new IllegalArgumentException("Invalid planned departure time: " + person.getId() + "/" + i);
                if (selection.equals("planned-departure") && (departure < start || departure >= end)) continue;
                if (selection.equals("planned-arrival") && trip.getDestinationActivity().getStartTime().seconds() < departure) {
                    excludedInvalidPlannedArrivalTrips++; continue;
                }
                if (carLegs.size() != 1) throw new IllegalArgumentException("Expected one car leg per trip: " + person.getId() + "/" + i);
                var activityParams = scenario.getConfig().scoring().getActivityParams(trip.getDestinationActivity().getType());
                Double deadline = activityParams != null && activityParams.getLatestStartTime().isDefined()
                        ? activityParams.getLatestStartTime().seconds() : null;
                Member m = new Member(person, i, trip, generator.compute(origin.getLinkId(), trip.getDestinationActivity().getLinkId(), departure, person),
                        deadline, history);
                List<Map<String, Object>> schedule = new ArrayList<>();
                for (Activity a : activities(person.getSelectedPlan())) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("purpose", a.getType());
                    row.put("plannedStartSeconds", a.getStartTime().isDefined() ? a.getStartTime().seconds() : null);
                    row.put("plannedEndSeconds", a.getEndTime().isDefined() ? a.getEndTime().seconds() : null);
                    var params = scenario.getConfig().scoring().getActivityParams(a.getType());
                    if (params != null) {
                        row.put("openingSeconds", params.getOpeningTime().isDefined() ? params.getOpeningTime().seconds() : null);
                        row.put("closingSeconds", params.getClosingTime().isDefined() ? params.getClosingTime().seconds() : null);
                        row.put("latestStartSeconds", params.getLatestStartTime().isDefined() ? params.getLatestStartTime().seconds() : null);
                    }
                    schedule.add(Collections.unmodifiableMap(row));
                }
                m.originalSchedule = List.copyOf(schedule);
                members.put(m.key, m); persons.add(person.getId().toString());
            }
        }
    }

    void selectControlledPeople(int count, long seed) {
        var ids = new ArrayList<>(new TreeSet<>(persons));
        if (count < 0 || count > ids.size()) throw new IllegalArgumentException("Requested controlled persons " + count
                + " exceeds morning cohort " + ids.size());
        Collections.shuffle(ids, new Random(seed));
        controlledPersons.clear(); controlledPersons.addAll(ids.subList(0, count));
        members.values().forEach(m -> m.controlled = controlledPersons.contains(m.person.getId().toString()));
    }

    void configureDepartureOptions(MorningExperiment settings, double horizon) {
        for (var m : members.values()) {
            var start = m.currentTrip().getOriginActivity().getStartTime();
            double earliest = start.isDefined() ? Math.max(0, start.seconds()) : 0;
            m.departureOptions = MorningDepartureOptions.generate(m.plannedDeparture, earliest, horizon,
                    settings.departureBeforeSeconds, settings.departureAfterSeconds, settings.departureStepSeconds);
        }
    }

    void configureInformation(Collection<String> people, double informedShare, long seed, int binSeconds) {
        information = new MorningTravelerInformation(people, informedShare, seed, binSeconds);
        members.values().forEach(m -> m.informationGroup = information.group(m.person.getId().toString()));
    }

    void assignProfiles(String mode, long seed, Map<String, Profile> imported) {
        for (var member : members.values()) {
            if (!member.controlled || mode.equals("input")) continue;
            String id = member.person.getId().toString();
            if (imported != null) {
                member.profile = imported.get(id);
                if (member.profile == null) throw new IllegalArgumentException("Profile file lacks controlled person " + id);
            } else member.profile = MorningProfiles.sample(id, seed);
        }
    }

    // Native ReRoute copies and selects plans. Never restore times through references
    // to activities from the original plan, which may no longer be selected.
    void restorePlannedTimes() {
        anchors.forEach((person, saved) -> {
            var current = activities(person.getSelectedPlan());
            if (current.size() != saved.size()) throw new IllegalStateException("Activity structure changed: " + person.getId());
            for (int i = 0; i < saved.size(); i++) {
                var a = current.get(i); var original = saved.get(i);
                if (!a.getType().equals(original.purpose()) || !Objects.equals(a.getLinkId(), original.link()))
                    throw new IllegalStateException("Activity OD changed: " + person.getId() + "/" + i);
                if (original.endTime() == null) a.setEndTimeUndefined(); else a.setEndTime(original.endTime());
            }
        });
    }

    private static List<Activity> activities(Plan plan) {
        return plan.getPlanElements().stream().filter(Activity.class::isInstance).map(Activity.class::cast)
                .filter(a -> !TripStructureUtils.isStageActivityType(a.getType())).toList();
    }

    static Leg carLeg(TripStructureUtils.Trip trip) {
        return trip.getLegsOnly().stream().filter(l -> l.getMode().equals("car")).findFirst().orElseThrow();
    }
}
