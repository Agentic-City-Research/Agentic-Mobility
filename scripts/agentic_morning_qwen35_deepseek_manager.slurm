#!/bin/bash
#SBATCH --job-name=morning-qwen-ds
# Choose a site-specific CPU partition with sbatch --partition=<partition>.
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=24:00:00
#SBATCH --output=morning-qwen-ds-%j.out

# Qwen decisions/private memory stay on the existing GPU job. Only manager
# policy/daily memory use DeepSeek. This job neither starts nor cancels Qwen.
set -Eeuo pipefail
phase=initialization
trap 'rc=$?; printf "ERROR: phase=%s line=%s exit=%s\n" "$phase" "$LINENO" "$rc" >&2' ERR
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ -n "${SLURM_JOB_ID:-}" ]] || fail 'Submit with sbatch, not bash on the login node.'

printf 'Joint experiment starting: job=%s node=%s\n' "$SLURM_JOB_ID" "$(hostname)"
export PROJECT_DIR="${PROJECT_DIR:-$HOME/AgenticMobility}"
[[ -d "$PROJECT_DIR" ]] || fail "Project directory not found: $PROJECT_DIR"
PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd -P)"
JAR="${JAR:-$PROJECT_DIR/matsim-example-project-0.0.1-SNAPSHOT.jar}"
CONFIG="${CONFIG:-$PROJECT_DIR/scenarios/SiouxFalls-public/config.xml}"
JAVA_BIN="${JAVA_BIN:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
if [[ -z "$JAVA_BIN" ]]; then
    if [[ -x "$HOME/opt/java25/bin/java" ]]; then JAVA_BIN="$HOME/opt/java25/bin/java"
    else JAVA_BIN="$(command -v java || true)"; fi
fi
PYTHON_BIN="$(command -v "${PYTHON_BIN:-python3}")" || fail 'Python 3 not found.'
[[ -f "$JAR" && -x "$JAVA_BIN" ]] || fail 'Upload the rebuilt JAR and select a Java 25 executable.'
[[ -f "$CONFIG" ]] || fail "Config not found: $CONFIG"
for cmd in curl tr; do command -v "$cmd" >/dev/null || fail "Missing command: $cmd"; done
command -v "$PYTHON_BIN" >/dev/null || fail "Python not found: $PYTHON_BIN"
JAR_HELP="$("$JAVA_BIN" -cp "$JAR" org.matsim.agentic.MorningExperiment --help)" || fail 'Cannot inspect uploaded JAR.'
[[ "$JAR_HELP" == *--manager-base-url* && "$JAR_HELP" == *--manager-response-format* ]] \
    || fail 'Uploaded JAR predates independent manager services; rebuild and upload it.'

export ENABLE_MANAGER=1
export DAYS="${DAYS:-100}"
export LLM_AGENTS="${LLM_AGENTS:-all}"
export DEMAND="${DEMAND:-100}"
export SAMPLE_RATE="${SAMPLE_RATE:-0.2}"
export OUTPUT_ROOT="${OUTPUT_ROOT:-$PROJECT_DIR/output/hpc/qwen35-deepseek-manager/sample$SAMPLE_RATE}"
export POPULATION_SEED="${POPULATION_SEED:-${SAMPLE_SEED:-6021}}"
export SEED="${SEED:-268}"
export PROFILE_SEED="${PROFILE_SEED:-$POPULATION_SEED}"
export CONCURRENCY="${CONCURRENCY:-196}"
export INFORMED_SHARE="${INFORMED_SHARE:-0.5}"
export INFORMATION_SEED="${INFORMATION_SEED:-$POPULATION_SEED}"
JAVA_HEAP="${JAVA_HEAP:-12g}"
MATSIM_THREADS="${MATSIM_THREADS:-4}"
OUTPUT_INTERVAL="${OUTPUT_INTERVAL:-10}"
QWEN_JOB_NAME="${QWEN_JOB_NAME:-qwen35-9b}"
QWEN_JOB_ID="${QWEN_JOB_ID:-}"
QWEN_PORT="${QWEN_PORT:-8002}"
export AGENTIC_MODEL="${MODEL_NAME:-qwen3.5-9b}"
export AGENTIC_BASE_URL="${QWEN_BASE_URL:-http://127.0.0.1:$QWEN_PORT/v1}"
QWEN_GENERATION_PARAMS="${QWEN_GENERATION_PARAMS:-}"
[[ -n "$QWEN_GENERATION_PARAMS" ]] || QWEN_GENERATION_PARAMS='{"chat_template_kwargs":{"enable_thinking":false}}'
export QWEN_GENERATION_PARAMS
if [[ -z "${CANDIDATE_FILE:-}" ]]; then
    CANDIDATE_FILE="$PROJECT_DIR/cache/candidates_100.jsonl.gz"
    [[ -s "$CANDIDATE_FILE" ]] || CANDIDATE_FILE="$PROJECT_DIR/cache/candidates_20.jsonl.gz"
