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

# A bare `[[ $(...) == "$want" ]]` under `set -e` exits 1 printing nothing.
expect_out() {
  local name=$1 want=$2 got=$3
  if [[ $got != "$want" ]]; then
    echo "v21-direct-asm-recursion-smoke: FAILED check '$name'" >&2
    echo "--- want" >&2; printf '%s\n' "$want" >&2
    echo "--- got" >&2;  printf '%s\n' "$got"  >&2
    echo "--- diff (want vs got)" >&2
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
    exit 1
  fi
}

expected=$'3628800\n144\n5000050000\n0\n10000\ntrue\ntrue\nfalse\nfalse\nLongest Collatz sequence up to 1000: starts at 871, 178 steps\nping\npang\npong'

# The point of this gate is that the compiled lanes do NOT need a big stack, so the
# tiny stack must actually take effect. `SSC_XSS` (not JAVA_TOOL_OPTIONS) is the knob:
# the launcher passes an explicit `-Xss` on the java command line, and that BEATS
# JAVA_TOOL_OPTIONS — so setting it that way silently tested the launcher's default
# instead. Keep JAVA_TOOL_OPTIONS too: it is what constrains the `java -jar` run below.
vm=$(SSC_XSS=256k JAVA_TOOL_OPTIONS=-Xss256k SSC_NO_CDS=1 \
  "$ROOT/bin/ssc-standard" run "$ROOT/examples/recursion.ssc")
asm=$(SSC_XSS=256k JAVA_TOOL_OPTIONS=-Xss256k SSC_NO_CDS=1 \
  "$ROOT/bin/ssc-standard" run --bytecode "$ROOT/examples/recursion.ssc")

expect_out vm "$expected" "$vm"
expect_out asm "$expected" "$asm"
expect_out vm-vs-asm "$vm" "$asm"
SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" build-jvm \
  "$ROOT/examples/recursion.ssc" -o "$sandbox/recursion.jar" >/dev/null
artifact=$(JAVA_TOOL_OPTIONS=-Xss256k java -jar "$sandbox/recursion.jar")
expect_out artifact "$expected" "$artifact"
echo 'PASS v21-direct-asm-recursion-smoke (256k stack, VM/ASM/artifact)'
