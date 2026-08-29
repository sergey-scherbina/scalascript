#!/usr/bin/env bash
#
# v2-given-extension-typehead-gate — `given g: TC with extension (recv: Type) def m(...) = ...`
# must dispatch `.m` by the extension's OWN declared receiver TYPE, not silently return the
# receiver unchanged.
#
# THE DEFECT, found 2026-08-29 while diagnosing a report that "extension-dispatch doesn't work".
# The instance-dispatch synthesizer (ssc1-lower's collectExtDispatch / F's collectExtDisp) derived
# the receiver tag-test's typeHead from the ENCLOSING GIVEN's OWN type argument (`TC[T]` -> "T").
# That coincides with the extension's declared receiver type only for the `Functor[F[_]] with
# extension (fa: F[_])`-shaped pattern. For a trait with no type parameter at all —
#
#     trait HasArea:
#       extension (sh: Shape) def area(mult: Int): Int
#     given shapeArea: HasArea with
#       extension (sh: Shape) def area(mult: Int): Int = sh match { case Circle(r) => ...; ... }
#
# — there is no given type argument to read, so the derived typeHead was wrong (empty/garbage),
# the tag-test chain never matched, and the call fell to the dispatcher's fallback: THE RECEIVER
# RETURNED UNCHANGED. `c.area(3)` silently printed `c` itself — no error, no refusal, just the
# wrong answer. Confirmed independently on BOTH self-hosted compilers that can run this shape:
# the reference front (ssc1-front.ssc0 + ssc1-lower.ssc0) and F (specs/v2.2-p6.5-fsub.ssc,
# `ssc info --front-report` confirms it — not the reference front's fallback — compiles ordinary
# extension+given syntax with no type parameters directly; they are separate implementations with
# independently-broken copies of the identical defect, ported and fixed together).
#
# THE FIX reads each extension member's OWN declared receiver type off its `extension (recv: T)`
# header (ssc1-front's new `peekExtReceiverType` / F's new `extRecvTypeName`) and uses THAT as the
# dispatch typeHead, falling back to the given's own type argument only when the receiver type
# isn't a simple nominal type (generic/tuple/absent) — the case the old derivation already got
# right. Row 2 below is the case that PROVES the extension's own type wins: the given's type
# argument is a deliberate lie (`Describable[String]` for an `Int`-receiving extension) and the
# correct answer still dispatches on the receiver's REAL type.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/ext-typehead.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

run() { SSC_NO_BUILD_CHECK=1 timeout 60 "$ssc" run "$1" 2>&1; }
runLegacy() { SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 60 "$ssc" run "$1" 2>&1; }
frontOf() { SSC_NO_BUILD_CHECK=1 timeout 60 "$ssc" info --front-report "$1" 2>&1 | awk '{print $2}'; }

check() { # $1 name, $2 expected, $3 source, $4 runner (default: run)
  local name=$1 expected=$2 src=$3 runner=${4:-run}
  local file="$sandbox/$name.ssc"
  printf '%s\n' "$src" > "$file"
  local out
  out=$("$runner" "$file")
  if [[ "$out" == *"$expected"* ]]; then
    echo "  ok   $name"
  else
    echo "  FAIL $name"
    echo "         expected to contain: $expected"
    echo "         got: $out"
    fails=$((fails + 1))
  fi
}

shapeAreaSrc='
trait Shape

case class Circle(r: Int) extends Shape
case class Square(s: Int) extends Shape

trait HasArea:
  extension (sh: Shape) def area(mult: Int): Int

given shapeArea: HasArea with
  extension (sh: Shape)
    def area(mult: Int): Int = sh match
      case Circle(r) => r * r * mult
      case Square(s) => s * s * mult

def main(): Unit =
  val c = Circle(4)
  println(c.area(3))'

echo "── no-type-param given: typeHead has no given-type-argument to fall back to ─────────"

probe="$sandbox/probe.ssc"
printf '%s\n' "$shapeAreaSrc" > "$probe"
front=$(frontOf "$probe")
if [[ "$front" == "F" ]]; then
  echo "  ok   shape-area-compiles-via-F (front-report: $front)"
else
  echo "  FAIL shape-area-compiles-via-F (front-report: $front, expected F — the default-front row below would silently test the fallback instead)"
  fails=$((fails + 1))
fi

check shape-area-default '48' "$shapeAreaSrc"
check shape-area-legacy '48' "$shapeAreaSrc" runLegacy

echo "── given's own type argument is a deliberate lie — the extension's type must win ─────"

mismatchSrc='
trait Describable[F]:
  extension (fa: F) def describe: String

given intDesc: Describable[String] with
  extension (n: Int)
    def describe: String = "int:" + n.toString

def main(): Unit =
  val n = 42
  println(n.describe)'

check mismatch-default 'int:42' "$mismatchSrc"
check mismatch-legacy 'int:42' "$mismatchSrc" runLegacy

echo "── must NOT regress: the Functor[F[_]]-style pattern where the two typeHeads coincide ──"

functorSrc='
trait Boxed[F[_]]:
  extension (fa: F[Int]) def describeBoxed: String

given optDesc: Boxed[Option] with
  extension (fa: Option[Int])
    def describeBoxed: String = fa match
      case Some(v) => "some:" + v.toString
      case None => "none"

def main(): Unit =
  val a: Option[Int] = Some(9)
  val b: Option[Int] = None
  println(a.describeBoxed)
  println(b.describeBoxed)'

check functor-style-default $'some:9\nnone' "$functorSrc"
check functor-style-legacy $'some:9\nnone' "$functorSrc" runLegacy

echo
if [ "$fails" -eq 0 ]; then
  echo "v2-given-extension-typehead-gate: PASS"
  exit 0
else
  echo "v2-given-extension-typehead-gate: FAIL ($fails row(s))"
  exit 1
fi
