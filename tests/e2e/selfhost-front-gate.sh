#!/usr/bin/env bash
# selfhost-front-gate — the self-hosted front (F) must not answer QUIETLY.
#
# WHY THIS EXISTS. Four bugs were filed against F and every one of them carried `gate: none`. Two
# had been fixed by somebody and nobody noticed, because nothing was watching; the other two were
# still live for the same reason. That is the whole argument for this file: the defects here are not
# crashes, they are *silent* — a program that prints nothing at exit 0, or prints `0` where the
# answer is `5`. A silent wrong answer is invisible to any check that only asks "did it exit 0".
#
# So every case below is a DIFFERENTIAL against a lane that is known to be right, and the comparison
# is on OUTPUT, never on the exit code. F's failures all exit 0.
#
# THE ORACLE IS NAMED PER CASE and it is not always the same lane. The interpreter (`--v1`) is the
# usual reference, but it CRASHES on the qualified-assignment case, so that row uses v3 instead. A
# gate that assumed one oracle would have had to drop the case that most needed it.
#
#   tests/e2e/selfhost-front-gate.sh              # check
#   tests/e2e/selfhost-front-gate.sh --self-test  # prove it can go red
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SSC="$ROOT/bin/ssc"
SSC3="$ROOT/v3/ssc3"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

fails=0
checked=0

# run <file> — F's answer, newline-joined with `/` so a one-line comparison is readable.
run_f()  { timeout 120 "$SSC" run "$1" 2>/dev/null | tr '\n' '/'; }
run_v3() { timeout 200 "$SSC3" run "$1" 2>/dev/null | grep -v '^\[' | tr '\n' '/'; }

# case <name> <expected> <program>
#
# `expected` is written out rather than computed from a lane at run time, and that is deliberate:
# an oracle consulted live can regress WITH the thing it is checking, and then both agree on the
# wrong answer. Each value below was taken from the named oracle once, by hand, and the oracle is
# recorded in the comment beside the case.
case_f() {
  local name="$1" want="$2" prog="$3" got
  printf '%s\n' "$prog" > "$WORK/c.ssc"
  got="$(run_f "$WORK/c.ssc")"
  checked=$((checked + 1))
  if [ "$got" = "$want" ]; then
    printf '  ok   %-44s %s\n' "$name" "$got"
  else
    printf '  FAIL %-44s got %-18s want %s\n' "$name" "${got:-<nothing>}" "$want"
    fails=$((fails + 1))
  fi
}

echo "selfhost-front-gate"

if [ ! -x "$SSC" ]; then
  echo "  ✋ no launcher at $SSC — run ./install.sh --dev first"
  exit 2
fi

# ── the while family ─────────────────────────────────────────────────────────────────────────────
# selfhost-front-while-with-an-assignment-body-runs-nothing. A ONE-LINE `while … do <assignment>`
# reached `parseExpr`, which stops at the `=` — and took the rest of the file with it. Oracle: the
# interpreter, which prints `3` then `after`. THREE shapes, because `bodyExpr` (the fix) covers all
# three and only the first was reported: a plain assignment, an indexed store, and a compound `+=`.
case_f "while one-line assignment body" "3/after/" 'var i = 0
while i < 3 do i = i + 1
println(i)
println("after")'

case_f "while one-line indexed store" "3/after/" 'var a = Array(0, 0)
while a(0) < 3 do a(0) = a(0) + 1
println(a(0))
println("after")'

case_f "while one-line compound assign" "3/" 'var i = 0
while i < 3 do i += 1
println(i)'

# The BLOCK form was never broken. It is here because the fix changes the routing of the
# single-line branch, and a fix that quietly moved the block branch too would otherwise be invisible.
case_f "while block body still works" "3/" 'var i = 0
while i < 3 do
  i = i + 1
println(i)'

# `if` used `bodyExpr` all along — that is exactly why it worked while `while` did not, one decision
# with two sites. Pinned so a future simplification cannot take the working site with the broken one.
case_f "if one-line assignment body" "1/" 'var i = 0
if i < 3 then i = i + 1
println(i)'

