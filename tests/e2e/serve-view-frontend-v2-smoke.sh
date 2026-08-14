#!/usr/bin/env bash
# v2-only regression: `serve(view, port)` must honor the frontmatter `frontend:`
# selection (web react) instead of defaulting to impls.head (swiftui, native-only)
# and crashing. Boots examples/content-introspection.ssc (frontend: react) on
# --v1 and --v2 and asserts BOTH serve a web SPA with frontend=react.
# (v2-serve-view-frontend-default — the v2 bridge never wired the frontmatter
# frontend selection, so serve crashed "the active frontend backend 'swiftui'
# is native-only"; invisible to the corpus because serve is stubbed in batch.)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EX="$ROOT/../examples/content-introspection.ssc"
# ssc-TOOLS, not ssc. This gate drives the `--v1` lane, and the STANDARD launcher refuses it by
# design: "'--v1' requires the optional ScalaScript tools/compatibility tier; run ssc-tools
# explicitly". The lane log said exactly that on every run — the gate then reported "--v1
# baseline broke", which reads as a product defect and is not one. The path was never wrong:
# ROOT is tests/, so $ROOT/../bin is the repo's bin/. Only the launcher was.
# (orphaned-e2e-gates-52.)
BIN="$ROOT/../bin/ssc-tools"
PORT=8099

# THIS GATE IS THE ONE WHERE OWNERSHIP IS DECISIVE, not defence in depth, and it was measured rather
# than argued. Its whole verdict is `http=200` plus a `frontend=` string scraped from its OWN LOG —
# neither of which comes from the response body — so ANY foreign 200 satisfies it. Planted
# 2026-08-14 with the launcher replaced by a script that prints the frontend line and sleeps, and a
# python server holding :8099:
#
#     --v2: http=200 frontend=react swiftui-crash=0      ← the exact string this gate calls a PASS
#
# Nothing of ours had bound. That is the instance
# `a-gate-that-starts-a-server-cannot-prove-it-is-talking-to-its-own` was filed without.
# A MISSING HELPER MUST NOT READ AS A FOUND DEFECT. Sourced without this guard, `.` fails quietly
# and every later `assert_own_listener` is "command not found" — non-zero — which the call sites
# below treat as "the port is foreign". Measured here: one gate where the source line was forgotten
# reported `:8769 is held by a process this gate did not start` on two lanes that were healthy.
# shellcheck source=lib/own-server.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/own-server.sh" || {
  echo "$(basename "${BASH_SOURCE[0]}"): cannot source lib/own-server.sh — refusing to run rather" >&2
  echo "  than report a healthy server as foreign." >&2; exit 2; }

trap 'lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT
run_lane() {
    local lane="$1"
    lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null; sleep 1
    "$BIN" "$lane" "$EX" > "/tmp/serve-view-$lane.log" 2>&1 &
    local pid=$!
    local deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        curl -sS -o /dev/null -m 1 "http://localhost:$PORT/" 2>/dev/null && break
        sleep 0.5
    done
    if ! assert_own_listener "$PORT" "$pid" "$lane"; then
        kill "$pid" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
        echo "$lane: http=FOREIGN-SERVER"
        return
    fi
    local code; code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "http://localhost:$PORT/")
    local fe;   fe=$(grep -oE "frontend=[a-z]+" "/tmp/serve-view-$lane.log" | head -1)
    # `grep -c` PRINTS 0 and EXITS 1 when there are no matches, so `|| echo 0` appended a SECOND
    # zero and `$swift` became the two lines "0\n0" — which is why this gate's own FAIL line used to
    # come out split across three lines with a bare `0` under it. The count grep already prints is
    # the right answer; only the fallback was wrong.
    local swift; swift=$(grep -c "swiftui.*native-only" "/tmp/serve-view-$lane.log" 2>/dev/null); swift=${swift:-0}
    kill "$pid" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
    echo "$lane: http=$code $fe swiftui-crash=$swift"
}
V1=$(run_lane --v1); V2=$(run_lane --v2)
echo "$V1"; echo "$V2"
for r in "$V1" "$V2"; do
  case "$r" in
    *"http=200 frontend=react swiftui-crash=0"*) : ;;
    *)
      echo "serve-view-frontend FAIL: $r"
      # PRINT THE LANE'S OWN OUTPUT. `http=000` means nothing ever listened, and this gate used to
      # report only that — while the log sitting next to it said, in one line, WHY:
      # `ssc: unbound global: contentToolkitBlock`. The entry filed against this gate consequently
      # sent its reader to the v2 `serve` path, which the program never reaches.
      # (v2-lane-does-not-serve-the-content-introspection-view.)
      case "$r" in
        --v1:*) lane_log="/tmp/serve-view---v1.log" ;;
        *)      lane_log="/tmp/serve-view---v2.log" ;;
      esac
      echo "  ── last lines of $lane_log:"
      tail -5 "$lane_log" 2>/dev/null | sed 's/^/  | /'
      exit 1 ;;
  esac
done
echo "serve-view-frontend PASS: both lanes serve frontend=react (no swiftui crash)"
