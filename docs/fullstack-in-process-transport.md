# Full-Stack In-Process Transport

Status: **planned / partially implemented** — May 2026. Phase 1 landed:
internal `BackendTransport` request/response types exist, and `ssc run`
parses/validates `--transport http|in-process` plus front matter
`fullstack.transport` / `transport`. Phase 2 landed: interpreter routes can be
dispatched through `InProcessBackendTransport` without opening a socket. CLI
full-stack `--transport in-process` execution is not implemented yet for the
general interpreter/browser path. The generated JVM/Swing path can already run
the UI in the backend JVM and dispatch Swing `fetchAction` handlers to the
generated route registry without opening an HTTP socket; see
`examples/frontend/swing-fullstack/`.

This document defines the planned monolithic full-stack mode: frontend and
backend logic can run in one process where the selected targets make that
possible, and backend calls use an in-process transport instead of TCP sockets
and HTTP. Split-process and distributed HTTP modes remain first-class and are
not replaced by this mode.

## Goals

- Add a separate full-stack transport mode for local monolithic execution.
- Avoid network sockets and HTTP parsing when frontend and backend handlers can
  live in the same runtime process.
- Preserve the same observable request/response semantics as split REST mode,
  so application code does not fork into "HTTP app" and "monolithic app"
  variants.
- Make transport selection explicit in CLI/front matter before changing default
  behavior.
- Keep server-only and distributed clients on HTTP/REST.
- Provide a path for fast tests that exercise full-stack routes without opening
  sockets.

## Non-Goals

- Do not remove or de-prioritize REST. It is still required for distributed
  clients, browser-to-JVM apps, external integrations, and deployment.
- Do not let client code directly access server-only databases, secrets, or
  mutable runtime state.
- Do not promise zero serialization in the first phase. The first contract keeps
  a boundary equivalent to REST; later phases may optimize trusted in-process
  calls.
- Do not make every frontend target support in-process mode. Unsupported
  frontend/backend combinations should produce diagnostics and fall back only
  when the user explicitly chooses a fallback.
- Do not implement packaging in this spec. Desktop packaging remains owned by
  the Electron/JVM REST milestone until a concrete in-process desktop target
  exists.

## Architecture

Full-stack execution gets a transport axis orthogonal to the selected frontend
and backend targets:

```text
Frontend API call
  -> BackendTransport
      -> HttpBackendTransport       # split/local and distributed REST
      -> InProcessBackendTransport  # same process, no sockets
      -> ElectronBridgeTransport    # optional desktop bridge adapter
```

The transport boundary should expose the same logical operation shape for all
implementations:

```scala
trait BackendTransport:
  def request(req: BackendRequest): Future[BackendResponse]

final case class BackendRequest(
  method: String,
  path: String,
  headers: Map[String, String],
  body: Array[Byte]
)

final case class BackendResponse(
  status: Int,
  headers: Map[String, String],
  body: Array[Byte]
)
```

The actual code does not need these exact types in Phase 1; they define the
contract shape. The important property is that in-process dispatch crosses the
same async request/response boundary as REST dispatch.

### Transport Semantics

In-process transport must preserve:

- async completion;
- route matching by method and path;
- request headers/body and response status/headers/body;
- the same error model that HTTP clients observe for missing routes and handler
  failures;
- side diagnostics: client-side code cannot call server-only resources except
  through declared route/API boundaries;
- deterministic test behavior without requiring a bound port.

The first implementation should dispatch through the existing route registry or
an adapter around it, not by calling arbitrary backend functions directly. Typed
service proxies can be layered on top later.

### CLI And Front Matter

Planned command surface:

```bash
ssc run --mode fullstack --transport http app.ssc
ssc run --mode fullstack --transport in-process app.ssc
ssc run --transport in-process app.ssc
```

Planned front matter:

```yaml
frontend: electron
backend: jvm
fullstack:
  transport: in-process
```

Open spelling choices are intentionally small. The transport names should be:

- `http` for local split-process or distributed REST;
- `in-process` for monolithic same-process dispatch.

`--mode server` and `--mode client` ignore `in-process` because they are
separate-process modes by definition. `--server-url` implies `http`.

### Target Compatibility

Initial compatibility matrix:

| Frontend target | Backend target | In-process viability | Notes |
|---|---|---|---|
| interpreter/test harness | interpreter | yes | Best Phase 1 target. No sockets; direct route registry dispatch. |
| Swing JVM desktop | JVM | yes, partially implemented | Same-process `SwingRuntime` exists; `fetchAction` dispatches through the generated JVM route registry; `examples/frontend/swing-fullstack/` demonstrates the no-socket path. `fetchTable` and typed clients remain planned. |
| JavaFX/Compose JVM desktop | JVM | possible later | Better modern UI options, but require extra dependencies and packaging work. |
| Electron renderer | JVM | no, not directly | Different processes/runtimes. Use HTTP sidecar or a future bridge through Electron main. |
| Electron main/local-first | JS/Node | possible later | Could use an Electron bridge transport, not JVM in-process. |
| Browser React/Vue/Solid | JVM | no | Browser and JVM are separate runtimes; HTTP remains required. |
| Browser local-first | JS/WASM | possible later | Only when backend routes are compiled into the browser bundle and allowed by capability checks. |

The first shippable slice should therefore target interpreter/JVM-local testing,
then expand only where there is a real same-process runtime.

### Composition With Existing REST Work

