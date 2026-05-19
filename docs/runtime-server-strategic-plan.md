# Runtime-server strategic plan — next phase

After Phase 2a–2g (`PLAN-runtime-consolidation.md`), the HTTP/WS
runtime is in a "consolidated but not unified" state.  This doc lays
out the **next-tier** architectural moves that target the structural
costs Phase 2 couldn't remove.  Each option is independently shippable
and reversible up to a point; the sections describe what each costs
and unlocks.  Concrete sequencing and decision points are at the
bottom.

Audience: future-me when the next "продолжай" lands and we need to
decide whether to keep dripping dedup or take a bigger swing.

## Status quo (2026-05-19)

- `runtime-server-common` packages **29** Scala files inlined into the
  codegen output and used directly by `backend-interpreter`.
- Phase 2a–2g pulled out every pure / table-driven helper plus the
  per-request HTTP envelope (`HttpDispatchLoop`) and per-frame WS
  dispatch (`WsFrameDispatch`).  Net dedup across the two JVM
  backends: ~−700 LOC vs the pre-Phase-2 baseline.
- `JvmGen.serveRuntime` is a `"""|..."""` triple-quoted string that
  spans three `String` halves (Part1 / Part1b / Part2) because the
  combined source exceeds JDK's 64 KB string-literal limit.  Current
  size: **~4 180 lines** of generated Scala.
- `backend-interpreter/src/main/scala/scalascript/server/` has
  10 files (`WebServer`, `WsProxy`, `WsConnection`, `WsClientSession`,
  `BlockingWsSession`, `TlsProxy`, `Routes`, `WsRoutes`, +2 helpers).
- JS backend (`backend-js/src/main/scala/scalascript/codegen/JsRuntimeMcp.scala`
  etc.) is intrinsically different (Node `http`/`ws`); not in scope.

## Core problems

### P1 — `JvmGen.serveRuntime` is a string template

`backend-jvm/src/main/scala/scalascript/codegen/JvmGen.scala` line
~2772 onwards.  Three `"""|..."""` halves, manual `|` prefix on every
line, hand-spliced together because of the 64 KB string-literal limit.
Errors only surface at scala-cli compile time on the user's machine.
IDE has no awareness, refactors are find/replace, tests are
conformance-driven (slow, indirect).

The Phase 2a–2g dedup is **all about working around this** — we
extracted helpers into `runtime-server-common` so the codegen can
inline them as real types rather than as more string fragments.  But
the string-template scaffolding itself (route registration, server
lifecycle, the `_handle` / `_proxyConnection` orchestration glue, all
the WS state-management) **stays inside the template**, because moving
it out would require generated code to depend on an external library.

This is the single biggest source of accumulated friction.

### P2 — Two thread models, structurally divergent

| | Interpreter | Codegen |
| --- | --- | --- |
| HTTP dispatch | JDK `HttpServer` + single-thread executor | JDK `HttpServer` + single-thread executor |
| WS read | NIO selector thread (`WsProxy`) | per-connection virtual thread (`_runReadLoop`) |
| WS write | NIO outbox queue → selector | per-connection writer VT + bounded `LinkedBlockingQueue` |
| User callbacks | Shared single-thread executor | Shared single-thread executor |

The HTTP side is already unified.  The WS side is structurally
different because:

- The **interpreter requires serial access** to `Value` / `Computation` /
  globals / call stack — NIO + single executor enforces this naturally.
- The **codegen output is thread-safe** (generated Scala obeys regular
  Scala memory model) — per-VT is simpler and scales linearly.

This is why `WsConnection.scala` (498 LOC) and the codegen WebSocket
class (~370 LOC of template text) can't be one class.  Phase 2e-3
extracted the per-frame dispatch into `WsFrameDispatch` but the
loop/thread orchestration stays separate.

### P3 — TlsProxy accumulates drift

TLS mode (interpreter `TlsProxy.scala` + codegen `_proxyConnection`
TLS arm) is a separate code path that Phase 2 has touched **last** in
every sub-phase.  2f-3 just closed the WsHandshake gap left from 2d.
Each new shared helper requires an explicit TlsProxy migration pass.

Root cause: `com.sun.net.httpserver.HttpServer` (used in plain mode)
doesn't support WebSocket upgrade — so the runtime adds a custom
"TLS proxy → internal loopback HttpServer" detour, and the WS upgrade
flow is reimplemented inside the TLS proxy.  This is structurally a
workaround.

### P4 — No unit tests on `runtime-server-common`

