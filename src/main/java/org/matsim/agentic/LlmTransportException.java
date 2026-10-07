package org.matsim.agentic;

/** A transient network or service failure that is safe to retry at the batch level. */
public final class LlmTransportException extends LlmException {
    public LlmTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
