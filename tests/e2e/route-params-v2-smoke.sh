#!/usr/bin/env bash
# v2-only regression: req.params(name) on a route with a :name path segment.
#
# Boots tests/e2e/fixtures/route-params-smoke.ssc through --v1 and --v2 and
# checks that a `:name` path segment binds to its real value in req.params,
# for both a MID-position segment (/mid/:x/tail) and a TRAILING one
# (/end/:x). v1 must bind correctly in both cases (this is the pinned,
# correct baseline); --v2 is expected to currently FAIL (returns "Stub"
# instead of the real segment) — this script documents/reproduces the gap,
# it does not (yet) gate CI on the v2 lane.
#
# Found 2026-07-09 running busi's full JDG money-loop simulator cycle
# (src/v2/sim/ksef_sim.ssc, tax_sim.ssc): any route whose handler actually
# reads req.params(name) silently gets a wrong value on v2 — no crash, no
# warning, just "Stub" flowing into business logic (a 404-shaped failure
# downstream, e.g. "no such invoice"/"no such object", not an obvious dispatch
# error). See BUGS.md: v2-route-params-stub.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURE="$ROOT/e2e/fixtures/route-params-smoke.ssc"
BIN="$ROOT/../bin/ssc"
PORT=8797

trap 'lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

kill_port() { lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null; sleep 1; }

run_lane() {
    local lane="$1"   # --v1 or --v2
    kill_port
    "$BIN" "$lane" "$FIXTURE" > "/tmp/route-params-smoke-$lane.log" 2>&1 &
    local pid=$!
    local deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        curl -sS -o /dev/null -m 1 "http://localhost:$PORT/end/probe" 2>/dev/null && break
        sleep 0.5
    done
    local mid; mid=$(curl -s -m 5 "http://localhost:$PORT/mid/hello/tail")
    local end; end=$(curl -s -m 5 "http://localhost:$PORT/end/hello")
    kill "$pid" 2>/dev/null
    kill_port
    echo "$lane: $mid | $end"
}

V1=$(run_lane --v1)
V2=$(run_lane --v2)

echo "$V1"
echo "$V2"

case "$V1" in
  *"mid=hello"*"end=hello"*) : ;;
  *) echo "route-params-v2-smoke FAIL: --v1 baseline broke (expected mid=hello, end=hello)"; exit 1 ;;
esac

case "$V2" in
  *"mid=hello"*"end=hello"*)
    echo "route-params-v2-smoke PASS: --v2 binds :name segments (mid + trailing)" ;;
  *)
    echo "route-params-v2-smoke FAIL: --v2 does not bind :name segments (got: $V2)"; exit 1 ;;
esac
