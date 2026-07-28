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
# Same hazard, other entries: v2-front-curried-def-second-clause (F drops a curried def's second
# clause and delegates) and v2-front-for-yield-remaining-layouts (this one).
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
  echo "--- self-test: a construct F genuinely cannot lower must report GAP ---"
  # A curried def is a known, filed F gap (v2-front-curried-def-second-clause). If this reports
  # F, either that bug was fixed — update this probe — or the report column is not being read.
  want_front selftest GAP 'def ap(n: Int)(f: Int => Int): Int = f(n)
println(ap(3)((i) => i * 2))'
  if [[ $fail -ne 0 ]]; then
    echo "SELF-TEST FAILED: the known GAP did not report GAP — this gate proves nothing"
    exit 1
  fi
  echo "--- self-test ok; running the real checks ---"
  pass=0; fail=0
fi

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
