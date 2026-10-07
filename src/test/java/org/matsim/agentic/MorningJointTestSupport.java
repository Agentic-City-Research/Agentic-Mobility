package org.matsim.agentic;

/** Offline fixtures preserve original departures unless a test explicitly exercises shifts. */
final class MorningJointTestSupport {
    static String withDeparture(String content, String user) {
        var node = Json.readTree(content);
        if (node.isObject() && node.has("routeId") && !node.has("departureSeconds") && user.contains("Allowed departureSeconds:")) {
            var clock = java.util.regex.Pattern.compile("Planned departure: (\\d+):(\\d+):(\\d+)").matcher(user);
            if (!clock.find()) throw new AssertionError("Missing original departure in prompt");
            double time = Integer.parseInt(clock.group(1))*3600 + Integer.parseInt(clock.group(2))*60 + Integer.parseInt(clock.group(3));
            ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("departureSeconds", time);
        }
        return Json.write(node);
    }
}