29 files of shared runtime, exactly **one** test file
(`WsFramingTest.scala`).  Regression coverage is conformance-driven —
slow, indirect, doesn't run automatically on PRs that only touch the
shared module.  Most of the Phase 2 refactors are validated by
"compile passes" + manual conformance runs.

This is the **lowest-cost gap to close** and unblocks confidence in
any of the strategic moves below.

### P5 — JS backend stays separate (immutable)

JS backend runs on Node `http`/`ws` — fundamentally different runtime.
No path to share `runtime-server-common` with it short of writing the
helpers in pure-JS-compatible Scala (no JDK), which they're not.
**This is just a fact**: "consolidate the runtime" only ever means
"the two JVM backends".

## Strategic options

### Option A — Eliminate the string template (`P1`)

**What.** Turn `JvmGen.serveRuntime` from a `"""|..."""` blob into
imports of a published `scalascript-runtime-server-jvm` jar.  Codegen
output emits a thin `using lib …:scalascript-runtime-server-jvm:VERSION`
plus the user's `route { … }` calls; the runtime is real Scala code
in the jar, type-checked at our build time, not at the user's run time.

**How.**
1. Promote `runtime-server-common` from "sources inlined as strings"
   to "consumed as a real dependency".  Already a real sbt module —
   just needs publishing.
2. Create `runtime-server-jvm` containing what's currently in the
   template (server lifecycle, route registration, WS state mgmt).
   It depends on `runtime-server-common`.
3. Publish both to Maven Central (or a private repo) on each release.
4. `JvmGen.serveRuntime` collapses to a small emitter producing:
   ```scala
   //> using lib com.scalascript::scalascript-runtime-server-jvm:VERSION
   import scalascript.runtime.server.*
   // user's route/serve/onWebSocket calls translated verbatim
   ```

**Trade-offs.**
- + Loses **all** the string-template friction in one move.
- + Codegen runtime becomes a first-class module with tests, IDE
  support, types, refactors.
- + scala-cli's per-run compile drops dramatically (depends on a jar
  instead of compiling 4 000 lines from source).
- − Requires publishing infra (Maven Central + signing keys + release
  process).  Can mitigate with GitHub Packages first.
- − Version compatibility surface: every backward-incompatible runtime
  change requires a new version and either upgrading user scripts or
  pinning.  ssc itself can pin a known-good version per ssc release.
- − Loses the "single self-contained script" property of generated
  output — generated `.scala` files now require Internet for the first
  build (until scala-cli caches the jar).  Workaround: bundled offline
  cache for `ssc compile`.
- − Distribution becomes a real concern; today ssc is a fat jar with
  zero external deps at runtime.

**Effort.** ~1 week.  Publishing infra is the slow part; the actual
code refactor is mostly mechanical (move template content into real
.scala files, fix the imports).

**Unlocks.** Phase 2h+ dedup becomes trivial.  Option B becomes much
cheaper to attempt.  External users could write their own routes on
top of the runtime without ssc at all.

### Option B — Unify the thread model (`P2`)

**What.**  Switch the interpreter from NIO selector + executor to
per-VT-per-connection (matching the codegen).  Add a coarse-grained
`Interpreter.lock` around user-handler invocation so `Value` /
`Computation` state stays serial.

**How.**
1. Replace `WsProxy`'s NIO selector with a blocking accept loop +
   `Thread.ofVirtual().start { ... }` per accepted socket.
2. Each per-VT read loop calls `WsFrameDispatch.handle` (Phase 2e-3
   already shared this) and dispatches to a shared executor for user
   callbacks.
3. `WsConnection` consolidates with the codegen `WebSocket` template
   class.
4. `Interpreter.invoke` takes a global lock; user handlers run
   serially regardless of which VT triggered them — same end-user
   semantics as today.

**Trade-offs.**
- + One `WebSocket` class instead of two.  Heartbeat, outbox, slot
  reservation, close handshake all live in one place.
- + JDK 21+ virtual threads remove the historical "NIO is faster" gap
  for the interpreter.
- − Interpreter under WS load now has one VT per connection plus an
  executor.  Lock contention on `Interpreter.lock` becomes the
  bottleneck instead of selector throughput.  Probably fine for the
  interpreter's use case (REPL + small services) but needs measuring.
- − Touches `WsConnection`, `WsProxy`, `BlockingWsSession`.  Conformance
  test coverage is decent here; risk is real but bounded.
- − Doesn't reduce LOC much by itself — the win is structural
  (one runtime → easier evolution), not lines.

**Effort.** ~1 week, plus a benchmark pass to verify interpreter
WS perf doesn't regress on the conformance suite's `BlockingWsSession`
tests.