fi
[[ -s "$CANDIDATE_FILE" ]] || fail "Matching candidate cache required: $CANDIDATE_FILE"
gzip -t "$CANDIDATE_FILE"
export MAX_REQUESTS="${MAX_REQUESTS:-40000000}"
export MANAGER_PROFILE="${MANAGER_PROFILE:-basic}"
export MANAGER_UPDATE_INTERVAL="${MANAGER_UPDATE_INTERVAL:-30}"
export MANAGER_MAX_OUTPUT_TOKENS="${MANAGER_MAX_OUTPUT_TOKENS:-8192}"
export MANAGER_MEMORY_MAX_OUTPUT_TOKENS="${MANAGER_MEMORY_MAX_OUTPUT_TOKENS:-2048}"
export AGENTIC_MANAGER_BASE_URL="${AGENTIC_MANAGER_BASE_URL:-https://api.deepseek.com}"
export AGENTIC_MANAGER_MODEL="${AGENTIC_MANAGER_MODEL:-deepseek-flash}"
export AGENTIC_MANAGER_THINKING="${AGENTIC_MANAGER_THINKING:-disabled}"
export AGENTIC_MANAGER_RESPONSE_FORMAT="${AGENTIC_MANAGER_RESPONSE_FORMAT:-json_object}"
export AGENTIC_MANAGER_TOKEN_LIMIT_FIELD="${AGENTIC_MANAGER_TOKEN_LIMIT_FIELD:-max_tokens}"
export AGENTIC_MANAGER_TIMEOUT_SECONDS="${AGENTIC_MANAGER_TIMEOUT_SECONDS:-300}"
if [[ -z "${AGENTIC_MANAGER_GENERATION_PARAMS:-}" ]]; then export AGENTIC_MANAGER_GENERATION_PARAMS='{}'; fi
VALIDATE_ONLY="${VALIDATE_ONLY:-0}"
MANAGER_PREFLIGHT="${MANAGER_PREFLIGHT:-1}"
[[ "$LLM_AGENTS" == all || "$LLM_AGENTS" =~ ^[1-9][0-9]*$ ]] || fail 'LLM_AGENTS must be all or a positive count.'
[[ "$DEMAND" == 100 || "$DEMAND" == 200 ]] || fail 'DEMAND must be 100 or 200.'
for name in DAYS CONCURRENCY MANAGER_UPDATE_INTERVAL MATSIM_THREADS OUTPUT_INTERVAL; do
    [[ "${!name}" =~ ^[1-9][0-9]*$ ]] || fail "$name must be positive."
done
for name in SEED POPULATION_SEED PROFILE_SEED INFORMATION_SEED; do
    [[ "${!name}" =~ ^-?[0-9]+$ ]] || fail "$name must be an integer."
