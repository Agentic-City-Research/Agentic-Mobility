package org.matsim.agentic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A versioned, deterministic information interface shared by live runs and replay. */
public final class MorningPromptRenderer {
    public static final String VERSION = "morning-information-v9-continuous-memory";
    static final int RECENT_FACT_DAYS = 3;
    static final String FACT_RULES = """
            Use only supplied facts. Missing preferences, numeric income, value of time, lateness tolerance and earlier experiences are unknown; do not invent them.
            Synthetic profiles are fixed experiment assumptions, not measured demographics. Explicit attributes and schedules take precedence over profiles and subjective memory.
            The listed route pool is complete, even if old notes suggest another route. Untried routes have no personally observed outcome.
            Tables are JSON-array rows in their stated column order. Time fields ending in _s are numeric seconds within the day or durations; distances are metres; costs are scenario monetary units, not an asserted real currency.
            null means unknown or not supplied, never zero or free. For unfinished trips, complete duration and arrival are unknown; null lateness can also mean no supplied deadline.
            Payment rows are [day,linkId,entry_s,amount]: actual historical point observations, not current tariff windows or evidence that other roads are free.
            Recent execution records are factual evidence. Your memory is a personal summary, not an execution log.
            Correct clear contradictions with visible execution facts yourself; do not discard older experience merely because it is outside the factual window.
            """;
    public static final String SYSTEM = FACT_RULES + """
            You are the individual traveler described below, choosing a route for your own trip.
            Use the supplied facts to make your own decision. Only the route can be changed.
            Departure time, origin, destination, travel mode and other activities are fixed.
            Choose only listed actions; never shift yesterday's choice to create new departure options or invent routes.
            Predictions are uncertain estimates, not personal experiences or guarantees. Predicted arrival includes access/egress; driving duration does not. Predictions can miss delays such as waiting to enter traffic.
            Estimated tolls use predicted road-entry times; actual charges use executed entries. Announced windows are [start_s,end_s).
            Reply with exactly one JSON object containing only routeId and reason:
            {"routeId":"<one listed route identifier>","reason":"<brief explanation>"}
            """;

