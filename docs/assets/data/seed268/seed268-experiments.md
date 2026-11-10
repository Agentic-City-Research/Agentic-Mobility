# Seed 268: five-configuration comparison

[Project experiments](../../../../README.md#experiments) · [Reported experiments](../../../reported-experiments.md) · [Methods and protocols](../../../methods-and-protocols.md)

This record follows the manuscript's execution-validation section and appendix, whose sources are not included in this repository. It describes the reported seed-268 demonstration, not a newly executed simulation. Day labels below use simulation Days 1–100. Source records use zero-based log days (simulation day = log day + 1).

## Completion and comparison window

All five configurations—MATSim, MNL, LLM Traveler, MATSim + Manager, and LLM Traveler + Manager—completed **100 simulated days**, **Days 1–100**, with simulation seed **268**. The common comparison window is Days 1–100 for every configuration. There is one run per configuration; consecutive days share persistent states and are not independent replications.

The results and audit counts below follow the manuscript. The [100-day export package](../../../data/seed268/README.md) recomputes aggregate outcomes, behavior rates, and Joint execution checks from the completed runs. Distributed records include 500 daily aggregate rows, five run summaries, audit/provenance JSON, the manuscript-summary JSON, and selected individual-case excerpts. See [Available records and reproduction scope](#available-records-and-reproduction-scope).

## Matched inputs and configuration differences

The manuscript reports input checks covering network, population, facilities, capacities, target identities, and background settings. The scenario retains 20% of the restored 84,110-person input: **16,822 simulated people**, with flow capacity factor **0.2** and storage capacity factor **0.2^0.75 ≈ 0.2991**. Population sampling, synthetic profiles, information groups, and controlled-roster selection use seed **6021**, separately from simulation seed 268.

The fixed target cohort contains **11,793 trips by 11,793 people**, spanning **2,877 OD pairs**. Work-arrival evaluation uses **11,332 work targets** with deadlines. All five configurations completed 11,793 target trips and **33,644 car legs per day**, with no stuck agents. Full-day background activity and non-target trips retain MATSim mechanisms.

MNL, LLM Traveler, and Joint use fixed route and departure-time sets. Routes have up to four candidates per OD; departure options span 90 minutes before to 30 minutes after the original plan at 15-minute intervals, clipped at activity and simulation boundaries. MATSim target decisions are not restricted to these candidate sets. Matching inputs does not remove differences in executable builds, preference representations, or remote model responses. MNL uses uncalibrated homogeneous coefficients, so its performance is not a general benchmark of calibrated MNL methods. Exact model-service settings and executable identities require the corresponding run-specific archive.

## Main results: Days 1–100

These daily means reproduce the manuscript's display precision over the common 100-day window. Rounded entries do not quantify uncertainty and should not be used to reconstruct full-precision percentages.

The [manuscript summary JSON](../../../data/manuscript-demonstration.json) transcribes these reported settings, outcomes, behavior rates, and audit/workload counts. The [data inventory](../../../data/README.md) distinguishes this transcription from selected case records and the separate seed-9261 comparisons.

| Configuration | All-day car time (person-hours/day) | Target-trip car time (min/trip) | Work late-arrival rate |
| :--- | ---: | ---: | ---: |
| MATSim | 12,431 | 29.3 | 20.0% |
| MNL | 17,201 | 53.2 | 31.3% |
| LLM Traveler | **9,958** | 17.4 | 5.6% |
| MATSim + Manager | 12,663 | 29.6 | 20.4% |
| LLM Traveler + Manager | 11,414 | **16.3** | **3.7%** |

The manuscript's Joint value of 16.3 is retained here. The source-derived mean is 16.2499879024 minutes, which directly rounds to 16.2 at one decimal; the original table-rounding pipeline is not identified. The [precision note](../../../data/seed268/README.md#measurement-and-precision) documents the discrepancy while preserving source precision in the CSV export.

- **All-day car time:** total elapsed car-leg time from car departure to car arrival across the full population, including pre-traffic waiting. It is not just time spent on controlled target trips.
- **Target-trip car time:** mean car duration for the fixed target-trip cohort, which includes multiple activity purposes.
- **Work late-arrival rate:** late work trips divided by the fixed 11,332 work trips/day. A difference between these percentages is expressed in percentage points.
- **Mean daily car time per person:** `60 × all-day car person-hours / 16,822`, in minutes/person. This is the normalized all-population measure shown in the manuscript's outcome figure.
- **Window summary:** arithmetic mean of daily values over Days 1–100. Relative changes use the ratio of unrounded window means, not the mean of daily percentages.
- **Link delay:** a separate vehicle-hours measure excluding pre-traffic waiting. It does not replace the all-day time measure.

Consecutive days and travelers within a run are dependent observations, not independent simulation replications. The manuscript's time-series figures show daily values and trailing seven-day means, with no confidence intervals.

## Individual and system-level outcomes

LLM Traveler has the lowest mean daily car time per person, **19.90% below MATSim**. Joint has the shortest mean target-trip time. Relative to LLM Traveler, Joint has **6.63% lower target-trip car time** but **14.62% higher mean daily car time per person**. MATSim + Manager has higher all-day car time than MATSim in these recorded runs.

Differences already exist before positive charging, preventing attribution of the Joint–Traveler contrast to the manager alone. Target/other-trip and waiting/post-entry decompositions are alternative accounts of the same total; other trips include controlled people's non-target journeys. Detailed individual distributions and daily decompositions cannot be reconstructed from the manuscript's rounded outcome table.

## Actual policy history

| Configuration | Day 21 | Day 51 | Day 81 | Charged days within Days 1–100 |
| :--- | :--- | :--- | :--- | ---: |
| MATSim + Manager | Positive charging begins | Review opportunity | Review opportunity | 80 |
| LLM Traveler + Manager | Retain zero charges | Charge 1.5 scenario units on each of `43_3` and `43_4` | Retain the same charges | 50 |

There are 20 initial toll-free days. Charges apply per actual link entry throughout `[0,108000)` seconds. A policy opportunity does not necessarily change the effective prices. Manager and Joint differ in activation dates and policy histories; they are not responses to an identical fixed policy. Manager-generated explanations record hypotheses, not verified causal effects. Joint assigns 5,897 travelers to current toll announcements and 5,896 to no current announcements, with personal feedback available to both groups.

## Schedule and individual outcomes

LLM Traveler and Joint select departures **34.96** and **39.65 minutes earlier** on average than their original plans. These schedule changes accompany travel-time and punctuality outcomes; no monetary value is assigned to earlier arrival or departure inconvenience. Mean outcomes do not imply that every traveler benefits.

The manuscript reports the following choice-change rates over **99 adjacent-day transitions**. Overall rates use all 11,793 targets. Candidate-based multi-route rates use the 9,013 targets with multiple routes; the remaining 2,780 cannot switch routes within their fixed candidate sets. Routes are identified by complete link sequences, not local candidate labels.

| Configuration | Overall route changes | Multi-route changes | Departure changes |
| :--- | ---: | ---: | ---: |
| MATSim | 5.26% | — | 20.05% |
| MNL | 48.55% | 63.52% | 85.24% |
| LLM Traveler | 3.08% | 4.03% | 29.45% |
| MATSim + Manager | 5.43% | — | 20.28% |
| LLM Traveler + Manager | 2.19% | 2.86% | 28.04% |

A dash denotes a candidate-space comparison that is not applicable to native MATSim choices. Native route and activity-time innovation is disabled on simulation Day 91; existing-plan selection continues. The resulting decline in route switching cannot be interpreted solely as behavioral stabilization. These observations do not establish learned habits, coordination, or welfare improvements.

## Execution validation and model-call scope

The manuscript reports **1,179,300 finalized Joint target decisions** (**11,793 trips × 100 days**) with legal route/departure choices linked to applied plans and completed routes. The 100-day personal-memory chains are continuous, and finalized summaries remain within the 500-character limit. All **three policy decisions** are valid and all **100 daily manager-memory updates** completed. The information-access check covers **4,000 sampled input contexts** only: 2,000 from Traveler and 2,000 from Joint. No current announcements were disclosed to uninformed travelers, and network-traffic visibility was disabled in the sampled contexts.

Output repair was required for **2,433 finalized travel-decision records (0.206%)** and **2,943 personal-memory records (0.250%)**. One manager-memory JSON response was repaired through retry. These checks describe accepted outputs after validation and repair; they do not imply that every initial response was valid. Semantic review was disabled, and execution consistency does not certify every rationale or summary as semantically correct.

The complete-run inference workload is reported in the manuscript appendix. Counts include retries and repairs; tokens are service-reported totals.

| Configuration | API calls | Tokens |
| :--- | ---: | ---: |
| MATSim | 0 | 0 |
| MNL | 0 | 0 |
| LLM Traveler | 2,362,964 | 5,713,538,990 |
| MATSim + Manager | 109 | 872,774 |
| LLM Traveler + Manager | 2,364,090 | 5,451,720,152 |

Joint contains 1,181,736 traveler-decision calls, 1,182,250 personal-memory calls, three manager-policy calls, and 101 manager-memory calls. The 101 manager-memory calls include retry overhead and produce 100 completed daily updates. Each personal task has 1,179,300 finalized records. Extra calls are not hallucination counts; token totals do not measure monetary cost, GPU energy, or end-to-end runtime. Zero LLM calls does not imply zero simulation cost.

## Individual examples

[Selected individual decision traces](seed268-individual-cases.md) connect permitted information, structured actions, generated rationales, and executed outcomes. The manuscript uses Joint traveler `10010_2` on simulation Day 61 and traveler `10042_2` on simulation Day 62; the homepage also presents the separately verified `20336_4` sequence. These cases do not estimate error prevalence or isolate route, departure-time, or shared-condition effects.

## Data identifiers

The manuscript labels **MATSim**, **MNL**, **LLM Traveler**, **MATSim + Manager**, and **LLM Traveler + Manager** correspond to archived identifiers `Native`, `MNL` (also displayed as `Constrained MNL`), `Traveler`, `Manager`, and `Joint`. The appendix also uses “Pricing” for the manager role. Machine-readable identifiers and recorded outputs retain their original values.

## Available records and reproduction scope

The current files support recalculating manuscript aggregates, inspecting source-derived audit summaries, and tracing selected cases. Full raw personal logs and simulation files remain in the external archive.

The [data inventory](../../../data/README.md) lists the aggregate exports, source-run mapping, manuscript summary, and selected case evidence, including the separately checked six-day `20336_4` sequence.

The [100-day outcome figure](../../seed268/outcomes.svg) uses mean daily car time per person, target-trip time, and work lateness; the daily export supplies those unsmoothed series. The [figure inventory](../../README.md) also links the manuscript's canonical PDF figures.

The [source-location guide](../../../data/seed268/README.md#source-location-and-regeneration) identifies the author's local `arxiv-v1-data` archive and regeneration command. Original simulation logs and model conversations remain in that external archive. Archived executable hashes identify historical builds; exact reruns additionally require the corresponding executable or source revision, complete inputs and candidate files, original prompts, final configuration, model-service settings, and a pinned release-to-run mapping. A traffic seed alone does not fix remote model responses.
