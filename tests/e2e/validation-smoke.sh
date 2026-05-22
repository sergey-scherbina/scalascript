#!/usr/bin/env bash
# Cross-backend smoke for Tier 5 #20 (typed request validation).
# Boots examples/validation-demo.ssc on each backend and verifies:
#   1. POST /users with all required fields → 200
#   2. POST /users missing `email`           → 400 with "missing field: email"
#   3. POST /users with non-integer age      → 400 with "invalid integer ..."
#   4. GET  /echo with query params          → 200 (validates query path too)
#   5. GET  /echo with missing query param   → 400
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$ROOT/examples/validation-demo.ssc"
BIN="$ROOT/bin"
PORT=8772

trap 'pkill -9 -f "examples/validation-demo\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

kill_port() {
    lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
    sleep 1
}

wait_for_server() {
    local deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/echo?name=x&n=1" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# Run a curl call, return body + "<status>" appended on a new line
fetch_status() {
    curl -sS -m 5 -o /tmp/val-body.txt -w "%{http_code}" "$@" 2>/dev/null
    local sc=$?
    if [ $sc -ne 0 ]; then echo "CURL_ERR"; return 1; fi
}

check() {
    local label="$1"
    local fail=0

    # 1. Good POST
    local status
    status=$(fetch_status -X POST -d "email=a@b.c&age=30&role=admin&active=true" "http://localhost:$PORT/users")
    if [ "$status" != "200" ]; then
        echo "  [FAIL] $label good POST: status=$status body=$(cat /tmp/val-body.txt)"
        fail=1
    elif ! grep -q "created: a@b.c age=30" /tmp/val-body.txt; then
        echo "  [FAIL] $label good POST: body=$(cat /tmp/val-body.txt)"
        fail=1
    fi

    # 2. Missing email
    status=$(fetch_status -X POST -d "age=30" "http://localhost:$PORT/users")
    if [ "$status" != "400" ]; then
        echo "  [FAIL] $label missing email: status=$status (want 400)"
        fail=1
    elif ! grep -q "missing field: email" /tmp/val-body.txt; then
        echo "  [FAIL] $label missing email: body=$(cat /tmp/val-body.txt)"
        fail=1
    fi

    # 3. Non-integer age
    status=$(fetch_status -X POST -d "email=a@b.c&age=NaN" "http://localhost:$PORT/users")
    if [ "$status" != "400" ]; then
        echo "  [FAIL] $label bad age: status=$status (want 400)"
        fail=1
    elif ! grep -q "invalid integer" /tmp/val-body.txt; then
        echo "  [FAIL] $label bad age: body=$(cat /tmp/val-body.txt)"
        fail=1
    fi

    # 4. GET /echo with query params
    status=$(fetch_status "http://localhost:$PORT/echo?name=alice&n=5")
    if [ "$status" != "200" ]; then
        echo "  [FAIL] $label good GET: status=$status"
        fail=1
    elif ! grep -q "hi alice n=5" /tmp/val-body.txt; then
        echo "  [FAIL] $label good GET: body=$(cat /tmp/val-body.txt)"
        fail=1
    fi

    # 5. GET /echo missing query param
    status=$(fetch_status "http://localhost:$PORT/echo?name=alice")
    if [ "$status" != "400" ]; then
        echo "  [FAIL] $label missing query: status=$status (want 400)"
        fail=1
    elif ! grep -q "missing field: n" /tmp/val-body.txt; then
        echo "  [FAIL] $label missing query: body=$(cat /tmp/val-body.txt)"
        fail=1
    fi

    return $fail
}

run_backend() {
    local label="$1"
    local launcher="$2"
    kill_port
    "$launcher" "$EXAMPLE" > "/tmp/val-smoke-$label.log" 2>&1 &
    local pid=$!
    if ! wait_for_server; then
        kill -9 $pid 2>/dev/null
        echo "[FAIL] $label: server did not start within 60s"
        echo "       log: /tmp/val-smoke-$label.log"
        return 1
    fi

    local fail=0
    check "$label" || fail=1

    kill $pid 2>/dev/null
    wait $pid 2>/dev/null
    if [ $fail -eq 0 ]; then
        echo "[PASS] $label"
        return 0
    fi
    return 1
}

echo "============================================================"
echo "  Validation smoke — three backends · port $PORT"
echo "============================================================"
echo

fail=0
run_backend INT "$BIN/ssc"   || fail=1
run_backend JVM "$BIN/sscc"  || fail=1
run_backend JS  "$BIN/jssc"  || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "Request validation works on all three backends."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/val-smoke-*.log"
    exit 1
fi
