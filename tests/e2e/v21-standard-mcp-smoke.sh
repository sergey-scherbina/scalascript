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

# ── toolWithSchema and resourceTemplate ──────────────────────────────────────
#
# The last two registration members. Both were absent from v2 AND undeclared in std/mcp/server.ssc,
# the shape `elicit` had: reachable on the interpreter, described nowhere, so a second lane had no
# contract to implement against. Declared and implemented together.
#
# What this asserts is the part a resolution check cannot see, which is the lesson the `resource`
# defect on this same file cost:
#   * the SCHEMA GIVEN reaches `tools/list`. `tool` registers `{"type":"object"}`, so a
#     `toolWithSchema` that ignored its schema argument would still list a tool and still answer
#     calls — `properties.a.type` is the byte that distinguishes them.
#   * the template SUBSTITUTES: `resources/read mem://note/7` must reach the handler as the
#     CONCRETE uri, not as `mem://note/{id}`. The body carries it back, so a template registered
#     but never matched, or matched but passed the template string, both fail here.
cat > "$tmp/reg.ssc" <<'SSC'
[mcpServer, serveMcp, Transport, ToolResult, ResourceResult, Content](std/mcp/server.ssc)

def main(): Unit =
  mcpServer(srv =>
    srv.toolWithSchema("add", "adds", Map(
      "type" -> "object",
      "properties" -> Map("a" -> Map("type" -> "number"))
    ))(args => ToolResult(List(Content.Text("ok"))))
    srv.resourceTemplate("mem://note/{id}", "note", "a note")(uri =>
      ResourceResult(uri, List(Content.Text("NOTE-" + uri))))
    // The SHORTEST spelling of each, driven because declaring an arity nobody calls is how a
    // declared-but-unimplemented member gets shipped — the exact shape `prompt` was in.
    srv.toolWithSchema("bare", Map("type" -> "object"))(args =>
      ToolResult(List(Content.Text("ok"))))
    srv.resourceTemplate("mem://bare/{id}")(uri =>
      ResourceResult(uri, List(Content.Text("BARE-" + uri)))))
  serveMcp(Transport.Stdio)
