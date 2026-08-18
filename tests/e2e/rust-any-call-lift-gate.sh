#!/usr/bin/env bash
#
# rust-any-call-lift-gate — a CALL handed to an `Any` parameter is lifted to `Value`.
#
# `def n(): Int` passed to `def g(x: Any)` was `error[E0308]: expected Value, found i64`, in five
# lines with no object, no package and no import. `needsAnyCoercion` decided this, and for a `Value`
# target it recognised a case class, an expression already known to hold a `Value`, a literal, and a
# closure parameter — three of those four were added one shape at a time, and a plain call is the
# shape every program writes.
#
# THREE RETURN TYPES, ON PURPOSE. `i64`, `String` and a user struct take different `From` impls, and
# the first version of the rule was bounded by "the callee's return type is known and not empty"
# precisely because `mapType` answers the empty string for `Unit` and for anything it cannot
# resolve — so the gate has to show the lift working across more than one of them.
#
# COMPARED AGAINST `run`: an `Any` that arrives as the wrong Value variant still compiles and still
# prints something, so compiling is not the property.
#
# COST: one cargo build plus one interpreter run, ~40 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "rust-any-call-lift-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }
if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-any-call-lift-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

sandbox=$(mktemp -d "$ROOT/examples/_anylift.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/a.ssc" <<'SSC'
case class Point(x: Int, y: Int)

def num(): Int = 7

def text(): String = "seven"

def pt(): Point = Point(1, 2)

def show(v: Any): String = "v=" + v

def main(): Unit =
  println("int: " + show(num()))
  println("str: " + show(text()))
  println("pt: " + show(pt()))

main()
SSC

want=$(timeout 600 "$ssc" run "$sandbox/a.ssc" 2>/dev/null)
[[ -z "$want" ]] && { echo "  ✗ the interpreter produced nothing — the oracle is unusable" >&2; exit 1; }

echo "── a call handed to an Any parameter is lifted"
if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/a.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build-rust failed — a call at an Any parameter is not lifted:"
  grep -m3 -E 'error\[E[0-9]+\]|Generic\(' "$sandbox/build.log" | cut -c1-110 | sed 's/^/      /'
  exit 1
fi
got=$(timeout 300 "$sandbox/a" 2>/dev/null)

while IFS= read -r row; do
  mine=$(printf '%s\n' "$got" | grep -F "${row%%:*}:" || true)
  if [[ "$mine" == "$row" ]]; then echo "  ✓ $row"
  else echo "  ✗ ${row%%:*}: rust '${mine#*: }', interpreter '${row#*: }'"; fails=$((fails + 1)); fi
done <<< "$want"

# WANTED ROWS, so a probe that stops compiling half-way cannot read as a pass: the loop above
# iterates the ORACLE, and an oracle that lost a line would quietly check fewer things.
n=$(printf '%s\n' "$want" | grep -c ':')
if [[ "$n" -eq 3 ]]; then echo "  ✓ all three return types were checked"
else echo "  ✗ the oracle produced $n rows, not 3 — the probe shrank"; fails=$((fails + 1)); fi

echo
if [[ "$fails" -ne 0 ]]; then echo "rust-any-call-lift-gate: FAIL ($fails)" >&2; exit 1; fi
echo "rust-any-call-lift-gate: PASS"
