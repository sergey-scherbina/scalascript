#!/usr/bin/env bash
#
# f-guarded-arm-gate — a nested pattern may carry a GUARD, in both spellings.
#
# THE DEFECT, measured 2026-08-16, in two cells of the same matrix:
#
#   (left, right) match
#     case (ah :: at, bh :: _) if ah <= bh => println("left head " + ah)
#     case (_, bh :: bt) => println("right head " + bh)
#
#   F: unbound global: (global at)   ref: right head 2      <- tuple-first: a DECLINE
#
#   Some(List(1, 2)) match
#     case Some(h :: t) if h > 0 => println("pos " + h)
#     case _ => println("no")
#
#   F: no                            ref: pos 1             <- ctor-first: a WRONG ANSWER
#
# The tuple-first spelling sent a guarded arm to the var fallback, which reads the leading `(` as a
# variable NAME, so the binders desynchronised and the file declined. The ctor-first spelling sent it
# to `parseGenCtor`, which understands guards but not nesting — and that one answered, wrongly, with
# exit 0 and no diagnostic. Removing the guard made both compile, which is what identified the guard
# rather than the nesting as the trigger.
#
# WHY THE PAIRS. Every guard row here comes twice, TRUE and FALSE, because each direction alone is
# satisfied by a different bug: a front that ignores the guard passes every FALSE row, and a front
# that treats it as always-false passes every TRUE row. The ctor-first FALSE case is the sharper
# lesson — both the broken and the fixed front answer `no` there, so the row that caught the silent
# wrong answer is the TRUE one and nothing else could have.
#
# WHERE THE GUARD GOES, since a wrong choice here is a wrong ANSWER rather than a refusal: `ah` and
# `at` are bound by the nested cons pattern, which `dischargeF` binds only INSIDE the match it emits.
# So the guard is parsed at the innermost success scope — the same one the body is lowered at — and
# its false branch re-lowers the remaining arms at the FAIL scope of that same depth, which is what
# `accFailF` computes as the exact dual of `accScopeF`. `guard-reads-the-tail` is the row that fails
# if the scope is taken from the wrong depth: `at` is the binder the original defect reported unbound.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-guarded-arm.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a nested pattern may carry a guard, in both spellings"
ssc_usable_or_skip f-guarded-arm-gate "$ssc"

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

# ── tuple-first: the decline ─────────────────────────────────────────────────────────────────────

both tuple-guard-false 'right 2' 'def main(): Unit =
  (List(5), List(2, 8)) match
    case (ah :: at, bh :: _) if ah <= bh => println("left " + ah)
    case (_, bh :: bt) => println("right " + bh)'

both tuple-guard-true 'left 1' 'def main(): Unit =
  (List(1), List(9, 8)) match
    case (ah :: at, bh :: _) if ah <= bh => println("left " + ah)
    case (_, bh :: bt) => println("right " + bh)'

# `at` is the name the original defect reported unbound — this row fails if the guard is parsed at
# any scope but the innermost one.
both guard-reads-the-tail 'tail ok 1' 'def main(): Unit =
  (List(1, 2), List(9)) match
    case (ah :: at, bh :: _) if at == List(2) => println("tail ok " + ah)
    case _ => println("no")'

both guard-reads-both-sides 'sum 7' 'def main(): Unit =
  (List(3, 7), List(4)) match
    case (ah :: at, bh :: _) if ah + bh == 7 => println("sum " + (ah + bh))
    case _ => println("no")'

# Two guarded arms in a row: the first arm's false branch must reach the SECOND arm, not the default.
both chained-guards gt 'def main(): Unit =
  (List(5), List(2)) match
    case (ah :: _, bh :: _) if ah < bh => println("lt")
    case (ah :: _, bh :: _) if ah > bh => println("gt")
    case _ => println("eq")'

both all-guards-false eq 'def main(): Unit =
  (List(2), List(2)) match
    case (ah :: _, bh :: _) if ah < bh => println("lt")
    case (ah :: _, bh :: _) if ah > bh => println("gt")
    case _ => println("eq")'

# ── ctor-first: the SILENT WRONG ANSWER ──────────────────────────────────────────────────────────
#
# This is the pair that matters most. The broken front answered `no` for both, so only the TRUE row
# separates a working guard from an ignored one — and the failure it guards is a wrong answer with
# exit 0, which nothing downstream would have questioned.

both ctor-guard-true 'pos 1' 'def main(): Unit =
  Some(List(1, 2)) match
    case Some(h :: t) if h > 0 => println("pos " + h)
    case _ => println("no")'

both ctor-guard-false no 'def main(): Unit =
  Some(List(-1, 2)) match
    case Some(h :: t) if h > 0 => println("pos " + h)
    case _ => println("no")'

both ctor-guard-reads-tail 'tail 2' 'def main(): Unit =
  Some(List(1, 2)) match
    case Some(h :: t) if t == List(2) => println("tail 2")
    case _ => println("no")'

# ── the shapes that must not move ────────────────────────────────────────────────────────────────

both tuple-unguarded 'left 5' 'def main(): Unit =
  (List(5), List(2, 8)) match
    case (ah :: at, bh :: _) => println("left " + ah)
    case _ => println("no")'

both ctor-unguarded 'head 1' 'def main(): Unit =
  Some(List(1, 2)) match
    case Some(h :: t) => println("head " + h)
    case _ => println("no")'

both flat-ctor-guard 'big 9' 'def main(): Unit =
  Some(9) match
    case Some(n) if n > 5 => println("big " + n)
    case _ => println("small")'

both flat-ctor-guard-false small 'def main(): Unit =
  Some(1) match
    case Some(n) if n > 5 => println("big " + n)
    case _ => println("small")'

if [[ $fails -eq 0 ]]; then echo "✓ f-guarded-arm-gate PASSED"; exit 0; fi
echo "✗ f-guarded-arm-gate: $fails failure(s)"
exit 1
