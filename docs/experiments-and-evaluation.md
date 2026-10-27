# Experiments & evaluation

[Documentation](README.md) · [Getting started](getting-started.md) · [Methods & protocols](methods-and-protocols.md) · [Scenarios & data](scenarios-and-data.md)

This guide covers **experiment configuration, supported run modes, and reproducibility records**. [Reported experiments](reported-experiments.md) documents the current manuscript's five seed-268 configurations over Days0–99, with historical seed-9261 comparisons listed separately. The [data inventory](data/README.md) distinguishes manuscript summaries from earlier aggregate exports.

Build the project and check a small native run with [Getting started](getting-started.md) before using these templates. Commands below describe configurations; they are not reports of completed experiments.

## Supported configurations

| Configuration | Traveler control | Pricing | Model service needed? |
| :--- | :--- | :--- | :--- |
| Native baseline | Native full-day MATSim behavior; zero controlled people. | No congestion tolls. | No. |
| Constrained joint MNL | `mnl-joint`: offline joint route/time choice for selected target trips within fixed candidate sets; native background remains. | No congestion tolls in the manuscript condition. | No. |
| Route-only MNL | `mnl`: selects a route; each controlled target retains its original planned departure. | Optional pricing. | No, unless the manager is enabled. |
| LLM travelers | LLM route/time choices for selected target trips; native background remains. | No congestion tolls. | Yes: traveler choices and reflection. |
| Native travelers + manager | Native travelers. | Daily manager memory and scheduled policy updates. | Yes, from the first day. |
| LLM travelers + manager | Selected LLM target trips and native background. | The same manager mechanism. | Yes: both roles. |
| Fixed tolls | Native travelers in the example; a traveler overlay can be configured separately. | A file-defined policy repeated after its free period. | No for the native example. |

These configurations all use `--mode hybrid`. The manuscript evaluates the first five rows; fixed tolls are an additional supported intervention. All use MATSim for environmental execution.

The CLI also provides `reference` and `fixed-routes` modes for auxiliary native/frozen-route configurations. They change the execution assumptions and should be labeled separately. Live LLM choices and manager pricing require `hybrid`. See the [operating guide](usage-guide.md) for auxiliary settings.

## Manuscript configuration mapping

The [active manuscript appendix](../paper/AgenticMobility_arXiv/appendices/app_1.tex) specifies the following recorded settings. These differ from the smaller integration templates below.

| Setting | Manuscript demonstration |
| :--- | :--- |
| Duration and simulation seed | 100 days, log Days0–99, `--seed 268`; one run per configuration |
| Population | Restored demand 100, 84,110 input people, `--sample-rate 0.2`, 16,822 simulated people |
| Population and assignment seed | `--population-seed 6021`; sampling, profiles, information groups, and controlled roster use 6021 |
| Capacity factors | Flow 0.2; storage `0.2^0.75`, approximately 0.2991 |
| Targets | Original destination-activity starts in `[0,32400)`; 11,793 fixed trips by 11,793 people across 2,877 OD pairs |
| Work-arrival denominator | 11,332 fixed work targets with deadlines |
| Candidate-based conditions | MNL, LLM Traveler, and Joint share fixed route/departure options; 2,780 single-route and 9,013 multi-route targets |
| Information in Joint | 5,897 informed and 5,896 uninformed travelers, assigned by person |
| Pricing opportunities | Log Days20, 50, 80; simulation days 21, 51, 81 |
| Positive charging | Manager from log Day20; Joint from log Day50, after retaining zero charges at Day20 |
| Native innovation cutoff | Log Day90; existing-plan selection continues |
| Memory and validation | Three-day personal facts, 500-character summary limit, three total output attempts; semantic review disabled |

| Manuscript condition | Target-control arguments in the current interface |
| :--- | :--- |
| MATSim | `--policy mnl --llm-agents 0` |
| MNL | `--policy mnl-joint --llm-agents all --mnl-parameters config/baselines/mnl-joint.example.json --mnl-choice-seed 268` |
| LLM Traveler | `--policy llm --llm-agents all` |
| MATSim + Manager | `--policy mnl --llm-agents 0 --manager --manager-profile basic` |
| LLM Traveler + Manager (Joint) | `--policy llm --llm-agents all --manager --manager-profile basic` |

