#!/usr/bin/env bash
#
# The effects fixtures, run by something other than a person remembering to.
#
# WHY THIS FILE EXISTS. Measured 2026-08-09: `v3/tests/effects/` holds 15 fixtures and **no gate
# reads that directory** — not `exec-gate.sh`, not `front-gate.sh`, not the workflow. They were
# written alongside the effect work and then exercised only by hand. A suite nobody runs is worse
# than a red one: a red suite at least says something. This repository has paid for that twice
# already — `@scalascript/control-direct` sat unrun for three weeks while eleven BUGS entries waited
# for a signal they could never receive.
#
# WHY NOT `v3/tests/front/`. That gate is a DIFFERENTIAL: it runs each fixture on the executor AND
# on the v2 bridge and requires identical output. `BridgeV2` has no case for `Instr.Handle`,
# `Instr.Perform` or `Instr.Resume` — effects are an executor-lane feature — so an effects fixture
# placed there reports the bridge printing nothing, which is true and useless. Measured the day this
# was written: `handle-return` gave `executor [List(11, 21, 12, 22)] bridge []`.
#
# So this gate runs ONE lane and says so. When the bridge grows effects, the fixtures move.
#
# CONTRACT, the same one `front-gate.sh` uses: a fixture with an `.expected` must RUN and match; a
# fixture without one must be REFUSED, with a position. Red in both directions.
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
trap 'rm -f "$ERRF"' EXIT

for f in "$DIR"/*.ssc; do
  [ -e "$f" ] || continue
  name="$(basename "$f" .ssc)"
  exp="$DIR/$name.expected"
  ran=$((ran + 1))
  got="$(timeout 180 $SSC3 run "$f" 2>"$ERRF")"
  rc=$?
  if [ -f "$exp" ]; then
    if [ $rc -ne 0 ]; then
      printf '  FAIL %-26s refused: %s\n' "$name" "$(grep -m1 '^ssc3:' "$ERRF" | cut -c1-90)"
      fails=$((fails + 1))
    elif [ "$got" != "$(cat "$exp")" ]; then
      printf '  FAIL %-26s got [%s] expected [%s]\n' "$name" \
             "$(printf '%s' "$got" | tr '\n' '/')" "$(tr '\n' '/' < "$exp")"
      fails=$((fails + 1))
    else
      printf '  ok   %-26s -> %s\n' "$name" "$(printf '%s' "$got" | tr '\n' '/')"
    fi
  else
    # NO `.expected` MEANS "must be refused", and the refusal must carry a POSITION — an
    # unpositioned failure tells the reader nothing they can act on, which is the same rule
    # `corpus-report.sh` uses to separate an honest refusal from a crash.
    if [ $rc -eq 0 ]; then
      printf '  FAIL %-26s was ACCEPTED — add an .expected, or make the front refuse it\n' "$name"
      fails=$((fails + 1))
    elif ! grep -qE ':[0-9]+:[0-9]+:' "$ERRF" &&
         ! printf '%s\n' "${KNOWN_UNPOSITIONED[@]}" | grep -qx "$name"; then
      printf '  FAIL %-26s refused without a source position: %s\n' "$name" \
             "$(grep -m1 -v '^\s*$' "$ERRF" | cut -c1-70)"
      fails=$((fails + 1))
    else
      printf '  ok   %-26s refused: %s\n' "$name" "$(grep -m1 '^ssc3:' "$ERRF" | cut -c1-70)"
    fi
  fi
done

echo "── effects: $ran fixture(s), executor lane ──────────────────────────────"
if [ $fails -ne 0 ]; then
  echo "effects-gate: FAIL ($fails)" >&2
  exit 1
fi
echo "effects-gate: OK ($ran fixtures)"
