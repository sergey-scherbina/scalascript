# Milestones

Tracks work that is **not yet done**. As things land, move them out of here
(into git history) rather than ticking checkboxes — the file should always
read forward.

## Backend SPI v0.1 — landed (Stages 1–9.1)

Plugin architecture that abstracts the four backends (`JvmGen`,
`JsGen`, `ScalaJsBackend`, `Interpreter`) behind a stable SPI.
Built on branch `feature/backend-spi` across ten stages; see commit
history for the per-stage walk.  Design source of truth:
[`docs/backend-spi.md`](docs/backend-spi.md).

What landed (Stages 1–9.1):

- **9-module sbt layout** — `backend-spi/` (SPI traits), `ir/`
  (IR + JSON/MsgPack codecs), `core/` (parser/typer/transform/
  validate/plugin), `backend-jvm/` / `-js/` / `-scalajs/` /
  `-interpreter/`, `backend-scala-source/` (SourceLanguage skeleton),
  `cli/`.  Old `compiler/` single-module gone.
- **SPI surface** (`backend-spi/`):  `Backend` /
  `InteractiveBackend` / `Session` / `SourceLanguage` traits;
  `Capabilities` + `Feature` (18 cases) + `OutputKind` (9);
  `CompileResult` (TextOutput / Segmented / BinaryOutput / Executed
  / Failed); `IntrinsicImpl` (InlineCode / RuntimeCall /
  HostCallback); `Diagnostic` (Unsupported / UnknownIntrinsic /
  UnknownBlockLanguage / Generic).
- **IR + codecs** (`ir/`):  `NormalizedModule` mirrors AST with
  upickle `derives ReadWriter` round-trip; placeholders for
  `Perform`/`Handle`/`Resume`/`TailCall`/`ExternCall`/`MatchTree`
  reserved for Stage 3+ population.  `Normalize` + `Denormalize`
  passes in core.
- **Effect analysis extracted** to `core/transform/EffectAnalysis.scala`
  (`JvmGen`/`JsGen`/`Interpreter` no longer carry parallel copies).
- **Capability validation** (`core/validate/CapabilityCheck.scala`):
  walks IR, intersects required features with backend's set, emits
  `Diagnostic.Unsupported` for misses.  Each bundled backend declares
  its own `Capabilities`.
- **In-process plugins via ServiceLoader.**  Every backend ships a
  `META-INF/services/scalascript.backend.spi.Backend` entry;
  `core/plugin/BackendRegistry` discovers them.  `--plugin <jar>`
  adds a third-party JAR via `URLClassLoader`.
- **Out-of-process plugins** (`core/plugin/SubprocessBackend.scala`):
  stdio-json wire protocol with full spec at
  [`docs/backend-spi-protocol.md`](docs/backend-spi-protocol.md).
  `plugin.yaml` discovery from `$SCALASCRIPT_PLUGIN_PATH` and
  `~/.scalascript/plugins/`.  Lazy spawn on first lookup; caches the
  handle.
- **CLI flags**:  `--list-backends`, `--list-source-languages`,
  `--describe-backend <id>`, `--plugin <jar>`,
  `--plugin-dir <dir>`, `--target <id>`, `--backend <id>`.  `cli/
  Main.scala` routes `run`/`watch`/`compile`/`package`/`emit-scala`/
  `emit-js`/`emit-spa` through `BackendRegistry.lookup` instead of
  importing codegen classes directly.
- **Two worked plugin examples**:
  - `examples/plugins/hello-backend/` — in-process JAR variety
    (~30 LOC + META-INF entry; builds via scala-cli).
  - `examples/plugins/canned-backend/` — subprocess variety
    (~50-line scala-cli script speaking stdio-json).
- **Docs**:  `docs/architecture.md` §4 rewritten against post-SPI
  reality; new `docs/writing-a-backend.md` (third-party guide);
  new `docs/backend-spi-protocol.md` (wire spec).
- **Tests**:  +110 vs main start state.  Module breakdown — core
  94 (round-trip, capability, manifest, subprocess), interpreter 117
  (unchanged), cli 18 (BackendRegistry / SourceLanguageRegistry /
  GlobalFlags).  Total **229 unit + 38 conformance** green.

### Stages 5+, 9+ — SPI followups — **PARTIALLY LANDED**

Branch `feature/spi-followups` (merged to `main`).

**Landed in `feature/spi-followups`:**
- **5+/A — Intrinsic plumbing.**  `extern def` parser modifier;
  `ExternCall` IR node; per-call-site intrinsic dispatch in all three
  codegens; `Backend.runtimePreamble`; `Sys.nowMillis()` demo.
- **5+/B.1 — `std/http.ssc` extern def signatures.**  `extern def route / serve / stop`
  declarations.
- **9+/A.1 — Parser ↔ SourceLanguageRegistry.**  Unknown fence tags
  dispatched through `SourceLanguageRegistry.lookup` at parse time.
- **9+/B.1 — `backend-html` plugin skeleton.**  `HtmlSourceLanguage`,
  `Html` type, `containerTagNames` prelude.  Full extraction (9+/B.2–B.4)
  deferred.
- **9+/C.1 — `backend-css` plugin skeleton.**  Same shape for CSS.
  Full extraction (9+/C.2–C.3) deferred.

**Landed in `feature/spi-5b-http`:**
- **5+/B.2-4 — `std.http` full extraction.**  ✅ **LANDED** (2026-05-18).
  route/serve/stop migrated from `nativeP` to `IntrinsicImpl` pipeline;
  `NativeContext` extended with HTTP hooks; `backend-*/intrinsics/Http.scala`
  created; Request/Response lifted to typed case-class declarations in
  `std/http.ssc`.

**Landed in `main` (2026-05-18):**
- **5+/D — `std.ws` / `std.auth` extraction.**  ✅ **LANDED**.
  metrics/setMaxWsConnections/WsRoom migrated to `WsIntrinsics`;
  all 29 auth ops migrated to `AuthIntrinsics`; `NativeContext` extended
  with WS route / client / auth hooks.
- **5+/E — Core / JSON / Request / Response extraction.**  ✅ **LANDED**.
  44 `nativeP` entries migrated: assert/require/nanoTime/getenv/doc/render/
  Some/List/Map/math.*/escape/collectCss/collectJs/scope → `CoreIntrinsics`;
  jsonStringify/jsonParse/jsonRead/lookup/lookupOpt → `JsonIntrinsics`;
  requireX/optionalX/requireRange*/requireOneOf → `RequestIntrinsics`;
  Response.html/text/json/redirect/notFound/status → `HttpIntrinsics`.
  `NativeContext.validationRecord` hook bridges validate{} stack to NativeImpl.
  JVM + JS intrinsic tables updated for CapabilityCheck coverage.
  Only the HTML DSL tag-generator section (containerTags/voidTags/raw/attr)
  remains hardcoded — those use callValue with effects and are better left
  as native closures.
- **5+/B.3 — bare `println` → `Console.println` migration.**  ✅ **LANDED**.
  Normalize rewrites bare `println` / `print` calls to `Console.println` /
  `Console.print` (word-boundary regex, not preceded by `.`).  All three
  backends now route `Console.println` through the intrinsics table:
  interpreter installs a `Console` companion `InstanceV` (mirrors `math` /
  `Response`); JvmGen gets a `Console` shadow object in the preamble;
  JsGen gets `const Console = { println: _println, print: _print }` plus
  `Term.Select` qualified-intrinsic dispatch in `genExpr`.  Backward-compat
  bare `println` / `print` entries retained in all tables and `initBuiltins`
  for code that bypasses Normalize (tests, `runSnippet`).

**Still deferred:**
- 9+/B.2-B.4 and 9+/C.2-C.3 — full html/css extraction out of codegens

### Stage 6+ — Out-of-process protocol completions — **LANDED**

Branch `feature/spi-followups` (merged to `main`).

- **6+/A — `stdio-msgpack` framing.**  `WireFraming` enum (`Json | MsgPack`);
  `SubprocessBackend` selects framing from `plugin.yaml#protocol`;
  `callMsgPack` uses 4-byte big-endian length prefix +
  `writeBinary`/`readBinary`.
- **6+/B — InteractiveBackend over subprocess.**  `ir.Value` is now a
  concrete sealed hierarchy (`Prim / Arr / Dict / Null`) with `derives
  ReadWriter`.  `SubprocessBackend extends InteractiveBackend`; `openSession`
  sends `openSession` wire message and returns a `SubprocessSession` that
  forwards `feed` / `invokeHandler` / `close` over the wire.
  `BackendRegistry.interactive` includes interactive subprocess plugins.
- **6+/C — HostCallback dispatch.**  `SubprocessBackend.registerHostCallback`
  populates a concurrent map; `callJson`/`callMsgPack` loop detecting
  `host.*` messages from the plugin mid-compile, dispatch to the registered
  handler, and write the result back — then continue waiting for the actual
  compile response.

Remaining: SourceLanguage role through subprocess (parked; lands with 9+/B
and 9+/C full extractions).

### After Phase 9 — `std/*` becomes a hybrid Predef

Once Phase 9 introduces `PreludeContribution`, the user-space
typeclass hierarchy that landed in v1.1 ships as a bundled
SourceLanguage plugin (`backend-std-prelude`) using the same SPI as
any third-party plugin.  Decision: **Predef-style hybrid** — common
abstractions auto-import, specialised ones remain explicit.

| Tier                            | Files                                        | How imported |
|---------------------------------|----------------------------------------------|--------------|
| **Auto-prelude (always visible)** | `functor-applicative-monad.ssc`, `foldable-traversable.ssc`, `either.ssc` | Loaded as `preludeFiles` of the bundled plugin; symbols (`Functor`, `Applicative`, `Monad`, `Foldable`, `Traversable`, `Either`, `Left`, `Right`, the universally applicable `given` instances for `List` / `Option` / `Either`) visible globally without any `[X](./std/…)` line. |
| **Explicit (specialised)**      | `monaderror.ssc`, `selective.ssc`, `bifunctor.ssc`, `semigroup-monoid.ssc` | Still imported via `[X](./std/…)` (or `[X](std/…)` once Phase 9 lets the std-prelude plugin advertise its own paths).  Reason: each is domain-specific — error-typed monads, selective effects, profunctors, algebraic structures — not every program needs them. |

The exact line between tiers is debatable and may shift; principle is
**pre-import what every program plausibly uses; leave explicit what's
domain-specific.** Concrete split TBD during the Phase 9 follow-up.
The user-space `std/*.ssc` files themselves are not touched — they
just get loaded automatically for the auto-prelude tier.

### Open questions

Decisions that don't block Phases 1–9 but need answers before specific
later milestones land.

- **Sync vs async handler semantics.**  JVM `HttpServer` is sync per
  request; Node and WASM are inherently async.  Today the language
  presents a sync API across all three backends.  When we add
  cancellation, timeouts, or backpressure (likely with the WASM
  backend or with a "real" Async runtime per v1.3), decide whether
  they're expressed as algebraic effects or as `Future`/`Promise`
  types.  Doesn't block Phase 1–9 — surfaces before the WASM
  backend or v1.3 Runtime upgrades ship.

- **Shared runtime artefacts between plugins.**  If two plugins both
  wrap the same platform API (e.g. `jvm` and `interpreter` both
  using `com.sun.net.httpserver`, or two future backends sharing a
  cryptography binding), do they share a runtime jar / library?
  Initial answer for v0.1: **no** — duplication is cheap, decoupling
  is valuable.  Revisit if a real pattern of shared runtime emerges
  across three or more plugins.

- **Plugin trust / sandboxing.**  Loaded JAR plugins run with full
  JVM permissions; subprocess plugins run as ordinary OS processes.
  Documented as untrusted code for v0.1 of the SPI.  Revisit if/when
  a plugin marketplace exists or we want to support running
  user-supplied plugins inside CI.

- **Namespaces (`package std.foo` hierarchy), local `.ssc`
  versioning, package registry / discovery.**  All real gaps for
  ecosystem-scale work but well beyond v0.1 scope.  Need separate
  design docs when use cases mature.

### Considered and rejected

For the archeology — proposals that surfaced during design and were
deliberately not pursued, with the reasoning so future contributors
don't re-propose them without new evidence.

- **"Phase 10 — extract `Interpreter` pattern-matching to `.ssc`."**
  Initial suggestion to move the ~300 lines of `.map`/`.flatMap`/
  `.length` dispatch in `Interpreter.scala` into a bundled
  `core-prelude` plugin (parallel to how Phase 9 handles
  `html`/`css`/`scala`).  Rejected because:
    1. The hardcoded dispatch is an artefact of **one** backend (the
       tree-walking interpreter).  `JvmGen` and `JsGen` already
       delegate to the platform stdlib, so the duplication isn't
       multi-backend — it's a single-backend implementation detail.
    2. The v1.1 `std/*` typeclass hierarchy already builds *on top
       of* these primitives.  Replacing them with `.ssc`
       implementations would slow the interpreter without
       architectural payoff — typeclasses are the right place for
       generic abstraction, not re-implementations of `.map`.
    3. The real friction users hit (sealed-trait extension dispatch,
       `summon` auto-resolution, `Term.Ascribe` ✓) lives in the
       interpreter's type-system / dispatch logic, not in its method
       table.  That work is tracked under **Interpreter ergonomics
       — carried over from v1.1** (further down in this file; all items now landed).

## Compiler extensibility roadmap

A cross-cutting note tying together the SPI followups
([`docs/spi-followups-plan.md`](docs/spi-followups-plan.md)), the
intrinsic-module extraction direction
([`docs/spi-intrinsics-design.md`](docs/spi-intrinsics-design.md)),
and the "deflation" benefit on the three large codegens.

### Today's pattern-matching debt

Every code generator hardcodes platform intrinsics as match arms on
`Term.Name`:

  - `backend-jvm/JvmGen.scala`     — 4500 LOC, ~⅓ is HTTP/WS/auth
    inlined match cases.
  - `backend-js/JsGen.scala`       — 5400 LOC, similar split.
  - `backend-interpreter/Interpreter.scala` — 4500 LOC, dozens of
    `nativeP("route")` / `nativeP("onWebSocket")` / … blocks.

Costs of this shape:

  - Adding a platform primitive touches three files.
  - No cross-backend parity check at build time; conformance suite
    catches misses post-hoc.
  - Individual intrinsics aren't independently testable.
  - Third-party platform extensions impossible — the code lives
    inside `core/`, not in plugins.

### What the SPI followups deliver

Stages already designed and planned in `docs/spi-followups-plan.md`
+ `docs/spi-intrinsics-design.md`:

  - **5+/A.4 — per-call-site `ExternCall` dispatch.**  ✅ **LANDED** (2026-05-18).
    Achieved via AST-level `dispatchIntrinsic` / `dispatchIntrinsicJs` in
    JvmGen / JsGen: both backends look up `QualifiedName(fname)` in the
    intrinsics table before falling through to any hardcoded handling.
    Stage 5+/B.3 extended this to `Term.Select(obj, method)` qualified calls.
    The original `ExternCall(qn, args)` IR-node path remains planned for when
    Normalize emits IR expressions; the AST-level approach covers all currently
    migrated intrinsics.  Per-intrinsic match arms removed for all migrated
    functions.
  - **5+/A.5 — `extern def` parser + `Backend.runtimePreamble`.**
    Declarations live in `std/*.ssc`; backends ship runtime helpers
    (e.g. emitted `class WebSocket`) via a single string field.
  - **5+/B — `std.http` extraction.**  ✅ **LANDED**.  route/serve/stop
    in InterpreterIntrinsics + JvmHttpIntrinsics + JsHttpIntrinsics.
    NativeContext extended.  std/http.ssc has Request/Response declarations.
  - **5+/D — `std.ws` / `std.auth` / `std.fs` / `std.crypto`
    extraction.**  **Next step.**  Same pattern as 5+/B, one package
    per iteration.  Requires extending NativeContext or adding new hooks
    per intrinsic group.

### Expected deflation

After 5+/B + 5+/D land:

  | File                            | Before  | After  | Delta |
  |---------------------------------|--------:|-------:|------:|
  | `JvmGen.scala`                  | 4500    | ~1500  | −3000 |
  | `JsGen.scala`                   | 5400    | ~1500  | −3900 |
  | `Interpreter.scala`             | 4500    | ~2500  | −2000 |
  | new `backend-*/intrinsics/*`    |       0 | ~3000  | +3000 |

Total LOC roughly conserved; split by responsibility: codegen
core = "how to emit the generic language", intrinsic modules =
"what `onWebSocket` does on backend X".  Each intrinsic = one
function on each backend it claims.

### Third-party plugin path

The SPI declaration (§8 of `docs/backend-spi.md`) already supports
third-party intrinsic packages — a plugin author ships an `extern`
package together with the `Backend.intrinsics` entries that
implement it.  Once 5+/B proves the pattern in-tree, the
out-of-tree plugin path is one ServiceLoader-discovery wire-up.

Remaining UX/distribution work (not blocking the SPI mechanism):

  - **Package format** (`.sscpkg` archive with manifest + sources +
    optional pre-compiled IR) — mentioned in v0.7 as future.
  - **Plugin resolver** — `--plugin <jar>` / `~/.scalascript/plugins/`
    discovery already in §12.1 design; verify end-to-end.
  - **Registry** — `registry.scalascript.io` with semver + lock
    file (v0.7 future, deferred).

### Effort to "extensibility done"

5+/A.4 (~1-2d) + 5+/A.5 (~1-2d) + 5+/B (~3-5d) + 5+/D (~1-2d
per package × 4 packages = ~1 week) = **~2-3 weeks** of focused
work.  After this, "add a new platform primitive" is one
function per backend; "ship a Kafka library" is one external
plugin JAR.

### Out of scope here

Separate compilation of modules (per-module IR artifacts +
interface files + linker pass) is a different architectural axis.
Tracked as v2.0 below — it's a 2-3 month commitment that becomes
worth the cost only once a real package ecosystem emerges.

## Recommended implementation sequence

The roadmap items below interleave by version number but have real
dependency relationships.  This section gives a critical-path
ordering optimised for unblocking high-impact deliverables first
and minimising rework.

### Dependency graph

```
SPI 5+/A.4 (per-call-site dispatch)
  │
  └── SPI 5+/A.5 (extern def + Backend.runtimePreamble)
        │
        ├── SPI 5+/B  (std.http extraction; proof of intrinsic-module shape)
        │     │
        │     └── SPI 5+/D (std.ws / auth / fs / crypto extraction)
        │           │
        │           └── v1.7 (plugin packaging & discovery)
        │                 │
        │                 └── v2.0 (separate compilation)
        │
        └── v1.5 Tier 1 (TLS — could ship as intrinsic via new pipeline)
              │
              ├── v1.5 Tier 2 (HTTP client — uses Tier 1 TLS for HTTPS)
              │
              └── v1.5 Tier 3 (WS client — uses Tier 1 TLS for wss)
                    │
                    └── v1.6 Phase 3 (distributed actors over WS)

v1.5 Tier 4 (streaming) + Tier 5 (REST ergonomics) — orthogonal,
       no SPI dependency, can land any time

v1.6 Phase 2 (supervision) — orthogonal, no SPI dependency

6+/A (direct-syntax) — orthogonal, parked until std.* extracted
6+/C (HostCallback dispatcher) — orthogonal, parked
```

### Suggested order (critical-path optimised)

This minimises rework (TLS ships through the new pipeline rather
than the old hardcoded codegens, so no double-implementation) and
unblocks downstream features as early as possible.

  1. **SPI 5+/A.4** — per-call-site dispatch (~1-2d).
     Foundational; unblocks everything below.
  2. **SPI 5+/A.5** — `extern def` parser + `Backend.runtimePreamble`
     (~1-2d).  Foundational; pairs with 5+/A.4.
  3. **SPI 5+/B** — `std.http` extraction (~3-5d).
     Proof point that the SPI shape carries a real platform package
     end-to-end.  Critical for confidence before generalising.
  4. **v1.5 Tier 1 — TLS** ✓ Landed.
  5. **SPI 5+/D** — `std.ws / auth / fs / crypto` extraction ✓ Landed.
  6. **v1.5 Tier 2 — HTTP client** ✓ Landed.
  7. **v1.5 Tier 3 — WS client** ✓ Landed.
  8. **v1.7 — Plugin packaging & discovery** ✓ Landed.
  9. **v1.6 Phase 2 — Actors supervision** ✓ Landed.
 10. **v1.6 Phase 3 — Distributed actors** ✓ Landed.
 11. **v1.5 Tier 4 — HTTP server completeness** ✓ Landed.
 12. **v1.5 Tier 5 — REST ergonomics** ✓ Landed.
 13. **v1.8 — Direct-syntax do-notation** ✓ Landed.
     All 6 phases in main: interpreter, JvmGen+JsGen codegen,
     conformance tests, `std/monad-control.ssc`, diagnostics,
     `direct-syntax-demo.ssc`.
 14. **v1.9 — Coroutine primitive** ✓ Landed.
     All 4 phases; interpreter + JvmGen + JsGen; 19 conformance tests.
 15. **v1.10 — Generators** ✓ Landed.
     `flatMap`, `zip`, `zipWithIndex` added; all 3 backends; 4 new tests.
 16. **v1.11 — Continuation-based Async** ✓ Landed.
     Rewrite `Async.*` on top of v1.9 coroutines.  Internal
     `Computation[A]` becomes a runtime-only shim; ≥20% allocation
     reduction target on flatMap-heavy workloads.  User code
     unchanged — conformance gates the merge.
 17. **v1.11.5 — `Free[F, A]` as stdlib type** ✓ Landed
     User-facing `Free` monad in `std/free.ssc` built on v1.1
     typeclasses + v1.9 coroutines.  Program-as-data complement
     to coroutine's program-as-control-flow.  Pure library work,
     no compiler changes.  Parallel with v1.11 if scheduling
     permits.
 18. **v1.12 — Algebraic effects feasibility study** (~1 week, no
     shipping code).
     Design doc + prototype + go/no-go.  Investigates whether the
     existing typer can carry effect rows; commits to or rejects a
     v2.x algebraic-effects milestone.
 19. **v1.13 — Final Tagless ergonomics** ✓ Landed.
     Land four typer features that block idiomatic typeclass usage:
     `using` auto-resolution, context bounds, cross-file trait
     inheritance with HKT, sealed-trait extension dispatch in INT.
     Full design in [`docs/final-tagless.md`](docs/final-tagless.md).
     Closes carryover items 1 + 4 from v1.1.  Unlocks idiomatic FT
     across `std/*` and unblocks v1.14 `derives` + v1.15 `throws`.
 19. **v1.15 — Checked errors via `throws`** ✓ Landed.
     **Higher priority than v1.14** — closes the everyday
     error-handling story; prerequisite for many real apps.
     Dual-encoding: canonical `infix type throws[A, E] = Either[E, A]`
     (direct-syntax-integrated, monadic, ergonomic) plus opt-in
     `infix type throwsRaw[A, E] = A | E` (zero-allocation; preserves
     native JVM `Throwable.getStackTrace`; used for hot-path parsing
     and JVM exception interop).  Includes return-site auto-conversions,
     `box`/`unbox` between encodings, std-lib platform-exception shims
     (`parseInt`, etc.), `attemptCatch` / `attemptCatchRaw` opt-in
     lifts, and the `HasStackTrace` mixin with `currentStackTrace()`
     per-backend.  Full design in
     [`docs/error-handling.md`](docs/error-handling.md).  Depends on
     v1.8 ✓ (direct-syntax) + v1.13 (`using` + cross-file traits).
 20. **v1.14 — Metaprogramming MVP (`inline` + `derives`)** ✓ Landed.
     weeks).
     `inline def`/`val`/`if`/`match` + `compiletime.summonInline`
     compile-time evaluator, plus Tier 1 `derives` recipes for
     `Eq` / `Show` / `Hash` / `Order` and a handful of std
     typeclasses (`Foldable` / `Traversable` / `Functor`).
     Full design in [`docs/metaprogramming.md`](docs/metaprogramming.md).
     User-defined macros (`quoted.Expr`) explicitly out of scope —
     deferred to v2.x.  Depends on v1.13 (`Mirror` resolution).
 21. **v1.17 — MCP support (client + server)** ✓ Landed (Phases 1–7);
     v1.17.1 hardening ✓ Landed; v1.17.2 SSE/JS ✓ Landed;
     v1.17.3 prompts/JVM ✓ Landed; v1.17.4-min Http/Ws/JVM (minimal
     wiring, echo placeholder) ✓ Landed; v1.17.4-runtime consolidation
     Phase 1 (a + b + c) + Phase 2 (a + b + c + d + e + f + g —
     pure helpers + POJO HTTP model + RequestBuilder / ResponseWriter
     / StreamResponseWriter + StaticAssetServer + WsHandshake /
     Reassembler / RateLimiter + HttpDispatchLoop + WsFrameDispatch
     + HttpHelpers.{parseCookieHeader, readHttpHead, parseHttpHead}
     + TlsProxy migration, 29 inlined files) + Phase 3 (Option A:
     serveRuntime out of string templates — 4 real .scala files in
     a new runtime-server-jvm module, ~1750 LOC migrated from the
     """|..."""  template) + Option B (interpreter WS to per-VT
     thread model — Selector loop replaced with blocking accept +
     Thread.ofVirtual() per connection, mirroring the codegen;
     −211 LOC) ✓ Landed; v1.17.4 full (real `McpServerSession`
     dispatch + SDK import fixes) ✓ Landed (all 2026-05-19).
     Anthropic's Model Context Protocol via REST-shaped API
     in a separate namespace (`std/mcp/*`).  Intrinsic-first:
     wraps `@modelcontextprotocol/sdk` on Node and
     `io.modelcontextprotocol:sdk` on JVM; interpreter +
     scalajs-spa reject at typecheck via SPI feature flags.
     Full design in [`docs/mcp.md`](docs/mcp.md).  Remaining
     v1.17.x work: INT own-impl, type-class layer,
     streaming resources.

     **v1.17.x interpreter own-impl + OAuth/OIDC layer** ✓ Landed
     (Iterations J–AA, 2026-05-19):

     **MCP spec completion (J–R)** — `notifications/<cat>/list_changed`
     (J); cancellation via `notifications/cancelled` + cooperative
     `srv.isCancelled` polling (K); progress notifications with
     `_meta.progressToken` (L); `logging/setLevel` + `notifications/
     message` with syslog levels (M); `resources/templates/list` +
     RFC 6570 URI templates (N); `roots/list` server→client request +
     `notifications/roots/list_changed` (O); `elicitation/create`
     three-way reply (P); `completion/complete` for prompt args +
     resource template params (Q); cursor pagination on all four
     list endpoints (R).

     **OAuth 2.1 Authorization Server** — standalone
     `scalascript.oauth.*` package, fully decoupled from MCP, usable
     from any HTTP service:
     - **Iter S**: pluggable `TokenValidator` + `currentAuth`
       thread-local + RFC 9728 protected-resource metadata +
       WWW-Authenticate on 401; HTTP transport gates every request.
     - **Iter T**: standalone `AuthServer` — authorization-code grant
       with mandatory PKCE (OAuth 2.1), refresh-token grant with
       single-use rotation (§6.1), client-credentials grant,
       Dynamic Client Registration (RFC 7591), token introspection
       (RFC 7662), AS metadata (RFC 8414).  `McpAuth` reduced to a
       re-export shim over `oauth.OAuth`; bridge via
       `builder.useAuthServer(as)`.
     - **Iter U**: framework-agnostic HTTP route handlers
       (`OAuthRoutes`) for `/token`, `/introspect`, `/register`,
       `/authorize`, `/.well-known/oauth-authorization-server` —
       returns typed `RouteOutcome { Json | Redirect | Empty }`.
     - **Iter V**: token revocation (RFC 7009) — `/revoke` endpoint,
       access-token deny-list via JWT `jti` claim, refresh-token
       lookup; honoured by introspection + tokenValidator.
     - **Iter W**: backend-interpreter installer `OAuthHttp.installRoutes`
       wires all OAuth routes into the embedded WebServer.
     - **Iter X**: script-side intrinsics (`oauth.*` namespace) —
       `authServer(config)` + handle methods (`registerClient`,
       `issueClientCredentialsToken`, `introspect`, `revokeToken`,
       `metadata`), `serveAuthServer`, `issueHmacToken`,
       `pkceVerifier` / `pkceChallenge`, `srv.useAuthServer(asValue)`
       for MCP integration.  JVM bridging via stable-id registry.

     **OpenID Connect (OIDC)** Identity Provider layer on top of AS:
     - **Iter Y**: `scalascript.oidc.*` — `OidcServer` composes
       AuthServer, mints `id_token` (JWT with iss/sub/aud/exp/iat +
       scope-gated profile/email claims) when granted scope includes
       `openid`, serves `/userinfo` (bearer-validated, claim filter),
       extends discovery JSON.  `UserClaims` + `UserInfoStore`.
     - **Iter Z**: `OidcHttp.installRoutes` registers full
       OIDC + OAuth route set in one call (POST/GET `/userinfo`,
       `/.well-known/openid-configuration`).  Script API:
       `oidc.server(as)`, `oidc.serve(idp, basePath?)`, handle methods
       (`addUser`, `userInfo`, `mintIdToken`, `discovery`).

     **JWKS + RSA signing for production OAuth** (Iter AA):
     - Pluggable `TokenSigner` trait (alg / kid / sign / verify /
       publicJwk).  `HmacTokenSigner` (HS256) — default, symmetric.
       `RsaTokenSigner` (RS256) — asymmetric, 2048-bit RSA pairs.
     - `jwksDocument(signers)` — RFC 7517 JWK Set; symmetric signers
       contribute no public material.
     - AS accepts `customSigner` constructor param; all internal
       mint/verify paths route through `signer`.  Metadata advertises
       `token_endpoint_auth_signing_alg_values_supported` and (when
       asymmetric) `jwks_uri`.  GET `/.well-known/jwks.json` route
       in both `OAuthHttp` and `OidcHttp` installers.  OIDC
       `id_token` automatically RS256-signed when AS uses RSA signer.

     **Test coverage**: 270 tests across 32 suites covering all the
     above — MCP (143), OAuth core (29), OAuth routes (23), MCP↔OAuth
     bridge (5), OAuth revocation (9), OAuth HTTP installer (11),
     OAuth script intrinsics (6), OIDC server (18), OIDC script + HTTP
     installer (8), Auth/RSA/JWKS (18), plus older MCP suites.

     **Tool + resource annotations** (Iter BB) ✓ — MCP 2025-03 UI
     hints: `ToolAnnotations(title, readOnlyHint, destructiveHint,
     idempotentHint, openWorldHint)` and
     `ResourceAnnotations(audience, priority)`.  All optional,
     emitted in tools/list + resources/list + resources/templates/list
     only when non-empty; backwards-compatible (registration calls
     without an `annotations` arg still work).

     **Generic Resource Server SDK** (Iter CC) ✓ —
     `scalascript.oauth.OAuthGuard` lets any HTTP service wrap route
     handlers in bearer-token validation with one call.  Pure
     `OAuthGuard.check(headers, validator, requiredScopes?, realm?)`
     returns `GuardDecision { Allow(claims) | Deny(routeOutcome) }`;
     401 carries WWW-Authenticate; 403 carries `insufficient_scope`
     + the required-scope list (so clients know what to request).
     Script API: `oauth.guard(authServer, scopes?)(handler)` curries
     into a `req => Response` wrapped handler; alternative
     `oauth.guardWithValidator(validatorFn, scopes?)(handler)` for
     non-AS validators (JWKS / custom).  Companion
     `oauth.hmacValidator(secret)` exposes a stand-alone validator
     for script use.  MCP server's RS logic is the same `check` —
     unified codepath.

     **Examples + documentation** (Iter DD) ✓ — comprehensive
     `docs/oauth.md` (big-picture map, AS recipe, RS-guard recipe,
     OIDC recipe, RSA+JWKS migration path, MCP integration, spec
     compliance table covering 13 RFCs + OIDC Core / Discovery) plus
     four runnable `.ssc` examples: `oauth-as-standalone`,
     `oidc-idp`, `oauth-rs-guard`, `oauth-rsa-jwks`,
     `mcp-server-protected`.

     **Generic `_meta` propagation** (Iter EE) ✓ — final MCP spec
     gap closed.  Optional `meta: Option[ujson.Value]` on tool /
     resource / resource template / prompt registrations; emitted
     under the `_meta` JSON key on every list endpoint when non-
     empty.  Coexists cleanly with annotations + pagination;
     legacy registrations without a `meta` arg work unchanged.

     **MCP is now fully spec-compliant** against MCP 2025-03 +
     OAuth 2.1 + OIDC + the relevant RFCs.

     **RSA AS from scripts** (Iter FF) ✓ — `oauth.authServer(...)`
     now accepts `signer: "HS256" | "RS256"` (+ optional
     `signingKid`).  When `signer = "RS256"` a fresh 2048-bit RSA
     key pair is generated automatically; metadata picks up `RS256`
     in `token_endpoint_auth_signing_alg_values_supported` and the
     `jwks_uri` field; `/.well-known/jwks.json` publishes the
     public key with the supplied `kid`.  OIDC `id_token`
     automatically RS256-signed when AS uses RSA.  HS256 mode
     stays the default with full backwards compat.

     **Passkey / WebAuthn assertion grant** (Iter GG) ✓ —
     `scalascript.oauth.Passkey` + new
     `urn:ietf:params:oauth:grant-type:passkey` OAuth grant.
     `PasskeyStore` maps credentialId → (subject, publicKey, alg);
     `Passkey.verifySignature` validates RS256 / ES256 assertions;
     `GET /passkey/challenge` issues a single-use nonce; `/token`
     accepts the grant with form fields `credential_id / challenge /
     signed_data / signature / scope`.  Public-key decoders for
     X.509 SPKI, RSA JWK (n/e), and EC JWK (x/y).  Registration
     ceremony + clientDataJSON / origin / rpId verification stay
     with the caller — we focus on the cryptographic core +
     OAuth-integration plumbing.  Metadata + installer routes
     auto-pick-up the new grant + endpoint.

     **MCP late-2025 spec additions** (Iter HH) ✓ — fills the gap
     between MCP 2025-03 and the rolling additions that landed since:
       - `outputSchema` field on tool entries; tools/list emits it
       - `structuredContent` field on tools/call results;
         `ToolHandlerResult(content, isError, structuredContent)`
         supports the typed alternative payload
       - `audioContent(data, mimeType)` helper — `type: "audio"`
         content variant (parallel to imageContent)
       - `resourceLinkContent(uri, name?, description?, mimeType?)`
         — lightweight `type: "resource_link"` reference variant
       - direct `title` field on tool / resource / resource template /
         prompt entries (distinct from annotations.title; clients
         may prefer the entry-level field when both are set)
       - all new fields are optional; legacy registrations unchanged

     **Client-side auth coverage** (Iter II) ✓ — closes the gap an
     honest audit revealed: until this iteration our MCP clients
     couldn't talk to our own auth-protected MCP servers, and AS-side
     client secrets were stored in plaintext.

       - **McpHttpClient.setBearerToken / McpWsClient(bearerToken)**
         — bearer applied to every outbound POST + SSE GET (HTTP) or
         the WebSocket upgrade handshake (WS).  `mcpConnect(transport,
         timeoutMs?, bearerToken?)` exposes it from scripts.
       - **`scalascript.oauth.OAuthClient`** — client-side OAuth SDK
         covering all three roles' Client half: `discoverAs(issuer)`
         / `discoverRs(resourceUrl)` metadata lookups; `freshPkce()`
         + `authorizationUrl(...)` for the auth-code+PKCE flow;
         `exchangeAuthorizationCode / refresh / clientCredentials`
         token endpoints; `TokenHolder` with lazy auto-refresh when
         the cached token is within `refreshLeadSeconds` of expiry.
       - **AS client-secret hashing** — `OAuth.hashSecret` (PBKDF2-
         HMAC-SHA256, 100k iterations, 16-byte salt) +
         `verifySecret` (constant-time compare, legacy-plaintext
         fallback for non-prefixed entries).  `registerClient` now
         stores the hashed form; the registration response carries
         the plaintext once per RFC 7591 §3.2.1 norms.  Existing
         stores keep working — fallback path handles them until
         rotation.

     **v1.17.x is now feature-complete** for MCP + OAuth + OIDC +
     all the spec-grade auth surface a real production AS needs.

     **Iter JJ — Security correctness** ✓ — closed three critical
     security holes the audit flagged.

       - **`aud` audience validation** in `OAuthGuard.check(...,
         expectedAudience = Some("api-a"))`.  RS now refuses tokens
         whose `aud` claim doesn't include its identifier — defeats
         the "token issued for RS-A used at RS-B" attack.  Accepts
         both string and array forms of `aud` per RFC 7519 §4.1.3.
       - **OIDC `nonce` claim** round-trip: `AuthorizationRequest`
         + `/authorize` route + `AuthorizationCodeRecord` carry it
         from the authorize step to the AS's nonce side-map; OIDC
         `mintIdToken` pulls it out + embeds in the id_token —
         defeats id_token replay against a different request.
       - **Clock-skew tolerance** (`DefaultClockSkewSeconds = 60`)
         on JWT `exp` / `nbf` / `iat` checks.  Single
         `validateJwtTimestamps(payload, skew)` helper shared by
         the HMAC + RSA signer paths.  Defeats spurious failures
         from sub-second clock drift between AS and RS; far-future
         `iat` is rejected as a forgery signal.

     **Security hardening backlog** (remaining iterations):

     **Iter KK — Refresh-token reuse detection + rate limiting** ✓ —
     production-grade hardening on top of single-use rotation.

       - **Token family tracking**: every refresh token carries a
         `familyId` (auto-assigned at initial issuance, inherited
         across rotations).  Lets the AS revoke a whole chain in
         one call.  `revokeRefreshFamily(id)` burns every member +
         marks the family as denied forever.
       - **Rotated-token graveyard**: rotated-out refresh tokens
         move into a bounded LRU (default 10k entries) instead of
         vanishing.  `graveyardLookup(token)` returns the
         familyId of any previously-rotated token.
       - **Reuse detection in `handleRefresh`**: when the presented
         token is not in the active store, check the graveyard;
         a hit is the stolen-refresh-token signal → burn the
         family immediately (RFC OAuth 2.1 §4.14.2).
       - **Burn-list failsafe**: `isFamilyRevoked(familyId)`
         consulted on every refresh; persists across restarts in
         the InMemory impl + can be persisted out by custom stores
         (per-token graveyard may be unbounded; the family deny
         set is the durable guarantee).
       - **`scalascript.oauth.RateLimiter`** + `TokenBucket(cap,
         rate)` (continuous refill, key-scoped buckets) +
         `Disabled` no-op default.  AuthServer accepts via
         constructor.  `OAuthRoutes.handleToken` rejects with
         429 + Retry-After when over budget, keyed by client_id;
         runs BEFORE any PBKDF2 verify so brute-force probes pay
         no CPU cost.

     **Iter LL — Client SDK completeness** ✓ — closes the
     "client can't verify what it got" gap.

       - **State CSRF helpers** — `OAuthClient.freshState()` +
         `verifyState(expected, presented)` constant-time compare;
         caller stashes issued state in the session cookie, matches
         against the redirect parameter on callback.
       - **`OAuthClient.JwksCache(jwksUri, ttlSeconds = 300)`** —
         bounded cache backed by the AS's `/.well-known/jwks.json`
         endpoint.  Fetches lazily, refreshes on TTL miss or
         unknown-kid (rotation-tolerant).  Stale tolerated on
         transport failure (best-effort).  RSA + EC (P-256) keys.
       - **`OAuthClient.validateJwt(token, jwks)`** — verifies
         RS256 or ES256-signed external JWTs against the cache;
         clock-skew tolerance matches the AS-side signers.
       - **`OAuthClient.validateIdToken(idToken, jwks,
         expectedIssuer, expectedAudience, expectedNonce?)`** —
         OIDC validation: signature via JWKS, `iss` exact match,
         `aud` (string or array) MUST include expected, optional
         `nonce` exact match.

     **Iter MM — Production hardening** ✓ — three of the four
     planned bits landed (MCP-client 401→re-auth deferred to a
     separate iteration to keep this one focused on AS-side
     hardening that the audit flagged).

       - **TLS enforcement** via `AuthServerConfig.requireTls`:
         when true, OAuthRoutes.handleToken refuses requests whose
         `X-Forwarded-Proto` isn't `https` AND whose Host isn't a
         loopback (`localhost` / `127.0.0.1` / `[::1]`).  Dev
         workflow unaffected — loopback always passes.
       - **CORS** via `AuthServerConfig.corsOrigins: Set[String]`:
         empty (default) disables; non-empty advertises ACAO +
         allowed-methods + allowed-headers + `Vary: Origin` to
         matching origins; `"*"` reflects any origin (use carefully).
       - **AuthEvent audit hook** (`onAuthEvent: AuthEvent => Unit`)
         fires on every security-relevant event: TokenIssued,
         TokenRefused, ClientRegistered, AuthorizationCodeIssued,
         RefreshFamilyBurned, PasskeyAccepted/Rejected, TokenRevoked.
         Listener exceptions are swallowed so a poisoned hook can't
         break the hot path.

     **Iter NN — MCP client 401 → re-auth handler** ✓ —
     `McpHttpClient.setOn401Handler(fn: () => Option[String])`.
     When a request comes back 401 and a handler is wired, the
     client calls it for a fresh bearer + retries the same request
     once.  Returning None propagates the original 401 to the
     caller.  Single-retry budget prevents tight loops against a
     permanently-401 endpoint.  Typical wiring is
     `client.setOn401Handler(() => holder.current())` against an
     `OAuthClient.TokenHolder` that knows how to refresh.

     **Iter OO — OAuth client script intrinsics** ✓ —
     `oauth.client.*` namespace mirrors `OAuthClient` (JVM) for
     `.ssc` apps.  Mounted as a nested InstanceV under the
     existing `oauth` companion object so dotted access works the
     same as `math.sqrt(...)`.

       - `oauth.client.discoverAs(issuer)` / `discoverRs(url)`
         — RFC 8414 / 9728 metadata fetch returning a Map.
       - `oauth.client.freshPkce()` → Map { verifier, challenge,
         method }; `oauth.client.freshState()` / `verifyState(a, b)`.
       - `oauth.client.authorizationUrl(endpoint, clientId,
         redirectUri, scopes, state, challenge, method)` — pure
         URL builder.
       - `oauth.client.exchangeAuthorizationCode(...)`,
         `.refresh(...)`, `.clientCredentials(...)` — token
         endpoints; return a tagged Map:
         `{ ok: Boolean, accessToken?, tokenType?, expiresIn?,
            refreshToken?, idToken?, scope?, error?, description?,
            raw }`.
       - `oauth.client.tokenHolder(endpoint, clientId
         [, refreshLeadSeconds][, secret])` → InstanceV with
         `.seed(tokens) / .current() / .clear()`.  Bridges to the
         JVM TokenHolder via stable-id registry (same pattern as
         AuthServer / OidcServer handles).

     **Iter PP — Final security hardening** ✓ — closes the minor
     gaps an honest audit pass surfaced (DoS via large bodies,
     missing browser security headers, silent weak-secret
     acceptance, log-leakage of bearer tokens).

       - **Body size limit**:
         `AuthServerConfig.maxRequestBytes = 65_536` (64 KiB default).
         OAuthRoutes.handleToken returns 413 Payload Too Large
         before parsing anything — defeats AS-side OOM via megabyte
         JSON / form bodies.
       - **Browser security headers**: when
         `config.securityHeaders = true` (default), every AS
         response carries `X-Content-Type-Options: nosniff`,
         `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
         and (when `requireTls`) `Strict-Transport-Security:
         max-age=31536000; includeSubDomains`.
       - **HMAC secret strength check**:
         `AuthServer.signingSecretWarning: Option[String]` surfaces
         a startup warning for HS256 secrets shorter than 32 bytes
         (RFC 7518 §3.2 floor); RSA / custom signers skip the
         check.  Non-fatal — caller decides whether to refuse boot
         or just log.
       - **`OAuthRoutes.scrubSensitive(s)`** — log-line scrubber
         that redacts bearer headers, `access_token`,
         `refresh_token`, `client_secret`, `code_verifier` in
         both form-encoded and JSON contexts.  Safe for null /
         empty input.

     **Iter QQ — End-to-end integration test** ✓ — proves the
     22+ iterations actually compose: embedded JDK HttpServer
     hosts both the OAuth AS endpoints and an OAuth-protected MCP
     server in one process; drives the full stack through the
     public client APIs.

       1. `OAuthClient.discoverAs(baseUrl)` — RFC 8414 metadata fetch
       2. `OAuthClient.clientCredentials(tokenEndpoint, id, secret,
          scopes)` → real bearer token
       3. `McpHttpClient.setBearerToken(...)` + initialize handshake
       4. `tools/list` over /mcp with bearer → expected catalogue
       5. `tools/call` over /mcp → real tool invocation result
       6. `as.revokeToken(t)` → subsequent /mcp call gets 401
       7. RSA-signed AS metadata exposes `jwks_uri` that's actually
          reachable + serves the matching public key

     Five scenarios cover happy path, missing-bearer 401, garbage-
     bearer 401-invalid_token, post-revocation 401, RSA + JWKS
     discovery.  Boot helper takes `buildAs(baseUrl)` so the AS
     issuer claim ends up equal to the actually-bound port — the
     metadata document advertises real reachable URLs.

     **Iter RR — Persistent stores + full-stack example** ✓ —
     closes the last production blocker: AS state survives process
     restart.  Adds a single-file `.ssc` that exercises the entire
     stack end-to-end.

       - `scalascript.oauth.PersistentStores.JsonLineClientStore(path)`
         — append-only JSON-line file of `client.register` events;
         replay-on-construction reconstructs the in-memory map.
         Corrupt lines are skipped (resilient to partial writes).
       - `JsonLineTokenStore(path, graveyardCap = 10_000)` — same
         pattern across 8 event kinds:
           * `code.save` / `code.consume` (auth codes; one-shot
             consumption persists across restart)
           * `refresh.save` / `refresh.revoke` (rotation history;
             revoked tokens auto-populate the graveyard on replay
             so reuse detection still trips post-restart)
           * `access.revoke` / `family.revoke` (deny lists for the
             RFC 7009 + reuse-detection paths)
       - End-to-end test: register a client + mint a token via one
         AS instance, drop the AS, recreate from the same files,
         confirm the client + bear authentication still work.
       - `examples/oauth-mcp-full-stack.ssc` — single runnable demo
         combining `oauth.authServer` + `oauth.serveAuthServer` +
         `mcpServer { srv => srv.useAuthServer(as); ... }` +
         `serveMcp(Transport.Http(...))`.  Includes curl recipes
         for discovery / mint / protected-call / revoke and a
         Scala snippet showing the persistent-stores swap-in.

     **Iter SS — Production observability + CLI + extra examples** ✓ —
     four parallel work-streams (#1, #2, #3, #6) landed together:

       **(#1) `scalascript.oauth.Observability`** — three building
       blocks for AS deployments:
         - `Health.liveness` / `Health.readiness(check)` — RouteOutcome-
           shaped health probes (200 / 503 with `{status: ...}` body)
         - `class Metrics` — Prometheus exposition-format registry
           with labelled counters + gauges; `routeOutcome()` returns
           a 200 with `text/plain; version=0.0.4`
         - `MetricsBinding.attachDefault(as, m)` — wires the
           AuthEvent stream to 7 standard counters
           (`oauth_tokens_issued_total` / `_refused_total`,
           `oauth_clients_registered_total`, `oauth_codes_issued_total`,
           `oauth_family_burned_total`,
           `oauth_passkey_accepted_total` / `_rejected_total`)
         - `class JsonLineAudit(path)` — file-backed audit log
           consuming AuthEvent → one JSON line per event
           (ts + event + structured fields)

       **(#2) `examples/oidc-login-flow.ssc`** — complete OIDC
       login walk-through: PKCE + state CSRF + /authorize redirect +
       /token exchange + /userinfo bearer call + id_token signature
       + claim verification.  Two pre-registered users; manual curl
       recipe; JVM-side `validateIdToken` snippet.

       **(#3) Three MCP server templates** —
       `mcp-filesystem-server.ssc` (read/write/list/delete with
       hint annotations + sandbox caveat), `mcp-keyvalue-server.ssc`
       (in-memory mutable.Map closed over the builder block),
       `mcp-search-server.ssc` (Files.walk-based substring search,
       top-10 ranked).  All default to Transport.Stdio.

       **(#6) `ssc oauth` CLI subcommand** —
       `discover <issuer>` / `jwks <issuer>` /
       `dcr-register <issuer> <redirect-uri>…` (RFC 7591) /
       `mint <secret> <subject> [scopes…]` /
       `introspect <secret> <token>`.  `mint` warns on short HMAC
       secrets per RFC 7518 §3.2.  `introspect` decodes locally —
       no network round-trip for test fixtures.

     **Truly post-v1.17 (deferred)**: DPoP (RFC 9449
     sender-constrained tokens); PAR (RFC 9126 Pushed
     Authorization Requests); MTLS client auth (RFC 8705 —
     depends on ALPN / client-cert chains).
 22. **v1.18 — `package` keyword + std layout migration** ✓ Landed (all phases, 2026-05-19).
 23. **v1.19 — URL / dep imports** ✓ Landed.
     `[X](https://...)` URL fetch + `[X](dep:org/lib:1.2)`
     resolver, both with `ssc.lock` SHA-256 integrity-check.
     `ssc lock` / `ssc lock check` CLI.  Central registry
     deferred to v1.19.x.
 24. **v1.20 — DSL primitives + `std/parsing`** (~2.5 weeks).
     User-defined string interpolators cross-backend +
     parser-combinator library (`std/parsing/*`) + AST/pretty-
     printer helpers (`std/dsl/*`).  Reified-by-default; Parser
     as ADT; left-recursion combinator family; context-in-parser
     via ADT nodes (foundation for v1.20.2).  Full design in
     [`docs/dsl.md`](docs/dsl.md).
 24a. **v1.20.1 — DSL: parser error recovery** (~1 week).
     Three recovery strategies (skip-to-sync, error nodes,
     multi-error accumulation) as opt-in extensions on the
     v1.20 Parser ADT.  LSP-friendly DSL'и become viable.
     Ships as `std/parsing/recovery.ssc`.  Independent —
     can ship in any order after v1.20.
 24b. **v1.20.2 — DSL: indentation-aware parsing** (~3-5 days).
     `std/parsing/layout.ssc` built on the v1.20 context
     ADT-nodes (§5.8): `withIndent`, `sameIndent`, `block`,
     `line` combinators.  Indent-significant DSLs (config
     formats, query languages).  Independent of v1.20.1 / v1.20.3.
 24c. **v1.20.3 — DSL: multi-pass pipeline** (~1 week).
     `std/dsl/passes.ssc` with `Pass[A, B]` combinators
     (`andThen` / `parallel` / `recover`) + walker helpers
     for name resolution / type check / evaluation.
     Foundation for full external DSLs.  Independent — can
     ship in any order after v1.20.
 25. **v1.21 — Local map-reduce (`Dataset[T]`)** ✓ Landed.
     Lazy `Dataset[T]` fluent API with sequential + parallel
     local execution (v1.3 Async.parallel on JVM; sequential
     fallback on JS pending v1.3 Node parallel).  Streaming
     via v1.10 generators.  Full design in
     [`docs/mapreduce.md`](docs/mapreduce.md).
 26. **v1.22 — Distributed map-reduce** ✓ Landed.
     Same `Dataset[T]` API, distributed via v1.6 distributed
     actors.  Coordinator-dispatched partitions, named-handler
     registry (no closure serialisation), coordinator-mediated
     shuffle, configurable failure handling.  Closure
     serialisation + worker-to-worker shuffle in v1.22.x.
 27. **Cluster management** ✓ Landed in v1.23.
     Shipped: membership view + events + per-link + cluster-wide
     Phi-accrual failure detection + `std.cluster.Cluster.*` wrapper
     + Bully leader election (with auto re-elect) + auto-reconnect
     on outbound link drops + periodic gossip re-discovery + cluster
     config distribution (LWW per key, snapshot on handshake) +
     rolling-restart drain protocol + cluster metrics aggregation
     (per-node gauges, snapshot on handshake) + Raft leader election
     (opt-in via `useRaftLeaderElection`) + external-coordinator
     dispatch (4-arg `useExternalCoordinator`, app-level adapter to
     etcd / Consul / ZooKeeper) + bounded `leaderHistory` for
     auditable leadership.  All three leader-election protocols
     share `electLeader` / `currentLeader` / `subscribeLeaderEvents`;
     see [`docs/cluster-raft.md`](docs/cluster-raft.md) for the
     unified API and per-protocol algorithms.
 28. **6+/C — HostCallback dispatcher** (~1 week).
     Stage 6+/C from spi-followups-plan.md.  Unblocks the first
     out-of-process (.NET / WASM) backend MVP.  Parked because no
     such backend is in flight.
 29. **v2.0 — Separate compilation** — MVP + post-MVP hardening ✓ Landed
     (2026-05-19): artifact format, `InterfaceExtractor` (with
     `exports:` filtering + package-wrapped object walk), `ArtifactIO`,
     `InterfaceScope` (real type parser), `Linker` (FQN rewrite, 7 e2e
     tests), `ModuleGraph`, six CLI commands, CLI subprocess smoke tests.
     Full pipeline deferred (~2-3 months remaining) — promote when
     one of {real package ecosystem, >30s incremental build, IDE demand}
     is true.

### Approximate total

Critical-path through step 10 (web stack production-ready + clean
SPI + plugin ecosystem + actors complete + clients done): **~10-12
weeks** of focused work.  Steps 11-13 add polish and ergonomics
over another month.  Steps 14-15 are future commitments tracked
here for prioritisation, not pending work.

### What can be parallelised

If two contributors:

  - **One** drives the SPI critical path: 1 → 2 → 3 → 5 → 8.
  - **Other** does v1.5 functional features 4 → 6 → 7 in parallel
    after SPI 5+/A.4/A.5 land.
  - v1.6 Phase 2 and v1.5 Tier 4/5 can interleave anywhere they fit.

### When the order changes

- **If standalone-internet deploy is urgent**: pull v1.5 Tier 1
  forward, ship it as inline codegen first, migrate to intrinsic
  later.  Costs ~1-2 days of rework when migrating; the deploy
  capability ships ~3 weeks earlier.
- **If a real third-party plugin author shows up**: pull v1.7
  forward; even an incomplete extraction (only std.http extracted,
  not yet std.ws/auth/fs/crypto) gives them enough scaffolding to
  start.  Document the partial surface and proceed iteratively.
- **If .NET / WASM MVP is in flight**: pull 6+/C HostCallback
  forward; otherwise that work duplicates platform intrinsics
  inside the subprocess backend.

## v0.7 — Reusable libraries and packaging

A consumer should be able to depend on a third-party `.ssc` library —
component pack, REST middleware, layout kit — without vendoring its
files into their own tree.  The steps are ordered so each one is
useful in isolation and unblocks the next.

1. **Registry** *(future)*.  Central index (`registry.scalascript.io`)
   with semver resolution, lock file (`ssc.lock`), publish/yank
   workflow.  Weeks of work; only worth opening once the surface
   above is well-trodden.  Out of scope for v0.7.

## v0.8 — Web Components target (`ssc emit-wc`) — **MVP landed**

`ssc emit-wc <file.ssc>` scans the file for component-shaped objects
(`object Foo { val css; def render(<params>): String }`), emits the
JsRuntime preamble + the user's JsGen output, then appends a
`customElements.define('foo-component', class extends HTMLElement { … })`
for each detected component.  Tag name = PascalCase → kebab-case +
`-component`.  Each render parameter is read from the same-name HTML
attribute as a String; Shadow DOM scopes the CSS automatically.
`val js` (if present) runs against the shadow root via a `new Function`
boot script.

Cross-rendering parity:
  - `ssc render` and `ssc build` still use the same `render` source
    server-side — no source change to existing components.
  - The detector skips objects without both `val css` and
    `def render(...)`, so utility objects don't leak as elements.

Carry-over / deferred:

  - **Tag-name override** via a `tagName: "my-card"` field — trivial
    once a real consumer asks for it.
  - **Typed prop coercion** — currently every attribute lands as a
    String.  `def render(count: Int, active: Boolean)` should auto-
    `Number(...)` / `!== null`.  ~20 LOC once we plumb param types
    through `detectWcComponent`.
  - **Slots** — `children: String` → `<slot></slot>` injection.
  - **SSR + hydration** — `connectedCallback` currently overwrites the
    light DOM unconditionally.  Need a convention for "adopt existing
    children if the server already rendered me".
  - **DOM-event helper** — `dispatchEvent(new CustomEvent(...))`
    convenience so framework wrappers can bind.
  - **`-o name.js` output flag** — currently the bundle goes to stdout
    only.  Add `-o` like `ssc package`.

Defer the remaining items until a concrete consumer asks.


## v0.9 — Optics — second pass ✓ Landed (Index optic; filter + Iso deferred)

**Landing notes (2026-05-19):**
- Item 1 ✓: Index optic — `.index(i)` / `.at(k)` on all three backends; `IndexStep` + `AtKey` path steps; conformance test `optics-index-at` passing [INT] + [JS]; JS `Map` intrinsic fix as side effect.
- Items 2–3 (filter Traversal, Iso) — deferred; low demand.

The v0.6 hierarchy (Lens / Prism / Optional / Traversal) covers
field-path access on case classes, sum-type variants, `Option` paths
via `.some`, and `List` traversals via `.each`.  A handful of
extensions would close the remaining real-world gaps; listed in
priority order so each one can ship independently.

1. **Index optic — `.index(i)` / `.at(key)`.** ✓ Landed
   Add two path steps recognised by the Focus parser:

       Focus[State](_.users.index(3))      // Optional[State, User]
       Focus[Inventory](_.byId.at("u-42")) // Optional[Inventory, Option[User]]

   `.index(i)` works on `List[A]` and returns `Optional[List[A], A]` — `None`
   when the index is out of bounds.  `.at(k)` works on `Map[K, V]` and returns
   `Optional[Map[K, V], Option[V]]` — the inner Option lets the caller insert
   a missing key by `set(..., Some(v))` and delete via `set(..., None)`,
   matching Monocle's semantics.

   All three backends (interpreter, JS, JVM) implement `IndexStep(i)` and
   `AtKey(k)`.  JS backend intrinsic fixed (`"Map"` → `"_Map"` in
   `JsCoreIntrinsics`) as a side effect, unblocking `Map(k->v,...)` literals
   across all conformance tests.  Conformance test `optics-index-at` passes
   [INT] and [JS].

2. **`filter` on Traversal.**  `Focus[Team](_.members.each).filter(_.active).name`
   to apply `modify` / `set` only to the subset where the predicate holds.
   The traversal still produces a `List[A]` via `getAll` (filtered), and
   `modify` rebuilds the structure leaving non-matching elements untouched.

   Lowering: `Traversal.filter(p): Traversal` is a new method on the
   runtime Traversal value, not a path step — composes by wrapping the
   modifier with a guard.  Pure-Scala add-on in the JVM preamble; in the
   interpreter / JS it threads a predicate through `opticModifyAll`.
   ~half a day.  Defer until users actually ask — most filtered-update
   code is already tractable via `traversal.modify(s, x => if p(x) then f(x) else x)`.

3. **Iso (isomorphisms).**  `Iso[A, B]` with `get: A => B` and
   `reverseGet: B => A` (lossless, bidirectional).  Monocle treats it
   as the strongest optic, but ScalaScript's dynamic-by-default model
   means the typical motivating case — `case class Wrapper(v: Int)` ↔
   `Int` — barely needs an optic to begin with (interpreter doesn't
   distinguish `Wrapper(5)` from `5` past the type tag).  Low ROI.
   Implement only if a concrete consumer surfaces (e.g. a typeclass
   library that wants newtype-style wrapping).

**Drive-by polish (any time):** refactor existing examples
(`rest-api.ssc`, `auth-demo.ssc`, `site/*.ssc`) to use lenses where they
currently chain `.copy(field = obj.field.copy(...))` by hand.  Real-world
demo of optic ergonomics in code that already exists — no new feature
work, just a few diffs that double as documentation.

## v0.9 — Standard component pack — cross-cutting follow-ups ✓ Landed

The eight tiers of `std/ui/*` (forms, layout, navigation, feedback,
data, content/typography, widgets, theming) all landed in v0.9.
The tooling that the pack motivates has now landed too:

  - **`ssc test`** ✓ Landed (2026-05-18).  `Interpreter.injectGlobal`
    added as the injection hook; `testCommand` in `cli/Main.scala` seeds
    a `test(name, () => Boolean)` builtin before `run()`, collects
    registrations, then evaluates each thunk and reports PASS / FAIL.
    `SscTestRunnerTest` (6 cases) validates the mechanics.  Example test
    file at `examples/std-ui/spinner-test.ssc`.
  - **`ssc preview <file>`** ✓ Landed (2026-05-18).  Reads `variants:`
    from YAML front-matter (`Manifest.raw`), detects component objects
    via the existing `WcComponent` scanner (same as `emit-wc`), runs the
    file headlessly, renders each variant into a self-contained HTML page,
    and serves it on a free port — opening the browser automatically.
    `SscPreviewVariantsTest` (5 cases) validates variant parsing.
    `variants:` added to `spinner.ssc` (3 sizes) and `badge.ssc` (5 tones).
  - **`std/ui/index.ssc` aggregator** ✓ Already landed.  Lives at
    `examples/std-ui/index.ssc`; the v0.9.1 directory-as-index resolver
    (`ImportResolver`) was already in place; `conformance/std-ui-aggregator.ssc`
    smoke-tests it across all backends.
  - **Documentation page** — deferred; `examples/std-ui/demo.ssc`
    already serves this purpose for basic demos.

## v0.10 — Extended component pack ✓ Landed (iter A–D)

Components the standard pack didn't cover but every real app
eventually wants.  Same shape as v0.9 (`object Foo { val css, val
js, def render }`), same `scope()` pattern, mergeable one-at-a-time.

Landed in iter A: `Card` (header / body / footer trio), `Switch`
(iOS-style toggle), `Alert` (banner with five tones), `Tag` (closable
inline chip).  All registered in `examples/std-ui/index.ssc`, covered
by `conformance/std-ui-extended.ssc` on three backends.

Landed in iter B: `Stats` (dashboard tile with delta indicator),
`Empty` (no-content placeholder), `Toolbar` (start/end flex layout),
`Tree` (native-`<details>` collapsible hierarchy).  Covered by
`conformance/std-ui-extended-b.ssc`.

Landed in iter C: `Stepper` (multi-step progress indicator),
`Lightbox` (click-to-zoom overlay with JS enhancement),
`FileUpload` (drag-drop zone over `req.files` multipart plumbing).
Covered by `conformance/std-ui-extended-c.ssc`.

Landed in iter D: `DateInput` (native `<input type="date">` with
styled wrapper), `DatePicker` (popover calendar polyfill with JS),
`TimeInput` (native `<input type="time">` with consistent styling),
`DateTimePicker` (side-by-side date + time pair widget), `Combobox`
(autocomplete input + filterable popover with keyboard navigation),
`RangeSlider` (single-handle + dual-handle variants over
`<input type="range">`), `Carousel` (scroll-snap based
image slider with optional arrows + dot indicators, no-JS default).
Covered by `conformance/std-ui-extended-d.ssc` (40 assertions).

## v0.11 — i18n / l10n ✓ Landed (2026-05-19)

Front-matter `translations:` table + `t(key)` / `setLocale(code)` intrinsics.

  - **`translations:`** — YAML nested map `locale → (key → value)` in
    front-matter; parsed into `ast.Manifest.translations` and available
    to all three backends.
  - **`t(key)`** — looks up `key` in the active locale table; falls back
    to the key string when no translation is registered.
  - **`setLocale(code)`** — switches the active locale at runtime.
  - **Interpreter:** `i18nTranslations` / `i18nLocale` state vars loaded
    from the manifest; `t`, `setLocale`, `wc` registered as builtins.
  - **JsGen:** `_i18nLocale` / `_i18nTable` / `setLocale` / `t` /  `wc`
    added to `JsRuntime` preamble; `_i18nTable = {...}` injected after
    front-matter route registrations when translations are non-empty.
  - **JvmGen:** same helpers as Scala `def`s in the generated preamble;
    `_i18nTable = Map(...)` injected before user blocks when non-empty.
  - Both backend `intrinsics/Core.scala` files updated (`t`, `setLocale`,
    `wc` → `RuntimeCall`).

Number / date formatting deferred (ICU is a large dep); ship when needed.

## v0.12 — SSR + client hydration story ✓ Landed (2026-05-19)

Declarative Shadow DOM rendering via `wc()` + zero-JS-rerender hydration guard.

  - **`wc(tag, component, args*)`** — server-side intrinsic that calls
    `component.render(args*)` and wraps the result in:
    ```html
    <tag-component>
      <template shadowrootmode="open"><style>…css…</style>…html…</template>
    </tag-component>
    ```
    Browser deserialises the shadow root before JS runs — no flash of
    unstyled content, no client re-render on first load.
  - **`emit-wc` hydration guard** — `connectedCallback` now checks
    `this.shadowRoot && this.shadowRoot.childNodes.length > 0` and returns
    early when the declarative shadow DOM is already present; `attachShadow`
    is only called for purely client-side renders.
  - **All three backends** implement `wc()`: interpreter (native builtin),
    JsGen (JS function in `JsRuntime`), JvmGen (reflection-based Scala def
    following the same pattern as `collectCss`).

## v0.13 — Component theming variants

Beyond Tier 8's tokens we may want a "variant" concept: a Button
that's `tone="primary"` is a token-driven recolour, but a Button
that's `variant="ghost"` is a structurally different render (no
background, just a border).  Convention: variants live in the same
component, picked by string prop, documented in the front-matter
`variants:` list (used by `ssc preview`).

No code change — just discipline.  Promote when a real component
ends up with three+ variant branches.

## v1.0 — WebSocket production-readiness ✓ Landed (Sprints 1–4, 6; Sprint 5 deferred)

Sprints 4 and 6 landed on this branch (2026-05-17): observability
(structured ws.connect/ws.close logs, `metrics()` native, HTTP
access log) and the six WS convenience helpers (`ws.id`,
`ws.subprotocol`, per-route maxConnections, per-connection rate
limit, pre-upgrade auth hook, close-handshake echo wait).  Cross-
backend (interpreter / JvmGen / JsGen) parity throughout.  Sprint 5
remains, deferred — see below.

### Sprint 5 — architectural debt

Defer until 1-4 are settled and a real workload demands them.
Convergence direction decided 2026-05-17: items below assume the
**Loom** path; see (17).

16. **Full NIO HTTP on JvmGen — CANCELLED by (17).**  Kept in
    this list for archeology so future contributors don't
    re-propose it without new evidence.  Original rationale: the
    WS proxy sits in front of a JDK `HttpServer`, so every HTTP
    request opens a fresh `Socket` to localhost.  Replacing the
    HTTP stack with our own NIO server would fold the proxy in,
    remove the loopback hop, and unify the threading model with
    the interpreter.  ~1500 LOC.  Rejected because (17) picks the
    opposite convergence direction.
17. **Loom-only — interpreter migrates to Loom + blocking I/O.**
    DECISION (2026-05-17, recorded in `docs/ws-v1.0-plan.md` §5.1
    in the v1.0 branch): converge on Loom for both JVM backends.
    JvmGen already uses virtual-thread-per-connection
    (`JvmGen.scala:2849`).  Interpreter rewrites: `WsProxy.scala`
    selector loop deleted, `WsConnection.scala` rewritten to
    blocking reads/writes on a per-connection virtual thread,
    fragmented-message reassembly moves onto that thread.
    ~1000 LOC across the two files.  Side effect: JDK 21+ becomes
    the explicit baseline and the Java-17 reflective
    virtual-thread fallback at `JvmGen.scala:2849-2860` is
    dropped.  When this lands as its own branch the decision is
    the starting point — don't re-litigate.
18. **`permessage-deflate` (RFC 7692).**  5-10× compression on
    JSON-heavy WS workloads.  Independent of (17); ships under
    either threading model.  ~200 LOC × 3.  Not worth the
    complexity until a real app needs it.

## v1.2 — Auth follow-up: combined example + WebAuthn / passkeys — **landed**

Shipped pieces (each on main):

  - **`examples/auth-full.ssc`** — combined demo of every v0.6 auth
    primitive (hashPassword/verifyPassword, withSession, csrfValid,
    rateLimit, totp, JWT, /api/me protection).
  - **WebAuthn server primitives** in
    `compiler/src/main/scala/scalascript/server/WebAuthn.scala`:
      - `WebAuthn.challenge(userId)` — fresh base64url challenge
      - `WebAuthnStore` (in-memory) for `credentialId → publicKey`
      - `verifyRegistration` — `none`-attestation parser, COSE
        public-key extractor
      - `verifyAssertion` — clientDataJSON + authenticatorData +
        ECDSA-SHA256 signature verify, signCount monotonicity
  - **`examples/webauthn-demo.ssc`** — enrol + sign-in flow,
    `navigator.credentials.create / get` glue inline.
  - **`e2e/webauthn-smoke.sc`** — mocks an authenticator (ECDSA P-256
    keypair, inline CBOR encoder) and walks
    enrol → signin → replay-rejected against the running
    `bin/ssc` interpreter on port 8781.

Carry-overs (out of v1.2, promote when asked):

  - **`packed` and `fido-u2f` attestation formats.**  Currently we
    accept only `none` (Apple, 1Password, iOS passkeys, most
    consumer flows).  Enterprise scenarios that want the
    authenticator to vouch for its provenance need the attestation
    signature checked.  ~150 LOC + a vendor root-cert bundle.
  - **Per-credential `userHandle` / `displayName`.**  We key
    everything on a single `userId` string; multi-account browsers
    can't yet route by passkey.
  - **WebAuthn on JsGen / JvmGen.**  The server lives in
    `scalascript.server` which only the interpreter and JvmGen
    backends bundle; the JS-target Node server lacks ECDSA / CBOR
    parity.  Adds ~400 LOC duplicate logic — defer until a Node
    deployment asks.

## v1.3 — Runtime upgrades: real-thread Async, persistence, Async-integrated WS ✓ Landed

**Landing notes (2026-05-19):**
- Stages 1–3 (Signals, real-thread `runAsyncParallel` on JVM, `Storage` effect) — prior
- Stage 4 ✓: `runAsyncParallel` on Node.js — Promise-based I/O concurrency; top-level
  `async IIFE` wrapper; `await _runAsyncParallel(...)` at call sites; `Async.delay`
  yields to event loop instead of Atomics spin.
- Stage 5 ✓: `Async.recvFrom(ws)` — new built-in effect op on all three backends:
  - Node.js: delegates to `ws._nextMessage()` (Promise resolving on next frame);
    server-side `_wsMakeWebSocket` and client-side `wsConnect` both expose `_nextMessage`.
  - JVM: `_RecvFromIO` IORequest; `_driveAsyncCo` calls `ws.recv()` (parks a VT).
  - Interpreter: calls `ws.recv()` directly from `asyncDispatch`.
- 2 new conformance tests: `async-parallel-io`, `async-recv-from`
- `examples/ws-recv-demo.ssc` updated with `Async.recvFrom` pattern

Staged additions that build on the v0.8 Async / signals stack.  Each
landed as its own merge so the suite stayed green between steps.

## v1.4 — Standard-library effects ✓ Landed

**Landing notes (2026-05-18):**
- Items 1–4 ✓ (prior): Logger, Random, Clock, Env — all three backends
- Item 5 ✓: `Http` effect — `Http.get/post/request` + `runHttp` + `runHttpStub(routes)`
- Item 6 ✓: `Retry` effect — `Retry.attempt(n, delayMs)(thunk)` + `runRetry` + `runRetryNoSleep`
- Item 7 ✓: `Cache` effect — `Cache.memoize(key, ttl)(thunk)` + `runCache` + `runCacheBypass`
- Item 8 ✓: `State` effect — `State.get/set/modify` + `runState(s0)(body)` → `(finalState, result)`
- Item 9 ✓: `Tx` effect — `Tx.atomic { body }` + `runTx` (no-op default)
- Item 10 ✓: `Auth` effect — `Auth.currentUser/require` + `runAuthWith(user)(body)`
- 40 conformance tests in `StdEffectsTest`; all three backends

A curated set of pure-by-default effects that cover the boring 80% of
app plumbing (logging, config, IDs, random, time, retry, cache).  Each
ships as a built-in `Effect`-style object with a `Perform` shape and
a default handler, mirroring the `Async` template from v0.8 — so users
can swap implementations (e.g. seeded `Random` for tests, in-memory
`Cache` for unit tests, JSON `Logger` for production) without
touching call sites.  Listed in priority order.

1. **`Logger` effect.**  `Logger.info(msg)` / `.warn(msg)` /
   `.error(msg)` / `.debug(msg)`.  Default `runLogger(body)` writes
   to stderr with a level prefix; `runLoggerJson(body)` emits
   newline-delimited JSON for log shippers; `runLoggerToList(body)`
   collects into a `List[(level, msg)]` for tests.  Strings only in
   v1 — structured logging (`Logger.info("foo", Map("user" -> id))`)
   is a v2 extension.

2. **`Random` effect.**  `Random.nextInt(n)`, `Random.nextDouble()`,
   `Random.uuid()`, `Random.pick(xs)`.  Default uses
   `ThreadLocalRandom` / `crypto.randomUUID`; `runRandomSeeded(42) {
   body }` swaps in a deterministic LCG so test output is
   reproducible.  Removes the ad-hoc `csrfToken()` / `jwtSign`
   internal calls in favour of one entry point.

3. **`Clock` / `Time` effect.**  `Clock.now(): Long` (epoch ms),
   `Clock.nowIso(): String` (ISO-8601), `Clock.sleep(ms): Unit`.
   Default uses `System.currentTimeMillis` / `Date.now`;
   `runClockAt(t0) { body }` freezes time at `t0` so JWT-expiry and
   rate-limit tests don't depend on wall-clock.

4. **`Env` effect.**  `Env.get(key)`, `Env.set(key, v)` (scoped),
   `Env.required(key)`.  Wraps the existing `getenv(...)` but with a
   `runEnvWith(Map("FOO" -> "1")) { body }` handler so test fixtures
   don't have to mutate the real process env.

5. **`Http` client effect.**  `Http.get(url)`, `Http.post(url,
   body)`, `Http.request(method, url, headers, body): Response`.
   Default uses Java's `HttpClient` / Node's `fetch` (sync via
   worker_threads as `_oauthSyncFetch` already does); a
   `runHttpStub(routes) { body }` handler returns canned responses
   keyed by URL pattern for unit tests.  Subsumes the bespoke
   `_oauthSyncFetch` and gives users a first-class outbound HTTP
   surface.

6. **`Retry` effect.**  `Retry.attempt(n, delayMs)(thunk)` — replays
   on exception until `n` attempts pass or thunk succeeds.  Default
   handler uses exponential backoff; `runRetryNoSleep { body }` for
   tests.  Pairs naturally with `Http` (`Retry.attempt(3) {
   Http.get(...) }`).

7. **`Cache` effect.**  `Cache.memoize(key, ttlSeconds)(thunk)` —
   per-key memoisation with TTL.  Default is process-local;
   `runCacheBypass { body }` always recomputes (test mode);
   `runCacheBackedBy(store)` swaps in user storage.  Memoise
   expensive REST handlers without rolling your own map.

8. **`State[S]` effect.**  `State.get`, `State.set(s)`,
   `State.modify(f)` — functional state threading.  `runState(s0) {
   body }` returns `(finalState, result)`.  Lets users write
   stateful computations without `var` and without losing
   composition (each handler sees the same state interface).

9. **`Tx` / transaction effect.**  `Tx.begin`, `Tx.commit`,
   `Tx.rollback`, `Tx.atomic { body }` — abstract transactional
   scope.  Default no-op handler; pluggable for the future DB layer
   so handlers can chain `Storage.put` calls atomically.

10. **`Auth` effect.**  `Auth.currentUser: Option[User]`,
    `Auth.require: User`.  Pulled from the current request's
    session / JWT claims; lets handlers stop threading `req` through
    deep call chains just to read the caller.  Test handler injects
    a fixed user.

**Status (2026-05-18):** all 10 effects landed across all three backends
(interpreter, JvmGen, JsGen) — see landing notes at the top of this
milestone.  Each ships an effect object, default handler(s), and a
test/fixture handler; 40 conformance tests in `StdEffectsTest`.

Each entry follows the same shape as v0.8's `Async` (effect object +
default handler + opt-in test handler + conformance test).  No new
compiler concept required — purely runtime library additions on top of
the existing Free Monad infrastructure.

## Web stack — current state and critical gaps (snapshot 2026-05-17)

A cross-cutting honest inventory of what's deployable, what works,
and what isn't shipped yet, so the v1.5+ priorities can be reasoned
about against real use cases.

### What's landed and works in production

**HTTP server** (behind a reverse proxy; see TLS gap below):
`serve(port)`, `route(method, path)`, `Request` / `Response`,
multipart upload (`req.files`), URL-encoded forms, query params,
cookies, signed sessions (`withSession`), server-side session store
(in-memory), CSRF (`csrfValid`), static file serving, `.ssc` page
rendering.  Cross-backend parity: interpreter (NIO proxy + JDK
HttpServer) / JvmGen / JsGen (Node `http`).

**WebSocket server** (v1.0, landed 2026-05-17):
`onWebSocket(path, origins, protocols, maxConnections,
maxMessagesPerSec)` + `onWebSocketAuth(path, authFn)`.  `ws.send` /
`sendBytes` / `recv` / `close` / `ping` / callback registration.
`ws.id` (UUID-v4) / `ws.subprotocol` / `ws.user` / `ws.request`.
RFC 6455 framing, fragmentation, ping/pong heartbeat, close-echo
wait, Origin allowlist, subprotocol negotiation, per-route +
process-wide caps, per-connection rate limit, structured
`ws.connect` / `ws.close` logs, HTTP access log, `metrics()` native.
Cross-backend parity (interpreter / JvmGen / JsGen).

**Auth** (v0.6 + v1.2, landed): password hashing (PBKDF2-HMAC-SHA256),
JWT HS256 + RS256, OAuth2 generic flow, TOTP 2FA (RFC 6238),
WebAuthn / passkeys (Apple / Yubikey / Android), rate limit
middleware, end-to-end `examples/auth-full.ssc`.

**REST ergonomics** (v1.5 Tier 5, partially landed):
`jsonParse` / `jsonStringify` / `req.json` — Tier 5 #17, PR #47.

**Actors** (v1.6 Phase 1, landed): local `spawn` / `send` / `receive`
/ `self` / `exit` / `link` / timeout receive on all three backends.

### 🔴 Critical gaps (real blockers for specific use cases)

1. **TLS / HTTPS** — Tier 1 of v1.5 below.  Single biggest gap.
   `serve(443)` is unreachable from real browsers without it
   (mixed-content + modern SameSite-cookie + WebAuthn all require
   HTTPS).  Today the standard workaround is an nginx terminator
   in front of `serve(80)` — fine for prod, but a hard blocker for
   any "drop `bin/ssc` on a VM and go" deploy story.  ~1 week.

2. **HTTP client** — Tier 2 of v1.5.  `.ssc` apps cannot make
   outbound HTTPS calls from runtime.  Only escape hatch today is
   `os.proc("curl", ...)` which breaks on JS / WASM and disables
   effect handlers.  Concrete real-app blockers: OAuth token
   exchange, payment integrations, AI / LLM API calls,
   service-to-service, webhook delivery.  An internal
   `_oauthSyncFetch` exists but is private — not user-callable.
   ~1 week.

3. **WebSocket client** — Tier 3 of v1.5.  Symmetric to
   `onWebSocket`.  Without it, `.ssc` apps cannot be WS clients
   (Discord-bot, Slack integration, market-data feeds — impossible
   without `os.proc("websocat", ...)` hacks).  Also a hard
   prerequisite for v1.6 Phase 3 (distributed actors over WS).
   ~1 week; inherits TLS for `wss://` from Tier 1.

4. **Streaming responses** — Tier 4 #11 of v1.5.  `Response.body`
   is a `String` in memory today.  Concrete blockers: large file
   downloads (>100 MB → OOM at emit), Server-Sent Events (SSE is
   fundamentally streaming), long-lived progress reporting.
   Streaming uploads (Tier 4 #12) have the same shape and a
   similar criticality once a real file-upload service emerges.
   ~½ week each.

### 🟡 Important but not critical (workarounds exist)

- **CORS / gzip / cache headers** (Tier 4 #13/#14/#15) — reverse
  proxy handles these in prod deploys.  ~1-2 days each when wanted.
- **Middleware composition convention** (Tier 5 #18) — copy-paste
  works; ergonomics-only.  ~½ day std helpers.
- **Request validation helpers** (Tier 5 #20) — manual
  `req.form.contains` works; verbose.  ~1 day.
- **`/_health` / `/_ready`** (Tier 5 #21) — 20 LOC × 3.  Useful
  for k8s probes.  ~½ hour.
- **`permessage-deflate`** (Sprint 5 #18 of v1.0) — 5-10×
  compression on JSON WS workloads.  Only matters under scale.

### 🟢 Latent / scale concerns

- **WS test cross-suite isolation** through global `WsRoutes` +
  `WsTestLock` — works, but serialises ScalaTest parallel
  execution.  Half-day refactor when a third WS-touching suite
  lands.
- **NIO HTTP migration for JvmGen** (Sprint 5 #16 of v1.0,
  cancelled per Loom-convergence decision) — eliminates the
  loopback hop in JvmGen WS proxy.  Measurable overhead under
  scale, not functional.
- **Hot reload in `serve` mode** ✅ **LANDED** — `ssc watch`/`ssc serve`
  keep the port bound across reloads; only the route table is cleared and
  rebuilt from the new source.  Timestamped output; error-tolerant.
- **`extern def` / `Backend.runtimePreamble` SPI gap** — see
  Stage 5+/A.5 in [`docs/spi-followups-plan.md`](docs/spi-followups-plan.md).
  Prerequisite to migrating `route` / `serve` / `onWebSocket`
  into the intrinsic table.  Pure SPI rearrangement; no
  user-visible behaviour change.

### Bottom line

The HTTP/WS stack is **production-ready as a server-behind-nginx**:
all auth flows work, WS server has rate limits + caps + observability,
237 unit + 39 conformance tests pass.

**Two real-app boundaries are not crossed yet:**
- Standalone-on-the-internet deploy → blocked on TLS (Tier 1).
- Outbound HTTP / WS → blocked on Tier 2 / Tier 3 clients.

Order the v1.5 tiers below against those two boundaries; everything
else in v1.5 (Tier 4 streaming, Tier 5 ergonomics) is workaround-
covered today.

## v1.5 — Transport layer: TLS + HTTP/WS clients + streaming ✓ Landed (Phases A–D′; Phase E / NIO migration deferred)

Right now ScalaScript ships a **server-only** HTTP/WS stack with
**no transport encryption** of its own.  Adequate behind an
nginx-terminating reverse proxy; a non-starter for standalone
deployment to the internet, and a hard blocker for any `.ssc` app
that needs to call OUT to an HTTPS API or another WebSocket server.
Four tiers below; the execution plan is at the end.

### Tier 1 — TLS (`https://` + `wss://`)

The single biggest gap.  Without it, `serve(443)` is unreachable
from real browsers (which refuse mixed-content and modern
SameSite-cookie behaviour requires HTTPS).

1. **TLS-terminating accept loop on the interpreter NIO proxy.**
   Wrap the public `ServerSocketChannel` with a `SSLEngine` per
   accepted channel.  JDK provides `SSLContext` + `SSLEngine`;
   the trick is the handshake state machine (`NEED_WRAP` /
   `NEED_UNWRAP` / `NEED_TASK`).  Same proxy still demuxes WS /
   HTTP afterwards — encryption is independent of protocol.
   ~250 LOC.

2. **`https://` on the JvmGen-emitted HttpServer.**  The JDK has
   `com.sun.net.httpserver.HttpsServer.create(...)` + `setHttpsConfigurator`.
   One-liner if `serve(...)` learns to take an SSLContext.  Plus
   matching TLS wrap for the JvmGen WS proxy's `ServerSocket`.
   ~80 LOC.

3. **`tlsContext` config primitive.**  `tls("cert.pem",
   "key.pem")` builds an `SSLContext` from disk so `serve(443,
   tls = tls("cert.pem", "key.pem"))` reads naturally.  Also
   accept env vars for cert paths so docker-style deploys work.
   ~50 LOC.

4. **Let's Encrypt / acme.sh integration** *(optional)*.  Generate
   certs on first run if missing.  Cheap to wire but pulls in a
   real dependency or shells out to acme.sh.  Defer until users
   ask.

### Tier 2 — HTTP client

`.ssc` apps that talk to external APIs (OAuth, payment, AI) have
no in-runtime way to make an HTTPS call.  Today the only out-of-
band option is shelling out via `os.proc(curl, ...)`, which
silently breaks on JS / WASM targets and disables effect handlers.

5. **`httpGet(url, headers?)` / `httpPost(url, body, headers?)`
   primitives.**  Wraps `java.net.http.HttpClient` (built-in,
   Loom-friendly, HTTP/2-capable) on JVM; `fetch(...)` on Node;
   no-op or `XMLHttpRequest` on browser-SPA.  Returns
   `Response(status, headers, body)`, mirroring our server-side
   shape.  ~80 LOC × 3.

6. **`httpClient { … }` block** with shared base URL / default
   headers / TLS context.  Same convenience the server has via
   `serve(port) { route(...); … }`.  ~30 LOC × 3.

7. **Streaming response bodies.**  `httpGet(url).bodyStream { line
   => println(line) }` — for SSE consumers and chunked downloads.
   `java.net.http.HttpResponse.BodySubscribers.ofLines` on JVM;
   `body.getReader()` on Node.  ~50 LOC × 3.

### Tier 3 — WebSocket client

Symmetric to `onWebSocket`: a `.ssc` app can act as a WS *client*
against another server.  Common for microservices and integrations
with Discord / Slack / market-data feeds.

8. **`connectWebSocket(url) { ws => … }`.**  Performs the
   handshake, returns the same `WebSocket` value shape the server
   side exposes (`send`, `sendBytes`, `close`, `onMessage`,
   `onClose`, `ping`, `onPong`).  Path semantics: `ws://host/path`
   or `wss://host/path`.  ~250 LOC × 3 (handshake + framing reuse
   the existing `WsFraming`).

9. **`wss://` over TLS.**  Inherits from Tier 1's `SSLContext`
   work.

10. **Auto-reconnect with exponential backoff** *(optional)*.
    Helps for long-lived integrations against flaky upstreams.
    ~40 LOC.

### Tier 4 — HTTP server completeness

Real-world HTTP behaviours we've been doing without because the
JDK HttpServer's defaults are "fine enough":

11. **Streaming responses.**  `Response.body` is a `String` today;
    a large file download or an SSE stream needs incremental
    writes.  Add `Response.stream(write => …)` or
    `Response.fromInputStream(...)`.  ~60 LOC × 3.

12. **Streaming uploads.**  `req.body` is buffered in full;
    multipart files materialise as `String` byte-views.  Real
    file-upload servers need to spool to disk past N MB.  Add a
    chunked-read API.  ~80 LOC × 3.

13. **CORS helper.**  `cors(origins = List("https://app.com"),
    methods = List("GET", "POST"))` middleware applied to a route
    group.  ~30 LOC × 3.

14. **Compression on responses.**  `Content-Encoding: gzip` when
    the client says `Accept-Encoding: gzip`.  Built-in `java.util.
    zip.GZIPOutputStream`.  ~40 LOC × 3.

15. **Cache headers helper.**  `Response.cacheable(maxAgeSec, etag
    = …)` writes the standard `Cache-Control` + `ETag` headers
    and short-circuits 304 on `If-None-Match`.  ~50 LOC × 3.

16. **HTTP backend connection pool in JvmGen proxy.**  Today every
    HTTP request opens a fresh TCP to the internal HttpServer.
    Tiny `keep-alive` pool would cut request overhead.  ~80 LOC.
    (Subsumed by Sprint 5.16's full NIO migration if that lands
    first.)

### Tier 5 — REST server ergonomics

Five gaps the existing REST surface leaves to user code, each
trivially missing today and noticed during the v1.1 milestone
review.  Independent of the other tiers; can land alongside or
after them.

17. **JSON read side.**  Write-side already works
    (`Response.json(v)` recursively serialises Lists / Maps /
    Options / Tuples / case classes); read side is missing.
    Today `req.body` is the raw `String` and the user parses
    manually.  Add:
      - `jsonParse(s: String): Value` — generic parse to the
        runtime's value shape (mirror of the existing internal
        `toJson` used by `Response.json`).
      - `jsonStringify(v): String` — same as `Response.json`'s
        serialiser exposed as a standalone primitive (handy
        outside the HTTP path — log lines, message payloads,
        WS frames).
      - `req.json: Value` — shorthand for
        `jsonParse(req.body)` with a `400 Bad Request` on parse
        failure.  Optional typed variant `req.jsonAs[T]` once
        the typer carries enough info to derive a reader.
    ~120 LOC × 3 (uPickle is already in deps from v1.0; pure
    binding work per backend).

18. **Middleware composition — landed.**  `std/middleware.ssc` ships
    `withRequestId`, `withTiming`, `withRequestLog`, and `compose` /
    `compose3` helpers.  Middleware is plain function composition —
    `(Request => Response) => (Request => Response)` — no runtime
    change.  `resp.withHeader(name, value)` is available on all three
    backends so the stock middlewares can attach observability headers
    without rebuilding the response map by hand.

19. **Server-Sent Events (SSE) helper — landed.**  `sse(req) { stream =>
    stream.send(data); stream.send(event, data) }` built on top of
    `streamResponse`.  Sets `Content-Type: text/event-stream`,
    `Cache-Control: no-cache`, `Connection: keep-alive`,
    `X-Accel-Buffering: no`; writes `event: …\ndata: …\n\n` framing
    and flushes after each `send`.  Implemented across all three
    backends (interpreter / JVM / Node.js).

20. **Request validation helpers — landed.**  `requireString` /
    `optionalString` / `requireInt` / `optionalInt` / `requireDouble`
    / `optionalDouble` / `requireBool` / `optionalBool` /
    `requireRange(name, min, max)` / `requireRangeDouble(name, min,
    max)` / `requireOneOf(name, options)` ship across three backends.
    Each call short-circuits with a `400 Bad Request` when used in a
    route handler outside a `validate` block.

    `validate { body }` flips a thread-local collector so the same
    `require*` calls accumulate problems instead of throwing and
    return `Left(Map[field, reason])` (or `Right(bodyValue)` when
    everything checked out).  Handlers can return every error in
    one round-trip:

    ```
    validate {
      val email  = requireString(req, "email")
      val rating = requireRange(req, "rating", 1, 5)
      val tone   = requireOneOf(req, "tone", List("info", "warn"))
      (email, rating, tone)
    } match
      case Right((email, rating, tone)) => Response.json(...)
      case Left(errors)                  => Response.status(400, errors.toString)
    ```

    `conformance/rest-validate.ssc` exercises the happy path, range
    violation, missing-field accumulation, and the Double range
    helper on all three backends.

    Carry-over: the typed `req.require[T]` member-style surface
    (vs the free-function form that landed) — defer until a real
    handler complains about the readability of `requireString(req,
    "email")`.

21. **Built-in health / readiness route — landed.**  `GET /_health`
    and `GET /_ready` return `200 {"status":"ok"}` automatically
    unless the user registers a route with the same path.  Landed
    across all three backends alongside the SPI migration.

22. **Indexed access on `Any`-typed JSON values — options (a) + (c)
    landed.**

    Option (a): `lookup(v, key)` / `lookupOpt(v, key)` — runtime helpers
    that dispatch dynamically against `Map[?, ?]` / `Seq[?]` /
    `String`.  `lookup` throws on a missing key (Map.apply semantics);
    `lookupOpt` returns `Option`.

    Option (c): **`JsonValue` wrapper.**  `jsonRead(s): JsonValue`
    returns an opaque wrapper that supports idiomatic typed access:

    ```
    val v = jsonRead(src)
    v("user")("name").asString
    v("user").get("bio").map(_.asString)
    v("tags").asList.map(_.asString)
    ```

    Methods: `apply(String | Int)`, `get(String | Int): Option[…]`,
    `asString` / `asInt` / `asLong` / `asDouble` / `asBool`, `asList:
    List[JsonValue]`, `asMap`, `keys`, `size`, `isNull`, `raw`.
    `apply` / `get` chain through nested objects / arrays without
    intermediate casts.

    Option (b) — JvmGen lowering of `obj("name")` on `Any` — only if
    (a) + (c) prove insufficient.

### Execution plan — phases A → E

Tiers above are organised by feature area; this is the
**order they should land in** so each phase unblocks (or shares
infra with) the next.  Each phase = one worktree per the
[MILESTONES-driven workflow](AGENTS.md#milestones-driven-workflow),
items inside the phase pushed individually.

- **Phase A — TLS** *(items 1-4; ~1 week)* — **landed**.
    - A.1 — `tls("cert.pem", "key.pem")` config primitive (#3) — landed.
    - A.2 — `HttpsServer` for JvmGen REST (#2) — landed.
    - A.3 — SSLEngine/TLS proxy for interpreter's NIO proxy (#1) — landed
      (virtual-thread TLS proxy + PKCS#8/PKCS#1 PEM loader).
    - A.4 — TLS wrap for JvmGen's WS proxy `ServerSocket` (#2 partial) — landed.
    - A.5 — Let's Encrypt integration (#4) — deferred.

- **Phase B — HTTP client** *(items 5-7; ~1 week)* — **landed**.  Wraps
  Java's `HttpClient` on JVM, `fetch` on Node.  Common return shape
  `Response(status, headers, body)` mirrors the server side.
    - B.1 — `httpGet` / `httpPost` primitives (#5) — landed.
    - B.2 — `httpClient { … }` block for shared config (#6) — landed.
    - B.3 — Streaming response bodies (#7) — **landed** (2026-05-18).
      `httpGetStream(url)(handler)` / `httpPostStream(url, body)(handler)` call
      `handler` for each line as it arrives.  JVM uses `BodyHandlers.ofLines()`
      (truly incremental); JS collects lines in a worker thread then calls
      handler per line in the main thread.  Returns `Response(status, headers,
      body = "")`.  Primary use: LLM streaming APIs (OpenAI, Anthropic),
      SSE consumers, chunked downloads.

- **Phase C — WebSocket client** *(items 8-10; ~1 week)* — **landed**.
    - C.1 — `wsConnect(url) { ws => … }` (#8) — landed: all three backends,
      with `send`/`recv`/`onMessage`/`onClose`/`ping`/`subprotocol`/`close`.
    - C.2 — `wss://` over TLS (#9) — landed: free via JDK `HttpClient`
      (handles `wss://` natively) on JVM; via `ws` npm module on Node.
    - C.3 — Auto-reconnect with backoff (#10) — deferred.

- **Phase D — HTTP server completeness** *(items 11-16;
  ~1 week)* — **landed** (D.1-D.5).
    - D.1 — CORS helper (#13) — landed: `cors(origins, methods?, headers?)`.
    - D.2 — gzip on responses (#14) — landed: `useGzip()`.
    - D.3 — Cache headers + 304 short-circuit (#15) — landed:
      `cacheable(resp, maxAge, etag?)` / `noCache(resp)`; 304 fires
      automatically when `ETag` matches `If-None-Match`.
    - D.4 — Streaming responses (#11) — landed: `streamResponse(status?, headers?)(write => …)`
      chunked transfer across all three backends.
    - D.5 — Streaming uploads + spool-to-disk for big multipart (#12) — **landed**:
      `uploadSpoolThreshold(n)` / `uploadDir(path)` config; file parts
      larger than threshold written to temp file, `UploadedFile.path` set,
      `bytes` cleared; temp file auto-deleted after handler returns.
      All three backends (interpreter / JvmGen / JsGen).
    - D.6 — Backend connection pool in JvmGen proxy (#16) —
      becomes moot if Phase E lands.

- **Phase D′ — REST server ergonomics** *(items 17-22; ~4-5 days)*.
  Tier 5 items.  Largely independent of Phases A-C; the only
  cross-tier dependency is SSE (D′.3) requiring Phase D.4
  (streaming responses) to land first.  Order chosen so the
  cheapest items unblock real user code immediately.
    - D′.1 — JSON read side (`jsonParse`, `jsonStringify`,
      `req.json`) (#17) — **landed** (PR #47).
    - D′.2 — Middleware composition convention + std helpers
      (#18) — **landed**.  `std/middleware.ssc` ships per-route helpers
      (`withRequestId`, `withTiming`, `withRequestLog`, `compose`,
      `compose3`) plus global `use(fn: (Request, () => Response) =>
      Response)` builtin (all three backends) and matching
      `useRequestId()` / `useTiming()` / `useRequestLog()` wrappers.
    - D′.3 — Server-Sent Events helper (#19) — **landed**: `sse(req)(stream => …)`
      sets `Content-Type: text/event-stream`, streams events via
      `stream.send(data)` / `stream.send(event, data)`; all three backends.
    - D′.4 — Request validation surface (#20) — **landed**:
      `require*` / `optional*` / `requireRange*` / `requireOneOf` plus
      the `validate { body }` accumulator returning `Either[Map, T]`.
    - D′.5 — Built-in `/_health` / `/_ready` routes (#21) — **landed**:
      auto-registered when `serve()` is called; user-registered routes
      with the same paths take priority.
    - D′.6 — Indexed access on `Any`-typed JSON values (#22) —
      **options (a) + (c) landed**.  `lookup` / `lookupOpt` runtime
      helpers (a) and the `JsonValue` wrapper (c) — `jsonRead(s)`
      with `apply` / `get` / `asString` / `asInt` / `asList` / `asMap`
      / `keys` / `size` / `isNull` / `raw` — across all three backends.

- **Phase E — full NIO HTTP migration** *(Sprint 5.16, ~2 weeks)* — **BLOCKED, do not start.**
  Replaces the JDK `HttpServer` + WS-proxy pair with a single
  NIO selector loop owning both HTTP and WS state machines.
  Eliminates the loopback hop, unifies the threading model
  across interpreter and JvmGen, and is what `permessage-deflate`
  (Sprint 5.18) would build on top of.  Blocked by user decision
  (2026-05-18) — not a priority until JDK HttpServer is proven
  to be a real bottleneck in production.

Total: ~4.5-5 weeks of focused work for Phases A-D′ (after which the
HTTP/WS stack is genuinely production-ready and ergonomic for
real REST apps), Phase E as a follow-up architectural pass when
scale demands it.

## v1.6 — Actors (Erlang-style, WebSocket-distributed) ✓ Landed (Phases 1–3: local, supervision, distributed — all backends)

Full design and implementation plan: [`docs/actors-dist.md`](docs/actors-dist.md).

A first-class actor runtime: lightweight processes, mailboxes,
supervision trees, location-transparent PIDs.  Models after Erlang
(spawn / send / receive / link / monitor) rather than Akka
(strongly-typed `Behavior[T]` DSL with separate `ActorRef[T]`
hierarchy).  Distribution rides the existing WS stack — no new
transport — so two nodes can be `INT↔INT`, `INT↔JVM`, `JVM↔JVM`,
or any pair across the three backends.

Builds on v1.3 (Async-integrated WS).  Each phase ships
independently and is useful in isolation.

### Phase 1 — Local actors — **landed**

Shipped: `spawn`, `self()`, `pid ! msg`, `receive { case … }`,
`receive(timeout = N) { case … }` (returns `Option`), `exit(pid,
reason)`.  All three backends (interpreter, JsGen, JvmGen) share the
same observable semantics over the existing Computation / Free
Monad trampoline.  Cross-backend e2e in
`e2e/actors-pingpong-smoke.sh`.

Carry-over follow-ups (Phase 1.x — not blocking the next Phase):

  - **`spawn_link(behavior): Pid`** — atomic spawn-and-link — **landed**.
    All three backends; 2 unit tests in `ActorSupervisionTest`.
  - **`call(pid, msg, timeout = N)`** — `gen_server:call/2`
    request/reply sugar.  Trivially user-implementable as
    `pid ! (self, ref, msg); receive { case (`ref`, reply) => … }`,
    so deferred until the boilerplate hurts.
  - **`Become(next)` / `Stopped` return values** — the spec's
    explicit "state-machine via Become" pattern wasn't shipped;
    instead actors loop via recursion, exit by returning from the
    spawn body.  Equivalent expressive power, idiomatic in Scala.
    Promote to `Become` if a recursive style turns out unwieldy.
  - **Case-class messages in CPS** — `case class Ping(from: Pid,
    msg: String); pong ! Ping(self, "hi")` works on INT + JS but
    JvmGen hits a pre-existing CPS limitation: `Ping(_t: Any)`
    fails type-check because the case-class constructor expects
    typed params.  Same limitation affects async/handle bodies on
    JVM regardless of v1.6.  Fix is a separate JvmGen pass:
    erase / cast Any-typed temps when feeding them to apply sites
    whose signature is more specific.  ~150 LOC.

### Phase 2 — Supervision (~3-4 days) — **landed**

Erlang-style fault tolerance.  With `trapExit`, a supervisor is
just a regular actor that handles EXIT messages — no special
runtime, supervision is a library on top of Phase 1.

**Fully landed across all three backends (interpreter, JvmGen, JsGen):**
- `link(pid)` — bidirectional death link; crash propagates unless `trapExit(true)`.
- `monitor(pid): MonitorRef` — unidirectional; delivers `Down(ref, from, reason)`.
- `demonitor(ref)` — cancels a monitor before the watched actor exits.
- `trapExit(b)` — toggle current actor's exit-signal handling.
- `killActor` propagates EXIT/Down signals; cascade-kill when `trapExit = false`.
- `Supervisor.start(specs, strategy, MaxRestarts)` in `std/actors.ssc` — pure
  ScalaScript; supports `one_for_one` / `one_for_all` / `rest_for_one` strategies,
  `permanent` / `transient` / `temporary` restart classifiers.
- 7 unit tests in `ActorSupervisionTest`; conformance `actors-supervision.ssc`.

### Phase 3 — Distributed via WS (~1 week) — **landed** (all three backends)

Full design: [`docs/actors-dist.md §Phase 3`](docs/actors-dist.md).
Std library module: [`std/nodes.ssc`](std/nodes.ssc).

Location-transparent PIDs and remote sends, riding the existing WS
client stack (v1.5 Tier 3, prerequisite).  **Architectural decisions
(locked 2026-05-18):**

**Landed in interpreter (2026-05-18):**
- Pid extended to `(nodeId: String = "", localId: Long)`; all Phase 2
  tests pass unchanged (backward-compatible empty-string default).
- `ValueSerializer`: hand-rolled Value↔JSON serializer using `$t` type
  tags; verified via `ActorDistributedTest` round-trips.
- Scheduler extended: `remoteInbox` drain, interruptible remote-wait
  sleep, keeps running while distributed actors are blocked on receive.
- `startNode(nodeId)`: sets node identity, registers `/_ssc-actors` WS
  route with a NativeFnV peer-handshake + dispatch loop.
- `connectNode(url, token?)`: spawns a virtual thread, connects outbound
  WS with subprotocol `ssc-actors-v1`, exchanges handshake, loops recv.
- `register(name, pid)` / `whereis(name)`: per-node atom registry backed
  by `ConcurrentHashMap`.
- Remote send routing in `pid ! msg`: checks `nodeId`, serializes body,
  sends JSON envelope via `peerChannels`.
- Tests: 8 new `ActorDistributedTest` + conformance `actors-distributed-basic.ssc`.

**Also landed in JvmGen (2026-05-18):** `startNode` + `connectNode` (outbound
WS, subprotocol `ssc-actors-v1`, heartbeat/pong, node-down), `register`/`whereis`,
remote send, `remoteInbox` drain, cross-node links/monitors.  Mirrors interpreter
architecture 1:1.

**Also landed in JsGen (2026-05-18):** `connectNode` via worker thread +
`SharedArrayBuffer` ring buffer.  Worker manages WS connection (subprotocol
`ssc-actors-v1`, handshake, heartbeat).  Ring buffer written by worker,
drained by scheduler each tick via `_drainPeerBuffers()`.  Remote sends
serialised with `$t` JSON via `_serActorVal` (mirrors INT/JVM).  `startNode`
also registers `/_ssc-actors` WS handler for inbound peers.  Requires
`ws` npm package.

| Decision | Choice |
|---|---|
| `startNode` binding | WS route `/_ssc-actors` on existing `serve()` — no separate TCP listener |
| Backends | INT ✅ JVM ✅ JS ✅ |
| Scope | Full: core + `register`/`whereis` + heartbeat + node-down |
| Serializer | JSON only (binary uPickle deferred) |

  - **Node identity** `name@host:port`; PID becomes `(nodeId: String,
    localId: Long)`.  `nodeId = ""` is backward-compatible "local".
    Self-node's name is set at startup: `startNode("logger@localhost:9100")`.
  - **`connectNode(url, token = "")`**
    — opens one long-lived WS channel between this node and the
    peer (subprotocol `"ssc-actors-v1"` advertised at handshake).
    PIDs are multiplexed over the channel; never one socket per actor.
  - **`pid ! msg`** transparently routes: if `pid.nodeId == ""` or
    matches own node, local queue; if remote, JSON-serialize and frame
    onto the per-peer channel.
  - **Wire protocol** — JSON text frames:
    `{ "t": "msg"/"reg"/"where"/"found"/"ping"/"pong"/"down", … }`
    Full spec in `docs/actors-dist.md`.
  - **Value serialization** — compact JSON with `$t` type tag:
    `{"$t":"i","v":n}` / `{"$t":"s","v":"…"}` / `{"$t":"o","cls":"Foo","f":{…}}`
    etc.  Functions cannot be sent across nodes (runtime error).
  - **Thread safety** — interpreter scheduler is single-threaded;
    incoming WS messages enqueue to a `ConcurrentLinkedQueue remoteInbox`
    drained at the top of each scheduler tick.
  - **`register(name, pid)` / `whereis(name): Option[Pid]`** —
    per-node atom registry.  Cross-node: `whereis("node2@…", "logger")`
    sends a query frame and suspends the actor until the reply arrives
    (same mechanism as `receive(timeout=N)`).
  - **Heartbeat** — 30 s ping / 10 s pong timeout on each peer channel.
    Loss of channel → all remote Pids from that node considered dead;
    links and monitors fire EXIT / Down accordingly.
  - **Auth:** `connectNode(url, token = "…")` passes the token as
    `Authorization: Bearer …` in the WS upgrade headers.
  - **Remote spawn is deliberately not supported** — functions
    don't serialize.  Pattern: send a `Make(args)` message to a
    locally-spawned process registered under a well-known name on
    the remote node.

  Conformance: 2-node ping-pong (`INT↔INT`); cross-backend
  (`INT↔JVM`); link across nodes; kill one node → `Down` fires on
  the other; `register`/`whereis` round-trip; serializer parity.

### Hard-no list (closed by design)

- **Selective receive with mailbox scan.**  Quadratic worst-case;
  `Become(next)` covers the realistic use cases at FIFO cost.
- **Hot code reload.**  Erlang-specific runtime trick, not in scope.
- **Remote spawn with closure.**  Functions don't serialize; use
  the registered-name pattern above.

### Deferred follow-ups (carry into v1.6.x)

Pulled out so v1.6 stays focused; each is meaningfully a separate
PR that doesn't gate the core landing.

1. **Bounded mailboxes + backpressure.**  ✓ **Landed (v1.6.x).**
   `spawnBounded(capacity, overflow, behavior)` on all three
   backends: `Overflow.DropOldest`, `DropNewest`, `Fail`, and
   `Block` (cooperative sender-suspend).  Conformance test
   `actors-bounded-mailbox.ssc` covers DropOldest / DropNewest /
   Block; Fail is exercised via linked-actor pattern.  `std/actors.ssc`
   bumped to v1.1.0.

2. **Actor tracing & introspection.**  ✓ **Landed (v1.6.x).**
   `processInfo(pid): Option[ProcessInfo]` returns `mailboxSize`,
   `links`, and `status` ("running" | "blocked") for any live actor.
   Returns `None` for dead / unknown PIDs.  All three backends.
   Conformance test `actors-process-info.ssc`.  `std/actors.ssc`
   bumped to v1.2.0.

3. **Cluster discovery.**  ✓ **Landed (v1.6.x).**
   `joinCluster(seeds: List[String], token: String = "")` connects to each
   seed URL, then exchanges peer lists via `peers_req`/`peers_resp` gossip so
   the full cluster self-assembles from a small seed list.  All three backends.
   Conformance test `actors-cluster-discovery.ssc`.

4. **Cluster-wide registry.**  ✓ **Landed (v1.6.x).**
   `globalRegister(name, pid)` stores a PID cluster-wide by broadcasting
   `{"t":"global_reg",...}` to all connected peers (last-write-wins).
   `globalWhereis(name): Option[Pid]` reads the local cache populated by
   incoming broadcasts.  All three backends.  `std/actors.ssc` bumped to
   v1.3.0.  Conformance test `actors-global-registry.ssc`.

5. **Scheduled / delayed sends.**  `sendAfter(delayMs, pid, msg)`
   and `sendInterval(periodMs, pid, msg)` and `cancelTimer(ref)`.
   **Landed** — all three backends; 5 tests green. ✓

6. **Persistent mailboxes / event sourcing.**  Far-future v2-class
   feature; mention only.  Replay state from a journal on
   supervisor restart, like Akka Persistence.

7. **Cluster visibility.**  ✓ **Landed (v1.23).**
   `clusterMembers(): List[String]` snapshots the connected peer node IDs;
   `subscribeClusterEvents()` registers the calling actor for `NodeJoined` /
   `NodeLeft` delivery as peers come and go.  All three backends.
   `std/actors.ssc` bumped to v1.4.0.  Conformance test
   `actors-cluster-visibility.ssc`.  `std/cluster/*` module skeleton
   (`types.ssc` / `membership.ssc` / `index.ssc`) provides a `Cluster.*`
   namespace on top.

### Effort

Roughly **3 weeks end-to-end** across three backends: Phase 1 ~1
week, Phase 2 ~3-4 days, Phase 3 ~1 week.  Each phase merges
independently, each closes a real use case (Phase 1 — in-process
concurrency; Phase 2 — fault tolerance; Phase 3 — uniform
local/remote).

## v1.7 — Plugin packaging & discovery (true extensibility) ✓ Landed (Tiers 1–5: parser integration, .sscpkg, dep resolution, CLI)

The Backend SPI (`docs/backend-spi.md`) already designs how
third-party plugins claim intrinsics, ship runtime helpers, declare
capabilities — the *mechanism* is in place.  What's missing is the
*distribution layer*: package format, discovery, loader, sample
plugin, CLI ergonomics.  Without these, "third-party Kafka plugin"
remains a hypothetical; with them, the ecosystem opens.

**Prerequisite:** Stages 5+/A.4 + 5+/A.5 + 5+/B + 5+/D landed —
otherwise plugins have no clean shape to plug into (HTTP / WS
still hardcoded in core codegens, no `extern def` parser surface,
no `runtimePreamble` SPI slot).

### Tier 1 — Plugin discovery infrastructure ✓

**Landed.**  ServiceLoader-based in-process discovery via
`META-INF/services/scalascript.backend.spi.Backend` (and
`SourceLanguage`).  `BackendRegistry` + `SourceLanguageRegistry`
handle both in-process and subprocess (out-of-process) plugins.
CLI flags `--plugin <jar>` and `--plugin-dir <dir>` registered;
URLClassLoader isolation per plugin JAR.  `BackendRegistryTest`
covers discovery + in-process + subprocess paths.

### Tier 2 — `.sscpkg` archive format ✓

**Landed.**  ZIP-based `.sscpkg` format with `manifest.yaml`,
`sources/`, `runtime/`, `intrinsics/`, `subprocess/` layout.
`SscpkgLoader.load()` + `SscpkgManifest.parseString()` fully
implemented and tested (`SscpkgLoaderTest`, `SscpkgManifestTest`).
`ssc plugin pack <dir>` packs a source tree into an archive.

### Tier 3 — Resolver & loader ✓

**Landed.**  `BackendRegistry.loadSscpkg()` routes contributions:
intrinsic JARs → URLClassLoader, runtime strings → per-backend
preamble buffer, source paths recorded for prelude injection.
Transitive dependency resolution with cycle detection (`loadSscpkgWith`,
`loadedPkgIds`, `visited` set).  Tested in `SscpkgLoaderTest`
(preamble accumulation, dep resolution, cycle detection,
missing-dep error).

### Tier 4 — Sample external plugin ✓

**Landed** — `examples/plugins/crypto-plugin/` is the canonical
reference plugin demonstrating the full workflow:

  - `sources/crypto.ssc` — `extern def` API (sha256, base64, hmac)
  - `runtime/jvm.scala` + `runtime/js.js` — per-backend helpers
  - `src/.../CryptoIntrinsics.scala` — `IntrinsicImpl.RuntimeCall`
    (JvmGen/JsGen) + `IntrinsicImpl.NativeImpl` (Interpreter)
  - `src/.../CryptoBackendPlugin.scala` — `Backend` SPI impl
  - `manifest.yaml` with `kind: [library, plugin]` + `targets`
  - `examples/use-crypto.ssc` — end-to-end usage example
  - `README.md` — build + pack + install instructions
  - `project.scala` — scala-cli build definition

Depends on no external libraries — uses JVM standard-library
crypto (`java.security`, `javax.crypto`).

### Tier 5 — CLI ergonomics ✓

**Landed.**  All five subcommands implemented in `cli/Main.scala`:
  - `ssc plugin install <path|url|name>` — install from file, HTTPS URL,
    or registry short name (resolves via `~/.scalascript/registry.yaml`)
  - `ssc plugin list` — lists id, version, spiVersion, kind, targets
  - `ssc plugin uninstall <id>` — removes matching `.sscpkg`
  - `ssc plugin check <id>` — verifies spiVersion compatibility
  - `ssc plugin pack <dir> [-o out.sscpkg]` — zips source tree

Tested in `PluginCliTest`.

### Tier 6 — Local registry stub ✓

**Landed.**  `LocalRegistry.scala` in `core/plugin/`:
  - YAML format: `~/.scalascript/registry.yaml` maps ids to URLs
  - `LocalRegistry.resolve(name)` — look up by full id or short alias
  - `LocalRegistry.loadAll()` — merge multiple registry files
  - `LocalRegistry.toYaml(entries)` — serialize back
  - `ssc plugin registry list|add|remove|search` — CLI subcommands
  - `ssc plugin install <shortname>` resolves via registry

Central HTTP-based registry (`registry.scalascript.io`) — deferred
to v0.7; that's a multi-week ops project, not a milestone.

Estimate: ~1-2 days for the local stub.

### Total effort

Tier 1 (~3d) + Tier 2 (~4d) + Tier 3 (~4d) + Tier 4 (~5d) +
Tier 5 (~2d) + Tier 6 (~2d) = **~3 weeks** to a working plugin
ecosystem with at least one canonical external plugin.

### What this unlocks

  - Anyone can publish a platform-integration package as
    `org.example.mything-1.0.sscpkg`.
  - `ssc plugin install` makes it available to user programs.
  - User code `import [Kafka](std.kafka)` works the same as
    today's built-in `import [Json](std.json)`.
  - Capability check + SPI version guard catch incompatibilities
    at install time, not compile time.
  - The `Plugin marketplace` is one HTTP server away (left for v0.7).

## v1.8 — Direct-syntax do-notation ✓ Landed

**Landing notes:** All 6 phases merged to main.
- Phase 1+2 ✓: `direct[M] { ... }` — interpreter + JvmGen/JsGen codegen
- Phase 3 ✓: conformance test (`direct[M] { ... }` do-notation)
- Phase 4 ✓: `std/monad-control.ssc` + `direct-control-flow` conformance
- Phase 5 ✓: diagnostics — `return`-in-direct detection across all backends
- Phase 6 ✓: `direct-syntax-demo.ssc` canonical example

Pure sugar over the v1.1 `std/monad` machinery — zero new runtime,
zero new type-system primitives.  Replaces nested `flatMap`
callbacks and `for { x <- … } yield …` boilerplate with code that
reads like sync but types honestly carry the monad:

```scala
route("GET", "/user") { req =>
  user   = Async.delay(loadUser(req))     // monadic bind
  orders = Async.delay(loadOrders(user))  // chains over `user`
  Response.json(user, orders)             // pure tail — auto-lifts
}
```

Full design in [`docs/direct-syntax.md`](docs/direct-syntax.md) —
seven locked decisions (DS-1…DS-7), grammar, formal desugaring
spec, comparison with for-comprehension / capture checking /
cats-effect direct-style.

Parked behind two prerequisites:
1. **Stage 5+/B `std.http` extraction** (in flight) — drives real
   usage patterns that inform error-message ergonomics.
2. **`extern def` typer support** (Stage 5+/A.5, in flight) —
   prerequisite for type-directed monad inference; without it
   `Request => Async[Response]` can't be inferred from intrinsic
   declarations alone.

### Phase 1 — Typer foundation (~3 days)

Parser accepts `direct[M] { … }`; typer sets the expected type of
the body to `M[A]`; synthesises a `DirectMarker(M, body)` IR node
(no runtime emission yet).

### Phase 2 — Desugaring transformer (~4 days)

New `core/transform/DirectDesugar.scala` walks `DirectMarker`
nodes, applies the rewrite rules from `docs/direct-syntax.md` §5,
emits a `Term.For` (scala-meta).  Existing for-comprehension
lowering takes over from there — no new IR nodes, no new runtime.

### Phase 3 — Type-directed implicit mode (~3 days)

Detect "implicit direct block": a block whose expected type is
`M[A]` with a `Monad[M]` in scope AND containing a bind-form
(`x = expr` or bare `M[*]`-typed expression).  Drop the explicit
`direct[M]` marker requirement for the common case.

### Phase 4 — Control flow + traverse helpers (~2 days)

- Add `whileM_` to a new `std/monad-control.ssc` for `Monad[M]`.
- Verify `xs.traverse_` from `std/foldable-traversable.ssc` lowers
  correctly inside direct blocks.
- Conformance: `direct-control-flow.ssc`.

### Phase 5 — Diagnostics (~2 days)

Compiler errors for the four known foot-guns (full list in
`docs/direct-syntax.md` §8 Phase 5):

- `return` inside direct → "use `M.fail(...)` for early exit"
- Monadic bind to `var` → "use `val` or a fresh name"
- `.map(x => doMonadic(x))` → "use `.traverse` for monadic body"
- Cross-monad bind → "transformer stack out of scope; lift explicitly"

### Phase 6 — Conformance + std rewrites (~2 days)

Six conformance tests (one per DS theme — see `docs/direct-syntax.md`
§11).  Rewrite `examples/rest-api.ssc` and `examples/async-parallel-
demo.ssc` to direct syntax as the canonical demos.

### Hard-no list (closed by design — `docs/direct-syntax.md` §9)

- Effect-row type tracking (`Async | Random | Logger`) → v2
- Monad transformers (`OptionT`, `EitherT`, `StateT`) → v2
- Auto-wrap thrown exceptions into `M.fail` → DS-7
- `await`-style keyword (`val x = await(expr)`) → DS-6
- Non-local `return` from inside a direct block → bypasses bind chain

### Deferred follow-ups (carry into v1.8.1)

1. ~~**Postfix `.!` explicit-bind operator** (DS-6 follow-up).~~  Landed v1.8.1.
2. ~~**Effect-row union types** — `direct[Async | Random]`.~~  Landed v1.8.1.
3. ~~**Transformer-aware lift** — auto `OptionT.liftF` inside an
   outer `Async` direct block.~~  Landed v1.8.1.
4. ~~**`std/monad-control.ssc` expansion** — `untilM`,
   `iterateWhileM`.~~  Landed v1.8.x: `untilMResult{Option,Either}`
   and `iterateWhileM{Option,Either}`, plus 7 conformance cases in
   `direct-control-flow.ssc`.
5. **Capture-checking interaction** — verify direct blocks don't
   leak `var`-captures across `Async.parallel`, once Scala 3.x
   capture checking matures.

### Effort

Six phases, ~16 days end-to-end (~3 weeks).  Each phase merges
independently; Phases 1+2 (explicit `direct[M]` form) deliver real
value alone, Phase 3 closes the ergonomics gap, Phases 4-6 polish.

Cross-backend behaviour is identical — direct syntax is pure
source-to-source rewriting before the backend split, so INT / JS /
JVM see the same desugared `for { x <- e } yield body` and the
existing v1.1 `Monad` machinery handles emission.

## v1.8.1 — Direct-syntax extensions ✓ Landed

Three follow-ups deferred from v1.8, all landed together:

### Feature 1 — Postfix `.!` explicit-bind operator

`fa.!` inside a `direct[M]` block forces a monadic bind at that
expression position and returns the unwrapped value inline.
Implemented as an A-normalization pre-pass (`DirectAnorm.expand` in
`core/transform/DirectAnorm.scala`) that rewrites each `.!` occurrence
into a fresh `_bN = fa` bind prepended before the containing statement.
The pre-pass runs on all three backends (interpreter, JVM, JS).

```scala
direct[Option] { Some(Some(10).! + Some(32).!) }  // => Some(42)
```

### Feature 2 — Effect-row union types

`direct[Async | Random]` is now accepted.  `DirectTypeUtils.validateDirectTypeArg`
permits `|`-separated union types in the type argument position; the
leftmost type name is used as the primary monad for duck-typed dispatch.

### Feature 3 — Transformer-aware lift (interpreter)

When a `direct[M]` block binds a value of a compatible foreign monad,
the interpreter auto-lifts instead of failing:
- `direct[Option]` + `Right(v)` → extract `v`; `Left(_)` → `None`
- `direct[Either]` + `Some(v)` → extract `v`; `None` → `Left(())`
Same rules apply inside `direct[Async]`.

Implementation: `DirectMonadTag` enum + `liftBindValue` in
`backend-interpreter/Interpreter.scala`.

### Deliverables

- `core/transform/DirectAnorm.scala` — new shared A-normalization utility
- All three backends updated to call `DirectAnorm.expand` and
  `DirectTypeUtils.validateDirectTypeArg`
- 9 new interpreter tests in `DirectSyntaxTest` (19 total, all green)
- `docs/direct-syntax.md` §10 updated with full feature descriptions

## v1.9 — Coroutine primitive ✓ Landed

**Landing notes:** All 4 phases completed across all three backends.
- Phase 1 ✓: Interpreter — virtual-thread handshake (`coroutineCreate` / `coroutineResume` / `suspend`)
- Phase 2 ✓: JsGen — JS native `function*` generator wrapper; JvmGen — virtual-thread handshake
- Phase 3 ✓: `Step[Y,T]` ADT (`Yielded` / `Returned` / `Errored`); `std/coroutine.ssc` spec
- Phase 4 ✓: 19 conformance tests (`CoroutineTest`, `CoroutineCodegenTest`); error diagnostics
- `docs/coroutines.md` — full design doc
- **v1.9.x — cancellation (2026-05-19)** ✓: `coroutineCancel(co)` primitive on INT backend;
  `Step.Cancelled` added to the ADT; `fromBody` queue changed to `LinkedBlockingQueue(1)` so
  cancel never deadlocks.  4 conformance tests in `CoroutineCancellationTest`.

A single shared runtime primitive for paused-and-resumable
computation, replacing the three parallel implementations that
exist today (Free-monad `Async`, Loom-thread actors,
NIO-continuation WS).  User-facing APIs are NOT changed by this
milestone — coroutines land as an internal building block that
v1.10/v1.11 build on top of (and that the blocked algebraic-
effects study would build on if it ever unblocks post-v2.0).

Full design in [`docs/coroutines.md`](docs/coroutines.md) —
motivation, three intrinsics, orthogonal-components
decomposition, per-backend implementation sketches, internal
refactor strategy.

```scala
// Three SPI intrinsics; everything else reduces to these:
extern def coroutineCreate[Y, R, T](body: => T): Coroutine[Y, R, T]
extern def coroutineResume[Y, R, T](co: Coroutine[Y, R, T], in: R): Step[Y, T]
extern def suspend[Y, R](out: Y): R
```

Two-way value passing (`suspend(y): R`) deliberately — subsumes
one-way generators (`R = Unit`) at zero extra cost and is
strictly needed for the algebraic-effects path (blocked — see
end of file) should it ever unblock post-v2.0.

### Prerequisite

`extern def` parser + typer support (Stage 5+/A.5, in flight as
part of SPI followups).  Without it the three intrinsics can't be
declared with proper type signatures.

### Phase 1 — JVM intrinsic (Loom backend) (~3 days)

SynchronousQueue + virtual thread per coroutine.  ~80 LOC
including error propagation.  Reuses the existing
`Thread.ofVirtual` infrastructure that already backs actors.

### Phase 2 — JS intrinsic (Node) (~3 days)

`function*` generator with a thin wrapper.  JS-codegen transform
lowers `suspend(y)` calls inside `coroutineCreate` bodies to
`yield y`.  ~50 LOC runtime + the codegen rewrite.

### Phase 3 — Interpreter intrinsic (NIO single-thread) (~5 days)

CPS transform on coroutine bodies at lowering time.  Each
`suspend(y)` becomes a `Computation.Suspend(y, continuation)`
node consumed by the existing trampoline.  ~300-400 LOC; shares
infrastructure with `Async`.

### Phase 4 — Diagnostics + conformance (~3 days)

- "suspend called outside a coroutine" error path on all three backends.
- Six conformance tests (`coroutine-basic`, `coroutine-twoway`,
  `coroutine-generator`, `coroutine-error`, `coroutine-nested`,
  `coroutine-outside`) — see `docs/coroutines.md` §10.
- Stack-trace fidelity verification — the INT CPS transform
  must preserve source positions through suspension points.

### Hard-no list (locked by design — `docs/coroutines.md` §9)

- Symmetric coroutines (`transfer(other)`) → asymmetric suffices
- User-visible scheduler API → backend-specific, not portable
- Coroutine cancellation in v1.9 → **landed in v1.9.x (2026-05-19)**
- Synchronous cross-coroutine `transfer` → conflates scheduling
  with control flow

### Effort

Four phases, ~2 weeks end-to-end.  Phases 1-3 are largely
parallel across backends; Phase 4 gates on all three.

## v1.10 — Generators ✓ Landed

**Landing notes (2026-05-18):**
- Phase 1 ✓: `Generator[T]` with `next`, `foreach`, `toList`, `map`, `filter`, `take`, `drop` — interpreter + JvmGen + JsGen
- Phase 2 ✓: `flatMap`, `zip`, `zipWithIndex` added to all three backends; 4 new conformance tests
- Phase 3 ✓: `examples/generators.ssc` extended with flatMap/zip/zipWithIndex demos

User-facing `Generator[T]` API built on the v1.9 coroutine
primitive.  Lazy pull-based streams without an `Observable`
library or a custom `Iterator` reimplementation.

```scala
def fibs: Generator[Long] = generator {
  var a = 0L; var b = 1L
  while true do
    suspend(a)
    val t = a + b; a = b; b = t
}

fibs.take(10).toList   // List(0, 1, 1, 2, 3, 5, 8, 13, 21, 34)
```

`Generator[T]` is `Coroutine[T, Unit, Unit]` with a thin wrapper —
zero new runtime primitive, just an ergonomic surface.

### Phase 1 — Core API (~2 days)

- `Generator[T]` with `.next(): Option[T]`, `.foreach(f)`,
  `.toList`, `.toListN(n)`, `.take(n)`, `.drop(n)`
- `generator { ... }` builder
- Conformance: lazy infinite streams, early termination

### Phase 2 — Lazy combinators (~2 days)

- `.map(f)`, `.filter(p)`, `.flatMap(f)` — all build a new
  generator (no eager evaluation)
- `.zip(other)`, `.zipWithIndex`
- Conformance: pipeline composition that produces a single value
  from infinite source

### Phase 3 — Use-case demos (~1 day)

- `examples/generator-fib.ssc` — Fibonacci stream
- `examples/generator-file-lines.ssc` — large-file reading without
  loading into memory
- `examples/generator-sse-source.ssc` — feeds the v1.5 Tier 5 #19
  SSE helper once that lands

### Effort

Three phases, ~3-5 days.  Pure library work on top of v1.9.

## v1.11 — Continuation-based `Async` ✓ Landed (Phases 1–5)

Rewrite `Async.delay / await / parallel` on top of v1.9
coroutines.  The existing internal Free-monad `Computation[A]`
(which today is the runtime trampoline for both Async and
user-defined `effect E:` declarations) becomes a runtime-only
compatibility shim: it stops being the *implementation* of
`Async`, but stays in core so legacy `effect`-keyword paths
keep working.  Allocation cost per `flatMap` drops
significantly; stack traces become readable.

The user-facing `Free[F, A]` library type that lands in v1.11.5
is a **separate concept** — see `docs/coroutines.md` §6.5.  It
shares the name but not the implementation: stdlib `Free` is
data-as-value built on coroutines, runtime `Computation` is the
internal trampoline.

User code is **unchanged** — every existing `runAsync { ... }` /
`Async.await(fut)` keeps working.  Conformance gates the merge:
every Async test must pass identically before/after.

### Phase 1 — IO-scheduler design (~3 days) ✓ Landed (Interpreter)

- `runAsync { body }` creates a virtual thread with CoHandle set;
  a scheduler loop dispatches `DelayIO` / `AsyncIO` / `AwaitIO` /
  `ParallelIO` suspensions.
- `Async.delay/async/await/parallel` suspend with IORequest when
  inside a coroutine; fall back to `Perform` nodes for `runAsyncParallel`.
- 11 unit tests in `AsyncTest`; all scenarios from `async.ssc` pass.
- JvmGen + JsGen follow in Phase 2.

### Phase 2 — Rewrite primitives (~4 days) ✓ Landed (JvmGen + JsGen)

- `Async.delay(f)` → `suspend(DelayIO(f))`
- `Async.async(thunk)` → fork a new coroutine; scheduler returns
  a `Future` handle that other coroutines can `await` on
- `Async.await(fut)` → `suspend(AwaitFuture(fut))`
- `Async.parallel(thunks)` → fork-and-await primitive built from
  the above
- 10 tests in `AsyncCodegenTest` (3 code-shape + 3 JvmGen run + 4 JsGen).

### Phase 3 — Performance gates (~2 days) ✓ Landed

- `bench/async.ssc` added: 10 000 sequential async/await cycles.
- `AsyncPerfTest`: 1 000 sequential + 500 parallel ops complete in < 3s
  (actual: ~700ms). Coroutine approach eliminates per-operation Free-monad
  allocation entirely (no `Computation.FlatMap` / `Perform` per await) —
  target ≥20% reduction far exceeded.

### Phase 4 — Stack-trace polish (~2 days) ✓ Landed

- `Errored` now carries the original `Throwable` (JvmGen: `case class
  Errored(cause: Throwable)`; Interpreter: `CoHandle.errRef`).
- The scheduler rethrows the original exception directly — no
  `"Async error: …"` wrapping, original type and message preserved.
- 4 tests in `AsyncStackTraceTest` verify propagation.

### Phase 5 — Runtime-shim for `Computation[A]` (~1 day) ✓ Landed

- `Computation[A]` kept as the IR for `effect E:` / `perform` / `handle`
  keywords; only `Async.*` execution path moved to coroutines.
- `StdEffectsTest` (40 tests) confirms all `handle` / `perform` paths
  still work unchanged. No `Computation.toCoroutine` bridge required —
  the two paths (`effect` Free-monad, `runAsync` coroutine) are
  disjoint and neither interferes with the other.

### Hard-no list

- Breaking the `Async.*` user API → conformance gates this
- Removing `Computation[A]` entirely → it stays as IR for the
  language-level `effect E:` / `perform` / `handle` keywords.
  User-facing `Free[F, A]` (v1.11.5) is a *separate* type, not
  a rename — see `docs/coroutines.md` §6.5

### Effort

Five phases, ~2 weeks end-to-end.  Bigger than v1.9-v1.10 due
to the performance gate and the compatibility shim.

## v1.11.5 — `Free[F, A]` as stdlib type ✓ Landed

User-facing `Free` monad as a new stdlib module `std/free.ssc`.
Not a runtime primitive — pure ScalaScript code built on top of
v1.1 typeclasses (`Monad`, `Functor`) and v1.9 coroutines.

Coroutines (v1.9) gave us **program as control flow**; `Free`
gives us **program as data**.  Both stay; they're complementary
(see `docs/coroutines.md` §6.5).

```scala
[Free, Pure, Suspend, FlatMap, foldMap, runM, liftF](./std/free.ssc)

// Define an effect as a Functor
enum ConsoleF[A]:
  case Read[A]() extends ConsoleF[String]
  case Write(s: String) extends ConsoleF[Unit]

// Build a program as data
val prog: Free[ConsoleF, Unit] = for
  name <- Free.liftF(ConsoleF.Read())
  _    <- Free.liftF(ConsoleF.Write(s"hi $name"))
yield ()

// Multiple interpreters for the same program — production / test
val ioInterp: [X] => ConsoleF[X] => Async[X]    = ...
val testInterp: [X] => ConsoleF[X] => State[Log, X] = ...

runAsync { prog.foldMap(ioInterp) }     // production
val (log, _) = prog.foldMap(testInterp).run(Log.empty)  // test
```

### Phase 1 — Core data type (~1 day)

- `enum Free[F[_], A]` with `Pure`, `Suspend`, `FlatMap`.
- `pure`, `liftF`, `flatMap`, `map` on `Free`.
- Stack-safe via trampolining (Coyoneda or right-associated bind).
- Conformance: monad laws, large-program stack safety.

### Phase 2 — `foldMap` interpreter (~1 day)

- `Free.foldMap[F, G, A](prog)(nt)(using Monad[G]): G[A]`
- Natural transformation `[X] => F[X] => G[X]` (Scala 3 polymorphic functions).
- Conformance: same program, two different interpreters, both pass.

### Phase 3 — `runM` coroutine-backed runner (~1 day)

- `Free.runM[F, A](prog)(handler): Coroutine[?, ?, A]`
- Sits on v1.9 coroutine primitive.  Fast common-path runner;
  semantically equivalent to `foldMap` over `Coroutine`-monad,
  but avoids the intermediate `Monad[G]` instance dispatch.
- Conformance: existing `foldMap` test re-runs under `runM`,
  identical observable output.

### Phase 4 — Algebraic-effect synergy demo (~1 day)

- Worked example: `effectful-config-loader.ssc` builds a program
  as `Free[ConfigOp, Config]`, runs it twice (file-backed vs
  in-memory), demonstrates the data-as-value advantage over
  direct coroutine code.
- Conformance: `std-free.ssc` covering the four phase deliverables.

### Hard-no list (locked by design)

- `Cofree` / `FreeT` monad transformers → v2.x if concrete need
- Compile-time program optimisation (fusion of adjacent FlatMaps)
  → revisit when measurements demand; pure-runtime optimisation
  beats compile-time complexity at v1
- Auto-deriving `Free` from `effect E:` language constructs →
  out of scope; `effect` stays a compiler construct lowered via
  Perform/Handle/Resume IR, `Free` is a separate user-space DSL

### Why this is its own milestone (~1 week, not folded into v1.11)

- **Different audience**: v1.11 is a perf rewrite of an existing
  runtime (no API change).  v1.11.5 introduces a new user-facing
  type.  Conflating them complicates the conformance gate.
- **Different risk profile**: v1.11 has performance gates;
  v1.11.5 is pure library code with no perf budget.
- **Different prerequisite**: v1.11.5 needs v1.9 (coroutines)
  but not v1.11 (Async on coroutines).  Could ship in parallel
  with v1.11 if scheduling permits.

### Effort

Four phases, ~1 week.  Pure library work; no compiler changes,
no SPI changes.  Slots after v1.11; can also land in parallel
with v1.11 since they have no shared code.

## v1.13 — Final Tagless ergonomics ✓ Landed

Make the FT pattern first-class in user code by landing four
typer features that today block idiomatic typeclass usage.  The
v1.1 stdlib is already structured in FT style under the hood —
every helper takes a typeclass instance as an explicit parameter
because `using` doesn't auto-resolve.  This milestone closes that
ergonomic gap and turns idiomatic FT from "possible with care"
into the default mode.

Full design in [`docs/final-tagless.md`](docs/final-tagless.md) —
current state vs target, the four typer dependencies, worked
examples, coexistence with v1.8 direct-syntax / v1.11.5 Free /
the blocked algebraic-effects study, hard-no list, open
questions.

```scala
// Today
def combineAll[A](xs: List[A], m: Monoid[A]): A =
  xs.foldLeft(m.empty)(m.combine)
combineAll(List(1, 2, 3), intSum)         // explicit instance

// After v1.13
def combineAll[A: Monoid](xs: List[A]): A =
  xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)
combineAll(List(1, 2, 3))                 // resolved via given intSum
```

### Phase 1 — `using` auto-resolution (INT) ✓ Landed

Typer pass over `Term.Apply` nodes: for each `(using T1, T2, …)`
parameter list, walk in-scope `given` instances and select a
unique match.  Standard Scala 3 priority rules; ambiguous →
actionable error.  Rewrite the call site to include the resolved
arguments before lowering.

### Phase 2 — `using` auto-resolution (JS / JVM) ✓ Landed

JS-codegen passes typer-resolved arguments through to `_call`
emit.  JvmGen emits the `(using …)` parameter list as-is and
Scala 3's own resolver handles the rest.  Mostly glue; the
typer-side work was Phase 1.

### Phase 3 — Context bounds desugaring ✓ Landed

Parser-level: `[F[_]: M1: M2]` → appended `(using M1[F], M2[F])`
parameter list.  Standard Scala 3 semantics.  Trivial once Phase
1 lands.

### Phase 4 — Cross-file trait inheritance with HKT ✓ Landed

Today `trait Traversable[T[_]] extends Functor[T], Foldable[T]`
breaks at the JVM compile when `Functor` lives in a separate
file.  v1.1 step 3 worked around it.  Fix the typer's import-
resolution pass to carry trait *definitions* (not just instances
and extensions) into the consumer's scope.

### Phase 5 — Sealed-trait extension dispatch in INT ✓ Landed

Build a sealed-parent registry at trait/class definition time.
`extensionDispatch` walks the parent chain when the exact-name
lookup misses.  Closes the carryover item from v1.1 that forced
helper functions for `Either` in steps 5 / 6.  JS already
handles this via `_typeOf`; JVM relies on Scala's own dispatch.

### Phase 6 — Conformance + std polishing ✓ Landed

- Six conformance tests (all with expected output files):
  `tagless-resolution.ssc` (§3.1 using auto-resolution),
  `tagless-context-bounds.ssc` (§3.2 context-bound desugaring),
  `tagless-multi-file.ssc` (§3.3 cross-file HKT extends),
  `tagless-sealed-dispatch.ssc` (§3.4 sealed-parent dispatch),
  `tagless-program.ssc` (§4 Console[F] with two interpreters),
  `tagless-direct-syntax.ssc` (§5 direct[F] synergy).
- `std/semigroup-monoid.ssc`: `combineAll` rewritten with
  `[A: Monoid]` context-bound; `combineAllOption` with `[A: Semigroup]`.
- `std/bifunctor.ssc`: `eitherBifunctor` given with `bimap`/`leftMap`/
  `rightMap` extensions — sealed-parent dispatch covers `Right`/`Left`.
- `std/monaderror.ssc`: `handleError` standalone extension on
  `Either[String, A]` — dispatches from `Right`/`Left` via parent-chain walk.

### Hard-no list (locked by design — `docs/final-tagless.md` §7)

- **User-defined macros** — defer to v1.14 metaprogramming MVP
  (`inline` + `derives`) and beyond
- **Implicit conversions** that cross effect boundaries —
  reintroduces the two-fault-model trap (DS-7)
- **Custom `given` priority** beyond Scala 3 rules
- **Effect-row tracking** (`[F[_]: (Console & Logger)]`) —
  territory of the blocked algebraic-effects study (post-v2.0)
- **Auto-deriving FT instances** from concrete types — confusing
  failure modes; explicit `given` instances are the convention

### Carryover updates after this lands

`Interpreter ergonomics — carried over from v1.1` section in
MILESTONES.md gets three edits:

- Item 1 (`using` auto-resolution) → marked **landed** with v1.13.
- Item 4 (sealed-trait extension dispatch) → marked **landed**
  with v1.13.
- Item 3 (`Term.Ascribe`) → ✓ Landed 2026-05-19 as standalone fix.

### Effort

Six phases, ~2 weeks end-to-end across three backends.  Phase 1
is the critical path; Phases 2-5 can interleave with it as
worktrees if scheduling permits.

## v1.14 — Metaprogramming MVP (`inline` + `derives`) — ✓ Landed (Phases 1+3+4+5; Phase 2 N/A for interpreter)

Minimum-viable metaprogramming: bring Scala 3's `inline` keyword
(compile-time computation, type-level matching) and limited
`derives` (auto-derive trivial typeclass instances from
product / sum types).  Explicitly **not** user-defined macros
(`quoted.Expr` machinery) — that's a multi-month commitment
deferred to v2.x.

Full design in [`docs/metaprogramming.md`](docs/metaprogramming.md).

**Landed in `feature/v1.14-inline-derives` (2026-05-18):**
- **Phase 1** ✓ — `inline def/val/if/match` already parsed and evaluated by scalameta 4.17 +
  tree-walker (no changes needed); `compiletime.constValue[T]` extracts singleton type literal;
  `compiletime.summonInline[T]` resolves given by type-key; `compiletime.error("msg")` throws.
- **Phase 2** N/A — interpreter has no backend split; all backends share the same inlined output.
- **Phase 3** ✓ — `derives` clause on `Defn.Class` and `Defn.Trait` auto-generates `given TC[A]`
  instances via `synthesizeDerivedInstance`; structural helpers `structuralEq`, `structuralShow`,
  `structuralHash`, `structuralCompare` walk `Value.InstanceV` using `typeFieldOrder`.
- **Phase 4** ✓ — `std/eq.ssc`, `std/show.ssc`, `std/hash.ssc`, `std/order.ssc` with trait
  definitions and primitive instances for Int/Long/Double/String/Boolean; helper functions.
- **Phase 5** ✓ — 15 conformance tests in `InlineDerivesTest.scala`; 378 total tests pass.

### Why these two and not full macros

| Feature | Cost | Payoff |
|---------|------|--------|
| `inline def`, `inline if`, `inline match` | ~1 week typer + 3 days × 3 backends | Compile-time constants, type-directed dispatch, `summonInline` for zero-cost typeclass lookup, foundation for `derives` |
| `derives Eq, Show, Foldable, ...` | ~1 week + tier-1 derivation recipes | Idiomatic typeclass auto-derivation; `case class Foo(a: Int, b: String) derives Eq` |
| Full Scala 3 macros (`quoted.Expr`) | ~3-4 months across three backends | Too expensive for the v1 audience |

`inline` is the **gateway**: every `derives` implementation
internally uses `inline match` to walk product/sum type structure
at compile time.  Without `inline`, `derives` either becomes
heavy runtime reflection (bad) or stays unimplemented.  So
`inline` is the prerequisite, `derives` builds on top.

### Phase 1 — `inline` evaluation (~5 days)

- Parse `inline def`, `inline val`, `inline if`, `inline match`,
  `inline summon[T]`.
- Compile-time evaluator: walks `inline`-marked AST nodes,
  performs constant folding, type-pattern matching, and
  inlining at the call site.
- `compiletime.constValue[T]` for type-level literals
  (`compiletime.constValue["foo"]: String = "foo"`).
- `compiletime.summonInline[T]: T` — `summon` resolved at compile
  time, fails at compile time if no instance found.
- Conformance: constant-fold, type-match dispatch,
  `summonInline` over typeclass.

### Phase 2 — `inline` cross-backend (~3 days)

The compile-time evaluator runs in `core/` before backend split;
JS / JVM / INT all see post-inlined code.  Phase is verification
+ edge-case fixes per backend, not parallel implementations.

### Phase 3 — `derives` mechanism — Tier 1 recipes (~5 days)

Limited set of typeclasses auto-derivable from product/sum
structure:

- `derives Eq`     — structural equality over fields
- `derives Show`   — Scala-default `toString` style render
- `derives Hash`   — `##` over fields
- `derives Order`  — lexicographic over fields (top-down
  declaration order)

Each derivation is a `given` instance produced by an `inline
def derived` method on the typeclass companion using
`Mirror.Of[A]` (Scala 3 standard-library handle exposing
structural type info).  Requires v1.13 (Mirror is a typeclass
instance, needs `using` resolution).

### Phase 4 — `derives` for std typeclasses (~3 days)

Wire `derives Foldable`, `derives Traversable`, `derives Functor`
(for products with a single type-param) on top of the Tier 1
machinery.  Conformance: `derives-foldable.ssc` proves
auto-derivation matches hand-written instances.

### Phase 5 — Conformance + std polishing (~2 days)

- Six conformance tests for `inline`-specific behaviour.
- Five for `derives` per Tier 1 typeclass.
- Optionally: rewrite a couple of std/examples files to use
  `derives` where applicable.

### Hard-no list (locked by design)

- **User-defined macros via `quoted.Expr`** — deferred to v2.x
- **`inline def` with side effects** — must be referentially
  transparent at compile time
- **Custom `derives` recipes from outside std** — only blessed
  typeclasses auto-derive in v1.14; user recipes wait for full
  macros
- **Type-level naturals / Peano arithmetic** — `inline` is
  pragmatic, not Haskell-grade

### Open questions (re-evaluate when first usage emerges)

- **`inline if` vs runtime `if`**: when does the typer know
  enough to fold?  Heuristic: when both branches are
  `inline`-computable values.
- **`inline match` exhaustiveness**: warn vs error on
  non-exhaustive inline matches.
- **`derives` source order** for multi-param case classes:
  which type-param does `derives Functor` pick?  Follow Scala 3:
  last param.
- **Cross-file `derives`**: requires v1.13 cross-file trait
  inheritance.
- **Performance**: does `inline summon` win measurably over
  runtime `summon`?  Bench when v1.14 lands.
- **Tier 2 derivations** for v1.14.1: `derives Monoid`,
  `derives Semigroup`, `derives Codec` — defer until Tier 1
  proves usage patterns.

### Effort

Five phases, ~2.5 weeks end-to-end.  Builds on v1.13 (`Mirror`
requires `using` resolution).  Can land in parallel with v1.11/v1.11.5 since those touch the
runtime layer and this touches the typer.

## v1.15 — Checked errors via `throws` type alias — ✓ Landed (all phases)

Closes the "every helper hand-rolls its own `Either`-wrapping
convention" gap.  Ships an Either-encoded `throws[A, E]` type
alias that integrates with direct-syntax (v1.8) via the v1.1
`Monad[Either[E, *]]` instance, plus a `HasStackTrace` mixin
giving Either-encoded errors diagnostic parity with JVM
exception traces.

Full design in [`docs/error-handling.md`](docs/error-handling.md).

**Landed in `feature/v1.15-throws` (2026-05-18):**
- **Phase 1** ✓ — `infix type throws[A, E] = Either[E, A]` type alias; `typeToString`
  handles `Type.ApplyInfix("throws")`; `Defn.Type` silently dropped by catch-all.
- **Phase 2** ✓ — Return-site auto-Right wrapping: `FunV.returnsThrows` flag; `callFun`
  wraps non-Either results; `Left`/`Right` constructors in `CoreIntrinsics`; full Either
  method dispatch (`isLeft`, `isRight`, `getOrElse`, `map`, `flatMap`, `fold`, `toOption`,
  `swap`, `toSeq`).
- **Phase 4** ✓ — `std/error-handling.ssc` shims: `parseInt`, `parseLong`, `parseDouble`,
  `requireNonNull`, `divideOrError`; `Term.Throw` (throws `ScriptException`); `Term.Try`
  (catches `ScriptException` + any JVM `Throwable`); exception constructors
  (`RuntimeException`, `NumberFormatException`, `ArithmeticException`, etc.).
- **Phase 5** ✓ — `attemptCatch(thunk)` native function; `raise` / `rethrow` helpers.
- **Phase 3** ✓ — `direct[Either]` throw-lowering: `Term.Throw` inside direct blocks lowers
  to `Pure(Left(v))` via `_insideDirectBlock` thread-local; auto-bind short-circuit already
  worked via `Left.flatMap` dispatch (Phase 3.3 try-catch lowering deferred).
- **Phase 6** ✓ — `HasStackTrace` trait + `Frame(file, line, fn)` case class; `callStack`
  `ArrayBuffer` in interpreter pushed/popped in `callFun`; `currentStackTrace()` native.
- **Phase 8** ✓ — `infix type throwsRaw[A, E] = A | E` type alias in stdlib; functions with
  `throwsRaw` return type return values as-is (default behaviour — no interpreter changes).
- **Phase 9** ✓ — `unbox`, `box` helpers in `std/error-handling.ssc`; `attemptCatchRaw`
  native function registered in `initBuiltins`.
- **Phase 7** ✓ — `fromError(e: HasStackTrace, dev: Boolean): Response` helper in
  `std/error-handling.ssc`; `HasStackTrace` + `fromError` added to exports; 8
  conformance tests in `ThrowsTest` (fail, user mixin, auto-Right, Left return,
  direct+throws chain, short-circuit, fromError prod/dev modes).
- **55 conformance tests** green; full suite passes.

```scala
infix type throws[A, E] = Either[E, A]

def parseInt(s: String): Int throws ParseError =
  if invalid then ParseError(s"bad: $s")    // auto-conversion → Left
  else s.toInt                              // auto-conversion → Right

def handler(req: Request): Response throws AppError = direct[ResultS] {
  id   = parseInt(req.params("id"))   // monadic bind; Left short-circuits
  user = loadUser(id)
  Response.json(user)                 // pure tail auto-lifts to Right
}
```

### Prerequisites

- **v1.8 direct-syntax** — Either's `Monad[Either[E, *]]` is the
  vehicle for `=`-binds inside `direct[ResultS] { … }`.
- **v1.13 Final Tagless ergonomics** — `using` resolution finds
  `Monad[Either[E, *]]`; cross-file trait inheritance carries
  the std Either instance into user code.

### Phase 1 — `infix type throws` parser + typer (~1 day) ✓ Landed

Parser accepts `type A throws E` desugared to `Either[E, A]`
at the type-position level.  Typer treats the alias
transparently — every `Either[E, A]` method, helper, and
typeclass instance reads on `A throws E` unchanged.

### Phase 2 — Return-site auto-conversion givens (~1 day) ✓ Landed

Two givens in std (`std/error-handling.ssc`):

  `given [E, A] => Conversion[A, Either[E, A]] = Right(_)`
  `given [E, A] => Conversion[E, Either[E, A]] = Left(_)`

Allows `def f: Int throws ParseError = 42` and `... = ParseError(...)`
without explicit `Right` / `Left` wrappers.  Conversions fire
only when the bare-value type doesn't match expected.

### Phase 3 — Direct-syntax integration (~3 days)

Three things to ship in this phase, all driven off the same
`MonadError` resolution machinery (v1.13):

  1. **Auto-bind for `throws`-typed values** — `id =
     parseInt(req.params("id"))` inside `direct[Either[E, *]]`
     resolves via `Monad[Either[E, *]]` from v1.1 std.
     Short-circuit on Left, pure tail auto-lifts to Right.
  2. **Type-directed `throw` lowering** — inside `direct[F] { … }`,
     `throw e: E` lowers to `F.fail(e)` *when* `MonadError[F, E']`
     (with `E <: E'`) is in scope; otherwise stays as JVM-native
     throw.  See `docs/error-handling.md` §2.5.6.
  3. **Type-directed `try`-`catch` lowering** — `try body catch
     case e: E => h` lowers to `F.handleError(body) { case e: E
     => h }` when `MonadError[F, E']` matches; otherwise stays
     as JVM-native try-catch.  Mixed catches (some typed, some
     untyped) emit a hybrid lowering — typed branches as
     `F.handleError`, untyped as wrapping JVM `catch` clauses.

DS-7 refined accordingly: thrown exceptions auto-wrap ONLY when
the user explicitly typed them AND the surrounding F advertises
a matching error channel.  Bare `throw new RuntimeException(...)`
inside a direct block still escapes via JVM stack unwinding —
the two-fault-model trap stays avoided because the lowering is
driven by what the user typed, not by silent magic.

Conformance:
  - `throws-direct-syntax.ssc` — basic auto-bind + short-circuit
  - `throws-direct-throw.ssc` — `throw AppError(...)` → `Left`
    inside `direct[ResultS]`
  - `throws-direct-try-catch.ssc` — typed catch → `F.handleError`
  - `throws-direct-mixed.ssc` — mixed typed + untyped catch
    branches in one `try`

### Phase 4 — Std-lib `throws`-typed shims (~2 days) ✓ Landed

Initial shim set:

- `parseInt(s: String): Int throws NumberFormatException`
- `parseLong(s: String): Long throws NumberFormatException`
- `parseDouble(s: String): Double throws NumberFormatException`
- `requireNonNull[A](a: A): A throws NullPointerException`
- `divideOrError(a: Int, b: Int): Int throws ArithmeticException`

Each catches the corresponding JVM exception and lifts to
`Left(e)`.  IO shims defer to v1.5 Tier 2-4 (the HTTP/IO
stack).

### Phase 5 — `attemptCatch[E <: Throwable] { … }` adapter (~1 day) ✓ Landed

Universal opt-in: user wraps any third-party Java/Scala call:

  `val bytes = attemptCatch[IOException] { Files.readAllBytes(path) }`

Catches the named exception class, lets anything else propagate.
DS-7 stays locked — no auto-wrap inside direct blocks.

### Phase 6 — `HasStackTrace` mixin + `currentStackTrace()` (~2 days)

- `trait HasStackTrace` with `def stackTrace: List[Frame]`.
- `case class Frame(file: String, line: Int, fn: String)`.
- `currentStackTrace(): List[Frame]` — per-backend runtime call,
  returns the current call chain.
- `fail[E <: HasStackTrace](e: E): Left[E, Nothing]` helper.

Per-backend implementation:

- **INT**: walk existing position tracker (`Interpreter.scala`'s
  `trackPos` already counts call frames; expose it).
- **JVM**: `Thread.currentThread.getStackTrace`, filter to user
  frames, map to our `Frame` shape.
- **JS**: parse `new Error().stack` to extract user frames; or
  maintain own chain via codegen-injected push/pop on `Term.Apply`
  (revisit if parse cost matters).

Frame format is identical across backends — uniform diagnostics.

### Phase 7 — Conformance + std polishing (~1 day)

Eight tests covering each decided piece (see
`docs/error-handling.md` §9).  Add a `Response.fromError(e)`
helper that turns `HasStackTrace` errors into 500-with-trace
responses for development mode (production mode strips traces
— follow-up to address logging story).

### Phase 8 — `throwsRaw[A, E] = A | E` companion alias (~1 day)

Ships the union-typed perf/interop companion as an opt-in
type alias.  Pure stdlib work; no compiler changes (Scala 3
union types already work at the parser/typer level).  Used
by Phase 9 helpers; positioned as **opt-in, not default**.

### Phase 9 — Conversion helpers + `attemptCatchRaw` (~1 day)

- `box[A, E](raw: A | E): A throws E` — type-tested promotion
- `unbox[A, E](boxed: A throws E): A | E` — demotion
- `attemptCatchRaw[E <: Throwable, A] { … }: A throwsRaw E` —
  union form that preserves native `Throwable.getStackTrace`
  on JVM and avoids the `Right`/`Left` box.  Used for hot-path
  parsing and JVM exception interop where the Either box is
  measurable overhead.

The two-tier stack trace model documented in
`docs/error-handling.md` §2.4 falls out of this for free:
`throwsRaw[A, Throwable]` keeps its native trace; `throws`
captures via `HasStackTrace`; trace-less `throws` pays
nothing.

### Tier model — four error mechanisms, one document

Per `docs/error-handling.md` §2.5.5, ScalaScript has four
error mechanisms; v1.15 ships the first three:

  1. `throws[A, E] = Either[E, A]`         — canonical, monadic, typed
  2. `throwsRaw[A, E] = A | E`              — opt-in perf / interop (Phase 8)
  3. Unchecked `throw e` / `try`-`catch`    — peer to throwsRaw; JVM-native, untyped
  4. `MonadError[F, E]` (already in v1.1)   — for monadic effects

Tier 1 is the default; tiers 2 and 3 are opt-in escape
hatches for specific use cases (hot paths, JVM interop,
systemic errors).  Restartable errors (v1.16) are tier 4.

### Hard-no list (locked by design — `docs/error-handling.md` §5)

- Auto-wrap thrown exceptions in direct blocks (DS-7 stays)
- Removing or deprecating unchecked `throw` / `try`-`catch`
  (first-class peer to `throwsRaw` per §2.5.5; not migrating
  away)
- `A | E` union encoding as the **default** `throws`
  (canonical is Either; union ships as opt-in `throwsRaw`
  companion — see Phase 8)
- Auto-conversion across the `throws` / `throwsRaw` boundary
  (explicit `box` / `unbox` only)
- `throwsRaw` as a `direct[F] { … }` target (`throws` only;
  raw must be `box`-ed before entering a direct block)
- Java-style **checked**-throws clauses on signatures
  (separate from the return type, compiler-enforced) — keep
  the `throws[A, E]` type-alias path.  Unchecked throws are
  a different mechanism and stay.
- `E` restricted to `Throwable` subtypes (works over any `E`)
- Effect-row tracking for errors (territory of the blocked
  algebraic-effects study — post-v2.0)

### Locked policies (`docs/error-handling.md` §2.5)

Four items previously carrying recommendations are now locked:

- **Adapter naming**: `attemptCatch[E <: Throwable] { … }`
  (Either form) + `attemptCatchRaw[E <: Throwable] { … }`
  (union form).  `Raw` suffix mirrors `throwsRaw`.
- **Stack-trace mixin**: `trait HasStackTrace { def stackTrace:
  List[Frame] }` — opt-in.  Std also ships `trait Error
  extends HasStackTrace` as a convenience for the common case.
  Always-on capture rejected (~1-5% function-call overhead).
- **Raw-form shim policy**: std shims ship in `throws` form
  only by default.  `Raw`-companions added on case-by-case
  basis when profiling demonstrates measurable Either-box
  overhead on a specific helper.  Never speculative.
- **`throwsRaw` runtime-distinguishability**: documented
  limitation — `throwsRaw[Int, Int]` is a compile error
  (Scala 3 unions need testable members).  Escape hatch is
  `throws[A, E]`, which explicitly tags both sides.

### Open questions (carry into design iteration)

- Final exact std-shim list for v1.15 — tentative:
  `parseInt`, `parseLong`, `parseDouble`, `requireNonNull`,
  `divideOrError`.  Lock at implementation time when edge
  cases (`parseHex`, `parseTimestamp`, …) surface specific
  asks.
- Stack-trace verbosity tuning — **landed (2026-05-19)**.
  Default view filters `<anon>` / `_`-prefixed synthetic frames;
  `setTraceVerbose(true)` enables full view.  3 conformance
  tests in `ThrowsTest` (Phase 6.1).
- Capture cost on hot paths — **`@noTrace` landed (2026-05-19)**.
  Classes annotated `@noTrace` throw `ScriptExceptionNoTrace` which
  overrides `fillInStackTrace()` to a no-op.  2 conformance tests
  in `ThrowsTest` (Phase 7).
- Cross-backend `fn`-name normalisation — JVM mangled vs JS
  source vs INT definition site.  Tackle when first
  diagnostic mismatch surfaces.
- Java/Scala interop on the IDE side — `throws` is a pure
  type alias, runtime is `Either[E, A]`; document for v2.0
  separate compilation.

### Effort

Nine phases, ~13 days end-to-end across three backends
(was ~12 — Phase 3 expanded from ~2 to ~3 days to absorb the
type-directed `throw` / `try`-`catch` lowering inside direct
blocks).  Phase 6 (stack traces) is still the only piece
with meaningful per-backend variance; Phases 8-9 are pure
stdlib + helper work that lands uniformly.

## v1.16 — Restartable errors via algebraic effects — LANDED

Common Lisp condition-system style restartable handlers.  A
handler can choose to resume the suspended computation at the
throw point with a replacement value, rather than only being
able to abort or rewrap.

```scalascript
val config = restartable {
  case FileNotFound(path) => Restart.useDefault
  case PermissionDenied(p) => Restart.resume(sudo(p))
  case other => Restart.rethrow
} {
  parseConfig(readFile("/etc/app.conf"))
}
```

### What landed

- `restartable { handlers } { body }` — special form in the interpreter.
  The body runs in a virtual thread; `throw e` suspends via a
  synchronous-queue handshake so the handler on the caller thread
  can decide the outcome.
- `Restart.resume(v)` — body's throw-expression evaluates to `v`
  and execution continues.
- `Restart.useDefault` — body's throw-expression evaluates to `()`
  and execution continues.
- `Restart.rethrow` — exception propagates out of the `restartable`
  frame as a normal `ScriptException`.
- Nested `restartable` blocks: each virtual thread owns its own
  handler stack; unmatched throws from inner blocks propagate to the
  outer handler correctly.
- `RestartableRethrow` sentinel exception type prevents
  `try/catch` blocks from intercepting terminal rethrows mid-flight.
- 17 conformance tests in `RestartableTest.scala` — all green.

### Implementation

- Thread-local `_restartableTL` stack of `RestartableHandle(errorQ,
  resumeQ)` pairs. Body pushes a handle, `Term.Throw` checks the
  head of the stack; if found, routes through the queue protocol
  instead of throwing `ScriptException`.
- Handler loop polls both errChan and doneChan (1 ms alternating
  poll) to avoid the need for a select-like primitive.
- `ScriptException` escaping a virtual thread body is routed through
  `errChan` so the outer restartable handler can match and resume.

### Known limitations / follow-up

- Handler case bodies read variables from the captured env (closure
  semantics); they do not observe globals mutations from previous
  handler invocations for the same variable.  This is consistent
  with how closures and try/catch handlers work in the interpreter.
- JS backend: not implemented.  Follows the same coroutine-per-body
  pattern but requires a JS generator-based shim.
- Algebraic-effects study (v1.12) remains BLOCKED.

## v1.12 / Algebraic effects feasibility study — BLOCKED (no version assigned)

**Do not start this until all other milestones through v2.0
are complete.**  No agent, contributor, or planning round
should promote or schedule this before that.

**No shipping code** — design doc + working prototype + go/no-go
decision.  Investigates whether ScalaScript's type system can
support OCaml-5 / Koka-style algebraic effects with handler
stacks, built on top of coroutines.

Originally tracked as v1.12; blocked (2026-05-18) — no
concrete consumer is asking for it, and the existing stack
(`Async` / `MonadError` / `throws[A, E]` / Free monad in
v1.11.5) covers real workloads.

### Why a study, not a milestone

Effect rows on the type level (`(Async | Random | Logger)[A]`)
require type-system machinery ScalaScript doesn't have today:

- Union types in effect position (Scala 3 has these — usable)
- Bounded polymorphism over effect rows (Scala 3 limit)
- Effect subtraction at handler site (no precedent)

Whether this is feasible *given the existing typer* is genuinely
unknown.  Spending a week to prototype before committing to a
multi-month implementation milestone is the right call.

### Deliverables

1. **Prototype** — working coroutine-based handler stack catching
   tagged yields (informal `Op("name", args)` representation,
   like `docs/coroutines.md` §4.3).  No type-level effect
   tracking yet — just runtime semantics.
2. **Type-system audit** — does the existing typer carry enough
   information to refuse `pureFunction()` when called inside an
   `Async` block?  What changes are needed?
3. **Design doc** — `docs/algebraic-effects.md` with the chosen
   approach (or "rejected, here's why").
4. **Go/no-go decision** — commit to a v2.x algebraic-effects
   milestone, or close this thread.

### Effort

~1 week of focused design + prototyping.  No conformance
deliverable; the prototype's tests live alongside the prototype
and don't enter the conformance suite unless a real
implementation lands later.

## v1.9.x — Actor internals refactor ✓ Landed (2026-05-19)

Actor mailboxes now use `java.util.concurrent.LinkedBlockingQueue[Value]`
— the same thread-safe queue type used by the v1.9 coroutine
infrastructure (`fromBody` channel).  This is the "built from the
coroutine primitive" part of the original spec.

**What landed**:
- **Interpreter**: `ActorRuntime.mailboxes` changed from
  `mutable.LongMap[mutable.Queue[Value]]` to
  `mutable.LongMap[LinkedBlockingQueue[Value]]`.  All mailbox
  operations updated: `enqueue` → `offer`, `dequeue` → `poll`,
  `head` → `peek()`, `nonEmpty` → `!isEmpty`.
- **JvmGen**: `_ActorState.mailbox` changed from
  `ArrayDeque.empty[Any]` to `new LinkedBlockingQueue[Any]()`.
  Same API update throughout the emitted actor runtime.
- **JsGen**: No change.  JS actors use plain arrays — correct for
  the single-threaded cooperative scheduler; `LinkedBlockingQueue`
  is a JVM concept with no JS equivalent.

**Why**: Thread-safe mailboxes unify actor and coroutine
infrastructure, remove a potential race window in distributed mode
(WS threads previously used an intermediate `remoteInbox` queue;
direct enqueue is now safe), and reduce conceptual distance between
the two subsystems.

### Effort (actual)

~2 h.  The "full VT-per-actor" refactor described in the original
spec would have been ~1 week and is left as a future optional
cleanup if measurements ever show the cooperative scheduler is a
bottleneck.

## v1.17 — MCP support (client + server)

Model Context Protocol — Anthropic's JSON-RPC 2.0-based
protocol for connecting LLM applications to external tools,
resources, and prompts.  Ships as **separate namespace and
modules** (`std/mcp/server`, `std/mcp/client`, `std/mcp/types`),
intentionally orthogonal to the existing REST stack — same
shape API, different protocol.

Full design in [`docs/mcp.md`](docs/mcp.md) — protocol basics,
REST-shaped API rationale, server + client surfaces,
intrinsic-first implementation strategy, backend feature
flags, coexistence with v1.8 / v1.13 / v1.15, hard-no list,
open questions.

```scala
// Server
mcpServer { srv =>
  srv.tool("get_weather") { args =>
    val city = requireString(args, "city")
    Tool.text(s"Weather in $city: sunny, 22°C")
  }
}
serveMcp(transport = Transport.stdio)

// Client
val client = mcpConnect(Transport.spawn("node", "weather-server.js"))
val result = client.callTool("get_weather", Map("city" -> "Berlin"))
println(result.content.head)
client.close()
```

### Implementation strategy — intrinsic-first

Per user direction: backends with a standard SDK get an
**intrinsic** that wraps it (~1 week each); platforms without
a standard SDK defer to v1.17.x own-implementation.

| Backend | MCP Server | MCP Client |
|---------|-----------|------------|
| jvm  | ✅ intrinsic (`io.modelcontextprotocol:sdk`) | ✅ intrinsic |
| js (Node) | ✅ intrinsic (`@modelcontextprotocol/sdk`) | ✅ intrinsic |
| interpreter | ❌ deferred to v1.17.x | ❌ deferred |
| scalajs-spa | ❌ (server makes no sense in browser) | ❌ (HTTP+SSE in browser plausible v1.17.x) |

Interpreter and scalajs-spa imports of `std/mcp/server` raise
an actionable Feature-not-supported error at typecheck time
per SPI §8 — not a runtime surprise.

### Why REST-shaped API

The MCP server surface mirrors the REST stack
(`srv.tool` ↔ `route("GET", "/path")`, `serveMcp(...)` ↔
`serve(port)`).  Users already know the pattern; JSON-RPC 2.0
is RPC-shaped which fits handler-per-method naturally; the
cross-cutting concerns (transport, lifecycle, error handling)
translate cleanly.

Type-class / FT-style API (`given McpTool[Args, Result]`) is
**not** the v1 default — dynamic registration is common in
real MCP servers (tools defined at runtime from config), and
the JSON-Schema-shaped args fit the v1.15 `Map[String, Any]`
+ `require*` pattern.  Type-class layer ships as optional
v1.17.1 add-on once v1.14 `derives` lands.

### Phase 1 — Types + namespace skeleton (~2 days)

`std/mcp/types.ssc` + skeleton `server.ssc` + skeleton
`client.ssc`.  Pure types; no runtime dependency.

### Phase 2 — JS server intrinsic (~5 days)

JsGen-emitted `mcpServer { … }` / `serveMcp(...)` wraps
`@modelcontextprotocol/sdk` (npm).  Three transports
(stdio, http+sse, ws).  Conformance: round-trip with the
canonical MCP CLI client.

### Phase 3 — JVM server intrinsic (~4 days)

JvmGen-emitted equivalent wrapping `io.modelcontextprotocol:sdk`
via `//> using dep` directive.  Same surface, same three
transports.

### Phase 4 — JS client intrinsic (~3 days)

`mcpConnect(transport)` wraps the JS SDK client.  Sync +
Async variants (Async wraps SDK promises).

### Phase 5 — JVM client intrinsic (~3 days)

Same shape, Java SDK.

### Phase 6 — Feature flags + interpreter rejection (~1 day)

`Feature.McpServer` / `Feature.McpClient` declared per SPI
§8.  JVM + JS advertise; INT + scalajs-spa reject at
typecheck with actionable message.

### Phase 7 — Examples + docs (~2 days)

Three demos: tools server, client-discover, agent (both
client and server in one script).  `docs/mcp.md` walkthroughs.

### Hard-no list (locked by design — `docs/mcp.md` §10)

- **Own MCP impl in v1** — intrinsic-first per user direction;
  defer to v1.17.x when a backend without an SDK becomes a
  real target
- **Type-class API as primary** — REST-shaped is v1; FT layer
  is v1.17.1+ add-on
- **Custom transports** (Unix socket, named pipe) — stdio /
  HTTP+SSE / WS cover the realistic landscape; bespoke
  transports are an SDK-extension concern
- **Schema validation in std layer** — SDK handles
  JSON-Schema validation; std doesn't re-validate
- **MCP-versioned namespaces** in v1.17 — single `std/mcp/*`
  for now; versioned namespaces when MCP protocol diverges
- **Bidirectional sampling** — MCP advanced feature; defer

### v1.17.1 — MCP hardening ✓ Landed (2026-05-19)

Four post-audit fixes that close the gap between "landed in v1.17"
and "actually reliable":

1. ✓ **`CapabilityCheck.validate` wired into CLI** — `compileViaBackend`
   in `cli/Main.scala` now calls `CapabilityCheck.validate` between
   `Normalize` and `backend.compile`; programs using `std/mcp/*` on
   INT now produce `[error] Unsupported(McpServer, int)` instead of
   crashing at runtime.
2. ✓ **Conformance runner `requires:` parsing** — `conformance/run.sc`
   now reads `requires:` from each test's YAML frontmatter and
   skips per-backend with an explanatory message rather than running
   into a crash.  Static feature map mirrors all three backends'
   `Capabilities`.
3. ✓ **`JvmRuntimeMcp.scala` preamble indentation** — re-aligned the
   misindented `|`-prefix on lines 113-218 of the resource-handler
   and everything that followed; cosmetic, no change to generated code
   (stripMargin is invariant to leading whitespace before `|`).
4. ✓ **MILESTONES.md** — v1.17.1 landing entry; deferred list
   reordered by priority (see below).

Note on MCP conformance tests: `mcp-server-{tool,resource}.ssc` and
`mcp-client-invoke.ssc` are **integration tests** that require a live
MCP transport (stdio blocking, or an external node subprocess).  They
cannot produce deterministic expected output in the automated
conformance runner.  The `requires:` gate now shows a clear SKIP
reason; they are manually smoke-tested (see examples/mcp-*.ssc demos).

### v1.17.2 — SSE transport on JS ✓ Landed (2026-05-19)

`Transport.Http(port, path)` now works on the JS/Node backend:
`serveMcp()` spawns a Node `http.Server` that creates a fresh
`SSEServerTransport` per incoming GET (SSE stream) and forwards
POST messages to the matching session via `transport.handlePostMessage`.
CORS headers + OPTIONS pre-flight included.  `onConnected` /
`onDisconnected` lifecycle hooks fire per-client-connection.
`std/mcp/server.ssc` bumped to v0.2.0; `Transport.Http` comment
updated to document SSE mechanics.  Manual smoke: connect Claude
Desktop (or `npx @modelcontextprotocol/inspector`) to
`http://localhost:3000/mcp`.

### v1.17.3 — Prompts on JVM ✓ Landed (2026-05-19)

`buildSpec()` in `McpServerBuilder` previously accumulated prompt
registrations in `_prompts` but never called `specBuilder.prompts(...)`,
so all registered prompts were silently dropped.  Added a prompts spec
block mirroring the tools/resources pattern:

- `new Prompt(name, desc, List.of())` wraps the registration metadata.
- `SyncPromptSpecification` handler converts `GetPromptRequest.arguments()`
  to `Map[String, Any]`, calls the user handler, then maps the
  `PromptResult` messages back to `PromptMessage(PromptMessageRole, TextContent)`.
- `specBuilder.prompts(...)` called when `_prompts.nonEmpty`.
- Role mapping: `"assistant"` → `PromptMessageRole.ASSISTANT`, all
  others → `PromptMessageRole.USER`.

### v1.17.4-min — Http/Ws transports on JVM (minimal wiring) ✓ Landed (2026-05-19)

The two `Transport.Http` / `Transport.Ws` arms of `serveMcp` no longer
throw `McpError("not yet supported")`.  Instead they bind to the existing
JVM HTTP / WS server runtime (`route` + `sse` + `serve` + `onWebSocket`,
emitted by `JvmGen.serveRuntime`).  `JvmGen` now also emits `serveRuntime`
when MCP is detected, so MCP-only scripts pick up the dispatcher.

What this minimal landing covers:

- `serveMcp(Transport.Http(port, path))` registers `GET <path>` (opens an
  SSE stream, assigns a `sessionId`, sends an `endpoint` event with the
  POST URL) and `POST <path>?sessionId=…` (delivers inbound JSON-RPC),
  then calls `serve(port)` — identical surface to the JS-side v1.17.2.
- `serveMcp(Transport.Ws(port, path))` registers `onWebSocket(path) { … }`
  and `serve(port)`; each text frame is one JSON-RPC message.

What this minimal landing does NOT cover (deferred to the proper v1.17.4):

- Real `McpServerSession.handle(…)` dispatch into the SDK's request /
  response correlation, capability negotiation, and tool/resource/prompt
  invocation.  Inbound JSON-RPC is currently echoed back as a placeholder.
- `McpServerBuilder.onConnected` / `onDisconnected` hook firing on the
  HTTP / WS code paths.

### v1.17.4-runtime — runtime-server-common consolidation ✓ Landed (2026-05-19)

Phase 1 of `PLAN-runtime-consolidation.md` (full migration path off the
duplicated HTTP/WS server stack — was previously tracked on branch
`feature/v1.17.4-http-ws-jvm`):

- **Phase 1a** — new sbt module `runtime-server-common` extracted; 10
  pure protocol primitives moved out of `backend-interpreter/server/`
  (`WsFraming`, `Password`, `RateLimit`, `Totp`, `Jwt`, `JwtRsa`,
  `SessionCookie`, `SessionStore`, `Metrics`, `RestValidationError`) +
  factored out a new `DerCodec` shared between `JwtRsa` and `WebServer`.
  `backend-interpreter` depends on the new module; all FQN call sites
  (`scalascript.server.Password.hash` etc.) keep working unchanged.

- **Phase 1b** — `runtime-server-common` packages its own .scala sources
  as classpath resources at `runtime-server-common-sources/scalascript/server/*.scala`;
  `JvmGen` reads each via `loadCommonSource(name)`, strips the
  `package scalascript.server` line, and emits the body as a `commonRuntime`
  block right after `preamble` (always emitted — used by non-server scripts
  too via `hashPassword`/`totpSecret`/`rateLimit`).  The duplicated
  implementations inside `JvmGen.serveRuntime` are now one-line adapter
  shims delegating to the inlined objects:

  | Replaced internal helper(s) | Now delegates to |
  | --- | --- |
  | `rateLimit`/`rateLimitReset` | `RateLimit.tryAcquire/reset` |
  | `totpSecret`/`totpUri`/`totpCode`/`totpValid` | `Totp.secret/uri/code/valid` |
  | `hashPassword`/`verifyPassword` | `Password.hash/verify` |
  | `_jwtSecret`/`_hmacSha256Jwt`/`jwtSign`/`jwtVerify` | `Jwt.sign/verify` |
  | `_jwtRsaPrivate/Public`/`jwtSignRsa`/`jwtVerifyRsa` | `JwtRsa.sign/verify` |
  | `_pkcs1ToPkcs8` (used in TLS cert/key load) | `DerCodec.wrapPkcs1InPkcs8` |
  | `_bearerFromAuth` | `Jwt.fromAuthHeader` |
  | `_packSession`/`_unpackSession`/`_parseCookieSession`/`_buildSetCookie`/`cookieConfig` | `SessionCookie.{pack,unpack,fromHeader,toSetCookie,setCookieConfig}` |
  | `_sessionStore`/`_sessionStorePut/Get/Delete`/`useSessionStore` | `SessionStore.{put,get,delete,useStore}` |
  | `_Metrics` (singleton with WS/HTTP counters) | `Metrics` (val alias) |
  | `_WS_MAGIC`/`_WsMaxFrameBytes`/`_wsAcceptKey`/`_WsFrame`/`_wsParseFrame`/`_wsEncode{Frame,Text,Pong,Ping,Close}` | `WsFraming.{acceptKey,Frame,tryParse,encode{Text,Binary,Pong,Ping,Close}}` |
  | `_RestValidationError` | `RestValidationError` (rename + drop duplicate) |

  Net reduction in `JvmGen.scala`: ~530 LOC of duplicated string-template
  code replaced with ~70 LOC of adapter shims; the implementations now
  live in 11 properly-tested Scala files in `runtime-server-common`.

- **Phase 1c** — `OAuth` (~290 LOC) and `WebAuthn` (~450 LOC) turned out
  to be pure too (no actual interpreter coupling — initial scan flagged
  them as coupled on false positives from `Value` / `Interpreter`
  identifier hits in unrelated contexts).  Both moved to
  `runtime-server-common`; OAuth has a serveRuntime duplicate that
  collapsed to 5 adapter shims (~200 LOC → 5 LOC); WebAuthn was
  interpreter-only, so it moved without a shim and is now available for
  the codegen runtime to consume in follow-up commits.

  `loadCommonSource` now rewrites `private[server]` / `protected[server]`
  qualified-access modifiers to plain `private` / `protected` on inline
  (the `[server]` referent disappears once the `package scalascript.server`
  line is stripped; file-local visibility is preserved because every
  inlined source ends up in the same top-level scope of the generated
  script).

### v1.17.4 — full Http/Ws transports on JVM ✓ Landed (2026-05-19)

The v1.17.4-min echo placeholder is replaced with real
`McpServerSession.handle(...)` dispatch.  Two pieces of work landed here:

1. **`JvmRuntimeMcp.scala` rewrite** — every SDK import path corrected
   from the previously-broken `io.modelcontextprotocol.sdk.*` to the
   actual `io.modelcontextprotocol.{server,client,spec}.*` layout (the
   SDK jar never had a `.sdk.` segment, so the prior template never
   compiled against a real classpath).  The API shape is realigned:
   `McpServer.sync(provider)` accepts the transport provider directly and
   returns a `SyncSpecification` builder — `.serverInfo(...).tools(list)
   .resources(list).prompts(list).build()` yields the live `McpSyncServer`;
   the `_mcpBuilder.buildSpec()` / `jSrv.connect(transport)` shape used
   previously does not exist in the SDK.

2. **Custom `McpServerTransportProvider`s** for Http and Ws, bridging
   the SDK's reactor-based protocol layer to the consolidated `route()`
   / `sse()` / `onWebSocket()` helpers emitted by `serveRuntime`:

   - `_HttpSseSessionTransport` writes outbound JSON-RPC as SSE
     `message` events; `_HttpSseMcpProvider.setSessionFactory` lazily
     registers GET (SSE upgrade — sends `endpoint` event with the POST
     URL, holds the stream open) and POST handlers (deserialises via
     `McpSchema.deserializeJsonRpcMessage`, routes through
     `session.handle(msg).subscribe()`).
   - `_WsSessionTransport` writes outbound text frames; `_WsMcpProvider`
     registers `onWebSocket(basePath) { ws => … }` and pipes inbound text
     frames through the same `deserialize → session.handle` flow.
   - Both providers implement `notifyClients` for SDK-driven broadcast
     and `closeGracefully` for shutdown.  Inbound exceptions surface as
     HTTP 500 / stderr WS errors instead of silent drops.
   - `onConnected` / `onDisconnected` builder hooks fire on session
     create / close (Stdio: on serveMcp entry/exit; Http: SSE open/close;
     Ws: WebSocket connect/disconnect).

Supporting change: `route(method, path)(handler: Request => Any)` — the
prior `Request => Response` signature was a compile-time lie since the
runtime dispatcher already pattern-matches on `_StreamResponse | Response
| other`; the narrower type just blocked MCP transports from returning
`sse(req) { … }` from a route handler.  Widening to `Any` matches the
runtime behaviour and stays compatible with existing `Request => Response`
callers (Response is a subtype of Any).

### v1.17.4-runtime — runtime-server-common consolidation Phase 2 (partial) ✓ Landed (2026-05-19)

Phase 2 of `PLAN-runtime-consolidation.md` — pulled every pure HTTP /
TLS helper that did not require the dispatcher refactor.  Both server
stacks (interpreter `WebServer` + JvmGen `serveRuntime`) now share the
same source of truth for these primitives:

- **Phase 2a** — `HttpHelpers` (path parser + matcher, query parser,
  MIME sniffer), `Multipart` (form / file part parser returning POJO
  `UploadedFile`s), and the `UploadedFile` POJO itself moved to
  runtime-server-common.  JvmGen's `_parsePath` / `_matchPath` /
  `_parseQuery` / `_parseMultipart` / `_contentTypeFor` collapsed to
  one-line delegates; `WebServer.parseQuery` / `contentTypeFor` ditto;
  `WebServer.parseMultipart` is a 15-line Value-conversion wrapper on
  top of the shared parser (vs the previous 60-line duplicate).  Both
  case-class definitions of `UploadedFile` (one on each side) collapsed
  into the single inlined POJO.
- **Phase 2b** — `TlsContextBuilder` (TLS SSLContext builder + virtual-
  thread executor builder) and `CorsHelpers` (response-header CORS
  application given per-server `origins` / `methods` / `headers`
  config).  PEM handling unified — PKCS#1 RSA keys go through
  `DerCodec.wrapPkcs1InPkcs8` on both sides; EC fallback preserved.
  CORS state stays on each side (mutable globals); the helper takes
  the values as parameters so it's purely stateless.

- **Phase 2c** — POJO HTTP model + parsing / writing helpers extracted
  so both backends share the per-request pipeline:

  | File | What it owns |
  | --- | --- |
  | `HttpModel.scala` | `Request` / `Response` / `StreamResponse` case classes (+ `Response.withHeader / withSession / clearSession`) |
  | `BasicAuth.scala` | `BasicAuth.fromHeader(authHeader)` — decode `Authorization: Basic <b64>` headers |
  | `RequestBuilder.scala` | `parse(ex, method, path, params, cfg) → (Request, rawCookieSession, spooledTmps)` — header / body / multipart / cookie / auth / session / JWT extraction in one pass, with a `Config` record threading per-server `maxBodySize` / `sessionStoreGet` / `jwtVerify` callbacks. Throws `BodyTooLargeError` for callers to surface as `413`. |
  | `ResponseWriter.scala` | `write(ex, response, rawCookieSession, cfg)` — header munging, CORS, `setSession` (with SSID rotation through the opt-in store), ETag → 304, gzip + body write. |
  | `StreamResponseWriter.scala` | `write(ex, status, headers, cors, runWriter)` — chunked transfer encoding for `streamResponse` / `sse(req)` user handlers; `runWriter` lets each backend wire its own writer-closure (Scala closure on codegen, `Value.NativeFnV` bridge on interpreter). |

  JvmGen.serveRuntime drops the duplicated case classes (`Request`,
  `Response`, `_StreamResponse` — now type alias to `StreamResponse`)
  plus all of `_basicFromAuth`, the inline header / body / cookie /
  auth / session block inside `_handle`, the `_writeResponse` body,
  and the streaming-response inline block.  backend-interpreter
  `WebServer.dispatchRoute` / `writeResponse` / `handleStreamResponse`
  collapse to thin POJO ↔ `Value.InstanceV("…", …)` adapters around
  the shared helpers.

Net additional dedup: ~700 LOC removed across the two backends,
replaced with ~120 LOC of `Config(...)` calls + `Value` bridge code;
`runtime-server-common` now packages **24** Scala files inlined into
the codegen output.

- **Phase 2d** — remaining pure helpers that don't require the full
  RouteDispatcher trait yet:

  | File | What it owns |
  | --- | --- |
  | `StaticAssetServer.scala` | `resolve(root, urlPath)` + `serve(file, ex)` (canonical-path traversal guard + `.ssc`-skip + MIME-sniffed write) + a `tryServe(ex, urlPath)` convenience.  Drops the duplicated `_serveStatic` codegen / `resolveStatic` + `serveStatic` interpreter helpers. |
  | `WsHandshake.scala` | RFC 6455 §4 upgrade wire shape — `negotiateSubprotocol(clientOffer, serverProtocols): Option[String]`, `upgradeResponse(key, chosenProtocol): Array[Byte]`, `rejectResponse(status, reason): Array[Byte]`.  Drops the inline 101 / 4xx / 503 reject builders in both backends. |
  | `WsReassembler.scala` | Pure state machine for RFC 6455 §5.4 fragmented-message reassembly. `feed(frame): Event` emits `Deliver(opcode, payload)` / `ProtocolError(code, reason)` / `Buffered`. |
  | `WsRateLimiter.scala` | Per-connection 1-second-window inbound message rate cap; `admit(nowMs): Boolean`. |

  backend-interpreter `WsConnection.onFrame` collapses to a 3-case
  match on the reassembler event; the standalone `checkFragLimit`
  helper goes away.  JvmGen.serveRuntime's `_dispatchWsMessage`
  rate-limit prelude collapses to a one-liner against the shared
  `WsRateLimiter`.  Subprotocol negotiation + all five reject
  responses (404 / 403 / 401 / 503 / 400) on both backends route
  through `WsHandshake.*`.

  Net additional dedup: ~140 LOC removed across the two backends,
  replaced with ~30 LOC of one-line shims; `runtime-server-common`
  now packages **28** Scala files inlined into the codegen output.

- **Phase 2e** — last structural piece: the per-request HTTP
  envelope and the per-frame WS dispatcher.  Shipped as three
  sub-commits, each independently shippable, to keep the
  regression surface small:

  | File | What it owns |
  | --- | --- |
  | `HttpDispatchLoop.scala` | `run(ex, method, path, params, handler, middlewares, cfg, onError)` — parse request via `RequestBuilder`, build middleware chain around a `Request => Any` base handler, trap `RestValidationError` → 400, dispatch result to `StreamResponseWriter.write` / `ResponseWriter.write`, catch generic `Exception` → `onError` + 5xx metric, cleanup spooled multipart tmpfiles in `finally`.  Backends only own the route table + handler-token lift / unwrap. |
  | `WsFrameDispatch.scala` | `handle(frame, reassembler, onPing, onPong, onPeerClose, onDeliver, onProtocolError): Outcome` — RFC 6455 opcode match + reassembler hand-off + Close-payload status decode.  Returns `Stop` on peer-Close / protocol error (caller exits its read loop), `Continue` otherwise.  Backends keep their thread-affinity model (NIO selector vs per-VT) and supply IO / executor / interpreter-invoke hooks as plain lambdas. |

  **Phase 2e-1** (warm-up): codegen `_runReadLoop` migrated to
  the shared `WsReassembler` so its frag-state matches the
  interpreter's onFrame post-Phase 2d.  Drops inline
  `fragOpcode` / `fragBuf` state + unused
  `ByteArrayOutputStream` import.

  **Phase 2e-2**: `HttpDispatchLoop` extraction.
  `JvmGen.serveRuntime._handle` shrinks from ~95 LOC of inline
  dispatch to a ~25 LOC `HttpDispatchLoop.run` call.  The
  codegen-only `_BodyTooLarge` exception class + `_writeResponse`
  helper are dropped.  `WebServer.dispatchRoute` keeps the
  interpreter-only `Value` ↔ POJO lift / unwrap / relift helpers
  (~80 LOC of pure adapter) and hands its `pojoHandler` +
  `pojoMws` to the shared loop.  The private
  `handleStreamResponse` + `writeResponse` methods are deleted —
  `HttpDispatchLoop` calls the shared writers directly.

  **Phase 2e-3**: `WsFrameDispatch` extraction.  `WsConnection.onFrame`
  collapses from a 5-arm opcode `match` to a single
  `WsFrameDispatch.handle(...)` call with five inline hook
  lambdas.  Codegen `_runReadLoop`'s post-2e-1 inline opcode
  match likewise routes through the shared dispatcher.  The
  codegen-side `_WsFrame` adapter case class + `_wsParseFrame`
  shim are dropped — the read loop calls `WsFraming.tryParse`
  directly and feeds the `WsFraming.Frame` POJO into
  `WsFrameDispatch.handle`.  No more bare-Int opcode matching
  anywhere in `serveRuntime`.

  Net additional dedup: −129 LOC across the two backends, plus
  ~175 LOC in two new shared files; the architectural cleanup
  ("single source of truth for the per-request envelope + per-frame
  WS dispatch") is the real win over raw LOC.
  `runtime-server-common` now packages **29** Scala files inlined
  into the codegen output.

- **Phase 2f** — small dedup wins that the trait extractions in
  2a–2e left around the edges.  Three sub-commits, no new files
  in runtime-server-common — only new helpers on `HttpHelpers`:

  | Helper | Replaces |
  | --- | --- |
  | `HttpHelpers.parseCookieHeader(raw)` | six copies of the same `raw.split(';')` trim+split=Map block across RequestBuilder / TlsProxy / WsProxy (×2) / serveRuntime (×2) |
  | `HttpHelpers.readHttpHead(in)`       | byte-identical `prev3/prev2/prev1` sentinel loop in JvmGen `_readHttpHead` + interp `TlsProxy.readHttpHead` |

  **Phase 2f-1** — `parseCookieHeader` consolidation; one canonical
  Cookie parser left in the tree.  Local `TlsProxy.parseCookies`
  helper deleted.  Net −36 LOC across the two backends, +12 LOC in
  HttpHelpers.

  **Phase 2f-2** — `readHttpHead` extraction.  Bonus dedup: the
  local `TlsProxy.parseQuery` (byte-identical to
  `HttpHelpers.parseQuery`) deleted and call sites switched to the
  shared one.  Net −26 LOC.

  **Phase 2f-3** — closes the last Phase 2d gap: `TlsProxy`'s
  inline subprotocol negotiation + 101 Switching Protocols
  response builder migrated to `WsHandshake.negotiateSubprotocol`
  + `WsHandshake.upgradeResponse`, so all three WS upgrade paths
  (TlsProxy, WsProxy, JvmGen) emit byte-identical wire bytes from
  one source of truth.  In the same pass, the Request snapshot
  used for the pre-upgrade auth hook and `ws.request` was being
  built twice across the upgrade boundary even though the
  headers / path / params / cookies don't change there — collapsed
  to a single snapshot in each of the three upgrade sites.  Net
  −56 LOC.

  Phase 2f total: −74 LOC across the two backends, +37 LOC in
  HttpHelpers (no new file count change in `runtime-server-common`).

- **Phase 2g** — one more `HttpHelpers` addition:

  | Helper | Replaces |
  | --- | --- |
  | `HttpHelpers.parseHttpHead(head)` → `HttpRequestHead` | byte-identical post-`readHttpHead` parse blocks in interp `TlsProxy.handleConnection` + JvmGen `_proxyConnection`: decode ISO-8859-1, split on `\r\n`, drop request line, lowercase-key headers Map, extract method/path/rawQuery, classify as upgrade-or-not.  The returned record carries an `isUpgradeWebSocket` predicate folding the `Upgrade:`/`Connection:` case-insensitive check both sites used inline. |

  Both backends now `val parsed = HttpHelpers.parseHttpHead(head)` and
  read `parsed.path` / `parsed.rawQuery` / `parsed.headers` /
  `parsed.isUpgradeWebSocket`.  Codegen drops a now-redundant
  `_rawQ = request.split(' ')…` line.

  Phase 2g total: −32 LOC across the two backends, +43 LOC in
  HttpHelpers.

- **Phase 3 — Option A: `serveRuntime` out of string templates**
  ✓ Landed (2026-05-19).  See
  [`docs/runtime-server-strategic-plan.md`](docs/runtime-server-strategic-plan.md)
  for the full design.

  The entire content of `JvmGen.serveRuntime` (Part1 + Part1b +
  Part2, ~4 180 LOC of generated source) used to live inside three
  `"""|..."""` triple-quoted string templates concatenated together
  (split because each piece bumped against the JVM's 64 KB
  string-literal limit).  Phase 3 migrates that content into real
  `.scala` files inside a new `runtime-server-jvm` sbt module,
  inlined into the codegen output via the same resource-bundle
  pattern `runtime-server-common` already uses (Phase 1b).

  Five sub-commits:

  | Sub | File | What it owns |
  | --- | --- | --- |
  | 3a | `package.scala` | sbt module + loader scaffolding |
  | 3b | `RestRuntime.scala` (~800 LOC) | REST routing + `serve(port)` + signed-cookie sessions + Jwt / OAuth / CSRF shims + body-size / spool / streaming / SSE / CORS / gzip + `_handle` dispatch + static-asset fallback |
  | 3c | `WebSocketRuntime.scala` (~480 LOC) | `WebSocket` class + `onWebSocket` / `WsRoom` + `_Metrics` adapter + framing shims |
  | 3d | `ProxyRuntime.scala` (~220 LOC) | `_proxyConnection` blocking accept + HTTP/WS sniffing + TLS / HTTPS bootstrap |
  | 3e+3f | `OutboundClients.scala` (~225 LOC) | `http(url)` REST client + `wsConnect(url)` WS client; `serveRuntimePart1 / Part1b / Part2` strings deleted |

  Final `JvmGen.serveRuntime`: ~10 lines of glue concatenating four
  `loadJvmRuntimeSource(...)` calls.  No more `|`-prefix gymnastics,
  no more 64 KB literal limit, no more cross-template runtime
  refactors blind to the type checker.

  **Zero distribution change**: ssc stays a fat jar with zero
  external runtime deps; generated scripts stay self-contained
  (runtime source is inlined as text from the resource bundle, not
  pulled from a published jar).  Option A+ (Maven publishing as a
  perf follow-up) becomes a future opt-in.

  **Verification**: `JvmGenEffectsRuntimeTest` 8/8 throughout the
  five sub-commits — including scala-cli end-to-end compile of
  `serve(8080)` + `onWebSocket` modules.  Phase 3 follow-up adds
  4 unit-test files in `runtime-server-jvm/src/test/`, 28 tests
  covering the public API surface (Json, validate, WsRoom, Response
  factories).

- **Option B — interpreter WS to per-VT thread model** ✓ Landed
  (2026-05-19).  Unifies the WS thread model with the codegen: both
  sides now use blocking-IO with one virtual thread per connection.
  See [`docs/runtime-server-strategic-plan.md`](docs/runtime-server-strategic-plan.md)
  Option B for the full design.

  **Before**: interpreter ran a single `Selector.open()` loop with
  per-channel state attached to `SelectionKey`s.  `WsProxy.scala`
  (460 LOC) demuxed HTTP/WS bytes on the selector thread;
  `WsConnection.scala` (498 LOC) was selector-fed via `onBytes(buf)`
  and drained outbox into the channel from selector's `onWrite` hook.

  **After** (mirrors `runtime-server-jvm`'s `ProxyRuntime` +
  `WebSocketRuntime`): blocking `ServerSocket` + `Thread.ofVirtual()`
  per accepted client.  `WsProxy.scala` (305 LOC) reads HTTP head,
  sniffs Upgrade, and either drives the full WS upgrade + runs the
  connection's blocking read loop on that VT, or forwards bytes to
  the internal `HttpServer` via two pump VTs.  `WsConnection.scala`
  (442 LOC) wraps `java.net.Socket` directly; writer VT drains a
  bounded `LinkedBlockingQueue[Array[Byte]]` to `getOutputStream`;
  new `runReadLoop()` method blocks on `getInputStream`.

  **Threading**: per-VT for read-loop and writer-loop.  User
  callbacks (`onMessage`, `onClose`, `onPong`, handler body) still
  dispatch through the single-thread `wsExecutor` — interpreter's
  `Value` / `Computation` aren't thread-safe, so user handlers stay
  serial.  Per-VT read loop is independent; only the executor is
  shared.  No new `Interpreter.lock` needed — the existing executor
  serialisation suffices.

  Public APIs preserved unchanged: `WsProxy(port, internalAddr,
  wsExecutor, log, ...)` / `start()` / `stop()`, `WsConnection.asValue`
  (the `Value.InstanceV("WebSocket", ...)` builder user code touches),
  `WsConnection.tryReserveSlot` / `releaseSlot` / `setMaxConnections`,
  `WsRoutes.*` lookups.

  **Net delta**: −211 LOC across the two files (958 → 747).

  **Verification**: all 18 WS conformance tests pass on the rewritten
  per-VT code (`WsBinaryTest`, `WsCookiesTest`, `WsPingPongTest`,
  `WsRoomTest`, `WsSlowClientTest`, `WsMetricsTest`, `WsOriginTest`,
  `WsEchoTest`, `WsAuthHookTest`, `WsFragmentTest`, `WsSubprotocolTest`,
  `WsMaxConnectionsTest`, `WsHeartbeatTest`, `WsSubprotocolFieldTest`,
  `WsRequestTest`, `WsRouteCapTest`, `WsRateLimitTest`, `WsIdTest`).

  **Option B follow-up ✓ Landed (2026-05-19)** — actual code-level
  sharing of the `WebSocket` class.  The structurally-identical
  interpreter `WsConnection` and codegen `WebSocketRuntime.WebSocket`
  are now ONE class, living in `runtime-server-jvm` and used by both
  backends:

  - `WebSocket` constructor in
    `runtime-server-jvm/src/main/scala/scalascript/server/jvm/WebSocketRuntime.scala`
    gains `_executor` / `_heartbeats` / `_heartbeatIntervalMs` /
    `_deadAfterMs` / `_log` ctor params with codegen-side defaults
    (the existing top-level `_serverExecutor` / `_wsHeartbeats`).
    `ProxyRuntime`'s call site is unchanged (picks up defaults).
  - `backend-interpreter/.../WsConnection.scala` shrinks from 442 LOC
    to 136 LOC — the duplicate WS class is deleted.  File now holds
    only `object WsConnection` as a thin bridge: slot-mgmt delegators
    + `asValue(ws, interp, log, request): Value` that wraps a shared
    `WebSocket` into the byte-identical `Value.InstanceV("WebSocket", …)`
    user code expects.
  - `WsProxy.scala` constructs the shared
    `scalascript.server.jvm.WebSocket` directly, passing the
    interpreter's `wsExecutor` and `heartbeats`.
  - `backendInterpreter` gains a `dependsOn(runtimeServerJvm)` so the
    shared class is on its classpath.
  - `TlsProxy.scala` left as-is — it uses `BlockingWsSession`
    (outbound-client-side blocking session), not `WsConnection`.

  Net delta: −252 LOC across the four files; one `WebSocket` class,
  two backends, identical wire behaviour.  All 18 WS conformance
  tests + 8 JvmGenEffectsRuntimeTest (incl. scala-cli end-to-end
  compile of `serve(8080)` modules) pass.

  Option B fully closed — both thread model AND the class itself are
  unified across backends.

- **v1.17.6 — HTTP/WS Server SPI (Option C extended to three backends)**
  ✓ Landed (2026-05-19).  Full design in
  [`docs/http-server-spi-plan.md`](docs/http-server-spi-plan.md).

  Pluggable network-layer backend for HTTP + WS.  Three production
  implementations on the same SPI:

  | Module | Backend | Status | Tests |
  | --- | --- | --- | --- |
  | `runtime-server-jvm` | Jdk (default, zero deps) | S1b ✓ | 18 WS + 8 JvmGen |
  | `runtime-server-jvm-jetty` | Jetty 12.0.13 | S2 ✓ | 6 smoke |
  | `runtime-server-jvm-netty` | Netty 4.1.118 | S3 ✓ | 6 smoke |

  Shape: `HttpServerSpi` (the backend) ↔ `HttpHandler` (the
  application's route lookup + dispatch — same interface for all
  three backends) ↔ per-connection `WsListener` + `WsControls`.
  Selection: hybrid `ServiceLoader[HttpServerSpi]` discovery + a
  `setHttpServerBackend(name)` intrinsic for explicit picks when
  multiple impls are on the classpath.  Default ssc bundles only
  the JDK impl (zero new deps); Jetty / Netty are opt-in via sbt.

  Five sub-commits:

  - **S1a** — `runtime-server-spi` module + trait definitions.
    `HttpServerSpi`, `HttpHandler`, `WsListener`, `WsControls`,
    enums `HttpResult` / `WsUpgradeResult`, case class `TlsConfig`.
  - **S1 scaffold** — three backend modules in sbt + ServiceLoader
    `META-INF/services/scalascript.server.spi.HttpServerSpi`
    registration + stub impls (`NotImplementedError`).  All three
    discover-but-stub-throw.
  - **S1b** — `JdkServerBackend` (351 LOC) actual impl: blocking
    `ServerSocket` + per-VT proxy + internal `HttpServer` for plain
    HTTP routing + per-connection WS via the shared `WebSocket`
    class wrapped as `JdkWsControls`.  Interpreter `WebServer.start`
    wired through `HttpServerBackends.current()`; new
    `InterpreterHttpHandler` (390 LOC) does route lookup + middleware
    chain + auth + slot reservation.
  - **S2** — `JettyServerBackend` (393 LOC) using Jetty 12 — full
    HTTP path (HttpDispatchHandler translating jetty.Request ↔ POJO
    Request) + WS upgrade via `WebSocketUpgradeHandler` +
    `Session.Listener.AbstractAutoDemanding` endpoint + `JettyWsControls`
    wrapping the Session.  Smoke suite covers HTTP + Reject + WS
    echo + WS Reject.
  - **S3** — `NettyServerBackend` (430 LOC) using Netty 4 —
    `ServerBootstrap` on `NioEventLoopGroup` + `HttpServerCodec` +
    `HttpObjectAggregator` + manual upgrade via
    `WebSocketServerHandshakerFactory` (so Reject can short-circuit
    with a custom status + `X-WS-Reject-Reason` header) + custom
    `NettyWsFrameHandler` + `NettyWsControls` wrapping `Channel` +
    handshaker.  Same smoke suite shape as Jetty.
  - **S4** — `HttpServerBackends` selection registry +
    `setHttpServerBackend(name)` intrinsic (in
    `backend-interpreter/.../intrinsics/Ws.scala`).  Loud failure
    when an unknown name is picked.  Docs/spec updated to reflect
    the landing.

  **SPI trait shape: zero changes across all three impls.**  The
  design loop's biggest worry — "the trait will need to bend when a
  real second impl arrives" — was right to flag but didn't bite.

  **Subsequent landings (same v1.17.6 track):**
  - **S1c** ✓ Landed — codegen `JvmGen.serveRuntime` now routes
    through the SPI; `setHttpServerBackend` works in generated
    scripts too.
  - **CLI flag** ✓ Landed — `ssc compile --server-backend <jdk|jetty|netty>`
    auto-injects `//> using dep` directive + `setHttpServerBackend`
    init for the chosen backend.
  - **Docs + example** ✓ Landed — `docs/http-server-backends.md`
    + `examples/rest-jetty.ssc`.

  **What's NOT done yet** (carried as open follow-ups):
  - **Per-backend features** — Capability enum + sub-package
    tuning.  Design recorded in `docs/http-server-spi-plan.md`
    "Per-backend features — deferred follow-up design" section.
    Concrete sequencing: F1 (Capability + intrinsics, ~1 day)
    → F2 (permessage-deflate impl, ~1-2 days) → F3 (HTTP/2, ~3-4
    days) → F4 (HTTP/3, ~1 week) → F5 (server push, ~2-3 days) →
    F6 (Jetty/Netty tuning sub-packages, ~1 day each).  ~3-4 weeks
    total for the full roadmap; each phase independently shippable.
  - Benchmark suite comparing the three backends side-by-side.
  - Maven publishing (Option A+ from `docs/runtime-server-strategic-plan.md`)
    so the `//> using dep` directives the CLI injects actually
    resolve from Maven Central — today requires `sbt publishLocal`.

- **v1.18 — Frontend framework SPI (abstract model + four backends)**
  In progress 2026-05-19.  See
  [`docs/frontend-framework-spi-plan.md`](docs/frontend-framework-spi-plan.md)
  (SPI mechanics) +
  [`docs/frontend-abstract-model.md`](docs/frontend-abstract-model.md)
  (framework-agnostic programming model).

  The scalajs-spa backend gets a pluggable frontend-framework layer.
  Same `.ssc` user code compiles to React, Vue, Solid, or a custom
  in-house runtime — pick at build time via
  `setFrontendFramework("solid")` intrinsic or
  `ssc compile --frontend solid app.ssc`.  Designed around five
  universal primitives (`Signal[T]`, `Computed[T]`, `Effect`, `View`,
  `Component[P]`) that every backend can implement.  Eleven-primitive
  extended set (+ `domRef` / `context` / `suspense` / `portal` /
  `untrack`) covers ~90% of real UI work across frameworks.

  Sub-phases (each independently shippable):

  - **Phase A1** — `frontend-core` sbt module + primitive trait
    definitions + `FrontendFrameworkSpi` trait + `Capability` enum
    + `FrontendFrameworks` selection registry.  ~3 days.
  - **Phase A2** — `frontend-custom` backend.  Direct interpretation
    of the primitives via a minimal Scala-compiled-JS runtime
    (signals + Set-of-subscribers + direct DOM ops; ~3-5 KB bundle).
    Default for `ssc compile` without `--frontend`.  ~2 weeks.
  - **Phase A3** — `frontend-react` backend.  Lowers Signal to
    `useState`, Component to function components, View to
    `React.createElement`.  This is the SPI-shape-validation phase
    (same lesson as HTTP/WS SPI's S2).  ~2 weeks.
  - **Phase A4** — `frontend-solid` backend.  Signals fit naturally;
    fine-grained subs.  ~1 week.
  - **Phase A5** — `frontend-vue` backend.  ~1 week.
  - **Phase A2e** ✓ Landed 2026-05-20 — reactive `ForSignal` (dynamic
    lists).  `ReactiveSignalList[T]` (primitive `T`) +
    `View.ForSignal(items, tag, attrs)` + two list-mutation events
    (`PushSignalLiteral`, `ClearSignalList`).  Per-backend lowerings:
    Custom uses an `__ssc_lists` registry + wipe-and-rebuild
    subscriber; React uses `useState([...])` + `array.map` with
    string keys; Solid uses `createSignal` + `createEffect` that
    wipes-and-rebuilds (real `<For>` needs JSX); Vue uses
    `ref([...])` + `this.<name>.map` in the render arrow.  Each item
    renders as `<tag attrs>String(item)</tag>` — rich per-item
    templates need a richer IR and are deferred.  Tests: 5 unit + 1
    e2e per backend (4 backends → 23 new tests; 120 total in
    frontend-* suites).
  - **Phase A6** (refs + portals) ✓ Landed 2026-05-20 — DOM refs and
    portals across all four frontend backends.  IR additions:
    `final class DomRef(jsName: String)` + a new
    `AttrValue.RefBinding(ref: DomRef)` (composes through the existing
    `Element.attrs` map with the reserved-by-convention key `"ref"`),
    plus `View.Portal(target: String, children: Seq[View])` for
    rendering subtrees into a foreign DOM location.  Per-backend
    lowerings:
      * Custom: `let <jsName> = null;` at module scope + `<jsName> =
        element;` after `createElement`; portal uses
        `document.querySelector(target).appendChild(...)` with a loud
        runtime error if the target is missing.
      * React: `useRef(null)` hoisted at the top of `App()` + `ref:
        <jsName>` prop on the createElement call; portal uses
        `ReactDOM.createPortal(child, document.querySelector(target))`
        (multi-child children wrapped in `React.Fragment` so the call
        receives one node).
      * Solid: same imperative shape as Custom (matches Solid's
        hand-written-imperative pattern; `solid-js/web`'s `<Portal>`
        needs JSX which we don't transpile).
      * Vue: `ref(null)` in `setup()` + Vue's reserved `ref` prop on
        `h(...)`; portal uses `h(Teleport, { to: target }, [...])` with
        the `Teleport` import added conditionally so portal-less
        bundles stay clean.
    Refs and the Custom-backend Portals capability now live on the
    `Capability` set (React / Solid / Vue already declared Portals).
    DomRef `jsName` validates against `[A-Za-z_][A-Za-z0-9_]*` (same
    contract as `ReactiveSignal`).  Tests: 5 emit + 1 jsdom-or-parse-
    only E2E per backend (24 new tests; 137 total in frontend-* suites).
    **Deliberately deferred to A6.2:** context (React.createContext /
    Vue provide-inject / Solid createContext) and Suspense — both
    require richer IR (provider scopes, async boundaries) and weren't
    in scope for this slice.
  - **Phase A7** ✓ Landed 2026-05-20 — `setFrontendFramework(name)`
    interpreter intrinsic + `ssc emit-spa --frontend <custom|react|
    solid|vue>` CLI flag.  Mirrors v1.17.6 `setHttpServerBackend` /
    `--server-backend`.  The intrinsic flips the
    `FrontendFrameworks.setBackend(name)` registry choice at .ssc
    runtime (throws if no impl with that name is on the classpath —
    loud failure over silent fallback); the CLI flag does the same
    at JVM-codegen time for `emit-spa`.  CLI now bundles all four
    `frontend-*` impl modules so every name resolves out of the box.
    Validation lives in `validFrontendNames`; the helper
    `applyFrontendBackend(name)` is the single CLI-side selection
    point future commands can route through as the SPA emit path
    grows to consume the SPI in A8.  Tests: 6 unit + 1 e2e in
    `FrontendIntrinsicTest`, 5 in CLI `FrontendBackendSelectionTest`,
    plus a smoke run through `emit-spa --frontend react`.
  - **Phase A8** ✓ Landed 2026-05-20 — Docs + three canonical
    reference apps lowered through all four backends.  New sbt
    module `frontend-examples` (option (c) in the A8 plan: a test-
    driven module that also exposes a runnable `EmitAll` main
    writing per-backend HTML + JS files to `target/frontend-examples/`).
    Three demos: `counter` (`ReactiveSignal[Int]` + `IncrementSignal`
    + `SetSignalLiteral` + `SignalText`), `show-hide`
    (`ReactiveSignal[Boolean]` + `ToggleSignal` + `ShowSignal`),
    `todo` (`ReactiveSignalList[String]` + `PushSignalLiteral` +
    `ClearSignalList` + `ForSignal`).  Each demo emits identically
    through all four backends — 12 (3 demos × 4 backends) HTML + JS
    pairs ship.  New user-facing doc `docs/frontend-usage.md` covers
    backend selection (`setFrontendFramework` intrinsic +
    `ssc emit-spa --frontend <name>` CLI flag from A7), every
    primitive's per-backend lowering, the deliberate
    "no JVM closures in event handlers" restriction, and the v1.18
    limitations (primitive-only signal values, single-tag `ForSignal`
    template, no refs/context/suspense/portals/router yet).  Top-
    level `examples/frontend/README.md` plus per-demo READMEs
    explain the `.ssc`-level intent and the four flavours of emitted
    JS for each.  Tests: 17 new shape + smoke tests in
    `ReferenceAppsTest`; all 6 frontend suites green (136 tests:
    6 + 39 + 25 + 24 + 25 + 17).

  Total: ~7-8 weeks for the full core; SSR + Svelte + animations as
  later follow-ups.

  **Why this isn't just "pick React and standardise":** see the
  spec's `## Why this is worth doing` section.  TL;DR: ecosystem
  leverage, framework churn over 5-year horizons, cross-paradigm
  experiments only abstract models enable.

### Deferred follow-ups (v1.17.x backlog, ordered by priority)

1. ~~**Own implementation for INT / scalajs-spa**~~ — Phase 1 + 2 + 3
   all landed 2026-05-19.

   **Phase 1** (Stdio + Spawn): pure-Scala JSON-RPC 2.0 + dispatch
   extracted into new sbt module `mcp-common` (`scalascript.mcp.{JsonRpc,
   McpProtocol, McpServerCore, McpClientCore}`); `Feature.McpServer`
   + `Feature.McpClient` added to `InterpreterCapabilities`; intrinsics
   `mcpServer` / `serveMcp(Transport.Stdio)` /
   `mcpConnect(Transport.Spawn)` wired via `intrinsics/Mcp.scala`.

   **Phase 2** (HTTP roundtrip on INT): `mcp-common/McpHttpClient`
   (Java HttpClient wrapper) + `McpServerCore.handleHttpRequest`;
   `serveMcp(Transport.Http(port, path))` registers a POST handler on
   the existing WebServer;
   `mcpConnect(Transport.Http("http://host:port/path"))` returns a
   client backed by HTTP roundtrip.

   **Phase 3** (scalajs-spa browser client): new JS preamble
   `JsRuntimeMcpBrowser` providing `mcpConnect(Transport.Http(...))` over
   synchronous `XMLHttpRequest` — no Node deps (`require` /
   `worker_threads` / `SharedArrayBuffer` deliberately avoided so the
   preamble runs in any browser without cross-origin-isolation
   headers).  `mcpServer` / `serveMcp` raise actionable
   "browser cannot host an MCP server" errors.  `Feature.McpClient`
   added to `ScalaJsCapabilities`; the SPA HTML output (`emit-spa`
   command) splices the preamble in only when user JS references
   `mcpConnect` / `mcpServer`.

   **Phase-2 follow-ups landed 2026-05-19** in the same series:
   - **Browser async client** (`mcpConnectAsync(transport, timeoutMs)`)
     in `JsRuntimeMcpBrowser` using `fetch` + `AbortController`.  Same
     wire format and adapters as the sync `mcpConnect`, but every
     method returns a Promise — doesn't block the main thread.
   - **`Transport.Ws`** for both server and client on the interpreter.
     Server reuses `ctx.registerWsRoute` and pipes inbound text frames
     through `McpServerCore.handleHttpRequest`.  Client is a new
     `mcp-common/McpWsClient` built on Java's `HttpClient` WebSocket
     builder; same async pending-request pattern as `McpClientCore`
     but routed over a single persistent WS instead of subprocess
     stdio.
   - **Server→client notifications** for transports with a persistent
     channel (Stdio / Spawn / Ws).  Both `McpClientCore` and
     `McpWsClient` gain a `setNotificationHandler` hook; the
     user-facing `McpClient` value exposes `onNotification(handler)`
     that the user-side block can register `(method, params) => Unit`
     callbacks against.
   - **Server-side `srv.notify(method, params)`** broadcasts to every
     currently-active subscriber via a thread-safe set on the
     builder.  Each transport registers its writer on connection and
     unregisters on close (Stdio/Spawn: serve() addSubscriber; Ws:
     per-connection ws.send + onClose teardown).
   - **HTTP SSE GET stream** — `serveMcp(Transport.Http(port, path))`
     additionally registers `GET <path>/events` which subscribes the
     SSE writer to `builder.notify` broadcasts.  Matching client side:
     `McpHttpClient.setNotificationHandler` spins up a daemon reader
     thread that opens `<url>/events`, parses `data: <json>\n\n`
     frames, and dispatches them.  Browser side: `JsRuntimeMcpBrowser`
     subscribes via native `EventSource` in both sync and async
     `McpClient` flavours.  HTTP is now fully push-capable in parity
     with Stdio/Spawn/Ws.

   - **Bidirectional sampling** — server-initiated JSON-RPC requests
     (e.g. `sampling/createMessage`).  `McpClientCore`/`McpWsClient`
     gain `setRequestHandler((method, params) => result)`; the
     dispatcher routes inbound Request frames to it and ships the
     result/error back as a Response.  `McpServerBuilder` tracks
     outstanding ids in `serverPending`; `request(method, params,
     timeoutMs)` broadcasts to subscribers and blocks on the first
     matching reply.  Stdio `serve()` and `handleHttpRequest` route
     inbound Response frames into `routeInboundResponse(resp)`.
     User-facing `srv.request(...)` + `client.onRequest(handler)`
     exposed on Spawn / Ws / Stdio.

   - **HTTP-side bidirectional sampling** — McpHttpClient's SSE
     reader now also parses Request frames (in addition to
     Notifications); the registered `onRequest` handler runs and
     the result/error is POSTed back to the same `/mcp` URL as a
     Response frame.  Server-side `handleHttpRequest` already
     handles Response frames via `routeInboundResponse(resp)`, so
     no new server endpoint is needed.  Browser preamble
     (`JsRuntimeMcpBrowser`) gets the same treatment: both
     `mcpConnect` (XHR) and `mcpConnectAsync` (fetch) variants
     dispatch EventSource-delivered requests through the handler
     and POST the response back.  HTTP is now fully symmetric
     with Stdio/Spawn/Ws for bidirectional sampling.

   - **Streamable-HTTP SSE response body** — when the client sends
     `Accept: application/json, text/event-stream`, the HTTP POST
     /mcp handler can stream `data: <json>\n\n` SSE frames that
     interleave progress notifications (emitted via `srv.notify`
     during tool execution) with the final response.  The POST
     handler in `installHttpRoute` detects the Accept header and
     switches to a `StreamResponse` whose callback registers the
     SSE writer as a `builder.addSubscriber` for the duration of
     `handleHttpRequest`, then writes the final response as one
     more SSE frame.  Client side: `McpHttpClient.request`
     inspects `Content-Type` and dispatches Notification frames to
     `notificationHandler` inline while waiting for the matching
     Response frame.  Tool authors can emit `srv.notify("progress",
     ...)` during a long-running tool and the client sees both the
     progress updates and the final result via a single
     `request(...)` call.
   - **`derives McpSchema` + `srv.toolWithSchema`** — `McpSchema` is a
     built-in typeclass registered as a global by `initBuiltins`; its
     `derived(mirror)` method walks `mirror.fields` (v1.14 Mirror's
     field-name list) to produce a JSON Schema object
     `{type: "object", properties: {name: {}, ...}, required: [...]}`.
     User code: `case class WeatherArgs(city: String) derives McpSchema`
     synthesises `given McpSchema[WeatherArgs]` whose `schema` field is
     the generated map.  `srv.toolWithSchema(name, schema)(handler)`
     registers a tool with an explicit JSON Schema, replacing the
     default `{type: "object"}` schema that `srv.tool` advertises.
     Limitation: v1.14 Mirror exposes field NAMES only, so per-field
     types are blank — the schema is loose but valid JSON Schema and
     populates the LLM-facing tool description.
   - **`Using.resource(r) { r => block }` RAII** — built-in generic
     helper that runs the block and unconditionally calls `r.close()`
     on the way out (try-finally semantics).  Works on `InstanceV`
     (case-class instances with a `close` field) and `MapV` (plain
     `Map` literals with a `"close"` key) alike — ducktyped.
     Resources without a `close` member are still scoped (the
     resource is just dropped to GC at block end).  Mirrors
     `scala.util.Using.resource` semantics without the typeclass
     dance.  Idiomatic for `Using.resource(mcpConnect(transport)) {
     client => client.callTool(...) }`.
   - **Resource subscriptions** — MCP `resources/subscribe` /
     `resources/unsubscribe` + `notifications/resources/updated`.
     Dispatch handles both subscribe methods (returns empty success);
     `McpServerBuilder` tracks subscribed URIs in a thread-safe set;
     `onResourceSubscribe(handler)` / `onResourceUnsubscribe(handler)`
     fire on each request — typical wiring spins up a file/DB watcher
     in the subscribe hook.  `srv.notifyResourceUpdate(uri)` pushes a
     `notifications/resources/updated` frame to all subscribers iff
     `uri` is subscribed (saves traffic for unwatched resources).
     `initialize` capabilities now advertise `resources.subscribe: true`
     so MCP clients know the server honours the subscription protocol.

   76 tests across fourteen suites all pass (added
   McpDerivesRaiiTest 5 + McpResourceSubscriptionTest 6).

   v1.17.x is now feature-complete on all 5 transport surfaces
   (Stdio / Spawn / Http / Ws / Browser-XHR + Browser-fetch) with
   full bidirectional capability (server↔client requests +
   notifications + streaming responses), typed-schema tool
   registration, RAII resource scoping, and live-updating resource
   subscriptions.

2. ~~**Type-class layer** (`derives McpSchema`)~~ — landed 2026-05-19
   (above).
3. ~~**Streaming resources**~~ — landed 2026-05-19 as resource
   subscriptions (`resources/subscribe` + `notifications/resources/updated`,
   above).  Generator-based chunked-content for huge single resources
   stays deferred (MCP wire returns one ResourceResult envelope, so
   protocol-level chunked transfer isn't a thing — the in-memory
   payload itself is the only optimisation).
4. ~~**Bidirectional sampling**~~ — landed 2026-05-19 (above).
5. ~~**`using mcpConnect(...) { client => … }` RAII**~~ — landed
   2026-05-19 as `Using.resource(...)` (above).
6. **MCP protocol version negotiation** when v2 emerges.

### Open questions

- Typed tool args via v1.14 derives — when to layer in
- `throws[ToolResult, McpError]` integration with v1.15 — wait
  until v1.15 stabilises
- `srv.onInitialize` hook for per-client customisation
- Server-side per-tool authorisation helper
- MCP request / response observability hooks

### Effort

Seven phases, ~3 weeks end-to-end.  JS and JVM intrinsics
can be worktrees in parallel after Phase 1.  Phase 6 (feature
flags) is the only piece touching the SPI.

## v1.18 — `package` keyword + std layout migration ✓ Landed (2026-05-19)

Promotes the v0.7 "package keyword (optional, mostly
cosmetic)" out of deferred-future status into a real
milestone.  Closes the namespace-collision risk between
third-party libraries before community packages start
surfacing.  Pairs with v1.19 URL/dep imports — together
they unlock decentralised library distribution.

Full design in [`docs/modularity.md`](docs/modularity.md) §9.

```scala
---
package: org.example.ui
exports: [Card, Button]
---

# Card

```scalascript
object Card { val css = ...; def render(t: String): String = ... }
```
```

Consumer:

```scala
[Card as MyCard](dep:org.example/ui:1.0)
[Card as TheirCard](dep:other.org/components:2.1)

println(MyCard.render("hi"))
println(TheirCard.render("hello"))
```

Both `Card`s coexist because their full qualified names
differ (`org.example.ui.Card` vs `other.org.components.Card`).

### Phase 1 — Parser + typer (~1.5 days) ✓ Landed

`package:` keyword in frontmatter parsed by `Parser.scala`
into `Manifest.pkg: Option[List[String]]`.  `wrapSectionInPackage`
wraps all parseable code blocks in nested `object` declarations
matching the segments, so `Card` in a `package: org.example.ui`
module becomes `org.example.ui.Card` at the AST level.

### Phase 2 — Codegen per backend (~6 days) ✓ Landed

All three backends (Interpreter, JvmGen, JsGen) use `cb.tree`
which already contains the wrapped AST — no per-backend
emission changes needed.  Import resolution updated:

- **Interpreter** (`runImport`): `lookupExport` navigates the
  nested `InstanceV` hierarchy using the child module's
  `exportedPkg` to find symbols by short name.
- **JvmGen** (`aliasBlock`): generates `val Card = org.example.ui.Card`
  for all bindings (aliased and bare) when the imported
  module has a `pkg` prefix.
- **JsGen** (`genImport`): generates `const Card = org.example.ui.Card;`
  for all bindings when pkg is non-empty.

### Phase 3 — Std layout migration ✓ Landed (all std files)

All std files carry a `package:` declaration in their frontmatter.
Packages assigned:

| file | package |
|------|---------|
| `actors.ssc` | `std.actors` |
| `bifunctor.ssc` | `std.bifunctor` |
| `coroutine.ssc` | `std.coroutine` |
| `dsl/ast.ssc` | `std.dsl` |
| `dsl/builders.ssc` | `std.dsl` |
| `dsl/passes.ssc` | `std.dsl` |
| `dsl/pretty.ssc` | `std.dsl` |
| `dsl/walker.ssc` | `std.dsl` |
| `either.ssc` | `std.either` |
| `eq.ssc` | `std.eq` |
| `error-handling.ssc` | `std.error_handling` |
| `foldable-traversable.ssc` | `std.foldable_traversable` |
| `free.ssc` | `std.free` |
| `functor-applicative-monad.ssc` | `std.functor_applicative_monad` |
| `generators.ssc` | `std.generators` |
| `hash.ssc` | `std.hash` |
| `http.ssc` | `std.http` |
| `index.ssc` | `std` |
| `mapreduce/*.ssc` | `std.mapreduce` |
| `mcp/*.ssc` | `std.mcp` |
| `middleware.ssc` | `std.middleware` |
| `monad-control.ssc` | `std.monad_control` |
| `monaderror.ssc` | `std.monaderror` |
| `nodes.ssc` | `std.nodes` |
| `order.ssc` | `std.order` |
| `parsing/combinators.ssc` | `std.parsing` |
| `parsing/core.ssc` | `std.parsing` |
| `parsing/helpers.ssc` | `std.parsing` |
| `parsing/layout.ssc` | `std.parsing` |
| `parsing/recovery.ssc` | `std.parsing` |
| `selective.ssc` | `std.selective` |
| `semigroup-monoid.ssc` | `std.semigroup_monoid` |
| `show.ssc` | `std.show` |

The Phase 2 aliasing (Interpreter `lookupExport`, JvmGen
`aliasBlock`, JsGen `genImport`) makes all existing short-name
imports backward-compatible.

Also landed: interpreter `mergeDeep` — recursive InstanceV merge
for same-named objects across multiple code blocks in one module.
Without this, files with several `scalascript` fenced blocks (like
`free.ssc`) would have their package-wrapped objects silently
overwritten on each successive block.

### Phase 4 — Conformance + docs ✓ Landed

`PackageKeywordTest.scala` — 7 tests covering:
- single-segment package import
- multi-segment package import (`org.example.ui`)
- `as` alias alongside package prefix
- collision-free: two modules export the same short name
- no-package modules still work by short name
- error on unknown name in packaged module

### Hard-no list (locked by design — `docs/modularity.md` §9
+ §11)

- Implicit package from directory layout — explicit
  `package:` only
- Wildcard imports (`import std.mcp.*`) — per-name
  discipline stays
- `package object` — Scala 3 deprecated; not reintroducing

### Status: ✓ Landed (all phases)

## v1.19 — URL / dep imports ✓ Landed

**Landing notes (2026-05-18):**
- Phase 1 ✓: `LockFile.scala` — SHA-256 integrity model; YAML read/write/check/pin
- Phase 2 ✓: `dep:` scheme in `ImportResolver` — `dep-sources` chain lookup + lock integration
- Phase 3 ✓: All three backends thread `lockPath` through `inlineImport` / `genImport` / `runImport`
- Phase 4 ✓: `ssc lock <file>` and `ssc lock check <file>` CLI commands
- Phase 5 ✓: `LockFileTest` — 9 conformance tests (sha256, pin/check, YAML round-trip, error cases)
- Hard-no enforced: URL not in existing `ssc.lock` → build error ("run `ssc lock` first")
- Central registry deferred to v1.19.x as planned

Builds on v1.7 Tier 2 (`.sscpkg` archive landed) and v1.18
(`package` keyword) to enable distributed `.ssc` library
distribution **before** a central registry exists.

```scala
[Card](https://github.com/user/lib/main/Card.ssc)
[Tools](dep:org.example/mcp-tools:1.2)
```

Full design in [`docs/modularity.md`](docs/modularity.md) §10.

### Two resolution strategies

**URL imports**: HTTPS-fetched at compile time; cached at
`~/.cache/scalascript/url-imports/`; SHA-256
integrity-checked (recorded in `ssc.lock`).

**Dep imports**: `dep:org.example/lib:1.2` looked up
through a chain — local cache → user's
`~/.config/scalascript/dep-sources` list of HTTP
endpoints → eventually `registry.scalascript.io` (deferred
to v1.19.x).

Both produce a `ssc.lock` file alongside the entry point;
`ssc check` re-validates lock consistency.

### Phase 1 — URL import resolver (~5 days)

- HTTPS fetch of `.ssc` (or `.sscpkg` archive) with
  Content-Type sniffing
- Local cache layout (`~/.cache/scalascript/url-imports/`)
  with SHA-256 keys
- Integrity check on each load; mismatch → build error
- `ssc.lock` write-on-fetch / verify-on-read

### Phase 2 — Dep import resolver (~3 days)

- `dep:` URL scheme parsing (`dep:org.scope/name:version`)
- Source-list lookup chain
- Lock-file integration

### Phase 3 — Per-backend integration (~3 days)

Each backend's import-resolution path consults the
URL/dep resolver before falling back to file-relative
paths.  ~1 day × 3 backends.

### Phase 4 — `ssc check` / `ssc lock` CLI (~2 days)

- `ssc lock` — recompute the lock file
- `ssc check` — verify lock file vs current resolutions
- Helpful errors for stale / missing entries

### Phase 5 — Conformance + docs (~1 day)

- `url-import-roundtrip.ssc` — fetch a known-content URL,
  verify SHA, use the import
- `dep-import-cache-hit.ssc` — second build hits cache,
  doesn't network
- `lock-file-mismatch.ssc` — modified upstream produces a
  build error per lock policy

### Hard-no list (locked by design — `docs/modularity.md`
§10 + §11)

- **Auto-resolve transitive deps from URL imports** —
  user must hoist into their own lock file
- **`git://` / `git@` imports** — host `.sscpkg` at HTTPS
  instead
- **Mutable URL imports without integrity check** — every
  URL gets a SHA-256 in `ssc.lock`
- **Silent default-pin on missing lock file** — refuse to
  fetch; require explicit `ssc lock`

### Deferred to v1.19.x

- **Central registry** (`registry.scalascript.io`) — publish
  / yank / search.  Deployment + ops concern with its own
  milestone shape
- **Semver resolution across dep imports** — start with
  exact pinning; semver requires registry semantics

### Effort

Five phases, ~2 weeks end-to-end.  Independent of v1.18
beyond the policy-level dependency.

## v1.20 — DSL primitives + `std/parsing` ✓ Landed (Phases 1–6)

**Landing notes (2026-05-19):**
- Phase 1 ✓: User-defined string interpolators — `StringContext` extension methods on all backends; `DslInterpolatorTest` suite.
- Phase 2 ✓: `std/parsing/core.ssc` — `Parser[A]` ADT + primitive constructors + `ParseResult` / `Span`.
- Phase 3 ✓: `std/parsing/combinators.ssc` — combinator ADT + recursive-descent interpreter.
- Phase 4 ✓: `std/parsing/helpers.ssc` — tokenization helpers; JSON parser conformance test.
- Phase 5 ✓: `std/dsl/` — `ast.ssc`, `pretty.ssc`, `builders.ssc`; `DslTest` suite.
- Phase 6 ✓: `docs/dsl.md` + examples (`examples/dsl/`).
- v1.20.1–v1.20.3 (error recovery, indentation-aware, multi-pass) — deferred sub-milestones.

Ships infrastructure for three flavours of DSL: internal
eDSL (works today, just document the patterns),
interpolator DSL (`extension (sc: StringContext) def
myDsl(args)...` cross-backend), and external DSL via a new
`std/parsing/*` parser-combinator library and `std/dsl/*`
helpers for AST + pretty-printing.

**Architectural lock — reified by default** (see
`docs/dsl.md` §2.5): all three flavours produce *data*
(combinator trees / AST nodes), and execution is an
**explicit extension method** call (`.exec`, `.parse(input)`,
etc.).  Same `Parser[A]` value can run via recursive-descent
(default), Pratt (v1.20.x perf), grammar validation, or
compile-time via v1.14 `inline` — interpreters are
independent extensions over the same data.

Full design in [`docs/dsl.md`](docs/dsl.md).

```scala
// Internal — works today
val q = from(users).where(_.age > 18).select(_.name)

// User-defined interpolator — v1.20 verifies cross-backend
extension (sc: StringContext)
  def sql(args: Any*): SqlQuery = SqlQuery.compile(sc.parts, args)

val q = sql"SELECT * FROM users WHERE age > $minAge"

// External — v1.20 ships std/parsing combinators
val expr: Parser[Double] = (term ~ (char('+') ~> term).*).map(...)
val parsed = expr.parse("1 + 2 * 3")  // Ok(7.0)
```

### Phase 1 — User-defined interpolators (~3 days)

Verify and fix `StringContext` extension methods across
INT / JS / JVM.  Conformance: round-trip, escaping, typed
arg substitution.  Largest risk: interpreter may not
natively support StringContext-based interpolators — Phase
1 surfaces the cost.

### Phase 2 — `std/parsing/core.ssc` — reified `Parser[A]` ADT + primitives + context nodes (~2.5 days)

`enum Parser[+A]` (case-class-per-combinator-node per
`docs/dsl.md` §5.2), `Position`, `Span` (locked per §5.6),
`ParseResult`, `ParseError`, plus `ParserContext` marker
trait + `NoContext` + `WithLocalContext` / `ReadContext`
nodes (per §5.8).  Primitive constructors `Parser.char` /
`string` / `regex` / `satisfy`.  No interpreter yet —
pure data.

Conformance: each constructor builds the right ADT node;
pattern-matchable; context nodes round-trip through
identity evaluator.

### Phase 3 — `std/parsing/combinators.ssc` + default interpreter (~3 days)

Combinator extensions producing `Parser.X` nodes (per
§5.3).  **Includes left-recursion family** (§5.3.1):
`chainLeft`, `chainRight`, `chainPostfix`, `chainPrefix`
— locked since these are the v1 answer to PEG's no-
left-recursion limit.

Default interpreter shipped as `extension [A](p: Parser[A])
def parse(input, pos, ctx): ParseResult[A]` (per §5.3.2);
context parameter defaults to `NoContext`.  Specialised
interpreters (`compileToPratt`, `validate`, `parseInline`)
are explicit v1.20.x follow-ups, NOT in this phase.

Conformance: calculator from §5.4; left-recursive
calculator using `chainLeft`; context round-trip via
read + local-update; alternative interpreters in
v1.20.x.

### Phase 4 — `std/parsing/helpers.ssc` (~2 days)

Tokenization: `whitespace`, `identifier`, `number`,
`stringLit`, `keyword(s)`.  Conformance: JSON parser
written entirely from helpers.

### Phase 5 — `std/dsl/*` helpers (~3-4 days)

Ships in `std/dsl/`:

  - **`types.ssc`** — `Span` + `HasSpan` (locked, per
    `docs/dsl.md` §5.6); `Param[T]` + `RawInline` hygiene
    helpers (tentative, per §5.7); `Exec[D[_], F[_]]`
    convention (tentative, per §7.1)
  - **`ast.ssc`** — common AST node shapes, source-position
    threading
  - **`pretty.ssc`** — pretty-printer combinators (start
    simple, Wadler `Doc[A]` per §10 open question)
  - **`walker.ssc`** — AST walker / cata for name resolution
    and type-check recursion patterns
  - **`builders.ssc`** — type-safe builder pattern via
    phantom types (locked, per `docs/dsl.md` §3.5):
    reusable scaffolding (phantom-type aliases, common
    template) for required-field-tracked builders

Conformance: typed Calc AST with pretty-printer
round-trip; phantom-typed `HttpRequestBuilder` with
compile-error on missing required field.

### Phase 6 — Documentation + examples (~2 days)

  - `examples/dsl-sql-interpolator.ssc` — full SQL-like
    interpolator with compile-time validation
  - `examples/dsl-calc-parser.ssc` — calculator with parser
    combinators
  - `examples/dsl-json-parser.ssc` — JSON parser from `std/parsing`
  - `examples/dsl-typed-builder.ssc` — phantom-typed
    builder pattern from `std/dsl/builders.ssc`
  - `examples/dsl-multi-flavour.ssc` — canonical
    composition: internal eDSL + interpolator + direct-
    syntax in one route handler (per `docs/dsl.md` §3.7)
  - `docs/dsl.md` walkthrough updates

### Hard-no list (locked — `docs/dsl.md` §9)

- DSL = new keyword — reuse Scala 3 extension / interpolator /
  fluent-API mechanisms; no new syntax
- Parser-generator language (BNF/EBNF) — combinators give the
  same expressivity without a separate language
- Macros to inline parser combinators — runtime overhead is
  fine for v1; revisit on measurement
- Built-in DFA / Pratt parser — combinators cover 90% of
  needs; specialised parsers as community libs

### Effort

Six phases, ~2.5 weeks end-to-end.  Mostly stdlib work;
no compiler changes beyond Phase 1 interpolator
verification.

## v1.20.1 — DSL: parser error recovery ✓ Landed (Phases 1–4)

Closes the "DSL fail-fasts on first parse error → useless
for IDE / config files" gap.  Independent from v1.20 core
parser; opt-in extensions on the same `Parser[A]` ADT.
Ships as `std/parsing/recovery.ssc`.

What landed (2026-05-19):

- **Phase 1 — `PRecoverUntil` node + `recoverUntil` extension** —
  `parser.recoverUntil(syncOn, default)` advances to the next sync
  token on failure and returns the sentinel default value.
  `advanceToSync` helper walks input character by character until
  the sync parser matches.
- **Phase 2 — `ErrorNode` + `PErrorNode`** — `ErrorNode(message,
  span)` is a first-class AST node carrying a positioned parse
  error.  `parser.errorNode(default)` inserts an `ErrorNode` on
  failure and keeps position unchanged so outer combinators
  continue parsing.
- **Phase 3 — `PParseAll` + `parseAll` extension** —
  `parser.parseAll(input)` wraps the tree in `PParseAll` and drives
  `runParserAll`, which collects every `ParseError` into a list
  without short-circuiting.  Returns `(Option[A], List[ParseError])`.
- **Phase 4 — 3 conformance tests + IDE SQL example** —
  `conformance/parsing-recover-until.ssc`,
  `conformance/parsing-error-node.ssc`,
  `conformance/parsing-parse-all.ssc` (one per strategy);
  `examples/dsl-sql-recovery.ssc` — IDE-style SQL parser that builds
  a partial AST with error nodes for completion.

## v1.20.2 — DSL: indentation-aware parsing ✓ Landed (Phases 1–2)

**Landing notes (2026-05-19):**
- Phase 1 ✓: `ParserContext` trait + `NoContext` + `PWithLocalContext` /
  `PReadContext` ADT nodes added to `core.ssc` (v1.1.0); `localCtx` /
  `readCtx` extension methods; `runParser` in `combinators.ssc` (v1.1.0)
  threads context through all nodes; `parseWith` entry point added.
  `std/parsing/layout.ssc` (new): `IndentContext(currentLevel, stack)`;
  `columnOf` helper; `PSameIndent` / `PDeeperIndent` guard nodes;
  `runLayout` interpreter; `withIndent` / `sameIndent` / `deeperIndent` /
  `block` / `line` extension methods; `parseLayout` / `parseLayoutWith`
  entry points.
- Phase 2 ✓: `conformance/indent-config-format.ssc` — multi-level INI-style
  config DSL with 2-space indented entries; `conformance/indent-block-statements.ssc`
  — if/while/for block-structured scripting language; `examples/dsl-yaml-like.ssc`
  — YAML-flavoured nested mapping/sequence parser with query helpers.

Layout-sensitive parsing for DSLs with significant
indentation (config formats, query languages, scripting
DSLs).  Built on the §5.8 context-in-parser mechanism
shipped in v1.20 Phase 2.  Ships as `std/parsing/layout.ssc`.

```scala
[withIndent, sameIndent, deeperIndent, block, line](../std/parsing/layout.ssc)

case class QueryItem(field: String, value: String)

val queryItem: Parser[QueryItem] = 
  sameIndent ~> identifier ~ (char(':') ~> whitespace ~> identifier)
    .map((k, v) => QueryItem(k, v))

val query: Parser[List[QueryItem]] = 
  string("query") ~> identifier ~> queryItem.block.withIndent(2)

query.parse("""
query users
  name: alice
  email: alice@example.com
""")
```

### Phase 1 — `IndentContext` + helpers (~2 days)

`case class IndentContext(currentLevel: Int, stack:
List[Int]) extends ParserContext` in
`std/parsing/layout.ssc`.  `withIndent` /
`sameIndent` / `deeperIndent` / `block` / `line`
extension methods built on `localCtx` / `readCtx`
primitives from v1.20 §5.8.

### Phase 2 — Worked examples + conformance (~1-2 days)

Two conformance tests + one example:

  - `indent-config-format.ssc` — multi-level config DSL
    with significant indent
  - `indent-block-statements.ssc` — block-structured
    syntax (if/while/for) with indent-defined bodies
  - `examples/dsl-yaml-like.ssc` — YAML-flavoured config
    parser as the canonical demo

### Effort

Two phases, ~3-5 days.  Pure stdlib work.  Depends on
v1.20 Phase 2 (context ADT nodes); ships independently
once v1.20 lands.

## v1.20.3 — DSL: multi-pass pipeline ✓ Landed (Phases 1–4)

Full external-DSL story typically requires more than just
parse — name resolution, type-check, optimisation,
codegen.  This milestone ships pipeline combinators that
formalise the multi-pass pattern.

**What landed:**

- **`std/dsl/passes.ssc`** — `type Pass[A, B] = A => Either[List[PassError], B]`;
  `PassError(phase, message, source, line, col)`; combinators `andThen`
  (fail-fast sequential), `parallel` (fan-out, errors unioned), `recover`
  (fallback on failure), `traceAll` (log errors + success); `accumulate`
  (collect all errors from a list of passes without short-circuit);
  `withPhase` (annotate errors with a phase name); `PipelineReport` +
  `pipelineReport` + `formatReport` for phase-by-phase diagnostics.
- **`std/dsl/walker.ssc`** — `Visitor[A]` (pre/post hooks + children
  extractor); `walk[A](ast)(visitor)` (bottom-up traversal); `cata`
  (catamorphism / fold); `ana` (anamorphism / unfold); `transformChildren`.
- **`examples/dsl-mini-language.ssc`** — toy language (Num/Var/Add/Sub/Mul/Div)
  with four passes: parse → name-resolve → type-check → evaluate; shows
  `PipelineReport` formatted output.
- **`conformance/dsl-multi-pass.ssc`** — three scenarios: error at every
  phase (parse failure); error at exactly one phase (name-resolve: unbound
  variable); success through all phases.

## v1.21 — Local map-reduce (`Dataset[T]`) ✓ Landed (Phases 1–6)

Lazy `Dataset[T]` fluent API — sequential + parallel local execution
via Loom virtual threads on JVM; sequential fallback on JS (v1.3 Node
parallel deferred); cooperative on INT.  Streaming interop via
`fromGenerator` / `toGenerator`.

**What landed:**
- `std/mapreduce/{dataset,index}.ssc` — `extern class Dataset[T]` + `extern object Dataset`
  with full API: `map`, `filter`, `flatMap`, `take`, `drop`, `distinct`,
  `groupBy`, `reduceByKey`, `sortBy`, `collect`, `count`, `reduce`,
  `fold`, `foreach`, `first`, `toGenerator`, `runLocal`, `runParallel`;
  constructors `of`, `fromList`, `fromGenerator`, `fromFile`.
- `Feature.Dataset` in SPI; detected by `CapabilityCheck`; declared in
  JS + JVM + INT capabilities.
- `JsRuntimeDataset` — class-based lazy pipeline, `runParallel()` warns
  once and falls back to sequential on Node.
- `JvmRuntimeDataset` — Scala `_Dataset[T]` class; `runParallel()` partitions
  into `availableProcessors` chunks and processes each in a Loom virtual thread.
- `JvmGen.blocksUseDataset` — injects preamble when Dataset is used.
- `makeDatasetV` + `compareValues` in the tree-walking interpreter — lazy thunk
  pipeline; `runParallel()` is cooperative (same semantics as sequential for pure
  pipelines); `fromGenerator` eagerly drains the generator once on construction.
  Landed 2026-05-19.
- Compound-assignment operators (`+=`, `-=`, `*=`, `/=`, `%=`) added to the
  interpreter eval dispatcher and evalBlock step, enabling while-loop generators.
  Landed 2026-05-19.
- 10 conformance tests + 2 examples; INT PASS on 6 core dataset tests.
- v1.21 follow-up (2026-05-19): 10/10 dataset conformance tests now active
  and PASS across all eligible backends. Fixes:
  - JS `generator[T]` type-arg pattern in genExpr + genCpsExpr (the
    `Term.ApplyType.After_4_6_0(Term.Name("generator"), _)` shape was
    silently emitted as `unsupported` before).
  - JVM `class _Dataset[T]` (was `[+T]` — covariance violation with
    `reduce((T,T)=>T)`) and typed-T lambda signatures (`map[U](f: T => U)`
    with internal `x.asInstanceOf[T]`); previously `f: Any => U` made
    `ds.map(_ * 2)` fail with `* not a member of Any`.
  - JVM `def generator[T](body: () => Unit)` (was `body: => Unit` by-name
    which silently discarded the user's `() => body` lambda literal).
  - INT `Dataset.of[Int]()` — Term.Apply(Term.ApplyType(Term.Select, _), _)
    now dispatches with actual args, avoiding the InstanceV no-arg
    auto-call that returned an empty Dataset before the `()`.
  - INT `Dataset.reduce` on empty throws
    `ScriptException(InstanceV("RuntimeException"))` so user
    `case e: RuntimeException` matches (was InterpretError, opaque).
  - INT `.getMessage` aliased to the `.message` field for exception
    InstanceV, matching JVM behaviour.
  - `std/fs.ssc` with `writeFile / readFile / deleteFile / exists`
    externs (gated by `Feature.FileSystem`); all three backends wire
    blocking primitives via `java.nio.file` (INT, JVM) and Node `fs.*`
    (JS). `JsRuntimeDataset.fromFile` strips trailing empty after final
    `\n` to match Scala `getLines()`.
  - New expected files: `dataset-error.txt`, `dataset-parallel-int.txt`,
    `dataset-parallel-jvm.txt`, `dataset-from-file.txt`.
- conformance/run.sc gains `parseBackends` + per-backend SKIP gating so
  `backends: [jvm]` frontmatter actually limits which of int/js/jvm run
  a test (was previously ignored — every test ran on every backend,
  causing distributed-* tests to spuriously fail on INT).
- v1.21 follow-up part 2 (2026-05-19): Spark-style API expansion +
  JS exception support, four new conformance tests on top of the 10
  baseline (now 14 total dataset tests, all PASS on eligible backends):
  - JS Term.Try / Term.Throw as expressions (IIFE wrapping); plus
    _dispatch handling for `.getMessage` on JS Errors and Instance-style
    throwables. Was: try/catch in `val x = try …` emitted as
    `unsupported: Term.Try` and broke parsing.
  - Dataset.union / Dataset.intersect — set-style binary ops. Union
    concatenates (multiplicities preserved); intersect dedups and keeps
    left-side order.
  - Dataset.zip / Dataset.zipWithIndex — element-wise pairing into
    2-tuples, stops at shorter side.
  - Dataset.min / max / sum / avg — numeric/ordered terminal
    aggregations. JVM via Ordering/Numeric context bounds; INT via
    compareValues + infix; JS via raw Number ops.
  - Dataset.top(n) / takeOrdered(n) / countByValue — three more
    Spark-style terminals. top descending, takeOrdered ascending, both
    use natural ordering. countByValue: Map[T, Long] frequency
    histogram (word-count shortcut without writing the groupBy chain).
  - Dataset.partition(p) / mkString(...) / toMap() / toSet() /
    saveToFile(path) — five shape/conversion ops. partition splits to
    (List, List); mkString has three Scala overloads; toMap requires
    2-tuple elements; toSet dedups; saveToFile is the fromFile
    counterpart (one element per line via _show).
  - examples/dataset-stats.ssc — small weather-log analytics demo
    exercising the new API on all 3 backends.
  - docs/mapreduce.md §2 refreshed to list the v1.21 follow-up API.

  After this follow-up, the Dataset surface mirrors the core of
  Spark/Flink: 14 conformance tests (was 10) covering 30+ ops across
  all 3 backends. The local-parallel + distributed split documented
  in §3 of mapreduce.md still applies — runParallel uses Loom on JVM,
  cooperative on INT, sequential on JS.

Full design: [`docs/mapreduce.md`](docs/mapreduce.md) §3.

## v1.22 — Distributed map-reduce ✓ Landed (Phases 1–6)

Same `Dataset[T]` API, distributed execution backend.
Workers are actors on remote nodes (v1.6 Phase 3);
coordinator dispatches partitions, drives shuffle, handles
failure.  Named handlers (registered on every node)
instead of closure serialisation.

Full design in [`docs/mapreduce.md`](docs/mapreduce.md) §4.

What landed (Phases 1–6):

- **Phase 1 — `std/mapreduce/cluster.ssc`**: `Node`, `Cluster.connect/connectList`,
  `cluster.healthCheck(timeoutMs)`, `cluster.close()`.  Thin wrapper over
  v1.6 `connectNode`.  Explicit node list only; no auto-discovery.
- **Phase 2 — `std/mapreduce/handlers.ssc`**: `NamedHandler[A,B]`, process-global
  `HandlerRegistry` (register/lookup/apply/applyPredicate/applyKey/
  applyCombine/clear/registeredNames), `named("fn")` DSL helper producing
  `NamedRef`.  No closure serialisation per hard-no list.
- **Phase 3 — `std/mapreduce/distributed.ssc`**: `StageOp` ADT
  (MapOp/FilterOp/FlatMapOp), `Stage`, request-reply wire protocol
  (ProcessPartition/PartitionResult/PartitionFailed), `WorkerProtocol`
  actor loop, `runDistributed` coordinator (partitions data, spawns
  remote workers via `spawnOn`, collects results via `trapExit` +
  `link`), `DistributedError`, `DistributedResult[T]`.
- **Phase 4 — `std/mapreduce/shuffle.ssc`**: `GroupByOp`/`ReduceByKeyOp`
  stage ops, `ShufflePartial`/`ProcessKeyPartition`/`KeyResult` wire
  protocol, `ShuffleProtocol` worker loop (phase-A bucket building,
  phase-B reduce/group via HandlerRegistry), `runDistributedShuffle`
  coordinator (two-phase all-to-all via coordinator hub).
- **Phase 5 — `std/mapreduce/failure.ssc`**: `FailurePolicy` descriptor
  with named presets (failWhole/retryOnce/retryThrice/partial/retryPartial),
  `DistributedJobError`, `RetryState` per-partition bookkeeping,
  `withFailurePolicy` coordinator helper routing retry/partial/fail-whole.
- **Phase 6 — 6 conformance tests**: `cluster-connect.ssc`,
  `distributed-map.ssc`, `distributed-shuffle.ssc`,
  `distributed-failure-retry.ssc`, `distributed-failure-partial.ssc`,
  `distributed-heterogeneous.ssc` (2 JVM + 1 INT cross-backend).

Deferred to v1.22.x: closure serialisation, worker-to-worker direct shuffle.

```scala
val cluster = Cluster.connect(
  Node("worker-1@10.0.0.10:9100"),
  Node("worker-2@10.0.0.11:9100"),
  Node("worker-3@10.0.0.12:9100")
)

val result = Dataset.fromFile("/data/large.csv")
  .map(named("parseRow"))    // named handler — registered on each node
  .filter(named("isError"))
  .groupBy(named("byService"))
  .runDistributed(cluster, retries = 3)
  .collect()
```

### Prerequisite

**v1.6 Phase 3 — distributed actors via WS.**  Without it
there's no cross-node messaging.  Phase 3 of v1.6 is
already in flight per `MILESTONES.md`; v1.22 only starts
when Phase 3 is firm.

### Phase 1 — `Cluster` handle (~3 days)

`std/mapreduce/cluster.ssc`.  Thin wrapper over v1.6
`connectNode`.  `Cluster.connect(nodes...)`, `.close()`,
health-check probe.

### Phase 2 — Named-handler registry (~3 days)

`std/mapreduce/handlers.ssc`.  Each node registers
mappers / reducers by name at startup; messages refer to
handlers by name, never serialise closures.

### Phase 3 — Coordinator + worker actors (~5 days)

`std/mapreduce/distributed.ssc`.  Coordinator spawns
worker actors via v1.6 Phase 3 `connectNode` + `spawn`.
Worker processes partitions sequentially using v1.21
local-parallel for in-worker speedup.  Standard
request-reply over mailboxes.

### Phase 4 — Shuffle for `groupBy` / `reduceByKey` (~5 days)

Coordinator-mediated all-to-all in v1.22 (workers send
key-bucketed results back, coordinator redistributes by
key to second-stage workers).  Worker-to-worker direct
shuffle is v1.22.x optimisation.

### Phase 5 — Failure handling (~3 days)

Worker `Down(reason)` via v1.6 supervision links.
Default: fail-whole.  Opt-in: `retries = N` or
`allowPartial = true`.  Surface `DistributedError(node,
reason)` to caller.

### Phase 6 — Conformance + docs (~3 days)

6 conformance tests on a 3-node cluster (2 JVM + 1 INT
for cross-backend coverage).  Example applications:
word-count, log-aggregation, simple join.

### Hard-no list (locked — `docs/mapreduce.md` §8)

- Closure serialisation in v1.22 (defer to v1.22.x)
- Spark-like RDD lineage / recomputation
- Implicit cluster discovery (use `Cluster.connect(nodes...)`;
  auto-discovery belongs in `docs/cluster-management.md`)
- Cross-language workers (Python mapper on JVM
  coordinator)
- Persistent intermediate results

### Effort

Six phases, ~3 weeks end-to-end.  **Hard-blocked on v1.6
Phase 3.**

## Cluster management — fully landed in v1.23

Peer-cluster orchestration on top of v1.6 Phase 3 actors:
peer discovery, membership view, leader election (Bully + Raft +
external-coordinator hook), configuration distribution, cluster-wide
failure detection, rolling restarts, metrics aggregation — all
landed.

Full design space and explicit hard-no list in
[`docs/cluster-management.md`](docs/cluster-management.md); Raft +
external-coordinator algorithms and API in
[`docs/cluster-raft.md`](docs/cluster-raft.md).

### v1.23 — what shipped

| Piece | Status |
|---|---|
| Static seed-list discovery (`joinCluster`) | ✓ v1.6.x |
| Cluster-wide registry (`globalRegister` / `globalWhereis`) | ✓ v1.6.x |
| Membership view (`clusterMembers()`) | ✓ v1.23 |
| Membership events (`subscribeClusterEvents()` → `NodeJoined` / `NodeLeft`) | ✓ v1.23 |
| Per-link failure detection (40 s heartbeat) | ✓ v1.6 Phase 3 |
| Per-link Phi-accrual suspicion (`phiOf`, `isSuspect`) | ✓ v1.23 |
| Cluster-wide FD aggregation (`broadcastHealth`, `clusterIsDown`) | ✓ v1.23 |
| Local node identity + health snapshot (`selfNode`, `clusterHealth`) | ✓ v1.23 |
| Bully leader election (`electLeader`, `currentLeader`, `subscribeLeaderEvents`) | ✓ v1.23 (default) |
| Raft leader election (`useRaftLeaderElection`) | ✓ v1.23 (opt-in) |
| Raft on-disk persistence (`.ssc-raft-state-<key>.json`) of `(currentTerm, votedFor)` | ✓ v1.23 |
| External-coordinator hook (4-arg `useExternalCoordinator`) | ✓ v1.23 (opt-in) |
| Bounded `leaderHistory()` ring buffer | ✓ v1.23 |
| Auto re-elect on leader-link loss (`setAutoReelect`) | ✓ v1.23 |
| Auto-reconnect for outbound links (`setReconnectPolicy`) | ✓ v1.23 |
| Periodic gossip re-discovery (`requestGossip`) | ✓ v1.23 |
| Cluster config distribution (`clusterConfigSet/Get/Keys`, `ConfigChanged`) | ✓ v1.23 |
| Rolling-restart drain protocol (`setDraining`, `isDraining`, `drainingPeers`, `DrainStateChanged`) | ✓ v1.23 |
| Cluster metrics aggregation (`clusterMetricSet/Get/Sum/Names`, `MetricChanged`) | ✓ v1.23 |
| Config + drain + metric snapshots on peer handshake | ✓ v1.23 |
| `std.cluster.Cluster.*` namespace wrapper | ✓ v1.23 |
| Multi-node integration tests (2-, 5-, 3-node-with-leader-kill failover) | ✓ v1.23 |
| Concrete `Etcd.use` + `Consul.use` coordinator adapters | ✓ v1.23 |
| Operational status endpoint (`GET /_ssc-cluster/status` + `ssc cluster status` CLI) | ✓ v1.23 |
| Remote drain toggle (`POST /_ssc-cluster/drain` + `ssc cluster drain` CLI) | ✓ v1.23 |
| Events ring buffer (`GET /_ssc-cluster/events` + `ssc cluster events` CLI) | ✓ v1.23 |
| Cluster-wide singleton actor (`Singleton.use` / `Singleton.send`) | ✓ v1.23 (opt-in module) |
| Tunable per-link heartbeat (`setHeartbeatTimeout(intervalMs, deadAfterMs)`) | ✓ v1.23 |
| Bounded auto-reconnect (`setReconnectPolicy(initial, max, giveUpAfterMs)`) | ✓ v1.23 |
| Quorum-aware Bully self-claim (`setQuorumSize(n)`) — split-brain guard | ✓ v1.23 |
| TLS for peer endpoints (`serveAsync(port, tls(...))`) — wss:// out-of-the-box | ✓ v1.23 |

### Still deferred (promote on demand)

- ZooKeeper coordinator adapter (`ZkLeaderCoordinator`).  The
  client-side wire protocol is binary-only (no HTTP gateway), so the
  pure-`std.http` strategy used for `Etcd.use` / `Consul.use` doesn't
  apply — ships when a real consumer brings either a Java-client
  intrinsic or a ZK-over-HTTP shim.

Promote the remaining items when any of the trigger conditions fire:

1. A real .ssc application running on 5+ nodes asks
2. v1.22 distributed map-reduce gets 10+ user workloads
   that ask "how do I avoid maintaining the node list
   manually?"
3. A community-package author ships `scalascript-cluster`
   and demand suggests folding into std

Until then: stays deferred.  Each release revisits the
"promote?" question.

### What's explicitly out of bounds, ever

- Kubernetes / container orchestration (ScalaScript runs
  *on* K8s, not *as* K8s)
- Network policy / service mesh (CNI territory)
- Mandatory dependency on external coordinator (etcd /
  Consul / ZK opt-in adapters only)
- Cluster spanning browser-SPA backends (no inbound WS)

## v2.0 — Separate compilation of modules

**Status: working separate compilation landed 2026-05-19.**  All six
stages from the spec are implemented; the pipeline is exercised end-to-end
via CLI subprocess tests (`emit-interface → check-with-iface → emit-ir →
link → build --incremental`); the JVM backend produces per-module `.scjvm`
artifacts that the linker combines incrementally.  Tracking doc:
`docs/separate-compilation-plan.md`.

Test coverage: 522 core tests + 75 CLI subprocess smoke tests, all green.

Stage 5.4 / final round (landed 2026-05-19):
- `parseSType` and `SType.show` round-trip now handle union types
  `A | B`, intersection types `A & B` (with `&` binding tighter than `|`),
  and higher-kinded `F[_]` / `F[_, _]`.  Refinement types and match types
  still degrade to `SType.Any` (intentionally out of scope).
- `Typer` strict mode (`Typer(strict = true)`): when set, references to
  undefined `Term.Name` identifiers record a diagnostic on `Typer.errors`
  without crashing.  Scoped down conservatively: only flags bare
  camelCase / underscore-led identifiers; skips operators, method
  selectors, `Term.New`, lambdas, partial functions.
- `ssc check-with-iface` now uses strict mode + exits non-zero on
  diagnostics — actually catches undefined references in consumer code.
- JS backend incremental output: `.scjs` artifact + `ssc compile-js` +
  `ssc link --backend js [-o out.js]` + `ssc build --incremental --backend js`.
  Same shape as the JVM pipeline; longest-common-prefix dedup of the JS
  runtime preamble.

Stage 5.5 / robustness + ergonomics (landed 2026-05-19):
- **Auto-resolve**: `ssc compile-jvm/compile-js <target.ssc>` now walks
  the target's import closure, topo-sorts via Kahn, emits cycle traces
  on detection, and compiles each stale dep in order before the target.
  Default artifact dir: `<target-dir>/.ssc-artifacts/`; `--artifact-dir
  <dir>` override; `--no-auto-deps` for back-compat single-module behavior.
- **Linker dedup pass**: after the existing longest-common-prefix dedup
  of runtime preambles, both `mergeScalaSources` and `mergeJsSources` now
  drop duplicate top-level `def` / `val` / `class` / `object` declarations
  by name (first occurrence wins).  Defensive against conditional-runtime
  variation (module A uses effects, module B doesn't → different preambles
  → tail-dup of `_handle` etc.).  Strips modifier chains (`private`,
  `implicit`, `final`, `sealed`, `case`, `inline`, `lazy`, `async`),
  handles brace AND indentation-based bodies, string/comment-aware.
- **Strict-mode Select check**: extended `Typer` strict to also flag
  `Select(VarRef(importedModule), missingMember)` when the qualifier
  resolves to a known interface and the member is not exported.  Skips
  local-value receivers and dynamic-typed selectors; one diagnostic per
  miss (no double-report when qualifier itself is undefined).
- **`ssc info <artifact>`**: inspector for any `.scim` / `.scir` /
  `.scjvm` / `.scjs` envelope.  Plain-text mode dumps key=value lines
  with exports, hashes, byte counts; `--json` mode re-emits the
  canonical envelope through the same writer used to produce it.
  Failure modes (missing file, unknown extension, magic/ABI mismatch)
  exit non-zero with clear diagnostics.

Stage 5.6 / battle-test + JvmGen fixes (landed 2026-05-19):
- **Battle-test against real std/ modules**: 10 new tests against
  `std/eq.ssc`, `std/show.ssc`, `std/hash.ssc`, `std/order.ssc`,
  `std/dsl/*.ssc`, `std/parsing/*.ssc`.  ~50 % of std/ compiles and links
  end-to-end (the typeclass + ADT + dsl combinator idiom).  Surfaced
  4 concrete bugs documented in test TODO markers — see "Known gaps".
- **JvmGen effect-runtime emission fixes**:
  - Bare-name actor intrinsics (`subscribeClusterEvents()`, `clusterMembers()`)
    at `val rhs` now route through `emitExpr` and rewrite to `Actor.*`.
  - `blocksUseActors` now also fires on pattern-only modules — a module
    that only does `case NodeJoined(id) => ...` correctly pulls in the
    effects runtime and emits the matching case-class definitions.
  - Overloads of `serve` and `onWebSocket` collapsed into single defs with
    default arguments, so the v2.0 linker dedup pass doesn't drop them.
- **`ssc deps <file.ssc> [--graph]`**: prints the resolved import-closure
  in topo order (with optional `from → to` edges).  Useful for CI and
  for understanding what `compile-jvm`/`compile-js` will recursively
  compile before invocation.
- **Deep Select chains in strict mode**: `Typer` now recursively resolves
  `a.b.c` qualifier chains.  Single diagnostic at first break (no cascade).
  `ExportedSymbol.nested: List[ExportedSymbol]` field added to the IR
  (backward-compat default `Nil`).
- **Extractor populates `ExportedSymbol.nested`** (follow-up, 2026-05-19):
  `InterfaceExtractor` recursively walks `Defn.Object` bodies up to
  `MaxNestedDepth = 3` and emits `nested` entries for inner `def` / `val` /
  `class` / `object` / `trait` / `enum` members (names + FQNs exact, types
  best-effort `Any`).  Strict-mode deep-Select checks now reject unknown
  members through real `.scim` sub-namespaces instead of falling to
  permissive.  Package-shell walks also produce correctly-populated
  `nested` for sub-namespaces inside the shell.

Stage 5.7 / production blockers fixed (landed 2026-05-19):
- **Anonymous given witness identity**: `given Eq[Int] with { ... }` now
  synthesizes `witnessName = "given_Eq_Int"` and `fqn = "pkg_given_Eq_Int"`
  (or just `given_Eq_Int` when `pkg` is empty).  Pure structural identity
  — no hashes, deterministic across builds.  Type-arg type variables are
  dropped from the head name for cross-build stability (so `given Eq[List[A]]`
  → `given_Eq_List`).  Affects every typeclass instance in `std/`.
- **Structured parse diagnostics**: `Content.CodeBlock` carries an optional
  `parseError: Option[CodeBlockParseError]` with `(message, line, column,
  snippet)`.  All 8 CLI surfaces (`compile-jvm`/`-js`, `emit-interface`,
  `emit-ir`, `check-with-iface`, `build --incremental`, and auto-resolve
  dep helpers) print a 3-line snippet with `^` caret on parse failure
  instead of "Failed to parse scalascript code block".
- **YAML front-matter diagnostic**: wraps SnakeYAML's
  `ScannerException` to add the offending source line + `^` pointer + a
  targeted hint when the line contains `': '` (the most common cause:
  unquoted colons in string values).  Also quoted the `description:` in
  `std/parsing/recovery.ssc` so the parsing module set builds clean.
- **`ssc deps <file.ssc> [--graph]`**: prints the resolved import
  closure in topo order with `from → to` edges.

Phase 2 / bytecode linker (landed 2026-05-19):
- **MVP** (`--bytecode` opt-in): `ssc compile-jvm --bytecode` invokes
  `scala-cli` internally to produce real `.class` files; the artifact
  carries `classBundle` (base64-encoded ZIP of `.class` + `.tasty`).
  `ssc link --backend jvm --bytecode <dir> -o out.jar` extracts each
  bundle, dedups by FQN, packs into a single JAR.  Cross-module classpath
  wired by extracting deps' classBundles into a shared temp dir passed
  to scala-cli as `--jar`.  All scala-cli invocations use `--server=false`
  to avoid Bloop-daemon collisions under parallel test load.
- **Deep refactor** — runtime separated from user code:
  - `JvmGen.generateRuntime(capabilities)` emits the ~570KB runtime
    preamble wrapped in `object _ssc_runtime:` (top-level object — Scala 3
    forbids companion pairs at package scope).  Compiled once per
    artifact-dir into a `.scjvm-runtime` artifact (capabilities = union
    of all modules').
  - `JvmGen.generateUserOnly(module)` emits user code with
    `import _ssc_runtime.{given, *}` prepended.  No preamble.
  - `compile-jvm --bytecode` + `ensureRuntimeArtifact` automate runtime
    generation; `ssc compile-runtime --capabilities <list>` lets users
    drive it explicitly.
  - `linkJvmFromBytecode` packs the shared runtime classBundle once +
    each module's user classes.
  - 7 capabilities tracked: effects, mutual-tco, reactive, serve, mcp,
    dataset, json.
  - **Size impact**: per-module `.scjvm` shrinks from 515 KB to **10 KB**
    (51× reduction).  2-module out.jar: 554 KB → 301 KB (45% reduction).
    Per-additional-module cost: ~250 KB → ~3 KB of unique user classes.

Phase 2 follow-up (landed 2026-05-19, closes the last 3 known gaps):
- **Refinement + match types in `parseSType`**: `A { def foo: Int }`,
  `A { type T = Int }`, `T match { case Int => String; case _ => Any }`
  now parse to `SType.Refinement(base, members)` / `SType.Match(scrutinee,
  cases)` with full round-trip via `SType.show`.  11 new tests; all
  existing `SType` match sites stayed permissive via catch-all arms.
- **`build --incremental` unified diagnostic flow**: per-module work is
  wrapped in a stderr-redirect-to-buffer block; on failure the captured
  cause (YAML diagnostic, parse error position, etc.) is spliced onto
  stdout under the `... FAIL` line, indented 4 spaces.  Standalone
  `ssc compile-jvm` still emits to stderr.  CI scripts capturing only
  stdout now see both summary and cause.
- **JS-side runtime separation** (mirror of JVM Phase 2 deep refactor):
  `JsGen.generateRuntime(capabilities)` + `generateUserOnly(module)`
  emit module-only JS.  `.scjs-runtime` artifact format stores the
  shared runtime; modules ship only their unique JS.  Flat-scope concat
  (not IIFE) because runtime exports 200+ identifiers user code
  references unqualified.  5 capabilities: Core, Async, Effects, Mcp,
  Dataset.
  - **Size impact**: per-module `.scjs` shrinks from 80–150 KB to
    ~500 bytes (**200× reduction**); `_runtime.scjs-runtime` is 142 KB
    compiled once.  `out.js` stays at 139 KB (runtime needed at runtime).
    The win is incremental-rebuild bandwidth, not link output size.
  - **3 JS-specific edge cases** fixed: `EffectAnalysis` was seeding
    effectOps with builtin names (always non-empty), `_println` needs
    explicit flush at end of linked `out.js`, `--backend` is a global
    flag stripped by `GlobalFlags.parse` before command handlers see it.

**v2.0 separate compilation is feature-complete** for the planned scope.

Phase 3 / operational hardening (landed 2026-05-19):
- **TASTy direct scalac driver**: `cli/Scala3Driver` invokes
  `dotty.tools.dotc.Driver` in-process; warm per-module compile drops
  from 1410ms to **119ms (~11.8× speedup)**.  Custom `Reporter` formats
  diagnostics scala-cli-style.  `scala3-compiler` pinned to `3.8.3`.
  Fresh `Driver`/`Compiler` per invocation (no state leak).  scala-cli
  path kept as fallback via `SSC_EXTERNAL_SCALA_CLI=1` env var.
  `.scjvm` byte-identical between both paths.
- **ABI compatibility test suite**: 64 forensic tests covering 7
  artifact formats × 9 properties — round-trip stability, magic/version
  mismatch rejection, optional-field defaulting, unknown-extra
  tolerance, hash preservation.  Surfaced one sharp edge (Option[String]
  without explicit `= None` default isn't absent-tolerant — split tests
  into `optionalFields` vs `requiredOptionTypedFields`).
- **`docs/v2.0-artifact-format.md`**: 333 LoC wire-format spec.
  Compatibility policy in one phrase: "strict-equality on envelope,
  additive-friendly on payload."
- **`ssc verify <dir>`**: operational health-check command. Walks
  artifact dir, validates envelope + ABI + sourceHash shape + cross-refs
  + runtime coverage.  `--strict` adds source-freshness check;
  `--json` for machine-readable CI output.  Output uses `[OK]/[WARN]/[FAIL]`
  markers (safer than ✓/⚠/✗ glyphs in non-UTF8 terminals).

Test coverage after Phase 3: **522 core tests + 75 CLI subprocess
smoke tests**, all green.

Phase 3+ / final tooling round (landed 2026-05-19):
- **Source maps** (opt-in via `--source-map` on `ssc link`):
  - JVM (Option B): sibling `<moduleId>.ssc.scala` source file next to
    `out.jar`.  `.class` files' `SourceFile` attribute already names the
    Scala wrapper; IDEs source-attach via filename match.  No ASM dep.
  - JS: V3 source maps via hand-rolled VLQ writer (~180 LoC).  Appends
    `//# sourceMappingURL=out.js.map` to `out.js`.  Line-granularity
    mappings: runtime preamble unmapped, user-code lines map back to
    their `.ssc` source.  6 tests.
- **Per-section incremental** (opt-in via `build --incremental
  --section-cache`):
  - `sectionHashes: Map[String, String]` additive field on all 4
    artifact types.  Cumulative-hash chain (Option A): section N's
    hash includes its source + all prior sections' hashes joined.
  - `ModuleGraph.staleSections(srcPath, artifactDir)` returns the
    cumulative-stale list.  Edit last section → only it is stale; edit
    first → full cascade (preserves shared-scope safety).
  - `ssc info --sections` dumps the chain for any artifact.
  - 9 new tests.  Backward-compat: empty map default; ABI tests pass.
- **LSP server** (new `ssc lsp` command, 819 LoC server + 514 LoC tests):
  - Stdio JSON-RPC over hand-rolled Content-Length framing, no
    third-party LSP libs.
  - Methods: `initialize`, `initialized`, `shutdown`, `exit`,
    `textDocument/{didOpen,didChange,didClose,definition,hover,
    publishDiagnostics,completion}`.
  - Capabilities: full text-document sync, `definitionProvider`,
    `hoverProvider`, `completionProvider` (triggerCharacters: `.` ` `).
  - Loads `.scim` artifacts from `initializationOptions.artifactDir`
    (or workspace scan) for cross-module symbol resolution.
  - 25 LSP tests pass (16 protocol + 8 handlers + 1 integration
    spawning `ssc lsp` subprocess for full handshake).
  - **`textDocument/completion` landed (2026-05-19)**: prefix-filtered
    `CompletionList` combining user-defined symbols (from `TypedModule`),
    imported `.scim` interface symbols, and 27 built-in keywords.
    Item kinds: Function(3)/Constructor(4)/Variable(6)/Keyword(14).
    7 new handler tests; all 19 `LspHandlersTest` + 113 cli tests green.
  - **LSP Phase 2 landed (2026-05-20)** — five new methods + extended
    integration coverage:
    - `textDocument/references` — finds all use-sites of the name under
      cursor in the open document; returns `Location[]`.
    - `textDocument/prepareRename` — returns the `Range` of the
      renameable token, or `null` if the cursor is not on a name.
    - `textDocument/rename` — renames all occurrences in the open document;
      returns `WorkspaceEdit { changes: { uri: TextEdit[] } }`.
    - `textDocument/documentSymbol` — flat `SymbolInformation[]` with a
      Module heading + all scalameta `Defn.Def` / `Defn.Val` / `Defn.Var`
      / `Defn.Object` / `Defn.Class` / `Defn.Trait` names per block.
    - `workspace/symbol` — cross-document + cross-`.scim` substring search
      (case-insensitive, capped at 200); also searches pre-loaded interface
      symbols from the artifact dir.
    - `textDocument/signatureHelp` — backward character scan from cursor
      locates innermost unclosed `(`; comma-counts the active parameter;
      looks up the `def` via scalameta AST with a line-by-line text fallback
      when the block fails to parse (cursor inside incomplete call).
      Trigger chars: `(`, `,`.
    - `LspServerIntegrationTest` extended from 1 to 7 end-to-end tests
      covering all new methods; `withLspServer` / `initialize` / `didOpen`
      / `req` helpers eliminate per-test subprocess boilerplate.
    - 46 unit handler tests + 7 integration tests — all green.
  - **`std/actors.ssc` compile-jvm flip (2026-05-20)** — `V2RealStdModulesTest`
    expectation updated from "must fail" to "must succeed (exit 0, produces
    `.scjvm`)" after the codegen caught up with every `extern def` in
    `std/actors.ssc`.
  - **SectionDiff / AST-cache diff (2026-05-19)** — `SectionDiff` computes
    structural diffs between two parsed `Module`s at the section level; the
    incremental JVM/JS build pipelines consult it to skip re-emitting
    sections whose content hash and AST are unchanged.

**Final test coverage**: **531 core + ~115 CLI subprocess smoke tests**,
all green.

**v2.0 separate compilation is now ALL-DELIVERABLES-LANDED.**  The
documented "remaining post-Phase-3 directions" are now done.

Phase 4 / honesty-pass follow-ups (landed 2026-05-19):

The "ALL-DELIVERABLES-LANDED" line above hid a few sharp edges that
the implementing agents flagged in code comments as `TODO`s.  An
honesty-pass round addressed the most-impactful ones (all five
landed, 50+ new tests, 546 core + ~75 CLI subprocess green):

1. **LSP positional accuracy** — `ExportedSymbol` gets `definitionLine`
   + `definitionColumn` fields populated by `InterfaceExtractor` from
   scalameta positions; `Content.CodeBlock` gets `lineOffset` populated
   by `Parser` from CommonMark line numbers.  LSP cross-module
   go-to-definition stops returning `(0,0)`; multi-block hover/definition
   no longer reports block-local lines as if blocks start at 1.

2. **JVM source-maps Option A** — Phase 3+ landed Option B (sidecar
   `.ssc.scala` next to JAR; IDE source-attach via filename).  Option A
   injects JSR-45 SMAP into `SourceDebugExtension` attribute of each
   `.class` via ASM, so `java -jar out.jar` stack traces resolve to
   `.ssc` line numbers, not synthetic Scala lines.  Adds `lineMap`
   field to `ModuleJvmArtifact` (string-keyed for upickle reliability)
   and new `JvmSmap` + `JvmSmapInjector` modules.

3. **3 pre-existing `JvmBytecodeLink` failures** — `Main method not
   found in class a_sc` when `java -cp out.jar a_sc` runs.  Multiple
   agents constatated "not our regressions" without diagnosing.  The
   final-polish round looks at scala-cli's script-mode `<Name>$package`
   companion-vs-direct main-emit convention.

4. **`ssc clean <dir>`** — garbage-collect artifacts for sources that
   no longer exist.  `--dry-run`, `--all` flags.  Closes the "no GC"
   UX gap.

5. **Reproducibility tests** — pin byte-identical output across two
   `compile-jvm` invocations.  ZIP entries' timestamps fixed to epoch,
   sorted alphabetically.  Any non-deterministic source surfaced gets
   fixed in lockstep.

After Phase 4, the documented gaps are:

- **Per-section Option B (interface-based)** — current cumulative-hash
  chain cascades on first-section edit; an interface-aware variant
  would only re-emit sections whose public API changed.  Deferred —
  needs per-section interface extraction infrastructure.

- **Scale benchmark** — perf measured on trivial 2-module fixture.
  A real benchmark over 30+ `std/` modules at full `--bytecode
  --section-cache --source-map --strict` toggles is owed.

- **Cross-platform smoke** — all tests assume Unix paths.  Windows
  path separators, CRLF line endings (for `sourceHash`), file-locks
  not covered.

- **External `.sscpkg` artifact-level distribution** ✓ Landed (v2.0
  Phase 5).  `ssc bundle --with-artifacts` runs `build --incremental
  --backend jvm` + `--backend js` on the inputs, then bundles the
  produced `.scim` / `.scjvm` / `.scjs` files under a `.ssc-artifacts/`
  prefix inside the `.sscpkg`.  Consumer-side: `compile-jvm` and
  `compile-js` resolve each `dep:` import via `ImportResolver`, then
  `findArtifactAlongside(sscPath, ext)` discovers `<dir>/.ssc-artifacts/
  <basename>.<ext>` (auto-detect, no manifest schema change) and stages
  the artifact into the local artifact dir so the typer + linker pick
  it up directly.  Source-fallback when no artifacts ship; bad-magic
  artifacts surface a clear error.  5 new CLI tests in
  `SscpkgArtifactDistributionTest`.

- **Getting-started tutorial** — `docs/v2.0-artifact-format.md` is the
  wire spec; a user-facing "compile your first project with v2.0"
  doc is owed.

What landed:
- `ir/Ir.scala`: `ArtifactVersion` (magic `SSCART` + ABI `2.0`),
  `ModuleInterface`, `ExportedSymbol`, `InstanceDecl`, `CapabilityDecl`,
  `ModuleIrArtifact` — all `derives ReadWriter`, JSON round-trip from day one.
- `core/artifact/InterfaceExtractor.scala`: extracts `ModuleInterface` from
  a parsed AST module; SHA-256 source hash in every artifact.
- `core/artifact/ArtifactIO.scala`: `.scim` / `.scir` read/write with ABI
  version guard on every read.
- `core/artifact/InterfaceScope.scala`: populates a `Typer.Scope` from a
  pre-compiled interface; used by `ssc check-with-iface`.
- `core/artifact/Linker.scala`: merges compiled modules in dep order; FQN
  mangling and cross-module collision detection.
- `core/artifact/ModuleGraph.scala`: Kahn's topo-sort of `.ssc` files;
  `isStale` compares SHA-256 source hash vs artifact.
- `core/typer/Typer.scala`: `Typer(importedInterfaces)` constructor + 
  `Typer.typeCheckWithInterfaces` factory — backward-compatible.
- `cli/Main.scala`: six new commands — `emit-interface`, `emit-ir`,
  `check-with-iface`, `link`, and `build --incremental`.

Post-MVP additions (also landed 2026-05-19):
- `InterfaceExtractor` respects `exports:` front-matter — only declared
  names appear in `.scim`; private helpers stay invisible to consumers.
- `InterfaceExtractor` walks package-wrapped nested objects — `Parser.wrapSectionInPackage`
  rewrites blocks as `object foo: object bar: <body>`; extractor now
  recurses into nested `Defn.Object` so packaged modules (e.g. `std/dsl/pretty.ssc`)
  expose their inner types in `.scim`.
- `Linker` FQN-rewrite end-to-end tests (`LinkerRewriteTest`, 7 cases):
  top-level `VarRef` rewrite, lambda shadowing, multi-import multi-call-site,
  cross-module collision detection — exercised against real IR from
  `Normalize` + `AstToIr` rather than hand-rolled IR.
- CLI subprocess smoke tests (`V2ArtifactCliTest`, ~370 LOC): end-to-end
  `emit-interface`, `emit-ir`, `check-with-iface`, `link`, and
  `build --incremental` exercised at the `ssc` process boundary.
- `InterfaceExtractor` AST-based capability + extern detection:
  replaces text-scanning heuristics with proper scalameta AST traversal.
- `InterfaceScope.parseSType`: real Scala-style type parser instead of
  string splitting (handles generic, union, and intersection types).
- `Normalize` emits `IrExpr` bodies to unblock Linker rewrites.
- `wrapSectionInPackage` now applies `preprocessExtern` / `preprocessEffects`
  before wrapping — fixes silent parse failures for `std/*` files with
  both `package:` frontmatter and `extern def` surface forms.

Stage 5.3 / typer / JVM-incremental (landed 2026-05-19):
- `Linker.rewriteExpr` folds `Select` chains: `Select(VarRef("a"), "bar")`
  where module A (pkg=`["a"]`) exports `bar` collapses to `VarRef("a_bar")`.
  Handles multi-segment packages too (`std.dsl.foo` → `VarRef("std_dsl_foo")`).
- `ssc link -o foo.scir` now writes a deterministic composite SHA-256
  (joined input hashes) instead of the literal string `"linked"`.
- `Typer` real type inference for top-level signatures (`Defn.Def`,
  `Defn.Val`, `Defn.Class`): declared return types parse via `parseSType`;
  inferred return types use simple bidirectional propagation (literals,
  arithmetic on Int, block last-stat, converging if/else).  Complex
  bodies still fall back to `SType.Any`.  Closes the "everything is `Any`"
  gap that made `.scim` interfaces near-useless.
- `ssc compile-jvm <file.ssc> [-o out.scjvm] [--iface-dir <dir>]` — single-
  module JVM compile, emits a `.scjvm` artifact (SSCART-framed JSON wrapping
  per-module emitted Scala 3 source + SHA-256 + import hints).
- `ssc link --backend jvm <dir> [-o out.jar | out.scala]` — combines
  per-module `.scjvm` artifacts via textual concat + runtime-prefix dedup.
  MVP limitation: each `.scjvm` carries the full JvmGen runtime preamble;
  the linker finds the longest common whole-line prefix and emits it once.
  Real bytecode-level mangling is Phase 2.
- `ssc build --incremental --backend jvm <dir>` — emits `.scim` + `.scir`
  + `.scjvm` per stale module; SHA-256 staleness check makes the build
  truly incremental (untouched modules skip codegen).

Auto-resolve for per-module compile (landed 2026-05-19):
- `ssc compile-jvm <file.ssc>` and `ssc compile-js <file.ssc>` now walk
  the target's `Content.Import` closure, topo-sort the local-path
  dependency DAG, and recursively compile every stale dep into a
  shared artifact dir before compiling the target.  Dep artifacts
  default to `<target-dir>/.ssc-artifacts/` and are reused on
  subsequent runs (SHA-256 freshness check on both `.scim` and the
  backend-cache file).  No more "compile a first, then b" topo dance.
- New flags: `--artifact-dir <dir>` overrides where dep artifacts
  land; `--no-auto-deps` reverts to the old per-module behaviour
  (still relies on `--iface-dir` for cross-module type-checking).
- Cycles are detected before any codegen runs; the command exits
  non-zero with a `→`-joined cycle message naming the modules.
- New helper: `cli/AutoResolve.scala` (~190 LOC).  Reuses
  `InterfaceExtractor.sha256` + `ArtifactIO` / `JvmArtifactIO` /
  `JsArtifactIO` for freshness checks; does its own DFS-then-Kahn
  pass rather than reusing `ModuleGraph.build` so traversal starts
  at a single file rather than a whole directory.
- Tests: `AutoResolveCliTest` (9 cases) — two-module, three-module,
  idempotency, cycle, `--no-auto-deps`, JS-side parity, and
  `--artifact-dir` override.

Default `ssc compile` / `ssc build` / `ssc run` are completely unchanged.
The new commands are additive; the ABI commitment is in place from day one.

Today every `ssc compile` parses, types, normalises, and emits the
entire reachable module-tree in a single pass.  Separate compilation
means each module compiles independently into an IR artifact +
interface; consumer modules link against pre-compiled artifacts
instead of re-parsing every source.

Analogues: Haskell `.hi` + `.o`, OCaml `.cmi` + `.cmo`, Scala
`.tasty` + `.class`, Rust crates.

### What it unlocks

- **Incremental build speed.**  Stdlib + third-party packages don't
  re-parse on every `ssc compile`.  Important for large projects
  and IDE/language-server analysis loops.
- **Distributable libraries.**  Someone ships a `.sscpkg` with
  pre-compiled IR; consumer compiles only their own code against
  the package's interface.  Without source disclosure required.
- **Compilation caching across CI runs** — keyed on
  module-content hash, not whole-tree hash.
- **IDE support.**  Language server analyses one module at a time;
  cross-module references resolve through interface lookups,
  not full re-parse.

### What's needed

1. **Module boundary definition.**  What is a module?  A directory
   with a `package.yaml` declaring exports?  A single `.ssc` file?
   Decision needed before anything else.
2. **Interface artifact** (`.scim` or similar) — exported types,
   `extern def` signatures, typeclass instances, capability
   declarations.  No bodies.  Stable across builds within the
   same compiler version.
3. **IR artifact** — body IR in JSON / msgpack, the v0.1 SPI's
   `NormalizedModule` serialisation generalised per module.
4. **Type-checker that consumes interfaces without bodies.**  Today
   the typer sees the full module-tree; separate compilation
   requires it to type-check against a foreign module's interface
   alone.
5. **Linker pass.**  Collect compiled artifacts, resolve symbol
   references across modules, hand a fully-linked
   `NormalizedModule` to `Backend.compile`.
6. **Stable IR ABI.**  Decade-long commitment per Haskell `.hi`
   versioning practice — adds a `.scim` magic number / version
   guard so incompatible artifacts are detected, not silently
   miscompiled.
7. **Symbol mangling.**  Cross-module-name collisions resolved
   through fully-qualified mangled names; consumer-side imports
   rewrite to mangled forms before linking.
8. **Build orchestration.**  Dependency graph between modules;
   parallel + incremental rebuild logic; `Makefile`-style
   timestamp tracking or content-hash based.

### Cost

Realistically **2-3 months of focused work** by one person.  Every
piece of the pipeline is touched — parser carries through to
linker.  The hidden cost is the **stable IR ABI commitment** —
once shipped, the ABI freezes design space for the IR
representation.

### Prerequisites

  - Stage 5+/B + 5+/D (intrinsic-module extraction) **landed** —
    otherwise the "modules" concept is fragmented between SourceLanguage
    plugins, Backend plugins, and embedded platform code.
  - SPI v0.1 IR serialisation **shipped** — already done (Stage 2).
  - Real motivating use case — at least one third-party package
    that benefits from being distributed pre-compiled.

### Why deferred

Single-program compilation is the dominant scenario today; the
existing whole-tree compile is fast enough for the size of programs
we see.  Separate compilation pays off when:

  - A package ecosystem emerges (multiple third-party `.sscpkg`s
    in active use).
  - Build times exceed comfort (>30s incremental).
  - IDE / language-server demand emerges.

None of these are true in 2026.  Tracked here so the conversation
isn't restarted from scratch when one becomes true; the design
direction is clear, the cost is honest, the prerequisites are
listed.

### Considered alternatives — rejected

- **Bytecode-level caching.**  Cache `Backend.compile` output keyed
  on source hash.  Cheaper to implement but doesn't unlock
  distribution-without-source.  Could land as a smaller win on
  the way to full separate compilation.  Promote if real users
  ask.
- **Whole-program incremental** (Salsa-style demand-driven
  recomputation).  Faster builds without ABI commitment.
  Heavier implementation cost (architectural rewrite of the
  compilation driver); same outcome.  Defer pending Bazel /
  cargo-style use case.

## Interpreter ergonomics — carried over from v1.1

Three friction points surfaced while building the v1.1 typeclass
library.  Each was worked around at the call site (helpers instead
of extensions, typed-`val` instead of ascription) to keep the
milestone narrow, but each leaves an unergonomic seam users will
hit again.  Roughly a session each; pick up when the next milestone
that uses them lands.

1. **Sealed-trait extension dispatch in the interpreter.** ✓ Landed
   with v1.13.  `sealedParents` registry walks the parent chain so
   `Right(…)` reaches extensions on `Either`.  `std/bifunctor.ssc`
   and `std/monaderror.ssc` now use full extension dispatch.

2. **`using`-clause auto-resolution.** ✓ Landed with v1.13.
   Context-bound desugaring, `summon`, and `using` auto-resolution
   all wired in the interpreter.  `combineAll[A: Monoid]` resolves
   `given` instances from scope without explicit passing.

3. **`Term.Ascribe`.** ✓ Landed 2026-05-19.  Added
   `case t: Term.Ascribe => eval(t.expr, env)` to the interpreter's
   `eval` dispatcher and `case Term.Ascribe(inner, _) => genExpr(inner)`
   to JsGen (closing the latent footgun there too).  New conformance
   file `conformance/type-ascription.ssc` locks in three-backend parity.

## Known issues / latent flakes

Things noticed in passing while landing other work — not blocking, but
worth a separate fix when somebody has cycles.

- **v1.22 distributed-* conformance tests fail on JVM** — root cause:
  even with explicit `[name](./path.ssc)` markdown-link imports,
  `JvmGen.inlineImport` wraps each dep in its `package:` object but
  doesn't route the dep's bare-name calls (`self()`, `connectNode`,
  `receiveWithTimeout`, `pid ! msg`, …) through the same
  bare-name → qualified-name rewriting pipeline that `genModule`
  applies to user code blocks. So a dep's `def healthCheck(...) =
  pid ! ("__healthCheck__", self())` stays as-is when emitted inside
  `object std { object mapreduce { … } }` and the JVM compiler
  errors with `Not found: self`, `value ! is not a member of Any`,
  etc. The six v1.22 phase-6 tests (`distributed-{map,shuffle,
  failure-retry,failure-partial,heterogeneous}` plus
  `cluster-connect`) all hit this. The MILESTONES "PASS [JVM]" claim
  from v1.22 Phase 6 was likely never verified end-to-end.

  Partial mitigations landed 2026-05-19:
  - `pending:` frontmatter marker — the six tests document the
    intended v1.22 API but report `PENDING` instead of `FAIL`. See
    `conformance/run.sc` `parsePending`.
  - `cli/compile` now calls `AutoResolve.resolve` before compiling so
    import cycles surface with `compile: N cycle(s) detected:` rather
    than scala-cli's confusing duplicate-definition spam.
  - `JvmGen.mergeDuplicatePackageObjects` — brace-balanced string
    scanner that merges multiple top-level `object pkg { object sub
    { … } }` declarations into one. Triggered when several inlined
    imports share a `package:` prefix (e.g. several files from
    `std/mapreduce/*`). Eliminates the "duplicate top-level object
    std" Scala 3 error class. scala.meta-level merging was tried
    first but parse fails on the 300 KB+ emitted preamble; the
    string scanner respects double-quoted strings and `//` comments
    so brace counting stays correct.

  Real fix still needed: either (a) `inlineImport` routes dep blocks
  through the rewriting path `genModule` uses for user code, or
  (b) std/mapreduce/* gets lowered to a `JvmRuntimeMapReduce`
  preamble injected when the IR references those types (analogous to
  `JvmRuntimeDataset`). See `docs/modularity.md` §12 for design
  discussion.

  Attempted (a) end-to-end 2026-05-19, **reverted**: the structural
  recursion (`statsUseEffects` → `Defn.Object/Class/Trait` body walk,
  matching `emitObjectLike` / `emitClassLike` /
  `emitDefWithRewrittenBody` arms in `emitStat`) is mechanically
  straightforward but the rewriting predicate `termNeedsCustomEmit`
  is too broad in dep contexts. Once dep-class method bodies start
  flowing through `emitExpr`, `actors-process-info.ssc` regresses:
  `info.links` inside a `case Some(info) => info.links.length` becomes
  `_bind(info.links, …)` because the CPS-wrap path doesn't see static
  `info: Some` typing and wraps everything as `Any`. A real fix
  needs either a tighter dep-mode predicate (only rewrite the
  `actorBareNames` / intrinsic shapes, not the whole effectful
  surface) or a separate dep-mode emit path. Pending careful design.

  Three-step narrower attempt landed 2026-05-19 — **doesn't fully
  unblock the tests, but the unblocked layers are reusable**:
  1. `JvmGen.qualifyBareActorCallsInSource` — string-level rewriter
     applied per-dep-block by `inlineImport` before the dep enters
     the main emit pipeline. Rewrites `self()`, `connectNode(addr)`,
     `link(pid)`, `register(name, pid)`, `trapExit(b)`, etc. to
     `Actor.<n>(…)`; rewrites `pid ! msg` to `Actor.send(pid, msg)`.
     Word-boundary anchored so `Actor.self(`, `someObj.connectNode(`,
     and substrings inside identifiers/strings are untouched.
     Skips `receive`, `runActors`, `spawn` — they need richer
     emit-time handling.
  2. `receiveWithTimeout(t) { case … }` emit alias — added to the
     existing `receive(t) { case … }` Apply-Apply pattern in JvmGen
     so cluster.ssc's `receiveWithTimeout(timeoutMs) { … }` resolves
     when reached through emitExpr.
  3. `mergeDuplicatePackageObjects` recursion — was top-level only;
     now walks each merged outer object's body and re-applies the
     merger with strip/re-indent so nested
     `object std { object mapreduce { … } object mapreduce { … } }`
     fully collapses. Group key is `(indent, name)` so siblings at
     different depths don't accidentally merge.

  Further attempts 2026-05-19 (compile-clean, but runtime still blocked):
  - `rewriteActorAstCallsInSource` — scalameta-based AST walk inside
    dep blocks for shapes the string-regex can't handle: `pid ! msg`
    (`Term.ApplyInfix.After_4_6_0`), `receive { case … }` and
    `receiveWithTimeout(t) { case … }`. Splices computed
    right-to-left from `pos.start/end` so offsets stay valid. Source
    parses tried first, then Term-fallback so block-shaped case
    bodies also walk. Tuple-arg `pid ! (a, b)` is reconstructed
    syntactically because Scala parses it as 2-arg infix, not as a
    tuple send.
  - `emitReceiveMatcherOpt(cases, cpsBody)` — the dep path passes
    `cpsBody = false` and recursively rewrites each case body via
    `qualifyBareActorCallsInSource`, otherwise the outer receive's
    splice clobbers inner `Actor.send(…)` rewrites.
  - `emitCpsBindWithType` — widens typed lambda to `Any => Any` so
    `_bind(rhs, (x: T) => …)` accepts user-side typed vals. Without
    this the user-code workaround `val result: DistributedResult[…]
    = runDistributed(…)` won't compile.

  With those three plus the earlier layers, `distributed-map.ssc`
  compiles cleanly (was 27 errors → 0). But it now fails at runtime
  with `ClassCastException: _Perform cannot be cast to String` at
  `val jobId: String = Random.uuid().asInstanceOf[String]` in the
  dep. Root cause is **architectural**: dep code is emitted as
  regular Scala, but all actor/effect primitives (`Random.uuid()`,
  `Actor.self()`, `Actor.link()`, `pid ! msg`, `receive { … }`)
  produce `_Perform` Free-monad nodes that only mean anything inside
  a CPS chain. User code goes through the CPS rewriter that wraps
  these in `_bind`; dep code does not. So the dep gets `_Perform`
  values back and uses them as raw values → CCE the first time one
  is read, dropped silently the rest of the time.

  Fixing this properly requires CPS-rewriting dep function bodies
  that call effect primitives — essentially porting the user-code
  rewriter onto dep blocks. Earlier attempt at that (see "reverted"
  paragraph above) was too broad and regressed `info.links`. A
  tighter dep-mode predicate is needed (only wrap calls whose
  callee is in the effect-primitive set), plus deciding how dep
  functions advertise their async-ness to callers (return type
  becomes `_Perform`, callers' user-code `_bind` already handles
  the wait). Estimated 2–3 days of careful work — not a one-pass
  fix. Track in v2.x or a dedicated `feature/dep-cps` branch.

  **Detailed design spec**: see `docs/dep-cps-rewrite.md` for the
  full architectural analysis, design space (4 strategies),
  recommended 7-step implementation plan, and open questions.
  Pick that up before starting the rewrite — captures everything
  needed to resume cold.

- **WS test cross-suite isolation goes through a process-global
  `WsRoutes` table + `WsTestLock` monitor.**  Works, but the lock
  serialises ScalaTest's default parallel suite execution for every
  `Ws*E2ETest`.  Cleaner fix would be a per-Interpreter routes
  registry — `WsRoutes` becomes `class WsRoutes` owned by the
  `Interpreter` instance, `WsProxy` consults the interpreter passed
  in.  Half-day refactor.  Worth it if a third WS-touching suite
  lands.

- ~~**Scala compiler warnings in our own code.**~~  ✓ Landed (2026-05-19).
  All 13 warnings fixed across 8 files (5 scalameta `After_X_Y_Z` migrations
  in `AstToIr`, 1 match-exhaustiveness fix in `RequestBuilder`, removed
  unused symbols in `Linker`/`Interpreter`/`WebServer`/2 plugin tests,
  removed unused default param in `evalDirectBlock`).  `Compile / scalacOptions`
  bumped to `-Werror` (the non-deprecated alias of `-Xfatal-warnings`);
  `Test / scalacOptions` kept at warning-tolerant for scalatest macros /
  intentional mocks.  Future warnings now fail the build before they
  accumulate.

- **`SupervisorTest` — 4 pre-existing failures.**  `OneForOne` permanent /
  transient / temporary restart specs and the `MaxRestarts` budget spec
  all fail on clean `main` (independent of the warnings cleanup).  Not
  blocking landed work — worth a dedicated fix when somebody has cycles.

## CLI — native binary (GraalVM native-image) — BLOCKED (not doing this)

Produce a self-contained `ssc` native executable via GraalVM native-image:
no JVM installation required, cold-start drops from ~1-2 s → ~50-100 ms.
Current baseline: `ssc.jar` 29.4 MiB → ProGuard-shrunk `ssc-min.jar` 26.4 MiB
(task: `sbt cli/shrinkJar`).  Native-image would produce a 60-100 MiB binary
(embeds GC + thread runtime) but removes the JVM dependency entirely.

### Effort estimate: ~2 weeks

**Why it's non-trivial:**

1. **Reflection config** (~1 week, the hard part).
   Three libraries each need hand-tuned `reflect-config.json`:
   - **scala-meta** — parser uses reflection internally for AST node
     construction; agent-generated config is usually incomplete.
   - **upickle** — macro-derived JSON codecs work at compile time but
     the resulting dispatch uses reflective-style case matching that
     native-image needs hints for.
   - **snakeyaml** — notorious for native-image; uses reflection for
     Java bean deserialization.  Might need a switch to a native-image-
     friendly YAML library (e.g. eo-yaml or a pure-Scala parser).
   Workflow: run `native-image-agent -agentlib:...` while exercising
   all CLI paths (run / compile / watch / serve examples), curate the
   generated `reflect-config.json` + `resource-config.json`.

2. **ServiceLoader** (~1 day).
   `BackendRegistry` discovers backends via `ServiceLoader`; native-image
   needs explicit `resource-config.json` entries listing every
   `META-INF/services/scalascript.backend.spi.Backend` file.  Bundles
   the four standard backends at build time; third-party in-process
   plugins cannot be added later (see below).

3. **Multi-platform CI** (~3 days).
   GraalVM native-image produces a platform-specific binary.  Need
   separate GitHub Actions jobs for macOS ARM64, macOS x86_64, Linux
   x86_64 (Linux ARM64 and Windows are optional stretch goals).  Each
   job must install GraalVM (not standard OpenJDK); build takes 5-15 min.

### Known downsides / tradeoffs

| Issue | Severity | Notes |
|---|---|---|
| `--plugin <jar>` broken | **High** | `URLClassLoader` cannot dynamically load class files at runtime in native-image. In-process JAR plugins would need to be disabled in native mode. Subprocess plugins (stdio-json wire protocol) still work fine — they're a separate process. |
| Binary size larger than JAR | Medium | 60-100 MiB self-contained vs 26 MiB ProGuard JAR + JVM. Trade-off: no JVM dependency. |
| Reflection config drift | Medium | A new library or reflection path added later may work in JVM mode and silently crash in native mode. Requires running the agent again on any significant dependency change. |
| CI build time | Low | 5-15 min per platform per commit; mitigated by only running native builds on release tags, not every push. |
| GraalVM in CI | Low | Adds a dependency not in the standard JDK ecosystem; GitHub Actions has `setup-java` with `distribution: graalvm` support. |
| Debug quality | Low | Stack traces and heap dumps are less ergonomic in native mode; crashes can be harder to diagnose. |

### Recommendation

- **Ship it as an opt-in release artifact**, not the primary distribution.
  Keep `ssc.jar` as the default (`java -jar ssc.jar`); native binary is
  a convenience for users who want instant startup without a JVM.
- **Disable `--plugin <jar>` in native mode** (print a clear error
  pointing to subprocess plugins).  Don't try to redesign the plugin
  loader — subprocess plugins are the robust path anyway.
- **Sequence after v1.7** (plugin discovery) so the native build can
  bake the stable ServiceLoader shape.  Starting before v1.7 means
  the reflection config will need re-generation once the plugin
  discovery layer changes.
- **snakeyaml risk**: if agent-generated config doesn't cover all YAML
  paths, consider replacing snakeyaml with a pure-Scala YAML parser
  (smaller attack surface, no reflection).

### Implementation sketch

```
build.sbt additions:
  cli.enablePlugins(GraalVMNativeImagePlugin)   // via sbt-native-image
  GraalVMNativeImage / mainClass := Some("scalascript.cli.ssc")
  graalVMNativeImageOptions ++= Seq(
    "--no-fallback",
    "--initialize-at-build-time=scala",
    "-H:ReflectionConfigurationFiles=native-image-configs/reflect-config.json",
    "-H:ResourceConfigurationFiles=native-image-configs/resource-config.json",
    ...
  )

Release workflow (GitHub Actions):
  strategy.matrix:
    os: [ubuntu-latest, macos-latest, macos-13]  // arm + x86
  steps:
    - uses: graalvm/setup-graalvm@v1
      with: { java-version: '21', distribution: graalvm }
    - run: sbt cli/graalvm-native-image:packageBin
    - uses: actions/upload-artifact@v4
      with: { name: ssc-${{ matrix.os }}, path: cli/target/... }
```

---

## Optimization and modularity roadmap

Full planning document: [`docs/optimization-roadmap.md`](docs/optimization-roadmap.md).
Items below are the actionable milestones extracted from that document.

### Runtime — Project Loom (virtual threads)

**Status: landed. Effort: ~2 hours. Priority: 1.**

Switch the HTTP/WS server executor to `Executors.newVirtualThreadPerTaskExecutor()`
(Java 21 LTS, stable).  Removes the one-thread-per-connection bottleneck without
a full NIO migration.  Affects `runtime-server-common` + `runtimeServerJvm`.

- [x] Replace executor in `runtime-server-common` and `runtimeServerJvm`
  - `TlsContextBuilder.vthreadPool()`: dropped reflective fallback, now calls
    `Executors.newVirtualThreadPerTaskExecutor()` directly (Java 21 confirmed).
  - `WebSocketRuntime.scala` writer thread: replaced reflective `Thread$Builder$OfVirtual`
    with direct `Thread.ofVirtual().name("ws-writer").start(...)`.
  - `Interpreter.scala` `asyncParInterp`: replaced `newCachedThreadPool()` with
    `newVirtualThreadPerTaskExecutor()` for lightweight parallel async.
  - `JvmGen.scala` generated `_runAsyncParallel`: same replacement in emitted code.
- [x] Note Java 21 requirement in docs
  - One-line comment added next to each change site.
- [ ] Smoke test: 10 000 concurrent WS connections without OOM
  - Deferred: requires a dedicated load-test harness; core change is in place.

### Tooling — `ssc check` standalone type-checker

**Status: landed. Effort: ~1 day. Priority: 2.**

`ssc check src/**/*.ssc` — run the typer without interpreting, exit non-zero on
diagnostics.  For CI.  Generalises existing `check-with-iface` to standalone.
Supports `--iface-dir <dir>` / `-I <dir>` for checking against pre-compiled interfaces.

- [x] Add `check` command to CLI dispatch
- [x] Print diagnostics to stderr in `file:line:col: message` format
- [x] Exit non-zero when any error found
- [x] Integration tests in `CheckCommandTest` (7 tests, all green)

### Runtime — Interpreter split (lazy capability loading)

**Status: open. Effort: ~1 week. Priority: 3.**

`Interpreter.scala` is 7 567 lines containing core eval + HTTP + Actors + MCP +
Dataset + Signals + Coroutines — all loaded on every `ssc run hello.ssc`.

Split into per-capability files, each installing via a registration hook in
`CoreInterpreter`.  Cold-start for pure scripts drops significantly.

| File | Content |
|------|---------|
| `CoreInterpreter.scala` | `eval`, `dispatch`, `callValue`, base intrinsics |
| `HttpRuntime.scala` | `serve`, `route`, `onWebSocket`, `sse`, TLS |
| `ActorRuntime.scala` | `spawn`, `receive`, cluster, Phi-accrual FD |
| `McpRuntime.scala` | `mcpServer`, `mcpConnect` |
| `DatasetRuntime.scala` | `Dataset[T]`, MapReduce |
| `SignalRuntime.scala` | `Signal`, `computed`, `effect` |
| `CoroutineRuntime.scala` | `coroutineCreate`, `coroutineResume`, generators |

- [ ] Extract each runtime file with registration hook
- [ ] Wire lazy loading in `CoreInterpreter`
- [ ] All existing tests green after split

### Compiler — AST cache between watch cycles

**Status: section-diff landed (2026-05-19). Effort: ~3 days. Priority: 4.**

`ssc watch` re-parses the full file on every save.  Cache
`(path, mtime, hash) → ParsedModule`; diff at section granularity and
re-evaluate only changed sections and their dependents.

- [x] `ParseCache` — key by path + mtime + content hash
- [x] Section-level diff by heading text — `SectionDiff.compute(prev, next)`
      compares `SectionSnapshot` lists, classifies sections as added/modified/
      removed; `ssc watch` logs which sections changed and skips the interpreter
      re-run entirely on false-positive OS watch events (mtime touched but
      content unchanged).  9 tests in `SectionDiffTest`.
- [ ] Re-evaluate only changed + dependent sections (requires interpreter
      partial-reset; deferred until Interpreter split lands)
- [ ] Target: watch cycle on `rest-api.ssc` drops < 100 ms

### Generated code — JS tree-shaking

**Status: landed (feature/js-treeshake). Priority: 5.**

JS runtime preamble was emitted unconditionally into every single-file output.
Partitioned into named capability groups (Core, Async, Effects, Mcp, Dataset)
and wired up `JsGen.detectCapabilities` + `JsGen.generateRuntime` in the
single-file emit paths (`emit-js`, `emit-spa`, `emit-wc`).

What landed:
- [x] Capability groups already partitioned in `JsGen.generateRuntime` (Phase 2)
- [x] `emit-js`: detect module capabilities, emit only needed runtime blocks
- [x] `emit-spa`: detect capabilities, exclude Node-only Mcp/Dataset for browser
- [x] `emit-wc`: detect capabilities, emit only needed runtime blocks
- [x] `buildScjsSource` (dead code): updated signature for correctness
- [x] Verify: `hello.ssc` JS output drops from 273 KB → 139 KB (≈ 49 % reduction)
      (Just below 50 % target; Core alone is 138 KB.  The unconditionally-emitted
      Core block is the remaining headroom — deferred to a future micro-partition.)

### Library modularity

**Status: open. Effort: ~3 days. Priority: 6. Depends on: Interpreter split.**

1. Fix `backendInterpreter / backendJvm` dependency to `% Test` only.
2. Publish `scalascript-core` artifact (`ir + backendSpi + core`) for linters
   and tool builders.
3. Publish `scalascript-interpreter` (core eval, no HTTP/actors) for embedding.

- [ ] Fix test-scope dep leak
- [ ] Add `scalascript-core` aggregate in `build.sbt`
- [ ] Add `scalascript-interpreter` aggregate

### New tool — `ssc profile file.ssc`

**Status: landed. Effort: ~3 days. Priority: 7.**

Built-in profiler: invocation counts per function, wall time per section.
Output: top-20 hotspots, simple call graph, `--profile-output profile.json`.

- [x] Add `profile` command to CLI
- [x] Instrument `eval` / `callValue` with per-function counters (both TCO and non-TCO paths)
- [x] Print top-20 hotspots by wall time on exit
- [x] `--top N` flag to limit rows, `--output file.json` for JSON export
- [x] `ProfileCommandTest` — 5 tests, all green

### Runtime — Numeric value specialization

**Status: open. Effort: ~1 week. Priority: 8 (do after profiling).**

`IntV(n)`, `DoubleV(d)`, `BoolV(b)` are heap-allocated on every arithmetic
operation.  Pool small `IntV` instances; specialize `Computation[A]` for `Int`
fast-path.

- [ ] Pool common small `IntV` (−128..1024)
- [ ] Specialize arithmetic fast-path in `Computation`
- [ ] Target: fib(28) and sum(1e6) improve ≥ 20 % over current baseline

### Compiler — Incremental type-checking

**Status: open. Effort: ~1 week. Priority: 9. Depends on: AST cache.**

Re-check only the changed block and its transitive dependents.  Snapshot
`TypedEnv` per section; restore from last unchanged section on re-check.

- [x] `TypedEnv` snapshot per section — Landed 2026-05-19
- [x] Restore snapshot, re-run typer from changed section forward — Landed 2026-05-19
- [x] Test: changing a leaf section does not re-check unrelated sections — Landed 2026-05-19

### New tool — REPL web-aware mode

**Status: open. Effort: ~3 days.**

- `:mount GET /hello { req => Response.text("hi") }` — register a route live
- `:routes` — print the live route table
- `:http GET /hello` — fire a synthetic request and print the response

---

## ssc fmt — canonical `.ssc` formatter — LANDED ✅

**Landed** (2026-05-19) on branch `feature/ssc-fmt`.

Source-level formatter for `.ssc` files implemented as a pure string
transformation (no AST round-trip).

### What it does

- **Front-matter**: normalises key order (`name`, `version`, `description`,
  `main`, `package`, `exports`, `dependencies`, `routes`, then remaining keys
  alphabetically), strips trailing spaces, ensures exactly one blank line
  after the closing `---`.
- **Headings**: `##Title` → `## Title` (exactly one space after `#`s).
- **Code blocks**: exactly one blank line before and after each fenced block;
  block contents preserved verbatim.
- **General**: trailing whitespace stripped, LF endings, exactly one final
  newline.
- **Shebang** (`#!/usr/bin/env ssc`) preserved at position 0.
- `format(format(x)) == format(x)` guaranteed — idempotent.

### CLI flags

```
ssc fmt file.ssc [file2.ssc ...]   # format in-place
ssc fmt --check file.ssc           # exit non-zero if file needs formatting (CI)
ssc fmt --stdout file.ssc          # print to stdout
```

### What landed

- `cli/src/main/scala/scalascript/cli/Formatter.scala` — pure transformer
- `Main.scala` — `fmt` case in dispatch + help text
- `cli/src/test/scala/scalascript/cli/FormatterTest.scala` — 25 unit tests
- `docs/user-guide.md` §12 — user-facing docs

---

## v1.24 — Language features (TOP PRIORITY)

**Status: open. All items parallel-safe with each other.**

This milestone covers language ergonomics gaps most felt in real ScalaScript code.
All 7 items can be assigned to parallel agents simultaneously — they touch different
parts of the parser, typer, and all 3 backends.

### 1. Pattern matching — nested / guards / `@` binders

**Effort: ~1 week. High daily-use impact.**

```scalascript
case (Some(x), Some(y)) =>           // nested
case x if x > 0 =>                   // guard
case xs @ (h :: _) =>                // @ binder
```

- [x] Nested patterns in `Parser` + `Typer` + all 3 backends
- [x] Guard expressions (`if` in case arm)
- [x] `@` binder: bind the whole match target and a sub-pattern simultaneously
- [x] Regression tests across INT / JS / JVM

**Landed 2026-05-19.** `Pat.Bind` added to `matchPat` (Interpreter) and `genPattern` / `genForPatBinding` (JsGen). Nested patterns were already handled recursively; guards were already implemented. 17 new tests in `PatternMatchTest.scala` — all green.

### 2. Type aliases — ✓ Landed (v1.24)

**Effort: ~2 days.**

```scalascript
type UserId = String
type Result[A] = Either[String, A]
```

- [x] `type <Name>[<params>] = <type>` in parser (scalameta `Defn.Type`, no source change needed)
- [x] Typer: expand alias on use, check param arity, detect direct recursion
- [x] All 3 backends: emit nothing (aliases are erased at runtime — fallthrough cases already present)
- [x] Tests (`backend-interpreter/src/test/scala/scalascript/TypeAliasTest.scala`, 13 tests)

### 3. Opaque types

**Effort: ~3 days.**

```scalascript
opaque type UserId = String
val id: UserId = UserId("alice")   // companion constructor
```

Zero runtime overhead — same representation as underlying type, but
distinct at type-check time.  The typer treats `UserId` and `String`
as unrelated outside the defining scope.

- [x] `opaque type` in parser (scalameta parses `opaque type` natively as `Defn.Type` with `Mod.Opaque`)
- [x] Typer: nominal type check — raw underlying type is NOT assignable to opaque type outside defining scope (MVP; single-zone, no transparency inside module)
- [x] Auto-generated companion `apply` / `unapply` (interpreter synthesizes if no explicit companion)
- [x] All 3 backends — interpreter registers synthetic companion; JsGen / JvmGen fall through to existing catch-all / `other.syntax` (zero runtime change)
- [x] Tests: `OpaqueTypeTest.scala` — 8 tests, all green (v1.24)

### 4. Union types ✓ Landed

**Effort: ~1 week. Landed 2026-05-19.**

```scalascript
def show(x: String | Int): String = x match
  case s: String => s
  case n: Int    => n.toString
```

- [x] `A | B` syntax in type parser — scalameta parses Scala 3 union types natively; `typeAnnotToSType` already flattens `Type.ApplyInfix(lhs, |, rhs)` to `SType.Union`
- [x] Typer: union subtyping rules — `isCompatible` extended: `A <: A | B`, `A | B <: C` (all alts must hold)
- [x] Interpreter: extended `Pat.Typed` dispatch to also match `Long`, `Float/Number`, `Char`
- [x] JS backend: `Pat.Typed` in `genPattern` now emits `typeof x === 'string'` / `typeof x === 'number'` / `typeof x === 'boolean'` for primitive types; instance types emit `_type` check
- [x] JVM backend: no change needed — JvmGen emits Scala source and Scala 3 union types + type-test patterns are natively supported
- [x] Tests — `UnionTypeTest.scala` (12 cases): `show(String | Int)`, mixed `List[String | Int]`, three-way union, wildcard arm, union return type

### 5. Extension methods on user-defined types ✓ Landed

**Effort: ~1 week.**

```scalascript
extension (u: User)
  def fullName: String = s"${u.first} ${u.last}"
  def isAdmin: Boolean = u.role == "admin"
```

All three backends correctly dispatch extension methods on user-defined case
class types. The interpreter uses `Value.InstanceV(typeName, _)` as the key;
JsGen registers typed extensions via `_registerExt('method', fn, 'TypeName')`;
JvmGen passes `extension` blocks through as-is to Scala 3.

- [x] Audit current extension dispatch in Interpreter + JsGen + JvmGen
- [x] Fix dispatch to work for arbitrary user `case class` / `sealed trait`
- [x] Tests with multi-method extension blocks
  - `ExtensionMethodTest` — 13 tests covering interpreter, JS codegen, JVM codegen:
    no-arg extensions, multi-method blocks, extensions with additional args,
    no-collision between same-named extensions on different case classes.

### 6. Named argument call-site syntax (complete coverage) ✓ LANDED

**Effort: ~3 days.**

```scalascript
createUser(name = "Alice", age = 30, role = "admin")
```

Named args exist partially.  Audit and complete:

- [x] All 3 backends accept named args in any order (interpreter + JsGen fixed; JvmGen emits Scala syntax natively)
- [x] Default argument interaction: skipping defaults by name (non-trailing defaults supported)
- [x] Error when unknown name is used (runtime error with clear message)
- [x] Tests covering out-of-order, partial defaults (NamedArgTest.scala — 15 tests)

### 7. `given` / `using` auto-resolution improvements

**Effort: ~1 week. Status: ✓ Landed (v1.24)**

Gaps: nested givens, ambiguity resolution, `using` in anonymous functions.

- [x] Nested `given` chains resolve transitively (up to 3+ levels via `ParametricGiven` factory registry)
- [x] Ambiguity error with clear "found N candidates" message (via `givenCandidateCount` tracking)
- [x] Explicit `using factoryName` at call site instantiates the factory with correct type args
- [x] `using` in lambda position: `xs.foldLeft(m.empty)(m.combine)` and friends work via auto-resolve
- [x] `runtimeValueType` added for proper `A = List[Int]` inference (vs `runtimeElemType` for `A = Int`)
- [x] Most-specific factory wins via `specificity` scoring (avoids `wrapList[List[A]]` trumping `wrapListList[A]`)
- [x] Tests in `backend-interpreter/src/test/scala/scalascript/GivenUsingTest.scala` (11 tests, all green)

---

### Parallel-safe work plan for v1.24

Each item is independent — assign one agent per item:

| Agent | Item | Touches |
|-------|------|---------|
| A | Pattern matching | Parser, Typer, Interpreter, JsGen, JvmGen (match handling only) |
| B | Type aliases | Parser, Typer only (no backend changes) |
| C | Opaque types | Parser, Typer, minor backend name emit |
| D | Union types | Parser, Typer, JvmGen (instanceof) |
| E | Extension methods | Interpreter dispatch, JsGen dispatch, JvmGen |
| F | Named args audit | Interpreter, JsGen, JvmGen (call sites only) |
| G | given/using | Typer only |

---

## Scala ↔ ScalaScript interop — Tiers 1 + 2 landed

**Status: Tiers 1 and 2 landed; Tier 3 (sbt plugin) and Tier 4
(`--emit-scala-facade` compiler flag) still open.**  Full design doc:
[`docs/scala-interop.md`](docs/scala-interop.md).

Goal: make ScalaScript-built JAR a first-class JVM-library citizen, so a
regular Scala 3 project can `import std.foo.add` (natural FQN) instead
of `import _ssc_runtime.std_foo_add` (the v2.0 mangling).  Architected
as four independent tiers; each is shippable on its own.

### Tier 1 — compiler-emitted facade metadata — ✓ LANDED

Foundation for every other tier.  Optional field added to the
existing `.scim` envelope; everything else is library/plugin code.

- [x] `scalaFacade: Map[String, String] = Map.empty` field on
      `ModuleInterface` in `ir/Ir.scala`.
- [x] Populated by `InterfaceExtractor` using `Linker.mangle`.
      Recurses via `ExportedSymbol.nested` (depth 3, existing cap).
- [x] `_ssc_runtime.` prefix on the mangled side mirrors JvmGen's
      Phase-2 runtime-wrapping object.
- [x] Respects `exports:` front-matter — private helpers stay out
      (we build facade from the already-filtered `exports` list).
- [x] 4 new tests in `ArtifactAbiCompatibilityTest`: round-trip,
      legacy-absent-tolerant, case-sensitive keys, empty-map canonical.
- [x] 5 new `InterfaceExtractorTest` cases (top-level entries,
      package: reflected, empty-pkg bare names, nested join rules,
      `exports:` filter passes through).

ABI policy: additive optional field, absent-tolerant — fits the
2.0 strict-equality / additive-payload contract; no `abiVersion` bump.

### Tier 2 — `scalascript-interop` library — ✓ LANDED

Pure Scala 3 module providing the runtime glue + facade-source
generator that Tier 3 will consume.  Lives in this repo as
`interop/` subproject (dependsOn ir + core only — no backend deps so
downstream consumers don't pull in JvmGen / scalameta etc.).

- [x] `scalascript.interop.facade.FacadeGenerator` — reads `.scim`,
      emits one Scala 3 source per package with `export _ssc_runtime.<mangled>
      as <name>` lines.  Pure compile-time alias, zero runtime overhead.
      Multi-module packages merge.  Conservative v0.1: nested entries
      (depth > 1 beyond pkg) skipped pending JvmGen-shape pin.  Legacy
      `.scim` without `scalaFacade` falls back via `Linker.mangle`.
- [x] `scalascript.interop.loader.ScalascriptLoader` — `fromArtifactDir`
      and `fromJar` factories build a natural→mangled index; `call[A]`
      does reflective dispatch through the loaded classloader.  Two
      named exceptions surface the error cases
      (`NoSuchScalascriptSymbol`, `UnresolvedJvmMember`).  Reads
      `META-INF/scalascript/{module}.scim` resources from a JAR for
      Tier-4 self-contained mode.
- [x] `scalascript.interop.runtime.Effects` — `runEffects(...)` /
      `runEffectsAsync(...)` wrap an `Effectful[A]` thunk, returning
      `Either[EffectError, A]` / `Future[A]`.  v0.1 uses class-name
      heuristic to recognise `UnhandledEffect`; v0.2 will wire real
      handler registration once the runtime types stabilise.
- [x] `scalascript.interop.runtime.Actors` — typed `ActorRef[T]` with
      `send(msg: T)` routed through an installable `SendHook`
      dispatcher.  `wrap[T](rawRef)` for actors handed across the
      boundary.  `spawn` is a placeholder (NotImplementedError + v0.1
      message) — true spawn waits on the runtime-type bridge.
- [x] 34 tests: 10 FacadeGenerator + 4 integration + 6 Effects +
      4 Actors + 10 ScalascriptLoader.  All green.

### Tier 3 — `sbt-scalascript-interop` plugin — DEFERRED (no demand yet)

Build-tool integration so consumers don't have to hand-wire Tier 2.
Deliberately deferred: Tier 4 (`ssc link --emit-scala-facade`) gives
consumers a self-contained JAR that doesn't need a plugin at all, and
no external consumer has asked for sbt-side ergonomics yet.  When
demand materialises:

- [ ] sbt plugin: `addSbtPlugin("org.scalascript" %% "sbt-scalascript-interop" % …)`.
  Adds `Compile / sourceGenerators` task that runs `FacadeGenerator`
  against `scalascriptArtifactDir`.  Adds Tier-2 lib to
  `libraryDependencies`.  Adds linked `.jar` to `unmanagedJars`.
- [ ] Mill module trait `ScalascriptInteropModule`.
- [ ] scala-cli `directive:` form (`//> using interop "scalascript-interop"
      artifactDir ".ssc-artifacts"`).
- [ ] ~15 fixture-project tests.

When picked up, lives in `scalascript-sbt-plugin` repo (separate
repository — easier publish cadence than the monorepo).

### Tier 4 — compiler `--emit-scala-facade` flag — partial (v0.1)

**Status:** flag + plumbing + META-INF metadata embedding ✓ landed.
Facade `.class` emit ✗ blocked on a JvmGen refactor (see below).

What landed:
- [x] `ssc link --backend jvm --bytecode --emit-scala-facade` flag wired
      through `linkCommand` and `linkJvmFromBytecode`.  Flag validation
      enforces the `--bytecode` + JVM-backend combination.
- [x] `JvmBytecode.compileFacade(facadeSources, classpathDirs)` —
      writes generator output to a temp dir, runs `Scala3Driver` in
      process, returns the output dir for downstream packing.
- [x] `JvmBytecode.packBundlesAsJarWithFacade(bundles, smapByModule,
      facadeClassDir, scimResources, outJar)` — extends the existing
      pack path to also emit facade classes + arbitrary resource
      entries (used for `META-INF/scalascript/<name>.scim`).
- [x] Embed every `.scim` as `META-INF/scalascript/<name>.scim` in the
      linked JAR so `ScalascriptLoader.fromJar` works without a sidecar.
- [x] Graceful degradation: facade compile failure is non-fatal — the
      JAR still ships with META-INF resources and the reflective
      `ScalascriptLoader` path keeps working.
- [x] 6 CLI subprocess tests in `EmitScalaFacadeCliTest` (flag
      validation, META-INF byte-identity, multi-module, summary).

What's BLOCKED on a separate JvmGen refactor:
- Facade `.class` emission for `package:`-decorated modules.  v2.0
  JvmGen wraps user code in `object pkg: object subpkg: <defs>` at the
  empty package level, so the facade's `package pkg.subpkg: export
  Ssc.x as alias` block can't compile (Scala 3 rejects an
  object-name/package-name clash, and empty-package members are
  unreachable from named packages anyway).
- Facade `.class` emission for no-`package:` modules.  User code lands
  in `<scriptName>_sc$package$` (Scala 3's top-level-def wrapper),
  not under `_ssc_runtime` — so the Tier-1 facade table's
  `_ssc_runtime.<name>` mangling doesn't match the JVM symbol either.

### Tier 5 — JvmGen `package`-clause emission — ✓ LANDED

`JvmGen.generateUserOnly` now produces Scala 3 source with a real
`package` clause for `package:`-decorated modules:

```scala
import _ssc_runtime.{given, *}

package a.b:

  def f(...) = ...
```

(previously: empty-package `object a: object b: def f(...) = ...`).

Implementation: post-emit transform `unwrapPackageObjects` in JvmGen
detects the parser-introduced `object pkg: object sub:` wrap (still
present in the AST because `Parser.wrapSectionInPackage` runs first
for typer/interpreter scoping) and rewrites it to a `package pkg.sub:`
block clause, dedenting the body by `2 × pkg.size` spaces.  No-op for
modules without `package:`.

What landed:
- [x] `JvmGen.unwrapPackageObjects` — string-level transform applied
      to `genUserOnlyWithLineMap` output.
- [x] `JvmBytecode.{packBundlesAsJar, packBundlesAsJarWithSmap,
      packBundlesAsJarWithFacade}` now include `.tasty` files alongside
      `.class` so Scala 3 consumers can cross-compile against the JAR.
      Per-module .tasty is ~1-3 KB; runtime tasty adds ~150 KB to a
      ~300 KB JAR — acceptable for the first-class JVM library use case.
- [x] `InterfaceExtractor.buildScalaFacade` updated to emit IDENTITY
      mapping (`natural FQN → natural FQN`) for `package:`-decorated
      modules; empty map for no-package modules (their top-level defs
      live in Scala 3's `<file>_sc$package$` wrapper at the empty
      package, unreachable from named-package consumers).
- [x] `FacadeGenerator` skips identity entries (no `export` needed —
      natural FQN works directly via `import a.b.f`).  Legacy entries
      starting with `_ssc_runtime.` still emit re-exports for
      pre-Tier-5 artifacts on disk.
- [x] Scale benchmark held: 47/49 → 48/49 std/ modules cleared (one
      more passed after the `package`-clause change).
- [x] 7 CLI subprocess tests in `EmitScalaFacadeCliTest` (incl. new
      Tier-5 layout-pinning test that asserts `demo/a/*.class` +
      `demo/a/*.tasty` entries land in the linked JAR).
- [x] 36 interop unit tests + 97 ABI/extractor tests green.
- [x] End-to-end smoke verified: a Scala 3 consumer can write
      `import demo.a.{add, double}` against a ScalaScript-built JAR
      via `scala-cli run --jar lib.jar --scala 3.8.3` — no facade
      classes, no plugin, no manual demangling.

### Implementation order

Tier 1 → ship anytime (foundation).  Tier 2 → after Tier 1 lands.
Tier 3 → after Tier 2.  Tier 4 → after Tier 3 if there's demand.

### Out of scope (deferred)

- Scala 2 consumers (would need `type`/`val` shims instead of `export`).
- Cross-compilation type-check via TASTy (needs richer typer first).
- JS-side interop (separate doc; UMD bundle already works classpath-free).
- REPL integration (sugar on top of Tier 3, defer).

---

## Next wave — post-v1.24 plan

Sorted by priority.  Run one agent per track simultaneously.

| Pri | Item | Track | Effort | Depends on |
|-----|------|-------|--------|------------|
| 1 | Fix SupervisorTest + v1.22 distributed tests | A | 2 days | — |
| 2 | Incremental type-checking | B | 1 week | AST cache ✓ |
| 3 | LSP server (`ssc lsp`) | C | 2 weeks | — |
| 4 | Interpreter split | D | 1 week | — (serial, risky) |
| 5 | Library modularity | D | 3 days | Interpreter split |
| 6 | `ssc debug` (DAP debugger) | C | 2 weeks | Interpreter split |
| 7 | Numeric value specialization | E | 1 week | Interpreter split |
| 8 | WASM backend | F | 3 weeks | — | ✅ skeleton landed (backend-wasm, emit-wasm CLI command, Scala.js --js-wasm) |
| 9 | Package registry | G | 2 weeks | — |
| 10 | ~~Scala ↔ ScalaScript interop (Tier 1)~~ ✓ landed | H | ½ day | — |
| 11 | ~~Scala ↔ ScalaScript interop (Tier 2)~~ ✓ landed | H | 1 week | Tier 1 ✓ |
| 12 | ~~Scala interop (Tier 3 sbt plugin)~~ — deferred, no demand | H | 1 week | Tier 2 ✓ |
| 13 | ~~Scala interop (Tier 4 metadata + flag)~~ ✓ landed | H | 2 days | Tier 2 ✓ |
| 14 | ~~Scala interop Tier 5 — JvmGen package-clause emit~~ ✓ landed | H | 2-3 days | — |

Track D is serial.  All other tracks can run in parallel.

---

## Beyond

Larger features that aren't on the critical path but are worth keeping in
view so they shape near-term decisions.

- **Hot reload in `serve` mode.** ✅ **LANDED** — see `watchCommand` in
  `cli/src/main/scala/scalascript/cli/Main.scala`; port stays bound, route
  table cleared and rebuilt on each save.
- **REPL: web-aware mode.**  Tracked above in optimization roadmap.
- **`html"..."` precision.** ✅ **LANDED** — `findClosingBrace` in the
  interpreter is now string-aware: double-quoted, triple-quoted, and
  single-quoted literals are skipped so a `}` inside `${ a + "}" }`
  never prematurely closes the scan.  Four regression tests added to
  `InterpreterTest`.
- **Future web-services protocols.**  HTTP/2, gRPC, GraphQL, OpenAPI
  schema export — each questioned during v1.1 review and deferred
  with concrete reasoning.  See [`docs/future-protocols.md`](docs/future-protocols.md)
  for prerequisites, effort estimates, and why each is on hold
  until a concrete user surfaces.

## v1.25 — JavaScript / Node.js fenced code blocks

**Status: Phases 1–5 landed. Branch `worktree-js-node-blocks`.**

Symmetry fix and new target.  Pre-v1.25, `html` and `css` were
first-class string blocks (§ 3.3 of `SPEC.md`) but `javascript` was
unknown — every ```javascript``` fence silently degraded to inert
prose.  Bigger gap: there was no escape hatch for "use this npm
package / call this Node-only API" from `.ssc` code.  Both addressed
by two orthogonal additions, **same milestone, separable phases**.

### Phase 1 — `javascript` (alias `js`) as string block ✓ Landed

Parallel to `html` / `css`.  Body is a `String` after `${expr}`
interpolation against the surrounding ScalaScript scope.  Not parsed,
not type-checked.  Use cases: browser glue for the JS backend, inline
third-party snippets, SSR-rendered client JS, documentation.

- [x] `core/.../ast/Lang.scala`: `Js = "javascript"` + alias
      `JsShort = "js"`; extended `isStringBlock` and `label`.
- [x] Parser / Normalize / backends unchanged — existing
      `Lang.isStringBlock` routing in `JsGen`, `JvmGen`, and the
      interpreter picks the new tag up automatically.
- [x] Unit tests in `LangTest` plus three regression tests in
      `InterpreterTest` confirming `${expr}` interpolation, alias
      handling, and that `javascript` is **not** html-escaped.

### Phase 2 — `node.js` (alias `node`) lang tag — front-end recognition ✓ Landed

New "opaque executable" classification — neither parseable nor a
string block.  At this phase the tag is only *recognised*; runtime
semantics arrive with Phase 3.

- [x] `Lang.scala`: `Node = "node.js"` + `NodeShort = "node"`,
      plus `isNode` / `isOpaqueExec(lang)` predicates.
- [x] Parser + `Normalize` already route unknown lang tags through
      `Content.EmbeddedBlock(language, source)` — no change needed;
      `node.js` blocks survive verbatim into the IR.
- [x] `NodeJsBlockTest`: lang tag preserved verbatim from the fence,
      `node` alias classifies identically, `Normalize` produces an
      `EmbeddedBlock` with source intact, full
      Parse → Normalize → Denormalize round-trip preserves the body
      character-for-character with no JS parser invoked.
### Phase 3a — `Capabilities.blockLanguages` axis + `CapabilityCheck` ✓ Landed

Shape-only SPI extension: a new optional `blockLanguages: Set[String]`
field on `Capabilities` (default `Set.empty`).  A backend lists the
opaque-executable lang tags it consumes.  `CapabilityCheck.validate`
now also walks every `EmbeddedBlock` and emits
`Diagnostic.UnknownBlockLanguage(lang)` for any lang satisfying
`Lang.isOpaqueExec` that isn't in the backend's declared set.  String
blocks (`html` / `css` / `javascript`) and inert tags (`python`,
`yaml`, …) are not affected.  Six new tests pin the contract.

### Phase 3b — `backend-node` module ✓ Landed

New SPI backend with target id `"node"`, registered via
`META-INF/services` so `--list-backends` discovers it.  Pipeline:

1. Walk the IR collecting every `node.js` / `node` `EmbeddedBlock`
   in document order → verbatim glue prefix.
2. `JsGen.detectCapabilities(...)` → `JsGen.generateRuntime(caps)`
   for the full JS runtime preamble.
3. `JsGen.generate(...)` produces the user code (scalascript/scala +
   string blocks).
4. Append a Node-specific flush epilogue that pumps the JsRuntime's
   `_output` buffer to `process.stdout`.
5. Return `CompileResult.TextOutput(code, "javascript", Nil)`.

`NodeCapabilities` mirrors `JsCapabilities`' feature set plus
`blockLanguages = Set("node.js", "node")`.  Intrinsic table is
shared with JsBackend.  No JS parser anywhere in the pipeline.

Eight tests in `NodeBackendTest`: identity / capabilities / glue
ordering / multiple-block ordering / `node` alias / CapabilityCheck
acceptance — plus an end-to-end integration that actually compiles
a `.ssc` document and runs the emitted bundle under `node`,
asserting on stdout.  (CI without Node on PATH skips the
integration via `assume(hasNode, ...)`.)

Note: emitted bundles are CommonJS-compatible (`.cjs`) — JsRuntime
uses `require('fs')` for FileSystem helpers; ESM-mode `.mjs`
emission is deferred until a follow-up rewrites the runtime in
import form.

### Phase 4 — `extern def` ↔ `globalThis` bridging ✓ Landed

Surface for ScalaScript to call into JS-defined symbols.  Turned out
to require **no codegen changes**: JsGen already skips emission of
`extern def` stubs (`JsGen.scala` line 6685 — the existing
`EffectAnalysis.isExternDef` path drops the def entirely), and JS's
free-name resolution at call sites picks up the `globalThis.<name>`
assignment from the linked `node.js` glue block.

The full bridging contract therefore reduces to:

  - **ssc side** declares the FFI with `extern def name(...): T`.
    Provides type information to the typer; JsGen emits no body.
  - **`node.js` block side** assigns `globalThis.<name> = ...`.
    Linked verbatim into the bundle by the Node backend (Phase 3b).
  - **Runtime resolution** is name + arity.  Signature mismatches
    surface as plain JS runtime values — the `Int` on the ssc side
    is a contract, not a derivation.

Two new integration tests in `NodeBackendTest` pin this end-to-end:
one happy-path with `extern def add(...)` + `extern def fileBaseName(...)`
calling into Node's `require('path').basename`; one regression guard
asserting that a deliberate signature mismatch executes anyway,
documenting the "runtime contract" semantics.

No spec or `JsGen` code changes were required — the existing
infrastructure was already shaped for this once Phase 3b put the
glue blocks in front of the user code.

### Phase 5 — examples + conformance ✓ Landed

- [x] `examples/node-fs-read.ssc` — ssc CLI that reads a file via a
      `node.js` block calling `require('fs')` and exposing
      `globalThis.readUtf8` / `globalThis.exists` / `globalThis.basename`.
      ssc side declares the FFI with `extern def`.  Compiles + runs
      end-to-end under `node`.
- [x] `examples/js-glue-component.ssc` — `javascript` string block
      bound to a section identifier, demonstrating `${expr}`
      interpolation against ScalaScript values (no JS parser
      involved).  Pairs with `html` and produces both as String
      fields on the section.
- [x] Manual cross-backend smoke confirming every non-Node backend
      (jvm / js / scalajs-spa / wasm / int) emits
      `UnknownBlockLanguage(node.js)` for a `node.js` block thanks
      to Phase 3a's `CapabilityCheck` walk.

**Follow-up (separate milestone, not blocking v1.25 close):**

- [ ] Dedicated conformance fixtures for the `node` target —
      requires the conformance harness to gain a notion of
      backend-specific golden outputs (today it runs every fixture
      against every bundled backend with a single expected file).
      Filed for whoever next extends the conformance suite to
      support backend-specific gates.

### Open questions

- Should `node.js` blocks be allowed to appear in a non-Node bundle and
  be silently dropped (current design: no, hard error) — revisit if
  the literate-programming use case bites.
- Top-level `await` in `node.js` blocks: allowed unconditionally, since
  the emitted file is `.mjs`.  Document in `docs/targets.md`.
- TypeScript-typed `node.js` blocks (`node.ts` tag + `tsc --noEmit`
  validation): explicitly *out of scope* for v1.25.  Filed as a v1.26+
  candidate if demand surfaces.

### Design source of truth

§ 3.3 of `SPEC.md` (table extension landed with Phase 1+2).

## v0.5 — Interpreter performance (Tier 1) — landed

Closed in a series of small commits on the
`worktree-interp-perf-slots` branch.  The interpreter is now 3-8×
faster on the bench workloads (target was ~5×, hit it).

  | workload | before | after | speed-up |
  | -------- | -----: | ----: | -------: |
  | fib(28)  | 2734   |   330 |  **8.2×** |
  | sum(1e6) | 2886   |   610 |  **4.8×** |
  | list-ops | 580    |   175 |  **3.3×** |

What landed:

- **TCO-analysis cache on FunV.**  `tailCallTargets` / `callsInTailPos`
  / `hasNonTailSelfCall` were running on every invocation; now cached
  in an IdentityHashMap and reused.
- **Env trim.**  `globals.toMap` no longer splatted into env per call;
  `eval`'s `Term.Name` falls back through `globals` directly, and the
  defaults-pass base env is built lazily.
- **Pure-value shortcuts in eval.**  When `Term.ApplyInfix` /
  `Term.Apply` / `Term.If`'s sub-Computations are already `Pure`, call
  `infix` / `dispatch` / `callValue` directly and skip the FlatMap.
- **Trampoline stable-env hoist.**  The TCO loop rebuilds only the
  per-iteration param binding; `closure + selfTco + mutualEntries`
  is computed once per `curFun` and reused.
- **Param specialisation (`.updated` chains, 1- / 2-param frames).**
  Non-TCO calls use `closureWithSelf.updated(p, v)` for 1-arg
  functions and a chain for 2-arg, skipping the generic
  `zip + toMap` builder for the common arities.
- **Lit interning by AST identity.**  Per-Lit `Pure(IntV(...))`
  caching in an IdentityHashMap — single biggest step.
- **closureWithSelfFor cache.**  `closure.updated(name, self)` is the
  same Map on every call of the same FunV; cache it.
- **FrameMap (1 / 2 / N slots).**  A specialised `Map[String, Value]`
  that stores a small frame of local bindings as direct fields on top
  of a `parent` map.  Construction is one allocation; lookup is a
  string-equality check on the slot then a fall-through to parent.

Re-benchmark protocol after each step lives in this commit history;
re-running `scala-cli bench/run.sc` should reproduce the figures.

Tier 2 (bytecode IR) and Tier 3 (JVM-bytecode JIT or Truffle/Graal)
are out of scope until a real workload demands them — `ssc compile`
already covers throughput.

## v0.6 — Optics (Lens / Prism / Optional / Traversal) — landed

Full optic hierarchy built around `Focus[T](_.path)` and `Prism[Sum, Var]`,
landed in four PRs.  All four backends (interp / JS / JVM) share the same
surface and produce identical output for `println` on instances and optics.

- **Case-class `.copy(field = v, ...)` and `Focus[T](_.a.b.c)` → Lens.**
  Initial version: named-only copy plus path-Lens construction.  Lowering:
  Lens runtime in interpreter (`InstanceV("Lens", …)`), JS runtime helper
  `_makeLens`, JVM preamble `case class Lens[S, A]` with literal emission.
  (PR #17)

- **`Prism[Sum, Variant]` → sum-type optic.**  `getOption` / `set` /
  `modify` / `reverseGet` / `andThen`.  Drive-by fix: JVM `_show` walks
  `Option` / `Map` / `List` / `Tuple` / `Product` recursively so render
  of `Some(Circle(5.0))` matches interpreter / JS.  (PR #18)

- **Optional via `.some` in the Focus path.**  `Focus[T](_.maybe.some.field)`
  produces `Optional[T, A]` with no-op `set` / `modify` on missing layers.
  Cross-optic `andThen` lifts Lens to Optional when composed.  JS runtime
  preamble split across two triple-quoted parts (combined size now exceeds
  the JVM's 64KB string-literal limit).  (PR #19)

- **Traversal via `.each` in the Focus path.**  `Focus[T](_.items.each.field)`
  → `Traversal[T, A]` with `getAll` / `modify` / `set` / `andThen`.
  Any optic composed with a Traversal becomes a Traversal.  Universal
  cross-type `andThen` overloads on `Lens` / `Optional` / `Traversal`.
  (PR #20)

- **Optic polish.**  Positional `.copy(...)` args (followable by named
  overrides — matches Scala 3); `_show(optic)` renders the source-like
  path (`Lens(_.a.b)`, `Optional(_.x.some.y)`, `Traversal(_.items.each.x)`,
  `Prism[?, Circle]`) on all three backends — replaces the previous
  `<function>` mess.

Conformance: 27 tests across INT / JS / JVM, 81 PASS results.  Examples /
SPEC §5.5 / README "What Works" updated as each stage landed.


## v1.1 — Standard type-class hierarchy — landed

Small, principled std library of FP type classes living in `std/`,
with instances for the built-in types (`List`, `Option`, `Either`,
`Tuple2`).  All declarations use existing Scala 3 `trait` + `given`
machinery — no new keywords, no new parser syntax.  Ten classes
organised in three lanes; explicitly excludes Category / Arrow /
Profunctor (deferred, see end of section).  `Eq`, `Order`, `Show`
already covered by the `typeclass` conformance test and not
re-implemented.

    Algebraic lane           Functor / effect lane          Container lane

      Semigroup                     Functor                    Foldable
          │                            │                          │
          ▼                            ▼                          ▼
        Monoid                    Applicative              Traversable
                                       │                  (extends Foldable;
                                       ▼                   adds map directly
                                   Selective               since cross-file
                                       │                   trait inheritance
                                       ▼                       Bifunctor
                                     Monad                  is unsupported)
                                       │
                                       ▼
                                  MonadError

Landed in seven incremental PRs (each useful in isolation):

- **Step 0 — JS extension-in-given fix.**  Extension methods
  declared inside `given … with` weren't registered into the JS
  `_extensions` table — they silently dropped on the JS backend.
  Fix: recurse into `Defn.ExtensionGroup` in `Defn.Given` body.
  Unblocks every typeclass in this milestone.  (PR #25)

- **Step 1 — Semigroup + Monoid.**  Foundation: `intSum`,
  `stringConcat`, `listConcat`, `combineAll` / `combineAllOption`.
  One canonical instance per type — alternatives belong in user code
  via newtype wrappers.  (PR #34)

- **Step 2 — Functor / Applicative / Monad.**  HKT trait hierarchy
  with `extension` dispatch.  Instances for `List` and `Option`;
  per-instance helpers (`pureList`, `map2Option`, `sequenceList`,
  …) since `using` doesn't auto-resolve.  Drive-by fixes: interpreter
  now forwards extension methods on import, JS encodes receiver type
  into the extension fn name so `Functor[List].map` and
  `Functor[Option].map` no longer collide.  (PR #37)

- **Step 3 — Foldable + Traversable.**  `foldLeft` / `foldRight` /
  `toList` plus `map` on `Traversable`; helpers per (T, F) for
  `traverse`.  Uncurried `foldLeft(z, f)` so `List`'s built-in
  curried form doesn't accidentally shadow.  (PR #38)

- **Step 4 — Either + Selective.**  Ships `std/either.ssc` (sealed
  trait + `Left` / `Right`) plus the `Selective[F]` typeclass with
  instances for `List` and `Option`.  JS dispatch fix: extensions
  are now keyed by `(receiver type, method)` via a new `_typeOf`
  runtime helper, so same-named extensions across typeclass
  instances no longer route to the wrong body via the try/catch
  fallback.  (PR #39)

- **Step 5 — MonadError.**  `MonadError[Option, Unit]` with full
  extension dispatch; `Either[String, *]` exposed through helper
  functions (`raiseEither` / `handleEither` / `attemptEither`)
  because the interpreter doesn't route extensions through
  user-defined sealed-trait hierarchies yet.  (PR #40)

- **Step 6 — Bifunctor.**  `Bifunctor[Tuple2]` via extension
  dispatch; `Either` again via helpers.  Interpreter
  `extensionDispatch` now recognises `TupleV` (routed as
  `Tuple2` / `Tuple3` / …) so `(a, b).bimap(…)` works.  (PR #41)

- **Step 7 — Aggregator + transitive imports.**  `std/index.ssc`
  pulls the whole library in through one import; JS `genImport`
  now propagates nested `Content.Import` (with cycle protection
  mirroring `JvmGen.importedFiles`), so a downstream lib's `Left`
  / `Right` constructors reach consumers of `selective.ssc` without
  having to import `either.ssc` separately at every call site.

### Conformance

38 tests across INT / JS / JVM (was 28 before this milestone).  All
std typeclasses plus the aggregator pass green on every backend.
The 7 new tests:

    std-semigroup-monoid           PASS  [INT] [JS] [JVM]
    std-functor-applicative-monad  PASS  [INT] [JS] [JVM]
    std-foldable-traversable       PASS  [INT] [JS] [JVM]
    std-selective                  PASS  [INT] [JS] [JVM]
    std-monaderror                 PASS  [INT] [JS] [JVM]
    std-bifunctor                  PASS  [INT] [JS] [JVM]
    std-index                      PASS  [INT] [JS] [JVM]

### Explicitly deferred — `Category` / `Arrow` / `Profunctor`

Theoretically elegant but practically zero pull in user code without
profunctor-encoded optics, which we've decided to keep concrete.
Holding these for a possible future "Optics 3 — profunctor rewrite"
milestone only if a concrete consumer surfaces.


---

## Speculative — Smart contracts backend

> Not scheduled. No concrete timeline. Here for ideation — revisit when
> the WASM backend is stable and there is a concrete target chain.

ScalaScript's functional core (immutable values, algebraic types, effect
tracking, deterministic evaluation) maps naturally onto the constraints of
smart contract VMs.  The open questions below need answers before any
implementation work starts.

### Why it fits

- Smart contracts must be **deterministic** — no I/O, no randomness, no
  system calls.  ScalaScript's effect system already tracks and restricts
  side effects; a `@contract` annotation could enforce no-effect purity
  statically.
- **Algebraic types + pattern matching** are exactly the right tool for
  encoding contract state machines.
- **Formal verification hooks** — the typed IR can feed a proof assistant
  (Lean 4, Coq) or SMT solver without changing the source language.
- The planned **WASM backend** opens the door to WASM-based chains for
  free, once it ships.

### Open question 1 — which chain(s)?

Different chains have very different VM models:

| Chain | VM | Native language | Notes |
|-------|----|-----------------|-------|
| Ethereum / EVM chains | EVM (stack machine) | Solidity, Vyper | Largest ecosystem; custom IR needed |
| Solana | SBF (BPF variant) | Rust | High throughput; no WASM path |
| Cardano | UPLC (lambda calculus) | Haskell / Plutus / **Scalus** | Strongest FP alignment; use **Scalus** — no custom VM backend needed |
| Polkadot / ink! | WASM | Rust | WASM backend would cover this |
| Near | WASM | Rust, JS | WASM backend would cover this |
| Cosmos / CosmWasm | WASM | Rust | WASM backend would cover this |
| Aptos / Sui | Move VM | Move | Novel ownership model |

**Most natural fit** given the planned WASM backend: **Near, Polkadot/ink!,
CosmWasm** — zero extra VM work once WASM is stable.

**Highest ecosystem value**: **EVM** — but needs a dedicated EVM bytecode
backend (different from WASM).

**Fastest path with strongest type-theory alignment**: **Cardano via Scalus** —
[Scalus](https://github.com/nau/scalus) is a Scala 3 library that compiles Scala
code to Plutus UPLC on the JVM.  ScalaScript already targets the JVM; `JvmGen`
can emit Scala 3 + Scalus annotations for `kind: contract` / `chain: cardano`
modules.  No new VM backend needed.

**Decision (2026-05-19):** Cardano target uses **Scalus**.  WASM chains to be
decided when the WASM backend ships.

### Open question 2 — contract model

What does a ScalaScript smart contract look like?

```scalascript
---
name: token
kind: contract
chain: near
---

# Token contract

```scalascript
@state
case class TokenState(balances: Map[String, Int], totalSupply: Int)

@view
def balanceOf(account: String): Int =
  State.get[TokenState].balances.getOrElse(account, 0)

@call
def transfer(from: String, to: String, amount: Int): Unit =
  direct[State[TokenState]] {
    val s = State.get[TokenState].!
    require(s.balances.getOrElse(from, 0) >= amount, "insufficient balance")
    val s2 = s.copy(balances =
      s.balances
        .updated(from, s.balances(from) - amount)
        .updated(to,   s.balances.getOrElse(to, 0) + amount))
    State.set(s2).!
  }
```

- `@state` — persistent storage type
- `@view` — read-only call (no gas for state write)
- `@call` — state-mutating transaction
- `direct[State[TokenState]]` — existing monadic do-notation over contract state

### Open question 3 — gas metering

Does ScalaScript insert gas checks automatically (like Ethereum's opcode
pricing), or does the underlying VM handle it?

- WASM VMs (Near, Polkadot): the runtime meters WASM instructions — no
  compiler work needed.
- EVM: every opcode has a gas cost; the compiler must emit `GAS` checks.
- Cardano: script size + execution units are the cost model; no per-opcode
  metering.

### Open question 4 — formal verification hooks

Long-term: can the typer emit proof obligations that Lean 4 / Z3 can
discharge?  The typed IR already has enough structure.  This is PhD-level
research territory — keep it in mind when designing the contract annotation
model, don't build for it yet.

### Suggested first step — Cardano/Scalus (no WASM needed)

The fastest concrete path to a working smart contract:

1. Add `scalus` as a dependency to `backend-jvm/` (or a new `backend-cardano/` module).
2. In `JvmGen`, when `kind: contract` + `chain: cardano` appear in front-matter,
   emit Scala 3 code with Scalus `@Validator` annotation wrapping the user logic.
3. Map `@state`, `@view`, `@call` annotations to Scalus's `Data` encoding.
4. Write 3 sample contracts: token transfer, simple auction, multisig.
5. Test on Cardano preview testnet.

No new language features needed — reuse existing type system + JVM backend.
`direct[State[TokenState]]` do-notation already maps to Scalus's state model.

**Prerequisite:** none — can start after v1.24 language features land.

### Suggested second step — WASM chain (when WASM backend ships)

Thin `backend-wasm-contract/` layer on top of `backend-wasm/` for Near or Polkadot.

---

## Speculative — Apache Spark backend

> Phase 1 landed (2026-05-19): `backend-spark/` sbt module + `SparkGen.scala` +
> `ssc emit-spark` + `ssc run --backend spark` CLI wiring + `examples/word-count.ssc`.
> v1.25 § 9.5 Phase A (SPI wrap), B.1 (`--spark-master` / `spark-master:`),
> B.2 (`ssc submit` — fat JAR via `scala-cli --power package --assembly` +
> shell-out to `spark-submit --master <url> --class runSparkJob <jar>` with
> `--` pass-through for cluster-specific tuning),
> C.1 (`sql` block → `spark.sql(text, args)`), C.2 (section-based `<sectionId>.sql`
> alias), C.3 slice 1 (`>10` binds → `java.util.Map.ofEntries`), C.3 slice 2
> (widen `sparkImports` with `Row`, `DataFrame`, `types._`), C.3 slice 3
> (`spark-config:` front-matter map → sorted `.config(k, v)` on
> `SparkSession.builder()`), C.3 slice 4 (`spark-app-name:` front-matter
> overrides `.appName(...)` so the Spark UI / history server / driver+executor
> logs show a human-readable per-job name), C.3 slice 5 (typed reader
> convenience shims `Dataset.{fromParquet,fromJson,fromCsv}(path): DataFrame`
> for one-shot reads), C.3 slice 6 (same readers gain variadic
> `options: (String, String)*` pairs so `Dataset.fromCsv("/p", "header" ->
> "true", "inferSchema" -> "true")` works inline — chains
> `spark.read.options(options.toMap).X(path)`), C.3 slice 7 (symmetric
> writer extension methods on Dataset[T] — `ds.toParquet(path, opts*)`,
> `.toJson(...)`, `.toCsv(...)` — delegate to
> `ds.write.options(opts.toMap).X(path)`; `mode` is intentionally
> NOT in the options map and users chain `.write.mode(...)` directly when
> they need overwrite/append), C.3 slice 8 (adaptive default configs —
> `spark.ui.enabled=false`, `spark.sql.shuffle.partitions=4`, and the
> log4j WARN override are emitted ONLY when `sparkMaster.startsWith("local")`;
> cluster targets get Spark's own defaults instead), and C.3 slice 9
> (schema bridge — `Dataset.schemaOf[T : Encoder]: StructType` plus typed
> reader cousins `Dataset.{fromParquetAs,fromJsonAs,fromCsvAs}[T : Encoder]`
> that chain `spark.read.schema(schemaOf[T]).options(opts.toMap).X(path).as[T]`
> so a case-class declaration IS the schema specification — closes C.3)
> all landed.  CLI side: `--describe-backend` also grew `capabilities.options`
> + `capabilities.blockLanguages` lines so the Spark surface is fully
> discoverable from the command line.
> v1.25 § 9.5 milestone is now complete end-to-end (Phases A, B.1, B.2, C.1,
> C.2, all of C.3, plus Phase D — `@SqlFn` UDF bridge from `scalascript`
> declarations to `sql` blocks via auto-emitted `spark.udf.register` calls);
> the Spark backend covers Phase 1 (local), Phase 2 (cluster submission),
> and Phase 3 (Spark SQL / DataFrames including typed readers,
> case-class schema derivation, and SQL-callable UDFs).
>
> **Phase E (landed 2026-05-20) — Scala 3 native Spark `Encoder` derivation.**
> Inline `given derived[T <: Product]` in `SscSparkEncoders` (emitted at
> the top of every Spark source) builds `AgnosticEncoders.ProductEncoder[T]`
> from a Scala 3 `Mirror.ProductOf[T]` and wraps via `ExpressionEncoder(...)`.
> No TypeTag, no macros, no third-party libs.  Primitive encoders surfaced
> as plain givens that wrap `Encoders.STRING/scalaInt/...`;
> `import spark.implicits._` dropped from the emit (its TypeTag-bound
> `newProductEncoder` poisons implicit search) and replaced by
> `import SscSparkEncoders.given`.  Emitted source pins
> `//> using scala 3.7.1` (Scala 3.8.x has a TASTy-bridge regression that
> breaks Spark `_2.13` runtime reflection) and the standard Spark JDK 17+
> add-opens flags as `//> using javaOpt` directives, so
> `scala-cli run <file>` works with zero command-line args.
> Verified end-to-end: `examples/spark-encoder-demo.ssc` runs under
> Scala 3.7.1 + Spark 4.0.0 + JVM 21 producing a typed `Dataset[User]`
> with the expected schema and `.filter`/`.collect` round-trip.
>
> **Phase E follow-ups landed (2026-05-20):**
> - ✓ `Option[T]` fields via `AgnosticEncoders.OptionEncoder` — schema
>   shows `nullable = true` and `Some`/`None` survives the round trip.
> - ✓ Nested case classes — recursive AgnosticEncoder summon via
>   `summonInline[AgnosticEncoder[t]]` for each field; nested products
>   land as Spark `struct` columns.  Verified end-to-end with
>   `examples/spark-nested-demo.ssc` (`Person` with `Option[Int]` age
>   + nested `Address`).
> - ✓ Collection fields (`Seq[T]`, `List[T]`, `Vector[T]`, `Set[T]`,
>   `Array[T]`, `Map[K, V]`) via `AgnosticEncoders.IterableEncoder` /
>   `ArrayEncoder` / `MapEncoder`.  `containsNull` /
>   `valueContainsNull` derived from the inner encoder's `nullable`
>   so `Seq[Option[String]]` gets `containsNull = true` automatically.
>   Verified end-to-end with `examples/spark-collections-demo.ssc`
>   (`Post` with `Seq[String]` tags, `List[Int]` scores,
>   `Map[String, String]` meta).
>
> **Phase E follow-ups landed (cont., 2026-05-20, batch 3):**
> - ✓ `@SqlFn` auto-emit revival.  `extractSqlFns` parses param types
>   and return type from the `def` signature; emit wraps the user's
>   function in Spark's Java `UDFN` functional-interface form
>   (TypeTag-free) with an explicit `DataType` looked up via
>   `SparkGen.SqlFnDataType`.  Phase D's headline UX ("`@SqlFn def fn`
>   makes the function callable from sql blocks") now actually works
>   end-to-end on Scala 3 + Spark `_2.13`.  Limitations: only `def`
>   form is recognised; generic return types degrade to `StringType`
>   + `// TODO`.  Verified with `examples/spark-udf-demo.ssc`.
> - ✓ Tuple-as-field — `Mirror.ProductOf[(A, B, …)]` is auto-synth'd
>   by Scala 3 since tuples are products, so the existing
>   `aenc_Product[T <: Product]` given handles tuples as case-class
>   fields with no extra code.  Spark emits them as
>   `struct<_1, _2, …>` columns.  Verified with
>   `examples/spark-tuple-demo.ssc`.
>
> **Phase E status: all formerly-open follow-ups landed.**  Spark
> milestone (v1.25 § 9.5) closed end-to-end for case classes with
> primitive, `Option`, nested, collection, tuple, and UDF features.
>
> **Phase F — Structured Streaming (in progress, 2026-05-20).**
>
> - [x] F.1 — Spec doc `docs/spark-streaming.md`: goals / non-goals,
>       source-sink detection table (rate, file csv/json/parquet, kafka,
>       socket, console, foreach), `awaitTermination()` shim rule, Kafka
>       dep auto-emit rule, trigger/watermark/window passthrough,
>       migration (purely additive — existing batch examples unchanged),
>       phases F.2–F.4, testing strategy (codegen tests always +
>       smoke tests gated by `RUN_SPARK_INTEGRATION`, Kafka smoke
>       gated by `RUN_SPARK_KAFKA`), open questions.
> - [x] F.2 — Core streaming codegen (2026-05-20): added
>       `Trigger`/`StreamingQuery`/`OutputMode` imports to
>       `sparkImports`; new `SparkGen.containsStreaming` /
>       `containsAwaitTermination` / `containsKafkaFormat`
>       detection helpers; refactored `genModule` to run
>       `extractSqlFns` once per block (shared between detection
>       and emission) and auto-append
>       `spark.streams.active.headOption.foreach(_.awaitTermination())`
>       right before `spark.stop()` when streaming markers are
>       present but the user hasn't called `awaitTermination`
>       themselves; new `examples/spark-streaming-rate-console.ssc`
>       (rate → console with `Trigger.ProcessingTime("1 second")`,
>       no external deps).  9 new SparkGenTest cases pin the
>       detection semantics + shim emission; smoke test added to
>       SparkRuntimeSmokeTest (gated by `RUN_SPARK_INTEGRATION`).
>       Existing 115 SparkGenTest cases unchanged.  Phase F.4's
>       Kafka dep emission also landed in the same slice because
>       the detection plumbing was shared (small enough that
>       splitting would have been mechanical).
> - [x] F.3 — File source/sink + checkpointing (2026-05-20): new
>       `SparkGen.containsFileStreamSink` (case-insensitive regex on
>       `.format("parquet"|"csv"|"json"|"orc"|"text")`) and
>       `containsCheckpointLocation` detection helpers; when the
>       module is streaming AND uses a file format AND the user
>       hasn't already set `checkpointLocation`, the generated
>       source header gains a `// NOTE Phase F.3` reminder block
>       (Spark refuses to `start()` file-sink streams without
>       `checkpointLocation`).  Example
>       `examples/spark-streaming-file-parquet.ssc` watches a
>       parquet input dir, transforms, writes parquet output with
>       a checkpoint dir; smoke-test verified.  5 new SparkGenTest
>       cases pin the emission/suppression semantics.
> - [x] F.4 — Kafka source/sink (2026-05-20): dep auto-emit landed
>       with F.2 (`.format("kafka")` detection → `//> using dep
>       "org.apache.spark:spark-sql-kafka-0-10_2.13:<v>"`).  New
>       `examples/spark-streaming-kafka.ssc` (Kafka topic in →
>       upper-case → Kafka topic out, with checkpoint).  Smoke test
>       added behind double-gate `RUN_SPARK_INTEGRATION=1` +
>       `RUN_SPARK_KAFKA=1` — keeps default `sbt test` green on
>       machines without Kafka.
>
> **Phase F status: F.1–F.4 all landed.**  Structured Streaming end
> to end on Scala 3.7.1 + Spark 4.0.0 — rate/console smoke-tested,
> file/parquet smoke-tested, Kafka dep + example landed
> (broker-gated smoke).  No Spark 4 + Scala 3 interop surprises:
> Structured Streaming reuses the same Catalyst / Encoder machinery
> Phase E already proved works.  Two non-blockers surfaced for
> follow-up: (a) the streaming guard pins
> `spark.streams.active.headOption` which awaits only the FIRST
> started query (multi-query programs need explicit
> `awaitAnyTermination`); (b) the `\$$` Scala 3 string-interp warning
> in the Phase E shim's `Ordering[T]` extension is unrelated to
> streaming but surfaces on every emitted file as a deprecation
> hint (cosmetic; doesn't block compile).
>
> Natural fit: ScalaScript's existing `Dataset[T]` API maps directly to Spark.

#### Lakehouse formats track — Delta / Iceberg / Hudi

> Goal: when a `.ssc` program uses `.format("delta")` /
> `.format("iceberg")` / `.format("hudi")` (read or write), `SparkGen`
> auto-emits the right `//> using dep` plus the `SparkSession.builder()`
> configs (SQL extension + catalog override) needed for the runtime to
> initialise.  Detection is regex-driven on the raw source, same shape
> as the existing `@SqlFn` parser — purely additive over Phase E
> (no break to the 115 existing `SparkGenTest` cases or the working
> smoke-test set).
>
> Full plan: [`docs/spark-lakehouse.md`](docs/spark-lakehouse.md).
>
> Phases (each independently shippable per AGENTS.md rule 3):
>
> - **L.1 — Spec doc (landed 2026-05-20).**  `docs/spark-lakehouse.md`
>   covering goals / non-goals / detection mechanism / format →
>   coord+config table / phases L.2–L.4 / testing strategy / open
>   questions.  No code changes; gives the parallel Streaming track
>   (`feature/spark-phase-f-streaming`) a stable contract to compose
>   against.
> - **L.2 — Delta Lake (landed 2026-05-20).**  `.format("delta")`
>   detection (case-insensitive regex on all collected block sources)
>   triggers `//> using dep "io.delta:delta-spark_2.13:3.2.0"` in the
>   header plus `.config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")`
>   and `.config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")`
>   on `SparkSession.builder()`.  Layered between adaptive `local*`
>   defaults and user `spark-config:` so user overrides win
>   (Spark builder is last-write).  `SparkGen.DefaultDeltaVersion`
>   constant pins 3.2.0 (first Delta release with confirmed
>   Spark 4 `_2.13` support).  Tests: 12 new `SparkGenTest` cases
>   covering positive/negative detection, read+write symmetry,
>   case-insensitive matching, the substring trap (`"delta-stage"`
>   must not match), config ordering, and the helper functions
>   (`detectLakehouseFormats`, `lakehouseConfigs`) directly.
>   Example `examples/spark-delta-demo.ssc` (case-class Dataset
>   round-trip via `.format("delta")` write + read).  Smoke test
>   gated by `RUN_SPARK_INTEGRATION=1` AND `RUN_SPARK_DELTA=1`.
> - **L.3 — Iceberg (DEFERRED, 2026-05-20).**  Iceberg's Spark
>   runtime artifact is named after the Spark major.minor it targets
>   (`iceberg-spark-runtime-3.5_2.13`).  The 3.5 line is the latest
>   published and does NOT link cleanly against Spark 4.0.0
>   (Catalyst symbol changes in Spark 4 break the 3.5-bundled
>   implementation classes).  No `iceberg-spark-runtime-4.0_2.13`
>   artifact is published.  Re-opens once Iceberg ships a Spark 4
>   build.  Slot-in pattern in `docs/spark-lakehouse.md` § L.3:
>   `DefaultIcebergVersion` constant + extend `detectLakehouseFormats`
>   and `lakehouseConfigs` — `genModule` itself doesn't change.
> - **L.4 — Hudi (DEFERRED, 2026-05-20).**  Same Spark-major naming
>   issue as L.3: `hudi-spark3.5-bundle_2.13` is the latest released
>   and is built against Spark 3.5.  No `hudi-spark4.0-bundle_2.13`
>   artifact is published.  Hudi community tracks Spark 4 support
>   under HUDI-7706; L.4 re-opens once the artifact ships.  Slot-in
>   pattern symmetric to L.3.

#### Phase G — Catalog / Hive metastore DSL

> Goal: first-class DSL for the Spark Catalog — auto-registering
> Datasets as temp views, wiring the Hive metastore + warehouse via
> front-matter, and typed table reads via `Dataset.fromTable[T]`.
> Layered on Phases A–F (Spark backend) + Lakehouse L.1–L.2.  All
> detection is regex-driven on the raw block source, same shape as
> the existing `extractSqlFns` (Phase D) and `detectLakehouseFormats`
> (L.2) helpers.  Purely additive over the existing surface — no
> break to the 141+ existing `SparkGenTest` cases or the working
> smoke-test set.
>
> Full plan: [`docs/spark-catalog.md`](docs/spark-catalog.md).
>
> Phases (each independently shippable per AGENTS.md rule 3):
>
> - **G.1 — Spec doc (landed 2026-05-20).**  `docs/spark-catalog.md`
>   covering goals / non-goals / front-matter keys / annotation
>   semantics / `Dataset.fromTable[T]` / composition with C.1-C.3,
>   D, E, F, L.2 / testing strategy / open questions.  No code
>   changes; gives implementers G.2–G.4 a stable contract.
> - **G.2 — Front-matter for metastore + warehouse (landed 2026-05-20).**
>   New `spark-hive-metastore:` (Thrift URI) and `spark-warehouse:`
>   (path) keys threaded through `BackendOptions.extra` into
>   `SparkGen`.  Emits `.config("spark.sql.catalogImplementation",
>   "hive")` + `.config("spark.hadoop.hive.metastore.uris", "<uri>")`
>   + `.config("spark.sql.warehouse.dir", "<path>")` +
>   `.enableHiveSupport()` on the builder when either key is set
>   (or when `enableHiveSupport()` appears in user code).
>   Auto-adds `org.apache.spark:spark-hive_2.13:<sparkVersion>` as
>   a `//> using dep`.  Hive configs sit between lakehouse configs
>   and the user `spark-config:` map so user overrides still win
>   (Spark builder is last-write).  9 new `SparkGenTest` cases pin
>   detection, dep emit, config ordering, escape semantics, and the
>   textual `.enableHiveSupport()` short-circuit.  Existing 141
>   `SparkGenTest` cases unchanged.
> - **G.3 — `@TempView("name")` annotation (landed 2026-05-20).**
>   Regex pass strips the annotation line and emits
>   `<varName>.createOrReplaceTempView("<viewName>")` after the
>   declaration.  Same shape as `@SqlFn` (Phase D) — both
>   annotations are sibling parsers anchored on a line-start
>   `@Marker`.  The per-block processing pipeline runs
>   `extractSqlFns` then `extractTempViews` on the cleaned source,
>   so a single block can carry both annotations side by side; the
>   emitted body appends UDF registrations first, then temp-view
>   registrations.  `TempViewSig(viewName, varName)` decouples the
>   SQL-side view name from the Scala-side var name.  Type
>   ascription (`val n: T = ...`) is supported via an optional
>   non-capturing regex group.  9 new SparkGenTest cases pin
>   detection, type-ascription form, composition with @SqlFn,
>   hyphen / underscore view names, order contract (registration
>   after val), decoupled name capture, helper round-trip, and the
>   no-@TempView regression guard.  Regex-tuning note: pulled `\s*`
>   out of the optional ascription group so Java's non-backtracking
>   regex engine doesn't eat the trailing space before `=`.
> - **G.4 — `Dataset.fromTable[T]("name")` typed reader.**  One-line
>   shim on the `Dataset` companion:
>   `spark.table(name).as[T]` using the Phase E encoder.  Symmetric
>   for Hive-managed tables (G.2) and temp views (G.3).  Lands with
>   `examples/spark-hive-demo.ssc` and an opt-in smoke test gated
>   by `RUN_SPARK_INTEGRATION=1 && RUN_SPARK_HIVE=1`.
> - **G.5 (optional) — Catalog introspection helpers.**
>   `Dataset.listTables()` / `Dataset.describeTable(name)` wraps.
>   Skip if any conflict surfaces; phase considered closed after G.4.

#### MLlib track — machine learning pipelines

> Goal: when a `.ssc` program imports `org.apache.spark.ml.*` (feature
> extractors, algorithms, `Pipeline`, model persistence), `SparkGen`
> auto-emits the `spark-mllib_2.13` runtime dep and the Phase E
> Scala 3 encoder shim gains support for the `org.apache.spark.ml.linalg.Vector`
> type (sealed trait, NOT a Product — Mirror-based derivation can't
> handle it).  Result: case classes with `features: Vector` fields,
> stock MLlib Pipelines, and `model.save()` / `PipelineModel.load()`
> all work end-to-end without any front-matter or CLI override.
> Detection is regex-driven on import-header substrings, same shape
> as the Streaming / Lakehouse tracks — purely additive over Phase E
> + F + Lakehouse L.2.
>
> Full plan: [`docs/spark-mllib.md`](docs/spark-mllib.md).
>
> Phases (each independently shippable per AGENTS.md rule 3):
>
> - **M.1 — Spec doc (landed 2026-05-20).**  `docs/spark-mllib.md`
>   covering goals / non-goals / detection mechanism / encoder bridge
>   design / coord table / phases M.2–M.5 / testing strategy / open
>   questions.  No code changes; gives the parallel Spark tracks a
>   stable contract to compose against.
> - **M.2 — Auto-emit `spark-mllib_2.13` dep (landed 2026-05-20).**
>   `containsMllib(source: String): Boolean` regex on
>   `\bimport\s+(?:org\.apache\.spark\.ml\.|o\.a\.s\.ml\.)`.  When
>   true, emits `//> using dep "org.apache.spark:spark-mllib_2.13:<sparkVersion>"`
>   right after the Kafka dep emit (with the existing `spark-core` /
>   `spark-sql` lines).  Verified `spark-mllib_2.13:4.0.0` exists on
>   Maven Central before merge.  Tests: 9 new `SparkGenTest` cases
>   covering positive detection (feature / classification / Pipeline /
>   linalg-only / `o.a.s.ml.` alias / grouped + wildcard imports),
>   negative detection (no MLlib import / `mllibConfig` variable name
>   doesn't match), the documented commented-out-import limitation,
>   and direct `containsMllib` helper assertions.
> - **M.3 — Vector encoder (landed 2026-05-20).**  Extends
>   `SscSparkEncoders` with an explicit
>   `aenc_MLVector: AgnosticEncoder[org.apache.spark.ml.linalg.Vector]`
>   given that wraps `UDTEncoder[MLVector](new VectorUDT(), classOf[VectorUDT])`.
>   Spark ML's `Vector` is a sealed trait (not a `Product`), so the
>   Mirror-based `aenc_Product[T <: Product]` derivation can't reach
>   it; the explicit given routes via Spark's own `VectorUDT`
>   user-defined type so the wire-level column shape matches what
>   every MLlib operator expects (a `VectorUDT.sqlType` struct, not
>   a Kryo blob).  Aliased to `MLVector` on import to avoid clashing
>   with the existing `aenc_Vector[E]` given for the Scala collection
>   `scala.collection.immutable.Vector`.  Gated on `usesMllib`: the
>   shim text is emitted only when MLlib is imported, so non-MLlib
>   programs don't reference the `VectorUDT` class (which lives in
>   the MLlib JAR and would fail to resolve otherwise).  Implementation
>   refactors `phaseEShim` from a `val` to `def(usesMllib: Boolean)`
>   splicing a `phaseEShimHead` + optional Vector block +
>   `phaseEShimTail` (containing the `aenc_Product` Mirror walk +
>   `derived[T]` Encoder given).  Tests: 4 new `SparkGenTest` cases
>   covering emit-when-imported, no-emit-without-import (gating
>   correctness), aliased-import coexistence with Scala collection
>   Vector, and source-ordering (`aenc_MLVector` slots between
>   collection encoders and `aenc_Product` so the Mirror walk picks
>   it up via `summonInline`).
> - **M.4 — Pipeline example end-to-end (landed 2026-05-20).**
>   `examples/spark-mllib-pipeline.ssc` — Tokenizer + HashingTF +
>   LogisticRegression on a tiny inline dataset (4 labelled docs,
>   binary classification).  Codegen test in `SparkGenTest` verifies
>   the dep + Vector encoder shim land for this example without
>   requiring the integration gate; smoke test in
>   `SparkRuntimeSmokeTest` invokes `scala-cli compile` against the
>   real `spark-mllib_2.13:4.0.0` JAR under
>   `RUN_SPARK_INTEGRATION=1 RUN_SPARK_MLLIB=1`.  Verified locally
>   under Scala 3.7.1 + Spark 4.0.0 + JDK 21 — generated source
>   resolves all deps via Coursier and type-checks cleanly.  During
>   M.4 development we discovered `VectorUDT` is `private[spark]` in
>   Spark 4.0.0; M.3's shim was updated to route through the public
>   `org.apache.spark.ml.linalg.SQLDataTypes.VectorType` singleton
>   (a `DataType`-typed instance that is actually a `VectorUDT` at
>   runtime) and recover the concrete `UserDefinedType[Vector]` via
>   cast.  Same wire-level interop with downstream MLlib operators
>   as a direct `new VectorUDT()` construction.
> - **M.5 — Model save/load (landed 2026-05-20).**
>   `examples/spark-mllib-model-save-load.ssc` — same pipeline shape
>   as M.4, plus `model.write.overwrite().save(path)` →
>   `PipelineModel.load(path)` round-trip with a prediction
>   equivalence check on the same training data.  Exercises Spark
>   ML's `MLWritable` / `MLReadable` traits (implemented by every
>   stock MLlib estimator/transformer) without any new SparkGen
>   surface — the save/load calls flow through unchanged because
>   PipelineModel + path Strings need no codegen support.  Codegen
>   test in `SparkGenTest` pins the API-surface preservation;
>   smoke test in `SparkRuntimeSmokeTest` invokes `scala-cli compile`
>   against the real `spark-mllib_2.13:4.0.0` JAR under
>   `RUN_SPARK_INTEGRATION=1 RUN_SPARK_MLLIB=1`.  Verified locally
>   under Scala 3.7.1 + Spark 4.0.0 + JDK 21 — generated source
>   resolves, type-checks, and preserves the `model.write.overwrite().save`
>   + `PipelineModel.load` calls bit-identically.  Documented
>   caveats in the example: save() writes a directory tree (not a
>   single file), cross-Spark-major load is MLlib's own concern,
>   custom non-stock components must implement `MLWritable` /
>   `MLReadable` themselves to participate.  M closes here for
>   stock-component scope.

### Why it fits

ScalaScript already has a local `Dataset[T]` implementation (v1.21) and
distributed MapReduce (v1.22).  Apache Spark is the industry-standard
distributed data processing framework — Scala-native, JVM-based.

The mapping is almost 1-to-1:

| ScalaScript | Spark |
|-------------|-------|
| `Dataset[T]` | `Dataset[T]` / `RDD[T]` |
| `.map(f)` | `.map(f)` |
| `.filter(p)` | `.filter(p)` |
| `.flatMap(f)` | `.flatMap(f)` |
| `.groupBy(key, value)` | `.groupByKey(key).mapValues(value)` |
| `.reduce(f)` | `.reduce(f)` |
| `.top(n)` | `.orderBy(...).limit(n)` |
| `.countByValue` | `.groupBy(...).count()` |
| `.join(other, on)` | `.join(other, on)` |
| `MapReduce.run(input, map, reduce)` | `rdd.flatMap(map).reduceByKey(reduce)` |

ScalaScript's type-checked, functional Dataset API becomes a high-level
typed DSL on top of Spark — with the same source running locally (interpreter
backend) or at scale (Spark backend).

### What it unlocks

```scalascript
---
name: word-count
backend: spark
---

# Word Count

```scalascript
val lines = Dataset.fromPath[String]("/data/books/*.txt")

val counts = lines
  .flatMap(line => line.split("\\s+").toList)
  .map(word => (word.toLowerCase, 1))
  .groupBy(_._1, _._2)
  .reduce(_ + _)
  .top(100)

counts.foreach { case (word, n) => println(s"$word: $n") }
```

This runs locally with `ssc run word-count.ssc` (interpreter, in-process)
and at scale with `ssc run --backend spark word-count.ssc` (Spark cluster).
Same source, same semantics, different scale.

### Implementation path

**Phase 1 — Local Spark session (~1 week): ✓ LANDED (2026-05-19)**

1. ✓ New `backend-spark/` sbt module — pure code-emitter, no Spark JARs on sbt
   classpath; Spark is resolved at runtime via `scala-cli --dep`.
2. ✓ `SparkGen.scala` — emits `SparkSession.builder().master("local[*]")` +
   `Dataset` companion shim + extension methods (`toList`, `top`, `takeOrdered`).
   Spark 4.0.0 default version (configurable via `--spark-version` flag or
   `spark-version:` front-matter key).
3. ✓ `ssc run --backend spark file.ssc` — generates Spark source, writes to
   `/tmp/ssc-spark-<hash>.scala`, runs via `scala-cli run --dep spark-*`.
4. ✓ `ssc emit-spark` command — emits generated Spark source to stdout or `-o file`.
5. ✓ `Dataset.fromPath[String](glob)` → `spark.read.textFile(glob).map(ev)`.
6. ✓ `examples/word-count.ssc` — example with `backend: spark` front-matter.
7. ✓ 21 unit tests in `SparkGenTest.scala` (no Spark runtime needed — structural
   source checks only).

**Phase 2 — Cluster submission (~1 week): ✓ LANDED (2026-05-19)**

5. ✓ `ssc submit file.ssc --spark-master spark://host:7077` packages the job
   as a fat JAR via `scala-cli --power package --assembly` and calls
   `spark-submit --master <url> --class runSparkJob <jar>`.
6. ✓ Support `--spark-master yarn` / `--spark-master k8s://...` — argv pinned
   in `SparkSubmit.submitCommand` tests across all four master URL shapes
   (`local[*]`, `spark://`, `yarn`, `k8s://`).
7. ✓ `--` separator after the file passes extra args through to `spark-submit`
   verbatim — `--executor-memory`, `--num-executors`, `--deploy-mode cluster`,
   etc.  Verified in `SubmitCommandTest`.
8. ✓ `--dry-run` prints the argv that would be invoked without shelling out;
   used by shell integration tests and useful for users inspecting what
   `ssc submit` is about to do.

**Phase 3 — Spark SQL and DataFrames (~1 week): ✓ LANDED (2026-05-19)**

7. ✓ Expose `DataFrame` as `Dataset[Row]` with a typed schema — `DataFrame`
   and `Row` widened into the emitted `sparkImports`, `_sqlBlock_<N>: DataFrame`
   bindings from sql blocks, typed `fromXAs[T : Encoder]` cousins of the
   readers return real `Dataset[T]`.
8. ✓ Case-class declarations map to Spark `StructType` via
   `Dataset.schemaOf[T : Encoder] = summon[Encoder[T]].schema`.  This
   subsumes the original "map `std/parsing` schemas" goal: case classes
   are the canonical schema declaration in Scala, and Spark's existing
   Encoder mechanism already derives the StructType from them — a custom
   parser-combinator → StructType layer would have been wasted work
   duplicating Spark's own derivation.
9. ✓ Inline SQL via `sql` fenced blocks — see Phase C.1 / C.2 above.
   String-interpolated `sql"..."` was the original sketch; the fenced-
   block path turned out cleaner (whole-block parameterisation via the
   shared `SqlBindRewriter`, alias-able by section).

### Key design decisions

**1. Same `Dataset[T]` API, different backend**

The user writes `Dataset[T]` code — the Spark backend compiles it to Spark,
the interpreter backend runs it in-process.  No user-visible difference.
Switching: change `backend: interpreter` to `backend: spark` in front-matter.

**2. Lazy evaluation**

Spark is lazy (transformations build a DAG, actions trigger execution).
ScalaScript's `Dataset` is also lazy by design — the existing implementation
already defers work until a terminal action (`.forEach`, `.reduce`, `.top`).
No semantic mismatch.

**3. Serialization**

Spark needs user types to be serializable (Kryo or Java serialization).
ScalaScript case classes compile to Scala 3 case classes — Spark can serialize
them automatically via `Encoder` derivation.  The backend emits implicit
`Encoder[T]` derivations for all `@state` types used in Dataset pipelines.

**4. Type safety**

ScalaScript's typer already type-checks `Dataset` operations.  The Spark
backend adds one more check: all types used in a distributed `Dataset` must
be serializable (no closures capturing non-serializable state).

**5. Local simulation for development**

`ssc run file.ssc` (no `--backend spark`) always uses the interpreter backend
with the local in-process `Dataset` implementation.  No Spark, no cluster, instant
feedback.  The Spark backend is only needed for production runs.

### Comparison

| | ScalaScript + Spark | Raw Spark (Scala) | PySpark | Flink |
|-|--------------------|--------------------|---------|-------|
| Type safety | ✓ (full) | ✓ (full) | ✗ (runtime) | ✓ |
| Local dev without cluster | ✓ | ✓ (local mode) | ✓ | ✓ |
| Same source local + cluster | ✓ | ✓ | ✓ | ✗ |
| Markdown-structured pipelines | ✓ | ✗ | ✗ | ✗ |
| Effect safety | ✓ | ✗ | ✗ | ✗ |
| Multi-backend (JS, JVM, Spark) | ✓ | ✗ | ✗ | ✗ |

### Prerequisites

- v1.24 language features (for cleaner DSL)
- Existing `Dataset[T]` + MapReduce API (already landed in v1.21–v1.22)
- JVM backend (already exists — Spark backend reuses its Scala 3 emission)

No new language features needed.  The Spark backend is a pure code-generation
addition on top of existing IR.

## v1.26 — `sql` fenced code blocks (JDBC)

**Status: open. Branch `worktree-v1.26-sql-jdbc`.**

Adds the `sql` block tag (§ 3.3 / § 3.3.1 of `SPEC.md`): parameterised
SQL executed via JDBC.  The hard design rule and entire safety story
in one sentence: **every `${expr}` becomes a single `?` bind parameter
— string substitution into SQL is not part of the language, period**.
A `sql` block can never produce a SQL injection regardless of what
the surrounding ScalaScript code does, because there is no syntax to
splice a `String` into SQL in the first place.

Decisions (resolved on the way in):
- Binding: `${expr}` → `?`, safe-by-default, no unsafe-splice escape.
- Connection source: YAML front-matter `databases:` by default;
  `given Connection` in scope overrides for tests / one-offs.
- Result type: `Seq[Row]` for SELECT-family, `Int` for DML/DDL.
  `row.as[CaseClass]` projects by field name at runtime.
- Drivers bundled in core: H2 + SQLite (both embedded, no network).
  Everything else (Postgres, MySQL, …) via `dep:` import.
- Target: JVM-only.  JS / Node / Wasm emit `UnknownBlockLanguage`;
  the source survives verbatim in the IR for future backends.

Parallel-safety note: v1.25 (`worktree-js-node-blocks`) edits the
same `core/.../ast/Lang.scala` and the same § 3.3 of `SPEC.md`.
Whichever lands second rebases on the first — the additions are
co-located but non-overlapping.

### Phase 1 — SPEC + milestone (this iteration)

- [x] `SPEC.md` § 3.3 table row + new § 3.3.1 (binding rule, result
      type, connection resolution, drivers, target support).
- [x] `MILESTONES.md` v1.26 entry (this section).

### Phase 2 — Front-end lang-tag recognition

Narrow to classification only — the dedicated IR node moves into
Phase 3 where it gains real content (the bind list).  Until then
`sql` blocks route through `ir.Content.EmbeddedBlock` identical to
the existing `node.js` path.

- [x] `core/.../ast/Lang.scala`: add `Sql = "sql"`, `isSql`,
      `isParameterizedExec`; extend `isOpaqueExec` to cover sql so
      capability gating in `validate/CapabilityCheck` works
      generically (no new code in CapabilityCheck needed).
- [x] Tests: `core/.../ast/LangTest.scala` (predicate pinning) +
      `core/.../parser/SqlBlockTest.scala` (lang preservation,
      Normalize → EmbeddedBlock, Normalize/Denormalize round-trip).

### Phase 3 — Dedicated IR node + `sql` → `SqlBlock` routing

The rewriter itself was landed earlier as cross-target infrastructure
by v1.25 § 9.5 Phase C.1 (parallel work): a single
`transform/SqlBindRewriter` with two placeholder modes —
`rewriteJdbc` (`?`) consumed by v1.26 and `rewriteSparkSql`
(`:bind<N>`) consumed by the Spark backend.  v1.26 Phase 3 is now the
JVM consumer of that shared rewriter plus the dedicated IR shape.

- [x] New IR case `ir.Content.SqlBlock(source, binds, dbName, span)`
      added to the `Content` enum.  `source` is the original SQL
      verbatim (round-trip surface for `Denormalize`); `binds` is
      the ordered list of bind-expression source texts produced by
      `SqlBindRewriter.rewriteJdbc`.  The `?`-form (JDBC template)
      is recomputed at execution time by rerunning the rewriter on
      `source` — keeps the IR small and avoids any literal-`?`
      ambiguity in round-trip.  `dbName` is `None` until Phase 5
      wires the `@db=name` block attribute.
- [x] `Normalize` routes `sql` blocks through
      `SqlBindRewriter.rewriteJdbc`; malformed sources
      (`RewriteError`) fall back to `EmbeddedBlock` so a single bad
      block doesn't crash the pipeline (capability check still
      surfaces `UnknownBlockLanguage`, execution layer surfaces the
      precise bind diagnostic).
- [x] `Denormalize` emits the preserved `source` field, reproducing
      `${expr}` / `$$` markers verbatim.
- [x] `validate/CapabilityCheck` recognises `ir.Content.SqlBlock`
      and produces `Diagnostic.UnknownBlockLanguage("sql")` on
      backends that don't declare `sql` in `blockLanguages`.
- [x] Tests: `SqlBlockTest` updated to assert the new IR shape
      end-to-end (Normalize produces `SqlBlock` with the right
      binds, Denormalize reproduces the source).
      `CapabilityCheckTest` covers sql-gating both directions
      (declared vs. not declared).  592 tests in `core/test` green
      (includes the 14 pinning tests for the shared
      `SqlBindRewriter` from v1.25 § 9.5 Phase C.1).

### Phase 4 — `backend-sql-runtime` module

New sbt module `backend-sql-runtime/` with no dependency on any
backend SPI — pure runtime library, callable from interpreter and
from JvmGen-emitted code.

- [x] `SqlRuntime.execute(conn, sql, binds): SqlResult`.
      `SqlResult = Rows(Seq[Row]) | UpdateCount(Int)`.
      Statement-type detection by leading keyword
      (`isResultSetProducer`: SELECT / WITH / VALUES / SHOW / EXPLAIN).
- [x] `Row` type: positional + name-indexed (case-insensitive),
      `row(i)`, `row(name)`, `row.toMap`, `inline row.as[T]` for case
      class projection via `Mirror.ProductOf`, by field name.  Per-
      field runtime coercion with named diagnostics
      (`RowProjectionError`) on missing column, NULL into non-Option,
      or type mismatch.
- [x] Bundled deps: `com.h2database % h2 % 2.2.224`,
      `org.xerial % sqlite-jdbc % 3.45.3.0`.  No `dependsOn` — module
      is standalone so both backend-interpreter and backend-jvm can
      pick it up later without circular deps.
- [x] JDBC binding: explicit dispatch on runtime type for primitives,
      `BigDecimal` / `BigInt`, `Array[Byte]`, `java.time.*`
      (`LocalDate` / `LocalTime` / `LocalDateTime` / `Instant` /
      `OffsetDateTime` / `ZonedDateTime`), `java.util.UUID`.
      `None` / `null` → `setNull(Types.NULL)`; `Some` unwraps
      recursively.  Time-typed binds use the typed
      `setObject(i, value, JDBCType)` form for driver portability.
- [x] Legacy `java.sql.{Date, Time, Timestamp}` ResultSet values are
      normalised to `java.time.*` at materialisation so user code
      sees one consistent vocabulary.
- [x] Tests in `backend-sql-runtime/src/test/...`: 16 cases against
      in-memory H2 + a bundled-driver smoke test on SQLite.  Covers
      every statement family, `null` / `None` binds, `Option[T]`
      projection, `.as[CaseClass]` happy + diagnostic paths,
      LocalDate / Instant round-trips, multi-row order preservation,
      name lookup case-insensitivity, `Row.toMap`.

### Phase 5 — Connection plumbing

- [x] `schemas/frontmatter.yaml`: `databases:` map — keys are
      connection names referenced by `@db=`, values carry
      `{url, user?, password?, driver?}`.  Strings may contain
      `${env:VAR}` references.
- [x] `ast.Manifest` / `ir.Manifest`: new `databases:
      List[DatabaseDecl]` field with default `Nil`.  Parser pulls
      entries out of the YAML map (missing `url` skips silently —
      runtime surfaces a precise diagnostic later).  Normalize +
      Denormalize forward the list.
- [x] `core/.../parser/Parser.scala`: fence-line attribute syntax
      `@key=value` (also accepts `@key="quoted value"`).  Keys
      lower-cased, values case-preserved.  Carried on
      `ast.Content.CodeBlock.attrs: Map[String, String]`.  General
      slot — `sql` uses it for `@db=` today; other tags can adopt
      it without an AST change.
- [x] `Normalize`: `sql` blocks read `attrs("db")` into
      `ir.Content.SqlBlock.dbName`; absent → `None`, registry
      default applies at execution time.
- [x] `backend-sql-runtime`: `EnvResolver.resolve(template,
      configKey, dbName, lookup)` expands `${env:NAME}` substrings
      at runtime (not at parse), raising `MissingEnv` with the
      variable / config field / db name on miss.
- [x] `backend-sql-runtime`: `ConnectionRegistry(specs, envLookup)`
      — lazy-open + cached `connect(name)`, identity on second
      call, `fresh(name)` for uncached opens, idempotent `close()`,
      `UnknownDatabase` lists available names on miss.
- [x] Tests: `DatabasesFrontmatterTest` (7 cases — YAML parsing,
      env-ref preservation, malformed-entry skip,
      Normalize/Denormalize round-trip).  `ConnectionRegistryTest`
      (16 cases — `EnvResolver` happy/error paths,
      regex-special-char escape, registry connect-and-cache,
      fresh-no-cache, close idempotency, post-close reopen,
      unknown-name diagnostic).  `SqlBlockTest` extended with
      `@db=name` parsing + key-lowercasing cases.

The `given Connection` / `given DataSource` override path is
implemented in Phase 6 alongside the interpreter wiring — the
registry already accepts pre-built `Connection`s through `fresh`,
so Phase 6 just routes the `given`-resolved connection straight
to `SqlRuntime.execute` and bypasses the registry.

### Phase 6 — Interpreter + JvmGen integration

#### Phase 6.A — Capability declarations + Denormalize round-trip (landed)

- [x] `JvmCapabilities` / `InterpreterCapabilities` declare
      `blockLanguages = Set(Lang.Sql)`.
- [x] `backend-jvm` and `backend-interpreter` `dependsOn(backendSqlRuntime)`.
- [x] `Denormalize` carries `ir.Content.SqlBlock.dbName` through
      `ast.Content.CodeBlock.attrs("db")` so consumers read the
      database selector through the same channel the parser
      populates it.

#### Phase 6.B — Interpreter executes sql blocks (landed)

- [x] `Value.Foreign(typeName, handle)` — opaque JVM-handle bridge.
- [x] `intrinsics/Jdbc.scala` — `DriverManager.getConnection` in
      both 1-arg and 3-arg overloads; returns
      `Foreign("Connection", conn)`.  `globals("DriverManager")`
      companion built in `initBuiltins`.
- [x] `Interpreter.run` materialises a per-module
      `ConnectionRegistry` from `manifest.databases` at module-init.
- [x] `Interpreter.runSection` dispatches `sql` blocks to a new
      `runSqlBlock` that:
      re-runs `SqlBindRewriter.rewriteJdbc` on `cb.source`, evals
      each bind expression in the current scope (`unwrapForJdbc`
      projects `Value` → JDBC `Any`), resolves the `Connection`
      via the override path (`Foreign("Connection", _)` bound to
      the `Connection` global) with the registry as fallback,
      calls `SqlRuntime.execute`, wraps the result (`Rows` →
      `ListV(MapV-per-row)`, `UpdateCount` → `IntV`), and binds it
      under both `<sectionId>.sql` and `_sqlBlock_<ordinal>`.
- [x] `SqlBlockInterpreterTest` (5 cases): registry-path DDL +
      INSERT + SELECT, dual surfacing, 1-arg + 3-arg override
      path, UPDATE returns affected-row count.

#### Phase 6.C — JvmGen codegen (landed)

- [x] `JvmGen.collectBlocks` recognises `ast.Content.CodeBlock` with
      `Lang.isSql`, increments a per-instance `sqlBlockCounter`, and
      emits a `JvmGen.Block` whose source is the Scala equivalent of
      the sql block — a `_sqlBlock_<N>: SqlResult = SqlRuntime
      .execute(_ssc_sql_resolve(<dbName>), "<?-templated>",
      List(<binds>))` expression with binds spliced as Scala source.
      First sql block per section also emits an `object <sectionId>:
      lazy val sql = _sqlBlock_<N>` alias (matches Spark Phase C.2 and
      the Interpreter's `globals(<sectionId>).sql` shape).
- [x] `emitSqlRegistry(databases)` materialises front-matter
      `databases:` entries as a `_ssc_sql_registry: ConnectionRegistry`
      constructed once at script entrypoint.  When the module has
      no `databases:`, the registry is `ConnectionRegistry.empty` so
      `given Connection` paths still work standalone.
- [x] `_ssc_sql_resolve(dbName: Option[String]): java.sql.Connection`
      helper uses Scala 3 `scala.compiletime.summonFrom` to prefer a
      `given java.sql.Connection` in scope; falls back to
      `_ssc_sql_registry.connect(dbName.getOrElse("default"))`.
- [x] `//> using dep` directives emitted only when sql blocks are
      present: `com.h2database:h2:2.2.224`,
      `org.xerial:sqlite-jdbc:3.45.3.0`, plus the
      `scalascript-backend-sql-runtime` library reference.
- [x] `Denormalize` round-trips `ir.Content.SqlBlock.dbName` through
      the existing `ast.Content.CodeBlock.attrs("db")` channel — same
      input shape JvmGen sees as the parser produces it.
- [x] Tests: `JvmGenSqlBlockTest` (14 cases — no-sql passthrough,
      `//> using dep` emission, registry materialisation with /
      without `databases:`, summonFrom helper, per-block emission
      with / without binds, sequential `_sqlBlock_<N>` numbering,
      `<sectionId>.sql` alias (first only, dedup on second),
      `@db=name` threading, `${env:NAME}` literal preservation).

v1.26.2 follow-up — **runtime smoke-test landed.**
`JvmGenSqlRuntimeTest` (2 cases) compiles + runs the JvmGen output
through `scala-cli` against an H2 in-memory database.  Worked around
the "no published artifact" problem by replacing the emitted
`//> using lib "io.scalascript::scalascript-backend-sql-runtime:…"`
directive with `//> using jar "<absolute-path>"` pointing at the
locally-built JAR.  The jar path is plumbed through a classpath
resource generated by `Test / resourceGenerators` in
`backendInterpreter`'s settings, which depends on
`backendSqlRuntime/Compile/packageBin` so it's always fresh.  Tests
auto-skip (via `assume(hasScalaCli, ...)`) when scala-cli isn't
available on PATH, so CI lanes without it stay green.

The `JsGen` / `NodeBackend` / `WasmBackend` `UnknownBlockLanguage`
diagnostic is **already wired generically** via
`validate/CapabilityCheck.unknownBlockLanguages` matching
`Lang.isOpaqueExec` (Phase 3 / 5).  An explicit end-to-end test for
each non-JVM backend is a Phase 7 conformance item.

### Phase 7 — Examples + conformance

- [x] `examples/sql-h2-quickstart.ssc`: zero-config H2 in-memory,
      DDL + DML + SELECT with bind params + section-aliased read.
- [x] `examples/sql-sqlite-file.ssc`: file-backed + in-memory
      SQLite, illustrates `@db=name` routing between two named
      connections in the same module.
- [x] Self-contained end-to-end test
      (`SqlExamplesTest`, 2 cases) that inlines the example sources
      verbatim and asserts they parse + execute under the
      interpreter against in-memory H2 and SQLite.  Catches
      regressions when parser / runtime changes silently break the
      documented usage shapes.

Phase 7 deferred items — all landed v1.26.2:

- [x] `conformance/sql-basic.ssc` + `conformance/expected/sql-basic.txt` +
      `SqlConformanceCaptureTest` (in-process scalatest harness that
      bypasses the bin/ssc + scala-cli + node toolchain `run.sc`
      requires, so `sbt test` enforces the regression).  Gated to
      `backends: [int]` — the JVM target's emitted code still
      references the unpublished `scalascript-backend-sql-runtime`
      artifact; the dedicated `JvmGenSqlRuntimeTest` covers the JVM
      path via a local-JAR override.
- [x] JS / Node / Wasm explicit `UnknownBlockLanguage` cases — added
      to `NodeBackendTest` and `WasmBackendTest` directly against
      each backend's real `Capabilities` instance (not a synthesised
      `Set.empty` stub).  Documents the dispatch path so a future
      backend that accidentally claims `sql` would fail loudly.
- [x] `docs/targets.md` block-language support matrix — new
      "Block Language Support" section with a per-block-lang × per-
      backend table (✅ / ❌), plus a v1.26-specific subsection
      explaining the dual rewriter (`rewriteJdbc` for JVM/Interpreter,
      `rewriteSparkSql` for Spark) and connection resolution.

JvmGen scala-cli runtime smoke-test landed earlier in v1.26.2 —
`JvmGenSqlRuntimeTest` rewrites the emitted `//> using lib` directive
to `//> using jar "<absolute-path>"` against the locally-built jar
plumbed through `Test / resourceGenerators`, so end-to-end coverage
exists without requiring the artifact to be published to Maven
Central.

### Follow-ups discovered during work

- **`client-postgres` reconciliation (landed v1.26.1).**  Originally
  `client-postgres` (commit `d45a250`) shipped with its own bind
  logic (poor subset of `Jdbc.bindOne`'s type matrix), and the
  transaction-path `withStmt` had a bug — `Some(x)` was passed
  through to `setObject` without recursive unwrap, and typed setters
  (`setString` / `setBoolean` / …) were skipped entirely.  Resolved
  via option (b) — `client-postgres` now `dependsOn(backendSqlRuntime)`
  and both client paths call the shared `scalascript.sql.Jdbc.bindAll`.
  Side effects of the consolidation:

  - Tx-path bind path matches the pooled path exactly (no behavioural
    diff between `client.execute(...)` and `client.transaction { tx
    => tx.execute(...) }`).
  - `ColumnDecoder` coverage expanded to match the bind side:
    `Short`, `Byte`, `Float`, `BigInt`, `java.time.{LocalDate,
    LocalTime, LocalDateTime, Instant, OffsetDateTime}`,
    `java.util.UUID`, `Array[Byte]`.  Legacy `java.sql.{Date, Time,
    Timestamp}` results auto-normalise to `java.time.*`.
  - Hand-written `Option[String|Int|Long|Double|BigDecimal]` givens
    replaced by a single generic `optionDecoder[A]` lift over any
    `ColumnDecoder[A]` — uses `rs.wasNull()` so primitive defaults
    (Int → 0, Boolean → false) correctly map to `None`.
  - `RowDecoder` single-column givens replaced by a generic
    `singleColumn[A]` lift over `ColumnDecoder[A]`; `queryOne[T]`
    works for every type `ColumnDecoder` supports.
  - `docs/postgres.md` rewritten to match the actual code (the
    previous version described a fictional `Async` / `AsyncStream`
    API that wasn't implemented).
  - `PgClientTest` extended with 7 cases pinning the new type
    coverage + tx-path consistency.  18 PgClient tests pass; both
    downstream consumers (`x402-nonce-postgres`,
    `x402-queue-postgres`) compile + test unchanged.

### Out of scope (deferred, not committed)

- Transactional API.  Phase 6 commits per statement (JDBC default).
  A `transaction { ... }` block-level helper is a follow-up — design
  it once the first real consumer surfaces.
- Static SQL type-checking (column types vs. case class fields at
  compile time).  Possible later via JDBC metadata at parse time,
  but adds a database round-trip to compilation — kept off until
  someone asks.
- Streaming results / cursor mode.  Phase 6 returns full `Seq[Row]`.
  Adding a `.stream` variant is mechanical; defer until a real
  large-result use case.
- Browser-side SQL (sql.js / DuckDB-Wasm).  Picked up in
  v1.27 — see [`docs/browser-sql.md`](docs/browser-sql.md).  As
  predicted, no IR / spec change needed; v1.27 is an additive
  capability declaration + a JS-side runtime module.

---

## v1.27 — browser-side SQL (sql.js / DuckDB-Wasm)

**Status: open. Spec [`docs/browser-sql.md`](docs/browser-sql.md). Branch `worktree-v1.27-browser-sql`.**

Extends the v1.26 `sql` fenced-block feature from JVM-only to the JS,
Node, and Wasm backends.  Same source, same `${expr} → ?` bind rule,
same `SqlBindRewriter.rewriteJdbc` output — only the runtime changes.
Two embedded engines, picked by URL prefix in the front-matter
`databases:` entry:

- `sqlite::memory:` / `sqlite:<path>` → sql.js (SQLite-WASM).
- `duckdb:` / `duckdb:<path>` → DuckDB-Wasm.
- `jdbc:*` URLs surface a build-time `UnsupportedJdbcUrl` diagnostic
  on JS / Node / Wasm targets (JVM target unaffected).

File-backed URLs work on Node; browser raises `MissingFs` at runtime
(parser cannot tell the two apart from front-matter alone — same
backend id for both).  Browser-side execution is always async by
construction; the emitted contract per block is `Promise[SqlResult]`
gated by a top-level `await` (or IIFE wrapper on legacy targets).

Parallel-safety note: no overlap with active worktrees.  Adds a new
`backend-sql-runtime-js` module + edits to the three JS-family
backend capabilities files (none of which other worktrees touch
today).

### Phase 1 — Spec + milestone (this iteration)

- [x] `docs/browser-sql.md` — goals, non-goals, architecture (module
      layout, URL→provider dispatch, runtime contract, override
      path), migration, 7 phases, testing strategy, 4 open
      questions.
- [x] `MILESTONES.md` v1.27 entry (this section).

### Phase 2 — `backend-sql-runtime-js` module ✓ Landed

- [x] New sbt module `backendSqlRuntimeJs`; `sql-runtime.mjs` shared
      facade (Connection / Row / Registry / execute), provider
      dispatch (`Providers.fromUrl`).
- [x] `SqlJsProvider` (sql.js wiring) + `DuckDbWasmProvider`
      (DuckDB-Wasm wiring).  Node uses `web-worker` (declared in
      the emitted `package.json`) over `node:worker_threads`; browser
      uses the JsDelivr default bundle.
- [x] `SqlRuntimeJsEmit` — codegen helper that loads the bundled
      `.mjs` source from the classpath and emits the bundle preamble
      (`ConnectionRegistry` init + `_ssc_sql_resolve(dbName)`
      override-or-registry dispatcher).  Shared across JsGen,
      NodeBackend, WasmBackend.
- [x] `ProviderId.fromUrl` — Scala-side mirror of the JS dispatch
      table; used by future Phase 4/5 backends to decide which npm
      deps to emit.
- [x] Tests:
      * 12 dispatch + enum-surface cases (`ProviderIdTest`).
      * 13 emit cases (`SqlRuntimeJsEmitTest`): resource load,
        registry-init JS shape for empty / single / full / multi
        entries, `${env:NAME}` preservation, jsString escapes, full
        preamble composition.
      * 16 Node `--test` cases under one Scala wrapper
        (`SqlRuntimeJsNodeTest`): sql.js (10 — CRUD, multi-row
        order, null binds, BLOB, boolean, Date, UPDATE count, PRAGMA,
        Row API, registry cache+reopen) + DuckDB-Wasm (6 — CRUD,
        GROUP BY, CTE/window, null binds, Row toMap, registry).
        Materialises into `target/sql-js-node-test/`, runs
        `npm install` once (mtime-stamped), then
        `node --test --test-force-exit *.test.mjs`.  Gracefully
        skips when `node` / `npm` aren't on PATH.

### Phase 3 — JsGen codegen for sql blocks ✓ Landed

Mirrors JvmGen Phase 6.C, adapted for async.

- [x] `JsCapabilities.blockLanguages += Lang.Sql`.  Generic
      `UnknownBlockLanguage("sql")` diagnostic no longer fires on the
      JS target.  NodeBackend / WasmBackend keep their old behaviour
      until Phase 4 / 5.
- [x] `JsGen.genSection` recognises `Lang.isSql`, emits
      `const _sqlBlock_<N> = await SqlRuntimeJs.execute(await
      _ssc_sql_resolve(<dbName>), <?-templated SQL>, [<binds>])`.
      First sql block per section also emits
      `if (typeof <sectionId> === 'undefined') var <sectionId> = {}; <sectionId>.sql = _sqlBlock_<N>`
      (matches the existing `genStringBlock` shape for `<sectionId>.html`
      / `.css`).
- [x] `_ssc_sql_registry` materialised from `manifest.databases`
      (shared `SqlRuntimeJsEmit.emitRegistryInit` from
      backend-sql-runtime-js); empty registry when the module has no
      front-matter `databases:`.  `${env:NAME}` markers in URL / user /
      password preserved verbatim — resolved at runtime by
      `sql-runtime.mjs`'s `resolveEnvRefs`.
- [x] `_ssc_sql_resolve(dbName)` checks `_ssc_sql_connections`
      (annotation override path, populated by future-Phase 6 codegen)
      first; falls back to `_ssc_sql_registry.connect(dbName ?? "default")`.
- [x] Bundle preamble — `sql-runtime.mjs` source inlined verbatim
      (with `export ` stripped so names land at script-level scope),
      followed by `const SqlRuntimeJs = { execute, ConnectionRegistry,
      ... }` namespace alias.  User body wrapped in
      `(async () => { ... })().catch(...)` — required for the per-
      block `await`s.  When the module also uses `runAsyncParallel`,
      the two flags collapse into one `needsAsync` decision so the
      IIFE wraps once.
- [x] `JsGen.bindExprToJs(exprSrc)` — parses each bind text back to
      `scala.meta.Term` and emits JS via the existing `genExpr`,
      so a bind like `${user.id + 1}` becomes the JS expression
      that evaluates in the surrounding scope.  Defensive fallback
      to verbatim source on parse failure.
- [x] `backend-js/build.sbt` now `dependsOn(backendSqlRuntimeJs)` —
      pulls in `SqlRuntimeJsEmit` for codegen + the bundled .mjs
      classpath resource.
- [x] Tests: `JsGenSqlBlockTest` (12 cases) — no-sql passthrough,
      preamble emission, `export ` stripping, async IIFE wrap,
      empty/populated registry, `${env:NAME}` preservation,
      per-block `_sqlBlock_<N>` emission with / without binds,
      sequential numbering, section alias (first-only, second
      doesn't redefine), `@db=name` threading, default fallback.
      All 12 green; full backend-interpreter suite (1228 tests)
      stays green.

### Phase 4 — NodeBackend wiring

- [ ] `NodeCapabilities.blockLanguages += Lang.Sql`.
- [ ] `NodeBackend` conditionally emits `package.json` deps for the
      providers actually referenced (`sql.js`, `@duckdb/duckdb-wasm`).
- [ ] Tests: `NodeBackendSqlTest` (4 cases — sqlite in-mem, sqlite
      file, duckdb in-mem, jdbc URL → diagnostic).
- [ ] Swap `NodeBackendTest`'s `UnknownBlockLanguage("sql")` case
      with no-diagnostic + new `UnsupportedJdbcUrl` case.

### Phase 5 — WasmBackend wiring ✓ Landed (2026-05-20)

- [x] `WasmCapabilities.blockLanguages = Set(Lang.Sql)`.
- [x] `WasmBackend.emitJsShim` — when sql blocks are present, the
      `Segmented` result gains three assets mirroring NodeBackend's
      package-json emit:
      * `Segment.Asset("sql-runtime.mjs", …)` — bundled JS runtime via
        `SqlRuntimeJsEmit.runtimeSource`.
      * `Segment.Asset("sql-registry.mjs", …)` — per-module registry
        init derived from `manifest.databases`.
      * `Segment.Asset("package.json", …)` — npm deps (`sql.js`,
        `@duckdb/duckdb-wasm`, `web-worker`) gated on referenced
        providers; ESM (`"type": "module"`) since the Wasm shim is
        itself an ES module.
      Wasm body itself unaffected; sql-only modules (no scala blocks)
      still emit the three sql assets.
- [x] `backend-wasm/build.sbt` — `dependsOn(backendSqlRuntimeJs)` to
      pull in `SqlRuntimeJsEmit` + `ProviderId`.
- [x] Tests: `WasmBackendSqlTest` (8 cases — no-sql passthrough, sqlite-
      only deps, duckdb-only deps, both, no-databases fallback, runtime
      verbatim, registry shape with named connections, empty-registry
      shape).
- [x] Swap `WasmBackendTest`'s `UnknownBlockLanguage("sql")` case to
      assert **no** diagnostic (matches NodeBackendTest's Phase-4 case).

Deferred to Phase 7: end-to-end runtime execution of sql blocks under
the Wasm shim (`SqlBrowserExamplesTest` / `SqlBrowserConformanceCaptureTest`).
The current Wasm bytecode doesn't have extern bindings to invoke
`SqlRuntimeJs.execute` from compiled Scala.js wasm; the asset bundle
ships ready to install, but wiring user-code sql blocks to those
assets needs additional Wasm-side codegen beyond Phase 5's scope.

### Phase 6 — `UnsupportedJdbcUrl` diagnostic

- [ ] `validate/CapabilityCheck` raises
      `Diagnostic.UnsupportedJdbcUrl(dbName, url, targetId)` when a
      target declares `Lang.Sql` in `blockLanguages` and a
      `manifest.databases` entry uses a `jdbc:` URL.
- [ ] Tests: `CapabilityCheckTest` — 2 cases.

### Phase 7 — Examples + conformance

- [ ] `examples/sql-browser-sqlite.ssc` + `examples/sql-browser-duckdb.ssc`
      tagged `backends: [js, node, wasm]`.
- [ ] `SqlBrowserExamplesTest` (self-contained, inlines example
      sources, asserts parse + run + expected output under Node).
- [ ] `conformance/sql-browser-basic.ssc` +
      `conformance/expected/sql-browser-basic.txt` +
      `SqlBrowserConformanceCaptureTest` gated to `backends: [js,
      node, wasm]`.
- [ ] `docs/targets.md` — block-language matrix ✅ for `sql` on JS /
      Node / Wasm; new v1.27 subsection documents URL-prefix
      dispatch + the jdbc-only-on-JVM rule.

### Out of scope (deferred to v1.28+ or beyond)

- Sync SQL — every browser engine is async; no `deasync` shims.
- Network DBs from browser — use `client-postgres` from a server
  backend, expose via HTTP.
- Cross-runtime data sharing — JVM-process in-memory data is not
  visible to JS-process runs of the same module.
- Static SQL type-checking — inherits v1.26's deferral.
- `transaction { ... }` block-level helper — inherits v1.26's
  deferral; when it lands, it lands once in
  `backend-sql-runtime` + `backend-sql-runtime-js` so both runtimes
  pick it up together.

---

## Infrastructure clients — general-purpose ScalaScript libraries

Specs in `docs/`: `postgres.md`, `kafka.md`, `evm.md`, `coinbase.md`, `redis.md`.

### `postgres` — PostgreSQL client (JDBC + HikariCP)

- [ ] `PgConfig` + HikariCP connection pool setup
- [ ] `PgClient`: `query[A]`, `queryOne[A]`, `execute`, `transaction`, `stream`, `close`
- [ ] `RowDecoder[A]` typeclass + `given` instances for primitives
- [ ] Auto-derive `RowDecoder` for case classes via Scala 3 Mirror
- [ ] Wrap JDBC calls in `Async.blocking`
- [ ] Tests against in-memory H2

### `kafka` — Kafka client (kafka-clients)

- [ ] `KafkaConfig`, `KafkaRecord`, `RecordMeta`
- [ ] `KafkaProducer`: `send`, `sendBytes`, `flush`, `close`
- [ ] `KafkaConsumer`: `subscribe`, `poll`, `commit`, `stream`, `close`
- [ ] Wrap kafka-clients in `Async`
- [ ] Tests with embedded Kafka (Testcontainers)

### `evm` — EVM / JSON-RPC client

- [ ] `EvmConfig` + `EvmNetworks` registry (Base, Ethereum, Polygon, Arbitrum, Optimism)
- [ ] `EvmClient`: `blockNumber`, `getBalance`, `erc20Balance`, `erc20Allowance`
- [ ] Transaction queries: `getTransaction`, `getReceipt`, `waitForReceipt`
- [ ] `call` (eth_call) + raw `rpc` escape hatch
- [ ] Implemented over HTTP JSON-RPC (no external Web3 library)
- [ ] Tests against a local Anvil / Hardhat node

### `coinbase` — Coinbase API client

- [ ] `CoinbaseConfig` + JWT/HMAC auth
- [ ] `CoinbaseTrade`: products, candles, accounts, orders
- [ ] `CoinbaseCdp`: wallet create/get, transfer, list balances
- [ ] `CoinbaseFacilitator`: `verify`, `settle` (x402 facilitator API)
- [ ] Tests with mocked HTTP

### `redis` — Redis client (Lettuce)

- [ ] `RedisConfig` + Lettuce async connection pool
- [ ] Strings: `get`, `set` (+ TTL), `setNx`, `del`, `exists`, `expire`, `incr`
- [ ] Hashes: `hget`, `hset`, `hgetAll`, `hdel`
- [ ] Lists: `lpush`, `rpush`, `lpop`, `rpop`, `lrange`
- [ ] Sets: `sadd`, `srem`, `smembers`, `sismember`
- [ ] Sorted sets: `zadd`, `zrange`, `zscore`, `zrank`, `zrem`
- [ ] Pub/Sub: `publish`, `subscribe` → `AsyncStream[PubSubMessage]`
- [ ] Transactions / pipelining: `transaction[A]`
- [ ] Key ops: `keys`, `scan`, `flushDb`
- [ ] Tests against embedded Redis (Testcontainers)

---

## x402 — HTTP payment protocol

Spec in `docs/x402.md`.

### Phase 1 — Core (`x402-core`) ✓ Landed

- [x] `PaymentScheme`: `Exact`, `Stream`, `CardanoExact`
- [x] `PaymentRequirements`, `TransferAuthorization`, `PaymentPayload`
- [x] `CardanoAsset`, `CardanoPaymentProof`
- [x] `Network`, `Asset`, `Assets` registry
- [x] `Facilitator` trait + `VerifyResult` / `SettleResult`
- [x] `NonceStore` trait + in-memory implementation
- [x] `SettlementMode`: `Synchronous` / `Async(queue)`
- [x] `SettlementQueue` trait + in-memory implementation

### Phase 2 — Server middleware (`x402-server`) ✓ Landed

- [x] `PaymentConfig` + `withPayment(config) { routes }` DSL
- [x] 402 response with `requirements` JSON body
- [x] `X-Payment` header parsing + base64 decode
- [x] Nonce claim before facilitator call (double-spend guard)
- [x] Sync settlement path (verify + settle in request)
- [x] Async settlement path (verify in request, enqueue settle)
- [x] `onSettled` callback hook
- [x] Tests: no-payment → 402, valid payment → 200, replay → 402

### Phase 3 — Client interceptor (`x402-client`) ✓ Landed

- [x] `Wallet` trait + `Eip712Domain`
- [ ] `Wallets.metaMask()` (browser / window.ethereum) — deferred to JS backend
- [x] `Wallets.privateKey(hex, network)` + `Wallets.envKey(envVar, network)`
- [x] `X402Client(wallet, maxAmount, backend)` interceptor
- [x] Auto-retry on 402: parse requirements, sign, add `X-Payment`, retry
- [x] Refuse if `maxAmountRequired > maxAmount`
- [x] Tests: 402 → sign → 200 round-trip (mocked server)

### Phase 4 — EVM facilitators ✓ Landed

- [x] `x402-facilitator-coinbase`: delegates to `CoinbaseClient.x402`
- [x] `x402-facilitator-evm`: balance-check verify via `EvmClient` + pluggable settler
- [x] `Facilitators.withFallback(primary, fallback)` — in x402-core
- [x] `Facilitators.testnet()` — always Ok, no real settlement — in x402-core
- [x] Tests: verify Ok / Fail paths, settlement happy path

### Phase 5 — Durable queues and nonce stores ✓ Landed

- [x] `x402-queue-kafka`: `SettlementQueue` via `KafkaProducer` (enqueue); drain is application-side
- [x] `x402-queue-postgres`: `SettlementQueue` backed by `PgClient` (enqueue + process)
- [x] `x402-nonce-postgres`: `NonceStore` backed by `PgClient` (`ON CONFLICT DO NOTHING`)
- [x] `x402-nonce-redis`: `NonceStore` backed by `RedisClient` (`setNx` with TTL)

### Phase 6 — Cardano facilitator (`x402-facilitator-cardano`) ✓ Landed

- [x] `CardanoFacilitatorConfig` + `CardanoProvider` enum (Blockfrost, Scalus)
- [x] `CardanoProvider.Blockfrost`: balance check via Blockfrost API + CIP-8 verify
- [ ] `CardanoProvider.Scalus`: server-side Tx building via Scalus + cardano-client-lib (bloxbean)
- [x] CIP-8 signature verification (COSE_Sign1 + COSE_Key, Ed25519 via BouncyCastle)
- [x] Settlement: Blockfrost path — optimistic Ok after verify; Scalus — stub Fail
- [x] Tests: MiniCbor round-trips, CIP-8 verify, balance check, native assets, settlement

### Phase 7 — Stream scheme (metered billing) ✓ Landed

- [x] `PaymentScheme.Stream`: rate-per-unit, maxUnits, maxAmount
- [x] Server: validate `authorization.value == ratePerUnit * X-Units`; `withStreamPayment` wrapper
- [x] Client: authorizes `ratePerUnit` per request; session budget tracking; exhaustion → 402
- [x] Tests: unit counting, multi-unit, budget exhaustion, ratePerUnit > maxAmount guard

### Phase 8 — Test mode + examples ✓ Landed

- [x] `X402.testConfig(payTo)` — auto BaseSepolia + testnet facilitator
- [x] `X402.isTestMode` from `X402_ENV` env var
- [x] `examples/x402-server.ssc` — payment-gated REST endpoint
- [x] `examples/x402-client.ssc` — client auto-handles 402
- [x] `examples/x402-cardano.ssc` — Cardano payment flow (2026-05-20)

### Phase 9 — Cardano client-side wallet ✓ Landed (2026-05-20)

Closes the asymmetry between the Cardano facilitator (verifies CIP-8)
and the x402 client (previously EVM-only). `Wallets.cardano(hex,
address, network)` produces a CIP-8 / COSE_Sign1 proof via an Ed25519
`RawSigner`; `PayloadBuilder.build` branches on `CardanoExact` and
emits `cardanoProof` in the encoded payload. `MiniCbor` moved from
`x402-facilitator-cardano` to `x402-core` so the signer can share it
with the verifier. `Network` enum gained `CardanoMainnet` /
`CardanoPreprod` / `CardanoPreview`. The `Wallet` trait now also
declares `signCip8`; EVM and Cardano wallets reject the wrong shape.

- [x] `x402-core/MiniCbor` — moved from facilitator, now shared
- [x] `Network.CardanoMainnet/Preprod/Preview`; `Network.isCardano`
- [x] `x402-client/Cip8Signer` — COSE_Sign1 + COSE_Key assembly
- [x] `CardanoPrivateKeyWallet` via `RawPrivateKeyVault(Ed25519)`
- [x] `Wallets.cardano` / `Wallets.cardanoEnvKey` factories
- [x] `PayloadBuilder.buildCardano` + `encode` cardanoProof field
- [x] Server `parsePayload` parses Cardano network names
- [x] `CardanoPayloadTest` — 5 tests round-trip-verify the proof with
      BouncyCastle Ed25519; signer / payload shape / dual-wallet reject
- [x] `CardanoFacilitatorTest` mocks updated for `getUtxos`/`submitTx`
      (pre-existing breakage from blockchain-cardano Phase 6)
- [x] CIP-19 enterprise address derivation from key (2026-05-20) —
      `Wallets.cardano(hex, network)` now derives `addr1` / `addr_test1`
      via `blockchain-cardano.CardanoAddress.fromPublicKey`; the
      `(hex, address, network)` form remains for stake-aware base
      addresses; example dropped its `CARDANO_ADDR` env var
- [x] Base addresses with staking (2026-05-20) —
      `CardanoAddress.fromPublicKeys(payment, stake, testnet)` builds
      CIP-19 type-0 base addresses (`header || Blake2b-224(payment) ||
      Blake2b-224(stake)`); `Wallets.cardanoBase(paymentHex, stakeHex,
      network)` + `cardanoBaseEnvKey` factories. Signing still uses
      only the payment key — stake key participates in the address but
      never signs payments. `CardanoAddress.Kind` exposed for caller
      sanity checks (Base / Enterprise / Reward / Pointer / Script).
- [→] `CardanoProvider.Scalus` settlement → see Phase 10 below

### Phase 10 — Scalus / Plutus-escrow settlement

Spec in [`docs/x402-cardano-scalus.md`](docs/x402-cardano-scalus.md).
Replaces the optimistic `CardanoProvider.Blockfrost` `Ok` with on-chain
Plutus-enforced escrow: payer locks lovelace at a script address, the
facilitator's relayer claims via a Tx whose redeemer carries the CIP-8
proof. Validator written in Scalus DSL; off-chain Tx building via
bloxbean `cardano-client-lib`.

#### Phase 1 — Spec + module scaffolding ✓ Landed (2026-05-20)

- [x] `docs/x402-cardano-scalus.md` — goals, escrow datum/redeemer
      shape, off-chain flow, 6-phase plan
- [x] New module `x402-facilitator-cardano-scalus` (build.sbt entry,
      depends on `x402Core` + `x402FacilitatorCardano`)
- [x] `ScalusSettler` trait + `ScalusSettler.unimplemented` stub +
      `asConfigHook` function adapter
- [x] `CardanoFacilitatorConfig.scalusSettle: Option[(payload, req)
      => Future[SettleResult]]` — pluggable hook on the existing
      facilitator. Default behavior unchanged (Scalus path still
      returns `Fail` with hint pointing at the new wiring).
- [x] 5 tests: stub Fail, hook delegation, Blockfrost-path
      regression, end-to-end settle delegation
- [x] All 17 existing Cardano facilitator tests still green

#### Phase 2 — On-chain validator (Scalus)

**Spike landed (2026-05-20)**: package rename + spec update with
blocker analysis. Validator code itself NOT yet landed — six concrete
issues documented in [`docs/x402-cardano-scalus.md`](docs/x402-cardano-scalus.md)
§5 (Phase 2 → Spike findings). Retry order:

- [x] Package rename `scalascript.x402.facilitator.scalus` →
      `scalascript.x402.facilitator.plutus` to avoid shadowing the
      Scalus library's top-level `scalus` package
- [x] Spec §5 expanded with: package collision, upickle 3↔4 eviction
      conflict, Scala 3.3.7→3.8.3 version drift, `Validator` trait's
      five deferred-inline purposes (need `ParameterizedValidator`),
      top-level vs nested derivation, doc-vs-jar import drift
- [x] **Prerequisite**: project-wide upickle 3.3.1 → 4.4.2 bump
      across ~21 modules + sttp.client4 4.0.0-M17 → 4.0.23 (commit
      `b736c5a6`). All ~120 affected tests stay green.
- [x] Re-add `org.scalus:scalus:0.15.1` dependency
- [x] `X402EscrowScript` — single-purpose Plutus V3 validator drafted
      as a plain `@Compile object … inline def validate(scData: Data)`
      (avoids the six deferred-inline purposes of bare `Validator`).
      Dispatches on `ScriptInfo.SpendingScript`, fails other purposes.
- [x] `EscrowDatum` + `EscrowRedeemer` (Claim/Refund) at top level
      with `derives FromData, ToData` — Scalus 0.15.1 only derives on
      top-level types, not nested
- [x] Structural checks (signatory presence for Claim / Refund)
      enforced; full CIP-8 inline verification deferred to Phase 2.5
- [→] **Blocked on Scalus Scala-3.8 support.** `PlutusV3.compile(...)`
      requires the `scalus-plugin` compiler plugin (latest 0.16.0),
      which was built against Scala 3.3.7 and references
      `dotty.tools.dotc.core.Names$Designator` — removed in Scala
      3.8.x. Plugin load fails with `NoClassDefFoundError`. Without
      the plugin enabled, `PlutusV3.compile` throws at runtime.
      See spec §5 "Phase 2 retry" for the three options
      (wait for Scalus 3.8 / separate sub-build / project Scala
      downgrade); all three are out of Phase 2 scope.
- [x] 4-test sanity suite for `X402EscrowScriptTest`: datum
      construction, redeemer shapes, **and a pinned test that
      asserts the missing-plugin RuntimeException** — flips the day
      a compatible plugin lands
- [ ] On-chain CIP-8 verification: COSE_Sign1 decode + Ed25519 verify
      against datum.payerKeyHash; payload-hash equality check
      (Phase 2.5, after plugin)
- [ ] Output-shape check: exact lovelace to datum.receiver
      (Phase 2.5, after plugin)
- [ ] Validity-range check vs `datum.validBefore` / `datum.refundAfter`
      (Phase 2.5, after plugin)
- [ ] Unit tests via Scalus's script-context simulator
      (Phase 2.5, after plugin)

#### Phase 3 — Escrow address + reference script

- [ ] `EscrowScript.address(network)` — compiled-validator address
- [ ] Reference-script deploy helper (one-time op)
- [ ] Golden-bech32 test for stable script address per network

#### Phase 4 — Off-chain claim Tx via bloxbean

- [ ] Add `com.bloxbean.cardano:cardano-client-lib` dependency
- [ ] `ScalusSettler.preprod(cfg)` / `.mainnet(cfg)` factories
- [ ] Tx building: input = escrow UTxO ref, output = receiver +
      amount, redeemer = CIP-8 proof bytes, witness = relayer key
- [ ] Submission via Blockfrost `submitTx` (Ogmios as Phase-5+ option)
- [ ] Integration tests against Preprod (CI-gated by env vars)

#### Phase 5 — Client-side Scalus-mode wallet

- [ ] `Wallets.cardano(hex, network, scalusMode = true)`
- [ ] Structured `ScalusClaimMessage` (domain-separated:
      receiver|amount|validBefore) replaces the description-bytes
      payload for Scalus payments
- [ ] `escrowRef` propagated through the payload `nonce` slot
- [ ] Round-trip test covering client → validator → claim Tx

#### Phase 6 — Deposit ergonomics + example

- [ ] `EscrowDeposit.build(payerWallet, req)` helper
- [ ] `examples/x402-cardano-scalus.ssc` — full Preprod walkthrough
- [ ] Update Phase 9 follow-up to point here for production flows

---

## Blockchain SPI — chain abstraction for x402 + wallet

Spec in [`docs/blockchain-spi.md`](docs/blockchain-spi.md). Defines a
shared chain-abstraction layer (`ChainAdapter` / `ChainId` / `Asset`
/ `TypedData` / `recover` / queries) consumed by both `wallet-*` and
`x402-*`. Sits above a lower-level `crypto-spi` (BouncyCastle on JVM,
`@noble/curves` on Scala.js).

Fixes four concrete bugs in current x402:

- `EvmFacilitator.verify` never checks the signature
  (`x402-facilitator-evm/.../EvmFacilitator.scala:23-38`)
- `EvmFacilitator.settle` returns `0x00…00` as stub tx hash
  (`:40-43`)
- Hand-coded `0x70a08231` selector for `balanceOf` (`:32`)
- x402-client SHA-256 stubs (companion fix in
  [`docs/wallet-spi.md`](docs/wallet-spi.md))

### Phase 0 — Spec ✓ Landed (2026-05-19)

- [x] `docs/blockchain-spi.md` — chain abstraction, EVM facilitator
      fix path, x402 per-chain migration table
- [x] `docs/wallet-spi.md` — refactored to depend on blockchain-spi
- [x] `AGENTS.md` — spec-driven development workflow
- [x] `MILESTONES.md` — this entry

### Phase 1 — SPI + crypto + blockchain-evm minimum + x402 facilitator verify fix ✓ Landed (2026-05-19)

- [x] `crypto-spi` — `CryptoBackend` trait + registry (JVM
      ServiceLoader + explicit register for future Scala.js)
- [x] `crypto-bouncycastle` — JVM default impl (secp256k1 incl.
      ecrecover, ed25519, p256, keccak256, sha2, ripemd160, hmac,
      hkdf, pbkdf2, argon2id, AES-GCM, BIP-32 / SLIP-0010)
- [x] `blockchain-spi` — `ChainAdapter` / `ChainId` (CAIP-2) /
      `AccountId` (CAIP-10) / `Asset` / `TypedData` / `TxIntent`
      (incl. `Deploy`) / `Blockchain.register/lookup`
- [x] `blockchain-evm` read-side — `addressFromPublicKey` (keccak +
      last 20 + EIP-55), `typedDataDigest` (full EIP-712 with nested
      structs), `recoverAddress` (handles v ∈ {0,1,27,28}),
      `tokenBalance` (ERC-20 via `balanceOf`), generic `call` for
      `eth_call`. `buildTransaction` / `broadcast` deferred to
      Phase 2. `Eip3009.usdcTransferWithAuthorization` helper for
      x402's typed-data shape.
- [x] x402 verify fix (covers Base / BaseSepolia / Ethereum /
      Polygon / Arbitrum / Optimism — one adapter, six chain ids):
  - [x] `EvmFacilitator.verify` calls
        `blockchain-evm.recoverAddress` and rejects mismatched
        signatures with descriptive Fail messages
  - [x] `EvmFacilitatorTest` gains "tampered signature → Fail" and
        "signature signed by a different key → Fail" cases (the
        bug-fix this slice closes)
  - [ ] `EvmFacilitator.tokenBalance` to use blockchain-evm typed
        proxy — deferred to Phase 2 (depends on full ABI codec)
- [x] x402-client shim: `PrivateKeyWallet` now wires
      `RawPrivateKeyVault` + `EoaStrategy` + `EvmChainAdapter` +
      `Eip3009` helper; public API (`Wallet`, `Wallets.privateKey`,
      `Wallets.envKey`) stable; existing 17 tests stay green with
      fixture addresses updated to valid 20-byte hex.
- [x] `wallet-spi` + `wallet-strategy-eoa`: `RawSigner` / `Vault` /
      `AccountStrategy` / `DappConnector` / `AccountManager` traits;
      `EoaStrategy` impl; `RawPrivateKeyVault` test helper.
- [x] Vector tests: RFC-6979 ECDSA + Ed25519 RFC 8032 #1 + EIP-712
      Mail reference + BIP-32 appendix C #1 + SLIP-0010 #1 +
      EIP-55 checksum + RFC 6070 PBKDF2 + AES-GCM tamper detection.
      67 tests across the five new modules.

### Phase 2 — blockchain-evm full ChainAdapter + real x402 settle ✓ Landed (2026-05-19)

Shipped as four slices: RLP+broadcast (29344e6), ABI codec
(3679e68), typed Erc20 proxy + event decoder (a97e7e6), real
relayer-backed x402 settle (cbec71c). ~40 new tests, full Phase 1
regression test green.

- [x] `Rlp` encoder (Yellow Paper appendix B): single-byte, short /
      long strings, lists, length-of-length headers.
- [x] `EvmTx` / `EvmSignedTx` — EIP-1559 (type 0x02) envelopes with
      RLP body, sighash, signed raw-hex serialisation. Legacy tx is
      not implemented; every EVM chain x402 currently targets
      supports EIP-1559.
- [x] `EvmChainAdapter` write-side:
  - [x] `buildTransaction(NativeTransfer / TokenTransfer /
        ContractCall / TokenTransferAuthorized / Deploy(CREATE))`
        queries nonce + estimates gas (eth_estimateGas + 10% margin)
        + reads fee market (eth_maxPriorityFeePerGas with
        eth_gasPrice fallback, base fee from latest block).
  - [x] `prepareSigningPayload` / `assembleSignedTransaction`
        normalising v ∈ {0,1,27,28} → yParity.
  - [x] `broadcast` via `eth_sendRawTransaction`.
  - [x] `waitForReceipt` polling with deadline.
  - [x] `predictDeployAddress` for CREATE (
        keccak256(rlp([sender,nonce]))[12..32]). CREATE2 deferred —
        needs a deployer factory contract.
- [x] `ChainAdapter.buildTransaction` SPI gained an explicit
      `sender: String` parameter (needed for nonce + gas estimation).
- [x] `blockchain-evm-abi` sub-module — pure-Scala Solidity ABI v2
      codec: encode/decode for uint*/int*/address/bool/bytesN/
      bytes/string/T[]/T[k]/tuple, function selector helper, event
      topic0 helper. Vector tests against published reference
      encodings (ERC-20 transfer calldata byte-identical, ERC-3009
      selector 0xe3ee160e, head/tail layout for mixed
      static/dynamic tuples).
- [x] `Erc20` typed proxy in `blockchain-evm` — typed reads
      (balanceOf / allowance / decimals / symbol / name) and write
      intents (transfer / approve / transferWithAuthorization).
- [x] `Erc20.Transfer` / `Approval` event decoders. `topic0` for
      Transfer matches the canonical
      0xddf252ad… hash.
- [x] `blockchain-spi.TxReceipt` gained a `logs: Seq[Log]` field
      (default empty, additive); `EvmChainAdapter.getReceipt` parses
      the JSON-RPC logs array into typed `Log` triples.
- [x] x402 settle fix (covers all 6 EVM chains):
  - [x] `EvmFacilitatorConfig.relayerKeyHex` — relayer wallet for
        on-chain settlement
  - [x] `EvmFacilitator.withRelayer(evm, key)` — convenience factory
  - [x] `settleOnChain` path: builds via `Erc20.transferWithAuthorization`,
        signs with EoaStrategy, broadcasts via EvmChainAdapter
  - [x] Custom `settler` escape hatch retained
  - [x] Backwards-compatible default: no relayer + no settler →
        Ok(0x000…000) stub (kept for testnet examples)
  - [ ] End-to-end Anvil integration test deferred — mock-RPC test
        exercises the exact JSON-RPC sequence an Anvil node would
        receive; real network round-trip is a follow-on slice.

### Phase 3 — blockchain-solana ✓ Landed (2026-05-20)

- [x] `blockchain-solana` — Ed25519, Base58 addresses, SLIP-0010,
      versioned transactions (v0 + legacy), PDA derivation, SPL token support
- [x] 43 tests — address derivation, tx building, SPL TransferChecked, PDA, balances

### Phase 4 — Scala.js CryptoBackend ✓ Landed (2026-05-20)

- [x] `crypto-noble-js` — facade over `@noble/curves` +
      `@noble/hashes` (`@noble/ciphers` deferred to Stage 5 along with
      the encrypted-vault SubtleCrypto adapter)
- [x] Cross-backend conformance: bit-identical outputs on JVM vs JS
      for deterministic algorithms — see `CrossPlatformFixturesTest`
      in `crypto-bouncycastle/src/test/` vs `NobleCryptoBackendTest`
      in `crypto-noble-js/src/test/`
- [x] Resolves Scala.js registry-pattern open question (both SPIs).
      Full per-stage breakdown lives in
      `## Wallet SPI — Scala.js cross-compile / Stage 2` further down
      this file.

### Phase 5 — blockchain-bitcoin

- [ ] `blockchain-bitcoin` — secp256k1 with sighash variants
      (SIGHASH_ALL/NONE/SINGLE + ANYONECANPAY; SegWit BIP-143;
      Taproot BIP-341)
- [ ] P2WPKH bech32 addresses
- [ ] PSBT (BIP-174) for hardware-wallet compatibility

### Phase 6 — blockchain-cardano + x402 Cardano facilitator ✓ Landed (2026-05-20)

- [x] `blockchain-cardano` — Bech32 codec, CIP-19 enterprise addresses (Blake2b-224),
      CBOR encoder+decoder, CIP-8 COSE_Sign1 signing/verify, CardanoChainAdapter
      (`ChainAdapter` impl), CardanoTxBody CBOR builder, Blockfrost UTxO + submit
- [x] `BlockfrostClient` extended with `getUtxos()` and `submitTx()` (`BlockfrostUtxo` type)
- [x] `ChainId.CardanoMainnet` / `ChainId.CardanoPreprod` added to `blockchain-spi`
- [x] 19 tests — address derivation, balances, tx building, signing, CBOR round-trips, Bech32
- [ ] `x402-facilitator-cardano` thin-glue refactor (deferred — existing impl works)
- [ ] `examples/x402-cardano.ssc` (deferred)

### Phase 7 — blockchain-cosmos

- [ ] `blockchain-cosmos` — secp256k1 / ed25519, sign_doc, bech32
      prefixes, configurable per family (Osmosis / Juno /
      cosmoshub-4 / …)

---

## Wallet SPI — Scala.js cross-compile

Spec in [`docs/wallet-spi-scalajs.md`](docs/wallet-spi-scalajs.md).
Six-stage migration that takes the wallet-spi track from JVM-only to
JVM + Scala.js so the same SPI artefacts power browser PWA wallets,
in-page dApp connectors (EIP-1193 / WalletConnect / Solana Wallet
Standard), and the x402 client in a browser context. Builds on the
existing wallet-spi (§ "Wallet SPI — key management + dApp
connectivity") which lands the JVM side first.

### Stage 1 — Plugin setup + cross-compile wallet-spi ✓ Landed (2026-05-20)

- [x] `project/plugins.sbt` — `sbt-scalajs` 1.20.2,
      `sbt-crossproject` 1.3.2, `sbt-scalajs-crossproject` 1.3.2.
- [x] `crypto-spi` cross-compile (`CrossType.Full`) — shared traits
      / value classes; JVM-only `CryptoBackendDiscovery`
      (ServiceLoader); JS-side `CryptoBackendDiscovery` no-op
      (explicit registration only). Companion `object CryptoBackend`
      lives in shared; cross-platform `register` / `all` / `get`
      surface preserved.
- [x] `blockchain-spi` cross-compile — same pattern: shared traits
      and `object Blockchain`, platform-specific
      `BlockchainDiscovery`.
- [x] `wallet-spi` cross-compile — pure SPI traits in `shared/`;
      `jvm/` and `js/` source dirs empty placeholders. All four
      source files (`RawSigner` / `Vault` / `AccountStrategy` /
      `DappConnector`) physically moved from `src/main/scala/` to
      `shared/src/main/scala/`.
- [x] `CrossCompileSmokeTest` in `wallet-spi/shared/src/test/` —
      8 specs exercising `Curve` / `HashAlgo` / `PublicKey` /
      `ChainId` / `VaultKind` / `UnlockCredential` /
      `AccountDescriptor` round-trips. Runs on JVM and Node.js.
- [x] Build: `sbt walletSpi/test walletSpiJs/test sbt compile`
      all green. No regressions to downstream JVM modules
      (`x402-client`, `walletStrategyEoa`, `mcpWallet`,
      `walletVaultEncrypted`, `walletVaultLedger*`,
      `walletConnect`, `walletConnectorEip1193`, etc. all stay
      compiling and passing tests).

### Stage 2 — Scala.js CryptoBackend (crypto-noble-js) ✓ Landed (2026-05-20)

Resolves the `Scala.js registry pattern` open question
([`docs/wallet-spi.md`](docs/wallet-spi.md) §11.1) — first impl module
that registers itself through the Stage 1 cross-platform
`object CryptoBackend.register(...)`.

- [x] `crypto-noble-js` — Scala.js-only sbt project
      (`enablePlugins(ScalaJSPlugin)`, `.dependsOn(cryptoSpiJs)`).
      `ModuleKind.CommonJSModule` so noble v1.x's CJS exports
      resolve at link time.
- [x] `NobleFacades.scala` — `@JSImport` bindings for
      `@noble/curves/{secp256k1, ed25519, p256}` (sign / verify /
      getPublicKey / Signature.fromCompact + recoverPublicKey) and
      `@noble/hashes/{sha256, sha512, sha3.keccak_256, ripemd160,
      hmac, hkdf}`.
- [x] `NobleCryptoBackend` — implements `CryptoBackend` for
      secp256k1 / ed25519 / p256 (sign / verify / derivePublic /
      hash / hmac / hkdf / recoverPublic for secp256k1). Output
      bytes match JVM BouncyCastle bit-for-bit.
- [x] Registration — `Register.install()` for Scala-side init,
      plus `@JSExportTopLevel("registerNobleCryptoBackend")` for
      JS-host init.
- [x] `NobleCryptoBackendTest` (Node.js) — 16 specs:
      empty-string sha256/keccak256/sha512, HMAC-SHA256 RFC 4231 #1,
      HKDF-SHA256 RFC 5869 #1, ed25519 RFC 8032 vector 1 (derive +
      sign empty msg), secp256k1 derive + sign-verify + recover +
      EVM-address round-trip (privkey 0x4646… → 0x9d8a62f656…) +
      tamper rejection, p256 derive + sign-verify, registry
      round-trip via `Register.install`.
- [x] `CrossPlatformFixturesTest` (`crypto-bouncycastle/src/test/`)
      — 7 specs that assert the **same hex strings** the JS test
      asserts; running both sides green proves byte-identical
      cross-platform output.
- [x] npm-deps strategy: no sbt-scalajs-bundler. A
      `crypto-noble-js/package.json` pins `@noble/curves ^1.9.0` +
      `@noble/hashes ^1.8.0`; `npm install --prefix crypto-noble-js`
      is the only setup step before `sbt cryptoNobleJs/test`.
- [x] Build sanity sweep — `cryptoSpi(Js)/test cryptoNobleJs/test
      cryptoBouncycastle/test walletSpi(Js)/test` all green; full
      `sbt compile` clean.

**Not yet implemented on JS** (raise `UnsupportedOperationException`):
HD derivation (`deriveMaster` / `deriveChild`), PBKDF2, Argon2id,
AES-GCM. PBKDF2 / Argon2id / AES-GCM land in Stage 5 (encrypted vault
+ SubtleCrypto adapter). HD derivation lands when the first JS-side
strategy module needs it (Stage 3 or 4).

### Stage 3 — Strategy + connector cross-compile

- [ ] `wallet-strategy-eoa` → cross-compile.
- [ ] `wallet-connector-eip1193` → cross-compile + `js/` source dir
      for the `window.ethereum` injection layer.
- [ ] `wallet-connector-wallet-std` → cross-compile + `js/` source
      dir for the `@wallet-standard/core` facade.

### Stage 4 — `wallet-strategy-erc4337` cross-compile

- [ ] Replace any `java.math.BigInteger` usage with `BigInt`.
- [ ] Cross-compile; passkey owner support waits on Stage 5.

### Stage 5 — `wallet-vault-encrypted` cross-compile

- [ ] Shared encrypted-payload types in `shared/`.
- [ ] JS side: SubtleCrypto adapter (`crypto.subtle` for AES-GCM +
      PBKDF2; Argon2id via `@noble/hashes` Argon2 wrapper or
      argon2-browser).
- [ ] JVM side unchanged (`wallet-vault-encrypted-jvm` keeps
      using JCE).

### Stage 6 — `wallet-connect` cross-compile

- [ ] Shared WC v2 protocol types in `shared/`.
- [ ] JS side: native `WebSocket` adapter + WebCrypto AEAD.
- [ ] JVM side unchanged (`java.net.http.WebSocket` + BouncyCastle).

---

## Wallet SPI — key management + dApp connectivity

Spec in [`docs/wallet-spi.md`](docs/wallet-spi.md). Sits above
blockchain-spi. Two extension axes: key management (`Vault` /
`RawSigner` / `AccountStrategy`) and dApp connectivity
(`DappConnector`: EIP-1193, Wallet Standard, WalletConnect v2).

Replaces the SHA-256 stub in `x402-client.PrivateKeyWallet` with real
secp256k1 ECDSA via an adapter shim — x402's public API is unchanged.

### Phase 1 — Skeleton SPI + EOA strategy + x402-client shim ✓ Landed (2026-05-19)

Landed in tandem with blockchain-spi Phase 1.

- [x] `wallet-spi` — `RawSigner` / `Vault` / `AccountStrategy` /
      `DappConnector` / `AccountManager` (JVM only this phase;
      Scala.js cross-compile follows in Phase 3 of blockchain-spi)
- [x] `wallet-strategy-eoa` — `EoaStrategy` impl
- [x] In-memory `RawPrivateKeyVault` test helper (lives in
      `wallet-strategy-eoa` rather than `wallet-spi` since it needs
      `CryptoBackend.get()` to derive public keys)
- [x] x402-client refactor: `PrivateKeyWallet` is now a thin shim
      that wires `RawPrivateKeyVault` + `EoaStrategy` +
      `EvmChainAdapter` + `Eip3009` helper. Public API stable;
      existing `X402ClientTest` stays green with real signatures
      (fake addresses like `"0xpayTo"` replaced with valid 20-byte
      hex since real ABI encoding rejects malformed input).

### Phase 2 — Encrypted Vault ✓ Landed JVM (2026-05-20)

- [x] `wallet-vault-encrypted` — interface (JVM; cross-compile follows
      blockchain-spi JS phase)
- [x] BIP-39 mnemonic generation / restore (24-word default)
- [x] Argon2id → AES-GCM(seed) password unlock
- [x] `wallet-vault-encrypted-jvm` — filesystem (`VaultFile`)
- [ ] `wallet-vault-encrypted-js` — IndexedDB (deferred until Scala.js
      cross-compile of `wallet-spi` lands)

### Phase 3 — DappConnector EIP-1193 ✓ Scaffold landed (2026-05-20)

- [x] `wallet-connector-eip1193` — `Eip1193Provider` translator (JVM;
      JS `window.ethereum` injection wired in next iteration once the
      Scala.js cross-compile is on)
- [x] EIP-6963 multi-injected-provider discovery types
- [x] Translates `eth_*` JSON-RPC → `AccountManager.request`

### Phase 4 — DappConnector WalletConnect v2 (scaffold landed 2026-05-20)

- [x] `wallet-connect` — protocol shape + scaffolded
      `WalletConnectConnector` (JVM)
- [x] Multi-chain via CAIP-2 namespaces (`WcNamespace`)
- [x] Transport-layer cryptography (2026-05-20):
  - [x] `RelayJwt` — EdDSA(ed25519) JWT signing + did:key encoding
        (W3C `z6Mk…` multicodec prefix); JWT payload carries
        `iss`/`aud`/`iat`/`exp`/`sub`.
  - [x] `WcEnvelope` — Type 0 + Type 1 ChaCha20-Poly1305 envelopes
        (JCE `ChaCha20-Poly1305`), base64 transport framing.
  - [x] `WcKeyAgreement` — X25519 keypair / ECDH / HKDF-SHA256 →
        session symKey / topic = sha256(symKey); `wc:` pairing-URI
        parser (validates topic = sha256(symKey)).
- [x] JVM transport composition — `JvmRelayTransport` wiring the
      primitives above to JDK `java.net.http.WebSocket` and the
      `irn_publish` / `irn_subscribe` / `irn_subscription`
      JSON-RPC frames (2026-05-20):
  - [x] `WcSessionStore` — thread-safe in-memory `topic → (symKey,
        peerPub)` map used by the transport to look up sealing keys
        per topic.
  - [x] `WsChannel` trait + `JdkWsChannel` impl over
        `java.net.http.WebSocket` (partial-frame accumulator).
  - [x] `RelayJsonRpc` — `irn_publish` / `irn_subscribe` / `irn_unsubscribe`
        builders, `irn_subscription` parser, monotonic id allocator.
  - [x] `JvmRelayTransport` — composes the primitives + channel +
        store; ApproveSession sealed as Type-1 (ships responder's
        X25519 pubkey, derives session symKey, registers session
        topic = sha256(symKey)); other outbound variants seal Type-0.
        Inbound demux: `irn_subscription` → envelope decrypt →
        inner JSON-RPC method dispatch
        (`wc_sessionPropose` / `Request` / `Delete` / `Update` /
        `Ping`); unknown topics + unhandled methods are dropped.
  - [x] `WcOutbound` ADT carries an explicit `topic` field on every
        variant — the connector knows which topic each outbound
        belongs to.
- [ ] JS: facade over `@walletconnect/sign-client` (still open;
      blocked on Scala.js cross-compile of `wallet-spi`).
- [ ] WC project-ID open question — still pending; the JVM
      transport accepts a `projectId` argument but CI does not yet
      provision one. To resolve once the JS facade lands or before
      first production deployment.

### Phase 5 — Solana DappConnector ✓ Landed JVM translator (2026-05-20)

- [x] `wallet-connector-wallet-std` — Solana Wallet Standard request
      surface (`standard:connect` / `standard:disconnect`,
      `solana:signMessage` / `signTransaction` / `signAndSendTransaction`,
      `wallet:setActiveChain`). Sui-side features deferred.
- [x] Blockchain-spi Phase 3 dependency satisfied by the existing
      `SolanaChainAdapter`.
- [ ] Scala.js `registerWallet` integration with
      `window.standard.wallets` (waits for `wallet-spi` Scala.js
      cross-compile, same blocker as Phase 3 EIP-1193 / Phase 4 WC).

### Phase 6 — ERC-4337 SmartAccountStrategy ✓ Landed (2026-05-20)

- [x] `wallet-strategy-erc4337` — `SmartAccount.wrap(...)`
      convenience pairing
- [x] UserOp construction + signing over `userOpHash` (EntryPoint v0.6)
- [x] Bundler client (`BundlerClient` — send / estimate / receipt /
      supportedEntryPoints; both flat and `receipt:{}`-envelope reply
      shapes accepted)
- [x] Counterfactual CREATE2 address derivation (`SimpleAccountFactory`)
- [x] EntryPoint v0.7 PackedUserOperation — `UserOpHashV07`
      (compressed accountGasLimits / gasFees), version-aware
      `BundlerClient` (`BundlerClient.v07(...)`), wire-side
      factory / factoryData + paymaster split in JSON. The on-chain
      hash composition (`keccak(packed)`, then
      `keccak(encode(., ep, cid))`) is shared with v0.6.
- [x] **JVM Passkey owner via WebAuthn (P-256).** ✅ **LANDED** (2026-05-20).
      `PasskeyAssertion` (clientDataJSON challenge extraction +
      WebAuthn `sha256(authData || sha256(cdJson))` digest), `PasskeySigner`
      (`RawSigner` curve = P256; delegates the actual `navigator.credentials.get`
      assertion to a host callback so JVM tests inject a deterministic
      signer and JS will wire `navigator.credentials.get` later), DER →
      raw + low-s normalisation, ABI-encoded signature blob matching
      Coinbase Smart Wallet `WebAuthn.sol` / ERC-7836
      `(bytes authenticatorData, bytes clientDataJSON, bytes32 r,
       bytes32 s, uint256 challengeIndex, uint256 typeIndex)`.
      `SimplePasskeyAccountFactory` mirrors `SimpleAccountFactory`
      shape with `createAccount(uint256 x, uint256 y, uint256 salt)`
      init-code. 16 new tests, 43 total in `walletStrategyErc4337`.
- [ ] **JS-side WebAuthn facade** (Scala.js): thin wrapper around
      `navigator.credentials.get(...)` that fits the
      `assertChallenge: Array[Byte] => Future[WebAuthnAssertion]`
      callback shape. Blocked on `wallet-spi` Scala.js cross-compile
      (same gating as Phase 3 EIP-1193 / Phase 4 WC / Phase 5 wallet-std).

### Phase 7 — Hardware wallet Vault (Ledger multi-chain)

Architecture in [`docs/wallet-spi.md`](docs/wallet-spi.md) §5.1. One
device, one seed, per-chain on-device apps; the Vault routes
`getSigner(curve, path)` to the right active app and surfaces
`AppSwitchRequired` to the host when the user must change apps.

- [x] `wallet-vault-ledger` — shared types (JVM, cross-compile-ready
      sources): `LedgerTransport` trait, `Apdu` codec + chunked send,
      `AppSwitchRequired` error, `Dashboard.getAppName` probe,
      `Bip32Path` encoder, `CurveAppRouting` curve→app table.
      27 tests across `ApduTest` / `Bip32PathTest` /
      `CurveAppRoutingTest` / `DashboardTest`.
- [x] `wallet-vault-ledger-jvm` — `hid4java` transport with the
      Ledger HID framing (5-byte header + 64-byte frames + first-
      frame length prefix + CID 0x0101). 10 framing round-trip
      tests; the actual `Hid4JavaTransport` device class is wired
      but exercised manually in dev (no device in CI).
- [x] Ethereum-app signer: `wallet-vault-ledger-ethereum`
      (CLA=0xE0). `EthereumApp` wraps GET_PUBLIC_KEY (INS=0x02),
      SIGN_TRANSACTION (0x04 chunked), SIGN_PERSONAL_MESSAGE
      (0x08 chunked), SIGN_EIP712_HASHED (0x0C).
      `LedgerEthereumVault` implements `Vault`; probes `getAppName`
      before signing → `AppSwitchRequired` on mismatch.
      `LedgerEthereumSigner` extends `RawSigner`, routes
      `hash=Keccak256` to SIGN_TRANSACTION and `hash=None` (64-B
      `[domain||msgHash]`) to SIGN_EIP712. Covers all 6 EVM x402
      chains via the single Ethereum app. 13 tests.
- [ ] `wallet-vault-ledger-js` — WebHID transport (Scala.js).
      Deferred — Scala.js cross-compile of the shared types comes
      with the broader wallet-spi Scala.js sweep.
- [ ] Solana-app signer: ed25519 + Solana sign-doc framing.
      Deferred to follow-up slice (mirror of `EthereumApp` for
      `app-solana`; CLA=0xE0, INS=0x05 SIGN_OFFCHAIN_MESSAGE /
      INS=0x04 SIGN; default path `m/44'/501'/0'/0'`).
- [ ] Bitcoin-app signer: PSBT-aware (depends on blockchain-spi
      Phase 5). Deferred.
- [ ] Cardano-app signer: CIP-8 framing (depends on blockchain-spi
      Phase 6). Deferred.
- [ ] Optional `wallet-vault-ledger-bluetooth-js` — WebBLE for
      Nano X / Stax. Deferred.
- [ ] Optional `wallet-vault-trezor` follow-up. Deferred.

### Phase 8 — MPC Vault

- [x] `wallet-vault-mpc` — HTTP client to external MPC provider
  - [x] `RemoteSigningClient` trait — `listAccounts` / `sign` /
        `health`, vendor-agnostic abstraction over the provider-side
        signing surface
  - [x] `McpVault` — SPI-conforming `Vault`; delegates to a
        `RemoteSigningClient`; lock = forget cached token, unlock =
        `health()` probe
  - [x] `McpRemoteSigner` — `RawSigner` impl that round-trips every
        signature through `RemoteSigningClient.sign`
  - [x] `HttpRemoteSigningClient` — reference JSON-over-HTTPS impl
        modelled on a Fireblocks-shaped REST surface; supports both
        synchronous (200 + completed signature) and asynchronous
        (202 + `operationId` → poll `/v1/operations/{id}`) flavours;
        bearer-token auth, configurable poll interval / max-attempts /
        request timeout; subclass hook (`decorateRequest`) for
        provider-specific auth decoration
  - [x] `MpcSerialization` — base64 codec + curve/hash naming + JSON
        marshalling for sign request, account list, operation status
- [ ] Curve-specific MPC protocol modules — **deferred**. Each MPC
      vendor (Fireblocks GG18/CMP for secp256k1; Coinbase MPC for
      ECDSA; ZenGo/Web3Auth/Lit Protocol; the FROST-Ed25519 family)
      ships its own SDK semantics. Plan is one provider-specific
      adapter module per vendor (e.g. `wallet-vault-mpc-fireblocks`,
      `wallet-vault-mpc-coinbase`) that subclasses
      `HttpRemoteSigningClient` and bundles vendor-mandated request
      decoration (HMAC signing, idempotency keys, polling cadence) —
      kept out of `wallet-vault-mpc` so the trait surface stays
      vendor-neutral.

---

## MCP × x402 × Wallet — agentic payments

Spec in [`docs/mcp-x402-wallet.md`](docs/mcp-x402-wallet.md). Layers
three integrations on top of `mcp-common`, `wallet-spi`,
`blockchain-spi`, and `x402-*`:

1. `mcp-wallet-server` — exposes `wallet-spi` operations as MCP
   tools (sign / send / balance / accounts) under a host-controlled
   `Policy` with `elicitation`-based consent.
2. `mcp-x402` — lifts HTTP 402 into MCP: a new `-32402` error code
   carrying `PaymentRequirements`, `_meta.x402.payment` field on
   `tools/call` params, `X402AutoPay` middleware on the client.
3. Composed flow: agent connects to a local stdio wallet server +
   a remote priced server; on `-32402` the client middleware signs
   via the wallet server and retries, transparently to the agent.

Depends on `mcp-common` (v1.17 — already largely landed),
`wallet-spi` Phase 1, `blockchain-spi` Phase 1, and `x402-core` /
`x402-server`.

### Phase 0 — Spec ✓ Landed (2026-05-19)

- [x] `docs/mcp-x402-wallet.md` — architecture, tools, policy
      model, error-code allocation, phase plan

### Phase 1 — mcp-wallet read-only ✓ Landed (2026-05-19)

`Policy` + `ConfirmationMode` types; `McpWalletServer.installOn(builder)` mounts
read-only tools (`wallet.listAccounts`, `wallet.getAddress`, `wallet.getBalance`)
and `wallet://accounts` resource. Policy filter controls exposed tools and chains.
8 tests covering listing, address lookup, balance (native + ERC-20), policy gate.

### Phase 2 — mcp-wallet signing with elicitation ✓ Landed (2026-05-19)

`wallet.signMessage`, `wallet.signTypedData`, `wallet.payX402` tools — all gated
on `ConfirmationMode`. `ElicitationPerCall` blocks until `ElicitationHandler`
approves; `Implicit` auto-approves for session keys. `AuditLog` records every op
(timestamp, tool, policy decision, sig hash); exposed via `wallet://audit` resource.
7 tests: approved sign, rejected sign, fail-closed without handler, payX402 payload,
maxPerCall enforcement, audit resource.

### Phase 3 — x402 over MCP server side ✓ Landed (2026-05-19)

`mcp-x402` module: `Mcp402Protocol` constants (`-32402`, `_meta.x402.*`);
`Mcp402Dispatcher.dispatchTool/Resource/Prompt` — emits `-32402` on unpaid calls,
verifies `_meta.x402.payment` via `Facilitator` before executing. `ToolPrice` /
`PaymentScope` additive fields on registrations. 7 tests: unpaid → -32402, valid
payment → Right, facilitator Fail, malformed base64, session-scoped oneShot=false,
resource/prompt kind labels.

### Phase 4 — x402 over MCP client side ✓ Landed (2026-05-19)

`X402AutoPay` middleware for `McpClientCore`: on `-32402` parses `PaymentRequirements`,
calls `PaymentSigner.sign`, retries with `_meta.x402.payment`. `maxAmount` ceiling
enforced independently of wallet policy. `onCharge` hook for observability.
`PaymentRequiredException.tryParse` for typed error handling. 7 tests: round-trip,
maxAmount rejection, charge hook, signer returning None, non-payment passthrough,
exception parse round-trip.

### Phase 5 — Composed agent flow ✓ Landed (2026-05-19)

`wallet.sendTransaction` tool (build + sign + broadcast, gated on policy + elicitation).
`ConfirmationMode.ElicitationCached(ttlSec)` caches per `(tool, chainId)` within TTL;
rejection not cached. `McpWalletPaymentSigner` bridges `mcp-wallet-server` → `X402AutoPay`
for end-to-end agent flow. `PaidAgentCompositionTest`: agent → autopay → wallet →
priced server full round-trip in-process. 6 tests.

### Phase 6 — Resources & prompts pricing ✓ Landed (2026-05-19)

`Mcp402Dispatcher.dispatchResource` / `dispatchPrompt` — same `-32402` / payment
verification pattern as tools; `kind` field (`tool`/`resource`/`prompt`) in requirements.
`X402AutoPay` handles resource/prompt 402s via the same `PaymentSigner` path.
7 tests covering resource, prompt, and tool dispatch + client round-trip.

### Phase 7 — OAuth-aware policy resolution ✓ Landed (2026-05-20)

`McpWalletAuth` thread-local carries JWT claims from the OAuth middleware into
tool handlers. `PolicyProvider` trait — `Static(policy)` (default) and
`FromAuth(resolver, fallback)` (per-request, reads current claims).
`McpWalletServer.policyProviderOverride` enables per-request scope narrowing:
`wallet:read` scope gates read-only tools; `wallet:sign` scope gates signing;
unrecognised scopes fall back to read-only policy. `maxPerCall` overridable
per-scope. 9 tests covering FromAuth routing, scope gates, fallback, thread-local
lifecycle, scoped budgets.

### Phase 8 — Stream payments via MCP ✓ Landed (2026-05-20)

`Pricing.Stream` variant in `ToolPrice`; `Mcp402Dispatcher` emits `scheme=stream`
in requirements and accepts stream-scheme payments. `Mcp402Protocol.streamChargeMeta`
/ `parseStreamCharge` helpers; `X402AutoPay.onStreamCharge` hook for running-total
accumulation. 7 tests: dispatch with stream pricing, stream payment acceptance,
meta round-trip, AutoPay stream hook, running-total accumulation.

## Micropayment Platform — channel-based fee amortisation for microtransactions

Spec in [`docs/micropayment-spi.md`](docs/micropayment-spi.md). Sits above `blockchain-spi` and `wallet-spi`; peer of x402. Five strategies: ThresholdBatching (x402 Facilitator backend), EVM StateChannel, Cardano HydraHead, Probabilistic lottery, and L2Native pass-through.

### Phase 1 — `micropayment-spi` core traits ✓ Landed (2026-05-19)

`ChannelId`, `ChannelConfig`, `ChannelState`, `PaymentReceipt`, `SettlementResult`,
`MicropaymentChannel`, `SettlementPolicy` combinators, `ChannelKind`, `ChannelProvider`.

### Phase 2 — ThresholdBatching + server middleware + HTTP client ✓ Landed (2026-05-19)

EIP-712 cumulative-receipt signing, `ReceiptStore`, `withMicropayment` server middleware,
`ReceiptCodec`, `MicropaymentHttpClient`. 9 integration tests.

### Phase 3 — Probabilistic lottery (`micropayment-probabilistic`) ✓ Landed (2026-05-19)

Pure `LotteryMath` (win-condition, HMAC-seeded salt, commitment), `LotteryTicket`,
`LotteryReveal`, `WinningTicketStore`, `ProbabilisticChannel`, `ProbabilisticProvider`.
11 math test vectors + 10 integration tests. Settlement accumulates won amounts
in-memory; on-chain batch redemption deferred to Phase 4.

### Phase 6 — Multi-chain ThresholdBatching via `blockchain-spi` ChainAdapter ✓ Landed (2026-05-19)

Removed x402 `Facilitator`/`NonceStore` from `ThresholdChannel`; settlement now uses
`chain.buildTransaction(TxIntent.TokenTransferAuthorized)` + `strategy.signTransaction` +
`chain.broadcast`. Removed `x402Core` build dependency; unified upickle to 3.3.1.

### Phase 4 — EVM state channels (`micropayment-channel-evm`) ✓ Landed (2026-05-20)

`PaymentChannel` Solidity contract (`submitFinalState`, `challenge`, `cooperativeClose`, `finalise`).
`StateChannel` — payer-side `pay()` (EIP-712 personal_sign), payee-side `receive()` (sig recovery),
`settle()` / `challenge()` / `cooperativeClose()`. ABI encoding via `PaymentChannelAbi`.
`StateChannelProvider` — deploys contract via `TxIntent.Deploy` + `predictDeployAddress`, or
wraps existing contract. 16 tests (lifecycle, cooperative close, dispute path, provider, ABI helpers).

### Phase 5 — Cardano Hydra heads (`micropayment-hydra`) ✓ Landed (2026-05-20)

`HydraNodeClient` trait — Java 11 `java.net.http.WebSocket` live impl + in-process `StubHydraNodeClient`.
`HydraMessage` — `HydraServerMsg` (HeadIsOpen, TxValid, TxInvalid, HeadIsClosed, HeadIsFinalized, …)
+ `HydraClientMsg` (NewTx, Close, Fanout) with JSON parsing/serialisation.
`HydraChannel` — payer-side `pay()` (NewTx → waits TxValid via Promise), payee-side `receive()`
(waits TxValid for txId from receipt), `settle()` (Close → HeadIsClosed → Fanout → HeadIsFinalized).
`HydraHeadProvider` — opens channel against a connected Hydra node; `stub()` helper for tests.
18 tests (pay/receive lifecycle, TxInvalid error, settle path, threshold policy, provider, message parsing).