    public String system(int reasonLimit) {
        return SYSTEM + "Write reason as one short sentence, at most " + reasonLimit
                + " Unicode characters, including spaces and punctuation. No reasoning transcript, repeated comparisons or extra fields.\n"
                + "Return one complete JSON object. No Markdown, text outside JSON, or unnecessary formatting whitespace.\n";
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
        appendPersonalInformation(s, c.personAttributes(), c.profile(), c.destinationPurpose());
        s.append("PERSONAL DAILY SCHEDULE\n");
        Object activities = c.schedule().get("activities");
        if (activities instanceof List<?> list && !list.isEmpty()) {
            s.append("columns=[purpose,plannedStart_s,plannedEnd_s,opening_s,closing_s,latestStart_s]\n");
            for (Object item : list) if (item instanceof Map<?, ?> a) {
                s.append(Json.write(Arrays.asList(purpose(String.valueOf(a.get("purpose"))),
                        scheduleTime(a,"plannedStartSeconds"), scheduleTime(a,"plannedEndSeconds"),
                        scheduleTime(a,"openingSeconds"), scheduleTime(a,"closingSeconds"), scheduleTime(a,"latestStartSeconds")))).append('\n');
            }
        } else s.append("No additional daily schedule is supplied.\n");
        // Candidate display order can be shuffled; keep it after invariant personal facts.
        appendActionSpace(s, c);
        s.append("ROUTE ATTRIBUTES\ncolumns=[routeId,distance_m,baseDistanceCost]\n");
        for (var o : c.candidates()) s.append(Json.write(Arrays.asList(o.routeId(),o.distanceMeters(),
                o.pricingKnowledge()==null?null:o.pricingKnowledge().baseMonetaryCost()))).append('\n');
        // All daily data follows the fixed trip/profile/schedule/pool prefix.
        s.append("SIMULATION DAY ").append(c.day()).append('\n');
        if (c.information() != null) {
            s.append("INFORMATION AVAILABLE BEFORE DEPARTURE\n")
                    .append("No network historical or live traffic is available. Both groups receive the same static route distances and free-flow times; only your own past traffic outcomes are known.\n");
            boolean disabled = c.candidates().stream().allMatch(o -> o.pricingKnowledge().source().equals("charging_disabled"));
            if (disabled) s.append("Charging is explicitly disabled in this run. There is no manager toll announcement and no congestion toll applies.\n");
            else if (c.information().currentAnnouncementVisible()) s.append("You can read the current manager toll announcement before departure.\n");
            else s.append("You cannot read the current manager toll announcement. Current tolls are unknown. Use only your own past actual payments as uncertain historical estimates, never as guaranteed current prices. Unknown does not mean free.\n");
            if (c.departureOptions().isEmpty()) {
                s.append("YOUR OWN ACTUAL PAYMENT OBSERVATIONS BEFORE TODAY\ncolumns=[day,linkId,entry_s,amount]\n");
                c.information().personalPayments().forEach(e -> appendPayment(s, e));
            } else s.append("Past payments are available only in your recent personal experiences and bounded self-written summary. No separate long-term exact-payment table is supplied.\n");
        }
        appendChoices(s,c);
        s.append("YOUR SELF-WRITTEN LONG-TERM MEMORY, BEFORE TODAY\n");
        if (c.memorySummary().isBlank()) s.append("No self-written memory yet; no pre-experiment experiences are assumed.\n");
        else s.append("Last updated after simulation day ").append(c.memorySummaryDay()).append(".\n").append(c.memorySummary()).append('\n');
        if(c.day()==0) s.append("No prior execution days exist yet.\n");
        else s.append("Recent factual window before today's decision: simulation days ").append(Math.max(0,c.day()-RECENT_FACT_DAYS))
                .append(" through ").append(c.day()-1).append("; read only supplied records. Older experience may persist in your memory.\n");
        appendExperiences(s,recentFacts(c.history(),c.day(),false,RECENT_FACT_DAYS),"YOUR MOST RECENT PERSONAL EXPERIENCES, OLDEST FIRST (ACTUAL EXECUTED RESULTS)");
        s.append(c.departureOptions().isEmpty() ? "Choose one of these routes for this trip; return routeId and one short reason only.\n"
                : "Choose one listed departureSeconds and one listed routeId together; return those two fields and one short reason only.\n");
        return s.toString();
    }

    private static void appendChoices(StringBuilder s, MorningRoutePolicy.Context c) {
        boolean joint = !c.departureOptions().isEmpty();
        s.append(joint ? "JOINT TIME AND ROUTE OPTIONS\n" : "ROUTE ESTIMATES AT PLANNED DEPARTURE\n");
        s.append("PREDICTIONS: the following rows are estimates, not actual executed results. Units follow FACT_RULES.\n");
        s.append(c.information()==null ? "forecastDay=null: initial estimate without prior traffic observations; otherwise the simulation day supplying traffic observations.\n"
                : "Static free-flow estimates; no observed network congestion.\n");
        var choices = joint ? c.departureOptions() : List.of(new MorningRoutePolicy.DepartureOption(c.plannedDepartureSeconds(),c.candidates()));
        appendChoiceRows(s,choices,c);
        // Saved/hand-written contexts may have a distinct original-plan reference estimate.
        // Omit it only when every original reference fact is already represented in the grid.
        if (joint && !sameOriginalEstimates(c)) {
            s.append("ORIGINAL PLAN REFERENCE ESTIMATES (not additional actions)\n");
            appendChoiceRows(s,List.of(new MorningRoutePolicy.DepartureOption(c.plannedDepartureSeconds(),c.candidates())),c);
        }
    }

    private static boolean sameOriginalEstimates(MorningRoutePolicy.Context c) {
        var original = c.departureOptions().stream().filter(d -> d.departureSeconds()==c.plannedDepartureSeconds()).findFirst().orElseThrow().routes();
        for (int i=0;i<original.size();i++) {
            var a=original.get(i); var b=c.candidates().get(i);
            if (a.expectedCarSeconds()!=b.expectedCarSeconds() || a.expectedArrivalSeconds()!=b.expectedArrivalSeconds()
                    || a.distanceMeters()!=b.distanceMeters() || !Objects.equals(a.estimatedMonetaryCost(),b.estimatedMonetaryCost())
                    || !Objects.equals(a.estimatedToll(),b.estimatedToll()) || !Objects.equals(a.tollPolicyId(),b.tollPolicyId())
                    || a.tollPolicyVersion()!=b.tollPolicyVersion() || !Objects.equals(a.forecastObservedDay(),b.forecastObservedDay())
                    || !Objects.equals(a.pricingKnowledge(),b.pricingKnowledge())) return false;
        }
        return true;
    }

