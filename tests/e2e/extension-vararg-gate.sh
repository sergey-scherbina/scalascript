#!/usr/bin/env bash
#
# A VARARG PARAMETER IN AN EXTENSION METHOD COLLECTS THE ARGUMENTS — in all three call shapes.
#
# WHY THIS EXISTS. `SC(1).g(7, 8)` with `def g(xs: Any*)` bound `xs` to the LAST argument, `8`, and
# the program died on `8.length`. One fact caused it: the vararg registry records the parameter's
# index from the WRITTEN parameter list, while the extension lift prepends the receiver — so every
# index the collapse used was one short. The same off-by-one had three faces, and each is a row here,
# because a fix that shifts the index alone leaves the other two wrong in DIFFERENT ways:
#
#   * called through the dot          — the collapse never ran at all (runtime dispatch, not the front)
#   * called as an ordinary function  — the collapse ran with the wrong index and ate the receiver
#   * the eligibility test            — `dlen(slices) == dlen(pd)` is one short with a receiver present,
#                                       so after the index was shifted this shape stopped collapsing
#                                       entirely: `arity: 2 expected, 3 given`
#
# THE LAST ROWS ARE THE CONTROL. Ordinary vararg functions were always correct, and a fix reached by
# widening the collapse could break them without any row above noticing.
#
# THE FIRST PROBE FOR THIS BUG SAID IT WORKED, because its body never touched `xs`. Every row below
# READS the vararg — its length or its elements — for that reason.
#
# Usage: tests/e2e/extension-vararg-gate.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2
SSC="${SSC:-bin/ssc}"
[ -x "$SSC" ] || { echo "extension-vararg-gate: no launcher at $SSC"; exit 2; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fails=0

answers() { # name, source, expected
  printf '%s\n' "$2" > "$TMP/t.ssc"
  local out; out="$(timeout 200 "$SSC" run "$TMP/t.ssc" 2>&1 | head -1)"
  if [ "$out" = "$3" ]; then
    printf '  ok   %-46s %s\n' "$1" "$3"
  else
    printf '  FAIL %-46s want [%s] got [%s]\n' "$1" "$3" "$(printf '%s' "$out" | cut -c1-70)"
    fails=$((fails + 1))
  fi
}

PRE='case class SC(p: Int)'

answers "extension vararg, .length through the dot" \
  "$PRE
extension (sc: SC) def g(xs: Any*): String = xs.length.toString
println(SC(1).g(7, 8))" "2"

answers "extension vararg, elements through the dot" \
  "$PRE
extension (sc: SC) def g(xs: Any*): String = xs.mkString(\",\")
println(SC(1).g(7, 8))" "7,8"

answers "extension vararg, called as a function" \
  "$PRE
extension (sc: SC) def g(xs: Any*): String = xs.length.toString
println(g(SC(1), 7, 8))" "2"

answers "extension vararg reads the receiver too" \
  "$PRE
extension (sc: SC) def g(xs: Any*): String = sc.p.toString
println(SC(9).g(7, 8))" "9"

answers "extension vararg, no arguments at all" \
  "$PRE
extension (sc: SC) def g(xs: Any*): String = xs.length.toString
println(SC(1).g())" "0"

# ---- the control: ordinary vararg functions, which were never broken ----

answers "plain vararg still collects" \
  "def up(xs: Any*): String = xs.length.toString
println(up(7, 8))" "2"

answers "plain vararg after a fixed parameter" \
  "def up(p: Int, xs: Any*): String = xs.length.toString
println(up(1, 7, 8))" "2"

answers "plain vararg, no arguments" \
  "def up(xs: Any*): String = xs.length.toString
println(up())" "0"

# ---- the whole reason the bug mattered: SPEC.md §5.7's interpolator shape ----

answers "an interpolator body runs end to end" \
  'case class StringContext(parts: List[String])
extension (sc: StringContext)
  def upper(args: Any*): String =
    var out = ""
    var i = 0
    sc.parts.foreach { p =>
      out = out + p.toUpperCase
      if i < args.length then out = out + args(i).toString
      i = i + 1
    }
    out
println(StringContext(List("hello ", ", welcome")).upper("ada"))' "HELLO ada, WELCOME"

if [ "$fails" -eq 0 ]; then echo "extension-vararg-gate: all rows green"; exit 0; fi
echo "extension-vararg-gate: $fails row(s) failed"; exit 1
