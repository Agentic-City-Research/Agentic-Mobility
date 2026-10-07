package org.matsim.agentic;

/** Raised for LLM transport/API failures and exhausted repair attempts. */
public class LlmException extends RuntimeException {
    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
