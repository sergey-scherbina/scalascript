#!/usr/bin/env bash
#
# f-bodyless-object-gate — a top-level `object A` with NO body declares an empty object, and the
# declarations after it are still there.
#
# THE DEFECT, measured 2026-08-14 on F. `objBodyToks` scanned forward from the object's name for the
# first `{` with NO stop condition, so a bodyless object consumed everything up to the next brace
# ANYWHERE in the file — or, if the file had none, the entire rest of it. The oracle has the stop and
# F did not: `v2/lib/ssc1-front.ssc0 skipToBrace` halts at `{`, EOF **or `;`**, and that missing third
# arm is the whole bug.
#
# It has three symptoms, which is why it was filed as something else. All three are rows below:
#
#   object A                          -> no `{` follows anywhere: the rest of the file is DISCARDED,
#   def h(): Int = 3                     `main` included, and F reports `unbound global: (global main)`
#   def main(): Unit = println(h())
#
#   object A                          -> the first `{` is the one the layout opens for h's continued
#   def h(): Int =                       body, so `h` is eaten and `main` survives past the matching
#     3                                  `}`: `unbound global: (global h)`
#   def main(): Unit = println(h())
#
#   object A                          -> collectObjReg attributes B's MEMBERS to the name A, so the
#   object B:                            call resolves to nothing: `unhandled runtime effect: B.g`.
#     def g(): Int = 2                   This is the symptom that reached the corpus, via
#   def main(): Unit = println(B.g())    `case object NoContext extends ParserContext` in
#                                        std/parsing/core.ssc.
#
# THE LAW THE BOARD HAD WAS WRONG IN BOTH DIRECTIONS, and the rows keep it honest.
# `f-package-namespace-breaks-on-an-object-with-extends` recorded `package:` as required and an
# `extends` clause as required. Neither is: there is no `package:` in any row here, `bodyless-plain`
# has no `extends`, and `case-object-bodyless` — which the entry said made no difference — is GREEN
# because `case object` takes a different parser path entirely. What is required is BODYLESSNESS.
# The package only ever supplied the generated text whose first `{` was the thing being run into.
#
# THE CONTROLS ARE THE POINT OF THE GUARD. `objBodyToks` is shared by six callers — objectItem,
# collectObjReg, collectObjDflts, collectObjVarargs and the two `given … with` collectors — so a
# stop condition that is one token too eager silently unregisters real object members. The bodied
# rows below (plain, `extends`, and `given … with`) are those callers' shapes.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/bodyless-obj.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a bodyless top-level object does not eat what follows it"
ssc_usable_or_skip f-bodyless-object-gate "$ssc"

run_front() { # $1 front (legacy|F), $2 file → first line of output (stderr folded in)
  if [[ "$1" == legacy ]]; then
    SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$2" 2>&1 | head -1
  else
    SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$2" 2>&1 | head -1
  fi
}

# $1 name, $2 expected first line, $3 source — asserted on BOTH fronts. The reference front is the
# control that the source is well-formed: a row that is red on both is a bad row, not a finding.
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

# ── the three symptoms ───────────────────────────────────────────────────────────────────────────

# No brace anywhere after the object: the rest of the file was discarded outright.
both bodyless-plain 3 'object A
def h(): Int = 3
def main(): Unit = println(h())'

# The first brace is the one the LAYOUT opens for a continued def body, several lines down.
both bodyless-eats-to-a-layout-brace 3 'object A
def h(): Int =
  3
def main(): Unit = println(h())'

# The first brace belongs to a LATER object, so everything between was swallowed.
both bodyless-eats-to-a-later-object 3 'object A
def h(): Int = 3
object C:
  def k(): Int = 7
def main(): Unit = println(h())'

# The misattribution symptom: B is declared and called, and only the bodyless A above it is new.
both bodyless-does-not-steal-members 2 'object A
object B:
  def g(): Int = 2
def main(): Unit = println(B.g())'

# `extends` is NOT required — this row and `bodyless-plain` differ only by the clause.
both bodyless-with-extends 3 'trait T
object A extends T
def h(): Int = 3
def main(): Unit = println(h())'

# THE SHAPE THAT REACHED THE CORPUS, and the one that shows `case object` is only HALF immune.
# `objectItem` never sees a `case object` — `parseTopItem` routes it to `caseObjectItem` — but the
# four raw-token collectors do not parse, they scan for the WORD `object`, and in `case object A`
# that word is one token along. So collectObjReg reads a bodyless `object A`, runs to `object
# Parser`'s brace, and files Parser's members under A. This is `std/parsing/core.ssc` in five lines:
# `case object NoContext extends ParserContext` sits directly in front of `object Parser`.
both case-object-before-object 42 'trait Ctx
case object NoContext extends Ctx
object Parser:
  def go(): Int = 42
def main(): Unit = println(Parser.go())'

echo "── the shapes that were never broken, and the six callers that share the scan"

# Always worked: nothing follows, so there was nothing to swallow. Keeps the fix from being
# mistaken for "bodyless objects are now rejected".
both bodyless-last 3 'def h(): Int = 3
def main(): Unit = println(h())
object A'

# `case object` with nothing for the collectors to misfile: green before the fix and after it. Read
# it together with `case-object-before-object` above — this row is why "case object is immune" was
# believed, and that row is why it is false. The difference is whether a later object exists to be
# stolen from, not whether the header is a `case object`.
both case-object-bodyless 3 'trait T
case object A extends T
def h(): Int = 3
def main(): Unit = println(h())'

# A real body must still be found — the stop condition must not fire before the brace.
both object-with-body 9 'object A:
  def k(): Int = 9
def main(): Unit = println(A.k())'

both object-extends-with-body 9 'trait T
object A extends T:
  def k(): Int = 9
def main(): Unit = println(A.k())'

# `given … with` reaches objBodyToks through two more callers (collectGivenReg2, collectED3); its
# brace comes from `with`, with no `;` in between, so the new stop must not truncate it.
#
# THE FIRST DRAFT OF THIS ROW WAS RED BEFORE THE FIX, which disqualifies it as a control: it called
# the given by name (`intSized.sizeOf(1)`) and F declines that with `match: no arm for Cons/2` — an
# unrelated gap. A control has to be GREEN on the unfixed build or it cannot show the fix broke
# nothing. This shape dispatches through the extension instead, and was green before and after.
both given-with-body 'List(2, 4, 6)' 'trait Functor[F[_]]:
  extension [A](fa: F[A]) def fmap[B](f: A => B): F[B]

given listFunctor: Functor[List] with
  extension [A](fa: List[A]) def fmap[B](f: A => B): List[B] = fa.map(f)

def main(): Unit = println(List(1, 2, 3).fmap((x: Int) => x * 2))'

if [[ $fails -eq 0 ]]; then echo "✓ f-bodyless-object-gate PASSED"; exit 0; fi
echo "✗ f-bodyless-object-gate: $fails failure(s)"
exit 1
