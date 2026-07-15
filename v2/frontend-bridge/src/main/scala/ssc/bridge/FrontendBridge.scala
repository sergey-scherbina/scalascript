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
  final case class SourceMetadata(
      name: Option[String],
      bundleId: Option[String],
      displayName: Option[String],
      version: Option[String],
      buildVersion: Option[String],
      frontend: Option[String],
      main: Option[String],
  )

  final case class CheckedSource(program: Program, metadata: SourceMetadata)

  /** Case class / enum field map: className → ordered field names.
   *  Built during first pass over stats; used when resolving field selects.
   *  LinkedHashMap preserves insertion order so fieldIndex returns the index from
   *  whichever case class was registered FIRST (import order) — deterministic. */
  private val fieldRegistry = collection.mutable.LinkedHashMap[String, Vector[String]]()

  /** Dynamic zero-arg runtime members must not be lowered by the global field
   *  registry without receiver type information. A case class field such as
   *  `RepoRef.head` made every `List.head` compile to eager `fieldAt`, bypassing
   *  method/effect lifting on effectful list receivers. Dynamic `__method__`
   *  still resolves same-named fields tag-aware for data receivers. */
  private val dynamicNoArgMemberNames: Set[String] = Set(
    "head", "tail", "last", "init",
    "isEmpty", "nonEmpty", "length", "size", "reverse",
    "toString", "trim", "toLowerCase", "toUpperCase"
  )

  /** Lookup field index for a class member field, if known — ONLY when the
   *  index is the SAME in every registered class containing the field. When
   *  two classes disagree (`transportError` = idx 1 in AgentStreamAttempt but
   *  idx 2 in AgentHttpAttempt), the old first-registered pick silently read
   *  the WRONG FIELD off the other class's values; ambiguous names now return
   *  None so the call site falls back to tag-aware `__method__` dispatch. */
  private def fieldIndex(name: String): Option[Int] =
    if dynamicNoArgMemberNames(name) then None
    else
      val idxs = fieldRegistry.values.collect {
        case fields if fields.contains(name) => fields.indexOf(name)
      }.toList.distinct
      idxs match
        case List(one) => Some(one)
        case _         => None

  /** Lookup field index for a specific class's field. */
  private def fieldIndexOf(className: String, name: String): Option[Int] =
    fieldRegistry.get(className).flatMap { fields =>
      val i = fields.indexOf(name); if i >= 0 then Some(i) else None
    }

  /** Runtime-shaped built-in types whose real value layout differs from their
   *  `.ssc` case-class declaration (extra runtime-injected fields). Their field
   *  layout is locked to the runtime layout (registerBuiltInBridgeTypes); the
   *  case-class declaration must NOT override it. See PluginBridge.requestFieldNames. */
  private val runtimeShapedTypes = Set("Request")
  /** Names for which the user DEFINED/imported a `case class` — these win over a
   *  plugin `functionConstructors`/method-object global of the same name (e.g.
   *  std/money.ssc `Money`/`Currency` vs the payments-bridge companions). */
  private val userCaseClasses = collection.mutable.HashSet[String]()
  /** The std/http.ssc `case class Request` declared shape. Only THIS lib
   *  declaration is locked out (its runtime value carries extra injected
   *  params/query/… — v2-route-params-stub); a user's OWN `case class Request`
   *  with a different shape must register and win, else it resolves as the HTTP
   *  Request and its fields read Stub in the batch path (user-request-collision). */
  private val httpLibRequestShape =
    Vector("method", "path", "headers", "body", "form", "files", "cookies", "session", "json")

  /** Parent type name → concrete case-class / enum-case tags that extend it.
   *  A `case _: Parent =>` type-ascription pattern lowers to a tag test over
   *  the parent's subtypes (a lone tag test only covers concrete leaf types). */
  private val subtypesOf = collection.mutable.LinkedHashMap[String, collection.mutable.LinkedHashSet[String]]()
  private def addSubtype(parent: String, child: String): Unit =
    if parent.nonEmpty && parent.head.isUpper && parent != child then
      subtypesOf.getOrElseUpdate(parent, collection.mutable.LinkedHashSet[String]()) += child

  /** Concrete runtime DataV tags a type name matches, or None when the type is
   *  untestable (unknown / type parameter / Any / scalar / non-DataV collection).
   *  None PRESERVES the historical unconditional-wildcard behavior for
   *  `case _: T =>`, so this only ever ADDS a discriminating test where the tag
   *  set is fully known — never a false negative on an unrecognized type. */
  private def typeTestTags(head: String): Option[List[String]] = head match
    case "" | "Any" | "AnyRef" | "AnyVal" | "Matchable" | "Object" => None
    case "Option"                                                  => Some(List("Some", "None"))
    case "Either"                                                  => Some(List("Left", "Right"))
    case t =>
      val acc  = collection.mutable.LinkedHashSet[String]()
      val seen = collection.mutable.HashSet[String]()
      def walk(n: String): Unit =
        if seen.add(n) then
          subtypesOf.get(n) match
            case Some(children) => children.foreach(walk)          // trait/enum parent → descend
            case None           => if fieldRegistry.contains(n) then acc += n  // concrete leaf
      walk(t)
      if acc.nonEmpty then Some(acc.toList) else None

  private def typeHeadOf(tpe: Type): String =
    // Strip type args / whitespace, then take the last path segment
    // (`scala.Option` → `Option`, `List[Int]` → `List`).
    val s   = tpe.syntax.takeWhile(c => c != '[' && c != ' ' && c != '(')
    val dot = s.lastIndexOf('.')
    if dot >= 0 then s.substring(dot + 1) else s

  /** Optional runtime type-test condition for a `case _: T =>` ascription. */
  private def typeAscriptionCond(tpe: Type, scrutRef: CT): Option[CT] =
    typeTestTags(typeHeadOf(tpe)).map { tags =>
      tags.map(t => CT.Prim("__isTag__", List(scrutRef, CT.Lit(Const.CStr(t)), CT.Lit(Const.CInt(-1)))))
        .reduceRight((a, b) => CT.If(a, CT.Lit(Const.CBool(true)), b))
    }

  /** Register a case class definition and its fields. */
  private def registerCaseClass(name: String, params: List[Term.Param]): Unit =
    val names = params.map(_.name.value).toVector
    // Lock ONLY the std/http.ssc lib Request (its exact declared shape); a
    // user's own Request (different fields) registers normally and overrides
    // the runtime layout for that compile unit.
    if runtimeShapedTypes(name) && names == httpLibRequestShape then return
    // A user/imported `case class X` must win over a plugin GLOBAL of the same
    // name — `X(args)` is a Ctor for THIS compile unit, not the plugin
    // companion's `apply`/factory (functionConstructors). Without this,
    // importing std/money.ssc's `case class Money(amount: Decimal, …)` /
    // `case class Currency(…)` still routed `Money(d, cur)` to the payments-bridge
    // `Money` companion, which coerces the amount to minor-units Long (busi's
    // whole money layer: Decimal 3000.00 → Int 300000 →
    // v2-money-decimal-regression, domain sweep 61/61→25/61).
    methodObjectNames -= name
    userCaseClasses += name
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
    opAnfNeeded = true
    sharedTopVars.clear()
    PluginBridge.clearDbs()
    PluginBridge.clearRemoteHandlers()
    fieldRegistry.clear()
    // Mirror is a built-in type with known fields at fixed indices.
    fieldRegistry("Mirror") = Vector("label", "elemLabels", "elemTypes")
    fieldTypeRegistry.clear()
    derivedSchemas.clear()
    generalDerives.clear()
    givenRegistry.clear()
    extensionMethods.clear()
    caseClassMethodNames.clear()
    extAccum.clear()
    defaultParams.clear()
    defaultParamTerms.clear()
    caseObjectNames.clear()
    methodObjectNames.clear()
    userCaseClasses.clear()
    entryValNames.clear()
    defContextBounds.clear()
    givenByTcHead.clear()
    tcParents.clear()
    defUsingBounds.clear()
    enumCaseNames.clear()
    objMethodDefaults.clear()
    objMethodVarargs.clear()
    sqlSectionIds.clear()
    yamlSectionIds.clear()
    hoistedValNames.clear()
    curriedExternMethods.clear()
    oneShotEffectNames.clear()
    multiEffectNames.clear()
    lastExtractDocOnly = false
    globalVarNames.clear()
    defParamNames.clear()
    varargDefs.clear()
    curriedVarargDefs.clear()
    zeroArgDefs.clear()
    parenlessUserDefs.clear()
    pendingRemoteHandlers = Vector.empty
    curryFirstClauseDefaults.clear()
    registerBuiltInBridgeTypes()

  private final case class RemoteHandlerSpec(
      name:         String,
      function:     String,
      path:         Option[String],
      requestType:  Option[String],
      responseType: Option[String]
  )

  private var pendingRemoteHandlers: Vector[RemoteHandlerSpec] = Vector.empty

  /** A `frontend:` front-matter value selects the frontend framework, mirroring v1
   *  (Interpreter.scala: `m.frontendFramework.foreach(FrontendFrameworks.setBackend)`).
   *  Without it, v2 leaves `FrontendFrameworks` unselected so `serve(view, port)`
   *  falls to `impls.head` (swiftui, native-only) and crashes
   *  "the active frontend backend 'swiftui' is native-only" instead of serving the
   *  web SPA (v2-serve-view-frontend-default). Only sets it when nothing was
   *  selected yet — an explicit `setFrontendFramework(...)` / CLI flag still wins. */
  private def selectFrontendFromFrontmatter(src: String): Unit =
    if scalascript.frontend.FrontendFrameworks.selectedName.isEmpty then
      frontMatterValue(src, "frontend").map(_.trim).filter(_.nonEmpty).foreach { name =>
        scala.util.Try(scalascript.frontend.FrontendFrameworks.setBackend(name))
      }

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
        // Register ANY scheme — registerDb normalizes sqlite:/h2:/postgres:/
        // mysql: to their jdbc: form (busi's databases: convention uses the
        // bare-scheme urls, pinned first-class by v1 JsGen/Wasm SQL tests).
        if rawUrl.nonEmpty then
          scala.util.Try(PluginBridge.registerDb(currentDb.get, rawUrl)).failed.foreach { e =>
            System.err.println(s"[v2] warn: could not register db '${currentDb.get}': ${e.getMessage}")
          }
        currentDb = None
    }

  private def collectRemoteHandlersFromSource(src: String): Vector[RemoteHandlerSpec] =
    val parsed = scala.util.Try(scalascript.parser.Parser.parse(src)).toOption
    val raw = parsed.toVector
      .flatMap(_.manifest.toVector)
      .flatMap(_.remoteHandlers)
      .map(h => RemoteHandlerSpec(h.name, h.function, h.path, h.requestType, h.responseType))
    val seen = collection.mutable.HashSet[String]()
    raw.filter(h => seen.add(h.name))

  private val remoteDefPat = """^(\s*)remote\s+def\s+([A-Za-z_][A-Za-z0-9_]*)\b(.*)$""".r

  private def preprocessRemoteDefs(code: String): String =
    if !code.contains("remote def") then return code
    code.linesWithSeparators.map { line =>
      val (body, sep) =
        if line.endsWith("\r\n") then (line.dropRight(2), "\r\n")
        else if line.endsWith("\n") then (line.dropRight(1), "\n")
        else if line.endsWith("\r") then (line.dropRight(1), "\r")
        else (line, "")
      body match
        case remoteDefPat(indent, name, tail) => s"${indent}def $name$tail$sep"
        case _                                => line
    }.mkString

  /** Extension method name registry: method name → receiver param name. */
  private val extensionMethods = collection.mutable.HashSet[String]()
  private val caseClassMethodNames = collection.mutable.HashSet[String]()

  /** Objects: `case object X` stays a zero-arg Ctor; `object X { def m }` is a
   *  method-obj GLOBAL — a bare uppercase name must not shadow it with Ctor(X). */
  private val caseObjectNames   = collection.mutable.HashSet[String]()
  private val methodObjectNames = collection.mutable.HashSet[String]()
  /** Names of vals routed to the ENTRY block (effectful or entry-dependent). */
  private val entryValNames     = collection.mutable.HashSet[String]()

  /** HTML element tag names the html plugin registers as globals (mirrors PluginBridge `containerTags`).
   *  A user `def` of one of these must win over the tag (common identifiers: main/label/title/form/…). */
  private val htmlTagNames: Set[String] = Set(
    "html", "head", "body", "title", "style", "script", "main",
    "section", "header", "footer", "nav", "article", "aside",
    "div", "span", "p", "a", "em", "strong", "small", "code", "pre",
    "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd",
    "table", "thead", "tbody", "tfoot", "tr", "td", "th",
    "form", "button", "label", "select", "option", "textarea",
    "figure", "figcaption", "blockquote")

  /** Context bounds — dictionary passing for the bridged pipeline.
   *  defContextBounds: def name → per-bound (tcName, drill) where drill says how
   *  to reach the TYPE WITNESS from the first user arg at runtime ("self" = the
   *  arg itself; "elem" = a list element, for `xs: List[A]` params).
   *  givenByTcHead: (tcName, typeHead) → given global name (from `given x: TC[T] with`). */
  private val defContextBounds = collection.mutable.LinkedHashMap[String, (List[(String, String)], Int)]()
  private val givenByTcHead    = collection.mutable.LinkedHashMap[(String, String), String]()
  /** (object, method) → (param names, raw default terms) for OBJECT methods
   *  with default params — Resource.text(s, mimeType = "text/plain") called
   *  with 1 arg crashed the 2-ary method lam (Local out of range). */
  private val objMethodDefaults =
    collection.mutable.LinkedHashMap[(String, String), (Vector[String], Vector[Option[scala.meta.Term]])]()

  /** Names of OBJECT-level `var` members — stored as global cells @name
   *  (CDef("@name", cell.new(init)); reads cell.get, writes cell.set). */
  private val globalVarNames = collection.mutable.HashSet[String]()

  /** TOP-LEVEL `var`s referenced inside a top-level def body. These must be
   *  SHARED cells: the entry block binds them as locals, but def bodies reach
   *  them as Global("@name") — without registration those were two DIFFERENT
   *  cells and mutations from defs silently vanished (busi local_journal:
   *  `def saveLocal(t) = localCell = t` never persisted; the sweep's whole
   *  "two facts" family). Populated by a convertStats pre-pass; the var decl
   *  then global.reg's its cell under "@name" (forced boxed — lcell is skipped
   *  for these so def-side cell.set/cell.get stay kind-coherent). */
  private val sharedTopVars = collection.mutable.HashSet[String]()

  /** Section ids that own a ```sql fence: `Section.sql` resolves to __sql_<Section>. */
  private val sqlSectionIds = collection.mutable.HashSet[String]()
  private val yamlSectionIds = collection.mutable.HashSet[String]()
  private val hoistedValNames = collection.mutable.HashSet[String]()
  /** Methods declared with TWO parameter clauses inside `extern class/trait`
   *  bodies (`def tool(name)(handler)`): their natives implement a CURRIED
   *  two-step protocol — the curried-merge conversion must NOT collapse
   *  `srv.tool(a, b) { h }` into one call. */
  private val curriedExternMethods = collection.mutable.HashSet[String]()
  /** Known curried PLUGIN-NATIVE methods provided as INSTANCE FIELDS (mcpServer's
   *  `srv`), not `extern def m(a)(b)` in an imported .ssc — the two-clause
   *  scanner never sees them, and the run path never calls resetState (only
   *  tests do), so a set seed wouldn't survive. Their natives return the
   *  second-step fn from the first application, so the call MUST stay two-step
   *  (merging feeds all args at once → the native's usage error). */
  private val knownCurriedNatives = Set("tool", "toolWithSchema", "resource", "prompt")
  /** Declared effect names retain their source multiplicity even though the
   *  compatibility parser normalizes `multi effect` to `effect`. Plain effects
   *  emit the bridge-private `__effect_oneshot__` marker with explicit effect
   *  and operation identity; multi effects retain reusable `__effect__`
   *  dispatch. Both markers also keep FastCode's long fast-tier away from
   *  unresolved Ops so they reach the lifting dispatch rather than an unboxing
   *  seam. */
  private val oneShotEffectNames = collection.mutable.HashSet[String]()
  private val multiEffectNames   = collection.mutable.HashSet[String]()
  /** Set when the last extractCode returned an EMPTY program because the
   *  source is a fence-less markdown document (doc-only). Run paths use it
   *  to print a note instead of a silent no-op. */
  @volatile var lastExtractDocOnly: Boolean = false
  /** Doc-only verdict of the TOP-LEVEL file of the last convertSource (import
   *  extractions must not clobber it — the note is about the user's file). */
  @volatile var lastTopDocOnly: Boolean = false

  /** (object, method) pairs whose method takes varargs — call sites wrap args in a list. */
  private val objMethodVarargs = collection.mutable.HashSet[(String, String)]()

  /** enum name → its zero-arg case names, for EnumName.values. */
  private val enumCaseNames = collection.mutable.LinkedHashMap[String, List[String]]()

  /** trait child → parent (one hop; walked transitively in dictArgsFor). */
  private val tcParents        = collection.mutable.HashMap[String, String]()
  /** USING-clause defs: name → (per-using (tc, drill), user param count).
   *  `def display[A](a: A)(using s: Show[A])` — call sites append a synthesized
   *  clause App(App(f, args), dicts); explicit trailing clause still accepted. */
  private val defUsingBounds   = collection.mutable.LinkedHashMap[String, (List[(String, String)], Int)]()

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
  private val curriedVarargDefs = collection.mutable.HashSet[String]()

  /** Zero-arg extern/def registry: `extern def foo: T` or `def foo: T = body` with no params.
   *  These are auto-called at the use site (like Scala's no-parens defs). */
  private val zeroArgDefs = collection.mutable.HashSet[String]()
  /** USER paren-less defs `def foo: T = body` only — they compile to Lam(0)
   *  so a bare reference must EVALUATE (0-arg call). Kept SEPARATE from the
   *  extern 0-arg set (zeroArgDefs): externs resolve to plugin VALUES, not
   *  thunks, and must NOT be auto-called (os-env's `def platform` = "JVM").*/
  private val parenlessUserDefs = collection.mutable.HashSet[String]()

  /** Multi-clause curried def first-clause defaults: for `def f(a: T = d)(args*)`, maps "f" →
   *  List of default CT for each first-clause param. Used to auto-inject defaults when calling
   *  `f(children...)` without the first-clause args (e.g. vstack(child1, child2) with no gap). */
  private val curryFirstClauseDefaults = collection.mutable.Map[String, List[CT]]()

  /** Build a List value from a sequence of CT expressions: List(a,b,c) → Cons(a,Cons(b,Cons(c,Nil))). */
  private def listOf(elems: List[CT]): CT =
    elems.foldRight(CT.Ctor("Nil", Nil): CT)((e, acc) => CT.Ctor("Cons", List(e, acc)))

  /** Synthesize missing trailing arguments from defaultParams when calling a def with fewer args. */
  /** Context-bound dictionary args for a call to `name` (Nil when none). The
   *  instance table for each bound is embedded at the call site; the runtime
   *  __resolve_given__ picks by the witness value's type head. */
  /** (typeHead, given) table for tc, honoring the typeclass hierarchy. */
  private def usingTableFor(tc: String): List[CT] =
    def satisfies(t: String): Boolean =
      var cur = t
      var hops = 0
      while cur != tc && hops < 8 && tcParents.contains(cur) do { cur = tcParents(cur); hops += 1 }
      cur == tc
    givenByTcHead.collect {
      case ((t, head), g) if satisfies(t) => List(CT.Lit(Const.CStr(head)), CT.Global(g))
    }.flatten.toList

  private def dictArgsFor(name: String, args: List[CT]): List[CT] =
    defContextBounds.get(name).fold(List.empty[CT]) { case (bs, userCount) =>
      if args.length != userCount then Nil  // explicit instance(s) passed — don't synthesize
      else bs.map { case (tc, drill) =>
        def satisfies(t: String): Boolean =
          var cur = t
          var hops = 0
          while cur != tc && hops < 8 && tcParents.contains(cur) do { cur = tcParents(cur); hops += 1 }
          cur == tc
        val table = givenByTcHead.collect {
          case ((t, head), g) if satisfies(t) => List(CT.Lit(Const.CStr(head)), CT.Global(g))
        }.flatten.toList
        val witness = args.headOption.getOrElse(CT.Lit(Const.CUnit))
        CT.Prim("__resolve_given__",
          CT.Lit(Const.CStr(tc)) :: CT.Lit(Const.CStr(drill)) :: witness :: table)
      }
    }

  private def fillDefaults(name: String, args: List[CT]): List[CT] =
    defaultParams.get(name).fold(args) { defs =>
      if args.length >= defs.length then args
      else args ++ defs.drop(args.length).collect { case Some(e) => e }
    }

  private def registerBuiltInBridgeTypes(): Unit =
    def registerRecord(
      name: String,
      fields: Vector[String],
      defaultsByField: Map[String, CT] = Map.empty,
      types: Vector[String] = Vector.empty
    ): Unit =
      fieldRegistry(name) = fields
      fieldTypeRegistry(name) =
        if types.nonEmpty then types else Vector.fill(fields.length)("Any")
      if defaultsByField.nonEmpty then
        defaultParams(name) = fields.map(f => defaultsByField.get(f))
      ssc.V2PluginRegistry.registerFieldNames(name, fields)

    val none: CT = CT.Ctor("None", Nil)
    val nil: CT = CT.Ctor("Nil", Nil)
    val emptyMap: CT = CT.Prim("__mk_map__", Nil)

    val serializeOptsFields = Vector("pretty", "indent", "omitXmlDecl")
    fieldRegistry("SerializeOpts") = serializeOptsFields
    fieldTypeRegistry("SerializeOpts") = Vector("Boolean", "String", "Boolean")
    defaultParams("SerializeOpts") = Vector(
      Some(CT.Lit(Const.CBool(false))),
      Some(CT.Lit(Const.CStr("  "))),
      Some(CT.Lit(Const.CBool(false)))
    )
    ssc.V2PluginRegistry.registerFieldNames("SerializeOpts", serializeOptsFields)
    ssc.V2PluginRegistry.registerFieldNames("TransformError", Vector("message"))
    // http `Request` carries runtime-injected params/query/… absent from its
    // std/http.ssc case class. Lock BOTH registries to the runtime layout so
    // `req.params(:name)` resolves the right slot instead of a Stub
    // (v2-route-params-stub). registerCaseClass skips runtimeShapedTypes.
    fieldRegistry("Request") = PluginBridge.requestFieldNames
    ssc.V2PluginRegistry.registerFieldNames("Request", PluginBridge.requestFieldNames)
    registerRecord("Currency", Vector("code", "scale", "symbol"), types = Vector("String", "Int", "String"))
    registerRecord("Money", Vector("minorUnits", "currency"), types = Vector("Long", "Currency"))
    Seq("IntentId", "CustomerId", "VaultId", "PlanId", "SubscriptionId", "RefundId",
      "DisputeId", "ChargeId", "MandateId", "TransferId", "RejectCode", "ReturnCode")
      .foreach(n => registerRecord(n, Vector("value"), types = Vector("String")))
    registerRecord("PaymentCapabilities", Vector(
      "supportsSubscriptions", "supportsSCA", "supports3DS2", "supportsACH",
      "supportsSEPA", "supportsApplePay", "supportsGooglePay", "supportsRefunds",
      "supportsPartialRefunds", "supportsDisputes", "supportsConnectedAccounts",
      "supportsMultiCurrency", "supportsMandates"),
      defaultsByField = Vector(
        "supportsSubscriptions", "supportsSCA", "supports3DS2", "supportsACH",
        "supportsSEPA", "supportsApplePay", "supportsGooglePay", "supportsRefunds",
        "supportsPartialRefunds", "supportsDisputes", "supportsConnectedAccounts",
        "supportsMultiCurrency", "supportsMandates").map(_ -> CT.Lit(Const.CBool(false))).toMap)
    registerRecord("SCAChallenge", Vector("provider", "redirectUrl", "returnUrl", "fingerprint"))
    registerRecord("Charge", Vector("id", "intentId", "amount", "paid", "receiptUrl", "balanceTransactionId"),
      defaultsByField = Map("receiptUrl" -> none, "balanceTransactionId" -> none))
    registerRecord("Card", Vector("token"))
    registerRecord("ApplePayCard", Vector("token"))
    registerRecord("GooglePayCard", Vector("token"))
    registerRecord("BankAccount", Vector("accountId"))
    registerRecord("Wallet", Vector("provider", "externalId"))
    registerRecord("SavedMethod", Vector("vaultId"))
    registerRecord("Fingerprint", Vector("value"))
    registerRecord("RequiresPaymentMethod", Vector("id", "amount", "metadata"),
      defaultsByField = Map("metadata" -> emptyMap))
    registerRecord("RequiresConfirmation", Vector("id", "amount", "method"))
    registerRecord("RequiresAction", Vector("id", "amount", "action"))
    registerRecord("Processing", Vector("id", "amount"))
    registerRecord("Succeeded", Vector("id", "amount", "charge"))
    registerRecord("Canceled", Vector("id", "reason"))
    registerRecord("Failed", Vector("id", "error", "retryable"))
    registerRecord("CreateIntentRequest", Vector(
      "amount", "method", "confirm", "customer", "captureMethod", "setupFutureUsage",
      "offSession", "mandateId", "scaExemptions", "metadata", "description", "returnUrl"),
      defaultsByField = Map(
        "method" -> none,
        "confirm" -> CT.Lit(Const.CBool(false)),
        "customer" -> none,
        "captureMethod" -> CT.Ctor("Automatic", Nil),
        "setupFutureUsage" -> none,
        "offSession" -> CT.Lit(Const.CBool(false)),
        "mandateId" -> none,
        "scaExemptions" -> nil,
        "metadata" -> emptyMap,
        "description" -> none,
        "returnUrl" -> none))
    registerRecord("CreateCustomerRequest", Vector("email", "name", "metadata"),
      defaultsByField = Map("name" -> none, "metadata" -> emptyMap))
    registerRecord("Customer", Vector("id", "email", "name", "metadata"))
    registerRecord("StoredMethod", Vector("vaultId", "last4", "brand", "expMonth", "expYear",
      "funding", "isDefault", "networkToken", "mandateId"),
      defaultsByField = Map("isDefault" -> CT.Lit(Const.CBool(false)), "networkToken" -> none, "mandateId" -> none))
    registerRecord("Daily", Vector("count"), defaultsByField = Map("count" -> CT.Lit(Const.CInt(1))))
    registerRecord("Weekly", Vector("count"), defaultsByField = Map("count" -> CT.Lit(Const.CInt(1))))
    registerRecord("Monthly", Vector("count"), defaultsByField = Map("count" -> CT.Lit(Const.CInt(1))))
    registerRecord("Yearly", Vector("count"), defaultsByField = Map("count" -> CT.Lit(Const.CInt(1))))
    registerRecord("CreatePlanRequest", Vector("amount", "interval", "trialPeriodDays", "metadata"),
      defaultsByField = Map("trialPeriodDays" -> none, "metadata" -> emptyMap))
    registerRecord("Plan", Vector("id", "amount", "interval", "trialPeriodDays", "metadata"))
    registerRecord("SubscribeOpts", Vector("trialPeriodDays", "defaultMethod", "metadata"),
      defaultsByField = Map("trialPeriodDays" -> none, "defaultMethod" -> none, "metadata" -> emptyMap))
    registerRecord("Subscription", Vector("id", "customerId", "planId", "status",
      "currentPeriodEnd", "cancelAtPeriodEnd", "trialEnd"))
    registerRecord("RefundRequest", Vector("intentId", "amount", "reason"),
      defaultsByField = Map("amount" -> none, "reason" -> CT.Ctor("RequestedByCustomer", Nil)))
    registerRecord("Refund", Vector("id", "intentId", "amount", "reason", "status"))
    registerRecord("DisputeEvidence", Vector("customerCommunication", "receipt",
      "shippingDocumentation", "uncategorizedText", "serviceDocumentation"),
      defaultsByField = Map("customerCommunication" -> none, "receipt" -> none,
        "shippingDocumentation" -> none, "uncategorizedText" -> none, "serviceDocumentation" -> none))
    registerRecord("Dispute", Vector("id", "intentId", "amount", "reason", "status", "dueDate", "evidence"))
    registerRecord("BankAccount", Vector(
      "iban", "accountNumber", "routingNumber", "bankCode", "pixKey", "holderName",
      "countryCode", "bic", "sortCode", "upiVpa", "zenginBankCode", "zenginBranchCode",
      "paynowProxy", "payid", "bsbNumber", "transitNumber", "institutionNumber",
      "email", "phone", "clabe"),
      defaultsByField = Vector("iban", "accountNumber", "routingNumber", "bankCode", "pixKey",
        "bic", "sortCode", "upiVpa", "zenginBankCode", "zenginBranchCode", "paynowProxy",
        "payid", "bsbNumber", "transitNumber", "institutionNumber", "email", "phone", "clabe")
        .map(_ -> none).toMap)
    registerRecord("InitiateTransferRequest", Vector(
      "rail", "amount", "sender", "recipient", "reference", "idempotencyKey",
      "sameDay", "scheduledDate", "metadata", "chargeBearer", "uetr"),
      defaultsByField = Map("sameDay" -> CT.Lit(Const.CBool(false)), "scheduledDate" -> none,
        "metadata" -> emptyMap, "chargeBearer" -> CT.Ctor("SHA", Nil), "uetr" -> none))
    registerRecord("BankTransfer", Vector("id", "rail", "amount", "sender", "recipient",
      "reference", "status", "createdAt", "settledAt", "returnedAt", "metadata",
      "uetr", "gpiTrail", "chargeBearer"),
      defaultsByField = Map("settledAt" -> none, "returnedAt" -> none, "metadata" -> emptyMap,
        "uetr" -> none, "gpiTrail" -> nil, "chargeBearer" -> none))
    registerRecord("InitiateDirectDebitRequest", Vector("rail", "amount", "mandateId",
      "creditorAccount", "debtorAccount", "creditorName", "reference", "idempotencyKey",
      "sameDay", "scheduledDate", "metadata"),
      defaultsByField = Map("sameDay" -> CT.Lit(Const.CBool(false)), "scheduledDate" -> none,
        "metadata" -> emptyMap))
    registerRecord("StaticConfig", Vector("pixKey", "merchantName", "merchantCity", "amount", "txid"),
      defaultsByField = Map("amount" -> none, "txid" -> CT.Lit(Const.CStr("***"))))
    registerRecord("DynamicConfig", Vector("cobvUrl", "merchantName", "merchantCity", "amount", "txid"),
      defaultsByField = Map("amount" -> none))
    registerRecord("PixConfig", Vector("pixApiUrl", "pixClientId", "pixClientSecret",
      "pixPixKey", "pixCertPath", "pixKeyPath"),
      defaultsByField = Map("pixCertPath" -> CT.Lit(Const.CStr("")), "pixKeyPath" -> CT.Lit(Const.CStr(""))))
    registerRecord("FedNowConfig", Vector("fednowApiUrl", "fednowCertPath", "fednowKeyPath",
      "fednowRoutingNumber", "fednowParticipantId"))
    methodObjectNames ++= Set(
      "MarkupCodec", "PureMarkupCodec", "SerializeOpts",
      "PaymentProvider", "Money", "Currency", "PixQrCode", "FedNowConfig",
      "Instant", "Thread")

  /** First pass: scan all stats and collect case class / enum / extension definitions. */
  private def registerTypes(stats: List[Stat]): Unit = stats.foreach {
    // Pre-register def param NAMES here (first pass) so a named-arg call that
    // textually PRECEDES the callee's definition still resolves positions —
    // std/mapreduce's `runDistributed` (line ~222) calls `collectResults`
    // (defined ~140 lines LOWER) with all-named args; second-pass-only
    // registration compiled those `name = value` args as assignments and the
    // callee received Units for every param. Defaults still register in the
    // second pass (they need convertExpr); names alone are enough here.
    case d: Defn.Def =>
      val ps = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
      if ps.nonEmpty then defParamNames(d.name.value) = ps.map(_.name.value).toVector
    case d: Defn.Class if d.mods.exists(_.is[Mod.Case]) =>
      val params = d.ctor.paramClauses.flatMap(_.values).toList
      registerCaseClass(d.name.value, params)
      // Record `extends Parent` so `case _: Parent =>` can tag-test this subtype.
      d.templ.inits.foreach { init => addSubtype(typeHeadOf(init.tpe), d.name.value) }
      d.templ.stats.collect { case m: Defn.Def => m }.foreach { m =>
        caseClassMethodNames += m.name.value
      }
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
    case d: Defn.Trait =>
      // Typeclass hierarchy: `trait Monoid[A] extends Semigroup[A]` — a Monoid
      // given satisfies a Semigroup context bound (dictArgsFor walks this).
      d.templ.inits.headOption.foreach { init =>
        val parent = init.tpe.syntax.takeWhile(c => c != '[' && c != ' ')
        if parent.nonEmpty && parent.head.isUpper then tcParents(d.name.value) = parent
      }
      // Sealed-hierarchy chain: `trait Child extends Parent` — so a `case _: Parent`
      // test descends through intermediate traits to the concrete leaf tags.
      d.templ.inits.foreach { init => addSubtype(typeHeadOf(init.tpe), d.name.value) }
    case d: Defn.Object =>
      if d.mods.exists(_.is[Mod.Case]) then caseObjectNames += d.name.value
      else if d.templ.stats.exists(_.isInstanceOf[Defn.Def]) then methodObjectNames += d.name.value
    case Defn.Enum(_, name, _, _, templ) =>
      val caseNames = List.newBuilder[String]
      templ.stats.foreach {
        case ec: Defn.EnumCase =>
          val params = ec.ctor.paramClauses.flatMap(_.values).toList
          registerCaseClass(ec.name.value, params)
          addSubtype(name.value, ec.name.value)  // enum case is a subtype of the enum
          if params.isEmpty then caseNames += ec.name.value
        // `case Red, Green` — comma-grouped zero-arg cases parse as ONE
        // RepeatedEnumCase, not per-case EnumCase; register each so `case _: Enum`
        // sees them as subtypes (they carry no fields → registerCaseClass(_, Nil)).
        case rec: Defn.RepeatedEnumCase =>
          rec.cases.foreach { c =>
            registerCaseClass(c.value, Nil)
            addSubtype(name.value, c.value)
            caseNames += c.value
          }
        case _ => ()
      }
      enumCaseNames(name.value) = caseNames.result()
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
  /** While converting extension impl BODIES: the method's own name — a same-name
   *  receiver call inside (`def map(f) = fa.map(f)`) must hit the BUILTIN member
   *  dispatch, not the extension global (members beat extensions in Scala; the
   *  extension route self-recursed → infinite loop in std monad instances). */
  private var suppressExtName: Option[String] = None

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

  def convertSourceWithMetadata(
      src: String,
      fileDir: Option[java.io.File] = None,
  ): CheckedSource =
    val metadata = SourceMetadata(
      name = topLevelFrontMatterValue(src, "name"),
      bundleId = topLevelFrontMatterValue(src, "bundle-id"),
      displayName = topLevelFrontMatterValue(src, "display-name"),
      version = topLevelFrontMatterValue(src, "version"),
      buildVersion = topLevelFrontMatterValue(src, "build-version"),
      frontend = topLevelFrontMatterValue(src, "frontend"),
      main = topLevelFrontMatterValue(src, "main"),
    )
    CheckedSource(convertSource(src, fileDir, metadata.main), metadata)

  /** Parse a source string and convert to Core IR Program.
   *  Supports script mode: bare expressions at the top level are wrapped in a block.
   *  Handles .ssc file format: optional shebang + YAML front matter + markdown prose +
   *  ```scalascript...``` fence; if no fence, uses the whole source. */
  def convertSource(
      src0: String,
      fileDir: Option[java.io.File] = None,
      manifestMain: Option[String] = None,
  ): Program =
    // Quoted-macro expansion pre-pass (v1 MacroCodegen) — the bridge has no
    // conversion for splice syntax, so expand call sites in the TEXT first.
    val src = if System.getenv("SSC_NO_MACRO_PREPASS") != null then src0 else PluginBridge.expandMacrosInSource(src0, fileDir)
    // Expose the parsed document to content-introspection natives
    // (contentBlock/contentData read markdown tables & yaml fences from it).
    PluginBridge.setDocumentFromSource(src)
    // Reset per-file state so batch runs don't pollute each other
    fieldRegistry.clear()
    extensionMethods.clear()
    caseClassMethodNames.clear()
    extAccum.clear()
    defaultParams.clear()
    defaultParamTerms.clear()
    defParamNames.clear()
    varargDefs.clear()
    curriedVarargDefs.clear()
    zeroArgDefs.clear()
    parenlessUserDefs.clear()
    oneShotEffectNames.clear()
    multiEffectNames.clear()
    PluginBridge.clearRemoteHandlers()
    pendingRemoteHandlers = Vector.empty
    curryFirstClauseDefaults.clear()
    registerBuiltInBridgeTypes()
    // Pre-register param names for plugins that accept named args (openapi, etc.)
    defParamNames("openapi") = Vector("summary", "description", "tags", "deprecated", "security")
    defaultParams("openapi") = Vector(
      Some(CT.Lit(Const.CStr(""))),       // summary
      Some(CT.Lit(Const.CStr(""))),       // description
      Some(CT.Ctor("Nil", Nil)),          // tags
      Some(CT.Lit(Const.CBool(false))),   // deprecated
      Some(CT.Ctor("Nil", Nil))           // security
    )
    defParamNames("pwa") = Vector(
      "name", "shortName", "description", "themeColor", "backgroundColor",
      "display", "startUrl", "icons", "precache", "cacheVersion",
      "networkFirst", "offlineHtml", "maskableIcon"
    )
    defaultParams("pwa") = Vector(
      None,                               // name
      Some(CT.Lit(Const.CStr(""))),       // shortName
      Some(CT.Lit(Const.CStr(""))),       // description
      Some(CT.Lit(Const.CStr("#ffffff"))), // themeColor
      Some(CT.Lit(Const.CStr("#ffffff"))), // backgroundColor
      Some(CT.Lit(Const.CStr("standalone"))),
      Some(CT.Lit(Const.CStr("/"))),
      Some(CT.Ctor("Nil", Nil)),          // icons
      Some(CT.Ctor("Nil", Nil)),          // precache
      Some(CT.Lit(Const.CStr("v1"))),
      Some(CT.Ctor("Nil", Nil)),          // networkFirst
      Some(CT.Lit(Const.CStr(""))),
      Some(CT.Lit(Const.CStr("")))        // maskableIcon
    )
    parseDatabasesFromFrontmatter(src)
    selectFrontendFromFrontmatter(src)
    pendingRemoteHandlers = collectRemoteHandlersFromSource(src)
    val resolvedImports = resolveImportsCode(src, fileDir)
    val merged = resolvedImports.code
    // Op-arg lifting is only NEEDED by programs where a raw effect Op can
    // materialize: typed `handle` programs / `effect` declarations (context-based
    // runners intercept ops BEFORE an Op value is built — viaCtx in Runtime's
    // effect dispatch). Effect-free programs skip the pass entirely so its Let
    // wrapping costs nothing on hot loops (pattern-match-heavy went 3-4x slower
    // with the pass unconditionally on). False positives (the words in prose)
    // only re-enable the pass — a perf tax, never a semantics change.
    opAnfNeeded = merged.contains("effect ") || merged.contains("handle")
    val processed = desugarListLiterals(
      stripExternDecls(preprocessAtAnnotations(preprocessRemoteDefs(merged))))
    val stats = parseStats(processed)
    val program = convertStats(stats, manifestMain)
    val sourceRefs = nativeUiDefinitionSources(stats, processed)
    ssc.NativeUiSites.annotate(program, ssc.NativeUiSites.Config(
      eligibleSymbols = resolvedImports.nativeUiSymbols,
      sourcesByDefinition = sourceRefs,
      entrySource = ssc.NativeUiSites.SourceRef("<entry>")))

  private val externBareAnnotation = """^@[A-Za-z_][A-Za-z0-9_]*\s*(\(.*\))?\s*$""".r

  /** Strip ScalaScript-specific `extern` declarations that scalameta cannot parse.
   *  Handles extern def/val (single or multi-line via open parens) and
   *  extern class/trait/object (strip until indentation returns to base level).
   *
   *  Also strips a bare backend-hint annotation (`@js(...)`, `@jvm(...)`, ...) that
   *  immediately precedes one of these — those annotations exist to tell a specific
   *  backend how to implement the extern, and scalameta never sees the extern decl
   *  itself (stripped below) for it to attach to. Left unstripped, ONE such orphaned
   *  annotation happens to parse anyway (it silently attaches to whatever real
   *  definition follows), but a SECOND orphaned annotation later in the same file has
   *  no real definition between it and the first — "expected start of definition".
   *  Reproduced minimally: two `@jvm("...") extern def foo(): Unit` pairs in one file
   *  fail; the same pair once, or two bare (unannotated) extern defs, both parse fine. */
  private def stripExternDecls(code: String): String =
    val lines = code.linesWithSeparators.toArray
    val sb = new java.lang.StringBuilder(code.length)
    var i = 0
    def nextNonBlankIsExtern(from: Int): Boolean =
      var j = from
      while j < lines.length && lines(j).isBlank do j += 1
      j < lines.length && {
        val s = lines(j).stripLeading()
        s.startsWith("extern def") || s.startsWith("extern val") ||
        s.startsWith("extern class") || s.startsWith("extern trait") || s.startsWith("extern object")
      }
    while i < lines.length do
      val line = lines(i)
      val t = line.stripLeading()
      if externBareAnnotation.matches(t) && nextNonBlankIsExtern(i + 1) then
        sb.append("//").append(line); i += 1
      else if t.startsWith("extern def") || t.startsWith("extern val") then
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
        val curriedDef = """def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*\(""".r
        while i < lines.length && !done do
          val next = lines(i)
          val stripped = next.stripLeading()
          val indent = next.length - stripped.length
          if stripped.isEmpty || indent > baseIndent then
            // remember two-clause method decls: their natives are curried
            curriedDef.findFirstMatchIn(stripped).foreach(m => curriedExternMethods += m.group(1))
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
    // Preserve declaration multiplicity BEFORE normalizing the bridge input:
    // ScalaMeta cannot parse the `multi` modifier, but runtime dispatch must not
    // silently upgrade a plain effect to reusable. Anchoring the plain pattern
    // at `effect` keeps `multi effect` out of the one-shot set.
    val noMulti =
      val multiDecl = """(?m)^\s*multi\s+effect\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""".r
      val oneShotDecl = """(?m)^\s*effect\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""".r
      multiDecl.findAllMatchIn(code).foreach(m => multiEffectNames += m.group(1))
      oneShotDecl.findAllMatchIn(code).foreach(m => oneShotEffectNames += m.group(1))
      if !code.contains("multi") then code
      else """(?m)^(\s*)multi\s+effect(\s+)""".r.replaceAllIn(code, m =>
        s"${m.group(1)}effect${m.group(2)}")
    if !noMulti.contains("@openapi") then noMulti
    else noMulti.replace("@openapi(", "openapi(")

  private def desugarListLiterals(code: String): String =
    if !code.contains("[") then return code
    val sb = new java.lang.StringBuilder(code.length + 64)
    // Stack: true = we opened List( for this bracket, false = raw [ emitted verbatim
    val stack = new java.util.ArrayDeque[Boolean]()
    var i = 0
    while i < code.length do
      code.charAt(i) match
        // TRIPLE-QUOTED strings copy verbatim with NO escape processing — the
        // single-quote scanner treated \" inside them as an escape, shifted
        // its quote pairing, and rewrote [1,2] INSIDE a later string literal
        // into List(1,2) (rozum-agent's response bodies came out mangled).
        case '"' if i + 2 < code.length && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"' =>
          sb.append("\"\"\""); i += 3
          var tdone = false
          while i < code.length && !tdone do
            if code.charAt(i) == '"' && i + 2 < code.length
               && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"' then
              // consume ALL consecutive quotes (Scala: """…x"""" ends with a quote)
              var q = 0
              while i + q < code.length && code.charAt(i + q) == '"' do q += 1
              var k = 0
              while k < q do { sb.append('"'); k += 1 }
              i += q; tdone = true
            else
              sb.append(code.charAt(i)); i += 1
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
          // Type-lambda `[A] =>> ...`: the bracket is a TYPE-param clause, not
          // a list literal — leave it for scala.meta (v2 erases the alias).
          val closeIdx = code.indexOf(']', i)
          val isTypeLambda = closeIdx > 0 && {
            var k = closeIdx + 1
            while k < code.length && (code.charAt(k) == ' ' || code.charAt(k) == '\t') do k += 1
            k + 2 < code.length && code.substring(k, k + 3) == "=>>"
          }
          if isTypeLambda then { sb.append('['); i += 1 }
          else {
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
          val prevIsWhitespace = i > 0 && code.charAt(i - 1).isWhitespace
          val spacedAfterOperator =
            prevIsWhitespace && "+-*/%|&<>!~^?=".contains(prevNonWs)
          val exprPos = prevNonWs match
            case '=' | '(' | ',' | '{' | '[' | '\n' | ';' | ':' => true
            case _ if spacedAfterOperator => true
            case _ => Set("then","else","return","yield","do","in","of","by","if","while","to","until","match","case","=>")(prevToken)
          if exprPos then
            sb.append("List("); stack.push(true)
          else
            sb.append('[');    stack.push(false)
          i += 1
          }
        case ']' =>
          if !stack.isEmpty && stack.pop() then sb.append(')')
          else sb.append(']')
          i += 1
        case c =>
          sb.append(c); i += 1
    sb.toString

  private final case class ResolvedImports(code: String, nativeUiSymbols: Set[String])
  private val sourceMarkerPrefix = "//__ssc_source__:"

  private def markedSource(file: String, code: String): String =
    val encoded = java.util.Base64.getUrlEncoder.withoutPadding()
      .encodeToString(file.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    s"$sourceMarkerPrefix$encoded\n$code"

  private def nativeUiDefinitionSources(
      stats: List[Stat],
      processed: String): Map[String, ssc.NativeUiSites.SourceRef] =
    val markers = processed.linesIterator.zipWithIndex.flatMap { case (line, index) =>
      if line.startsWith(sourceMarkerPrefix) then
        val encoded = line.drop(sourceMarkerPrefix.length)
        scala.util.Try(new String(
          java.util.Base64.getUrlDecoder.decode(encoded),
          java.nio.charset.StandardCharsets.UTF_8)).toOption.map(index -> _)
      else None
    }.toVector

    def ref(tree: Tree): ssc.NativeUiSites.SourceRef =
      val line = if tree.pos != Position.None then tree.pos.startLine else 0
      val (markerLine, file) = markers.reverseIterator.find(_._1 <= line).getOrElse(-1 -> "<entry>")
      val sourceLine = if markerLine < 0 then line + 1 else line - markerLine
      val column = if tree.pos != Position.None then tree.pos.startColumn + 1 else 0
      ssc.NativeUiSites.SourceRef(file, sourceLine.max(0), column)

    stats.flatMap {
      case definition: Defn.Def => Some(definition.name.value -> ref(definition))
      case definition: Defn.Object => Some(definition.name.value -> ref(definition))
      case _ => None
    }.toMap

  /** Collect std-import lines `[names](path.ssc)` from the document body (outside fences),
   *  load each referenced file, and prepend their code before the main code block.
   *  Falls back gracefully if a path can't be resolved. */
  private def resolveImportsCode(src: String, fileDir: Option[java.io.File]): ResolvedImports =
    val seen    = collection.mutable.HashSet[String]()
    val ordered = collection.mutable.ListBuffer.empty[(String, String, String)] // (canonical, display, code)
    val nativeUiSymbols = collection.mutable.LinkedHashSet.empty[String]
    val importPat = """\[([^\]]+)\]\(([^)]+)\)""".r
    // fenceOnly=true: only scan outside fences (top-level user files, where imports are prose).
    // fenceOnly=false: scan ALL lines (stdlib files where imports live inside code fences).
    def collectImports(source: String, dir: Option[java.io.File], fenceOnly: Boolean): Unit =
      var inFence = false
      // Multi-line import directives — `[A,\n  B,\n  C](path.ssc)` — must be JOINED
      // before the per-line regex sees them (std/parsing's imports span 3 lines and
      // were silently never loaded → unbound runParserAll).
      val joined = collection.mutable.ListBuffer.empty[String]
      val pending = new StringBuilder
      source.linesIterator.foreach { raw =>
        if pending.nonEmpty then
          pending.append(' ').append(raw.trim)
          if raw.contains(")") then { joined += pending.toString; pending.clear() }
        else if raw.trim.startsWith("[") && raw.trim.endsWith(",") && !raw.contains("](") then
          pending.append(raw.trim)
        else joined += raw
      }
      if pending.nonEmpty then joined += pending.toString
      joined.foreach { line =>
        if line.startsWith("```") then inFence = !inFence
        else if !fenceOnly || !inFence then
          importPat.findAllMatchIn(line).foreach { m =>
            val rel = m.group(2)
            // Only file imports: markdown links ([buy](/buy)) and package-registry
            // refs ([a, b](pkg:ns/name:1.0)) are NOT import directives to resolve.
            // Import paths: .ssc/.ssc0 files, or extensionless DIRECTORY imports
            // (resolved to index.ssc). Reject absolute markdown links (/buy),
            // pkg-registry refs, urls, and anything with spaces or foreign
            // extensions (README.md etc. are prose links, not imports).
            val base = rel.split("/").last
            if !rel.startsWith("/") && !rel.startsWith("pkg:") && !rel.contains("://")
               && !rel.contains(" ")
               && (rel.endsWith(".ssc") || rel.endsWith(".ssc0") || !base.contains(".")) then
            resolveStdPathWithFile(rel, dir).foreach { case (content, resolvedFile) =>
              val key = resolvedFile.getCanonicalPath
              if seen.add(key) then
                PluginBridge.registerImportedContent(rel, resolvedFile, content)
                val normalized = key.replace(java.io.File.separatorChar, '/')
                if normalized.contains("/runtime/std/ui/") then
                  "[A-Za-z_][A-Za-z0-9_]*".r.findAllIn(m.group(1)).foreach { symbol =>
                    if ssc.NativeUiSites.annotatedSymbols(symbol) then nativeUiSymbols += symbol
                  }
                // DFS: recurse first so dependencies come before their dependents
                collectImports(content, Some(resolvedFile.getParentFile), fenceOnly = false)
                ordered += ((key, rel, extractCode(content, allFences = true)))
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
    val prelude = ordered.collect {
      case (_, display, code) if code.nonEmpty => markedSource(display, code)
    }.mkString("\n")
    // v1 semantics: ALL scalascript fences of the entry file run in document
    // order (first-fence-only silently dropped every later section — the T4.4
    // output-equality suite exposed it on multi-section conformance files).
    lastExtractDocOnly = false
    val mainCode = extractCode(src, allFences = true)
    lastTopDocOnly = lastExtractDocOnly
    val markedMain = markedSource("<entry>", mainCode)
    val code = if prelude.isEmpty then markedMain else s"$prelude\n$markedMain"
    ResolvedImports(code, nativeUiSymbols.toSet)

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
      // Mirror ImportResolver.resolve's secondary fallback: the launcher sets
      // ssc.lib.path to the INSTALL ROOT (parent of bin/), where std/ lives at
      // runtime/std (the tracked runtime → v1/runtime symlink). Without this,
      // std/ imports on the v2 lane resolved only when os.pwd happened to be
      // inside the repo — busi's `std/money.ssc` import was unbound from its
      // own cwd while the v1 lane resolved it fine.
      .orElse(scalascript.imports.ImportResolver.libPath.flatMap { root =>
        tryOsPath(root / "runtime" / os.RelPath(rel))
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
      // Pre-v1/-move import paths: examples still say `../runtime/std/mapreduce/x.ssc`,
      // which no longer exists relative to examples/ (runtime/ moved to v1/runtime/).
      // The v1 ImportResolver falls back by std/ suffix; mirror that here — extract
      // the `std/...` tail and resolve it through the same chain (once; the tail
      // differs from `rel` so this cannot loop).
      .orElse {
        val i = rel.indexOf("std/")
        if i > 0 then resolveStdPathWithFile(rel.substring(i), fileDir) else None
      }

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
    // Package-registry import: [a, b](pkg:ns/name:1.0) — contains ':' so it must
    // be accepted BEFORE the operator quick-reject below.
    if t.matches("""\[[^\]]*\]\(pkg:[^)]+\)""") then return true
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
        if t.contains(".ssc)") || t.contains(".ssc0)") || t.matches("""\[[^\]]*\]\(pkg:[^)]+\)""") then
          i += 1 // single-line import: skip it
        else
          // Multi-line import ONLY when a closing `](….ssc)` line actually follows
          // and every line until then is an ident-list continuation. A bare `[`
          // opening a multi-line list literal (or `[a, b,` …) that never closes
          // with `.ssc)` is CODE — emit it and keep scanning, never swallow the
          // rest of the fence (busi datatable-static-spa parse crash on --v2).
          var j = i + 1
          var foundClose = false
          var stillImport = true
          while j < lines.length && !foundClose && stillImport do
            val tj = lines(j).trim
            if tj.contains(".ssc)") || tj.contains(".ssc0)") then foundClose = true
            else if tj.isEmpty || !tj.matches("""[A-Za-z0-9_,\s]+""") then stillImport = false
            else j += 1
          if foundClose then
            i = j + 1 // consume the whole multi-line import directive
          else
            if sb.nonEmpty then sb.append('\n')
            sb.append(lines(i)); i += 1 // list-literal opener, not an import — keep as code
      else
        if sb.nonEmpty then sb.append('\n')
        sb.append(lines(i))
        i += 1
    sb.toString

  private def frontMatterValue(raw: String, keys: String*): Option[String] =
    val noShebang = if raw.startsWith("#!/") then raw.dropWhile(_ != '\n').drop(1) else raw
    if !noShebang.stripLeading().startsWith("---") then None
    else
      val start = noShebang.indexOf("---")
      val end   = noShebang.indexOf("\n---", start + 3)
      if end < 0 then None
      else
        val keySet = keys.toSet
        noShebang.slice(start + 3, end).linesIterator.flatMap { line =>
          val t = line.trim
          val colon = t.indexOf(':')
          if colon <= 0 then None
          else
            val key = t.take(colon).trim
            if !keySet(key) then None
            else
              val rawValue = t.drop(colon + 1).takeWhile(_ != '#').trim
              Some(rawValue.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'"))
        }.toSeq.headOption

  private def topLevelFrontMatterValue(raw: String, key: String): Option[String] =
    val noShebang = if raw.startsWith("#!/") then raw.dropWhile(_ != '\n').drop(1) else raw
    if !noShebang.stripLeading().startsWith("---") then None
    else
      val start = noShebang.indexOf("---")
      val end = noShebang.indexOf("\n---", start + 3)
      if end < 0 then None
      else
        noShebang.slice(start + 3, end).linesIterator.collectFirst {
          case line if line.nonEmpty && !line.head.isWhitespace && line.startsWith(key + ":") =>
            line.drop(key.length + 1).takeWhile(_ != '#').trim
              .stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")
        }

  private def truthyFrontMatterValue(value: String): Boolean =
    value.trim.toLowerCase match
      case "true" | "yes" | "on" | "1" | "runnable" => true
      case _ => false

  def extractCode(raw: String, allFences: Boolean = false): String =
    val noShebang = if raw.startsWith("#!/") then raw.dropWhile(_ != '\n').drop(1) else raw
    val noFront =
      if noShebang.stripLeading().startsWith("---") then
        val start = noShebang.indexOf("---")
        val after = noShebang.indexOf("\n---", start + 3)
        if after >= 0 then noShebang.drop(after + 4) else noShebang
      else noShebang
    val fence = "```scalascript\n"
    val scalaScriptFenceRe = """(?m)^```(scalascript|ssc)([^\n]*)\n""".r
    val scalaFenceRe       = """(?m)^```scala([^\n]*)\n""".r
    val hasScalaScriptFences = scalaScriptFenceRe.findFirstIn(noFront).isDefined
    val hasScalaFences       = scalaFenceRe.findFirstIn(noFront).isDefined
    val hasSqlFences         = noFront.contains("```sql") || noFront.contains("```transaction")
    val explicitScalaFenceRun =
      frontMatterValue(raw, "runScalaFences", "run-scala-fences").exists(truthyFrontMatterValue) ||
        frontMatterValue(raw, "scalaFences", "scala-fences").exists(truthyFrontMatterValue)
    val runnableScalaFences = hasScalaFences && (!hasScalaScriptFences || explicitScalaFenceRun)
    // Any runnable fence counts for the all-fences path. Standard `scala`
    // fences are executable when they are the document's runnable source
    // (standard-Scala-only examples), or when a mixed document explicitly opts
    // into running them via front matter. They stay illustrative by default in
    // mixed ScalaScript docs unless SQL extraction already needs them.
    val anyRunnableFence =
      allFences && (hasScalaScriptFences || hasSqlFences || runnableScalaFences)
    // line-start anchored for the same string-literal reason as anyFence below
    val firstFence =
      if anyRunnableFence then 0
      else
        val i = noFront.indexOf(fence)
        if i <= 0 then i
        else
          var j = i
          while j > 0 && noFront.charAt(j - 1) != '\n' do j = noFront.indexOf(fence, j + 1)
          j
    // No fence: if the content looks like markdown prose (starts with # or ---), it's
    // a doc-only example with no runnable code — return empty so it compiles as a no-op.
    // Otherwise treat as raw Scala source (for test-style usage with no front matter).
    if firstFence < 0 then
      val trimmed = noFront.trim
      if trimmed.startsWith("#") || trimmed.startsWith("[") || trimmed.isEmpty then
        lastExtractDocOnly = true
        ""
      else trimmed
    else if !allFences then
      // Original behavior: only extract the first fence
      val codeStart = firstFence + fence.length
      val fenceEnd  = noFront.indexOf("\n```", codeStart)
      val code = if fenceEnd < 0 then noFront.drop(codeStart).trim
                 else noFront.slice(codeStart, fenceEnd)
      filterImportLines(code)
    else
      // Collect ALL runnable fences IN DOCUMENT ORDER: scalascript + scala code,
      // and ```sql blocks (v1.26): each sql fence becomes a synthetic
      //   val __sql_<Section> = __sqlExec__(url, "sql-with-?", List(binds…))
      // where <Section> is the nearest preceding `## Heading`; `Section.sql`
      // in later code resolves to that val (see sqlSectionIds in convertExpr).
      val dbUrl = """url:\s*"([^"]+)"""".r.findFirstMatchIn(raw).map(_.group(1)).getOrElse("")
      val blocks = collection.mutable.ListBuffer.empty[String]
      // ```scala fences are runnable in sql-conformance docs (which have
      // ```sql/```transaction fences), standard-Scala-only docs, and explicit
      // mixed-runnable docs. Elsewhere they are illustrative prose (running
      // them unconditionally cost 23 corpus files).
      // (?m)^ — fence opens must sit at LINE START: a ``` embedded inside a
      // string literal (markdown content in a val, busi model.ssc) matched
      // mid-line and desynced the whole fence walk — prose after the next real
      // close was parsed as code ("illegal unicode codepoint: 0xab" on «).
      val anyFence =
        if hasSqlFences || runnableScalaFences then
          """(?m)^```(scalascript|ssc|scala|sql|transaction|yaml|yml)([^\n]*)\n""".r
        else
          """(?m)^```(scalascript|ssc|yaml|yml)([^\n]*)\n""".r
      var sqlBlockIdx = 0
      var pos = 0
      var lastHeading = "S0"
      var done = false
      while !done do
        anyFence.findFirstMatchIn(noFront.substring(pos)) match
          case None => done = true
          case Some(m) =>
            val lang       = m.group(1)
            val attrs      = Option(m.group(2)).getOrElse("")
            val fenceStart = pos + m.start
            val codeStart  = pos + m.end
            // nearest preceding heading for the sql section id
            noFront.substring(0, fenceStart).linesIterator.foreach { ln =>
              if ln.startsWith("#") then
                // v1 sectionIdent semantics (JvmGen): split the heading on
                // non-alphanumerics and camelCase-join — "## Active users" →
                // "ActiveUsers". The old takeWhile stopped at the first space
                // ("Active"), so `ActiveUsers.sql` never resolved and leaked
                // as an effect Op (sql-h2-quickstart parity mismatch).
                val parts = ln.dropWhile(_ == '#').trim.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
                if parts.nonEmpty then
                  val raw = parts.head + parts.tail.map(p => s"${p.head.toUpper}${p.tail}").mkString
                  lastHeading = if raw.head.isDigit then "_" + raw else raw
            }
            val fenceEnd = noFront.indexOf("\n```", codeStart)
            val code     = if fenceEnd < 0 then noFront.drop(codeStart) else noFront.slice(codeStart, fenceEnd)
            lang match
              case _ if attrs.contains("no-run") =>
                // ```scala no-run — illustrative pseudo-code fence (free
                // variables, handler sketches): documented, never executed.
                ()
              case "sql" | "transaction" =>
                // @db=name attribute selects a named database from front-matter
                val dbName = """@db=([A-Za-z0-9_-]+)""".r.findFirstMatchIn(attrs).map(_.group(1))
                val urlForBlock = dbName.flatMap { n =>
                  (n + """:\s*\n\s*url:\s*"([^"]+)"""").r.findFirstMatchIn(raw).map(_.group(1))
                }.getOrElse(dbUrl)
                val bindPat = """\$\{([^}]+)\}""".r
                val binds   = bindPat.findAllMatchIn(code).map(_.group(1)).toList
                val sqlText = bindPat.replaceAllIn(code, "?").trim
                  .replace("\"", "\\\"").replace("\n", " ")
                sqlSectionIds += lastHeading
                // v1 numbering: every sql fence is also _sqlBlock_<N> (document order)
                blocks += s"""val __sql_$lastHeading = __sqlExec__("$urlForBlock", "$sqlText", List(${binds.mkString(", ")}))"""
                blocks += s"""val _sqlBlock_$sqlBlockIdx = __sql_$lastHeading"""
                sqlBlockIdx += 1
              case "yaml" | "yml" =>
                // <SectionId>.yaml — the PARSED yaml of the section's fence
                // (v1 SectionRuntime stores InstanceV(id, "yaml" -> parsed)).
                yamlSectionIds += lastHeading
                val esc = code.trim.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                blocks += s"""val __yaml_$lastHeading = __yamlSection__("$esc")"""
              case _ =>
                blocks += autoPrintLast(filterImportLines(code))
            pos = if fenceEnd < 0 then noFront.length else fenceEnd + 4
            if pos >= noFront.length then done = true
      blocks.mkString("\n")

  /** v1 auto-output: the LAST expression of a top-level fence is printed when
   *  it is not Unit (SectionRuntime prints each section's final value). Parse
   *  the fence and textually wrap its last statement in __autoPrint__(…) when
   *  that statement is an EXPRESSION (not a definition/val/import). */
  private def autoPrintLast(code: String): String =
    if System.getenv("SSC_NO_AUTOPRINT") != null then return code
    try
      given Dialect = Scala3
      // Parse the same two ways parseStats does, but keep track of the OFFSET
      // between parsed positions and `code` (the block fallback prepends "{\n").
      val (stats, off): (List[Stat], Int) = code.parse[Source] match
        case Parsed.Success(tree) => (tree.stats.toList, 0)
        case _: Parsed.Error =>
          s"{\n$code\n}".parse[Term] match
            case Parsed.Success(Term.Block(ss)) => (ss.toList, 2)
            case _                              => (Nil, 0)
      if stats.isEmpty then code
      else stats.last match
        case _: Defn | _: Decl | _: Import | _: Term.Assign => code
        case last: Term =>
          val s = last.pos.start - off; val e = last.pos.end - off
          if s >= 0 && e <= code.length && s < e
          then code.substring(0, s) + "__autoPrint__(" + code.substring(s, e) + ")" + code.substring(e)
          else code
        case _ => code
    catch case _: Throwable => code

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

  /** Whether convertStats applies OpAnf (set per-conversion by convertSource;
   *  safe default ON for standalone convertStats/convertTrees callers). */
  private var opAnfNeeded: Boolean = true

  def convertStats(stats: List[Stat], manifestMain: Option[String] = None): Program =
    registerTypes(stats)  // first pass: populate field registry

    // Pre-pass: top-level vars referenced inside any top-level def body become
    // shared @-cells (see sharedTopVars). Names-only scan is conservative — a
    // shadowing local of the same name inside a def costs only the lcell
    // optimization, never correctness.
    locally {
      val topVars = stats.collect { case v: Defn.Var => v.pats.flatMap(patNames) }.flatten.toSet
      if topVars.nonEmpty then
        val referenced = stats.collect { case d: Defn.Def =>
          d.body.collect { case Term.Name(n) if topVars(n) => n }
        }.flatten.toSet
        sharedTopVars ++= referenced
        globalVarNames ++= referenced
    }

    val defsB  = List.newBuilder[CDef]
    val entryB = List.newBuilder[Stat]

    val userDefNames = (stats.collect {
      case d: Defn.Def                        => List(d.name.value)
      case v: Defn.Val if isSimplePat(v.pats) => List(patName(v.pats.head))
      case g: Defn.Given                      => List(g.name.value)
      case eg: Defn.ExtensionGroup            => extMethods(eg).map(_.name.value)
      case obj: Defn.Object                   => List(obj.name.value)
    }).flatten.toSet

    // Forward-call metadata: quoted macro entrypoints commonly appear before
    // their implementation helper (`inline def m = ${ impl('x) }`), so call-site
    // conversion must know about the helper's trailing `using` clause before the
    // first stats pass reaches the helper definition itself.
    def rememberUsingBounds(d: Defn.Def): Unit =
      val allPcs = d.paramClauseGroups.flatMap(_.paramClauses)
      val usingParams = allPcs.flatMap(_.values).filter(_.mods.exists(_.is[Mod.Using]))
      if usingParams.nonEmpty then
        val userCount = allPcs.flatMap(_.values).count(!_.mods.exists(_.is[Mod.Using]))
        val firstTpe  = allPcs.flatMap(_.values).filter(!_.mods.exists(_.is[Mod.Using]))
          .headOption.flatMap(_.decltpe).map(_.syntax).getOrElse("")
        val ubs = usingParams.flatMap(_.decltpe).map { t =>
          val tc = t.syntax.takeWhile(c => c != '[' && c != ' ')
          val tv = t.syntax.dropWhile(_ != '[').drop(1).dropRight(1)
          val drill = if firstTpe.matches(s"List\\[\\s*$tv\\s*\\]") then "elem" else "self"
          (tc, drill)
        }.toList
        defUsingBounds(d.name.value) = (ubs, userCount)
    stats.foreach { case d: Defn.Def => rememberUsingBounds(d); case _ => () }

    stats.foreach {
      case d: Defn.Def =>
        // `stripExternDecls` removes plugin-backed extern declarations before parsing; any
        // remaining `Defn.Def` has a real body and must shadow same-named plugin globals.
        // Context bounds `[A: TC]` → prepend a `__tc_TC` dictionary param;
        // summon[TC[A]] in the body resolves to it (see the summon case), and
        // call sites synthesize the instance via __resolve_given__.
        val tparams = d.paramClauseGroups.flatMap(_.tparamClause.values)
        val bounds  = tparams.flatMap { tp =>
          tp.cbounds.map { cb =>
            val tcName = cb.syntax.takeWhile(c => c != '[' && c != ' ')
            (tcName, tp.name.value)
          }
        }
        val userParamCount = d.paramClauseGroups.flatMap(_.paramClauses).map(_.values.length).sum
        if bounds.nonEmpty then
          val firstUserParamTpe = d.paramClauseGroups
            .flatMap(_.paramClauses).flatMap(_.values).headOption
            .flatMap(_.decltpe).map(_.syntax).getOrElse("")
          defContextBounds(d.name.value) = (bounds.map { case (tc, tv) =>
            val drill = if firstUserParamTpe.matches(s"List\\[\\s*$tv\\s*\\]") then "elem" else "self"
            (tc, drill)
          }.toList, userParamCount)
        // Dict params go LAST (v1 using-clause convention: an explicit instance may
        // be passed as the trailing argument — combineAll(xs, intSum)).
        val dictParams = bounds.map { case (tc, _) => s"__tc_$tc" }.toList
        // USING clauses: register for call-site clause synthesis
        val allPcs = d.paramClauseGroups.flatMap(_.paramClauses)
        val usingParams = allPcs.flatMap(_.values).filter(_.mods.exists(_.is[Mod.Using]))
        if usingParams.nonEmpty then
          val userCount = allPcs.flatMap(_.values).count(!_.mods.exists(_.is[Mod.Using]))
          val firstTpe  = allPcs.flatMap(_.values).filter(!_.mods.exists(_.is[Mod.Using]))
            .headOption.flatMap(_.decltpe).map(_.syntax).getOrElse("")
          val ubs = usingParams.flatMap(_.decltpe).map { t =>
            val tc = t.syntax.takeWhile(c => c != '[' && c != ' ')
            val tv = t.syntax.dropWhile(_ != '[').drop(1).dropRight(1)
            val drill = if firstTpe.matches(s"List\\[\\s*$tv\\s*\\]") then "elem" else "self"
            (tc, drill)
          }.toList
          defUsingBounds(d.name.value) = (ubs, userCount)
        val params = allParams(d) ++ dictParams
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
        // Register param names for named-arg call-site synthesis — for EVERY
        // def with params, not only those with defaults: a fully-named call to
        // a default-free def (`collectResults(jobId = …, pending = …, …)` in
        // std/mapreduce) otherwise fell to the generic path, which compiled
        // each `name = value` arg as an ASSIGNMENT (Unit) — the callee then
        // received Units for all params (".isEmpty on ()").
        if allParamTerms.nonEmpty then
          defParamNames(d.name.value) = allParamTerms.map(_.name.value).toVector
        // Register vararg defs: last clause has a Type.Repeated param
        val lastClause = allClauses.lastOption.toList.flatMap(_.values)
        val hasVarargLastClause = lastClause.exists(p => p.decltpe.exists(_.isInstanceOf[Type.Repeated]))
        if hasVarargLastClause then
          varargDefs += d.name.value
          if allClauses.length > 1 then curriedVarargDefs += d.name.value
        // Register 0-arg no-parens defs: `def foo: T = body` (no param clauses at all) → auto-call.
        // Excludes `def foo(): T` (has one empty clause) which needs explicit `()`.
        if allClauses.isEmpty then
          zeroArgDefs += d.name.value
          parenlessUserDefs += d.name.value
        val lam =
          if allClauses.length <= 1 then
            if params.isEmpty then CT.Lam(0, body) else CT.Lam(params.length, body)
          else
            // Nested: outermost clause wraps inner. Scope is still full flat list.
            val clauseLens0 = allClauses.map(_.values.length)
            val clauseLens = clauseLens0.init :+ (clauseLens0.last + dictParams.length)
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
        // A "pure" rhs may still reference an ENTRY-local val (an earlier val that
        // was routed to the entry block): hoisting it to a value DEF would evaluate
        // it in pass 2, before the entry runs — unbound global / shifted locals
        // (std/parsing's PErrorNode(pInt, 0) crashed exactly this way).
        def mentionsEntryVal(t: Term): Boolean = t.collect {
          case Term.Name(n) if entryValNames.contains(n) => n
          // Section.sql resolves to the ENTRY-local __sql_<Section> val
          case Term.Select(Term.Name(sec), Term.Name("sql")) if sqlSectionIds.contains(sec) => sec
        }.nonEmpty
        // DUPLICATE top-level val names (val r = … in two fences): only the
        // FIRST occurrence may hoist to a global — a second CDef would clobber
        // the first for ALL references (lenses.ssc: prism-section r=Rect was
        // read as the later r=Roster). Duplicates go to the entry block, where
        // the local binding shadows the global from its position onward.
        val dupName = hoistedValNames.contains(name) || entryValNames.contains(name)
        if !dupName && isPureValRhs(v.rhs) && !mentionsEntryVal(v.rhs) then
          val rhs  = convertExpr(v.rhs, Nil)
          defsB += CDef(name, rhs)
          hoistedValNames += name
        else
          entryB += v
          entryValNames += name
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
      case cls: Defn.Class if cls.mods.exists(_.is[Mod.Case]) =>
        emitCaseClassMethods(cls, defsB)
      case obj: Defn.Object if !V2PluginRegistry.hasGlobal(obj.name.value) =>
        // object Foo { def m(...) = ... } → Foo = __mk_method_obj__(["m", lam, ...])
        val methods = obj.templ.stats.collect { case m: Defn.Def => m }
        // Object VAR members → global cells: CDef("@name", cell.new(init));
        // method bodies read via cell.get(Global(@name)), assignments already
        // route to cell.set(Global(@name)) in convertAssign.
        obj.templ.stats.collect { case v: Defn.Var => v }.foreach { varDef =>
          varDef.pats.collect { case Pat.Var(Term.Name(vn)) => vn }.foreach { vn =>
            globalVarNames += vn
            defsB += CDef(s"@$vn", CT.Prim("cell.new", List(convertExpr(varDef.body, Nil))))
          }
        }
        // Emit object vals as top-level CDefs so method bodies can reference them via CT.Global
        obj.templ.stats.collect { case v: Defn.Val => v }.foreach { valDef =>
          valDef.pats.collect { case Pat.Var(Term.Name(vname)) => vname }.foreach { vname =>
            if !userDefNames.contains(vname) && !V2PluginRegistry.hasGlobal(vname) then
              defsB += CDef(vname, convertExpr(valDef.body.asInstanceOf[Term], Nil))
          }
        }
        if methods.nonEmpty then
          val pairs = methods.flatMap { m =>
            val ps  = allParams(m)
            val mParams = m.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
            if mParams.exists(_.default.isDefined) then
              objMethodDefaults((obj.name.value, m.name.value)) =
                (mParams.map(_.name.value).toVector, mParams.map(_.default).toVector)
            if mParams.lastOption.exists(_.decltpe.exists(_.isInstanceOf[Type.Repeated])) then
              objMethodVarargs += ((obj.name.value, m.name.value))
            val sc  = ps.reverse
            val bod = convertExpr(m.body, sc)
            val lam = if ps.isEmpty then CT.Lam(0, bod) else CT.Lam(ps.length, bod)
            // Also emit method as a top-level CDef so intra-object calls resolve via CT.Global
            if !userDefNames.contains(m.name.value) && !V2PluginRegistry.hasGlobal(m.name.value) then
              defsB += CDef(m.name.value, lam)
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
    // A manifest `main:` is authoritative. It replaces the compatibility
    // auto-call of a literal `def main()` and is validated while the checked
    // source definitions are still available, before any target emits output.
    // With no manifest entry, retain the existing v1-compatible auto-main.
    val selectedMain: Option[String] = manifestMain match
      case Some(name) =>
        if !name.matches("[A-Za-z_][A-Za-z0-9_]*") then
          throw new IllegalArgumentException(s"checked source: invalid manifest main entry '$name'")
        val body = defs.find(_.name == name).map(_.body).getOrElse(
          throw new IllegalArgumentException(s"checked source: manifest main entry '$name' is not defined"))
        body match
          case CT.Lam(0, _) => Some(name)
          case _ =>
            throw new IllegalArgumentException(
              s"checked source: manifest main entry '$name' must be a zero-argument function")
      case None => Option.when(userDefNames.contains("main"))("main")
    // Call the selected entry AFTER all top-level/module initialization.
    val mainCall: List[Stat] = selectedMain.toList.map(name =>
      Term.Apply.After_4_6_0(Term.Name(name), Term.ArgClause(Nil)))
    val entryStmts = entryB.result() ++ mainCall
    def optStr(value: Option[String]): CT =
      value match
        case Some(s) => CT.Ctor("Some", List(CT.Lit(Const.CStr(s))))
        case None    => CT.Ctor("None", Nil)
    def remoteRegistration(h: RemoteHandlerSpec): CT =
      CT.Prim("remote.registerHandler", List(
        CT.Lit(Const.CStr(h.name)),
        CT.Lit(Const.CStr(h.function)),
        CT.Global(h.function),
        optStr(h.path),
        optStr(h.requestType),
        optStr(h.responseType)
      ))
    val baseEntry =
      if entryStmts.nonEmpty then convertBlock(entryStmts, Nil, topLevel = true)
      else CT.Lit(Const.CUnit)
    val remoteRegs = pendingRemoteHandlers.toList.map(remoteRegistration)
    val entry =
      if remoteRegs.nonEmpty then CT.Seq(remoteRegs :+ baseEntry)
      else baseEntry
    // Op-argument lifting (bridged lane only — see OpAnf): arguments that may
    // evaluate to an unresolved effect Op are Let-bound so the kernel's
    // Let-threading defers the consumer into the Op's continuation. Gated:
    // convertSource turns it off for effect-free sources; standalone
    // convertStats/convertTrees callers keep the safe default (on).
    val prog = Program(defs, entry)
    if opAnfNeeded then OpAnf.lift(prog) else prog

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
    // Match-exhaustion sentinel: caseChain/guard fallthrough compiles to
    // __match_fail__() — without this def the global was UNBOUND and a failed
    // match crashed with an opaque unknown-global error instead of this one.
    CDef("__match_fail__", CT.Lam(0, CT.Prim("__match_fail_prim__", Nil))),
    CDef("nanoTime", CT.Lam(0, CT.Prim("io.nanoTime", Nil))),
    // Bench harness stub — Bench.opaque(x) = identity (prevents constant folding)
    CDef("Bench", CT.Ctor("BenchObj", Nil)),
    // LazyList object (accessed as LazyList.from — isCtorName("LazyList")==true → Ctor handled in __method__)
    CDef("LazyList", CT.Ctor("LazyList", Nil)),
  )

  private val fFormatPrefixRe =
    """%(?:[-#+ 0,(<]*)(?:\d+)?(?:\.\d+)?[a-zA-Z]""".r

  private def splitFFormatPrefix(part: String): (String, String) =
    fFormatPrefixRe.findPrefixMatchOf(part) match
      case Some(m) => (m.matched, part.drop(m.end))
      case None    => ("%s", part)

  // ── Block lowering ────────────────────────────────────────────────────────────

  def convertBlock(stats: List[Stat], scope: List[String], topLevel: Boolean = false): CT =
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
        val restCT = convertBlock(rest, scopeName :: scope, topLevel)
        if topLevel then
          // TOP-LEVEL entry vals are v1 GLOBALS: register at runtime so pass-2
          // defs invoked later resolve them (parser grammars: def rules
          // referencing entry-val combinators — jNull, ws, parsePass …).
          CT.Let(List(rhs),
            CT.Seq(List(CT.Prim("global.reg", List(CT.Lit(Const.CStr(name)), CT.Local(0))), restCT)))
        else CT.Let(List(rhs), restCT)

      // val (a, b) = rhs; rest (tuple destructuring)
      case (v: Defn.Val) :: rest =>
        val rhs   = convertExpr(v.rhs, scope)
        val indexedNames: List[(String, Int)] =
          v.pats match
            case List(Pat.Tuple(pats)) =>
              pats.toList.zipWithIndex.flatMap {
                case (Pat.Var(Term.Name(n)), i) => Some((n, i))
                case (Pat.Typed(Pat.Var(Term.Name(n)), _), i) => Some((n, i))
                case _ => None
              }
            case _ =>
              v.pats.flatMap(patNames).zipWithIndex
        if indexedNames.isEmpty then
          CT.Let(List(rhs), convertBlock(rest, "_blk_" :: scope, topLevel))
        else
          // Bind tuple to "_tup_", then extract each field
          val tupName  = "_tup_"
          val withTup  = tupName :: scope
          def chain(remaining: List[(String, Int)], sc: List[String]): CT =
            remaining match
              case Nil              => convertBlock(rest, sc, topLevel)
              case (nm, i) :: tail =>
                CT.Let(
                  List(CT.Prim("fieldAt", List(lookupVar(tupName, sc), CT.Lit(Const.CInt(i))))),
                  chain(tail, nm :: sc)
                )
          CT.Let(List(rhs), chain(indexedNames, withTup))

      // var name = rhs; rest (mutable cell)
      case (v: Defn.Var) :: rest =>
        val names = v.pats.flatMap(patNames)
        val rhs   = varRhs(v)
        val rhsIr = convertExpr(rhs, scope)
        // A top-level var referenced from def bodies must be ONE shared boxed
        // cell, published as Global("@name") (see sharedTopVars): defs read it
        // via cell.get(Global @name) and assign via cell.set(Global @name),
        // while the entry block keeps using the scope local — same object.
        val shared = topLevel && names.headOption.exists(sharedTopVars.contains)
        val (cellOp, prefix) =
          if !shared && isIntLit(rhs) then ("lcell.new", "@@")
          else if !shared && isFloatLit(rhs) then ("dcell.new", "@#")
          else                             ("cell.new",  "@")
        val cellName = names.headOption.map(n => s"$prefix$n").getOrElse("@_")
        val body = convertBlock(rest, cellName :: scope, topLevel)
        val bodyWithReg =
          if shared then CT.Seq(List(
            CT.Prim("global.reg", List(CT.Lit(Const.CStr(cellName)), CT.Local(0))), body))
          else body
        CT.Let(List(CT.Prim(cellOp, List(rhsIr))), bodyWithReg)

      // def name(params) = body; rest (local LetRec for self-recursion)
      case (d: Defn.Def) :: rest =>
        val name      = d.name.value
        val params    = allParams(d)
        val letrecSc  = name :: scope
        val defSc     = params.reverse ++ letrecSc
        val body      = convertExpr(d.body, defSc)
        val lam       = CT.Lam(params.length, body)
        CT.LetRec(List(lam), convertBlock(rest, letrecSc, topLevel))

      // while (cond) body; rest
      case (w: Term.While) :: rest =>
        val loop = CT.While(convertExpr(w.expr, scope), convertExpr(w.body, scope))
        rest match
          case Nil => loop
          case _   => CT.Let(List(loop), convertBlock(rest, "_blk_" :: scope, topLevel))

      // x = rhs (assignment to mutable var)
      case (a: Term.Assign) :: rest =>
        val setIr = convertAssign(a, scope)
        rest match
          case Nil => setIr
          case _   => CT.Seq(List(setIr, convertBlock(rest, scope, topLevel)))

      // expression statement (not last)
      case (t: Term) :: (rest @ (_ :: _)) =>
        CT.Seq(List(convertExpr(t, scope), convertBlock(rest, scope, topLevel)))

      // last expression — the block's value
      case (t: Term) :: Nil =>
        convertExpr(t, scope)

      // non-Term at end (shouldn't happen in well-formed code)
      case _ :: rest => convertBlock(rest, scope, topLevel)

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
    case Term.Name("???") => CT.App(CT.Global("???"), Nil)  // Predef.??? throws on reference
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
              case Some(i) => CT.Prim("fieldAt", List(recv, CT.Lit(Const.CInt(i)), CT.Lit(Const.CStr(mname))))
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
              case Term.Name(n) if !innerScope.contains(n) && varargDefs.contains(n) && !curriedVarargDefs.contains(n) =>
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
    // Compound assignment: x += e → x = (x op e) via the var-cell machinery
    case Term.ApplyInfix.After_4_6_0(lhs @ Term.Name(_), op, _, argClause)
        if Set("+=", "-=", "*=", "/=")(op.value) && argClause.values.length == 1 =>
      val baseOp = op.value.stripSuffix("=")
      val combined = Term.ApplyInfix(lhs, Term.Name(baseOp), Type.ArgClause(Nil),
        Term.ArgClause(argClause.values, None))
      convertAssign(Term.Assign(lhs, combined), scope)
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
    case Term.ApplyType.After_4_6_0(Term.Name("Prism"), argClause) =>
      // BARE Prism[Outer, Variant] value (no call args) — variant prism
      val variant = argClause match
        case ac: Type.ArgClause if ac.values.length >= 2 => ac.values(1).syntax.takeWhile(_ != '[')
        case ac: Type.ArgClause if ac.values.nonEmpty    => ac.values.head.syntax.takeWhile(_ != '[')
        case _ => "?"
      CT.Prim("optics.prism", List(CT.Lit(Const.CStr(variant))))
    case Term.ApplyType.After_4_6_0(Term.Name("summon"), argClause) =>
      val typeSig = argClause match
        case ac: Type.ArgClause => ac.values.headOption.fold("?")(_.syntax)
        case _ => "?"
      val tcHead0 = typeSig.takeWhile(c => c != '[' && c != ' ')
      val dictIdx0 = scope.indexOf(s"__tc_$tcHead0")
      if dictIdx0 >= 0 then CT.Local(dictIdx0)
      else givenRegistry.get(typeSig) match
        case Some(name) => CT.Global(name)
        case None =>
          // summon[Mirror.Of[X]] — if X is a known case class, build an inline Mirror ctor.
          val mirrorTypePat = """^Mirror\.Of\[(.+)\]$""".r
          typeSig match
            case mirrorTypePat(className) if fieldRegistry.contains(className) =>
              val fields    = fieldRegistry(className)
              val types     = fieldTypeRegistry.getOrElse(className, Vector.fill(fields.length)("Any"))
              val labelLit  = CT.Lit(Const.CStr(className))
              val elemLbls  = listOf(fields.map(f => CT.Lit(Const.CStr(f))).toList)
              val elemTypes = listOf(types.map(t => CT.Lit(Const.CStr(t))).toList)  // real field types (String, Int…), not "Any"
              CT.Ctor("Mirror", List(labelLit, elemLbls, elemTypes))
            case _ =>
              CT.App(CT.Global("__unsupported__"), List(CT.Lit(Const.CStr(s"summon[$typeSig]"))))
    case Term.ApplyType.After_4_6_0(fn, _) =>
      convertExpr(fn, scope)

    // ── Function application ──────────────────────────────────────────────────
    case Term.Apply.After_4_6_0(fn, argClause) =>
      convertApply(fn, argClause.values.toList, scope)

    // ── Member select (field/method as value, no args) ────────────────────────
    // scala.collection.mutable sugar: mutable.Map.empty / mutable.Map() → kernel map
    case Term.Select(Term.Select(Term.Name("mutable"), Term.Name("Map")), Term.Name("empty")) =>
      CT.Prim("map.new", Nil)
    // Section.sql → the synthetic __sql_<Section> result val (sql fenced blocks)
    case Term.Select(Term.Name(sec), Term.Name("sql")) if sqlSectionIds.contains(sec) =>
      lookupVarFull(s"__sql_$sec", scope)
    case Term.Select(Term.Name(sec), Term.Name("yaml")) if yamlSectionIds.contains(sec) =>
      lookupVarFull(s"__yaml_$sec", scope)
    // EnumName.values (bare, no parens) → list of the enum's zero-arg cases
    case Term.Select(Term.Name(en), Term.Name("values")) if enumCaseNames.contains(en) =>
      enumCaseNames(en).foldRight(CT.Ctor("Nil", Nil): CT)((c, acc) =>
        CT.Ctor("Cons", List(CT.Ctor(c, Nil), acc)))
    case Term.Select(qual, Term.Name(name)) =>
      // Namespace.CtorName → CT.Ctor (e.g. Transport.Stdio, Either.Left, Option.None)
      // Also handles zero-arg enum cases (fieldRegistry has empty vector for them).
      // Guard: only treat as Ctor when the qualifier is itself a type name (uppercase-first),
      // e.g. Option.None, Either.Left — NOT when qualifier is a value (math.Pi, list.Head).
      val isZeroArgEnumCase = fieldRegistry.get(name).exists(_.isEmpty)
      val qualIsTypeName = qual match
        case Term.Name(qn) => isCtorName(qn)
        case _             => false
      val qualIsMethodObject = qual match
        case Term.Name(qn) => methodObjectNames.contains(qn)
        case _             => false
      val qualCompanionStatic = qual match
        case Term.Name(qn) => companionStaticConstructors.contains(qn)
        case _             => false
      // cur-2: `Currency.USD`/`.EUR`/… — a companion STATIC on a functionConstructor
      // whose members are currency codes. The runtime global `Currency` methodObject
      // resolves the code via `apply` (currencyV, with scale/symbol defaults); route
      // `Currency.CODE` to that apply so it doesn't fall through to a zero-arg
      // `CT.Ctor("USD", Nil)` (v2-money Currency companion compatibility).
      if qualCompanionStatic && isCtorName(name) then
        qual match
          case Term.Name(qn) => CT.App(CT.Global(qn), List(CT.Lit(Const.CStr(name))))
          case _             => CT.Ctor(name, Nil)
      else if qualIsTypeName && !qualIsMethodObject && isCtorName(name) && (!fieldRegistry.contains(name) || isZeroArgEnumCase) then CT.Ctor(name, Nil)
      else
        val q = convertExpr(qual, scope)
        if isEffectReceiver(qual) then
          methodCallFor(qual, name, q, Nil)
        else if extensionMethods.contains(name) then
          CT.App(CT.Global(name), List(q))
        else if caseClassMethodNames.contains(name) then
          CT.Prim("__methodOrExt__", List(CT.Lit(Const.CStr(name)), q, CT.Global(name)))
        else
          fieldIndex(name) match
            case Some(i) => CT.Prim("fieldAt", List(q, CT.Lit(Const.CInt(i)), CT.Lit(Const.CStr(name))))
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

    // ── Try/catch: __try__(bodyThunk, handlerLam) — the runtime catches
    // BridgeThrow (carries the thrown v2 VALUE) and calls the handler with it.
    case Term.Try(expr, catchCases, _) if catchCases.nonEmpty =>
      // The body thunk is a ZERO-arity Lam and the runtime calls it with NO
      // args (callClosure(thunk, Nil)) — pushing a phantom "_unit_" onto the
      // conversion scope shifted every OUTER-local reference inside the try
      // body one slot too high (off-by-one → env out-of-bounds at runtime;
      // std/agent postChatCompletionsOnce crashed on any try inside a def
      // that referenced the def's params). Top-level tries never noticed —
      // their references are globals.
      val bodyThunk = CT.Lam(0, convertExpr(expr, scope))
      val handler   = CT.Lam(1, convertMatch(CT.Local(0), catchCases, "_exc_" :: scope))
      CT.Prim("__try__", List(bodyThunk, handler))
    case Term.Try(expr, _, _) => convertExpr(expr, scope)

    // ── Partial function {case p => e} as lambda+match ────────────────────────
    case Term.PartialFunction(cases) =>
      CT.Lam(1, convertMatch(
        CT.Local(0), cases, "_pfn_" :: scope, handlerDispatch = true))

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

    // ── String interpolation s"... $x ..." (and supported variants) ─────────────
    // Unknown interpolators (html"...", sql"...", etc.) are treated as s"..." (string concat).
    case Term.Interpolate(interpName, parts, args) =>
      val partStrings = parts.map {
        case Lit.String(s) => s
        case _             => ""
      }
      if interpName.value == "xml" then
        val strs = partStrings.map(s => CT.Lit(Const.CStr(s)))
        val vals = args.map(e => CT.App(CT.Global("__xmlPart"), List(convertExpr(e, scope))))
        CT.App(CT.Global("xml"), List(interleaveConcat(strs, vals)))
      else if interpName.value == "f" then
        val encoded = collection.mutable.ListBuffer.empty[CT]
        encoded += CT.Lit(Const.CStr(partStrings.headOption.getOrElse("")))
        args.zipWithIndex.foreach { case (arg, i) =>
          val (spec, rest) = splitFFormatPrefix(partStrings.lift(i + 1).getOrElse(""))
          encoded += CT.Lit(Const.CStr(spec))
          encoded += convertExpr(arg, scope)
          encoded += CT.Lit(Const.CStr(rest))
        }
        CT.Prim("__fInterpolate__", encoded.toList)
      else
        val strs = partStrings.map(s => CT.Lit(Const.CStr(s)))
        val vals = args.map { e =>
          CT.Prim("__method__", List(CT.Lit(Const.CStr("toString")), convertExpr(e, scope)))
        }
        val concat = interleaveConcat(strs, vals)
        // md"..." strips the leading/trailing blank lines and the common indent
        // (v1 Interpreter.stripIndent) — treating it as plain s"..." left the raw
        // indented block in the output (content.ssc parity).
        if interpName.value == "md" then CT.Prim("__mdStrip__", List(concat)) else concat

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
      val dcell = scope.indexOf(s"@#$name")
      val cell  = scope.indexOf(s"@$name")
      val arr   = scope.indexOf(s"##$name")
      if lcell >= 0 then CT.Prim("lcell.get", List(CT.Local(lcell)))
      else if dcell >= 0 then CT.Prim("dcell.get", List(CT.Local(dcell)))
      else if cell >= 0 then CT.Prim("cell.get", List(CT.Local(cell)))
      else if arr >= 0 then CT.Local(arr)  // array val — return raw local
      else if globalVarNames.contains(name) then
        CT.Prim("cell.get", List(CT.Global(s"@$name")))
      else if isCtorName(name) && !methodObjectNames.contains(name) then
        CT.Ctor(name, Nil)  // e.g. Nil, None, True, case objects
      else if parenlessUserDefs.contains(name) then
        // paren-less def `def foo: T = body` compiles to Lam(0, body): a bare
        // reference EVALUATES it (Scala getter semantics), so emit a 0-arg
        // call. `foo(x)` then becomes App(App(Global(foo),Nil), x) — evaluate
        // then apply. Without this, `foo` passed by name is the 0-arity closure
        // itself and a later apply hits "arity: 0 expected, N given".
        CT.App(CT.Global(name), Nil)
      else CT.Global(name)

  private def isEffectReceiver(qual: Term): Boolean = qual match
    case Term.Name(n) => oneShotEffectNames.contains(n) || multiEffectNames.contains(n)
    case _            => false

  /** Build dynamic dispatch while retaining typed-effect multiplicity.
   *
   *  `__effect_oneshot__` is intentionally bridge-private: Runtime first uses
   *  ordinary method/effect dispatch (preserving active contexts and plugin
   *  fallbacks), then wraps the matching Op continuation with PortableEffects'
   *  shared one-shot gate. The explicit strings are the structured OperationId;
   *  no runtime code needs to split the legacy display label. */
  private def methodCallFor(qual: Term, method: String, receiver: CT, args: List[CT]): CT =
    qual match
      case Term.Name(effect) if oneShotEffectNames.contains(effect) =>
        CT.Prim("__effect_oneshot__",
          CT.Lit(Const.CStr(effect)) :: CT.Lit(Const.CStr(method)) :: receiver :: args)
      case Term.Name(effect) if multiEffectNames.contains(effect) =>
        CT.Prim("__effect__", CT.Lit(Const.CStr(method)) :: receiver :: args)
      case _ =>
        CT.Prim("__method__", CT.Lit(Const.CStr(method)) :: receiver :: args)

  private def convertAssign(a: Term.Assign, scope: List[String]): CT =
    a.lhs match
      case Term.Name(name) =>
        val lcell = scope.indexOf(s"@@$name")
        val dcell = scope.indexOf(s"@#$name")
        val cell  = scope.indexOf(s"@$name")
        val rhs   = convertExpr(a.rhs, scope)
        if lcell >= 0 then CT.Prim("lcell.set", List(CT.Local(lcell), rhs))
        else if dcell >= 0 then CT.Prim("dcell.set", List(CT.Local(dcell), rhs))
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
      // A named ARGUMENT `kw = v` (Term.Assign in call position) is a keyword
      // arg, not an assignment statement. The only cell-backed keyword is
      // `timeout` (receive(timeout=n) → @timeout cell, read by registerActors);
      // every OTHER named arg was compiled to cell.set(@kw, v) → UnitV into a
      // global nothing reads, silently DROPPING the value (mcp tool hints,
      // etc.). Pass those positionally by value instead. (receive/@cell keeps
      // its behavior: `timeout` and any @kw/@@kw already in scope.)
      val eForConv = e match
        case Term.Assign(Term.Name(kw), rhs)
            if kw != "timeout" && !scope.contains(s"@$kw") && !scope.contains(s"@@$kw") =>
          rhs
        case _ => e
      val converted = convertExpr(wrapIfPH(eForConv), scope)
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
        val positional = rawArgs.collect {
          case e if !e.isInstanceOf[Term.Assign] => convertExpr(wrapIfPH(e), scope)
        }
        fieldRegistry.get(name) match
          case Some(fields) =>
            val ctorDefs = defaultParams.get(name)
            CT.Ctor(name, fields.zipWithIndex.map { case (fn, i) =>
              overrides
                .get(fn)
                .orElse(positional.lift(i))
                .getOrElse(ctorDefs.flatMap(defs => defs.lift(i).flatten).getOrElse(CT.Lit(Const.CUnit)))
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
        // A user/imported case class of this name wins over a plugin
        // functionConstructor companion (std/money.ssc Money/Currency vs the
        // payments-bridge factories — v2-money-decimal-regression).
        // cur-1: a user/imported case class wins over a plugin functionConstructor
        // companion of the same name (v2-money-decimal-regression) — but only when
        // the call ARITY matches the case class. A companion shorthand of a
        // DIFFERENT arity coexists: std/money `Currency(code, scale, symbol)` 3-arg
        // → Ctor, payments `Currency(code)` 1-arg → companion (fills scale/symbol
        // defaults the case class lacks). `Money(amount, cur)` 2-arg still matches
        // its case class → Ctor, so the decimal-regression fix is preserved.
        val caseArity      = fieldRegistry.get(name).map(_.length)
        val argMatchesCase = caseArity.contains(args.length)
        if functionConstructors.contains(name) && (!userCaseClasses.contains(name) || !argMatchesCase) then
          CT.App(CT.Global(name), args)
        else buildWithDefaults(name, args, refs => CT.Ctor(name, refs))
               .getOrElse(CT.Ctor(name, fillDefaults(name, args)))
      // .copy(field = val, ...) — case class copy with named field overrides.
      // Intercept before the generic __method__ path so we can use the field registry
      // to find indices and emit Ctor with field-at fallbacks, avoiding @field globals.
      case Term.Select(qual, Term.Name("copy")) if rawArgs.nonEmpty =>
        // .copy with named, positional, or MIXED args. Named-only + unambiguous
        // class → convert-time rebuild; anything else defers to RUNTIME copy with
        // ("#i" | name, value) pair encoding — the receiver's actual tag is known there.
        val named: Map[String, CT] = rawArgs.collect {
          case Term.Assign(Term.Name(n), rhs) => n -> convertExpr(rhs, scope)
        }.toMap
        val positional: List[(Int, CT)] = rawArgs.zipWithIndex.collect {
          case (t, i) if !t.isInstanceOf[Term.Assign] => i -> convertExpr(t, scope)
        }
        val candidates = fieldRegistry.filter { case (_, fields) =>
          named.keys.forall(k => fields.contains(k))
        }
        (positional, candidates.toList) match
          case (Nil, List((tag, fields))) =>
            val q = convertExpr(qual, scope)
            CT.Let(List(q), CT.Ctor(tag, fields.zipWithIndex.map { case (fn, i) =>
              named.getOrElse(fn, CT.Prim("fieldAt", List(CT.Local(0), CT.Lit(Const.CInt(i)))))
            }.toList))
          case _ =>
            val q = convertExpr(qual, scope)
            val pairs =
              positional.flatMap { case (i, v) => List(CT.Lit(Const.CStr(s"#$i")), v) } ++
              named.toList.flatMap { case (n, v) => List(CT.Lit(Const.CStr(n)), v) }
            CT.Prim("__method__", CT.Lit(Const.CStr("copy")) :: q :: pairs)
      // Object VARARG method: wrap the args in a list (Prompt.messages(m1, m2))
      case Term.Select(Term.Name(objn), Term.Name(mname))
          if objMethodVarargs.contains((objn, mname)) =>
        val q = convertExpr(Term.Name(objn), scope)
        CT.Prim("__method__", List(CT.Lit(Const.CStr(mname)), q, listOf(args)))
      // Object method with DEFAULT params called with fewer args: wrap like
      // buildWithDefaults — missing defaults evaluate in scope of earlier params.
      case Term.Select(Term.Name(objn), Term.Name(mname))
          if objMethodDefaults.contains((objn, mname))
             && args.length < objMethodDefaults((objn, mname))._2.length =>
        val (pnames, defs) = objMethodDefaults((objn, mname))
        val k     = args.length
        val total = defs.length
        var dscope = pnames.take(k).reverse.toList
        val lets  = collection.mutable.ListBuffer.empty[CT]
        var ok    = true
        defs.drop(k).zipWithIndex.foreach { case (dOpt, j) =>
          dOpt match
            case Some(d) => lets += convertExpr(d, dscope); dscope = pnames(k + j) :: dscope
            case None    => ok = false
        }
        if !ok then
          val q = convertExpr(Term.Name(objn), scope)
          CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: q :: args)
        else
          val refs = (0 until total).toList.map(i => CT.Local(total - 1 - i))
          // receiver converted OUTSIDE the wrapper (scope unchanged), then shifted:
          // simplest safe form — resolve the object as a Global/Ctor with no locals.
          val q = convertExpr(Term.Name(objn), Nil)
          val core = CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: q :: refs)
          val body = if lets.isEmpty then core else CT.Let(lets.toList, core)
          CT.App(CT.Lam(k, body), args)
      // EnumName.values → list of the enum's zero-arg cases (convert time)
      case Term.Select(Term.Name(en), Term.Name("values"))
          if enumCaseNames.contains(en) && args.isEmpty =>
        enumCaseNames(en).foldRight(CT.Ctor("Nil", Nil): CT)((c, acc) =>
          CT.Ctor("Cons", List(CT.Ctor(c, Nil), acc)))
      // Method call: qual.method(args)
      case Term.Select(qual, Term.Name(mname)) =>
        if isCtorName(mname) then CT.Ctor(mname, fillDefaults(mname, args))
        else
          val q = convertExpr(qual, scope)
          // Extension method → global call with receiver as first arg
          if isEffectReceiver(qual) then
            methodCallFor(qual, mname, q, args)
          else if (extensionMethods.contains(mname) || caseClassMethodNames.contains(mname)) &&
             !suppressExtName.contains(mname) then
            // Runtime-branching: a method-object receiver with its OWN `mname`
            // field wins over the extension global (a Prism's modify was hijacked
            // by the optic-path extension modify and returned a stale walk).
            CT.Prim("__methodOrExt__", CT.Lit(Const.CStr(mname)) :: q :: (args :+ CT.Global(mname)))
          // If it's a known field accessor with no args, use fieldAt
          else if args.isEmpty then
            fieldIndex(mname) match
              case Some(i) => CT.Prim("fieldAt", List(q, CT.Lit(Const.CInt(i)), CT.Lit(Const.CStr(mname))))
              case None    => methodCallFor(qual, mname, q, args)
          else
            methodCallFor(qual, mname, q, args)
      // Curried method application: qual.method(a)(b) — merge into one __method__ call
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name(mname)), innerClause) if !isCtorName(mname) =>
        val q     = convertExpr(qual, scope)
        // named args in the FIRST clause of a KNOWN curried native (mcp tool
        // hints) are keyword values, not @cell assignments — pass positionally.
        // Scoped to knownCurriedNatives so ordinary curried calls (handleError,
        // etc.) keep their exact arg conversion.
        val stripKw = knownCurriedNatives.contains(mname)
        val inner = innerClause.values.toList.map { e =>
          val eConv = e match
            case Term.Assign(Term.Name(kw), rhs)
                if stripKw && kw != "timeout" && !scope.contains(s"@$kw") && !scope.contains(s"@@$kw") => rhs
            case _ => e
          convertExpr(eConv, scope)
        }
        if isEffectReceiver(qual) then
          methodCallFor(qual, mname, q, inner ++ args)
        else if curriedExternMethods.contains(mname) || knownCurriedNatives.contains(mname) then
          // extern `def m(a…)(b…)` OR a known curried plugin-native: the native
          // returns the SECOND-step fn from the first application — keep the
          // two-step (merging fed all args at once and the native raised its
          // usage error: srv.tool of std/mcp).
          CT.App(CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: q :: inner), args)
        else
          CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: q :: (inner ++ args))
      // Prism[Outer, Variant] — variant prism (v1 OpticsRuntime.buildPrism mirror)
      case Term.ApplyType.After_4_6_0(Term.Name("Prism"), argClause) =>
        val variant = argClause match
          case ac: Type.ArgClause if ac.values.length >= 2 => ac.values(1).syntax.takeWhile(_ != '[')
          case ac: Type.ArgClause if ac.values.nonEmpty    => ac.values.head.syntax.takeWhile(_ != '[')
          case _ => "?"
        CT.Prim("optics.prism", List(CT.Lit(Const.CStr(variant))))
      // summon[T] — resolve given by type string
      case Term.ApplyType.After_4_6_0(Term.Name("summon"), argClause) =>
        val typeSig = argClause match
          case ac: Type.ArgClause => ac.values.headOption.fold("?")(_.syntax)
          case _ => "?"
        val tcHead = typeSig.takeWhile(c => c != '[' && c != ' ')
        val dictIdx = scope.indexOf(s"__tc_$tcHead")
        if dictIdx >= 0 then CT.Local(dictIdx)
        else givenRegistry.get(typeSig) match
          case Some(name) => CT.Global(name)
          case None       => CT.App(CT.Global("__unsupported__"), List(CT.Lit(Const.CStr(s"summon[$typeSig]"))))
      // Focus[T](_.path.some.index(i).at(k)…) — optics path lens (v1 OpticsRuntime
      // mirror): extract the step list from the lambda AST at convert time and
      // build the optic via the runtime helper (PluginBridge "optics.focus").
      case Term.ApplyType.After_4_6_0(Term.Name("Focus"), _) if rawArgs.length == 1 =>
        def stepsOf(t: Term, baseName: String => Boolean): Option[List[CT]] = t match
          case Term.Name(n) if baseName(n) => Some(Nil)
          case _: Term.Placeholder => Some(Nil)
          case Term.Select(inner, Term.Name("some")) =>
            stepsOf(inner, baseName).map(_ :+ CT.Ctor("OSome", Nil))
          case Term.Select(inner, Term.Name("each")) =>
            stepsOf(inner, baseName).map(_ :+ CT.Ctor("OEach", Nil))
          case Term.Select(inner, Term.Name(f)) =>
            stepsOf(inner, baseName).map(_ :+ CT.Ctor("OField", List(CT.Lit(Const.CStr(f)))))
          case Term.Apply.After_4_6_0(Term.Select(inner, Term.Name("index")), ac) if ac.values.length == 1 =>
            stepsOf(inner, baseName).map(_ :+ CT.Ctor("OIndex", List(convertExpr(ac.values.head, scope))))
          case Term.Apply.After_4_6_0(Term.Select(inner, Term.Name("at")), ac) if ac.values.length == 1 =>
            stepsOf(inner, baseName).map(_ :+ CT.Ctor("OAt", List(convertExpr(ac.values.head, scope))))
          case _ => None
        val stepsOpt = rawArgs.head match
          case Term.Function.After_4_6_0(ps, body) =>
            val pn = ps.values.headOption.map(_.name.value).getOrElse("_")
            stepsOf(body, _ == pn)
          case Term.AnonymousFunction(body) => stepsOf(body, _ => false)
          case _ => None
        stepsOpt match
          case Some(steps) =>
            val lst = steps.foldRight(CT.Ctor("Nil", Nil): CT)((st, acc) => CT.Ctor("Cons", List(st, acc)))
            CT.Prim("optics.focus", List(lst, CT.Lit(Const.CStr(rawArgs.head.syntax))))
          case None =>
            CT.App(CT.Global("__unsupported__"), List(CT.Lit(Const.CStr("Focus[...](non-path lambda)"))))
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
        val innerApp = inner match
          // f(a)(using x) / f(a)(x) where f has a using clause and THIS apply
          // supplies it explicitly — build the inner app WITHOUT synthesis
          case Term.Name(iname) if !scope.contains(iname) && defUsingBounds.contains(iname)
              && defUsingBounds(iname)._2 == innerClause.values.length =>
            CT.App(CT.Global(iname), innerClause.values.toList.map(a => convertExpr(a, scope)))
          case _ => convertApply(inner, innerClause.values.toList, scope)
        val isVararg = inner match
          case Term.Name(n) if !scope.contains(n) => varargDefs.contains(n)
          case _ => false
        if isVararg then CT.App(innerApp, List(listOf(args)))
        else CT.App(innerApp, args)
      // Synthetic sql-fence executor call → prim (registered in PluginBridge)
      case Term.Name("__sqlExec__") =>
        CT.Prim("__sqlExec__", args)
      case Term.Name("__autoPrint__") =>
        CT.Prim("__autoPrint__", args)
      case Term.Name("__yamlSection__") =>
        CT.Prim("__yamlSection__", args)
      // Regular function call (with optional default-param synthesis for known defs)
      case Term.Name(name) if !scope.contains(name) && defaultParams.contains(name) =>
        val fullArgs = args ++ dictArgsFor(name, args)
        buildWithDefaults(name, fullArgs, refs => CT.App(CT.Global(name), refs))
          .getOrElse(CT.App(CT.Global(name), fillDefaults(name, fullArgs)))
      // Context-bound def WITHOUT defaults: synthesize the dictionary args only
      case Term.Name(name) if !scope.contains(name) && defContextBounds.contains(name) =>
        CT.App(CT.Global(name), args ++ dictArgsFor(name, args))
      // USING-clause def called with only the user clause → synthesize the using clause
      case Term.Name(name) if !scope.contains(name) && defUsingBounds.contains(name)
          && defUsingBounds(name)._2 == args.length =>
        val (ubs, _) = defUsingBounds(name)
        val dicts = ubs.map { case (tc, drill) =>
          val table = usingTableFor(tc)
          val witness = args.headOption.getOrElse(CT.Lit(Const.CUnit))
          CT.Prim("__resolve_given__",
            CT.Lit(Const.CStr(tc)) :: CT.Lit(Const.CStr(drill)) :: witness :: table)
        }
        CT.App(CT.App(CT.Global(name), args), dicts)
      // Direct single-clause vararg call: f(a, b, c) where f has vararg — always wrap in list
      // (single-arg calls f(x) must also wrap: body expects a List, e.g. body.toList)
      case Term.Name(name) if !scope.contains(name) && varargDefs.contains(name) && !curriedVarargDefs.contains(name) =>
        CT.App(CT.Global(name), List(listOf(args)))
      case other => CT.App(convertExpr(other, scope), args)

  /** Desugar `direct[M] { stmts }` bind-forms into flatMap chains.
   *  `x = expr` → expr.flatMap { x => rest }
   *  `val x = expr` → let x = expr in rest (pure)
   *  bare expr → expr.flatMap { _ => rest } */
  private def desugarDirect(stmts: List[scala.meta.Stat], scope: List[String],
                            vars: Set[String] = Set.empty): CT =
    import scala.meta.*
    stmts match
      case Nil => CT.Lit(Const.CUnit)
      case (last: Term) :: Nil => convertExpr(last, scope)
      // `var x = e` in a direct block: plain let; later `x = …` REASSIGNS via a
      // shadowing let (straight-line code) instead of becoming a monadic bind.
      case (dv: Defn.Var) :: rest =>
        val x = dv.pats match { case List(Pat.Var(n)) => n.value; case _ => "_" }
        val rhsT = dv.body
        CT.Let(List(convertExpr(rhsT, scope)), desugarDirect(rest, x :: scope, vars + x))
      case Term.Assign(Term.Name(x), rhs) :: rest if vars(x) =>
        CT.Let(List(convertExpr(rhs, scope)), desugarDirect(rest, x :: scope, vars))
      case Term.Assign(Term.Name(x), rhs) :: rest =>
        val bindedScope = x :: scope
        val body = desugarDirect(rest, bindedScope, vars)
        CT.Prim("__method__", List(
          CT.Lit(Const.CStr("flatMap")),
          convertExpr(rhs, scope),
          CT.Lam(1, body)))
      case Defn.Val(_, List(Pat.Var(n)), _, rhs) :: rest =>
        val x = n.value
        val letScope = x :: scope
        CT.Let(List(convertExpr(rhs, scope)), desugarDirect(rest, letScope, vars))
      case (t: Term) :: rest =>
        CT.Prim("__method__", List(
          CT.Lit(Const.CStr("flatMap")),
          convertExpr(t, scope),
          CT.Lam(1, desugarDirect(rest, "_" :: scope, vars))))
      case _ :: rest => desugarDirect(rest, scope, vars)

  /** Operators the kernel's arithOp implements. Anything else that is a
   *  registered EXTENSION method (std/parsing's ~ | ~> <~ etc.) must call the
   *  extension global — __arith__ has no arm for user symbolic operators and
   *  the whole parser chain silently degraded to Unit. */
  private val arithInfixOps = Set(
    "+", "-", "*", "/", "%", "&", "^", "|", "++", ":+",
    "<<", ">>", ">>>", "<", "<=", ">", ">=", "==", "!=")

  private def convertInfix(op: String, l: CT, r: CT, scope: List[String]): CT = op match
    case "&&" => CT.If(l, r, CT.Lit(Const.CBool(false)))
    // Compound assignment (x += e etc): scala.meta parses it as ApplyInfix;
    // the arith table has no "+=" — desugar to a var-cell write of x op e.
    // The CALLER (convertExpr ApplyInfix arm) handles the Name-lhs case before
    // reaching here, so this arm only fires when misused — keep the error clear.
    case "+=" | "-=" | "*=" | "/=" =>
      sys.error(s"compound assignment '$op' reached convertInfix — non-var lhs?")
    case "||" => CT.If(l, CT.Lit(Const.CBool(true)), r)
    case "::"  => CT.Ctor("Cons", List(l, r))
    case _ if !arithInfixOps(op) && extensionMethods.contains(op) =>
      CT.App(CT.Global(op), List(l, r))
    case _ if arithInfixOps(op) && extensionMethods.contains(op) =>
      // Ambiguous (e.g. `|`: bitwise-or on numbers, parser-choice extension on
      // data; `++`: concat vs Doc-beside): branch on RUNTIME operand types via
      // a single prim — prim args all evaluate in the CURRENT scope. (A Let
      // formulation shifted the second operand's De Bruijn locals: Let rhs #2
      // runs in an env already extended by rhs #1.)
      CT.Prim("__arithExt__", List(CT.Lit(Const.CStr(op)), l, r, CT.Global(op)))
    case _    => CT.Prim("__arith__", List(CT.Lit(Const.CStr(op)), l, r))

  private def convertMatch(
      scrut: CT,
      cases: List[Case],
      scope: List[String],
      handlerDispatch: Boolean = false
  ): CT =
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

    if hasNestedOrDup || cases.exists(_.cond.nonEmpty) then
      // General if-chain: each case becomes a condition (flat, using nested fieldAt) + bindings + body
      val scrutRef = CT.Local(0)
      def caseChain(cs: List[Case]): CT = cs match
        case Nil =>
          if handlerDispatch then
            CT.Prim(_root_.ssc.HandlerDispatchShape.MissPrimitive, List(scrutRef))
          else CT.App(CT.Global("__match_fail__"), Nil)
        case c :: rest =>
          val (conds, binds) = flattenPattern(c.pat, scrutRef, sc)
          val failCT  = caseChain(rest)
          // CT.Let is SEQUENTIAL: each binding shifts Local(k) by 1 for subsequent binds.
          // Shift the scrutinee reference (L0 at this scope) by k for the k-th binding.
          val bindRhs = binds.zipWithIndex.map { case ((expr, _), k) => shiftLocals(expr, k) }
          val bindNms = binds.map(_._2)
          val bodyScope = bindNms.reverse ++ sc
          val failInBodyScope = if binds.isEmpty then failCT else shiftLocals(failCT, binds.length)
          val rawBody = convertExpr(c.body, bodyScope)
          val selectedBody =
            if handlerDispatch then
              val eventInBodyScope = shiftLocals(scrutRef, binds.length)
              CT.Seq(List(
                CT.Prim(_root_.ssc.HandlerDispatchShape.SelectedPrimitive,
                  List(eventInBodyScope)),
                rawBody))
            else rawBody
          val bodyExpr  = c.cond match
            case Some(g) =>
              CT.If(convertExpr(g, bodyScope), selectedBody, failInBodyScope)
            case None => selectedBody
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
        // as-patterns (`ok @ P(...)`) bind the SCRUTINEE — only the general
        // if-chain (flattenPattern) can express that; CT.Match arms cannot.
        case _: Pat.Bind => true
        // NAMED catch-all (`case other => …body uses other…`): the CT.Match
        // default arm binds NOTHING, but the simple path converted the body as
        // if the name were in scope — every outer reference shifted by one
        // (Local(-1) crashes in std/parsing's runParserAll delegate arm).
        case Pat.Var(Term.Name(n)) if !isCtorName(n) && n != "_" => true
        case Pat.Typed(Pat.Var(Term.Name(n)), _) if !isCtorName(n) && n != "_" => true
        // `case _: T =>` with a TESTABLE type: the simple path drops the type and
        // lowers it to an unconditional default (wrong when other arms follow /
        // precede). Route to the general if-chain so flattenPattern emits the tag test.
        case Pat.Typed(Pat.Wildcard(), tpe) if typeTestTags(typeHeadOf(tpe)).isDefined => true
        case Pat.Extract.After_4_6_0(ctor, ac) =>
          val key = s"${ctorPatName(ctor)}/${ac.values.length}"
          val dup = !seen.add(key)
          dup || ac.values.exists {
            case _: Pat.Extract | _: Pat.Tuple | _: Lit => true
            case Pat.Typed(inner, _) =>
              inner match
                case _: Pat.Extract | _: Pat.Tuple | _: Lit => true
                case Pat.Var(Term.Name(n)) if isCtorName(n) => true
                case _ => false
            case Pat.Var(Term.Name(n)) if isCtorName(n) => true
            case _ => false
          }
        // `(a, b) :: rest` — a TUPLE (or nested ctor/literal) in the HEAD of a
        // cons pattern: convertPat flattens the head to "_" losing the bindings;
        // only the general chain extracts them (pipelineReport's stage loop).
        case Pat.ExtractInfix.After_4_6_0(h, Term.Name("::"), _) =>
          h match
            case _: Pat.Tuple | _: Pat.Extract | _: Lit => true
            case _ => false
        // Tuple with nested non-variable sub-patterns (e.g. `(x :: xs, y :: ys)`) needs
        // general if-chain so flattenPattern can recursively extract the sub-bindings.
        case Pat.Tuple(pats) =>
          pats.exists {
            case _: Pat.Extract | _: Pat.ExtractInfix | _: Lit => true
            // NESTED tuple sub-pattern (`case (((wPid, node), part), idx) =>`,
            // std/mapreduce zip-chains) — the fast path binds only the top
            // level, leaving inner names unbound globals; route to the
            // general if-chain where flattenPattern recurses.
            case _: Pat.Tuple => true
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
    case Term.Select(_, Term.Name(n)) if isCtorName(n) =>
      // Qualified zero-arg case pattern: `case Role.User =>` — tag test on the case name
      (List(CT.Prim("__isTag__", List(scrutRef, CT.Lit(Const.CStr(n)), CT.Lit(Const.CInt(0))))), Nil)
    case l: Lit =>
      (List(CT.Prim("__arith__", List(CT.Lit(Const.CStr("==")), scrutRef, convertExpr(l, scope)))), Nil)
    case Pat.Typed(inner, tpe) =>
      // Type-ascription pattern `case _: T =>` / `case x: T =>`: emit a runtime
      // tag test when T resolves to a known concrete tag set; otherwise (untestable
      // type) fall through to the inner pattern's behavior (historical wildcard).
      val (conds, binds) = flattenPattern(inner, scrutRef, scope)
      typeAscriptionCond(tpe, scrutRef) match
        case Some(tc) => (tc :: conds, binds)
        case None     => (conds, binds)
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
    case Pat.Bind(Pat.Var(Term.Name(n)), inner) =>
      // as-pattern `ok @ ParseOk(...)`: inner pattern's conditions + bind the
      // WHOLE scrutinee to the name (it fell through to (Nil, Nil) before —
      // no binding AND matched anything).
      val (conds, binds) = flattenPattern(inner, scrutRef, scope)
      (conds, (scrutRef, n) :: binds)
    case _ => (Nil, Nil)

  private def flattenCtorPat(tag: String, pats: List[Pat], scrutRef: CT, scope: List[String]): (List[CT], List[(CT, String)]) =
    val arity   = pats.length
    val tagCond = CT.Prim("__isTag__", List(scrutRef, CT.Lit(Const.CStr(tag)), CT.Lit(Const.CInt(arity))))
    val (subConds, subBinds) = pats.zipWithIndex.map { case (p, i) =>
      val fref = CT.Prim("fieldAt", List(scrutRef, CT.Lit(Const.CInt(i))))
      flattenPattern(p, fref, scope)  // all use current scope, no scope extension here
    }.unzip
    (tagCond :: subConds.flatten, subBinds.flatten)

  /** Shift free Local indices by `amount` when reusing a continuation under
   *  extra bindings (for example guard-false fall-through inside pattern Lets). */
  private def shiftLocals(expr: CT, amount: Int, cutoff: Int = 0): CT =
    if amount == 0 then expr
    else expr match
      case CT.Local(k) =>
        if k >= cutoff then CT.Local(k + amount) else expr
      case CT.Lam(arity, body) =>
        CT.Lam(arity, shiftLocals(body, amount, cutoff + arity))
      case CT.App(fn, args) =>
        CT.App(shiftLocals(fn, amount, cutoff), args.map(a => shiftLocals(a, amount, cutoff)))
      case CT.Let(rhs, body) =>
        CT.Let(rhs.map(r => shiftLocals(r, amount, cutoff)), shiftLocals(body, amount, cutoff + rhs.length))
      case CT.LetRec(lams, body) =>
        val recCutoff = cutoff + lams.length
        val shiftedLams = lams.map {
          case CT.Lam(arity, body) => CT.Lam(arity, shiftLocals(body, amount, recCutoff + arity))
          case other               => shiftLocals(other, amount, recCutoff)
        }
        CT.LetRec(shiftedLams, shiftLocals(body, amount, recCutoff))
      case CT.If(c, t, e) =>
        CT.If(shiftLocals(c, amount, cutoff), shiftLocals(t, amount, cutoff), shiftLocals(e, amount, cutoff))
      case CT.Ctor(tag, fields) =>
        CT.Ctor(tag, fields.map(f => shiftLocals(f, amount, cutoff)))
      case CT.Match(scrut, arms, default) =>
        val shiftedArms = arms.map(a => Arm(a.tag, a.arity, shiftLocals(a.body, amount, cutoff + a.arity)))
        CT.Match(shiftLocals(scrut, amount, cutoff), shiftedArms, default.map(d => shiftLocals(d, amount, cutoff)))
      case CT.Prim(op, args) =>
        CT.Prim(op, args.map(a => shiftLocals(a, amount, cutoff)))
      case CT.While(cond, body) =>
        CT.While(shiftLocals(cond, amount, cutoff), shiftLocals(body, amount, cutoff))
      case CT.Seq(terms) =>
        CT.Seq(terms.map(t => shiftLocals(t, amount, cutoff)))
      case other =>
        other  // Lit, Global — no locals

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
      // TOP-LEVEL wildcard arm: the VM's Match default binds NOTHING — a phantom
      // "_" name here shifted every outer reference in the default body by one
      // (Local(-1) crash in std/parsing's runParserAll delegate arm). Sub-pattern
      // wildcards (inside Extract) keep their "_" slot for field-arity alignment.
      case Pat.Wildcard()                                   => (None, Nil)
      // Scala3: uppercase stable-id patterns are Term.Name (not Pat.Var)
      case Term.Name(n) if isCtorName(n)                   => (Some((n, 0)), Nil)
      // Qualified zero-arg case pattern: `case Role.User =>`
      case Term.Select(_, Term.Name(n)) if isCtorName(n)  => (Some((n, 0)), Nil)
      // Uppercase name in Pat.Var = constructor (enum case or zero-arity case class)
      case Pat.Var(Term.Name(n)) if isCtorName(n)          => (Some((n, 0)), Nil)
      case Pat.Var(Term.Name(n))                            => (None, List(n))
      case Pat.Typed(Pat.Wildcard(), _)                     => (None, Nil)
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
          case Pat.Var(Term.Name(n))               => List(n)
          case Pat.Typed(Pat.Var(Term.Name(n)), _) => List(n)
          case Pat.Wildcard()                      => List("_")
          case Pat.Typed(Pat.Wildcard(), _)        => List("_")
          case _                                   => List("_")
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
        "IllegalStateException", "UnsupportedOperationException",
        "Currency", "Money", "StripeProvider", "PixProvider", "FedNowProvider",
        "FedNowConfig")

  /** functionConstructors that also expose companion STATICS as `X.MEMBER` (e.g.
   *  `Currency.USD`). The runtime global methodObject resolves the member; the
   *  compile side routes `X.MEMBER` to its `apply(MEMBER)` (cur-2). */
  private val companionStaticConstructors: Set[String] = Set("Currency")

  private val impureMethodObjects: Set[String] =
    Set("Dataset", "DistributedDataset", "HandlerRegistry", "WorkerProtocol",
        "ShuffleProtocol", "Cluster")

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
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(obj), _), _)
        if impureMethodObjects.contains(obj) =>
      false
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(obj), _), ac)
        if methodObjectNames.contains(obj) =>
      // Factory calls on converted OBJECTS (Parser.regex, Tool.text, ...) are
      // constructor-like and safe to hoist — std parser grammars define
      // top-level vals from them that pass-2 defs reference.
      ac.values.forall(isPureValRhs)
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
  private def isFloatLit(t: Term): Boolean = t match
    case Lit.Double(_) | Lit.Float(_) => true
    case _                            => false

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

  private def emitCaseClassMethods(cls: Defn.Class, defsB: collection.mutable.Builder[CDef, List[CDef]]): Unit =
    val className = cls.name.value
    val fields = fieldRegistry.getOrElse(className, Vector.empty).toList
    cls.templ.stats.collect { case m: Defn.Def => m }.foreach { m =>
      caseClassMethodNames += m.name.value
      val params = "this" :: allParams(m)
      val baseScope = params.reverse
      val saved = suppressExtName
      suppressExtName = Some(m.name.value)
      val body =
        try
          def bindFields(todo: List[(String, Int)], sc: List[String]): CT =
            todo match
              case Nil => convertExpr(m.body, sc)
              case (fieldName, _) :: rest if sc.contains(fieldName) =>
                bindFields(rest, sc)
              case (fieldName, idx) :: rest =>
                val recv = lookupVar("this", sc)
                val field = CT.Prim("fieldAt", List(
                  recv,
                  CT.Lit(Const.CInt(idx)),
                  CT.Lit(Const.CStr(fieldName))
                ))
                CT.Let(List(field), bindFields(rest, fieldName :: sc))
          bindFields(fields.zipWithIndex, baseScope)
        finally suppressExtName = saved
      val lam = CT.Lam(params.length, body)
      extAccum.getOrElseUpdate(m.name.value, collection.mutable.ListBuffer.empty) +=
        ((className, params.length, lam))
    }

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
      val saved  = suppressExtName
      suppressExtName = Some(m.name.value)
      val body   = try convertExpr(m.body, scope) finally suppressExtName = saved
      val lam    = CT.Lam(params.length, body)
      extAccum.getOrElseUpdate(m.name.value, collection.mutable.ListBuffer.empty) += ((typeHead, params.length, lam))
    }

  private def convertGiven(g: Defn.Given): CT =
    // Extract the parent type sig (e.g. "Show[Int]") from template.inits
    val typeSig = g.templ.inits.headOption.fold("")(_.tpe.syntax)
    givenRegistry(typeSig) = g.name.value
    // (tc, typeHead) → instance name, for call-site __resolve_given__ tables:
    // "Monoid[Int]" → ("Monoid","Int"); "Monoid[List[A]]" → ("Monoid","List")
    val tcOf   = typeSig.takeWhile(_ != '[')
    val inner  = typeSig.dropWhile(_ != '[').drop(1).dropRight(1)
    val headOf = inner.takeWhile(c => c != '[' && c != ',' && c != ' ')
    if tcOf.nonEmpty && headOf.nonEmpty then givenByTcHead((tcOf, headOf)) = g.name.value
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
    val types  = fieldTypeRegistry.getOrElse(className, Vector.fill(fields.length)("Any")).toList
    val genName = s"__${tcName.toLowerCase}_${className}__"
    givenRegistry(s"$tcName[$className]") = genName
    val mirror = CT.Ctor("Mirror", List(
      CT.Lit(Const.CStr(className)),
      fields.foldRight(CT.Ctor("Nil", Nil): CT)((f, acc) => CT.Ctor("Cons", List(CT.Lit(Const.CStr(f)), acc))),
      types.foldRight(CT.Ctor("Nil", Nil): CT)((t, acc) => CT.Ctor("Cons", List(CT.Lit(Const.CStr(t)), acc)))  // real field types, not "Any"
    ))
    // Call Tc.derived(mirror) — compiled as __method__("derived", TcCompanion, mirror)
    Some(CDef(genName, CT.Prim("__method__", List(CT.Lit(Const.CStr("derived")), CT.Global(tcName), mirror))))
