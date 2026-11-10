# Historical joint route and departure-time MNL baseline

[Documentation](README.md) · [Experiments & evaluation](experiments-and-evaluation.md) · [Reported experiments](reported-experiments.md)

This page records the recovered implementation used to investigate the manuscript's MNL configuration. In that historical build, `--policy mnl-joint` selected route and departure time jointly, while `--policy mnl` was route-only. These names describe that build, not the current interface.

**For the current code**, use [`--policy mnl` with a controlled-person count](mnl-joint.md). The historical `--policy mnl-joint` command is not a current-code example. MNL itself makes no model calls; an enabled LLM manager still requires a model service.

## Action, information, and parameters

The historical baseline selects among legal route/departure pairs using uncalibrated engineering parameters and bounded personal experience. See the [experiment record](assets/data/seed268/seed268-experiments.md) for historical action sets and information conditions, and [current MNL setup](mnl-joint.md) for today's parameters and commands.

## Provenance and records

The implementation and example parameters were recovered from the local `AgenticCity-mnl-baseline` checkout. Its JAR SHA-256 is `a5f0a7b3b9d91783769aa61802734233b736d414938ce8c1d81113059f54f8d9`, matching the MNL runtime hash in the [archived run metadata](data/seed268/run-metadata.json); the parameter file hash also matches the archived record. This connects the recovered files to the historical build but does not by itself prove a commit-to-run mapping or make a new build byte-identical to that JAR.

As an implementation check, the recovered policy was applied to 200 archived baseline contexts: the first 100 choices on each of log Days0 and 1. Every selected route and departure matched the recorded decision. This is a sampled saved-context check, not a new 100-day simulation or a guarantee of an exact end-to-end rerun.

For a matched study, reuse the archived candidates, population, controlled roster, profiles, information assignment, action grid, and native behavior settings; record the executable and all seeds. One run per configuration, uncalibrated coefficients, and different historical builds limit inference. Exact historical reproduction additionally needs the original runtime, run inputs/configuration, and a verified release-to-run mapping.
