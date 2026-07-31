#!/usr/bin/env bash
#
# js-self-tco-gate — a self tail call must become a loop on the JS lane, wherever the function lives.
#
# JsGen has had self-TCO since long before this gate, on ONE of its two function-emission paths. A
# top-level `def` becomes `function f(_a, _b) { let a = …; while(true) { … } }`. A `def` inside an
# `object`/package module took the other path and became a plain arrow, `const f = (a, b) => …`, so
# the self call stayed a real JS call and the stack depth was the recursion count.
#
# THE SAME FUNCTION, MOVED, CHANGES SEMANTICS — that is what makes this worth a gate rather than a
# fixed call site. `writeZerosLoop` (scljet/write.ssc:74) carries the comment "tail-recursive so the
# interpreter can TCO the large zero-fills" and died on the JS lane with `RangeError: Maximum call
# stack size exceeded` the day a page got large enough, while the identical shape at top level had
# been fine for months.
#
# TWO ASSERTIONS, and the pair is the point:
#   1. it RUNS — 200k deep, which is ~20x Node's default stack, so a real recursion cannot pass;
#   2. the emitted code CONTAINS the loop — because (1) alone would also pass if some future change
#      merely raised the stack or made the case shallower, and this gate is about the transform.
# Both, in both placements: top level (the path that always worked — a regression guard) and inside
# an object (the path this gate exists for).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }

echo "── js self-TCO gate"
SSC_TOOLS="$ROOT/bin/ssc-tools"
if [ ! -x "$SSC_TOOLS" ]; then
  echo "  (skip: no staged launcher — run ./install.sh --dev first)"
  echo "✗ js-self-tco-gate cannot run"; exit 1
fi

# 200_000 frames: Node's default is ~11k, so this is not a marginal-stack test that could pass by
# luck on a roomier host. Deliberately NOT larger — the point is the transform, not a stress test.
DEPTH=200000

write_case() { # write_case <file> <top-level|in-object>
  if [ "$2" = "top-level" ]; then
    cat > "$1" <<EOF
def countLoop(n: Int, acc: Int): Int =
  if n <= 0 then acc else countLoop(n - 1, acc + 1)

def main(): Unit =
  println(countLoop($DEPTH, 0))
EOF
  else
    cat > "$1" <<EOF
object Loops:
  def countLoop(n: Int, acc: Int): Int =
    if n <= 0 then acc else countLoop(n - 1, acc + 1)

def main(): Unit =
  println(Loops.countLoop($DEPTH, 0))
EOF
  fi
}

check() { # check <label> <placement>
  local label=$1 placement=$2 src="$TMP/$2.ssc" out js
  write_case "$src" "$placement"

  out=$("$SSC_TOOLS" run-js "$src" 2>&1)
  case "$out" in
    *"$DEPTH"*)
      ok "$label: runs $DEPTH deep" ;;
    *"Maximum call stack"*)
      bad "$label: RangeError — the self call is still a real JS call"
      printf '%s\n' "$out" | head -3 | sed 's/^/      /' ;;
    *)
      bad "$label: did not print $DEPTH"
      printf '%s\n' "$out" | head -4 | sed 's/^/      /' ;;
  esac

  # The transform itself. Asserting on OUTPUT alone would stay green if someone deleted the
  # transform and raised the stack instead — a different program with the same answer.
  js=$("$SSC_TOOLS" emit-js "$src" 2>/dev/null)
  case "$js" in
    *"countLoop"*) ;;
    *) bad "$label: countLoop is absent from the emitted bundle — the probe is not measuring it"
       return ;;
  esac
  # Look only at countLoop's OWN text, not the whole bundle: the runtime prelude is full of
  # `while(true)` and matching those would make this assertion vacuous.
  #
  # Newlines are folded first (the top-level form spans lines, the module form is one long line) and
  # the window is anchored on a DEFINITION — `function countLoop(` or `countLoop = (` — never on the
  # bare name, which also appears at every call site. 250 is grep's maximum repetition; both emitted
  # forms reach `while(true)` well inside it.
  flat=$(printf '%s' "$js" | tr '\n' ' ')
  body=$(printf '%s' "$flat" | grep -o "function countLoop *(.\{0,250\}" | head -1)
  [ -n "$body" ] || body=$(printf '%s' "$flat" | grep -o "countLoop *= *(.\{0,250\}" | head -1)
  if [ -z "$body" ]; then
    bad "$label: found no countLoop DEFINITION in the bundle — the probe would pass on any output"
    return
  fi
  case "$body" in
    *"while(true)"*|*"while (true)"*) ok "$label: emitted as a loop" ;;
    *) bad "$label: emitted as recursion, not a loop:"
       printf '      %s\n' "$(printf '%s' "$body" | cut -c1-150)" ;;
  esac
}

check "top level" top-level
check "inside an object" in-object

echo
[ "$fail" -eq 0 ] && { echo "✓ js self-TCO gate PASSED"; exit 0; }
echo "✗ js self-TCO gate FAILED"; exit 1
