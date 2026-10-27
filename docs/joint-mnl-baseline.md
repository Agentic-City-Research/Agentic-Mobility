# Joint route and departure-time MNL baseline

[Documentation](README.md) · [Experiments & evaluation](experiments-and-evaluation.md) · [Reported experiments](reported-experiments.md)

The manuscript's MNL configuration selects a route and departure time jointly from the same fixed candidate sets used by LLM travelers. The recovered implementation is available as `--policy mnl-joint`. The older `--policy mnl` remains a separate route-only baseline: it keeps each controlled target's original planned departure. Neither policy calls a traveler LLM.

For a small offline integration run after building the JAR:

```bash
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment \
  --config scenarios/SiouxFalls-public/config.xml \
  --mode hybrid --policy mnl-joint --llm-agents 3 \
  --mnl-parameters config/baselines/mnl-joint.example.json --mnl-choice-seed 268 \
  --demand 100 --sample-persons 100 --days 2 \
  --population-seed 6021 --seed 268 --output output/mnl-joint-check
```

`--llm-agents` selects controlled people even for this offline baseline; `all` means all eligible target people. Non-target trips and uncontrolled people retain native MATSim behavior. Diagnostic `--sample-persons` does not scale road capacity and is not the manuscript configuration. The PowerShell wrapper accepts `-Policy mnl-joint -LlmAgents 3 -MnlParameters 'config/baselines/mnl-joint.example.json' -MnlChoiceSeed 268`.

## Action, information, and parameters

The baseline considers every legal route/departure pair, including multiple departure times for a single-route OD. Choices use a softmax over explicit travel-time, monetary-cost, predicted-lateness, and absolute original-departure-shift terms. Recent own completed experiences in the same route/time bin can update the travel-time estimate. The parameters are in [mnl-joint.example.json](../config/baselines/mnl-joint.example.json); their values are engineering assumptions, not calibrated behavioral estimates. The `--mnl-parameters` argument is required so that the assumptions and their file hash are recorded.

The choice seed defaults to the simulation seed and can be set independently with `--mnl-choice-seed`. A local seed is derived from the baseline version, choice seed, simulation day, and trip identity. Candidate presentation order or concurrent task scheduling does not reassign draws. The legacy route-only MNL is evaluated in fixed cohort order even when a Manager creates a worker pool.

The baseline receives the same permitted route/time context as an LLM traveler; its numeric estimate uses bounded recent personal facts and does not use the LLM's self-written summary or network-wide congestion information. Its default parameter file rejects pricing with uninformed controlled travelers. To use that mode, disable pricing or make all controlled travelers informed. A separate parameter file may explicitly set `unknownTollMode` to `personal-with-prior`; that is an additional modeling assumption, not knowledge of the current tariff. The prior and learning rule must be reported.

## Provenance and records

The implementation and example parameters were recovered from the local `AgenticCity-mnl-baseline` checkout. Its JAR SHA-256 is `a5f0a7b3b9d91783769aa61802734233b736d414938ce8c1d81113059f54f8d9`, matching the MNL runtime hash in the [archived run metadata](data/seed268/run-metadata.json); the parameter file hash also matches the archived record. This connects the recovered files to the historical build but does not by itself prove a commit-to-run mapping or make a new build byte-identical to that JAR.

As an implementation check, the recovered policy was applied to 200 archived baseline contexts: the first 100 choices on each of log Days0 and 1. Every selected route and departure matched the recorded decision. This is a sampled saved-context check, not a new 100-day simulation or a guarantee of an exact end-to-end rerun.

Each run records the effective baseline parameters and seed in `morning/baseline-parameters.json` and the manifest. `baseline-contexts.jsonl.gz` contains the supplied contexts; `baseline-choices.jsonl.gz` contains joint alternatives, utility components, probabilities, seed, and selected action. These detailed records can be large and may contain person-level simulation context. `--validate-only` writes preparation and parameter provenance without running traffic or making model calls.

For a matched study, reuse the archived candidates, population, controlled roster, profiles, information assignment, action grid, and native behavior settings; record the executable and all seeds. One run per configuration, uncalibrated coefficients, and different historical builds limit inference. Exact historical reproduction additionally needs the original runtime, run inputs/configuration, and a verified release-to-run mapping.
