#!/usr/bin/env bash
# Cross-backend smoke for the built-in /_health and /_ready endpoints
# (Tier 5 #21).  Boots examples/health-defaults.ssc on port 8769 through
# each of the three backends (interpreter, JVM-compiled, JS via Node)
# and verifies that:
#   1. GET /_health → 200 {"status":"ok"}
#   2. GET /_ready  → 200 {"status":"ok"}
#   3. Content-Type is application/json
# Plain bash (not scala-cli) so nested scala-cli inside bin/sscc and
# bin/jssc doesn't deadlock on the parent harness.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$ROOT/examples/health-defaults.ssc"
BIN="$ROOT/bin"
PORT=8769

trap 'pkill -9 -f "examples/health-defaults\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

kill_port() {
    lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
    sleep 1
}

wait_for_server() {
    local deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/_health" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

check_endpoint() {
    local label="$1"
    local path="$2"
    local out status ctype
    out=$(curl -sS -m 5 -w '\n%{http_code}\n%{content_type}' "http://localhost:$PORT$path") || {
        echo "  [FAIL] $label: curl failed"
        return 1
    }
    local body
    body=$(printf '%s' "$out" | head -n -2)
    status=$(printf '%s' "$out" | tail -n 2 | head -n 1)
    ctype=$(printf '%s' "$out" | tail -n 1)

    local fail=0
    [ "$status" = "200" ] \
        || { echo "  [FAIL] $label: status=$status (want 200)"; fail=1; }
    [ "$body" = '{"status":"ok"}' ] \
        || { echo "  [FAIL] $label: body=$body"; fail=1; }
    case "$ctype" in
        application/json*) ;;
        *) echo "  [FAIL] $label: content-type=$ctype (want application/json)"; fail=1;;
    esac
    return $fail
}

run_backend() {
    local label="$1"
    local launcher="$2"
    kill_port
    "$launcher" "$EXAMPLE" > "/tmp/health-smoke-$label.log" 2>&1 &
    local pid=$!
    if ! wait_for_server; then
        kill -9 $pid 2>/dev/null
        echo "[FAIL] $label: server did not start within 60s"
        echo "       log: /tmp/health-smoke-$label.log"
        return 1
    fi

    local fail=0
    check_endpoint "GET /_health" /_health || fail=1
    check_endpoint "GET /_ready"  /_ready  || fail=1

    kill $pid 2>/dev/null
    wait $pid 2>/dev/null
    if [ $fail -eq 0 ]; then
        echo "[PASS] $label"
        return 0
    fi
    return 1
}

echo "============================================================"
echo "  Health/ready defaults smoke — three backends · port $PORT"
echo "============================================================"
echo

fail=0
run_backend INT "$BIN/ssc"   || fail=1
run_backend JVM "$BIN/sscc"  || fail=1
run_backend JS  "$BIN/jssc"  || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "Built-in /_health and /_ready work on all three backends."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/health-smoke-*.log"
    exit 1
fi
