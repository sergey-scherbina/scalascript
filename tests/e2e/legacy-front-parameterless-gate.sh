#!/usr/bin/env bash
# A parameterless def must be invoked at its mention on the LEGACY front too.
#
# tests/conformance/parameterless-def-local.ssc pins this for the DEFAULT front and is structurally
# blind to legacy: `SSC_FRONT` is not something a corpus case can set. That blindness is why the
# legacy half outlived the F fix by a day — the corpus went green while one front still answered
# `<closure>` and exited 0.
set -uo pipefail
cd "$(dirname "$0")/../.."
SSC="${SSC:-./bin/ssc}"
export SSC_NO_BUILD_CHECK=1
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fails=0

run_case() {  # <label> <expected> <source>
  local label="$1" expected="$2" src="$3" got
  printf '%s\n' "$src" > "$WORK/case.ssc"
  got="$(SSC_FRONT=legacy timeout 180 "$SSC" run "$WORK/case.ssc" 2>&1 | grep -v '^ssc: ')"
  if [ "$got" = "$expected" ]; then
    echo "  ok   $label"
  else
    echo "  FAIL $label"
    echo "    expected: $(printf '%s' "$expected" | tr '\n' '|')"
    echo "    got:      $(printf '%s' "$got" | head -3 | tr '\n' '|')"
    fails=$((fails+1))
  fi
}

echo "legacy-front-parameterless-gate  (SSC_FRONT=legacy)"

# The defect: a LOCAL parenless def mentioned bare. Before the fix this printed `<closure>` and exited
# 0 for every line — the failure mode worth a gate, because nothing about it looks like an error.
run_case "a local parameterless def is invoked at its mention" \
  "$(printf 'loc\n1\n2')" \
  "$(printf 'case class Box(v: String)\ndef main(): Unit =\n  def mk: Box = Box("loc")\n  var n = 0\n  def next: Int =\n    n = n + 1\n    n\n  println(mk.v)\n  println(next)\n  println(next)')"

# The control. Registering local parenless defs means forcing them at every mention; a change that
# forced ANY zero-argument local would break this row, and `next` printing 1 twice instead of 1 then 2
# would mean the def was memoised like a val. Both states the gate has to be able to tell apart.
run_case "control: the ()-declared spelling still takes (), and a parenless one re-evaluates" \
  "$(printf '7\n5')" \
  "$(printf 'def main(): Unit =\n  def zero(): Int = 7\n  println(zero())\n  def mk: Int = 5\n  println(mk)')"

# Top level was never broken on this front; it is here so a regression that moves the registration out
# of block scope and back to module scope is visible as THIS row going red, not as a silent widening.
run_case "control: the top-level case still works" \
  "$(printf '9')" \
  "$(printf 'def top: Int = 9\ndef main(): Unit = println(top)')"

if [ "$fails" -eq 0 ]; then echo "legacy-front-parameterless-gate: OK"; exit 0; fi
echo "legacy-front-parameterless-gate: FAIL ($fails)"; exit 1