**Depends on.** Option A makes this trivial (one class to write
instead of two converging implementations).  Without Option A, you'd
do this twice (once in `WsConnection.scala`, once in the codegen
template).

### Option C — Netty/Jetty (replace JDK HttpServer + TlsProxy)

**What.**  Drop `com.sun.net.httpserver.HttpServer` + the custom
`TlsProxy` / `WsProxy` machinery.  Run HTTP, HTTPS, and WS through
one library (Netty or Jetty) that supports all three out of the box.

**How.**
1. Pick a library.  Netty: maximum throughput, low-level, large API.
   Jetty 12: built on Netty, friendlier, well-documented HTTPS+WS.
   Recommended: **Jetty 12** unless we have a perf reason to go raw.
2. Replace `WsProxy` + `TlsProxy` + the JDK `HttpServer` setup in
   `WebServer.start` and the codegen `serve(port)` template with a
   single Jetty bootstrap.
3. Per-request dispatch still goes through `HttpDispatchLoop`; per-
   frame WS dispatch still goes through `WsFrameDispatch`.  Jetty
   sits **below** these — it's a different HTTP/WS implementation,
   not a different dispatch model.

**Trade-offs.**
- + ~700 LOC of `TlsProxy` + `WsProxy` deletes.
- + No more "internal HttpServer on 127.0.0.1" detour.  Direct WS
  upgrade on the public socket regardless of TLS.
- + HTTP/2 + HTTP/3 + ALPN come for free (Jetty/Netty handle them).
- − **External runtime dependency** Jetty (~3 MB) — currently we have
  zero external deps at runtime.  ssc binary size grows.
- − Generated scala-cli scripts now pull Jetty.  See Option A's
  distribution discussion.
- − Operationally: Jetty's tuning surface (acceptor threads, selector
  threads, max connections) becomes our problem.

**Effort.** ~1–2 weeks including the migration of all conformance
tests to the new server.

**Depends on.** Independent of A and B.  Can land before, after, or
in parallel.  But **doesn't compose well with B without A** — you'd
be migrating both backends to Jetty AND unifying the WS class.

### Option D — Dual-impl elimination

**What.**  Interpreter shells out to JvmGen for HTTP/WS dispatch.
Each user-registered `route(method, path) { req => … }` becomes a
JVM bytecode closure (via the same Scala 3 → bytecode pipeline that
v2.0 uses for separate compilation), and the interpreter hands that
closure to the codegen-side runtime's `_routes.add`.  There's then
**one** HTTP/WS implementation, period.

**How.**
1. Build on v2.0's bytecode-compilation work (in flight on `main`).
   The interpreter compiles each route-handler block to a `Function1`
   in bytecode at registration time.
2. The "interpreter web server" goes away; user code that calls
   `serve(8080) { route("GET", "/x") { req => … } }` invokes the
   shared runtime.
3. `WebServer.scala`, `WsProxy.scala`, `WsConnection.scala`,
   `BlockingWsSession.scala`, `WsClientSession.scala`, `TlsProxy.scala`,
   `Routes.scala`, `WsRoutes.scala` all delete.

**Trade-offs.**
- + Eliminates the entire **reason** for Phase 2 to exist.  One
  runtime, one set of bugs to fix, one set of perf to tune.
- + Interpreter perf for HTTP/WS becomes codegen perf (much faster
  for hot routes — no Value boxing/unboxing per call).
- − Loses immediate-eval semantics inside route handlers: handler
  bodies are compiled at registration time, not interpreted line by
  line.  Hot-reload of handlers becomes "re-register route" instead
  of "edit and re-run".
- − Loses the REPL's ability to inspect interpreter state from
  inside a handler — handlers run as plain bytecode and can't see
  the interpreter's globals / call stack.  Probably acceptable for
  the web-server use case (handlers shouldn't be poking interpreter
  internals anyway).
- − **Depends on v2.0 being mature.**  Separate-compilation stage 5+
  needs to be solid first.
- − Big migration: every existing interpreter conformance test that
  hits the web stack must keep passing.

**Effort.** ~2 weeks once v2.0 separate compilation is stable.
Today's v2.0 status: stage 5.7 landed.

**Depends on.** v2.0 separate compilation (in flight).  Naturally
follows Option A — both involve treating codegen as the canonical
runtime.

### Option E — `runtime-server-common` unit tests (`P4`)

**What.**  Direct unit-test coverage on each shared module.  Currently
exactly one test file in `runtime-server-common/src/test/`.

