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
# BIGINT/BIGDECIMAL WERE NOT IN THIS GATE, and how they got here is worth keeping. They were left
# out because I froze `BigInt(1) == 1.0` as `false`, reasoning that BigInt.equals rejects a Double;
# the oracle answered `true` and this gate REFUSED TO JUDGE rather than charge my mistake to the
# other lanes — which is exactly what the refuse-to-judge branch is for. They are in now
# (2026-08-12), as rows 12-21 of the SAME source: 12-20 the ones every lane gets right, 21 the
# one still wrong somewhere with each lane's current answer FROZEN so the gate speaks up when it
# is fixed. Whatever is added here, measure the oracle first — that habit is what caught the
# original mistake.
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
  println((BigInt(1) == 1).toString)
  println((BigInt(2) == 1.0).toString)
  println((Decimal(1) == 1).toString)
  println((BigDecimal(1) == 1).toString)
  println((BigInt(2) == 2).toString)
  println((BigInt(1) == 1.0).toString)
  println((1.0 == BigInt(1)).toString)
  println((BigInt(9007199254740993L) == 9.007199254740992e15).toString)
  println((BigInt("123456789012345678901234567890") == 1.2345678901234568e29).toString)
  println((BigDecimal(1) == 1.0).toString)
  println((BigDecimal("0.1") == 0.1).toString)
  println((BigDecimal(1) < 2.0).toString)
  println((BigDecimal(2) < 1.0).toString)
  println((BigDecimal("0.1") <= 0.1).toString)
EOF

# 11 comparison rows, then 9 wide-type rows, then 1 that is still wrong somewhere. ONE file and
# one process per lane, not three: this check was 166.4 s and 16% of the smoke run against a
# baseline of 8% — the most expensive check in the suite and the reason the push path went OVER
# BUDGET at 1004.5 s of 963 s. It grew because the wide-type rows arrived as two extra SOURCES,
# and every source costs another launch on all five lanes, one of which compiles real Scala.
# Rows are cheap; processes are not.
ROWS=25
CMP_TO=11      # 1-11   the Int/Double comparisons and the literal-pattern rows
BIG_FROM=12    # 12-25  wide types, every lane agrees with the oracle
BIG_TO=25
# THE KNOWN-GAP BLOCK IS GONE, and rows 21-22 moved up into the group above — the file's own
# instruction for the day the frozen row starts matching jvm. `BigDecimal(1) == 1.0` answered
# `false` on native and bytecode and `true` on jvm/js/int; the owner took the contract decision on
# 2026-08-15 and the quiet lanes moved. (BUGS `bigdecimal-against-a-binary-float-is-a-contract-decision-nobody-has-taken`.)
#
# `BigDecimal("0.1") == 0.1` IS BACK, and the note that used to stand here explains why it left.
# Added on 2026-08-15 as the row a wrong implementation cannot fake, it took the JS lane down with
# `cannot mix Decimal and a fractional Number — convert explicitly`, so js's `true` on the integer
# row had never been agreement about the rule — `Number.isInteger(1.0)` is true and a fractional
# Double threw. The row was withdrawn, the divergence filed, and the guard is now fixed: comparison
# answers on every lane, arithmetic still refuses.

# ROW 19 IS THE ONE THIS GATE COULD NOT SEE BEFORE, and it is here because every other wide row uses
# a SMALL value. `BigInt(9007199254740993L) == 9.007199254740992e15` is 2^53+1 against the Double it
# rounds to: real Scala answers `false` (`BigInt.equals` checks `isValidDouble` first), while the
# obvious wrong implementation, `a.toDouble == b`, answers `true`. Every other row in this block
# agrees under BOTH implementations, so a rewrite to `toDouble` would have kept the gate green.
# ROW 20 IS THE SAME TEST PAST LONG RANGE, and it could not be written until today. This comment
# used to say the String spelling `BigInt("…")` dies with `i->big: not Int` on native and bytecode
# so it could not be a shared row; that was true when row 19 was added and was fixed hours later by
# 58bdeb4c1. RE-MEASURED on a toolchain built from a commit that CONTAINS the fix — the first
# reading was taken against a build 16 minutes older than it and repeated the old answer, which is
# the whole reason to check the build stamp before believing a lane. All five lanes answer `false`.
# Kept BESIDE row 19 rather than replacing it: row 19 is inside Long range and row 20 is not, so
# together they say the equality is right for a value the Long constructor can express and for one
# only the String constructor can.

