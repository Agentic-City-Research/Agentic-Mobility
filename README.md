# Agentic MATSim

An unofficial research platform connecting bounded LLM travel agents to MATSim. Sioux Falls is the default scenario; a city-adapter interface keeps the agent loop reusable for later cities.

一个非官方的 MATSim + LLM Agent 开源实验平台。当前直接接入 Sioux Falls，并保留城市适配接口供后续场景扩展。

> Status: research preview (`v0.1`). The default provider is deterministic `mock`, so an accidental launch never creates paid API calls.

## Current scope

- Sioux Falls population, network, facilities and public transport integration.
- Repeated mode and departure-time decisions driven by MATSim feedback.
- Per-agent long-term action memory, controlled exploration, rollback and feasibility guards.
- Deterministic `mock` and generic `openai-compatible` decision providers.
- Auditable decisions, experiences, selected agents, action memory and run manifest.
- `CityScenarioAdapter` extension point for subsequent cities.

The provider proposes only a bounded high-level action. MATSim performs routing, traffic simulation and scoring. Agents cannot change activities, destinations, capacities or the physical network.

## Requirements

- Java 25
- Windows, Linux or macOS
- Maven is not required; Maven Wrapper is included.
- A local copy of the enriched Sioux Falls MATSim scenario.

## Prepare Sioux Falls input

Scenario data is not tracked by Git until its redistribution terms have been confirmed. On the current development machine it is already connected. A new user initializes it from a local copy:

```powershell
.\scenarios\sioux-falls\setup-input.ps1 `
  -SourceDirectory "D:\path\to\siouxfalls-2014"
```

The expected files and scenario citation are documented in [`scenarios/sioux-falls`](scenarios/sioux-falls/README.md).

## Build and verify with Sioux Falls

Use a single iteration for a network-free smoke test:

```powershell
.\mvnw.cmd clean package
$env:AGENTIC_PROVIDER = "mock"
$env:LLM_AGENT_SHARE = "0.0001"
java -jar target\agentic-matsim-0.1.0-SNAPSHOT.jar `
  --config scenarios\sioux-falls\config.xml `
  --iterations 0 `
  --output output\sioux-falls-smoke
```

Running the JAR without arguments uses `scenarios/sioux-falls/config.xml` and its full `0..100` iteration setting.

## Run with an LLM

Keep credentials in environment variables only:

```powershell
$env:AGENTIC_PROVIDER = "openai-compatible"
$env:AGENTIC_API_KEY = "..."
$env:AGENTIC_BASE_URL = "https://api.deepseek.com"
$env:AGENTIC_MODEL = "deepseek-chat"
$env:LLM_AGENT_SHARE = "0.01"
java -jar target\agentic-matsim-0.1.0-SNAPSHOT.jar `
  --config scenarios\sioux-falls\config.xml
```

For the validated 101-iteration recipe, use `scenarios/sioux-falls/run_101_iterations.ps1` after reviewing its expected call count and output path.

## Feedback loop

```text
city scenario -> select agents -> MATSim observation -> memory/policy
      ^                                                  |
      |                                                  v
      +-------- simulate and score <- validated action <- provider
```

Outputs include:

```text
llm-agent-selection.csv
llm-decisions.csv
llm-experiences.csv
llm-action-memory.csv
run-manifest.json
```

## Add another city

For a standard MATSim scenario:

1. Add `scenarios/<city>/config.xml` and keep large or restricted inputs outside Git.
2. Set `AGENTIC_CITY_ADAPTER=generic`.
3. Set `AGENTIC_ALLOWED_MODES`, for example `car,pt,walk,bike`.
4. Run the same application with `--config scenarios/<city>/config.xml`.

If a city has special mode availability, attributes or validation rules, implement [`CityScenarioAdapter`](src/main/java/org/matsim/agentic/scenario/CityScenarioAdapter.java). Built-in adapters are resolved by `CityScenarioAdapters`; external modules can register implementations through Java `ServiceLoader` without modifying the decision core.

## Main configuration

| Environment variable | Default | Meaning |
|---|---:|---|
| `AGENTIC_CITY_ADAPTER` | `sioux-falls` | Built-in or registered city adapter ID |
| `AGENTIC_ALLOWED_MODES` | `car,pt,walk` | Modes used by the `generic` adapter |
| `AGENTIC_PROVIDER` | `mock` | `mock` or `openai-compatible` |
| `AGENTIC_API_KEY` | none | Required only for a remote provider |
| `AGENTIC_BASE_URL` | DeepSeek endpoint | OpenAI-compatible API base URL |
| `AGENTIC_MODEL` | `deepseek-chat` | Provider model name |
| `LLM_AGENT_COUNT` | unset | Explicit controlled-agent count |
| `LLM_AGENT_SHARE` | `0.01` | Controlled share when count is unset |
| `LLM_SAMPLE_SEED` | `20260805` | Reproducible stratified sample seed |
| `LLM_MAX_CONCURRENCY` | `5` | Maximum simultaneous provider calls |
| `LLM_ALLOWED_DEPARTURE_SHIFTS` | `-30,-15,0,15,30` | Bounded action space in minutes |

The versioned system prompt is at `src/main/resources/prompts/travel-decision-v1.txt`. Invalid provider output falls back to the previous or best feasible action.

## Repository map

```text
src/main/java/org/matsim/agentic/api       provider/action API
src/main/java/org/matsim/agentic/provider  mock and remote providers
src/main/java/org/matsim/agentic/scenario  city scenario adapters
src/main/java/org/matsim/llmprocess        MATSim controller and adaptive policy
scenarios/sioux-falls                     default scenario configuration
analysis                                  experiment analysis tools
docs                                      architecture and experiment protocol
```

## Reproducibility and boundaries

Every run writes `run-manifest.json`, including city adapter, MATSim/Java versions, provider/model, seeds, agent count and prompt version. Remote model behavior can vary after provider updates; use `mock` for deterministic software tests.

- This is not an official MATSim project.
- An iteration is an execution-feedback-replanning cycle, not automatically a calendar day.
- `v0.1` exposes mode and departure-time decisions; manager agents are outside the current scope.
- Use a paired conventional MATSim baseline with identical demand and QSim settings for causal comparisons.

## License and citation

Java source is GPL-2.0; see [`LICENSE`](LICENSE). External scenarios retain their original terms and are excluded from Git until redistribution permission is confirmed. Update [`CITATION.cff`](CITATION.cff) with author and repository metadata before the first public release.

See [`CONTRIBUTING.md`](CONTRIBUTING.md) and [`SECURITY.md`](SECURITY.md) before contributing or publishing data.
