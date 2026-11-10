# AgenticMobility operating guide

[Project overview](../README.md) · [Documentation index](README.md) · [Experiments and evaluation](experiments-and-evaluation.md)

This guide explains how to download, build, and run AgenticMobility on your own computer. AgenticMobility runs from a terminal: MATSim executes the traffic simulation, and an optional model service supplies decisions for LLM agents. It does not open a graphical application.

Run all commands below from the project folder unless a step says otherwise. Choose the commands for your terminal: **PowerShell** on Windows, or **Bash** on Linux and macOS.

## 1. Requirements

| Requirement                                 | Purpose                                                      |
| ------------------------------------------- | ------------------------------------------------------------ |
| JDK 25, including `java` and `javac`        | Build and run the project.                                   |
| Git                                         | Download the repository and its history.                     |
| Internet access for the first build         | The included Maven Wrapper downloads Maven and dependencies. |
| A Chat Completions-compatible model service | Required only for LLM travelers or an LLM pricing manager.   |

MATSim runs on the CPU. The model service can be hosted locally or remotely and must be started or provided separately. A basic MATSim run does not need a model service or API key.

## 2. Download and build

Open PowerShell or a terminal in the directory where you want to keep the project:

```bash
git clone https://github.com/Agentic-City-Research/Agentic-Mobility.git
cd Agentic-Mobility
java -version
javac -version
```

Both Java commands should report version 25. Build using the Maven Wrapper included in the repository:

**Windows PowerShell**

```powershell
.\mvnw.cmd -B clean package
```

**Linux or macOS**

```bash
sh ./mvnw -B clean package
```

The first build downloads dependencies. A successful build creates `matsim-example-project-0.0.1-SNAPSHOT.jar` in the project folder. Rebuild after changing Java source code.

To see the available options without starting a simulation:

```bash
java -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --help
```

## 3. Run a basic simulation

Start with a two-day diagnostic run using 100 MATSim agents and no model API calls.

**Windows PowerShell**

```powershell
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment `
  --config scenarios/SiouxFalls-public/config.xml `
  --mode hybrid --policy mnl --llm-agents 0 `
  --demand 100 --sample-persons 100 --days 2 `
  --population-seed 4711 --seed 4711 `
  --output output/quickstart
```

**Linux or macOS**

```bash
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment \
  --config scenarios/SiouxFalls-public/config.xml \
  --mode hybrid --policy mnl --llm-agents 0 \
  --demand 100 --sample-persons 100 --days 2 \
  --population-seed 4711 --seed 4711 \
  --output output/quickstart
```

`--llm-agents 0` leaves traveler decisions to MATSim, so this run does not call a model. `--sample-persons 100` is an installation check and does not scale road capacity. For research comparisons, use the [sampling and capacity guidance](scenarios-and-data.md#sampling-and-capacity).

Open `morning/status.json` inside the actual output directory shown in the console. For a successful two-day run, check `complete: true`, `daysCompleted: 2`, and `integrityVerified: true`. The file contains additional fields. If `output/quickstart` already exists, the application creates a numbered alternative; use the path printed in the console.

## 4. Connect a model service

To run LLM agents, obtain these values from your model provider or the operator of your model server:

| Variable | Value |
| --- | --- |
| `AGENTIC_BASE_URL` | API root for a Chat Completions-compatible service, including any required prefix such as `/v1`. |
| `AGENTIC_MODEL` | Exact model ID accepted by the service. |
| `AGENTIC_API_KEY` | Non-empty credential accepted by the service. |

Set them in the same terminal that will start the simulation. Replace each placeholder with your own service settings.

**Windows PowerShell**

```powershell
$env:AGENTIC_BASE_URL = 'https://YOUR_API_HOST/v1'
$env:AGENTIC_MODEL = 'YOUR_MODEL_ID'
$env:AGENTIC_API_KEY = 'YOUR_API_KEY'
```

**Linux or macOS**

```bash
export AGENTIC_BASE_URL='https://YOUR_API_HOST/v1'
export AGENTIC_MODEL='YOUR_MODEL_ID'
export AGENTIC_API_KEY='YOUR_API_KEY'
```

The application sends requests to the service's `/chat/completions` endpoint. Use the provider's API root and exact model ID; `127.0.0.1` refers to the same computer running Java. The program does not automatically load `.env` files; [`.env.example`](../.env.example) documents optional variables. Keep credentials out of committed files and shared logs.

For an optional connection test and provider-specific settings, see the [API connection guide](getting-started.md#5-connect-an-existing-model-service).

## 5. Run LLM traveler agents

After configuring the service, run the following seven-day example. LLMs control the eligible target trips of three people in a 10% population sample; other trips follow MATSim behavior.

**Windows PowerShell**

```powershell
java -Xmx8g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment `
  --config scenarios/SiouxFalls-public/config.xml `
  --mode hybrid --demand 100 --sample-rate 0.1 `
  --population-seed 4711 --seed 4711 --days 7 `
  --policy llm --llm-agents 3 --concurrency 3 `
  --thinking omit --response-format omit --max-requests 500 `
  --output output/first-llm-travelers
