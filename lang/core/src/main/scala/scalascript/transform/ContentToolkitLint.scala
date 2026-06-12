package scalascript.transform

import scala.meta.*
import scalascript.ast
import scalascript.ast.{Position, Span}
import scalascript.typer.TypeError

/** Build-time id-existence lint for `@ui=toolkit` control trees (declarative-ui
 *  Scope B.7 v1).
 *
 *  A typo'd id in a declarative control — `{type: button, action: refesh}` when
 *  the registry holds `refresh` — used to surface only at *render* time, caught
 *  fail-soft into an inline error node (Scope A). That is invisible in CI and a
 *  small red box on a live SPA. This pass lifts it to `ssc check`: a referenced
 *  id with no matching registration emits a **warning** (never an error, so the
 *  exit code is unchanged on an otherwise-clean file).
 *
 *  The analysis is pure and AST-only (mirrors [[MarkupInterpolatorCheck]]): it
 *  walks the parsed `ast.Module`, never the interpreter / content pipeline /
 *  plugin YAML parser, and only **harvests id strings** — it does not re-render
 *  controls.
 *
 *    - References: `@ui=toolkit` blocks (a `Content.CodeBlock` whose fence attrs
 *      carry `ui=toolkit`); the raw YAML `source` is line-scanned for the control
 *      keys `action:` (action registry), `source:` / `rows:` (source registry), and
 *      `signal:` / `showWhen:` / `enabledWhen:` (the signal universe — a
 *      `contentComputed` registration or a local `signals:` default).
 *    - Registrations: every scalascript `CodeBlock.tree` is traversed for
 *      `contentAction` (action ids), `contentRows` / `contentDataSource` (source
 *      ids), and `contentComputed` (signal ids) with a string-literal first arg,
 *      regardless of nesting; plus the YAML `signals:` blocks for local signal ids.
 *
 *  Cross-check is **conservative**: a reference is flagged only when its registry
 *  is non-empty in the reachable graph and the id is absent. An empty registry
 *  (registrations may be dynamic/external) yields no warning, keeping false
 *  positives near zero. The caller unions registrations across the entry module
 *  and its transitively-imported modules, and suppresses the lint entirely when
 *  the import graph is incomplete (a hidden registration must never warn).
 *
 *  Scope: `action` / `source` / `rows` / `signal` / `showWhen` / `enabledWhen`
 *  YAML control references. Markdown `toolkit:` *link* references and shape
 *  checking are still deferred.
 */
