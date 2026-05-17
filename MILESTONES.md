# Milestones

Tracks work that is **not yet done**. As things land, move them out of here
(into git history) rather than ticking checkboxes — the file should always
read forward.

## v1.0 — WebSocket production-readiness

The WS stack landed in v0.7 covers the API surface across all three
backends (`onWebSocket(path) { ws => … }`, framing, fragmentation,
`ws.request`, virtual threads on JvmGen, NIO proxy on interpreter)
but still has known production gaps.  Listed in order of "stops
real problems" → "feature gaps" → "nice to have"; each sprint is a
session-sized chunk.

### Sprint 3 — API completeness

Common asks from real apps that aren't covered today.

10. **`Sec-WebSocket-Protocol` subprotocol negotiation.**  Pick from
    the client's offered list, echo on the 101.  Without this
    `socket.io` / `graphql-ws` clients refuse to connect.  ~30 LOC × 3.
11. **Built-in `WsRoom` type.**  Thread-safe registry + `broadcast(msg)`
    so every chat demo doesn't reinvent `var clients =
    List[WebSocket]()` (and forget the synchronisation).  ~80 LOC × 3.
12. **`ws.request.cookies: Map[String, String]`.**  Parse `Cookie:`
    header into a map at upgrade time, parallel to what REST
    handlers already see.  ~20 LOC × 3.

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

## v0.7 — Reusable libraries and packaging

A consumer should be able to depend on a third-party `.ssc` library —
component pack, REST middleware, layout kit — without vendoring its
files into their own tree.  The steps are ordered so each one is
useful in isolation and unblocks the next.

1. **Registry** *(future)*.  Central index (`registry.scalascript.io`)
   with semver resolution, lock file (`ssc.lock`), publish/yank
   workflow.  Weeks of work; only worth opening once the surface
   above is well-trodden.  Out of scope for v0.7.

## v0.9 — Standard component pack (`std/ui/*`)

The component convention is in place (`object Foo { val css, val js,
def render(...) }`), the distribution surface lands in v0.7, and
v0.8 makes them W3C Custom Elements.  v0.9 fills the gap that every
real `.ssc` app hits: a curated set of components, shipped as a
single `.sscpkg` (or via URL imports against a stable tag), so
nobody re-implements yet-another-`Button.ssc` from scratch.

Layout one file per component under `std/ui/`, all using `scope(...)`
on bare class names, all reachable through a top-level aggregator
`std/ui/index.ssc` so consumers can do
`[Button, Card, FormInput as Input](std/ui)`.

Tier 1 — **forms** (the highest-leverage gap right now)

  - **`Input`** — labelled text input with optional helper text and
    error state.  Renders `<label><span/><input/><small/></label>`;
    attributes: `name`, `label`, `type` (text/email/password/url/
    number/tel), `value`, `placeholder`, `required`, `helper`, `error`.
  - **`Textarea`** — same shape as `Input`, multi-line.
  - **`Select`** — dropdown over a `List[(String, String)]` of
    `(value, label)` pairs; honours `selected`.
  - **`Checkbox`** / **`Radio`** / **`RadioGroup`** — boolean
    inputs.  Checkbox is single, RadioGroup builds a `<fieldset>`
    around N `Radio` siblings with shared `name`.
  - **`FormGroup`** — vertical stack with consistent spacing /
    section header; the parent block most apps wrap forms in.
  - **`SubmitButton`** — variant of the existing `Button` with
    `type="submit"`, loading state, and disabled-while-pending JS.

Tier 2 — **layout primitives** (the things every page needs once)

  - **`Stack`** / **`Row`** — flex containers with a `gap` prop;
    `Stack` is vertical, `Row` is horizontal.  Cover ~90 % of
    layout needs without a grid framework.
  - **`Grid`** — n-column responsive grid (`columns`, `gap`).
  - **`Container`** — max-width centred wrapper with consistent
    horizontal padding.  The `<main>` of a typical page.
  - **`Divider`** — `<hr>` with theme spacing.
  - **`Spacer`** — flex-fill or fixed-height gap, for when `gap`
    isn't enough.

Tier 3 — **navigation**

  - **`NavBar`** — `Layout`-style header with brand + nav links +
    optional right-aligned slot.  Generalised version of what
    `examples/site/components/layout.ssc` already inlines.
  - **`Breadcrumbs`** — links with `/`-separator from a `List[(Path,
    Label)]`.
  - **`Tabs`** — controlled (active index as prop) and uncontrolled
    (`val js` swaps `aria-selected` on click).  Tabs+content panels
    in one component, with `data-tab="<id>"` wiring.
  - **`Pagination`** — page-number row with prev/next, capped
    visible-page count.
  - **`Sidebar`** — sticky vertical nav for docs / dashboards.

Tier 4 — **feedback / overlays**

  - **`Toast`** + `toast.show(...)` JS helper — auto-dismiss
    notifications stacked top-right.  Server-render-only stub +
    client-side stack.
  - **`Modal`** — open/close via `open` boolean; click-outside
    closes; `Esc` closes; focus-trap inside.
  - **`Drawer`** — slide-in side panel.  Same controls as Modal.
  - **`Spinner`** + **`ProgressBar`** — pure CSS, no JS.
  - **`Skeleton`** — animated placeholder for loading content.

Tier 5 — **data display**

  - **`Table`** — headers + rows from `List[(label, getter)]` and
    `List[T]`.  Optional sortable columns (client-side `val js`).
  - **`List`** (linear list — name TBD to avoid clashing with
    `scala.List`) — vertical item list with optional dividers and
    interactive rows.
  - **`DataGrid`** — Table + pagination + filter row.  Probably
    builds on `Table` rather than replacing it.
  - **`KeyValue`** — `<dl>` pair list for "name: value" displays.

Tier 6 — **content / typography**

  - **`Code`** — `<pre><code>` with optional language hint;
    syntax highlighting is out of scope, but the markup + spacing
    is.
  - **`MarkdownBlock`** — render a Markdown string via commonmark
    server-side.  Useful for CMS-style content fields.
  - **`Quote`** — styled blockquote with optional citation.

Tier 7 — **widgets**

  - **`Avatar`** — circular image with fallback initials, sizes
    (`sm` / `md` / `lg`).
  - **`Badge`** — small pill, neutral / info / warn / danger / success.
  - **`Tooltip`** — CSS-only hover popover (no JS for the basic
    version).
  - **`Accordion`** — collapsible sections, multi-vs-single open
    mode prop.
  - **`Dropdown`** — button + popover menu; same JS bones as the
    tab switcher.

Tier 8 — **theming infrastructure**

  - **CSS variables** (`--ui-color-primary`, `--ui-radius-md`,
    `--ui-font-body`, …) defined in `std/ui/theme.ssc`; every
    other component reads from them so theming is variable-swap.
  - **`ThemeProvider`** — sets variables on `:root` from a Map.
  - **Dark-mode toggle** — `prefers-color-scheme` defaults + a
    user override via `data-theme="dark"`.
  - **Reset / normalise stylesheet** shipped as `std/ui/reset.css`.

Cross-cutting work the pack motivates:

  - **`ssc test`** — a runner for component-level unit tests
    (`tests:` block in front-matter or a sibling `*-test.ssc` file).
    Without this every component ships untested.
  - **Component preview** — `ssc preview <file>` opens a browser
    page that renders the component with each declared `variants:`
    set.  Storybook-lite.  Optional but pays back the cost the
    first time a designer needs to see all 47 Button states.
  - **A documentation page** for the pack (`examples/std-ui/`)
    that builds via `ssc build` and renders every component with
    every variant, with the source visible.

Effort estimate: tiers are independent and roughly half-day each
(one focused session per tier).  Whole pack is ~1–2 weeks of
component work at one tier per day plus 2–3 days for `ssc test`
and the preview tooling.  Defer until at least one consumer of the
existing convention asks "where do I get a Button?".

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

## v1.1 — Standard type-class hierarchy

Land a small, principled std library of FP type classes with instances
for the built-in types (`List`, `Option`, `Map`, `Either`, `Tuple2`).
All declarations use existing Scala 3 `trait` + `given` machinery — no
new keywords, no new parser syntax, no divergence from the "Scala 3
dialect" brand.  The interpreter / JS / JVM all already support typeclass
dispatch through `summon[…]` (covered by the `typeclass` conformance
test), so this is mostly **library code in `std/`** plus a few inference
ergonomics on top.

### Hierarchy — minimal and sufficient

Ten classes, organised into three lanes.  Picked to cover ~99% of real
FP user code without academic bloat; explicitly *excludes* Category /
Arrow / Profunctor (deferred — see end of section).  `Eq`, `Order`, and
`Show` already exist in the codebase via the `typeclass` conformance
test and are not re-implemented here.

    Algebraic lane           Functor / effect lane          Container lane

      Semigroup                     Functor                    Foldable
          │                            │                          │
          ▼                            ▼                          ▼
        Monoid                    Applicative              Traversable
                                       │                  (extends Functor +
                                       ▼                   Foldable; `traverse`
                                   Selective               takes Applicative)
                                       │
                                       ▼                       Bifunctor
                                     Monad                  (standalone, for
                                       │                     Either / Tuple2)
                                       ▼
                                  MonadError

### Steps (priority order — each useful in isolation)

1. **`Semigroup` + `Monoid`.**  Foundation for fold / concat /
   `combine` / `empty`.  Instances for `String`, `List`, `Int`-sum,
   `Int`-product (newtype style), `Option[A: Semigroup]`, `Map[K, V:
   Semigroup]` (last-write-wins is too lossy; merge values).  ~half a
   day.

2. **`Functor`, `Applicative`, `Monad`.**  Core abstractions:

       trait Functor[F[_]]:
         extension [A](fa: F[A]) def map[B](f: A => B): F[B]

       trait Applicative[F[_]] extends Functor[F]:
         def pure[A](a: A): F[A]
         extension [A](fa: F[A]) def ap[B](ff: F[A => B]): F[B]

       trait Monad[F[_]] extends Applicative[F]:
         extension [A](fa: F[A]) def flatMap[B](f: A => F[B]): F[B]

   Instances: `List`, `Option`, `Either[E, *]`, `Function1[X, *]`,
   `Tuple2[E, *]` (Writer-style).  ~1 day.

3. **`Foldable` + `Traversable`.**  The practical win in real code.

       trait Foldable[F[_]]:
         extension [A](fa: F[A])
           def foldLeft[B](z: B)(op: (B, A) => B): B
           def foldRight[B](z: B)(op: (A, B) => B): B
           def toList: List[A]

       trait Traversable[T[_]] extends Functor[T] with Foldable[T]:
         extension [A](ta: T[A])
           def traverse[F[_]: Applicative, B](f: A => F[B]): F[T[B]]
           def sequence[F[_]: Applicative]: F[T[A]]

   Instances for `List`, `Option`, `Map` (by-value), `Either` (right-
   biased).  ~1 day with conformance tests.

4. **`Selective` (between Applicative and Monad).**  Mokhov/Lukyanov:
   `select :: f (Either a b) -> f (a -> b) -> f b` — conditional
   effects while keeping the call graph statically inspectable.
   Smaller user base; ship after the core lane.  ~half a day.

5. **`MonadError`.**  Adds `raise[A](e: E): F[A]` and
   `handleError[A](fa: F[A])(f: E => F[A]): F[A]` to `Monad`.
   Instances for `Either[E, *]` and `Option` (with `E = Unit`).
   Unifies error-handling vocabulary across types.  ~half a day.

6. **`Bifunctor`.**  Two-position functor for `Either` and `Tuple2`:

       trait Bifunctor[F[_, _]]:
         extension [A, B](fab: F[A, B])
           def bimap[C, D](f: A => C, g: B => D): F[C, D]
           def leftMap[C](f: A => C): F[C, B] = bimap(f, identity)

   Closes the "I have an `Either[Error, User]`, I want to map the
   error half" use case that comes up in every REST handler.  ~half
   a day.

7. **Inference ergonomics (no new keywords).**  Two pragmatic wins
   while keeping vanilla Scala 3 syntax:

   - **`pure[F](x)` / `empty[F]` without explicit `summon`.**  Today
     users write `summon[Applicative[F]].pure(x)`; add top-level
     shortcut defs in the std prelude so `pure[List](42)` Just Works.
   - **`given` defaults wired into the default import set** so
     `xs.traverse(f)` on a `List` finds the instance without an
     explicit import (similar to how `math.sqrt` is in scope today).

   *Explicitly out of scope:* new `typeclass` / `instance` keywords,
   do-notation desugaring, type defaulting.  Scala 3's `trait` +
   `given` are sufficient — diverging from them costs more than it
   earns.

### Explicitly deferred — `Category` / `Arrow` / `Profunctor`

Theoretically elegant but practically zero pull in user code without
profunctor-encoded optics, which we've decided to keep concrete.  In
Scala, function composition is already `f andThen g`; `***` / `&&&` /
`dimap` surface once in a blue moon.  Holding these for a possible
future "Optics 3 — profunctor rewrite" milestone only if a concrete
consumer surfaces; until then, dead weight.

### Effort

Steps 1-6: ~4 days of typeclass + instance code; ~1 day of conformance
tests.  Step 7 (ergonomics): half a day.  Roughly **a week end-to-end**
across three backends.  Each step ships as its own PR, mergeable in
sequence.

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

## v1.3 — Runtime upgrades: real-thread Async, persistence, Async-integrated WS

Staged additions that build on the v0.8 Async / signals stack.  Each
lands as its own merge so the suite stays green between steps.
(Stages 1 and 2 — fine-grained reactive `Signal` / `computed` /
`effect`, and real-thread `runAsyncParallel` handler — landed; see
git history.)

1. **`Storage` effect.**  `Storage.get(key)` / `Storage.put(key, v)` /
   `Storage.remove(key)` / `Storage.keys()` against a JSON-backed
   file (`./ssc-storage.json` by default, override via
   `SSC_STORAGE_PATH`).  Lets REST + auth demos outlive a process
   restart without dragging in JDBC.  Handlers `runStorage(body)` and
   `runEphemeralStorage(body)` for the file-backed and in-memory
   variants; the latter is what the conformance suite uses so tests
   stay hermetic.

2. **`Async`-integrated WebSocket.**  Lift the `ws.onMessage(cb)` /
   `ws.onClose(cb)` callback surface into suspending `Async`
   operations: `Async.recvFrom(ws)` and `Async.closed(ws)`.  A
   WebSocket handler written as `runAsync { while (...) { … } }`
   reads linearly instead of the inverted-control callback chain.
   Builds on top of the existing `WsRoutes` plumbing in
   `compiler/src/main/scala/scalascript/server/`.

3. **Node target for `runAsyncParallel`.**  Today the Node JS runtime
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
