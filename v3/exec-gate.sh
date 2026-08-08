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
# Through `v3/ssc3`, NOT `scala-cli run`: the driver caches a jar per source digest, and a bare
# `scala-cli run` recompiles into a SHARED `.scala-build` that a concurrent one can delete
# underneath it — the program then prints nothing and this gate reads that as a wrong answer.
SSC3="v3/ssc3"
V2="v3/ssc3 v2"
fail=0; ran=0
$SSC3 selftest >/dev/null 2>&1   # compile once; a compile racing the first case reads as a failure

# WHICH FRONTS EXIST IS A FACT ABOUT THIS WORKING TREE, NOT ABOUT THE CODE. The uniml front is
# registered only after `v3/uniml-classpath.sh` has been run HERE, and a fresh worktree has not run
# it. A fixture marked `.uniml-only` uses a construct v3's own parser refuses — without that front
# it cannot be read at all, `$SSC3 exec` prints nothing, and this gate used to report it as
# `FAIL … executor [] bridge [] expected [at 0 of hello/…]`: a failing FIXTURE, which indicts the
# code for the state of the checkout. It cost a wrong verdict on a sibling's commit on 2026-08-08 —
# and the control that "confirmed" it (revert the change, watch it still fail) could not have said
# otherwise, because the missing front is reachable without the change.
#
# The same shape is already written down at the top of this file for a different cause: a program
# that prints nothing gets read as a wrong answer. Naming the cause is the whole fix.
uniml=0
$SSC3 fronts 2>/dev/null | grep -qx uniml && uniml=1
unreadable=0; unreadable_names=""
diverging=0; diverging_names=""
ERRF="$(mktemp)"; trap 'rm -f "$ERRF"' EXIT

