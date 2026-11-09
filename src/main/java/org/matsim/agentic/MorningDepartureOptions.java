package org.matsim.agentic;

import java.util.*;

/** Immutable departure choices anchored to the input plan, never yesterday's choice. */
final class MorningDepartureOptions {
    static List<Double> generate(double original, double earliest, double horizon, int before, int after, int step) {
        return generate(original, earliest, horizon, before, after, step, false);
    }

    /** Optionally exclude the upper offset; the original departure remains an option. */
    static List<Double> generate(double original, double earliest, double horizon, int before, int after, int step,
                                 boolean endExclusive) {
        if (!Double.isFinite(original) || !Double.isFinite(earliest) || !Double.isFinite(horizon)
                || earliest < 0 || original < earliest || original >= horizon || before < 0 || after < 0
                || endExclusive && after == 0
                || step < 1 || before % step != 0 || after % step != 0 || (long)before + after > 86400)
            throw new IllegalArgumentException("Invalid departure option protocol or original schedule");
        var options = new ArrayList<Double>();
        for (long offset = -before; endExclusive ? offset < after : offset <= after; offset += step) {
            double time = original + offset;
            if (time >= earliest && time < horizon) options.add(time);
        }
        return List.copyOf(options);
    }
}
