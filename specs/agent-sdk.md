# spec: agent-sdk — the generic, domain-agnostic LLM-agent SDK (scalascript side)

Status: **P0–P2 SHIPPED; P3 + conformance remaining.** This is the consolidated scalascript-side
spec for the agent SDK. It mirrors rozum's design specs — `rozum:docs/specs/agent-sdk.md`
(public API contract) and `rozum:docs/specs/integration.md` (the 3 contracts, distributed-first
rationale) — and supersedes the three slice specs as the single entry point:
[`rozum-agent-streaming.md`](rozum-agent-streaming.md),
[`rozum-agent-endpoint-pool.md`](rozum-agent-endpoint-pool.md),
[`rozum-agent-schema-derivation.md`](rozum-agent-schema-derivation.md).

## Position in the stack (3 tiers)

```
app (e.g. busi)   DOMAIN: tools (handler+schema) + prompts + eval. Thin leaf.
   │ uses
std.agent (THIS)  GENERIC: ModelClient · AgentLoop · ToolRegistry · SchemaDerivation ·
   │ consumes      EndpointPool/retry · Transcript · (P3) MCP bridge · (P3) Embedded
rozum gateway     stateless: POST /v1/chat/completions (tools, SSE) → tool_calls | text
   │ below SPI     + (optional) Rust reference agent-runtime (embedded twin)
local MLX / etc.  the model
```

The app owns the loop's *state* and *validates every operation*; the SDK never performs a side
effect — it only calls the app's tool handler (the safety guarantee that makes a small local model
safe to drive). "No AI at runtime/compile time" (AGENTS.md) is about the **language**; this is a
**library** an app uses to call an external model — fully compatible.

## What's SHIPPED (`runtime/std/agent.ssc`)

- **Contract 1 — model client.** `postChatCompletions` (HTTP/JSON to the rozum gateway,
  OpenAI `/v1/chat/completions` form), text + `tool_calls` parsing, `finish_reason`.
- **Contract 2 — agent loop.** `runAgent` / `runAgentPool` → `runAgentLoop`:
  `[system,user] → model → (tool_calls → dispatch → tool results)* → final text`, budget via
  `RunOptions` (maxSteps), `AgentResult{text, operations, transcript, stop}`.
- **Contract 3 — tools.** `AgentTool(name, description, parametersJson, handler: String => ToolResult)`,
  `ToolResult(contentJson, isError)`, `agentTool(...)`, `toolOk`/`toolError`; unknown-tool/handler
  error fed back to the model (not a crash).
- **Streaming (P1).** `runAgentStream` / `collectAgentStream` → `runAgentStreamLoop`, SSE deltas
  → `AgentEvent` (TextDelta / ToolCall* / Stopped / Errored). spec `rozum-agent-streaming.md`.
- **EndpointPool + retry/failover (P1).** `AgentEndpointPool(endpoints, attemptLimit)`,
  `postChatCompletionsFrom` rotates endpoints on failure with capped attempts. spec
  `rozum-agent-endpoint-pool.md`.
- **SchemaDerivation (P2).** `agentToolFor[A](name, desc, schema)(handler: A => ToolResult)` +
  `AgentSchema` (`objectSchema`, `agentJsonSchemaForType`, `agentDecodeValue`, list/option) — strict
  JSON-Schema from a typed handler so small models fill args correctly. spec
  `rozum-agent-schema-derivation.md`.
- Examples: `rozum-agent{,-pool,-streaming,-schema-derived}.ssc`. Tests: `AgentSdk`,
  `AgentEndpointPool`, `AgentSchemaDerivation`, `AgentSdkStreaming` (interp-plugin-tests).

## Remaining work (this milestone)

### P3a — MCP bridge (the "one tool definition, two consumers")

scalascript already ships a full MCP stack — `std/mcp/server.ssc` (`mcpServer`, `serveMcp`,
`McpServer.tool(name, desc)(handler: Map[String,Any] => mcp.ToolResult)`), `std/mcp/client.ssc`
(`mcpConnect → McpClient{ listTools(): List[ToolDescriptor], callTool(name, Map): mcp.ToolResult }`),
`std/mcp/types.ssc` (`mcp.ToolResult(content: List[Content], isError)`, `requireString`, …). The
agent SDK does NOT yet bridge to it. Two directions, both belong in a NEW module
`runtime/std/agent-mcp.ssc` (keeps `agent.ssc` MCP-free; the bridge imports both layers):

