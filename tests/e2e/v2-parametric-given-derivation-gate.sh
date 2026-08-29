#!/usr/bin/env bash
#
# v2-parametric-given-derivation-gate — `given X[A](using ma: TC[A], ...): TC2[F[A]] with { … }`
# must actually derive an instance, not corrupt the file around it.
#
# THE DEFECT, found 2026-08-29 while extending specs/aggregation-algebra.md with EffAggregator.
# `ssc1-front.ssc0`'s `given` parser expected `:` or `=` immediately after the given's NAME — a
# type-param list (`[A, B]`) or a `(using …)` clause in between matched neither, so the parser
# treated the WHOLE declaration as `sealed` (a no-op) and skipped to "the next statement" — except
# the skip did not correctly span the given's own multi-line `with { … }` body, so that body's
# tokens leaked out and were re-parsed as ordinary top-level code, corrupting whatever type
# information the checker had already built for an EARLIER, unrelated given of the same trait:
#
#     given intM: Monoid[Int] with { def empty = 0; def combine(a,b) = a + b }
#     given pairMonoid[A, B](using ma: Monoid[A], mb: Monoid[B]): Monoid[(A, B)] with { … }
#     summon[Monoid[(Int, String)]]     ->  TYPEERR: in def empty: cannot unify tuple: (Dyn,Dyn) vs String
#
# The message blames `intM`'s `Int`, nowhere near the actual mistake — the tuple never even
# started being derived. Two ORDINARY givens of the same trait (no type params, no `using`) were
# never affected; only a THIRD, parametric one triggered the corruption (row 2 below is the control
# that pins this).
#
# THE FIX has two parts. (1) The parser now recognizes the shape and produces a `given_poly` node
# carrying the type params, the `using` params (name + full declared type, e.g. `Monoid[A]` — not
# just the head `Monoid`, which is all the EXISTING `using`-on-a-def machinery keeps), the target
# type, and the body — falling through to the EXACT prior parsing for a plain given untouched.
# (2) `ssc1-lower.ssc0` unifies a `given_poly`'s declared type against a `summon[TC[Concrete]]`
# request (`Monoid[(A, B)]` vs `Monoid[(Int, String)]` binds `A->Int, B->String`), then RECURSIVELY
# resolves each `using` requirement the same way (`Monoid[A]` substituted to `Monoid[Int]`, looked
# up in the plain given table or derived again) before constructing the instance directly as
# CoreIR. Only consulted when the PLAIN given table has no exact/wildcard match — a request that
# already resolved before this existed still resolves exactly the same way, unchanged.
#
# F FRONT NOTE: `ssc1-check.ssc0` (F's separate type-checking pass) does not know the `given_poly`
# tag yet and falls back to the reference front for a file using this construct — an HONEST,
# non-corrupting fallback (`ssc info --front-report` reports it as a GAP), not a wrong answer, and
# not what this gate holds F to. `v2/BUGS.md`'s own entry for this defect tracks porting the
# derivation to F's checker as a separate follow-up.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/poly-given.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

run() { # $1 file → combined stdout+stderr, reference lane (F may honestly fall back — see header)
  SSC_NO_BUILD_CHECK=1 timeout 60 "$ssc" run "$1" 2>&1
}

check() { # $1 name, $2 expected, $3 source
  local name=$1 expected=$2 src=$3
  local file="$sandbox/$name.ssc"
  printf '%s\n' "$src" > "$file"
  local out
  out=$(run "$file")
  if [[ "$out" == *"$expected"* ]]; then
    echo "  ok   $name"
  else
    echo "  FAIL $name"
    echo "         expected to contain: $expected"
    echo "         got: $out"
    fails=$((fails + 1))
  fi
}

echo "── derivation itself ──────────────────────────────────────────────"

check tuple-pair '(3, ab)' '
trait Monoid[A]:
  def empty: A
  def combine(a: A, b: A): A

given intM: Monoid[Int] with
  def empty: Int = 0
  def combine(a: Int, b: Int): Int = a + b

given strM: Monoid[String] with
  def empty: String = ""
  def combine(a: String, b: String): String = a + b

given pairMonoid[A, B](using ma: Monoid[A], mb: Monoid[B]): Monoid[(A, B)] with
  def empty: (A, B) = (ma.empty, mb.empty)
  def combine(x: (A, B), y: (A, B)): (A, B) = (ma.combine(x._1, y._1), mb.combine(x._2, y._2))

def main(): Unit =
  val m = summon[Monoid[(Int, String)]]
  println(m.combine((1, "a"), (2, "b")))'

check single-param-wrapper '7' '
trait Monoid[A]:
  def empty: A
  def combine(a: A, b: A): A

given intM: Monoid[Int] with
  def empty: Int = 0
  def combine(a: Int, b: Int): Int = a + b

case class Box[A](value: A)

given boxMonoid[A](using ma: Monoid[A]): Monoid[Box[A]] with
  def empty: Box[A] = Box(ma.empty)
  def combine(x: Box[A], y: Box[A]): Box[A] = Box(ma.combine(x.value, y.value))

def main(): Unit =
  val m = summon[Monoid[Box[Int]]]
  println(m.combine(Box(3), Box(4)).value)'

check nested-two-levels '(3, ab)' '
trait Monoid[A]:
  def empty: A
  def combine(a: A, b: A): A

given intM: Monoid[Int] with
  def empty: Int = 0
  def combine(a: Int, b: Int): Int = a + b

given strM: Monoid[String] with
  def empty: String = ""
  def combine(a: String, b: String): String = a + b

given pairMonoid[A, B](using ma: Monoid[A], mb: Monoid[B]): Monoid[(A, B)] with
  def empty: (A, B) = (ma.empty, mb.empty)
  def combine(x: (A, B), y: (A, B)): (A, B) = (ma.combine(x._1, y._1), mb.combine(x._2, y._2))

case class Box[A](value: A)

given boxMonoid[A](using ma: Monoid[A]): Monoid[Box[A]] with
  def empty: Box[A] = Box(ma.empty)
  def combine(x: Box[A], y: Box[A]): Box[A] = Box(ma.combine(x.value, y.value))

def main(): Unit =
  val m = summon[Monoid[Box[(Int, String)]]]
  println(m.combine(Box((1, "a")), Box((2, "b"))).value)'

echo "── must NOT regress: two ORDINARY givens of the same trait, no parametric third one ──"

check two-plain-givens $'0\n\n7\nab' '
trait Monoid[A]:
  def empty: A
  def combine(a: A, b: A): A

given intM: Monoid[Int] with
  def empty: Int = 0
  def combine(a: Int, b: Int): Int = a + b

given strM: Monoid[String] with
  def empty: String = ""
  def combine(a: String, b: String): String = a + b

def main(): Unit =
  println(intM.empty)
  println(strM.empty)
  println(intM.combine(3, 4))
  println(strM.combine("a", "b"))'

echo
if [ "$fails" -eq 0 ]; then
  echo "v2-parametric-given-derivation-gate: PASS"
  exit 0
else
  echo "v2-parametric-given-derivation-gate: FAIL ($fails row(s))"
  exit 1
fi