# A SECOND source, and every difference from the first one is load-bearing. On 2026-08-12 the
# native front's checker refused
#
#     println(1 < 2.0)        TYPEERR: type mismatch in comparison: cannot unify Int: Int vs Float
#
# while ACCEPTING all three of these, measured on one binary:
#
#     println((1 < 2.0).toString)      -> true    a method call on the result
#     def main(): Unit =                          the same expression inside a def
#       println(1 < 2.0)               -> true
#     var a = 1; var b = 1.0; a < b    -> true    operands carrying type variables
#
# So the shape that fails is the comparison used DIRECTLY — printed, or bound to a top-level `val`
# — with both operands at CONCRETE numeric types. The eleven rows above miss it on two counts at
# once: they live in `def main` AND they wrap every expression in `.toString`. This gate was green
# on a front that refused the plainest thing a script can write.
#
# Both misses were found the same way: the first version of this block used `.toString` at top
# level, and the without-the-fix control passed. A gate that cannot reach the failing shape does
# not guard it, and the control is what says whether it reaches it.
cat > "$tmp/top.ssc" <<'EOF'
println(1 < 2.0)
println(1 <= 1.0)
println(2.0 > 1)
println(1 == 1.0)
println(1 != 1.0)
println(1.0 == 1)
println(2 == 2.5)
val guard = 1 < 2.0
println(guard)
println(1 == 1)
EOF
TOP_ROWS=9

run() { # run <label> <src> <rows> <cmd...>
  local label=$1 src=$2 rows=$3; shift 3
  if ! "$@" "$tmp/$src" > "$tmp/$label.raw" 2>"$tmp/$label.err"; then
    echo "mixed-numeric-comparison: lane $label failed to run" >&2; cat "$tmp/$label.err" >&2; exit 1
  fi
  tail -"$rows" "$tmp/$label.raw" > "$tmp/$label.txt"
}

run jvm      cmp.ssc "$ROWS" "$TOOLS" run-jvm
run int      cmp.ssc "$ROWS" "$TOOLS" run --v1
run native   cmp.ssc "$ROWS" "$SSC"   run
run bytecode cmp.ssc "$ROWS" "$TOOLS" run --bytecode
run js       cmp.ssc "$ROWS" "$TOOLS" run-js

# ── The WIDE numeric types ───────────────────────────────────────────────────────────────────────
# Rows 12-21 of `cmp.ssc`, not their own sources. They are still two GROUPS and the split is the
# point: 12-20 are rows where every lane now answers what real Scala answers, 21 is the row where
# one or more lanes still do not and each lane's CURRENT answer is frozen below. A known gap left
# unasserted is a gap nobody notices closing — freezing the wrong value makes the gate go red the
# day it is fixed, which is the only moment anyone wants to be told.
slice() { sed -n "$2,$3p" "$tmp/$1.txt"; }

run jvm-top      top.ssc "$TOP_ROWS" "$TOOLS" run-jvm
run int-top      top.ssc "$TOP_ROWS" "$TOOLS" run --v1
run native-top   top.ssc "$TOP_ROWS" "$SSC"   run
run bytecode-top top.ssc "$TOP_ROWS" "$TOOLS" run --bytecode
run js-top       top.ssc "$TOP_ROWS" "$TOOLS" run-js


# The oracle must say what this gate was written against. If real Scala ever disagrees with the
# frozen row, the gate refuses to judge rather than charging the difference to the other lanes.
expected_jvm=$'true\ntrue\ntrue\ntrue\nfalse\ntrue\nfalse\none\nother\ntrue\ntrue'
if [[ "$(slice jvm 1 "$CMP_TO")" != "$expected_jvm" ]]; then
  echo "mixed-numeric-comparison: the ORACLE lane (run-jvm) does not match the frozen row." >&2
  echo "  Real Scala is the authority here — if this changed, the FROZEN ROW is what to re-derive," >&2
  echo "  not the other lanes. Refusing to judge." >&2
  echo "  expected: $(echo "$expected_jvm" | tr '\n' '/')" >&2
  echo "  got:      $(slice jvm 1 "$CMP_TO" | tr '\n' '/')" >&2
  exit 2
fi

# Same discipline for the top-level source: freeze what real Scala answers, and refuse to judge if
# the oracle itself moves.
expected_top=$'true\ntrue\ntrue\ntrue\nfalse\ntrue\nfalse\ntrue\ntrue'
if [[ "$(cat "$tmp/jvm-top.txt")" != "$expected_top" ]]; then
  echo "mixed-numeric-comparison: the ORACLE lane (run-jvm) does not match the frozen TOP-LEVEL row." >&2
  echo "  expected: $(echo "$expected_top" | tr '\n' '/')" >&2
  echo "  got:      $(tr '\n' '/' < "$tmp/jvm-top.txt")" >&2
  exit 2
fi