The Electron JVM REST milestone remains the default path for Electron + JVM
backend because Electron renderer and JVM backend are not one process. This
spec complements that work:

- HTTP transport handles local split and distributed deployments.
- In-process transport handles monolithic runtimes and fast tests.
- A future bridge transport may handle desktop shells where the frontend process
  can synchronously or asynchronously call a backend host process without public
  network sockets.

The shared contract should live below generated UI helpers such as `fetchTable`,
`fetchAction`, and future typed API clients. User code should select a transport
through configuration, not by rewriting every call site.

## Migration

No existing behavior changes in Phase 0. Current full-stack Electron/JVM apps
continue to use HTTP.

Phase 1 should add the transport contract and diagnostics behind explicit
configuration. If a user requests `--transport in-process` for an unsupported
target pair, the CLI should fail with a clear diagnostic rather than silently
falling back to HTTP.

Once enough target pairs are implemented, defaults can be revisited:

- local tests may default to in-process;
- Electron + JVM should continue to default to HTTP unless a real bridge target
  lands;
- distributed commands always stay HTTP.

## Phases

### Phase 0 — Spec And Milestone

Land this design, document the planned mode as not implemented, and add the
backlog phases.

### Phase 1 — Transport Contract Skeleton

Add internal `BackendTransport` request/response types and CLI/front-matter
selection diagnostics. Keep runtime defaults unchanged; the concrete
route-dispatch adapter starts in Phase 2 with the interpreter path.

Landed 2026-05-25: `BackendTransport`, `BackendRequest`,
`BackendResponse`, and `BackendTransportKind` are defined in backend SPI. The
CLI accepts `--transport http|in-process`, reads `fullstack.transport` and flat
`transport`, keeps `http` behavior unchanged, and rejects explicit
`in-process` for server/client split modes or runtime execution until Phase 2
adds a real dispatcher.

### Phase 2 — Interpreter In-Process Dispatch

Add a test/dev path that runs frontend-triggered route calls through the
interpreter route registry without binding a socket. This closes the fast
full-stack test use case first.

Landed 2026-05-25: `scalascript.server.InProcessBackendTransport` adapts
`BackendRequest` to the existing `InterpreterHttpHandler`, so it reuses
`Routes.matchRequest`, path capture, query/header/body lifting, middleware, and
`Response` unwrapping without TCP or HTTP wire parsing. Current scope is the
interpreter/test harness path; CLI full-stack execution remains diagnostic-only
until a frontend client adapter selects this transport.

### Phase 3 — Generated Client Adapter

Route `fetchAction`, `fetchTable`, and generated typed client calls through the
transport abstraction where supported. Preserve HTTP behavior for browser/JVM
and distributed modes.

Partial 2026-05-25: generated JVM/Swing `FetchAction` dispatch is implemented
for the same-process runtime path. `SwingRuntime` accepts a `FetchDispatcher`,
and `JvmGen` wires it to the generated JVM route registry so button-driven
`fetchAction` / `fetchActionClear` calls can reuse route matching, middleware,
request body passing, and response status handling without a socket. `fetchTable`
and generated typed clients remain planned.

### Phase 4 — JVM Monolithic Frontend Target

If a JVM-hosted UI target exists, run frontend and backend in the same JVM
process with `InProcessBackendTransport`. Add a runnable example.

In progress 2026-05-25: `ssc run-jvm` now accepts `--transport http|in-process`
and reads `transport:` / `fullstack.transport` front matter through the same
parser as `ssc run`. `http` keeps existing behavior. Swing no longer launches
generated desktop sources through a nested `scala-cli` process:
`ssc run-jvm --frontend swing` now calls `SwingRuntime.run(module)` inside the
current JVM, and `--frontend swing --transport in-process` is accepted as the
monolithic runtime foundation. Swing `fetchAction` dispatch now uses the
generated JVM route registry in the same process. `examples/frontend/swing-fullstack/`
demonstrates that path. Remaining route-client work is `fetchTable`, typed
clients, and deciding whether generated JVM dispatch should reuse the
interpreter `InProcessBackendTransport` class directly.

### Phase 5 — Optional Desktop Bridge Transport

Evaluate Electron-main or other desktop shell bridge transports. This is not
the same as JVM in-process; it is a local IPC/host bridge that avoids public
network sockets while still crossing a process boundary.

## Testing Strategy

- Unit tests for transport selection:
  - `--transport http` selects HTTP;
  - `--transport in-process` selects in-process only for compatible targets;
  - `--server-url` rejects or overrides in-process;
  - server/client split modes reject in-process.
- Route adapter tests for method/path/body/status/header behavior.
- Error tests for missing routes and handler failures matching REST semantics.
- Fast full-stack smoke using interpreter in-process dispatch with no bound TCP
  port.
- Compatibility diagnostics for unsupported frontend/backend pairs.
- Later desktop smoke tests only after a supported desktop in-process or bridge
  target exists.

## Open Questions

- Should the user-facing spelling be `--transport in-process`,
  `--fullstack-transport in-process`, or `--mode monolith`?
- Should front matter use a nested `fullstack.transport` key or a flat
  `transport:` key?
- Should `InProcessBackendTransport` serialize through bytes immediately, or use
  structured values internally while preserving byte-equivalent behavior at the
  edge?
- Which route registry should be canonical for both HTTP and in-process:
  current server routes, interpreter routes, or a new shared route table?
- Is Electron-main bridge transport worth pursuing before a JVM-hosted desktop
  frontend exists?
