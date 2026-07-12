#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
TOOLS="$ROOT/bin/ssc-tools"
LANE="$ROOT/bin/lib/tools/x402"
[[ -x $TOOLS && -d $LANE/classes && -d $LANE/jars ]] || {
  echo 'v21-explicit-x402-tools-smoke: run installBin first' >&2
  exit 2
}
command -v node >/dev/null || { echo 'v21-explicit-x402-tools-smoke: node is required' >&2; exit 2; }
if find "$ROOT/bin/lib/standard" -type f -print | grep -Ei 'x402|sttp' >/dev/null; then
  echo 'v21-explicit-x402-tools-smoke: tools dependencies leaked into standard tier' >&2
  exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-x402.XXXXXX")
server_pid=
cleanup() {
  [[ -z $server_pid ]] || kill "$server_pid" 2>/dev/null || true
  rm -rf "$tmp"
}
trap cleanup EXIT HUP INT TERM
node "$ROOT/examples/x402-server-tools.js" "$tmp/url" >"$tmp/server.out" 2>"$tmp/server.err" &
server_pid=$!
for _ in $(seq 1 100); do
  [[ -s $tmp/url ]] && break
  kill -0 "$server_pid" 2>/dev/null || { cat "$tmp/server.err" >&2; exit 1; }
  sleep 0.05
done
[[ -s $tmp/url ]] || { echo 'x402 fixture did not start' >&2; exit 1; }

X402_SERVER_URL=$(cat "$tmp/url") \
  "$TOOLS" run-jvm "$ROOT/examples/x402-client.ssc" >"$tmp/client.out" 2>"$tmp/client.err"
grep -E '^Wallet address: 0x[0-9A-Fa-f]{40}$' "$tmp/client.out" >/dev/null
grep -F 'Health: 200 ok' "$tmp/client.out" >/dev/null
grep -F 'Premium: 200 premium data' "$tmp/client.out" >/dev/null
[[ $(wc -l <"$tmp/client.out" | tr -d ' ') == 3 ]]

echo 'PASS v21-explicit-x402-tools-smoke (1 exact row, local 402 sign/retry)'
