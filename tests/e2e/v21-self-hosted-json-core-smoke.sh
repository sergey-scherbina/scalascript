#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
fixture="$ROOT/tests/fixtures/v21-native/json-core.ssc"
expected="$ROOT/tests/fixtures/v21-native/json-core.expected"

if [[ ! -x "$ROOT/bin/ssc" ]]; then
  echo 'v21-self-hosted-json-core-smoke: staged launcher missing; run scripts/sbtc "installBin"' >&2
  exit 2
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/ssc-v21-json-core.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native "$fixture" >"$tmp/vm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native --bytecode "$fixture" >"$tmp/asm.out"

cmp -s "$tmp/vm.out" "$tmp/asm.out" || {
  echo 'v21-self-hosted-json-core-smoke: VM/ASM output mismatch' >&2
  diff -u "$tmp/vm.out" "$tmp/asm.out" >&2 || true
  exit 1
}
diff -u "$expected" "$tmp/vm.out"

echo 'PASS v21-self-hosted-json-core-smoke'
