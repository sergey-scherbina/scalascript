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

**Still deferred (pending parallel WS v1.0 merge to main):**
- 5+/B.3 — migrate bare `println` → `Console.println` in Normalize
- 5+/D — `std.ws` / `std.auth` / `std.fs` / `std.crypto` extraction (next step)
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
       `summon` auto-resolution, `Term.Ascribe`) lives in the
       interpreter's type-system / dispatch logic, not in its method
       table.  That work is tracked under **Interpreter ergonomics
       — carried over from v1.1** (further down in this file).

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

  - **5+/A.4 — per-call-site `ExternCall` dispatch.**  JvmGen / JsGen
    gain a single emit case for `ExternCall(qn, args)` that consults
    `backend.intrinsics(qn)`.  Removes the per-intrinsic match arms.
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
  4. **v1.5 Tier 1 — TLS** (~1 week).
     Highest-impact functional unblocker (standalone-internet
     deploy).  Ships as a new intrinsic via the just-built pipeline,
     so the work is doubly cost-efficient (real feature + proves
     the SPI on a non-trivial intrinsic).
  5. **SPI 5+/D** — `std.ws / auth / fs / crypto` extraction
     (~1 week, 1-2d per package).
     Generalises 5+/B to the remaining platform surface; finishes
     the codegen-deflation pass.
  6. **v1.5 Tier 2 — HTTP client** (~1 week).
     Outbound integration unblocker (OAuth callbacks, payments,
     AI/LLM, microservices).  Ships through the SPI pipeline as a
     new intrinsic package.
  7. **v1.5 Tier 3 — WS client** (~1 week).
     Symmetric to onWebSocket; needed for v1.6 Phase 3.  Inherits
     TLS for `wss://`.
  8. **v1.7 — Plugin packaging & discovery** (~3 weeks).
     Now that the SPI surface is clean and exercised by multiple
     extracted packages (http, ws, auth, fs, crypto + tls + http
     client + ws client), the plugin distribution layer has a
     stable target to build against.
  9. **v1.6 Phase 2 — Actors supervision** (~3-4d).
     Independent; can land any time after Phase 1.
 10. **v1.6 Phase 3 — Distributed actors** (~1 week).
     Builds on Tier 3 WS client.
 11. **v1.5 Tier 4 — HTTP server completeness** (~1 week).
     Streaming responses + uploads, CORS, gzip, cache headers.
 12. **v1.5 Tier 5 — REST ergonomics** (~4-5d).
     Middleware, request validation, /_health/_ready, indexed JSON.
 13. **v1.8 — Direct-syntax do-notation** (~3 weeks).
     Promoted from 6+/A to its own milestone with full design in
     [`docs/direct-syntax.md`](docs/direct-syntax.md) and a six-
     phase plan.  Parked until Stage 5+/B `std.http` is complete —
     implementation cost dominates over benefit until real `std.*`
     packages drive direct-syntax usage patterns.
 14. **6+/C — HostCallback dispatcher** (~1 week).
     Stage 6+/C from spi-followups-plan.md.  Unblocks the first
     out-of-process (.NET / WASM) backend MVP.  Parked because no
     such backend is in flight.
 15. **v2.0 — Separate compilation** (~2-3 months).
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


## v0.9 — Optics — second pass

The v0.6 hierarchy (Lens / Prism / Optional / Traversal) covers
field-path access on case classes, sum-type variants, `Option` paths
via `.some`, and `List` traversals via `.each`.  A handful of
extensions would close the remaining real-world gaps; listed in
priority order so each one can ship independently.

