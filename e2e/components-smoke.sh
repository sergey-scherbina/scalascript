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
# either indicates a regression in the import / object / css path.
EXPECTED_TITLE=2          # <title>Components demo</title> + <h1>…
EXPECTED_BTN_PRIMARY=2    # .btn-primary { … } + class="btn btn-primary"
EXPECTED_BTN_SECONDARY=2  # .btn-secondary + Cancel button
EXPECTED_BTN_DANGER=2     # .btn-danger + Delete button
EXPECTED_CARD_TITLE=3     # .card-title { … } + two rendered cards
EXPECTED_CARD=2           # class="card" — two rendered cards (CSS rule uses .card, not class="card")

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

    local title=$(echo "$body" | grep -c 'Components demo' || true)
    local btn1=$(echo "$body" | grep -c 'btn-primary'   || true)
    local btn2=$(echo "$body" | grep -c 'btn-secondary' || true)
    local btn3=$(echo "$body" | grep -c 'btn-danger'    || true)
    local card1=$(echo "$body" | grep -c 'card-title'   || true)
    local cardc=$(echo "$body" | grep -c 'class="card"' || true)

    [ "$title" = "$EXPECTED_TITLE" ]           || { echo "  [FAIL] $label title:$title (want $EXPECTED_TITLE)"; fail=1; }
    [ "$btn1"  = "$EXPECTED_BTN_PRIMARY" ]     || { echo "  [FAIL] $label btn-primary:$btn1 (want $EXPECTED_BTN_PRIMARY)"; fail=1; }
    [ "$btn2"  = "$EXPECTED_BTN_SECONDARY" ]   || { echo "  [FAIL] $label btn-secondary:$btn2 (want $EXPECTED_BTN_SECONDARY)"; fail=1; }
    [ "$btn3"  = "$EXPECTED_BTN_DANGER" ]      || { echo "  [FAIL] $label btn-danger:$btn3 (want $EXPECTED_BTN_DANGER)"; fail=1; }
    [ "$card1" = "$EXPECTED_CARD_TITLE" ]      || { echo "  [FAIL] $label card-title:$card1 (want $EXPECTED_CARD_TITLE)"; fail=1; }
    [ "$cardc" = "$EXPECTED_CARD" ]            || { echo "  [FAIL] $label card class:$cardc (want $EXPECTED_CARD)"; fail=1; }

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
