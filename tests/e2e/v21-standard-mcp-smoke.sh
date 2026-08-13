#!/usr/bin/env bash
set -euo pipefail

# MCP on the STANDARD launcher.
#
# This gate replaces v21-explicit-mcp-provider-smoke.sh, which asserted the OPPOSITE of what is
# now true: its last check required plain `ssc` to FAIL with `unbound global: mcpConnect`, because
# MCP shipped as an opt-in provider under bin/lib/providers/mcp. Sergiy's decision on 2026-07-31
# made MCP part of the standard surface, so that assertion had to go — but deleting a gate to make
# a change pass is how coverage disappears quietly. Everything else is carried over verbatim: the
# same two examples, the same exact expected rows, the same VM/ASM equality. Only the launcher
# changed, from `ssc-provider mcp run` to `ssc run`.
#
# What it is really protecting: `bin/ssc` is the launcher the conformance contract drives, and six
# corpus cases were red for no reason other than this graph being absent from it.

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
LAUNCHER="$ROOT/bin/ssc"
STANDARD="$ROOT/bin/lib/standard/jars"

[[ -x $LAUNCHER && -d $STANDARD ]] || {
  echo 'v21-standard-mcp-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}
command -v node >/dev/null || { echo 'v21-standard-mcp-smoke: node is required' >&2; exit 2; }

# The plugin JAR must be in the STANDARD graph, not under a provider directory. Without this the
# suite still passes on a build that quietly reverted to opt-in packaging, since `ssc-provider mcp`
# would simply be gone and nothing would state where the JAR is expected to live instead.
find "$STANDARD" -maxdepth 1 -type f -name '*.jar' -print \
  | grep -F 'scalascript-v2-native-mcp-plugin_' >/dev/null || {
  echo 'v21-standard-mcp-smoke: the MCP plugin JAR is not in bin/lib/standard/jars' >&2
  exit 1
}
if [[ -d "$ROOT/bin/lib/providers/mcp" ]]; then
  echo 'v21-standard-mcp-smoke: bin/lib/providers/mcp is back — MCP would be staged twice' >&2
  exit 1
fi

# Carried over from the provider gate: the MCP graph must not drag compiler/compatibility JARs in.
# It matters MORE now, because these JARs sit on every `ssc` invocation rather than an opt-in one.
if find "$STANDARD" -maxdepth 1 -type f -name '*.jar' -print | grep -Ei \
    'scalameta|scala3-compiler|compiler-driver|scalascript-(core|backend-interpreter|v2-plugin-bridge)' >/dev/null; then
  echo 'v21-standard-mcp-smoke: forbidden compatibility/compiler dependency in the standard graph' >&2
  exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-standard-mcp.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# A bare `[[ $(…) == "$want" ]]` under `set -e` exits 1 printing NOTHING — the run's
# own stderr is captured to a file, so a failure here looked like a silent exit 1.
expect_out() {
  local name=$1 want=$2 got=$3
  if [[ $got != "$want" ]]; then
    echo "v21-standard-mcp-smoke: FAILED check '$name'" >&2
    echo "--- want" >&2; printf '%s\n' "$want" >&2
    echo "--- got" >&2;  printf '%s\n' "$got"  >&2
    echo "--- diff (want vs got)" >&2
    diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") >&2 || true
    exit 1
  fi
}

# Surface the captured stderr: without this a non-zero exit aborts the gate under
# `set -e` with the actual error message still sitting in a temp file.
run_case() {
  local file=$1
  "$LAUNCHER" run "$ROOT/examples/$file" >"$tmp/$file.vm" 2>"$tmp/$file.vm.err" </dev/null || {
    echo "v21-standard-mcp-smoke: VM run FAILED for $file" >&2
    cat "$tmp/$file.vm.err" >&2; exit 1
  }
  "$LAUNCHER" run --bytecode "$ROOT/examples/$file" >"$tmp/$file.asm" 2>"$tmp/$file.asm.err" </dev/null || {
    echo "v21-standard-mcp-smoke: ASM run FAILED for $file" >&2
    cat "$tmp/$file.asm.err" >&2; exit 1
  }
  cmp "$tmp/$file.vm" "$tmp/$file.asm" || {
    echo "v21-standard-mcp-smoke: VM/ASM output differs for $file" >&2
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

# The SERVER half, which is what the six corpus cases exercise and what the two client examples
# above never touch: `mcpServer` must resolve on the standard launcher. Asserted against the
# interpreter rather than a hand-written string, so this cannot drift into agreeing with itself.
server_case=$ROOT/tests/conformance/mcp-server-tool.ssc
"$ROOT/bin/ssc-tools" run --v1 "$server_case" >"$tmp/server.int" 2>/dev/null </dev/null || {
  echo 'v21-standard-mcp-smoke: the interpreter reference run FAILED' >&2; exit 1
}
"$LAUNCHER" run --v2 "$server_case" >"$tmp/server.v2" 2>"$tmp/server.v2.err" </dev/null || {
  echo 'v21-standard-mcp-smoke: mcpServer FAILED on the standard launcher' >&2
  cat "$tmp/server.v2.err" >&2; exit 1
}
cmp "$tmp/server.int" "$tmp/server.v2" || {
  echo 'v21-standard-mcp-smoke: server output differs between int and v2' >&2
  diff "$tmp/server.int" "$tmp/server.v2" >&2 || true; exit 1
}

# -- the 2026-07-28 server surface, on BOTH lanes --------------------------------
#
# WHY THIS EXISTS. Every case above -- and every MCP example in the corpus -- uses only `tool`,
# `onConnected` and `onDisconnected`. Those are three of the FOUR members v2's native provider
# implemented, so while that was the whole corpus the suite stayed green even though 36 of the
# interpreter's 40 `srv` members were unreachable from `ssc run`, the DEFAULT lane. The gate was
# not wrong; it never crossed the boundary. This case crosses it.
#
# Differential, like the server case above: whatever the two lanes answer, they must answer the
# same, and a member missing on either fails that lane's run outright.
cat >"$tmp/mrtr-surface.ssc" <<'MRTREOF'
---
name: mrtr-surface
version: 1.0.0
description: the 2026-07-28 srv surface must resolve on every lane
---

```scalascript
[mcpServer, Tool](std/mcp/server.ssc)

mcpServer { srv =>
  srv.setMrtrMode("park")
  srv.setRequestState("outside-a-request")
  println("mode-set")
  println("asTask=" + srv.asTask().toString)
  println("tasks=" + srv.clientSupportsTasks().toString)
  println("cancelled=" + srv.isCancelled().toString)
}
println("surface-ok")
```
MRTREOF
"$ROOT/bin/ssc-tools" run --v1 "$tmp/mrtr-surface.ssc" >"$tmp/mrtr.int" 2>"$tmp/mrtr.int.err" </dev/null || {
  echo 'v21-standard-mcp-smoke: the 2026 surface FAILED on the interpreter' >&2
  cat "$tmp/mrtr.int.err" >&2; exit 1
}
"$LAUNCHER" run --v2 "$tmp/mrtr-surface.ssc" >"$tmp/mrtr.v2" 2>"$tmp/mrtr.v2.err" </dev/null || {
  echo 'v21-standard-mcp-smoke: the 2026 surface FAILED on the standard launcher' >&2
  echo '  a "no field ... on named-method-obj" here means v2 lacks a member v1 has' >&2
  cat "$tmp/mrtr.v2.err" >&2; exit 1
}
cmp "$tmp/mrtr.int" "$tmp/mrtr.v2" || {
  echo 'v21-standard-mcp-smoke: the 2026 surface answers DIFFERENTLY on the two lanes' >&2
  diff "$tmp/mrtr.int" "$tmp/mrtr.v2" >&2 || true; exit 1
}
grep -q 'surface-ok' "$tmp/mrtr.v2" || {
  echo 'v21-standard-mcp-smoke: the 2026 surface case did not run to completion' >&2; exit 1
}

echo 'PASS v21-standard-mcp-smoke (2 rows VM/ASM + server vs int + 2026 surface both lanes)'
