package scalascript.codegen

import scalascript.ast.*
import scala.collection.mutable
import scala.meta.*

/** Generates a Scala 3 script (.sc) from a ScalaScript module.
 *
 *  Pure ssc/scala code blocks are emitted as-is. Effect declarations
 *  (`effect E:`), `handle(body) { cases }` expressions, and the bodies of
 *  functions that transitively perform effects are rewritten to use a
 *  trampolined Free Monad runtime emitted in the preamble.
 *
 *  The runtime, the analysis, and the CPS transform mirror the JS backend so
 *  semantics line up across `ssc run`, `ssc compile`, and `ssc emit-js`.
 */
object JvmGen:

  def generate(module: Module, baseDir: Option[os.Path] = None): String =
    JvmGen(baseDir).genModule(module)

  private case class Block(node: ScalaNode, src: String)
  /** A heading-bound `html` / `css` code block: render to a string in the
   *  same source position as the surrounding parsed blocks, then bind to
   *  `<sectionId>.<lang>` (html or css) at the end of the module. */
  private case class StringBlockEntry(lang: String, src: String, sectionId: String, order: Int)

class JvmGen(baseDir: Option[os.Path] = None):
  // Effect operations declared in the module, keyed as "Eff.op".
  private val effectOps     = mutable.Set.empty[String]
  // Functions whose body transitively performs effects; their bodies are
  // emitted in CPS form.
  private val effectfulFuns = mutable.Set.empty[String]
  // funName → full set of clique members (including self) for every function
  // that participates in a mutually-recursive tail-call SCC of size ≥ 2.
  private val mutualGroups  = mutable.Map.empty[String, Set[String]]
  // Resolved paths of files already inlined via Content.Import, so a diamond
  // import doesn't emit the same definitions twice.
  private val importedFiles = mutable.Set.empty[String]

  // ─── Module entry ─────────────────────────────────────────────────

  def genModule(module: Module): String =
    // Collect blocks first — including those pulled in by `[..](./x.ssc)`
    // imports — so the effect / mutual-TCO analysis sees the full picture.
    val blocks = collectBlocks(module.sections)
    analyzeEffects(blocks)
    analyzeMutualRecursion(blocks)
    val sb = StringBuilder()

    // //> using directives from YAML front-matter
    module.manifest.foreach { m =>
      m.dependencies.foreach { (dep, version) =>
        sb.append(s"""//> using dep "$dep:$version"\n""")
      }
    }

    val frontmatterRoutes = module.manifest.toList.flatMap(_.routes)

    sb.append(preamble)
    sb.append(htmlDslTagBindings(collectUserTopNames(blocks)))
    if effectOps.nonEmpty                                  then sb.append(effectsRuntime)
    if mutualGroups.nonEmpty                               then sb.append(mutualTcoRuntime)
    if blocksUseRoutes(blocks) || frontmatterRoutes.nonEmpty then sb.append(serveRuntime)

    // Front-matter route declarations are emitted as `route(method, path)
    // { req => handler(req) }` calls.  We place them BEFORE the user blocks
    // because the user's `serve(port)` typically appears as the last
    // statement of their script and blocks forever — registrations
    // afterwards would never run.  Forward references to the handler defs
    // work because `.sc` script files wrap all top-level defs as methods
    // of an enclosing class, so they're accessible throughout the body.
    frontmatterRoutes.foreach { r =>
      val esc = r.path.replace("\\", "\\\\").replace("\"", "\\\"")
      sb.append(s"""route("${r.method}", "$esc") { req => ${r.handler}(req) }\n""")
    }
    if frontmatterRoutes.nonEmpty then sb.append("\n")

    blocks.foreach { block =>
      sb.append(emitBlock(block).stripTrailing())
      sb.append("\n\n")
    }

    // Emit heading-bound string blocks as `lazy val` accessors on a per-section
    // object.  `lazy val` makes the body see definitions that appear earlier
    // OR LATER in the module, matching the interpreter's "evaluate at access
    // time" behaviour (forward references work via Scala's initialisation order).
    emitStringBlocks(sb)

    // Auto-call main entry if declared in front-matter.
    val mainEntry = module.manifest
      .flatMap(_.raw.get("main"))
      .collect { case s: String => s }
    mainEntry.foreach { name => sb.append(s"$name()\n") }

    sb.toString.stripTrailing() + "\n"

  private def emitStringBlocks(sb: StringBuilder): Unit =
    if stringBlocks.isEmpty then return
    sb.append("\n// ── Heading-bound html / css blocks ─────────────────────────────────\n")
    // Group by section id; emit one object per section with the present
    // language fields.
    stringBlocks.groupBy(_.sectionId).foreach { case (id, entries) =>
      sb.append(s"object $id:\n")
      entries.sortBy(_.order).foreach { e =>
        sb.append(s"  lazy val ${e.lang}: String = ${stringBlockTemplate(e.src, e.lang == Lang.Html)}\n")
      }
      sb.append("\n")
    }

  /** Build a Scala 3 `s""" ... """` template that mirrors the interpreter
   *  rendering: each `${expr}` is wrapped in `_html_interp(...)` for html
   *  blocks (auto-escape unless `_Raw`) or `_show(...)` for css blocks.
   *  Literal `$` outside `${...}` is escaped to `$$`. */
  private def stringBlockTemplate(src: String, escape: Boolean): String =
    val sb = StringBuilder()
    sb.append("s\"\"\"")
    var i = 0
    while i < src.length do
      val c = src.charAt(i)
      if c == '$' && i + 1 < src.length && src.charAt(i + 1) == '{' then
        val end = findClose(src, i + 2)
        if end < 0 then
          sb.append("$$").append(src.substring(i + 1)); i = src.length
        else
          val expr = src.substring(i + 2, end).trim
          val wrap = if escape then "_html_interp" else "_show"
          sb.append("${").append(wrap).append("(").append(expr).append(")}")
          i = end + 1
      else if c == '$' then
        sb.append("$$"); i += 1
      else
        sb.append(c); i += 1
    sb.append("\"\"\"")
    sb.toString

  private def findClose(src: String, from: Int): Int =
    var depth = 1
    var i = from
    while i < src.length && depth > 0 do
      src.charAt(i) match
        case '{' => depth += 1
        case '}' => depth -= 1; if depth == 0 then return i
        case _   => ()
      i += 1
    -1

  // Heading-bound string blocks collected during `collectBlocks`; emitted
  // as `_<id>_<lang>` String vals interleaved with parsed blocks (so they
  // see preceding definitions), then wrapped in companion objects at the end.
  private val stringBlocks = mutable.ArrayBuffer.empty[JvmGen.StringBlockEntry]

  private def collectBlocks(sections: List[Section]): List[JvmGen.Block] =
    sections.flatMap { s =>
      val sectionId = sectionIdent(s.heading.text)
      val own = s.content.flatMap {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.map(t => JvmGen.Block(t, cb.source)).toList
        case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
          // Reserve a position in the eventual emission order so the
          // String val lands between the surrounding parsed blocks.
          sectionId.foreach { id =>
            stringBlocks += JvmGen.StringBlockEntry(cb.lang, cb.source, id, stringBlocks.length)
          }
          Nil
        case imp: Content.Import =>
          inlineImport(imp.path)
        case _ => Nil
      }
      own ++ collectBlocks(s.subsections)
    }

  /** Mirror Interpreter / JsGen `sectionIdent`. */
  private def sectionIdent(text: String): Option[String] =
    val parts = text.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val head = parts.head
      val tail = parts.tail.map(p => p.head.toUpper + p.tail)
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  /** Resolve a `[name](./path.ssc)` Markdown import: parse the referenced
   *  file and return its code blocks, transitively following its own imports.
   *  Each path is inlined at most once per JvmGen run. */
  private def inlineImport(path: String): List[JvmGen.Block] =
    import scalascript.parser.Parser
    val resolved = baseDir match
      case Some(dir) => dir / os.RelPath(path)
      case None      => os.Path(path, os.pwd)
    val key = resolved.toString
    if importedFiles.contains(key) then Nil
    else if !os.exists(resolved) then
      throw new RuntimeException(s"Import not found: $path")
    else
      importedFiles += key
      val importedModule = Parser.parse(os.read(resolved))
      // Use a nested JvmGen for transitive imports so relative paths resolve
      // against the imported file's directory.
      val nested = new JvmGen(Some(resolved / os.up))
      nested.importedFiles ++= importedFiles
      nested.collectBlocks(importedModule.sections)

  // ─── Effect analysis ──────────────────────────────────────────────

  private def analyzeEffects(blocks: List[JvmGen.Block]): Unit =
    effectOps.clear()
    effectfulFuns.clear()

    val funBodies = mutable.Map[String, Term]()

    def collectFromStats(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Object =>
        d.templ.body.stats.foreach {
          case dd: Defn.Def if isEffectOpDef(dd.body) =>
            effectOps += s"${d.name.value}.${dd.name.value}"
          case _ => ()
        }
      case d: Defn.Def => funBodies(d.name.value) = d.body
      case _           => ()
    }

    blocks.foreach { block =>
      ScalaNode.fold(block.node) {
        case Source(stats)     => collectFromStats(stats)
        case Term.Block(stats) => collectFromStats(stats)
        case _                 => ()
      }
    }

    def callees(tree: scala.meta.Tree): Set[String] = tree match
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) =>
        Set(n) ++ argClause.values.flatMap(callees).toSet
      case Term.Apply.After_4_6_0(Term.Select(Term.Name(qual), Term.Name(method)), argClause) =>
        Set(s"$qual.$method") ++ argClause.values.flatMap(callees).toSet
      case Term.Apply.After_4_6_0(fun, argClause) =>
        callees(fun) ++ argClause.values.flatMap(callees).toSet
      case Term.Select(Term.Name(qual), Term.Name(method)) =>
        Set(s"$qual.$method")
      case other =>
        other.children.flatMap(callees).toSet

    var changed = true
    while changed do
      changed = false
      funBodies.foreach { (fname, body) =>
        if !effectfulFuns.contains(fname) then
          val calls = callees(body)
          if calls.exists(c => effectOps.contains(c) || effectfulFuns.contains(c)) then
            effectfulFuns += fname
            changed = true
      }

  private def isEffectOpDef(body: Term): Boolean = body match
    case Term.Name("__effectOp__") => true
    case _                         => false

  private def isEffectOpRef(eff: String, op: String): Boolean =
    effectOps.contains(s"$eff.$op")

  private def isEffectfulFun(name: String): Boolean = effectfulFuns.contains(name)

  // ─── Routing detection ───────────────────────────────────────────
  //
  // True when any code block invokes `route(...)`, in which case JvmGen
  // emits the serve runtime (Request/Response, registry, HTTP dispatcher).

  private def blocksUseRoutes(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("route"), _) => found = true
        }
      }
      found
    }

  // ─── Mutual-recursion analysis ────────────────────────────────────
  //
  // Build a graph of tail-position calls between non-effectful, single-clause
  // functions (multi-clause and effectful functions are out of scope — the
  // CPS path already trampolines effects, and curried tail recursion is rare
  // enough that we skip it).  Compute SCCs; any SCC of size ≥ 2 is a mutual
  // tail-recursion clique that the emitter will trampoline.

  private def analyzeMutualRecursion(blocks: List[JvmGen.Block]): Unit =
    mutualGroups.clear()
    val callGraph = mutable.Map[String, Set[String]]()

    def collectFuncs(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def
          if !isEffectfulFun(d.name.value)
          && !hasInterParamDefault(d)
          && isSingleClauseDef(d) =>
        callGraph(d.name.value) =
          tailCallTargets(d.body, d.name.value, tailPos = true)
      case _ => ()
    }

    blocks.foreach { block =>
      ScalaNode.fold(block.node) {
        case Source(stats)     => collectFuncs(stats)
        case Term.Block(stats) => collectFuncs(stats)
        case _                 => ()
      }
    }

    val funcNames = callGraph.keySet.toSet
    val sccs = findSCCs(callGraph.toMap, funcNames)
    sccs.filter(_.size > 1).foreach { scc =>
      scc.foreach { name => mutualGroups(name) = scc }
    }

  private def isSingleClauseDef(d: Defn.Def): Boolean =
    d.paramClauseGroups.size == 1 &&
    d.paramClauseGroups.head.paramClauses.size == 1

  /** Names of functions called in tail position inside `tree`, excluding
   *  `selfName` (self-recursion is handled by the while-loop reassignment
   *  inside _impl, not by a graph edge). */
  private def tailCallTargets(
      tree:     scala.meta.Tree,
      selfName: String,
      tailPos:  Boolean
  ): Set[String] =
    tree match
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) =>
        if tailPos && n != selfName then Set(n)
        else argClause.values.flatMap(a => tailCallTargets(a, selfName, false)).toSet
      case t: Term.If =>
        tailCallTargets(t.cond,  selfName, false) ++
        tailCallTargets(t.thenp, selfName, tailPos) ++
        tailCallTargets(t.elsep, selfName, tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).flatMap(s => tailCallTargets(s, selfName, false)).toSet ++
        stats.lastOption.map(s => tailCallTargets(s, selfName, tailPos)).getOrElse(Set.empty)
      case t: Term.Match =>
        tailCallTargets(t.expr, selfName, false) ++
        t.casesBlock.cases.flatMap(c => tailCallTargets(c.body, selfName, tailPos)).toSet
      case other =>
        other.children.flatMap(c => tailCallTargets(c, selfName, false)).toSet

  /** Tarjan's algorithm — returns the SCCs of the directed graph. */
  private def findSCCs(
      graph: Map[String, Set[String]],
      names: Set[String]
  ): List[Set[String]] =
    var idx = 0
    val stack   = mutable.Stack[String]()
    val onStk   = mutable.Set[String]()
    val nodeIdx = mutable.Map[String, Int]()
    val low     = mutable.Map[String, Int]()
    val result  = mutable.ListBuffer[Set[String]]()

    def connect(v: String): Unit =
      nodeIdx(v) = idx; low(v) = idx; idx += 1
      stack.push(v); onStk += v
      for w <- graph.getOrElse(v, Set.empty) if names.contains(w) do
        if !nodeIdx.contains(w) then
          connect(w)
          low(v) = low(v) min low(w)
        else if onStk.contains(w) then
          low(v) = low(v) min nodeIdx(w)
      if low(v) == nodeIdx(v) then
        val scc = mutable.Set[String]()
        var w = ""
        while { w = stack.pop(); onStk -= w; scc += w; w != v } do ()
        result += scc.toSet

    for v <- names do
      if !nodeIdx.contains(v) then connect(v)
    result.toList

  private def isInMutualClique(name: String): Boolean =
    mutualGroups.contains(name)

  // ─── Block emission ───────────────────────────────────────────────

  private def emitBlock(block: JvmGen.Block): String =
    // If the block has no effects content, no mutual-TCO clique, and no
    // last-expression auto-output, the original source compiles as-is —
    // modulo a pass that routes `.mkString(...)` and `s"..."` through
    // `_show` so whole-number Doubles strip trailing ".0" the way the
    // interpreter and JS backends do.
    val rewritten =
      if !blockNeedsRewrite(block.node) then block.src
      else
        val out = StringBuilder()
        ScalaNode.fold(block.node) {
          case Source(stats)     => emitStats(stats, out, isTopLevel = true)
          case Term.Block(stats) => emitStats(stats, out, isTopLevel = true)
          case t: Term           => out.append(wrapAutoOutput(emitExpr(t))).append("\n")
          case _                 => ()
        }
        out.toString
    routeMkStringThroughShow(rewritten)

  /** Wrap a top-level expression so its non-Unit, non-null result is
   *  printed — mirrors interpreter `autoOutput` and JsGen's `_auto` block.
   *  Goes through the overridden `println` so Doubles strip ".0". */
  private def wrapAutoOutput(expr: String): String =
    s"{ val _auto: Any = $expr; if _auto != () && _auto != null then println(_auto) }"

  /** Route emitted code through `_show` for the cases where Scala 3's
   *  default Any.toString would print a whole-number Double as "4.0":
   *
   *    - `expr.mkString(...)`  →  `expr.map(_show).mkString(...)`
   *    - `s"... $x ..."`       →  `sx"... $x ..."`  (sx is defined in preamble)
   *
   *  `_show` is identity for non-Doubles, so other element types are
   *  unaffected. The patterns are conservative enough not to match inside
   *  identifiers (e.g. `bytes"..."` is not rewritten because `\bs"` requires
   *  a word boundary immediately before the `s`). */
  private def routeMkStringThroughShow(src: String): String =
    var out = src
    if out.contains(".mkString(") then
      out = out.replaceAll("""\.mkString\(""", ".map(_show).mkString(")
    if out.contains("s\"") || out.contains("s\"\"\"") then
      // Negative lookbehind for `$` or word char so we don't rewrite the `s`
      // in `$s"..."` (the trailing variable reference inside an s-interp) or
      // in user identifiers like `bytes"..."`.
      out = out.replaceAll("""(?<![$\w])s("{1,3})""", "sx$1")
    out

  private def blockNeedsRewrite(node: ScalaNode): Boolean =
    blockUsesEffects(node) || blockUsesMutualTco(node) || blockHasAutoOutputTerm(node)

  /** True if the top-level node ends with a bare expression — that's the
   *  trigger to take the walking emit path and inject the auto-output wrap. */
  private def blockHasAutoOutputTerm(node: ScalaNode): Boolean =
    ScalaNode.fold(node) {
      case Source(stats)     => stats.lastOption.exists(_.isInstanceOf[Term])
      case Term.Block(stats) => stats.lastOption.exists(_.isInstanceOf[Term])
      case _: Term           => true
      case _                 => false
    }

  private def blockUsesMutualTco(node: ScalaNode): Boolean =
    var found = false
    ScalaNode.fold(node) {
      case Source(stats)     => if statsUseMutualTco(stats) then found = true
      case Term.Block(stats) => if statsUseMutualTco(stats) then found = true
      case _                 => ()
    }
    found

  private def statsUseMutualTco(stats: List[Stat]): Boolean =
    stats.exists {
      case d: Defn.Def => isInMutualClique(d.name.value)
      case _           => false
    }

  /** True if any effect declaration, handle call, effectful function defn, or
   *  effect-op reference appears within `node`. */
  private def blockUsesEffects(node: ScalaNode): Boolean =
    var found = false
    ScalaNode.fold(node) {
      case Source(stats)     => if statsUseEffects(stats) then found = true
      case Term.Block(stats) => if statsUseEffects(stats) then found = true
      case t: Term           => if termUsesEffects(t)     then found = true
      case _                 => ()
    }
    found

  private def statsUseEffects(stats: List[Stat]): Boolean =
    stats.exists {
      case d: Defn.Object =>
        d.templ.body.stats.exists {
          case dd: Defn.Def => isEffectOpDef(dd.body)
          case _            => false
        }
      case d: Defn.Def =>
        isEffectfulFun(d.name.value) || termUsesEffects(d.body) || hasInterParamDefault(d)
      case _: Defn.Enum => true
      case t: Term      => termUsesEffects(t)
      case _            => false
    }

  private def termUsesEffects(t: Term): Boolean = t match
    case Term.Apply.After_4_6_0(Term.Apply.After_4_6_0(Term.Name("handle"), _), _) => true
    case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op)     => true
    case Term.Apply.After_4_6_0(Term.Name(n), _) if isEffectfulFun(n)             => true
    case _ => t.children.exists {
      case tt: Term => termUsesEffects(tt)
      case _        => false
    }

  // ─── Default-param helpers ────────────────────────────────────────
  //
  // ScalaScript allows a default expression to reference earlier parameters
  // in the same clause:    def shift(x: Int, by: Int = x + 1): Int = x + by
  // Scala 3 forbids this. We emit a set of overloads that materialise the
  // defaults at call sites where they're visible.

  private def hasInterParamDefault(d: Defn.Def): Boolean =
    d.paramClauseGroups.exists { group =>
      group.paramClauses.exists { clause =>
        val params = clause.values
        params.zipWithIndex.exists { case (p, i) =>
          p.default.exists { dflt =>
            val earlier = params.take(i).map(_.name.value).toSet
            earlier.nonEmpty && referencesAny(dflt, earlier)
          }
        }
      }
    }

  private def referencesAny(term: Term, names: Set[String]): Boolean = term match
    case Term.Name(n) if names(n) => true
    case _ => term.children.exists {
      case t: Term => referencesAny(t, names)
      case _       => false
    }

  // ─── Statement emission ───────────────────────────────────────────

  private def emitStats(stats: List[Stat], out: StringBuilder, isTopLevel: Boolean = false): Unit =
    stats.zipWithIndex.foreach { (s, i) =>
      val isLast = i == stats.length - 1
      val rendered = emitStat(s)
      val text = s match
        case _: Term if isLast && isTopLevel => wrapAutoOutput(rendered)
        case _                               => rendered
      out.append(text).append("\n")
    }

  private def emitStat(stat: Stat): String = stat match
    // Effect declaration: `effect Console: def writeLine(s): Unit; def readLine(): String`
    // → `object Console: def writeLine(s) = _perform("Console", "writeLine", s)`
    case d: Defn.Object if d.templ.body.stats.exists {
      case dd: Defn.Def => isEffectOpDef(dd.body); case _ => false
    } =>
      val ops = d.templ.body.stats.collect {
        case dd: Defn.Def if isEffectOpDef(dd.body) =>
          val params = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
          val paramSig = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map { p =>
            p.decltpe.map(t => s"${p.name.value}: ${t.syntax}").getOrElse(s"${p.name.value}: Any")
          }.mkString(", ")
          val argList = if params.isEmpty then "" else ", " + params.mkString(", ")
          s"  def ${dd.name.value}($paramSig): Any = _perform(\"${d.name.value}\", \"${dd.name.value}\"$argList)"
      }
      s"object ${d.name.value}:\n${ops.mkString("\n")}\n"

    // Effectful function: emit CPS body
    case d: Defn.Def if isEffectfulFun(d.name.value) =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map { p =>
        p.decltpe.map(t => s"${p.name.value}: ${t.syntax}").getOrElse(s"${p.name.value}: Any")
      }.mkString(", ")
      // Return type set to Any (could be a Free value).
      s"def ${d.name.value}($params): Any = ${emitCpsExpr(d.body)}"

    // val/var with effect-using rhs: transform rhs via emitExpr, which routes
    // `handle(...)` to its CPS rewrite.
    case Defn.Val(mods, pats, tpe, rhs) if termUsesEffects(rhs) =>
      val mod = mods.map(_.syntax).mkString(" ")
      val modStr = if mod.isEmpty then "" else mod + " "
      val patsStr = pats.map(_.syntax).mkString(", ")
      val tpeStr = tpe.map(t => s": ${t.syntax}").getOrElse("")
      s"${modStr}val $patsStr$tpeStr = ${emitExpr(rhs)}"
    case Defn.Var.After_4_7_2(mods, pats, tpe, rhs: Term) if termUsesEffects(rhs) =>
      val mod = mods.map(_.syntax).mkString(" ")
      val modStr = if mod.isEmpty then "" else mod + " "
      val patsStr = pats.map(_.syntax).mkString(", ")
      val tpeStr = tpe.map(t => s": ${t.syntax}").getOrElse("")
      s"${modStr}var $patsStr$tpeStr = ${emitExpr(rhs)}"

    // Enum — emit as-is plus `import EnumName.*` so unqualified case names
    // (`Circle()` rather than `Shape.Circle()`) resolve at use sites, matching
    // ScalaScript interpreter / JS-backend semantics.
    case d: Defn.Enum =>
      s"${d.syntax}\nimport ${d.name.value}.*"

    // Function with a default that references an earlier parameter in the
    // same clause — generate the base def plus one overload per dropped
    // trailing arg, so each call-site arity is reachable.
    case d: Defn.Def if hasInterParamDefault(d) =>
      emitDefWithOverloads(d)

    // Function in a mutual tail-recursion clique: emit a trampolined _impl
    // plus a thin public wrapper.
    case d: Defn.Def if isInMutualClique(d.name.value) =>
      emitMutualTcoFun(d)

    case t: Term => emitExpr(t)

    // Everything else: emit as-is via scalameta's printer.
    case other => other.syntax

  private def emitDefWithOverloads(d: Defn.Def): String =
    val groups = d.paramClauseGroups
    // Only handle the single-clause case; multi-clause defs already let Scala 3
    // see earlier params in defaults, so the as-is printer is fine.
    if groups.size != 1 || groups.head.paramClauses.size != 1 then return d.syntax

    val params  = groups.head.paramClauses.head.values
    val name    = d.name.value
    val retType = d.decltpe.map(t => s": ${t.syntax}").getOrElse("")

    def sigFor(ps: List[Term.Param]): String =
      ps.map { p =>
        p.decltpe.map(t => s"${p.name.value}: ${t.syntax}").getOrElse(s"${p.name.value}: Any")
      }.mkString(", ")

    val baseDef = s"def $name(${sigFor(params)})$retType = ${d.body.syntax}"

    val firstDefault = params.indexWhere(_.default.isDefined)
    val overloads =
      if firstDefault < 0 then Nil
      else (firstDefault until params.length).map { takeN =>
        val taken   = params.take(takeN)
        val missing = params.drop(takeN)
        val args    = taken.map(_.name.value) ++ missing.map(_.default.get.syntax)
        s"def $name(${sigFor(taken)})$retType = $name(${args.mkString(", ")})"
      }

    (baseDef +: overloads).mkString("\n")

  // ─── Mutual-TCO emission ──────────────────────────────────────────
  //
  // For each function f in an SCC of size ≥ 2 we emit:
  //   def _f_impl(_p1: T1, _p2: T2): Any =
  //     var p1: T1 = _p1; var p2: T2 = _p2
  //     while true do
  //       <transformed body — self-calls reassign vars and fall through;
  //        friend-calls return a new _TailCall thunk; other expressions
  //        return their value.>
  //     throw new RuntimeException("unreachable")
  //
  //   def f(p1: T1, p2: T2): R =
  //     _trampoline(() => _f_impl(p1, p2)).asInstanceOf[R]

  private def emitMutualTcoFun(d: Defn.Def): String =
    val fname   = d.name.value
    val params  = d.paramClauseGroups.head.paramClauses.head.values
    val friends = mutualGroups(fname) - fname

    def typeOf(p: Term.Param): String =
      p.decltpe.map(_.syntax).getOrElse("Any")

    val paramNames  = params.map(_.name.value)
    val implName    = s"_${fname}_impl"

    val implParams = params.map(p => s"_${p.name.value}: ${typeOf(p)}").mkString(", ")
    val varDecls   = params.map(p =>
      s"  var ${p.name.value}: ${typeOf(p)} = _${p.name.value}"
    ).mkString("\n")

    val bodyOut = StringBuilder()
    emitMutualTcoBody(d.body, fname, paramNames, friends, indent = 2, bodyOut)

    val impl =
      s"""def $implName($implParams): Any =
         |$varDecls
         |  while true do
         |${bodyOut.toString.stripTrailing}
         |  throw new RuntimeException("unreachable")""".stripMargin

    val wrapperRet = d.decltpe.map(t => s": ${t.syntax}").getOrElse("")
    val cast       = d.decltpe.map(t => s".asInstanceOf[${t.syntax}]").getOrElse("")
    val wrapperSig = params.map(p => s"${p.name.value}: ${typeOf(p)}").mkString(", ")
    val wrapperArgs = paramNames.mkString(", ")
    val wrapper    =
      s"def $fname($wrapperSig)$wrapperRet = _trampoline(() => $implName($wrapperArgs))$cast"

    s"$impl\n$wrapper"

  /** Recursively emit the body of `_f_impl` as Scala statements. Every leaf
   *  is either a self-call (reassign vars, let the while-loop iterate),
   *  a friend-call (return a _TailCall thunk), or any other expression
   *  (returned as the trampoline's final value). */
  private def emitMutualTcoBody(
      term:    Term,
      fname:   String,
      params:  List[String],
      friends: Set[String],
      indent:  Int,
      out:     StringBuilder
  ): Unit =
    val pad = "  " * indent
    term match
      // Self-tail-call: reassign params via temporaries, then fall through so
      // the enclosing while-loop iterates with the new arguments.
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        val args = argClause.values.map(_.syntax)
        val tmps = params.map(p => s"_new_$p")
        out.append(pad).append("{\n")
        tmps.zip(args).foreach { (t, a) =>
          out.append(pad).append("  val ").append(t).append(" = ").append(a).append("\n")
        }
        params.zip(tmps).foreach { (p, t) =>
          out.append(pad).append("  ").append(p).append(" = ").append(t).append("\n")
        }
        out.append(pad).append("}\n")

      // Friend-tail-call: hand the next step to the trampoline.
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) if friends.contains(n) =>
        val args = argClause.values.map(_.syntax).mkString(", ")
        out.append(pad).append(s"return new _TailCall(() => _${n}_impl($args))\n")

      // Conditional in tail position: recurse into both branches.
      case t: Term.If =>
        out.append(pad).append(s"if ${t.cond.syntax} then\n")
        emitMutualTcoBody(t.thenp, fname, params, friends, indent + 1, out)
        out.append(pad).append("else\n")
        emitMutualTcoBody(t.elsep, fname, params, friends, indent + 1, out)

      // Match in tail position: recurse into each case body.
      case t: Term.Match =>
        out.append(pad).append(s"${t.expr.syntax} match\n")
        t.casesBlock.cases.foreach { c =>
          val guard = c.cond.map(g => s" if ${g.syntax}").getOrElse("")
          out.append(pad).append(s"  case ${c.pat.syntax}$guard =>\n")
          emitMutualTcoBody(c.body, fname, params, friends, indent + 2, out)
        }

      // Block: emit non-final stats verbatim, recurse into the tail expression.
      case Term.Block(stats) =>
        stats.dropRight(1).foreach { s =>
          out.append(pad).append(s.syntax).append("\n")
        }
        stats.lastOption match
          case Some(t: Term) =>
            emitMutualTcoBody(t, fname, params, friends, indent, out)
          case Some(s) =>
            out.append(pad).append(s.syntax).append("\n")
            out.append(pad).append("return ()\n")
          case None =>
            out.append(pad).append("return ()\n")

      // Anything else in tail position: this is the final value.
      case other =>
        out.append(pad).append(s"return ${other.syntax}\n")

  // ─── Expression emission ──────────────────────────────────────────

  /** Emit a Scala expression. For non-effectful subtrees, fall through to
   *  scalameta's source. For effect-related subtrees, do custom emission. */
  private def emitExpr(term: Term): String = term match
    // handle(body) { cases }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          emitHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => "??? /* invalid handle */"

    // If the term has nested effect content, recursively process it.
    case _ if termUsesEffects(term) => emitExprDeep(term)

    // Otherwise emit Scala source as-is.
    case other => other.syntax

  /** Emit a term that contains effect-related content, walking children. */
  private def emitExprDeep(term: Term): String = term match
    case Term.Block(stats) =>
      val sb2 = StringBuilder()
      sb2.append("{\n")
      stats.foreach { s => sb2.append("  ").append(emitStat(s)).append("\n") }
      sb2.append("}")
      sb2.toString
    case t: Term.If =>
      s"if ${emitExpr(t.cond)} then ${emitExpr(t.thenp)} else ${emitExpr(t.elsep)}"
    case app: Term.Apply =>
      app.fun match
        case Term.Apply.After_4_6_0(Term.Name("handle"), _) =>
          emitExpr(app)  // re-route to handle path
        case Term.Select(qual, Term.Name(m)) =>
          val q = emitExpr(qual)
          val args = app.argClause.values.map(emitExpr).mkString(", ")
          s"$q.$m($args)"
        case fun =>
          val f = emitExpr(fun)
          val args = app.argClause.values.map(emitExpr).mkString(", ")
          s"$f($args)"
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val l = emitExpr(lhs)
      val r = argClause.values.map(emitExpr).mkString(", ")
      s"$l ${op.value} $r"
    case Term.Select(qual, name) =>
      s"${emitExpr(qual)}.${name.value}"
    case other => other.syntax

  /** Emit `handle(body) { cases }` as a `_handle(...)` call with CPS body. */
  private def emitHandleForm(body: Term, cases: List[Case]): String =
    val handled = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), _) =>
          Some(s"\"$eff.$op\"")
        case _ => None
    }.distinct
    val handlerEntries = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), argClause) =>
          val pats = argClause.values
          val paramNames = pats.zipWithIndex.map { (p, i) =>
            p match
              case Pat.Var(n)      => n.value
              case Pat.Wildcard()  => s"_unused${i}"
              case _               => s"_p${i}"
          }
          // Destructure: last is `resume` (always typed as Any => Any),
          // preceding are operation arguments.
          val (opArgs, resumeName) =
            if paramNames.isEmpty then (Nil, "_unusedResume")
            else (paramNames.init, paramNames.last)
          val bindings = opArgs.zipWithIndex.map { (n, i) =>
            s"val $n = _args($i)"
          }.mkString("; ")
          val resumeBind = s"val $resumeName = _args(${opArgs.length}).asInstanceOf[Any => Any]"
          val bodyJs = emitCaseBody(c.body)
          val all = (if bindings.isEmpty then List(resumeBind) else List(bindings, resumeBind))
                      .mkString("; ")
          Some(s""""$eff.$op" -> ((_args: List[Any]) => { $all; $bodyJs })""")
        case _ => None
    }
    val bodyThunk = s"() => ${emitCpsExpr(body)}"
    val handlersMap = handlerEntries.mkString(",\n  ")
    s"""_handle($bodyThunk, Set(${handled.mkString(", ")}), Map(
  $handlersMap
))"""

  /** Emit a handler case body. Mostly verbatim Scala, but `<list>.flatMap(...)`
   *  is rewritten to use a runtime helper so the callback may return either a
   *  plain value or an iterable (mirrors JS-style loose flatMap). */
  private def emitCaseBody(t: Term): String = t match
    case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("flatMap")), argClause) =>
      val q  = emitCaseBody(qual)
      val fn = argClause.values.map(emitCaseBody).mkString(", ")
      s"_anyFlatMap($q, $fn)"
    // `x :: xs` where both operands are Any-typed (typical inside handler
    // bodies, since `_args(i)` and `resume(...)` are both Any). Cast the RHS
    // so Scala 3 type-checks the List cons.
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("::"), _, argClause) =>
      val l = emitCaseBody(lhs)
      val r = emitCaseBody(argClause.values.head)
      s"($l :: $r.asInstanceOf[List[Any]])"
    case Term.Apply.After_4_6_0(fun, argClause) =>
      val f = emitCaseBody(fun)
      val a = argClause.values.map(emitCaseBody).mkString(", ")
      s"$f($a)"
    case Term.Function.After_4_6_0(paramClause, body) =>
      val ps = paramClause.values.map(_.name.value).mkString(", ")
      val wrap = if paramClause.values.length == 1 then ps else s"($ps)"
      s"$wrap => ${emitCaseBody(body)}"
    case Term.Block(stats) =>
      val items = stats.map {
        case t: Term => emitCaseBody(t)
        case s       => s.syntax
      }
      "{ " + items.mkString("; ") + " }"
    case other => other.syntax

  // ─── CPS transform ────────────────────────────────────────────────
  //
  // The CPS transform converts direct-style ssc code to monadic-style Scala
  // that builds a Free tree at runtime.  Pure sub-expressions stay as-is;
  // function calls and effect ops are threaded through `_bind`.

  private def isSimpleCps(t: Term): Boolean = t match
    case _: Lit                                  => true
    case Term.Name(n) if !isEffectfulFun(n)      => true
    case _                                       => false

  /** Bind a list of CPS sub-expressions; pass their values into `k`. */
  private def bindArgsCps(args: List[Term])(k: List[String] => String): String =
    def loop(remaining: List[Term], acc: List[String]): String = remaining match
      case Nil       => k(acc.reverse)
      case t :: rest =>
        if isSimpleCps(t) then loop(rest, t.syntax :: acc)
        else
          val v = freshTmp()
          s"_bind(${emitCpsExpr(t)}, ($v: Any) => ${loop(rest, v :: acc)})"
    loop(args, Nil)

  private var tmpIdx = 0
  private def freshTmp(): String = { tmpIdx += 1; s"_t$tmpIdx" }

  /** Emit a Scala expression in CPS form. */
  private def emitCpsExpr(term: Term): String = term match
    case _: Lit       => term.syntax
    case Term.Name(_) => term.syntax

    case Term.Block(stats)            => emitCpsBlock(stats)
    case t: Term.If                   =>
      val tmp = freshTmp()
      val thenJs = emitCpsExpr(t.thenp)
      val elseJs = t.elsep match
        case Lit.Unit() => "()"
        case e          => emitCpsExpr(e)
      if isSimpleCps(t.cond) then s"(if ${t.cond.syntax} then ($thenJs) else ($elseJs))"
      else
        s"_bind(${emitCpsExpr(t.cond)}, ($tmp: Any) => (if ${tmp}.asInstanceOf[Boolean] then ($thenJs) else ($elseJs)))"

    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md" =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val sb2 = StringBuilder()
        sb2.append(s"""$prefix"""")
        for i <- parts.indices do
          sb2.append(parts(i).asInstanceOf[Lit.String].value
            .replace("\\", "\\\\").replace("\"", "\\\""))
          if i < args.length then sb2.append("${").append(vs(i)).append("}")
        sb2.append("\"")
        sb2.toString
      }

    case Term.Tuple(elems) =>
      bindArgsCps(elems) { vs => s"(${vs.mkString(", ")})" }

    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values.map { p =>
        val tpe = p.decltpe.map(t => s": ${t.syntax}").getOrElse(": Any")
        s"${p.name.value}${tpe}"
      }
      val wrap = if params.length == 1 then params.head else s"(${params.mkString(", ")})"
      s"$wrap => ${emitCpsExpr(body)}"

    // Nested handle inside CPS body
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          emitHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => "??? /* invalid handle */"

    case app: Term.Apply => emitCpsApply(app)

    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val rhs = argClause.values.head
      bindArgsCps(List(lhs, rhs)) { case List(vl, vr) =>
        op.value match
          case "==" | "!="    => s"($vl ${op.value} $vr)"
          case "&&" | "||"    => s"(${vl}.asInstanceOf[Boolean] ${op.value} ${vr}.asInstanceOf[Boolean])"
          // Arithmetic / comparison operators: operands are Any in CPS context,
          // so delegate to a runtime helper that pattern-matches on the actual
          // numeric / String types.
          case "+" | "-" | "*" | "/" | "%" |
               "<" | ">" | "<=" | ">="          => s"""_binOp("${op.value}", $vl, $vr)"""
          case "::"                              => s"$vl :: $vr.asInstanceOf[List[Any]]"
          case "++" | ":::"                      => s"$vl.asInstanceOf[List[Any]] ++ $vr.asInstanceOf[List[Any]]"
          case other                             => s"($vl $other $vr)"
        case _ => "/* infix arity */"
      }

    case Term.Select(qual, name) =>
      bindArgsCps(List(qual)) { case List(q) => s"$q.${name.value}"; case _ => "/* select */" }

    case t: Term.Match =>
      bindArgsCps(List(t.expr)) { case List(sv) =>
        val arms = t.casesBlock.cases.map { c =>
          val guard = c.cond.map(g => s" if ${g.syntax}").getOrElse("")
          s"  case ${c.pat.syntax}${guard} => ${emitCpsExpr(c.body)}"
        }.mkString("\n")
        s"($sv match {\n$arms\n})"
        case _ => "/* match */"
      }

    // Fallback to verbatim — caller should ensure no nested effects here.
    case other => other.syntax

  private def emitCpsApply(app: Term.Apply): String =
    val args = app.argClause.values
    app.fun match
      // Effect op call: bind args, then _perform
      case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op) =>
        bindArgsCps(args) { vs =>
          val argTail = if vs.isEmpty then "" else ", " + vs.mkString(", ")
          s"""_perform("$eff", "$op"$argTail)"""
        }

      // Method call on a non-effectful value: bind qual + args, dispatch
      case Term.Select(qual, Term.Name(method)) =>
        bindArgsCps(qual :: args) { vs =>
          s"${vs.head}.${method}(${vs.tail.mkString(", ")})"
        }

      case fun =>
        // The function reference itself is always a callable value (not a
        // Free), so we never bind on `fun` — only on its args. The call's
        // result may be a Free; the caller's bind handles that.
        bindArgsCps(args) { vs => s"${fun.syntax}(${vs.mkString(", ")})" }

  /** Emit a Scala block in CPS form: thread vals + statements via `_bind`. */
  private def emitCpsBlock(stats: List[Stat]): String =
    if stats.isEmpty then "()"
    else
      def build(remaining: List[Stat]): String = remaining match
        case Nil => "()"
        case List(s) =>
          s match
            case t: Term => emitCpsExpr(t)
            case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
              s"_bind(${emitCpsExpr(rhs)}, (${n.value}: Any) => ())"
            case other => s"{ ${other.syntax}; () }"
        case s :: rest =>
          s match
            case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
              s"_bind(${emitCpsExpr(rhs)}, (${n.value}: Any) => ${build(rest)})"
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
              s"_bind(${emitCpsExpr(rhs)}, (${n.value}: Any) => ${build(rest)})"
            case t: Term =>
              if isSimpleCps(t) then s"{ ${t.syntax}; ${build(rest)} }"
              else
                val tmp = freshTmp()
                s"_bind(${emitCpsExpr(t)}, (${tmp}: Any) => ${build(rest)})"
            case other => s"{ ${other.syntax}; ${build(rest)} }"
      build(stats)

  // ─── Preamble + runtime ───────────────────────────────────────────

  // HTML DSL tag names. Tags collide with top-level user `val`/`def`/`object`
  // names (Scala can't shadow within the same scope), so we filter the list
  // against `userTopNames` before emission — mirroring the JS preamble's
  // `if (globalThis[k] === undefined)` guard for the same reason.
  private val containerTagNames: List[String] = List(
    "html","head","body","title","style","script","main",
    "section","header","footer","nav","article","aside",
    "div","span","p","a","em","strong","small","code","pre",
    "h1","h2","h3","h4","h5","h6",
    "ul","ol","li","dl","dt","dd",
    "table","thead","tbody","tfoot","tr","td","th",
    "form","button","label","select","option","textarea",
    "figure","figcaption","blockquote"
  )
  private val voidTagNames: List[String] = List(
    "br","hr","img","input","link","meta"
  )

  private def htmlDslTagBindings(userTopNames: Set[String]): String =
    val sb = StringBuilder()
    sb.append("\n// Tag value bindings (skipped where the user binds the same name)\n")
    containerTagNames.filterNot(userTopNames.contains).foreach { t =>
      sb.append(s"""val $t = _Tag("$t")\n""")
    }
    voidTagNames.filterNot(userTopNames.contains).foreach { t =>
      sb.append(s"""val $t = _Tag("$t", voidTag = true)\n""")
    }
    sb.append("\n")
    sb.toString

  /** Collect top-level identifiers defined in the user's parsed blocks
   *  (val, def, object, class, enum, trait, type, given). Local bindings
   *  inside function bodies don't reach this set — they shadow at their
   *  own scope and don't conflict with module-level tag vals. */
  private def collectUserTopNames(blocks: List[JvmGen.Block]): Set[String] =
    val names = mutable.Set.empty[String]
    def fromStats(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Val => d.pats.foreach { case Pat.Var(n) => names += n.value; case _ => () }
      case Defn.Var.After_4_7_2(_, pats, _, _) => pats.foreach { case Pat.Var(n) => names += n.value; case _ => () }
      case d: Defn.Def    => names += d.name.value
      case d: Defn.Object => names += d.name.value
      case d: Defn.Class  => names += d.name.value
      case d: Defn.Trait  => names += d.name.value
      case d: Defn.Enum   => names += d.name.value
      case d: Defn.Type   => names += d.name.value
      case d: Defn.Given  => names += d.name.value
      case _ => ()
    }
    blocks.foreach { block =>
      block.node match
        case Source(stats)     => fromStats(stats)
        case Term.Block(stats) => fromStats(stats)
        case _                 => ()
    }
    names.toSet

  private val preamble: String =
    """|
       |// ── Show / println override (scripting-style Double formatting) ────────
       |// Mirrors the interpreter / JS backends: a Double whose value is an
       |// integer renders without the trailing ".0" (e.g. 4.0 → "4").
       |def _show(v: Any): String = v match
       |  case d: Double => if d == d.toLong.toDouble then d.toLong.toString else d.toString
       |  case s: String => s
       |  case null      => "null"
       |  // _Raw HTML nodes (from `raw(...)`, html"...", or DSL tag fns) render
       |  // as their inner string so `println(div(...))` prints the markup.
       |  case r: _Raw   => r.html
       |  // Render a Range like a List so xs.indices and similar lazy
       |  // iterables match the interpreter / JS output ("List(0, 1, 2)").
       |  case r: scala.collection.immutable.Range => r.toList.map(_show).mkString("List(", ", ", ")")
       |  case other     => other.toString
       |
       |def println(v: Any): Unit = scala.Predef.println(_show(v))
       |
       |// `sx` is like `s` but routes each interpolated value through `_show`,
       |// so a whole-number Double interpolated into a string drops its ".0".
       |// Code-block emission rewrites `s"..."` to `sx"..."` for the same reason.
       |extension (sc: StringContext)
       |  def sx(args: Any*): String = sc.s(args.map(_show)*)
       |
       |extension (sc: StringContext)
       |  def md(args: Any*): String =
       |    val s = sc.s(args*)
       |    val lines = s.split("\n", -1).toSeq
       |    val body = lines.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
       |    if body.isEmpty then ""
       |    else
       |      val indent = body.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).min
       |      body.map(_.drop(indent)).mkString("\n")
       |
       |// ── HTML / CSS string interpolators ────────────────────────────────────
       |// html"..." auto-escapes interpolated values unless wrapped in raw(s).
       |case class _Raw(html: String)
       |def raw(s: Any): _Raw = _Raw(_show(s))
       |
       |def _htmlEscape(s: String): String =
       |  val sb = StringBuilder(s.length)
       |  var i = 0
       |  while i < s.length do
       |    s.charAt(i) match
       |      case '&'  => sb ++= "&amp;"
       |      case '<'  => sb ++= "&lt;"
       |      case '>'  => sb ++= "&gt;"
       |      case '"'  => sb ++= "&quot;"
       |      case '\'' => sb ++= "&#39;"
       |      case c    => sb += c
       |    i += 1
       |  sb.toString
       |
       |def escape(s: Any): String = _htmlEscape(_show(s))
       |
       |// Used by heading-bound html-block emission: escape unless raw(...).
       |def _html_interp(v: Any): String = v match
       |  case r: _Raw => r.html
       |  case _       => _htmlEscape(_show(v))
       |
       |extension (sc: StringContext)
       |  def html(args: Any*): String =
       |    val sb = StringBuilder()
       |    val parts = sc.parts
       |    var i = 0
       |    while i < parts.length do
       |      sb ++= parts(i)
       |      if i < args.length then args(i) match
       |        case r: _Raw => sb ++= r.html
       |        case v       => sb ++= _htmlEscape(_show(v))
       |      i += 1
       |    sb.toString
       |
       |  def css(args: Any*): String = sc.s(args.map(_show)*)
       |
       |// ── Typed HTML DSL — `div(attr.cls := "hero", h1("hi"))` ───────────────
       |case class _AttrKey(name: String):
       |  def := (value: Any): _Attr = _Attr(name, _show(value))
       |case class _Attr(name: String, value: String)
       |
       |object attr:
       |  val cls         = _AttrKey("class")
       |  val id          = _AttrKey("id")
       |  val href        = _AttrKey("href")
       |  val src         = _AttrKey("src")
       |  val alt         = _AttrKey("alt")
       |  val name        = _AttrKey("name")
       |  val title       = _AttrKey("title")
       |  val style       = _AttrKey("style")
       |  val type_       = _AttrKey("type")
       |  val value_      = _AttrKey("value")
       |  val placeholder = _AttrKey("placeholder")
       |  val method_     = _AttrKey("method")
       |  val action      = _AttrKey("action")
       |  val target      = _AttrKey("target")
       |  val rel         = _AttrKey("rel")
       |  val for_        = _AttrKey("for")
       |  val role        = _AttrKey("role")
       |  val colspan     = _AttrKey("colspan")
       |  val rowspan     = _AttrKey("rowspan")
       |  val disabled    = _AttrKey("disabled")
       |
       |private def _renderChild(v: Any): String = v match
       |  case r: _Raw         => r.html
       |  case xs: Iterable[_] => xs.map(_renderChild).mkString
       |  case other           => _htmlEscape(_show(other))
       |
       |private def _renderTag(name: String, args: Seq[Any], voidTag: Boolean = false): _Raw =
       |  val attrs    = scala.collection.mutable.LinkedHashMap.empty[String, String]
       |  val children = StringBuilder()
       |  def handle(v: Any): Unit = v match
       |    case a: _Attr        => attrs(a.name) = a.value
       |    case xs: Iterable[_] => xs.foreach(handle)
       |    case other           => children ++= _renderChild(other)
       |  args.foreach(handle)
       |  val attrStr =
       |    if attrs.isEmpty then ""
       |    else attrs.map((k, v) => " " + k + "=\"" + _htmlEscape(v) + "\"").mkString
       |  if voidTag then _Raw("<" + name + attrStr + ">")
       |  else            _Raw("<" + name + attrStr + ">" + children.toString + "</" + name + ">")
       |
       |// Each tag is a value, not a def, so `items.map(li)` works.  The class
       |// extends `Any => _Raw` so it eta-expands to a Function1; an additional
       |// `apply(args: Any*)` overload preserves the multi-arg `div(a, b, c)`
       |// call syntax that the DSL needs.
       |class _Tag(name: String, voidTag: Boolean = false) extends (Any => _Raw):
       |  override def apply(arg: Any): _Raw = _renderTag(name, Seq(arg), voidTag)
       |  def apply(args: Any*): _Raw       = _renderTag(name, args, voidTag)
       |
       |case class _Doc(parts: Seq[Any])
       |def doc(args: Any*): _Doc = _Doc(args.toSeq)
       |def render(args: Any*): Unit =
       |  def toStr(v: Any): String = v match
       |    case d: _Doc => d.parts.map(toStr).mkString("\n")
       |    case other   => other.toString
       |  val text =
       |    if args.length == 1 && args(0).isInstanceOf[_Doc] then toStr(args(0).asInstanceOf[_Doc])
       |    else args.map(toStr).mkString("\n")
       |  println(text)
       |
       |// Wall-clock for benchmarks — matches ScalaScript's `nanoTime()` primitive.
       |def nanoTime(): Long = java.lang.System.nanoTime()
       |
       |""".stripMargin

  /** Trampoline runtime for mutual tail-recursion. Each mutually-recursive
   *  function is rewritten to a `_f_impl` that may either return a value or a
   *  `_TailCall` thunk; `_trampoline` drives the thunk chain in a flat loop. */
  private val mutualTcoRuntime: String =
    """|
       |// ── Mutual tail-call trampoline ────────────────────────────────────────
       |final class _TailCall(val k: () => Any)
       |def _trampoline(start: () => Any): Any =
       |  var r: Any = start()
       |  while r.isInstanceOf[_TailCall] do
       |    r = r.asInstanceOf[_TailCall].k()
       |  r
       |
       |""".stripMargin

  /** Server runtime — REST routing + JDK HttpServer dispatcher.  Emitted only
   *  when the module calls `route(...)`.  Provides the same Request/Response
   *  shape and `Response.{html,text,json,redirect,notFound,status}` factories
   *  as the interpreter, so a single `.ssc` source runs identically through
   *  `ssc` / `ssc compile`.  `serve(port)` blocks the calling thread; the
   *  default executor is single-threaded so handler bodies see no concurrency
   *  unless the user supplies their own synchronisation. */
  private val serveRuntime: String =
    """|
       |// ── REST routing + serve(port) ─────────────────────────────────────────
       |case class UploadedFile(
       |  name:        String,
       |  filename:    String,
       |  contentType: String,
       |  size:        Int,
       |  // ISO-8859-1 view of the original bytes (1 char = 1 byte). Round-trip
       |  // back to a byte array with `bytes.getBytes("ISO-8859-1")`.
       |  bytes:       String
       |)
       |
       |case class Request(
       |  method:  String,
       |  path:    String,
       |  params:  Map[String, String],
       |  query:   Map[String, String],
       |  headers: Map[String, String],
       |  body:    String,
       |  form:    Map[String, String]         = Map.empty,
       |  files:   Map[String, UploadedFile]   = Map.empty
       |)
       |
       |case class Response(
       |  status:  Int                 = 200,
       |  headers: Map[String, String] = Map.empty,
       |  body:    String              = ""
       |)
       |
       |// JSON-encode anything: strings pass through as raw JSON (so hand-
       |// built JSON strings keep working); other values get structural
       |// emission with proper escaping.
       |private def _toJson(v: Any): String = v match
       |  case null     => "null"
       |  case s: String => s  // raw passthrough
       |  case _        => _toJsonValue(v)
       |private def _jsonQuote(s: String): String =
       |  val sb = StringBuilder().append('"')
       |  var i = 0
       |  while i < s.length do
       |    val c = s.charAt(i)
       |    if c == '"' || c == '\\' then sb.append('\\').append(c)
       |    else if c == '\n' then sb.append('\\').append('n')
       |    else if c == '\r' then sb.append('\\').append('r')
       |    else if c == '\t' then sb.append('\\').append('t')
       |    else if c == '\b' then sb.append('\\').append('b')
       |    else if c == '\f' then sb.append('\\').append('f')
       |    else if c < 0x20 then
       |      val hex = Integer.toHexString(c.toInt)
       |      sb.append('\\').append('u')
       |      var pad = 4 - hex.length
       |      while pad > 0 do sb.append('0'); pad -= 1
       |      sb.append(hex)
       |    else sb.append(c)
       |    i += 1
       |  sb.append('"').toString
       |private def _toJsonValue(v: Any): String = v match
       |  case null              => "null"
       |  case b: Boolean        => b.toString
       |  case n: (Int | Long | Short | Byte)  => n.toString
       |  case d: (Double | Float)             => d.toString
       |  case s: String         => _jsonQuote(s)
       |  case c: Char           => _jsonQuote(c.toString)
       |  case None              => "null"
       |  case Some(x)           => _toJsonValue(x)
       |  case xs: Iterable[?]   =>
       |    xs match
       |      case m: Map[?, ?] =>
       |        m.iterator.map { case (k, vv) =>
       |          val ks = k match { case s: String => s; case other => _show(other) }
       |          _jsonQuote(ks) + ":" + _toJsonValue(vv)
       |        }.mkString("{", ",", "}")
       |      case _ =>
       |        xs.iterator.map(_toJsonValue).mkString("[", ",", "]")
       |  case p: Product if p.productArity > 0 =>
       |    val names = p.productElementNames.toList
       |    val vals  = (0 until p.productArity).map(p.productElement).toList
       |    val isTuple = names.forall(_.matches("_[0-9]+"))
       |    if isTuple then vals.map(_toJsonValue).mkString("[", ",", "]")
       |    else names.iterator.zip(vals.iterator).map { (k, vv) =>
       |      _jsonQuote(k) + ":" + _toJsonValue(vv)
       |    }.mkString("{", ",", "}")
       |  case other             => _jsonQuote(_show(other))
       |
       |object Response:
       |  private val Html = Map("Content-Type" -> "text/html; charset=utf-8")
       |  private val Text = Map("Content-Type" -> "text/plain; charset=utf-8")
       |  private val Json = Map("Content-Type" -> "application/json")
       |  def html(body: Any): Response     = Response(200, Html, _show(body))
       |  def text(body: Any): Response     = Response(200, Text, _show(body))
       |  def json(body: Any): Response     = Response(200, Json, _toJson(body))
       |  def redirect(to: String): Response = Response(302, Map("Location" -> to), "")
       |  def notFound(body: Any = "Not Found"): Response = Response(404, body = _show(body))
       |  def status(code: Int, body: Any = ""): Response = Response(code, body = _show(body))
       |
       |private enum _Seg:
       |  case Lit(s: String)
       |  case Cap(name: String)
       |
       |private def _parsePath(p: String): List[_Seg] =
       |  p.split('/').toList.filter(_.nonEmpty).map { s =>
       |    if s.startsWith(":") then _Seg.Cap(s.tail) else _Seg.Lit(s)
       |  }
       |
       |private case class _Route(method: String, pattern: List[_Seg], handler: Request => Response)
       |private val _routes = scala.collection.mutable.ArrayBuffer.empty[_Route]
       |
       |def route(method: String, path: String)(handler: Request => Response): Unit =
       |  _routes += _Route(method.toUpperCase, _parsePath(path), handler)
       |
       |private def _matchPath(pat: List[_Seg], segs: List[String]): Option[Map[String, String]] =
       |  if pat.length != segs.length then None
       |  else
       |    val ps = scala.collection.mutable.Map.empty[String, String]
       |    val ok = pat.zip(segs).forall {
       |      case (_Seg.Lit(p), a)  => p == a
       |      case (_Seg.Cap(n), a)  => ps(n) = a; true
       |    }
       |    if ok then Some(ps.toMap) else None
       |
       |private def _parseQuery(q: String): Map[String, String] =
       |  if q == null || q.isEmpty then Map.empty
       |  else q.split('&').iterator.flatMap { pair =>
       |    val i = pair.indexOf('=')
       |    if i < 0 then Some(java.net.URLDecoder.decode(pair, "UTF-8") -> "")
       |    else Some(
       |      java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") ->
       |      java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
       |    )
       |  }.toMap
       |
       |/** Multipart parser — see WebServer.parseMultipart for the design notes.
       | *  `bodyLatin1` is byte-equivalent to the wire body so split/index are
       | *  byte-exact even when parts carry binary content. */
       |private def _parseMultipart(
       |    contentType: String,
       |    bodyLatin1:  String
       |): (Map[String, String], Map[String, UploadedFile]) =
       |  val boundary = "boundary=([^;]+)".r.findFirstMatchIn(contentType).map { m =>
       |    val raw = m.group(1).trim
       |    if raw.startsWith("\"") && raw.endsWith("\"") then raw.substring(1, raw.length - 1) else raw
       |  }
       |  boundary.fold((Map.empty[String, String], Map.empty[String, UploadedFile])) { b =>
       |    val sep   = "--" + b
       |    val parts = bodyLatin1.split(java.util.regex.Pattern.quote(sep), -1)
       |    val form  = scala.collection.mutable.Map.empty[String, String]
       |    val files = scala.collection.mutable.Map.empty[String, UploadedFile]
       |    parts.drop(1).dropRight(1).foreach { raw =>
       |      val part   = raw.stripPrefix("\r\n").stripSuffix("\r\n")
       |      val sepIdx = part.indexOf("\r\n\r\n")
       |      if sepIdx >= 0 then
       |        val headerText = part.substring(0, sepIdx)
       |        val partBody   = part.substring(sepIdx + 4)
       |        val disp = headerText.linesIterator
       |          .find(_.toLowerCase.startsWith("content-disposition"))
       |          .getOrElse("")
       |        val ctype = headerText.linesIterator
       |          .find(_.toLowerCase.startsWith("content-type"))
       |          .map(_.split(":", 2).lift(1).getOrElse("").trim)
       |          .getOrElse("application/octet-stream")
       |        val name     = "name=\"([^\"]*)\"".r.findFirstMatchIn(disp).map(_.group(1))
       |        val filename = "filename=\"([^\"]*)\"".r.findFirstMatchIn(disp).map(_.group(1))
       |        (name, filename) match
       |          case (Some(n), Some(fn)) =>
       |            files(n) = UploadedFile(n, fn, ctype, partBody.length, partBody)
       |          case (Some(n), None) =>
       |            form(n) = new String(partBody.getBytes("ISO-8859-1"), "UTF-8")
       |          case _ => ()
       |    }
       |    (form.toMap, files.toMap)
       |  }
       |
       |private def _handle(ex: com.sun.net.httpserver.HttpExchange): Unit =
       |  try
       |    val method = ex.getRequestMethod.toUpperCase
       |    val path   = ex.getRequestURI.getPath
       |    val segs   = path.split('/').toList.filter(_.nonEmpty)
       |    val matched = _routes.iterator
       |      .filter(_.method == method)
       |      .flatMap(r => _matchPath(r.pattern, segs).map(p => (r, p)))
       |      .nextOption()
       |    matched match
       |      case Some((r, params)) =>
       |        import scala.jdk.CollectionConverters.*
       |        val headers = ex.getRequestHeaders.entrySet.iterator.asScala.flatMap { e =>
       |          if e.getValue.isEmpty then None
       |          else Some(e.getKey -> e.getValue.get(0))
       |        }.toMap
       |        // Read body as bytes so multipart file parts round-trip byte-exact.
       |        // `body` is the UTF-8 view (back-compat); `bodyLatin1` is a
       |        // byte-equivalent String for multipart parsing.
       |        val bodyBytes  = ex.getRequestBody.readAllBytes()
       |        val body       = new String(bodyBytes, "UTF-8")
       |        val bodyLatin1 = new String(bodyBytes, "ISO-8859-1")
       |        val ct   = headers.collectFirst {
       |          case (k, v) if k.equalsIgnoreCase("Content-Type") => v
       |        }.getOrElse("")
       |        val (form, files) =
       |          if ct.toLowerCase.startsWith("application/x-www-form-urlencoded") then
       |            (_parseQuery(body), Map.empty[String, UploadedFile])
       |          else if ct.toLowerCase.startsWith("multipart/form-data") then
       |            _parseMultipart(ct, bodyLatin1)
       |          else
       |            (Map.empty[String, String], Map.empty[String, UploadedFile])
       |        val req  = Request(method, path, params,
       |          _parseQuery(ex.getRequestURI.getRawQuery), headers, body, form, files)
       |        _writeResponse(ex, r.handler(req))
       |      case None =>
       |        // Fall through to a static file under the current directory
       |        // before 404'ing — mirrors the interpreter's WebServer.
       |        _serveStatic(ex, path) match
       |          case Some(_) => ()
       |          case None    =>
       |            val msg = s"Not Found: $path".getBytes("UTF-8")
       |            ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
       |            ex.sendResponseHeaders(404, msg.length.toLong)
       |            ex.getResponseBody.write(msg)
       |  catch case e: Exception =>
       |    System.err.println(s"route error: ${e.getMessage}")
       |  finally ex.close()
       |
       |private def _writeResponse(ex: com.sun.net.httpserver.HttpExchange, r: Response): Unit =
       |  r.headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
       |  if !r.headers.contains("Content-Type") then
       |    ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
       |  val bytes = r.body.getBytes("UTF-8")
       |  ex.sendResponseHeaders(r.status, if bytes.isEmpty then -1L else bytes.length.toLong)
       |  if bytes.nonEmpty then ex.getResponseBody.write(bytes)
       |
       |/** Try to serve a static asset (non-.ssc file) under the cwd; returns
       | *  Some when handled, None when the file is missing / disqualified.
       | *  Path-traversal is blocked by canonical-path checks. */
       |private def _serveStatic(ex: com.sun.net.httpserver.HttpExchange, urlPath: String): Option[Unit] =
       |  val cleaned = urlPath.stripPrefix("/")
       |  if cleaned.isEmpty then return None
       |  val rootDir = new java.io.File(".").getCanonicalFile
       |  val target  = new java.io.File(rootDir, cleaned).getCanonicalFile
       |  if !target.exists() || !target.isFile() then None
       |  else if !target.getPath.startsWith(rootDir.getPath) then None
       |  else if target.getName.endsWith(".ssc") then None
       |  else
       |    val bytes = java.nio.file.Files.readAllBytes(target.toPath)
       |    ex.getResponseHeaders.add("Content-Type", _contentTypeFor(target.getName))
       |    ex.sendResponseHeaders(200, bytes.length.toLong)
       |    ex.getResponseBody.write(bytes)
       |    Some(())
       |
       |private def _contentTypeFor(name: String): String =
       |  val lower = name.toLowerCase
       |  val explicit: Option[String] = lower match
       |    case n if n.endsWith(".html") || n.endsWith(".htm") => Some("text/html; charset=utf-8")
       |    case n if n.endsWith(".css")  => Some("text/css; charset=utf-8")
       |    case n if n.endsWith(".js") || n.endsWith(".mjs") => Some("application/javascript; charset=utf-8")
       |    case n if n.endsWith(".json") => Some("application/json; charset=utf-8")
       |    case n if n.endsWith(".txt") || n.endsWith(".md") => Some("text/plain; charset=utf-8")
       |    case n if n.endsWith(".svg")  => Some("image/svg+xml")
       |    case n if n.endsWith(".png")  => Some("image/png")
       |    case n if n.endsWith(".jpg") || n.endsWith(".jpeg") => Some("image/jpeg")
       |    case n if n.endsWith(".gif")  => Some("image/gif")
       |    case n if n.endsWith(".webp") => Some("image/webp")
       |    case n if n.endsWith(".ico")  => Some("image/x-icon")
       |    case n if n.endsWith(".woff") => Some("font/woff")
       |    case n if n.endsWith(".woff2") => Some("font/woff2")
       |    case n if n.endsWith(".wasm") => Some("application/wasm")
       |    case _ => None
       |  explicit.orElse {
       |    try Option(java.nio.file.Files.probeContentType(java.nio.file.Paths.get(name)))
       |    catch case _: Throwable => None
       |  }.getOrElse("application/octet-stream")
       |
       |def serve(port: Int): Unit =
       |  val server = com.sun.net.httpserver.HttpServer.create(
       |    java.net.InetSocketAddress(port), 0)
       |  server.createContext("/", _handle)
       |  server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
       |  server.start()
       |  println(s"Listening on http://localhost:$port/")
       |  Thread.currentThread().join()
       |
       |""".stripMargin

  /** Free-Monad runtime for algebraic effects. Mirrors the interpreter and JS
   *  backend: Pure values are plain Scala values, Perform/FlatMap are case
   *  classes, _bind is constant-time, _run / _handle right-associate
   *  FlatMaps in a while-loop (stack-safe in bind-chain depth). */
  private val effectsRuntime: String =
    """|
       |// ── Algebraic effects runtime (trampolined Free Monad) ─────────────────
       |sealed trait _Computation
       |case class _Perform(eff: String, op: String, args: List[Any]) extends _Computation
       |case class _FlatMap(sub: Any, k: Any => Any) extends _Computation
       |
       |def _bind(c: Any, f: Any => Any): Any = c match
       |  case _: _Computation => _FlatMap(c, f)
       |  case v               => f(v)
       |
       |def _perform(eff: String, op: String, args: Any*): _Computation =
       |  _Perform(eff, op, args.toList)
       |
       |def _run(c0: Any): Any =
       |  var current: Any = c0
       |  while true do
       |    current match
       |      case _Perform(eff, op, _) =>
       |        throw new RuntimeException(s"Unhandled effect: $eff.$op")
       |      case _FlatMap(sub, f) => sub match
       |        case _Perform(eff, op, _) =>
       |          throw new RuntimeException(s"Unhandled effect: $eff.$op")
       |        case _FlatMap(s2, g) =>
       |          current = _FlatMap(s2, (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |        case v =>
       |          current = f.asInstanceOf[Any => Any](v)
       |      case v => return v
       |  throw new RuntimeException("unreachable")
       |
       |def _handle(
       |  bodyThunk:  () => Any,
       |  handledOps: Set[String],
       |  handlers:   Map[String, List[Any] => Any]
       |): Any =
       |  def interp(initial: Any): Any =
       |    var current: Any = initial
       |    while true do
       |      current match
       |        case _Perform(eff, op, args) =>
       |          val key = s"$eff.$op"
       |          if handledOps(key) then
       |            val resume: Any => Any = (v: Any) => v
       |            current = handlers(key)(args :+ resume)
       |          else return current
       |        case _FlatMap(sub, f) => sub match
       |          case _Perform(eff, op, args) =>
       |            val key = s"$eff.$op"
       |            val fn = f.asInstanceOf[Any => Any]
       |            if handledOps(key) then
       |              val resume: Any => Any = (v: Any) => interp(fn(v))
       |              current = handlers(key)(args :+ resume)
       |            else
       |              return _FlatMap(_Perform(eff, op, args),
       |                              (v: Any) => interp(fn(v)))
       |          case _FlatMap(s2, g) =>
       |            current = _FlatMap(s2,
       |              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |          case v =>
       |            current = f.asInstanceOf[Any => Any](v)
       |        case v => return v
       |    throw new RuntimeException("unreachable")
       |  interp(bodyThunk())
       |
       |/** Loose flatMap used inside handler case bodies — accepts callbacks that
       | *  return either an iterable (multi-shot resume) or a single value
       | *  (one-shot resume), matching the duck-typed JS semantics. */
       |def _anyFlatMap(xs: Any, f: Any => Any): Any = xs match
       |  case ys: scala.collection.Iterable[_] =>
       |    ys.asInstanceOf[Iterable[Any]].toList.flatMap { x =>
       |      f(x) match
       |        case zs: scala.collection.Iterable[_] => zs.asInstanceOf[Iterable[Any]].toList
       |        case v                                => List(v)
       |    }
       |  case _ => xs
       |
       |/** Dynamic binary operator dispatch for CPS contexts where operands are
       | *  typed as `Any`. Mirrors the interpreter's `infix` table. */
       |def _binOp(op: String, a: Any, b: Any): Any = (op, a, b) match
       |  case ("+",  x: Int,    y: Int)    => x + y
       |  case ("+",  x: Long,   y: Long)   => x + y
       |  case ("+",  x: Long,   y: Int)    => x + y
       |  case ("+",  x: Int,    y: Long)   => x + y
       |  case ("+",  x: Double, y: Double) => x + y
       |  case ("+",  x: Int,    y: Double) => x + y
       |  case ("+",  x: Double, y: Int)    => x + y
       |  case ("+",  x: String, _)         => x + b.toString
       |  case ("+",  _,         y: String) => a.toString + y
       |  case ("-",  x: Int,    y: Int)    => x - y
       |  case ("-",  x: Long,   y: Long)   => x - y
       |  case ("-",  x: Double, y: Double) => x - y
       |  case ("-",  x: Int,    y: Double) => x.toDouble - y
       |  case ("-",  x: Double, y: Int)    => x - y.toDouble
       |  case ("*",  x: Int,    y: Int)    => x * y
       |  case ("*",  x: Long,   y: Long)   => x * y
       |  case ("*",  x: Double, y: Double) => x * y
       |  case ("/",  x: Int,    y: Int)    => x / y
       |  case ("/",  x: Long,   y: Long)   => x / y
       |  case ("/",  x: Double, y: Double) => x / y
       |  case ("%",  x: Int,    y: Int)    => x % y
       |  case ("<",  x: Int,    y: Int)    => x < y
       |  case ("<",  x: Long,   y: Long)   => x < y
       |  case ("<",  x: Double, y: Double) => x < y
       |  case (">",  x: Int,    y: Int)    => x > y
       |  case (">",  x: Long,   y: Long)   => x > y
       |  case (">",  x: Double, y: Double) => x > y
       |  case ("<=", x: Int,    y: Int)    => x <= y
       |  case ("<=", x: Long,   y: Long)   => x <= y
       |  case ("<=", x: Double, y: Double) => x <= y
       |  case (">=", x: Int,    y: Int)    => x >= y
       |  case (">=", x: Long,   y: Long)   => x >= y
       |  case (">=", x: Double, y: Double) => x >= y
       |  case _ => sys.error(s"Cannot $op on $a, $b")
       |
       |""".stripMargin