1. **Index optic — `.at(key)` / `.index(i)`.**  Today `Focus[T](_.users.each)`
   traverses every element, but pointwise access (`users(3)`, `byId("u-42")`)
   still falls back to `xs.find(...).map(...)` and hand-rolled `.copy`.
   Add two path steps recognised by the Focus parser:

       Focus[State](_.users.index(3))      // Optional[State, User]
       Focus[Inventory](_.byId.at("u-42")) // Optional[Inventory, Option[User]]

   `.index(i)` works on `List[A]` and returns `Optional[List[A], A]` — `None`
   when the index is out of bounds.  `.at(k)` works on `Map[K, V]` and returns
   `Optional[Map[K, V], Option[V]]` — the inner Option lets the caller insert
   a missing key by `set(..., Some(v))` and delete via `set(..., None)`,
   matching Monocle's semantics.

   Backend lowering: same pattern as `SomeStep` / `EachStep` — add `IndexStep(i)`
   and `AtKey(k)` to `PathStep`, extend `opticGetOption` / `opticSet` /
   `opticGetAll` / `opticModifyAll`, runtime helpers in JS, emitter cases
   in JvmGen.  Most useful extension by far — closes the biggest pain
   point still left in optic-using code.  ~1 day across three backends
   plus a conformance test.

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

## v0.9 — Standard component pack — cross-cutting follow-ups

The eight tiers of `std/ui/*` (forms, layout, navigation, feedback,
data, content/typography, widgets, theming) all landed in v0.9.
What's left from that block is the tooling that the pack motivates:

  - **`ssc test`** — a runner for component-level unit tests
    (`tests:` block in front-matter or a sibling `*-test.ssc` file).
    Without this every component ships untested.  Likely shape: each
    test is `(name, () => Boolean)`, runner prints pass/fail and
    aggregates exit status — same backend matrix as conformance.
    ~1 day per backend.
  - **`ssc preview <file>`** — opens a browser page that renders the
    component with each declared `variants:` set.  Storybook-lite.
    Pays back the cost the first time a designer needs to see all
    47 Button states.  ~1 day.
  - **Documentation page** for the pack (`examples/std-ui/`) that
    builds via `ssc build` and renders every component with every
    variant, with the source visible.  Largely content, not code.
    ~1 day.
  - **`std/ui/index.ssc` aggregator** — a single re-export entry so
    consumers can write `[Button, Card](./std-ui)` instead of one
    import per component.  Requires the directory-as-index resolver
    fix below.

Defer until at least one consumer of the existing convention asks
"where do I get a Button?".

## v0.10 — Extended component pack

Components the standard pack didn't cover but every real app
eventually wants.  Same shape as v0.9 (`object Foo { val css, val
js, def render }`), same `scope()` pattern, mergeable one-at-a-time.

  - **`DateInput` / `DatePicker`** — `<input type=date>` plus a CSS
    polyfill for browsers that show the ugly native UI.
  - **`TimeInput` / `DateTimePicker`**.
  - **`FileUpload`** — drag-drop zone over the existing
    `multipart/form-data` plumbing (req.files already works).
  - **`Combobox`** — autocomplete `<input>` + filterable popover.
  - **`Stepper`** — multi-step form wizard with progress indicator.
  - **`Toolbar`** — horizontal action bar (compose Button + Dropdown).
  - **`RangeSlider`** — single + dual-handle.
  - **`Tree`** — collapsible hierarchical view (built on Accordion
    primitives).
  - **`Carousel`** — scroll-snap based, no JS for the default mode.
  - **`Lightbox`** — image viewer overlay.
  - **`Stats`** — dashboard-style number tile with delta indicator.
  - **`Empty`** — no-content placeholder with icon + CTA.

Landed in iter A: `Card` (header / body / footer trio), `Switch`
(iOS-style toggle), `Alert` (banner with five tones), `Tag` (closable
inline chip).  All registered in `std/ui/index.ssc`, covered by
`conformance/std-ui-extended.ssc` on three backends.

Landed in iter B: `Stats` (dashboard tile with delta indicator),
`Empty` (no-content placeholder), `Toolbar` (start/end flex layout),
`Tree` (native-`<details>` collapsible hierarchy).  Covered by
`conformance/std-ui-extended-b.ssc`.

Each is half-day to a day.  Pick what a consumer actually asks for
before grinding through speculatively.

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

## v1.0 — WebSocket production-readiness

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

## v1.3 — Runtime upgrades: real-thread Async, persistence, Async-integrated WS

Staged additions that build on the v0.8 Async / signals stack.  Each
lands as its own merge so the suite stays green between steps.
(Stages 1, 2, and 3 — fine-grained reactive `Signal` / `computed` /
`effect`; real-thread `runAsyncParallel` handler; and the built-in
`Storage` effect with file-backed + ephemeral handlers — landed;
see git history.)

