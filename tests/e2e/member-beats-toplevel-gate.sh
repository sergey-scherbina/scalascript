#!/usr/bin/env bash
#
# member-beats-toplevel-gate — a method wins over a same-named top-level def, and the tail-call
# trampoline does not change that.
#
# `two-fronts-disagree-on-name-resolution`, the v1 half. The sharpest form of the defect needs no
# explanation at all:
#
#     def size(): Int = 100
#     case class S(n: Int):
#       def size(x: Int): Int = x * 2
#       def go(): Int = size(5)          -> 100        the top-level function
#       def go(): Int = size(5) + 0      ->  10        the method
#
# Adding `+ 0`, which cannot change a value, changed which function ran. Cause: `size(5)` in TAIL
# position becomes a mutual-tail-call target, and `TcoRuntime` resolved the target with
# `interp.globals.get(name) orElse curFun.closure.get(name)` — globals first. It then overlaid a
# trampoline stub for the top-level `size` on top of the frame, shadowing the sibling binding that
# `DispatchRuntime.bindSiblings` had already put in the closure. Out of tail position no stub is
# built, so the same expression resolved differently.
#
# THE ORACLE IS REAL SCALA, not a preference: `scala-cli run` on the same source answers 10 and 7.
# That is why this gate can pin numbers where `sibling-method-gate.sh` deliberately does not — there
# the native lane's answer depends on which front lowered the file, here the interpreter is the only
# producer and Scala settles it.
#
# THE SECOND HALF IS NOT OPTIONAL. The fix swaps a lookup order inside the tail-call trampoline, so
# the way to get this gate green while breaking the language is to disable mutual tail calls. The
# depth cases below are what makes that impossible: they need the trampoline to actually work, and
# they fail with a stack overflow, not a wrong number, if it stops.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="$ROOT/bin/ssc-tools"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/member-beats-toplevel.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a method wins over a same-named top-level def"

if [[ ! -x "$tools" ]]; then
  echo "SKIP member-beats-toplevel-gate: $tools not built (run scripts/sbtc installBin)"
  exit 0
fi

run_case() {
  local name=$1 want=$2 src=$3 secs=${4:-120}
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  local got
  got=$(timeout "$secs" "$tools" run --v1 "$sandbox/$name.ssc" 2>/dev/null | head -1)
  if [[ "$got" == "$want" ]]; then
    echo "  ✓ $name → $got"
  else
    echo "  ✗ $name: expected '$want', got '$got'"
    timeout "$secs" "$tools" run --v1 "$sandbox/$name.ssc" 2>&1 | grep -vE '^[[:space:]]+at ' | head -2 | sed 's/^/      /'
    fails=$((fails + 1))
  fi
}

# ── the pair that names the bug ──────────────────────────────────────────────────────────────────
# These two differ by `+ 0`. If they ever disagree with each other again, the tail-call path has
# started resolving names differently from the ordinary one, which is the whole defect.
run_case tail-position 10 'def size(): Int = 100
case class S(n: Int):
  def size(x: Int): Int = x * 2
  def go(): Int = size(5)
def main() = println(S(1).go())'

run_case non-tail-position 10 'def size(): Int = 100
case class S(n: Int):
  def size(x: Int): Int = x * 2
  def go(): Int = size(5) + 0
def main() = println(S(1).go())'

# Same arity on both sides, so a wrong answer cannot be blamed on argument count. This is the
# fixture shape from `sibling-method-gate.sh`, whose native half is filed separately.
run_case same-arity 7 'def size(): Int = 100
case class Shadowed(n: Int):
  def size(): Int = n
  def viaGlobal(): Int = size()
def main() = println(Shadowed(7).viaGlobal())'

# A top-level def with NO member of that name must still resolve to the top-level def — the fix
# must not make classes swallow every global call they happen to sit near.
run_case global-still-reachable 100 'def size(): Int = 100
case class S(n: Int):
  def go(): Int = size()
def main() = println(S(1).go())'

# ── what the fix could break, and the reason it is measured at depth ─────────────────────────────
# The lookup order that was swapped lives in the mutual-tail-call setup. Both cases below recurse
# 1e6 deep: without a working trampoline they do not return a wrong answer, they die.
run_case mutual-tail-1e6 true 'def isEven(n: Int): Boolean =
  if n == 0 then true else isOdd(n - 1)

def isOdd(n: Int): Boolean =
  if n == 0 then false else isEven(n - 1)

def main() = println(isEven(1000000))' 240

run_case self-tail-1e6 1000000 'def count(n: Int, acc: Int): Int =
  if n == 0 then acc else count(n - 1, acc + 1)
def main() = println(count(1000000, 0))' 240

# Mutual tail recursion where one partner shares a name with a METHOD — the two halves of this gate
# meeting. The class must not capture the top-level partner, and the recursion must still trampoline.
run_case mutual-with-collision true 'def ping(n: Int): Boolean =
  if n == 0 then true else pong(n - 1)

def pong(n: Int): Boolean =
  if n == 0 then false else ping(n - 1)

case class Holder(k: Int):
  def ping(n: Int): Boolean = false
  def go(): Boolean = ping(1)

def main() = println(ping(1000000))' 240

echo
if [[ $fails -eq 0 ]]; then
  echo "✓ member-beats-toplevel-gate PASSED"
  exit 0
fi
echo "✗ member-beats-toplevel-gate FAILED ($fails)"
exit 1
