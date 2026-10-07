package org.matsim.agentic;

import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

/** Read-only diagnostic: replay saved events without running the simulation or calling an LLM. */
public final class ReplayLinkMetrics {
    public static void main(String[] args) {
        Path dir = Path.of(args[0]);
        var network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(dir.resolve("output_network.xml.zst").toString());
        var metrics = new LinkTrafficMetrics(network);
        var events = EventsUtils.createEventsManager();
        events.addHandler((BasicEventHandler) metrics::handle);
        events.initProcessing();
        new MatsimEventsReader(events).readFile(dir.resolve("output_events.xml.zst").toString());
        events.finishProcessing();
        var rows = metrics.snapshot();
        for (var row : rows) {
            double entered = ((Number) row.get("entered")).doubleValue();
            double accounted = 0;
            for (String key : List.of("completed", "aborted", "unfinished", "partial"))
                accounted += ((Number) row.get(key)).doubleValue();
            if (entered != accounted) throw new AssertionError("Unbalanced counts: " + row);
        }
        var summary = new CongestionSummary(List.of(Map.of("link_metrics_version", 2, "link_hourly", rows)));
        System.out.println("REPLAY hourly_bins=" + rows.size());
        System.out.println("REPLAY daily_ratios=" + new java.util.TreeMap<>(summary.ratios(network)));
        System.out.println(summary.text(network));
    }
}
