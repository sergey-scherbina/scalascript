#!/usr/bin/env bash
# The reference front must not silently disagree with the other two lanes on an operator.
#
# WHY THIS EXISTS. `xs -- ys` had no token in the reference front's lexer. The two minus signs
# lexed as unary minus applied twice, so `Set(1,2,3) -- Set(2)` did not error -- it quietly
# produced a different value, while the v1 interpreter and front F both answered Set(1, 3).
# Nothing was red. The class of defect is "operator absent from ONE front", and the only thing
# that catches it is running the same source on all three lanes and comparing.
#
# WHAT IT ASSERTS. For each probe, all three lanes produce byte-identical stdout. Agreement on
# an ERROR counts: `List(1,2,3) -- List(2)` must be a loud "No method" everywhere, and a lane
# that answers instead of erroring is exactly the bug. The v1 interpreter prefixes its
# diagnostics differently, so error rows are compared on the message, not the whole line.
#
# `++` rides along deliberately. It is the mirror operator and the source of the lowering the
# `--` fix was modelled on; a copy-paste regression there would otherwise be invisible.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
export SSC_NO_BUILD_CHECK=1
SELFTEST=0; [ "${1:-}" = "--self-test" ] && SELFTEST=1
. tests/e2e/lib/ssc-usable.sh 2>/dev/null || true
if [ $SELFTEST -eq 0 ] && command -v ssc_usable_or_skip >/dev/null 2>&1; then
  ssc_usable_or_skip ref-front-three-defects-gate ./bin/ssc || exit 0
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/ref3.XXXXXX"); trap 'rm -rf "$work"' EXIT HUP INT TERM
fail=0

# Each probe: a name and one line of ssc printing a single value.
probe() {
  local name=$1 body=$2
  printf 'def main() =\n  println(%s)\n' "$body" > "$work/$name.ssc"

  local ref f interp
  ref=$(SSC_FRONT=legacy    timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -1)
  f=$(SSC_FRONT_STRICT=1    timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -1)
  interp=$(timeout 90 ./bin/ssc-tools run --v1 "$work/$name.ssc" 2>&1 | head -1)

  # Two lane-local renderings, neither of which is about the operator, are normalised away:
  #   - the interpreter brackets its diagnostics: "[ERROR] [line 3, col 28] No method ..."
  #   - and renders the offending value through its INTERNAL Show, which shows the runtime
  #     constructor: "on ListV(List(1, 2, 3))" where the compiled lanes say "on List(1, 2, 3)".
  # Both are cosmetic and pre-date this gate. The value itself stays in the comparison -- only
  # the `XxxV(...)` wrapper comes off -- so a lane erroring on the WRONG value is still caught.
  strip() {
    sed -E 's/^\[ERROR\] \[[^]]*\] //; s/^ssc: //; s/ on [A-Za-z]+V\((.+)\)$/ on \1/'
  }
  ref=$(printf '%s' "$ref" | strip); f=$(printf '%s' "$f" | strip)
  interp=$(printf '%s' "$interp" | strip)

  # The self-test corrupts ONE lane to prove this comparison can go red. Without it the gate
  # asserts a property that held before the fix too, and would have passed on the bug.
  [ $SELFTEST -eq 1 ] && [ "$name" = "set-diff" ] && ref="Set(1, 2, 3)"

  if [ "$ref" = "$f" ] && [ "$f" = "$interp" ]; then
    printf '  %-14s %s\n' "$name" "$ref"
  else
    printf '  %-14s РАСХОЖДЕНИЕ\n    эталон:       %s\n    F:            %s\n    интерпретатор:%s\n' \
      "$name" "$ref" "$f" "$interp"
    fail=$((fail+1))
  fi
}

# A whole program rather than one expression: the vararg defect needs a CURRIED callee with
# defaults, and currying is the whole reason it exists -- the front pre-fills the first clause's
# defaults as positionals, and the trailing vararg then received one of them instead of absorbing
# the real arguments. A single-clause reduction does NOT reproduce it and actively misleads.
prog() {
  local name=$1 body=$2
  printf '%s\n' "$body" > "$work/$name.ssc"
  local ref f interp
  ref=$(SSC_FRONT=legacy    timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -1)
  f=$(SSC_FRONT_STRICT=1    timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -1)
  interp=$(timeout 90 ./bin/ssc-tools run --v1 "$work/$name.ssc" 2>&1 | head -1)
  # Planted separately from the operator probes' plant: a self-test that only ever trips the OLD
  # rows would say nothing about whether these new ones can fail at all.
  [ $SELFTEST -eq 1 ] && [ "$name" = "vararg-named" ] && ref="1"
  if [ "$ref" = "$f" ] && [ "$f" = "$interp" ]; then
    printf '  %-14s %s\n' "$name" "$ref"
  else
    printf '  %-14s РАСХОЖДЕНИЕ\n    эталон:       %s\n    F:            %s\n    интерпретатор:%s\n' \
      "$name" "$ref" "$f" "$interp"
    fail=$((fail+1))
  fi
}

echo "── три полосы на одном исходнике:"
prog vararg-named 'def hstack(gap: Int = 0, wrap: Boolean = false)(children: Int*): Int = children.length
def main() =
  println(hstack(gap = 8)(1, 2))'
prog vararg-both 'def hstack(gap: Int = 0, wrap: Boolean = false)(children: Int*): Int = children.length
def main() =
  println(hstack(gap = 8, wrap = true)(1, 2, 3))'
# The case the comment above expandNamedDefaultCall documents: named arg, defaults, NO vararg.
# Carried here so a future change to the vararg branch cannot quietly take this path with it.
prog named-no-vararg 'def f(a: String, b: String = "B0", c: String = "C0", d: String = "D0") = a + b + c + d
def main() =
  println(f("x", c = "C1"))'
probe set-diff   'Set(1, 2, 3) -- Set(2)'
probe list-diff  'List(1, 2, 3) -- List(2)'
probe set-union  'Set(1, 2) ++ Set(3)'
probe list-cat   'List(1, 2) ++ List(3)'

if [ $SELFTEST -eq 1 ]; then
  # BOTH plants must fire -- one in the operator rows, one in the vararg rows. `-gt 0` would be
  # satisfied by the operator plant alone and would say nothing about whether the vararg probes can
  # fail at all, which is the whole question a self-test exists to answer.
  if [ $fail -ge 2 ]; then echo "✓ self-test: обе подложки сработали ($fail строк), гейт краснеет"; exit 0
  elif [ $fail -gt 0 ]; then echo "✗ self-test: сработала только 1 подложка из 2 — часть проб ничего не проверяет"; exit 1
  else echo "✗ self-test: подложил расхождение, гейт остался зелёным — он ничего не проверяет"; exit 1; fi
fi
if [ $fail -gt 0 ]; then echo "✗ ref-front-three-defects-gate: расхождений — $fail"; exit 1; fi
echo "✓ ref-front-three-defects-gate: все полосы согласны"
