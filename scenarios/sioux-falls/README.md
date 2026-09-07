# Sioux Falls scenario

Sioux Falls is the default city scenario for Agentic MATSim. The included configuration is derived from the previously validated 101-iteration experiment.

## Required input

Place these files in the git-ignored `scenarios/sioux-falls/input/` directory:

```text
Siouxfalls_network_PT.xml
Siouxfalls_population.xml.gz
Siouxfalls_facilities.xml.gz
Siouxfalls_transitSchedule.xml
Siouxfalls_vehicles.xml
```

On Windows, copy and validate them with:

```powershell
.\scenarios\sioux-falls\setup-input.ps1 `
  -SourceDirectory "D:\path\to\siouxfalls-2014"
```

The input is the “Enriched Sioux Falls Scenario with Dynamic and Disaggregate Demand” associated with Chakirov and Fourie (2014). Confirm the authoritative download source, citation and redistribution terms before publishing the files.

## Experiment behavior

- MATSim iterations: `0..100`.
- Controlled population: reproducible 1% stratified sample by default.
- Agent actions: `car`, `pt` or `walk`, plus a bounded departure-time shift.
- Feedback: executed mode, travel time, distance and MATSim score.
- Adaptation: recent history, long-term action statistics, exploration, rollback and mode blocking.

Use `--iterations 0` and a small `LLM_AGENT_SHARE` for a quick integration check. Use `run_101_iterations.ps1` only after setting a provider key and reviewing the expected number and cost of calls.
