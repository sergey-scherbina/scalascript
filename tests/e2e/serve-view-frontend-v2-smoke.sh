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
BIN="$ROOT/../bin/ssc"
PORT=8099
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
    local code; code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "http://localhost:$PORT/")
    local fe;   fe=$(grep -oE "frontend=[a-z]+" "/tmp/serve-view-$lane.log" | head -1)
    local swift; swift=$(grep -c "swiftui.*native-only" "/tmp/serve-view-$lane.log" 2>/dev/null || echo 0)
    kill "$pid" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
    echo "$lane: http=$code $fe swiftui-crash=$swift"
}
V1=$(run_lane --v1); V2=$(run_lane --v2)
echo "$V1"; echo "$V2"
for r in "$V1" "$V2"; do
  case "$r" in
    *"http=200 frontend=react swiftui-crash=0"*) : ;;
    *) echo "serve-view-frontend FAIL: $r"; exit 1 ;;
  esac
done
echo "serve-view-frontend PASS: both lanes serve frontend=react (no swiftui crash)"
