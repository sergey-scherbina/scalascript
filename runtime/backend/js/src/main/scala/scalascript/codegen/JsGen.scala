package scalascript.codegen

import scalascript.ast.*
import scalascript.transform.{DirectAnorm, DirectTypeUtils, EffectAnalysis}
import scalascript.typeddata.TypedJsonCodecRuntime
import scala.collection.mutable
// jsgen-split-p1: pure helpers extracted out of this file.
import JsGenStringUtils.*

/** JavaScript code generator for ScalaScript modules.
 *
 *  Walks the same Module type the interpreter uses, generating JS for each
 *  Scala code block. The generated JS can be embedded in HTML and run in-browser.
 */
object JsGen:

  enum Segment:
    case ScalaScriptJs(code: String)
    case ScalaSource(source: String)

  /** Statistics from a tree-shaking pass.  Returned by [[generateWithStats]]
   *  and printed to stderr by the CLI when `--stats` is active.
   *
   *  @param kept   number of top-level declarations retained in the output
   *  @param total  total number of top-level declarations before shaking
   */
  case class TreeShakeStats(kept: Int, total: Int):
    def pruned: Int = total - kept
    /** One-line human-readable summary, e.g. "Tree-shake: kept 42 / 78 symbols (removed 36, -46%)" */
    def summary: String =
      val pct = if total == 0 then 0 else (pruned * 100) / total
      s"Tree-shake: kept $kept / $total symbols (removed $pruned, -$pct%)"

  /** Generate JS source with tree-shaking (default) or without (`noTreeShake = true`).
   *  Returns both the generated code and the shaking statistics.
   *
   *  Use [[generate]] when you only need the code and don't need stats. */
  def generateWithStats(
      module:      Module,
      baseDir:     Option[os.Path] = None,
      intrinsics:  Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:    Option[os.Path] = None,
      noTreeShake: Boolean = false
  ): (String, Option[TreeShakeStats]) =
    val shakeResult =
      if noTreeShake then None
      else Some(TreeShaker.shake(module))
    val reachable = shakeResult.map(_.reachable)
    val gen  = new JsGen(baseDir, intrinsics, lockPath, reachableNames = reachable)
    val code = gen.genModule(module)
    val stats = shakeResult.map(r => TreeShakeStats(kept = r.kept, total = r.total))
    (code, stats)

  /** Generate JS source for all scalascript code blocks in a module.
   *  Tree-shaking is OFF by default here to preserve the existing API behaviour;
   *  use [[generateWithStats]] to enable it. */
  def generate(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None
  ): String =
    val gen = new JsGen(baseDir, intrinsics, lockPath)
    gen.genModule(module)

  /** Generate segments in document order, preserving scala/scalascript interleaving.
   *  Tree-shaking is OFF by default to preserve the existing API behaviour.
   *  Pass `noTreeShake = false` explicitly (or use [[generateWithStats]]) to enable it. */
  def generateSegmented(
      module:      Module,
      baseDir:     Option[os.Path] = None,
      intrinsics:  Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:    Option[os.Path] = None,
      noTreeShake: Boolean = true
  ): List[Segment] =
    val shakeResult =
      if noTreeShake then None
      else Some(TreeShaker.shake(module))
    val reachable = shakeResult.map(_.reachable)
    val gen = new JsGen(baseDir, intrinsics, lockPath, reachableNames = reachable)
    gen.genModuleSegmented(module)

  /** True if the module contains at least one scalascript block. */
  def hasBlocks(module: Module): Boolean =
    module.sections.exists(sectionHas)

  private def sectionHas(s: Section): Boolean =
    s.content.exists {
      case cb: Content.CodeBlock => Lang.isScalaScript(cb.lang)
      case _                     => false
    } || s.subsections.exists(sectionHas)

  // ─── v2.0 Phase 2 — split-runtime emit (JS) ────────────────────────────
  //
  // Mirrors `JvmGen.{generateRuntime, generateUserOnly, detectCapabilities}`.
  //
  // The legacy `generate(module)` returns user code only (no preamble).  The
  // CLI's `buildScjsSource` historically prepended the full runtime preamble
  // before persisting to `.scjs`, so every module artifact carried ~80 KB
  // of duplicate runtime JS.  Phase 2 factors the preamble into a separate
  // `_runtime.scjs-runtime` artifact compiled once per artifact dir and
  // textually concatenated before each module's user code at link time.
  //
  // For JS the equivalent of JVM's `package _ssc_runtime` is just textual
  // concatenation: classic-script JS has a single global namespace, so
  // emitting `function _show(...) { … }` at the top of `out.js` makes the
  // symbol reachable from every later module without imports or namespacing.
  // An IIFE wrapper (`const _ssc_runtime = (function(){ … return { … }; })();`)
  // was considered but rejected — the runtime exports 200+ identifiers and
  // wrapping/destructuring each one is fragile (typed-tag DSL, `Console`,
  // `attr`, `Async`, `Logger`, `Random`, etc. would all need explicit
  // re-exports).  Flat-scope concatenation keeps the runtime body verbatim.

  /** Identifier for a runtime capability that the user module depends on.
   *  Drives the `generateRuntime` capability switch and determines which
   *  helper blocks (effects, async, mcp, dataset) are emitted. */
  sealed trait Capability
  object Capability:
    /** Core runtime: `_show`, `_println`, `_tupleConcat`, `_dispatch`, given
     *  registry, JSON, Free Monad, fs helpers.  Always present. */
    case object Core      extends Capability
    case object Async     extends Capability  // `JsRuntimeAsync` — Async + Actor + Storage
    case object Effects   extends Capability  // `JsRuntimeV14Effects` — Logger/Random/Clock/Env/Auth
    case object Mcp       extends Capability  // `JsRuntimeMcp` — MCP server / client
    case object Dataset   extends Capability  // `JsRuntimeDataset` — Dataset[T] lazy pipeline
    case object Payment   extends Capability  // `JsRuntimePayment` — Payment Request API
    case object HtmlDsl   extends Capability  // `JsRuntimePart1b` — HTTP serve/route/sessions/metrics
    case object Jwt       extends Capability  // `JsRuntimePart1c` — JWT/OAuth2/CSRF
    case object WsServer  extends Capability  // `JsRuntimePart1d` — WebSocket/SSE/CORS
    case object Optics    extends Capability  // `JsRuntimeOptics` — Lens/Optional/Traversal/Prism
    case object Signals   extends Capability  // `JsRuntimeSignals` — reactive signals
    case object IndexedDb extends Capability  // `JsRuntimeIndexedDb` — client-side storage
    case object Graphql   extends Capability  // `JsRuntimeGraphql` — GraphQL server + client

    val all: Set[Capability] = Set(Core, Async, Effects, Mcp, Dataset, Payment,
                                   HtmlDsl, Jwt, WsServer, Optics, Signals, IndexedDb,
                                   Graphql)

    /** Encode a capability as a stable, persistence-safe string.
     *  These strings appear in `.scjs-runtime` envelopes — do not rename. */
    def encode(c: Capability): String = c match
      case Core      => "core"
      case Async     => "async"
      case Effects   => "effects"
      case Mcp       => "mcp"
      case Dataset   => "dataset"
      case Payment   => "payment"
      case HtmlDsl   => "htmldsl"
      case Jwt       => "jwt"
      case WsServer  => "wsserver"
      case Optics    => "optics"
      case Signals   => "signals"
      case IndexedDb => "indexeddb"
      case Graphql   => "graphql"

    def decode(s: String): Option[Capability] = s match
      case "core"      => Some(Core)
      case "async"     => Some(Async)
      case "effects"   => Some(Effects)
      case "mcp"       => Some(Mcp)
      case "dataset"   => Some(Dataset)
      case "payment"   => Some(Payment)
      case "htmldsl"   => Some(HtmlDsl)
      case "jwt"       => Some(Jwt)
      case "wsserver"  => Some(WsServer)
      case "optics"    => Some(Optics)
      case "signals"   => Some(Signals)
      case "indexeddb" => Some(IndexedDb)
      case "graphql"   => Some(Graphql)
      case _           => None

  /** Inspect `module` and return the capability set its emitted JS would
   *  depend on.  `Core` is always included.  Other capabilities are
   *  toggled by scanning the parsed scalascript blocks for references
   *  to the corresponding effect / DSL names (`Async.*`, `Actor.*`,
   *  `Logger.*` / `Random.*` / `Clock.*` / `Env.*`, `mcpServer` /
   *  `serveMcp`, `Dataset.*`). */
  def detectCapabilities(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None
  ): Set[Capability] =
    new JsGen(baseDir, intrinsics, lockPath).detectCapabilities(module)

  /** Emit the runtime preamble for the given capability set.  No user code
   *  is included.  Capability `Core` is always added regardless of input —
   *  the per-module emit relies on `_show` / `_println` / HTML DSL helpers
   *  that live in the core block.
   *
   *  v2.0 Phase 2 — split-runtime emit. */
  def generateRuntime(capabilities: Set[Capability]): String =
    val caps = capabilities + Capability.Core
    val sb   = new StringBuilder
    sb.append("// ── scalascript JS runtime ──────────────────────────────────────────\n")
    // Inject js/glue.js preambles from loaded .ssclib archives (Phase 4 FFI).
    val gluePreambles = scalascript.imports.GlueJsPreambleRegistry.preambles
    if gluePreambles.nonEmpty then
      sb.append("// ── glue preambles ─────────────────────────────────────────────────\n")
      gluePreambles.foreach { glue =>
        sb.append(glue)
        if !glue.endsWith("\n") then sb.append('\n')
      }
    // v1.61.6: build from individual parts so unused sections are omitted.
    // Part1a is always included (Core).
    sb.append(JsRuntimePart1a)
    if !JsRuntimePart1a.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.HtmlDsl) then
      sb.append(JsRuntimePart1b)
      if !JsRuntimePart1b.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Jwt) then
      sb.append(JsRuntimePart1c)
      if !JsRuntimePart1c.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.WsServer) then
      sb.append(JsRuntimePart1d)
      if !JsRuntimePart1d.endsWith("\n") then sb.append('\n')
    // Part2a and Part2b are always included (Core dispatch, _show, _tupleConcat, Free Monad, fs).
    sb.append(JsRuntimePart2a)
    if !JsRuntimePart2a.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Optics) then
      sb.append(JsRuntimeOptics)
      if !JsRuntimeOptics.endsWith("\n") then sb.append('\n')
    sb.append(JsRuntimePart2b)
    if !JsRuntimePart2b.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Signals) then
      sb.append(JsRuntimeSignals)
      if !JsRuntimeSignals.endsWith("\n") then sb.append('\n')
    sb.append(TypedJsonCodecRuntime.jsFacade)
    if !TypedJsonCodecRuntime.jsFacade.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.IndexedDb) then
      sb.append(JsRuntimeIndexedDb)
      if !JsRuntimeIndexedDb.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Async) then
      sb.append(JsRuntimeAsync)
      if !JsRuntimeAsync.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Effects) then
      sb.append(JsRuntimeV14Effects)
      if !JsRuntimeV14Effects.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Mcp) then
      sb.append(JsRuntimeMcp)
      if !JsRuntimeMcp.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Dataset) then
      sb.append(JsRuntimeDataset)
      if !JsRuntimeDataset.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Payment) then
      sb.append(JsRuntimePayment)
      if !JsRuntimePayment.endsWith("\n") then sb.append('\n')
    // GraphQL runtime references `route` (Part1b) / `serve` (Part1d) /
    // `_mkResp` / `_jsonConvert` (defined above), so it is appended last.
    // Capability detection forces HtmlDsl + WsServer + Async whenever Graphql
    // is present, so those parts are emitted.
    if caps.contains(Capability.Graphql) then
      sb.append(JsRuntimeGraphql)
      if !JsRuntimeGraphql.endsWith("\n") then sb.append('\n')
    sb.toString

  /** Emit user code only — no runtime preamble.  In JS this is a synonym
   *  for [[generate]] today (`genModule` never prepended the preamble; the
   *  preamble was concatenated by the CLI's `buildScjsSource`).  The alias
   *  exists so call sites read symmetrically with the JVM split-runtime
   *  emit (`JvmGen.generateUserOnly`).
   *
   *  Tree-shaking is OFF by default to preserve the existing API behaviour.
   *  The CLI passes `noTreeShake = false` to enable dead-code elimination
   *  for artifact builds.
   *
   *  v2.0 Phase 2 — split-runtime emit. */
  def generateUserOnly(
      module:      Module,
      baseDir:     Option[os.Path] = None,
      intrinsics:  Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:    Option[os.Path] = None,
      noTreeShake: Boolean = true
  ): String =
    generateWithStats(module, baseDir, intrinsics, lockPath, noTreeShake)._1










val JsRuntime: String =
  JsRuntimePart1a + JsRuntimePart1b + JsRuntimePart1c + JsRuntimePart1d +
  JsRuntimePart2a + JsRuntimeOptics + JsRuntimePart2b + JsRuntimeSignals +
  TypedJsonCodecRuntime.jsFacade + JsRuntimeIndexedDb

/** Built-in `Async` effect runtime — concatenated onto `JsRuntime`.
 *  Lives in its own val because together with the rest of the runtime
 *  it overflows the JVM's 65 535-byte string-literal limit.  Same
 *  semantics as the interpreter and JvmGen: `delay` blocks via
 *  Atomics on Node, thunks passed to `async` / `parallel` run
 *  synchronously, results come back in declared order.  Itself split
 *  into two halves to stay under that 65 KiB string-literal cap. */
lazy val JsRuntimeAsync: String = JsRuntimeAsyncA + JsRuntimeAsyncB





