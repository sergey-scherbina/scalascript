package ssc.bridge

import scala.meta.*
import scala.meta.dialects.Scala3
import ssc.{Term as CT, Const, Def as CDef, Arm, Program}
import ssc.V2PluginRegistry

/** Converts v1 scalameta AST (output of v1 Parser+Typer+Linker) to v2 Core IR.
 *
 *  Entry point: FrontendBridge.convertSource(src: String): Program
 *  Or:          FrontendBridge.convertTrees(trees: List[Tree]): Program
 *
 *  De Bruijn scope: List[String] newest-first.
 *    Local(0) = scope.head  (most recently bound)
 *    Local(i) = scope(i)
 *  Names NOT in scope → Global(name).
 *
 *  Special scope prefixes (same as ssc1c):
 *    "@name"  = cell ref for `var name` (cell.new)
 *    "@@name" = long-cell ref for `var name = <int-lit>` (lcell.new, avoids IntV boxing) */
object FrontendBridge:

  /** Case class / enum field map: className → ordered field names.
   *  Built during first pass over stats; used when resolving field selects.
   *  LinkedHashMap preserves insertion order so fieldIndex returns the index from
   *  whichever case class was registered FIRST (import order) — deterministic. */
  private val fieldRegistry = collection.mutable.LinkedHashMap[String, Vector[String]]()

  /** Lookup field index for a class member field, if known.
   *  Returns the index from the FIRST registered class that contains the field.
   *  Deterministic because fieldRegistry is insertion-ordered (LinkedHashMap). */
  private def fieldIndex(name: String): Option[Int] =
    fieldRegistry.values.collectFirst {
      case fields if fields.contains(name) => fields.indexOf(name)
    }

  /** Lookup field index for a specific class's field. */
  private def fieldIndexOf(className: String, name: String): Option[Int] =
    fieldRegistry.get(className).flatMap { fields =>
      val i = fields.indexOf(name); if i >= 0 then Some(i) else None
    }

  /** Register a case class definition and its fields. */
  private def registerCaseClass(name: String, params: List[Term.Param]): Unit =
    val names = params.map(_.name.value).toVector
    fieldRegistry(name) = names
    ssc.V2PluginRegistry.registerFieldNames(name, names)  // shared with PluginBridge.v2ToV1
    val types = params.map(p => p.decltpe.map(_.syntax).getOrElse("Any")).toVector
    fieldTypeRegistry(name) = types
    val defs = params.map(p => p.default.map(d => convertExpr(d, Nil))).toVector
    if defs.exists(_.isDefined) then
      defaultParams(name) = defs
      defaultParamTerms(name) = (names, params.map(_.default).toVector)

  /** Reset all per-compilation mutable state.  Call between batch examples to prevent
   *  field-registry pollution from one example affecting the next. */
  def resetState(): Unit =
    PluginBridge.clearDbs()
    fieldRegistry.clear()
    // Mirror is a built-in type with known fields at fixed indices.
    fieldRegistry("Mirror") = Vector("label", "elemLabels", "elemTypes")
    fieldTypeRegistry.clear()
    derivedSchemas.clear()
    generalDerives.clear()
    givenRegistry.clear()
    extensionMethods.clear()
    extAccum.clear()
    defaultParams.clear()
    defaultParamTerms.clear()
    defParamNames.clear()
    varargDefs.clear()
    zeroArgDefs.clear()
    curryFirstClauseDefaults.clear()

  /** Parse `databases:` YAML block from front-matter and register JDBC connections.
   *  Format:
   *    databases:
   *      mydb:
   *        url: "jdbc:h2:mem:test"
   *  Registered so `Db.query("mydb", ...)` works in v2. */
  private def parseDatabasesFromFrontmatter(src: String): Unit =
    val noShebang = if src.startsWith("#!/") then src.dropWhile(_ != '\n').drop(1) else src
    if !noShebang.stripLeading().startsWith("---") then return
    val start = noShebang.indexOf("---")
    val end   = noShebang.indexOf("\n---", start + 3)
    if end < 0 then return
    val yaml = noShebang.slice(start + 3, end)
    // Simple line-by-line parse: look for `databases:` block, then `name:` then `url:`
    val lines = yaml.linesIterator.toVector
    var inDatabases = false
    var currentDb: Option[String] = None
    lines.foreach { line =>
      val t = line.trim
      if t == "databases:" then
        inDatabases = true
        currentDb = None
      else if inDatabases && t.nonEmpty && !line.startsWith(" ") && !line.startsWith("\t") then
        inDatabases = false
        currentDb = None  // top-level key ended databases block
      else if inDatabases && t.endsWith(":") && !t.startsWith("#") && !t.contains(" ") then
        currentDb = Some(t.dropRight(1))  // `mydb:` line
      else if currentDb.isDefined && t.startsWith("url:") then
        val rawUrl = t.drop(4).trim.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")
        if rawUrl.startsWith("jdbc:") then
          scala.util.Try(PluginBridge.registerDb(currentDb.get, rawUrl)).failed.foreach { e =>
            System.err.println(s"[v2] warn: could not register db '${currentDb.get}': ${e.getMessage}")
          }
        currentDb = None
    }

  /** Extension method name registry: method name → receiver param name. */
  private val extensionMethods = collection.mutable.HashSet[String]()

  /** Default-param registry: def name → per-param default CT exprs (None = required). */
  private val defaultParams = collection.mutable.HashMap[String, Vector[Option[CT]]]()

  /** RAW default-param registry: name → (param names, raw default Terms).
   *  Raw terms let a default reference EARLIER params (`def shift(x: Int, by: Int
   *  = x + 1)`) — the pre-converted registry lowered `x` with an empty scope
   *  (unbound global). At the call site buildWithDefaults binds provided args in
   *  a wrapper lambda and converts each missing default IN SCOPE of the params
   *  before it. */
  private val defaultParamTerms =
    collection.mutable.HashMap[String, (Vector[String], Vector[Option[scala.meta.Term]])]()

  /** provided args + defaults → mk(all-params-as-locals) wrapped so defaults see
   *  earlier params: App(Lam(k, Let(defaults…, mk(refs))), provided). */
  private def buildWithDefaults(name: String, args: List[CT], mk: List[CT] => CT): Option[CT] =
    defaultParamTerms.get(name).flatMap { case (pnames, defs) =>
      if args.length >= defs.length then None
      else
        val k     = args.length
        val total = defs.length
        var scope = pnames.take(k).reverse.toList
        val lets  = collection.mutable.ListBuffer.empty[CT]
        var ok    = true
        defs.drop(k).zipWithIndex.foreach { case (dOpt, j) =>
          dOpt match
            case Some(d) =>
              lets += convertExpr(d, scope)
              scope = pnames(k + j) :: scope
            case None => ok = false  // missing required arg mid-list: bail to old path
        }
        if !ok then None
        else
          val refs = (0 until total).toList.map(i => CT.Local(total - 1 - i))
          val core = mk(refs)
          val body = if lets.isEmpty then core else CT.Let(lets.toList, core)
          Some(if k == 0 then body match
            case _ => CT.App(CT.Lam(0, body), Nil)
          else CT.App(CT.Lam(k, body), args))
    }

  /** Param-name registry for regular defs (enables named-arg call-site synthesis). */
  private val defParamNames = collection.mutable.HashMap[String, Vector[String]]()

  /** Vararg-def registry: functions whose last param clause contains a * (vararg) param.
   *  Maps def name → number of non-vararg params in the last clause (0 for single vararg clause). */
  private val varargDefs = collection.mutable.HashSet[String]()

  /** Zero-arg extern/def registry: `extern def foo: T` or `def foo: T = body` with no params.
   *  These are auto-called at the use site (like Scala's no-parens defs). */
  private val zeroArgDefs = collection.mutable.HashSet[String]()

  /** Multi-clause curried def first-clause defaults: for `def f(a: T = d)(args*)`, maps "f" →
   *  List of default CT for each first-clause param. Used to auto-inject defaults when calling
   *  `f(children...)` without the first-clause args (e.g. vstack(child1, child2) with no gap). */
  private val curryFirstClauseDefaults = collection.mutable.Map[String, List[CT]]()

  /** Build a List value from a sequence of CT expressions: List(a,b,c) → Cons(a,Cons(b,Cons(c,Nil))). */
  private def listOf(elems: List[CT]): CT =
    elems.foldRight(CT.Ctor("Nil", Nil): CT)((e, acc) => CT.Ctor("Cons", List(e, acc)))

  /** Synthesize missing trailing arguments from defaultParams when calling a def with fewer args. */
  private def fillDefaults(name: String, args: List[CT]): List[CT] =
    defaultParams.get(name).fold(args) { defs =>
      if args.length >= defs.length then args
      else args ++ defs.drop(args.length).collect { case Some(e) => e }
    }

  /** First pass: scan all stats and collect case class / enum / extension definitions. */
  private def registerTypes(stats: List[Stat]): Unit = stats.foreach {
    case d: Defn.Class if d.mods.exists(_.is[Mod.Case]) =>
      val params = d.ctor.paramClauses.flatMap(_.values).toList
      registerCaseClass(d.name.value, params)
      d.templ.derives.foreach { derivedType =>
        val tcName = derivedType match
          case Type.Name(n) => n
          case _            => derivedType.syntax
        if tcName == "AgentSchema" || tcName == "McpSchema" then
          derivedSchemas(d.name.value) = tcName
        else
          val cn = d.name.value
          generalDerives(cn) = generalDerives.getOrElse(cn, Nil) :+ tcName
      }
    case d: Defn.Trait => ()
    case d: Defn.Object => ()
    case Defn.Enum(_, name, _, _, templ) =>
      templ.stats.foreach {
        case ec: Defn.EnumCase =>
          val params = ec.ctor.paramClauses.flatMap(_.values).toList
          registerCaseClass(ec.name.value, params)
        case _ => ()
      }
    case eg: Defn.ExtensionGroup =>
      extMethods(eg).foreach { m => extensionMethods += m.name.value }
    case g: Defn.Given =>
      g.templ.stats.foreach {
        case eg: Defn.ExtensionGroup =>
          extMethods(eg).foreach { m => extensionMethods += m.name.value }
        case _ => ()
      }
    case _ => ()
  }

  private def extMethods(eg: Defn.ExtensionGroup): List[Defn.Def] =
    eg.body match
      case m: Defn.Def           => List(m)
      case Term.Block(stats)     => stats.collect { case m: Defn.Def => m }
      case _                     => Nil

  private def extReceiverName(eg: Defn.ExtensionGroup): String =
    eg.paramClauseGroup
      .flatMap(_.paramClauses.headOption)
      .flatMap(_.values.headOption)
      .map(_.name.value)
      .getOrElse("_self_")

  /** Receiver TYPE head of an extension group, e.g. "Tuple2" for
   *  `extension [A, B](fab: Tuple2[A, B])` — used to dispatch same-named
   *  extensions from different given instances by runtime tag. */
  private def extReceiverTypeHead(eg: Defn.ExtensionGroup): String =
    eg.paramClauseGroup
      .flatMap(_.paramClauses.headOption)
      .flatMap(_.values.headOption)
      .flatMap(_.decltpe)
      .map(_.syntax.takeWhile(c => c != '[' && c != ' '))
      .getOrElse("")

  /** Same-named extension defs from DIFFERENT instances (Bifunctor[Tuple2] vs
   *  Bifunctor[Either]) must not overwrite each other — collect (typeHead, arity,
   *  lam) per name and emit ONE dispatching global per name at flush time. */
  private val extAccum = collection.mutable.LinkedHashMap[String, collection.mutable.ListBuffer[(String, Int, CT)]]()

  /** Runtime tag test for a receiver type head; None = untestable (catch-all). */
  private def tagTestFor(typeHead: String, recv: CT): Option[CT] =
    def tagIs(t: String) = CT.Prim("seq", List(CT.Prim("tagOf", List(recv)), CT.Lit(Const.CStr(t))))
    def anyOf(ts: List[String]): CT = ts.map(tagIs).reduceRight((a, b) => CT.If(a, CT.Lit(Const.CBool(true)), b))
    typeHead match
      case "" | "Any" => None
      case "Either"   => Some(anyOf(List("Left", "Right")))
      case "Option"   => Some(anyOf(List("Some", "None")))
      case "List"     => Some(anyOf(List("Cons", "Nil")))
      case t          => Some(tagIs(t))

  private def flushExtensions(defsB: collection.mutable.Builder[CDef, List[CDef]]): Unit =
    extAccum.foreach { case (name, impls) =>
      if impls.length == 1 then defsB += CDef(name, impls.head._3)
      else
        // Dispatcher: params of the widest arity; test receiver tag per impl.
        val arity = impls.map(_._2).max
        val recv  = CT.Local(arity - 1)  // first param (receiver) — Local(arity-1)
        def callImpl(lam: CT): CT =
          CT.App(lam, (0 until arity).toList.reverse.map(i => CT.Local(i)))
        // Build if-chain, LAST impl as the else fallback.
        val body = impls.toList.init.foldRight(callImpl(impls.last._3)) { case ((th, _, lam), els) =>
          tagTestFor(th, recv) match
            case Some(test) => CT.If(test, callImpl(lam), els)
            case None       => callImpl(lam)
        }
        defsB += CDef(name, CT.Lam(arity, body))
    }
    extAccum.clear()

  // ── Entry points ─────────────────────────────────────────────────────────────

  /** Parse a source string and convert to Core IR Program.
   *  Supports script mode: bare expressions at the top level are wrapped in a block.
   *  Handles .ssc file format: optional shebang + YAML front matter + markdown prose +
   *  ```scalascript...``` fence; if no fence, uses the whole source. */
  def convertSource(src: String, fileDir: Option[java.io.File] = None): Program =
    // Reset per-file state so batch runs don't pollute each other
    fieldRegistry.clear()
    extensionMethods.clear()
    extAccum.clear()
    defaultParams.clear()
    defaultParamTerms.clear()
    defParamNames.clear()
    varargDefs.clear()
    zeroArgDefs.clear()
    curryFirstClauseDefaults.clear()
    // Pre-register param names for plugins that accept named args (openapi, etc.)
    defParamNames("openapi") = Vector("summary", "description", "tags", "deprecated", "security")
    defaultParams("openapi") = Vector(
      Some(CT.Lit(Const.CStr(""))),       // summary
      Some(CT.Lit(Const.CStr(""))),       // description
      Some(CT.Ctor("Nil", Nil)),          // tags
      Some(CT.Lit(Const.CBool(false))),   // deprecated
      Some(CT.Ctor("Nil", Nil))           // security
    )
    parseDatabasesFromFrontmatter(src)
    convertStats(parseStats(desugarListLiterals(
      stripExternDecls(preprocessAtAnnotations(resolveImportsCode(src, fileDir))))))

  /** Strip ScalaScript-specific `extern` declarations that scalameta cannot parse.
   *  Handles extern def/val (single or multi-line via open parens) and
   *  extern class/trait/object (strip until indentation returns to base level). */
  private def stripExternDecls(code: String): String =
    val lines = code.linesWithSeparators.toArray
    val sb = new java.lang.StringBuilder(code.length)
    var i = 0
    while i < lines.length do
      val line = lines(i)
      val t = line.stripLeading()
      if t.startsWith("extern def") || t.startsWith("extern val") then
        sb.append("//").append(line)
        val depth0 = line.count(_ == '(') - line.count(_ == ')')
        // Collect 0-arg extern defs: `extern def foo: T` with NO parens (not just balanced).
        if t.startsWith("extern def") && !line.contains('(') then
          val afterKw = t.drop("extern def".length).stripLeading()
          val name = afterKw.takeWhile(c => c.isLetterOrDigit || c == '_' || c == '-')
          if name.nonEmpty then zeroArgDefs += name
        var depth = depth0
        i += 1
        while i < lines.length && depth > 0 do
          sb.append("//").append(lines(i))
          depth += lines(i).count(_ == '(') - lines(i).count(_ == ')')
          i += 1
      else if t.startsWith("extern class") || t.startsWith("extern trait") || t.startsWith("extern object") then
        // Strip the header line + all indented body lines
        val baseIndent = line.length - line.stripLeading().length
        sb.append("//").append(line); i += 1
        var done = false
        while i < lines.length && !done do
          val next = lines(i)
          val stripped = next.stripLeading()
          val indent = next.length - stripped.length
          if stripped.isEmpty || indent > baseIndent then
            sb.append("//").append(next); i += 1
          else done = true // break: indentation returned, don't advance i
      else
        sb.append(line); i += 1
    sb.toString

  /** Convert bare `[expr, ...]` list literals to `List(expr, ...)`.
   *  Stack-based: each `[` in expression position opens a `List(`, the matching `]` closes with `)`.
   *  Nested list literals are handled naturally. String literals are skipped verbatim. */
  /** Strip `@` from expression-level annotations that scalameta cannot parse as Scala:
   *  `@openapi(args) route(...)` → `openapi(args)\nroute(...)`.
   *  The v1 parser has a dedicated preprocessor for this; we just strip the `@`. */
  private def preprocessAtAnnotations(code: String): String =
    if !code.contains("@openapi") then code
    else code.replace("@openapi(", "openapi(")

  private def desugarListLiterals(code: String): String =
    if !code.contains("[") then return code
    val sb = new java.lang.StringBuilder(code.length + 64)
    // Stack: true = we opened List( for this bracket, false = raw [ emitted verbatim
    val stack = new java.util.ArrayDeque[Boolean]()
    var i = 0
    while i < code.length do
      code.charAt(i) match
        case '"' =>
          // Copy string literal verbatim
          sb.append('"'); i += 1
          var done = false
          while i < code.length && !done do
            val c = code.charAt(i)
            if c == '\\' && i + 1 < code.length then
              sb.append(c); i += 1; sb.append(code.charAt(i)); i += 1
            else if c == '"' then
              sb.append(c); i += 1; done = true
            else
              sb.append(c); i += 1
        case '[' =>
          // Check if in expression position
          val prevNonWs = (i - 1 to 0 by -1).find(j => !code.charAt(j).isWhitespace).map(code.charAt).getOrElse('\n')
          val prevToken: String = {
            var end = i - 1
            while end >= 0 && code.charAt(end).isWhitespace do end -= 1
            if end < 0 then ""
            else {
              var start = end
              while start > 0 && (code.charAt(start-1).isLetterOrDigit || code.charAt(start-1) == '_') do start -= 1
              code.substring(start, end + 1)
            }
          }
          // Only treat [ as list literal when unambiguously in expression position.
          // Operators (+,-,*,/,|,&,<,>,!) are excluded — they also appear as method names
          // before type-param brackets (e.g. def |[B], def +[A]).
          val exprPos = prevNonWs match
            case '=' | '(' | ',' | '{' | '[' | '\n' | ';' | ':' => true
            case _ => Set("then","else","return","yield","do","in","of","by","if","while","to","until","match","case","=>")(prevToken)
          if exprPos then
            sb.append("List("); stack.push(true)
          else
            sb.append('[');    stack.push(false)
          i += 1
        case ']' =>
          if !stack.isEmpty && stack.pop() then sb.append(')')
          else sb.append(']')
          i += 1
        case c =>
          sb.append(c); i += 1
    sb.toString

  /** Collect std-import lines `[names](path.ssc)` from the document body (outside fences),
   *  load each referenced file, and prepend their code before the main code block.
   *  Falls back gracefully if a path can't be resolved. */
  private def resolveImportsCode(src: String, fileDir: Option[java.io.File]): String =
    val seen    = collection.mutable.HashSet[String]()
    val ordered = collection.mutable.ListBuffer.empty[(String, String)] // (canonical, code)
    val importPat = """\[([^\]]+)\]\(([^)]+)\)""".r
    // fenceOnly=true: only scan outside fences (top-level user files, where imports are prose).
    // fenceOnly=false: scan ALL lines (stdlib files where imports live inside code fences).
    def collectImports(source: String, dir: Option[java.io.File], fenceOnly: Boolean): Unit =
      var inFence = false
      source.linesIterator.foreach { line =>
        if line.startsWith("```") then inFence = !inFence
        else if !fenceOnly || !inFence then
          importPat.findAllMatchIn(line).foreach { m =>
            val rel = m.group(2)
            resolveStdPathWithFile(rel, dir).foreach { case (content, resolvedFile) =>
              val key = resolvedFile.getCanonicalPath
              if seen.add(key) then
                // DFS: recurse first so dependencies come before their dependents
                collectImports(content, Some(resolvedFile.getParentFile), fenceOnly = false)
                ordered += key -> extractCode(content, allFences = true)
            }
          }
      }
    // Auto-inject UI stdlib for files with `frontend:` in frontmatter (no explicit imports needed)
    val hasFrontend = src.linesIterator.takeWhile(_ != "---" || src.startsWith("---")).exists(
      l => l.startsWith("frontend:"))
    // Auto-inject the mapreduce stdlib when the code references its surface —
    // in v1 these are auto-available stdlib symbols (no explicit import lines);
    // index.ssc chain-imports dataset/cluster/handlers/distributed/shuffle/typed
    // so one injected line pulls the whole family via the DFS import collector.
    val mapreduceSurface = java.util.regex.Pattern.compile(
      "\\b(runDistributed|runDistributedWire|runDistributedShuffle|runDistributedShuffleWire|HandlerRegistry|NamedHandler|DistributedDataset)\\b")
    if mapreduceSurface.matcher(src).find() then
      collectImports("[Dataset, HandlerRegistry, NamedHandler, runDistributed, runDistributedShuffle](std/mapreduce/index.ssc)", fileDir, fenceOnly = false)
    if hasFrontend then
      val uiAutoImports =
        "[TkNode, VStackNode, HStackNode, DividerNode, SpacerNode, BoxNode](std/ui/nodes.ssc)\n" +
        "[vstack, hstack, divider, spacer, box](std/ui/layout.ssc)\n" +
        "[heading, text, bold, italic, code, pre, label, placeholder, caption](std/ui/typography.ssc)\n" +
        "[button, textField, checkbox, select, slider, colorPicker](std/ui/input.ssc)\n"
      collectImports(uiAutoImports, fileDir, fenceOnly = false)
    collectImports(src, fileDir, fenceOnly = false)
    val prelude = ordered.map(_._2).filter(_.nonEmpty).mkString("\n")
    // v1 semantics: ALL scalascript fences of the entry file run in document
    // order (first-fence-only silently dropped every later section — the T4.4
    // output-equality suite exposed it on multi-section conformance files).
    val mainCode = extractCode(src, allFences = true)
    if prelude.isEmpty then mainCode else s"$prelude\n$mainCode"

  /** Resolve a std-import path like `std/dsl/ast.ssc` to an absolute file.
   *  Search order: relative to fileDir → ImportResolver.stdPath → cwd-based dev-tree walk. */
  private def resolveStdPathWithFile(rel: String, fileDir: Option[java.io.File]): Option[(String, java.io.File)] =
    def tryFile(f: java.io.File): Option[(String, java.io.File)] =
      if f.isDirectory then tryFile(new java.io.File(f, "index.ssc"))
      else if f.exists() then scala.util.Try(scala.io.Source.fromFile(f).mkString).toOption.map(_ -> f)
      else None
    def tryOsPath(p: os.Path): Option[(String, java.io.File)] = tryFile(p.toIO)
    fileDir.flatMap(d => tryFile(new java.io.File(d, rel)))
      .orElse(scalascript.imports.ImportResolver.stdPath.flatMap { root =>
        tryOsPath(root / os.RelPath(rel))
      })
      .orElse {
        var cur = os.pwd
        var found: Option[os.Path] = None
        while found.isEmpty && cur.segmentCount > 0 do
          val stdDir = cur / "v1" / "runtime"
          if os.exists(stdDir / "std") then found = Some(stdDir)
          else cur = cur / os.up
        found.flatMap(root => tryOsPath(root / os.RelPath(rel)))
      }
      .orElse(tryOsPath(os.pwd / os.RelPath(rel)))

  private def resolveStdPath(rel: String, fileDir: Option[java.io.File]): Option[String] =
    resolveStdPathWithFile(rel, fileDir).map(_._1)


  /** Extract scalascript code blocks from a .ssc file.
   *  allFences=true: concatenate ALL fences (for stdlib imports).
   *  allFences=false (default): only the first fence (for the main entry file). */
  /** Filter out .ssc import directive lines, handling multi-line imports.
   *  Import directives have the form `[Ident1, Ident2, ...](path.ssc)` — a comma-separated
   *  list of bare identifiers (no operators, no strings, no `->`) followed by `](path.ssc)`.
   *  Multi-line: the first line is `[Ident, Ident,` (ends with comma); subsequent lines
   *  continue until one ends with `](path.ssc)`. */
  private val importIdentListRe = """^\[(\s*[A-Za-z_][A-Za-z0-9_]*\s*,\s*)*([A-Za-z_][A-Za-z0-9_]*)?\s*(,\s*)?(\]\([^)]+\.ssc\d*\))?$""".r

  private def looksLikeImportStart(t: String): Boolean =
    // Must start with [ and contain only idents+commas (possibly ending with .ssc))
    if !t.startsWith("[") then return false
    // Quick reject: import lines have no quotes, ->, :, =, other operators
    if t.contains("\"") || t.contains("'") || t.contains("->") || t.contains(":") ||
       t.contains("=") || t.contains("+") || t.contains("-") || t.contains("*") ||
       t.contains("|") || t.contains("&") || t.contains("!") || t.contains("s\"") then return false
    importIdentListRe.matches(t)

  private def filterImportLines(code: String): String =
    val lines = code.linesIterator.toArray
    val sb    = new StringBuilder
    var i     = 0
    while i < lines.length do
      val t = lines(i).trim
      if looksLikeImportStart(t) then
        // Import directive: skip until the closing line with ](path.ssc)
        if t.contains(".ssc)") || t.contains(".ssc0)") then
          i += 1 // single-line import: skip it
        else
          i += 1 // multi-line: skip continuations until closing .ssc) line
          var foundClose = false
          while i < lines.length && !foundClose do
            val t2 = lines(i).trim
            i += 1
            if t2.contains(".ssc)") || t2.contains(".ssc0)") then foundClose = true
      else
        if sb.nonEmpty then sb.append('\n')
        sb.append(lines(i))
        i += 1
    sb.toString

  def extractCode(raw: String, allFences: Boolean = false): String =
    val noShebang = if raw.startsWith("#!/") then raw.dropWhile(_ != '\n').drop(1) else raw
    val noFront =
      if noShebang.stripLeading().startsWith("---") then
        val start = noShebang.indexOf("---")
        val after = noShebang.indexOf("\n---", start + 3)
        if after >= 0 then noShebang.drop(after + 4) else noShebang
      else noShebang
    val fence = "```scalascript\n"
    val firstFence = noFront.indexOf(fence)
    // No fence: if the content looks like markdown prose (starts with # or ---), it's
    // a doc-only example with no runnable code — return empty so it compiles as a no-op.
    // Otherwise treat as raw Scala source (for test-style usage with no front matter).
    if firstFence < 0 then
      val trimmed = noFront.trim
      if trimmed.startsWith("#") || trimmed.startsWith("[") || trimmed.isEmpty then ""
      else trimmed
    else if !allFences then
      // Original behavior: only extract the first fence
      val codeStart = firstFence + fence.length
      val fenceEnd  = noFront.indexOf("\n```", codeStart)
      val code = if fenceEnd < 0 then noFront.drop(codeStart).trim
                 else noFront.slice(codeStart, fenceEnd)
      filterImportLines(code)
    else
      // Collect ALL scalascript fences (for stdlib library files)
      val blocks = collection.mutable.ListBuffer.empty[String]
      var pos = 0
      while pos < noFront.length do
        val fenceStart = noFront.indexOf(fence, pos)
        if fenceStart < 0 then pos = noFront.length
        else
          val codeStart = fenceStart + fence.length
          val fenceEnd  = noFront.indexOf("\n```", codeStart)
          val code = if fenceEnd < 0 then noFront.drop(codeStart).trim
                     else noFront.slice(codeStart, fenceEnd)
          val stripped = filterImportLines(code)
          if stripped.trim.nonEmpty then blocks += stripped
          pos = if fenceEnd < 0 then noFront.length else fenceEnd + 4
      blocks.mkString("\n")

  /** Convert a list of scalameta Trees (parsed body of a .ssc module) to Program. */
  def convertTrees(trees: List[Tree]): Program =
    val stats: List[Stat] = trees.flatMap {
      case Source(ss)      => ss
      case b: Term.Block   => b.stats
      case s: Stat         => List(s)
      case t: Term         => List(t)
      case _               => Nil
    }
    convertStats(stats)

  /** Parse raw ssc source to stats (script mode: expressions allowed at top level). */
  def parseStats(src: String): List[Stat] =
    given Dialect = Scala3
    src.parse[Source] match
      case Parsed.Success(tree)             => tree.stats.toList
      case _: Parsed.Error                  =>
        s"{\n$src\n}".parse[Term] match
          case Parsed.Success(Term.Block(ss)) => ss.toList
          case Parsed.Success(t: Term)        => List(t)
          case err: Parsed.Error              => throw err.details

  // ── Top-level stats → Program ─────────────────────────────────────────────────

  def convertStats(stats: List[Stat]): Program =
    registerTypes(stats)  // first pass: populate field registry

    val defsB  = List.newBuilder[CDef]
    val entryB = List.newBuilder[Stat]

    val userDefNames = (stats.collect {
      case d: Defn.Def                        => List(d.name.value)
      case v: Defn.Val if isSimplePat(v.pats) => List(patName(v.pats.head))
      case g: Defn.Given                      => List(g.name.value)
      case eg: Defn.ExtensionGroup            => extMethods(eg).map(_.name.value)
      case obj: Defn.Object                   => List(obj.name.value)
    }).flatten.toSet

    stats.foreach {
      case d: Defn.Def if V2PluginRegistry.hasGlobal(d.name.value) =>
        () // Plugin already provides this def (with default-param support); don't shadow it.
      case d: Defn.Def =>
        val params = allParams(d)
        val scope  = params.reverse  // Local(0) = last param
        val body   = convertExpr(d.body, scope)
        // For multi-clause curried defs, build nested lambdas per clause
        val allClauses = d.paramClauseGroups.flatMap(_.paramClauses)
        // Register default params (for call-site synthesis)
        val allParamTerms = allClauses.flatMap(_.values)
        val defVec = allParamTerms.map(p => p.default.map(convertExpr(_, Nil))).toVector
        if defVec.exists(_.isDefined) then
          defaultParams(d.name.value) = defVec
          defaultParamTerms(d.name.value) =
            (allParamTerms.map(_.name.value).toVector, allParamTerms.map(_.default).toVector)
        // Register param names for named-arg call-site synthesis
        if allParamTerms.nonEmpty && defVec.exists(_.isDefined) then
          defParamNames(d.name.value) = allParamTerms.map(_.name.value).toVector
        // Register vararg defs: last clause has a Type.Repeated param
        val lastClause = allClauses.lastOption.toList.flatMap(_.values)
        if lastClause.exists(p => p.decltpe.exists(_.isInstanceOf[Type.Repeated])) then
          varargDefs += d.name.value
        // Register 0-arg no-parens defs: `def foo: T = body` (no param clauses at all) → auto-call.
        // Excludes `def foo(): T` (has one empty clause) which needs explicit `()`.
        if allClauses.isEmpty then
          zeroArgDefs += d.name.value
        val lam =
          if allClauses.length <= 1 then
            if params.isEmpty then CT.Lam(0, body) else CT.Lam(params.length, body)
          else
            // Nested: outermost clause wraps inner. Scope is still full flat list.
            val clauseLens = allClauses.map(_.values.length)
            val innermost  = clauseLens.tail.foldRight(body: CT) { (n, inner) => CT.Lam(if n == 0 then 0 else n, inner) }
            // Register first-clause defaults for curried vararg defs (e.g. vstack(gap=0)(children*)).
            // This lets `vstack(child1, child2)` auto-inject gap=0 rather than erroring on arity.
            val firstClauseParams = allClauses.head.values
            if firstClauseParams.nonEmpty && firstClauseParams.forall(p => p.default.isDefined) then
              val firstDefs = firstClauseParams.map(p => convertExpr(p.default.get, Nil)).toList
              curryFirstClauseDefaults(d.name.value) = firstDefs
            CT.Lam(if clauseLens.head == 0 then 0 else clauseLens.head, innermost)
        defsB += CDef(d.name.value, lam)
      case v: Defn.Val if isSimplePat(v.pats) =>
        val name = patName(v.pats.head)
        // Pure RHS → safe to evaluate eagerly as a global.
        // Effectful RHS (has function calls) → keep in entry block to preserve order.
        if isPureValRhs(v.rhs) then
          val rhs  = convertExpr(v.rhs, Nil)
          defsB += CDef(name, rhs)
        else
          entryB += v
      case g: Defn.Given =>
        // given name: T with { def m1(a...) = ...; def m2(b...) = ... }
        // → val name = __mk_method_obj__(["m1", lam..., "m2", lam...])
        defsB += CDef(g.name.value, convertGiven(g))
        // Extension groups declared INSIDE `given ... with` (std/bifunctor's
        // `given tupleBifunctor: Bifunctor[Tuple2] with extension (fab) def bimap…`)
        // hoist to global extension defs, same lowering as top-level extensions.
        // (Trait-level extension DECLARATIONS are Decl.Def — no body — and are
        // naturally skipped by the Defn.Def collector.)
        g.templ.stats.foreach {
          case eg: Defn.ExtensionGroup => emitExtensionGroup(eg, defsB)
          case _ => ()
        }
      case eg: Defn.ExtensionGroup =>
        emitExtensionGroup(eg, defsB)
      case obj: Defn.Object if !V2PluginRegistry.hasGlobal(obj.name.value) =>
        // object Foo { def m(...) = ... } → Foo = __mk_method_obj__(["m", lam, ...])
        val methods = obj.templ.stats.collect { case m: Defn.Def => m }
        if methods.nonEmpty then
          val pairs = methods.flatMap { m =>
            val ps  = allParams(m)
            val sc  = ps.reverse
            val bod = convertExpr(m.body, sc)
            val lam = if ps.isEmpty then CT.Lam(0, bod) else CT.Lam(ps.length, bod)
            List(CT.Lit(Const.CStr(m.name.value)), lam)
          }
          defsB += CDef(obj.name.value, CT.Prim("__mk_method_obj__", pairs))
      case other =>
        entryB += other
    }

    val stdDefs = standardPrelude.filterNot(d => userDefNames.contains(d.name))
    val derivedDefs = derivedSchemas.toList.flatMap { case (cn, tc) => makeDerivedSchemaCDef(cn, tc) }
    // Only derive for typeclasses defined locally (not imported from plugins).
    // A locally-defined typeclass companion shows up in userDefNames as a Defn.Object.
    val generalDerivedDefs = generalDerives.toList.flatMap { case (cn, tcs) =>
      tcs.filter(userDefNames.contains).flatMap(tc => makeDerivedGeneralCDef(cn, tc))
    }
    flushExtensions(defsB)  // emit per-name (possibly tag-dispatching) extension globals
    val defs  = stdDefs ++ defsB.result() ++ derivedDefs ++ generalDerivedDefs
    val entryStmts = entryB.result()
    val entry =
      if entryStmts.nonEmpty then convertBlock(entryStmts, Nil)
      else if userDefNames.contains("main") then CT.App(CT.Global("main"), Nil)
      else CT.Lit(Const.CUnit)
    Program(defs, entry)

  /** Standard prelude defs injected into every converted program.
   *  Maps common v1 scalascript globals to v2 primitives. */
  private val standardPrelude: List[CDef] = List(
    // println/print are registered natively (variadic) so println() works too
    CDef("identity",  CT.Lam(1, CT.Local(0))),
    CDef("not",       CT.Lam(1, CT.If(CT.Local(0), CT.Lit(Const.CBool(false)), CT.Lit(Const.CBool(true))))),
    CDef("math",          CT.Prim("__math_obj__", Nil)),
    CDef("__unsupported__", CT.Lam(1, CT.Prim("io.println",
      List(CT.Prim("__arith__", List(CT.Lit(Const.CStr("++")),
        CT.Lit(Const.CStr("Unsupported: ")), CT.Local(0))))))),
    CDef("nanoTime", CT.Lam(0, CT.Prim("io.nanoTime", Nil))),
    // Bench harness stub — Bench.opaque(x) = identity (prevents constant folding)
    CDef("Bench", CT.Ctor("BenchObj", Nil)),
    // LazyList object (accessed as LazyList.from — isCtorName("LazyList")==true → Ctor handled in __method__)
    CDef("LazyList", CT.Ctor("LazyList", Nil)),
  )

  // ── Block lowering ────────────────────────────────────────────────────────────

  def convertBlock(stats: List[Stat], scope: List[String]): CT =
    stats match
      case Nil => CT.Lit(Const.CUnit)

      // val name = rhs; rest
      case (v: Defn.Val) :: rest if isSimplePat(v.pats) =>
        val name = patName(v.pats.head)
        val rhs  = convertExpr(v.rhs, scope)
        // Track array vals with ## prefix so a(idx) can use arr.get instead of App
        val isArray = v.rhs match
          case Term.Apply.After_4_6_0(Term.Name("Array"), _) => true
          case _ => false
        val scopeName = if isArray then s"##$name" else name
        CT.Let(List(rhs), convertBlock(rest, scopeName :: scope))

      // val (a, b) = rhs; rest (tuple destructuring)
      case (v: Defn.Val) :: rest =>
        val rhs   = convertExpr(v.rhs, scope)
        val names = v.pats.flatMap(patNames)
        if names.isEmpty then
          CT.Let(List(rhs), convertBlock(rest, "_blk_" :: scope))
        else
          // Bind tuple to "_tup_", then extract each field
          val tupName  = "_tup_"
          val withTup  = tupName :: scope
          def chain(remaining: List[(String, Int)], sc: List[String]): CT =
            remaining match
              case Nil              => convertBlock(rest, sc)
              case (nm, i) :: tail =>
                CT.Let(
                  List(CT.Prim("fieldAt", List(lookupVar(tupName, sc), CT.Lit(Const.CInt(i))))),
                  chain(tail, nm :: sc)
                )
          CT.Let(List(rhs), chain(names.zipWithIndex.toList, withTup))

      // var name = rhs; rest (mutable cell)
      case (v: Defn.Var) :: rest =>
        val names = v.pats.flatMap(patNames)
        val rhs   = varRhs(v)
        val rhsIr = convertExpr(rhs, scope)
        val (cellOp, prefix) =
          if isIntLit(rhs) then ("lcell.new", "@@")
          else                   ("cell.new",  "@")
        val cellName = names.headOption.map(n => s"$prefix$n").getOrElse("@_")
        CT.Let(List(CT.Prim(cellOp, List(rhsIr))), convertBlock(rest, cellName :: scope))

      // def name(params) = body; rest (local LetRec for self-recursion)
      case (d: Defn.Def) :: rest =>
        val name      = d.name.value
        val params    = allParams(d)
        val letrecSc  = name :: scope
        val defSc     = params.reverse ++ letrecSc
        val body      = convertExpr(d.body, defSc)
        val lam       = CT.Lam(params.length, body)
        CT.LetRec(List(lam), convertBlock(rest, letrecSc))

      // while (cond) body; rest
      case (w: Term.While) :: rest =>
        val loop = CT.While(convertExpr(w.expr, scope), convertExpr(w.body, scope))
        rest match
          case Nil => loop
          case _   => CT.Let(List(loop), convertBlock(rest, "_blk_" :: scope))

      // x = rhs (assignment to mutable var)
      case (a: Term.Assign) :: rest =>
        val setIr = convertAssign(a, scope)
        rest match
          case Nil => setIr
          case _   => CT.Seq(List(setIr, convertBlock(rest, scope)))

      // expression statement (not last)
      case (t: Term) :: (rest @ (_ :: _)) =>
        CT.Seq(List(convertExpr(t, scope), convertBlock(rest, scope)))

      // last expression — the block's value
      case (t: Term) :: Nil =>
        convertExpr(t, scope)

      // non-Term at end (shouldn't happen in well-formed code)
      case _ :: rest => convertBlock(rest, scope)

  // ── Expression conversion ─────────────────────────────────────────────────────

  def convertExpr(term: Term, scope: List[String]): CT = term match

    // ── Literals ──────────────────────────────────────────────────────────────
    // Note: scalameta Lit.Int/Double/Float hold the literal as String from source
    case Lit.Int(n)     => CT.Lit(Const.CInt(n.toLong))
    case Lit.Long(n)    => CT.Lit(Const.CInt(n.toLong))
    case Lit.Double(d)  => CT.Lit(Const.CFloat(d.toDouble))
    case Lit.Float(f)   => CT.Lit(Const.CFloat(f.toDouble))
    case Lit.String(s)  => CT.Lit(Const.CStr(s))
    case Lit.Boolean(b) => CT.Lit(Const.CBool(b))
    case Lit.Unit()     => CT.Lit(Const.CUnit)
    case Lit.Char(c)    => CT.Lit(Const.CInt(c.toLong))
    case Lit.Null()     => CT.Ctor("None", Nil)

    // ── Variables ─────────────────────────────────────────────────────────────
    case Term.Name(n)   => lookupVarFull(n, scope)

    // ── Block ─────────────────────────────────────────────────────────────────
    case Term.Block(ss) => convertBlock(ss, scope)

    // ── If / else ─────────────────────────────────────────────────────────────
    case t: Term.If =>
      val c = convertExpr(t.cond, scope)
      val th = convertExpr(t.thenp, scope)
      // elsep is Lit.Unit() or Term.Block(Nil) when no else branch
      val el = t.elsep match
        case Lit.Unit()        => CT.Lit(Const.CUnit)
        case Term.Block(Nil)   => CT.Lit(Const.CUnit)
        case e                 => convertExpr(e, scope)
      CT.If(c, th, el)

    // ── Lambda ────────────────────────────────────────────────────────────────
    case Term.Function.After_4_6_0(paramClause, body) =>
      val names  = paramClause.values.map(_.name.value)
      val newSc  = names.reverse.toList ++ scope
      CT.Lam(names.length, convertExpr(body, newSc))

    case Term.AnonymousFunction(body) =>
      val arity = countPlaceholders(body)
      if arity == 0 then CT.Lam(1, convertExpr(body, "_" :: scope))
      else
        // _ph_0 is arg0 (outer), _ph_{n-1} is innermost (Local(0) is newest-bound)
        // With arity=2 and names=[_ph_0, _ph_1]: scope = _ph_1 :: _ph_0 :: outer
        // so Local(0)=_ph_1, Local(1)=_ph_0
        val paramNames = (0 until arity).map(i => s"_ph_$i").toList
        val innerScope = paramNames.reverse ++ scope  // newest-first
        val counter = Array(0)
        def go(t: Term): CT = t match
          case _: Term.Placeholder =>
            val i = counter(0); counter(0) += 1
            val name = paramNames(i)
            CT.Local(innerScope.indexOf(name))
          case Term.Select(q, Term.Name(mname)) =>
            val recv = go(q)
            if extensionMethods.contains(mname) then CT.App(CT.Global(mname), List(recv))
            else fieldIndex(mname) match
              case Some(i) => CT.Prim("fieldAt", List(recv, CT.Lit(Const.CInt(i))))
              case None    => CT.Prim("__method__", List(CT.Lit(Const.CStr(mname)), recv))
          case Term.Apply.After_4_6_0(fn, ac) =>
            // Process args directly through go to resolve placeholders as CT.Local(idx)
            // DO NOT call convertApply here: it uses a different scope and re-wraps
            // AnonymousFunction(Placeholder) → creating nested identity lambdas instead of locals.
            val rawArgs = ac.values.toList.map(go)
            fn match
              case Term.Select(recv, Term.Name(mname)) =>
                // recv.mname(args...) — go(recv) correctly handles placeholders as CT.Local
                val recvCT = go(recv)
                if extensionMethods.contains(mname) then CT.App(CT.Global(mname), recvCT :: rawArgs)
                else CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: recvCT :: rawArgs)
              case Term.Name(n) if !innerScope.contains(n) && varargDefs.contains(n) =>
                CT.App(go(fn), List(listOf(rawArgs)))
              case _ =>
                CT.App(go(fn), rawArgs)
          case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
            val l = go(lhs)
            val r = argClause.values.toList match
              case Nil     => CT.Lit(Const.CUnit)
              case List(e) => go(e)
              case es      => CT.Ctor(s"Tuple${es.length}", es.map(go))
            convertInfix(op.value, l, r, innerScope)
          case _ => convertExpr(t, innerScope)
        CT.Lam(arity, go(body))

    // ── Tuple ─────────────────────────────────────────────────────────────────
    case Term.Tuple(elems) =>
      CT.Ctor(s"Tuple${elems.length}", elems.map(e => convertExpr(e, scope)))

    // ── New ───────────────────────────────────────────────────────────────────
    case Term.New(init) =>
      val name = ctorName(init.tpe)
      val args = init.argClauses.flatMap(_.values).map(e => convertExpr(e, scope)).toList
      CT.Ctor(name, args)

    // ── Pattern match ─────────────────────────────────────────────────────────
    case t: Term.Match =>
      convertMatch(convertExpr(t.expr, scope), t.cases, scope)

    // ── Infix ─────────────────────────────────────────────────────────────────
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val l = convertExpr(lhs, scope)
      // Multi-arg RHS (e.g. `a ++ (b, c)` parses as two args, not a Tuple)
      val r = argClause.values.toList match
        case Nil      => CT.Lit(Const.CUnit)
        case List(e)  => convertExpr(e, scope)
        case es       => CT.Ctor(s"Tuple${es.length}", es.map(e => convertExpr(e, scope)))
      convertInfix(op.value, l, r, scope)

    // ── Unary prefix ──────────────────────────────────────────────────────────
    case Term.ApplyUnary(op, expr) =>
      CT.Prim("__unary__", List(CT.Lit(Const.CStr(op.value)), convertExpr(expr, scope)))

    // ── Type application — erase type args (summon[T] → resolve given) ──────
    case Term.ApplyType.After_4_6_0(Term.Name("summon"), argClause) =>
      val typeSig = argClause match
        case ac: Type.ArgClause => ac.values.headOption.fold("?")(_.syntax)
        case _ => "?"
      givenRegistry.get(typeSig) match
        case Some(name) => CT.Global(name)
        case None =>
          // summon[Mirror.Of[X]] — if X is a known case class, build an inline Mirror ctor.
          val mirrorTypePat = """^Mirror\.Of\[(.+)\]$""".r
          typeSig match
            case mirrorTypePat(className) if fieldRegistry.contains(className) =>
              val fields    = fieldRegistry(className)
              val labelLit  = CT.Lit(Const.CStr(className))
              val elemLbls  = listOf(fields.map(f => CT.Lit(Const.CStr(f))).toList)
              val elemTypes = listOf(fields.map(_ => CT.Lit(Const.CStr("Any"))).toList)
              CT.Ctor("Mirror", List(labelLit, elemLbls, elemTypes))
            case _ =>
              CT.App(CT.Global("__unsupported__"), List(CT.Lit(Const.CStr(s"summon[$typeSig]"))))
    case Term.ApplyType.After_4_6_0(fn, _) =>
      convertExpr(fn, scope)

    // ── Function application ──────────────────────────────────────────────────
    case Term.Apply.After_4_6_0(fn, argClause) =>
      convertApply(fn, argClause.values.toList, scope)

    // ── Member select (field/method as value, no args) ────────────────────────
    case Term.Select(qual, Term.Name(name)) =>
      // Namespace.CtorName → CT.Ctor (e.g. Transport.Stdio, Either.Left, Option.None)
      // Also handles zero-arg enum cases (fieldRegistry has empty vector for them).
      // Guard: only treat as Ctor when the qualifier is itself a type name (uppercase-first),
      // e.g. Option.None, Either.Left — NOT when qualifier is a value (math.Pi, list.Head).
      val isZeroArgEnumCase = fieldRegistry.get(name).exists(_.isEmpty)
      val qualIsTypeName = qual match
        case Term.Name(qn) => isCtorName(qn)
        case _             => false
      if qualIsTypeName && isCtorName(name) && (!fieldRegistry.contains(name) || isZeroArgEnumCase) then CT.Ctor(name, Nil)
      else
        val q = convertExpr(qual, scope)
        if extensionMethods.contains(name) then
          CT.App(CT.Global(name), List(q))
        else
          fieldIndex(name) match
            case Some(i) => CT.Prim("fieldAt", List(q, CT.Lit(Const.CInt(i))))
            case None    => CT.Prim("__method__", List(CT.Lit(Const.CStr(name)), q))

    // ── Assign ────────────────────────────────────────────────────────────────
    case a: Term.Assign => convertAssign(a, scope)

    // ── While ─────────────────────────────────────────────────────────────────
    case t: Term.While =>
      CT.While(convertExpr(t.expr, scope), convertExpr(t.body, scope))

    // ── Return ────────────────────────────────────────────────────────────────
    case Term.Return(expr) => convertExpr(expr, scope)

    // ── Eta expansion ─────────────────────────────────────────────────────────
    case Term.Eta(expr) => convertExpr(expr, scope)

    // ── Type ascription ───────────────────────────────────────────────────────
    case Term.Ascribe(expr, _) => convertExpr(expr, scope)

    // ── Throw ─────────────────────────────────────────────────────────────────
    case Term.Throw(expr) =>
      CT.App(CT.Global("__throw__"), List(convertExpr(expr, scope)))

    // ── Try/catch (run body, ignore handlers for now) ─────────────────────────
    case Term.Try(expr, _, _) => convertExpr(expr, scope)

    // ── Partial function {case p => e} as lambda+match ────────────────────────
    case Term.PartialFunction(cases) =>
      CT.Lam(1, convertMatch(CT.Local(0), cases, "_pfn_" :: scope))

    // ── Anonymous function (placeholder syntax: _.foo, _ + 1, etc.) ─────────
    case Term.AnonymousFunction(body) =>
      val (n, replaced) = collectPlaceholders(body)
      val paramNames = (0 until n).map(i => s"_p$i").toList
      val innerScope = paramNames.reverse ++ scope
      CT.Lam(n, convertExpr(replaced, innerScope))

    // ── For/yield ────────────────────────────────────────────────────────────
    case Term.ForYield(enums, body) =>
      convertForYield(enums, body, scope)

    // ── For/do (imperative) ───────────────────────────────────────────────────
    case Term.For(enums, body) =>
      convertForDo(enums, body, scope)

    // ── String interpolation s"... $x ..." ───────────────────────────────────
    case Term.Interpolate(Term.Name("s"), parts, args) =>
      val strs = parts.map {
        case Lit.String(s) => CT.Lit(Const.CStr(s))
        case _             => CT.Lit(Const.CStr(""))
      }
      val vals = args.map { e =>
        CT.Prim("__method__", List(CT.Lit(Const.CStr("toString")), convertExpr(e, scope)))
      }
      interleaveConcat(strs, vals)

    // ── Unknown — emit stub that errors at runtime ────────────────────────────
    case other =>
      CT.App(CT.Global("__unsupported__"),
        List(CT.Lit(Const.CStr(other.getClass.getSimpleName))))

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private def lookupVar(name: String, scope: List[String]): CT =
    val i = scope.indexOf(name)
    if i >= 0 then CT.Local(i) else CT.Global(name)

  private def lookupVarFull(name: String, scope: List[String]): CT =
    val i = scope.indexOf(name)
    if i >= 0 then CT.Local(i)
    else
      val lcell = scope.indexOf(s"@@$name")
      val cell  = scope.indexOf(s"@$name")
      val arr   = scope.indexOf(s"##$name")
      if lcell >= 0 then CT.Prim("lcell.get", List(CT.Local(lcell)))
      else if cell >= 0 then CT.Prim("cell.get", List(CT.Local(cell)))
      else if arr >= 0 then CT.Local(arr)  // array val — return raw local
      else if isCtorName(name) then CT.Ctor(name, Nil)  // e.g. Nil, None, True
      else CT.Global(name)

  private def convertAssign(a: Term.Assign, scope: List[String]): CT =
    a.lhs match
      case Term.Name(name) =>
        val lcell = scope.indexOf(s"@@$name")
        val cell  = scope.indexOf(s"@$name")
        val rhs   = convertExpr(a.rhs, scope)
        if lcell >= 0 then CT.Prim("lcell.set", List(CT.Local(lcell), rhs))
        else if cell >= 0 then CT.Prim("cell.set", List(CT.Local(cell), rhs))
        else CT.Prim("cell.set", List(CT.Global(s"@$name"), rhs))
      case Term.Apply.After_4_6_0(fn, argClause) =>
        val arr = convertExpr(fn, scope)
        val idx = argClause.values.headOption.map(e => convertExpr(e, scope)).getOrElse(CT.Lit(Const.CInt(0)))
        CT.Prim("arr.set", List(arr, idx, convertExpr(a.rhs, scope)))
      case other =>
        CT.Prim("__assign__", List(convertExpr(other, scope), convertExpr(a.rhs, scope)))

  private def convertApply(fn: Term, rawArgs: List[Term], scope: List[String]): CT =
    // Term.Block args need special handling:
    // - Block(List(Function/AnonymousFunction)) = a lambda block like { x => expr } → keep as-is
    // - Any other Block = a statement block like { stmt; expr } → wrap in Lam(0) (by-name thunk)
    val args = rawArgs.map { e =>
      val converted = convertExpr(wrapIfPH(e), scope)
      e match
        case Term.Block(List(_: Term.Function | _: Term.AnonymousFunction | _: Term.PartialFunction)) =>
          converted  // lambda block: { x => ... } or { case ... }
        case _: Term.Block =>
          CT.Lam(0, converted)  // statement block → thunk (e.g. runLogger { ... })
        case _ => converted
    }
    fn match
      // Array(name) indexed read — ## prefix means it's a ForeignV(ArrayBuffer)
      case Term.Name(name) if scope.contains(s"##$name") && args.length == 1 =>
        val idx = scope.indexOf(s"##$name")
        CT.Prim("arr.get", List(CT.Local(idx), args.head))
      // List/Seq/Vector factory → linked list
      case Term.Name("List") | Term.Name("Seq") | Term.Name("Vector") | Term.Name("IArray") =>
        val nil: CT = CT.Ctor("Nil", Nil)
        args.foldRight(nil)((h, t) => CT.Ctor("Cons", List(h, t)))
      // Array factory → mutable array
      case Term.Name("Array") =>
        CT.Prim("__mk_arr__", args)
      // Map factory: Map(k1->v1, k2->v2) → map.new + map.put
      case Term.Name("Map") =>
        CT.Prim("__mk_map__", args)
      // Set factory → just build a deduplicated list for now
      case Term.Name("Set") =>
        val nil: CT = CT.Ctor("Nil", Nil)
        args.foldRight(nil)((h, t) => CT.Ctor("Cons", List(h, t)))
      // route(method, path, handler) 3-arg direct form → curried route(method, path)(handler)
      case Term.Name("route") if args.length == 3 =>
        CT.App(CT.App(CT.Global("route"), List(args(0), args(1))), List(args(2)))
      // Curried vararg def called with positional children only — auto-inject first-clause defaults.
      // E.g. `vstack(child1, child2)` where vstack(gap=0)(children*) → vstack(0)(List(child1,child2)).
      case Term.Name(name) if !isCtorName(name) && !scope.contains(name)
          && !rawArgs.exists(_.isInstanceOf[Term.Assign])
          && curryFirstClauseDefaults.contains(name)
          && varargDefs.contains(name) =>
        val firstDefs = curryFirstClauseDefaults(name)
        val innerApp  = CT.App(CT.Global(name), firstDefs)
        CT.App(innerApp, List(listOf(args)))  // vararg: wrap children in list
      // Named-arg call to a known non-ctor function (e.g. f(a, computed = b))
      // Resolves named positions using defParamNames and fills defaults from defaultParams.
      case Term.Name(name) if !isCtorName(name) && !scope.contains(name)
          && rawArgs.exists(_.isInstanceOf[Term.Assign])
          && defParamNames.contains(name) =>
        val pnames = defParamNames(name)
        val defs   = defaultParams.get(name)
        val positionalRaw = rawArgs.takeWhile(!_.isInstanceOf[Term.Assign])
        val positional    = positionalRaw.map(e => convertExpr(wrapIfPH(e), scope))
        val named: Map[String, CT] = rawArgs.collect {
          case Term.Assign(Term.Name(n), rhs) => n -> convertExpr(rhs, scope)
        }.toMap
        // Stop at first param with no positional/named value and no default.
        // This prevents multi-clause curried fns (e.g. vstack(gap=16)) from getting
        // spurious `unit` args for the second clause's vararg param.
        val callArgs = pnames.zipWithIndex.iterator.map { case (pn, i) =>
          if i < positional.length then Some(positional(i))
          else named.get(pn).orElse(defs.flatMap(_.lift(i).flatten))
        }.takeWhile(_.isDefined).map(_.get).toList
        CT.App(CT.Global(name), callArgs)
      // Constructor application — named args case: Ctor(field = val, ...) → positional
      // Named args arrive as Term.Assign inside rawArgs; field registry gives positional order.
      case Term.Name(name) if isCtorName(name) && rawArgs.exists(_.isInstanceOf[Term.Assign]) =>
        val overrides: Map[String, CT] = rawArgs.collect {
          case Term.Assign(Term.Name(n), rhs) => n -> convertExpr(rhs, scope)
        }.toMap
        fieldRegistry.get(name) match
          case Some(fields) =>
            val ctorDefs = defaultParams.get(name)
            CT.Ctor(name, fields.zipWithIndex.map { case (fn, i) =>
              overrides.getOrElse(fn, ctorDefs.flatMap(defs => defs.lift(i).flatten).getOrElse(CT.Lit(Const.CUnit)))
            }.toList)
          case None =>
            // Unknown ctor with named args — extract RHS in call order (best-effort)
            val positional = rawArgs.map {
              case Term.Assign(_, rhs) => convertExpr(rhs, scope)
              case e                  => convertExpr(wrapIfPH(e), scope)
            }
            CT.Ctor(name, positional)
      // Constructor application — positional args
      // Exception: known factory functions (Decimal, BigInt, etc.) are globals, not ctors.
      case Term.Name(name) if isCtorName(name) =>
        if functionConstructors.contains(name) then CT.App(CT.Global(name), args)
        else buildWithDefaults(name, args, refs => CT.Ctor(name, refs))
               .getOrElse(CT.Ctor(name, fillDefaults(name, args)))
      // .copy(field = val, ...) — case class copy with named field overrides.
      // Intercept before the generic __method__ path so we can use the field registry
      // to find indices and emit Ctor with field-at fallbacks, avoiding @field globals.
      case Term.Select(qual, Term.Name("copy"))
          if rawArgs.nonEmpty && rawArgs.forall(_.isInstanceOf[Term.Assign]) =>
        val overrides: Map[String, CT] = rawArgs.collect {
          case Term.Assign(Term.Name(n), rhs) => n -> convertExpr(rhs, scope)
        }.toMap
        val classOpt = fieldRegistry.find { case (_, fields) =>
          overrides.keys.forall(k => fields.contains(k))
        }
        classOpt match
          case Some((tag, fields)) =>
            val q = convertExpr(qual, scope)
            CT.Let(List(q), CT.Ctor(tag, fields.zipWithIndex.map { case (fn, i) =>
              overrides.getOrElse(fn, CT.Prim("fieldAt", List(CT.Local(0), CT.Lit(Const.CInt(i)))))
            }.toList))
          case None =>
            val q = convertExpr(qual, scope)
            CT.Prim("__method__", CT.Lit(Const.CStr("copy")) :: q :: args)
      // Method call: qual.method(args)
      case Term.Select(qual, Term.Name(mname)) =>
        if isCtorName(mname) then CT.Ctor(mname, args)
        else
          val q = convertExpr(qual, scope)
          // Extension method → global call with receiver as first arg
          if extensionMethods.contains(mname) then
            CT.App(CT.Global(mname), q :: args)
          // If it's a known field accessor with no args, use fieldAt
          else if args.isEmpty then
            fieldIndex(mname) match
              case Some(i) => CT.Prim("fieldAt", List(q, CT.Lit(Const.CInt(i))))
              case None    => CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: q :: args)
          else
            CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: q :: args)
      // Curried method application: qual.method(a)(b) — merge into one __method__ call
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name(mname)), innerClause) if !isCtorName(mname) =>
        val q     = convertExpr(qual, scope)
        val inner = innerClause.values.toList.map(e => convertExpr(e, scope))
        CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: q :: (inner ++ args))
      // summon[T] — resolve given by type string
      case Term.ApplyType.After_4_6_0(Term.Name("summon"), argClause) =>
        val typeSig = argClause match
          case ac: Type.ArgClause => ac.values.headOption.fold("?")(_.syntax)
          case _ => "?"
        givenRegistry.get(typeSig) match
          case Some(name) => CT.Global(name)
          case None       => CT.App(CT.Global("__unsupported__"), List(CT.Lit(Const.CStr(s"summon[$typeSig]"))))
      // direct[M] { stmts } — desugar bind-forms to flatMap chains
      case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) if rawArgs.length == 1 =>
        val stmts = rawArgs.head match
          case Term.Block(ss) => scalascript.transform.DirectAnorm.expand(ss)
          case t              => List(t)
        desugarDirect(stmts, scope)
      // Type-applied call List[T](...) — strip type args
      case Term.ApplyType.After_4_6_0(inner, _) =>
        convertApply(inner, rawArgs, scope)
      // Curried constructor application f(a)(b) — if f is a known vararg def, wrap vararg args in a list
      case Term.Apply.After_4_6_0(inner, innerClause) =>
        val innerApp = convertApply(inner, innerClause.values.toList, scope)
        val isVararg = inner match
          case Term.Name(n) if !scope.contains(n) => varargDefs.contains(n)
          case _ => false
        if isVararg then CT.App(innerApp, List(listOf(args)))
        else CT.App(innerApp, args)
      // Regular function call (with optional default-param synthesis for known defs)
      case Term.Name(name) if !scope.contains(name) && defaultParams.contains(name) =>
        buildWithDefaults(name, args, refs => CT.App(CT.Global(name), refs))
          .getOrElse(CT.App(CT.Global(name), fillDefaults(name, args)))
      // Direct single-clause vararg call: f(a, b, c) where f has vararg — always wrap in list
      // (single-arg calls f(x) must also wrap: body expects a List, e.g. body.toList)
      case Term.Name(name) if !scope.contains(name) && varargDefs.contains(name) =>
        CT.App(CT.Global(name), List(listOf(args)))
      case other => CT.App(convertExpr(other, scope), args)

  /** Desugar `direct[M] { stmts }` bind-forms into flatMap chains.
   *  `x = expr` → expr.flatMap { x => rest }
   *  `val x = expr` → let x = expr in rest (pure)
   *  bare expr → expr.flatMap { _ => rest } */
  private def desugarDirect(stmts: List[scala.meta.Stat], scope: List[String]): CT =
    import scala.meta.*
    stmts match
      case Nil => CT.Lit(Const.CUnit)
      case (last: Term) :: Nil => convertExpr(last, scope)
      case Term.Assign(Term.Name(x), rhs) :: rest =>
        val bindedScope = x :: scope
        val body = desugarDirect(rest, bindedScope)
        CT.Prim("__method__", List(
          CT.Lit(Const.CStr("flatMap")),
          convertExpr(rhs, scope),
          CT.Lam(1, body)))
      case Defn.Val(_, List(Pat.Var(n)), _, rhs) :: rest =>
        val x = n.value
        val letScope = x :: scope
        CT.Let(List(convertExpr(rhs, scope)), desugarDirect(rest, letScope))
      case (t: Term) :: rest =>
        CT.Prim("__method__", List(
          CT.Lit(Const.CStr("flatMap")),
          convertExpr(t, scope),
          CT.Lam(1, desugarDirect(rest, "_" :: scope))))
      case _ :: rest => desugarDirect(rest, scope)

  private def convertInfix(op: String, l: CT, r: CT, scope: List[String]): CT = op match
    case "&&" => CT.If(l, r, CT.Lit(Const.CBool(false)))
    case "||" => CT.If(l, CT.Lit(Const.CBool(true)), r)
    case "::"  => CT.Ctor("Cons", List(l, r))
    case _    => CT.Prim("__arith__", List(CT.Lit(Const.CStr(op)), l, r))

  private def convertMatch(scrut: CT, cases: List[Case], scope: List[String]): CT =
    val hasCtorArms = cases.exists { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(_, _) | Pat.ExtractInfix.After_4_6_0(_, _, _) |
             Pat.Tuple(_)                                                              => true
        case Term.Name(n) if isCtorName(n)                                            => true
        case Pat.Var(Term.Name(n)) if isCtorName(n)                                  => true
        case _                                                                         => false
    }
    val hasLitArms      = cases.exists(c => c.pat.is[Lit])
    val hasNestedOrDup  = hasLitArms || needsGeneralChain(cases)
    val sc = "_sc_" :: scope

    if hasNestedOrDup || (!hasCtorArms && cases.exists(_.cond.nonEmpty)) then
      // General if-chain: each case becomes a condition (flat, using nested fieldAt) + bindings + body
      val scrutRef = CT.Local(0)
      def caseChain(cs: List[Case]): CT = cs match
        case Nil => CT.App(CT.Global("__match_fail__"), Nil)
        case c :: rest =>
          val (conds, binds) = flattenPattern(c.pat, scrutRef, sc)
          val failCT  = caseChain(rest)
          // CT.Let is SEQUENTIAL: each binding shifts Local(k) by 1 for subsequent binds.
          // Shift the scrutinee reference (L0 at this scope) by k for the k-th binding.
          val bindRhs = binds.zipWithIndex.map { case ((expr, _), k) => shiftLocals(expr, k) }
          val bindNms = binds.map(_._2)
          val bodyScope = bindNms.reverse ++ sc
          val bodyExpr  = c.cond match
            case Some(g) => CT.If(convertExpr(g, bodyScope), convertExpr(c.body, bodyScope), failCT)
            case None    => convertExpr(c.body, bodyScope)
          val withBinds = if binds.isEmpty then bodyExpr else CT.Let(bindRhs, bodyExpr)
          conds.foldRight(withBinds) { (k, t) => CT.If(k, t, failCT) }
      CT.Let(List(scrut), caseChain(cases))
    else
      // Simple ctor match (no duplicates, no nested ctors) → CT.Match
      var default: Option[CT] = None
      val arms = List.newBuilder[Arm]
      cases.foreach { c =>
        val (ctorOpt, names) = convertPat(c.pat)
        val bodyScope        = names ++ scope
        val rawBody          = convertExpr(c.body, bodyScope)
        val body             = c.cond match
          case Some(g) => CT.If(convertExpr(g, bodyScope), rawBody, CT.App(CT.Global("__match_fail__"), Nil))
          case None    => rawBody
        ctorOpt match
          case None               => default = Some(body)
          case Some((tag, arity)) => arms += Arm(tag, arity, body)
      }
      CT.Match(scrut, arms.result(), default)

  private def needsGeneralChain(cases: List[Case]): Boolean =
    // True if there are duplicate outer ctor tags or any arm has complex sub-patterns
    val seen = collection.mutable.HashSet[String]()
    cases.exists { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(ctor, ac) =>
          val key = s"${ctorPatName(ctor)}/${ac.values.length}"
          val dup = !seen.add(key)
          dup || ac.values.exists {
            case _: Pat.Extract | _: Lit => true
            case Pat.Var(Term.Name(n)) if isCtorName(n) => true
            case _ => false
          }
        // Tuple with nested non-variable sub-patterns (e.g. `(x :: xs, y :: ys)`) needs
        // general if-chain so flattenPattern can recursively extract the sub-bindings.
        case Pat.Tuple(pats) =>
          pats.exists {
            case _: Pat.Extract | _: Pat.ExtractInfix | _: Lit => true
            case Pat.Var(Term.Name(n)) if isCtorName(n) => true
            case _ => false
          }
        case _ => false
    }

  /** Flatten a pattern into (conditions, ordered variable bindings).
   *  All CTs in conditions + bindings are evaluated in the SAME scope (before any Let).
   *  This avoids De Bruijn shifting issues when generating if-chains. */
  private def flattenPattern(pat: Pat, scrutRef: CT, scope: List[String]): (List[CT], List[(CT, String)]) = pat match
    case Pat.Wildcard() | Pat.Var(Term.Name("_")) => (Nil, Nil)
    case Pat.Var(Term.Name(n)) if !isCtorName(n) => (Nil, List((scrutRef, n)))
    case Pat.Var(Term.Name(n)) if isCtorName(n) =>
      (List(CT.Prim("__isTag__", List(scrutRef, CT.Lit(Const.CStr(n)), CT.Lit(Const.CInt(0))))), Nil)
    case Term.Name(n) if isCtorName(n) =>
      (List(CT.Prim("__isTag__", List(scrutRef, CT.Lit(Const.CStr(n)), CT.Lit(Const.CInt(0))))), Nil)
    case l: Lit =>
      (List(CT.Prim("__arith__", List(CT.Lit(Const.CStr("==")), scrutRef, convertExpr(l, scope)))), Nil)
    case Pat.Typed(inner, _) => flattenPattern(inner, scrutRef, scope)
    case Pat.ExtractInfix.After_4_6_0(h, Term.Name("::"), t) =>
      val head = h; val tail = t.values.headOption.getOrElse(Pat.Wildcard())
      flattenCtorPat("Cons", List(head, tail), scrutRef, scope)
    case Pat.Tuple(pats) =>
      flattenCtorPat(s"Tuple${pats.length}", pats.toList, scrutRef, scope)
    case Pat.Extract.After_4_6_0(ctor, ac) =>
      flattenCtorPat(ctorPatName(ctor), ac.values.toList, scrutRef, scope)
    case Pat.Alternative(alts) =>
      // Simplified: use first alternative only
      flattenPattern(alts.head, scrutRef, scope)
    case _ => (Nil, Nil)

  private def flattenCtorPat(tag: String, pats: List[Pat], scrutRef: CT, scope: List[String]): (List[CT], List[(CT, String)]) =
    val arity   = pats.length
    val tagCond = CT.Prim("__isTag__", List(scrutRef, CT.Lit(Const.CStr(tag)), CT.Lit(Const.CInt(arity))))
    val (subConds, subBinds) = pats.zipWithIndex.map { case (p, i) =>
      val fref = CT.Prim("fieldAt", List(scrutRef, CT.Lit(Const.CInt(i))))
      flattenPattern(p, fref, scope)  // all use current scope, no scope extension here
    }.unzip
    (tagCond :: subConds.flatten, subBinds.flatten)

  /** Shift all Local indices by `amount` in a pure expression (no binders). */
  private def shiftLocals(expr: CT, amount: Int): CT =
    if amount == 0 then expr
    else expr match
      case CT.Local(k)       => CT.Local(k + amount)
      case CT.Prim(op, args) => CT.Prim(op, args.map(a => shiftLocals(a, amount)))
      case other             => other  // Lit, Global — no locals

  /** Produce an optional condition term for a literal pattern match. */
  private def matchCond(pat: Pat, scrutRef: CT, scope: List[String]): Option[CT] = pat match
    case Pat.Wildcard()               => None
    case Pat.Var(Term.Name(n)) if !isCtorName(n) => None
    case l: Lit =>
      Some(CT.Prim("__arith__", List(CT.Lit(Const.CStr("==")), scrutRef, convertExpr(l, scope))))
    case _                            => None

  /** Returns (Some((tag, arity)) for ctor patterns, None for wildcard/var),
   *  and the list of bound names (newest-first for de Bruijn). */
  private def convertPat(pat: Pat): (Option[(String, Int)], List[String]) =
    pat match
      case Pat.Wildcard()                                   => (None, List("_"))
      // Scala3: uppercase stable-id patterns are Term.Name (not Pat.Var)
      case Term.Name(n) if isCtorName(n)                   => (Some((n, 0)), Nil)
      // Uppercase name in Pat.Var = constructor (enum case or zero-arity case class)
      case Pat.Var(Term.Name(n)) if isCtorName(n)          => (Some((n, 0)), Nil)
      case Pat.Var(Term.Name(n))                            => (None, List(n))
      case Pat.Typed(Pat.Wildcard(), _)                     => (None, List("_"))
      case Pat.Typed(Pat.Var(Term.Name(n)), _) if isCtorName(n) => (Some((n, 0)), Nil)
      case Pat.Typed(Pat.Var(Term.Name(n)), _)              => (None, List(n))
      case Pat.Extract.After_4_6_0(ctor, argClause) =>
        val tag   = ctorPatName(ctor)
        val pats  = argClause.values
        val names = pats.toList.flatMap {
          case Pat.Var(Term.Name(n))               => List(n)
          case Pat.Wildcard()                      => List("_")
          case Pat.Typed(Pat.Var(Term.Name(n)), _) => List(n)
          case _                                   => List("_")
        }.reverse  // reverse: last field = Local(0)
        (Some((tag, pats.length)), names)
      case Pat.ExtractInfix.After_4_6_0(head, Term.Name("::"), tail) =>
        val headName = head match { case Pat.Var(Term.Name(n)) => n; case _ => "_" }
        val tailName = tail.values.headOption match
          case Some(Pat.Var(Term.Name(n))) => n
          case _                           => "_"
        (Some(("Cons", 2)), List(tailName, headName))  // reversed: tail=Local(0), head=Local(1)
      case Pat.Tuple(pats) =>
        val arity = pats.length
        val names = pats.toList.flatMap {
          case Pat.Var(Term.Name(n)) => List(n)
          case Pat.Wildcard()        => List("_")
          case _                     => List("_")
        }.reverse
        (Some((s"Tuple$arity", arity)), names)
      case Pat.Alternative(alts) =>
        // Use first alternative (simplified — guards not supported for alts)
        convertPat(alts.head)
      case _: Lit =>
        // Literal pattern — becomes default + guard (simplified: emit as wildcard)
        (None, List("_"))
      case _ =>
        (None, List("_"))

  private def convertForYield(enums: List[Enumerator], body: Term, scope: List[String]): CT =
    enums match
      case Nil =>
        convertExpr(body, scope)
      case Enumerator.Generator(pat, rhs) :: rest =>
        val name     = pat match { case Pat.Var(Term.Name(n)) => n; case Pat.Tuple(_) => "_tup_"; case _ => "_gen_" }
        val q        = convertExpr(rhs, scope)
        val newScope = name :: scope
        val hasMoreGens = rest.exists { case _: Enumerator.Generator => true; case _ => false }
        if hasMoreGens then
          // Not last generator: flatMap to produce sub-lists
          val inner = CT.Lam(1, convertForYield(rest, body, newScope))
          CT.Prim("__method__", List(CT.Lit(Const.CStr("flatMap")), q, inner))
        else
          // Last generator: apply guards as filter, then map body
          val filtered = rest.foldLeft(q) {
            case (acc, Enumerator.Guard(cond)) =>
              CT.Prim("__method__", List(CT.Lit(Const.CStr("filter")), acc,
                CT.Lam(1, convertExpr(cond, newScope))))
            case (acc, _) => acc
          }
          // Handle tuple destructuring in last generator via PartialFunction pattern
          val bodyLam = pat match
            case Pat.Tuple(_) =>
              CT.Lam(1, convertMatch(CT.Local(0),
                List(Case(pat, None, body)), "_gen_" :: newScope))
            case _ => CT.Lam(1, convertExpr(body, newScope))
          CT.Prim("__method__", List(CT.Lit(Const.CStr("map")), filtered, bodyLam))
      case Enumerator.Val(pat, rhs) :: rest =>
        val name = pat match { case Pat.Var(Term.Name(n)) => n; case _ => "_val_" }
        CT.Let(List(convertExpr(rhs, scope)), convertForYield(rest, body, name :: scope))
      case Enumerator.Guard(cond) :: rest =>
        CT.If(convertExpr(cond, scope), convertForYield(rest, body, scope), CT.Lit(Const.CUnit))
      case _ :: rest =>
        convertForYield(rest, body, scope)

  /** True if the term contains a placeholder anywhere. */
  private def hasPH(t: Term): Boolean = t match
    case Term.Placeholder()                            => true
    case Term.Select(q, _)                             => hasPH(q)
    case Term.Apply.After_4_6_0(f, ac)                => hasPH(f) || ac.values.exists(hasPH)
    case Term.ApplyInfix.After_4_6_0(l, _, _, rc)     => hasPH(l) || rc.values.exists(hasPH)
    case Term.ApplyUnary(_, x)                         => hasPH(x)
    case _                                             => false

  /** Wrap a placeholder-containing expression into an anonymous function. */
  private def wrapIfPH(t: Term): Term =
    if hasPH(t) then Term.AnonymousFunction(t) else t

  /** Replace placeholders left-to-right with fresh names; returns (count, rewritten tree). */
  private def collectPlaceholders(t: Term): (Int, Term) =
    var count = 0
    def fresh(): Term.Name = { val n = Term.Name(s"_p$count"); count += 1; n }
    def walk(node: Term): Term = node match
      case Term.Placeholder()                                => fresh()
      case Term.Select(q, n)                                 => Term.Select(walk(q), n)
      case Term.Apply.After_4_6_0(f, argClause)             =>
        Term.Apply.After_4_6_0(walk(f), Term.ArgClause(argClause.values.map(walk)))
      case Term.ApplyInfix.After_4_6_0(l, op, tgs, rc)     =>
        Term.ApplyInfix.After_4_6_0(walk(l), op, tgs, Term.ArgClause(rc.values.map(walk)))
      case Term.ApplyUnary(op, x)                           => Term.ApplyUnary(op, walk(x))
      case other                                             => other
    val replaced = walk(t)
    (count, replaced)

  private def convertForDo(enums: List[Enumerator], body: Term, scope: List[String]): CT =
    enums match
      case Nil => convertExpr(body, scope)
      case Enumerator.Generator(pat, rhs) :: rest =>
        val name     = pat match { case Pat.Var(Term.Name(n)) => n; case Pat.Tuple(_) => "_tup_"; case _ => "_gen_" }
        val q        = convertExpr(rhs, scope)
        val newScope = name :: scope
        val filtered = rest.foldLeft(q) {
          case (acc, Enumerator.Guard(cond)) =>
            CT.Prim("__method__", List(CT.Lit(Const.CStr("filter")), acc,
              CT.Lam(1, convertExpr(cond, newScope))))
          case (acc, _) => acc
        }
        val bodyLam = pat match
          case Pat.Tuple(_) =>
            CT.Lam(1, convertMatch(CT.Local(0), List(Case(pat, None, body)), "_gen_" :: newScope))
          case _ => CT.Lam(1, convertForDo(rest.filterNot(_.isInstanceOf[Enumerator.Guard]), body, newScope))
        CT.Prim("__method__", List(CT.Lit(Const.CStr("foreach")), filtered, bodyLam))
      case _ :: rest => convertForDo(rest, body, scope)

  private def interleaveConcat(strs: List[CT], vals: List[CT]): CT =
    (strs, vals) match
      case (Nil, Nil)        => CT.Lit(Const.CStr(""))
      case (s :: Nil, Nil)   => s
      case (Nil, v :: Nil)   => v
      case (s :: sr, v :: vr) =>
        val sv   = CT.Prim("__arith__", List(CT.Lit(Const.CStr("++")), s, v))
        val rest = interleaveConcat(sr, vr)
        CT.Prim("__arith__", List(CT.Lit(Const.CStr("++")), sv, rest))
      case (s :: sr, Nil) => interleaveConcat(sr, Nil) match
        case CT.Lit(Const.CStr("")) => s
        case rest => CT.Prim("__arith__", List(CT.Lit(Const.CStr("++")), s, rest))
      case (Nil, v :: vr) =>
        CT.Prim("__arith__", List(CT.Lit(Const.CStr("++")), v, interleaveConcat(Nil, vr)))

  // ── Pattern / def helpers ──────────────────────────────────────────────────────

  private def ctorName(tpe: Type): String = tpe match
    case Type.Name(n)         => n
    case Type.Apply(t, _)     => ctorName(t)
    case Type.Select(_, Type.Name(n)) => n
    case _                    => tpe.syntax

  private def ctorPatName(ctor: Term): String = ctor match
    case Term.Name(n)                  => n
    case Term.Select(_, Term.Name(n)) => n
    case _                             => "Unknown"

  private def isCtorName(n: String): Boolean =
    n.nonEmpty && Character.isUpperCase(n.charAt(0))

  /** Uppercase names that are actually factory functions (registered as globals),
   *  not case class constructors. */
  private val functionConstructors: Set[String] =
    Set("Decimal", "BigDecimal", "BigInt",
        "RuntimeException", "Exception", "IllegalArgumentException",
        "IllegalStateException", "UnsupportedOperationException")

  private def allParams(d: Defn.Def): List[String] =
    d.paramClauseGroups.flatMap(_.paramClauses.flatMap(_.values.map(_.name.value)))

  /** True when a top-level val RHS is safe to evaluate eagerly as a global def.
   *  Collection / data constructors and arithmetic are pure; IO calls are not. */
  private def isPureValRhs(e: Term): Boolean = e match
    case _: Lit                   => true
    case _: Term.Name             => true
    case _: Term.Interpolate      => false // may involve I/O
    case Term.Tuple(args)         => args.forall(isPureValRhs)
    case Term.Apply.After_4_6_0(Term.Name(ctor), args) =>
      // Collection / data constructors that are always pure
      val pureCtors = Set("Map","List","Seq","Set","Vector","Array","Some","None",
                          "Nil","Right","Left","Option","Tuple","BigInt","BigDecimal",
                          "Currency","Money","Duration","Range","Pair","Triple")
      (pureCtors.contains(ctor) || ctor.head.isUpper) && args.forall(isPureValRhs)
    case Term.Apply.After_4_6_0(Term.Select(_, _), _) =>
      false // method calls (obj.method(args)) are always treated as effectful
    case Term.ApplyInfix.After_4_6_0(l, op, _, r) =>
      val opStr = op.value
      (opStr == "+" || opStr == "-" || opStr == "*" || opStr == "/" || opStr == "%" ||
       opStr == "->" || opStr == "++" || opStr == "::" || opStr == "&&" || opStr == "||") &&
        isPureValRhs(l) && r.forall(isPureValRhs)
    case Term.ApplyUnary(_, a)    => isPureValRhs(a)
    case Term.Select(q, _)        => isPureValRhs(q)
    case Term.Block(stats)        => stats.forall { case t: Term => isPureValRhs(t); case _ => false }
    case _                        => false

  private def isSimplePat(pats: List[Pat]): Boolean =
    pats.length == 1 && (pats.head match
      case Pat.Var(_) => true
      case _          => false)

  private def patName(pat: Pat): String = pat match
    case Pat.Var(Term.Name(n)) => n
    case _                     => "_"

  private def patNames(pat: Pat): List[String] = pat match
    case Pat.Var(Term.Name(n))  => List(n)
    case Pat.Tuple(pats)        => pats.flatMap(patNames).toList
    case Pat.Typed(inner, _)    => patNames(inner)
    case Pat.Extract.After_4_6_0(_, argClause) =>
      argClause.values.flatMap(patNames).toList
    case _                      => Nil

  private def countPlaceholders(t: Tree): Int = t match
    case _: Term.Placeholder => 1
    case _                   => t.children.map(countPlaceholders).sum

  private def isIntLit(t: Term): Boolean = t match
    case Lit.Int(_) | Lit.Long(_) => true
    case _                        => false

  private def varRhs(v: Defn.Var): Term = v match
    case Defn.Var.After_4_7_2(_, _, _, body: Term) => body
    case _                                          => Lit.Unit()

  /** Given registry: type-string → given name (for summon) */
  private val givenRegistry = collection.mutable.HashMap[String, String]()

  /** Field type registry: class name → field type strings (parallel to fieldRegistry). */
  private val fieldTypeRegistry = collection.mutable.LinkedHashMap[String, Vector[String]]()

  /** Classes with `derives AgentSchema` or `derives McpSchema`: class name → typeclass name. */
  private val derivedSchemas = collection.mutable.LinkedHashMap[String, String]()

  /** General typeclass derivation via `T.derived(mirror)`: class name → list of typeclass names.
   *  Populated from `case class X(...) derives Tc` for any Tc not handled specially. */
  private val generalDerives = collection.mutable.LinkedHashMap[String, List[String]]()

  /** Convert a `given name: T with { defs }` to a method-object prim. */
  /** extension (recv: T) def m(a...) = ... → accumulate (typeHead, arity, lam);
   *  flushExtensions emits one (possibly tag-dispatching) global per name.
   *  Used for top-level extension groups AND ones nested in `given ... with`. */
  private def emitExtensionGroup(eg: Defn.ExtensionGroup, defsB: collection.mutable.Builder[CDef, List[CDef]]): Unit =
    val recvName = extReceiverName(eg)
    val typeHead = extReceiverTypeHead(eg)
    extMethods(eg).foreach { m =>
      extensionMethods += m.name.value
      val params = recvName :: allParams(m)
      val scope  = params.reverse
      val body   = convertExpr(m.body, scope)
      val lam    = CT.Lam(params.length, body)
      extAccum.getOrElseUpdate(m.name.value, collection.mutable.ListBuffer.empty) += ((typeHead, params.length, lam))
    }

  private def convertGiven(g: Defn.Given): CT =
    // Extract the parent type sig (e.g. "Show[Int]") from template.inits
    val typeSig = g.templ.inits.headOption.fold("")(_.tpe.syntax)
    givenRegistry(typeSig) = g.name.value
    val methods = g.templ.stats.collect { case m: Defn.Def => m }
    val pairs = methods.flatMap { m =>
      val params = allParams(m)
      val scope2 = params.reverse
      val body   = convertExpr(m.body, scope2)
      val lam    = if params.isEmpty then CT.Lam(0, body) else CT.Lam(params.length, body)
      List(CT.Lit(Const.CStr(m.name.value)), lam)
    }
    CT.Prim("__mk_method_obj__", pairs)

  /** Generate a synthetic CDef for a `derives AgentSchema` / `derives McpSchema` case class.
   *  Calls the schema functions from agent.ssc / mcp.ssc at runtime to produce the instance. */
  private def makeDerivedSchemaCDef(className: String, tcName: String): Option[CDef] =
    val fields = fieldRegistry.getOrElse(className, return None).toList
    val types  = fieldTypeRegistry.getOrElse(className, Vector.fill(fields.length)("Any")).toList
    val n      = fields.length
    val genName = s"__${tcName.toLowerCase}_${className}__"
    givenRegistry(s"$tcName[$className]") = genName

    def strList(ss: List[String]): CT =
      ss.foldRight(CT.Ctor("Nil", Nil): CT)((s, acc) => CT.Ctor("Cons", List(CT.Lit(Const.CStr(s)), acc)))

    val labelsIR = strList(fields)
    val typesIR  = strList(types)

    // Decode fn: argsJson → PostTransaction(values(0), values(1), ...)
    // Lambda Local(0) = argsJson
    // After Let([jsonValue(argsJson)]): Local(0) = args
    // After Let([agentSchemaDecodeFields(labels, types, args)]): Local(0) = values
    val decodeFn = CT.Lam(1,
      CT.Let(List(CT.App(CT.Global("jsonValue"), List(CT.Local(0)))),
        CT.Let(List(CT.App(CT.Global("agentSchemaDecodeFields"), List(labelsIR, typesIR, CT.Local(0)))),
          CT.Ctor(className, (0 until n).toList.map(i => CT.App(CT.Local(0), List(CT.Lit(Const.CInt(i)))))))))

    val paramsJson = CT.App(CT.Global("objectSchema"), List(
      CT.App(CT.Global("agentSchemaProperties"), List(labelsIR, typesIR)),
      CT.App(CT.Global("agentSchemaRequired"),   List(labelsIR, typesIR))))

    Some(CDef(genName, CT.Ctor("AgentSchemaInstance", List(paramsJson, decodeFn))))

  /** Generate a synthetic CDef for `case class X(...) derives Tc` by calling `Tc.derived(mirror)`.
   *  The mirror is built from the class's field registry. */
  private def makeDerivedGeneralCDef(className: String, tcName: String): Option[CDef] =
    val fields = fieldRegistry.getOrElse(className, return None).toList
    val genName = s"__${tcName.toLowerCase}_${className}__"
    givenRegistry(s"$tcName[$className]") = genName
    val mirror = CT.Ctor("Mirror", List(
      CT.Lit(Const.CStr(className)),
      fields.foldRight(CT.Ctor("Nil", Nil): CT)((f, acc) => CT.Ctor("Cons", List(CT.Lit(Const.CStr(f)), acc))),
      fields.foldRight(CT.Ctor("Nil", Nil): CT)((_, acc) => CT.Ctor("Cons", List(CT.Lit(Const.CStr("Any")), acc)))
    ))
    // Call Tc.derived(mirror) — compiled as __method__("derived", TcCompanion, mirror)
    Some(CDef(genName, CT.Prim("__method__", List(CT.Lit(Const.CStr("derived")), CT.Global(tcName), mirror))))
