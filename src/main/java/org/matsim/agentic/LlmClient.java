package org.matsim.agentic;

/**
 * A chat-completions client. {@code role} is one of "traveler" or "manager" so
 * the mock implementation can return role-appropriate deterministic outputs.
 */
public interface LlmClient {
    LlmResponse complete(String role, String system, String user) throws LlmException;

    /** Per-request output schema; offline fixtures may ignore it, callers still validate locally. */
    default LlmResponse complete(String role, String system, String user,
                                 java.util.Map<String, Object> outputSchema) throws LlmException {
        return complete(role, system, user);
    }
}
