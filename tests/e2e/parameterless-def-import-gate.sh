#!/usr/bin/env bash
# A parameterless def must still be a parameterless def after it crosses a module import.
#
# This gate exists because the single-file case cannot see the defect it guards. A module import
# runs the imported file in a CHILD Interpreter and the section path `copy`s every function it
# binds, so two implementations that passed every single-file shape still handed the importing
# program a closure: one kept the flag in a non-constructor `var` (dropped by `copy`), the other in
# an interpreter-owned registry (not shared with the child). Only the two-file shape says so.
# See BUGS.md parameterless-def-diverges-native-vs-interp.
set -uo pipefail
cd "$(dirname "$0")/../.."
SSC_TOOLS="${SSC_TOOLS:-./bin/ssc-tools}"
export SSC_NO_BUILD_CHECK=1
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fails=0

run_case() {  # <label> <expected-lines> <lib-body> <main-body>
  local label="$1" expected="$2" lib="$3" main="$4"
  printf '# lib\n\n```scalascript\n%s\n```\n' "$lib"  > "$WORK/lib.ssc"
  printf '# main\n\n[mk, toFn, Box](lib.ssc)\n\n```scalascript\n%s\n```\n' "$main" > "$WORK/main.ssc"
  local got
  got="$(timeout 180 "$SSC_TOOLS" run --v1 "$WORK/main.ssc" 2>&1 | grep -v '^ssc:')"
  if [ "$got" = "$expected" ]; then
    echo "  ok   $label"
  else
    echo "  FAIL $label"
    echo "    expected: $(printf '%s' "$expected" | tr '\n' '|')"
    # capped: the interpreter prints a ~50-frame stack on this failure, and a gate that dumps it
    # buries the row that failed under the rows that did not.
    echo "    got:      $(printf '%s' "$got" | head -3 | tr '\n' '|')"
    fails=$((fails+1))
  fi
}

echo "parameterless-def-import-gate"

# 1+2. The defect: an imported `def mk: Box` mentioned bare, and an imported parameterless def whose
#      result is a function, so the mention and the application are two separate steps.
run_case "imported parameterless def is invoked at its mention" \
  "$(printf 'imported\n13')" \
  "$(printf 'case class Box(v: String)\ndef mk: Box = Box("imported")\ndef toFn: Int => Int = x => x + 10')" \
  "$(printf 'println(mk.v)\nprintln(toFn(3))')"

# 3. The control. Same program, same import, but the defs are declared WITH a parameter clause and
#    called with one. A change that invoked every zero-argument function it found — rather than only
#    the parameterless spelling — turns `mk()` into a call on the RESULT and this row goes red, which
#    is the state the gate has to be able to distinguish.
run_case "control: the ()-declared spelling still takes ()" \
  "$(printf 'parens\n13')" \
  "$(printf 'case class Box(v: String)\ndef mk(): Box = Box("parens")\ndef toFn(): Int => Int = x => x + 10')" \
  "$(printf 'println(mk().v)\nprintln(toFn()(3))')"

if [ "$fails" -eq 0 ]; then echo "parameterless-def-import-gate: OK"; exit 0; fi
echo "parameterless-def-import-gate: FAIL ($fails)"; exit 1
