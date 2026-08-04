#!/usr/bin/env bash
# v3 SSC3-3 gate — the V-0 bridge, end to end.
#
# For each `v3/tests/bridge/*.ssir`:  ssc3 verifies it, translates it to v2 Core IR, and the v2 VM
# RUNS the result. The verdict is the program's OUTPUT compared against a checked-in expectation.
#
# Output, never the exit code. v2 fails by printing a sentinel at exit 0, and the array defect this
# module's own SSC3-1 fixed exited 0 on a wrong answer — an exit-code check would have seen neither.
#
# The gate is red in BOTH directions: `unsupported.ssir` uses an instruction V-0 does not translate
# and MUST be refused with a message naming it. A bridge that silently emitted something for every
# input would pass a one-directional gate while producing programs nobody can trust.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SSC3="scala-cli run v3/src --server=false --quiet --"
V2="scala-cli run v2/src --server=false --quiet --java-opt=-Xss512m --"
fail=0
ran=0

echo "── translated and RUN on the v2 VM ─────────────────────────────────────"
for f in v3/tests/bridge/*.ssir; do
  name="$(basename "$f" .ssir)"
  exp="v3/tests/bridge/$name.expected"
  [ -f "$exp" ] || continue
  ran=$((ran + 1))
  ir="$(mktemp -t ssc3ir)"
  if ! $SSC3 emit-v2 "$f" > "$ir" 2>/dev/null; then
    echo "  FAIL $name — the bridge refused a case it is supposed to translate"
    fail=1; rm -f "$ir"; continue
  fi
  got="$($V2 run-ir "$ir" 2>/dev/null)"
  rm -f "$ir"
  if [ "$got" = "$(cat "$exp")" ]; then
    echo "  ok   $name -> $(printf '%s' "$got" | tr '\n' '/')"
  else
    echo "  FAIL $name — expected [$(tr '\n' '/' < "$exp")] got [$(printf '%s' "$got" | tr '\n' '/')]"
    fail=1
  fi
done

echo
echo "── the other direction: what V-0 must REFUSE ───────────────────────────"
msg="$($SSC3 emit-v2 v3/tests/bridge/unsupported.ssir 2>&1 >/dev/null)"
if [ -z "$msg" ]; then
  echo "  FAIL an untranslatable instruction was ACCEPTED — the bridge emits for anything"
  fail=1
elif printf '%s' "$msg" | grep -q 'resume'; then
  echo "  ok   refused, and named the instruction: $msg"
else
  echo "  FAIL refused without naming what it could not translate: $msg"
  fail=1
fi

echo
# A gate that ran nothing exits 0 and reads as green. Fail LOUD instead.
if [ "$ran" -eq 0 ]; then echo "== v3 SSC3-3 gate: NO CASES RAN =="; exit 2; fi
[ "$fail" = 0 ] && echo "== v3 SSC3-3 gate: GREEN ($ran program(s) executed) ==" || echo "== v3 SSC3-3 gate: RED =="
exit "$fail"
