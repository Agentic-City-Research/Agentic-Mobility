package org.matsim.agentic;

import java.util.*;

/** Daily, deterministic protection. A breach schedules zero prices for the next iteration, never extra API calls. */
final class MorningManagerSafety {
    private record Sample(int day,List<Map<String,Object>> rows,Map<String,Object> carTrips) {}
    private record Reference(Map<String,Double> means,Map<String,Double> limits,int firstDay,int lastDay) {}
    private final MorningManagerControls controls;
    private final OutputWriter output;
    private final Deque<Sample> history=new ArrayDeque<>();
    private TollPolicy policy=TollPolicy.empty(-1);
    private Reference reference;
    private int effectiveDay=-1,breaches,cooldownUntil=-1;
    private Integer rollbackAfterDay;
    private Map<String,Object> lastReview=Map.of();
    private Map<String,Object> lastWithdrawal=Map.of();

    MorningManagerSafety(MorningManagerControls controls,OutputWriter output) {this.controls=controls;this.output=output;}

    void applied(int day,TollPolicy next,boolean automatic) {
        if (policy.fingerprint().equals(next.fingerprint())) return; // Version-only holds preserve adaptation and breach counters.
        policy=next;effectiveDay=day;breaches=0;rollbackAfterDay=null;reference=null;
        if (positive(next)) {
            var before=history.stream().skip(Math.max(0,history.size()-controls.reviewDays)).toList();
            if (before.size()==controls.reviewDays) {
                var metrics=before.stream().map(s -> metrics(s,next)).toList();
                var means=new LinkedHashMap<String,Double>();var limits=new LinkedHashMap<String,Double>();
                for (String key:metrics.getFirst().keySet()) {
                    var values=metrics.stream().map(m -> m.get(key)).filter(Objects::nonNull).toList();
                    if (values.size()!=before.size()) continue;
                    double mean=values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                    double sd=Math.sqrt(values.stream().mapToDouble(v -> (v-mean)*(v-mean)).sum()/(values.size()-1));
                    double floor=key.endsWith("Completion")?controls.completionDrop:
                            key.equals("networkDelay")?controls.delayTolerance:controls.localTolerance;
                    means.put(key,mean);
                    limits.put(key,Math.max(key.endsWith("Completion")?floor:Math.max(1e-9,mean*floor),controls.noiseMultiplier*sd));
                }
                reference=new Reference(means,limits,before.getFirst().day(),before.getLast().day());
            }
        } else if (automatic) cooldownUntil=day+controls.cooldownDays;
    }

    void observe(int day,List<Map<String,Object>> rows,Map<String,Object> trips,MorningTollSystem.State state) {
        if (!policy.fingerprint().equals(state.policy().fingerprint())) applied(day,state.policy(),false);
        var sample=new Sample(day,rows,trips);history.addLast(sample);
        while(history.size()>Math.max(30,controls.reviewDays*2)) history.removeFirst();
        var review=new LinkedHashMap<String,Object>(); review.put("day",day);review.put("policyId",state.policyId());
        review.put("policyVersion",state.version());review.put("policyFingerprint",state.policy().fingerprint());
        review.put("policyEffectiveFromDay",effectiveDay);review.put("matchedCounterfactualAvailable",false);
        review.put("referenceInterpretation","Recent pre-change observed days, selected on the same proposed priced links/windows. Native learning and prior prices remain confounders; thresholds are operational protection, not causal estimates.");
        String status="unpriced";
        var reasons=new ArrayList<String>();
        if (positive(policy) && reference!=null) {
            review.put("referenceFirstDay",reference.firstDay());review.put("referenceLastDay",reference.lastDay());
            review.put("referenceMeans",reference.means());review.put("tolerances",reference.limits());
            var today=metrics(sample,policy);review.put("today",today);
            // Serious loss of completions bypasses the adaptation grace period and rolling-window requirement.
            for(String key:List.of("carCompletion","linkCompletion")) {
                Double now=today.get(key),before=reference.means().get(key),limit=reference.limits().get(key);
                if(now!=null && before!=null && before-now>limit) reasons.add("serious_"+key+"_drop");
            }
            boolean serious=!reasons.isEmpty();
            var after=history.stream().filter(s -> s.day()>=effectiveDay).toList();
            if (!serious && (day-effectiveDay<controls.adaptationDays || after.size()<controls.reviewDays)) status="adaptation";
            else {
                var tail=after.subList(Math.max(0,after.size()-controls.reviewDays),after.size());
                var rolling=metrics(tail,policy);review.put("rolling",rolling);
                if (above(rolling,"networkDelay")) reasons.add("network_delay_increase");
                Double total=rolling.get("networkDelay"),old=reference.means().get("networkDelay");
                boolean noNetworkBenefit=total!=null && old!=null && total>=old*(1-controls.delayTolerance);
                if (noNetworkBenefit && above(rolling,"pricedAverageDelay")) reasons.add("priced_average_delay_increase_without_network_benefit");
                if (noNetworkBenefit && above(rolling,"otherDelay")) reasons.add("other_links_delay_increase_without_network_benefit");
                for(String key:List.of("carCompletion","linkCompletion")) {
                    Double now=rolling.get(key),before=reference.means().get(key),limit=reference.limits().get(key);
                    if(now!=null && before!=null && before-now>limit && !reasons.contains("serious_"+key+"_drop")) reasons.add("rolling_"+key+"_drop");
                }
                breaches=reasons.isEmpty()?0:breaches+1;status=reasons.isEmpty()?"within_tolerance":"breach";
                if (serious || breaches>=controls.breachDays) {rollbackAfterDay=day;status="rollback_pending";}
            }
        } else if (positive(policy)) status="insufficient_reference";
        review.put("status",status);review.put("reasons",reasons);review.put("consecutiveBreaches",breaches);
        review.put("rollbackEffectiveNextIteration",rollbackAfterDay==null?null:rollbackAfterDay+1);
        lastReview=Collections.unmodifiableMap(review);output.appendLine("manager-safety-reviews.jsonl",review);
    }

