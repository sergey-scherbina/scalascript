# Rozum Agent Streaming

## Overview

Add P1 streaming support to `std.agent` for OpenAI-compatible rozum chat
completions. The existing P0 `runAgent` API remains unchanged and keeps using
non-streaming `httpPost`. The new streaming API uses the already-available
`std.http.httpPostStream` line callback to consume SSE `data:` frames, emit
typed-ish agent events to the application, assemble text/tool-call deltas, and
return the same final `AgentResult` shape as `runAgent`.

This is still an app-owned agent loop: rozum remains a stateless model service,
and ScalaScript owns message history, tool dispatch, operation recording, and
the stopping budget.

## Interface

Public module: `runtime/std/agent.ssc`, package `std.agent`.

New exported types/functions:

```scalascript
case class AgentEvent(
  kind: String,
  text: String = "",
  tool: String = "",
  argsJson: String = "",
  resultJson: String = "",
  isError: Boolean = false,
  stop: String = ""
)

case class AgentStreamResult(
  result: AgentResult,
  events: List[AgentEvent]
)

def runAgentStream(endpoint: AgentEndpoint, model: String, system: String, user: String,
                   tools: List[AgentTool], options: RunOptions = RunOptions())
                  (onEvent: AgentEvent => Unit): AgentResult

def collectAgentStream(endpoint: AgentEndpoint, model: String, system: String, user: String,
                       tools: List[AgentTool], options: RunOptions = RunOptions()): AgentStreamResult
```

`AgentEvent.kind` values:

| Kind | Meaning |
|---|---|
| `TextDelta` | Incremental assistant text; `text` carries the delta. |
| `ToolCallStarted` | A streamed tool call id/name was seen; `tool` carries the name. |
| `ToolCallDelta` | Incremental tool argument JSON; `tool` carries the name when known, `argsJson` carries the delta chunk. |
| `ToolCallResult` | A local handler finished; `tool`, `resultJson`, and `isError` are populated. |
| `Stopped` | The run ended; `stop` is `Done`, `MaxSteps`, or `Error`. |
| `Errored` | A stream-level error was observed; `text` carries the diagnostic. |

The API is synchronous from the caller's point of view: `runAgentStream` drains
each rozum SSE response, calls `onEvent` as chunks arrive, performs any required
tool dispatch, and returns the final `AgentResult`.

## Behavior

- [x] `runAgentStream` sends the same OpenAI-compatible request shape as
      `runAgent`, except `stream=true` and `Accept: text/event-stream`.
- [x] Text SSE deltas append to the final `AgentResult.text` and emit
      `TextDelta` events in order.
- [x] Tool-call SSE deltas assemble `id`, `name`, and chunked
      `function.arguments` by `tool_calls[].index`; when `finish_reason` is
      `tool_calls`, the SDK appends the assistant tool-call message, dispatches
      handlers, emits `ToolCallResult`, appends tool-result messages, and
      continues the loop with another streaming model call.
- [x] Unknown tools and handler validation errors use the same tool-error
      feedback path as `runAgent`.
- [x] `maxSteps` caps streamed tool-call rounds and returns `AgentResult.stop =
      "MaxSteps"` with a `Stopped(MaxSteps)` event.
- [x] Non-2xx HTTP status, OpenAI SSE error frames, or transport exceptions
      return `AgentResult.stop = "Error"` and emit `Errored`/`Stopped(Error)`.
- [x] `collectAgentStream` returns the same final result and the ordered event
      list without requiring users to manage a mutable event buffer.

## Design

No new backend intrinsic is required. `std.http` already exports
`httpPostStream(url, body, headers) { line => ... }`, and the interpreter HTTP
plugin implements it with `java.net.http.HttpResponse.BodyHandlers.ofLines()`.
That means the agent SDK sees one line at a time:

- `data: <json>` for each OpenAI-compatible SSE chunk.
- `data: [DONE]` terminator.
- blank/comment/event lines are ignored for this P1 slice.

The implementation will add pure `.ssc` helpers to `runtime/std/agent.ssc`:

- `requestBodyWithStream(..., stream: Boolean)` so `runAgent` keeps
  `stream=false` while `runAgentStream` sends `stream=true`.
- `streamRequestHeaders` to add `Accept: text/event-stream` while preserving
  bearer auth.
- `handleStreamLine`, `applyToolCallDeltas`, and loop-local helpers that update
  text, pending tool-call accumulators, and finish/error state.

Pending tool calls are stored in a small list keyed by streamed
`tool_calls[].index`. This avoids assuming one tool call while still keeping the
P1 implementation simple and dependency-free.

Testing uses an in-process fake HTTP server that writes finite SSE fixtures.
The fixtures cover text deltas, chunked tool-call arguments followed by a
second streamed final answer, stream error frames, and `MaxSteps`.

## Decisions

- **Callback API first** — chosen because `httpPostStream` is callback-shaped
  and synchronous in the interpreter. Rejected: introducing a lazy `Stream` or
  async iterator abstraction in this slice; that belongs in a broader streaming
  runtime design.
- **String `kind` events** — chosen to match P0's string-based `stop` and avoid
  adding a public enum/sum-type surface before the language has a settled event
  ADT style. Rejected: one case class per event kind, because it would broaden
  the public API without changing behavior.
- **Structural SSE parsing** — chosen because OpenAI/rozum frames are one JSON
  payload per `data:` line. Rejected: a general-purpose SSE parser in `std.http`
  for P1; if other users need full `event:`/multi-line `data:` support, that
  should be a separate `std.http` feature.
- **Shared tool dispatch** — chosen so streamed and non-streamed loops handle
  unknown tools and handler failures identically. Rejected: a separate streaming
  tool path with subtly different validation behavior.

## Out of scope

- True asynchronous stream objects / cancellation handles.
- Endpoint pool/retry/failover.
- Live streaming conformance against a real model.
- Anthropic `/v1/messages` streaming.
- Full SSE grammar support beyond OpenAI-compatible `data:` frames.
- JSON Schema derivation.

## Results

Implemented in `std.agent` with no new backend intrinsic: streaming reuses
`std.http.httpPostStream`, sends `stream=true`, and preserves the existing
non-streaming `runAgent` behavior.

Verification:

- `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-streaming && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkStreamingInterpreterTest scalascript.AgentSdkInterpreterTest"` — 14 tests passed, covering text deltas, callback delivery, streamed tool-call assembly/dispatch/continuation, non-2xx errors, SSE error frames, maxSteps, sync API regression, and both `rozum-agent` examples.
- `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-streaming && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkStreamingInterpreterTest"` — 7 streaming tests passed after adding `examples/rozum-agent-streaming.ssc`.
