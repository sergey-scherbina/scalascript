package scalascript.codegen

import scalascript.ast.*
import scalascript.transform.{DirectAnorm, DirectTypeUtils, EffectAnalysis}
import scalascript.sql.js.SqlRuntimeJsEmit
import scalascript.codegen.JvmGenStringUtils.*
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
      module:          Module,
      baseDir:         Option[os.Path] = None,
      intrinsics:      Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:        Option[os.Path] = None,
      frontendOverride: Option[String] = None
  ): String =
    // arch-meta-v2 macro-codegen-backends — expand restricted quoted macros to
    // plain code before codegen (no-op for macro-free modules).
    JvmGen(baseDir, intrinsics, lockPath, frontendOverride)
      .genModule(scalascript.artifact.MacroCodegen.expand(module))

  // ─── v2.0 Phase 2 — split-runtime emit ──────────────────────────────────
  //
  // The full `generate` above emits a self-contained Scala 3 script with
  // ~180 KB of runtime preamble baked in front of the user code.  When
  // shipping `.scjvm` artifacts with `classBundle`s, this preamble is
  // compiled into EVERY module's bundle — a 2-module JAR balloons to
  // 500+ KB even though the user code is ~10 KB.
  //
  // The split emit factors the preamble into a separate compilation unit
  // wrapped in `package _ssc_runtime` so that the runtime classes can be
  // compiled once per session and shared across modules.  User modules
  // emit only their own code with `import _ssc_runtime.{*, given}`
  // prepended so the same identifiers (`_show`, `_handle`, `route`, …)
  // resolve at compile time.
  //
  // The textual concat result of `generateRuntime(allCapabilities) +
  // generateUserOnly(module, …)` produces a single source equivalent to
  // the legacy `generate` output, modulo the surrounding `package` block.

  /** Identifier for a runtime capability that the user module depends on.
   *  Drives the `generateRuntime` capability switch and determines which
   *  helper blocks (effects, actors, reactive, dataset, …) are emitted. */
  sealed trait Capability
  object Capability:
    case object Effects     extends Capability  // Free-Monad runtime; gated by `effectOps.nonEmpty`
    case object MutualTco   extends Capability  // mutual tail-call trampoline
    case object Reactive    extends Capability  // Signal / computed / effect
    case object Serve       extends Capability  // REST routing + HTTP / WS server
    case object Mcp         extends Capability  // MCP server / client runtime
    case object Dataset     extends Capability  // Dataset[T] lazy pipeline
    case object Json        extends Capability  // standalone JSON helpers
    case object Reserved    extends Capability  // placeholder for backward-compat union

    val all: Set[Capability] = Set(Effects, MutualTco, Reactive, Serve, Mcp, Dataset, Json)

    /** Encode a capability as a stable, persistence-safe string.
     *  These strings appear in `.scjvm-runtime` envelopes — do not rename. */
    def encode(c: Capability): String = c match
      case Effects   => "effects"
      case MutualTco => "mutual-tco"
      case Reactive  => "reactive"
      case Serve     => "serve"
      case Mcp       => "mcp"
      case Dataset   => "dataset"
      case Json      => "json"
      case Reserved  => "reserved"

    def decode(s: String): Option[Capability] = s match
      case "effects"    => Some(Effects)
      case "mutual-tco" => Some(MutualTco)
      case "reactive"   => Some(Reactive)
      case "serve"      => Some(Serve)
      case "mcp"        => Some(Mcp)
      case "dataset"    => Some(Dataset)
      case "json"       => Some(Json)
      case "reserved"   => Some(Reserved)
      case _            => None

  /** Inspect `module` (parsing imports under `baseDir`) and return the
   *  capability set its emitted code would depend on.  Used by
   *  `compile-jvm --bytecode` to compute the union of capabilities across
   *  all modules before compiling the shared runtime. */
  def detectCapabilities(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None
  ): Set[Capability] =
    JvmGen(baseDir, intrinsics, lockPath).detectCapabilities(module)

  /** Emit the runtime preamble for the given capability set, wrapped in
   *  `package _ssc_runtime`.  No user code is included.
   *
   *  The output is valid Scala 3 source suitable for one-shot compilation
   *  into a classBundle that is shared across modules.  `private` access
   *  modifiers in the preamble are dropped so wildcard-imported user code
   *  can still reach helper names like `_toJson` / `_lookupKey`.
   *
   *  v2.0 Phase 2 — split-runtime emit. */
  def generateRuntime(capabilities: Set[Capability]): String =
    JvmGen(None, Map.empty, None).genRuntime(capabilities)

  /** Emit user code only — no runtime preamble — prepended with
   *  `import _ssc_runtime.{given, *}` so the wildcard-imported helpers
   *  from the shared runtime resolve at compile time.
   *
   *  v2.0 Phase 2 — split-runtime emit. */
  def generateUserOnly(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None,
      preserveTotalEffectfulReturnTypes: Boolean = true
  ): String =
    JvmGen(
      baseDir,
      intrinsics,
      lockPath,
      preserveTotalEffectfulReturnTypes = preserveTotalEffectfulReturnTypes
    ).genUserOnly(scalascript.artifact.MacroCodegen.expand(module))

  /** Emit user code only AND a generated-Scala-line → original-`.ssc`-line
   *  map suitable for JSR-45 SMAP injection.  Returns the same Scala
   *  source as [[generateUserOnly]] (which is a thin wrapper that
   *  discards the map for callers that don't need it).
   *
   *  The map is best-effort, block-granular: each block's emitted line
   *  range maps to a contiguous stretch of original `.ssc` lines
   *  starting at the block's `span.start.line`.  Lines outside any
   *  user block (the `import _ssc_runtime.*` prefix, front-matter
   *  `//> using` lines, route registrations) are NOT in the map —
   *  stack traces resolving against those lines fall through to the
   *  base `LineNumberTable` (the JVM keeps the original line on no
   *  match, which is what users want for the link-emitted preamble
   *  anyway).
   *
   *  v2.0 Phase 4 (Option A) — SMAP line mapping. */
  def generateUserOnlyWithLineMap(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None,
      preserveTotalEffectfulReturnTypes: Boolean = true
  ): (String, Map[Int, Int]) =
    JvmGen(
      baseDir,
      intrinsics,
      lockPath,
      preserveTotalEffectfulReturnTypes = preserveTotalEffectfulReturnTypes
    ).genUserOnlyWithLineMap(scalascript.artifact.MacroCodegen.expand(module))

  /** Block carries its original `.ssc` source-line offset so the emitter
   *  can build a JSR-45 SMAP line map.  `lineOffset` is the 1-based line
   *  in the `.ssc` where the block's `src` content begins (the line
   *  immediately after the opening fence ```scalascript line).  When
   *  unknown (synthesised blocks, imports), `lineOffset` is 0 and the
   *  block contributes no entries to the line map. */
  private[codegen] case class Block(
      node: ScalaNode,
      src: String,
      lineOffset: Int = 0,
      contentSectionIndex: Option[Int] = None
  )
  /** A heading-bound `html` / `css` code block: render to a string in the
   *  same source position as the surrounding parsed blocks, then bind to
   *  `<sectionId>.<lang>` (html or css) at the end of the module. */
  private case class StringBlockEntry(lang: String, src: String, sectionId: String, order: Int)
  private case class ReExportTarget(pkg: List[String], name: String)
  private case class ImportSpec(name: String, alias: Option[String])

