# Milestones

Tracks work that is **not yet done**. As things land, move them out of here
(into git history) rather than ticking checkboxes — the file should always
read forward.

## Backend SPI v0.1 — plugin architecture

A standalone design document and 9-phase migration plan that
abstracts the current four backends (`JvmGen`, `JsGen`,
`ScalaJsBackend`, `Interpreter`) behind a stable plugin SPI.
After landing, adding a new target (WASM, native, .NET, Python,
SQL frontend, …) is one plugin JAR or subprocess instead of a
modification to `core`.

- **Design:** [`docs/backend-spi.md`](docs/backend-spi.md) — 17
  sections, source of truth.  Locks all architectural decisions
  (effects, intrinsics, multi-language blocks, block references,
  prelude contributions, versioning, JSON+MsgPack wire formats,
  core-boundary minimisation).
- **Tracking issue:** [#36](https://github.com/sergey-scherbina/scalascript/issues/36)
  — parent with the 9-phase checklist.  Phase issues
  [#26–#33, #35](https://github.com/sergey-scherbina/scalascript/issues/26).
- **Estimate:** ~8–11 working days total.

### Phase summary

1. **#26** — Module split & SPI skeleton  (M, ~1d)
2. **#27** — IR + JSON/MsgPack codec  (M, ~1d)
3. **#28** — Effect lowering as a core pass  (L, ~1–2d)
4. **#29** — Capabilities + validation  (S, ~0.5d)
5. **#30** — Convert existing backends to plugins  (M, ~1–1.5d)
6. **#31** — Out-of-process plugin loader (stdio JSON/MsgPack)  (M, ~1–1.5d)
7. **#32** — CLI ergonomics for plugin management  (S–M, ~0.5–1d)
8. **#33** — Docs & sample external plugin  (S, ~0.5d)
9. **#35** — Extract bundled SourceLanguage plugins (`html`, `css`, `scala-source`)  (M–L, ~1.5–2d)

After Phase 9, `core` knows only Markdown + `scalascript`/`ssc`;
`html`, `css`, and `scala` blocks plus the `html"…"`/`css"…"`
interpolators live in bundled plugins that consume the same SPI as
any third-party plugin.

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

## v0.8 — Web Components target (`ssc emit-wc`)

Make SSC components consumable as standards-track W3C Custom Elements
so a `.ssc` component is a first-class HTML element usable in any
framework (or none) — independent of the ScalaScript toolchain on the
consumer side.

Today our `object Card { val css, val js, def render(...) }` runs on
the **server**: render returns a String, JS is a side-loaded handler
that finds elements via querySelector, `scope(...)` simulates style
isolation through class-name suffixes.  A W3C Custom Element runs on
the **client**: a `class CardComponent extends HTMLElement` with
lifecycle callbacks, real Shadow DOM, attribute-driven re-render.
v0.8 emits the second from the first.

What it does:

- **New command `ssc emit-wc <file.ssc> [-o name.js]`** generates one
  JS bundle per component (or one bundle per file with multiple
  components):

      class CardComponent extends HTMLElement {
        static get observedAttributes() { return ['title', 'body']; }
        connectedCallback() {
          const shadow = this.attachShadow({mode: 'open'});
          shadow.innerHTML = `<style>${Card.css}</style>` +
            Card.render(this.getAttribute('title'), this.getAttribute('body'));
        }
        attributeChangedCallback() { this.connectedCallback(); }
      }
      customElements.define('card-component', CardComponent);

  Consumer drops `<script type="module" src="card.js"/>` into any
  HTML page and uses `<card-component title="…" body="…"/>` like a
  native tag.

- **Tag-name convention.**  PascalCase object name → kebab-case + a
  required dash (W3C spec mandates a hyphen).  `object Card` →
  `card-component`; `object NavBar` → `nav-bar-component`.  Override
  via a `tagName: "my-card"` field on the object.

- **Attributes-as-props.**  HTML attributes are string-only by spec.
  `def render(title: String, count: Int, active: Boolean)` translates
  to `getAttribute('title')`, `Number(getAttribute('count'))`,
  `getAttribute('active') !== null`.  Booleans use presence semantics
  (`<my-tag active>` vs absent).  Complex props (List, case classes)
  pass through `data-` attributes parsed as JSON or via JS properties
  set imperatively on the element.

- **Real Shadow DOM scoping.**  Inside a Custom Element the
  `<style>` is encapsulated, so `scope(...)` becomes optional — the
  shadow boundary already prevents leaks.  Components that target
  *only* `emit-wc` can drop `scope()` and write bare class names.
  Mixed-target components keep `scope()` so the same source works
  both as a server-rendered fragment (where there's no shadow) and
  as a Custom Element.

- **Slots.**  A render argument named `children: String` maps to a
  `<slot></slot>` in the Custom Element template, letting callers
  write `<card-component><h2>Title</h2><p>Body</p></card-component>`.

What stays the same:

- `def render(...)` is still the single source of truth for the
  template.  `ssc render` / `ssc build` use it server-side; `ssc
  emit-wc` wraps the same template in a Custom Element class.
- `collectCss` / `collectJs` still aggregate for whole-page builds.
- Existing component examples (Button, Card, Alert, Counter)
  unchanged — they gain a new emission target without source edits.

Open questions to resolve during implementation:

- **SSR + hydration story.**  If a page is server-rendered AND
  contains `<card-component>` tags, the inner shadow tree is re-
  rendered on the client when the script runs, replacing the SSR
  output.  Need a convention for `connectedCallback` to detect
  already-rendered light DOM and adopt it instead of overwriting.
- **Typed-prop sugar.**  Worth adding a `props: Map[String, Type]`
  declaration to the component so attribute parsing is generated,
  or stick with manual `getAttribute` calls in user code?
- **Interop with React/Vue events.**  Custom Elements emit DOM
  events.  Need a convention for `dispatchEvent(new CustomEvent(...))`
  so framework wrappers can bind to them.

Effort: 3–7 days, depending on how deep we go on the open questions.
Defer until a concrete use case demands real client-side
interactivity beyond `val js` querySelector handlers.


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

## v0.9.1 — Import ergonomics: directory-as-index

Make `[Name](./pack)` resolve to `./pack/index.ssc` when the path is
a directory.  Today `ImportResolver.resolve` returns the raw path; if
it points to a directory all three backends fail with "not a file".

Single-spot change: after the resolver computes the final `os.Path`,
if `os.isDir(p)` then return `p / "index.ssc"`.  ~20 LOC + one
conformance test that imports a folder.  Unblocks `std/ui/index.ssc`.

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
  - **`Card`** — header / body / footer slot trio.
  - **`Alert`** banners (already present as an example; promote).
  - **`Tag` / `Chip`** — closable inline tokens (close-button slot).
  - **`Switch`** — toggle styled checkbox.
  - **`RangeSlider`** — single + dual-handle.
  - **`Tree`** — collapsible hierarchical view (built on Accordion
    primitives).
  - **`Carousel`** — scroll-snap based, no JS for the default mode.
  - **`Lightbox`** — image viewer overlay.
  - **`Stats`** — dashboard-style number tile with delta indicator.
  - **`Empty`** — no-content placeholder with icon + CTA.

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

The WS stack landed in v0.7 covers the API surface across all three
backends (`onWebSocket(path) { ws => … }`, framing, fragmentation,
`ws.request`, virtual threads on JvmGen, NIO proxy on interpreter)
but still has known production gaps.  Listed in order of "stops
real problems" → "feature gaps" → "nice to have"; each sprint is a
session-sized chunk.

### Sprint 4 — observability

13. **Structured connect/disconnect/error logs** — client IP, route,
    duration, close code.  ~30 LOC × 3.
14. **`metrics()` native** — `wsActive`, `wsMessagesIn`/`Out`,
    `wsBytesIn`/`Out` exposed as a map for scraping.  ~50 LOC.
15. **HTTP access log** for the proxy-forwarded path.  Currently
    silent.  ~20 LOC.

### Sprint 5 — architectural debt

Defer until 1-4 are settled and a real workload demands them.

16. **Full NIO HTTP on JvmGen.**  Today the WS proxy sits in front
    of a JDK `HttpServer`, so every HTTP request opens a fresh
    `Socket` to localhost.  Replacing the HTTP stack with our own
    NIO server would fold the proxy in, remove the loopback hop,
    and unify the threading model with the interpreter.  ~1500 LOC.
17. **Loom-only or NIO-only — pick one for both JVM backends.**
    Maintaining two parallel models (NIO for interpreter,
    Loom+blocking for JvmGen) is dead weight if neither has a real
    edge.  Worth re-deciding after (16).
18. **`permessage-deflate` (RFC 7692).**  5-10× compression on
    JSON-heavy WS workloads.  Not worth the complexity until a
    real app needs it.  ~200 LOC × 3.

### Sprint 6 — WS convenience helpers

Small quality-of-life additions noticed while running through
Sprint 3.  Each is meaningfully complete on its own.

19. **Per-route `maxConnections`.**  `setMaxWsConnections` is
    process-wide.  Real apps want `/chat` capped at 1000 and
    `/admin` capped at 5.  Add the cap as a fourth `onWebSocket`
    arg or via a `WsRoute` builder.  ~40 LOC × 3.
20. **Per-connection rate limit.**  Cap incoming messages/sec so
    one client can't burn the server's CPU.  Bound a sliding-window
    counter on `WsConnection`; on overflow close 1008 ("policy
    violation").  ~30 LOC × 3.
21. **Auth helper at upgrade time.**  `onWebSocket("/x", auth =
    bearer { token => validate(token) }) { … }` — current users
    have to inspect `ws.request.headers("authorization")` and
    `ws.close(1008, "")` manually.  ~30 LOC × 3.
22. **`ws.id: String`.**  Stable per-connection identifier for
    logs / traces.  UUID-v4 generated at upgrade.  Trivial; pays
    back the first time someone wants to grep logs for a single
    session.  ~10 LOC × 3.
23. **`ws.subprotocol: String`.**  The protocol the server chose
    during negotiation.  Currently inspectable via
    `ws.request.headers("sec-websocket-protocol")`, but that's the
    client's full *offer* list, not the server's selection.
    Surface the chosen value explicitly.  ~10 LOC × 3.
24. **Close-handshake echo wait.**  RFC SHOULD wait briefly for
    the peer's close-echo after sending our Close before tearing
    down the channel.  Currently we just `closeNow`; strict
    clients may complain.  Half a line of `scheduler.schedule(...)`.

## v1.2 — Auth follow-up: combined example + WebAuthn / passkeys

The v0.6 auth surface landed primitives (sessions, CSRF, JWT HS256/RS256,
server-side store, Basic, OAuth, TOTP, password hashing, rate limiting)
but two ergonomic gaps remain:

1. **`examples/auth-full.ssc`** — end-to-end demo stitching all v0.6
   primitives in one file: signup with `hashPassword`, login with
   `verifyPassword` + `rateLimit` on the endpoint, post-login session
   via `withSession`, `csrfValid` on every state-changing POST, a
   `/profile` route gated on `req.session`, optional 2FA via
   `totpValid`, and an `/api/me` route protected by `req.jwtClaims`.
   Acts as the canonical "how do I wire these together" reference —
   today's `auth-demo.ssc` only shows cookie+CSRF, `oauth-demo.ssc`
   only shows OAuth.  Small commit (~150 LOC), zero new compiler
   features.

2. **WebAuthn / passkeys** — modern standard for passwordless login
   (FIDO2 / WebAuthn Level 2).  Server side runs entirely in the
   existing serve runtime; browser side uses `navigator.credentials
   .create()` / `.get()` against a small JS shim emitted alongside
   the SPA target.  Stages:

   2a. **Challenge issuance + credential store.**  `webauthnChallenge()`
       returns a fresh base64url challenge bound to a session.
       `WebAuthnStore` (in-memory by default, ConcurrentHashMap keyed
       by userId) holds the `(credentialId → publicKey)` pairs.

   2b. **Registration verification.**  Parse the browser's
       `AttestationResponse` (CBOR-encoded `attestationObject` +
       `clientDataJSON`), extract the COSE public key, verify the
       challenge matches and `origin` / `rpId` match.  Support
       packed / none attestation formats (covers Apple, Yubikey,
       Android).  Store credentialId + publicKey + signCount.

   2c. **Authentication verification.**  Parse `AssertionResponse`
       (`authenticatorData` + `clientDataJSON` + `signature`),
       verify signature against stored publicKey, check `signCount`
       monotonicity, return logged-in `userId`.

   2d. **`examples/webauthn-demo.ssc`** — full enrol + sign-in flow
       driven by a small in-page `<script>` calling
       `navigator.credentials`.  Same `.ssc` works on `ssc emit-spa`
       and `ssc run`; the browser-side JS is emitted inline.

   2e. **e2e harness `e2e/webauthn-smoke.sc`** — mocks an authenticator
       (an in-process ECDSA P-256 keypair) and walks register → auth →
       counter-replay-rejected.  No real authenticator hardware
       needed in CI.

Approx scope: 1 is ~2h; 2a-2c are ~2 days (CBOR parser + signature
verify on three backends); 2d-2e ~1 day.

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

- **Phase E — full NIO HTTP migration** *(Sprint 5.16, ~2 weeks)*.
  Replaces the JDK `HttpServer` + WS-proxy pair with a single
  NIO selector loop owning both HTTP and WS state machines.
  Eliminates the loopback hop, unifies the threading model
  across interpreter and JvmGen, and is what `permessage-deflate`
  (Sprint 5.18) would build on top of.  Deferred until at least
  one Phase D item proves the JDK HttpServer is genuinely the
  bottleneck.

Total: ~4 weeks of focused work for Phases A-D (after which the
HTTP/WS stack is genuinely production-ready), Phase E as a
follow-up architectural pass when scale demands it.

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

### Phase 1 — Local actors (~1 week)

Core process model.  No supervision, no distribution — just spawn,
send, receive on one node.

  - **`spawn(behavior): Pid`** — creates an actor; behavior is
    `Behavior[M]` (a function `M => Behavior[M]` returning `Same`,
    `Stopped`, or `Become(next)`).  Returns an opaque `Pid`.
  - **`spawn_link(behavior): Pid`** — atomic spawn-and-link so
    there's no race window where the child dies before the parent
    calls `link`.
  - **`pid ! msg`** — fire-and-forget send; never blocks, never
    fails (send to dead PID is a no-op, Erlang semantics).
  - **`receive { case … }`** — FIFO head-only pattern match.  Did
    not match → message goes to dead letters (logged).  Selective
    receive (scanning the mailbox) is **explicitly out of scope**;
    state-machine behaviour is expressed through `Become(next)`,
    which switches the pattern set for the next receive.
  - **`receive (timeout = 1000) { case … }`** — `None` on timeout;
    otherwise the matched value.
  - **`self: Pid`** — current actor's PID.
  - **`call(pid, msg, timeout = 5000): Reply`** — request/reply
    helper (the `gen_server:call/2` pattern), wired through a
    one-shot internal mailbox so user code doesn't reimplement it
    every time.
  - **`exit(pid, reason)`** — kill another process by PID; needed
    by supervisors and for clean shutdown.

  Mailboxes per backend:
    - **JVM (Loom):** virtual thread per actor + `LinkedBlockingQueue`.
      `receive` is a blocking `take()` — Loom parks cheaply.
    - **Interpreter (NIO):** continuation per actor on the existing
      event loop + `ArrayDeque`.  `receive` is a suspension point in
      the `Async` effect.
    - **JS:** microtask-scheduled coroutine + array mailbox.  Same
      suspension semantics through `Async`.

  New `Actor` effect, parallel to `Async`; internally uses Async's
  suspension machinery on each backend.

  Conformance: ping-pong; state machine via `Become`; timeout
  receive; dead letter on unmatched; `Stopped` exit; `exit(other,
  …)` from outside.

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

