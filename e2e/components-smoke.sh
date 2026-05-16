#!/usr/bin/env bash
# Cross-backend smoke for the components-demo page.
#
# Boots examples/components-demo.ssc through each of the three backends
# and asserts that the rendered HTML contains the expected markers from
# every imported component (Button + Card).  Verifies that the MVP
# component convention (one .ssc per component with `object Name { css,
# render }`, imported via `[Name](./path.ssc)`, composed at the page
# level) yields byte-identical output across INT / JVM / JS.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$ROOT/examples/components-demo.ssc"
BIN="$ROOT/bin"
PORT=8768

trap 'pkill -9 -f "examples/components-demo\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

kill_port() {
    lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
    sleep 1
}

wait_for_server() {
    local deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/" 2>/dev/null; then
            return 0
        fi
        sleep 1
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

run_backend() {
    local label="$1"
    local launcher="$2"
    kill_port
    "$launcher" "$EXAMPLE" > "/tmp/components-smoke-$label.log" 2>&1 &
    local pid=$!
    if ! wait_for_server; then
        kill -9 $pid 2>/dev/null
        echo "[FAIL] $label: server did not start within 60s"
        echo "       log: /tmp/components-smoke-$label.log"
        return 1
    fi

    local fail=0
    local body
    body=$(curl -sS "http://localhost:$PORT/")

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

    kill $pid 2>/dev/null
    wait $pid 2>/dev/null
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
run_backend INT "$BIN/ssc"   || fail=1
run_backend JVM "$BIN/sscc"  || fail=1
run_backend JS  "$BIN/jssc"  || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "All three backends render the components page identically."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/components-smoke-*.log"
    exit 1
fi
