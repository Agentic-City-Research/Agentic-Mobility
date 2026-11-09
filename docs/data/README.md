# Experiment data inventory

[Documentation](../README.md) · [Reported experiments](../reported-experiments.md) · [Scenarios and data](../scenarios-and-data.md) · [Figure inventory](../assets/README.md)

## Current manuscript summary

The manuscript reports **five configurations at simulation seed 268, each completed over log Days0–99**. The manuscript source is not included in this repository; the available summary and underlying exports are listed below.

[manuscript-demonstration.json](manuscript-demonstration.json) transcribes the manuscript's outcome table, settings, choice-change rates, execution audit, and recorded inference workload. Outcome entries retain manuscript display precision: integer person-hours/day, one decimal for target minutes/trip and lateness percentages. Relative changes are the percentages reported in the manuscript, not ratios reconstructed from rounded entries. This file supplies a machine-readable summary; it contains no synthesized daily trajectories or individual records.

The demonstration uses 16,822 simulated people from a 20% sample of the restored 84,110-person input, population/assignment seed 6021, 11,793 fixed targets, and 11,332 work targets. Full-record Joint checks cover 1,179,300 finalized decisions and 100-day personal-memory chains, with three policy decisions and 100 completed daily manager-memory updates. Information-access inspection covers 4,000 sampled contexts only: 2,000 from Traveler and 2,000 from Joint. These are accepted-output/execution checks, not systematic semantic validation of all reasons and memories.

## Selected seed-268 case evidence

The [individual examples](../assets/data/seed268/seed268-individual-cases.md) preserve 18 selected date/trip records, including the interaction trace and explanation inconsistency used in the manuscript. These are purposeful examples, not a representative sample or a full 100-day audit.

| File | Available scope |
| :--- | :--- |
| [individual-case-evidence.json](../assets/data/seed268/individual-case-evidence.json) | Selected context, model output, decision, and execution excerpts with source hashes |
| [individual-case-traces.csv](../assets/data/seed268/individual-case-traces.csv) | 18 dated records from the three featured traces |
| [individual-case-validation.json](../assets/data/seed268/individual-case-validation.json) | Validation scoped to the published excerpts |

The complete five-configuration daily aggregate trajectories and full-precision run summaries are supplied in the [100-day export package](seed268/README.md). The case excerpts remain a separate selected subset of individual records. Original source hashes and line references in case evidence identify those excerpts; full personal logs remain in the external run archive.

## Completed 100-day archive exports

The [100-day export package](seed268/README.md) supplies the aggregate time series, run summaries, and audit summaries described in the manuscript. These exports were recomputed from the author's `arxiv-v1-data` archive and reconciled against the manuscript appendix; that manuscript source is not included here.

| File | Coverage and checks |
| :--- | :--- |
| [Daily metrics](seed268/daily-metrics.csv) | All five configurations over Days0–99: 500 unique configuration/day rows, including completion counts and unsmoothed outcomes/choice changes |
| [Run summaries](seed268/run-summary.csv) | Full-precision means calculated from source records; recorded API calls and token totals |
| [Execution audit](seed268/execution-audit.json) | Full-window Joint finalized-action, execution, and memory checks; manager updates; separate 4,000-context information-access checks |
| [Policy schedule](seed268/policy-schedule.json) | Six Manager/Joint review opportunities and the accepted pricing rules |
| [Run metadata](seed268/run-metadata.json) | Source-run mapping, archived executable/input identities, generation settings, source hashes, and export hashes |
| [Validation](seed268/validation.json) | Manuscript outcome/behavior agreement, unrounded relative differences, workload totals, and shared cohort/candidate identities |

Joint's source-derived target-trip mean is 16.2499879024 minutes, while the manuscript displays 16.3. The [precision note](seed268/README.md#measurement-and-precision) and validation JSON preserve this difference explicitly; manuscript-facing tables keep 16.3 and the export CSV keeps the original precision.

The [export-package guide](seed268/README.md#source-location-and-regeneration) records the supplied local archive path and regeneration command. Full individual records and model conversations remain in that external archive; they are not copied into this documentation package. The local archive path does not provide public download access. Exact reruns still need the corresponding runtime, original inputs/prompts, model service, and a verified release-to-run mapping.

## Historical seed-9261 archive

The [18 October 2026 archive](../history/2026-10-18/README.md) contains the earlier 61-day Traveler and 500-day Manager comparisons, four retained CSVs, and their metadata. The expanded Traveler CSV preserves all fields and rows of the removed duplicate export. Historical settings, the +3.52% Manager car-time gain, and aggregate hashes are documented together in that archive.

## Recalculation and reruns

Recalculate each CSV only over its labeled window and population/trip scope. Use unrounded values for ratios and percentage-point differences; do not combine historical windows with manuscript table entries. Consecutive days and people sharing a run are dependent observations.

Exact historical reruns additionally require a corresponding executable or code revision, complete input/candidate/profile files, original prompts, final configuration, and model-service settings, with an accessible release-to-run mapping. A traffic seed does not fix remote model responses. The aggregate exports and manuscript transcription do not supply that complete bundle.