class JvmGen(
    private[codegen] val baseDir:  Option[os.Path] = None,
    private[codegen] val intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
    private[codegen] val lockPath: Option[os.Path] = None,
    frontendOverride: Option[String]  = None,
    private val preserveTotalEffectfulReturnTypes: Boolean = true) extends JvmGenBlockAnalysis, JvmGenTermAnalysis, JvmGenMutualRecursion, JvmGenEffectAnalysis, JvmGenRuntimeSources, JvmGenCpsTransform, JvmGenMutualTco, JvmGenPreamble, JvmGenContentEmit:
  import JvmGen.{ImportSpec, ReExportTarget}

  // Effect operations declared in the module, keyed as "Eff.op".
  // `private[codegen]` so the extracted `JvmGenEffectAnalysis` mixin can populate/read them.
  private[codegen] val effectOps     = mutable.Set.empty[String]
  // Functions whose body transitively performs effects; their bodies are
  // emitted in CPS form.
  private[codegen] val effectfulFuns = mutable.Set.empty[String]
  // funName → full set of clique members (including self) for every function
  // that participates in a mutually-recursive tail-call SCC of size ≥ 2.
  private[codegen] val mutualGroups  = mutable.Map.empty[String, Set[String]]
  // funName → its Defn.Def, for clique members — lets the emitter merge a
  // uniform-signature clique into one allocation-free dispatch loop.
  private[codegen] val mutualDefs    = mutable.Map.empty[String, scala.meta.Defn.Def]
  // Resolved paths of files already inlined via Content.Import, so a diamond
  // import doesn't emit the same definitions twice.
  private val importedFiles = mutable.Set.empty[String]
  private[codegen] val importedContentDocuments = mutable.Map.empty[String, List[DocumentContent]]
  // v1.26 — sequential counter driving emitted `_sqlBlock_<N>` value names,
  // and per-section book-keeping so only the first sql block in each section
  // gets the friendly `<sectionId>.sql` alias (Phase 6.C, mirrors Spark
  // Phase C.2 convention).
  private var sqlBlockCounter: Int = 0
  private val sqlPerSection = mutable.Map.empty[String, Int]
  private val lowLevelContentIntrinsicNames: Set[String] = Set(
    "contentDocument",
    "contentCurrentSection",
    "contentSection",
    "contentBlock",
    "contentData",
    "contentMetadata",
    "contentBind",
    "contentPlainText",
    "contentToMarkdown",
    "contentModules",
    "contentModule",
    "contentModuleSection",
    "contentModuleBlock",
    "contentModuleData",
    "contentModuleMetadata"
  )
  private[codegen] val contentToolkitIntrinsicNames: Set[String] = Set(
    "contentToolkitNode",
    "contentToolkitBlock",
    "contentToolkitSection"
  )
  private[codegen] val contentIntrinsicNames: Set[String] = lowLevelContentIntrinsicNames ++ contentToolkitIntrinsicNames
  private var contentRuntimeEnabled: Boolean = false
  private var contentToolkitRuntimeEnabled: Boolean = false
  private var contentSectionIndex: Int = 0
  // v1.30 Phase 4 — @side=client sql blocks collected during collectBlocks;
  // injected into the browser JS bundle for frontend (SPA) modules only.
  // Each entry: (source, dbName, sectionAlias)
  private val clientSqlBlocksList =
    mutable.ListBuffer.empty[(String, Option[String], Option[String])]
  // Maps resolved path → pkg segments so the alias generator can qualify names
  // even when a file was already inlined (diamond import case).
  private val importedPkgs  = mutable.Map.empty[String, List[String]]
  // Maps a package path (e.g. "std.money") → the TYPE-like exported names
  // (capitalised exports: types, enums, case classes/objects) of the module
  // that defines it.  A Markdown import only lists *value* bindings, so a dep
  // that uses a sibling module's TYPE in a signature (e.g. ledger's
  // `: Map[String, Money]`) would have an unresolved `Money` in generated
  // Scala.  The alias generator pulls these in alongside the listed values so
  // the type resolves — mirroring the interpreter, where a dependency's
  // module-level names are merged into the importer's scope.
  private val importedTypeExports = mutable.Map.empty[String, List[String]]
  private val importedExtensionExports = mutable.Map.empty[String, List[String]]
  private val importedReExports = mutable.Map.empty[String, Map[String, ReExportTarget]]

  // ─── Strategy D, Step 2 — dep-mode CPS fixpoint state ─────────────
  //
  // `depDefs` — every `Defn.Def` found inside an inlined dep (top-level
  // or nested in an object/class), keyed by its simple name.
  // `globalEffectfulDeps` — names from `depDefs` whose bodies transitively
  // reach an effect primitive.  Populated by `analyzeDepEffectfulness`
  // after all `inlineImport` calls finish (i.e. after `collectBlocks`
  // returns for the user module).  Consumed by Step 3's emit path.
  //
  // Simple-name keying (no FQN) means two dep defs with the same name
  // in different objects/files would collide.  Acceptable for the
  // current dep corpus (std/mapreduce, std/actors, etc.) — names are
  // unique by convention.  Track FQN promotion as a v2.x follow-up
  // once `.scim` artifacts persist `isEffectful` (see §8.2 of the spec).
  private[codegen] val depDefs = mutable.Map.empty[String, scala.meta.Defn.Def]
  private[codegen] val globalEffectfulDeps = mutable.Set.empty[String]

  // `depClasses` — every `Defn.Class` (incl. case classes) found inside
  // an inlined dep, keyed by simple name.  Used by `emitCpsApply` to
  // resolve constructor calls (`Cluster(nodeList, pids)`) so we can
  // cast `_tN`-named args to the declared field types — otherwise
  // typed constructor signatures reject Any-bound args from the
  // surrounding CPS continuation.  Populated alongside `depDefs` in
  // `inlineImport`.
  private[codegen] val depClasses = mutable.Map.empty[String, scala.meta.Defn.Class]

  // `localDefSigs` — every `Defn.Def` in the USER module being generated (not a
  // dep), keyed by simple name.  Used ONLY by `applyCalleeCasts`/`calleeParamType`
  // to cast Any-bound CPS call args to a callee's declared param type — e.g. a
  // recursive effectful `def go(n: Int): Int ! Log` whose CPS body calls `go(_t3)`
  // with `_t3: Any` (a `_bind` continuation result): the call must read
  // `go(_t3.asInstanceOf[Int])` or scala-cli rejects it (Found Any / Required Int).
  // Kept SEPARATE from `depDefs` (which drives effect-propagation + dep emission)
  // so registering user defs here has no effect-analysis / double-emit side effects.
  // (BUGS.md jvmgen-returnclause-effect-in-recursion.)
  private[codegen] val localDefSigs = mutable.Map.empty[String, scala.meta.Defn.Def]

  // `depTypeNames` — every type-defining decl (Defn.Class, Defn.Trait,
  // Defn.Enum) found in an inlined dep, mapped to its package path.
  // Used by `calleeParamType` to qualify dep type names in injected
  // `asInstanceOf[…]` so they resolve at the user call site even when
  // the user didn't explicitly import that type (e.g. `StageOp` is the
  // sealed trait extended by `MapOp` / `FilterOp` — users only import
  // the concrete cases).
  private[codegen] val depTypeNames = mutable.Map.empty[String, List[String]]
  private[codegen] val declaredVarTypes = mutable.Map.empty[String, String]
  // jvmgen-multishot-handle-result-any: names of vals bound directly to a `handle(...)` expression.
  // `_handle` returns Any, so a 0-arg collection method on such a val (`val all = handle(..); all.sum`)
  // is routed through `_anyCall0` (dynamic dispatch) instead of `all.sum` raw, which Scala 3 rejects.
  private[codegen] val handleResultVals = mutable.Set.empty[String]
  // jvmgen-handle-result-mainpath: Any-taint propagation. Superset of `handleResultVals` that also
  // includes vals bound (no explicit type) to an expression DERIVED from an Any-typed handle result —
  // e.g. `val t = (r, r + 1)` makes `t` an Any-tuple, so `t._1 + t._2` must route through `_binOp`.
  // The routing predicates (`termRefsHandleResultVal`, `termContainsHandleResultArith`) key off this
  // set. Only ever non-empty for effect programs (seeded by `handleResultVals`), so pure code is unaffected.
  private[codegen] val anyTypedVals = mutable.Set.empty[String]
  private def isHandleApp(t: Term): Boolean = t match
    case Term.Apply.After_4_6_0(Term.Apply.After_4_6_0(Term.Name("handle"), _), _) => true
    case _ => false
  private[codegen] val anyCall0Methods = Set(
    "sum", "product", "length", "size", "head", "last", "min", "max",
    "isEmpty", "nonEmpty", "toList", "reverse", "distinct")

  // Test hooks — Step 2 unit tests populate `depDefs` via
  // `seedDepDefsForTest` (which mirrors what `inlineImport` does
  // post-rewrite) and read back `globalEffectfulDeps` after
  // `analyzeDepEffectfulness()` runs.
  private[scalascript] def globalEffectfulDepsForTest: mutable.Set[String] = globalEffectfulDeps
  private[scalascript] def seedDepDefsForTest(module: Module): Unit =
    val blocks = expandMacrosInBlocks(collectBlocks(module.sections))
    blocks.foreach { b =>
      ScalaNode.fold(b.node) { tree =>
        tree.collect { case d: Defn.Def => depDefs(d.name.value) = d }
      }
    }

  // ─── Module entry ─────────────────────────────────────────────────

  /** Module-level `dependencies:` from the front-matter; threaded into
   *  `inlineImport` so `<dep-name>://path` imports rewrite through the
   *  resolver. */
  private[codegen] var moduleDeps: Map[String, String] = Map.empty

  private[codegen] def isContentStdImport(path: String): Boolean =
    path == "std/content.ssc" || path.endsWith("std/content.ssc")

  private[codegen] def isContentToolkitStdImport(path: String): Boolean =
    path == "std/ui/content.ssc" || path.endsWith("std/ui/content.ssc")

  private[codegen] def isContentHelperImport(path: String): Boolean =
    isContentStdImport(path) || isContentToolkitStdImport(path)

  def genModule(module: Module): String =
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    contentRuntimeEnabled = moduleUsesContentIntrinsics(module)
    contentToolkitRuntimeEnabled = moduleUsesContentToolkitIntrinsics(module)
    if contentRuntimeEnabled then collectDirectImportedContent(module) else importedContentDocuments.clear()
    contentSectionIndex = 0
    // Collect blocks first — including those pulled in by `[..](./x.ssc)`
    // imports — so the effect / mutual-TCO analysis sees the full picture.
    val blocks = expandMacrosInBlocks(collectBlocks(module.sections, module.document.map(_.sections).getOrElse(Nil)))
    // Strategy D, Step 2 — fixpoint over the dep call graph runs AFTER
    // collectBlocks (all `inlineImport` calls have populated `depDefs`)
    // and BEFORE emit so Step 3 can consult `globalEffectfulDeps`.
    analyzeDepEffectfulness()
    analyzeEffects(blocks)
    analyzeMutualRecursion(blocks)
    indexLocalDefSigs(blocks)
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

    // backend-blocks-p3: emit `java` fenced blocks as separate .java source files.
    // Each block is written to `_ssc_java_<n>.java` next to the .ssc source (when
    // baseDir is set) and referenced via `//> using sources` so scala-cli compiles it.
    javaBlocks.zipWithIndex.foreach { (src, i) =>
      val fname = s"_ssc_java_$i.java"
      sb.append(s"""//> using sources "$fname"\n""")
      baseDir.foreach { dir => os.write.over(dir / fname, src) }
    }

    val frontmatterRoutes = module.manifest.toList.flatMap(_.routes)
    val apiClients = module.manifest.toList.flatMap(_.apiClients)
    val objectStores = module.manifest.toList.flatMap(_.objectStores).filter(s => s.sync == "client-server" && s.valueType.nonEmpty)
    val graphStores = module.manifest.toList.flatMap(_.graphs)
    val frontendFramework = module.manifest.flatMap(_.frontendFramework)
    val effectiveFrontend = frontendOverride.orElse(frontendFramework)
    val usesObjectStore = blocksUseObjectStore(blocks)
    val usesGraph = blocksUseGraph(blocks) || graphStores.nonEmpty
    val usesHttpServer =
      effectOps.nonEmpty || blocksUseRoutes(blocks) || frontmatterRoutes.nonEmpty ||
      objectStores.nonEmpty || blocksUseJson(blocks) || blocksUseMcp(blocks) ||
      effectiveFrontend.isDefined || blocksUseOutboundHttp(blocks)
    if blocksUseMcp(blocks) then
      sb.append(s"""//> using dep "$JvmMcpDep"\n""")
    val usesTypedData = blocksUseTypedData(blocks)
    val usesWire = blocksUseWire(blocks) || usesTypedData || apiClients.nonEmpty
    if usesWire then
      sb.append(sscJarDirective("scalascript-wire-core"))
    if usesTypedData then
      sb.append(sscJarDirective("scalascript-backend-typed-data-runtime"))
    if usesGraph then
      sb.append(sscJarDirective("scalascript-backend-typed-data-runtime"))
      sb.append(sscJarDirective("scalascript-backend-graph-runtime"))
      graphRuntimeDeps(graphStores).foreach(dep => sb.append(s"""//> using dep "$dep"\n"""))

    // Frontend SPA — pull in the frontend-core + framework-specific JARs so
    // the UI primitives can reference scalascript.frontend.* types at runtime.
    val nativeModels = module.manifest.fold(Nil)(_.models)
    if effectiveFrontend.isDefined then
      sb.append(sscJarDirective("scalascript-frontend-core"))
      if nativeModels.nonEmpty then
        sb.append(sscJarDirective("scalascript-core"))
      if effectiveFrontend.contains("swing") then
        sb.append(sscJarDirective("scalascript-backend-spi"))
        sb.append(sscJarDirective("scalascript-frontend-javafx"))
        val jfxOs = javafxOs
        sb.append(s"""//> using dep "org.openjfx:javafx-controls:$javafxEmitVersion:$jfxOs"\n""")
        sb.append(s"""//> using dep "org.openjfx:javafx-base:$javafxEmitVersion:$jfxOs"\n""")
        sb.append(s"""//> using dep "org.openjfx:javafx-graphics:$javafxEmitVersion:$jfxOs"\n""")
        if apiClients.nonEmpty then
          sb.append(sscJarDirective("scalascript-backend-typed-data-runtime"))
      if effectiveFrontend.contains("javafx") then
        sb.append(sscJarDirective("scalascript-backend-spi"))
        sb.append(sscJarDirective("scalascript-frontend-swing"))
        if apiClients.nonEmpty then
          sb.append(sscJarDirective("scalascript-backend-typed-data-runtime"))
        val jfxOs = javafxOs
        sb.append(s"""//> using dep "org.openjfx:javafx-controls:$javafxEmitVersion:$jfxOs"\n""")
        sb.append(s"""//> using dep "org.openjfx:javafx-base:$javafxEmitVersion:$jfxOs"\n""")
        sb.append(s"""//> using dep "org.openjfx:javafx-graphics:$javafxEmitVersion:$jfxOs"\n""")
      val fwLib = effectiveFrontend.getOrElse("react")
      sb.append(sscJarDirective(s"scalascript-frontend-$fwLib"))

    // v1.26 — JDBC runtime + bundled H2/SQLite drivers.  Emitted only
    // when the module actually contains sql blocks or server ObjectStore calls;
    // modules without JDBC-backed APIs don't pull these onto their scala-cli classpath.
    if sqlBlockCounter > 0 || usesObjectStore || objectStores.nonEmpty then
      sb.append("""//> using dep "com.lihaoyi::ujson:4.4.2"""" + "\n")
      sb.append("""//> using dep "com.h2database:h2:2.4.240"""" + "\n")
      sb.append("""//> using dep "org.xerial:sqlite-jdbc:3.53.1.0"""" + "\n")
      sb.append(sscJarDirective("scalascript-backend-typed-data-runtime"))
      sb.append(sscJarDirective("scalascript-backend-sql-runtime"))

    // User-name-aware preamble: the `object Console` println-shadow is
    // omitted when the module defines its own `Console` (e.g. an effect).
    val userTopNames = collectUserTopNames(blocks)
    val userShadowsHttpModel = !usesHttpServer &&
      Set("Request", "Response", "StreamResponse").exists(userTopNames.contains)
    sb.append(JvmRuntimePreamble.sourceFor(userTopNames))
    sb.append(if userShadowsHttpModel then commonRuntimeWithoutHttpModel else commonRuntime)
    sb.append(generatorRuntime)
    sb.append(fsRuntime)
    // std.process exec + ProcessOptions/ProcessResult — supplied by the runtime
    // (not inlined from the import). Unconditional like fsRuntime; also appended
    // to the shared `_ssc_runtime` (genRuntime) for the split --bytecode path.
    sb.append(processRuntime)
    sb.append(htmlDslTagBindings(userTopNames))
    // commonRuntime inlines Logger, whose effect-style static methods call
    // _perform. Keep the effect runtime available even for route-only modules
    // that do not otherwise use explicit effects.
    if userShadowsHttpModel then
      sb.append(nonServerHttpModelStubs)
      sb.append(nonServerHttpModelRefs(effectsRuntime))
    else
      sb.append(effectsRuntime)
    // arch-meta-v2-p5 Track A (A1a/A1b/A1c) — `derives` synthesis on the JVM.
    // Strip every HANDLED `derives` clause (custom user typeclasses + the stdlib
    // structural four Eq/Show/Hash/Order) from the blocks up front so the emitted
    // classes don't carry a clause scalac can't satisfy; the per-type Mirror
    // givens and the synthesized `given TC[T]` are appended after the user blocks
    // below.  The Mirror runtime is emitted when the module references `Mirror`
    // or has a custom derive; the structural-derives runtime when any stdlib
    // structural derive is present.
    val userDerivableTCs = userDerivableTypeclasses(blocks)
    val handledTCs       = userDerivableTCs ++ stdlibStructuralTCs
    val handledDerives   = scala.collection.mutable.LinkedHashMap.empty[String, List[String]]
    val emitBlocksList   = blocks.map(b => stripHandledDerives(b, handledTCs, handledDerives))
    val hasCustomDerive  = handledDerives.values.exists(_.exists(userDerivableTCs.contains))
    val hasStdlibDerive  = handledDerives.values.exists(_.exists(stdlibStructuralTCs.contains))
    val emitMirror = blocksUseMirror(blocks) || hasCustomDerive
    if emitMirror then sb.append(mirrorRuntime)
    if hasStdlibDerive then sb.append(mirrorStructRuntime)
    if mutualGroups.nonEmpty                               then sb.append(JvmRuntimeMutualTco.source)
    // SwiftUI builds get a dedicated reactive preamble (ReactiveSignal-based)
    // in uiHelperFunctions instead of the JVM-only Signal runtime.
    if blocksUseReactive(blocks) && !effectiveFrontend.contains("swiftui") then sb.append(reactiveRuntime)
    // serveRuntime is also emitted when MCP is used so that `serveMcp(Transport.Http|Ws(...))`
    // can drive the JVM HTTP+WS server via route() / onWebSocket() / serve() instead of
    // throwing "not yet supported".  See JvmRuntimeMcp serveMcp(Transport.Http/Ws) arms.
    if usesHttpServer then sb.append(serveRuntime)
    else sb.append(if userShadowsHttpModel then nonServerHttpModelRefs(stubServeRuntime) else stubServeRuntime)
    if blocksUseMcp(blocks)                                                          then sb.append(JvmRuntimeMcp)
    if blocksUseDataset(blocks)                                                      then sb.append(JvmRuntimeDataset)
    if contentRuntimeEnabled                                                         then sb.append(emitContentRuntime(module.document, contentToolkitRuntimeEnabled))

    // Front-matter route declarations are emitted as `route(method, path)
    // { req => handler(req) }` calls.  We place them BEFORE the user blocks
    // because the user's `serve(port)` typically appears as the last
    // statement of their script and blocks forever — registrations
    // afterwards would never run.  Forward references to the handler defs
    // work because `.sc` script files wrap all top-level defs as methods
    // of an enclosing class, so they're accessible throughout the body.
    val apiResponseTypes = openApiResponseTypes(apiClients)
    frontmatterRoutes.foreach { r =>
      emitRouteRegistration(sb, r.method, r.path, r.handler, apiResponseTypes)
    }
    if frontmatterRoutes.nonEmpty then sb.append("\n")
    val jvmClassFields = jvmCaseClassFieldsInBlocks(blocks)
    val jvmClientWarnings = apiClients.flatMap(c =>
      c.endpoints.flatMap(e => jvmEndpointPathWarnings(c.name, e, jvmClassFields))
    )
    jvmClientWarnings.foreach { w =>
      System.err.println(s"[ssc warning] $w")
      sb.append(s"// [ssc warning] $w\n")
    }
    emitTypedRouteClientMetadata(apiClients, sb)
    if effectiveFrontend.contains("swing") || effectiveFrontend.contains("javafx") then
      emitSwingTypedRouteClients(apiClients, sb)
    collectDeclaredVarTypes(blocks)

    // i18n table injection — emitted once before user blocks so t(key) resolves correctly.
    module.manifest.foreach { m =>
      if m.translations.nonEmpty then
        val entries = m.translations.map { (locale, kvs) =>
          val pairs = kvs.map { (k, v) =>
            val ek = k.replace("\\", "\\\\").replace("\"", "\\\"")
            val ev = v.replace("\\", "\\\\").replace("\"", "\\\"")
            s""""$ek" -> "$ev""""
          }.mkString(", ")
          s""""$locale" -> Map($pairs)"""
        }.mkString(", ")
        sb.append(s"_i18nTable = Map($entries)\n\n")
    }

    // v1.26 Phase 6.C — JDBC connection registry + resolver helper.
    // Emitted only when the module actually has sql blocks or server
    // ObjectStore calls; modules that don't use JDBC-backed APIs pay nothing.
    if sqlBlockCounter > 0 || usesObjectStore || objectStores.nonEmpty then
      sb.append(emitSqlRegistry(module.manifest.toList.flatMap(_.databases)))
    if objectStores.nonEmpty then
      emitObjectStoreSyncRoutes(objectStores, sb)
    if usesGraph then
      sb.append(emitGraphRegistry(graphStores))

    // v1.30 Phase 4 — browser JS for @side=client sql blocks.  Always
    // emitted in frontend modules so uiHelperFunctions can reference
    // _ssc_client_sql_js unconditionally; empty string when no client blocks.
    if effectiveFrontend.isDefined then
      val clientJs = emitClientSqlJs(module)
      sb.append("val _ssc_client_sql_js: String = ")
      sb.append(scalaStringLiteral(clientJs))
      sb.append("\n\n")

    // Mark the boundary between fixed preamble and user-generated code.
    // colonObjectsToBraces / hoistSscImportsIntoObjectStd / mergeDuplicatePackageObjects
    // must only run on the user section — the preamble uses colon-style objects like
    // `object Actor:` intentionally and must NOT be rewritten.
    val preambleLen = sb.length

    emitBlocksList.foreach { block =>
      sb.append(emitBlock(block).stripTrailing())
      sb.append("\n\n")
    }

    // arch-meta-v2-p5 Track A — per-product-type Mirror givens (A1a) and
    // synthesized `given TC[T]` (A1b custom + A1c stdlib structural), appended
    // after the user case-class definitions.  Givens are lazy, so a forward
    // `summon[Mirror.Of[T]]` / `summon[TC[T]]` earlier in the script resolves
    // and initialises correctly.
    if emitMirror then
      val givens = mirrorProductGivens(blocks)
      if givens.nonEmpty then
        sb.append("// arch-meta-v2-p5 Track A — derived Mirror givens\n")
        givens.foreach { g => sb.append(g); sb.append("\n") }
        sb.append("\n")
    val derivesGivens = derivesGivensFor(handledDerives, userDerivableTCs)
    if derivesGivens.nonEmpty then
      sb.append("// arch-meta-v2-p5 Track A — synthesized derives givens\n")
      derivesGivens.foreach { g => sb.append(g); sb.append("\n") }
      sb.append("\n")

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
    // SwiftUI builds: auto-add serve(view(), 0) when def view() exists and
    // no explicit main: is set (serve reads ssc.build.outdir/-platform JVM props).
    if effectiveFrontend.contains("swiftui") && mainEntry.isEmpty then
      val hasViewDef = blocks.exists { b =>
        ScalaNode.fold(b.node) { tree =>
          tree.collect { case d: Defn.Def if d.name.value == "view" => () }.nonEmpty
        }
      }
      if hasViewDef then sb.append("serve(view(), 0)\n")

    val fixedHead = sb.substring(0, preambleLen)
    // jvm-lazylist-fusion: fuse bounded LazyList.from(s).map(f)?.take(n).sum pipelines into native
    // loops in the USER code only (parsing just the user slice, not the ~7k-line preamble).
    val userSrc   = fuseLazyListInSource(sb.substring(preambleLen))
    // Inject UI helper functions (top-level) + primitives object block when
    // the module uses a frontend framework.  Helpers are prepended so they're
    // defined before the `import std.ui.primitives.{serve,...}` line and can
    // therefore call the preamble's `serve(port)` without the import shadowing
    // it.  The primitives colon-block is appended so `mergeDuplicatePackageObjects`
    // merges it with the existing (extern-filtered, empty) object from primitives.ssc.
    val appIcon = module.manifest
      .flatMap(m => m.raw.get("app-icon").orElse(m.raw.get("appIcon")))
      .collect { case s: String => s }
    val nativeBundleId   = module.manifest.flatMap(_.raw.get("bundle-id").collect { case s: String => s })
    val nativeDisplayName = module.manifest.flatMap(_.name)
    val nativeVersion    = module.manifest.flatMap(_.version)
    val withUi =
      if effectiveFrontend.isDefined then
        val webPrimitives = "\n" + JvmRuntimeUiPrimitives.source
        uiHelperFunctions(
          effectiveFrontend.getOrElse("react"), appIcon,
          bundleId = nativeBundleId, displayName = nativeDisplayName, version = nativeVersion,
          models = nativeModels
        ) + "\n" + userSrc + webPrimitives
      else userSrc
    val braced    = colonObjectsToBraces(withUi).stripTrailing()
    val hoisted   = hoistSscImportsIntoObjectStd(braced)
    val merged    = mergeDuplicatePackageObjects(hoisted)
    fixedHead + merged + "\n"

  /** Merge multiple `object X { object Y { ... } }` declarations sharing
   *  the same outer chain into one — needed when several inlined imports
   *  all declare `package: X.Y` and would otherwise produce duplicate
   *  top-level `object X` blocks that Scala 3 refuses to compile.
   *
   *  Uses scala.meta to walk the source: collect top-level `Defn.Object`
   *  by name, merge their bodies pairwise (recursively for matching
   *  inner objects), emit the merged form back. Non-object top-level
   *  statements pass through unchanged in their original order. */
  /** Convert column-0 colon-indent `object NAME:` blocks to brace-style
   *  `object NAME { … }` so that [[mergeDuplicatePackageObjectsOnce]] can
   *  see and merge them.  Recursively descends into each block's body so
   *  nested `object ui:` / `object nodes:` chains are also converted.
   *
   *  Only exact `object NAME:` lines (colon immediately follows the name,
   *  nothing else on the line) at the current column-0 level are touched;
   *  arbitrary inner braces / definitions are passed through unchanged.
   *  Body collection tracks triple-quoted strings because generated/imported
   *  UI modules can contain JavaScript literals whose content starts at
   *  column 0; those lines are still part of the object body. */
  // Resolve an io.scalascript artifact to an absolute //> using jar path so that
  // scala-cli/Coursier never tries Maven Central for internal packages.
  // Falls back to //> using dep if the jar directory cannot be found.
  private def sscJarDirective(artifactBase: String): String =
    import scala.jdk.CollectionConverters.*
    def findIn(dir: java.nio.file.Path): Option[java.nio.file.Path] =
      if java.nio.file.Files.isDirectory(dir) then
        val stream = java.nio.file.Files.list(dir)
        try
          stream.iterator.asScala
            .find(p =>
              val name = p.getFileName.toString
              name.startsWith(s"${artifactBase}_") && name.endsWith(".jar") && !name.endsWith("-tests.jar")
            )
        finally stream.close()
      else None
    def findInDevTree(root: java.nio.file.Path): Option[java.nio.file.Path] =
      if java.nio.file.Files.isDirectory(root) then
        val stream = java.nio.file.Files.walk(root, 7)
        try
          stream.iterator.asScala
            .filterNot(p => p.toString.contains(s"${java.io.File.separator}target${java.io.File.separator}bg-jobs${java.io.File.separator}"))
            .filter(p =>
              val name = p.getFileName.toString
              name.startsWith(s"${artifactBase}_") && name.endsWith(".jar") && !name.endsWith("-tests.jar")
            )
            .toVector
            .sortBy(_.toString)
            .headOption
        finally stream.close()
      else None
    val libPath = Option(System.getProperty("ssc.lib.path"))
    val installed = libPath.flatMap(path => findIn(java.nio.file.Paths.get(path, "bin", "lib", "jars")))
    // Locate jars/ relative to the running jar — works for `java -jar ssc.jar`
    // from any CWD, since the dep jars live next to ssc.jar in bin/lib/jars/.
    val jarLocal = Option(this.getClass.getProtectionDomain).flatMap(pd => Option(pd.getCodeSource))
      .flatMap(cs => Option(cs.getLocation))
      .flatMap(url => scala.util.Try(java.nio.file.Paths.get(url.toURI).getParent).toOption)
      .flatMap(dir => findIn(dir.resolve("jars")))
    val cwd = java.nio.file.Paths.get(".").toAbsolutePath.normalize()
    val staged = findIn(cwd.resolve("bin").resolve("lib").resolve("jars"))
    val devTarget = findInDevTree(cwd)
    val found = installed.orElse(jarLocal).orElse(staged).orElse(devTarget)
    found
      .map(p => s"""//> using jar "${p.toAbsolutePath}"\n""")
      .getOrElse(s"""//> using dep "io.scalascript::$artifactBase:0.1.0-SNAPSHOT"\n""")

  private def colonObjectsToBraces(src: String): String =
    val lines    = src.split('\n')
    val n        = lines.length
    val result   = new StringBuilder(src.length)
    var i        = 0
    val ColonObj = "^(object \\w+):$".r
    def togglesTripleString(line: String): Boolean =
      var idx   = line.indexOf("\"\"\"")
      var count = 0
      while idx >= 0 do
        count += 1
        idx = line.indexOf("\"\"\"", idx + 3)
      (count % 2) == 1
    while i < n do
      lines(i) match
        case ColonObj(header) =>
          // Collect body: all lines that are blank OR indented (start with space/tab).
          i += 1
          val body = new StringBuilder()
          var inTripleString = false
          while i < n && (
              inTripleString ||
              lines(i).isEmpty ||
              lines(i).charAt(0) == ' ' ||
              lines(i).charAt(0) == '\t'
            ) do
            body.append(lines(i)).append('\n')
            if togglesTripleString(lines(i)) then inTripleString = !inTripleString
            i += 1
          // Strip trailing blank lines so the closing `}` lands cleanly.
          var bodyStr = body.toString
          while bodyStr.endsWith("\n\n") do bodyStr = bodyStr.dropRight(1)
          // Dedent by 2 spaces, then recurse to convert nested colon objects.
          val dedented = bodyStr.linesWithSeparators.map(l => if l.startsWith("  ") then l.drop(2) else l).mkString
          val inner    = colonObjectsToBraces(dedented.stripTrailing)
          // Re-indent the converted body back to 2 spaces.
          val reindented = inner.linesWithSeparators.map { l =>
            if l.forall(_.isWhitespace) then l else "  " + l
          }.mkString.stripTrailing
          result.append(header).append(" {\n")
          result.append(reindented).append('\n')
          result.append("}\n")
        case line =>
          result.append(line).append('\n')
          i += 1
    result.toString.stripTrailing

  /** Brace-balanced scanner. Walks `src` looking for top-level `object X { … }`
   *  blocks (column-0 `object` keyword); groups by name; merges bodies of
   *  same-name blocks into the FIRST occurrence and removes the rest.
   *  Plain string-level — scala.meta can't parse the 300KB+ emitted source.
   *  Brace counting respects ordinary strings, triple-quoted strings, and
   *  comments; imported UI modules often contain JavaScript/CSS triple
   *  literals with their own braces. */
  private def mergeDuplicatePackageObjects(src: String): String =
    // First merge top-level (column-0) duplicates; then recursively
    // merge inside each merged outer object's body so a collapsed
    // `object std { object mapreduce { … } object mapreduce { … } }`
    // becomes `object std { object mapreduce { merged-body } }`.
    val merged = mergeDuplicatePackageObjectsOnce(src)
    if merged == src then src
    else mergeInsideObjects(merged)

  /** After a top-level merge, walk each `object X { … }` block at
   *  column 0 and recursively re-run the merger inside its body, with
   *  the body's leading 2-space indent stripped and re-applied. This
   *  lets nested duplicates (`  object mapreduce { … }` siblings) get
   *  detected by the same column-0 anchor logic. */
  private def mergeInsideObjects(src: String): String =
    val n = src.length
    val out = new StringBuilder(n)
    var i = 0
    while i < n do
      val atCol0 = i == 0 || src.charAt(i - 1) == '\n'
      if atCol0 && src.startsWith("object ", i) then
        var j = i + "object ".length
        while j < n && (src.charAt(j).isLetterOrDigit || src.charAt(j) == '_') do j += 1
        while j < n && src.charAt(j) == ' ' do j += 1
        if j < n && src.charAt(j) == '{' then
          val k = findMatchingBraceEnd(src, j)
          // Emit header + brace
          out.append(src.substring(i, j + 1))
          // Recursively merge inside body. Strip leading 2-space indent
          // so nested object decls appear at column 0 for the merger.
          val bodyRaw    = src.substring(j + 1, k - 1)
          val unindented = bodyRaw.linesWithSeparators.map { l =>
            if l.startsWith("  ") then l.drop(2) else l
          }.mkString
          val mergedBody = mergeDuplicatePackageObjects(unindented)
          // Re-indent.
          val reIndented = mergedBody.linesWithSeparators.map { l =>
            if l.trim.isEmpty then l else "  " + l
          }.mkString
          out.append(reIndented)
          out.append('}')
          i = k
        else
          out.append(src.charAt(i)); i += 1
      else
        out.append(src.charAt(i)); i += 1
    out.toString

  private def findMatchingBraceEnd(src: String, openBrace: Int): Int =
    val n = src.length
    var depth      = 1
    var k          = openBrace + 1
    var inStr      = false
    var inTriple   = false
    var inLnCmt    = false
    var inBlockCmt = false
    while k < n && depth > 0 do
      val c = src.charAt(k)
      if inLnCmt then
        if c == '\n' then inLnCmt = false
      else if inBlockCmt then
        if c == '*' && k + 1 < n && src.charAt(k + 1) == '/' then
          inBlockCmt = false
          k += 1
      else if inTriple then
        if src.startsWith("\"\"\"", k) then
          inTriple = false
          k += 2
      else if inStr then
        if c == '\\' && k + 1 < n then k += 1
        else if c == '"' then inStr = false
      else if src.startsWith("\"\"\"", k) then
        inTriple = true
        k += 2
      else
        c match
          case '"' => inStr = true
          case '/' if k + 1 < n && src.charAt(k + 1) == '/' =>
            inLnCmt = true
            k += 1
          case '/' if k + 1 < n && src.charAt(k + 1) == '*' =>
            inBlockCmt = true
            k += 1
          case '{' => depth += 1
          case '}' => depth -= 1
          case _   => ()
      k += 1
    k

  /** After `colonObjectsToBraces`, alias import blocks like
   *  `import std.ui.nodes.{TkNode,...}` end up between (or after) the
   *  `object std { ... }` blocks they were generated alongside.  Once
   *  `mergeDuplicatePackageObjects` merges all `object std` blocks into ONE,
   *  those imports land AFTER the merged block's closing `}` — outside the
   *  scope of nested `object lower { def lower(n: TkNode,...) }`.
   *
   *  Fix: hoist type-like names (first character uppercase) from top-level
   *  `import std.*` lines to the start of `object std`'s body.
   *  Convert `import std.X.Y.{A,B}` → `import X.Y.{A,B}` (drop `std.`)
   *  since the injection site is already inside `object std`.
   *
   *  Lowercase value-level names (functions/vals such as `mapRight` or
   *  `serve`) are left in place — those are only needed by user code at the
   *  file level, while nested std package code needs the type-like names.
   *
   *  Additionally: if `object std.ui.primitives` is present in the output
   *  (std/ui is in use), inject `import ui.primitives.{Signal, View,
   *  EventHandler}` at the start of `object std` so opaque type annotations
   *  in dep code (e.g. `: View` return in `object lower`) resolve correctly.
   *
   *  After this pass, `mergeDuplicatePackageObjects` sees the type imports as
   *  body content of the first `object std` block and keeps them there,
   *  making TkNode / Theme / View / etc. visible inside all nested objects. */
  private def hoistSscImportsIntoObjectStd(src: String): String =
    val lines = src.split('\n')
    val n     = lines.length
    // Find the first column-0 "object std {" line.
    val firstStdObj = lines.indexWhere { l =>
      l == "object std {" || l.startsWith("object std {")
    }
    if firstStdObj < 0 then return src

    // Collect uppercase specs from `import std.*` lines. Mixed imports such
    // as `{Either, Left, Right, mapRight}` still need `Either` visible inside
    // `object std`, but the lowercase helpers must remain file-level imports.
    def uppercaseImportSpecs(line: String): List[String] =
      val lb = line.lastIndexOf('{')
      val rb = line.lastIndexOf('}')
      if lb < 0 || rb <= lb then Nil
      else
        val namesPart = line.substring(lb + 1, rb)
        namesPart.split(",").map(_.trim).filter(_.nonEmpty).toList.filter { spec =>
          val target = if spec.contains(" as ") then spec.substring(0, spec.indexOf(" as ")).trim else spec
          target.headOption.exists(_.isUpper)
        }

    val importBuf    = scala.collection.mutable.ListBuffer.empty[String]
    val removedLines = scala.collection.mutable.Set.empty[Int]
    for idx <- firstStdObj + 1 until n do
      val l = lines(idx)
      if l.startsWith("import std.") && !l.startsWith("import std.{") then
        val specs = uppercaseImportSpecs(l)
        if specs.nonEmpty then
          val lb = l.lastIndexOf('{')
          val relativeHead = "  " + l.substring(0, lb + 1).replaceFirst("import std\\.", "import ")
          importBuf += (relativeHead + specs.mkString(", ") + "}")

    // Inject std/ui opaque type aliases at the start if std.ui.primitives is present.
    // Replace any partial ui.primitives.{ import (types-only) with the full one.
    val hasPrimitivesObj = lines.exists(l => l.trim == "object primitives {" || l.contains("object primitives {"))
    if hasPrimitivesObj then
      importBuf.filterInPlace(!_.contains("ui.primitives.{"))
      // NOTE: "Signal" (capitalized) is deliberately NOT imported here — it is not a
      // member of ui.primitives (JvmRuntimeUiPrimitives only exports lowercase `signal`).
      // It's a SEPARATE, unrelated top-level `type Signal[A] = ReactiveSignal[A]` alias
      // emitted only for the swiftui-native convenience DSL (JvmGenPreamble's frontendName
      // == "swiftui" branch), already visible without import when present. Importing it
      // from here previously threw "value Signal is not a member of ... ui.primitives".
      // Full list of JvmRuntimeUiPrimitives.source's actual exports (kept in sync by hand;
      // this had silently drifted — forKeyedView/selectFromView/seedSignal/emptyHeaders/fetchActionTo/
      // fetchCaptureAction/rowEditAction were missing, so any real .ssc using dynamic
      // forKeyed or those less-common primitives hit "Not found" on this import alone).
      importBuf.prepend("  import ui.primitives.{View, EventHandler, signal, seedSignal, componentScope, element, textNode, signalText, showSignal, fragment, forKeyedView, selectFromView, setSignal, inputChange, toggleSignal, eqSignal, hashSignal, emptyHeaders, emit, serve, fetchUrlSignal, fetchRowsSource, fetchAction, fetchActionTo, incSignal, fetchActionClear, fetchCaptureAction, fieldColumn, fieldPayload, wholeRowPayload, fieldsPayload, rowDeleteAction, rowPostAction, rowLinkAction, rowEditAction, dataTableView}")

    if importBuf.isEmpty && !hasPrimitivesObj then return src

    // Reassemble: copy all lines, skip removed ones, inject hoisted imports
    // as the first lines of object std's body (right after firstStdObj).
    val out = new StringBuilder(src.length)
    for idx <- 0 until n do
      val l = lines(idx)
      if removedLines.contains(idx) then
        ()  // dropped — will appear inside object std instead
      else
        out.append(l).append('\n')
        if idx == firstStdObj then
          importBuf.distinct.foreach { il => out.append(il).append('\n') }
    out.toString.stripTrailing

  private def mergeDuplicatePackageObjectsOnce(src: String): String =
    case class Block(name: String, indent: Int, start: Int, end: Int, headerEnd: Int, bodyEnd: Int)
    val blocks = scala.collection.mutable.ListBuffer.empty[Block]
    val n = src.length
    var i = 0
    while i < n do
      // `object` is recognised at the start of a line (column 0 or any
      // indent). Capture the leading indent so we group merges by depth.
      val atLineStart = i == 0 || src.charAt(i - 1) == '\n'
      // Skip past indent whitespace to find the `object` keyword.
      var skipped = 0
      while atLineStart && i + skipped < n && (src.charAt(i + skipped) == ' ' || src.charAt(i + skipped) == '\t') do
        skipped += 1
      val objStart = i + skipped
      if atLineStart && objStart < n && src.startsWith("object ", objStart) then
        // Parse "object <Name> {"
        var j = objStart + "object ".length
        val nameStart = j
        while j < n && (src.charAt(j).isLetterOrDigit || src.charAt(j) == '_') do j += 1
        val name = src.substring(nameStart, j)
        while j < n && src.charAt(j) == ' ' do j += 1
        if j < n && src.charAt(j) == '{' then
          // Brace-match to find the closing }.
          val k = findMatchingBraceEnd(src, j)
          blocks += Block(name, skipped, i, k, j + 1, k - 1)  // [start, end), body = (headerEnd, bodyEnd]
          i = k
        else i = j
      else i += 1

    // Group by (indent, name) so we only merge SIBLING duplicates — a
    // nested object only collides with another at the same indent.
    val grouped = blocks.groupBy(b => (b.indent, b.name)).filter(_._2.size > 1)
    if grouped.isEmpty then return src

    val toDrop  = scala.collection.mutable.Set.empty[Int]
    val mergeInto = scala.collection.mutable.Map.empty[Int, StringBuilder]
    for (_, group) <- grouped do
      val first = group.head
      val sb    = new StringBuilder
      val firstBodyText = src.substring(first.headerEnd, first.bodyEnd).stripTrailing()
      var accumulated   = firstBodyText
      group.tail.foreach { b =>
        // Inner body text, trimmed of leading/trailing whitespace and outer
        // braces (we keep the original first block's outer braces).
        val body = src.substring(b.headerEnd, b.bodyEnd).stripTrailing()
        // Skip duplicate bodies (same content already present from an earlier inline).
        if body.nonEmpty && !accumulated.contains(body.trim) then
          sb.append("\n").append(body)
          accumulated += "\n" + body
        toDrop += b.start  // identify by start position
      }
      mergeInto(first.start) = sb

    // Reassemble: walk blocks in order; for each block keep it (with merge
    // appended for the first occurrence), or skip it (subsequent dupes).
    val out = new StringBuilder
    var cursor = 0
    for b <- blocks.sortBy(_.start) do
      if toDrop.contains(b.start) then
        // Emit prefix up to b.start, then skip the block. Trim a trailing
        // blank line if present so we don't leave a gap.
        out.append(src.substring(cursor, b.start))
        // Trim trailing whitespace from out so we don't accumulate blanks.
        while out.nonEmpty && (out.last == ' ' || out.last == '\n') do out.setLength(out.length - 1)
        out.append('\n').append('\n')
        cursor = b.end
      else
        mergeInto.get(b.start) match
          case Some(extra) if extra.nonEmpty =>
            // Emit prefix + block-up-to-closing-brace + appended bodies + closing brace.
            out.append(src.substring(cursor, b.bodyEnd))
            out.append(extra)
            out.append(src.substring(b.bodyEnd, b.end))
            cursor = b.end
          case _ => () // leave cursor; the block will be copied by the trailing append below
    out.append(src.substring(cursor))
    out.toString

  // ─── v2.0 Phase 2 — split-runtime emit helpers ──────────────────────────

  /** Capability detection — mirrors the gating predicates used inside
   *  `genModule` (effects, mutual TCO, reactive, …) but returns a stable
   *  `Set[Capability]` instead of toggling internal flags. */
  def detectCapabilities(module: Module): Set[JvmGen.Capability] =
    import JvmGen.Capability.*
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    val blocks = expandMacrosInBlocks(collectBlocks(module.sections))
    analyzeDepEffectfulness()
    analyzeEffects(blocks)
    analyzeMutualRecursion(blocks)
    val frontmatterRoutes = module.manifest.toList.flatMap(_.routes)
    val caps = scala.collection.mutable.Set.empty[JvmGen.Capability]
    if effectOps.nonEmpty                                  then caps += Effects
    if mutualGroups.nonEmpty                               then caps += MutualTco
    if blocksUseReactive(blocks)                           then caps += Reactive
    if effectOps.nonEmpty || blocksUseRoutes(blocks) ||
       frontmatterRoutes.nonEmpty || blocksUseJson(blocks) ||
       blocksUseMcp(blocks) || blocksUseOutboundHttp(blocks) then caps += Serve
    if blocksUseMcp(blocks)                                then caps += Mcp
    if blocksUseDataset(blocks)                            then caps += Dataset
    if blocksUseJson(blocks)                               then caps += Json
    caps.toSet

  /** Emit the runtime preamble wrapped in `package _ssc_runtime`, gated by
   *  `capabilities`.  The contents mirror the gating logic of `genModule`
   *  but emit a self-contained Scala 3 file with no user code. */
  def genRuntime(capabilities: Set[JvmGen.Capability]): String =
    import JvmGen.Capability.*
    val body = StringBuilder()
    body.append(JvmRuntimePreamble.source)
    body.append(commonRuntime)
    body.append(generatorRuntime)
    body.append(fsRuntime)
    // std.process exec + ProcessOptions/ProcessResult — cross-module via
    // `import _ssc_runtime.*` (the split --bytecode/run-jvm path), mirroring
    // fsRuntime above so a called `exec(...)` resolves.
    body.append(processRuntime)
    // HTML tag bindings: emit all of them — the runtime doesn't know which
    // names the user shadows, so we drop tags into the runtime package and
    // user code shadows via its own top-level definitions where it wants
    // to (Scala 3 resolves the local binding over the wildcard import).
    body.append(htmlDslTagBindings(Set.empty))
    body.append(effectsRuntime)
    if capabilities.contains(MutualTco) then body.append(JvmRuntimeMutualTco.source)
    if capabilities.contains(Reactive)  then body.append(reactiveRuntime)
    if capabilities.contains(Serve)     then body.append(serveRuntime)
    else body.append(stubServeRuntime)
    if capabilities.contains(Mcp)       then body.append(JvmRuntimeMcp)
    if capabilities.contains(Dataset)   then body.append(JvmRuntimeDataset)

    // Wrap in an `object _ssc_runtime` (NOT a package) so the runtime's
    // top-level `class WsRoom` + `def WsRoom()` companion pair survives
    // — Scala 3 requires the two be defined "together" in a package,
    // but an object body has no such restriction.  Drop top-level
    // `private` modifiers so user code with `import _ssc_runtime.*`
    // can reach helper names like `_toJson` / `_lookupKey` /
    // `_renderChild`.  Also drop `package` declarations the inlined
    // common-runtime sources may carry — the object scope handles the
    // namespacing.
    val header = StringBuilder()
    if capabilities.contains(Mcp) then
      header.append(s"""//> using dep "$JvmMcpDep"\n""")
    header.append("object _ssc_runtime:\n")

    val depinned = stripPrivateModifiers(body.toString)
    // Indent every non-empty line by two spaces so the contents sit
    // inside the wrapping `object _ssc_runtime:` body.
    val indented = depinned.linesIterator
      .map(l => if l.isEmpty then l else "  " + l)
      .mkString("\n")
    header.append(indented)
    header.toString.stripTrailing() + "\n"

  /** Emit user code only, prepending `import _ssc_runtime.{given, *}` so
   *  the wildcard-imported helpers from the shared runtime resolve at
   *  compile time.  No runtime preamble is included. */
  def genUserOnly(module: Module): String =
    genUserOnlyWithLineMap(module)._1

  /** Tier 5 — rewrite parser-introduced `object pkg: object sub: <body>`
   *  wrap chains into Scala 3 `package pkg.sub:` block clauses, so the
   *  resulting `.class` files live in real Scala packages and external
   *  Scala consumers can `import pkg.sub.x` directly.
   *
   *  The wrap originates in `Parser.wrapSectionInPackage`, applied
   *  uniformly so the typer / interpreter / JS backends keep their
   *  nested-object scoping.  JvmGen alone unwinds the wrap because
   *  the JVM ABI hands consumers package-qualified symbols.
   *
   *  Discovery is structural: any chain of `object <ident>:` lines
   *  starting at indent 0, each next at indent +2, ending at a body
   *  block deeper-indented — that's a wrap.  Multi-section .ssc files
   *  produce one wrap chain per section, and inlined deps produce wrap
   *  chains under their OWN package name (e.g. user is `std.bifunctor`
   *  but it inlines `std.either` blocks).  We unwrap every chain we
   *  find. */
  private def unwrapPackageObjects(src: String, pkg: List[String]): String =
    if pkg.isEmpty then return src
    val maxChainDepth = pkg.size
    val normalized = normalizeBracedObjectWraps(src)
    val lines = normalized.linesIterator.toIndexedSeq
    if !lines.exists(_.startsWith("object ")) then return normalized

    // First pass: collect per-package body fragments.  Multiple
    // sections (and inlined deps) may target the same package; Scala 3
    // doesn't reliably resolve anonymous `given X with` declarations
    // when they live in DIFFERENT `package X:` blocks in the same
    // file (the synthetic `given_X_Int` name's lookup behaves
    // differently across blocks).  So we MERGE all fragments targeting
    // the same package into a single `package X.Y:` block.
    //
    // Preserves order: each package's first-seen position determines
    // where its merged block lands in the output; subsequent fragments
    // append to that block.  Non-wrap lines (header, imports, stray
    // top-level code) emit at their original positions, mixed with
    // package-block placeholders.
    val pkgFragments = scala.collection.mutable.LinkedHashMap.empty[String, scala.collection.mutable.StringBuilder]
    // Output structure: list of either Literal(String) or PackageRef(key)
    val outOrder = scala.collection.mutable.ListBuffer.empty[Either[String, String]]
    val literalBuf = new StringBuilder

    def flushLiteral(): Unit =
      if literalBuf.nonEmpty then
        outOrder += Left(literalBuf.toString)
        literalBuf.clear()

    var i = 0
    while i < lines.length do
      val chain = detectWrapChain(lines, i, maxChainDepth)
      if chain.isEmpty then
        literalBuf.append(lines(i)).append('\n')
        i += 1
      else
        val (segments, openerCount, bodyEnd) = chain.get
        val depth = segments.length
        val unwrapIndent = "  " * depth
        val pkgKey = segments.mkString(".")
        // Anchor this package's emit position when first encountered.
        if !pkgFragments.contains(pkgKey) then
          flushLiteral()
          outOrder += Right(pkgKey)
          pkgFragments(pkgKey) = new StringBuilder
        // Append this fragment's body lines to the package's collected
        // body, dedented + re-indented to 2 under the `package X:` block.
        val frag = pkgFragments(pkgKey)
        var j = i + openerCount
        while j < bodyEnd do
          val l = lines(j)
          if l.isEmpty || l.forall(_ == ' ') then
            frag.append('\n')
          else if l.startsWith(unwrapIndent) then
            frag.append("  ").append(l.substring(unwrapIndent.length)).append('\n')
          else
            frag.append(l).append('\n')
          j += 1
        // Always end each fragment with a single blank line so the
        // merged block has visible boundaries between sections (purely
        // cosmetic; the compile is the same either way).
        if !frag.toString.endsWith("\n\n") then frag.append('\n')
        i = bodyEnd

    flushLiteral()

    // Render the output: literals as-is, package refs as
    // `package X.Y:\n<collected body>`.
    //
    // Skip packages whose collected body has no actual definitions —
    // only blank lines and comments.  Some std/ modules (e.g.
    // std/cluster/types.ssc) are pure re-export shells whose only
    // code block is a list-form import; after `preprocessInlineImports`
    // strips it to a comment, the wrap body is comment-only.  Scala 3
    // rejects `package X:` with an empty body ("indented definitions
    // expected, eof found"); just omit the empty block.
    val out = new StringBuilder
    for chunk <- outOrder do chunk match
      case Left(lit)     => out.append(lit)
      case Right(pkgKey) =>
        val body = pkgFragments(pkgKey).toString
        if hasRealDefinitions(body) then
          out.append(s"package $pkgKey:\n")
          out.append(body)
        // else: omit the empty package block entirely.
    out.toString.stripTrailing() + "\n"

  /** True when a collected package-body string contains at least one
   *  line that is neither blank nor a single-line comment.  Comments
   *  alone don't justify emitting a `package X:` block — Scala 3
   *  requires non-empty bodies under colon-indent syntax. */
  private def hasRealDefinitions(body: String): Boolean =
    body.linesIterator.exists { l =>
      val t = l.strip
      t.nonEmpty && !t.startsWith("//")
    }

  /** Detect a parser-introduced wrap chain starting at `lines(start)`.
   *
   *  A wrap chain is N >= 1 consecutive lines of the form
   *  `(  )*object <ident>:` at increasing indent (0, 2, 4, …, 2*(N-1)),
   *  followed by at least one non-blank body line at indent >= 2*N.
   *
   *  Returns `(segments, openerCount, bodyEnd)` where:
   *  - `segments` is the list of identifiers from the opener lines,
   *  - `openerCount` is N (number of opener lines to skip),
   *  - `bodyEnd` is the exclusive-end index of the wrap's body —
   *    where the next outer-indent line OR a new wrap chain starts.
   *
   *  Returns `None` when no wrap chain is detected at this position. */
  private def detectWrapChain(
      lines: IndexedSeq[String],
      start: Int,
      maxDepth: Int
  ): Option[(List[String], Int, Int)] =
    if start >= lines.length then return None
    val openerPat = """^( *)object\s+([A-Za-z_][\w]*)\s*:\s*$""".r
    val first = lines(start)
    openerPat.findFirstMatchIn(first) match
      case Some(m) if m.group(1).length == 0 =>
        // Possible chain start: collect consecutive openers at +2 indent
        // up to maxDepth.  Capping the chain depth at the module's
        // `pkg.size` avoids over-unwrapping a user-level `object Parser:`
        // that happens to sit at the natural-next indent of the wrap.
        // Inlined deps with their own pkg get unwrapped too AS LONG AS
        // their pkg depth equals or fits under the module's cap — most
        // std/ corpus has uniform 2-segment packages so this holds.
        val segments = scala.collection.mutable.ListBuffer.empty[String]
        segments += m.group(2)
        var idx = start + 1
        var expectedIndent = 2
        while idx < lines.length && segments.length < maxDepth do
          openerPat.findFirstMatchIn(lines(idx)) match
            case Some(mm) if mm.group(1).length == expectedIndent =>
              segments += mm.group(2)
              idx += 1
              expectedIndent += 2
            case _ =>
              if isWrapBody(lines, idx, expectedIndent) then
                val bodyEnd = findBodyEnd(lines, idx, expectedIndent)
                return Some((segments.toList, segments.length, bodyEnd))
              else
                return None
        // Reached maxDepth (or end-of-file mid-chain).
        if isWrapBody(lines, idx, expectedIndent) then
          val bodyEnd = findBodyEnd(lines, idx, expectedIndent)
          Some((segments.toList, segments.length, bodyEnd))
        // Full-depth chain with NO body (e.g. an .ssc whose code
        // blocks are all `extern def` declarations that emitStat
        // stripped) — recognise the chain anyway so [[hasRealDefinitions]]
        // can omit the empty `package X:` block downstream.  Scala 3
        // rejects `object std: object fs:` with no body, so leaving
        // the wrap intact would produce an "indented definitions
        // expected" error.
        else if segments.length == maxDepth then
          Some((segments.toList, segments.length, idx))
        else None
      case _ => None

  /** True when `lines(idx)` is non-blank and indented at least
   *  `expectedIndent` spaces — i.e. it belongs inside the wrap body. */
  private def isWrapBody(lines: IndexedSeq[String], idx: Int, expectedIndent: Int): Boolean =
    if idx >= lines.length then return false
    val l = lines(idx)
    l.nonEmpty && l.startsWith(" " * expectedIndent)

  /** Walk forward from `start` while lines are blank or indented at
   *  least `bodyIndent`.  Returns the first line index at lower indent
   *  (or end of file). */
  private def findBodyEnd(lines: IndexedSeq[String], start: Int, bodyIndent: Int): Int =
    var idx = start
    while idx < lines.length do
      val l = lines(idx)
      if l.isEmpty then idx += 1
      else if l.startsWith(" " * bodyIndent) then idx += 1
      else return idx
    idx

  /** Convert any `object NAME { ... }` blocks at column 0 — emitted by
   *  the `Defn.Object` braced-syntax arm in `emitStat` and by scalameta's
   *  `.syntax` fallback when no specialised arm matched — into Scala 3
   *  colon-indent form `object NAME:\n  ...` so [[detectWrapChain]] can
   *  recognise the wrap.  Recursively descends INTO each converted
   *  block so chains like `object std { object actors { … } }` get
   *  flattened to colon-indent throughout.
   *
   *  Inner braces that don't form a wrap (`enum Overflow { case A }`,
   *  `def foo() { … }`, etc.) are left untouched — we only convert
   *  `object NAME {` openers and their matching closers, not arbitrary
   *  brace blocks.  Two cues distinguish the wrap shape: the opener
   *  line must START at column 0 (or at +2 inside an already-converted
   *  outer wrap, handled by recursive descent into the inner body) and
   *  must look like `object IDENT { ` with no other content on the
   *  line.  Scalameta's `.syntax` for `Defn.Object` follows this exact
   *  layout, so the rule is reliable in practice. */
  private def normalizeBracedObjectWraps(src: String): String =
    val n = src.length
    val out = new StringBuilder(n)
    var i = 0
    while i < n do
      val atCol0 = i == 0 || src.charAt(i - 1) == '\n'
      if atCol0 && src.startsWith("object ", i) then
        // Parse `object NAME ` then optional space; need `{` at end of line.
        var j = i + "object ".length
        while j < n && (src.charAt(j).isLetterOrDigit || src.charAt(j) == '_') do j += 1
        var k = j
        while k < n && src.charAt(k) == ' ' do k += 1
        if k < n && src.charAt(k) == '{' then
          // Confirm `{` is at end of line (only whitespace between it and `\n`).
          var afterBrace = k + 1
          while afterBrace < n && (src.charAt(afterBrace) == ' ' || src.charAt(afterBrace) == '\t') do
            afterBrace += 1
          if afterBrace >= n || src.charAt(afterBrace) == '\n' then
            // Wrap-shape match.  Find matching `}` via brace count.
            var depth = 1
            var p     = k + 1
            var inStr = false; var inLnCmt = false
            while p < n && depth > 0 do
              val c = src.charAt(p)
              if inLnCmt then { if c == '\n' then inLnCmt = false }
              else if inStr then { if c == '\\' && p + 1 < n then p += 1 else if c == '"' then inStr = false }
              else c match
                case '"' => inStr = true
                case '/' if p + 1 < n && src.charAt(p + 1) == '/' => inLnCmt = true; p += 1
                case '{' => depth += 1
                case '}' => depth -= 1
                case _   => ()
              p += 1
            // p now points just past the matching `}`.
            val closeIdx = p - 1
            // Body lies between `{` and the matching `}`.  Drop the
            // leading newline immediately after `{` so the dedent step
            // sees content starting at column 2 (the natural indent of
            // a brace body in scalameta's output) — then we can strip
            // one level of indentation, recursively normalise, and
            // re-indent.  The trailing newline before `}` is dropped
            // too so we don't accumulate blank lines.
            val bodyStart = k + 1
            val bodyEnd   = closeIdx
            var bs = bodyStart
            if bs < bodyEnd && src.charAt(bs) == '\n' then bs += 1
            val rawBody = src.substring(bs, bodyEnd)
            val bodyStripped =
              if rawBody.endsWith("\n") then rawBody.dropRight(1) else rawBody
            // Dedent by 2 so nested `object NAME {` lands at column 0
            // for the recursive scanner.  Blank lines stay blank.
            val dedented = bodyStripped.linesWithSeparators.map { l =>
              if l.startsWith("  ") then l.drop(2) else l
            }.mkString
            val recursedFlat = normalizeBracedObjectWraps(dedented)
            // Re-indent back to 2 so the colon-indent wrap is well-formed.
            val recursedIndented = recursedFlat.linesWithSeparators.map { l =>
              if l.trim.isEmpty then l else "  " + l
            }.mkString
            out.append(src.substring(i, j))   // `object NAME`
            out.append(":\n")
            out.append(recursedIndented)
            // Ensure a trailing newline so the next top-level item starts cleanly.
            if !recursedIndented.endsWith("\n") then out.append('\n')
            i = p
          else
            out.append(src.charAt(i)); i += 1
        else
          out.append(src.charAt(i)); i += 1
      else
        out.append(src.charAt(i)); i += 1
    out.toString

  /** Emit user code plus a generated-line → original-`.ssc`-line map.
   *  See [[JvmGen.generateUserOnlyWithLineMap]] for semantics. */
  def genUserOnlyWithLineMap(module: Module): (String, Map[Int, Int]) =
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    contentRuntimeEnabled = moduleUsesContentIntrinsics(module)
    contentToolkitRuntimeEnabled = moduleUsesContentToolkitIntrinsics(module)
    if contentRuntimeEnabled then collectDirectImportedContent(module) else importedContentDocuments.clear()
    contentSectionIndex = 0
    val blocks = expandMacrosInBlocks(collectBlocks(module.sections, module.document.map(_.sections).getOrElse(Nil)))
    analyzeDepEffectfulness()
    analyzeEffects(blocks)
    analyzeMutualRecursion(blocks)
    indexLocalDefSigs(blocks)
    val sb = StringBuilder()
    val lineMap = scala.collection.mutable.LinkedHashMap.empty[Int, Int]

    // Carry the same `//> using dep` directives the legacy emit would
    // produce; scala-cli treats them as no-ops in non-script files but
    // a `.sc` script needs them for any non-stdlib deps.
    module.manifest.foreach { m =>
      m.dependencies.foreach { (dep, version) =>
        if !version.startsWith("http://") && !version.startsWith("https://") then
          sb.append(s"""//> using dep "$dep:$version"\n""")
      }
    }
    if blocksUseMcp(blocks) then
      sb.append(s"""//> using dep "$JvmMcpDep"\n""")

    // Bring the shared runtime symbols into scope.  `given` is needed for
    // any extension methods / typeclass instances the runtime exposes,
    // and `*` covers values / defs / classes / objects.
    sb.append("import _ssc_runtime.{given, *}\n\n")
    if contentRuntimeEnabled then sb.append(emitContentRuntime(module.document, contentToolkitRuntimeEnabled))

    val frontmatterRoutes = module.manifest.toList.flatMap(_.routes)
    val apiClients = module.manifest.toList.flatMap(_.apiClients)
    val apiResponseTypes = openApiResponseTypes(apiClients)
    frontmatterRoutes.foreach { r =>
      emitRouteRegistration(sb, r.method, r.path, r.handler, apiResponseTypes)
    }
    if frontmatterRoutes.nonEmpty then sb.append("\n")
    val jvmClassFields2 = jvmCaseClassFieldsInBlocks(blocks)
    apiClients.flatMap(c => c.endpoints.flatMap(e => jvmEndpointPathWarnings(c.name, e, jvmClassFields2))).foreach { w =>
      System.err.println(s"[ssc warning] $w")
      sb.append(s"// [ssc warning] $w\n")
    }
    emitTypedRouteClientMetadata(apiClients, sb)
    val effectiveFrontend = frontendOverride.orElse(module.manifest.flatMap(_.frontendFramework))
    if effectiveFrontend.contains("swing") || effectiveFrontend.contains("javafx") then
      emitSwingTypedRouteClients(apiClients, sb)

    // i18n table injection — same as `genModule`.
    module.manifest.foreach { m =>
      if m.translations.nonEmpty then
        val entries = m.translations.map { (locale, kvs) =>
          val pairs = kvs.map { (k, v) =>
            val ek = k.replace("\\", "\\\\").replace("\"", "\\\"")
            val ev = v.replace("\\", "\\\\").replace("\"", "\\\"")
            s""""$ek" -> "$ev""""
          }.mkString(", ")
          s""""$locale" -> Map($pairs)"""
        }.mkString(", ")
        sb.append(s"_i18nTable = Map($entries)\n\n")
    }

    // Emit each block while recording where it landed in the output so
    // we can build the SMAP line map.  `currentGenLine` is the 1-based
    // line in `sb` where the NEXT character would go.  After wrapping
    // by the JvmBytecode driver into `object <name>_sc { … }`, the
    // emitted source gets ONE additional line at the top, shifting
    // every gen-line by +1.  We bake that +1 in here so callers don't
    // have to know about the wrapper detail.
    val WrapperShift = 1
    blocks.foreach { block =>
      val emitted = emitBlock(block).stripTrailing()
      val genStart = countLines(sb) + WrapperShift
      // Map each non-blank line of the block's emitted output to a
      // matching .ssc line.  We use a 1:1 stride: line N of the emit
      // ⇒ line N + lineOffset of the .ssc.  This is approximate when
      // emitBlock rewrites a block (effects / mutual-TCO transforms
      // can balloon or contract line counts) but every JVM stack-trace
      // tool we've checked tolerates an approximate mapping — it just
      // picks the closest preceding entry.
      if block.lineOffset > 0 then
        val emittedLines = emitted.linesIterator.length
        var i = 0
        while i < emittedLines do
          lineMap.update(genStart + i, block.lineOffset + i)
          i += 1
      sb.append(emitted)
      sb.append("\n\n")
    }

    emitStringBlocks(sb)

    val mainEntry = module.manifest
      .flatMap(_.raw.get("main"))
      .collect { case s: String => s }
    mainEntry.foreach { name => sb.append(s"$name()\n") }

    val rawSrc     = sb.toString.stripTrailing() + "\n"
    // Bare-name actor intrinsics inside verbatim-emitted objects/classes
    // (e.g. `object Cluster: def join(...) = joinCluster(...)`) — these
    // never went through `emitExpr`, so the runtime-only `Actor.<n>`
    // form was never spliced.  Use the AST-only pass — the regex pass
    // would also rewrite `def NAME(...)` declarations, breaking files
    // that legitimately re-export an intrinsic name as a wrapper.
    val actorSrc     = rewriteActorAstCallsInSource(rawSrc)
    // jvm-lazylist-fusion: fuse bounded LazyList.from(s).map(f)?.take(n).sum pipelines into
    // native loops so the emitted Scala (emit-scala / run-jvm) doesn't pay LazyList cons cost.
    val qualifiedSrc = fuseLazyListInSource(actorSrc)
    // `extern def` stubs at top-level (or inside an effectful object) are
    // stripped by the `case d: Defn.Def if isExternDef(d.body)` arm in
    // emitStat — but stubs nested inside plain classes / non-recursing
    // objects pass through scalameta's `.syntax` verbatim with the
    // `__extern__` body marker intact, and the Scala 3 compiler then
    // fails with "Not found: __extern__".  Replace any remaining
    // marker with the standard `???` placeholder so the type sig stays
    // intact and calls into the unfiltered stub raise `NotImplementedError`.
    // The runtime intrinsic table is consulted at call sites for the
    // real implementation (see `dispatchIntrinsic`).
    val externPatched = qualifiedSrc.replace("__extern__", "???")
    val modulePkg     = module.manifest.flatMap(_.pkg).getOrElse(Nil)
    val src           = unwrapPackageObjects(externPatched, modulePkg)
    (src, lineMap.toMap)

  /** Count the number of newlines emitted into `sb` so far.  Used to
   *  compute the 1-based generated-line position the next emit would
   *  land on.  Linear scan; the StringBuilder is rebuilt fresh per
   *  module so the cost is bounded. */
  private def countLines(sb: StringBuilder): Int =
    var n = 1; var i = 0
    val len = sb.length
    while i < len do
      if sb.charAt(i) == '\n' then n += 1
      i += 1
    n

  /** Strip top-level `private` access modifiers from a Scala source block.
   *  Used by `genRuntime` so wildcard-imported user code can reach helper
   *  names the legacy script-mode emit kept private.  Conservative pattern:
   *  only strip `private ` (with trailing space) at the start of a line —
   *  in-line `private` qualifiers (e.g. `class C: private val x = …`)
   *  stay intact because changing class-private visibility could break
   *  the runtime's own internal contracts. */
  private def stripPrivateModifiers(src: String): String =
    src.linesIterator
      .map { l =>
        // Match leading whitespace then `private ` (NOT `private[`, NOT
        // a comment) — only at indent depth 0 (top-level inside the
        // wrapping `package _ssc_runtime` block).
        if l.startsWith("private ") then l.drop("private ".length)
        else l
      }
      .mkString("\n") + (if src.endsWith("\n") then "\n" else "")

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

  // `java` fenced blocks collected during `collectBlocks` (backend-blocks-p3).
  // Each entry is the raw Java source.  Emitted as `//> using sources` directives
  // + written to files alongside the .ssc source when `baseDir` is set.
  private val javaBlocks = mutable.ArrayBuffer.empty[String]

  /** arch-meta-v2 macro-codegen-backends (cross-module) — expand + strip quoted
   *  macros over the ASSEMBLED block set (consumer + inlined imports), so a macro
   *  defined in an imported module and called from the consumer is handled. Runs
   *  at the top-level `collectBlocks` sites only (never the nested per-import
   *  call), where the imported macro defs and the consumer's call sites coexist.
   *  Strict no-op when the set declares no expandable macros. */
  private def expandMacrosInBlocks(blocks: List[JvmGen.Block]): List[JvmGen.Block] =
    val transformed = scalascript.artifact.MacroCodegen.expandUnits(blocks.map(b => (b.node, b.src)))
    blocks.zip(transformed).map { case (b, (node, src)) =>
      if node eq b.node then b else b.copy(node = node, src = src)
    }

  private def collectBlocks(sections: List[Section]): List[JvmGen.Block] =
    collectBlocks(sections, Nil)

  private def collectBlocks(sections: List[Section], contentSections: List[SectionContent]): List[JvmGen.Block] =
    sections.zipWithIndex.flatMap { (s, sectionPosition) =>
      val sectionId = sectionIdent(s.heading.text)
      val contentSection = contentSections.lift(sectionPosition)
      val currentContentSectionIndex = contentSection.map { _ =>
        val index = contentSectionIndex
        contentSectionIndex += 1
        index
      }
      val own = s.content.flatMap {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) && cb.attrs.get("side").contains("client") =>
          Nil
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          // `Content.CodeBlock.lineOffset` is populated by `Parser` from
          // CommonMark `BLOCKS` source-spans and points to the FIRST CODE
          // LINE inside the fence (0-based in the .ssc file).  When the
          // block was synthesised (e.g. by `inlineImport` injecting a
          // virtual block), `lineOffset` defaults to 0 and the block
          // contributes nothing to the SMAP.
          val origStart = cb.lineOffset
          cb.tree.map(t => JvmGen.Block(t, cb.source, origStart, currentContentSectionIndex)).toList
        case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
          // Reserve a position in the eventual emission order so the
          // String val lands between the surrounding parsed blocks.
          sectionId.foreach { id =>
            stringBlocks += JvmGen.StringBlockEntry(cb.lang, cb.source, id, stringBlocks.length)
          }
          Nil
        // v1.26 Phase 6.C — sql blocks compile to a `scalascript.sql.SqlRuntime
        // .execute(...)` call returning `SqlResult`.  Connection resolution at
        // codegen time: the emitted helper `_ssc_sql_resolve(name)` consults
        // a `given java.sql.Connection` in scope first, then falls back to
        // `_ssc_sql_registry.connect(name)`.  First sql block per section
        // also gets a friendly `<sectionId>.sql` alias (Phase C.2 convention).
        // v1.30 Phase 4 — @side=client blocks are collected for browser JS
        // injection instead of server JDBC emit.
        case cb: Content.CodeBlock if Lang.isSql(cb.lang) =>
          if cb.attrs.get("side").contains("client") then
            val alias = sectionId.filter { id =>
              val prior = sqlPerSection.getOrElse(id, 0)
              sqlPerSection(id) = prior + 1
              prior == 0
            }
            clientSqlBlocksList += ((cb.source, cb.attrs.get("db"), alias))
            Nil
          else
            val n = sqlBlockCounter
            sqlBlockCounter += 1
            val aliasable = sectionId.filter { id =>
              val prior = sqlPerSection.getOrElse(id, 0)
              sqlPerSection(id) = prior + 1
              prior == 0
            }
            val sqlSrc = sqlBlockToScala(cb.source, cb.attrs.get("db"), n, aliasable)
            import scala.meta.{dialects, *}
            val tree = dialects.Scala3(Input.VirtualFile(s"<sql-block-$n>", sqlSrc))
              .parse[Source].toOption.map(scalascript.ast.ScalaNode(_))
            tree.map(t => JvmGen.Block(t, sqlSrc)).toList
        // transaction fenced blocks: emit a `withTransaction` call that
        // wraps all `;`-separated statements in one atomic JDBC transaction.
        case cb: Content.CodeBlock if Lang.isTransaction(cb.lang) =>
          val n = sqlBlockCounter
          sqlBlockCounter += 1
          val txSrc = transactionBlockToScala(cb.source, cb.attrs.get("db"), n)
          import scala.meta.{dialects, *}
          val tree = dialects.Scala3(Input.VirtualFile(s"<tx-block-$n>", txSrc))
            .parse[Source].toOption.map(scalascript.ast.ScalaNode(_))
          tree.map(t => JvmGen.Block(t, txSrc)).toList
        // `java` fenced blocks: collect source for `//> using sources` emission.
        case cb: Content.CodeBlock if Lang.isJava(cb.lang) =>
          javaBlocks += cb.source
          Nil
        case imp: Content.Import =>
          val (blocks, importedPkg) = inlineImport(imp.path)
          blocks ++ aliasBlock(imp.bindings, importedPkg).toList
        case _ => Nil
      }
      own ++ collectBlocks(s.subsections, contentSection.map(_.children).getOrElse(Nil))
    }

  /** Emit the per-module SQL connection registry + the
   *  `_ssc_sql_resolve(dbName: Option[String])` helper that every
   *  emitted sql block routes through.
   *
   *  Resolution policy (same shape as the interpreter's
   *  `resolveSqlConnection`):
   *
   *    1. If a `given java.sql.Connection` is in scope at the call
   *       site, use it.  This is the override path — typical for
   *       tests that inject an H2 connection.
   *    2. Otherwise, resolve via the registry keyed by the fence's
   *       `@db=name` attribute (or `"default"` when absent).
   *
   *  The registry is built once at script entrypoint from the
   *  front-matter `databases:` map; `${env:NAME}` references in URL /
   *  user / password are resolved at connect time, not here. */
  private def emitSqlRegistry(databases: List[scalascript.ast.DatabaseDecl]): String =
    val sb = StringBuilder()
    sb.append("// ── v1.26 — JDBC sql-block runtime support ────────────────────────\n")
    if databases.isEmpty then
      sb.append("val _ssc_sql_registry: scalascript.sql.ConnectionRegistry =\n")
      sb.append("  scalascript.sql.ConnectionRegistry.empty\n\n")
    else
      sb.append("val _ssc_sql_registry: scalascript.sql.ConnectionRegistry = {\n")
      sb.append("  scalascript.sql.ConnectionRegistry(List(\n")
      val specs = databases.map { d =>
        val name = escapeStringLit(d.name)
        val url  = escapeStringLit(d.url)
        val user = d.user.map(u => s"""Some("${escapeStringLit(u)}")""").getOrElse("None")
        val pass = d.password.map(p => s"""Some("${escapeStringLit(p)}")""").getOrElse("None")
        val drv  = d.driver.map(c => s"""Some("${escapeStringLit(c)}")""").getOrElse("None")
        s"""    scalascript.sql.DatabaseSpec("$name", "$url", $user, $pass, $drv)"""
      }.mkString(",\n")
      sb.append(specs).append("\n")
      sb.append("  ))\n")
      sb.append("}\n\n")
    sb.append("/** Resolve a connection for a sql block: `given Connection` in\n")
    sb.append(" *  scope wins (Scala 3 `summonFrom` picks it up), registry\n")
    sb.append(" *  fallback otherwise. */\n")
    sb.append("inline def _ssc_sql_resolve(dbName: Option[String]): java.sql.Connection =\n")
    sb.append("  scala.compiletime.summonFrom {\n")
    sb.append("    case c: java.sql.Connection => c\n")
    sb.append("    case _                       => _ssc_sql_registry.connect(dbName.getOrElse(\"default\"))\n")
    sb.append("  }\n")
    sb.append("\n")
    // Db object — mirrors the interpreter's Db.query / Db.execute intrinsics.
    // Acquires a per-call connection from the registry, delegates to SqlRuntime,
    // and closes the connection regardless of outcome.
    sb.append("""|object Db:
                 |  def query(dbName: String, sql: String, params: List[Any]): List[Map[String, Any]] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.SqlRuntime.execute(conn, sql, params) match
                 |      case scalascript.sql.SqlResult.Rows(rows) => rows.map(_.toMap).toList
                 |      case scalascript.sql.SqlResult.UpdateCount(_) => Nil
                 |  def query[A](dbName: String, sql: String, params: List[Any])(using scalascript.typeddata.RowCodec[A]): List[A] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.SqlRuntime.query[A](conn, sql, params).toList
                 |  def insert[A](dbName: String, table: String, value: A)(using scalascript.typeddata.RowCodec[A]): Int =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.SqlRuntime.insert[A](conn, table, value)
                 |  def update[A](dbName: String, table: String, keyColumn: String, keyValue: Any, value: A)(using scalascript.typeddata.RowCodec[A]): Int =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.SqlRuntime.update[A](conn, table, keyColumn, keyValue, value)
                 |  def execute(dbName: String, sql: String, params: List[Any]): Int =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.SqlRuntime.execute(conn, sql, params) match
                 |      case scalascript.sql.SqlResult.UpdateCount(n) => n
                 |      case scalascript.sql.SqlResult.Rows(_) => 0
                 |
                 |object ObjectStore:
                 |  def put[A](dbName: String, store: String, value: A)(using scalascript.typeddata.ObjectCodec[A]): scalascript.sql.Stored[A] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.ObjectStoreRuntime.put[A](conn, store, value)
                 |  def put[A](dbName: String, store: String, key: String, value: A)(using scalascript.typeddata.ObjectCodec[A]): scalascript.sql.Stored[A] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.ObjectStoreRuntime.put[A](conn, store, value, Some(key))
                 |  def putExpected[A](dbName: String, store: String, key: String, expectedVersion: Long, value: A)(using scalascript.typeddata.ObjectCodec[A]): scalascript.sql.Stored[A] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.ObjectStoreRuntime.put[A](conn, store, value, Some(key), Some(expectedVersion))
                 |  def get[A](dbName: String, store: String, key: String)(using scalascript.typeddata.ObjectCodec[A]): Option[A] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.ObjectStoreRuntime.get[A](conn, store, key)
                 |  def getStored[A](dbName: String, store: String, key: String)(using scalascript.typeddata.ObjectCodec[A]): Option[scalascript.sql.Stored[A]] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.ObjectStoreRuntime.getStored[A](conn, store, key)
                 |  def all[A](dbName: String, store: String)(using scalascript.typeddata.ObjectCodec[A]): List[A] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.ObjectStoreRuntime.all[A](conn, store).toList
                 |  def changes[A](dbName: String, store: String, sinceVersion: Long, limit: Int = 100)(using scalascript.typeddata.ObjectCodec[A]): List[scalascript.sql.Stored[A]] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.ObjectStoreRuntime.changes[A](conn, store, sinceVersion, limit).toList
                 |  def delete(dbName: String, store: String, key: String): scalascript.sql.Stored[Nothing] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.ObjectStoreRuntime.delete(conn, store, key)
                 |  def deleteExpected(dbName: String, store: String, key: String, expectedVersion: Long): scalascript.sql.Stored[Nothing] =
                 |    val conn = _ssc_sql_registry.connect(dbName)
                 |    scalascript.sql.ObjectStoreRuntime.delete(conn, store, key, Some(expectedVersion))
                 |
                 |""".stripMargin)
    sb.toString

  private def emitGraphRegistry(graphs: List[scalascript.ast.GraphDecl]): String =
    val declared = if graphs.nonEmpty then graphs else List(scalascript.ast.GraphDecl("default"))
    val sb = StringBuilder()
    sb.append("// ── Graph runtime support ───────────────────────────────────────\n")
    sb.append("val _ssc_graph_registry: Map[String, scalascript.graph.GraphBackend] = Map(\n")
    val entries = declared.map { g =>
      val name = escapeStringLit(g.name)
      val backend = g.backend.toLowerCase
      val model = g.model.toLowerCase
      val side = g.side.toLowerCase
      val rhs =
        if side != "server" then
          s"throw scalascript.graph.GraphRuntimeError(\"graphs.$name: only side=server is supported by the JVM graph facade today\")"
        else if Set("in-memory", "memory", "embedded-memory", "rdf-memory").contains(backend) then
          "scalascript.graph.GraphRuntime.inMemory()"
        else if Set("embedded-tinkergraph", "tinkergraph", "tinkerpop-tinkergraph").contains(backend) then
          "scalascript.graph.GraphRuntime.tinkerGraph()"
        else if Set("rdf4j-memory", "rdf4j", "embedded-rdf4j").contains(backend) then
          "scalascript.graph.GraphRuntime.rdf4jMemory()"
        else if backend == "neo4j" then
          val boltUri = escapeStringLit(g.uri.getOrElse("bolt://localhost:7687"))
          val boltUser = escapeStringLit(g.user.getOrElse("neo4j"))
          val boltPass = escapeStringLit(g.password.getOrElse("neo4j"))
          s"""scalascript.graph.GraphRuntime.neo4j("$boltUri", "$boltUser", "$boltPass")"""
        else if Set("gremlin-server", "janusgraph", "tinkerpop-remote", "gremlin").contains(backend) then
          val wsUri  = escapeStringLit(g.uri.getOrElse("ws://localhost:8182/gremlin"))
          val maybeUser = g.user.map(u => s"""Some("${escapeStringLit(u)}")""").getOrElse("None")
          val maybePass = g.password.map(p => s"""Some("${escapeStringLit(p)}")""").getOrElse("None")
          s"""scalascript.graph.GraphRuntime.gremlinRemote("$wsUri", $maybeUser, $maybePass)"""
        else if Set("rdf4j-http", "rdf4j-server", "graphdb", "fuseki", "stardog").contains(backend) then
          val httpUri  = escapeStringLit(g.uri.getOrElse("http://localhost:7200/repositories/default"))
          val maybeUser = g.user.map(u => s"""Some("${escapeStringLit(u)}")""").getOrElse("None")
          val maybePass = g.password.map(p => s"""Some("${escapeStringLit(p)}")""").getOrElse("None")
          s"""scalascript.graph.GraphRuntime.rdf4jHttp("$httpUri", $maybeUser, $maybePass)"""
        else
          s"throw scalascript.graph.GraphRuntimeError(\"graphs.$name: backend '$backend' for model '$model' is planned but not implemented yet\")"
      s"  \"$name\" -> $rhs"
    }.mkString(",\n")
    sb.append(entries).append("\n)\n\n")
    sb.append("""|object Graph:
                 |  private def backend(name: String): scalascript.graph.GraphBackend =
                 |    _ssc_graph_registry.getOrElse(name, throw scalascript.graph.GraphRuntimeError(s"unknown graph store '$name'. Declared stores: ${_ssc_graph_registry.keys.toList.sorted.mkString(", ")}"))
                 |  def putVertex[A](graphName: String, value: A)(using scalascript.typeddata.VertexCodec[A]): scalascript.typeddata.VertexValue =
                 |    scalascript.graph.GraphRuntime.putVertex(backend(graphName), value)
                 |  def getVertex[A](graphName: String, id: String)(using scalascript.typeddata.VertexCodec[A]): Option[A] =
                 |    scalascript.graph.GraphRuntime.getVertex[A](backend(graphName), id)
                 |  def vertices[A](graphName: String)(using scalascript.typeddata.VertexCodec[A]): List[A] =
                 |    scalascript.graph.GraphRuntime.vertices[A](backend(graphName)).toList
                 |  def putEdge[A](graphName: String, value: A)(using scalascript.typeddata.EdgeCodec[A]): scalascript.graph.StoredEdge =
                 |    scalascript.graph.GraphRuntime.putEdge(backend(graphName), value)
                 |  def edges[A](graphName: String)(using scalascript.typeddata.EdgeCodec[A]): List[A] =
                 |    scalascript.graph.GraphRuntime.edges[A](backend(graphName)).toList
                 |  def neighborValues(graphName: String, from: String, edgeLabel: Option[String] = None): List[scalascript.typeddata.VertexValue] =
                 |    backend(graphName).neighbors(from, edgeLabel).toList
                 |  def neighbors[A](graphName: String, from: String, edgeLabel: Option[String] = None)(using scalascript.typeddata.VertexCodec[A]): List[A] =
                 |    backend(graphName).neighbors(from, edgeLabel).toVector.map { vertex =>
                 |      scalascript.typeddata.VertexCodec[A].decode(vertex) match
                 |        case Right(value) => value
                 |        case Left(error) => throw scalascript.graph.GraphRuntimeError(s"vertex decode failed for ${vertex.id}: ${error.render}")
                 |    }.toList
                 |  def putRdf[A](graphName: String, value: A)(using scalascript.typeddata.RdfCodec[A]): scalascript.typeddata.RdfValue =
                 |    scalascript.graph.GraphRuntime.putRdf(backend(graphName), value)
                 |  def getRdf[A](graphName: String, subject: scalascript.typeddata.RdfNode)(using scalascript.typeddata.RdfCodec[A]): Option[A] =
                 |    scalascript.graph.GraphRuntime.getRdf[A](backend(graphName), subject)
                 |  def triples(graphName: String, subject: Option[scalascript.typeddata.RdfNode] = None, predicate: Option[String] = None): List[scalascript.typeddata.RdfTriple] =
                 |    backend(graphName).triples(subject, predicate).toList
                 |
                 |object Sparql:
                 |  private def backend(name: String): scalascript.graph.GraphBackend =
                 |    _ssc_graph_registry.getOrElse(name, throw scalascript.graph.GraphRuntimeError(s"unknown graph store '$name'. Declared stores: ${_ssc_graph_registry.keys.toList.sorted.mkString(", ")}"))
                 |  def select(graphName: String, query: String): List[Map[String, scalascript.typeddata.RdfNode]] =
                 |    scalascript.graph.GraphRuntime.sparqlSelect(backend(graphName), query).toList
                 |  def update(graphName: String, query: String): Unit =
                 |    scalascript.graph.GraphRuntime.sparqlUpdate(backend(graphName), query)
                 |
                 |object Cypher:
                 |  private def backend(name: String): scalascript.graph.PropertyGraphBackend =
                 |    _ssc_graph_registry.getOrElse(name, throw scalascript.graph.GraphRuntimeError(s"unknown graph store '$name'. Declared stores: ${_ssc_graph_registry.keys.toList.sorted.mkString(", ")}")) match
                 |      case pg: scalascript.graph.PropertyGraphBackend => pg
                 |      case _ => throw scalascript.graph.GraphRuntimeError(s"graph store '$name' does not support Cypher queries")
                 |  def query(graphName: String, query: String, params: Map[String, Any] = Map.empty): List[Map[String, scalascript.typeddata.JsonValue]] =
                 |    backend(graphName).cypherQuery(query, params).toList
                 |
                 |object Gremlin:
                 |  private def backend(name: String): scalascript.graph.PropertyGraphBackend =
                 |    _ssc_graph_registry.getOrElse(name, throw scalascript.graph.GraphRuntimeError(s"unknown graph store '$name'. Declared stores: ${_ssc_graph_registry.keys.toList.sorted.mkString(", ")}")) match
                 |      case pg: scalascript.graph.PropertyGraphBackend => pg
                 |      case _ => throw scalascript.graph.GraphRuntimeError(s"graph store '$name' does not support Gremlin queries")
                 |  def query(graphName: String, query: String, bindings: Map[String, Any] = Map.empty): List[scalascript.typeddata.JsonValue] =
                 |    backend(graphName).gremlinQuery(query, bindings).toList
                 |
                 |""".stripMargin)
    sb.toString

  private def graphRuntimeDeps(graphs: List[scalascript.ast.GraphDecl]): Vector[String] =
    val deps = Vector.newBuilder[String]
    graphs.foreach { graph =>
      graph.backend.toLowerCase match
        case backend if Set("embedded-tinkergraph", "tinkergraph", "tinkerpop-tinkergraph").contains(backend) =>
          deps += "org.apache.tinkerpop:tinkergraph-gremlin:3.8.1"
        case backend if Set("rdf4j-memory", "rdf4j", "embedded-rdf4j").contains(backend) =>
          deps += "org.eclipse.rdf4j:rdf4j-repository-sail:5.3.1"
          deps += "org.eclipse.rdf4j:rdf4j-sail-memory:5.3.1"
        case backend if Set("rdf4j-http", "rdf4j-server", "graphdb", "fuseki", "stardog").contains(backend) =>
          deps += "org.eclipse.rdf4j:rdf4j-repository-http:5.3.1"
        case "neo4j" =>
          deps += "org.neo4j.driver:neo4j-java-driver:5.28.5"
        case backend if Set("gremlin-server", "janusgraph", "tinkerpop-remote", "gremlin").contains(backend) =>
          deps += "org.apache.tinkerpop:gremlin-driver:3.8.1"
        case _ =>
          ()
    }
    deps.result().distinct

  private def emitObjectStoreSyncRoutes(stores: List[ObjectStoreDecl], sb: StringBuilder): Unit =
    sb.append("// ── ObjectStore generated REST sync routes ─────────────────────\n")
    sb.append(
      """|private def _ssc_sync_map(value: Any): Map[String, Any] =
         |  value match
         |    case m: Map[?, ?] => m.iterator.map { case (k, v) => k.toString -> v }.toMap
         |    case _ => Map.empty
         |private def _ssc_sync_list(value: Any): List[Any] =
         |  value match
         |    case xs: Iterable[?] => xs.toList
         |    case xs: Array[?] => xs.toList
         |    case _ => Nil
         |private def _ssc_sync_long(value: Option[Any], default: Long): Long =
         |  value match
         |    case Some(n: java.lang.Number) => n.longValue
         |    case Some(s: String) => scala.util.Try(s.toLong).getOrElse(default)
         |    case _ => default
         |private def _ssc_sync_int(value: Option[Any], default: Int): Int =
         |  value match
         |    case Some(n: java.lang.Number) => n.intValue
         |    case Some(s: String) => scala.util.Try(s.toInt).getOrElse(default)
         |    case _ => default
         |private def _ssc_sync_bool(value: Option[Any], default: Boolean = false): Boolean =
         |  value match
         |    case Some(b: Boolean) => b
         |    case Some(s: String) => s.equalsIgnoreCase("true") || s == "1"
         |    case _ => default
         |
         |""".stripMargin
    )
    stores.foreach { store =>
      val routeStore = store.name
      val storageStore = store.store.getOrElse(store.name)
      val path = "/__ssc/sync/" + routeStore + "/"
      val database = scalaStringLiteral(store.database)
      val storage = scalaStringLiteral(storageStore)
      val table = store.table.map(scalaStringLiteral).getOrElse("scalascript.sql.ObjectStoreRuntime.DefaultTable")
      val tpe = store.valueType
      val conflictPolicy = scalaStringLiteral(store.conflict)
      sb.append(s"""route("GET", ${scalaStringLiteral(path + "changes")}) { req =>
  val since = _ssc_sync_long(req.query.get("since"), 0L)
  val limit = _ssc_sync_int(req.query.get("limit"), 100)
  val conn = _ssc_sql_registry.connect($database)
  val changes = scalascript.sql.ObjectStoreRuntime.changes[$tpe](conn, $storage, since, limit, $table).toList
  val nextCursor = changes.foldLeft(since)((cursor, change) => math.max(cursor, change.version))
  Response.json(Map(
    "changes" -> changes.map(change => Map(
      "key" -> change.key,
      "version" -> change.version,
      "updatedAt" -> change.updatedAt.toString,
      "deleted" -> change.deleted,
      "value" -> change.value
    )),
    "nextCursor" -> nextCursor
  ))
}

route("POST", ${scalaStringLiteral(path + "push")}) { req =>
  val conn = _ssc_sql_registry.connect($database)
  val payload = _ssc_sync_map(req.json.getOrElse(Map.empty[String, Any]))
  val mutations = _ssc_sync_list(payload.getOrElse("mutations", Nil))
  val results = scala.collection.mutable.ListBuffer.empty[Map[String, Any]]
  val conflicts = scala.collection.mutable.ListBuffer.empty[Map[String, Any]]
  mutations.foreach { raw =>
    val mutation = _ssc_sync_map(raw)
    val key = mutation.get("key").map(_.toString).getOrElse("")
    val expected = mutation.get("expectedVersion").map(v => _ssc_sync_long(Some(v), 0L))
    val deleted = _ssc_sync_bool(mutation.get("deleted"))
    try
      val stored =
        if deleted then
          scalascript.sql.ObjectStoreRuntime.delete(conn, $storage, key, expected, $table)
        else
          val decoded = scalascript.sql.ObjectStoreRuntime.decodeAny[$tpe](mutation.getOrElse("value", Map.empty[String, Any]))
          scalascript.sql.ObjectStoreRuntime.put[$tpe](conn, $storage, decoded, Some(key).filter(_.nonEmpty), expected, $table)
      results += Map("key" -> stored.key, "version" -> stored.version, "deleted" -> stored.deleted)
    catch
      case conflict: scalascript.sql.ObjectStoreConflict =>
        val policy = $conflictPolicy
        if policy == "client-wins" then
          val stored =
            if deleted then
              scalascript.sql.ObjectStoreRuntime.delete(conn, $storage, key, None, $table)
            else
              val decoded = scalascript.sql.ObjectStoreRuntime.decodeAny[$tpe](mutation.getOrElse("value", Map.empty[String, Any]))
              scalascript.sql.ObjectStoreRuntime.put[$tpe](conn, $storage, decoded, Some(key).filter(_.nonEmpty), None, $table)
          results += Map("key" -> stored.key, "version" -> stored.version, "deleted" -> stored.deleted)
        else
          val current = scalascript.sql.ObjectStoreRuntime.getStored[$tpe](conn, $storage, key, $table)
          if policy == "server-wins" then
            results += Map(
              "key" -> key,
              "version" -> current.map(_.version).getOrElse(0L),
              "deleted" -> current.forall(_.deleted)
            )
          else
            conflicts += Map(
              "key" -> key,
              "expectedVersion" -> expected,
              "actualVersion" -> current.map(_.version),
              "deleted" -> current.exists(_.deleted),
              "value" -> current.flatMap(_.value)
            )
  }
  Response.json(Map("results" -> results.toList, "conflicts" -> conflicts.toList))
}

""")
    }

  /** Minimal escape for emitted Scala string literals. */
  private val javafxEmitVersion: String = "21.0.5"

  private def javafxOs: String =
    val name = sys.props.getOrElse("os.name", "")
    val arch = sys.props.getOrElse("os.arch", "")
    if name.startsWith("Mac") then if arch.contains("aarch64") then "mac-aarch64" else "mac"
    else if name.startsWith("Windows") then "win"
    else "linux"

  private def openApiResponseTypes(apiClients: List[ApiClientDecl]): Map[(String, String), String] =
    apiClients
      .flatMap(_.endpoints)
      .collect {
        case ep if ep.responseType.nonEmpty && ep.responseType != "Any" =>
          (ep.method.toUpperCase, ep.path) -> ep.responseType
      }
      .toMap

  private def emitRouteRegistration(
      sb:            StringBuilder,
      method:        String,
      path:          String,
      handler:       String,
      responseTypes: Map[(String, String), String]
  ): Unit =
    val m = method.toUpperCase
    val p = escapeStringLit(path)
    responseTypes.get((m, path)) match
      case Some(responseType) =>
        sb.append(s"""_ssc_route_response("$m", "$p", ${scalaStringLiteral(responseType)}) { req => $handler(req) }\n""")
      case None =>
        sb.append(s"""route("$m", "$p") { req => $handler(req) }\n""")

  private val jvmEndpointPrimitives = Set("Int", "Long", "String", "Boolean", "Double", "Float")

  private def jvmPathParamNames(path: String): List[String] =
    path.split("/").toList.collect { case seg if seg.startsWith(":") => seg.drop(1) }

  private def jvmCaseClassFieldsInBlocks(blocks: List[JvmGen.Block]): Map[String, List[String]] =
    val result = scala.collection.mutable.Map.empty[String, List[String]]
    blocks.foreach { b =>
      ScalaNode.fold(b.node) { tree =>
        tree.collect {
          case d: Defn.Class if d.mods.exists(_.isInstanceOf[Mod.Case]) =>
            result(d.name.value) = d.ctor.paramClauses.flatMap(_.values).map(_.name.value).toList
        }
      }
    }
    result.toMap

  // ── arch-meta-v2-p5 Track A (A1a) — JVM runtime Mirror metadata ────────────
  //
  // The interpreter registers summon-able `Mirror.Of[T]` metadata as types are
  // declared (`DerivesRuntime.mirrorForType`).  The generated JVM backend had
  // no equivalent — `summon[Mirror.Of[Person]]` did not resolve and the bare
  // `Mirror` type was undefined.  We mirror the interpreter shape with a small
  // PHANTOM-typed `_SscMirror[A]` runtime class (so `Mirror.Of[A]` selects the
  // per-type given) and emit one `given _SscMirror[T]` per top-level product
  // (case class).  Sum types (enum / sealed trait) and generic case classes are
  // deferred follow-ups.  Emitted only when the module references `Mirror`.

  /** True when any user block mentions the `Mirror` API (the trigger for
   *  emitting the Mirror runtime + product givens). */
  private def blocksUseMirror(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists(_.src.contains("Mirror"))

  /** Runtime preamble for the JVM Mirror surface — a phantom-typed metadata
   *  class plus the `Mirror.{Of,ProductOf,SumOf}` type aliases and a bare
   *  `Mirror` alias (so `def derived(m: Mirror)` type-checks once A1b lands). */
  private[codegen] val mirrorRuntime: String =
    """
      |// ── arch-meta-v2-p5 Track A — runtime Mirror metadata (JVM) ──────────────
      |// `+A` is a phantom type tag (no member references A), covariant so a
      |// per-type `_SscMirror[T]` is accepted where a bare `Mirror` (= `_SscMirror[Any]`)
      |// is expected — e.g. `def derived(m: Mirror)` called with `summon[Mirror.Of[T]]`.
      |final class _SscMirror[+A](
      |    val label: String,
      |    val elemLabels: List[String],
      |    val elemTypes: List[String],
      |    val variants: List[String],
      |    val isProduct: Boolean,
      |    val isSum: Boolean,
      |    _fromProduct: List[Any] => Any,
      |    _ordinal: Any => Int):
      |  def fields: List[String] = elemLabels
      |  def fromProduct(xs: Any): Any = (xs: @unchecked) match
      |    case l: List[Any] => _fromProduct(l)
      |    case p: Product   => _fromProduct(p.productIterator.toList)
      |    case other        => _fromProduct(List(other))
      |  def ordinal(x: Any): Int = _ordinal(x)
      |object Mirror:
      |  type Of[A] = _SscMirror[A]
      |  type ProductOf[A] = _SscMirror[A]
      |  type SumOf[A] = _SscMirror[A]
      |type Mirror = _SscMirror[Any]
      |""".stripMargin

  /** Emit a `given _SscMirror[T]` for each top-level, non-generic case class
   *  in the user blocks.  Deterministic order, de-duplicated by type name. */
  private def mirrorProductGivens(blocks: List[JvmGen.Block]): List[String] =
    val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
    blocks.foreach { b =>
      val topStats = b.node.tree match
        case Source(stats)     => stats
        case Term.Block(stats) => stats
        case other             => List(other)
      topStats.foreach {
        case d: Defn.Class
            if d.mods.exists(_.isInstanceOf[Mod.Case]) && d.tparamClause.values.isEmpty =>
          val name   = d.name.value
          val params = d.ctor.paramClauses.flatMap(_.values)
          val labels = params.map(_.name.value)
          val types  = params.map(_.decltpe.map(_.syntax).getOrElse("Any"))
          val labelList = labels.map(l => "\"" + l + "\"").mkString("List(", ", ", ")")
          val typeList  = types.map(t => "\"" + t + "\"").mkString("List(", ", ", ")")
          val ctorArgs  = labels.indices.map(i => s"xs($i).asInstanceOf[${types(i)}]").mkString(", ")
          out(name) =
            s"given _SscMirror[$name] = new _SscMirror[$name](" +
            s""""$name", $labelList, $typeList, Nil, true, false, """ +
            s"(xs: List[Any]) => $name($ctorArgs), (x: Any) => -1)"
        case _ =>
      }
    }
    out.values.toList

  // ── arch-meta-v2-p5 Track A (A1b) — custom `derives TC` synthesis (JVM) ─────
  //
  // For `case class T(...) derives TC` where `TC` is a user typeclass providing
  // `def derived(m: Mirror)`, scalac's own derivation contract (`derived[T](using
  // Mirror.Of[T])`) does not match the SS contract.  So we STRIP the (all-custom)
  // derives clause from the emitted class and synthesize the given ourselves:
  //   given TC[T] = TC.derived(summon[Mirror.Of[T]]).asInstanceOf[TC[T]]
  // reusing the A1a per-type Mirror given.  `derives` clauses that mix in
  // non-user typeclasses (e.g. stdlib `Eq`) are left untouched — stdlib
  // structural derivation is A1c.

  /** Object names that declare a `derived` method — the user typeclasses whose
   *  `derives` clause we can synthesize (`object Csv: def derived(m: Mirror)`). */
  private def userDerivableTypeclasses(blocks: List[JvmGen.Block]): Set[String] =
    val names = scala.collection.mutable.Set.empty[String]
    blocks.foreach { b =>
      ScalaNode.fold(b.node) { tree =>
        tree.collect {
          case o: Defn.Object if o.templ.body.stats.exists {
                case dd: Defn.Def => dd.name.value == "derived"
                case _            => false
              } => names += o.name.value
        }
      }
    }
    names.toSet

  /** The four stdlib typeclasses the JVM backend synthesizes STRUCTURALLY (A1c)
   *  — they define no `derived`; the interpreter builds them in `DerivesRuntime`.*/
  private val stdlibStructuralTCs: Set[String] = Set("Eq", "Show", "Hash", "Order")

  /** Strip `derives TC` clauses we HANDLE (custom user typeclasses + the stdlib
   *  structural four) from top-level case classes in a block — but only when ALL
   *  of a class's derives are handled (a clause mixing in an unknown typeclass is
   *  left untouched).  Strips both the parsed tree and the raw `block.src` (via
   *  tree positions), recording `typeName -> derived TC names` into `sink`. */
  private def stripHandledDerives(
      b: JvmGen.Block,
      handledTCs: Set[String],
      sink: scala.collection.mutable.LinkedHashMap[String, List[String]]
  ): JvmGen.Block =
    val topStats = b.node.tree match
      case Source(stats)     => stats
      case Term.Block(stats) => stats
      case _                 => Nil
    if topStats.isEmpty then return b
    var srcEdits = List.empty[(Int, Int)]
    var changed  = false
    val newStats = topStats.map {
      case d: Defn.Class if d.mods.exists(_.isInstanceOf[Mod.Case]) && d.templ.derives.nonEmpty =>
        val tcNames = d.templ.derives.map { case Type.Name(n) => n; case t => t.syntax }
        if tcNames.forall(handledTCs.contains) then
          sink(d.name.value) = tcNames
          val firstT = d.templ.derives.head
          val lastT  = d.templ.derives.last
          val kw     = b.src.lastIndexOf("derives", firstT.pos.start)
          if kw >= 0 then srcEdits = (kw, lastT.pos.end) :: srcEdits
          changed = true
          d.copy(templ = d.templ.copy(derives = Nil))
        else d
      case other => other
    }
    if !changed then b
    else
      val newTree = b.node.tree match
        case s: Source      => s.copy(stats = newStats)
        case tb: Term.Block => tb.copy(stats = newStats)
        case other          => other
      val newSrc = srcEdits.sortBy(-_._1).foldLeft(b.src) {
        case (s, (a, e)) => s.substring(0, a) + s.substring(e)
      }
      b.copy(node = ScalaNode(newTree), src = newSrc)

  /** Synthesize the `given` lines for the handled derives recorded by
   *  [[stripHandledDerives]].  Custom typeclasses (with a `derived` method) get
   *  `given TC[T] = TC.derived(summon[Mirror.Of[T]])` (A1b); the stdlib four get
   *  a structural instance (A1c) using Scala `Product` + the `_ssc_struct*`
   *  runtime helpers. */
  private def derivesGivensFor(
      derives: scala.collection.mutable.LinkedHashMap[String, List[String]],
      userTCs: Set[String]
  ): List[String] =
    derives.toList.flatMap { (t, tcs) =>
      tcs.map { tc =>
        if userTCs.contains(tc) then
          s"given $tc[$t] = $tc.derived(summon[Mirror.Of[$t]]).asInstanceOf[$tc[$t]]"
        else tc match
          case "Eq"    => s"given Eq[$t] = new Eq[$t] { def eqv(a: $t, b: $t): Boolean = a == b }"
          case "Show"  => s"given Show[$t] = new Show[$t] { def show(a: $t): String = _ssc_structShow(a) }"
          case "Hash"  => s"given Hash[$t] = new Hash[$t] { def hash(a: $t): Int = a.hashCode }"
          case "Order" => s"given Order[$t] = new Order[$t] { def compare(a: $t, b: $t): Int = _ssc_structCompare(a, b) }"
          case _       => s"// arch-meta-v2: unhandled derives $tc on $t"
      }
    }

  /** Runtime helpers for A1c stdlib structural derives — structural `show`
   *  (`TypeName(field=value, ...)` via `Product.productElementName`) and
   *  `compare` (field-by-field, first non-zero), matching the interpreter's
   *  `DerivesRuntime.structuralShow` / `structuralCompare`.  Emitted into the
   *  preamble when a module has any stdlib structural `derives`. */
  private[codegen] val mirrorStructRuntime: String =
    """
      |// ── arch-meta-v2-p5 Track A (A1c) — structural derives helpers (JVM) ─────
      |def _ssc_structShow(v: Any): String = v match
      |  case s: String                       => s
      |  case _: scala.collection.Iterable[?] => _show(v)
      |  case _: Option[?]                    => _show(v)
      |  case p: Product if !v.getClass.getName.startsWith("scala.") =>
      |    if p.productArity == 0 then p.productPrefix
      |    else p.productPrefix + "(" + (0 until p.productArity).map(i =>
      |      p.productElementName(i) + "=" + _ssc_structShow(p.productElement(i))).mkString(", ") + ")"
      |  case other => _show(other)
      |def _ssc_structCompare(a: Any, b: Any): Int = (a, b) match
      |  case (x: Int, y: Int)         => x.compareTo(y)
      |  case (x: Long, y: Long)       => x.compareTo(y)
      |  case (x: Double, y: Double)   => x.compareTo(y)
      |  case (x: String, y: String)   => x.compareTo(y)
      |  case (x: Boolean, y: Boolean) => x.compareTo(y)
      |  case (pa: Product, pb: Product) if pa.productPrefix == pb.productPrefix =>
      |    var i = 0; var r = 0
      |    while i < pa.productArity && r == 0 do {
      |      r = _ssc_structCompare(pa.productElement(i), pb.productElement(i)); i += 1
      |    }
      |    r
      |  case _ => 0
      |""".stripMargin

  private def jvmEndpointPathWarnings(
    clientName: String,
    ep: ApiEndpointDecl,
    classFields: Map[String, List[String]]
  ): List[String] =
    val params = jvmPathParamNames(ep.path)
    if params.isEmpty then Nil
    else ep.requestType match
      case "Unit" =>
        params.map(p => s"apiClient $clientName.${ep.name}: path param ':$p' cannot be filled — request type is Unit")
      case prim if jvmEndpointPrimitives.contains(prim) =>
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

  private def emitTypedRouteClientMetadata(clients: List[ApiClientDecl], sb: StringBuilder): Unit =
    val endpoints = clients.flatMap(client => client.endpoints.map(endpoint => client.name -> endpoint))
    if endpoints.nonEmpty then
      sb.append(
        "final case class _TypedRouteClientEndpoint(client: String, name: String, method: String, path: String, requestType: String, responseType: String)\n"
      )
      val rows = endpoints.map { (client, endpoint) =>
        "  _TypedRouteClientEndpoint(" +
          List(
            client,
            endpoint.name,
            endpoint.method,
            endpoint.path,
            endpoint.requestType,
            endpoint.responseType
          ).map(scalaStringLiteral).mkString(", ") +
          ")"
      }.mkString(",\n")
      sb.append("val _ssc_typedRouteClients: List[_TypedRouteClientEndpoint] = List(\n")
      sb.append(rows).append("\n)\n\n")

  private def emitSwingTypedRouteClients(clients: List[ApiClientDecl], sb: StringBuilder): Unit =
    if clients.exists(_.endpoints.nonEmpty) then
      sb.append(JvmRuntimeSwingClient.source)
      clients.foreach { client =>
        if client.endpoints.nonEmpty then
          sb.append("object ").append(client.name).append(":\n")
          client.endpoints.foreach { endpoint =>
            val method = scalaStringLiteral(endpoint.method)
            val path = scalaStringLiteral(endpoint.path)
            if ApiEndpointDecl.isWs(endpoint) then
              if endpoint.requestType == "Unit" then
                sb.append("  def ").append(endpoint.name)
                  .append("(onEvent: ").append(endpoint.responseType).append(" => Unit")
                  .append(", onError: String => Unit = _ => ()")
                  .append(", onOpen: _SscWsHandle => Unit = _ => ()): _SscWsHandle")
                  .append(" = _ssc_api_ws_request[Unit, ").append(endpoint.responseType).append("](")
                  .append(path).append(", (), onEvent, onError, onOpen)\n")
              else
                sb.append("  def ").append(endpoint.name)
                  .append("(input: ").append(endpoint.requestType)
                  .append(", onEvent: ").append(endpoint.responseType).append(" => Unit")
                  .append(", onError: String => Unit = _ => ()")
                  .append(", onOpen: _SscWsHandle => Unit = _ => ()): _SscWsHandle")
                  .append(" = _ssc_api_ws_request[").append(endpoint.requestType).append(", ").append(endpoint.responseType).append("](")
                  .append(path).append(", input, onEvent, onError, onOpen)\n")
            else if ApiEndpointDecl.isSse(endpoint) then
              if endpoint.requestType == "Unit" then
                sb.append("  def ").append(endpoint.name)
                  .append("(onEvent: ").append(endpoint.responseType).append(" => Unit")
                  .append(", onError: String => Unit = _ => (), headers: Map[String, String] = Map.empty): AutoCloseable")
                  .append(" = _ssc_api_stream_request[Unit, ").append(endpoint.responseType).append("](")
                  .append(method).append(", ").append(path).append(", (), onEvent, onError, headers)\n")
              else
                sb.append("  def ").append(endpoint.name)
                  .append("(input: ").append(endpoint.requestType)
                  .append(", onEvent: ").append(endpoint.responseType).append(" => Unit")
                  .append(", onError: String => Unit = _ => (), headers: Map[String, String] = Map.empty): AutoCloseable")
                  .append(" = _ssc_api_stream_request[").append(endpoint.requestType).append(", ").append(endpoint.responseType).append("](")
                  .append(method).append(", ").append(path).append(", input, onEvent, onError, headers)\n")
            else if endpoint.requestType == "Unit" then
              sb.append("  def ").append(endpoint.name)
                .append("(headers: Map[String, String] = Map.empty, cancelToken: _SscCancelToken = null): ").append(endpoint.responseType)
                .append(" = _ssc_api_request[Unit, ").append(endpoint.responseType).append("](")
                .append(method).append(", ").append(path).append(", (), headers, cancelToken)\n")
              if endpoint.paginated then
                val pagedPath = path + " + \"?page=\" + page + \"&size=\" + size"
                sb.append("  def ").append(endpoint.name).append("Paged")
                  .append("(page: Int, size: Int, headers: Map[String, String] = Map.empty, cancelToken: _SscCancelToken = null): ").append(endpoint.responseType)
                  .append(" = _ssc_api_request[Unit, ").append(endpoint.responseType).append("](")
                  .append(method).append(", ").append(pagedPath).append(", (), headers, cancelToken)\n")
            else
              sb.append("  def ").append(endpoint.name).append("(input: ").append(endpoint.requestType)
                .append(", headers: Map[String, String] = Map.empty, cancelToken: _SscCancelToken = null): ")
                .append(endpoint.responseType).append(" = _ssc_api_request[")
                .append(endpoint.requestType).append(", ").append(endpoint.responseType).append("](")
                .append(method).append(", ").append(path).append(", input, headers, cancelToken)\n")
              if endpoint.paginated then
                val pagedPath = path + " + \"?page=\" + page + \"&size=\" + size"
                sb.append("  def ").append(endpoint.name).append("Paged").append("(input: ").append(endpoint.requestType)
                  .append(", page: Int, size: Int, headers: Map[String, String] = Map.empty, cancelToken: _SscCancelToken = null): ")
                  .append(endpoint.responseType).append(" = _ssc_api_request[")
                  .append(endpoint.requestType).append(", ").append(endpoint.responseType).append("](")
                  .append(method).append(", ").append(pagedPath).append(", input, headers, cancelToken)\n")
          }
          sb.append("\n")
      }

  /** Build the JS content for `_ssc_client_sql_js`.
   *
   *  Inline the sql-runtime.mjs (export-stripped) + registry init +
   *  one `await SqlRuntimeJs.execute(...)` per collected @side=client
   *  block, all wrapped in an async IIFE so `await` is valid.  The
   *  results are exposed on `window._ssc_client[sectionAlias]` for
   *  the React app to consume (e.g. via `useEffect` + state). */
  private def emitClientSqlJs(module: Module): String =
    if clientSqlBlocksList.isEmpty then return ""
    val sb = StringBuilder()
    sb.append("// ── @side=client sql blocks (injected by JvmGen v1.30) ──────────────\n")
    sb.append("(async function() {\n")
    val runtimeSrc = SqlRuntimeJsEmit.runtimeSource.replace("export ", "")
    sb.append(runtimeSrc)
    sb.append("\nconst SqlRuntimeJs = { execute, ConnectionRegistry, makeRow, isResultSetProducer, Providers, SqlJsProvider, SqliteWasmProvider, DuckDbWasmProvider };\n")
    val databases = module.manifest.toList.flatMap(_.databases)
    val entries   = databases.map { d =>
      SqlRuntimeJsEmit.DatabaseEntry(
        name = d.name, url = d.url, user = d.user, password = d.password, driver = d.driver
      )
    }
    sb.append(SqlRuntimeJsEmit.emitRegistryInit(entries))
    sb.append("\n")
    sb.append("  if (typeof window !== 'undefined') { if (!window._ssc_client) window._ssc_client = {}; }\n")
    clientSqlBlocksList.zipWithIndex.foreach { case ((source, dbNameOpt, alias), i) =>
      val rewrite = scalascript.transform.SqlBindRewriter.rewriteJdbc(source)
      val sqlLit  = jsLitForClientSql(rewrite.sql)
      val bindsJs = rewrite.binds.map(_.trim).mkString(", ")
      val dbArg   = dbNameOpt.map(jsLitForClientSql).getOrElse("undefined")
      sb.append(s"  const _clientSqlBlock_$i = await SqlRuntimeJs.execute(await _ssc_sql_resolve($dbArg), $sqlLit, [$bindsJs]);\n")
      alias.foreach { aliasId =>
        val aliasLit = jsLitForClientSql(aliasId)
        sb.append(s"  if (typeof window !== 'undefined') window._ssc_client[$aliasLit] = { sql: _clientSqlBlock_$i };\n")
      }
    }
    sb.append("})().catch(function(e) { console.error('[ssc] @side=client sql error:', e); });\n")
    sb.toString

  /** Translate a `sql` fenced block to its emitted Scala source.  Walks
   *  the block through `SqlBindRewriter.rewriteJdbc` to get a
   *  `?`-templated SQL string + an ordered bind-expression list, then
   *  emits a `val _sqlBlock_<n>` bound to the `SqlRuntime.execute`
   *  result.  Bind expressions are spliced as Scala source — they're
   *  evaluated in the surrounding scope at runtime, exactly like the
   *  interpreter does.
   *
   *  When `sectionAlias` is `Some("Users")` and this is the first sql
   *  block in the section, appends a friendly object alias:
   *
   *    object Users:
   *      lazy val sql: scalascript.sql.SqlResult = _sqlBlock_<n>
   *
   *  Subsequent sql blocks in the same section pass `None` (the
   *  caller's `sqlPerSection` book-keeping enforces "first only"), so
   *  no duplicate `lazy val sql` ever lands in the same `object`. */
  private def sqlBlockToScala(
    source:        String,
    dbName:        Option[String],
    n:             Int,
    sectionAlias:  Option[String]
  ): String =
    val r       = scalascript.transform.SqlBindRewriter.rewriteJdbc(source)
    val valName = s"_sqlBlock_$n"
    // Escape `"""` if it appears in the SQL (rare — but defensive).
    val sqlLit  =
      if r.sql.contains("\"\"\"") then
        "\"" + r.sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      else
        "\"\"\"" + r.sql + "\"\"\""
    val bindsArg =
      if r.binds.isEmpty then "Nil"
      else "List(" + r.binds.mkString(", ") + ")"
    val dbArg = dbName match
      case Some(n) => s"""Some("$n")"""
      case None    => "None"
    val execLine =
      s"val $valName: scalascript.sql.SqlResult = " +
        s"_ssc_sql_resolve($dbArg) match { case _ssc_conn => " +
        s"scalascript.sql.SqlRuntime.execute(_ssc_conn, $sqlLit, $bindsArg) }"
    sectionAlias match
      case None        => execLine
      case Some(secId) =>
        execLine + "\n" +
        s"object $secId:\n" +
        s"  lazy val sql: scalascript.sql.SqlResult = $valName"

  /** Translate a `transaction` fenced block to emitted Scala source.
   *  Splits the source on `;` (outside `${...}`), rewrites each statement
   *  through `SqlBindRewriter.rewriteJdbc`, and wraps all statements in a
   *  `_ssc_sql_registry.withTransaction(dbName) { conn => List(...) }` call.
   *
   *  The result is a `val _sqlBlock_<n>: List[scalascript.sql.SqlResult]`
   *  holding the results of every statement in the transaction. */
  private def transactionBlockToScala(
    source: String,
    dbName: Option[String],
    n:      Int
  ): String =
    val stmts    = scalascript.transform.SqlBindRewriter.splitStatements(source)
    val rewrites = stmts.map(scalascript.transform.SqlBindRewriter.rewriteJdbc)
    val valName  = s"_sqlBlock_$n"
    val db       = dbName.getOrElse("default")
    val dbLit    = escapeStringLit(db)
    val stmtLines = rewrites.map { r =>
      val sqlLit =
        if r.sql.contains("\"\"\"") then
          "\"" + r.sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        else
          "\"\"\"" + r.sql + "\"\"\""
      val bindsArg =
        if r.binds.isEmpty then "Nil"
        else "List(" + r.binds.mkString(", ") + ")"
      s"      scalascript.sql.SqlRuntime.execute(_ssc_tx_conn, $sqlLit, $bindsArg)"
    }.mkString(",\n")
    s"""val $valName: List[scalascript.sql.SqlResult] =
       |  _ssc_sql_registry.withTransaction("$dbLit") { _ssc_tx_conn =>
       |    List(
       |$stmtLines
       |    )
       |  }""".stripMargin

  /** Mirror Interpreter / JsGen `sectionIdent`. */
  private def sectionIdent(text: String): Option[String] =
    val parts = text.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val head = parts.head
      val tail = parts.tail.map(p => s"${p.head.toUpper}${p.tail}")
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  /** Synthesise import/alias bindings for imported names.
   *  When `pkg` is non-empty, emit a Scala `import` statement — this handles
   *  both trait/type definitions and given values, since `val X = pkg.X` would
   *  fail to compile for traits.
   *  When `pkg` is empty, only `as`-aliased bindings produce a `val`. */
  private def aliasBlock(bindings: List[ImportBinding], pkg: List[String]): Option[JvmGen.Block] =
    import scala.meta.{dialects, *}
    val helperAliases = bindings.flatMap { b =>
      if contentIntrinsicNames(b.name) then
        b.alias.collect { case alias if alias != b.name => s"val $alias = ${b.name}" }
      else None
    }
    if pkg.nonEmpty then
      val pkgPath = pkg.mkString(".")
      val importBindings = bindings.filterNot { b =>
        (pkgPath == "std.content" || pkgPath == "std.ui.content") && contentIntrinsicNames(b.name)
      }
      val boundNames = importBindings.map(_.name).toSet
      // Pull in the dep's TYPE-like exports that the import list omits, so
      // type annotations referring to them resolve in generated Scala.
      val typeSpecs = importedTypeExports.getOrElse(pkgPath, Nil).distinct
        .filterNot(boundNames.contains)
      val extensionSpecs = importedExtensionExports.getOrElse(pkgPath, Nil)
        .filterNot(boundNames.contains)
      val requested =
        importBindings.map(b => ImportSpec(b.name, b.alias)) ++
          typeSpecs.map(n => ImportSpec(n, None)) ++
          extensionSpecs.map(n => ImportSpec(n, None))
      val reExports = importedReExports.getOrElse(pkgPath, Map.empty)
      val grouped = mutable.LinkedHashMap.empty[List[String], mutable.ListBuffer[(ReExportTarget, ImportSpec)]]
      requested.foreach { spec =>
        val target = reExports.getOrElse(spec.name, ReExportTarget(pkg, spec.name))
        grouped.getOrElseUpdate(target.pkg, mutable.ListBuffer.empty) += ((target, spec))
      }
      val importLines = grouped.toList.flatMap { case (targetPkg, specs) =>
        if targetPkg.isEmpty then Nil
        else
          val specText = specs.map { case (target, spec) =>
            val alias = spec.alias.orElse {
              if target.name != spec.name then Some(spec.name) else None
            }
            alias match
              case None    => target.name
              case Some(a) => s"${target.name} as $a"
          }.mkString(", ")
          List(s"import ${targetPkg.mkString(".")}.{$specText}")
      }
      val lines =
        importLines ++ helperAliases
      if lines.isEmpty then None
      else
        val src   = lines.mkString("\n")
        val input = Input.VirtualFile("<import-aliases>", src)
        dialects.Scala3(input).parse[Source].toOption.map(s => JvmGen.Block(ScalaNode(s), src))
    else
      val aliases = bindings.flatMap { b =>
        b.alias.map { a => s"val $a = ${b.name}" }
      } ++ helperAliases
      if aliases.isEmpty then None
      else
        val src   = aliases.mkString("\n")
        val input = Input.VirtualFile("<import-aliases>", src)
        dialects.Scala3(input).parse[Source].toOption.map(s => JvmGen.Block(ScalaNode(s), src))

  /** Resolve a `[name](./path.ssc)` Markdown import: parse the referenced
   *  file, inline its code blocks, and return the `(blocks, pkg)` pair so
   *  the caller can generate correctly-qualified alias vals.
   *  Each path is inlined at most once per JvmGen run. */
  private def inlineImport(path: String): (List[JvmGen.Block], List[String]) =
    import scalascript.parser.Parser
    val base = baseDir.getOrElse(os.pwd)
    val resolved =
      try scalascript.imports.ImportResolver.resolve(path, base, moduleDeps, lockPath)
      catch case e: Throwable => throw new RuntimeException(s"Import $path: ${e.getMessage}")
    val key = resolved.toString
    if importedFiles.contains(key) then
      val pkg = importedPkgs.getOrElse(key, Nil)
      if pkg.nonEmpty && os.exists(resolved) then
        val importedModule = Parser.parse(os.read(resolved))
        recordImportMetadata(importedModule, resolved, pkg)
      (Nil, pkg)
    else if !os.exists(resolved) then
      throw new RuntimeException(s"Import not found: $path")
    else
      importedFiles += key
      val importedModule = Parser.parse(os.read(resolved))
      val pkg = importedModule.manifest.flatMap(_.pkg).getOrElse(Nil)
      importedPkgs(key) = pkg
      recordImportMetadata(importedModule, resolved, pkg)
      val nested = new JvmGen(Some(resolved / os.up), lockPath = lockPath)
      nested.importedFiles ++= importedFiles
      // Propagate pkg/type-export knowledge so a TRANSITIVE import of an
      // already-inlined package module (e.g. ledger → std/money) still takes
      // the early-return path with a non-empty pkg, and so its alias line is
      // emitted with the right qualifier and type names.
      nested.importedPkgs       ++= importedPkgs
      nested.importedTypeExports ++= importedTypeExports
      nested.importedExtensionExports ++= importedExtensionExports
      nested.importedReExports  ++= importedReExports
      // Apply the bare-actor-name rewrite to dep blocks before they enter
      // the main emit pipeline. Without this, code like `def healthCheck =
      // { val me = self(); pid ! msg }` lands verbatim inside the eventual
      // `object std { object mapreduce { … } }` wrapper, and `self()` is
      // unresolved (the JvmGen emit-time qualification only fires for
      // user-code blocks via the effect-detection path). The transform is
      // purely textual: bare actor names (`self`, `link`, `trapExit`, …)
      // become `Actor.<n>`. Effect-primitive call shapes that need real
      // CPS plumbing (`pid ! msg`, `receive { case … }`) are handled by
      // Strategy D's dep-mode CPS emit (Step 3) via the existing
      // `emitCpsExpr` / `emitReceiveMatcher` infrastructure.
      val rawBlocks = nested.collectBlocks(importedModule.sections)
      // Merge back what the nested run inlined, so siblings/parents see the
      // same already-inlined set, pkgs, and type-exports.
      importedFiles       ++= nested.importedFiles
      importedPkgs        ++= nested.importedPkgs
      importedTypeExports ++= nested.importedTypeExports
      importedExtensionExports ++= nested.importedExtensionExports
      importedReExports   ++= nested.importedReExports
      if pkg.nonEmpty then
        val pkgPath = pkg.mkString(".")
        val extensionExps =
          (moduleTopLevelExtensionNames(importedModule) ++ topLevelExtensionNames(rawBlocks)).distinct
        if extensionExps.nonEmpty then
          importedExtensionExports(pkgPath) =
            (importedExtensionExports.getOrElse(pkgPath, Nil) ++ extensionExps).distinct
      val rewrittenBlocks = rawBlocks.map(qualifyBareActorCallsInBlock)
      // Strategy D, Step 2 — index every Defn.Def in this dep (top-level
      // or nested in object/class) so the fixpoint can analyse its body.
      // Also index every Defn.Class so chunk 3's call-site cast injection
      // can look up constructor param types when emitting `Cluster(...)`
      // and similar inside a CPS continuation.  Plus every type-defining
      // decl (class/trait/enum) goes into `depTypeNames` with its pkg
      // so qualifier prefixing in `calleeParamType` can keep dep types
      // resolvable at user call sites regardless of explicit imports.
      rewrittenBlocks.foreach { b =>
        ScalaNode.fold(b.node) { tree =>
          tree.collect {
            case d: Defn.Def   => depDefs(d.name.value)    = d
            case d: Defn.Class =>
              depClasses(d.name.value)   = d
              depTypeNames(d.name.value) = pkg
            case d: Defn.Trait => depTypeNames(d.name.value) = pkg
            case d: Defn.Enum  => depTypeNames(d.name.value) = pkg
          }
        }
      }
      (rewrittenBlocks, pkg)

  private def recordImportMetadata(importedModule: Module, resolved: os.Path, pkg: List[String]): Unit =
    if pkg.nonEmpty then
      val pkgPath  = pkg.mkString(".")
      val typeExps = importedModule.manifest.toList.flatMap(_.exports)
        .filter(n => n.nonEmpty && n.charAt(0).isUpper)
      importedTypeExports(pkgPath) =
        (importedTypeExports.getOrElse(pkgPath, Nil) ++ typeExps).distinct
      val extensionExps = moduleTopLevelExtensionNames(importedModule)
      if extensionExps.nonEmpty then
        importedExtensionExports(pkgPath) =
          (importedExtensionExports.getOrElse(pkgPath, Nil) ++ extensionExps).distinct
      val reExports = inferReExportTargets(importedModule, resolved / os.up, Set(resolved.toString))
      if reExports.nonEmpty then
        importedReExports(pkgPath) =
          importedReExports.getOrElse(pkgPath, Map.empty) ++ reExports

  private def topLevelExtensionNames(blocks: List[JvmGen.Block]): List[String] =
    blocks.flatMap { block =>
      ScalaNode.fold(block.node)(topLevelExtensionNamesInTree) ++
        topLevelExtensionNamesInSource(block.src)
    }.distinct

  private def moduleTopLevelExtensionNames(module: Module): List[String] =
    val moduleIndent = module.manifest.flatMap(_.pkg).fold(0)(_.size * 2)
    def loop(sections: List[Section]): List[String] =
      sections.flatMap { section =>
        section.content.flatMap {
          case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
            cb.tree.toList.flatMap(node => ScalaNode.fold(node)(topLevelExtensionNamesInTree)) ++
              topLevelExtensionNamesInSource(cb.source, moduleIndent)
          case _ => Nil
        } ++ loop(section.subsections)
      }
    loop(module.sections).distinct

  private def topLevelExtensionNamesInSource(src: String, topIndent: Int = 0): List[String] =
    val indent = " " * topIndent
    val nextIndent = " " * (topIndent + 2)
    val sameLine =
      ("""(?m)^""" + java.util.regex.Pattern.quote(indent) +
        """extension\b[^\n]*\bdef\s+([A-Za-z_][A-Za-z0-9_]*)""").r
    val indentedDef =
      ("""(?m)^""" + java.util.regex.Pattern.quote(indent) +
        """extension\b[^\n]*(?:\n""" + java.util.regex.Pattern.quote(nextIndent) +
        """def\s+([A-Za-z_][A-Za-z0-9_]*))""").r
    (sameLine.findAllMatchIn(src).map(_.group(1)) ++
      indentedDef.findAllMatchIn(src).map(_.group(1))).toList.distinct

  private def topLevelExtensionNamesInTree(tree: Tree): List[String] =
    def methodNames(eg: Defn.ExtensionGroup): List[String] =
      eg.body match
        case d: Defn.Def       => List(d.name.value)
        case Term.Block(stats) => stats.collect { case d: Defn.Def => d.name.value }
        case _                 => Nil

    tree match
      case Source(stats) =>
        stats.collect { case eg: Defn.ExtensionGroup => methodNames(eg) }.flatten
      case Term.Block(stats) =>
        stats.collect { case eg: Defn.ExtensionGroup => methodNames(eg) }.flatten
      case eg: Defn.ExtensionGroup =>
        methodNames(eg)
      case _ =>
        Nil

  private def inferReExportTargets(
    module: Module,
    moduleBase: os.Path,
    seen: Set[String]
  ): Map[String, ReExportTarget] =
    val exported = module.manifest.toList.flatMap(_.exports).toSet
    if exported.isEmpty then return Map.empty

    def importsIn(sections: List[Section]): List[Content.Import] =
      sections.flatMap { section =>
        section.content.collect { case imp: Content.Import => imp } ++
          importsIn(section.subsections)
      }

    val out = mutable.LinkedHashMap.empty[String, ReExportTarget]
    importsIn(module.sections).foreach { imp =>
      val resolved =
        try Some(scalascript.imports.ImportResolver.resolve(imp.path, moduleBase, moduleDeps, lockPath))
        catch case _: Throwable => None
      resolved.foreach { path =>
        val key = path.toString
        if !seen.contains(key) && os.exists(path) then
          val targetModule = scalascript.parser.Parser.parse(os.read(path))
          val targetPkg = targetModule.manifest.flatMap(_.pkg).getOrElse(Nil)
          val targetReExports = inferReExportTargets(targetModule, path / os.up, seen + key)
          imp.bindings.foreach { binding =>
            val exportedName = binding.alias.getOrElse(binding.name)
            if exported(exportedName) && !out.contains(exportedName) then
              val target = targetReExports.getOrElse(binding.name, ReExportTarget(targetPkg, binding.name))
              if target.pkg.nonEmpty then out(exportedName) = target
          }
      }
    }
    out.toMap

  /** Pre-emit string rewrite for dep blocks. Targets the narrow set of
   *  bare actor names the runtime exposes only as `Actor.<n>` (not as
   *  top-level names), so wrapping the dep in `object std { object pkg
   *  { … } }` doesn't lose access. Conservative: word-boundary anchored
   *  so substrings inside identifiers / comments / strings aren't
   *  touched.
   *
   *  Effect-primitive call shapes (`pid ! msg`, `receive { case … }`)
   *  are NOT handled here — Strategy D's dep-mode CPS emit (Step 3)
   *  rewrites them via `emitCpsExpr` / `emitReceiveMatcher`. Only the
   *  bare-name fixup is needed for case-class / nested-method paths
   *  where CPS emit doesn't fire.
   *
   *  Why string-level and not a scala.meta transformer: scalameta 4.13
   *  doesn't ship a public `Tree.transform` (the transversers package
   *  is empty), and writing a manual recursive copy-based transformer
   *  for every container type would be ~200 lines of plumbing. The
   *  regex set below is intentionally tiny so the matching is
   *  predictable. */
  private def qualifyBareActorCallsInBlock(block: JvmGen.Block): JvmGen.Block =
    val rewrittenSrc = qualifyBareActorCallsInSource(block.src)
    if rewrittenSrc == block.src then block
    else
      // Re-parse so the downstream emitBlock sees the new shape.
      import scala.meta.{dialects, *}
      dialects.Scala3(Input.VirtualFile("<dep-rewrite>", rewrittenSrc))
        .parse[Source].toOption match
        case Some(reparsed) => JvmGen.Block(scalascript.ast.ScalaNode(reparsed), rewrittenSrc)
        case None           => block  // re-parse failed — fall back to original

  private def qualifyBareActorCallsInSource(src: String): String =
    var out = src
    val bareNames = actorBareNames - "receive" - "runActors" - "spawn" - "spawn_link" - "spawnBounded"
    bareNames.foreach { n =>
      val pattern = ("""(?<![.\w])""" + java.util.regex.Pattern.quote(n) + """\(""").r
      out = pattern.replaceAllIn(out, java.util.regex.Matcher.quoteReplacement(s"Actor.$n("))
    }
    out

  /** Names from [[actorBareNames]] that participate in the AST-level
   *  bare-call rewrite in [[rewriteActorAstCallsInSource]].  Excludes
   *  only `receive` / `receiveWithTimeout` — those have dedicated
   *  AST cases above this one because they need PartialFunction →
   *  matcher translation, and a naïve splice (`Actor.receive(...)`)
   *  would land a verbatim `case … =>` block straight into runtime
   *  args.  `spawn` / `spawn_link` / `spawnBounded` / `runActors` ARE
   *  included: the runtime accepts the same `() => Any` thunk shape
   *  the user wrote, and inner intrinsic calls inside the thunk get
   *  rewritten by the recursive walk (`argClause.values.foreach(walk)`)
   *  before the outer splice replaces the bare function name. */
  private def isBareActorIntrinsic(n: String): Boolean =
    actorBareNames(n) && n != "receive"

  /** Recognise the declared return types that don't benefit from an
   *  `.asInstanceOf[T]` cast in the wrapper-emit path: `Any` and `Unit`
   *  alone (any of the typical surface forms — `Unit` named directly,
   *  the unit tuple `()`, the Type.Name lowering scalameta emits).
   *  A `: Any` return is the runtime's own signature, no cast needed.
   *  A `: Unit` return discards the result anyway, so wrapping in a
   *  cast is pointless and risks `()` mismatches inside the body. */
  private def isAnyOrUnitType(t: scala.meta.Type): Boolean = t match
    case scala.meta.Type.Name(name) => name == "Any" || name == "Unit"
    case _                          => false

  /** Walk every Apply / Pat tree in `src` (via scalameta parse) and
   *  splice in the AST-level rewrites the regex-based
   *  [[qualifyBareActorCallsInSource]] can't safely make:
   *
   *  - Bare actor intrinsic call at a typed `def` body — re-emit as
   *    `Actor.<n>(...).asInstanceOf[T]` so the wrapper preserves the
   *    user's declared return type.
   *  - Bare actor intrinsic call anywhere else — splice the function
   *    name to `Actor.<n>` while leaving the args (and their parens)
   *    alone so nested intrinsic calls get rewritten in their own
   *    recursion.
   *  - `Pat.Extract(None | Nil, ())` — scalameta's printer would emit
   *    `case None()` after a `.syntax` round-trip; Scala 3 rejects
   *    that because `None` is a case object.  Collapse back to the
   *    singleton form.
   *
   *  Receive / `!` AST handling is NOT here — origin/main relies on
   *  the regex pass + CPS emit for those, and re-introducing the AST
   *  band-aid would route bodies through the CPS pipeline at sites
   *  where they shouldn't be (see commit log on
   *  `emitReceiveMatcherOpt`).
   *
   *  Replacements are applied right-to-left so earlier offsets stay
   *  valid.  Used as a post-processing pass over user-only emit output
   *  (see [[genUserOnlyWithLineMap]]). */
  private def rewriteActorAstCallsInSource(src: String): String =
    import scala.meta.{dialects, *}
    // Try parsing as Source first (top-level decls), then fall back to
    // Term (so block-shaped bodies — passed in by the wrapper-arg
    // recursion — also get walked).
    val input = Input.VirtualFile("<user-ast>", src)
    val parsed: Option[Tree] =
      scala.util.Try(dialects.Scala3(input).parse[Source]).toOption
        .flatMap(_.toOption).map(t => t: Tree)
      .orElse(
        scala.util.Try(dialects.Scala3(input).parse[Term]).toOption
          .flatMap(_.toOption).map(t => t: Tree)
      )
    parsed match
      case None => src
      case Some(tree) =>
        case class Splice(start: Int, end: Int, replacement: String)
        val splices = scala.collection.mutable.ListBuffer.empty[Splice]
        def walk(t: Tree): Unit =
          t match
            // receiveWithTimeout(t) { case … }  /  receive(t) { case … }.
            // Body emitted verbatim AFTER a recursive `rewriteActorAstCallsInSource`
            // pass — the enclosing `def` may not be CPS-rewritten in the
            // user-only path, so dropping into `emitCpsExpr` here would mix
            // `_bind`/`_dispatch` calls with the surrounding non-CPS scope.
            // But bodies like `case Exit(from, _) => exit(from, "reason")`
            // do still need their inner intrinsics qualified — the outer
            // splice replaces the whole receive expression, swallowing the
            // case bodies past where the walker would otherwise visit them.
            case app @ Term.Apply.After_4_6_0(
                  Term.Apply.After_4_6_0(Term.Name("receive" | "receiveWithTimeout"), timeoutArgs),
                  pfArgs)
                if pfArgs.values.size == 1 && timeoutArgs.values.size == 1 =>
              val timeoutSrc = timeoutArgs.values.head match
                case Term.Assign(Term.Name("timeout"), v)   => v.syntax
                case Term.Assign(Term.Name("timeoutMs"), v) => v.syntax
                case other: Term                            => other.syntax
              pfArgs.values.head match
                case pf: Term.PartialFunction =>
                  val matcher = buildReceiveMatcherWithRewrittenBodies(pf.cases)
                  splices += Splice(
                    app.pos.start, app.pos.end,
                    s"Actor.receive_t(_registerReceive($matcher), $timeoutSrc)"
                  )
                case _ => ()
              timeoutArgs.values.foreach(walk)
            // receive { case … }
            case app @ Term.Apply.After_4_6_0(Term.Name("receive"), pfArgs)
                if pfArgs.values.size == 1 =>
              pfArgs.values.head match
                case pf: Term.PartialFunction =>
                  val matcher = buildReceiveMatcherWithRewrittenBodies(pf.cases)
                  splices += Splice(
                    app.pos.start, app.pos.end,
                    s"Actor.receive_(_registerReceive($matcher))"
                  )
                case _ => ()
            // pid ! msg → Actor.send(pid, msg).  Walk children first so
            // nested ! / receive inside lhs or msg are processed too.
            // Scala parses `pid ! (a, b)` as a 2-arg infix call
            // (`pid.!(a, b)`) rather than a 1-arg-with-tuple call —
            // reconstruct the tuple syntactically so the emitted code
            // is `Actor.send(pid, (a, b))`.
            case infix @ Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
                if op.value == "!" && argClause.values.nonEmpty =>
              walk(lhs)
              argClause.values.foreach(walk)
              val msgSrc = argClause.values match
                case List(single) => single.syntax
                case many         => many.map(_.syntax).mkString("(", ", ", ")")
              splices += Splice(
                infix.pos.start, infix.pos.end,
                s"Actor.send(${lhs.syntax}, $msgSrc)"
              )
            // Bare-name actor intrinsic at a `def` body position with
            // a non-Any declared return type:
            //
            //   def members(): List[String] = clusterMembers()
            //
            // The runtime `Actor.clusterMembers()` returns `Any`, so the
            // user's `: List[String]` needs an explicit cast.  Splice
            // the whole body as `Actor.<n>(...).asInstanceOf[T]`,
            // recursively rewriting each arg so nested intrinsic calls
            // inside the args get qualified too.  Skipped when the
            // return type is `Any` / `Unit`: the cast would be a no-op
            // and a wholesale body splice could clobber further-down
            // walks (e.g. `spawn { thunk }` whose thunk contains
            // `trapExit(true)` — we want the generic walk to splice
            // the bare names inside the thunk individually).
            //
            // Match BEFORE the generic body walk so the catch-all
            // doesn't double-splice the function name.
            case d: Defn.Def =>
              d.decltpe match
                case Some(retType) if !isAnyOrUnitType(retType) =>
                  d.body match
                    case body: Term.Apply =>
                      body.fun match
                        case Term.Name(n) if isBareActorIntrinsic(n) =>
                          val argsSrc = body.argClause.values
                            .map(arg => rewriteActorAstCallsInSource(arg.syntax))
                            .mkString(", ")
                          splices += Splice(
                            body.pos.start, body.pos.end,
                            s"Actor.$n($argsSrc).asInstanceOf[${retType.syntax}]"
                          )
                        case _ => d.children.foreach(walk)
                    case _ => d.children.foreach(walk)
                case _ => d.children.foreach(walk)
            // Bare-name actor intrinsic call: `joinCluster(seeds, token)` →
            // `Actor.joinCluster(seeds, token)`.  We rewrite only the
            // function-name position, leaving the args + parens alone so
            // nested intrinsic calls inside the args get rewritten in
            // their own recursion.  `receive` / `spawn` / `runActors`
            // have dedicated cases above (or specialised CPS emission)
            // and must NOT route through the bare splice.
            //
            // Walks the args before splicing so right-to-left ordering
            // (`splices.sortBy(-_.start)`) still applies cleanly when an
            // arg contains another bare intrinsic call.
            case Term.Apply.After_4_6_0(fun @ Term.Name(n), argClause)
                if isBareActorIntrinsic(n) =>
              argClause.values.foreach(walk)
              splices += Splice(fun.pos.start, fun.pos.end, s"Actor.$n")
            // `case None =>` / `case Nil =>` get re-parsed by scalameta
            // as `Pat.Extract(None, ())` and the pretty-printer emits
            // `case None()` — which Scala 3 rejects because `None` is a
            // case object (no `unapply`).  Splice the empty-args parens
            // away so the constructor-style pattern collapses back to
            // the singleton form.  Scoped to the two stdlib singletons
            // that surface this; other empty-arg extracts are left
            // intact (`X()` with an actual `unapply` is still valid).
            case pe @ Pat.Extract.After_4_6_0(Term.Name(n), argClause)
                if argClause.values.isEmpty && (n == "None" || n == "Nil") =>
              splices += Splice(pe.pos.start, pe.pos.end, n)
            case other =>
              other.children.foreach(walk)
        walk(tree)
        if splices.isEmpty then src
        else
          // Apply right-to-left so earlier offsets remain valid.
          val ordered = splices.sortBy(-_.start).toList
          val sb = new StringBuilder(src)
          ordered.foreach { sp =>
            sb.replace(sp.start, sp.end, sp.replacement)
          }
          sb.toString

  /** jvm-lazylist-fusion: fuse `LazyList.from(start).map(f)?.take(n).sum` (the receiver of a
   *  terminal `.sum`) into a native `while` loop over the bounded n-element prefix — no lazy
   *  cons cells / thunks (the inherent LazyList cost that made `lazylist-take` ~5.9 ms). The
   *  spliced expression is `Int` (matching `LazyList[Int].sum`), so a trailing `.toLong` outside
   *  the replaced node still applies. `take` is REQUIRED (an unbounded `.sum` would never
   *  terminate), so only bounded prefixes match. Same parse → splice mechanism as
   *  [[rewriteActorAstCallsInSource]] (scalameta 4.13 ships no public `Tree.transform`). */
  private def fuseLazyListInSource(src: String): String =
    if !src.contains("LazyList.from") then return src
    import scala.meta.{dialects, *}
    def tryParse(text: String): Option[Tree] =
      scala.util.Try(dialects.Scala3(Input.VirtualFile("<llf>", text)).parse[Source])
        .toOption.flatMap(_.toOption).map(t => t: Tree)
    // Parse src directly (full source); if that fails — e.g. a user-code FRAGMENT with top-level
    // statements, which `parse[Source]` rejects — retry wrapped in an object, offsetting positions.
    val (parsed, off): (Option[Tree], Int) =
      tryParse(src) match
        case Some(t) => (Some(t), 0)
        case None =>
          val pfx = "object __sscLazyFuse {\n"
          (tryParse(pfx + src + "\n}"), pfx.length)
    parsed match
      case None => src
      case Some(tree) =>
        case class Splice(start: Int, end: Int, replacement: String)
        val splices = scala.collection.mutable.ListBuffer.empty[Splice]
        // recv of `.sum` ≟ `LazyList.from(start).map(1-arg-lambda)?.take(n)` → (start, map?, n).
        def recognize(recv: Term): Option[(Term, Option[(String, Term)], Term)] = recv match
          case Term.Apply.After_4_6_0(Term.Select(inner, Term.Name("take")), tArgs)
              if tArgs.values.lengthCompare(1) == 0 =>
            val nT = tArgs.values.head
            inner match
              case Term.Apply.After_4_6_0(Term.Select(
                     Term.Apply.After_4_6_0(Term.Select(Term.Name("LazyList"), Term.Name("from")), fArgs),
                     Term.Name("map")), mArgs)
                  if fArgs.values.lengthCompare(1) == 0 && mArgs.values.lengthCompare(1) == 0 =>
                mArgs.values.head match
                  case Term.Function.After_4_6_0(pc, body) if pc.values.lengthCompare(1) == 0 =>
                    Some((fArgs.values.head, Some((pc.values.head.name.value, body)), nT))
                  case _ => None
              case Term.Apply.After_4_6_0(Term.Select(Term.Name("LazyList"), Term.Name("from")), fArgs)
                  if fArgs.values.lengthCompare(1) == 0 =>
                Some((fArgs.values.head, None, nT))
              case _ => None
          case _ => None
        def walk(t: Tree): Unit =
          t match
            case sel @ Term.Select(recv, Term.Name("sum")) if recognize(recv).isDefined =>
              val (startT, mapOpt, nT) = recognize(recv).get
              val loopBody = mapOpt match
                case Some((p, body)) => s"val $p = __st + __k; __acc += (${body.syntax})"
                case None            => "__acc += (__st + __k)"
              splices += Splice(sel.pos.start - off, sel.pos.end - off,
                s"{ val __st = (${startT.syntax}); val __n = (${nT.syntax}); var __acc = 0; var __k = 0; " +
                s"while (__k < __n) { $loopBody; __k += 1 }; __acc }")
            case other => other.children.foreach(walk)
        walk(tree)
        if splices.isEmpty then src
        else
          val ordered = splices.sortBy(-_.start).toList
          val sb = new StringBuilder(src)
          ordered.foreach(sp => sb.replace(sp.start, sp.end, sp.replacement))
          sb.toString

  /** Build the `_pfToFun { case … => Some(body); case _ => None }`
   *  string used by the receive-splice in [[rewriteActorAstCallsInSource]]
   *  with each case body recursively passed through the same rewriter
   *  so bare intrinsic calls inside the body (`exit(...)`, `self()`)
   *  get qualified before they land in the spliced source. */
  private def buildReceiveMatcherWithRewrittenBodies(cases: List[scala.meta.Case]): String =
    val sb = StringBuilder()
    sb.append("_pfToFun { ")
    cases.foreach { c =>
      sb.append("case ")
      sb.append(c.pat.syntax)
      c.cond.foreach { g => sb.append(" if "); sb.append(g.syntax) }
      sb.append(" => Some(")
      sb.append(rewriteActorAstCallsInSource(c.body.syntax))
      sb.append("); ")
    }
    sb.append("case _ => None }")
    sb.toString

  // ─── Effect analysis ──────────────────────────────────────────────
  // Extracted to the `JvmGenEffectAnalysis` mixin (JvmGenEffectAnalysis.scala):
  // analyzeEffects / isEffectOpDef / isEffectOpRef / isEffectfulFun.


  // ─── Strategy D, Step 1 ──────────────────────────────────────────
  //
  // `containsEffectPrimitive(tree)` — whitelist predicate used by the
  // dep-mode CPS rewriter (Step 3) to decide which `Defn.Def` bodies
  // need to be CPS-emitted instead of emitted verbatim.
  //
  // **Strict semantics:** only call-site shapes that ARE primitive
  // count.  Selects, user-defined Applies, and type-inferred Anys
  // are deliberately ignored — see specs/dep-cps-rewrite.md §4.4
  // "The hard part — predicate tightness" for why broader rules
  // regress `actors-process-info.ssc`.
  //
  // Step 1 handles intrinsic primitives only.  Step 2 wraps this in
  // a cross-dep-aware predicate that also matches calls to other
  // defs already marked effectful by the fixpoint pass.

  private val randomPrimitiveOps:  Set[String] = Set("nextInt", "nextDouble", "uuid", "pick")
  private val storagePrimitiveOps: Set[String] = Set("get", "put", "remove", "has", "keys")
  private val clockPrimitiveOps:   Set[String] = Set("now", "nowIso", "sleep")
  private val loggerPrimitiveOps:  Set[String] = Set("info", "warn", "error", "debug")
  private val asyncPrimitiveOps:   Set[String] = Set("delay", "async", "await", "parallel")
  // Uuid.v4/v7/v7Monotonic are SideEffect primitives; rawV4/rawV7 deliberately excluded
  private val uuidPrimitiveOps:    Set[String] = Set("v4", "v7", "v7Monotonic")

  /** Step 1 — intrinsic-only predicate (default `crossDepEffectful = empty`).
   *  Step 2 — cross-dep aware variant: also matches `Term.Apply(Term.Name(n), _)`
   *  where `n` is a dep-defined function already marked effectful by the
   *  fixpoint pass.
   *
   *  Walks the entire tree (nested objects, nested defs, lambda bodies,
   *  case bodies, pattern guards, default params).  Over-approximation:
   *  `def outer = { def inner = self(); 42 }` marks BOTH `inner` and
   *  `outer` even though `outer` itself only returns `42`.  That's a
   *  safe false positive: Step 3 CPS-emits both, the extra wrapping on
   *  `outer` is correct (a pure `42` CPS-emits to `42` via `_bind`'s
   *  value branch).  Avoids needing a custom traversal. */
  private[scalascript] def containsEffectPrimitive(
      tree: scala.meta.Tree,
      crossDepEffectful: Set[String] = Set.empty
  ): Boolean =
    tree.collect {
      // Bare actor primitives: `self()`, `link(pid)`, `receive { ... }`, etc.
      case Term.Apply.After_4_6_0(Term.Name(n), _) if actorBareNames(n) => ()
      // Cross-dep: bare call to a known-effectful dep function.
      case Term.Apply.After_4_6_0(Term.Name(n), _) if crossDepEffectful(n) => ()
      // Cross-dep: qualified call to a known-effectful dep method.
      case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(n)), _) if crossDepEffectful(n) => ()
      case Term.Apply.After_4_6_0(
            Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name(n)), _),
            _
          ) if crossDepEffectful(n) => ()
      case Term.Apply.After_4_6_0(
            Term.ApplyType.After_4_6_0(Term.Name(n), _),
            _
          ) if crossDepEffectful(n) => ()
      // Actor send sugar: `pid ! msg` (any infix `!`).
      case Term.ApplyInfix.After_4_6_0(_, Term.Name("!"), _, _) => ()
      // Qualified primitive Selects + Apply: `Actor.send(...)`, etc.
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Actor"),   Term.Name(_)),  _) => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Random"),  Term.Name(op)), _) if randomPrimitiveOps(op)  => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Storage"), Term.Name(op)), _) if storagePrimitiveOps(op) => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Clock"),   Term.Name(op)), _) if clockPrimitiveOps(op)   => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Logger"),  Term.Name(op)), _) if loggerPrimitiveOps(op)  => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Async"),   Term.Name(op)), _) if asyncPrimitiveOps(op)   => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Stream"),  Term.Name(_)), _)    => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Uuid"),    Term.Name(op)), _) if uuidPrimitiveOps(op)    => ()
    }.nonEmpty

  // ─── Strategy D, Step 2 ──────────────────────────────────────────
  //
  // `analyzeDepEffectfulness` — runs the global fixpoint over all
  // dep-defined `Defn.Def` nodes collected by `inlineImport`.
  // Populates `globalEffectfulDeps` for Step 3 to consume.
  //
  // Algorithm:
  //   1. SEED: every dep def whose body contains an intrinsic
  //      primitive → mark effectful.
  //   2. ITERATE: rescan each unmarked def with `crossDepEffectful =
  //      globalEffectfulDeps`; new marks → keep iterating.
  //   3. TERMINATE: when a pass adds nothing.
  //
  // O(deps × defs × calls) — <1 ms for the std corpus.

  /** Index this module's own `Defn.Def`s (top-level + nested) by simple name so a
   *  CPS call to one of them casts its Any-bound args to the declared param types.
   *  Pre-pass so forward refs / mutual recursion resolve. Dep defs win on a name
   *  clash (they're consulted first in `applyCalleeCasts`/`calleeParamType`). */
  private def indexLocalDefSigs(blocks: List[JvmGen.Block]): Unit =
    localDefSigs.clear()
    blocks.foreach { b =>
      ScalaNode.fold(b.node) { tree =>
        tree.collect { case d: Defn.Def => localDefSigs.getOrElseUpdate(d.name.value, d) }
      }
    }

  private[scalascript] def analyzeDepEffectfulness(): Unit =
    globalEffectfulDeps.clear()
    // Seed
    for ((name, defn) <- depDefs)
      if containsEffectPrimitive(defn.body) then
        globalEffectfulDeps += name
    // Iterate to fixpoint
    var changed = true
    while changed do
      changed = false
      for ((name, defn) <- depDefs if !globalEffectfulDeps(name))
        if containsEffectPrimitive(defn.body, globalEffectfulDeps.toSet) then
          globalEffectfulDeps += name
          changed = true


  private def emitBlock(block: JvmGen.Block): String =
    // If the block has no effects content, no mutual-TCO clique, and no
    // last-expression auto-output, the original source compiles as-is —
    // modulo a pass that routes `.mkString(...)` and `s"..."` through
    // `_show` so whole-number Doubles strip trailing ".0" the way the
    // interpreter and JS backends do.
    val rewritten =
      if !blockNeedsRewrite(block.node) && !blockContainsExplicitContextualCall(block.node) then block.src
      else
        val out = StringBuilder()
        ScalaNode.fold(block.node) {
          case Source(stats)     => emitStats(stats, out, isTopLevel = true)
          case Term.Block(stats) => emitStats(stats, out, isTopLevel = true)
          case t: Term           => out.append(wrapAutoOutput(emitExpr(t))).append("\n")
          case _                 => ()
        }
        out.toString
    val routed = routeMkStringThroughShow(rewritten)
    if contentRuntimeEnabled then
      val current = block.contentSectionIndex.map(index => s"Some($index)").getOrElse("None")
      s"_ssc_content_current_section_index = $current\n${routed.stripTrailing}\n_ssc_content_current_section_index = None"
    else routed

  /** Wrap a top-level expression so its non-Unit, non-null result is
   *  printed — mirrors interpreter `autoOutput` and JsGen's `_auto` block.
   *  Goes through the overridden `println` so Doubles strip ".0". */
  private def wrapAutoOutput(expr: String): String =
    // `locally { … }` (not a bare `{ … }`) so a top-level auto-output block FOLLOWING a class/trait/def
    // definition isn't parsed as that definition's body — `case class P(x: Int)` then a bare `{ … }`
    // on the next line silently became `P`'s template, swallowing the statement. (jvmgen-autooutput-after-classdef.)
    s"locally { val _auto: Any = $expr; if _auto != () && _auto != null then println(_auto) }"

  /** Route emitted code through `_show` for the cases where Scala 3's
   *  default Any.toString would print a whole-number Double as "4.0":
   *
   *    - `expr.mkString` / `expr.mkString(...)`
   *                         →  `expr.map(_show).mkString` / `expr.map(_show).mkString(...)`
   *    - `s"... $x ..."`       →  `sx"... $x ..."`  (sx is defined in preamble)
   *
   *  `_show` is identity for non-Doubles, so other element types are
   *  unaffected. The patterns are conservative enough not to match inside
   *  identifiers (e.g. `bytes"..."` is not rewritten because `\bs"` requires
   *  a word boundary immediately before the `s`). */
  private def routeMkStringThroughShow(src: String): String =
    var out = src
    if out.contains(".mkString(") then
      // Scala's no-arg Iterable.mkString is parameterless; `mkString()` parses
      // as applying the returned String (`StringOps.apply`) and fails to compile.
      out = out.replaceAll("""\.mkString\(\)""", ".map(_show).mkString")
      out = out.replaceAll("""\.mkString\(""", ".map(_show).mkString(")
    if out.contains("s\"") || out.contains("s\"\"\"") then
      // Negative lookbehind for `$` or word char so we don't rewrite the `s`
      // in `$s"..."` (the trailing variable reference inside an s-interp) or
      // in user identifiers like `bytes"..."`.
      out = out.replaceAll("""(?<![$\w])s("{1,3})""", "sx$1")
    out

  // ─── Statement emission ───────────────────────────────────────────

  /** Emit a function body:
   *  1. Hoists constant-expression assignments out of while loops.
   *  2. Rewrites `list.foreach(p => body)` that capture outer `var`s to
   *     explicit while-loops (prevents DoubleRef / IntRef boxing).
   *  3. When the foreach is `stable.foreach(p => { acc = acc + f(p) })` with
   *     a Double accumulator and stable (non-var) receiver, hoists the per-
   *     iteration sum out of the outer loop — matching the O(1) strength-
   *     reduction that the SSC bytecode JIT applies to the same pattern. */
  private def emitBodyWithHoisting(body: Term): String =
    body match
      case Term.Block(stats) =>
        val outerVarNames: Set[String] = stats.collect {
          case Defn.Var.After_4_7_2(_, pats, _, _) =>
            pats.collect { case Pat.Var(n) => n.value }
        }.flatten.toSet
        // Vars initialised with a numeric literal → accumulator type, so the
        // hoisted temp matches (avoids Scala type errors). Covers Double / Long /
        // Int accumulators (e.g. `var total = 0.0`, `var sum = 0L`, `var n = 0`).
        val outerVarNumInit: Map[String, String] = stats.collect {
          case Defn.Var.After_4_7_2(_, pats, _, rhs) =>
            val ty = rhs match
              case _: Lit.Double => "Double"
              case _: Lit.Long   => "Long"
              case _: Lit.Int    => "Int"
              case _             => null
            if ty != null then pats.collect { case Pat.Var(n) => (n.value, ty) } else Nil
        }.flatten.toMap
        var idx = 0
        val parts = collection.mutable.ListBuffer.empty[String]
        for stat <- stats do
          stat match
            case w: Term.While =>
              val loopBodyStats: List[Stat] = w.body match
                case Term.Block(ss) => ss
                case s: Stat        => List(s)
              val hoistLines = collection.mutable.ListBuffer.empty[String]
              val newBodyLines = loopBodyStats.map {
                case Term.Assign(lhs, rhs: Term) if isConstantExpr(rhs) =>
                  val name = s"_hoist_$idx"
                  idx += 1
                  hoistLines += s"val $name = ${rhs.syntax}"
                  s"${lhs.syntax} = $name"
                case app: Term.Apply if isForeachCapturingVars(app, outerVarNames) =>
                  // Try to hoist an invariant accumulation sum out of the loop:
                  //   stable.foreach(p => { acc = acc + addend(p) })
                  // → (before loop) val _sum = { …iterate stable once… }
                  // → (in body)     acc = acc + _sum
                  val hoisted: String | Null = app match
                    case Term.Apply.After_4_6_0(
                          Term.Select(Term.Name(stableName), Term.Name("foreach")),
                          Term.ArgClause(List(Term.Function.After_4_6_0(paramClause, fnBody)), _)
                        ) if paramClause.values.lengthCompare(1) == 0
                          && !outerVarNames.contains(stableName) =>
                      val paramName = paramClause.values.head.name.value
                      fnBody match
                        case Term.Block(List(Term.Assign(Term.Name(accName),
                              Term.ApplyInfix.After_4_6_0(Term.Name(acc2), Term.Name("+"), _,
                                ac)))) if ac.values.lengthCompare(1) == 0
                                  && accName == acc2
                                  && outerVarNumInit.contains(accName)
                                  && !referencesAny(ac.values.head, outerVarNames) =>
                          val addend   = ac.values.head
                          val accTy    = outerVarNumInit(accName)
                          val zero     = accTy match { case "Double" => "0.0"; case "Long" => "0L"; case _ => "0" }
                          val sumName  = s"_sum_$idx"
                          val iterName = s"_iter_$idx"
                          idx += 1
                          hoistLines += s"val $sumName = { var _tmp: $accTy = $zero; var $iterName = $stableName; while $iterName.nonEmpty do { val $paramName = $iterName.head; _tmp = _tmp + ${addend.syntax}; $iterName = $iterName.tail }; _tmp }"
                          s"$accName = $accName + $sumName"
                        case _ => null
                    case _ => null
                  if hoisted != null then hoisted
                  else
                    val iterName = s"_iter_$idx"
                    idx += 1
                    emitForeachAsWhile(app, iterName)
                case other => other.syntax
              }
              hoistLines.foreach(parts += _)
              val whileBody = newBodyLines match
                case Seq(single) => s" $single"
                case many        => " {\n    " + many.mkString("\n    ") + "\n  }"
              parts += s"while ${w.expr.syntax} do$whileBody"
            case other =>
              parts += other.syntax
        "{\n  " + parts.mkString("\n  ") + "\n}"
      case other =>
        other.syntax

  /** Rewrite `<list>.foreach(<p> => <body>)` to an explicit while-loop so that
   *  outer `var`s captured by the lambda are not boxed as DoubleRef / IntRef etc. */
  private def emitForeachAsWhile(app: Term.Apply, iterName: String): String =
    app match
      case Term.Apply.After_4_6_0(
            Term.Select(receiver, _),
            Term.ArgClause(List(Term.Function.After_4_6_0(paramClause, fnBody)), _)
          ) =>
        val paramName = paramClause.values.head.name.value
        val bodyStats: List[Stat] = fnBody match
          case Term.Block(ss) => ss
          case s: Stat        => List(s)
        val bodyStr = bodyStats.map(_.syntax).mkString("; ")
        s"{ var $iterName = ${receiver.syntax}; while ($iterName.nonEmpty) { val $paramName = $iterName.head; $bodyStr; $iterName = $iterName.tail } }"
      case _ => app.syntax

  private def emitStats(stats: List[Stat], out: StringBuilder, isTopLevel: Boolean): Unit =
    stats.zipWithIndex.foreach { (s, i) =>
      s match
        case Defn.Var.After_4_7_2(_, pats, Some(tpe), _) =>
          pats.foreach {
            case Pat.Var(n) => declaredVarTypes(n.value) = tpe.syntax
            case _          => ()
          }
        case _ => ()
      val isLast = i == stats.length - 1
      val rendered = emitStat(s)
      val text = s match
        case _: Term if isLast && isTopLevel => wrapAutoOutput(rendered)
        case _                               => rendered
      out.append(text).append("\n")
    }

  private[codegen] def emitEffectfulParamGroups(d: Defn.Def): String =
    if d.paramClauseGroups.isEmpty then "()"
    else d.paramClauseGroups.map(_.syntax).mkString

  private[codegen] def declaredCpsResultType(d: Defn.Def): Option[String] =
    d.decltpe.flatMap {
      case Type.ApplyInfix(_, Type.Name("!"), _) => None
      case Type.Name("Any")                      => None
      case t                                     => Some(t.syntax)
    }

  private[codegen] def emitEffectfulResultType(d: Defn.Def): String =
    declaredCpsResultType(d).map(t => s": $t").getOrElse(": Any")

  private[codegen] def castCpsResultToDeclared(d: Defn.Def, body: String): String =
    declaredCpsResultType(d).map(t => s"($body).asInstanceOf[$t]").getOrElse(body)

  private[codegen] def shouldPreserveCpsDeclaredResult(d: Defn.Def): Boolean =
    // Keep the declared result type ONLY when the def handles its own
    // effects.  A def that merely performs ops handled by its caller must
    // return the unresolved Free tree as Any; casting it here would force
    // `_FlatMap`/`_Perform` to Unit/String/etc. before the runner sees it.
    preserveTotalEffectfulReturnTypes && d.body.collect {
      case Term.Apply.After_4_6_0(Term.Name("handle"), _) => ()
    }.nonEmpty

  /** Extract the first string literal argument from a named annotation in `mods`.
   *  Returns `Some(expr)` when `@name("expr")` is present, `None` otherwise. */
  private def extractAnnotationArg(mods: List[Mod], name: String): Option[String] =
    mods.collectFirst {
      case Mod.Annot(init) if (init.tpe match
        case Type.Name(n)                 => n == name
        case Type.Select(_, Type.Name(n)) => n == name
        case _                            => false) =>
          init.argClauses.headOption.flatMap(_.values.collectFirst { case Lit.String(s) => s })
    }.flatten

  /** Substitute `$0`, `$1`, … in `expr` with the corresponding parameter name. */
  private def substituteArgs(expr: String, params: List[String]): String =
    params.zipWithIndex.foldLeft(expr) { case (e, (name, i)) => e.replace(s"$$$i", name) }

  private def emitStat(stat: Stat): String = stat match
    // arch-ffi-p1 — `@jvm("expr")` on an extern def: emit the expression as
    // the method body, with $0/$1/… substituted by parameter names.
    // Falls through to the plain extern-skip arm when no @jvm annotation exists.
    case d: Defn.Def if EffectAnalysis.isExternDef(d.body) =>
      extractAnnotationArg(d.mods, "jvm") match
        case Some(expr) =>
          val params   = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value).toList
          val body     = substituteArgs(expr, params)
          val paramSig = if d.paramClauseGroups.isEmpty then "()" else d.paramClauseGroups.map(_.syntax).mkString
          val retType  = d.decltpe.map(t => s": ${t.syntax}").getOrElse(": Any")
          s"def ${d.name.value}$paramSig$retType = $body"
        case None =>
          // Stage 5+/A.6 (Б-1) — `extern def foo(...): T = __extern__` is a
          // type-only stub; the intrinsic table provides the real impl
          // (caught at call sites by `dispatchIntrinsic`).  Skip emission
          // so Scala doesn't choke on `__extern__`.
          ""

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
      // A user effect named `Console` REPLACES the preamble's println-shadow
      // object (omitted via JvmRuntimePreamble.sourceFor) — carry the
      // println/print bridges here so Normalize's bare-println rewrite
      // (`println` → `Console.println`) keeps routing through `_show`.
      val consoleBridges =
        if d.name.value == "Console" then
          "\n  def println(v: Any): Unit = scala.Predef.println(_show(v))" +
          "\n  def print(v: Any): Unit   = scala.Predef.print(_show(v))"
        else ""
      s"object ${d.name.value}:\n${ops.mkString("\n")}$consoleBridges\n"

    // Effectful function: emit CPS body
    case d: Defn.Def if isEffectfulFun(d.name.value) =>
      // Preserve the user's declared param types when available.  Inside
      // the CPS-emitted body, field accesses like `cluster.nodes` and
      // `cluster.pids` are emitted verbatim (the Term.Select arm in
      // emitCpsExpr uses the qual's syntax directly when it's a simple
      // name) — so if we widen `cluster` to `Any` those accesses fail
      // with `value nodes is not a member of Any`.  Keeping the declared
      // type works because callers from user code already pass typed
      // values; callers from inside CPS continuations (where vars are
      // Any) typecheck through the runtime's Any/Any glue (`_bind`
      // returns Any, the call's Any args go to a `: T` param via
      // implicit widening at the JVM level since erasure makes T == Any
      // for reference types — and primitives are boxed through
      // `_perform` chains anyway).  Fallback to Any when `decltpe` is
      // absent, matching the previous behaviour for un-annotated params.
      val params = emitEffectfulParamGroups(d)
      // Register declared param types so a CPS-block `var x = <param-expr>` can
      // infer its type (e.g. `var s = start + 1` → Long when `start: Long`),
      // which lets the var stay a real typed mutable `var` across a `while`.
      d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).foreach { p =>
        p.decltpe.foreach(t => declaredVarTypes(p.name.value) = t.syntax)
      }
      // Effect-row defs (`A ! Eff`) still return Any because they may
      // produce a Free value that the caller/handler unwraps. JVM split
      // runtime keeps declared result types for total defs that merely
      // contain handled effects; the WASM effect path disables that
      // preservation so direct `_bind`/`_perform` results are not cast to
      // `Unit`/`Int` before the surrounding handler can interpret them.
      val cpsBody = emitCpsExpr(d.body)
      // Keep the declared result type ONLY when the def handles its own
      // effects (a `handle(...)`/`handle { ... }` inside the body resolves
      // the Free before returning — the benchmark-sink case codex's flag
      // preserved). A def that merely PERFORMS ops handled at the CALL SITE
      // (`val r = handle(greet()) {...}`) returns an UNRESOLVED Free; casting
      // it to the declared type threw ClassCastException (_FlatMap → String
      // in tests/conformance/effects.ssc, _FlatMap → DistributedResult in
      // distributed-map.ssc) before the surrounding handler could run it.
      val preserveDeclared = shouldPreserveCpsDeclaredResult(d)
      val retType =
        if preserveDeclared then emitEffectfulResultType(d)
        else ": Any"
      val body =
        if preserveDeclared then castCpsResultToDeclared(d, cpsBody)
        else cpsBody
      s"def ${d.name.value}$params$retType = $body"

    // Non-effectful function with `T ! Eff` return-type annotation: strip the
    // effect row (not valid Scala syntax) and emit with `: Any` return type.
    // These functions have a pure body (no perform calls) but are declared as
    // effect-typed so callers can pass them to effect runners (sub-effecting).
    case d: Defn.Def if d.decltpe.exists {
      case Type.ApplyInfix(_, Type.Name("!"), _) => true
      case _                                     => false
    } =>
      val params = emitEffectfulParamGroups(d)
      s"def ${d.name.value}$params: Any = ${emitExpr(d.body)}"

    // val/var: always run rhs through emitExpr so constant folding applies
    // even for non-effectful RHS (e.g. `val x = 1 + 2` folds to `val x = 3`).
    // emitExpr routes effectful terms via emitExprDeep and falls back to
    // term.syntax for anything it doesn't special-case.
    // val (a, b) = runXxx(...) — runner returns Any at compile time but a tuple at
    // runtime; Scala 3 warns about the narrowing pattern unless .runtimeChecked is added.
    case Defn.Val(mods, pats, tpe, rhs)
        if pats.exists(_.isInstanceOf[Pat.Tuple]) && termUsesEffects(rhs) =>
      val mod = mods.map(_.syntax).mkString(" ")
      val modStr = if mod.isEmpty then "" else mod + " "
      val patsStr = pats.map(_.syntax).mkString(", ")
      val tpeStr = tpe.map(t => s": ${t.syntax}").getOrElse("")
      s"${modStr}val $patsStr$tpeStr = ${emitExpr(rhs)}.runtimeChecked"

    case Defn.Val(mods, pats, tpe, rhs) =>
      // Record `val x = handle(...)` so a later `x.sum`/`x.length` routes through `_anyCall0`
      // (jvmgen-multishot-handle-result-any) — `_handle` returns Any. Plus Any-taint PROPAGATION
      // (jvmgen-handle-result-mainpath): an untyped val whose rhs is derived from an Any handle result
      // (`val t = (r, r + 1)`) is itself Any-typed, so its accessors/uses also need `_binOp`/`_anyCall0`.
      if tpe.isEmpty then
        val isHandle = isHandleApp(rhs)
        val derived  = !isHandle && termRefsHandleResultVal(rhs)
        if isHandle || derived then pats.foreach {
          case Pat.Var(n) =>
            anyTypedVals += n.value
            if isHandle then handleResultVals += n.value
          case _ => ()
        }
      val mod = mods.map(_.syntax).mkString(" ")
      val modStr = if mod.isEmpty then "" else mod + " "
      val patsStr = pats.map(_.syntax).mkString(", ")
      val tpeStr = tpe.map(t => s": ${t.syntax}").getOrElse("")
      s"${modStr}val $patsStr$tpeStr = ${emitExpr(rhs)}"
    case Defn.Var.After_4_7_2(mods, pats, tpe, rhs: Term) =>
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

    // Function in a mutual tail-recursion clique. When the whole clique has a
    // uniform signature, emit ONE allocation-free dispatch loop (`_tag` + var
    // reassignment) at the canonical member and thin wrappers for all members;
    // other members emit nothing. Otherwise fall back to the per-function
    // closure trampoline (correct, but allocates a thunk per step).
    case d: Defn.Def if isInMutualClique(d.name.value) && mergeableMutualClique(d.name.value) =>
      if d.name.value == canonicalCliqueMember(d.name.value) then emitMergedMutualClique(d.name.value)
      else ""
    case d: Defn.Def if isInMutualClique(d.name.value) =>
      emitMutualTcoFun(d)

    // Body needs custom emit (e.g. SSC named-arg `name: value` syntax in
    // Column/Row calls): re-emit the body through emitExpr so emitCallArg
    // converts `name: value` ascriptions to `name = value` named args.
    // When the body uses effect runners, the runner returns Any; widen
    // the declared return type to Any so Scala 3 doesn't reject the def.
    case d: Defn.Def if termNeedsCustomEmit(d.body) || termContainsExplicitContextualCall(d.body) =>
      val modStr  = if d.mods.isEmpty then "" else d.mods.map(_.syntax).mkString(" ") + " "
      val paramStr = d.paramClauseGroups.map(_.syntax).mkString
      val retStr  = if termUsesEffects(d.body) then ": Any"
                    else d.decltpe.map(t => s": ${t.syntax}").getOrElse("")
      s"${modStr}def ${d.name.value}$paramStr$retStr = ${emitExpr(d.body)}"

    // Hoist loop-invariant constant assignments and rewrite var-capturing foreach
    // closures to while-loops to prevent Ref boxing.
    case d: Defn.Def if containsHoistableWhile(d.body) || containsForeachCapturingVars(d.body) =>
      val modStr   = if d.mods.isEmpty then "" else d.mods.map(_.syntax).mkString(" ") + " "
      val paramStr = d.paramClauseGroups.map(_.syntax).mkString
      val retStr   = d.decltpe.map(t => s": ${t.syntax}").getOrElse("")
      s"${modStr}def ${d.name.value}$paramStr$retStr = ${emitBodyWithHoisting(d.body)}"

    // Strategy D, Step 3 — Defn.Object wrapping dep blocks may contain
    // effectful dep defs (marked by analyzeDepEffectfulness).  Recurse
    // into the body so the `Defn.Def if isEffectfulFun` arm above fires
    // for those defs.  Only triggers when the body TRANSITIVELY contains
    // a dep def name we marked — non-dep objects fall through to .syntax.
    case d: Defn.Object if
        d.collect { case dd: Defn.Def if globalEffectfulDeps(dd.name.value) => () }.nonEmpty =>
      val inner = d.templ.body.stats.map(emitStat).filter(_.nonEmpty).mkString("\n")
      val indented = inner.linesIterator.map("  " + _).mkString("\n")
      // Use brace syntax (not Scala 3 indent) so the
      // `mergeDuplicatePackageObjects` brace-balanced scanner can
      // combine us with other dep blocks that also use braces (those
      // come from the verbatim emit path for pure dep code).
      s"object ${d.name.value} {\n$indented\n}"

    // v2.0 / --bytecode — Defn.Object whose body TRANSITIVELY contains
    // an `extern def foo(...): T = __extern__` stub.  scalameta's
    // `.syntax` would print the stub verbatim and scala-cli would
    // reject it with "Not found: __extern__".  Recurse so the
    // `case d: Defn.Def if EffectAnalysis.isExternDef(d.body)` arm
    // above fires per stub.  Emits brace syntax to stay consistent
    // with the sibling `globalEffectfulDeps` arm; the user-only emit
    // pipeline normalises braces back to colon-indent before
    // [[unwrapPackageObjects]] lifts the wrap to a `package X.Y:` block.
    case d: Defn.Object if
        d.collect { case dd: Defn.Def if EffectAnalysis.isExternDef(dd.body) => () }.nonEmpty =>
      val inner = d.templ.body.stats.map(emitStat).filter(_.nonEmpty).mkString("\n")
      val indented = inner.linesIterator.map("  " + _).mkString("\n")
      s"object ${d.name.value} {\n$indented\n}"

    // Chunk 4 — same recursion for Defn.Class so a `case class Cluster`
    // body containing `def healthCheck = … pid ! msg …` reaches the
    // `Defn.Def if isEffectfulFun` CPS arm.  Conservative trigger: only
    // when the class body transitively names a globally-effectful dep
    // def.  Plain data classes fall through to .syntax untouched
    // (avoids the broad regression that bit the earlier reverted
    // attempt — e.g. `info.links` in actors-process-info.ssc stays
    // outside this path because that file has no effectful dep class).
    case d: Defn.Class if
        d.collect { case dd: Defn.Def if globalEffectfulDeps(dd.name.value) => () }.nonEmpty =>
      emitClassWithRewrittenBody(d)

    // Strip @model annotation — synthetic marker added by `model case class Foo(...)`.
    // Not valid Scala, so must be removed before emitting via scalameta's printer.
    case d: Defn.Class if d.mods.exists {
      case Mod.Annot(init) => init.tpe match
        case Type.Name(n)          => n == "model"
        case Type.Select(_, Type.Name(n)) => n == "model"
        case _ => false
      case _ => false
    } =>
      val stripped = d.mods.filterNot {
        case Mod.Annot(init) => init.tpe match
          case Type.Name(n)          => n == "model"
          case Type.Select(_, Type.Name(n)) => n == "model"
          case _ => false
        case _ => false
      }
      d.copy(mods = stripped).syntax

    case t: Term => emitExpr(t)

    // SSC dep-module imports (`import actors.ProcessInfo`, `import actors.Overflow`)
    // reference SSC namespaces that don't exist as top-level Scala packages in the
    // generated code — dep types are accessed via `_dispatch` at runtime.  Drop them
    // (mirrors how JsGen ignores all `Import` nodes for JS output).  Real Scala/Java
    // imports (`import scala.*`, `import java.*`, `import org.*`, etc.) fall through
    // to the verbatim printer.
    case imp: Import =>
      imp.importers.headOption.map(_.ref.syntax.split('.').headOption.getOrElse(""))
        .filter(sscDepModulePrefixes.contains).map(_ => "")
        .getOrElse(imp.syntax)

    // Everything else: emit as-is via scalameta's printer.
    case other => other.syntax

  /** Build a Defn.Class with the body re-emitted via emitStat per
   *  member (so its effectful `Defn.Def` methods reach the dep-mode
   *  CPS arm).  Reuses the original class signature by string-slicing
   *  scalameta's syntax up to the first body brace.  Falls back to
   *  `d.syntax` verbatim if no body brace is detected (parameter-only
   *  case classes / structural anomalies). */
  private def emitClassWithRewrittenBody(d: Defn.Class): String =
    val raw       = d.syntax
    val bodyStart = raw.indexOf('{')
    if bodyStart < 0 then d.syntax
    else
      val signature = raw.substring(0, bodyStart).trim
      val stats     = d.templ.body.stats
      val inner     = stats.map(emitStat).filter(_.nonEmpty).mkString("\n")
      val indented  = inner.linesIterator.map("  " + _).mkString("\n")
      s"$signature {\n$indented\n}"

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
        s"$target(${argClause.values.map(emitCallArg).mkString(", ")})"
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
        argClause.values.map(emitCallArg).mkString(s"$fname(", ", ", ")")
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
  private[codegen] def emitExpr(term: Term): String = term match
    // Stage 5+/A.4 intrinsic dispatch — fires first.
    case Term.Apply.After_4_6_0(Term.Name(fname), argClause)
        if dispatchIntrinsic(fname, argClause).isDefined =>
      dispatchIntrinsic(fname, argClause).get

    // Stage 5+/B.3 — qualified intrinsic dispatch for `Obj.method(args)`.
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(obj), Term.Name(method)), argClause)
        if dispatchIntrinsic(s"$obj.$method", argClause).isDefined =>
      dispatchIntrinsic(s"$obj.$method", argClause).get

    // handle(body) { cases }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          emitHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => "??? /* invalid handle */"

    // runAsync(body) — coroutine scheduler.  Body is emitted as plain
    // code; Async.* ops check _coHandleTL and suspend/resume the VT.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${emitExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

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

    // runToList() / toList on the result of runStream — the static type is Any
    // because runStream returns Any, so Scala 3 can't resolve the extension.
    // Cast to _Source so the concrete method is reachable.
    case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("runToList" | "toList")), argClause)
        if argClause.values.isEmpty =>
      s"${emitExpr(qual.asInstanceOf[Term])}.asInstanceOf[_Source].runToList()"

    // ── Standard algebraic-effect runners — wrap body in () => thunk ──
    // runXxx { body }  →  runXxx(() => emitExpr(body))
    // Without this, single-block-arg calls hit the `emitExprDeep` "Widget"
    // path and emit `runXxx() { body }` which Scala rejects (wrong arity).
    // Body uses emitExpr (not emitCpsExpr) so while/var inside the body
    // emits as regular Scala — Stream.emit(i) is a direct side-effecting
    // call via the _streamBuf ThreadLocal (no CPS trampoline needed).
    case Term.Apply.After_4_6_0(
        Term.Name("runLogger" | "runLoggerJson" | "runLoggerToList" |
                  "runRandom" | "runClock" | "runHttp" | "runEnv" |
                  "runStream" | "runCache" | "runCacheBypass" |
                  "runTx" | "runRetry" | "runRetryNoSleep"),
        bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val fn   = term.asInstanceOf[Term.Apply].fun.syntax
      val body = emitExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$fn(() => $body)"
    // Curried effect runners: runXxx(extra)(body)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.Name("runRandomSeeded" | "runClockAt" | "runEnvWith" |
                    "runHttpStub" | "runState" | "runAuthWith"),
          extraArgClause),
        bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val outerApply = term.asInstanceOf[Term.Apply].fun.asInstanceOf[Term.Apply]
      val fn    = outerApply.fun.syntax
      val extra = extraArgClause.values.map(v => emitExpr(v.asInstanceOf[Term])).mkString(", ")
      val body  = emitExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$fn($extra)(() => $body)"

    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive" | "receiveWithTimeout"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v)   => v
        case Term.Assign(Term.Name("timeoutMs"), v) => v
        case other: Term                            => other
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
    case Term.Apply.After_4_6_0(Term.Name("spawn_link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.spawn_link(${emitCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("spawnBounded"), argClause)
        if argClause.values.size == 3 =>
      val capSc      = emitExpr(argClause.values(0).asInstanceOf[Term])
      val overflowSc = emitExpr(argClause.values(1).asInstanceOf[Term])
      val thunkSc    = emitCpsExpr(argClause.values(2).asInstanceOf[Term])
      s"Actor.spawnBounded($capSc, $overflowSc, $thunkSc)"
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
        if argClause.values.size >= 1 =>
      val nodeId = emitExpr(argClause.values(0).asInstanceOf[Term])
      val url    = if argClause.values.size >= 2 then emitExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.startNode($nodeId, $url)"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.connectNode(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.connectNode(${emitExpr(argClause.values(0).asInstanceOf[Term])}, ${emitExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("joinCluster"), argClause)
        if argClause.values.size >= 1 =>
      val seeds = emitExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then emitExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.joinCluster($seeds, $token)"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${emitExpr(argClause.values(0).asInstanceOf[Term])}, ${emitExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalRegister"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.globalRegister(${emitExpr(argClause.values(0).asInstanceOf[Term])}, ${emitExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalWhereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.globalWhereis(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — scheduled sends
    case Term.Apply.After_4_6_0(Term.Name("sendAfter"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => emitExpr(v.asInstanceOf[Term]))
      s"Actor.sendAfter(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("sendInterval"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => emitExpr(v.asInstanceOf[Term]))
      s"Actor.sendInterval(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("cancelTimer"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.cancelTimer(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — process introspection
    case Term.Apply.After_4_6_0(Term.Name("processInfo"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.processInfo(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
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
      s"Actor.phiOf(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isSuspect"), argClause)
        if argClause.values.size >= 1 =>
      val nid = emitExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then emitExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
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
      val nid = emitExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then emitExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
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
      s"Actor.setAutoReelect(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — protocol switch + history
    case Term.Apply.After_4_6_0(Term.Name("useRaftLeaderElection"), argClause)
        if argClause.values.isEmpty =>
      "Actor.useRaftLeaderElection()"
    case Term.Apply.After_4_6_0(Term.Name("useExternalCoordinator"), argClause)
        if argClause.values.size == 4 =>
      val vs = argClause.values.map(v => emitExpr(v.asInstanceOf[Term]))
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
      s"Actor.setDraining(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
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
      val n0 = emitExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterMetricSet($n0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricGet"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricGet(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSum"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricSum(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricNames"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMetricNames()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeMetricEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeMetricEvents()"
    // v1.23 — auto-reconnect policy (2- or 3-arg form)
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 2 =>
      val ini = emitExpr(argClause.values(0).asInstanceOf[Term])
      val mx  = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx)"
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 3 =>
      val ini    = emitExpr(argClause.values(0).asInstanceOf[Term])
      val mx     = emitExpr(argClause.values(1).asInstanceOf[Term])
      val giveUp = emitExpr(argClause.values(2).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx, $giveUp)"
    // v1.23 — per-link heartbeat tuning
    case Term.Apply.After_4_6_0(Term.Name("setHeartbeatTimeout"), argClause)
        if argClause.values.size == 2 =>
      val iv   = emitExpr(argClause.values(0).asInstanceOf[Term])
      val dead = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setHeartbeatTimeout($iv, $dead)"
    // v1.23 — quorum-aware Bully threshold
    case Term.Apply.After_4_6_0(Term.Name("setQuorumSize"), argClause)
        if argClause.values.size == 1 =>
      val n = emitExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.setQuorumSize($n)"
    // v1.23 — cluster endpoint shared-secret
    case Term.Apply.After_4_6_0(Term.Name("setClusterAuthToken"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setClusterAuthToken(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — periodic gossip re-discovery
    case Term.Apply.After_4_6_0(Term.Name("requestGossip"), argClause)
        if argClause.values.isEmpty =>
      "Actor.requestGossip()"
    // v1.23 — cluster configuration distribution
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigSet"), argClause)
        if argClause.values.size == 2 =>
      val k0 = emitExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterConfigSet($k0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigGet"), argClause)
        if argClause.values.size == 1 =>
      val k0 = emitExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.clusterConfigGet($k0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigKeys"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterConfigKeys()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeConfigEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeConfigEvents()"

    // Focus[T](_.a.b) / Focus(_.a.b) — lower to a Lens(get, set) literal.
    // The lambda body's field-access chain becomes nested get + nested copy.
    case app: Term.Apply if isFocusApp(app) =>
      emitFocus(app)

    // Prism[Outer, Variant] — lower to a Prism(getOption, reverseGet) literal.
    case ta: Term.ApplyType if isPrismApplyType(ta) =>
      emitPrism(ta)

    // direct[M] { stmts } — v1.8 do-notation sugar
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("direct"), typeArgClause), argClause)
        if argClause.values.size == 1 =>
      val typeArg = typeArgClause.values.headOption.getOrElse(Type.Name("?"))
      DirectTypeUtils.validateDirectTypeArg(typeArg)
      argClause.values.head match
        case block: Term.Block => emitDirectBlock(block.stats)
        case single: Term      => emitExpr(single)
        case null              => "??? /* direct: expected block */"

    case app: Term.Apply if emitExplicitContextualCall(app.fun, app.argClause).isDefined =>
      emitExplicitContextualCall(app.fun, app.argClause).get

    // Registered (non-native) interpolator — jvmEmit takes precedence over raw Scala syntax.
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if !jvmNativeInterpolators.contains(prefix)
        && scalascript.compiler.plugin.InterpolatorRegistry.lookup(prefix).isDefined =>
      val partStrs = parts.map(_.asInstanceOf[Lit.String].value)
      val argStrs  = args.map(a => emitExpr(a.asInstanceOf[Term]))
      scalascript.compiler.plugin.InterpolatorRegistry.lookup(prefix).get.jvmEmit(partStrs, argStrs)

    // Constant folding: if(true)/if(false) branch elimination.
    // Applied before termNeedsCustomEmit so even simple dead branches are dropped.
    case t: Term.If =>
      t.cond match
        case Lit.Boolean(true)  => emitExpr(t.thenp)
        case Lit.Boolean(false) => t.elsep match
          case Lit.Unit() => "()"
          case e          => emitExpr(e)
        case _ if termNeedsCustomEmit(t) || termContainsExplicitContextualCall(t) => emitExprDeep(t)
        case _                           => t.syntax

    // Constant folding: literal-only binary arithmetic/comparison/logic.
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.length == 1 =>
      foldConstantScala(lhs, op.value, argClause.values.head.asInstanceOf[Term]) match
        case Some(folded) => folded
        case None =>
          if termNeedsCustomEmit(term) || termContainsExplicitContextualCall(term) then emitExprDeep(term) else term.syntax

    // If the term has nested effect / optics content or a flat explicit contextual call, walk children.
    case _ if termNeedsCustomEmit(term) || termContainsExplicitContextualCall(term) => emitExprDeep(term)

    // Otherwise emit Scala source as-is.
    case other => other.syntax

  private[codegen] def isFocusApp(app: Term.Apply): Boolean = app.fun match
    case Term.Name("Focus")                                => true
    case ta: Term.ApplyType                                =>
      ta.fun match { case Term.Name("Focus") => true; case _ => false }
    case _                                                 => false

  private[codegen] def isPrismApplyType(ta: Term.ApplyType): Boolean = ta.fun match
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

  /** Emit a single function call argument, converting SSC `name: value` named-arg
   *  syntax (represented as Term.Ascribe) to Scala `name = value`. */
  private def emitCallArg(arg: Term): String = arg match
    case Term.Ascribe(Term.Name(n), tpe) => s"$n = ${tpe.syntax}"
    case f @ Term.Function.After_4_6_0(_, body) if termUsesEffects(body) =>
      emitCpsExpr(f)
    case other                           => emitExpr(other)

  private def contextualCalleeName(fun: Term): Option[String] = fun match
    case Term.Name(n) => Some(n)
    case Term.ApplyType.After_4_6_0(Term.Name(n), _) => Some(n)
    case _ => None

  private def contextualCallShape(d: Defn.Def): Option[(Int, Int)] =
    def isUsingClause(clause: Term.ParamClause): Boolean =
      clause.mod.exists(_.is[Mod.Using]) ||
        clause.values.exists(_.mods.exists(_.is[Mod.Using]))

    val termClauses = d.paramClauseGroups.flatMap(_.paramClauses)
    val regularClauses = termClauses.filterNot(isUsingClause)
    val usingParamCount = termClauses.filter(isUsingClause).map(_.values.size).sum
    val contextBoundCount =
      d.paramClauseGroups.flatMap(_.tparamClause.values).map(_.bounds.context.size).sum
    val contextualParamCount = usingParamCount + contextBoundCount
    if contextualParamCount > 0 && regularClauses.size == 1 && regularClauses.head.values.nonEmpty then
      Some((regularClauses.head.values.size, contextualParamCount))
    else None

  private def explicitContextualCallShape(fun: Term, argClause: Term.ArgClause): Option[(Int, Int)] =
    if argClause.mod.nonEmpty then None
    else
      for
        name <- contextualCalleeName(fun)
        d <- depDefs.get(name).orElse(localDefSigs.get(name))
        shape <- contextualCallShape(d)
        if argClause.values.size == shape._1 + shape._2
      yield shape

  private def emitExplicitContextualCall(fun: Term, argClause: Term.ArgClause): Option[String] =
    explicitContextualCallShape(fun, argClause).map { case (regularParamCount, _) =>
      val regularArgs = argClause.values.take(regularParamCount).map(emitCallArg).mkString(", ")
      val usingArgs = argClause.values.drop(regularParamCount).map(emitCallArg).mkString(", ")
      s"${emitExpr(fun)}($regularArgs)(using $usingArgs)"
    }

  private def termContainsExplicitContextualCall(t: Term): Boolean =
    treeContainsExplicitContextualCall(t)

  private def blockContainsExplicitContextualCall(node: ScalaNode): Boolean =
    ScalaNode.fold(node)(treeContainsExplicitContextualCall)

  private def treeContainsExplicitContextualCall(tree: Tree): Boolean =
    var found = false
    def walk(t: Tree): Unit =
      if !found then t match
        case app: Term.Apply if explicitContextualCallShape(app.fun, app.argClause).isDefined =>
          found = true
        case _ => t.children.foreach(walk)
    walk(tree)
    found

  /** Emit a term that contains effect-related content, walking children. */
  private def emitExprDeep(term: Term): String = term match
    case Term.Block(stats) =>
      val sb2 = StringBuilder()
      sb2.append("{\n")
      stats.foreach { s => sb2.append("  ").append(emitStat(s)).append("\n") }
      sb2.append("}")
      sb2.toString
    case t: Term.If =>
      t.cond match
        case Lit.Boolean(true)  => emitExpr(t.thenp)
        case Lit.Boolean(false) => t.elsep match
          case Lit.Unit() => "()"
          case e          => emitExpr(e)
        case _ =>
          // A comparison on an Any-typed handle-result val lowers to `_binOp(">", r, 5)` which
          // returns Any, so the `if` condition needs an explicit Boolean cast (jvmgen-handle-result-mainpath).
          val condStr = emitExpr(t.cond)
          val cond = if termContainsHandleResultArith(t.cond) then s"$condStr.asInstanceOf[Boolean]" else condStr
          s"if $cond then ${emitExpr(t.thenp)} else ${emitExpr(t.elsep)}"
    case ta: Term.ApplyType if isPrismApplyType(ta) =>
      emitPrism(ta)
    case app: Term.Apply =>
      emitExplicitContextualCall(app.fun, app.argClause).getOrElse {
        app.fun match
          case Term.Apply.After_4_6_0(Term.Name("handle"), _) =>
            emitExpr(app)  // re-route to handle path
          case _ if isFocusApp(app) =>
            emitFocus(app)
          case Term.Select(qual, Term.Name(m)) =>
            val q = emitExpr(qual)
            val args = app.argClause.values.map(emitCallArg).mkString(", ")
            s"$q.$m($args)"
          case fun =>
            val f = emitExpr(fun)
            // Widget { body } pattern: bare-name call with a single block arg.
            // Emit as `Widget() { body }` (empty first clause + trailing block)
            // ONLY when the callee is a known CURRIED def (≥2 param clauses,
            // e.g. `def vstack(gap: Int = 0)(children: => Any)` from a dep or
            // the local module) so the block reaches the trailing clause.
            // Everything else — single-clause defs and unknown callees such as
            // the preamble's `computed`/`effect(thunk: => A)`, `validate { }`,
            // `runActors { }`, `handle { }` — keeps the plain `f({ block })`
            // form; the unconditional `f() { block }` emission broke every
            // single-thunk callee ("missing argument for parameter thunk",
            // jvmgen-block-call-empty-parens, 4 conformance fails).
            app.argClause.values match
              case List(block: Term.Block) if fun.isInstanceOf[Term.Name]
                  && depDefs.get(fun.asInstanceOf[Term.Name].value)
                       .orElse(localDefSigs.get(fun.asInstanceOf[Term.Name].value))
                       .exists(_.paramClauseGroups.flatMap(_.paramClauses).length >= 2) =>
                s"$f() ${emitExprDeep(block)}"
              case _ =>
                // jvmgen-handle-result-mainpath: a call arg that references an Any-typed handle-result
                // val (`dbl(r)`) is cast to the callee's declared param type, mirroring the CPS-path
                // `applyCalleeCasts` — otherwise `dbl(r)` fails (Found Any / Required Int).
                val calleeName = fun match { case Term.Name(n) => Some(n); case _ => None }
                val args = app.argClause.values.zipWithIndex.map { (a, i) =>
                  val emitted = emitCallArg(a)
                  a match
                    case Term.Ascribe(_, _) => emitted
                    case _ if termRefsHandleResultVal(a) =>
                      calleeName.flatMap(n => calleeParamType(n, i, None, Map.empty)) match
                        case Some(t) => s"$emitted.asInstanceOf[$t]"
                        case None    => emitted
                    case _ => emitted
                }.mkString(", ")
                // A handle-result val applied as a function (`threaded(0)`) is Any-typed;
                // cast the callee to `Any => Any` so the application type-checks.
                // (deep-handler state threading — v1-jvm-state-threaded-handler-codegen.)
                val fApp = calleeName match
                  case Some(n) if (handleResultVals.contains(n) || anyTypedVals.contains(n))
                                  && app.argClause.values.lengthCompare(1) == 0 =>
                    s"$f.asInstanceOf[Any => Any]"
                  case _ => f
                s"$fApp($args)"
      }
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val args = argClause.values
      val constResult =
        if args.length == 1 then foldConstantScala(lhs, op.value, args.head.asInstanceOf[Term]) else None
      constResult.getOrElse {
        val l = emitExpr(lhs)
        val r = args.map(v => emitExpr(v.asInstanceOf[Term])).mkString(", ")
        op.value match
          // v1.6 actors: `pid ! msg` always lowers to Actor.send.
          case "!" => s"Actor.send($l, $r)"
          // Arithmetic / comparison: operands may be Any (e.g. Async.await result),
          // so delegate to the same _binOp helper used in CPS context.
          case "+" | "-" | "*" | "/" | "%" |
               "<" | ">" | "<=" | ">="    => s"""_binOp("${op.value}", $l, $r)"""
          case other                      => s"$l $other $r"
      }
    case Term.Select(qual, name)
        if termRefsHandleResultVal(qual) && anyCall0Methods(name.value) =>
      // jvmgen-multishot-handle-result-any / -mainpath: a 0-arg collection method whose receiver is
      // (or contains) an Any-typed `handle(...)` result — `all.sum`, `List(r, r).sum` — routes through
      // `_anyCall0` (dynamic dispatch), since the raw `.sum` doesn't type-check on Any / List[Any].
      s"""_anyCall0(${emitExpr(qual)}, "${name.value}")"""
    case Term.Select(qual, name) =>
      s"${emitExpr(qual)}.${name.value}"
    // jvmgen-handle-result-mainpath: recurse the scrutinee + arm bodies + guards so a handle-result
    // val used in a `match` arm (`r match { case _ => r * 2 }`) lowers through `_binOp` rather than
    // falling to the `.syntax` raw fallback (which Scala 3 rejects on the Any-typed result).
    case t: Term.Match =>
      val arms = t.casesBlock.cases.map { c =>
        val guard = c.cond.map { g =>
          val gs = emitExpr(g)
          s" if ${if termContainsHandleResultArith(g) then s"$gs.asInstanceOf[Boolean]" else gs}"
        }.getOrElse("")
        s"case ${c.pat.syntax}$guard => ${emitExpr(c.body)}"
      }.mkString("; ")
      s"(${emitExpr(t.expr)} match { $arms })"
    case Term.Tuple(args) =>
      s"(${args.map(emitExpr).mkString(", ")})"
    case other => other.syntax

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

  private def emitDirectBlock(stats: List[Stat]): String =
    checkDirectBlockStatics(stats)
    val expanded = DirectAnorm.expand(stats)
    if expanded.isEmpty then "()"
    else
      val varNames: Set[String] = expanded.collect {
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, _) => n.value
      }.toSet
      def go(remaining: List[Stat]): String = remaining match
        case Nil => "()"
        case List(t: Term)  => emitExpr(t)
        case List(other)    => s"{ ${other.syntax} }"
        case Term.Assign(Term.Name(x), rhs) :: rest if varNames.contains(x) =>
          s"{ $x = ${emitExpr(rhs)}\n${go(rest)} }"
        case Term.Assign(Term.Name(x), rhs) :: rest =>
          s"${emitExpr(rhs)}.flatMap { $x =>\n${go(rest)}\n}"
        case Defn.Val(_, List(_: Pat.Wildcard), _, rhs) :: rest =>
          s"${emitExpr(rhs)}.flatMap { _ =>\n${go(rest)}\n}"
        case Defn.Val(_, List(Pat.Var(n)), _, rhs) :: rest =>
          s"{ val ${n.value} = ${emitExpr(rhs)}\n${go(rest)} }"
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) :: rest =>
          s"{ var ${n.value} = ${emitExpr(rhs)}\n${go(rest)} }"
        case (t: Term) :: rest =>
          s"{ ${emitExpr(t)}\n${go(rest)} }"
        case _ :: rest => go(rest)
      go(expanded)

  /** Emit a Scala matcher closure for a `receive { case … }` block.
   *  Type: `(msg: Any) => Option[Any]` — `Some(bodyComputation)` on
   *  match, `None` on miss.  Case bodies are CPS-emitted so any nested
   *  Actor / Async / handle effects compose into the actor's pending
   *  Computation.
   *  Emits as `_pfToFun({ case pat => Some(...); case _ => None })`.
   *  `_pfToFun` accepts `PartialFunction[Any, Option[Any]]` and
   *  returns a total `Any => Option[Any]`. */
  private[codegen] def emitReceiveMatcher(cases: List[Case]): String =
    emitReceiveMatcherOpt(cases, cpsBody = true)

  /** Variant of [[emitReceiveMatcher]] that emits the case body either
   *  in CPS form (`cpsBody = true`, for dep-mode emit where the body
   *  lives inside an `emitCpsExpr` continuation) or verbatim
   *  (`cpsBody = false`, used by the user-only-emit AST post-pass in
   *  [[rewriteActorAstCallsInSource]] — bodies there execute in the
   *  surrounding non-CPS scope, so a CPS-shaped body would mix
   *  `_bind`/`_dispatch` with plain calls and trip the typer).
   *
   *  Mirror of the `cpsBody` parameter retired in c7523e14 — re-added
   *  for the user-only emit path, kept private so the dep-mode caller
   *  doesn't have to know about it. */
  private def emitReceiveMatcherOpt(cases: List[Case], cpsBody: Boolean): String =
    val sb = StringBuilder()
    sb.append("_pfToFun { ")
    cases.foreach { c =>
      sb.append("case ")
      sb.append(c.pat.syntax)
      c.cond.foreach { g => sb.append(" if "); sb.append(g.syntax) }
      sb.append(" => Some(")
      sb.append(if cpsBody then emitCpsExpr(c.body) else c.body.syntax)
      sb.append("); ")
    }
    sb.append("case _ => None }")
    sb.toString

  /** Emit `handle(body) { cases }` as a `_handle(...)` call with CPS body. */
  private[codegen] def emitHandleForm(body: Term, cases: List[Case]): String =
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
    // Optional return clause: `case Return(x) => expr` (unqualified `Return`, vs the
    // qualified `Eff.op` effect cases). Emits a `retMap: Any => Any` and routes through
    // `_handleWithReturn` so the body's pure completion maps through it (deep-handler
    // accumulation). No `Return` case → the plain `_handle` (identity completion).
    val returnCase = cases.find { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Name("Return"), _) => true
        case _                                               => false
    }
    returnCase match
      case None =>
        s"""_handle($bodyThunk, Set(${handled.mkString(", ")}), Map(
  $handlersMap
))"""
      case Some(c) =>
        val binder = c.pat match
          case Pat.Extract.After_4_6_0(_, ac) if ac.values.nonEmpty =>
            ac.values.head match
              case Pat.Var(n) => Some(n.value)
              case _          => None
          case _ => None
        val retBody = emitCaseBody(c.body)
        val retMap = binder match
          case Some(name) => s"((_rv: Any) => { val $name = _rv; $retBody })"
          case None       => s"((_rv: Any) => { $retBody })"
        s"""_handleWithReturn($bodyThunk, Set(${handled.mkString(", ")}), Map(
  $handlersMap
), $retMap)"""

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
    // Arithmetic / comparison on Any-typed handler operands (e.g. `resume(a * b + 1)`,
    // `resume(k * 10)`): the op-args `_args(i)` are bound as `Any`, so `a * b` raw is rejected by
    // Scala 3 — lower to the `_binOp` runtime helper, exactly as `emitExpr` does in CPS context.
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 &&
           Set("+", "-", "*", "/", "%", "<", ">", "<=", ">=").contains(op.value) =>
      val l = emitCaseBody(lhs)
      val r = emitCaseBody(argClause.values.head)
      s"""_binOp("${op.value}", $l, $r)"""
    case Term.Apply.After_4_6_0(fun, argClause) =>
      val f = emitCaseBody(fun)
      val a = argClause.values.map(emitCaseBody).mkString(", ")
      // `resume(())(x)`: applying the RESULT of a call. In a handler body that
      // intermediate is Any-typed, so cast it to a function before applying.
      // (deep-handler state threading — v1-jvm-state-threaded-handler-codegen.)
      fun match
        case _: Term.Apply => s"($f).asInstanceOf[Any => Any]($a)"
        case _             => s"$f($a)"
    case Term.Function.After_4_6_0(paramClause, body) =>
      // Annotate each param with its declared type (or `: Any` when un-annotated) so a
      // lambda that lands in an Any-typed handler slot has an inferable parameter type.
      // Without this the deep-handler state-threading arm `(s: Int) => resume(())(s + n)`
      // emitted as bare `s => …` and Scala 3 could not infer `s`.
      // (v1-jvm-state-threaded-handler-codegen.)
      val ps = paramClause.values.map { p =>
        p.decltpe.map(t => s"${p.name.value}: ${t.syntax}").getOrElse(s"${p.name.value}: Any")
      }.mkString(", ")
      s"($ps) => ${emitCaseBody(body)}"
    case Term.Block(stats) =>
      val items = stats.map {
        case t: Term => emitCaseBody(t)
        case s       => s.syntax
      }
      "{ " + items.mkString("; ") + " }"
    // `if` in a handler body: recurse so a comparison on an Any-typed op-arg in the condition
    // (e.g. `if k > 2 then resume(k) else resume(0)`) lowers to `_binOp` rather than emitting `k > 2`
    // raw, which Scala 3 rejects on `Any` (jvmgen-effect-handler-arg-arith, control-flow case).
    case t: Term.If =>
      // The condition is Any-typed here (op-args are Any, and `_binOp(">", …)` returns Any), so cast
      // to Boolean for the `if` — the comparison genuinely yields a Boolean at runtime.
      s"(if (${emitCaseBody(t.cond)}.asInstanceOf[Boolean]) ${emitCaseBody(t.thenp)} else ${emitCaseBody(t.elsep)})"
    // ── Systemic (jvmgen-emitcasebody-systemic): recurse EVERY remaining composite term so the
    //    `.syntax` raw fallback below only ever sees atoms (Lit / Name). A composite that fell to
    //    `.syntax` while containing an Any-typed effectful sub-op (arithmetic / comparison / flatMap)
    //    is exactly the recurring JVM-handler-codegen bug class — recursing transforms the sub-op. ──
    case t: Term.Match =>
      val arms = t.casesBlock.cases.map { c =>
        val guard = c.cond.map(g => s" if ${emitCaseBody(g)}.asInstanceOf[Boolean]").getOrElse("")
        s"case ${c.pat.syntax}$guard => ${emitCaseBody(c.body)}"
      }.mkString("; ")
      s"(${emitCaseBody(t.expr)} match { $arms })"
    case Term.Tuple(args) =>
      s"(${args.map(emitCaseBody).mkString(", ")})"
    case Term.Ascribe(expr, tpe) =>
      s"(${emitCaseBody(expr)}: ${tpe.syntax})"
    case Term.Select(qual, name) =>
      s"${emitCaseBody(qual)}.${name.value}"
    // General infix beyond arithmetic/`::` (e.g. `==`, `!=` on Any — which type-check raw): recurse
    // both operands so any nested Any-typed arithmetic inside them still lowers via `_binOp`.
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) if argClause.values.lengthCompare(1) == 0 =>
      s"(${emitCaseBody(lhs)} ${op.value} ${emitCaseBody(argClause.values.head)})"
    case other => other.syntax
