#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
fixture="$ROOT/tests/conformance/v2-self-hosted-markdown-core.ssc"
expected="$ROOT/tests/conformance/expected/v2-self-hosted-markdown-core.txt"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/ssc-v21-markdown-core.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc" run --native "$fixture" >"$tmp/vm.out"
PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc" run --native --bytecode "$fixture" >"$tmp/asm.out"
cmp -s "$tmp/vm.out" "$tmp/asm.out" || {
  diff -u "$tmp/vm.out" "$tmp/asm.out" >&2 || true
  exit 1
}
diff -u "$expected" "$tmp/vm.out"

echo 'PASS v21-self-hosted-markdown-core-smoke'
