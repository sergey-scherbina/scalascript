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

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXAMPLE="$ROOT/examples/health-defaults.ssc"
BIN="$ROOT/bin"
PORT=8769

# A MISSING HELPER MUST NOT READ AS A FOUND DEFECT. Sourced without this guard, `.` fails quietly
# and every later `assert_own_listener` is "command not found" — non-zero — which the call sites
# below treat as "the port is foreign". Measured here: one gate where the source line was forgotten
# reported `:8769 is held by a process this gate did not start` on two lanes that were healthy.
# shellcheck source=lib/own-server.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/own-server.sh" || {
  echo "$(basename "${BASH_SOURCE[0]}"): cannot source lib/own-server.sh — refusing to run rather" >&2
  echo "  than report a healthy server as foreign." >&2; exit 2; }

trap 'pkill -9 -f "examples/health-defaults\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

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
    # 180, not 60, and it is a CEILING rather than a target: the fast path is unchanged because
    # the loop exits the moment the port answers. The native lane COMPILES the program on each run,
    # and on a host running several builds that took past 60 s — measured here as
    # `server did not start before the deadline` for a server that was still in `lowerNative`,
    # not stuck. A deadline tuned on an idle machine reports contention as a product failure.
    local deadline=$(( $(date +%s) + 180 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/_health" 2>/dev/null; then
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
    local path="$2"
    local out status ctype
    out=$(curl -sS -m 5 -w '\n%{http_code}\n%{content_type}' "http://localhost:$PORT$path") || {
        echo "  [FAIL] $label: curl failed"
        return 1
    }
    local body
    # `head -n -2` is a GNU extension. BSD/macOS head rejects it outright —
    # `head: illegal line count -- -2` — so `body` was ALWAYS EMPTY here, and every body assertion
    # failed on every lane regardless of what the server sent. A check whose failure is
    # indistinguishable from the defect it looks for is not a check. Portable form:
    body=$(printf '%s' "$out" | sed '$d' | sed '$d')
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
    # shellcheck disable=SC2086 -- $launcher is a COMMAND with arguments now, not one path
    $launcher "$EXAMPLE" > "/tmp/health-smoke-$label.log" 2>&1 &
    local pid=$!
    wait_for_server "$pid"; wrc=$?
    if [ "$wrc" -ne 0 ]; then
        kill -9 $pid 2>/dev/null
        if [ "$wrc" -eq 2 ]; then
            echo "[FAIL] $label: the server process EXITED before it listened"
        else
            echo "[FAIL] $label: server did not start before the deadline"
        fi
        echo "       log: /tmp/health-smoke-$label.log"
        return 1
    fi

    # Something answered — but whose? This port is shared with `std-ui-forms-smoke.sh` (one of the
    # three pairs frozen in `no-leaked-servers.sh`), so a neighbour's run, or a leak from one, would
    # satisfy the poll above. Here it is defence in depth rather than the whole verdict — the body
    # assertions below would also reject a foreign server, by its wrong answer — but "would also"
    # is not "does", and the failure it prints names the holder instead of blaming the body.
    # (a-gate-that-starts-a-server-cannot-prove-it-is-talking-to-its-own.)
    if ! assert_own_listener "$PORT" "$pid" "$label"; then
        kill -9 $pid 2>/dev/null
        echo "[FAIL] $label: :$PORT is held by a process this gate did not start"
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
echo "  Health/ready defaults smoke — four lanes · port $PORT"
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
# Measured per lane with the correct runners before this was rewritten; see the table in
# tests/BUGS.md `native-lane-ignores-declarative-route-registration`.
run_backend NATIVE "$BIN/ssc"                    || fail=1
run_backend INT    "$BIN/ssc-tools run --v1"     || fail=1
run_backend JVM    "$BIN/ssc-tools run-jvm"      || fail=1
run_backend JS     "$BIN/ssc-tools run-js"       || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "Built-in /_health and /_ready work on all four lanes."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/health-smoke-*.log"
    exit 1
fi
