package org.matsim.agentic.api;

/** Produces a bounded travel action from a MATSim observation. */
public interface DecisionProvider extends AutoCloseable {

    AgentAction decide(DecisionContext context) throws Exception;

    String name();

    @Override
    default void close() {
        // Most providers do not own closeable resources.
    }
}
