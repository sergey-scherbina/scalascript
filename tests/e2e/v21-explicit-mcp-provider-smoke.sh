#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
LAUNCHER="$ROOT/bin/ssc-provider"
PROVIDER="$ROOT/bin/lib/providers/mcp/jars"

[[ -x $LAUNCHER && -d $PROVIDER ]] || {
  echo 'v21-explicit-mcp-provider-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}
command -v node >/dev/null || { echo 'v21-explicit-mcp-provider-smoke: node is required' >&2; exit 2; }
find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print | grep -F 'scalascript-v2-native-mcp-plugin_' >/dev/null
if find "$PROVIDER" -maxdepth 1 -type f -name '*.jar' -print | grep -Ei \
    'scalameta|scala3-compiler|compiler-driver|scalascript-(core|backend-interpreter|v2-plugin-bridge)' >/dev/null; then
  echo 'v21-explicit-mcp-provider-smoke: forbidden compatibility/compiler dependency' >&2
  exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-mcp.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

run_case() {
  local file=$1
  "$LAUNCHER" mcp run "$ROOT/examples/$file" >"$tmp/$file.vm" 2>"$tmp/$file.vm.err"
  "$LAUNCHER" mcp run --bytecode "$ROOT/examples/$file" >"$tmp/$file.asm" 2>"$tmp/$file.asm.err"
  cmp "$tmp/$file.vm" "$tmp/$file.asm"
}

run_case mcp-client-discover.ssc
run_case agent-mcp-toolsource.ssc

[[ $(cat "$tmp/mcp-client-discover.ssc.vm") == $'Tools (3):\n  - echo: Return the input string unchanged\n  - add: Add two numbers\n  - get_weather: Get current weather for a city (stub)\nResources: 0\nPrompts: 0\nDone' ]]
[[ $(cat "$tmp/agent-mcp-toolsource.ssc.vm") == $'imported 3 MCP tools as agent tools:\n  echo — Return the input string unchanged\n  add — Add two numbers\n  get_weather — Get current weather for a city (stub)' ]]

if "$ROOT/bin/ssc" run "$ROOT/examples/mcp-client-discover.ssc" >"$tmp/plain.out" 2>"$tmp/plain.err"; then
  echo 'v21-explicit-mcp-provider-smoke: plain ssc unexpectedly loaded MCP' >&2
  exit 1
fi
grep -F 'unbound global: mcpConnect' "$tmp/plain.err" >/dev/null

echo 'PASS v21-explicit-mcp-provider-smoke (2 exact rows, VM/ASM)'
