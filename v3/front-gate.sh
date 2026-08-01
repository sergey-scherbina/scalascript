#!/usr/bin/env bash
# v3 SSC3-4 gate — the front, end to end on real `.ssc` source.
#
# For each `v3/tests/front/*.ssc` with an `.expected`: `v3/ssc3 run` lexes, parses, lowers to SSC
# IR, VERIFIES, bridges to v2 Core IR and executes it, and the verdict is the program's OUTPUT.
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

echo "── compiled by v3 and RUN ──────────────────────────────────────────────"
for f in v3/tests/front/*.ssc; do
  name="$(basename "$f" .ssc)"
  exp="v3/tests/front/$name.expected"
  [ -f "$exp" ] || continue
  ran=$((ran + 1))
  got="$(v3/ssc3 run "$f" 2>/dev/null)"
  if [ "$got" = "$(cat "$exp")" ]; then
    echo "  ok   $name -> $(printf '%s' "$got" | tr '\n' '/')"
  else
    echo "  FAIL $name — expected [$(tr '\n' '/' < "$exp")] got [$(printf '%s' "$got" | tr '\n' '/')]"
    fail=1
  fi
done

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
  elif printf '%s' "$msg" | grep -qE ':[0-9]+:[0-9]+:'; then
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