class JsGen(
    baseDir:    Option[os.Path] = None,
    intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
    lockPath:   Option[os.Path] = None,
    // Shared across parent + all child generators to track which top-level `const X` names
    // have been declared.  When two package-qualified imports share the same top-level namespace
    // (e.g. std.ui.primitives and std.ui.nodes both wrap content in `object std { ... }`), the
    // second occurrence merges via _ssc_mergeDeep instead of re-declaring the const.
    private[codegen] val topLevelConsts: mutable.Set[String] = mutable.Set.empty,
    private[codegen] val mergeHelperEmitted: Array[Boolean]  = Array(false),
    // Shared across parent + all child generators to track which import-binding `const` names
    // have been declared.  A module imported transitively (e.g. nodes.ssc pulled in by both
    // primitives.ssc and layout.ssc) must not emit duplicate `const TkNode = …` lines.
    // Pre-populated with preamble-defined globals so imports of the same name are skipped
    // (e.g. `[Left, Right](std/either.ssc)` must not emit `const Left = …` since
    // `function Left` is already in the preamble and `const` cannot redeclare it).
    private[codegen] val declaredBindings: mutable.Set[String] =
      mutable.Set("Left", "Right", "Some", "None", "Nil"),
    // When Some(set), only top-level declarations whose name is in the set are emitted.
    // None means no filtering (tree-shaking disabled — emit everything).
    // Populated by TreeShaker.shake() and threaded from the companion object entry points.
    private[codegen] val reachableNames: Option[Set[String]] = None) extends JsGenAnalysisQueries:
  import scala.meta.*

  private[codegen] val sb = StringBuilder()
  private var indent = 0
  private var tmpIdx = 0
  private var hasMain = false
  private var mainCalled = false
  // Active parameter renames for JS reserved-word param names (e.g. `default` → `default_p`).
  private val paramRenames = mutable.Map.empty[String, String]
  // Set when the module uses runAsyncParallel; causes the user code sections to
  // be wrapped in a top-level async IIFE so `await _runAsyncParallel(...)` works.
  private var usesRunAsyncParallel: Boolean = false
  // Set when the module uses runActors; same async-IIFE wrap so
  // `await _runActors(...)` works.  The async-aware `_runActors`
  // yields to Node's event loop between scheduler ticks (see the
  // runtime emission of `_runActors` in `JsRuntimeAsync`) so that
  // libuv-accepted HTTP requests still drain while actors are
  // long-blocked on a `receive` deadline.  Detected by
  // `scanForRunActors` and combined into the `needsAsync` decision
  // alongside `usesRunAsyncParallel` and `needSqlPreamble`.
  private var usesRunActors: Boolean = false
  // Set when client/browser code uses `awaitClient(promise)`.  This helper
  // lowers directly to JS `await` and therefore needs the top-level async IIFE.
  private var usesAwaitClient: Boolean = false
  // Set when the module uses stream terminal operations (runForeach, runFold,
  // runToList, runDrain) — these are async and need the top-level async IIFE.
  private var usesStreams: Boolean = false
  // Stack of placeholder counters: each AnonymousFunction pushes 0, Placeholder increments top
  private var phCounters: List[Int] = Nil
  // Names of variables known to hold integer values (for integer division detection)
  private val intVars = scala.collection.mutable.Set[String]()
  // fname → set of all group members (populated by analyzeMutualRecursion before emit)
  private var mutualGroups: Map[String, Set[String]] = Map.empty
  // Effect operations declared in the module, as "Eff.op" strings.
  private[codegen] val effectOps: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty
  // Functions that transitively perform effects — emitted in CPS form so callers
  // get a Free value (Pure plain value or Perform node) and can compose them.
  private[codegen] val effectfulFuns: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty
  // Effect object names that carry the `val __multiShot__ = true` marker.
  private val multiShotEffects: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty
  // Maps summon key "TC_A" → local param name "A$TC" for context-bound params
  // of the current Defn.Def being emitted. Cleared before each function.
  private val cbSummonMap = scala.collection.mutable.Map.empty[String, String]
  // funcParamOrder: function name → ordered parameter names.
  // Populated by collectFuncParamOrders before the main emit pass.
  // Used at call sites with named args to reorder them to positional order.
  private val funcParamOrder = scala.collection.mutable.Map.empty[String, List[String]]
  // Functions with 2+ explicit (non-using) parameter clause groups.
  // Call sites for these need flattened args: _call(f, a, b) not _call(_call(f, a), b).
  private val multiParamGroupFns = scala.collection.mutable.Set.empty[String]
  // Zero-explicit-param defs (def f: T = body). In JS these compile to function f() { return body; }.
  // When called as f(x), we must generate _call(f(), x) so the returned function gets applied.
  private val zeroParamFns = scala.collection.mutable.Set.empty[String]
  // User-defined functions with :Int or :Long return type — their call sites can use isIntExpr.
  private val intFunctions = scala.collection.mutable.Set.empty[String]
  // User-defined functions with :Double or :Float return type — their call sites can use isNumericExpr.
  private val numericFunctions = scala.collection.mutable.Set.empty[String]
  // v1.27 Phase 3 — sql block emission state.  Mirrors JvmGen's
  // `sqlBlockCounter` / `sqlPerSection`: sequential `_sqlBlock_<n>`
  // names, and per-section "first-only" tracking so only the first
  // `sql` block in each section gets the friendly `<sectionId>.sql`
  // alias.  Cleared at the start of every `genModule` call.
  private var sqlBlockCounter: Int = 0
  private val sqlPerSection = scala.collection.mutable.Map.empty[String, Int]

  // Typeclass parent map: TC -> List[parentTC], accumulated from all trait declarations.
  // Used when registering `given` instances to also register under parent TC keys.
  private val tcParentMap = scala.collection.mutable.Map.empty[String, List[String]]
  // Case class type name → ordered field names; populated per module by genModule.
  // Used in genPattern to emit scrutVar.fieldName instead of Object.values(...)[i].
  private var caseClassFieldsByType: Map[String, List[String]] = Map.empty
  // Case class type name → (field name → declared type); populated per module by genModule.
  // Used in genPattern to add Double/Float-typed bound vars to numericVars.
  private var caseClassFieldTypeMap: Map[String, Map[String, String]] = Map.empty
  // Variables statically known to hold Double/Float values — arithmetic on them uses direct JS operators.
  private val numericVars = scala.collection.mutable.Set[String]()
  // js-codegen-opt-p2: loop-invariant constant tuple hoisting.
  // While-loop codegen sets this buffer; constant-tuple exprs append (name, frozenExpr)
  // and return the name instead of re-allocating on every iteration.
  private var loopHoistBuf: mutable.Buffer[(String, String)] | Null = null
  private var hoistIdx: Int = 0

  private def freshTmp(): String =
    tmpIdx += 1
    s"_t$tmpIdx"

  private def isLiteralTerm(t: Term): Boolean = t match
    case _: Lit.Int | _: Lit.Double | _: Lit.Float | _: Lit.Long |
         _: Lit.String | _: Lit.Boolean | Lit.Unit() => true
    case _ => false

  private def isConstantTupleExpr(t: Term): Boolean = t match
    case Term.Tuple(elems) => elems.forall(isLiteralTerm)
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) if op.value == "++" || op.value == ":::" =>
      isConstantTupleExpr(lhs) && argClause.values.asInstanceOf[List[Term]].forall(e => isLiteralTerm(e) || isConstantTupleExpr(e))
    case _ => false

  private def collectConstantTupleElems(t: Term): List[String] = t match
    case Term.Tuple(elems) => elems.map(genExpr)
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) if op.value == "++" || op.value == ":::" =>
      collectConstantTupleElems(lhs) ++ argClause.values.asInstanceOf[List[Term]].flatMap {
        case te: Term.Tuple => collectConstantTupleElems(te)
        case other          => List(genExpr(other))
      }
    case _ => List(genExpr(t))

  private def freshHoistConst(value: String): String =
    val name = s"_k$hoistIdx"
    hoistIdx += 1
    loopHoistBuf.nn += ((name, value))
    name

  private def line(s: String): Unit =
    sb.append("  " * indent).append(s).append("\n")

  // ─── Named-arg param-order collection ────────────────────────────
  //
  // Pre-pass over all Defn.Def nodes in the module to record the ordered
  // parameter list for each user-defined function.  At call sites that
  // pass named args (Term.Assign), genApply consults funcParamOrder to
  // reorder the arguments into the declared positional order before
  // emitting a regular positional JS call.

  private def collectFuncParamOrders(module: Module): Unit =
    funcParamOrder.clear()
    multiParamGroupFns.clear()
    zeroParamFns.clear()
    intFunctions.clear()
    numericFunctions.clear()
    def collectDefs(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def =>
        val paramVals = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
        val params = paramVals.map(_.name.value).toList
        if params.nonEmpty then funcParamOrder(d.name.value) = params
        val explicitGroups = d.paramClauseGroups.flatMap(_.paramClauses).filterNot(_.mod.nonEmpty)
        if explicitGroups.size > 1 then multiParamGroupFns += d.name.value
        if explicitGroups.isEmpty then zeroParamFns += d.name.value
        // Track Int/Long/Double/Float return type for isIntExpr/isNumericExpr at call sites.
        d.decltpe match
          case Some(Type.Name("Int" | "Long"))     => intFunctions     += d.name.value
          case Some(Type.Name("Double" | "Float")) => numericFunctions += d.name.value
          case _ => ()
        // Track Int/Long/Double/Float-typed parameters so arithmetic on them avoids _arith.
        paramVals.foreach { pv =>
          pv.decltpe match
            case Some(Type.Name("Int" | "Long"))         => intVars += pv.name.value
            case Some(Type.Name("Double" | "Float"))     => numericVars += pv.name.value
            case _ => ()
        }
      case _ => ()
    }
    def scanSection(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => collectDefs(stats)
              case Term.Block(stats) => collectDefs(stats)
              case _                 => ()
            }
          }
        case _ => ()
      }
      section.subsections.foreach(scanSection)
    module.sections.foreach(scanSection)

  // ─── Mutual recursion analysis ───────────────────────────────────

  private def analyzeMutualRecursion(module: Module): Unit =
    val funcs = mutable.Map[String, Set[String]]()

    def collectFuncs(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def if d.paramClauseGroups.nonEmpty =>
        funcs(d.name.value) = tailCallTargets(d.body, d.name.value, tailPos = true)
      case _ => ()
    }

    def scanSection(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => collectFuncs(stats)
              case Term.Block(stats) => collectFuncs(stats)
              case _                 => ()
            }
          }
        case _ => ()
      }
      section.subsections.foreach(scanSection)

    module.sections.foreach(scanSection)

    val funcNames = funcs.keySet.toSet
    val sccs = findSCCs(funcs.toMap, funcNames)
    mutualGroups = sccs.filter(_.size > 1).foldLeft(Map.empty[String, Set[String]]) { (acc, scc) =>
      acc ++ scc.map(name => name -> scc)
    }

  private def findSCCs(graph: Map[String, Set[String]], names: Set[String]): List[Set[String]] =
    var idx = 0
    val stack  = mutable.Stack[String]()
    val onStk  = mutable.Set[String]()
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

  // Returns names of functions called in tail position (excludes selfName).
  private def tailCallTargets(tree: scala.meta.Tree, selfName: String, tailPos: Boolean): Set[String] =
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

  // ─── Module entry ─────────────────────────────────────────────────

  /** Module-level `dependencies:` from the front-matter (set at the top
   *  of `genModule` / `genModuleSegmented`).  Threaded into `genImport`
   *  so `<dep-name>://path` imports rewrite through the resolver. */
  private var moduleDeps: Map[String, String] = Map.empty

  /** Absolute paths of `.ssc` files that have already been inlined by
   *  `genImport`.  Mirrors the cycle-protection invariant in
   *  `JvmGen.importedFiles` — a child module re-importing something
   *  the parent already pulled in (or a diamond import) emits nothing
   *  the second time around. */
  private val importedFiles: scala.collection.mutable.Set[String] =
    scala.collection.mutable.Set.empty

  def genModule(module: Module): String =
    sb.clear()
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    caseClassFieldsByType = caseClassFieldsInModule(module)
    caseClassFieldTypeMap = caseClassFieldTypesInModule(module)
    collectFuncParamOrders(module)
    analyzeMutualRecursion(module)
    analyzeEffects(module)
    scanForRunAsyncParallel(module)
    scanForRunActors(module)
    scanForAwaitClient(module)
    scanForStreams(module)
    // v1.27 Phase 3 — sql state.  Reset every module to keep `genModule`
    // re-entrant; emit preamble once when at least one sql block is
    // present (URL-prefix providers + ConnectionRegistry init + resolver).
    sqlBlockCounter = 0
    sqlPerSection.clear()
    val needSqlPreamble = hasSqlBlocks(module)
    if needSqlPreamble then emitSqlPreamble(module)
    // Front-matter route declarations are emitted BEFORE the user blocks so
    // a typical user-side `serve(port)` (last statement of the script) sees
    // them already registered.  JS function declarations are hoisted, so
    // forward references to the handler defs resolve at call time.
    emitFrontmatterRoutes(module)
    emitI18nTable(module)
    emitHttpTypedRouteClients(module.manifest.toList.flatMap(_.apiClients), module)
    // Async wrap rationale (v1.27): sql blocks compile to `await
    // SqlRuntimeJs.execute(...)`, which is only legal at top level in
    // ESM with top-level-await support.  An async IIFE is the
    // universally-portable wrapper that also keeps the legacy
    // classic-script target working.  Same wrapper as the existing
    // `runAsyncParallel` path; the three flags collapse into one
    // `needsAsync` decision.  `runActors` joins this set because its
    // scheduler is `async` — it `await`s `setImmediate`/`setTimeout`
    // between ticks so Node's event loop drains its I/O queue while
    // any actor is blocked on a long-armed `receive` (otherwise an
    // HTTP server bound via `serveAsync(...)` from inside the same
    // `runActors { ... }` body would be unreachable).
    val needsAsync = usesRunAsyncParallel || usesRunActors || usesAwaitClient || needSqlPreamble || usesStreams
    if needsAsync then
      line("(async () => {")
      // Write-through `_println` for code that runs *inside* the
      // async IIFE.  Without this, every `_println` call after the
      // first `await` pushes to `_output` but the outer
      // `process.stdout.write(_output...)` (appended by
      // `Main.scala`'s emit-js / link pipeline) ran synchronously
      // *before* the IIFE's microtasks fire — so async-scheduled
      // prints (actor body output, post-await SQL prints, etc.) were
      // silently dropped.  Override `Console.println` / `Console.print`
      // too — Normalize rewrites bare `println(...)` to
      // `Console.println(...)`, and the runtime's `Console` object
      // captures `_println` by reference at init time, so
      // reassigning `_println` alone leaves `Console.println`
      // pointing at the *buffered* original.  The overrides do NOT
      // push to `_output` (the buffer is only useful for the browser
      // SPA overlay, which doesn't share the Node code path), so the
      // outer segment-end flush has nothing to re-emit and we avoid
      // the duplicate-print failure mode.  `NodeBackend` re-overrides
      // these with an equivalent body — the duplicate-assign is
      // intentional and harmless.
      line("if (typeof process !== 'undefined' && process.stdout && typeof _output !== 'undefined') {")
      line("  _println = function(...args) { process.stdout.write(args.map(_show).join(' ') + '\\n'); };")
      line("  _print   = function(...args) { process.stdout.write(args.map(_show).join(''));         };")
      line("  if (typeof Console !== 'undefined') {")
      line("    Console.println = _println;")
      line("    Console.print   = _print;")
      line("  }")
      line("}")
    // Pre-connect all databases declared in frontmatter so that the
    // synchronous Db facade (defined in emitSqlPreamble) can serve
    // route-handler Db.query / Db.execute calls without awaiting.
    if needSqlPreamble then
      val dbNames = module.manifest.toList.flatMap(_.databases).map(_.name)
      for dbName <- dbNames do
        line(s"Db._conns[${jsQuote(dbName)}] = await _ssc_sql_resolve(${jsQuote(dbName)});")
    module.sections.foreach(genSection)
    // Auto-call main() if defined and not already called
    if hasMain && !mainCalled then
      line("if (typeof main === 'function') { main(); }")
    if needsAsync then
      line("})().catch(e => {")
      line("  const msg = String(e && e.stack ? e.stack : e);")
      line("  if (typeof process !== 'undefined' && process.stderr) { process.stderr.write(msg + '\\n'); process.exit(1); }")
      line("  else if (typeof document !== 'undefined') { document.body.textContent = msg; }")
      line("  else { console.error(msg); }")
      line("});")
    else
      // Flush synchronous _output to stdout (non-async, non-browser path).
      // Async mode redirects _println to process.stdout.write directly;
      // browser mode relies on JsRuntimeBrowserPatch. This flush covers
      // synchronous scripts running in Node (e.g. ssc run without awaitClient).
      line("if (typeof process !== 'undefined' && process.stdout && typeof _output !== 'undefined' && _output.length > 0) {")
      line("  for (const _l of _output) process.stdout.write(_l + '\\n');")
      line("  _output = [];")
      line("}")
    sb.toString

  /** Emit the v1.27 sql-block preamble: hand-written `sql-runtime.mjs`
   *  source from `backend-sql-runtime-js`, plus the per-module
   *  `_ssc_sql_registry` + `_ssc_sql_resolve(dbName)` dispatcher.
   *
   *  The runtime source is read once (lazy val) from the classpath
   *  resource shipped by `backend-sql-runtime-js`.  Calls into
   *  `ConnectionRegistry`, `execute`, etc. resolve inside that source —
   *  no `import` statements are required in user-emitted code.
   *
   *  Note on async: the runtime is itself ES module syntax (`export
   *  class …`) when run via `node --test`-style import, but JsGen
   *  embeds it textually into a classic script context.  Strip the
   *  `export` keyword so the names land at the function/class top of
   *  the IIFE scope. */
  private def emitSqlPreamble(module: Module): Unit =
    val databases = module.manifest.toList.flatMap(_.databases)
    val entries   = databases.map { d =>
      scalascript.sql.js.SqlRuntimeJsEmit.DatabaseEntry(
        name     = d.name,
        url      = d.url,
        user     = d.user,
        password = d.password,
        driver   = d.driver,
      )
    }
    val runtimeSrc = scalascript.sql.js.SqlRuntimeJsEmit.runtimeSource
    // Strip ESM `export` keywords so the names land at script-level
    // scope (works for both ESM and classic-script consumers).  The
    // SqlRuntimeJs namespace below re-exports the public surface for
    // `genSqlBlock`-emitted call sites.
    val stripped   = runtimeSrc.replace("export ", "")
    sb.append(stripped)
    sb.append("\nconst SqlRuntimeJs = { execute, ConnectionRegistry, makeRow, isResultSetProducer, Providers, SqlJsProvider, SqliteWasmProvider, DuckDbWasmProvider };\n")
    sb.append(scalascript.sql.js.SqlRuntimeJsEmit.emitRegistryInit(entries))
    // Synchronous Db facade.  Connections are pre-initialized inside the
    // async IIFE (see genModule) and stored in Db._conns so that route
    // handlers can call Db.query / Db.execute synchronously.  sql.js is
    // itself synchronous once connected; only the initial load is async.
    sb.append("""const Db = {
  _conns: {},
  query(dbName, sql, params) {
    const conn = Db._conns[dbName || 'default'];
    const p = Array.isArray(params) ? params : (params ? [...params] : []);
    if (conn && conn._sscElectronBridge) return conn.querySync(sql, p);
    if (!conn || !conn._db) throw new Error('Db: no connection for "' + (dbName || 'default') + '"');
    const stmt = conn._db.prepare(sql);
    try {
      stmt.bind(p.map(toSqlJsBind));
      if (isResultSetProducer(sql)) {
        const rows = [];
        let columns = null;
        while (stmt.step()) {
          if (columns === null) columns = stmt.getColumnNames();
          rows.push(makeRow(columns, stmt.get().map(fromSqlJsValue)));
        }
        return rows;
      }
      while (stmt.step()) {}
      return conn._db.getRowsModified();
    } finally {
      stmt.free();
    }
  },
  execute(dbName, sql, params) {
    const conn = Db._conns[dbName || 'default'];
    const p = Array.isArray(params) ? params : (params ? [...params] : []);
    if (conn && conn._sscElectronBridge) return conn.executeSync(sql, p);
    return Db.query(dbName, sql, p);
  }
};
""")
    sb.append("\n")

  // ─── v2.0 Phase 2 — capability detection ─────────────────────────────
  //
  // The split-runtime emit uses this to decide which optional preamble
  // blocks (`JsRuntimeAsync`, `JsRuntimeV14Effects`, `JsRuntimeMcp`,
  // `JsRuntimeDataset`) to include in the shared `_runtime.scjs-runtime`
  // artifact.  Heuristic: scan the module's scalascript blocks for
  // textual references to the corresponding effect/DSL namespaces.
  // Conservative — when the heuristic is unsure we include the block;
  // a missing-block false negative would surface as a `ReferenceError`
  // at runtime, far worse than the few KB of extra runtime size.
  def detectCapabilities(module: Module): Set[JsGen.Capability] =
    import JsGen.Capability.*
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    // Run the existing effect analysis to populate `effectOps` /
    // `effectfulFuns`.  `analyzeEffects` seeds `effectOps` with the
    // built-in `Async.*` / `Actor.*` / `Logger.*` / … names so the
    // CPS-emit pipeline recognises them, so we can't rely on the size
    // of that set — only on whether a user-declared `effect E:` block
    // appeared (those names won't be in the builtin seed).
    analyzeEffects(module)
    val caps = scala.collection.mutable.Set.empty[JsGen.Capability]
    caps += Core
    // Collect all parsed scalascript block sources (textual) so we can
    // grep for capability markers without rebuilding the AST traversal.
    def collectSources(s: Section): List[String] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) => cb.source
      } ++ s.subsections.flatMap(collectSources)
    val sources = module.sections.flatMap(collectSources)
    val allText = sources.mkString("\n")
    // True iff the user declared their own `effect E:` block in this
    // module — `EffectAnalysis.analyze(builtins=…)` seeds `effectOps`
    // with the builtin names, so size alone is not a signal.  We use a
    // textual marker: an `effect ` keyword at the start of a line (the
    // language's effect-declaration syntax).
    val userEffectDecl = sources.exists { s =>
      s.linesIterator.exists(l => l.stripLeading.startsWith("effect "))
    }
    // Async / Actor / Storage live in `JsRuntimeAsync`.  `effect E:` blocks
    // also need the Free Monad runtime that ships in `JsRuntimeAsync`.
    // Include bare actor API calls (`runActors`, `spawn`, `receive`, `send`)
    // in addition to the qualified `Actor.*` / `Async.*` namespace markers.
    // SQL blocks and Db.* calls also require the async runtime: sql blocks
    // compile to `await` expressions and `Db.*` intrinsics are async.
    val hasSql = hasSqlBlocks(module) || module.manifest.exists(_.databases.nonEmpty)
    val hasAsync = hasSql ||
                   allText.contains("Db.") ||
                   allText.contains("route(") ||
                   allText.contains("Async.") || allText.contains("Actor.") ||
                   allText.contains("Storage.") || userEffectDecl ||
                   allText.contains("runAsync") || allText.contains("runActors") ||
                   allText.contains("handle(") || allText.contains("spawn {") ||
                   allText.contains("spawn{") || allText.contains("receive {") ||
                   allText.contains("receive{") || allText.contains("receive(") ||
                   allText.contains("_perform") || allText.contains("perform ") ||
                   // v1.23 cluster intrinsics that lower to Actor.* in JsGen —
                   // bare-name calls in user source still need the actor
                   // runtime emitted (without it `Actor` is undefined at run
                   // time).  Listed explicitly so capability detection picks
                   // them up before the Term-level lowering runs.
                   allText.contains("startNode") || allText.contains("connectNode") ||
                   allText.contains("joinCluster") ||
                   // v1.9 coroutines and v1.10 generators — both live in JsRuntimeAsyncB
                   allText.contains("coroutineCreate") || allText.contains("coroutineResume") ||
                   allText.contains("generator {") || allText.contains("generator[") ||
                   allText.contains("_makeGenerator") || allText.contains("fromGenerator")
    // v1.51.2 streams — Source.* / stream {} / runToList / etc. require the async runtime
    // (_makeAsyncStream uses async function*; terminal ops need await).
    val hasStreams = allText.contains("Source.") || allText.contains("stream {") ||
                    allText.contains("runToList") || allText.contains("runForeach") ||
                    allText.contains("runDrain")  || allText.contains("runFold") ||
                    allText.contains("runStream") || allText.contains("Sink.") ||
                    allText.contains("Flow.")
    if hasAsync || hasStreams then caps += Async
    // v1.4 effects: Logger / Random / Clock / Env / Auth — `JsRuntimeV14Effects`.
    // v1.51.6: Stream algebraic effect also lives in JsRuntimeV14Effects (at the end).
    val hasV14 = allText.contains("Logger.") || allText.contains("Random.") ||
                 allText.contains("Clock.")  || allText.contains("Env.")    ||
                 allText.contains("Auth.")   || allText.contains("runLogger") ||
                 allText.contains("runRandom") || allText.contains("runClock") ||
                 allText.contains("runEnv")  || allText.contains("runAuth")  ||
                 allText.contains("Stream.") || allText.contains("runStream")
    if hasV14 then caps += Effects
    // MCP — `JsRuntimeMcp`.
    val hasMcp = allText.contains("mcpServer") || allText.contains("serveMcp") ||
                 allText.contains("mcpConnect")
    if hasMcp then caps += Mcp
    // Dataset[T] — `JsRuntimeDataset`.
    val hasDataset = allText.contains("Dataset.") || allText.contains("Dataset(")
    if hasDataset then caps += Dataset
    // Payment Request — `JsRuntimePayment`.
    val hasPayment = allText.contains("PaymentRequest") || allText.contains("PaymentMethod.")
    if hasPayment then caps += Payment
    // v1.61.6 sub-capabilities
    // HtmlDsl — Part1b: HTTP serve/route/sessions/metrics/password/TOTP
    val hasHtmlDsl = allText.contains("serve(") || allText.contains("route(") ||
                     allText.contains("serveAsync(") ||
                     allText.contains("WsRoom(") || allText.contains(".session") ||
                     allText.contains("metrics.") || module.manifest.exists(_.routes.nonEmpty)
    if hasHtmlDsl then { caps += HtmlDsl; caps += Jwt }  // Part1b uses _bearerFromAuth from Part1c
    // Jwt — Part1c: JwtSign/JwtVerify/OAuth2/CSRF/BearerToken
    val hasJwt = allText.contains("JwtSign(") || allText.contains("JwtVerify(") ||
                 allText.contains("OAuth2.") || allText.contains("bearerToken") ||
                 allText.contains("csrf")
    if hasJwt then caps += Jwt
    // WsServer — Part1d: WebSocket connections, SSE, CORS, outbound HTTP client
    val hasWsServer = allText.contains("WsConnection(") || allText.contains("WsRoom(") ||
                      allText.contains("serveAsync(") ||
                      allText.contains("sse(") || allText.contains("cors(") ||
                      allText.contains("httpGet(") || allText.contains("httpPost(") ||
                      allText.contains("httpPut(") || allText.contains("httpPatch(") ||
                      allText.contains("httpDelete(") || allText.contains("httpClient(")
    if hasWsServer then caps += WsServer
    // Optics — Lens/Optional/Traversal/Prism
    val hasOptics = allText.contains("Lens(") || allText.contains("Optional(") ||
                    allText.contains("Prism(") || allText.contains("Prism[") ||
                    allText.contains("Traversal(") || allText.contains(".focus") ||
                    allText.contains("Focus[") || allText.contains("_makeLens") ||
                    allText.contains("indexAt")
    if hasOptics then caps += Optics
    // Signals — reactive signals and the lightweight browser UI helpers.
    // UI/DataTable constructors are emitted as `_ssc_ui_*` extern shims from
    // std/ui modules, so they need the Signals runtime even without an explicit
    // user-level `signal(...)` call.
    val hasUiHelpers = List(
      "fetchUrlSignal", "fetchAction", "fetchActionClear", "emptyHeaders",
      "dataTable", "dataTableView", "staticDataTable", "signalDataTable",
      "fieldColumn", "dateColumn", "moneyColumn", "statusColumn", "linkColumn",
      "fcol", "dcol", "mcol", "scol", "lcol",
      "rowDelete", "rowPost", "rowLink", "rowEdit",
      "textNode", "signalText", "showSignal", "fragment("
    ).exists(allText.contains)
    val hasSignals = allText.contains("signal(") || allText.contains("Signal(") ||
                    allText.contains("computed(") || allText.contains("Computed(") ||
                    hasUiHelpers
    if hasSignals then caps += Signals
    // IndexedDb — client-side IndexedDB storage
    val hasIndexedDb = allText.contains("IndexedDb.")
    if hasIndexedDb then caps += IndexedDb
    // GraphQL — `JsRuntimeGraphql`.  Triggered by a `graphql` fenced block or
    // by any of the server/client intrinsics.  The runtime mounts on the full
    // HTTP server stack, which spans `route` (Part1b/HtmlDsl), `_mkRequest`'s
    // auth helpers `_bearerFromAuth` / `jwtVerify` (Part1c/Jwt), and `serve` /
    // `_ssc_http_serve` (Part1d/WsServer); resolvers run async.  Force all of
    // HtmlDsl + Jwt + WsServer + Async whenever GraphQL is used.
    def hasGraphqlBlock(s: Section): Boolean =
      s.content.exists { case cb: Content.CodeBlock => Lang.isGraphql(cb.lang); case _ => false } ||
      s.subsections.exists(hasGraphqlBlock)
    val hasGraphql = module.sections.exists(hasGraphqlBlock) ||
                     allText.contains("GraphQL.") || allText.contains("serveGraphQL") ||
                     allText.contains("graphqlMount") || allText.contains("graphqlHandler") ||
                     allText.contains("graphqlQuery") || allText.contains("graphqlSse") ||
                     allText.contains("graphqlSubscribe") || allText.contains("serveSubgraph") ||
                     allText.contains("graphqlSubgraphMount")
    if hasGraphql then { caps += Graphql; caps += HtmlDsl; caps += Jwt; caps += WsServer; caps += Async }
    caps.toSet

  /** Emit `route(method, path)(handler)` registrations for every
   *  `routes:` entry in the module's front-matter. */
  private def emitFrontmatterRoutes(module: Module): Unit =
    module.manifest.toList.flatMap(_.routes).foreach { r =>
      val m = jsQuote(r.method)
      val p = jsQuote(r.path)
      line(s"route($m, $p)(${r.handler});")
    }

  /** Emit `_i18nTable = { ... }` from the module's front-matter translations. */
  private def emitI18nTable(module: Module): Unit =
    module.manifest.foreach { m =>
      if m.translations.nonEmpty then
        val entries = m.translations.map { (locale, kvs) =>
          val pairs = kvs.map { (k, v) => s"${jsQuote(k)}: ${jsQuote(v)}" }.mkString(", ")
          s"${jsQuote(locale)}: {$pairs}"
        }.mkString(", ")
        line(s"_i18nTable = {$entries};")
    }

  private val endpointPrimitives = Set("Int", "Long", "String", "Boolean", "Double", "Float")

  private def pathParamNames(path: String): List[String] =
    path.split("/").toList.collect { case seg if seg.startsWith(":") => seg.drop(1) }

  private def caseClassFieldsInModule(module: Module): Map[String, List[String]] =
    val result = scala.collection.mutable.Map.empty[String, List[String]]
    def scanStats(stats: List[scala.meta.Stat]): Unit = stats.foreach {
      case d: Defn.Class if d.mods.exists(_.isInstanceOf[Mod.Case]) =>
        result(d.name.value) = d.ctor.paramClauses.flatMap(_.values).map(_.name.value).toList
      case _ => ()
    }
    def scanSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node => ScalaNode.fold(node) {
            case Source(stats)     => scanStats(stats); ()
            case Term.Block(stats) => scanStats(stats); ()
            case _                 => ()
          }}
        case _ => ()
      }
      s.subsections.foreach(scanSection)
    module.sections.foreach(scanSection)
    result.toMap

  private def caseClassFieldTypesInModule(module: Module): Map[String, Map[String, String]] =
    val result = scala.collection.mutable.Map.empty[String, Map[String, String]]
    def scanStats(stats: List[scala.meta.Stat]): Unit = stats.foreach {
      case d: Defn.Class if d.mods.exists(_.isInstanceOf[Mod.Case]) =>
        val fields = d.ctor.paramClauses.flatMap(_.values).flatMap { pv =>
          pv.decltpe.collect { case Type.Name(t) => pv.name.value -> t }
        }.toMap
        result(d.name.value) = fields
      case _ => ()
    }
    def scanSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node => ScalaNode.fold(node) {
            case Source(stats)     => scanStats(stats); ()
            case Term.Block(stats) => scanStats(stats); ()
            case _                 => ()
          }}
        case _ => ()
      }
      s.subsections.foreach(scanSection)
    module.sections.foreach(scanSection)
    result.toMap

  private def endpointPathWarnings(
    clientName: String,
    ep: ApiEndpointDecl,
    classFields: Map[String, List[String]]
  ): List[String] =
    val params = pathParamNames(ep.path)
    if params.isEmpty then Nil
    else ep.requestType match
      case "Unit" =>
        params.map(p => s"apiClient $clientName.${ep.name}: path param ':$p' cannot be filled — request type is Unit")
      case prim if endpointPrimitives.contains(prim) =>
        if params.size > 1 then
          List(s"apiClient $clientName.${ep.name}: ${params.size} path params but request type '$prim' provides at most one value")
        else Nil
      case typeName =>
        classFields.get(typeName) match
          case Some(fields) =>
            params.filterNot(fields.contains).map { p =>
              s"apiClient $clientName.${ep.name}: path param ':$p' not found in case class '$typeName' (fields: ${fields.mkString(", ")})"
            }
          case None => Nil

  /** Emit browser/Electron HTTP clients declared by front-matter
   *  `apiClients:`. The generated methods intentionally return Promises:
   *  browser `fetch` is asynchronous, and SPA/Electron client-only bundles
   *  can route relative URLs to a JVM backend through
   *  `globalThis.__sscBackendBaseUrl`.
   */
  private def emitHttpTypedRouteClients(clients: List[ApiClientDecl], module: Module): Unit =
    val classFields = caseClassFieldsInModule(module)
    val warnings = clients.flatMap(c => c.endpoints.flatMap(e => endpointPathWarnings(c.name, e, classFields)))
    warnings.foreach { w =>
      System.err.println(s"[ssc warning] $w")
      line(s"// [ssc warning] $w")
    }
    val endpoints = clients.flatMap(client => client.endpoints.map(endpoint => client.name -> endpoint))
    if endpoints.nonEmpty then
      line("const _ssc_typedRouteClients = [")
      indent += 1
      endpoints.zipWithIndex.foreach { case ((client, endpoint), idx) =>
        val comma = if idx == endpoints.size - 1 then "" else ","
        line(
          "{client: " + jsQuote(client) +
            ", name: " + jsQuote(endpoint.name) +
            ", method: " + jsQuote(endpoint.method) +
            ", path: " + jsQuote(endpoint.path) +
            ", requestType: " + jsQuote(endpoint.requestType) +
            ", responseType: " + jsQuote(endpoint.responseType) +
            "}" + comma
        )
      }
      indent -= 1
      line("];")
      sb.append(httpTypedRouteClientRuntime)
      clients.foreach { client =>
        if client.endpoints.nonEmpty then
          line(s"const ${client.name} = {")
          indent += 1
          val allMethodLines = client.endpoints.flatMap { endpoint =>
            val method = jsQuote(endpoint.method)
            val path = jsQuote(endpoint.path)
            val requestType = jsQuote(endpoint.requestType)
            val responseType = jsQuote(endpoint.responseType)
            if ApiEndpointDecl.isWs(endpoint) then
              if endpoint.requestType == "Unit" then
                List(s"${endpoint.name}(onEvent, onError, onOpen, headers) { return _ssc_api_ws_request($path, undefined, onEvent, onError, onOpen, $responseType, headers); }")
              else
                List(s"${endpoint.name}(input, onEvent, onError, onOpen, headers) { return _ssc_api_ws_request($path, input, onEvent, onError, onOpen, $responseType, headers); }")
            else if ApiEndpointDecl.isSse(endpoint) then
              if endpoint.requestType == "Unit" then
                List(s"${endpoint.name}(onEvent, onError, headers) { return _ssc_api_stream_request($method, $path, undefined, onEvent, onError, $responseType, headers); }")
              else
                List(s"${endpoint.name}(input, onEvent, onError, headers) { return _ssc_api_stream_request($method, $path, input, onEvent, onError, $responseType, headers); }")
            else if endpoint.requestType == "Unit" then
              val base = s"${endpoint.name}(headers, cancelToken) { return _ssc_api_request($method, $path, undefined, $requestType, $responseType, headers, cancelToken); }"
              if endpoint.paginated then
                val pagedPath = s"""$path + "?page=" + page + "&size=" + size"""
                val paged = s"${endpoint.name}Paged(page, size, headers, cancelToken) { return _ssc_api_request($method, $pagedPath, undefined, $requestType, $responseType, headers, cancelToken); }"
                List(base, paged)
              else List(base)
            else
              val base = s"${endpoint.name}(input, headers, cancelToken) { return _ssc_api_request($method, $path, input, $requestType, $responseType, headers, cancelToken); }"
              if endpoint.paginated then
                val pagedPath = s"""$path + "?page=" + page + "&size=" + size"""
                val paged = s"${endpoint.name}Paged(input, page, size, headers, cancelToken) { return _ssc_api_request($method, $pagedPath, input, $requestType, $responseType, headers, cancelToken); }"
                List(base, paged)
              else List(base)
          }
          allMethodLines.zipWithIndex.foreach { case (mline, idx) =>
            val comma = if idx == allMethodLines.size - 1 then "" else ","
            line(s"$mline$comma")
          }
          indent -= 1
          line("};")
      }

  private val httpTypedRouteClientRuntime: String = JsRuntimeHttpClient.source

  private def jsTypedJsonRegisterProduct(typeName: String, fields: Seq[String], ctorName: String): String =
    jsTypedJsonRegisterProduct(typeName, fields, Some(ctorName))

  private def jsTypedJsonRegisterProduct(typeName: String, fields: Seq[String], ctorName: Option[String]): String =
    val fieldArray = fields.map(jsQuote).mkString("[", ", ", "]")
    val ctor = ctorName.getOrElse("undefined")
    s"""if (typeof _ssc_typed_json_register_product === "function") _ssc_typed_json_register_product(${jsQuote(typeName)}, $fieldArray, $ctor);"""

  // ─── Effect analysis ─────────────────────────────────────────────
  //
  // Walks the module to:
  //   (1) collect effect operation names: `effect Eff: def op(...) = __effectOp__`
  //       contributes the string "Eff.op" to effectOps.
  //   (2) determine the set of functions that may transitively perform effects.
  //       A function is effectful if its body calls an effect op or another
  //       effectful function. Iterate to a fixed point.
  //
  // Effectful functions are emitted in CPS form (returning a Free value).
  // Pure functions stay direct — plain values double as Pure(value), so they
  // compose with the Free Monad runtime without any wrapping.

  private def analyzeEffects(module: Module): Unit =
    val builtins = Set(
      "Async.delay", "Async.async", "Async.await", "Async.parallel", "Async.recvFrom",
      "Storage.get", "Storage.put", "Storage.remove", "Storage.has", "Storage.keys",
      "Actor.spawn", "Actor.spawn_link", "Actor.self", "Actor.send", "Actor.exit",
      "Actor.receive", "Actor.receive_t",
      "Actor.link", "Actor.monitor", "Actor.demonitor", "Actor.trapExit",
      "Actor.startNode", "Actor.connectNode", "Actor.joinCluster", "Actor.register", "Actor.whereis",
      "Actor.globalRegister", "Actor.globalWhereis",
      "Actor.clusterMembers", "Actor.subscribeClusterEvents",
      "Actor.phiOf", "Actor.isSuspect",
      "Actor.selfNode", "Actor.clusterHealth",
      "Actor.broadcastHealth", "Actor.clusterIsDown",
      "Actor.electLeader", "Actor.currentLeader", "Actor.subscribeLeaderEvents",
      "Actor.setAutoReelect",
      "Actor.useRaftLeaderElection", "Actor.useExternalCoordinator",
      "Actor.leaderProtocol", "Actor.leaderHistory",
      "Actor.setReconnectPolicy", "Actor.requestGossip",
      "Actor.clusterConfigSet", "Actor.clusterConfigGet",
      "Actor.clusterConfigKeys", "Actor.subscribeConfigEvents",
      "Actor.setDraining", "Actor.isDraining",
      "Actor.drainingPeers", "Actor.subscribeDrainEvents",
      "Actor.clusterMetricSet", "Actor.clusterMetricGet",
      "Actor.clusterMetricSum", "Actor.clusterMetricNames",
      "Actor.subscribeMetricEvents",
      "Actor.sendAfter", "Actor.sendInterval", "Actor.cancelTimer",
      "Logger.info", "Logger.warn", "Logger.error", "Logger.debug",
      "Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick",
      "Clock.now", "Clock.nowIso", "Clock.sleep",
      "Env.get", "Env.set", "Env.required"
    )

    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)

    val trees = module.sections.flatMap(collectTrees)
    val r     = EffectAnalysis.analyze(trees, builtins)

    effectOps.clear();        effectOps        ++= r.effectOps
    effectfulFuns.clear();    effectfulFuns    ++= r.effectfulFuns
    multiShotEffects.clear(); multiShotEffects ++= r.multiShotEffects

  /** Walk the module AST and set `usesRunAsyncParallel` if any `runAsyncParallel` call
   *  is present.  Called from `genModule` before emitting user code sections so the
   *  IIFE wrapper and `await` prefix can be applied consistently. */
  private def scanForRunAsyncParallel(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    usesRunAsyncParallel = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("runAsyncParallel"), _) => ()
      }.nonEmpty
    }

  /** Walk the module AST and set `usesRunActors` if any `runActors` call is
   *  present.  Mirrors `scanForRunAsyncParallel` — sister flag that also feeds
   *  the async-IIFE wrap decision in `genModule` and toggles the `await`
   *  prefix on `_runActors(...)` callsites.  The async-aware scheduler yields
   *  to Node's event loop between ticks (see runtime emission), which is
   *  what unblocks `serveAsync(...)` bound from inside `runActors { ... }`. */
  private def scanForRunActors(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    usesRunActors = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("runActors"), _) => ()
      }.nonEmpty
    }

  /** Walk client-side ScalaScript blocks and detect `awaitClient(promise)`.
   *  Server-only blocks are skipped because JS targets do not emit them.
   */
  private def scanForAwaitClient(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock
            if Lang.isScalaScript(cb.lang) && !cb.attrs.get("side").contains("server") =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    usesAwaitClient = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("awaitClient"), _) => ()
      }.nonEmpty
    }

  private def scanForStreams(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    val terminalNames = Set("runForeach", "runToList", "runDrain", "runFold")
    usesStreams = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Select(_, scala.meta.Term.Name(m)), _)
            if terminalNames.contains(m) => ()
        // v1.51.6: runStream { body } also requires the streams preamble
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("runStream"), _) => ()
      }.nonEmpty
    }


  /** Walk the module in document order, grouping consecutive same-type blocks into
   *  Segment values.  ScalaScript blocks are transpiled to JS; scala blocks are
   *  collected as raw Scala source for later Scala.js compilation.
   */
  def genModuleSegmented(module: Module): List[JsGen.Segment] =
    sb.clear()
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    collectFuncParamOrders(module)
    analyzeMutualRecursion(module)
    analyzeEffects(module)
    scanForRunAsyncParallel(module)
    scanForRunActors(module)
    scanForAwaitClient(module)
    scanForStreams(module)
    // Emit `route(...)` registrations from front-matter before user blocks,
    // so a typical user-side `serve(port)` (last statement of the script)
    // sees them already registered.  JS function declarations are hoisted,
    // so forward references to handler defs resolve at call time.
    emitFrontmatterRoutes(module)
    emitI18nTable(module)
    emitHttpTypedRouteClients(module.manifest.toList.flatMap(_.apiClients), module)
    val result    = mutable.ListBuffer[JsGen.Segment]()
    val scalaBuf  = mutable.ListBuffer[String]()
    var ssStart   = 0

    def flushSS(): Unit =
      val code = sb.substring(ssStart)
      if code.trim.nonEmpty then result += JsGen.Segment.ScalaScriptJs(code)
      ssStart = sb.length

    def flushScala(): Unit =
      if scalaBuf.nonEmpty then
        result += JsGen.Segment.ScalaSource(scalaBuf.mkString("\n\n"))
        scalaBuf.clear()

    def walkSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) && cb.attrs.get("side").contains("server") =>
          ()
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          flushScala()
          cb.tree.foreach(genScalaNode)
        case Content.CodeBlock(lang, src, _, _, _, _, _) if Lang.isStandardScala(lang) =>
          flushSS()
          scalaBuf += src.stripTrailing()
        case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
          flushScala()
          genStringBlock(cb, s)
        case cb: Content.CodeBlock if Lang.isGraphql(cb.lang) =>
          flushScala()
          genGraphqlBlock(cb)
        case imp: Content.Import =>
          flushScala()
          genImport(imp)
        case _ => ()
      }
      s.subsections.foreach(walkSection)

    val needsAsyncSeg = usesRunAsyncParallel || usesRunActors || usesAwaitClient
    if needsAsyncSeg then
      sb.append("(async () => {\n")
      // See `genModule` for the rationale — install a write-through
      // `_println` (and rebind `Console.println` which captures the
      // original `_println` by reference) inside the IIFE so prints
      // from after the first `await` reach stdout instead of being
      // buffered into a `_output` array the outer segment-end flush
      // no longer sees.
      sb.append("if (typeof process !== 'undefined' && process.stdout && typeof _output !== 'undefined') {\n")
      sb.append("  _println = function(...args) { process.stdout.write(args.map(_show).join(' ') + '\\n'); };\n")
      sb.append("  _print   = function(...args) { process.stdout.write(args.map(_show).join(''));         };\n")
      sb.append("  if (typeof Console !== 'undefined') {\n")
      sb.append("    Console.println = _println;\n")
      sb.append("    Console.print   = _print;\n")
      sb.append("  }\n")
      sb.append("}\n")
    module.sections.foreach(walkSection)
    flushScala()
    if hasMain && !mainCalled then
      sb.append("if (typeof main === 'function') { main(); }\n")
    if needsAsyncSeg then
      sb.append("})().catch(e => {\n")
      sb.append("  const msg = String(e && e.stack ? e.stack : e);\n")
      sb.append("  if (typeof process !== 'undefined' && process.stderr) { process.stderr.write(msg + '\\n'); process.exit(1); }\n")
      sb.append("  else if (typeof document !== 'undefined') { document.body.textContent = msg; }\n")
      sb.append("  else { console.error(msg); }\n")
      sb.append("});\n")
    val finalCode = sb.substring(ssStart)
    if finalCode.trim.nonEmpty then result += JsGen.Segment.ScalaScriptJs(finalCode)
    result.toList

  private[codegen] def genSection(section: Section): Unit =
    section.content.foreach {
      case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) && cb.attrs.get("side").contains("server") =>
        ()
      case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
        cb.tree.foreach(genScalaNode)
      case cb: Content.CodeBlock if Lang.isStandardScala(cb.lang) =>
        line(s"/* scala: standard Scala 3 block — compile via Scala.js for JS execution */")
      case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
        genStringBlock(cb, section)
      case cb: Content.CodeBlock if Lang.isSql(cb.lang) =>
        genSqlBlock(cb, section)
      case cb: Content.CodeBlock if Lang.isGraphql(cb.lang) =>
        genGraphqlBlock(cb)
      case imp: Content.Import =>
        genImport(imp)
      case _ => ()
    }
    section.subsections.foreach(genSection)

  /** Emit a `graphql` fenced block as a `_registerGraphqlSdl(<sdl>)` call.
   *  The SDL string is stashed in the runtime so `graphqlMount` /
   *  `serveGraphQL` can build the schema later (mirrors the interpreter's
   *  `GraphQLJvmBlockRunner.registerSdl`). */
  private def genGraphqlBlock(cb: Content.CodeBlock): Unit =
    line(s"_registerGraphqlSdl(${jsStringLit(cb.source.stripTrailing)});")

  /** v1.27 Phase 3 — emit a `sql` fenced block as a `_sqlBlock_<n>`
   *  `const` initialised by `await SqlRuntimeJs.execute(...)`.
   *
   *  Mirrors `JvmGen.sqlBlockToScala` but emits JS:
   *    - `${expr}` interpolations have already been lifted to a `?`-
   *      template + ordered bind list by `SqlBindRewriter.rewriteJdbc`.
   *      Each bind text is parsed back as a Scala term and emitted as
   *      JS via the existing `genExpr` machinery, so the bind value
   *      evaluates in the surrounding scope at runtime.
   *    - Connection resolution funnels through the module-scope
   *      `_ssc_sql_resolve(dbName)` helper emitted once by
   *      `emitSqlPreamble`.
   *    - First sql block in each section also lands as
   *      `const <sectionId> = { ..., sql: _sqlBlock_<n> }` — the
   *      friendly `<sectionId>.sql` alias matches JvmGen's Phase
   *      6.C / Spark Phase C.2 convention.  Subsequent sql blocks in
   *      the same section skip the alias (the first-only book-keeping
   *      in `sqlPerSection` enforces this). */
  private def genSqlBlock(cb: Content.CodeBlock, section: Section): Unit =
    // v1.30 Phase 4 — @side=server blocks are server-only; skip in JS targets.
    if cb.attrs.get("side").contains("server") then return
    val n = sqlBlockCounter
    sqlBlockCounter += 1
    val rewrite = scalascript.transform.SqlBindRewriter.rewriteJdbc(cb.source)
    val sqlLit  = jsStringLit(rewrite.sql)
    val bindsJs = rewrite.binds.map(bindExprToJs).mkString(", ")
    val dbArg   = cb.attrs.get("db") match
      case Some(name) => jsStringLit(name)
      case None       => "undefined"
    val valName = s"_sqlBlock_$n"
    line(s"const $valName = await SqlRuntimeJs.execute(await _ssc_sql_resolve($dbArg), $sqlLit, [$bindsJs]);")
    sectionIdent(section.heading.text).foreach { id =>
      val prior = sqlPerSection.getOrElse(id, 0)
      sqlPerSection(id) = prior + 1
      if prior == 0 then
        line(s"if (typeof $id === 'undefined') var $id = {};")
        line(s"$id.sql = $valName;")
    }

  /** Parse a single bind-expression text (Scala source from inside
   *  `${...}`) back to a `Term` and emit it as JS.  Falls back to
   *  splicing the raw source verbatim when scala.meta can't parse it
   *  (defensive — the parser already rejected malformed source upstream,
   *  but never trust the boundary). */
  private def bindExprToJs(exprSrc: String): String =
    val trimmed = exprSrc.trim
    val parsed  =
      try
        Some(scala.meta.dialects.Scala3(scala.meta.Input.String(trimmed)).parse[scala.meta.Term].toOption).flatten
      catch case _: Throwable => None
    parsed match
      case Some(t) => genExpr(t)
      case None    => trimmed   // last-resort fallback

  /** Detect whether the module has any sql blocks — drives preamble
   *  emission + async wrap.  Walks sections recursively. */
  private def hasSqlBlocks(module: Module): Boolean =
    def go(s: Section): Boolean =
      s.content.exists {
        // v1.30 — @side=server blocks are server-only; don't count them as
        // requiring the SQL preamble in a JS-family bundle.
        case cb: Content.CodeBlock =>
          Lang.isSql(cb.lang) && !cb.attrs.get("side").contains("server")
        case _ => false
      } || s.subsections.exists(go)
    module.sections.exists(go)

  /** Emit a heading-bound html / css block: render the source as a JS
   *  template literal (using `_html_interp` for html), assign to
   *  `<sectionIdent>.<lang>`. */
  private def genStringBlock(cb: Content.CodeBlock, section: Section): Unit =
    sectionIdent(section.heading.text).foreach { id =>
      val rendered = stringBlockTemplate(cb.source, cb.lang == Lang.Html)
      // Use `var` so multiple kinds of block in one section (html + css)
      // can share a single object literal without each invocation
      // clobbering the previous.
      line(s"if (typeof $id === 'undefined') var $id = {};")
      line(s"$id.${cb.lang} = $rendered;")
    }

  /** Mirror Interpreter.sectionIdent: camelCase alphanumeric runs, preserve
   *  the first word's casing; None when the heading is all punctuation. */
  private def sectionIdent(text: String): Option[String] =
    val parts = text.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val head = parts.head
      val tail = parts.tail.map(p => s"${p.head.toUpper}${p.tail}")
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  private def genImport(imp: Content.Import): Unit =
    import scalascript.parser.Parser
    val base = baseDir.getOrElse(os.pwd)
    val initiallyResolved =
      try scalascript.imports.ImportResolver.resolve(imp.path, base, moduleDeps, lockPath)
      catch case _: Throwable => base / os.RelPath(imp.path)
    val resolvedPath =
      if os.exists(initiallyResolved) then initiallyResolved
      else resolveStdImportFromProjectTree(imp.path, base).getOrElse(initiallyResolved)
    if !os.exists(resolvedPath) then return
    val key         = resolvedPath.toString
    val childModule = Parser.parse(os.read(resolvedPath))
    if !importedFiles.contains(key) then
      importedFiles += key
      val childDir = resolvedPath / os.up
      val childGen = new JsGen(Some(childDir), lockPath = lockPath, topLevelConsts = topLevelConsts, mergeHelperEmitted = mergeHelperEmitted, declaredBindings = declaredBindings)
      childGen.importedFiles ++= importedFiles
      // Emit only the definitions from the imported module (suppress top-level output)
      childModule.sections.foreach { section =>
        section.content.foreach {
          case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
            cb.tree.foreach(childGen.genScalaNode)
          case nestedImp: Content.Import =>
            // Propagate transitive imports — e.g., std/selective.ssc
            // pulls in std/either.ssc, and consumers of selective need
            // Either's constructors emitted too.
            childGen.genImport(nestedImp)
          case _ => ()
        }
        section.subsections.foreach(childGen.genSection)
      }
      sb.append(childGen.sb)
      // Pull cycle-protection state back so siblings don't re-import
      importedFiles ++= childGen.importedFiles
    // Always extract bindings, even when this module was already imported by
    // a transitive dependency.  Cycle-protection only guards code emission —
    // each explicit import still needs its `const x = pkg.x;` binding.
    // `declaredBindings` prevents duplicate `const` declarations when the same
    // symbol is extracted more than once (e.g. TkNode from nodes.ssc imported
    // by both primitives.ssc and layout.ssc).
    val childPkg     = childModule.manifest.flatMap(_.pkg).getOrElse(Nil)
    val pkgPrefix    = if childPkg.isEmpty then "" else childPkg.mkString("", ".", ".")
    val childExports = childModule.manifest.map(_.exports).getOrElse(Nil)
    imp.bindings.foreach { b =>
      val fullName  = s"$pkgPrefix${b.name}"
      val localName = b.alias.getOrElse(b.name)
      // If the child module declares an exports list and this name is absent,
      // skip — don't block a later import from the correct module.
      val notExported = childExports.nonEmpty && !childExports.contains(b.name)
      if fullName != localName && !notExported && !declaredBindings.contains(localName) then
        declaredBindings += localName
        line(s"const $localName = $fullName;")
    }

  private def resolveStdImportFromProjectTree(rawPath: String, base: os.Path): Option[os.Path] =
    if !rawPath.startsWith("std/") then None
    else
      val rel = os.RelPath(rawPath)
      var cur = base
      while true do
        val runtimeStd = cur / "runtime" / rel
        if os.exists(runtimeStd) then return Some(runtimeStd)
        val installedStd = cur / rel
        if os.exists(installedStd) then return Some(installedStd)
        val parent = cur / os.up
        if parent == cur then return None
        cur = parent
      None

  private[codegen] def genScalaNode(node: ScalaNode): Unit =
    ScalaNode.fold(node) {
      case Source(stats) => genBlockStats(stats, topLevel = true)
      case t: Term.Block => genBlockStats(t.stats, topLevel = true)
      case t: Term       => line(genExpr(t) + ";")
      case _             => ()
    }

  /** Returns true if the declaration should be emitted at the top level.
   *  When tree-shaking is active (`reachableNames.isDefined`), named
   *  declarations that are not in the reachable set are suppressed. */
  private def isReachableStat(stat: Stat, topLevel: Boolean): Boolean =
    if !topLevel then return true          // inner-scope stats: never filtered
    reachableNames match
      case None       => true              // tree-shaking off: emit everything
      case Some(reach) =>
        stat match
          case d: Defn.Def     => reach.contains(d.name.value)
          case Defn.Val(_, List(Pat.Var(n)), _, _) =>
            reach.contains(n.value)
          case _: Defn.Val     => true     // multi-pat: conservative keep
          case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, _) =>
            reach.contains(n.value)
          case _: Defn.Var     => true     // multi-pat or other var: conservative keep
          case d: Defn.Class   => reach.contains(d.name.value)
          case d: Defn.Object  => reach.contains(d.name.value)
          case d: Defn.Enum    => reach.contains(d.name.value)
          case _: Defn.Given   =>
            // All givens register in _ssc_givens (a global side-effect) → always emit
            true
          case _: Defn.Trait   => true     // erased anyway; never filtered
          case _: Term         => true     // top-level term/side effect: always keep
          case _               => true     // conservative: keep unknown node kinds

  private def genBlockStats(stats: List[Stat], topLevel: Boolean): Unit =
    stats.zipWithIndex.foreach { (s, i) =>
      val isLast = i == stats.length - 1
      // Skip unreachable top-level declarations when tree-shaking is active
      if !isReachableStat(s, topLevel) then ()
      else
      s match
        case tw: Term.While =>
          val savedBuf = loopHoistBuf
          val newBuf   = mutable.Buffer.empty[(String, String)]
          loopHoistBuf = newBuf
          val bodyJs = genExpr(tw.body)
          val condJs = genExpr(tw.expr)
          loopHoistBuf = savedBuf
          newBuf.foreach { (k, v) => line(s"const $k = $v;") }
          line(s"while ($condJs) { $bodyJs; }")
        case t: Term if isLast && topLevel =>
          // Track main() calls; auto-output non-unit last expression
          t match
            case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
            case _ => ()
          val expr = genExpr(t)
          line(s"{ const _auto = $expr; if (_auto !== undefined && !(_auto === null)) _println(_show(_auto)); }")
        case t: Term =>
          t match
            case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
            case _ => ()
          line(genExpr(t) + ";")
        case stat =>
          stat match
            case d: Defn.Object if topLevel =>
              val name = d.name.value
              if topLevelConsts.contains(name) then
                emitMergeHelper()
                line(s"_ssc_mergeDeep($name, ${genObjectAsExpr(d)});")
              else
                topLevelConsts += name
                genStat(stat)
            case _ =>
              genStat(stat)
    }

  private def emitMergeHelper(): Unit =
    if !mergeHelperEmitted(0) then
      mergeHelperEmitted(0) = true
      line("function _ssc_mergeDeep(dst, src) { for (const k of Object.keys(src)) { if (dst[k] !== null && typeof dst[k] === 'object' && typeof src[k] === 'object') _ssc_mergeDeep(dst[k], src[k]); else dst[k] = src[k]; } }")

  private def isStreamTerminalStat(t: Term): Boolean = t match
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("runForeach" | "runToList" | "runDrain")), _) => true
    case Term.Apply.After_4_6_0(Term.Apply.After_4_6_0(Term.Select(_, Term.Name("runFold")), _), _)   => true
    case _ => false

  /** arch-ffi-p1 — extract the first string literal arg from `@name("expr")` in `mods`. */
  private def extractAnnotationArg(mods: List[Mod], name: String): Option[String] =
    mods.collectFirst {
      case Mod.Annot(init) if (init.tpe match
        case Type.Name(n)                 => n == name
        case Type.Select(_, Type.Name(n)) => n == name
        case _                            => false) =>
          init.argClauses.headOption.flatMap(_.values.collectFirst { case Lit.String(s) => s })
    }.flatten

  /** Substitute `$0`, `$1`, … with the corresponding param names. */
  private def substituteJsArgs(expr: String, params: List[String]): String =
    params.zipWithIndex.foldLeft(expr) { case (e, (n, i)) => e.replace(s"$$$i", n) }

  private def genStat(stat: Stat): Unit = stat match
    case Defn.Val(_, pats, _, rhs) =>
      pats match
        case List(Pat.Var(n)) =>
          if isIntExpr(rhs) then intVars += n.value
          else if isNumericExpr(rhs) then numericVars += n.value
          line(s"const ${n.value} = ${genExpr(rhs)};")
        case List(pat) =>
          // Tuple/pattern destructuring
          val patJs = genPatDestructure(pat)
          line(s"const $patJs = ${genExpr(rhs)};")
        case _ =>
          line(s"/* multi-pat val */")

    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      if isIntExpr(rhs) then intVars += n.value
      else if isNumericExpr(rhs) then numericVars += n.value
      line(s"let ${n.value} = ${genExpr(rhs)};")

    // arch-ffi-p1 — @js("expr") / @jvm-only handling for extern defs.
    // @js("expr")     → emit a JS function with the inline expression body.
    // @jvm + no @js   → emit a stub that throws a clear runtime error.
    // no annotation   → skip (intrinsic table handles it at call sites).
    case d: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(d.body) =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value).toList
      extractAnnotationArg(d.mods, "js") match
        case Some(jsExpr) =>
          val body     = substituteJsArgs(jsExpr, params)
          val paramsStr = params.mkString(", ")
          line(s"function ${d.name.value}($paramsStr) { return $body; }")
        case None =>
          if extractAnnotationArg(d.mods, "jvm").isDefined then
            // @jvm-only: provide a stub that throws at runtime instead of a silent undefined
            val paramsStr = params.mkString(", ")
            line(s"function ${d.name.value}($paramsStr) { throw new Error('${d.name.value} is @jvm-only and cannot be called from the JS backend.'); }")

    case d: Defn.Def =>
      val paramVals   = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
      val params      = paramVals.map(_.name.value)
      val hasDefaults = paramVals.exists(_.default.isDefined)
      val fname       = d.name.value
      val defRenames  = params.collect { case p if jsReservedWords.contains(p) => p -> safeJsParam(p) }.toMap
      // `using` params: explicit implicit parameter clauses `(using x: TC[T])`.
      // Emit guards to resolve them at runtime via _resolveGiven + _ssc_typeOf on a hint param.
      val nonUsingParams = d.paramClauseGroups.flatMap(_.paramClauses).filterNot { pc =>
        pc.mod match { case Some(_: scala.meta.Mod.Using) => true; case _ => false }
      }.flatMap(_.values)
      val usingGuards: List[String] = d.paramClauseGroups.flatMap(_.paramClauses).filter { pc =>
        pc.mod match { case Some(_: scala.meta.Mod.Using) => true; case _ => false }
      }.flatMap(_.values).flatMap { pv =>
        val pname = pv.name.value
        pv.decltpe.toList.flatMap { tpe =>
          val guardOpt: Option[String] = tpe match
            case Type.Apply.After_4_6_0(Type.Name(tc), args) =>
              // Find a hint param whose type matches the type argument of TC[A]
              val typeParamName = args.values match
                case List(Type.Name(n)) => n
                case _                  => ""
              // A concrete type: starts uppercase with >1 char (Option, List, Int...).
              // For concrete type args, use literal key; for type vars (A, F, T...) use runtime.
              def isConcrete(n: String): Boolean = n.length > 1 && n.head.isUpper
              val matchingParam = if typeParamName.nonEmpty then
                nonUsingParams.find { p =>
                  p.decltpe.exists {
                    case Type.Name(n) => n == typeParamName
                    case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n == typeParamName; case _ => false }
                    case _ => false
                  }
                }
              else None
              if matchingParam.isDefined then
                // Type variable matched a non-using param → derive TC arg at runtime
                val hint = matchingParam.get.name.value
                Some(s"""if ($pname === undefined) $pname = _resolveGiven("${tc}_" + _ssc_typeOf($hint));""")
              else if typeParamName.nonEmpty && isConcrete(typeParamName) then
                // Concrete type argument (e.g. Console[Option]) → use literal key
                Some(s"""if ($pname === undefined) $pname = _ssc_givens["${tc}_${typeParamName}"] ?? _resolveGiven("${tc}_${typeParamName}");""")
              else
                // Type variable without matching param → best-effort with first non-using param
                val hint = nonUsingParams.headOption.map(_.name.value).getOrElse("undefined")
                Some(s"""if ($pname === undefined) $pname = _resolveGiven("${tc}_" + _ssc_typeOf($hint));""")
            case Type.Name(tc) =>
              Some(s"""if ($pname === undefined) $pname = _ssc_givens["$tc"] || _resolveGiven("$tc");""")
            case _ => None
          guardOpt.toList
        }
      }
      // Context-bound type params [A: TC] → synthetic JS param "A$TC", summon key "TC_A"
      @annotation.nowarn("msg=deprecated")
      val cbParams: List[(String, String)] =
        d.paramClauseGroups.flatMap(_.tparamClause.values).flatMap { tp =>
          tp.cbounds.map { cb =>
            val tvName = tp.name.value
            val tcName = cb match
              case Type.Name(n)   => n
              case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "?" }
              case _              => "?"
            s"${tvName}$$${tcName}" -> s"${tcName}_${tvName}"
          }
        }
      val savedCbMap    = cbSummonMap.toMap
      cbSummonMap.clear()
      cbParams.foreach { (pname, skey) => cbSummonMap(skey) = pname }
      val baseParamsStr = paramListWithDefaults(paramVals)
      val cbParamsStr   = cbParams.map(_._1).mkString(", ")
      val paramsStr     =
        if baseParamsStr.isEmpty then cbParamsStr
        else if cbParamsStr.isEmpty then baseParamsStr
        else s"$baseParamsStr, $cbParamsStr"
      if fname == "main" && params.isEmpty then hasMain = true
      // Effectful function: body emitted in CPS form, returns Free value.
      if isEffectfulFun(fname) then
        d.body match
          case Term.Block(stats) =>
            line(s"function $fname($paramsStr) { return ${genCpsBlockAsIife(stats)}; }")
          case expr =>
            line(s"function $fname($paramsStr) { return ${genCpsExpr(expr)}; }")
      // Context-bound params: emit plain function with auto-resolve guards; skip TCO
      else if cbParams.nonEmpty || usingGuards.nonEmpty then
        val hintParam = paramVals.headOption.map(_.name.value).getOrElse("undefined")
        val cbGuards = cbParams.map { (pname, skey) =>
          val tcName = skey.takeWhile(_ != '_')
          s"""if ($pname === undefined) $pname = _resolveGiven("${tcName}_" + _ssc_typeOf($hintParam));"""
        }
        val allGuards = cbGuards ++ usingGuards
        d.body match
          case Term.Block(bodyStats) =>
            line(s"function $fname($paramsStr) {")
            indent += 1
            allGuards.foreach(line)
            genFunctionBody(bodyStats)
            indent -= 1
            line("}")
          case expr =>
            line(s"function $fname($paramsStr) {")
            indent += 1
            allGuards.foreach(line)
            line(s"return ${genExpr(expr)};")
            indent -= 1
            line("}")
      // Mutual recursion group → _impl + trampoline wrapper.
      // Defaults disable the TCO/mutual-TCO shadowing path since the _p shadow
      // names would shadow the original parameter names referenced in default
      // expressions; defaults are uncommon in tight recursive loops anyway.
      else if mutualGroups.contains(fname) && params.nonEmpty && !hasDefaults &&
              !containsAwaitClient(d.body) then
        genMutualTcoFun(d, fname, params)
      // Self-TCO: emit a while-loop trampoline when the function calls itself and all
      // self-calls are in tail position. The anywhereContainsSelfCall guard is required
      // because hasNonTailSelfCall returns false for non-recursive functions too (zero
      // non-tail self-calls), which would incorrectly wrap every function in while(true).
      else if params.nonEmpty && fname.nonEmpty && !hasDefaults &&
              !containsAwaitClient(d.body) &&
              anywhereContainsSelfCall(d.body, fname) &&
              !hasNonTailSelfCall(d.body, fname, tailPos = true) then
        // Formals are _p shadow-names so we can declare mutable let params inside.
        // safeJsParam guards against JS reserved words (e.g. `default` → `default_p`).
        val renames  = params.collect { case p if jsReservedWords.contains(p) => p -> safeJsParam(p) }.toMap
        val formals  = params.map(p => s"_$p").mkString(", ")
        val letDecls = "let " + params.map(p => s"${safeJsParam(p)} = _$p").mkString(", ")
        line(s"function $fname($formals) {")
        indent += 1
        line(s"$letDecls;")
        line("while(true) {")
        indent += 1
        withParamRenames(renames)(genTcoBody(d.body, fname, params))
        indent -= 1
        line("}")
        indent -= 1
        line("}")
      else
        val fnKw = if containsAwaitClient(d.body) then "async function" else "function"
        d.body match
          case Term.Block(bodyStats) =>
            line(s"$fnKw $fname($paramsStr) {")
            indent += 1
            withParamRenames(defRenames)(genFunctionBody(bodyStats))
            indent -= 1
            line("}")
          case tm: Term.Match =>
            line(s"$fnKw $fname($paramsStr) {")
            indent += 1
            withParamRenames(defRenames)(genMatchAsStmts(tm, t => line(s"return ${genExpr(t)};")))
            indent -= 1
            line("}")
          case expr =>
            line(s"$fnKw $fname($paramsStr) { return ${withParamRenames(defRenames)(genExpr(expr))}; }")
      cbSummonMap.clear()
      cbSummonMap ++= savedCbMap

    case d: Defn.Class =>
      // case class → constructor function returning plain object
      val paramVals = d.ctor.paramClauses.flatMap(_.values)
      val params = paramVals.map(_.name.value)
      val typeName = d.name.value
      val paramsStr = paramListWithDefaults(paramVals)
      val fields = params.map(p => s"$p: $p").mkString(", ")
      line(s"function $typeName($paramsStr) { return {_type: '$typeName', $fields}; }")
      line(jsTypedJsonRegisterProduct(typeName, params, typeName))
      // Compile zero-param body methods (e.g. override def toString) as typed extension registrations.
      val destructure = if params.isEmpty then "" else s"const {${params.mkString(", ")}} = _self; "
      d.templ.body.stats.foreach {
        case meth: Defn.Def
            if meth.paramClauseGroups.flatMap(_.paramClauses).filterNot(_.mod.nonEmpty).flatMap(_.values).isEmpty =>
          val methName = meth.name.value
          val bodyJs = genExpr(meth.body)
          line(s"_registerExt('$methName', (_self) => { ${destructure}return $bodyJs; }, '$typeName');")
        case _ => ()
      }

    case d: Defn.Object =>
      val objName = d.name.value
      // If the name is already declared in the preamble (e.g. `Console`), merge via
      // Object.assign rather than re-declaring with `const` (which is a SyntaxError).
      val preambleConsts = Set("Console", "attr", "scope")
      if preambleConsts.contains(objName) then
        line(s"Object.assign($objName, ${genObjectAsExpr(d)});")
      else
        line(s"const $objName = ${genObjectAsExpr(d)};")

    case d: Defn.Enum =>
      val enumName = d.name.value
      val allCases = scala.collection.mutable.ListBuffer.empty[String]
      val nullary  = scala.collection.mutable.ListBuffer.empty[String]
      def emitNullary(caseName: String): Unit =
        line(s"const $caseName = {_type: '$caseName'};")
        line(jsTypedJsonRegisterProduct(caseName, Nil, None))
        allCases += caseName; nullary += caseName
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseName = ec.name.value
          val paramVals = ec.ctor.paramClauses.flatMap(_.values)
          val params = paramVals.map(_.name.value)
          if params.isEmpty then emitNullary(caseName)
          else
            val paramsStr = paramListWithDefaults(paramVals)
            val fields = params.map(p => s"$p: $p").mkString(", ")
            line(s"function $caseName($paramsStr) { return {_type: '$caseName', $fields}; }")
            line(jsTypedJsonRegisterProduct(caseName, params, caseName))
            allCases += caseName
        // `case A, B` (comma-separated parameterless cases) → RepeatedEnumCase.
        case rec: Defn.RepeatedEnumCase =>
          rec.cases.foreach(nm => emitNullary(nm.value))
        case _ => ()
      }
      // Companion: qualified `EnumName.Case` refs + `EnumName.values` (the
      // parameterless cases, in declaration order).
      val members = allCases.map(c => s"$c: $c").mkString(", ")
      val sep     = if members.isEmpty then "" else ", "
      line(s"const $enumName = { $members${sep}values: [${nullary.mkString(", ")}] };")

    case td: Defn.Trait =>
      // Record parent typeclasses so given registrations can propagate up the hierarchy.
      val parents = td.templ.inits.flatMap { init =>
        init.tpe match
          case Type.Name(n)  => List(n)
          case ta: Type.Apply => ta.tpe match { case Type.Name(n) => List(n); case _ => Nil }
          case _             => Nil
      }
      if parents.nonEmpty then tcParentMap(td.name.value) = parents

    case d: Defn.Given =>
      // given intShow: Show[Int] with { def show(x) = ... }
      // → const intShow = { show: (x) => ..., ... };
      // also register as Show_Int.
      //
      // Extension methods inside the given body (`extension [A](fa: F[A]) def fmap[B](f) = ...`)
      // are registered into the global `_extensions` table so `fa.fmap(f)` dispatches —
      // same machinery as top-level extension groups.
      d.templ.body.stats.foreach {
        case eg: Defn.ExtensionGroup => genStat(eg)
        case _                       => ()
      }
      d.templ.inits.headOption.foreach { init =>
        def extractTcArg(tpe: Type): Option[(String, String)] = tpe match
          case Type.Name(n) => Some(n -> n)
          case ta: Type.Apply => ta.tpe match
            case Type.Name(tc) =>
              val arg = ta.argClause.values match
                case List(Type.Name(n))    => n
                case List(ta2: Type.Apply) => ta2.tpe match { case Type.Name(n) => n; case _ => "_" }
                case _                     => "_"
              Some(tc -> arg)
            case _ => None
          case _ => None
        extractTcArg(init.tpe).foreach { (primaryTc, typeArg) =>
          val members = d.templ.body.stats.collect { case dd: Defn.Def =>
            s"${dd.name.value}: ${genDefAsMethod(dd)}"
          }
          val obj = s"{${members.mkString(", ")}}"
          val explicitName = d.name.value
          // Compute all typeclass keys transitively (primary + parent TCs)
          def allTcKeys(tc: String): List[String] =
            tc :: tcParentMap.getOrElse(tc, Nil).flatMap(allTcKeys)
          val allKeys = allTcKeys(primaryTc).map(tc => s"${tc}_${typeArg}").distinct
          if explicitName.nonEmpty then
            line(s"const $explicitName = $obj;")
            // Register under primary alias + all parent typeclass keys
            line(s"const ${primaryTc}_${typeArg} = $explicitName;")
            allKeys.foreach { key => line(s"""_ssc_givens["$key"] = $explicitName;""") }
          else
            val primaryKey = s"${primaryTc}_${typeArg}"
            line(s"const $primaryKey = $obj;")
            allKeys.foreach { key => line(s"""_ssc_givens["$key"] = $primaryKey;""") }
        }
      }

    case _: Decl.Def => () // abstract

    case d: Defn.ExtensionGroup =>
      d.paramClauseGroup.foreach { pcg =>
        pcg.paramClauses.headOption.flatMap(_.values.headOption).foreach { recvParam =>
          val recvName = recvParam.name.value
          val recvType = recvParam.decltpe match
            case Some(Type.Name(n))   => n
            case Some(ta: Type.Apply) => ta.tpe match { case Type.Name(n) => n; case _ => "Any" }
            case _                    => "Any"
          d.body match
            case defn: Defn.Def =>
              genExtensionDef(recvName, recvType, defn)
            case Term.Block(stats) =>
              stats.foreach { case defn: Defn.Def => genExtensionDef(recvName, recvType, defn); case _ => () }
            case _ => ()
        }
      }

    case t: Term =>
      // Track if main() is explicitly called
      t match
        case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
        case _ => ()
      if isStreamTerminalStat(t) then line(s"await ${genExpr(t)};")
      else line(genExpr(t) + ";")

    case _: Import => () // ignored
    case _: Export => () // ignored
    case _ => () // type aliases etc.

  private def genExtensionDef(recvName: String, recvType: String, defn: Defn.Def): Unit =
    val mparamVals = defn.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
    val paramsStr  = (recvName :: mparamVals.map(formalWithDefault)).mkString(", ")
    // Encode receiver TYPE into the function name so that the same extension
    // method on different types (e.g., Functor[List].ap and Functor[Option].ap)
    // does not collide and silently overwrite each other.
    val fnName = s"_ext_${recvType}_${defn.name.value}"
    defn.body match
      case Term.Block(bodyStats) =>
        line(s"function $fnName($paramsStr) {")
        indent += 1
        genFunctionBody(bodyStats)
        indent -= 1
        line("}")
      case expr =>
        line(s"function $fnName($paramsStr) { return ${genExpr(expr)}; }")
    // Register extension for dispatch.  The receiver type (when known)
    // disambiguates same-named extensions across typeclass instances
    // — e.g., Functor[List].map vs Functor[Option].map both register
    // `map` but route by `_typeOf(obj)` at the call site.  `Any` means
    // the legacy method-only registry handles it.
    val regType = if recvType == "Any" then "null" else s"'$recvType'"
    line(s"_registerExt('${defn.name.value}', ($recvName, ...args) => $fnName($recvName, ...args), $regType);")

  /** Emit a Scala `Defn.Object` as a JS expression — an IIFE that
   *  declares each member as a local const and returns them as an
   *  object literal.  Used both at top level (`const X = (iife)()`)
   *  and as the right-hand side of a nested `const inner = (iife)()`
   *  inside another object's body, which is how the `package:`
   *  front-matter wrapper survives JS emission. */
  private def genObjectAsExpr(d: Defn.Object): String =
    val objectName = d.name.value
    val decls = mutable.ArrayBuffer.empty[String]
    val names = mutable.ArrayBuffer.empty[String]
    // Populate tcParentMap from trait declarations in this object (cross-block accumulation).
    d.templ.body.stats.foreach {
      case td: Defn.Trait =>
        val parents = td.templ.inits.flatMap { init =>
          init.tpe match
            case Type.Name(n)  => List(n)
            case ta: Type.Apply => ta.tpe match { case Type.Name(n) => List(n); case _ => Nil }
            case _             => Nil
        }
        if parents.nonEmpty then tcParentMap(td.name.value) = parents
      case _ => ()
    }
    d.templ.body.stats.foreach {
      case dd: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(dd.body) =>
        val fname = dd.name.value
        val stub = s"_ssc_ui_$fname"
        // Forward to the corresponding Node.js runtime stub if present,
        // otherwise leave as undefined.  Avoids TDZ issues that occur when
        // the parent scope later does `const fname = pkg.fname` (shadowing).
        decls += s"const $fname = (typeof $stub !== 'undefined') ? $stub : undefined;"
        names += fname
      case dd: Defn.Def if isEffectOpDef(dd.body) =>
        val opName = dd.name.value
        val paramVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
        val params    = paramVals.map(_.name.value)
        val paramsStr = paramListWithDefaults(paramVals)
        val argsArr = if params.isEmpty then "[]" else s"[${params.mkString(", ")}]"
        decls += s"const $opName = ($paramsStr) => _perform('$objectName', '$opName', $argsArr);"
        names += opName
      case dd: Defn.Def =>
        val fname = dd.name.value
        val allClauses = dd.paramClauseGroups.flatMap(_.paramClauses).filterNot(_.mod.nonEmpty)
        // Context-bound params for functions inside objects/packages — must be included
        // in the function signature so that call sites can pass them (or trigger auto-resolve).
        @annotation.nowarn("msg=deprecated")
        val objCbParams: List[(String, String)] =
          dd.paramClauseGroups.flatMap(_.tparamClause.values).flatMap { tp =>
            tp.cbounds.map { cb =>
              val tvName = tp.name.value
              val tcName = cb match
                case Type.Name(n)   => n
                case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "?" }
                case _              => "?"
              s"${tvName}$$${tcName}" -> s"${tcName}_${tvName}"
            }
          }
        val objCbGuards = objCbParams.map { (pname, skey) =>
          val tcName = skey.takeWhile(_ != '_')
          val hintParam = allClauses.flatMap(_.values).headOption.map(_.name.value).getOrElse("undefined")
          s"""if ($pname === undefined) $pname = _resolveGiven("${tcName}_" + _ssc_typeOf($hintParam));"""
        }
        val cbParamsStr = if objCbParams.isEmpty then "" else ", " + objCbParams.map(_._1).mkString(", ")
        val savedCbMap2 = cbSummonMap.toMap
        cbSummonMap.clear()
        objCbParams.foreach { (pname, skey) => cbSummonMap(skey) = pname }
        val bodyJsRaw = dd.body match
          case Term.Block(bodyStats) =>
            if objCbGuards.isEmpty then genBlockAsIife(bodyStats)
            else s"{ ${objCbGuards.mkString(" ")} return ${genBlockAsIife(bodyStats)}; }"
          case expr =>
            if objCbGuards.isEmpty then genExpr(expr)
            else s"{ ${objCbGuards.mkString(" ")} return ${genExpr(expr)}; }"
        cbSummonMap.clear()
        cbSummonMap ++= savedCbMap2
        def clauseSig(params: List[Term.Param]): String =
          if params.nonEmpty && params.last.decltpe.exists(_.isInstanceOf[Type.Repeated]) then
            val nonVararg = params.init
            val vararg    = params.last.name.value
            if nonVararg.isEmpty then s"(...$vararg)"
            else s"(${paramListWithDefaults(nonVararg)}, ...$vararg)"
          else s"(${paramListWithDefaults(params)})"
        if allClauses.length <= 1 then
          val baseSig = clauseSig(allClauses.flatMap(_.values))
          // Append cb params to the signature if present
          val sig = if cbParamsStr.isEmpty then baseSig
                    else baseSig match
                      case s"($inner)" => s"($inner$cbParamsStr)"
                      case other       => s"($other$cbParamsStr)"
          decls += s"const $fname = $sig => $bodyJsRaw;"
        else
          val innerFn = allClauses.init.foldRight(clauseSig(allClauses.last.values) + s" => $bodyJsRaw") {
            (clause, inner) => clauseSig(clause.values) + s" => $inner"
          }
          decls += s"const $fname = $innerFn;"
        names += fname
      case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
        decls += s"const ${n.value} = ${genExpr(rhs)};"
        names += n.value
      case nested: Defn.Object =>
        decls += s"const ${nested.name.value} = ${genObjectAsExpr(nested)};"
        names += nested.name.value
      case d: Defn.Class =>
        val paramVals = d.ctor.paramClauses.flatMap(_.values)
        val params    = paramVals.map(_.name.value)
        val typeName  = d.name.value
        val paramsStr = paramListWithDefaults(paramVals)
        val fields    = params.map(p => s"$p: $p").mkString(", ")
        decls += s"function $typeName($paramsStr) { return {_type: '$typeName', $fields}; }"
        decls += jsTypedJsonRegisterProduct(typeName, params, typeName)
        names += typeName
      case d: Defn.Enum =>
        val enumName = d.name.value
        val allCases = scala.collection.mutable.ListBuffer.empty[String]
        val nullary  = scala.collection.mutable.ListBuffer.empty[String]
        def emitNullary(caseName: String): Unit =
          decls += s"const $caseName = {_type: '$caseName'};"
          decls += jsTypedJsonRegisterProduct(caseName, Nil, None)
          names += caseName; allCases += caseName; nullary += caseName
        d.templ.body.stats.foreach {
          case ec: Defn.EnumCase =>
            val caseName  = ec.name.value
            val paramVals = ec.ctor.paramClauses.flatMap(_.values)
            val params    = paramVals.map(_.name.value)
            if params.isEmpty then emitNullary(caseName)
            else
              val paramsStr = paramListWithDefaults(paramVals)
              val fields    = params.map(p => s"$p: $p").mkString(", ")
              decls += s"function $caseName($paramsStr) { return {_type: '$caseName', $fields}; }"
              decls += jsTypedJsonRegisterProduct(caseName, params, caseName)
              names += caseName; allCases += caseName
          case rec: Defn.RepeatedEnumCase =>
            rec.cases.foreach(nm => emitNullary(nm.value))
          case _ => ()
        }
        val members = allCases.map(c => s"$c: $c").mkString(", ")
        val sep     = if members.isEmpty then "" else ", "
        decls += s"const $enumName = { $members${sep}values: [${nullary.mkString(", ")}] };"
        names += enumName
      case eg: Defn.ExtensionGroup =>
        // Top-level extension groups inside packages register into _extensions (global).
        genStat(eg)
      case gd: Defn.Given =>
        // Extension groups inside givens register into _extensions (global side-effect).
        gd.templ.body.stats.foreach {
          case eg: Defn.ExtensionGroup => genStat(eg)
          case _                       => ()
        }
        // given intSum: Monoid[Int] with { ... } inside a package object:
        // define the instance object, register in _ssc_givens (and under parent TC keys too).
        gd.templ.inits.headOption.foreach { init =>
          def extractTcAndArg(tpe: Type): Option[(String, String)] = tpe match
            case Type.Name(n)  => Some(n -> n)
            case ta: Type.Apply =>
              ta.tpe match
                case Type.Name(tc) =>
                  val arg = ta.argClause.values match
                    case List(Type.Name(n))    => n
                    case List(ta2: Type.Apply) => ta2.tpe match { case Type.Name(n) => n; case _ => "_" }
                    case _                     => "_"
                  Some(tc -> arg)
                case _ => None
            case _ => None
          extractTcAndArg(init.tpe).foreach { (primaryTc, typeArg) =>
            val members = gd.templ.body.stats.collect { case dd: Defn.Def =>
              s"${dd.name.value}: ${genDefAsMethod(dd)}"
            }
            val obj = s"{${members.mkString(", ")}}"
            val explicitName = gd.name.value
            // Compute all typeclass keys: primary + parent TCs from tcParentMap (cross-block)
            def allTcKeys(tc: String): List[String] =
              tc :: tcParentMap.getOrElse(tc, Nil).flatMap(allTcKeys)
            val allKeys = allTcKeys(primaryTc).map(tc => s"${tc}_${typeArg}").distinct
            if explicitName.nonEmpty then
              decls += s"const $explicitName = $obj;"
              allKeys.foreach { key => decls += s"""_ssc_givens["$key"] = $explicitName;""" }
              names += explicitName
            else
              val primaryKey = s"${primaryTc}_${typeArg}"
              decls += s"const $primaryKey = $obj;"
              allKeys.foreach { key => decls += s"""_ssc_givens["$key"] = $primaryKey;""" }
              names += primaryKey
          }
        }
      case _ => ()
    }
    val body = decls.mkString(" ")
    val ret  = names.mkString(", ")
    s"(() => { $body return { $ret }; })()"

  private def genDefAsMethod(dd: Defn.Def): String =
    val paramVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
    val paramsStr = paramListWithDefaults(paramVals)
    dd.body match
      case Term.Block(bodyStats) =>
        val bodyJs = genBlockAsIife(bodyStats)
        s"($paramsStr) => $bodyJs"
      case expr =>
        s"($paramsStr) => ${genExpr(expr)}"

  /** Render a comma-separated formal parameter list with `= <expr>` for any
   *  parameter that has a default value. Used everywhere we emit a JS function
   *  signature from a scalameta `Term.Param` list. */
  private def paramListWithDefaults(paramVals: Seq[Term.Param]): String =
    paramVals.map(formalWithDefault).mkString(", ")

  // JS reserved words that cannot appear as parameter names (ES2022 strict mode).
  private val jsReservedWords = Set(
    "await", "break", "case", "catch", "class", "const", "continue", "debugger",
    "default", "delete", "do", "else", "export", "extends", "false", "finally",
    "for", "function", "if", "import", "in", "instanceof", "let", "new", "null",
    "return", "static", "super", "switch", "this", "throw", "true", "try",
    "typeof", "var", "void", "while", "with", "yield"
  )

  private def safeJsParam(name: String): String =
    if jsReservedWords.contains(name) then s"${name}_p" else name

  private def formalWithDefault(p: Term.Param): String =
    val n = safeJsParam(p.name.value)
    p.default match
      case Some(d) => s"$n = ${genExpr(d)}"
      case None    => n

  // ─── Mutual TCO helpers ──────────────────────────────────────────

  // Emits _fname_impl (while-loop + _tailCall for mutual calls) and the public wrapper.
  private def genMutualTcoFun(d: Defn.Def, fname: String, params: List[String]): Unit =
    val implName = s"_${fname}_impl"
    val friends  = mutualGroups(fname) - fname
    val renames  = params.collect { case p if jsReservedWords.contains(p) => p -> safeJsParam(p) }.toMap
    val formals  = params.map(p => s"_$p").mkString(", ")
    val letDecls = "let " + params.map(p => s"${safeJsParam(p)} = _$p").mkString(", ")
    line(s"function $implName($formals) {")
    indent += 1
    line(s"$letDecls;")
    line("while(true) {")
    indent += 1
    withParamRenames(renames)(genMutualTcoBody(d.body, fname, params, friends))
    indent -= 1
    line("}")
    indent -= 1
    line("}")
    // Public wrapper that starts the trampoline
    val wrapArgs = params.map(p => s"_$p").mkString(", ")
    line(s"function $fname($formals) { return _trampoline($implName, $wrapArgs); }")

  // Like genTcoBody but mutual tail calls return _tailCall thunks.
  private def genMutualTcoBody(term: Term, fname: String, params: List[String], friends: Set[String]): Unit =
    term match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
        if params.length == 1 then line(s"${params(0)} = ${newArgs(0)};")
        else
          val temps = newArgs.indices.map(i => s"_tco$i")
          line(s"const ${temps.zip(newArgs).map((t, v) => s"$t = $v").mkString(", ")};")
          params.zip(temps).foreach { (p, t) => line(s"$p = $t;") }
        line("continue;")
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) if friends.contains(n) =>
        val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
        line(s"return _tailCall(_${n}_impl, ${newArgs.mkString(", ")});")
      case t: Term.If =>
        line(s"if (${genExpr(t.cond)}) {")
        indent += 1; genMutualTcoBody(t.thenp, fname, params, friends); indent -= 1
        line("} else {")
        indent += 1; genMutualTcoBody(t.elsep, fname, params, friends); indent -= 1
        line("}")
      case Term.Block(stats) =>
        stats.dropRight(1).foreach {
          case t: Term => line(genExpr(t) + ";")
          case s       => genStat(s)
        }
        stats.lastOption.foreach {
          case t: Term => genMutualTcoBody(t, fname, params, friends)
          case _       => ()
        }
      case other =>
        line(s"return ${genExpr(other)};")

  // ─── Self-TCO helpers ─────────────────────────────────────────────

  // Emits statements for the body of a TCO while-loop.
  // Tail calls to fname become parameter reassignment + continue.
  // All other expressions become return statements.
  private def genTcoBody(term: Term, fname: String, params: List[String]): Unit = term match
    case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
      val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      if params.length == 1 then
        line(s"${params(0)} = ${newArgs(0)};")
      else
        // Use individual const temps to avoid temporary array allocation.
        // All RHS are evaluated before any LHS is written (cross-referencing is safe).
        val temps = newArgs.indices.map(i => s"_tco$i")
        line(s"const ${temps.zip(newArgs).map((t, v) => s"$t = $v").mkString(", ")};")
        params.zip(temps).foreach { (p, t) => line(s"$p = $t;") }
      line("continue;")
    case t: Term.If =>
      line(s"if (${genExpr(t.cond)}) {")
      indent += 1; genTcoBody(t.thenp, fname, params); indent -= 1
      line("} else {")
      indent += 1; genTcoBody(t.elsep, fname, params); indent -= 1
      line("}")
    case Term.Block(stats) =>
      stats.dropRight(1).foreach {
        case t: Term => line(genExpr(t) + ";")
        case s       => genStat(s)
      }
      stats.lastOption.foreach {
        case t: Term => genTcoBody(t, fname, params)
        case _       => ()
      }
    case tm: Term.Match =>
      genMatchAsStmts(tm, t => genTcoBody(t, fname, params))
    case other =>
      line(s"return ${genExpr(other)};")

  // Returns true if term contains a call to fname NOT in tail position.

  private def genFunctionBody(stats: List[Stat]): Unit =
    if stats.isEmpty then
      line("return undefined;")
    else
      stats.zipWithIndex.foreach { (s, i) =>
        val isLast = i == stats.length - 1
        s match
          case tw: Term.While =>
            val savedBuf = loopHoistBuf
            val newBuf   = mutable.Buffer.empty[(String, String)]
            loopHoistBuf = newBuf
            val body = genWhileBodyInline(tw.body)
            val cond = genExpr(tw.expr)
            loopHoistBuf = savedBuf
            newBuf.foreach { (k, v) => line(s"const $k = $v;") }
            line(s"while ($cond) { $body; }")
            if isLast then line("return undefined;")
          case tm: Term.Match if isLast =>
            genMatchAsStmts(tm, t => line(s"return ${genExpr(t)};"))
          case t: Term if isLast =>
            line(s"return ${genExpr(t)};")
          case t: Term =>
            line(genExpr(t) + ";")
          case stat =>
            genStat(stat)
      }

  // ── Generator / coroutine body helpers ───────────────────────────────
  // The parser wraps `{ () => body }` in a Term.Block; this helper unwraps
  // it so we always call genGeneratorBody with just the body content.
  private def extractGenBody(arg: Term): String = arg match
    case Term.Function.After_4_6_0(_, body) =>
      genGeneratorBody(body.asInstanceOf[Term])
    case Term.Block(List(Term.Function.After_4_6_0(_, body))) =>
      genGeneratorBody(body.asInstanceOf[Term])
    case other =>
      s"function*() { return ${genExpr(other)}; }"

  private def extractCoroutineBody(arg: Term): String = arg match
    case Term.Function.After_4_6_0(_, body) =>
      genCoroutineBody(body.asInstanceOf[Term])
    case Term.Block(List(Term.Function.After_4_6_0(_, body))) =>
      genCoroutineBody(body.asInstanceOf[Term])
    case other =>
      s"function*() { return ${genExpr(other)}; }"

  private def genGeneratorBody(t: Term): String =
    s"function*() {\n${genGenStmt(t)}\n}"

  private def extractStreamBody(arg: Term): String = arg match
    case Term.Function.After_4_6_0(_, body) =>
      s"async function*() {\n${genGenStmt(body.asInstanceOf[Term])}\n}"
    case Term.Block(List(Term.Function.After_4_6_0(_, body))) =>
      s"async function*() {\n${genGenStmt(body.asInstanceOf[Term])}\n}"
    case other =>
      s"async function*() { return ${genExpr(other)}; }"

  // Like genGeneratorBody but emits `return` for the last expression so
  // the coroutine's completion value is propagated via gen.next().done.
  private def genCoroutineBody(t: Term): String =
    s"function*() {\n${genCoroutineStmts(t)}\n}"

  private def genCoroutineStmts(t: Term): String = t match
    case Term.Block(stats) if stats.nonEmpty =>
      val (init, last) = (stats.init, stats.last)
      val initJs = init.map(genGenStatItem).mkString("\n")
      val lastJs = last match
        case w: Term.While  => genGenStatItem(w)  // while returns Unit — no return needed
        case t: Term.If     => genGenStatItem(t)  // if-as-statement form
        case t: Term.Throw  => genGenStatItem(t)  // throw is a statement, not an expression
        case t: Term        => s"  return ${genGenExpr(t)};"
        case s              => s"  ${genStatInline(s)}"
      if init.isEmpty then lastJs else initJs + "\n" + lastJs
    case t: Term => s"  return ${genGenExpr(t)};"
    case null    => ""

  private def genGenStmt(t: Term): String = t match
    case Term.Block(stats) => stats.map(genGenStatItem).mkString("\n")
    case s: Stat           => genGenStatItem(s)

  private def genGenStatItem(s: Stat): String = s match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"  const ${n.value} = ${genGenExpr(rhs)};"
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      s"  let ${n.value} = ${genGenExpr(rhs)};"
    case Term.Assign(Term.Name(n), rhs) =>
      s"  $n = ${genGenExpr(rhs)};"
    case t: Term.While =>
      val bodyStr = genGenStmt(t.body)
      s"  while (${genExpr(t.expr)}) {\n$bodyStr\n  }"
    case t: Term.If =>
      val elseStr = t.elsep match
        case Lit.Unit() => ""
        case ep         => s" else {\n${genGenStmt(ep)}\n  }"
      s"  if (${genExpr(t.cond)}) {\n${genGenStmt(t.thenp)}\n  }$elseStr"
    case t: Term.Match =>
      // Match as statement in a generator/coroutine body — must NOT wrap in an IIFE
      // because case branches may contain `yield` (via suspend), which is invalid inside arrow fns.
      val scrutVar = freshTmp()
      val scrutExpr = genGenExpr(t.expr)
      val casesJs = t.casesBlock.cases.map { c =>
        val (cond, bindings) = genPattern(scrutVar, c.pat)
        val bindingJs = if bindings.isEmpty then ""
          else bindings.map { case (n, e) => s"    const $n = $e;" }.mkString("\n") + "\n"
        val bodyJs = genGenStmt(c.body)
        val condStr = s"($cond) "
        s"  if $condStr{\n$bindingJs$bodyJs\n  }"
      }.mkString(" else ")
      s"  const $scrutVar = $scrutExpr;\n$casesJs"
    case Term.Throw(expr) =>
      expr match
        case Term.New(init) =>
          val errMsg = init.argClauses.headOption.flatMap(_.values.headOption)
            .map(v => genExpr(v.asInstanceOf[Term]))
            .getOrElse("'error'")
          s"  throw new Error($errMsg);"
        case Term.Apply.After_4_6_0(Term.Name("RuntimeException" | "Exception" | "Error"), argClause)
            if argClause.values.size == 1 =>
          val errMsg = genExpr(argClause.values.head.asInstanceOf[Term])
          s"  throw new Error($errMsg);"
        case _ =>
          s"  throw ${genGenExpr(expr)};"
    case t: Term => s"  ${genGenExpr(t)};"
    case _       => s"  ${genStatInline(s)}"

  private def genGenExpr(t: Term): String = t match
    case Term.Apply.After_4_6_0(Term.Name("suspend" | "emit"), argClause) if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Block(stats) => stats.map(genGenStatItem).mkString("\n")
    case _                 => genExpr(t)

  // Emits a Term.Match as an if-else chain of statements. Each case arm is emitted
  // with its bindings as const declarations followed by the arm body via `bodyEmit`.
  // This avoids the IIFE that genExpr(Term.Match) produces, eliminating arrow-fn
  // allocation + call overhead when the match is in a return/tail position.
  private def genMatchAsStmts(t: Term.Match, bodyEmit: Term => Unit): Unit =
    val scrutVar = freshTmp()
    line(s"const $scrutVar = ${genExpr(t.expr)};")
    var lastWasWildcard = false
    t.casesBlock.cases.zipWithIndex.foreach { (c, idx) =>
      val (cond, bindings) = genPattern(scrutVar, c.pat)
      if cond == "true" then
        lastWasWildcard = true
        if idx > 0 then { indent -= 1; line("} else {"); indent += 1 }
        else { line("{"); indent += 1 }
      else
        lastWasWildcard = false
        val kw = if idx == 0 then "if" else "} else if"
        if idx > 0 then indent -= 1
        line(s"$kw ($cond) {")
        indent += 1
      bindings.foreach { case (n, e) => line(s"const $n = $e;") }
      bodyEmit(c.body.asInstanceOf[Term])
    }
    indent -= 1
    if lastWasWildcard then line("}")
    else line(s"} else { throw new Error('Match failure: ' + _show($scrutVar)); }")

  // Emits while-body stats as a flat semicolon-separated string — no IIFE wrapper.
  private def genWhileBodyInline(body: Term): String = body match
    case Term.Block(stats) =>
      stats.map {
        case t: Term => genExpr(t)
        case stat    => genStatInline(stat)
      }.mkString("; ")
    case t => genExpr(t)

  private def genBlockAsIife(stats: List[Stat]): String =
    if stats.isEmpty then "undefined"
    else if stats.length == 1 then
      stats.head match
        case t: Term => genExpr(t)
        case stat =>
          s"(() => { ${genStatInline(stat)} return undefined; })()"
    else
      // Multi-stat IIFE
      val inner = StringBuilder()
      inner.append("(() => {\n")
      stats.zipWithIndex.foreach { (s, i) =>
        val isLast = i == stats.length - 1
        val pad = "  "
        s match
          case tw: Term.While =>
            val savedBuf = loopHoistBuf
            val newBuf   = mutable.Buffer.empty[(String, String)]
            loopHoistBuf = newBuf
            val twBody = genWhileBodyInline(tw.body)
            val twCond = genExpr(tw.expr)
            loopHoistBuf = savedBuf
            newBuf.foreach { (k, v) => inner.append(pad).append(s"const $k = $v;\n") }
            val body = s"while ($twCond) { $twBody; }"
            if isLast then inner.append(pad).append(body).append(" return undefined;\n")
            else inner.append(pad).append(body).append("\n")
          case t: Term if isLast =>
            inner.append(pad).append("return ").append(genExpr(t)).append(";\n")
          case t: Term =>
            inner.append(pad).append(genExpr(t)).append(";\n")
          case stat =>
            inner.append(pad).append(genStatInline(stat)).append("\n")
      }
      inner.append("})()")
      inner.toString

  private def genStatInline(stat: Stat): String = stat match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"const ${n.value} = ${genExpr(rhs)};"
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      s"let ${n.value} = ${genExpr(rhs)};"
    case d: Defn.Def =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
      d.body match
        case expr =>
          s"function ${d.name.value}(${params.mkString(", ")}) { return ${genExpr(expr)}; }"
    case t: Term => genExpr(t) + ";"
    case _ => "/* stat */;"

  private def genPatDestructure(pat: Pat): String = pat match
    case Pat.Tuple(pats) =>
      "[" + pats.map(p => p match
        case Pat.Var(n) => n.value
        case Pat.Wildcard() => "_"
        case inner => genPatDestructure(inner)
      ).mkString(", ") + "]"
    case Pat.Var(n) => n.value
    case Pat.Wildcard() => "_"
    case _ => "_"

  private def isEffectOpDef(body: Term): Boolean =
    scalascript.transform.EffectAnalysis.isEffectOpDef(body)

  /** Emits a JS matcher closure for a `receive { case … }` block.
   *  The closure takes the next mailbox message and returns either
   *  `{ matched: false }` or `{ matched: true, body: () => <computation> }`.
   *  Case bodies are CPS-emitted so any nested Actor / Async / handle
   *  effects compose into the actor's pending Computation. */
  private def genReceiveMatcher(cases: List[Case]): String =
    val scrut = "__rcv_msg__"
    val chain = cases.map { c =>
      val (cond, bindings) = genPattern(scrut, c.pat)
      val bindStmts = bindings.map { case (n, e) => s"const $n = $e;" }.mkString(" ")
      val bodyCps   = genCpsExpr(c.body)
      val condFinal = c.cond match
        case Some(g) =>
          val guardJs = genExpr(g)
          if cond == "true" then s"($guardJs)" else s"($cond) && ($guardJs)"
        case None => if cond == "true" then "true" else s"($cond)"
      s"if ($condFinal) { $bindStmts return { matched: true, body: () => $bodyCps }; }"
    }.mkString(" ")
    s"($scrut) => { $chain return { matched: false }; }"

  private def genHandleForm(body: Term, cases: List[Case]): String =
    val handledOps = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), _) => Some(s"'$eff.$op'")
        case _ => None
    }
    val handlerEntries = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), argClause) =>
          val paramNames = argClause.values.map {
            case Pat.Var(n)     => n.value
            case Pat.Wildcard() => "_"
            case _              => "_"
          }
          val paramsStr = s"[${paramNames.mkString(", ")}]"
          // Handler case bodies stay direct: they receive `resume` (a plain
          // function returning the value of the resumed branch) and compose it
          // with regular JS. Effects inside case bodies are uncommon and would
          // need their own handle.
          val bodyJs = c.body match
            case Term.Block(stats) =>
              val stmts = stats.dropRight(1).map {
                case t: Term => genExpr(t) + ";"
                case s       => genStatInline(s)
              }.mkString(" ")
              val last = stats.lastOption.map {
                case t: Term => s"return ${genExpr(t)};"
                case _       => ""
              }.getOrElse("")
              s"($paramsStr) => { $stmts $last }"
            case expr =>
              s"($paramsStr) => ${genExpr(expr)}"
          Some(s"'$eff.$op': $bodyJs")
        case _ => None
    }
    // The handle body is always emitted in CPS form so that effect ops build
    // a Free tree which _handle can interpret.
    val bodyThunk = s"() => ${genCpsExpr(body)}"
    // Use _handleOneShot when no op belongs to a multi-shot effect.
    // Also detect implicit multi-shot: if `resume` appears inside a nested lambda
    // in any handler body, the effect is being used multi-shot regardless of declaration.
    def resumeInLambda(caseBody: Term, resumeParam: String): Boolean =
      def check(t: scala.meta.Tree, depth: Int): Boolean = t match
        case Term.Name(n) if n == resumeParam => depth > 0
        case _: Term.Function           => t.children.exists(check(_, depth + 1))
        case _: Term.AnonymousFunction  => t.children.exists(check(_, depth + 1))
        case _                          => t.children.exists(check(_, depth))
      check(caseBody, 0)
    val allOneShot = handledOps.forall { opStr =>
      val effName = opStr.stripPrefix("'").takeWhile(_ != '.')
      if multiShotEffects.contains(effName) then false
      else
        // Check if any case for this effect uses resume inside a lambda
        val resumeUsedMultiShot = cases.exists { c =>
          c.pat match
            case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), _), argClause) if eff == effName =>
              val resumeName = argClause.values.lastOption match
                case Some(Pat.Var(n)) => n.value
                case _                => ""
              resumeName.nonEmpty && resumeInLambda(c.body, resumeName)
            case _ => false
        }
        !resumeUsedMultiShot
    }
    val handleFn = if allOneShot then "_handleOneShot" else "_handle"
    s"$handleFn($bodyThunk, [${handledOps.mkString(", ")}], {${handlerEntries.mkString(", ")}})"

  /** Stage 5+/A.5 — per-call-site intrinsic dispatch.  Returns the
   *  JS expression string to splice in, or `None` if no intrinsic
   *  claims this name.  Called from `genExpr` for Term.Apply
   *  (Term.Name(fname), args) sites BEFORE the existing hardcoded
   *  pattern matches, so a registered intrinsic always wins. */
  private def dispatchIntrinsicJs(fname: String, argClause: Term.ArgClause): Option[String] =
    // If the name has been shadowed by an explicit import binding (const fname = ...), skip
    // the intrinsic so the local binding takes precedence.
    if declaredBindings.contains(fname) then return None
    val qn = scalascript.ir.QualifiedName(fname)
    intrinsics.get(qn).map {
      case scalascript.backend.spi.RuntimeCall(target) =>
        // Named args (`f(name = expr)`) lower to a trailing options object
        // `{ name: expr, ... }` after the positionals.  Positional-only
        // calls are unaffected.  The runtime intrinsic reads the options
        // object (e.g. `GraphQL.resolvers({ query, mutation })`).
        val (named, positional) = argClause.values.partition(_.isInstanceOf[Term.Assign])
        val posJs = positional.map(genExpr)
        val argsJs =
          if named.isEmpty then posJs
          else
            val obj = named.collect {
              case Term.Assign(Term.Name(n), rhs) => s"$n: ${genExpr(rhs)}"
            }.mkString("{", ", ", "}")
            posJs :+ obj
        s"$target(${argsJs.mkString(", ")})"
      case scalascript.backend.spi.InlineCode(emit) =>
        val irArgs = argClause.values.map(termToIrJs)
        val ctx    = JsEmitContext
        emit(irArgs, ctx).value
      case _ =>
        // NativeImpl / HostCallback don't emit target source; fall
        // through to scalameta's default emission.
        argClause.values.map(genExpr).mkString(s"$fname(", ", ", ")")
    }

  /** Minimum-viable IrExpr conversion for intrinsic dispatch — only
   *  string / int / double / bool literals survive shape; everything
   *  else becomes a `VarRef` carrying the genExpr-emitted JS. */
  private def termToIrJs(t: Term): scalascript.ir.IrExpr = t match
    case Lit.String(s)  => scalascript.ir.Lit(scalascript.ir.LitValue.StringL(s))
    case Lit.Int(n)     => scalascript.ir.Lit(scalascript.ir.LitValue.IntL(n.toLong))
    case Lit.Long(n)    => scalascript.ir.Lit(scalascript.ir.LitValue.IntL(n))
    case Lit.Double(d)  => scalascript.ir.Lit(scalascript.ir.LitValue.DoubleL(d.toDouble))
    case Lit.Boolean(b) => scalascript.ir.Lit(scalascript.ir.LitValue.BoolL(b))
    case Lit.Unit()     => scalascript.ir.Lit(scalascript.ir.LitValue.UnitL)
    case other          => scalascript.ir.VarRef(genExpr(other))

  /** Stage 5+/A.5 — `JsGen`'s per-call-site EmitContext.  Stub for
   *  now; future intrinsics extend the trait surface as needed. */
  private object JsEmitContext extends scalascript.ir.EmitContext

  /** Generate a JS expression string for a scalameta Term. */
  def genExpr(term: Term): String = term match
    // Stage 5+/A.5 intrinsic dispatch — fires first.
    case Term.Apply.After_4_6_0(Term.Name(fname), argClause)
        if dispatchIntrinsicJs(fname, argClause).isDefined =>
      dispatchIntrinsicJs(fname, argClause).get

    // System.currentTimeMillis() → Date.now()  (milliseconds since epoch, same semantics)
    case Term.Apply.After_4_6_0(
          Term.Select(Term.Name("System"), Term.Name("currentTimeMillis")), _) =>
      "Date.now()"

    // System.nanoTime() → nanoseconds via performance.now() (μs precision in Node/browsers)
    case Term.Apply.After_4_6_0(
          Term.Select(Term.Name("System"), Term.Name("nanoTime")), _) =>
      "Math.round(performance.now() * 1e6)"

    // Stage 5+/B.3 — qualified intrinsic dispatch for `Obj.method(args)`.
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(obj), Term.Name(method)), argClause)
        if dispatchIntrinsicJs(s"$obj.$method", argClause).isDefined =>
      dispatchIntrinsicJs(s"$obj.$method", argClause).get

    // Literals
    case Lit.Int(v)     => v.toString
    case Lit.Long(v)    => v.toString
    case Lit.Double(v)  => v.toString
    case Lit.Float(v)   => v.toString
    case Lit.String(v)  =>
      // Escape for JS string literal
      "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    case Lit.Boolean(v) => v.toString
    case Lit.Char(v)    => "\"" + v.toString.replace("\"", "\\\"") + "\""
    case Lit.Unit()     => "undefined"
    case Lit.Null()     => "null"

    // Name lookup
    case Term.Name(name) => mapName(name)

    // Block
    case Term.Block(stats) =>
      if stats.isEmpty then "undefined"
      else genBlockAsIife(stats)

    // If/else — short-circuit when condition is a boolean literal
    case t: Term.If =>
      t.cond match
        case Lit.Boolean(true)  => genExpr(t.thenp)
        case Lit.Boolean(false) =>
          t.elsep match
            case Lit.Unit() => "undefined"
            case e          => genExpr(e)
        case _ =>
          val cond  = genExpr(t.cond)
          val thenp = genExpr(t.thenp)
          val elsep = t.elsep match
            case Lit.Unit() => "undefined"
            case e          => genExpr(e)
          s"($cond ? $thenp : $elsep)"

    // String interpolation
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md"
        || prefix == "html" || prefix == "css" =>
      val sb2 = StringBuilder()
      sb2.append("`")
      for i <- parts.indices do
        val part = parts(i).asInstanceOf[Lit.String].value
        // Backslash first — replacing `\\` AFTER `` ` `` would double-escape
        // the backslash inserted by the `` ` `` step, breaking the JS
        // template literal.
        sb2.append(part.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$"))
        if i < args.length then
          val arg = args(i).asInstanceOf[Term]
          val argJs = genExpr(arg)
          // html"..." escapes interpolated values unless they're a raw() marker.
          val wrapped =
            if prefix == "html" then s"_html_interp($argJs)"
            else                     s"_show($argJs)"
          sb2.append("${").append(wrapped).append("}")
      sb2.append("`")
      val templateLiteral = sb2.toString
      if prefix == "md" then s"_md($templateLiteral)" else templateLiteral

    // Registered interpolator (InterpolatorRegistry) takes precedence.
    // User-defined interpolator: _ext_StringContext_prefix(_sc([...]), [arg1, arg2])
    // Args are packed into an array so the `args: Any*` param binds a list.
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      val partStrs = parts.map(_.asInstanceOf[Lit.String].value)
      scalascript.compiler.plugin.InterpolatorRegistry.lookup(prefix) match
        case Some(impl) =>
          val argsExpr = args.map(a => genExpr(a.asInstanceOf[Term]))
          impl.jsEmit(partStrs, argsExpr)
        case None =>
          val partsJs = partStrs.map(s => "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .mkString("[", ", ", "]")
          val argsJs = args.map(a => genExpr(a.asInstanceOf[Term])).mkString("[", ", ", "]")
          s"_ext_StringContext_$prefix(_sc($partsJs), $argsJs)"

    // Anonymous function with _ placeholders — stack-based param counting
    case t: Term.AnonymousFunction =>
      phCounters = 0 :: phCounters          // push fresh counter
      val bodyJs = genExpr(t.body)
      val count  = phCounters.head
      phCounters = phCounters.tail           // pop
      val params = (0 until count).map(i => s"_$$${i}")
      if params.isEmpty then s"() => $bodyJs"
      else s"(${params.mkString(", ")}) => $bodyJs"

    // Placeholder _ — increment top counter and return indexed name
    case _: Term.Placeholder =>
      val i = phCounters.headOption.getOrElse(0)
      phCounters = phCounters match
        case h :: t => (h + 1) :: t
        case Nil    => Nil
      s"_$$$i"

    // Lambda
    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values.map(_.name.value)
      val bodyJs = body match
        case Term.Block(stats) => genBlockAsIife(stats)
        case expr              => genExpr(expr)
      if params.length == 1 then s"${params.head} => $bodyJs"
      else
        // Auto-tuple: when this lambda is passed somewhere that supplies a
        // single tuple-arg (e.g. `pairs.foreach((n, s) => ...)`, where the
        // callback receives one `[n, s]` array), destructure on entry.
        val arity   = params.length
        val joined  = params.mkString(", ")
        s"((...__a) => { const [$joined] = (__a.length === 1 && Array.isArray(__a[0]) && __a[0].length === $arity) ? __a[0] : __a; return $bodyJs; })"

    // Partial function { case ... => ... }
    case Term.PartialFunction(cases) =>
      val scrutVar = freshTmp()
      val casesJs = cases.map { c =>
        genCase(scrutVar, c)
      }.mkString(" else ")
      s"(($scrutVar) => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })"

    // Match
    case t: Term.Match =>
      val scrutVar = freshTmp()
      val scrutExpr = genExpr(t.expr)
      val casesJs = t.casesBlock.cases.map { c =>
        genCase(scrutVar, c)
      }.mkString(" else ")
      s"(($scrutVar => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })($scrutExpr))"

    // Tuple
    case Term.Tuple(elems) =>
      val elemsJs = elems.map(genExpr).mkString(", ")
      if loopHoistBuf != null && elems.forall(isLiteralTerm) then
        freshHoistConst(s"Object.freeze(Object.assign([$elemsJs], {_isTuple: true}))")
      else
        s"Object.assign([$elemsJs], {_isTuple: true})"

    // Assignment
    case Term.Assign(lhs, rhs) =>
      s"${genExpr(lhs)} = ${genExpr(rhs)}"

    // While
    case t: Term.While =>
      val savedBuf = loopHoistBuf
      val newBuf   = mutable.Buffer.empty[(String, String)]
      loopHoistBuf = newBuf
      val bodyJs = genWhileBodyInline(t.body)
      val condJs = genExpr(t.expr)
      loopHoistBuf = savedBuf
      val hoistJs = newBuf.map((k, v) => s"const $k = $v;").mkString(" ")
      if hoistJs.isEmpty then s"(() => { while ($condJs) { $bodyJs; } })()"
      else s"(() => { $hoistJs while ($condJs) { $bodyJs; } })()"

    // For-do
    case t: Term.For =>
      genForDo(t.enumsBlock.enums, t.body)

    // For-yield
    case t: Term.ForYield =>
      genForYield(t.enumsBlock.enums, t.body)

    // new ClassName(args)
    case Term.New(Init.After_4_6_0(tpe, _, argClauses)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val args = argClauses.toList.flatMap(_.values).map(genExpr)
      s"$typeName(${args.mkString(", ")})"

    // Return
    case Term.Return(expr) =>
      genExpr(expr)  // We can't easily return from JS like Scala; treat as expression

    // summon[TC[T]]
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          val key = typeArg match
            case n: Type.Name  => n.value
            case ta: Type.Apply =>
              val tc  = ta.tpe match { case n: Type.Name => n.value; case _ => "?" }
              val arg = ta.argClause.values match { case List(n: Type.Name) => n.value; case _ => "_" }
              s"${tc}_${arg}"
            case _ => "undefined"
          // Prefer a local CB param if one shadows this summon key
          cbSummonMap.getOrElse(key, key)
        case (Term.Name("Prism"), List(_, variantType)) =>
          val variantName = variantType match
            case n: Type.Name => n.value
            case _            => return "(()=>{ throw new Error('Prism[Outer, Variant]: Variant must be a simple type name'); })()"
          s"_makePrism('$variantName')"
        case _ => genExpr(t.fun)

    // v1.51.2 Streams — Source.empty / Sink.ignore / Sink.toList (field access, no args)
    case Term.Select(Term.Name("Source"), Term.Name("empty")) =>
      "_makeAsyncStream((async function*() {})(  ))"
    case Term.Select(Term.Name("Sink"), Term.Name("ignore")) =>
      "({ run: (src) => src.runDrain() })"
    case Term.Select(Term.Name("Sink"), Term.Name("toList")) =>
      "({ run: (src) => src.runToList() })"
    // Field/method selection without arguments
    case Term.Select(qual, name) =>
      val qualJs = genExpr(qual)
      name.value match
        // Built-in collection/string methods that need runtime dispatch (computed properties)
        case "head" | "tail" | "last" | "init" | "reverse" | "distinct" | "sorted" |
             "toList" | "toSet" | "sum" | "min" | "max" | "flatten" | "isEmpty" |
             "nonEmpty" | "size" | "length" | "keys" | "values" | "isDefined" |
             "toUpperCase" | "toLowerCase" | "trim" | "toInt" | "toDouble" | "toLong" |
             "abs" | "round" | "floor" | "ceil" | "zipWithIndex" | "nonEmpty" |
             "_1" | "_2" | "_3" | "_4" =>
          s"_dispatch($qualJs, '${name.value}', [])"
        case other =>
          // Direct property access for regular objects (case classes, typeclasses, etc.)
          // Use _dispatch for extension methods, but try direct property first
          s"_dispatch($qualJs, '$other', [])"

    // Special form: handle(body) { case Eff.op(args, resume) => ... }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          genHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => s"/* invalid handle */ undefined"

    // Special form: runAsync(body) — built-in Async-effect driver.  Body
    // is CPS-emitted so Async.* ops build a Free tree; `_runAsync`
    // walks it and dispatches each op against the default handler.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val awaitPrefix = if usesRunAsyncParallel then "await " else ""
      s"${awaitPrefix}_runAsyncParallel(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Browser/client helper for Promise-returning typed HTTP clients.
    // Source form: `awaitClient(Messages.list())` → JS: `await Messages.list()`.
    case Term.Apply.After_4_6_0(Term.Name("awaitClient"), argClause)
        if argClause.values.size == 1 =>
      s"await ${genExpr(argClause.values.head.asInstanceOf[Term])}"

    // Client IndexedDB typed helper.
    // Source: `IndexedDb.store[Draft]("drafts")`
    // JS:     `IndexedDb.store("drafts", "Draft")`
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Select(Term.Name("IndexedDb"), Term.Name("store")), typeArgs),
          argClause
        ) =>
      val typeName = typeArgs.values.headOption match
        case Some(Type.Name(name)) => name
        case Some(other) => other.syntax
        case None => ""
      val args = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).toList
      val jsArgs = args.headOption.toList ++ List(jsQuote(typeName)) ++ args.drop(1)
      s"IndexedDb.store(${jsArgs.mkString(", ")})"

    // Client ObjectStore sync helpers.
    // Source: `Sync.pull[Draft]("drafts")`
    // JS:     `Sync.pull("drafts", "Draft")`
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Select(Term.Name("Sync"), Term.Name(method)), typeArgs),
          argClause
        ) if method == "pull" || method == "push" || method == "sync" =>
      val typeName = typeArgs.values.headOption match
        case Some(Type.Name(name)) => name
        case Some(other) => other.syntax
        case None => ""
      val args = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).toList
      val jsArgs = args.headOption.toList ++ List(jsQuote(typeName)) ++ args.drop(1)
      s"Sync.$method(${jsArgs.mkString(", ")})"
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Select(Term.Name("Sync"), Term.Name(method)), typeArgs),
          argClause
        ) if method == "put" || method == "remove" || method == "resolve" =>
      val typeName = typeArgs.values.headOption match
        case Some(Type.Name(name)) => name
        case Some(other) => other.syntax
        case None => ""
      val args = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).toList
      val jsArgs = args.headOption.toList ++ List(jsQuote(typeName)) ++ args.drop(1)
      s"Sync.$method(${jsArgs.mkString(", ")})"

    // Storage handlers — file-backed (with optional path arg) and ephemeral
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      val pathJs = bodyArgClause.values.lift(1).map(p => genExpr(p.asInstanceOf[Term])).getOrElse("null")
      s"_runStorage(() => $bodyJs, $pathJs)"
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runStorage(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])}, null)"

    // ── v1.6 Actors Phase 1 ─────────────────────────────────────────
    // `runActors { body }` — body is CPS-emitted so Actor.* ops build
    // a Free tree the scheduler walks.  When the module uses `runActors`
    // anywhere, the whole top level runs inside an async IIFE (see
    // `genModule`'s `needsAsync`), and `_runActors` is async so it yields
    // to Node's event loop between scheduler ticks; the `await` here
    // makes the caller observe the body's last expression value (instead
    // of a Promise) and lets surrounding statements see the actor body's
    // side effects finish before they run.
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val awaitPrefix = if usesRunActors then "await " else ""
      s"${awaitPrefix}_runActors(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // v1.4 effect runners — body must be wrapped in a thunk so that effect ops
    // build a Free tree which the runner's _handle/_handleOneShot can interpret.
    // Simple runners: runF(body) → runF(() => cps(body))
    case Term.Apply.After_4_6_0(Term.Name(runner), bodyArgClause)
        if bodyArgClause.values.size == 1 &&
           Set("runLogger","runLoggerJson","runLoggerToList",
               "runRandom","runClock","runEnv","runHttp",
               "runRetry","runRetryNoSleep",
               "runCache","runCacheBypass","runTx","runStream").contains(runner) =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$runner(() => $bodyJs)"
    // Curried runners: runF(arg)(body) → runF(arg)(() => cps(body))
    case Term.Apply.After_4_6_0(
          Term.Apply.After_4_6_0(Term.Name(runner), argClause),
          bodyArgClause)
        if bodyArgClause.values.size == 1 &&
           Set("runRandomSeeded","runClockAt","runEnvWith",
               "runState","runAuthWith","runHttpStub").contains(runner) =>
      val argJs  = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).mkString(", ")
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$runner($argJs)(() => $bodyJs)"

    // `receive(timeout = N) { case … }` — same machinery as `receive`
    // but the matcher is registered with `wrapSome=true` and the
    // driver tracks a deadline.
    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_t(_registerReceive($matcherJs), ${genExpr(timeoutTerm.asInstanceOf[Term])})"
        case _ => "/* invalid receive */ undefined"

    // `receive { case … }` — special form so we can synthesise the
    // matcher closure with the right CPS-emitted bodies.
    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_(_registerReceive($matcherJs))"
        case _ => "/* invalid receive */ undefined"

    // Spawn / self / exit — emit through the Actor runtime so they
    // produce _Perform nodes the scheduler picks up.
    case Term.Apply.After_4_6_0(Term.Name("spawn"), argClause)
        if argClause.values.size == 1 =>
      val thunk = argClause.values.head.asInstanceOf[Term]
      // The thunk's body is CPS-emitted so its Actor.* ops chain.
      thunk match
        case Term.Function.After_4_6_0(_, body) =>
          s"Actor.spawn(() => ${genCpsExpr(body)})"
        case other => s"Actor.spawn(${genExpr(other)})"
    case Term.Apply.After_4_6_0(Term.Name("spawn_link"), argClause)
        if argClause.values.size == 1 =>
      val thunk = argClause.values.head.asInstanceOf[Term]
      thunk match
        case Term.Function.After_4_6_0(_, body) =>
          s"Actor.spawn_link(() => ${genCpsExpr(body)})"
        case other => s"Actor.spawn_link(${genExpr(other)})"
    case Term.Apply.After_4_6_0(Term.Name("spawnBounded"), argClause)
        if argClause.values.size == 3 =>
      val capJs      = genExpr(argClause.values(0).asInstanceOf[Term])
      val overflowJs = genExpr(argClause.values(1).asInstanceOf[Term])
      val thunk      = argClause.values(2).asInstanceOf[Term]
      val thunkJs = thunk match
        case Term.Function.After_4_6_0(_, body) => s"() => ${genCpsExpr(body)}"
        case other => genExpr(other)
      s"Actor.spawnBounded($capJs, $overflowJs, $thunkJs)"
    case Term.Apply.After_4_6_0(Term.Name("self"), argClause)
        if argClause.values.isEmpty =>
      "Actor.self()"
    case Term.Apply.After_4_6_0(Term.Name("exit"), argClause)
        if argClause.values.size == 2 =>
      val pidJs    = genExpr(argClause.values(0).asInstanceOf[Term])
      val reasonJs = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.exit($pidJs, $reasonJs)"
    // v1.6 Phase 2 — supervision primitives
    case Term.Apply.After_4_6_0(Term.Name("link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.link(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("monitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.monitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("demonitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.demonitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("trapExit"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.trapExit(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6 Phase 3 — distributed node primitives
    case Term.Apply.After_4_6_0(Term.Name("startNode"), argClause)
        if argClause.values.size >= 1 =>
      val nodeId = genExpr(argClause.values(0).asInstanceOf[Term])
      val url    = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.startNode($nodeId, $url)"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size >= 1 =>
      val url   = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.connectNode($url, $token)"
    case Term.Apply.After_4_6_0(Term.Name("joinCluster"), argClause)
        if argClause.values.size >= 1 =>
      val seeds = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.joinCluster($seeds, $token)"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalRegister"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.globalRegister(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalWhereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.globalWhereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — cluster visibility
    case Term.Apply.After_4_6_0(Term.Name("clusterMembers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMembers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeClusterEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeClusterEvents()"
    // v1.23 — phi-accrual failure detector
    case Term.Apply.After_4_6_0(Term.Name("phiOf"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.phiOf(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isSuspect"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.isSuspect($nid, $thr)"
    // v1.23 — local node identity + phi vector
    case Term.Apply.After_4_6_0(Term.Name("selfNode"), argClause)
        if argClause.values.isEmpty =>
      "Actor.selfNode()"
    case Term.Apply.After_4_6_0(Term.Name("clusterHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterHealth()"
    // v1.23 — cluster-wide failure detector
    case Term.Apply.After_4_6_0(Term.Name("broadcastHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.broadcastHealth()"
    case Term.Apply.After_4_6_0(Term.Name("clusterIsDown"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.clusterIsDown($nid, $thr)"
    // v1.23 — leader election (Bully)
    case Term.Apply.After_4_6_0(Term.Name("electLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.electLeader()"
    case Term.Apply.After_4_6_0(Term.Name("currentLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.currentLeader()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeLeaderEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeLeaderEvents()"
    case Term.Apply.After_4_6_0(Term.Name("setAutoReelect"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setAutoReelect(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — protocol switch + history
    case Term.Apply.After_4_6_0(Term.Name("useRaftLeaderElection"), argClause)
        if argClause.values.isEmpty =>
      "Actor.useRaftLeaderElection()"
    case Term.Apply.After_4_6_0(Term.Name("useExternalCoordinator"), argClause)
        if argClause.values.size == 4 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.useExternalCoordinator(${vs(0)}, ${vs(1)}, ${vs(2)}, ${vs(3)})"
    case Term.Apply.After_4_6_0(Term.Name("leaderProtocol"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderProtocol()"
    case Term.Apply.After_4_6_0(Term.Name("leaderHistory"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderHistory()"
    // v1.23 — drain / rolling-restart
    case Term.Apply.After_4_6_0(Term.Name("setDraining"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setDraining(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isDraining"), argClause)
        if argClause.values.isEmpty =>
      "Actor.isDraining()"
    case Term.Apply.After_4_6_0(Term.Name("drainingPeers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.drainingPeers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeDrainEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeDrainEvents()"
    // v1.23 — cluster metrics aggregation
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSet"), argClause)
        if argClause.values.size == 2 =>
      val n0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterMetricSet($n0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricGet"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricGet(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSum"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricSum(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricNames"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMetricNames()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeMetricEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeMetricEvents()"
    // v1.23 — auto-reconnect policy
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 2 =>
      val ini = genExpr(argClause.values(0).asInstanceOf[Term])
      val mx  = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx)"
    // v1.23 — periodic gossip re-discovery
    case Term.Apply.After_4_6_0(Term.Name("requestGossip"), argClause)
        if argClause.values.isEmpty =>
      "Actor.requestGossip()"
    // v1.23 — cluster configuration distribution
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigSet"), argClause)
        if argClause.values.size == 2 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterConfigSet($k0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigGet"), argClause)
        if argClause.values.size == 1 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.clusterConfigGet($k0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigKeys"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterConfigKeys()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeConfigEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeConfigEvents()"
    // v1.6.x — scheduled sends
    case Term.Apply.After_4_6_0(Term.Name("sendAfter"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendAfter(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("sendInterval"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendInterval(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("cancelTimer"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.cancelTimer(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — process introspection
    case Term.Apply.After_4_6_0(Term.Name("processInfo"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.processInfo(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.10 Generator — generator { () => body } / generator[T] { () => body } / suspend(v)
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("generator"), _) | Term.Name("generator"),
        argClause) if argClause.values.size == 1 =>
      s"_makeGenerator(${extractGenBody(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("suspend"), argClause)
        if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.51.2 Streams — stream { body } / emit(x) / Source.from / Source.single / Source.empty
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("stream"), _) | Term.Name("stream"),
        argClause) if argClause.values.size == 1 =>
      val bodyJs = extractStreamBody(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream(($bodyJs)())"
    case Term.Apply.After_4_6_0(Term.Name("emit"), argClause)
        if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("from")),
        argClause) if argClause.values.size == 1 =>
      val xs = genExpr(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*(xs) { for (const v of xs) yield v; })($xs))"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("single")),
        argClause) if argClause.values.size == 1 =>
      val x = genExpr(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*() { yield $x; })(  ))"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("fromGenerator")),
        argClause) if argClause.values.size == 1 =>
      val gen = genExpr(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*(g) { for await (const v of { [Symbol.asyncIterator]() { return { async next() { const r = g.next(); return r === null ? { done: true } : { done: false, value: r._value }; } }; } }) yield v; })($gen))"
    // v1.51.1 Source.tick / Source.unfold / Source.fromCallback
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("tick")),
        argClause) if argClause.values.size == 1 =>
      val ms = genExpr(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*() { while(true) { if($ms>0) await new Promise(r=>setTimeout(r,$ms)); yield undefined; } })(  ))"
    // Source.unfold(seed)(f) — curried application
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("unfold")),
          seedClause),
        fClause) if seedClause.values.size == 1 && fClause.values.size == 1 =>
      val seed = genExpr(seedClause.values.head.asInstanceOf[Term])
      val f    = genExpr(fClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*() { let _s=${seed}; while(true) { const _r=(${f})(_s); if(!_r||_r._type==='_None') break; const _t=_r.value; _s=_t[0]; yield _t[1]; } })(  ))"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("fromCallback")),
        argClause) if argClause.values.size == 1 =>
      val reg = genExpr(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*() { const _vs=[]; (${reg})(v=>_vs.push(v)); for(const v of _vs) yield v; })(  ))"
    // Sink companion methods
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Sink"), _) | Term.Name("Sink"), Term.Name("foreach")),
        argClause) if argClause.values.size == 1 =>
      val f = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ run: (src) => src.runForeach(${f}) })"
    // Sink.fold(z)(f) — curried
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Sink"), _) | Term.Name("Sink"), Term.Name("fold")),
          zClause),
        fClause) if zClause.values.size == 1 && fClause.values.size == 1 =>
      val z = genExpr(zClause.values.head.asInstanceOf[Term])
      val f = genExpr(fClause.values.head.asInstanceOf[Term])
      s"({ run: async (src) => { let acc=${z}; for await (const v of src) acc=(${f})(acc,v); return acc; } })"
    // Flow companion methods
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("map")),
        argClause) if argClause.values.size == 1 =>
      val f = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.map(${f}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("filter")),
        argClause) if argClause.values.size == 1 =>
      val p = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.filter(${p}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("fromFunction")),
        argClause) if argClause.values.size == 1 =>
      val f = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.map(${f}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("take")),
        argClause) if argClause.values.size == 1 =>
      val n = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.take(${n}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("drop")),
        argClause) if argClause.values.size == 1 =>
      val n = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.drop(${n}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("flatMap")),
        argClause) if argClause.values.size == 1 =>
      val f = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.flatMap(${f}) })"
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("scan")),
          zClause),
        fClause) if zClause.values.size == 1 && fClause.values.size == 1 =>
      val z = genExpr(zClause.values.head.asInstanceOf[Term])
      val f = genExpr(fClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.scan(${z})(${f}) })"
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("mapAsync")),
          nClause),
        fClause) if nClause.values.size == 1 && fClause.values.size == 1 =>
      val n = genExpr(nClause.values.head.asInstanceOf[Term])
      val f = genExpr(fClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.mapAsync(${n})(${f}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("recover")),
        argClause) if argClause.values.size == 1 =>
      val h = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.recover(${h}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("throttle")),
        argClause) if argClause.values.size == 1 =>
      val r = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.throttle(${r}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("debounce")),
        argClause) if argClause.values.size == 1 =>
      val ms = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.debounce(${ms}) })"
    // v1.9 Coroutine — coroutineCreate { () => body } / coroutineResume(co, in)
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("coroutineCreate"), _) | Term.Name("coroutineCreate"),
        argClause) if argClause.values.size == 1 =>
      s"_coroutineCreate(${extractCoroutineBody(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("coroutineResume"), _) | Term.Name("coroutineResume"),
        argClause) if argClause.values.size == 2 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"_coroutineResume(${vs(0)}, ${vs(1)})"

    // Special forms: computed / effect — wrap the by-name body as a
    // zero-arg thunk so the reactive scheduler can rerun it when its
    // signal deps change.
    case Term.Apply.After_4_6_0(Term.Name(react @ ("computed" | "effect")), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val bodyJs = genExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$react(() => $bodyJs)"

    // v1.5 Tier 5 #20 — `validate { body }` collects all `require*`
    // errors raised inside `body` and returns Right(value) / Left(map).
    case Term.Apply.After_4_6_0(Term.Name("validate"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val bodyJs = genExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"validate(() => $bodyJs)"

    // httpClient(url) { block } — block must be a lazy thunk so the
    // base-URL ThreadLocal is set before the block executes.  Without this
    // special case the outer apply compiles as _call(httpClient(url), body()),
    // which evaluates httpClient(url) with no block argument → TypeError.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("httpClient"), urlArgClause),
        blockArgClause)
        if blockArgClause.values.size == 1 =>
      val urlJs   = genExpr(urlArgClause.values.head.asInstanceOf[Term])
      val blockJs = genExpr(blockArgClause.values.head.asInstanceOf[Term])
      s"httpClient($urlJs, () => $blockJs)"

    // Function application
    case app: Term.Apply =>
      genApply(app)

    // Infix
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val args = argClause.values
      // Constant folding: both operands are compile-time literals
      val constResult =
        if args.length == 1 then foldConstant(lhs, op.value, args.head) else None
      // js-codegen-opt-p2: constant-tuple-concat hoisting.
      // When inside a while loop (loopHoistBuf set) and both sides are all-literal, compile-time
      // fold the concat into a single frozen array hoisted before the loop.
      val tupleHoist: Option[String] =
        if (op.value == "++" || op.value == ":::") && loopHoistBuf != null && isConstantTupleExpr(lhs) &&
           args.asInstanceOf[List[Term]].forall(e => isLiteralTerm(e) || isConstantTupleExpr(e)) then
          val elems = collectConstantTupleElems(lhs) ++ args.asInstanceOf[List[Term]].flatMap {
            case te: Term.Tuple => collectConstantTupleElems(te)
            case other          => List(genExpr(other))
          }
          Some(freshHoistConst(s"Object.freeze(Object.assign([${elems.mkString(", ")}], {_isTuple: true}))"))
        else None
      tupleHoist.orElse(constResult).getOrElse {
        val lhsJs = genExpr(lhs)
        val rhsJs = if args.length == 1 then genExpr(args.head) else args.map(genExpr).mkString(", ")
        op.value match
          case "::" => s"[${genExpr(lhs)}, ...(${genExpr(args.head)})]"
          case ":+" => s"[...($lhsJs), ${genExpr(args.head)}]"
          case "+:" => s"[${genExpr(lhs)}, ...(${genExpr(args.head)})]"
          case "++" | ":::" =>
            // Scalameta parses `a ++ (b, c)` as two infix args [b, c], not one Tuple(b,c).
            // Wrap multiple args as a JS tuple so _tupleConcat sees the full RHS.
            val rhsArgJs = args match
              case List(single) => genExpr(single)
              case multi        => s"Object.assign([${multi.map(a => genExpr(a.asInstanceOf[Term])).mkString(", ")}], {_isTuple: true})"
            s"_tupleConcat($lhsJs, $rhsArgJs)"
          // HTML DSL: `attr.cls := "hero"` builds an Attr object.
          case ":=" => s"_attr($lhsJs, $rhsJs)"
          // v1.6 actors: `pid ! msg` enqueues into the receiver's mailbox.
          case "!" => s"Actor.send($lhsJs, $rhsJs)"
          case "->" =>
            s"Object.assign([$lhsJs, $rhsJs], {_isTuple: true})"
          case "*" =>
            val rIsNum = args.headOption.exists(isNumericExpr)
            if isIntExpr(lhs) && args.headOption.exists(isIntExpr) then s"($lhsJs * $rhsJs)"
            else if isNumericExpr(lhs) && rIsNum then s"($lhsJs * $rhsJs)"
            else s"(typeof ($lhsJs) === 'string' ? ($lhsJs).repeat($rhsJs) : _arith('*', $lhsJs, $rhsJs))"
          // Exact numerics: value-based `==` for Decimal/BigInt when operands
          // aren't both statically Int (then native === is correct and faster).
          case "==" if isNumericExpr(lhs) && args.headOption.exists(isNumericExpr) => s"($lhsJs === $rhsJs)"
          case "!=" if isNumericExpr(lhs) && args.headOption.exists(isNumericExpr) => s"($lhsJs !== $rhsJs)"
          case "==" => s"_arith('==', $lhsJs, $rhsJs)"
          case "!=" => s"_arith('!=', $lhsJs, $rhsJs)"
          case "&&" => s"($lhsJs && $rhsJs)"
          case "||" => s"($lhsJs || $rhsJs)"
          case "to" =>
            // n to m → array [n, n+1, ..., m]
            s"_dispatch($lhsJs, 'to', [$rhsJs])"
          case "until" =>
            // n until m → array [n, n+1, ..., m-1]
            s"_dispatch($lhsJs, 'until', [$rhsJs])"
          case "/" if isIntExpr(lhs) && args.headOption.exists(isIntExpr) =>
            s"Math.trunc($lhsJs / $rhsJs)"
          // Exact numerics (v1.64): when operands aren't both statically Int,
          // route arithmetic/comparison through _arith so BigInt/Decimal work
          // (native JS `+` throws on BigInt+Number and can't add Decimal objects).
          // `+` keeps string-concat semantics (handled inside _arith's number path).
          // When both sides are provably numeric (Double/Float/Int/Long), use JS operators directly.
          case "+" | "-" | "/" | "%" | "<" | ">" | "<=" | ">="
              if !(isIntExpr(lhs) && args.headOption.exists(isIntExpr)) =>
            if isNumericExpr(lhs) && args.headOption.exists(isNumericExpr) then s"($lhsJs ${op.value} $rhsJs)"
            else s"_arith('${op.value}', $lhsJs, $rhsJs)"
          case other => s"($lhsJs $other $rhsJs)"
      }

    // Prefix unary operators: `!x`, `-x`, `+x`, `~x`.
    case t: Term.ApplyUnary =>
      // Constant folding for literal operands
      (t.op.value, t.arg) match
        case ("-", Lit.Int(n))     => (-n).toString
        case ("-", Lit.Long(n))    => (-n).toString
        case ("-", Lit.Double(ns)) => (-ns.toDouble).toString
        case ("+", Lit.Int(n))     => n.toString
        case ("+", Lit.Long(n))    => n.toString
        case ("!", Lit.Boolean(b)) => (!b).toString
        case _ =>
          val argJs = genExpr(t.arg)
          t.op.value match
            case "!" => s"!($argJs)"
            case "-" => s"-($argJs)"
            case "+" => s"+($argJs)"
            case "~" => s"~($argJs)"
            case op  => s"/* unsupported unary $op */"

    case Term.Ascribe(inner, _) =>
      genExpr(inner)

    // throw expr — JS `throw` is a statement, wrap in an IIFE so it's
    // usable in expression position (e.g. inside a ternary). Mirrors the
    // Term.Throw lowering in genGenStatItem.
    case Term.Throw(expr) =>
      expr match
        case Term.New(init) =>
          val errMsg = init.argClauses.headOption.flatMap(_.values.headOption)
            .map(v => genExpr(v.asInstanceOf[Term]))
            .getOrElse("'error'")
          s"(() => { throw new Error($errMsg); })()"
        case Term.Apply.After_4_6_0(Term.Name("RuntimeException" | "Exception" | "Error"), argClause)
            if argClause.values.size == 1 =>
          val errMsg = genExpr(argClause.values.head.asInstanceOf[Term])
          s"(() => { throw new Error($errMsg); })()"
        case _ =>
          // User-defined throwable (e.g. McpError("msg")): throw the value directly so
          // catch clauses can match on _type rather than instanceof Error.
          s"(() => { throw ${genExpr(expr)}; })()"

    // try { body } catch { case ... => ... } finally { ... }
    // Lowered to an IIFE so it works in expression position (val x = try …).
    // The catch handler reuses genCase, which produces an if/else chain that
    // returns from the IIFE; a trailing `throw errVar` rethrows when no case
    // matches (Scala semantics). Finally runs regardless.
    case Term.Try.After_4_9_9(bodyExpr, catchClauseOpt, finallyOpt) =>
      val bodyJs = genExpr(bodyExpr)
      val errVar = freshTmp()
      val cases  = catchClauseOpt.toList.flatMap(_.cases)
      val catchJs =
        if cases.isEmpty then s"throw $errVar;"
        else cases.map(c => genCase(errVar, c)).mkString(" else ") + s" else { throw $errVar; }"
      val finallyJs = finallyOpt.map { f => s" finally { ${genExpr(f)}; }" }.getOrElse("")
      s"(() => { try { return $bodyJs; } catch ($errVar) { $catchJs }$finallyJs })()"

    case other =>
      s"/* unsupported: ${other.productPrefix} */"

  private def genApply(app: Term.Apply): String =
    // f(regular)(using tc) — flatten all curried arg lists when the outermost
    // Apply carries a `using` clause, so the JS call passes all args at once.
    if app.argClause.mod.nonEmpty then
      def collectAllArgs(t: Term, acc: List[Term]): (Term, List[Term]) = t match
        case inner: Term.Apply => collectAllArgs(inner.fun, inner.argClause.values ++ acc)
        case other             => (other, acc)
      val (baseFun, allArgs) = collectAllArgs(app.fun, app.argClause.values)
      return s"_call(${genExpr(baseFun)}, ${allArgs.map(genExpr).mkString(", ")})"

    // f(a)(b) where f is a user-defined function with multiple explicit param groups:
    // flatten to _call(f, a, b) so the flat JS function receives all args at once.
    app.fun match
      case inner: Term.Apply =>
        def topName(t: Term): Option[String] = t match
          case Term.Name(n)       => Some(n)
          case a: Term.Apply      => topName(a.fun)
          case _                  => None
        topName(inner) match
          case Some(n) if multiParamGroupFns(n) =>
            def flattenArgs(t: Term, acc: List[Term]): (Term, List[Term]) = t match
              case a: Term.Apply => flattenArgs(a.fun, a.argClause.values ++ acc)
              case other         => (other, acc)
            val (baseFun, allArgs) = flattenArgs(app, Nil)
            return s"_call(${genExpr(baseFun)}, ${allArgs.map(genExpr).mkString(", ")})"
          case _ => ()
      case _ => ()

    // def f: T => U = x => body — zero-param def returning a function.
    // _call(f, x) would call f(x) in JS but f has no params, so x is ignored and the lambda is returned.
    // Fix: generate _call(f(), x) so the inner function receives x.
    app.fun match
      case Term.Name(n) if zeroParamFns(n) && app.argClause.values.nonEmpty =>
        val argsJs = app.argClause.values.map(genExpr).mkString(", ")
        return s"_call($n(), $argsJs)"
      case _ => ()

    // .copy(field = value, ...) — spread the receiver, override named fields.
    // Intercepted before argVals are computed so Term.Assign doesn't fall into
    // genExpr's `lhs = rhs` path (which would emit a JS assignment expression).
    app.fun match
      case Term.Select(qual, Term.Name("copy")) =>
        return genCopy(qual, app.argClause.values)
      case _ => ()

    // Focus[T](_.a.b) / Focus(_.a.b) — emit a Lens object built from the
    // syntactic field path. The lambda body is inspected at codegen time;
    // letting it through normal genExpr would lose the path information.
    app.fun match
      case ta: Term.ApplyType if isFocusFun(ta.fun) =>
        return genFocus(app.argClause.values)
      case Term.Name("Focus") =>
        return genFocus(app.argClause.values)
      case _ => ()

    // direct[M] { stmts } — v1.8 do-notation sugar
    app.fun match
      case Term.ApplyType.After_4_6_0(Term.Name("direct"), typeArgClause) if app.argClause.values.size == 1 =>
        val typeArg = typeArgClause.values.headOption.getOrElse(Type.Name("?"))
        DirectTypeUtils.validateDirectTypeArg(typeArg)
        return (app.argClause.values.head match
          case block: Term.Block => genDirectBlock(block.stats)
          case single: Term      => genExpr(single)
          case null              => "undefined")
      case _ => ()

    // Named-arg reordering: when any arg is Term.Assign (name = expr),
    // look up the function's param order and reorder args to positional form.
    // Falls back to the original (potentially wrong) order when the function
    // is not in funcParamOrder (e.g. higher-order / imported functions).
    val rawArgs = app.argClause.values
    val hasNamedArgs = rawArgs.exists(_.isInstanceOf[Term.Assign])
    val argVals: List[String] =
      if !hasNamedArgs then rawArgs.map(genExpr)
      else
        // Extract the function name for param-order lookup.
        val fnNameOpt: Option[String] = app.fun match
          case Term.Name(n)             => Some(n)
          case Term.Select(_, Term.Name(n)) => Some(n)
          case _                        => None
        fnNameOpt.flatMap(funcParamOrder.get) match
          case Some(params) =>
            // Reorder: fill slots by name, then fill remaining with positionals.
            val slots = Array.fill[Option[String]](params.length)(None)
            rawArgs.foreach {
              case Term.Assign(Term.Name(n), rhs) =>
                val idx = params.indexOf(n)
                if idx >= 0 then slots(idx) = Some(genExpr(rhs))
              case _ => ()
            }
            val positionals = rawArgs.collect { case t if !t.isInstanceOf[Term.Assign] => genExpr(t) }.iterator
            for i <- slots.indices do
              if slots(i).isEmpty && positionals.hasNext then slots(i) = Some(positionals.next())
            // Emit up to the last filled slot; trailing Nones become undefined (JS default).
            val lastFilled = slots.lastIndexWhere(_.isDefined)
            if lastFilled < 0 then Nil
            else slots.take(lastFilled + 1).map(_.getOrElse("undefined")).toList
          case None =>
            // Function not in table — fall back: positionals first, then named by RHS only.
            rawArgs.map {
              case Term.Assign(_, rhs) => genExpr(rhs)
              case other               => genExpr(other)
            }
    app.fun match
      // Map constructor - args are tuple pairs
      case Term.Name("Map") =>
        s"_Map(${argVals.mkString(", ")})"

      // List constructor
      case Term.Name("List") =>
        s"[${argVals.mkString(", ")}]"

      // Some / None
      case Term.Name("Some") | Term.Name("_Some") =>
        s"_Some(${argVals.mkString(", ")})"

      // assert
      case Term.Name("assert") =>
        s"assert(${argVals.mkString(", ")})"

      // foldLeft curried: Apply(Apply(Select(xs, "foldLeft"), [init]), [f])
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldLeft")), initArgClause) =>
        val qualJs = genExpr(qual)
        val initJs = initArgClause.values.map(genExpr).mkString(", ")
        val fJs = argVals.mkString(", ")
        s"_seqFoldLeft($qualJs, $initJs, $fJs)"

      // foldRight curried
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldRight")), initArgClause) =>
        val qualJs = genExpr(qual)
        val initJs = initArgClause.values.map(genExpr).mkString(", ")
        val fJs = argVals.mkString(", ")
        s"(($qualJs).reduceRight(($fJs === undefined ? (acc,x) => acc : (acc,x) => ($fJs)(x, acc)), $initJs))"

      // Method calls: obj.method(args) → _dispatch(obj, "method", [args])
      case Term.Select(qual, Term.Name(method)) =>
        val qualJs = genExpr(qual)
        method match
          // String * n handled specially
          case _ =>
            val argsJs = argVals.mkString(", ")
            s"_dispatch($qualJs, '$method', [$argsJs])"

      // Known user-defined function with params — emit a direct JS call.
      // Safe because funcParamOrder only contains `def f(...)` declarations;
      // Array/Map values are vals, never in funcParamOrder.
      case Term.Name(n) if funcParamOrder.contains(n) =>
        s"$n(${argVals.mkString(", ")})"

      // Regular function call or constructor — wrap in `_call` so a
      // bare Array / Map reference (`xs(i)` / `m(k)`) is dispatched as
      // indexing rather than failing with "not a function".
      case fun =>
        val funJs = genExpr(fun)
        s"_call($funJs, ${argVals.mkString(", ")})"

  // ─── Lenses / Focus / .copy ──────────────────────────────────────

  private def isFocusFun(t: Term): Boolean = t match
    case Term.Name("Focus") => true
    case _                  => false

  private def genCopy(qual: Term, args: List[Term]): String =
    val qualJs = genExpr(qual)
    val positional = args.collect {
      case t if !t.isInstanceOf[Term.Assign] => genExpr(t)
    }
    val named = args.collect {
      case Term.Assign(Term.Name(field), rhs) => s"$field: ${genExpr(rhs)}"
    }
    if positional.isEmpty then
      // All-named — emit a plain spread for clarity / speed.
      if named.isEmpty then s"({...$qualJs})"
      else s"({...$qualJs, ${named.mkString(", ")}})"
    else
      // Mixed or all-positional — route through the `_copy` runtime helper,
      // which uses the object's own key order to map positionals to fields.
      val posArr = s"[${positional.mkString(", ")}]"
      val namedObj = if named.isEmpty then "{}" else s"{${named.mkString(", ")}}"
      s"_copy($qualJs, $posArr, $namedObj)"

  private def genFocus(args: List[Term]): String = args match
    case List(lambda) =>
      val stepsOpt: Option[List[String]] = lambda match
        case Term.AnonymousFunction(body) =>
          extractPathSteps(body, _.isInstanceOf[Term.Placeholder])
        case Term.Function.After_4_6_0(paramClause, body) =>
          paramClause.values.headOption.map(_.name.value).flatMap { p =>
            extractPathSteps(body, {
              case Term.Name(n) => n == p
              case _            => false
            })
          }
        case _ => None
      stepsOpt match
        case Some(steps) if steps.nonEmpty =>
          // Steps are JS-code fragments: each entry is a literal that goes
          // straight into the array.  Field / __some__ / __each__ encode
          // as plain strings ('field' / '__some__' / '__each__'); v0.9
          // `.index(i)` / `.at(k)` encode as small object literals
          // (`{kind:'index',i:3}` / `{kind:'at',key:'u-42'}`).
          val stepLiterals = s"[${steps.mkString(", ")}]"
          val hasIndexOrAt =
            steps.exists(s => s.startsWith("{kind:'index'") || s.startsWith("{kind:'at'"))
          if steps.contains("'__each__'")                      then s"_makeTraversal($stepLiterals)"
          else if steps.contains("'__some__'") || hasIndexOrAt then s"_makeOptional($stepLiterals)"
          else                                                       s"_makeLens($stepLiterals)"
        case _ =>
          s"(()=>{ throw new Error('Focus: expected a field-access lambda like _.field.subfield'); })()"
    case _ =>
      s"(()=>{ throw new Error('Focus expects exactly one lambda argument'); })()"

  private def extractPathSteps(body: Term, isBase: Term => Boolean): Option[List[String]] =
    def jsLit(lit: Lit): Option[String] = lit match
      case Lit.String(v)  => Some("\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
      case Lit.Int(v)     => Some(v.toString)
      case Lit.Long(v)    => Some(v.toString)
      case Lit.Double(v)  => Some(v.toString)
      case Lit.Boolean(v) => Some(v.toString)
      case _              => None
    def loop(t: Term, acc: List[String]): Option[List[String]] = t match
      case Term.Select(qual, Term.Name("some")) => loop(qual, "'__some__'" :: acc)
      case Term.Select(qual, Term.Name("each")) => loop(qual, "'__each__'" :: acc)
      // v0.9 pointwise — `.index(i)` / `.at(k)`.
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("index")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case Lit.Int(i)  => loop(qual, s"{kind:'index',i:$i}" :: acc)
          case Lit.Long(i) => loop(qual, s"{kind:'index',i:$i}" :: acc)
          case _           => None
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("at")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case lit: Lit => jsLit(lit).flatMap(js => loop(qual, s"{kind:'at',key:$js}" :: acc))
          case _        => None
      case Term.Select(qual, name)              => loop(qual, s"'${name.value}'" :: acc)
      case other if isBase(other)               => Some(acc)
      case _                                     => None
    loop(body, Nil)

  // ─── CPS codegen for effectful contexts ──────────────────────────
  //
  // Inside a handle body (or the body of an effectful function), expressions
  // are emitted in CPS form: every operation that may depend on a Free value
  // is threaded through `_bind`. Plain JS values double as Pure(value), so
  // pure sub-expressions don't pay any wrapping overhead.
  //
  // genCpsExpr(t) returns a JS expression that evaluates to a Free value:
  // either a plain JS value (Pure) or a {_tag:'Perform', eff, op, args, k} node.

  /** Whether `t` is a syntactically simple value reference: no sub-computation,
   *  guaranteed not to be a Perform. Used to avoid pointless `_bind` chains. */
  private def isSimpleCpsExpr(t: Term): Boolean = t match
    case _: Lit                                  => true
    case _: Term.Placeholder                     => true
    case Term.Name(n) if !isEffectfulFun(n)      => true
    case _                                       => false

  /** Bind a list of CPS sub-expressions; pass their resulting plain values to k.
   *  Simple sub-expressions are inlined without a bind. */
  private def bindArgsCps(args: List[Term])(k: List[String] => String): String =
    def loop(remaining: List[Term], acc: List[String]): String = remaining match
      case Nil       => k(acc.reverse)
      case t :: rest =>
        if isSimpleCpsExpr(t) then loop(rest, genExpr(t) :: acc)
        else
          val v = freshTmp()
          s"_bind(${genCpsExpr(t)}, $v => ${loop(rest, v :: acc)})"
    loop(args, Nil)

  /** Generate a JS expression in CPS form. */
  private def genCpsExpr(term: Term): String = term match
    // Literals / names — pure values pass straight through
    case _: Lit              => genExpr(term)
    case _: Term.Placeholder => genExpr(term)
    case Term.Name(_)        => genExpr(term)

    // Block — chain stats through _bind
    case Term.Block(stats) => genCpsBlockAsIife(stats)

    // If — bind cond, then branch (each branch is CPS)
    case t: Term.If =>
      val thenJs = genCpsExpr(t.thenp)
      val elseJs = t.elsep match
        case Lit.Unit() => "undefined"
        case e          => genCpsExpr(e)
      if isSimpleCpsExpr(t.cond) then s"(${genExpr(t.cond)} ? ($thenJs) : ($elseJs))"
      else
        val tmp = freshTmp()
        s"_bind(${genCpsExpr(t.cond)}, $tmp => $tmp ? ($thenJs) : ($elseJs))"

    // String interpolation — bind args
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md" =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val sb2 = StringBuilder()
        sb2.append("`")
        for i <- parts.indices do
          val part = parts(i).asInstanceOf[Lit.String].value
          // Backslash first — see twin in genExpr.
          sb2.append(part.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$"))
          if i < args.length then sb2.append("${_show(").append(vs(i)).append(")}")
        sb2.append("`")
        val templateLiteral = sb2.toString
        if prefix == "md" then s"_md($templateLiteral)" else templateLiteral
      }

    // Registered interpolator (CPS path) — InterpolatorRegistry takes precedence.
    // User-defined interpolator (CPS path): _ext_StringContext_prefix(_sc([...]), [...])
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val partStrs = parts.map(_.asInstanceOf[Lit.String].value)
        scalascript.compiler.plugin.InterpolatorRegistry.lookup(prefix) match
          case Some(impl) => impl.jsEmit(partStrs, vs.toList)
          case None =>
            val partsJs = partStrs.map(s => "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
              .mkString("[", ", ", "]")
            val argsJs = vs.mkString("[", ", ", "]")
            s"_ext_StringContext_$prefix(_sc($partsJs), $argsJs)"
      }

    // Tuple
    case Term.Tuple(elems) =>
      bindArgsCps(elems) { vs =>
        s"Object.assign([${vs.mkString(", ")}], {_isTuple: true})"
      }

    // Lambda — CPS body
    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values.map(_.name.value)
      val bodyJs = body match
        case Term.Block(stats) => genCpsBlockAsIife(stats)
        case expr              => genCpsExpr(expr)
      if params.length == 1 then s"${params.head} => $bodyJs"
      else
        val arity  = params.length
        val joined = params.mkString(", ")
        s"((...__a) => { const [$joined] = (__a.length === 1 && Array.isArray(__a[0]) && __a[0].length === $arity) ? __a[0] : __a; return $bodyJs; })"

    // Anonymous function with placeholders — body is CPS
    case t: Term.AnonymousFunction =>
      phCounters = 0 :: phCounters
      val bodyJs = genCpsExpr(t.body)
      val count  = phCounters.head
      phCounters = phCounters.tail
      val params = (0 until count).map(i => s"_$$${i}")
      if params.isEmpty then s"() => $bodyJs"
      else s"(${params.mkString(", ")}) => $bodyJs"

    // Nested handle inside CPS body — returns Free that we treat like any value
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          genHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => "/* invalid handle */ undefined"

    // Nested runAsync inside CPS body
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      // Inside a CPS body: no `await` — returns a Promise that _runAsyncParallelInner
      // handles via its thenable check (nested runAsyncParallel produces a sub-Promise
      // which _FlatMap's sub resolves via `await _runAsyncParallelInner(node.sub)`).
      s"_runAsyncParallel(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Nested storage handlers inside CPS body
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      val pathJs = bodyArgClause.values.lift(1).map(p => genExpr(p.asInstanceOf[Term])).getOrElse("null")
      s"_runStorage(() => $bodyJs, $pathJs)"
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runStorage(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])}, null)"

    // ── v1.6 Actors Phase 1 (inside CPS body) ──────────────────────────
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val awaitPrefix = if usesRunActors then "await " else ""
      s"${awaitPrefix}_runActors(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // v1.4 effect runners inside CPS body — same thunk-wrapping as genExpr
    case Term.Apply.After_4_6_0(Term.Name(runner), bodyArgClause)
        if bodyArgClause.values.size == 1 &&
           Set("runLogger","runLoggerJson","runLoggerToList",
               "runRandom","runClock","runEnv","runHttp",
               "runRetry","runRetryNoSleep",
               "runCache","runCacheBypass","runTx","runStream").contains(runner) =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$runner(() => $bodyJs)"
    case Term.Apply.After_4_6_0(
          Term.Apply.After_4_6_0(Term.Name(runner), argClause),
          bodyArgClause)
        if bodyArgClause.values.size == 1 &&
           Set("runRandomSeeded","runClockAt","runEnvWith",
               "runState","runAuthWith","runHttpStub").contains(runner) =>
      val argJs  = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).mkString(", ")
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$runner($argJs)(() => $bodyJs)"

    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_t(_registerReceive($matcherJs), ${genExpr(timeoutTerm.asInstanceOf[Term])})"
        case _ => "/* invalid receive */ undefined"

    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_(_registerReceive($matcherJs))"
        case _ => "/* invalid receive */ undefined"

    case Term.Apply.After_4_6_0(Term.Name("spawn"), argClause)
        if argClause.values.size == 1 =>
      // The spawn arg is a behavior thunk.  genCpsExpr on a Function
      // emits a lambda whose body is `_bind`-chained — exactly the
      // shape `Actor.spawn(thunk)` expects.
      s"Actor.spawn(${genCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("spawn_link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.spawn_link(${genCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("spawnBounded"), argClause)
        if argClause.values.size == 3 =>
      val capJs      = genExpr(argClause.values(0).asInstanceOf[Term])
      val overflowJs = genExpr(argClause.values(1).asInstanceOf[Term])
      val thunkJs    = genCpsExpr(argClause.values(2).asInstanceOf[Term])
      s"Actor.spawnBounded($capJs, $overflowJs, $thunkJs)"
    case Term.Apply.After_4_6_0(Term.Name("self"), argClause)
        if argClause.values.isEmpty =>
      "Actor.self()"
    case Term.Apply.After_4_6_0(Term.Name("exit"), argClause)
        if argClause.values.size == 2 =>
      val pidJs    = genExpr(argClause.values(0).asInstanceOf[Term])
      val reasonJs = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.exit($pidJs, $reasonJs)"
    // v1.6 Phase 2 — supervision primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.link(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("monitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.monitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("demonitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.demonitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("trapExit"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.trapExit(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6 Phase 3 — distributed node primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("startNode"), argClause)
        if argClause.values.size >= 1 =>
      val nodeId = genExpr(argClause.values(0).asInstanceOf[Term])
      val url    = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.startNode($nodeId, $url)"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size >= 1 =>
      val url   = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.connectNode($url, $token)"
    case Term.Apply.After_4_6_0(Term.Name("joinCluster"), argClause)
        if argClause.values.size >= 1 =>
      val seeds = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.joinCluster($seeds, $token)"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalRegister"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.globalRegister(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalWhereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.globalWhereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — cluster visibility
    case Term.Apply.After_4_6_0(Term.Name("clusterMembers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMembers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeClusterEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeClusterEvents()"
    // v1.23 — phi-accrual failure detector
    case Term.Apply.After_4_6_0(Term.Name("phiOf"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.phiOf(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isSuspect"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.isSuspect($nid, $thr)"
    // v1.23 — local node identity + phi vector
    case Term.Apply.After_4_6_0(Term.Name("selfNode"), argClause)
        if argClause.values.isEmpty =>
      "Actor.selfNode()"
    case Term.Apply.After_4_6_0(Term.Name("clusterHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterHealth()"
    // v1.23 — cluster-wide failure detector
    case Term.Apply.After_4_6_0(Term.Name("broadcastHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.broadcastHealth()"
    case Term.Apply.After_4_6_0(Term.Name("clusterIsDown"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.clusterIsDown($nid, $thr)"
    // v1.23 — leader election (Bully)
    case Term.Apply.After_4_6_0(Term.Name("electLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.electLeader()"
    case Term.Apply.After_4_6_0(Term.Name("currentLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.currentLeader()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeLeaderEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeLeaderEvents()"
    case Term.Apply.After_4_6_0(Term.Name("setAutoReelect"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setAutoReelect(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — protocol switch + history
    case Term.Apply.After_4_6_0(Term.Name("useRaftLeaderElection"), argClause)
        if argClause.values.isEmpty =>
      "Actor.useRaftLeaderElection()"
    case Term.Apply.After_4_6_0(Term.Name("useExternalCoordinator"), argClause)
        if argClause.values.size == 4 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.useExternalCoordinator(${vs(0)}, ${vs(1)}, ${vs(2)}, ${vs(3)})"
    case Term.Apply.After_4_6_0(Term.Name("leaderProtocol"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderProtocol()"
    case Term.Apply.After_4_6_0(Term.Name("leaderHistory"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderHistory()"
    // v1.23 — drain / rolling-restart
    case Term.Apply.After_4_6_0(Term.Name("setDraining"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setDraining(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isDraining"), argClause)
        if argClause.values.isEmpty =>
      "Actor.isDraining()"
    case Term.Apply.After_4_6_0(Term.Name("drainingPeers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.drainingPeers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeDrainEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeDrainEvents()"
    // v1.23 — cluster metrics aggregation
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSet"), argClause)
        if argClause.values.size == 2 =>
      val n0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterMetricSet($n0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricGet"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricGet(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSum"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricSum(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricNames"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMetricNames()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeMetricEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeMetricEvents()"
    // v1.23 — auto-reconnect policy
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 2 =>
      val ini = genExpr(argClause.values(0).asInstanceOf[Term])
      val mx  = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx)"
    // v1.23 — periodic gossip re-discovery
    case Term.Apply.After_4_6_0(Term.Name("requestGossip"), argClause)
        if argClause.values.isEmpty =>
      "Actor.requestGossip()"
    // v1.23 — cluster configuration distribution
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigSet"), argClause)
        if argClause.values.size == 2 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterConfigSet($k0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigGet"), argClause)
        if argClause.values.size == 1 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.clusterConfigGet($k0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigKeys"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterConfigKeys()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeConfigEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeConfigEvents()"
    // v1.6.x — scheduled sends (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("sendAfter"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendAfter(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("sendInterval"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendInterval(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("cancelTimer"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.cancelTimer(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — process introspection (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("processInfo"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.processInfo(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.10 Generator inside CPS body — generator { } / generator[T] { }
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("generator"), _) | Term.Name("generator"),
        argClause) if argClause.values.size == 1 =>
      val bodyJs = argClause.values.head match
        case Term.Function.After_4_6_0(_, body) =>
          genGeneratorBody(body.asInstanceOf[Term])
        case other => s"function*() { return ${genExpr(other.asInstanceOf[Term])}; }"
      s"_makeGenerator($bodyJs)"
    case Term.Apply.After_4_6_0(Term.Name("suspend" | "emit"), argClause)
        if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.51.2 Streams inside CPS body
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("stream"), _) | Term.Name("stream"),
        argClause) if argClause.values.size == 1 =>
      val bodyJs = extractStreamBody(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream(($bodyJs)())"

    // Nested computed / effect inside CPS body — same wrapping as the
    // non-CPS form: by-name body becomes a zero-arg thunk.
    case Term.Apply.After_4_6_0(Term.Name(react @ ("computed" | "effect")), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"$react(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Apply — function or method call
    case app: Term.Apply =>
      genCpsApply(app)

    // Infix — bind both sides, then apply op
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val rhsTerms = argClause.values.collect { case t: Term => t }
      val rhs: Term = rhsTerms match
        case List(single) => single
        case multi        => Term.Tuple(multi.map(_.asInstanceOf[scala.meta.Term]))
      bindArgsCps(List(lhs, rhs)) { case List(vl, vr) =>
        op.value match
          case "::"           => s"[$vl, ...$vr]"
          case ":+"           => s"[...$vl, $vr]"
          case "+:"           => s"[$vl, ...$vr]"
          case "++" | ":::"   => s"_tupleConcat($vl, $vr)"
          case "!"            => s"Actor.send($vl, $vr)"
          case "->"           => s"Object.assign([$vl, $vr], {_isTuple: true})"
          case "*"            =>
            if isNumericExpr(lhs) && isNumericExpr(rhs) then s"($vl * $vr)"
            else s"(typeof ($vl) === 'string' ? ($vl).repeat($vr) : ($vl) * ($vr))"
          case "=="           => s"($vl === $vr)"
          case "!="           => s"($vl !== $vr)"
          case "&&"           => s"($vl && $vr)"
          case "||"           => s"($vl || $vr)"
          case "to"           => s"_dispatch($vl, 'to', [$vr])"
          case "until"        => s"_dispatch($vl, 'until', [$vr])"
          case "/" if isIntExpr(lhs) && isIntExpr(rhs) => s"Math.trunc($vl / $vr)"
          case other          => s"($vl $other $vr)"
        case _ => "/* infix arity mismatch */"
      }

    // Select — bind qual, dispatch
    case Term.Select(qual, name) =>
      bindArgsCps(List(qual)) { case List(q) =>
        s"_dispatch($q, '${name.value}', [])"
        case _ => "/* select arity */"
      }

    // Match — bind scrutinee, then dispatch cases
    case t: Term.Match =>
      val scrutVar = freshTmp()
      val casesJs = t.casesBlock.cases.map(c => genCpsCase(scrutVar, c)).mkString(" else ")
      bindArgsCps(List(t.expr)) { case List(sv) =>
        s"(($scrutVar => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })($sv))"
        case _ => "/* match arity */"
      }

    // For-yield in CPS — fall back: the rhs collections / generators don't typically
    // perform effects, so direct codegen with bind on result suffices for now.
    case t: Term.ForYield => genForYield(t.enumsBlock.enums, t.body)
    case t: Term.For      => genForDo(t.enumsBlock.enums, t.body)

    // While — CPS not really meaningful (side-effecting loop). Fall back.
    case t: Term.While    => genExpr(t)

    // Return
    case Term.Return(expr) => genCpsExpr(expr)

    // Default: try direct codegen (covers values, partial functions, etc.)
    case other => genExpr(other)

  /** Call site in CPS mode: bind args, then call. Handles effect ops specially. */
  private def genCpsApply(app: Term.Apply): String =
    val args = app.argClause.values
    app.fun match
      // Effect op: Eff.op(args) → _bind args then _perform
      case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op) =>
        bindArgsCps(args) { vs =>
          s"_perform('$eff', '$op', [${vs.mkString(", ")}])"
        }

      // Builtin constructors
      case Term.Name("Map") =>
        bindArgsCps(args) { vs => s"_Map(${vs.mkString(", ")})" }
      case Term.Name("List") =>
        bindArgsCps(args) { vs => s"[${vs.mkString(", ")}]" }
      case Term.Name("Some") | Term.Name("_Some") =>
        bindArgsCps(args) { vs => s"_Some(${vs.mkString(", ")})" }
      case Term.Name("assert") =>
        bindArgsCps(args) { vs => s"assert(${vs.mkString(", ")})" }

      // foldLeft curried: bind qual + init + f
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldLeft")), initArgClause) =>
        bindArgsCps(qual :: initArgClause.values ++ args) { vs =>
          val q = vs.head; val init = vs(1); val f = vs(2)
          s"_seqFoldLeft($q, $init, $f)"
        }

      // foldRight curried
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldRight")), initArgClause) =>
        bindArgsCps(qual :: initArgClause.values ++ args) { vs =>
          val q = vs.head; val init = vs(1); val f = vs(2)
          s"(($q).reduceRight((acc, x) => ($f)(x, acc), $init))"
        }

      // Method call: obj.method(args) → _dispatch
      case Term.Select(qual, Term.Name(method)) =>
        bindArgsCps(qual :: args) { vs =>
          s"_dispatch(${vs.head}, '$method', [${vs.tail.mkString(", ")}])"
        }

      // Regular function call: bind args, then call (function value itself is simple)
      case fun =>
        if isSimpleCpsExpr(fun) then
          bindArgsCps(args) { vs =>
            s"${genExpr(fun)}(${vs.mkString(", ")})"
          }
        else
          bindArgsCps(fun :: args) { vs =>
            s"${vs.head}(${vs.tail.mkString(", ")})"
          }

  /** Block as IIFE in CPS form — chains statements through _bind. */
  private def genCpsBlockAsIife(stats: List[Stat]): String =
    if stats.isEmpty then "undefined"
    else
      def build(remaining: List[Stat]): String = remaining match
        case Nil => "undefined"
        case List(s) =>
          s match
            case t: Term => genCpsExpr(t)
            case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
              // Last statement is a binding — block evaluates to undefined.
              // Still bind it so its effects (if any) run.
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => undefined)"
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => undefined)"
            case stat =>
              s"(() => { ${genStatInline(stat)} return undefined; })()"
        case s :: rest =>
          s match
            case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => ${build(rest)})"
            case Defn.Val(_, List(pat), _, rhs) =>
              val patJs = genPatDestructure(pat)
              val tmp = freshTmp()
              s"_bind(${genCpsExpr(rhs)}, $tmp => { const $patJs = $tmp; return ${build(rest)}; })"
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
              // For simplicity, treat var like val in CPS context.
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => ${build(rest)})"
            case d: Defn.Def =>
              // Function definition in block — emit as nested function declaration
              val fnJs = genCpsInlineFn(d)
              s"((${d.name.value}) => ${build(rest)})($fnJs)"
            case t: Term =>
              if isSimpleCpsExpr(t) then s"(${genExpr(t)}, ${build(rest)})"
              else s"_bind(${genCpsExpr(t)}, _ => ${build(rest)})"
            case stat =>
              s"(() => { ${genStatInline(stat)} return ${build(rest)}; })()"
      build(stats)

  /** Emit a function definition as an inline function value in CPS form. */
  private def genCpsInlineFn(d: Defn.Def): String =
    val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
    val paramsStr = params.mkString(", ")
    d.body match
      case Term.Block(stats) => s"(${paramsStr}) => ${genCpsBlockAsIife(stats)}"
      case expr              => s"(${paramsStr}) => ${genCpsExpr(expr)}"

  /** CPS case generator — like genCase but the body is CPS. */
  private def genCpsCase(scrutVar: String, c: Case): String =
    val (cond, bindings) = genPattern(scrutVar, c.pat)
    val bindingStmts = bindings.map { case (name, expr) => s"const $name = $expr;" }.mkString(" ")
    val bodyJs = genCpsExpr(c.body)
    c.cond match
      case Some(guard) =>
        val guardExpr = genExpr(guard)
        val condStr = if cond == "true" then s"($guardExpr)" else s"($cond) && ($guardExpr)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"
      case None =>
        val condStr = if cond == "true" then "true" else s"($cond)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"

  private def genCase(scrutVar: String, c: Case): String =
    val (cond, bindings) = genPattern(scrutVar, c.pat)
    val bindingStmts = bindings.map { case (name, expr) => s"const $name = $expr;" }.mkString(" ")
    val bodyJs = genExpr(c.body)

    // Pattern guard: we need bindings set up before evaluating the guard.
    // We use a nested IIFE to set up bindings, evaluate guard, then run body.
    c.cond match
      case Some(guard) if bindings.nonEmpty =>
        // Put bindings and guard inside the if block
        val guardExpr = genExpr(guard)
        val patCond = if cond == "true" then "" else s"($cond) && "
        s"if (${patCond}(() => { $bindingStmts return $guardExpr; })()) { $bindingStmts return $bodyJs; }"
      case Some(guard) =>
        val guardExpr = genExpr(guard)
        val condStr = if cond == "true" then s"($guardExpr)" else s"($cond) && ($guardExpr)"
        s"if ($condStr) { return $bodyJs; }"
      case None =>
        val condStr = if cond == "true" then "true" else s"($cond)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"

  /** Returns (condition JS, list of (varName, expr) bindings).
   *  The condition must be true for the pattern to match.
   *  Bindings are set up when condition is true.
   */
  private def genPattern(scrutVar: String, pat: Pat): (String, List[(String, String)]) = pat match
    case Pat.Wildcard() =>
      ("true", Nil)

    case Pat.Var(n) =>
      ("true", List(n.value -> scrutVar))

    case lit: Lit =>
      val litJs = lit match
        case Lit.Int(v)     => v.toString
        case Lit.Long(v)    => v.toString
        case Lit.Double(v)  => v.toString
        case Lit.String(v)  => "\"" + v.replace("\"", "\\\"") + "\""
        case Lit.Boolean(v) => v.toString
        case Lit.Null()     => "null"
        case _              => "undefined"
      (s"$scrutVar === $litJs", Nil)

    case Pat.Typed(inner, tpe) =>
      // Emit a type-test guard for union-type narrowing: `case s: String =>`.
      // Map the declared type name to a JS typeof / instanceof check.
      val typeName = tpe match
        case Type.Name(n)   => n
        case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "" }
        case _              => ""
      val typeCond = typeName match
        case "String"  => s"(typeof $scrutVar === 'string')"
        case "Int" | "Long" | "Double" | "Float" | "Number" =>
          s"(typeof $scrutVar === 'number')"
        case "Boolean" => s"(typeof $scrutVar === 'boolean')"
        case "RuntimeException" | "Exception" | "Throwable" =>
          s"($scrutVar instanceof Error || ($scrutVar && $scrutVar._type === '$typeName'))"
        case ""        => "true"    // unknown type — fall through
        case _         => s"($scrutVar && $scrutVar._type === '$typeName')"
      val (innerCond, bindings) = genPattern(scrutVar, inner)
      val cond =
        if typeCond == "true" then innerCond
        else if innerCond == "true" then typeCond
        else s"$typeCond && $innerCond"
      (cond, bindings)

    case Pat.Tuple(pats) =>
      val subConditions = pats.zipWithIndex.map { (p, i) =>
        genPattern(s"$scrutVar[$i]", p)
      }
      val cond = subConditions.map(_._1).filter(_ != "true").mkString(" && ")
      val bindings = subConditions.flatMap(_._2)
      (if cond.isEmpty then "true" else cond, bindings)

    case Pat.Extract.After_4_6_0(fn, argClause) =>
      val typeName = fn match
        case Term.Name(n)                 => n
        case Term.Select(_, Term.Name(n)) => n
        case _                            => "?"
      val args = argClause.values

      typeName match
        case "Some" =>
          val innerScrutVar = s"$scrutVar.value"
          val subConds = if args.isEmpty then Nil
            else args.zipWithIndex.map { (p, i) =>
              genPattern(if args.length == 1 then innerScrutVar else s"$innerScrutVar[$i]", p)
            }
          val subCond = subConds.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = subConds.flatMap(_._2)
          val cond = s"($scrutVar && $scrutVar._type === '_Some')" +
            (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

        case "None" =>
          (s"($scrutVar && $scrutVar._type === '_None')", Nil)

        case _ =>
          // Case class or enum case extract — use field names when available
          val knownFields = caseClassFieldsByType.get(typeName)
          val fieldTypes  = caseClassFieldTypeMap.get(typeName).getOrElse(Map.empty)
          val fields = args.zipWithIndex.map { (p, i) =>
            val fieldName = knownFields.flatMap(_.lift(i))
            val accessor = fieldName match
              case Some(fname) => s"$scrutVar.$fname"
              case None        => s"Object.values($scrutVar).slice(1)[$i]"
            // Track Double/Float-typed bound vars for direct JS arithmetic
            p match
              case Pat.Var(nm) =>
                fieldName.foreach { fname =>
                  fieldTypes.get(fname) match
                    case Some("Double" | "Float") => numericVars += nm.value
                    case _ => ()
                }
              case _ => ()
            genPattern(accessor, p)
          }
          val typeCond = s"($scrutVar && $scrutVar._type === '$typeName')"
          val subCond = fields.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = fields.flatMap(_._2)
          val cond = typeCond + (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

    case Pat.Alternative(lhs, rhs) =>
      // Either alternative matches, no bindings from alternatives typically
      val (lCond, _) = genPattern(scrutVar, lhs)
      val (rCond, _) = genPattern(scrutVar, rhs)
      (s"($lCond || $rCond)", Nil)

    // @ binder: `xs @ pattern` — bind `xs` to the whole scrutinee, then match `pattern`
    case Pat.Bind(lhs: Pat.Var, rhs) =>
      val (cond, bindings) = genPattern(scrutVar, rhs)
      (cond, (lhs.name.value -> scrutVar) :: bindings)

    // Enum singleton reference: case Red => or case Color.Red =>
    case t: Term.Name =>
      t.value match
        case "None" => (s"($scrutVar && $scrutVar._type === '_None')", Nil)
        case n      => (s"($scrutVar === $n || ($scrutVar && $scrutVar._type === '$n'))", Nil)

    case Term.Select(qual, Term.Name(n)) =>
      val qualJs = qual match
        case Term.Name(q) => s"$q.$n"
        case _            => n
      if n == "None" then (s"($scrutVar && $scrutVar._type === '_None')", Nil)
      else (s"($scrutVar === $qualJs || ($scrutVar && $scrutVar._type === '$n'))", Nil)

    case _ =>
      ("true", Nil)

  private def genForDo(enums: List[Enumerator], body: Term): String =
    if enumeratorsNeedAsyncFor(enums) then genAsyncForDo(enums, body)
    else genForDoHelper(enums, genExpr(body))

  private def genForDoHelper(enums: List[Enumerator], bodyJs: String): String = enums match
    case Nil => s"(() => { $bodyJs; })()"
    case Enumerator.Generator(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { _dispatch($rhsJs, 'forEach', [($iterVar) => { $patJs $inner; }]); })()"
    case Enumerator.Guard(cond) :: rest =>
      val condJs = genExpr(cond)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { if ($condJs) { $inner; } })()"
    case Enumerator.Val(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val v = freshTmp()
      val patJs = genForPatBinding(pat, v)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { const $v = $rhsJs; $patJs $inner; })()"
    case _ :: rest => genForDoHelper(rest, bodyJs)

  private def genForYield(enums: List[Enumerator], body: Term): String =
    if enumeratorsNeedAsyncFor(enums) then genAsyncForYield(enums, body)
    else genForYieldHelper(enums, genExpr(body))

  private def genForYieldHelper(enums: List[Enumerator], bodyJs: String): String = enums match
    case Nil => bodyJs
    case Enumerator.Generator(pat, rhs) :: Nil =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      if patJs.isEmpty then
        s"_dispatch($rhsJs, 'map', [($iterVar) => $bodyJs])"
      else
        s"_dispatch($rhsJs, 'map', [($iterVar) => { $patJs return $bodyJs; }])"
    case Enumerator.Generator(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      val inner = genForYieldHelper(rest, bodyJs)
      if patJs.isEmpty then
        s"_dispatch($rhsJs, 'flatMap', [($iterVar) => $inner])"
      else
        s"_dispatch($rhsJs, 'flatMap', [($iterVar) => { $patJs return $inner; }])"
    case Enumerator.Guard(cond) :: rest =>
      val condJs = genExpr(cond)
      val inner = genForYieldHelper(rest, bodyJs)
      // wrap in filter; but we need the generator context
      // For guard as first enum (unusual), filter is not trivially accessible
      // Return inner filtered - but we don't have a collection here, use conditional
      s"($condJs ? [$inner] : [])"
    case Enumerator.Val(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val v = freshTmp()
      val patJs = genForPatBinding(pat, v)
      val inner = genForYieldHelper(rest, bodyJs)
      s"(() => { const $v = $rhsJs; $patJs return $inner; })()"
    case _ :: rest => genForYieldHelper(rest, bodyJs)

  // Async for-yield: all generators use awaitClient → sequential awaits in async IIFE.
  // Returns a Promise; wrap with awaitClient(...) at the call site to get the value.
  private def genAsyncForYield(enums: List[Enumerator], body: Term): String =
    val stmts = scala.collection.mutable.ListBuffer[String]()
    for e <- enums do e match
      case Enumerator.Generator(pat, Term.Apply.After_4_6_0(Term.Name("awaitClient"), argClause))
          if argClause.values.size == 1 =>
        val promiseJs = genExpr(argClause.values.head.asInstanceOf[Term])
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = await $promiseJs;"
                  else s"const $iterVar = await $promiseJs; $patJs")
      case Enumerator.Generator(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = $rhsJs;"
                  else s"const $iterVar = $rhsJs; $patJs")
      case Enumerator.Guard(cond) =>
        stmts += s"if (!(${genExpr(cond)})) return undefined;"
      case Enumerator.Val(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val v = freshTmp()
        val patJs = genForPatBinding(pat, v)
        stmts += (if patJs.isEmpty then s"const $v = $rhsJs;"
                  else s"const $v = $rhsJs; $patJs")
      case _ => ()
    val bodyJs = genExpr(body)
    s"(async () => { ${stmts.mkString(" ")} return $bodyJs; })()"

  // Async for-do: all generators use awaitClient → sequential awaits in async IIFE.
  private def genAsyncForDo(enums: List[Enumerator], body: Term): String =
    val stmts = scala.collection.mutable.ListBuffer[String]()
    for e <- enums do e match
      case Enumerator.Generator(pat, Term.Apply.After_4_6_0(Term.Name("awaitClient"), argClause))
          if argClause.values.size == 1 =>
        val promiseJs = genExpr(argClause.values.head.asInstanceOf[Term])
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = await $promiseJs;"
                  else s"const $iterVar = await $promiseJs; $patJs")
      case Enumerator.Generator(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = $rhsJs;"
                  else s"const $iterVar = $rhsJs; $patJs")
      case Enumerator.Guard(cond) =>
        stmts += s"if (!(${genExpr(cond)})) return;"
      case Enumerator.Val(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val v = freshTmp()
        val patJs = genForPatBinding(pat, v)
        stmts += (if patJs.isEmpty then s"const $v = $rhsJs;"
                  else s"const $v = $rhsJs; $patJs")
      case _ => ()
    val bodyJs = genExpr(body)
    s"(async () => { ${stmts.mkString(" ")} $bodyJs; })()"

  // ─── direct[M] { ... } — v1.8 do-notation ────────────────────────

  private def checkDirectBlockStatics(stats: List[Stat]): Unit =
    def isNestedDirect(t: Tree): Boolean = t match
      case app: Term.Apply =>
        app.fun match
          case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
          case _ => false
      case _ => false
    def go(t: Tree): Unit = t match
      case _: Term.Return =>
        throw new RuntimeException("'return' inside a direct block escapes the flatMap chain — for early failure use the monad's zero (None, Nil, Left(err), …) instead")
      case _ if isNestedDirect(t) => ()
      case _: Defn.Def | _: Term.Function => ()
      case other => other.children.foreach(go)
    stats.foreach(go)

  private def genDirectBlock(stats: List[Stat]): String =
    checkDirectBlockStatics(stats)
    val expanded = DirectAnorm.expand(stats)
    if expanded.isEmpty then "undefined"
    else
      val varNames: Set[String] = expanded.collect {
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, _) => n.value
      }.toSet
      def go(remaining: List[Stat]): String = remaining match
        case Nil => "undefined"
        case List(t: Term)  => genExpr(t)
        case List(other)    => s"(() => { ${genStatInline(other)} return undefined; })()"
        case Term.Assign(Term.Name(x), rhs) :: rest if varNames.contains(x) =>
          s"(() => { $x = ${genExpr(rhs)}; return ${go(rest)}; })()"
        case Term.Assign(Term.Name(x), rhs) :: rest =>
          s"_dispatch(${genExpr(rhs)}, 'flatMap', [($x) => ${go(rest)}])"
        case Defn.Val(_, List(_: Pat.Wildcard), _, rhs) :: rest =>
          s"_dispatch(${genExpr(rhs)}, 'flatMap', [(_) => ${go(rest)}])"
        case Defn.Val(_, List(Pat.Var(n)), _, rhs) :: rest =>
          s"(() => { const ${n.value} = ${genExpr(rhs)}; return ${go(rest)}; })()"
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) :: rest =>
          s"(() => { let ${n.value} = ${genExpr(rhs)}; return ${go(rest)}; })()"
        case (t: Term) :: rest =>
          s"(() => { ${genExpr(t)}; return ${go(rest)}; })()"
        case _ :: rest => go(rest)
      go(expanded)

  private def genForPatBinding(pat: Pat, scrutVar: String): String = pat match
    case Pat.Var(n) if n.value == scrutVar => ""
    case Pat.Var(n) => s"const ${n.value} = $scrutVar;"
    case Pat.Wildcard() => ""
    case Pat.Tuple(pats) =>
      pats.zipWithIndex.map { (p, i) =>
        p match
          case Pat.Var(n) => s"const ${n.value} = $scrutVar[$i];"
          case Pat.Wildcard() => ""
          case inner => genForPatBinding(inner, s"$scrutVar[$i]")
      }.mkString(" ")
    case Pat.Extract.After_4_6_0(_, argClause) =>
      argClause.values.zipWithIndex.map { (p, i) =>
        p match
          case Pat.Var(n) => s"const ${n.value} = Object.values($scrutVar).slice(1)[$i];"
          case _ => ""
      }.mkString(" ")
    // @ binder: bind the whole scrutinee to the name, then destructure rhs
    case Pat.Bind(lhs: Pat.Var, rhs) =>
      val rhsBindings = genForPatBinding(rhs, scrutVar)
      val lhsBinding  = if lhs.name.value == scrutVar then "" else s"const ${lhs.name.value} = $scrutVar;"
      s"$lhsBinding $rhsBindings".trim
    case _ => ""

  private def mapName(name: String): String = name match
    case "println" => "_println"
    case "print"   => "_print"
    case "Some"    => "_Some"
    case "None"    => "_None"
    case other     => paramRenames.getOrElse(other, other)

  private def withParamRenames[A](renames: Map[String, String])(f: => A): A =
    paramRenames ++= renames
    try f finally paramRenames --= renames.keys

  /** Returns true if the term is provably integer-valued (no decimal arithmetic). */
  private def isIntExpr(t: Term): Boolean = t match
    case _: Lit.Int | _: Lit.Long                 => true
    case _: Lit.Double | _: Lit.Float              => false
    case Term.Name(n)                              => intVars.contains(n)
    case Term.Apply.After_4_6_0(Term.Name(n), _)  => intFunctions.contains(n)
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toInt" | "toLong")), _) => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toDouble" | "toFloat")), _) => false
    // Collection / String methods that always return an Int regardless of the
    // element type — both the field-access (`xs.length`) and the apply
    // (`xs.indexOf(x)`) forms.
    case Term.Select(_, Term.Name("length" | "size")) => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("length" | "size" | "indexOf" | "lastIndexOf" | "count")), _) => true
    // `xs.foldLeft(init)(_ + _)` returns whatever `init` is — Int when the
    // seed literal is Int.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Select(_, Term.Name("foldLeft" | "foldRight" | "fold")), initClause),
        _) => initClause.values.headOption.exists(isIntExpr)
    case Term.ApplyInfix.After_4_6_0(l, Term.Name(op), _, argClause)
        if Set("+", "-", "*", "/", "%").contains(op) =>
      argClause.values.headOption.exists(r => isIntExpr(l) && isIntExpr(r))
    case _ => false

  /** Returns true if the term is provably numeric (Int, Long, Double, or Float — never a String). */
  private def isNumericExpr(t: Term): Boolean = isIntExpr(t) || (t match
    case _: Lit.Double | _: Lit.Float => true
    case Term.Name(n)                 => numericVars.contains(n)
    case Term.Apply.After_4_6_0(Term.Name(n), _)  => numericFunctions.contains(n)
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toDouble" | "toFloat")), _) => true
    case Term.ApplyInfix.After_4_6_0(l, Term.Name(op), _, argClause)
        if Set("+", "-", "*", "/", "%").contains(op) =>
      argClause.values.headOption.exists(r => isNumericExpr(l) && isNumericExpr(r))
    case _ => false
  )

  /** Escape a string value for a JS string literal (double-quoted). */
  private def escapeJsString(s: String): String =
    s.replace("\\", "\\\\")
     .replace("\"", "\\\"")
     .replace("\n", "\\n")
     .replace("\r", "\\r")
     .replace("\t", "\\t")

  /** Try to evaluate a binary infix expression at compile time.
   *  Returns Some(js) when both operands are literals and the op is foldable.
   *  Returns None when runtime evaluation is required.
   */
  private def foldConstant(lhs: Term, op: String, rhs: Term): Option[String] =
    (lhs, rhs) match
      case (Lit.Int(a), Lit.Int(b)) => op match
        case "+"  => Some((a + b).toString)
        case "-"  => Some((a - b).toString)
        case "*"  => Some((a * b).toString)
        case "/"  if b != 0 => Some((a / b).toString)
        case "%"  if b != 0 => Some((a % b).toString)
        case "<"  => Some((a < b).toString)
        case ">"  => Some((a > b).toString)
        case "<=" => Some((a <= b).toString)
        case ">=" => Some((a >= b).toString)
        case "==" => Some((a == b).toString)
        case "!=" => Some((a != b).toString)
        case _    => None
      case (Lit.Long(a), Lit.Long(b)) => op match
        case "+"  => Some((a + b).toString)
        case "-"  => Some((a - b).toString)
        case "*"  => Some((a * b).toString)
        case "/"  if b != 0 => Some((a / b).toString)
        case "%"  if b != 0 => Some((a % b).toString)
        case "<"  => Some((a < b).toString)
        case ">"  => Some((a > b).toString)
        case "<=" => Some((a <= b).toString)
        case ">=" => Some((a >= b).toString)
        case "==" => Some((a == b).toString)
        case "!=" => Some((a != b).toString)
        case _    => None
      case (Lit.Double(as), Lit.Double(bs)) =>
        // Lit.Double.value is a String in scalameta 4.x
        val a = as.toDouble; val b = bs.toDouble
        op match
          case "+"  => Some((a + b).toString)
          case "-"  => Some((a - b).toString)
          case "*"  => Some((a * b).toString)
          case "/"  => Some((a / b).toString)
          case "<"  => Some((a < b).toString)
          case ">"  => Some((a > b).toString)
          case "<=" => Some((a <= b).toString)
          case ">=" => Some((a >= b).toString)
          case "==" => Some((a == b).toString)
          case "!=" => Some((a != b).toString)
          case _    => None
      case (Lit.Boolean(a), Lit.Boolean(b)) => op match
        case "&&" => Some((a && b).toString)
        case "||" => Some((a || b).toString)
        case "==" => Some((a == b).toString)
        case "!=" => Some((a != b).toString)
        case _    => None
      case (Lit.String(a), Lit.String(b)) if op == "+" =>
        Some("\"" + escapeJsString(a + b) + "\"")
      case _ => None