1. **Expose `AgentTool`s over MCP** (external agent drives the app):
   `serveAgentToolsMcp(tools: List[AgentTool])(transport)` → `mcpServer { srv =>
   tools.foreach(t => srv.tool(t.name, t.description) { args => …t.handler(argsMapToJson(args))… }) }`.
2. **Consume an MCP server's tools as `AgentTool`s** (MCP as a tool source for the loop):
   `mcpToolSource(client: McpClient): List[AgentTool]` — `listTools()` → for each, an `AgentTool`
   whose handler does `client.callTool(name, jsonToArgsMap(argsJson))`.

**Design decisions to resolve in code:**
- **`ToolResult` name collision** — `agent.ToolResult(contentJson: String, …)` vs
  `mcp.ToolResult(content: List[Content], …)`. The bridge module needs both. Resolve via import
  alias if scalascript supports `[ToolResult as McpToolResult]`; ELSE the bridge defines explicit
  converters and refers to the MCP type through a thin re-export, never importing both unqualified.
  (First implementation task: confirm scalascript import-aliasing; pick the cleanest.)
- **args `Map[String,Any]` ↔ JSON string** — MCP handlers/`callTool` use a `Map`; agent
  handlers/`parametersJson` use a JSON string. Add `argsMapToJson` / `jsonToArgsMap` helpers
  (reuse the JSON plugin; the schema-derivation code already parses `JsonValue`).
- **ToolResult conversion** — agent→mcp: `mcp.ToolResult(List(Content.Text(r.contentJson)), r.isError)`;
  mcp→agent: join the `Content.Text` parts into `contentJson`, carry `isError`.

### P3b — Embedded transport (deferred, cross-repo)

`Endpoint.Embedded(backend)` runs an in-process rozum backend (Rust crate, small model, no
network); the loop/tools are identical, only `ModelClient`'s transport differs. Blocked on rozum
shipping the **`rozum-embed`** public crate (`rozum:docs/specs/integration.md` "Rust reference
runtime" follow-up). Document as a follow-up; do not build the scalascript side until the crate exists.

### Conformance

- **Mock gateway** — a fake Contract-1 endpoint (a local HTTP server, or an injectable transport)
  scripted to return canned `text`/`tool_calls` → test the loop, dispatch, budget, retry with NO
  real model. (The streaming test already avoids a real model via a port; generalise it.)
- **Golden transcripts** — record a few runs; assert transcript STRUCTURE (not exact model text).
- **Live rozum smoke** — a small opt-in suite hitting a real rozum gateway to pin Contract-1.

## Non-goals

- Domain logic / business validation (the app's handlers own it).
- Model serving / inference (rozum, below the SPI).
- A bespoke wire protocol — OpenAI/Anthropic over HTTP+SSE is the contract.
- Owning session/business state (the app does; the SDK is stateless between `run`s save the transcript).

## Phased status

- [x] **P0** ModelClient + AgentLoop + ToolRegistry + `run()`.
- [x] **P1** `stream()` + AgentEvents; EndpointPool + retry/failover; Transcript.
- [x] **P2** SchemaDerivation from typed handlers; resume-able transcript.
- [~] **P3a** MCP bridge — `runtime/std/agent-mcp.ssc`:
  - [x] **provider direction** `serveAgentToolsMcp(tools, transport)` — expose `AgentTool`s over
        `mcpServer` (Map→JSON via `jsonStringify`; agent→mcp result via `Tool.text`/`Tool.error`,
        no `ToolResult` name collision). `example/agent-mcp-server.ssc`; module + example
        `ssc check` OK.
  - [ ] **consumer direction** `mcpToolSource(client)` — wrap an MCP server's tools as `AgentTool`s.
        BLOCKED on a JSON-string → `Map[String,Any]` helper (`JsonValue` has no `asMap`); add a
        small `jsonToArgsMap` (json plugin or json.ssc) first.
  - [ ] round-trip test (server+client) — needs an MCP transport workable in the JVM interp test
        (Http is JS-only; Stdio blocks); mirror `McpEndToEndTest`'s approach.
- [ ] **P3b** Embedded transport — deferred until `rozum-embed` crate exists.
- [ ] **Conformance** — mock-gateway loop test + golden transcripts + live-rozum smoke.
