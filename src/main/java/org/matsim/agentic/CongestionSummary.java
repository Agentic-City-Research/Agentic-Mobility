package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import java.util.*;

/** Combines sufficient statistics, not unweighted daily ratios. */
final class CongestionSummary {
    private record Key(String link, int start, int end) {}
    private final Map<Key, double[]> bins = new HashMap<>();
    private final int days;

    CongestionSummary(List<Map<String, Object>> metrics) {
        int observedDays = 0;
        if (metrics != null) for (Map<String, Object> day : metrics) {
            if (!(day.get("link_metrics_version") instanceof Number version) || version.intValue() < 2) continue;
            if (!(day.get("link_hourly") instanceof List<?> rows)) continue;
            observedDays++;
            for (Object value : rows) {
                if (!(value instanceof Map<?, ?> r)) continue;
                Key key = new Key((String) r.get("link_id"), ((Number) r.get("start")).intValue(), ((Number) r.get("end")).intValue());
                double[] t = bins.computeIfAbsent(key, k -> new double[7]);
                String[] fields = {"entered", "completed", "total_travel_time", "total_delay", "aborted", "unfinished", "partial"};
                for (int i = 0; i < fields.length; i++) t[i] += ((Number) r.get(fields[i])).doubleValue();
            }
        }
        days = observedDays;
    }

    Map<String, Double> ratios(Network network) {
        Map<String, double[]> sums = new HashMap<>();
        bins.forEach((k, t) -> {
            double[] s = sums.computeIfAbsent(k.link(), id -> new double[2]);
            s[0] += t[2]; s[1] += t[1];
        });
        Map<String, Double> ratios = new HashMap<>();
        sums.forEach((id, s) -> {
            var link = network.getLinks().get(Id.createLinkId(id));
            if (link != null && s[1] > 0) ratios.put(id, s[0] / s[1] / (link.getLength() / link.getFreespeed()));
        });
        return ratios;
    }

    String text(Network network) {
        return text(network, null);
    }

    String text(Network network, TollPolicy policy) {
        if (days == 0) return "Hourly congestion observations unavailable (legacy ratios are not used).\n";
        Comparator<Map.Entry<Key, double[]>> byDelay = Comparator
                .<Map.Entry<Key, double[]>>comparingDouble(e -> e.getValue()[3]).reversed()
                .thenComparing(e -> e.getKey().link()).thenComparingInt(e -> e.getKey().start());
        Set<Key> selected = new LinkedHashSet<>();
        bins.entrySet().stream().filter(e -> e.getValue()[3] > 0).sorted(byDelay).limit(40)
                .forEach(e -> selected.add(e.getKey()));
        // Incomplete traversals must remain visible even when completed-only delay is low.
        bins.entrySet().stream().filter(e -> e.getValue()[4] + e.getValue()[5] > 0)
                .sorted(Comparator.<Map.Entry<Key, double[]>>comparingDouble(e -> e.getValue()[4] + e.getValue()[5]).reversed()
                        .thenComparing(e -> e.getKey().link()).thenComparingInt(e -> e.getKey().start()))
                .limit(10).forEach(e -> selected.add(e.getKey()));
        // Retain observations of the current intervention even when it leaves the top-delay list.
        if (policy != null) for (var rule : policy.rules()) {
            bins.keySet().stream().filter(k -> k.link().equals(rule.linkId().toString())
                    && k.start() < rule.endSec() && rule.startSec() < k.end())
                    .sorted(Comparator.comparingInt(Key::start)).forEach(selected::add);
        }
        StringBuilder out = new StringBuilder("Car observations in entry-time bins across ").append(days)
                .append(" days: top 40 link bins by total delay, plus top 10 by aborted/unfinished count.\n")
                .append("Observed link bins overlapping the current toll policy are also included; absent bins have no recorded entries.\n")
                .append("Bins use entry time; times are seconds since simulation-day start, not modulo 24h. ")
                .append("Entry rate is entries per hour averaged over these days, not capacity utilization. ")
                .append("Counts are period totals; travel/delay averages use completed full-link traversals only.\n")
                .append("Unlisted link-hours are not necessarily free-flow; full observations are saved in metrics.\n");
        if (selected.isEmpty()) out.append("  No positive measured link delay or aborted/unfinished traversals.\n");
        for (Key k : selected) {
            double[] t = bins.get(k);
            var link = network.getLinks().get(Id.createLinkId(k.link()));
            if (link == null) continue;
            out.append(String.format(Locale.ROOT,
                    "  %s [%d,%d): entries/h=%.1f completed=%.0f travel_s=%s delay_s=%s ratio=%s total_delay_s/day=%.1f aborted=%.0f unfinished=%.0f partial_excluded=%.0f%n",
                    k.link(), k.start(), k.end(), t[0] / days / ((k.end() - k.start()) / 3600.0), t[1],
                    t[1] == 0 ? "NA" : String.format(Locale.ROOT, "%.1f", t[2] / t[1]),
                    t[1] == 0 ? "NA" : String.format(Locale.ROOT, "%.1f", t[3] / t[1]),
                    t[1] == 0 ? "NA" : String.format(Locale.ROOT, "%.2f", t[2] / t[1] / (link.getLength() / link.getFreespeed())),
                    t[3] / days, t[4], t[5], t[6]));
        }
        return out.toString();
    }
}
