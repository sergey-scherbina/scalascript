#!/usr/bin/env bash
# Cross-backend smoke for std/middleware.ssc (Tier 5 #18).  Boots
# examples/middleware-demo.ssc on port 8770 on each backend and
# verifies:
#   1. GET /echo returns 200
#   2. X-Request-Id header is echoed (or minted when absent)
#   3. X-Response-Time-Ms header is present
#   4. JSON body matches the echo template
#
# RUNNERS CORRECTED 2026-08-04 (tests/BUGS.md orphaned-e2e-gates-52). Two were wrong, and neither
# wrong is visible from the failure it produced:
#
#   `$BIN/sscc` is `ssc-tools compile-jvm` — it COMPILES and exits, printing `JVM artifact written`.
#   As a "JVM backend" it never ran anything: this gate's JVM lane was measuring a compiler, and
#   reported it as "the server process EXITED before it listened".
#
#   `$BIN/ssc` is `StandardMain`, the NATIVE/default lane, not the interpreter. The label said INT
#   for a launcher that stopped being the interpreter when the lane map moved, so this gate's
#   failures were filed against the wrong lane's owner. The runner now matches the label; NATIVE is
#   not covered here and its own gap is `v2/BUGS.md native-lane-ignores-declarative-route-registration`.
#
# The launcher is now a COMMAND WITH ARGUMENTS, passed as one string and invoked UNQUOTED. Passing
# the extra words as separate arguments instead is a silent trap: helpers that take positional args
# shift `expected` into `$4`, and helpers that use `"$launcher"` drop the words entirely — which
# made three differently-labelled lanes all run the same default lane and turned two gates green
# for no reason. Both happened here before this note was written.
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXAMPLE="$ROOT/examples/middleware-demo.ssc"
BIN="$ROOT/bin"
PORT=8770

trap 'pkill -9 -f "examples/middleware-demo\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

kill_port() {
    lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
    sleep 1
}

wait_for_server() {
    # $1 = pid of the launched server, when the caller has one. A dead process is the answer NOW:
    # measured 2026-08-02, four of the five delegating launchers exited instantly ("compile-jvm
    # requires the optional tools tier") and this loop still burned its whole deadline waiting for
    # a listener that could never appear. Three backends x 60-90s put the gate past the census's
    # 180s timeout, so it was filed as HANGING and left unwired -- the one diagnosis that stops
    # anyone reading the actual error. Polling a corpse is never worth the wall clock.
    local pid="${1:-}"
    local deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/echo" 2>/dev/null; then
            return 0
        fi
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            return 2   # the server process is gone; nothing will ever answer
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
    $launcher "$EXAMPLE" > "/tmp/mw-smoke-$label.log" 2>&1 &
    local pid=$!
    wait_for_server "$pid"; wrc=$?
    if [ "$wrc" -ne 0 ]; then
        kill -9 $pid 2>/dev/null
        if [ "$wrc" -eq 2 ]; then
            echo "[FAIL] $label: the server process EXITED before it listened"
        else
            echo "[FAIL] $label: server did not start before the deadline"
        fi
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
run_backend INT "$BIN/ssc-tools run --v1"   || fail=1
run_backend JVM "$BIN/ssc-tools run-jvm"  || fail=1
run_backend JS  "$BIN/ssc-tools run-js"  || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "std/middleware works on all three backends."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/mw-smoke-*.log"
    exit 1
fi
