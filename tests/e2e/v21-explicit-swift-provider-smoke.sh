#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
LAUNCHER="$ROOT/bin/ssc-provider"
PROVIDER="$ROOT/bin/lib/providers/swift/jars"

[[ -x $LAUNCHER && -d $PROVIDER ]] || {
  echo 'v21-explicit-swift-provider-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}
command -v node >/dev/null || { echo 'v21-explicit-swift-provider-smoke: node is required' >&2; exit 2; }
find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print |
  grep -F 'scalascript-v2-native-swift-plugin_' >/dev/null
if find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print | grep -Ei \
    'scalameta|scala3-compiler|compiler-driver|scalascript-(core|backend-interpreter|v2-plugin-bridge)' >/dev/null; then
  echo 'v21-explicit-swift-provider-smoke: forbidden compatibility/compiler dependency' >&2
  exit 1
fi
if find "$ROOT/bin/lib/standard/jars" -maxdepth 1 -type f -name '*.jar' -print |
    grep -F 'scalascript-v2-native-swift-plugin_' >/dev/null; then
  echo 'v21-explicit-swift-provider-smoke: provider leaked into standard jars' >&2
  exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-swift.XXXXXX")
server_pid=
cleanup() {
  [[ -z $server_pid ]] || kill "$server_pid" 2>/dev/null || true
  rm -rf "$tmp"
}
trap cleanup EXIT HUP INT TERM
node "$ROOT/examples/swift-server-tools.js" "$tmp/url" >"$tmp/server.out" 2>"$tmp/server.err" &
server_pid=$!
for _ in $(seq 1 100); do
  [[ -s $tmp/url ]] && break
  kill -0 "$server_pid" 2>/dev/null || { cat "$tmp/server.err" >&2; exit 1; }
  sleep 0.05
done
[[ -s $tmp/url ]] || { echo 'SWIFT fixture did not start' >&2; exit 1; }
url=$(cat "$tmp/url")

for mode in vm asm; do
  args=()
  [[ $mode == asm ]] && args+=(--bytecode)
  SWIFT_AGGREGATOR_URL="$url" SWIFT_API_KEY=test-swift-key \
    "$LAUNCHER" swift run "${args[@]}" \
      "$ROOT/examples/international-bank-rails.ssc" \
      >"$tmp/$mode.out" 2>"$tmp/$mode.err"
done
cmp "$tmp/vm.out" "$tmp/asm.out"
[[ $(cat "$tmp/vm.out") == $'pacs.008 Transfer ID: swift-pacs-001\nUETR: 11111111-1111-4111-8111-111111111111\nStatus: Pending\nMT103 Transfer UETR: 22222222-2222-4222-8222-222222222222\nGPI hop: DEUTDEFF — ACCC at 2026-07-12T10:00:00Z' ]]

if SWIFT_AGGREGATOR_URL="$url" SWIFT_API_KEY=test-swift-key \
    "$ROOT/bin/ssc" run "$ROOT/examples/international-bank-rails.ssc" \
    >"$tmp/plain.out" 2>"$tmp/plain.err"; then
  echo 'v21-explicit-swift-provider-smoke: plain ssc unexpectedly loaded SWIFT' >&2
  exit 1
fi
grep -F 'unbound global: SwiftProvider' "$tmp/plain.err" >/dev/null

echo 'PASS v21-explicit-swift-provider-smoke (1 exact row, VM/ASM)'