These argument mappings identify roles, not complete historical rerun commands. The joint MNL implementation and parameter file were recovered from the local baseline checkout whose runtime JAR matches the archived MNL run's hash; an exact rerun still requires the corresponding runtime, inputs, candidates, and configuration. See [joint MNL](joint-mnl-baseline.md). Its homogeneous coefficients are uncalibrated. `--policy mnl` is the separate legacy route-only baseline; with `--llm-agents 0`, all choices remain native. Fixed-file toll examples are outside the manuscript's five-condition comparison.

The installation check uses 100 people and two days. The templates below instead use 10% sampling, seeds 4711, and three controlled people for integration checks. Neither is the manuscript experiment. A matching population size obtained from demand 200 at 10% would also have different people and capacity factors from the manuscript's demand 100 at 20%.

## Keep population and control settings distinct

| Setting | Meaning |
| :--- | :--- |
| `--demand 100` or `200` | Select the restored original-demand or published doubled-demand input before sampling. CLI default is `200`; examples here explicitly use `100`. |
| `--sample-rate k` | Retain `floor(N × k)` people and apply the documented capacity scaling. |
| `--sample-persons n` | Diagnostic subsampling without capacity scaling. Do not combine it with `--sample-rate`. |
| `--llm-agents 0`, a number, or `all` | Select controlled people from the eligible target cohort. `all` means all eligible target people, not every trip in the population. |
| Actual controlled target trips | The number of selected eligible trips belonging to controlled people. Use this count when estimating traveler calls. |

The shared examples use demand 100 with sample rate 0.1: **8,411 simulated people** from the 84,110-person input. This is an input-size calculation, not an experimental finding. Three controlled people are a small integration configuration; a study's control size must be specified separately.

## Bash command templates

