#!/usr/bin/env bash
#
# f-plain-class-gate — a plain `class` is a `case class` on this lane, and F now says so.
#
# THE DEFECT: `class C:` was `unbound global: (global C)` on F while the reference ran it.
# `isCCHead` demands the PAIR `case`+`class`, and it is consulted in EIGHT places — the item emitter,
# the given table, the type cases, the subtype registry, the defaults table, the top-level vals, the
# constructor names, the method collector.
#
# THE ESTIMATE THAT CALLED THIS EXPENSIVE WAS PRICED AGAINST THE WRONG SEMANTICS. In Scala a plain
# class has no `apply`, no structural equality and no pattern matching, so handing it case-class
# treatment would be a silent lie — that is what made an eight-site change look necessary. Measured on
# the ORACLE, which is what F must agree with:
#
#   class C: def m(): Int = 42     ref: C().m() = 42     constructs without `new`
#   C() match { case C() => .. }   ref: matched          it IS pattern-matchable
#   C() == C()                     ref: true             it HAS structural equality
#
# The reference already treats the two identically, so there is no distinction to preserve, and the
# fix is ONE site: a bare `class` gets a synthetic `case` in front of it right after `layout(lex(…))`,
# and the eight collectors never learn that plain classes exist. `oracle-parity-*` are the rows that
# justify that, and `match-arm-unchanged` is the row that fails if the token insertion ever touches a
# `case` that belongs to a match arm rather than to a class.
#
# A SECOND, OLDER DEFECT SURFACED UNDER IT and is fixed here too: with NO parameter list at all,
# `collectFields` and `collectPD` walk past the missing `(` and read the `:` as a name — `expected
# Str, got 34`, a CRASH rather than a decline, on a declaration nothing had used. It predates plain
# classes: `case class C:` fails identically on a build without any of this, which is why
# `case-class-no-parens` is a row of its own.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-plain-class.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── a plain class is a case class on this lane"
ssc_usable_or_skip f-plain-class-gate "$ssc"

both() {
  local name=$1 want=$2 src=$3 f r
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  r=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$sandbox/$name.ssc" < /dev/null 2>&1 | head -1)
  f=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" < /dev/null 2>&1 | head -1)
  if [[ "$r" == "$want" && "$f" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: reference='$r' F='$f', wanted '$want' from both"
    fails=$((fails + 1))
  fi
}

# ── the defect ───────────────────────────────────────────────────────────────────────────────────

both class-no-parens 42 'class C:
  def m(): Int = 42

def main(): Unit = println(C().m())'

both class-with-field 42 'class C(a: Int):
  def m(): Int = a * 2

def main(): Unit = println(C(21).m())'

both class-empty-parens 42 'class C():
  def m(): Int = 42

def main(): Unit = println(C().m())'

# The older crash this uncovered: no parameter list, on the CASE spelling, which never involved
# plain classes at all.
both case-class-no-parens 42 'case class C:
  def m(): Int = 42

def main(): Unit = println(C().m())'

# ── oracle parity: the reason one site is enough ────────────────────────────────────────────────

both oracle-parity-match matched 'class C:
  def m(): Int = 42

def main(): Unit =
  C() match
    case C() => println("matched")
    case _ => println("no")'

both oracle-parity-equality true 'class C:
  def m(): Int = 42

def main(): Unit = println(C() == C())'

# ── the shapes that must not move ────────────────────────────────────────────────────────────────
#
# The fix inserts a `case` TOKEN. `match-arm-unchanged` is the row that fails if that insertion ever
# fires on a `case` that opens a match arm instead of a class declaration.

both match-arm-unchanged 1 'def main(): Unit =
  List(1, 2) match
    case h :: t => println(h)
    case _ => println("no")'

both case-class-unchanged 42 'case class P(x: Int):
  def dbl(): Int = x * 2

def main(): Unit = println(P(21).dbl())'

both case-class-defaults 3 'case class P(x: Int, y: Int = 2):
  def s(): Int = x + y

def main(): Unit = println(P(1).s())'

both case-object-unchanged ok 'case object Marker

def main(): Unit = println("ok")'

# ── the corpus files this unblocked ──────────────────────────────────────────────────────────────

for case in parameterless-def-mention named-arg-defaults; do
  subject="$ROOT/tests/conformance/$case.ssc"
  if [[ ! -f "$subject" ]]; then echo "  ⊘ corpus-$case: absent"; continue; fi
  f=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$subject" < /dev/null 2>&1 | head -3 | tr '\n' '|')
  r=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 300 "$ssc" run "$subject" < /dev/null 2>&1 | head -3 | tr '\n' '|')
  if [[ "$f" == "$r" && "$f" != ssc:* ]]; then
    echo "  ✓ corpus-$case: $f"
  else
    echo "  ✗ corpus-$case: F='$f' ref='$r'"
    fails=$((fails + 1))
  fi
done

if [[ $fails -eq 0 ]]; then echo "✓ f-plain-class-gate PASSED"; exit 0; fi
echo "✗ f-plain-class-gate: $fails failure(s)"
exit 1
