#!/usr/bin/env bash
# Cross-backend smoke for the components-demo page.
#
# Boots examples/components-demo.ssc through each of the three backends
# and asserts that the rendered HTML contains the expected markers from
# every imported component (Button + Card).  Verifies that the MVP
# component convention (one .ssc per component with `object Name { css,
# render }`, imported via `[Name](./path.ssc)`, composed at the page
# level) yields byte-identical output across INT / JVM / JS.
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
EXAMPLE="$ROOT/examples/components-demo.ssc"
BIN="$ROOT/bin"
PORT=8768

trap 'pkill -9 -f "examples/components-demo\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

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
    local deadline=$(( $(date +%s) + 90 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 2 "http://localhost:$PORT/" 2>/dev/null; then
            return 0
        fi
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            return 2   # the server process is gone; nothing will ever answer
        fi
        sleep 2
    done
    return 1
}

# Expected occurrence counts for known markers.  These cover both the
# CSS-rule occurrence and the rendered element occurrence — drift in
# either indicates a regression in the import / object / css / scope path.
EXPECTED_DOCTYPE=1           # single <!doctype html> — Page wraps; route handler must not emit its own
EXPECTED_TITLE=2             # <title>Components demo</title> + <h1>…
EXPECTED_BTN_PRIMARY=2       # .btn-primary { … } + class="btn btn-primary"
EXPECTED_BTN_SECONDARY=2     # .btn-secondary + Cancel button
EXPECTED_BTN_DANGER=2        # .btn-danger + Delete button
# Card uses scope("Card"), so its classes are suffixed with __Card.  Each
# of root / title / body appears in the CSS rule + twice in HTML (two cards).
EXPECTED_CARD_ROOT=3
EXPECTED_CARD_TITLE=3
EXPECTED_CARD_BODY=3
# Alert uses scope("Alert"); .root appears in 3 CSS rules (.root, .root.warn,
# .root.error) + 3 rendered alerts = 6.  .title / .body each: 1 CSS rule +
# 3 alerts = 4.
EXPECTED_ALERT_ROOT=6
EXPECTED_ALERT_TITLE=4
EXPECTED_ALERT_BODY=4
# Counter has CSS for .root/.label/.button, renders 2 instances, and the
# `val js` references `.root__Counter` in `document.querySelectorAll(...)` —
# total CSS-rule (1) + instances (2) + JS reference (1) = 4.
# `collectJs(Counter)` produces exactly one `<script>` block.
EXPECTED_COUNTER_ROOT=4
EXPECTED_QUERYSEL=1

run_backend() {
    local label="$1"
    local launcher="$2"
    local body=""
    local fail=0
    # The INT path uses headless `ssc render` instead of `serve` + curl.
    # The interpreter's serve runtime currently has a request-handling
    # regression unrelated to components; `ssc render` exercises the same
    # interpreter through the static-render entry point and validates that
    # INT produces the right page.
    if [ "$label" = "INT" ]; then
        body=$($launcher render "$EXAMPLE" 2>/tmp/components-smoke-INT.log)
        if [ -z "$body" ]; then
            echo "[FAIL] $label: 'ssc render' produced no output"
            cat /tmp/components-smoke-INT.log
            return 1
        fi
    else
        kill_port
        $launcher "$EXAMPLE" > "/tmp/components-smoke-$label.log" 2>&1 &
        local pid=$!
        wait_for_server "$pid"; wrc=$?
    if [ "$wrc" -ne 0 ]; then
            kill -9 $pid 2>/dev/null
            if [ "$wrc" -eq 2 ]; then
                echo "[FAIL] $label: the server process EXITED before it listened"
            else
                echo "[FAIL] $label: server did not start before the deadline"
            fi
            echo "       log: /tmp/components-smoke-$label.log"
            return 1
        fi
        body=$(curl -sS "http://localhost:$PORT/")
    fi

    local doctype=$(echo "$body" | grep -ci '<!doctype html>' || true)
    local title=$(echo "$body"  | grep -c 'Components demo' || true)
    local btn1=$(echo "$body"   | grep -c 'btn-primary'   || true)
    local btn2=$(echo "$body"   | grep -c 'btn-secondary' || true)
    local btn3=$(echo "$body"   | grep -c 'btn-danger'    || true)
    local croot=$(echo "$body"  | grep -c 'root__Card'    || true)
    local ctitle=$(echo "$body" | grep -c 'title__Card'   || true)
    local cbody=$(echo "$body"  | grep -c 'body__Card'    || true)
    local aroot=$(echo "$body"  | grep -c 'root__Alert'   || true)
    local atitle=$(echo "$body" | grep -c 'title__Alert'  || true)
    local abody=$(echo "$body"  | grep -c 'body__Alert'   || true)
    local croot_counter=$(echo "$body" | grep -c 'root__Counter'           || true)
    local qsel=$(echo "$body"           | grep -c 'document.querySelectorAll' || true)

    [ "$doctype" = "$EXPECTED_DOCTYPE" ]        || { echo "  [FAIL] $label doctype:$doctype (want $EXPECTED_DOCTYPE)"; fail=1; }
    [ "$title"  = "$EXPECTED_TITLE" ]           || { echo "  [FAIL] $label title:$title (want $EXPECTED_TITLE)"; fail=1; }
    [ "$btn1"   = "$EXPECTED_BTN_PRIMARY" ]     || { echo "  [FAIL] $label btn-primary:$btn1 (want $EXPECTED_BTN_PRIMARY)"; fail=1; }
    [ "$btn2"   = "$EXPECTED_BTN_SECONDARY" ]   || { echo "  [FAIL] $label btn-secondary:$btn2 (want $EXPECTED_BTN_SECONDARY)"; fail=1; }
    [ "$btn3"   = "$EXPECTED_BTN_DANGER" ]      || { echo "  [FAIL] $label btn-danger:$btn3 (want $EXPECTED_BTN_DANGER)"; fail=1; }
    [ "$croot"  = "$EXPECTED_CARD_ROOT" ]       || { echo "  [FAIL] $label root__Card:$croot (want $EXPECTED_CARD_ROOT)"; fail=1; }
    [ "$ctitle" = "$EXPECTED_CARD_TITLE" ]      || { echo "  [FAIL] $label title__Card:$ctitle (want $EXPECTED_CARD_TITLE)"; fail=1; }
    [ "$cbody"  = "$EXPECTED_CARD_BODY" ]       || { echo "  [FAIL] $label body__Card:$cbody (want $EXPECTED_CARD_BODY)"; fail=1; }
    [ "$aroot"  = "$EXPECTED_ALERT_ROOT" ]      || { echo "  [FAIL] $label root__Alert:$aroot (want $EXPECTED_ALERT_ROOT)"; fail=1; }
    [ "$atitle" = "$EXPECTED_ALERT_TITLE" ]     || { echo "  [FAIL] $label title__Alert:$atitle (want $EXPECTED_ALERT_TITLE)"; fail=1; }
    [ "$abody"  = "$EXPECTED_ALERT_BODY" ]      || { echo "  [FAIL] $label body__Alert:$abody (want $EXPECTED_ALERT_BODY)"; fail=1; }
    [ "$croot_counter" = "$EXPECTED_COUNTER_ROOT" ] || { echo "  [FAIL] $label root__Counter:$croot_counter (want $EXPECTED_COUNTER_ROOT)"; fail=1; }
    [ "$qsel"   = "$EXPECTED_QUERYSEL" ]        || { echo "  [FAIL] $label querySelectorAll:$qsel (want $EXPECTED_QUERYSEL)"; fail=1; }

    if [ "$label" != "INT" ]; then
        kill $pid 2>/dev/null
        wait $pid 2>/dev/null
    fi
    if [ $fail -eq 0 ]; then
        echo "[PASS] $label"
        return 0
    fi
    return 1
}

echo "============================================================"
echo "  Components smoke — three backends · port $PORT"
echo "============================================================"
echo

fail=0
# INT goes through ssc-tools: this lane calls `render`, which lives in the optional tier and
# which the standard ssc declines with an EMPTY stdout — indistinguishable here from a page
# that rendered to nothing.
run_backend INT "$BIN/ssc-tools"   || fail=1
run_backend JVM "$BIN/ssc-tools run-jvm"  || fail=1
run_backend JS  "$BIN/ssc-tools run-js"  || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "All three backends render the components page identically."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/components-smoke-*.log"
    exit 1
fi
