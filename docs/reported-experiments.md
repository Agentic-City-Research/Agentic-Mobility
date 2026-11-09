# Reported experiments

[Project overview](../README.md#experiments) · [Documentation](README.md) · [Configuration templates](experiments-and-evaluation.md)

This page documents the five-configuration, 100-day demonstration described in the manuscript's execution-validation section and appendix. The manuscript source is not included in this repository; the linked experiment records and aggregate exports below are the available evidence. Earlier seed-9261 comparisons are retained under [the historical archive](history/2026-10-18/README.md).

## Current manuscript: comparison settings

All five configurations completed 100 simulated days with simulation seed **268**, using the common window **Days0–99**. Day labels on this page are zero-based; these correspond to simulation days 1–100. There is one run per configuration, and successive days are dependent observations.

| Configuration | Target-trip decisions | LLM manager |
| :--- | :--- | :--- |
| MATSim | Native MATSim plan selection and replanning | Disabled |
| MNL | Constrained joint route–departure choice within fixed candidate sets | Disabled |
| LLM Traveler | LLM route–departure choice within fixed candidate sets | Disabled |
| MATSim + Manager | Native MATSim plan selection and replanning | Enabled |
| LLM Traveler + Manager | LLM route–departure choice within fixed candidate sets | Enabled |

The scenario samples 20% of the restored 84,110-person input, yielding **16,822 simulated people**. Population sampling, synthetic profiles, information assignments, and controlled-roster selection use seed **6021**. Flow and storage capacity factors are **0.2** and **0.2^0.75 ≈ 0.2991**. The fixed cohort contains **11,793 target trips** across **2,877 OD pairs**, including **11,332 work targets** with arrival deadlines. Background trips retain MATSim mechanisms and full-day activity chains.

Pricing review opportunities occur on **Days20, 50, and 80**. Positive charging begins on **Day20** in MATSim + Manager and **Day50** in LLM Traveler + Manager. An opportunity can retain an empty or unchanged policy; it does not necessarily introduce new charges. The [detailed seed-268 record](assets/data/seed268/seed268-experiments.md) documents the policy timing and label mapping.

## Current manuscript: outcomes and measurement

The table reproduces the manuscript's rounded daily means over Days0–99. All configurations completed 11,793 target trips and 33,644 car legs per day, with no stuck agents.

| Configuration | Total car time (person-hours/day) | Target-trip car time (min/trip) | Work late-arrival rate |
| :--- | ---: | ---: | ---: |
| MATSim | 12,431 | 29.3 | 20.0% |
| MNL | 17,201 | 53.2 | 31.3% |
| LLM Traveler | 9,958 | 17.4 | 5.6% |
| MATSim + Manager | 12,663 | 29.6 | 20.4% |
| LLM Traveler + Manager | 11,414 | 16.3 | 3.7% |

The table retains the manuscript's display values. The raw Joint target-trip mean is 16.2499879024 minutes; direct one-decimal rounding gives 16.2. See the [precision note](data/seed268/README.md#measurement-and-precision) for this display discrepancy and the source-derived CSV values.

- **Total daily car time** includes all car legs and waiting before network entry. The corresponding mean daily car time per person is `60 × total person-hours / 16,822`, in minutes/person.
- **Target-trip car time** covers the fixed 11,793 controlled target trips. It differs from door-to-door time, which also includes access and egress.
- **Work lateness** uses the fixed denominator of 11,332 work targets. Differences in lateness rates are percentage points.
- **Window summaries** average daily values over Days0–99. Relative changes use ratios of unrounded window means; they should not be recomputed from the rounded table above. Plotted smoothing is a trailing seven-day mean.
- **Completed-link delay**, the manager's objective, excludes waiting before network entry and has different event boundaries from complete trip time.

LLM Traveler has the lowest mean daily car time per person, **19.90% below MATSim**. LLM Traveler + Manager has the shortest target-trip time: relative to LLM Traveler, its target-trip time is **6.63% lower**, while mean daily car time per person is **14.62% higher**. MATSim + Manager records higher total car time than MATSim in this seed-268 comparison. The historical 3.52% car-time improvement in the [archive](history/2026-10-18/README.md#manager-outcomes) belongs to a different seed and window.

Differences between Traveler and Joint already exist before positive charging, so the contrast cannot be attributed to the manager alone. Traveler and Joint select departures **34.96** and **39.65 minutes earlier** than their original plans on average. These time outcomes do not establish welfare improvements, learned habits, or causal coordination. Some executable builds differ, and MNL uses uncalibrated homogeneous coefficients; rankings describe the recorded configurations rather than general comparative effectiveness.

## Current manuscript: execution validation

The manuscript reports the following audit scopes. Full-record action and memory checks concern **LLM Traveler + Manager**; the information-access check concerns the inspected samples only.

| Check | Coverage | Recorded result |
| :--- | :--- | :--- |
| Action constraints and execution | 1,179,300 finalized target decisions: 11,793 × 100 days | Legal route/time choices linked to applied plans and completed routes |
| Personal state continuity | 100-day personal-memory chains | Continuous chains; finalized summaries within the 500-character limit |
| Pricing decisions and state | 3 policy decisions; 100 daily memory updates | Valid policies; all daily memory updates completed |
| Information access | 4,000 sampled input contexts: 2,000 Traveler and 2,000 Joint | No current announcements disclosed to uninformed travelers; network-traffic visibility is disabled in the sampled contexts |

Output repair affected **2,433 travel-decision records (0.206%)** and **2,943 personal-memory records (0.250%)**. One manager-memory JSON response required repair. These are accepted outputs after validation and retry, not a claim that every initial response was valid. Semantic review was disabled; execution consistency does not establish the accuracy of all generated reasons or summaries. See the [detailed audit and workload](assets/data/seed268/seed268-experiments.md#execution-validation-and-model-call-scope).

## Current manuscript: distributed-record scope

The [data inventory](data/README.md) links [complete 100-day aggregate exports](data/seed268/README.md), a [machine-readable manuscript summary](data/manuscript-demonstration.json), selected case evidence, and historical seed-9261 comparisons. The summary preserves the paper's display precision; the separate aggregate CSVs retain source-derived numeric precision. The [figure inventory](assets/README.md) identifies the framework and 100-day displays.

The [available seed-268 records](assets/data/seed268/seed268-experiments.md#available-records-and-reproduction-scope) include 500 configuration/day aggregate rows, five run summaries, policy decisions, source metadata, and a recomputed full-window Joint execution audit. The exports reconcile with the manuscript's outcomes, behavior rates, repair counts, and inference workload. Full personal records and raw audit streams remain in the author's external `arxiv-v1-data` archive.

The [export-package guide](data/seed268/README.md) documents measurement fields, audit scope, the source archive location, and the command to regenerate these exports from the original records.

Exact reruns additionally require the corresponding executable or code revision, complete inputs and candidate files, original prompts, final configuration, and model-service settings. The current repository revision and a traffic seed alone do not identify the historical executable or fix remote model responses. The manuscript's implementation source is not included in this repository.

## Historical experiments

The [18 October 2026 archive](history/2026-10-18/README.md) contains the earlier seed-9261 Traveler and Manager comparisons, their aggregate CSVs, and metadata. Those runs use different durations and are separate from the current manuscript demonstration.
