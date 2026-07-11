#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-parser-dsl.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
expected_dir="$ROOT/tests/fixtures/v21-parser-dsl"
native_fixtures="$ROOT/tests/fixtures/v21-native"

run_native() {
  PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --native "$@"
}

for example in dsl-json-parser.ssc dsl-yaml-like.ssc; do
  run_native "$ROOT/examples/$example" >"$tmp/$example.vm.out" \
    2>"$tmp/$example.vm.err"
  run_native --bytecode "$ROOT/examples/$example" >"$tmp/$example.asm.out" \
    2>"$tmp/$example.asm.err"
  cmp "$expected_dir/${example%.ssc}.expected" "$tmp/$example.vm.out"
  cmp "$expected_dir/${example%.ssc}.expected" "$tmp/$example.asm.out"
  [[ ! -s "$tmp/$example.vm.err" ]]
  [[ ! -s "$tmp/$example.asm.err" ]]
  ! rg -q 'Stub' "$tmp/$example.vm.out" "$tmp/$example.asm.out"
done

focused=parser-dsl-values.ssc
focused_expected='1:alpha|2:beta|3:gamma'
run_native "$native_fixtures/$focused" >"$tmp/$focused.vm.out" 2>"$tmp/$focused.vm.err"
run_native --bytecode "$native_fixtures/$focused" >"$tmp/$focused.asm.out" 2>"$tmp/$focused.asm.err"
[[ $(<"$tmp/$focused.vm.out") == "$focused_expected" ]]
[[ $(<"$tmp/$focused.asm.out") == "$focused_expected" ]]
[[ ! -s "$tmp/$focused.vm.err" ]]
[[ ! -s "$tmp/$focused.asm.err" ]]

layout=parser-layout-block.ssc
layout_expected=$'ParseOk((), host, Position(2))\nParseOk(List(host), , Position(6))'
run_native "$native_fixtures/$layout" >"$tmp/$layout.vm.out" 2>"$tmp/$layout.vm.err"
run_native --bytecode "$native_fixtures/$layout" >"$tmp/$layout.asm.out" 2>"$tmp/$layout.asm.err"
[[ $(<"$tmp/$layout.vm.out") == "$layout_expected" ]]
[[ $(<"$tmp/$layout.asm.out") == "$layout_expected" ]]
[[ ! -s "$tmp/$layout.vm.err" ]]
[[ ! -s "$tmp/$layout.asm.err" ]]

echo 'PASS v21-parser-dsl-smoke'
