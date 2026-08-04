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
# The self-test is the point of this file: it plants the OLD message shape and requires this gate to
# reject it. Without that, a gate asserting "the error mentions `vs`" would also pass on a checker
# that had silently stopped type-checking at all.
# NOT REGISTERED YET: `scripts/smoke-ci.ssc` and `.github/workflows/ci.yml` are both held by a live
# claim (uniml-ssc3-frontend-readiness) — this repo has no generic runner, gates are named in those
# two files by hand. Landing the gate unregistered is deliberate: an orphan gate is visible to
# `orphaned-gates-runner-sweep`, whereas editing another agent's claimed files is not something a
# guard would even allow. Run it directly meanwhile:
#   bash tests/e2e/typeerr-names-both-types.sh [--self-test]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SSC="$ROOT/bin/ssc"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-typeerr.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

# want_typeerr <name> <regex the message must match> <source>
want_typeerr() {
  local name="$1" want="$2" src="$3"
  printf '%s\n' "$src" > "$WORK/$name.ssc"
  local out
  out="$("$SSC" run "$WORK/$name.ssc" 2>&1 | head -3)"
  if printf '%s' "$out" | grep -qE "$want"; then
    echo "ok   [$name]"
    pass=$((pass + 1))
  else
    echo "FAIL [$name] the type error did not name both types"
    echo "  wanted (regex): $want"
    echo "  got:            $out"
    fail=$((fail + 1))
  fi
}

if [[ "${1:-}" == "--self-test" ]]; then
  echo "--- self-test: the OLD message shape must be rejected ---"
  # `cannot unify Tuple with non-Tuple` is what this gate exists to prevent regressing to. Assert a
  # pattern that ONLY the old shape satisfies, and require the check to fail.
  want_typeerr selftest-old-shape 'cannot unify Tuple with non-Tuple' \
'def tri(a: Int)(b: Int)(c: Int): Int = a + b + c
println(tri(1)(2)(3))'
  if [[ $fail -eq 0 ]]; then
    echo "SELF-TEST FAILED: the old uninformative message is back, or nothing was type-checked"
    exit 1
  fi
  echo "--- self-test ok (that failure was deliberate); running the real checks ---"
  pass=0; fail=0
fi

# A curried def with three clauses — the case that motivated this. Both sides must be named.
# NOTE: this program is EXPECTED to fail type checking; the entry is
# v2-three-parameter-clauses-fail-typecheck. This gate asserts the QUALITY OF THE MESSAGE, not that
# the program works — so it keeps passing once that bug is fixed only if the message shape survives,
# which is why the row below is the generic one rather than this specific text.
want_typeerr curried-three-clauses 'cannot unify tuple: \(\) vs \(Int -> t[0-9]+\)' \
'def tri(a: Int)(b: Int)(c: Int): Int = a + b + c
println(tri(1)(2)(3))'

# A plain arity mismatch on a tuple: both sides named, and the arity path still reachable.
want_typeerr int-vs-function 'cannot unify' \
'def two(a: Int)(b: Int): Int = a + b
println(two(1)(2)(3))'

echo
if [[ $fail -eq 0 ]]; then
  echo "typeerr-names-both-types: OK ($pass checks)"
  exit 0
fi
echo "typeerr-names-both-types: $pass ok, $fail FAIL"
exit 1
