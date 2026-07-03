# Spec: agent-mcp round-trip test

**Slug:** agent-mcp-roundtrip  
**Status:** implementing  
**Date:** 2026-07-03

## Goal

Add an end-to-end round-trip test for the `std.agent.mcp` bridge
(`runtime/std/agent-mcp.ssc`). The bridge has two directions:

- `serveAgentToolsMcp(tools, transport)` — wraps a list of `AgentTool`s as MCP
  server tools and serves them over a transport.
- `mcpToolSource(client)` — connects to an MCP server and wraps its tools as
  `AgentTool`s for use in an agent loop.

The test must exercise both sides in a single JVM process: tool-call request
flows from the client-side `AgentTool`, through the MCP protocol, to the
server-side `AgentTool` handler, and the response flows back.

## Design

### Transport

Use in-process `LinkedBlockingQueue` pipes, the same pattern as `McpEndToEndTest`.
No OS subprocess, no network, no redirected `System.in/out`.

This means we cannot drive the full `serveAgentToolsMcp` function end-to-end
(it calls `serveMcp(Transport.Stdio)` which reads from `System.in`). Instead, the
test exercises the bridge mapping logic directly in Scala, replicating the exact
transformations the `.ssc` functions perform:

**Server side** (mirrors `serveAgentToolsMcp`):
- For each `AgentTool` t, register `builder.tool(t.name, Some(t.description), ...)`.
- The handler converts `Map[String,Any]` args → JSON string via `McpServerCore.scalaToJson` +
  `ujson.write`, passes to `t.handler`, maps `ToolResult` → `ToolHandlerResult`.
- Feed the builder to `McpServerCore.serve` with the in-process queues.

**Client side** (mirrors `mcpToolSource`):
- After handshake, call `listTools` on the MCP client.
- For each tool descriptor, build an `AgentTool` whose handler calls `callTool`
  and extracts the text content as `contentJson`.

### Coverage

| What | Tested |
|---|---|
| Tool name/description survives the round-trip | yes |
| `contentJson` is preserved unchanged | yes |
| `isError=false` response maps to `ToolResult.isError=false` | yes |
| `isError=true` response maps to `ToolResult.isError=true` | yes |
| Multiple tools visible via `listTools` | yes |

### What is NOT tested here

- The `serveMcp(Transport.Stdio)` I/O loop (already covered by `McpEndToEndTest`
  and `McpInterpreterIntegrationTest`).
- Interpreter-level invocation of `serveAgentToolsMcp`/`mcpToolSource` (these
  require a blocking Stdio server or a real subprocess; out of scope for this
  unit test).

## File locations

| File | Role |
|---|---|
| `runtime/std/agent-mcp.ssc` | Bridge implementation (already done) |
| `runtime/backend/interpreter-plugin-tests/src/test/scala/scalascript/AgentMcpRoundTripTest.scala` | New test |

## Gate

Test class `AgentMcpRoundTripTest` compiles and all tests in it pass under
`sbt "interpreterPluginTests/test"` (or at minimum `interpreterPluginTests/testOnly scalascript.AgentMcpRoundTripTest`).
