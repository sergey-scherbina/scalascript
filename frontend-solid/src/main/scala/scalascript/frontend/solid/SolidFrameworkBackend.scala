package scalascript.frontend.solid

import scalascript.frontend.*

/** Solid frontend backend — lowers the framework-agnostic
 *  primitives onto SolidJS 1.x idioms.
 *
 *  Solid's reactivity is fine-grained: pass a getter (signal-as-
 *  function) to `h()` and Solid creates a subscription so the
 *  DOM node updates on signal change.  Pass an unwrapped value
 *  (`signal()`) and the binding is static.  This is OPPOSITE to
 *  React, where the same expression `count` is a number and
 *  triggers full re-render via reconciliation.  Same IR, very
 *  different semantics — the SPI abstraction earns its keep.
 *
 *  Lowerings:
 *    - `ReactiveSignal[T]`          → `const [x, setX] = createSignal(init)`
 *    - `View.Element`               → `h(tag, props, ...children)`
 *    - `View.TextNode`              → string literal child
 *    - `View.SignalText`            → bare variable name (the
 *                                     getter function — Solid
 *                                     auto-subscribes)
 *    - `View.Fragment`              → array literal of children
 *    - `View.ComponentInstance`     → inlined
 *    - `View.Show`                  → emit-time snapshot branch
 *    - `View.For`                   → array literal of children
 *    - `SetSignalLiteral`           → `() => setX(value)`
 *    - `IncrementSignal`            → `() => setX(c => c + by)`
 *    - `Simple` / `WithEvent`       → JVM-closure marker comment */
final class SolidFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "solid"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState,
    Capability.ComputedDerived,
    Capability.EffectLifecycle,
    Capability.DomRefs,
    Capability.Context,
    Capability.Portals,
    Capability.Untrack
  )

  override def jsDeps: List[JsDep] = List(
    JsDep("solid-js",     "^1.8.0", "solid-js"),
    JsDep("solid-js/web", "^1.8.0", "solid-js/web")
  )

  override def emit(module: FrontendModule): EmittedSpa =
    val entry = module.components.find(_.name == module.entryPoint).getOrElse(
      throw new IllegalArgumentException(
        s"FrontendModule.entryPoint='${module.entryPoint}' not found among " +
        s"components [${module.components.map(_.name).mkString(", ")}]."
      )
    )
    val rootView = entry.body(())
    val js   = SolidEmitter.emit(rootView)
    val html = htmlShell(initialRoute = module.initialRoute)
    EmittedSpa(js = js, html = html, css = "")

  private def htmlShell(initialRoute: String): String =
    // ES-module import-maps so the bundle can `import` solid-js
    // directly from esm.sh without a bundler step.  We only need
    // `solid-js` itself — the emit avoids `solid-js/h` (upstream-
    // broken in 1.8+) by hand-writing the DOM construction.
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8">
       |  <title>ScalaScript SPA (Solid)</title>
       |  <script type="importmap">
       |  {
       |    "imports": {
       |      "solid-js": "https://esm.sh/solid-js@1.8.0"
       |    }
       |  }
       |  </script>
       |</head>
       |<body>
       |  <div id="app"></div>
       |  <script type="module" src="./app.js" data-initial-route="$initialRoute"></script>
       |</body>
       |</html>
       |""".stripMargin
