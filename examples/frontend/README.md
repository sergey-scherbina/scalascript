# Frontend SPI + Toolkit — reference apps

Four canonical demos that exercise the ScalaScript frontend SPI
across all four backends.  Each demo is built once as a framework-
agnostic `FrontendModule` (a tree of `View` primitives + reactive
signals), then lowered by Custom / React / Solid / Vue into four
different idiomatic JS bundles.

The source for the demo IR lives in the `frontend-examples` sbt
module under
[`frontend-examples/src/main/scala/scalascript/frontend/examples/`](../../frontend-examples/src/main/scala/scalascript/frontend/examples).
The per-backend tests under each `frontend-<name>/src/test` already
exercise the same shapes end-to-end through `jsdom` when available.

## The four demos

| Demo | Primitives exercised | Source |
| --- | --- | --- |
| [`counter/`](counter/)     | `ReactiveSignal[Int]`, `IncrementSignal`, `SetSignalLiteral`, `SignalText` | [`CounterDemo.scala`](../../frontend-examples/src/main/scala/scalascript/frontend/examples/CounterDemo.scala) |
| [`show-hide/`](show-hide/) | `ReactiveSignal[Boolean]`, `ToggleSignal`, `View.ShowSignal`              | [`ShowHideDemo.scala`](../../frontend-examples/src/main/scala/scalascript/frontend/examples/ShowHideDemo.scala) |
| [`todo/`](todo/)           | `ReactiveSignalList[String]`, `PushSignalLiteral`, `ClearSignalList`, `View.ForSignal` | [`TodoListDemo.scala`](../../frontend-examples/src/main/scala/scalascript/frontend/examples/TodoListDemo.scala) |
| `toolkit-demo` *(no `.ssc`; built via `Tk` facade)* | Frontend Toolkit — Stack / Heading / Text / Card / TextField / Checkbox / Button / Spinner / Badge / Alert / theme tokens | [`ToolkitDemo.scala`](../../frontend-examples/src/main/scala/scalascript/frontend/examples/ToolkitDemo.scala) |
| [`swing-hello/`](swing-hello/) | Minimal JDK-only Swing desktop window; the frontend SPI `swing` backend is currently a skeleton | [`swing-hello.ssc`](swing-hello/swing-hello.ssc) |
| [`typed-client-distributed/`](typed-client-distributed/) | Same `.ssc` source as JVM backend on one machine and browser/Electron client on another, using generated `apiClients:` HTTP methods | [`typed-client-distributed.ssc`](typed-client-distributed/typed-client-distributed.ssc) |

## Compile

The `frontendExamples` sbt module compiles the demo source +
re-exports each backend's emitter:

```bash
# Compile sources + tests for all four demos
sbt frontendExamples/compile

# Run the shape-assertion test suite (41 tests)
sbt frontendExamples/test
```

## Generate static bundles

`EmitAll` lowers every (demo × backend) pair to an HTML + JS pair:

```bash
# Emit 16 (4 demos x 4 backends) bundles to target/frontend-examples/
sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll"

# Custom out-dir:
sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll /tmp/ssc-spa"
```

After running, the layout looks like:

```
target/frontend-examples/
  counter/
    custom/index.html  custom/app.js
    react/index.html   react/app.js
    solid/index.html   solid/app.js
    vue/index.html     vue/app.js
  show-hide/    custom/ ... react/ ... solid/ ... vue/ ...
  todo/         custom/ ... react/ ... solid/ ... vue/ ...
  toolkit-demo/ custom/ ... react/ ... solid/ ... vue/ ...
```

## Run in a browser

ES-module emit (Vue / Solid / Custom) needs to be served over HTTP —
`file://` blocks `import` statements.  Use the bundled `ssc serve`
static file server (it ships with the CLI, no Python/Node needed):

```bash
# Serve the React build of toolkit-demo
ssc serve 8000 target/frontend-examples/toolkit-demo/react
# then open http://localhost:8000/

# Other backends + demos work the same way:
ssc serve 8000 target/frontend-examples/toolkit-demo/vue
ssc serve 8000 target/frontend-examples/counter/solid
ssc serve 8000 target/frontend-examples/todo/custom
```

`ssc serve [port] [dir]` is the CLI's built-in static file server
(scalascript's own JVM `WebServer`).  Defaults: port `8080`, dir `.`.

### Per-backend notes

- **Custom**       — self-contained ES module + bundled runtime, zero
                     npm deps.  *Toolkit-demo through Custom is
                     currently static*: signal bindings + lambda
                     event handlers don't yet translate (Phase D
                     follow-up; see `docs/frontend-toolkit-spec.md`).
- **React**        — pulls React 18 from `unpkg.com` via plain
                     `<script>` tags; non-module, also runs from
                     `file://` if needed.
- **Vue**          — importmap pointing at `esm.sh/vue@3.4.0`; needs
                     an HTTP server.
- **Solid**        — importmap pointing at `esm.sh/solid-js`; needs
                     an HTTP server.

## SSR — static HTML without any runtime

The toolkit's `Ssr` renderer turns a `ToolkitNode` into a frozen
HTML string with no JS subscriptions.  Useful for SEO, static-site
generators, snapshot tests, email templates:

```scala
import scalascript.frontend.toolkit.{Ssr, ToolkitDemo}

// Render just the toolkit tree:
val module = ToolkitDemo.buildModule()
val view   = module.components.head.body(Nil)
val html   = Ssr.renderToHtml(view)

// Or a full HTML5 document with theme-coloured body:
val doc = Ssr.renderDocument(
  body  = /* ToolkitNode */,
  title = "Demo",
  theme = scalascript.frontend.toolkit.Theme.default
)
```

## See also

- [`docs/frontend-usage.md`](../../docs/frontend-usage.md) — user-facing
  guide to the SPI and its primitives.
- [`docs/frontend-abstract-model.md`](../../docs/frontend-abstract-model.md) — the framework-agnostic primitive
  contract every backend honours.
- [`docs/frontend-framework-spi-plan.md`](../../docs/frontend-framework-spi-plan.md) — SPI mechanics (selection,
  ServiceLoader, codegen flags).