# Row 21 — `BigDecimal(1) == 1.0` — joined this list on 2026-08-15 when the contract decision was
# taken and the two quiet lanes moved to the oracle's answer. It is the LAST entry, `true`.
# Rows 22-25 joined on 2026-08-15 when the Decimal-vs-Double decision was completed: `==` had
# landed and the ORDERING comparisons were still throwing, which is the decision half-applied.
# 22 `BigDecimal("0.1") == 0.1` — the row a hand-rolled exact compare fails.
# 23 `<` true, 24 `<` false, 25 `<=` true — and 25 is the one that pins the RULE: Scala
# compares the decimal's `toDouble`, so `"0.1" < 0.1` is FALSE while `<=` is true.
expected_big=$'true\nfalse\ntrue\ntrue\ntrue\ntrue\ntrue\nfalse\nfalse\ntrue\ntrue\ntrue\nfalse\ntrue'
if [[ "$(slice jvm "$BIG_FROM" "$BIG_TO")" != "$expected_big" ]]; then
  echo "mixed-numeric-comparison: the ORACLE lane (run-jvm) does not match the frozen WIDE row." >&2
  echo "  expected: $(echo "$expected_big" | tr '\n' '/')" >&2
  echo "  got:      $(slice jvm "$BIG_FROM" "$BIG_TO" | tr '\n' '/')" >&2
  exit 2
fi

fail=0
for lane in int native bytecode js; do
  if ! diff -u <(slice jvm "$BIG_FROM" "$BIG_TO") <(slice "$lane" "$BIG_FROM" "$BIG_TO") > "$tmp/$lane-big.diff"; then
    echo "mixed-numeric-comparison: $lane disagrees with run-jvm (real Scala) on the WIDE types" >&2
    echo "  (-) jvm   (+) $lane" >&2
    cat "$tmp/$lane-big.diff" >&2
    echo "  rows: 1 BigInt==Int · 2 BigInt!=Double · 3 Decimal(Int) · 4 BigDecimal(Int) · 5 control" >&2
    echo "        6 BigInt(1)==1.0 · 7 1.0==BigInt(1) · 8 2^53+1 · 9 the same past Long range — added 2026-08-13" >&2
    echo "  Rows 6-7 failing on int means the wide/Double arms in \`infix2Eq\` went away." >&2
    echo "  ROW 8 FAILING ALONE means they were rewritten as \`a.toDouble == b\`: that is right for" >&2
    echo "  every small value and wrong for one not exactly representable as a Double. Delegate to" >&2
    echo "  \`BigInt.equals\`/\`BigDecimal.equals\` instead — they carry the isValidDouble rule." >&2
    echo "  Row 3 or 4 failing on native/bytecode means the v2 front stopped binding \`BigDecimal\`," >&2
    echo "  or \`dec.parse\` stopped accepting a non-String — those two are what made \`Decimal(1)\`" >&2
    echo "  die with \"expects String, got 1\" while interp answered it." >&2
    fail=1
  fi
done

# THE FROZEN KNOWN-WRONG BLOCK USED TO BE HERE, and its deletion is the record that the decision was
# taken. It froze `BigDecimal(1) == 1.0` per lane — jvm/js/int `true`, native/bytecode `false` — and
# said, in its own failure message, that native's `false` was "a DESIGN position, not an omission"
# because `PortableDecimal` declines to read a binary float as a decimal in three places.
#
# MEASURED 2026-08-15, and that reading was wrong. All three of those refusals THROW; none of them
# answers `false`. The `false` came from a structural `case _ => false` in `DecimalV.equals` and from
# `eqWidening`'s `l == r` default — a fall-through, not a position. And the module already ALLOWS
# the other direction: `(d: DecimalV, "toDouble")` converts through the very `toJava` that refuses
# Double input. The line it actually draws is: refuse where inexactness would be CAPTURED into a
# stored decimal, answer where it is only OBSERVED. `==` yields a Boolean and stores nothing.
#
# The owner decided on that basis; the two quiet lanes moved and now answer what the oracle answers,
# which is why rows 21-22 sit in the 12-22 group above and this block is gone rather than edited.

for lane in int-top native-top bytecode-top js-top; do
  if ! diff -u "$tmp/jvm-top.txt" "$tmp/$lane.txt" > "$tmp/$lane.diff"; then
    echo "mixed-numeric-comparison: $lane disagrees with run-jvm (real Scala) at TOP LEVEL" >&2
    echo "  (-) jvm   (+) $lane" >&2
    cat "$tmp/$lane.diff" >&2
    echo "  These are the same expressions as the eleven rows above, moved out of \`def main\`." >&2
    echo "  If this block fails while the block below passes, the defect is in a FRONT that types" >&2
    echo "  top-level statements differently from def bodies — not in either runtime. That is" >&2
    echo "  exactly how the native checker refused \`1 < 2.0\` while this gate was green." >&2
    fail=1
  fi
done
for lane in int native bytecode js; do
  if ! diff -u <(slice jvm 1 "$CMP_TO") <(slice "$lane" 1 "$CMP_TO") > "$tmp/$lane.diff"; then
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
# COUNTED, NOT SPELLED. This line has been wrong twice in one day: it said "nine … (one more frozen
# as a known gap)" after the frozen gap was gone, and "ten" after four rows were added. A summary a
# reader trusts and an author retypes drifts by construction, so the numbers come from the same
# constants the assertions use.
echo "mixed-numeric-comparison: ok — int, native, bytecode and js all agree with run-jvm on $CMP_TO rows in a def, $TOP_ROWS at top level and $(( BIG_TO - BIG_FROM + 1 )) on the wide numeric types"
