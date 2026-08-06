#!/usr/bin/env bash
# Cross-backend smoke for front-matter route declarations.
#
# Boots examples/rest-api-fm.ssc on http://localhost:8767 through each of
# the four lanes (native/default, interpreter, JVM-compiled, JS via Node) and verifies
# that all four front-matter-declared routes round-trip identically.
# Plain bash (not scala-cli) so nested scala-cli inside bin/sscc and
# bin/jssc doesn't deadlock on the parent harness.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXAMPLE="$ROOT/examples/rest-api-fm.ssc"
BIN="$ROOT/bin"
PORT=8767

trap 'pkill -9 -f "examples/rest-api-fm\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

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
    # A CEILING, not a target — the loop exits when the port answers. The native lane compiles the
    # program on each run and on a loaded host that passes 60 s, which this gate then reported as
    # `server did not start` for a process still in `lowerNative`. Same change as
    # health-defaults-smoke.
    local deadline=$(( $(date +%s) + 180 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/api/todos" 2>/dev/null; then
            return 0
        fi
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            return 2   # the server process is gone; nothing will ever answer
        fi
        sleep 1
    done
    return 1
}

run_backend() {
    local label="$1"
    local launcher="$2"
    kill_port
    # shellcheck disable=SC2086 -- $launcher is a COMMAND with arguments now, not one path
    $launcher "$EXAMPLE" > "/tmp/fm-routes-smoke-$label.log" 2>&1 &
    local pid=$!
    wait_for_server "$pid"; wrc=$?
    if [ "$wrc" -ne 0 ]; then
        kill -9 $pid 2>/dev/null
        if [ "$wrc" -eq 2 ]; then
            echo "[FAIL] $label: the server process EXITED before it listened"
        else
            echo "[FAIL] $label: server did not start before the deadline"
        fi
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
echo "  Front-matter routes smoke — four lanes · port $PORT"
echo "============================================================"
echo

fail=0
# THE RUNNERS WERE WRONG, and two of the three labels with them.
#
#   `$BIN/sscc` is `ssc-tools compile-jvm`: it COMPILES and exits, printing
#   `JVM artifact written to …`. It never served anything, so "the server process EXITED before it
#   listened" was the literal truth about a compiler. The JVM lane works — via `run-jvm`.
#
#   `$BIN/ssc` is `StandardMain`, the DEFAULT (native) lane, not the interpreter. Labelling it INT
#   sent its failures to the wrong lane's owner.
#
# NATIVE was a declared KNOWN GAP here until 2026-08-06 and now counts with the rest
# (v2/BUGS.md `native-lane-ignores-declarative-route-registration`, both halves closed). The
# declaration was DELETED because this gate failed the moment NATIVE started passing and said so in
# its own output — which is the whole point of declaring rather than skipping.
run_backend NATIVE "$BIN/ssc"                    || fail=1
run_backend INT    "$BIN/ssc-tools run --v1"     || fail=1
run_backend JVM    "$BIN/ssc-tools run-jvm"      || fail=1
run_backend JS     "$BIN/ssc-tools run-js"       || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "All four lanes register front-matter routes correctly."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/fm-routes-smoke-*.log"
    exit 1
fi
