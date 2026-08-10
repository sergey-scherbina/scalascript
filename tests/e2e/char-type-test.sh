#!/usr/bin/env bash
# `case _: Char` answers Char — on every lane that has a Char to answer about.
#
# THE ORACLE is `--v1`, the interpreter. specs/type-ascription-matrix.md measured all fourteen
# type-ascription answers against real Scala on the JVM and INT matched on all fourteen, so this
# gate compares against INT rather than freezing a transcript of my own expectations.
#
# WHAT WAS WRONG (measured 2026-08-10, fresh build, four lanes):
#
#   kind('c') / kind("c") / kind(99) / kind(s.charAt(0))
#     --v1        Char   String  Int  Char     <- correct
#     bin/ssc     Int    String  Int  Int
#     --bytecode  Int    String  Int  Int
#     run-js      String String  Int  other
#
# TWO different causes, which is why one fix would not have done:
#   * native/bytecode — `CharV extends IntV`, so a Char fell into the IntV arm of `__isTag__`.
#     Ordering, not a missing type: CharV became a distinct class in f39448c96.
#   * js — a Char RESULT is a `_Char` box and the type-test mapping simply had no "Char" arm,
#     so it answered `other`.
#
# THE THIRD CAUSE, and it did not stay declared: a char LITERAL on js was a plain one-character
# string, so `case _: Char` was false for `'c'` while `case _: String` was true. No arm can serve
# both — `case _: String` on a genuine one-character string has to keep working — so this one really
# did need the literal to carry the `_Char` box. `JsGen` had argued that change needed its own
# corpus run; it was run (81/81 smoke, 101/101 JsGen suites) and it passed, so the box landed and
# all four lanes now agree. The gate compares js against `--v1` line for line like the others.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC="$ROOT/bin/ssc"
TOOLS="$ROOT/bin/ssc-tools"
for l in "$SSC" "$TOOLS"; do
  [[ -x $l ]] || { echo "char-type-test: no launcher at $l — run ./install.sh --dev" >&2; exit 2; }
done

tmp=$(mktemp -d "${TMPDIR:-/tmp}/chartype.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat > "$tmp/kind.ssc" <<'EOF'
def kind(x: Any): String =
  x match
    case _: Char   => "Char"
    case _: String => "String"
    case _: Int    => "Int"
    case _         => "other"

def main(): Unit =
  val s = "abc"
  println(kind('c'))
  println(kind("c"))
  println(kind(99))
  println(kind(s.charAt(0)))
  println(kind("cc"))
EOF

run() { # run <label> <cmd...> ; prints the last 5 lines
  local label=$1; shift
  if ! "$@" "$tmp/kind.ssc" > "$tmp/$label.raw" 2>"$tmp/$label.err"; then
    echo "char-type-test: lane $label failed to run" >&2; cat "$tmp/$label.err" >&2; exit 1
  fi
  tail -5 "$tmp/$label.raw" > "$tmp/$label.txt"
}

run int "$TOOLS" run --v1
run native "$SSC" run
run bytecode "$TOOLS" run --bytecode
run js "$TOOLS" run-js

# The oracle must say what the census says it says, or an agreeing pair of wrong lanes reads green.
expected_int=$'Char\nString\nInt\nChar\nString'
if [[ "$(cat "$tmp/int.txt")" != "$expected_int" ]]; then
  echo "char-type-test: the REFERENCE lane (--v1) does not match the census — the gate cannot judge" >&2
  echo "  expected: $(echo "$expected_int" | tr '\n' '/')" >&2
  echo "  got:      $(tr '\n' '/' < "$tmp/int.txt")" >&2
  exit 2
fi

fail=0
for lane in native bytecode; do
  if ! diff -u "$tmp/int.txt" "$tmp/$lane.txt" > "$tmp/$lane.diff"; then
    echo "char-type-test: $lane disagrees with --v1" >&2
    echo "  (-) --v1   (+) $lane" >&2
    cat "$tmp/$lane.diff" >&2
    fail=1
  fi
done

if ! diff -u "$tmp/int.txt" "$tmp/js.txt" > "$tmp/js.diff"; then
  echo "char-type-test: js disagrees with --v1" >&2
  echo "  (-) --v1   (+) js" >&2
  cat "$tmp/js.diff" >&2
  echo "If row 1 ('c') answers String, the char literal has stopped carrying the _Char box —" >&2
  echo "check what emits Lit.Char in JsGen and JsGenCpsCodegen. The two must move TOGETHER:" >&2
  echo "boxing only the expression side makes case 'a' => compare a box with a string." >&2
  fail=1
fi

[[ $fail -eq 0 ]] || exit 1
echo "char-type-test: ok — native, bytecode and js all agree with --v1 on five rows"
