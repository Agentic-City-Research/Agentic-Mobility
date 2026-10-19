#!/usr/bin/env bash
# Single-GPU cloud host, no Slurm. Calls an already running Qwen service.
set -euo pipefail
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Experiment settings. Environment variables override these defaults.
DAYS="${DAYS:-100}"
SAMPLE_RATE="${SAMPLE_RATE:-0.2}"
DEMAND="${DEMAND:-100}"
LLM_AGENTS="${LLM_AGENTS:-200}"  # all = every sampled morning-cohort person
CONCURRENCY="${CONCURRENCY:-16}"
SEED="${SEED:-9261}"
POPULATION_SEED="${POPULATION_SEED:-6021}"
PROFILE_SEED="${PROFILE_SEED:-6021}"
MAX_REQUESTS="${MAX_REQUESTS:-40000000}"
EVENTS_INTERVAL="${EVENTS_INTERVAL:-10}"

# Paths from the cloud installation guide; the system disk is not used for runs.
WORK_DIR="${WORK_DIR:-/root/autodl-tmp/agentic-cloud}"
PROJECT_DIR="${PROJECT_DIR:-$WORK_DIR/Agentic-Mobility}"
JAR="${JAR:-$PROJECT_DIR/matsim-example-project-0.0.1-SNAPSHOT.jar}"
CONFIG="${CONFIG:-$PROJECT_DIR/scenarios/SiouxFalls-public/config.xml}"
JAVA_BIN="${JAVA_BIN:-$WORK_DIR/opt/java25/bin/java}"
PYTHON_BIN="${PYTHON_BIN:-$WORK_DIR/venv/bin/python}"
JAVA_HEAP="${JAVA_HEAP:-12g}"
API_KEY_FILE="${API_KEY_FILE:-$WORK_DIR/api.key}"
MODEL_NAME="${MODEL_NAME:-qwen3.5-9b}"
export AGENTIC_BASE_URL="${AGENTIC_BASE_URL:-http://127.0.0.1:8002/v1}"
export AGENTIC_MODEL="$MODEL_NAME"

[[ -d "$PROJECT_DIR" ]] || fail "Project not found: $PROJECT_DIR"
[[ -f "$JAR" ]] || fail "JAR not found; build the project first: $JAR"
[[ -f "$CONFIG" ]] || fail "Config not found: $CONFIG"
[[ -x "$JAVA_BIN" ]] || fail "Java 25 not found; set JAVA_BIN: $JAVA_BIN"
[[ -x "$PYTHON_BIN" ]] || fail "Python not found; set PYTHON_BIN: $PYTHON_BIN"
if [[ -z "${AGENTIC_API_KEY:-}" ]]; then
    [[ -s "$API_KEY_FILE" ]] || fail "Set AGENTIC_API_KEY or provide $API_KEY_FILE"
    export AGENTIC_API_KEY="$(tr -d '\r\n' < "$API_KEY_FILE")"
fi

RUN_ID="$(TZ=Asia/Shanghai date +%Y-%m-%d_%H-%M-%S)-$$"
RUN_DIR="${RUN_DIR:-$WORK_DIR/runs/sample${SAMPLE_RATE}_llm${LLM_AGENTS}_${RUN_ID}}"
[[ ! -e "$RUN_DIR" ]] || fail "Run directory already exists: $RUN_DIR"
mkdir -p "$RUN_DIR"
exec > >(tee -a "$RUN_DIR/console.log") 2>&1
cp -- "${BASH_SOURCE[0]}" "$RUN_DIR/launcher.sh"
printf 'Run directory: %s\nManager: disabled\n' "$RUN_DIR"
"$JAVA_BIN" -version
sha256sum "$JAR" > "$RUN_DIR/jar.sha256"

# Preserve the original config. Absolute inputs keep relative scenario paths
# valid when the run's config is stored outside the scenario directory.
"$PYTHON_BIN" - "$CONFIG" "$RUN_DIR/config.xml" "$SAMPLE_RATE" "$DAYS" \
    "$CONCURRENCY" "$LLM_AGENTS" "$DEMAND" "$MAX_REQUESTS" "$EVENTS_INTERVAL" <<'PY'
import math
import sys
from pathlib import Path
from xml.dom import minidom

source, target = map(Path, sys.argv[1:3])
rate = float(sys.argv[3])
assert math.isfinite(rate) and 0 < rate <= 1, 'SAMPLE_RATE must be in (0,1].'
for index, name in ((4, 'DAYS'), (5, 'CONCURRENCY'), (9, 'EVENTS_INTERVAL')):
    assert int(sys.argv[index]) > 0, f'{name} must be positive.'
