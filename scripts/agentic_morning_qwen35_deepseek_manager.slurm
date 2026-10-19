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
set -euo pipefail
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ -n "${SLURM_JOB_ID:-}" ]] || fail 'Submit with sbatch, not bash on the login node.'

export PROJECT_DIR="${PROJECT_DIR:-$HOME/matsim-example-morning}"
RUNNER="$PROJECT_DIR/scripts/agentic_morning_qwen35.slurm"
JAR="${JAR:-$PROJECT_DIR/matsim-example-project-0.0.1-SNAPSHOT.jar}"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
[[ -f "$RUNNER" ]] || fail "Upload scripts/ including the Qwen runner: $RUNNER"
[[ -f "$JAR" && -x "$JAVA_BIN" ]] || fail 'Upload the rebuilt JAR and select a Java 25 executable.'
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
export SEED="${SEED:-9261}"
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
[[ "$VALIDATE_ONLY" == 0 || "$VALIDATE_ONLY" == 1 ]] || fail 'VALIDATE_ONLY must be 0 or 1.'
[[ "$MANAGER_PREFLIGHT" == 0 || "$MANAGER_PREFLIGHT" == 1 ]] || fail 'MANAGER_PREFLIGHT must be 0 or 1.'
"$PYTHON_BIN" -c '
import json, os, urllib.parse
url = urllib.parse.urlsplit(os.environ["AGENTIC_MANAGER_BASE_URL"])
if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.query or url.fragment:
    raise SystemExit("Manager base URL must be HTTP(S), without credentials, query or fragment.")
if not isinstance(json.loads(os.environ["AGENTIC_MANAGER_GENERATION_PARAMS"]), dict):
    raise SystemExit("AGENTIC_MANAGER_GENERATION_PARAMS must be a JSON object.")
'

if [[ "$VALIDATE_ONLY" == 0 ]]; then
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
exec bash "$RUNNER"
