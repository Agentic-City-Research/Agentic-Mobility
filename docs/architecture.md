# Architecture

Agentic MATSim separates transport simulation from agent reasoning.

1. A `CityScenarioAdapter` validates the scenario and exposes feasible modes.
2. `AgentSelector` behavior in the application selects a reproducible population subset.
3. The MATSim controller converts the latest experienced plan into an `AgentExperience`.
4. Per-agent memory summarizes prior mode/departure actions and scores.
5. A `DecisionProvider` proposes a bounded `AgentAction`.
6. Policy code applies feasibility guards, exploration or rollback.
7. MATSim's `TripRouter` materializes the action and MATSim simulates it.
8. CSV audit tables and a JSON run manifest capture the loop.

The provider cannot modify the network, activities, destinations or scoring parameters. This boundary makes provider comparisons and deterministic ablations possible.

## Extension points

- Implement `DecisionProvider` for a new model or rule system.
- Add bounded fields to `AgentAction` only together with validation and an action applier.
- Keep memory and exploration outside remote providers so that experiments can isolate their effects.

The traveler controller remains in the legacy `org.matsim.llmprocess` package. In v0.2 an optional `org.matsim.agentic.manager` module observes completed iterations, validates a bounded congestion-price table and publishes it before the next traveler decision. Actual car payments enter MATSim scoring as money events; car routing reads the same immutable table. Disabled mode installs no handlers or routing overrides. See [Manager](manager.md) for configuration, experiment boundaries and audit semantics.
