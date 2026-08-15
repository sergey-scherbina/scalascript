#!/usr/bin/env bash
# The v2 type checker's unification errors must NAME THE TWO TYPES, not just the constructor that
# mismatched.
#
# Why this is a gate and not a nicety. Until 2026-08-04 every unify failure read
# `cannot unify Tuple with non-Tuple`, which names neither side. Chasing
# `v2-three-parameter-clauses-fail-typecheck` with that message, the obvious reading — "a tuple
# literal is involved somewhere" — is wrong: the tuple is the EMPTY one, i.e. Unit, and the other
# side is a function. Naming both sides turned it into `cannot unify tuple: () vs (Int -> t6)`,
# which says "a zero-argument application is meeting a one-argument function" in one run.
#
# ── 2026-08-15: THIS GATE ENCODED A DEFECT THAT HAS SINCE BEEN FIXED, AND ITS OWN COMMENT SAID SO ──
#
# The `curried-three-clauses` case ran `def tri(a)(b)(c)` and required the message
# `cannot unify tuple: () vs (Int -> t6)`. That program was expected to FAIL type checking —
# `v2-three-parameter-clauses-fail-typecheck` — and that entry is `status: fixed`, `6d0066d14`. The
# program now prints **6**, so the case asserted an error that no longer exists and the gate had been
# red ever since. Nobody saw it: the gate is invoked by nothing (tests/BUGS.md
# `orphaned-e2e-gates-52`).
#
# THE AUTHOR PREDICTED THIS EXACTLY AND THEN WROTE THE OPPOSITE. The note above the case read: *"this
# gate asserts the QUALITY OF THE MESSAGE, not that the program works — so it keeps passing once that
# bug is fixed only if the message shape survives, which is why the row below is the generic one
# rather than this specific text."* The row below was the SPECIFIC text. The mitigation existed in
# prose and not in the assertion, which is the whole failure: a comment cannot hold an invariant.
#
# So every case now asserts the GENERIC shape — a name, a colon, two sides separated by ` vs ` — and
# no case depends on any particular program remaining broken. Two sources are used, both of which
# still fail unification today, and both were measured rather than assumed:
#
#   def g(): Int = 1;             println(g(5))         -> cannot unify Int: Int vs (Int -> t0)
#   def two(a: Int)(b: Int) …;    println(two(1)(2)(3)) -> cannot unify Int: Int vs (Int -> t5)
#
# AND THE SELF-TEST NO LONGER DEPENDS ON A PROGRAM BEING BROKEN EITHER. It used to plant `tri` and
# require the gate to fail; once `tri` started working, that failure came from the program succeeding
# rather than from the message being uninformative, so it would have passed against a checker that
# had stopped type-checking altogether — the exact hole the self-test exists to close. It now tests
# the MATCHER against literal message text, in both directions, which nothing about the compiler's
# current behaviour can rot.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SSC="$ROOT/bin/ssc"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-typeerr.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

# A unify error names BOTH sides: `<what>: <left> vs <right>`. The old shape,
# `cannot unify Tuple with non-Tuple`, has no colon and no ` vs `, so it cannot match.
BOTH_SIDES='cannot unify [^:]+: .+ vs .+'

# want_typeerr <name> <source> — the program must be REJECTED, with a message naming both types.
want_typeerr() {
  local name="$1" src="$2" out
  printf '%s\n' "$src" > "$WORK/$name.ssc"
  out="$("$SSC" run "$WORK/$name.ssc" 2>&1 | head -3)"
  if printf '%s' "$out" | grep -qE "$BOTH_SIDES"; then
    echo "ok   [$name]"
    pass=$((pass + 1))
  else
    echo "FAIL [$name] the type error did not name both types"
    echo "  wanted (regex): $BOTH_SIDES"
    echo "  got:            $out"
    fail=$((fail + 1))
  fi
}

if [[ "${1:-}" == "--self-test" ]]; then
  echo "--- self-test: the matcher itself, on literal text, both directions ---"
  st_fail=0
  # The shape this gate exists to prevent regressing to. It must NOT satisfy the matcher.
  if printf '%s' 'ssc: TYPEERR: cannot unify Tuple with non-Tuple' | grep -qE "$BOTH_SIDES"; then
    echo "FAIL the old uninformative shape SATISFIES the matcher — this gate cannot fail"
    st_fail=1
  else
    echo "ok   the old shape 'cannot unify Tuple with non-Tuple' is rejected"
  fi
  # A real current message must satisfy it, or the gate is red for everyone on a healthy checker.
  if printf '%s' 'ssc: TYPEERR: cannot unify Int: Int vs (Int -> t5)' | grep -qE "$BOTH_SIDES"; then
    echo "ok   a both-sides message is accepted"
  else
    echo "FAIL a well-formed both-sides message is REJECTED — the matcher is too strict"
    st_fail=1
  fi
  # And a checker that says nothing at all must not read as success.
  if printf '%s' '6' | grep -qE "$BOTH_SIDES"; then
    echo "FAIL a program's ordinary OUTPUT satisfies the matcher"
    st_fail=1
  else
    echo "ok   ordinary program output does not read as a type error"
  fi
  [[ $st_fail -eq 0 ]] || { echo "SELF-TEST FAILED"; exit 1; }
  echo "--- self-test ok; running the real checks ---"
fi

# Over-applying a zero-argument def. Measured 2026-08-15: cannot unify Int: Int vs (Int -> t0)
want_typeerr zero-arg-over-applied \
'def g(): Int = 1
println(g(5))'

# Over-applying a two-clause curried def. Measured 2026-08-15: cannot unify Int: Int vs (Int -> t5)
want_typeerr curried-over-applied \
'def two(a: Int)(b: Int): Int = a + b
println(two(1)(2)(3))'

echo
if [[ $fail -eq 0 ]]; then
  echo "typeerr-names-both-types: OK ($pass checks)"
  exit 0
fi
echo "typeerr-names-both-types: $pass ok, $fail FAIL"
exit 1
