# TinySynthetic validation record

**Scenario:** TinySynthetic, synthetic and uncalibrated. This record describes
an actual local run, not a calibration or a real-world traffic claim.

## Tested version

- Code and scenario inputs: commit `bb0e7253dac0f04dbd206f62c32fa5fb3d471fa8`; the
  Java sources and four scenario inputs are identical to `e286b4d`. The final
  test-assertion and documentation edits were committed after this run and do
  not change the JAR or the inputs.
- Working tree at run: HEAD was `bb0e725`; the pending test/documentation edits
  and the untracked `VALIDATION.md` were present, but the Java sources and the
  four input files matched the hashes below.
- JAR: `matsim-example-project-0.0.1-SNAPSHOT.jar`, SHA-256
  `fc64980d9e0414121e91fee874481e1db1a0133bdc53dfca061072248db23c96`.
- Scenario inputs (SHA-256):
  - `config.xml`: `5c1878c6d8c4c3e19915965ee33e9fe8f0b4e89fc05b4557ea39742065a3c747`
  - `network.xml`: `9583ea9d03e5df0d795c32c8759f98cc12761e43b14a0f296e9c4ec4d1be217a`
  - `population.xml`: `719ffdedc56d489187f67babb1478861b7ddf2e802a7f4478f9dd74d4b97581a`
  - `facilities.xml`: `01f179d32bc8ecabd8c89c1e931eab2d425bdb5c18ebc905c6c67c723913a9dd`
- Environment: macOS 26.6.2, Darwin 25.6.0, arm64; OpenJDK 25.0.5.0.3
  (Homebrew); Maven Wrapper 3.9.8 with Maven JVM 25.0.5.
- Model service: none. All runs used `--llm-agents 0` and reported
  `apiCalls=0`.

## Commands

Preparation check:

```bash
java -Xmx1g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --config scenarios/TinySynthetic/config.xml --population scenarios/TinySynthetic/population.xml --demand 100 --scenario-source "TinySynthetic: synthetic and uncalibrated" --mode hybrid --policy mnl --llm-agents 0 --days 2 --seed 4711 --population-seed 4711 --native-time-mutation --trip-output-interval 1 --output output/tiny-prepare --validate-only
```

Official two-day run:

```bash
java -Xmx1g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --config scenarios/TinySynthetic/config.xml --population scenarios/TinySynthetic/population.xml --demand 100 --scenario-source "TinySynthetic: synthetic and uncalibrated" --mode hybrid --policy mnl --llm-agents 0 --days 2 --seed 4711 --population-seed 4711 --native-time-mutation --trip-output-interval 1 --output output/tiny-run-final
```

Exit status: `0` for both commands. Actual output directories were reserved by
the runner and are named in the sections below.

## Preparation result

- Output: `output/tiny-prepare` (no suffix).
- `morning/status.json`: `validated=true`, `simulationRun=false`,
  `apiCalls=0`, `integrityVerified=true`.
- 10 cohort target records, one unique OD `sh>tw`, two candidates:
  - `c0 = sh-hs-su-ut-tw`, 120 s, 1200 m;
  - `c1 = sh-hs-sl-lt-tw`, 145 s, 1200 m; overlap about 1/6.
- `morning/candidates.jsonl.gz` has 1 row; `morning/candidate-quality.jsonl.gz`
  has 1 row; `profiles` and `information-groups` are absent because the
  controlled roster is empty.
- Manifest: `schemaVersion=23`, `requestedDemandPercent=100`,
  `simulatedPopulation=10`, `populationSampling.mode=none`,
  `source` is the explicit TinySynthetic declaration, `sourceBasis` is
  `explicit-scenario-declaration`, and there is no `sourceWarning`.
- `inputHashes.population` points to `scenarios/TinySynthetic/population.xml`
  and matches the SHA-256 above.
- Population selection: explicit file override; the demand selector was not
  applied. `requestedDemandPercent=100` is only the original CLI request label.

## Two-day result

- Output: `output/tiny-run-final` (verify the exact name printed by the runner
  if it was already present).
- `morning/status.json`: `complete=true`, `daysCompleted=2`,
  `integrityVerified=true`, `unexpectedShutdown=false`, `apiCalls=0`.
- Manifest: `schemaVersion=23`, explicit source and `sourceBasis`, no
  `sourceWarning`, `apiCalls=0`.
- Event counts per iteration: 20 car departures and 20 car arrivals.
- Records:

| File or metric | Day 0 | Day 1 | Total |
|---|---:|---:|---:|
| `morning/trips.jsonl.gz` | 10 | 10 | 20 |
| `morning/experiences.jsonl.gz` | 10 | 10 | 20 |
| `morning/metrics.jsonl.gz` | 1 | 1 | 2 |
| `fullDayCarTravel.expectedCarLegs` | 20 | 20 | 40 |
| `fullDayCarTravel.completedCarLegs` | 20 | 20 | 40 |

- Each day: `notStartedCarLegs=0`, `abortedCarLegs=0`,
  `unfinishedCarLegs=0`, `allPlannedCarLegsCompleted=true`.
