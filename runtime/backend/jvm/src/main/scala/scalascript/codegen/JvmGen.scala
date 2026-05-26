package scalascript.codegen

import scalascript.ast.*
import scalascript.transform.{DirectAnorm, DirectTypeUtils, EffectAnalysis}
import scalascript.typeddata.TypedJsonCodecRuntime
import scalascript.sql.js.SqlRuntimeJsEmit
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
    JvmGen(baseDir, intrinsics, lockPath, frontendOverride).genModule(module)

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
      lockPath:   Option[os.Path] = None
  ): String =
    JvmGen(baseDir, intrinsics, lockPath).genUserOnly(module)

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
      lockPath:   Option[os.Path] = None
  ): (String, Map[Int, Int]) =
    JvmGen(baseDir, intrinsics, lockPath).genUserOnlyWithLineMap(module)

  /** Block carries its original `.ssc` source-line offset so the emitter
   *  can build a JSR-45 SMAP line map.  `lineOffset` is the 1-based line
   *  in the `.ssc` where the block's `src` content begins (the line
   *  immediately after the opening fence ```scalascript line).  When
   *  unknown (synthesised blocks, imports), `lineOffset` is 0 and the
   *  block contributes no entries to the line map. */
  private case class Block(node: ScalaNode, src: String, lineOffset: Int = 0)
  /** A heading-bound `html` / `css` code block: render to a string in the
   *  same source position as the surrounding parsed blocks, then bind to
   *  `<sectionId>.<lang>` (html or css) at the end of the module. */
  private case class StringBlockEntry(lang: String, src: String, sectionId: String, order: Int)

