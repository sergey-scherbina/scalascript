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
     v1.17.1 hardening ✓ Landed (2026-05-19).
     Anthropic's Model Context Protocol via REST-shaped API
     in a separate namespace (`std/mcp/*`).  Intrinsic-first:
     wraps `@modelcontextprotocol/sdk` on Node and
     `io.modelcontextprotocol:sdk` on JVM; interpreter +
     scalajs-spa reject at typecheck via SPI feature flags.
     Full design in [`docs/mcp.md`](docs/mcp.md).  Optional
     v1.17.2+ follow-ups: SSE transport on JS, prompts on JVM,
     type-class layer (depends v1.14), own implementation for
     INT (defer), streaming resources (depends v1.10).
 22. **v1.18 — `package` keyword + std layout migration** ✓ Landed.
     Phases 1, 2, 4 landed (parser, codegen, conformance); Phase 3 (std migration) deferred.
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
 25. **v1.21 — Local map-reduce (`Dataset[T]`)** (~2 weeks).
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
 27. **Cluster management** — *deferred, no version assigned*.
     Peer discovery, membership view, leader election,
     configuration distribution.  Promote when any of three
     trigger conditions fire (see
     [`docs/cluster-management.md`](docs/cluster-management.md)
     §6).
 28. **6+/C — HostCallback dispatcher** (~1 week).
     Stage 6+/C from spi-followups-plan.md.  Unblocks the first
     out-of-process (.NET / WASM) backend MVP.  Parked because no
     such backend is in flight.
 29. **v2.0 — Separate compilation** (~2-3 months).
     **After v2.0 is complete, the algebraic-effects feasibility
     study (BLOCKED — see end of file) may be re-evaluated.**
     Restartable errors (v1.16) are folded into that study and
     promote only if the study commits to "go".
     Multi-month architecture commitment.  Promote when at least
     one of {real package ecosystem, >30s incremental build, IDE
     demand} is true.

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

## v0.11 — i18n / l10n

Many real apps need translatable strings before they need much else.
Today every component embeds English labels directly in `def
render`.  Need a non-leaky way to thread a locale through.

Shape under consideration:

  - **`Locale` global** — a thread-local-like singleton with the
    current locale code; components call `t("submit")` to look up
    the active translation.  Falls back to the key string if no
    translation registered.
  - **Front-matter `translations:`** — per-page or per-component
    YAML map keyed by locale code.  Aggregated into a global table
    at boot.
  - **Number / date formatting** — defer to ICU only on JVM; ship a
    tiny built-in formatter for interpreter + JS.

Defer until a multilingual app surfaces.  ~1 week if we keep it
opinionated.

## v0.12 — SSR + client hydration story

We already render server-side and we can ship `val js` that finds
elements via querySelector.  But once we ship Web Components (v0.8)
the question is: how do server-rendered light-DOM + client-side
shadow-DOM cohabit without one wiping the other?

Investigation, not implementation, until v0.8 lands.

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
- **Hot reload in `serve` mode** — routes pinned at start.
  Restart-to-deploy works in prod; dev UX issue.
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

1. **Bounded mailboxes + backpressure.**  v1.6 ships unbounded
   mailboxes (Erlang default).  Add `mailbox = bounded(n,
   onOverflow = Block | DropOldest | DropNewest | Fail)` as a
   `spawn` option once a real workload demands backpressure.  ~80
   LOC per backend.

2. **Actor tracing & introspection.**  `processInfo(pid):
   ProcessInfo` returning mailbox size, links, monitors, current
   behavior; opt-in hook to log every message in/out of a PID.
   Half a day; pays back the first time something hangs.

3. **Cluster discovery.**  v1.6 requires manual `connectNode(url)`
   per peer.  Add a seed-list or multicast helper so an N-node
   cluster doesn't need N² manual connects.  ~150 LOC.

4. **Cluster-wide registry.**  v1.6 has per-node `register` /
   `whereis` with explicit cross-node lookup.  Add an Erlang
   `global:register_name` analogue with node-id-priority conflict
   resolution.  Requires a small consensus protocol; defer until
   the manual cross-node `whereis` becomes friction.

5. **Scheduled / delayed sends.**  `sendAfter(delayMs, pid, msg)`
   and `sendInterval(periodMs, pid, msg)` and `cancelTimer(ref)`.
   **Landed** — all three backends; 5 tests green. ✓

6. **Persistent mailboxes / event sourcing.**  Far-future v2-class
   feature; mention only.  Replay state from a journal on
   supervisor restart, like Akka Persistence.

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

