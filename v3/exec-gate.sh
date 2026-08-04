#!/usr/bin/env bash
# v3 SSC3-3b gate — the executor, and the two things it is FOR.
#
# 1. DIFFERENTIAL. Every bridge fixture runs on BOTH lanes — v3's own executor and the v2 bridge —
#    and their OUTPUT must be identical. Two independent implementations agreeing is evidence
#    neither can produce alone; when they disagree, one of them is wrong and the gate says which
#    program exposed it. This is the technique that found 8 defects in uniml that point examples
#    had missed.
#
# 2. CONSTANT STACK, proven BY CONTRAST rather than asserted. `tail-call.ssir` makes 10 000 000 tail
#    calls: the executor returns 10000000, and the same program through the bridge dies with
#    StackOverflowError because v2 has no TCO. A gate that only ran the executor could not tell the
#    difference between a real tail call and a lucky stack size.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SSC3="scala-cli run v3/src --server=false --quiet --"
V2="scala-cli run v2/src --server=false --quiet --java-opt=-Xss512m --"
fail=0; ran=0
$SSC3 selftest >/dev/null 2>&1   # compile once; a compile racing the first case reads as a failure

echo "── differential: v3 executor vs the v2 bridge ──────────────────────────"
for f in v3/tests/bridge/*.ssir; do
  name="$(basename "$f" .ssir)"
  [ -f "v3/tests/bridge/$name.expected" ] || continue
  ran=$((ran + 1))
  own="$($SSC3 exec "$f" 2>/dev/null)"
  ir="$(mktemp -t ssc3x)"; $SSC3 emit-v2 "$f" > "$ir" 2>/dev/null
  via="$($V2 run-ir "$ir" 2>/dev/null)"; rm -f "$ir"
  exp="$(cat "v3/tests/bridge/$name.expected")"
  if [ "$own" = "$via" ] && [ "$own" = "$exp" ]; then
    echo "  ok   $name -> $(printf '%s' "$own" | tr '\n' '/')  (both lanes agree)"
  else
    echo "  FAIL $name — executor [$(printf '%s' "$own" | tr '\n' '/')] bridge [$(printf '%s' "$via" | tr '\n' '/')] expected [$(printf '%s' "$exp" | tr '\n' '/')]"
    fail=1
  fi
done

echo
echo "── differential: the same, on real .ssc source ─────────────────────────"
# This is where the differential earns its keep. The IR fixtures above are hand-written and small;
# these go through the whole front, so a disagreement here indicts the lowering, the bridge or the
# executor — and says which program exposed it.
for f in v3/tests/front/*.ssc; do
  name="$(basename "$f" .ssc)"
  exp="v3/tests/front/$name.expected"
  [ -f "$exp" ] || continue
  ran=$((ran + 1))
  own="$($SSC3 exec "$f" 2>/dev/null)"
  via="$(v3/ssc3 run "$f" 2>/dev/null)"
  want="$(cat "$exp")"
  if [ "$own" = "$via" ] && [ "$own" = "$want" ]; then
    echo "  ok   $name -> $(printf '%s' "$own" | tr '\n' '/')  (both lanes agree)"
  else
    echo "  FAIL $name — executor [$(printf '%s' "$own" | tr '\n' '/')] bridge [$(printf '%s' "$via" | tr '\n' '/')] expected [$(printf '%s' "$want" | tr '\n' '/')]"
    fail=1
  fi
done

echo
echo "── constant stack, shown by contrast ───────────────────────────────────"
ran=$((ran + 1))
own="$($SSC3 exec v3/tests/tail-call.ssir 2>/dev/null)"
if [ "$own" = "$(cat v3/tests/tail-call.expected)" ]; then
  echo "  ok   executor survives 10 000 000 tail calls -> $own"
else
  echo "  FAIL executor did not complete the tail-call program: [$own]"
  fail=1
fi
# The same contrast from `.ssc` SOURCE, which is what proves the tail-call PASS fires: without
# TailCalls.scala rewriting `if n == 0 then true else isOdd(n - 1)`, the executor recurses too.
ran=$((ran + 1))
own="$($SSC3 exec v3/tests/mutual-recursion.ssc 2>/dev/null)"
if [ "$own" = "$(cat v3/tests/mutual-recursion.expected)" ]; then
  echo "  ok   executor survives 100 000 MUTUAL calls from .ssc source -> $(printf '%s' "$own" | tr '\n' '/')"
else
  echo "  FAIL executor did not complete mutual recursion: [$(printf '%s' "$own" | tr '\n' '/')]"
  fail=1
fi
# The bridge USED to overflow here and no longer does: the group-merge pass turns mutual tail
# recursion into a loop IN THE IR, so both lanes get it. The gate now asserts the stronger property
# — they AGREE — instead of the contrast it asserted while only the executor could do it. It went
# red the moment that changed, which is what an expiring assertion is for.
bridge_mut="$(v3/ssc3 run v3/tests/mutual-recursion.ssc 2>/dev/null)"
if [ "$bridge_mut" = "$(cat v3/tests/mutual-recursion.expected)" ]; then
  echo "  ok   the bridge completes it too now — mutual recursion is a loop in the IR, not a lane trick"
else
  echo "  FAIL the bridge no longer matches on mutual recursion: [$(printf '%s' "$bridge_mut" | tr '\n' '/')]"
  fail=1
fi

# The hand-written `.ssir` contrast still holds, and it is worth saying why: a `.ssir` is read
# straight into the IR and NO pass runs on it, so its raw `TailCall` reaches the bridge intact. That
# is what still distinguishes a backend that honours TailCall from one that does not.
ir="$(mktemp -t ssc3x)"; $SSC3 emit-v2 v3/tests/tail-call.ssir > "$ir" 2>/dev/null
# Capture FIRST, then match. Under `set -o pipefail` the pipeline takes java's exit status, and java
# exits non-zero precisely BECAUSE it overflowed — so `… | grep -q` reported failure exactly when
# the thing being looked for had happened. The check inverted itself.
bridge_out="$($V2 run-ir "$ir" 2>&1)"
if printf '%s' "$bridge_out" | grep -q StackOverflowError; then
  echo "  ok   the bridge overflows on the same program — so the check can tell the two apart"
else
  echo "  FAIL the bridge did NOT overflow; this comparison no longer proves anything"
  fail=1
fi
rm -f "$ir"

echo
if [ "$ran" -eq 0 ]; then echo "== v3 SSC3-3b gate: NO CASES RAN =="; exit 2; fi
[ "$fail" = 0 ] && echo "== v3 SSC3-3b gate: GREEN ($ran case(s)) ==" || echo "== v3 SSC3-3b gate: RED =="
exit "$fail"
