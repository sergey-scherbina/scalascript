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
- **Form-data / multipart parsing in `Request`.**  Currently `Request.body`
  is the raw String; only `enctype="text/plain"` round-trips cleanly.  Need
  `application/x-www-form-urlencoded` and `multipart/form-data` parsers
  exposed as `req.form: Map[String, String]`.
- **Static file serving.**  When `serve` falls through to the file-rendering
  path, also serve plain assets (`.css`, `.js`, `.png`, …) from the root
  directory with proper Content-Type sniffing.
- **Concurrent request handling.**  The default JDK `HttpServer` executor is
  multithreaded but the interpreter is not.  Decide between (a) explicit
  single-thread executor + document the limit or (b) lock around
  `Interpreter.invoke`.  Today races are latent.

## v0.3 — Cross-backend REST

Move the REST primitives off "JVM interpreter only".

- **JVM compile backend.**  `ssc compile` for a `.ssc` file using `route(...)`
  should emit a self-contained Scala 3 script that bundles the `WebServer`
  runtime, registers the routes, and starts the server via the generated
  main.  JvmGen needs to know about `route` / `Request` / `Response`.
- **JS backend.**  Two sub-options to pick from when we get there:
  - Generate a Node HTTP server (server-side rendering / SPA-less REST).
  - Generate a client-side router that hydrates `.ssc` documents in the
    browser.
  Start with the Node target; it's the natural mirror of `ssc compile`.
- **`Response.json` auto-serialisation.**  Today the body has to be a
  string the caller serialised.  Add a JSON serialiser for case classes,
  `List`, `Map`, primitives — covering the same value space `Value.show`
  handles.  Probably a third-party lib (`upickle` or `circe`) on the JVM
  and a hand-rolled emitter for JS.

## v0.4 — Stability & polish

Known bugs and rough edges that need a separate pass.

- **[JS] `_output` const reassignment bug.**  Running `jssc examples/hello.ssc`
  prints the right output, then crashes with
  `TypeError: Assignment to constant variable.` at `_output = []`.
  JsGen runtime declares `_output` as `const`; the generated script
  reassigns it.  Either switch to `let` or replace the reassignment with
  `.length = 0`.
- **[JS] `typed-data.ssc` output diverges from the interpreter.**  JS
  prints extra trailing iterables on the same line; interpreter prints a
  clean per-line form.  Likely the same `(value, index, array)` callback
  drift the recent `_isIntTyped` fix uncovered, but for `foreach` /
  `mkString` interactions.  Reproduce and fix.
- **[JVM] effects handler case bodies with `::`.**  Compiling
  `examples/effects.ssc` via `ssc compile` fails with
  `value :: is not a member of Any` at the rewritten handler body — JvmGen
  emits the RHS of `msg :: resume(())` without recovering its `List` type.
  Cast or rewrite the cons-target in the handler path.
- **Interpreter — collection ergonomics.**  During the rest-api example
  we hit several missing operations.  Add support in
  `Interpreter` (`infix` table / method dispatch):
  - `xs :+ x` / `x +: xs` cons-append on `List`
  - `xs(i)` apply-on-`List` indexing
  - `m(k)` apply-on-`Map` indexing
  - tuple destructure in lambda parameters: `(t, i) => ...`
  - `xs.indices` on `List`
- **`examples/scala-js-demo.ssc` interpreter run.**  The example currently
  exits non-zero under the tree-walking interpreter (uses Scala 3 features
  outside the interpreter's subset).  Either broaden the interpreter or
  remove the file's interpreter expectation.

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
