#!/usr/bin/env bash
# v3 — the PRELUDE: a module loaded before the program v3 executes, so its names need no import.
#
# WHY IT EXISTS. `v3/BACKLOG.md`'s DATASET decision offered three ways to give v3 host surface and
# the owner took the third: write the library IN ScalaScript, over lists, so both lanes get it with
# no host surface at all — the answer the project's own rule already points at ("new intrinsics go
# to the plugin, never the core"). That option was recorded as blocked on exactly one thing: the
# corpus calls `Dataset.of` with NO import, v3's module system is markdown links, and there was no
# way for a name to be in scope without one.
#
# WHAT THIS GATE CHECKS, and every line of it is a property that broke or could break silently:
#
#   1. ON      — a name defined only in the prelude resolves.
#   2. OFF     — with `SSC3_PRELUDE=` it does NOT, and the diagnostic still names the symbol.
#                A mechanism that cannot be turned off cannot be measured, and a gate that cannot
#                observe the OFF state is measuring nothing.
#   3. BOTH LANES — the executor and the bridge give the SAME answer. This is the whole point of
#                choosing a ScalaScript library over host surface: option 3 was picked precisely
#                because options 1 and 2 could not give both lanes one implementation.
#   4. NOT IN THE AST — `ssc3 ast` renders the user's program and NOT the prelude's declarations.
#                Every `.expected` fixture under `v3/tests/front/` is a byte-comparison against
#                that render; a prelude visible there would rewrite all of them and put its own
#                text into every future diff.
#   5. EMPTY STAYS EMPTY — a file that declares nothing and executes nothing is still refused with
#                `empty program`. THIS ONE ACTUALLY BROKE: a prelude loaded unconditionally makes
#                every program non-empty, `v3/tests/front/trait-refused.ssc` was accepted, and
#                `front-gate.sh` went red with "the front emits for anything". It was right.
#
# CHECK 5 IS ALSO A CONSISTENCY CHECK BETWEEN TWO FILES, which is the real reason it is here rather
# than left to `front-gate`. `Loader` decides whether to load the prelude using its own emptiness
# test, and `Lower.scala:2227` refuses an empty program using another. They must agree, and they are
# in different files because `Lower.scala` was held by five other claims the day this was written.
# If someone changes either predicate, this check fails.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

say() { printf '  %-6s %s\n' "$1" "$2"; }
SSC3="$PWD/v3/ssc3"
fail=0

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT

# The probe prelude is WRITTEN HERE rather than taken from `v3/prelude/`, so this gate tests the
# MECHANISM and not whatever the shipped prelude happens to contain today. It also means the gate
# passes in a tree that has no prelude at all.
mkdir -p "$T/p"
printf 'def __gateOnlyName(): Int = 4242\n' > "$T/p/index.ssc"
PRE="$T/p/index.ssc"

printf 'def main(): Unit = println(__gateOnlyName())\n' > "$T/use.ssc"
printf 'trait OnlyATrait:\n  def area(): Int\n' > "$T/empty.ssc"

echo "── v3 prelude: on, off, both lanes, and out of the printed Ast ───────────"

got="$(SSC3_PRELUDE="$PRE" "$SSC3" exec "$T/use.ssc" 2>/dev/null | tail -1)"
if [ "$got" = "4242" ]; then say ok "a prelude-only name resolves on the executor"
else say FAIL "prelude ON: expected 4242, got '$got'"; fail=1; fi

off="$(SSC3_PRELUDE= "$SSC3" exec "$T/use.ssc" 2>&1 >/dev/null | tail -1)"
case "$off" in
  *__gateOnlyName*) say ok "with the prelude OFF the same program is refused, naming the symbol" ;;
  *) say FAIL "prelude OFF: expected a refusal naming __gateOnlyName, got '$off'"; fail=1 ;;
esac

br="$(SSC3_PRELUDE="$PRE" "$SSC3" run --bridge "$T/use.ssc" 2>/dev/null | tail -1)"
if [ "$br" = "4242" ]; then say ok "the bridge lane gives the same answer — one implementation, two lanes"
else say FAIL "bridge lane: expected 4242, got '$br'"; fail=1; fi

ast="$(SSC3_PRELUDE="$PRE" "$SSC3" ast "$T/use.ssc" 2>/dev/null)"
case "$ast" in
  *'(def "__gateOnlyName"'*) say FAIL "the prelude's declarations leaked into the printed Ast"; fail=1 ;;
  *) say ok "the printed Ast is the user's program, without the prelude" ;;
esac

emp="$(SSC3_PRELUDE="$PRE" "$SSC3" build "$T/empty.ssc" 2>&1 >/dev/null | tail -1)"
case "$emp" in
  *"empty program"*) say ok "a program with no code is still refused, prelude or not" ;;
  "") say FAIL "an empty program was ACCEPTED because the prelude filled it — Loader and Lower disagree"; fail=1 ;;
  *) say FAIL "empty program: unexpected diagnostic '$emp'"; fail=1 ;;
esac

# A tree with no prelude file behaves exactly as it did before the mechanism existed. Checked with a
# path that does not exist rather than by deleting anything.
none="$(SSC3_PRELUDE="$T/no/such/prelude.ssc" "$SSC3" exec "$T/use.ssc" 2>&1 >/dev/null | tail -1)"
case "$none" in
  *__gateOnlyName*) say ok "a prelude path that does not exist is simply absent, not an error" ;;
  *) say FAIL "missing prelude path: expected the ordinary unknown-name refusal, got '$none'"; fail=1 ;;
esac

# AN ERROR IN THE PRELUDE MUST NAME THE PRELUDE. It used to be reported against the file the user
# named, so a three-line fixture was told its error was at line 38 — a line in the prelude. Positions
# were always counted inside their own file; it was the FILE that was lost, because `LowerFail`'s
# origin is attached in one place per pass and the early passes had none.
printf 'def two(a: Int, b: Int): Int = a + b\ndef bad(): Int = two(1)\n' > "$T/bad-prelude.ssc"
blame="$(SSC3_PRELUDE="$T/bad-prelude.ssc" "$SSC3" build "$T/use.ssc" 2>&1 >/dev/null | tail -1)"
case "$blame" in
  *bad-prelude.ssc:2:*) say ok "an error in the prelude names the prelude, at its own line number" ;;
  *use.ssc*)  say FAIL "the prelude's error was blamed on the user's file: $blame"; fail=1 ;;
  *) say FAIL "unexpected diagnostic for a broken prelude: $blame"; fail=1 ;;
esac

echo
[ "$fail" = 0 ] && echo "== v3 prelude gate: GREEN ==" || echo "== v3 prelude gate: RED =="
[ "$fail" = 0 ]
