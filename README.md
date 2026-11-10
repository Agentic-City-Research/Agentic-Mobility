<div align="center">

<h1>Agentic Mobility</h1>

<p><strong>Exploring AI Agents and Emergent Dynamics in Mobility Systems</strong></p>


<p>
  <a href="#quickstart"><img src="https://img.shields.io/badge/Java-25-blue?style=flat" alt="Requires Java 25"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--2.0-blue?style=flat" alt="License: GPL-2.0"></a>
</p>

Part of [AgenticCity](https://github.com/Agentic-City-Research), exploring AI agents and interconnected urban systems.

[Paper](https://arxiv.org/abs/2611.01918) · [Quickstart](#quickstart) · [Experiments](#experiments) · [Documentation](#documentation) · [Contributing](#community--contributing)

</div>


<a id="overview"></a>

## Overview 🧩

Agentic Mobility is a research platform for studying AI agents and collective dynamics in mobility systems. Traveler agents select routes and departure times; optional pricing policies change travel incentives. MATSim executes these decisions in a shared traffic environment, where one agent's choices can affect conditions experienced by others.

![AgenticMobility framework](docs/assets/agenticmobility_framework_siouxfalls.svg)
### Daily simulation cycle

Each simulation day connects decisions, traffic execution, and feedback. Agents receive information permitted by their configuration; reflection and pricing updates follow configurable schedules. LLMs control selected target trips, while other trips follow MATSim behavior. Model weights remain fixed during the simulation.

The platform supports MATSim agents, constrained MNL, and LLM traveler agents, with no tolls or an LLM pricing manager. See [Methods & protocols](docs/methods-and-protocols.md) for information access, decision constraints, reflection, and policy execution.

<a id="research-questions"></a>
## What you can study 🎯

| Research task         | What you can vary                     | What you can inspect                          |
| :-------------------- | :------------------------------------ | :-------------------------------------------- |
| Traveler decisions    | MATSim, MNL, or LLM decision policies | Routes, departure times, and arrival outcomes |
| Information access    | Access to current toll announcements  | Route use, payments, and individual traces    |
| Pricing interventions | No tolls or an LLM pricing manager    | Individual and system-level outcomes          |

Explore the [experiment guide](docs/experiments-and-evaluation.md) for configurations, evaluation, and run records. Information-access effects and causal interactions between traveler and pricing agents remain open research questions.

<a id="experiments"></a>
## Experiments 🧪

The following historical experiment connects individual decisions with outcomes across a shared mobility system.

### Five configurations over 100 simulation days

**Historical results:** these figures describe the recorded experiment builds and settings. Current code and defaults differ; consult the [experiment record](docs/assets/data/seed268/seed268-experiments.md) and original build identifiers before attempting reproduction.

We compare five configurations of the enriched Sioux Falls scenario over **Days 1–100**, using **simulation seed 268**. All share the same scenario inputs, sampled population and target-trip cohort. Constrained MNL and both LLM traveler configurations share fixed route and departure-time options. Traveler agents use `qwen3.5-9b`; pricing agents use `deepseek-flash`. 

| Configuration | Traveler decisions | Pricing agent |
| :--- | :--- | :--- |
| MATSim agents | MATSim replanning | None |
| Constrained MNL | MNL over fixed route–departure options | None |
| LLM traveler agents | LLM route–departure choices | None |
| Manager | MATSim replanning | Enabled; tolls from simulation Day 21 (log Day20) |
| Joint | LLM route–departure choices | Enabled; tolls from simulation Day 51 (log Day50) |

For the historical MNL configuration and its provenance, see the [experiment record](docs/joint-mnl-baseline.md). For the current implementation, see [MNL setup](docs/mnl-joint.md).

### Traffic replay

Compare **MATSim agents**, **LLM traveler agents**, and **LLM traveler agents with a pricing manager** at three simulation days. Each animation follows the same 500 sampled travelers from the 16,822-person population. Quantitative results use the full population relevant to each metric.

**Final day — simulation Day 100**

<p align="center">
  <img src="docs/assets/Day100.gif"
       alt="Traffic replay on simulation Day 100: comparison of three configurations"
       width="100%">
</p>

<details>
<summary>View the initial and intermediate snapshots</summary>

#### Initial snapshot — simulation Day 1 (log Day 0)

<p align="center">
  <img src="docs/assets/Day1.gif"
       alt="Traffic replay on simulation Day 1: comparison of three configurations"
       width="100%">
</p>

#### Intermediate snapshot — simulation Day 51 (log Day 50)

<p align="center">
  <img src="docs/assets/Day50.gif"
       alt="Traffic replay on simulation Day 51: comparison of three configurations"
       width="100%">
</p>

</details>

### Individual and system outcomes

**LLM traveler agents record the lowest all-day car time, 19.90% below MATSim agents. Joint records the shortest target-trip time and lowest work late-arrival rate.**

| Configuration | Daily car time (min/person/day) | Change vs. MATSim agents | Target-trip car time (min/trip) | Work late-arrival rate |
| :--- | ---: | ---: | ---: | ---: |
| MATSim agents | 44.34 | — | 29.32 | 19.97% |
| Constrained MNL | 61.35 | +38.37% | 53.19 | 31.25% |
| LLM traveler agents | **35.52** | −19.90% | 17.40 | 5.61% |
| Manager | 45.16 | +1.86% | 29.64 | 20.42% |
| Joint | 40.71 | −8.18% | **16.25** | **3.73%** |

All-day car time is normalized to min/person by dividing the daily person-hours total by the 16,822 simulated travelers and converting to minutes. 

Daily means cover **11,793 target trips**, including **11,332 work trips with arrival deadlines**, and **33,644 all-day car legs** across **16,822 people**.
All car legs completed, with no stuck agents. Car time includes waiting before network entry; target trips cover multiple activity purposes. Contrasts use unrounded means.

[![Five configurations: mobility outcomes over Days 1–100](docs/assets/seed268/outcomes.png)](docs/assets/seed268/outcomes.svg)

Thin lines show daily values; thick lines show trailing seven-day means. Figure markers use zero-based log days: toll activation at Day20 for Manager and Day50 for Joint, a policy review on Day80, and the MATSim route/time innovation cutoff on Day90. These are simulation days 21, 51, 81, and 91 respectively; existing-plan selection continues after the cutoff.

Compared with LLM traveler agents alone, **Joint has 6.63% shorter target-trip time and 1.87 percentage points less work lateness, alongside 14.62% more all-day car time**. Differences already exist before Joint tolling.

<details>
<summary>Behavioral patterns and outcome breakdowns</summary>

Target trips save 226.73 person-hours/day, while other car trips add 1,682.97. Across individual targets, 60.65% have lower 100-day mean car time in Joint and 39.28% have higher time.

Route persistence coexists with schedule adjustment: LLM traveler agents and Joint change routes in **3.08% / 2.19%** of adjacent-day target decisions, but change departure times in **29.45% / 28.04%**. Their mean departures are **34.96 / 39.65 minutes earlier** than the original plans.

</details>

### Zoom in on individual decisions

Two recorded examples connect available information, selected actions, generated rationales and realized outcomes.

<details>
<summary>LLM traveler agent: switching routes after a late arrival</summary>

Traveler `1005_1`, log Day 65 (simulation Day 66); LLM traveler agents configuration.

| Stage | Recorded example |
| :--- | :--- |
| Available information | Four routes, nine departure times, a 09:00 arrival target, and personal trips from log Days 62–64; no network traffic information. |
| Selected action | `c1` at **08:12:47**, the same departure time as the previous day. |
| Recorded rationale | “I chose route c1 to avoid the inconsistency of c0 while ensuring I meet the 09:00 deadline on time.” |
| Realized outcome | **7.85 min** car time; destination arrival **08:41:33**. The previous day's `c0` trip took **31.28 min**, arriving **4 min 59 s late**. |

Both choices were executed as recorded. Shared conditions also varied between days.

</details>

<details>
<summary>Joint: trying different routes after delays</summary>

Traveler `20336_4` keeps the original **07:49:58** departure across log Days **21–26** (zero-based; simulation Days 22–27), with a **09:00** arrival target. The traveler belongs to the uninformed group. This sequence precedes Joint toll activation.

| Log day | Route | Car time (min:sec) | Destination arrival | Arrival status |
| :--- | :--- | ---: | :--- | :--- |
| 21 | c0 | 14:17 | 08:16:59 | On time |
| 22 | c0 | 65:35 | 09:08:17 | 8:17 late |
| 23 | c1 | 97:43 | 09:40:25 | 40:25 late |
| 24 | c2 | 37:55 | 08:40:37 | On time |
| 25 | c2 | 87:52 | 09:30:34 | 30:34 late |
| 26 | c0 | 64:00 | 09:06:42 | 6:42 late |

After a late `c0` trip, the traveler tries `c1` and arrives later still. On log Day 24, the recorded rationale describes `c0` as unreliable and `c1` as too slow; the selected `c2` trip arrives on time. The next `c2` trip is late, followed by a return to `c0` that is also late.

All six selected routes and departure times match execution. Log Day 26's rationale calls `c0` “untested”, although it was used earlier. Car time includes pre-network waiting; this sequence shows changing choices and outcomes, without isolating a route or memory effect.

</details>

[Case records and execution checks →](docs/assets/data/seed268/seed268-individual-cases.md)

### Current toll information and route choice

In Joint, **5,897 travelers receive current toll announcements and 5,896 do not**; both groups receive personal trip feedback, and neither receives network-wide traffic information. After charging starts, use of the two tolled links falls from **6.67% to 2.49%** among informed travelers, versus **6.54% to 6.94%** among uninformed travelers. Informed travelers pay **64.15% less per target trip**, while uninformed travelers depart earlier and record shorter travel times.

<details>
<summary>Information-group outcomes and route-use trajectories</summary>

| Measure | Informed | Uninformed |
| :--- | ---: | ---: |
| Use of subsequently tolled links, Days 1–50 | 6.67% | 6.54% |
| Use of tolled links, Days 51–100 | 2.49% | 6.94% |
| Mean target payment, Days 51–100 (scenario units/trip) | 0.075 | 0.208 |
| Target car time, Days 51–100 (min/trip) | 17.02 | 15.17 |
| Work late-arrival rate, Days 51–100 | 3.81% | 2.50% |

[![Use of subsequently tolled links by information group, in Joint and the no-toll traveler configuration](docs/assets/seed268/information_route_response.png)](docs/assets/seed268/information_route_response.svg)

Links are identified retrospectively before charging and in the no-toll comparison. Payments include zero-payment trips. Group time differences already exist before charging; these observations do not isolate a toll-information treatment effect.

</details>

**Interpretation.** The main comparison contains one run per configuration. MNL uses uncalibrated homogeneous parameters, and some executable builds differ. Shared traffic and pre-existing differences limit causal attribution and claims of traveler–pricing synergy.

**[Experiment settings, data, behavioral results and validation →](docs/assets/data/seed268/seed268-experiments.md)**

<br>

<a id="quickstart"></a>
## Quickstart 🚀

Build the project, run a small MATSim baseline, and check that it completes. Then, optionally connect a model service to run LLM-controlled travelers.

**Prerequisites:** JDK 25 (including `javac`), Git, and network access for the first build. The Maven Wrapper downloads Maven and dependencies. The MATSim baseline requires no model API.

Confirm that both commands report version 25:

```text
java -version
javac -version
```

### 1. Get the source and build

**Bash (Linux/macOS)**

```bash
git clone https://github.com/Agentic-City-Research/Agentic-Mobility.git
cd Agentic-Mobility
sh ./mvnw -B clean package
```

<details>
<summary>Windows PowerShell</summary>

```powershell
git clone https://github.com/Agentic-City-Research/Agentic-Mobility.git
cd Agentic-Mobility
.\mvnw.cmd -B clean package
```

</details>

The build creates `matsim-example-project-0.0.1-SNAPSHOT.jar` in the repository root. Run the simulation commands below from that directory.

### 2. Run a MATSim baseline

Run a two-day diagnostic simulation with 100 MATSim travelers and no model API calls.

**Bash (Linux/macOS)**

```bash
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment \
  --config scenarios/SiouxFalls-public/config.xml \
  --mode hybrid --policy mnl --llm-agents 0 \
  --demand 100 --sample-persons 100 --days 2 \
  --population-seed 4711 --seed 4711 \
  --output output/quickstart
```

<details>
<summary>Windows PowerShell command</summary>

```powershell
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment `
  --config scenarios/SiouxFalls-public/config.xml `
  --mode hybrid --policy mnl --llm-agents 0 `
  --demand 100 --sample-persons 100 --days 2 `
  --population-seed 4711 --seed 4711 `
  --output output/quickstart
```

</details>

### 3. Confirm the run completed

Use the output directory reported in the console (normally `output/quickstart`). If that directory already exists, the application creates a numbered alternative.

Open `<output-directory>/morning/status.json` and check that it contains:

```json
{
  "complete": true,
  "daysCompleted": 2,
  "integrityVerified": true
}
```

The status file also contains additional run information.

This 100-person example checks installation and execution. The `--sample-persons` option reduces the population without scaling road capacity. For research experiments, follow the [sampling and capacity guidance](docs/scenarios-and-data.md#sampling-and-capacity). To run controlled joint MNL instead, use `--policy mnl --llm-agents all`; the default parameter file is an uncalibrated engineering example, not an empirical estimate. See [MNL setup](docs/mnl-joint.md).

### 4. Optional: run an LLM traveler experiment

1. Follow the [API connection guide](docs/getting-started.md#5-connect-an-existing-model-service) to set `AGENTIC_BASE_URL`, `AGENTIC_MODEL`, and `AGENTIC_API_KEY` for a Chat Completions-compatible service.
2. Follow the [first LLM traveler experiment](docs/getting-started.md#start-with-a-small-traveler-run) to run seven simulation days with the eligible target trips of three LLM-controlled people in a 10% population sample. Bash and Windows PowerShell examples are provided.

**[Inspect your agent decisions and outcomes →](docs/usage-guide.md#outputs-and-evaluation)**

In the reported output directory, `morning/decisions.jsonl.gz` records choices, while `morning/trips.jsonl.gz` records realized trips. See the output guide for the other records. MATSim runs on CPU; the model service can be hosted on another machine. The same API setup is required for an LLM pricing agent.

For installation, troubleshooting, and additional instructions, see the [operating guide](docs/usage-guide.md) or its [Chinese version](docs/usage-guide.zh.md).

<a id="documentation"></a>

## Documentation 📖

New to the project? Start with [Getting started](docs/getting-started.md). For research experiments, check the scenario and sampling settings, review the methods, then use the experiment guide to configure and record your runs.

| I want to… | Read | What it covers |
| :--- | :--- | :--- |
| Install and run my first simulation | [Getting started](docs/getting-started.md) | Requirements, diagnostic baseline, model API setup, and troubleshooting |
| Choose data and set the simulation scale | [Scenarios & data](docs/scenarios-and-data.md) | Scenario inputs, data provenance, population sampling, and road capacity |
| Understand how the agents work | [Methods & protocols](docs/methods-and-protocols.md) | Agent roles, information access, decisions, memory, and pricing |
| Inspect agent prompts | [Agent prompts (Chinese)](docs/agent-prompts.zh.md) | System prompts, dynamic inputs, and request logs |
| Design and evaluate an experiment | [Experiments & evaluation](docs/experiments-and-evaluation.md) | Configuration templates, baselines, request budgets, and run records |
| Inspect the five-configuration experiment | [Experiment record](docs/assets/data/seed268/seed268-experiments.md) | Settings, results, provenance, and available data |
| Inspect earlier exploratory results | [Reported experiments](docs/reported-experiments.md) | Separate earlier comparisons, model settings, and aggregate data |
| Find detailed operating instructions | [Operating guide](docs/usage-guide.md) | Execution options, deployment, outputs, and troubleshooting |

Browse the [documentation index](docs/README.md) for all guides.

<a id="community--contributing"></a>

## Community & Contributing 🤝

Contributions are welcome, including decision policies, reproducible examples, simulation scenarios, and tools for inspecting agent trajectories.

[Report an issue](https://github.com/Agentic-City-Research/Agentic-Mobility/issues) · [Contribution guide](CONTRIBUTING.md) · [Contributors](https://github.com/Agentic-City-Research/Agentic-Mobility/graphs/contributors)

⭐ If you find Agentic Mobility useful, consider starring the repository.

## Citation & License 📚

If you use Agentic Mobility in research, please cite the [paper](https://arxiv.org/abs/2611.01918) and report the exact code version, scenario, model, and experimental settings.

```bibtex
@article{xiao2026agenticmobility,
  title   = {AgenticMobility: An Open-Source Platform for Multi-AI-Agent Mobility Dynamics},
  author  = {Xiao, Jiaheng and Han, Jiangtao and Wang, Jiachen and Li, Honghai and Sun, Lijun and Axhausen, Kay and Liu, Chang and He, Zhengbing},
  journal = {arXiv preprint arXiv:2611.01918},
  year    = {2026},
  url     = {https://arxiv.org/abs/2611.01918}
}
```

Code is licensed under [GPL-2.0](LICENSE). Third-party code and data retain their documented terms in [THIRD_PARTY.md](THIRD_PARTY.md). Built on MATSim and its example-project lineage.
