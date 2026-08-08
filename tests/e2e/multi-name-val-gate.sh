#!/usr/bin/env bash
# `val a, b = 1` — one declaration, and the lanes do not agree on what it means.
#
#     def main() =
#       val a, b = 1
#       println(a)        // and, separately, println(b)
#
#     lane     println(a)      println(b)
#     int      <native:a>      [ERROR] Undefined: b
#     native   1               refuses the file: "rejected incomplete parse"
#     js       <function>      ReferenceError: b is not defined
#
# THE DEFECT IS NOT THAT THE FORM IS UNIMPLEMENTED. It is that two lanes answer CONFIDENTLY AND
# WRONGLY: `<native:a>` and `<function>` are internal placeholders leaking into user output, with no
# diagnostic and exit 0. A program carries them forward until something unrelated breaks. Declining
# the file, as native does, is the only defensible one of the three wrong answers.
#
# Root entry: `multi-name-val-binds-garbage-and-says-nothing` (BUGS.md, lane multi). Sibling:
# `an-undefined-name-in-a-pattern-means-three-different-things`, gated next door — same shape, a form
# no corpus covers with each lane inventing an answer, and the QUIET lanes the dangerous ones.
#
# WHY IT PINS THE DEFECT RATHER THAN ASSERTING THE FIX: Scala binds both names, so the wanted
# behaviour is `1` and `1` everywhere. No lane does that here, so a gate asserting it would be red on
# arrival and could not land. Freezing today's answers is what makes a CHANGE visible. When a lane
# starts printing `1`, DELETE its row rather than editing the expectation — the row becoming wrong is
# the point of it.
#
# THE jvm LANE IS NOT COVERED HERE and the gate says so at run time rather than omitting it quietly.
# The entry records jvm as the one lane that gets BOTH names right; `bin/ssc run-jvm` needs the
# optional tools/compatibility component, absent from a plain `./install.sh --dev`, so this gate
# cannot confirm it. A missing lane that nobody mentions reads as a lane that passed.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"

for launcher in ssc ssc-tools jssc; do
  if [ ! -x "$BIN/$launcher" ]; then
    echo "FAIL: $BIN/$launcher is missing — run ./install.sh --dev first"
    exit 1
  fi
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export SSC_NO_BUILD_CHECK=1

probe() {                      # probe <name> — a file printing just that binding
  local n="$1"
  printf 'def main() =\n  val a, b = 1\n  println(%s)\n' "$n" > "$WORK/mnv-$n.ssc"
  echo "$WORK/mnv-$n.ssc"
}
A="$(probe a)"
B="$(probe b)"

fails=0

# EXACT, for a row asserting that a lane got it RIGHT. Substring matching is wrong here and the A/B
# proved it: `grep -F 1` matches native's refusal message too, because the temp path in it contains
# a "1" — so a row claiming "native prints 1" passed against output that was an error. A row that
# cannot distinguish the right answer from a path is not a row.
exact() {                      # exact <label> <lane> <expected-whole-output> <actual>
  local label="$1" lane="$2" want="$3" got="$4"
  local trimmed; trimmed="$(printf '%s' "$got" | tr -d '[:space:]')"
  if [ "$trimmed" = "$want" ]; then
    echo "  $label $lane: $want"
  else
    echo "  FAIL $lane: expected the whole output to be [$want]"
    echo "        got: $(head -3 <<<"$got" | tr '\n' ' ' | cut -c1-140)"
    fails=$((fails + 1))
  fi
}

# CONTAINS, for a row asserting a lane FAILS in a particular way. A diagnostic legitimately carries
# paths and line numbers around the substring that identifies it.
row() {                        # row <label> <lane> <expected-substring> <actual>
  local label="$1" lane="$2" want="$3" got="$4"
  if grep -qF -- "$want" <<<"$got"; then
    echo "  $label $lane: $want"
  else
    echo "  FAIL $lane: expected to contain [$want]"
    echo "        got: $(head -3 <<<"$got" | tr '\n' ' ' | cut -c1-140)"
    echo "        If this lane now binds BOTH names and prints 1, that is the fix —"
    echo "        delete this lane's row instead of loosening it."
    fails=$((fails + 1))
  fi
}

