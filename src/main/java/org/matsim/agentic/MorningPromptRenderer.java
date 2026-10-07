package org.matsim.agentic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A versioned, deterministic information interface shared by live runs and replay. */
public final class MorningPromptRenderer {
    public static final String VERSION = "morning-information-v7-joint-bounded-memory";
    public static final String SYSTEM = """
            You are the individual traveler described below, choosing a route for your own trip.
            Use the supplied facts to make your own decision. Only the route can be changed.
            Departure time, origin, destination, travel mode and other activities are fixed.
            Traffic-service predictions are uncertain estimates, not personal experiences or guarantees.
            Missing personal preferences and earlier experiences are unknown; do not invent them.
            A supplied synthetic profile is a fixed experiment assumption, not measured demographic data.
            Your self-written memory is a subjective interpretation; factual records and stated schedules take precedence.
            The current available-route list is the complete action space for this trip, even if old notes suggest another route.
            Do not invent alternative routes or claim an untried route has a personally observed result.
            An unfinished trip has unknown complete duration, not zero duration.
            Reply with exactly one JSON object containing only routeId and reason:
            {"routeId":"<one listed route identifier>","reason":"<brief explanation>"}
            """;

    public String system(int reasonLimit) {
        return SYSTEM + "Write reason as one short sentence, at most " + reasonLimit
                + " Unicode characters. No reasoning transcript, repeated comparisons or extra fields. Finish the JSON object.\n";
    }

    public String system(int reasonLimit, boolean joint) {
        if (!joint) return system(reasonLimit);
        return system(reasonLimit)
                .replace("Only the route can be changed.", "Choose a listed departure time and a listed route together.")
                .replace("Departure time, origin, destination, travel mode and other activities are fixed.",
                        "Departure options are anchored to the original plan. Origin, destination, mode and other planned activity end times remain fixed. Actual departure can be delayed by preceding activities or traffic.")
                .replace("only routeId and reason", "only routeId, departureSeconds and reason")
                .replace("{\"routeId\":\"<one listed route identifier>\",\"reason\":\"<brief explanation>\"}",
                        "{\"routeId\":\"<listed route>\",\"departureSeconds\":<listed numeric seconds>,\"reason\":\"<brief explanation>\"}");
    }

