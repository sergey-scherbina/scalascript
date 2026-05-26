# Interpreter Server Extraction

## Goals

- Move interpreter-owned HTTP/WS socket runtime out of `backend-interpreter`
  into a separately compilable module.
- Keep existing `serve`, `serveAsync`, WebSocket, cluster peer, and in-process
  frontend transport behavior unchanged.
- Avoid an sbt dependency cycle: server runtime needs `Interpreter`/`Value`,
  while interpreter core must not depend directly on the extracted server
  implementation.

## Non-goals

- This phase does not move `Routes` and `WsRoutes`; they remain in
  `backend-interpreter` because route registration is still used directly by
  interpreter cluster helpers and std plugins.
- This phase does not redesign HTTP/WS semantics, route matching, middleware,
  CORS, gzip, or validation behavior.
- This phase does not change the generated JVM server runtime used by
  `JvmGen`; that remains under `runtime/http-server/*`.

## Architecture

The extraction adds `backendInterpreterServer` at
`runtime/backend/interpreter-server`.

Moved implementation classes:

- `scalascript.server.WebServer`
- `scalascript.server.InterpreterHttpHandler`
- `scalascript.server.InProcessBackendTransport`
- `scalascript.server.WsClientSession`
- `scalascript.server.WsConnection`
- `scalascript.server.WsProxy`
- `scalascript.server.TlsProxy`
- `scalascript.bench.WsStress`

`backend-interpreter` now owns only the bridge contract:

```scala
trait InterpreterServerSupport
trait InterpreterWsClientSession
object InterpreterServerSupport
```

`InterpreterServerSupport.current` resolves an implementation with
`ServiceLoader`. The extracted module registers
`scalascript.server.InterpreterServerSupportImpl` under
`META-INF/services/scalascript.interpreter.InterpreterServerSupport`.

The interpreter calls the bridge for:

- server startup/shutdown;
- CORS/gzip/upload/body-size configuration;
- process-wide WS connection cap;
- blocking WS client sessions used by `wsConnectSync` and cluster peer links.

`cli` depends on `backendInterpreterServer`, so end-user behavior remains the
same. Tests that exercise `WsProxy`, `WsConnection`, and
`InProcessBackendTransport` moved with the extracted module.

## Migration

No `.ssc` source changes are required.

Scala callers that used `backendInterpreter` only for pure interpretation keep
that dependency. Callers that start interpreter-backed servers or directly use
`scalascript.server.WebServer`, `WsProxy`, `WsConnection`,
`WsClientSession`, or `InProcessBackendTransport` should add
`backendInterpreterServer` to their classpath.

If `backendInterpreterServer` is missing and a server operation is requested,
the interpreter raises a clear runtime error instead of failing with a missing
class.

## Phases

1. **Landed** — move socket/server runtime and WS server tests into
   `backendInterpreterServer`; keep `Routes`/`WsRoutes` in interpreter core;
   bridge via `InterpreterServerSupport`.
2. Move `Routes` and `WsRoutes` behind a registry interface once std plugins
   and cluster helpers no longer need direct object access.
3. Revisit published artifact boundaries: decide whether a future minimal
   interpreter distribution should omit `backendInterpreterServer` by default.

## Testing Strategy

- Compile `backendInterpreter` and `backendInterpreterServer`.
- Compile both test suites after moving server-specific tests.
- Run focused tests for the bridge and moved behavior:
  `InProcessBackendTransportTest`, representative `scalascript.server.*` WS
  tests, and at least one std WS plugin interpreter test.

## Open Questions

- Should `Routes`/`WsRoutes` become part of a small server registry SPI, or
  should route registration move fully into the extracted module?
- Should `backendInterpreterServer` be loaded by default by all CLI modes, or
  only by server/full-stack commands in a future minimized distribution?
