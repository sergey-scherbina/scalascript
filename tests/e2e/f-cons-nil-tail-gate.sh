#!/usr/bin/env bash
#
# f-cons-nil-tail-gate — a cons pattern whose TAIL is not a bare variable (`case h :: Nil`,
# `case a :: b :: Nil`) matches what it says, in the ORDERED resolver as well as the ctor one.
#
# THE DEFECT, measured 2026-08-14 on F, and it is a silent wrong answer rather than a decline.
# F picks a match's resolver from its arms: any match containing a NESTED pattern goes to the
# ordered resolver (`parseMatchArms` → `hasNestedArms` → `parseGenMatch`), and a `Nil` tail counts as
# nested. That resolver had no cons rule at all, so `case h :: Nil` fell through `parseGenArm` to
# `parseGenVar` — which reads `h` as a catch-all VAR arm bound to the whole scrutinee and takes
# `:: Nil => …` as the body:
#
#   xs match { case h :: Nil => "one:" + h  case _ => "other" }
#
#     f(List(5))     F: one:List(5)      ref: one:5        <- h bound to the LIST, not the head
#     f(List(4, 5))  F: one:List(4, 5)   ref: other        <- and the arm matched when it must not
#
# The second line is the worse one: it is not a mis-bound name, it is the wrong ARM. A catch-all
# tests nothing, so every list took the one-element branch.
#
# NINE FILES, ONE SHAPE. `f-gap-census-refresh` ranked this second among F's remaining refusals —
# every `tests/conformance/scljet-*` case carries `case h :: Nil` in a `showRow`/`showValue` helper,
# and all nine reported `unbound global: (global h)`, a name plainly present in the pattern.
#
# BOTH RESOLVERS ARE ASSERTED, deliberately and at some cost in rows. Which one runs is decided by
# the arms, not by the syntax, so a row cannot pick its resolver directly: `plain-cons-*` reach the
# CTOR path (no nested pattern anywhere in the match) and everything with a `Nil` tail reaches the
# ORDERED one. That distinction is not decoration — `f-at-bind-pattern-emits-unbound-underscore`
# nearly shipped half-fixed the same day because eleven green rows all reached the same resolver.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/cons-nil.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a cons pattern with a non-variable tail matches what it says"
ssc_usable_or_skip f-cons-nil-tail-gate "$ssc"

run_front() { # $1 front (legacy|F), $2 file → first line of output (stderr folded in)
  if [[ "$1" == legacy ]]; then
    SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$2" 2>&1 | head -1
  else
    SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$2" 2>&1 | head -1
  fi
}

both() {
  local name=$1 want=$2 src=$3 f r
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  r=$(run_front legacy "$sandbox/$name.ssc")
  f=$(run_front F "$sandbox/$name.ssc")
  if [[ "$r" == "$want" && "$f" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: reference='$r' F='$f', wanted '$want' from both"
    fails=$((fails + 1))
  fi
}

# ── the ORDERED resolver: every row here has a `Nil` tail, which is what routes it there ─────────

# The binding: `h` is the HEAD, not the scrutinee.
both nil-tail-binds-the-head one:5 'def f(xs: List[Int]): String = xs match { case h :: Nil => "one:" + h  case _ => "other" }
def main(): Unit = println(f(List(5)))'

# THE ROW THAT MATTERS MOST: the arm must not match a two-element list. A catch-all tests nothing,
# so under the defect this answered `one:List(4, 5)` — wrong value AND wrong arm.
both nil-tail-does-not-match-longer other 'def f(xs: List[Int]): String = xs match { case h :: Nil => "one:" + h  case _ => "other" }
def main(): Unit = println(f(List(4, 5)))'

# A nested tail two deep, so the obligation machinery is exercised rather than a single Nil test.
both two-element-nil-tail two:45 'def f(xs: List[Int]): String = xs match { case a :: b :: Nil => "two:" + a + b  case _ => "other" }
def main(): Unit = println(f(List(4, 5)))'

both two-element-nil-tail-longer other 'def f(xs: List[Int]): String = xs match { case a :: b :: Nil => "two:" + a + b  case _ => "other" }
def main(): Unit = println(f(List(4, 5, 6)))'

# The real corpus shape: a `Nil` arm, then the one-element cons, then the general cons.
both nil-then-one-then-many one:7 'def f(xs: List[Int]): String = xs match { case Nil => "empty"  case h :: Nil => "one:" + h  case h :: t => "many:" + h }
def main(): Unit = println(f(List(7)))'

both nil-then-one-then-many-long many:7 'def f(xs: List[Int]): String = xs match { case Nil => "empty"  case h :: Nil => "one:" + h  case h :: t => "many:" + h }
def main(): Unit = println(f(List(7, 8)))'

both nil-then-one-then-many-empty empty 'def f(xs: List[Int]): String = xs match { case Nil => "empty"  case h :: Nil => "one:" + h  case h :: t => "many:" + h }
def main(): Unit = println(f(List()))'

# The scljet helper, as written: recursive, and the recursion is what makes a mis-taken arm loop or
# lie rather than merely answer wrongly.
both recursive-show '1|2|3' 'def showRow(xs: List[Int]): String = xs match { case h :: Nil => "" + h  case h :: t => "" + h + "|" + showRow(t)  case _ => "" }
def main(): Unit = println(showRow(List(1, 2, 3)))'

# ── the CTOR resolver: no nested pattern anywhere, so these route the other way ───────────────────
#
# Green before the fix and after it. They are here because the two resolvers are independent and a
# fix to one is invisible to rows that only reach the other.

both plain-cons-head many:5 'def f(xs: List[Int]): String = xs match { case h :: t => "many:" + h  case _ => "e" }
def main(): Unit = println(f(List(5)))'

both plain-cons-tail 'List(6, 7)' 'def f(xs: List[Int]): String = xs match { case h :: t => "" + t  case _ => "e" }
def main(): Unit = println(f(List(5, 6, 7)))'

both bare-nil-arm-then-cons many:5 'def f(xs: List[Int]): String = xs match { case Nil => "empty"  case h :: t => "many:" + h }
def main(): Unit = println(f(List(5)))'

if [[ $fails -eq 0 ]]; then echo "✓ f-cons-nil-tail-gate PASSED"; exit 0; fi
echo "✗ f-cons-nil-tail-gate: $fails failure(s)"
exit 1
