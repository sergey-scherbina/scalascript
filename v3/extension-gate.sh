#!/usr/bin/env bash
# v3 extension gate — keeps `Lower`'s built-in vocabulary honest against `Exec`'s actual table.
#
#   v3/extension-gate.sh              the check
#   v3/extension-gate.sh --self-test  prove it can go RED
#
# ── WHY THIS EXISTS ──────────────────────────────────────────────────────────────────────────────
#
# `specs/ssc3-extensions.md` §3: `v.m(args)` is rewritten to `m(v, args)` only when `m` is neither
# declared by a class in the merged program NOR a built-in method name. The third condition needs a
# list of `Exec`'s built-in method names inside `Lower`, and **a hand-written copy of another file's
# table is the shape this repository keeps paying for** — it goes stale in the direction nobody
# notices.
#
# Here the bad direction is exact: if `Exec` gains a method and `Lower`'s list does not have it, an
# extension with that name is rewritten and SHADOWS the built-in. The program then answers the
# extension where it should have answered the method — a wrong answer, weeks after the commit that
# caused it, in a different file.
#
# So the list is DERIVED here and compared, and the failure lands on the commit that adds the
# built-in rather than on the one that trips over it.
#
# ── WHY THE EXTRACTION IS DELIBERATELY OVER-INCLUSIVE ────────────────────────────────────────────
#
# It takes every string literal in `Exec.invoke`, which sweeps in type names (`Cons`, `Nil`, `Some`)
# and diagnostic fragments along with the method names. That is not sloppiness, it is the safe
# direction: an extra name can only PREVENT a rewrite, never permit one. Missing a name is the only
# way this can be wrong, and a method name in `invoke` is always a string literal, so the only way to
# miss one is a name computed at run time — which `invoke` does not do.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

EXEC="v3/src/Exec.scala"
LOWER="v3/src/Lower.scala"

# Every string literal inside `invoke`, whatever it looks like — words and operators alike, since an
# extension may be named `~` as `tests/conformance/f-tilde-arrow-ext.ssc` is.
exec_vocab() {
  awk '/private def invoke\(/,/private def constOf/' "$EXEC" |
    # COMMENTS ARE NOT VOCABULARY, and this gate learned that by going red on main. `f77b5c46f`
    # documented the new String*Int lowering with `` // `"ab" * 3` IS "ababab" — … ``; two quoted
    # runs on one comment line, so the extractor below took `ababab` for a method `Exec` answers to
    # and the gate demanded `Lower` list it. Nothing was wrong with the code or the comment — the
    # reader was. A gate that parses prose will keep inventing names, and the alternative (reword
    # every comment that quotes an example) puts the burden on everyone who ever explains a lowering.
    #
    # Whole-line `//` only, deliberately narrow: a trailing comment after real code would take the
    # code with it if stripped naively, and a `//` inside a string literal is exactly the shape this
    # gate exists to read. Block comments are not stripped either — `/* … */` does not appear in this
    # range today, and guessing at a shape that is not there is how the next false positive gets in.
    grep -vE '^[[:space:]]*//' |
    grep -oE '"[^"]+"' | sed 's/^"//; s/"$//' |
    # A method name is an identifier or an operator, and nothing else. Without this the fragments of
    # diagnostics come through — `invoke`'s refusal is built as `"method '" + name + "' on "`, so a
    # lone apostrophe arrived in the vocabulary and the self-test dutifully planted on it. Still
    # over-inclusive INSIDE those two shapes (type names like `Cons` stay), which is the safe
    # direction; this only removes strings that cannot be a method name at all.
    grep -E '^([A-Za-z_][A-Za-z0-9_]*|[-+*/%<>=!&|^~:]+)$' |
    LC_ALL=C sort -u
}

# What `Lower` claims to know, from the block the lowering reads. The marker is a comment so the
# list has one home and this script does not have to parse Scala.
lower_vocab() {
  sed -n '/BUILTIN-VOCABULARY-BEGIN/,/BUILTIN-VOCABULARY-END/p' "$LOWER" |
    # Same comment filter as `exec_vocab`, and on THIS side it guards the dangerous direction. The
    # check is "Lower ⊇ Exec", so a quoted example in a comment here does not add a false alarm — it
    # SILENTLY SATISFIES the check for a name Lower does not actually handle. Fixing only the Exec
    # side would have left that twin.
    #
    # MEASURED before adding, because a filter that changes nothing still has to be shown to change
    # nothing: this block holds 17 comment lines, and the vocabulary is 152 names with or without
    # them. Nothing is masked today and no name is lost — the filter is here for the comment somebody
    # writes next, not for one already written.
    grep -vE '^[[:space:]]*//' |
    grep -oE '"[^"]+"' | sed 's/^"//; s/"$//' |
    LC_ALL=C sort -u
}

