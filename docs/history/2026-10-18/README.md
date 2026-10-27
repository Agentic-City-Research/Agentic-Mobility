# Historical experiments recorded on 2026-10-18

[Documentation](../../README.md) · [Current manuscript results](../../reported-experiments.md) · [Data inventory](../../data/README.md)

The following record describes earlier homepage comparisons. The traveler and manager experiments used different role configurations and durations against their own native MATSim baselines. Their settings, runtime hashes, and percentage changes apply only to these historical comparisons; they are not the current manuscript's seed-268 five-configuration results.

## Comparison settings

| Setting | LLM travelers | LLM manager |
| :--- | :--- | :--- |
| Intervention | Joint route and departure-time choices for selected target trips | Congestion pricing for native travelers |
| Baseline | Native MATSim; no manager or tolls | Native MATSim; no manager or tolls |
| Observation window | Days 0–60: 61 completed days in both conditions | Days 0–499: 500 completed days in both conditions |
| Headline summary window | Days 0–60 | Charged period: Days 20–499 |
| Main simulation seed | 9261 in both conditions | 9261 in both conditions |
| Additional runs | Native seeds 268 and 4711, for descriptive context | None included in the displayed comparison |
| Population seed | 6021 | 6021 |
| Simulated population | 16,822 people | 16,822 people |
| Flow capacity factor | 0.2 | 0.2 |
| Storage capacity factor | 0.29906975624424414 | Approximately 0.29907 |
| MATSim version recorded in manifests | 2026.0 | 2026.0 |
| Recorded model ID | `qwen3.5-9b` | `deepseek-flash` |
| Pricing | Disabled in both conditions | All-day tolling starts on Day 20; policy reviews every 30 days |
| QSim stuckTime | See each archived configuration | 50 seconds in both conditions |

Within each comparison, raw population, network and facilities inputs, sampled person identities, capacity factors and recorded native strategy settings were checked. Native strategy weights are ChangeExpBeta 0.7, ReRoute 0.1 and TimeAllocationMutator 0.1. Actual configuration records take precedence over descriptive folder names.

The model names above are the identifiers recorded by the runs. Detailed recorded request parameters and artifact hashes are included in the [metadata file](data/reported-experiment-metadata.json). Aggregate-file hashes describe UTF-8 files with LF line endings; normalize CRLF checkout line endings to LF before comparison. An omitted sampling parameter is not assumed to be zero or to match between services.

## Runtime artifact identifiers

| Runtime | Output schema | JAR SHA-256 |
| :--- | ---: | :--- |
| LLM traveler run | 23 | `d840f208dc618cb4238df09b36b2b4bd386d663880b0dee1f293aeaf367b11c3` |
| Native baselines | 18 | `6eb3c58b1f16bdbad31313d1ec12e8421e29d8fa0eb609c533f7518343aea958` |
| LLM manager run | 23 | `c44eabe2f082be26a26802281357ba9e6d54fdb55f1daf2a47913d9860f75102` |

The archived manifests identify these runtime binaries by hash but do not record a source Git commit. The current repository commit must therefore not be treated as the exact source revision of every displayed run. Input configuration hashes also differ within both comparisons. Matching selected manifest fields does not establish full code equivalence; the impact of build differences has not been isolated.

## Traveler outcomes

The target cohort contains 11,793 trips/day, including 11,332 work trips/day. Full-day car travel time covers all 33,644 car legs/day. All these trips and car legs completed on each of the selected days in the four displayed runs. Incomplete LLM Day 61 and baseline Days 61–499 are excluded from this comparison.

- **Target-trip car travel time:** elapsed time from car departure to car arrival for the target trip, including queueing and applicable pre-traffic waiting.
- **Door-to-door travel time:** elapsed time for the target origin-to-destination trip, including access and egress components.
- **P95:** compute the 95th percentile separately for each day, then average those 61 daily values. It is not a percentile of pooled multi-day trips.
- **Work lateness:** percentage of the fixed 11,332 daily work trips arriving after their recorded deadline. Changes in this percentage are reported in percentage points.
- **Total daily car travel time:** sum elapsed car-leg times across the full population, expressed in person-hours/day.

The earlier homepage values are arithmetic means of daily metrics over Days 0–60. Relative change is `100 × (LLM mean − native mean) / native mean`, calculated before rounding. For lateness, subtract the two mean percentages directly.

