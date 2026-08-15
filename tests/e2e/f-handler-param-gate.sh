#!/usr/bin/env bash
#
# f-handler-param-gate — a curried def whose SECOND PARAMETER CLAUSE starts on a continuation line
# binds that clause's parameters.
#
# THE DEFECT, measured 2026-08-15. Four lines:
#
#   def mk(a: Int)
#         (b: Int): Int =
#     a + b
#
#     F     ssc: unbound global: (global b)
#     ref   3
#
# Put the clause on the same line and F is fine. The layout inserts a separator at the newline —
# a line beginning with `(` passes `canStartLine` and fails `isCont`, so it reads as a new statement
# — and `emitDefU` tests `hd(r)` for `(` to find the next clause, finds the separator instead, and
# falls through to the body. The clause is dropped and every use of its parameters is a free global.
#
# THE FIX IS IN THE DEF PARSER, NOT THE LAYOUT, and deliberately: making a leading `(` a
# continuation everywhere would change how every statement starting with a tuple is read. Skipping
# separators is safe HERE because we are mid-declaration — nothing can start a new statement before
# the def's `=`.
#
# EIGHT CORPUS FILES, and finding it took reducing 637 lines to four. `std/agent.ssc`,
# `std/agent-mcp.ssc` and the six `examples/rozum-agent*` / `agent-mcp*` that import them all
# reported `unbound global: (global handler)` — the name of a curried parameter written exactly this
# way. Three built-up synthetic reconstructions were GREEN, which is why this had to be reduced DOWN
# from the real file: the trigger is the line break, and a synthetic written on one line has none.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/curried-nl.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a curried clause on a continuation line binds its parameters"
ssc_usable_or_skip f-handler-param-gate "$ssc"

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

both clause-on-next-line 3 'def mk(a: Int)
      (b: Int): Int =
  a + b
def main(): Unit = println(mk(1)(2))'

# The corpus shape: a function-typed parameter in the continued clause, used in the body.
both clause-fn-param x! 'def mk(a: String)
      (h: String => String): String =
  h(a)
def main(): Unit = println(mk("x")((s: String) => s + "!"))'

# The clause AND the return type both on continuation lines — the first draft of this row used
# THREE clauses and was red on both fronts (`TYPEERR: cannot unify tuple`), which makes it a bad row
# rather than a finding: three-clause defs are a type-checker limit unrelated to this defect.
both clause-and-rettype-continued 3 'def mk(a: Int)
      (b: Int)
      : Int =
  a + b
def main(): Unit = println(mk(1)(2))'

# A `(using …)` clause reaches the same test, so it must survive the same line break.
both using-clause-continued 5 'trait M:
  def v: Int
given m: M with
  def v: Int = 5
def z(a: Int)
     (using p: M): Int =
  p.v
def main(): Unit = println(z(1))'

# ── the shapes that must not move ────────────────────────────────────────────────────────────────

both clause-same-line 3 'def mk(a: Int)(b: Int): Int = a + b
def main(): Unit = println(mk(1)(2))'

both single-clause 2 'def mk(a: Int): Int = a + 1
def main(): Unit = println(mk(1))'

# A def whose body RETURNS a function is not a curried def and its call site must stay nested —
# the distinction half 2 of `v2-front-curried-def-second-clause` exists to preserve.
both returns-a-function 7 'def mk(a: Int): Int => Int = (b: Int) => a + b
def main(): Unit = println(mk(3)(4))'

# A statement that legitimately starts with `(` after a def must still be its own statement — this
# is what a layout-level fix would have put at risk.
both tuple-statement-after-def '(1, 2)' 'def mk(a: Int): Int = a
def main(): Unit =
  val t = (1, 2)
  println(t)'

if [[ $fails -eq 0 ]]; then echo "✓ f-handler-param-gate PASSED"; exit 0; fi
echo "✗ f-handler-param-gate: $fails failure(s)"
exit 1