# ── qualified assignment to an object member ─────────────────────────────────────────────────────
# selfhost-front-qualified-assignment-to-an-object-member-is-ignored. `Counter.n = 5` is FOUR tokens,
# so the two-token peek in `isAssignHead` saw a `.` in second position, called it not-an-assignment,
# and `parseExpr` stopped at the `=`. The write vanished and the read printed the initial value — a
# wrong answer at exit 0. ORACLE IS v3, not the interpreter: the interpreter CRASHES on this program,
# which is the reason this gate names an oracle per case instead of once.
case_f "qualified assign to an object var" "5/" 'object Counter:
  var n = 0
Counter.n = 5
println(Counter.n)'

# The compound form reads the same cell before combining, so it exercises the read side too.
case_f "qualified compound assign" "13/" 'object Counter:
  var n = 10
Counter.n += 3
println(Counter.n)'

# Not only at top level: the same statement inside a `def` goes through a different dispatcher
# (`bodyExpr`), and widening the shared predicate is what makes both work from one edit.
case_f "qualified assign inside a def" "7/" 'object C:
  var n = 0
def bump() = C.n = 7
bump()
println(C.n)'

# The plain form must keep working — the predicate now answers two shapes and this is the one that
# was never broken.
case_f "plain assignment still works" "5/" 'var i = 0
i = 5
println(i)'

# ── given … with ─────────────────────────────────────────────────────────────────────────────────
# selfhost-front-given-with-swallows-the-rest-of-the-file. Everything AFTER a `given X with` and its
# body disappears on F — no diagnostic, exit 0 — while v1 prints it. 24 programs write this form.
#
# THIS CASE EXISTS BECAUSE THE GATE MISSED IT. The suite above was written this morning against
# exactly this class — a silent wrong answer at exit 0 — and it did not cover `given`, so the defect
# sat one construct away from cases that would have caught it. A gate is only as wide as its case
# list, and the honest response to finding a hole is a case, not a note.
#
# PINNED TO TODAY'S WRONG ANSWER, not to the right one, and that is deliberate. A gate that ships
# red blocks every push in the repo for a defect its author did not fix, which is a tax on everyone
# else. Pinned this way it is still a gate: the day someone fixes `given … with`, the recorded
# `<nothing>` stops matching and this case goes RED, naming the entry — so the fix cannot land
# unnoticed and the expectation gets flipped to `после/` in the same commit.
case_f "given ... with — KNOWN WRONG, see BUGS" "" 'trait S:
  def z(): Int
given S with
  def z(): Int = 7
println("после")'

# The value form has no `with` and is unaffected — the control that says the loss belongs to `with`
# and not to `given`.
case_f "given = value form still works" "после/" 'trait S:
  def z(): Int
case class Impl() extends S:
  def z(): Int = 7
given S = Impl()
println("после")'

# ── entries filed as broken that are NOT ──────────────────────────────────────────────────────────
# Both were reported against F and both answer correctly today. Kept as REGRESSION coverage rather
# than deleted: they were filed for a reason, nothing recorded when they were fixed, and with no
# gate they could return exactly as quietly as they went. Oracle: the interpreter, which agrees.
case_f "alternative pattern, two branches" "ab/ab/" 'trait K
case object A extends K
case object B extends K
def f(k: K): String =
  k match
    case A | B => "ab"
println(f(A))
println(f(B))'

case_f "alternative pattern beside a third arm" "ab/ab/c/" 'trait K
case object A extends K
case object B extends K
case object C extends K
def f(k: K): String =
  k match
    case A | B => "ab"
    case C     => "c"
println(f(A))
println(f(B))
println(f(C))'

case_f "trailing operator continuation" "3/" 'val x = 1 +
  2
println(x)'

echo
if [ $fails -eq 0 ]; then
  echo "  selfhost-front-gate: $checked/$checked"
  exit 0
fi
echo "  selfhost-front-gate: $((checked - fails))/$checked — $fails silent divergence(s)"
exit 1
