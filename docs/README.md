# Documentation

[Project overview](../README.md)

For the current arXiv demonstration, start with [Reported experiments](reported-experiments.md): **five configurations, simulation seed 268, 100 days each (log Days0–99)**. The [manuscript](../paper/AgenticMobility_arXiv/main.tex) and its active appendix, `appendices/app_1.tex`, define the reported settings and results.

| Guide | Contents |
| :--- | :--- |
| [Getting started](getting-started.md) | Installation, diagnostic run, model API setup, and troubleshooting |
| [Methods & protocols](methods-and-protocols.md) | Agent roles, route/time choices, information access, reflection, and pricing |
| [Experiments & evaluation](experiments-and-evaluation.md) | Configuration templates, baselines, request budgets, and run records |
| [Joint MNL baseline](joint-mnl-baseline.md) | Recovered route/departure baseline, explicit parameters, information boundaries, and provenance |
| [Reported experiments](reported-experiments.md) | Current 100-day results and execution audit |
| [Seed-268 experiment record](assets/data/seed268/seed268-experiments.md) | Five configurations, policy timing, behavior metrics, and inference workload |
| [Individual traces](assets/data/seed268/seed268-individual-cases.md) | Recorded decisions, execution, and an explanation inconsistency |
| [Scenarios & data](scenarios-and-data.md) | Scenario inputs, provenance, sampling, capacity, and extensions |
| [Operating guide](usage-guide.md) | Detailed execution options and workflows |
| [中文操作指南](usage-guide.zh.md) | 安装、运行与故障排查 |
| [Data inventory](data/README.md) | Complete 100-day aggregate exports, source-derived audit summaries, manuscript summary, and selected cases |
| [Figure inventory](assets/README.md) | Framework figures, current 100-day displays, and simulation videos |
| [Historical experiments](history/2026-10-18/README.md) | Earlier seed-9261 comparisons, aggregate CSVs, and metadata |
| [Community](community.md) | Questions, research discussions, and contributions |

Start with the diagnostic baseline in Getting started. The 100-person/two-day check and 10%/three-controlled-person examples are setup configurations. The manuscript uses a 20% sample of the original-demand population, population seed 6021, simulation seed 268, and 11,793 fixed target trips; see the [configuration mapping](experiments-and-evaluation.md#manuscript-configuration-mapping).

The data inventory links [500 daily aggregate rows and five run summaries](data/seed268/README.md), source-derived audit summaries, the manuscript transcription, selected individual-case evidence, and historical seed-9261 comparisons. Source hashes and archived runtime identities identify the records used for these exports. Full raw logs remain in the external `arxiv-v1-data` archive. Exact historical reruns additionally require the corresponding executable, complete inputs, original prompts, final configuration, and model-service settings.