done
[[ "$MAX_REQUESTS" =~ ^[0-9]+$ ]] || fail 'MAX_REQUESTS must be nonnegative.'
[[ "$VALIDATE_ONLY" == 0 || "$VALIDATE_ONLY" == 1 ]] || fail 'VALIDATE_ONLY must be 0 or 1.'
[[ "$MANAGER_PREFLIGHT" == 0 || "$MANAGER_PREFLIGHT" == 1 ]] || fail 'MANAGER_PREFLIGHT must be 0 or 1.'
"$PYTHON_BIN" -c '
import json, math, os, urllib.parse
rate, share = float(os.environ["SAMPLE_RATE"]), float(os.environ["INFORMED_SHARE"])
if not (math.isfinite(rate) and 0 < rate <= 1 and math.isfinite(share) and 0 <= share <= 1):
    raise SystemExit("Invalid sample rate or informed share")
if not isinstance(json.loads(os.environ["QWEN_GENERATION_PARAMS"]), dict):
    raise SystemExit("QWEN_GENERATION_PARAMS must be a JSON object")
for key in ("AGENTIC_BASE_URL", "AGENTIC_MANAGER_BASE_URL"):
    url = urllib.parse.urlsplit(os.environ[key])
    if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise SystemExit(key + " must be HTTP(S), without credentials, query or fragment.")
if not isinstance(json.loads(os.environ["AGENTIC_MANAGER_GENERATION_PARAMS"]), dict):
    raise SystemExit("AGENTIC_MANAGER_GENERATION_PARAMS must be a JSON object.")
'

