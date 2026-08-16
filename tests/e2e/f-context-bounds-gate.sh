#!/usr/bin/env bash
#
# f-context-bounds-gate — `def f[A: TC](…)` is a def of TWO parameters, and the second one is a
# DICTIONARY the call site passes in.
#
# THE DEFECT, measured 2026-08-16: F erased the whole `[…]` clause with `skipGen`, so `summon[TC[A]]`
# in the body had nothing to resolve to and emitted the bare `(global summon)` — five corpus files
# declined. The oracle desugars each bound into a real `__tc_<TC>` parameter (ssc1-front mkCtxParam,
# read back by ssc1-lower isCtxParam/ctxParamTC): dictionary passing, no runtime dispatcher.
#
# THE TRAP THIS GATE EXISTS FOR. Making the bound compile is the easy half. The old selection rule
# was "exact type match, else the FIRST instance of the type class", and with `combineAll` there are
# three Monoid instances:
#
#   println(combineAll(List("hello", ", ", "world")))   F: 0hello, world     ref: hello, world
#
# It picked `intSum`, folded from 0, and printed a WRONG ANSWER — the failure mode no output gate
# catches, because the program ran and produced a plausible string. `type-directed-choice` below is
# that exact line. `ambiguous-declines` is its complement: when the type genuinely cannot be known
# and more than one instance exists, F must REFUSE the module (an unbound `__ambiguous_using_` marker
# makes the reference compile it instead) rather than guess. A decline costs coverage; a guess costs
# correctness, and only one of those is recoverable.
#
# THREE ORDERING FACTS, each of which was wrong once and is a row here:
#   * the dictionary is the LAST parameter, not the first — F's injection APPENDS givens and the
#     first argument takes the highest local index, so prepending hands the body the LIST where it
#     expects the instance ("`Cons.empty` was called but does not exist").
#   * with two bounds the injection list runs in SOURCE order while the env runs reversed, so
#     `ctxTCs` reverses. Getting it backwards swaps the dictionaries and each is asked for the
#     other's method. A single bound is symmetric and cannot catch this — hence `two-bounds`.
#   * a bound PROPAGATES: inside `f[A: TC]`, `A` is abstract, so no global given can be right and the
#     caller's own dictionary is the only correct answer. `bound-propagation` is that row.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/ctx-bounds.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a context bound is a dictionary parameter"
ssc_usable_or_skip f-context-bounds-gate "$ssc"

run_front() {
  if [[ "$1" == legacy ]]; then
    SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$2" 2>&1
  else
    SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$2" 2>&1
  fi
}

