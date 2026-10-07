package org.matsim.agentic;

import picocli.CommandLine.Option;
import org.matsim.api.core.v01.Id;
import java.util.*;

/** Explicit experimental limits; time equivalents use the scenario's routing coefficients. */
final class MorningManagerControls {
    @Option(names="--manager-profile", defaultValue="basic", description="basic (environment facts and execution limits) | controlled (legacy guided trials)") String profile = "basic";
    static final int MAX_RULES = 40, BASIC_BIN_SECONDS = 900;
    boolean basic() { return profile.equals("basic"); }
    static MorningManagerControls controlled() { var c = new MorningManagerControls(); c.profile = "controlled"; return c; }
    @Option(names="--manager-max-corridor-minutes", defaultValue="5") double maxCorridorMinutes = 5;
    @Option(names="--manager-max-step-minutes", defaultValue="2") double maxStepMinutes = 2;
    @Option(names="--manager-max-priced-corridors", defaultValue="1") int maxPricedCorridors = 1;
    @Option(names="--manager-max-unavoidable-share", defaultValue="0.5") double maxUnavoidableShare = .5;
    @Option(names="--manager-review-days", defaultValue="7") int reviewDays = 7;
    @Option(names="--manager-adaptation-days", defaultValue="7") int adaptationDays = 7;
    @Option(names="--manager-breach-days", defaultValue="3") int breachDays = 3;
    @Option(names="--manager-cooldown-days", defaultValue="7") int cooldownDays = 7;
    @Option(names="--manager-delay-tolerance", defaultValue="0.05") double delayTolerance = .05;
    @Option(names="--manager-local-tolerance", defaultValue="0.10") double localTolerance = .10;
    @Option(names="--manager-noise-multiplier", defaultValue="3") double noiseMultiplier = 3;
    @Option(names="--manager-completion-drop", defaultValue="0.01") double completionDrop = .01;

    void validate() {
        if (!Set.of("basic", "controlled").contains(profile)) throw new IllegalArgumentException("Invalid --manager-profile");
        if (basic()) return;
        for (double v : new double[]{maxCorridorMinutes,maxStepMinutes,delayTolerance,localTolerance,noiseMultiplier,completionDrop})
            if (!(v > 0 && Double.isFinite(v))) throw new IllegalArgumentException("Manager limits must be finite and positive");
        if (!(maxUnavoidableShare >= 0 && maxUnavoidableShare <= 1) || completionDrop > 1
                || maxPricedCorridors < 1 || reviewDays < 2 || adaptationDays < 0 || breachDays < 1 || cooldownDays < 1)
            throw new IllegalArgumentException("Invalid manager review or pricing limits");
    }

    Map<String,Object> context(MorningManagerEnvironment environment) {
        if (basic()) return Map.of("profile", "basic", "maximumRules", MAX_RULES,
                "chargingWindowResolutionSeconds", BASIC_BIN_SECONDS, "managerMemoryMaximumWords", MorningManager.MEMORY_MAX_WORDS,
                "policyPersistence", "Until the next accepted manager update; no automatic strategy intervention");
        var r = new LinkedHashMap<String,Object>();
        r.put("maxCorridorRoutingMinutes",maxCorridorMinutes); r.put("maxPriceIncreaseRoutingMinutes",maxStepMinutes);
        r.put("routingMinutesPerMonetaryUnit",environment.routingMinutesPerMoney());
        r.put("maxPricedCorridors",maxPricedCorridors); r.put("maxPricedLinksPerCorridor",1);
        r.put("maxUnavoidableShareAmongReferenceRouteUsers",maxUnavoidableShare);
        r.put("chargingWindowResolutionSeconds",3600); r.put("rollingReviewDays",reviewDays);
        r.put("adaptationDays",adaptationDays); r.put("consecutiveBreachChecks",breachDays); r.put("cooldownDays",cooldownDays);
        r.put("networkDelayRelativeToleranceFloor",delayTolerance); r.put("localRelativeToleranceFloor",localTolerance);
        r.put("referenceDailyNoiseMultiplier",noiseMultiplier); r.put("completionRateDropFloor",completionDrop);
        r.put("interpretation","Configurable experimental guardrails, not calibrated optimal tolls. Reference-route OD diagnostics are structural full-day counts, not actual peak payers or spare capacity. Price increases are capped; reductions and full withdrawal are always permitted.");
        r.put("profile", "controlled"); r.put("maximumRules", MAX_RULES);
        return r;
    }