object ContentToolkitLint:

  /** Registered toolkit ids, bucketed by registry kind. */
  final case class Registrations(actions: Set[String], sources: Set[String], computed: Set[String]):
    def ++(o: Registrations): Registrations =
      Registrations(actions ++ o.actions, sources ++ o.sources, computed ++ o.computed)
    def isEmpty: Boolean = actions.isEmpty && sources.isEmpty && computed.isEmpty

  object Registrations:
    val empty: Registrations = Registrations(Set.empty, Set.empty, Set.empty)

  /** A registry kind a control key binds to. */
  enum RefKind:
    case Action, Source, Signal

  /** A toolkit id reference harvested from a `@ui=toolkit` YAML block. */
  final case class Reference(kind: RefKind, id: String, line: Int)

  // ── Registrations ────────────────────────────────────────────────────────

  /** Harvest `contentAction` / `contentRows` registrations from every
   *  scalascript code block in `module`. */
  def collectRegistrations(module: ast.Module): Registrations =
    val actions  = scala.collection.mutable.Set.empty[String]
    val sources  = scala.collection.mutable.Set.empty[String]
    val computed = scala.collection.mutable.Set.empty[String]
    def walkSection(s: ast.Section): Unit =
      s.content.foreach {
        case ast.Content.CodeBlock(lang, _, Some(tree), _, _, _, _)
            if ast.Lang.isScalaScript(lang) =>
          ast.ScalaNode.fold(tree) { t =>
            t.traverse {
              case Term.Apply.After_4_6_0(Term.Name(fn), argClause) =>
                argClause.values.headOption match
                  case Some(Lit.String(id)) =>
                    fn match
                      case "contentAction"   => actions += id
                      case "contentRows" | "contentDataSource" => sources += id
                      case "contentComputed" => computed += id
                      case _                 => ()
                  case _ => ()
            }
          }
        case _ => ()
      }
      s.subsections.foreach(walkSection)
    module.sections.foreach(walkSection)
    Registrations(actions.toSet, sources.toSet, computed.toSet)

  // ── References ───────────────────────────────────────────────────────────

  // Block-style control key: `   action: refresh` or `   - source: invoices`.
  // Anchored at line start (after optional `- `) so it never matches a key name
  // embedded inside a quoted string value.  `signal`/`showWhen`/`enabledWhen`
  // reference a computed registration or a local `signals:` default (Scope B.7+).
  private val KeyPat =
    """^\s*(?:-\s*)?(action|source|rows|signal|showWhen|enabledWhen)\s*:\s*(.+?)\s*(?:#.*)?$""".r

  private def refKindFor(key: String): RefKind = key match
    case "action"                            => RefKind.Action
    case "source" | "rows"                   => RefKind.Source
    case "signal" | "showWhen" | "enabledWhen" => RefKind.Signal
    case _                                    => RefKind.Signal

  /** Harvest `action:` / `source:` / `rows:` references from every `@ui=toolkit`
   *  block in `module`, with best-effort file-level line numbers. */
  def collectReferences(module: ast.Module): List[Reference] =
    val refs = scala.collection.mutable.ListBuffer.empty[Reference]
    def walkSection(s: ast.Section): Unit =
      s.content.foreach {
        case cb: ast.Content.CodeBlock if isToolkitBlock(cb) =>
          cb.source.linesIterator.zipWithIndex.foreach { (rawLine, idx) =>
            KeyPat.findFirstMatchIn(rawLine).foreach { m =>
              val key = m.group(1)
              val id  = unquote(m.group(2))
              if id.nonEmpty && !id.startsWith("$") then
                refs += Reference(refKindFor(key), id, cb.lineOffset + idx + 1)
            }
          }
        case _ => ()
      }
      s.subsections.foreach(walkSection)
    module.sections.foreach(walkSection)
    refs.toList

  private def isToolkitBlock(cb: ast.Content.CodeBlock): Boolean =
    cb.attrs.get("ui").contains("toolkit")

  private def unquote(s: String): String =
    val t = s.trim
    if t.length >= 2 && (t.head == '"' || t.head == '\'') && t.last == t.head then
      t.substring(1, t.length - 1)
    else t

  // ── Local signals (for `signal:` reference validation) ─────────────────────

  private val SignalsHeader = """^(\s*)signals\s*:\s*$""".r
  private val SignalChild   = """^(\s*)([A-Za-z_][\w-]*)\s*:.*$""".r

  /** Harvest the ids declared in every `@ui=toolkit` block's `signals:` block —
   *  these scalar defaults satisfy a `signal:` / `showWhen:` / `enabledWhen:`
   *  reference just like a `contentComputed` registration, so they must be part
   *  of the known-signal universe or a valid reference would falsely warn. */
  def collectLocalSignals(module: ast.Module): Set[String] =
    val ids = scala.collection.mutable.Set.empty[String]
    def walk(s: ast.Section): Unit =
      s.content.foreach {
        case cb: ast.Content.CodeBlock if isToolkitBlock(cb) => ids ++= localSignalsIn(cb.source)
        case _                                               => ()
      }
      s.subsections.foreach(walk)
    module.sections.foreach(walk)
    ids.toSet

  private def localSignalsIn(source: String): Set[String] =
    val out   = scala.collection.mutable.Set.empty[String]
    val lines = source.linesIterator.toArray
    var i = 0
    while i < lines.length do
      SignalsHeader.findFirstMatchIn(lines(i)) match
        case Some(m) =>
          val baseIndent = m.group(1).length
          var j = i + 1
          var done = false
          while j < lines.length && !done do
            val line = lines(j)
            if line.trim.isEmpty then j += 1
            else
              val indent = line.takeWhile(_ == ' ').length
              if indent <= baseIndent then done = true
              else
                SignalChild.findFirstMatchIn(line).foreach(cm => out += cm.group(2))
                j += 1
          i = j
        case None => i += 1
    out.toSet

  // ── Cross-check ──────────────────────────────────────────────────────────

  /** Cross-check `module`'s references against the union of its own registrations
   *  and `extra` (registrations reachable through imports). Returns one warning
   *  per reference whose id is absent from a **non-empty** registry. */
  def lint(module: ast.Module, extra: Registrations): List[TypeError] =
    val regs = collectRegistrations(module) ++ extra
    // A `signal:` reference is satisfied by a `contentComputed` registration OR a
    // locally-declared YAML `signals:` default — both form the known-signal universe.
    val signalUniverse = regs.computed ++ collectLocalSignals(module)
    collectReferences(module).flatMap { ref =>
      val (registered, label) = ref.kind match
        case RefKind.Action => (regs.actions, "action")
        case RefKind.Source => (regs.sources, "data source")
        case RefKind.Signal => (signalUniverse, "signal")
      // Conservative: only warn when the registry is populated somewhere in the
      // graph (otherwise the registration may be dynamic/external).
      if registered.nonEmpty && !registered.contains(ref.id) then
        val pos  = Position(ref.line, 1, 0)
        val near = nearest(ref.id, registered)
        val hint = near.fold("")(n => s" (did you mean '$n'?)")
        Some(TypeError(
          s"@ui=toolkit references $label '${ref.id}' which is not registered$hint",
          Some(Span(pos, pos)),
          isWarning = true
        ))
      else None
    }

  /** Closest registered id by edit distance, when it is a plausible typo. */
  private def nearest(id: String, candidates: Set[String]): Option[String] =
    candidates.iterator
      .map(c => (c, editDistance(id, c)))
      .filter((_, d) => d <= (id.length / 2).max(1))
      .minByOption(_._2)
      .map(_._1)

  private def editDistance(a: String, b: String): Int =
    val prev = Array.tabulate(b.length + 1)(identity)
    val cur  = new Array[Int](b.length + 1)
    for i <- 1 to a.length do
      cur(0) = i
      for j <- 1 to b.length do
        val cost = if a(i - 1) == b(j - 1) then 0 else 1
        cur(j) = math.min(math.min(cur(j - 1) + 1, prev(j) + 1), prev(j - 1) + cost)
      System.arraycopy(cur, 0, prev, 0, cur.length)
    prev(b.length)
