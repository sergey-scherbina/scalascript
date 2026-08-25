#!/usr/bin/env bash
#
# AN UNKNOWN INTERPOLATOR PREFIX REFUSES BY NAME — it does not parse as two expressions.
#
# WHY THIS EXISTS. `println(upper"hello")` printed `<closure>` and then `0`: one call, two lines,
# neither of them the answer. `upper` was read as an identifier and `"hello"` as a separate string,
# because the prefix set was a hardcoded list and anything outside it fell through to "an ordinary
# identifier". A program that says one thing and does another costs more than one that refuses.
#
# TWO FRONTS, BOTH ROWS. This lane runs F first and hands a file F refuses to the legacy front, so a
# refusal made in F alone is silently overridden — measured: F's refusal came back as "F did not
# lower this file; compiled with the default front instead" and the program ran anyway. Both fronts
# carry the rule now, and the third row is what proves the chain: the same source through
# `--native`, which is the other end of it.
#
# THE LAST ROWS ARE THE ONES THAT KEEP THE RULE HONEST. `s`, `md` and an ordinary identifier that
# merely SITS near a string must be untouched; a rule that refused those would pass the first row and
# break the corpus.
#
# Usage: tests/e2e/user-interpolator-gate.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2
SSC="${SSC:-bin/ssc}"
[ -x "$SSC" ] || { echo "user-interpolator-gate: no launcher at $SSC"; exit 2; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fails=0

refuses() { # name, source, [extra flag]
  printf '%s\n' "$2" > "$TMP/t.ssc"
  local out; out="$(timeout 120 "$SSC" run ${3:-} "$TMP/t.ssc" 2>&1)"
  if grep -q "unknown interpolator prefix 'upper'" <<<"$out"; then
    printf '  ok   %-42s refused by name\n' "$1"
  else
    printf '  FAIL %-42s [%s]\n' "$1" "$(printf '%s' "$out" | tr '\n' '/' | cut -c1-90)"; fails=$((fails + 1))
  fi
}
answers() { # name, source, expected
  printf '%s\n' "$2" > "$TMP/t.ssc"
  local out; out="$(timeout 200 "$SSC" run ${4:-} "$TMP/t.ssc" 2>&1)"
  if [ "$out" = "$3" ]; then printf '  ok   %-42s %s\n' "$1" "$3"
  else printf '  FAIL %-42s got [%s] wanted [%s]\n' "$1" "$(printf '%s' "$out" | tr '\n' '/' | cut -c1-60)" "$3"; fails=$((fails + 1)); fi
}

refuses "an undefined prefix" 'println(upper"hello")'
refuses "a prefix defined as an ordinary function" 'def upper(x: Any): String = "U"
println(upper"hello")'
refuses "on the native lane too" 'println(upper"hello")' "--native"

answers "s is untouched" 'val x = 7
println(s"v=$x")' "v=7"
answers "an ordinary call near a string is untouched" 'def tag(x: String): String = x
println(tag("hi"))' "hi"
answers "a string on its own line is still a string" 'val a = 1
println("b")' "b"


# ---------------------------------------------------------------------------
# THE FEATURE ITSELF (landed 2026-08-25). `upper"a${x}b"` IS
# `StringContext(List("a", "b")).upper(x)` — SPEC.md 5.7 — and this lane now builds that call.
#
# WHY EVERY ROW READS BOTH LISTS. The two differ in length by one, and each is produced by its own
# walk, so a row that reads only one of them cannot see the other going wrong. It happened: with the
# argument accumulator replacing instead of appending, `parts` stayed correct for every shape while
# `args.length` answered 1 for every hole count above zero. A wrong count that is still a number
# reads exactly like a working feature.
#
# THE HOLE COUNTS ARE 0, 1, 2 AND 3, and the two-hole case appears both SEPARATED and ADJACENT —
# adjacent holes are the case that needs an empty literal between them, which `interpParts` does not
# emit on its own because concatenation does not need it.
J='extension (sc: StringContext) def j(args: Any*): String = sc.parts.mkString("|") + "/" + args.length.toString'

answers "no holes" "$J
println(j\"plain\")" "plain/0"
answers "empty string" "$J
println(j\"\")" "/0"
answers "one hole" "$J
val a = 1
println(j\"x\${a}y\")" "x|y/1"
answers "two holes, separated" "$J
val a = 1
val b = 2
println(j\"x\${a}y\${b}z\")" "x|y|z/2"
answers "two holes, adjacent" "$J
val a = 1
val b = 2
println(j\"\$a\$b\")" "||/2"
answers "three holes" "$J
val a = 1
val b = 2
val c = 3
println(j\"\${a}-\${b}-\${c}\")" "|-|-|/3"
answers "a bare \$ident hole" "$J
val nm = 1
println(j\"hi \$nm!\")" "hi |!/1"
answers "an expression hole" "$J
println(j\"\${1 + 2}\")" "|/1"

# The holes must arrive IN ORDER and by VALUE, not merely in the right number.
answers "hole values, in order" 'extension (sc: StringContext)
  def k(args: Any*): String = args.mkString(",")
val a = 7
val b = 8
println(k"x${a}y${b}z")' "7,8"

# SPEC.md 5.7 verbatim — the example the documentation ships.
answers "the SPEC 5.7 example" 'extension (sc: StringContext)
  def upper(args: Any*): String =
    var out = ""
    var i = 0
    sc.parts.foreach { p =>
      out = out + p.toUpperCase
      if i < args.length then out = out + args(i).toString
      i = i + 1
    }
    out

val name = "ada"
println(upper"hello $name, welcome")' "HELLO ada, WELCOME"

# The class is SYNTHESIZED only when the program does not declare it — a user's own class wins, and
# the synthesized one must not collide with it.
answers "a user-declared StringContext wins" 'case class StringContext(parts: List[String])
extension (sc: StringContext) def j(args: Any*): String = sc.parts.mkString("|")
println(j"q")' "q"

# The other end of the chain, for the feature as well as for the refusal above.
answers "the feature on the native lane too" "$J
val a = 1
println(j\"x\${a}y\")" "x|y/1" "--native"

if [ $fails -gt 0 ]; then echo "user-interpolator-gate: FAIL ($fails)"; exit 1; fi
echo "user-interpolator-gate: OK"