if [[ "$VALIDATE_ONLY" == 0 ]]; then
    phase=model_services
    if [[ -z "${QWEN_BASE_URL:-}" ]]; then
        if [[ -z "$QWEN_JOB_ID" ]]; then
            jobs="$(squeue -h -u "$(id -un)" -n "$QWEN_JOB_NAME" -t RUNNING -o '%i')"
            ids=()
            while IFS= read -r job; do [[ -z "$job" ]] || ids+=("$job"); done <<< "$jobs"
            [[ ${#ids[@]} -eq 1 ]] || fail 'Set QWEN_JOB_ID to exactly one running Qwen job.'
            QWEN_JOB_ID="${ids[0]}"
        fi
        nodes="$(squeue -h -u "$(id -un)" -j "$QWEN_JOB_ID" -t RUNNING -o '%N')"
        [[ -n "$nodes" ]] || fail "Qwen job $QWEN_JOB_ID is not RUNNING."
        QWEN_NODE="$(scontrol show hostnames "$nodes")"
        [[ -n "$QWEN_NODE" && "$QWEN_NODE" != *$'\n'* ]] || fail 'Qwen service must occupy one node.'
        export AGENTIC_BASE_URL="http://$QWEN_NODE:$QWEN_PORT/v1"
    fi
    API_KEY_FILE="${API_KEY_FILE:-$HOME/.vllm_api_key}"
    [[ -s "$API_KEY_FILE" ]] || fail "Qwen key file missing: $API_KEY_FILE"
    export AGENTIC_API_KEY="$(tr -d '\r\n' < "$API_KEY_FILE")"
    [[ -n "$AGENTIC_API_KEY" ]] || fail 'Qwen API key is blank.'
    "$PYTHON_BIN" - <<'PY'
import json, os, urllib.error, urllib.parse, urllib.request
base = os.environ["AGENTIC_BASE_URL"]
url = urllib.parse.urlsplit(base)
if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.query or url.fragment:
    raise SystemExit("QWEN_BASE_URL must be HTTP(S), without credentials/query/fragment")
req = urllib.request.Request(base.rstrip("/")+"/models", headers={"Authorization":"Bearer "+os.environ["AGENTIC_API_KEY"]})
try:
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=30) as response:
        models = {x.get("id") for x in json.load(response).get("data", [])}
except urllib.error.HTTPError as error:
    raise SystemExit("Qwen preflight failed: HTTP " + str(error.code))
except (urllib.error.URLError, TimeoutError):
    raise SystemExit("Qwen endpoint unreachable from the MATSim compute node")
if os.environ["AGENTIC_MODEL"] not in models: raise SystemExit("Qwen model not advertised by /models")
print("Qwen service ready", flush=True)
PY
    if [[ -z "${AGENTIC_MANAGER_API_KEY:-}" && -n "${DEEPSEEK_API_KEY:-}" ]]; then
        export AGENTIC_MANAGER_API_KEY="$DEEPSEEK_API_KEY"
    fi
    if [[ -z "${AGENTIC_MANAGER_API_KEY:-}" ]]; then
        MANAGER_API_KEY_FILE="${MANAGER_API_KEY_FILE:-$HOME/.deepseek_api_key}"
        [[ -s "$MANAGER_API_KEY_FILE" ]] || fail "DeepSeek key file not found or empty: $MANAGER_API_KEY_FILE"
        export AGENTIC_MANAGER_API_KEY="$(tr -d '\r\n' < "$MANAGER_API_KEY_FILE")"
    fi
    [[ -n "$AGENTIC_MANAGER_API_KEY" ]] || fail 'Manager API key is blank.'
    if [[ "$MANAGER_PREFLIGHT" == 1 ]]; then
        # GET /models is an authenticated connectivity check, not a generation call.
        if ! manager_models="$(printf 'header = "Authorization: Bearer %s"\n' "$AGENTIC_MANAGER_API_KEY" |
            curl --config - -fsS --connect-timeout 10 --max-time 30 "${AGENTIC_MANAGER_BASE_URL%/}/models")"; then
            fail 'Manager endpoint is unreachable or rejected authentication from this compute node.'
        fi
        "$PYTHON_BIN" -c '
import json, sys
models = {x.get("id") for x in json.load(sys.stdin).get("data", [])}
if sys.argv[1] not in models: raise SystemExit("Requested manager model is absent from /models.")
' "$AGENTIC_MANAGER_MODEL" <<< "$manager_models"
    fi
fi
printf 'Manager API: %s\nManager model: %s\nManager format: %s\n' \
    "$AGENTIC_MANAGER_BASE_URL" "$AGENTIC_MANAGER_MODEL" "$AGENTIC_MANAGER_RESPONSE_FORMAT"
printf 'Manager policy/memory: same independent service; all-day charging retained.\n'
phase=run_preparation
cd "$PROJECT_DIR"
mkdir -p "$OUTPUT_ROOT/job-$SLURM_JOB_ID"
RUN_DIR="$(mktemp -d "$OUTPUT_ROOT/job-$SLURM_JOB_ID/joint_seed${SEED}_XXXXXX")"
OUTPUT_DIR="${OUTPUT_DIR:-$RUN_DIR/simulation}"
[[ ! -e "$OUTPUT_DIR" ]] || fail "Output already exists: $OUTPUT_DIR"
RUNTIME_JAR="$RUN_DIR/runtime.jar"
trap 'rm -f -- "$RUNTIME_JAR"' EXIT
SOURCE_SHA="$(sha256sum "$JAR")"; SOURCE_SHA="${SOURCE_SHA%% *}"
cp -- "$JAR" "$RUNTIME_JAR"
RUNTIME_SHA="$(sha256sum "$RUNTIME_JAR")"; RUNTIME_SHA="${RUNTIME_SHA%% *}"
[[ "$SOURCE_SHA" == "$RUNTIME_SHA" ]] || fail 'JAR changed during copying; finish uploading before submission.'
printf '%s\n' "$RUNTIME_SHA" > "$RUN_DIR/jar.sha256"
sha256sum "$CANDIDATE_FILE" > "$RUN_DIR/candidate-cache.sha256"
"$JAVA_BIN" -version > "$RUN_DIR/java-version.txt" 2>&1
"$PYTHON_BIN" - "$CONFIG" "$RUN_DIR/config.xml" "$MATSIM_THREADS" "$OUTPUT_INTERVAL" <<'PY'
import sys
from pathlib import Path
from xml.dom import minidom
source = Path(sys.argv[1]).resolve()
doc = minidom.parse(str(source))
inputs = {"network":"inputNetworkFile", "plans":"inputPlansFile", "facilities":"inputFacilitiesFile"}
for module in doc.getElementsByTagName("module"):
    group = module.getAttribute("name")
    for param in module.getElementsByTagName("param"):
        name = param.getAttribute("name")
        if inputs.get(group) == name:
            param.setAttribute("value", str((source.parent/param.getAttribute("value")).resolve()))
        if group in ("global", "qsim") and name == "numberOfThreads": param.setAttribute("value", sys.argv[3])
        if group == "controller" and name in ("writeEventsInterval", "writePlansInterval"): param.setAttribute("value", sys.argv[4])
Path(sys.argv[2]).write_bytes(doc.toxml(encoding="UTF-8"))
PY
args=(
    --config "$RUN_DIR/config.xml" --output "$OUTPUT_DIR" --mode hybrid --policy llm
    --llm-agents "$LLM_AGENTS" --manager --manager-profile "$MANAGER_PROFILE"
    --days "$DAYS" --demand "$DEMAND" --sample-rate "$SAMPLE_RATE"
    --seed "$SEED" --population-seed "$POPULATION_SEED" --personal-profiles legacy --profile-seed "$PROFILE_SEED"
    --cohort-selection planned-arrival --planned-arrival-before 32400
    --departure-before-seconds 3600 --departure-after-seconds 1800 --departure-step-seconds 900
    --peak-start 25200 --peak-end 32400 --analysis-start 23400 --analysis-end 39600
    --informed-share "$INFORMED_SHARE" --information-scope controlled --information-seed "$INFORMATION_SEED"
    --toll-history-bin-seconds 3600 --candidate-file "$CANDIDATE_FILE" --candidate-order original --candidate-order-seed 0
    --concurrency "$CONCURRENCY" --repair-attempts 3 --timeout-seconds 300 --llm-stall-seconds 900 --max-requests "$MAX_REQUESTS"
    --model "$AGENTIC_MODEL" --base-url "$AGENTIC_BASE_URL" --thinking omit --generation-params "$QWEN_GENERATION_PARAMS"
    --response-format json_schema --token-limit-field max_tokens --max-output-tokens 1024 --reason-max-chars 120
    --memory-mode llm --memory-max-chars 500 --memory-max-output-tokens 1024
    --manager-update-interval "$MANAGER_UPDATE_INTERVAL" --manager-max-output-tokens "$MANAGER_MAX_OUTPUT_TOKENS"
    --manager-memory-max-output-tokens "$MANAGER_MEMORY_MAX_OUTPUT_TOKENS"
    --manager-model "$AGENTIC_MANAGER_MODEL" --manager-base-url "$AGENTIC_MANAGER_BASE_URL"
    --manager-thinking "$AGENTIC_MANAGER_THINKING" --manager-response-format "$AGENTIC_MANAGER_RESPONSE_FORMAT"
    --manager-generation-params "$AGENTIC_MANAGER_GENERATION_PARAMS" --manager-token-limit-field "$AGENTIC_MANAGER_TOKEN_LIMIT_FIELD"
    --manager-timeout-seconds "$AGENTIC_MANAGER_TIMEOUT_SECONDS" --trip-output-interval 1 --context-samples 20
)
[[ "$VALIDATE_ONLY" == 0 ]] || args+=(--validate-only)
command=("$JAVA_BIN" --enable-native-access=ALL-UNNAMED -Xms2g "-Xmx$JAVA_HEAP" -cp "$RUNTIME_JAR" org.matsim.agentic.MorningExperiment "${args[@]}")
printf '%q ' "${command[@]}" > "$RUN_DIR/command.txt"
printf '\n' >> "$RUN_DIR/command.txt"
printf 'Qwen job: %s\nQwen API: %s\n' "${QWEN_JOB_ID:-not-used}" "$AGENTIC_BASE_URL"
printf 'Days=%s sample=%s morning_people=%s seed=%s concurrency=%s\n' "$DAYS" "$SAMPLE_RATE" "$LLM_AGENTS" "$SEED" "$CONCURRENCY"
printf 'Run records: %s\nSimulation output: %s\n' "$RUN_DIR" "$OUTPUT_DIR"
phase=matsim
if [[ "$VALIDATE_ONLY" == 1 ]]; then "${command[@]}"
else srun --ntasks=1 "${command[@]}"; fi
printf 'Joint experiment finished: %s\n' "$(date -Is)"
