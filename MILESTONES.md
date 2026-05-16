# Milestones

Tracks work that is **not yet done**. As things land, move them out of here
(into git history) rather than ticking checkboxes — the file should always
read forward.

## v0.2 — Web layer maturity

Direction-A follow-ups from the `feat(web): html"/css" interpolators + route()`
landing.  Goal: a `.ssc` document can express a full HTML page + REST API
without reaching for raw string concatenation.

- **A.2 — heading-bound `html` / `css` blocks.**  Today `html"..."` /
  `css"..."` only work as inline interpolators inside `scalascript` blocks.
  Make fenced `html` / `css` code blocks parseable as string-blocks: the
  block's body evaluates as a `String` with the same `${expr}` rules, and
  the first such block under `# Foo` binds to `Foo.html` / `Foo.css` in
  scope.  `Lang.isStringBlock` is already in place.
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

## v0.5 — Interpreter performance (Tier 1)

The tree-walking interpreter runs ~1000× slower than the compiled paths
on call-heavy workloads (see `bench/`).  This is by design — the
interpreter exists for instant-startup interactive use, not throughput.
But the worst offenders are easy to attack without an architectural
rewrite, with a realistic target of **~5×** overall.

- **Slot-indexed variable resolution.**  Pre-pass rewrites every
  `Term.Name("x")` into a `LocalSlot(i)` against a per-scope index.
  Lookups become array indexing instead of two HashMap probes (`env`
  then `globals`).  Highest-impact change.
- **Cached function resolution at call sites.**  At a `Term.Apply` the
  callee is currently re-resolved by name on every invocation.  Cache
  the resolved `FunV` / `NativeFnV` at the call site after the first
  resolve, invalidate on rebinding.
- **Constant folding in the parser.**  Literals in
  `if n < 2 then ...` are re-boxed into `Value.IntV(2)` on every visit.
  Hoist literal-to-`Value` conversion to parse time.
- **Skip `Computation` wrapping on pure paths.**  Functions without
  reachable effects (already known from `analyzeEffects`) shouldn't
  pay the `Pure(...)` allocation per step — return raw `Value`.
- **Integer fast path in the `infix` table.**  Match `(Int, op, Int)`
  before the generic pattern table so primitive arithmetic doesn't
  pay the full dispatch cost.

Re-benchmark after each step; commit only if the median moves more
than measurement noise.  Tier 2 (bytecode IR) and Tier 3 (JVM-bytecode
JIT or Truffle/Graal) are out of scope until a real workload demands
them — `ssc compile` already covers throughput.

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
