#!/usr/bin/env bash
#
# f-at-bind-pattern-gate — `case q @ Ctor(…) =>` binds the whole matched value AND destructures it,
# and the arms after it are unaffected.
#
# THE DEFECT, measured 2026-08-14 on F. There was no rule for an @-bind arm at all. `parseArm` fell
# through to `parseConsArm`, which reads `h :: t` positionally, so the pattern's `_`s landed in
# EXPRESSION position and F reported `unbound global: (global _)` — a name that appears nowhere in
# the user's source, usually attributed to a file three imports away from the one with the pattern.
# That single message was the whole of `std/parsing/recovery.ssc`'s and
# `examples/dsl-sql-recovery.ssc`'s remaining GAP.
#
# IT IS THE F TWIN of `v21-native-sql-recovery-parser-sentinel`, which was the SAME three
# `case ok @ ParseOk(_, _, _)` lines on the other front, fixed in `1bf9c7c06` on 2026-07-11. A
# construct fixed on one front and never checked on the other is the recurring shape here.
#
# THE ROWS THAT MATTER MOST ARE THE ONES AFTER THE BIND. `bindScrut` renames the synthetic `__m`
# scrutinee slot, and `parseArmBody` hands the same menv to the arms that FOLLOW — so a fix that
# threads the renamed env down would leave later arms with no `__m`: a later `case other =>` would
# silently stop binding, and `genScrut`'s `lookup("__m", menv)` would emit `(local -1)`. Neither
# would be visible in the arm that carries the `@`. `later-ctor-arm`, `later-binder-arm` and
# `later-wildcard-arm` are those checks, and they are why this gate has more control rows than
# defect rows.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/at-bind.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── an @-bind pattern binds the whole value and leaves the other arms alone"
ssc_usable_or_skip f-at-bind-pattern-gate "$ssc"

DECLS='sealed trait T
case class A(x: Int, y: Int) extends T
case class B(x: Int) extends T
case object C extends T
case class D(x: Int) extends T
'

run_front() { # $1 front (legacy|F), $2 file → first line of output (stderr folded in)
  if [[ "$1" == legacy ]]; then
    SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$2" 2>&1 | head -1
  else
    SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$2" 2>&1 | head -1
  fi
}

