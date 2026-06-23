package scalascript.cli

import scalascript.ast.*
import scalascript.parser.Parser
import scala.meta.*

/** UCC [E3] — the `def view()` convention on the static codegen path.
 *
 *  `def view()` (one `.ssc` that compiles to web OR terminal with no explicit
 *  `serve(..., port)` in the source) is honored at interpret time by
 *  `Interpreter.autoRunView`. But the static JS compile path
 *  (`compileJsSegments` → `JsGen`) — used by `run --mode client` (the
 *  cross-origin SPA preview) and `emit-spa` — never runs the interpreter, so it
 *  never sees a `serve(...)` and emits NO `ReactDOM.createRoot(...).render(...)`
 *  mount: a module that only defines `def view()` renders a blank client-mode
 *  page.
 *
 *  This applies the same convention for codegen: when a frontend backend is
 *  selected, the module defines a zero-arg top-level `view`, and the module
 *  does not call a UI entry itself, synthesize a top-level `serve(view(), 8080)`
 *  so `JsGen` emits the SPA + mount. Mirrors the gating of `autoRunView`. */
object AutoViewEntry:

  private val uiEntryNames = Set("serve", "emit", "mount", "serveAsync")

  /** Append a synthetic `serve(view(), 8080)` when the `def view()` convention
   *  holds and a frontend is selected; otherwise return the module unchanged. */
  def maybeInject(module: Module): Module =
    if scalascript.frontend.FrontendFrameworks.selectedName.isEmpty then module
    else if callsUiEntry(module) then module          // module renders itself explicitly
    else if !definesZeroArgView(module) then module
    else
      // Parse a bare synthetic fenced block to get a properly-treed section,
      // then append it — preserves the original module's config/manifest.
      val snippet = Parser.parse("```scalascript\nserve(view(), 8080)\n```\n")
      module.copy(sections = module.sections ++ snippet.sections)

  private def fnName(fun: Term): Option[String] = fun match
    case n: Term.Name   => Some(n.value)
    case s: Term.Select => Some(s.name.value)
    case _              => None

  /** Apply `f` to every top-level statement across the module's parseable blocks. */
  private def foreachTopStat(module: Module)(f: Stat => Unit): Unit =
    def loop(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.foreach(node => ScalaNode.fold(node) {
            case s: Source     => s.stats.foreach(f)
            case b: Term.Block => b.stats.foreach(f)
            case _             => ()
          })
        case _ => ()
      }
      section.subsections.foreach(loop)
    module.sections.foreach(loop)

  private def callsUiEntry(module: Module): Boolean =
    var found = false
    foreachTopStat(module) {
      case a: Term.Apply if fnName(a.fun).exists(uiEntryNames) => found = true
      case _                                                   => ()
    }
    found

  private def definesZeroArgView(module: Module): Boolean =
    var found = false
    foreachTopStat(module) {
      case d: Defn.Def
          if d.name.value == "view" &&
             d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).isEmpty =>
        found = true
      case _ => ()
    }
    found
