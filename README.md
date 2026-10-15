<div align="center">

<h1>Agentic Mobility</h1>

<p><strong>An open research platform for interactions between LLM travelers and traffic managers.</strong></p>


English | [中文](README.zh.md)
</div>

<div align="left">
    
Agentic Mobility is the mobility research project within the 
[AgenticCity](https://github.com/Agentic-City-Research), which
aims to build an open research community around interacting 
agents and interconnected urban systems, starting with transportation.

</div>


<div align="center">
    
🚀[Quickstart](#quickstart) | 🗺️[Documentation](#documentation) |📣[Research questions](#research-questions) |  💬[Community](docs/wechat.md)

</div>

## 🧩Overview

![AgenticMobility framework](docs/assets/mobility-loop-sioux-falls.svg)

AgenticMobility connects LLM travelers and traffic managers 
through a shared transportation environment to study decisions,
memory, policy responses, and collective outcomes. 

The current v1 implementation supports joint route and departure-time
choice, daily reflection, and periodic congestion pricing in a full-day
**enriched Sioux Falls MATSim simulation**, alongside native traffic.


## 🔄How it works

Travelers choose actions using personal information, recent
experience, private memory, and policy information available
to their assigned group. The manager uses public traffic
aggregates, policy history, and its own memory.

```mermaid
flowchart LR
    A["<div style='width:200px'>🧩 Prepare once<br/>Population · groups<br/>Candidate actions</div>"]
    B["<div style='width:200px'>🧭 Decide<br/>LLM travel choices · scheduled pricing</div>"]
    C["🚗 Execute<br/>Full-day traffic simulation"]
    D["🪞 Reflect<br/>Separate role memories"]

    A --> B --> C --> D
    D -->|Next simulation day| B

    style A fill:#f1f5f9,stroke:#94a3b8,color:#2563eb
    style B fill:#eff6ff,stroke:#60a5fa,color:#2563eb
    style C fill:#f0fdfa,stroke:#2dd4bf,color:#2563eb
    style D fill:#faf5ff,stroke:#c084fc,color:#2563eb
```

LLM control applies to selected target trips. Other trips retain native behavior. Adaptation uses model inference and memory updates; model weights remain unchanged.

## 🎯Research questions

| Research focus | Experiment setup |
| :--- | :--- |
| 📊 **Traveler adaptation** | Native baseline vs. LLM-controlled target trips |
| 🔎 **Information access** | Informed vs. uninformed controlled travelers |
| 🚦 **Policy comparison** | Native baseline vs. fixed tolls vs. LLM pricing |
| 🔄 **Traveler–manager interaction** | LLM travelers with vs. without an LLM manager |

Comparisons require matched population, scenario, and background-traffic settings; detailed configurations are documented separately.

[Fixed tolls](scenarios/SiouxFalls-public/fixed-tolls.md) and replay of saved traveler contexts provide additional comparisons. See the [experiment guide](docs/usage-guide.md#experiments) for commands and settings.

## 📦Quickstart

Requires **JDK 25** (including `javac`) and Git. The Maven Wrapper downloads Maven and dependencies on first use.

```bash
git clone https://github.com/Agentic-City-Research/Agentic-Mobility.git
cd Agentic-Mobility
sh ./mvnw -B clean package
```

On Windows PowerShell, build with `.\mvnw.cmd -B clean package`.

Run a two-day, 100-person diagnostic baseline with **no model calls**:

```bash
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment \
  --config scenarios/SiouxFalls-public/config.xml \
  --mode hybrid --policy mnl --llm-agents 0 \
  --demand 100 --sample-persons 100 --days 2 --seed 4711 \
  --output output/quickstart
```

In the output directory reported by the run, inspect `morning/status.json` for `complete=true` and `daysCompleted=2`. Diagnostic sampling does not scale road capacity; use the documented sampling configuration for research experiments.

For [LLM travelers](docs/usage-guide.md#llm-travelers), [manager experiments](docs/usage-guide.md#native-travelers--manager), and [joint experiments](docs/usage-guide.md#llm-travelers--manager), configure a reachable Chat Completions service and follow the operating guide. MATSim runs on CPU; the repository calls an existing model service.

## 🧪Experiment status

| Area | Current status |
| --- | --- |
| Implementation | Traveler decisions, private memory, manager policies, and execution records are implemented. |
| Evaluation | Exploratory runs are under analysis; multi-seed comparisons are being prepared. |
| Manuscript | In preparation. |

Verified configurations and results will be published with their code versions, model settings, seeds, and evaluation procedures.

Each run records its manifest, completion status, decisions, trips, policies, memories, and API usage. See [outputs and evaluation](docs/usage-guide.md#outputs-and-evaluation) for record definitions and metric scopes.

Sioux Falls is a synthetic research scenario. Behavioral realism, convergence, and policy benefits require experimental evidence beyond successful execution.

## 📖Documentation

| Guide | What you will find |
| :--- | :--- |
| [Getting started](docs/README.md#getting-started) | Installation, first run, and troubleshooting |
| [Methods & protocols](docs/README.md#methods--protocols) | Agent roles, information access, memory, and pricing |
| [Experiments & evaluation](docs/README.md#experiments--evaluation) | Experiment settings, baselines, outputs, and metrics |
| [Scenarios & data](docs/README.md#scenarios--data) | Scenario inputs, data provenance, and extensions |

## 🤝Community & Contributing

We welcome contributions from researchers and developers working on agents, transportation, simulation, and policy evaluation:

- Join Discord to share your recipes, ask implementation questions, and discuss new features.
- [Join the WeChat group](docs/wechat.md): the group is full, so add the assistant and it will invite you.

If Agentic Mobility looks useful to you, please give it a ⭐ — it helps the community to discover and contribute to the project.

## 📚Citation and license

For research use, reference [Agentic-City-Research/Agentic-Mobility](https://github.com/Agentic-City-Research/Agentic-Mobility) and report the exact commit, scenario, model, and experimental settings. Manuscript information will be added when publicly available.

Code is licensed under [GPL-2.0](LICENSE). Third-party code and data retain their documented terms in [THIRD_PARTY.md](THIRD_PARTY.md). Built on MATSim and its example-project lineage.
