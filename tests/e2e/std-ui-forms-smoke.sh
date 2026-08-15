#!/usr/bin/env bash
# Tier 1 std/ui smoke — renders the demo through all three backends
# (INT via ssc render, JVM via sscc serve+curl, JS via jssc serve+curl)
# and verifies every component's scoped class names + the aggregated
# `<script>` appear in the rendered HTML.
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
BIN="$ROOT/bin"
DEMO="$ROOT/examples/std-ui/demo.ssc"
# 8771, NOT 8769, since 2026-08-15. This gate and `health-defaults-smoke.sh` both bound 8769 — a
# pair frozen in `no-leaked-servers.sh` rather than fixed — and a frozen collision is still a live
# collision. Observed in an ordinary `scripts/smoke-ci` run: this gate failed with
#     [FAIL] JVM: :8769 is answering, but NOT from the process this gate started (pid 43041)
# while passing 3/3 standalone at the SAME load, so the holder was a sibling gate inside the same
# run. The identity check below did its job; what it caught was a port two gates were told to share.
PORT=8771

# A MISSING HELPER MUST NOT READ AS A FOUND DEFECT. Sourced without this guard, `.` fails quietly
# and every later `assert_own_listener` is "command not found" — non-zero — which the call sites
# below treat as "the port is foreign". Measured here: one gate where the source line was forgotten
# reported `:8769 is held by a process this gate did not start` on two lanes that were healthy.
# shellcheck source=lib/own-server.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/own-server.sh" || {
  echo "$(basename "${BASH_SOURCE[0]}"): cannot source lib/own-server.sh — refusing to run rather" >&2
  echo "  than report a healthy server as foreign." >&2; exit 2; }
INT_ERR="${TMPDIR:-/tmp}/std-ui-forms-int.err"

trap 'pkill -9 -f "examples/std-ui/demo\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

assert_markers() {
    local label="$1"
    local body="$2"
    local fail=0
    declare -A want=(
        ["title>std/ui"]=1
        ["root__Input"]=3
        ["root__Textarea"]=2
        ["root__Select"]=2
        ["root__Checkbox"]=3
        ["group__Radio"]=2
        ["root__FormGroup"]=4
        ["querySelectorAll"]=2
    )
    for marker in "${!want[@]}"; do
        local exp="${want[$marker]}"
        local got
        got=$(echo "$body" | grep -c "$marker" || true)
        if [ "$got" != "$exp" ]; then
            echo "  [FAIL] $label: $marker ($got, want $exp)"
            fail=1
        fi
    done
    if [ $fail -eq 0 ]; then
        echo "  [PASS] $label"
    fi
    return $fail
}

kill_port() {
    lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
    sleep 1
}

wait_for_server() {
    local deadline=$(( $(date +%s) + 90 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        curl -sS -o /dev/null -m 2 "http://localhost:$PORT/" 2>/dev/null && return 0
        sleep 2
    done
    return 1
}

echo "============================================================"
echo "  std/ui Tier 1 (forms) smoke"
echo "============================================================"
echo

fail=0

# INT via the headless `ssc render` path (avoids the interpreter's
# WS-aware NIO-proxy startup delay).
# stderr to a FILE, not /dev/null. Discarding it turned a named interpreter error into "0 markers
# found", so every row failed for a reason the gate refused to print — measured 2026-08-04, the real
# answer is `InterpretError: [line 36, col 196] Undefined: impl` in examples/std-ui/demo.ssc and
# stdout is EMPTY. "Rendered the wrong page" and "died before rendering" must not look alike.
body_int=$("$BIN/ssc-tools" render "$DEMO" 2>"$INT_ERR")
if ! assert_markers "INT" "$body_int"; then
  fail=1
  # The captured error is the whole point of capturing it: without this the rows above say only
  # "(0, want 2)" and a reader has no idea whether the renderer produced a wrong page or none.
  [ -s "$INT_ERR" ] && sed 's/^/       INT stderr: /' "$INT_ERR" | head -3
  [ -z "$body_int" ] && echo "       INT stdout was EMPTY — nothing was rendered at all"
fi

# JVM / JS via serve+curl when their launchers are available.  Each
# launcher shells out to `scala-cli`, which currently needs JDK 21 to
# compile the bench/WsStress.scala that landed alongside the WS work;
# skip these arms cleanly when the compile fails so this smoke isn't
# blocked by an unrelated environmental issue.
run_serve_backend() {
    local label="$1"
    local launcher="$2"
    local sleep_min="$3"
    kill_port
    $launcher "$DEMO" > "/tmp/std-ui-$label.log" 2>&1 &
    local pid=$!
    sleep $sleep_min
    if ! kill -0 $pid 2>/dev/null; then
        echo "  [skip] $label: launcher exited (likely scala-cli compile gate — see /tmp/std-ui-$label.log)"
        return 0
    fi
    if ! wait_for_server; then
        kill -9 $pid 2>/dev/null
        echo "  [skip] $label: server did not start within 90s"
        return 0
    fi
    # `wait_for_server` polls the PORT, and this gate no longer shares one — it moved off 8769 on
    # 2026-08-15 (see the PORT declaration). The check STAYS: a foreign answer can still come from a
    # leak, and this gate is the one that proved a shared port bites in practice.
    # A foreign server is a FAILURE here and not a `[skip]`: the two skips above are
    # environmental — a launcher that could not compile — while this one means the port is not ours
    # and the marker assertions below would be reading somebody else's page.
    # (a-gate-that-starts-a-server-cannot-prove-it-is-talking-to-its-own.)
    if ! assert_own_listener "$PORT" "$pid" "$label"; then
        kill -9 $pid 2>/dev/null
        echo "  [FAIL] $label: :$PORT is held by a process this gate did not start"
        return 1
    fi
    local body
    body=$(curl -sS "http://localhost:$PORT/")
    kill $pid 2>/dev/null; wait $pid 2>/dev/null
    assert_markers "$label" "$body" || return 1
}

run_serve_backend "JVM" "$BIN/ssc-tools run-jvm" 5 || fail=1
run_serve_backend "JS"  "$BIN/ssc-tools run-js" 3 || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "Tier 1 forms render identically (INT + any backend that compiled)."
    exit 0
fi
exit 1
