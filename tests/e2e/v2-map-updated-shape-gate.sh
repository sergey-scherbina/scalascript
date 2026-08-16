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
# THE LAUNCHER IS REQUIRED FOR THE MEASUREMENT, NOT FOR THE SELF-TEST, and demanding it for both made
# the self-test unrunnable in a fresh worktree — which is exactly where someone changing this file
# wants to run it. The check moved below the `--self-test` branch; the arithmetic it exercises needs
# no compiler at all.
need_tools() {
  [ -x "$TOOLS" ] || { echo "map-updated-shape: no launcher at $TOOLS — run ./install.sh --dev" >&2; exit 2; }
}

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

# Fewer reps than the single-shot version used, because the power now comes from taking THREE
# readings per arm and reducing with a median rather than from one long reading. Total wall clock is
# about the same, which keeps the smoke baseline row honest.
ms() {  # $1 = keyspace -> workload-only ms per iteration
  "$TOOLS" --backend v2 bench --machine --warmup-time 1000 --reps 10 "$tmp/map$1.ssc" 2>/dev/null |
    awk '/^BENCH /{print $3}'
}

med3() { printf '%s\n' "$1" "$2" "$3" | sort -g | sed -n 2p; }

# THE CONTROL IS max/min ACROSS ALL THREE IDENTICAL-WORK READINGS, and this line was argued to three
# different answers before a measurement settled it. Recorded because the wrong two are tempting.
#
# The question is "can this host resolve a 2.5x difference today?", answered by running the SAME work
# three times and asking how far apart the readings land. The defect this gate exists for is FIXED on
# the current binary, so any RED is a FALSE POSITIVE — which makes an A/A over ten runs a direct
# measurement of each candidate:
#
#     control statistic                            false reds   no verdict   pass
#     the OLD one (one pair, sampled in a block)         0            7         3
#     the closest ADJACENT gap of three                  1            3         6
#     max/min across all three                           0           10         0
#
# The middle row is the one written first as a "correction", on the reasoning that three samples
# spread wider than two so a limit calibrated for two had silently become stricter. The reasoning is
# true and the conclusion was wrong: discarding the outlier lets two lucky-close readings authorise a
# verdict while the median is still bimodal, and it produced a false RED the original did not have.
# IGNORING THE EXTREME IS EXACTLY WHAT YOU MUST NOT DO WHEN THE EXTREME IS THE NOISE YOU ARE TESTING
# FOR.
#
# So: max/min. 0 of 10 verdicts on a host at load ~45 is not this gate failing — it is the gate saying
# something true that nothing else says. `RESULT=no-verdict` is greppable so a run of them can be
# counted from CI logs; a check that never renders a verdict covers nothing, and that has to be
# VISIBLE rather than fixed by loosening the thing keeping it honest.
spread3() { printf '%s\n' "$1" "$2" "$3" | sort -g |
  awk 'NR==1{lo=$1} END{ if (lo > 0) printf "%.2f", $1/lo; else print "0" }'; }

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
  # THE REDUCERS ARE PART OF THE VERDICT NOW, so they are exercised here too. A median that returned
  # the first reading, or a spread that compared the wrong pair, would turn this gate back into the
  # single-shot thing it just stopped being — silently, because both still produce a number.
  got="$(med3 5 1 3)"
  [ "$got" = "3" ] || { echo "  self-test FAIL: med3 5 1 3 gave '$got', want 3"; fail=1; }
  got="$(med3 2 2 2)"
  [ "$got" = "2" ] || { echo "  self-test FAIL: med3 2 2 2 gave '$got', want 2"; fail=1; }
  # spread3 is max/min across ALL THREE. The outlier is the noise this gate tests for, so it must
  # DEFINE the spread rather than be discarded — a "closest pair" version of this line measured a
  # false RED over ten runs where max/min measured none.
  got="$(spread3 3 3 3)"
  [ "$got" = "1.00" ] || { echo "  self-test FAIL: spread3 3 3 3 gave '$got', want 1.00"; fail=1; }
  got="$(spread3 1 1 4)"     # two agree exactly and the third is wild: the WILD one decides
  [ "$got" = "4.00" ] || { echo "  self-test FAIL: spread3 1 1 4 gave '$got', want 4.00 — the outlier is being discarded"; fail=1; }
  got="$(spread3 4 1 1)"     # order must not matter
  [ "$got" = "4.00" ] || { echo "  self-test FAIL: spread3 4 1 1 gave '$got', want 4.00"; fail=1; }
  got="$(spread3 1 2 4)"
  [ "$got" = "4.00" ] || { echo "  self-test FAIL: spread3 1 2 4 gave '$got', want 4.00"; fail=1; }

  # AND THE GUARD READS BOTH ARMS. Guarding only the control arm is a floor on the good number: a
  # host that cannot repeat the LARGE reading cannot support a verdict either.
  guard() { awk -v a="$1" -v b="$2" -v m=1.5 'BEGIN{ print (a > m || b > m) ? "NO-VERDICT" : "VERDICT" }'; }
  got="$(guard 1.1 1.2)"
  [ "$got" = "VERDICT" ] || { echo "  self-test FAIL: quiet host read as $got"; fail=1; }
  got="$(guard 1.9 1.1)"
  [ "$got" = "NO-VERDICT" ] || { echo "  self-test FAIL: noisy SMALL arm read as $got"; fail=1; }
  got="$(guard 1.1 1.9)"
  [ "$got" = "NO-VERDICT" ] || { echo "  self-test FAIL: noisy LARGE arm read as $got — the large arm is not guarded"; fail=1; }

  [ "$fail" = 0 ] || { echo "map-updated-shape: SELF-TEST FAILED"; exit 1; }
  echo "map-updated-shape self-test: PASS (4.9x RED, 1.3x GREEN, 2.5x boundary GREEN,"
  echo "   med3/spread3 exact, and BOTH arms gate the verdict)"
  exit 0