SSC
reg_in='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"resources/templates/list","params":{}}
{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"mem://note/7"}}
{"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"mem://bare/3"}}'
for lane in --v1 --v2; do
  if [[ $lane == --v1 ]]; then bin="$ROOT/bin/ssc-tools"; else bin="$LAUNCHER"; fi
  printf '%s\n' "$reg_in" | "$bin" run $lane "$tmp/reg.ssc" >"$tmp/reg$lane.out" 2>"$tmp/reg$lane.err" || true
  grep -q '"properties":{"a":{"type":"number"}}' "$tmp/reg$lane.out" || {
    echo "v21-standard-mcp-smoke: toolWithSchema did not publish its schema on $lane" >&2
    echo '  a bare {"type":"object"} here means the schema argument was ignored' >&2
    tail -3 "$tmp/reg$lane.out" "$tmp/reg$lane.err" >&2; exit 1
  }
  grep -q '"uriTemplate":"mem://note/{id}"' "$tmp/reg$lane.out" || {
    echo "v21-standard-mcp-smoke: resourceTemplate was not listed on $lane" >&2
    tail -3 "$tmp/reg$lane.out" "$tmp/reg$lane.err" >&2; exit 1
  }
  grep -q '"text":"NOTE-mem://note/7"' "$tmp/reg$lane.out" || {
    echo "v21-standard-mcp-smoke: the template did not serve the CONCRETE uri on $lane" >&2
    tail -3 "$tmp/reg$lane.out" >&2; exit 1
  }
  # The short arities: `toolWithSchema(name, schema)` and `resourceTemplate(template)`, both
  # DECLARED in std/mcp/server.ssc. Asserted here so that no declared spelling goes undriven.
  grep -q '"name":"bare"' "$tmp/reg$lane.out" || {
    echo "v21-standard-mcp-smoke: toolWithSchema(name, schema) — the 2-arg spelling — failed on $lane" >&2
    tail -3 "$tmp/reg$lane.out" "$tmp/reg$lane.err" >&2; exit 1
  }
  grep -q '"text":"BARE-mem://bare/3"' "$tmp/reg$lane.out" || {
    echo "v21-standard-mcp-smoke: resourceTemplate(template) — the 1-arg spelling — failed on $lane" >&2
    tail -3 "$tmp/reg$lane.out" "$tmp/reg$lane.err" >&2; exit 1
  }
done
grep -v '"serverInfo"' "$tmp/reg--v1.out" >"$tmp/reg.v1.cmp"
grep -v '"serverInfo"' "$tmp/reg--v2.out" >"$tmp/reg.v2.cmp"
cmp "$tmp/reg.v1.cmp" "$tmp/reg.v2.cmp" || {
  echo 'v21-standard-mcp-smoke: registration members answer DIFFERENTLY on the two lanes' >&2
  diff "$tmp/reg.v1.cmp" "$tmp/reg.v2.cmp" >&2 || true; exit 1
}
[[ $(wc -l <"$tmp/reg.v1.cmp") -eq 4 ]] || {
  echo "v21-standard-mcp-smoke: expected 4 non-initialize answers, got $(wc -l <"$tmp/reg.v1.cmp")" >&2
  cat "$tmp/reg.v1.cmp" >&2; exit 1
}

# ── notifications, progress and logging: eight members, on the wire ──────────
#
# THREE of these are CONDITIONAL, and a case that ignores the condition passes against a member
# that does nothing. The preconditions are set up here on purpose:
#   * `resources/subscribe` first, or `notifyResourceUpdate` is a no-op for that uri.
#   * `logging/setLevel debug` first, or `log` is dropped by the level floor.
#   * `_meta.progressToken` on the call, or `notifyProgress` has no token to attach to.
# `currentLogLevel` is read back INTO the tool result, so the setLevel above must have taken
# effect for the row to pass — `level=debug`, not the `info` default.
cat > "$tmp/notif.ssc" <<'SSC'
[mcpServer, serveMcp, Transport, ToolResult, ResourceResult, Content](std/mcp/server.ssc)

def main(): Unit =
  mcpServer(srv =>
    srv.resource("mem://a", "a")(uri => ResourceResult(uri, List(Content.Text("A"))))
    srv.tool("go")(args =>
      srv.notifyProgress(0.5, 1.0)
      srv.log("error", "LOGLINE")
      srv.notifyResourceUpdate("mem://a")
      srv.notifyToolsListChanged()
      srv.notifyResourcesListChanged()
      srv.notifyPromptsListChanged()
      srv.notify("notifications/custom", Map("k" -> "v"))
      ToolResult(List(Content.Text("level=" + srv.currentLogLevel()))))
  )
  serveMcp(Transport.Stdio)
SSC
notif_in='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}
{"jsonrpc":"2.0","id":2,"method":"resources/subscribe","params":{"uri":"mem://a"}}
{"jsonrpc":"2.0","id":3,"method":"logging/setLevel","params":{"level":"debug"}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"go","arguments":{},"_meta":{"progressToken":"T1"}}}'
for lane in --v1 --v2; do
  if [[ $lane == --v1 ]]; then bin="$ROOT/bin/ssc-tools"; else bin="$LAUNCHER"; fi
  printf '%s\n' "$notif_in" | "$bin" run $lane "$tmp/notif.ssc" >"$tmp/notif$lane.out" 2>"$tmp/notif$lane.err" || true
  for want in \
    '"method":"notifications/progress","params":{"progressToken":"T1","progress":0.5,"total":1}' \
    '"method":"notifications/message","params":{"level":"error","data":"LOGLINE"}' \
    '"method":"notifications/resources/updated","params":{"uri":"mem://a"}' \
    '"method":"notifications/tools/list_changed"' \
    '"method":"notifications/resources/list_changed"' \
    '"method":"notifications/prompts/list_changed"' \
    '"method":"notifications/custom","params":{"k":"v"}' \
    '"text":"level=debug"'
  do
    grep -qF "$want" "$tmp/notif$lane.out" || {
      echo "v21-standard-mcp-smoke: missing on $lane -> $want" >&2
      tail -4 "$tmp/notif$lane.out" "$tmp/notif$lane.err" >&2; exit 1
    }
  done
done
grep -v '"serverInfo"' "$tmp/notif--v1.out" >"$tmp/notif.v1.cmp"
grep -v '"serverInfo"' "$tmp/notif--v2.out" >"$tmp/notif.v2.cmp"
cmp "$tmp/notif.v1.cmp" "$tmp/notif.v2.cmp" || {
  echo 'v21-standard-mcp-smoke: notifications differ between the lanes' >&2
  diff "$tmp/notif.v1.cmp" "$tmp/notif.v2.cmp" >&2 || true; exit 1
}

