#!/usr/bin/env bash
#
# f-case-object-pattern-gate — F compiles a match on a standalone `case object`.
#
# THE DEFECT. `case A =>`, where `A` is a top-level `case object`, made F refuse the whole file:
#
#     reason: "unknown constructor 'A' in a pattern"
#
# and the lane then compiled it with the reference front. Nothing was RED — the program ran and
# printed the right answer — but every measurement of that file was the other front's, and
# `examples/scljet-hello.ssc` is one of the files it happened to, which is why
# `tests/e2e/v2-f-nested-bytecode-fast-path.sh` could never see F take its own fast path.
#
# WHERE IT CAME FROM, because the two halves disagreed rather than either being wrong alone:
# `ckCtorF` accepts a case object via `isTopVal`, and its comment says that is "what a case object
# lowers to here". That stopped being true when `caseObjectItem` landed and began lowering a
# standalone `case object N` to `(def N (ctor N))` — a ctor DEF, not a val. The pattern check was
# never updated with it. The fix registers those names in `ccNames`, where `ckCtorF` already looks.
#
# THE ROWS.
#   * `bare`     — the defect: two `case object`s and a match on them, nine lines.
#   * `extends`  — the same with `extends`, because that is the shape the corpus actually writes
#     (`case object IndexLeafPage extends BtreePageKind`) and it takes a different parse path.
#   * `mixed`    — a case object matched BESIDE a case class, so the widened name list is shown not
#     to have cost the ordinary case: `P(n)` must still bind its field.
#   * `value`    — THE ANTI-ROW. A case object used as a VALUE, not a pattern, must still work; this
#     is what would break if the fix had made the name a pattern-only tag. It passes with the fix
#     reverted, which is what makes it an anti-row.
#
# Every row runs under SSC_FRONT_STRICT=1: without it F's refusal is silently papered over by the
# reference front and the gate would pass on a file F cannot compile — which is exactly how this
# defect stayed invisible.
#
# COST: four programs, one lane, ~15 s.

set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

. "$ROOT/tests/e2e/lib/ssc-usable.sh" 2>/dev/null || true
if command -v ssc_usable_or_skip >/dev/null 2>&1; then
  ssc_usable_or_skip f-case-object-pattern-gate "$ssc" || exit 0
fi

sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-caseobj.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

row() { # $1 name, $2 want, $3 source
  printf '%s\n' "$3" > "$sandbox/$1.ssc"
  local out
  out=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$1.ssc" 2>&1)
  if grep -q 'unknown constructor' <<<"$out"; then
    echo "  ✗ $1: F REFUSED — $(grep -oE "reason: \"[^\"]*\"" <<<"$out" | head -1)"
    fails=$((fails + 1))
  elif [[ "$(tail -1 <<<"$out")" == "$2" ]]; then
    echo "  ✓ $1: $2"
  else
    echo "  ✗ $1: answered '$(tail -1 <<<"$out")', wanted '$2'"
    fails=$((fails + 1))
  fi
}

echo "── a standalone case object is a matchable tag on F"
row bare 1 'case object A
case object B

def f(x: Any): Int = x match
  case A => 1
  case B => 2

def main(): Unit = println(f(A).toString)'

row extends 2 'sealed trait Kind
case object A extends Kind
case object B extends Kind

def f(x: Kind): Int = x match
  case A => 1
  case B => 2

def main(): Unit = println(f(B).toString)'

row mixed 7 'case class P(n: Int)
case object Zero

def f(x: Any): Int = x match
  case Zero => 0
  case P(n) => n
  case _ => -1

def main(): Unit = println((f(P(7)) + f(Zero)).toString)'

# The anti-row: the name is still a VALUE, not only a pattern tag.
row value ok 'case object A

def name(x: Any): String = if x == A then "ok" else "no"

def main(): Unit = println(name(A))'

echo
if [[ "$fails" -ne 0 ]]; then
  echo "f-case-object-pattern-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "f-case-object-pattern-gate: PASS"
