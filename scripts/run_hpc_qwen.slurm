#!/bin/bash
#SBATCH --job-name=morning-qwen
# Choose a site-specific partition with sbatch --partition=<partition>.
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=24:00:00
#SBATCH --output=morning-qwen-%j.out

# Submit with sbatch; this CPU job only calls an existing Qwen GPU job.
# Upload the CURRENT morning JAR and the complete scenarios/ directory first.
# Override the log location using sbatch --output=<path> if needed.
set -euo pipefail

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Experiment defaults: full background traffic, three LLM-controlled people.
DAYS="${DAYS:-7}"
LLM_AGENTS="${LLM_AGENTS:-3}"
DEMAND="${DEMAND:-100}"
SEED="${SEED:-4711}"
CONCURRENCY="${CONCURRENCY:-3}"
REPAIR_ATTEMPTS="${REPAIR_ATTEMPTS:-3}"
MAX_REQUESTS="${MAX_REQUESTS:-1000}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"
LLM_STALL_SECONDS="${LLM_STALL_SECONDS:-900}"
HISTORY_WINDOW="${HISTORY_WINDOW:-5}"
MEMORY_MODE="${MEMORY_MODE:-llm}"
DEPARTURE_BEFORE_SECONDS="${DEPARTURE_BEFORE_SECONDS:-5400}"
DEPARTURE_AFTER_SECONDS="${DEPARTURE_AFTER_SECONDS:-1800}"
DEPARTURE_STEP_SECONDS="${DEPARTURE_STEP_SECONDS:-900}"
MANAGER_PROFILE="${MANAGER_PROFILE:-basic}"
ENABLE_MANAGER="${ENABLE_MANAGER:-0}"
MANAGER_UPDATE_INTERVAL="${MANAGER_UPDATE_INTERVAL:-30}"
MANAGER_MAX_OUTPUT_TOKENS="${MANAGER_MAX_OUTPUT_TOKENS:-8192}"
MEMORY_MAX_CHARS="${MEMORY_MAX_CHARS:-500}"
MEMORY_MAX_OUTPUT_TOKENS="${MEMORY_MAX_OUTPUT_TOKENS:-1024}"
MAX_OUTPUT_TOKENS="${MAX_OUTPUT_TOKENS:-512}"
RESPONSE_FORMAT="${RESPONSE_FORMAT:-json_schema}"
JAVA_HEAP="${JAVA_HEAP:-12g}"
CANDIDATE_SEARCH_LIMIT="${CANDIDATE_SEARCH_LIMIT:-10000}"
CANDIDATE_POOL_SIZE="${CANDIDATE_POOL_SIZE:-16}"
CANDIDATE_MAX_TIME_RATIO="${CANDIDATE_MAX_TIME_RATIO:-2.5}"
CANDIDATE_TIME_SLACK_SECONDS="${CANDIDATE_TIME_SLACK_SECONDS:-300}"
CANDIDATE_MAX_DISTANCE_RATIO="${CANDIDATE_MAX_DISTANCE_RATIO:-2.5}"
CANDIDATE_DISTANCE_SLACK_METERS="${CANDIDATE_DISTANCE_SLACK_METERS:-2000}"
CANDIDATE_MAX_OVERLAP="${CANDIDATE_MAX_OVERLAP:-0.85}"

# Override PROJECT_DIR when your uploaded project has a different name.
PROJECT_DIR="${PROJECT_DIR:-$HOME/matsim-example-morning}"
JAR="${JAR:-$PROJECT_DIR/matsim-example-project-0.0.1-SNAPSHOT.jar}"
CONFIG="${CONFIG:-$PROJECT_DIR/scenarios/SiouxFalls-public/config.xml}"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
API_KEY_FILE="${API_KEY_FILE:-$HOME/.vllm_api_key}"

