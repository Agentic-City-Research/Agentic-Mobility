# Scenarios & data

[Documentation](README.md) · [Getting started](getting-started.md) · [Methods & protocols](methods-and-protocols.md) · [Experiments & evaluation](experiments-and-evaluation.md)

The reference implementation uses an **enriched Sioux Falls car-traffic scenario** with full-day activity chains, facilities, and native MATSim background traffic. This guide describes the distributed files, their provenance, population/capacity settings, and the inputs needed for an extension.

The population is synthetic. The supplied files are research inputs rather than observed individual travel traces or a calibration of current Sioux Falls residents.

## Scenario and data provenance

The project's provenance record identifies the public input lineage as:

> Golan Ben-Dor. *Souix Falls 2014, Car Traffic with Doubled Population (Input for k = 1)*. Mendeley Data, version 3, 2020-01-20. DOI: [10.17632/2332kg48c8.3](https://data.mendeley.com/datasets/2332kg48c8/3).

The underlying enriched-scenario research is Chakirov and Fourie (2014), *Enriched Sioux Falls Scenario with Dynamic and Disaggregate Demand*. See the [MATSim scenario description](https://matsim.org/gallery/sioux-falls/) and the repository's [third-party record](../THIRD_PARTY.md).

The input lineage is documented under CC BY 4.0. Keep author/source attribution, the license link, and modification notes with redistributed derived data. Project source code and Maven Wrapper retain their separately documented licenses; see [LICENSE](../LICENSE), [LICENSES](../LICENSES), and [THIRD_PARTY.md](../THIRD_PARTY.md).

The repository identifies its distributed files by SHA-256. These hashes establish which local files were used; they do not claim a new byte-for-byte verification against a fresh upstream download.

## Distributed files

| File | Role |
| :--- | :--- |
| [`scenarios/SiouxFalls-public/config.xml`](../scenarios/SiouxFalls-public/config.xml) | Current full-day MATSim 2026 configuration. |
| [`Siouxfalls_network_Car.xml.gz`](../scenarios/Souix%20Falls%202014/Siouxfalls_network_Car.xml.gz) | Directed car network. |
| [`Siouxfalls_facilities.xml.gz`](../scenarios/Souix%20Falls%202014/Siouxfalls_facilities.xml.gz) | Activity facilities and their spatial/activity information. |
| [`Siouxfalls_population_100pct.xml.gz`](../scenarios/Souix%20Falls%202014/Siouxfalls_population_100pct.xml.gz) | Restored original-demand population. |
| [`Siouxfalls_population_200pct.xml.gz`](../scenarios/Souix%20Falls%202014/Siouxfalls_population_200pct.xml.gz) | Published doubled-demand population lineage. |
| [`Siouxfalls_config.xml.orig`](../scenarios/Souix%20Falls%202014/Siouxfalls_config.xml.orig) | Historical configuration reference; use the current public configuration for the current interface. |
| [`fixed-tolls-*.json`](../scenarios/SiouxFalls-public) | Project-authored example toll policies. |
| [`replay-models.example.json`](../scenarios/SiouxFalls-public/replay-models.example.json) | Example service definitions for saved-context replay. |

The source directory is spelled **`Souix Falls 2014`**. Preserve this exact directory name and its spaces when using the supplied configuration. JSON fixed-policy files and replay settings are project experiment inputs, not upstream validated policy recommendations.

### Input inventory

Counts below were read directly from the distributed compressed XML files on 2026-10-16. They describe input structure, not traffic outcomes.

| Input component | Count |
| :--- | ---: |
| Network nodes | 282 |
| Directed network links | 334 |
| Facilities | 24,718 |
| People in the 100pct population | 84,110 |
| People in the 200pct population | 168,220 |

File sizes and complete SHA-256 values are maintained in [THIRD_PARTY.md](../THIRD_PARTY.md#实际输入校验值).

The 100pct population was produced by removing verified `_new` duplicate people from the doubled-demand file. [`restore_original_population.py`](../scripts/restore_original_population.py) checks pairing and retained person content before writing. This restoration is different from a random population sample.

## Current execution configuration

| Setting | Supplied configuration |
| :--- | :--- |
| MATSim version | 2026.0. |
| Coordinate-system label | `Atlantis`; do not assume a geographic latitude/longitude system. |
| QSim interval | 00:00–30:00, or `[0,108000)` seconds for charging. |
| Road mode | `car`. |
| Access/egress | Facility-to-road access and egress walking are represented by the configured routing mechanism. |
| Population | Full-day activity chains, including non-target and return trips. |
| Native behavior | Plan selection, rerouting, and time mutation in the main `hybrid` configuration. |
| Work activity reference | `latestStartTime` is 09:00 in the public scoring configuration. |

The code's `Morning` class names refer to target-trip selection history. Execution remains full-day. An analysis or target-selection time window does not cut off the rest of the population's activities or the QSim horizon.

The current LLM action space is route plus departure time for eligible car trips. Access/egress walks are part of trip execution, not an implemented LLM transport-mode choice feature.

Configuration values can be overridden by CLI settings. In particular, use explicit `--days` rather than relying on the XML's long default iteration range. Network/facilities/plans paths in the supplied XML are resolved relative to that configuration; commands in these guides run from the repository root.

## Demand levels

`--demand 100` selects the restored 84,110-person input. `--demand 200` selects the 168,220-person doubled-demand input. The CLI default is **200**, while the documentation's common examples explicitly use **100**.

Demand level selects an input **before** sampling. Thus a 10% sample of demand 200 is not the same traffic setup as a 10% sample of demand 100.

`--population PATH` explicitly overrides the population file. In that case, retain its real file path and hash: a demand label alone is insufficient to identify the population used.

## Sampling and capacity

For `--sample-rate k`, with `0 < k <= 1`:

$$
N_{\mathrm{simulated}}=\lfloor N_{\mathrm{input}}k\rfloor,
\qquad f_{\mathrm{flow}}=k,
\qquad f_{\mathrm{storage}}=k^{0.75}.
$$

The QSim factors are absolute factors of the original road capacities. They replace those factors rather than multiplying an already sampled configuration. Native network geometry is preserved.

| Configuration | Input people | Simulated people | Flow factor | Storage factor |
| :--- | ---: | ---: | ---: | ---: |
| Demand 100, `--sample-rate 1` | 84,110 | 84,110 | 1 | 1 |
| Demand 100, `--sample-rate 0.1` | 84,110 | 8,411 | 0.1 | Approximately 0.177828 |
| Demand 200, `--sample-rate 0.1` | 168,220 | 16,822 | 0.1 | Approximately 0.177828 |

These are configuration calculations. They do not establish that sampled and full-population traffic outcomes are equivalent.

`--population-seed` controls deterministic person sampling and defaults to 4711. It also supplies default cohort-control, profile, and information-assignment seeds where the interface uses that common seed. `--seed` controls MATSim randomness separately.

`--sample-persons` is a diagnostic alternative that reduces the population without applying this capacity formula. It is mutually exclusive with `--sample-rate`. Use diagnostic counts for setup checks, and document any research use as a different loading condition.

If neither sampling option is supplied, the loaded population and configured QSim capacity factors are retained. Record the effective values in the run manifest.

## Target trips, profiles, and reusable actions

Population sampling, target eligibility, and LLM control are separate selection steps:

1. Load the chosen population and apply any sample.
2. Select eligible car trips from original plans, by default using destination activity planned start before 09:00.
3. Select controlled people from the eligible cohort using `--llm-agents`.
4. Prepare fixed route/time choices, profiles, and information assignments.

The original destination activity start determines default eligibility. The actual later start or arrival cannot change cohort membership. A controlled person can contribute multiple target trips.

For route-candidate filtering, departure-grid defaults, and information boundaries, see [Methods & protocols](methods-and-protocols.md#joint-route-and-departure-time-actions).

Preparation exports data used to identify or reuse these inputs:

| Export | Contents and reuse |
| :--- | :--- |
| `cohort.jsonl.gz` | Stable trip/person identity and fixed trip conditions. |
| `candidates.jsonl.gz` | Frozen per-OD route IDs and paths; reusable with `--candidate-file`. |
| `candidate-quality.jsonl.gz` | Search, count, and filtering diagnostics. |
| `profiles.jsonl.gz` | Fixed profiles; reusable with `--profile-file` when all controlled people are covered. |
| `information-groups.jsonl.gz` | Stable person-level visibility assignments. |

The default `legacy` profile traits are synthetic experimental assumptions. The input population does not thereby provide measured income, time value, or lateness tolerance for each person. Unknown attributes remain unknown. Use `--personal-profiles input` when excluding synthetic traits, or document a separately sourced attribute mapping before using new preference data.

## Units and time conventions

| Quantity | Convention |
| :--- | :--- |
| Day index | `day` is an iteration index; the standard first iteration is 0. |
| Human-readable day | `day - firstIteration + 1`; default policy day 21 is iteration 20. |
| Daily time and duration | Seconds, with execution extending beyond 24:00 to 30:00. |
| Distance | Metres. |
| Toll amount | Scenario monetary units per actual priced-link entry; not an asserted national currency. |
| Toll interval | Left-inclusive, right-exclusive: `[start,end)`. |
| Missing value | `null` or absent information as documented by its schema; not zero. |

Original, selected, and actual departure times have distinct meanings. A fixed planned start or an arrival deadline is a schedule reference, not a promise that traffic execution will meet it.

## Fixed toll files

The fixed-file interface accepts a JSON object containing a `rules` array. For example, the distributed [`75_1` policy](../scenarios/SiouxFalls-public/fixed-tolls-75_1-1unit.json) uses this form:

```json
{
  "rules": [
    {
      "link_id": "75_1",
      "start": 28800,
      "end": 32400,
      "amount": 1.0
    }
  ]
}
```

This means one scenario monetary unit for each entry onto directed link `75_1` from 08:00 through, but not including, 09:00. Validate every link against the selected network.

Rules require existing car links, finite nonnegative amounts, and time windows inside QSim. Windows on the same link must not overlap. Multiple priced links accumulate actual entry charges.

`--fixed-toll-free-days 20` activates the file policy after 20 unpriced days. `0` activates it from the first day. The policy repeats unchanged once active. The application stores a normalized `morning/fixed-toll-policy.json` and provenance in the manifest.

Fixed-policy windows are independent of manager constraints. A `basic` manager must return one all-day rule per priced link, while a fixed file can define narrower non-overlapping windows. Fixed files and `--manager` cannot be enabled together.

See the [fixed-policy notes](../scenarios/SiouxFalls-public/fixed-tolls.md) for the other distributed examples.

## Check input identity

Use the recorded hashes before preparing matched runs. For example, from PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'scenarios/Souix Falls 2014/Siouxfalls_network_Car.xml.gz'
Get-FileHash -Algorithm SHA256 -LiteralPath 'scenarios/Souix Falls 2014/Siouxfalls_population_100pct.xml.gz'
```

Bash:

```bash
sha256sum 'scenarios/Souix Falls 2014/Siouxfalls_network_Car.xml.gz'
sha256sum 'scenarios/Souix Falls 2014/Siouxfalls_population_100pct.xml.gz'
```

Compare these with [THIRD_PARTY.md](../THIRD_PARTY.md) and the actual run manifest. Preserve imported candidate/profile and policy hashes as well as the base scenario hashes. The same route ID or population count is not sufficient to identify identical inputs.

## Extending a scenario

A replacement scenario needs a consistent network, facilities, population plans, scoring configuration, and explicit provenance. Preparing a new map is only one part of this work.

| Area | Required preparation |
| :--- | :--- |
| Network and facilities | Valid directed car links, units, capacities, coordinate convention, connected activity access, and consistent identifiers. |
| Population | Full-day plans with valid links/facilities, supported activity types, and supplied timing sufficient for the chosen target-selection rule. |
| Scoring and native behavior | Activity schedule parameters, monetary coefficients, plan/replanning settings, and their intended interpretation. |
| Target actions | Regenerate or validate route pools and departure grids against the new inputs; do not reuse incompatible route IDs or paths. |
| Attributes and information | Explicit attribute mapping and units, documented synthetic assumptions, and defined information-group scope. |
| Policies | Link IDs and windows valid for the new network and horizon; existing Sioux Falls toll files are scenario-specific. |
| Provenance | Sources, licensing/attribution, conversion steps, input hashes, and the code revision that prepares them. |

Begin with `--validate-only`, inspect prepared identities/candidates, and complete a small native execution check before adding live LLM roles. The main action protocol remains car route/time choice unless corresponding code and prompt changes are implemented. Multimodal choice or connections to other urban systems require an explicit protocol extension.
