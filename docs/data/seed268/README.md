# Completed seed-268 experiment exports

[Data inventory](../README.md) · [Reported experiments](../../reported-experiments.md) · [Detailed experiment record](../../assets/data/seed268/seed268-experiments.md)

These files are derived from the five completed runs in the author's `arxiv-v1-data` archive. Each run covers log Days0–99, with simulation seed 268, population seed 6021, 16,822 simulated people, 11,793 fixed target trips, and 11,332 work targets. The archive also contains other seeds and video-export copies; these exports use only the five run paths recorded in the metadata.

| File                                         | Contents                                                                                                                               |
| :------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------- |
| [daily-metrics.csv](daily-metrics.csv)       | 500 unique configuration/day rows: outcomes, completion counts, selected-action changes, route-flow turnover, and departure offsets    |
| [run-summary.csv](run-summary.csv)           | Five run summaries calculated from unrounded daily values, with recorded API calls and token totals                                    |
| [execution-audit.json](execution-audit.json) | Recomputed Joint action/execution checks, 100-day personal-memory continuity, repairs, manager updates, and sampled information checks |
| [policy-schedule.json](policy-schedule.json) | Manager and Joint policy decisions at Days20, 50, and 80, including accepted rules and price fingerprints                              |
| [run-metadata.json](run-metadata.json)       | Source-run mapping, source/export hashes, archived executable and input identities, selected manifest fields, and generation settings  |
| [validation.json](validation.json)           | Agreement with manuscript display values, workload totals, relative differences, and shared cohort/candidate identities                |

## Measurement and precision

CSV values retain the precision produced by Python from the numeric source records. They are not reconstructed from manuscript rounding. Daily rows contain original unsmoothed values; trailing seven-day means are a plotting operation.

One manuscript display needs a precision note: Joint's source-derived mean target-trip car time is **16.2499879024 minutes**, while the manuscript table displays **16.3**. Direct rounding of the source mean to one decimal yields **16.2**. The original table-rounding pipeline has not been identified. Narrative tables retain the manuscript's 16.3; CSV exports retain the source value, and `validation.json` records this difference explicitly. The source-derived relative change against Traveler still rounds to the reported −6.63%.

| Field or field group | Meaning |
| :--- | :--- |
| `condition`, `seed`, `day` | `Native`, `MNL`, `Traveler`, `Manager`, or `Joint`; simulation seed; zero-based log day |
| `total_car_person_hours` | All car-departure-to-car-arrival durations, including pre-traffic waiting, from the full-population daily record |
| `mean_daily_car_min_per_person` | `60 × total_car_person_hours / 16822` |
| `mean_target_car_min`, `daily_target_p95_car_min`, `mean_target_door_min` | Fixed target-cohort time measures with their distinct event boundaries; the P95 column is each day's percentile |
| `work_late_percent` | `100 × late_work_trips / 11332` |
| `all_car_pretraffic_wait_person_hours` | Full-population waiting before traffic entry; already included in total car time |
| `completed_link_delay_vehicle_hours` | Completed full-link delay in vehicle-hours; its event boundaries differ from trip time |
| `overall_route_switch_percent`, `departure_adjustment_percent` | Adjacent-day changes in selected route fingerprints or selected departures across all 11,793 targets |
| `multiple_route_switch_percent` | Candidate-based changes among 9,013 multi-route targets; blank for native-choice configurations |
| `route_flow_turnover_percent` | `100 × sum(abs(current_OD_route_count − previous_OD_route_count)) / (2 × 11793)` |
| `mean_selected_departure_minutes_earlier_than_original` | Negative mean selected-minus-original departure offset in minutes; positive values mean earlier departures |

Day0 has no previous-day comparison, so change-count and change-rate fields are blank. They are not zero observations. Run summaries average available daily values: outcomes and offsets use 100 days, while change rates and turnover use 99 transitions. A blank multi-route rate in Native or Manager means the fixed-candidate restriction does not apply. Summary column names prefix the corresponding daily field with `mean_`; workload columns are recorded totals rather than daily means.

Full-population time and waiting come from `metrics.jsonl.gz`. Target means are reconciled against `trips.jsonl.gz`; selected routes, departures, and OD counts are recomputed from those target records. Route identity uses the complete ordered-link fingerprint, not the local `c0`/`c1` label. Relative differences are ratios of unrounded window means; lateness differences use percentage points. See the [manuscript](https://arxiv.org/abs/2611.01918) for its measurement appendix.

## Audit scope

The Joint audit covers 1,179,300 finalized target choices and execution checks, and 1,179,300 committed personal-memory updates. It compares finalized and applied actions with execution records; checks applied fingerprints and expected link sequences against the selected candidate; checks expected versus executed links and plan times; and reconciles selected and realized departure times with trip records. Each target's previous memory, previous day, version, and final memory are checked across all 100 days.

Information checks use **2,000 sampled contexts from Traveler and 2,000 from Joint**, with 20 records per day in each configuration. They inspect network-visibility flags in both configurations and current-cost/policy fields in route and departure-option projections. Uninformed Joint contexts retain unknown current tolls as null; Traveler contexts explicitly disable charging and may retain known base monetary costs. This sample scope is separate from full-record Joint action checks. Accepted-output legality, state continuity, and these sampled checks do not establish semantic accuracy of all generated reasons or memories. The audit JSON documents the specific checks performed.

## Source location and regeneration

The raw archive supplied for this documentation update is stored outside this repository. The source paths in `run-metadata.json` are relative to that archive, allowing it to be moved. It is not a public download location. Raw personal contexts, model responses, and complete simulation files remain in the external archive; the documentation contains aggregate exports, audit summaries, and the separately published case excerpts.

Run the [exporter](../../../scripts/export_arxiv_documentation_data.py) from the repository root:

```powershell
python scripts/export_arxiv_documentation_data.py --source '<path-to-arxiv-v1-data>'
```

On another machine, pass that machine's archive path to `--source`. The exporter reads source records, validates all five 100-day windows, recomputes the Joint audit, checks manuscript values, and writes these documentation exports. It performs no simulation or model calls. It records source stability and hashes; the source archive is read-only during export. The archived JAR hashes identify historical runtime builds. Exact simulation reruns additionally require the corresponding executable or verified source revision, original prompts, full inputs, and model-service setup; an export script is not a pinned release-to-run mapping.

Git attributes retain LF line endings for the exported CSV/JSON files and export script, so their recorded hashes remain stable across supported checkouts.

The exporter uses the Python standard library; reading the archived Zstandard-compressed network requires Python 3.14 or newer, or the `zstandard` package on an earlier Python version.