# Pin QWEN_JOB_ID if several Qwen jobs are running; never silently pick one.
QWEN_JOB_ID="${QWEN_JOB_ID:-}"
QWEN_JOB_NAME="${QWEN_JOB_NAME:-qwen38-fp8}"
QWEN_PORT="${QWEN_PORT:-8001}"
MODEL_NAME="${MODEL_NAME:-qwen3.8-27b-fp8}"
if [[ -z "${QWEN_GENERATION_PARAMS:-}" ]]; then
    QWEN_GENERATION_PARAMS='{"chat_template_kwargs":{"enable_thinking":false}}'
fi

[[ -n "${SLURM_JOB_ID:-}" ]] || fail 'Use sbatch, not bash on the login node.'
[[ -d "$PROJECT_DIR" ]] || fail "Project directory not found: $PROJECT_DIR"
[[ -f "$JAR" ]] || fail "Current morning JAR not found: $JAR"
[[ -f "$CONFIG" ]] || fail "Public scenario config not found: $CONFIG"
[[ -x "$JAVA_BIN" ]] || fail "Java executable not found: $JAVA_BIN"
[[ -s "$API_KEY_FILE" ]] || fail "API key file not found or empty: $API_KEY_FILE"
for required_cmd in squeue scontrol srun curl tr date id hostname; do
    command -v "$required_cmd" >/dev/null || fail "Missing command: $required_cmd"
done
command -v "$PYTHON_BIN" >/dev/null || fail "Python not found: $PYTHON_BIN"
[[ "$DAYS" =~ ^[1-9][0-9]*$ ]] || fail 'DAYS must be positive.'
[[ "$LLM_AGENTS" == all || "$LLM_AGENTS" =~ ^[1-9][0-9]*$ ]] || fail 'LLM_AGENTS must be all or a positive count.'
[[ "$DEMAND" == 100 || "$DEMAND" == 200 ]] || fail 'DEMAND must be 100 or 200.'
[[ "$CONCURRENCY" =~ ^[1-9][0-9]*$ ]] || fail 'CONCURRENCY must be positive.'
[[ "$MAX_REQUESTS" =~ ^[0-9]+$ ]] || fail 'MAX_REQUESTS must be nonnegative (0 disables the limit).'
[[ "$MEMORY_MODE" == llm ]] || fail 'MEMORY_MODE only supports llm.'
[[ "$ENABLE_MANAGER" == 0 || "$ENABLE_MANAGER" == 1 ]] || fail 'ENABLE_MANAGER must be 0 or 1.'
[[ "$MANAGER_PROFILE" == basic || "$MANAGER_PROFILE" == controlled ]] || fail 'MANAGER_PROFILE must be basic or controlled.'
[[ "$MANAGER_UPDATE_INTERVAL" =~ ^[1-9][0-9]*$ ]] || fail 'MANAGER_UPDATE_INTERVAL must be positive.'
[[ "$RESPONSE_FORMAT" == json_schema || "$RESPONSE_FORMAT" == json_object || "$RESPONSE_FORMAT" == omit ]] \
    || fail 'RESPONSE_FORMAT must be json_schema, json_object or omit.'
JAR_HELP="$("$JAVA_BIN" -cp "$JAR" org.matsim.agentic.MorningExperiment --help)" \
    || fail 'The JAR must include the current MorningExperiment entry point.'
for option in --candidate-pool-size --candidate-time-slack-seconds --candidate-distance-slack-meters \
    --departure-before-seconds --departure-after-seconds --departure-step-seconds --manager-profile; do
    [[ "$JAR_HELP" == *"$option"* ]] || fail "Uploaded JAR is too old: missing $option"
done

