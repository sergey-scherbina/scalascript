# Frontend SPI — usage guide

How to write a reactive SPA in ScalaScript and ship it to any of the
four supported frontend backends.  Companion docs:

- [`frontend-abstract-model.md`](frontend-abstract-model.md) — the
  primitive contract every backend honours (the *what*).
- [`frontend-framework-spi-plan.md`](frontend-framework-spi-plan.md)
  — SPI mechanics and per-framework trade-offs table (the *why* and
  *how-it-binds*).
- [`../examples/frontend/`](../examples/frontend) — three runnable
  reference apps that exercise everything below.

This doc is the *user-facing surface* of the SPI as of v1.18 Phase A8:
which primitives exist, how to select a backend, and what each one
lowers to.

## TL;DR

```scala
// 1. Pick a backend (build-time choice).
setFrontendFramework("solid")   // or "custom" | "react" | "vue"

// 2. Create reactive state.
val count = new ReactiveSignal[Int]("count", 0)

// 3. Describe the view tree.
val app = ComponentDef("App", Nil, _ => View.Element(
  "div", Map.empty, Map.empty, Seq(
    View.Element("button",
      Map("id" -> AttrValue.Str("inc")),
      Map("click" -> EventHandler.IncrementSignal(count)),
      Seq(View.TextNode(() => "+"))
    ),
    View.Element("span", Map.empty, Map.empty, Seq(View.SignalText(count)))
  )
))

// 4. Lower to JS.
val emitted = FrontendFrameworks.current()
  .emit(FrontendModule(List(app), "App", "/"))
// emitted.js + emitted.html + emitted.css are ready to serve.
```

The same `app` definition compiles to four different JS bundles
depending on `setFrontendFramework`.  See the [reference apps](../examples/frontend)
for fuller worked examples.

## Choosing a backend

The trade-offs table in
[`frontend-framework-spi-plan.md`](frontend-framework-spi-plan.md#trade-offs-at-a-glance)
is the canonical reference.  Quick mental model:

| Need                                            | Recommended backend |
| ----------------------------------------------- | ------------------- |
| Zero JS deps, smallest bundle                   | `custom`            |
| Existing React design system / component lib    | `react`             |
| Best perf on granular state changes             | `solid`             |
| Familiar template syntax + smooth proxy reactivity | `vue`            |

All four implement the same set of primitives — switching is a build-
time flag, not a rewrite.

## Selecting at build time

### From `.ssc` source

The `setFrontendFramework` intrinsic (v1.18 Phase A7) flips the
`FrontendFrameworks.setBackend(name)` choice for the rest of the
program:

```scala
setFrontendFramework("react")

// downstream emit code now routes through ReactFrameworkBackend
```

Unknown names raise `IllegalStateException` with the list of impls
that *are* on the classpath — loud failure over silent fallback.

### From the CLI

```bash
ssc emit-spa --frontend react app.ssc > spa.html
ssc emit-spa --frontend solid app.ssc > spa.html
```

The CLI bundles all four `frontend-{custom,react,solid,vue}` modules
so every name resolves out of the box.  Validation lives in
`validFrontendNames`; unknown names exit non-zero with an error.

### From Scala-host integration code

If you're driving the SPI directly from JVM-side glue (e.g., a build
script that wants to emit all four backends — see
[`EmitAll.scala`](../frontend-examples/src/main/scala/scalascript/frontend/examples/EmitAll.scala)),
construct the backend impl directly or call `FrontendFrameworks`:

```scala
import scalascript.frontend.*
import scalascript.frontend.react.ReactFrameworkBackend

val backend: FrontendFrameworkSpi = new ReactFrameworkBackend
val emitted: EmittedSpa = backend.emit(myModule)

// or, with ServiceLoader discovery + the global selection:
FrontendFrameworks.setBackend("solid")
val emitted2 = FrontendFrameworks.current().emit(myModule)
```

## Reactive primitives

### `ReactiveSignal[T]`

