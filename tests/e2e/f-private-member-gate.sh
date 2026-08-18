#!/usr/bin/env bash
#
# f-private-member-gate — a member may carry MODIFIERS, and F used to discard it when it did.
#
# THE DEFECT, reduced to five lines:
#
#   object T:
#     private def helper(x: Int): Int = x * 2
#     val out: Int = helper(21)
#
#   F: unbound global: (global helper)   ref: 42     — and dropping `private` makes F answer 42
#
# `collectOM` skipped exactly one modifier, `override`. Everything else fell through to
# `collectOM(skipStmt(ts))`, which DISCARDS the whole member: its name was never registered, so a
# sibling calling it emitted a bare global and the file declined.
#
# THE CENSUS WAS TAKEN BEFORE THE FIX, not one cell at a time: `private def`, `protected def`,
# `final def`, `private[this] def` and `private val` all lost the member; a plain `def` did not. And
# `collectMD` — the same job for CLASS methods — carried the identical `override`-only branch, found
# by grepping for the second decision site rather than waiting for it to be reported. `class-method-*`
# is that site.
#
# WHAT THIS DELIBERATELY DOES NOT FIX, because it is a different cause and the rows say so: an object
# `val` read by a sibling is unbound with or without a modifier — `val base` + `val out = base * 2`
# fails identically on a build WITHOUT this change, verified by reverting and rebuilding. Only `def`
# members resolve. `examples/std-ui/theme.ssc` shows both in one file: its reason moved from
# `(global declarations)` (a private def — fixed here) to `(global lightTokens)` (a val — not).
# Filed separately; `object-val-still-unbound` records the state so the day it changes is visible.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-private-member.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a member may carry modifiers"
ssc_usable_or_skip f-private-member-gate "$ssc"

both() {
  local name=$1 want=$2 src=$3 f r
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  r=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$sandbox/$name.ssc" < /dev/null 2>&1 | head -1)
  f=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" < /dev/null 2>&1 | head -1)
  if [[ "$r" == "$want" && "$f" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: reference='$r' F='$f', wanted '$want' from both"
    fails=$((fails + 1))
  fi
}

# ── the modifier matrix, on an object ────────────────────────────────────────────────────────────

both private-def 42 'object T:
  private def helper(x: Int): Int = x * 2
  val out: Int = helper(21)

def main(): Unit = println(T.out)'

both protected-def 42 'object T:
  protected def helper(x: Int): Int = x * 2
  val out: Int = helper(21)

def main(): Unit = println(T.out)'

both final-def 42 'object T:
  final def helper(x: Int): Int = x * 2
  val out: Int = helper(21)

def main(): Unit = println(T.out)'

# The QUALIFIED form: `skipGen` skips the bracketed clause and leaves the stream alone when absent.
both private-qualified-def 42 'object T:
  private[this] def helper(x: Int): Int = x * 2
  val out: Int = helper(21)

def main(): Unit = println(T.out)'

both override-still-works 42 'object T:
  override def helper(x: Int): Int = x * 2
  val out: Int = helper(21)

def main(): Unit = println(T.out)'

both plain-def-unchanged 42 'object T:
  def helper(x: Int): Int = x * 2
  val out: Int = helper(21)

def main(): Unit = println(T.out)'

# ── the second decision site: class methods ──────────────────────────────────────────────────────

# A CASE CLASS, not a plain `class`: F does not support `class C:` at all (`unbound global: (global
# C)`), so a row written on that shape would have been red for a reason with nothing to do with
# modifiers — and it was, in this gate's first draft. Verified by control: on a build WITHOUT this
# change `case class + private def` gives `unbound global: (global helper)` and the plain one gives
# 42, so this row is the one that proves the collectMD site was reached.
both case-class-method-private 42 'case class P(a: Int):
  private def helper(x: Int): Int = x * 2
  def out(): Int = helper(a)

def main(): Unit = println(P(21).out())'

both case-class-method-plain 42 'case class P(a: Int):
  def helper(x: Int): Int = x * 2
  def out(): Int = helper(a)

def main(): Unit = println(P(21).out())'

# ── the OTHER cause, fixed the same day and kept as rows ─────────────────────────────────────────
#
# An object `val` read by a sibling used to be unbound on F with or without a modifier — a DIFFERENT
# cause from the modifier defect above, verified against a build predating that fix. The row here was
# originally an assertion that it still failed, precisely so the day it changed the gate would go red
# instead of the fix landing unnoticed. It did go red, on this message: "it WORKS now — the separate
# val gap closed; delete this row." So it was deleted deliberately and replaced by these.
#
# The payload behind it: `curObj` carried (varNames, defNames) and a val was in neither. Vals emit
# `(def O_base …)` — the same shape as methods — so they belong in the def list, and `var`s
# deliberately do not: those emit a CELL and their bare reference must read it.

both object-val-from-val 42 'object T:
  val base: Int = 21
  val out: Int = base * 2

def main(): Unit = println(T.out)'

both object-val-from-def 42 'object T:
  val base: Int = 21
  def out(): Int = base * 2

def main(): Unit = println(T.out())'

both object-private-val 42 'object T:
  private val base: Int = 21
  val out: Int = base * 2

def main(): Unit = println(T.out)'

# A `var` member must still read its CELL, not a plain global — the half of the payload that was
# already right and would break if vals and vars were folded into one list.
both object-var-still-a-cell 42 'object T:
  var n: Int = 21
  def out(): Int = n * 2

def main(): Unit = println(T.out())'

if [[ $fails -eq 0 ]]; then echo "✓ f-private-member-gate PASSED"; exit 0; fi
echo "✗ f-private-member-gate: $fails failure(s)"
exit 1
