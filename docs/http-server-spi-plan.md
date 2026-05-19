# HTTP/WS Server SPI plan

✅ **All four phases (S1–S4) landed 2026-05-19.**  SPI trait shape
from S1a survived integration against all three backends with zero
modifications.  Per-impl smoke suites all pass.  See the bottom of
this doc for the post-landing status.

Design spec for a pluggable HTTP/WS server backend.  Three
implementations:

- **`Jdk`** (default) — current code: JDK `com.sun.net.httpserver.HttpServer`
  + custom blocking ServerSocket + per-VT proxy demux + custom
  `WebSocket` class.  Zero external runtime deps.
- **`Jetty`** (optional) — Jetty 12.  HTTP + HTTPS + HTTP/2 + WS in one
  managed stack.  ~3 MB external dep.
- **`Netty`** (optional) — Netty 4.  Low-level, highest throughput;
  HTTP/2 + HTTP/3 + custom protocols.  ~4 MB external dep.

User code never picks the backend in source — selection is build-time
or runtime configuration.  The user-facing intrinsics (`route`,
`onWebSocket`, `serve`, `http`, `wsConnect`) stay 100% backend-agnostic.

Audience: future-me when one of Jetty or Netty becomes a real need.
This doc lets us build the SPI *with* the second implementation, not
upfront.

## Why an SPI

Current state (after Phase 3 + Option B + tactical wins):
- Server runtime lives in `runtime-server-jvm`, in real `.scala` files
  inlined into codegen output AND used directly by interpreter.
- `WebSocket` class (server-side) + `WsClient` class (outbound) are
  shared between backends.
- Threading model is uniform: per-VT.
- Both backends still hard-code JDK `HttpServer` + custom WS upgrade
  proxy.

To unlock HTTP/2, HTTP/3, or raw TCP perf without rewriting the runtime,
the HTTP/WS *network layer* needs to be pluggable.  Everything ABOVE
that layer (route registry, middleware chain, Request/Response POJOs,
session/JWT/OAuth shims, JSON, validation, etc.) stays shared — those
were the wins of Phase 1-3.

## Scope

### What's behind the SPI

The thin slice that actually depends on the HTTP/WS library:

- Listening on a port + accepting connections.
- Parsing HTTP request line + headers (or letting the library do it).
- Calling our shared dispatch loop (`HttpDispatchLoop.run`) with the
  parsed request.
- Writing the response (via our shared `ResponseWriter` /
  `StreamResponseWriter`).
- Handling the WS upgrade handshake.
- Per-WS-connection frame parsing + dispatch (or letting the library
  do it and surfacing already-decoded messages).
- TLS termination.

### What stays out of the SPI (shared by all three impls)

- `Request` / `Response` / `StreamResponse` POJOs (runtime-server-common).
- `HttpHelpers` (path parser, query parser, content-type sniffer,
  cookie parser, head reader).
- `HttpDispatchLoop` (per-request envelope: middleware chain,
  `RestValidationError` → 400, result → writer).
- `ResponseWriter`, `StreamResponseWriter`.
- `RequestBuilder` (POJO ← `HttpExchange`-like input).
- `SessionCookie`, `SessionStore`, `Jwt`, `OAuth`, `BasicAuth`, `WebAuthn`.
- `WsFraming`, `WsReassembler`, `WsRateLimiter`, `WsFrameDispatch`,
  `WsHandshake`.
- Route registry (`_routes`, `_middlewares`, `_wsRoutes`), `Routes` /
  `WsRoutes` singletons.
- All user-facing intrinsics (`route`, `serve`, `onWebSocket`, `http`,
  `wsConnect`, `validate`, `requireString`, ...).
- Process-wide slot mgmt (`_wsActiveCount`, `_wsMaxActive`,
  `_wsTryReserve`).

The SPI is intentionally narrow.  Goal: each impl is ~500-1000 LOC of
glue, not a rewrite.

## SPI shape (sketch)

