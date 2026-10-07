package org.matsim.agentic;

import java.time.*;

public final class ExperimentRunName {
    private ExperimentRunName() {}
    public static LocalDate date(String override) {
        return override == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : LocalDate.parse(override);
    }
    public static String format(LocalDate date, int days, int llmAgents, boolean noManager, long seed) {
        if (days < 1 || llmAgents < 0) throw new IllegalArgumentException("Invalid run name parameters");
        return date + "_day" + days + "_llm" + llmAgents + "_" + (noManager ? "noMgr" : "mgr") + "_seed" + seed;
    }
}