```

**Linux or macOS**

```bash
java -Xmx8g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment \
  --config scenarios/SiouxFalls-public/config.xml \
  --mode hybrid --demand 100 --sample-rate 0.1 \
  --population-seed 4711 --seed 4711 --days 7 \
  --policy llm --llm-agents 3 --concurrency 3 \
  --thinking omit --response-format omit --max-requests 500 \
  --output output/first-llm-travelers
```

Check the printed output path and its `morning/status.json` for `complete: true`, `daysCompleted: 7`, and `integrityVerified: true`. The number of controlled target trips can exceed three because the option specifies people. This run makes live model requests, including scheduled reflection and possible retries; provider charges and limits apply.

`--thinking omit` omits a provider-specific field; it does not disable the model's internal reasoning. `--response-format omit` leaves structured-output enforcement to local validation. See [service compatibility](getting-started.md#service-compatibility-and-limits) if your provider requires different settings.

## 6. Choose an experiment

| Goal | Configuration |
| --- | --- |
| MATSim agents without tolls | `--policy mnl --llm-agents 0` |
| Controlled joint route/departure MNL | `--policy mnl --llm-agents all` |
| LLM traveler agents | `--policy llm --llm-agents 3` or `all` |
| Add an LLM pricing manager | Add `--manager` to a compatible traveler configuration. |
| Use fixed tolls | Supply `--fixed-toll-policy` with a policy JSON file. |

The MNL parameter file is an uncalibrated example; see [MNL setup](mnl-joint.md). Fixed tolls and an LLM manager cannot be enabled together. A manager calls the model for daily reflection from the first day; under default settings, its first possible pricing update is on simulation day 21. A short run therefore checks the manager connection without demonstrating a pricing intervention.

For complete configurations, budgets, baselines, and comparison guidance, see [Experiments and evaluation](experiments-and-evaluation.md). Record the code version, scenario, model, settings, and seeds for research runs.

## Outputs and evaluation

Each run writes its results under the output directory reported in the terminal. Look inside its `morning/` folder:

| File | What it contains |
| --- | --- |
| `status.json`, `progress.json` | Completion, completed days, and progress or failure information. |
| `manifest.json` | Effective configuration, inputs, seeds, and runtime information. |
| `decisions.jsonl.gz` | Selected traveler actions and recorded reasons. |
| `trips.jsonl.gz`, `experiences.jsonl.gz` | Executed trips and personal experience records. |
| `metrics.jsonl.gz` | Daily aggregate outcomes. |
| `memory-updates.jsonl.gz` | Traveler reflection records when enabled. |
| `manager-decisions.jsonl.gz`, `manager-memory-updates.jsonl.gz` | Pricing decisions and manager reflections when the manager is enabled. |
| `api-usage.json` and task-specific attempt files | Model-call counts, reported usage, requests, and responses when applicable. |

The JSONL files are gzip-compressed; individual files depend on enabled roles and output settings. Compare intended decisions with executed trips before interpreting behavior. See [run records](experiments-and-evaluation.md#run-records) for the complete output map.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| `java` or `javac` is missing | Install a full JDK 25 and check `JAVA_HOME` and `PATH`. |
| Build cannot download dependencies | Check network access; use the included Maven Wrapper. |
| JAR or scenario file not found | Build first and run the command from the project folder. |
| API connection refused | Check whether the model service is running and whether the host, port, and API root are reachable. |
| API returns 401/403 or model not found | Check the service credential, permissions, and exact model ID. |
| API returns 404 | Check the API root and resulting `/chat/completions` path. |
| Empty or invalid model output | Inspect attempt records and confirm the service supports the requested settings and output limits. |
| Run is incomplete | Inspect `morning/status.json`, console output, and logs in the actual output directory. |

For further help, include the code version, command, operating system, Java version, and relevant error output when [opening an issue](https://github.com/Agentic-City-Research/Agentic-Mobility/issues). Remove credentials and private data first.

## Learn more

- [Getting started](getting-started.md): model connection details and a first run.
- [Methods and protocols](methods-and-protocols.md): agent roles, information, memory, and pricing.
- [Experiments and evaluation](experiments-and-evaluation.md): configurations, request budgets, and run records.
- [Scenarios and data](scenarios-and-data.md): input files, population sampling, and capacity.
