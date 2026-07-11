#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-parser-dsl.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

run_native() {
  PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --native "$@"
}

for example in dsl-json-parser.ssc dsl-yaml-like.ssc; do
  run_native "$ROOT/examples/$example" >"$tmp/$example.vm.out" \
    2>"$tmp/$example.vm.err"
  run_native --bytecode "$ROOT/examples/$example" >"$tmp/$example.asm.out" \
    2>"$tmp/$example.asm.err"
  cmp -s "$tmp/$example.vm.out" "$tmp/$example.asm.out"
  [[ ! -s "$tmp/$example.vm.err" ]]
  [[ ! -s "$tmp/$example.asm.err" ]]
done

echo 'PASS v21-parser-dsl-smoke'