A reactive cell with a JS-safe `jsName` (used by the emitter as the
identifier for the cell across the bundle) and a primitive initial
value (`String | Int | Long | Double | Boolean`).

```scala
val count    = new ReactiveSignal[Int]("count", 0)
val username = new ReactiveSignal[String]("username", "anonymous")
val online   = new ReactiveSignal[Boolean]("online", true)
```

What this lowers to:

| Backend | Lowering                                                     |
| ------- | ------------------------------------------------------------ |
| custom  | `__ssc_signals['count']` cell + `Set` of subscribers         |
| react   | `const [count, setCount] = useState(0)` hoisted to component |
| solid   | `const [count, setCount] = createSignal(0)`                  |
| vue     | `const count = ref(0)` returned from `setup()`               |

### `ReactiveSignalList[T]`

A reactive sequence of `T` values; same naming + primitive-type
restriction as `ReactiveSignal`.

```scala
val todos = new ReactiveSignalList[String]("todos", Seq("first"))
```

Lowering: identical strategy to `ReactiveSignal` but stores an array
in the cell.  Backends subscribe to list changes for `View.ForSignal`
rendering.

### `View.SignalText`

A reactive text node bound to a `ReactiveSignal[?]`.  The emitter
generates a subscription that updates `textContent` (Custom / Solid)
or interpolates the current value into the re-rendered tree
(React / Vue).

```scala
View.SignalText(count)         // shows "0", "1", "2", … as count changes
```

### `View.ShowSignal`

A reactive conditional sub-tree.  `cond` is a
`ReactiveSignal[Boolean]`; the subtree swaps reactively when the
signal flips.

```scala
View.ShowSignal(
  cond      = visible,
  whenTrue  = View.Element("span", ..., Seq(View.SignalText(count))),
  whenFalse = View.TextNode(() => "")
)
```

| Backend | Lowering                                                            |
| ------- | ------------------------------------------------------------------- |
| custom  | Subscription on the `visible` cell that swaps a placeholder node   |
| react   | Ternary inside `render()` — `useState` change triggers re-render    |
| solid   | `createEffect` that wipes/rebuilds the conditional region          |
| vue     | Ternary inside the render arrow — proxy change triggers re-render  |

(For static "evaluated once at emit time" conditionals use the plain
`View.Show` — its `cond` is a `() => Boolean` JVM closure and is
snapshot at emit, not subscribed.)

### `View.ForSignal`

Repeated sub-trees backed by a `ReactiveSignalList[T]`.  Each item
renders as `<tag attrs>String(item)</tag>` — single-tag-per-item is
the current scope.

```scala
View.Element("ul", Map.empty, Map.empty, Seq(
  View.ForSignal(items = todos, tag = "li", attrs = Map.empty)
))
```

Rich per-item templates (nested elements, per-item events) need a
richer IR; they're deferred to a follow-up phase.

## Event handlers

The IR has two "closure-shaped" handlers
(`EventHandler.Simple(() => Unit)` and `EventHandler.WithEvent(Any => Unit)`)
and four "translatable" handlers that the emitter can lower into real
JS without translating an arbitrary JVM closure.

**The translatable handlers are the recommended path.**  The
closure-shaped ones are kept in the IR for completeness (a future
phase may lower limited closures); today every backend emits a marker
comment in their place rather than a working handler — explicitly
documented in each emitter so this isn't a silent footgun.

| Handler                                | What it does                                        |
| -------------------------------------- | --------------------------------------------------- |
| `EventHandler.IncrementSignal(s, by)`  | Add `by` (default 1) to a `ReactiveSignal[Int]`     |
| `EventHandler.SetSignalLiteral(s, v)`  | Set a `ReactiveSignal[?]` to a JS-literal value     |
| `EventHandler.ToggleSignal(s)`         | Flip a `ReactiveSignal[Boolean]`                    |
| `EventHandler.PushSignalLiteral(l, v)` | Append a literal to a `ReactiveSignalList[T]`       |
| `EventHandler.ClearSignalList(l)`      | Reset a `ReactiveSignalList[T]` to empty            |