echo "── differential: v3 executor vs the v2 bridge ──────────────────────────"
for f in v3/tests/bridge/*.ssir; do
  name="$(basename "$f" .ssir)"
  [ -f "v3/tests/bridge/$name.expected" ] || continue
  ran=$((ran + 1))
  own="$($SSC3 exec "$f" 2>/dev/null)"
  ir="$(mktemp "${TMPDIR:-/tmp}/ssc3x.XXXXXX")"; $SSC3 emit-v2 "$f" > "$ir" 2>/dev/null
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
  if [ -f "v3/tests/front/$name.uniml-only" ] && [ "$uniml" = 0 ]; then
    unreadable=$((unreadable + 1)); unreadable_names="$unreadable_names $name"; continue
  fi
  ran=$((ran + 1))
  own="$($SSC3 exec "$f" 2>"$ERRF")"
  why="$(head -1 "$ERRF")"
  # `run --bridge`, NOT `run`. THIS LINE READ `v3/ssc3 run "$f"` FROM 2026-08-02 TO 2026-08-08 AND
  # THE WHOLE `.ssc` HALF OF THIS DIFFERENTIAL COMPARED THE EXECUTOR WITH ITSELF, printing "(both
  # lanes agree)" for every case while only one lane ran. It was written when `ssc3 run` MEANT the
  # bridge; `5cdf4a3c5` repointed `run` at v3's own runtime, updated `parity-gate.sh`, which uses
  # the same command — and did not touch this file. A gate can be broken by a commit that never
  # names it, and this one broke into a shape that says the opposite of the truth.
  via="$(v3/ssc3 run --bridge "$f" 2>/dev/null)"
  want="$(cat "$exp")"
  if [ "$own" = "$via" ] && [ "$own" = "$want" ]; then
    if [ -f "v3/tests/front/$name.bridge-diverges" ]; then
      # A declaration that no longer applies is worse than none: it silences a real future
      # divergence. Removing it is the fix, so the gate demands it.
      echo "  FAIL $name is declared \`.bridge-diverges\` but the lanes now AGREE — delete the marker"
      fail=1
    else
      echo "  ok   $name -> $(printf '%s' "$own" | tr '\n' '/')  (both lanes agree)"
    fi
  elif [ "$own" = "$want" ] && [ -f "v3/tests/front/$name.bridge-diverges" ]; then
    # The executor is right and the bridge is known not to run this. DECLARED, so it is counted and
    # printed rather than hidden — an undeclared divergence below is still a failure.
    diverging=$((diverging + 1)); diverging_names="$diverging_names $name"
    echo "  I-3  $name — executor [$(printf '%s' "$own" | tr '\n' '/')] but the bridge does not run it: $(cat "v3/tests/front/$name.bridge-diverges")"
  else
    # The REASON, not just the empty string it produced. Discarding stderr here is what made an
    # unreadable fixture and a wrong answer look identical.
    echo "  FAIL $name — executor [$(printf '%s' "$own" | tr '\n' '/')] bridge [$(printf '%s' "$via" | tr '\n' '/')] expected [$(printf '%s' "$want" | tr '\n' '/')]${why:+  ← $why}"
    fail=1
  fi
done

if [ "$diverging" != 0 ]; then
  echo
  echo "  $diverging fixture(s) run on the executor and NOT on the v2 bridge — I-3, declared:"
  echo "   $diverging_names"
  echo "     Each carries a \`.bridge-diverges\` file naming its BUGS.md entry. They are COUNTED so"
  echo "     the set cannot grow quietly, and the gate goes red if one of them starts agreeing."
fi

if [ "$unreadable" != 0 ]; then
  echo
  echo "  ✋ $unreadable fixture(s) COULD NOT BE READ IN THIS WORKING TREE, and were not run:"
  echo "    $unreadable_names"
  echo "     They are marked \`.uniml-only\` — they use a construct v3's own parser refuses, and the"
  echo "     uniml front is not registered here. Run \`v3/uniml-classpath.sh\`, then re-run this gate."
  echo "     This is the state of the CHECKOUT. It is RED rather than skipped because a gate that"
  echo "     goes green with fixtures unrun reports less than it claims — but it is NOT a defect in"
  echo "     the code, and nothing in the diff you are testing can fix it."
  fail=1
fi

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
# `--bridge` here too: this line CLAIMED "the bridge completes it too now" while running the
# executor, so the assertion it exists to make was never made. The claim happens to be true —
# measured — but a check that cannot fail is not evidence that it is.
bridge_mut="$(v3/ssc3 run --bridge v3/tests/mutual-recursion.ssc 2>/dev/null)"
if [ "$bridge_mut" = "$(cat v3/tests/mutual-recursion.expected)" ]; then
  echo "  ok   the bridge completes it too now — mutual recursion is a loop in the IR, not a lane trick"
else
  echo "  FAIL the bridge no longer matches on mutual recursion: [$(printf '%s' "$bridge_mut" | tr '\n' '/')]"
  fail=1
fi

# The hand-written `.ssir` contrast still holds, and it is worth saying why: a `.ssir` is read
# straight into the IR and NO pass runs on it, so its raw `TailCall` reaches the bridge intact. That
# is what still distinguishes a backend that honours TailCall from one that does not.
ir="$(mktemp "${TMPDIR:-/tmp}/ssc3x.XXXXXX")"; $SSC3 emit-v2 v3/tests/tail-call.ssir > "$ir" 2>/dev/null
# Capture FIRST, then match. Under `set -o pipefail` the pipeline takes java's exit status, and java
# exits non-zero precisely BECAUSE it overflowed — so `… | grep -q` reported failure exactly when
# the thing being looked for had happened. The check inverted itself.
bridge_out="$($V2 run-ir "$ir" 2>&1)"
# NO PIPE, and that is the fix rather than a style choice. `grep -q` exits the instant it matches,
# which closes the pipe while `printf` still has the rest of a StackOverflowError trace to write;
# `printf` then fails with EPIPE and, under `set -o pipefail`, takes the whole pipeline non-zero —
# so the check reports "did NOT overflow" exactly when the overflow DID happen. The comment above
# describes this same inversion being fixed once already, in its earlier shape; it came back through
# the pipe instead of through java's exit status.
#
# It cannot be caught on macOS: the trace fits the 64 KB pipe buffer there, so `printf` completes
# before `grep` exits and the pipeline is clean. On Linux CI the trace is larger than the buffer.
# Reproduced deliberately — marker FIRST then 300 KB of filler inverts it, marker LAST does not.
if grep -q StackOverflowError <<<"$bridge_out"; then
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
