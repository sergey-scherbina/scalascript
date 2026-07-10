#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
SSC="$ROOT/bin/ssc"

run_fail() {
  local label=$1 expected=$2
  shift 2
  local out rc
  set +e
  out=$(PATH=/usr/bin:/bin "$SSC" "$@" 2>&1)
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
run_fail 'native VM missing dispatch'  'unresolved runtime dispatch:' run --native "$FIX"
run_fail 'native ASM missing dispatch' 'unresolved runtime dispatch:' run --native --bytecode "$FIX"
run_fail 'bridge ASM x402 Op'  'unhandled runtime effect: Wallets.metaMask' run --bytecode "$ROOT/examples/x402-metamask.ssc"

echo 'PASS v21-unhandled-effect-smoke'
