package scalascript.frontend.custom

import scalascript.frontend.*

/** Default frontend backend — no external npm runtime.
 *
 *  v1.18 / Phase A2 — static-subset emit.  Walks the user-defined
 *  Component tree, evaluates closures at emit-time, and produces a
 *  standalone HTML+JS bundle that builds the rendered DOM via
 *  imperative `document.createElement` / `appendChild` calls.
 *
 *  Phase A2 limitations (lifted in A2b):
 *
 *    - **Signals are snapshotted, not subscribed.**  Reading a
 *      `Signal[T]` via a `() => T` thunk happens once at emit time;
 *      the embedded value is a literal in the generated JS.  The
 *      page renders the initial state correctly but does not
 *      re-render when signals change.
 *    - **Events do nothing.**  `EventHandler.Simple` / `WithEvent`
 *      closures live on the JVM and aren't translatable to JS yet
 *      — they're emitted as a comment in the generated source.
 *    - **Effects don't run.**  Same JVM-closure problem.
 *
 *  These limits exist because the IR (`ComponentDef.body: Any =>
 *  View`) is a JVM function whose closures the static-emit pass
 *  can't translate to JS.  Phase A2b will switch to a textual /
 *  expression-tree IR populated by the compiler lowering pass; at
 *  that point the same Component definitions you write today
 *  become fully reactive in the browser. */
final class CustomFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "custom"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState,
    Capability.ComputedDerived,
    Capability.EffectLifecycle,
    Capability.DomRefs,
    Capability.Context,
    Capability.Untrack
  )

  override def jsDeps: List[JsDep] = Nil // zero npm deps — that's the whole point

  override def emit(module: FrontendModule): EmittedSpa =
    val entry = module.components.find(_.name == module.entryPoint).getOrElse(
      throw new IllegalArgumentException(
        s"FrontendModule.entryPoint='${module.entryPoint}' not found among " +
        s"components [${module.components.map(_.name).mkString(", ")}]."
      )
    )
    // Entry component is called with `()` — A2 restricts entry-point
    // signature to Component[Unit].  Sub-components can take any props
    // but they're inlined here through their own body() at emit time.
    val rootView = entry.body(())
    val js       = StaticJsEmitter.emit(rootView, module.components)
    val html     = htmlShell(initialRoute = module.initialRoute)
    EmittedSpa(js = js, html = html, css = "")

  private def htmlShell(initialRoute: String): String =
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8">
       |  <title>ScalaScript SPA</title>
       |</head>
       |<body>
       |  <div id="app"></div>
       |  <script type="module" src="./app.js" data-initial-route="$initialRoute"></script>
       |</body>
       |</html>
       |""".stripMargin