The LLM decision space uses 1–4 frozen route candidates and a 15-minute departure grid from 90 minutes before to 30 minutes after the original departure, subject to activity/day boundaries. Native MATSim is not constrained to the same candidate pool or departure grid. LLM decisions are active on Day 0, so Day 0 is not an untreated reference day.

Travel-time and punctuality differences accompany changed departure times. They do not quantify the full schedule cost of earlier departures or arrivals, nor do population means imply that every traveler benefits. The comparison describes the complete configured decision policies; individual design components have not been isolated.

The additional native seeds 268 and 4711 provide descriptive context rather than confidence bounds.

## Manager outcomes

The manager acts on native travelers; no traveler trips are controlled by an LLM in this experiment. Days 0–19 have no tolls. A scheduled policy review can change prices or hold the existing policy; a review date alone does not identify a policy effect.

- **Network delay:** delay summed over completed full-link traversals. It excludes pre-traffic waiting and partial-link movement.
- **Morning link delay:** completed full-link delay grouped by link-entry times in `[07:00, 09:00)`. It does not measure only delay occurring within those clock hours.
- **Total daily car travel time:** car departure-to-arrival time, including pre-traffic waiting.

Positive gain means improvement: `100 × (baseline − manager) / baseline`. Historical headline gains use the ratio of totals across Days 20–499 at seed 9261, rather than the mean of daily percentages. This gives approximately +0.29% for total network delay, −0.26% for morning link delay and +3.52% for total car travel time. These gains do not describe the current manuscript's seed-268 Manager configuration.

The corridor CSV groups the 334 directed car links into 76 topological corridors: maximal directed chains through intermediate nodes with one car in-link and one car out-link. Its delay contrast is `baseline mean daily delay − manager mean daily delay` over Days 20–499, in vehicle-hours/day. Toll exposure identifies corridors containing a tolled link during that period. Corridor changes can reflect routing and traffic-volume changes as well as delay per traversal; they do not isolate the effect of an individual toll.

## Aggregate data and figure reproduction

| File | Contents |
| :--- | :--- |
| [Traveler daily comparison](data/daily-comparison.csv) | Days 0–60 for one LLM run and three native runs; outcome and expanded behavior metrics |
| [Manager daily comparison](data/manager-daily-comparison.csv) | Days 0–499 for the paired manager/native runs |
| [Manager corridor comparison](data/manager-corridor-comparison.csv) | Mean daily delays and toll exposure for the 76 corridors over Days 20–499 |
| [Manager policy schedule](data/manager-policy-schedule.csv) | Policy review days, actions and recorded price changes |
| [Experiment metadata](data/reported-experiment-metadata.json) | Recorded model parameters, runtime/input hashes and aggregate-file hashes |

The retained `daily-comparison.csv` includes all 244 keyed rows and all eight fields of the former `llm-traveler-daily-comparison.csv`, plus additional behavior metrics. The duplicate export was removed after exact comparison by `condition`, `seed`, and `day`; its recorded hash remains in the metadata as provenance.

The CSVs contain aggregate statistics, without individual traveler records or model conversations. Percentages should be recomputed from unrounded CSV values. For the manager summary, select `20 <= day <= 499` and compute `100 × (sum(baseline) − sum(manager)) / sum(baseline)` for the relevant pair of columns.

To redraw the historical 61-day traveler figure from its committed CSV, install Matplotlib in your Python environment and run from the repository root:

```bash
python scripts/plot_readme_traveler_results.py
```

The script exports PNG and editable SVG into `docs/history/2026-10-18/figures/` when run; generated figures are not bundled in this archive. It uses the recorded daily values and performs no traffic simulation or model calls. These materials support recalculating summaries and redrawing the traveler figure. Rerunning the original traffic experiments additionally requires the archived runtime, full inputs, run configuration and model service; those runtime binaries and raw logs are not included here.

## Interpretation and next comparisons

Each setting currently includes one LLM seed. Extra native seeds provide descriptive context but do not replace LLM repetitions. Consecutive simulation days are dependent observations, so they are not treated as independent experimental replicates.

These historical traveler and manager experiments use different interventions, durations and metric scopes. Their percentage changes do not establish a direct ranking between the interventions. They did not evaluate joint LLM traveler–manager behavior or isolate information-access effects. The current manuscript includes the Joint configuration, while mechanism isolation and robustness still require repeated seeds and controlled, documented configurations.
