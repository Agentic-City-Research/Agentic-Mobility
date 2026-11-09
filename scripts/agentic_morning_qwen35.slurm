#!/bin/bash
#SBATCH --job-name=morning-qwen
# Choose a site-specific partition with sbatch --partition=<partition>.
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=24:00:00
#SBATCH --output=morning-qwen-%j.out

# Calls an already running Qwen service. Upload the current JAR and scenarios/.
# Logs go to the submission directory; keep this file in LF format.
set -euo pipefail
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Defaults retained from the supplied 100-day, 200-person Qwen3.5 experiment.
DAYS="${DAYS:-100}"
LLM_AGENTS="${LLM_AGENTS:-200}"
MAX_REQUESTS="${MAX_REQUESTS:-40000000}"
CONCURRENCY="${CONCURRENCY:-60}"
SEED="${SEED:-9261}"
DEMAND="${DEMAND:-100}"
SAMPLE_RATE="${SAMPLE_RATE:-0.1}"
POPULATION_SEED="${POPULATION_SEED:-${SAMPLE_SEED:-4711}}"
ENABLE_MANAGER="${ENABLE_MANAGER:-0}"
NATIVE_TIME_MUTATION="${NATIVE_TIME_MUTATION:-1}"
MANAGER_MAX_OUTPUT_TOKENS="${MANAGER_MAX_OUTPUT_TOKENS:-8192}"
MANAGER_MEMORY_MAX_OUTPUT_TOKENS="${MANAGER_MEMORY_MAX_OUTPUT_TOKENS:-2048}"
MANAGER_UPDATE_INTERVAL="${MANAGER_UPDATE_INTERVAL:-30}"
INFORMED_SHARE="${INFORMED_SHARE:-0.5}"
INFORMATION_SCOPE="${INFORMATION_SCOPE:-controlled}"
INFORMATION_SEED="${INFORMATION_SEED:-$POPULATION_SEED}"
TOLL_HISTORY_BIN_SECONDS="${TOLL_HISTORY_BIN_SECONDS:-3600}"
MEMORY_MODE="${MEMORY_MODE:-llm}"
MAX_OUTPUT_TOKENS="${MAX_OUTPUT_TOKENS:-1024}"
REASON_MAX_CHARS="${REASON_MAX_CHARS:-120}"
TRAVELER_REFLECTION_INTERVAL="${TRAVELER_REFLECTION_INTERVAL:-5}"
HISTORY_WINDOW="${HISTORY_WINDOW:-$TRAVELER_REFLECTION_INTERVAL}"
DEPARTURE_BEFORE_SECONDS="${DEPARTURE_BEFORE_SECONDS:-3600}"
DEPARTURE_AFTER_SECONDS="${DEPARTURE_AFTER_SECONDS:-1800}"
DEPARTURE_STEP_SECONDS="${DEPARTURE_STEP_SECONDS:-900}"
MANAGER_PROFILE="${MANAGER_PROFILE:-basic}"
RESPONSE_FORMAT="${RESPONSE_FORMAT:-json_schema}"
TRAVELER_SEMANTIC_REVIEW="${TRAVELER_SEMANTIC_REVIEW:-0}"
SEMANTIC_REVIEW_MAX_OUTPUT_TOKENS="${SEMANTIC_REVIEW_MAX_OUTPUT_TOKENS:-2048}"
VALIDATE_ONLY="${VALIDATE_ONLY:-0}"
CANDIDATE_SEARCH_LIMIT="${CANDIDATE_SEARCH_LIMIT:-10000}"
CANDIDATE_POOL_SIZE="${CANDIDATE_POOL_SIZE:-16}"
CANDIDATE_MAX_TIME_RATIO="${CANDIDATE_MAX_TIME_RATIO:-2.5}"
CANDIDATE_TIME_SLACK_SECONDS="${CANDIDATE_TIME_SLACK_SECONDS:-300}"
CANDIDATE_MAX_DISTANCE_RATIO="${CANDIDATE_MAX_DISTANCE_RATIO:-2.5}"
CANDIDATE_DISTANCE_SLACK_METERS="${CANDIDATE_DISTANCE_SLACK_METERS:-2000}"
CANDIDATE_MAX_OVERLAP="${CANDIDATE_MAX_OVERLAP:-0.85}"