```scala
package scalascript.server.spi

import scalascript.server.{Request, Response, StreamResponse, RestValidationError}

/** A pluggable HTTP/WS network layer.  Implementations wrap a
 *  specific HTTP server library (JDK, Jetty, Netty) and expose a
 *  uniform interface that the shared runtime drives. */
trait HttpServerSpi:

  /** Start listening.  `tls` is `None` for plain HTTP, `Some(cfg)` for
   *  HTTPS.  Blocking — returns when the server stops (or runs in a
   *  background thread, depending on impl). */
  def start(
      port:    Int,
      tls:     Option[TlsConfig],
      dispatch: HttpDispatch
  ): Unit

  /** Stop accepting new connections + close existing ones cleanly. */
  def stop(): Unit

  /** True between successful `start` and `stop`. */
  def isRunning: Boolean

/** What the SPI calls into when a request / WS frame arrives.  The
 *  shared runtime provides one instance of this when starting the
 *  server. */
trait HttpDispatch:

  /** Handle one HTTP request.  Impl translates the library-specific
   *  request type into our POJO `Request`, invokes this, then writes
   *  the returned `Response` (or `StreamResponse`) back through the
   *  library's writer. */
  def handleHttp(req: Request): HttpResult

  /** Handle a WebSocket upgrade request.  Returns `Some(WsHandler)` if
   *  the upgrade should proceed (with the negotiated subprotocol), or
   *  `None` if the upgrade should be rejected (with the given status
   *  and reason).  The impl drives the actual handshake bytes. */
  def handleWsUpgrade(req: Request): WsUpgradeResult

/** Result of an HTTP request — one of three shapes.  `Response` is
 *  the common case; `StreamResponse` opts into chunked / SSE output;
 *  `Reject` short-circuits with a status + body (e.g. 413, 400). */
enum HttpResult:
  case Plain(resp: Response)
  case Stream(resp: StreamResponse)
  case Reject(status: Int, body: String)

/** Result of a WS upgrade request.  `Accept` proceeds with the
 *  negotiated subprotocol + user-payload + per-connection callbacks
 *  set by the route's onWebSocket handler.  `Reject` returns the
 *  given status (404 / 403 / 401 / 503 / 400). */
enum WsUpgradeResult:
  case Accept(subprotocol: String, handler: WsHandler, userPayload: Option[Any], maxMessagesPerSec: Int, onTerminate: () => Unit)
  case Reject(status: Int, reason: String)

/** Per-WS-connection callbacks the SPI invokes as frames arrive.  The
 *  SPI handles raw framing internally (either via its library or
 *  via our shared `WsFraming` helpers); this trait sees only fully-
 *  reassembled text/binary messages and lifecycle events. */
trait WsHandler:
  def onMessage(text: String): Unit
  def onClose(code: Int, reason: String): Unit
  def onError(t: Throwable): Unit
  /** SPI invokes this so the user-facing `ws.send` / `ws.close` /
   *  `ws.ping` methods have a target.  Backed by the SPI's internal
   *  write path. */
  def attach(controls: WsControls): Unit

/** What the user's `ws.send` etc. ultimately call.  The SPI provides
 *  an instance per accepted WS connection. */
trait WsControls:
  def send(text: String): Unit
  def sendBytes(bytes: Array[Byte]): Unit
  def close(code: Int, reason: String): Unit
  def ping(payload: Array[Byte]): Unit
  def isClosed: Boolean

final case class TlsConfig(certPemPath: String, keyPemPath: String)
```

### Why this shape

- `HttpServerSpi` is the *backend* (the library wrapper).
- `HttpDispatch` is the *application* (our shared runtime).
- The two trade `Request` / `Response` POJOs across the boundary; the
  POJOs come from `runtime-server-common`, so both sides already know
  them.
- WS callbacks are message-level, not frame-level — every impl handles
  framing internally (JDK impl via our shared `WsFraming`/`WsReassembler`/
  `WsFrameDispatch`; Jetty + Netty via their own framing).
- `WsControls` is the back-channel the runtime needs to drive
  user-facing `ws.send` etc.  Each impl provides its own.

## Three implementations

### `JdkServerBackend` (default, in `runtime-server-jvm`)

Wraps the current code:
- `com.sun.net.httpserver.HttpServer` on a loopback port for HTTP.
- Blocking `ServerSocket` + per-VT proxy for HTTP/WS demux on the
  public port (replaces the loopback hop for WS upgrades).
- Shared `WebSocket` class for accepted WS connections.

