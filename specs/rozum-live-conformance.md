# Rozum Live Conformance

## Overview

ScalaScript's `std.agent` P0 has fake-gateway tests for the OpenAI-compatible
agent loop. This feature adds a skipped-by-default live conformance test that
can be pointed at a real adjacent Rust `rozum` gateway and verifies that the
implemented `std.agent` request/response contract still matches rozum's
`POST /v1/chat/completions` surface.

The test is intentionally a conformance smoke, not an eval. It proves transport
shape, authentication wiring, non-streaming `stream:false`, JSON parsing, and the
`AgentResult` contract against a real gateway. It does not judge model quality or
require deterministic natural-language text.

## Interface

New opt-in test class:

```text
runtime/backend/interpreter-plugin-tests/src/test/scala/scalascript/RozumLiveConformanceTest.scala
```

Environment contract:

| Env var | Required | Meaning |
|---|---:|---|
| `ROZUM_BASE_URL` | yes | Gateway base URL without `/v1`, for example `http://127.0.0.1:8089`. |
| `ROZUM_MODEL` | yes | Model id accepted by the gateway, for example a cached local model spec or an id from `GET /v1/models`. |
| `ROZUM_AUTH_TOKEN` | no | Optional bearer token; when set, passed as `AgentEndpoint.authToken`. |

Run command:

```bash
cd <absolute-worktree-path> && sbt "backendInterpreterPluginTests/testOnly scalascript.RozumLiveConformanceTest"
```

The no-env path must cancel/skip the test, not fail. To run against the adjacent
rozum gateway with a real local model or configured upstream:

```bash
cd /Users/sergiy/work/my/rozum
cargo run -- gateway --port 8089 --model <model-spec>

# Or, with any OpenAI-compatible upstream already serving /v1:
ROZUM_BACKEND_URL=http://127.0.0.1:<upstream-port>/v1 \
  cargo run -- gateway --port 8089 --model <upstream-model-id>

cd <absolute-worktree-path> && \
  ROZUM_BASE_URL=http://127.0.0.1:8089 \
  ROZUM_MODEL=<model-id> \
  sbt "backendInterpreterPluginTests/testOnly scalascript.RozumLiveConformanceTest"
```

If the gateway was started through `rozum launch`, export its
`ROZUM_GATEWAY_URL` value as `ROZUM_BASE_URL`; `OPENAI_BASE_URL` is not used
because launch-oriented OpenAI clients conventionally include `/v1`.

## Behavior

- [x] With no `ROZUM_BASE_URL` or no `ROZUM_MODEL`, the live test cancels with a
      clear message and the suite remains green.
- [x] With both required env vars set, the test runs a ScalaScript snippet that
      imports `std.agent`, calls `runAgent`, and reaches
      `<ROZUM_BASE_URL>/v1/chat/completions`.
- [x] The request uses `stream:false`, `temperature:0`, a bounded `max_tokens`,
      no tools, and the configured `model`.
- [x] `ROZUM_AUTH_TOKEN` is not printed or logged and, when non-empty, is sent
      through `AgentEndpoint` as `Authorization: Bearer <token>`.
- [x] A conforming non-streaming response returns `AgentResult.stop = "Done"`,
      a non-empty final `text`, zero operations, and a transcript containing the
      system and user messages.

## Design

The live conformance test belongs beside `AgentSdkInterpreterTest` in
`backendInterpreterPluginTests` because it exercises the same interpreter plus
`HttpInterpreterPlugin` and `JsonInterpreterPlugin` stack, while replacing the
in-process fake HTTP server with a real rozum endpoint.

The test does not add any new public `std.agent` API. It constructs an
`AgentEndpoint` from test-side environment values and runs an ordinary `.ssc`
snippet. This keeps secrets out of source and avoids adding environment logic to
`runtime/std/agent.ssc`.

Assertions are structural rather than exact-output:

- `stop == "Done"` proves the non-streaming response shape was parsed.
- `text.nonEmpty` works for real local models without depending on exact wording.
- `operations.length == 0` proves a no-tool prompt did not synthesize tool work.
- transcript containment checks prove request-side message assembly survived the
  real round trip.

## Decisions

- **Opt-in env gate** — chosen because CI and most worktrees do not have a real
  rozum gateway/model running. Rejected: starting rozum from the test, because
  model loading is environment-specific and can be slow or unavailable.
- **`ROZUM_BASE_URL` instead of `OPENAI_BASE_URL`** — chosen because `std.agent`
  wants a base URL before `/v1`, while OpenAI client env often already includes
  `/v1`. Rejected: guessing and stripping path segments implicitly.
- **Structural assertions** — chosen because model text is nondeterministic.
  Rejected: requiring an exact phrase, which would turn this conformance smoke
  into a model-following eval.
- **No tool call in the live smoke** — chosen because it works with the `hello`
  backend and any text-capable gateway. Rejected: requiring a real model to choose
  a tool deterministically; the fake-gateway P0 tests already cover tool-loop
  semantics.

## Out of scope

- Starting or supervising the Rust `rozum` gateway from sbt.
- Live streaming/SSE conformance.
- Endpoint pool/retry/failover.
- Tool-call live evals with a real model.
- JSON Schema derivation.

## Results

Implemented as
`runtime/backend/interpreter-plugin-tests/src/test/scala/scalascript/RozumLiveConformanceTest.scala`.
The test reads `ROZUM_BASE_URL`, `ROZUM_MODEL`, and optional
`ROZUM_AUTH_TOKEN` on the ScalaTest side, constructs an `AgentEndpoint`, and
executes the same interpreter/plugin stack as `AgentSdkInterpreterTest`.

Verified the default no-env path with:

```bash
cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-live-conformance && sbt "backendInterpreterPluginTests/testOnly scalascript.RozumLiveConformanceTest"
```

Result on 2026-06-15: suite success with one canceled test:
`ROZUM_BASE_URL not set - opt in with ROZUM_BASE_URL and ROZUM_MODEL to hit a live rozum gateway`.

Attempted to start the adjacent rozum gateway with the historical docs smoke
model:

```bash
cd /Users/sergiy/work/my/rozum
cargo run -- gateway --port 18089 --model hello
```

The current rozum CLI built successfully but rejected `hello` with
`no backend found for 'hello'`; current live verification therefore requires a
real cached model or `ROZUM_BACKEND_URL` upstream, as documented in the
Interface section.