PROJECT_DIR="${PROJECT_DIR:-$HOME/matsim-example-morning}"
JAR="${JAR:-$PROJECT_DIR/matsim-example-project-0.0.1-SNAPSHOT.jar}"
# This config loads the original 100pct/200pct population. Sampling is done
# exactly once by --sample-rate; do not substitute an already sampled plans file.
CONFIG="${CONFIG:-$PROJECT_DIR/scenarios/SiouxFalls-public/config.xml}"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
JAVA_HEAP="${JAVA_HEAP:-12g}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
API_KEY_FILE="${API_KEY_FILE:-$HOME/.vllm_api_key}"

# These values identify the existing server, not the model checkpoint directory.
QWEN_JOB_NAME="${QWEN_JOB_NAME:-qwen35-9b}"
QWEN_JOB_ID="${QWEN_JOB_ID:-}"
QWEN_PORT="${QWEN_PORT:-8002}"
MODEL_NAME="${MODEL_NAME:-qwen3.5-9b}"
if [[ -z "${QWEN_GENERATION_PARAMS:-}" ]]; then
    QWEN_GENERATION_PARAMS='{"chat_template_kwargs":{"enable_thinking":false}}'
fi

[[ -n "${SLURM_JOB_ID:-}" ]] || fail 'Submit with sbatch.'
[[ -d "$PROJECT_DIR" ]] || fail "Project directory not found: $PROJECT_DIR"
[[ -f "$JAR" ]] || fail "Current morning JAR not found: $JAR"
[[ -f "$CONFIG" ]] || fail "Original-population config not found: $CONFIG"
[[ -x "$JAVA_BIN" ]] || fail "Java executable not found: $JAVA_BIN"
[[ -x "$PYTHON_BIN" ]] || fail "Python executable not found: $PYTHON_BIN"
[[ -s "$API_KEY_FILE" ]] || fail "API key file not found or empty: $API_KEY_FILE"
for cmd in squeue scontrol srun curl tr date id hostname sha256sum cp mktemp rm rmdir; do
    command -v "$cmd" >/dev/null || fail "Missing command: $cmd"
done
[[ "$DAYS" =~ ^[1-9][0-9]*$ ]] || fail 'DAYS must be positive.'
[[ "$LLM_AGENTS" == all || "$LLM_AGENTS" =~ ^[0-9]+$ ]] || fail 'LLM_AGENTS must be a nonnegative count or all.'
[[ "$CONCURRENCY" =~ ^[1-9][0-9]*$ ]] || fail 'CONCURRENCY must be positive.'
[[ "$DEMAND" == 100 || "$DEMAND" == 200 ]] || fail 'DEMAND must be 100 or 200.'
[[ "$MAX_REQUESTS" =~ ^[0-9]+$ ]] || fail 'MAX_REQUESTS must be nonnegative; 0 removes the limit.'
[[ "$ENABLE_MANAGER" == 0 || "$ENABLE_MANAGER" == 1 ]] || fail 'ENABLE_MANAGER must be 0 or 1.'
[[ "$NATIVE_TIME_MUTATION" == 0 || "$NATIVE_TIME_MUTATION" == 1 ]] || fail 'NATIVE_TIME_MUTATION must be 0 or 1.'
[[ "$MANAGER_PROFILE" == basic || "$MANAGER_PROFILE" == controlled ]] || fail 'MANAGER_PROFILE must be basic or controlled.'
[[ "$MANAGER_UPDATE_INTERVAL" =~ ^[1-9][0-9]*$ ]] || fail 'MANAGER_UPDATE_INTERVAL must be positive.'
[[ "$VALIDATE_ONLY" == 0 || "$VALIDATE_ONLY" == 1 ]] || fail 'VALIDATE_ONLY must be 0 or 1.'
[[ "$INFORMATION_SCOPE" == controlled || "$INFORMATION_SCOPE" == all ]] || fail 'INFORMATION_SCOPE must be controlled or all.'
[[ "$MEMORY_MODE" == llm ]] || fail 'MEMORY_MODE only supports llm.'
[[ "$TRAVELER_REFLECTION_INTERVAL" == 1 || "$TRAVELER_REFLECTION_INTERVAL" == 5 ]] || fail 'TRAVELER_REFLECTION_INTERVAL must be 1 or 5.'
[[ "$HISTORY_WINDOW" =~ ^[1-5]$ ]] || fail 'HISTORY_WINDOW must be 1..5.'
[[ "$TRAVELER_SEMANTIC_REVIEW" == 0 || "$TRAVELER_SEMANTIC_REVIEW" == 1 ]] || fail 'TRAVELER_SEMANTIC_REVIEW must be 0 or 1.'
for value_name in MAX_OUTPUT_TOKENS REASON_MAX_CHARS SEMANTIC_REVIEW_MAX_OUTPUT_TOKENS; do
    [[ "${!value_name}" =~ ^[1-9][0-9]*$ ]] || fail "$value_name must be positive."
