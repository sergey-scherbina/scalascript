package scalascript.codegen

import scalascript.ast.*
import scalascript.transform.EffectAnalysis
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

  def generate(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty
  ): String =
    JvmGen(baseDir, intrinsics).genModule(module)

  private case class Block(node: ScalaNode, src: String)
  /** A heading-bound `html` / `css` code block: render to a string in the
   *  same source position as the surrounding parsed blocks, then bind to
   *  `<sectionId>.<lang>` (html or css) at the end of the module. */
  private case class StringBlockEntry(lang: String, src: String, sectionId: String, order: Int)

class JvmGen(
    baseDir:    Option[os.Path] = None,
    intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty):
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

  /** Module-level `dependencies:` from the front-matter; threaded into
   *  `inlineImport` so `<dep-name>://path` imports rewrite through the
   *  resolver. */
  private var moduleDeps: Map[String, String] = Map.empty

  def genModule(module: Module): String =
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    // Collect blocks first — including those pulled in by `[..](./x.ssc)`
    // imports — so the effect / mutual-TCO analysis sees the full picture.
    val blocks = collectBlocks(module.sections)
    analyzeEffects(blocks)
    analyzeMutualRecursion(blocks)
    val sb = StringBuilder()

    // //> using directives from YAML front-matter.  URL-shaped values
    // are SSC-style `.ssc` deps (resolved by `ImportResolver`), not
    // Maven coordinates — skip them here so scala-cli doesn't try to
    // parse `cards:http://…` as a `g:a:v` triple and abort.
    module.manifest.foreach { m =>
      m.dependencies.foreach { (dep, version) =>
        if !version.startsWith("http://") && !version.startsWith("https://") then
          sb.append(s"""//> using dep "$dep:$version"\n""")
      }
    }

    val frontmatterRoutes = module.manifest.toList.flatMap(_.routes)

    sb.append(preamble)
    sb.append(htmlDslTagBindings(collectUserTopNames(blocks)))
    if effectOps.nonEmpty                                  then sb.append(effectsRuntime)
    if mutualGroups.nonEmpty                               then sb.append(mutualTcoRuntime)
    if blocksUseReactive(blocks)                           then sb.append(reactiveRuntime)
    if blocksUseRoutes(blocks) || frontmatterRoutes.nonEmpty || blocksUseJson(blocks) then sb.append(serveRuntime)

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
          inlineImport(imp.path) ++ aliasBlock(imp.bindings).toList
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

  /** For each `[Name as Alias]` binding, synthesise a `val Alias = Name`
   *  declaration so the alias is available in the consumer's scope.
   *  Whole-module inline means `Name` is already visible from the
   *  import; the alias is an additional name pointing at the same value.
   *  Returns `None` when no binding carries an alias. */
  private def aliasBlock(bindings: List[ImportBinding]): Option[JvmGen.Block] =
    import scala.meta.{dialects, *}
    val aliases = bindings.collect {
      case ImportBinding(name, Some(alias), _) => s"val $alias = $name"
    }
    if aliases.isEmpty then None
    else
      val src   = aliases.mkString("\n")
      val input = Input.VirtualFile("<import-aliases>", src)
      dialects.Scala3(input).parse[Source].toOption.map(s => JvmGen.Block(ScalaNode(s), src))

  /** Resolve a `[name](./path.ssc)` Markdown import: parse the referenced
   *  file and return its code blocks, transitively following its own imports.
   *  Each path is inlined at most once per JvmGen run. */
  private def inlineImport(path: String): List[JvmGen.Block] =
    import scalascript.parser.Parser
    val base = baseDir.getOrElse(os.pwd)
    val resolved =
      try scalascript.imports.ImportResolver.resolve(path, base, moduleDeps)
      catch case e: Throwable => throw new RuntimeException(s"Import $path: ${e.getMessage}")
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
    // Built-in `Async` / `Storage` / `Actor` effects — pre-populated only
    // when the module actually uses them, keeping the emitted Scala lean
    // otherwise.
    val builtins =
      (if blocksUseAsync(blocks) then
         Set("Async.delay", "Async.async", "Async.await", "Async.parallel")
       else Set.empty[String]) ++
      (if blocksUseStorage(blocks) then
         Set("Storage.get", "Storage.put", "Storage.remove", "Storage.has", "Storage.keys")
       else Set.empty[String]) ++
      (if blocksUseActors(blocks) then
         Set("Actor.spawn", "Actor.self", "Actor.send", "Actor.exit",
             "Actor.receive", "Actor.receive_t",
             "Actor.link", "Actor.monitor", "Actor.demonitor", "Actor.trapExit")
       else Set.empty[String])

    val trees = blocks.map(b => ScalaNode.fold(b.node)(identity))
    val r     = EffectAnalysis.analyze(trees, builtins)

    effectOps.clear();     effectOps     ++= r.effectOps
    effectfulFuns.clear(); effectfulFuns ++= r.effectfulFuns

  private def isEffectOpDef(body: Term): Boolean = EffectAnalysis.isEffectOpDef(body)

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
          case Term.Apply.After_4_6_0(Term.Name("wsConnect"),    _) => found = true
          // `serve(port)` on its own (no user routes) must still pull in
          // the runtime: Tier 5 #21 auto-registers `/_health` /_ready` at
          // serve() time so a bare `serve(8080)` is a valid program.
          case Term.Apply.After_4_6_0(Term.Name("serve"),        _) => found = true
        }
      }
      found
    }

  /** True if any block references the standalone JSON / REST-validation
   *  helpers without going through `serve()`.  Pulls in `serveRuntime`
   *  — which carries `_toJson` / `_fromJson` / `_lookupKey` plus the
   *  `require*` / `validate` family — so the script compiles even when
   *  it never registers a route. */
  private def blocksUseJson(blocks: List[JvmGen.Block]): Boolean =
    val triggers = Set(
      "jsonParse", "jsonStringify", "jsonRead",
      "lookup", "lookupOpt",
      "validate",
      "requireString",  "optionalString",
      "requireInt",     "optionalInt",
      "requireDouble",  "optionalDouble",
      "requireBool",    "optionalBool",
      "requireRange",   "requireRangeDouble",
      "requireOneOf",
    )
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _) if triggers(n) => found = true
        }
      }
      found
    }

  /** True if any block references the built-in `Async` effect — either
   *  via `runAsync(...)` or via a `Async.{delay,async,await,parallel}`
   *  call.  Used to gate registration of the four Async op names in
   *  `effectOps` (and therefore the emission of the effects runtime). */
  private def blocksUseAsync(blocks: List[JvmGen.Block]): Boolean =
    val asyncOps = Set("delay", "async", "await", "parallel")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("runAsync"),         _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), _) => found = true
          case Term.Select(Term.Name("Async"), Term.Name(op))
              if asyncOps(op) => found = true
        }
      }
      found
    }

  /** True if any block uses the reactive primitives `Signal(...)`,
   *  `computed { ... }`, or `effect { ... }`.  Gates emission of the
   *  reactive runtime preamble in the generated Scala script. */
  private def blocksUseReactive(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("Signal"),   _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("computed"), _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("effect"),   _) => found = true
        }
      }
      found
    }

  /** True if any block references the v1.6 actor model — via
   *  `runActors`, `spawn`, `self`, `exit`, `receive`, `link`, `monitor`,
   *  `demonitor`, `trapExit`, or Phase 3 distributed primitives. */
  private def blocksUseActors(blocks: List[JvmGen.Block]): Boolean =
    val names = Set("runActors", "spawn", "self", "exit", "receive",
                    "link", "monitor", "demonitor", "trapExit",
                    "startNode", "connectNode", "register", "whereis")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _) if names(n) => found = true
        }
      }
      found
    }

  /** True if any block references the built-in `Storage` effect —
   *  either via `runStorage` / `runEphemeralStorage` or a
   *  `Storage.{get,put,remove,has,keys}` call. */
  private def blocksUseStorage(blocks: List[JvmGen.Block]): Boolean =
    val storageOps = Set("get", "put", "remove", "has", "keys")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("runStorage"),          _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), _) => found = true
          case Term.Select(Term.Name("Storage"), Term.Name(op))
              if storageOps(op) => found = true
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
    blockUsesEffects(node)        ||
    blockUsesMutualTco(node)      ||
    blockHasAutoOutputTerm(node)  ||
    blockUsesIntrinsics(node)     ||
    blockContainsExternDef(node)

  /** Stage 5+/A.6 (Б-1) — force blocks that declare an `extern def`
   *  through `emitStats` so the extern stub gets filtered out (the
   *  intrinsic table provides the real impl).  Without this the
   *  `__extern__` body marker would emit verbatim and scala-cli
   *  would fail with "Not found: __extern__". */
  private def blockContainsExternDef(node: ScalaNode): Boolean =
    def go(t: scala.meta.Tree): Boolean = t match
      case d: Defn.Def if EffectAnalysis.isExternDef(d.body) => true
      case other => other.children.exists(go)
    ScalaNode.fold(node)(go)

  /** Stage 5+/A.4 — force blocks that call a registered intrinsic
   *  through `emitExpr` so per-call-site dispatch fires.  Without
   *  this, simple `val t = nowMillis()` would passthrough as raw
   *  Scala and `nowMillis` would be unresolved (the Stage 5+/A.3
   *  prelude alias is gone). */
  private def blockUsesIntrinsics(node: ScalaNode): Boolean =
    if intrinsics.isEmpty then false
    else
      val names = intrinsics.keySet.map(_.value)
      def go(t: scala.meta.Tree): Boolean = t match
        case Term.Apply.After_4_6_0(Term.Name(n), _) if names(n) => true
        case other => other.children.exists(go)
      ScalaNode.fold(node)(go)

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
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), _)                         => true
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), _)                 => true
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), _)                       => true
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), _)              => true
    case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op)     => true
    case Term.Apply.After_4_6_0(Term.Name(n), _) if isEffectfulFun(n)             => true
    case _ => t.children.exists {
      case tt: Term => termUsesEffects(tt)
      case _        => false
    }

  /** True if the term needs codegen rewriting (effect machinery,
   *  Focus → Lens expansion, Prism[O, V] → Prism literal) rather than
   *  verbatim Scala source emission. */
  private def termNeedsCustomEmit(t: Term): Boolean =
    termUsesEffects(t) || termContainsFocus(t) || termContainsPrism(t) || termContainsIntrinsic(t)

  /** Stage 5+/A.4 — `val t = nowMillis()` and similar val-bound
   *  intrinsic calls need the rhs to go through `emitExpr` (where
   *  the intrinsic dispatch fires).  Without this they emit the
   *  raw scalameta syntax and the bare intrinsic name is
   *  unresolved at scala-cli compile time. */
  private def termContainsIntrinsic(t: Term): Boolean =
    if intrinsics.isEmpty then false
    else
      val names = intrinsics.keySet.map(_.value)
      def walk(n: Tree): Boolean = n match
        case Term.Apply.After_4_6_0(Term.Name(nm), _) if names(nm) => true
        case _ => n.children.exists(walk)
      walk(t)

  private def termContainsFocus(t: Term): Boolean =
    var found = false
    def walk(n: Tree): Unit =
      if !found then n match
        case app: Term.Apply if isFocusApp(app) => found = true
        case _ => n.children.foreach(walk)
    walk(t)
    found

  private def termContainsPrism(t: Term): Boolean =
    var found = false
    def walk(n: Tree): Unit =
      if !found then n match
        case ta: Term.ApplyType if isPrismApplyType(ta) => found = true
        case _ => n.children.foreach(walk)
    walk(t)
    found

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
    // Stage 5+/A.6 (Б-1) — `extern def foo(...): T = __extern__` is a
    // type-only stub; the intrinsic table provides the real impl
    // (caught at call sites by `dispatchIntrinsic`).  Skip emission
    // so Scala doesn't choke on `__extern__`.
    case d: Defn.Def if EffectAnalysis.isExternDef(d.body) => ""

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
      // Widen all params to `Any` — inside CPS the body operates on Any
      // (Pure value | Free node) anyway, and Any-typed params let callers
      // inside an enclosing CPS context (where they hand us Any-bound
      // locals) typecheck without per-arg casts.
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map { p =>
        s"${p.name.value}: Any"
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

  /** Stage 5+/A.4 — per-call-site intrinsic dispatch.  Returns the
   *  TargetCode string to splice into the emitted Scala, or `None`
   *  if no intrinsic claims this name.  Called from `emitExpr` for
   *  Term.Apply(Term.Name(fname), args) sites BEFORE the existing
   *  hardcoded pattern matches, so a registered intrinsic always
   *  wins.
   *
   *  Limitation: only fires when the block goes through `emitExpr`
   *  (effects / mutual-TCO / auto-output) — passthrough blocks let
   *  Scala's own name resolution apply.  Future work moves more
   *  blocks through `emitExpr` so the dispatch covers everything. */
  private def dispatchIntrinsic(fname: String, argClause: Term.ArgClause): Option[String] =
    val qn = scalascript.ir.QualifiedName(fname)
    intrinsics.get(qn).map {
      case scalascript.backend.spi.RuntimeCall(target) =>
        s"$target(${argClause.values.map(_.syntax).mkString(", ")})"
      case scalascript.backend.spi.InlineCode(emit) =>
        // Convert scalameta args → IR exprs (literal types preserved;
        // everything else as a VarRef of its syntactic surface).
        // Crude but covers `println("hello")` / `print(42)`-shaped
        // call sites where args are scalars.
        val irArgs = argClause.values.map(termToIr)
        val ctx    = JvmEmitContext
        emit(irArgs, ctx).value
      case _ =>
        // NativeImpl / HostCallback don't emit target source; fall
        // through to scalameta's default emission.
        argClause.values.map(_.syntax).mkString(s"$fname(", ", ", ")")
    }

  /** Minimum-viable IrExpr conversion for intrinsic dispatch — only
   *  string / int / double literals survive shape; everything else
   *  becomes a `VarRef` carrying the scalameta syntax (so the
   *  intrinsic can splice it back into the emitted Scala unchanged). */
  private def termToIr(t: Term): scalascript.ir.IrExpr = t match
    case Lit.String(s)  => scalascript.ir.Lit(scalascript.ir.LitValue.StringL(s))
    case Lit.Int(n)     => scalascript.ir.Lit(scalascript.ir.LitValue.IntL(n.toLong))
    case Lit.Long(n)    => scalascript.ir.Lit(scalascript.ir.LitValue.IntL(n))
    case Lit.Double(d)  => scalascript.ir.Lit(scalascript.ir.LitValue.DoubleL(d.toDouble))
    case Lit.Boolean(b) => scalascript.ir.Lit(scalascript.ir.LitValue.BoolL(b))
    case Lit.Unit()     => scalascript.ir.Lit(scalascript.ir.LitValue.UnitL)
    case other          => scalascript.ir.VarRef(other.syntax)

  /** Stage 5+/A.4 — `JvmGen`'s per-call-site EmitContext.  Trait
   *  methods stubbed for now (intrinsics in this stage don't need
   *  them); fleshed out in subsequent iterations. */
  private object JvmEmitContext extends scalascript.ir.EmitContext

  /** Emit a Scala expression. For non-effectful subtrees, fall through to
   *  scalameta's source. For effect-related subtrees, do custom emission. */
  private def emitExpr(term: Term): String = term match
    // Stage 5+/A.4 intrinsic dispatch — fires first.
    case Term.Apply.After_4_6_0(Term.Name(fname), argClause)
        if dispatchIntrinsic(fname, argClause).isDefined =>
      dispatchIntrinsic(fname, argClause).get

    // handle(body) { cases }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          emitHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => "??? /* invalid handle */"

    // runAsync(body) — built-in Async-effect driver.  Body is CPS-emitted
    // so Async.* ops build a Free tree that `_runAsync` walks against the
    // default handler.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // runAsyncParallel(body) — real-thread variant, same Async.* API
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsyncParallel(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Storage handlers
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val bodyJs = emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      val pathJs = bodyArgClause.values.lift(1)
        .map(p => emitExpr(p.asInstanceOf[Term]))
        .getOrElse("null")
      s"_runStorage(() => $bodyJs, $pathJs)"
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runStorage(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])}, null)"

    // ── v1.6 Actors Phase 1 ────────────────────────────────────────────
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runActors(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcher = emitReceiveMatcher(pf.cases)
          s"Actor.receive_t(_registerReceive($matcher), ${emitExpr(timeoutTerm.asInstanceOf[Term])})"
        case _ => "??? /* invalid receive */"

    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcher = emitReceiveMatcher(pf.cases)
          s"Actor.receive_(_registerReceive($matcher))"
        case _ => "??? /* invalid receive */"

    case Term.Apply.After_4_6_0(Term.Name("spawn"), argClause)
        if argClause.values.size == 1 =>
      // emitCpsExpr on a `() => …` Function literal already emits
      // `() => <cps-body>` — exactly the `() => Any` thunk `Actor.spawn`
      // expects.  Wrapping in another `() =>` would double-thunk.
      s"Actor.spawn(${emitCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("self"), argClause)
        if argClause.values.isEmpty =>
      "Actor.self()"
    case Term.Apply.After_4_6_0(Term.Name("exit"), argClause)
        if argClause.values.size == 2 =>
      val pidJs    = emitExpr(argClause.values(0).asInstanceOf[Term])
      val reasonJs = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.exit($pidJs, $reasonJs)"
    // v1.6 Phase 2 — supervision primitives
    case Term.Apply.After_4_6_0(Term.Name("link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.link(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("monitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.monitor(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("demonitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.demonitor(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("trapExit"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.trapExit(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6 Phase 3 — distributed node primitives
    case Term.Apply.After_4_6_0(Term.Name("startNode"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.startNode(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.connectNode(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.connectNode(${emitExpr(argClause.values(0).asInstanceOf[Term])}, ${emitExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${emitExpr(argClause.values(0).asInstanceOf[Term])}, ${emitExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${emitExpr(argClause.values.head.asInstanceOf[Term])})"

    // Focus[T](_.a.b) / Focus(_.a.b) — lower to a Lens(get, set) literal.
    // The lambda body's field-access chain becomes nested get + nested copy.
    case app: Term.Apply if isFocusApp(app) =>
      emitFocus(app)

    // Prism[Outer, Variant] — lower to a Prism(getOption, reverseGet) literal.
    case ta: Term.ApplyType if isPrismApplyType(ta) =>
      emitPrism(ta)

    // If the term has nested effect or Focus / Prism content, walk children.
    case _ if termNeedsCustomEmit(term) => emitExprDeep(term)

    // Otherwise emit Scala source as-is.
    case other => other.syntax

  private def isFocusApp(app: Term.Apply): Boolean = app.fun match
    case Term.Name("Focus")                                => true
    case ta: Term.ApplyType                                =>
      ta.fun match { case Term.Name("Focus") => true; case _ => false }
    case _                                                 => false

  private def isPrismApplyType(ta: Term.ApplyType): Boolean = ta.fun match
    case Term.Name("Prism") => true
    case _                  => false

  /** Lower `Prism[Outer, Variant]` to a `Prism(getOption, reverseGet)` literal
   *  that pattern-matches on the variant type. */
  private def emitPrism(ta: Term.ApplyType): String =
    ta.argClause.values match
      case List(outerType, variantType) =>
        val outer   = outerType.syntax
        val variant = variantType.syntax
        val label   = s"Prism[?, $variant]"
        s"""Prism[$outer, $variant]((s: $outer) => s match { case _v: $variant => Some(_v); case _ => None }, (a: $variant) => a, "$label")"""
      case _ =>
        "??? /* Prism expects two type arguments: Prism[Outer, Variant] */"

  /** Lower `Focus[T](_.a.b)` to `Lens[T, _]((s: T) => s.a.b, (s: T, v) =>
   *  s.copy(a = s.a.copy(b = v)))`. `T` is taken from `Focus[T]` if present;
   *  otherwise the lambda's explicit param type is used; otherwise we emit
   *  an unannotated form (and rely on Scala 3 inference, which usually
   *  needs an outer type ascription to succeed). */
  /** A step in an optic path: a field-name select, an Option-unwrap
   *  (`.some`), or a collection traversal (`.each`). */
  private enum FocusStep:
    case Field(name: String)
    case SomeUnwrap
    case EachStep
    /** v0.9 — pointwise access into `List[A]`. */
    case IndexStep(i: Int)
    /** v0.9 — pointwise access into `Map[K, V]`.  `keyExpr` is a Scala
     *  source fragment for the key (so a String key emits as `"foo"`,
     *  an Int as `42`). */
    case AtKey(keyExpr: String)

  private def emitFocus(app: Term.Apply): String =
    val typeArg: Option[String] = app.fun match
      case ta: Term.ApplyType =>
        ta.argClause.values.headOption.map(_.syntax)
      case _ => None
    app.argClause.values match
      case List(lambda) =>
        val stepsAndExplicitTpe: Option[(List[FocusStep], Option[String])] = lambda match
          case Term.AnonymousFunction(body) =>
            extractPathSteps(body, _.isInstanceOf[Term.Placeholder]).map(_ -> None)
          case Term.Function.After_4_6_0(paramClause, body) =>
            paramClause.values.headOption.flatMap { p =>
              extractPathSteps(body, {
                case Term.Name(n) => n == p.name.value
                case _            => false
              }).map(_ -> p.decltpe.map(_.syntax))
            }
          case _ => None
        stepsAndExplicitTpe match
          case Some((steps, explicitTpe)) if steps.nonEmpty =>
            val tpe = typeArg.orElse(explicitTpe).getOrElse("Any")
            val hasPartial = steps.exists {
              case FocusStep.SomeUnwrap | _: FocusStep.IndexStep | _: FocusStep.AtKey => true
              case _                                                                    => false
            }
            if steps.exists(_ == FocusStep.EachStep) then
              buildTraversalLiteral(tpe, steps)
            else if hasPartial then
              buildOptionalLiteral(tpe, steps)
            else
              buildLensLiteral(tpe, steps.collect { case FocusStep.Field(n) => n })
          case _ =>
            "??? /* Focus: expected a field-access lambda like _.field.subfield */"
      case _ =>
        "??? /* Focus expects exactly one lambda argument */"

  private def extractPathSteps(body: Term, isBase: Term => Boolean): Option[List[FocusStep]] =
    def loop(t: Term, acc: List[FocusStep]): Option[List[FocusStep]] = t match
      case Term.Select(qual, Term.Name("some")) => loop(qual, FocusStep.SomeUnwrap :: acc)
      case Term.Select(qual, Term.Name("each")) => loop(qual, FocusStep.EachStep :: acc)
      // v0.9 pointwise — `_.users.index(3)` / `_.byId.at("u-42")`.
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("index")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case Lit.Int(i)  => loop(qual, FocusStep.IndexStep(i) :: acc)
          case Lit.Long(i) => loop(qual, FocusStep.IndexStep(i.toInt) :: acc)
          case _           => None
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("at")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case lit: Lit => loop(qual, FocusStep.AtKey(lit.syntax) :: acc)
          case _        => None
      case Term.Select(qual, name)              => loop(qual, FocusStep.Field(name.value) :: acc)
      case other if isBase(other)               => Some(acc)
      case _                                     => None
    loop(body, Nil)

  /** Render an optic path back into its source-like form for use in the
   *  optic's `toString` label (e.g. `Lens(_.a.b)`, `Optional(_.x.some.y)`). */
  private def pathLabel(prefix: String, steps: List[FocusStep]): String =
    val parts = steps.map {
      case FocusStep.Field(n)    => n
      case FocusStep.SomeUnwrap  => "some"
      case FocusStep.EachStep    => "each"
      case FocusStep.IndexStep(i)=> s"index($i)"
      // `keyExpr` is the Scala source for the key (e.g. `"u-42"` with
      // its quotes); the label is later embedded in a `"…"` literal so
      // we must escape the inner double-quotes.
      case FocusStep.AtKey(k)    => s"at(${k.replace("\"", "\\\"")})"
    }
    s"""$prefix(_.${parts.mkString(".")})"""

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
    val label  = pathLabel("Lens", path.map(FocusStep.Field(_)))
    s"""Lens($getter, $setter, "$label")"""

  /** Emit an Optional literal for a path containing `.some` steps. Get/set
   *  thread the value through `Option.flatMap` / `Option.map` and rebuild
   *  copies along the way; missing layers turn the whole operation into a
   *  no-op for set / modify, and `None` for getOption. */
  private def buildOptionalLiteral(tpe: String, steps: List[FocusStep]): String =
    val getter = s"(s: $tpe) => ${emitOpticGet(steps, "s", 0)}"
    val setter = s"(s: $tpe, v) => ${emitOpticSet(steps, "s", "v", 0)}"
    val label  = pathLabel("Optional", steps)
    s"""Optional($getter, $setter, "$label")"""

  private def isPartialStep(s: FocusStep): Boolean = s match
    case FocusStep.SomeUnwrap | _: FocusStep.IndexStep | _: FocusStep.AtKey => true
    case _                                                                    => false

  /** Build the `getOption` expression. Splits on the first partial step
   *  (Some / Index / At); if more partials follow we use `flatMap`,
   *  otherwise `map`. Field-only segments are emitted as chained
   *  `.f1.f2.…` accessors. */
  private def emitOpticGet(steps: List[FocusStep], in: String, counter: Int): String =
    val firstPartial = steps.indexWhere(isPartialStep)
    if firstPartial < 0 then
      val fields = steps.collect { case FocusStep.Field(n) => n }
      if fields.isEmpty then in else s"$in.${fields.mkString(".")}"
    else
      val prefixFields = steps.take(firstPartial).collect { case FocusStep.Field(n) => n }
      val splitStep   = steps(firstPartial)
      val suffix      = steps.drop(firstPartial + 1)
      val prefixExpr  = if prefixFields.isEmpty then in else s"$in.${prefixFields.mkString(".")}"
      val opticHead = splitStep match
        case FocusStep.SomeUnwrap   => prefixExpr
        case FocusStep.IndexStep(i) => s"$prefixExpr.lift($i)"
        case FocusStep.AtKey(k)     => s"$prefixExpr.get($k)"
        case _                      => prefixExpr
      if suffix.isEmpty then opticHead
      else
        val combinator = if suffix.exists(isPartialStep) then "flatMap" else "map"
        val v = s"_p$counter"
        s"$opticHead.$combinator($v => ${emitOpticGet(suffix, v, counter + 1)})"

  /** Build the `set` expression: nested `.copy(field = ...)` interleaved
   *  with `.map(p => …)` for Some, bounds-checked `.updated` for Index,
   *  unconditional `.updated` for At. */
  private def emitOpticSet(steps: List[FocusStep], target: String, valExpr: String, counter: Int): String = steps match
    case Nil                                  => valExpr
    case FocusStep.Field(n) :: Nil            => s"$target.copy($n = $valExpr)"
    case FocusStep.Field(n) :: rest           =>
      s"$target.copy($n = ${emitOpticSet(rest, s"$target.$n", valExpr, counter)})"
    case FocusStep.SomeUnwrap :: Nil          => s"$target.map(_ => $valExpr)"
    case FocusStep.SomeUnwrap :: rest         =>
      val v = s"_p$counter"
      s"$target.map($v => ${emitOpticSet(rest, v, valExpr, counter + 1)})"
    case FocusStep.IndexStep(i) :: Nil        =>
      s"(if ($i >= 0 && $i < $target.length) $target.updated($i, $valExpr) else $target)"
    case FocusStep.IndexStep(i) :: rest       =>
      val v = s"_p$counter"
      s"(if ($i >= 0 && $i < $target.length) $target.updated($i, { val $v = $target($i); ${emitOpticSet(rest, v, valExpr, counter + 1)} }) else $target)"
    case FocusStep.AtKey(k) :: Nil            =>
      s"$target.updated($k, $valExpr)"
    case FocusStep.AtKey(k) :: rest           =>
      val v = s"_p$counter"
      s"$target.get($k).map($v => $target.updated($k, ${emitOpticSet(rest, v, valExpr, counter + 1)})).getOrElse($target)"
    case FocusStep.EachStep :: _              =>
      // Only reached via a misuse of buildOptionalLiteral on a path that
      // ought to be a Traversal — keep behaviour consistent and stop.
      target

  /** Emit a Traversal literal for a path containing at least one `EachStep`.
   *  `toList` walks the path producing a flat `List[A]`; `modify` applies
   *  a function to every focus and rebuilds the structure. The second
   *  lambda's `f` parameter is left unannotated so Scala 3 can infer
   *  `A` from the leaf type that `toList` produces. */
  private def buildTraversalLiteral(tpe: String, steps: List[FocusStep]): String =
    val toListExpr = s"(s: $tpe) => ${emitTraversalGet(steps, "s", 0)}"
    val modifyExpr = s"(s: $tpe, _f) => ${emitTraversalModify(steps, "s", "_f", 0)}"
    val label      = pathLabel("Traversal", steps)
    s"""Traversal($toListExpr, $modifyExpr, "$label")"""

  /** Build the `toList` expression. Splits on the first `.some` or `.each`
   *  step; subsequent `.some` / `.each` chain via `List.flatMap`. */
  private def emitTraversalGet(steps: List[FocusStep], in: String, counter: Int): String =
    val firstSplit = steps.indexWhere {
      case FocusStep.SomeUnwrap | FocusStep.EachStep |
           _: FocusStep.IndexStep | _: FocusStep.AtKey => true
      case _                                            => false
    }
    if firstSplit < 0 then
      val fields = steps.collect { case FocusStep.Field(n) => n }
      val accessed = if fields.isEmpty then in else s"$in.${fields.mkString(".")}"
      s"List($accessed)"
    else
      val prefix = steps.take(firstSplit).collect { case FocusStep.Field(n) => n }
      val splitStep = steps(firstSplit)
      val suffix = steps.drop(firstSplit + 1)
      val prefixExpr = if prefix.isEmpty then in else s"$in.${prefix.mkString(".")}"
      val v = s"_p$counter"
      val recurExpr = emitTraversalGet(suffix, v, counter + 1)
      splitStep match
        case FocusStep.SomeUnwrap   => s"$prefixExpr.toList.flatMap($v => $recurExpr)"
        case FocusStep.EachStep     => s"$prefixExpr.flatMap($v => $recurExpr)"
        case FocusStep.IndexStep(i) => s"$prefixExpr.lift($i).toList.flatMap($v => $recurExpr)"
        case FocusStep.AtKey(k)     => s"$prefixExpr.get($k).toList.flatMap($v => $recurExpr)"
        case _                      => prefixExpr

  /** Build the `modify` expression: `.copy(field = …)` for FieldSteps,
   *  `.map(p => …)` for `.some` / `.each` steps, applying `f` at the leaf. */
  private def emitTraversalModify(steps: List[FocusStep], target: String, fName: String, counter: Int): String = steps match
    case Nil                                  => s"$fName($target)"
    case FocusStep.Field(n) :: Nil            => s"$target.copy($n = $fName($target.$n))"
    case FocusStep.Field(n) :: rest           =>
      s"$target.copy($n = ${emitTraversalModify(rest, s"$target.$n", fName, counter)})"
    case FocusStep.SomeUnwrap :: Nil          => s"$target.map($fName)"
    case FocusStep.SomeUnwrap :: rest         =>
      val v = s"_p$counter"
      s"$target.map($v => ${emitTraversalModify(rest, v, fName, counter + 1)})"
    case FocusStep.EachStep :: Nil            => s"$target.map($fName)"
    case FocusStep.EachStep :: rest           =>
      val v = s"_p$counter"
      s"$target.map($v => ${emitTraversalModify(rest, v, fName, counter + 1)})"
    case FocusStep.IndexStep(i) :: Nil        =>
      s"(if ($i >= 0 && $i < $target.length) $target.updated($i, $fName($target($i))) else $target)"
    case FocusStep.IndexStep(i) :: rest       =>
      val v = s"_p$counter"
      s"(if ($i >= 0 && $i < $target.length) $target.updated($i, { val $v = $target($i); ${emitTraversalModify(rest, v, fName, counter + 1)} }) else $target)"
    case FocusStep.AtKey(k) :: Nil            =>
      s"$target.get($k).map(_p => $target.updated($k, $fName(_p))).getOrElse($target)"
    case FocusStep.AtKey(k) :: rest           =>
      val v = s"_p$counter"
      s"$target.get($k).map($v => $target.updated($k, ${emitTraversalModify(rest, v, fName, counter + 1)})).getOrElse($target)"

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
    case ta: Term.ApplyType if isPrismApplyType(ta) =>
      emitPrism(ta)
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
      // v1.6 actors: `pid ! msg` always lowers to Actor.send.
      if op.value == "!" then s"Actor.send($l, $r)"
      else s"$l ${op.value} $r"
    case Term.Select(qual, name) =>
      s"${emitExpr(qual)}.${name.value}"
    case other => other.syntax

  /** Emit a Scala matcher closure for a `receive { case … }` block.
   *  Type: `(msg: Any) => Option[Any]` — `Some(bodyComputation)` on
   *  match, `None` on miss.  Case bodies are CPS-emitted so any nested
   *  Actor / Async / handle effects compose into the actor's pending
   *  Computation. */
  private def emitReceiveMatcher(cases: List[Case]): String =
    val sb = StringBuilder()
    // Emit as `_pfToFun({ case pat => Some(...); case _ => None })`.
    // `_pfToFun` is defined in the actor runtime and accepts a
    // `PartialFunction[Any, Option[Any]]`, returning a total
    // `Any => Option[Any]`.  This sidesteps Scala 3's
    // `(x: Any) => x match {…}` postfix-`match` precedence trap and
    // avoids needing in-line ascription that confuses the parser.
    sb.append("_pfToFun { ")
    cases.foreach { c =>
      sb.append("case ")
      sb.append(c.pat.syntax)
      c.cond.foreach { g => sb.append(" if "); sb.append(g.syntax) }
      sb.append(" => Some(")
      sb.append(emitCpsExpr(c.body))
      sb.append("); ")
    }
    sb.append("case _ => None }")
    sb.toString

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
      // Always paren-wrap: a single-param lambda with a type ascription
      // (`n: Any => body`) would be parsed as `n` of type `Any => body`,
      // not as a one-parameter lambda.  Parens disambiguate.
      val wrap = s"(${params.mkString(", ")})"
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

    // Nested runAsync inside CPS body — drive the inner Free tree to a
    // plain value, then continue the outer continuation with it.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsyncParallel(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val bodyJs = emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      val pathJs = bodyArgClause.values.lift(1)
        .map(p => emitExpr(p.asInstanceOf[Term]))
        .getOrElse("null")
      s"_runStorage(() => $bodyJs, $pathJs)"
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runStorage(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])}, null)"

    // ── v1.6 Actors Phase 1 (inside CPS body) ──────────────────────────
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runActors(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcher = emitReceiveMatcher(pf.cases)
          s"Actor.receive_t(_registerReceive($matcher), ${emitExpr(timeoutTerm.asInstanceOf[Term])})"
        case _ => "??? /* invalid receive */"

    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcher = emitReceiveMatcher(pf.cases)
          s"Actor.receive_(_registerReceive($matcher))"
        case _ => "??? /* invalid receive */"

    case Term.Apply.After_4_6_0(Term.Name("spawn"), argClause)
        if argClause.values.size == 1 =>
      // emitCpsExpr on a `() => …` Function literal already emits
      // `() => <cps-body>` — exactly the `() => Any` thunk `Actor.spawn`
      // expects.  Wrapping in another `() =>` would double-thunk.
      s"Actor.spawn(${emitCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("self"), argClause)
        if argClause.values.isEmpty =>
      "Actor.self()"
    case Term.Apply.After_4_6_0(Term.Name("exit"), argClause)
        if argClause.values.size == 2 =>
      val pidJs    = emitExpr(argClause.values(0).asInstanceOf[Term])
      val reasonJs = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.exit($pidJs, $reasonJs)"
    // v1.6 Phase 2 — supervision primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.link(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("monitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.monitor(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("demonitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.demonitor(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("trapExit"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.trapExit(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6 Phase 3 — distributed node primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("startNode"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.startNode(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.connectNode(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.connectNode(${emitExpr(argClause.values(0).asInstanceOf[Term])}, ${emitExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${emitExpr(argClause.values(0).asInstanceOf[Term])}, ${emitExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${emitExpr(argClause.values.head.asInstanceOf[Term])})"

    case app: Term.Apply => emitCpsApply(app)

    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val rhs = argClause.values.head
      bindArgsCps(List(lhs, rhs)) { case List(vl, vr) =>
        op.value match
          case "==" | "!="    => s"($vl ${op.value} $vr)"
          case "!"            => s"Actor.send($vl, $vr)"
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

      // Curried foldLeft: xs.foldLeft(init)(fn) — route through `_seqFoldLeft`
      // so an effectful `fn` is sequenced step by step instead of leaving a
      // Free tree at every accumulator step.
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldLeft")), initArgClause) =>
        bindArgsCps(qual :: initArgClause.values ++ args) { vs =>
          val q = vs.head; val init = vs(1); val f = vs(2)
          s"_seqFoldLeft($q.asInstanceOf[List[Any]], $init, $f.asInstanceOf[(Any, Any) => Any])"
        }

      // Method call: bind qual + args, then runtime-dispatch.  Inside CPS
      // every value is statically typed `Any`, so we can't let the Scala
      // typer resolve methods like `.map` directly — `_dispatch` does it
      // at runtime and threads Free results through `_seq*` helpers for
      // HOFs whose callbacks may produce a Free tree.
      case Term.Select(qual, Term.Name(method)) =>
        bindArgsCps(qual :: args) { vs =>
          val argList = vs.tail.mkString(", ")
          val argSeq  = if vs.tail.isEmpty then "Nil" else s"List($argList)"
          s"""_dispatch(${vs.head}, "${method}", $argSeq)"""
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
       |  case null      => "null"
       |  case d: Double => if d == d.toLong.toDouble then d.toLong.toString else d.toString
       |  case s: String => s
       |  // _Raw HTML nodes (from `raw(...)`, html"...", or DSL tag fns) render
       |  // as their inner string so `println(div(...))` prints the markup.
       |  case r: _Raw   => r.html
       |  // Render a Range like a List so xs.indices and similar lazy
       |  // iterables match the interpreter / JS output ("List(0, 1, 2)").
       |  case r: scala.collection.immutable.Range => r.toList.map(_show).mkString("List(", ", ", ")")
       |  // Match interpreter/JS rendering of Option, Map, List, Tuple, and
       |  // case-class instances — recursively `_show` children so a Double
       |  // inside Some(Circle(5.0)) still drops its trailing `.0`.
       |  case None       => "None"
       |  case Some(inner) => "Some(" + _show(inner) + ")"
       |  case m: Map[?, ?] =>
       |    if m.isEmpty then "Map()"
       |    else m.iterator.map((k, vv) => _show(k) + " -> " + _show(vv)).mkString("Map(", ", ", ")")
       |  case t: scala.Tuple =>
       |    "(" + t.productIterator.map(_show).mkString(", ") + ")"
       |  case xs: List[?] => xs.map(_show).mkString("List(", ", ", ")")
       |  // Optics carry a printable label as their last field; route through
       |  // toString so callers see `Lens(_.a.b)` instead of the function refs.
       |  case l: Lens[?, ?]      => l.toString
       |  case o: Optional[?, ?]  => o.toString
       |  case t: Traversal[?, ?] => t.toString
       |  case p: Prism[?, ?]     => p.toString
       |  case p: Product if p.productArity > 0 =>
       |    p.productPrefix + "(" + p.productIterator.map(_show).mkString(", ") + ")"
       |  case p: Product => p.productPrefix
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
       |/** `collectJs(comp1, comp2, ...)` — same shape as `collectCss`,
       | *  reads each argument's `val js: String` for a page <script>. */
       |def collectJs(parts: Any*): String =
       |  parts.flatMap { part =>
       |    try
       |      val m = part.getClass.getMethod("js")
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
       |case class Lens[S, A](get: S => A, set: (S, A) => S, _label: String = ""):
       |  override def toString: String = if _label.isEmpty then "Lens" else _label
       |  def modify(s: S, f: A => A): S = set(s, f(get(s)))
       |  def andThen[B](other: Lens[A, B]): Lens[S, B] =
       |    Lens(s => other.get(get(s)), (s, b) => set(s, other.set(get(s), b)))
       |  def andThen[B](other: Optional[A, B]): Optional[S, B] =
       |    Optional(s => other.getOption(get(s)), (s, b) => set(s, other.set(get(s), b)))
       |  def andThen[B](other: Traversal[A, B]): Traversal[S, B] =
       |    Traversal(
       |      s => other.toList(get(s)),
       |      (s, f) => set(s, other.modifyF(get(s), f))
       |    )
       |
       |// ── Prism runtime — sum-type optic, conditional get / set / modify ────
       |case class Prism[S, A](getOption: S => Option[A], reverseGet: A => S, _label: String = ""):
       |  override def toString: String = if _label.isEmpty then "Prism" else _label
       |  def set(s: S, a: A): S = getOption(s) match
       |    case Some(_) => reverseGet(a)
       |    case None    => s
       |  def modify(s: S, f: A => A): S = getOption(s) match
       |    case Some(a) => reverseGet(f(a))
       |    case None    => s
       |  def andThen[B](other: Prism[A, B]): Prism[S, B] =
       |    Prism(
       |      s => getOption(s).flatMap(other.getOption),
       |      b => reverseGet(other.reverseGet(b))
       |    )
       |
       |// ── Optional runtime — partial optic over a path with `.some` ─────
       |case class Optional[S, A](getOption: S => Option[A], set: (S, A) => S, _label: String = ""):
       |  override def toString: String = if _label.isEmpty then "Optional" else _label
       |  def modify(s: S, f: A => A): S = getOption(s) match
       |    case Some(a) => set(s, f(a))
       |    case None    => s
       |  def andThen[B](other: Optional[A, B]): Optional[S, B] =
       |    Optional(
       |      s => getOption(s).flatMap(other.getOption),
       |      (s, b) => getOption(s) match
       |        case Some(a) => set(s, other.set(a, b))
       |        case None    => s
       |    )
       |  def andThen[B](other: Lens[A, B]): Optional[S, B] =
       |    Optional(
       |      s => getOption(s).map(other.get),
       |      (s, b) => getOption(s) match
       |        case Some(a) => set(s, other.set(a, b))
       |        case None    => s
       |    )
       |  def andThen[B](other: Traversal[A, B]): Traversal[S, B] =
       |    Traversal(
       |      s => getOption(s).toList.flatMap(other.toList),
       |      (s, f) => getOption(s) match
       |        case Some(a) => set(s, other.modifyF(a, f))
       |        case None    => s
       |    )
       |
       |// ── Traversal runtime — multi-foci optic for `.each` paths ────────
       |case class Traversal[S, A](toList: S => List[A], modifyF: (S, A => A) => S, _label: String = ""):
       |  override def toString: String = if _label.isEmpty then "Traversal" else _label
       |  def getAll(s: S): List[A] = toList(s)
       |  def modify(s: S, f: A => A): S = modifyF(s, f)
       |  def set(s: S, a: A): S = modifyF(s, _ => a)
       |  def andThen[B](other: Traversal[A, B]): Traversal[S, B] =
       |    Traversal(
       |      s => toList(s).flatMap(other.toList),
       |      (s, f) => modifyF(s, a => other.modifyF(a, f))
       |    )
       |  def andThen[B](other: Lens[A, B]): Traversal[S, B] =
       |    Traversal(
       |      s => toList(s).map(other.get),
       |      (s, f) => modifyF(s, a => other.set(a, f(other.get(a))))
       |    )
       |  def andThen[B](other: Optional[A, B]): Traversal[S, B] =
       |    Traversal(
       |      s => toList(s).flatMap(a => other.getOption(a).toList),
       |      (s, f) => modifyF(s, a => other.modify(a, f))
       |    )
       |
       |// Environment variable reader — same surface on all three backends.
       |def getenv(key: String, defaultVal: String = ""): String =
       |  val v = java.lang.System.getenv(key)
       |  if v == null || v.isEmpty then defaultVal else v
       |
       |// ── Rate limiting ─────────────────────────────────────────────
       |// Fixed-window counter, process-local.  Returns true if allowed
       |// (and bumps the counter), false if `limit` requests already
       |// happened within `windowSeconds`.
       |private case class _RateBucket(count: java.util.concurrent.atomic.AtomicLong, windowStartMs: Long)
       |private val _rateLimitBuckets = new java.util.concurrent.ConcurrentHashMap[String, _RateBucket]()
       |def rateLimit(key: String, limit: Long, windowSeconds: Long): Boolean =
       |  val now      = java.lang.System.currentTimeMillis()
       |  val windowMs = windowSeconds * 1000L
       |  val current  = _rateLimitBuckets.get(key)
       |  if current == null || now - current.windowStartMs >= windowMs then
       |    _rateLimitBuckets.put(key, _RateBucket(java.util.concurrent.atomic.AtomicLong(1L), now))
       |    1L <= limit
       |  else current.count.incrementAndGet() <= limit
       |def rateLimitReset(key: String): Unit =
       |  _rateLimitBuckets.remove(key)
       |
       |// ── TOTP / 2FA (RFC 6238) ─────────────────────────────────────
       |// HMAC-SHA1, 30-second step, 6-digit code, base32 secret —
       |// compatible with Google Authenticator etc.
       |private val _totpAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
       |private val _totpDecodeTable: Array[Int] =
       |  val t = Array.fill(128)(-1)
       |  _totpAlphabet.zipWithIndex.foreach((c, i) => t(c.toInt) = i)
       |  t
       |private def _base32Encode(bytes: Array[Byte]): String =
       |  val sb = StringBuilder()
       |  var buf = 0L
       |  var bits = 0
       |  for b <- bytes do
       |    buf = (buf << 8) | (b & 0xffL)
       |    bits += 8
       |    while bits >= 5 do
       |      bits -= 5
       |      sb.append(_totpAlphabet.charAt(((buf >> bits) & 0x1f).toInt))
       |  if bits > 0 then sb.append(_totpAlphabet.charAt(((buf << (5 - bits)) & 0x1f).toInt))
       |  sb.toString
       |private def _base32Decode(s: String): Array[Byte] =
       |  val clean = s.toUpperCase.filter(c => c != '=' && c != ' ')
       |  val out   = scala.collection.mutable.ArrayBuffer.empty[Byte]
       |  var buf   = 0L
       |  var bits  = 0
       |  for c <- clean do
       |    val v = if c.toInt < 128 then _totpDecodeTable(c.toInt) else -1
       |    if v >= 0 then
       |      buf = (buf << 5) | v.toLong
       |      bits += 5
       |      if bits >= 8 then
       |        bits -= 8
       |        out += ((buf >> bits) & 0xff).toByte
       |  out.toArray
       |def totpSecret(): String =
       |  val bytes = new Array[Byte](20)
       |  java.security.SecureRandom().nextBytes(bytes)
       |  _base32Encode(bytes)
       |def totpUri(secret: String, account: String, issuer: String = ""): String =
       |  val labelIssuer = if issuer.isEmpty then "" else issuer + ":"
       |  val label = java.net.URLEncoder.encode(labelIssuer + account, "UTF-8").replace("+", "%20")
       |  val params = scala.collection.mutable.LinkedHashMap[String, String]()
       |  params("secret")    = secret
       |  params("algorithm") = "SHA1"
       |  params("digits")    = "6"
       |  params("period")    = "30"
       |  if issuer.nonEmpty then params("issuer") = issuer
       |  val qs = params.iterator.map((k, v) =>
       |    java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
       |  ).mkString("&")
       |  s"otpauth://totp/$label?$qs"
       |private def _totpCodeAt(secret: String, counter: Long): String =
       |  val key = _base32Decode(secret)
       |  val buf = new Array[Byte](8)
       |  var c = counter
       |  var i = 7
       |  while i >= 0 do
       |    buf(i) = (c & 0xff).toByte
       |    c >>>= 8
       |    i -= 1
       |  val mac = javax.crypto.Mac.getInstance("HmacSHA1")
       |  mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"))
       |  val h = mac.doFinal(buf)
       |  val off = h(h.length - 1) & 0x0f
       |  val bin = ((h(off)     & 0x7f) << 24) |
       |            ((h(off + 1) & 0xff) << 16) |
       |            ((h(off + 2) & 0xff) <<  8) |
       |             (h(off + 3) & 0xff)
       |  f"${bin % 1000000}%06d"
       |def totpCode(secret: String): String =
       |  _totpCodeAt(secret, java.lang.System.currentTimeMillis() / 1000L / 30L)
       |def totpValid(secret: String, code: String, skew: Int = 1): Boolean =
       |  if code == null || code.length != 6 || !code.forall(_.isDigit) then false
       |  else
       |    val now = java.lang.System.currentTimeMillis() / 1000L / 30L
       |    var i = -skew
       |    var ok = false
       |    while i <= skew do
       |      val expected = _totpCodeAt(secret, now + i)
       |      if expected.length == code.length then
       |        var diff = 0
       |        var j = 0
       |        while j < expected.length do
       |          diff |= expected.charAt(j) ^ code.charAt(j)
       |          j += 1
       |        if diff == 0 then ok = true
       |      i += 1
       |    ok
       |
       |// ── Password hashing (PBKDF2-HMAC-SHA256) ──────────────────────
       |// Same algorithm + encoded format as scalascript.server.Password
       |// so a hash minted on one backend verifies on another.  Lives in
       |// the base runtime (not gated on route usage) so non-server code
       |// can still hash passwords (e.g. seeding a user table).
       |def hashPassword(password: String, iter: Int = 200000): String =
       |  val salt = new Array[Byte](16)
       |  java.security.SecureRandom().nextBytes(salt)
       |  val key = _pbkdf2(password, salt, iter, 256)
       |  val b64 = java.util.Base64.getEncoder.withoutPadding
       |  s"pbkdf2$$iter=$iter$$${b64.encodeToString(salt)}$$${b64.encodeToString(key)}"
       |def verifyPassword(password: String, encoded: String): Boolean =
       |  try
       |    val parts = encoded.split('$')
       |    if parts.length != 4 || parts(0) != "pbkdf2" then false
       |    else
       |      val iter     = parts(1).stripPrefix("iter=").toInt
       |      val b64      = java.util.Base64.getDecoder
       |      val salt     = b64.decode(parts(2))
       |      val expected = b64.decode(parts(3))
       |      val actual   = _pbkdf2(password, salt, iter, expected.length * 8)
       |      java.security.MessageDigest.isEqual(expected, actual)
       |  catch case _: Throwable => false
       |private def _pbkdf2(password: String, salt: Array[Byte], iter: Int, bits: Int): Array[Byte] =
       |  val spec    = javax.crypto.spec.PBEKeySpec(password.toCharArray, salt, iter, bits)
       |  val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
       |  try factory.generateSecret(spec).getEncoded
       |  finally spec.clearPassword()
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
  /** Server-side runtime (routes, sessions, JWT, OAuth, WS, …).  Split
   *  into two `String` halves because the combined source exceeds the
   *  JVM's 64 KB string-literal limit — the natural seam is right
   *  before the WS-specific section. */
  // `lazy` to break the forward-reference to the two halves declared
  // below — a plain `val` initialises in source order, so both halves
  // are still null when this line runs and the emit gets garbage.
  private lazy val serveRuntime: String =
    serveRuntimePart1 + serveRuntimePart1b + serveRuntimePart2

  private val serveRuntimePart1: String =
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
       |  basicAuth:   Option[(String, String)]    = None,
       |  cookies:     Map[String, String]         = Map.empty
       |):
       |  /** Lenient JSON-read accessor — Some(parsed) on success, None
       |   *  on parse failure or empty body.  Same semantics as the
       |   *  interpreter / JS backends. */
       |  lazy val json: Option[Any] =
       |    if body.isEmpty then None
       |    else try Some(_fromJson(body)) catch case _: Throwable => None
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
       |  /** Attach (or overwrite) a header — used by std/middleware.ssc. */
       |  def withHeader(name: String, value: String): Response   = copy(headers = headers + (name -> value))
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
       |// Cookie security config — secure flag + SameSite policy, set
       |// via the top-level `cookieConfig(secure, sameSite)` call.
       |@volatile private var _cookieSecure: Boolean = false
       |@volatile private var _cookieSameSite: String = "Lax"
       |def cookieConfig(secure: Boolean, sameSite: String = "Lax"): Unit =
       |  _cookieSecure = secure
       |  _cookieSameSite = sameSite match
       |    case s @ ("Strict" | "Lax" | "None") => s
       |    case _                               => "Lax"
       |private def _buildSetCookie(payload: Map[String, String]): String =
       |  val base = s"Path=/; HttpOnly; SameSite=${_cookieSameSite}" +
       |    (if _cookieSecure then "; Secure" else "")
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
       |// ── JWT RS256 (asymmetric) ────────────────────────────────────
       |// Same wire format as scalascript.server.JwtRsa.  Reads keys
       |// from env (SSC_JWT_PRIVATE_KEY / SSC_JWT_PUBLIC_KEY, PEM).
       |private def _pemBytes(pem: String): Array[Byte] =
       |  val cleaned = pem
       |    .replaceAll("-----BEGIN [^-]+-----", "")
       |    .replaceAll("-----END [^-]+-----", "")
       |    .replaceAll("\\s+", "")
       |  java.util.Base64.getDecoder.decode(cleaned)
       |private lazy val _jwtRsaPrivate: Option[java.security.PrivateKey] =
       |  sys.env.get("SSC_JWT_PRIVATE_KEY").filter(_.nonEmpty).map { pem =>
       |    java.security.KeyFactory.getInstance("RSA")
       |      .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(_pemBytes(pem)))
       |  }
       |private lazy val _jwtRsaPublic: Option[java.security.PublicKey] =
       |  sys.env.get("SSC_JWT_PUBLIC_KEY").filter(_.nonEmpty).map { pem =>
       |    java.security.KeyFactory.getInstance("RSA")
       |      .generatePublic(java.security.spec.X509EncodedKeySpec(_pemBytes(pem)))
       |  }
       |private val _jwtRsaHeaderB64 = _b64urlEnc("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes("UTF-8"))
       |def jwtSignRsa(claims: Map[String, String]): String =
       |  val pk = _jwtRsaPrivate.getOrElse(
       |    throw RuntimeException("SSC_JWT_PRIVATE_KEY is not set (expected PKCS#8 RSA PEM)"))
       |  val payloadB64 = _b64urlEnc(_sessionJsonEnc(claims).getBytes("UTF-8"))
       |  val sig = java.security.Signature.getInstance("SHA256withRSA")
       |  sig.initSign(pk)
       |  sig.update((_jwtRsaHeaderB64 + "." + payloadB64).getBytes("UTF-8"))
       |  val sigB64 = _b64urlEnc(sig.sign())
       |  s"${_jwtRsaHeaderB64}.$payloadB64.$sigB64"
       |def jwtVerifyRsa(token: String): Option[Map[String, String]] =
       |  _jwtRsaPublic.flatMap { pub =>
       |    val parts = token.split('.')
       |    if parts.length != 3 then None
       |    else
       |      val h = parts(0); val p = parts(1); val s = parts(2)
       |      try
       |        val header = String(_b64urlDec(h), "UTF-8")
       |        if !header.contains("\"alg\":\"RS256\"") then None
       |        else
       |          val sig = java.security.Signature.getInstance("SHA256withRSA")
       |          sig.initVerify(pub)
       |          sig.update((h + "." + p).getBytes("UTF-8"))
       |          if !sig.verify(_b64urlDec(s)) then None
       |          else _sessionJsonDec(String(_b64urlDec(p), "UTF-8")) match
       |            case None => None
       |            case Some(claims) =>
       |              claims.get("exp") match
       |                case Some(expStr) =>
       |                  val now = java.lang.System.currentTimeMillis() / 1000L
       |                  try
       |                    if expStr.toLong < now then None else Some(claims)
       |                  catch case _: Throwable => None
       |                case None => Some(claims)
       |      catch case _: Throwable => None
       |  }
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
       |// JSON read side — hand-rolled recursive-descent parser.  Returns
       |// Map[String, Any] for objects, List[Any] for arrays, Long for
       |// integers, Double for fractional / exponent numbers, String for
       |// strings, Boolean for booleans, None for JSON null.  Mirrors the
       |// interpreter / JS backends bit-for-bit.
       |private class _JsonParseException(msg: String) extends RuntimeException(msg)
       |private def _fromJson(src: String): Any =
       |  val state = new _JsonParser(src)
       |  val v = state.parseValue()
       |  state.skipWs()
       |  if state.pos < src.length then
       |    throw _JsonParseException("jsonParse: trailing data at position " + state.pos)
       |  v
       |private class _JsonParser(src: String):
       |  var pos: Int = 0
       |  val len: Int = src.length
       |  private def fail(msg: String): Nothing =
       |    throw _JsonParseException("jsonParse: " + msg + " at position " + pos)
       |  def skipWs(): Unit =
       |    while pos < len && { val c = src.charAt(pos); c == ' ' || c == '\t' || c == '\n' || c == '\r' } do pos += 1
       |  private def expect(s: String): Unit =
       |    if pos + s.length > len || src.substring(pos, pos + s.length) != s then fail(s"expected '$s'")
       |    else pos += s.length
       |  private def parseString(): String =
       |    if pos >= len || src.charAt(pos) != '"' then fail("expected '\"'")
       |    pos += 1
       |    val sb = StringBuilder()
       |    var done = false
       |    while !done do
       |      if pos >= len then fail("unterminated string")
       |      src.charAt(pos) match
       |        case '"'  => pos += 1; done = true
       |        case '\\' =>
       |          pos += 1
       |          if pos >= len then fail("dangling escape")
       |          src.charAt(pos) match
       |            case '"'  => sb.append('"');  pos += 1
       |            case '\\' => sb.append('\\'); pos += 1
       |            case '/'  => sb.append('/');  pos += 1
       |            case 'n'  => sb.append('\n'); pos += 1
       |            case 'r'  => sb.append('\r'); pos += 1
       |            case 't'  => sb.append('\t'); pos += 1
       |            case 'b'  => sb.append('\b'); pos += 1
       |            case 'f'  => sb.append('\f'); pos += 1
       |            case 'u'  =>
       |              pos += 1
       |              if pos + 4 > len then fail("short unicode escape")
       |              val hex = src.substring(pos, pos + 4)
       |              try sb.append(Integer.parseInt(hex, 16).toChar)
       |              catch case _: NumberFormatException => fail("bad unicode escape")
       |              pos += 4
       |            case c    => fail(s"bad escape '\\$c'")
       |        case c    => sb.append(c); pos += 1
       |    sb.toString
       |  private def parseNumber(): Any =
       |    val start = pos
       |    if pos < len && src.charAt(pos) == '-' then pos += 1
       |    while pos < len && { val c = src.charAt(pos); c >= '0' && c <= '9' } do pos += 1
       |    var isDouble = false
       |    if pos < len && src.charAt(pos) == '.' then
       |      isDouble = true
       |      pos += 1
       |      while pos < len && { val c = src.charAt(pos); c >= '0' && c <= '9' } do pos += 1
       |    if pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E') then
       |      isDouble = true
       |      pos += 1
       |      if pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-') then pos += 1
       |      while pos < len && { val c = src.charAt(pos); c >= '0' && c <= '9' } do pos += 1
       |    val s = src.substring(start, pos)
       |    if isDouble then s.toDouble
       |    else try s.toLong catch case _: NumberFormatException => s.toDouble
       |  def parseValue(): Any =
       |    skipWs()
       |    if pos >= len then fail("unexpected end of input")
       |    src.charAt(pos) match
       |      case '"' => parseString()
       |      case 't' => expect("true");  true
       |      case 'f' => expect("false"); false
       |      case 'n' => expect("null");  None
       |      case '[' =>
       |        pos += 1; skipWs()
       |        val items = scala.collection.mutable.ListBuffer.empty[Any]
       |        if pos < len && src.charAt(pos) == ']' then pos += 1
       |        else
       |          var done = false
       |          while !done do
       |            items += parseValue()
       |            skipWs()
       |            if pos >= len then fail("unterminated array")
       |            src.charAt(pos) match
       |              case ',' => pos += 1; skipWs()
       |              case ']' => pos += 1; done = true
       |              case c   => fail(s"expected ',' or ']', got '$c'")
       |        items.toList
       |      case '{' =>
       |        pos += 1; skipWs()
       |        val entries = scala.collection.mutable.ListBuffer.empty[(String, Any)]
       |        if pos < len && src.charAt(pos) == '}' then pos += 1
       |        else
       |          var done = false
       |          while !done do
       |            skipWs()
       |            val k = parseString()
       |            skipWs()
       |            if pos >= len || src.charAt(pos) != ':' then fail("expected ':'")
       |            pos += 1
       |            val v = parseValue()
       |            entries += (k -> v)
       |            skipWs()
       |            if pos >= len then fail("unterminated object")
       |            src.charAt(pos) match
       |              case ',' => pos += 1
       |              case '}' => pos += 1; done = true
       |              case c   => fail(s"expected ',' or '}', got '$c'")
       |        entries.toMap
       |      case c if c == '-' || (c >= '0' && c <= '9') => parseNumber()
       |      case c   => fail(s"unexpected character '$c'")
       |
       |def jsonParse(s: String): Any        = _fromJson(s)
       |def jsonStringify(v: Any): String    = _toJsonValue(v)
       |""".stripMargin

  // Continuation of part1 — split out so each string stays under the
  // JVM's 65 535-byte constant-pool limit.
  private val serveRuntimePart1b: String =
    """|
       |// v1.5 Tier 5 #22 option (c) — `JsonValue` wrapper.  `jsonRead(s)`
       |// returns a `JsonValue` that supports idiomatic apply / get /
       |// typed-accessor methods.  Stored as a Scala class so
       |// `v("k")(i).asString` resolves cleanly through the Scala typer.
       |class JsonValue(val raw: Any):
       |  def apply(k: String): JsonValue = raw match
       |    case m: scala.collection.Map[?, ?] =>
       |      m.asInstanceOf[scala.collection.Map[Any, Any]].get(k) match
       |        case Some(v) => new JsonValue(v)
       |        case None    => throw new RuntimeException("JsonValue: no key '" + k + "'")
       |    case _ => throw new RuntimeException("JsonValue.apply('" + k + "'): not an object")
       |  def apply(i: Int): JsonValue = raw match
       |    case xs: scala.collection.Seq[?] =>
       |      if i >= 0 && i < xs.length then new JsonValue(xs(i))
       |      else throw new RuntimeException("JsonValue: index " + i + " out of bounds (size=" + xs.length + ")")
       |    case _ => throw new RuntimeException("JsonValue.apply(" + i + "): not an array")
       |  def get(k: String): Option[JsonValue] = raw match
       |    case m: scala.collection.Map[?, ?] =>
       |      m.asInstanceOf[scala.collection.Map[Any, Any]].get(k).map(new JsonValue(_))
       |    case _ => None
       |  def get(i: Int): Option[JsonValue] = raw match
       |    case xs: scala.collection.Seq[?] if i >= 0 && i < xs.length =>
       |      Some(new JsonValue(xs(i)))
       |    case _ => None
       |  def asString: String = raw match
       |    case s: String => s
       |    case other     => throw new RuntimeException("JsonValue.asString: expected string but got " + _show(other))
       |  def asInt: Long = raw match
       |    case n: Long   => n
       |    case n: Int    => n.toLong
       |    case n: Double => n.toLong
       |    case other     => throw new RuntimeException("JsonValue.asInt: expected int but got " + _show(other))
       |  def asLong: Long = asInt
       |  def asDouble: Double = raw match
       |    case n: Double => n
       |    case n: Long   => n.toDouble
       |    case n: Int    => n.toDouble
       |    case other     => throw new RuntimeException("JsonValue.asDouble: expected double but got " + _show(other))
       |  def asBool: Boolean = raw match
       |    case b: Boolean => b
       |    case other      => throw new RuntimeException("JsonValue.asBool: expected bool but got " + _show(other))
       |  def asList: List[JsonValue] = raw match
       |    case xs: scala.collection.Seq[?] => xs.toList.map(x => new JsonValue(x))
       |    case other => throw new RuntimeException("JsonValue.asList: expected list but got " + _show(other))
       |  def asMap: scala.collection.Map[Any, JsonValue] = raw match
       |    case m: scala.collection.Map[?, ?] =>
       |      m.asInstanceOf[scala.collection.Map[Any, Any]]
       |        .map { case (k, v) => k -> new JsonValue(v) }
       |    case other => throw new RuntimeException("JsonValue.asMap: expected map but got " + _show(other))
       |  def isNull: Boolean = raw == null
       |  def keys: List[Any] = raw match
       |    case m: scala.collection.Map[?, ?] =>
       |      m.asInstanceOf[scala.collection.Map[Any, Any]].keys.toList
       |    case _ => Nil
       |  def size: Long = raw match
       |    case xs: scala.collection.Seq[?]   => xs.length.toLong
       |    case m: scala.collection.Map[?, ?] => m.size.toLong
       |    case s: String                      => s.length.toLong
       |    case _                              => 0L
       |  override def toString: String = _show(raw)
       |def jsonRead(s: String): JsonValue = new JsonValue(_fromJson(s))
       |
       |// v1.5 Tier 5 #22 — indexed access on `Any`-typed JSON values.
       |// `jsonParse` returns `Any`; the Scala typer rejects `obj("name")`
       |// on `Any` because `Any` has no `apply`.  `lookup` / `lookupOpt`
       |// dispatch dynamically at runtime so the same source compiles on
       |// all three backends.  `lookup` throws on a missing key
       |// (matches Map.apply); `lookupOpt` returns `Option`.
       |private def _lookupKey(v: Any, k: Any): Option[Any] = v match
       |  case m: scala.collection.Map[?, ?] => m.asInstanceOf[scala.collection.Map[Any, Any]].get(k)
       |  case xs: scala.collection.Seq[?]   => k match
       |    case i: Int  => if i >= 0 && i < xs.length then Some(xs(i)) else None
       |    case i: Long => val ii = i.toInt; if ii >= 0 && ii < xs.length then Some(xs(ii)) else None
       |    case _       => None
       |  case s: String => k match
       |    case i: Int  => if i >= 0 && i < s.length then Some(s.charAt(i).toString) else None
       |    case i: Long => val ii = i.toInt; if ii >= 0 && ii < s.length then Some(s.charAt(ii).toString) else None
       |    case _       => None
       |  case _ => None
       |def lookup(v: Any, k: Any): Any = _lookupKey(v, k) match
       |  case Some(x) => x
       |  case None    => throw new RuntimeException("lookup: key " + _show(k) + " not found in " + _show(v))
       |def lookupOpt(v: Any, k: Any): Option[Any] = _lookupKey(v, k)
       |
       |// Tier 5 #20 — typed request validation primitives.  `requireX`
       |// throws a `_RestValidationError` on missing/invalid input that
       |// the route dispatcher catches and turns into a 400 Bad Request.
       |//
       |// `validate { body }` flips a thread-local flag so `require*`
       |// records the error and returns a safe default instead of
       |// throwing — the body keeps running and accumulates every
       |// problem in one pass, returning Right(value) / Left(map).
       |//
       |// The argument is typed `Any` (not the bundled `Request` class)
       |// so unit tests can pass any case class with `form` / `query`
       |// maps; matches the dynamic semantics on the other two backends.
       |final class _RestValidationError(msg: String) extends RuntimeException(msg)
       |val _validationStack = new java.util.concurrent.atomic.AtomicReference[List[scala.collection.mutable.LinkedHashMap[String, String]]](Nil)
       |private def _recordOrThrow(name: String, msg: String, default: Any): Any =
       |  val cur = _validationStack.get()
       |  cur.headOption match
       |    case Some(buf) =>
       |      buf.put(name, msg); default
       |    case None =>
       |      throw new _RestValidationError(msg)
       |private def _restFieldOf(req: Any, name: String): Option[String] =
       |  def look(field: String): Option[String] = req match
       |    case r: Request => field match
       |      case "form"  => r.form.get(name)
       |      case "query" => r.query.get(name)
       |      case _       => None
       |    case _ =>
       |      try
       |        val cls = req.getClass
       |        val f = cls.getMethod(field)
       |        f.invoke(req) match
       |          case m: scala.collection.Map[?, ?] =>
       |            m.asInstanceOf[scala.collection.Map[String, String]].get(name)
       |          case _ => None
       |      catch case _: Throwable => None
       |  look("form").orElse(look("query"))
       |def requireString(req: Any, name: String): String =
       |  _restFieldOf(req, name) match
       |    case Some(s) => s
       |    case None    => _recordOrThrow(name, s"missing field: $name", "").asInstanceOf[String]
       |def optionalString(req: Any, name: String): Option[String] =
       |  _restFieldOf(req, name)
       |def requireInt(req: Any, name: String): Long =
       |  _restFieldOf(req, name) match
       |    case Some(s) =>
       |      try s.trim.toLong
       |      catch case _: NumberFormatException =>
       |        _recordOrThrow(name, s"invalid integer for field: $name", 0L) match
       |          case n: Long => n
       |          case n: Int  => n.toLong
       |          case _       => 0L
       |    case None => _recordOrThrow(name, s"missing field: $name", 0L) match
       |      case n: Long => n
       |      case n: Int  => n.toLong
       |      case _       => 0L
       |def optionalInt(req: Any, name: String): Option[Long] =
       |  _restFieldOf(req, name).flatMap(s =>
       |    try Some(s.trim.toLong) catch case _: NumberFormatException => None)
       |def requireDouble(req: Any, name: String): Double =
       |  _restFieldOf(req, name) match
       |    case Some(s) =>
       |      try s.trim.toDouble
       |      catch case _: NumberFormatException =>
       |        _recordOrThrow(name, s"invalid number for field: $name", 0.0).asInstanceOf[Double]
       |    case None => _recordOrThrow(name, s"missing field: $name", 0.0).asInstanceOf[Double]
       |def optionalDouble(req: Any, name: String): Option[Double] =
       |  _restFieldOf(req, name).flatMap(s =>
       |    try Some(s.trim.toDouble) catch case _: NumberFormatException => None)
       |def requireBool(req: Any, name: String): Boolean =
       |  _restFieldOf(req, name) match
       |    case Some(s) => s.trim.toLowerCase match
       |      case "true"  | "1" | "yes" | "on"  => true
       |      case "false" | "0" | "no"  | "off" => false
       |      case _ => _recordOrThrow(name, s"invalid boolean for field: $name", false).asInstanceOf[Boolean]
       |    case None => _recordOrThrow(name, s"missing field: $name", false).asInstanceOf[Boolean]
       |def optionalBool(req: Any, name: String): Option[Boolean] =
       |  _restFieldOf(req, name).flatMap(s => s.trim.toLowerCase match
       |    case "true"  | "1" | "yes" | "on"  => Some(true)
       |    case "false" | "0" | "no"  | "off" => Some(false)
       |    case _ => None)
       |def requireRange(req: Any, name: String, min: Long, max: Long): Long =
       |  _restFieldOf(req, name) match
       |    case Some(s) =>
       |      try
       |        val n = s.trim.toLong
       |        if n < min || n > max then
       |          _recordOrThrow(name, s"out of range [$min..$max] for field: $name", min) match
       |            case x: Long => x; case x: Int => x.toLong; case _ => min
       |        else n
       |      catch case _: NumberFormatException =>
       |        _recordOrThrow(name, s"invalid integer for field: $name", min) match
       |          case x: Long => x; case x: Int => x.toLong; case _ => min
       |    case None => _recordOrThrow(name, s"missing field: $name", min) match
       |      case x: Long => x; case x: Int => x.toLong; case _ => min
       |def requireRangeDouble(req: Any, name: String, min: Double, max: Double): Double =
       |  _restFieldOf(req, name) match
       |    case Some(s) =>
       |      try
       |        val n = s.trim.toDouble
       |        if n < min || n > max then
       |          _recordOrThrow(name, s"out of range [$min..$max] for field: $name", min).asInstanceOf[Double]
       |        else n
       |      catch case _: NumberFormatException =>
       |        _recordOrThrow(name, s"invalid number for field: $name", min).asInstanceOf[Double]
       |    case None =>
       |      _recordOrThrow(name, s"missing field: $name", min).asInstanceOf[Double]
       |def requireOneOf(req: Any, name: String, options: List[String]): String =
       |  val fallback = options.headOption.getOrElse("")
       |  _restFieldOf(req, name) match
       |    case Some(s) if options.contains(s) => s
       |    case Some(s) =>
       |      _recordOrThrow(name,
       |        s"invalid value '$s' for field: $name (expected one of: ${options.mkString(", ")})",
       |        fallback).asInstanceOf[String]
       |    case None =>
       |      _recordOrThrow(name, s"missing field: $name", fallback).asInstanceOf[String]
       |
       |/** v1.5 Tier 5 #20 — accumulating-error block.  Runs `body` with an
       | *  active collector, returns Right(result) on success or Left(map)
       | *  carrying every error in insertion order. */
       |def validate[A](body: => A): Any =
       |  val buf = scala.collection.mutable.LinkedHashMap.empty[String, String]
       |  _validationStack.updateAndGet(buf :: _)
       |  try
       |    val result = body
       |    if buf.nonEmpty then Left(buf.toMap) else Right(result)
       |  finally
       |    _validationStack.updateAndGet(_.tail)
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
       |private case class _Route(method: String, path: String, pattern: List[_Seg], handler: Request => Response)
       |private val _routes = scala.collection.mutable.ArrayBuffer.empty[_Route]
       |
       |def route(method: String, path: String)(handler: Request => Response): Unit =
       |  _routes += _Route(method.toUpperCase, path, _parsePath(path), handler)
       |
       |// Tier 5 #21 — `/_health` and `/_ready` defaults auto-registered the
       |// first time `serve(...)` runs.  User-defined routes with the same
       |// path keep precedence.
       |private def _registerHealthDefaults(): Unit =
       |  def has(p: String): Boolean = _routes.exists(r => r.method == "GET" && r.path == p)
       |  val ok: Request => Response = _ =>
       |    Response(200, Map("Content-Type" -> "application/json"), "{\"status\":\"ok\"}")
       |  if !has("/_health") then _routes += _Route("GET", "/_health", _parsePath("/_health"), ok)
       |  if !has("/_ready")  then _routes += _Route("GET", "/_ready",  _parsePath("/_ready"),  ok)
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
       |// ── Body size limit ───────────────────────────────────────────────────
       |@volatile private var _maxBodySizeBytes: Long = Long.MaxValue
       |def maxBodySize(n: Int): Unit = _maxBodySizeBytes = n.toLong
       |private class _BodyTooLarge extends Exception("Request Entity Too Large")
       |
       |// ── Streaming response sentinel ────────────────────────────────────────
       |case class _StreamResponse(
       |  status:  Int,
       |  headers: Map[String, String],
       |  writer:  (String => Unit) => Any
       |)
       |def streamResponse(status: Int = 200, headers: Map[String, String] = Map.empty)(block: (String => Unit) => Any): _StreamResponse =
       |  _StreamResponse(status, headers, block)
       |
       |// ── Server-Sent Events ────────────────────────────────────────────────
       |private val _sseHeaders: Map[String, String] = Map(
       |  "Content-Type"      -> "text/event-stream",
       |  "Cache-Control"     -> "no-cache",
       |  "Connection"        -> "keep-alive",
       |  "X-Accel-Buffering" -> "no"
       |)
       |case class _SseStream(private val _write: String => Unit):
       |  def send(data: String): Unit = _write(s"data: $data\n\n")
       |  def send(event: String, data: String): Unit = _write(s"event: $event\ndata: $data\n\n")
       |  def close(): Unit = ()
       |
       |def sse(req: Any)(block: _SseStream => Any): _StreamResponse =
       |  streamResponse(200, _sseHeaders) { write =>
       |    block(_SseStream(write))
       |  }
       |
       |// ── CORS / gzip / cache config ────────────────────────────────────────
       |@volatile private var _corsOrigins: List[String] = Nil
       |@volatile private var _corsMethods: List[String] = Nil
       |@volatile private var _corsHeaders: List[String] = Nil
       |@volatile private var _gzipEnabled = false
       |
       |def cors(origins: List[String], methods: List[String] = List("GET","POST","PUT","DELETE","OPTIONS","PATCH"), headers: List[String] = Nil): Unit =
       |  _corsOrigins = origins; _corsMethods = methods; _corsHeaders = headers
       |
       |def useGzip(): Unit = _gzipEnabled = true
       |
       |def cacheable(r: Response, maxAge: Long, etag: String = ""): Response =
       |  val cc = s"public, max-age=$maxAge"
       |  val h0 = r.headers + ("Cache-Control" -> cc)
       |  val h1 = if etag.nonEmpty then h0 + ("ETag" -> etag) else h0
       |  r.copy(headers = h1)
       |
       |def noCache(r: Response): Response =
       |  r.copy(headers = r.headers + ("Cache-Control" -> "no-store, no-cache, must-revalidate"))
       |
       |private def _applyCors(ex: com.sun.net.httpserver.HttpExchange): Unit =
       |  if _corsOrigins.nonEmpty then
       |    val origin  = Option(ex.getRequestHeaders.getFirst("Origin")).getOrElse("")
       |    val allowed = if _corsOrigins.contains("*") then "*"
       |                  else if _corsOrigins.contains(origin) then origin else ""
       |    if allowed.nonEmpty then
       |      ex.getResponseHeaders.add("Access-Control-Allow-Origin", allowed)
       |      if _corsMethods.nonEmpty then
       |        ex.getResponseHeaders.add("Access-Control-Allow-Methods", _corsMethods.mkString(", "))
       |      if _corsHeaders.nonEmpty then
       |        ex.getResponseHeaders.add("Access-Control-Allow-Headers", _corsHeaders.mkString(", "))
       |      ex.getResponseHeaders.add("Vary", "Origin")
       |
       |private def _handle(ex: com.sun.net.httpserver.HttpExchange): Unit =
       |  _Metrics.httpRequests.incrementAndGet()
       |  val _accessStartNs = java.lang.System.nanoTime()
       |  val _accessMethod  = ex.getRequestMethod
       |  val _accessPath    = ex.getRequestURI.getPath
       |  val _accessIp      = try ex.getRemoteAddress.getAddress.getHostAddress catch case _: Throwable => "?"
       |  val _accessUa      = try ex.getRequestHeaders.getFirst("User-Agent") catch case _: Throwable => ""
       |  try
       |    val method = ex.getRequestMethod.toUpperCase
       |    val path   = ex.getRequestURI.getPath
       |    val segs   = path.split('/').toList.filter(_.nonEmpty)
       |    if method == "OPTIONS" && _corsOrigins.nonEmpty then
       |      _applyCors(ex)
       |      ex.sendResponseHeaders(204, -1)
       |    else
       |    val matched = _routes.iterator
       |      .filter(_.method == method)
       |      .flatMap(r => _matchPath(r.pattern, segs).map(p => (r, p)))
       |      .nextOption()
       |    matched match
       |      case Some((r, params)) =>
       |        import scala.jdk.CollectionConverters.*
       |        // Lowercase keys for portable lookup — matches Node's
       |        // `req.headers` and the WS handshake convention.
       |        val headers = ex.getRequestHeaders.entrySet.iterator.asScala.flatMap { e =>
       |          if e.getValue.isEmpty then None
       |          else Some(e.getKey.toLowerCase -> e.getValue.get(0))
       |        }.toMap
       |        // Body size guard — reject before buffering when Content-Length is known.
       |        val _clHdr = try Option(ex.getRequestHeaders.getFirst("Content-Length")).map(_.toLong).getOrElse(0L) catch case _: Throwable => 0L
       |        if _clHdr > _maxBodySizeBytes then throw _BodyTooLarge()
       |        // Read body as bytes so multipart file parts round-trip byte-exact.
       |        // `body` is the UTF-8 view (back-compat); `bodyLatin1` is a
       |        // byte-equivalent String for multipart parsing.
       |        val bodyBytes  = ex.getRequestBody.readAllBytes()
       |        if bodyBytes.length.toLong > _maxBodySizeBytes then throw _BodyTooLarge()
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
       |        // Generic cookie map for handler convenience (parallels
       |        // the WS-side `ws.request.cookies`).  Separate from the
       |        // signed `session` map above.
       |        val cookies: Map[String, String] =
       |          if cookieHeader.isEmpty then Map.empty
       |          else cookieHeader.split(';').iterator.flatMap { pair =>
       |            val t = pair.trim
       |            val i = t.indexOf('=')
       |            if i < 0 then None else Some(t.substring(0, i).trim -> t.substring(i + 1).trim)
       |          }.toMap
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
       |          form, files, session, bearer, claims, basicAuth, cookies)
       |        // Tier 5 #20 — validation primitives short-circuit by
       |        // throwing _RestValidationError; convert to 400.
       |        val _rawResult =
       |          try r.handler(req)
       |          catch case ve: _RestValidationError =>
       |            Response(400,
       |              Map("Content-Type" -> "text/plain; charset=utf-8"),
       |              ve.getMessage)
       |        _rawResult match
       |          case sr: _StreamResponse =>
       |            sr.headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
       |            if !sr.headers.contains("Content-Type") then
       |              ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
       |            _applyCors(ex)
       |            ex.sendResponseHeaders(sr.status, 0)
       |            val _out = ex.getResponseBody
       |            try sr.writer { chunk =>
       |              val _b = chunk.getBytes("UTF-8"); _out.write(_b); _out.flush()
       |            } finally _out.close()
       |          case resp: Response =>
       |            _writeResponse(ex, resp, rawCookieSession)
       |          case other =>
       |            _writeResponse(ex, Response(200, Map("Content-Type" -> "text/plain; charset=utf-8"), String.valueOf(other)), rawCookieSession)
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
       |  catch
       |    case _: _BodyTooLarge =>
       |      val _m = "Request Entity Too Large".getBytes("UTF-8")
       |      ex.sendResponseHeaders(413, _m.length.toLong)
       |      ex.getResponseBody.write(_m)
       |    case e: Exception =>
       |      System.err.println(s"route error: ${e.getMessage}")
       |      _Metrics.http5xx.incrementAndGet()
       |  finally
       |    val code = try ex.getResponseCode catch case _: Throwable => -1
       |    if code >= 400 && code < 500 then _Metrics.http4xx.incrementAndGet()
       |    else if code >= 500           then _Metrics.http5xx.incrementAndGet()
       |    val _durMs   = (java.lang.System.nanoTime() - _accessStartNs) / 1_000_000L
       |    val _effCode = if code < 0 then 0 else code
       |    val _uaSan   = if _accessUa == null then "" else _accessUa.replace('"', '\'')
       |    println("http\tip="           + _accessIp +
       |            "\tmethod="           + _accessMethod +
       |            "\tpath="             + _accessPath +
       |            "\tstatus="           + _effCode +
       |            "\tduration_ms="      + _durMs +
       |            "\tua=\""             + _uaSan + "\"")
       |    ex.close()
       |
       |private def _writeResponse(
       |    ex:               com.sun.net.httpserver.HttpExchange,
       |    r:                Response,
       |    rawCookieSession: Map[String, String] = Map.empty
       |): Unit =
       |  r.headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
       |  if !r.headers.contains("Content-Type") then
       |    ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
       |  _applyCors(ex)
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
       |  val _responseEtag   = r.headers.getOrElse("ETag", r.headers.getOrElse("etag", ""))
       |  val _ifNoneMatch    = Option(ex.getRequestHeaders.getFirst("If-None-Match")).getOrElse("")
       |  val _etagUnquoted   = _ifNoneMatch.stripPrefix("\"").stripSuffix("\"")
       |  if _responseEtag.nonEmpty && _ifNoneMatch.nonEmpty &&
       |     (_responseEtag == _ifNoneMatch || _responseEtag == _etagUnquoted) then
       |    ex.sendResponseHeaders(304, -1L)
       |  else
       |    val _rawBytes    = r.body.getBytes("UTF-8")
       |    val _acceptGzip  = Option(ex.getRequestHeaders.getFirst("Accept-Encoding")).getOrElse("").contains("gzip")
       |    val _contentType = Option(ex.getResponseHeaders.getFirst("Content-Type")).getOrElse("")
       |    val _compress    = _gzipEnabled && _acceptGzip && _rawBytes.nonEmpty &&
       |                       (_contentType.startsWith("text/") || _contentType.contains("json") || _contentType.contains("javascript"))
       |    val bytes =
       |      if _compress then
       |        val baos = new java.io.ByteArrayOutputStream()
       |        val gz   = new java.util.zip.GZIPOutputStream(baos)
       |        gz.write(_rawBytes); gz.finish()
       |        ex.getResponseHeaders.add("Content-Encoding", "gzip")
       |        baos.toByteArray
       |      else _rawBytes
       |    ex.sendResponseHeaders(r.status, if bytes.isEmpty then -1L else bytes.length.toLong)
       |    if bytes.nonEmpty then ex.getResponseBody.write(bytes)
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
       |""".stripMargin

  private val serveRuntimePart2: String =
    """|
       |// ── WebSocket support (RFC 6455) ───────────────────────────────────────
       |//
       |// Asymmetric design vs the interpreter: the interpreter runs an NIO
       |// selector loop in front of the JDK `HttpServer`; here we use a
       |// blocking-IO proxy with **one virtual thread per connection**
       |// (Project Loom, JDK 21+).  A parked virtual thread on a slow read
       |// costs ~few KB of heap rather than a 1 MB platform-thread stack,
       |// so the scale ceiling is now file descriptors, not threads.
       |// The user-facing `onWebSocket(path) { ws => … }` API is identical
       |// to the interpreter, so .ssc code is portable.  Full NIO migration
       |// will land alongside the HTTP NIO rewrite.
       |//
       |// Threading: user callbacks (`onWebSocket` body, `onMessage`,
       |// `onClose`) all dispatch through `_serverExecutor`, a single-
       |// platform-thread executor that also backs the internal HttpServer.
       |// That way mutations to top-level `var`s from HTTP handlers and WS
       |// callbacks are serial — no cross-handler races even though every
       |// WS read-loop runs on its own virtual thread.
       |//
       |// `synchronized` on the WebSocket write path is avoided in favour of
       |// `ReentrantLock`: on JDK 21 a `synchronized` block pins the carrier
       |// thread of any virtual thread that enters it.  (Pinning was removed
       |// in JDK 24; we keep the lock for portability.)
       |
       |private val _serverExecutor: java.util.concurrent.ExecutorService =
       |  java.util.concurrent.Executors.newSingleThreadExecutor()
       |
       |// Shared scheduler driving the periodic Ping heartbeat across every
       |// active WebSocket — single daemon thread, cheap.
       |private val _wsHeartbeats: java.util.concurrent.ScheduledExecutorService =
       |  java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r => {
       |    val t = Thread(r, "ws-heartbeats"); t.setDaemon(true); t
       |  })
       |
       |class WebSocket(
       |    private val socket: java.net.Socket,
       |    val request: Request,
       |    /** The subprotocol the server selected during upgrade
       |     *  negotiation (RFC 6455 §1.9), or "" when no negotiation
       |     *  took place.  `ws.request.headers("sec-websocket-protocol")`
       |     *  still carries the client's full offer list. */
       |    val subprotocol: String = "",
       |    /** Per-route cleanup callback fired exactly once when this
       |     *  connection terminates.  Used to decrement the route's
       |     *  active-connection counter so the per-route cap recovers. */
       |    private val _onTerminate: () => Unit = () => (),
       |    /** Cap on inbound messages per second on this connection.
       |     *  0 = unlimited.  Overrun closes with code 1008.  See
       |     *  the fixed-window counter in `_dispatchWsMessage`. */
       |    private val _maxMessagesPerSec: Int = 0,
       |    /** Payload returned by the route's auth hook (or None for
       |     *  routes without one).  Surfaced to handlers as
       |     *  `ws.user` so authenticated routes don't re-parse
       |     *  headers / claims / sessions inside the body. */
       |    val user: Option[Any] = None):
       |  /** Stable per-connection identifier.  UUID-v4 generated at
       |   *  upgrade time; surfaced to user code as `ws.id` and used to
       |   *  tag every log line for a single session. */
       |  val id: String = java.util.UUID.randomUUID().toString
       |  /** Wall-clock time of upgrade — feeds `duration_ms` into the
       |   *  Sprint-4 close log. */
       |  private val _startedAtMs: Long = java.lang.System.currentTimeMillis()
       |  import java.io.{BufferedInputStream, ByteArrayOutputStream, OutputStream}
       |  import java.nio.charset.StandardCharsets
       |  import java.util.concurrent.{LinkedBlockingQueue, ScheduledFuture, TimeUnit}
       |  import java.util.concurrent.atomic.AtomicReference
       |  @volatile private var onMessageCb: String => Unit = null
       |  @volatile private var onPongCb:    String => Unit = null
       |  // AtomicReference so close-fires-once is enforced by a CAS,
       |  // not by a best-effort `var = null` read/write race between
       |  // the writer-VT's finally and any caller-side `close()`.
       |  private val onCloseCb = AtomicReference[() => Unit](null)
       |  @volatile private var closing:     Boolean        = false
       |  // Server-initiated heartbeat: empty Ping every 30 s, drop the
       |  // connection if no Pong arrives within 90 s.  Catches NAT-dropped
       |  // and silently-half-closed peers well before OS keepalive does.
       |  private val HeartbeatIntervalMs: Long = 30_000L
       |  private val DeadAfterMs:         Long = 90_000L
       |  @volatile private var lastPongAt: Long = java.lang.System.currentTimeMillis()
       |  @volatile private var heartbeatTask: ScheduledFuture[?] = null
       |  // Fragmented-message reassembly (RFC 6455 §5.4): the first frame
       |  // of a fragmented message carries the data opcode with FIN=0,
       |  // follow-up frames are Continuation (opcode=0) with the rest,
       |  // the last with FIN=1.  Control frames may interleave freely.
       |  // Held strictly on the read-loop thread so no synchronisation
       |  // needed beyond the loop itself.
       |  private var fragOpcode: Int = -1
       |  private val fragBuf     = ByteArrayOutputStream()
       |  private val out: OutputStream                     = socket.getOutputStream
       |
       |  // Outbound write queue: every `send` / `close` / `pong` parks a
       |  // ready-encoded frame here and returns immediately.  A dedicated
       |  // writer virtual thread drains the queue and writes to the
       |  // socket — so a broadcast `clients.foreach { _.send(msg) }`
       |  // never blocks on the slowest peer.  The queue is bounded
       |  // (`MaxOutQDepth` frames): if a peer can't keep up the offers
       |  // start returning false, and we force-close.
       |  private val MaxOutQDepth = 1024
       |  private val outQ: LinkedBlockingQueue[Array[Byte]] = LinkedBlockingQueue(MaxOutQDepth)
       |  // Reference-identity sentinel for "drain and exit".  A zero-
       |  // length payload is distinguishable by `eq` (we never enqueue
       |  // an actual zero-length frame; the encoder always emits at
       |  // least 2 bytes of header).
       |  private val SENTINEL: Array[Byte] = new Array[Byte](0)
       |  // Writer virtual thread — cheap with Loom (~few KB stack).
       |  // Reflective lookup so the emit also compiles on Java 17,
       |  // where ofVirtual() isn't on Thread.  Falls back to a
       |  // regular daemon Thread (each WS connection still gets its
       |  // own writer thread, just at a ~256 KB stack apiece).
       |  private val writerThread: Thread =
       |    try
       |      val cls   = Class.forName("java.lang.Thread$Builder$OfVirtual")
       |      val of    = classOf[Thread].getMethod("ofVirtual").invoke(null)
       |      val named = cls.getMethod("name", classOf[String]).invoke(of, "ws-writer")
       |      cls.getMethod("start", classOf[Runnable])
       |        .invoke(named, (() => _writeLoop()): Runnable).asInstanceOf[Thread]
       |    catch case _: Throwable =>
       |      val t = Thread(() => _writeLoop(), "ws-writer")
       |      t.setDaemon(true)
       |      t.start()
       |      t
       |
       |  private def _writeLoop(): Unit =
       |    try
       |      var done = false
       |      while !done do
       |        val bytes = outQ.take()
       |        if bytes eq SENTINEL then done = true
       |        else
       |          // out.write is the only blocking op here, and we own
       |          // its only caller — no lock needed.  A slow peer parks
       |          // this VT (cheap); other connections' writers are
       |          // unaffected.
       |          out.write(bytes)
       |          out.flush()
       |    catch case _: Throwable => () // socket closed mid-write etc.
       |    finally
       |      // Stop the heartbeat first so it can't fire after we close.
       |      val t = heartbeatTask; heartbeatTask = null
       |      if t != null then t.cancel(false)
       |      try socket.close() catch case _: Throwable => ()
       |      // Release the global-cap slot reserved at upgrade time.
       |      _wsActiveCount.decrementAndGet()
       |      _Metrics.wsActive.decrementAndGet()
       |      // Structured close log (Sprint 4 #13).  One tab-separated
       |      // line per teardown, regardless of who initiated.
       |      val _durMs = java.lang.System.currentTimeMillis() - _startedAtMs
       |      println("ws.close\tid=" + id + "\tduration_ms=" + _durMs)
       |      // Per-route cleanup (e.g. decrement the route's activeCount).
       |      // No-op when the route had no per-route cap.
       |      try _onTerminate() catch case _: Throwable => ()
       |      val cb = onCloseCb.getAndSet(null)
       |      if cb != null then _serverExecutor.execute { () =>
       |        try cb() catch case e: Throwable =>
       |          System.err.println(s"WS close handler: ${e.getMessage}")
       |      }
       |      // Wake any parked recv consumers with a sentinel `null`.
       |      _deliverRecv(null)
       |
       |  /** Force the writer loop to exit promptly when the queue can't
       |   *  drain.  Used by `send`-overflow and by the read-loop when EOF
       |   *  arrives.  Idempotent. */
       |  private def _forceShutdown(): Unit =
       |    if !closing then closing = true
       |    // Closing the socket breaks both `out.write` (in writer) and
       |    // `in.read` (in read-loop) with an IOException — both fall
       |    // into their `catch / finally` clauses and tidy up.
       |    try socket.close() catch case _: Throwable => ()
       |
       |  def send(s: String): Unit =
       |    if closing then return
       |    val frame = _wsEncodeText(s)
       |    if !outQ.offer(frame) then _forceShutdown()
       |    else { _Metrics.wsMessagesOut.incrementAndGet(); _Metrics.wsBytesOut.addAndGet(frame.length.toLong) }
       |
       |  // Binary frames take the Latin-1 byte-view convention the rest
       |  // of the runtime already uses (UploadedFile.bytes, inbound
       |  // binary frames): one Java char per wire byte.
       |  def sendBytes(s: String): Unit =
       |    if closing then return
       |    val frame = _wsEncodeFrame(0x2, s.getBytes("ISO-8859-1"))
       |    if !outQ.offer(frame) then _forceShutdown()
       |    else { _Metrics.wsMessagesOut.incrementAndGet(); _Metrics.wsBytesOut.addAndGet(frame.length.toLong) }
       |
       |  def close(code: Int = 1000, reason: String = ""): Unit =
       |    if closing then return
       |    closing = true
       |    // Best-effort: enqueue the close frame, then a sentinel so
       |    // the writer drains and exits cleanly.  If the queue is full
       |    // (slow peer), fall through to a hard socket close — the
       |    // writer's finally handles onClose dispatch either way.
       |    val queued = outQ.offer(_wsEncodeClose(code, reason)) && outQ.offer(SENTINEL)
       |    if !queued then _forceShutdown()
       |
       |  def onMessage(cb: String => Unit): Unit = onMessageCb = cb
       |  def onClose(cb: () => Unit): Unit       = onCloseCb.set(cb)
       |  def onPong(cb: String => Unit): Unit    = onPongCb   = cb
       |
       |  // ── Async-style blocking recv (alternative to onMessage cb) ────
       |  //
       |  // Lets a handler read messages with a `while !ws.isClosed do …`
       |  // loop instead of inverting control through `onMessage`.  Works
       |  // because each WS connection runs the user handler on its own
       |  // virtual thread (Loom), so a parked recv blocks just that VT.
       |  // `_recvQueue` is lazily populated by `_deliverMessage` once
       |  // `recv` (or `recvBytes`) is first called.
       |  private val _recvQueue: LinkedBlockingQueue[String | Null] = LinkedBlockingQueue()
       |  @volatile private var _recvEnabled: Boolean = false
       |
       |  /** Block the calling thread until a message arrives or the WS
       |   *  closes.  Returns Some(msg) on a text/binary message, None on
       |   *  close. */
       |  def recv(): Option[String] =
       |    _recvEnabled = true
       |    val v = _recvQueue.take()
       |    if v == null then None else Some(v.asInstanceOf[String])
       |
       |  /** True once the close-frame has been sent or received. */
       |  def isClosed: Boolean = closing
       |
       |  /** Called by the read-loop after each fully-reassembled message
       |   *  (and once with `null` on close).  Pushes into the recv-queue
       |   *  if any recv-style consumer has activated it; otherwise falls
       |   *  through to the callback-style API.  Cost when nobody calls
       |   *  recv: a single volatile read. */
       |  def _deliverRecv(payload: String | Null): Unit =
       |    if _recvEnabled then _recvQueue.offer(payload)
       |
       |  /** ping([payload]): empty Ping or Latin-1-byte-view payload that
       |    * the peer echoes back as a Pong (delivered via `onPong`).
       |    * Doesn't interfere with the server-side 30 s heartbeat;
       |    * both call sites refresh the same `lastPongAt`. */
       |  def ping(): Unit = ping("")
       |  def ping(payload: String): Unit =
       |    if closing then return
       |    val bytes = if payload.isEmpty then Array.emptyByteArray else payload.getBytes("ISO-8859-1")
       |    if !outQ.offer(_wsEncodePing(bytes)) then _forceShutdown()
       |
       |  /** Arm the periodic Ping → Pong heartbeat.  Called once by
       |    * `_proxyConnection` right after the upgrade. */
       |  def _startHeartbeat(): Unit =
       |    lastPongAt = java.lang.System.currentTimeMillis()
       |    heartbeatTask = _wsHeartbeats.scheduleAtFixedRate(() => {
       |      try
       |        if java.lang.System.currentTimeMillis() - lastPongAt > DeadAfterMs then
       |          close(1001, "ping timeout")
       |        else if !closing then
       |          // Empty Ping — _wsEncodePing's payload is unused except
       |          // for the wire byte.  Drop on full outQ (peer too slow).
       |          outQ.offer(_wsEncodePing(Array.emptyByteArray))
       |      catch case _: Throwable => ()
       |    }, HeartbeatIntervalMs, HeartbeatIntervalMs, TimeUnit.MILLISECONDS)
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
       |                case 0x9 =>
       |                  // Drop the pong if the write queue is full — peer
       |                  // is too slow to keep up with the data flow we're
       |                  // trying to send them anyway; the next ping will
       |                  // time out and the writer will force-close.
       |                  outQ.offer(_wsEncodePong(fr.payload))
       |                case 0xA =>
       |                  lastPongAt = java.lang.System.currentTimeMillis()
       |                  val cb = onPongCb
       |                  if cb != null then
       |                    val payload = new String(fr.payload, "ISO-8859-1")
       |                    _serverExecutor.execute { () =>
       |                      try cb(payload) catch case e: Throwable =>
       |                        System.err.println(s"WS onPong handler: ${e.getMessage}")
       |                    }
       |                case 0x8 =>
       |                  val status =
       |                    if fr.payload.length >= 2
       |                    then ((fr.payload(0) & 0xFF) << 8) | (fr.payload(1) & 0xFF)
       |                    else 1000
       |                  close(status, "")
       |                  return
       |                case 0x1 | 0x2 =>
       |                  if !fr.fin then
       |                    // Start of a fragmented message.
       |                    if fragOpcode != -1 then { close(1002, "new data frame mid-fragment"); return }
       |                    fragOpcode = fr.opcode
       |                    fragBuf.reset()
       |                    fragBuf.write(fr.payload)
       |                    if fragBuf.size > _WsMaxFrameBytes then
       |                      fragOpcode = -1; fragBuf.reset(); close(1009, "message too big"); return
       |                  else _dispatchWsMessage(fr.opcode, fr.payload)
       |                case 0x0 =>
       |                  if fragOpcode == -1 then { close(1002, "continuation without prior data frame"); return }
       |                  fragBuf.write(fr.payload)
       |                  if fragBuf.size > _WsMaxFrameBytes then
       |                    fragOpcode = -1; fragBuf.reset(); close(1009, "message too big"); return
       |                  if fr.fin then
       |                    val op    = fragOpcode
       |                    val bytes = fragBuf.toByteArray
       |                    fragOpcode = -1; fragBuf.reset()
       |                    _dispatchWsMessage(op, bytes)
       |                case _   => close(1003, ""); return
       |        if offset > 0 then
       |          System.arraycopy(buf, offset, buf, 0, len - offset)
       |          len -= offset
       |    catch case _: Throwable => ()
       |    finally
       |      // Tell the writer to drain and exit; its `finally` does the
       |      // actual `socket.close()` + onClose dispatch.  If the
       |      // sentinel can't be queued (full backlog) we force-close
       |      // the socket so the writer's blocking `out.write` throws
       |      // and runs its cleanup.
       |      closing = true
       |      if !outQ.offer(SENTINEL) then
       |        try socket.close() catch case _: Throwable => ()
       |
       |  /** Hand a fully-reassembled text/binary payload to the user
       |   *  `onMessage` callback via the shared executor.  Queued
       |   *  unconditionally — the `onWebSocket` body also runs on the
       |   *  executor, so reading the callback inside the task gives us
       |   *  the up-to-date value. */
       |  // Rate-limit state — fixed 1-second window.  Only the read loop
       |  // touches these, so no synchronisation needed.
       |  private var _rateWindowStartMs: Long = 0L
       |  private var _rateMsgsInWindow:  Int  = 0
       |
       |  private def _dispatchWsMessage(opcode: Int, payload: Array[Byte]): Unit =
       |    if _maxMessagesPerSec > 0 then
       |      val now = java.lang.System.currentTimeMillis()
       |      if now - _rateWindowStartMs >= 1000L then
       |        _rateWindowStartMs = now
       |        _rateMsgsInWindow  = 0
       |      _rateMsgsInWindow += 1
       |      if _rateMsgsInWindow > _maxMessagesPerSec then
       |        close(1008, "rate limit exceeded"); return
       |    _Metrics.wsMessagesIn.incrementAndGet()
       |    _Metrics.wsBytesIn.addAndGet(payload.length.toLong)
       |    val msg =
       |      if opcode == 0x1 then new String(payload, StandardCharsets.UTF_8)
       |      else                  new String(payload, "ISO-8859-1")
       |    // Async-style recv path: parked consumers wake on the next take.
       |    _deliverRecv(msg)
       |    _serverExecutor.execute { () =>
       |      val cb = onMessageCb
       |      if cb != null then try cb(msg) catch case e: Throwable =>
       |        System.err.println(s"WS message handler: ${e.getMessage}")
       |    }
       |
       |private final case class _WsRoute(
       |  pattern:   List[_Seg],
       |  handler:   WebSocket => Unit,
       |  origins:   List[String] = Nil,  // empty = no Origin restriction
       |  protocols: List[String] = Nil,  // empty = no subprotocol negotiation
       |  // Per-route active-connection cap.  0 = unlimited; positive
       |  // values refuse upgrades past the cap with 503.  Composes
       |  // with the process-wide `setMaxWsConnections` cap.
       |  maxConnections: Int = 0,
       |  // Per-connection inbound message rate cap (msgs/sec).
       |  // 0 = unlimited; overrun closes the offending client with
       |  // code 1008.  Applied per connection on this route.
       |  maxMessagesPerSec: Int = 0,
       |  // Pre-upgrade auth hook.  None = no check; Some(fn) =
       |  // invoke fn with the Request before reserving any slot.
       |  // fn returns Some(payload) to accept (payload becomes
       |  // ws.user) or None to reject with HTTP 401.  Runs on the
       |  // dispatch thread, so it must be read-only.
       |  auth: Option[Request => Option[Any]] = None,
       |  activeCount: java.util.concurrent.atomic.AtomicInteger =
       |               java.util.concurrent.atomic.AtomicInteger(0)
       |):
       |  def tryReserve(): Boolean =
       |    if maxConnections <= 0 then true
       |    else
       |      val after = activeCount.incrementAndGet()
       |      if after > maxConnections then { activeCount.decrementAndGet(); false }
       |      else true
       |  def release(): Unit =
       |    if maxConnections > 0 then activeCount.decrementAndGet()
       |private val _wsRoutes = scala.collection.mutable.ArrayBuffer.empty[_WsRoute]
       |
       |// ── Process-wide metrics (Sprint 4 #14) ─────────────────────────
       |// Counters mirroring scalascript.server.Metrics on the
       |// interpreter side.  Same key names so log shippers / health
       |// checks scrape identical output across backends.
       |private object _Metrics:
       |  val wsActive      = java.util.concurrent.atomic.AtomicLong(0L)
       |  val wsUpgraded    = java.util.concurrent.atomic.AtomicLong(0L)
       |  val wsRejected    = java.util.concurrent.atomic.AtomicLong(0L)
       |  val wsMessagesIn  = java.util.concurrent.atomic.AtomicLong(0L)
       |  val wsMessagesOut = java.util.concurrent.atomic.AtomicLong(0L)
       |  val wsBytesIn     = java.util.concurrent.atomic.AtomicLong(0L)
       |  val wsBytesOut    = java.util.concurrent.atomic.AtomicLong(0L)
       |  val httpRequests  = java.util.concurrent.atomic.AtomicLong(0L)
       |  val http4xx       = java.util.concurrent.atomic.AtomicLong(0L)
       |  val http5xx       = java.util.concurrent.atomic.AtomicLong(0L)
       |  def snapshot: Map[String, Long] = Map(
       |    "ws.active"       -> wsActive.get,
       |    "ws.upgraded"     -> wsUpgraded.get,
       |    "ws.rejected"     -> wsRejected.get,
       |    "ws.messages.in"  -> wsMessagesIn.get,
       |    "ws.messages.out" -> wsMessagesOut.get,
       |    "ws.bytes.in"     -> wsBytesIn.get,
       |    "ws.bytes.out"    -> wsBytesOut.get,
       |    "http.requests"   -> httpRequests.get,
       |    "http.4xx"        -> http4xx.get,
       |    "http.5xx"        -> http5xx.get
       |  )
       |
       |/** Snapshot of process-wide counters — `Map[String, Long]`,
       |  * same key names as the interpreter's `metrics()` native. */
       |def metrics(): Map[String, Long] = _Metrics.snapshot
       |
       |// Process-wide cap on active WS sessions.  Tuned with
       |// `setMaxWsConnections(n)`; default = unlimited.  Upgrades past
       |// the cap are refused with 503.  `closeNow` decrements via the
       |// per-connection `_releaseSlot` helper.
       |private val _wsMaxActive    = java.util.concurrent.atomic.AtomicInteger(Int.MaxValue)
       |private val _wsActiveCount  = java.util.concurrent.atomic.AtomicInteger(0)
       |def setMaxWsConnections(n: Int): Unit =
       |  _wsMaxActive.set(if n < 0 then Int.MaxValue else n)
       |private def _wsTryReserve(): Boolean =
       |  val after = _wsActiveCount.incrementAndGet()
       |  if after > _wsMaxActive.get then { _wsActiveCount.decrementAndGet(); false }
       |  else { _Metrics.wsActive.incrementAndGet(); true }
       |
       |/** WsRoom — thread-safe registry of WebSocket clients with a
       |  * built-in `broadcast(msg)` helper.  Spawn one per logical
       |  * channel (e.g. one room per chat room) and let the handler
       |  * `add` / `remove` itself in onMessage / onClose. */
       |class WsRoom:
       |  private val members = java.util.concurrent.CopyOnWriteArrayList[WebSocket]()
       |  def add(ws: WebSocket): Unit    = members.add(ws)
       |  def remove(ws: WebSocket): Unit = members.remove(ws)
       |  def broadcast(msg: String): Unit =
       |    val it = members.iterator()
       |    while it.hasNext do
       |      try it.next().send(msg)
       |      catch case _: Throwable => () // dead client, will be reaped via onClose
       |  def size: Int = members.size
       |
       |/** Companion `WsRoom()` factory so user code reads naturally. */
       |def WsRoom(): WsRoom = new WsRoom
       |
       |def onWebSocket(path: String): (WebSocket => Unit) => Unit = (handler) => {
       |  _wsRoutes += _WsRoute(_parsePath(path), handler)
       |}
       |
       |/** Two-arg form: only accept upgrades whose `Origin:` header is in
       |  * `origins`.  Browser CSRF guard — same-origin policy does NOT
       |  * block cross-site `new WebSocket(...)` calls. */
       |def onWebSocket(path: String, origins: List[String]): (WebSocket => Unit) => Unit = (handler) => {
       |  _wsRoutes += _WsRoute(_parsePath(path), handler, origins)
       |}
       |
       |/** Three-arg form: also negotiate Sec-WebSocket-Protocol.  Server
       |  * picks the first protocol from its `protocols` list that's in
       |  * the client's request; no match refuses with 400.  Required
       |  * for `socket.io` / `graphql-ws` clients. */
       |def onWebSocket(path: String, origins: List[String], protocols: List[String]): (WebSocket => Unit) => Unit = (handler) => {
       |  _wsRoutes += _WsRoute(_parsePath(path), handler, origins, protocols)
       |}
       |
       |/** Four-arg form adds a per-route active-connection cap.
       |  * 0 = unlimited; positive values refuse upgrades past the cap
       |  * with 503.  Composes with the process-wide
       |  * `setMaxWsConnections`. */
       |def onWebSocket(path: String, origins: List[String], protocols: List[String], maxConnections: Int): (WebSocket => Unit) => Unit = (handler) => {
       |  _wsRoutes += _WsRoute(_parsePath(path), handler, origins, protocols, maxConnections)
       |}
       |
       |/** Five-arg form also caps inbound messages per second per
       |  * connection.  Overrun closes the offending client with code
       |  * 1008 ("policy violation").  0 = unlimited. */
       |def onWebSocket(path: String, origins: List[String], protocols: List[String], maxConnections: Int, maxMessagesPerSec: Int): (WebSocket => Unit) => Unit = (handler) => {
       |  _wsRoutes += _WsRoute(_parsePath(path), handler, origins, protocols, maxConnections, maxMessagesPerSec)
       |}
       |
       |/** Pre-upgrade auth hook.  `authFn(req)` returns `Some(payload)`
       |  * to accept the upgrade (payload becomes `ws.user`) or `None`
       |  * to reject with HTTP 401 before the WebSocket is even built.
       |  * Hook runs on the dispatch thread — must be read-only over
       |  * mutable state. */
       |def onWebSocketAuth(path: String, authFn: Request => Option[Any]): (WebSocket => Unit) => Unit = (handler) => {
       |  _wsRoutes += _WsRoute(_parsePath(path), handler, auth = Some(authFn))
       |}
       |
       |// ── Framing ──────────────────────────────────────────────────────────
       |
       |private val _WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
       |// Hard cap on a single frame's payload (16 MB) — protects against a
       |// hostile peer announcing a multi-GB payload that we'd otherwise
       |// try to allocate up front.
       |private val _WsMaxFrameBytes: Int = 16 * 1024 * 1024
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
       |      if v > _WsMaxFrameBytes.toLong then throw RuntimeException(s"WS frame too large: $v bytes (max $_WsMaxFrameBytes)")
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
       |def _wsEncodePing(p: Array[Byte]): Array[Byte] = _wsEncodeFrame(0x9, p)
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
       |  // TCP keepalive lets the OS detect peers that vanished without
       |  // FIN (yanked cables, dropped mobile sessions).  Without it a
       |  // dead WS holds its FD for ~2 h before the TCP stack notices.
       |  try client.setKeepAlive(true) catch case _: Throwable => ()
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
       |      _matchPath(r.pattern, segs).map(params => (r, params))
       |    }.nextOption()
       |    matched match
       |      case None =>
       |        cout.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"))
       |        cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet()
       |      case Some((r, params)) =>
       |        val key = headers.getOrElse("sec-websocket-key", "")
       |        if key.isEmpty then { client.close(); return }
       |        // Origin allowlist (CSRF guard) — empty list = unrestricted.
       |        if r.origins.nonEmpty then
       |          val origin = headers.getOrElse("origin", "")
       |          if !r.origins.contains(origin) then
       |            cout.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"))
       |            cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet(); return
       |        // Pre-upgrade auth hook.  Same shape as the interpreter
       |        // path: read-only over headers / cookies / Origin.
       |        // None → reject with 401; Some(v) → carry v to ws.user.
       |        val _authRawQ  = request.split(' ').lift(1).getOrElse("/").split('?').lift(1).getOrElse("")
       |        val _authCookies: Map[String, String] = headers.get("cookie") match
       |          case None => Map.empty
       |          case Some(raw) => raw.split(';').iterator.flatMap { pair =>
       |            val t = pair.trim
       |            val i = t.indexOf('=')
       |            if i < 0 then None else Some(t.substring(0, i).trim -> t.substring(i + 1).trim)
       |          }.toMap
       |        val _authReq = Request(
       |          method  = "GET",
       |          path    = path,
       |          params  = params,
       |          query   = _parseQuery(_authRawQ),
       |          headers = headers,
       |          body    = "",
       |          cookies = _authCookies
       |        )
       |        var _authPayload: Option[Any] = None
       |        var _authReject:  Boolean      = false
       |        r.auth.foreach { fn =>
       |          try fn(_authReq) match
       |            case Some(v) => _authPayload = Some(v)
       |            case None    => _authReject  = true
       |          catch case e: Throwable =>
       |            System.err.println(s"WS auth hook: ${e.getMessage}")
       |            _authReject = true
       |        }
       |        if _authReject then
       |          cout.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"))
       |          cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet(); return
       |        // Process-wide active-connection cap.  Reserved AFTER
       |        // the Origin check so a denied-Origin attempt doesn't
       |        // briefly consume a slot.  Released in the writer-VT's
       |        // `finally` after the channel closes.
       |        if !_wsTryReserve() then
       |          cout.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"))
       |          cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet(); return
       |        // Per-route active-connection cap.  Composes with the
       |        // process-wide cap above (both must permit).  0 = no
       |        // per-route limit.  Released by the writer-VT finally
       |        // via `r.release()`.
       |        if !r.tryReserve() then
       |          _wsActiveCount.decrementAndGet()
       |          cout.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"))
       |          cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet(); return
       |        // Subprotocol negotiation (RFC 6455 §1.9).
       |        val chosenProtocol: String =
       |          if r.protocols.isEmpty then ""
       |          else
       |            val offered = headers.getOrElse("sec-websocket-protocol", "")
       |              .split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet
       |            r.protocols.find(offered.contains).getOrElse("")
       |        if r.protocols.nonEmpty && chosenProtocol.isEmpty then
       |          _wsActiveCount.decrementAndGet()
       |          r.release()
       |          cout.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"))
       |          cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet(); return
       |        val accept = _wsAcceptKey(key)
       |        val protoHeader = if chosenProtocol.isEmpty then "" else s"Sec-WebSocket-Protocol: $chosenProtocol\r\n"
       |        val resp =
       |          "HTTP/1.1 101 Switching Protocols\r\n" +
       |          "Upgrade: websocket\r\n" +
       |          "Connection: Upgrade\r\n" +
       |          s"Sec-WebSocket-Accept: $accept\r\n" +
       |          protoHeader + "\r\n"
       |        cout.write(resp.getBytes("US-ASCII")); cout.flush()
       |        // Build the Request snapshot — same shape as REST handlers
       |        // see (sans body / form / files; the WS upgrade is a GET
       |        // with no body) so WS-side auth can read cookies /
       |        // Authorization / Origin from `ws.request.headers`.
       |        val rawQ  = request.split(' ').lift(1).getOrElse("/").split('?').lift(1).getOrElse("")
       |        // Cookie header: `name=value; name=value; …` → Map.
       |        val wsCookies: Map[String, String] = headers.get("cookie") match
       |          case None => Map.empty
       |          case Some(raw) => raw.split(';').iterator.flatMap { pair =>
       |            val t = pair.trim
       |            val i = t.indexOf('=')
       |            if i < 0 then None else Some(t.substring(0, i).trim -> t.substring(i + 1).trim)
       |          }.toMap
       |        val wsReq = Request(
       |          method  = "GET",
       |          path    = path,
       |          params  = params,
       |          query   = _parseQuery(rawQ),
       |          headers = headers,
       |          body    = "",
       |          cookies = wsCookies
       |        )
       |        _Metrics.wsUpgraded.incrementAndGet()
       |        val ws = WebSocket(client, wsReq, subprotocol = chosenProtocol, _onTerminate = () => r.release(), _maxMessagesPerSec = r.maxMessagesPerSec, user = _authPayload)
       |        // Structured connect log (Sprint 4 #13).
       |        val _accessIp     = try client.getRemoteSocketAddress.toString catch case _: Throwable => "?"
       |        val _accessOrigin = headers.getOrElse("origin", "")
       |        println("ws.connect\tid=" + ws.id + "\tip=" + _accessIp +
       |          "\troute=" + path + "\torigin=" + _accessOrigin +
       |          "\tproto=" + chosenProtocol)
       |        // Run the user's `onWebSocket` block on the shared single-
       |        // thread executor so any state it touches (top-level `var`s,
       |        // route registry, etc.) is serial with HTTP handlers and
       |        // later `onMessage` / `onClose` callbacks.
       |        _serverExecutor.execute { () =>
       |          try r.handler(ws) catch case e: Throwable =>
       |            System.err.println(s"WS upgrade handler: ${e.getMessage}")
       |        }
       |        // Read-loop stays on this thread (cached pool) so a slow
       |        // socket can't block the executor; only the dispatched
       |        // callbacks above go through the single-thread queue.
       |        ws._startHeartbeat()
       |        ws._runReadLoop()
       |  else
       |    // Plain HTTP — open a backend socket to the internal HttpServer
       |    // and copy bytes both ways until either side EOFs.
       |    val back = java.net.Socket("127.0.0.1", internalPort)
       |    val bin  = java.io.BufferedInputStream(back.getInputStream)
       |    val bout = back.getOutputStream
       |    bout.write(head); bout.flush()
       |    // Virtual threads (Loom) on JDK 21+; plain Threads as a
       |    // fallback so the emit also compiles on Java 17.
       |    def _spawn(name: String, body: () => Unit): Thread =
       |      try
       |        val cls    = Class.forName("java.lang.Thread$Builder$OfVirtual")
       |        val of     = classOf[Thread].getMethod("ofVirtual").invoke(null)
       |        val named  = cls.getMethod("name", classOf[String]).invoke(of, name)
       |        cls.getMethod("start", classOf[Runnable])
       |          .invoke(named, (() => body()): Runnable).asInstanceOf[Thread]
       |      catch case _: Throwable =>
       |        val t = Thread(() => body(), name)
       |        t.start()
       |        t
       |    val pump1 = _spawn("ws-proxy-pump-c2b", () => _pump(cin, bout, back, client))
       |    val pump2 = _spawn("ws-proxy-pump-b2c", () => _pump(bin, cout, client, back))
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
       |// ── TLS / HTTPS support ────────────────────────────────────────────────
       |case class _TlsConfig(cert: String, key: String)
       |
       |def tls(cert: String, key: String): _TlsConfig = _TlsConfig(cert, key)
       |
       |def _buildSslContext(certPath: String, keyPath: String): javax.net.ssl.SSLContext =
       |  import java.security.{KeyStore, KeyFactory}
       |  import java.security.cert.CertificateFactory
       |  import javax.net.ssl.{KeyManagerFactory, SSLContext}
       |  val certBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(certPath))
       |  val keyBytes  = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(keyPath))
       |  val certFactory = CertificateFactory.getInstance("X.509")
       |  val cert = certFactory.generateCertificate(java.io.ByteArrayInputStream(certBytes))
       |  val keyPem = new String(keyBytes, "UTF-8")
       |    .replaceAll("-----[^-]+-----", "").replaceAll("\\s", "")
       |  val der = java.util.Base64.getDecoder.decode(keyPem)
       |  val keySpec = java.security.spec.PKCS8EncodedKeySpec(der)
       |  val privateKey =
       |    try java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)
       |    catch case _: Throwable =>
       |      java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec)
       |  val ks = KeyStore.getInstance("JKS")
       |  ks.load(null, null)
       |  ks.setCertificateEntry("cert", cert)
       |  ks.setKeyEntry("key", privateKey, Array.emptyCharArray, Array(cert))
       |  val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
       |  kmf.init(ks, Array.emptyCharArray)
       |  val ctx = SSLContext.getInstance("TLS")
       |  ctx.init(kmf.getKeyManagers, null, null)
       |  ctx
       |
       |def _vThreadPool(): java.util.concurrent.ExecutorService =
       |  try
       |    classOf[java.util.concurrent.Executors]
       |      .getMethod("newVirtualThreadPerTaskExecutor")
       |      .invoke(null).asInstanceOf[java.util.concurrent.ExecutorService]
       |  catch case _: Throwable =>
       |    java.util.concurrent.Executors.newCachedThreadPool()
       |
       |private val _stopLatch = java.util.concurrent.CountDownLatch(1)
       |@volatile private var _pubSocket: java.net.ServerSocket | Null = null
       |@volatile private var _internalHttp: com.sun.net.httpserver.HttpServer | Null = null
       |
       |def stop(): Unit =
       |  try { _pubSocket  match { case s if s != null => s.close();  case _ => () } } catch { case _: Throwable => () }
       |  try { _internalHttp match { case h if h != null => h.stop(0); case _ => () } } catch { case _: Throwable => () }
       |  _stopLatch.countDown()
       |
       |def serve(port: Int): Unit = serve(port, null.asInstanceOf[_TlsConfig])
       |
       |def serve(port: Int, tlsCfg: _TlsConfig): Unit =
       |  _registerHealthDefaults()
       |  val internal = com.sun.net.httpserver.HttpServer.create(
       |    java.net.InetSocketAddress("127.0.0.1", 0), 0)
       |  internal.createContext("/", _handle)
       |  internal.setExecutor(_serverExecutor)
       |  internal.start()
       |  _internalHttp = internal
       |  val internalPort = internal.getAddress.getPort
       |  val pool = _vThreadPool()
       |  if tlsCfg != null then
       |    val sslCtx = _buildSslContext(tlsCfg.cert, tlsCfg.key)
       |    val pub = sslCtx.getServerSocketFactory.createServerSocket(port)
       |      .asInstanceOf[javax.net.ssl.SSLServerSocket]
       |    _pubSocket = pub
       |    println(s"Listening on https://localhost:$port/  (proxy → 127.0.0.1:$internalPort)")
       |    Thread(() => {
       |      while !pub.isClosed do
       |        try
       |          val c = pub.accept()
       |          pool.execute { () => _proxyConnection(c, internalPort) }
       |        catch case _: Throwable => ()
       |    }, "tls-proxy-accept").start()
       |  else
       |    val pub = java.net.ServerSocket(port)
       |    _pubSocket = pub
       |    println(s"Listening on http://localhost:$port/  (proxy → 127.0.0.1:$internalPort)")
       |    Thread(() => {
       |      while !pub.isClosed do
       |        try
       |          val c = pub.accept()
       |          pool.execute { () => _proxyConnection(c, internalPort) }
       |        catch case _: Throwable => ()
       |    }, "ws-proxy-accept").start()
       |  _stopLatch.await()
       |
       |// ── Outbound HTTP client ────────────────────────────────────────────────
       |private var _httpBaseUrl: String = ""
       |
       |private def _httpDoRequest(method: String, url: String, body: String,
       |    headers: Map[String, String]): Any =
       |  import java.net.http.{HttpClient as JHC, HttpRequest, HttpResponse}
       |  import scala.jdk.CollectionConverters.*
       |  val effectiveUrl = if _httpBaseUrl.nonEmpty && !url.startsWith("http") then _httpBaseUrl + url else url
       |  val client = JHC.newHttpClient()
       |  val builder = HttpRequest.newBuilder().uri(java.net.URI.create(effectiveUrl))
       |  headers.foreach((k, v) => builder.header(k, v))
       |  val req = method match
       |    case "GET"    => builder.GET().build()
       |    case "DELETE" => builder.DELETE().build()
       |    case m        => builder.method(m, HttpRequest.BodyPublishers.ofString(body)).build()
       |  val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
       |  val hdrs = resp.headers().map().entrySet().iterator().asScala.flatMap { e =>
       |    if e.getValue.isEmpty then None
       |    else Some(e.getKey -> e.getValue.get(0))
       |  }.toMap
       |  Response(status = resp.statusCode(), body = resp.body(), headers = hdrs)
       |
       |def httpGet(url: String, headers: Map[String, String] = Map.empty): Any =
       |  _httpDoRequest("GET", url, "", headers)
       |
       |def httpPost(url: String, body: String, headers: Map[String, String] = Map.empty): Any =
       |  _httpDoRequest("POST", url, body, headers)
       |
       |def httpClient(baseUrl: String)(block: => Any): Any =
       |  val prior = _httpBaseUrl
       |  _httpBaseUrl = baseUrl
       |  try block finally _httpBaseUrl = prior
       |
       |// ── v1.10 Generator — pull-based lazy streams via virtual threads ────────
       |// Each Generator[A] runs its body in a virtual thread.
       |// suspend(v) blocks the body thread until the consumer calls .next().
       |private val _genQueueTL = new ThreadLocal[java.util.concurrent.SynchronousQueue[Option[Any]]]()
       |
       |private def _suspend(v: Any): Unit =
       |  val q = _genQueueTL.get()
       |  if q == null then throw new RuntimeException("suspend called outside a generator body")
       |  q.put(Some(v))
       |
       |def suspend(v: Any): Unit = _suspend(v)
       |
       |class _Generator[+A](bodyFn: () => Unit):
       |  private type Q = java.util.concurrent.SynchronousQueue[Option[Any]]
       |  private val queue: Q = new Q()
       |  Thread.ofVirtual().start { () =>
       |    _genQueueTL.set(queue)
       |    try bodyFn()
       |    catch case _: Throwable => ()
       |    finally try queue.put(None) catch case _ => ()
       |  }
       |
       |  def next(): Option[A] = queue.take().asInstanceOf[Option[A]]
       |
       |  def foreach(f: A => Unit): Unit =
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      f(item.get)
       |      item = queue.take().asInstanceOf[Option[A]]
       |
       |  def toList: List[A] =
       |    val buf = scala.collection.mutable.ListBuffer.empty[A]
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      buf += item.get
       |      item = queue.take().asInstanceOf[Option[A]]
       |    buf.toList
       |
       |  def map[B](f: A => B): _Generator[B] = new _Generator[B]({ () =>
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      _suspend(f(item.get))
       |      item = queue.take().asInstanceOf[Option[A]]
       |  })
       |
       |  def filter(pred: A => Boolean): _Generator[A] = new _Generator[A]({ () =>
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      if pred(item.get) then _suspend(item.get)
       |      item = queue.take().asInstanceOf[Option[A]]
       |  })
       |
       |  def take(n: Int): _Generator[A] = new _Generator[A]({ () =>
       |    var remaining = n
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined && remaining > 0 do
       |      _suspend(item.get)
       |      remaining -= 1
       |      item = if remaining > 0 then queue.take().asInstanceOf[Option[A]] else None
       |  })
       |
       |  def drop(n: Int): _Generator[A] = new _Generator[A]({ () =>
       |    var toDrop = n
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined && toDrop > 0 do
       |      toDrop -= 1
       |      item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      _suspend(item.get)
       |      item = queue.take().asInstanceOf[Option[A]]
       |  })
       |
       |def generator(body: => Unit): _Generator[Any] = new _Generator[Any](() => body)
       |
       |// ── Outbound WebSocket client ─────────────────────────────────────────
       |
       |private class _WsClientConn(url: String, extraHdrs: Map[String, String], protocols: List[String]):
       |  import java.net.URI
       |  import java.net.http.{HttpClient => _JHttpClient, WebSocket => _JWs}
       |  import java.nio.ByteBuffer
       |  import java.util.concurrent.{LinkedBlockingQueue, CountDownLatch, CompletableFuture}
       |  import java.util.concurrent.atomic.AtomicReference
       |
       |  val id: String = java.util.UUID.randomUUID().toString
       |  @volatile private var _ws: _JWs | Null = null
       |  @volatile private var closingSent = false
       |  @volatile private var closedFired = false
       |  @volatile private var _subprotocol = ""
       |  private val onCloseCbRef  = new AtomicReference[Any | Null](null)
       |  @volatile private var onMessageCb: Option[Any] = None
       |  @volatile private var onPongCb:    Option[Any] = None
       |  private val recvQueue = new LinkedBlockingQueue[String | Null]()
       |  private val closeLatch = new CountDownLatch(1)
       |  private val textBuf = new StringBuilder()
       |
       |  private def dispatch(f: () => Unit): Unit =
       |    try f()
       |    catch case e: Throwable => System.err.println(s"wsConnect callback error: ${e.getMessage}")
       |
       |  private def doClose(): Unit =
       |    if !closedFired then
       |      closedFired = true
       |      recvQueue.offer(null)
       |      val cb = onCloseCbRef.getAndSet(null)
       |      if cb != null then dispatch { () => cb.asInstanceOf[() => Any]() }
       |      closeLatch.countDown()
       |
       |  private val _listener: _JWs.Listener = new _JWs.Listener:
       |    override def onText(ws: _JWs, data: CharSequence, last: Boolean): CompletableFuture[?] =
       |      textBuf.append(data)
       |      if last then
       |        val msg = textBuf.toString(); textBuf.setLength(0)
       |        recvQueue.offer(msg)
       |        onMessageCb.foreach { cb =>
       |          dispatch { () => cb.asInstanceOf[String => Any](msg) }
       |        }
       |      ws.request(1)
       |      CompletableFuture.completedFuture(null)
       |    override def onBinary(ws: _JWs, data: ByteBuffer, last: Boolean): CompletableFuture[?] =
       |      if last then
       |        val bytes = new Array[Byte](data.remaining()); data.get(bytes)
       |        val msg = new String(bytes, "ISO-8859-1")
       |        recvQueue.offer(msg)
       |        onMessageCb.foreach { cb =>
       |          dispatch { () => cb.asInstanceOf[String => Any](msg) }
       |        }
       |      ws.request(1)
       |      CompletableFuture.completedFuture(null)
       |    override def onClose(ws: _JWs, statusCode: Int, reason: String): CompletableFuture[?] =
       |      doClose(); CompletableFuture.completedFuture(null)
       |    override def onPong(ws: _JWs, message: ByteBuffer): CompletableFuture[?] =
       |      onPongCb.foreach { cb =>
       |        val payload = new String(message.array(), "ISO-8859-1")
       |        dispatch { () => cb.asInstanceOf[String => Any](payload) }
       |      }
       |      CompletableFuture.completedFuture(null)
       |    override def onError(ws: _JWs | Null, error: Throwable): Unit =
       |      System.err.println(s"wsConnect error [$url]: ${error.getMessage}")
       |      doClose()
       |
       |  def connect(): Unit =
       |    val builder = _JHttpClient.newHttpClient().newWebSocketBuilder()
       |    extraHdrs.foreach(builder.header)
       |    if protocols.nonEmpty then builder.subprotocols(protocols.head, protocols.tail*)
       |    val ws = builder.buildAsync(URI.create(url), _listener).join()
       |    _ws = ws
       |    _subprotocol = ws.getSubprotocol
       |
       |  def awaitClose(): Unit = closeLatch.await()
       |  def subprotocol: String = _subprotocol
       |
       |  def wsMap: Map[String, Any] = Map(
       |    "id"          -> id,
       |    "subprotocol" -> _subprotocol,
       |    "send"        -> ((s: Any) => {
       |                       if !closingSent then _ws match
       |                         case ws if ws != null => ws.sendText(s.toString, true)
       |                         case _ => ()
       |                       () }),
       |    "sendBytes"   -> ((s: Any) => {
       |                       if !closingSent then _ws match
       |                         case ws if ws != null =>
       |                           ws.sendBinary(ByteBuffer.wrap(s.toString.getBytes("ISO-8859-1")), true)
       |                         case _ => ()
       |                       () }),
       |    "close"       -> ((args: Any) => {
       |                       if !closingSent then
       |                         closingSent = true
       |                         _ws match
       |                           case ws if ws != null =>
       |                             args match
       |                               case ()             => ws.sendClose(1000, "")
       |                               case code: Int      => ws.sendClose(code, "")
       |                               case (code: Int, r: String) => ws.sendClose(code, r)
       |                               case _              => ws.sendClose(1000, "")
       |                           case _ => doClose()
       |                       () }),
       |    "onMessage"   -> ((cb: Any) => { onMessageCb = Some(cb); () }),
       |    "onClose"     -> ((cb: Any) => { onCloseCbRef.set(cb); () }),
       |    "ping"        -> ((payload: Any) => {
       |                       _ws match
       |                         case ws if ws != null =>
       |                           payload match
       |                             case s: String => ws.sendPing(ByteBuffer.wrap(s.getBytes("ISO-8859-1")))
       |                             case _         => ws.sendPing(ByteBuffer.allocate(0))
       |                         case _ => ()
       |                       () }),
       |    "onPong"      -> ((cb: Any) => { onPongCb = Some(cb); () }),
       |    "recv"        -> (() => {
       |                       val v = recvQueue.take()
       |                       if v == null then None else Some(v) }),
       |    "isClosed"    -> (() => closingSent)
       |  )
       |
       |def wsConnect(url: String, headers: Map[String, String] = Map.empty, protocols: List[String] = Nil)(handler: Map[String, Any] => Any): Any =
       |  val sess = _WsClientConn(url, headers, protocols)
       |  sess.connect()
       |  handler(sess.wsMap)
       |  sess.awaitClose()
       |  ()
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
       |// ── Built-in `Async` effect + default `runAsync` handler ────────────────
       |//
       |// Same single-threaded semantics as the interpreter and JS Node target:
       |// thunks passed to `async` / `parallel` execute immediately (so output
       |// is byte-identical across all three backends), `delay` blocks the
       |// calling thread via Thread.sleep, `await` unwraps the cached value.
       |// Real-thread parallelism is a future handler swap away.
       |
       |object Async:
       |  def delay(ms: Int): Any           = _perform("Async", "delay",    ms)
       |  def async(thunk: () => Any): Any  = _perform("Async", "async",    thunk)
       |  def await(fut: Any): Any          = _perform("Async", "await",    fut)
       |  def parallel(thunks: List[() => Any]): Any =
       |    _perform("Async", "parallel", thunks)
       |
       |case class Future(value: Any)
       |
       |// ── CPS-aware collection helpers (sequence Free callbacks) ──────────
       |//
       |// In CPS-emitted bodies the receiver of `xs.map(fn)` is typed `Any`
       |// (the Free monad's value carrier), so Scala can't resolve `.map`
       |// statically.  `_dispatch` runs the method at runtime — for HOFs
       |// it routes through `_seq*` helpers that thread per-element Free
       |// results into a single sequenced Free, matching the interpreter's
       |// `Computation.sequence` semantics.  Pure callbacks short-circuit
       |// (no Free anywhere → return the plain array).
       |
       |def _isFree(c: Any): Boolean = c.isInstanceOf[_Computation]
       |
       |def _seq(comps: List[Any]): Any =
       |  if !comps.exists(_isFree) then comps
       |  else
       |    def loop(i: Int, acc: List[Any]): Any =
       |      if i == comps.length then acc
       |      else _bind(comps(i), (v: Any) => loop(i + 1, acc :+ v))
       |    loop(0, Nil)
       |
       |def _seqMap(xs: List[Any], fn: Any => Any): Any =
       |  _seq(xs.map(fn))
       |
       |def _seqFlatMap(xs: List[Any], fn: Any => Any): Any =
       |  val s = _seqMap(xs, fn)
       |  s match
       |    case c: _Computation =>
       |      _bind(c, (rs: Any) => rs.asInstanceOf[List[Any]].flatMap {
       |        case ys: List[_] => ys.asInstanceOf[List[Any]]
       |        case v           => List(v)
       |      })
       |    case rs: List[_] => rs.asInstanceOf[List[Any]].flatMap {
       |      case ys: List[_] => ys.asInstanceOf[List[Any]]
       |      case v           => List(v)
       |    }
       |    case _ => s
       |
       |def _seqFilter(xs: List[Any], fn: Any => Any, neg: Boolean): Any =
       |  val flags = xs.map(fn)
       |  val pick = (bs: List[Any]) => xs.zip(bs).collect {
       |    case (x, b: Boolean) if (if neg then !b else b) => x
       |  }
       |  _seq(flags) match
       |    case c: _Computation => _bind(c, (bs: Any) => pick(bs.asInstanceOf[List[Any]]))
       |    case bs: List[_]     => pick(bs.asInstanceOf[List[Any]])
       |    case other           => other
       |
       |def _seqForeach(xs: List[Any], fn: Any => Any): Any =
       |  _seq(xs.map(fn)) match
       |    case c: _Computation => _bind(c, (_: Any) => ())
       |    case _               => ()
       |
       |def _seqExists(xs: List[Any], fn: Any => Any): Any =
       |  _seq(xs.map(fn)) match
       |    case c: _Computation => _bind(c, (bs: Any) =>
       |      bs.asInstanceOf[List[Any]].exists { case b: Boolean => b; case _ => false })
       |    case bs: List[_]     =>
       |      bs.asInstanceOf[List[Any]].exists { case b: Boolean => b; case _ => false }
       |    case _ => false
       |
       |def _seqForall(xs: List[Any], fn: Any => Any): Any =
       |  _seq(xs.map(fn)) match
       |    case c: _Computation => _bind(c, (bs: Any) =>
       |      bs.asInstanceOf[List[Any]].forall { case b: Boolean => b; case _ => false })
       |    case bs: List[_]     =>
       |      bs.asInstanceOf[List[Any]].forall { case b: Boolean => b; case _ => false }
       |    case _ => true
       |
       |def _seqCount(xs: List[Any], fn: Any => Any): Any =
       |  _seq(xs.map(fn)) match
       |    case c: _Computation => _bind(c, (bs: Any) =>
       |      bs.asInstanceOf[List[Any]].count { case b: Boolean => b; case _ => false })
       |    case bs: List[_]     =>
       |      bs.asInstanceOf[List[Any]].count { case b: Boolean => b; case _ => false }
       |    case _ => 0
       |
       |def _seqFind(xs: List[Any], fn: Any => Any): Any =
       |  val flags = xs.map(fn)
       |  val pick  = (bs: List[Any]) =>
       |    val i = bs.indexWhere { case b: Boolean => b; case _ => false }
       |    if i < 0 then None else Some(xs(i))
       |  _seq(flags) match
       |    case c: _Computation => _bind(c, (bs: Any) => pick(bs.asInstanceOf[List[Any]]))
       |    case bs: List[_]     => pick(bs.asInstanceOf[List[Any]])
       |    case _               => None
       |
       |def _seqFoldLeft(xs: List[Any], init: Any, fn: (Any, Any) => Any): Any =
       |  def loop(i: Int, acc: Any): Any =
       |    if i == xs.length then acc
       |    else
       |      val next = fn(acc, xs(i))
       |      next match
       |        case c: _Computation => _bind(c, (v: Any) => loop(i + 1, v))
       |        case v               => loop(i + 1, v)
       |  loop(0, init)
       |
       |/** Runtime method dispatcher used in CPS contexts where the receiver
       | *  is statically `Any`.  Covers the collection HOFs that need
       | *  Free-aware sequencing plus the common direct methods used inside
       | *  `runAsync`/`handle` bodies.  Methods we don't know about fall
       | *  through to Java reflection so a typo at the call site surfaces
       | *  as the same NoSuchMethod we'd get with a direct call. */
       |def _dispatch(obj: Any, method: String, args: List[Any]): Any =
       |  (obj, method, args) match
       |    // List HOFs — CPS-aware
       |    case (xs: List[_], "map",       List(fn))   => _seqMap     (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "flatMap",   List(fn))   => _seqFlatMap (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "filter",    List(fn))   => _seqFilter  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any], neg = false)
       |    case (xs: List[_], "filterNot", List(fn))   => _seqFilter  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any], neg = true)
       |    case (xs: List[_], "foreach",   List(fn))   => _seqForeach (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "exists",    List(fn))   => _seqExists  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "forall",    List(fn))   => _seqForall  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "find",      List(fn))   => _seqFind    (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "count",     List(fn))   => _seqCount   (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "foldLeft",  List(init)) =>
       |      // Curried in Scala: foldLeft(init)(fn) — return the fn-taker.
       |      (fn: ((Any, Any) => Any)) => _seqFoldLeft(xs.asInstanceOf[List[Any]], init, fn)
       |    // Direct List methods we use commonly inside CPS bodies
       |    case (xs: List[_], "head",     Nil)       => xs.head
       |    case (xs: List[_], "tail",     Nil)       => xs.tail
       |    case (xs: List[_], "size",     Nil)       => xs.size
       |    case (xs: List[_], "length",   Nil)       => xs.length
       |    case (xs: List[_], "isEmpty",  Nil)       => xs.isEmpty
       |    case (xs: List[_], "nonEmpty", Nil)       => xs.nonEmpty
       |    case (xs: List[_], "reverse",  Nil)       => xs.reverse
       |    case (xs: List[_], "mkString", Nil)       => xs.mkString
       |    case (xs: List[_], "mkString", List(s: String)) => xs.mkString(s)
       |    case (xs: List[_], "sum",      Nil)       => xs.asInstanceOf[List[Any]].foldLeft(0: Any)((a, b) => _binOp("+", a, b))
       |    case (s: String,   "length",   Nil)       => s.length
       |    case (s: String,   "size",     Nil)       => s.length
       |    case (s: String,   "toInt",    Nil)       => s.toInt
       |    case (s: String,   "toLong",   Nil)       => s.toLong
       |    case (s: String,   "toDouble", Nil)       => s.toDouble
       |    // Option — `getOrElse` takes a by-name param which Java
       |    // reflection can't resolve directly from a String arg.
       |    case (opt: Option[_], "get",        Nil)       => opt.get
       |    case (opt: Option[_], "getOrElse",  List(d))   => opt.getOrElse(d)
       |    case (opt: Option[_], "isDefined",  Nil)       => opt.isDefined
       |    case (opt: Option[_], "isEmpty",    Nil)       => opt.isEmpty
       |    case (opt: Option[_], "nonEmpty",   Nil)       => opt.nonEmpty
       |    case (opt: Option[_], "map",        List(fn))  =>
       |      opt.asInstanceOf[Option[Any]].map(fn.asInstanceOf[Any => Any])
       |    case (opt: Option[_], "flatMap",    List(fn))  =>
       |      opt.asInstanceOf[Option[Any]].flatMap(x => fn.asInstanceOf[Any => Option[Any]](x))
       |    case (opt: Option[_], "foreach",    List(fn))  =>
       |      opt.asInstanceOf[Option[Any]].foreach(fn.asInstanceOf[Any => Any]); ()
       |    // Fallback: try Java reflection so non-HOF method calls still work
       |    case _ =>
       |      val cls = obj.getClass
       |      val ms  = cls.getMethods.filter(m =>
       |        m.getName == method && m.getParameterCount == args.length)
       |      if ms.isEmpty then
       |        sys.error(s"No method '$method' on ${cls.getName} with ${args.length} arg(s)")
       |      val boxed: Array[Object] = args.map(_.asInstanceOf[AnyRef]).toArray
       |      ms.head.invoke(obj, boxed*)
       |
       |def _runAsync(bodyThunk: () => Any): Any =
       |  def dispatch(op: String, args: List[Any], resume: Any => Any): Any = op match
       |    case "delay" =>
       |      val ms = args(0).asInstanceOf[Int]
       |      if ms > 0 then Thread.sleep(ms.toLong)
       |      resume(())
       |    case "async" =>
       |      val thunk = args(0).asInstanceOf[() => Any]
       |      resume(Future(interp(thunk())))
       |    case "await" =>
       |      val fut = args(0).asInstanceOf[Future]
       |      resume(fut.value)
       |    case "parallel" =>
       |      val thunks = args(0).asInstanceOf[List[() => Any]]
       |      resume(thunks.map(t => interp(t())))
       |    case _ => sys.error("Unknown Async operation: " + op)
       |  def interp(initial: Any): Any =
       |    var current: Any = initial
       |    while true do
       |      current match
       |        case _Perform("Async", op, args) =>
       |          val resume: Any => Any = (v: Any) => v
       |          current = dispatch(op, args, resume)
       |        case _Perform(_, _, _) => return current
       |        case _FlatMap(sub, f) => sub match
       |          case _Perform("Async", op, args) =>
       |            val fn = f.asInstanceOf[Any => Any]
       |            val resume: Any => Any = (v: Any) => interp(fn(v))
       |            current = dispatch(op, args, resume)
       |          case _Perform(_, _, _) =>
       |            val fn = f.asInstanceOf[Any => Any]
       |            return _FlatMap(sub, (v: Any) => interp(fn(v)))
       |          case _FlatMap(s2, g) =>
       |            current = _FlatMap(s2,
       |              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |          case v =>
       |            current = f.asInstanceOf[Any => Any](v)
       |        case v => return v
       |    throw new RuntimeException("unreachable")
       |  interp(bodyThunk())
       |
       |// ── runAsyncParallel: real-thread alternate handler ────────────────────
       |//
       |// Same `Async.*` API as `runAsync` but `async` / `parallel` submit
       |// their thunks to an `ExecutorService`.  `await` blocks the calling
       |// thread on the future; `parallel` waits on each future in declared
       |// order so the result list mirrors input order regardless of
       |// completion order — value-deterministic code retains byte-identical
       |// output across the single- and parallel-handler variants.
       |
       |val _parallelFutures =
       |  new java.util.concurrent.ConcurrentHashMap[Long, java.util.concurrent.Future[Any]]()
       |val _parallelFutureSeq = new java.util.concurrent.atomic.AtomicLong(0L)
       |def _freshFutureId(): Long = _parallelFutureSeq.incrementAndGet()
       |
       |def _runAsyncParallel(bodyThunk: () => Any): Any =
       |  val _ex = java.util.concurrent.Executors.newCachedThreadPool()
       |  try
       |    def dispatch(op: String, args: List[Any], resume: Any => Any): Any = op match
       |      case "delay" =>
       |        val ms = args(0).asInstanceOf[Int]
       |        if ms > 0 then Thread.sleep(ms.toLong)
       |        resume(())
       |      case "async" =>
       |        val thunk = args(0).asInstanceOf[() => Any]
       |        val fut: java.util.concurrent.Future[Any] = _ex.submit(
       |          new java.util.concurrent.Callable[Any] {
       |            def call(): Any = interp(thunk())
       |          })
       |        val fid = _freshFutureId()
       |        _parallelFutures.put(fid, fut)
       |        resume(Future(("_parId", fid)))
       |      case "await" =>
       |        args(0) match
       |          case Future(("_parId", fid: Long)) =>
       |            val fut = _parallelFutures.remove(fid)
       |            if fut == null then sys.error("Async.await: stale Future")
       |            resume(fut.get())
       |          case Future(v) => resume(v)
       |          case _         => sys.error("Async.await(future)")
       |      case "parallel" =>
       |        val thunks = args(0).asInstanceOf[List[() => Any]]
       |        val futs = thunks.map { t =>
       |          _ex.submit(new java.util.concurrent.Callable[Any] {
       |            def call(): Any = interp(t())
       |          })
       |        }
       |        resume(futs.map(_.get()))
       |      case _ => sys.error("Unknown Async operation: " + op)
       |    def interp(initial: Any): Any =
       |      var current: Any = initial
       |      while true do
       |        current match
       |          case _Perform("Async", op, args) =>
       |            current = dispatch(op, args, (v: Any) => v)
       |          case _Perform(_, _, _) => return current
       |          case _FlatMap(sub, f) => sub match
       |            case _Perform("Async", op, args) =>
       |              val fn = f.asInstanceOf[Any => Any]
       |              current = dispatch(op, args, (v: Any) => interp(fn(v)))
       |            case _Perform(_, _, _) =>
       |              val fn = f.asInstanceOf[Any => Any]
       |              return _FlatMap(sub, (v: Any) => interp(fn(v)))
       |            case _FlatMap(s2, g) =>
       |              current = _FlatMap(s2,
       |                (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |            case v =>
       |              current = f.asInstanceOf[Any => Any](v)
       |          case v => return v
       |      throw new RuntimeException("unreachable")
       |    interp(bodyThunk())
       |  finally _ex.shutdown()
       |
       |// ── Storage: built-in key-value effect ─────────────────────────────────
       |//
       |// `Storage.{get,put,remove,has,keys}` produce `_Perform("Storage",
       |// op, args)` nodes; `_runStorage(bodyThunk, path)` is the handler.
       |// When `path` is non-null it hydrates from / flushes to that JSON
       |// file on every mutation (file-backed); otherwise the map stays
       |// in-process and is discarded at scope exit (ephemeral mode).
       |
       |def _storageLoad(path: String, state: scala.collection.mutable.LinkedHashMap[String, String]): Unit =
       |  val p = java.nio.file.Paths.get(path)
       |  if java.nio.file.Files.exists(p) then
       |    val src = java.nio.file.Files.readString(p).trim
       |    if src.startsWith("{") && src.endsWith("}") then
       |      var i = 1
       |      val end = src.length - 1
       |      def skipWs(): Unit = while i < end && src.charAt(i).isWhitespace do i += 1
       |      def readStr(): String =
       |        if i >= end || src.charAt(i) != '"' then sys.error(s"Storage JSON: expected string at $i")
       |        i += 1
       |        val sb = new StringBuilder
       |        while i < end && src.charAt(i) != '"' do
       |          if src.charAt(i) == '\\' && i + 1 < end then
       |            src.charAt(i + 1) match
       |              case '"'  => sb.append('"');  i += 2
       |              case '\\' => sb.append('\\'); i += 2
       |              case 'n'  => sb.append('\n'); i += 2
       |              case 't'  => sb.append('\t'); i += 2
       |              case 'r'  => sb.append('\r'); i += 2
       |              case c    => sb.append(c);    i += 2
       |          else { sb.append(src.charAt(i)); i += 1 }
       |        i += 1
       |        sb.toString
       |      skipWs()
       |      while i < end do
       |        val k = readStr(); skipWs()
       |        if i >= end || src.charAt(i) != ':' then sys.error("Storage JSON: expected ':'")
       |        i += 1; skipWs()
       |        val v = readStr(); skipWs()
       |        state(k) = v
       |        if i < end && src.charAt(i) == ',' then i += 1
       |        skipWs()
       |
       |def _storageSave(path: String, state: scala.collection.mutable.LinkedHashMap[String, String]): Unit =
       |  def esc(s: String): String =
       |    val sb = new StringBuilder
       |    sb.append('"')
       |    s.foreach {
       |      case '"'  => sb.append("\\\"")
       |      case '\\' => sb.append("\\\\")
       |      case '\n' => sb.append("\\n")
       |      case '\r' => sb.append("\\r")
       |      case '\t' => sb.append("\\t")
       |      case c    => sb.append(c)
       |    }
       |    sb.append('"').toString
       |  val body = state.iterator.map { case (k, v) => esc(k) + ":" + esc(v) }.mkString(",")
       |  java.nio.file.Files.writeString(java.nio.file.Paths.get(path), "{" + body + "}")
       |
       |def _runStorage(bodyThunk: () => Any, path: String): Any =
       |  val state = scala.collection.mutable.LinkedHashMap.empty[String, String]
       |  if path != null then _storageLoad(path, state)
       |  def flush(): Unit = if path != null then _storageSave(path, state)
       |  def dispatch(op: String, args: List[Any], resume: Any => Any): Any = op match
       |    case "get" =>
       |      val k = args(0).asInstanceOf[String]
       |      resume(if state.contains(k) then Some(state(k)) else None)
       |    case "put" =>
       |      val k = args(0).asInstanceOf[String]
       |      state(k) = _show(args(1))
       |      flush()
       |      resume(())
       |    case "remove" =>
       |      state.remove(args(0).asInstanceOf[String])
       |      flush()
       |      resume(())
       |    case "has" => resume(state.contains(args(0).asInstanceOf[String]))
       |    case "keys" => resume(state.keys.toList)
       |    case _ => sys.error("Unknown Storage operation: " + op)
       |  def interp(initial: Any): Any =
       |    var current: Any = initial
       |    while true do
       |      current match
       |        case _Perform("Storage", op, args) =>
       |          current = dispatch(op, args, (v: Any) => v)
       |        case _Perform(_, _, _) => return current
       |        case _FlatMap(sub, f) => sub match
       |          case _Perform("Storage", op, args) =>
       |            val fn = f.asInstanceOf[Any => Any]
       |            current = dispatch(op, args, (v: Any) => interp(fn(v)))
       |          case _Perform(_, _, _) =>
       |            val fn = f.asInstanceOf[Any => Any]
       |            return _FlatMap(sub, (v: Any) => interp(fn(v)))
       |          case _FlatMap(s2, g) =>
       |            current = _FlatMap(s2,
       |              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |          case v =>
       |            current = f.asInstanceOf[Any => Any](v)
       |        case v => return v
       |    throw new RuntimeException("unreachable")
       |  interp(bodyThunk())
       |
       |// ── v1.6 Actors — Phase 1 cooperative scheduler ────────────────────────
       |//
       |// Same Computation / Free-Monad walk as `_runAsync` but the outer
       |// loop interleaves multiple actors.  Mailboxes are `ArrayDeque`s;
       |// blocked-on-receive state lives on each actor along with the
       |// captured continuation.  Quiescence with timeout-armed receives
       |// sleeps until the earliest deadline and resumes that actor with
       |// `None`.  Single-threaded for parity with the interpreter and
       |// JsGen — a Loom variant can swap the scheduler later without
       |// changing the API surface.
       |
       |// Phase 3: nodeId="" means local (backward-compatible default)
       |case class _Pid(nodeId: String, localId: Long)
       |// v1.6 Phase 2 — supervision message types visible to ScalaScript code
       |case class Exit(from: Any, reason: Any)
       |case class Down(ref: Any, from: Any, reason: Any)
       |case object noproc
       |
       |/** Adapter: a partial-function literal becomes a total
       | *  `Any => Option[Any]`.  Used by emitReceiveMatcher so the
       | *  generated source doesn't fight Scala 3's `(x) => x match`
       | *  postfix-match precedence trap. */
       |def _pfToFun(pf: PartialFunction[Any, Option[Any]]): Any => Option[Any] =
       |  (msg: Any) => pf.applyOrElse(msg, (_: Any) => None)
       |
       |val _receiveSpecs =
       |  new java.util.concurrent.ConcurrentHashMap[Long, Any => Option[Any]]()
       |val _receiveSpecSeq = new java.util.concurrent.atomic.AtomicLong(0L)
       |def _registerReceive(matcher: Any => Option[Any]): Long =
       |  val id = _receiveSpecSeq.incrementAndGet()
       |  _receiveSpecs.put(id, matcher)
       |  id
       |
       |object Actor:
       |  def spawn(thunk: () => Any): Any              = _perform("Actor", "spawn",       thunk)
       |  def self(): Any                               = _perform("Actor", "self")
       |  def send(pid: Any, msg: Any): Any             = _perform("Actor", "send",        pid, msg)
       |  def exit(pid: Any, reason: Any): Any          = _perform("Actor", "exit",        pid, reason)
       |  def receive_(specId: Long): Any               = _perform("Actor", "receive",     specId)
       |  def receive_t(specId: Long, ms: Any): Any     = _perform("Actor", "receive_t",   specId, ms)
       |  // v1.6 Phase 2 — supervision
       |  def link(pid: Any): Any                       = _perform("Actor", "link",        pid)
       |  def monitor(pid: Any): Any                    = _perform("Actor", "monitor",     pid)
       |  def demonitor(ref: Any): Any                  = _perform("Actor", "demonitor",   ref)
       |  def trapExit(b: Any): Any                     = _perform("Actor", "trapExit",    b)
       |  // v1.6 Phase 3 — distributed
       |  def startNode(nodeId: Any): Any               = _perform("Actor", "startNode",   nodeId)
       |  def connectNode(url: Any, tok: Any = ""): Any = _perform("Actor", "connectNode", url, tok)
       |  def register(name: Any, pid: Any): Any        = _perform("Actor", "register",    name, pid)
       |  def whereis(name: Any): Any                   = _perform("Actor", "whereis",     name)
       |
       |class _ActorState:
       |  val mailbox = scala.collection.mutable.ArrayDeque.empty[Any]
       |  var pending: Any = null
       |  // (matcher, k, deadline?, wrapSome)
       |  var blocked: (Any => Option[Any], Any => Any, Option[Long], Boolean) = null
       |
       |def _runActors(bodyThunk: () => Any): Any =
       |  val actors    = scala.collection.mutable.LongMap.empty[_ActorState]
       |  // Phase 2 supervision state
       |  val links     = scala.collection.mutable.LongMap.empty[scala.collection.mutable.Set[Long]]
       |  val monitors  = scala.collection.mutable.LongMap.empty[scala.collection.mutable.Map[Long, Long]]
       |  val trapExitM = scala.collection.mutable.LongMap.empty[Boolean]
       |  var nextMonRef: Long = 0L
       |  // Phase 3 distributed state
       |  var _localNodeId: String = ""
       |  val _nodeRegistry = new java.util.concurrent.ConcurrentHashMap[String, Long]()
       |  val _peerChannels = new java.util.concurrent.ConcurrentHashMap[String, String => Unit]()
       |  val _remoteInbox  = new java.util.concurrent.ConcurrentLinkedQueue[(Long, Any)]()
       |
       |  val ready  = scala.collection.mutable.ArrayDeque.empty[Long]
       |  var nextId: Long = 0L
       |  var rootResult: Any = ()
       |
       |  def spawnActor(thunk: () => Any): Long =
       |    val id = nextId
       |    nextId += 1
       |    val st = new _ActorState
       |    st.pending = thunk()
       |    actors.put(id, st)
       |    ready.append(id)
       |    id
       |
       |  val rootId = spawnActor(bodyThunk)
       |
       |  def tryDeliver(state: _ActorState, matcher: Any => Option[Any], wrapSome: Boolean): Option[Any] =
       |    while state.mailbox.nonEmpty do
       |      val msg = state.mailbox.head
       |      matcher(msg) match
       |        case Some(bodyC) =>
       |          state.mailbox.removeHead()
       |          if wrapSome then
       |            return Some(_FlatMap(bodyC, (v: Any) => Some(v)))
       |          else
       |            return Some(bodyC)
       |        case None =>
       |          state.mailbox.removeHead()
       |    None
       |
       |  def tryWakeBlocked(id: Long): Unit =
       |    actors.get(id).foreach { st =>
       |      if st.blocked != null then
       |        val b = st.blocked
       |        tryDeliver(st, b._1, b._4) match
       |          case Some(c) =>
       |            st.pending = _FlatMap(c, b._2)
       |            st.blocked = null
       |            ready.append(id)
       |          case None => ()
       |    }
       |
       |  def killActor(targetId: Long, reason: Any): Unit =
       |    if !actors.contains(targetId) then return
       |    actors.remove(targetId)
       |    trapExitM.remove(targetId)
       |    val deadPid = _Pid("", targetId)
       |    links.remove(targetId).foreach { linkedSet =>
       |      linkedSet.foreach { linkedId =>
       |        links.get(linkedId).foreach(_.remove(targetId))
       |        if trapExitM.getOrElse(linkedId, false) then
       |          actors.get(linkedId).foreach { st =>
       |            st.mailbox.append(Exit(deadPid, reason))
       |            tryWakeBlocked(linkedId)
       |          }
       |        else
       |          killActor(linkedId, reason)
       |      }
       |    }
       |    monitors.remove(targetId).foreach { monMap =>
       |      monMap.foreach { (monRef, observerId) =>
       |        actors.get(observerId).foreach { st =>
       |          st.mailbox.append(Down(monRef, deadPid, reason))
       |          tryWakeBlocked(observerId)
       |        }
       |      }
       |    }
       |
       |  def handleActorOp(id: Long, state: _ActorState, op: String, args: List[Any], k: Any => Any): Either[Unit, Any] = op match
       |    case "spawn" =>
       |      val thunk = args(0).asInstanceOf[() => Any]
       |      val childId = spawnActor(thunk)
       |      Right(k(_Pid(_localNodeId, childId)))
       |    case "self" => Right(k(_Pid(_localNodeId, id)))
       |    case "send" =>
       |      args(0) match
       |        case _Pid(pidNode, targetId) =>
       |          if pidNode.nonEmpty && pidNode != _localNodeId then
       |            // Remote send — serialize and enqueue to peer channel
       |            Option(_peerChannels.get(pidNode)).foreach { sendFn =>
       |              val body = _serializeValue(args(1))
       |              sendFn(_mkMsgEnv(_localNodeId, id, pidNode, targetId, body))
       |            }
       |          else
       |            actors.get(targetId).foreach { ts =>
       |              ts.mailbox.append(args(1))
       |              if ts.blocked != null then
       |                val b = ts.blocked
       |                tryDeliver(ts, b._1, b._4) match
       |                  case Some(c) =>
       |                    ts.pending = _FlatMap(c, b._2)
       |                    ts.blocked = null
       |                    ready.append(targetId)
       |                  case None => ()
       |            }
       |        case _ => ()
       |      Right(k(()))
       |    case "exit" =>
       |      args(0) match
       |        case _Pid(_, targetId) => killActor(targetId, args(1))
       |        case _                 => ()
       |      if actors.contains(id) then Right(k(())) else Left(())
       |    case "receive" =>
       |      val matcher = _receiveSpecs.get(args(0).asInstanceOf[Long])
       |      tryDeliver(state, matcher, false) match
       |        case Some(c) => Right(_FlatMap(c, k))
       |        case None =>
       |          state.blocked = (matcher, k, None, false)
       |          Left(())
       |    case "receive_t" =>
       |      val matcher = _receiveSpecs.get(args(0).asInstanceOf[Long])
       |      val ms = args(1) match
       |        case n: Int  => n.toLong
       |        case n: Long => n
       |        case _       => 0L
       |      tryDeliver(state, matcher, true) match
       |        case Some(c) => Right(_FlatMap(c, k))
       |        case None =>
       |          state.blocked = (matcher, k, Some(System.currentTimeMillis() + ms), true)
       |          Left(())
       |    // ── v1.6 Phase 2 — supervision ─────────────────────────────────────
       |    case "link" =>
       |      args(0) match
       |        case _Pid(_, targetId) =>
       |          if actors.contains(targetId) then
       |            links.getOrElseUpdate(id,       scala.collection.mutable.Set.empty) += targetId
       |            links.getOrElseUpdate(targetId, scala.collection.mutable.Set.empty) += id
       |          else
       |            if trapExitM.getOrElse(id, false) then
       |              actors.get(id).foreach(_.mailbox.append(Exit(_Pid("", targetId), noproc)))
       |            else
       |              killActor(id, noproc)
       |        case _ => ()
       |      if actors.contains(id) then Right(k(())) else Left(())
       |    case "monitor" =>
       |      args(0) match
       |        case _Pid(_, targetId) =>
       |          val monRef = nextMonRef; nextMonRef += 1
       |          if actors.contains(targetId) then
       |            monitors.getOrElseUpdate(targetId, scala.collection.mutable.Map.empty)(monRef) = id
       |          else
       |            actors.get(id).foreach { st =>
       |              st.mailbox.append(Down(monRef, _Pid("", targetId), noproc))
       |              tryWakeBlocked(id)
       |            }
       |          Right(k(monRef))
       |        case _ => Right(k(-1L))
       |    case "demonitor" =>
       |      val monRef = args(0).asInstanceOf[Long]
       |      monitors.foreachEntry((_, m) => m.remove(monRef))
       |      Right(k(()))
       |    case "trapExit" =>
       |      trapExitM(id) = args(0) match
       |        case b: Boolean => b
       |        case _          => args(0) == true
       |      Right(k(()))
       |    // ── Phase 3 — distributed ────────────────────────────────────────────
       |    case "startNode" =>
       |      _localNodeId = args(0).toString
       |      Right(k(()))
       |    case "connectNode" =>
       |      // WS peer connection stub — full implementation deferred to Phase 3 WS integration
       |      Right(k(()))
       |    case "register" =>
       |      val name = args(0).toString
       |      val localId = args(1) match { case _Pid(_, lid) => lid; case _ => id }
       |      _nodeRegistry.put(name, localId)
       |      Right(k(()))
       |    case "whereis" =>
       |      val name = args(0).toString
       |      val result =
       |        if _nodeRegistry.containsKey(name) && actors.contains(_nodeRegistry.get(name)) then
       |          Some(_Pid(_localNodeId, _nodeRegistry.get(name)))
       |        else
       |          None
       |      Right(k(result))
       |    case other => sys.error("Unknown Actor op: " + other)
       |
       |  def stepActor(id: Long, initial: Any): Unit =
       |    var current: Any = initial
       |    while true do
       |      current match
       |        case _Perform("Actor", op, args) =>
       |          handleActorOp(id, actors(id), op, args, (v: Any) => v) match
       |            case Right(next) => current = next
       |            case Left(_)     => return
       |        case _Perform(eff, op, _) =>
       |          throw new RuntimeException("Unhandled effect inside actor: " + eff + "." + op)
       |        case _FlatMap(sub, f) => sub match
       |          case _Perform("Actor", op, args) =>
       |            handleActorOp(id, actors(id), op, args, f.asInstanceOf[Any => Any]) match
       |              case Right(next) => current = next
       |              case Left(_)     => return
       |          case _Perform(eff, op, _) =>
       |            throw new RuntimeException("Unhandled effect inside actor: " + eff + "." + op)
       |          case _FlatMap(s2, g) =>
       |            current = _FlatMap(s2,
       |              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
       |          case v =>
       |            current = f.asInstanceOf[Any => Any](v)
       |        case v =>
       |          if id == rootId then rootResult = v
       |          // Fire monitors with reason "normal" on natural completion.
       |          val myPid = _Pid(_localNodeId, id)
       |          monitors.remove(id).foreach { monMap =>
       |            monMap.foreach { (monRef, observerId) =>
       |              actors.get(observerId).foreach { st =>
       |                st.mailbox.append(Down(monRef, myPid, "normal"))
       |                tryWakeBlocked(observerId)
       |              }
       |            }
       |          }
       |          links.remove(id).foreach { linkedSet =>
       |            linkedSet.foreach { linkedId =>
       |              links.get(linkedId).foreach(_.remove(id))
       |            }
       |          }
       |          actors.remove(id)
       |          return
       |
       |  def _mkMsgEnv(fromNode: String, fromId: Long, toNode: String, toId: Long, body: String): String =
       |    "{\"t\":\"msg\",\"to\":{\"nodeId\":" + _jstr(toNode) + ",\"localId\":" + toId +
       |    "},\"from\":{\"nodeId\":" + _jstr(fromNode) + ",\"localId\":" + fromId +
       |    "},\"body\":" + body + "}"
       |
       |  def _serializeValue(v: Any): String = v match
       |    case n: Long    => "{\"$t\":\"i\",\"v\":" + n + "}"
       |    case n: Int     => "{\"$t\":\"i\",\"v\":" + n + "}"
       |    case d: Double  => "{\"$t\":\"d\",\"v\":" + d + "}"
       |    case s: String  => "{\"$t\":\"s\",\"v\":" + _jstr(s) + "}"
       |    case b: Boolean => "{\"$t\":\"b\",\"v\":" + b + "}"
       |    case ()         => "{\"$t\":\"u\"}"
       |    case _Pid(nId, lId) => "{\"$t\":\"pid\",\"n\":" + _jstr(nId) + ",\"id\":" + lId + "}"
       |    case xs: List[?] => "{\"$t\":\"l\",\"v\":[" + xs.map(_serializeValue).mkString(",") + "]}"
       |    case _          => "{\"$t\":\"s\",\"v\":" + _jstr(v.toString) + "}"
       |
       |  def _jstr(s: String): String =
       |    val sb = new StringBuilder(s.length + 2).append('"')
       |    s.foreach { case '"' => sb.append("\\\""); case '\\' => sb.append("\\\\")
       |                case '\n' => sb.append("\\n"); case c => sb.append(c) }
       |    sb.append('"').toString
       |
       |  val _isDistributed = _localNodeId.nonEmpty || !_peerChannels.isEmpty
       |
       |  while ready.nonEmpty ||
       |        actors.exists { (_, st) => st != null && st.blocked != null && st.blocked._3.isDefined } ||
       |        (_isDistributed && actors.nonEmpty && actors.exists { (_, st) => st != null && st.blocked != null })
       |  do
       |    // Drain remote inbox
       |    while !_remoteInbox.isEmpty do
       |      val (targetId, msg) = _remoteInbox.poll()
       |      actors.get(targetId).foreach { ts =>
       |        ts.mailbox.append(msg)
       |        tryWakeBlocked(targetId)
       |      }
       |    if ready.isEmpty then
       |      val earliest = actors.iterator.collect {
       |        case (aid, st) if st != null && st.blocked != null && st.blocked._3.isDefined =>
       |          (aid, st.blocked._3.get)
       |      }.toList.minByOption(_._2)
       |      earliest match
       |        case Some((aid, deadline)) =>
       |          val sleepFor = deadline - System.currentTimeMillis()
       |          if sleepFor > 0 then
       |            try Thread.sleep(sleepFor)
       |            catch case _: InterruptedException => ()
       |          val st = actors(aid)
       |          val (_, k, _, _) = st.blocked
       |          st.pending = k(None)
       |          st.blocked = null
       |          ready.append(aid)
       |        case None =>
       |          if _isDistributed && actors.exists { (_, st) => st != null && st.blocked != null } then
       |            try Thread.sleep(30)
       |            catch case _: InterruptedException => ()
       |    else
       |      val id = ready.removeHead()
       |      actors.get(id).foreach { st =>
       |        if st.pending != null then
       |          val initial = st.pending
       |          st.pending = null
       |          stepActor(id, initial)
       |      }
       |
       |  rootResult
       |
       |""".stripMargin

  /** Reactive runtime — same push-model as the interpreter and JsGen.
   *  Signals are mutable cells with a subscriber set; reads inside an
   *  active effect / computed register a mutual subscription; writes
   *  queue subscribers into a LinkedHashSet and a scheduled flush
   *  drains it so each effect runs at most once per synchronous
   *  transaction (dedupes the diamond). */
  private val reactiveRuntime: String =
    """|
       |// ── Reactive signals (fine-grained reactivity) ─────────────────────
       |class _Signal(var value: Any, val subs: scala.collection.mutable.HashSet[Long])
       |class _Effect(val thunk: () => Any, val deps: scala.collection.mutable.HashSet[Long])
       |
       |val _signals = scala.collection.mutable.HashMap.empty[Long, _Signal]
       |val _effects = scala.collection.mutable.HashMap.empty[Long, _Effect]
       |var _reactiveSeq: Long = 0L
       |val _effectStack = scala.collection.mutable.Stack.empty[Long]
       |val _pendingEffects = scala.collection.mutable.LinkedHashSet.empty[Long]
       |var _reactiveFlushing = false
       |
       |def _freshReactiveId(): Long = { _reactiveSeq += 1; _reactiveSeq }
       |
       |def _signalGet(id: Long): Any =
       |  val s = _signals.getOrElse(id, sys.error("Signal disposed or unknown id"))
       |  if _effectStack.nonEmpty then
       |    val eid = _effectStack.top
       |    s.subs += eid
       |    _effects.get(eid).foreach(_.deps += id)
       |  s.value
       |
       |def _signalSet(id: Long, v: Any): Unit =
       |  val s = _signals.getOrElse(id, sys.error("Signal disposed or unknown id"))
       |  s.value = v
       |  // Skip subscribers currently running — otherwise an effect
       |  // that writes a signal it also reads infinite-loops itself.
       |  s.subs.foreach { eid =>
       |    if !_effectStack.contains(eid) then _pendingEffects += eid
       |  }
       |  if !_reactiveFlushing then _reactiveFlush()
       |
       |def _reactiveFlush(): Unit =
       |  _reactiveFlushing = true
       |  try
       |    while _pendingEffects.nonEmpty do
       |      val eid = _pendingEffects.head
       |      _pendingEffects -= eid
       |      _runEffect(eid)
       |  finally _reactiveFlushing = false
       |
       |def _clearEffectDeps(eid: Long): Unit =
       |  _effects.get(eid).foreach { e =>
       |    e.deps.foreach { sid => _signals.get(sid).foreach(_.subs -= eid) }
       |    e.deps.clear()
       |  }
       |
       |def _runEffect(eid: Long): Unit =
       |  _effects.get(eid).foreach { e =>
       |    _clearEffectDeps(eid)
       |    _effectStack.push(eid)
       |    try e.thunk()
       |    finally _effectStack.pop()
       |  }
       |
       |/** User-visible Signal handle — parameterised on the value type
       | *  so callers get back `count.get: Int` instead of `Any` and the
       | *  Scala typer resolves arithmetic (`count.get * 2`) cleanly. */
       |class Signal[A](val id: Long):
       |  def get: A           = _signalGet(id).asInstanceOf[A]
       |  def set(v: A): Unit  = _signalSet(id, v)
       |  def apply(): A       = get
       |  override def toString: String = s"Signal(${get})"
       |object Signal:
       |  def apply[A](initial: A): Signal[A] =
       |    val id = _freshReactiveId()
       |    _signals(id) = _Signal(initial, scala.collection.mutable.HashSet.empty)
       |    new Signal[A](id)
       |
       |def effect(thunk: => Any): Unit =
       |  val eid = _freshReactiveId()
       |  _effects(eid) = _Effect(() => thunk, scala.collection.mutable.HashSet.empty)
       |  _runEffect(eid)
       |
       |def computed[A](thunk: => A): Signal[A] =
       |  val sid = _freshReactiveId()
       |  val eid = _freshReactiveId()
       |  _signals(sid) = _Signal(null, scala.collection.mutable.HashSet.empty)
       |  val updater: () => Any = () => _signalSet(sid, thunk)
       |  _effects(eid) = _Effect(updater, scala.collection.mutable.HashSet.empty)
       |  _runEffect(eid)
       |  new Signal[A](sid)
       |
       |""".stripMargin
