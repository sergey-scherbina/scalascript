#!/usr/bin/env bash
#
# f-bare-member-call-gate — front F must LOWER a bare call to a sibling member of the enclosing
# `object`, not decline the whole file over it.
#
# `f-declines-every-non-top-level-def`. F resolves a bare applied name against top-level defs and
# @-cells; `calleeOf` had an arm for the current object's `var`s (`isCurObjVar`) and none for its
# methods, so
#
#     object M:
#       def twice(): Int = 2
#       def quad(): Int = twice() * 2
#
# lowered `twice` to a top-level `(global twice)`, `validateNoReader` rejected it, and F declined
# the file — which sends the whole program to the reference front. That matters beyond the one
# construct: an F decline is FILE-scoped, so one such method makes every measurement in that file
# the reference front's numbers rather than F's.
#
# MEASURED WITH SSC_FRONT_STRICT=1 on purpose. Without it the fallback is silent and the program
# still prints the right answer, so a plain output comparison passes whether F lowered the file or
# declined it — the gate would be green either way and prove nothing. Strict mode turns the decline
# into a hard error, which is the only way this can distinguish the two states.
#
# STILL OPEN, deliberately not asserted here: the same bare call inside a `case class` body. That
# path is `ccMDef` (this file, ~:2612) and it does not run its method bodies under `withCurObj`, so
# `isCurObjMethod` never fires for it. Reusing the object registry would be the obvious fix and is
# NOT safe — `objReg` holds `object` declarations only, and the case-class constructor path depends
# on that (see the note at fsub :1033). Left in the BUGS entry with that address rather than pinned
# red here.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="$ROOT/bin/ssc"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-bare-member.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0
export SSC_NO_BUILD_CHECK=1

echo "── F lowers a bare call to a sibling object member"

if [[ ! -x "$ssc" ]]; then
  echo "SKIP f-bare-member-call-gate: $ssc not built (run scripts/sbtc installBin)"
  exit 0
fi

# $1 name, $2 expected stdout, $3 source. Runs twice: strict (did F lower it?) and plain (is the
# answer right?). Both matter — F lowering a file to the WRONG code would pass a strict-only check.
lowered_and_correct() {
  local name=$1 want=$2 src=$3
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  local strict out
  strict=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1)
  if grep -qF 'refusing to fall back' <<<"$strict"; then
    echo "  ✗ $name: F DECLINED — $(grep -oE 'unbound global: \(global [A-Za-z0-9_]+\)' <<<"$strict" | head -1)"
    fails=$((fails + 1)); return
  fi
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>/dev/null | head -1)
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $name: F lowered it, answer $out"
  else
    echo "  ✗ $name: F lowered it but answered '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

lowered_and_correct object-sibling 4 'object M:
  def twice(): Int = 2
  def quad(): Int = twice() * 2
def main() = println(M.quad())'

lowered_and_correct object-sibling-with-arg 12 'object M:
  def scale(n: Int): Int = n * 2
  def go(): Int = scale(6)
def main() = println(M.go())'

lowered_and_correct object-chain 22 'object M:
  def base(): Int = 2
  def mid(): Int = base() * 10
  def top(): Int = mid() + base()
def main() = println(M.top())'

# A sibling call must not swallow a same-named TOP-LEVEL def when the object has no such member.
# Without this, "resolve bare names to the enclosing object" would look correct on every case above
# while quietly capturing ordinary global calls.
lowered_and_correct global-still-reachable 100 'def helper(): Int = 100
object M:
  def go(): Int = helper()
def main() = println(M.go())'

# The object`s own `var` arm must keep working — it is the arm this fix was inserted next to, and
# the two are one `if` chain.
lowered_and_correct object-var-still-works 7 'object M:
  var seed = 7
  def go(): Int = seed
def main() = println(M.go())'

echo
if [[ $fails -eq 0 ]]; then
  echo "✓ f-bare-member-call-gate PASSED"
  exit 0
fi
echo "✗ f-bare-member-call-gate FAILED ($fails)"
exit 1