class JvmGen(
    baseDir:          Option[os.Path] = None,
    intrinsics:       Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
    lockPath:         Option[os.Path] = None,
    frontendOverride: Option[String]  = None):
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
  // v1.26 — sequential counter driving emitted `_sqlBlock_<N>` value names,
  // and per-section book-keeping so only the first sql block in each section
  // gets the friendly `<sectionId>.sql` alias (Phase 6.C, mirrors Spark
  // Phase C.2 convention).
  private var sqlBlockCounter: Int = 0
  private val sqlPerSection = mutable.Map.empty[String, Int]
  // v1.30 Phase 4 — @side=client sql blocks collected during collectBlocks;
  // injected into the browser JS bundle for frontend (SPA) modules only.
  // Each entry: (source, dbName, sectionAlias)
  private val clientSqlBlocksList =
    mutable.ListBuffer.empty[(String, Option[String], Option[String])]
  // Maps resolved path → pkg segments so the alias generator can qualify names
  // even when a file was already inlined (diamond import case).
  private val importedPkgs  = mutable.Map.empty[String, List[String]]

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
  private val depDefs = mutable.Map.empty[String, scala.meta.Defn.Def]
  private val globalEffectfulDeps = mutable.Set.empty[String]

  // `depClasses` — every `Defn.Class` (incl. case classes) found inside
  // an inlined dep, keyed by simple name.  Used by `emitCpsApply` to
  // resolve constructor calls (`Cluster(nodeList, pids)`) so we can
  // cast `_tN`-named args to the declared field types — otherwise
  // typed constructor signatures reject Any-bound args from the
  // surrounding CPS continuation.  Populated alongside `depDefs` in
  // `inlineImport`.
  private val depClasses = mutable.Map.empty[String, scala.meta.Defn.Class]

  // `depTypeNames` — every type-defining decl (Defn.Class, Defn.Trait,
  // Defn.Enum) found in an inlined dep, mapped to its package path.
  // Used by `calleeParamType` to qualify dep type names in injected
  // `asInstanceOf[…]` so they resolve at the user call site even when
  // the user didn't explicitly import that type (e.g. `StageOp` is the
  // sealed trait extended by `MapOp` / `FilterOp` — users only import
  // the concrete cases).
  private val depTypeNames = mutable.Map.empty[String, List[String]]

  // Test hooks — Step 2 unit tests populate `depDefs` via
  // `seedDepDefsForTest` (which mirrors what `inlineImport` does
  // post-rewrite) and read back `globalEffectfulDeps` after
  // `analyzeDepEffectfulness()` runs.
  private[scalascript] def globalEffectfulDepsForTest: mutable.Set[String] = globalEffectfulDeps
  private[scalascript] def seedDepDefsForTest(module: Module): Unit =
    val blocks = collectBlocks(module.sections)
    blocks.foreach { b =>
      ScalaNode.fold(b.node) { tree =>
        tree.collect { case d: Defn.Def => depDefs(d.name.value) = d }
      }
    }

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
    // Strategy D, Step 2 — fixpoint over the dep call graph runs AFTER
    // collectBlocks (all `inlineImport` calls have populated `depDefs`)
    // and BEFORE emit so Step 3 can consult `globalEffectfulDeps`.
    analyzeDepEffectfulness()
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
    val apiClients = module.manifest.toList.flatMap(_.apiClients)
    val objectStores = module.manifest.toList.flatMap(_.objectStores).filter(s => s.sync == "client-server" && s.valueType.nonEmpty)
    val graphStores = module.manifest.toList.flatMap(_.graphs)
    val frontendFramework = module.manifest.flatMap(_.frontendFramework)
    val effectiveFrontend = frontendOverride.orElse(frontendFramework)
    val usesObjectStore = blocksUseObjectStore(blocks)
    val usesGraph = blocksUseGraph(blocks) || graphStores.nonEmpty
    if blocksUseMcp(blocks) then
      sb.append(s"""//> using dep "$JvmMcpDep"\n""")
    if blocksUseTypedData(blocks) then
      sb.append(sscJarDirective("scalascript-backend-typed-data-runtime"))
    if usesGraph then
      sb.append(sscJarDirective("scalascript-backend-typed-data-runtime"))
      sb.append(sscJarDirective("scalascript-backend-graph-runtime"))
      graphRuntimeDeps(graphStores).foreach(dep => sb.append(s"""//> using dep "$dep"\n"""))

    // Frontend SPA — pull in the frontend-core + framework-specific JARs so
    // the UI primitives can reference scalascript.frontend.* types at runtime.
    if effectiveFrontend.isDefined then
      sb.append(sscJarDirective("scalascript-frontend-core"))
      if effectiveFrontend.contains("swing") then
        sb.append(sscJarDirective("scalascript-backend-spi"))
        if apiClients.nonEmpty then
          sb.append(sscJarDirective("scalascript-backend-typed-data-runtime"))
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

    sb.append(preamble)
    sb.append(commonRuntime)
    sb.append(generatorRuntime)
    sb.append(fsRuntime)
    sb.append(htmlDslTagBindings(collectUserTopNames(blocks)))
    if effectOps.nonEmpty                                  then sb.append(effectsRuntime)
    if mutualGroups.nonEmpty                               then sb.append(mutualTcoRuntime)
    if blocksUseReactive(blocks)                           then sb.append(reactiveRuntime)
    // serveRuntime is also emitted when MCP is used so that `serveMcp(Transport.Http|Ws(...))`
    // can drive the JVM HTTP+WS server via route() / onWebSocket() / serve() instead of
    // throwing "not yet supported".  See JvmRuntimeMcp serveMcp(Transport.Http/Ws) arms.
    if effectOps.nonEmpty || blocksUseRoutes(blocks) || frontmatterRoutes.nonEmpty || objectStores.nonEmpty || blocksUseJson(blocks) || blocksUseMcp(blocks) || effectiveFrontend.isDefined then sb.append(serveRuntime)
    if blocksUseMcp(blocks)                                                          then sb.append(JvmRuntimeMcp)
    if blocksUseDataset(blocks)                                                      then sb.append(JvmRuntimeDataset)

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
    emitTypedRouteClientMetadata(apiClients, sb)
    if effectiveFrontend.contains("swing") then
      emitSwingTypedRouteClients(apiClients, sb)

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

    val fixedHead = sb.substring(0, preambleLen)
    val userSrc   = sb.substring(preambleLen)
    // Inject UI helper functions (top-level) + primitives object block when
    // the module uses a frontend framework.  Helpers are prepended so they're
    // defined before the `import std.ui.primitives.{serve,...}` line and can
    // therefore call the preamble's `serve(port)` without the import shadowing
    // it.  The primitives colon-block is appended so `mergeDuplicatePackageObjects`
    // merges it with the existing (extern-filtered, empty) object from primitives.ssc.
    val withUi =
      if effectiveFrontend.isDefined then
        uiHelperFunctions(effectiveFrontend.getOrElse("react")) + "\n" + userSrc + "\n" + uiPrimitivesBlock
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
   *  arbitrary inner braces / definitions are passed through unchanged. */
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
    val cwd = java.nio.file.Paths.get(".").toAbsolutePath.normalize()
    val staged = findIn(cwd.resolve("bin").resolve("lib").resolve("jars"))
    val devTarget = findInDevTree(cwd)
    val found = installed.orElse(staged).orElse(devTarget)
    found
      .map(p => s"""//> using jar "${p.toAbsolutePath}"\n""")
      .getOrElse(s"""//> using dep "io.scalascript::$artifactBase:0.1.0-SNAPSHOT"\n""")

  private def colonObjectsToBraces(src: String): String =
    val lines    = src.split('\n')
    val n        = lines.length
    val result   = new StringBuilder(src.length)
    var i        = 0
    val ColonObj = "^(object \\w+):$".r
    while i < n do
      lines(i) match
        case ColonObj(header) =>
          // Collect body: all lines that are blank OR indented (start with space/tab).
          i += 1
          val body = new StringBuilder()
          while i < n && (lines(i).isEmpty || lines(i).charAt(0) == ' ' || lines(i).charAt(0) == '\t') do
            body.append(lines(i)).append('\n')
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
   *  Brace counting respects double-quoted strings and `//` line comments;
   *  triple-quoted strings and `/* */` comments are rare enough at the
   *  outer indent level that we don't track them. */
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
          var depth = 1
          var k     = j + 1
          var inStr = false; var inLnCmt = false
          while k < n && depth > 0 do
            val c = src.charAt(k)
            if inLnCmt then { if c == '\n' then inLnCmt = false }
            else if inStr then { if c == '\\' && k + 1 < n then k += 1 else if c == '"' then inStr = false }
            else c match
              case '"' => inStr = true
              case '/' if k + 1 < n && src.charAt(k + 1) == '/' => inLnCmt = true; k += 1
              case '{' => depth += 1
              case '}' => depth -= 1
              case _   => ()
            k += 1
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

  /** After `colonObjectsToBraces`, alias import blocks like
   *  `import std.ui.nodes.{TkNode,...}` end up between (or after) the
   *  `object std { ... }` blocks they were generated alongside.  Once
   *  `mergeDuplicatePackageObjects` merges all `object std` blocks into ONE,
   *  those imports land AFTER the merged block's closing `}` — outside the
   *  scope of nested `object lower { def lower(n: TkNode,...) }`.
   *
   *  Fix: hoist top-level `import std.*` lines that export ONLY TYPE names
   *  (first character uppercase) to the start of `object std`'s body.
   *  Convert `import std.X.Y.{A,B}` → `import X.Y.{A,B}` (drop `std.`)
   *  since the injection site is already inside `object std`.
   *
   *  Imports for value-level names (functions/vals starting lowercase, e.g.
   *  `import std.ui.primitives.{signal, serve, ...}`) are left in place —
   *  those names don't exist as Scala members of the package objects (extern
   *  defs are filtered) and are only needed by user code at the file level.
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

    // Collect `import std.*` lines that export ONLY type names (uppercase).
    // Heuristic: extract names between `{` and `}`, check each starts uppercase.
    def isTypeOnlyImport(line: String): Boolean =
      val lb = line.lastIndexOf('{')
      val rb = line.lastIndexOf('}')
      if lb < 0 || rb <= lb then false
      else
        val namesPart = line.substring(lb + 1, rb)
        val names = namesPart.split(",").map(_.trim).map { s =>
          if s.contains(" as ") then s.substring(0, s.indexOf(" as ")).trim else s
        }
        names.nonEmpty && names.forall(n => n.headOption.exists(_.isUpper))

    val importBuf    = scala.collection.mutable.ListBuffer.empty[String]
    val removedLines = scala.collection.mutable.Set.empty[Int]
    for idx <- firstStdObj + 1 until n do
      val l = lines(idx)
      if l.startsWith("import std.") && isTypeOnlyImport(l) then
        val relative = "  " + l.replaceFirst("import std\\.", "import ")
        importBuf   += relative
        removedLines += idx

    // Inject std/ui opaque type aliases at the start if std.ui.primitives is present.
    // Replace any partial ui.primitives.{ import (types-only) with the full one.
    val hasPrimitivesObj = lines.exists(l => l.trim == "object primitives {" || l.contains("object primitives {"))
    if hasPrimitivesObj then
      importBuf.filterInPlace(!_.contains("ui.primitives.{"))
      importBuf.prepend("  import ui.primitives.{Signal, View, EventHandler, signal, element, textNode, signalText, showSignal, fragment, setSignal, inputChange, toggleSignal, eqSignal, hashSignal, emit, serve, fetchUrlSignal, fetchAction, incSignal, fetchActionClear, fetchTableView}")

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
          importBuf.foreach { il => out.append(il).append('\n') }
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
          var depth   = 1
          var k       = j + 1
          var inStr   = false
          var inLnCmt = false
          while k < n && depth > 0 do
            val c = src.charAt(k)
            if inLnCmt then
              if c == '\n' then inLnCmt = false
            else if inStr then
              if c == '\\' && k + 1 < n then k += 1
              else if c == '"' then inStr = false
            else
              c match
                case '"' => inStr = true
                case '/' if k + 1 < n && src.charAt(k + 1) == '/' => inLnCmt = true; k += 1
                case '{' => depth += 1
                case '}' => depth -= 1
                case _   => ()
            k += 1
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
    val blocks = collectBlocks(module.sections)
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
       blocksUseMcp(blocks)                                then caps += Serve
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
    body.append(preamble)
    body.append(commonRuntime)
    body.append(generatorRuntime)
    body.append(fsRuntime)
    // HTML tag bindings: emit all of them — the runtime doesn't know which
    // names the user shadows, so we drop tags into the runtime package and
    // user code shadows via its own top-level definitions where it wants
    // to (Scala 3 resolves the local binding over the wildcard import).
    body.append(htmlDslTagBindings(Set.empty))
    if capabilities.contains(Effects)   then body.append(effectsRuntime)
    if capabilities.contains(MutualTco) then body.append(mutualTcoRuntime)
    if capabilities.contains(Reactive)  then body.append(reactiveRuntime)
    if capabilities.contains(Serve)     then body.append(serveRuntime)
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
    val blocks = collectBlocks(module.sections)
    analyzeDepEffectfulness()
    analyzeEffects(blocks)
    analyzeMutualRecursion(blocks)
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

    val frontmatterRoutes = module.manifest.toList.flatMap(_.routes)
    val apiClients = module.manifest.toList.flatMap(_.apiClients)
    frontmatterRoutes.foreach { r =>
      val esc = r.path.replace("\\", "\\\\").replace("\"", "\\\"")
      sb.append(s"""route("${r.method}", "$esc") { req => ${r.handler}(req) }\n""")
    }
    if frontmatterRoutes.nonEmpty then sb.append("\n")
    emitTypedRouteClientMetadata(apiClients, sb)
    val effectiveFrontend = frontendOverride.orElse(module.manifest.flatMap(_.frontendFramework))
    if effectiveFrontend.contains("swing") then
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
    val qualifiedSrc = rewriteActorAstCallsInSource(rawSrc)
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

  private def collectBlocks(sections: List[Section]): List[JvmGen.Block] =
    sections.flatMap { s =>
      val sectionId = sectionIdent(s.heading.text)
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
          cb.tree.map(t => JvmGen.Block(t, cb.source, origStart)).toList
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
        case imp: Content.Import =>
          val (blocks, importedPkg) = inlineImport(imp.path)
          blocks ++ aliasBlock(imp.bindings, importedPkg).toList
        case _ => Nil
      }
      own ++ collectBlocks(s.subsections)
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
  private def escapeStringLit(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

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
      sb.append(swingTypedRouteClientRuntime)
      clients.foreach { client =>
        if client.endpoints.nonEmpty then
          sb.append("object ").append(client.name).append(":\n")
          client.endpoints.foreach { endpoint =>
            val method = scalaStringLiteral(endpoint.method)
            val path = scalaStringLiteral(endpoint.path)
            if endpoint.requestType == "Unit" then
              sb.append("  def ").append(endpoint.name).append("(): ").append(endpoint.responseType)
                .append(" = _ssc_api_request[Unit, ").append(endpoint.responseType).append("](")
                .append(method).append(", ").append(path).append(", ())\n")
            else
              sb.append("  def ").append(endpoint.name).append("(input: ").append(endpoint.requestType).append("): ")
                .append(endpoint.responseType).append(" = _ssc_api_request[")
                .append(endpoint.requestType).append(", ").append(endpoint.responseType).append("](")
                .append(method).append(", ").append(path).append(", input)\n")
          }
          sb.append("\n")
      }

  private val swingTypedRouteClientRuntime: String =
    """|// ── Typed route clients: Swing in-process transport ────────────────
       |import scala.compiletime.{erasedValue, summonInline}
       |
       |private def _ssc_api_url_encode(value: Any): String =
       |  java.net.URLEncoder.encode(_show(value), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
       |
       |private def _ssc_api_product_fields(value: Any): Map[String, Any] =
       |  value match
       |    case p: Product =>
       |      p.productElementNames.zip(p.productIterator).toMap
       |    case _ => Map.empty
       |
       |private def _ssc_api_path_param_names(pathTemplate: String): List[String] =
       |  pathTemplate.split('/').toList.filter(_.startsWith(":")).map(_.drop(1))
       |
       |private def _ssc_api_path(pathTemplate: String, input: Any): String =
       |  val names = _ssc_api_path_param_names(pathTemplate)
       |  val fields = _ssc_api_product_fields(input)
       |  val primitiveForSingleParam =
       |    names.size == 1 && fields.isEmpty && input != null && input != ()
       |  pathTemplate.split('/').toList.map { segment =>
       |    if segment.startsWith(":") then
       |      val name = segment.drop(1)
       |      val value =
       |        if primitiveForSingleParam then input
       |        else fields.getOrElse(name, throw RuntimeException("typed route client: missing path field '" + name + "'"))
       |      _ssc_api_url_encode(value)
       |    else segment
       |  }.mkString("/")
       |
       |private def _ssc_api_query(pathTemplate: String, input: Any): String =
       |  val used = _ssc_api_path_param_names(pathTemplate).toSet
       |  val fields = _ssc_api_product_fields(input).filterNot((k, _) => used.contains(k))
       |  if fields.isEmpty then ""
       |  else fields.iterator.map((k, v) => _ssc_api_url_encode(k) + "=" + _ssc_api_url_encode(v)).mkString("?", "&", "")
       |
       |""".stripMargin + TypedJsonCodecRuntime.jvmFacade + """|private inline def _ssc_api_body[Req](method: String, input: Req): String =
       |  if method == "GET" || input == () then ""
       |  else _ssc_typed_json_encode[Req](input)
       |
       |inline def _ssc_api_request[Req, Resp](methodRaw: String, pathTemplate: String, input: Req): Resp =
       |  val method = methodRaw.toUpperCase
       |  val url = _ssc_api_path(pathTemplate, input) + _ssc_api_query(pathTemplate, input)
       |  val response = _ssc_ui_backend_request(method, url, _ssc_api_body[Req](method, input))
       |  val responseBody = String(response.body, java.nio.charset.StandardCharsets.UTF_8)
       |  if response.status < 200 || response.status >= 300 then
       |    throw RuntimeException("typed route client: " + method + " " + url + " returned " + response.status + ": " + responseBody)
       |  _ssc_typed_json_decode_response[Resp](response)
       |
       |""".stripMargin

  /** Wrap `s` as a properly-escaped Scala double-quoted string literal,
   *  safe for embedding in emitted `.sc` source. */
  private def scalaStringLiteral(s: String): String =
    "\"" + s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
      + "\""

  /** Escape a string for inclusion in a double-quoted JS literal inside
   *  the emitted client-SQL JS bundle. */
  private def jsLitForClientSql(s: String): String =
    val sb = StringBuilder("\"")
    s.foreach {
      case '\\' => sb.append("\\\\")
      case '"'  => sb.append("\\\"")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
      case c    => sb.append(c)
    }
    sb.append("\"").toString

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
    if pkg.nonEmpty then
      val pkgPath = pkg.mkString(".")
      val specs   = bindings.map { b => b.alias match
        case None    => b.name
        case Some(a) => s"${b.name} as $a"
      }.mkString(", ")
      val src   = s"import $pkgPath.{$specs}"
      val input = Input.VirtualFile("<import-aliases>", src)
      dialects.Scala3(input).parse[Source].toOption.map(s => JvmGen.Block(ScalaNode(s), src))
    else
      val aliases = bindings.flatMap { b =>
        b.alias.map { a => s"val $a = ${b.name}" }
      }
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
      (Nil, importedPkgs.getOrElse(key, Nil))
    else if !os.exists(resolved) then
      throw new RuntimeException(s"Import not found: $path")
    else
      importedFiles += key
      val importedModule = Parser.parse(os.read(resolved))
      val pkg = importedModule.manifest.flatMap(_.pkg).getOrElse(Nil)
      importedPkgs(key) = pkg
      val nested = new JvmGen(Some(resolved / os.up), lockPath = lockPath)
      nested.importedFiles ++= importedFiles
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

  private def analyzeEffects(blocks: List[JvmGen.Block]): Unit =
    // Built-in `Async` / `Storage` / `Actor` effects — pre-populated only
    // when the module actually uses them, keeping the emitted Scala lean
    // otherwise.
    val builtins =
      (if blocksUseAsync(blocks) then
         Set("Async.delay", "Async.async", "Async.await", "Async.parallel", "Async.recvFrom")
       else Set.empty[String]) ++
      (if blocksUseStorage(blocks) then
         Set("Storage.get", "Storage.put", "Storage.remove", "Storage.has", "Storage.keys")
       else Set.empty[String]) ++
      (if blocksUseActors(blocks) then
         Set("Actor.spawn", "Actor.spawn_link", "Actor.self", "Actor.send", "Actor.exit",
             "Actor.receive", "Actor.receive_t",
             "Actor.link", "Actor.monitor", "Actor.demonitor", "Actor.trapExit")
       else Set.empty[String]) ++
      (if blocksUseLogger(blocks) then
         Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
       else Set.empty[String]) ++
      (if blocksUseRandom(blocks) then
         Set("Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick")
       else Set.empty[String]) ++
      (if blocksUseClock(blocks) then
         Set("Clock.now", "Clock.nowIso", "Clock.sleep")
       else Set.empty[String]) ++
      (if blocksUseEnv(blocks) then
         Set("Env.get", "Env.set", "Env.required")
       else Set.empty[String]) ++
      (if blocksUseHttp(blocks) then
         Set("Http.get", "Http.post", "Http.request")
       else Set.empty[String]) ++
      (if blocksUseRetry(blocks) then
         Set("Retry.attempt")
       else Set.empty[String]) ++
      (if blocksUseCache(blocks) then
         Set("Cache.memoize")
       else Set.empty[String]) ++
      (if blocksUseState(blocks) then
         Set("State.get", "State.set", "State.modify")
       else Set.empty[String]) ++
      // Tx and Auth don't use _perform; add dummy entries only to gate
      // effectsRuntime emission when no other effects are present.
      (if blocksUseTx(blocks) || blocksUseAuth(blocks) then
         Set("_v14extras")
       else Set.empty[String])

    val trees = blocks.map(b => ScalaNode.fold(b.node)(identity))
    val r     = EffectAnalysis.analyze(trees, builtins)

    effectOps.clear();     effectOps     ++= r.effectOps
    effectfulFuns.clear(); effectfulFuns ++= r.effectfulFuns

  private def isEffectOpDef(body: Term): Boolean = EffectAnalysis.isEffectOpDef(body)

  private def isEffectOpRef(eff: String, op: String): Boolean =
    effectOps.contains(s"$eff.$op")

  /** True for user-code effectful functions (populated by `analyzeEffects`)
   *  AND for dep-defined functions marked effectful by Strategy D's
   *  fixpoint (Step 2, `analyzeDepEffectfulness`). Both kinds need the
   *  same downstream treatment: params widened to `Any`, body routed
   *  through `emitCpsExpr`. */
  private def isEffectfulFun(name: String): Boolean =
    effectfulFuns.contains(name) || globalEffectfulDeps.contains(name)

  // ─── Routing detection ───────────────────────────────────────────
  //
  // True when any code block calls `mcpServer`, `serveMcp`, or `mcpConnect`.
  private def blocksUseMcp(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("mcpServer"),  _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("serveMcp"),   _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("mcpConnect"), _) => found = true
        }
      }
      found
    }

  private def blocksUseTypedData(blocks: List[JvmGen.Block]): Boolean =
    val names = Set(
      "JsonCodec", "JsonValue", "JsonFieldSpec",
      "RowCodec", "RowValue", "RowValueCodec", "RowFieldSpec",
      "ObjectCodec", "ObjectValue", "ObjectFieldSpec",
      "VertexCodec", "VertexValue", "EdgeCodec", "EdgeValue",
      "RdfCodec", "RdfValue", "RdfTriple", "RdfNode",
      "DatasetCodec", "DatasetPartition", "DatasetWirePartition",
      "SparkSchemaCodec", "SparkSchema", "SparkSchemaField", "SparkSchemaType"
    )
    blocks.exists { b =>
      var found = false
      if b.src.contains("scalascript.typeddata") || names.exists(name => b.src.contains(name)) then
        found = true
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Import(importers) if importers.exists(_.ref.syntax.startsWith("scalascript.typeddata")) => found = true
          case Term.Name(name) if names(name) => found = true
          case Type.Name(name) if names(name) => found = true
        }
      }
      found
    }

  private def blocksUseObjectStore(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = b.src.contains("ObjectStore.")
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Select(Term.Name("ObjectStore"), _) => found = true
          case Term.Name("ObjectStore") => found = true
        }
      }
      found
    }

  private def blocksUseGraph(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = b.src.contains("Graph.") || b.src.contains("GraphRuntime") || b.src.contains("scalascript.graph")
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Import(importers) if importers.exists(_.ref.syntax.startsWith("scalascript.graph")) => found = true
          case Term.Select(Term.Name("Graph"), _) => found = true
          case Term.Name("Graph") => found = true
          case Term.Name("GraphRuntime") => found = true
          case Type.Name("GraphRuntime") => found = true
        }
      }
      found
    }

  // True when any code block uses `Dataset.of`, `Dataset.fromList`, etc.
  private def blocksUseDataset(blocks: List[JvmGen.Block]): Boolean =
    val triggers = Set("Dataset", "_Dataset")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Select(Term.Name(n), _) if triggers(n) => found = true
          case Term.Apply.After_4_6_0(Term.Select(Term.Name(n), _), _) if triggers(n) => found = true
        }
      }
      found
    }

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
          // `serveAsync(port[, tls])` is the non-blocking sibling of
          // `serve` (virtual-thread launch).  Same runtime dependency
          // — must pull `serveRuntime` in so the inlined ProxyRuntime
          // `def serveAsync` is in scope.
          case Term.Apply.After_4_6_0(Term.Name("serveAsync"),   _) => found = true
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

  /** Bare-name intrinsics that route through the v1.6 actor model and
   *  must be rewritten to `Actor.<name>(...)` at emission time.  Listed
   *  once here so the analysis (`blocksUseActors`) and the rewrite-gate
   *  (`termUsesEffects`) stay in sync — a call like
   *  `val sub = subscribeClusterEvents()` needs the gate to fire so the
   *  rhs goes through `emitExpr` (which performs the rewrite).
   *
   *  Without this, top-level `val`-bound calls fall through to the
   *  scalameta verbatim printer and the unqualified `subscribeClusterEvents`
   *  is unresolved at scala-cli compile time (the runtime exposes it
   *  as `Actor.subscribeClusterEvents`). */
  private val actorBareNames: Set[String] =
    Set("runActors", "spawn", "spawn_link", "spawnBounded", "self", "exit", "receive",
        "link", "monitor", "demonitor", "trapExit",
        "startNode", "connectNode", "joinCluster", "register", "whereis",
        "globalRegister", "globalWhereis",
        "clusterMembers", "subscribeClusterEvents",
        "phiOf", "isSuspect", "selfNode", "clusterHealth",
        "broadcastHealth", "clusterIsDown",
        "electLeader", "currentLeader", "subscribeLeaderEvents",
        "setAutoReelect",
        "useRaftLeaderElection", "useExternalCoordinator", "leaderProtocol",
        "leaderHistory",
        "setReconnectPolicy", "setHeartbeatTimeout", "setQuorumSize", "requestGossip",
        "clusterConfigSet", "clusterConfigGet", "clusterConfigKeys",
        "subscribeConfigEvents",
        "setDraining", "isDraining", "drainingPeers", "subscribeDrainEvents",
        "clusterMetricSet", "clusterMetricGet", "clusterMetricSum",
        "clusterMetricNames", "subscribeMetricEvents",
        "sendAfter", "sendInterval", "cancelTimer", "processInfo")

  /** First-segment names of SSC dep modules whose types are accessed via
   *  `_dispatch` at runtime rather than as real Scala classes.  An `import`
   *  statement in a user code block that starts with one of these names is
   *  dropped by `emitStat` (the `actors` module is the canonical example:
   *  `import actors.ProcessInfo` / `import actors.Overflow`). */
  private val sscDepModulePrefixes: Set[String] = Set("actors")

  /** Case-class names defined inside `effectsRuntime` whose presence in
   *  patterns or expressions should also pull in the runtime.  Without
   *  this, a module that does e.g.
   *  ```
   *  def describe(e: Any) = e match { case NodeJoined(id) => ... }
   *  ```
   *  would compile-error with "no pattern match extractor named NodeJoined". */
  private val actorRuntimeCaseClasses: Set[String] =
    Set("NodeJoined", "NodeLeft", "LeaderElected", "LeaderLost",
        "ConfigChanged", "DrainStateChanged", "MetricChanged",
        "Exit", "Down")

  /** True if any block references the v1.6 actor model — via
   *  `runActors`, `spawn`, `self`, `exit`, `receive`, `link`, `monitor`,
   *  `demonitor`, `trapExit`, Phase 3 distributed primitives, or a
   *  pattern/expression that mentions one of the actor-runtime case
   *  classes (`NodeJoined` / `NodeLeft` / `Exit` / `Down`). */
  private def blocksUseActors(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _) if actorBareNames(n) => found = true
          // Pattern match: `case NodeJoined(id) => ...` lowers to
          // `Pat.Extract(Term.Name("NodeJoined"), ...)`.  Detect those.
          case scala.meta.Pat.Extract.After_4_6_0(Term.Name(n), _) if actorRuntimeCaseClasses(n) => found = true
          // Bare expression reference (companion or constructor):
          // `NodeJoined`, `Exit(pid, "stop")` — covers both `Term.Name`
          // (apply target) and lone references.
          case Term.Name(n) if actorRuntimeCaseClasses(n) => found = true
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

  private def blocksUseLogger(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("info", "warn", "error", "debug")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runLogger", "runLoggerJson", "runLoggerToList")(n) => found = true
          case Term.Select(Term.Name("Logger"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private def blocksUseRandom(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("nextInt", "nextDouble", "uuid", "pick")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runRandom", "runRandomSeeded")(n) => found = true
          case Term.Select(Term.Name("Random"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private def blocksUseClock(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("now", "nowIso", "sleep")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runClock", "runClockAt")(n) => found = true
          case Term.Select(Term.Name("Clock"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private def blocksUseEnv(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("get", "set", "required")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runEnv", "runEnvWith")(n) => found = true
          case Term.Select(Term.Name("Env"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private def blocksUseHttp(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("get", "post", "request")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runHttp", "runHttpStub")(n) => found = true
          case Term.Select(Term.Name("Http"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private def blocksUseRetry(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runRetry", "runRetryNoSleep")(n) => found = true
          case Term.Select(Term.Name("Retry"), Term.Name("attempt")) => found = true
        }
      }
      found
    }

  private def blocksUseCache(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runCache", "runCacheBypass")(n) => found = true
          case Term.Select(Term.Name("Cache"), Term.Name("memoize")) => found = true
        }
      }
      found
    }

  private def blocksUseState(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("get", "set", "modify")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("runState"), _) => found = true
          case Term.Select(Term.Name("State"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private def blocksUseTx(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("runTx"), _) => found = true
          case Term.Select(Term.Name("Tx"), Term.Name("atomic")) => found = true
        }
      }
      found
    }

  private def blocksUseAuth(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("runAuthWith"), _) => found = true
          case Term.Select(Term.Name("Auth"), Term.Name(_)) => found = true
        }
      }
      found
    }

  // ─── Strategy D, Step 1 ──────────────────────────────────────────
  //
  // `containsEffectPrimitive(tree)` — whitelist predicate used by the
  // dep-mode CPS rewriter (Step 3) to decide which `Defn.Def` bodies
  // need to be CPS-emitted instead of emitted verbatim.
  //
  // **Strict semantics:** only call-site shapes that ARE primitive
  // count.  Selects, user-defined Applies, and type-inferred Anys
  // are deliberately ignored — see docs/dep-cps-rewrite.md §4.4
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
      // Actor send sugar: `pid ! msg` (any infix `!`).
      case Term.ApplyInfix.After_4_6_0(_, Term.Name("!"), _, _) => ()
      // Qualified primitive Selects + Apply: `Actor.send(...)`, etc.
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Actor"),   Term.Name(_)),  _) => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Random"),  Term.Name(op)), _) if randomPrimitiveOps(op)  => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Storage"), Term.Name(op)), _) if storagePrimitiveOps(op) => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Clock"),   Term.Name(op)), _) if clockPrimitiveOps(op)   => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Logger"),  Term.Name(op)), _) if loggerPrimitiveOps(op)  => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("Async"),   Term.Name(op)), _) if asyncPrimitiveOps(op)   => ()
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
    blockContainsExternDef(node)  ||
    blockContainsDirectBlock(node)

  /** v1.8 — force any block containing a direct[M] { ... } expression through
   *  emitStats so emitDirectBlock rewrites it to .flatMap chains (and so the
   *  Phase 5 static checks fire). */
  private def blockContainsDirectBlock(node: ScalaNode): Boolean =
    def go(t: scala.meta.Tree): Boolean = t match
      case app: Term.Apply =>
        app.fun match
          case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
          case _ => app.children.exists(go)
      case other => other.children.exists(go)
    ScalaNode.fold(node)(go)

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
        } ||
        // Strategy D — recurse into nested objects to find dep defs marked
        // effectful by the fixpoint.  Without this, deps wrapped as
        // `object std { object pkg { def f = … } }` bypass emit and are
        // returned as `block.src` verbatim, skipping Step 3's CPS emit.
        d.collect { case dd: Defn.Def if globalEffectfulDeps(dd.name.value) => () }.nonEmpty
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
    // Bare-name actor intrinsics — `subscribeClusterEvents()`,
    // `clusterMembers()`, `spawn { ... }`, etc.  Without this case the
    // val-rhs path emits the syntax verbatim and the unqualified name
    // is unresolved at scala-cli compile time (the runtime exposes them
    // as `Actor.subscribeClusterEvents`, and the rewriter only fires
    // when the rhs goes through `emitExpr`).
    case Term.Apply.After_4_6_0(Term.Name(n), _) if actorBareNames(n)             => true
    case _ => t.children.exists {
      case tt: Term => termUsesEffects(tt)
      case _        => false
    }

  /** True if the term needs codegen rewriting (effect machinery,
   *  Focus → Lens expansion, Prism[O, V] → Prism literal) rather than
   *  verbatim Scala source emission. */
  private def termNeedsCustomEmit(t: Term): Boolean =
    termUsesEffects(t) || termContainsFocus(t) || termContainsPrism(t) || termContainsIntrinsic(t) || termContainsDirectBlock(t)

  private def termContainsDirectBlock(t: Term): Boolean =
    def go(n: Tree): Boolean = n match
      case app: Term.Apply =>
        app.fun match
          case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
          case _ => app.children.exists(go)
      case other => other.children.exists(go)
    go(t)

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

  private def emitStats(stats: List[Stat], out: StringBuilder, isTopLevel: Boolean): Unit =
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
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map { p =>
        p.decltpe.map(t => s"${p.name.value}: ${t.syntax}")
          .getOrElse(s"${p.name.value}: Any")
      }.mkString(", ")
      // Return type set to Any (could be a Free value the caller's
      // `_bind` will unwrap).
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
      op.value match
        // v1.6 actors: `pid ! msg` always lowers to Actor.send.
        case "!" => s"Actor.send($l, $r)"
        // Arithmetic / comparison: operands may be Any (e.g. Async.await result),
        // so delegate to the same _binOp helper used in CPS context.
        case "+" | "-" | "*" | "/" | "%" |
             "<" | ">" | "<=" | ">="    => s"""_binOp("${op.value}", $l, $r)"""
        case other                      => s"$l $other $r"
    case Term.Select(qual, name) =>
      s"${emitExpr(qual)}.${name.value}"
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
  private def emitReceiveMatcher(cases: List[Case]): String =
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
    // Varargs spread `xs: _*` is a syntactic call form — can't be bound
    // as a value.  Pass through verbatim so `f(xs: _*)` stays as
    // `f(xs: _*)` not `_bind(xs: _*, …)`.
    case _: Term.Repeated                        => true
    // Function literals / partial functions / placeholder lambdas
    // (`_._1`, `_ + 1`) are pure values, never effectful.
    case _: Term.Function                        => true
    case _: Term.AnonymousFunction               => true
    case _: Term.PartialFunction                 => true
    // Named-arg assignments inside a call: `f(name = expr)`.  The CPS
    // emit should treat the assignment shape as syntactic — bind only
    // the value side if needed (handled separately when the time comes).
    case _: Term.Assign                          => true
    case _                                       => false

  /** Bind a list of CPS sub-expressions; pass their values into `k`. */
  private def bindArgsCps(args: List[Term])(k: List[String] => String): String =
    def emitSimple(t: Term): String = t match
      // A bare `{ case … }` lands in Any-typed positions (e.g. inside
      // `_dispatch(qual, "map", List({case …}))`).  Scala 3 can't infer
      // `x$1`'s type for the auto-expanded `x$1 => x$1 match { … }`
      // when the expected type is `Any`, so wrap it ourselves with an
      // explicit `Any` parameter.  The destructuring inside still works
      // (`case (a, b) => …` binds against the runtime tuple).
      //
      // Chunk 4 — case bodies are CPS-emitted (mirroring the Term.Match
      // arm in emitCpsExpr).  Without this, an effectful expression
      // inside a case body — `pid ! msg`, `receive`, `nested.method` —
      // stays as raw syntax and skips the CPS rewrites it needs.  Side
      // effects on pure case bodies are nil: `case x => f(x)` re-emits
      // as `case x => f(x)` (`f(x)` is `emitCpsApply` whose `case fun`
      // arm produces `f(${vs})` when there's nothing to bind).
      case pf: Term.PartialFunction =>
        val tmp   = freshTmp()
        val cases = pf.cases.map { c =>
          val guard = c.cond.map(g => s" if ${g.syntax}").getOrElse("")
          // Pattern-bound names (`case (k, vs) => …`, `case Some(info)
          // => …`) come from destructuring an `Any` value, so they're
          // also Any-typed.  Register them so the case body's
          // `.method` accesses (`vs.sorted`, `info.links`) route via
          // _dispatch instead of failing on `value sorted is not a
          // member of Any`.
          val boundNames = c.pat.collect { case scala.meta.Pat.Var(n) => n.value }.toSet
          val body = withAnyBoundNames(boundNames)(emitCpsExpr(c.body))
          s"case ${c.pat.syntax}${guard} => $body"
        }.mkString(" ")
        s"((${tmp}: Any) => ${tmp} match { $cases })"
      // Chunk 6 — Term.Function mirroring the PF treatment above.  A
      // bare `pid => pid ! msg` lands in an Any-typed position (e.g.
      // `_dispatch(pids, "foreach", List(pid => …))`) and Scala 3
      // can't infer `pid`'s type from the expected `Any`.  Widen
      // each param to its declared type (or `Any` when un-annotated)
      // and CPS-emit the body so an effectful expression inside the
      // body (`pid ! msg`) reaches the existing `Term.ApplyInfix("!")`
      // arm and becomes `Actor.send(pid, msg)`.  Pure bodies re-emit
      // unchanged because emitCpsApply's `case fun` arm produces
      // `f(${vs})` when there's nothing to bind — same as the PF
      // case-body argument above.
      case Term.Function.After_4_6_0(paramClause, body) =>
        val params = paramClause.values.map { p =>
          p.decltpe.map(t => s"${p.name.value}: ${t.syntax}")
            .getOrElse(s"${p.name.value}: Any")
        }.mkString(", ")
        // Widened (no-decltpe) params land as `Any` — register so the
        // Term.Select arm can _dispatch their `.member` accesses.
        val anyParams = paramClause.values.filter(_.decltpe.isEmpty).map(_.name.value).toSet
        s"(($params) => ${withAnyBoundNames(anyParams)(emitCpsExpr(body))})"
      // `_._1`, `_._2 == deadPid`, `_ + 1` — Scala can't infer the
      // expanded `x$N` parameter type when the placeholder shorthand
      // lands in an `Any`-typed slot.  Rewrite by string-substituting
      // the bare `_` for a fresh name, re-parsing, and CPS-emitting
      // through the registered-Any path so accesses like `_._1`
      // become `_dispatch(_t, "_1", Nil)` at runtime.
      case af: Term.AnonymousFunction =>
        import scala.meta.{dialects, *}
        val tmp      = freshTmp()
        val bodySrc  = af.body.syntax
        // Word-boundary regex: replace standalone `_` only, leaving
        // `_x`, `xs._1`, `xs: _*`, etc. alone.
        val rewritten = bodySrc.replaceAll("""(?<![A-Za-z0-9_])_(?![A-Za-z0-9_])""", tmp)
        dialects.Scala3(Input.String(rewritten)).parse[Term] match
          case Parsed.Success(parsedBody) =>
            s"(($tmp: Any) => ${withAnyBoundNames(Set(tmp))(emitCpsExpr(parsedBody))})"
          case _ =>
            // Re-parse failed: fall back to raw syntax (preserves the
            // original failure mode rather than introducing a new one).
            af.syntax
      // Named-arg RHS in CPS context — emit through the CPS pipeline so
      // chained calls on Any-typed values get `_dispatch`/`_bind`.
      // Otherwise `pending = assignments.map(_._1).toSet` would land
      // raw and the `.map` on Any-typed assignments would fail.
      case Term.Assign(lhs, rhs) =>
        s"${lhs.syntax} = ${emitCpsExpr(rhs)}"
      case _ => t.syntax
    def loop(remaining: List[Term], acc: List[String]): String = remaining match
      case Nil       => k(acc.reverse)
      case t :: rest =>
        if isSimpleCps(t) then loop(rest, emitSimple(t) :: acc)
        else
          val v = freshTmp()
          s"_bind(${emitCpsExpr(t)}, ($v: Any) => ${loop(rest, v :: acc)})"
    loop(args, Nil)

  private var tmpIdx = 0
  private def freshTmp(): String = { tmpIdx += 1; s"_t$tmpIdx" }

  // Chunk 6 — names statically bound to `Any` in the enclosing
  // Term.Function lambda(s) of the current emitCpsExpr context.
  // The Term.Select arm consults this so `node.address` becomes
  // `_dispatch(node, "address", Nil)` when `node` came from an
  // `(node: Any) =>` widened param (otherwise `.address` on `Any`
  // wouldn't typecheck).  Push at function entry, restore at exit
  // — robust to shadowing because we snapshot the previous set.
  private val anyBoundNames = scala.collection.mutable.Set.empty[String]
  /** Run `body` with `names` added to `anyBoundNames`; restore on
   *  exit (snapshot/restore pattern handles shadowed re-binds). */
  private def withAnyBoundNames[A](names: Set[String])(body: => A): A =
    if names.isEmpty then body
    else
      val prev = anyBoundNames.toSet
      anyBoundNames ++= names
      try body
      finally
        anyBoundNames.clear()
        anyBoundNames ++= prev

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

    // User-defined interpolator: StringContext("p1","p2").prefix(arg1, arg2)
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val scParts = parts.map { p =>
          val s = p.asInstanceOf[Lit.String].value
          "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }.mkString(", ")
        val argsStr = vs.mkString(", ")
        s"StringContext($scParts).$prefix($argsStr)"
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
      val anyParams = paramClause.values.filter(_.decltpe.isEmpty).map(_.name.value).toSet
      s"$wrap => ${withAnyBoundNames(anyParams)(emitCpsExpr(body))}"

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
      s"_runAsync(() => ${emitExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
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
    // v1.6.x — scheduled sends (inside CPS body)
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
    // v1.6.x — process introspection (inside CPS body)
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

    case app: Term.Apply => emitCpsApply(app)

    // Chunk 6 — `throw expr` routes the inner through CPS so the
    // throwable construction (e.g. `throw DistributedError(failedNode,
    // reason)`) gets call-site cast injection from chunk 3.  When the
    // inner expression is complex we _bind first; simple inners get
    // a direct throw.
    case t: Term.Throw =>
      if isSimpleCps(t.expr) then s"throw ${t.expr.syntax}"
      else
        val v = freshTmp()
        s"_bind(${emitCpsExpr(t.expr)}, ($v: Any) => throw $v.asInstanceOf[Throwable])"

    // Chunk 5 — `expr.asInstanceOf[T]` wraps the inner emit so the
    // receive/match shapes that live inside the cast (e.g.
    // `receiveWithTimeout(t) { case … }.asInstanceOf[Boolean]` in
    // dep code) reach their CPS arms instead of falling through to
    // `other.syntax`.  Without this, the inner `receiveWithTimeout`
    // stays as a raw bare-name call and Scala reports `Not found:
    // receiveWithTimeout`.  General `f[T]` (Term.ApplyType without
    // the `.asInstanceOf` Select) keeps its verbatim path via the
    // fallback below — we only fire on the cast shape.
    case Term.ApplyType.After_4_6_0(
            Term.Select(qual, Term.Name("asInstanceOf")), tparams) =>
      val tStr = tparams.values.map(_.syntax).mkString(", ")
      val v    = freshTmp()
      s"_bind(${emitCpsExpr(qual)}, ($v: Any) => $v.asInstanceOf[$tStr])"

    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      // Scala parses `pid ! (a, b)` as `pid.!(a, b)` (2-arg infix) NOT as
      // `pid.!((a, b))` (1-arg tuple).  For `!` (actor send), reconstruct
      // the tuple syntactically when multiple values are present, so the
      // CPS-emitted Actor.send receives exactly one msg argument.
      val rhs = argClause.values match
        case List(single) => single
        case many         =>
          import scala.meta.{dialects, *}
          val tupleSrc = many.map(_.syntax).mkString("(", ", ", ")")
          dialects.Scala3(Input.String(tupleSrc)).parse[Term].get
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
          case ":+"                              => s"$vl.asInstanceOf[List[Any]] :+ $vr"
          case "+:"                              => s"$vl +: $vr.asInstanceOf[List[Any]]"
          case "->"                              => s"($vl, $vr)"
          case other                             => s"($vl $other $vr)"
        case _ => "/* infix arity */"
      }

    case Term.Select(qual, name) =>
      // When `qual` is a simple name/literal it stays statically typed
      // (`cluster.nodes` on `cluster: Cluster` works directly).  When it
      // requires a `_bind` (`expr.field` where `expr` itself is a CPS
      // computation), the lambda param is `Any` and `.field` fails to
      // typecheck — route through `_dispatch` which uses the same
      // reflection fallback that handles `.method(args)` in the Apply
      // arm above.  Chunk 6 — also dispatch when `qual` is a simple
      // Term.Name that we widened to `Any` in the surrounding
      // Term.Function lambda (`(node: Any) => node.address`).
      qual match
        case Term.Name(n) if anyBoundNames(n) =>
          s"""_dispatch(${qual.syntax}, "${name.value}", Nil)"""
        case _ if isSimpleCps(qual) =>
          s"${qual.syntax}.${name.value}"
        case _ =>
          val v = freshTmp()
          s"""_bind(${emitCpsExpr(qual)}, ($v: Any) => _dispatch($v, "${name.value}", Nil))"""

    case t: Term.Match =>
      bindArgsCps(List(t.expr)) { case List(sv) =>
        val arms = t.casesBlock.cases.map { c =>
          val guard = c.cond.map(g => s" if ${g.syntax}").getOrElse("")
          // Pattern-bound names come from destructuring a scrutinee that
          // is Any-typed (it's a _bind lambda param).  Register them so
          // field accesses like `info.links` route via _dispatch instead
          // of failing with "value links is not a member of Any".
          // Mirrors the identical treatment in bindArgsCps's PF branch.
          val boundNames = c.pat.collect { case scala.meta.Pat.Var(n) => n.value }.toSet
          val body = withAnyBoundNames(boundNames)(emitCpsExpr(c.body))
          s"  case ${c.pat.syntax}${guard} => $body"
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
          // Strip `name = ` prefix from named args before they enter
          // `List(...)`.  Runtime `_dispatch` is positional (Java
          // reflection doesn't see param names anyway), and a literal
          // `List(timeoutMs = 1000)` would be parsed as
          // `List.apply(timeoutMs = 1000)` — `List` has no such param.
          val cleaned = vs.tail.map { v =>
            val eq = v.indexOf('=')
            // Conservative: only strip if the LHS of the first `=`
            // looks like a Scala identifier (avoid mangling expressions
            // that contain `=` such as `x == y` or `_ => …`).
            if eq > 0 && v.substring(0, eq).trim.matches("[A-Za-z_][A-Za-z0-9_]*") then
              v.substring(eq + 1).trim
            else v
          }
          val argList = cleaned.mkString(", ")
          val argSeq  = if cleaned.isEmpty then "Nil" else s"List($argList)"
          s"""_dispatch(${vs.head}, "${method}", $argSeq)"""
        }

      case fun =>
        // The function reference itself is always a callable value (not a
        // Free), so we never bind on `fun` — only on its args. The call's
        // result may be a Free; the caller's bind handles that.
        //
        // Chunk 3 — cast injection for known dep callees: when `fun` is
        // a bare name resolving to a `Defn.Def` or `Defn.Class` we
        // indexed in `inlineImport`, look up each param's declared type
        // and wrap the corresponding bound value as `v.asInstanceOf[T]`.
        // Without this, calls like `Cluster(nodeList, pids)` and
        // `collectResults(assignments = assignments, …)` fail because
        // `nodeList` / `assignments` are `Any` in the surrounding
        // continuation but the callee expects concrete types.
        val funName = fun match
          case Term.Name(n) => Some(n)
          case _            => None
        bindArgsCps(args) { vs =>
          val castedVs = funName match
            case None    => vs
            case Some(n) => applyCalleeCasts(n, args, vs)
          // Chunk 9 — when `fun` is an Any-bound name (e.g.
          // `workers` from a CPS continuation, accessed as
          // `workers(idx)`), Scala won't apply Any as a function.
          // Cast through `List[Any]` so the call typechecks
          // (positional element access on Any-typed collections is
          // the dominant shape; reflection-call would be heavier).
          funName match
            case Some(n) if anyBoundNames(n) =>
              // Index args also come in Any-typed; List.apply needs Int.
              val intArgs = castedVs.map(v => s"$v.asInstanceOf[Int]")
              s"${fun.syntax}.asInstanceOf[List[Any]](${intArgs.mkString(", ")})"
            case _ =>
              s"${fun.syntax}(${castedVs.mkString(", ")})"
        }

  /** Look up the declared param type for a call to a known dep def
   *  or dep class constructor, by position (`argIdx`) or by name
   *  (when the arg is `Term.Assign(Term.Name(n), _)`).  Returns the
   *  type's `syntax` form when present, `None` otherwise — including
   *  for varargs (`T*`) where `asInstanceOf[T*]` isn't a legal cast,
   *  and for types that reference a class-scoped type param (e.g.
   *  `List[T]` on `case class DistributedResult[T](items: List[T])`),
   *  where `T` is out of scope at the call site. */
  private def calleeParamType(
      callee:  String,
      argIdx:  Int,
      argName: Option[String]
  ): Option[String] =
    val classDef = depClasses.get(callee)
    val ps: Option[List[scala.meta.Term.Param]] =
      classDef.map(_.ctor.paramClauses.flatMap(_.values).toList)
        .orElse(
          depDefs.get(callee).map(d =>
            d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
          )
        )
    // Names of any class-level type params we need to exclude from
    // the cast (caller isn't inside the class scope so `T` is unbound).
    val tparamNames: Set[String] = classDef.map(_.tparamClause.values.map(_.name.value).toSet).getOrElse(Set.empty)
    /** Substitute class-scoped tparam names with `Any` (chunk 5) AND
     *  qualify dep-defined type names with their package path (chunk 8)
     *  so casts emitted at user call sites compile regardless of which
     *  dep types the user explicitly imported.  E.g.
     *  `List[StageOp]` → `List[std.mapreduce.StageOp]` when StageOp is
     *  a sealed trait in `std/mapreduce/distributed.ssc` and the user
     *  only imported `MapOp`, `FilterOp`, …. */
    def substituteTparams(t: scala.meta.Type): String =
      // Step 1: class-tparam substitution (chunk 5).
      val afterTparams =
        if tparamNames.isEmpty then t.syntax
        else
          tparamNames.foldLeft(t.syntax) { (acc, tp) =>
            acc.replaceAll(s"\\b${java.util.regex.Pattern.quote(tp)}\\b", "Any")
          }
      // Step 2: qualify any dep type name (chunk 8).  Word-boundary
      // anchored; skip names that already appear qualified
      // (`std.mapreduce.X.Y` shouldn't get re-prefixed).
      depTypeNames.foldLeft(afterTparams) { case (acc, (name, pkg)) =>
        if pkg.isEmpty then acc
        else
          val qualified = (pkg :+ name).mkString(".")
          // Negative lookbehind for `.` so we don't re-prefix an
          // already-qualified occurrence.
          acc.replaceAll(s"(?<![.A-Za-z0-9_])${java.util.regex.Pattern.quote(name)}(?![A-Za-z0-9_])", qualified)
      }
    ps.flatMap { params =>
      val targetOpt = argName match
        case Some(n) => params.find(_.name.value == n)
        case None    => params.lift(argIdx)
      targetOpt.flatMap { p =>
        p.decltpe match
          // Skip varargs — `asInstanceOf[Node*]` doesn't parse.
          case Some(_: scala.meta.Type.Repeated) => None
          case Some(t)                           => Some(substituteTparams(t))
          case None                              => None
      }
    }

  /** Apply per-arg casts in step with `vs` produced by `bindArgsCps`.
   *  Handles both positional args (cast the bound value as a whole) and
   *  named args (`vs(i)` looks like `"name = value"` — cast the rhs). */
  private def applyCalleeCasts(
      callee: String,
      args:   List[scala.meta.Term],
      vs:     List[String]
  ): List[String] =
    if depClasses.get(callee).isEmpty && depDefs.get(callee).isEmpty then vs
    else
      vs.zip(args).zipWithIndex.map { case ((v, arg), i) =>
        arg match
          case scala.meta.Term.Assign(scala.meta.Term.Name(n), _) =>
            calleeParamType(callee, i, Some(n)) match
              case None => v
              case Some(t) =>
                val eqIdx = v.indexOf('=')
                if eqIdx < 0 then v
                else
                  val rhs = v.substring(eqIdx + 1).trim
                  s"$n = $rhs.asInstanceOf[$t]"
          case _ =>
            calleeParamType(callee, i, None) match
              case None    => v
              case Some(t) => s"$v.asInstanceOf[$t]"
      }

  /** Emit a Scala block in CPS form: thread vals + statements via `_bind`. */
  private def emitCpsBlock(stats: List[Stat]): String =
    if stats.isEmpty then "()"
    else
      // Nested `def f = receive { ... }` inside a runActors body —
      // emit with CPS body so `receive` / `!` reach their CPS arms
      // instead of staying raw and failing `Not found: receive`.
      // Conservative trigger: only when the def's body transitively
      // contains an effect primitive (same predicate dep-mode uses).
      def emitDefMaybeCps(d: Defn.Def): String =
        if containsEffectPrimitive(d.body) then
          val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map { p =>
            p.decltpe.map(t => s"${p.name.value}: ${t.syntax}")
              .getOrElse(s"${p.name.value}: Any")
          }.mkString(", ")
          s"def ${d.name.value}($params): Any = ${emitCpsExpr(d.body)}"
        else d.syntax
      def build(remaining: List[Stat]): String = remaining match
        case Nil => "()"
        case List(s) =>
          s match
            case t: Term => emitCpsExpr(t)
            case Defn.Val(_, List(Pat.Var(n)), tpe, rhs) =>
              emitCpsBindWithType(rhs, n.value, tpe, "()")
            case d: Defn.Def => s"{ ${emitDefMaybeCps(d)}; () }"
            case other => s"{ ${other.syntax}; () }"
        case s :: rest =>
          s match
            case Defn.Val(_, List(Pat.Var(n)), tpe, rhs) =>
              emitCpsBindWithType(rhs, n.value, tpe, build(rest))
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), tpe, rhs) =>
              emitCpsBindWithType(rhs, n.value, tpe, build(rest))
            case d: Defn.Def =>
              s"{ ${emitDefMaybeCps(d)}; ${build(rest)} }"
            case t: Term =>
              if isSimpleCps(t) then s"{ ${t.syntax}; ${build(rest)} }"
              else
                val tmp = freshTmp()
                s"_bind(${emitCpsExpr(t)}, (${tmp}: Any) => ${build(rest)})"
            case other => s"{ ${other.syntax}; ${build(rest)} }"
      build(stats)

  /** Emit a CPS `_bind(rhs, lambda)` that preserves the user's
   *  declared val type inside the lambda body — without breaking the
   *  runtime's `_bind(c: Any, f: Any => Any): Any` signature.
   *
   *  When the user wrote `val x: T = expr`, we emit:
   *    `_bind(rhs, ((x: T) => body).asInstanceOf[Any => Any])`
   *
   *  The `.asInstanceOf[Any => Any]` widens the function to the
   *  signature `_bind` expects; inside the body `x` still has type `T`
   *  so downstream constructor calls / field accesses typecheck the
   *  way the user intended.
   *
   *  When no type ascription is present, fall back to the legacy
   *  `(x: Any) => body` form unchanged. */
  private def emitCpsBindWithType(
      rhs:  Term,
      name: String,
      tpe:  Option[scala.meta.Type],
      body: => String
  ): String =
    // Pre-emit `rhs` so its tmp names (if any) come from the OUTER
    // scope's freshTmp counter, then build the continuation `body`
    // with `name` registered as Any-bound if tpe was None — so
    // `name.member` inside `body` routes via _dispatch (chunk 8).
    val rhsEmit = emitCpsExpr(rhs)
    tpe match
      case None =>
        val bodyStr = withAnyBoundNames(Set(name))(body)
        s"_bind($rhsEmit, (${name}: Any) => ${bodyStr})"
      case Some(t) =>
        val tSyntax = t.syntax
        s"_bind($rhsEmit, ((${name}: ${tSyntax}) => ${body}).asInstanceOf[Any => Any])"

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
       |def print(v: Any): Unit   = scala.Predef.print(_show(v))
       |
       |// Stage 5+/B.3 — `Console` shadows `scala.Console` so that Normalize's
       |// bare-println rewrite (`println` → `Console.println`) routes through
       |// `_show` in both the emitExpr intrinsic path and the passthrough path.
       |object Console:
       |  def println(v: Any): Unit = scala.Predef.println(_show(v))
       |  def print(v: Any): Unit   = scala.Predef.print(_show(v))
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
       |// i18n runtime helpers
       |var _i18nLocale: String = "en"
       |var _i18nTable: Map[String, Map[String, String]] = Map.empty
       |def setLocale(code: String): Unit = { _i18nLocale = code }
       |def t(key: String): String = _i18nTable.get(_i18nLocale).flatMap(_.get(key)).getOrElse(key)
       |/** `wc(tag, Component, args*)` — server-side render with declarative shadow DOM.
       | *  Uses reflection to call `Component.css` and `Component.render(args*)`,
       | *  following the same convention as `collectCss`. */
       |def wc(tag: String, component: Any, args: Any*): String =
       |  val cssStr =
       |    try component.getClass.getMethod("css").invoke(component) match
       |      case s: String => s
       |      case _         => ""
       |    catch case _: Throwable => ""
       |  val innerHtml =
       |    try
       |      val cls = component.getClass
       |      val methods = cls.getMethods.filter(_.getName == "render")
       |      val renderM = methods.find(m => m.getParameterCount == args.length)
       |        .orElse(methods.headOption)
       |      renderM match
       |        case Some(m) =>
       |          m.invoke(component, args.map(_.asInstanceOf[AnyRef])*) match
       |            case r: _Raw => r.html
       |            case v       => _show(v)
       |        case None => ""
       |    catch case _: Throwable => ""
       |  s"<$tag-component><template shadowrootmode=\"open\"><style>$cssStr</style>$innerHtml</template></$tag-component>"
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
       |// ── Rate limiting / TOTP / Password — adapter shims ───────────
       |// The implementations live in runtime-server-common (inlined as
       |// classpath resources by JvmGen.commonRuntime); these top-level
       |// defs preserve the user-facing API.
       |def rateLimit(key: String, limit: Long, windowSeconds: Long): Boolean =
       |  RateLimit.tryAcquire(key, limit, windowSeconds)
       |def rateLimitReset(key: String): Unit = RateLimit.reset(key)
       |def totpSecret(): String = Totp.secret()
       |def totpUri(secret: String, account: String, issuer: String = ""): String =
       |  Totp.uri(secret, account, issuer)
       |def totpCode(secret: String): String = Totp.code(secret)
       |def totpValid(secret: String, code: String, skew: Int = 1): Boolean =
       |  Totp.valid(secret, code, skew)
       |def hashPassword(password: String, iter: Int = 200000): String =
       |  Password.hash(password, iter)
       |def verifyPassword(password: String, encoded: String): Boolean =
       |  Password.verify(password, encoded)
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
  /** Read a `.scala` source file from one of our runtime resource
   *  bundles and return its body with the leading `package …` line
   *  stripped.  The result is suitable for direct inlining into a
   *  top-level scala-cli script (which has no package declaration).
   *  Imports inside the file are preserved.
   *
   *  Two bundles exist:
   *    - `http-server-common-sources/scalascript/server/`
   *      (Phase 1b — pure protocol primitives + POJO HTTP model +
   *      shared dispatch loops; shared with the interpreter)
   *    - `http-server-jvm-sources/scalascript/server/jvm/`
   *      (Phase 3 — JVM-specific server lifecycle, route / WS
   *      registration, proxy, outbound clients; what used to be
   *      `serveRuntime`'s `"""|..."""` string template)
   *
   *  See the `runtimeServerCommon` / `runtimeServerJvm` settings in
   *  `build.sbt` for how the resources get packaged. */
  private def loadRuntimeSource(bundle: String, subPath: String, name: String): String =
    val path = s"/$bundle/$subPath/$name.scala"
    val stream = getClass.getResourceAsStream(path)
    if stream == null then
      throw new RuntimeException(s"runtime resource missing: $path " +
        s"— is `$bundle / copyResources` up to date?")
    val raw = try
      new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()
    // Drop the leading `package …` declaration.  The generated scala-cli
    // script is top-level; mixing a package decl with top-level statements
    // would be invalid.  Leading blank line(s) after the package line are
    // also dropped for readability.  Also rewrite `private[server]` /
    // `protected[server]` (and the `[jvm]` variants used in the JVM bundle)
    // access modifiers to bare `private` / `protected` — at top level the
    // qualified form has no referent and file-local visibility is
    // sufficient since all inlined sources end up in the same compilation
    // unit.
    // Drop `// BUILD-ONLY:start … // BUILD-ONLY:end` blocks — files in
    // `runtime-server-jvm` use those to declare local stubs for symbols
    // that are defined elsewhere in the inlined output (e.g. the
    // preamble's `_show`, Part2's `_Metrics`) so the file type-checks
    // standalone in our build.  At scala-cli inline time the stubs are
    // stripped so they don't clash with the real definitions.
    val noStubs =
      val sb = new StringBuilder
      var inStub = false
      raw.linesIterator.foreach { l =>
        val trimmed = l.trim
        if trimmed.startsWith("// BUILD-ONLY:start") then inStub = true
        else if trimmed.startsWith("// BUILD-ONLY:end") then inStub = false
        else if !inStub then sb.append(l).append('\n')
      }
      sb.toString
    noStubs.linesIterator
      .dropWhile(l => l.trim.startsWith("package ") || l.trim.isEmpty)
      .map(_.replace("private[server]",    "private")
            .replace("protected[server]",  "protected")
            .replace("private[jvm]",       "private")
            .replace("protected[jvm]",     "protected"))
      .mkString("\n", "\n", "\n")

  /** Phase 1b loader — pulls files from `runtime-server-common`. */
  private def loadCommonSource(name: String): String =
    loadRuntimeSource("http-server-common-sources", "scalascript/server", name)

  /** Phase 3 loader — pulls files from `runtime-server-jvm`.  Will
   *  be used by Phase 3b–3e as the migration of `serveRuntime` content
   *  proceeds; suppress the "unused" warning until then. */
  @scala.annotation.unused
  private def loadJvmRuntimeSource(name: String): String =
    loadRuntimeSource("http-server-jvm-sources", "scalascript/server/jvm", name)

  /** v1.17.6 / Phase S1c loader — pulls SPI traits from
   *  `runtime-server-spi`.  Same shape as the common / JVM loaders
   *  above; emitted at the top of `serveRuntime` so the codegen
   *  `serve(port, tls)` can route through `HttpServerBackends.current()`
   *  exactly like the interpreter does. */
  private def loadSpiRuntimeSource(name: String): String =
    loadRuntimeSource("http-server-spi-sources", "scalascript/server/spi", name)

  /** Inline `scalascript.logging.Logger` from the `logger` module's JAR
   *  resource.  Strips the `package scalascript.logging` declaration so the
   *  class lands at the generated script's top level, where inlined
   *  runtime-server sources can reference it by the unqualified name `Logger`
   *  (their BUILD-ONLY import blocks provide the qualified name for the
   *  module build). */
  private lazy val loggerRuntime: String =
    val path = "/logger-sources/scalascript/logging/Logger.scala"
    val stream = getClass.getResourceAsStream(path)
    if stream == null then
      throw new RuntimeException(s"logger resource missing: $path " +
        "— is `logger / copyResources` up to date?")
    val raw = try
      new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()
    val header =
      "\n// ── scalascript-logger (inlined from classpath resources) ─────────────\n" +
      "// Source of truth: logger/src/main/scala/scalascript/logging/Logger.scala\n"
    val body = raw.linesIterator
      .dropWhile(l => l.trim.startsWith("package ") || l.trim.isEmpty)
      .mkString("\n", "\n", "\n")
    val withEffectMethods = body.replace(
      "object Logger:\n  def apply(name: String): Logger  = new Logger(name)",
      """object Logger:
  def info (msg: Any): Any  = _perform("Logger", "info",  msg)
  def warn (msg: Any): Any  = _perform("Logger", "warn",  msg)
  def error(msg: Any): Any  = _perform("Logger", "error", msg)
  def debug(msg: Any): Any  = _perform("Logger", "debug", msg)

  def apply(name: String): Logger  = new Logger(name)"""
    )
    header + withEffectMethods

  /** Concatenate the pure-primitive sources from runtime-server-common in
   *  a deterministic order.  Emitted as a `commonRuntime` block at the
   *  top of the generated script (before `serveRuntime`) so the inlined
   *  objects (WsFraming, Password, Jwt, …) are in scope for adapter
   *  shims inside `serveRuntime` and for user code.
   *
   *  Logger is prepended first so runtime-server sources that reference
   *  the unqualified `Logger` name find it in scope. */
  private lazy val commonRuntime: String =
    val files = List(
      "RestValidationError", "DerCodec", "WsFraming", "Metrics",
      "RateLimit", "Password", "Totp", "Jwt", "JwtRsa",
      "SessionCookie", "SessionStore", "OAuth", "WebAuthn",
      "UploadedFile", "HttpHelpers", "Multipart", "TlsContextBuilder",
      "CorsHelpers", "HttpModel", "BasicAuth", "ResponseWriter",
      "RequestBuilder", "StreamResponseWriter", "HttpDispatchLoop",
      "StaticAssetServer", "WsHandshake", "WsReassembler",
      "WsFrameDispatch", "WsRateLimiter"
    )
    val header =
      "\n// ── runtime-server-common (inlined from classpath resources) ──────────\n" +
      "// Source of truth: runtime-server-common/src/main/scala/scalascript/server/*.scala\n"
    loggerRuntime + header + files.map(loadCommonSource).mkString("\n")

  /** Server-side runtime (routes, sessions, JWT, OAuth, WS, …).
   *
   *  Phase 3 (Option A from `docs/runtime-server-strategic-plan.md`)
   *  is complete: the entire content used to live in three
   *  triple-quoted string templates (Part1 / Part1b / Part2);
   *  now it's four real Scala source files in
   *  `runtime-server-jvm/src/main/scala/scalascript/server/jvm/`,
   *  type-checked at our build time and inlined into the codegen
   *  output via `loadJvmRuntimeSource`.
   *
   *  v1.17.6 / Phase S1c: the SPI traits + `HttpServerBackends`
   *  registry + `JdkServerBackend` impl are inlined at the top so
   *  `serve(port, tls)` resolves through `HttpServerBackends.current()`
   *  instead of constructing its own accept loop.  Wire-equivalent
   *  to the interpreter's S1b flow (`WebServer.start` →
   *  `HttpServerBackends.current().start(port, tls, handler)`). */
  private val serveRuntime: String =
    val spiHeader =
      "\n// ── runtime-server-spi (inlined from classpath resources) ────────────\n" +
      "// Source of truth: runtime-server-spi/src/main/scala/scalascript/server/spi/*.scala\n"
    val jvmHeader =
      "\n// ── runtime-server-jvm (inlined from classpath resources) ─────────────\n" +
      "// Source of truth: runtime-server-jvm/src/main/scala/scalascript/server/jvm/*.scala\n"
    spiHeader + loadSpiRuntimeSource("HttpServerSpi") +
                loadSpiRuntimeSource("HttpServerBackends") +
    jvmHeader + loadJvmRuntimeSource("RestRuntime") +
                loadJvmRuntimeSource("WebSocketRuntime") +
                loadJvmRuntimeSource("JdkServerBackend") +
                loadJvmRuntimeSource("ProxyRuntime") +
                loadJvmRuntimeSource("OutboundClients")

  private val fsRuntime: String =
    """|
       |// ── std.fs — synchronous file primitives (java.nio.file) ─────────────────
       |// Defined under the user-facing names so nested calls like
       |// `println(readFile(path))` resolve directly without intrinsic
       |// dispatch (dispatch only fires for top-level Apply, not args).
       |def writeFile(path: String, contents: String): Unit =
       |  java.nio.file.Files.write(
       |    java.nio.file.Paths.get(path),
       |    contents.getBytes(java.nio.charset.StandardCharsets.UTF_8))
       |  ()
       |def readFile(path: String): String =
       |  new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
       |             java.nio.charset.StandardCharsets.UTF_8)
       |def deleteFile(path: String): Unit =
       |  java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path))
       |  ()
       |def exists(path: String): Boolean =
       |  java.nio.file.Files.exists(java.nio.file.Paths.get(path))
       |
       |""".stripMargin

  private val generatorRuntime: String =
    """|
       |// ── v1.10 Generator — pull-based lazy streams via virtual threads ────────
       |// Each Generator[A] runs its body in a virtual thread.
       |// suspend(v) blocks the body thread until the consumer calls .next().
       |private val _genQueueTL = new ThreadLocal[java.util.concurrent.SynchronousQueue[Option[Any]]]()
       |
       |private def _suspend(v: Any): Unit =
       |  val q = _genQueueTL.get()
       |  if q == null then throw new RuntimeException("suspend called outside a coroutine or generator body")
       |  q.put(Some(v))
       |
       |def suspend(v: Any): Any =
       |  val coH = _coHandleTL.get()
       |  if coH != null then
       |    coH.fromBody.put(Yielded(v))
       |    coH.toBody.take()
       |  else
       |    _suspend(v)
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
       |  def flatMap[B](f: A => _Generator[B]): _Generator[B] = new _Generator[B]({ () =>
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      val inner = f(item.get)
       |      var sub = inner.next()
       |      while sub.isDefined do
       |        _suspend(sub.get)
       |        sub = inner.next()
       |      item = queue.take().asInstanceOf[Option[A]]
       |  })
       |
       |  def zip[B](other: _Generator[B]): _Generator[(A, B)] = new _Generator[(A, B)]({ () =>
       |    var a = queue.take().asInstanceOf[Option[A]]
       |    var b = other.next()
       |    while a.isDefined && b.isDefined do
       |      _suspend((a.get, b.get))
       |      a = queue.take().asInstanceOf[Option[A]]
       |      if a.isDefined then b = other.next()
       |  })
       |
       |  def zipWithIndex: _Generator[(A, Int)] = new _Generator[(A, Int)]({ () =>
       |    var idx = 0
       |    var item = queue.take().asInstanceOf[Option[A]]
       |    while item.isDefined do
       |      _suspend((item.get, idx))
       |      idx += 1
       |      item = queue.take().asInstanceOf[Option[A]]
       |  })
       |
       |def generator[T](body: () => Unit): _Generator[T] = new _Generator[T](body)
       |
       |// ── v1.9 Coroutine primitive — virtual-thread handshake ──────────────────
       |// Two-way suspend/resume via a pair of SynchronousQueues.
       |// Protocol (lazy start): body waits on toBody.take() for the first resume,
       |// each suspend puts Yielded(out) and takes from toBody for the next input.
       |case class Yielded(value: Any)
       |case class Returned(value: Any)
       |case class Errored(message: String)
       |
       |private case class _CoHandle(
       |  fromBody: java.util.concurrent.SynchronousQueue[Any],
       |  toBody:   java.util.concurrent.SynchronousQueue[Any]
       |)
       |case class _Coroutine(_id: Long)
       |private val _coHandleTL = new ThreadLocal[_CoHandle]()
       |private val _coHandles  = new java.util.concurrent.ConcurrentHashMap[Long, _CoHandle]()
       |private val _nextCoId   = new java.util.concurrent.atomic.AtomicLong(0L)
       |
       |def coroutineCreate(body: () => Any): _Coroutine =
       |  val fromBody = new java.util.concurrent.SynchronousQueue[Any]()
       |  val toBody   = new java.util.concurrent.SynchronousQueue[Any]()
       |  val handle   = _CoHandle(fromBody, toBody)
       |  val id       = _nextCoId.getAndIncrement()
       |  _coHandles.put(id, handle)
       |  Thread.ofVirtual().start { () =>
       |    _coHandleTL.set(handle)
       |    toBody.take()
       |    try
       |      val result = body()
       |      fromBody.put(Returned(result))
       |    catch case t: Throwable =>
       |      val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
       |      try fromBody.put(Errored(msg)) catch case _: Throwable => ()
       |  }
       |  _Coroutine(id)
       |
       |def coroutineResume(co: Any, in: Any): Any =
       |  co match
       |    case _Coroutine(id) =>
       |      val handle = _coHandles.get(id)
       |      if handle == null then throw new RuntimeException("coroutineResume: coroutine already completed")
       |      handle.toBody.put(in)
       |      val step = handle.fromBody.take()
       |      step match
       |        case _: Returned | _: Errored => _coHandles.remove(id)
       |        case _ => ()
       |      step
       |    case _ => throw new RuntimeException("coroutineResume: not a coroutine")
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
       |  // Collection ops — `+`/`-` on Set/Map for membership update,
       |  // `+` on List/Map for cons/insert (CPS dep code uses these
       |  // via _binOp when operands' static types are Any).
       |  case ("+", xs: Set[_], y)         => xs.asInstanceOf[Set[Any]] + y
       |  case ("-", xs: Set[_], y)         => xs.asInstanceOf[Set[Any]] - y
       |  case ("+", xs: Map[_, _], y: (_, _)) =>
       |    xs.asInstanceOf[Map[Any, Any]] + y.asInstanceOf[(Any, Any)]
       |  case _ => sys.error(s"Cannot $op on $a, $b")
       |
       |// ── Built-in `Async` effect + v1.11 coroutine-based `runAsync` ─────────
       |//
       |// v1.11: Async.* ops check _coHandleTL.  When set (inside a runAsync
       |// virtual thread), they suspend with an IORequest case class instead of
       |// returning a _Perform node.  The runAsync scheduler drives the coroutine
       |// and dispatches IORequests.  runAsyncParallel still uses the old Free
       |// monad path (Async.* return _perform nodes when _coHandleTL is null).
       |
       |// IORequest types for the runAsync coroutine scheduler
       |private case class _DelayIO(ms: Long)
       |private case class _AsyncIO(thunk: () => Any)
       |private case class _AwaitIO(fut: Any)
       |private case class _ParallelIO(thunks: List[() => Any])
       |private case class _RecvFromIO(ws: Any)
       |
       |object Async:
       |  def delay(ms: Int): Any =
       |    val coH = _coHandleTL.get()
       |    if coH != null then
       |      coH.fromBody.put(Yielded(_DelayIO(ms.toLong)))
       |      coH.toBody.take()
       |    else _perform("Async", "delay", ms)
       |  def async(thunk: () => Any): Any =
       |    val coH = _coHandleTL.get()
       |    if coH != null then
       |      coH.fromBody.put(Yielded(_AsyncIO(thunk)))
       |      coH.toBody.take()
       |    else _perform("Async", "async", thunk)
       |  def await(fut: Any): Any =
       |    val coH = _coHandleTL.get()
       |    if coH != null then
       |      coH.fromBody.put(Yielded(_AwaitIO(fut)))
       |      coH.toBody.take()
       |    else _perform("Async", "await", fut)
       |  def parallel(thunks: List[() => Any]): Any =
       |    val coH = _coHandleTL.get()
       |    if coH != null then
       |      coH.fromBody.put(Yielded(_ParallelIO(thunks)))
       |      coH.toBody.take()
       |    else _perform("Async", "parallel", thunks)
       |  def recvFrom(ws: Any): Any =
       |    val coH = _coHandleTL.get()
       |    if coH != null then
       |      coH.fromBody.put(Yielded(_RecvFromIO(ws)))
       |      coH.toBody.take()
       |    else
       |      ws.asInstanceOf[Map[String, Any]]("recv").asInstanceOf[() => Any]()
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
       |  // Option-returning fns flatten via .toList (Some(v) → [v];
       |  // None → []) so `xs.flatMap(x => Option[v])` works at
       |  // runtime like the Scala stdlib does.
       |  def flatten(v: Any): List[Any] = v match
       |    case ys: List[_]   => ys.asInstanceOf[List[Any]]
       |    case opt: Option[_] => opt.toList.asInstanceOf[List[Any]]
       |    case other         => List(other)
       |  s match
       |    case c: _Computation =>
       |      _bind(c, (rs: Any) => rs.asInstanceOf[List[Any]].flatMap(flatten))
       |    case rs: List[_] => rs.asInstanceOf[List[Any]].flatMap(flatten)
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
       |    // `.toMap` / `.toSet` carry implicit evidence — reflection
       |    // sees them as 1-arg methods that don't match a Nil call.
       |    case (xs: List[_], "toMap",    Nil)       =>
       |      xs.asInstanceOf[List[(Any, Any)]].toMap
       |    case (xs: List[_], "toSet",    Nil)       => xs.toSet
       |    case (xs: List[_], "zip",      List(other)) =>
       |      xs.zip(other.asInstanceOf[Iterable[Any]])
       |    case (xs: List[_], "zipWithIndex", Nil)   => xs.zipWithIndex
       |    // `.sortBy(fn)` carries an implicit Ordering — like toMap,
       |    // reflection-arity check rejects the 2-arg signature.
       |    case (xs: List[_], "sortBy",  List(fn))   =>
       |      given _ordAny: Ordering[Any] = new Ordering[Any]:
       |        def compare(a: Any, b: Any): Int = (a, b) match
       |          case (x: Int,    y: Int)    => x.compare(y)
       |          case (x: Long,   y: Long)   => x.compare(y)
       |          case (x: Double, y: Double) => x.compare(y)
       |          case (x: String, y: String) => x.compare(y)
       |          case _ => a.toString.compare(b.toString)
       |      xs.asInstanceOf[List[Any]].sortBy(fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "sorted", Nil) =>
       |      given _ordAny: Ordering[Any] = new Ordering[Any]:
       |        def compare(a: Any, b: Any): Int = (a, b) match
       |          case (x: Int,    y: Int)    => x.compare(y)
       |          case (x: Long,   y: Long)   => x.compare(y)
       |          case (x: Double, y: Double) => x.compare(y)
       |          case (x: String, y: String) => x.compare(y)
       |          case _ => a.toString.compare(b.toString)
       |      xs.asInstanceOf[List[Any]].sorted
       |    case (xs: List[_], "groupBy", List(fn))   =>
       |      xs.asInstanceOf[List[Any]].groupBy(fn.asInstanceOf[Any => Any])
       |    case (xs: List[_], "headOption", Nil)     => xs.headOption
       |    case (xs: List[_], "lastOption", Nil)     => xs.lastOption
       |    case (xs: List[_], "drop",   List(n: Int))  => xs.drop(n)
       |    case (xs: List[_], "take",   List(n: Int))  => xs.take(n)
       |    case (xs: List[_], "distinct", Nil)       => xs.distinct
       |    case (xs: List[_], "contains", List(x))   =>
       |      xs.asInstanceOf[List[Any]].contains(x)
       |    case (xs: List[_], "mkString", Nil)       => xs.mkString
       |    case (xs: List[_], "mkString", List(s: String)) => xs.mkString(s)
       |    case (xs: List[_], "sum",      Nil)       => xs.asInstanceOf[List[Any]].foldLeft(0: Any)((a, b) => _binOp("+", a, b))
       |    case (s: String,   "length",   Nil)       => s.length
       |    case (s: String,   "size",     Nil)       => s.length
       |    case (s: String,   "toInt",    Nil)       => s.toInt
       |    case (s: String,   "toLong",   Nil)       => s.toLong
       |    case (s: String,   "toDouble", Nil)       => s.toDouble
       |    case (s: String,   "take",     List(n: Int))  => s.take(n)
       |    case (s: String,   "drop",     List(n: Int))  => s.drop(n)
       |    case (s: String,   "head",     Nil)       => s.head
       |    case (s: String,   "tail",     Nil)       => s.tail
       |    case (s: String,   "isEmpty",  Nil)       => s.isEmpty
       |    case (s: String,   "nonEmpty", Nil)       => s.nonEmpty
       |    case (s: String,   "trim",     Nil)       => s.trim
       |    case (s: String,   "toLowerCase", Nil)    => s.toLowerCase
       |    case (s: String,   "toUpperCase", Nil)    => s.toUpperCase
       |    case (s: String,   "split",    List(sep: String)) => s.split(sep).toList
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
       |    // Map ops — by-name default arg in `getOrElse` confuses
       |    // the reflection fallback, so dispatch explicitly.
       |    case (m: Map[_, _], "getOrElse", List(k, d)) =>
       |      m.asInstanceOf[Map[Any, Any]].getOrElse(k, d)
       |    case (m: Map[_, _], "get",       List(k))    =>
       |      m.asInstanceOf[Map[Any, Any]].get(k)
       |    case (m: Map[_, _], "contains",  List(k))    =>
       |      m.asInstanceOf[Map[Any, Any]].contains(k)
       |    case (m: Map[_, _], "size",      Nil)        => m.size
       |    case (m: Map[_, _], "isEmpty",   Nil)        => m.isEmpty
       |    case (m: Map[_, _], "nonEmpty",  Nil)        => m.nonEmpty
       |    case (m: Map[_, _], "keys",      Nil)        =>
       |      m.asInstanceOf[Map[Any, Any]].keys
       |    case (m: Map[_, _], "values",    Nil)        =>
       |      m.asInstanceOf[Map[Any, Any]].values
       |    // Map key access for runtime record types (e.g. `info.mailboxSize` on
       |    // a ProcessInfo map).  Must come after the explicit method cases above.
       |    case (m: Map[_, _], key, Nil)               =>
       |      m.asInstanceOf[Map[Any, Any]].getOrElse(key, null)
       |    // Set ops
       |    case (s: Set[_], "contains",  List(x)) => s.asInstanceOf[Set[Any]].contains(x)
       |    case (s: Set[_], "size",      Nil)     => s.size
       |    case (s: Set[_], "isEmpty",   Nil)     => s.isEmpty
       |    case (s: Set[_], "nonEmpty",  Nil)     => s.nonEmpty
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
       |// v1.11 coroutine-based runAsync scheduler
       |def _driveAsyncCo(
       |  fromBody: java.util.concurrent.SynchronousQueue[Any],
       |  toBody:   java.util.concurrent.SynchronousQueue[Any]
       |): Any =
       |  while true do
       |    fromBody.take() match
       |      case Returned(v)           => return v
       |      case Errored(msg)          => throw new RuntimeException(s"Async error: $msg")
       |      case Yielded(_DelayIO(ms)) =>
       |        if ms > 0 then Thread.sleep(ms)
       |        toBody.put(())
       |      case Yielded(_AsyncIO(thunk)) =>
       |        toBody.put(Future(_runAsync(thunk)))
       |      case Yielded(_AwaitIO(fut)) =>
       |        toBody.put(fut match
       |          case Future(v) => v
       |          case other     => sys.error(s"Async.await: expected Future, got $other"))
       |      case Yielded(_ParallelIO(thunks)) =>
       |        toBody.put(thunks.map(_runAsync))
       |      case Yielded(_RecvFromIO(ws)) =>
       |        toBody.put(ws.asInstanceOf[Map[String, Any]]("recv").asInstanceOf[() => Any]())
       |      case other =>
       |        sys.error(s"_driveAsyncCo: unexpected step: $other")
       |  sys.error("unreachable")
       |
       |def _runAsync(bodyThunk: () => Any): Any =
       |  val fromBody = new java.util.concurrent.SynchronousQueue[Any]()
       |  val toBody   = new java.util.concurrent.SynchronousQueue[Any]()
       |  val handle   = _CoHandle(fromBody, toBody)
       |  Thread.ofVirtual().start { () =>
       |    _coHandleTL.set(handle)
       |    toBody.take()
       |    try
       |      val result = bodyThunk()
       |      fromBody.put(Returned(result))
       |    catch case t: Throwable =>
       |      val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
       |      try fromBody.put(Errored(msg)) catch case _: Throwable => ()
       |  }
       |  toBody.put(())
       |  _driveAsyncCo(fromBody, toBody)
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
       |  // Java 21 requirement: virtual threads (Project Loom) for lightweight parallelism.
       |  val _ex = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
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
       |""".stripMargin +
    """|
       |// ── v1.6 Actors — Phase 1 cooperative scheduler ────────────────────────
       |//
       |// Same Computation / Free-Monad walk as `_runAsync` but the outer
       |// loop interleaves multiple actors.  Mailboxes are `LinkedBlockingQueue`s
       |// (v1.9.x: same infrastructure as coroutines, thread-safe);
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
       |// v1.23 — cluster visibility events
       |case class NodeJoined(nodeId: String)
       |case class NodeLeft(nodeId: String, reason: String)
       |// v1.23 — leader election (Bully) events
       |case class LeaderElected(nodeId: String)
       |case class LeaderLost(nodeId: String)
       |// v1.23 — config-distribution events
       |case class ConfigChanged(key: String, value: String)
       |// v1.23 — drain / rolling-restart events
       |case class DrainStateChanged(nodeId: String, draining: Boolean)
       |// v1.23 — cluster metrics aggregation events
       |case class MetricChanged(name: String, nodeId: String, value: Double)
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
       |  def spawn_link(thunk: () => Any): Any         = _perform("Actor", "spawnLink",   thunk)
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
       |  def startNode(nodeId: Any, url: Any = ""): Any = _perform("Actor", "startNode",   nodeId, url)
       |  def connectNode(url: Any, tok: Any = ""): Any  = _perform("Actor", "connectNode", url, tok)
       |  def joinCluster(seeds: Any, tok: Any = ""): Any = _perform("Actor", "joinCluster", seeds, tok)
       |  def register(name: Any, pid: Any): Any             = _perform("Actor", "register",       name, pid)
       |  def whereis(name: Any): Any                        = _perform("Actor", "whereis",        name)
       |  // v1.6.x — cluster-wide registry
       |  def globalRegister(name: Any, pid: Any): Any       = _perform("Actor", "globalRegister", name, pid)
       |  def globalWhereis(name: Any): Any                  = _perform("Actor", "globalWhereis",  name)
       |  // v1.6.x — scheduled sends
       |  def sendAfter(delayMs: Any, pid: Any, msg: Any): Any   = _perform("Actor", "sendAfter",   delayMs, pid, msg)
       |  def sendInterval(periodMs: Any, pid: Any, msg: Any): Any = _perform("Actor", "sendInterval", periodMs, pid, msg)
       |  def cancelTimer(ref: Any): Any                          = _perform("Actor", "cancelTimer", ref)
       |  // v1.6.x — bounded mailbox spawn
       |  def spawnBounded(cap: Any, overflow: Any, thunk: () => Any): Any = _perform("Actor", "spawnBounded", cap, overflow, thunk)
       |  // v1.6.x — process introspection
       |  def processInfo(pid: Any): Any = _perform("Actor", "processInfo", pid)
       |  // v1.23 — cluster visibility
       |  def clusterMembers(): Any         = _perform("Actor", "clusterMembers")
       |  def subscribeClusterEvents(): Any = _perform("Actor", "subscribeClusterEvents")
       |  // v1.23 — phi-accrual failure detector
       |  def phiOf(nid: Any): Any           = _perform("Actor", "phiOf", nid)
       |  def isSuspect(nid: Any, thr: Any = 8.0): Any = _perform("Actor", "isSuspect", nid, thr)
       |  // v1.23 — local node identity + phi vector
       |  def selfNode(): Any      = _perform("Actor", "selfNode")
       |  def clusterHealth(): Any = _perform("Actor", "clusterHealth")
       |  // v1.23 — cluster-wide failure detector
       |  def broadcastHealth(): Any                            = _perform("Actor", "broadcastHealth")
       |  def clusterIsDown(nid: Any, thr: Any = 8.0): Any      = _perform("Actor", "clusterIsDown", nid, thr)
       |  // v1.23 — leader election (Bully)
       |  def electLeader(): Any                                = _perform("Actor", "electLeader")
       |  def currentLeader(): Any                              = _perform("Actor", "currentLeader")
       |  def subscribeLeaderEvents(): Any                      = _perform("Actor", "subscribeLeaderEvents")
       |  def setAutoReelect(enabled: Any): Any                 = _perform("Actor", "setAutoReelect", enabled)
       |  // v1.23 — leader-protocol switch (Raft / external coordinator stubs).
       |  // See docs/cluster-raft.md for the spec.  Calling these promotes the
       |  // node off Bully but the alternative protocols' actual algorithms
       |  // land in subsequent phases — for now these mark intent and let
       |  // `leaderProtocol()` observe it.
       |  def useRaftLeaderElection(): Any                      = _perform("Actor", "useRaftLeaderElection")
       |  def useExternalCoordinator(acquireLease: Any, renewLease: Any,
       |                              releaseLease: Any, currentHolder: Any): Any =
       |    _perform("Actor", "useExternalCoordinator", acquireLease, renewLease, releaseLease, currentHolder)
       |  def leaderProtocol(): Any                             = _perform("Actor", "leaderProtocol")
       |  // v1.23 — bounded ring buffer of accepted leader claims this node has
       |  // observed.  Each entry is (term, leaderId, wallClockMs).
       |  def leaderHistory(): Any                              = _perform("Actor", "leaderHistory")
       |  // v1.23 — auto-reconnect policy (exponential backoff per peer)
       |  def setReconnectPolicy(initialMs: Any, maxMs: Any): Any = _perform("Actor", "setReconnectPolicy", initialMs, maxMs)
       |  def setReconnectPolicy(initialMs: Any, maxMs: Any, giveUpAfterMs: Any): Any =
       |    _perform("Actor", "setReconnectPolicy", initialMs, maxMs, giveUpAfterMs)
       |  // v1.23 — per-link heartbeat cadence + dead-after threshold
       |  def setHeartbeatTimeout(intervalMs: Any, deadAfterMs: Any): Any =
       |    _perform("Actor", "setHeartbeatTimeout", intervalMs, deadAfterMs)
       |  // v1.23 — quorum-aware Bully threshold (split-brain guard)
       |  def setQuorumSize(n: Any): Any = _perform("Actor", "setQuorumSize", n)
       |  // v1.23 — periodic gossip re-discovery (ask peers for their peer list)
       |  def requestGossip(): Any = _perform("Actor", "requestGossip")
       |  // v1.23 — cluster configuration distribution
       |  def clusterConfigSet(key: Any, value: Any): Any  = _perform("Actor", "clusterConfigSet", key, value)
       |  def clusterConfigGet(key: Any): Any              = _perform("Actor", "clusterConfigGet", key)
       |  def clusterConfigKeys(): Any                     = _perform("Actor", "clusterConfigKeys")
       |  def subscribeConfigEvents(): Any                 = _perform("Actor", "subscribeConfigEvents")
       |  // v1.23 — drain / rolling-restart
       |  def setDraining(b: Any): Any                     = _perform("Actor", "setDraining", b)
       |  def isDraining(): Any                            = _perform("Actor", "isDraining")
       |  def drainingPeers(): Any                         = _perform("Actor", "drainingPeers")
       |  def subscribeDrainEvents(): Any                  = _perform("Actor", "subscribeDrainEvents")
       |  // v1.23 — cluster metrics aggregation
       |  def clusterMetricSet(name: Any, value: Any): Any = _perform("Actor", "clusterMetricSet", name, value)
       |  def clusterMetricGet(name: Any): Any             = _perform("Actor", "clusterMetricGet", name)
       |  def clusterMetricSum(name: Any): Any             = _perform("Actor", "clusterMetricSum", name)
       |  def clusterMetricNames(): Any                    = _perform("Actor", "clusterMetricNames")
       |  def subscribeMetricEvents(): Any                 = _perform("Actor", "subscribeMetricEvents")
       |
       |// v1.6.x — bounded mailbox overflow strategies.  Plain string values so
       |// `spawnBounded(cap, Overflow.DropOldest, thunk)` compiles and passes the
       |// right string to the actor scheduler.
       |object Overflow:
       |  val DropOldest: Any = "DropOldest"
       |  val DropNewest: Any = "DropNewest"
       |  val Block: Any = "Block"
       |  val Fail: Any = "Fail"
       |
       |class _ActorState:
       |  val mailbox = new java.util.concurrent.LinkedBlockingQueue[Any]()
       |  var pending: Any = null
       |  // (matcher, k, deadline?, wrapSome)
       |  var blocked: (Any => Option[Any], Any => Any, Option[Long], Boolean) = null
       |  // v1.6.x bounded mailbox
       |  var cap:      Int    = 0   // 0 = unbounded
       |  var overflow: String = ""
       |  val blockedSends = scala.collection.mutable.ArrayDeque.empty[(Long, Any, Any => Any)]
       |
       |def _runActors(bodyThunk: () => Any): Any =
       |  val actors    = scala.collection.mutable.LongMap.empty[_ActorState]
       |  // Phase 2 supervision state
       |  val links     = scala.collection.mutable.LongMap.empty[scala.collection.mutable.Set[Long]]
       |  val monitors  = scala.collection.mutable.LongMap.empty[scala.collection.mutable.Map[Long, Long]]
       |  val trapExitM = scala.collection.mutable.LongMap.empty[Boolean]
       |  var nextMonRef: Long = 0L
       |  // v1.6.x scheduled sends — timerId → (fireAt, periodMs, targetId, msg)
       |  val _timers      = scala.collection.mutable.LongMap.empty[(Long, Option[Long], Long, Any)]
       |  var _nextTimerId: Long = 0L
       |  // Phase 3 distributed state
       |  var _localNodeId:  String  = ""
       |  var _localNodeUrl: String  = ""
       |  @volatile var _joinMode:  Boolean = false
       |  @volatile var _joinToken: String  = ""
       |  val _peerUrls       = new java.util.concurrent.ConcurrentHashMap[String, String]()
       |  val _nodeRegistry    = new java.util.concurrent.ConcurrentHashMap[String, Long]()
       |  val _globalRegistry  = new java.util.concurrent.ConcurrentHashMap[String, _Pid]()
       |  val _peerChannels    = new java.util.concurrent.ConcurrentHashMap[String, String => Unit]()
       |  val _remoteInbox    = new java.util.concurrent.ConcurrentLinkedQueue[(Long, Any)]()
       |  val _peerLastPong   = new java.util.concurrent.ConcurrentHashMap[String, Long]()
       |  val _nodeDownQueue  = new java.util.concurrent.ConcurrentLinkedQueue[String]()
       |  // cross-node monitors: nodeId → [(localActorId, monRef, remotePid.localId)]
       |  val _remoteMonitors = new java.util.concurrent.ConcurrentHashMap[String,
       |    java.util.concurrent.CopyOnWriteArrayList[(Long, Long, Long)]]()
       |  // cross-node links:   nodeId → [(localActorId, remotePid.localId)]
       |  val _remoteLinks    = new java.util.concurrent.ConcurrentHashMap[String,
       |    java.util.concurrent.CopyOnWriteArrayList[(Long, Long)]]()
       |  // v1.23 — cluster visibility
       |  val _clusterEventSubs  = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
       |  val _clusterEventQueue = new java.util.concurrent.ConcurrentLinkedQueue[(String, String, String)]()
       |  // JSON string-escape helper — hoisted above all subsequent vals so
       |  // nested defs can forward-reference it without Scala 3 flagging the
       |  // ref as "extending over" a val initialiser.
       |  def _jstr(s: String): String =
       |    val sb = new StringBuilder(s.length + 2).append('"')
       |    s.foreach { case '"' => sb.append("\\\""); case '\\' => sb.append("\\\\")
       |                case '\n' => sb.append("\\n"); case c => sb.append(c) }
       |    sb.append('"').toString
       |  // v1.23 — bounded ring buffer of cluster events as JSON lines.  Feeds
       |  // `GET /_ssc-cluster/events`.  Independent of in-process subscribers
       |  // — events land here whether or not any actor has called
       |  // `subscribe*Events`, so ops tooling always has data.  Cap 200.
       |  val _CLUSTER_EVENT_LOG_MAX = 200
       |  val _clusterEventLog       = new java.util.concurrent.ConcurrentLinkedDeque[String]()
       |  def _recordEventLog(json: String): Unit =
       |    _clusterEventLog.offer(json)
       |    while _clusterEventLog.size() > _CLUSTER_EVENT_LOG_MAX do _clusterEventLog.pollFirst()
       |  // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
       |  // Reads `SSC_CLUSTER_TOKEN` env at startup.  Empty ⇒ endpoints open.
       |  @volatile var _clusterAuthToken: String =
       |    Option(System.getenv("SSC_CLUSTER_TOKEN")).getOrElse("")
       |  def _fireClusterEvent(tag: String, nodeId: String, reason: String = ""): Unit =
       |    val ts = System.currentTimeMillis()
       |    val logEntry =
       |      if tag == "NodeJoined" then
       |        "{\"ts\":" + ts.toString + ",\"type\":\"NodeJoined\",\"nodeId\":" + _jstr(nodeId) + "}"
       |      else
       |        "{\"ts\":" + ts.toString + ",\"type\":\"NodeLeft\",\"nodeId\":" + _jstr(nodeId) +
       |        ",\"reason\":" + _jstr(reason) + "}"
       |    _recordEventLog(logEntry)
       |    if !_clusterEventSubs.isEmpty then _clusterEventQueue.offer((tag, nodeId, reason))
       |  // v1.23 — phi-accrual failure detector: sliding window of inter-pong intervals.
       |  val _PHI_HIST_MAX  = 100
       |  val _peerPongHist  = new java.util.concurrent.ConcurrentHashMap[String,
       |    java.util.concurrent.ConcurrentLinkedDeque[java.lang.Long]]()
       |  // v1.23 — cluster-wide FD: peerNodeId -> view of (targetNodeId -> phi).
       |  val _peerPhiViews  = new java.util.concurrent.ConcurrentHashMap[String,
       |    java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]]()
       |  // v1.23 — leader election (Bully) state.  Single node-wide view.
       |  val _currentLeader        = new java.util.concurrent.atomic.AtomicReference[String]("")
       |  @volatile var _electionInProgress: Boolean = false
       |  @volatile var _electionStartedAt:  Long    = 0L
       |  @volatile var _gotAliveResponse:   Boolean = false
       |  val _ELECTION_TIMEOUT_MS  = 2000L
       |  val _leaderEventSubs      = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
       |  val _leaderEventQueue     = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()
       |  def _fireLeaderEvent(tag: String, leaderId: String): Unit =
       |    val ts = System.currentTimeMillis()
       |    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":" + _jstr(tag) +
       |                    ",\"nodeId\":" + _jstr(leaderId) + "}")
       |    if !_leaderEventSubs.isEmpty then _leaderEventQueue.offer((tag, leaderId))
       |  @volatile var _autoReelect: Boolean = false
       |  // v1.23 — protocol dispatch (cluster-raft.md §6).  "bully" today;
       |  //   Phase 3a flips to "raft", Phase 3b flips to "coord".
       |  val _leaderProtocol      = new java.util.concurrent.atomic.AtomicReference[String]("bully")
       |  @volatile var _leaderCoordinator: Any = null
       |  // v1.23 — bounded leader-claim history (cluster-raft.md §6).
       |  val _LEADER_HIST_MAX     = 100
       |  val _leaderHistTermSeq   = new java.util.concurrent.atomic.AtomicLong(0L)
       |  val _leaderHist          = new java.util.concurrent.ConcurrentLinkedDeque[(Long, String, Long)]()
       |  def _recordLeaderHist(leaderId: String): Unit =
       |    // Caller still gates on prev != new, so every call is a real change.
       |    val term = _leaderHistTermSeq.incrementAndGet()
       |    _leaderHist.offer((term, leaderId, System.currentTimeMillis()))
       |    while _leaderHist.size() > _LEADER_HIST_MAX do _leaderHist.pollFirst()
       |  // v1.23 — external-coordinator lease state (cluster-raft.md §5).
       |  // Pulled out via `productElement` so the runtime can call them
       |  // without structural types or reflection.
       |  @volatile var _coordAcquireFn: AnyRef = null  // (String, Long) => Boolean
       |  @volatile var _coordRenewFn:   AnyRef = null  // String => Boolean
       |  @volatile var _coordReleaseFn: AnyRef = null  // String => Unit
       |  @volatile var _coordHolderFn:  AnyRef = null  // () => Option[String]
       |  @volatile var _coordIsLeader:  Boolean = false
       |  val _coordTickThread = new java.util.concurrent.atomic.AtomicReference[Thread](null)
       |  val _COORD_LEASE_TIMEOUT_MS  = 5000L
       |  val _COORD_RENEW_INTERVAL_MS = 1000L
       |  def _ensureCoordTickThread(): Unit =
       |    if _coordTickThread.get() != null then return
       |    val t = Thread.ofVirtual().start { () =>
       |      try
       |        var done = false
       |        while !done && _leaderProtocol.get() == "coord" do
       |          try
       |            if !_coordIsLeader then
       |              val acq = _coordAcquireFn
       |              if acq != null then
       |                val got = try acq.asInstanceOf[(String, Long) => Boolean](_localNodeId, _COORD_LEASE_TIMEOUT_MS)
       |                          catch case _: Throwable => false
       |                if got then
       |                  _coordIsLeader = true
       |                  val prev = _currentLeader.getAndSet(_localNodeId)
       |                  if prev != _localNodeId then
       |                    _fireLeaderEvent("LeaderElected", _localNodeId)
       |                    _recordLeaderHist(_localNodeId)
       |            else
       |              val ren = _coordRenewFn
       |              if ren != null then
       |                val ok = try ren.asInstanceOf[String => Boolean](_localNodeId)
       |                         catch case _: Throwable => false
       |                if !ok then
       |                  _coordIsLeader = false
       |                  val prev = _currentLeader.getAndSet("")
       |                  if prev.nonEmpty then _fireLeaderEvent("LeaderLost", prev)
       |          catch case _: Throwable => ()
       |          try Thread.sleep(_COORD_RENEW_INTERVAL_MS)
       |          catch case _: InterruptedException => done = true
       |      catch case _: Throwable => ()
       |    }
       |    if !_coordTickThread.compareAndSet(null, t) then t.interrupt()
       |  // v1.23 — Raft state (cluster-raft.md §4.1).
       |  @volatile var _raftCurrentTerm: Long   = 0L
       |  @volatile var _raftVotedFor:    String = ""        // "" = None
       |  @volatile var _raftState:       String = "follower" // follower | candidate | leader
       |  @volatile var _raftLeaderId:    String = ""
       |  @volatile var _raftElectionDue: Long   = 0L
       |  @volatile var _raftVotes:       Int    = 0
       |  val _RAFT_ELECTION_LO  = 150L
       |  val _RAFT_ELECTION_HI  = 300L
       |  val _RAFT_HEARTBEAT_MS = 50L
       |  val _raftTickThread = new java.util.concurrent.atomic.AtomicReference[Thread](null)
       |  val _raftRand       = new scala.util.Random()
       |  def _raftRandTimeout: Long =
       |    _RAFT_ELECTION_LO + _raftRand.nextInt((_RAFT_ELECTION_HI - _RAFT_ELECTION_LO).toInt + 1)
       |  def _raftBroadcastHeartbeat(): Unit =
       |    val payload = "{\"t\":\"raft_append\",\"from\":" + _jstr(_localNodeId) +
       |                  ",\"term\":" + _raftCurrentTerm.toString + "}"
       |    _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |  def _raftAdoptLeader(newLeader: String): Unit =
       |    val prev = _currentLeader.getAndSet(newLeader)
       |    if prev != newLeader then
       |      _fireLeaderEvent("LeaderElected", newLeader)
       |      _recordLeaderHist(newLeader)
       |  def _startRaftElection(): Unit =
       |    _raftState       = "candidate"
       |    _raftCurrentTerm = _raftCurrentTerm + 1
       |    _raftVotedFor    = _localNodeId
       |    _raftVotes       = 1
       |    _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
       |    _raftPersist()
       |    val peerIds = scala.collection.mutable.ListBuffer.empty[String]
       |    _peerChannels.keySet().forEach(p => peerIds += p)
       |    val total = peerIds.size + 1
       |    // Single-node majority is trivially us — claim immediately.
       |    if _raftVotes > total / 2 then
       |      _raftState    = "leader"
       |      _raftLeaderId = _localNodeId
       |      _raftAdoptLeader(_localNodeId)
       |      _raftBroadcastHeartbeat()
       |    else
       |      val payload = "{\"t\":\"raft_vote_req\",\"from\":" + _jstr(_localNodeId) +
       |                    ",\"term\":" + _raftCurrentTerm.toString + ",\"lastLogTerm\":0}"
       |      peerIds.foreach { nid =>
       |        try Option(_peerChannels.get(nid)).foreach(_.apply(payload))
       |        catch case _: Throwable => ()
       |      }
       |  def _ensureRaftTickThread(): Unit =
       |    if _raftTickThread.get() != null then return
       |    val t = Thread.ofVirtual().start { () =>
       |      try
       |        while _leaderProtocol.get() == "raft" do
       |          Thread.sleep(_RAFT_HEARTBEAT_MS)
       |          val now = System.currentTimeMillis()
       |          _raftState match
       |            case "leader" =>
       |              _raftBroadcastHeartbeat()
       |            case "follower" | "candidate" =>
       |              if now >= _raftElectionDue then _startRaftElection()
       |            case _ => ()
       |      catch case _: InterruptedException => ()
       |    }
       |    if !_raftTickThread.compareAndSet(null, t) then t.interrupt()
       |  // v1.23 — Raft persistence (cluster-raft.md §4.1).  One JSON file per
       |  // node, written on every (term, votedFor) mutation so a crashed-and-
       |  // restarted node doesn't double-vote in the same term.  Best-effort:
       |  // IO errors are swallowed (the alternative is to refuse to start,
       |  // which is worse for trusted-deployment use).
       |  def _raftStatePath: java.nio.file.Path =
       |    val key = if _localNodeId.isEmpty then "default" else _localNodeId.replaceAll("[^A-Za-z0-9._-]", "_")
       |    java.nio.file.Paths.get(s".ssc-raft-state-$key.json")
       |  def _raftPersist(): Unit =
       |    try
       |      val voted = _raftVotedFor.replace("\\", "\\\\").replace("\"", "\\\"")
       |      val json  = "{\"currentTerm\":" + _raftCurrentTerm.toString + ",\"votedFor\":\"" + voted + "\"}"
       |      java.nio.file.Files.writeString(_raftStatePath, json,
       |        java.nio.charset.StandardCharsets.UTF_8,
       |        java.nio.file.StandardOpenOption.CREATE,
       |        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
       |    catch case _: Throwable => ()
       |  def _raftLoad(): Unit =
       |    try
       |      val p = _raftStatePath
       |      if java.nio.file.Files.exists(p) then
       |        val s = java.nio.file.Files.readString(p)
       |        val termIdx = s.indexOf("\"currentTerm\"")
       |        if termIdx >= 0 then
       |          val ci = s.indexOf(':', termIdx); var i = ci + 1
       |          while i < s.length && s(i) == ' ' do i += 1
       |          var j = i; while j < s.length && (s(j).isDigit || s(j) == '-') do j += 1
       |          if j > i then s.substring(i, j).toLongOption.foreach(t => _raftCurrentTerm = t)
       |        val vk = "\"votedFor\""
       |        val ki = s.indexOf(vk)
       |        if ki >= 0 then
       |          val qi = s.indexOf('"', ki + vk.length + 1)
       |          val qe = if qi > 0 then s.indexOf('"', qi + 1) else -1
       |          if qe > qi then _raftVotedFor = s.substring(qi + 1, qe)
       |    catch case _: Throwable => ()
       |  // v1.23 — drain-aware step-down (cluster-raft.md §7).  Called when
       |  // `setDraining(true)` flips while this node holds leadership.
       |  // Releases the lease (coord), reverts to follower (Raft), or just
       |  // clears the cached leader (Bully); always fires LeaderLost(self).
       |  def _stepDownIfLeader(): Unit =
       |    _leaderProtocol.get() match
       |      case "raft" =>
       |        if _raftState == "leader" then
       |          _raftState    = "follower"
       |          _raftLeaderId = ""
       |          val prev = _currentLeader.getAndSet("")
       |          if prev.nonEmpty then _fireLeaderEvent("LeaderLost", prev)
       |      case "coord" =>
       |        if _coordIsLeader then
       |          _coordIsLeader = false
       |          val rel = _coordReleaseFn
       |          if rel != null then
       |            try rel.asInstanceOf[String => Unit](_localNodeId)
       |            catch case _: Throwable => ()
       |          val prev = _currentLeader.getAndSet("")
       |          if prev.nonEmpty then _fireLeaderEvent("LeaderLost", prev)
       |      case _ =>
       |        if _currentLeader.compareAndSet(_localNodeId, "") then
       |          _fireLeaderEvent("LeaderLost", _localNodeId)
       |  // v1.23 — auto-reconnect: exponential-backoff retry per peer URL after a
       |  // disconnect.  Both fields 0 ⇒ disabled (default).  `setReconnectPolicy`
       |  // sets them at runtime.  `_reconnectGiveUpMs` caps the total
       |  // wall-clock retry budget per URL (0 = retry forever).
       |  @volatile var _reconnectInitialMs: Long = 0L
       |  @volatile var _reconnectMaxMs:     Long = 0L
       |  @volatile var _reconnectGiveUpMs:  Long = 0L
       |  // v1.23 — per-link heartbeat tuning.  Defaults 30s ping / 40s dead
       |  // match the pre-v1.23 hardcoded values; `setHeartbeatTimeout` tunes
       |  // them for low-latency / test clusters.
       |  @volatile var _peerHeartbeatIntervalMs:  Long = 30000L
       |  @volatile var _peerHeartbeatDeadAfterMs: Long = 40000L
       |  // v1.23 — quorum-aware Bully threshold.  0 = no quorum check;
       |  // set to N/2+1 of expected cluster size for split-brain guard.
       |  @volatile var _quorumSize: Long = 0L
       |  def _hasQuorum: Boolean = _quorumSize <= 0L || (_peerChannels.size + 1L) >= _quorumSize
       |  // v1.23 — cluster configuration distribution.  LWW per key by timestamp;
       |  // ties broken by lex-greatest nodeId so all nodes converge.
       |  val _clusterConfig    = new java.util.concurrent.ConcurrentHashMap[String, (String, Long, String)]()
       |  val _configEventSubs  = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
       |  val _configEventQueue = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()
       |  def _fireConfigEvent(key: String, value: String): Unit =
       |    val ts = System.currentTimeMillis()
       |    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":\"ConfigChanged\",\"key\":" +
       |                    _jstr(key) + ",\"value\":" + _jstr(value) + "}")
       |    if !_configEventSubs.isEmpty then _configEventQueue.offer((key, value))
       |  // Returns true if (ts, origin) wins over the stored (ts, origin) for key.
       |  def _applyConfigUpdate(key: String, value: String, ts: Long, origin: String): Boolean =
       |    val prev = _clusterConfig.get(key)
       |    val accept =
       |      prev == null || ts > prev._2 ||
       |      (ts == prev._2 && origin > prev._3)
       |    if accept then
       |      _clusterConfig.put(key, (value, ts, origin))
       |      _fireConfigEvent(key, value)
       |    accept
       |  // Snapshot every locally-known config entry to a single peer.  Called
       |  // on every successful handshake so late-joining nodes pick up entries
       |  // set before they joined.  LWW on the receiver protects us from
       |  // downgrading any value the new peer might already have.
       |  def _sendConfigSnapshot(targetSend: String => Unit): Unit =
       |    _clusterConfig.forEach { (key, tuple) =>
       |      val payload = "{\"t\":\"config_set\",\"key\":" + _jstr(key) +
       |                    ",\"value\":" + _jstr(tuple._1) +
       |                    ",\"ts\":" + tuple._2.toString +
       |                    ",\"origin\":" + _jstr(tuple._3) + "}"
       |      try targetSend(payload) catch case _: Throwable => ()
       |    }
       |  // v1.23 — drain / rolling-restart state
       |  val _isDrainingSelf  = new java.util.concurrent.atomic.AtomicBoolean(false)
       |  val _drainingPeers   = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()
       |  val _drainEventSubs  = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
       |  val _drainEventQueue = new java.util.concurrent.ConcurrentLinkedQueue[(String, Boolean)]()
       |  def _fireDrainEvent(nodeId: String, draining: Boolean): Unit =
       |    val ts = System.currentTimeMillis()
       |    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":\"DrainStateChanged\",\"nodeId\":" +
       |                    _jstr(nodeId) + ",\"draining\":" + draining.toString + "}")
       |    if !_drainEventSubs.isEmpty then _drainEventQueue.offer((nodeId, draining))
       |  // Tell a freshly-handshaken peer our current drain state.  No-op when we
       |  // are not draining (peers default-assume `false`).
       |  def _sendDrainState(targetSend: String => Unit): Unit =
       |    if _isDrainingSelf.get() then
       |      val payload = "{\"t\":\"drain\",\"from\":" + _jstr(_localNodeId) + ",\"draining\":true}"
       |      try targetSend(payload) catch case _: Throwable => ()
       |  // v1.23 — cluster metrics: per-node gauges.
       |  //   _clusterMetrics(name)(nodeId) = latest value
       |  val _clusterMetrics    = new java.util.concurrent.ConcurrentHashMap[String,
       |    java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]]()
       |  val _metricEventSubs   = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
       |  val _metricEventQueue  = new java.util.concurrent.ConcurrentLinkedQueue[(String, String, Double)]()
       |  def _fireMetricEvent(name: String, nodeId: String, value: Double): Unit =
       |    val ts = System.currentTimeMillis()
       |    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":\"MetricChanged\",\"name\":" +
       |                    _jstr(name) + ",\"nodeId\":" + _jstr(nodeId) +
       |                    ",\"value\":" + value.toString + "}")
       |    if !_metricEventSubs.isEmpty then _metricEventQueue.offer((name, nodeId, value))
       |  def _applyMetricUpdate(name: String, nodeId: String, value: Double): Unit =
       |    val inner = _clusterMetrics.computeIfAbsent(name, _ =>
       |      new java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]())
       |    val boxed = java.lang.Double.valueOf(value)
       |    val prev  = inner.put(nodeId, boxed)
       |    if prev == null || prev.doubleValue() != value then
       |      _fireMetricEvent(name, nodeId, value)
       |  // Snapshot every local metric to a single peer on handshake so late
       |  // joiners catch up without waiting for the next set.
       |  def _sendMetricSnapshot(targetSend: String => Unit): Unit =
       |    _clusterMetrics.forEach { (name, inner) =>
       |      val localVal = inner.get(_localNodeId)
       |      if localVal != null then
       |        val payload = "{\"t\":\"metric\",\"from\":" + _jstr(_localNodeId) +
       |                      ",\"name\":" + _jstr(name) +
       |                      ",\"value\":" + localVal.doubleValue().toString + "}"
       |        try targetSend(payload) catch case _: Throwable => ()
       |    }
       |""".stripMargin +
    """|  // v1.23 — Bearer-token gate shared by every /_ssc-cluster/* HTTP
       |  // route.  Returns Some(401-response) when the token is set and the
       |  // request's Authorization header doesn't carry `Bearer <token>`;
       |  // None when the token is empty (endpoints open) or matches.  Mirrors
       |  // `Interpreter.clusterAuthReject`.
       |  def _clusterAuthReject(req: Request): Option[Response] =
       |    val tok = _clusterAuthToken
       |    if tok.isEmpty then None
       |    else
       |      val hdr = req.headers.getOrElse("authorization", "")
       |      if hdr == ("Bearer " + tok) then None
       |      else Some(Response(
       |        401,
       |        Map("Content-Type" -> "application/json"),
       |        "{\"error\":\"unauthorized\",\"hint\":\"set Authorization: Bearer <token>\"}"))
       |  // v1.23 — `GET /_ssc-cluster/status` JSON snapshot of cluster state.
       |  // Idempotent: subsequent `startNode` calls are no-ops for the route
       |  // table.  Mirrors `Interpreter.registerClusterStatusRoute`.
       |  def _registerClusterStatusRoute(): Unit =
       |    val path = "/_ssc-cluster/status"
       |    if _routes.exists(r => r.method == "GET" && r.path == path) then return
       |    route("GET", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          val sb = new StringBuilder("{")
       |          def kv(k: String, jsonVal: String, first: Boolean = false): Unit =
       |            if !first then sb.append(',')
       |            sb.append('"').append(k).append("\":").append(jsonVal)
       |          def jsonStrArr(xs: Iterable[String]): String =
       |            xs.map(_jstr).mkString("[", ",", "]")
       |          val members = scala.collection.mutable.ListBuffer.empty[String]
       |          _peerChannels.keySet().forEach(p => members += p)
       |          val drainPeers = scala.collection.mutable.ListBuffer.empty[String]
       |          _drainingPeers.forEach { (nid, dr) =>
       |            if dr != null && dr.booleanValue() then drainPeers += nid
       |          }
       |          val leaderNow =
       |            _leaderProtocol.get() match
       |              case "raft" => _raftLeaderId
       |              case _      => _currentLeader.get()
       |          kv("nodeId",        _jstr(_localNodeId), first = true)
       |          kv("leader",        _jstr(leaderNow))
       |          kv("protocol",      _jstr(_leaderProtocol.get()))
       |          kv("members",       jsonStrArr(members.toList))
       |          kv("drainingSelf",  if _isDrainingSelf.get() then "true" else "false")
       |          kv("drainingPeers", jsonStrArr(drainPeers.toList))
       |          kv("raftTerm",      _raftCurrentTerm.toString)
       |          kv("raftState",     _jstr(_raftState))
       |          sb.append('}')
       |          Response(200, Map("Content-Type" -> "application/json"), sb.toString)
       |    }
       |  // v1.23 — `POST /_ssc-cluster/drain` toggles local drain state.
       |  // Body is JSON `{"enabled":true|false}` (empty body = enable).
       |  // Mirrors the in-process `setDraining` effect: flips
       |  // `_isDrainingSelf`, broadcasts DrainStateChanged to peers, steps
       |  // down if we were leader.  Used by `ssc cluster drain <url> [--off]`.
       |  def _registerClusterDrainRoute(): Unit =
       |    val path = "/_ssc-cluster/drain"
       |    if _routes.exists(r => r.method == "POST" && r.path == path) then return
       |    route("POST", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          val body = req.body
       |          val enabled: Boolean =
       |            if body.trim.isEmpty then true
       |            else
       |              val needle = "\"enabled\":"
       |              val i = body.indexOf(needle)
       |              if i < 0 then true
       |              else
       |                val rest = body.substring(i + needle.length).trim
       |                !rest.startsWith("false")
       |          val prev = _isDrainingSelf.getAndSet(enabled)
       |          if prev != enabled then
       |            val payload = "{\"t\":\"drain\",\"from\":" + _jstr(_localNodeId) +
       |                          ",\"draining\":" + enabled.toString + "}"
       |            _peerChannels.forEach { (_, send) =>
       |              try send(payload) catch case _: Throwable => ()
       |            }
       |            _fireDrainEvent(_localNodeId, enabled)
       |            if enabled then _stepDownIfLeader()
       |          Response(
       |            200,
       |            Map("Content-Type" -> "application/json"),
       |            "{\"drainingSelf\":" + (if enabled then "true" else "false") + "}")
       |    }
       |  // v1.23 — `GET /_ssc-cluster/events[?since=<ts>]` returns the bounded
       |  // ring buffer of recent cluster events as a JSON array.  Optional
       |  // `since` query filters to entries strictly newer than the given
       |  // epoch-ms.  Idempotent registration.
       |  def _registerClusterEventsRoute(): Unit =
       |    val path = "/_ssc-cluster/events"
       |    if _routes.exists(r => r.method == "GET" && r.path == path) then return
       |    route("GET", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          val sinceMs: Long =
       |            req.query.get("since").flatMap(_.toLongOption).getOrElse(0L)
       |          val sb = new StringBuilder("[")
       |          var first = true
       |          val it = _clusterEventLog.iterator()
       |          while it.hasNext do
       |            val line = it.next()
       |            val tsMatch =
       |              if sinceMs <= 0L then true
       |              else
       |                val tsPrefix = "{\"ts\":"
       |                if line.startsWith(tsPrefix) then
       |                  val end = line.indexOf(',', tsPrefix.length)
       |                  if end > 0 then
       |                    line.substring(tsPrefix.length, end).toLongOption
       |                      .exists(_ > sinceMs)
       |                  else false
       |                else false
       |            if tsMatch then
       |              if !first then sb.append(',')
       |              sb.append(line)
       |              first = false
       |          sb.append(']')
       |          Response(200, Map("Content-Type" -> "application/json"), sb.toString)
       |    }
       |  // v1.23 — `POST /_ssc-cluster/step-down`.  If this node is the current
       |  // leader, step down (clear `_currentLeader`, broadcast `LeaderLost`,
       |  // surrender any external coordinator lease).  If it's not the leader,
       |  // returns 409 Conflict so the operator notices.  Apps with
       |  // `setAutoReelect(true)` re-elect automatically — that's the rolling-
       |  // restart pattern.
       |  def _registerClusterStepDownRoute(): Unit =
       |    val path = "/_ssc-cluster/step-down"
       |    if _routes.exists(r => r.method == "POST" && r.path == path) then return
       |    route("POST", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          val wasLeader =
       |            _leaderProtocol.get() match
       |              case "raft"  => _raftState == "leader"
       |              case "coord" => _coordIsLeader
       |              case _       => _currentLeader.get() == _localNodeId
       |          if !wasLeader then
       |            Response(
       |              409,
       |              Map("Content-Type" -> "application/json"),
       |              "{\"error\":\"not_leader\",\"leader\":" + _jstr(_currentLeader.get()) + "}")
       |          else
       |            _stepDownIfLeader()
       |            Response(
       |              200,
       |              Map("Content-Type" -> "application/json"),
       |              "{\"steppedDown\":true,\"nodeId\":" + _jstr(_localNodeId) + "}")
       |    }
       |  // v1.23 — `GET /_ssc-cluster/metrics-prom` returns `_clusterMetrics`
       |  // gauges in Prometheus text exposition format.  One
       |  // `<sanitized-name>{nodeId="<id>"} <value>` line per (metric, peer)
       |  // pair, plus `# TYPE … gauge` declarations.  Same Bearer-token gate.
       |  def _registerClusterMetricsPromRoute(): Unit =
       |    val path = "/_ssc-cluster/metrics-prom"
       |    if _routes.exists(r => r.method == "GET" && r.path == path) then return
       |    route("GET", path) { req =>
       |      _clusterAuthReject(req) match
       |        case Some(r) => r
       |        case None =>
       |          // Prometheus metric names must match `[a-zA-Z_:][a-zA-Z0-9_:]*`.
       |          def sanitize(s: String): String =
       |            val sb = new StringBuilder(s.length)
       |            var i = 0
       |            while i < s.length do
       |              val c = s.charAt(i)
       |              val ok =
       |                (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
       |                (c >= '0' && c <= '9') || c == '_' || c == ':'
       |              sb.append(if ok then c else '_')
       |              i += 1
       |            val out = sb.toString
       |            if out.nonEmpty && out.charAt(0) >= '0' && out.charAt(0) <= '9'
       |            then "_" + out else out
       |          def escLabel(s: String): String =
       |            s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
       |          val sb = new StringBuilder()
       |          _clusterMetrics.forEach { (name, inner) =>
       |            val pName = sanitize(name)
       |            sb.append("# TYPE ").append(pName).append(" gauge\n")
       |            inner.forEach { (nodeId, value) =>
       |              sb.append(pName)
       |                .append("{nodeId=\"").append(escLabel(nodeId)).append("\"} ")
       |                .append(value.doubleValue())
       |                .append('\n')
       |            }
       |          }
       |          Response(
       |            200,
       |            Map("Content-Type" -> "text/plain; version=0.0.4; charset=utf-8"),
       |            sb.toString)
       |    }
       |""".stripMargin +
    """|  def _broadcastCoordinator(): Unit =
       |    val payload = "{\"t\":\"coordinator\",\"from\":" + _jstr(_localNodeId) + "}"
       |    _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |  def _startElection(): Unit =
       |    if _localNodeId.isEmpty then
       |      val prev = _currentLeader.getAndSet(_localNodeId)
       |      if prev != _localNodeId then { _fireLeaderEvent("LeaderElected", _localNodeId); _recordLeaderHist(_localNodeId) }
       |    else
       |      val higher = scala.collection.mutable.ListBuffer.empty[String]
       |      _peerChannels.keySet().forEach(nid => if nid > _localNodeId then higher += nid)
       |      if higher.isEmpty then
       |        // v1.23 — quorum gate: refuse self-claim when below quorum
       |        // (split-brain guard).  No-op when `_quorumSize = 0`.
       |        if !_hasQuorum then ()
       |        else
       |          val prev = _currentLeader.getAndSet(_localNodeId)
       |          _broadcastCoordinator()
       |          if prev != _localNodeId then
       |            _fireLeaderEvent("LeaderElected", _localNodeId)
       |            _recordLeaderHist(_localNodeId)
       |      else
       |        _electionInProgress = true
       |        _electionStartedAt  = System.currentTimeMillis()
       |        _gotAliveResponse   = false
       |        val payload = "{\"t\":\"election\",\"from\":" + _jstr(_localNodeId) + "}"
       |        higher.foreach { nid =>
       |          try Option(_peerChannels.get(nid)).foreach(_.apply(payload))
       |          catch case _: Throwable => ()
       |        }
       |  def _recordPongInterval(nid: String): Unit =
       |    val now  = System.currentTimeMillis()
       |    val last = _peerLastPong.getOrDefault(nid, 0L)
       |    if last > 0L then
       |      val delta = java.lang.Long.valueOf(now - last)
       |      val dq    = _peerPongHist.computeIfAbsent(nid, _ =>
       |        new java.util.concurrent.ConcurrentLinkedDeque[java.lang.Long]())
       |      dq.offer(delta)
       |      while dq.size() > _PHI_HIST_MAX do dq.pollFirst()
       |  def _computePhi(nid: String): Double =
       |    val hist = _peerPongHist.get(nid)
       |    if hist == null || hist.isEmpty then return Double.PositiveInfinity
       |    val n = hist.size
       |    var s = 0.0
       |    val it = hist.iterator
       |    while it.hasNext do s += it.next().longValue().toDouble
       |    val mean = s / n
       |    var sq = 0.0
       |    val it2 = hist.iterator
       |    while it2.hasNext do
       |      val d = it2.next().longValue().toDouble - mean
       |      sq += d * d
       |    val variance = if n > 1 then sq / (n - 1) else 1.0
       |    val stddev   = math.sqrt(variance).max(50.0)
       |    val now      = System.currentTimeMillis()
       |    val last     = _peerLastPong.getOrDefault(nid, now)
       |    val elapsed  = (now - last).toDouble
       |    if elapsed <= mean then 0.0
       |    else
       |      val z    = (elapsed - mean) / stddev
       |      val tail = math.exp(-z * z / 2.0) / (z * math.sqrt(2.0 * math.Pi))
       |      if tail <= 0.0 then Double.PositiveInfinity
       |      else -math.log10(tail.min(1.0))
       |
       |  val ready  = scala.collection.mutable.ArrayDeque.empty[Long]
       |  var nextId: Long = 0L
       |  var rootResult: Any = ()
       |
       |  def spawnActor(thunk: () => Any, cap: Int = 0, overflow: String = ""): Long =
       |    val id = nextId
       |    nextId += 1
       |    val st = new _ActorState
       |    st.pending = thunk()
       |    st.cap = cap
       |    st.overflow = overflow
       |    actors.put(id, st)
       |    ready.append(id)
       |    id
       |
       |  val rootId = spawnActor(bodyThunk)
       |
       |  def _fireNodeDown(nodeId: String): Unit =
       |    val noconn = "noconnection"
       |    val deadBase = Map("nodeId" -> nodeId)
       |    Option(_remoteMonitors.remove(nodeId)).foreach { list =>
       |      list.forEach { (actorId, monRef, rPidLocalId) =>
       |        actors.get(actorId).foreach { st =>
       |          st.mailbox.offer(Down(monRef, _Pid(nodeId, rPidLocalId), noconn))
       |          tryWakeBlocked(actorId)
       |        }
       |      }
       |    }
       |    Option(_remoteLinks.remove(nodeId)).foreach { list =>
       |      list.forEach { (actorId, rPidLocalId) =>
       |        if trapExitM.getOrElse(actorId, false) then
       |          actors.get(actorId).foreach { st =>
       |            st.mailbox.offer(Exit(_Pid(nodeId, rPidLocalId), noconn))
       |            tryWakeBlocked(actorId)
       |          }
       |        else killActor(actorId, noconn)
       |      }
       |    }
       |
       |  def _connectPeer(url: String, token: String): Unit =
       |    Thread.ofVirtual().start { () =>
       |      try
       |        import java.net.URI
       |        import java.net.http.{HttpClient => _JHC2, WebSocket => _JWs2}
       |        import java.util.concurrent.{LinkedBlockingQueue => _LBQ, CompletableFuture => _CF}
       |        val recvQ  = new _LBQ[String | Null]()
       |        val textB  = new StringBuilder()
       |        @volatile var _ws2: _JWs2 | Null = null
       |        val listener = new _JWs2.Listener:
       |          override def onText(ws: _JWs2, data: CharSequence, last: Boolean): _CF[?] =
       |            textB.append(data)
       |            if last then { val m = textB.toString(); textB.setLength(0); recvQ.offer(m) }
       |            ws.request(1); _CF.completedFuture(null)
       |          override def onClose(ws: _JWs2, c: Int, r: String): _CF[?] =
       |            recvQ.offer(null); _CF.completedFuture(null)
       |          override def onError(ws: _JWs2 | Null, e: Throwable): Unit =
       |            System.err.println("ssc-peer error [" + url + "]: " + e.getMessage); recvQ.offer(null)
       |        val hdrs = if token.nonEmpty then Map("Authorization" -> ("Bearer " + token)) else Map.empty[String,String]
       |        val builder = _JHC2.newHttpClient().newWebSocketBuilder()
       |        hdrs.foreach { case (k, v) => builder.header(k, v) }
       |        builder.subprotocols("ssc-actors-v1")
       |        val ws = builder.buildAsync(URI.create(url), listener).join()
       |        _ws2 = ws
       |        def sendFn(t: String): Unit = if _ws2 != null then _ws2.sendText(t, true)
       |        def recvFn(): String | Null  = recvQ.take()
       |        sendFn("{\"nodeId\":" + _jstr(_localNodeId) + "}")
       |        val first = recvFn()
       |        if first != null then
       |          val pnId = _parseNodeId(first)
       |          if pnId.nonEmpty then
       |            _peerUrls.put(pnId, url)
       |            _peerChannels.put(pnId, sendFn)
       |            _peerLastPong.put(pnId, System.currentTimeMillis())
       |            _fireClusterEvent("NodeJoined", pnId)
       |            if _joinMode then try sendFn("{\"t\":\"peers_req\",\"from\":" + _jstr(_localNodeId) + "}") catch case _: Throwable => ()
       |            // v1.23 — snapshot the cluster config to the new peer so it
       |            // sees entries set before it joined (LWW protects existing values).
       |            _sendConfigSnapshot(sendFn)
       |            _sendDrainState(sendFn)
       |            _sendMetricSnapshot(sendFn)
       |            val hbThread = Thread.ofVirtual().start { () =>
       |              try
       |                while _peerChannels.containsKey(pnId) do
       |                  Thread.sleep(_peerHeartbeatIntervalMs)
       |                  if _peerChannels.containsKey(pnId) then
       |                    val age = System.currentTimeMillis() - _peerLastPong.getOrDefault(pnId, 0L)
       |                    if age > _peerHeartbeatDeadAfterMs then
       |                      _peerChannels.remove(pnId)
       |                      try if _ws2 != null then _ws2.abort() catch case _: Throwable => ()
       |                    else try _peerChannels.get(pnId)("{\"t\":\"ping\"}") catch case _: Throwable => ()
       |              catch case _: InterruptedException => ()
       |            }
       |            var running = true
       |            while running do
       |              val msg = recvFn()
       |              if msg == null then running = false
       |              else _dispatchPeerEnv(pnId, msg)
       |            hbThread.interrupt()
       |            _peerChannels.remove(pnId)
       |            _peerLastPong.remove(pnId)
       |            _peerUrls.remove(pnId)
       |            _peerPongHist.remove(pnId)
       |            _peerPhiViews.remove(pnId)
       |            _drainingPeers.remove(pnId)
       |            _clusterMetrics.forEach { (_, inner) => inner.remove(pnId) }
       |            _nodeDownQueue.offer(pnId)
       |            _fireClusterEvent("NodeLeft", pnId, "disconnect")
       |            if _currentLeader.compareAndSet(pnId, "") then
       |              _fireLeaderEvent("LeaderLost", pnId)
       |              if _autoReelect then _startElection()
       |            // v1.23 — auto-reconnect: schedule exponential-backoff retries
       |            // for this URL until the peer reappears.
       |            if _reconnectInitialMs > 0L then _scheduleReconnect(url, token)
       |      catch case e: Throwable =>
       |        System.err.println("connectNode error [" + url + "]: " + e.getMessage)
       |        // v1.23 — schedule reconnect from the dial-failure path too.
       |        // Without this, non-seed nodes racing each other only ever
       |        // reach the seed and the cluster stays fragmented.
       |        if _reconnectInitialMs > 0L && !_peerUrls.containsValue(url) then
       |          _scheduleReconnect(url, token)
       |    }
       |
       |  // v1.23 — URL-keyed dedupe so concurrent peer-loss + dial-failure
       |  // events for the same URL don't each spin up an independent
       |  // exponential-backoff loop (FD exhaustion under sustained churn).
       |  // `lazy` is required: `killActor` is referenced earlier in the
       |  // emitted preamble (`_connectPeer`'s catch) than where it's
       |  // defined, and a regular val here would block the forward
       |  // reference per Scala's init-order rule.
       |  lazy val _reconnectActive =
       |    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
       |
       |  def _scheduleReconnect(rurl: String, rtok: String): Unit =
       |    if !_reconnectActive.add(rurl) then return
       |    Thread.ofVirtual().start { () =>
       |      val startedAt = System.currentTimeMillis()
       |      var delay = _reconnectInitialMs.max(1L)
       |      var done  = false
       |      try
       |        while !done && !_peerUrls.containsValue(rurl) do
       |          try Thread.sleep(delay) catch case _: InterruptedException => done = true
       |          if !done && _reconnectInitialMs <= 0L then done = true
       |          if !done && _peerUrls.containsValue(rurl) then done = true
       |          // v1.23 — give-up budget: stop retrying after the
       |          // configured wall-clock elapsed.  0 ⇒ retry forever.
       |          if !done && _reconnectGiveUpMs > 0L &&
       |             (System.currentTimeMillis() - startedAt) >= _reconnectGiveUpMs then
       |            done = true
       |          if !done then
       |            try _connectPeer(rurl, rtok) catch case _: Throwable => ()
       |            if _peerUrls.containsValue(rurl) then done = true
       |            else
       |              val cap = if _reconnectMaxMs > 0L then _reconnectMaxMs else delay
       |              delay = math.min(delay * 2L, cap.max(delay))
       |      catch case _: Throwable => ()
       |      finally _reconnectActive.remove(rurl)
       |    }
       |
       |  def _parseNodeId(json: String): String =
       |    val key = "\"nodeId\""
       |    val ki = json.indexOf(key); if ki < 0 then return ""
       |    val vi = json.indexOf('"', ki + key.length + 1); if vi < 0 then return ""
       |    val ve = json.indexOf('"', vi + 1); if ve < 0 then return ""
       |    json.substring(vi + 1, ve)
       |
       |  def _dispatchPeerEnv(pnId: String, json: String): Unit =
       |    val ti = json.indexOf("\"t\""); if ti < 0 then return
       |    val vi = json.indexOf('"', ti + 4); if vi < 0 then return
       |    val ve = json.indexOf('"', vi + 1); if ve < 0 then return
       |    val t  = json.substring(vi + 1, ve)
       |    t match
       |      case "msg" =>
       |        val toId = _extractToLocalId(json)
       |        if toId >= 0 then
       |          val body = _extractBody(json)
       |          if body != null then
       |            val msg = _deserializeValue(body)
       |            _remoteInbox.offer((toId, msg))
       |      case "ping" => try Option(_peerChannels.get(pnId)).foreach(_.apply("{\"t\":\"pong\"}")) catch case _: Throwable => ()
       |      case "pong" =>
       |        _recordPongInterval(pnId)
       |        _peerLastPong.put(pnId, System.currentTimeMillis())
       |      case "peers_req" =>
       |        val sb = new StringBuilder("{\"t\":\"peers_resp\",\"peers\":[")
       |        var first = true
       |        if _localNodeUrl.nonEmpty then
       |          sb.append("{\"nodeId\":" + _jstr(_localNodeId) + ",\"url\":" + _jstr(_localNodeUrl) + "}")
       |          first = false
       |        _peerUrls.forEach { (nid, u) =>
       |          if u.nonEmpty then
       |            if !first then sb.append(',')
       |            sb.append("{\"nodeId\":" + _jstr(nid) + ",\"url\":" + _jstr(u) + "}")
       |            first = false
       |        }
       |        sb.append("]}")
       |        try Option(_peerChannels.get(pnId)).foreach(_.apply(sb.toString)) catch case _: Throwable => ()
       |      case "peers_resp" =>
       |        _extractPeersList(json).foreach { case (pnid, purl) =>
       |          if pnid.nonEmpty && purl.nonEmpty && pnid != _localNodeId && !_peerChannels.containsKey(pnid) then
       |            _connectPeer(purl, _joinToken)
       |        }
       |      case "global_reg" =>
       |        val grName    = _extractJsonStr(json, "\"name\"")
       |        val grNodeId  = _extractJsonStr(json, "\"nodeId\"")
       |        val grLocalId = _extractJsonStr(json, "\"localId\"").toLongOption.getOrElse(0L)
       |        if grName.nonEmpty && grNodeId.nonEmpty then
       |          _globalRegistry.put(grName, _Pid(grNodeId, grLocalId))
       |      case "phi_vector" =>
       |        // v1.23 — peer's phi vector.  Parse out `from` and the `view`
       |        // pair list, replace our recorded view of that peer.
       |        val from = _extractJsonStr(json, "\"from\"")
       |        if from.nonEmpty then
       |          val pairs = _extractPhiView(json)
       |          val m = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]()
       |          pairs.foreach { case (nid, p) => m.put(nid, java.lang.Double.valueOf(p)) }
       |          _peerPhiViews.put(from, m)
       |      case "election" =>
       |        // v1.23 — Bully: lower-id peer is calling an election.  Respond
       |        // with `alive` (we're bigger) and start our own election.
       |        val from = _extractJsonStr(json, "\"from\"")
       |        if from.nonEmpty && from < _localNodeId then
       |          val reply = "{\"t\":\"alive\",\"from\":" + _jstr(_localNodeId) + "}"
       |          try Option(_peerChannels.get(from)).foreach(_.apply(reply))
       |          catch case _: Throwable => ()
       |          if !_electionInProgress then _startElection()
       |      case "alive" =>
       |        _gotAliveResponse = true
       |      case "coordinator" =>
       |        val from = _extractJsonStr(json, "\"from\"")
       |        if from.nonEmpty then
       |          val prev = _currentLeader.getAndSet(from)
       |          _electionInProgress = false
       |          if prev != from then
       |            _fireLeaderEvent("LeaderElected", from)
       |            _recordLeaderHist(from)
       |      case "config_set" =>
       |        // v1.23 — cluster config distribution.  LWW by (ts, originNodeId).
       |        val key   = _extractJsonStr(json, "\"key\"")
       |        val value = _extractJsonStr(json, "\"value\"")
       |        val orig  = _extractJsonStr(json, "\"origin\"")
       |        val ts    = _extractJsonLong(json, "\"ts\"")
       |        if key.nonEmpty then _applyConfigUpdate(key, value, ts, orig)
       |      case "drain" =>
       |        // v1.23 — peer announced its drain state.
       |        val from = _extractJsonStr(json, "\"from\"")
       |        if from.nonEmpty then
       |          val isDraining = json.contains("\"draining\":true")
       |          val prev = _drainingPeers.put(from, java.lang.Boolean.valueOf(isDraining))
       |          if prev == null || prev.booleanValue() != isDraining then
       |            _fireDrainEvent(from, isDraining)
       |      case "metric" =>
       |        val from  = _extractJsonStr(json, "\"from\"")
       |        val name  = _extractJsonStr(json, "\"name\"")
       |        val value = _extractJsonDouble(json, "\"value\"")
       |        if from.nonEmpty && name.nonEmpty then _applyMetricUpdate(name, from, value)
       |      // v1.23 — Raft RPCs (cluster-raft.md §4.2).
       |      case "raft_vote_req" =>
       |        val from = _extractJsonStr(json, "\"from\"")
       |        val term = _extractJsonLong(json, "\"term\"")
       |        if from.nonEmpty then
       |          var mutated = false
       |          val granted =
       |            if term < _raftCurrentTerm then false
       |            else
       |              if term > _raftCurrentTerm then
       |                _raftCurrentTerm = term
       |                _raftVotedFor    = ""
       |                _raftState       = "follower"
       |                mutated = true
       |              if _raftVotedFor.isEmpty || _raftVotedFor == from then
       |                _raftVotedFor    = from
       |                _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
       |                mutated = true
       |                true
       |              else false
       |          if mutated then _raftPersist()
       |          val reply = "{\"t\":\"raft_vote_resp\",\"from\":" + _jstr(_localNodeId) +
       |                      ",\"term\":" + _raftCurrentTerm.toString +
       |                      ",\"granted\":" + granted.toString + "}"
       |          try Option(_peerChannels.get(from)).foreach(_.apply(reply))
       |          catch case _: Throwable => ()
       |      case "raft_vote_resp" =>
       |        val term = _extractJsonLong(json, "\"term\"")
       |        val granted = json.contains("\"granted\":true")
       |        if term == _raftCurrentTerm && _raftState == "candidate" && granted then
       |          _raftVotes = _raftVotes + 1
       |          val total = _peerChannels.size() + 1
       |          if _raftVotes > total / 2 then
       |            _raftState    = "leader"
       |            _raftLeaderId = _localNodeId
       |            _raftAdoptLeader(_localNodeId)
       |            _raftBroadcastHeartbeat()
       |      case "raft_append" =>
       |        val from = _extractJsonStr(json, "\"from\"")
       |        val term = _extractJsonLong(json, "\"term\"")
       |        if from.nonEmpty && term >= _raftCurrentTerm then
       |          val termChanged = term > _raftCurrentTerm
       |          _raftCurrentTerm = term
       |          _raftState       = "follower"
       |          val prevLeader   = _raftLeaderId
       |          _raftLeaderId    = from
       |          _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
       |          if termChanged then _raftPersist()
       |          if prevLeader != from then _raftAdoptLeader(from)
       |      case _      => ()
       |
       |  def _extractJsonStr(json: String, key: String, fromIdx: Int = 0): String =
       |    val ki = json.indexOf(key, fromIdx); if ki < 0 then return ""
       |    val vi = json.indexOf('"', ki + key.length + 1); if vi < 0 then return ""
       |    val ve = json.indexOf('"', vi + 1); if ve < 0 then return ""
       |    json.substring(vi + 1, ve)
       |
       |  def _extractJsonLong(json: String, key: String): Long =
       |    val ki = json.indexOf(key); if ki < 0 then return 0L
       |    val ci = json.indexOf(':', ki + key.length); if ci < 0 then return 0L
       |    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |    var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
       |    if j > i then json.substring(i, j).toLongOption.getOrElse(0L) else 0L
       |
       |  def _extractJsonDouble(json: String, key: String): Double =
       |    val ki = json.indexOf(key); if ki < 0 then return 0.0
       |    val ci = json.indexOf(':', ki + key.length); if ci < 0 then return 0.0
       |    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |    var j = i
       |    while j < json.length && (json(j).isDigit || json(j) == '-' ||
       |                              json(j) == '.' || json(j) == 'e' || json(j) == 'E' ||
       |                              json(j) == '+') do j += 1
       |    if j > i then json.substring(i, j).toDoubleOption.getOrElse(0.0) else 0.0
       |
       |  def _extractPeersList(json: String): List[(String, String)] =
       |    val ak = "\"peers\""; val ai = json.indexOf(ak); if ai < 0 then return Nil
       |    val ab = json.indexOf('[', ai + ak.length); if ab < 0 then return Nil
       |    var ae = ab + 1; var depth = 1
       |    while ae < json.length && depth > 0 do
       |      if json(ae) == '[' then depth += 1
       |      else if json(ae) == ']' then depth -= 1
       |      ae += 1
       |    val arr = json.substring(ab + 1, ae - 1)
       |    val buf = scala.collection.mutable.ListBuffer.empty[(String, String)]
       |    var pos = 0
       |    while pos < arr.length do
       |      val ob = arr.indexOf('{', pos); if ob < 0 then pos = arr.length
       |      else
       |        var oe = ob + 1; var d2 = 1
       |        while oe < arr.length && d2 > 0 do
       |          if arr(oe) == '{' then d2 += 1
       |          else if arr(oe) == '}' then d2 -= 1
       |          oe += 1
       |        val obj = arr.substring(ob, oe)
       |        val nid = _extractJsonStr(obj, "\"nodeId\"")
       |        val url = _extractJsonStr(obj, "\"url\"")
       |        if nid.nonEmpty && url.nonEmpty then buf += ((nid, url))
       |        pos = oe
       |    buf.toList
       |
       |  // v1.23 — parse a `view` field of shape [["nodeA",0.5],["nodeB",2.3], ...]
       |  // from a phi_vector envelope.  Returns the inner pairs.
       |  def _extractPhiView(json: String): List[(String, Double)] =
       |    val key = "\"view\""; val ki = json.indexOf(key); if ki < 0 then return Nil
       |    val outer = json.indexOf('[', ki + key.length); if outer < 0 then return Nil
       |    var oe = outer + 1; var od = 1
       |    while oe < json.length && od > 0 do
       |      if json(oe) == '[' then od += 1
       |      else if json(oe) == ']' then od -= 1
       |      oe += 1
       |    val arr = json.substring(outer + 1, oe - 1)
       |    val buf = scala.collection.mutable.ListBuffer.empty[(String, Double)]
       |    var pos = 0
       |    while pos < arr.length do
       |      val ib = arr.indexOf('[', pos); if ib < 0 then pos = arr.length
       |      else
       |        var ie = ib + 1; var d2 = 1
       |        while ie < arr.length && d2 > 0 do
       |          if arr(ie) == '[' then d2 += 1
       |          else if arr(ie) == ']' then d2 -= 1
       |          ie += 1
       |        val inner = arr.substring(ib + 1, ie - 1).trim
       |        val nameEnd = inner.indexOf('"', 1)
       |        if inner.startsWith("\"") && nameEnd > 0 then
       |          val nm = inner.substring(1, nameEnd)
       |          val tail = inner.substring(nameEnd + 1).dropWhile(c => c == ',' || c == ' ').trim
       |          tail.toDoubleOption match
       |            case Some(d) => buf += ((nm, d))
       |            case None    => ()
       |        pos = ie
       |    buf.toList
       |
       |  def _extractToLocalId(json: String): Long =
       |    val toKey = "\"to\""; val ti = json.indexOf(toKey); if ti < 0 then return -1L
       |    val lk = "\"localId\""; val li = json.indexOf(lk, ti); if li < 0 then return -1L
       |    val ci = json.indexOf(':', li + lk.length); if ci < 0 then return -1L
       |    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |    var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
       |    if j > i then json.substring(i, j).toLongOption.getOrElse(-1L) else -1L
       |
       |  def _extractBody(json: String): String | Null =
       |    val bk = "\"body\""; val bi = json.indexOf(bk); if bi < 0 then return null
       |    val ci = json.indexOf(':', bi + bk.length); if ci < 0 then return null
       |    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |    if i >= json.length then return null
       |    // Body is a nested JSON object — find balanced {}
       |    if json(i) != '{' then return null
       |    var depth = 0; var j = i
       |    while j < json.length do
       |      if json(j) == '{' then depth += 1
       |      else if json(j) == '}' then { depth -= 1; if depth == 0 then return json.substring(i, j + 1) }
       |      j += 1
       |    null
       |
       |  def _deserializeValue(json: String): Any =
       |    val ti = json.indexOf("\"$t\""); if ti < 0 then return json
       |    val vi = json.indexOf('"', ti + 5); if vi < 0 then return json
       |    val ve = json.indexOf('"', vi + 1); if ve < 0 then return json
       |    val tag = json.substring(vi + 1, ve)
       |    tag match
       |      case "i" =>
       |        val nv = json.indexOf("\"v\""); val ci = json.indexOf(':', nv + 3)
       |        var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |        var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
       |        json.substring(i, j).toLongOption.getOrElse(0L)
       |      case "d" =>
       |        val nv = json.indexOf("\"v\""); val ci = json.indexOf(':', nv + 3)
       |        var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |        var j = i; while j < json.length && (json(j).isDigit || json(j) == '-' || json(j) == '.' || json(j) == 'e') do j += 1
       |        json.substring(i, j).toDoubleOption.getOrElse(0.0)
       |      case "s" =>
       |        val nv = json.indexOf("\"v\""); val qi = json.indexOf('"', json.indexOf(':', nv + 3) + 1)
       |        val qe = json.indexOf('"', qi + 1)
       |        if qi >= 0 && qe > qi then json.substring(qi + 1, qe) else ""
       |      case "b" =>
       |        json.contains("true")
       |      case "u" => ()
       |      case "pid" =>
       |        val ni = json.indexOf("\"n\""); val qi = json.indexOf('"', json.indexOf(':', ni + 3) + 1)
       |        val qe = json.indexOf('"', qi + 1); val nid = if qi >= 0 && qe > qi then json.substring(qi + 1, qe) else ""
       |        val li = json.indexOf("\"id\""); val ci = json.indexOf(':', li + 4)
       |        var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
       |        var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
       |        val lid = json.substring(i, j).toLongOption.getOrElse(0L)
       |        _Pid(nid, lid)
       |      case _ => json
       |
       |""".stripMargin +
    """|
       |  def _resumeBlockedSender(state: _ActorState): Unit =
       |    if state.cap <= 0 || state.blockedSends.isEmpty then return
       |    if state.mailbox.size >= state.cap then return
       |    while state.blockedSends.nonEmpty do
       |      val (senderId, msg, senderK) = state.blockedSends.removeHead()
       |      actors.get(senderId) match
       |        case Some(ss) if ss != null =>
       |          state.mailbox.offer(msg)
       |          // Defer senderK(()) via _FlatMap so the continuation's side
       |          // effects run in the sender's own stepActor turn, not in the
       |          // current actor's turn (ordering fix: Block overflow).
       |          ss.pending = _FlatMap((), senderK)
       |          ready.append(senderId)
       |          return
       |        case _ => ()  // dead sender — skip
       |
       |  def tryDeliver(state: _ActorState, matcher: Any => Option[Any], wrapSome: Boolean): Option[Any] =
       |    while !state.mailbox.isEmpty do
       |      val msg = state.mailbox.peek()
       |      matcher(msg) match
       |        case Some(bodyC) =>
       |          state.mailbox.poll()
       |          _resumeBlockedSender(state)
       |          if wrapSome then
       |            return Some(_FlatMap(bodyC, (v: Any) => Some(v)))
       |          else
       |            return Some(bodyC)
       |        case None =>
       |          state.mailbox.poll()
       |          _resumeBlockedSender(state)
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
       |    val _dyingSt = actors(targetId)
       |    actors.remove(targetId)
       |    trapExitM.remove(targetId)
       |    // Resume blocked senders: target died → send becomes silent no-op.
       |    if _dyingSt.blockedSends.nonEmpty then
       |      _dyingSt.blockedSends.foreach { (senderId, _, senderK) =>
       |        actors.get(senderId).foreach { ss =>
       |          ss.pending = _FlatMap((), senderK)
       |          ready.append(senderId)
       |        }
       |      }
       |      _dyingSt.blockedSends.clear()
       |    val deadPid = _Pid("", targetId)
       |    links.remove(targetId).foreach { linkedSet =>
       |      linkedSet.foreach { linkedId =>
       |        links.get(linkedId).foreach(_.remove(targetId))
       |        if trapExitM.getOrElse(linkedId, false) then
       |          actors.get(linkedId).foreach { st =>
       |            st.mailbox.offer(Exit(deadPid, reason))
       |            tryWakeBlocked(linkedId)
       |          }
       |        else
       |          killActor(linkedId, reason)
       |      }
       |    }
       |    monitors.remove(targetId).foreach { monMap =>
       |      monMap.foreach { (monRef, observerId) =>
       |        actors.get(observerId).foreach { st =>
       |          st.mailbox.offer(Down(monRef, deadPid, reason))
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
       |    case "spawnLink" =>
       |      val thunk = args(0).asInstanceOf[() => Any]
       |      val childId = spawnActor(thunk)
       |      // Atomic bidirectional link
       |      links.getOrElseUpdate(id,      scala.collection.mutable.Set.empty) += childId
       |      links.getOrElseUpdate(childId, scala.collection.mutable.Set.empty) += id
       |      Right(k(_Pid(_localNodeId, childId)))
       |    case "spawnBounded" =>
       |      val cap = args(0) match
       |        case n: Int  => n
       |        case n: Long => n.toInt
       |        case _       => 0
       |      val ov = args(1) match
       |        case s: String => s
       |        case m: scala.collection.immutable.Map[?, ?] =>
       |          m.asInstanceOf[Map[String, Any]].getOrElse("_type", "DropNewest").toString
       |        case _ => "DropNewest"
       |      val thunk = args(2).asInstanceOf[() => Any]
       |      val childId = spawnActor(thunk, cap, ov)
       |      Right(k(_Pid(_localNodeId, childId)))
       |    case "self" => Right(k(_Pid(_localNodeId, id)))
       |    case "processInfo" =>
       |      args(0) match
       |        case _Pid(_, targetId) =>
       |          actors.get(targetId) match
       |            case None => Right(k(None))
       |            case Some(ts) =>
       |              val lnks = links.get(targetId).map(_.toList.map(lid => _Pid("", lid))).getOrElse(List.empty)
       |              val status = if ts.blocked != null then "blocked" else "running"
       |              val info = Map("_type" -> "ProcessInfo", "mailboxSize" -> ts.mailbox.size,
       |                             "links" -> lnks, "status" -> status)
       |              Right(k(Some(info)))
       |        case _ => Right(k(None))
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
       |            actors.get(targetId) match
       |              case Some(ts) =>
       |                val _delivered =
       |                  if ts.cap > 0 && ts.mailbox.size >= ts.cap then
       |                    ts.overflow match
       |                      case "DropOldest" =>
       |                        ts.mailbox.poll(); ts.mailbox.offer(args(1)); true
       |                      case "DropNewest" => false
       |                      case "Fail" =>
       |                        killActor(id, "mailbox_overflow")
       |                        return if actors.contains(id) then Right(k(())) else Left(())
       |                      case "Block" =>
       |                        ts.blockedSends.append((id, args(1), k))
       |                        return Left(())
       |                      case _ => ts.mailbox.offer(args(1)); true
       |                  else { ts.mailbox.offer(args(1)); true }
       |                if _delivered && ts.blocked != null then
       |                  val b = ts.blocked
       |                  tryDeliver(ts, b._1, b._4) match
       |                    case Some(c) =>
       |                      ts.pending = _FlatMap(c, b._2)
       |                      ts.blocked = null
       |                      ready.append(targetId)
       |                    case None => ()
       |              case None => ()
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
       |        case _Pid(nid, targetId) =>
       |          if nid.nonEmpty && nid != _localNodeId then
       |            if _peerChannels.containsKey(nid) then
       |              _remoteLinks.computeIfAbsent(nid, _ => new java.util.concurrent.CopyOnWriteArrayList()).add((id, targetId))
       |            else
       |              if trapExitM.getOrElse(id, false) then
       |                actors.get(id).foreach(_.mailbox.offer(Exit(_Pid(nid, targetId), noproc)))
       |              else killActor(id, noproc)
       |          else if actors.contains(targetId) then
       |            links.getOrElseUpdate(id,       scala.collection.mutable.Set.empty) += targetId
       |            links.getOrElseUpdate(targetId, scala.collection.mutable.Set.empty) += id
       |          else
       |            if trapExitM.getOrElse(id, false) then
       |              actors.get(id).foreach(_.mailbox.offer(Exit(_Pid("", targetId), noproc)))
       |            else
       |              killActor(id, noproc)
       |        case _ => ()
       |      if actors.contains(id) then Right(k(())) else Left(())
       |    case "monitor" =>
       |      args(0) match
       |        case _Pid(nid, targetId) =>
       |          val monRef = nextMonRef; nextMonRef += 1
       |          if nid.nonEmpty && nid != _localNodeId then
       |            if _peerChannels.containsKey(nid) then
       |              _remoteMonitors.computeIfAbsent(nid, _ => new java.util.concurrent.CopyOnWriteArrayList()).add((id, monRef, targetId))
       |            else
       |              actors.get(id).foreach { st =>
       |                st.mailbox.offer(Down(monRef, _Pid(nid, targetId), "noconnection"))
       |                tryWakeBlocked(id)
       |              }
       |            Right(k(monRef))
       |          else if actors.contains(targetId) then
       |            monitors.getOrElseUpdate(targetId, scala.collection.mutable.Map.empty)(monRef) = id
       |            Right(k(monRef))
       |          else
       |            actors.get(id).foreach { st =>
       |              st.mailbox.offer(Down(monRef, _Pid("", targetId), noproc))
       |              tryWakeBlocked(id)
       |            }
       |            Right(k(monRef))
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
       |      _localNodeId  = args(0).toString
       |      _localNodeUrl = if args.length > 1 then args(1).toString else ""
       |      // Register /_ssc-actors WS route for inbound peer connections.
       |      // v1.23 — the blocking handshake + recv loop is wrapped in a
       |      // virtual thread so the WS server's single-thread dispatch
       |      // executor returns immediately and stays free to process the
       |      // next peer.  Without this the FIRST inbound peer's recv loop
       |      // monopolises the executor and subsequent handshakes stall
       |      // in the queue — fragmented clusters where every node only
       |      // sees the seed.
       |      onWebSocket("/_ssc-actors") { ws =>
       |        Thread.ofVirtual().start { () =>
       |          def wsSend(t: String): Unit = ws.send(t)
       |          def wsRecv(): String | Null  = ws.recv() match
       |            case Some(s) => s
       |            case None    => null
       |          val first = wsRecv()
       |          if first != null then
       |            val pnId = _parseNodeId(first)
       |            if pnId.nonEmpty then
       |              wsSend("{\"nodeId\":" + _jstr(_localNodeId) + "}")
       |              _peerChannels.put(pnId, wsSend)
       |              _peerLastPong.put(pnId, System.currentTimeMillis())
       |              _fireClusterEvent("NodeJoined", pnId)
       |              _sendConfigSnapshot(wsSend)
       |              _sendDrainState(wsSend)
       |              _sendMetricSnapshot(wsSend)
       |              val hbThread = Thread.ofVirtual().start { () =>
       |                try
       |                  while _peerChannels.containsKey(pnId) do
       |                    Thread.sleep(_peerHeartbeatIntervalMs)
       |                    if _peerChannels.containsKey(pnId) then
       |                      val age = System.currentTimeMillis() - _peerLastPong.getOrDefault(pnId, 0L)
       |                      if age > _peerHeartbeatDeadAfterMs then _peerChannels.remove(pnId)
       |                      else try _peerChannels.get(pnId)("{\"t\":\"ping\"}") catch case _: Throwable => ()
       |                catch case _: InterruptedException => ()
       |              }
       |              var running = true
       |              while running do
       |                val msg = wsRecv()
       |                if msg == null then running = false else _dispatchPeerEnv(pnId, msg)
       |              hbThread.interrupt()
       |              _peerChannels.remove(pnId)
       |              _peerLastPong.remove(pnId)
       |              _peerUrls.remove(pnId)
       |              _peerPongHist.remove(pnId)
       |              _peerPhiViews.remove(pnId)
       |              _drainingPeers.remove(pnId)
       |              _clusterMetrics.forEach { (_, inner) => inner.remove(pnId) }
       |              _nodeDownQueue.offer(pnId)
       |              _fireClusterEvent("NodeLeft", pnId, "disconnect")
       |              if _currentLeader.compareAndSet(pnId, "") then
       |                _fireLeaderEvent("LeaderLost", pnId)
       |                if _autoReelect then _startElection()
       |        }
       |      }
       |      // v1.23 — operational HTTP endpoints under /_ssc-cluster/* so
       |      // ops tooling (`ssc cluster status / drain / events / step-down
       |      // / metrics-prom`) can talk to codegen-built nodes just like
       |      // interpreter nodes.  Idempotent — repeated startNode calls
       |      // are no-ops for the route table.
       |      _registerClusterStatusRoute()
       |      _registerClusterDrainRoute()
       |      _registerClusterEventsRoute()
       |      _registerClusterStepDownRoute()
       |      _registerClusterMetricsPromRoute()
       |      Right(k(()))
       |    case "connectNode" =>
       |      val url = args(0).toString
       |      val tok = if args.length > 1 then args(1).toString else ""
       |      _connectPeer(url, tok)
       |      Right(k(()))
       |    case "joinCluster" =>
       |      _joinMode  = true
       |      _joinToken = if args.length > 1 then args(1).toString else ""
       |      val seeds = args(0) match
       |        case lst: List[?] => lst.collect { case s: String => s }
       |        case _            => Nil
       |      seeds.foreach(u => _connectPeer(u, _joinToken))
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
       |    // v1.6.x — cluster-wide registry
       |    case "globalRegister" =>
       |      val grName    = args(0).toString
       |      val grPidRaw  = args(1).asInstanceOf[_Pid]
       |      // v1.23 — local-spawn Pids carry an empty nodeId.  Stamp the
       |      // local node identity onto the registered Pid so cross-node
       |      // lookups can route back here; without this the broadcast
       |      // payload's `nodeId` is "" and remote nodes silently drop
       |      // every cross-node send to this name.
       |      val grNid     = if grPidRaw.nodeId.nonEmpty then grPidRaw.nodeId else _localNodeId
       |      val grPid     = _Pid(grNid, grPidRaw.localId)
       |      _globalRegistry.put(grName, grPid)
       |      val payload = "{\"t\":\"global_reg\",\"name\":" + _jstr(grName) + ",\"nodeId\":" + _jstr(grNid) + ",\"localId\":" + _jstr(grPid.localId.toString) + "}"
       |      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |      Right(k(()))
       |    case "globalWhereis" =>
       |      val gwName = args(0).toString
       |      val result = Option(_globalRegistry.get(gwName))
       |      Right(k(result))
       |    // v1.23 — cluster visibility
       |    case "clusterMembers" =>
       |      val buf = scala.collection.mutable.ListBuffer.empty[String]
       |      _peerChannels.keySet().forEach(k0 => buf += k0)
       |      Right(k(buf.toList))
       |    case "subscribeClusterEvents" =>
       |      val boxed = java.lang.Long.valueOf(id)
       |      if !_clusterEventSubs.contains(boxed) then _clusterEventSubs.add(boxed)
       |      Right(k(()))
       |    // v1.23 — phi-accrual failure detector
       |    case "phiOf" =>
       |      Right(k(_computePhi(args(0).toString)))
       |    case "isSuspect" =>
       |      val thr = args(1) match
       |        case d: Double => d
       |        case l: Long   => l.toDouble
       |        case i: Int    => i.toDouble
       |        case _         => 8.0
       |      Right(k(_computePhi(args(0).toString) >= thr))
       |    // v1.23 — local node identity
       |    case "selfNode" =>
       |      Right(k(_localNodeId))
       |    // v1.23 — cluster health (phi vector for connected peers)
       |    case "clusterHealth" =>
       |      val m = scala.collection.mutable.Map.empty[String, Double]
       |      _peerChannels.keySet().forEach(k0 => m(k0) = _computePhi(k0))
       |      Right(k(m.toMap))
       |    // v1.23 — cluster-wide FD: broadcast phi vector to peers.
       |    case "broadcastHealth" =>
       |      val sb = new StringBuilder("{\"t\":\"phi_vector\",\"from\":")
       |      sb.append(_jstr(_localNodeId)).append(",\"view\":[")
       |      var first = true
       |      _peerChannels.keySet().forEach { nid =>
       |        val phi = _computePhi(nid)
       |        if !phi.isInfinite && !phi.isNaN then
       |          if !first then sb.append(',')
       |          sb.append("[").append(_jstr(nid)).append(',').append(phi).append(']')
       |          first = false
       |      }
       |      sb.append("]}")
       |      val payload = sb.toString
       |      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |      Right(k(()))
       |    // v1.23 — cluster-wide FD: majority vote across peer views.
       |    case "clusterIsDown" =>
       |      val target = args(0).toString
       |      val thr    = args(1) match
       |        case d: Double => d
       |        case l: Long   => l.toDouble
       |        case i: Int    => i.toDouble
       |        case _         => 8.0
       |      var votes = 0
       |      var total = 0
       |      if _peerChannels.containsKey(target) then
       |        total += 1
       |        if _computePhi(target) >= thr then votes += 1
       |      _peerPhiViews.forEach { (peerNid, peerView) =>
       |        if peerNid != target then
       |          val p = peerView.get(target)
       |          if p != null then
       |            total += 1
       |            if p.doubleValue() >= thr then votes += 1
       |      }
       |      val majority = (total + 1) / 2
       |      Right(k(total > 0 && votes >= majority))
       |    // v1.23 — leader election (Bully or Raft, picked by _leaderProtocol)
       |    case "electLeader" =>
       |      _leaderProtocol.get() match
       |        case "raft" => _startRaftElection()
       |        case _      => _startElection()
       |      Right(k(()))
       |    case "currentLeader" =>
       |      _leaderProtocol.get() match
       |        case "raft"  => Right(k(_raftLeaderId))
       |        case "coord" =>
       |          val holderFn = _coordHolderFn
       |          val held: Option[String] =
       |            if holderFn != null then
       |              try holderFn.asInstanceOf[() => Option[String]]()
       |              catch case _: Throwable => None
       |            else None
       |          Right(k(held.getOrElse("")))
       |        case _       => Right(k(_currentLeader.get()))
       |    case "subscribeLeaderEvents" =>
       |      val boxed = java.lang.Long.valueOf(id)
       |      if !_leaderEventSubs.contains(boxed) then _leaderEventSubs.add(boxed)
       |      Right(k(()))
       |    case "setAutoReelect" =>
       |      _autoReelect = args(0) match
       |        case b: Boolean => b
       |        case _          => false
       |      Right(k(()))
       |    // v1.23 — protocol switch + history (cluster-raft.md §6).
       |    case "useRaftLeaderElection" =>
       |      _leaderProtocol.set("raft")
       |      _raftLoad()
       |      _raftState       = "follower"
       |      _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
       |      _ensureRaftTickThread()
       |      Right(k(()))
       |    case "useExternalCoordinator" =>
       |      _leaderProtocol.set("coord")
       |      if args.length >= 4 then
       |        _coordAcquireFn = args(0).asInstanceOf[AnyRef]
       |        _coordRenewFn   = args(1).asInstanceOf[AnyRef]
       |        _coordReleaseFn = args(2).asInstanceOf[AnyRef]
       |        _coordHolderFn  = args(3).asInstanceOf[AnyRef]
       |        // Try once synchronously so callers don't wait a tick.
       |        val got = try _coordAcquireFn.asInstanceOf[(String, Long) => Boolean](_localNodeId, _COORD_LEASE_TIMEOUT_MS)
       |                  catch case _: Throwable => false
       |        if got then
       |          _coordIsLeader = true
       |          val prev = _currentLeader.getAndSet(_localNodeId)
       |          if prev != _localNodeId then
       |            _fireLeaderEvent("LeaderElected", _localNodeId)
       |            _recordLeaderHist(_localNodeId)
       |        _ensureCoordTickThread()
       |      Right(k(()))
       |    case "leaderProtocol" =>
       |      Right(k(_leaderProtocol.get()))
       |    case "leaderHistory" =>
       |      val buf = scala.collection.mutable.ListBuffer.empty[(Long, String, Long)]
       |      _leaderHist.iterator().forEachRemaining(e => buf += e)
       |      Right(k(buf.toList))
       |    // v1.23 — auto-reconnect policy
       |    case "setReconnectPolicy" =>
       |      def _argL(i: Int): Long = if args.length > i then args(i) match
       |        case l: Long   => l
       |        case i2: Int   => i2.toLong
       |        case d: Double => d.toLong
       |        case _         => 0L
       |      else 0L
       |      _reconnectInitialMs = _argL(0).max(0L)
       |      _reconnectMaxMs     = _argL(1).max(_reconnectInitialMs)
       |      // v1.23 — optional 3rd arg: total wall-clock retry budget (ms)
       |      // per URL; 0 = no cap (retry forever).
       |      _reconnectGiveUpMs  = _argL(2).max(0L)
       |      Right(k(()))
       |    // v1.23 — heartbeat cadence + dead-after threshold
       |    case "setHeartbeatTimeout" =>
       |      val iv = args(0) match
       |        case l: Long   => l
       |        case i: Int    => i.toLong
       |        case d: Double => d.toLong
       |        case _         => 30000L
       |      val dead = args(1) match
       |        case l: Long   => l
       |        case i: Int    => i.toLong
       |        case d: Double => d.toLong
       |        case _         => 40000L
       |      _peerHeartbeatIntervalMs  = iv.max(1L)
       |      _peerHeartbeatDeadAfterMs = dead.max(_peerHeartbeatIntervalMs)
       |      Right(k(()))
       |    // v1.23 — quorum-aware Bully threshold
       |    case "setQuorumSize" =>
       |      val n = args(0) match
       |        case l: Long   => l
       |        case i: Int    => i.toLong
       |        case d: Double => d.toLong
       |        case _         => 0L
       |      _quorumSize = n.max(0L)
       |      Right(k(()))
       |    // v1.23 — periodic gossip re-discovery: ask every connected peer
       |    // for their peer-URL list.  Replies come back via the existing
       |    // `peers_resp` handler and feed `_connectPeer` for unknown URLs.
       |    case "requestGossip" =>
       |      val payload = "{\"t\":\"peers_req\",\"from\":" + _jstr(_localNodeId) + "}"
       |      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |      Right(k(()))
       |    // v1.23 — cluster configuration distribution.
       |    case "clusterConfigSet" =>
       |      val key   = args(0).toString
       |      val value = args(1).toString
       |      val ts    = System.currentTimeMillis()
       |      val orig  = _localNodeId
       |      _applyConfigUpdate(key, value, ts, orig)
       |      val payload = "{\"t\":\"config_set\",\"key\":" + _jstr(key) +
       |                    ",\"value\":" + _jstr(value) +
       |                    ",\"ts\":" + ts.toString +
       |                    ",\"origin\":" + _jstr(orig) + "}"
       |      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |      Right(k(()))
       |    case "clusterConfigGet" =>
       |      val key = args(0).toString
       |      val entry = _clusterConfig.get(key)
       |      val result: Any = if entry == null then None else Some(entry._1)
       |      Right(k(result))
       |    case "clusterConfigKeys" =>
       |      val buf = scala.collection.mutable.ListBuffer.empty[String]
       |      _clusterConfig.keySet().forEach(k0 => buf += k0)
       |      Right(k(buf.toList))
       |    case "subscribeConfigEvents" =>
       |      val boxed = java.lang.Long.valueOf(id)
       |      if !_configEventSubs.contains(boxed) then _configEventSubs.add(boxed)
       |      Right(k(()))
       |    // v1.23 — drain / rolling-restart
       |    case "setDraining" =>
       |      val b = args(0) match
       |        case bb: Boolean => bb
       |        case _           => false
       |      val prev = _isDrainingSelf.getAndSet(b)
       |      if prev != b then
       |        val payload = "{\"t\":\"drain\",\"from\":" + _jstr(_localNodeId) +
       |                      ",\"draining\":" + b.toString + "}"
       |        _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |        _fireDrainEvent(_localNodeId, b)
       |        // v1.23 — drain-aware step-down: if we just flipped to
       |        // draining and we're the leader, release leadership.
       |        if b then _stepDownIfLeader()
       |      Right(k(()))
       |    case "isDraining" =>
       |      Right(k(_isDrainingSelf.get()))
       |    case "drainingPeers" =>
       |      val buf = scala.collection.mutable.ListBuffer.empty[String]
       |      _drainingPeers.forEach { (nid, v) => if v != null && v.booleanValue() then buf += nid }
       |      Right(k(buf.toList))
       |    case "subscribeDrainEvents" =>
       |      val boxed = java.lang.Long.valueOf(id)
       |      if !_drainEventSubs.contains(boxed) then _drainEventSubs.add(boxed)
       |      Right(k(()))
       |    // v1.23 — cluster metrics aggregation
       |    case "clusterMetricSet" =>
       |      val name = args(0).toString
       |      val value = args(1) match
       |        case d: Double => d
       |        case l: Long   => l.toDouble
       |        case i: Int    => i.toDouble
       |        case _         => 0.0
       |      _applyMetricUpdate(name, _localNodeId, value)
       |      val payload = "{\"t\":\"metric\",\"from\":" + _jstr(_localNodeId) +
       |                    ",\"name\":" + _jstr(name) +
       |                    ",\"value\":" + value.toString + "}"
       |      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
       |      Right(k(()))
       |    case "clusterMetricGet" =>
       |      val name = args(0).toString
       |      val inner = _clusterMetrics.get(name)
       |      val m = scala.collection.mutable.Map.empty[String, Double]
       |      if inner != null then
       |        inner.forEach { (nid, v) => m(nid) = v.doubleValue() }
       |      Right(k(m.toMap))
       |    case "clusterMetricSum" =>
       |      val name = args(0).toString
       |      val inner = _clusterMetrics.get(name)
       |      var sum = 0.0
       |      if inner != null then
       |        inner.forEach { (_, v) => sum += v.doubleValue() }
       |      Right(k(sum))
       |    case "clusterMetricNames" =>
       |      val buf = scala.collection.mutable.ListBuffer.empty[String]
       |      _clusterMetrics.keySet().forEach(s => buf += s)
       |      Right(k(buf.toList))
       |    case "subscribeMetricEvents" =>
       |      val boxed = java.lang.Long.valueOf(id)
       |      if !_metricEventSubs.contains(boxed) then _metricEventSubs.add(boxed)
       |      Right(k(()))
       |    // v1.6.x — scheduled sends
       |    case "sendAfter" =>
       |      val delayMs  = args(0).asInstanceOf[Long]
       |      val targetId = args(1).asInstanceOf[_Pid].localId
       |      val msg      = args(2)
       |      val fireAt   = System.currentTimeMillis() + delayMs
       |      val ref      = _nextTimerId; _nextTimerId += 1
       |      _timers(ref) = (fireAt, None, targetId, msg)
       |      Right(k(ref))
       |    case "sendInterval" =>
       |      val periodMs = args(0).asInstanceOf[Long]
       |      val targetId = args(1).asInstanceOf[_Pid].localId
       |      val msg      = args(2)
       |      val fireAt   = System.currentTimeMillis() + periodMs
       |      val ref      = _nextTimerId; _nextTimerId += 1
       |      _timers(ref) = (fireAt, Some(periodMs), targetId, msg)
       |      Right(k(ref))
       |    case "cancelTimer" =>
       |      _timers.remove(args(0).asInstanceOf[Long])
       |      Right(k(()))
       |    case other => sys.error("Unknown Actor op: " + other)
       |
       |  // Synchronous fallback handler for non-Actor effects performed
       |  // inside an actor body — dep code like std/mapreduce/distributed
       |  // calls `Random.uuid()` while running under `runActors`, and the
       |  // value-producing primitives (Random.*, Clock.now/nowIso) can be
       |  // evaluated in-place without a continuation.  Unsupported effects
       |  // still throw with a clear message.  Blocking ops (Clock.sleep)
       |  // intentionally don't appear here — they'd freeze the single
       |  // actor scheduler thread.
       |  lazy val _actorRng = new java.util.Random()
       |  def _actorFallback(eff: String, op: String, args: List[Any]): Any =
       |    (eff, op) match
       |      case ("Random", "uuid") =>
       |        val bytes = new Array[Byte](16)
       |        _actorRng.nextBytes(bytes)
       |        bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
       |        bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
       |        def hex(b: Byte) = f"${b & 0xff}%02x"
       |        val u = bytes.map(hex).mkString
       |        s"${u.take(8)}-${u.slice(8,12)}-${u.slice(12,16)}-${u.slice(16,20)}-${u.drop(20)}"
       |      case ("Random", "nextInt") =>
       |        val n = args(0) match { case x: Int => x; case x: Long => x.toInt; case _ => 1 }
       |        _actorRng.nextInt(if n > 0 then n else 1)
       |      case ("Random", "nextDouble") => _actorRng.nextDouble()
       |      case ("Random", "pick") =>
       |        val xs = args(0).asInstanceOf[List[Any]]
       |        xs(_actorRng.nextInt(xs.size))
       |      case ("Clock", "now")    => java.lang.System.currentTimeMillis()
       |      case ("Clock", "nowIso") =>
       |        java.time.format.DateTimeFormatter.ISO_INSTANT
       |          .format(java.time.Instant.ofEpochMilli(java.lang.System.currentTimeMillis()))
       |      case _ =>
       |        throw new RuntimeException("Unhandled effect inside actor: " + eff + "." + op)
       |
       |  def stepActor(id: Long, initial: Any): Unit =
       |    var current: Any = initial
       |    while true do
       |      current match
       |        case _Perform("Actor", op, args) =>
       |          handleActorOp(id, actors(id), op, args, (v: Any) => v) match
       |            case Right(next) => current = next
       |            case Left(_)     => return
       |        case _Perform(eff, op, args) =>
       |          // Plain perform with no continuation: compute via
       |          // fallback and use the value as the final result of
       |          // this actor step.
       |          current = _actorFallback(eff, op, args)
       |        case _FlatMap(sub, f) => sub match
       |          case _Perform("Actor", op, args) =>
       |            handleActorOp(id, actors(id), op, args, f.asInstanceOf[Any => Any]) match
       |              case Right(next) => current = next
       |              case Left(_)     => return
       |          case _Perform(eff, op, args) =>
       |            val v = _actorFallback(eff, op, args)
       |            current = f.asInstanceOf[Any => Any](v)
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
       |                st.mailbox.offer(Down(monRef, myPid, "normal"))
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
       |  val _isDistributed = _localNodeId.nonEmpty || !_peerChannels.isEmpty
       |
       |  while ready.nonEmpty ||
       |        !_nodeDownQueue.isEmpty ||
       |        !_clusterEventQueue.isEmpty ||
       |        !_leaderEventQueue.isEmpty ||
       |        !_configEventQueue.isEmpty ||
       |        !_drainEventQueue.isEmpty ||
       |        !_metricEventQueue.isEmpty ||
       |        _electionInProgress ||
       |        _timers.nonEmpty ||
       |        actors.exists { (_, st) => st != null && st.blocked != null && st.blocked._3.isDefined } ||
       |        (_isDistributed && actors.nonEmpty && actors.exists { (_, st) => st != null && st.blocked != null })
       |  do
       |    // Drain remote inbox
       |    while !_remoteInbox.isEmpty do
       |      val (targetId, msg) = _remoteInbox.poll()
       |      actors.get(targetId).foreach { ts =>
       |        ts.mailbox.offer(msg)
       |        tryWakeBlocked(targetId)
       |      }
       |    // Drain node-down notifications
       |    while !_nodeDownQueue.isEmpty do
       |      _fireNodeDown(_nodeDownQueue.poll())
       |    // v1.23 — deliver cluster events to subscribers.
       |    while !_clusterEventQueue.isEmpty do
       |      val (_tag, _nid, _reason) = _clusterEventQueue.poll()
       |      val _msg: Any =
       |        if _tag == "NodeJoined" then NodeJoined(_nid)
       |        else NodeLeft(_nid, _reason)
       |      val _it = _clusterEventSubs.iterator
       |      while _it.hasNext do
       |        val _aid = _it.next().longValue()
       |        actors.get(_aid).foreach { ts =>
       |          ts.mailbox.offer(_msg)
       |          tryWakeBlocked(_aid)
       |        }
       |    // v1.23 — Bully election timeout: claim self if no higher-id peer
       |    // responded with `alive` within the window.
       |    if _electionInProgress && System.currentTimeMillis() - _electionStartedAt >= _ELECTION_TIMEOUT_MS then
       |      _electionInProgress = false
       |      // v1.23 — quorum gate: same as `_startElection.higher.isEmpty`
       |      // branch — even though no higher peer responded, decline to
       |      // self-claim when below quorum.
       |      if !_gotAliveResponse && _hasQuorum then
       |        val _prev = _currentLeader.getAndSet(_localNodeId)
       |        _broadcastCoordinator()
       |        if _prev != _localNodeId then
       |          _fireLeaderEvent("LeaderElected", _localNodeId)
       |          _recordLeaderHist(_localNodeId)
       |    // v1.23 — deliver leader events to subscribers.
       |    while !_leaderEventQueue.isEmpty do
       |      val (_tag, _lid) = _leaderEventQueue.poll()
       |      val _msg: Any =
       |        if _tag == "LeaderElected" then LeaderElected(_lid)
       |        else LeaderLost(_lid)
       |      val _it = _leaderEventSubs.iterator
       |      while _it.hasNext do
       |        val _aid = _it.next().longValue()
       |        actors.get(_aid).foreach { ts =>
       |          ts.mailbox.offer(_msg)
       |          tryWakeBlocked(_aid)
       |        }
       |    // v1.23 — deliver config-change events to subscribers.
       |    while !_configEventQueue.isEmpty do
       |      val (_key, _val) = _configEventQueue.poll()
       |      val _msg: Any = ConfigChanged(_key, _val)
       |      val _it = _configEventSubs.iterator
       |      while _it.hasNext do
       |        val _aid = _it.next().longValue()
       |        actors.get(_aid).foreach { ts =>
       |          ts.mailbox.offer(_msg)
       |          tryWakeBlocked(_aid)
       |        }
       |    // v1.23 — deliver drain-state events to subscribers.
       |    while !_drainEventQueue.isEmpty do
       |      val (_nid, _drn) = _drainEventQueue.poll()
       |      val _msg: Any = DrainStateChanged(_nid, _drn)
       |      val _it = _drainEventSubs.iterator
       |      while _it.hasNext do
       |        val _aid = _it.next().longValue()
       |        actors.get(_aid).foreach { ts =>
       |          ts.mailbox.offer(_msg)
       |          tryWakeBlocked(_aid)
       |        }
       |    // v1.23 — deliver metric events to subscribers.
       |    while !_metricEventQueue.isEmpty do
       |      val (_nm, _nid, _val) = _metricEventQueue.poll()
       |      val _msg: Any = MetricChanged(_nm, _nid, _val)
       |      val _it = _metricEventSubs.iterator
       |      while _it.hasNext do
       |        val _aid = _it.next().longValue()
       |        actors.get(_aid).foreach { ts =>
       |          ts.mailbox.offer(_msg)
       |          tryWakeBlocked(_aid)
       |        }
       |    // Fire scheduled sends whose deadline has passed.
       |    if _timers.nonEmpty then
       |      val _nowMs = System.currentTimeMillis()
       |      val _firedRefs = _timers.collect { case (r, (fa, _, _, _)) if _nowMs >= fa => r }.toList
       |      for _ref <- _firedRefs; _entry <- _timers.get(_ref) do
       |        val (fireAt, period, targetId, msg) = _entry
       |        actors.get(targetId).foreach { ts =>
       |          ts.mailbox.offer(msg)
       |          tryWakeBlocked(targetId)
       |        }
       |        period match
       |          case Some(p) => _timers(_ref) = (fireAt + p, period, targetId, msg)
       |          case None    => _timers.remove(_ref)
       |    if ready.isEmpty then
       |      val _blockDeadline = actors.iterator.collect {
       |        case (aid, st) if st != null && st.blocked != null && st.blocked._3.isDefined =>
       |          (aid, st.blocked._3.get)
       |      }.toList.minByOption(_._2)
       |      val _timerDeadline = if _timers.isEmpty then None else Some(_timers.values.map(_._1).min)
       |      val _sleepUntil = List(_blockDeadline.map(_._2), _timerDeadline).flatten.minOption
       |      val _sleepFor   = _sleepUntil.map(_ - System.currentTimeMillis()).getOrElse(if _isDistributed then 30L else Long.MaxValue)
       |      if _sleepFor > 0 then
       |        try Thread.sleep(_sleepFor)
       |        catch case _: InterruptedException => ()
       |      _blockDeadline match
       |        case Some((aid, deadline)) if System.currentTimeMillis() >= deadline =>
       |          val st = actors(aid)
       |          val (_, k, _, _) = st.blocked
       |          st.pending = k(None)
       |          st.blocked = null
       |          ready.append(aid)
       |        case _ => ()
       |    else
       |      val id = ready.removeHead()
       |      actors.get(id).foreach { st =>
       |        if st.pending != null then
       |          val initial = st.pending
       |          st.pending = null
       |          stepActor(id, initial)
       |      }
       |
       |  // v1.23 — graceful cluster shutdown: release the coord lease if we
       |  // hold it, so the next leader can claim immediately instead of
       |  // waiting for the TTL.  Raft's final (term, votedFor) is already
       |  // on disk via _raftPersist().
       |  if _leaderProtocol.get() == "coord" && _coordIsLeader then
       |    val rel = _coordReleaseFn
       |    if rel != null then
       |      try rel.asInstanceOf[String => Unit](_localNodeId)
       |      catch case _: Throwable => ()
       |    _coordIsLeader = false
       |  // Interrupt tick threads so they don't leak across reused JVMs
       |  // (each `_runActors` call has its own closure-captured state, so
       |  // an orphan thread would otherwise loop forever on stale refs).
       |  val rtt = _raftTickThread.getAndSet(null);   if rtt != null then rtt.interrupt()
       |  val ctt = _coordTickThread.getAndSet(null);  if ctt != null then ctt.interrupt()
       |  rootResult
       |""".stripMargin +
    """|
       |// ── v1.4 Logger effect ─────────────────────────────────────────────────────
       |//
       |// Logger.{info,warn,error,debug}  → _perform("Logger", op, msg)
       |// runLogger { body }              — "[LEVEL] msg" to stdout
       |// runLoggerJson { body }          — {"level":"…","msg":"…"} newline-JSON
       |// runLoggerToList { body }        — (result, List[(level, msg)])
       |
       |private def _loggerJsonStr(s: String): String =
       |  val sb = new StringBuilder("\"")
       |  s.foreach {
       |    case '"'  => sb.append("\\\"")
       |    case '\\' => sb.append("\\\\")
       |    case '\n' => sb.append("\\n")
       |    case '\r' => sb.append("\\r")
       |    case '\t' => sb.append("\\t")
       |    case c    => sb.append(c)
       |  }
       |  sb.append('"').toString
       |
       |def runLogger(bodyThunk: () => Any): Any =
       |  val ops = Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
       |  _handle(bodyThunk, ops, Map(
       |    "Logger.info"  -> { (args: List[Any]) => println(s"[INFO] ${args(0)}");  args.last.asInstanceOf[Any => Any](()) },
       |    "Logger.warn"  -> { (args: List[Any]) => println(s"[WARN] ${args(0)}");  args.last.asInstanceOf[Any => Any](()) },
       |    "Logger.error" -> { (args: List[Any]) => println(s"[ERROR] ${args(0)}"); args.last.asInstanceOf[Any => Any](()) },
       |    "Logger.debug" -> { (args: List[Any]) => println(s"[DEBUG] ${args(0)}"); args.last.asInstanceOf[Any => Any](()) },
       |  ))
       |
       |def runLoggerJson(bodyThunk: () => Any): Any =
       |  val ops = Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
       |  def fmt(level: String)(args: List[Any]): Any =
       |    println(s"{\"level\":\"$level\",\"msg\":${_loggerJsonStr(args(0).toString)}}")
       |    args.last.asInstanceOf[Any => Any](())
       |  _handle(bodyThunk, ops, Map(
       |    "Logger.info"  -> fmt("info"),
       |    "Logger.warn"  -> fmt("warn"),
       |    "Logger.error" -> fmt("error"),
       |    "Logger.debug" -> fmt("debug"),
       |  ))
       |
       |def runLoggerToList(bodyThunk: () => Any): Any =
       |  val log = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
       |  def writeLog(level: String)(args: List[Any]): Any =
       |    log += (level -> args(0).toString)
       |    args.last.asInstanceOf[Any => Any](())
       |  val ops = Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
       |  val result = _handle(bodyThunk, ops, Map(
       |    "Logger.info"  -> writeLog("info"),
       |    "Logger.warn"  -> writeLog("warn"),
       |    "Logger.error" -> writeLog("error"),
       |    "Logger.debug" -> writeLog("debug"),
       |  ))
       |  (result, log.toList)
       |
       |// ── v1.4 Random effect ─────────────────────────────────────────────────────
       |//
       |// Random.{nextInt,nextDouble,uuid,pick}  → _perform("Random", op, args*)
       |// runRandom { body }          — java.util.Random (non-deterministic)
       |// runRandomSeeded(seed)(body) — deterministic seeded java.util.Random
       |
       |object Random:
       |  def nextInt(n: Any): Any  = _perform("Random", "nextInt",    n)
       |  def nextDouble(): Any     = _perform("Random", "nextDouble")
       |  def uuid(): Any           = _perform("Random", "uuid")
       |  def pick(xs: Any): Any    = _perform("Random", "pick",       xs)
       |
       |private def _randomHandlers(rng: java.util.Random): Map[String, List[Any] => Any] = Map(
       |  "Random.nextInt" -> { (args: List[Any]) =>
       |    val n = args(0).asInstanceOf[Int]
       |    args.last.asInstanceOf[Any => Any](rng.nextInt(if n > 0 then n else 1))
       |  },
       |  "Random.nextDouble" -> { (args: List[Any]) =>
       |    args.last.asInstanceOf[Any => Any](rng.nextDouble())
       |  },
       |  "Random.uuid" -> { (args: List[Any]) =>
       |    val bytes = new Array[Byte](16)
       |    rng.nextBytes(bytes)
       |    bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
       |    bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
       |    def hex(b: Byte) = f"${b & 0xff}%02x"
       |    val u = bytes.map(hex).mkString
       |    args.last.asInstanceOf[Any => Any](s"${u.take(8)}-${u.slice(8,12)}-${u.slice(12,16)}-${u.slice(16,20)}-${u.drop(20)}")
       |  },
       |  "Random.pick" -> { (args: List[Any]) =>
       |    val xs = args(0).asInstanceOf[List[Any]]
       |    args.last.asInstanceOf[Any => Any](xs(rng.nextInt(xs.size)))
       |  },
       |)
       |
       |def runRandom(bodyThunk: () => Any): Any =
       |  val ops = Set("Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick")
       |  _handle(bodyThunk, ops, _randomHandlers(new java.util.Random()))
       |
       |def runRandomSeeded(seed: Any)(bodyThunk: () => Any): Any =
       |  val ops = Set("Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick")
       |  val s = seed match
       |    case n: Long => n
       |    case n: Int  => n.toLong
       |    case _       => sys.error("runRandomSeeded(seed: Long)")
       |  _handle(bodyThunk, ops, _randomHandlers(new java.util.Random(s)))
       |
       |// ── v1.4 Clock effect ──────────────────────────────────────────────────────
       |//
       |// Clock.{now,nowIso,sleep}  → _perform("Clock", op, args*)
       |// runClock { body }         — real wall clock; sleep → Thread.sleep(ms)
       |// runClockAt(t0) { body }   — frozen at t0 ms since epoch; sleep is no-op
       |
       |object Clock:
       |  def now(): Any          = _perform("Clock", "now")
       |  def nowIso(): Any       = _perform("Clock", "nowIso")
       |  def sleep(ms: Any): Any = _perform("Clock", "sleep", ms)
       |
       |private def _clockHandlers(frozen: Option[Long]): Map[String, List[Any] => Any] =
       |  def nowMs()  = frozen.getOrElse(java.lang.System.currentTimeMillis())
       |  def nowIso() =
       |    java.time.format.DateTimeFormatter.ISO_INSTANT
       |      .format(java.time.Instant.ofEpochMilli(nowMs()))
       |  Map(
       |    "Clock.now"    -> { (args: List[Any]) => args.last.asInstanceOf[Any => Any](nowMs()) },
       |    "Clock.nowIso" -> { (args: List[Any]) => args.last.asInstanceOf[Any => Any](nowIso()) },
       |    "Clock.sleep"  -> { (args: List[Any]) =>
       |      val ms = args(0) match { case n: Long => n; case n: Int => n.toLong; case _ => 0L }
       |      if frozen.isEmpty && ms > 0 then Thread.sleep(ms)
       |      args.last.asInstanceOf[Any => Any](())
       |    },
       |  )
       |
       |def runClock(bodyThunk: () => Any): Any =
       |  val ops = Set("Clock.now", "Clock.nowIso", "Clock.sleep")
       |  _handle(bodyThunk, ops, _clockHandlers(None))
       |
       |def runClockAt(t0: Any)(bodyThunk: () => Any): Any =
       |  val ops = Set("Clock.now", "Clock.nowIso", "Clock.sleep")
       |  val frozen = t0 match
       |    case n: Long => n
       |    case n: Int  => n.toLong
       |    case _       => sys.error("runClockAt(t0: Long)")
       |  _handle(bodyThunk, ops, _clockHandlers(Some(frozen)))
       |
       |// ── v1.4 Env effect ────────────────────────────────────────────────────────
       |//
       |// Env.{get,set,required}  → _perform("Env", op, args*)
       |// runEnv { body }          — real process env; Env.set mutates local overlay
       |// runEnvWith(map) { body } — fixture map; Env.set mutates overlay
       |
       |object Env:
       |  def get(key: Any): Any             = _perform("Env", "get",      key)
       |  def set(key: Any, value: Any): Any = _perform("Env", "set",      key, value)
       |  def required(key: Any): Any        = _perform("Env", "required", key)
       |
       |private def _envHandlers(
       |  overlay: scala.collection.mutable.Map[String, String],
       |  useReal: Boolean
       |): Map[String, List[Any] => Any] =
       |  def lookup(k: String): Option[String] =
       |    overlay.get(k)
       |      .orElse(if useReal then Option(java.lang.System.getenv(k)).filter(_.nonEmpty) else None)
       |  Map(
       |    "Env.get" -> { (args: List[Any]) =>
       |      args.last.asInstanceOf[Any => Any](lookup(args(0).toString))
       |    },
       |    "Env.set" -> { (args: List[Any]) =>
       |      overlay(args(0).toString) = args(1).toString
       |      args.last.asInstanceOf[Any => Any](())
       |    },
       |    "Env.required" -> { (args: List[Any]) =>
       |      val k = args(0).toString
       |      lookup(k) match
       |        case Some(v) => args.last.asInstanceOf[Any => Any](v)
       |        case None    => sys.error(s"Env.required: key '$k' not found in environment")
       |    },
       |  )
       |
       |def runEnv(bodyThunk: () => Any): Any =
       |  val ops = Set("Env.get", "Env.set", "Env.required")
       |  _handle(bodyThunk, ops, _envHandlers(scala.collection.mutable.Map.empty, useReal = true))
       |
       |def runEnvWith(initMap: Any)(bodyThunk: () => Any): Any =
       |  val ops = Set("Env.get", "Env.set", "Env.required")
       |  val overlay = initMap match
       |    case m: Map[_, _] =>
       |      scala.collection.mutable.Map.from(m.asInstanceOf[Map[String, String]])
       |    case _ => sys.error("runEnvWith(map: Map[String, String])")
       |  _handle(bodyThunk, ops, _envHandlers(overlay, useReal = false))
       |
       |// ── v1.4 Http effect ──────────────────────────────────────────────────────
       |//
       |// Http.{get,post,request}  → _perform("Http", op, args*)
       |// runHttp { body }              — delegates to real _httpDoRequest
       |// runHttpStub(routes) { body }  — test stub: url→body Map
       |
       |object Http:
       |  def get(url: Any): Any                                   = _perform("Http", "get",     url)
       |  def post(url: Any, body: Any): Any                       = _perform("Http", "post",    url, body)
       |  def request(method: Any, url: Any, headers: Any, body: Any): Any =
       |    _perform("Http", "request", method, url, headers, body)
       |
       |private def _httpEffectHandlers(
       |  routes: Option[Map[String, String]]
       |): Map[String, List[Any] => Any] =
       |  def stubResponse(url: String): Any =
       |    routes match
       |      case Some(m) if m.contains(url) =>
       |        Map("status" -> 200, "headers" -> Map.empty, "body" -> m(url))
       |      case _ =>
       |        Map("status" -> 404, "headers" -> Map.empty, "body" -> "")
       |  def mkResponse(url: String, method: String, body: String,
       |                 headers: Map[String, String]): Any =
       |    routes.fold(_httpDoRequest(method, url, body, headers))(_ => stubResponse(url))
       |  Map(
       |    "Http.get" -> { (args: List[Any]) =>
       |      val url = args(0).toString
       |      args.last.asInstanceOf[Any => Any](mkResponse(url, "GET", "", Map.empty))
       |    },
       |    "Http.post" -> { (args: List[Any]) =>
       |      val url = args(0).toString; val body = args(1).toString
       |      args.last.asInstanceOf[Any => Any](mkResponse(url, "POST", body, Map.empty))
       |    },
       |    "Http.request" -> { (args: List[Any]) =>
       |      val method = args(0).toString; val url = args(1).toString
       |      val headers = args(2) match
       |        case m: Map[_, _] => m.asInstanceOf[Map[String, String]]
       |        case _            => Map.empty[String, String]
       |      val body = if args.size > 3 then args(3).toString else ""
       |      args.last.asInstanceOf[Any => Any](mkResponse(url, method, body, headers))
       |    },
       |  )
       |
       |def runHttp(bodyThunk: () => Any): Any =
       |  val ops = Set("Http.get", "Http.post", "Http.request")
       |  _handle(bodyThunk, ops, _httpEffectHandlers(None))
       |
       |def runHttpStub(routes: Any)(bodyThunk: () => Any): Any =
       |  val ops = Set("Http.get", "Http.post", "Http.request")
       |  val m = routes match
       |    case r: Map[_, _] => r.asInstanceOf[Map[String, String]]
       |    case _            => sys.error("runHttpStub(routes: Map[String, String])")
       |  _handle(bodyThunk, ops, _httpEffectHandlers(Some(m)))
       |
       |// ── v1.4 Retry effect ─────────────────────────────────────────────────────
       |//
       |// Retry.attempt(n, delayMs)(thunk)  — retries thunk up to n times on exception
       |// runRetry { body }        — real Thread.sleep between attempts
       |// runRetryNoSleep { body } — test handler: no sleep
       |
       |object Retry:
       |  def attempt(n: Any, delayMs: Any): Any => Any =
       |    (thunk: Any) => _perform("Retry", "attempt", n, delayMs, thunk)
       |
       |private def _retryHandlers(doSleep: Boolean): Map[String, List[Any] => Any] = Map(
       |  "Retry.attempt" -> { (args: List[Any]) =>
       |    val n = args(0) match { case i: Int => i.toLong; case l: Long => l; case _ => 0L }
       |    val delayMs = args(1) match { case i: Int => i.toLong; case l: Long => l; case _ => 0L }
       |    val thunk = args(2).asInstanceOf[() => Any]
       |    val resume = args.last.asInstanceOf[Any => Any]
       |    var lastErr: Throwable = null
       |    var result: Any = ()
       |    var attempt = 0
       |    var succeeded = false
       |    while attempt <= n && !succeeded do
       |      try { result = thunk(); succeeded = true }
       |      catch case e: Throwable =>
       |        lastErr = e; attempt += 1
       |        if attempt <= n && doSleep && delayMs > 0 then Thread.sleep(delayMs)
       |    if succeeded then resume(result) else throw lastErr
       |  },
       |)
       |
       |def runRetry(bodyThunk: () => Any): Any =
       |  val ops = Set("Retry.attempt")
       |  _handle(bodyThunk, ops, _retryHandlers(doSleep = true))
       |
       |def runRetryNoSleep(bodyThunk: () => Any): Any =
       |  val ops = Set("Retry.attempt")
       |  _handle(bodyThunk, ops, _retryHandlers(doSleep = false))
       |""".stripMargin +
    """|
       |// ── v1.4 Cache effect ─────────────────────────────────────────────────────
       |//
       |// Cache.memoize(key, ttlSeconds)(thunk)  — process-local TTL memoization
       |// runCache { body }        — uses module-level _cacheStore
       |// runCacheBypass { body }  — always recomputes; skips cache
       |
       |private val _cacheStore = new java.util.concurrent.ConcurrentHashMap[String, (Long, Any)]()
       |private val _cacheBypass = ThreadLocal.withInitial[Boolean](() => false)
       |
       |object Cache:
       |  def memoize(key: Any, ttlSeconds: Any): Any => Any =
       |    (thunk: Any) => _perform("Cache", "memoize", key, ttlSeconds, thunk)
       |
       |private def _cacheHandlers(bypass: Boolean): Map[String, List[Any] => Any] = Map(
       |  "Cache.memoize" -> { (args: List[Any]) =>
       |    val key = args(0).toString
       |    val ttlMs = (args(1) match
       |      case i: Int  => i.toLong
       |      case l: Long => l
       |      case _       => 0L
       |    ) * 1000L
       |    val thunk = args(2).asInstanceOf[() => Any]
       |    val resume = args.last.asInstanceOf[Any => Any]
       |    if bypass || _cacheBypass.get() then resume(thunk())
       |    else
       |      val nowMs = java.lang.System.currentTimeMillis()
       |      val cached = Option(_cacheStore.get(key))
       |      cached match
       |        case Some((expiry, v)) if nowMs < expiry => resume(v)
       |        case _ =>
       |          val v = thunk()
       |          _cacheStore.put(key, (nowMs + ttlMs, v))
       |          resume(v)
       |  },
       |)
       |
       |def runCache(bodyThunk: () => Any): Any =
       |  val prior = _cacheBypass.get()
       |  _cacheBypass.set(false)
       |  try _handle(bodyThunk, Set("Cache.memoize"), _cacheHandlers(false))
       |  finally _cacheBypass.set(prior)
       |
       |def runCacheBypass(bodyThunk: () => Any): Any =
       |  val prior = _cacheBypass.get()
       |  _cacheBypass.set(true)
       |  try _handle(bodyThunk, Set("Cache.memoize"), _cacheHandlers(true))
       |  finally _cacheBypass.set(prior)
       |
       |// ── v1.4 State effect ─────────────────────────────────────────────────────
       |//
       |// State.get              → _perform("State", "get")
       |// State.set(s)           → _perform("State", "set", s)
       |// State.modify(f)        → _perform("State", "modify", f)
       |// runState(s0) { body }  — returns (finalState, result)
       |
       |object State:
       |  def get(): Any          = _perform("State", "get")
       |  def set(s: Any): Any    = _perform("State", "set", s)
       |  def modify(f: Any): Any = _perform("State", "modify", f)
       |
       |def runState(s0: Any)(bodyThunk: () => Any): Any =
       |  var state: Any = s0
       |  val handlers: Map[String, List[Any] => Any] = Map(
       |    "State.get" -> { (args: List[Any]) =>
       |      args.last.asInstanceOf[Any => Any](state)
       |    },
       |    "State.set" -> { (args: List[Any]) =>
       |      state = args(0)
       |      args.last.asInstanceOf[Any => Any](())
       |    },
       |    "State.modify" -> { (args: List[Any]) =>
       |      state = args(0).asInstanceOf[Any => Any](state)
       |      args.last.asInstanceOf[Any => Any](())
       |    },
       |  )
       |  val ops = Set("State.get", "State.set", "State.modify")
       |  val result = _handle(bodyThunk, ops, handlers)
       |  (state, result)
       |
       |// ── v1.4 Tx effect ────────────────────────────────────────────────────────
       |//
       |// Tx.atomic { body }  — signals transactional scope; default is no-op
       |// runTx { body }      — default no-op handler (just runs body directly)
       |
       |object Tx:
       |  def atomic(thunk: () => Any): Any = thunk()
       |
       |def runTx(bodyThunk: () => Any): Any = bodyThunk()
       |
       |// ── v1.4 Auth effect ──────────────────────────────────────────────────────
       |//
       |// Auth.currentUser  — Option[Any] from thread-local
       |// Auth.require      — current user or throw RuntimeException
       |// runAuthWith(user) { body }  — injects a fixed user
       |
       |private val _authUser = ThreadLocal.withInitial[Option[Any]](() => None)
       |
       |object Auth:
       |  def currentUser: Any = _authUser.get()
       |  def require: Any = _authUser.get() match
       |    case Some(u) => u
       |    case None    => throw new RuntimeException("Auth.require: no authenticated user in context")
       |
       |def runAuthWith(user: Any)(bodyThunk: () => Any): Any =
       |  val prior = _authUser.get()
       |  _authUser.set(Some(user))
       |  try bodyThunk() finally _authUser.set(prior)
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

  /** Top-level helper functions for frontend modules.  Prepended to the user
   *  section so they're defined BEFORE the `import std.ui.primitives.{serve,...}`
   *  line — this ensures `serve(port)` inside `_ssc_ui_serve` resolves to the
   *  preamble's `serve(port: Int, ...)` rather than the opaque-typed wrapper. */
  private def uiHelperFunctions(frontendName: String): String =
    s"""|
       |// ── UI helpers injected by JvmGen for frontend-framework modules ──────────
       |{
       |  val _fe_prop = System.getProperty("scalascript.frontend")
       |  val _fe_name = if _fe_prop != null && _fe_prop.nonEmpty then _fe_prop else "$frontendName"
       |  scalascript.frontend.FrontendFrameworks.setBackend(_fe_name)
       |  _ssc_frontend_name = _fe_name
       |}
       |def _ssc_ui_decodeAttrs(m: Map[String, Any]): Map[String, scalascript.frontend.AttrValue] =
       |  m.collect {
       |    case (k, v: String)  => k -> scalascript.frontend.AttrValue.Str(v)
       |    case (k, v: Boolean) => k -> scalascript.frontend.AttrValue.Bool(v)
       |    case (k, v: Int)     => k -> scalascript.frontend.AttrValue.Num(v.toDouble)
       |    case (k, v: Long)    => k -> scalascript.frontend.AttrValue.Num(v.toDouble)
       |    case (k, v: Double)  => k -> scalascript.frontend.AttrValue.Num(v)
       |    case (k, v: scalascript.frontend.ReactiveSignal[?]) =>
       |      k -> scalascript.frontend.AttrValue.Reactive(v)
       |  }
       |
       |def _ssc_ui_decodeEvents(m: Map[String, Any]): Map[String, scalascript.frontend.EventHandler] =
       |  m.collect { case (k, v: scalascript.frontend.EventHandler) => k -> v }
       |
       |def _ssc_ui_buildModule(view: scalascript.frontend.View[?], extraCss: String = ""): scalascript.frontend.FrontendModule =
       |  scalascript.frontend.FrontendModule(
       |    List(scalascript.frontend.ComponentDef("App", Nil, _ =>
       |      scalascript.frontend.View.Element("div",
       |        Map("id" -> scalascript.frontend.AttrValue.Str("ui-app")),
       |        Map.empty, Seq(view)))),
       |    "App", "/", extraCss)
       |
       |def _ssc_ui_emit_to_dir(view: scalascript.frontend.View[?], dir: String, extraCss: String = ""): Unit =
       |  val _mod     = _ssc_ui_buildModule(view, extraCss)
       |  val _emitted = scalascript.frontend.FrontendFrameworks.current().emit(_mod)
       |  val _p = java.nio.file.Paths.get(dir)
       |  java.nio.file.Files.createDirectories(_p)
       |  java.nio.file.Files.writeString(_p.resolve("index.html"), _emitted.html)
       |  val _appJs = if _ssc_client_sql_js.nonEmpty then _emitted.js + "\\n" + _ssc_client_sql_js
       |               else _emitted.js
       |  java.nio.file.Files.writeString(_p.resolve("app.js"), _appJs)
       |  if _emitted.css.nonEmpty then
       |    java.nio.file.Files.writeString(_p.resolve("app.css"), _emitted.css)
       |
       |def _ssc_ui_emit_to_tempdir(view: scalascript.frontend.View[?], extraCss: String = ""): String =
       |  val _mod     = _ssc_ui_buildModule(view, extraCss)
       |  val _emitted = scalascript.frontend.FrontendFrameworks.current().emit(_mod)
       |  val _tmpDir  = java.nio.file.Files.createTempDirectory("ssc-ui")
       |  java.nio.file.Files.writeString(_tmpDir.resolve("index.html"), _emitted.html)
       |  val _appJs = if _ssc_client_sql_js.nonEmpty then _emitted.js + "\\n" + _ssc_client_sql_js
       |               else _emitted.js
       |  java.nio.file.Files.writeString(_tmpDir.resolve("app.js"), _appJs)
       |  if _emitted.css.nonEmpty then
       |    java.nio.file.Files.writeString(_tmpDir.resolve("app.css"), _emitted.css)
       |  _tmpDir.toString
       |
       |def _ssc_ui_emit_native_to_dir(view: scalascript.frontend.View[?], dir: String, extraCss: String = ""): Unit =
       |  val _mod = _ssc_ui_buildModule(view, extraCss)
       |  val _artifact = scalascript.frontend.FrontendFrameworks.current()
       |    .emitNative(_mod, scalascript.frontend.Platform.Desktop())
       |    .getOrElse(throw IllegalStateException("selected frontend does not emit a JVM desktop native app"))
       |  val _p = java.nio.file.Paths.get(dir)
       |  java.nio.file.Files.createDirectories(_p)
       |  for ((_name, _source) <- _artifact.sources) do
       |    val _target = _p.resolve(_name)
       |    val _parent = _target.getParent
       |    if _parent != null then java.nio.file.Files.createDirectories(_parent)
       |    java.nio.file.Files.writeString(_target, _source)
       |  for ((_name, _bytes) <- _artifact.resources) do
       |    val _target = _p.resolve(_name)
       |    val _parent = _target.getParent
       |    if _parent != null then java.nio.file.Files.createDirectories(_parent)
       |    java.nio.file.Files.write(_target, _bytes)
       |
       |private def _ssc_ui_utf8(value: String): Array[Byte] =
       |  Option(value).getOrElse("").getBytes(java.nio.charset.StandardCharsets.UTF_8)
       |
       |def _ssc_ui_backend_response(value: Any): scalascript.backend.spi.BackendResponse =
       |  value match
       |    case r: Response =>
       |      scalascript.backend.spi.BackendResponse(
       |        r.status,
       |        r.headers,
       |        _ssc_ui_utf8(r.body)
       |      )
       |    case sr: _StreamResponse =>
       |      val sb = StringBuilder()
       |      sr.writer(chunk => sb.append(chunk))
       |      scalascript.backend.spi.BackendResponse(
       |        sr.status,
       |        sr.headers,
       |        _ssc_ui_utf8(sb.toString)
       |      )
       |    case other =>
       |      scalascript.backend.spi.BackendResponse(
       |        200,
       |        Map("Content-Type" -> "text/plain; charset=utf-8"),
       |        _ssc_ui_utf8(_show(other))
       |      )
       |
       |private val _ssc_ui_backend_transport: scalascript.backend.spi.BackendTransport =
       |  new scalascript.backend.spi.BackendTransport:
       |    def request(req0: scalascript.backend.spi.BackendRequest): scala.concurrent.Future[scalascript.backend.spi.BackendResponse] =
       |      val method = req0.method.toUpperCase
       |      val queryIdx = req0.path.indexOf('?')
       |      val path = if queryIdx >= 0 then req0.path.take(queryIdx) else req0.path
       |      val query = if queryIdx >= 0 then _parseQuery(req0.path.drop(queryIdx + 1)) else Map.empty[String, String]
       |      val segs = path.split('/').toList.filter(_.nonEmpty)
       |      val body = String(req0.body, java.nio.charset.StandardCharsets.UTF_8)
       |      val loweredHeaders = req0.headers.map((k, v) => k.toLowerCase -> v)
       |      val response =
       |        _routes.iterator
       |          .filter(_.method == method)
       |          .flatMap(r => _matchPath(r.pattern, segs).map(params => (r, params)))
       |          .nextOption() match
       |            case Some((r, params)) =>
       |              val req = Request(method, path, params, query, loweredHeaders, body)
       |              try
       |                def runHandler(): Any = r.handler(req)
       |                val chain = _middlewares.reverseIterator.foldLeft(() => runHandler()) { (next, mw) =>
       |                  () => mw(req, next)
       |                }
       |                _ssc_ui_backend_response(chain())
       |              catch
       |                case e: RestValidationError =>
       |                  scalascript.backend.spi.BackendResponse(
       |                    400,
       |                    Map("Content-Type" -> "text/plain; charset=utf-8"),
       |                    _ssc_ui_utf8(e.getMessage)
       |                  )
       |                case e: Throwable =>
       |                  scalascript.backend.spi.BackendResponse(
       |                    500,
       |                    Map("Content-Type" -> "text/plain; charset=utf-8"),
       |                    _ssc_ui_utf8(e.getMessage)
       |                  )
       |            case None =>
       |              scalascript.backend.spi.BackendResponse(
       |                404,
       |                Map("Content-Type" -> "text/plain; charset=utf-8"),
       |                _ssc_ui_utf8("Not Found: " + path)
       |              )
       |      scala.concurrent.Future.successful(response)
       |
       |def _ssc_ui_backend_request(method: String, url: String, body: String): scalascript.backend.spi.BackendResponse =
       |  scala.concurrent.Await.result(
       |    _ssc_ui_backend_transport.request(
       |      scalascript.backend.spi.BackendRequest(
       |        method,
       |        url,
       |        Map("Content-Type" -> "application/json"),
       |        _ssc_ui_utf8(body)
       |      )
       |    ),
       |    scala.concurrent.duration.Duration.Inf
       |  )
       |
       |def _ssc_ui_inprocess_fetch(methodRaw: String, url: String, body: String): scalascript.frontend.swing.SwingRuntime.FetchResponse =
       |  val response = _ssc_ui_backend_request(methodRaw, url, body)
       |  scalascript.frontend.swing.SwingRuntime.FetchResponse(
       |    response.status,
       |    String(response.body, java.nio.charset.StandardCharsets.UTF_8),
       |    response.headers
       |  )
       |
       |def _ssc_ui_run_native(view: scalascript.frontend.View[?], extraCss: String = ""): Unit =
       |  val _mod = _ssc_ui_buildModule(view, extraCss)
       |  println("ssc: launching Swing")
       |  println("     mode:   same-process JVM")
       |  scalascript.frontend.swing.SwingRuntime.run(
       |    _mod,
       |    scalascript.frontend.swing.SwingRuntime.Options(
       |      fetchDispatcher = Some(new scalascript.frontend.swing.SwingRuntime.FetchDispatcher:
       |        def request(method: String, url: String, body: String): scalascript.frontend.swing.SwingRuntime.FetchResponse =
       |          _ssc_ui_inprocess_fetch(method, url, body)
       |      )
       |    )
       |  )
       |
       |def _ssc_ui_serve(tree: Any, port: Int, extraCss: String = ""): Unit =
       |  if _ssc_frontend_name == "swing" then
       |    _ssc_ui_run_native(tree.asInstanceOf[scalascript.frontend.View[?]], extraCss)
       |  else
       |    val _outDir = _ssc_ui_emit_to_tempdir(tree.asInstanceOf[scalascript.frontend.View[?]], extraCss)
       |    _ssc_static_root = _outDir
       |    serve(port)
       |
       |// ── Overloads to shadow preamble names that conflict with UI widget imports ──
       |// serve(view, port[, extraCss]): beats preamble serve(Int) / serve(Int,String) / serve(Int,TlsConfig)
       |def serve(tree: Any, port: Int): Unit = _ssc_ui_serve(tree, port)
       |def serve(tree: Any, port: Int, extraCss: String): Unit = _ssc_ui_serve(tree, port, extraCss)
       |// text(String): beats extension (r: Response.type) def text(body: Any)
       |def text(content: String) = std.ui.typography.text(content)
       |
       |""".stripMargin

  /** Colon-style `object std:` block injected into the user section and merged
   *  (via `mergeDuplicatePackageObjects`) with the extern-filtered primitives.ssc
   *  object.  Provides JVM implementations for all UI extern defs so that
   *  `import std.ui.primitives.{signal, element, serve, ...}` resolves. */
  private val uiPrimitivesBlock: String =
    """|object std:
       |  object ui:
       |    object primitives:
       |      // Signal[T] params/returns use Any: opaque Signal[T]=Any, but callers
       |      // may pass Any-typed fields (e.g. case class fields typed as Any in nodes.ssc).
       |      def signal[T](name: String, default: T): Any =
       |        new scalascript.frontend.ReactiveSignal[T](name, default)
       |
       |      def element(tag: String, attrs: Map[String, Any], events: Map[String, Any], children: List[View]): View =
       |        scalascript.frontend.View.Element(tag,
       |          _ssc_ui_decodeAttrs(attrs), _ssc_ui_decodeEvents(events),
       |          children.asInstanceOf[Seq[scalascript.frontend.View[?]]])
       |
       |      def textNode(s: String): View =
       |        scalascript.frontend.View.TextNode(() => s)
       |
       |      def signalText(s: Any): View =
       |        scalascript.frontend.View.SignalText(
       |          s.asInstanceOf[scalascript.frontend.ReactiveSignal[?]])
       |
       |      def showSignal(cond: Any, whenTrue: View, whenFalse: View): View =
       |        scalascript.frontend.View.ShowSignal(
       |          cond.asInstanceOf[scalascript.frontend.ReactiveSignal[Boolean]],
       |          whenTrue.asInstanceOf[scalascript.frontend.View[?]],
       |          whenFalse.asInstanceOf[scalascript.frontend.View[?]])
       |
       |      def fragment(children: List[View]): View =
       |        scalascript.frontend.View.Fragment(
       |          children.asInstanceOf[Seq[scalascript.frontend.View[?]]])
       |
       |      def setSignal(s: Any, v: Any): EventHandler =
       |        scalascript.frontend.EventHandler.SetSignalLiteral(
       |          s.asInstanceOf[scalascript.frontend.ReactiveSignal[Any]], v)
       |
       |      def inputChange(s: Any): EventHandler =
       |        scalascript.frontend.EventHandler.InputChange(
       |          s.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |
       |      def toggleSignal(s: Any): EventHandler =
       |        scalascript.frontend.EventHandler.ToggleSignal(
       |          s.asInstanceOf[scalascript.frontend.ReactiveSignal[Boolean]])
       |
       |      def eqSignal(s: Any, value: Any): Any =
       |        val _jsName     = s.asInstanceOf[scalascript.frontend.ReactiveSignal[?]].id
       |        val _initial    = s.asInstanceOf[scalascript.frontend.ReactiveSignal[?]].apply().asInstanceOf[Any] == value
       |        val _safeSuffix = value.toString.replaceAll("[^A-Za-z0-9]", "_")
       |        new scalascript.frontend.ReactiveSignal[Boolean](_jsName + "__eq__" + _safeSuffix, _initial)
       |
       |      def hashSignal(): Any =
       |        new scalascript.frontend.ReactiveSignal[String]("__hash__", "")
       |
       |      def fetchUrlSignal(name: String, url: String, refreshTick: Any): Any =
       |        new scalascript.frontend.FetchUrlSignal(name, url,
       |          refreshTick.asInstanceOf[scalascript.frontend.ReactiveSignal[?]].id)
       |
       |      def fetchAction(method: String, url: String, body: Any, onSuccessTick: Any): EventHandler =
       |        scalascript.frontend.EventHandler.FetchAction(method, url,
       |          body.asInstanceOf[scalascript.frontend.ReactiveSignal[String]],
       |          onSuccessTick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]])
       |
       |      def incSignal(s: Any): EventHandler =
       |        scalascript.frontend.EventHandler.IncrementSignal(
       |          s.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]], 1)
       |
       |      def fetchActionClear(method: String, url: String, body: Any, onSuccessTick: Any): EventHandler =
       |        scalascript.frontend.EventHandler.FetchAction(method, url,
       |          body.asInstanceOf[scalascript.frontend.ReactiveSignal[String]],
       |          onSuccessTick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]],
       |          clearBody = true)
       |
       |      def fetchTableView(fetchUrl: String, deleteUrl: String, tick: Any): View =
       |        val _tableJsName = "sscRows_" + fetchUrl.replaceAll("[^A-Za-z0-9]", "_")
       |        scalascript.frontend.View.FetchTable(_tableJsName, fetchUrl, deleteUrl,
       |          tick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]])
       |
       |      def emit(tree: View, outDir: String): Unit =
       |        _ssc_ui_emit_to_dir(tree.asInstanceOf[scalascript.frontend.View[?]], outDir)
       |
       |      def serve(tree: View, port: Int): Unit =
       |        _ssc_ui_serve(tree, port)
       |
       |""".stripMargin
