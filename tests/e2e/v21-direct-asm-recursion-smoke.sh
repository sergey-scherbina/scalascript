#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
[[ -x "$ROOT/bin/ssc-standard" ]] || {
  echo 'v21-direct-asm-recursion-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-direct-asm-recursion.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

expected=$'3628800\n144\n5000050000\n0\n10000\ntrue\ntrue\nfalse\nfalse\nLongest Collatz sequence up to 1000: starts at 871, 178 steps\nping\npang\npong'

vm=$(JAVA_TOOL_OPTIONS=-Xss256k SSC_NO_CDS=1 \
  "$ROOT/bin/ssc-standard" run "$ROOT/examples/recursion.ssc")
asm=$(JAVA_TOOL_OPTIONS=-Xss256k SSC_NO_CDS=1 \
  "$ROOT/bin/ssc-standard" run --bytecode "$ROOT/examples/recursion.ssc")

[[ $vm == "$expected" ]]
[[ $asm == "$expected" ]]
[[ $vm == "$asm" ]]
SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" build-jvm \
  "$ROOT/examples/recursion.ssc" -o "$sandbox/recursion.jar" >/dev/null
artifact=$(JAVA_TOOL_OPTIONS=-Xss256k java -jar "$sandbox/recursion.jar")
[[ $artifact == "$expected" ]]
echo 'PASS v21-direct-asm-recursion-smoke'
