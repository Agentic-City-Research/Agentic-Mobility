package org.matsim.agentic;

import java.util.concurrent.atomic.AtomicLong;

/** Atomic dispatch budget shared by manager updates, route selection and post-QSim reflection. */
final class MorningRequestBudget {
    private final int maximum;
    private final AtomicLong calls = new AtomicLong();
    MorningRequestBudget(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("Invalid request budget");
        this.maximum = maximum;
    }
    void reserve() {
        while (true) {
            long used = calls.get();
            if (maximum > 0 && used >= maximum) throw new LlmException("Morning LLM request budget exhausted (max " + maximum + ")");
            if (calls.compareAndSet(used, used + 1)) return;
        }
    }
    long attemptedCalls() { return calls.get(); }
}
