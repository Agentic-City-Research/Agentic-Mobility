package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A congestion-pricing policy: a set of non-overlapping per-link time-window
 * toll rules. Legal rules require a real car-allowed link, 0 &lt;= start &lt; end
 * &lt;= 86400, non-negative finite amounts, and no overlapping windows on the same
 * link. These are execution requirements, not pricing targets.
 */
public record TollPolicy(List<TollRule> rules, int day) {

    public record TollRule(Id<Link> linkId, double startSec, double endSec, double amount) {
    }

    public static TollPolicy empty(int day) {
        return new TollPolicy(List.of(), day);
    }

    /** Left-closed right-open toll lookup: [start, end). */
    public double amountAt(Id<Link> linkId, double time) {
        double amount = 0.0;
        for (TollRule r : rules) {
            if (r.linkId().equals(linkId) && time >= r.startSec() && time < r.endSec()) {
                amount += r.amount();
            }
        }
        return amount;
    }

    public boolean hasRules() {
        return !rules.isEmpty();
    }

    /** Identity of the price function, independent of version, rule order and zero-price entries. */
    public String fingerprint() {
        var ordered = rules.stream().filter(r -> r.amount() > 0).sorted(java.util.Comparator
                .comparing((TollRule r) -> r.linkId().toString()).thenComparingDouble(TollRule::startSec)
                .thenComparingDouble(TollRule::endSec)).toList();
        var merged = new ArrayList<TollRule>();
        for (var rule : ordered) {
            if (!merged.isEmpty()) {
                var previous = merged.getLast();
                if (previous.linkId().equals(rule.linkId()) && previous.endSec() == rule.startSec()
                        && previous.amount() == rule.amount()) {
                    merged.set(merged.size() - 1, new TollRule(previous.linkId(), previous.startSec(), rule.endSec(), rule.amount()));
                    continue;
                }
            }
            merged.add(new TollRule(rule.linkId(), rule.startSec() == 0 ? 0.0 : rule.startSec(), rule.endSec(), rule.amount()));
        }
        return MorningPromptRenderer.hash(Json.write(new TollPolicy(merged, 0).toJson().get("rules")));
    }

    /** @return a list of violation messages; empty means the policy is executable. */
    public List<String> validate(Network network) {
        return validate(network, 86400);
    }

    /** The morning experiment can run past midnight; preserve the legacy 24h default. */
    public List<String> validate(Network network, double simulationEnd) {
        List<String> errors = new ArrayList<>();
        Map<Id<Link>, List<TollRule>> byLink = new LinkedHashMap<>();
        for (TollRule r : rules) {
            Link link = network.getLinks().get(r.linkId());
            if (link == null) {
                errors.add("link " + r.linkId() + " does not exist in the network");
                continue;
            }
            if (!link.getAllowedModes().contains("car")) {
                errors.add("link " + r.linkId() + " does not allow car");
            }
            if (!(Double.isFinite(r.startSec()) && Double.isFinite(r.endSec())
                    && r.startSec() >= 0 && r.startSec() < r.endSec() && r.endSec() <= simulationEnd)) {
                errors.add("link " + r.linkId() + " violates 0<=start<end<=" + simulationEnd);
            }
            if (!Double.isFinite(r.amount()) || r.amount() < 0) {
                errors.add("link " + r.linkId() + " amount must be finite and non-negative");
            }
            byLink.computeIfAbsent(r.linkId(), k -> new ArrayList<>()).add(r);
        }
        for (List<TollRule> perLink : byLink.values()) {
            for (int i = 0; i < perLink.size(); i++) {
                for (int j = i + 1; j < perLink.size(); j++) {
                    TollRule a = perLink.get(i), b = perLink.get(j);
                    if (a.startSec() < b.endSec() && b.startSec() < a.endSec()) {
                        errors.add("overlapping windows on link " + a.linkId());
                    }
                }
            }
        }
        return errors;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day);
        List<Map<String, Object>> rs = new ArrayList<>();
        for (TollRule r : rules) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("link_id", r.linkId().toString());
            rm.put("start", r.startSec());
            rm.put("end", r.endSec());
            rm.put("amount", r.amount());
            rs.add(rm);
        }
        m.put("rules", rs);
        return m;
    }
}
