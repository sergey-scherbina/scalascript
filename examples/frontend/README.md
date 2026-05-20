# Frontend SPI — reference apps

Three canonical demos that exercise the ScalaScript frontend SPI
across all four backends.  Each demo is built once as a framework-
agnostic `FrontendModule` (a tree of `View` primitives + reactive
signals), then lowered by Custom / React / Solid / Vue into four
different idiomatic JS bundles.

The source for the demo IR lives in the `frontend-examples` sbt
module under
[`frontend-examples/src/main/scala/scalascript/frontend/examples/`](../../frontend-examples/src/main/scala/scalascript/frontend/examples).
The per-backend tests under each `frontend-<name>/src/test` already
exercise the same shapes end-to-end through `jsdom` when available.

## The three demos

| Demo | Primitives exercised | Source |
| --- | --- | --- |
| [`counter/`](counter/)     | `ReactiveSignal[Int]`, `IncrementSignal`, `SetSignalLiteral`, `SignalText` | [`CounterDemo.scala`](../../frontend-examples/src/main/scala/scalascript/frontend/examples/CounterDemo.scala) |
| [`show-hide/`](show-hide/) | `ReactiveSignal[Boolean]`, `ToggleSignal`, `View.ShowSignal`              | [`ShowHideDemo.scala`](../../frontend-examples/src/main/scala/scalascript/frontend/examples/ShowHideDemo.scala) |
| [`todo/`](todo/)           | `ReactiveSignalList[String]`, `PushSignalLiteral`, `ClearSignalList`, `View.ForSignal` | [`TodoListDemo.scala`](../../frontend-examples/src/main/scala/scalascript/frontend/examples/TodoListDemo.scala) |

## Running them

```bash
# Emit all 12 (3 demos x 4 backends) bundles to target/frontend-examples/
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
  show-hide/
    custom/ ...  react/ ...  solid/ ...  vue/ ...
  todo/
    custom/ ...  react/ ...  solid/ ...  vue/ ...
```

The Custom HTML shell is fully self-contained (zero npm deps); React
and Vue use CDN script tags + ES-module import maps so you can open
`index.html` directly in a browser.  Solid's output expects a bundler
(`solid-js` import).

## Running the shape tests

The reference-app IR is also wired into a small ScalaTest suite that
asserts every backend emits non-empty, backend-idiomatic JS for every
demo:

```bash
sbt frontendExamples/test
```

## See also

- [`docs/frontend-usage.md`](../../docs/frontend-usage.md) — user-facing
  guide to the SPI and its primitives.
- [`docs/frontend-abstract-model.md`](../../docs/frontend-abstract-model.md) — the framework-agnostic primitive
  contract every backend honours.
- [`docs/frontend-framework-spi-plan.md`](../../docs/frontend-framework-spi-plan.md) — SPI mechanics (selection,
  ServiceLoader, codegen flags).
