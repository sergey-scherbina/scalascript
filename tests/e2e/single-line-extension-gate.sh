#!/usr/bin/env bash
#
# single-line-extension-gate — `extension (r) def m …` on ONE line declares ONE member, and the
# declarations after it are still top-level.
#
# THE DEFECT, measured 2026-08-13 on both fronts. The single-line form's member block was never
# opened (no NL follows the receiver), so no `extension_end` was ever emitted, so the receiver
# parameters stayed live for the rest of the file and every following `def` silently became a
# member of the extension:
#
#   extension (s: String) def boxed: String = s
#   def tagged: String = "T"        -- `"a".tagged` answered "T": ABSORBED
#   def main(): Unit = …            -- absorbed too
#
# The two fronts then failed differently, which is why this was filed as one bug and is really two:
#
#   reference (legacy)  exit 0 and NO OUTPUT — `main` had become a member, so nothing ran
#   F                   `arity: 1 expected, 0 given` — `main` had gained the receiver parameter
#
# THE ABSORPTION IS WHAT THIS GATE PINS, not either symptom. `following-def-is-callable` calls the
# next declaration as an ordinary function: under the bug it has an extra leading parameter and the
# call fails, whatever the front does about `main`.
#
# THE CONTROLS ARE THE RISK. Nine sites in this repo already use the single-line form — inside
# `trait`/`given … with` bodies (`std/functor-applicative-monad.ssc`, `std/monaderror.ssc`,
# `tests/conformance/typeclass-extension.ssc`) — and they WORK, because a body with one member has
# nothing for the leak to corrupt. A fix that scopes the form must not disturb them, and
# `typeclass-extension` below is that check: abstract member, implemented member, and a member whose
# body continues on following lines after `= … match`.
#
# F IS ASSERTED AS KNOWN-RED, deliberately. Its half of the defect is unfixed and its front file is
# held by another claim; a row that merely prints the failure would let it be fixed with nobody
# noticing this gate. Instead the known-red rows FAIL when F starts passing, so whoever fixes F is
# told to promote them.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/single-ext.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a single-line extension declares ONE member"
ssc_usable_or_skip single-line-extension-gate "$ssc"

# The reference front is INTERPRETED from a staged copy of `ssc1-front.ssc0`, so a toolchain built
# before a front fix runs the OLD parser while the checkout holds the new one. That is not
# hypothetical — it kept `reference-front-mislexes-a-dollar-brace-inside-a-plain-string-literal`
# open for four days on a measurement of code its own fix had replaced.
front_src="$ROOT/v2/lib/ssc1-front.ssc0"
front_staged=""
for c in "$(dirname -- "$ssc")/lib/standard/native-front/tower/lib/ssc1-front.ssc0" \
         "$(dirname -- "$ssc")/lib/native-front/tower/lib/ssc1-front.ssc0"; do
  [[ -r "$c" ]] && { front_staged="$c"; break; }
done
if [[ -n "$front_staged" && -r "$front_src" ]]; then
  if cmp -s "$front_staged" "$front_src"; then
    echo "  · toolchain carries this checkout's reference front"
  else
    echo "  ✗ STALE TOOLCHAIN — the reference front staged in the toolchain is not this checkout's:"
    echo "      staged:   $front_staged"
    echo "      checkout: $front_src"
    echo "    Rebuild before reading any row below: ./install.sh --dev"
    exit 1
  fi
else
  echo "  · front staleness NOT checked — no staged ssc1-front.ssc0 under $(dirname -- "$ssc")/lib"
fi

run_front() { # $1 front (legacy|F), $2 file → first line of output (stderr folded in)
  if [[ "$1" == legacy ]]; then
    SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$2" 2>&1 | head -1
  else
    SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$2" 2>&1 | head -1
  fi
}

# $1 name, $2 expected first line, $3 source — asserted on BOTH fronts.
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

# $1 name, $2 expected first line, $3 source — the reference front must answer it; F is KNOWN-RED
# and the row fails if F starts answering correctly, so the state cannot drift unnoticed.
ref_only() {
  local name=$1 want=$2 src=$3 f r
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  r=$(run_front legacy "$sandbox/$name.ssc")
  f=$(run_front F "$sandbox/$name.ssc")
  if [[ "$r" != "$want" ]]; then
    echo "  ✗ $name: reference='$r', wanted '$want'"
    fails=$((fails + 1))
  elif [[ "$f" == "$want" ]]; then
    echo "  ✗ $name: F is GREEN now — promote this row to both() and close the F half of"
    echo "      v2-extension-member-call-inside-a-def-body-fails-by-arity"
    fails=$((fails + 1))
  else
    echo "  ✓ $name: $want   (F still red: '$f')"
  fi
}

# THE ROW THAT PINS THE ABSORPTION, independently of what either front does with `main`.
ref_only following-def-is-callable T 'extension (s: String) def boxed: String = s
def tagged(): String = "T"
def main(): Unit = println(tagged())'

# The shape the conformance case carries: the member is used from inside a def body, with no
# top-level statement above that def. Both conditions are required to reproduce.
ref_only member-from-a-def-body body-a 'case class Box(v: String)
extension (s: String) def boxed: Box = Box(s)
def inBody(): Unit = println("body-" + "a".boxed.v)
def main(): Unit = inBody()'

ref_only member-from-top-level top-a 'case class Box(v: String)
extension (s: String) def boxed: Box = Box(s)
def main(): Unit = println("top-" + "a".boxed.v)'

# Nothing in the file uses the extension at all: the declaration alone was enough to break the file.
ref_only untouched-extension after-extension 'extension (s: String) def boxed: String = s
def main(): Unit = println("after-extension")'

echo "── the shapes already in this repo, and the two forms that were never broken"

both block-form block-form 'extension (s: String)
  def boxed: String = s
def main(): Unit = println("block-form")'

both extension-last before-extension 'def main(): Unit = println("before-extension")
extension (s: String) def boxed: String = s'

# The nine real sites in this repo, in one file: an ABSTRACT single-line member in a trait, an
# implemented one in a `given … with`, and one whose body continues on the next line after `match`.
#
# AND IT WAS RED ON THE REFERENCE FRONT TOO, which is worth stating because it was written as a
# control and is not one. The nine sites work as written only because nothing follows them that the
# leak can corrupt — `typeclass-extension.ssc` ends in top-level `println`s. Add a `def` after the
# givens, as below, and the receiver leaks out of the `given … with` body into it: reference front
# silent, F fine. So this row asserts the fix as much as it guards the existing shapes.
both trait-and-given-members 'List(2, 4, 6)' 'trait Functor[F[_]]:
  extension [A](fa: F[A]) def fmap[B](f: A => B): F[B]

given listFunctor: Functor[List] with
  extension [A](fa: List[A]) def fmap[B](f: A => B): List[B] = fa.map(f)

given optionFunctor: Functor[Option] with
  extension [A](fa: Option[A]) def fmap[B](f: A => B): Option[B] = fa match
    case Some(a) => Some(f(a))
    case None => None

def main(): Unit =
  println(List(1, 2, 3).fmap((x: Int) => x * 2))
  println(Some(10).fmap((x: Int) => x + 1))'

if [[ $fails -eq 0 ]]; then echo "✓ single-line-extension-gate PASSED"; exit 0; fi
echo "✗ single-line-extension-gate: $fails failure(s)"
exit 1
