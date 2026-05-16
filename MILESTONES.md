# Milestones

Tracks work that is **not yet done**. As things land, move them out of here
(into git history) rather than ticking checkboxes — the file should always
read forward.

## v0.7 — Reusable libraries and packaging

A consumer should be able to depend on a third-party `.ssc` library —
component pack, REST middleware, layout kit — without vendoring its
files into their own tree.  The steps are ordered so each one is
useful in isolation and unblocks the next.

1. **Import-alias parsing.**  `[Card as MyCard](./card.ssc)` /
   `[render, css as styles](./card.ssc)`.  The AST already carries
   `ImportBinding.alias: Option[String]`, but `Parser.asImport` always
   emits `None` (`parser/Parser.scala:167`).  Wire the parser, then
   honour the alias in `Interpreter.runImport` (`globals(alias) = v`)
   and in JsGen / JvmGen `genImport`.  Smallest step, biggest
   immediate payoff — unblocks naming conflicts between any two
   libraries that export the same identifier.  ~2–3 hours.

2. **URL imports.**  `[Card](https://raw.githubusercontent.com/u/r/v1.0/card.ssc)`
   resolves to a local cache at `~/.cache/ssc/<host>/<path>` (first
   fetch downloads; subsequent reads hit cache; pin by tag/sha for
   reproducibility).  Deno-style: no central registry needed, but
   real distribution becomes a one-liner.  Add a `--no-network`
   flag for sandboxed builds.  ~1–2 days.

3. **Cross-backend `dependencies:` resolver.**  Today the manifest's
   `dependencies:` only flows to scala-cli on the JVM target.
   Extend it to:
     - JSON (`{"foo": "1.2.0", "bar": "https://..."}`) — either
       semver against a future registry or a URL pin.
     - Resolve at parse time on all three backends (interp, JsGen,
       JvmGen), pull sources into the local cache, and rewrite
       bare-name imports (`[Card](foo://card.ssc)`) to the resolved
       path.
   Decouples the URL from every call site — pin once in the manifest,
   import by short name everywhere.  ~1 day on top of (2).

4. **`package` keyword** *(optional)*.  `package org.example.ui` at
   the top of a scalascript block puts its declarations under that
   dotted path so two libraries can each export `Card` without
   collision in the global namespace.  Mostly cosmetic once (1)/(2)
   are in place — alias parsing already solves the collision case
   for callers, and URL/dep resolution provides the uniqueness — but
   `package`-prefixed imports map cleanly to a future registry.
   Parser + 3 backends (`object org.example.ui.Card { … }` emit).
   ~1–2 days.  Defer until a concrete clash motivates it.

5. **Registry** *(future)*.  Central index (`registry.scalascript.io`)
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

## Beyond

Larger features that aren't on the critical path but are worth keeping in
view so they shape near-term decisions.

- **Persistence.**  At minimum a `Storage` effect (key-value, JSON-backed)
  so REST demos can outlive a process restart without dragging in JDBC.
- **Real-thread `Async` handler.**  The built-in `Async` effect ships with a
  single-threaded `runAsync` handler so observable output is byte-identical
  across all three backends.  A drop-in alternative — `runAsyncParallel` on
  the JVM using `ExecutorService` / `CompletableFuture`, on Node using
  `worker_threads` — would give real concurrency for `async` / `parallel`
  without touching user code.
- **Hot reload in `serve` mode.**  Reparse and re-register routes when a
  `.ssc` file changes on disk; today the server pins them at start.
- **REPL: web-aware mode.**  `bin/ssc repl` that lets you mount routes
  interactively and inspect the route table.
- **`html"..."` precision.**  Smarter `${}` parsing inside string-blocks
  so `${ a + "}" }` doesn't fool the regex (current TODO in the inline
  block evaluator).
