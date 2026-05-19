# Plan: v1.17 MCP own-implementation for interpreter (Phase 1)

**Worktree:** `.claude/worktrees/v1.17-mcp-int-own-impl`
**Branch:** `worktree-v1.17-mcp-int-own-impl`
**Scope:** First shippable slice — interpreter stdio MCP server + Spawn client.
**Estimate:** ~600-800 LOC across new files; the existing `std/mcp/*.ssc`
user-facing API stays unchanged.

## Phases

### Phase 1 (THIS SESSION) — Interpreter stdio + spawn

- **1a.** Pure-Scala MCP runtime in `backend-interpreter/src/main/scala/scalascript/interpreter/mcp/`:
  - `JsonRpc.scala` — JSON-RPC 2.0 message types + parse/serialise via upickle/ujson
  - `McpProtocol.scala` — MCP-specific request/response shapes (tools/list, tools/call, resources/list, resources/read, prompts/list, prompts/get, initialize, ping)
  - `McpServerCore.scala` — handler registry (tool/resource/prompt registrations) + dispatch loop. Transport-agnostic; takes a `Reader: () => Option[String]` and `Writer: String => Unit`.
  - `McpClientCore.scala` — JSON-RPC pending-request table, response routing by id, blocking `request()` API.
- **1b.** Interpreter intrinsics in `backend-interpreter/src/main/scala/scalascript/interpreter/intrinsics/Mcp.scala`:
  - `mcpServer(setup)` — installs the McpServerBuilder global; user-side setup block runs and registers handlers via `srv.tool`/`srv.resource`/`srv.prompt`.
  - `serveMcp(Transport.Stdio)` — drives the dispatch loop reading from `System.in`, writing to `System.out`. Blocks until EOF.
  - `mcpConnect(Transport.Spawn(cmd, args))` — `ProcessBuilder` spawns child, frames JSON-RPC over its stdin/stdout, returns an `InstanceV` exposing `listTools/listResources/listPrompts/callTool/readResource/getPrompt/close/isClosed`.
  - HTTP and WS transports stub out with `Pure(Value.UnitV)` and a warning log — Phase 2/3 work.
- **1c.** Capability flags: add `Feature.McpServer` + `Feature.McpClient` to `InterpreterCapabilities`.
- **1d.** Tests: `McpStdioServerTest` + `McpSpawnClientTest` (use Echo handler + a self-spawned subprocess that runs an MCP server in `--mcp-stdio` mode for the client).
- **1e.** Update `MILESTONES.md` + `docs/mcp.md` § 6.3 (interpreter — no longer deferred for stdio).

### Phase 2 (FUTURE SESSION) — HTTP+SSE on interpreter

- Reuse `WebServer` infrastructure for the HTTP listener.
- Add SSE response writer (chunked text/event-stream).
- `serveMcp(Transport.Http(port, path))` accepts POSTs of JSON-RPC requests and streams responses via SSE.

### Phase 3 (FUTURE SESSION) — scalajs-spa client

- Cross-build the pure-Scala `JsonRpc` / `McpProtocol` / `McpClientCore` modules.
- New HTTP+SSE transport implementation using browser `fetch` + `EventSource`.
- `Feature.McpClient` added to `ScalaJsCapabilities` (server stays unsupported — server in browser doesn't make sense).

## What stays unchanged

- `std/mcp/{types,server,client,index}.ssc` user-facing API.
- `JvmRuntimeMcp` (JVM backend keeps using `io.modelcontextprotocol:sdk`).
- `JsRuntimeMcp` (JS backend keeps using `@modelcontextprotocol/sdk`).
- The `CapabilityCheck` / `CapabilityRegistry` machinery — we only flip flags.

## Acceptance criteria (Phase 1)

- A `.ssc` file with `mcpServer { srv => srv.tool("echo") { args => Tool.text(requireString(args, "msg")) } }; serveMcp(Transport.Stdio)` runs under the interpreter, accepts JSON-RPC over stdin, replies on stdout.
- The same `.ssc` invoked via `Transport.Spawn` from another interpreter run produces a working `client.callTool("echo", Map("msg" -> "hello"))` → `ToolResult(Content.Text("hello"))`.
- `sbt test` green on `core/`, `backendInterpreter/`, `cli/` (pre-existing `SupervisorTest` flake excluded).
- No regression in JVM/JS MCP suites.

## Risk

- **Concurrent reads on `System.in`**: stdio server blocks on `BufferedReader.readLine()`. Need to handle EOF cleanly (return from `serveMcp`).
- **Subprocess lifecycle**: `mcpConnect(Transport.Spawn)` must `Process.destroy()` on `client.close()`. Verify via test that no zombie processes linger.
- **JSON-RPC framing**: the spec uses line-delimited JSON for stdio (one JSON object per line). HTTP+SSE uses `Content-Length` headers — defer to Phase 2.
- **Error handling**: tool handlers may throw `McpError`. Interpreter catches via `attemptCatchRaw` analog, marshals to JSON-RPC `error` field.

## Cleanup

- After Phase 1 lands and pushes to `origin/main`: `ExitWorktree(action: "remove")`, delete local + remote branch, archive PLAN.md (or merge surviving sections into MILESTONES.md).