Parked deliberately — re-evaluate after v1.8 ships and real code
drives the question:

1. **Postfix `.!` explicit-bind operator** (DS-6 follow-up).
2. **Effect-row union types** — `direct[Async | Random]`.
3. **Transformer-aware lift** — auto `OptionT.liftF` inside an
   outer `Async` direct block.
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

### Phase 4 — Cross-file trait inheritance with HKT (~3-4 days)

Today `trait Traversable[T[_]] extends Functor[T], Foldable[T]`
breaks at the JVM compile when `Functor` lives in a separate
file.  v1.1 step 3 worked around it.  Fix the typer's import-
resolution pass to carry trait *definitions* (not just instances
and extensions) into the consumer's scope.

### Phase 5 — Sealed-trait extension dispatch in INT (~2 days)

Build a sealed-parent registry at trait/class definition time.
`extensionDispatch` walks the parent chain when the exact-name
lookup misses.  Closes the carryover item from v1.1 that forced
helper functions for `Either` in steps 5 / 6.  JS already
handles this via `_typeOf`; JVM relies on Scala's own dispatch.

### Phase 6 — Conformance + std polishing (~2 days)

- Six conformance tests covering the four typer dependencies
  (see `docs/final-tagless.md` §9).
- Rewrite `std/semigroup-monoid.ssc` helpers from explicit-pass
  to context-bound form.  Same observable behaviour, less
  call-site boilerplate.
- Re-enable extension-method form of `bimap` / `handleError` for
  `Either` (currently shipped as helpers per v1.1 carryover).

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

## v1.16 — Restartable errors via algebraic effects — BLOCKED

**Do not start this until all milestones through v2.0 are
complete** (same block as the algebraic-effects study below).

Common Lisp condition-system style restartable handlers.  A
handler can choose to resume the suspended computation at the
throw point with a replacement value, rather than only being
able to abort or rewrap.

```scala
val config = restartable {
  case FileNotFound(path) => Restart.useDefault
  case PermissionDenied(p) => Restart.retry(sudo(p))
  case other => Restart.abort(other)
} {
  parseConfig(readFile("/etc/app.conf"))
}
```

If the algebraic-effects study (see end of file) eventually
commits to "go", v1.16 reduces to: `throw e` becomes
`suspend(ErrorTag(e))`; handler stack catches the tag and
resumes with a replacement value.  Direct mapping onto v1.9
coroutines.

If the study says "no-go" (or stays blocked indefinitely),
v1.16 retires — the path becomes `M.recover` + retry-loops,
no compile-time restart support.

### Sketch

- New `Restart[A]` ADT: `UseValue(a)`, `Retry(args)`, `Abort(e)`,
  `Transform(e2)`.
- `restartable[E, A](handler: E => Restart[A])(body: => A): A` —
  catches `E`, applies the handler decision, resumes/aborts
  accordingly.
- Sits on the v1.9 coroutine primitive: `suspend(ErrorTag(e))`
  pauses the computation, handler interprets, resume.

### Effort

~1 week if the algebraic-effects study (blocked — see end of
file) eventually commits to "go"; ~0 otherwise.

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

## v1.9.x — Actor internals refactor (optional cleanup)

Rebuild `spawn(...)` / `receive` on top of v1.9 coroutines.
Mailbox becomes a `Channel[M]` built from the coroutine
primitive.  Visible from outside only as a performance / code-
size win.

**Deferred behind v1.11.**  Only land if measurements show the
existing Loom-/NIO-/microtask-based actor runtime has measurable
overhead vs the coroutine-based path.  Until then, redundancy is
acceptable — two implementations sharing the same observable
semantics is a maintainable trade-off.

### Effort

~1 week if it happens.  Could be skipped entirely without
affecting any v1.x milestone deliverable.

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

### Deferred follow-ups (v1.17.x backlog, ordered by priority)

1. **SSE transport on JS** — `JsRuntimeMcp.scala:97` has a
   `/* res stub */ null` placeholder; closest to working, highest
   user impact after stdio.
2. **Prompts on JVM** — currently silently dropped in `buildSpec()`
   (no `specBuilder.prompts(...)` call); correctness bug, small fix.
3. **Http/Ws transports on JVM** — both throw `McpError("not yet
   supported")`; needs Jetty / Vert.x event-loop integration.
4. **Own implementation for INT / scalajs-spa** — ~1500 LOC
   JSON-RPC 2.0 stack; blocked until INT becomes a priority target.