assert sys.argv[6] == 'all' or int(sys.argv[6]) > 0, 'LLM_AGENTS must be positive or all.'
assert int(sys.argv[7]) in (100, 200), 'DEMAND must be 100 or 200.'
assert int(sys.argv[8]) >= 0, 'MAX_REQUESTS must be nonnegative; 0 means unlimited.'
doc = minidom.parse(str(source))
input_fields = {'network': 'inputNetworkFile', 'plans': 'inputPlansFile',
                'facilities': 'inputFacilitiesFile'}
for module in doc.getElementsByTagName('module'):
    name = module.getAttribute('name')
    for param in module.getElementsByTagName('param'):
        field = param.getAttribute('name')
        if name == 'controller' and field in ('writeEventsInterval', 'writePlansInterval'):
            param.setAttribute('value', sys.argv[9])
        if input_fields.get(name) == field:
            param.setAttribute('value', str((source.resolve().parent / param.getAttribute('value')).resolve()))
target.write_bytes(doc.toxml(encoding='UTF-8'))
print(f'Sample={rate}; flow factor={rate}; storage factor={rate ** 0.75:.9f}')
print(f'Days={sys.argv[4]}; LLM people={sys.argv[6]}; concurrency={sys.argv[5]}')
PY

# This checks the model ID through JSON parsing, independent of JSON whitespace.
"$PYTHON_BIN" - <<'PY'
import json
import os
import urllib.request

url = os.environ['AGENTIC_BASE_URL'].rstrip('/') + '/models'
request = urllib.request.Request(url, headers={
    'Authorization': 'Bearer ' + os.environ['AGENTIC_API_KEY']})
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with opener.open(request, timeout=30) as response:
    models = [m['id'] for m in json.load(response).get('data', [])]
if os.environ['AGENTIC_MODEL'] not in models:
    raise SystemExit(f'Requested model is not advertised. Available models: {models}')
print('Qwen connection verified:', url)
PY

cd "$PROJECT_DIR"
args=(
    --config "$RUN_DIR/config.xml" --output "$RUN_DIR/output"
    --mode hybrid --policy llm --days "$DAYS" --llm-agents "$LLM_AGENTS"
    --demand "$DEMAND" --sample-rate "$SAMPLE_RATE"
    --seed "$SEED" --population-seed "$POPULATION_SEED"
    --personal-profiles legacy --profile-seed "$PROFILE_SEED"
    --cohort-selection planned-arrival --planned-arrival-before 32400
    --departure-before-seconds 5400 --departure-after-seconds 1800 --departure-step-seconds 900
    --peak-start 25200 --peak-end 32400 --analysis-start 23400 --analysis-end 39600
    --concurrency "$CONCURRENCY" --repair-attempts 3
    --timeout-seconds 300 --llm-stall-seconds 900 --max-requests "$MAX_REQUESTS"
    --model "$MODEL_NAME" --base-url "$AGENTIC_BASE_URL"
    --thinking omit --generation-params '{"chat_template_kwargs":{"enable_thinking":false}}'
    --token-limit-field max_tokens --response-format json_schema
    --max-output-tokens 1024 --reason-max-chars 120
    --memory-mode llm --history-window 3 --memory-max-chars 500 --memory-max-output-tokens 1024
    --candidate-order original --candidate-order-seed 0
    --trip-output-interval 1 --context-samples 20
)
# Leave unset to generate candidates; never point at a nonexistent cache file.
if [[ -n "${CANDIDATE_FILE:-}" ]]; then
    [[ -f "$CANDIDATE_FILE" ]] || fail "Candidate file not found: $CANDIDATE_FILE"
    args+=(--candidate-file "$CANDIDATE_FILE")
fi
command=("$JAVA_BIN" --enable-native-access=ALL-UNNAMED -Xms2g "-Xmx$JAVA_HEAP"
    -cp "$JAR" org.matsim.agentic.MorningExperiment "${args[@]}")
printf '%q ' "${command[@]}" > "$RUN_DIR/command.txt"
printf '\n' >> "$RUN_DIR/command.txt"
printf 'MATSim start: %s\n' "$(TZ=Asia/Shanghai date --iso-8601=seconds)"
"${command[@]}"
"$PYTHON_BIN" - "$RUN_DIR/output/morning/status.json" "$DAYS" <<'PY'
import json
import sys
with open(sys.argv[1], encoding='utf-8') as f:
    status = json.load(f)
print('Final status:', status)
if status.get('complete') is not True or status.get('daysCompleted') != int(sys.argv[2]):
    raise SystemExit('The experiment did not complete all requested days.')
PY
printf 'MATSim completed. Results: %s\n' "$RUN_DIR/output"
