# Figure and media inventory

[Documentation](../README.md) · [Reported experiments](../reported-experiments.md) · [Data inventory](../data/README.md)

## Framework and 100-day displays

| Asset | Meaning and scope |
| :--- | :--- |
| [agenticmobility_framework_siouxfalls.svg](agenticmobility_framework_siouxfalls.svg) | Role-specific observations/actions, shared execution, persistent state, and a research archive separate from agent memory |
| [mobility-loop.svg](mobility-loop.svg), [simplified loop](mobility-loop-simple.svg) | Current traveler/manager MATSim implementation; daily memory and periodic pricing are distinct tasks |
| [seed268/outcomes.svg](seed268/outcomes.svg), [PNG](seed268/outcomes.png) | Five-configuration 100-day outcome display: mean daily car time per person, target-trip time, and work lateness |
| [seed268/information_route_response.svg](seed268/information_route_response.svg), [PNG](seed268/information_route_response.png) | Additional 100-day route-use display by announcement group; not a figure in the current manuscript and not an isolated causal information effect |

The outcome display uses denominators of 16,822 people, 11,793 target trips, and 11,332 work targets. Mean daily car time per person is `60 × total car person-hours / 16,822`. Thin time-series lines show daily values; thick lines show trailing seven-day means. Log Days20 and 50 identify positive charging in Manager and Joint, respectively; Day90 is the native innovation cutoff. Policy opportunities occur on Days20, 50, and 80, equivalent to simulation days 21, 51, and 81.

The manuscript's canonical PDF figures and source diagrams are not included in this repository. The available displays are linked in the table above; the additional route-use display does not expand the manuscript's evidence claims.

The [100-day aggregate exports](../data/seed268/README.md) supply the unsmoothed daily outcomes and behavior measures. Full individual records remain in the external run archive; selected case excerpts are documented separately in the data inventory. The extra information-group route-use display requires its own group-specific source records beyond these outcome/behavior columns. Pre-pricing differences prevent interpreting Joint–Traveler contrasts as an isolated manager effect, and people sharing a run are not independent simulation replications.

## Simulation videos

As documented in the [project overview](../../README.md#experiments), the videos compare MATSim, LLM Traveler, and LLM Traveler + Manager using the same 500 sampled travelers from the population of 16,822. [Day1.gif](Day1.gif) is labeled log Day0, [Day50.gif](Day50.gif) is labeled log Day50, and [Day100.gif](Day100.gif) is labeled simulation day 100 (log Day99). The filename numbering alone is not a consistent day-index convention.

These videos visualize a subset of travelers. Quantitative results use the full population or fixed target/work cohort appropriate to each measure; the animations do not replace completed-run execution audits.