Examples:

```scala
// + button
View.Element("button",
  Map.empty,
  Map("click" -> EventHandler.IncrementSignal(count, by = 1)),
  Seq(View.TextNode(() => "+"))
)

// reset button
View.Element("button",
  Map.empty,
  Map("click" -> EventHandler.SetSignalLiteral(count, 0)),
  Seq(View.TextNode(() => "reset"))
)

// add-todo button
View.Element("button",
  Map.empty,
  Map("click" -> EventHandler.PushSignalLiteral(todos, "new item")),
  Seq(View.TextNode(() => "add"))
)
```

Lowering shape (illustrative, React):

```js
const onClickInc   = () => setCount(c => c + 1);
const onClickReset = () => setCount(0);
const onClickAdd   = () => setTodos(arr => [...arr, "new item"]);
```

## Three reference apps

Three small demos live under [`examples/frontend/`](../examples/frontend)
and are built from
[`frontend-examples/src/main/scala/scalascript/frontend/examples/`](../frontend-examples/src/main/scala/scalascript/frontend/examples):

1. **counter** — `ReactiveSignal[Int]` + `IncrementSignal` +
   `SetSignalLiteral` + `SignalText`.
2. **show-hide** — `ReactiveSignal[Boolean]` + `ToggleSignal` +
   `ShowSignal`.
3. **todo** — `ReactiveSignalList[String]` + `PushSignalLiteral` +
   `ClearSignalList` + `ForSignal`.

Run them:

```bash
# Emit 12 (3 demos x 4 backends) HTML + JS bundles to target/frontend-examples/
sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll"

# Then open, e.g.:
#   target/frontend-examples/counter/react/index.html
#   target/frontend-examples/show-hide/solid/index.html
#   target/frontend-examples/todo/vue/index.html

# Or run the shape-assertion tests:
sbt frontendExamples/test
```

The per-demo READMEs explain each demo's `.ssc`-level intent and how
the four backends emit it.

## Limitations (as of v1.18 A8)

- **Primitive-only signal values.**  `ReactiveSignal[T]` /
  `ReactiveSignalList[T]` restrict `T` to a JSON-literal type
  (`String | Int | Long | Double | Boolean`) so the initial value can
  be embedded into JS without a portable encoder.  Widening waits on
  a serialiser story.

- **`ForSignal` per-item template is `<tag>String(item)</tag>`.**  Rich
  per-item views need either compile-time inlining of a `T => View`
  template or a runtime view DSL on the JS side — both deferred.

- **No JVM closures in event handlers.**  `EventHandler.Simple` and
  `EventHandler.WithEvent` emit a marker comment, not a real handler.
  Use `IncrementSignal` / `SetSignalLiteral` / `ToggleSignal` /
  `PushSignalLiteral` / `ClearSignalList` for working clicks.

- **No refs, context, suspense, or portals.**  These are A6 work
  (in progress).  `Capability.DomRefs`, `Capability.Context`,
  `Capability.Suspense`, `Capability.Portals` declarations on
  individual backends only describe planned coverage; the
  corresponding `View` / `EventHandler` constructors don't exist yet.

- **No router.**  `FrontendModule.initialRoute` is passed through to
  the HTML shell but no `View.Route(...)` primitive exists yet.

- **CSR only — no SSR.**  Server-side rendering per backend is a
  separate Fr8 follow-up (see the SPI plan doc).

- **No bundler integration.**  The emitted `app.js` for React / Vue
  uses CDN script tags or import maps so demos run without a
  bundler; Solid's output expects `import 'solid-js'` to resolve
  through your own toolchain (Vite / esbuild / etc.).

- **Component scoping is flat.**  `FrontendModule.entryPoint`
  identifies a single top-level component; sub-components are
  inlined at emit time rather than emitted as separate framework-
  level components.  A future phase will lower nested `Component[P]`
  into framework-native function components.
