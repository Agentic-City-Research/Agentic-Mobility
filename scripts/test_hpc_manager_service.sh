#!/bin/bash
# Offline Slurm-wrapper checks: fake Java, fake /models, no scheduler or network.
set -euo pipefail
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
TEST_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEST_WRAPPER="$TEST_SCRIPT_DIR/agentic_morning_qwen35_deepseek_manager.slurm"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/manager-service-test.XXXXXXXX")"
export PROJECT_DIR="$TEST_ROOT/project"
mkdir -p "$PROJECT_DIR/scripts" "$TEST_ROOT/bin"
export JAR="$PROJECT_DIR/matsim-example-project-0.0.1-SNAPSHOT.jar"
unset SAMPLE_RATE POPULATION_SEED SAMPLE_SEED
export TEST_ROOT
export SLURM_JOB_ID=offline-wrapper-test
export JAVA_BIN="$TEST_ROOT/bin/java"
export PYTHON_BIN="${PYTHON_BIN:-python3}"
export PATH="$TEST_ROOT/bin:$PATH"
export MANAGER_API_KEY_FILE="$TEST_ROOT/manager-key"
export AGENTIC_API_KEY=qwen-test-key
export EXPECTED_MANAGER_KEY=manager-file-key
for suffix in BASE_URL MODEL THINKING RESPONSE_FORMAT GENERATION_PARAMS TOKEN_LIMIT_FIELD TIMEOUT_SECONDS TEMPERATURE TOP_P GENERATION_SEED API_KEY; do
    unset "AGENTIC_MANAGER_$suffix"
done
unset DEEPSEEK_API_KEY
export VALIDATE_ONLY=0 MANAGER_PREFLIGHT=1 MOCK_OLD_JAR=0 MOCK_CURL_FAIL=0 MOCK_MODEL_MISSING=0
printf 'manager-file-key\n' > "$MANAGER_API_KEY_FILE"
: > "$PROJECT_DIR/matsim-example-project-0.0.1-SNAPSHOT.jar"
cat > "$JAVA_BIN" <<'STUB'
#!/bin/bash
if [[ "${MOCK_OLD_JAR:-0}" == 1 ]]; then printf '%s\n' '--help';
else printf '%s\n' '--manager-base-url --manager-response-format'; fi
STUB
cat > "$TEST_ROOT/bin/curl" <<'STUB'
#!/bin/bash
printf 'called\n' >> "$TEST_ROOT/curl-called"
[[ "${MOCK_CURL_FAIL:-0}" == 0 ]] || exit 7
config="$(cat)"
[[ "$config" == "header = \"Authorization: Bearer $EXPECTED_MANAGER_KEY\"" ]] || exit 89
if [[ "${MOCK_MODEL_MISSING:-0}" == 1 ]]; then printf '%s\n' '{"data":[{"id":"missing-model"}]}';
else printf '%s\n' '{"data":[{"id":"deepseek-flash"}]}'; fi
STUB
cat > "$PROJECT_DIR/scripts/agentic_morning_qwen35.slurm" <<'STUB'
#!/bin/bash
set -euo pipefail
# Simulate the existing runner selecting the Qwen job without touching manager variables.
export AGENTIC_BASE_URL=http://qwen.internal:8002/v1
export AGENTIC_MODEL=qwen-fixture
export AGENTIC_API_KEY=qwen-test-key
[[ "$ENABLE_MANAGER" == 1 && "$SAMPLE_RATE" == 0.2 && "$POPULATION_SEED" == 6021 ]] || exit 90
[[ "$AGENTIC_MANAGER_BASE_URL" == https://api.deepseek.com ]] || exit 91
[[ "$AGENTIC_MANAGER_MODEL" == deepseek-flash && "$AGENTIC_MANAGER_RESPONSE_FORMAT" == json_object ]] || exit 92
[[ "$AGENTIC_MANAGER_THINKING" == disabled && "$AGENTIC_MANAGER_GENERATION_PARAMS" == '{}' ]] || exit 93
if [[ "${VALIDATE_ONLY:-0}" == 0 ]]; then
    [[ "$AGENTIC_MANAGER_API_KEY" == "$EXPECTED_MANAGER_KEY" && "$AGENTIC_MANAGER_API_KEY" != "$AGENTIC_API_KEY" ]] || exit 94
fi
printf 'called\n' > "$TEST_ROOT/runner-called"
STUB
chmod +x "$JAVA_BIN" "$TEST_ROOT/bin/curl"

invoke() {
    : > "$TEST_ROOT/runner-called"; : > "$TEST_ROOT/curl-called"
    if bash "$TEST_WRAPPER" > "$TEST_ROOT/stdout" 2> "$TEST_ROOT/stderr"; then TEST_CODE=0; else TEST_CODE=$?; fi
    for key in manager-file-key manager-env-key manager-fallback-key qwen-test-key; do
        [[ "$(< "$TEST_ROOT/stdout")$(< "$TEST_ROOT/stderr")" != *"$key"* ]] || fail 'A credential appeared in logs.'
    done
}
invoke
[[ "$TEST_CODE" == 0 && -s "$TEST_ROOT/runner-called" && -s "$TEST_ROOT/curl-called" ]] || fail 'File credential and preflight.'

export AGENTIC_MANAGER_API_KEY=manager-env-key EXPECTED_MANAGER_KEY=manager-env-key
export DEEPSEEK_API_KEY=manager-fallback-key
invoke
[[ "$TEST_CODE" == 0 && -s "$TEST_ROOT/runner-called" ]] || fail 'Explicit manager key must win.'
unset AGENTIC_MANAGER_API_KEY
export EXPECTED_MANAGER_KEY=manager-fallback-key
invoke
[[ "$TEST_CODE" == 0 && -s "$TEST_ROOT/runner-called" ]] || fail 'DeepSeek environment fallback.'
unset DEEPSEEK_API_KEY

export MANAGER_API_KEY_FILE="$TEST_ROOT/missing-key" VALIDATE_ONLY=1
invoke
[[ "$TEST_CODE" == 0 && -s "$TEST_ROOT/runner-called" && ! -s "$TEST_ROOT/curl-called" ]] || fail 'Validate-only must not need a manager key or contact the manager.'
export VALIDATE_ONLY=0
invoke
[[ "$TEST_CODE" != 0 && ! -s "$TEST_ROOT/runner-called" && ! -s "$TEST_ROOT/curl-called" ]] || fail 'Missing manager key must not fall back to Qwen.'

export MANAGER_API_KEY_FILE="$TEST_ROOT/manager-key" EXPECTED_MANAGER_KEY=manager-file-key MOCK_CURL_FAIL=1
invoke
[[ "$TEST_CODE" != 0 && ! -s "$TEST_ROOT/runner-called" ]] || fail 'Network failure must stop before MATSim.'
export MOCK_CURL_FAIL=0 MOCK_MODEL_MISSING=1
invoke
[[ "$TEST_CODE" != 0 && ! -s "$TEST_ROOT/runner-called" ]] || fail 'Missing model must stop before MATSim.'
export MOCK_MODEL_MISSING=0 MOCK_OLD_JAR=1
invoke
[[ "$TEST_CODE" != 0 && ! -s "$TEST_ROOT/runner-called" ]] || fail 'Old JAR must be rejected.'
printf 'PASS: 8 offline wrapper cases; key separation/precedence, preflight, validation, network/model failures and old-JAR rejection. Fixtures: %s\n' "$TEST_ROOT"