Constructor: `(executor, heartbeats, wsClient)` — passes through to
the existing `WebSocket` class.

Refactor cost: ~3-4 days.  The current code is already structured
into `ProxyRuntime` + `WebSocketRuntime` + the runtime-server-common
shared helpers; this step extracts an `HttpServerSpi` interface and
re-points the existing classes at it.

Distribution: zero new deps.  ssc stays a fat jar.

### `JettyServerBackend` (optional, new module `runtime-server-jvm-jetty`)

New sbt module `runtime-server-jvm-jetty` (or similar).  Wraps Jetty
12 — `org.eclipse.jetty:jetty-server`, `jetty-servlet`,
`jetty-websocket-jetty-api`.  ~3 MB jar.

Implementation:
- `JettyServer` for the HTTP listener + TLS termination.
- `WebSocketUpgradeFilter` for the WS upgrade path.
- A custom Jetty `Handler` calls into our `HttpDispatch.handleHttp`
  with the translated `Request` POJO.
- A custom Jetty WS `EndpointConfig` calls into `handleWsUpgrade` and
  wires `WsHandler` to Jetty's `Session`.

What we gain:
- HTTP/2 + HTTP/1.1 on the same port (Jetty ALPN auto-negotiation).
- Production-grade TLS (Conscrypt / BoringSSL integration).
- Mature WS implementation (frame masking, extension negotiation,
  permessage-deflate compression if we enable it).

What we lose:
- Self-contained ssc jar.  Generated scripts that opt into Jetty pull
  Jetty from Maven Central.

Selection: per-deployment.  Default ssc still uses JDK backend; user
opts into Jetty via `--server jetty` flag or via `setHttpServerBackend(...)`
intrinsic.

Build cost: ~1-2 weeks including conformance tests against the new
backend.

### `NettyServerBackend` (optional, new module `runtime-server-jvm-netty`)

New sbt module wrapping Netty 4 — `io.netty:netty-codec-http`,
`netty-codec-http2`, `netty-handler-ssl`.  ~4 MB jar.

Implementation:
- Netty `ServerBootstrap` with a `ChannelInitializer` setting up
  `HttpServerCodec` + `HttpObjectAggregator` + a custom handler.
- The custom handler calls `HttpDispatch.handleHttp` with the
  translated `Request` POJO.
- For WS: Netty's `WebSocketServerProtocolHandler` does the upgrade,
  our handler wraps Netty's `Channel` writes for `WsControls`.

