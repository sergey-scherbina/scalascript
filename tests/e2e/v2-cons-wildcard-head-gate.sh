#!/usr/bin/env bash
#
# v2-cons-wildcard-head-gate — `case _ :: t =>` matches and runs its body on the v2 lane.
#
# THE DEFECT (v2-cons-pattern-with-a-wildcard-head-returns-a-closure). `parseArm` in the F front
# dispatched on the FIRST token and tested the wildcard before anything else:
#
#     if isWild(hd(ts)) then parseWildArm(tl(ts), ...) else ... else parseConsArm(ts, ...)
#
# `parseConsArm` was the last resort, so a wildcard head could never reach it. `case _ :: t => body`
# collapsed to a catch-all `_`, the body was then parsed from the LEFTOVER `:: t => body`, and
# `t => body` is a LAMBDA. The match answered `<closure>`, the arm body never ran, and nothing was
# reported — a silent wrong answer on the DEFAULT lane for ordinary Scala.
#
# WHY IT SURVIVED. The extractor spelling `case Cons(_, t)` is correct, a named head `case h :: t`
# is correct, and a wildcard anywhere but the head is correct — so every neighbouring spelling
# works and only this cell is wrong. It was found by cross-lane comparison while choosing rows for
# a rust gate: v1 and rust said `A`, v2 said `<closure>`.
#
# THE ROWS.
#   * `wildhead` — the defect itself, in both spellings that trigger it (`_ :: t` and `_ :: _`).
#   * `neighbours` — the cells that always worked and must keep working: `h :: t`, `h :: _`,
#     `Cons(_, t)`, and a wildcard in a NESTED position (`h :: _ :: t`).
#   * `catchall` — THE ANTI-ROW. The fix puts a cons test in front of the wildcard test, so a plain
#     `case _ =>` must still be a catch-all — and so must a BINDING arm `case bound =>`, which the
#     source comment above `parseArms0` warns must be split out before `parseConsArm` because that
#     one indexes blind. This row is what stops the fix from eating either, and it
#     passes with the fix REVERTED, which is what makes it an anti-row rather than a second copy of
#     the first row.
#
# The expected answers are the v1 lane's, which is why every row runs both: what these programs
# should print is the reference lane's answer, not this file's opinion.
#
# NOT COVERED, measured and filed rather than left to be rediscovered: a PARENTHESISED wildcard head
# (`case (_) :: t`) still answers the catch-all arm on v2. `isConsArmHead` peeks exactly two tokens,
# so it cannot see the `::` past `(_)`; making it parse a full atom first would also reroute every
# `case Cons(a, b)` from `parseCtorArm` to `parseConsArm`, which is a different lowering and a
# bigger change than this fix. See the entry.
#
# COST: two lanes x three programs, no cargo, ~10 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$ssc" ]] || { echo "v2-cons-wildcard-head-gate: no launcher at $ssc — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_conswild.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/wildhead.ssc" <<'SSC'
def tailNamed(xs: List[Int]): String = xs match { case _ :: t => "named-tail" ; case _ => "z" }
def bothWild(xs: List[Int]): String  = xs match { case _ :: _ => "both-wild" ; case _ => "z" }

def main(): Unit =
  println(tailNamed(List(1, 2)))
  println(tailNamed(List()))
  println(bothWild(List(1)))
main()
SSC

cat > "$sandbox/neighbours.ssc" <<'SSC'
def named(xs: List[Int]): String     = xs match { case h :: t => "h" ; case _ => "z" }
def tailWild(xs: List[Int]): String  = xs match { case h :: _ => "hw" ; case _ => "z" }
def ctorWild(xs: List[Int]): String  = xs match { case Cons(_, t) => "cw" ; case _ => "z" }
def nestWild(xs: List[Int]): String  = xs match { case h :: _ :: t => "nw" ; case _ => "z" }

def main(): Unit =
  println(named(List(1)))
  println(tailWild(List(1)))
  println(ctorWild(List(1)))
  println(nestWild(List(1, 2, 3)))
main()
SSC

# The anti-row: a bare `_` arm is a catch-all and must stay one now that a cons test runs first.
cat > "$sandbox/catchall.ssc" <<'SSC'
def plain(n: Int): String = n match { case 1 => "one" ; case _ => "other" }
def onlyWild(n: Int): String = n match { case _ => "always" }
// A BINDING arm names the scrutinee and is a catch-all too. The source comment above
// `parseArms0` warns that it must be split out BEFORE `parseConsArm`, which "indexes blind",
// so the new cons test running first has to leave it alone.
def named(n: Int): String = n match { case bound => "got " + bound }

def main(): Unit =
  println(plain(1))
  println(plain(9))
  println(onlyWild(3))
  println(named(7))
main()
SSC

lane_says() { # $1 label, $2 want, $3.. command
  local label=$1 want=$2; shift 2
  local out
  out=$(timeout 200 "$@" 2>/dev/null | head -8 | tr '\n' '|')
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
  lane_says "v2 run" "$want" "$ssc" run "$sandbox/$name.ssc"
  lane_says "--v1  " "$want" "$tools" run --v1 "$sandbox/$name.ssc"
}

row wildhead   'named-tail|z|both-wild|'
row neighbours 'h|hw|cw|nw|'
row catchall   'one|other|always|got 7|'

echo
if [[ "$fails" -ne 0 ]]; then
  echo "v2-cons-wildcard-head-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "v2-cons-wildcard-head-gate: PASS"
