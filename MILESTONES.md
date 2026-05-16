# Milestones

Tracks work that is **not yet done**. As things land, move them out of here
(into git history) rather than ticking checkboxes — the file should always
read forward.

## v0.2 — Web layer maturity

Direction-A follow-ups from the `feat(web): html"/css" interpolators + route()`
landing.  Goal: a `.ssc` document can express a full HTML page + REST API
without reaching for raw string concatenation.

- **A.2 — typed HTML DSL.**  Tag and attribute constructors (`div(cls := "...",
  h1("Hi"))`) returning HTML-tree values that convert to `String` on output.
  Minimum core: ~30 most common tags, ~20 attributes, sane escaping, splice
  interop with `html"..."` templates.
- **Multipart form parsing.**  `req.form` now covers
  `application/x-www-form-urlencoded` on all three backends; add a
  parser for `multipart/form-data` so file uploads round-trip too.
- **Static asset serving for compiled backends.**  Interpreter `serve`
  now falls through to static files (any non-`.ssc` under the root)
  before 404'ing.  The JvmGen / JsGen `serveRuntime` blocks still only
  do REST + 404 — give them the same fall-through so a compiled
  binary or Node script can serve a docs site too.

## v0.3 — Cross-backend REST (remaining)

The JVM and JS backends now ship their own `serveRuntime` / `route` runtime
(see the `feat(web)` cross-backend commit).  What's left:

- **Browser-side JS.**  The current JS backend emits a Node server.  A
  parallel build target should generate a client-side router that hydrates
  `.ssc` documents in the browser, so a single `.ssc` can run as either
  Node service or SPA.
- **`Response.json` auto-serialisation.**  Today the body has to be a
  string the caller serialised.  Add a JSON serialiser for case classes,
  `List`, `Map`, primitives — covering the same value space `Value.show`
  handles.  Probably a third-party lib (`upickle` or `circe`) on the JVM
  and a hand-rolled emitter for JS.
- **Cross-backend smoke harness.**  Add a script under `bench/` (or a new
  `e2e/`) that starts the same `rest-api.ssc` through each backend in
  turn, hits it with `curl`, and diffs the responses — guards against
  drift between the three serve runtimes.

## v0.4 — Stability & polish

Known bugs and rough edges that need a separate pass.

- **`examples/scala-js-demo.ssc` interpreter run.**  The example currently
  exits non-zero under the tree-walking interpreter (uses Scala 3 features
  outside the interpreter's subset).  Either broaden the interpreter or
  remove the file's interpreter expectation.

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

- **Component system.**  A `.ssc` file as a self-contained component:
  `scalascript` for logic, `html` for view, `css` for style, optional
  front-matter route declarations.  Plays well with v0.2 heading-bound
  blocks.
- **Auth / sessions / cookies.**  Session middleware, cookie helpers on
  `Request` / `Response`.  Probably needs a `before` / `after` hook
  primitive next to `route(...)`.
- **WebSocket support.**  `ws("/path") { conn => ... }` upgrade primitive,
  bidirectional `Frame` type.
- **Persistence.**  At minimum a `Storage` effect (key-value, JSON-backed)
  so REST demos can outlive a process restart without dragging in JDBC.
- **Hot reload in `serve` mode.**  Reparse and re-register routes when a
  `.ssc` file changes on disk; today the server pins them at start.
- **REPL: web-aware mode.**  `bin/ssc repl` that lets you mount routes
  interactively and inspect the route table.
- **`html"..."` precision.**  Smarter `${}` parsing inside string-blocks
  so `${ a + "}" }` doesn't fool the regex (current TODO in the inline
  block evaluator).
