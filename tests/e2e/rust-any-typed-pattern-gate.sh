#!/usr/bin/env bash
#
# rust-any-typed-pattern-gate — `case Box(v: Int)` over an `Any` matches only an Int, and binds one.
#
# A WRONG ANSWER, not a compile error. `case class Box(v: Any)` plus `case Box(v: Int) => …` emitted
# a test for the CONSTRUCTOR only and dropped the ascription, so `Box("x")` entered that arm and the
# `.ssc_int()` in the body aborted the process — where `run` falls through to the next arm and
# answers -1. The ascription is the only thing in the program that says what the arm is for, and
# `std/json-core.ssc` writes exactly this shape with a comment explaining that the jvm lane needs it.
#
# TWO ROWS, AND THE SECOND IS THE ONE THAT WAS BROKEN. The matching row passed before the fix; only
# the NON-matching row shows the defect, and it shows it as a panic rather than as a diff, which is
# why the gate compares the whole output against `run` instead of grepping for a number.
#
# COST: one cargo build plus one interpreter run, ~40 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "rust-any-typed-pattern-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }
if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-any-typed-pattern-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

sandbox=$(mktemp -d "$ROOT/examples/_typat.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/t.ssc" <<'SSC'
case class Box(v: Any)

def double(n: Int): Int = n * 2

def go(b: Any): Int = b match
  case Box(v: Int) => double(v)
  case _ => -1

def label(b: Any): String = b match
  case Box(s: String) => "s:" + s
  case Box(n: Int) => "n:" + n
  case _ => "none"

def main(): Unit =
  println("int: " + go(Box(21)))
  println("other: " + go(Box("x")))
  println("str-arm: " + label(Box("hi")))
  println("int-arm: " + label(Box(5)))

main()
SSC

want=$(timeout 600 "$ssc" run "$sandbox/t.ssc" 2>/dev/null)
[[ -z "$want" ]] && { echo "  ✗ the interpreter produced nothing — the oracle is unusable" >&2; exit 1; }

echo "── a typed binder over an Any tests its ascription"
if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/t.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build-rust failed:"
  grep -m3 -E 'error\[E[0-9]+\]|Generic\(' "$sandbox/build.log" | cut -c1-110 | sed 's/^/      /'
  exit 1
fi
# The binary ABORTS when the arm is wrong, so stderr and the exit code are part of the answer.
got=$(timeout 300 "$sandbox/t" 2>"$sandbox/run.err"); rc=$?
if [[ "$rc" -ne 0 ]]; then
  echo "  ✗ the binary exited $rc — a non-matching ascription still aborts:"
  head -2 "$sandbox/run.err" | cut -c1-110 | sed 's/^/      /'
  fails=$((fails + 1))
fi

while IFS= read -r row; do
  mine=$(printf '%s\n' "$got" | grep -F "${row%%:*}:" || true)
  if [[ "$mine" == "$row" ]]; then echo "  ✓ $row"
  else echo "  ✗ ${row%%:*}: rust '${mine#*: }', interpreter '${row#*: }'"; fails=$((fails + 1)); fi
done <<< "$want"

# The oracle must still FALL THROUGH rather than match: if `run` ever answered 42 for the second row
# the comparison above would be satisfied by a lane that had also stopped discriminating.
if printf '%s\n' "$want" | grep -q 'other: -1' && printf '%s\n' "$want" | grep -q 'str-arm: s:hi'; then
  echo "  ✓ the oracle itself discriminates: a String does not enter the Int arm"
else
  echo "  ✗ the interpreter stopped discriminating — the oracle regressed"; fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then echo "rust-any-typed-pattern-gate: FAIL ($fails)" >&2; exit 1; fi
echo "rust-any-typed-pattern-gate: PASS"
