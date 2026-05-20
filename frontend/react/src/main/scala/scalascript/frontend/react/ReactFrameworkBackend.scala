package scalascript.frontend.react

import scalascript.frontend.*

/** React frontend backend — lowers the framework-agnostic
 *  primitives onto React 18 idioms:
 *
 *    - `ReactiveSignal[T]` → `useState` (collected from the View
 *      tree and hoisted to the top of the component function).
 *    - `View.Element` → `React.createElement(tag, props, ...children)`.
 *    - `View.TextNode` → string child.
 *    - `View.SignalText` → direct interpolation of the useState
 *      variable; the whole component re-renders on signal change
 *      (React idiom; Custom uses fine-grained subscriptions).
 *    - `View.Fragment` → `React.createElement(React.Fragment, ...)`.
 *    - `View.ComponentInstance` → recursive emit at IR level
 *      (the component is currently inlined; A3+ moves to function-
 *      component refs once we lower Component[P] separately).
 *    - `View.Show` → ternary expression.
 *    - `View.For` → `.map(item => render(item))`.
 *    - `EventHandler.SetSignalLiteral` → `setX(value)`.
 *    - `EventHandler.IncrementSignal`  → `setX(c => c + by)` —
 *      functional setState avoids stale-closure pitfalls.
 *    - `EventHandler.Simple/WithEvent` → JVM closures still
 *      untranslatable; emit a marker comment.
 *
 *  HTML shell loads React + ReactDOM from a CDN by default so the
 *  bundle runs without a separate package.json install for quick
 *  demos.  A real build pipeline would replace this with bundler
 *  output. */
final class ReactFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "react"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState,
    Capability.ComputedDerived,
    Capability.EffectLifecycle,
    Capability.DomRefs,
    Capability.Context,
    Capability.Portals,
    Capability.Suspense
  )

  override def jsDeps: List[JsDep] = List(
    JsDep("react",     "^18.3.0", "react"),
    JsDep("react-dom", "^18.3.0", "react-dom/client")
  )

  override def emit(module: FrontendModule): EmittedSpa =
    val entry = module.components.find(_.name == module.entryPoint).getOrElse(
      throw new IllegalArgumentException(
        s"FrontendModule.entryPoint='${module.entryPoint}' not found among " +
        s"components [${module.components.map(_.name).mkString(", ")}]."
      )
    )
    val rootView = entry.body(())
    val js   = ReactEmitter.emit(rootView)
    val html = htmlShell(initialRoute = module.initialRoute)
    EmittedSpa(js = js, html = html, css = "")

  private def htmlShell(initialRoute: String): String =
    // Standalone React + ReactDOM from a CDN for unbundled local demos.
    // Production builds would replace this with bundled output.
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8">
       |  <title>ScalaScript SPA (React)</title>
       |  <script crossorigin src="https://unpkg.com/react@18/umd/react.production.min.js"></script>
       |  <script crossorigin src="https://unpkg.com/react-dom@18/umd/react-dom.production.min.js"></script>
       |  <style>
    @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
    .ssc-spin { animation: spin 0.8s linear infinite; }
  </style>
       |</head>
       |<body>
       |  <div id="app"></div>
       |  <script src="./app.js" data-initial-route="$initialRoute"></script>
       |</body>
       |</html>
       |""".stripMargin
