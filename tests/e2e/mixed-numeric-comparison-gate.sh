#!/usr/bin/env bash
# Mixed Int/Double comparison answers the same on every lane — and that answer is Scala's.
#
# THE ORACLE IS `run-jvm`, NOT `--v1`. Every other cross-lane gate in this repo compares against the
# interpreter, because a census established it matches real Scala on the question being asked. Here
# the interpreter was one of the WRONG lanes: it ordered correctly (`1 < 2.0` true) and compared for
# equality without widening (`1 == 1.0` false). A gate written the usual way would have frozen the
# defect as the reference. `run-jvm` compiles to real Scala and runs it, so it cannot be wrong about
# what Scala does — it is slower, and that is what it buys.
#
# WHAT WAS WRONG (measured 2026-08-12, fresh build, five lanes):
#
#                       1 == 1.0   1 != 1.0   1.0 == 1   case 1 <- 1.0
#     jvm (oracle)        true      false       true       one
#     js                  true      false       true       one
#     interp             FALSE      TRUE       FALSE       other
#     native             FALSE      TRUE       FALSE       other
#     bytecode           FALSE      TRUE       FALSE       other
#
# Ordering was right on all five. Only equality diverged, and it diverged in the direction that
# reports NOTHING: a wrong answer with a zero exit code, taking the other branch.
#
# THREE SITES, and each was the structural default of a match:
#   * interp `infix2Eq` — listed every mixed numeric pair (BigInt/Int, Decimal/Int, Decimal/BigInt,
#     List/Vector) EXCEPT Int/Double, so that pair fell to `lhs == rhs` where `IntV(1)` and
#     `DoubleV(1.0)` are different objects. Its twin `infix2Ord` had widened Int/Double all along.
#   * interp PATTERNS — `scrutV == litV`, in SIX copies across the fast paths and the general
#     matcher. Fixing `infix2Eq` alone left `lit(1.0)` answering `other` on this lane while every
#     other lane said `one`; that is row 8, and it is how the incomplete fix was caught.
#   * v2 `__eq__` — was `BoolV(a(0) == a(1))`. `==` lowers to this prim rather than `__arith__`
#     (ssc1-lower :2832), so `arithOp`'s correct `(IntV, FloatV)` arm was never consulted. On v2 the
#     comparison and the literal pattern are the SAME prim, which is why one edit fixed both there.
#
# THE LITERAL-PATTERN ROW IS NOT DECORATION. `case 1 =>` compiles to `__eq__(scrutinee, 1)` on v2
# and reaches the same equality on interp, so widening changes pattern matching too. That is
# correct — the oracle answers `one` for a Double `1.0` — but it is the row that would catch a fix
# applied to the comparison operators alone, which is the obvious way to fix this and is incomplete.
#
# BIGINT/BIGDECIMAL ARE NOT IN THIS GATE, and the reason is worth the line. I froze
# `BigInt(1) == 1.0` here as `false`, reasoning that BigInt.equals rejects a Double. The oracle
# answered `true` and this gate REFUSED TO JUDGE rather than charge my mistake to the other lanes —
# which is exactly what the refuse-to-judge branch is for. Those pairs are wrong on interp and
# native too, but they are a different pair set needing arms in more places (and native does not
# bind `BigDecimal` at all), so they are filed as their own entry instead of widened on the way
# past. Do not add them here without measuring the oracle first.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC="$ROOT/bin/ssc"
TOOLS="$ROOT/bin/ssc-tools"
for l in "$SSC" "$TOOLS"; do
  [[ -x $l ]] || { echo "mixed-numeric-comparison: no launcher at $l — run ./install.sh --dev" >&2; exit 2; }
done

tmp=$(mktemp -d "${TMPDIR:-/tmp}/mixcmp.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat > "$tmp/cmp.ssc" <<'EOF'
def lit(x: Any): String =
  x match
    case 1 => "one"
    case _ => "other"

def main(): Unit =
  println((1 < 2.0).toString)
  println((1 <= 1.0).toString)
  println((2.0 > 1).toString)
  println((1 == 1.0).toString)
  println((1 != 1.0).toString)
  println((1.0 == 1).toString)
  println((2 == 2.5).toString)
  println(lit(1.0))
  println(lit(2))
  println((1 == 1).toString)
  println(("a" == "a").toString)
EOF

ROWS=11

run() { # run <label> <cmd...>
  local label=$1; shift
  if ! "$@" "$tmp/cmp.ssc" > "$tmp/$label.raw" 2>"$tmp/$label.err"; then
    echo "mixed-numeric-comparison: lane $label failed to run" >&2; cat "$tmp/$label.err" >&2; exit 1
  fi
  tail -"$ROWS" "$tmp/$label.raw" > "$tmp/$label.txt"
}

run jvm      "$TOOLS" run-jvm
run int      "$TOOLS" run --v1
run native   "$SSC"   run
run bytecode "$TOOLS" run --bytecode
run js       "$TOOLS" run-js

# The oracle must say what this gate was written against. If real Scala ever disagrees with the
# frozen row, the gate refuses to judge rather than charging the difference to the other lanes.
expected_jvm=$'true\ntrue\ntrue\ntrue\nfalse\ntrue\nfalse\none\nother\ntrue\ntrue'
if [[ "$(cat "$tmp/jvm.txt")" != "$expected_jvm" ]]; then
  echo "mixed-numeric-comparison: the ORACLE lane (run-jvm) does not match the frozen row." >&2
  echo "  Real Scala is the authority here — if this changed, the FROZEN ROW is what to re-derive," >&2
  echo "  not the other lanes. Refusing to judge." >&2
  echo "  expected: $(echo "$expected_jvm" | tr '\n' '/')" >&2
  echo "  got:      $(tr '\n' '/' < "$tmp/jvm.txt")" >&2
  exit 2
fi

fail=0
for lane in int native bytecode js; do
  if ! diff -u "$tmp/jvm.txt" "$tmp/$lane.txt" > "$tmp/$lane.diff"; then
    echo "mixed-numeric-comparison: $lane disagrees with run-jvm (real Scala)" >&2
    echo "  (-) jvm   (+) $lane" >&2
    cat "$tmp/$lane.diff" >&2
    echo "  rows: 1-3 ordering · 4-7 equality · 8-9 literal pattern · 10-11 unmixed controls" >&2
    echo "  Read WHICH rows moved — on interp the two halves have separate code:" >&2
    echo "    rows 4-7 wrong, row 8 right  -> infix2Eq lost its Int/Double arms" >&2
    echo "    rows 4-7 right, row 8 wrong  -> a literal-pattern compare stopped going through" >&2
    echo "                                    litMatches (there are SIX call sites; this exact" >&2
    echo "                                    shape was measured with one of them reverted)" >&2
    echo "  On native/bytecode both halves are the ONE __eq__ prim, so they move together there." >&2
    fail=1
  fi
done

[[ $fail -eq 0 ]] || exit 1
echo "mixed-numeric-comparison: ok — int, native, bytecode and js all agree with run-jvm on eleven rows"
