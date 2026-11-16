# TinySynthetic: local demonstration scenario

**Synthetic and Uncalibrated.** This scenario is a purpose-built teaching and
validation input. It is not derived from Sioux Falls, is not calibrated, and
does not represent observed traffic or real-world travel behavior.

The scenario is intentionally small: 6 nodes, 12 directed car links, and 10
commuters. It exists so that a new user can inspect a complete MATSim morning
cohort, its route candidates, and two days of native execution without loading
the full Sioux Falls population or calling a model service.

## Topology

```text
H --hs--> S --su--> U --ut--> T --tw--> W
           \--sl--> L --lt-->/
```

Every connection is bidirectional; the reverse directed links are `sh`, `us`,
`tu`, `ls`, `tl`, and `wt`. Coordinates are local artificial meters, not
geographic coordinates.

| Link pair | Length per direction | Free speed |
|---|---:|---:|
| `hs` / `sh` | 100 m | 10 m/s |
| `su` / `us` | 500 m | 10 m/s |
| `ut` / `tu` | 500 m | 10 m/s |
| `sl` / `ls` | 500 m | 8 m/s |
| `lt` / `tl` | 500 m | 8 m/s |
| `tw` / `wt` | 100 m | 10 m/s |

All links allow `car`, have one lane, and carry 1,000 vehicles per hour with a
one-hour capacity period.

## People and the target trip

`p01`–`p10` each travel `Home -> car -> Work -> car -> Home`. The morning home
activity ends between 08:00:00 and 08:04:30; work starts at 08:15:00 and ends
between 17:00:00 and 17:04:30. The final home activity has no start time.

The default target cohort therefore contains the ten morning Home-to-Work car
trips (`p01/0` … `p10/0`). Return trips are still simulated as full-day car
legs, but they are not morning cohort records and do not appear in
`morning/trips.jsonl.gz`.

- Home facility is on `sh` (S to H).
- Work facility is on `tw` (T to W).
- The target OD is `sh>tw`.
- Expected candidates are `sh-hs-su-ut-tw` (120 s) and `sh-hs-sl-lt-tw`
  (145 s), both 1200 m, overlap about 1/6. The lower corridor is a valid
  alternative, but the scenario does not guarantee that native travelers use
  it.

## Build

Use JDK 25. From the repository root:

```bash
sh ./mvnw -B clean package
```

Windows PowerShell:

```powershell
.\mvnw.cmd -B clean package
```

## Preparation check (no simulation)

Bash:

```bash
java -Xmx1g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --config scenarios/TinySynthetic/config.xml --population scenarios/TinySynthetic/population.xml --demand 100 --scenario-source "TinySynthetic: synthetic and uncalibrated" --mode hybrid --policy mnl --llm-agents 0 --days 2 --seed 4711 --population-seed 4711 --native-time-mutation --trip-output-interval 1 --output output/tiny-prepare --validate-only
```

PowerShell uses the same single-line command. It has been executed on Windows
11 with Windows PowerShell 5.1 and Temurin 25; see `VALIDATION.md`. Make sure
`java` resolves to JDK 25 first: with a JDK 17 earlier on `PATH`, the command
stops before MATSim starts with `UnsupportedClassVersionError` (class file
version 69).

Expected preparation records:

- 10 cohort target records and one unique OD `sh>tw`;
- two candidates for that OD, with the link sequences and costs above;
- `morning/status.json` reports `validated=true`, `simulationRun=false`, and
  `apiCalls=0`;
- `morning/profiles.jsonl.gz` and `morning/information-groups.jsonl.gz` are not
  produced because the controlled roster is empty.

## Two-day native run

```bash
java -Xmx1g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --config scenarios/TinySynthetic/config.xml --population scenarios/TinySynthetic/population.xml --demand 100 --scenario-source "TinySynthetic: synthetic and uncalibrated" --mode hybrid --policy mnl --llm-agents 0 --days 2 --seed 4711 --population-seed 4711 --native-time-mutation --trip-output-interval 1 --output output/tiny-run
```

