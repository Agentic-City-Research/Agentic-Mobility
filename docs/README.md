# AgenticMobility documentation

[Project overview](../README.md) · [Community & contributing](../README.md#community--contributing)

Start with [Getting started](getting-started.md) to build the project, run a MATSim baseline, and connect a model service. Older documents may use “native” for MATSim agents following MATSim replanning.

## Getting started and operating

| Guide                                                                          | What it covers                                                       |
| :----------------------------------------------------------------------------- | :------------------------------------------------------------------- |
| [Getting started](getting-started.md)                                          | Requirements, build, diagnostic run, model API, and troubleshooting. |
| [Operating guide](usage-guide.md)                                              | Commands, outputs, deployment, and troubleshooting.                  |


## Methods and experiment design

| Guide                                                                                                                                                                                                                    | What it covers                                                               |
| :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :--------------------------------------------------------------------------- |
| [Methods & protocols](methods-and-protocols.md)                                                                                                                                                                          | Agent roles, information, action spaces, feedback, and pricing.              |
| [Experiments & evaluation](experiments-and-evaluation.md)                                                                                                                                                                | Configurations, baselines, request budgets, and run records.                 |
| [Scenarios & data](scenarios-and-data.md)                                                                                                                                                                                | Scenario inputs, sampling, capacity, and provenance.                         |
| [Current MNL setup](mnl-joint.md) · [Historical MNL provenance](joint-mnl-baseline.md)                                                                                                                                   | Current configuration and the historical baseline's distinct implementation. |
| [Agent prompts](agent-prompts.md)· [Traveler protocol](traveler-v9.md)                                                                                          | Prompts, traveler memory, output validation, and reflection schedules.       |

## Results and data

| Record | What it covers |
| :--- | :--- |
| [Seed-268 experiment](assets/data/seed268/seed268-experiments.md) · [Individual cases](assets/data/seed268/seed268-individual-cases.md) | Five-configuration historical experiment, interpretation, and case evidence. |
| [Reported experiments](reported-experiments.md) · [Historical archive](history/2026-10-18/README.md) | Manuscript demonstration and earlier comparisons. |
| [Data inventory](data/README.md) · [Seed-268 exports](data/seed268/README.md) · [Figure inventory](assets/README.md) | Available aggregate data, selected records, and figures. |

The recorded experiments used specific builds and settings; consult their provenance before comparing them with current code. For new runs, preserve the commit, executable hash, effective configuration, and `morning/manifest.json`.