**How.**  Add `*Test.scala` per file for the table-driven helpers
first (`HttpHelpers`, `WsReassembler`, `WsRateLimiter`, `WsHandshake`,
`BasicAuth`, `Multipart`, `SessionCookie`, `Jwt`, `Totp`, `Password`,
`RateLimit`).  Then mock-based tests for the IO-touching modules
(`RequestBuilder`, `ResponseWriter`, `StreamResponseWriter`,
`HttpDispatchLoop`).

**Trade-offs.**
- + No risk, no behaviour change, only confidence gain.
- + Unblocks the strategic moves: A/B/C/D each touch large surfaces;
  having a fast test suite means we ship them with safety.
- + Catches regressions that conformance suites only catch slowly.
- − Real time investment with no immediate user-visible benefit.

**Effort.**  ~1–2 days for the table-driven helpers.  ~3–5 days for
the full suite including mock-driven `HttpDispatchLoop` tests.

**Depends on.** Nothing.  Standalone.

## Recommended sequencing

1. **Option E first** — pay the test debt.  Cheap, blocks nothing,
   buys safety for everything below.  ~2 days.
2. **Option A second** — eliminate the string template.  Highest
   ROI on accumulated friction.  ~1 week.  Cleanest version is to do
   E before A so the new real-Scala modules are test-covered as they
   land.
3. **One of B / C / D** — pick based on what's driving you:
   - B (thread-model unification) if the next major user is hitting
     WS concurrency edges in the interpreter.
   - C (Jetty/Netty) if HTTP/2 or HTTPS performance is on the
     critical path.
   - D (dual-impl elimination) if v2.0 separate compilation lands and
     we want the simplification.

A locks in the foundation; B/C/D are independent choices on top.
Doing more than one of B/C/D at once is not recommended — the test
surface and conformance regression risk compound.

## Open decisions

These need a call before we start cutting:

1. **Maven publishing infrastructure** — required for Option A.  Are we
   willing to set up Sonatype + GPG signing, or do we start with
   GitHub Packages (private to begin with)?
2. **External runtime dependencies allowed?** — current ssc has zero
   external deps at runtime.  Option C explicitly breaks this for
   Jetty.  Option A breaks it for the runtime jar itself (unless we
   bundle it via scala-cli's offline cache).
3. **Backward compatibility horizon for the runtime jar** — once
   published, the API becomes a real contract.  Are we willing to
   ship a 0.x with breakage, then 1.x with semver?  Or keep the
   runtime tightly coupled to ssc releases (any ssc upgrade requires
   a runtime upgrade)?
4. **Interpreter perf budget** — Option B accepts a global lock on
   `Interpreter.invoke`.  How much WS throughput regression is OK?
   Need a baseline measurement first.
5. **TLS strategy** — if not Option C, do we still want to eliminate
   the `TlsProxy → internal HttpServer` detour?  Possible without
   Jetty by switching to `HttpsServer` and writing a thin WS shim,
   but that's its own project.
6. **JS backend evolution** — when v2.0 lands a unified codegen IR,
   could the JS backend share more with the JVM runtime via a SPI?
   Probably not for HTTP/WS specifically (Node primitives are too
   different), but worth a design pass.

## Out of scope

- Replacing scala-cli with our own build tool.
- Migrating `JsRuntimeMcp` (JS-backend MCP runtime) into a shared
  module.  Node's `http`/`ws` are too different.
- Generalising `runtime-server-common` to non-server use cases.
- Performance optimisation work disconnected from the dedup story
  (buffer pooling, zero-copy, etc.) — handled separately when
  needed.

## Open follow-ups from Phase 2

These didn't make it into Phase 2a–g but are still on the table as
quick tactical wins regardless of which strategic option we pick:

- **WsSlotManager extraction** — `_wsActiveCount` / `_wsMaxActive` +
  `tryReserve` / `release` static state duplicated between
  `WsConnection.tryReserveSlot` (interp) and codegen `_wsTryReserve`.
  One shared object, ~30 LOC saved.  Low risk.  Could land before A.
- **Semantic renaming of `serveRuntime` parts** — `Part1` / `Part1b`
  / `Part2` → `serveRuntimeRest` / `serveRuntimeWs` / `serveRuntimeTls`.
  No logic change; readability win.  Useful if we end up staying with
  the string template longer than expected.
- **WS client refactor** — `WsClientSession` + `BlockingWsSession` +
  the codegen outbound WS client.  Could share more framing/reassembly
  via `WsFraming` + `WsReassembler` (already shared, but not used by
  the client paths).  ~100 LOC potential.
