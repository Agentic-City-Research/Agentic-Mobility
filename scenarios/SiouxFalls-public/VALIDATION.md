# SiouxFalls-public validation record

**Scenario:** the distributed enriched Sioux Falls inputs, run with the current
public configuration. The population is synthetic and uncalibrated; this record
describes an actual local run, not a reproduction of real-world traffic or of
the manuscript's seed-268 comparison.

## Tested version

- Code and scenario inputs: commit
  `06256fbfb3a583f0c97f14b2273667613e3c6c0e`, clean working tree.
- JAR: `matsim-example-project-0.0.1-SNAPSHOT.jar`, SHA-256
  `6cc07cbf650c9156fd2df334b85ad35695a4cf8e339ccf9479f15d80fc469904`, built
  with `sh ./mvnw -B -DskipTests package`.
- Environment: Windows 11 Home China 10.0.22631, Git Bash; Temurin OpenJDK
  25.0.5+8; Maven Wrapper 3.9.8; 32 GB RAM.
- MATSim version reported in the manifest: `2026.0`.
- Model service: none. Both runs used `--llm-agents 0` and reported
  `apiCalls=0`.
- Scenario source recorded in the manifest: the default local Sioux Falls
  declaration (DOI 10.17632/2332kg48c8.3). No `--scenario-source` override was
  passed, so no `sourceBasis` entry exists.

Scenario inputs as recorded in `morning/manifest.json` (`inputHashes`):

| Input | SHA-256 |
| :--- | :--- |
| `scenarios/SiouxFalls-public/config.xml` | `c9837c8a3a5852a65027be4be4a8c267afe30452726d9b10130682c44d3c36a8` |
| `scenarios/Souix Falls 2014/Siouxfalls_network_Car.xml.gz` | `5cf9741ced77122b0971f7c2294770ede9af8d649d14ba905b9afbcc227083fb` |
| `scenarios/Souix Falls 2014/Siouxfalls_population_100pct.xml.gz` | `d888a3db7e2823f4c02f60b6f69462726470d163efd7bfa42261667bb35c036b` |
| `scenarios/Souix Falls 2014/Siouxfalls_facilities.xml.gz` | `6affe3962df39c1c1def993176298d199b7474dc9ebcf8aa53b64e2fa4319fbd` |

The three `.xml.gz` hashes equal the git blob hashes, so they are identical on
any checkout. The `config.xml` hash is the working-tree hash of this Windows
checkout (CRLF line endings); its git blob hash is
`14fd0f2451ab41bdc50cb8914595b7183cc8cdb7711334318d7810fb17afaeaa`. `.gitattributes`
does not normalize this file, so a POSIX checkout will report a different
working-tree hash for the same committed content.

## Commands

Preparation check:

```bash
java -Xmx8g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --config scenarios/SiouxFalls-public/config.xml --mode hybrid --demand 100 --sample-rate 0.1 --population-seed 4711 --seed 4711 --days 2 --policy mnl --llm-agents 0 --native-time-mutation --output output/sf-prepare --validate-only
```

Two-day native run:

```bash
java -Xmx8g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --config scenarios/SiouxFalls-public/config.xml --mode hybrid --demand 100 --sample-rate 0.1 --population-seed 4711 --seed 4711 --days 2 --policy mnl --llm-agents 0 --native-time-mutation --output output/sf-run-2day
```

Exit status: `0` for both commands. Wall time: 3 min 52 s and 3 min 55 s.

## Preparation result

- Output: `output/sf-prepare`.
- `morning/status.json`: `validated=true`, `simulationRun=false`,
  `apiCalls=0`, `integrityVerified=true`.
- Scales: 8,411 simulated people, 5,847 morning target trips, 1,963 unique
  ODs. Demand 100 selects the 84,110-person input before the 0.1 sample;
  capacity factors are `flow=0.1` and `storage≈0.177828`.
- Candidate preparation: 27,798 candidates, distribution by OD
  `{1: 413, 2: 124, 3: 139, 4: 1287}`; 1,287 ODs reached the four-route
  target; 163 were search-limited and 217 exhausted the search space;
  5,545,310 shortest-path searches; 13,418 cost-rejected, 189
  overlap-rejected and 7,965 omitted paths.
