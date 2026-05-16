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
          case Term.Apply.After_4_6_0(Term.Name("route"),        _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("onWebSocket"),  _) => found = true
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

  /** True if the term needs codegen rewriting (effect machinery,
   *  Focus → Lens expansion) rather than verbatim Scala source emission. */
  private def termNeedsCustomEmit(t: Term): Boolean =
    termUsesEffects(t) || termContainsFocus(t)

  private def termContainsFocus(t: Term): Boolean = t match
    case app: Term.Apply if isFocusApp(app) => true
    case _ => t.children.exists {
      case tt: Term => termContainsFocus(tt)
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
    case Defn.Val(mods, pats, tpe, rhs) if termNeedsCustomEmit(rhs) =>
      val mod = mods.map(_.syntax).mkString(" ")
      val modStr = if mod.isEmpty then "" else mod + " "
      val patsStr = pats.map(_.syntax).mkString(", ")
      val tpeStr = tpe.map(t => s": ${t.syntax}").getOrElse("")
      s"${modStr}val $patsStr$tpeStr = ${emitExpr(rhs)}"
    case Defn.Var.After_4_7_2(mods, pats, tpe, rhs: Term) if termNeedsCustomEmit(rhs) =>
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

    // Focus[T](_.a.b) / Focus(_.a.b) — lower to a Lens(get, set) literal.
    // The lambda body's field-access chain becomes nested get + nested copy.
    case app: Term.Apply if isFocusApp(app) =>
      emitFocus(app)

    // If the term has nested effect or Focus content, walk children.
    case _ if termNeedsCustomEmit(term) => emitExprDeep(term)

    // Otherwise emit Scala source as-is.
    case other => other.syntax

  private def isFocusApp(app: Term.Apply): Boolean = app.fun match
    case Term.Name("Focus")                                => true
    case ta: Term.ApplyType                                =>
      ta.fun match { case Term.Name("Focus") => true; case _ => false }
    case _                                                 => false

  /** Lower `Focus[T](_.a.b)` to `Lens[T, _]((s: T) => s.a.b, (s: T, v) =>
   *  s.copy(a = s.a.copy(b = v)))`. `T` is taken from `Focus[T]` if present;
   *  otherwise the lambda's explicit param type is used; otherwise we emit
   *  an unannotated form (and rely on Scala 3 inference, which usually
   *  needs an outer type ascription to succeed). */
  private def emitFocus(app: Term.Apply): String =
    val typeArg: Option[String] = app.fun match
      case ta: Term.ApplyType =>
        ta.argClause.values.headOption.map(_.syntax)
      case _ => None
    app.argClause.values match
      case List(lambda) =>
        val pathAndExplicitTpe: Option[(List[String], Option[String])] = lambda match
          case Term.AnonymousFunction(body) =>
            extractFieldPath(body, _.isInstanceOf[Term.Placeholder]).map(_ -> None)
          case Term.Function.After_4_6_0(paramClause, body) =>
            paramClause.values.headOption.flatMap { p =>
              extractFieldPath(body, {
                case Term.Name(n) => n == p.name.value
                case _            => false
              }).map(_ -> p.decltpe.map(_.syntax))
            }
          case _ => None
        pathAndExplicitTpe match
          case Some((path, explicitTpe)) if path.nonEmpty =>
            val tpe = typeArg.orElse(explicitTpe).getOrElse("Any")
            buildLensLiteral(tpe, path)
          case _ =>
            "??? /* Focus: expected a field-access lambda like _.field.subfield */"
      case _ =>
        "??? /* Focus expects exactly one lambda argument */"

  private def extractFieldPath(body: Term, isBase: Term => Boolean): Option[List[String]] =
    def loop(t: Term, acc: List[String]): Option[List[String]] = t match
      case Term.Select(qual, name) => loop(qual, name.value :: acc)
      case other if isBase(other)  => Some(acc)
      case _                       => None
    loop(body, Nil)

  /** Emit a Lens literal whose setter walks `path` and rebuilds nested copies. */
  private def buildLensLiteral(tpe: String, path: List[String]): String =
    val getter = s"(s: $tpe) => s.${path.mkString(".")}"
    // Build the nested copy from outside in:
    //   path = [a]            =>  s.copy(a = v)
    //   path = [a, b]         =>  s.copy(a = s.a.copy(b = v))
    //   path = [a, b, c]      =>  s.copy(a = s.a.copy(b = s.a.b.copy(c = v)))
    def buildSet(prefix: String, remaining: List[String]): String = remaining match
      case last :: Nil       => s"$prefix.copy($last = v)"
      case head :: rest      => s"$prefix.copy($head = ${buildSet(s"$prefix.$head", rest)})"
      case Nil               => "v"
    val setter = s"(s: $tpe, v) => ${buildSet("s", path)}"
    s"Lens($getter, $setter)"

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
        case _ if isFocusApp(app) =>
          emitFocus(app)
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
       |/** `collectCss(comp1, comp2, ...)` — concatenate each argument's
       | *  `css` field into one CSS string for a page-level <style>.
       | *  Convention helper for component-style .ssc files (see SPEC §8.4).
       | *  Each argument is expected to be a Scala `object` exposing a
       | *  `val css: String`; reflective access keeps the helper free of
       | *  a shared component supertype.  Anything without a no-arg
       | *  `css` method that returns a String is silently skipped. */
       |def collectCss(parts: Any*): String =
       |  parts.flatMap { part =>
       |    try
       |      val m = part.getClass.getMethod("css")
       |      m.invoke(part) match
       |        case s: String => Some(s)
       |        case _         => None
       |    catch case _: Throwable => None
       |  }.mkString("\n")
       |
       |/** `scope("Card")` — class-name suffix helper for component-style
       | *  .ssc files (see SPEC §8.4).
       | *
       | *    val s = scope("Card")
       | *    val css = s.css(".title { color: blue }")  // ".title__Card { color: blue }"
       | *    val c   = s.cls("title")                   // "title__Card"
       | *
       | *  Two components can both write bare `.title` without their
       | *  concatenated CSS colliding.  The CSS rewriter is a simple
       | *  `\.identifier` regex pass; class chains (`.a.b`) work, but
       | *  `.ident` inside `url(...)` would also be rewritten — keep URL
       | *  strings free of bare-identifier dots if you depend on them. */
       |class _Scope(val name: String):
       |  private val pat = "\\.([A-Za-z_][A-Za-z0-9_-]*)".r
       |  def css(s: String): String =
       |    pat.replaceAllIn(s, m =>
       |      java.util.regex.Matcher.quoteReplacement("." + m.group(1) + "__" + name)
       |    )
       |  def cls(n: String): String = n + "__" + name
       |
       |def scope(name: String): _Scope = _Scope(name)
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
       |// ── Lens runtime — pure-functional optic over case-class field paths ──
       |case class Lens[S, A](get: S => A, set: (S, A) => S):
       |  def modify(s: S, f: A => A): S = set(s, f(get(s)))
       |  def andThen[B](other: Lens[A, B]): Lens[S, B] =
       |    Lens(s => other.get(get(s)), (s, b) => set(s, other.set(get(s), b)))
       |
       |// Environment variable reader — same surface on all three backends.
       |def getenv(key: String, defaultVal: String = ""): String =
       |  val v = java.lang.System.getenv(key)
       |  if v == null || v.isEmpty then defaultVal else v
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
       |  method:      String,
       |  path:        String,
       |  params:      Map[String, String],
       |  query:       Map[String, String],
       |  headers:     Map[String, String],
       |  body:        String,
       |  form:        Map[String, String]         = Map.empty,
       |  files:       Map[String, UploadedFile]   = Map.empty,
       |  session:     Map[String, String]         = Map.empty,
       |  bearerToken: Option[String]              = None,
       |  jwtClaims:   Option[Map[String, String]] = None,
       |  basicAuth:   Option[(String, String)]    = None
       |)
       |
       |case class Response(
       |  status:     Int                         = 200,
       |  headers:    Map[String, String]         = Map.empty,
       |  body:       String                      = "",
       |  setSession: Option[Map[String, String]] = None
       |):
       |  /** Attach a session payload — HMAC-signed and packed into Set-Cookie. */
       |  def withSession(payload: Map[String, String]): Response = copy(setSession = Some(payload))
       |  /** Clear the session cookie (Max-Age=0 on the wire). */
       |  def clearSession(): Response                            = copy(setSession = Some(Map.empty))
       |
       |// ── Signed cookie sessions ──────────────────────────────────────
       |// HMAC-SHA256-signed Map[String, String] roundtripped through the
       |// `session=<b64url(json)>.<b64url(hmac)>` cookie format.  Mirrors
       |// scalascript.server.SessionCookie and the JsGen Node runtime so
       |// the wire format is identical across all three backends.
       |private lazy val _sessionSecret: Array[Byte] =
       |  sys.env.get("SSC_SESSION_SECRET").filter(_.nonEmpty) match
       |    case Some(s) => s.getBytes("UTF-8")
       |    case None    =>
       |      val bytes = new Array[Byte](32)
       |      java.security.SecureRandom().nextBytes(bytes)
       |      System.err.println(
       |        "[ssc] SSC_SESSION_SECRET not set; using a process-local random key. " +
       |        "Sessions will not survive a server restart."
       |      )
       |      bytes
       |private def _hmacSha256(payload: Array[Byte]): Array[Byte] =
       |  val mac = javax.crypto.Mac.getInstance("HmacSHA256")
       |  mac.init(javax.crypto.spec.SecretKeySpec(_sessionSecret, "HmacSHA256"))
       |  mac.doFinal(payload)
       |private def _b64urlEnc(b: Array[Byte]): String =
       |  java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(b)
       |private def _b64urlDec(s: String): Array[Byte] =
       |  java.util.Base64.getUrlDecoder.decode(s)
       |private def _sessionJsonEnc(m: Map[String, String]): String =
       |  def esc(s: String): String =
       |    val sb = StringBuilder().append('"')
       |    var i = 0
       |    while i < s.length do
       |      val c = s.charAt(i)
       |      if c == '"' || c == '\\' then sb.append('\\').append(c)
       |      else if c == '\n' then sb.append("\\n")
       |      else if c == '\r' then sb.append("\\r")
       |      else if c == '\t' then sb.append("\\t")
       |      else if c < 0x20 then sb.append("\\u%04x".format(c.toInt))
       |      else sb.append(c)
       |      i += 1
       |    sb.append('"').toString
       |  m.iterator.map((k, v) => esc(k) + ":" + esc(v)).mkString("{", ",", "}")
       |private def _sessionJsonDec(json: String): Option[Map[String, String]] =
       |  val t = json.trim
       |  if !t.startsWith("{") || !t.endsWith("}") then None
       |  else
       |    val inner = t.substring(1, t.length - 1).trim
       |    if inner.isEmpty then Some(Map.empty)
       |    else try
       |      var i = 0
       |      def skipWs(): Unit = while i < inner.length && inner.charAt(i).isWhitespace do i += 1
       |      def readStr(): String =
       |        if inner.charAt(i) != '"' then throw RuntimeException("expected quote")
       |        i += 1
       |        val sb = StringBuilder()
       |        while i < inner.length && inner.charAt(i) != '"' do
       |          val c = inner.charAt(i)
       |          if c == '\\' && i + 1 < inner.length then
       |            inner.charAt(i + 1) match
       |              case '"'  => sb.append('"');  i += 2
       |              case '\\' => sb.append('\\'); i += 2
       |              case 'n'  => sb.append('\n'); i += 2
       |              case 'r'  => sb.append('\r'); i += 2
       |              case 't'  => sb.append('\t'); i += 2
       |              case 'u' if i + 5 < inner.length =>
       |                sb.append(Integer.parseInt(inner.substring(i + 2, i + 6), 16).toChar)
       |                i += 6
       |              case _    => sb.append(c); i += 1
       |          else { sb.append(c); i += 1 }
       |        if i >= inner.length then throw RuntimeException("unterminated")
       |        i += 1
       |        sb.toString
       |      val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
       |      while i < inner.length do
       |        skipWs(); val k = readStr()
       |        skipWs()
       |        if i >= inner.length || inner.charAt(i) != ':' then throw RuntimeException("expected colon")
       |        i += 1
       |        skipWs(); val v = readStr()
       |        out(k) = v
       |        skipWs()
       |        if i < inner.length then
       |          if inner.charAt(i) != ',' then throw RuntimeException("expected comma")
       |          i += 1
       |      Some(out.toMap)
       |    catch case _: Throwable => None
       |private def _packSession(m: Map[String, String]): String =
       |  val body = _b64urlEnc(_sessionJsonEnc(m).getBytes("UTF-8"))
       |  val sig  = _b64urlEnc(_hmacSha256(body.getBytes("UTF-8")))
       |  body + "." + sig
       |private def _unpackSession(cookieValue: String): Map[String, String] =
       |  val dot = cookieValue.indexOf('.')
       |  if dot <= 0 || dot == cookieValue.length - 1 then Map.empty
       |  else
       |    val body = cookieValue.substring(0, dot)
       |    val sig  = cookieValue.substring(dot + 1)
       |    try
       |      val expected = _b64urlEnc(_hmacSha256(body.getBytes("UTF-8")))
       |      if !java.security.MessageDigest.isEqual(
       |          expected.getBytes("UTF-8"), sig.getBytes("UTF-8")) then Map.empty
       |      else _sessionJsonDec(String(_b64urlDec(body), "UTF-8")).getOrElse(Map.empty)
       |    catch case _: Throwable => Map.empty
       |private def _parseCookieSession(cookieHeader: String): Map[String, String] =
       |  if cookieHeader == null || cookieHeader.isEmpty then Map.empty
       |  else cookieHeader.split(';').iterator.map(_.trim)
       |    .find(_.startsWith("session="))
       |    .map(p => _unpackSession(p.substring("session=".length)))
       |    .getOrElse(Map.empty)
       |private def _buildSetCookie(payload: Map[String, String]): String =
       |  val base = "Path=/; HttpOnly; SameSite=Lax"
       |  if payload.isEmpty then s"session=; $base; Max-Age=0"
       |  else s"session=${_packSession(payload)}; $base"
       |
       |// ── Opt-in server-side session store ────────────────────────────
       |// Same semantics as scalascript.server.SessionStore: process-local
       |// ConcurrentHashMap keyed by random SSID, lazy TTL sweep.  When
       |// enabled the cookie payload is `{"_ssid": "..."}` and the real
       |// data lives on the server.
       |private case class _StoreEntry(payload: Map[String, String], lastAccess: Long)
       |private val _sessionStore = new java.util.concurrent.ConcurrentHashMap[String, _StoreEntry]()
       |@volatile private var _sessionStoreEnabled = false
       |@volatile private var _sessionStoreTtlMs   = 30L * 60L * 1000L
       |private val _sessionAccessCount = new java.util.concurrent.atomic.AtomicLong(0L)
       |def useSessionStore(ttlSeconds: Long = 30L * 60L): Unit =
       |  _sessionStoreTtlMs = ttlSeconds * 1000L
       |  _sessionStoreEnabled = true
       |private def _sessionStoreSweep(): Unit =
       |  val cutoff = java.lang.System.currentTimeMillis() - _sessionStoreTtlMs
       |  val it = _sessionStore.entrySet().iterator()
       |  while it.hasNext do
       |    val e = it.next()
       |    if e.getValue.lastAccess < cutoff then it.remove()
       |private def _sessionStoreMaybeSweep(): Unit =
       |  if (_sessionAccessCount.incrementAndGet() & 0xFF) == 0L then _sessionStoreSweep()
       |private def _sessionStorePut(payload: Map[String, String]): String =
       |  val bytes = new Array[Byte](24)
       |  java.security.SecureRandom().nextBytes(bytes)
       |  val ssid = _b64urlEnc(bytes)
       |  _sessionStore.put(ssid, _StoreEntry(payload, java.lang.System.currentTimeMillis()))
       |  _sessionStoreMaybeSweep()
       |  ssid
       |private def _sessionStoreGet(ssid: String): Option[Map[String, String]] =
       |  Option(_sessionStore.get(ssid)) match
       |    case None    => None
       |    case Some(e) =>
       |      val now = java.lang.System.currentTimeMillis()
       |      if now - e.lastAccess > _sessionStoreTtlMs then
       |        _sessionStore.remove(ssid, e)
       |        None
       |      else
       |        _sessionStore.put(ssid, e.copy(lastAccess = now))
       |        _sessionStoreMaybeSweep()
       |        Some(e.payload)
       |private def _sessionStoreDelete(ssid: String): Unit =
       |  _sessionStore.remove(ssid)
       |
       |// ── JWT (HS256) ─────────────────────────────────────────────────
       |// Same wire format as scalascript.server.Jwt and the JsGen Node
       |// runtime: header `{"alg":"HS256","typ":"JWT"}`, payload = JSON of
       |// a Map[String, String], sig = HMAC-SHA256(header_b64.payload_b64).
       |// Secret: SSC_JWT_SECRET preferred, SSC_SESSION_SECRET as fallback.
       |private lazy val _jwtSecret: Array[Byte] =
       |  sys.env.get("SSC_JWT_SECRET").filter(_.nonEmpty)
       |    .orElse(sys.env.get("SSC_SESSION_SECRET").filter(_.nonEmpty)) match
       |    case Some(s) => s.getBytes("UTF-8")
       |    case None    =>
       |      val bytes = new Array[Byte](32)
       |      java.security.SecureRandom().nextBytes(bytes)
       |      System.err.println("[ssc] SSC_JWT_SECRET / SSC_SESSION_SECRET not set; JWTs signed with a process-local random key.")
       |      bytes
       |private def _hmacSha256Jwt(payload: Array[Byte]): Array[Byte] =
       |  val mac = javax.crypto.Mac.getInstance("HmacSHA256")
       |  mac.init(javax.crypto.spec.SecretKeySpec(_jwtSecret, "HmacSHA256"))
       |  mac.doFinal(payload)
       |private val _jwtHeaderB64 = _b64urlEnc("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes("UTF-8"))
       |def jwtSign(claims: Map[String, String]): String =
       |  val payloadB64 = _b64urlEnc(_sessionJsonEnc(claims).getBytes("UTF-8"))
       |  val sig        = _b64urlEnc(_hmacSha256Jwt((_jwtHeaderB64 + "." + payloadB64).getBytes("UTF-8")))
       |  s"${_jwtHeaderB64}.$payloadB64.$sig"
       |def jwtVerify(token: String): Option[Map[String, String]] =
       |  val parts = token.split('.')
       |  if parts.length != 3 then None
       |  else
       |    val h = parts(0); val p = parts(1); val s = parts(2)
       |    try
       |      val expected = _b64urlEnc(_hmacSha256Jwt((h + "." + p).getBytes("UTF-8")))
       |      if !java.security.MessageDigest.isEqual(
       |          expected.getBytes("UTF-8"), s.getBytes("UTF-8")) then None
       |      else
       |        val header = String(_b64urlDec(h), "UTF-8")
       |        if !header.contains("\"alg\":\"HS256\"") then None
       |        else _sessionJsonDec(String(_b64urlDec(p), "UTF-8")) match
       |          case None => None
       |          case Some(claims) =>
       |            claims.get("exp") match
       |              case Some(expStr) =>
       |                val now = java.lang.System.currentTimeMillis() / 1000L
       |                try
       |                  val exp = expStr.toLong
       |                  if exp < now then None else Some(claims)
       |                catch case _: Throwable => None
       |              case None => Some(claims)
       |    catch case _: Throwable => None
       |private def _bearerFromAuth(h: String): Option[String] =
       |  val t = Option(h).map(_.trim).getOrElse("")
       |  if t.length < 7 || !t.substring(0, 7).equalsIgnoreCase("Bearer ") then None
       |  else Some(t.substring(7).trim)
       |
       |// ── OAuth2 helpers ────────────────────────────────────────────
       |// Same surface as scalascript.server.OAuth: pure URL builder +
       |// blocking token exchange via java.net.http.HttpClient.
       |// Builtin presets + a mutable registry for user-supplied providers.
       |private val _oauthProviders = new java.util.concurrent.ConcurrentHashMap[String, Map[String, String]](
       |  java.util.Map.of(
       |    "google", Map(
       |      "authorizeUrl" -> "https://accounts.google.com/o/oauth2/v2/auth",
       |      "tokenUrl"     -> "https://oauth2.googleapis.com/token",
       |      "userinfoUrl"  -> "https://www.googleapis.com/oauth2/v3/userinfo",
       |      "defaultScope" -> "openid email profile",
       |    ),
       |    "github", Map(
       |      "authorizeUrl" -> "https://github.com/login/oauth/authorize",
       |      "tokenUrl"     -> "https://github.com/login/oauth/access_token",
       |      "userinfoUrl"  -> "https://api.github.com/user",
       |      "defaultScope" -> "user:email",
       |    ),
       |  )
       |)
       |private def _oauthCfg(provider: String): Option[Map[String, String]] =
       |  Option(_oauthProviders.get(provider))
       |def oauthRegisterProvider(name: String, cfg: Map[String, String]): Unit =
       |  _oauthProviders.put(name, _oauthCfg(name).getOrElse(Map.empty) ++ cfg)
       |private def _oauthEnc(s: String): String =
       |  java.net.URLEncoder.encode(s, "UTF-8")
       |def oauthAuthorizeUrl(provider: String, clientId: String, redirectUri: String, state: String, scope: String = ""): String =
       |  val cfg = _oauthCfg(provider).getOrElse(throw IllegalArgumentException(s"unknown OAuth provider: $provider"))
       |  val eff = if scope.nonEmpty then scope else cfg.getOrElse("defaultScope", "")
       |  val base = cfg("authorizeUrl")
       |  val params = scala.collection.mutable.LinkedHashMap[String, String]()
       |  params("response_type") = "code"
       |  params("client_id")     = clientId
       |  params("redirect_uri")  = redirectUri
       |  params("state")         = state
       |  if eff.nonEmpty then params("scope") = eff
       |  val qs = params.iterator.map((k, v) => _oauthEnc(k) + "=" + _oauthEnc(v)).mkString("&")
       |  base + "?" + qs
       |// More permissive JSON-object parser than `_sessionJsonDec`: handles
       |// nested objects / arrays by surfacing them as raw JSON strings.
       |// Needed for userinfo responses (e.g. GitHub's `plan: {...}`).
       |private def _oauthJsonFlat(s: String): Option[Map[String, String]] =
       |  val t = s.trim
       |  if !t.startsWith("{") || !t.endsWith("}") then None
       |  else
       |    val inner = t.substring(1, t.length - 1).trim
       |    if inner.isEmpty then Some(Map.empty)
       |    else try
       |      val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
       |      var i = 0
       |      def skipWs(): Unit = while i < inner.length && inner.charAt(i).isWhitespace do i += 1
       |      def readStr(): String =
       |        if inner.charAt(i) != '"' then throw RuntimeException("expected quote")
       |        i += 1
       |        val sb = StringBuilder()
       |        while i < inner.length && inner.charAt(i) != '"' do
       |          val c = inner.charAt(i)
       |          if c == '\\' && i + 1 < inner.length then
       |            inner.charAt(i + 1) match
       |              case '"'  => sb.append('"');  i += 2
       |              case '\\' => sb.append('\\'); i += 2
       |              case 'n'  => sb.append('\n'); i += 2
       |              case 'r'  => sb.append('\r'); i += 2
       |              case 't'  => sb.append('\t'); i += 2
       |              case _    => sb.append(c); i += 1
       |          else { sb.append(c); i += 1 }
       |        i += 1
       |        sb.toString
       |      def readScalar(): String =
       |        val sb = StringBuilder()
       |        while i < inner.length && inner.charAt(i) != ',' && inner.charAt(i) != '}' do
       |          sb.append(inner.charAt(i)); i += 1
       |        sb.toString.trim
       |      def readNested(open: Char, close: Char): String =
       |        val sb = StringBuilder().append(inner.charAt(i)); i += 1
       |        var depth = 1
       |        while i < inner.length && depth > 0 do
       |          val c = inner.charAt(i)
       |          sb.append(c)
       |          if c == '"' then
       |            i += 1
       |            while i < inner.length && inner.charAt(i) != '"' do
       |              if inner.charAt(i) == '\\' && i + 1 < inner.length then
       |                sb.append(inner.charAt(i)).append(inner.charAt(i + 1)); i += 2
       |              else { sb.append(inner.charAt(i)); i += 1 }
       |            if i < inner.length then { sb.append('"'); i += 1 }
       |          else
       |            if c == open  then depth += 1
       |            if c == close then depth -= 1
       |            i += 1
       |        sb.toString
       |      while i < inner.length do
       |        skipWs(); val k = readStr()
       |        skipWs()
       |        if inner.charAt(i) != ':' then throw RuntimeException("expected colon")
       |        i += 1
       |        skipWs()
       |        val v =
       |          if inner.charAt(i) == '"' then readStr()
       |          else if inner.charAt(i) == '{' then readNested('{', '}')
       |          else if inner.charAt(i) == '[' then readNested('[', ']')
       |          else readScalar()
       |        out(k) = v
       |        skipWs()
       |        if i < inner.length then
       |          if inner.charAt(i) != ',' then throw RuntimeException("expected comma")
       |          i += 1; skipWs()
       |      Some(out.toMap)
       |    catch case _: Throwable => None
       |def oauthUserinfo(provider: String, accessToken: String): Option[Map[String, String]] =
       |  _oauthCfg(provider).flatMap(_.get("userinfoUrl")).flatMap { url =>
       |    try
       |      val client = java.net.http.HttpClient.newBuilder()
       |        .connectTimeout(java.time.Duration.ofSeconds(10)).build()
       |      val req = java.net.http.HttpRequest.newBuilder()
       |        .uri(java.net.URI.create(url))
       |        .timeout(java.time.Duration.ofSeconds(30))
       |        .header("Authorization", s"Bearer $accessToken")
       |        .header("Accept",        "application/json")
       |        .header("User-Agent",    "scalascript-oauth/0.6")
       |        .GET().build()
       |      val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
       |      if resp.statusCode() < 200 || resp.statusCode() >= 300 then None
       |      else _oauthJsonFlat(resp.body())
       |    catch case _: Throwable => None
       |  }
       |
       |def oauthExchangeCode(provider: String, code: String, clientId: String, clientSecret: String, redirectUri: String): Option[Map[String, String]] =
       |  _oauthCfg(provider).flatMap { cfg =>
       |    val tokenUrl = cfg("tokenUrl")
       |    val form = Map(
       |      "grant_type"    -> "authorization_code",
       |      "code"          -> code,
       |      "client_id"     -> clientId,
       |      "client_secret" -> clientSecret,
       |      "redirect_uri"  -> redirectUri,
       |    )
       |    val body = form.iterator.map((k, v) => _oauthEnc(k) + "=" + _oauthEnc(v)).mkString("&")
       |    try
       |      val client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
       |      val req = java.net.http.HttpRequest.newBuilder()
       |        .uri(java.net.URI.create(tokenUrl))
       |        .timeout(java.time.Duration.ofSeconds(30))
       |        .header("Content-Type", "application/x-www-form-urlencoded")
       |        .header("Accept", "application/json")
       |        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
       |        .build()
       |      val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
       |      if resp.statusCode() < 200 || resp.statusCode() >= 300 then None
       |      else
       |        val text = resp.body()
       |        val ct   = resp.headers().firstValue("content-type").orElse("").toLowerCase
       |        if ct.contains("application/json") || text.trim.startsWith("{") then
       |          _sessionJsonDec(text)
       |        else
       |          Some(text.split('&').iterator.flatMap { pair =>
       |            val i = pair.indexOf('=')
       |            if i < 0 then None
       |            else Some(
       |              java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") ->
       |              java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8"))
       |          }.toMap)
       |    catch case _: Throwable => None
       |  }
       |
       |// `oauthRefreshToken(provider, refresh, clientId, clientSecret)` —
       |// trade a long-lived refresh token for a fresh access token.
       |def oauthRefreshToken(provider: String, refresh: String, clientId: String, clientSecret: String): Option[Map[String, String]] =
       |  _oauthCfg(provider).flatMap { cfg =>
       |    val tokenUrl = cfg("tokenUrl")
       |    val form = Map(
       |      "grant_type"    -> "refresh_token",
       |      "refresh_token" -> refresh,
       |      "client_id"     -> clientId,
       |      "client_secret" -> clientSecret,
       |    )
       |    val body = form.iterator.map((k, v) => _oauthEnc(k) + "=" + _oauthEnc(v)).mkString("&")
       |    try
       |      val client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
       |      val req = java.net.http.HttpRequest.newBuilder()
       |        .uri(java.net.URI.create(tokenUrl))
       |        .timeout(java.time.Duration.ofSeconds(30))
       |        .header("Content-Type", "application/x-www-form-urlencoded")
       |        .header("Accept", "application/json")
       |        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
       |        .build()
       |      val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
       |      if resp.statusCode() < 200 || resp.statusCode() >= 300 then None
       |      else
       |        val text = resp.body()
       |        val ct   = resp.headers().firstValue("content-type").orElse("").toLowerCase
       |        if ct.contains("application/json") || text.trim.startsWith("{") then _oauthJsonFlat(text)
       |        else
       |          Some(text.split('&').iterator.flatMap { pair =>
       |            val i = pair.indexOf('=')
       |            if i < 0 then None
       |            else Some(
       |              java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") ->
       |              java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8"))
       |          }.toMap)
       |    catch case _: Throwable => None
       |  }
       |
       |// HTTP Basic: Authorization: Basic <b64(user:pass)>
       |private def _basicFromAuth(h: String): Option[(String, String)] =
       |  val t = Option(h).map(_.trim).getOrElse("")
       |  if t.length < 6 || !t.substring(0, 6).equalsIgnoreCase("Basic ") then None
       |  else
       |    try
       |      val decoded = new String(java.util.Base64.getDecoder.decode(t.substring(6).trim), "UTF-8")
       |      val colon   = decoded.indexOf(':')
       |      if colon < 0 then None
       |      else Some(decoded.substring(0, colon) -> decoded.substring(colon + 1))
       |    catch case _: Throwable => None
       |
       |// ── CSRF helpers ────────────────────────────────────────────────
       |// `csrfToken()` returns a url-safe random token; the caller stashes
       |// it under "csrf" in the session and renders it in their form.
       |// `csrfValid(req)` checks form `csrf` / `X-CSRF-Token` header.
       |def csrfToken(): String =
       |  val bytes = new Array[Byte](24)
       |  java.security.SecureRandom().nextBytes(bytes)
       |  _b64urlEnc(bytes)
       |def csrfValid(req: Request): Boolean =
       |  val expected = req.session.getOrElse("csrf", "")
       |  val supplied = req.form.get("csrf")
       |    .orElse(req.headers.collectFirst {
       |      case (k, v) if k.equalsIgnoreCase("X-CSRF-Token") => v
       |    })
       |    .getOrElse("")
       |  if expected.isEmpty || supplied.isEmpty || expected.length != supplied.length then false
       |  else java.security.MessageDigest.isEqual(
       |    expected.getBytes("UTF-8"), supplied.getBytes("UTF-8"))
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
       |  def basicAuthChallenge(realm: String): Response =
       |    val safe = realm.replace("\\", "\\\\").replace("\"", "\\\"")
       |    Response(401, Map("WWW-Authenticate" -> ("Basic realm=\"" + safe + "\"")), "Authentication required")
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
       |        val cookieHeader = headers.collectFirst {
       |          case (k, v) if k.equalsIgnoreCase("Cookie") => v
       |        }.getOrElse("")
       |        val rawCookieSession = _parseCookieSession(cookieHeader)
       |        val session =
       |          if _sessionStoreEnabled then
       |            rawCookieSession.get("_ssid").flatMap(_sessionStoreGet).getOrElse(Map.empty)
       |          else rawCookieSession
       |        val authHeader = headers.collectFirst {
       |          case (k, v) if k.equalsIgnoreCase("Authorization") => v
       |        }.getOrElse("")
       |        val bearer    = _bearerFromAuth(authHeader)
       |        val claims    = bearer.flatMap(jwtVerify)
       |        val basicAuth = _basicFromAuth(authHeader)
       |        val req  = Request(method, path, params,
       |          _parseQuery(ex.getRequestURI.getRawQuery), headers, body,
       |          form, files, session, bearer, claims, basicAuth)
       |        _writeResponse(ex, r.handler(req), rawCookieSession)
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
       |private def _writeResponse(
       |    ex:               com.sun.net.httpserver.HttpExchange,
       |    r:                Response,
       |    rawCookieSession: Map[String, String] = Map.empty
       |): Unit =
       |  r.headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
       |  if !r.headers.contains("Content-Type") then
       |    ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
       |  r.setSession.foreach { payload =>
       |    val cookiePayload: Map[String, String] =
       |      if !_sessionStoreEnabled then payload
       |      else if payload.isEmpty then
       |        rawCookieSession.get("_ssid").foreach(_sessionStoreDelete)
       |        Map.empty
       |      else
       |        rawCookieSession.get("_ssid").foreach(_sessionStoreDelete)
       |        val ssid = _sessionStorePut(payload)
       |        Map("_ssid" -> ssid)
       |    ex.getResponseHeaders.add("Set-Cookie", _buildSetCookie(cookiePayload))
       |  }
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
       |// ── WebSocket support (RFC 6455) ───────────────────────────────────────
       |//
       |// Asymmetric design vs the interpreter: the interpreter runs an NIO
       |// selector loop in front of the JDK `HttpServer`; here we use a
       |// blocking-IO proxy (one thread per connection).  The user-facing
       |// `onWebSocket(path) { ws => … }` API is identical, so .ssc code is
       |// portable.  NIO migration on this side will land together with the
       |// HTTP NIO rewrite.
       |//
       |// `serve(port)` below stops binding the JDK HttpServer directly: it
       |// starts the HttpServer on a loopback ephemeral port and a public
       |// blocking proxy that sniffs the request head and either upgrades to
       |// WebSocket (handshake + frame loop in-place) or forwards bytes both
       |// ways to the internal HttpServer (REST + static files unchanged).
       |
       |class WebSocket(private val socket: java.net.Socket):
       |  import java.io.{BufferedInputStream, OutputStream}
       |  import java.nio.charset.StandardCharsets
       |  @volatile private var onMessageCb: String => Unit = null
       |  @volatile private var onCloseCb:   () => Unit     = null
       |  @volatile private var closing:     Boolean        = false
       |  private val out: OutputStream                     = socket.getOutputStream
       |
       |  def send(s: String): Unit = synchronized {
       |    if !closing && !socket.isClosed then
       |      out.write(_wsEncodeText(s))
       |      out.flush()
       |  }
       |
       |  def close(code: Int = 1000, reason: String = ""): Unit = synchronized {
       |    if !closing && !socket.isClosed then
       |      closing = true
       |      out.write(_wsEncodeClose(code, reason))
       |      out.flush()
       |      try socket.close() catch case _: Throwable => ()
       |      val cb = onCloseCb; onCloseCb = null
       |      if cb != null then try cb() catch case _: Throwable => ()
       |  }
       |
       |  def onMessage(cb: String => Unit): Unit = onMessageCb = cb
       |  def onClose(cb: () => Unit): Unit       = onCloseCb   = cb
       |
       |  // Read-loop entry point: called from the per-connection thread
       |  // after the handshake completes.  Pulls bytes through the parser,
       |  // dispatches frames synchronously on the same thread.  Returns
       |  // when the peer closes or a protocol error occurs.
       |  def _runReadLoop(): Unit =
       |    val in   = BufferedInputStream(socket.getInputStream)
       |    var buf  = new Array[Byte](4096)
       |    var len  = 0
       |    try
       |      while !closing && !socket.isClosed do
       |        if len == buf.length then
       |          val grown = new Array[Byte](buf.length * 2); System.arraycopy(buf, 0, grown, 0, len); buf = grown
       |        val n = in.read(buf, len, buf.length - len)
       |        if n < 0 then return
       |        len += n
       |        var offset = 0
       |        var more   = true
       |        while more do
       |          _wsParseFrame(buf, offset, len) match
       |            case null => more = false
       |            case (fr, consumed) =>
       |              offset += consumed
       |              fr.opcode match
       |                case 0x9 => synchronized { out.write(_wsEncodePong(fr.payload)); out.flush() }
       |                case 0xA => ()
       |                case 0x8 =>
       |                  val status =
       |                    if fr.payload.length >= 2
       |                    then ((fr.payload(0) & 0xFF) << 8) | (fr.payload(1) & 0xFF)
       |                    else 1000
       |                  close(status, "")
       |                  return
       |                case 0x1 | 0x2 =>
       |                  val msg =
       |                    if fr.opcode == 0x1
       |                    then new String(fr.payload, StandardCharsets.UTF_8)
       |                    else new String(fr.payload, "ISO-8859-1")
       |                  val cb = onMessageCb
       |                  if cb != null then try cb(msg) catch case e: Throwable =>
       |                    System.err.println(s"WS message handler: ${e.getMessage}")
       |                case _   => close(1003, ""); return
       |        if offset > 0 then
       |          System.arraycopy(buf, offset, buf, 0, len - offset)
       |          len -= offset
       |    catch case _: Throwable => ()
       |    finally
       |      try socket.close() catch case _: Throwable => ()
       |      val cb = onCloseCb; onCloseCb = null
       |      if cb != null then try cb() catch case _: Throwable => ()
       |
       |private final case class _WsRoute(pattern: List[_Seg], handler: WebSocket => Unit)
       |private val _wsRoutes = scala.collection.mutable.ArrayBuffer.empty[_WsRoute]
       |
       |def onWebSocket(path: String): (WebSocket => Unit) => Unit = (handler) => {
       |  _wsRoutes += _WsRoute(_parsePath(path), handler)
       |}
       |
       |// ── Framing ──────────────────────────────────────────────────────────
       |
       |private val _WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
       |
       |def _wsAcceptKey(clientKey: String): String =
       |  val md = java.security.MessageDigest.getInstance("SHA-1")
       |  val digest = md.digest((clientKey + _WS_MAGIC).getBytes("US-ASCII"))
       |  java.util.Base64.getEncoder.encodeToString(digest)
       |
       |private final case class _WsFrame(fin: Boolean, opcode: Int, payload: Array[Byte])
       |
       |// Returns (frame, bytesConsumed) or null if more bytes are needed.
       |// Throws on unrecoverable protocol error — caller closes the connection.
       |private def _wsParseFrame(buf: Array[Byte], offset: Int, until: Int): (_WsFrame, Int) | Null =
       |  val avail = until - offset
       |  if avail < 2 then return null
       |  val b0 = buf(offset)     & 0xFF
       |  val b1 = buf(offset + 1) & 0xFF
       |  val fin = (b0 & 0x80) != 0
       |  val op  = b0 & 0x0F
       |  val masked = (b1 & 0x80) != 0
       |  val len7   = b1 & 0x7F
       |  var hdrLen = 2
       |  val payloadLen: Int =
       |    if len7 <= 125 then len7
       |    else if len7 == 126 then
       |      if avail < hdrLen + 2 then return null
       |      hdrLen += 2
       |      ((buf(offset + 2) & 0xFF) << 8) | (buf(offset + 3) & 0xFF)
       |    else
       |      if avail < hdrLen + 8 then return null
       |      hdrLen += 8
       |      var v = 0L; var i = 0
       |      while i < 8 do { v = (v << 8) | (buf(offset + 2 + i) & 0xFF).toLong; i += 1 }
       |      if v > Int.MaxValue.toLong then throw RuntimeException("WS frame too large")
       |      v.toInt
       |  val maskLen = if masked then 4 else 0
       |  val total   = hdrLen + maskLen + payloadLen
       |  if avail < total then return null
       |  val payload = new Array[Byte](payloadLen)
       |  val payloadStart = offset + hdrLen + maskLen
       |  if masked then
       |    val m = Array.tabulate(4)(i => buf(offset + hdrLen + i))
       |    var i = 0
       |    while i < payloadLen do { payload(i) = (buf(payloadStart + i) ^ m(i & 3)).toByte; i += 1 }
       |  else if payloadLen > 0 then
       |    System.arraycopy(buf, payloadStart, payload, 0, payloadLen)
       |  (_WsFrame(fin, op, payload), total)
       |
       |private def _wsEncodeFrame(opcode: Int, payload: Array[Byte]): Array[Byte] =
       |  val len = payload.length
       |  if len <= 125 then
       |    val b = new Array[Byte](2 + len)
       |    b(0) = (0x80 | opcode).toByte; b(1) = len.toByte
       |    System.arraycopy(payload, 0, b, 2, len); b
       |  else if len <= 0xFFFF then
       |    val b = new Array[Byte](4 + len)
       |    b(0) = (0x80 | opcode).toByte; b(1) = 126.toByte
       |    b(2) = ((len >> 8) & 0xFF).toByte; b(3) = (len & 0xFF).toByte
       |    System.arraycopy(payload, 0, b, 4, len); b
       |  else
       |    val b = new Array[Byte](10 + len)
       |    b(0) = (0x80 | opcode).toByte; b(1) = 127.toByte
       |    var v = len.toLong; var i = 0
       |    while i < 8 do { b(9 - i) = (v & 0xFF).toByte; v >>>= 8; i += 1 }
       |    System.arraycopy(payload, 0, b, 10, len); b
       |
       |def _wsEncodeText(s: String): Array[Byte] =
       |  _wsEncodeFrame(0x1, s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
       |def _wsEncodePong(p: Array[Byte]): Array[Byte] = _wsEncodeFrame(0xA, p)
       |def _wsEncodeClose(code: Int, reason: String): Array[Byte] =
       |  val r = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8)
       |  val p = new Array[Byte](2 + r.length)
       |  p(0) = ((code >> 8) & 0xFF).toByte; p(1) = (code & 0xFF).toByte
       |  System.arraycopy(r, 0, p, 2, r.length); _wsEncodeFrame(0x8, p)
       |
       |// ── Proxy: blocking accept + sniff + forward / upgrade ───────────────
       |
       |private def _readHttpHead(in: java.io.BufferedInputStream): Array[Byte] =
       |  val sb = scala.collection.mutable.ArrayBuffer.empty[Byte]
       |  var prev3 = 0; var prev2 = 0; var prev1 = 0
       |  var done  = false
       |  while !done do
       |    val b = in.read()
       |    if b < 0 then return sb.toArray
       |    sb += b.toByte
       |    if prev3 == 13 && prev2 == 10 && prev1 == 13 && b == 10 then done = true
       |    prev3 = prev2; prev2 = prev1; prev1 = b
       |  sb.toArray
       |
       |private def _proxyConnection(client: java.net.Socket, internalPort: Int): Unit =
       |  val cin  = java.io.BufferedInputStream(client.getInputStream)
       |  val cout = client.getOutputStream
       |  val head = _readHttpHead(cin)
       |  val headText = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1)
       |  val lines    = headText.split("\r\n").toList
       |  val request  = lines.headOption.getOrElse("")
       |  val headers: Map[String, String] = lines.drop(1).flatMap { l =>
       |    val i = l.indexOf(':')
       |    if i < 0 then None
       |    else Some(l.substring(0, i).trim.toLowerCase -> l.substring(i + 1).trim)
       |  }.toMap
       |  val path = request.split(' ').lift(1).getOrElse("/").split('?').head
       |  val isWs = headers.get("upgrade").exists(_.equalsIgnoreCase("websocket")) &&
       |             headers.get("connection").exists(_.toLowerCase.contains("upgrade"))
       |  if isWs then
       |    val segs = path.split('/').toList.filter(_.nonEmpty)
       |    val matched = _wsRoutes.iterator.flatMap { r =>
       |      _matchPath(r.pattern, segs).map(_ => r)
       |    }.nextOption()
       |    matched match
       |      case None =>
       |        cout.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"))
       |        cout.flush(); client.close()
       |      case Some(r) =>
       |        val key = headers.getOrElse("sec-websocket-key", "")
       |        if key.isEmpty then { client.close(); return }
       |        val accept = _wsAcceptKey(key)
       |        val resp =
       |          "HTTP/1.1 101 Switching Protocols\r\n" +
       |          "Upgrade: websocket\r\n" +
       |          "Connection: Upgrade\r\n" +
       |          s"Sec-WebSocket-Accept: $accept\r\n\r\n"
       |        cout.write(resp.getBytes("US-ASCII")); cout.flush()
       |        val ws = WebSocket(client)
       |        try r.handler(ws) catch case e: Throwable =>
       |          System.err.println(s"WS upgrade handler: ${e.getMessage}")
       |        ws._runReadLoop()
       |  else
       |    // Plain HTTP — open a backend socket to the internal HttpServer
       |    // and copy bytes both ways until either side EOFs.
       |    val back = java.net.Socket("127.0.0.1", internalPort)
       |    val bin  = java.io.BufferedInputStream(back.getInputStream)
       |    val bout = back.getOutputStream
       |    bout.write(head); bout.flush()
       |    val pump1 = Thread(() => _pump(cin, bout, back, client), "ws-proxy-pump-c2b")
       |    val pump2 = Thread(() => _pump(bin, cout, client, back), "ws-proxy-pump-b2c")
       |    pump1.setDaemon(true); pump2.setDaemon(true)
       |    pump1.start(); pump2.start()
       |    pump1.join(); pump2.join()
       |
       |private def _pump(in: java.io.InputStream, out: java.io.OutputStream, a: java.net.Socket, b: java.net.Socket): Unit =
       |  val buf = new Array[Byte](8192)
       |  try
       |    var n = in.read(buf)
       |    while n >= 0 do
       |      out.write(buf, 0, n); out.flush()
       |      n = in.read(buf)
       |  catch case _: Throwable => ()
       |  finally
       |    try a.close() catch case _: Throwable => ()
       |    try b.close() catch case _: Throwable => ()
       |
       |def serve(port: Int): Unit =
       |  // Internal HttpServer on a loopback ephemeral port — REST + static
       |  // files keep flowing through it as before, single-threaded.
       |  val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
       |  val internal = com.sun.net.httpserver.HttpServer.create(
       |    java.net.InetSocketAddress("127.0.0.1", 0), 0)
       |  internal.createContext("/", _handle)
       |  internal.setExecutor(executor)
       |  internal.start()
       |  val internalPort = internal.getAddress.getPort
       |  // Public-facing blocking proxy: one accept loop, one thread per
       |  // connection.  WS connections hold their thread for the lifetime
       |  // of the session; HTTP connections spawn two pump threads.
       |  val pub = java.net.ServerSocket(port)
       |  println(s"Listening on http://localhost:$port/  (proxy → 127.0.0.1:$internalPort)")
       |  val pool = java.util.concurrent.Executors.newCachedThreadPool()
       |  Thread(() => {
       |    while !pub.isClosed do
       |      try
       |        val c = pub.accept()
       |        pool.execute { () => _proxyConnection(c, internalPort) }
       |      catch case _: Throwable => ()
       |  }, "ws-proxy-accept").start()
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
