# Backend SPI intrinsics — pre-flight design notes

> Pre-flight design questions to settle before Stage 5+/B (`std.http`
> extraction) starts.  Companion to [`docs/backend-spi.md`](backend-spi.md)
> §8 and [`docs/spi-followups-plan.md`](spi-followups-plan.md).
>
> Each section: the open question, viable options, recommended path,
> what stays open after.  None of these block 5+/A (Console.println as
> proof point) — they all surface the moment 5+/B touches a real
> platform package.

## Why this doc exists

`docs/backend-spi.md` §8 lays out the intrinsic mechanism end-to-end:
`extern def` → `ExternCall` IR node → `Backend.intrinsics:
Map[QualifiedName, IntrinsicImpl]` → `InlineCode` / `RuntimeCall` /
`HostCallback`.  The API ships; no intrinsic has yet flowed through it
end-to-end.

Four design holes are likely to bite during 5+/B–D in ways that would
force a redesign mid-extraction.  Resolving them up front costs an hour
of writing and saves an iteration of churn later.

Holes, ordered by blast radius:

1. **[Sync vs async handler semantics](#1-sync-vs-async-handler-semantics)** — shape of every server-side intrinsic.
2. **[Server vs client split in `std.ws`](#2-server-vs-client-split-in-stdws)** — naming + package layout.
3. **[`HostCallback` end-to-end](#3-hostcallback-end-to-end)** — out-of-process backends (.NET, WASM) need this before they can ship anything HTTP/WS-shaped.
4. **[Partial feature coverage](#4-partial-feature-coverage)** — graduation path for early backends that can't implement the full `std.http` surface day one.

---

## 1. Sync vs async handler semantics

### The question

Today every backend presents a synchronous handler signature:

```scala
route("GET", "/users") { req: Request => Response(...) }
onWebSocket("/chat") { ws: WebSocket => ... }
```

JVM `HttpServer` is sync per request (one virtual thread blocks until
the handler returns); Node `http.createServer` is async (handlers
return `Promise<Response>` or write to `res` from a callback); WASM /
.NET sit somewhere in between.

The sync surface works today because every existing backend either
(a) runs on Loom virtual threads (JvmGen) or (b) hides the async
machinery inside a single-thread event loop dispatched from sync-shaped
glue (interpreter, Node).  When we add **cancellation, timeouts, or
backpressure** — features any real production HTTP/WS stack wants —
we need to pick a representation.

### Options

**A. Stay sync everywhere; cancellation via algebraic effects.**
Handler stays `Request => Response`.  Cancellation surfaces as an
`Async.cancelled` check inside the handler body (algebraic effect from
v0.8).  Backends suspend the handler virtual-thread (Loom) or its
single-thread continuation (interpreter / Node-via-worker) when they
detect peer disconnect.

  - Pro: zero API change; existing user code untouched.
  - Pro: same Async-effect surface for HTTP, WS, file I/O, future DB.
  - Con: Node still needs `worker_threads` for real blocking I/O —
    same blocker as v1.3 stage 2 (`runAsyncParallel` on Node).
  - Con: backpressure on streaming responses (v1.5 Tier 4 #11) needs
    a `Stream` effect on top — not just a sync return.

**B. Make handlers return `Future[Response]` (or `Async[Response]`).**
Explicit async surface: handler builds a computation, runtime drives it.

  - Pro: maps cleanly onto Node / WASM / .NET native async.
  - Pro: backpressure is just "the Future hasn't completed yet".
  - Con: breaks every existing `route("GET", …) { req => Response(…) }`
    call site.  Migration: implicit lift from sync `Response` to
    `Async(Response)` — same trick Scala 3 has for `Future.successful`.
  - Con: two-handler-shape problem when WS handlers run a `while
    !ws.isClosed` loop — that's procedural, not Async-shaped.

**C. Hybrid: sync handler, side-channel for cancellation/backpressure.**
Handler still `Request => Response`.  Cancellation flows via a token
the handler can poll (`req.cancelled: Boolean` / `req.onCancel { … }`).
Streaming via `Response.stream(write => …)` where `write` blocks /
suspends naturally.

  - Pro: API stays familiar; new primitives are opt-in.
  - Con: two ways to express "give up early" — `req.cancelled` check
    vs Async-effect's `cancelled` — risk of divergence.
  - Con: not a unified story for HTTP + WS + DB + FS.

### Recommended

**Option A** — keep sync; layer cancellation / timeouts / streaming
through the existing `Async` effect.  Rationale:

- v0.8 + v1.3 already invested in `Async` as the cancellation /
  suspension abstraction.  Reusing it for HTTP handlers is one
  mechanism, not two.
- The "Node needs worker_threads" objection applies regardless of
  which option we pick — it's a Node runtime gap (v1.3 stage 2), not
  a handler-shape choice.
- Migration from today's sync handlers is **zero changes** in user
  code; cancellation-awareness is opt-in (`Async.cancelled` check
  inside the body).

What stays open:

- **Streaming responses** (v1.5 Tier 4 #11) need a `Response.stream`
  shape regardless of A/B/C.  Define it as a sync-side primitive
  whose `write(chunk)` suspends via `Async` when the peer is slow.
- **Per-request deadline** as a request field (`req.deadline: Option[Long]`)
  vs as an Async-effect `withTimeout` wrapping — pick at 5+/B start.
  Default: deadline as field, `withTimeout` as combinator on top.

### Concrete consequence for `std.http`

```scala
package std.http

case class Request(method: String, path: String, ...,
                   deadline: Option[Long] = None)
case class Response(status: Int, headers: Map[String, String],
                    body: ResponseBody)

sealed trait ResponseBody
object ResponseBody:
  case class Bytes(value: Array[Byte])               extends ResponseBody
  case class Stream(write: (Array[Byte] => Unit) => Unit) extends ResponseBody

extern def route(method: String, path: String,
                 handler: Request => Response): Unit
extern def serve(port: Int): Unit
extern def stop(): Unit
```

`Stream` is the streaming variant whose `write` callback can suspend
through Async (Loom on JVM, microtask on Node post-`worker_threads`).
No async wrapping on `route` itself.

---

## 2. Server vs client split in `std.ws`

### The question

`docs/backend-spi.md` §8 ships an example: `std.ws.{accept, send,
recv, close}` — those are server-side primitives.  v1.5 Tier 3
introduces `connectWebSocket(url) { ws => … }` — client-side.  Same
package or two packages?

### Options

**A. Single package `std.ws`** with both sides.

```scala
package std.ws
extern def onWebSocket(path: String, handler: WebSocket => Unit): Unit  // server
extern def connectWebSocket(url: String, handler: WebSocket => Unit): Unit  // client
// WebSocket type shared; send/recv/close shared on both sides.
```

  - Pro: `WebSocket` value is the same shape on both sides — `send`,
    `recv`, `close`, `onMessage`, `onClose`, `id`, `subprotocol`,
    `user`.  All already in place after v1.0.
  - Pro: one capability flag (`Feature.WebSockets`) gates both.
  - Con: a backend with WS-server-only (or client-only) capability
    has to declare partial coverage — see [hole #4](#4-partial-feature-coverage).

**B. Two packages `std.ws.server` / `std.ws.client`** with shared `WebSocket` in `std.ws`.

```scala
package std.ws  // shared types
case class WebSocket(...)

package std.ws.server
extern def onWebSocket(path: String, handler: WebSocket => Unit): Unit

package std.ws.client
extern def connectWebSocket(url: String, handler: WebSocket => Unit): Unit
```

  - Pro: separate feature flags (`WebSocketsServer`, `WebSocketsClient`)
    — a browser-target backend has WS *client* only, never server.
    Symmetrically a CLI tool might want client only, no server.
  - Con: longer qualified names; users `[onWebSocket](std.ws.server)`
    is uglier than `[onWebSocket](std.ws)`.

### Recommended

**Option B** with one twist: the **default user-facing import is
`std.ws`** which re-exports `onWebSocket` / `connectWebSocket` /
`WebSocket` from the appropriate sub-package.  Backends declare
`Feature.WebSocketsServer` and/or `Feature.WebSocketsClient`
independently.

Rationale: the browser-SPA backend will only ever do client-side; the
"backend with WS server but no client" case (interpreter pre-v1.5)
already exists.  Forcing them into one capability flag wastes the
asymmetry information.

Naming alignment with HTTP: same shape.  `Feature.HttpServer` and
(future) `Feature.HttpClient` split symmetrically when v1.5 Tier 2
lands.

What stays open:

- Should `WebSocket` carry server-only fields (`request: Request`,
  `user: Option[Any]`) on client connections?  Likely no — split into
  `ServerWebSocket` (extends `WebSocket`) for the server side; client
  uses the base.  Land alongside v1.5 Tier 3.

---

## 3. `HostCallback` end-to-end

### The question

`IntrinsicImpl` ships three variants:

```scala
sealed trait IntrinsicImpl
case class InlineCode(emit: ...)         extends IntrinsicImpl
case class RuntimeCall(targetSymbol: String) extends IntrinsicImpl
case class HostCallback(name: String)    extends IntrinsicImpl
```

`InlineCode` and `RuntimeCall` are clear — both are in-process: backend
emits target source, runs.  `HostCallback` is meant for **out-of-process
backends** (`.NET` subprocess, `WASM` host) — the subprocess receives
an `ExternCall` IR node it can't execute itself, calls back into core,
core dispatches the named callback (HTTP request, FS read, etc), result
returns over the wire.

That dispatcher does not yet exist.  No subprocess has yet tried to
call back into core for any intrinsic.

### Why it matters

`.NET` and `WASM` backends are forecast as out-of-process for v0.1 of
the SPI (in-process needs JVM-classpath which only JVM-target backends
have).  Without `HostCallback`, every platform call from those
backends has to be reimplemented in their language — duplicating the
JVM/Node runtimes inside a `.NET` plugin and a WASM plugin.

If we land `std.http` / `std.ws` extraction (5+/B, 5+/D) before
`HostCallback` works, the .NET/WASM backends will be stuck with
"can't do HTTP" indefinitely.

### Options

**A. Build `HostCallback` plumbing now, prove with a trivial intrinsic.**
Pick the cheapest possible: `std.io.println(s)` or `std.sys.nowMillis()`.
Plumbing pieces:

  - Wire format for callback request / response (json or msgpack,
    same as existing stdio protocol).
  - Dispatcher in core: `HostDispatcher` that receives
    `{call: "std.io.println", args: ["hello"]}` and runs the matching
    in-process implementation (JVM call to `System.out`).
  - Subprocess-side: when a `.NET` backend hits an `ExternCall` whose
    intrinsic is `HostCallback`, it sends the request and awaits the
    response.
  - Round-trip test: a stub subprocess backend calls `println` and
    sees output in core's stdout.

Estimated: ~1 iteration (1 day) for the trivial intrinsic; subsequent
intrinsics reuse the same plumbing.

**B. Defer until first out-of-process backend ships.**
Land `std.http` / `std.ws` extraction (5+/B–D) JVM/Node/Interpreter
only; `HostCallback` waits until the first `.NET` or `WASM` MVP
appears.

  - Pro: less speculative work.
  - Con: when .NET/WASM lands, it ships with no HTTP/WS — degraded
    backend that can compile pure-Scala code only.  Bad first
    impression.
  - Con: schema for the wire format remains unproven; first .NET MVP
    discovers the gaps the hard way.

### Recommended

**Option A**, scheduled as Stage 6+/C in the existing followups plan,
**before** Stage 5+/D (`std.ws / auth / fs / crypto`).  Reason: 5+/B
`std.http` extraction is the natural moment to design the wire format
for a non-trivial intrinsic — request bodies, response status codes,
header maps — and `HostCallback`'s round-trip is small enough not to
balloon the scope.

Concrete plumbing pieces, in order:

1. Wire `HostDispatcher` in `core/`, accept `{method, args}` JSON over
   the existing stdio frame protocol.
2. Implement two trivial host callbacks: `std.io.println` and
   `std.sys.nowMillis`.  Both have known in-process implementations
   (`InterpreterBackend.intrinsics`); the `HostCallback` variant just
   reuses them.
3. Stub `.NET`-style subprocess backend (or `WASM` mock — pick one)
   that does nothing but echo "hello world" through `println`-via-
   `HostCallback`.  Conformance test verifies the round-trip.

What stays open:

- **Streaming intrinsics** (a WS `recv` loop, an HTTP streaming
  response) need bidirectional / multi-message framing.  Today's
  stdio protocol is request/response sync.  Add a session-ID +
  out-of-band callbacks shape — same complexity as `gRPC`-style
  bidirectional streams.  Reserved for Stage 6+/D.

---

## 4. Partial feature coverage

### The question

`docs/backend-spi.md` §8 closing paragraph:

> A backend that declares a feature MUST provide intrinsics for the
> whole package; partial coverage is a bug, not a degraded mode.

Strict all-or-nothing.  A backend ships `Feature.HttpServer` iff it
implements every `extern def` in `std.http`.

This bites early-stage backends.  Examples:

- `.NET` MVP can implement `serve / route / Request / Response`
  trivially via `HttpListener`, but `Response.stream` (v1.5 Tier 4 #11)
  needs `HttpResponse.Body.Stream` which has a different shape from
  the JVM equivalent.  Initial port skips streaming → MVP doesn't
  qualify for `Feature.HttpServer`.
- `WASM` backend wraps WASI's HTTP-proxy proposal which has no
  multipart upload primitive — `Request.files` unimplementable
  short-term.

### Options

**A. Stay strict all-or-nothing.**
Backend missing one intrinsic → can't declare the feature → can't
compile programs that use the package.

  - Pro: zero ambiguity; user knows exactly what works.
  - Con: blocks every early-stage backend until it reaches full
    parity.  Realistically — that's months of work for .NET/WASM.

**B. Per-intrinsic capability check.**
Drop feature flags; each `extern def` is independently checked.  A
backend's `Backend.intrinsics` map *is* the capability declaration.

  - Pro: maximum granularity; backends ship MVP packages incrementally.
  - Con: capability-check error messages get noisy ("missing
    `std.http.Response.stream`, missing `std.http.req.files`, …" —
    20 lines per `std.http`-using program).
  - Con: language-feature flags (`PatternMatching`, etc.) still
    need the all-or-nothing semantic; mixing two models is ugly.

**C. Hierarchical features.**

```scala
enum Feature:
  case HttpServer            // serve + route + Request + Response (bytes body)
  case HttpServerStreaming   // adds Response.stream
  case HttpServerMultipart   // adds Request.files
  case HttpServerCookies     // adds req.cookies / Set-Cookie
  // ... per-capability subdivisions
```

Backends declare the subset they implement.  Capability check is
still set-membership.

  - Pro: clearer error messages ("backend X does not declare
    `HttpServerStreaming` — needed for `Response.stream(...)` at
    line 42").
  - Pro: roadmap for an early backend is explicit — "ship
    `HttpServer` first, add `HttpServerStreaming` next".
  - Con: more flag values to maintain (~3-5 per package).
  - Con: where to draw the lines is judgment — exposes design
    decisions to bikeshed.

### Recommended

**Option C** with a small starter set per package, ~3 sub-features
each.  Bias toward few-but-meaningful subdivisions:

```scala
enum Feature:
  // language ...
  case HttpServer              // baseline: serve + route + Request + Response (bytes body)
  case HttpServerStreaming     // + Response.stream / Request.body as stream
  case HttpServerMultipart     // + Request.files / multipart parsing

  case WebSocketsServer        // baseline: onWebSocket + send/recv/close + onMessage/onClose
  case WebSocketsServerExtras  // + per-route cap + rate limit + auth hook + id/subprotocol
  case WebSocketsClient        // + connectWebSocket (v1.5 Tier 3)

  case HttpClient              // baseline: httpGet / httpPost
  case HttpClientStreaming     // + bodyStream for SSE
  // ...
```

Rationale: the v1.0 baseline + v1.5 streaming/client + v1.6 actors
distribution natural-cluster well enough that 3 sub-features per
package buys 80% of the granularity we'd want without combinatorial
explosion.

What stays open:

- **Migration path for the existing 3 backends** (interpreter / JvmGen /
  JsGen).  They all currently declare `Feature.HttpServer` /
  `WebSockets` implicitly via inlined codegen.  When 5+/B switches
  to intrinsics, they each gain `HttpServer` + `HttpServerMultipart`
  (already there) and lose `HttpServerStreaming` (none of the three
  implements streaming responses yet).  Verify v1.5 Tier 4 #11
  doesn't get pushed earlier as a result.

---

## Summary table

| Hole | Recommended | Cost to decide now | Cost to defer |
|------|------------|--------------------|---------------|
| Sync vs async handlers | Sync + Async-effect for cancellation/streaming | 30 min (write up) | ~1 iteration (redo 5+/B handler shape) |
| `std.ws` server/client split | Two packages + `std.ws` re-export, two feature flags | 10 min (naming) | ~half iteration (move files when 5+/D lands) |
| `HostCallback` end-to-end | Build before 5+/D; prove with `println` + `nowMillis` | ~1 iteration | unbounded (.NET/WASM stay without HTTP indefinitely) |
| Partial feature coverage | Hierarchical sub-features, ~3 per package | 30 min (enum layout) | ~quarter iteration per blocked backend |

**Total pre-flight investment:** ~1.5 iterations to settle all four.
Saves ~2-3 iterations of redo across the 5+/B-D extractions and the
first out-of-process backend MVP.

## Next steps

If recommendations stand:

1. Land this doc.
2. Update `docs/backend-spi.md` §8 to reflect: sync-handler decision,
   `std.ws.server` / `std.ws.client` split, the hierarchical
   `Feature` enum, and a one-liner pointing at this doc.
3. Update `docs/spi-followups-plan.md` Stage 6+/C row — promote
   `HostCallback` plumbing ahead of Stage 5+/D.
4. Then 5+/A (Console.println proof point) → 5+/B (`std.http`) can
   land on a settled base.

If any recommendation needs redirect — flip the section and the
table; rest of the plan is independent.