- `decisions.jsonl.gz` and `executed-route-checks.jsonl.gz` are absent because
  no agent is controlled.
- Iteration files: `ITERS/it.0/0.events.xml.gz`, `0.plans.xml.gz`,
  `ITERS/it.1/1.events.xml.gz`, `1.plans.xml.gz`; both gzip files passed
  `gzip -t`, and both plans contain two car legs per person.

## Checks run

- `ManifestSourceTest`: 4/4 pass (default source unchanged, explicit source,
  blank rejection, population-without-source warning).
- `TinySyntheticScenarioTest`: 8/8 pass (structure, loaded cohort/candidates,
  missing/reversed link constraints, two-day native run, XML edits).
- `MorningExperimentTest` + `MorningTravelerV9Test`: 17/17 pass.
- Full `sh ./mvnw -B clean package`: `BUILD SUCCESS`; 236 tests, 0 failures,
  0 errors, 1 skipped (the existing `MorningFullTrafficValidationTest` skip);
  22:10 total. This run preceded the final test-only assertion additions; after
  those additions, `TinySyntheticScenarioTest` and `ManifestSourceTest` were
  rerun: 12/12 pass.
- `python3 -m unittest discover -s tests`: 8 tests, OK.
- `python3 scripts/check_local_links.py`: 248 links checked, 0 errors.

## Warnings and environment noise

- Google Guice's bundled ASM logs `Unsupported class file major version 69`
  when printing injector line numbers on Java 25. The run and all tests
  complete; this is a known Guice/JDK compatibility warning, not a scenario
  failure.
- MATSim initially tries remote DTD URLs in CLI runs, times out, and falls back
  to the bundled local DTD. Tests patch copies to the bundled DTD; the run is
  unaffected.
- `accessEgressType=none` is deprecated in MATSim 2026; it is used to keep the
  scenario car-only, and a deprecation notice may appear.
- `output/tiny-run-final/logfileWarningsErrors.log` contains no ERROR/WARNING
  lines for the scenario itself.

## Known limitations

- Synthetic and uncalibrated; one two-day run is not evidence about
  equilibrium, pricing, demand/capacity policy, or real-world traffic.
- The PowerShell form was executed once on one Windows machine. The full
  `clean package` including the whole test suite was not repeated in
  PowerShell.
- A `--config`-only invocation that omits both `--population` and
  `--scenario-source` can bypass the source warning and retain the legacy
  Sioux Falls source text.
- `c1` is a feasible alternative prepared for the target OD, but native MATSim
  is not guaranteed to select it.
- `decisions.jsonl.gz` and `executed-route-checks.jsonl.gz` are absent by the
  current zero-controlled output contract; they are not missing evidence of a
  failed decision log.
- Only the default seed 4711 was run; this record is not a multi-seed study.

## Windows re-run (2026-11-16)

An independent re-run on a second machine, using the same commands and the
default seed.

### Tested version and environment

- Code: commit `06256fbfb3a583f0c97f14b2273667613e3c6c0e`, clean working tree.
- JAR: `matsim-example-project-0.0.1-SNAPSHOT.jar`, SHA-256
  `6cc07cbf650c9156fd2df334b85ad35695a4cf8e339ccf9479f15d80fc469904`, built
  with `sh ./mvnw -B -DskipTests package`.
- Environment: Windows 11 Home China 10.0.22631, Git Bash; Temurin OpenJDK
  25.0.5+8; Maven Wrapper 3.9.8; 32 GB RAM.
- Model service: none. Both runs used `--llm-agents 0` and reported
  `apiCalls=0`.

### Input hashes and line endings

A Windows checkout writes CRLF line endings, because `.gitattributes`
normalizes only the archived parameter and data files, not the scenario XMLs.
Working-tree SHA-256 values therefore differ from the POSIX checkout above,
while the git blob hashes (the recorded macOS values) are unchanged:

| File | Working tree, CRLF Windows | Git blob, equals the macOS record above |
| :--- | :--- | :--- |
| `config.xml` | `5dd02c361771577b00a171d9af393cdf7d7f4e193cb318056ad021eaa4d8f332` | `5c1878c6d8c4c3e19915965ee33e9fe8f0b4e89fc05b4557ea39742065a3c747` |
| `network.xml` | `27606b46bb91fc048f48f4112bc20f1270ee0a6769e7b0e9a2708826d51ae9ff` | `9583ea9d03e5df0d795c32c8759f98cc12761e43b14a0f296e9c4ec4d1be217a` |
| `population.xml` | `226164771c23e7b471e39d0bbad54358dc38ccc11096caed1eb6ecd2a1935eb3` | `719ffdedc56d489187f67babb1478861b7ddf2e802a7f4478f9dd74d4b97581a` |
| `facilities.xml` | `2ac1a9f66a9a7d7edf97e6d64ee1f80668591958ece039e7f44d470c3beb9f37` | `01f179d32bc8ecabd8c89c1e931eab2d425bdb5c18ebc905c6c67c723913a9dd` |

The two checkouts therefore contain the same committed content. Compare
git blob hashes when checking identity across platforms.