What we gain over Jetty:
- Lower latency, higher throughput per core (Netty's event-loop model
  is more efficient than Jetty's thread-per-request).
- HTTP/3 support (Netty incubator QUIC).
- Custom protocol support (raw TCP, MQTT, etc.) — useful if SSC ever
  serves non-HTTP.

What we lose vs Jetty:
- Lower-level API; impl code is longer (~1500 LOC vs Jetty's ~800).
- Different operational profile — Netty performance tuning requires
  knowing event-loop sizing, direct-buffer pools, etc.

Build cost: ~2-3 weeks (Netty's API surface is bigger).

## Backend selection mechanism

Three options:

### (a) Compile-time via sbt module choice

User picks at build time which backend module their app depends on:

```sbt
libraryDependencies += "io.scalascript" %% "scalascript-runtime-server-jvm-jetty" % VERSION
```

That module provides an `HttpServerSpi` via `META-INF/services/` so
`ServiceLoader` picks it up at app startup.  Default ssc bundles
only `runtime-server-jvm` (the JDK impl).

Pros: zero-config for default users; Jetty/Netty users opt in
explicitly via dep.
Cons: Doesn't help users who want runtime selection (e.g. config
flag to switch between dev vs prod backend).

### (b) Runtime selection via `setHttpServerBackend(name)`

User code calls a top-level intrinsic before `serve(port)`:

```scala
setHttpServerBackend("jetty")
serve(8080)
```

Pros: Runtime choice, no rebuild.
Cons: Requires all three backends on the classpath even if only one
is used.  Big distribution.

### (c) Hybrid — `ServiceLoader` discovery + fallback to JDK default

`ServiceLoader[HttpServerSpi]` picks the first registered impl; if
none, fall back to `JdkServerBackend`.  Adding the Jetty module to
the classpath automatically switches over.

Pros: Best of both — explicit dep choice, zero config, multiple impls
possible.
Cons: Subtle when wrong dep on classpath; need clear error message
when two impls collide.

**Recommended**: (c) for users; (a) for ssc's own distribution
(default ssc bundles only the JDK impl).

## Migration plan

Order matters — don't introduce the SPI without the second impl
landing alongside, otherwise the SPI is unvalidated.

### Phase S1 — Define the SPI traits, refactor JDK impl behind it (~3 days)

- New sbt module `runtime-server-jvm-spi` (or extend existing
  `runtime-server-common` with SPI traits).
- Define `HttpServerSpi`, `HttpDispatch`, `WsHandler`, `WsControls`,
  `TlsConfig`, etc.
- Refactor the current `ProxyRuntime` + `WebSocketRuntime` to be a
  concrete `JdkServerBackend extends HttpServerSpi`.
- `serve(port)` / `setHttpServerBackend` intrinsic resolves the SPI
  via `ServiceLoader`.
- All conformance tests pass — JDK backend is the only impl, behaviour
  unchanged.

This phase is the riskiest — it's where the SPI shape gets validated
against a real impl.  Iterate on the trait until the existing code
fits cleanly.

### Phase S2 — Build `JettyServerBackend` (~1-2 weeks)

- New module `runtime-server-jvm-jetty` depending on
  `runtime-server-jvm-spi` + Jetty 12.
- Implements `HttpServerSpi` using Jetty.
- Conformance test suite runs against both impls — same `.ssc` source,
  same expected output.
- Decision point: if Jetty's API forced a change to the SPI traits,
  refactor JDK impl to match, re-run conformance.

### Phase S3 — Build `NettyServerBackend` (~2-3 weeks)

Same shape as S2 but with Netty.  If Netty forces yet another SPI
change, iterate.

### Phase S4 — User-facing wiring (~3 days)

- `setHttpServerBackend(name: String)` intrinsic.
- Documentation: how to pick a backend (sbt dep vs runtime intrinsic).
- Examples + benchmarks.
- Decision on what ssc's default distribution bundles (probably just
  JDK; Jetty/Netty as separate downloads / sbt opt-in).

Total: ~5-6 weeks of focused work for all three impls, or ~3 weeks
if we ship just default + Jetty initially.

## Trade-offs at a glance

| | Default (JDK) | Jetty | Netty |
| --- | --- | --- | --- |
| External deps | 0 MB | ~3 MB | ~4 MB |
| HTTP/2 | No | Yes | Yes |
| HTTP/3 | No | No | Yes (incubator) |
| TLS | JDK SSLEngine | Jetty / Conscrypt | Netty / Conscrypt / OpenSSL |
| WS extensions | None | permessage-deflate, etc. | permessage-deflate, etc. |
| Throughput / core | Adequate | Good | Best |
| Memory per conn | Low (per-VT) | Moderate | Lowest (event loop) |
| Implementation LOC | ~800 (existing) | ~800 new | ~1500 new |
| Tuning surface | Tiny | Moderate (acceptor / selector threads) | Large (event-loop sizing, direct buffers) |
| Operational maturity | OK for small/medium loads | Production-proven | Production-proven |

## Open decisions

These need a call before/during S1:

1. **SPI module home.**  New `runtime-server-jvm-spi` or extend
   `runtime-server-common`?  `runtime-server-common` is currently
   "pure helpers, no HttpExchange refs".  The SPI introduces an
   `HttpExchange`-like role, so a separate module is probably cleaner.

2. **Selection mechanism for the user.**  Pure `ServiceLoader` or
   intrinsic `setHttpServerBackend(name)` or both?  Recommendation in
   "Backend selection" above is hybrid.

3. **Per-server-instance backend choice?**  Currently `serve(port)` is
   process-global.  Could one ssc app run two servers on different
   backends (e.g. Jetty for public, JDK for internal admin)?  Probably
   not needed; ship the simple version (one backend per process).

4. **WS handler shape — message-level vs frame-level.**  The sketch
   above gives the handler text strings (post-reassembly).  Jetty +
   Netty do reassembly internally so that's natural for them.  Our JDK
   impl currently uses `WsFraming` + `WsReassembler` + `WsFrameDispatch`
   internally; it would need to ALSO surface messages at the
   reassembled level (small refactor — `WsFrameDispatch.handle`
   already exposes the assembled message via `onDeliver`).

5. **TLS shape.**  `TlsConfig(certPemPath, keyPemPath)` is enough for
   JDK; Jetty + Netty support full keystores + truststores + client
   auth.  Either expose a richer config or keep it simple and let
   advanced users configure the backend directly.

6. **HTTP/2 ergonomics.**  Should `ws.send(text)` work over HTTP/2
   WebTransport when the user opted into Jetty / Netty?  Probably no
   — HTTP/2 WebTransport is a different protocol.  Limit WS to
   HTTP/1.1 upgrade for now.

7. **Backend-specific extensions.**  Jetty has `permessage-deflate`,
   Netty has HTTP/3.  Surface these via per-backend extension
   methods (cast `HttpServerSpi` to the concrete impl) or generic
   capability flags?  Recommendation: keep the SPI capability-flag-free
   for now; users who need Jetty-specific features add them via cast
   or via the underlying library directly.

## Status (post-S4, 2026-05-19)

✅ **All four phases shipped in one session.**

| Phase | What landed | Tests |
| --- | --- | --- |
| S1a | `runtime-server-spi` sbt module + trait definitions | 9 / 9 |
| S1 scaffold | 3 backend modules + ServiceLoader META-INF/services + stub impls | 6 / 6 (cross-module discovery) |
| S1b | `JdkServerBackend` fully implemented; interpreter `WebServer.start` wired via SPI | 18 / 18 WS + 8 / 8 JvmGen |
| S2 | `JettyServerBackend` (Jetty 12) — HTTP, WS upgrade, TLS, Reject path | 6 / 6 |
| S3 | `NettyServerBackend` (Netty 4) — same shape | 6 / 6 |
| S4 | `HttpServerBackends` registry (hybrid ServiceLoader + by-name) + `setHttpServerBackend(name)` intrinsic | 9 / 9 SPI |

**SPI trait shape: zero changes** between S1a and S4.  The design
loop's biggest worry — "single impl validates nothing, the trait will
need to bend when a real second impl shows up" — was right to flag
but didn't bite.  Three real impls (one custom blocking-IO, one
Jetty event-driven, one Netty event-loop) all consumed the same
`HttpServerSpi` / `HttpHandler` / `WsListener` / `WsControls` traits
without a single modification.