    private static void appendChoiceRows(StringBuilder s, List<MorningRoutePolicy.DepartureOption> choices, MorningRoutePolicy.Context c) {
        var options = choices.stream().flatMap(d -> d.routes().stream()).toList();
        var sources = options.stream().map(o -> o.pricingKnowledge()==null ? "traffic_service" : o.pricingKnowledge().source()).distinct().toList();
        boolean sourceColumn = sources.size()>1;
        var policies = options.stream().map(o -> Arrays.asList(o.tollPolicyId(),o.tollPolicyVersion())).distinct().toList();
        boolean policyPresent = options.stream().anyMatch(o -> o.tollPolicyId()!=null), policyColumn = policyPresent && policies.size()>1;
        boolean forecastColumn = c.information()==null;
        if (!sourceColumn) s.append("Cost source: ").append(sources.getFirst()).append('\n');
        if (policyPresent && !policyColumn) s.append("Public policy (id,version) for this estimate table: ").append(Json.write(policies.getFirst())).append('\n');
        if (sources.contains("own_historical_payments")) s.append("Current toll: unknown. Toll/total estimates use your latest own paid entry on each road in the same ")
                .append(c.information().tollHistoryBinSeconds()).append("-second time-of-day bin; they may be outdated. Unknown does not mean free.\n");
        var columns = new ArrayList<>(List.of("departure_s","routeId","drive_s","arrival_s","estimatedToll","estimatedTotalCost"));
        if (sourceColumn) columns.add("costSource");
        if (forecastColumn) columns.add("forecastDay");
        if (policyColumn) columns.addAll(List.of("publicPolicyId","publicPolicyVersion"));
        s.append("columns=").append(Json.write(columns)).append('\n');
        for (var d : choices) for (var o : d.routes()) {
            var row = new ArrayList<>(Arrays.asList(d.departureSeconds(),o.routeId(),o.expectedCarSeconds(),o.expectedArrivalSeconds(),o.estimatedToll(),o.estimatedMonetaryCost()));
            if (sourceColumn) row.add(o.pricingKnowledge()==null ? "traffic_service" : o.pricingKnowledge().source());
            if (forecastColumn) row.add(o.forecastObservedDay());
            if (policyColumn) row.addAll(Arrays.asList(o.tollPolicyId(),o.tollPolicyVersion()));
            s.append(Json.write(row)).append('\n');
        }
        appendPricingDetails(s,choices,c);
    }

    private record PricingDetail(Double historicalToll, Double historicalCostSubtotal, List<String> unknownLinks,
                                 List<MorningRoutePolicy.TollObservation> observations, List<MorningRoutePolicy.AnnouncedToll> announcement,
                                 Double differentBaseCost, Double differentDistance) {}

