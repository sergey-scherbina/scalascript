package scalascript.artifact

import scalascript.ast.{Module, Section, Content, ScalaNode}
import scalascript.parser.Parser
import scala.meta.*

/** arch-meta-v2 `macro-codegen-backends` — expand restricted quoted macros in an
 *  `ast.Module` BEFORE the generated backends (`JvmGen` / `JsGen`) run.
 *
 *  The generated backends emit Scala/JS from the parsed module and rely on the
 *  target compiler for `inline`; they cannot run ScalaScript's `__ssc_macro__`
 *  / `Expr` / `QuotedContext`.  This pass rewrites macro call sites to their
 *  expansion (reusing the tested const-fold parser `Linker.parseAsValueFold` /
 *  `normalizeQuotedMacroBody` / `isLiteralArg`, beta-reduced by direct
 *  substitution) and drops the macro entrypoint + impl definitions, so the
 *  backend only ever sees plain code.
 *
 *  **Strict no-op invariant:** a module with no expandable quoted-macro
 *  entrypoints is returned unchanged (reference-equal sections), so macro-free
 *  modules — the overwhelming majority — cannot be affected.  See
 *  `specs/macro-codegen-backends.md`. */
object MacroCodegen:

  given Dialect = dialects.Scala3

  /** Single-module path — expand + strip quoted macros in one parsed module
   *  (defs and call sites both in this module). No-op when the module declares
   *  no expandable macro entrypoints. */
  def expand(module: Module): Module =
    val (table, stripSet) = collectMacros(module)
    if table.isEmpty then module
    else module.copy(sections = module.sections.map(s => rewriteSection(s, table, stripSet)))

  /** Cross-module path (Approach B) — expand + strip macros over an ALREADY
   *  ASSEMBLED set of code units (consumer blocks + inlined imported blocks),
   *  collected by the generated backends AFTER import inlining. Because the
   *  imported macro defs and the consumer's call sites coexist here, a macro
   *  defined in an imported module and called from a consumer is handled with
   *  no import resolution / double-parse. Each unit is `(tree, source)`; the
   *  returned tuple is unchanged (same `ScalaNode` identity) when nothing in the
   *  unit changed. No-op when the combined set declares no expandable macros. */
  def expandUnits(units: List[(ScalaNode, String)]): List[(ScalaNode, String)] =
    if units.isEmpty then return units
    val allStats          = units.flatMap { case (node, _) => nodeStats(node).getOrElse(Nil) }
    val (table, stripSet)  = collectMacrosFromStats(allStats)
    if table.isEmpty then units
    else units.map { case (node, source) => transformUnit(node, source, table, stripSet) }

  // ── macro discovery ──────────────────────────────────────────────────

  /** Build `name → MacroExpansion` and the strip-set of entrypoint + impl
   *  names, considering only macros whose impl body is expandable (a direct
   *  quote or an `Expr.asValue match`).  Interpreter-only macro shapes are left
   *  untouched. */
  private def collectMacros(module: Module): (Map[String, Linker.MacroExpansion], Set[String]) =
    val stats = scala.collection.mutable.ListBuffer.empty[Stat]
    foreachTopStat(module)(stats += _)
    collectMacrosFromStats(stats.toList)

  /** Build `name → MacroExpansion` + the strip-set of entrypoint + impl names
   *  from a flat statement list (the module's own, or the assembled cross-module
   *  set). Considers only macros whose impl body is expandable. */
  private def collectMacrosFromStats(
      stats: List[Stat]): (Map[String, Linker.MacroExpansion], Set[String]) =
    val entrypoints = scala.collection.mutable.LinkedHashMap.empty[String, (String, List[String])]
    val implBodies  = scala.collection.mutable.LinkedHashMap.empty[String, String]
    stats.foreach {
      case d: Defn.Def =>
        detectEntrypoint(d).foreach { case (name, impl, params) => entrypoints(name) = (impl, params) }
        detectImpl(d).foreach { case (impl, body) => implBodies(impl) = body }
      case _ => ()
    }
    val table = entrypoints.flatMap { case (name, (impl, params)) =>
      implBodies.get(impl).map(body => name -> Linker.MacroExpansion(params, body))
    }.toMap
    val stripSet =
      table.keySet ++ entrypoints.collect { case (name, (impl, _)) if table.contains(name) => impl }
    (table, stripSet)

  /** `inline def NAME(...) = __ssc_macro__(IMPL(__ssc_quote__("p", p), …))`
   *  → `(NAME, IMPL, quotedParams)`. */
  private def detectEntrypoint(d: Defn.Def): Option[(String, String, List[String])] =
    if !d.mods.exists(_.is[Mod.Inline]) then None
    else d.body match
      case Term.Apply.After_4_6_0(Term.Name("__ssc_macro__"), Term.ArgClause(List(inner), _)) =>
        inner match
          case Term.Apply.After_4_6_0(Term.Name(implName), Term.ArgClause(implArgs, _)) =>
            val params = implArgs.collect {
              case Term.Apply.After_4_6_0(Term.Name("__ssc_quote__"), Term.ArgClause(Lit.String(p) :: _, _)) => p
            }
            Some((d.name.value, implName, params))
          case _ => None
      case _ => None

  /** A macro impl whose body the Linker can expand: a direct quote
   *  (`__ssc_quote_expr__(…)`) or an `Expr.asValue match`. */
  private def detectImpl(d: Defn.Def): Option[(String, String)] =
    val body = d.body.syntax.trim
    if body.startsWith("__ssc_quote_expr__(") && body.endsWith(")") then Some(d.name.value -> body)
    else if Linker.parseAsValueFold(body).isDefined then Some(d.name.value -> body)
    else None

  // ── rewrite ──────────────────────────────────────────────────────────

  private def rewriteSection(
      s: Section, table: Map[String, Linker.MacroExpansion], stripSet: Set[String]): Section =
    s.copy(
      content     = s.content.map(c => rewriteContent(c, table, stripSet)),
      subsections = s.subsections.map(sub => rewriteSection(sub, table, stripSet))
    )

  private def rewriteContent(
      c: Content, table: Map[String, Linker.MacroExpansion], stripSet: Set[String]): Content =
    c match
      case cb: Content.CodeBlock if cb.tree.isDefined => rewriteBlock(cb, table, stripSet)
      case other                                      => other

  private def rewriteBlock(
      cb: Content.CodeBlock, table: Map[String, Linker.MacroExpansion], stripSet: Set[String]): Content =
    cb.tree match
      case None => cb
      case Some(node) =>
        val (newNode, newSrc) = transformUnit(node, cb.source, table, stripSet)
        if newNode eq node then cb else cb.copy(source = newSrc, tree = Some(newNode))

  /** Strip macro-def statements + expand macro call sites in one code unit, then
   *  re-parse to a fresh tree. Returns the same `(node, source)` (same `node`
   *  identity) when nothing changed or the tree shape is unsupported / the
   *  re-parse fails — never worse than today. */
  private def transformUnit(
      node: ScalaNode, source: String,
      table: Map[String, Linker.MacroExpansion], stripSet: Set[String]): (ScalaNode, String) =
    nodeStats(node) match
      case None => (node, source)
      case Some(stats) =>
        val kept     = stats.filterNot(isMacroDefStat(_, stripSet))
        val keptSrc  = kept.map(_.syntax).mkString("\n\n")
        val expanded = expandCalls(keptSrc, table)
        if expanded == keptSrc && kept.size == stats.size then (node, source)
        else
          Parser.parseScalaWithDiagnostic(expanded) match
            case (Some(tree), _) => (tree, expanded)
            case (None, _)       => (node, source)   // re-parse failed → keep original

  // ── codegen-oriented expansion (block-val, scalac-compilable) ─────────

  /** A macro's codegen template: the selected branch body plus the variable it
   *  binds to the call-site argument. We beta-reduce by substituting that
   *  variable with the argument directly — a lambda (`((n) => body)(7)`) is
   *  rejected by scalac ("missing parameter type") and a block argument
   *  (`f({ val n = 7; body })`) re-renders as a brace-arg; a substituted,
   *  parenthesised expression embeds cleanly anywhere. */
  private enum Tmpl:
    case Fold(fold: Linker.AsValueFold)
    case Quote(param: String, body: String)

  private def templateFor(m: Linker.MacroExpansion): Option[Tmpl] =
    Linker.parseAsValueFold(m.bodySource) match
      case Some(f) => Some(Tmpl.Fold(f))
      case None =>
        val b = m.bodySource.trim
        if b.startsWith("__ssc_quote_expr__(") || b.startsWith("'{") then
          m.paramNames.headOption.map(p => Tmpl.Quote(p, Linker.normalizeQuotedMacroBody(b)))
        else None

  /** `name(arg)` → `(body[bind := (arg)])` for the selected branch. */
  private def emit(t: Tmpl, arg: String): String = t match
    case Tmpl.Fold(f) =>
      if Linker.isLiteralArg(arg) then "(" + substituteVar(f.someBranch, f.binder, arg) + ")"
      else "(" + substituteVar(f.noneBranch, f.param, arg) + ")"
    case Tmpl.Quote(p, body) => "(" + substituteVar(body, p, arg) + ")"

  /** Replace every whole-word occurrence of `name` in `body` with `(arg)`,
   *  skipping string/char literals so a binder name appearing inside a string
   *  is never corrupted. */
  private def substituteVar(body: String, name: String, arg: String): String =
    val repl = "(" + arg + ")"
    val sb   = new java.lang.StringBuilder(body.length + arg.length + 8)
    val len  = body.length
    var i    = 0
    def isIdentChar(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '$'
    while i < len do
      val c = body(i)
      if c == '"' || c == '\'' then
        sb.append(c); i += 1
        while i < len && body(i) != c do
          if body(i) == '\\' && i + 1 < len then { sb.append(body(i)); i += 1 }
          sb.append(body(i)); i += 1
        if i < len then { sb.append(body(i)); i += 1 }   // closing delimiter
      else if isIdentChar(c) && (i == 0 || !isIdentChar(body(i - 1))) &&
              body.startsWith(name, i) &&
              (i + name.length >= len || !isIdentChar(body(i + name.length))) then
        sb.append(repl); i += name.length
      else
        sb.append(c); i += 1
    sb.toString

  /** Expand single-argument macro call sites in `src` to their block-val
   *  expansion. Mirrors the Linker's call-site scanner (string/word-boundary
   *  aware) but emits scalac-compilable beta-reduced blocks. */
  private def expandCalls(src: String, table: Map[String, Linker.MacroExpansion]): String =
    val templates = table.flatMap { case (name, m) => templateFor(m).map(name -> _) }
    if templates.isEmpty || src.isEmpty then return src
    val sb  = new java.lang.StringBuilder(src.length + 64)
    val len = src.length
    var i   = 0
    var modified = false

    def isIdentChar(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '$'
    def skipStringLit(j: Int): Int =
      val delim = src(j); var k = j + 1
      while k < len && src(k) != delim do
        if src(k) == '\\' then k += 1
        k += 1
      if k < len then k + 1 else k
    def extractSingleArg(from: Int): Option[(String, Int)] =
      var depth = 1; var k = from
      val buf = new java.lang.StringBuilder
      while k < len && depth > 0 do
        src(k) match
          case '"' | '\'' => val e = skipStringLit(k); buf.append(src, k, e); k = e
          case '('        => depth += 1; buf.append('('); k += 1
          case ')'        => depth -= 1; if depth > 0 then buf.append(')'); k += 1
          case c          => buf.append(c); k += 1
      if depth != 0 then None else Some(buf.toString.trim -> (k - 1))

    def appendCharAdvance(): Unit = { sb.append(src(i)); i += 1 }

    while i < len do
      src(i) match
        case '"' | '\'' => val e = skipStringLit(i); sb.append(src, i, e); i = e
        case c =>
          val matched =
            if isIdentChar(c) && (i == 0 || !isIdentChar(src(i - 1))) then
              templates.find { case (name, _) =>
                src.startsWith(name, i) &&
                  (i + name.length >= len || !isIdentChar(src(i + name.length)))
              }
            else None
          matched match
            case Some((name, tmpl)) =>
              var j = i + name.length
              while j < len && src(j).isWhitespace do j += 1
              if j < len && src(j) == '(' then
                extractSingleArg(j + 1) match
                  case Some((arg, endPos)) => modified = true; sb.append(emit(tmpl, arg)); i = endPos + 1
                  case None                => appendCharAdvance()
              else appendCharAdvance()
            case None => appendCharAdvance()

    if modified then sb.toString else src

  private def topLevelStats(t: scala.meta.Tree): Option[List[Stat]] = t match
    case s: Source     => Some(s.stats)
    case b: Term.Block => Some(b.stats)
    case _             => None

  /** Top-level statements of a parsed unit, guarding the lazy `ScalaNode.tree`
   *  access (a deferred node can throw on a failed parse → treat as no stats). */
  private def nodeStats(node: ScalaNode): Option[List[Stat]] =
    scala.util.Try(topLevelStats(node.tree)).toOption.flatten

  private def blockStats(cb: Content.CodeBlock): Option[List[Stat]] =
    cb.tree.flatMap(nodeStats)

  private def isMacroDefStat(st: Stat, stripSet: Set[String]): Boolean = st match
    case d: Defn.Def => stripSet.contains(d.name.value)
    case _           => false

  /** Walk every top-level statement of every parseable code block in the
   *  module (used for macro discovery). */
  private def foreachTopStat(module: Module)(f: Stat => Unit): Unit =
    def walkSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock => blockStats(cb).foreach(_.foreach(f))
        case _                     => ()
      }
      s.subsections.foreach(walkSection)
    module.sections.foreach(walkSection)
