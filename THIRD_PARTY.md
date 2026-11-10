# Third-party sources and data attribution

## MATSim code and templates

This project follows the Maven build lineage of [matsim-org/matsim-example-project](https://github.com/matsim-org/matsim-example-project). The root `LICENSE` is GPL version 2; retain existing copyright notices and follow each source file's header where it specifies version 2 or later. MATSim is resolved as a Maven dependency, not bundled in the source directory.

`mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` derive from Apache Maven Wrapper. Retain their Apache-2.0 headers; the license is in [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).

The October 15, 2026 cleanup removed upstream example entry points, the equil scenario, related test benchmarks, and a standalone morning-trip scenario. Their attribution, checksums, and instructions remain in the [historical version](https://github.com/Agentic-City-Research/Agentic-Mobility/blob/065f6cda9eab4dca7034bcfe07cae0e3a0bacf1c/THIRD_PARTY.md).

## Sioux Falls inputs

Source: Golan Ben-Dor, **Souix Falls 2014, Car Traffic with Doubled Population (Input for k = 1)**, Mendeley Data, version 3, 2020-01-20, [10.17632/2332kg48c8.3](https://data.mendeley.com/datasets/2332kg48c8/3), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

Original scenario research: Chakirov, A. and Fourie, P. J. (2014), *Enriched Sioux Falls Scenario with Dynamic and Disaggregate Demand*. See [MATSim Sioux Falls](https://matsim.org/gallery/sioux-falls/). This is a synthetic benchmark, not individual observations of real residents.

- The car-only network, facilities, and 200% population in `scenarios/Souix Falls 2014/` follow the public data lineage. The 100% population removes individually checked `_new` duplicates; see `scripts/restore_original_population.py`.
- `scenarios/SiouxFalls-public/config.xml` is adapted for MATSim 2026 and this project. The historical `.xml.orig` is a reference configuration; its former absolute output path was changed to a relative path, so it is not a byte-identical original.
- Fixed-toll JSON files are project experiment examples, not validated upstream policies.

The source attribution relies on existing project records and public source pages; the full scenario was not re-downloaded during cleanup. The following SHA-256 values identify locally distributed inputs and do not establish a byte-for-byte comparison with the Mendeley release. Retain authors, source, license link, and modification notices when distributing derived data. CC BY 4.0 does not imply endorsement.

## Input checksums

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `scenarios/Souix Falls 2014/Siouxfalls_facilities.xml.gz` | 426022 | `6affe3962df39c1c1def993176298d199b7474dc9ebcf8aa53b64e2fa4319fbd` |
| `scenarios/Souix Falls 2014/Siouxfalls_network_Car.xml.gz` | 8532 | `5cf9741ced77122b0971f7c2294770ede9af8d649d14ba905b9afbcc227083fb` |
| `scenarios/Souix Falls 2014/Siouxfalls_population_100pct.xml.gz` | 4501400 | `d888a3db7e2823f4c02f60b6f69462726470d163efd7bfa42261667bb35c036b` |
| `scenarios/Souix Falls 2014/Siouxfalls_population_200pct.xml.gz` | 5840027 | `f5f2d716560e45606b2a38c1d95c82abce0beff82762d6f3831021f2c619707c` |
