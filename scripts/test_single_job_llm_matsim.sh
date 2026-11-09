#!/usr/bin/env bash
# Offline lifecycle check for the Slurm launcher; no GPU, model or MATSim required.
set -Eeuo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
root="$(mktemp -d /tmp/agentic-launcher-test.XXXXXXXX)"
cleanup() { case "$root" in /tmp/agentic-launcher-test.*) rm -rf -- "$root";; *) echo 'Unsafe test directory' >&2; exit 1;; esac; }
trap cleanup EXIT
mkdir -p "$root/home/miniforge3/etc/profile.d" "$root/model" "$root/project/scenarios/SiouxFalls-public" "$root/bin"
printf 'dummy jar' > "$root/project/matsim-example-project-0.0.1-SNAPSHOT.jar"
printf 'dummy config' > "$root/project/scenarios/SiouxFalls-public/config.xml"
printf '{}' > "$root/model/config.json"
printf 'test-key' > "$root/home/.vllm_api_key"
printf 'conda() { :; }\n' > "$root/home/miniforge3/etc/profile.d/conda.sh"
module() { :; }; export -f module
cat > "$root/bin/mock-vllm" <<'SH'
#!/usr/bin/env bash
[[ "${MOCK_VLLM_FAIL:-0}" != 1 ]] || exit 42
exec python3 "$MOCK_ROOT/server.py" "$@"
SH
cat > "$root/server.py" <<'PY'
from http.server import BaseHTTPRequestHandler, HTTPServer
import json, os, sys, threading
args=sys.argv[1:]; port=int(args[args.index('--port')+1]); name=args[args.index('--served-model-name')+1]
open(os.environ['MOCK_ROOT']+'/server.pid','w').write(str(os.getpid()))
if os.environ.get('MOCK_VLLM_CRASH') == '1':
    timer=threading.Timer(7,lambda:os._exit(91)); timer.daemon=True; timer.start()
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/v1/models' or self.headers.get('Authorization') != 'Bearer test-key':
            self.send_response(403); self.end_headers(); return
        body=json.dumps({'data':[{'id':name}]}).encode()
        self.send_response(200); self.end_headers(); self.wfile.write(body)
    def log_message(self,*args): pass
HTTPServer(('127.0.0.1',port), Handler).serve_forever()
PY
cat > "$root/bin/mock-java" <<'PY'
#!/usr/bin/env python3
import json, os, pathlib, sys, time
args=sys.argv[1:]
assert args[args.index('--history-window')+1]=='5'
assert args[args.index('--traveler-reflection-interval')+1]=='5'
assert args[args.index('--manager-max-output-tokens')+1]=='2048'
assert os.environ['AGENTIC_BASE_URL'].startswith('http://127.0.0.1:')
assert not any(k.startswith('AGENTIC_MANAGER_') for k in os.environ), 'local manager must not inherit remote service'
if os.environ.get('MOCK_JAVA_FAIL') == '1': sys.exit(7)
if os.environ.get('MOCK_JAVA_WAIT') == '1':
    (pathlib.Path(os.environ['MOCK_ROOT'])/'java.pid').write_text(str(os.getpid()))
    time.sleep(30)
out=pathlib.Path(args[args.index('--output')+1])/'morning'
out.mkdir(parents=True)
(out/'status.json').write_text(json.dumps({'complete':True,'daysCompleted':2,'integrityVerified':True}),encoding='utf-8')
PY
chmod +x "$root/bin/mock-vllm" "$root/bin/mock-java"
export HOME="$root/home" PROJECT_DIR="$root/project" MODEL_PATH="$root/model" MOCK_ROOT="$root"
export VLLM_BIN="$root/bin/mock-vllm" JAVA_BIN="$root/bin/mock-java" SLURM_JOB_ID=offline-test
export ENABLE_MANAGER=1 MANAGER_SERVICE=local AGENTIC_MANAGER_BASE_URL=https://unexpected.invalid
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'; }
export PORT="$(free_port)"
export READY_TIMEOUT=20
bash "$script_dir/run_single_job_llm_matsim.slurm" > "$root/success.log" 2>&1 || { cat "$root/success.log"; exit 1; }
pid="$(cat "$root/server.pid")"
if kill -0 "$pid" 2>/dev/null; then echo 'Model service survived successful run' >&2; exit 1; fi
[[ -f "$root/project/output/hpc/single-job-offline-test/morning/status.json" ]]
export SLURM_JOB_ID=offline-java-failure MOCK_JAVA_FAIL=1
export PORT="$(free_port)"
if bash "$script_dir/run_single_job_llm_matsim.slurm" > "$root/java-failure.log" 2>&1; then
    echo 'Simulation failure was not propagated' >&2; exit 1
fi
pid="$(cat "$root/server.pid")"
if kill -0 "$pid" 2>/dev/null; then echo 'Model service survived simulation failure' >&2; exit 1; fi
unset MOCK_JAVA_FAIL
export SLURM_JOB_ID=offline-model-crash MOCK_VLLM_CRASH=1 MOCK_JAVA_WAIT=1
export PORT="$(free_port)"
if bash "$script_dir/run_single_job_llm_matsim.slurm" > "$root/model-crash.log" 2>&1; then
    echo 'Model failure during simulation was not propagated' >&2; exit 1
fi
java_pid="$(cat "$root/java.pid")"
if kill -0 "$java_pid" 2>/dev/null; then echo 'Simulation survived model failure' >&2; exit 1; fi
unset MOCK_VLLM_CRASH MOCK_JAVA_WAIT
export SLURM_JOB_ID=offline-failure MOCK_VLLM_FAIL=1
export PORT="$(free_port)"
if bash "$script_dir/run_single_job_llm_matsim.slurm" > "$root/failure.log" 2>&1; then
    echo 'Startup failure was not propagated' >&2; exit 1
fi
[[ ! -e "$root/project/output/hpc/single-job-offline-failure/morning/status.json" ]]
echo 'PASS: readiness, local role isolation, five-day settings, status verification, cleanup, simulation failure, model crash and startup failure'
