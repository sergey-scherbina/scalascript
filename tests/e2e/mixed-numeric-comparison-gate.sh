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
# (2026-08-12), as rows 12-19 of the SAME source: 12-16 the ones every lane gets right, 17-19 the
# ones still wrong somewhere with each lane's current answer FROZEN so the gate speaks up when one
# of them is fixed. Whatever is added here, measure the oracle first — that habit is what caught the
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
  println((BigDecimal(1) == 1.0).toString)
EOF

# 11 comparison rows, then 5 wide-type rows, then 3 that are still wrong somewhere. ONE file and
# one process per lane, not three: this check was 166.4 s and 16% of the smoke run against a
# baseline of 8% — the most expensive check in the suite and the reason the push path went OVER
# BUDGET at 1004.5 s of 963 s. It grew because the wide-type rows arrived as two extra SOURCES,
# and every source costs another launch on all five lanes, one of which compiles real Scala.
# Rows are cheap; processes are not.
ROWS=19
CMP_TO=11      # 1-11   the Int/Double comparisons and the literal-pattern rows
BIG_FROM=12    # 12-16  wide types, every lane agrees with the oracle
BIG_TO=16
GAP_ROWS_END=19
GAP_FROM=17    # 17-19  wide types, some lane still disagrees — frozen per lane below

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
# Rows 12-19 of `cmp.ssc`, not their own sources. They are still two GROUPS and the split is the
# point: 12-16 are rows where every lane now answers what real Scala answers, 17-19 are rows where
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

expected_big=$'true\nfalse\ntrue\ntrue\ntrue'
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
    echo "  Row 3 or 4 failing on native/bytecode means the v2 front stopped binding \`BigDecimal\`," >&2
    echo "  or \`dec.parse\` stopped accepting a non-String — those two are what made \`Decimal(1)\`" >&2
    echo "  die with \"expects String, got 1\" while interp answered it." >&2
    fail=1
  fi
done

# The frozen KNOWN-WRONG block. Each lane's current answer, not the oracle's — read the comment on
# rows 17-19 above. When one of these starts matching jvm, this gate FAILS on purpose: move that
# row up into the 12-16 group (widen BIG_TO, raise GAP_FROM) and delete its line here.
gap_expect() { case $1 in
  jvm|js)             printf 'true\ntrue\ntrue' ;;
  native|bytecode)    printf 'true\ntrue\nfalse' ;;
  int)                printf 'false\nfalse\nfalse' ;;
esac; }
for lane in jvm js native bytecode int; do
  if [[ "$(slice "$lane" "$GAP_FROM" "$GAP_ROWS_END")" != "$(gap_expect "$lane")" ]]; then
    echo "mixed-numeric-comparison: the frozen KNOWN-GAP row moved on $lane" >&2
    echo "  frozen: $(gap_expect "$lane" | tr '\n' '/')" >&2
    echo "  now:    $(slice "$lane" "$GAP_FROM" "$GAP_ROWS_END" | tr '\n' '/')" >&2
    echo "  rows: 1 BigInt(1)==1.0 · 2 1.0==BigInt(1) · 3 BigDecimal(1)==1.0" >&2
    echo "  If it moved TOWARDS jvm this is a fix landing, not a regression: move the row into" >&2
    echo "  big-agree.ssc. Rows 1-2 on int are blocked on the interp \`infix2Eq\` arms; row 3 on" >&2
    echo "  native/bytecode is PortableDecimal declining to read a binary float as a decimal." >&2
    fail=1
  fi
done

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
echo "mixed-numeric-comparison: ok — int, native, bytecode and js all agree with run-jvm on eleven rows in a def, nine at top level and five on the wide numeric types (three more frozen as known gaps)"