# Both fronts must produce the SAME lines. Comparing against the reference rather than against a
# frozen string is what makes a row about F's agreement instead of about my expectations.
both() {
  local name=$1 want=$2 src=$3 f r
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  r=$(run_front legacy "$sandbox/$name.ssc" | tr '\n' '|')
  f=$(run_front F "$sandbox/$name.ssc" | tr '\n' '|')
  if [[ "$r" == "$want" && "$f" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: reference='$r' F='$f', wanted '$want' from both"
    fails=$((fails + 1))
  fi
}

# F ALONE, because the reference is wrong on these — it does not propagate a context bound through a
# call and re-resolves from the given table by first-instance, so `q` comes back as `q0`. The row
# still prints what the reference said, so the day that changes is visible rather than silent.
# Filed as `ref-front-loses-the-context-bound-across-a-call` in BUGS.md.
f_only() {
  local name=$1 want=$2 src=$3 f r
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  f=$(run_front F "$sandbox/$name.ssc" | tr '\n' '|')
  r=$(run_front legacy "$sandbox/$name.ssc" | tr '\n' '|')
  if [[ "$f" == "$want" ]]; then
    echo "  ✓ $name: $want   (reference disagrees: '$r')"
  else
    echo "  ✗ $name: F='$f', wanted '$want'   (reference: '$r')"
    fails=$((fails + 1))
  fi
}

TC='trait M[A]:
  def empty: A
  def combine(x: A, y: A): A

given intM: M[Int] with
  def empty: Int = 0
  def combine(x: Int, y: Int): Int = x + y

given strM: M[String] with
  def empty: String = ""
  def combine(x: String, y: String): String = x + y
'

# ── the defect ───────────────────────────────────────────────────────────────────────────────────

both single-bound '10|' "$TC
def dbl[A: M](a: A): A = summon[M[A]].combine(a, a)
def main(): Unit = println(dbl(5))"

# THE LIAR. Two instances exist; the String call must reach strM. Under the old first-instance rule
# this printed a wrong answer instead of failing, which is why it is the first row after the basics.
both type-directed-choice 'haha|' "$TC
def dbl[A: M](a: A): A = summon[M[A]].combine(a, a)
def main(): Unit = println(dbl(\"ha\"))"

# The element of a list argument is what carries the bound, not the list. This one folds down to
# `empty`, which is the ONLY member that distinguishes the two instances — `combine` is `x + y` in
# both and concatenates strings either way, so a row built on `combine` alone passes with the wrong
# dictionary. That is exactly how the first version of `bound-propagation` below came out green
# while the dictionary was being chosen at random.
f_only list-element-type 'ab|' "$TC
def fold1[A: M](xs: List[A]): A = xs match { case Nil => summon[M[A]].empty case h :: t => summon[M[A]].combine(h, fold1(t)) }
def main(): Unit = println(fold1(List(\"a\", \"b\")))"

both list-element-type-int '3|' "$TC
def fold1[A: M](xs: List[A]): A = xs match { case Nil => summon[M[A]].empty case h :: t => summon[M[A]].combine(h, fold1(t)) }
def main(): Unit = println(fold1(List(1, 2)))"

# ── the three orderings ──────────────────────────────────────────────────────────────────────────

both two-bounds '#4|' "$TC
trait P[A]:
  def pretty(a: A): String

given pInt: P[Int] with
  def pretty(a: Int): String = \"#\" + a.toString

def both2[A: M: P](a: A): String = summon[P[A]].pretty(summon[M[A]].combine(a, a))
def main(): Unit = println(both2(2))"

# `A` is abstract inside `outer`, so the given table cannot answer — only the caller's dictionary can.
# `empty` is what makes this discriminate: with the wrong (Int) dictionary the answer is "q0".
f_only bound-propagation 'q|' "$TC
def inner[A: M](a: A): A = summon[M[A]].combine(a, summon[M[A]].empty)
def outer[A: M](a: A): A = inner(a)
def main(): Unit = println(outer(\"q\"))"

# The same shape at Int, where the first-instance guess happens to be right and both fronts agree —
# so a regression that reverts propagation shows up as ONE row flipping, not as the pair going red
# together and looking like an environment problem.
both propagation-int '2|' "$TC
def inner[A: M](a: A): A = summon[M[A]].combine(a, summon[M[A]].empty)
def outer[A: M](a: A): A = inner(a)
def main(): Unit = println(outer(2))"

# ── the honest decline ───────────────────────────────────────────────────────────────────────────
#
# `id(1)` is an application, so the argument type is unknowable here and BOTH instances remain
# candidates. F must decline — and the program must still run, because declining hands the module to
# the reference. Two assertions, because either alone is satisfiable by a broken compiler: a front
# that declines everything passes the first, and a front that guesses passes the second.

printf '%s\n' "$TC
def id(a: Int): Int = a
def dbl[A: M](a: A): A = summon[M[A]].combine(a, a)
def main(): Unit = println(dbl(id(1)))" > "$sandbox/ambig.ssc"

strict=$(run_front F "$sandbox/ambig.ssc")
if grep -q '__ambiguous_using_M' <<<"$strict"; then
  echo "  ✓ ambiguous-declines: F refuses rather than guessing"
else
  echo "  ✗ ambiguous-declines: F did not decline — got '$(tr '\n' '|' <<<"$strict")'"
  fails=$((fails + 1))
fi

# stdout ONLY: the fallback notice goes to stderr, and folding it in would make this row assert the
# notice's wording rather than the program's answer.
fallback=$(SSC_NO_BUILD_CHECK=1 timeout 200 "$ssc" run "$sandbox/ambig.ssc" 2>/dev/null | tr '\n' '|')
if [[ "$fallback" == '2|' ]]; then
  echo "  ✓ ambiguous-still-runs: the decline falls back and the program is correct"
else
  echo "  ✗ ambiguous-still-runs: wanted '2|', got '$fallback'"
  fails=$((fails + 1))
fi

# ── the shapes that must not move ────────────────────────────────────────────────────────────────
#
# An explicit `using` clause goes through the same selection code, so it is the thing a strictness
# change breaks first.

both explicit-using '7|' "$TC
def add(a: Int, b: Int)(using m: M[Int]): Int = m.combine(a, b)
def main(): Unit = println(add(3, 4))"

both plain-generic 'List(2, 3)|' 'def bump[A](xs: List[Int]): List[Int] = xs.map(x => x + 1)
def main(): Unit = println(bump[Int](List(1, 2)))'

both bodyless-generic '2|' 'def snd2[A, B](p: (A, B)): B = p._2
def main(): Unit = println(snd2((1, 2)))'

if [[ $fails -eq 0 ]]; then echo "✓ f-context-bounds-gate PASSED"; exit 0; fi
echo "✗ f-context-bounds-gate: $fails failure(s)"
exit 1
