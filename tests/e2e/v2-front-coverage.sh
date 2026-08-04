#!/usr/bin/env bash
#
# v2-front-coverage.sh — does F actually COMPILE these shapes, or does it decline and let the
# fallback do it?
#
#   ./tests/e2e/v2-front-coverage.sh              # check the staged bin/
#   ./tests/e2e/v2-front-coverage.sh --self-test  # prove the check can fail, then check
#
# WHY THIS EXISTS, and why it is not a conformance case.
#
# When F declines a file, the F4a fallback recompiles it with the legacy front and the program
# produces the RIGHT OUTPUT. So any gate that compares output is green whether or not F can handle
# the construct. Measured, and it caught me: `tests/conformance/for-yield-layout.ssc` contains a
# layout F could not lower, and that case was green — because the fallback supplied the expected
# text. An output comparison cannot see which front compiled the file.
#
# `ssc info --front-report` answers exactly that question and nothing else: it prints
# `<file>\t<F|GAP|ERROR>\t<reason>` without executing the program. This gate asserts on that
# column, so "F silently stopped handling X" fails here even while every output test stays green.
#
# Same hazard, other entries: v2-front-for-yield-remaining-layouts (this one). The curried-def
# entry was the other example and is FIXED (2026-08-04) — F lowers `def f(a)(b)` and flattens the
# call site now, so it is a coverage row below rather than a known gap.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SSC="$ROOT/bin/ssc"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-front-cov.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

# want_front <name> <F|GAP> <source>
want_front() {
  local name="$1" want="$2" src="$3"
  printf '%s\n' "$src" > "$WORK/$name.ssc"
  local line got reason
  line="$("$SSC" info --front-report "$WORK/$name.ssc" 2>&1 | tail -1)"
  got="$(printf '%s' "$line" | awk -F'\t' '{print $2}')"
  reason="$(printf '%s' "$line" | awk -F'\t' '{print $3}')"
  if [[ "$got" == "$want" ]]; then
    echo "ok   [$name] front=$got"
    pass=$((pass + 1))
  else
    echo "FAIL [$name] F coverage changed"
    echo "  expected front: $want"
    echo "  got:            ${got:-<none>}  ${reason:-}"
    echo "  source:"; printf '%s\n' "$src" | sed 's/^/    /'
    fail=$((fail + 1))
  fi
}

if [[ "${1:-}" == "--self-test" ]]; then
  echo "--- self-test: a WRONG expectation must be caught ---"
  # This used to anchor on a known GAP — a curried def, filed as
  # v2-front-curried-def-second-clause — and assert it still reported GAP. That design guarantees
  # the self-test breaks the day someone FIXES the bug, which is what happened (2026-08-04): the
  # curried-def gap closed and this probe started failing for the right reason, which is a bad
  # signal to send. Worse, it needed a filed gap to exist at all, and that was the last open one.
  #
  # The self-test's actual job is to prove the gate can DISTINGUISH the two states. Asserting that
  # a wrong expectation FAILS proves exactly that, and does not rot: a file F certainly lowers is
  # declared GAP on purpose, and want_front must reject it.
  want_front selftest-inverted GAP 'def id(n: Int): Int = n
println(id(1))'
  if [[ $fail -eq 0 ]]; then
    echo "SELF-TEST FAILED: want_front accepted a wrong expectation — this gate proves nothing"
    exit 1
  fi
  echo "--- self-test ok (the mismatch above was deliberate); running the real checks ---"
  pass=0; fail=0
fi

# ── curried defs: F must lower them AND flatten the call site ──────────────────────────
# Both halves or neither. Half 1 alone (the def lowers at total arity, the call still nests) makes
# this row report F while the program dies at run time with `arity: 2 expected, 1 given` — which is
# how the first attempt at this fix looked like progress. The conformance case
# tests/conformance/curried-def-clauses.ssc pins the OUTPUT; this pins which front produced it.
want_front curried-two-clauses F 'def ap(n: Int)(f: Int => Int): Int = f(n)
println(ap(3)((i) => i * 2))'
# THREE clauses are NOT here: `def tri(a)(b)(c)` dies with `TYPEERR: cannot unify Tuple with
# non-Tuple` — and measured on the unmodified toolchain it does the same, so that is a pre-existing
# type-checker defect, not this front's. Filed as v2-three-parameter-clauses-fail-typecheck.
# The discriminator: a def that RETURNS a function is called the same way and must stay NESTED.
# If flattening keyed on syntax instead of the callee table, this would lower to a 2-arity call.
want_front returns-a-function F 'def mk(n: Int): Int => Int = (x) => x + n
println(mk(10)(5))'

# ── for/yield: all four layouts must be compiled BY F, not delegated ────────────────────
want_front foryield-sameline-col0 F 'val xs = List(1,2)
val r = for
  i <- xs
yield i
println(r.length)'

want_front foryield-sameline-indent F 'val xs = List(1,2)
val r = for
  i <- xs
  yield i
println(r.length)'

want_front foryield-contline F 'val xs = List(1,2)
val r =
  for
    i <- xs
  yield i
println(r.length)'

want_front foryield-defbody F 'val xs = List(1,2)
def f(): List[Int] =
  for
    i <- xs
  yield i
println(f().length)'

# ── shapes fixed earlier today, pinned so F cannot quietly stop handling them ───────────
want_front colon-trailing-lambda F 'def ap(n: Int)(g: Int => Int): Int = 0
def useColon(): Int =
  ap(3): (i) =>
    i * 2
println(useColon())'

want_front try-multistatement-body F 'def w(): String =
  try
    val x = "ok"
    x
  catch case e: Throwable => "caught"
println(w())'

echo
echo "v2-front-coverage: $pass ok, $fail FAIL"
[[ $fail -eq 0 ]]
