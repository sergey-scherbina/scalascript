#!/usr/bin/env bash
#
# A VARARG CALL PASSES THE CHECKER AT ANY ARGUMENT COUNT — the cap used to be the parameter COUNT.
#
# WHY THIS EXISTS. `def c(xs: Any*): String = "a"` called as `c(1, 2)` was refused with
# `TYPEERR: cannot unify String: String vs (Int -> t3)`, while the SAME call with the SAME signature
# and the body `xs.length.toString` ran fine. Neither lane was wrong about the program: `ssc1-lower`
# packs a call's trailing arguments into one list (`packVarargsArgs`) and F does the same, so both
# EXECUTE these calls correctly. The refusal came from the CHECKER, which runs on the parsed AST
# BEFORE that packing and applied the arguments one at a time — so the second argument was applied to
# the def's RESULT type.
#
# WHY IT LOOKED LIKE A DIFFERENT BUG. It only fires when the result type is KNOWN. A body ending in a
# selection (`xs.length`, `xs.head`) is typed `TyDyn`, and TyDyn unifies with anything, so the surplus
# application was absorbed in silence. The first probe happened to be a String concatenation, and the
# defect was filed as one — `+` has nothing to do with it, and the rows below say so by pinning a
# literal body, an Int body and an `if` body with no operator in sight.
#
# THE ROW THAT NAMES THE MECHANISM is `three-args-one-param`: `c(1)` always passed and `c(1, 2)` did
# not, so the boundary is the parameter count, not the shape of anything.
#
# THE TyDyn ROWS ARE THE CONTROL, and they are not decoration: a fix that force-packs every call
# would change what those already-passing programs mean. They were green before this gate existed and
# have to stay green.
#
# Usage: tests/e2e/vararg-arity-gate.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2
SSC="${SSC:-bin/ssc}"
[ -x "$SSC" ] || { echo "vararg-arity-gate: no launcher at $SSC"; exit 2; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fails=0

answers() { # name, source, expected
  printf '%s\n' "$2" > "$TMP/t.ssc"
  local out; out="$(timeout 200 "$SSC" run "$TMP/t.ssc" 2>&1 | head -1)"
  if [ "$out" = "$3" ]; then
    printf '  ok   %-40s %s\n' "$1" "$3"
  else
    printf '  FAIL %-40s want [%s] got [%s]\n' "$1" "$3" "$(printf '%s' "$out" | cut -c1-70)"
    fails=$((fails + 1))
  fi
}

starts() { # name, source, expected-prefix
  printf '%s\n' "$2" > "$TMP/t.ssc"
  local out; out="$(timeout 200 "$SSC" run "$TMP/t.ssc" 2>&1 | head -1)"
  case "$out" in
    "$3"*) printf '  ok   %-40s %s...\n' "$1" "$3" ;;
    *) printf '  FAIL %-40s want [%s...] got [%s]\n' "$1" "$3" "$(printf '%s' "$out" | cut -c1-70)"
       fails=$((fails + 1)) ;;
  esac
}

echo "== the result type is KNOWN: refused before the fix =="

answers 'literal-body' \
'def c(xs: Any*): String = "a"
def main(): Unit = println(c(1, 2))' 'a'

answers 'int-body' \
'def c(xs: Any*): Int = 1 + 2
def main(): Unit = println(c(1, 2))' '3'

answers 'if-body' \
'def c(xs: Any*): String = if xs.length > 0 then "hit" else "miss"
def main(): Unit = println(c(1, 2))' 'hit'

answers 'concat-body' \
'def c(xs: Any*): String = xs.length.toString + "!"
def main(): Unit = println(c(1, 2))' '2!'

answers 'no-return-annotation' \
'def c(xs: Any*) = xs.length.toString + "!"
def main(): Unit = println(c(1, 2))' '2!'

answers 'three-args-one-param' \
'def c(xs: Any*): String = "a"
def main(): Unit = println(c(1, 2, 3))' 'a'

answers 'fixed-param-then-vararg' \
'def f(n: Int, xs: Any*): String = n.toString + "/" + xs.length.toString
def main(): Unit = println(f(1, 2, 3))' '1/2'

answers 'val-bound-call' \
'def c(xs: Any*): String = "a"
def main(): Unit =
  val r = c(1, 2)
  println(r)' 'a'

echo "== the vararg is still COLLECTED, not eaten =="

answers 'reads-length' \
'def c(xs: Any*): Int = xs.length
def main(): Unit = println(c(1, 2, 3))' '3'

answers 'reads-elements' \
'def c(xs: Any*): String = xs.toList.toString
def main(): Unit = println(c(1, 2))' 'List(1, 2)'

answers 'fixed-param-reads-both' \
'def f(n: Int, xs: Any*): Int = n + xs.length
def main(): Unit = println(f(10, 1, 2))' '12'

echo "== controls: shapes that already passed and must keep passing =="

answers 'one-arg' \
'def c(xs: Any*): String = "a"
def main(): Unit = println(c(1))' 'a'

answers 'zero-args' \
'def c(xs: Any*): String = "a"
def main(): Unit = println(c())' 'a'

answers 'tydyn-result' \
'def c(xs: Any*): String = xs.length.toString
def main(): Unit = println(c(1, 2))' '2'

answers 'not-a-vararg' \
'def c(ys: List[Any]): String = ys.length.toString + "!"
def main(): Unit = println(c(List(1, 2)))' '2!'

answers 'plain-two-param-def' \
'def c(a: Int, b: Int): Int = a + b
def main(): Unit = println(c(1, 2))' '3'

# The fresh-type-variable COUNTER is in the message and it moves whenever anything upstream
# allocates one more or one fewer, so this row pins the PREFIX. Pinning the whole line made the row
# fail for a reason that is not a defect (`t1` where `t3` was written) the first time it ran.
starts 'arity-error-still-caught' \
'def c(a: Int): Int = a
def main(): Unit = println(c(1, 2))' 'ssc: TYPEERR: in def main: cannot unify Int: Int vs (Int -> t'

answers 'extension-vararg' \
'extension (s: String) def tag(xs: Any*): String = s ++ xs.length.toString
def main(): Unit = println("x".tag(1, 2))' 'x2'

if [ "$fails" -ne 0 ]; then
  echo "vararg-arity-gate: $fails FAILED"
  exit 1
fi
echo "vararg-arity-gate: all rows pass"
