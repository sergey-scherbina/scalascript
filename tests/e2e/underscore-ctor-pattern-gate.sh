#!/usr/bin/env bash
#
# A CONSTRUCTOR PATTERN NAMED WITH A LEADING UNDERSCORE MATCHES — and a binder still binds.
#
# WHY THIS EXISTS. `case _Bar(x) =>` never matched: the lexer gives every identifier starting with
# `_` the kind `id` whatever follows it, and F makes a constructor pattern only from kind `uid`, so
# `_Bar` was read as a VARIABLE. Both halves of that were silent — `_Bar(7)` fell through to the
# default arm, and a bare `case _Bar =>` "matched" everything because a binder does. A fall-through
# arm answers, so the program ran and was wrong.
#
# THE RULE IS SCALA'S: a pattern identifier is a binder when it begins with a LOWERCASE letter and a
# stable id otherwise. `_` is not lowercase, so `_Bar` is a constructor, `_bar` is a binder, `_1` is
# a binder. That is why the last two rows are here: a fix that made every underscore name a
# constructor would pass the first row and break every `_acc`-style binder in the corpus.
#
# THREE DECISION SITES, WHICH IS WHY THE FIRST TWO ROWS LOOK REDUNDANT AND ARE NOT. F decides
# "constructor" in `parseArm` (an ordinary match), `parseGenArm` (the ordered resolver) and
# `parsePatId` (the structured sub-pattern parser); the file's own comment calls them the three
# mouths. A one-argument pattern and a NESTED one take different routes, so both are asserted.
#
# Usage: tests/e2e/underscore-ctor-pattern-gate.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2
SSC="${SSC:-bin/ssc}"
[ -x "$SSC" ] || { echo "underscore-ctor-pattern-gate: no launcher at $SSC"; exit 2; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fails=0
run() { # name, source, expected
  printf '%s\n' "$2" > "$TMP/t.ssc"
  local got; got="$(timeout 120 "$SSC" run "$TMP/t.ssc" 2>&1)"
  if [ "$got" = "$3" ]; then printf '  ok   %-34s %s\n' "$1" "$3"
  else printf '  FAIL %-34s got [%s] wanted [%s]\n' "$1" "$(printf '%s' "$got" | tr '\n' '/' | cut -c1-70)" "$3"; fails=$((fails + 1)); fi
}

run "underscore ctor with an argument" \
'case class _Bar(v: Any)
println(_Bar(7) match { case _Bar(x) => "matched " + x.toString; case o => "FELL THROUGH" })' \
"matched 7"

run "underscore ctor, nested subpattern" \
'case class _Bar(v: Any)
case class Box(b: Any)
println(Box(_Bar(7)) match { case Box(_Bar(x)) => "nested " + x.toString; case o => "FELL THROUGH" })' \
"nested 7"

run "underscore ctor TESTS, does not bind" \
'case class _Bar(v: Any)
case class Other(v: Any)
println(Other(1) match { case _Bar(x) => "WRONGLY MATCHED"; case o => "correctly fell through" })' \
"correctly fell through"

run "a lowercase underscore name still binds" \
'case class Bar(v: Any)
println(Bar(3) match { case _bar => "bound " + _bar.toString })' \
"bound Bar(3)"

run "a digit after the underscore still binds" \
'println(List(1, 2) match { case _1 => "bound" })' \
"bound"

run "a plain wildcard is still a wildcard" \
'println(42 match { case _ => "wild" })' \
"wild"

if [ $fails -gt 0 ]; then echo "underscore-ctor-pattern-gate: FAIL ($fails)"; exit 1; fi
echo "underscore-ctor-pattern-gate: OK"