# $1 name, $2 expected first line, $3 body (the shared declarations are prepended) — BOTH fronts.
# The reference front is the control that the source is well formed: a row red on both is a bad row.
both() {
  local name=$1 want=$2 src=$3 f r
  printf '%s%s\n' "$DECLS" "$src" > "$sandbox/$name.ssc"
  r=$(run_front legacy "$sandbox/$name.ssc")
  f=$(run_front F "$sandbox/$name.ssc")
  if [[ "$r" == "$want" && "$f" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: reference='$r' F='$f', wanted '$want' from both"
    fails=$((fails + 1))
  fi
}

# ── the bind itself ──────────────────────────────────────────────────────────────────────────────

# The corpus shape: every field discarded, the whole value used.
both at-bind-whole-value 4 'def f(t: T): Int = t match { case q @ A(_, _) => q.x case B(x) => x case _ => 0 }
def main(): Unit = println(f(A(4, 5)))'

# Fields AND the whole value in one body — the two bindings must not shadow each other.
both at-bind-fields-and-whole 4 'def f(t: T): Int = t match { case q @ A(a, b) => a + b + q.x case _ => 0 }
def main(): Unit = println(f(A(1, 2)))'

# A nullary constructor: no parameter list follows the tag, so arity is 0.
both at-bind-case-object c9 'def f(t: T): String = t match { case q @ C => "c9" case _ => "no" }
def main(): Unit = println(f(C))'

# ── the arms AFTER the bind, which is where a careless fix breaks things ─────────────────────────

both later-ctor-arm 9 'def f(t: T): Int = t match { case q @ A(_, _) => q.x case B(x) => x case _ => 0 }
def main(): Unit = println(f(B(9)))'

both later-binder-arm 'oB(9)' 'def f(t: T): String = t match { case q @ A(_, _) => "a" + q.x case other => "o" + other }
def main(): Unit = println(f(B(9)))'

both later-wildcard-arm 0 'def f(t: T): Int = t match { case q @ A(_, _) => q.x case B(x) => x case _ => 0 }
def main(): Unit = println(f(C))'

# ── THE SECOND RESOLVER ──────────────────────────────────────────────────────────────────────────
#
# Which resolver a match uses is decided by its FIRST pattern (parseMatchArms): a ctor-first match
# goes to parseCtorMatch/parseArm, a var- or scalar-first match to parseGenMatch/parseGenArm. Every
# row ABOVE matches on a sealed trait and reaches only the first. The rows here reach the second, and
# they are not decoration: with no @-bind rule there, `ok` was read as a catch-all VAR arm and
# `POk(_, _, _) => body` became the arm's BODY, so the match evaluated to a lambda and F printed
# `<closure>` — a silent wrong answer, where the ctor path merely declined. The first version of this
# fix shipped a green eleven-row gate that never executed the broken function.

# The @-bind is the FIRST arm, so the whole match routes to the ordered resolver.
both gen-at-bind-first-arm errbad1 'case class Perr(m: String, p: Int)
case class PE(e: Perr)
case class POk(v: Int, r: String, p: Int)
def run(n: Int): Any = if n > 0 then POk(n, "r", 0) else PE(Perr("bad", 1))
def f(n: Int): String =
  run(n) match
    case ok @ POk(_, _, _) => "ok"
    case PE(e) => "err" + e.m + e.p
def main(): Unit = println(f(0))'

# The @-bind reached through the ordered resolver, and the whole value actually USED.
both gen-at-bind-whole-value ok7 'case class POk(v: Int, r: String, p: Int)
case class PE(m: String)
def run(n: Int): Any = if n > 0 then POk(7, "r", 0) else PE("bad")
def f(n: Int): String =
  run(n) match
    case ok @ POk(_, _, _) => "ok" + ok.v
    case PE(m) => m
def main(): Unit = println(f(1))'

# An ordered match that starts with a scalar arm and carries an @-bind after it — the arms following
# the bind must still be built on the un-renamed env, or genScrut emits `(local -1)`.
both gen-at-bind-after-scalar zz 'case class POk(v: Int, r: String, p: Int)
def classify(v: Any): String =
  v match
    case 1 => "one"
    case ok @ POk(_, _, _) => "ok" + ok.v
    case other => "zz"
def main(): Unit = println(classify("s"))'

# ── the arm forms that must not be mistaken for an @-bind ────────────────────────────────────────
#
# isAtBindArm keys on "ident (kind 1) directly followed by a ctor (kind 3)", which is only possible
# because the lexer DROPS `@`. Every other arm head has something else in that second position, and
# these rows are what says so.

both plain-ctor-arm 5 'def f(t: T): Int = t match { case A(_, y) => y case _ => 0 }
def main(): Unit = println(f(A(4, 5)))'

both plain-binder-arm 8 'def g(n: Int): Int = n match { case 1 => 10 case other => other + 1 }
def main(): Unit = println(g(7))'

both cons-arm 1 'def h(xs: List[Int]): Int = xs match { case y :: ys => y case _ => 0 }
def main(): Unit = println(h(List(1, 2, 3)))'

both tuple-arm 7 'def k(p: (Int, Int)): Int = p match { case (a, b) => a + b }
def main(): Unit = println(k((3, 4)))'

# Same ARITY on both sides — F's alternation requires it (parseCtorArm2 / parseParenAlt), and a
# mixed-arity `A(_, _) | B(_)` is declined for reasons that have nothing to do with this fix. The
# first draft of this row used exactly that and was red before the fix, which would have made it
# useless as a control.
both alt-arm alt 'def f(t: T): String = t match { case B(_) | D(_) => "alt" case _ => "no" }
def main(): Unit = println(f(B(1)))'

if [[ $fails -eq 0 ]]; then echo "✓ f-at-bind-pattern-gate PASSED"; exit 0; fi
echo "✗ f-at-bind-pattern-gate: $fails failure(s)"
exit 1
