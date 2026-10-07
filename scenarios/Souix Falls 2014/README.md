# Sioux Falls population size

The active `Siouxfalls_config.xml` now loads
`Siouxfalls_population_100pct.xml.gz`: 84,110 original persons, with 168,220
car legs. Original attributes, activity chains, locations, times, and person IDs
are preserved. This is a full-day population, not an extracted morning population.

The retained `Siouxfalls_population_200pct.xml.gz` contains 168,220 persons.
Each original person has one identical copy with the suffix `_new`. All 84,110
copy pairs were verified before copies were removed from the new file.
The original doubled file has not been overwritten.

The network, facilities, flow capacity factor (1.0), and storage capacity factor
(1.0) have not changed. To use all 84,110 persons with the agentic application,
use `--sample 1.0 --capacity-factor 1.0` (both are effective defaults for this
configuration). `--sample 0.5` now retains 42,055 persons, not 84,110.

To restore the doubled scenario, point `inputPlansFile` back to
`Siouxfalls_population_200pct.xml.gz`.

The conversion is reproducible with `scripts/restore_original_population.py`.
The script verifies copy equivalence and all-car legs and refuses to overwrite
an existing destination.
