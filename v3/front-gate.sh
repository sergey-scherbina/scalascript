#!/usr/bin/env bash
# v3 SSC3-4 gate — the front, end to end on real `.ssc` source.
#
# For each `v3/tests/front/*.ssc` with an `.expected`: `v3/ssc3 run` lexes, parses, lowers to SSC
# IR, VERIFIES and executes it on V3'S OWN EXECUTOR, and the verdict is the program's OUTPUT.
#
# THIS PARAGRAPH USED TO SAY "bridges to v2 Core IR and executes it", AND THAT STOPPED BEING TRUE ON
# 2026-08-07 WITHOUT ANYONE EDITING THIS FILE. `5cdf4a3c5` repointed `ssc3 run` from the bridge to
# v3's own runtime and updated `parity-gate.sh`, which uses the same command; this gate and
# `exec-gate.sh` were missed. There the consequence was severe — a two-lane differential silently
# comparing one lane with itself, see BUGS.md
# `v3-exec-gate-ssc-differential-compared-the-EXECUTOR-WITH-ITSELF`.
#
# Here it is only the description that was wrong, and the description is what changed rather than
# the command: `exec-gate.sh` now runs every one of these fixtures through `run --bridge` AND
# compares the two lanes, which is strictly more than this gate could say by running the bridge
# alone. Running them through it twice would cost minutes and add nothing. What this gate is for is
# the FRONT — that a real `.ssc` program lexes, parses, lowers, verifies and produces the right
# answer, and that a program it must refuse is refused with a position.
#
# Output, never the exit code — v2 fails by printing a sentinel at exit 0.
#
# Red in BOTH directions: `.ssc` files with NO `.expected` are programs the front must REFUSE, and
# the refusal has to name a source position. A front that accepted everything would pass a
# one-directional gate while emitting nonsense.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
fail=0
ran=0

# Compile ONCE before measuring. `v3/ssc3` shells into `scala-cli run`, which compiles on demand, and
# a compile racing the first case produced an empty result that the gate scored as a FAILURE —
# observed 2026-08-01 right after editing the lexer. A gate that is red for a reason that is not the
# code under test is a gate people stop believing, which is worse than not having it.
v3/ssc3 selftest >/dev/null 2>&1

# The paragraph above is about a compile racing the first case. THIS IS THE SAME SHAPE FROM A
# DIFFERENT CAUSE: which fronts are registered is a fact about the WORKING TREE, not about the code.
# The uniml front exists only after `v3/uniml-classpath.sh` has been run here, and a fixture marked
# `.uniml-only` uses a construct v3's own parser refuses — so in a fresh worktree it produces nothing
# and this loop scored it as a failing fixture. On 2026-08-08 that read as a regression from a
# sibling's commit and was reported as one.
uniml=0
v3/ssc3 fronts 2>/dev/null | grep -qx uniml && uniml=1
unreadable=0; unreadable_names=""
ERRF="$(mktemp)"; trap 'rm -f "$ERRF"' EXIT

echo "── compiled by v3 and RUN ──────────────────────────────────────────────"
for f in v3/tests/front/*.ssc; do
  name="$(basename "$f" .ssc)"
  exp="v3/tests/front/$name.expected"
  [ -f "$exp" ] || continue
  if [ -f "v3/tests/front/$name.uniml-only" ] && [ "$uniml" = 0 ]; then
    unreadable=$((unreadable + 1)); unreadable_names="$unreadable_names $name"; continue
  fi
  ran=$((ran + 1))
  got="$(v3/ssc3 run "$f" 2>"$ERRF")"
  why="$(head -1 "$ERRF")"
  if [ "$got" = "$(cat "$exp")" ]; then
    echo "  ok   $name -> $(printf '%s' "$got" | tr '\n' '/')"
  else
    # WITH THE REASON. Sending stderr to /dev/null made "the front refused this" and "the program
    # printed the wrong thing" the same observation — an empty string either way.
    echo "  FAIL $name — expected [$(tr '\n' '/' < "$exp")] got [$(printf '%s' "$got" | tr '\n' '/')]${why:+  ← $why}"
    fail=1
  fi
done

if [ "$unreadable" != 0 ]; then
  echo
  echo "  ✋ $unreadable fixture(s) COULD NOT BE READ IN THIS WORKING TREE, and were not run:"
  echo "    $unreadable_names"
  echo "     Marked \`.uniml-only\`; the uniml front is not registered here. Run"
  echo "     \`v3/uniml-classpath.sh\`, then re-run. RED rather than skipped because a gate that goes"
  echo "     green with fixtures unrun reports less than it claims — but nothing in the diff you are"
  echo "     testing can fix it, so do not read it as a defect in the code."
  fail=1
fi

echo
echo "── programs the front must REFUSE, with a position ─────────────────────"
for f in v3/tests/front/*.ssc; do
  name="$(basename "$f" .ssc)"
  [ -f "v3/tests/front/$name.expected" ] && continue
  ran=$((ran + 1))
  msg="$(v3/ssc3 build "$f" 2>&1 >/dev/null)"
  if [ -z "$msg" ]; then
    echo "  FAIL $name was ACCEPTED — the front emits for anything"
    fail=1
  # A diagnostic without a line:col is a diagnostic nobody can act on, so the gate demands one.
  elif grep -qE ':[0-9]+:[0-9]+:' <<<"$msg"; then
    echo "  ok   $name refused: $msg"
  else
    echo "  FAIL $name refused without a source position: $msg"
    fail=1
  fi
done

echo
if [ "$ran" -eq 0 ]; then echo "== v3 SSC3-4 gate: NO CASES RAN =="; exit 2; fi
[ "$fail" = 0 ] && echo "== v3 SSC3-4 gate: GREEN ($ran case(s)) ==" || echo "== v3 SSC3-4 gate: RED =="
exit "$fail"
