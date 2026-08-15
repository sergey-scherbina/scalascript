#!/usr/bin/env bash
#
# f-placeholder-ctor-gate — an underscore placeholder works in a CONSTRUCTOR application and under a
# prefix operator, as it already did in a plain call.
#
# THE DEFECT, measured 2026-08-15. `isAppHeadPh` tested the call head for kind 1 — a LOWERCASE
# identifier — so a constructor, which lexes as kind 3, never reached the placeholder wrapper and its
# `_` fell through as a bare name. The identical def call was fine, which is what made it look like a
# placeholder defect rather than a routing one:
#
#     g(_)        def   fine        Some(_)    ctor   unbound global: (global _)
#     g(_, 10)    def   fine        P(_, 9)    ctor   unbound global: (global _)
#
# A leading prefix `!` hid the head from the same test, so `filter(!composite(_))` fell through too.
#
# SCALA SCOPES A PLACEHOLDER TO THE SMALLEST ENCLOSING EXPRESSION, and that is the whole risk in
# widening this test. `xs.map(f(g(_)))` must scope to `g(_)`, NOT to the outer call — wrapping the
# outside would change what the program means, silently. The existing guard requires every
# placeholder to be a DIRECT argument of a single leading application, and `nested-call-scopes-inner`
# below is the row that fails if a widening breaks that.
#
# TWO CORPUS FILES: `tests/conformance/dsl-multi-pass.ssc` (`env.get(name).map(Right(_))`) and
# `examples/wasm-primes.ssc` (`(2 to limit).filter(!composite(_))`).
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/ph-ctor.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a placeholder in a constructor, and under a prefix operator"
ssc_usable_or_skip f-placeholder-ctor-gate "$ssc"

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

both ctor-bare-placeholder 'List(Some(1), Some(2))' 'def main(): Unit = println(List(1, 2).map(Some(_)))'

both ctor-placeholder-and-arg 'List(P(1, 9), P(2, 9))' 'case class P(a: Int, b: Int)
def main(): Unit = println(List(1, 2).map(P(_, 9)))'

both prefix-not-placeholder 'List(1)' 'def isC(n: Int): Boolean = n > 2
def main(): Unit = println(List(1, 3, 5).filter(!isC(_)))'

# ── the scoping rule the widening must not break ─────────────────────────────────────────────────
#
# The placeholder belongs to the INNER call. If the wrap were applied to the outer one the program
# would still compile and would answer differently — the failure mode this row exists for.

both nested-call-scopes-inner 'List(2, 3)' 'def inc(n: Int): Int = n + 1
def id(f: Int => Int): Int => Int = f
def main(): Unit = println(List(1, 2).map(id(inc(_))))'

# ── the shapes that already worked ───────────────────────────────────────────────────────────────

both def-bare-placeholder 'List(2, 3)' 'def g(a: Int): Int = a + 1
def main(): Unit = println(List(1, 2).map(g(_)))'

both def-placeholder-and-arg 'List(11, 12)' 'def g(a: Int, b: Int): Int = a + b
def main(): Unit = println(List(1, 2).map(g(_, 10)))'

both shallow-placeholder 'List(2, 4)' 'def main(): Unit = println(List(1, 2).map(_ * 2))'

both two-placeholders 3 'def main(): Unit = println(List((1, 2)).map((p: (Int, Int)) => p._1 + p._2).head)'

if [[ $fails -eq 0 ]]; then echo "✓ f-placeholder-ctor-gate PASSED"; exit 0; fi
echo "✗ f-placeholder-ctor-gate: $fails failure(s)"
exit 1