done
[[ "$RESPONSE_FORMAT" == json_schema || "$RESPONSE_FORMAT" == json_object || "$RESPONSE_FORMAT" == omit ]] || fail 'Invalid RESPONSE_FORMAT.'
"$PYTHON_BIN" -c '
import json, math, sys
rate, share = map(float, sys.argv[1:3])
if not (math.isfinite(rate) and 0 < rate <= 1):
    raise SystemExit("SAMPLE_RATE must be in (0,1].")
if not (math.isfinite(share) and 0 <= share <= 1):
    raise SystemExit("INFORMED_SHARE must be in [0,1].")
if not isinstance(json.loads(sys.argv[3]), dict):
    raise SystemExit("QWEN_GENERATION_PARAMS must be a JSON object.")
' "$SAMPLE_RATE" "$INFORMED_SHARE" "$QWEN_GENERATION_PARAMS"

# A private JAR also protects an active job from replacement during uploads.
RUNTIME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/matsim-morning.XXXXXXXX")"
RUNTIME_JAR="$RUNTIME_DIR/experiment.jar"
cleanup() { rm -f -- "$RUNTIME_JAR"; rmdir -- "$RUNTIME_DIR" 2>/dev/null || true; }
trap cleanup EXIT
SOURCE_SHA="$(sha256sum "$JAR")"; SOURCE_SHA="${SOURCE_SHA%% *}"
cp -- "$JAR" "$RUNTIME_JAR"
RUNTIME_SHA="$(sha256sum "$RUNTIME_JAR")"; RUNTIME_SHA="${RUNTIME_SHA%% *}"
[[ "$SOURCE_SHA" == "$RUNTIME_SHA" ]] || fail 'JAR changed while copying; upload completely before submission.'
JAR_HELP="$("$JAVA_BIN" -cp "$RUNTIME_JAR" org.matsim.agentic.MorningExperiment --help)" || fail 'Cannot read MorningExperiment help.'
for option in --sample-rate --population-seed --informed-share --information-scope --manager-max-output-tokens --manager-update-interval --response-format \
    --candidate-pool-size --candidate-time-slack-seconds --candidate-distance-slack-meters \
    --departure-before-seconds --departure-after-seconds --departure-step-seconds --manager-profile --manager-memory-max-output-tokens --traveler-semantic-review native-time-mutation; do
    [[ "$JAR_HELP" == *"$option"* ]] || fail "Uploaded JAR is too old: missing $option"
done
[[ "$JAR_HELP" == *json_schema* ]] || fail 'Uploaded JAR lacks json_schema support.'
printf 'Source JAR: %s\nRuntime JAR SHA256: %s\n' "$JAR" "$RUNTIME_SHA"

