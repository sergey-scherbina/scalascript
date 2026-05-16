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

2. **`ssc bundle <file.ssc> [-o name.sscpkg]`.**  Walks transitive
   `.ssc` imports starting from `<file>`, packs them into a zip with
   layout
       bundle.yaml          (entry, name, version, transitive list)
       <entry>.ssc          (at the archive root)
       <relative paths>/…   (imported files, paths kept)
   Refuses imports above the entry directory (`../foo.ssc`) so authors
   keep impls self-contained.  No network, no registry — distribution
   by attaching `.sscpkg` to a GitHub release / S3 / email.  Consumer
   unzips and imports relatively.  ~2–3 hours.

3. **URL imports.**  `[Card](https://raw.githubusercontent.com/u/r/v1.0/card.ssc)`
   resolves to a local cache at `~/.cache/ssc/<host>/<path>` (first
   fetch downloads; subsequent reads hit cache; pin by tag/sha for
   reproducibility).  Deno-style: no central registry needed, but
   real distribution becomes a one-liner.  Add a `--no-network`
   flag for sandboxed builds.  ~1–2 days.

4. **Cross-backend `dependencies:` resolver.**  Today the manifest's
   `dependencies:` only flows to scala-cli on the JVM target.
   Extend it to:
     - JSON (`{"foo": "1.2.0", "bar": "https://..."}`) — either
       semver against a future registry or a URL pin.
     - Resolve at parse time on all three backends (interp, JsGen,
       JvmGen), pull sources into the local cache, and rewrite
       bare-name imports (`[Card](foo://card.ssc)`) to the resolved
       path.
   Decouples the URL from every call site — pin once in the manifest,
   import by short name everywhere.  ~1 day on top of (3).

5. **`package` keyword** *(optional)*.  `package org.example.ui` at
   the top of a scalascript block puts its declarations under that
   dotted path so two libraries can each export `Card` without
   collision in the global namespace.  Mostly cosmetic once (1)/(3)
   are in place — alias parsing already solves the collision case
   for callers, and URL/dep resolution provides the uniqueness — but
   `package`-prefixed imports map cleanly to a future registry.
   Parser + 3 backends (`object org.example.ui.Card { … }` emit).
   ~1–2 days.  Defer until a concrete clash motivates it.

6. **Registry** *(future)*.  Central index (`registry.scalascript.io`)
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