**What's wired today:**
- Default ssc distribution bundles `runtime-server-jvm` (the JDK
  backend).  Generated scripts + the interpreter use it via the SPI.
- Jetty and Netty are optional sbt modules — add them via
  `dependsOn(runtimeServerJvmJetty)` / `dependsOn(runtimeServerJvmNetty)`.
- User picks the backend via `setHttpServerBackend("jetty")` /
  `setHttpServerBackend("netty")` before calling `serve(port)`.
  Unset (the default) = first-found wins from ServiceLoader.

**What's NOT done:**
- **S1c** — codegen `JvmGen.serveRuntime` still emits ProxyRuntime as
  inlined Scala that constructs `WsProxy` directly.  Not yet routed
  through the SPI.  Generated scripts therefore can't pick Jetty /
  Netty via `setHttpServerBackend(name)` from inside the script;
  the call works at the interpreter level only.
- Permessage-deflate WS compression (Jetty / Netty support it; we
  don't enable it).
- HTTP/2 server push (Jetty / Netty support; not surfaced through
  the SPI).
- HTTP/3 (Netty incubator; not enabled).
- Benchmark suite comparing the three backends.

## Out of scope

- HTTP/2 client.  `http(url)` outbound goes through JDK
  `java.net.http.HttpClient`, which already speaks HTTP/2.  No SPI
  needed there.
- Outbound WS client.  Same — `wsConnect(url)` uses JDK
  `java.net.http.WebSocket`.  Already standardised.
- gRPC, REST framework features (OpenAPI codegen, etc.).
- Per-route backend choice (route X via Jetty, route Y via JDK).
  Conceivable but adds a router-over-routers layer; not worth it.
