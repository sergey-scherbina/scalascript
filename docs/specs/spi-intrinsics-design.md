# Backend SPI intrinsics — pre-flight design notes

> Pre-flight design questions to settle before Stage 5+/B (`std.http`
> extraction) starts.  Companion to [`docs/specs/backend-spi.md`](backend-spi.md)
> §8 and [`docs/specs/spi-followups-plan.md`](spi-followups-plan.md).
>
> ## Resolved (2026-05-17)
>
> Four of the five holes are now decided; one is parked.
>
> | # | Hole | Resolution |
> |---|------|-----------|
> | 1.1 | Plumbing approach | **Hybrid synthetic AST-marker** — Normalize rewrites `Term.Apply(Name(qn))` → `ir.ExternCall`; Denormalize emits as `Term.Apply(Name("__intrinsic_<qn>"))`; JvmGen/JsGen catch the prefix in existing `Term.Apply` emit case and consult `backend.intrinsics(qn)`. |
> | 1.2 | `EmitContext` shape | **Minimum** — `out: StringBuilder`, `freshName(prefix): String`, `emitArg(IrExpr): String`.  Extend as needs surface. |
> | 1.3 | First proof intrinsic | **`println(s)`** — migrates the existing hardcoded path on compiled backends without regression.  Interpreter already on `NativeImpl`. |
> | 1.4 | Workspace | **Worktree** on a long-lived feature branch per AGENTS.md big-feature workflow. |
> | 2 | Sync vs async handler semantics | **B + direct-syntax + implicit lift.**  Handler signature is `Request => Async[Response]`.  Existing call sites continue to work via Scala 3 `given Conversion[Response, Async[Response]]` — zero migration cost.  User-facing convenience: a **do-notation direct-syntax block** (see [Direct-syntax defaults](#direct-syntax-defaults)) lets handler bodies read like sync code while the type system honestly carries `Async`. |
>
> ## Direct-syntax defaults
>
> **Full design promoted to [`docs/direct-syntax.md`](../direct-syntax.md)**
> (2026-05-18) — covers all seven decisions DS-1…DS-7 including
> control flow, lambda boundaries, and bind-marker syntax, plus the
> formal desugaring spec and implementation phases.  The summary
> below mirrors the four core decisions from the May 17 lock-in for
> readers who land here from the SPI followups thread; consult the
> standalone document for canonical text.
>
> A direct-syntax block desugars to a `for { ... } yield ...` over a
> single monad.  Each line `x = expr` becomes `x <- expr`; each bare
> effectful line becomes `_ <- expr`; the trailing expression is the
> yield clause.  Defaults locked 2026-05-17:
>
> | # | Question | Resolution |
> |---|----------|-----------|
> | DS-1 | How does the typer infer the monad for a direct block? | **Type-directed** — inferred from the expected return type (e.g. handler typed `Request => Async[Response]` ⇒ block is in `Async`).  An explicit `direct[M] { ... }` marker is the fallback when context is ambiguous. |
> | DS-2 | Pure values inside a direct block — when do they auto-lift? | **`val x = expr` is a pure local binding (no bind).  `x = expr` is a monadic bind**; if `expr` is pure, it auto-lifts via `Monad[M].pure`.  Re-assignment to a pre-declared `var` keeps existing Scala semantics (the `var` heuristic disambiguates from monadic bind). |
> | DS-3 | Bare statements — bind-and-discard or regular statement? | **Type-directed** — if the expression's type is `M[*]`, the line becomes `_ <- expr`.  Pure-typed bare expressions stay regular statements (e.g. `assert(x > 0)`). |
> | DS-7 | Error handling — `MonadError` or thrown exceptions? | **Monadic** — `Async.fail(...)` / `Async.recover(...)` for monadic failure; `Async[A]` is a `MonadError`.  Thrown exceptions are NOT auto-wrapped into monadic failure (avoids the two-fault-model trap). |
>
> ### Effect rows
>
> Multi-effect computations (e.g. a block that uses both `Async` and
> `Random`) represent the row as a **Scala 3 union type**:
> `Async | Random | Logger`.  Runtime still uses the universal
> `Computation[A]` Free-monad from v0.8 — the row exists only at the
> type level for `CapabilityCheck` and type inference; no new runtime
> machinery is needed.  Surface type-level row tracking is a v2
> extension; the v1 path keeps `Async[A]` as the master effect type
> that subsumes other effects via stacked handlers.
>
> ### Open follow-ups (do NOT block 5+/B)
>
> Edge cases that surface during typer extension; address as they
> appear:
>
> - **Control flow inside direct blocks** — `if`/`match`/`while`
>   desugar to nested for-comprehensions; pure branches auto-lift to
>   the monad of the surrounding block.
> - **`for`-loops** — desugar to `Async.traverse_` / `Async.foldM`
>   instead of plain `foreach`.  Stdlib helpers needed:
>   `Async.traverse`, `Async.parTraverse`, `Async.traverse_`,
>   `Async.replicateM_`.
> - **Lambdas inside collection operations** — a lambda whose
>   expected return type is `M[*]` is itself a direct block (so
>   `urls.parTraverse { url => resp = fetch(url); parse(resp) }`
>   works recursively).
> - **Error messages** — must be specific: "expected `Async[Int]`,
>   got `Int` — pure values auto-lift only when bound; did you mean
>   `val x = …` (pure) or `x = expr` (monadic bind)?".
> - **`for { … } yield` continues to work** — direct syntax is sugar
>   over the same monad; both coexist.
> - **`return` / non-local exits** — disallowed inside direct blocks;
>   use `Async.fail` for early failure.
> - **Type-level effect-row tracking** — deferred to v2; `Async[A]`
>   as master type covers v1 needs.
> | 3 | `std.ws` server/client split | **Split** — packages `std.ws.server` / `std.ws.client` with `std.ws` re-export.  Two feature flags `Feature.WebSocketsServer` / `Feature.WebSocketsClient`.  Browser-SPA / CLI-only-client backends get the right capability bucket. |
> | 4 | `HostCallback` end-to-end | **Build now** before 5+/D.  HostDispatcher in `core/` + stub subprocess backend + conformance round-trip via `println`.  ~1 day; first out-of-process backend (.NET / WASM) starts with a working callback. |
> | 5 | Partial feature coverage | **Hierarchical sub-features** — ~3 sub-flags per platform package (e.g. `HttpServer` baseline + `HttpServerStreaming` + `HttpServerMultipart`; `WebSocketsServer` baseline + `WebSocketsServerExtras` + `WebSocketsClient`).  Backend declares the subset it implements; error messages stay actionable. |
>
> ## Additional prerequisites surfaced 2026-05-17
>
> A subsequent sanity check on the source-of-truth code surfaced four
> follow-up questions specific to web-stack extraction (`std.http`
> Stage 5+/B).  Resolutions:
>
> | # | Question | Resolution |
> |---|----------|-----------|
> | Б-1 | `extern def` parser + typer support | **Prerequisite to 5+/B; tracked as Stage 5+/A.5.**  Parser accepts an `extern` modifier on `def`; typer registers the symbol with its declared type (no body required).  Without this, intrinsic calls cannot be type-checked, which breaks both implicit `Conversion[Response, Async[Response]]` lift (no expected type to drive it) and the type-directed monad inference of direct syntax.  Existing intrinsics (`println`, `nowMillis`) work today through name-based registration without type info; that path stays for v1 console-style primitives, `extern def` is the typed surface for everything that takes user callbacks. |
> | Б-2 | Where intrinsics ship runtime helpers (`class WebSocket`, `_wsEncodeText`, the proxy selector loop, …) | **Add `Backend.runtimePreamble: String` method to the SPI.**  Each backend assembles its preamble from its own private state — typically per-intrinsic strings concatenated.  Core prepends it before user code at emit time.  Minimum SPI surface; no changes to the four `IntrinsicImpl` variants.  Per-intrinsic dead-code elimination is a follow-up (today every Feature.HttpServer backend bundles the full runtime regardless of usage). |
> | Б-3 | Direct syntax — implement before or after 5+/B `std.http`? | **After.**  Stage 5+/B ships with implicit `Conversion[A, Async[A]]` handling trivial handlers and explicit `for { x <- … } yield …` for compound bodies.  Direct-syntax do-notation (DS-1…DS-7) is sugar over the same monad — adds zero expressivity, only ergonomics.  Implementing it requires ~1-2 weeks of typer work and is best informed by real `std.http` / `std.ws` usage before committing.  Tracked as Stage 6+/A (or a separate v1.7 milestone). |
> | Б-4 | Backend needs `preludeFiles` hook for `std.http` declarations? | **No.**  `extern def` declarations live in regular `std/*.ssc` files at the repo root (same as `std/foldable-traversable.ssc` etc.) and are loaded via user imports or the implicit prelude mechanism.  Once Б-1 lands the parser understands `extern def`, the file `std/http.ssc` is just a normal source file.  Backend SPI does not need a new prelude-contribution hook. |
>
> ### Updated stage order
>
> ```
> Stage 5+/A.4 — per-call-site dispatch (hybrid AST-marker; hole #1)
> Stage 5+/A.5 — extern def parser + typer + Backend.runtimePreamble (Б-1 + Б-2)
> Stage 5+/B   — std.http extraction (no direct syntax; implicit lift + for-yield)
> Stage 5+/D   — std.ws / auth / fs / crypto extraction
> Stage 6+/C   — HostCallback dispatcher (hole #4)
> Stage 6+/A   — direct-syntax do-notation implementation (Б-3, parked here
>                so std.* extractions inform the real usage patterns)
> ```
>
> Total estimated work to a functional std.http MVP: 5+/A.4 (~1-2 days)
> - 5+/A.5 (~1-2 days) + 5+/B (~3-5 days) = ~1.5 weeks.  Direct syntax
> (6+/A) and HostCallback (6+/C) add ~2-3 weeks each but don't block
> std.http or std.ws.
>
> ---
>
> Sections below describe the original holes and trade-offs leading to
> these resolutions.  Kept for archeology so future contributors don't
> re-litigate without new evidence.
>
> Stage 5+/A is partially landed: `Backend.intrinsics` API exists,
> `nowMillis` flows through all three backends, `println` migrated
> from hardcoded `nativeP` to a `NativeImpl` intrinsic on the
> interpreter (compiled backends still emit `println` inline).
> The remaining work is **per-call-site dispatch** through `ExternCall`
> for compiled backends — that's hole #1 below and prerequisite to
> 5+/B.
>
> Each section: the open question, viable options, recommended path,
> what stays open after.

## Why this doc exists

`docs/specs/backend-spi.md` §8 lays out the intrinsic mechanism end-to-end:
`extern def` → `ExternCall` IR node → `Backend.intrinsics:
Map[QualifiedName, IntrinsicImpl]` → one of four `IntrinsicImpl`
variants (see [State of play](#state-of-play) below).

Five design holes are likely to bite during 5+/B–D in ways that would
force a redesign mid-extraction.  Resolving them up front costs an
hour of writing and saves an iteration of churn later.

Holes, ordered by blast radius:

1. **[Per-call-site dispatch vs prelude aliasing](#1-per-call-site-dispatch-vs-prelude-aliasing)** — current compiled-backend implementation is a workaround that won't scale to HTTP-shaped intrinsics with handler closures.
2. **[Sync vs async handler semantics](#2-sync-vs-async-handler-semantics)** — shape of every server-side intrinsic.
3. **[Server vs client split in `std.ws`](#3-server-vs-client-split-in-stdws)** — naming + package layout.
4. **[`HostCallback` end-to-end](#4-hostcallback-end-to-end)** — out-of-process backends (.NET, WASM) need this before they can ship anything HTTP/WS-shaped.
5. **[Partial feature coverage](#5-partial-feature-coverage)** — graduation path for early backends that can't implement the full `std.http` surface day one.

## State of play

Verified against `main` (commit `464245a`+):

### Landed

- **`Backend.intrinsics: Map[QualifiedName, IntrinsicImpl]`** API on all
  three backends (`backend-spi/Backend.scala:16`,
  `JvmBackend.scala:20`, `JsBackend.scala:19`,
  `InterpreterBackend.scala:21`).
- **Four `IntrinsicImpl` variants** (`backend-spi/IntrinsicImpl.scala`):
  - `InlineCode((List[IrExpr], EmitContext) => TargetCode)` — per-call-site
    target source generation (compiled backends).
  - `RuntimeCall(targetSymbol)` — alias to a runtime helper the backend
    ships in its preamble.
  - `HostCallback(name)` — out-of-process backend calls back into core
    over the stdio wire.  **No dispatcher yet.**
  - `NativeImpl((NativeContext, List[Any]) => Any)` — in-process
    function the interpreter calls directly.  `NativeContext` carries
    the per-session `out` / `err` `PrintStream`s.
- **`NativeContext` trait** — runtime hooks an in-process native
  intrinsic may consult (`out`, `err`).
- **`ExternCall(name, args)` IR node** in `ir/Ir.scala:136` — derived
  `ReadWriter` so it round-trips through the SPI wire protocol.
- **`Feature` enum platform flags**: `ConsoleIO`, `HttpServer`,
  `WebSockets`, `Auth`, `FileSystem`, `Crypto`, `Database`.
  (`backend-spi/Feature.scala`).
- **Three intrinsics actually populated** in backends:
  - `nowMillis` — `NativeImpl` (interpreter), `RuntimeCall("java.lang.System.currentTimeMillis")` (JVM), `RuntimeCall("Date.now")` (JS).  Additive demo, no pre-existing path.
  - `println` / `print` — `NativeImpl` only.  Hardcoded `nativeP("println")` / `nativeP("print")` blocks **removed from `Interpreter.initBuiltins`**; intrinsic-installed via `Interpreter.installNativeIntrinsics` at session start.  JvmGen / JsGen still hardcode their `println` emission — only the interpreter side has migrated.

### Not landed

- **Parser**: no `extern def` modifier.  Adding one is Stage 5+/C.1.
- **Normalize**: `Normalize.scala:25` says "Stage 5 rewrites `extern
  def` call sites to `ExternCall`" but no code actually does that yet.
  Today's `nowMillis` / `println` work because their names hit the
  intrinsic map directly via name-based installation, not via IR
  lowering.
- **Per-call-site emit-time intrinsic consultation** in compiled
  backends: `JvmBackend.intrinsicPrelude` and `JsBackend`'s equivalent
  prepend a single `def name() = target()` (or `const name = target`)
  alias.  Works for **zero-argument intrinsics that take no closures**
  — fine for `nowMillis`.  **Does NOT work** for `route(...) { handler
  }` / `onWebSocket(...) { handler }` shapes; the alias can't capture
  a closure parameter typed in user code.  Real per-call-site emit is
  pending.
- **`HostCallback` dispatcher** in core — no out-of-process round-trip
  exists for any intrinsic.
- **`std.http` / `std.ws` / `std.auth` / `std.fs` / `std.crypto`
  prelude packages** — none exist as `.ssc` files.  Hardcoded
  `nativeP("route")` / `nativeP("serve")` / `nativeP("onWebSocket")` /
  `nativeP("onWebSocketAuth")` blocks still in
  `Interpreter.initBuiltins`; HTTP / WS emission still inline in
  JvmGen / JsGen as `Term.Apply(Term.Name("onWebSocket"), …)`
  pattern matches.

### Implication

The SPI is **API-complete** but **dispatch-incomplete**.  5+/B
(`std.http` extraction) cannot proceed without per-call-site IR
dispatch — the current prelude-alias workaround is structurally too
weak for handler-taking intrinsics.  That's the first hole below; it's
prerequisite work, not a deferrable design question.

---

## 1. Per-call-site dispatch vs prelude aliasing

### The question

`JvmBackend.intrinsicPrelude` and the equivalent in `JsBackend` today
turn every `RuntimeCall(target)` intrinsic into a one-line alias
prepended to the emitted source:

```scala
// JVM
def nowMillis() = java.lang.System.currentTimeMillis()
// JS
const nowMillis = () => Date.now();
```

User code `nowMillis()` then resolves to the alias via ordinary name
lookup.  Crude but valid for zero-argument intrinsics that don't take
closures.

This breaks the moment we get to:

```scala
route("GET", "/users") { req => Response(...) }
onWebSocket("/chat") { ws => ... }
```

A prelude alias `def route(method, path, handler) = ???` cannot
forward to the platform API without knowing the handler's runtime
shape — the JVM backend wants a `(Request => Response)` Scala value,
the JS backend wants a JS callback, and the wire-level signatures
differ.  The whole point of `InlineCode` is to **emit different target
source per call site** depending on backend.

### Options

**A. Implement per-call-site `InlineCode` dispatch in JvmGen / JsGen.**
Normalize lowers `route(method, path, handler)` →
`ExternCall(QualifiedName("std.http.route"), [method, path, handler])`.
JvmGen's emit pass, on hitting an `ExternCall`, looks up
`backend.intrinsics(qn)`; if `InlineCode`, invokes `emit(args, ctx)`
and inlines the result.  `RuntimeCall` keeps the alias-prepend path
for trivial intrinsics.

- Pro: it's the spec's design (§8).
- Pro: zero workaround leftover after — `nowMillis` migrates to
    `InlineCode` later if its overhead matters; today's alias still
    works.
- Con: requires JvmGen and JsGen to consume `ir.NormalizedModule`
    (or at least its `Extern` nodes) directly, not just the
    `Denormalize`d AST view they use today.  ~1 iteration of plumbing
    per backend.

**B. Defer `InlineCode` until `std.http` actually needs it; ship `std.http`
intrinsics as `RuntimeCall` with hand-written multi-arg runtime helpers.**
Each backend ships a `_runtimeRoute(method, path, handler)`-style helper
in its preamble; the intrinsic table maps `std.http.route` → that
helper.

- Pro: smaller plumbing change — no IR consumption in codegens.
- Con: the helpers ARE the inlined target source; just packaged as
    runtime functions instead of emit callbacks.  Lossy when call
    sites benefit from inlining (constant folding `method` / `path`
    at compile time).
- Con: helper layer adds an indirection on every HTTP call — small
    but real overhead for hot paths.

**C. Move existing JvmGen / JsGen HTTP emission verbatim into
`InlineCode` callbacks, mechanically.**
The current `Term.Apply(Term.Name("onWebSocket"), …)` match arm in
JvmGen.scala lines ~3320-3380 becomes an `InlineCode.emit(args, ctx)`
closure registered under `std.ws.onWebSocket`.  Same target source;
just dispatched via the intrinsic map instead of pattern matching.

- Pro: zero behaviour change.  Mechanical refactor.  Conformance
    suite is the regression net.
- Pro: keeps existing call-site inlining and constant folding.
- Con: still requires the codegens to walk IR (option A's plumbing
    issue) — there's no shortcut.

### Recommended

**Option A** plumbing, **Option C** as the migration strategy for
each existing inline-emit case.

Concrete sequence for 5+/B (smallest unit of work that proves the
path):

1. Add `EmitContext` (in `ir/`) with whatever surrounding state JvmGen
   / JsGen need to keep their existing emit logic working (symbol
   names, indentation, the in-progress output buffer).  Mostly
   already in JvmGen's internal threading; lift it to a typed
   record.
2. Add a `Normalize`-pass step that rewrites bare-name calls whose
   target is in the runtime's `intrinsics` map → `ExternCall`.
   (Before `extern def` parser modifier ships — name-based
   recognition is fine as the bootstrap path.)
3. Add JvmGen + JsGen emit cases for `ExternCall` that consult
   `backend.intrinsics(qn)`: `InlineCode` invokes `emit`,
   `RuntimeCall` falls back to today's alias.  `HostCallback` errors
   for now (Stage 6+/C).
4. Migrate **one** intrinsic (recommend `println`) to `InlineCode` on
   JvmGen and JsGen as the proof point that compiled backends route
   through the dispatcher.  Interpreter already uses `NativeImpl`;
   conformance proves no regression.

Once that lands, 5+/B `std.http` extraction can move HTTP emission
into `InlineCode` callbacks one intrinsic at a time without further
plumbing changes.

What stays open:

- **`EmitContext` shape** — minimum viable is just the output buffer
  - a fresh-name supplier; everything else can be threaded through
  closures.  Pin exact fields at Step 1.
- **Round-trip stability of `ExternCall` through Denormalize** —
  JvmGen and JsGen currently process the `Denormalize`d AST view, not
  the IR directly.  Option A as scoped above keeps `Denormalize`
  ignorant of `ExternCall`; codegens get the IR module passed
  alongside the AST until full IR consumption migrates over.  That's
  a Stage 5+/A.4-ish follow-up, not blocking 5+/B.

### Concrete consequence for 5+/B

`std.http.route` ships as an `InlineCode` intrinsic on JvmGen + JsGen,
`NativeImpl` on Interpreter.  JvmGen's existing emit logic for
`Term.Apply(Term.Name("route"), …)` moves verbatim into the `emit`
closure.  Conformance tests pass unchanged.

---

## 2. Sync vs async handler semantics

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

## 3. Server vs client split in `std.ws`

### The question

`docs/specs/backend-spi.md` §8 ships an example: `std.ws.{accept, send,
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

## 4. `HostCallback` end-to-end

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

## 5. Partial feature coverage

### The question

`docs/specs/backend-spi.md` §8 closing paragraph:

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

| # | Hole | Recommended | Status today | Cost to decide now | Cost to defer |
|---|------|------------|--------------|--------------------|---------------|
| 1 | Per-call-site dispatch | Plumb `ExternCall` consumption in JvmGen/JsGen + migrate one intrinsic to `InlineCode` | Prelude-alias workaround only; doesn't scale to handler-taking intrinsics | ~1 iteration (plumbing) | **Blocks 5+/B entirely** |
| 2 | Sync vs async handlers | Sync + Async-effect for cancellation/streaming | Sync today; no cancellation/streaming on any backend | 30 min (write up) | ~1 iteration (redo 5+/B handler shape) |
| 3 | `std.ws` server/client split | Two packages + `std.ws` re-export, two feature flags | Single `Feature.WebSockets` flag today | 10 min (naming) | ~half iteration (move files when 5+/D lands) |
| 4 | `HostCallback` end-to-end | Build before 5+/D; prove with `println` + `nowMillis` round-trip | ADT variant declared, dispatcher missing | ~1 iteration | unbounded (.NET/WASM stay without HTTP indefinitely) |
| 5 | Partial feature coverage | Hierarchical sub-features, ~3 per package | All-or-nothing per §8 (strict) | 30 min (enum layout) | ~quarter iteration per blocked backend |

**Total pre-flight investment:** hole #1 is ~1 iteration of code plus
holes #2-5 at ~1 hour of writing.  Saves ~2-3 iterations of redo
across 5+/B-D and the first out-of-process backend MVP.

## Next steps

If recommendations stand:

1. Land this doc.
2. Implement **hole #1** as Stage 5+/A.4: per-call-site `ExternCall`
   dispatch in JvmGen + JsGen, with `println` migrated to `InlineCode`
   as the proof point.  Prerequisite for 5+/B.
3. Update `docs/specs/backend-spi.md` §8 to reflect: sync-handler decision
   (hole #2), `std.ws.server` / `std.ws.client` split (hole #3),
   the hierarchical `Feature` enum (hole #5), and a one-liner
   pointing at this doc.
4. Update `docs/specs/spi-followups-plan.md`: promote `HostCallback`
   plumbing (Stage 6+/C, hole #4) to land before 5+/D; record 5+/A.4
   plumbing as the new prerequisite to 5+/B.
5. Then 5+/B (`std.http`) can proceed on a settled base — moving
   today's hardcoded HTTP emission into `InlineCode` callbacks one
   intrinsic at a time.

If any recommendation needs redirect — flip the section and the
table; rest of the plan is independent.
