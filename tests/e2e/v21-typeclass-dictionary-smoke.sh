#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-typeclass-dictionary.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

run_native() {
  PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --native "$@"
}

focused_expected=$'int\n0\n7\nstring\n[]\nleft-right\nint|string\n17\n21'
typeclass_expected=$'Int    : Int(42)\nBool   : yes\nString : \'hello\'\nsummon : Int(99)\n1 == 1  : true\n1 == 2  : false\nhi == hi: true\nhi == ho: false\n3 < 7   : true\n5 > 2   : true\nmin(3,7): 3\nmax(3,7): 7\nsorted  : 1, 2, 3, 5, 8, 9\nsum    : 15\nconcat : hello, world!\nrepeat : abababab\ndoubled: 2, 4, 6, 8, 10\nsquared: 1, 4, 9, 16, 25'

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native "${mode_args[@]}" \
    "$ROOT/tests/fixtures/v21-native/typeclass-dictionary.ssc" \
    >"$tmp/focused.$mode.out" 2>"$tmp/focused.$mode.err"
  [[ $(<"$tmp/focused.$mode.out") == "$focused_expected" ]]
  [[ ! -s "$tmp/focused.$mode.err" ]]

  run_native "${mode_args[@]}" "$ROOT/examples/typeclass.ssc" \
    >"$tmp/typeclass.$mode.out" 2>"$tmp/typeclass.$mode.err"
  [[ $(<"$tmp/typeclass.$mode.out") == "$typeclass_expected" ]]
  [[ ! -s "$tmp/typeclass.$mode.err" ]]
done

cmp "$tmp/focused.vm.out" "$tmp/focused.asm.out"
cmp "$tmp/typeclass.vm.out" "$tmp/typeclass.asm.out"
! rg -q 'Stub|sentinel' "$tmp/focused.vm.out" "$tmp/typeclass.vm.out"

echo 'PASS v21-typeclass-dictionary-smoke'
