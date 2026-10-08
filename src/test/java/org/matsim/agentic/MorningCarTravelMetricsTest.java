package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;

import static org.junit.jupiter.api.Assertions.*;

class MorningCarTravelMetricsTest {
    @Test void totalIncludesPreTrafficQueueAndMeasuresAllTripsOfTheDay() {
        var metrics = new MorningCarTravelMetrics(); metrics.reset(); metrics.expectCarLegs(2);
        var person = Id.createPersonId("p"); var link = Id.createLinkId("l"); var vehicle = Id.createVehicleId("v");
        metrics.handle(new PersonDepartureEvent(100, person, link, "car", "car"));
        metrics.handle(new VehicleEntersTrafficEvent(400, person, link, vehicle, "car", 1));
        metrics.handle(new VehicleLeavesTrafficEvent(700, person, link, vehicle, "car", 1));
        metrics.handle(new PersonArrivalEvent(700, person, link, "car"));
        metrics.handle(new PersonDepartureEvent(2000, person, link, "car", "car"));
        metrics.handle(new VehicleEntersTrafficEvent(2600, person, link, vehicle, "car", 1));
        metrics.handle(new VehicleLeavesTrafficEvent(2900, person, link, vehicle, "car", 1));
        metrics.handle(new PersonArrivalEvent(2900, person, link, "car"));
        var result = metrics.summary(3000);
        assertEquals(true, result.get("allPlannedCarLegsCompleted"));
        assertEquals(1500. / 3600, (Double) result.get("carPersonHours"), 1e-12);
        assertEquals(900. / 3600, (Double) result.get("completedPreTrafficWaitPersonHours"), 1e-12);
        assertEquals(450., result.get("meanCompletedPreTrafficWaitSeconds"));
        assertEquals(750., result.get("meanCompletedCarSeconds"));
    }

    @Test void abortUnfinishedAndNotStartedRemainFailuresInsteadOfZeroCost() {
        var metrics = new MorningCarTravelMetrics(); metrics.reset(); metrics.expectCarLegs(4);
        var p = Id.createPersonId("p"); var q = Id.createPersonId("q"); var link = Id.createLinkId("l"); var v = Id.createVehicleId("v");
        metrics.handle(new PersonDepartureEvent(10, p, link, "car", "car"));
        metrics.handle(new VehicleEntersTrafficEvent(20, p, link, v, "car", 1));
        metrics.handle(new PersonArrivalEvent(30, p, link, "car"));
        metrics.handle(new PersonDepartureEvent(100, p, link, "car", "car"));
        metrics.handle(new VehicleEntersTrafficEvent(200, p, link, v, "car", 1));
        metrics.handle(new PersonDepartureEvent(300, q, link, "car", "car")); // Still waiting to enter traffic.
        metrics.handle(new PersonStuckEvent(400, p, link, "car"));
        metrics.handle(new VehicleAbortsEvent(400, v, link)); // Must not count the same failure twice.
        var result = metrics.summary(1000);
        assertEquals(false, result.get("allPlannedCarLegsCompleted")); assertNull(result.get("carPersonHours"));
        assertEquals(1L, result.get("completedCarLegs")); assertEquals(1L, result.get("abortedCarLegs"));
        assertEquals(1, result.get("unfinishedCarLegs")); assertEquals(1L, result.get("notStartedCarLegs"));
        assertEquals(.25, result.get("completionFractionAll"));
        assertEquals(1020. / 3600, (Double) result.get("observedCarPersonHoursLowerBound"), 1e-12);
        assertEquals(20., result.get("meanCompletedCarSeconds"));
        metrics.reset(); metrics.expectCarLegs(1);
        assertEquals(0., metrics.summary(1000).get("completedCarPersonHours"));
        assertNull(metrics.summary(1000).get("carPersonHours"));
    }
}