HPC_USER="$(id -un)"
if [[ -z "$QWEN_JOB_ID" ]]; then
    jobs="$(squeue -h -u "$HPC_USER" -n "$QWEN_JOB_NAME" -t RUNNING -o '%i')"
    ids=()
    while IFS= read -r job; do [[ -z "$job" ]] || ids+=("$job"); done <<< "$jobs"
    [[ ${#ids[@]} -eq 1 ]] || fail "Found ${#ids[@]} running $QWEN_JOB_NAME jobs; set QWEN_JOB_ID explicitly."
    QWEN_JOB_ID="${ids[0]}"
fi
nodes="$(squeue -h -u "$HPC_USER" -j "$QWEN_JOB_ID" -t RUNNING -o '%N')"
[[ -n "$nodes" ]] || fail "Qwen job $QWEN_JOB_ID is not RUNNING."
QWEN_NODE="$(scontrol show hostnames "$nodes")"
[[ -n "$QWEN_NODE" && "$QWEN_NODE" != *$'\n'* ]] || fail 'The Qwen service must occupy one node.'

export AGENTIC_API_KEY="$(tr -d '\r\n' < "$API_KEY_FILE")"
[[ -n "$AGENTIC_API_KEY" ]] || fail 'API key is blank.'
export AGENTIC_BASE_URL="http://${QWEN_NODE}:${QWEN_PORT}/v1"
export AGENTIC_MODEL="$MODEL_NAME"
if ! models_json="$(printf 'header = "Authorization: Bearer %s"\n' "$AGENTIC_API_KEY" |
    curl --config - --noproxy '*' -fsS --connect-timeout 10 --max-time 30 "$AGENTIC_BASE_URL/models")"; then
    fail "Cannot reach Qwen from the MATSim node: $AGENTIC_BASE_URL"
fi
printf '%s' "$models_json" | "$PYTHON_BIN" -c \
    'import json,sys; d=json.load(sys.stdin); ids=[m.get("id") for m in d.get("data",[])]; sys.exit(0 if sys.argv[1] in ids else "MODEL_NAME not advertised; available IDs: " + repr(ids))' \
    "$MODEL_NAME"

RUN_DATE="${RUN_DATE:-$(TZ=Asia/Shanghai date +%F)}"
MANAGER_LABEL=noMgr
[[ "$ENABLE_MANAGER" == 0 ]] || MANAGER_LABEL=mgr
RUN_NAME="${RUN_DATE}_day${DAYS}_llm${LLM_AGENTS}_${MANAGER_LABEL}_seed${SEED}"
MODEL_SLUG="${MODEL_NAME//[^a-zA-Z0-9._-]/_}"
OUTPUT_ROOT="${OUTPUT_ROOT:-$PROJECT_DIR/output/hpc/$MODEL_SLUG/sample$SAMPLE_RATE}"
OUTPUT_DIR="${OUTPUT_DIR:-$OUTPUT_ROOT/job-${SLURM_JOB_ID}/$RUN_NAME}"
[[ ! -e "$OUTPUT_DIR" ]] || fail "Output already exists: $OUTPUT_DIR"
cd "$PROJECT_DIR"
printf 'Qwen job/node: %s / %s\nAPI: %s\nModel: %s\n' "$QWEN_JOB_ID" "$QWEN_NODE" "$AGENTIC_BASE_URL" "$MODEL_NAME"
printf 'Days: %s\nLLM people: %s\nDemand: %s%%\nPopulation sample rate k (flow=k; storage=k^0.75): %s\nManager: %s\nConcurrency: %s\n' \
    "$DAYS" "$LLM_AGENTS" "$DEMAND" "$SAMPLE_RATE" "$ENABLE_MANAGER" "$CONCURRENCY"
printf 'Population sampling seed: %s\nSimulation seed: %s\n' "$POPULATION_SEED" "$SEED"
printf 'Information scope/share/seed: %s / %s / %s\nMemory: %s\nResponse format: %s\nOutput: %s\n' \
    "$INFORMATION_SCOPE" "$INFORMED_SHARE" "$INFORMATION_SEED" "$MEMORY_MODE" "$RESPONSE_FORMAT" "$OUTPUT_DIR"

experiment_args=(
    --config "$CONFIG" --mode hybrid --policy llm
    --days "$DAYS" --llm-agents "$LLM_AGENTS" --demand "$DEMAND" --sample-rate "$SAMPLE_RATE"
    --population-seed "$POPULATION_SEED"
    --seed "$SEED" --run-date "$RUN_DATE" --output "$OUTPUT_DIR"
    --peak-start 25200 --peak-end 32400 --analysis-start 23400 --analysis-end 39600
    --informed-share "$INFORMED_SHARE" --information-scope "$INFORMATION_SCOPE"
    --information-seed "$INFORMATION_SEED" --toll-history-bin-seconds "$TOLL_HISTORY_BIN_SECONDS"
    --concurrency "$CONCURRENCY" --repair-attempts 3 --timeout-seconds 300 --llm-stall-seconds 900
    --max-requests "$MAX_REQUESTS" --model "$MODEL_NAME" --base-url "$AGENTIC_BASE_URL"
    --thinking omit --generation-params "$QWEN_GENERATION_PARAMS"
    --token-limit-field max_tokens --response-format "$RESPONSE_FORMAT"
    --max-output-tokens "$MAX_OUTPUT_TOKENS" --reason-max-chars "$REASON_MAX_CHARS"
    --personal-profiles legacy --profile-seed "$POPULATION_SEED"
    --departure-before-seconds "$DEPARTURE_BEFORE_SECONDS" --departure-after-seconds "$DEPARTURE_AFTER_SECONDS" --departure-step-seconds "$DEPARTURE_STEP_SECONDS"
    --memory-mode "$MEMORY_MODE" --history-window "$HISTORY_WINDOW" --traveler-reflection-interval "$TRAVELER_REFLECTION_INTERVAL" --memory-max-chars 500 --memory-max-output-tokens 1024
    --candidate-order original --candidate-order-seed 0 --trip-output-interval 1 --context-samples 20
    --candidate-search-limit "$CANDIDATE_SEARCH_LIMIT" --candidate-pool-size "$CANDIDATE_POOL_SIZE"
    --candidate-max-time-ratio "$CANDIDATE_MAX_TIME_RATIO" --candidate-time-slack-seconds "$CANDIDATE_TIME_SLACK_SECONDS"
    --candidate-max-distance-ratio "$CANDIDATE_MAX_DISTANCE_RATIO" --candidate-distance-slack-meters "$CANDIDATE_DISTANCE_SLACK_METERS"
    --candidate-max-overlap "$CANDIDATE_MAX_OVERLAP"
)
if [[ "$TRAVELER_SEMANTIC_REVIEW" == 1 ]]; then experiment_args+=(--traveler-semantic-review --semantic-review-max-output-tokens "$SEMANTIC_REVIEW_MAX_OUTPUT_TOKENS"); fi
if [[ "$ENABLE_MANAGER" == 1 ]]; then experiment_args+=(--manager --manager-profile "$MANAGER_PROFILE" --manager-update-interval "$MANAGER_UPDATE_INTERVAL" --manager-max-output-tokens "$MANAGER_MAX_OUTPUT_TOKENS" --manager-memory-max-output-tokens "$MANAGER_MEMORY_MAX_OUTPUT_TOKENS"); fi
if [[ "$NATIVE_TIME_MUTATION" == 0 ]]; then experiment_args+=(--no-native-time-mutation); fi
if [[ "$VALIDATE_ONLY" == 1 ]]; then experiment_args+=(--validate-only); fi
for entry in CANDIDATE_FILE PROFILE_FILE; do
    value="${!entry:-}"
    if [[ -n "$value" ]]; then
        [[ -f "$value" ]] || fail "$entry file not found: $value"
        if [[ "$entry" == CANDIDATE_FILE ]]; then experiment_args+=(--candidate-file "$value"); else experiment_args+=(--profile-file "$value"); fi
    fi
done
printf 'MATSim start: %s\n' "$(date --iso-8601=seconds)"
srun --ntasks=1 "$JAVA_BIN" --enable-native-access=ALL-UNNAMED -Xms2g "-Xmx$JAVA_HEAP" \
    -cp "$RUNTIME_JAR" org.matsim.agentic.MorningExperiment "${experiment_args[@]}"
printf 'MATSim completed: %s\nResults: %s\n' "$(date --iso-8601=seconds)" "$OUTPUT_DIR"
