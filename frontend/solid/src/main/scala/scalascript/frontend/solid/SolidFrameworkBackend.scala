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
    // jsDelivr +esm converts the npm package to ESM on-the-fly — more
    // reliable than esm.sh which closes connections intermittently.
    // We only need solid-js itself; the emitter avoids solid-js/web by
    // writing imperative DOM directly (createSignal + createEffect only).
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8">
       |  <meta name="viewport" content="width=device-width,initial-scale=1">
       |  <title>ScalaScript SPA (Solid)</title>
       |  <style>
    *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
    body{margin:0;padding:0;background:#fff;-webkit-text-size-adjust:100%;font-size:16px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif}
    #app{max-width:700px;margin:0 auto;padding:24px 20px}
    @media(max-width:767px){body{font-size:18px!important}p,label,span{font-size:18px!important}h1{font-size:28px!important}h2{font-size:22px!important}h3{font-size:18px!important}h4,h5,h6{font-size:16px!important}input[type=text],input[type=email],input[type=password]{font-size:18px!important;padding:18px!important;border-radius:12px!important}button{font-size:18px!important;padding:18px 32px!important;border-radius:18px!important}input[type=checkbox]{width:22px!important;height:22px!important}}
    button{touch-action:manipulation;cursor:pointer}
    button:disabled{opacity:.5;cursor:default}
    input[type=checkbox]{width:22px;height:22px;accent-color:#2563eb;cursor:pointer;flex-shrink:0}
    hr{border:none;border-top:1px solid #e5e7eb;margin:0}
    @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
    .ssc-spin { animation: spin 0.8s linear infinite; }
  </style>
       |  <script type="importmap">
       |  {
       |    "imports": {
       |      "solid-js": "https://cdn.jsdelivr.net/npm/solid-js@1.8.0/+esm"
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