    public String render(MorningRoutePolicy.Context c) {
        validate(c);
        StringBuilder s = new StringBuilder();
        s.append("YOUR TRIP\nPurpose: ").append(purpose(c.originPurpose())).append(" to ").append(purpose(c.destinationPurpose()))
                .append(". Travel mode: car. Planned departure: ").append(clock(c.plannedDepartureSeconds())).append(".\n");
        if (c.latestArrivalSeconds() != null) s.append("Required arrival by: ").append(clock(c.latestArrivalSeconds()))
                .append(". Arrival later than this is late. Choosing a route does not guarantee punctuality.\n");
        else s.append("No arrival deadline is supplied for this destination.\n");
        appendActionSpace(s, c);
        appendPersonalInformation(s, c.personAttributes(), c.profile(), c.destinationPurpose());
        s.append("PERSONAL DAILY SCHEDULE\n");
        Object activities = c.schedule().get("activities");
        if (activities instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) if (item instanceof Map<?, ?> a) {
                s.append("- ").append(purpose(String.valueOf(a.get("purpose"))));
                appendTime(s, a, "plannedStartSeconds", "; planned start ");
                appendTime(s, a, "plannedEndSeconds", "; planned end ");
                appendTime(s, a, "openingSeconds", "; opens ");
                appendTime(s, a, "closingSeconds", "; closes ");
                appendTime(s, a, "latestStartSeconds", "; required start by ");
                s.append(".\n");
            }
        } else s.append("No additional daily schedule is supplied.\n");
        s.append("YOUR SELF-WRITTEN LONG-TERM MEMORY, BEFORE TODAY\n");
        if (c.memorySummary().isBlank()) s.append("No self-written memory yet; no pre-experiment experiences are assumed.\n");
        else s.append("Last updated after simulation day ").append(c.memorySummaryDay()).append(".\n")
                .append(c.memorySummary()).append('\n');
        if (c.information() != null) {
            s.append("INFORMATION AVAILABLE BEFORE DEPARTURE\n")
                    .append("No network historical or live traffic is available. Both groups receive the same static route distances and free-flow times; only your own past traffic outcomes are known.\n");
            boolean disabled = c.candidates().stream().allMatch(o -> o.pricingKnowledge().source().equals("charging_disabled"));
            if (disabled) s.append("Charging is explicitly disabled in this run. There is no manager toll announcement and no congestion toll applies.\n");
            else if (c.information().currentAnnouncementVisible()) s.append("You can read the current manager toll announcement before departure.\n");
            else s.append("You cannot read the current manager toll announcement. Current tolls are unknown. Use only your own past actual payments as uncertain historical estimates, never as guaranteed current prices. Unknown does not mean free.\n");
            if (c.departureOptions().isEmpty()) {
                s.append("YOUR OWN ACTUAL PAYMENT OBSERVATIONS BEFORE TODAY\n");
                c.information().personalPayments().forEach(e -> appendPayment(s, e));
            } else s.append("Past payments are available only in your recent personal experiences and bounded self-written summary. No separate long-term exact-payment table is supplied.\n");
        }
        s.append(c.information() == null ? "TRAFFIC INFORMATION SERVICE: AVAILABLE ROUTES\n" : "BASIC PATH INFORMATION AND VISIBLE COST COMPONENTS\n")
                .append("Predicted driving time and destination arrival are estimates. Arrival includes the supplied access/egress time; driving time does not.\n")
                .append("Predictions can miss delays such as waiting to enter traffic. They are not guaranteed door-to-door durations.\n")
                .append("Costs below are estimates in scenario monetary units, not an asserted real-world currency.\n");
        for (var o : c.candidates()) {
            s.append("- Route ").append(o.routeId()).append(": predicted driving time ").append(minutes(o.expectedCarSeconds()))
                    .append(" minutes; predicted arrival ").append(clock(o.expectedArrivalSeconds()))
                    .append("; distance ").append(Double.toString(o.distanceMeters())).append(" metres. ");
            var knowledge = o.pricingKnowledge();
            if (knowledge != null) {
                s.append("Base distance cost ").append(Double.toString(knowledge.baseMonetaryCost())).append(" units. ");
                if (knowledge.source().equals("bounded_personal_memory")) {
                    s.append("Current toll and total cost are unknown. Interpret your own recent experiences and summary; unknown does not mean free. ");
                } else if (knowledge.source().equals("own_historical_payments")) {
                    s.append("Current toll: unknown. Historical paid toll component ")
                            .append(Double.toString(knowledge.knownTollSubtotal())).append(" units; cost subtotal including observed historical tolls ")
                            .append(Double.toString(knowledge.baseMonetaryCost() + knowledge.knownTollSubtotal())).append(" units. ")
                            .append("Historical estimates use only your latest own paid entry on the same road and in the same ")
                            .append(c.information().tollHistoryBinSeconds()).append("-second time-of-day bin. They may be outdated. ");
                    if (!knowledge.unknownLinks().isEmpty()) s.append("Full route cost: unknown; missing historical toll observations for road IDs ")
                            .append(Json.write(knowledge.unknownLinks())).append(". These roads are not assumed free. ");
                    else s.append("Historical full-route cost estimate ").append(Double.toString(o.estimatedMonetaryCost()))
                            .append(" units; this is not the current announced cost. ");
                    knowledge.observations().forEach(e -> appendPayment(s, e));
                } else {
                    s.append("Estimated route cost ").append(Double.toString(o.estimatedMonetaryCost())).append(" units. ");
                    if (knowledge.source().equals("charging_disabled")) s.append("Charging is disabled in this run. ");
                    if (!knowledge.announcement().isEmpty()) s.append("Current announced rules on this route: ")
                            .append(Json.write(knowledge.announcement())).append(". Time windows are [startSeconds,endSeconds). ");
                }
            } else s.append("estimated cost ").append(String.format(Locale.ROOT, "%.4f", o.estimatedMonetaryCost())).append(" units. ");
            if (o.tollPolicyId() != null) s.append("This includes an estimated congestion toll of ")
                    .append(Double.toString(o.estimatedToll())).append(" units under public policy ")
                    .append(o.tollPolicyId()).append(" (version ").append(o.tollPolicyVersion())
                    .append("). Actual payment depends on the actual road-entry times. ");
            if (c.information() != null) s.append("Static free-flow estimate; contains no observed network congestion.");
            else if (o.forecastObservedDay() == null) s.append("Initial estimate without prior traffic observations.");
            else s.append("Based on traffic observed on simulation day ").append(o.forecastObservedDay()).append(".");
            s.append("\n");
        }
        s.append("YOUR MOST RECENT PERSONAL EXPERIENCES, OLDEST FIRST\n");
        if (c.history().isEmpty()) s.append("No previous personal experiences are recorded. No pre-experiment habits are assumed.\n");
        for (var e : c.history()) appendExperience(s, e);
        if (!c.departureOptions().isEmpty()) {
            s.append("JOINT TIME AND ROUTE OPTIONS (numeric seconds within the day)\n")
                    .append("The route table above uses original departure. For each selectable departure, the following static estimates use that time; toll estimates use predicted link-entry times, actual charges use executed entries.\n");
            for (var d : c.departureOptions()) {
                s.append("Departure ").append(d.departureSeconds()).append(" (").append(clock(d.departureSeconds())).append("):\n");
                for (var o : d.routes()) s.append("  Route ").append(o.routeId()).append("; free-flow arrival ")
                        .append(clock(o.expectedArrivalSeconds())).append("; estimated toll ").append(o.estimatedToll())
                        .append("; estimated total monetary cost ").append(o.estimatedMonetaryCost()).append(".\n");
            }
        }
        appendActionSpace(s, c);
        s.append(c.departureOptions().isEmpty() ? "Choose one of these routes for this trip; return routeId and one short reason only.\n"
                : "Choose one listed departureSeconds and one listed routeId together; return those two fields and one short reason only.\n");
        return s.toString();
    }

    private static void appendActionSpace(StringBuilder s, MorningRoutePolicy.Context c) {
        s.append("AVAILABLE ACTIONS FOR THIS TRIP: ")
                .append(Json.write(c.candidates().stream().map(MorningRoutePolicy.Option::routeId).toList())).append(".\n");
        if (c.candidates().size() == 1) s.append("There is exactly one available route: ")
                .append(c.candidates().getFirst().routeId())
                .append(c.departureOptions().isEmpty() ? ". No alternative is offered. You cannot change departure time or invent a route to avoid lateness.\n"
                        : ". Only this route is legal; departure-time options are still available.\n");
        if (!c.departureOptions().isEmpty()) s.append("Allowed departureSeconds: ")
                .append(Json.write(c.departureOptions().stream().map(MorningRoutePolicy.DepartureOption::departureSeconds).toList()))
                .append(". Choose only one listed value; never shift yesterday's choice to build new options.\n");
    }

    static void appendPersonalInformation(StringBuilder s, Map<String, Object> attributes, Profile profile, String destination) {
        s.append("PERSONAL INFORMATION\n");
        // Explicit input facts only. Do not expose routing IDs, scoring coefficients or arbitrary technical attributes.
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("age", "Age"); labels.put("sex", "Sex"); labels.put("employment", "Employment");
        labels.put("employed", "Employment status"); labels.put("carAvail", "Car availability");
        labels.put("income", "Income (units as supplied)"); labels.put("incomeUnits", "Income units");
        labels.put("lateToleranceMinutes", "Stated lateness tolerance in minutes");
        labels.put("valueOfTime", "Stated value of time (units as supplied)");
        labels.put("preferences", "Stated preferences"); labels.put("scheduleRequirements", "Stated schedule requirements");
        boolean supplied = false;
        for (var entry : labels.entrySet()) if (attributes.containsKey(entry.getKey()) && attributes.get(entry.getKey()) != null) {
            s.append(entry.getValue()).append(": ").append(attributes.get(entry.getKey())).append(".\n"); supplied = true;
        }
        if (!supplied) s.append("No additional personal attributes are supplied.\n");
        if (profile == null) s.append("Unspecified income, lateness tolerance and time-money preferences are unknown. Do not infer them from demographic facts.\n");
        else {
            s.append("FIXED SYNTHETIC PROFILE (experiment assumptions, not measured attributes)\n")
                    .append("Income category: ").append(profile.income()).append("; ").append(profile.incomeDescription()).append(".\n")
                    .append("Decision style: ").append(profile.decisionStyle()).append("; ").append(profile.decisionStyleDescription()).append(".\n");
            if (destination.equals("work")) s.append("Work timeliness: ").append(profile.workTimeliness()).append("; ").append(switch (profile.workTimeliness()) {
                case MUST_ON_TIME -> "Meeting the stated work deadline is a high priority for you.";
                case SLIGHT_LATE -> "You are less concerned about small lateness than large lateness; the stated deadline is unchanged and any arrival after it is still late.";
                case EARLY_ARRIVAL -> "You prefer time to settle before work; no numerical preparation period is assumed.";
            }).append('\n');
            else s.append("Work-timeliness preferences do not create a work deadline for this non-work destination.\n");
            s.append("Supplied input attributes and schedules take precedence over conflicting synthetic descriptions. No numerical income, tolerance or value-of-time is inferred from a category.\n");
        }
    }

    static void appendExperience(StringBuilder s, MorningMemory.Experience e) {
        s.append("- Simulation day ").append(e.day()).append(", route ").append(e.routeId());
        if (e.selectedDepartureSeconds() != null) s.append("; selected departure ").append(clock(e.selectedDepartureSeconds()));
        if (e.departureSeconds() != null) s.append("; actual departure ").append(clock(e.departureSeconds()));
        s.append("; actual driving time ").append(e.carSeconds() == null ? "unknown (driving not completed)" : minutes(e.carSeconds()) + " minutes");
        if (e.doorSeconds() != null) s.append("; actual total trip time ").append(minutes(e.doorSeconds())).append(" minutes");
        s.append("; destination arrival ").append(e.arrivalSeconds() == null ? "not recorded (destination not reached)" : clock(e.arrivalSeconds()));
        s.append("; lateness ").append(e.lateSeconds() == null ? "unknown or no deadline" : minutes(e.lateSeconds()) + " minutes");
        s.append("; trip unfinished: ").append(e.arrivalSeconds() == null ? "yes" : "no");
        s.append("; trip terminated while stuck: ").append(e.stuck() ? "yes" : "no").append(".\n");
        if (e.tollPaid() != null) s.append("  Actual congestion toll paid on this trip: ")
                .append(Double.toString(e.tollPaid())).append(" scenario monetary units.\n");
        e.tollObservations().forEach(p -> appendPayment(s, p));
    }

    static void appendPayment(StringBuilder s, MorningRoutePolicy.TollObservation e) {
        s.append("  Own payment: simulation day ").append(e.day()).append(", road ").append(e.linkId())
                .append(", entry at ").append(clock(e.time())).append(", paid ").append(Double.toString(e.amount()))
                .append(" units. This is a historical point observation, not a tariff window.\n");
    }

    static void validatePayment(MorningRoutePolicy.TollObservation e, int beforeDay) {
        if (e.day() < 0 || e.day() >= beforeDay || e.linkId() == null || e.linkId().isBlank()
                || !Double.isFinite(e.amount()) || e.amount() <= 0) throw new IllegalArgumentException("Invalid/future personal payment");
        requireTime(e.time());
    }

    public static void validate(MorningRoutePolicy.Context c) {
        if (c.day() < 0 || c.candidates().isEmpty() || c.candidates().size() > 4)
            throw new IllegalArgumentException("Invalid day or candidate count");
        requireTime(c.plannedDepartureSeconds());
        if (c.latestArrivalSeconds() != null) requireTime(c.latestArrivalSeconds());
        if (c.memorySummary().isBlank() != (c.memorySummaryDay() == null)
                || c.memorySummaryDay() != null && (c.memorySummaryDay() < 0 || c.memorySummaryDay() >= c.day()))
            throw new IllegalArgumentException("Same-day/future or undated memory summary in route context");
        Set<String> ids = new HashSet<>();
        var information = c.information();
        if (information != null) {
            if (!Set.of("informed", "uninformed").contains(information.group()) || information.networkTrafficVisible()
                    || information.currentAnnouncementVisible() != information.group().equals("informed")
                    || information.tollHistoryBinSeconds() < 1 || information.personalPayments() == null)
                throw new IllegalArgumentException("Invalid traveler information boundary");
            information.personalPayments().forEach(e -> validatePayment(e, c.day()));
        }
        for (var o : c.candidates()) {
            if (o.routeId() == null || !o.routeId().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(o.routeId())
                    || o.chosenCount() < 0 || o.experiencedCount() < 0 || o.experiencedCount() > o.chosenCount()
                    || o.estimatedMonetaryCost() != null && !Double.isFinite(o.estimatedMonetaryCost())) throw new IllegalArgumentException("Invalid route facts");
            if (o.estimatedToll() != null && (!Double.isFinite(o.estimatedToll()) || o.estimatedToll() < 0) || o.tollPolicyVersion() < 0)
                throw new IllegalArgumentException("Invalid public toll forecast");
            validateKnowledge(c, o);
            requireTime(o.expectedCarSeconds()); requireTime(o.expectedArrivalSeconds());
            if (o.forecastObservedDay() != null && o.forecastObservedDay() >= c.day()
                    || o.lastPersonalExperienceDay() != null && o.lastPersonalExperienceDay() >= c.day())
                throw new IllegalArgumentException("Same-day/future information in route context");
        }
        int last = -1;
        for (var e : c.history()) {
            if (e.day() <= last || e.day() >= c.day()) throw new IllegalArgumentException("Same-day/future or unordered personal experience");
            last = e.day();
            for (Double value : Arrays.asList(e.carSeconds(), e.arrivalSeconds(), e.lateSeconds(), e.departureSeconds(), e.doorSeconds()))
                if (value != null) requireTime(value);
            if (e.tollPaid() != null) requireTime(e.tollPaid());
            e.tollObservations().forEach(p -> { validatePayment(p, c.day()); if (p.day() != e.day()) throw new IllegalArgumentException("Payment and experience day mismatch"); });
        }
        double previousDeparture = -1;
        for (var d : c.departureOptions()) {
            requireTime(d.departureSeconds());
            if (d.departureSeconds() <= previousDeparture) throw new IllegalArgumentException("Departure options must be unique and ordered");
            previousDeparture = d.departureSeconds();
            if (!d.routes().stream().map(MorningRoutePolicy.Option::routeId).toList().equals(c.candidates().stream().map(MorningRoutePolicy.Option::routeId).toList()))
                throw new IllegalArgumentException("Joint options must retain the same ordered route pool");
            validate(new MorningRoutePolicy.Context(c.day(), c.tripId(), c.personId(), c.originPurpose(), c.destinationPurpose(),
                    d.departureSeconds(), c.latestArrivalSeconds(), c.personAttributes(), c.schedule(), d.routes(), c.history(),
                    c.profile(), c.memorySummary(), c.memorySummaryDay(), c.information()));
        }
        if (!c.departureOptions().isEmpty() && c.departureOptions().stream().noneMatch(d -> d.departureSeconds() == c.plannedDepartureSeconds()))
            throw new IllegalArgumentException("Original departure must remain an option");
    }

    public static MorningRoutePolicy.Context order(MorningRoutePolicy.Context c, String mode, long seed) {
        var options = new ArrayList<>(c.candidates());
        switch (mode) {
            case "original" -> { }
            case "reverse" -> Collections.reverse(options);
            case "shuffle" -> Collections.shuffle(options, new Random(seed ^ ((long)c.day() << 32) ^ c.tripId().hashCode()));
            default -> throw new IllegalArgumentException("Order must be original, reverse or shuffle");
        }
        return new MorningRoutePolicy.Context(c.day(), c.tripId(), c.personId(), c.originPurpose(), c.destinationPurpose(),
                c.plannedDepartureSeconds(), c.latestArrivalSeconds(), c.personAttributes(), c.schedule(), List.copyOf(options), c.history(),
                c.profile(), c.memorySummary(), c.memorySummaryDay(), c.information(), c.departureOptions().stream()
                .map(d -> new MorningRoutePolicy.DepartureOption(d.departureSeconds(), options.stream().map(o -> d.routes().stream()
                        .filter(r -> r.routeId().equals(o.routeId())).findFirst().orElseThrow()).toList())).toList());
    }

    private static void validateKnowledge(MorningRoutePolicy.Context c, MorningRoutePolicy.Option o) {
        var k = o.pricingKnowledge();
        if (c.information() == null) {
            if (o.estimatedMonetaryCost() == null || o.estimatedToll() == null || k != null)
                throw new IllegalArgumentException("Missing legacy route estimates");
            return;
        }
        var i = c.information();
        if (k == null || !k.group().equals(i.group()) || !Set.of("current_announcement", "own_historical_payments", "bounded_personal_memory", "charging_disabled").contains(k.source())
                || !Double.isFinite(k.baseMonetaryCost()) || !Double.isFinite(k.knownTollSubtotal()) || k.knownTollSubtotal() < 0
                || k.unknownLinks() == null || k.observations() == null || k.announcement() == null
                || !o.links().containsAll(k.unknownLinks()) || o.forecastObservedDay() != null || !o.estimateSource().equals("static_free_flow"))
            throw new IllegalArgumentException("Invalid visible cost/traffic knowledge");
        if (i.group().equals("uninformed") && (o.tollPolicyId() != null || o.tollPolicyVersion() != 0 || !k.announcement().isEmpty()
                || k.source().equals("current_announcement"))) throw new IllegalArgumentException("Latest policy leaked to uninformed traveler");
        if (k.source().equals("own_historical_payments") && i.currentAnnouncementVisible()
                || k.source().equals("current_announcement") && !i.currentAnnouncementVisible())
            throw new IllegalArgumentException("Toll source disagrees with information group");
        for (var observation : k.observations()) {
            validatePayment(observation, c.day());
            if (!o.links().contains(observation.linkId()) || !i.personalPayments().contains(observation))
                throw new IllegalArgumentException("Toll estimate lacks own actual-payment provenance");
        }
        if (k.source().equals("own_historical_payments")) {
            double observedSubtotal = k.observations().stream().mapToDouble(MorningRoutePolicy.TollObservation::amount).sum();
            var accountedLinks = new ArrayList<>(k.unknownLinks());
            k.observations().forEach(e -> accountedLinks.add(e.linkId()));
            var expectedLinks = new ArrayList<>(o.links()); Collections.sort(expectedLinks); Collections.sort(accountedLinks);
            if (Math.abs(k.knownTollSubtotal() - observedSubtotal) > 1e-8 || !expectedLinks.equals(accountedLinks))
                throw new IllegalArgumentException("Historical costs must account for every observed and unknown route entry");
        } else if (k.source().equals("bounded_personal_memory")) {
            if (!i.group().equals("uninformed") || !k.unknownLinks().equals(o.links()) || !k.observations().isEmpty()
                    || !i.personalPayments().isEmpty() || k.knownTollSubtotal() != 0)
                throw new IllegalArgumentException("Bounded memory must not retrieve exact long-term toll facts");
        } else if (!k.unknownLinks().isEmpty() || !k.observations().isEmpty())
            throw new IllegalArgumentException("Public/disabled toll estimates must not mix private historical components");
        for (var rule : k.announcement()) {
            if (!o.links().contains(rule.linkId()) || !Double.isFinite(rule.amount()) || rule.amount() < 0
                    || !Double.isFinite(rule.startSeconds()) || !Double.isFinite(rule.endSeconds())
                    || rule.startSeconds() < 0 || rule.endSeconds() <= rule.startSeconds())
                throw new IllegalArgumentException("Invalid visible announcement");
        }
        boolean complete = k.unknownLinks().isEmpty();
        if (complete != (o.estimatedToll() != null) || complete != (o.estimatedMonetaryCost() != null))
            throw new IllegalArgumentException("Unknown tolls must remain null, not zero");
        if (complete && (o.estimatedToll() != k.knownTollSubtotal()
                || Math.abs(o.estimatedMonetaryCost() - k.baseMonetaryCost() - k.knownTollSubtotal()) > 1e-8))
            throw new IllegalArgumentException("Visible route cost components disagree");
    }

    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void requireTime(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid time fact");
    }
    private static void appendTime(StringBuilder s, Map<?, ?> a, String key, String label) {
        if (a.get(key) instanceof Number n) { requireTime(n.doubleValue()); s.append(label).append(clock(n.doubleValue())); }
    }
    private static String purpose(String p) {
        return switch (p) { case "home" -> "home"; case "work" -> "work"; case "secondary" -> "other activity"; default -> p; };
    }
    private static String minutes(double seconds) { return String.format(Locale.ROOT, "%.2f", seconds / 60); }
    private static String clock(double seconds) {
        long t = Math.round(seconds);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", t / 3600, t / 60 % 60, t % 60);
    }
}
