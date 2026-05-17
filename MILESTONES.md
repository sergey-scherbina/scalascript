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

### Stage 5.4 — `std.http` / `std.ws` / `std.auth` extraction → DEFERRED

`Backend.intrinsics` ships as the API but no platform package routes
through it yet.  Hardcoded `nativeP("serve")` etc. stay in
`Interpreter.scala`; `route`/`serve` keyword recognition stays inline
in `JvmGen`/`JsGen`.  Full extraction needs an `extern` parser
modifier + Normalize pass extension + per-codegen emission swap —
4-6 iterations of work.  Concrete proposal:

- **5+/A — Intrinsic plumbing.**  Add the `extern` parsing, the
  `ExternCall` IR-node populated, the intrinsic-table consultation
  at emit time.  Migrate ONE intrinsic (e.g. `Console.println`) end-
  to-end as a proof point.  ~1 iteration.
- **5+/B — `std.http` extraction.**  Move HTTP through the pipeline
  established in 5+/A.  ~2 iterations.
- **5+/C — `std.ws`, `std.auth`, `std.fs`, `std.crypto`.**  Same
  pattern.  ~1 iteration each.

The transitional `backend-interpreter dependsOn backend-js` stays
in `build.sbt` (`server/WebServer.scala` imports `codegen.JsGen` for
the SPA runtime preamble) until 5+/A lands.

### Stage 9+ — `html` / `css` SourceLanguage extraction → DEFERRED

`SourceLanguageRegistry` ships as the API, and `backend-scala-source/`
is a registered no-op skeleton, but the actual extraction of
`html` / `css` from `JvmGen`/`JsGen`/`Interpreter` is also multi-
iteration:

- **9+/A — Registry consumption.**  Parser routes unknown fence tags
  through `SourceLanguageRegistry.lookup`; typer accepts
  `SymbolExport` from plugins.
- **9+/B — `backend-html` extraction.**  Move `containerTagNames` +
  `nativeP("div")` block + `html"…"` interpolator + `_Raw` emission
  into a `backend-html/` plugin with `Html` type in `preludeFiles`.
- **9+/C — `backend-css` extraction.**  Same shape as 9+/B.

After 9+/B and 9+/C the spec §17 acceptance bullet — "no
`if lang == "html" || lang == "css"` anywhere in core" — becomes
testable.  Until then `Lang.isStringBlock` / `Lang.isParseable`
stay as they are.

### Stage 6+ — Out-of-process protocol completions

Stage 6 ships `stdio-json` framing + sync round-trip + describe /
compile / shutdown methods.  Out of scope and reserved:

- **`stdio-msgpack` framing.**  Same case classes round-tripped via
  upickle's `writeBinary`/`readBinary`.  ~half a session.
- **InteractiveBackend over subprocess.**  Needs concrete
  `ir.Value` wire shape (Stage 5+/A territory).
- **HostCallback intrinsic variant.**  Out-of-proc backends call
  back into core via a named host callback core dispatches.  Wired
  up alongside 5+/A.
- **SourceLanguage role through subprocess.**  Ditto — wired
  alongside 9+/A.

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

