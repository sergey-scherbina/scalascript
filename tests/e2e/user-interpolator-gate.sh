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
  local out; out="$(timeout 120 "$SSC" run "$TMP/t.ssc" 2>&1)"
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

if [ $fails -gt 0 ]; then echo "user-interpolator-gate: FAIL ($fails)"; exit 1; fi
echo "user-interpolator-gate: OK"
