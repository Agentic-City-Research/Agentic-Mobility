# Getting started

[Documentation](README.md) · [Methods & protocols](methods-and-protocols.md) · [Experiments & evaluation](experiments-and-evaluation.md) · [Scenarios & data](scenarios-and-data.md)

This guide takes you from a source checkout to a small native traffic run, then to an optional LLM-traveler run. All commands run from the repository root. The examples are setup instructions; they do not report experimental results.

For the current paper, see [Reported experiments](reported-experiments.md) and the [manuscript configuration mapping](experiments-and-evaluation.md#manuscript-configuration-mapping). The paper uses five configurations, seed 268, population seed 6021, a 20% sample of demand 100, and 100 simulated days. The diagnostic seed 4711 and the small examples below are deliberate setup choices. The [data inventory](data/README.md) identifies the earlier aggregate exports and their available windows.

## 1. Check requirements

| Requirement | Used for |
| :--- | :--- |
| JDK 25, including `javac` | Compiling and running the project. |
| Git and access to the repository | Obtaining the source and recording its revision. |
| Network access for the first build | Downloading Maven and dependencies through the bundled Maven Wrapper. |
| A reachable Chat Completions model service | Live LLM travelers or an LLM manager. A native run needs no service. |
| Python 3 or PowerShell | Optional analysis tools, replay scripts, and deployment helpers. |

MATSim executes on the CPU. The application calls an existing model service, which may run on another machine. Model serving is configured separately.

## 2. Obtain and build the source

```bash
git clone https://github.com/Agentic-City-Research/Agentic-Mobility.git
cd Agentic-Mobility
java -version
javac -version
```

Both Java commands should identify JDK 25. If the repository is private, authenticate with an account that has access before cloning.

Build with the command for your shell:

**Bash, Linux/macOS**

```bash
sh ./mvnw -B clean package
```

**Windows PowerShell**

```powershell
.\mvnw.cmd -B clean package
```

The build places `matsim-example-project-0.0.1-SNAPSHOT.jar` in the **repository root**. Rebuild it after Java source changes. Build requirements and the JAR location are defined in [`pom.xml`](../pom.xml).

Inspect the available CLI without starting traffic:

```bash
java -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --help
```

## 3. Validate inputs first

This single-line command works in Bash and PowerShell:

```text
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --config scenarios/SiouxFalls-public/config.xml --mode hybrid --policy mnl --llm-agents 0 --demand 100 --sample-persons 100 --days 2 --population-seed 4711 --seed 4711 --validate-only --output output/input-check
```

`--validate-only` loads the scenario, checks configuration, prepares the cohort and candidates, and exports preparation records. It runs neither QSim nor model calls.

The console reports the actual output path. Inspect:

- `effective-config.xml` in the run directory.
- `morning/manifest.json` for effective settings and inputs.
- `morning/cohort.jsonl.gz` and `morning/candidates.jsonl.gz` for prepared target trips and routes.
- `morning/status.json` for `validated=true`, `simulationRun=false`, and `apiCalls=0`.

A preparation-only status describes input validation. It does not have the same completion meaning as a finished traffic simulation.

## 4. Run a small native baseline

**Bash**

```bash
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment \
  --config scenarios/SiouxFalls-public/config.xml \
  --mode hybrid --policy mnl --llm-agents 0 \
  --demand 100 --sample-persons 100 --days 2 \
  --population-seed 4711 --seed 4711 \
  --output output/quickstart
```

**Windows PowerShell**

```powershell
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment `
  --config scenarios/SiouxFalls-public/config.xml `
  --mode hybrid --policy mnl --llm-agents 0 `
  --demand 100 --sample-persons 100 --days 2 `
  --population-seed 4711 --seed 4711 `
  --output output/quickstart
```

With `--llm-agents 0` and no manager, all travelers retain native behavior and there are no model calls. The `mnl` policy does not replace native MATSim choices when the controlled set is empty.

This 100-person example is a diagnostic run. `--sample-persons` reduces population **without capacity scaling**. For research configurations, use the documented [`--sample-rate` procedure](scenarios-and-data.md#sampling-and-capacity), and keep demand and capacity settings explicit.

### Check completion

Use the output directory printed by the application. If the requested name already exists, the application reserves a numbered alternative such as `output/quickstart-2`.

A successfully completed two-day traffic run should report `complete=true` and `daysCompleted=2` in `morning/status.json`. Also check the process exit status and console/MATSim logs. A partial directory alone does not establish completion.

For example, in PowerShell:

```powershell
$MobilityRun = 'output/quickstart' # Replace with the path reported by the run.
Get-Content -LiteralPath "$MobilityRun/morning/status.json" -Raw | ConvertFrom-Json
```

In Bash:

```bash
cat output/quickstart/morning/status.json
```

The main records are described in [Experiments & evaluation](experiments-and-evaluation.md#run-records). JSONL archives use UTF-8 gzip compression.

## 5. Connect an existing model service

AgenticMobility calls a **Chat Completions-compatible HTTP API**. Once the service is configured, the application builds prompts, sends requests, validates responses, and records usage automatically. You do not need to write a separate API-calling program to run an experiment. The Java HTTP client is included in the built JAR.

### Obtain the three connection values

You can use a hosted model API or an already running local/remote model server that exposes the required interface. Obtain the connection values from the API provider or the person operating the server:

| Variable | What to supply | Where it comes from |
| :--- | :--- | :--- |
| `AGENTIC_API_KEY` | A non-empty service credential accepted by the model endpoint. | The provider's API-key settings or your server administrator. This is separate from a GitHub access token. |
| `AGENTIC_MODEL` | The exact model ID accepted by that service. | The provider's model documentation or your server's configured model ID. A local model-directory name is not necessarily the served ID. |
| `AGENTIC_BASE_URL` | The service's API root, including its required prefix such as `/v1`. | The provider's endpoint instructions or your server's listening address and API path. |

For example, an API root of `http://127.0.0.1:8001/v1` produces a request to `http://127.0.0.1:8001/v1/chat/completions`. Use the API root rather than a chat website, API-key management page, or model-download URL. The prefix required by a service is part of its configuration; do not assume every service uses the same URL path.

For a local service, obtain the credential it accepts. The application requires a non-empty `AGENTIC_API_KEY` for live LLM runs, including when connecting to a server with optional authentication.

### Set the environment

Set the values in the same shell that launches Java:

**Bash**

```bash
export AGENTIC_API_KEY='YOUR_MODEL_API_KEY'
export AGENTIC_MODEL='YOUR_SERVED_MODEL_NAME'
export AGENTIC_BASE_URL='http://127.0.0.1:8001/v1'
```

**Windows PowerShell**

```powershell
$env:AGENTIC_API_KEY = 'YOUR_MODEL_API_KEY'
$env:AGENTIC_MODEL = 'YOUR_SERVED_MODEL_NAME'
$env:AGENTIC_BASE_URL = 'http://127.0.0.1:8001/v1'
```

Replace the placeholders with your service settings. `127.0.0.1` works only when Java and the service are on the same host; otherwise use the reachable service address. [`.env.example`](../.env.example) documents the variables, but the application does **not** automatically load `.env` files.

Keep credentials in the environment, outside committed configuration files. The CLI can override the model and endpoint with `--model` and `--base-url`; the key is read from `AGENTIC_API_KEY`.

### Check the API independently

The following optional check sends **one live generation request** without MATSim. It is separate from the application's request budget and experiment logs. Use the environment values configured above.

**Bash with curl**

```bash
curl --fail --silent --show-error --max-time 300 \
  "${AGENTIC_BASE_URL%/}/chat/completions" \
  -H "Authorization: Bearer $AGENTIC_API_KEY" \
  -H 'Content-Type: application/json' \
  --data @- <<EOF
{
  "model": "$AGENTIC_MODEL",
  "messages": [
    {"role": "system", "content": "Answer briefly."},
    {"role": "user", "content": "Reply with OK."}
  ],
  "max_tokens": 128,
  "stream": false
}
EOF
```

**Windows PowerShell**

```powershell
$MobilityApiUri = "$($env:AGENTIC_BASE_URL.TrimEnd('/'))/chat/completions"
$MobilityApiBody = @{
  model = $env:AGENTIC_MODEL
  messages = @(
    @{ role = 'system'; content = 'Answer briefly.' },
    @{ role = 'user'; content = 'Reply with OK.' }
  )
  max_tokens = 128
  stream = $false
} | ConvertTo-Json -Depth 6

$MobilityApiResponse = Invoke-RestMethod -Method Post -Uri $MobilityApiUri `
  -Headers @{ Authorization = "Bearer $env:AGENTIC_API_KEY" } `
  -ContentType 'application/json' -Body $MobilityApiBody -TimeoutSec 300

$MobilityApiResponse.choices[0].message.content
```

If the service requires `max_completion_tokens`, replace `max_tokens` in the diagnostic request. The corresponding application setting is `--token-limit-field max_completion_tokens`. Any additional service-required generation fields must also be configured according to that service's interface.

A compatible non-streaming response includes `choices[0].message.content`. This is an illustrative response shape:

```json
{
  "choices": [
    {
      "message": {"role": "assistant", "content": "OK"},
      "finish_reason": "stop"
    }
  ]
}
```

The content need not match `OK` exactly. Confirm a successful response and non-empty message content; if content is empty or output is truncated, inspect the service response and generation requirements. This check verifies connectivity, credentials, and basic text generation. The small traveler run below also checks the experiment's structured choice and memory workflow.

### Which experiment stages call the API?

| Task | When it calls the model | Expected output |
| :--- | :--- | :--- |
| Traveler choice | Once per controlled target trip per simulation day, before execution, plus allowed output retries. | A legal route ID, departure time, and short reason. |
| Traveler reflection | After that target trip's daily execution feedback, plus allowed output retries. | A replacement private summary. |
| Manager policy | On scheduled update days, beginning on day 21 by default, plus allowed output retries. | Complete toll rules and a structured decision. |
| Manager reflection | After every completed simulation day, including the initial free period, plus allowed output retries. | A replacement manager memory. |
| Optional traveler review | When explicitly enabled, at most once per structurally legal choice or reflection proposal. | Review status and permitted text revisions. |

The current entry point shares one model ID, endpoint, credential, and sampling configuration across enabled tasks. It provides separate output-token limits for traveler choice, traveler memory, manager policy, manager memory, and optional review. These are current-interface capabilities; historical run-specific model/service settings must be taken from the corresponding archive rather than inferred from this example or from a model ID in a different run.

Native-only runs and `--validate-only` make no model calls. When an LLM role is enabled, the application invokes the relevant tasks automatically. Full request construction and response parsing are implemented in [`DeepSeekClient`](../src/main/java/org/matsim/agentic/DeepSeekClient.java); despite its name, the client is configured by the endpoint and model rather than tied to one provider.

### Service compatibility and limits

| Setting | Purpose |
| :--- | :--- |
| `--thinking omit` | Omit the provider-specific thinking field. This does not itself disable internal model reasoning. |
| `--response-format omit` | Use normal API generation with local strict output validation. |
| `--response-format json_schema` | Send the task's dynamic schema when supported by the service. |
| `--token-limit-field max_tokens` or `max_completion_tokens` | Select the output-limit field expected by the service. |
| `--generation-params` | Supply supported extra request fields as a JSON object. Reserved fields cannot be overridden through this option. |
| `--timeout-seconds` | Per-request full-response deadline; default 300 seconds unless overridden through the environment or CLI. |
| `--llm-stall-seconds` | No-completion stall threshold for model batches; default 600 seconds unless overridden. |
| `--max-requests` | Shared application client-call cap; plan it using the enabled tasks and controlled-trip count. |

Traveler decisions default to 1,024 output tokens, traveler memory to 1,024, manager policy to 8,192, manager memory to 2,048, and optional review to 2,048, subject to the documented overrides. The service's context window must accommodate the input, chat-template overhead, and requested output together. Use parameters actually supported by the chosen service.

Inspect `morning/api-usage.json` and the task-specific attempt archives for calls and reported usage. Standalone curl/PowerShell checks are outside those records. For budget calculations and the complete logging map, see [request budgets](experiments-and-evaluation.md#request-budgets) and [run records](experiments-and-evaluation.md#run-records).

### Start with a small traveler run

This example controls the eligible target trips of three people for seven days, within a 10% population sample. The number of controlled target trips may exceed three.

**Bash**

```bash
java -Xmx8g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment \
  --config scenarios/SiouxFalls-public/config.xml \
  --mode hybrid --demand 100 --sample-rate 0.1 \
  --population-seed 4711 --seed 4711 --days 7 \
  --policy llm --llm-agents 3 --concurrency 3 \
  --history-window 3 --max-output-tokens 1024 --reason-max-chars 120 \
  --memory-max-output-tokens 1024 --memory-max-chars 500 --repair-attempts 3 \
  --thinking omit --response-format omit --max-requests 500 \
  --output output/first-llm-travelers
```

**Windows PowerShell**

```powershell
java -Xmx8g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment `
  --config scenarios/SiouxFalls-public/config.xml `
  --mode hybrid --demand 100 --sample-rate 0.1 `
  --population-seed 4711 --seed 4711 --days 7 `
  --policy llm --llm-agents 3 --concurrency 3 `
  --history-window 3 --max-output-tokens 1024 --reason-max-chars 120 `
  --memory-max-output-tokens 1024 --memory-max-chars 500 --repair-attempts 3 `
  --thinking omit --response-format omit --max-requests 500 `
  --output output/first-llm-travelers
```

Before making live calls, you can add `--validate-only` and choose a separate output directory to inspect preparation settings. The request budget is an example, not a token or spending estimate; check the prepared controlled-trip count and [budget calculation](experiments-and-evaluation.md#request-budgets) before extending a run.

`--thinking omit` omits the provider-specific thinking field. It does not itself disable a model's internal reasoning. `--response-format omit` uses local output validation without requesting server-side structured output. Use `json_schema` only when the service supports it.

For manager-only or joint runs, continue to [experiment configurations](experiments-and-evaluation.md). Manager reflection calls begin on the first day, even though its default first pricing opportunity is day 21.

## Troubleshooting

| Symptom | What to check |
| :--- | :--- |
| `javac` is missing, or release 25 is unsupported | Select a full JDK 25 and align `JAVA_HOME`, `PATH`, and Maven's Java version. |
| Maven is not installed | Use `sh ./mvnw` or `.\mvnw.cmd`; a separate Maven installation is optional. |
| JAR or scenario file cannot be found | Run from the repository root, build first, and preserve the distributed scenario directory names. |
| Connection refused or model not found | Verify the service host, port, API root, credentials, and exact served model ID. |
| HTTP 401/403 | Check the model-service key, its permissions, and any model-access restrictions. A GitHub token does not authenticate the model API. |
| HTTP 404 | Check the API root, the resulting `/chat/completions` path, and the exact served model name. |
| HTTP 429 | Check service rate limits, available quota, and request concurrency. |
| HTTP 400 for a generation option | Check the token-limit field and supported parameters; use `--response-format omit` when strict schemas are unsupported. |
| Context-length error | Account for the prompt, chat-template overhead, and requested output tokens together. |
| Blank, truncated, or invalid JSON output | Inspect attempt records and `finishReason`; check service behavior, output limits, and unsupported generation settings. |
| Request budget rejected before simulation | Count controlled trips, choice/reflection calls, manager tasks, optional reviews, and repair allowance. |
| Output directory differs from the supplied name | Read the reserved output path in the console; repeated names receive a suffix. |
| An existing output directory is incomplete | Inspect `status.json`, `progress.json`, exit status, and logs before treating it as a complete run. |

For Slurm and advanced operating options, see the [operating guide](usage-guide.md) and [Qwen deployment notes](../scripts/agentic_morning_qwen35.md). When reporting a problem, include the commit, command, effective settings, and relevant logs, with credentials removed.
