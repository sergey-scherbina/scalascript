#!/usr/bin/env bash
#
# f-u0-gap-gate — F still cannot lower `std/ui/content.ssc`, and the two files that fail WITH it
# fail only because they import it.
#
#   ./tests/e2e/f-u0-gap-gate.sh
#
# THIS GATE PINS A DEFECT and goes RED when it is fixed, on purpose.
# tests/BUGS.md `f-u0-reduction-2026-08-15`.
#
# WHY IT EXISTS AT ALL, given `scripts/f-gap-census` already reports this. That census is an
# INSTRUMENT and says so in its own header: when F cannot lower a file it falls back to the
# reference front SILENTLY — the program runs, the exit code is 0, and every output gate stays
# green. So nothing fails when F's coverage shrinks, and nothing would have said if this bucket grew
# or moved. An instrument you have to remember to run is not a guard.
#
# IT ASSERTS THE STRUCTURE, NOT A COUNT — and that structure is the entry's main finding. The bucket
# reads as "3 files" and is ONE subject plus two casualties: `markdown-toolkit-links.ssc` is 38 lines
# with four calls and NO placeholder at all, and fails purely because it imports the subject. A gate
# on the number 3 would be satisfied by three unrelated failures; this one names the subject and
# requires the other two to carry the SAME reason, which is what makes them collateral rather than
# three defects.
#
# THE REASON STRING IS ASSERTED, not just the GAP verdict. `GAP` alone would stay green if F started
# refusing these files for a different cause — the bucket would look unchanged while having become a
# different bug. `__u0` is what names the mechanism.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ssc="${SSC:-$ROOT/bin/ssc}"

SUBJECT="std/ui/content.ssc"
CASUALTIES="examples/markdown-toolkit-links.ssc examples/content-live-rows.ssc"
REASON="(global __u0) is neither a top-level def nor an @-cell"

fails=0
report() {  # $1 = file → the front-report line for it, or nothing
  timeout 300 "$ssc" info --front-report "$1" 2>/dev/null | grep -F "$1" | tail -1
}

for f in $SUBJECT $CASUALTIES; do
  if [ ! -f "$f" ]; then
    echo "f-u0-gap: SKIP — $f is not in this checkout."
    echo "  A SKIP, not a pass: the subject is absent, so the check cannot answer."
    exit 0
  fi
done

line="$(report "$SUBJECT")"
case "$line" in
  *GAP*"$REASON"*)
    echo "  ok   subject $SUBJECT: GAP, $REASON" ;;
  *GAP*)
    echo "f-u0-gap: FAIL — $SUBJECT still GAPs, but for a DIFFERENT reason." >&2
    echo "  The bucket would look unchanged while having become a different bug." >&2
    echo "  want: $REASON" >&2
    echo "  got:  $line" >&2
    fails=$((fails + 1)) ;;
  *)
    echo "  ✓ F now lowers $SUBJECT — THE GAP IS CLOSED." >&2
    echo "    That is what this gate exists to notice. Close tests/BUGS.md" >&2
    echo "    f-u0-reduction-2026-08-15 and delete this gate; do not relax the assertion." >&2
    echo "    got: ${line:-<no front-report line>}" >&2
    fails=$((fails + 1)) ;;
esac

# THE CASUALTIES CARRY THE SAME REASON, which is what makes them collateral rather than defects.
# If one of them ever fails for its OWN reason the bucket has stopped being single-cause, and that
# is a finding rather than noise — so it is a failure here, with the difference printed.
for f in $CASUALTIES; do
  l="$(report "$f")"
  case "$l" in
    *GAP*"$REASON"*) echo "  ok   casualty $f: same reason, so collateral of the subject" ;;
    *GAP*)
      echo "f-u0-gap: FAIL — $f GAPs for a DIFFERENT reason than the subject." >&2
      echo "  The bucket is no longer one cause with two casualties; it is two defects." >&2
      echo "  got: $l" >&2
      fails=$((fails + 1)) ;;
    *)
      echo "  ✓ $f no longer GAPs while the subject still does — the import is no longer" >&2
      echo "    carrying the failure. Re-read the entry's 'one subject, two casualties' finding." >&2
      fails=$((fails + 1)) ;;
  esac
done

if [ "$fails" -ne 0 ]; then
  echo "f-u0-gap-gate: FAIL ($fails)" >&2
  exit 1
fi
echo "f-u0-gap-gate: OK (gap still present; one subject, two casualties, one reason)"
