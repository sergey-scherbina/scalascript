# v2-http-fast — super-optimal from-scratch HTTP/WS plugin for the v2 JVM lane

**Status:** spec / in progress. Author: lucky-perch, 2026-07-11, at Sergiy's direction
("сделай для v2 jvm новый супер оптимальный http/ws плагин … сделай новый v2-http-ws по
умолчанию вместо старого … проверь thread-safety v2 VM под параллельными запросами").

## Goal

A new v2 native plugin — a from-scratch, zero-dependency, high-throughput HTTP/1.1 +
WebSocket server for the v2 JVM runtime — that replaces the current
`com.sun.net.httpserver`-based plugin AS THE DEFAULT, adds the features the current one
stubs out (WebSocket, path params, query parsing, streaming/SSE, middleware), and is safe
under real concurrency.

## Current state (measured 2026-07-11)

`v2/runtime/std/http-plugin` uses the JDK `com.sun.net.httpserver.HttpServer` +
`Executors.newVirtualThreadPerTaskExecutor()`. Limitations:
- Reference-impl server: per-exchange object allocation, non-zero-copy header parsing, no
  keep-alive tuning, no HTTP/2.
- **Router is exact string equality on method+path** — NO path params (`/users/:id`), NO
  query parsing, `params` isn't even a Request field.
- **WebSocket / SSE / middleware are throwing stubs** (`onWebSocket`, `wsConnect`, `sse`,
  `cors`, `use`, `mount`, `useGzip`, `maxBodySize`, …) — `HttpNativePlugin.scala:282-286`.

## Intrinsic surface to MATCH (drop-in compat)

- **Request** DataV (9 fields, `registerFields("Request", …)`): `method, path, headers
  (MapV lowercased), body (StrV), form, files, cookies, session, json`. Field access
  resolves via `V2PluginRegistry.lookupFieldNames("Request", 9)`.
- **Response** DataV (3 fields): `status (IntV), headers (MapV), body (StrV)`; a handler may
  also return a bare `StrV` (→ 200 text/plain).
- Server intrinsics: `route(method, path[, handler])` (curried), `serve(port)` (blocks),
  `serveAsync(port)`, `stop()`. Client + `Response.*` constructors + decorators as today
  (keep delegating client to `java.net.http.HttpClient`; the win is server + WS).
- Handler invocation bridge: `NativePluginContext.invoke(clos, [requestValue])` →
  `Runtime.run(clos.code, extend(clos.env, args))`. Handler returns a Value → HTTP response.
- Registered via the SPI: `trait NativePlugin { id; install(ctx) }`, discovered by
  ServiceLoader through `META-INF/services/ssc.plugin.NativePlugin`. Ownership claims forbid
  double-registration → the new plugin REPLACES the old (swap the service file / drop the old
  module from the CLI classpath).

## WebSocket surface to MATCH (from the v1 ws/http plugins)

- `onWebSocket(path[, origins, protocols, maxConn, maxMsgRate]) { ws => … }`,
  `onWebSocketAuth(path, authFn)(handler)`, `wsConnect(url[, headers, protocols]) { ws => … }`
  (client), `sse(req) { stream => … }`, `WsRoom()` (broadcast, CopyOnWriteArrayList).
- The `ws` value: `InstanceV("WebSocket", { send, sendBytes, close, ping, onMessage, onClose,
  onPong, recv, isClosed, request, id, subprotocol, user })`. Message callbacks receive
  `StrV(msg)`, invoked via `invoke(cb, [StrV(msg)])`.

## Design — the server engine (from-scratch, zero-dep, JVM 21)

- **Transport:** blocking `ServerSocket`/`Socket` + **virtual-thread-per-connection** with
  blocking reads. JVM 21 makes thread-per-connection cheap to millions of conns; no selector
  event-loop complexity, lowest latency. (Chose `java.net.ServerSocket` over
  `ServerSocketChannel`: identical under vthreads — both park on blocking accept/read — but
  `Socket.setSoTimeout` gives a real per-connection idle timeout, which a blocking
  `SocketChannel` read ignores.)
- **HTTP/1.1 parser:** byte-level, incremental, off a reused per-connection read buffer.
  Parse request line + headers without premature String alloc; keep-alive + pipelining +
  chunked transfer + Content-Length bodies; configurable max header/body sizes.
- **Router:** compiled route table with path segments — literal, `:param` (captured into the
  Request), and `*` wildcard; query string parsed into params. (Request keeps the 9-field
  compat shape; params/query surfaced via `form`/a params map to stay compat.)
- **Response:** pre-encoded common status/header bytes, gather writes (header+body), direct
  ByteBuffers; keep-alive reuse.
- **WebSocket (RFC 6455):** upgrade on the HTTP connection (`Sec-WebSocket-Accept` = base64(
  SHA-1(key + GUID)) — SHA-1 via the crypto plugin / JDK MessageDigest), frame parse
  (opcode/mask/fragmentation/close), per-connection read vthread + a write queue with
  backpressure; ping/pong heartbeat; the `WebSocket` value surface above.
- **Guards:** idle/read timeouts, max header/body, connection cap, write-queue overflow →
  force-close, graceful shutdown.

## THREAD-SAFETY (verified + fixed) — Sergiy asked explicitly

`Runtime.run`/`callClos`/`Emit.app` are stateless → fully re-entrant. `V2EffectContext` is
ThreadLocal (safe). `V2PluginRegistry` maps are frozen-after-`install` (install completes
before `serve()` — `register` throws after serve), read-only during serving → safe. TWO
GENUINE RACES (the current server already runs handlers concurrently with no lock, so these
already exist and are merely tolerated):

1. **`Emit.globalsRef`** (mutable.HashMap in the ASM/bytecode lane) — `registerGlobal` does
   in-place `m(name) = v` on first `@`-global / `global.reg` access, while other handler
   threads call `getOrElse` → HashMap resize/read corruption.
2. **VM-lane `globals`** (`Runtime.scala:605` mutable.HashMap) — same `@`-cell auto-create
   write at `:679/:1061/:1318` during concurrent handler execution (narrow window: first-ever
   access of an `@`-global concurrently).

**Fix (phase 1):** make both globals maps concurrent-safe (`java.util.concurrent.
ConcurrentHashMap` via a scala `mutable.Map` view, or `scala.collection.concurrent.TrieMap`)
so concurrent get + first-touch put is race-free. Reads stay lock-free (perf-neutral —
verified by the bytecode/VM benches). Gate with a concurrency stress test (N vthreads each
first-touching distinct + shared `@`-globals) that reliably crashes on `mutable.HashMap` and
passes after the fix. Reactive-plugin state (#3) and shared program cells (#4) are
program-level; documented as "don't mutate shared Signals/cells from concurrent handlers
without your own synchronization" (out of scope for the VM fix).

## Phases (each: worktree, tests, bench, conformance, push when green)

- [x] **hf-1 vm-thread-safety** — DONE. Both globals maps (`Compiler.compileWithGlobals`'s
      VM-lane map + `Emit.globalsRef` in the ASM/artifact lane) are now
      `scala.collection.concurrent.TrieMap` — lock-free reads, race-free concurrent
      first-touch. `GlobalsConcurrencyTest` (32 vthreads × 500 distinct `@`-global
      first-touches + a hot shared key) fails on the old `mutable.HashMap` (lost updates) and
      passes on TrieMap. Benches unregressed: float-loop 22.5×, list-fold 1.9×, float-fold
      1.87× (bytecode vs VM). Benefits the current server too. FOUNDATION.
- [x] **hf-2 http-core** — DONE. New module `v2/runtime/std/http-fast-plugin`
      (`v2NativeHttpFastPlugin`, aggregated for CI but NOT yet on the CLI classpath). Engine:
      `FastHttpServer` (blocking `ServerSocket` + vthread-per-connection), `HttpProtocol`
      (hand-written HTTP/1.1 parser: request line, headers, Content-Length + chunked bodies,
      `Expect: 100-continue`, header/body caps, keep-alive), `Router[H]` (literal/`:param`/`*`
      with specificity ordering + 404-vs-405), `NioNativeHttpServerHost` (routes → 9-field
      Request DataV — query+path-params in `form`, cookies parsed — → invoke → Response),
      `HttpFastNativePlugin` (same intrinsic surface + `id="50-http"`; `maxBodySize` now real).
      26 tests green (RouterTest, HttpProtocolTest, FastHttpServerIntegrationTest: GET/POST/
      query/500/keep-alive/concurrency/body-limit). Transport bench vs raw `com.sun` server
      (same vthread executor + client): **1.46× req/s** (21.8k→31.9k), p50 2.59→1.79ms, p99
      7.79→4.18ms, 0 errors (`HttpFastBench` via `Test/runMain`).
- [x] **hf-3 websocket** — DONE. `WebSocketFrames` (RFC 6455 codec: accept-key SHA-1/base64,
      masked frame read, unmasked write, 7/16/64-bit lengths, close/ping/pong), `WsConnection`
      (per-conn read loop: fragmentation reassembly, auto-pong, close handshake, thread-safe
      writes, teardown hook), upgrade wired into `FastHttpServer` (101 handshake + subprotocol
      negotiation, `setSoTimeout(0)` for live conns). Bridge: `WsChannel` unifies server
      ([[WsConnection]]) + client (`java.net.http.WebSocket`); the `ws` value = `DataV(
      "WebSocket",[id])` with tagged methods send/sendBytes/close/ping/onMessage/onClose/
      onPong/isClosed/request/subprotocol/user; `onWebSocket`/`onWebSocketAuth`/`wsConnect`/
      `WsRoom` (broadcast). 10 tests: 9 engine (echo, fragmentation, 200 KB, binary, close both
      ways, 20-conn broadcast, RFC vector) + a ServiceLoader install smoke test asserting the
      full surface registers. VM-level `.ssc` end-to-end validated in hf-5 conformance (the
      tagged-method dispatch mirrors the proven reactive-plugin idiom).
- [x] **hf-4 streaming/middleware** — DONE. `use(mw)` middleware chain (first middleware to
      return a `Response` short-circuits, else falls through to the route); `cors([origin,
      methods, headers])` (adds CORS headers + answers OPTIONS preflight 204); `useGzip()`
      (gzips a ≥256 B body when the client accepts gzip); `sse(req){ stream => }` +
      `streamResponse([ct]){ stream => }` via a small engine streaming hook (`RawResponse.
      stream`: status+headers with no Content-Length + `Connection: close`, then the handler
      writes the body over time). `HttpStream` value (an [[SseWriter]]) with tagged methods
      send/write/comment/close/isClosed; multi-line SSE data split into `data:` lines. Tests:
      an engine streaming test + the install smoke test extended over the new intrinsics +
      `HttpStream` methods (37 module tests). Still stubbed (honest): `uploadSpoolThreshold`,
      `uploadDir`, `mount` (file-upload spooling + static mounting — future).
- [x] **hf-5 default-swap** — DONE. The CLI now bundles `v2NativeHttpFastPlugin` (id 50-http)
      in place of `v2NativeHttpPlugin`; the old module (`v2/runtime/std/http-plugin`) is
      **removed** and its client + Response-builder test coverage ported to the fast module
      (`HttpFastClientResponseTest`). 40 module tests green. End-to-end validated through the
      real v2 VM via `bin/ssc run --native`: HTTP (route, path `:params` → `user=42`, query
      `q=hello world`, POST echo, 404), WebSocket (`onWebSocket` echo + `wsConnect` client →
      `client got: echo:hello-ws` — the tagged-method dispatch through the VM), and hf-4
      (cors header `Some(*)`, `use` middleware 403 short-circuit, `sse` events). A new
      `HttpFastVmIntegrationTest` locks route+serve+dispatch through the ServiceLoader-
      registered intrinsics into CI.

      **Lane note (important).** The fast plugin is the v2 *native* HTTP/WS server, used by
      the `--native` runner (`RunNativeV2` → `NativePluginHost.loadAll`, no v1). The `--v2`
      FrontendBridge runner (`RunV2` → `PluginBridge`) still overrides `serve`/`route` with the
      legacy v1 `scalascript.server.WebServer` (`PluginBridge.registerWebServer`) — that is a
      separate migration seam, out of scope here. So "default v2 http server" = the `--native`
      lane. Full `tests/conformance` runs via `--v2`, so it exercises the v1 WebServer, not this
      plugin; the `--native` e2e programs above are the authoritative validation.

## hf-6 — the fast engine also backs the `--v2` lane (HttpServerSpi backend)

The `--v2` FrontendBridge lane serves http through the mature v1 `scalascript.server.WebServer`
framework (literate `.ssc` rendering, routing, static files, TLS, session, health, OpenAPI, WS)
on a **pluggable `HttpServerSpi` transport backend** (default `JdkServerBackend` = com.sun;
`jetty`/`netty` are opt-in). To give `--v2` the fast NIO/vthread transport **without**
reimplementing the framework, add a new backend on the same engine — the framework stays above
the SPI.

- [x] **hf-6a engine extraction** — DONE. The value-agnostic engine (`FastHttpServer`,
      `HttpProtocol`/`HttpReader`, `Router`, `WsConnection`, `WebSocketFrames`) moved to its own
      dependency-free module `httpFastEngine` (`v1/runtime/http-server/fast-engine`, package
      unchanged `ssc.plugin.httpfast`). Both `v2NativeHttpFastPlugin` and the new v1 backend
      depend on it; the backend never pulls in the plugin's `NativePlugin` ServiceLoader entry.
      Added for the backend: `FastHttpServer.start(ServerSocket)` (TLS/supplied socket) +
      `WsConnection.recv()`/`id`/`remoteAddress`.
- [x] **hf-6b FastServerBackend** — DONE. `runtimeServerJvmFast`
      (`v1/runtime/http-server/jvm-fast`): `FastServerBackend extends HttpServerSpi` (name
      `"fast"`), mirroring the Jetty backend — builds the POJO `Request(method,path,query,
      headers,body,cookies)` directly, maps `HttpResult` (PlainResp incl. `setSession` via the
      shared `SessionCookie.toSetCookie`; StreamResp → the engine stream hook; Reject), and WS
      `onWsUpgrade` → `Accept(subprotocol,listener)` wired to a `WsConnection` + `FastWsControls`.
      TLS via `TlsContextBuilder` + `SSLServerSocket`. Registered via
      `META-INF/services/scalascript.server.spi.HttpServerSpi`.
- [x] **hf-6c default in --v2** — DONE. The CLI depends on `runtimeServerJvmFast`; the v2 lane
      selects it with `HttpServerBackends.setBackend("fast")` so `ssc run --v2 <server>` serves
      on the fast engine (framework unchanged). `--native` keeps the native plugin lane;
      `--v1`/default keep the JDK backend unless they `setHttpServerBackend("fast")`.
- [x] **hf-6d framework request/session parity** — DONE. The fast SPI backend
      feed raw method/path/query/headers/body through the same shared
      `RequestBuilder` semantics as the JDK backend: urlencoded and multipart
      form fields, generic cookies, signed session, bearer/basic auth and JWT
      hooks. Handler `Response.headers` and `Response.setSession` must reach the
      engine unchanged. A real-socket pairing regression posts a form, observes
      `Set-Cookie`, and authenticates the next request with that cookie. The
      fast engine remains value-agnostic and does not duplicate application or
      cookie policy. `RequestBuilder.parseRaw` now owns the transport-neutral
      body/header half and is shared by fast/JDK semantics; multipart temp files
      are removed after handler completion. A real-socket test covers
      urlencoded form, explicit cookie round-trip, signed session round-trip,
      bearer and basic auth. Verification: common 150/150, fast 5/5,
      interpreter-server 58/58, `rest-validate` INT/JS/JVM, assembled busi Vault
      11-step/restart/leakage check, and canonical fast-backend Chromium 6/6 in
      1.9 minutes.

## hf-8 — standard-tier ownership and release cutover

The hf-5 module replacement must be reflected in every compiler-free
distribution surface. The standard image and `build-jvm` artifact use explicit
JAR allowlists; neither may retain the removed
`scalascript-v2-native-http-plugin`, and both must include the replacement
`scalascript-v2-native-http-fast-plugin` plus its value-agnostic
`scalascript-http-fast-engine` dependency.

Behavior:

- [x] `installBin` stages the fast native provider and engine in both tools and
      standard layouts, with no retired native HTTP provider JAR.
- [x] `NativeJvmArtifact` includes the same fast provider/engine pair when an
      executable artifact is assembled.
- [x] Core-dependency and native-provider gates classify the fast artifact
      names, retain forbidden parser/compiler dependency scans, and require the
      provider ServiceLoader entry without treating the engine as a plugin.
- [x] Native-entry replaces the obsolete `useGzip` feature-unavailable
      assertion with a positive zero-error fast-provider check.
- [x] Native provider, core dependency, slim, JRE module, standard tier,
      build-jvm, native-entry, and fresh affected conformance gates pass.

Decisions:

- **Stage the engine explicitly** — chosen because the fast provider has a real
  runtime class edge to the extracted transport module. Rejected: relying on
  tools-only transitive availability (breaks the closed standard image and
  standalone artifacts).
- **Keep the engine non-provider** — chosen because it is value-agnostic and
  has no `NativePlugin` ServiceLoader entry. Rejected: duplicating plugin
  discovery/ownership in the engine.
- **Positive middleware smoke** — chosen because `useGzip` landed in hf-4 and
  now exits successfully. Rejected: preserving a negative assertion for a
  deliberately available feature.

Results (2026-07-11, `d503cf856`):

- `installBin` stages 111 tools JARs and 31 standard dependency JARs. Both
  layouts contain `scalascript-v2-native-http-fast-plugin` and
  `scalascript-http-fast-engine`; neither contains the retired provider.
- The strict dependency gate classifies 17 roots, 65 static edges, and 11
  reflective plugin dependencies across all 31 staged JARs, with zero
  outside-closure artifacts, parser migrations, or violations. The engine is
  scanned as a feature root and has no `NativePlugin` service entry.
- Native VM and direct ASM both execute the positive `useGzip()` fixture with
  empty output and stderr. A compiler-free `build-jvm` artifact records the
  fast provider, provider implementation, and engine in deterministic metadata.
- Native provider, core dependency, standard, slim, JRE module, native-entry,
  build-jvm smoke/release, and fresh `v2-*` conformance (11/11) pass. The quick
  consolidated self-hosted-core gate reports `release.ready=true`; the slim
  image is 32 total JARs including `ssc.jar`, 6,617 classes, and 30,727,574
  bytes with compiler tools absent.

### Follow-ups (resolved 2026-07-11)

- **Cross-lane Request parity.** The native plugin used to stuff path params + query into
  `form`; now it exposes `params` (path params) and `query` (query string) as their own fields
  and `form` = POST `x-www-form-urlencoded` body — matching the v1 WebServer Request. An ssc
  program using `req.params`/`req.query`/`req.form` now runs identically on `--native` and
  `--v2` (verified: both print `params=7 query=hi` / `form=ada` for the same source).
- **Standard-tier image cutover.** `standardJarPrefixes` still whitelisted the removed
  `scalascript-v2-native-http-plugin_`, so the compiler-free standard image shipped no http
  provider. Swapped to `scalascript-v2-native-http-fast-plugin_` + `scalascript-http-fast-engine_`
  (standard image 29→31 jars). The `--v2` HttpServerSpi backend stays tools-only.

## Non-goals (this spec)

HTTP/2, HTTP/3/QUIC (future); the HTTP CLIENT stays on `java.net.http` (the win is the
server); TLS beyond the existing `tls(cert,key)` surface (can wrap `SSLEngine` later).
