#!/usr/bin/env bash
#SBATCH --job-name=agentic-single
#SBATCH --partition=Q10
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=16
#SBATCH --gres=gpu:l20:4
#SBATCH --mem=64G
#SBATCH --time=72:00:00
#SBATCH --output=agentic-single-%j.log

# One node, one local vLLM service and one MATSim run. Change resources for your cluster.
set -Eeuo pipefail
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ -n "${SLURM_JOB_ID:-}" ]] || fail 'Submit this script with sbatch.'
PROJECT_DIR="${PROJECT_DIR:-${SLURM_SUBMIT_DIR:-}}"
[[ -d "$PROJECT_DIR" ]] || fail 'Set PROJECT_DIR to the project root on HPC.'
PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd -P)"
cd "$PROJECT_DIR"
module load "${GCC_MODULE:-gcc14.2}"
source "$HOME/miniforge3/etc/profile.d/conda.sh"
conda activate "${CONDA_ENV:-qwen38-cu130}"
JAR="${JAR:-$PROJECT_DIR/matsim-example-project-0.0.1-SNAPSHOT.jar}"
CONFIG="${CONFIG:-$PROJECT_DIR/scenarios/SiouxFalls-public/config.xml}"
MODEL_PATH="${MODEL_PATH:-$HOME/models/Qwen3.5-9B}"
KEY_FILE="${KEY_FILE:-$HOME/.vllm_api_key}"
MODEL_NAME="${MODEL_NAME:-qwen3.5-9b}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
VLLM_BIN="${VLLM_BIN:-vllm}"
JAVA_BIN="${JAVA_BIN:-java}"
PORT="${PORT:-8002}"
READY_TIMEOUT="${READY_TIMEOUT:-1200}"
MAX_MODEL_LEN="${MAX_MODEL_LEN:-16384}"
GPU_MEMORY_UTILIZATION="${GPU_MEMORY_UTILIZATION:-0.90}"
TENSOR_PARALLEL_SIZE="${TENSOR_PARALLEL_SIZE:-1}"
DATA_PARALLEL_SIZE="${DATA_PARALLEL_SIZE:-4}"
MAX_NUM_SEQS="${MAX_NUM_SEQS:-512}"
MAX_BATCHED_TOKENS="${MAX_BATCHED_TOKENS:-16384}"
JAVA_HEAP="${JAVA_HEAP:-8g}"
DAYS="${DAYS:-2}"
LLM_AGENTS="${LLM_AGENTS:-3}"
DEMAND="${DEMAND:-100}"
SAMPLE_PERSONS="${SAMPLE_PERSONS:-20}"
SAMPLE_RATE="${SAMPLE_RATE:-}"
SEED="${SEED:-268}"
POPULATION_SEED="${POPULATION_SEED:-6021}"
INFORMATION_SEED="${INFORMATION_SEED:-$POPULATION_SEED}"
INFORMED_SHARE="${INFORMED_SHARE:-0.5}"
CONCURRENCY="${CONCURRENCY:-3}"
MAX_REQUESTS="${MAX_REQUESTS:-1000}"
ENABLE_MANAGER="${ENABLE_MANAGER:-0}"
MANAGER_SERVICE="${MANAGER_SERVICE:-local}"
MANAGER_PROFILE="${MANAGER_PROFILE:-basic}"
MANAGER_UPDATE_INTERVAL="${MANAGER_UPDATE_INTERVAL:-30}"
MANAGER_MAX_OUTPUT_TOKENS="${MANAGER_MAX_OUTPUT_TOKENS:-2048}"
MANAGER_MEMORY_MAX_OUTPUT_TOKENS="${MANAGER_MEMORY_MAX_OUTPUT_TOKENS:-1024}"
MAX_OUTPUT_TOKENS="${MAX_OUTPUT_TOKENS:-1024}"
MEMORY_MAX_OUTPUT_TOKENS="${MEMORY_MAX_OUTPUT_TOKENS:-1024}"
TRAVELER_REFLECTION_INTERVAL="${TRAVELER_REFLECTION_INTERVAL:-5}"
HISTORY_WINDOW="${HISTORY_WINDOW:-$TRAVELER_REFLECTION_INTERVAL}"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJECT_DIR/output/hpc/single-job-$SLURM_JOB_ID}"
SERVICE_LOG="${SERVICE_LOG:-$PROJECT_DIR/output/hpc/vllm-$SLURM_JOB_ID.log}"
RESPONSE_FORMAT="${RESPONSE_FORMAT:-omit}"
VALIDATE_ONLY="${VALIDATE_ONLY:-0}"