1. **`Async`-integrated WebSocket — full cross-backend.**  Blocking
   `ws.recv(): Option[String]` and `ws.isClosed` primitives landed
   for the JVM interpreter and `ssc compile` backends — see
   `examples/ws-recv-demo.ssc` — so handlers can read in a `while
   !ws.isClosed do ws.recv()` loop instead of inverting control
   through `onMessage`.  Remaining work:
     - Lift these into proper Async-effect operations
       (`Async.recvFrom(ws)`, `Async.closed(ws)`) so a handler can
       suspend across messages inside `runAsync { … }` instead of
       parking a dedicated thread.
     - JS Node target: `recv()` doesn't fit Node's single-thread
       event loop without a `worker_threads` bridge — same shape as
       stage 2 of this milestone (Node `runAsyncParallel`).  Until
       then Node WS handlers keep the callback-only API.

2. **Node target for `runAsyncParallel`.**  Today the Node JS runtime
   aliases `_runAsyncParallel` to `_runAsync` (single-threaded fallback)
   because real concurrency requires `worker_threads` + `Atomics.wait`
   for blocking `await`.  Wire that for parity with the JVM backends —
   each `Async.async(thunk)` posts to a worker, `Async.await(fut)`
   blocks the main thread on the per-future `SharedArrayBuffer` flag.
   Worker creation is ~50–100ms one-time, so a pool would help for
   small-task workloads.

## v1.4 — Standard-library effects

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

Each entry is roughly the same shape as v0.8's `Async` (effect
object + default handler + opt-in test handler + conformance test),
so they should land at a similar pace once the template is
established.  No new compiler concept required — purely runtime
library additions on top of the existing Free Monad infrastructure.

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

## v1.5 — Transport layer: TLS + HTTP/WS clients + streaming

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

- **Phase A — TLS** *(items 1-4; ~1 week)*.  The blocking piece.
  Without `https://` neither the existing server nor the planned
  HTTP client matters for real internet use, and the modern
  cookie / mixed-content rules make standalone `serve(80)` a dead
  end.  Delivers the shared `SSLContext` factory that Phases B
  and C re-use, so doing it first amortises the work.
    - A.1 — `tls("cert.pem", "key.pem")` config primitive (#3).
    - A.2 — `HttpsServer` for JvmGen REST (#2, JDK one-liner).
    - A.3 — SSLEngine wrap for the interpreter's NIO proxy (#1,
      the hard part — handshake state machine over `ByteBuffer`s).
    - A.4 — TLS wrap for JvmGen's WS proxy `ServerSocket` (#2
      partial, blocking IO so easier than A.3).
    - A.5 — Optional Let's Encrypt / acme.sh integration (#4),
      defer.

