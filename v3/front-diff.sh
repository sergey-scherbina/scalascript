#!/usr/bin/env bash
# v3 SSC3-11 — the gate that will decide the UniML front swap.
#
# `v3/specs/40-front-on-uniml.md` §7: the UniML front is adopted when, for every fixture and every
# corpus case v3 compiles, BOTH fronts print the same `Ast` in canonical form. Comparing parsers any
# other way means comparing them through the lowering, the verifier and a backend, where a
# difference arrives far from its cause — and a compensating PAIR of differences arrives not at all.
#
# WHAT THIS GATE IS TODAY, said plainly: there is ONE front. It therefore compares nothing, and
# saying "GREEN" on that basis would be the vacuous-gate shape this repository has already paid for
# twice. So it does the two things it honestly can:
#
#   1. TOTALITY — every fixture prints an `Ast`, non-empty, exit 0. A printer with a missing arm is
#      a compile error in Scala, but `floatText` on a NaN or a name with a quote in it is not, and
#      those reach this gate rather than a user.
#   2. A SELF-TEST of the COMPARATOR — one fixture's output is mutated by one token and the
#      comparison must report a difference. That is the doctrine applied before the thing it guards
#      exists: a comparator nobody has watched fail is a hypothesis, and it is much cheaper to find
#      out now than on the day the second front lands and everything is green for the wrong reason.
#
# When `Front.available` grows a second entry this file starts comparing front to front with no
# edit — that is the point of listing the fronts rather than hard-coding two names.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SSC3="$ROOT/v3/ssc3"

fail=0
ran=0
agree=0
disagree=0

# The agreement FLOOR. It may rise in any commit and fall in none — the same non-regression rule the
# corpus number carries, and for the same reason: a gate that is permanently red stops being read,
# and a gate with no floor lets the number slide back without anyone noticing.
FLOOR="${SSC3_FRONT_AGREE_FLOOR:-30}"

# The fronts the DRIVER says it can run. Asked rather than duplicated here: a list in two places is
# a list that disagrees with itself. The kernel knows one front; the driver knows whether the second
# one's classpath is present, which is a fact about the working tree rather than about the code.
fronts="$("$SSC3" fronts)"
nfronts="$(printf '%s\n' "$fronts" | grep -c .)"

echo "── fronts declared runnable: $(printf '%s' "$fronts" | tr '\n' ' ') ──"

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

for f in v3/tests/front/*.ssc; do
  name="$(basename "$f" .ssc)"
  # A fixture that is EXPECTED to be refused has no `.expected`; printing its Ast is not meaningful.
  [ -f "v3/tests/front/$name.expected" ] || continue
  ran=$((ran + 1))

  first=""
  for fr in $fronts; do
    out="$work/$name.$fr"
    if ! "$SSC3" ast "$f" "$fr" > "$out" 2>"$work/err"; then
      # The FIRST front is v3's own and must always print — a failure there is a real defect. A
      # second front refusing is expected work-in-progress and is counted, not failed.
      if [ "$fr" = "v3" ]; then
        echo "  FAIL $name — the v3 front could not print an Ast: $(head -1 "$work/err")"
        fail=1
      else
        echo "  refused $name — $(head -1 "$work/err" | sed 's/^ssc3: //' | cut -c1-70)"
        disagree=$((disagree + 1))
      fi
      continue
    fi
    if [ ! -s "$out" ]; then
      echo "  FAIL $name — front '$fr' printed NOTHING at exit 0"
      fail=1
      continue
    fi
    if [ -z "$first" ]; then
      first="$out"
    elif ! diff -q "$first" "$out" >/dev/null; then
      echo "  diff $name"
      diff "$first" "$out" | head -4 | sed 's/^/         /'
      disagree=$((disagree + 1))
    else
      agree=$((agree + 1))
    fi
  done
done

echo
echo "── self-test: the comparator must SEE a one-token difference ─────────────"
probe="$(ls v3/tests/front/*.ssc | head -1)"
"$SSC3" ast "$probe" v3 > "$work/a" 2>/dev/null
sed 's/(int /(intX /' "$work/a" > "$work/b"
if diff -q "$work/a" "$work/b" >/dev/null; then
  # No integer literal in the probe: mutate something every Ast has instead.
  sed 's/(program/(programX/' "$work/a" > "$work/b"
fi
if diff -q "$work/a" "$work/b" >/dev/null; then
  echo "  FAIL the mutation changed nothing — the self-test proves nothing"
  fail=1
elif diff "$work/a" "$work/b" >/dev/null 2>&1; then
  echo "  FAIL the comparator reported no difference on a mutated file"
  fail=1
else
  echo "  ok   a one-token mutation is reported as a difference"
fi

echo
if [ "$ran" -eq 0 ]; then echo "== v3 SSC3-11 gate: NO CASES RAN =="; exit 2; fi
if [ "$nfronts" -lt 2 ]; then
  echo "  note ONE front is runnable, so nothing was COMPARED. This gate proves the printer is"
  echo "       total over $ran fixture(s) and that the comparator can see a difference — not that"
  echo "       two fronts agree. It starts comparing with no edit when Front.available grows."
fi
if [ "$nfronts" -ge 2 ]; then
  echo
  echo "  fronts AGREE on $agree of $ran fixture(s); $disagree still differ or are refused (floor $FLOOR)"
  if [ "$agree" -lt "$FLOOR" ]; then
    echo "  FAIL agreement REGRESSED below the floor — raise it only after a measurement, never before"
    fail=1
  fi
fi
[ "$fail" = 0 ] && echo "== v3 SSC3-11 gate: GREEN ($ran fixture(s), $nfronts front(s), agree $agree) ==" \
                || echo "== v3 SSC3-11 gate: RED =="
[ "$fail" = 0 ]