fi

need_tools
gen 10
gen 1000

# A CONTROL, GENUINELY INTERLEAVED — and the previous version of this comment was WRONG ABOUT THE
# CODE BENEATH IT.
#
# It said "Interleaved rather than run in a block, because a control taken before or after the
# experiment describes a different host than the experiment saw" — and then measured
# `SMALL; LARGE; SMALL2`, which is a block with the control on the end. A block gives the control
# exactly ONE chance to notice the load, and load moves during a 30-second run. So the failing
# combination is not rare: the control lands inside tolerance while the experiment was taken during a
# spike, and the gate then reports a confident, well-formed regression with a slug and a board
# reference attached — the most convincing possible false positive. It did exactly that inside
# `scripts/smoke-ci` on a v1 `Typer.scala` edit that cannot reach the v2 native lane at all, at load
# 50.76. Re-run standalone three times on the same binary it passed all three, and in two of them the
# 1000-key map came out FASTER than the 10-key one, which is not a thing a copying `updated` can do.
# (v2/BUGS.md -> map-updated-shape-reads-host-load-as-a-regression.)
#
# NOW: three ROUNDS, each holding both arms next to each other, with the order rotated so neither
# size always runs first on a warming host:
#
#     round 1   small large
#     round 2   large small
#     round 3   small large
#
# A spike now lands inside a round and moves BOTH arms, and the median of three discards the round it
# ruined. The control is the spread across the three identical-work small readings, which is the same
# question as before asked of data that can actually answer it.
#
# The tolerance is deliberately NOT raised. Raising it would only make the gate blind to the defect it
# exists for; the quiet-host numbers show a real signal is present when the host can be read at all.
CONTROL_MAX="${SSC_MAP_CONTROL_MAX:-1.5}"

S1="$(ms 10)";   L1="$(ms 1000)"
L2="$(ms 1000)"; S2="$(ms 10)"
S3="$(ms 10)";   L3="$(ms 1000)"

for v in "$S1" "$S2" "$S3" "$L1" "$L2" "$L3"; do
  [ -n "$v" ] || { echo "map-updated-shape: a lane produced no BENCH line — the gate measured nothing" >&2; exit 2; }
done

SMALL="$(med3 "$S1" "$S2" "$S3")"
LARGE="$(med3 "$L1" "$L2" "$L3")"
spread="$(spread3 "$S1" "$S2" "$S3")"
lspread="$(spread3 "$L1" "$L2" "$L3")"
ratio="$(awk -v s="$SMALL" -v l="$LARGE" 'BEGIN{printf "%.1f", l/s}')"

echo "── Map.updated, 500 calls, workload-only ms (v2 lane), 3 interleaved rounds ──"
echo "   10 keys:   $S1  $S2  $S3   -> median $SMALL ms  (identical work, spread ${spread}x)"
echo "   1000 keys: $L1  $L2  $L3   -> median $LARGE ms  (spread ${lspread}x)"

# BOTH SPREADS ARE READ, not just the small one. The large arm is identical work across its three
# readings too, so a host that cannot repeat IT cannot support a verdict either — and guarding only
# the arm that happens to be the control is the shape of a floor on the good number.
if awk -v a="$spread" -v b="$lspread" -v m="$CONTROL_MAX" 'BEGIN { exit !(a > m || b > m) }'; then
  echo "map-updated-shape: RESULT=no-verdict — identical work spread ${spread}x (10 keys) and"
  echo "   ${lspread}x (1000 keys), against a ${CONTROL_MAX}x limit, so this host cannot resolve the 2.5x"
  echo "   line today. NOT A PASS: nothing was measured. The ratio below is noise, not a reading."
  echo "   ratio would have been ${ratio}x"
  echo "   (v2/BUGS.md -> v2-map-updated-copies-the-whole-map records why this control exists.)"
  # Exit 0 deliberately: a false red is worse than a skipped check. But the line above is greppable
  # ON PURPOSE — `RESULT=no-verdict` is how a run of these can be counted from CI logs, because a
  # check that returns no verdict on most runs is covering nothing and nothing else would show it.
  exit 0
fi

if awk -v s="$SMALL" -v l="$LARGE" 'BEGIN { exit !(l > s * 2.5) }'; then
  echo "map-updated-shape: RESULT=fail — the large map costs ${ratio}x the small one."
  echo "   Map.updated is scaling with the map's SIZE, which means it is copying rather than sharing."
  echo "   v2/BUGS.md -> v2-map-updated-copies-the-whole-map"
  exit 1
fi
echo "map-updated-shape: RESULT=pass (${ratio}x, under the 2.5x line; controls ${spread}x / ${lspread}x)"