    private static void appendPricingDetails(StringBuilder s, List<MorningRoutePolicy.DepartureOption> choices, MorningRoutePolicy.Context c) {
        boolean header = false;
        for (var original : c.candidates()) {
            var groups = new LinkedHashMap<PricingDetail,List<Double>>();
            for (var d : choices) {
                var o = d.routes().stream().filter(r -> r.routeId().equals(original.routeId())).findFirst().orElseThrow();
                var k = o.pricingKnowledge(); boolean history = k!=null && k.source().equals("own_historical_payments");
                var detail = new PricingDetail(history?k.knownTollSubtotal():null, history?k.baseMonetaryCost()+k.knownTollSubtotal():null,
                        history?k.unknownLinks():List.of(),history?k.observations():List.of(),k==null?List.of():k.announcement(),
                        k!=null && (original.pricingKnowledge()==null || k.baseMonetaryCost()!=original.pricingKnowledge().baseMonetaryCost())?k.baseMonetaryCost():null,
                        o.distanceMeters()!=original.distanceMeters()?o.distanceMeters():null);
                if (history || !detail.announcement().isEmpty() || detail.differentBaseCost()!=null || detail.differentDistance()!=null)
                    groups.computeIfAbsent(detail, ignored -> new ArrayList<>()).add(d.departureSeconds());
            }
            for (var entry : groups.entrySet()) {
                if (!header) {
                    s.append("VISIBLE ROUTE COST DETAILS\ncolumns=[routeId,departures_s,historicalToll,historicalCostSubtotal,unknownRoads,ownPaymentRows,announcedRuleRows,overrideBaseCost,overrideDistance_m]\n")
                            .append("departures_s=\"all_listed\" applies to every departure in the preceding estimate table. Announced rule rows=[linkId,start_s,end_s,amount]. Empty unknownRoads does not certify a current price; read costSource. Null overrides use ROUTE ATTRIBUTES.\n");
                    header = true;
                }
                var detail = entry.getKey();
                s.append(Json.write(Arrays.asList(original.routeId(),entry.getValue().size()==choices.size()?"all_listed":entry.getValue(),
                        detail.historicalToll(),detail.historicalCostSubtotal(),detail.unknownLinks(),detail.observations().stream().map(MorningPromptRenderer::paymentRow).toList(),
                        detail.announcement().stream().map(r -> List.of(r.linkId(),r.startSeconds(),r.endSeconds(),r.amount())).toList(),
                        detail.differentBaseCost(),detail.differentDistance()))).append('\n');
            }
        }
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
                .append(".\n");
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
        }
    }

    static void appendExperiences(StringBuilder s, List<MorningMemory.Experience> experiences, String title) {
        s.append(title).append('\n');
        if (experiences.isEmpty()) s.append("No previous personal experiences are recorded. No pre-experiment habits are assumed.\n");
        else {
            s.append("columns=[day,routeId,selectedDeparture_s,actualDeparture_s,drive_s,door_s,arrival_s,late_s,unfinished,stuck,tollPaid,paymentRows]\n");
            experiences.forEach(e -> appendExperience(s,e));
        }
    }

    static List<MorningMemory.Experience> recentFacts(List<MorningMemory.Experience> facts,int day,boolean includeToday,int days) {
        int end=includeToday?day:day-1, start=Math.max(0,end-days+1);
        return facts.stream().filter(e -> e.day()>=start && e.day()<=end).toList();
    }

    static String decisionReviewFacts(MorningRoutePolicy.Context c) {
        String rendered=new MorningPromptRenderer().render(c);
        var facts=new StringBuilder(rendered.substring(0,rendered.indexOf("SIMULATION DAY ")));
        appendExperiences(facts,recentFacts(c.history(),c.day(),false,RECENT_FACT_DAYS),"RECENT ACTUAL EXECUTION FACTS");
        facts.append("Current manager announcement visible: ").append(c.information()!=null && c.information().currentAnnouncementVisible()).append('\n');
        return facts.toString();
    }

    static void appendExperience(StringBuilder s, MorningMemory.Experience e) {
        s.append(Json.write(Arrays.asList(e.day(),e.routeId(),e.selectedDepartureSeconds(),e.departureSeconds(),e.carSeconds(),e.doorSeconds(),
                e.arrivalSeconds(),e.lateSeconds(),e.arrivalSeconds()==null,e.stuck(),e.tollPaid(),e.tollObservations().stream().map(MorningPromptRenderer::paymentRow).toList()))).append('\n');
    }

    static void appendPayment(StringBuilder s, MorningRoutePolicy.TollObservation e) {
        s.append(Json.write(paymentRow(e))).append('\n');
    }

    private static List<Object> paymentRow(MorningRoutePolicy.TollObservation e) {
        return List.of(e.day(),e.linkId(),e.time(),e.amount());
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
    private static Double scheduleTime(Map<?, ?> a, String key) {
        if (a.get(key) instanceof Number n) { requireTime(n.doubleValue()); return n.doubleValue(); }
        return null;
    }
    private static String purpose(String p) {
        return switch (p) { case "home" -> "home"; case "work" -> "work"; case "secondary" -> "other activity"; default -> p; };
    }
    private static String clock(double seconds) {
        long t = Math.round(seconds);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", t / 3600, t / 60 % 60, t % 60);
    }
}
