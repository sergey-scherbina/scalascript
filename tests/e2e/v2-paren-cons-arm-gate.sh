#!/usr/bin/env bash
#
# v2-paren-cons-arm-gate — `case (h) :: t` matches a list on the DEFAULT front.
#
# A SILENT WRONG ANSWER, and in one shape a crash. The dispatchers test the head TOKEN `(` before
# anything asks whether the pattern is a cons, so `case (_) :: t` and `case (h) :: t` reached the
# tuple-arm parser and became a one-element tuple pattern:
#
#   case (_) :: t  first arm, with a catch-all   ->  the catch-all answered      (v1: "paren")
#   case (h) :: t  later arm, no catch-all       ->  ssc: match: no arm for Cons/2
#
# COMPARED AGAINST `--v1`, not against expected text: this is a two-lane disagreement, v1 has always
# been right, and the entry that reports it was written from exactly that comparison.
#
# THE CONTROLS ARE HALF THE ROWS. A previous attempt at this defect made the arm RECOGNISED but sent
# it to a parser that indexes tokens blind, turning `z` into `<closure>` — one wrong answer for
# another. So the gate pins what the value IS (`binds` returns 42, not a closure and not -1), and it
# keeps a real tuple arm and a plain `h :: t` arm beside them, because the fix moves matches that
# contain a paren-cons arm to the other resolver and those two must not move with them.
#
# COST: two interpreter runs, ~10 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$ssc" && -x "$tools" ]] || { echo "v2-paren-cons-arm-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v2-paren-cons.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/p.ssc" <<'SSC'
def paren(xs: List[Int]): String  = xs match { case (_) :: t => "paren"  ; case _ => "z" }
def parenN(xs: List[Int]): String = xs match { case (h) :: t => "parenN" ; case _ => "z" }
def binds(xs: List[Int]): Int     = xs match { case (h) :: t => h        ; case _ => -1 }
def nested(xs: List[Int]): String = xs match { case (a) :: (b) :: t => "two" ; case _ => "z" }

def later(xs: List[Int]): String = xs match {
  case Nil => "nil"
  case (h) :: t => "later"
}

def guarded(xs: List[Int]): String = xs match { case (h) :: t if h > 0 => "g+" ; case _ => "z" }

def tup(p: (Int, Int)): String    = p match { case (a, b) => "tup" }
def plain(xs: List[Int]): String  = xs match { case h :: t => "plain" ; case _ => "z" }

def main(): Unit =
  println("paren: " + paren(List(1)))
  println("parenN: " + parenN(List(1)))
  println("binds: " + binds(List(42)))
  println("nested: " + nested(List(1, 2)))
  println("later: " + later(List(7)))
  println("guarded: " + guarded(List(7)))
  println("tup: " + tup((1, 2)))
  println("plain: " + plain(List(1)))

main()
SSC

want=$(timeout 600 "$tools" run --v1 "$sandbox/p.ssc" 2>/dev/null)
got=$(timeout 600 "$ssc" run "$sandbox/p.ssc" 2>/dev/null)

echo "── a parenthesised head is still a cons arm"
if [[ -z "$want" ]]; then
  echo "  ✗ --v1 produced nothing — the oracle is unusable" >&2; exit 1
fi

while IFS= read -r row; do
  mine=$(printf '%s\n' "$got" | grep -F "${row%%:*}:" || true)
  if [[ "$mine" == "$row" ]]; then echo "  ✓ $row"
  else echo "  ✗ ${row%%:*}: v2 '${mine#*: }', v1 '${row#*: }'"; fails=$((fails + 1)); fi
done <<< "$want"

# The oracle must still DISCRIMINATE. If v1 ever answered "z" for the first two rows the comparison
# above would be satisfied by two lanes that had both stopped matching, and `binds` pins the VALUE
# rather than the arm — the shape a previous attempt got wrong by answering a closure.
if printf '%s\n' "$want" | grep -q 'paren: paren' &&
   printf '%s\n' "$want" | grep -q 'binds: 42' &&
   printf '%s\n' "$want" | grep -q 'later: later'; then
  echo "  ✓ the oracle itself matches the arm and binds its head"
else
  echo "  ✗ --v1 no longer answers paren/42/later — the oracle regressed"; fails=$((fails + 1))
fi

# Eight rows, so a probe that stopped compiling half-way cannot read as a pass.
n=$(printf '%s\n' "$want" | grep -c ': ')
if [[ "$n" -eq 8 ]]; then echo "  ✓ all eight rows were compared"
else echo "  ✗ the oracle produced $n rows, not 8 — the probe shrank"; fails=$((fails + 1)); fi

echo
if [[ "$fails" -ne 0 ]]; then echo "v2-paren-cons-arm-gate: FAIL ($fails)" >&2; exit 1; fi
echo "v2-paren-cons-arm-gate: PASS"