- `candidates.jsonl.gz` and `candidate-quality.jsonl.gz` each have 1,963
  rows; `cohort.jsonl.gz` has 5,847 rows. No controlled roster exists with
  `--llm-agents 0`, so profiles and information groups are absent.

## Two-day result

- Output: `output/sf-run-2day`.
- `morning/status.json`: `complete=true`, `daysCompleted=2`,
  `integrityVerified=true`, `unexpectedShutdown=false`, `apiCalls=0`.
- Records:

| File or metric | Day 0 | Day 1 | Total |
|---|---:|---:|---:|
| `morning/trips.jsonl.gz` | 5,847 | 5,847 | 11,694 |
| `morning/experiences.jsonl.gz` | 5,847 | 5,847 | 11,694 |
| `morning/metrics.jsonl.gz` | 1 | 1 | 2 |
| `morning/link-hours.jsonl.gz` | 5,193 | 5,195 | 10,388 |
| `fullDayCarTravel.expectedCarLegs` | 16,822 | 16,822 | 33,644 |
| `fullDayCarTravel.completedCarLegs` | 16,822 | 16,822 | 33,644 |

- Both days: `completionRate=1.0`, `stuckTrips=0`, `abortedCarLegs=0`,
  `unfinishedCarLegs=0`, `notStartedCarLegs=0`,
  `allPlannedCarLegsCompleted=true`.
- Target-trip outcomes: `meanCarSeconds` 2,983.7 (day 0) and 3,005.5
  (day 1); late work targets 1,725 of 5,632 (30.63%) and 1,745 of 5,632
  (30.98%).
- Events per iteration: 688,190 and 688,185 events. Each iteration has
  50,464 `departure` and 50,465 `arrival` events, 33,644 `travelled` events,
  16,822 `vehicle leaves traffic`/`PersonEntersVehicle` events and about
  192,700 `entered link`/`left link` events. The counts are the expected
  16,822 car legs plus 33,644 walk access/egress legs per day.
- Plans: each iteration file contains 8,411 persons with 16,822 car legs and
  33,644 walk legs.
- Iteration files are zstd-compressed (`.zst`). All four iteration event and
  plan files were decompressed in full with Python `zstandard` 0.25.0 without
  error.

## Checks run on this machine

- `python -m unittest discover -s tests`: 8 tests, OK.
- `python scripts/check_local_links.py`: 241 links checked, 0 errors.
- `TinySyntheticScenarioTest` 8/8 and `ManifestSourceTest` 4/4 were also run
  against this JAR (12/12), as recorded in
  [`scenarios/TinySynthetic/VALIDATION.md`](../TinySynthetic/VALIDATION.md#windows-re-run-2026-11-16).
- The full-traffic validation test is not part of this two-day record; see
  the note below.

## Full-population opt-in test

`MorningFullTrafficValidationTest` runs the same inputs with the full 84,110
people, three controlled travelers and seven days. It only runs when the
Maven invocation adds `-Dmorning.fullTrafficValidation=true` inside
`-DargLine`, so a normal `mvn test` build reports it as skipped and the
full-population path is not covered by default.

## Warnings and environment noise

- `logfileWarningsErrors.log` contains 0 ERROR lines and 13 WARN lines, all
  MATSim deprecations (`usePersonIdForMissingVehicleId`,
  `NetworkRoutingProvider` for the configured routing module,
  `ExperiencedPlansServiceImpl`, `ExpBetaPlanChanger`, and the
  `AttributeConverter` notice for `IdImpl`).
- MATSim initially tries remote DTD URLs in CLI runs, times out, and falls
  back to the bundled local DTD. The runs are unaffected.
- No zstd command-line tool is installed on this machine; decompression was
  checked with the Python `zstandard` package installed in a temporary
  directory, not in a global environment.

## Known limitations

- Synthetic population and uncalibrated scenario; two simulation days are not
  evidence about equilibrium, pricing, demand/capacity policy, or real-world
  traffic.
- Only the native MATSim path (`--llm-agents 0`) was run. No traveler or
  manager model behavior, prompt, or repair path is verified here.
- This is not a reproduction of the manuscript's seed-268 100-day
  comparison. That comparison uses a different window, sampling seed and
  executable; see [reported experiments](../../docs/reported-experiments.md).
- The default seed 4711 and a single run per command were used.
- Only Bash on Windows was executed; the PowerShell equivalents in
  [the README](../../README.md) are untested in this record.
