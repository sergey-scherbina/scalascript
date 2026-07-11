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
- [ ] **hf-4 streaming/middleware** — fill the current stubs feasibly: `sse`, `cors`, `use`
      (middleware chain), `useGzip`, `maxBodySize`. Tests.
- [ ] **hf-5 default-swap** — make the new plugin the DEFAULT http plugin (service-file swap /
      drop the old module), full conformance + the busi/http examples green, remove the old.

## Non-goals (this spec)

HTTP/2, HTTP/3/QUIC (future); the HTTP CLIENT stays on `java.net.http` (the win is the
server); TLS beyond the existing `tls(cert,key)` surface (can wrap `SSLEngine` later).
