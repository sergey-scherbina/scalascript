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
echo "val a, b = 1 — println(a):"
int_a="$("$BIN/ssc-tools" run --v1 "$A" 2>&1)"
row "KNOWN-RED" "int"    "<native:a>" "$int_a"
exact "OK       " "native" "1"          "$("$BIN/ssc" run "$A" 2>&1)"
js_a="$("$BIN/jssc" "$A" 2>&1)"
row "KNOWN-RED" "js"     "<function>" "$js_a"

# ── the SECOND name, a separate axis: it may not exist at all ─────────────────
echo "val a, b = 1 — println(b):"
row "KNOWN-RED" "int"    "Undefined: b"            "$("$BIN/ssc-tools" run --v1 "$B" 2>&1)"
row "KNOWN-RED" "native" "rejected incomplete parse" "$("$BIN/ssc" run "$B" 2>&1)"
row "KNOWN-RED" "js"     "ReferenceError: b is not defined" "$("$BIN/jssc" "$B" 2>&1)"

# ── what actually makes this dangerous, asserted on its own ───────────────────
# The rows above would still pass if a lane started printing a placeholder AND reporting failure.
# The defect is the silence: a wrong value with exit 0 is what travels.
echo "the silence — a wrong value must not arrive with a success status:"
"$BIN/ssc-tools" run --v1 "$A" >/dev/null 2>&1; int_rc=$?
"$BIN/jssc" "$A" >/dev/null 2>&1; js_rc=$?
for pair in "int:$int_rc" "js:$js_rc"; do
  lane="${pair%%:*}"; rc="${pair##*:}"
  if [ "$rc" -eq 0 ]; then
    echo "  KNOWN-RED $lane: exit 0 while printing a placeholder"
  else
    echo "  NOTE $lane now exits $rc — it reports the failure instead of hiding it."
    echo "       That is an improvement; re-read the entry and update this gate deliberately."
    fails=$((fails + 1))
  fi
done

# ── jvm, named rather than omitted ────────────────────────────────────────────
if "$BIN/ssc" run-jvm "$WORK/single.ssc" >/dev/null 2>&1; then
  echo "  NOTE run-jvm is available here — the entry records jvm as the only lane that gets BOTH"
  echo "       names right, and this gate does not yet check it. Worth adding now that it can run."
else
  echo "  UNMEASURED jvm: run-jvm needs the optional tools/compatibility component (not installed)."
  echo "             The entry records jvm as the only correct lane; this gate cannot confirm it."
fi

if [ "$fails" -ne 0 ]; then
  echo "multi-name-val-gate: FAIL ($fails)"
  exit 1
fi
echo "multi-name-val-gate: OK — three lanes, three answers, all still as recorded"
