#!/usr/bin/env bash
# Cross-backend smoke for front-matter route declarations.
#
# Boots examples/rest-api-fm.ssc on http://localhost:8767 through each of
# the three backends (interpreter, JVM-compiled, JS via Node) and verifies
# that all four front-matter-declared routes round-trip identically.
# Plain bash (not scala-cli) so nested scala-cli inside bin/sscc and
# bin/jssc doesn't deadlock on the parent harness.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$ROOT/examples/rest-api-fm.ssc"
BIN="$ROOT/bin"
PORT=8767

trap 'pkill -9 -f "examples/rest-api-fm\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

kill_port() {
    lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
    sleep 1
}

wait_for_server() {
    local deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/api/todos" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

run_backend() {
    local label="$1"
    local launcher="$2"
    kill_port
    "$launcher" "$EXAMPLE" > "/tmp/fm-routes-smoke-$label.log" 2>&1 &
    local pid=$!
    if ! wait_for_server; then
        kill -9 $pid 2>/dev/null
        echo "[FAIL] $label: server did not start within 60s"
        echo "       log: /tmp/fm-routes-smoke-$label.log"
        return 1
    fi

    local fail=0
    local got
    got=$(curl -sS "http://localhost:$PORT/api/todos")
    [ "$got" = '["Buy milk","Walk the dog","Write spec"]' ] \
        || { echo "  [FAIL] GET /api/todos: $got"; fail=1; }
    got=$(curl -sS -X POST -d "Buy bread" "http://localhost:$PORT/api/todos")
    [ "$got" = "Buy bread" ] \
        || { echo "  [FAIL] POST /api/todos: $got"; fail=1; }
    got=$(curl -sS "http://localhost:$PORT/api/todos")
    [ "$got" = '["Buy milk","Walk the dog","Write spec","Buy bread"]' ] \
        || { echo "  [FAIL] GET after POST: $got"; fail=1; }
    got=$(curl -sS -X DELETE "http://localhost:$PORT/api/todos/0" -o /dev/null -w "%{http_code}")
    [ "$got" = "204" ] \
        || { echo "  [FAIL] DELETE: status=$got"; fail=1; }
    local title_count
    title_count=$(curl -sS "http://localhost:$PORT/" | grep -c '<title>Todos</title>' || true)
    [ "$title_count" = "1" ] \
        || { echo "  [FAIL] GET / missing <title>Todos</title>"; fail=1; }

    kill $pid 2>/dev/null
    wait $pid 2>/dev/null
    if [ $fail -eq 0 ]; then
        echo "[PASS] $label"
        return 0
    fi
    return 1
}

echo "============================================================"
echo "  Front-matter routes smoke — three backends · port $PORT"
echo "============================================================"
echo

fail=0
run_backend INT "$BIN/ssc"   || fail=1
run_backend JVM "$BIN/sscc"  || fail=1
run_backend JS  "$BIN/jssc"  || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "All three backends register front-matter routes correctly."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/fm-routes-smoke-*.log"
    exit 1
fi