Run from the repository root. Configure your model-service environment first if either LLM role is enabled; see [Getting started](getting-started.md#5-connect-an-existing-model-service).

### Shared settings

```bash
MOBILITY_JAR='matsim-example-project-0.0.1-SNAPSHOT.jar'
MOBILITY_APP='org.matsim.agentic.MorningExperiment'

MOBILITY_COMMON=(
  --config scenarios/SiouxFalls-public/config.xml
  --mode hybrid --demand 100 --sample-rate 0.1
  --population-seed 4711 --seed 4711 --days 100
  --cohort-selection planned-arrival --planned-arrival-before 32400
  --native-time-mutation
  --thinking omit --response-format omit --max-requests 3000
)

MOBILITY_TRAVELER=(
  --policy llm --llm-agents 3 --concurrency 3
  --information-scope controlled --informed-share 0.5
  --history-window 3 --max-output-tokens 1024 --reason-max-chars 120
  --memory-max-output-tokens 1024 --memory-max-chars 500
  --repair-attempts 3
)

MOBILITY_MANAGER=(
  --manager --manager-profile basic --manager-update-interval 30
  --manager-max-output-tokens 8192
  --manager-memory-max-output-tokens 2048
)
```

The 3,000-call budget is an example. Inspect the prepared trip count and calculate the budget for your settings before making live calls. Provider-supported sampling parameters and any inherited environment variables must also be recorded.

### Select a configuration

```bash
# Native baseline: no model calls.
java -Xmx8g -cp "$MOBILITY_JAR" "$MOBILITY_APP" "${MOBILITY_COMMON[@]}" \
  --policy mnl --llm-agents 0 --output output/native-baseline

# Small constrained-MNL integration run: no model calls.
java -Xmx8g -cp "$MOBILITY_JAR" "$MOBILITY_APP" "${MOBILITY_COMMON[@]}" \
  --policy mnl-joint --llm-agents 3 \
  --mnl-parameters config/baselines/mnl-joint.example.json --output output/mnl-targets

# LLM travelers, without pricing.
java -Xmx8g -cp "$MOBILITY_JAR" "$MOBILITY_APP" "${MOBILITY_COMMON[@]}" \
  "${MOBILITY_TRAVELER[@]}" --output output/llm-travelers

# Native travelers with an LLM manager.
java -Xmx8g -cp "$MOBILITY_JAR" "$MOBILITY_APP" "${MOBILITY_COMMON[@]}" \
  --policy mnl --llm-agents 0 "${MOBILITY_MANAGER[@]}" \
  --output output/native-manager

# LLM travelers with an LLM manager.
java -Xmx8g -cp "$MOBILITY_JAR" "$MOBILITY_APP" "${MOBILITY_COMMON[@]}" \
  "${MOBILITY_TRAVELER[@]}" "${MOBILITY_MANAGER[@]}" \
  --output output/llm-travelers-manager

# Native travelers with a fixed policy file.
java -Xmx8g -cp "$MOBILITY_JAR" "$MOBILITY_APP" "${MOBILITY_COMMON[@]}" \
  --policy mnl --llm-agents 0 \
  --fixed-toll-policy scenarios/SiouxFalls-public/fixed-tolls-75_1-1unit.json \
  --fixed-toll-free-days 20 --output output/native-fixed-tolls
```

Choose the configuration you need. Each command creates a separate run. Add `--validate-only` and use a separate `--output` to prepare its inputs without traffic or model calls.

## Windows PowerShell command templates

These are the equivalents of the Bash arrays above. Set the model-service environment in this PowerShell session before a live LLM configuration.

```powershell
$MobilityJar = 'matsim-example-project-0.0.1-SNAPSHOT.jar'
$MobilityApp = 'org.matsim.agentic.MorningExperiment'

$MobilityCommon = @(
  '--config', 'scenarios/SiouxFalls-public/config.xml',
  '--mode', 'hybrid', '--demand', '100', '--sample-rate', '0.1',
  '--population-seed', '4711', '--seed', '4711', '--days', '100',
  '--cohort-selection', 'planned-arrival', '--planned-arrival-before', '32400',
  '--native-time-mutation',
  '--thinking', 'omit', '--response-format', 'omit', '--max-requests', '3000'
)

$MobilityTraveler = @(
  '--policy', 'llm', '--llm-agents', '3', '--concurrency', '3',
  '--information-scope', 'controlled', '--informed-share', '0.5',
  '--history-window', '3', '--max-output-tokens', '1024', '--reason-max-chars', '120',
  '--memory-max-output-tokens', '1024', '--memory-max-chars', '500',
  '--repair-attempts', '3'
)

$MobilityManager = @(
  '--manager', '--manager-profile', 'basic', '--manager-update-interval', '30',
  '--manager-max-output-tokens', '8192',
  '--manager-memory-max-output-tokens', '2048'
)

# Native baseline.
java -Xmx8g -cp $MobilityJar $MobilityApp @MobilityCommon `
  --policy mnl --llm-agents 0 --output output/native-baseline

# Small constrained-MNL integration run.
java -Xmx8g -cp $MobilityJar $MobilityApp @MobilityCommon `
  --policy mnl-joint --llm-agents 3 `
  --mnl-parameters config/baselines/mnl-joint.example.json --output output/mnl-targets

# LLM travelers.
java -Xmx8g -cp $MobilityJar $MobilityApp @MobilityCommon `
  @MobilityTraveler --output output/llm-travelers

# Native travelers with an LLM manager.
java -Xmx8g -cp $MobilityJar $MobilityApp @MobilityCommon `
  --policy mnl --llm-agents 0 @MobilityManager --output output/native-manager

# LLM travelers with an LLM manager.
java -Xmx8g -cp $MobilityJar $MobilityApp @MobilityCommon `
  @MobilityTraveler @MobilityManager --output output/llm-travelers-manager

# Native travelers with a fixed policy file.
java -Xmx8g -cp $MobilityJar $MobilityApp @MobilityCommon `
  --policy mnl --llm-agents 0 `
  --fixed-toll-policy scenarios/SiouxFalls-public/fixed-tolls-75_1-1unit.json `
  --fixed-toll-free-days 20 --output output/native-fixed-tolls
```

`-Xmx8g` is the example Java heap allocation, not a measured minimum. Resource use depends on population, run length, retained records, and enabled tasks.

## Information access and policy settings

### Information access

The default `--informed-share 0.5 --information-scope controlled` creates fixed informed and uninformed groups among controlled people. Use `--information-seed` to specify an independent assignment seed; otherwise it defaults to the population seed.

For separate all-informed or all-uninformed configurations, replace the informed-share value with `1` or `0`. Preserve the same population, controlled people, profiles, route/time candidates, and other settings. Record whether you use groups within one run or separate runs. People still share the road environment within each run.

Without congestion charging, the run explicitly states that charging is disabled. An information-access configuration intended to study unknown current tolls must therefore also specify the pricing intervention it uses.

### Manager policy schedule

The first 20 days are toll-free. With `--manager-update-interval 30`, default policy opportunities are days 21, 51, and 81 in a 100-day run. Manager reflection starts on day 1. A run shorter than 21 days can exercise manager memory but cannot include a scheduled manager policy intervention.

Those are one-based simulation days, equivalent to log Days20, 50, and 80. A policy decision can retain zero or unchanged charges. In the manuscript, positive charging begins on log Day20 for Manager and Day50 for Joint; the two conditions do not apply the same intervention history.

`--manager-profile basic` is the default autonomous profile. The `controlled` profile adds restrictions and automatic intervention mechanisms; report that profile as a different condition. `--fixed-toll-free-days` applies to fixed-policy files, not the manager's initial free period.

### Fixed policies

The supplied `fixed-tolls-75_1-1unit.json` example charges one scenario monetary unit per entry during **08:00–09:00**. Manager-generated policies must cover the full execution day. The example file and manager therefore differ in time coverage as well as policy mechanism. Define and document the intended coverage when setting up a comparison.

`--fixed-toll-policy` and `--manager` are mutually exclusive. File rules are loaded before simulation and are not reloaded if the source file is edited during a run. See [fixed-policy data](scenarios-and-data.md#fixed-toll-files).

### Native background behavior

Native time mutation is enabled in the shared examples. To use fixed original native activity ends in `hybrid`, replace `--native-time-mutation` with `--no-native-time-mutation` consistently across the intended configurations. Other native route/plan mechanisms remain active. Delays can still shift actual departures.

Record innovative-strategy settings and their effective disable timing, including any `--innovation-stop-day` override and the XML innovation settings.

## Reuse fixed inputs

For matched configurations, preserve demand, population input, sample rate, population seed, cohort rule, and native behavior settings. Use the same action sets and profiles where required:

```text
--candidate-file PATH_TO_PREPARED_CANDIDATES.jsonl.gz
--profile-file PATH_TO_PREPARED_PROFILES.jsonl.gz
```

These lines illustrate additional arguments; replace the paths with real exported files. Prepare profiles using a control set containing every person whose profile will be imported. A zero-controlled-person baseline need not export profiles for a later LLM cohort. Profile imports require coverage of all controlled people and the compatible `legacy` profile mode; missing people or conflicting profiles fail rather than being filled silently.

Keep the same population seed to select the same controlled people when changing other settings. Reused route IDs must refer to the same exported paths, not independently generated labels with similar names.

## Request budgets

Let `n` be the number of **controlled target trips**, `D` the number of simulation days, and `U` the number of scheduled policy opportunities. For a default `basic` manager run starting on simulation day 1:

$$
U=\begin{cases}
0 & D\leq20,\\
1+\left\lfloor\dfrac{D-21}{I}\right\rfloor & D\geq21,
\end{cases}
$$

where `I` is `--manager-update-interval`, default 30.

| Enabled roles | Base client calls before repairs |
| :--- | :--- |
| Native baseline or native fixed tolls | 0 |
| Constrained MNL without a manager | 0 |
| LLM travelers | `2 × n × D` for choices and reflections. |
| Manager only | `D + U` for daily reflection and policy opportunities. |
| LLM travelers + manager | `2 × n × D + D + U`. |

Optional traveler semantic review adds up to `2 × n × D` base calls. Output repairs require additional allowance. A 100-day default manager-only configuration has 103 base calls; this is a schedule calculation, not observed API usage.

At the manuscript's 11,793 targets and 100 days, the base traveler workload is **2,358,600 calls**; Joint adds 103 base manager calls, for **2,358,703 before retries and repairs**. The manuscript records 2,362,964 calls for Traveler, 109 for Manager, and 2,364,090 for Joint. These observed totals differ from base budget estimates; see the [audit and workload](assets/data/seed268/seed268-experiments.md#execution-validation-and-model-call-scope).

`--max-requests` is a shared client-call budget. It includes output repairs and optional reviews, while HTTP-layer recovery can produce additional requests. It is neither a token budget nor a billing cap. The startup check rejects a finite budget below the base requirement; actual usage comes from the call records.

## Manuscript measurement and interpretation

The [measurement appendix](../paper/AgenticMobility_arXiv/appendices/app_1.tex) defines distinct populations and event boundaries:

| Measure | Scope and aggregation |
| :--- | :--- |
| Total daily car time | All car-departure-to-car-arrival durations, including waiting before network entry; complete total is undefined if any planned car leg is incomplete |
| Mean daily car time per person | `60 × total car person-hours / 16,822`, in minutes/person |
| Mean target-trip car time | Completed controlled target trips; 11,793 targets complete each day in the reported runs |
| Work lateness | Late completed work targets divided by the fixed 11,332-work-target cohort; on-time, late, and noncompleted outcomes exhaust the cohort |
| Mean daily P95 | Compute each day's target-time 95th percentile, then average those daily values; not a pooled percentile or the mean of the slowest 5% |
| Mean positive early arrival | Average `max(deadline − arrival, 0)` across all work targets, including zeros |
| Individual time savings | Compare each target person's mean time over the same labeled window; not independent run replications |
| Completed-link delay | Aggregate original link intervals to full days; excludes waiting before network entry and differs from full-trip time |

Choice changes use **selected actions**, with realized departures retained separately. Route switching compares complete ordered link sequences, not candidate labels. Overall rates use all 11,793 targets; candidate-based multi-route rates use 9,013 targets and exclude the 2,780 single-route cases. Departure adjustment compares selected departure times. Both rates average the **99 adjacent-day transitions** ending on log Days1–99. Departure offsets remain anchored to the original plan, not yesterday's choice.

Aggregate route-flow turnover is `sum(abs(n[o,r,d] − n[o,r,d−1])) / (2 × 11,793)`, where counts group targets by OD and complete route. Offsetting individual switches can leave route counts unchanged, so switching and net flow turnover measure different changes. The native innovation cutoff is log Day90; cutoff diagnostics treat Day90 separately and use Days91–99 for the post-cutoff window. It does not disable LLM or MNL target choices.

Outcome summaries use arithmetic daily means over Days0–99; relative changes are ratios of unrounded window means minus one, and lateness differences are percentage points. Time-series smoothing uses trailing seven-day means. Target/other-trip and waiting/post-entry decompositions each recover the same all-car total and must not be added together. Target payments and full-day personal payments also have different trip scopes.

One run per configuration, different executable builds, and uncalibrated MNL coefficients limit the comparison to recorded outputs. Pre-charging differences prevent attributing the Joint–Traveler contrast to the manager alone. Earlier departures and arrivals are not monetized welfare gains. Current action validation and sampled information-access checks do not establish semantic correctness of all reasons/memories, behavioral validity, or causal mechanisms. The [100-day audit](assets/data/seed268/seed268-experiments.md#execution-validation-and-model-call-scope) records these scopes separately.

## Run records

The application prints the reserved output path. Repeated names receive a numeric suffix. MATSim files are stored at the run level; platform records are in `morning/`. Most record streams are UTF-8 `.jsonl.gz`; single JSON documents remain uncompressed. Files depend on the enabled roles and logging settings.

| Record group | Main files | Purpose |
| :--- | :--- | :--- |
| Configuration and status | `manifest.json`, `status.json`, `progress.json` | Effective inputs, settings, versions, progress, and completion. |
| Fixed preparation | `cohort.jsonl.gz`, `profiles.jsonl.gz`, `information-groups.jsonl.gz`, `candidates.jsonl.gz`, `candidate-quality.jsonl.gz` | Identity, control membership, groups, profiles, and frozen actions. |
| Traveler decisions | `decision-contexts.jsonl.gz`, `attempts.jsonl.gz`, `llm-results.jsonl.gz`, `decisions.jsonl.gz` | Permitted context, raw attempts, legal output, and applied choice. |
| Joint MNL | `baseline-parameters.json`, `baseline-contexts.jsonl.gz`, `baseline-choices.jsonl.gz` | Explicit parameter provenance, supplied contexts, joint alternatives and seeded choices. |
| Traveler execution | `executed-route-checks.jsonl.gz`, `trips.jsonl.gz`, `experiences.jsonl.gz` | Plan/action checks, actual trips, and personal feedback. |
| Traveler memory | `memory-contexts.jsonl.gz`, `memory-attempts.jsonl.gz`, `memory-updates.jsonl.gz`, `memory-final.jsonl.gz` | Reflection inputs, responses, and committed summaries. |
| Policy execution | `policies.jsonl.gz`, `policy-applications.jsonl.gz`, `toll-payments.jsonl.gz` | Complete policies, daily effective policy, and actual charges. |
| Manager tasks | `manager-contexts.jsonl.gz`, `manager-attempts.jsonl.gz`, `manager-decisions.jsonl.gz`, `manager-memory-*.jsonl.gz`, `manager-fallbacks.jsonl.gz` | Policy/reflective inputs, responses, updates, and fallbacks. |
| Public traffic archives | `metrics.jsonl.gz`, `link-hours.jsonl.gz` | Saved daily and road-level traffic summaries; see [measurement and interpretation](#manuscript-measurement-and-interpretation) for event boundaries and aggregation. |
| Optional review and usage | `semantic-reviews.jsonl.gz`, `api-usage.json` | Review evidence/status and recorded client/service usage. |

Before treating a traffic run as complete, check `status.json` for `complete=true` and the expected `daysCompleted`, together with exit status and logs. Preparation-only runs instead record `validated=true`, `simulationRun=false`, and `apiCalls=0`.

Keep `day`, `tripId`, and `personId` when relating choices, memory, and execution. Use `policyId`, `policyVersion`, and `policyFingerprint` to relate effective policies to traffic days. Do not collapse multiple target trips into a person-only key or assume that a new version changes prices.

Missing token or cache usage is unknown, not zero. Full archives and final memories are not a complete MATSim checkpoint; automatic continuation of all simulation state from an interrupted run is not supported.

`scripts/analyze_morning_choices.py` requires a completed status, manifest, and the traveler streams required by the recorded role; missing or incomplete streams fail rather than being counted as zero. Its cost-information groups distinguish known-value changes from changes in whether a value is known. If token usage is missing for any inspected call, the overall token total is `null`; reported subtotals and missing-usage counts remain available.

## Run-configuration template

Fill this table from preparation records and the actual run manifest. Requested CLI values and effective counts should both be retained.

| Field | What to record |
| :--- | :--- |
| Run identity | Run label, purpose, exact command, actual output directory, and date. |
| Code and runtime | Git commit, runtime JAR SHA-256, JDK/MATSim versions, and deployment environment. |
| Scenario | Config path/hash and network, facilities, population, and policy-file hashes. |
| Population | Demand input, full input count, sample method/rate, simulated count, capacity factors, and population seed. |
| Target cohort | Selection rule and cutoff, eligible people/trips, requested controlled people, and actual controlled trips. |
| Information | Scope, requested share, actual group counts, and assignment seed. |
| Profiles and candidates | Generation/import settings, file hashes, seeds, candidate filters, departure grid, and presentation order. |
| Native behavior | Execution mode, native time mutation, replanning/innovation settings, and simulation seed. |
| Model service | Provider/runtime and served model ID, generation settings, supported/omitted fields, and service context limit. Exclude credentials. |
| Traveler protocol | Template versions, history window, reason/memory limits, retry count, and review switch. |
| Pricing | None/fixed/manager, manager profile, activation/update schedule, policy limits, and time coverage. |
| Duration and budget | Planned days, base task-call estimate, request cap, concurrency, and per-task output limits. |
| Record availability | Manifest/status paths, enabled logs, observed failure/fallback records, and retained artifacts. |

## Saved-context replay

[`MorningReplay`](../src/main/java/org/matsim/agentic/MorningReplay.java) and the [PowerShell replay runner](../scripts/replay_morning_choices.ps1) can reuse saved traveler contexts across model configurations or candidate presentation orders. The [example model file](../scenarios/SiouxFalls-public/replay-models.example.json) references environment-variable names for credentials.

Replay performs choices on fixed saved inputs. It runs neither QSim nor memory updates. Its outputs therefore belong to a separate workflow from day-to-day traffic experiments. See the [operating guide](usage-guide.md) for replay invocation and output layout.
