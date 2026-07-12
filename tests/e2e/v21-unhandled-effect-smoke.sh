#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
SSC="$ROOT/bin/ssc"
SSC_TOOLS="$ROOT/bin/ssc-tools"

run_fail() {
  local launcher=$1 label=$2 expected=$3
  shift 3
  local out rc
  set +e
  out=$(PATH=/usr/bin:/bin "$launcher" "$@" 2>&1)
  rc=$?
  set -e
  if [[ $rc -eq 0 || $out != *"$expected"* ]]; then
    printf 'FAIL %s\n  rc:  %s\n  out: %s\n  expected nonzero + %s\n' \
      "$label" "$rc" "$out" "$expected" >&2
    exit 1
  fi
  printf 'ok   %-36s => rejected (%s)\n' "$label" "$expected"
}

FIX="$ROOT/tests/fixtures/v21-native/unhandled-effect.ssc"
run_fail "$SSC" 'native VM missing dispatch'  'unhandled runtime effect: MissingRuntime.call' run --native "$FIX"
run_fail "$SSC" 'native ASM missing dispatch' 'unhandled runtime effect: MissingRuntime.call' run --native --bytecode "$FIX"
run_fail "$SSC_TOOLS" 'bridge ASM x402 Op' 'unhandled runtime effect: Wallets.metaMask' run --bytecode "$ROOT/examples/x402-metamask.ssc"

echo 'PASS v21-unhandled-effect-smoke'
