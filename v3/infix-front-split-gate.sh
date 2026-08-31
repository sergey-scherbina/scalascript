#!/usr/bin/env bash
#
# infix-front-split-gate — `Box(40) add 2` works on v3's own front and is REFUSED by the spike
# front, and the gate proves both halves by controlling which front runs.
#
#   ./v3/infix-front-split-gate.sh
#
# THIS GATE PINS A DEFECT and goes RED when it is fixed, on purpose.
# v3/BUGS.md `infix-application-does-not-reach-a-declared-class-method`.
#
# WHY THIS SHAPE, AND WHY THE OBVIOUS ONE WAS WITHDRAWN. A `v3/tests/front` fixture for this program
# was written and taken back out (c780b1832), because its verdict FLIPS with an artifact:
#
#     v3/.jars/uniml.cp absent   ->  ssc3 run: 42 / 42
#     v3/.jars/uniml.cp present  ->  ssc3 run: REFUSED, expected ')' to close call [spike.expected]
#
# So the fixture was green in a built tree and red in a fresh one, with neither colour being about
# the compiler. `ssc3 run` and `ssc3 exec` behave IDENTICALLY here — an earlier claim that they
# differ was wrong and is retracted at the `.refuses` slot.
#
# THE ANSWER IS TO CONTROL THE VARIABLE RATHER THAN SUFFER IT. This gate runs the SAME program in
# BOTH states, toggling the classpath itself, and asserts the pair. That turns the thing that made
# a fixture untrustworthy into the thing being measured: the defect IS that one front lowers this
# and the other cannot.
#
# THE PAIR IS THE ASSERTION. Either half alone is worthless — "it refuses" would pass on any broken
# program, and "it prints 42" would pass on a tree where the second front simply is not registered.
# Together they say the two fronts DISAGREE about a legal program, which is the entry's claim.
#
# RESTORING THE CLASSPATH IS NOT OPTIONAL. The gate moves a real artifact out of the way, so the
# trap restores it on every exit path including a signal — leaving `uniml.cp` moved would silently
# turn every later v3 differential into a one-front run reporting green, which is the exact failure
# `a-fresh-control-worktree-silently-runs-a-different-front` records.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CP="v3/.jars/uniml.cp"
STASH=""
restore() { [ -n "$STASH" ] && [ -f "$STASH" ] && mv "$STASH" "$CP"; STASH=""; }
trap 'restore; rm -rf "$sandbox"' EXIT HUP INT TERM

sandbox="$(mktemp -d "${TMPDIR:-/tmp}/infixsplit.XXXXXX")"
cat > "$sandbox/infix.ssc" <<'EOF'
case class Box(v: Int):
  def add(other: Int): Int = v + other

println(Box(40).add(2))
println(Box(40) add 2)
EOF

if [ ! -s "$CP" ]; then
  echo "infix-front-split: SKIP — $CP is absent, so only one front is registered here."
  echo "  A SKIP, not a pass: this gate compares TWO fronts and cannot with one."
  echo "  Build it with v3/uniml-classpath.sh to make this check meaningful."
  exit 0
fi

fails=0

# HALF 1 — the spike front, registered. It cannot parse the infix application.
out_spike="$(timeout 300 v3/ssc3 run "$sandbox/infix.ssc" 2>&1)"; rc_spike=$?

# HALF 2 — v3's own front, reached by taking the other one out of the registry.
STASH="$sandbox/uniml.cp"; mv "$CP" "$STASH"
out_own="$(timeout 300 v3/ssc3 run "$sandbox/infix.ssc" 2>&1)"; rc_own=$?
restore

if [ "$rc_own" -ne 0 ] || [ "$(printf '%s' "$out_own" | grep -c '^42$')" -ne 2 ]; then
  echo "infix-front-split: FAIL — v3's OWN front no longer lowers this." >&2
  echo "  Both spellings must print 42. This half is the one that WORKS, so a failure here is a" >&2
  echo "  regression in v3's front rather than the tracked gap." >&2
  echo "  got: $out_own" >&2
  fails=$((fails + 1))
else
  echo "  ok   v3's own front: both spellings print 42"
fi

if [ "$rc_spike" -eq 0 ]; then
  echo "  ✓ the spike front now lowers it too — THE GAP IS CLOSED." >&2
  echo "    That is what this gate exists to notice. Close v3/BUGS.md" >&2
  echo "    infix-application-does-not-reach-a-declared-class-method and replace this gate with a" >&2
  echo "    plain fixture; do not delete the assertion to get green." >&2
  fails=$((fails + 1))
elif ! grep -q "expected ')' to close call" <<<"$out_spike"; then
  echo "infix-front-split: FAIL — the spike front refuses, but not at the infix application." >&2
  echo "  want a parse refusal: \"expected ')' to close call\". A different message means the" >&2
  echo "  subject drifted, or the refusal moved to a different stage." >&2
  echo "  got: $out_spike" >&2
  fails=$((fails + 1))
else
  echo "  ok   spike front: refuses at the infix application, expected ')' to close call"
fi

if [ ! -s "$CP" ]; then
  echo "infix-front-split: FAIL — $CP was not restored. Later v3 differentials would silently" >&2
  echo "  run one front and report green." >&2
  exit 1
fi

if [ "$fails" -ne 0 ]; then
  echo "== v3 infix-front-split gate: RED ==" >&2
  exit 1
fi
echo "== v3 infix-front-split gate: GREEN (the two fronts disagree, as tracked) =="
