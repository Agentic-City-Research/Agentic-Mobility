# Joint route and departure-time MNL

`--policy mnl` controls route and departure-time choices for selected target trips. Use `--mode hybrid --llm-agents all` for the selected cohort; `--llm-agents 0` uses MATSim replanning. MNL makes no model API calls; an enabled manager still requires a model service.

## Decisions and information

MNL and LLM travelers use fixed route candidates, departure-time options anchored to the original plan, and information groups. Each route–departure combination has utility `V = -a·T - b·C - c·L - d·|departure - originalDeparture|`. Travel time, lateness, and departure offset are in minutes; cost uses scenario currency units. Lateness is zero when no deadline exists. Softmax temperature is 1.

Estimated travel time combines static free-flow time and the mean of completed personal experiences on the same route and departure-time bin within `--history-window`. The default weight is 0.5 and the bin is 900 seconds. If no experience matches, the model uses free-flow time; incomplete trips are not completed observations.

Informed travelers use the current toll announcement. Uninformed travelers use their most recent personal observation for each link and time bin, including zero charges; unobserved tolls initially have a zero estimate. Actual charges follow the policy in force at link entry.

## Parameters and execution

The default file `config/baselines/mnl-joint.example.json` contains **uncalibrated engineering examples**, not estimated population parameters. Override it with `--mnl-parameters` or PowerShell `-MnlParameters`. The manifest and `baseline-parameters.json` record effective parameters and input hashes.

Run a small diagnostic from the repository root:

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mnl -LlmAgents all `
  -Demand 100 -SamplePersons 40 -Days 4 -Seed 268 `
  -MnlChoiceSeed 268 -Output 'output/mnl-joint-smoke'
```

This sample does not scale road capacity and is intended for debugging. For comparisons, document population, capacity scaling, input and candidate files, action space, information access, policy, seeds, and repeated runs. MNL choice seeds can be set independently with `--mnl-choice-seed` or `-MnlChoiceSeed`. Manager-enabled runs need a configured model service and sufficient request budget.

Fixed-toll comparisons can use the same policy in both traveler conditions. A closed-loop manager may select different policies in response to each condition, so such comparisons describe complete systems.

## Outputs

- `baseline-parameters.json` and the manifest record parameters, provenance, hashes, and seeds.
- `baseline-contexts.jsonl.gz` records available facts and actions.
- `baseline-choices.jsonl.gz` records utility components, probabilities, random draws, and choices.
- `decisions.jsonl.gz`, `experiences.jsonl.gz`, `trips.jsonl.gz`, and `executed-route-checks.jsonl.gz` record choices, experiences, and execution checks.

See [methods and protocols](methods-and-protocols.md) and [experiment design](experiments-and-evaluation.md).
