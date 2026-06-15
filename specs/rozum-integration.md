# Rozum Integration

## Overview

ScalaScript provides a generic agent SDK that lets an application call the
adjacent Rust `rozum` gateway as a stateless OpenAI-compatible model service.
The application owns the agent loop, session state, prompts, and tool handlers;
`rozum` receives `messages + tools` and returns either final assistant text or
tool calls. This mirrors `../rozum/docs/specs/integration.md` and
`../rozum/docs/specs/agent-sdk.md` while keeping the first ScalaScript slice
portable and library-only.

## Interface

Public module: `runtime/std/agent.ssc`, package `std.agent`.

P0 API:

```scalascript
package std.agent

case class AgentEndpoint(baseUrl: String, authToken: String = "")

case class RunOptions(
  temperature: Double = 0.0,
  maxSteps: Int = 8,
  maxTokens: Int = 4096,
  toolChoice: String = "auto"
)

case class ToolResult(contentJson: String, isError: Boolean = false)

case class AgentTool(
  name: String,
  description: String,
  parametersJson: String,
  handler: String => ToolResult
)

case class ExecutedOp(
  tool: String,
  argsJson: String,
  resultJson: String,
  isError: Boolean
)

case class AgentResult(
  text: String,
  operations: List[ExecutedOp],
  transcriptJson: String,
  stop: String
)

def runAgent(endpoint: AgentEndpoint, model: String, system: String, user: String,
             tools: List[AgentTool], options: RunOptions = RunOptions()): AgentResult

def agentTool(name: String, description: String, parametersJson: String)
             (handler: String => ToolResult): AgentTool
def toolOk(contentJson: String): ToolResult
def toolError(message: String): ToolResult
def objectSchema(propertiesJson: String, required: List[String]): String
```

`parametersJson` is a JSON Schema object accepted by the OpenAI tool/function
shape. Tool handlers receive the raw JSON argument object string and return JSON
text. Applications may parse arguments with `std.json.jsonValue`.

## Behavior

- [ ] `runAgent` sends `POST <baseUrl>/v1/chat/completions` with `model`,
      `messages`, `tools`, `tool_choice`, `temperature`, `max_tokens`, and
      `stream=false`.
- [ ] The request uses OpenAI-compatible function-tool JSON:
      `{type:"function", function:{name, description, parameters}}`.
- [ ] If rozum returns final assistant text, `runAgent` returns
      `AgentResult(stop = "Done")` with no synthetic tool execution.
- [ ] If rozum returns `finish_reason = "tool_calls"`, the SDK appends the
      assistant tool-call message, dispatches each known tool handler, appends
      tool-result messages, records `ExecutedOp`s, and calls rozum again.
- [ ] Unknown tools and malformed/missing tool arguments become tool-result
      errors fed back to the model instead of crashing the loop.
- [ ] The loop stops with `stop = "MaxSteps"` when `maxSteps` tool-call rounds
      are exhausted and preserves the transcript for audit/resume.
- [ ] HTTP status outside 2xx returns `AgentResult(stop = "Error")` with a
      diagnostic text; it does not throw from the public API.
- [ ] `authToken` adds `Authorization: Bearer <token>` when non-empty and is
      omitted otherwise.
- [ ] `examples/rozum-agent.ssc` demonstrates a fake accounting-style tool and
      points at a local rozum gateway URL.

## Design

P0 is implemented as pure `.ssc` composition over existing standard modules:

- `std.http` for `httpPost` and response status/body.
- `std.json` for request builders and response navigation.

No new backend intrinsic is required for the non-streaming P0 slice. If later
streaming SSE parsing, endpoint health state, or embedded Rust calls need native
support, those additions must live under `runtime/std/agent-plugin/`, not in
interpreter core.

The SDK stores the transcript as a JSON array string rather than introducing a
large public message ADT in P0. This keeps the public surface small and exactly
aligned with the gateway contract. A typed transcript can be added later without
breaking `transcriptJson`.

Testing uses a local Scala test with an in-process fake HTTP route that returns:

1. A first response containing a tool call.
2. A second response containing final text after the SDK posts a tool result.

The test asserts both request shape and resulting `AgentResult`.

## Decisions

- **busi/app owns the loop** -- chosen because rozum's integration spec makes
  the model gateway stateless and keeps session/orchestration state in the app.
  Rejected: rozum-owned stateful agent sessions, because they are harder to
  scale and fail over.
- **Library-first P0** -- chosen because `std.http` and `std.json` already cover
  the non-streaming gateway contract. Rejected: adding a new intrinsic plugin
  before a native capability is needed.
- **Explicit schemas first** -- chosen for a small, testable slice. Rejected:
  compiler/macro schema derivation in P0, because it is a separate feature with
  broader type-system/codegen implications.
- **Raw JSON tool arguments/results** -- chosen to keep the SDK domain-agnostic
  and avoid inventing a partial typed JSON codec in this slice. Rejected:
  busi-specific tool types in `std.agent`.

## Out of scope

- Streaming `client.stream(...)` / SSE event assembly.
- Endpoint pool, health checks, retry/failover across multiple rozum instances.
- JSON Schema derivation from ScalaScript handler signatures.
- Embedded Rust transport.
- MCP server exposure for the same tools.
- Any busi-specific accounting prompts, validation rules, or eval harness.

## Results

Filled during implementation verification.
