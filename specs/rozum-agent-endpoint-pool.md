# Rozum Agent Endpoint Pool

## Overview

Add optional endpoint-pool failover to `std.agent` so applications can point the
same app-owned agent loop at several OpenAI-compatible rozum gateways. The
existing single-endpoint APIs stay stable: `runAgent`, `runAgentStream`, and
`collectAgentStream` keep taking `AgentEndpoint` and keep their current
semantics. The new pool APIs retry only gateway transport failures and 5xx
responses, in bounded endpoint order, before any tool side effects are
performed for that model-call attempt.

## Interface

Public module: `runtime/std/agent.ssc`, package `std.agent`.

New exported type/functions:

```scalascript
case class AgentEndpointPool(
  endpoints: List[AgentEndpoint],
  maxAttempts: Int = 3
)

def runAgentPool(pool: AgentEndpointPool, model: String, system: String, user: String,
                 tools: List[AgentTool], options: RunOptions = RunOptions()): AgentResult

def runAgentStreamPool(pool: AgentEndpointPool, model: String, system: String, user: String,
                       tools: List[AgentTool], options: RunOptions = RunOptions())
                      (onEvent: AgentEvent => Unit): AgentResult

def collectAgentStreamPool(pool: AgentEndpointPool, model: String, system: String, user: String,
                           tools: List[AgentTool], options: RunOptions = RunOptions()): AgentStreamResult
```

`maxAttempts` is the agent-level number of endpoint attempts for each
model-call round. It is capped by the number of configured endpoints; values
less than `1` are treated as `1`. Endpoint order is stable and deterministic.
Callers should not enable ambient `std.http.httpRetry` around pool calls when
they need exact per-endpoint attempt counts, because that lower-level HTTP
policy can retry inside one endpoint attempt before the pool sees a response.

## Behavior

- [x] Existing `runAgent(endpoint, ...)`, `runAgentStream(endpoint, ...)`, and
      `collectAgentStream(endpoint, ...)` stay source-compatible and route
      through a single-endpoint pool internally.
- [x] `runAgentPool` tries endpoints in list order and falls through from
      transport exceptions or HTTP `5xx` responses to the next endpoint until
      `maxAttempts` is exhausted.
- [x] HTTP `4xx` responses are returned as `AgentResult(stop = "Error")`
      immediately and do not retry another endpoint.
- [x] Model-level tool calls, unknown tools, and handler validation errors use
      the normal tool-result feedback path and do not trigger endpoint retry.
- [x] Tool handlers are executed only after a successful model response; a
      failed gateway attempt never replays a local tool side effect.
- [x] After a successful tool-call response, the next model-call round starts
      from the first endpoint again so recovered gateways can rejoin without
      sticky health state in this small P2 slice.
- [x] The single-endpoint pool path behaves the same as the old single endpoint
      path for success, tool calls, maxSteps, and non-2xx errors.
- [x] Streaming pool APIs retry pre-stream `5xx` / transport failures, preserve
      ordered `AgentEvent`s on the successful endpoint, and do not retry `4xx`
      or stream-level OpenAI error frames.

## Design

The pool is pure `.ssc` library code layered over the existing `std.http`
requests:

- Non-streaming calls use a helper that returns the first non-retryable
  `Response` or the final exhausted `5xx`/transport diagnostic.
- Streaming calls use the same endpoint order, but each attempted stream keeps
  its text/tool/event state local to that attempt. The successful attempt's
  state is committed to the agent loop; failed `5xx` attempts do not leak events
  or partial tool accumulators.
- The pool is deliberately stateless. It does not keep health probes, circuit
  breakers, sticky endpoint affinity, or cross-run cooldowns.

The loop-level idempotency rule is the main invariant: retries only wrap the
remote model call. Tool handlers run after a successful response and are never
re-run because a prior gateway returned `5xx`.

## Decisions

- **New function names** — chosen to avoid relying on overload resolution for
  `runAgent`/`runAgentStream` while keeping the current public signatures stable.
  Rejected: overloading `runAgent(pool, ...)`, because the existing `.ssc`
  examples use explicit imports and a small, predictable export surface is
  easier to maintain.
- **Bounded ordered failover only** — chosen because `std.agent` has no portable
  sleep/backoff primitive and new intrinsics are out of scope. Rejected:
  adding `retryDelayMs` now, because it would advertise timing behavior the
  library cannot implement honestly across backends in this slice.
- **No sticky health state** — chosen so every run is deterministic and local to
  the call. Rejected: circuit breakers/health scores, because they require
  shared mutable state and a larger policy surface.
- **Retry transport/5xx only** — chosen to match HTTP client conventions and
  avoid masking user/model errors. Rejected: retrying `4xx`, unknown tools, or
  handler validation failures, because those are not gateway availability
  problems.
- **Do not mutate ambient HTTP retry state** — chosen because `httpRetry` is a
  broader `std.http` setting and `httpClient` is not importable through the
  current interpreter module path. Rejected: silently calling `httpRetry(0, 0)`
  inside `std.agent`, because that would change caller state outside the pool.

## Out of scope

- Exponential backoff or sleeps between endpoint attempts.
- Health probes, endpoint scoring, circuit breakers, or sticky affinity.
- Concurrent hedged requests.
- Per-tool idempotency keys or replay protection beyond "do not run tools until
  a model response succeeds".
- Live multi-gateway rozum conformance.
- Enforcing exact physical HTTP request counts when a caller has enabled
  ambient `std.http.httpRetry`.

## Results

Implemented in `runtime/std/agent.ssc` as pure `.ssc` library code, with no new
intrinsic plugin. Existing single-endpoint functions now delegate to
single-endpoint pools; new pool APIs cover non-streaming and streaming calls.

Verification:

- `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentEndpointPoolInterpreterTest"` — 7 endpoint-pool tests passed, covering ordered 5xx fallback, no retry on 4xx, `maxAttempts`, no retry on unknown-tool feedback, single-endpoint stability, streaming fallback, and `examples/rozum-agent-pool.ssc`.
- `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest scalascript.AgentEndpointPoolInterpreterTest"` — 21 agent SDK tests passed, including P0/P1 regression coverage and the endpoint-pool suite.
- While verifying, a test-order port collision between `examples/rozum-agent.ssc`
  and `AgentSdkStreamingInterpreterTest` was found, tracked in `BUGS.md`, fixed
  by moving the streaming suite to port `19698`, and verified in the formerly
  failing suite order.