`--days 2` runs iterations 0 and 1. `--llm-agents 0` keeps every traveler
native and makes no model API calls; `--policy mnl` is only the selected
interface label and does not create MNL decisions when the controlled roster is
empty. `--trip-output-interval 1` is explicit for reproducibility and retains
target trip rows for both days; `1` is also the CLI default.

Expected day-level records, for the default 10 people:

| File or metric | Per day | Two days |
|---|---:|---:|
| `morning/trips.jsonl.gz` target records | 10 | 20 |
| `morning/experiences.jsonl.gz` target records | 10 | 20 |
| `morning/metrics.jsonl.gz` | 1 | 2 |
| `fullDayCarTravel.expectedCarLegs` | 20 | 40 |
| `fullDayCarTravel.completedCarLegs` | 20 | 40 |

The full-day metrics should also report `notStartedCarLegs=0`,
`abortedCarLegs=0`, `unfinishedCarLegs=0`, and
`allPlannedCarLegsCompleted=true`. Because there are no controlled travelers,
`decisions.jsonl.gz` and `executed-route-checks.jsonl.gz` are not created.
Events and plans are written per iteration, for example
`ITERS/it.0/0.events.xml.gz`, `ITERS/it.0/0.plans.xml.gz`, and the matching
`it.1` files.

If the requested output directory already exists, the runner reserves a name
such as `output/tiny-run-2`; use the directory printed by the application, not
the requested path.

To inspect the records, read `morning/status.json` and `morning/manifest.json`
as plain JSON, and use `gzip -dc output/tiny-run/morning/trips.jsonl.gz | head`
for JSONL files. Iteration events and plans are gzip XML under
`ITERS/it.0/` and `ITERS/it.1/`.

## How to change demand or capacity

Copy the scenario directory to a new location (for example under `output/`)
and edit the copy. Do not edit the published defaults for an experiment.

- **Reduce demand:** delete a complete `<person>` element from
  `population.xml`.
- **Increase demand:** copy a complete `<person>` element, assign a unique ID,
  and keep the facility, link, and coordinate references. For example, a
  `p11` copy with home end `08:04:45` and work end `17:04:45` stays inside the
  intended five-minute window; work start remains `08:15:00`.
- **Change capacity:** edit the `capacity` attribute of the chosen directed
  links, for example set both `su` and `us` from `1000.0` to `500.0`. The value
  is vehicles per hour under the one-hour capacity period.

Rerun both commands with the copied paths and a new output directory. The
default acceptance run does not include variant simulations.

`--demand 100` is retained only as the CLI request label. With an explicit
`--population`, the population file above is the effective input; the label is
not a person-count multiplier and does not imply the 84,110-person Sioux Falls
lineage.

## Source and known boundaries

The manifest keeps `schemaVersion=23`. Passing `--scenario-source` replaces
`manifest.source` and adds the optional `sourceBasis` field. If `--population`
is supplied without `--scenario-source`, the runner prints a warning and adds
an optional `sourceWarning` field, while preserving the legacy source text.
Readers must tolerate these optional fields.

Known boundaries:

- A `--config`-only invocation that omits both `--population` and
  `--scenario-source` can bypass the warning and retain the legacy Sioux Falls
  source text. The canonical commands above include both parameters.
- The PowerShell command is equivalent but has not been executed in this
  validation.
- `accessEgressType=none` is deprecated in MATSim 2026 and may log a deprecation
  notice; it keeps this scenario car-only and requires no access/egress walk.
- `c1` is a feasible alternative used to verify candidate preparation; it is
  not guaranteed to be selected by native MATSim.
- One two-day run is a teaching and validation input, not evidence about
  equilibrium, pricing, demand/capacity policy, or real-world traffic.

`VALIDATION.md` records the actual tested commit, JAR and input hashes,
environment, commands, counts, and limitations. The scenario reuses topology,
configuration, and assertion ideas from the existing synthetic test fixtures;
it is not a wrapper around a complete existing scenario.
