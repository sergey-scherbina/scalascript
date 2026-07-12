#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
LAUNCHER="$ROOT/bin/ssc-provider"
PROVIDER="$ROOT/bin/lib/providers/nfc/jars"
EXAMPLE="$ROOT/examples/nfc-ndef.ssc"

[[ -x $LAUNCHER && -d $PROVIDER ]] || {
  echo 'v21-explicit-nfc-provider-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}

find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print | grep -F 'scalascript-v2-native-nfc-plugin_' >/dev/null
if find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print | grep -Ei \
    'scalameta|scala3-compiler|compiler-driver|scalascript-(core|backend-interpreter|v2-plugin-bridge)' >/dev/null; then
  echo 'v21-explicit-nfc-provider-smoke: forbidden compatibility/compiler dependency' >&2
  exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-nfc.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

"$LAUNCHER" nfc run "$EXAMPLE" >"$tmp/vm.out" 2>"$tmp/vm.err"
"$LAUNCHER" nfc run --bytecode "$EXAMPLE" >"$tmp/asm.out" 2>"$tmp/asm.err"
cmp "$tmp/vm.out" "$tmp/asm.out"
[[ $(cat "$tmp/vm.out") == $'NFC platform:  jvm-host\nNFC supported: false\nNDEF read:     false\nNDEF write:    false\nPermission:    NfcPermissionUnknown\nText record:   text en bytes=22\nURI record:    uri bytes=31\nMIME record:   application/octet-stream bytes=1,2,3,255\nScan skipped:  NFC hardware is unavailable on this backend' ]]

if "$ROOT/bin/ssc" run "$EXAMPLE" >"$tmp/plain.out" 2>"$tmp/plain.err"; then
  echo 'v21-explicit-nfc-provider-smoke: plain ssc unexpectedly loaded NFC' >&2
  exit 1
fi
grep -F 'unbound global: nfcCapabilities' "$tmp/plain.err" >/dev/null

echo 'PASS v21-explicit-nfc-provider-smoke (1 exact row, VM/ASM)'
