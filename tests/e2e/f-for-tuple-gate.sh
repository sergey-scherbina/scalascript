#!/usr/bin/env bash
#
# f-for-tuple-gate — a for-comprehension generator may bind a TUPLE.
#
# THE DEFECT, measured 2026-08-15:
#
#   for (name, grade) <- names.zip(grades) do
#     println(name + ":" + grade)
#
#   F: unbound global: (global grade)     ref: a:90
#
# The binder used to be one NAME, and `skipForOpen` skipped a leading `(` unconditionally because
# `for (n <- xs)` may put the whole generator in parens. So a tuple pattern lost its own paren,
# `name` became the entire binder, and everything after it desynchronised.
#
# THE TWO FORMS SEPARATE ONE TOKEN LATER, which is the whole fix: `( ident <-` is a parenthesised
# generator and `( ident ,` is a tuple pattern. `paren-generator` below is the row that fails if that
# distinction is lost again — it is the shape the old code was written for, and the one a careless
# fix breaks while making the tuple case pass.
#
# THE BINDER IS NOW A LIST, used in exactly the two places it always was: pushed onto the env and
# wrapped by the lambda. `forTupNames` accumulates by PREPENDING, which yields ["b","a"] for
# `(a, b)` — already the order F's ctor arms use — and `forLamN` emits the destructuring lambda
# through `genPairArm`, reusing the Pair/Tuple2 duality the ordered resolver encodes rather than
# inventing a second one. `tuple-field-order` is what proves the order is right rather than
# symmetric-and-therefore-untested: its two fields have different values AND different types.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/for-tuple.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a for generator may bind a tuple"
ssc_usable_or_skip f-for-tuple-gate "$ssc"

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

both tuple-do a:90 'def main(): Unit =
  for (n, g) <- List("a").zip(List(90)) do
    println(n + ":" + g)'

both tuple-yield 'List(3)' 'def main(): Unit = println(for (a, b) <- List((1, 2)) yield a + b)'

# The fields are ordered AND typed differently, so a reversed binding cannot pass by symmetry.
both tuple-field-order 'x=1' 'def main(): Unit =
  for (s, n) <- List(("x", 1)) do
    println(s + "=" + n)'

both tuple-with-guard b 'def main(): Unit =
  for (s, n) <- List(("a", 1), ("b", 2)) if n > 1 do
    println(s)'

# ── the shapes that must not move ────────────────────────────────────────────────────────────────
#
# `paren-generator` is the reason the fix reads one token further instead of just keeping the paren:
# the old code skipped it for exactly this form.

both paren-generator 1 'def main(): Unit =
  for (n <- List(1, 2)) do
    println(n)'

both single-binder 1 'def main(): Unit =
  for n <- List(1, 2) do
    println(n)'

both single-binder-yield 'List(2, 3)' 'def main(): Unit = println(for n <- List(1, 2) yield n + 1)'

both nested-generators 'List(11, 12)' 'def main(): Unit = println(for a <- List(1, 2); b <- List(10) yield a + b * 1 + (a - a))'

if [[ $fails -eq 0 ]]; then echo "✓ f-for-tuple-gate PASSED"; exit 0; fi
echo "✗ f-for-tuple-gate: $fails failure(s)"
exit 1
