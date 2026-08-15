#!/usr/bin/env bash
#
# The effects fixtures, run by something other than a person remembering to — on BOTH lanes.
#
# WHY THIS FILE EXISTS. Measured 2026-08-09: `v3/tests/effects/` holds 15 fixtures and **no gate
# reads that directory** — not `exec-gate.sh`, not `front-gate.sh`, not the workflow. They were
# written alongside the effect work and then exercised only by hand. A suite nobody runs is worse
# than a red one: a red suite at least says something. This repository has paid for that twice
# already — `@scalascript/control-direct` sat unrun for three weeks while eleven BUGS entries waited
# for a signal they could never receive.
#
# WHY IT IS NOW A DIFFERENTIAL. It used to run ONE lane and say so, because `BridgeV2` had no case
# for `Instr.Handle`, `Instr.Perform` or `Instr.Resume` and an effects fixture reported the bridge
# printing nothing — true and useless. The bridge grew them (SSC3-3, `v3/src/BridgeV2.scala`
# §effects), so this gate now runs both and requires one answer, which is the only check that can
# catch the failure effects actually have: a WRONG value rather than a refusal. `multi-shot`
# measured 8 where the answer is 12 on the executor once, from one shared frame; the bridge has the
# same trap and a different implementation of it.
#
# THE THREE-WAY IS THE POINT. Executor, bridge and `.expected` must all agree. Two lanes agreeing on
# a wrong answer is a real outcome — they share the lowering — so the recorded value is kept as the
# third opinion rather than dropped now that there are two lanes.
#
# CONTRACT, the same one `front-gate.sh` uses: a fixture with an `.expected` must RUN on both lanes
# and match; a fixture without one must be REFUSED on both, with a position on the executor and a
# one-line `ssc3:` refusal on the bridge — not a Java stack trace, which is what an untranslated
# construct used to produce (BUGS.md v3-bridge-lazylist-crashes-with-a-java-stack-trace).
#
# Usage: v3/effects-gate.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SSC3="v3/ssc3"
DIR="v3/tests/effects"

# ONE REFUSAL HAS NO POSITION, and it is declared rather than excused. `perform-no-handler` fails
# at RUN time — `no handler for effect operation …` — and that message carries no `line:col`,
# because the executor raises it from a frame that no longer knows where the perform was written.
# A refusal without a position is what `corpus-report.sh` classifies as a CRASH, so this is a real
# if small defect (BUGS.md v3-no-handler-error-has-no-position); declaring it keeps the gate honest
# about the difference instead of weakening the rule for every fixture.
declare -a KNOWN_UNPOSITIONED=(perform-no-handler)

fails=0
ran=0
ERRF="$(mktemp)"
BERRF="$(mktemp)"
trap 'rm -f "$ERRF" "$BERRF"' EXIT

flat() { printf '%s' "$1" | tr '\n' '/'; }

for f in "$DIR"/*.ssc; do
  [ -e "$f" ] || continue
  name="$(basename "$f" .ssc)"
  exp="$DIR/$name.expected"
  ran=$((ran + 1))
  got="$(timeout 180 $SSC3 run "$f" 2>"$ERRF")"
  rc=$?
  via="$(timeout 300 $SSC3 run --bridge "$f" 2>"$BERRF")"
  brc=$?
  if [ -f "$exp" ]; then
    want="$(cat "$exp")"
    if [ $rc -ne 0 ]; then
      printf '  FAIL %-26s executor refused: %s\n' "$name" "$(grep -m1 '^ssc3:' "$ERRF" | cut -c1-90)"
      fails=$((fails + 1))
    elif [ $brc -ne 0 ]; then
      printf '  FAIL %-26s bridge refused: %s\n' "$name" "$(grep -m1 '^ssc3:' "$BERRF" | cut -c1-90)"
      fails=$((fails + 1))
    elif [ "$got" != "$want" ] || [ "$via" != "$want" ]; then
      printf '  FAIL %-26s executor [%s] bridge [%s] expected [%s]\n' \
             "$name" "$(flat "$got")" "$(flat "$via")" "$(flat "$want")"
      fails=$((fails + 1))
    else
      printf '  ok   %-26s -> %s  (both lanes agree)\n' "$name" "$(flat "$got")"
    fi
  else
    # NO `.expected` MEANS "must be refused", and the refusal must carry a POSITION — an
    # unpositioned failure tells the reader nothing they can act on, which is the same rule
    # `corpus-report.sh` uses to separate an honest refusal from a crash.
    if [ $rc -eq 0 ]; then
      printf '  FAIL %-26s was ACCEPTED by the executor — add an .expected, or make the front refuse it\n' "$name"
      fails=$((fails + 1))
    elif [ $brc -eq 0 ]; then
      printf '  FAIL %-26s was ACCEPTED by the BRIDGE while the executor refused it: [%s]\n' \
             "$name" "$(flat "$via")"
      fails=$((fails + 1))
    elif ! grep -qE ':[0-9]+:[0-9]+:' "$ERRF" &&
         ! printf '%s\n' "${KNOWN_UNPOSITIONED[@]}" | grep -qx "$name"; then
      printf '  FAIL %-26s refused without a source position: %s\n' "$name" \
             "$(grep -m1 -v '^\s*$' "$ERRF" | cut -c1-70)"
      fails=$((fails + 1))
    elif ! grep -q '^ssc3:' "$BERRF"; then
      # A Java stack trace is not a refusal. The bridge has a sentence for every construct it does
      # not translate, and this is what holds it to that.
      printf '  FAIL %-26s bridge failed without an `ssc3:` refusal: %s\n' "$name" \
             "$(grep -m1 -v '^\s*$' "$BERRF" | cut -c1-70)"
      fails=$((fails + 1))
    else
      printf '  ok   %-26s refused on both: %s\n' "$name" "$(grep -m1 '^ssc3:' "$ERRF" | cut -c1-60)"
    fi
  fi
done

echo "── effects: $ran fixture(s), executor AND v2 bridge ──────────────────────"
if [ $fails -ne 0 ]; then
  echo "effects-gate: FAIL ($fails)" >&2
  exit 1
fi
echo "effects-gate: OK ($ran fixtures, both lanes)"
