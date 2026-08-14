#!/usr/bin/env bash
# v2-map-updated-shape-gate — `Map.updated` must not cost O(size).
#
# THE GATE ASSERTS A SHAPE, NOT A TIME, and that is the design. A wall-clock threshold on this host
# is unreadable: identical code spread 2.5x at load 5.5, and four consecutive measurements in
# `specs/ssc3-jit.md` could see nothing under 2x. A RATIO between two runs of the SAME program at two
# map sizes survives that, because both halves pay the same host tax.
#
# WHAT IT CATCHES. `MapV` used to be a mutable `LinkedHashMap` and every immutable update was
# `MapV.from(m)` — a full copy — then one mutation, so `m.updated(k, v)` was O(size). Workload-only
# ms for 500 `updated` calls, measured 2026-08-14:
#
#     keyspace     copying store    persistent store
#     10           0.3504           0.0849
#     1000         1.71             0.1097
#     ratio        4.9x             1.3x
#
# See `v2/BUGS.md` -> `v2-map-updated-copies-the-whole-map`.
#
# ── WHY IT TIMES THE WORKLOAD AND NOT THE PROCESS, which is the whole reason this file exists ────
#
# The first version of this gate wrapped `bin/ssc run` in a wall clock and compared the two totals.
# It PASSED against the defect — 3528 ms vs 3560 ms, ratio 1.0 — because 3.5 s of JVM startup and
# compilation drowned 64 ms of workload. It had a floor on the small reading, and the floor did not
# help: it guarded that the number was big enough to compare, not that the WORKLOAD was what made it
# big. Found by running the gate against the old build instead of trusting it, which is the only way
# this kind of miss is ever found.
#
# `ssc-tools bench --machine` reports ms per workload iteration with compilation excluded and warmup
# done, so the two readings differ in the one thing the gate is about.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

TOOLS="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
[ -x "$TOOLS" ] || { echo "map-updated-shape: no launcher at $TOOLS — run ./install.sh --dev" >&2; exit 2; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 500 `updated` calls in both programs. ONLY the keyspace differs, so the call count, the loop and the
# arithmetic cancel in the ratio; what does not cancel is how many entries each call has to carry.
gen() {
  cat > "$tmp/map$1.ssc" <<EOF
def workload(): Long =
  var m: Map[Int, Int] = Map[Int, Int]()
  var i = 0
  while i < 500 do
    m = m.updated(i % $1, i)
    i = i + 1
  m.size.toLong
EOF
}

ms() {  # $1 = keyspace -> workload-only ms per iteration
  "$TOOLS" --backend v2 bench --machine --warmup-time 1500 --reps 20 "$tmp/map$1.ssc" 2>/dev/null |
    awk '/^BENCH /{print $3}'
}

gen 10
gen 1000

if [ "${1:-}" = "--self-test" ]; then
  # THE GATE MUST BE ABLE TO SAY NO, and the arithmetic is what decides, so it is exercised on the
  # numbers this defect and its fix actually produced.
  fail=0
  verdict() { awk -v s="$1" -v l="$2" 'BEGIN { print (l > s * 2.5) ? "RED" : "GREEN" }'; }
  got="$(verdict 0.3504 1.71)"   # the copying store, measured: 4.9x
  [ "$got" = "RED" ] || { echo "  self-test FAIL: the copying store read as $got"; fail=1; }
  got="$(verdict 0.0849 0.1097)" # the persistent store, measured: 1.3x
  [ "$got" = "GREEN" ] || { echo "  self-test FAIL: the persistent store read as $got"; fail=1; }
  got="$(verdict 0.1 0.25)"      # exactly 2.5x is not over the line
  [ "$got" = "GREEN" ] || { echo "  self-test FAIL: the boundary read as $got"; fail=1; }
  [ "$fail" = 0 ] || { echo "map-updated-shape: SELF-TEST FAILED"; exit 1; }
  echo "map-updated-shape self-test: PASS (4.9x RED, 1.3x GREEN, the 2.5x boundary GREEN)"
  exit 0
fi

SMALL="$(ms 10)"
LARGE="$(ms 1000)"
[ -n "$SMALL" ] && [ -n "$LARGE" ] || {
  echo "map-updated-shape: a lane produced no BENCH line — the gate measured nothing" >&2; exit 2; }

echo "── Map.updated, 500 calls, workload-only ms (v2 lane) ──"
echo "   10 keys:   $SMALL ms"
echo "   1000 keys: $LARGE ms"
ratio="$(awk -v s="$SMALL" -v l="$LARGE" 'BEGIN{printf "%.1f", l/s}')"
if awk -v s="$SMALL" -v l="$LARGE" 'BEGIN { exit !(l > s * 2.5) }'; then
  echo "map-updated-shape: FAIL — the large map costs ${ratio}x the small one."
  echo "   Map.updated is scaling with the map's SIZE, which means it is copying rather than sharing."
  echo "   v2/BUGS.md -> v2-map-updated-copies-the-whole-map"
  exit 1
fi
echo "map-updated-shape: PASS (${ratio}x, under the 2.5x line)"
