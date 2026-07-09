#!/usr/bin/env bash
# v2-only regression: an http `Request` and a domain case class ALSO named
# `Request` in the same file. The http `req.params(:name)` field access (a
# field-WITH-args on a plugin-native value whose tag collides with a user type)
# must resolve to the real segment, AND the domain Request's fields must resolve
# — each against the layout matching the receiver's arity. v1 is the correct,
# pinned baseline; --v2 used to return "Stub" for the http field
# (v2-req-form-type-collision — busi's real hub POST /pair on req.form).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURE="$ROOT/e2e/fixtures/req-type-collision-smoke.ssc"
BIN="$ROOT/../bin/ssc"
PORT=8834
trap 'lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT
kill_port() { lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null; sleep 1; }
run_lane() {
    local lane="$1"; kill_port
    "$BIN" "$lane" "$FIXTURE" > "/tmp/req-type-collision-$lane.log" 2>&1 &
    local pid=$!
    local deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        curl -sS -o /dev/null -m 1 "http://localhost:$PORT/end/probe" 2>/dev/null && break
        sleep 0.5
    done
    local mid; mid=$(curl -s -m 5 "http://localhost:$PORT/mid/hello/tail")
    kill "$pid" 2>/dev/null; kill_port
    echo "$lane: $mid"
}
V1=$(run_lane --v1)
V2=$(run_lane --v2)
echo "$V1"
echo "$V2"
case "$V1" in
  *"mid=hello id=r1 kind=task"*) : ;;
  *) echo "req-type-collision FAIL: --v1 baseline broke"; exit 1 ;;
esac
case "$V2" in
  *"mid=hello id=r1 kind=task"*) echo "req-type-collision PASS: --v2 resolves http req.params AND domain Request fields" ;;
  *) echo "req-type-collision FAIL: --v2 (got: $V2)"; exit 1 ;;
esac
