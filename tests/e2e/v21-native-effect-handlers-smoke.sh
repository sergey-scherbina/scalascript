#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-effect-handlers.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

run_native() {
  PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --native "$@"
}

fixture="$ROOT/tests/fixtures/v21-native/effect-handlers.ssc"
expected=$'owned\n7\n5\n6\n41\n33\n42\nList(2, 12)'

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native "${mode_args[@]}" "$fixture" >"$tmp/$mode.out" 2>"$tmp/$mode.err"
  [[ $(<"$tmp/$mode.out") == "$expected" ]]
  [[ ! -s "$tmp/$mode.err" ]]
done

cmp "$tmp/vm.out" "$tmp/asm.out"
! rg -q 'Stub|unhandled runtime effect|unbound global' "$tmp/vm.out" "$tmp/asm.out"

algebraic_expected=$'List(Hello, World!)\n0\n1\n[LOG] before increment\n[LOG] after increment\n10\n11\nList(11, 21, 12, 22, 13, 23)\ndone\n(42, List((info, step 1), (info, step 2)))\nList(1, 2)'
for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native "${mode_args[@]}" "$ROOT/examples/algebraic-effects.ssc" \
    >"$tmp/algebraic.$mode.out" 2>"$tmp/algebraic.$mode.err"
  [[ $(<"$tmp/algebraic.$mode.out") == "$algebraic_expected" ]]
  [[ ! -s "$tmp/algebraic.$mode.err" ]]
done
cmp "$tmp/algebraic.vm.out" "$tmp/algebraic.asm.out"

nested_fixture="$ROOT/tests/fixtures/v21-native/effect-runners-nested.ssc"
nested_expected=$'((7, List((info, inner))), List((info, outer-before), (info, outer-after)))\nList(1, 2)\nList(9)'
for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native "${mode_args[@]}" "$nested_fixture" \
    >"$tmp/nested.$mode.out" 2>"$tmp/nested.$mode.err"
  [[ $(<"$tmp/nested.$mode.out") == "$nested_expected" ]]
  [[ ! -s "$tmp/nested.$mode.err" ]]
done
cmp "$tmp/nested.vm.out" "$tmp/nested.asm.out"

echo 'PASS v21-native-effect-handlers-smoke'
