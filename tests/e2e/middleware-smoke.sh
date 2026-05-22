#!/usr/bin/env bash
# Cross-backend smoke for std/middleware.ssc (Tier 5 #18).  Boots
# examples/middleware-demo.ssc on port 8770 on each backend and
# verifies:
#   1. GET /echo returns 200
#   2. X-Request-Id header is echoed (or minted when absent)
#   3. X-Response-Time-Ms header is present
#   4. JSON body matches the echo template
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$ROOT/examples/middleware-demo.ssc"
BIN="$ROOT/bin"
PORT=8770

trap 'pkill -9 -f "examples/middleware-demo\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

kill_port() {
    lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
    sleep 1
}

wait_for_server() {
    local deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/echo" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

check_endpoint() {
    local label="$1"

    # 1. No inbound X-Request-Id → server should mint one
    # Response.json(string) emits the string raw (no JSON quoting) — the
    # existing pass-through semantics so hand-built JSON strings keep
    # working.  See `_toJson` in JsGen / interpreter.
    rm -f /tmp/mw-headers.txt
    local body
    body=$(curl -sS -m 5 -D /tmp/mw-headers.txt "http://localhost:$PORT/echo")
    [ "$body" = 'echo /echo' ] \
        || { echo "  [FAIL] $label: body=$body (want 'echo /echo')"; return 1; }
    grep -qi '^x-request-id:' /tmp/mw-headers.txt \
        || { echo "  [FAIL] $label: minted X-Request-Id missing"; cat /tmp/mw-headers.txt; return 1; }
    grep -qi '^x-response-time-ms:' /tmp/mw-headers.txt \
        || { echo "  [FAIL] $label: X-Response-Time-Ms missing"; return 1; }
    local minted
    minted=$(grep -i '^x-request-id:' /tmp/mw-headers.txt | head -1 | tr -d '\r\n')
    case "$minted" in
        *req-*) ;;
        *) echo "  [FAIL] $label: minted id doesn't match req-* pattern: $minted"; return 1;;
    esac

    # 2. Inbound X-Request-Id → server should echo it back unchanged
    rm -f /tmp/mw-headers.txt
    curl -sS -m 5 -D /tmp/mw-headers.txt -H "X-Request-Id: trace-abc-123" "http://localhost:$PORT/echo" > /dev/null
    local echoed
    echoed=$(grep -i '^x-request-id:' /tmp/mw-headers.txt | head -1 | sed 's/^[Xx]-[Rr]equest-[Ii]d: *//' | tr -d '\r\n')
    [ "$echoed" = "trace-abc-123" ] \
        || { echo "  [FAIL] $label: inbound id not echoed: got '$echoed'"; return 1; }

    return 0
}

run_backend() {
    local label="$1"
    local launcher="$2"
    kill_port
    "$launcher" "$EXAMPLE" > "/tmp/mw-smoke-$label.log" 2>&1 &
    local pid=$!
    if ! wait_for_server; then
        kill -9 $pid 2>/dev/null
        echo "[FAIL] $label: server did not start within 60s"
        echo "       log: /tmp/mw-smoke-$label.log"
        return 1
    fi

    local fail=0
    check_endpoint "$label" || fail=1

    kill $pid 2>/dev/null
    wait $pid 2>/dev/null
    if [ $fail -eq 0 ]; then
        echo "[PASS] $label"
        return 0
    fi
    return 1
}

echo "============================================================"
echo "  Middleware smoke — three backends · port $PORT"
echo "============================================================"
echo

fail=0
run_backend INT "$BIN/ssc"   || fail=1
run_backend JVM "$BIN/sscc"  || fail=1
run_backend JS  "$BIN/jssc"  || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "std/middleware works on all three backends."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/mw-smoke-*.log"
    exit 1
fi
