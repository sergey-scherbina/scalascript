#!/usr/bin/env bash
# v3 SSC3-17 — LANE PARITY, one probe per method.
#
# Invariant I-3 says v3's two lanes agree byte for byte. The fixture gates check that for the
# constructs they happen to use; this checks it for the METHOD SURFACE, which no fixture set covers
# by accident.
#
# It exists because of how the first four divergences were found: `substring`, `indexOf`, `replace`
# and `contains` ran on the bridge and refused on the executor, and I noticed while writing a spec.
# A systematic sweep then found 23 of 32 probes bridge-only — a hole no amount of reading either
# implementation had suggested, because reading tells you what IS there and a probe tells you what
# a program can REACH.
#
# THREE OUTCOMES, and only one is a failure:
#   agree        both lanes ran it and printed the same thing
#   NEITHER      neither lane has it — not a divergence, and not this gate's business
#   DIVERGE      one lane ran it and the other did not, or they printed differently — FAIL
#
# Compare OUTPUT, never exit codes: v2 signals failure by printing a sentinel at exit 0.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SSC3="$ROOT/v3/ssc3"

agree=0; neither=0; fail=0; ran=0
for f in v3/tests/parity/*.ssc; do
  name="$(basename "$f" .ssc)"
  ran=$((ran + 1))
  b="$("$SSC3" run  "$f" 2>/dev/null | tr '\n' '/')"
  e="$("$SSC3" exec "$f" 2>/dev/null | tr '\n' '/')"
  if [ -n "$b" ] && [ "$b" = "$e" ]; then
    agree=$((agree + 1))
  elif [ -z "$b" ] && [ -z "$e" ]; then
    neither=$((neither + 1))
    echo "  neither $name — no lane implements it, which is not a divergence"
  else
    echo "  FAIL $name — bridge [$b] executor [$e]"
    fail=1
  fi
done

echo
if [ "$ran" -eq 0 ]; then echo "== v3 SSC3-17 gate: NO PROBES RAN =="; exit 2; fi
echo "  $agree of $ran probes agree; $neither implemented by neither lane"
[ "$fail" = 0 ] && echo "== v3 SSC3-17 gate: GREEN ($ran probe(s)) ==" || echo "== v3 SSC3-17 gate: RED =="
[ "$fail" = 0 ]
