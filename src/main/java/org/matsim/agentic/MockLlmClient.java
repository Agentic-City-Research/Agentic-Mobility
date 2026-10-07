package org.matsim.agentic;

/**
 * Deterministic test client used with {@code --mock}. Returns role-appropriate,
 * always-valid outputs so the wiring (validation, plan writing, billing,
 * reporting) can be exercised end-to-end without a real API or behavioral claim.
 */
public final class MockLlmClient implements LlmClient {

    @Override
    public LlmResponse complete(String role, String system, String user) {
        String content = switch (role) {
            case "manager" -> "{\"rules\":[],\"memory\":\"mock\",\"reason\":\"no toll in mock mode\"}";
            default -> "{\"outbound\":{\"route_id\":\"c0\",\"offset\":0},"
                    + "\"return\":{\"route_id\":\"c0\",\"offset\":0},"
                    + "\"memory\":\"mock\",\"reason\":\"deterministic mock decision\"}";
        };
        return new LlmResponse(content, 1L, 1L, 0L, 1L);
    }
}