# ── subscriptions, paging and completions ────────────────────────────────────
#
# Six members, and each assertion below is chosen so an UNIMPLEMENTED member fails it:
#
#   * PAGING — three tools registered with `setPageSize(2)`. The row wants exactly `t1`,`t2` and a
#     `nextCursor`. A `setPageSize` that did nothing would list all three and pass any
#     "did tools/list answer" check.
#   * COMPLETIONS — the suggestions are DERIVED from what the client typed (`x` -> `x-ann`), so a
#     handler that was never registered cannot produce them. This matters more than usual here:
#     `completion/complete` answers `{"values":[]}` for a MISSING handler rather than an error,
#     by design (graceful degradation, per spec), so asserting success would pass on a member that
#     does not exist.
#   * SUBSCRIPTIONS — both hooks put a distinct notification on the wire, so a hook that was
#     registered but never called, and a hook that was never registered, both fail.
#   * `currentPageSize` is read back INTO a tool result: `size=2` proves the setter took effect
#     rather than the getter merely returning a default.
#
# `completionForPrompt(name, arg, handler)` is NOT curried — three arguments in one call, which is
# what v1's intrinsic accepts. Writing it curried is what made the first draft of this row red.
cat > "$tmp/spc.ssc" <<'SSC'
[mcpServer, serveMcp, Transport, ToolResult, ResourceResult, PromptResult, Message, Role, Content](std/mcp/server.ssc)

def main(): Unit =
  mcpServer(srv =>
    srv.setPageSize(2)
    srv.tool("t1")(args => ToolResult(List(Content.Text("1"))))
    srv.tool("t2")(args => ToolResult(List(Content.Text("2"))))
    srv.tool("t3")(args => ToolResult(List(Content.Text("3"))))
    srv.resource("mem://a", "a")(uri => ResourceResult(uri, List(Content.Text("A"))))
    srv.prompt("greet", "g")(args => PromptResult(List(Message(Role.User, Content.Text("hi")))))
    srv.completionForPrompt("greet", "who", partial => List(partial + "-ann", partial + "-bo"))
    srv.onResourceSubscribe(uri => srv.notify("notifications/subbed", Map("uri" -> uri)))
    srv.onResourceUnsubscribe(uri => srv.notify("notifications/unsubbed", Map("uri" -> uri)))
    srv.tool("page")(args => ToolResult(List(Content.Text("size=" + srv.currentPageSize().toString)))))
  serveMcp(Transport.Stdio)
SSC
spc_in='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"completion/complete","params":{"ref":{"type":"ref/prompt","name":"greet"},"argument":{"name":"who","value":"x"}}}
{"jsonrpc":"2.0","id":4,"method":"resources/subscribe","params":{"uri":"mem://a"}}
{"jsonrpc":"2.0","id":5,"method":"resources/unsubscribe","params":{"uri":"mem://a"}}
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"page","arguments":{}}}'
for lane in --v1 --v2; do
  if [[ $lane == --v1 ]]; then bin="$ROOT/bin/ssc-tools"; else bin="$LAUNCHER"; fi
  printf '%s\n' "$spc_in" | "$bin" run $lane "$tmp/spc.ssc" >"$tmp/spc$lane.out" 2>"$tmp/spc$lane.err" || true
  for want in \
    '"nextCursor":"2"' \
    '"values":["x-ann","x-bo"]' \
    '"method":"notifications/subbed","params":{"uri":"mem://a"}' \
    '"method":"notifications/unsubbed","params":{"uri":"mem://a"}' \
    '"text":"size=2"'
  do
    grep -qF "$want" "$tmp/spc$lane.out" || {
      echo "v21-standard-mcp-smoke: missing on $lane -> $want" >&2
      tail -4 "$tmp/spc$lane.out" "$tmp/spc$lane.err" >&2; exit 1
    }
  done
  # The page must be SHORT: three tools registered, two listed.
  grep -qF '"name":"t3"' "$tmp/spc$lane.out" && {
    echo "v21-standard-mcp-smoke: setPageSize(2) did not bound the page on $lane — t3 was listed" >&2
    exit 1
  }
done
grep -v '"serverInfo"' "$tmp/spc--v1.out" >"$tmp/spc.v1.cmp"
grep -v '"serverInfo"' "$tmp/spc--v2.out" >"$tmp/spc.v2.cmp"
cmp "$tmp/spc.v1.cmp" "$tmp/spc.v2.cmp" || {
  echo 'v21-standard-mcp-smoke: subscriptions/paging/completions differ between the lanes' >&2
  diff "$tmp/spc.v1.cmp" "$tmp/spc.v2.cmp" >&2 || true; exit 1
}

echo 'PASS v21-standard-mcp-smoke (2 rows VM/ASM + server vs int + 2026 surface both lanes + elicit on the wire + prompts/resource bodies + toolWithSchema/resourceTemplate + 8 notification members + subscriptions/paging/completions)'
