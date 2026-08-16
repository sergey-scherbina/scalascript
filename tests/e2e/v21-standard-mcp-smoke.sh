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

# ── elicit: the REQUEST must go out, on both lanes, identically ──────────────
#
# `srv.elicit` cannot SUCCEED over stdio on either lane — it blocks the handler waiting for an answer
# that only the single-threaded serve loop it is blocking could deliver
# (mcp-elicit-deadlocks-the-serve-loop). So this asserts what is actually true: the member RESOLVES
# on both lanes and puts `elicitation/create` on the wire with the message and schema it was given.
#
# ASSERTING THE REQUEST RATHER THAN THE ANSWER IS THE POINT. A case demanding the answer would have
# to be deleted or weakened the moment it ran, and a case asserting only that the member exists would
# pass on a member that resolves and sends nothing. `timeoutMs` is 1 so the row costs a second rather
# than the builder's 60.
cat > "$tmp/elicit.ssc" <<'SSC'
[mcpServer, serveMcp, Transport, Tool](std/mcp/server.ssc)

def main(): Unit =
  mcpServer(srv =>
    srv.tool("ask")(args =>
      val r = srv.elicit("name?", Map("type" -> "object"), 1)
      Tool.text("action=" + r.action)))
  serveMcp(Transport.Stdio)
SSC
elicit_in='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{"elicitation":{}}}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ask","arguments":{}}}'
# The interpreter lane goes through ssc-tools and the standard launcher through $LAUNCHER — the
# same split the rows above use; taking one binary for both is what made the first draft of this
# case red on --v1.
for lane in --v1 --v2; do
  if [[ $lane == --v1 ]]; then bin="$ROOT/bin/ssc-tools"; else bin="$LAUNCHER"; fi
  printf '%s\n' "$elicit_in" | "$bin" run $lane "$tmp/elicit.ssc" >"$tmp/elicit$lane.out" 2>/dev/null || true
  grep -q '"method":"elicitation/create"' "$tmp/elicit$lane.out" || {
    echo "v21-standard-mcp-smoke: elicit did not reach the wire on $lane" >&2
    tail -3 "$tmp/elicit$lane.out" >&2; exit 1
  }
  grep -q '"message":"name?"' "$tmp/elicit$lane.out" || {
    echo "v21-standard-mcp-smoke: elicit sent a request without its message on $lane" >&2; exit 1
  }
done

# ── prompts and resource BODIES, compared on the wire ────────────────────────
#
# Two defects this row was written against, both measured before the fix and both invisible to
# every case above, because those assert stdout and this asserts what a CLIENT receives:
#
#   * `srv.prompt` is DECLARED in std/mcp/server.ssc and v2 did not implement it: the interpreter
#     served `prompts/list`, the DEFAULT lane died with `no field 'prompt'`.
#   * `srv.resource` on v2 never decoded its handler's result. Reading `mem://a` answered
#     `{"uri":"mem://a","text":"ResourceResult(\"mem://a\", List(Text(\"BODY-42\")))"}` — the
#     rendered VALUE as the resource body — where the interpreter answered `BODY-42`.
#
# The second is why this compares BODIES rather than checking that a member resolves: `resource`
# resolved fine on v2 the whole time and served nonsense. A resolution check cannot see that.
#
# `Role.Assistant` and a distinctive body are deliberate: `user` is the role a defaulting
# implementation returns, so a case using `Role.User` would pass against one that ignores the field.
cat > "$tmp/pr.ssc" <<'SSC'
[mcpServer, serveMcp, Transport, PromptResult, ResourceResult, Message, Role, Content](std/mcp/server.ssc)

def main(): Unit =
  mcpServer(srv =>
    srv.prompt("greet", "say hi")(args =>
      PromptResult(List(Message(Role.Assistant, Content.Text("hi")))))
    srv.resource("mem://a", "a")(uri =>
      ResourceResult(uri, List(Content.Text("BODY-42")))))
  serveMcp(Transport.Stdio)
SSC
pr_in='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}
{"jsonrpc":"2.0","id":2,"method":"prompts/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"prompts/get","params":{"name":"greet","arguments":{}}}
{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"mem://a"}}'
for lane in --v1 --v2; do
  if [[ $lane == --v1 ]]; then bin="$ROOT/bin/ssc-tools"; else bin="$LAUNCHER"; fi
  printf '%s\n' "$pr_in" | "$bin" run $lane "$tmp/pr.ssc" >"$tmp/pr$lane.out" 2>"$tmp/pr$lane.err" || true
  grep -q '"role":"assistant"' "$tmp/pr$lane.out" || {
    echo "v21-standard-mcp-smoke: prompts/get did not answer with the handler's message on $lane" >&2
    tail -3 "$tmp/pr$lane.out" "$tmp/pr$lane.err" >&2; exit 1
  }
  grep -q '"text":"BODY-42"' "$tmp/pr$lane.out" || {
    echo "v21-standard-mcp-smoke: resources/read did not answer with the resource BODY on $lane" >&2
    echo '  a `ResourceResult(...)` here means the handler result was rendered, not decoded' >&2
    tail -3 "$tmp/pr$lane.out" >&2; exit 1
  }
done
# Whatever they answer, they must answer it identically — the same differential the rows above use,
# MINUS `serverInfo`, which is the one field the two lanes are SUPPOSED to disagree about: each
# names itself (`ssc-mcp-int` 1.0.0 vs `ssc-mcp-native` 2.1). Excluding it by name rather than by
# line number, so the row keeps working if another response is ever added ahead of it. A plain `cmp`
# here fails on that line alone — which is how this exclusion came to be written, not a guess.
grep -v '"serverInfo"' "$tmp/pr--v1.out" >"$tmp/pr.v1.cmp"
grep -v '"serverInfo"' "$tmp/pr--v2.out" >"$tmp/pr.v2.cmp"
cmp "$tmp/pr.v1.cmp" "$tmp/pr.v2.cmp" || {
  echo 'v21-standard-mcp-smoke: prompts/resources answer DIFFERENTLY on the two lanes' >&2
  diff "$tmp/pr.v1.cmp" "$tmp/pr.v2.cmp" >&2 || true; exit 1
}
# ...and the exclusion must not have eaten everything: three answers remain (ids 2, 3, 4).
[[ $(wc -l <"$tmp/pr.v1.cmp") -eq 3 ]] || {
  echo "v21-standard-mcp-smoke: expected 3 non-initialize answers, got $(wc -l <"$tmp/pr.v1.cmp")" >&2
  cat "$tmp/pr.v1.cmp" >&2; exit 1
}

echo 'PASS v21-standard-mcp-smoke (2 rows VM/ASM + server vs int + 2026 surface both lanes + elicit on the wire + prompts/resource bodies)'