HPC_USER="$(id -un)"
if [[ -z "$QWEN_JOB_ID" ]]; then
    qwen_jobs="$(squeue -h -u "$HPC_USER" -n "$QWEN_JOB_NAME" -t RUNNING -o '%i')"
    qwen_job_ids=()
    while IFS= read -r qwen_job; do
        [[ -z "$qwen_job" ]] || qwen_job_ids+=("$qwen_job")
    done <<< "$qwen_jobs"
    [[ ${#qwen_job_ids[@]} -eq 1 ]] || fail "Expected one RUNNING $QWEN_JOB_NAME job; found ${#qwen_job_ids[@]}. Set QWEN_JOB_ID explicitly."
    QWEN_JOB_ID="${qwen_job_ids[0]}"
fi
qwen_node_list="$(squeue -h -u "$HPC_USER" -j "$QWEN_JOB_ID" -t RUNNING -o '%N')"
[[ -n "$qwen_node_list" ]] || fail "Qwen job $QWEN_JOB_ID is not RUNNING for $HPC_USER."
qwen_nodes="$(scontrol show hostnames "$qwen_node_list")"
[[ -n "$qwen_nodes" && "$qwen_nodes" != *$'\n'* ]] || fail 'This launcher requires a single-node Qwen job.'
QWEN_NODE="$qwen_nodes"

# Credentials go through the environment, not Java command-line arguments.
export AGENTIC_API_KEY="$(tr -d '\r\n' < "$API_KEY_FILE")"
[[ -n "$AGENTIC_API_KEY" ]] || fail 'API key is blank after removing line endings.'
export AGENTIC_BASE_URL="http://${QWEN_NODE}:${QWEN_PORT}/v1"
export AGENTIC_MODEL="$MODEL_NAME"

# Authentication/model discovery only: no extra chat-completions test call.
if ! models_json="$(printf 'header = "Authorization: Bearer %s"\n' "$AGENTIC_API_KEY" |
    curl --config - --noproxy '*' -fsS --connect-timeout 10 --max-time 30 \
        "$AGENTIC_BASE_URL/models")"; then
    fail "Cannot reach Qwen from the MATSim node: $AGENTIC_BASE_URL"
fi
if ! printf '%s' "$models_json" | "$PYTHON_BIN" -c \
    'import json, sys; d = json.load(sys.stdin); sys.exit(0 if any(m.get("id") == sys.argv[1] for m in d.get("data", [])) else 1)' "$MODEL_NAME"; then
    fail "Qwen API does not advertise model $MODEL_NAME."
fi
"$PYTHON_BIN" -c 'import json, sys; assert isinstance(json.loads(sys.argv[1]), dict)' "$QWEN_GENERATION_PARAMS"

# Distinct parent per Slurm job prevents overwriting a same-seed repeat.
# With LLM_AGENTS=all, the label is llmall; actual counts are in manifest.json.
RUN_DATE="${RUN_DATE:-$(TZ=Asia/Shanghai date +%F)}"
MANAGER_LABEL=noMgr
[[ "$ENABLE_MANAGER" == 0 ]] || MANAGER_LABEL=mgr
RUN_NAME="${RUN_DATE}_day${DAYS}_llm${LLM_AGENTS}_${MANAGER_LABEL}_seed${SEED}"
OUTPUT_ROOT="${OUTPUT_ROOT:-$PROJECT_DIR/output/hpc/qwen38-fp8}"
OUTPUT_DIR="${OUTPUT_DIR:-$OUTPUT_ROOT/job-${SLURM_JOB_ID}/$RUN_NAME}"
[[ ! -e "$OUTPUT_DIR" ]] || fail "Output already exists: $OUTPUT_DIR"
cd "$PROJECT_DIR"

printf 'Qwen job: %s\nQwen node: %s\nQwen API: %s\nModel: %s\n' \
    "$QWEN_JOB_ID" "$QWEN_NODE" "$AGENTIC_BASE_URL" "$MODEL_NAME"
printf 'MATSim node: %s\nDays: %s\nLLM people: %s\nDemand: %s%%\nConcurrency: %s\nMemory: %s\nOutput: %s\n' \
    "$(hostname)" "$DAYS" "$LLM_AGENTS" "$DEMAND" "$CONCURRENCY" "$MEMORY_MODE" "$OUTPUT_DIR"
printf 'Response format: %s (no automatic downgrade if unsupported)\n' "$RESPONSE_FORMAT"

experiment_args=(
    --config "$CONFIG" --mode hybrid --policy llm
    --days "$DAYS" --llm-agents "$LLM_AGENTS" --demand "$DEMAND" --sample-persons 0
    --seed "$SEED" --run-date "$RUN_DATE" --output "$OUTPUT_DIR"
    --peak-start 25200 --peak-end 32400 --analysis-start 23400 --analysis-end 39600
    --concurrency "$CONCURRENCY" --repair-attempts "$REPAIR_ATTEMPTS"
    --timeout-seconds "$TIMEOUT_SECONDS" --llm-stall-seconds "$LLM_STALL_SECONDS"
    --max-requests "$MAX_REQUESTS"
    --model "$MODEL_NAME" --base-url "$AGENTIC_BASE_URL"
    --thinking omit --generation-params "$QWEN_GENERATION_PARAMS"
    --token-limit-field max_tokens --response-format "$RESPONSE_FORMAT"
    --max-output-tokens "$MAX_OUTPUT_TOKENS" --reason-max-chars 240
    --personal-profiles legacy --profile-seed "$SEED"
    --departure-before-seconds "$DEPARTURE_BEFORE_SECONDS" --departure-after-seconds "$DEPARTURE_AFTER_SECONDS" --departure-step-seconds "$DEPARTURE_STEP_SECONDS"
    --memory-mode "$MEMORY_MODE" --history-window "$HISTORY_WINDOW"
    --memory-max-chars "$MEMORY_MAX_CHARS" --memory-max-output-tokens "$MEMORY_MAX_OUTPUT_TOKENS"
    --candidate-order original --candidate-order-seed 0
    --candidate-search-limit "$CANDIDATE_SEARCH_LIMIT" --candidate-pool-size "$CANDIDATE_POOL_SIZE"
    --candidate-max-time-ratio "$CANDIDATE_MAX_TIME_RATIO" --candidate-time-slack-seconds "$CANDIDATE_TIME_SLACK_SECONDS"
    --candidate-max-distance-ratio "$CANDIDATE_MAX_DISTANCE_RATIO" --candidate-distance-slack-meters "$CANDIDATE_DISTANCE_SLACK_METERS"
    --candidate-max-overlap "$CANDIDATE_MAX_OVERLAP"
    --trip-output-interval 1 --context-samples 20
)
if [[ "$ENABLE_MANAGER" == 1 ]]; then
    experiment_args+=(--manager --manager-profile "$MANAGER_PROFILE" --manager-update-interval "$MANAGER_UPDATE_INTERVAL" --manager-max-output-tokens "$MANAGER_MAX_OUTPUT_TOKENS")
elif [[ "$ENABLE_MANAGER" != 0 ]]; then fail 'ENABLE_MANAGER must be 0 or 1.'; fi
if [[ -n "${CANDIDATE_FILE:-}" ]]; then
    [[ -f "$CANDIDATE_FILE" ]] || fail "Candidate file not found: $CANDIDATE_FILE"
    experiment_args+=(--candidate-file "$CANDIDATE_FILE")
fi
if [[ -n "${PROFILE_FILE:-}" ]]; then
    [[ -f "$PROFILE_FILE" ]] || fail "Profile file not found: $PROFILE_FILE"
    experiment_args+=(--profile-file "$PROFILE_FILE")
fi

printf 'MATSim start: %s\n' "$(date --iso-8601=seconds)"
srun --ntasks=1 "$JAVA_BIN" --enable-native-access=ALL-UNNAMED -Xms2g "-Xmx$JAVA_HEAP" \
    -cp "$JAR" org.matsim.agentic.MorningExperiment "${experiment_args[@]}"
printf 'MATSim completed: %s\nResults: %s\n' "$(date --iso-8601=seconds)" "$OUTPUT_DIR"
# This script deliberately does not cancel the existing Qwen job.
