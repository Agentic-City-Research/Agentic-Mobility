# Figure and media inventory

[Documentation](../README.md) · [Reported experiments](../reported-experiments.md) · [Data inventory](../data/README.md)

## Framework and 100-day displays

| Asset                                                                                                                 | Meaning and scope                                                                                                                                |
| :-------------------------------------------------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------- |
| [agenticmobility_framework_siouxfalls.svg](agenticmobility_framework_siouxfalls.svg)                                  | Role-specific observations/actions, shared execution, persistent state, and a research archive separate from agent memory                        |
| [outcomes.svg](../pic/outcomes.svg), [PNG](../pic/outcomes.png)                                                       | Five-configuration 100-day outcome display: mean daily car time per person, target-trip time, and work lateness                                  |
| [information_route_response.svg](../pic/information_route_response.svg), [PNG](../pic/information_route_response.png) | Additional 100-day route-use display by announcement group; not a figure in the current manuscript and not an isolated causal information effect |

The outcome display uses denominators of 16,822 people, 11,793 target trips, and 11,332 work targets. Mean daily car time per person is `60 × total car person-hours / 16,822`. Thin time-series lines show daily values; thick lines show trailing seven-day means. Positive charging begins on simulation Days 21 and 51 in Manager and Joint, respectively; Day 91 is the MATSim innovation cutoff. Policy opportunities occur on simulation Days 21, 51, and 81. The original figure axes use zero-based log days; add 1 to read a simulation day.

The manuscript's canonical PDF figures and source diagrams are not included in this repository. The available displays are linked in the table above; the additional route-use display does not expand the manuscript's evidence claims.

The [100-day aggregate exports](../data/seed268/README.md) supply the unsmoothed daily outcomes and behavior measures. Full individual records remain in the external run archive; selected case excerpts are documented separately in the data inventory. The extra information-group route-use display requires its own group-specific source records beyond these outcome/behavior columns. Pre-pricing differences prevent interpreting Joint–Traveler contrasts as an isolated manager effect, and people sharing a run are not independent simulation replications.

## Simulation videos

As documented in the [project overview](../../README.md#experiments), the videos compare MATSim, LLM Traveler, and LLM Traveler + Manager using the same 500 sampled travelers from the population of 16,822. [Day1.gif](Day1.gif), [Day50.gif](Day50.gif), and [Day100.gif](Day100.gif) show simulation Days 1, 51, and 100, respectively. The source filenames and embedded labels retain their original conventions; log days are zero-based.

These videos visualize a subset of travelers. Quantitative results use the full population or fixed target/work cohort appropriate to each measure; the animations do not replace completed-run execution audits.