### Results

- Preparation run (`output/tiny-prepare-win`): `validated=true`,
  `simulationRun=false`, `apiCalls=0`, `integrityVerified=true`.
  One unique OD `sh>tw`, 10 cohort records, the same two candidates
  (`c0 = sh-hs-su-ut-tw`, 120 s, 1200 m; `c1 = sh-hs-sl-lt-tw`, 145 s,
  1200 m; overlap about 1/6).
- Two-day run (`output/tiny-run-win`): `complete=true`, `daysCompleted=2`,
  `integrityVerified=true`, `unexpectedShutdown=false`, `apiCalls=0`.
- Records match the POSIX run:

| File or metric | Day 0 | Day 1 | Total |
|---|---:|---:|---:|
| `morning/trips.jsonl.gz` | 10 | 10 | 20 |
| `morning/experiences.jsonl.gz` | 10 | 10 | 20 |
| `morning/metrics.jsonl.gz` | 1 | 1 | 2 |
| `fullDayCarTravel.expectedCarLegs` | 20 | 20 | 40 |
| `fullDayCarTravel.completedCarLegs` | 20 | 20 | 40 |

- Both days: 20 car departures and 20 car arrivals; `notStartedCarLegs=0`,
  `abortedCarLegs=0`, `unfinishedCarLegs=0`,
  `allPlannedCarLegsCompleted=true`; `lateWorkTrips=0` of 10 work targets,
  `onTimeWorkFractionAll=1.0`; `meanCarSeconds=124.0` with `c0` selected
  (`meanSelectedRouteFreeFlowSecondsCompleted=120.0`).
- Both plans files contain two car legs for each of the 10 persons; all four
  `ITERS` gzip files pass `gzip -t`.
- Manifest: `schemaVersion=23`, `requestedDemandPercent=100`,
  `simulatedPopulation=10`, explicit source and `sourceBasis`, no
  `sourceWarning`, `apiCalls=0`.
- Wall time: about 4 s for preparation and 6 s for the two-day run.

### PowerShell execution

The documented PowerShell form was executed on the same machine with Windows
PowerShell 5.1.22621, using the same JAR and inputs:

```powershell
Set-Location 'C:\Users\ROG\Desktop\Agentic-Mobility'
$env:JAVA_HOME = 'C:\Users\ROG\jdks\jdk-25.0.5+8'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -Xmx1g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --config scenarios/TinySynthetic/config.xml --population scenarios/TinySynthetic/population.xml --demand 100 --scenario-source "TinySynthetic: synthetic and uncalibrated" --mode hybrid --policy mnl --llm-agents 0 --days 2 --seed 4711 --population-seed 4711 --native-time-mutation --trip-output-interval 1 --output output/tiny-prepare-ps --validate-only
java -Xmx1g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --config scenarios/TinySynthetic/config.xml --population scenarios/TinySynthetic/population.xml --demand 100 --scenario-source "TinySynthetic: synthetic and uncalibrated" --mode hybrid --policy mnl --llm-agents 0 --days 2 --seed 4711 --population-seed 4711 --native-time-mutation --trip-output-interval 1 --output output/tiny-run-ps
```

- Without the `JAVA_HOME`/`PATH` lines the same command fails before MATSim
  starts: the default `java` on this machine is JDK 17, which rejects the JDK 25
  class files (`UnsupportedClassVersionError`, class file version 69), exit
  status 1.
- With them, both runs exit `0`. `output/tiny-prepare-ps` reports
  `validated=true`, `simulationRun=false`, `apiCalls=0`; `output/tiny-run-ps`
  reports `complete=true`, `daysCompleted=2`, `apiCalls=0`.
- `candidates.jsonl.gz` and `cohort.jsonl.gz` in the PowerShell runs are
  byte-identical to the Bash runs in `output/tiny-prepare-win` and
  `output/tiny-run-win` (both 1 candidate row and 10 cohort rows). Trips and
  experiences are 10 per day, as above.
- `.\mvnw.cmd -B -DskipTests package` also reports `BUILD SUCCESS` in
  PowerShell. The documented `clean package` including the full test suite was
  not repeated in PowerShell.

### Checks run on this machine

- `TinySyntheticScenarioTest`: 8/8 pass; `ManifestSourceTest`: 4/4 pass
  (12/12).
- `python -m unittest discover -s tests`: 8 tests, OK.
- `python scripts/check_local_links.py`: 241 links checked, 0 errors,
  re-run after the PowerShell edits.
- The full test suite was not rerun here; the JAR was built with
  `-DskipTests`. Both the Bash and PowerShell command forms were executed.

### Warnings

- `logfileWarningsErrors.log` has no ERROR lines. Its WARN lines are MATSim
  deprecations (`usePersonIdForMissingVehicleId`, `NetworkRoutingProvider`
  for `accessEgressType=none`, `ExperiencedPlansServiceImpl`, the
  `AttributeConverter` notice for `IdImpl`, and `ExpBetaPlanChanger`) plus the
  Guice/ASM Java 25 notice described above. None affects completion.