    private boolean above(Map<String,Double> values,String key) {
        Double now=values.get(key),before=reference.means().get(key),limit=reference.limits().get(key);
        return now!=null && before!=null && limit!=null && now>before+limit;
    }
    TollPolicy rollback(int day,MorningTollSystem.State state) {
        if(rollbackAfterDay==null || day<=rollbackAfterDay || !positive(state.policy())) return null;
        lastWithdrawal=Map.of("day",day,"action","withdraw_all_prices",
                "previousPolicyId",state.policyId(),"previousPolicyVersion",state.version(),"triggerReview",lastReview,
                "cooldownThroughIteration",day+controls.cooldownDays-1);
        output.appendLine("manager-safety-actions.jsonl",lastWithdrawal);
        return TollPolicy.empty(day);
    }
    boolean cooldown(int day) {return day<cooldownUntil;}
    Map<String,Object> context() {
        var r=new LinkedHashMap<String,Object>();r.put("lastDailyReview",lastReview);r.put("cooldownUntilIteration",cooldownUntil<0?null:cooldownUntil);
        r.put("lastAutomaticWithdrawal",lastWithdrawal);
        r.put("rollbackPendingAfterIteration",rollbackAfterDay);r.put("extraApiCalls",false);r.put("fallbackPolicy","zero prices");return r;
    }
    static boolean positive(TollPolicy policy) {return policy.rules().stream().anyMatch(r -> r.amount()>0);}

    private static Map<String,Double> metrics(Sample day,TollPolicy policy) {return metrics(List.of(day),policy);}
    private static Map<String,Double> metrics(List<Sample> days,TollPolicy policy) {
        var groups=days.stream().map(s -> MorningManagerMeasurements.spatial(s.rows(),policy)).toList();
        var all=MorningManagerMeasurements.statistics(groups);
        var result=new LinkedHashMap<String,Double>();
        result.put("networkDelay",mean(all,"network","totalLinkDelaySeconds"));
        result.put("otherDelay",mean(all,"otherLinksFullDay","totalLinkDelaySeconds"));
        result.put("pricedAverageDelay",(Double)((Map<?,?>)all.get("pricedChargingWindow")).get("delaySecondsPerCompletedTraversal"));
        double completed=sum(groups,"completedFullLinkTraversals"),failed=sum(groups,"abortedFullLinkTraversals")+sum(groups,"unfinishedFullLinkTraversals");
        result.put("linkCompletion",completed+failed==0?null:completed/(completed+failed));
        double started=0,finished=0;boolean known=true;
        for(var s:days) {
            if(!(s.carTrips().get("carTripsStarted") instanceof Number n) || !(s.carTrips().get("carTripsCompleted") instanceof Number c)) {known=false;break;}
            started+=n.doubleValue();finished+=c.doubleValue();
        }
        result.put("carCompletion",!known || started==0?null:finished/started);return result;
    }
    private static double sum(List<Map<String,Map<String,Double>>> days,String metric) {return days.stream().mapToDouble(g -> g.get("network").get(metric)).sum();}
    private static double mean(Map<String,Object> groups,String group,String metric) {return ((Number)((Map<?,?>)((Map<?,?>)groups.get(group)).get("meanDaily")).get(metric)).doubleValue();}
}
