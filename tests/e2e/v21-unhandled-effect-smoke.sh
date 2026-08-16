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
# THE BRIDGE ASM LANE, on the same fixture as the two native cases above.
#
# It used to drive `examples/x402-metamask.ssc` and expect `unhandled runtime effect:
# Wallets.metaMask`, and that case could never pass as invoked. Three things were wrong with it and
# only the third is fatal:
#   * `payments` is bundled-but-OPT-IN, staged under `bin/lib/tools/x402/`, which `ssc-tools run`
#     does not put on the classpath — so the run died earlier with `unbound global: Network`.
#   * the example's own note says so: "load it manually only in a non-staged checkout OR TO RUN the
#     example". `ssc check` passes it because check resolves the plugin's declared prelude SYMBOLS.
#   * and the fatal one: the example is `target: js`. `Wallets.metaMask` and `enum Network` live in
#     `payments/x402/client-js` (BrowserWallets.scala), not in the JVM `payments/x402/client`, so a
#     JVM/bytecode lane REFUSING it is correct behaviour, not a defect. `run-jvm` says
#     `value metaMask is not a member of object scalascript.x402.client.Wallets`.
#
# The INTENT was "the bridge ASM path surfaces an unhandled effect Op", and that needs no x402 at
# all: the same `unhandled-effect.ssc` the native cases use produces
# `unhandled runtime effect: MissingRuntime.call` on this lane. Same invariant, one lane further,
# no opt-in plugin and no cross-target example.
run_fail "$SSC_TOOLS" 'bridge ASM missing dispatch' 'unhandled runtime effect: MissingRuntime.call' run --bytecode "$FIX"

echo 'PASS v21-unhandled-effect-smoke'
