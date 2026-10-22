# Seed 268: five-configuration comparison

[Project experiments](../README.md#experiments) · [Methods and protocols](methods-and-protocols.md)

This record accompanies the analysis snapshot dated **22 October 2026**. It describes the supplied seed268 analysis, not a newly executed simulation. All day labels are zero-based.

## Completion and comparison window

MATSim agents, Constrained MNL, LLM traveler agents, and Manager have completed Days 0–99. Joint has complete metrics and verified trip records for Days 0–87, but no final status file or effective output XML. Its progress snapshot is on Day 88 in the LLM-decision phase. Therefore every five-condition comparison uses Days 0–87. No partial Day 88 output, extrapolated value, or zero-filled future day enters these results.

Some Joint gzip streams were not finalized. The source analysis accepted only newline-terminated, parseable records from the available stream and reconciled complete days against trip-level data. This is an archived completed prefix, not a claim that the full 100-day Joint run finished.

## Matched inputs and configuration differences

The analysis verifies identical network, population, and facilities content hashes; sampled population identities; target-trip cohort, original schedules, endpoints and departure options; and candidate-route contents. Constrained MNL, LLM traveler agents and Joint also share controlled membership, synthetic profiles and information assignments. There are 16,822 simulated people, 11,793 target trips/day and 11,332 work trips/day with an arrival deadline. All 33,644 daily car legs completed on included days; stuck counts are zero.

The equality checks do not establish equal implementations. The MATSim agents and Manager configurations share a JAR. MNL, LLM traveler agents and Joint use different builds. Joint lacks its final effective XML. The simulation seed does not fix model-service sampling or deployment behavior. Constrained MNL is a fixed-action-set baseline with uncalibrated homogeneous example parameters; synthetic profiles are not mapped to heterogeneous coefficients. Its performance is not a general benchmark of calibrated MNL methods.

LLM traveler agents use the recorded model ID `qwen3.5-9b`; the pricing role uses `deepseek-flash`.

## Main results: Days 0–87

| Configuration | All-day car time (person-hours/day) | Change vs. MATSim agents | Target-trip car time (min) | Work late-arrival rate |
| :--- | ---: | ---: | ---: | ---: |
| MATSim agents | 12,322.75 | — | 28.31 | 19.71% |
| Constrained MNL | 17,307.06 | +40.45% | 53.23 | 31.28% |
| LLM traveler agents | **10,044.57** | −18.49% | 17.43 | 5.67% |
| Manager | 12,530.01 | +1.68% | 28.48 | 19.91% |
| Joint | 11,568.74 | −6.12% | **16.33** | **3.82%** |

- **All-day car time:** total elapsed car-leg time from car departure to car arrival across the full population, including pre-traffic waiting. It is not just time spent on controlled target trips.
- **Target-trip car time:** mean car duration for the fixed target-trip cohort, which includes multiple activity purposes.
- **Work late-arrival rate:** late work trips divided by the fixed 11,332 work trips/day. A difference between these percentages is expressed in percentage points.
- **Window summary:** arithmetic mean of daily values. Relative changes use the ratio of unrounded window means, not the mean of daily percentages.
- **Link delay:** a separate vehicle-hours measure excluding pre-traffic waiting. It does not replace the all-day time measure.

Consecutive days and travelers within a run are dependent observations, not independent simulation replications. The figures show daily values and trailing seven-day means, with no confidence intervals.

## Joint versus LLM traveler agents

Joint has 1.09 minutes (6.28%) lower target-trip car time and 1.85 percentage points lower work lateness, but 15.17% higher all-day car time. The all-day difference is 1,524.17 person-hours/day:

| Decomposition | Joint minus LLM traveler agents (person-hours/day) |
| :--- | ---: |
| Target trips | −215.01 |
| Other car trips | +1,739.18 |
| Total | +1,524.17 |

The same total can instead be decomposed into **+1,178.45** person-hours/day of pre-traffic waiting and **+345.72** person-hours/day after traffic entry. These are two decompositions of the same difference and must not be added together. Other trips include the controlled people's non-target journeys; they are not a separate untreated population.

The all-day difference is already **+14.90% on Days 0–49**, before Joint charges any toll. Even on Days 0–19, when neither pricing configuration charges tolls, Joint is 12.53% higher than LLM traveler agents. Removing Day 0 does not remove the gap. These observations prevent attribution of the Joint–LLM traveler agents difference to the added pricing mechanism or a claim of causal synergy.

## Actual policy history

| Configuration | Day 20 | Day 50 | Day 80 | Charged days within Days 0–87 |
| :--- | :--- | :--- | :--- | ---: |
| Manager | Test charges on 10 links | Hold | Adjust to charges on 6 links | 68 |
| Joint | Hold zero charges | Charge 1.5 scenario units on each of `43_4` and `43_3` | Hold | 38 |

Charges apply per actual link entry throughout `[0,108000)` seconds. Manager and Joint differ in activation dates, links, prices and history; they are not responses to an identical fixed policy. Manager-generated explanations record hypotheses, not verified causal effects.

## Schedule and individual outcomes

LLM traveler agents and Joint depart 34.93 and 39.55 minutes earlier on average than their original plans. Earliest-boundary selection rates are 39.91% and 44.85%. Mean positive early-arrival durations across all work trips are 88.94 and 94.22 minutes, respectively; this is a schedule measure and is not automatically a monetized welfare cost.

Relative to LLM traveler agents, Joint saves 0.31 minutes at the median target traveler. The shares with lower and higher mean car time are 59.86% and 40.07%. These percentages do not sum to 100% because some differences are zero and values are rounded. Mean results do not imply universal benefit. Information-group differences already exist before tolls and are not evidence that withholding information improves decisions.

## Execution validation and model-call scope

The supplied audit reports **1,037,784** legal complete-day Joint decisions and the same number of matching executions (11,793 trips × 88 days). Selected route/time records agree with trip and execution checks, and trip-level daily car time, departure shifts and lateness reconcile with metrics. This establishes execution consistency for the checked records, not the faithfulness of every rationale or the semantic correctness of all summaries.

The analysis checks three valid Joint policy outputs and 88 completed-day pricing-role reflections. One invalid JSON reflection was repaired. Full traveler attempt streams and the complete semantic summary chain were not audited. Joint's API-usage snapshot includes partial Day 88 calls and inconsistent counters; it is not used for a strict per-completed-day cost or an overall failure-rate estimate.

## Individual examples

[Three individual decision traces](seed268-individual-cases.md) connect permitted information, structured actions, generated rationales and executed outcomes. Two concise examples appear on the homepage; a third documents a rationale/action time discrepancy. Published evidence and a read-only extraction script support inspecting the cases.

## Separate 100-day results

The following table uses **Days 0–99 for the four completed runs only**. It is not combined with the Joint 88-day table.

| Configuration | All-day car time (person-hours/day) | Change vs. MATSim agents | Target-trip car time (min) | Work late-arrival rate |
| :--- | ---: | ---: | ---: | ---: |
| MATSim agents | 12,431.45 | — | 29.32 | 19.97% |
| Constrained MNL | 17,200.90 | +38.37% | 53.19 | 31.25% |
| LLM traveler agents | 9,958.11 | −19.90% | 17.40 | 5.61% |
| Manager | 12,662.64 | +1.86% | 29.64 | 20.42% |

Joint has no complete 100-day result in this snapshot. The main comparison ends before the Day 90 MATSim strategy-innovation shutdown phase.

## Data identifiers

The display labels **MATSim agents** and **LLM traveler agents** correspond to the original CSV identifiers `Native` and `Traveler`, respectively. Manager and Joint denote MATSim agents and LLM traveler agents combined with an LLM pricing agent. Machine-readable identifiers and recorded outputs retain their original values.

## Published aggregate files

| File | Content |
| :--- | :--- |
| [Common-window summary](data/seed268_five_conditions_20261022/comparison_common88.csv) | Five configurations over Days 0–87 |
| [Daily metrics](data/seed268_five_conditions_20261022/daily_metrics.csv) | Available complete daily aggregates, including four 100-day runs |
| [Period summaries](data/seed268_five_conditions_20261022/period_summary.csv) | Explicitly labeled common, pre-pricing, pricing and 100-day windows |
| [Paired comparisons](data/seed268_five_conditions_20261022/paired_comparisons.csv) | Differences and ratios for each reported comparison/window |
| [Individual-effect summaries](data/seed268_five_conditions_20261022/individual_effects.csv) | Within-run medians and shares with lower or higher target-trip car time |
| [Matching audit](data/seed268_five_conditions_20261022/matching_audit.json) | Matched inputs and known implementation differences |
| [Joint execution audit](data/seed268_five_conditions_20261022/joint_execution_audit.json) | Counts of legal decisions and matching execution checks |
| [Analysis verification](data/seed268_five_conditions_20261022/verification.json) | Checks recorded by the source analysis |
| [Snapshot metadata](data/seed268_five_conditions_20261022/metadata.json) | Completion state, runtime JAR hashes and copied-file checksums |

PNG and editable SVG figures are stored in [the figure directory](assets/seed268_five_conditions_20261022). The aggregate files permit recomputing the displayed means, percentages and time decompositions. The original simulation logs, all model conversations and runtime JARs are not included in this README update package; it does not provide a complete rerun of the underlying experiments.
