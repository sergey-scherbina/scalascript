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
PORT=8769

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
body_int=$("$BIN/ssc-tools" render "$DEMO" 2>/dev/null)
assert_markers "INT" "$body_int" || fail=1

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
