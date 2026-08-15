#!/usr/bin/env bash
#
# f-foldable-grade-gate — a declaration may list additional parents with a COMMA, not only `with`.
#
# THE DEFECT, measured 2026-08-15: `trait C[T] extends A[T], B[T]` made F decline. Scala 3 accepts
# both separators; F's parent-list skipper knew only `with`, so the comma and everything after it
# stayed in the token stream and the SECOND parent was read as an expression. The symptom names the
# trait itself — `std/foldable-traversable.ssc` declined on `unbound global: (global Foldable)`, from
# its own line `trait Traversable[T[_]] extends Functor[T], Foldable[T]`.
#
# TWO CORPUS FILES: `std/foldable-traversable.ssc` and `tests/conformance/std-foldable-traversable.ssc`.
# Both now lower under F and agree with the reference front.
#
# THE `with` AND SINGLE-PARENT ROWS ARE CONTROLS, and they are cheap insurance: the fix extends a
# recursive chain, and getting the recursion wrong would break the separator that already worked.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/parents.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a parent list may be separated by commas"
ssc_usable_or_skip f-foldable-grade-gate "$ssc"

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

# ── the defect ───────────────────────────────────────────────────────────────────────────────────

both trait-comma-parents ok 'trait A[T]
trait B[T]
trait C[T] extends A[T], B[T]
def main(): Unit = println("ok")'

# Three parents, so the chain has to recurse rather than skip exactly one comma.
both trait-three-parents ok 'trait A[T]
trait B[T]
trait D[T]
trait C[T] extends A[T], B[T], D[T]
def main(): Unit = println("ok")'

# Mixed separators, which is legal and is what a naive fix breaks.
both trait-mixed-separators ok 'trait A[T]
trait B[T]
trait D[T]
trait C[T] extends A[T], B[T] with D[T]
def main(): Unit = println("ok")'

# The corpus shape: a parent list on a trait carrying extension members.
both traversable-shape 'List(2, 4)' 'trait Functor[F[_]]:
  extension [A](fa: F[A]) def fmap[B](f: A => B): F[B]

trait Foldable[F[_]]:
  extension [A](fa: F[A]) def toL: List[A]

trait Traversable[T[_]] extends Functor[T], Foldable[T]

given listFunctor: Functor[List] with
  extension [A](fa: List[A]) def fmap[B](f: A => B): List[B] = fa.map(f)

def main(): Unit = println(List(1, 2).fmap((x: Int) => x * 2))'

# ── the separators that already worked ───────────────────────────────────────────────────────────

both trait-single-parent ok 'trait A[T]
trait C[T] extends A[T]
def main(): Unit = println("ok")'

both trait-with-parents ok 'trait A[T]
trait B[T]
trait C[T] extends A[T] with B[T]
def main(): Unit = println("ok")'

both class-extends-single ok 'trait A
case class P(n: Int) extends A
def main(): Unit = println("ok")'

if [[ $fails -eq 0 ]]; then echo "✓ f-foldable-grade-gate PASSED"; exit 0; fi
echo "✗ f-foldable-grade-gate: $fails failure(s)"
exit 1
