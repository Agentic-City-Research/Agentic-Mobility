# AgenticMobility documentation

[Project overview](../README.md) · [Chinese operating guide](usage-guide.zh.md)

AgenticMobility connects LLM travelers, an LLM congestion-pricing manager, and full-day MATSim traffic. Start with a native run, read the interaction protocol, and then configure the roles and scenario needed for your study.

当前代码的功能、默认设置、原生/MNL/LLM 旅客与收费条件分类及中文运行示例见 [功能与设置说明](功能与设置说明.md)。

旅客、Manager 和可选语义复核的系统提示词与动态输入见 [Agent 提示词说明](agent-prompts.zh.md)。

在同一 Slurm 作业中启动 Qwen 服务和 MATSim，参见 [HPC 单作业运行说明](HPC单作业运行.md)。

| Guide | Start here when you want to… |
| :--- | :--- |
| [Getting started](getting-started.md) | Install the project, check inputs, complete a first run, and connect a model service. |
| [Methods & protocols](methods-and-protocols.md) | Understand roles, action spaces, information access, memory, execution, and failure handling. |
| [Experiments & evaluation](experiments-and-evaluation.md) | Configure supported experiment modes and preserve the settings and records needed to reproduce a run. |
| [Scenarios & data](scenarios-and-data.md) | Understand the Sioux Falls inputs, population sampling, data provenance, and scenario extension requirements. |

The experiment guide currently covers setup and run records. Experimental results and the detailed evaluation protocol will be added separately.

## Getting started

Read [Getting started](getting-started.md) in order:

1. Check JDK 25 and build the executable JAR.
2. Validate a small input configuration without simulation or model calls.
3. Run a two-day native diagnostic baseline.
4. [Configure and check a model API](getting-started.md#5-connect-an-existing-model-service), then start a small LLM-traveler run.

For additional deployment options, see the [operating guide](usage-guide.md#installation), [Chinese operating guide](usage-guide.zh.md), and [Slurm deployment notes](../scripts/agentic_morning_qwen35.md). Slurm templates require cluster-specific settings and an existing model service.

## Methods & protocols

Read [Methods & protocols](methods-and-protocols.md) for the current reference implementation. It explains how target-trip choices and scheduled toll policies enter the same traffic environment, while traveler and manager memories retain different information.

Topic references:

| Topic | Reference |
| :--- | :--- |
| Traveler prompts, continuous summaries, strict JSON, and optional review | [Traveler v9](traveler-v9.md) |
| Target-cohort selection | [Planned-arrival cohort notes](../scripts/planned_arrival_cohort.md) |
| Joint traveler actions and basic manager pricing | [Joint protocol notes, Chinese](../scripts/manager_basic_joint.md) |
| Daily manager reflection and periodic policy updates | [Manager memory notes, Chinese](../scripts/manager_daily_memory.md) |
| Optional restricted manager profile | [Controlled manager notes, Chinese](../scripts/manager_v5.md) |

Some topic notes retain development history. Read their version and profile labels before applying settings to a new run.

## Experiments & evaluation

Read [Experiments & evaluation](experiments-and-evaluation.md) for native, traveler-only, manager-only, joint, and fixed-toll configurations. The guide includes Bash and PowerShell command templates, information-group settings, request-budget planning, and a run-configuration template.

Additional references:

- [Detailed operating commands](usage-guide.md#experiments).
- [Fixed-policy file format, Chinese](../scenarios/SiouxFalls-public/fixed-tolls.md).
- [Replay runner](../scripts/replay_morning_choices.ps1) and [example model configuration](../scenarios/SiouxFalls-public/replay-models.example.json).
- [Contribution and experiment-report requirements](../CONTRIBUTING.md).

## Scenarios & data

Read [Scenarios & data](scenarios-and-data.md) for the distributed enriched Sioux Falls scenario, the distinction between demand level and sample rate, units, fixed inputs, and extension requirements.

Source references:

- [Current MATSim configuration](../scenarios/SiouxFalls-public/config.xml).
- [Detailed public-scenario notes, Chinese](../scenarios/SiouxFalls-public/README.md).
- [Input-data notes](../scenarios/Souix%20Falls%202014/README.md).
- [Third-party attribution, modifications, and input hashes](../THIRD_PARTY.md).

## Documentation scope

These guides describe the `org.matsim.agentic.MorningExperiment` interface, Traveler v9, the default `basic` manager, and manifest schema 23, as checked against repository source on **2026-10-16**. The protocol description draws on the framework draft dated 2026-10-13 and technical notes dated 2026-10-14, with current source used to resolve implementation details.

The guides distinguish implemented behavior from the broader AgenticCity direction. Current adaptation uses model inference and memory updates. Mode choice, manager teams, elections, model-weight training, and integration with other urban systems are outside this reference implementation.

For a particular run, preserve its commit, runtime JAR hash, effective configuration, and `morning/manifest.json`. Explicit arguments and environment variables can change defaults. Preparation checks, complete traffic runs, and research findings are different stages; only the corresponding records establish what was actually performed.
