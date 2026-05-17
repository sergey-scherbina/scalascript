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

### Sprint 2 — security / robustness hardening

7. **Connect/disconnect bench.**  10K concurrent WS + a mixed
   HTTP load to establish a baseline before the NIO migration.
   ~100 LOC.

### Sprint 3 — API completeness

Common asks from real apps that aren't covered today.

8. **`ws.sendBytes(Array[Byte])`.**  Only text is sendable now;
   binary frames are received but can't be sent.  ~10 LOC × 3.
9. **`ws.ping()` / `ws.onPong { … }`.**  User-side liveness probe.
   Useful for health-checked clients.  ~20 LOC × 3.
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

1. **`package` keyword** *(optional)*.  `package org.example.ui` at
   the top of a scalascript block puts its declarations under that
   dotted path so two libraries can each export `Card` without
   collision in the global namespace.  Mostly cosmetic once URL/dep
   imports
   are in place — alias parsing already solves the collision case
   for callers, and URL/dep resolution provides the uniqueness — but
   `package`-prefixed imports map cleanly to a future registry.
   Parser + 3 backends (`object org.example.ui.Card { … }` emit).
   ~1–2 days.  Defer until a concrete clash motivates it.

2. **Registry** *(future)*.  Central index (`registry.scalascript.io`)
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

Listed in order so each step is useful on its own and unblocks the next.

1. **Core: `Category`, `Functor`, `Applicative`, `Monad`.**  Standard
   shape:

       trait Functor[F[_]]:
         extension [A](fa: F[A]) def map[B](f: A => B): F[B]

       trait Applicative[F[_]] extends Functor[F]:
         def pure[A](a: A): F[A]
         extension [A](fa: F[A]) def ap[B](ff: F[A => B]): F[B]

       trait Monad[F[_]] extends Applicative[F]:
         extension [A](fa: F[A]) def flatMap[B](f: A => F[B]): F[B]

       trait Category[->>[_, _]]:
         def id[A]: A ->> A
         extension [A, B](f: A ->> B) def andThen[C](g: B ->> C): A ->> C

   Instances: `List`, `Option`, `Either[E, *]`, `Tuple2[E, *]`,
   `Function1[X, *]`, `Lens[S, *]`-style profunctor-light witnesses for
   the optics that have natural functors (see point 4).

2. **`Traversable` / `traverse` / `sequence`.**  The big one in practice
   — lets users sequence effects across a structure:

       trait Traversable[T[_]] extends Functor[T]:
         extension [A](ta: T[A])
           def traverse[F[_]: Applicative, B](f: A => F[B]): F[T[B]]
           def sequence[F[_]: Applicative]: F[T[A]]  // T[F[A]] case

   Instances for `List` and `Option` cover ~90% of real use.  Map's
   `traverse` is by-value; Either's traverse is `traverse on Right`.

3. **`Selective` (between Applicative and Monad).**  Mokhov/Lukyanov's
   class — `select :: f (Either a b) -> f (a -> b) -> f b` — lets a
   computation choose between two branches without the full power of
   `flatMap`, so it stays statically analyseable.  Useful for parsers,
   build-pipeline planners, anything that wants conditional effects but
   keeps the call graph inspectable.  Smaller user base than the rest
   of the hierarchy; ship after (1) and (2) land.

4. **`Arrow` + `Profunctor` as type classes (NOT an optics rewrite).**
   Adds the typeclass *definitions* and the obvious instances
   (`Function1` for both, optic-shaped witnesses where they fit):

       trait Profunctor[P[_, _]]:
         extension [A, B](pab: P[A, B])
           def dimap[C, D](f: C => A, g: B => D): P[C, D]

       trait Arrow[->>[_, _]] extends Category[->>]:
         def arr[A, B](f: A => B): A ->> B
         extension [A, B](f: A ->> B)
           def split[C, D](g: C ->> D): (A, C) ->> (B, D)
           def fanout[C](g: A ->> C): A ->> (B, C)

   The **existing concrete optic encoding stays as the primary API**;
   we don't rewrite `Lens` / `Prism` / `Optional` / `Traversal` in
   profunctor form (rank-N polymorphism + 5-10× slowdown isn't worth
   it without a compiler that erases the abstraction).  Instead, expose
   `Profunctor` and `Arrow` as standalone typeclasses with instances
   for `Function1` / `Lens[*, *]`-views / `Iso[*, *]`-views so users
   can write `dimap` / `split` over both functions and optics
   uniformly.  Roughly: optics keep their concrete `get` / `set`
   today; profunctor methods are a thin functional veneer on top.

