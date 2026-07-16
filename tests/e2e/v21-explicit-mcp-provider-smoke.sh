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

# A bare `[[ $(…) == "$want" ]]` under `set -e` exits 1 printing NOTHING — the run's
# own stderr is captured to a file, so a failure here looked like a silent exit 1.
expect_out() {
  local name=$1 want=$2 got=$3
  if [[ $got != "$want" ]]; then
    echo "v21-explicit-mcp-provider-smoke: FAILED check '$name'" >&2
    echo "--- want" >&2; printf '%s\n' "$want" >&2
    echo "--- got" >&2;  printf '%s\n' "$got"  >&2
    echo "--- diff (want vs got)" >&2
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
    exit 1
  fi
}

# Surface the captured stderr: without this a non-zero `ssc-provider` exit aborts the
# gate under `set -e` with the actual error message still sitting in a temp file.
run_case() {
  local file=$1
  "$LAUNCHER" mcp run "$ROOT/examples/$file" >"$tmp/$file.vm" 2>"$tmp/$file.vm.err" || {
    echo "v21-explicit-mcp-provider-smoke: VM run FAILED for $file" >&2
    cat "$tmp/$file.vm.err" >&2; exit 1
  }
  "$LAUNCHER" mcp run --bytecode "$ROOT/examples/$file" >"$tmp/$file.asm" 2>"$tmp/$file.asm.err" || {
    echo "v21-explicit-mcp-provider-smoke: ASM run FAILED for $file" >&2
    cat "$tmp/$file.asm.err" >&2; exit 1
  }
  cmp "$tmp/$file.vm" "$tmp/$file.asm" || {
    echo "v21-explicit-mcp-provider-smoke: VM/ASM output differs for $file" >&2
    diff "$tmp/$file.vm" "$tmp/$file.asm" >&2 || true; exit 1
  }
}

run_case mcp-client-discover.ssc
run_case agent-mcp-toolsource.ssc

expect_out mcp-client-discover \
  $'Tools (3):\n  - echo: Return the input string unchanged\n  - add: Add two numbers\n  - get_weather: Get current weather for a city (stub)\nResources: 0\nPrompts: 0\nDone' \
  "$(cat "$tmp/mcp-client-discover.ssc.vm")"
expect_out agent-mcp-toolsource \
  $'imported 3 MCP tools as agent tools:\n  echo — Return the input string unchanged\n  add — Add two numbers\n  get_weather — Get current weather for a city (stub)' \
  "$(cat "$tmp/agent-mcp-toolsource.ssc.vm")"

if "$ROOT/bin/ssc" run "$ROOT/examples/mcp-client-discover.ssc" >"$tmp/plain.out" 2>"$tmp/plain.err"; then
  echo 'v21-explicit-mcp-provider-smoke: plain ssc unexpectedly loaded MCP' >&2
  exit 1
fi
grep -F 'unbound global: mcpConnect' "$tmp/plain.err" >/dev/null

echo 'PASS v21-explicit-mcp-provider-smoke (2 exact rows, VM/ASM)'