    Map<String,Object> check(TollPolicy previous, TollPolicy next, Map<String,String> decision,
                             MorningManagerEnvironment environment) {
        var changed = !previous.fingerprint().equals(next.fingerprint());
        String action = decision.get("action");
        if (action.equals("hold") && changed) throw new IllegalArgumentException("hold must preserve the actual price function");
        if (!action.equals("hold") && !changed) throw new IllegalArgumentException(action+" must change the actual price function; use hold for unchanged rules");
        var ids = new TreeSet<String>();
        previous.rules().forEach(r -> ids.add(r.linkId().toString())); next.rules().forEach(r -> ids.add(r.linkId().toString()));
        var diff = new ArrayList<Map<String,Object>>();
        Set<String> corridors = new HashSet<>(); Map<String,Set<String>> links = new HashMap<>();
        Double scale = environment.routingMinutesPerMoney();
        if (next.rules().size() > MAX_RULES) throw new IllegalArgumentException("At most 40 rules are allowed");
        if (basic()) for (var rule : next.rules()) {
            if (rule.startSec() % BASIC_BIN_SECONDS != 0 || rule.endSec() % BASIC_BIN_SECONDS != 0)
                throw new IllegalArgumentException("Charging windows must align with the 900-second observation bins");
        }
        for (var rule : next.rules()) if (!basic() && rule.amount()>0) {
            if (rule.startSec()%3600!=0 || rule.endSec()%3600!=0)
                throw new IllegalArgumentException("Manager charging windows must align to hourly observation bins");
            if (scale == null) throw new IllegalArgumentException("Positive manager tolls need a positive routing money/time coefficient");
            String id = rule.linkId().toString(), corridor = environment.corridorId(id);
            corridors.add(corridor); links.computeIfAbsent(corridor,k -> new HashSet<>()).add(id);
            var compulsory = environment.referenceUnavoidableShare(id);
            if (compulsory != null && compulsory > maxUnavoidableShare)
                throw new IllegalArgumentException("Link "+id+" has unavoidable reference-OD share "+compulsory+" above "+maxUnavoidableShare);
            if (rule.amount()*scale > maxCorridorMinutes+1e-9)
                throw new IllegalArgumentException("Corridor "+corridor+" exceeds cumulative routing-minute cap "+maxCorridorMinutes);
        }
        if (!basic() && corridors.size()>maxPricedCorridors) throw new IllegalArgumentException("At most "+maxPricedCorridors+" corridors may be priced in one trial");
        if (!basic() && links.values().stream().anyMatch(v -> v.size()>1))
            throw new IllegalArgumentException("Use one entry link per corridor; subdivision charges must not accumulate");
        for (String id : ids) {
            var bounds = new TreeSet<Double>();
            for (var p : List.of(previous,next)) for (var r : p.rules()) if (r.linkId().toString().equals(id)) {
                bounds.add(r.startSec()); bounds.add(r.endSec());
            }
            var b = new ArrayList<>(bounds);
            for (int i=1;i<b.size();i++) {
                double start=b.get(i-1),end=b.get(i),time=(start+end)/2;
                double before=previous.amountAt(Id.createLinkId(id),time), after=next.amountAt(Id.createLinkId(id),time);
                if (before==after) continue;
                if (action.equals("remove") && after>before) throw new IllegalArgumentException("remove may only reduce or withdraw prices");
                if (!basic() && after>before && (scale == null || (after-before)*scale > maxStepMinutes+1e-9))
                    throw new IllegalArgumentException("Price increase on "+id+" exceeds routing-minute step cap "+maxStepMinutes);
                diff.add(Map.of("linkId",id,"start",start,"end",end,"previousAmount",before,"newAmount",after));
            }
        }
        return Map.of("action",action,"pricingChanged",changed,"changes",diff,
                "previousFingerprint",previous.fingerprint(),"newFingerprint",next.fingerprint());
    }
}
