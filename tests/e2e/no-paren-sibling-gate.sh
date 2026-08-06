#!/usr/bin/env bash
#
# no-paren-sibling-gate — a PARAMETERLESS sibling method used as a value must evaluate to its
# RESULT on the v1 interpreter, as it already did on the native and jvm lanes.
#
# `int-no-paren-sibling-method-is-undefined`. The sibling-call fix (c04de5df1, b70a1e92c) keyed on
# `bareAppliedNames`, which collects `Term.Apply` heads — so `twice()` was found and `twice`, the
# no-paren form, was not. Measured before the fix, one construct, three lanes:
#
#     case class Box(n: Int):
#       def twice: Int = n * 2
#       def quad(): Int = twice * 2          v1: Undefined: twice    native: 12    jvm: 12
#
# The interpreter is the corpus golden, so a disagreement here silently makes the golden the wrong
# lane — the reason this is a gate and not a unit test.
#
# THE PROBE NAMES MATTER HERE. An earlier version of this gate was written alongside a filed bug
# claiming a parameterless def leaks into `globals`, on a probe that named the method `a` — and `a`
# is the built-in HTML anchor element, so `println(a)` answers from the builtin table whether or not
# any class is in the file. Every name below is one no builtin uses; the control that would have
# caught it is `def main() = println(<name>)` with no class present, which must be `Undefined`.
#
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="$ROOT/bin/ssc-tools"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/no-paren-sibling.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a parameterless sibling method is a value, not an error"

if [[ ! -x "$tools" ]]; then
  echo "SKIP no-paren-sibling-gate: $tools not built (run scripts/sbtc installBin)"
  exit 0
fi

# Each case: name | expected stdout | source. The expectations are the NATIVE lane's answers,
# which is the point — this gate exists because the two lanes disagreed.
run_case() {
  local name=$1 want=$2 src=$3
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  local got
  got=$(timeout 120 "$tools" run --v1 "$sandbox/$name.ssc" 2>/dev/null | head -1)
  if [[ "$got" == "$want" ]]; then
    echo "  ✓ $name → $got"
  else
    echo "  ✗ $name: expected '$want', got '$got'"
    timeout 120 "$tools" run --v1 "$sandbox/$name.ssc" 2>&1 | grep -vE '^[[:space:]]+at ' | head -2 | sed 's/^/      /'
    fails=$((fails + 1))
  fi
}

# The original repro: no-paren sibling read from a method that takes a parameter clause.
run_case no-paren 12 'case class Box(n: Int):
  def twice: Int = n * 2
  def quad(): Int = twice * 2
def main() = println(Box(3).quad())'

# Applied and no-paren siblings in ONE expression — the shape `v3/tests/front/traits-methods.ssc`
# uses, and the reason a fix covering only one of the two forms is not enough.
run_case mixed 31 'case class Counter(start: Int):
  def plus(n: Int): Int = start + n
  def doubled: Int = start * 2
  def twicePlus(n: Int): Int = doubled + plus(n)
def main() = println(Counter(10).twicePlus(1))'

# A trait default method reading an abstract member implemented by the subtype.
run_case inherited 18 'trait Sh:
  def area(): Int
  def describe(): Int = area() * 2
case class Sq(s: Int) extends Sh:
  def area(): Int = s * s
def main() = println(Sq(3).describe())'

# The caller x callee paren matrix. The fix is on the CALLEE side, so the caller's own form must
# not matter — asserting only one combination would have hidden that, and did: the pair below was
# once reported broken, on a checkout fast-forwarded past the fix but never rebuilt.
run_case caller_paren_callee_bare 20 'case class T(n: Int):
  def inc: Int = n + 1
  def outer(): Int = inc * 10
def main() = println(T(1).outer())'

run_case caller_bare_callee_paren 20 'case class T(n: Int):
  def inc(): Int = n + 1
  def outer: Int = inc() * 10
def main() = println(T(1).outer)'

run_case caller_bare_callee_bare 20 'case class T(n: Int):
  def inc: Int = n + 1
  def outer: Int = inc * 10
def main() = println(T(1).outer)'

# Chained: a no-paren method reading a no-paren method that itself reads a third.
run_case chained 22 'case class T(n: Int):
  def inc: Int = n + 1
  def scaled: Int = inc * 10
  def go(): Int = scaled + inc
def main() = println(T(1).go())'

# ── the two sides that keep the fix honest ───────────────────────────────────────────────────────
# A local binding SHADOWS a same-named method, as in Scala. Without this, a fix that resolved the
# method first would pass every case above and silently change what `v` means.
run_case shadow 8 'case class S(n: Int):
  def v: Int = 99
  def go(): Int =
    val v = 7
    v + 1
def main() = println(S(1).go())'

# A genuine typo must still be an error. The fix lives on the name-lookup MISS path, so an
# over-broad version of it would swallow undefined names and report something plausible instead.
printf '%s\n' 'case class B(n: Int):
  def go(): Int = nosuch * 2
def main() = println(B(1).go())' > "$sandbox/typo.ssc"
# Captured first, grepped second: under `pipefail` the pipeline would report the INTERPRETER's
# non-zero exit — which this case expects — and the `grep` match would never be read.
typo_out=$(timeout 120 "$tools" run --v1 "$sandbox/typo.ssc" 2>&1)
if grep -qF 'Undefined: nosuch' <<<"$typo_out"; then
  echo "  ✓ typo: an undefined name is still reported, not resolved to a method"
else
  echo "  ✗ typo: 'Undefined: nosuch' was not reported — the miss path swallows unknown names"
  fails=$((fails + 1))
fi

# The ABSENT-state control this gate exists to remember: every probe name above must be undefined
# when no class defines it. A name that answers from the builtin table would make every case above
# pass without testing anything.
control_bad=0
for probe_name in inc outer scaled twice quad doubled; do
  printf '%s\n' "def main() = println($probe_name)" > "$sandbox/control.ssc"
  ctl_out=$(timeout 120 "$tools" run --v1 "$sandbox/control.ssc" 2>&1)
  if ! grep -qF "Undefined: $probe_name" <<<"$ctl_out"; then
    echo "  ✗ control: '$probe_name' resolves with no class in the file — it is a builtin, so every"
    echo "            case using it proves nothing. Rename the probe."
    fails=$((fails + 1)); control_bad=$((control_bad + 1))
  fi
done
if [[ $control_bad -eq 0 ]]; then
  echo "  ✓ control: all probe names are undefined without a class to define them"
fi

echo
if [[ $fails -eq 0 ]]; then
  echo "✓ no-paren-sibling-gate PASSED"
  exit 0
fi
echo "✗ no-paren-sibling-gate FAILED ($fails)"
exit 1
