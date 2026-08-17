#!/usr/bin/env bash
#
# rust-list-pattern-gate — a cons pattern with a WILDCARD TAIL emits Rust that parses.
#
# THE DEFECT (rust-cons-pattern-with-a-wildcard-tail-emits-an-invalid-at-pattern). The tail of a
# cons pattern lowers to a slice rest-binding, `[h, t @ ..]`. Rust requires a BINDING left of `@` —
# `x`, `mut x`, `ref x`, `ref mut x` — and `_` is a pattern, not a binding:
#
#     case Cons(_, _) => …          ->   [_, _ @ ..] => …
#     error: left-hand side of `@` must be a binding
#
# A PARSE error, so the crate stops there and every other diagnostic in the file is hidden behind
# it. `std/json-core.ssc:474` spells it exactly that way (`case Cons(_, _)`), which is how it was
# found: two of the errors that module produces were this, not type errors.
#
# TWO SPELLINGS, TWO ROWS, ONE HELPER. `case Cons(h, t)` and `case h :: t` are separate arms in
# `renderPattern` and each built the tail string itself. A fix applied to one of them is half a fix,
# so both now call `sliceTail` and both are gated:
#   * `consw`  — `case Cons(_, _)`, the extractor spelling, wildcard tail
#   * `infixw` — `case h :: _`,     the infix spelling,     wildcard tail
#   * `named`  — `case h :: t`,     the ANTI-ROW: a tail that is still BOUND must keep its
#                `t @ ..`, and the row reads `t` so a fix that dropped the binding fails here.
#
# NOT GATED HERE, deliberately: a wildcard HEAD in the infix spelling (`case _ :: t`) answers
# `<closure>` on the v2 lane while v1 and rust answer correctly — a v2 front defect, filed as
# `v2-cons-pattern-with-a-wildcard-head-returns-a-closure`. Putting it in a row would mean picking
# a winner between disagreeing lanes, which is not this gate's job.
#
# COST: three cargo builds, ~45 s. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-list-pattern-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_listpat.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/consw.ssc" <<'SSC'
def describe(xs: List[Int]): String =
  xs match {
    case Cons(_, _) => "many"
    case _          => "none"
  }

def main(): Unit =
  println(describe(List(1, 2)))
  println(describe(List()))
main()
SSC

cat > "$sandbox/infixw.ssc" <<'SSC'
def firstOf(xs: List[Int]): Int =
  xs match {
    case h :: _ => h
    case _      => -1
  }

def main(): Unit =
  println(firstOf(List(4, 5, 6)))
  println(firstOf(List()))
main()
SSC

# The anti-row: a BOUND tail must keep its binding, and the row reads it.
cat > "$sandbox/named.ssc" <<'SSC'
def headPlusRest(xs: List[Int]): Int =
  xs match {
    case h :: t => h + t.length
    case _      => -1
  }

def main(): Unit =
  println(headPlusRest(List(5, 6, 7)))
  println(headPlusRest(List(9)))
main()
SSC

lane_says() { # $1 label, $2 want, $3.. command
  local label=$1 want=$2; shift 2
  local out
  out=$(timeout 200 "$@" 2>&1 | head -6 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $label: $out"
  else
    echo "  ✗ $label: got '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

row() { # $1 case name, $2 expected output
  local name=$1 want=$2
  echo "── $name"
  lane_says "run   " "$want" "$ssc" run "$sandbox/$name.ssc"
  lane_says "--v1  " "$want" "$tools" run --v1 "$sandbox/$name.ssc"
  if ! command -v cargo >/dev/null 2>&1; then
    echo "  [skip] cargo is not on PATH — the Rust row cannot run. That is a SKIP, not a pass."
  elif ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/$name.ssc" >"$sandbox/$name.log" 2>&1); then
    echo "  ✗ rust  : build failed: $(grep -m1 -E 'error\[E[0-9]+\]|^error' "$sandbox/$name.log")"
    fails=$((fails + 1))
  else
    lane_says "rust  " "$want" "$sandbox/$name"
  fi
}

row consw  'many|none|'
row infixw '4|-1|'
row named  '7|9|'

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-list-pattern-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-list-pattern-gate: PASS"
