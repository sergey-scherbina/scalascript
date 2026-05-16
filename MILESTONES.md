# Milestones

Tracks work that is **not yet done**. As things land, move them out of here
(into git history) rather than ticking checkboxes — the file should always
read forward.

## v0.2 — Web layer maturity

Direction-A follow-ups from the `feat(web): html"/css" interpolators + route()`
landing.  Goal: a `.ssc` document can express a full HTML page + REST API
without reaching for raw string concatenation.

- **Multipart file uploads.**  The interpreter now parses
  `multipart/form-data` text parts into `req.form`; file parts (those
  with a `filename=` directive) are skipped.  Add a `req.files`-style
  API plus binary-safe body reading so file uploads round-trip, and
  port both to JsGen / JvmGen.

## v0.3 — Cross-backend REST (remaining)

The JVM and JS backends now ship their own `serveRuntime` / `route` runtime
(see the `feat(web)` cross-backend commit).  What's left:

- **Browser-side JS.**  The current JS backend emits a Node server.  A
  parallel build target should generate a client-side router that hydrates
  `.ssc` documents in the browser, so a single `.ssc` can run as either
  Node service or SPA.
- ~~**`Response.json` auto-serialisation.**~~  *Landed.*  Hand-rolled
  encoders on all three backends (INT/JS/JVM) serialise `List`, `Map`,
  `Option`, primitives, case classes and tuples to byte-identical JSON.
  Bare `String` bodies still pass through verbatim for back-compat.
- ~~**Cross-backend smoke harness.**~~  *Landed* as `e2e/rest-smoke.sc`.
  Boots `examples/rest-api.ssc` through each backend in turn, runs a
  fixed sequence of HTTP requests, and diffs `(status, body)` across
  INT/JS/JVM. Works both from an installed `bin/` and from a fresh
  worktree (falls back to `scala-cli run compiler` when bin/ is absent).

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
