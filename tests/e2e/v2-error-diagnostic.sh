#!/usr/bin/env bash
#
# v2-error-diagnostic.sh — an uncaught error on the native lane must SAY something.
#
#   ./tests/e2e/v2-error-diagnostic.sh              # check the staged bin/
#   ./tests/e2e/v2-error-diagnostic.sh --self-test  # prove the comparison can fail, then check
#
# WHAT THIS GUARDS
#
# `bin/ssc run` is the default product command, so its uncaught-error output is the first thing
# a user sees when anything goes wrong. Measured 2026-07-28, it said nothing at all:
#
#   throw new RuntimeException("the real message")  ->  ssc: SscThrow
#   List(1,2,3)(9)                                  ->  ssc: 9
#
# `ssc: 9` was the WHOLE diagnostic — not the operation, not the bound, not the line, just the
# index. Two independent defects met at one printer: `SscThrow` was constructed without a
# message, so `StandardMain`'s `getMessage`-or-class-name printed the class name and threw the
# carried VALUE away; and `Prims.listIndex` fell through to Scala's `List.apply`, whose
# `IndexOutOfBoundsException` message is the bare index.
#
# This class of defect is invisible to the conformance suite by construction: a case is graded on
# stdout against `expected/<name>.txt`, and these programs produce no stdout at all. Nothing else
# looks at what the native lane says when it fails.
#
# COMPARE FIRST: every probe runs on BOTH execution lanes, the observed text is compared to the
# required substring, and a mismatch prints `expected=… got=…`. A bare `[[ … ]]` under `set -e`
# is not a test — it can fail while printing nothing (AGENTS.md, "measurement apparatus must
# COMPARE, never PRE-JUDGE").
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

SSC="$ROOT/bin/ssc"
SELF_TEST=0
[[ "${1:-}" == "--self-test" ]] && SELF_TEST=1

WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-error-diag.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

fail=0
pass=0

# probe <name> <source> <required-substring> <forbidden-substring>
#   The forbidden substring is the OLD, content-free answer: asserting only the new text would
#   still pass if some later change reverted to printing the class name alongside it.
probe() {
  local name="$1" src="$2" want="$3" nope="$4"
  printf '%s\n' "$src" > "$WORK/$name.ssc"
  local lane
  for lane in "default" "--interpret"; do
    local args=() got
    [[ "$lane" != "default" ]] && args=("$lane")
    got="$("$SSC" run "${args[@]}" "$WORK/$name.ssc" 2>&1 </dev/null || true)"
    got="$(printf '%s' "$got" | grep -v '^NOTE: Picked up' | grep -v '^Warning: Only java' || true)"
    if [[ "$got" != *"$want"* ]]; then
      echo "FAIL [$name / $lane] diagnostic does not name the failure"
      echo "  expected to contain: $want"
      echo "  got:                 ${got:-<empty>}"
      fail=$((fail + 1))
    elif [[ -n "$nope" && "$got" == *"$nope"* ]]; then
      echo "FAIL [$name / $lane] diagnostic still carries the content-free form"
      echo "  must NOT contain: $nope"
      echo "  got:              ${got:-<empty>}"
      fail=$((fail + 1))
    else
      echo "ok   [$name / $lane] $got"
      pass=$((pass + 1))
    fi
  done
}

if [[ $SELF_TEST -eq 1 ]]; then
  # The comparison must be able to FAIL. Assert a string the diagnostic cannot contain and
  # require the probe to report it; then reset the counters and run the real checks.
  echo "--- self-test: the comparison must be able to fail ---"
  probe selftest 'println(List(1,2,3)(9))' '__this_can_never_appear__' ''
  if [[ $fail -eq 0 ]]; then
    echo "SELF-TEST FAILED: an impossible expectation passed — this gate proves nothing"
    exit 1
  fi
  echo "--- self-test ok ($fail expected failure(s)); running the real checks ---"
  fail=0
  pass=0
fi

# 1. `throw e` must render the thrown VALUE, not the host exception class carrying it.
probe throw-value \
  'def boom(): Int = throw new RuntimeException("the real message")
println(boom())' \
  'the real message' \
  'ssc: SscThrow'

# 2. An out-of-range list index must name the operation and the bound, not just the index.
probe list-index \
  'val xs = List(1,2,3)
println(xs(9))' \
  'out of bounds for list of length 3' \
  ''

# 3. A CAUGHT exception must be unaffected: this changes the message, not the payload.
#    Compared on stdout, so it also proves the program still runs to completion.
printf 'def f(): String =\n  try\n    throw new RuntimeException("inner")\n  catch case e: RuntimeException => "caught:" + e.getMessage\nprintln(f())\n' > "$WORK/catch-payload.ssc"
caught="$("$SSC" run "$WORK/catch-payload.ssc" 2>&1 </dev/null || true)"
if [[ "$caught" == *"caught:inner"* ]]; then
  echo "ok   [catch-payload] $caught"
  pass=$((pass + 1))
else
  echo "FAIL [catch-payload] a caught RuntimeException no longer binds its message"
  echo "  expected to contain: caught:inner"
  echo "  got:                 ${caught:-<empty>}"
  fail=$((fail + 1))
fi

echo
echo "v2-error-diagnostic: $pass ok, $fail FAIL"
[[ $fail -eq 0 ]]
