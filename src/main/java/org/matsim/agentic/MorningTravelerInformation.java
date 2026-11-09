package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import java.util.*;

/** Person-private toll observations; never populated from policy tables or traffic logs. */
final class MorningTravelerInformation {
    private record LinkBin(String linkId, long bin) {}
    private final Map<String, Boolean> groups;
    private final Map<String, Map<LinkBin, MorningRoutePolicy.TollObservation>> observations = new HashMap<>();
    private final int binSeconds;
    private int decisionDay = -1;

    MorningTravelerInformation(Collection<String> people, double share, long seed, int binSeconds) {
        if (!Double.isFinite(share) || share < 0 || share > 1 || binSeconds < 1)
            throw new IllegalArgumentException("Invalid traveler information settings");
        this.binSeconds = binSeconds;
        var ordered = new ArrayList<>(new TreeSet<>(people));
        Collections.shuffle(ordered, new Random(seed));
        int informed = (int) Math.round(ordered.size() * share);
        var assigned = new TreeMap<String, Boolean>();
        for (int i = 0; i < ordered.size(); i++) assigned.put(ordered.get(i), i < informed);
        groups = Collections.unmodifiableMap(assigned);
    }

    boolean informed(String person) { return groups.getOrDefault(person, true); }
    String group(String person) { return participates(person) ? informed(person) ? "informed" : "uninformed" : "background"; }
    boolean participates(String person) { return groups.containsKey(person); }
    Map<String, Boolean> assignments() { return groups; }
    int binSeconds() { return binSeconds; }
    void beginDay(int day) { decisionDay = day; }

    synchronized void observe(int day, String person, MorningTollSystem.Receipt receipt) {
        if (!participates(person)) return;
        if (day < 0 || receipt.amount() < 0 || !Double.isFinite(receipt.amount())
                || receipt.time() < 0 || !Double.isFinite(receipt.time()))
            throw new IllegalArgumentException("Only actual nonnegative personal toll observations may enter toll knowledge");
        var event = new MorningRoutePolicy.TollObservation(day, receipt.linkId(), receipt.time(), receipt.amount());
        var privateFacts = observations.computeIfAbsent(person, k -> new HashMap<>());
        var key = new LinkBin(receipt.linkId(), (long) Math.floor(receipt.time() / binSeconds));
        var previous = privateFacts.get(key);
        if (previous != null && (day < previous.day() || day == previous.day() && receipt.time() < previous.time()))
            throw new IllegalArgumentException("Personal toll observations must be chronological");
        privateFacts.put(key, event);
    }

    synchronized MorningRoutePolicy.TollObservation past(String person, String link, double time, int beforeDay) {
        var known = observations.getOrDefault(person, Map.of()).get(new LinkBin(link, (long) Math.floor(time / binSeconds)));
        return known != null && known.day() < beforeDay ? known : null;
    }

    synchronized List<MorningRoutePolicy.TollObservation> personalPayments(String person) {
        return observations.getOrDefault(person, Map.of()).values().stream()
                .sorted(Comparator.comparingInt(MorningRoutePolicy.TollObservation::day)
                        .thenComparingDouble(MorningRoutePolicy.TollObservation::time)
                        .thenComparing(MorningRoutePolicy.TollObservation::linkId)).toList();
    }

    MorningRoutePolicy.Information visible(String person, int day) {
        return new MorningRoutePolicy.Information(group(person), informed(person), false, binSeconds,
                personalPayments(person).stream().filter(e -> e.day() < day).toList());
    }

    MorningRoutePolicy.Information visibleForLlm(String person) {
        return new MorningRoutePolicy.Information(group(person), informed(person), false, binSeconds, List.of());
    }

    MorningRoutePolicy.Information visibleForLlm(String person, int day, MorningTollSystem tolls) {
        var rules = tolls != null && informed(person) ? tolls.state().policy().rules().stream()
                .map(r -> new MorningRoutePolicy.AnnouncedToll(r.linkId().toString(), r.startSec(), r.endSec(), r.amount())).toList()
                : List.<MorningRoutePolicy.AnnouncedToll>of();
        var personal = informed(person) ? List.<MorningRoutePolicy.TollObservation>of()
                : personalPayments(person).stream().filter(e -> e.day() < day).toList();
        return new MorningRoutePolicy.Information(group(person), informed(person), false, binSeconds, personal, rules);
    }

    MorningRoutePolicy.PricingKnowledge pricingForLlm(String person, List<String> links, List<Double> entries,
                                                     double baseCost, MorningTollSystem tolls, int day) {
        if (tolls == null || informed(person)) return pricing(person, links, entries, baseCost, tolls, day);
        var historical = pricing(person, links, entries, baseCost, tolls, day);
        return new MorningRoutePolicy.PricingKnowledge(historical.group(), "personal_history_zero_prior", baseCost,
                historical.knownTollSubtotal(), historical.unknownLinks(), historical.observations(), List.of());
    }

    MorningRoutePolicy.PricingKnowledge pricing(String person, List<String> links, List<Double> entryTimes,
                                               double baseCost, MorningTollSystem tolls, int day) {
        if (links.size() != entryTimes.size()) throw new IllegalArgumentException("Missing route entry times");
        double subtotal = 0;
        var unknown = new ArrayList<String>();
        var used = new ArrayList<MorningRoutePolicy.TollObservation>();
        for (int i = 0; i < links.size(); i++) {
            if (tolls == null) continue;
            if (informed(person)) subtotal += tolls.amountAt(Id.createLinkId(links.get(i)), entryTimes.get(i));
            else {
                var prior = past(person, links.get(i), entryTimes.get(i), day);
                if (prior == null) unknown.add(links.get(i));
                else { subtotal += prior.amount(); used.add(prior); }
            }
        }
        var announcement = tolls != null && informed(person) ? tolls.state().policy().rules().stream()
                .filter(rule -> links.contains(rule.linkId().toString()))
                .map(rule -> new MorningRoutePolicy.AnnouncedToll(rule.linkId().toString(), rule.startSec(), rule.endSec(), rule.amount())).toList()
                : List.<MorningRoutePolicy.AnnouncedToll>of();
        return new MorningRoutePolicy.PricingKnowledge(group(person), tolls == null ? "charging_disabled"
                : informed(person) ? "current_announcement" : "own_historical_payments", baseCost, subtotal,
                List.copyOf(unknown), List.copyOf(used), announcement);
    }

    // Native routing has no representation for unknown monetary costs. Use the
    // observed component only; this numerical omission is never a zero-price fact.
    double routingToll(String person, Id<Link> link, double time, MorningTollSystem tolls) {
        if (informed(person)) return tolls.amountAt(link, time);
        var past = past(person, link.toString(), time, decisionDay);
        return past == null ? 0 : past.amount();
    }
}