5. **Type-class layer** (`given McpTool[A, R]`, `derives McpSchema`)
   — depends on v1.14 `derives`.
6. **Streaming resources** — depends on v1.10 Generators.
7. **Bidirectional sampling** — MCP advanced feature.
8. **`using mcpConnect(...) { client => … }` RAII** — needs
   `using`-resource language feature.
9. **MCP protocol version negotiation** when v2 emerges.

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

## v1.18 — `package` keyword + std layout migration

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

### Phase 3 — Std layout migration ✓ Landed

All 16 std files (excluding `actors.ssc` deferred to v1.6 Phase 2
and the not-yet-created `std/mcp/` waiting for v1.17) now carry a
`package:` declaration in their frontmatter.  Packages assigned:

| file | package |
|------|---------|
| `bifunctor.ssc` | `std.bifunctor` |
| `coroutine.ssc` | `std.coroutine` |
| `either.ssc` | `std.either` |
| `error-handling.ssc` | `std.error_handling` |
| `foldable-traversable.ssc` | `std.foldable_traversable` |
| `free.ssc` | `std.free` |
| `functor-applicative-monad.ssc` | `std.functor_applicative_monad` |
| `generators.ssc` | `std.generators` |
| `http.ssc` | `std.http` |
| `index.ssc` | `std` |
| `middleware.ssc` | `std.middleware` |
| `monad-control.ssc` | `std.monad_control` |
| `monaderror.ssc` | `std.monaderror` |
| `nodes.ssc` | `std.nodes` |
| `selective.ssc` | `std.selective` |
| `semigroup-monoid.ssc` | `std.semigroup_monoid` |

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
  JS + JVM capabilities.
- `JsRuntimeDataset` — class-based lazy pipeline, `runParallel()` warns
  once and falls back to sequential on Node.
- `JvmRuntimeDataset` — Scala `_Dataset[T]` class; `runParallel()` partitions
  into `availableProcessors` chunks and processes each in a Loom virtual thread.
- `JvmGen.blocksUseDataset` — injects preamble when Dataset is used.
- 10 conformance tests + 2 examples.

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

## Cluster management — deferred (no version assigned)

Peer-cluster orchestration on top of v1.6 Phase 3 actors:
peer discovery, membership view, leader election,
configuration distribution, cluster-wide failure
detection, rolling restarts, metrics aggregation.

Full design space and explicit hard-no list in
[`docs/cluster-management.md`](docs/cluster-management.md).
**No milestone version assigned** — promote when any of
the trigger conditions fire:

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

- **WS test cross-suite isolation goes through a process-global
  `WsRoutes` table + `WsTestLock` monitor.**  Works, but the lock
  serialises ScalaTest's default parallel suite execution for every
  `Ws*E2ETest`.  Cleaner fix would be a per-Interpreter routes
  registry — `WsRoutes` becomes `class WsRoutes` owned by the
  `Interpreter` instance, `WsProxy` consults the interpreter passed
  in.  Half-day refactor.  Worth it if a third WS-touching suite
  lands.

- **Scala compiler warnings in our own code.**  `build.sbt` already
  enables `-Wunused:all -deprecation -feature`, but the warnings
  haven't been routinely fixed as they accumulated.  A one-pass
  cleanup: triage the current backlog, fix the ones that point at
  real issues (unused vals, deprecated stdlib calls, missing
  `language:` imports), and silence-with-comment the ones that are
  intentional.  Then bump to `-Xfatal-warnings` so the next batch
  can't accumulate silently.  Tighten as `Compile / scalacOptions`
  so test code can stay slightly looser if needed.  Half-day.

## CLI — native binary (GraalVM native-image)

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

## Beyond

Larger features that aren't on the critical path but are worth keeping in
view so they shape near-term decisions.

- **Hot reload in `serve` mode.**  Reparse and re-register routes when a
  `.ssc` file changes on disk; today the server pins them at start.
- **REPL: web-aware mode.**  `bin/ssc repl` that lets you mount routes
  interactively and inspect the route table.
- **`html"..."` precision.**  Smarter `${}` parsing inside string-blocks
  so `${ a + "}" }` doesn't fool the regex (current TODO in the inline
  block evaluator).
- **Future web-services protocols.**  HTTP/2, gRPC, GraphQL, OpenAPI
  schema export — each questioned during v1.1 review and deferred
  with concrete reasoning.  See [`docs/future-protocols.md`](docs/future-protocols.md)
  for prerequisites, effort estimates, and why each is on hold
  until a concrete user surfaces.

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

