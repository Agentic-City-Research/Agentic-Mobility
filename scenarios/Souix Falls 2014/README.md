# Sioux Falls full-day input data

The current experiment configuration is [SiouxFalls-public/config.xml](../SiouxFalls-public/config.xml).
It loads the doubled-demand population by default. Set `--demand 100` in
`MorningExperiment` to use `Siouxfalls_population_100pct.xml.gz`: 84,110
original persons, with 168,220 car legs. Original attributes, activity chains,
locations, times, and person IDs are preserved.

The retained `Siouxfalls_population_200pct.xml.gz` contains 168,220 persons.
Each original person has one identical copy with the suffix `_new`. All 84,110
copy pairs were verified before copies were removed from the new file.
The original doubled file has not been overwritten.

Use `--sample-rate k` for population sampling in the current experiment.
The effective capacity factors are flow=`k` and storage=`k^0.75`.
The diagnostic `--sample-persons` option does not scale capacities.
See the [scenario protocol](../SiouxFalls-public/README.md) for details.

The population conversion is reproducible with
[`scripts/restore_original_population.py`](../../scripts/restore_original_population.py).
The script verifies copy equivalence and all-car legs and refuses to overwrite
an existing destination.

`Siouxfalls_config.xml.orig` is retained as a historical configuration reference.
Data sources, attribution, and file hashes are recorded in
[THIRD_PARTY.md](../../THIRD_PARTY.md).