[[ -f "$JAR" && -f "$CONFIG" ]] || fail 'JAR or MATSim config is missing; upload the rebuilt project.'
[[ -s "$MODEL_PATH/config.json" && -s "$KEY_FILE" ]] || fail 'MODEL_PATH/config.json or KEY_FILE missing; upload the complete model and create a nonempty key file.'
command -v "$JAVA_BIN" >/dev/null || fail 'Java executable not found; load JDK 25.'
command -v "$PYTHON_BIN" >/dev/null || fail 'Python executable not found; activate your vLLM environment.'
command -v "$VLLM_BIN" >/dev/null || fail 'vllm executable not found; activate your vLLM environment.'
for cmd in curl setsid sha256sum; do command -v "$cmd" >/dev/null || fail "Missing command: $cmd"; done
[[ "$ENABLE_MANAGER" == 0 || "$ENABLE_MANAGER" == 1 ]] || fail 'ENABLE_MANAGER must be 0 or 1.'
[[ "$MANAGER_SERVICE" == local || "$MANAGER_SERVICE" == remote ]] || fail 'MANAGER_SERVICE must be local or remote.'
[[ "$VALIDATE_ONLY" == 0 || "$VALIDATE_ONLY" == 1 ]] || fail 'VALIDATE_ONLY must be 0 or 1.'
[[ "$PORT" =~ ^[0-9]+$ && "$PORT" -ge 1 && "$PORT" -le 65535 ]] || fail 'PORT must be 1..65535.'
[[ "$READY_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || fail 'READY_TIMEOUT must be positive.'
[[ "$TRAVELER_REFLECTION_INTERVAL" == 1 || "$TRAVELER_REFLECTION_INTERVAL" == 5 ]] || fail 'TRAVELER_REFLECTION_INTERVAL must be 1 or 5.'
[[ "$HISTORY_WINDOW" =~ ^[1-5]$ ]] || fail 'HISTORY_WINDOW must be 1..5.'
[[ ! -e "$OUTPUT_DIR" ]] || fail "Output already exists: $OUTPUT_DIR"
if [[ -n "$SAMPLE_RATE" ]]; then
    sampling=(--sample-rate "$SAMPLE_RATE")
else
    sampling=(--sample-persons "$SAMPLE_PERSONS")
fi
[[ -z "${CANDIDATE_FILE:-}" || -f "$CANDIDATE_FILE" ]] || fail "Candidate file missing: $CANDIDATE_FILE"

mkdir -p "$(dirname "$SERVICE_LOG")"
service_pid=''
java_pid=''
runtime_dir="$(mktemp -d "${TMPDIR:-/tmp}/agentic-single.XXXXXXXX")"
runtime_jar="$runtime_dir/experiment.jar"
cleanup() {
    if [[ -n "$java_pid" ]] && kill -0 "$java_pid" 2>/dev/null; then kill "$java_pid" 2>/dev/null || true; wait "$java_pid" 2>/dev/null || true; fi
    if [[ -n "$service_pid" ]]; then kill -- "-$service_pid" 2>/dev/null || true; wait "$service_pid" 2>/dev/null || true; fi
    rm -f -- "$runtime_jar"; rmdir -- "$runtime_dir" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# vLLM reads VLLM_API_KEY from its environment; do not print or put the key in argv.
export VLLM_API_KEY="$(tr -d '\r\n' < "$KEY_FILE")"
[[ -n "$VLLM_API_KEY" ]] || fail 'KEY_FILE is blank.'
export HF_HOME="${HF_HOME:-$HOME/.cache/huggingface}"
export VLLM_LOGGING_LEVEL="${VLLM_LOGGING_LEVEL:-INFO}" PYTHONUNBUFFERED=1
export AGENTIC_API_KEY="$VLLM_API_KEY"
export AGENTIC_BASE_URL="http://127.0.0.1:$PORT/v1"
export AGENTIC_MODEL="$MODEL_NAME"
"$PYTHON_BIN" - "$PORT" <<'PY'
import socket, sys
s = socket.socket()
try: s.bind(('127.0.0.1', int(sys.argv[1])))
except OSError as e: raise SystemExit(f'Local port unavailable: {e}')
finally: s.close()
PY
if [[ "$MANAGER_SERVICE" == local ]]; then
    for key in ${!AGENTIC_MANAGER_@}; do unset "$key"; done
elif [[ "$ENABLE_MANAGER" == 1 ]]; then
    if [[ -n "${MANAGER_KEY_FILE:-}" ]]; then
        [[ -s "$MANAGER_KEY_FILE" ]] || fail 'MANAGER_KEY_FILE missing or empty.'
        export AGENTIC_MANAGER_API_KEY="$(tr -d '\r\n' < "$MANAGER_KEY_FILE")"
    fi
    [[ -n "${AGENTIC_MANAGER_BASE_URL:-}" && -n "${AGENTIC_MANAGER_MODEL:-}" && -n "${AGENTIC_MANAGER_API_KEY:-}" ]] || fail 'Remote manager requires AGENTIC_MANAGER_BASE_URL, AGENTIC_MANAGER_MODEL and AGENTIC_MANAGER_API_KEY.'
fi
source_hash="$(sha256sum "$JAR" | cut -d' ' -f1)"
cp -- "$JAR" "$runtime_jar"
[[ "$source_hash" == "$(sha256sum "$runtime_jar" | cut -d' ' -f1)" ]] || fail 'JAR changed during copy.'
printf 'Project: %s\nJAR SHA-256: %s\nModel: %s\nOutput: %s\nService log: %s\n' \
    "$PROJECT_DIR" "$source_hash" "$MODEL_NAME" "$OUTPUT_DIR" "$SERVICE_LOG"
printf 'Manager enabled: %s; manager service: %s\n' "$ENABLE_MANAGER" "$MANAGER_SERVICE"
setsid "$VLLM_BIN" serve "$MODEL_PATH" \
    --served-model-name "$MODEL_NAME" --host 127.0.0.1 --port "$PORT" \
    --dtype bfloat16 --tensor-parallel-size "$TENSOR_PARALLEL_SIZE" --data-parallel-size "$DATA_PARALLEL_SIZE" \
    --language-model-only --max-model-len "$MAX_MODEL_LEN" --max-num-seqs "$MAX_NUM_SEQS" \
    --max-num-batched-tokens "$MAX_BATCHED_TOKENS" --gpu-memory-utilization "$GPU_MEMORY_UTILIZATION" \
    --reasoning-parser qwen3 --default-chat-template-kwargs '{"enable_thinking":false}' >"$SERVICE_LOG" 2>&1 &
service_pid=$!
deadline=$((SECONDS + READY_TIMEOUT))
while :; do
    kill -0 "$service_pid" 2>/dev/null || { wait "$service_pid" || true; fail "Model service exited; inspect $SERVICE_LOG"; }
    if models=$(printf 'header = "Authorization: Bearer %s"\n' "$VLLM_API_KEY" | curl --config - --noproxy '*' -fsS --connect-timeout 2 --max-time 5 "$AGENTIC_BASE_URL/models" 2>/dev/null) && \
        printf '%s' "$models" | "$PYTHON_BIN" -c 'import json,sys; sys.exit(0 if any(x.get("id")==sys.argv[1] for x in json.load(sys.stdin).get("data",[])) else 1)' "$MODEL_NAME"; then break; fi
    (( SECONDS < deadline )) || fail "Model service not ready within ${READY_TIMEOUT}s; inspect $SERVICE_LOG"
    sleep 5
done
printf 'Local model service ready.\n'

args=(--config "$CONFIG" --mode hybrid --policy llm --llm-agents "$LLM_AGENTS" --days "$DAYS"
    --demand "$DEMAND" "${sampling[@]}" --seed "$SEED" --population-seed "$POPULATION_SEED"
    --information-seed "$INFORMATION_SEED" --informed-share "$INFORMED_SHARE"
    --traveler-reflection-interval "$TRAVELER_REFLECTION_INTERVAL" --history-window "$HISTORY_WINDOW"
    --concurrency "$CONCURRENCY" --max-requests "$MAX_REQUESTS" --model "$MODEL_NAME"
    --base-url "$AGENTIC_BASE_URL" --thinking omit --response-format "$RESPONSE_FORMAT"
    --max-output-tokens "$MAX_OUTPUT_TOKENS" --memory-max-output-tokens "$MEMORY_MAX_OUTPUT_TOKENS" --output "$OUTPUT_DIR")
[[ -z "${CANDIDATE_FILE:-}" ]] || args+=(--candidate-file "$CANDIDATE_FILE")
[[ -z "${GENERATION_PARAMS:-}" ]] || args+=(--generation-params "$GENERATION_PARAMS")
if [[ "$ENABLE_MANAGER" == 1 ]]; then args+=(--manager --manager-profile "$MANAGER_PROFILE" --manager-update-interval "$MANAGER_UPDATE_INTERVAL" --manager-max-output-tokens "$MANAGER_MAX_OUTPUT_TOKENS" --manager-memory-max-output-tokens "$MANAGER_MEMORY_MAX_OUTPUT_TOKENS"); fi
[[ "$VALIDATE_ONLY" == 0 ]] || args+=(--validate-only)
"$JAVA_BIN" --enable-native-access=ALL-UNNAMED "-Xmx$JAVA_HEAP" -cp "$runtime_jar" org.matsim.agentic.MorningExperiment "${args[@]}" &
java_pid=$!
while kill -0 "$java_pid" 2>/dev/null; do
    kill -0 "$service_pid" 2>/dev/null || fail "Model service exited during simulation; inspect $SERVICE_LOG"
    sleep 2
done
wait "$java_pid"
java_pid=''
"$PYTHON_BIN" - "$OUTPUT_DIR/morning/status.json" "$VALIDATE_ONLY" "$DAYS" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as f: status = json.load(f)
ok = status.get('validated') and status.get('simulationRun') is False if sys.argv[2] == '1' else status.get('complete') and status.get('daysCompleted') == int(sys.argv[3])
if not ok or not status.get('integrityVerified'): raise SystemExit('Run status or integrity verification failed')
print('Finished and verified:', sys.argv[1])
PY
