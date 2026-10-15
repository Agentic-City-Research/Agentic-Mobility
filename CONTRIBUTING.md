# Contributing to AgenticMobility

Contributions can include reproducible bug reports, documentation, experiment protocols, scenario extensions, analysis tools, and implementation changes. Start with an [issue](https://github.com/Agentic-City-Research/Agentic-Mobility/issues) when the scope needs discussion.

## Current implementation

- `MorningExperiment` runs the full-day scenario with optional traveler choices and manager pricing.
- `MorningReplay` evaluates saved traveler contexts without rerunning traffic.
- [`src/main/java/org/matsim/agentic/`](src/main/java/org/matsim/agentic/) contains the implementation and shared model client.
- [`src/test/java/org/matsim/agentic/`](src/test/java/org/matsim/agentic/) contains regression checks and their helpers.
- [`scenarios/SiouxFalls-public/`](scenarios/SiouxFalls-public/) contains experiment configuration; [`scenarios/Souix Falls 2014/`](scenarios/Souix%20Falls%202014/) supplies the full-day inputs.
- [`scripts/`](scripts/) contains runners, replay, analysis, population restoration, and Slurm templates.

Superseded standalone prototypes and upstream examples are available in [the source history before publication cleanup](https://github.com/Agentic-City-Research/Agentic-Mobility/tree/065f6cda9eab4dca7034bcfe07cae0e3a0bacf1c).

## Report a problem or experiment

Include the code commit, Java and operating-system versions, command or configuration, expected and observed behavior, and relevant logs. Remove credentials and private data.

For experiments, also include:

- The research question, comparison conditions, and metric definitions.
- Population sampling, eligible and controlled people/trips, model identifier, and service settings.
- Traffic, population, profile, information, and generation seeds as applicable; number of repetitions.
- Completion/failure counts, output manifest, analysis procedure, results, and limitations.

## Development checks

Use JDK 25 and run the checks relevant to your change:

```bash
sh ./mvnw -B clean package
```

Windows runner checks:

```powershell
pwsh -File scripts/test_run_public_experiment.ps1
```

Normal regression checks use offline clients or local test services. Full-background validation is opt-in with `-Dmorning.fullTrafficValidation=true`. Report what you actually checked and whether a live model service was used. The [CI configuration](.github/workflows/ci.yml) defines Linux and Windows checks.

Keep each change focused and update affected documentation. Preserve the scenario attribution and source notices in [THIRD_PARTY.md](THIRD_PARTY.md).