18. **Middleware composition.**  Today every handler is
    autonomous; cross-cutting concerns (auth check, request
    logging, request-id, timing) get pasted into each one or
    extracted into helper `def`s that the handler calls.  Add a
    first-class middleware shape:

    ```
    def authMw(handler: Request => Response): Request => Response =
      req => if validate(req) then handler(req) else Response.status(401)

    route("GET", "/users")(authMw { req => ... })
    route("GET", "/admin")(authMw andThen logMw { req => ... })
    ```

    Just function composition; no runtime change.  What's missing
    is the **convention** and a couple of stock middlewares in
    std: `withRequestId`, `withTiming`, `withRequestLog`, plus
    the existing CORS (Tier 4 #13) and Cache (Tier 4 #15) helpers
    fitting the same shape.  ~50 LOC of std helpers + docs.

19. **Server-Sent Events (SSE) helper.**  Builds on Tier 4 #11
    (streaming responses) but with the right Content-Type and
    framing so browsers' `EventSource` works without user
    boilerplate.  Shape:

    ```
    route("GET", "/events") { req =>
      sse(req) { stream =>
        stream.send(event = "tick", data = "1")
        stream.send(data = "raw payload")
        stream.close()
      }
    }
    ```

    Sets `Content-Type: text/event-stream`, `Cache-Control:
    no-cache`, `Connection: keep-alive`; writes `event: …\ndata:
    …\n\n` framing; flushes after each `send`.  ~80 LOC × 3.
    Hard-blocked on Tier 4 #11 — without streaming responses,
    SSE collapses to a one-shot buffered write.

20. **Request validation helpers.**  Today every handler
    re-implements `if req.form.contains("email") then …` with
    ad-hoc string error messages.  Add a small typed validator
    surface:

    ```
    val email   = req.require[String]("email")             // 400 if missing
    val page    = req.optional[Int]("page").getOrElse(1)
    val rating  = req.requireRange("rating", min = 1, max = 5)
    ```

    `require[T]` returns the value or short-circuits with
    `Response.status(400, "missing/invalid: <field>")`.
    Validation builders compose into an accumulating error map
    when the handler wants to return all problems at once
    (`req.validate { ... }` block).  ~150 LOC × 3 — mostly a
    typed-coercion table covering `String` / `Int` / `Long` /
    `Double` / `Boolean` / `Option`, plus the short-circuit
    semantics through the existing Free-monad / Async layer.

21. **Built-in health / readiness route.**  Convention: register
    `GET /_health` and `GET /_ready` returning `200 {"status":
    "ok"}` automatically when `serve(port)` is called, unless
    the user has registered a route with the same path.  Real
    apps inevitably hit Kubernetes / load-balancer probes and
    re-implement these.  ~20 LOC × 3.

22. **Indexed access on `Any`-typed JSON values.**  Follow-up to
    Tier 5 #17.  `jsonParse(s)` returns `Any` because the result
    varies (`Long` / `String` / `Map` / `List` / …); the
    interpreter and JS dispatch `obj("name")` dynamically, but
    the JVM Scala compiler rejects it — `Any` has no `apply`.
    Today users have to bind to `val m: Map[String, Any] = ...`
    explicitly (interpreter/JS only — JVM still rejects the
    implicit cast).  Three viable shapes:

    a. **Runtime `lookup(v, key)` helper.**  `lookup(obj, "name")`
       — pattern-matches at runtime, returns `Any`.  Cheapest;
       ugly call site.  ~30 LOC × 3.
    b. **JvmGen lowering.**  Detect `obj(k)` where `obj`'s
       inferred type is `Any`, emit `_lookup(obj, k)` instead of
       `obj.apply(k)`.  Keeps user syntax `obj("name")` working;
       requires the typer to flow `Any`-types into JvmGen.
       ~150 LOC.
    c. **`JsonValue` wrapper type.**  `jsonParse` returns a
       sealed `JsonValue` with `apply(key: String): JsonValue`,
       `asString`, `asInt`, `asList`, `asMap`, etc.  Most typed,
       most ergonomic, biggest API surface.  ~300 LOC × 3 plus
       conformance for the new type.

    Recommended: start with (a) so users have a working escape
    hatch, then add (c) for idiomatic access when v1.5 Tier 5 #20
    (typed request validation) clarifies which JSON-typed shapes
    matter in practice.  (b) only if (a) and (c) prove
    insufficient.

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
    - D′.4 — Request validation surface (#20).
    - D′.5 — Built-in `/_health` / `/_ready` routes (#21).
    - D′.6 — Indexed access on `Any`-typed JSON values (#22),
      follow-up to D′.1.  Start with the `lookup(v, key)`
      runtime helper (option a), revisit `JsonValue` wrapper
      type (option c) once D′.4 clarifies which typed JSON
      shapes matter in practice.

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

### Phase 3 — Distributed via WS (~1 week)

Location-transparent PIDs and remote sends, riding the existing WS
client stack (v1.5 Tier 3, prerequisite).

  - **Node identity** `name@host:port`; PID is `<node>.<localId>`,
    serializable.  Self-node's name is set at startup
    (`startNode("logger@localhost:9100")`).
  - **`connectNode(url, token = ..., serializer = Json | Binary)`**
    — opens one long-lived WS channel between this node and the
    peer.  PIDs are multiplexed over the channel; never one socket
    per actor (fan-out would die).
  - **`pid ! msg`** transparently routes: if PID's node is local,
    queue.put; if remote, serialize and frame onto the per-peer
    channel.
  - **Serializer choice per link** — JSON via uPickle (default,
    human-debuggable, cross-language) or binary uPickle
    (compact, faster).  Both already in deps.
  - **`register(name, pid)` / `whereis(name): Option[Pid]`** —
    per-node atom registry.  Cross-node lookup is explicit:
    `whereis("node2@x:9100", "logger")`.
  - **Heartbeat** on each peer channel.  Loss of channel → all
    PIDs from that node are considered dead; queued links /
    monitors fire `Down(...) / Exit(...)`.
  - **Auth:** reuses existing WS auth (JWT or bearer token); no
    new cookie scheme.  `connectNode(url, token = "...")`.
  - **Remote spawn is deliberately not supported** — functions
    don't serialize.  Pattern: send a `Make(args)` message to a
    locally-spawned process registered under a well-known name on
    the remote node, and let it spawn its own children.

  Conformance: 2-node ping-pong (`INT↔INT`); cross-backend
  (`INT↔JVM`); link across nodes; kill one node → `Down` fires on
  the other; serializer round-trip parity.

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

