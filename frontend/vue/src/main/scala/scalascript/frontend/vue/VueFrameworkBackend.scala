package scalascript.frontend.vue

import scalascript.frontend.*

/** Vue 3 frontend backend — lowers the framework-agnostic
 *  primitives onto Vue's setup + render-function idiom.
 *
 *  Vue's reactivity is proxy-based — `ref()` returns a wrapper
 *  whose `.value` is intercepted, and inside the component proxy
 *  (`this` in render()) refs auto-unwrap.  This is a third
 *  distinct paradigm from React (re-render whole component) and
 *  Solid (fine-grained subscription via getter functions): Vue
 *  knows which refs the render function read because the proxy
 *  observed those accesses, and re-runs render only when those
 *  refs change.
 *
 *  Same IR, three frameworks, three reactivity models.  The
 *  abstraction earns its keep.
 *
 *  Lowerings:
 *    - `ReactiveSignal[T]`          → `ref(init)` in setup(),
 *                                     returned for proxy auto-unwrap
 *    - `View.Element`               → `h(tag, props, [...children])`
 *    - `View.TextNode`              → string child
 *    - `View.SignalText`            → `this.name` (auto-unwrapped)
 *    - `View.Fragment`              → `h(Fragment, ...)`
 *    - `View.Show` / `View.For`     → emit-time snapshot
 *    - `SetSignalLiteral`           → `() => { this.x = value; }`
 *    - `IncrementSignal`            → `() => { this.x += by; }`
 *    - `Simple` / `WithEvent`       → JVM-closure marker comment */
final class VueFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "vue"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState,
    Capability.ComputedDerived,
    Capability.EffectLifecycle,
    Capability.DomRefs,
    Capability.Context,
    Capability.Portals,
    Capability.Suspense,
    Capability.TwoWayBinding
  )

  override def jsDeps: List[JsDep] = List(
    JsDep("vue", "^3.4.0", "vue")
  )

  override def emit(module: FrontendModule): EmittedSpa =
    val entry = module.components.find(_.name == module.entryPoint).getOrElse(
      throw new IllegalArgumentException(
        s"FrontendModule.entryPoint='${module.entryPoint}' not found among " +
        s"components [${module.components.map(_.name).mkString(", ")}]."
      )
    )
    val rootView = entry.body(())
    val js   = VueEmitter.emit(rootView)
    val html = htmlShell(initialRoute = module.initialRoute)
    EmittedSpa(js = js, html = html, css = "")

  private def htmlShell(initialRoute: String): String =
    // Use ES-module import-map pointing at esm.sh so the bundle
    // can `import { ref, h, createApp } from 'vue'` without a
    // bundler step.  Production setups would replace with a real
    // bundler output.
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8">
       |  <meta name="viewport" content="width=device-width,initial-scale=1">
       |  <title>ScalaScript SPA (Vue)</title>
       |  <style>
    *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
    body{margin:0;padding:0;background:#fff;-webkit-text-size-adjust:100%;font-size:16px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif}
    #app{max-width:700px;margin:0 auto;padding:24px 20px}
    @media(max-width:767px){body{font-size:32px!important}p,label,span{font-size:32px!important}h1{font-size:56px!important}h2{font-size:44px!important}h3{font-size:36px!important}h4{font-size:30px!important}h5{font-size:24px!important}h6{font-size:20px!important}input[type=text],input[type=email],input[type=password]{font-size:32px!important;padding:40px!important;border-radius:24px!important}button{font-size:32px!important;padding:40px 72px!important;border-radius:36px!important}input[type=checkbox]{width:40px!important;height:40px!important}}
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
       |      "vue": "https://unpkg.com/vue@3.4.0/dist/vue.esm-browser.prod.js"
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
