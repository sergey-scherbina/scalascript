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

echo 'PASS v21-native-effect-handlers-smoke'
