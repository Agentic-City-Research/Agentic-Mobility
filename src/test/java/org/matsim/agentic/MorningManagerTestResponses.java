package org.matsim.agentic;

import java.util.Map;

/** Explicit structured decision fixture for manager responses. */
final class MorningManagerTestResponses {
    static Map<String, String> decision(String action) {
        return Map.of("action", action, "evidenceBasis", "untested", "hypothesis", "Route redistribution may help",
                "expectedNetworkEffect", "Measure network delay", "uncertainty", "No matched counterfactual",
                "evaluationPlan", "Review at next executable update", "revisitCondition", "New public observations");
    }
    static String withDecision(String content) {
        var node = Json.readTree(content);
        if (node.isObject() && node.has("rules") && !node.has("decision")) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            object.set("decision", Json.MAPPER.valueToTree(decision(node.get("rules").isEmpty() ? "hold" : "test")));
        }
        return Json.write(node);
    }
}
