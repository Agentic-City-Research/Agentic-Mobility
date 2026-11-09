package org.matsim.agentic;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MorningDepartureOptionsV2Test {
    @Test void v2HasInclusiveMinus60AndExclusivePlus30() {
        assertEquals(List.of(21600d, 22500d, 23400d, 24300d, 25200d, 26100d),
                MorningDepartureOptions.generate(25200, 0, 108000, 3600, 1800, 900, true));
    }

    @Test void v2RespectsActivityAndSimulationBoundaries() {
        assertEquals(List.of(0d, 900d, 1800d),
                MorningDepartureOptions.generate(900, 0, 108000, 3600, 1800, 900, true));
        assertEquals(List.of(24300d, 25200d, 26100d),
                MorningDepartureOptions.generate(25200, 24300, 27000, 3600, 1800, 900, true));
        assertEquals(List.of(23400d, 24300d, 25200d),
                MorningDepartureOptions.generate(25200, 23400, 26100, 3600, 1800, 900, true));
    }

    @Test void defaultStillIncludesUpperBound() {
        assertEquals(List.of(21600d, 22500d, 23400d, 24300d, 25200d, 26100d, 27000d),
                MorningDepartureOptions.generate(25200, 0, 108000, 3600, 1800, 900));
        assertThrows(IllegalArgumentException.class,
                () -> MorningDepartureOptions.generate(25200, 0, 108000, 3600, 0, 900, true));
    }
}