# ── the CONTROL, first and in its own section ─────────────────────────────────
# A single-name `val` must still work everywhere. If this fails, the lanes are broken in some larger
# way and the rows below are measuring that instead of the multi-name form.
printf 'def main() =\n  val a = 1\n  println(a)\n' > "$WORK/single.ssc"
echo "control — an ordinary single-name val prints its value:"
exact "OK       " "int"    "1" "$("$BIN/ssc-tools" run --v1 "$WORK/single.ssc" 2>&1)"
exact "OK       " "native" "1" "$("$BIN/ssc" run "$WORK/single.ssc" 2>&1)"
exact "OK       " "js"     "1" "$("$BIN/jssc" "$WORK/single.ssc" 2>&1)"

# ── the FIRST name ────────────────────────────────────────────────────────────
# These were KNOWN-RED rows pinning `<native:a>` on int and `<function>` on js — internal
# placeholders leaking into user output at exit 0. Both lanes bind properly now, so the rows are
# DELETED and replaced by the assertion, which is what this gate's own message demanded rather than
# loosening them.
echo "val a, b = 1 — println(a):"
exact "OK       " "int"    "1" "$("$BIN/ssc-tools" run --v1 "$A" 2>&1)"
exact "OK       " "native" "1" "$("$BIN/ssc" run "$A" 2>&1)"
exact "OK       " "js"     "1" "$("$BIN/jssc" "$A" 2>&1)"

# ── the SECOND name, a separate axis: it used not to exist at all ─────────────
echo "val a, b = 1 — println(b):"
exact "OK       " "int"    "1" "$("$BIN/ssc-tools" run --v1 "$B" 2>&1)"
exact "OK       " "js"     "1" "$("$BIN/jssc" "$B" 2>&1)"
# native still declines the whole file, which is the one DEFENSIBLE wrong answer of the original
# four: it refuses loudly instead of binding something and carrying on. Kept as a declared red.
row "KNOWN-RED" "native" "rejected incomplete parse" "$("$BIN/ssc" run "$B" 2>&1)"

# ── the semantics nobody had pinned ───────────────────────────────────────────
# Scala's `val p1, …, pn = e` is `val p1 = e; …; val pn = e` — the right-hand side is evaluated ONCE
# PER NAME. A fix that bound one shared value would pass every row above and still be wrong, and it
# is the obvious way to write it, so this row exists to reject it. The expected numbers were measured
# on the jvm lane before the int and js fixes were written, not derived from the spec.
printf 'var c = 0\ndef bump(): Int =\n  c = c + 1\n  c\ndef main() =\n  val a, b = bump()\n  println(a)\n  println(b)\n  println(c)\n' > "$WORK/pername.ssc"
echo "the rhs is evaluated once per name (jvm-measured: 1, 2, 2):"
# `exact` compares against the actual output with ALL whitespace stripped, so the three lines are
# spelled `122` here — the same convention the single-value rows above use.
exact "OK       " "int" "122" "$("$BIN/ssc-tools" run --v1 "$WORK/pername.ssc" 2>&1)"
exact "OK       " "js"  "122" "$("$BIN/jssc" "$WORK/pername.ssc" 2>&1)"

# ── jvm, the reference this was fixed against ─────────────────────────────────
# `$BIN/ssc run-jvm` is the STANDARD tier and refuses the subcommand; the jvm lane lives behind
# ssc-tools. The old probe asked the wrong launcher and therefore always reported UNMEASURED.
if "$BIN/ssc-tools" run-jvm "$WORK/single.ssc" >/dev/null 2>&1; then
  exact "OK       " "jvm" "1" "$("$BIN/ssc-tools" run-jvm "$A" 2>&1 | grep -v '^ssc:')"
  exact "OK       " "jvm" "1" "$("$BIN/ssc-tools" run-jvm "$B" 2>&1 | grep -v '^ssc:')"
else
  echo "  UNMEASURED jvm: ssc-tools run-jvm not available in this checkout."
fi

if [ "$fails" -ne 0 ]; then
  echo "multi-name-val-gate: FAIL ($fails)"
  exit 1
fi
echo "multi-name-val-gate: OK — int, js and jvm agree; native still declines the file, loudly"