- **Phase B — HTTP client** *(items 5-7; ~1 week)*.  Picks up
  Phase A's `SSLContext` for free.  Wraps Java's `HttpClient`
  (Loom-friendly, HTTP/2 capable) on JVM, `fetch` on Node,
  `XMLHttpRequest` on browser-SPA.  Common return shape
  `Response(status, headers, body)` mirrors the server side.
    - B.1 — `httpGet` / `httpPost` primitives (#5).
    - B.2 — `httpClient { … }` block for shared config (#6).
    - B.3 — Streaming response bodies (`bodyStream { line => … }`)
      for SSE / chunked downloads (#7).

- **Phase C — WebSocket client** *(items 8-10; ~1 week)*.
  Symmetric to `onWebSocket`; re-uses `WsFraming` and Phase A's
  TLS.  Asymmetries vs the server: the client *masks* its
  outbound frames (RFC 6455 §5.3), generates a random
  `Sec-WebSocket-Key`, parses unmasked server frames.
    - C.1 — `connectWebSocket(url) { ws => … }` (#8).
    - C.2 — `wss://` over TLS (#9, free given A).
    - C.3 — Auto-reconnect with backoff (#10), defer.

- **Phase D — HTTP server completeness** *(items 11-16;
  ~1 week)*.  Independent tactical fixes, each its own commit.
  Can interleave with phases above if helpful, but most of them
  presume the v1.0 server lifecycle is settled.
    - D.1 — CORS helper (#13, smallest).
    - D.2 — gzip on responses (#14).
    - D.3 — Cache headers + 304 short-circuit (#15).
    - D.4 — Streaming responses (#11, biggest API change).
    - D.5 — Streaming uploads + spool-to-disk for big multipart (#12).
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
      (#18).  Pure library work; no runtime change.
    - D′.3 — Server-Sent Events helper (#19).  Hard-blocked on
      D.4 (Tier 4 #11).
    - D′.4 — Request validation surface (#20) — **landed**:
      `require*` / `optional*` / `requireRange*` / `requireOneOf` plus
      the `validate { body }` accumulator returning `Either[Map, T]`.
    - D′.5 — Built-in `/_health` / `/_ready` routes (#21).
    - D′.6 — Indexed access on `Any`-typed JSON values (#22) —
      **options (a) + (c) landed**.  `lookup` / `lookupOpt` runtime
      helpers (a) and the `JsonValue` wrapper (c) — `jsonRead(s)`
      with `apply` / `get` / `asString` / `asInt` / `asList` / `asMap`
      / `keys` / `size` / `isNull` / `raw` — across all three backends.

- **Phase E — full NIO HTTP migration** *(Sprint 5.16, ~2 weeks)*.
  Replaces the JDK `HttpServer` + WS-proxy pair with a single
  NIO selector loop owning both HTTP and WS state machines.
  Eliminates the loopback hop, unifies the threading model
  across interpreter and JvmGen, and is what `permessage-deflate`
  (Sprint 5.18) would build on top of.  Deferred until at least
  one Phase D item proves the JDK HttpServer is genuinely the
  bottleneck.

Total: ~4.5-5 weeks of focused work for Phases A-D′ (after which the
HTTP/WS stack is genuinely production-ready and ergonomic for
real REST apps), Phase E as a follow-up architectural pass when
scale demands it.

## v1.6 — Actors (Erlang-style, WebSocket-distributed)

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

  - **`spawn_link(behavior): Pid`** — atomic spawn-and-link.  Needs
    Phase 2's link machinery.
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

### Phase 2 — Supervision (~3-4 days)

Erlang-style fault tolerance.  With `trap_exit`, a supervisor is
just a regular actor that handles EXIT messages — no special
runtime, supervision is a library on top of Phase 1.

**Implementation state:** `link`/`monitor`/`demonitor`/`trap_exit`
handlers are **absent** from `handleActorOp`.  The `ActorRuntime`
class does not yet carry `links`, `monitors`, `trapExitM` fields.
The current `exit` op removes the actor but does not propagate
EXIT/DOWN signals.  Full implementation plan: `docs/actors-dist.md §Phase 2`.

What needs to land:
  - Add `links / monitored / monitorOf / trapExitM / nextMonRef` to
    `ActorRuntime` (interpreter) and to the emitted actor runtime
    strings in `JvmGen.scala` and `JsGen.scala`.
  - Add `handleActorOp` cases for `"link"`, `"monitor"`,
    `"demonitor"`, `"trap_exit"` — same logic in all three backends.
  - Update `exit` / `killActor` to walk links and monitors and
    enqueue `Exit(from, reason)` / `Down(ref, from, reason)` into
    surviving actors' mailboxes.  Cascade-kill if `trap_exit = false`
    and reason ≠ `"normal"`.
  - `Supervisor.start` in `std/actors.ssc` is pure ScalaScript on
    top of these primitives — no new runtime changes needed there.

  - **`link(pid)`** — bidirectional death link.  When either side
    dies, the other receives an EXIT signal.  Default behaviour is
    to crash the receiver; with `trap_exit = true` the EXIT
    arrives as a normal `Exit(from, reason)` message.
  - **`monitor(pid): MonitorRef`** — unidirectional.  The
    monitoring actor receives a `Down(ref, from, reason)` message
    when the target dies.
  - **`trap_exit(on: Boolean)`** — toggle current process's
    exit-signal handling.  Supervisors set this on at startup.
  - **`Supervisor.start(children, strategy): Pid`**
    - Strategies: `OneForOne`, `OneForAll`, `RestForOne`.
    - `ChildSpec(id, start, restart, ...)` with restart classifier:
      `Permanent` (always restart), `Transient` (restart only on
      abnormal exit), `Temporary` (never restart).
    - Max-restart window: `MaxRestarts(n, withinMs)`.  Exceeding
      the budget crashes the supervisor itself (escalates upward).

  Conformance: worker crashes → restarted; max-restart exceeded →
  supervisor dies up the tree; `OneForAll` restarts all three
  children when one dies; `Transient` doesn't restart on normal
  exit.

### Phase 3 — Distributed via WS (~1 week) — interpreter core landed

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

**Remaining (JVM + JS backends + heartbeat + node-down):**

| Decision | Choice |
|---|---|
| `startNode` binding | WS route `/_ssc-actors` on existing `serve()` — no separate TCP listener |
| Backends | All three: INT + JVM + JS |
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
   and `sendInterval(periodMs, pid, msg, until = ...)`.  ~30 LOC
   on top of the existing scheduler.

6. **Persistent mailboxes / event sourcing.**  Far-future v2-class
   feature; mention only.  Replay state from a journal on
   supervisor restart, like Akka Persistence.

### Effort

Roughly **3 weeks end-to-end** across three backends: Phase 1 ~1
week, Phase 2 ~3-4 days, Phase 3 ~1 week.  Each phase merges
independently, each closes a real use case (Phase 1 — in-process
concurrency; Phase 2 — fault tolerance; Phase 3 — uniform
local/remote).

## v1.7 — Plugin packaging & discovery (true extensibility)

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

### Tier 1 — Plugin discovery infrastructure

Audit + complete what `docs/backend-spi.md` §12.1 already
describes; some pieces may be partly landed from Stage 5–6.

  - `META-INF/services/scalascript.backend.spi.Backend` —
    ServiceLoader-based in-process plugin discovery.  Same for
    `SourceLanguage`.
  - Default discovery paths:
      - bundled CLI classpath (the four standard plugins),
      - `~/.scalascript/plugins/*.jar`,
      - `$SCALASCRIPT_PLUGIN_PATH` (colon-separated dirs).
  - CLI flags: `--plugin <jar>`, `--plugin-dir <dir>`.
  - Each plugin JAR in its own `URLClassLoader` whose parent is
    the SPI classloader only (no dependency conflicts between
    plugins).

Estimate: ~2-3 days to verify what's there + close gaps + end-to-
end conformance with a trivial test plugin.

### Tier 2 — `.sscpkg` archive format

A unified package format that covers both shapes:

  - **User-space `.ssc` library** (v0.7 case): `.ssc` source files,
    optional `package.yaml` manifest, no intrinsics.
  - **Backend / SourceLanguage plugin**: compiled JAR with
    `IntrinsicImpl` classes + runtime helper strings + `META-INF`
    service entries + optional `.ssc` prelude files.
  - **Hybrid**: both — e.g. a Kafka package that ships an
    `extern def`-declaring `.ssc` API + a JVM-backend JAR that
    implements the intrinsics.

Layout:
```
mypackage-1.2.3.sscpkg  (ZIP archive)
├── manifest.yaml          — id, version, deps, kind, capabilities, exports
├── sources/               — .ssc files (loaded into module scope)
├── runtime/               — per-backend helper strings
│   ├── jvm.scala
│   ├── js.js
│   └── interpreter.scala
├── intrinsics/            — compiled IntrinsicImpl classes
│   └── mypackage-intrinsics.jar
└── subprocess/            — optional out-of-process plugin executables
    ├── linux-x86_64
    └── darwin-arm64
```

`manifest.yaml`:
```yaml
id:           org.example.kafka
version:      1.2.3
spiVersion:   "0.1.0"
kind:         [library, plugin]   # one or both
dependencies:
  - id: org.example.json, version: ^1.0
exports:
  externDefs: [std.kafka.connect, std.kafka.publish, std.kafka.subscribe]
capabilities:
  features:   [HttpClient]        # required by the plugin's intrinsics
  declares:   [KafkaClient]       # custom sub-feature this plugin adds
targets:      [jvm, interpreter]  # which backends this plugin supports
```

Estimate: ~3-4 days.

### Tier 3 — Resolver & loader

Reads a `.sscpkg`, validates manifest (SPI version, dependency
graph), routes contributions:

  - `sources/*.ssc` → module-tree prelude
  - `intrinsics/*.jar` → ServiceLoader-discovered `Backend.intrinsics`
    contributions
  - `runtime/*` → concatenated into `Backend.runtimePreamble` for
    the matching backend
  - `subprocess/*` → out-of-process plugin launcher (Stage 6 wire)

Plus dependency resolution (transitive `.sscpkg` loading) with
cycle detection.

Estimate: ~3-4 days.

### Tier 4 — Sample external plugin

Pick one real platform integration as the canonical reference and
ship it as a standalone repo + `.sscpkg`.  Candidates:

  - **Kafka client** — natural fit; demonstrates HTTP-client
    dependency + connection pooling + serialisation.
  - **Redis client** — smaller surface, simpler protocol; better
    if Kafka is too complex for a first example.
  - **PostgreSQL client** — drives the `std.db` design too.

Includes:
  - `extern def`-declared API surface in `.ssc`
  - `IntrinsicImpl` for JvmGen (uses JDBC / libpq / kafka-java)
  - `IntrinsicImpl` for Interpreter (same Scala wrapper as JvmGen,
    different runtime path)
  - Optional JsGen (Node native client)
  - End-to-end example `.ssc` consuming the package
  - README + `manifest.yaml` + `.sscpkg` build instructions

Estimate: ~3-5 days depending on which integration.

### Tier 5 — CLI ergonomics

  - `ssc plugin install <path-or-url>` — fetch `.sscpkg`, validate,
    drop into `~/.scalascript/plugins/`.
  - `ssc plugin list` — installed plugins with version, capabilities
    declared, SPI version compatibility.
  - `ssc plugin uninstall <id>` — remove.
  - `ssc plugin check <id>` — verify SPI compatibility with the
    running compiler.
  - `ssc plugin pack <dir>` — build a `.sscpkg` from a source tree
    (similar to `ssc bundle` from v0.7).

Estimate: ~2 days.

### Tier 6 — Local registry stub (optional)

Filesystem-based registry mirror — a config file listing known
packages + their canonical URLs.  Enables `ssc plugin install
kafka` without specifying the full URL.

Central HTTP-based registry (`registry.scalascript.io`) — defer to
the v0.7 future-of-future entry; that's a multi-week ops project,
not a milestone.

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

## v1.8 — Direct-syntax do-notation

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
4. **`std/monad-control.ssc` expansion** — `untilM`, `iterateWhileM`.
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

1. **Sealed-trait extension dispatch in the interpreter.**  A
   `Right(…)` value has typeName `"Right"`, but `extension [A](fa:
   Either[E, A])` registers under `"Either"`.  Without a
   sealed-parent registry the interpreter misses the route — which
   is why `Bifunctor[Either]` and `MonadError[Either, …]` ship
   through helper functions in steps 5 and 6 of v1.1.  Need a
   sealed-trait → case-class index built at trait/class definition
   time, then `extensionDispatch` walks the parent chain.  ~100 LOC
   interpreter change.  JS already handles this via `_typeOf` from
   v1.1 step 4.

2. **`using`-clause auto-resolution.**  Polymorphic typeclass code
   (`def doIt[F[_]: Monad, A](fa: F[A])…`) requires `summon`
   resolution at the call site.  ScalaScript today only supports
   explicit-parameter passing, which is why every std helper is
   monomorphic (`pureList`, `pureOption`, `combineAll(xs, intSum)`
   rather than `pure[List](x)` / `xs.combineAll`).  Bigger lift
   across all three backends — needs a resolution pass in the
   typer that walks each `using` parameter, finds a unique
   in-scope `given` of the right type, and re-writes the call to
   pass it explicitly before lowering.  Defer until a user-facing
   API actually wants the polymorphic shape.

3. **`Term.Ascribe`.**  Type ascriptions like `(None: Option[Int])`
   aren't handled by the interpreter — it errors with `Cannot
   eval: Term.Ascribe`.  Worked around throughout the std lib and
   conformance tests by binding to a typed `val` first.  Smallest
   of the three: the interpreter just needs to evaluate the inner
   term and discard the type.  ~20 LOC.

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