5. **Inference ergonomics (no new keywords).**  Two pragmatic wins
   while keeping vanilla Scala 3 syntax:

   - **`pure[F](x)` without explicit `summon`.**  Today users have to
     write `summon[Applicative[F]].pure(x)`; add a top-level `def
     pure[F[_]: Applicative, A](a: A): F[A] = ...` shortcut in the
     std prelude so `pure[List](42)` Just Works.  Same for `empty`,
     `unit`, etc.
   - **`given` defaults for the common cases** so `xs.traverse(f)` on
     a `List` finds the instance without imports.  Already works via
     companion-object givens — just need to wire the std typeclass
     module into the default import set (similar to how `math`
     globals are imported today).

   *Explicitly out of scope:* new `typeclass` / `instance` keywords, do-
   notation desugaring, type defaulting.  Scala 3's `trait` + `given`
   are sufficient — diverging from them costs more than it earns.

**Profunctor-encoded optics are explicitly deferred** to a possible
future "Optics 3 — profunctor rewrite" track only if a concrete use
case demands it.  Current consensus: the concrete encoding is faster
and more readable; profunctor optics are an academic win that we don't
need today.

Effort: (1) ~1 day for the typeclasses + instances; (2) ~1 day for
Traversable + tests; (3) ~half a day; (4) ~1 day for the typeclasses
and the obvious Function1 instances; (5) ~half a day.  Roughly a week
end-to-end with conformance tests.

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

Three staged additions that build on the v0.8 Async / signals stack.
Each lands as its own merge so the suite stays green between steps.
(Stage 1 — fine-grained reactive `Signal` / `computed` / `effect` —
landed; see git history.)

1. **Real-thread `runAsyncParallel` handler.**  Drop-in alternative to
   the default `runAsync` driver: on the JVM uses an
   `ExecutorService` + `CompletableFuture`, on Node spawns
   `worker_threads` for each `async`.  Same `Async.*` API, swap-the-
   handler ergonomics — code written for `runAsync` becomes genuinely
   concurrent in `runAsyncParallel` without touching call sites.
   `parallel` returns results in declared order regardless of
   completion order, preserving deterministic output for code that
   doesn't rely on timing.  Conformance for this handler lives in its
   own opt-in suite (output is timing-sensitive so we can't fold it
   into the main `conformance/`).

2. **`Storage` effect.**  `Storage.get(key)` / `Storage.put(key, v)` /
   `Storage.remove(key)` / `Storage.keys()` against a JSON-backed
   file (`./ssc-storage.json` by default, override via
   `SSC_STORAGE_PATH`).  Lets REST + auth demos outlive a process
   restart without dragging in JDBC.  Handlers `runStorage(body)` and
   `runEphemeralStorage(body)` for the file-backed and in-memory
   variants; the latter is what the conformance suite uses so tests
   stay hermetic.

3. **`Async`-integrated WebSocket.**  Lift the `ws.onMessage(cb)` /
   `ws.onClose(cb)` callback surface into suspending `Async`
   operations: `Async.recvFrom(ws)` and `Async.closed(ws)`.  A
   WebSocket handler written as `runAsync { while (...) { … } }`
   reads linearly instead of the inverted-control callback chain.
   Builds on top of (1) for any subscriber UIs and on the existing
   `WsRoutes` plumbing in `compiler/src/main/scala/scalascript/server/`.

## Known issues / latent flakes

Things noticed in passing while landing other work — not blocking, but
worth a separate fix when somebody has cycles.

- **`InterpreterTest` StackOverflowError under sbt's parallel suite
  execution.**  ~5–10 % of `sbt compile/test` runs fail with a
  `java.lang.StackOverflowError` somewhere inside
  `Interpreter.callValue` / `eval` — almost always at the
  `mutual-TCO` / `stack-safe bind chains` / `Async`-related tests.
  Reproducible in isolation only by raising parallel suite load.
  Looks like sbt's forked test JVM defaults to `-Xss` ≈ 512 KB
  which is fine for any single test but tips over under the
  combined recursion depth of all parallel suites.  Fixes to try:
  bump `Test / javaOptions += "-Xss4m"` in `build.sbt`, or set
  `Test / parallelExecution := false` for the `compiler` module.
  Either is one-line; pick whichever the user prefers.
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