check() {
  local quiet="${1:-}"
  local ev lv missing
  ev="$(exec_vocab)"
  lv="$(lower_vocab)"
  if [ -z "$ev" ]; then
    echo "  ✋ extracted NOTHING from $EXEC — the awk range no longer matches, so this proves nothing"
    return 2
  fi
  if [ -z "$lv" ]; then
    echo "  RED  $LOWER has no BUILTIN-VOCABULARY block — the rewrite has no way to avoid shadowing"
    echo "       a built-in method. See specs/ssc3-extensions.md §4."
    return 1
  fi
  missing="$(LC_ALL=C comm -23 <(printf '%s\n' "$ev") <(printf '%s\n' "$lv"))"
  if [ -n "$missing" ]; then
    echo "  RED  $LOWER's vocabulary is missing name(s) that $EXEC answers to:"
    printf '%s\n' "$missing" | sed 's/^/         /'
    echo "       An extension with one of those names would SHADOW the built-in. Add them to the"
    echo "       BUILTIN-VOCABULARY block; the direction that matters is that Lower's list is a"
    echo "       SUPERSET of Exec's."
    return 1
  fi
  [ -n "$quiet" ] || echo "  ok   Lower's vocabulary covers all $(printf '%s\n' "$ev" | grep -c .) name(s) Exec answers to"
  return 0
}

if [ "${1:-}" = "--self-test" ]; then
  fails=0
  echo "── self-test: can this gate go RED? ────────────────────────────────────"
  if check quiet >/dev/null 2>&1; then
    echo "  ok   baseline is GREEN, so a planted failure is distinguishable from it"
  else
    echo "  FAIL baseline is already RED — plant nothing, fix that first"
    check | sed 's/^/       /'
    fails=$((fails + 1))
  fi
  # Plant: drop one name Exec answers to from Lower's view. Done by narrowing `lower_vocab`, not by
  # editing the file, so an interrupted run cannot leave the repository wrong.
  _victim="$(exec_vocab | head -1)"
  # SAVED SO IT CAN BE UNPLANTED. The plant below REDEFINES `lower_vocab`, which was harmless while
  # the self-test exited immediately after — and became wrong the moment it started falling through
  # to the real check: the check inherited the narrowed vocabulary and reported RED against a tree
  # that is GREEN. Caught by running both modes and comparing, not by reading the code.
  _lower_vocab_orig="$(declare -f lower_vocab)"
  lower_vocab() {
    sed -n '/BUILTIN-VOCABULARY-BEGIN/,/BUILTIN-VOCABULARY-END/p' "$LOWER" |
      grep -oE '"[^"]+"' | sed 's/^"//; s/"$//' | grep -vxF "$_victim" | LC_ALL=C sort -u
  }
  if check quiet >/dev/null 2>&1; then
    echo "  FAIL removing '$_victim' from Lower's vocabulary left the check GREEN — it is not comparing"
    fails=$((fails + 1))
  else
    echo "  ok   a name missing from Lower's vocabulary ('$_victim') goes RED"
  fi
  eval "$_lower_vocab_orig"        # UNPLANT before the real check runs below
  echo
  [ "$fails" = 0 ] && echo "== extension gate self-test: the gate discriminates ==" \
                   || echo "== extension gate self-test: $fails rule(s) DID NOT FIRE =="
  [ "$fails" = 0 ] || exit "$fails"
  # FALLS THROUGH TO THE REAL CHECK, like `no-orphan-gates.sh` and `v1-jit-size.sh`: one invocation
  # does both. It used to `exit` here, which meant a suite wiring `--self-test` got the proof that
  # the gate CAN fail and never the answer to what it actually guards — a gate that only ever
  # rehearses. Added 2026-08-16 while draining this script from the orphan list, where it had sat
  # green and unrun.
fi

echo "── extension rewrite: Lower's vocabulary vs Exec's table ───────────────"
check
rc=$?
echo
[ "$rc" = 0 ] && echo "== v3 extension gate: GREEN ==" || echo "== v3 extension gate: RED =="
exit "$rc"
