#!/usr/bin/env bash
#
# f-cons2-no-arm-gate — a member call on a `given … with` object, by name, COMPILES.
#
# THE DEFECT, measured 2026-08-15: F's compiler CRASHED — not F declining a construct, F itself
# dying with `match: no arm for Cons/2` while lowering five lines:
#
#   trait M:
#     def empty: Int
#   given intM: M with
#     def empty: Int = 7
#   def main(): Unit = println(intM.empty)
#
# TWO REGISTRARS, ONE READER, AND ONE OF THEM WAS NEVER WIDENED. `objReg` maps an object name to its
# members, and its payload used to be a bare list of member names. It was widened to a PAIR —
# `(memberNames, varMemberNames)` — because a `var` member lowers to `O_v__cell` and the selection
# site has to know that from outside the object. `collectObjReg` was updated; `collectGivenObj`,
# which registers `given … with` bodies into the SAME table, kept emitting the bare list.
#
# The reader is `objMemsOf`, which does `fst(snd(h))`. On an object entry that takes the first half
# of a pair; on a given entry it applies `fst` to a LIST, and a `Cons` hitting a Tuple2-only match is
# exactly `no arm for Cons/2`. It fires only when the given is looked up BY NAME, which is why
# binding the same value to a local first always worked and made the bug look like it was about
# `summon`.
#
# FIVE CORPUS FILES, and the census is what found it rather than a person: `examples/typeclass.ssc`,
# `tests/conformance/std-index.ssc`, `std-monaderror.ssc`, `std-semigroup-monoid.ssc`,
# `tagless-multi-file.ssc` — every one calls a given by name (`intShow.show(42)`,
# `intSum.combine(…)`).
#
# THE `var` ROWS ARE THE POINT OF THE CONTROLS. The fix makes givens carry the same pair, so the
# second half of that pair — the var-member names, read by `isObjVarMember` — must still be right for
# plain objects AND must not start claiming a given's plain `def` is a `var`. `object-var-member`
# and `given-def-is-not-a-var` are those two directions.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/cons2.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a member call on a given-by-name compiles"
ssc_usable_or_skip f-cons2-no-arm-gate "$ssc"

run_front() {
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

# ── the crash ────────────────────────────────────────────────────────────────────────────────────

both given-member-by-name 7 'trait M:
  def empty: Int
given intM: M with
  def empty: Int = 7
def main(): Unit = println(intM.empty)'

both given-member-with-args 5 'trait M:
  def f(n: Int): Int
given intM: M with
  def f(n: Int): Int = n + 1
def main(): Unit = println(intM.f(4))'

# `summon[M].empty` resolves to the given's global name and then takes the same selection path, so it
# crashed for the same reason — which is why this was first mistaken for a `summon` defect.
both summon-then-member 0 'trait M:
  def empty: Int
given intM: M with
  def empty: Int = 0
def main(): Unit = println(summon[M].empty)'

both two-givens-both-called 12 'trait M:
  def v: Int
given a: M with
  def v: Int = 5
given b: M with
  def v: Int = 7
def main(): Unit = println(a.v + b.v)'

# ── controls: the shapes that always worked, and the two halves of the widened payload ───────────

# Binding the same value to a local first was always fine — the objReg lookup never happened.
both given-via-local 0 'trait M:
  def empty: Int
given intM: M with
  def empty: Int = 0
def main(): Unit =
  val x = summon[M]
  println(x.empty)'

# A plain object: the entry that was ALREADY a pair.
both object-member 9 'object O:
  def k(): Int = 9
def main(): Unit = println(O.k())'

# The second half of the pair, read by isObjVarMember: a `var` member is `O_v__cell` and the
# selection site has to know it from outside. This is what the payload was widened FOR.
both object-var-member 3 'object O:
  var v: Int = 3
def main(): Unit = println(O.v)'

# …and the other direction: a given's plain `def` must NOT be reported as a var member, or the
# selection emits a cell read for something that is not a cell.
both given-def-is-not-a-var 4 'trait M:
  def v: Int
given g: M with
  def v: Int = 4
def main(): Unit = println(g.v)'

# A given reached through an extension rather than by name — the path that was green throughout.
both given-via-extension 'List(2, 4, 6)' 'trait Functor[F[_]]:
  extension [A](fa: F[A]) def fmap[B](f: A => B): F[B]

given listFunctor: Functor[List] with
  extension [A](fa: List[A]) def fmap[B](f: A => B): List[B] = fa.map(f)

def main(): Unit = println(List(1, 2, 3).fmap((x: Int) => x * 2))'

if [[ $fails -eq 0 ]]; then echo "✓ f-cons2-no-arm-gate PASSED"; exit 0; fi
echo "✗ f-cons2-no-arm-gate: $fails failure(s)"
exit 1
