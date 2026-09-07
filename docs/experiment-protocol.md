# Experiment Protocol

For every reported run, archive:

- MATSim config and scenario provenance;
- `run-manifest.json`;
- decision, experience, selection and action-memory tables;
- provider/model identifier and prompt version;
- all random seeds and controlled-agent selection rules;
- a paired conventional MATSim baseline using the same network, demand and QSim settings.

Use the mock provider for regression tests. For remote providers, report retry and fallback rates, token use and whether the provider model may have changed during data collection.

Do not call an iteration a day by default. An iteration is one execution-feedback-replanning cycle over the modeled plan; a calendar-day interpretation requires an explicit behavioral assumption.

Before interpreting social evolution, separate transient adaptation from stable outcomes. Plot population score, travel time, mode shares, switching rates, action diversity, inequality and individual trajectories over iterations. Repeat stochastic conditions with multiple seeds.
