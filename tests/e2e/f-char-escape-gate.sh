#!/usr/bin/env bash
#
# f-char-escape-gate — a Char literal whose escape has no named branch must still reach the IR as a
# CODE POINT.
#
# `escCharCode` mapped `\n`, `\t`, `\r` and `\0` to Int literals and fell through returning `e`
# unconverted — but `e` comes from `charAt`, so it is a Char. The two escapes with no branch, `'\\'`
# and `'\''`, therefore reached emitInt as characters and the literal came out `(lit (int '\'))`,
# which is not an integer:
#
#   '\\'.toInt             F  0     reference  92
#   "a\\b".indexOf('\\')   F -1     reference   1
#
# Loud through F0 — `int literal: not a canonical INT` — and SILENT through bin/ssc, which answered
# 0. It survived because every escape anyone had tested has a named branch; the two that do not are
# exactly the two that were wrong.
#
# THE FOUR NAMED ESCAPES ARE CONTROLS. They were always right, and they are what a "just call .toInt
# everywhere" change would break if the branches were removed instead of the fall-through fixed.
#
# Found by sweeping tests/conformance with the output-agreement comparison, which the gate excludes
# for runtime. `char-literal-escapes.ssc` is one of five files that sweep turned up.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-char-esc.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0
export SSC_NO_BUILD_CHECK=1

echo "── an escaped Char literal reaches the IR as a code point"
ssc_usable_or_skip f-char-escape-gate "$ssc"

# Asserts BOTH fronts: the two agreeing is the property, and the reference is the oracle for F.
both_print() {
  local name=$1 want=$2 src=$3
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  local f r
  f=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -1)
  r=$(SSC_FRONT=legacy    timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -1)
  if [[ "$f" == "$want" && "$r" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: F='$f' reference='$r', wanted '$want' from both"
    fails=$((fails + 1))
  fi
}

# The two escapes with NO named branch — the ones that were wrong.
both_print backslash-code 92 "def main(): Unit = println('\\\\'.toInt)"
both_print quote-code 39 "def main(): Unit = println('\\''.toInt)"

# The position the conformance file actually uses: a Char literal as an argument.
both_print backslash-as-argument 1 'def main(): Unit = println("a\\b".indexOf('"'"'\\'"'"'))'

echo "── controls: the four named escapes were always right"
both_print ctl-newline 10 "def main(): Unit = println('\\n'.toInt)"
both_print ctl-tab 9 "def main(): Unit = println('\\t'.toInt)"
both_print ctl-return 13 "def main(): Unit = println('\\r'.toInt)"
both_print ctl-nul 0 "def main(): Unit = println('\\0'.toInt)"

# A plain, unescaped Char literal goes down the other path entirely and must not change.
both_print ctl-plain-char 97 "def main(): Unit = println('a'.toInt)"

# ── the corpus file this came from ───────────────────────────────────────────────────────────────
echo "── the conformance file the sweep turned up"
conf="$ROOT/tests/conformance/char-literal-escapes.ssc"
if [[ -f "$conf" ]]; then
  f=$(SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$conf" 2>&1 | head -8)
  r=$(SSC_FRONT=legacy    timeout 300 "$ssc" run "$conf" 2>&1 | head -8)
  if [[ "$f" == "$r" ]]; then
    echo "  ✓ char-literal-escapes: both fronts agree"
  else
    echo "  ✗ char-literal-escapes: the fronts disagree"
    diff <(printf '%s\n' "$f") <(printf '%s\n' "$r") | head -6 | sed 's/^/      /'
    fails=$((fails + 1))
  fi
else
  echo "  SKIP char-literal-escapes: not present"
fi

if [[ $fails -eq 0 ]]; then echo "✓ f-char-escape-gate PASSED"; exit 0; fi
echo "✗ f-char-escape-gate: $fails failure(s)"
exit 1
