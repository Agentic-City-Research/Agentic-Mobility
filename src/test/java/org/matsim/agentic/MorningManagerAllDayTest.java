package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MorningManagerAllDayTest {
    @TempDir Path directory;

    @Test void alignedPeakOnlyOutputIsRepairedAndSchemaFixesTheWholeSimulationHorizon() throws Exception {
        for (String profile : List.of("basic", "controlled")) {
            var calls = new AtomicInteger();
            Path out = directory.resolve(profile);
            try (var output = new OutputWriter(out)) {
                var manager = manager(profile, 108000, output, (role, system, user) -> {
                    assertEquals("manager", role);
                    assertTrue(system.contains("start=0"));
                    assertTrue(system.contains("end=simulationEndSeconds"));
                    if (calls.incrementAndGet() == 1) {
                        var input = Json.readTree(user);
                        var controls = input.get("pricingControls");
                        assertEquals("full_day", controls.get("chargingMode").asText());
                        assertEquals(108000, controls.get("chargingEndSeconds").asDouble());
                        assertTrue(controls.get("oneRulePerLink").asBoolean());
                        assertEquals(profile.equals("basic") ? 900 : 3600, controls.get("observationBinSeconds").asInt());
                        assertFalse(controls.has("chargingWindowResolutionSeconds"));
                        return response(policy(25200, 32400, "test"));
                    }
                    assertTrue(user.contains("must cover the full simulated day"));
                    return response(policy(0, 108000, "test"));
                }, 2);
                var result = manager.update(20, 0, initial());
                assertTrue(result.accepted()); assertEquals(2, calls.get());
                assertEquals(.1, result.policy().amountAt(Id.createLinkId("road"), 25 * 3600));
            }
            var attempts = JsonlTestSupport.readLines(out.resolve("manager-attempts.jsonl")).stream().map(Json::readTree).toList();
            assertFalse(attempts.getFirst().get("valid").asBoolean());
            assertTrue(attempts.getLast().get("valid").asBoolean());
            var properties = attempts.getFirst().get("outputSchema").get("properties").get("rules").get("items").get("properties");
            assertEquals(0, properties.get("start").get("enum").get(0).asDouble());
            assertEquals(108000, properties.get("end").get("enum").get(0).asDouble());
        }
    }

    @Test void aTwentyFourHourWindowCannotReplaceAnExistingThirtyHourPolicy() throws Exception {
        for (String profile : List.of("basic", "controlled")) {
            var previous = new TollPolicy(List.of(new TollPolicy.TollRule(Id.createLinkId("road"), 0, 108000, .05)), 20);
            var state = new MorningTollSystem.State(previous, "existing", 1);
            try (var output = new OutputWriter(directory.resolve(profile))) {
                var manager = manager(profile, 108000, output, (r,s,u) -> response(policy(0, 86400, "adjust")), 1);
                var rejected = manager.update(50, 0, state);
                assertFalse(rejected.accepted()); assertSame(previous, rejected.policy());
                assertEquals(.05, rejected.policy().amountAt(Id.createLinkId("road"), 29 * 3600));
            }
        }
    }

    @Test void allDayPricesReachTheExactEndEvenWhenItIsNotAnObservationBinBoundary() throws Exception {
        double horizon = 108123.5;
        for (String profile : List.of("basic", "controlled")) {
            try (var output = new OutputWriter(directory.resolve(profile))) {
                var manager = manager(profile, horizon, output, (r,s,u) -> response(policy(0, horizon, "test")), 1);
                var result = manager.update(20, 0, initial()); assertTrue(result.accepted());
                var events = EventsUtils.createEventsManager();
                var payments = new ArrayList<PersonMoneyEvent>();
                events.addHandler((BasicEventHandler) event -> { if (event instanceof PersonMoneyEvent payment) payments.add(payment); });
                var tolls = new MorningTollSystem(MorningPricingTest.network(), events, null);
                tolls.setPolicy(result.policy(), 1); tolls.reset(20);
                var person = Id.createPersonId("driver"); var vehicle = Id.createVehicleId("car"); var road = Id.createLinkId("road");
                tolls.handleEvent(new VehicleEntersTrafficEvent(0, person, road, vehicle, "car", .5));
                tolls.handleEvent(new LinkLeaveEvent(.5, vehicle, road));
                for (double time : new double[]{7 * 3600, 18 * 3600, 25 * 3600, horizon - 1, horizon}) {
                    tolls.handleEvent(new LinkEnterEvent(time, vehicle, road));
                    tolls.handleEvent(new LinkLeaveEvent(time + .5, vehicle, road));
                }
                assertEquals(5, payments.size());
                assertTrue(payments.stream().allMatch(payment -> payment.getAmount() == -.1));
                assertEquals(0, result.policy().amountAt(road, horizon));
                int bin = profile.equals("basic") ? 900 : 3600;
                double binStart = Math.floor(horizon / bin) * bin;
                manager.observe(20, List.of(Map.of("link_id", "road", "start", binStart, "end", binStart + bin,
                        "entered", 1, "completed", 1, "aborted", 0, "unfinished", 0, "partial", 0,
                        "total_travel_time", 11, "total_delay", 1)), new MorningTollSystem.State(result.policy(), "all-day", 1));
                var daily = Json.readTree(Json.write(manager.context(21, 0, tolls.state()))).get("dailyTraffic").get(0);
                assertEquals(1, daily.get("pricingSpatial").get("pricedChargingWindow").get("enteredFullLinkTraversals").asDouble());
                assertEquals(0, daily.get("pricingSpatial").get("pricedOutsideWindow").get("enteredFullLinkTraversals").asDouble());
            }
        }
    }

    @Test void separatePricesForTheSameLinkCannotBecomeAFullDayManagerPolicy() throws Exception {
        for (String profile : List.of("basic", "controlled")) {
            String split = Json.write(Map.of("rules", List.of(
                    Map.of("link_id", "road", "start", 0, "end", 54000, "amount", .1),
                    Map.of("link_id", "road", "start", 54000, "end", 108000, "amount", .2)),
                    "decision", MorningManagerTestResponses.decision("test"), "memory", "Rejected time-varying trial"));
            try (var output = new OutputWriter(directory.resolve(profile))) {
                var rejected = manager(profile, 108000, output, (r,s,u) -> response(split), 1).update(20, 0, initial());
                assertFalse(rejected.accepted()); assertTrue(rejected.policy().rules().isEmpty());
            }
        }
    }

    @Test void managerPolicyAndReflectionSeeFullDayCompletionAndWaitWithoutIndividualData() throws Exception {
        for (String profile : List.of("basic", "controlled")) {
            var sent = new AtomicReference<String>();
            try (var output = new OutputWriter(directory.resolve("full-day-" + profile))) {
                var manager = manager(profile, 108000, output, (r,s,u) -> { sent.set(u); return response(policy(0,108000,"test")); }, 1);
                var facts = new LinkedHashMap<String,Object>();
                facts.put("expectedCarLegs", 3); facts.put("departedCarLegs", 3); facts.put("completedCarLegs", 2);
                facts.put("abortedCarLegs", 1); facts.put("unfinishedCarLegs", 0); facts.put("notStartedCarLegs", 0);
                facts.put("completedCarLegsWithTrafficEntry", 2); facts.put("allPlannedCarLegsCompleted", false);
                facts.put("carPersonHours", null); facts.put("completedCarPersonHours", 2.0);
                facts.put("completedPreTrafficWaitPersonHours", .5); facts.put("observedCarPersonHoursLowerBound", 2.5);
                facts.put("personId", "private-should-not-leak");
                var trips = Map.<String,Object>of("carTripsStarted",3,"carTripsCompleted",2,"carTripsAborted",1,"carTripsUnfinished",0);
                manager.observe(0, List.of(), initial(), trips, 0.0, facts);
                var reflection = Json.readTree(Json.write(manager.memoryContext(0,0,initial())));
                var travel = reflection.get("feedback").get("today").get("fullDayCarTravel");
                assertTrue(travel.get("available").asBoolean());
                assertEquals(.5, travel.get("totals").get("completedPreTrafficWaitPersonHours").asDouble());
                assertTrue(travel.get("totals").get("carPersonHours").isNull());
                manager.update(20,0,initial());
                assertTrue(sent.get().contains("fullDayCarTravel"));
                assertTrue(sent.get().contains("completedPreTrafficWaitPersonHours"));
                assertFalse(sent.get().contains("private-should-not-leak"));
            }
        }
    }

    private static MorningManager manager(String profile, double horizon, OutputWriter output, LlmClient client, int attempts) {
        var network = MorningPricingTest.network(); var config = ConfigUtils.createConfig(); config.qsim().setEndTime(horizon);
        var environment = new MorningManagerEnvironment(network, config, 1, 1, true, "hybrid", 0, Map.of());
        var controls = new MorningManagerControls(); controls.profile = profile;
        return new MorningManager(network, horizon, environment, client, output, new MorningRequestBudget(attempts), attempts,
                Map.of(), MorningManager.UPDATE_INTERVAL, 100, controls);
    }

    private static String policy(double start, double end, String action) {
        return Json.write(Map.of("rules", List.of(Map.of("link_id", "road", "start", start, "end", end, "amount", .1)),
                "decision", MorningManagerTestResponses.decision(action), "memory", "Review the all-day route redistribution trial"));
    }
    private static LlmResponse response(String text) { return new LlmResponse(MorningManagerTestResponses.withDecision(text), 0, 0, 0, 0); }
    private static MorningTollSystem.State initial() { return new MorningTollSystem.State(TollPolicy.empty(-1), "initial", 0); }
}
