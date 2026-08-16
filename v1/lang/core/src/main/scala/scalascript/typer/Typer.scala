package scalascript.typer

import scalascript.ast.*
import scala.collection.mutable.ListBuffer
import scala.meta.*
import java.security.MessageDigest

/** Optionally-populated map of pre-compiled module interfaces.
 *  Key is the import alias (or the last segment of the package name).
 *  Used by `ssc check --use-interface <dir>` to avoid re-parsing sources.
 *
 *  v2.0 / Stage 4.
 *
 *  @param importedInterfaces map of import alias -> ModuleInterface
 *  @param strict             when true, references to names not in scope
 *                            (neither in the consumer's own defs, nor in any
 *                            imported `.scim` interface, nor in the builtin
 *                            prelude) record a `TypeError`.  Default is
 *                            `false` for backward compatibility with callers
 *                            that rely on the historic permissive behaviour.
 */
class Typer(
    importedInterfaces: Map[String, scalascript.ir.ModuleInterface] = Map.empty,
    strict: Boolean = false,
    extraBuiltins: Set[String] = Set.empty,
    fatalWarnings: Boolean = false,
    strictNamespaces: Boolean = false,
    suppressedCollisions: Set[(String, String)] = Set.empty,
    /** Typed prelude symbols contributed by plugins (core-min-prelude-spi). Each defines a
     *  prelude `Symbol` with the declared `tpe` (parsed via `InterfaceScope.parseSType`) instead
     *  of the untyped `variadic`, so `ssc check` can type-check calls to plugin intrinsics. */
    preludeSymbols: List[scalascript.ir.ExportedSymbol] = Nil
):
  private val errors      = ListBuffer[TypeError]()

  /** wide-jit C-1: Term → inferred `SType` side-map, recorded by `inferType` during
   *  type inference. Keyed by scalameta `Tree` IDENTITY (each node is a distinct object).
   *  Exposed so the register-VM JIT can seed its register types from real static types
   *  instead of re-inferring at runtime (`typeOf` defaulting to `TInt`). Partial by design:
   *  first-order shapes get real types; lambdas/closures get `SType.Any` (out of wide-jit
   *  scope — those need a closure model). See `specs/wide-jit-typed-input.md`. */
  private val _nodeTypes = new java.util.IdentityHashMap[scala.meta.Tree, SType]()
  /** Read the per-node type map after `typeCheck` (identity-keyed on the checked trees). */
  def nodeTypes: java.util.Map[scala.meta.Tree, SType] = _nodeTypes

  /** Deprecated definitions: name → deprecation message. */
  private val deprecatedDefs    = collection.mutable.Map.empty[String, String]
  /** Experimental definitions: name → experimental notice. */
  private val experimentalDefs  = collection.mutable.Map.empty[String, String]
  /** arch-lib-p2 — names marked @internal in any imported module interface.
   *  Accessing one of these from this module is a cross-package access error. */
  private val internalImportedNames: Set[String] =
    importedInterfaces.values.flatMap(_.exports.filter(_.isInternal).map(_.name)).toSet
  /** Registry of user-defined type aliases: name → (typeParams, expandedRhs).
   *  Populated by `checkStat` when it encounters a `type Name[...] = T` declaration.
   *  Consulted by `typeAnnotToSType` to expand alias names at use-sites. */
  private val typeAliases = collection.mutable.Map.empty[String, (List[String], SType)]

  /** Names of opaque types defined in the current module.
   *  Opaque types are nominal — `String` is NOT assignable to `UserId` outside
   *  the defining scope.  The typer records them here so `isCompatible` can
   *  enforce the sealing rule.  This is a conservative MVP: we treat all
   *  access as outside the defining scope (no two-zone rule yet). */
  private val opaqueTypes = collection.mutable.Set.empty[String]

  /** Field tables for user-defined classes / case classes / enums:
   *  className → ordered (fieldName, fieldType) list.  Populated by
   *  `checkStat` when it encounters a `class`/`case class` declaration
   *  with a constructor parameter clause.  Consulted by [[inferType]]
   *  on `Term.Select(qual, Name(field))` when `qual`'s type is
   *  `Named(className, _)`, so `someFoo.x` returns the declared type
   *  of `x` instead of collapsing to `Any`.
   *
   *  Tier-5 .scim granularity push (Open question #1) — the .scim
   *  artifact records callsite types accurately for the common
   *  "case-class accessor" pattern without paying the cost of full
   *  type-parameter substitution (deferred). */
  private val classFields = collection.mutable.Map.empty[String, List[(String, SType)]]

  /** Direct nominal parents (the `extends` clause) of each user `class` /
   *  `case class` / `trait` / `enum`, by name. Populated in the collect pass
   *  (alongside `classFields`) from the template `inits`; consulted by
   *  [[nominalSubtype]] so `isCompatible` recognises `C <: T` for a declared
   *  `case class C() extends T`. Without it the only "subtype" that ever
   *  passed was the accidental `looksLikeTypeVar` match on single-uppercase
   *  names; real multi-character names failed. Plain mutable, NOT snapshotted
   *  — a structural registry like `classFields`. */
  private val classParents = collection.mutable.Map.empty[String, List[String]]

  /** Kind registry for type constructors: name → the kinds of its type
   *  parameters, where each kind is that parameter's own arity (`0` = proper
   *  type `*`; `k>0` = higher-kinded `F[_…]` of arity `k`). The list length is
   *  the constructor's own arity. Populated for user `class`/`trait`/`enum`
   *  (collect pass) and `type` aliases (incl. type lambdas). Consulted by
   *  `checkTypeApplication` for arity + higher-kinded-bound checking. Mirrors
   *  `classFields` (plain mutable, NOT snapshotted — a structural registry, not
   *  per-section restorable state). */
  private val typeCtorKinds = collection.mutable.Map.empty[String, List[Int]]

  /** Diagnostics accumulated so far.  Stable view into the running buffer —
   *  callers should snapshot to a `List` when they need persistence. */
  def diagnostics: List[TypeError] = errors.toList

  def typeCheck(module: Module): TypedModule =
    val prelude  = if extraBuiltins.isEmpty && preludeSymbols.isEmpty then Typer.sharedPrelude else createPrelude()
    // v2.0: if we have pre-compiled interfaces, build an InterfaceScope layer
    // between the prelude and the module's own top-level scope so that names
    // from imported modules resolve without re-parsing their source.
    // Always use a child scope so predeclareModuleNames never writes into the
    // shared prelude (which would corrupt it for concurrent/subsequent modules).
    val baseScope =
      if importedInterfaces.isEmpty then prelude.child("<module>")
      else
        import scalascript.artifact.InterfaceScope
        // Detect namespace collisions before building the merged scope.
        if importedInterfaces.size > 1 then
          val collisions = InterfaceScope.detectCollisions(
            importedInterfaces.toList,
            suppressed = suppressedCollisions
          )
          collisions.foreach { c =>
            errors += TypeError(c.message, None, isWarning = !(fatalWarnings || strictNamespaces))
          }
        InterfaceScope.fromInterfaces(importedInterfaces.toList, parent = Some(prelude))
    // Pre-declare all top-level names from every section so cross-section
    // forward references and mutual recursion work in ssc check.
    predeclareModuleNames(module, baseScope)
    val sections = module.sections.map(s => typeCheckSection(s, baseScope))
    TypedModule(
      name     = module.manifest.flatMap(_.name).getOrElse("<anonymous>"),
      version  = module.manifest.flatMap(_.version).getOrElse("0.0.0"),
      sections = sections,
      errors   = errors.toList
    )

  /** Incremental type-check variant.
   *
   *  Compares a content hash for each section against the previous run's
   *  [[SectionSnapshot]] list.  Re-types only from the first changed
   *  section forward; sections before it reuse their previously computed
   *  [[TypedSection]] results and the snapshot restores the typer's mutable
   *  state (typeAliases / opaqueTypes) to the value it had just before that
   *  section was originally typed.
   *
   *  @param module        freshly-parsed module (may differ from the previous run)
   *  @param prevSnapshots snapshots produced by the previous call (or `Nil` for
   *                       a cold first run, which re-types all sections)
   *  @return a pair of (TypedModule, new snapshots aligned with module.sections)
   */
  def typeCheckIncremental(
      module: Module,
      prevSnapshots: List[SectionSnapshot]
  ): (TypedModule, List[SectionSnapshot]) =
    val prelude = if extraBuiltins.isEmpty && preludeSymbols.isEmpty then Typer.sharedPrelude else createPrelude()
    val baseScope =
      if importedInterfaces.isEmpty then prelude.child("<module>")
      else
        import scalascript.artifact.InterfaceScope
        InterfaceScope.fromInterfaces(importedInterfaces.toList, parent = Some(prelude))

    // Build hashes for every section in the current parse.
    val currentHashes = module.sections.map(SectionSnapshot.hashSection)

    // Find the first section whose hash differs from the previous snapshot.
    // `firstChangedIdx` == module.sections.length when nothing changed.
    val prevHashList = prevSnapshots.map(_.sectionHash)
    val firstChangedIdx = currentHashes.zipWithIndex.collectFirst {
      case (hash, idx) if prevHashList.lift(idx).forall(_ != hash) => idx
    }.getOrElse(module.sections.length)

    // Restore typer state from the snapshot *before* the first changed section.
    // If firstChangedIdx == 0, we start from an empty state (already the
    // default after construction).
    if firstChangedIdx > 0 then
      val snapBefore = prevSnapshots(firstChangedIdx - 1)
      typeAliases.clear()
      typeAliases ++= snapBefore.typeAliases
      opaqueTypes.clear()
      opaqueTypes ++= snapBefore.opaqueTypes
    // else: typeAliases / opaqueTypes start empty (constructor defaults).

    errors.clear()

    // Restore errors from unchanged sections.  Without this, a type error in
    // section 1 would silently vanish whenever a later section is re-typed,
    // because `errors` only accumulates from the re-typed sections below.
    val unchangedErrors = prevSnapshots.take(firstChangedIdx).flatMap(_.errors)
    errors ++= unchangedErrors

    // Carry forward unchanged TypedSection results.
    val unchangedTyped = prevSnapshots.take(firstChangedIdx).map(_.typedSection)

    // Re-type sections from firstChangedIdx onward, building new snapshots.
    // Track where `errors` sits before each section so we can isolate that
    // section's own errors and store them in its snapshot.
    val newSnapshots = ListBuffer[SectionSnapshot](prevSnapshots.take(firstChangedIdx)*)
    val reTyped = module.sections.zipWithIndex.drop(firstChangedIdx).map { (section, idx) =>
      val errorsBefore = errors.length
      val ts = typeCheckSection(section, baseScope)
      val sectionErrors = errors.slice(errorsBefore, errors.length).toList
      newSnapshots += SectionSnapshot(
        sectionHash  = currentHashes(idx),
        typedSection = ts,
        typeAliases  = typeAliases.toMap,
        opaqueTypes  = opaqueTypes.toSet,
        errors       = sectionErrors
      )
      ts
    }

    val allSections = unchangedTyped ++ reTyped
    val typedModule = TypedModule(
      name     = module.manifest.flatMap(_.name).getOrElse("<anonymous>"),
      version  = module.manifest.flatMap(_.version).getOrElse("0.0.0"),
      sections = allSections,
      errors   = errors.toList
    )
    (typedModule, newSnapshots.toList)

  private def createPrelude(): Scope =
    val s = Scope()
    // I/O / assertions — variadic, single Any param sentinel
    s.define(Symbol("println", SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    s.define(Symbol("print",   SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    s.define(Symbol("assert",  SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    s.define(Symbol("require", SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    // Option / collection constructors
    s.define(Symbol("Some",    SType.Function(List(SType.Any), SType.option(SType.Any)), SymbolKind.Def))
    s.define(Symbol("None",    SType.option(SType.Nothing), SymbolKind.Val))
    s.define(Symbol("Nil",     SType.list(SType.Nothing),   SymbolKind.Val))
    s.define(Symbol("List",    SType.Function(List(SType.Any), SType.list(SType.Any)), SymbolKind.Def))
    s.define(Symbol("Vector",  SType.Function(List(SType.Any), SType.Named("Vector", List(SType.Any))), SymbolKind.Def))
    s.define(Symbol("Set",     SType.Function(List(SType.Any), SType.Named("Set",    List(SType.Any))), SymbolKind.Def))
    s.define(Symbol("Map",     SType.Function(List(SType.Any), SType.map(SType.Any, SType.Any)), SymbolKind.Def))
    s.define(Symbol("Seq",     SType.Function(List(SType.Any), SType.Named("Seq",    List(SType.Any))), SymbolKind.Def))
    s.define(Symbol("Array",   SType.Function(List(SType.Any), SType.Named("Array",  List(SType.Any))), SymbolKind.Def))
    s.define(Symbol("Right",   SType.Function(List(SType.Any), SType.Named("Either", List(SType.Any, SType.Any))), SymbolKind.Def))
    s.define(Symbol("Left",    SType.Function(List(SType.Any), SType.Named("Either", List(SType.Any, SType.Any))), SymbolKind.Def))
    // Exact-numeric constructors (v1.64) — return BigInt / Decimal so the
    // numeric tower and the Decimal⊕Double guard fire at type-check time.
    s.define(Symbol("BigInt",     SType.Function(List(SType.Any), SType.BigInt),  SymbolKind.Def))
    s.define(Symbol("Decimal",    SType.Function(List(SType.Any), SType.Decimal), SymbolKind.Def))
    s.define(Symbol("BigDecimal", SType.Function(List(SType.Any), SType.Decimal), SymbolKind.Def))
    // Standard objects / namespaces
    s.define(Symbol("math",    SType.Named("math", Nil), SymbolKind.Object))
    s.define(Symbol("scala",   SType.Named("scala", Nil), SymbolKind.Object))
    s.define(Symbol("java",    SType.Named("java", Nil), SymbolKind.Object))
    s.define(Symbol("compiletime", SType.Named("compiletime", Nil), SymbolKind.Object))
    s.define(Symbol("sys",     SType.Named("sys", Nil), SymbolKind.Object))
    s.define(Symbol("Console", SType.Named("Console", Nil), SymbolKind.Object))
    s.define(Symbol("args",    SType.Named("Array", List(SType.String)), SymbolKind.Val))
    // Doc / render / serve
    s.define(Symbol("doc",     SType.Function(List(SType.Any), SType.Any), SymbolKind.Def))
    s.define(Symbol("render",  SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    s.define(Symbol("serve",      SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    s.define(Symbol("serveAsync", SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    // Value (de)serialization builtins — interpreter globals (BuiltinsRuntime),
    // not plugin intrinsics, so a standalone `ssc check` of a single module that
    // uses them (without a full-program run) would otherwise flag them undefined.
    s.define(Symbol("toWire",   SType.Function(List(SType.Any),    SType.String), SymbolKind.Def))
    s.define(Symbol("fromWire", SType.Function(List(SType.String), SType.Any),    SymbolKind.Def))
    // Effect / actor / runtime intrinsics — the interpreter recognises these
    // by name (see backend-interpreter/Interpreter.scala).  Seeding them here
    // prevents strict-mode false-positives for code that uses real ScalaScript
    // effects without an explicit import.
    val variadic = SType.Function(List(SType.Any), SType.Any)
    // ALL standard effect runners are now declared in their bundled plugins' `preludeSymbols`
    // (core-min-prelude-migrate) — the typer does not enforce effect discharge, so a plain `Any`
    // declaration suffices for `ssc check`, and the interpreter resolves each runner via the bundled
    // plugin's block-form (or, for Stream/Actors, a runtime that stays in core via a provider seam):
    //   runLogger/runLoggerJson/runLoggerToList (logger), runRandomSeeded (random),
    //   runClockAt (clock), runEnvWith (env), runState (state), runHttp/runHttpStub (http),
    //   runStream + the `Stream` object (streams-plugin) — the LAST runner off the core prelude.
    // The typed `runnerType`/`runnerType2` prelude helpers were removed with their last user
    // (`runStream`); Stream's runtime (Free-monad driver + FastTier + installStreamGlobal) stays in core.
    // NonDet and Reader globals (no dedicated plugin yet — stay in core)
    s.define(Symbol("NonDet",   SType.Named("NonDet",  Nil), SymbolKind.Object))
    s.define(Symbol("Reader",   SType.Named("Reader",  Nil), SymbolKind.Object))
    s.define(Symbol("withReader", variadic, SymbolKind.Def))
    val effectBuiltins = List(
      "handle", "validate", "computed", "effect", "summon", "summonInline",
      "constValue", "direct", "Focus", "Prism",
      "runAsync", "runAsyncParallel", "runAuthWith",
      "runEphemeralStorage",
      // runRandom — MIGRATED to random-effect-plugin.preludeSymbols (core-min-prelude-migrate);
      // resolves via the bundled plugin's typed prelude (the keystone), not this hardcoded list.
      "runStorage", "runTx",
      "httpClient",
      // runActors + the whole actor/process/cluster keyword set (spawn/self/send/receive/timeout,
      // membership/leader/gossip/config/metric/drain, timers, recvFrom) — MIGRATED to
      // actors-plugin.preludeSymbols. The bundled actors plugin declares them for `ssc check`; the
      // runtime stays in core via the ActorRuntimeProvider seam (CoreActorRuntimeProvider).
      "delay", "async", "await", "parallel",
      // tests / DSL helpers
      "main", "test", "describe", "it", "expect", "check"
    )
    effectBuiltins.foreach(n => s.define(Symbol(n, variadic, SymbolKind.Def)))
    extraBuiltins.foreach(n => s.define(Symbol(n, variadic, SymbolKind.Def)))
    // core-min-advanced-optin: plugin namespace OBJECTS (`oauth`/`oidc`/`spark`/`http`) MIGRATED to
    // their owning plugins' `preludeSymbols` — oauth/oidc → oauth-plugin (advanced, opt-in via
    // `--plugin`), spark → SparkBackend, http → http-plugin (essential, auto-loaded). The hardcoded
    // `pluginObjects` list is gone; advanced names now resolve only when the plugin is added (strict
    // opt-in, the deliberate UX), essential/backend names resolve whenever the plugin is on classpath.
    val pluginBuiltins = List(
      // interpreter-core globals (no owning std plugin) — stay hardcoded.
      "Async", "Await", "Signal", "Future", "Storage",
      "Db", "KV", "ObjectStore", "State", "Events", "Sync", "InMemory", "IndexedDb",
      // `suspend` sits beside `generator` because it is that construct's keyword: it is only
      // meaningful inside `generator[T] { ... }`, exactly as the actor keywords are only meaningful
      // inside `runActors`. This typer models construct keywords as global names — that is its
      // existing design, not a new one — and `suspend` was simply missing, so two corpus cases that
      // run on every lane were reported as referencing an undefined name.
      "MarkupCodec", "PureMarkupCodec", "awaitClient", "generator", "suspend",
      // Route verbs of the `serve { … }` block, on the same footing as `suspend`: they are
      // meaningful only inside that construct, and this typer models construct keywords as global
      // names. Added as the COMPLETE verb set rather than the one that happened to fail — only
      // `get` appears bare in the corpus today, and adding just `get` is how this list got into
      // the state described below.
      "get", "post", "put", "delete", "patch",
      // JVM/Scala interop names used bare in examples, + `apiClients` (synthesised by the
      // frontend/graphs frontmatter machinery).
      "Thread", "collection", "apiClients",
      // stdlib `.ssc` library modules (std.mapreduce / std.cluster / std.crypto — no compiled
      // plugin) — stay hardcoded until stdlib auto-import covers them.
      //
      // THIS LIST IS A HAND-MAINTAINED COPY OF SOMETHING THE REPO ALREADY HAS MACHINE-READABLE.
      // Every module under `std/` declares an `exports:` block in its front-matter; `Node`, `MapOp`,
      // `FilterOp`, `FlatMapOp`, `Transport` and `vstack` below were all missing purely because
      // nothing had ever type-checked a program that used them. Generating the list from those
      // `exports:` blocks is the real fix — filed as `typer-prelude-list-should-be-generated-from-std-exports`.
      "HandlerRegistry", "Cluster", "ShuffleStage", "Stage", "Node",
      "MapOp", "FilterOp", "FlatMapOp",
      "runDistributed", "runDistributedShuffle", "localLoopbackCluster", "verifyEd25519",
      // std/mcp/server.ssc and std/ui/layout.ssc exports used bare in examples.
      "Transport", "vstack", "hstack", "divider", "spacer",
      // macro-expansion sentinels — appear in macro-expanded / quoted example bodies.
      "__ssc_macro__", "__ssc_quote_expr__", "__ssc_quote__", "__ssc_splice__", "Expr",
      // MIGRATED to plugin `preludeSymbols` (core-min-advanced-optin): Source → streams-plugin,
      // setHttpServerBackend → ws-plugin (both essential); Wallets/X402Client/X402/CardanoFacilitator/
      // PaymentConfig/DefaultSyncBackend/basicRequest → payments-plugin (advanced, opt-in);
      // PipelineModel → SparkBackend.
    )
    pluginBuiltins.foreach(n => s.define(Symbol(n, variadic, SymbolKind.Def)))
    // core-min-prelude-spi: typed prelude symbols contributed by plugins. Defined with the
    // declared `tpe` (parsed back from its `SType.show` string) so `ssc check` type-checks calls
    // to plugin intrinsics; a symbol carrying `tpe == "Any"` degrades to the names-only behaviour.
    preludeSymbols.foreach { es =>
      s.define(Symbol(
        es.name,
        scalascript.artifact.InterfaceScope.parseSType(es.tpe),
        scalascript.artifact.InterfaceScope.parseKind(es.kind)))
    }
    s

  // ─── Pre-declaration pass ──────────────────────────────────────────────────
  // Collects every top-level name defined in the module (across all sections)
  // into `scope` as SType.Any before the real type-check runs.  This lets the
  // type-checker resolve cross-section forward references without false
  // "undefined name" errors.  The full typeCheckBlock pass overwrites each
  // entry with the correctly-inferred type.

  private def predeclareModuleNames(module: Module, scope: Scope): Unit =
    var sqlBlockCounter = 0
    module.sections.foreach(predeclareSection(_, scope, sqlBlockCounter, { n => sqlBlockCounter = n }))
    // Frontmatter `apiClients:` declares typed HTTP-route client namespaces,
    // referenced in code as `Items.list()` / `Chat.send()`. Register each name so
    // `ssc check` resolves it (endpoint calls degrade to Any — metadata-only today).
    module.manifest.foreach(_.apiClients.foreach { c =>
      scope.define(Symbol(c.name, SType.Any, SymbolKind.Object))
    })

  private def predeclareSection(
      section: Section,
      scope: Scope,
      sqlStart: Int,
      updateSqlCount: Int => Unit
  ): Unit =
    var sqlCount = sqlStart
    // Declare section-identifier-derived name (mirrors JvmGen/SparkGen sectionIdent).
    sectionIdent(section.heading.text).foreach { id =>
      scope.define(Symbol(id, SType.Any, SymbolKind.Object))
    }
    section.content.foreach {
      case cb: Content.CodeBlock if cb.lang == "sql" =>
        // Each sql block produces a `_sqlBlock_<n>` binding visible to later sections.
        scope.define(Symbol(s"_sqlBlock_$sqlCount", SType.Any, SymbolKind.Val))
        sqlCount += 1
        updateSqlCount(sqlCount)
      case cb: Content.CodeBlock if cb.tree.isDefined =>
        // AN IMPORT WRITTEN INSIDE A FENCE IS STILL AN IMPORT. `[a, b](path.ssc)` is the import form
        // wherever it stands, but only a link OUTSIDE a fence becomes a `Content.Import`; inside one
        // it is parsed as code and its names were never bound. Four corpus cases reference names
        // imported that way — Transport in the three MCP servers, square/cube in
        // native-import-in-fence — and `check` reported every one as an undefined name while the
        // programs run correctly on every lane. Same defect the CLI's own import discovery has
        // (BUGS js-jvm-codegen-in-fence-imports-not-followed); this is the typer's half of it.
        inFenceImportNames(cb.source).foreach(n =>
          scope.define(Symbol(n, SType.Any, SymbolKind.Val)))
        cb.tree.foreach { node =>
          ScalaNode.fold(node) {
            case Source(stats)     => stats.foreach(predeclareStat(_, scope))
            case Term.Block(stats) => stats.foreach(predeclareStat(_, scope))
            case _                 => ()
          }
        }
      case imp: Content.Import =>
        imp.bindings.foreach(b => scope.define(Symbol(b.name, SType.Any, SymbolKind.Val)))
      case _ => ()
    }
    var subsqlCount = sqlCount
    section.subsections.foreach { sub =>
      predeclareSection(sub, scope, subsqlCount, { n => subsqlCount = n })
    }
    updateSqlCount(subsqlCount)

  /** The Markdown-link import form, matched on a WHOLE line — the same shape
    * `NativeSourceClosure` scans for, kept deliberately identical so the two do not drift about
    * what counts as an import. A line that merely CONTAINS a link is prose; only a line that IS one
    * binds names. */
  private val InFenceImportLine = """^\s*\[([^\]]+)\]\([^)]+\.ssc\)\s*$""".r
  private def inFenceImportNames(src: String): List[String] =
    src.linesIterator.collect { case InFenceImportLine(names) => names }
      .flatMap(_.split(",").iterator.map(_.trim).filter(_.nonEmpty))
      .toList

  private def sectionIdent(text: String): Option[String] =
    val parts = text.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val head = parts.head
      val tail = parts.tail.map(p => s"${p.head.toUpper}${p.tail}")
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  private def predeclareStat(stat: scala.meta.Tree, scope: Scope): Unit = stat match
    case Defn.Val(_, pats, tpeOpt, _) =>
      val declared = tpeOpt.map(typeAnnotToSType)
      pats.foreach {
        case Pat.Var(n) => scope.define(Symbol(n.value, declared.getOrElse(SType.Any), SymbolKind.Val))
        case pat        => bindPatVars(pat, scope, declared)
      }
    case Defn.Var.After_4_7_2(_, pats, tpeOpt, _) =>
      val declared = tpeOpt.map(typeAnnotToSType)
      pats.foreach {
        case Pat.Var(n) => scope.define(Symbol(n.value, declared.getOrElse(SType.Any), SymbolKind.Var, mutable = true))
        case pat        => bindPatVars(pat, scope, declared)
      }
    case d: Defn.Def =>
      // One arrow per clause — see `defClauseSTypes`. This is the pre-pass that makes forward
      // references resolve, so it must build the SAME shape as the real `Defn.Def` case below or a
      // curried def would type one way when called before its definition and another when called
      // after.
      val (retType, effects) = parseDeclReturnType(d.decltpe)
      scope.define(Symbol(
        d.name.value,
        curriedFnType(defClauseSTypes(d), retType.getOrElse(SType.Any), effects),
        SymbolKind.Def
      ))
    case d: Defn.Class =>
      val fields = classFieldTypes(d)
      classFields(d.name.value) = fields
      typeCtorKinds(d.name.value) = tparamKinds(d.tparamClause)
      classParents(d.name.value) = parentNamesOf(d.templ)
      scope.define(Symbol(
        d.name.value,
        SType.Function(fields.map(_._2), SType.named0(d.name.value)),
        SymbolKind.Class
      ))
    case d: Defn.Object => scope.define(Symbol(d.name.value, SType.named0(d.name.value), SymbolKind.Object))
    case d: Defn.Trait  =>
      typeCtorKinds(d.name.value) = tparamKinds(d.tparamClause)
      classParents(d.name.value) = parentNamesOf(d.templ)
      scope.define(Symbol(d.name.value, SType.named0(d.name.value), SymbolKind.Trait))
    case d: Defn.Enum   =>
      val enumType = SType.named0(d.name.value)
      typeCtorKinds(d.name.value) = tparamKinds(d.tparamClause)
      classParents(d.name.value) = parentNamesOf(d.templ)
      scope.define(Symbol(d.name.value, enumType, SymbolKind.Enum))
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseSTypes = ec.ctor.paramClauses.flatMap(_.values).map { p =>
            p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
          }.toList
          val caseType =
            if caseSTypes.isEmpty then enumType
            else SType.Function(caseSTypes, enumType)
          scope.define(Symbol(ec.name.value, caseType, SymbolKind.Val))
        case _                 => ()
      }
    case d: Defn.Given  =>
      val n = d.name.value
      if n.nonEmpty then scope.define(Symbol(n, SType.Any, SymbolKind.Val))
    case i: Import =>
      i.importers.foreach { importer =>
        importer.importees.foreach {
          case Importee.Name(n)        => scope.define(Symbol(n.value, SType.Any, SymbolKind.Val))
          case Importee.Rename(_, n)   => scope.define(Symbol(n.value, SType.Any, SymbolKind.Val))
          case Importee.Wildcard()     => () // can't know names; ignored
          case _                       => ()
        }
      }
    case _ => ()

  // ──────────────────────────────────────────────────────────────────────────

  private def typeCheckSection(section: Section, parent: Scope): TypedSection =
    val scope = parent.child(section.heading.text)
    val defs  = section.content.flatMap(typeCheckContent(_, scope))
    TypedSection(
      name        = section.heading.text,
      level       = section.heading.level,
      definitions = defs,
      subsections = section.subsections.map(s => typeCheckSection(s, scope))
    )

  private def typeCheckContent(content: Content, scope: Scope): Option[TypedDef] =
    content match
      case cb: Content.CodeBlock =>
        val isScala = Lang.isParseable(cb.lang)
        if isScala && cb.tree.isEmpty then
          errors += TypeError(s"Failed to parse ${cb.lang} code block", None)
          Some(TypedDef.CodeBlock(cb.lang, parsed = false, Nil))
        else if isScala then
          val blockDefs = typeCheckBlock(cb, scope)
          Some(TypedDef.CodeBlock(cb.lang, parsed = true, blockDefs))
        else
          Some(TypedDef.CodeBlock(cb.lang, parsed = false, Nil))

      case imp: Content.Import =>
        imp.bindings.foreach { b =>
          scope.define(Symbol(b.name, SType.Any, SymbolKind.Val))
        }
        Some(TypedDef.Import(imp.path, imp.bindings.map(_.name)))

      case _ => None

  /** Walk scalameta statements in a code block, collect definitions into scope,
   *  and return a summary of what was found. */
  private def typeCheckBlock(cb: Content.CodeBlock, scope: Scope): List[DefSummary] =
    val summaries = ListBuffer[DefSummary]()
    if Lang.isScalaScript(cb.lang) then
      cb.tree.foreach(checkPlatformTypeBan)
    cb.tree.foreach { node =>
      ScalaNode.fold(node) {
        case Source(stats)     => stats.foreach(s => checkStat(s, scope, summaries))
        case Term.Block(stats) => stats.foreach(s => checkStat(s, scope, summaries))
        case t: Term           => val _ = inferType(t, scope)
        case _                 => ()
      }
    }
    // v1.12.1: run EffectAnalysis verifier — cross-check declared effect rows
    // (from function type annotations) against name-reachability analysis.
    cb.tree.foreach { node =>
      ScalaNode.fold(node) { tree =>
        val trees = tree match
          case s: Source     => List(s)
          case b: Term.Block => List(b)
          case other         => List(other)
        val analysisResult0 = scalascript.transform.EffectAnalysis.analyze(trees)
        // Discharge-aware refinement: a function that fully HANDLES the effects it
        // performs (e.g. `def capture(): Int => Int = handle { … Eff.op() … } { case
        // Eff.op(k) => k }`) does not leak them and correctly declares no effect row.
        // `analyze.effectfulFuns` is a coarse name-reachability set that would mis-flag
        // such a function; the verifier consults the handle-scoped `leakingFuns` instead.
        // (`effectfulFuns` itself is left intact — the codegens consume it as-is.)
        val leaking = scalascript.transform.EffectAnalysis.leakingFuns(trees, analysisResult0.effectOps)
        val analysisResult = analysisResult0.copy(
          effectfulFuns = analysisResult0.effectfulFuns intersect leaking
        )
        // Include ALL defs (even those with no declared effects) so the verifier
        // can warn when an effectful function declares no row.
        val declaredEffects: Map[String, Set[String]] = summaries.collect {
          case DefSummary(name, SymbolKind.Def, SType.Function(_, _, SType.EffectRow(_, ops)), _, _) =>
            name -> ops.map(_.name)
        }.toMap
        if declaredEffects.nonEmpty || analysisResult.effectfulFuns.nonEmpty then
          scalascript.transform.EffectAnalysis.verify(declaredEffects, analysisResult, asErrors = false)
            .foreach(msg => errors += TypeError(msg, None))
      }
    }
    summaries.toList

  /** Banned top-level package prefixes in `scalascript` blocks.
   *  Any import that resolves to one of these roots is a platform-type
   *  violation (E_PlatformType).  The ban does not apply to `scala` blocks
   *  or to `@jvm("...")` annotation strings.
   *  See specs/backend-specific-blocks.md §5.1. */
  private val platformTypeBannedPrefixes: Set[String] =
    Set("java", "javax", "sun", "com.sun")

  private def checkPlatformTypeBan(node: ScalaNode): Unit =
    val stats: List[scala.meta.Tree] = node.tree match
      case Source(ss)     => ss.toList
      case Term.Block(ss) => ss.toList
      case single         => List(single)
    stats.foreach {
      case Import(importers) =>
        importers.foreach { importer =>
          val path = showTermPath(importer.ref)
          if platformTypeBannedPrefixes.exists(p => path == p || path.startsWith(p + ".")) then
            errors += TypeError(
              s"E_PlatformType: `$path` is a JVM-only package and cannot appear in ScalaScript code. " +
              s"Use `std.*` from the standard library, or isolate this code in a `scala` fenced block " +
              s"(see specs/backend-specific-blocks.md §1).",
              None
            )
        }
      case _ => ()
    }

  private def checkStat(
      stat: scala.meta.Tree,
      scope: Scope,
      out: ListBuffer[DefSummary]
  ): Unit = stat match

    // val name: T = rhs
    case Defn.Val(_, pats, tpeOpt, rhs) =>
      val rhsType  = inferType(rhs, scope)
      val declType = tpeOpt.map(typeAnnotToSType).getOrElse(rhsType)
      checkAssignable(rhsType, declType, rhs.pos)
      pats.foreach {
        case Pat.Var(name) =>
          scope.define(Symbol(name.value, declType, SymbolKind.Val))
          out += DefSummary(
            name.value,
            SymbolKind.Val,
            declType,
            Nil,
            Some(declaredOrInferredEvidence(declType, tpeOpt.nonEmpty, name.pos, "val summary"))
          )
        case other =>
          // Tuple/extractor patterns: `val (l, r) = ...`, `val Some(x) = ...`
          bindPatVars(other, scope, Some(declType)).foreach { (name, tpe) =>
            out += DefSummary(
              name,
              SymbolKind.Val,
              tpe,
              Nil,
              Some(declaredOrInferredEvidence(tpe, tpeOpt.nonEmpty, other.pos, "pattern-bound val summary"))
            )
          }
      }

    // var name: T = rhs
    case Defn.Var.After_4_7_2(_, List(Pat.Var(name)), tpeOpt, rhs) =>
      val rhsType  = inferType(rhs, scope)
      val declType = tpeOpt.map(typeAnnotToSType).getOrElse(rhsType)
      checkAssignable(rhsType, declType, rhs.pos)
      scope.define(Symbol(name.value, declType, SymbolKind.Var, mutable = true))
      out += DefSummary(
        name.value,
        SymbolKind.Var,
        declType,
        Nil,
        Some(declaredOrInferredEvidence(declType, tpeOpt.nonEmpty, name.pos, "var summary"))
      )

    // def name(params...): T = body
    case d: Defn.Def =>
      val allParamVals = d.paramClauseGroups
        .flatMap(_.paramClauses)
        .flatMap(_.values)
        .toList
      val paramSTypes = allParamVals
        .map(p => p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any))
      // The FLAT list above still feeds `DefSummary.paramTypes` and the summary's `fnType`, which
      // downstream consumers (the effect verifier, the artifact descriptors) read arity-wise. The
      // CLAUSE list below is what a call site sees — see `defClauseSTypes`.
      val clauseSTypes = defClauseSTypes(d)
      // Type-check body in a child scope with params bound, so we can infer
      // the return type when no explicit annotation is given.
      val bodyScope = scope.child(d.name.value)
      d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).foreach { p =>
        val pt = p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
        bodyScope.define(Symbol(p.name.value, pt, SymbolKind.Param))
      }
      // Prefer the declared return type when present; otherwise infer from
      // the body.  Inference is signature-level only (literals, var refs,
      // simple arithmetic, blocks, if/else) — anything richer falls back to
      // SType.Any.
      // v1.12.1: parse `def f(): T ! Eff` — the `!` infix in the return type
      // carries the effect row; extract it before falling through to typeAnnotToSType.
      val (declaredRet, declaredEffects) = parseDeclReturnType(d.decltpe)
      // `extern def` compiles to Defn.Def with body Term.Name("__extern__").
      // Treat as an opaque declaration: use declared return type, skip body check.
      val isExternDef = d.body match { case Term.Name("__extern__") | Term.Name("__ssc_macro__") | Term.Name("__ssc_quote_expr__") => true; case _ => false }
      // Allow self-recursion: bind the function name in bodyScope before
      // typing the body so `def fib(n) = fib(n-1)` resolves correctly.
      bodyScope.define(Symbol(d.name.value,
        curriedFnType(clauseSTypes, declaredRet.getOrElse(SType.Any), declaredEffects),
        SymbolKind.Def))
      val retType =
        if isExternDef then declaredRet.getOrElse(SType.Any)
        else
          val bodyType = inferType(d.body, bodyScope)
          val rt = declaredRet.getOrElse(bodyType)
          declaredRet.foreach { declared =>
            if declared != SType.Any then
              checkAssignable(bodyType, declared, d.body.pos)
          }
          rt
      val fnType = SType.Function(paramSTypes, retType, declaredEffects)
      // A PARENLESS def is INVOKED AT ITS MENTION, so at a use site its type is its RESULT, not
      // `() => result`. `def mkAdd: Int => Int = x => x + 1` makes `mkAdd` an `Int => Int`, and
      // typing it `() => Int => Int` reported `useIt(mkAdd)` as a mismatch on a program that runs
      // correctly on every lane. NOTE the distinction that matters: NO parameter clause at all is
      // parenless; an explicit `()` (`def z(): Int`) is a zero-argument FUNCTION and keeps its
      // Function type, so `z(5)` stays an over-application.
      // Invisible until argument inference reached inside `println(...)`
      // (tests/BUGS.md v1-check-never-looks-inside-a-println).
      val isParenless = d.paramClauseGroups.isEmpty
      val siteType    = if isParenless then retType else curriedFnType(clauseSTypes, retType, declaredEffects)
      scope.define(Symbol(d.name.value, siteType, SymbolKind.Def))
      val hasMissingParamTypes = allParamVals.exists(_.decltpe.isEmpty)
      val hasDeclaredSignature = declaredRet.nonEmpty && !hasMissingParamTypes
      out += DefSummary(
        d.name.value,
        SymbolKind.Def,
        fnType,
        paramSTypes,
        Some(declaredOrInferredEvidence(fnType, hasDeclaredSignature, d.name.pos, "def summary"))
      )
      // Collect @deprecated / @experimental annotations after body check to avoid
      // false-positive warnings on recursive self-calls within the function body.
      d.mods.foreach {
        case Mod.Annot(init) =>
          val annotName = init.tpe match
            case Type.Name(n)                 => n
            case Type.Select(_, Type.Name(n)) => n
            case other                        => other.syntax.split('.').lastOption.getOrElse("")
          annotName match
            case "deprecated" =>
              val sinceArg = init.argClauses.flatMap(_.values).collectFirst {
                case Term.Assign(Term.Name("since"), Lit.String(v)) => v
              }
              val msgArg = init.argClauses.flatMap(_.values).collectFirst {
                case Lit.String(s) => s
              }
              val msg = msgArg.map(m => s": $m").getOrElse("") +
                sinceArg.map(s => s" (since $s)").getOrElse("")
              deprecatedDefs(d.name.value) = msg
            case "experimental" =>
              val notice = init.argClauses.headOption
                .flatMap(_.values.collectFirst { case Lit.String(s) => s })
                .map(m => s": $m").getOrElse("")
              experimentalDefs(d.name.value) = notice
            case _ => ()
        case _ => ()
      }

    // class Name(params...)
    case d: Defn.Class =>
      val params = d.ctor.paramClauses.flatMap(_.values)
      val paramSTypes = params.map { p =>
        p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
      }.toList
      val classType = SType.named0(d.name.value)
      val ctorType  = SType.Function(paramSTypes, classType)
      scope.define(Symbol(d.name.value, ctorType, SymbolKind.Class))
      // Index constructor params by name → type so [[inferType]] on
      // `Term.Select(qual, field)` can resolve a field access on a
      // value of this class type.  case classes expose every ctor
      // param as a public field; plain classes only when declared
      // `val` / `var` — both shapes carry decltpe at the same param
      // index in scalameta, so the table is built the same way.
      val fieldEntries = params.zip(paramSTypes).map { (p, t) => p.name.value -> t }.toList
      classFields(d.name.value) = fieldEntries
      // DefSummary records the *constructor* signature so consumers of the
      // `.scim` interface see `(Int, String) => Foo` for `case class Foo(x, y)`,
      // not just `Foo`.  This matches what the typer stores in the scope.
      out += DefSummary(
        d.name.value,
        SymbolKind.Class,
        ctorType,
        paramSTypes,
        Some(declaredOrUnknownEvidence(ctorType, d.name.pos, "class constructor declaration"))
      )

    // object Name
    case d: Defn.Object =>
      val objType = SType.named0(d.name.value)
      scope.define(Symbol(d.name.value, objType, SymbolKind.Object))
      out += DefSummary(
        d.name.value,
        SymbolKind.Object,
        objType,
        Nil,
        Some(TypeEvidence.declared(objType, posToSpan(d.name.pos), Some("object declaration")))
      )

    // enum Name
    case d: Defn.Enum =>
      val enumType = SType.named0(d.name.value)
      scope.define(Symbol(d.name.value, enumType, SymbolKind.Enum))
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseSTypes = ec.ctor.paramClauses.flatMap(_.values).map { p =>
            p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
          }.toList
          val caseType =
            if caseSTypes.isEmpty then enumType
            else SType.Function(caseSTypes, enumType)
          scope.define(Symbol(ec.name.value, caseType, SymbolKind.Val))
        case _ => ()
      }
      out += DefSummary(
        d.name.value,
        SymbolKind.Enum,
        enumType,
        Nil,
        Some(TypeEvidence.declared(enumType, posToSpan(d.name.pos), Some("enum declaration")))
      )

    // type Name[A, B] = T  — compile-time only; erased at runtime
    // `opaque type UserId = String` → Defn.Type with Mod.Opaque() in mods
    case d: Defn.Type =>
      val typeName = d.name.value
      val isOpaque = d.mods.exists(_.isInstanceOf[Mod.Opaque])
      if isOpaque then
        // Opaque types are nominal — NOT transparent to the underlying type
        // outside the defining scope (MVP: single-zone, no transparency inside).
        val opaqueNominalType = SType.named0(typeName)
        opaqueTypes += typeName
        scope.define(Symbol(typeName, opaqueNominalType, SymbolKind.Type))
        out += DefSummary(
          typeName,
          SymbolKind.Type,
          opaqueNominalType,
          Nil,
          Some(TypeEvidence.declared(opaqueNominalType, posToSpan(d.name.pos), Some("opaque type declaration")))
        )
      else
        val typeParamNames = d.tparamClause.values.map(_.name.value).toList
        // Parse the RHS in a temporary context where the type params are known as
        // plain named types (so `type Opt[A] = Option[A]` resolves `A` to `SType.Named("A")`).
        val rhsSType = typeAnnotToSType(d.body)
        // Prevent trivially recursive aliases (name appears in its own rhs at top level).
        if isDirectlyRecursive(typeName, rhsSType) then
          errors += TypeError(s"Recursive type alias: $typeName", None)
        else
          typeAliases(typeName) = (typeParamNames, rhsSType)
          // Kind of the alias as a type constructor: a type-lambda rhs takes the
          // lambda's params' kinds (`type Fix = [F[_]] =>> F[Int]` → [1]); a plain
          // parameterised alias takes its own params' kinds (`type Opt[A]` → [0]).
          typeCtorKinds(typeName) = d.body match
            case Type.Lambda.After_4_6_0(lamTparams, _) => tparamKinds(lamTparams)
            case _                                      => tparamKinds(d.tparamClause)
          scope.defineType(typeName, TypeScheme(Nil, rhsSType))
          out += DefSummary(
            typeName,
            SymbolKind.Type,
            rhsSType,
            Nil,
            Some(declaredOrUnknownEvidence(rhsSType, d.name.pos, "type alias declaration"))
          )

    // given declarations — `given listFunctor: Functor[List[Int]] with { ... }`
    case d: Defn.Given =>
      val n = d.name.value
      if n.nonEmpty then
        scope.define(Symbol(n, SType.Any, SymbolKind.Val))
        out += DefSummary(
          n,
          SymbolKind.Val,
          SType.Any,
          Nil,
          Some(TypeEvidence.unknown(SType.Any, posToSpan(d.name.pos), Some("given declaration has no supported type evidence")))
        )

    // Top-level expressions
    case t: Term => val _ = inferType(t, scope)

    case _ => ()

  /** Very lightweight type inference — returns an SType label for a term.
   *  Does not attempt full HM inference; focuses on literals and known names.
   *  wide-jit C-1: the result is recorded into `_nodeTypes` (identity-keyed) by the
   *  `inferType` wrapper below, so every node reached during inference is captured. */
  private def inferType(term: scala.meta.Tree, scope: Scope): SType =
    val t = inferTypeImpl(term, scope)
    _nodeTypes.put(term, t)
    t
  private def inferTypeImpl(term: scala.meta.Tree, scope: Scope): SType = term match
    case Lit.Int(_)     => SType.Int
    case Lit.Long(_)    => SType.Long
    case Lit.Double(_)  => SType.Double
    case Lit.Float(_)   => SType.Double
    case Lit.String(_)  => SType.String
    case Lit.Boolean(_) => SType.Boolean
    case Lit.Char(_)    => SType.Char
    case Lit.Unit()     => SType.Unit
    case Lit.Null()     => SType.Null
    case Term.Tuple(args) =>
      SType.Tuple(args.map(inferType(_, scope)).toList)

    case t @ Term.Name(name) =>
      scope.lookup(name) match
        case Some(sym) =>
          // Emit lifecycle warnings for deprecated / experimental defs at use sites.
          deprecatedDefs.get(name).foreach { suffix =>
            emitWarning(s"$name is deprecated$suffix", posToSpan(t.pos))
          }
          experimentalDefs.get(name).foreach { suffix =>
            emitWarning(s"$name is experimental$suffix", posToSpan(t.pos))
          }
          // arch-lib-p2 — cross-package @internal access error.
          if internalImportedNames.contains(name) then
            errors += TypeError(
              s"`$name` is marked @internal in the imported library and cannot be accessed from outside that library.",
              posToSpan(t.pos)
            )
          sym.tpe
        case None      =>
          // Strict mode: record a diagnostic for references to identifiers
          // that are not in any scope (the consumer's defs, any imported
          // `.scim` interface, or the builtin prelude).  Permissive mode
          // (the default) silently returns `SType.Any` to preserve
          // historical behaviour for callers like `ssc compile`.
          //
          // Conservative scoping rules — to avoid false-positives we only
          // flag a name that looks like a top-level term identifier:
          //  * starts with a letter or underscore (not an operator),
          //  * contains no `.` (selects go through `Term.Select`, not here),
          //  * is not a single-underscore placeholder.
          // …and NOT inside a variadic argument. See `variadicArgDepth`.
          if strict && variadicArgDepth == 0 && isFlaggableName(name) then
            errors += TypeError(
              s"Reference to undefined name: $name",
              posToSpan(t.pos)
            )
          SType.Any

    // v1.12.1: handle[Eff](body) { cases } — discharge named effect from body type.
    // Curried form: Term.Apply(Term.Apply(Term.ApplyType("handle", [Eff]), body), cases)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Name("handle"), targs),
          bodyClause
        ),
        _
      ) =>
      val effName  = targs.headOption.collect { case Type.Name(n) => n }.getOrElse("")
      val bodyType = bodyClause.values.headOption.map(inferType(_, scope)).getOrElse(SType.Any)
      dischargeEffect(bodyType, effName)

    // handle[Eff](body) — single-apply form (body is the sole argument, no case block yet)
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("handle"), targs),
        argClause
      ) =>
      val effName  = targs.headOption.collect { case Type.Name(n) => n }.getOrElse("")
      val bodyType = argClause.values.headOption.map(inferType(_, scope)).getOrElse(SType.Any)
      dischargeEffect(bodyType, effName)

    // `direct[M] { x = expr; … }` — do-notation. `x = expr` is a monadic BIND that INTRODUCES `x`,
    // not an assignment to an existing name, so the block's binders must be in scope before its
    // statements are inferred. Without this the three direct-syntax corpus cases reported their own
    // binders as undefined names while running correctly on every lane.
    //
    // Scoped to the `direct` call rather than made a global rule: "an assignment to an unknown name
    // introduces it" would be true here and wrong everywhere else, and would quietly disable the
    // undefined-name check that `ssc check` turns on deliberately (strict = true).
    case Term.Apply.After_4_6_0(fun, argClause) if isDirectCall(fun) =>
      val blockScope = scope.child("direct")
      argClause.values.foreach {
        case Term.Block(stats) =>
          stats.foreach {
            case Term.Assign(Term.Name(n), _) =>
              blockScope.define(Symbol(n, SType.Any, SymbolKind.Val))
            case _ => ()
          }
        case _ => ()
      }
      argClause.values.foreach(a => inferType(a, blockScope))
      SType.Any

    case Term.Apply.After_4_6_0(fun, argClause) =>
      inferKnownApply(fun, argClause.values.toList, scope) match
        case Some(tpe) => tpe
        case None =>
          inferCallableType(fun, scope) match
            case SType.Function(paramTypes, retType, _) =>
              val args = argClause.values
              // Only check arity for non-variadic functions.
              // Variadic: represented as single SType.Any param in our prelude.
              val isVariadic = paramTypes == List(SType.Any)
              // Underflow is permitted — trailing parameters may have defaults that
              // the lightweight typer does not track. Only flag overflow.
              // `paramTypes.nonEmpty` was part of this condition and exempted exactly the shape the
              // runtime is loudest about: `def z(): Int = 1; z(5)` has an empty parameter list, so
              // the overflow check was skipped, `check` answered OK, and `run --v1` then died with
              // `Not callable: 1`. A parenless `def f = …` is NOT this case — it types as its result
              // and never reaches here — so the exemption protected nothing it needed to.
              if !isVariadic && args.length > paramTypes.length then
                errors += TypeError(
                  s"Wrong number of arguments: expected ${paramTypes.length}, got ${args.length}",
                  posToSpan(argClause.pos)
                )
              // Infer EVERY argument, variadic included. `inferType` on an argument is what finds
              // the arity and type errors nested inside it, and it used to run only in the
              // non-variadic branch — so nothing written inside a `println(...)` was ever checked,
              // which is essentially everything anyone writes.
              args.zip(paramTypes.padTo(args.length, SType.Any)).foreach { (arg, expected) =>
                val actual = if isVariadic then withinVariadicArg(inferType(arg, scope)) else inferType(arg, scope)
                if !isVariadic then checkAssignable(actual, expected, arg.pos)
              }
              retType
            // Applying something that is NOT a function. Only reportable now that clauses are
            // modelled: while they were flattened, `two(1)` on `def two(a: Int)(b: Int): Int` typed
            // as `Int`, so this arm could not tell an over-application from a legal curried call —
            // it rejected four correct corpus cases and was reverted. With one arrow per clause
            // `two(1)` is a Function and only a genuine over-application reaches here.
            //
            // Restricted to types this language cannot apply AT ALL. `String`, `List`, `Map`,
            // `Array` and friends are all callable (`s(0)`, `xs(0)`, `m(k)`), and a `Named` this
            // typer merely does not know about may have an `apply` — so the set is the primitives
            // whose application is what the runtime reports as `Not callable: <value>`.
            case notCallable if isNeverApplicable(notCallable) =>
              errors += TypeError(
                s"Not a function: ${notCallable.show} cannot be applied to arguments",
                posToSpan(argClause.pos)
              )
              SType.Any
            case _ => SType.Any

    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val lhsType  = inferType(lhs, scope)
      val rhsType  = argClause.values.headOption.map(inferType(_, scope)).getOrElse(SType.Any)
      if op.value == "->" && argClause.values.length == 1 then
        SType.Tuple(List(lhsType, rhsType))
      else checkBinaryOp(lhsType, op.value, rhsType, op.pos)

    case Term.Block(stats) =>
      val blockScope = scope.child("<block>")
      var lastType: SType = SType.Unit
      stats.foreach { s =>
        s match
          case t: Term =>
            lastType = inferType(t, blockScope)
          case stat =>
            val dummyOut = ListBuffer[DefSummary]()
            checkStat(stat, blockScope, dummyOut)
            lastType = SType.Unit
      }
      lastType

    case t: Term.If =>
      val condType = inferType(t.cond, scope)
      if condType != SType.Boolean && condType != SType.Any then
        errors += TypeError(
          s"If condition must be Boolean, found ${condType.show}",
          posToSpan(t.cond.pos)
        )
      val thenType = inferType(t.thenp, scope)
      val elseType = inferType(t.elsep, scope)
      if thenType == elseType then thenType else SType.Any

    case Term.Interpolate(Term.Name(p), _, _) if p == "s" || p == "f" || p == "md" =>
      SType.String

    case Term.Interpolate(Term.Name(p), _, _) =>
      scalascript.compiler.plugin.InterpolatorRegistry.lookup(p)
        .map(impl => SType.named0(impl.returnTypeName))
        .getOrElse(SType.String)

    // ── Strict-mode check for Select chains rooted at imported modules ──────
    //
    // The existing `Term.Name` branch flags references to undefined top-level
    // names.  This branch extends the same check to dotted selects of any
    // depth — a chain `a.b.c.…` walks the qualifier recursively and emits
    // exactly one diagnostic at the deepest point where resolution breaks.
    //
    // Conservative — we only flag chains whose root is a known imported
    // module (alias key in `importedInterfaces`).  This avoids false-
    // positives for:
    //   - method calls on values whose type is `Any` (interpreter intrinsics);
    //   - builtins like `1.toString` / `"x".length` where the typer knows
    //     the receiver type but not its method set;
    //   - same-module values (`val x = 42; x.toString`);
    //   - deep selects whose sub-namespaces were not populated by the
    //     interface extractor (see `ExportedSymbol.nested` — extractor work
    //     is deferred; chains that hit a sub-namespace with empty `nested`
    //     fall back to permissive).
    case t @ Term.Select(_, Term.Name(_)) if strict =>
      checkSelectStrict(t, scope)
      // Strict mode keeps the previous "always Any" return so the
      // qualifier doesn't get walked twice (once by `checkSelectStrict`
      // via `resolveQualifier`, once by [[inferSelectType]] via
      // `inferType`) and double-record the same diagnostic.  Field
      // inference is purely for `.scim` granularity, which runs in
      // permissive mode.
      SType.Any

    case t: Term.Select           => inferSelectType(t, scope)

    // `new Foo(...)` / `new Foo[T](...)` — infer the named-type result
    // from the `Init`'s type annotation.  Without this, every constructor
    // call produced `SType.Any` in the .scim, hiding the most common
    // case-class result.  Tier-5 .scim granularity push (Open question #1).
    case Term.New(init) => typeAnnotToSType(init.tpe)
    case Term.NewAnonymous(tpl) =>
      tpl.inits.headOption.map(init => typeAnnotToSType(init.tpe))
        .getOrElse(SType.Any)

    // `(x: A, y: B) => body` — if every parameter has a type annotation
    // AND the body's type is known, produce `SType.Function(...)`.
    // Otherwise fall back to `Any`.  Tier-5 .scim granularity push.
    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values
      val paramsOpt =
        if params.forall(_.decltpe.isDefined) then
          Some(params.map(p => typeAnnotToSType(p.decltpe.get)))
        else None
      paramsOpt match
        case Some(paramTypes) =>
          // Body inference under a scope where params are bound.  Avoids
          // false-positive strict diagnostics for the params themselves.
          val bodyScope = scope.child("<lambda>")
          params.zip(paramTypes).foreach { (p, pt) =>
            bodyScope.define(Symbol(p.name.value, pt, SymbolKind.Val))
          }
          val retType = inferType(body, bodyScope)
          SType.Function(paramTypes, retType)
        case None => SType.Any

    case _: Term.PartialFunction  => SType.Any
    case _: Term.AnonymousFunction => SType.Any

    // `scrutinee match { case … => body }` — LUB across arms. Same
    // policy as if/else: when every arm body infers to the same type,
    // propagate it; any divergence collapses to Any. Pattern variables
    // are bound in a per-case scope, using the scrutinee type for simple
    // tuple / typed / extractor patterns when available.
    case t: Term.Match =>
      val scrutineeType = inferType(t.expr, scope)
      val armTypes = t.casesBlock.cases.map { c =>
        val caseScope = scope.child("<case>")
        bindPatVars(c.pat, caseScope, Some(scrutineeType))
        inferType(c.body, caseScope)
      }
      armTypes.headOption match
        case None      => SType.Any
        case Some(t0)  => if armTypes.forall(_ == t0) then t0 else SType.Any

    case _                        => SType.Any

  private def bindPatVars(
      pat: scala.meta.Tree,
      scope: Scope,
      expected: Option[SType]
  ): List[(String, SType)] = pat match
    case Pat.Var(n) =>
      val tpe = expected.getOrElse(SType.Any)
      scope.define(Symbol(n.value, tpe, SymbolKind.Val))
      List(n.value -> tpe)
    case p: Pat.Extract =>
      val argTypes = expectedExtractArgTypes(p.fun, expected)
      p.argClause.values.zipWithIndex.flatMap { (arg, idx) =>
        bindPatVars(arg, scope, argTypes.lift(idx).flatten)
      }.toList
    case Pat.ExtractInfix.After_4_6_0(lhs, op, argClause) =>
      val listElem = collectionElementType(expected.getOrElse(SType.Any))
      val lhsExpected = if op.value == "::" then listElem else None
      val rhsExpected = if op.value == "::" then expected else None
      bindPatVars(lhs, scope, lhsExpected) ++
        argClause.values.flatMap(bindPatVars(_, scope, rhsExpected)).toList
    case Pat.Tuple(args) =>
      val elemTypes = expected match
        case Some(SType.Tuple(elems)) => elems.map(Some(_))
        case _                       => Nil
      args.zipWithIndex.flatMap { (arg, idx) =>
        bindPatVars(arg, scope, elemTypes.lift(idx).flatten)
      }.toList
    case p: Pat.Typed =>
      bindPatVars(p.lhs, scope, Some(typeAnnotToSType(p.rhs)))
    case p: Pat.Bind =>
      bindPatVars(p.lhs, scope, expected) ++ bindPatVars(p.rhs, scope, expected)
    // A CAPITALISED bare name in pattern position is a stable-identifier pattern — it MATCHES
    // against something that must already exist, unlike a lowercase name, which BINDS. Scala 3
    // rejects `case Nope =>` with `not found: value Nope`; this front resolved nothing and fell
    // through to `case _ => Nil` below, so the pattern simply never matched. Measured across the
    // lanes: int and native printed the fallback arm silently at exit 0, and js emitted the name
    // verbatim so it became a run-time `ReferenceError` — three lanes, three answers, no diagnostic
    // (BUGS.md `an-undefined-name-in-a-pattern-means-three-different-things`).
    //
    // The wording matches v3's `unknown constructor '…' in a pattern`, which already reports this,
    // so the two fronts say the same sentence about the same program rather than each inventing one.
    //
    // The capitalisation rule itself was never the defect — the lowercase control binds correctly on
    // every lane. What was missing is the RESOLUTION check behind it, which is why this looks only
    // at a name the parser has already decided is not a binder.
    case n: Term.Name if n.value.headOption.exists(_.isUpper) =>
      if scope.lookup(n.value).isEmpty && scope.lookupType(n.value).isEmpty then
        errors += TypeError(s"unknown constructor '${n.value}' in a pattern", None)
      Nil
    case _ => Nil

  private def classFieldTypes(d: Defn.Class): List[(String, SType)] =
    d.ctor.paramClauses
      .flatMap(_.values)
      .map(p => p.name.value -> p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any))
      .toList

  // ── THE CLAUSE MODEL ────────────────────────────────────────────────────────────────────────
  //
  // ONE PARAMETER CLAUSE = ONE ARROW. `def two(a: Int)(b: Int): Int` is `Int => (Int => Int)`, not
  // the flattened `(Int, Int) => Int` this typer used to build.
  //
  // The flattening was not merely imprecise, it made a whole class of error UNDETECTABLE: with one
  // list, `two(1)` reads as an underflow (permitted — trailing parameters may carry defaults this
  // typer does not track) and the OUTER apply of `two(1)(2)(3)` re-reads the SAME type, so the
  // over-application that dies at runtime with `Not callable: 3` type-checked clean. The obvious
  // patch — "applying a concrete primitive is an error" — was implemented, measured and reverted:
  // under flattening `two(1)` types as `Int`, so a LEGAL curried call is indistinguishable from
  // applying a primitive, and it rejected four correct corpus cases including the two that pin
  // currying as intended. The check was right and the TYPE was wrong.
  //
  // With one arrow per clause, nothing needs a special case: `two(1)` is a Function, `two(1)(2)` is
  // an `Int`, and `two(1)(2)(3)` applies an `Int` — caught by the apply site's ordinary
  // "not a function" arm.

  /** Whether a clause is `(using …)` / `(implicit …)` — auto-supplied at the call site.
    *
    * These must NOT become arrows. `def display[A](a: A)(using s: Show[A]): String` is CALLED as
    * `display(x)`, a complete call whose type is `String`; nesting would make it an
    * under-application typed `Show[A] => String` and every use of it a mismatch. Both spellings are
    * tested the same way `PreBodyApiDescriptorProducer` tests them — the modifier can sit on the
    * clause or on its parameters depending on the syntax used.
    */
  private def isContextualClause(clause: Term.ParamClause): Boolean =
    clause.mod.exists(m => m.is[Mod.Using] || m.is[Mod.Implicit]) ||
      clause.values.exists(_.mods.exists(m => m.is[Mod.Using] || m.is[Mod.Implicit]))

  /** A def's parameter clauses as types, one inner list per EXPLICIT clause.
    *
    * Contextual clauses are folded onto the last explicit one rather than dropped, so their
    * parameters keep the treatment they have today: a call that omits them is an underflow, which
    * this typer permits. Dropping them instead would silently stop checking anything about them;
    * appending changes no verdict that exists.
    *
    * A def with NO clause at all (`def f = …`, parenless) yields `Nil` — the caller distinguishes
    * that from `List(Nil)` (an explicit `()`), and the two mean different things at a use site.
    */
  private def defClauseSTypes(d: Defn.Def): List[List[SType]] =
    val clauses = d.paramClauseGroups.flatMap(_.paramClauses).toList
    val asTypes = (c: Term.ParamClause) =>
      c.values.toList.map(p => p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any))
    val (explicit, contextual) = clauses.partition(c => !isContextualClause(c))
    val ctxTypes = contextual.flatMap(asTypes)
    explicit.map(asTypes) match
      case Nil                       => if ctxTypes.isEmpty then Nil else List(ctxTypes)
      case groups if ctxTypes.isEmpty => groups
      case groups                    => groups.init :+ (groups.last ++ ctxTypes)

  /** Fold clause groups right-to-left into nested arrows.
    *
    * The effect row goes on the OUTERMOST arrow. Strictly the effects happen when the LAST clause is
    * applied and the body runs, not when an intermediate partial application produces a closure —
    * but every consumer in this file reads the row off the type it looked up, with a flat
    * `SType.Function(_, _, EffectRow(_, ops))` pattern (the effect verifier's `DefSummary` scan, and
    * `dischargeEffect`). Putting the row anywhere else would not make those consumers smarter, it
    * would make them silently stop seeing effects on every curried def.
    */
  private def curriedFnType(
      clauses: List[List[SType]],
      result: SType,
      effects: SType.EffectRow = SType.EffectRow(-1, Set.empty)
  ): SType =
    clauses match
      case Nil           => SType.Function(Nil, result, effects)
      case single :: Nil => SType.Function(single, result, effects)
      // inner arrows take Function's DEFAULT (empty) row rather than naming one
      case head :: rest  => SType.Function(head, curriedFnType(rest, result), effects)

  // ── WHY UNDEFINED-NAME REPORTING STOPS AT A VARIADIC ARGUMENT ───────────────────────────────
  //
  // Turning on argument inference switched on THREE checks at once inside `println(...)` and every
  // other variadic call: arity, argument type, and undefined name. The first two are ready. The
  // third is not, and the measurement says so plainly.
  //
  // This typer's model of what names exist is a HAND-MAINTAINED list (`pluginBuiltins` above) plus
  // whatever plugins contribute. Reporting undefined names inside arguments rejected **11 of the
  // examples CI already checks** — `Node`, `MapOp`, `Transport`, `vstack`, `__ssc_quote__` — all of
  // them real exports of `std/` modules that simply were not on the list. Adding the ones that
  // failed brought it to one file, whose next names (`text`, `textField`, `actionButton`) are three
  // of the ~250 that `std/ui` alone exports. Hand-maintaining that is not a task, it is a treadmill.
  //
  // So the two checks that are ready are on, and the one that is not stays off in this position
  // until the prelude is GENERATED from the `exports:` front-matter those modules already declare
  // (`tests/BUGS.md typer-prelude-list-should-be-generated-from-std-exports`). Statement position is
  // unaffected — it has always reported, and still does — and so are non-variadic arguments, which
  // were already being inferred before this change and keep every diagnostic they had.
  private var variadicArgDepth: Int = 0

  private def withinVariadicArg[A](body: => A): A =
    variadicArgDepth += 1
    try body
    finally variadicArgDepth -= 1

  /** Flatten nested arrows into (every parameter, the final result).
    *
    * The inverse of `curriedFnType`, used ONLY for assignability. Note it cannot distinguish
    * `def f(a: Int)(b: Int): Int` from `def f(a: Int): Int => Int` — and must not, because neither
    * runtime distinguishes them at a call either.
    */
  private def uncurriedFn(t: SType): (List[SType], SType) = t match
    case SType.Function(ps, r, _) =>
      val (rest, res) = uncurriedFn(r)
      (ps ++ rest, res)
    case other => (Nil, other)

  /** Types this language can never apply to an argument list.
    *
    * A CLOSED, NAMED set rather than "anything that is not a Function", because most things in this
    * language ARE callable: `String`, `List`, `Vector`, `Seq`, `Array` and `Map` all index through
    * `apply`, a class instance may define `apply`, and `Any` / `Var` / `Error` mean the typer does
    * not know. Only the primitives below have nothing to apply — applying one is what the v1 runtime
    * reports as `Not callable: <value>`, which is the observation this check exists to anticipate.
    *
    * `Unit` is in the set on the same footing; `Char` is not listed separately because this typer
    * spells it `Named("Char", Nil)` only in artifact round-trips and never infers it here.
    */
  private val NeverApplicable: Set[String] =
    Set("Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Unit", "BigInt", "Decimal")

  private def isNeverApplicable(t: SType): Boolean = t match
    case SType.Named(n, Nil) => NeverApplicable.contains(n)
    case _                   => false

  /** `direct { … }` or `direct[M] { … }` — the do-notation head, in either spelling. */
  private def isDirectCall(fun: Term): Boolean = fun match
    case Term.Name("direct")                            => true
    case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
    case _                                              => false

  private def inferCallableType(fun: Term, scope: Scope): SType = fun match
    case Term.Select(qual, Term.Name("apply")) =>
      inferType(qual, scope) match
        case f @ SType.Function(_, _, _) => f
        case _                           => inferType(fun, scope)
    case Term.ApplyType.After_4_6_0(inner, _) =>
      inferCallableType(inner, scope)
    case _ => inferType(fun, scope)

  private def inferKnownApply(fun: Term, args: List[Term], scope: Scope): Option[SType] =
    appliedConstructorName(fun).flatMap {
      case ("Some" | "Option", targs) =>
        Some(SType.option(firstExplicitOrInferred(targs, args, scope)))
      case ("List", targs) =>
        Some(SType.list(firstExplicitOrInferred(targs, args, scope)))
      case ("Vector", targs) =>
        Some(SType.Named("Vector", List(firstExplicitOrInferred(targs, args, scope))))
      case ("Set", targs) =>
        Some(SType.Named("Set", List(firstExplicitOrInferred(targs, args, scope))))
      case ("Seq", targs) =>
        Some(SType.Named("Seq", List(firstExplicitOrInferred(targs, args, scope))))
      case ("Array", targs) =>
        Some(SType.Named("Array", List(firstExplicitOrInferred(targs, args, scope))))
      case ("Map", targs) =>
        Some(inferMapConstructor(targs, args, scope))
      case ("Right", targs) =>
        val left  = targs.headOption.getOrElse(SType.Nothing)
        val right = targs.lift(1).getOrElse(firstExplicitOrInferred(Nil, args, scope))
        Some(SType.Named("Either", List(left, right)))
      case ("Left", targs) =>
        val left  = targs.headOption.getOrElse(firstExplicitOrInferred(Nil, args, scope))
        val right = targs.lift(1).getOrElse(SType.Nothing)
        Some(SType.Named("Either", List(left, right)))
      case _ => None
    }

  private def appliedConstructorName(fun: Term): Option[(String, List[SType])] = fun match
    case Term.Name(n) => Some(n -> Nil)
    case Term.Select(Term.Name(n), Term.Name("apply")) => Some(n -> Nil)
    case Term.Select(_, Term.Name(n)) => Some(n -> Nil)
    case Term.ApplyType.After_4_6_0(inner, targs) =>
      appliedConstructorName(inner).map { (name, _) =>
        name -> targs.map(typeAnnotToSType).toList
      }
    case _ => None

  private def firstExplicitOrInferred(targs: List[SType], args: List[Term], scope: Scope): SType =
    targs.headOption.getOrElse(commonType(args.map(inferType(_, scope))))

  private def inferMapConstructor(targs: List[SType], args: List[Term], scope: Scope): SType =
    if targs.length >= 2 then SType.map(targs(0), targs(1))
    else
      val pairTypes = args.map(inferType(_, scope))
      val pairs = pairTypes.collect { case SType.Tuple(List(k, v)) => k -> v }
      if pairs.length == pairTypes.length then
        SType.map(commonType(pairs.map(_._1)), commonType(pairs.map(_._2)))
      else SType.map(SType.Any, SType.Any)

  private def commonType(types: List[SType]): SType =
    val concrete = types.filterNot(_ == SType.Nothing)
    if concrete.isEmpty then SType.Nothing
    else if concrete.exists(_ == SType.Any) then SType.Any
    else if concrete.forall(_ == concrete.head) then concrete.head
    else commonNumericType(concrete).getOrElse {
      concrete match
        case (first @ SType.Named(name, args)) :: rest
            if rest.forall {
              case SType.Named(n, as) => n == name && as.length == args.length
              case _                  => false
            } =>
          val allArgs = (first :: rest).collect { case SType.Named(_, as) => as }
          SType.Named(name, args.indices.map(i => commonType(allArgs.map(_(i)))).toList)
        case _ => SType.Any
    }

  private def commonNumericType(types: List[SType]): Option[SType] =
    val numeric = Set(SType.Int, SType.Long, SType.Double, SType.BigInt, SType.Decimal)
    if !types.forall(numeric.contains) then None
    else if types.contains(SType.Double) && (types.contains(SType.BigInt) || types.contains(SType.Decimal)) then
      Some(SType.Any)
    else if types.contains(SType.Double) then Some(SType.Double)
    else if types.contains(SType.Decimal) then Some(SType.Decimal)
    else if types.contains(SType.BigInt) then Some(SType.BigInt)
    else if types.contains(SType.Long) then Some(SType.Long)
    else Some(SType.Int)

  private def expectedExtractArgTypes(fun: Term, expected: Option[SType]): List[Option[SType]] =
    termLastName(fun) match
      case Some("Some") => List(collectionElementType(expected.getOrElse(SType.Any)))
      case Some(name) =>
        classFields.get(name).map(_.map((_, tpe) => Some(tpe))).getOrElse(Nil)
      case None => Nil

  private def collectionElementType(tpe: SType): Option[SType] = tpe match
    case SType.Named("List" | "Vector" | "Set" | "Seq" | "Array" | "Option", List(elem)) =>
      Some(elem)
    case _ => None

  private def termLastName(term: Term): Option[String] = term match
    case Term.Name(n) => Some(n)
    case Term.Select(_, Term.Name(n)) => Some(n)
    case Term.ApplyType.After_4_6_0(inner, _) => termLastName(inner)
    case _ => None

  /** Best-effort field-access inference for `qual.field`:
   *
   *  - When `qual` infers to `SType.Named(className, _)` AND `className`
   *    has a recorded constructor-field table (populated by the
   *    Defn.Class arm of [[checkStat]]), return the field's declared
   *    type.  Picks up `someFoo.x` for `case class Foo(x: Int)` (and
   *    plain classes with `val`/`var` params; scalameta carries
   *    `decltpe` on all of them).
   *  - Otherwise return `SType.Any`.  We don't speculate on stdlib
   *    method shapes (`.length` on `String`, `.map` on `List`, etc.)
   *    — those would need a full method table the typer doesn't yet
   *    own.  Reserved for a later push.
   *
   *  Tier-5 .scim granularity push (Open question #1). */
  private def inferSelectType(t: Term.Select, scope: Scope): SType =
    val qualType = inferType(t.qual, scope)
    val fieldName = t.name.value
    qualType match
      case SType.Named(className, _) =>
        classFields.get(className).flatMap(_.collectFirst {
          case (n, fty) if n == fieldName => fty
        }).getOrElse(SType.Any)
      case _ => SType.Any

  /** Check that `actual` is assignable to `expected`, emitting an error if not. */
  private def checkAssignable(actual: SType, expected: SType, pos: scala.meta.Position): Unit =
    if expected == SType.Any || actual == SType.Any || expected == SType.Nothing then ()
    else if isCompatible(actual, expected) then ()
    else
      errors += TypeError(
        s"Type mismatch: expected ${expected.show}, found ${actual.show}",
        posToSpan(pos)
      )

  private def isCompatible(actual: SType, expected: SType): Boolean =
    // Expand user type aliases (`type Env = Map[String, Int]`) on both sides before the
    // structural comparison, so `Env` and `Map[String, Int]` are seen as the same type.
    def unalias(t: SType): SType = t match
      case SType.Named(n, Nil) => typeAliases.get(n) match
        case Some((Nil, rhs)) => rhs
        case _                => t
      case _ => t
    val ua = unalias(actual); val ue = unalias(expected)
    if ua != actual || ue != expected then return isCompatible(ua, ue)
    // Opaque-type sealing: if the expected type is an opaque type and the
    // actual type is the underlying primitive, they are NOT compatible outside
    // the defining scope.  We require the user to go through the companion
    // constructor (`UserId.apply`, `UserId("alice")`) so the types align.
    val expectedIsOpaque = expected match
      case SType.Named(name, Nil) => opaqueTypes.contains(name)
      case _ => false
    if expectedIsOpaque && actual != expected && actual != SType.Any && actual != SType.Nothing then
      return false
    // Effect runner block args: any value is compatible with a zero-arity effectful thunk
    // `() => Any ! Eff` — the shape of every runLogger/runStream/etc. body parameter.
    // The body block is evaluated directly by the interpreter and wrapped in `() => ...`
    // by the JVM codegen; the static type of the block expression does not need to match.
    expected match
      case SType.Function(Nil, SType.Any, SType.EffectRow(_, ops)) if ops.nonEmpty =>
        return true
      case _ =>
    actual == expected ||
    actual == SType.Nothing ||
    expected == SType.Any   ||
    (actual == SType.Null && isNullable(expected)) ||
    // Numeric widening: Int literal is valid where Long or Double is expected
    (actual == SType.Int  && (expected == SType.Long || expected == SType.Double)) ||
    (actual == SType.Long && expected == SType.Double) ||
    // Exact-numeric widening (v1.64): Int ⊆ BigInt ⊆ Decimal. Note Decimal is
    // deliberately NOT compatible with Double (exact vs inexact, §4.3).
    (actual == SType.Int    && (expected == SType.BigInt || expected == SType.Decimal)) ||
    (actual == SType.Long   && (expected == SType.BigInt || expected == SType.Decimal)) ||
    (actual == SType.BigInt && expected == SType.Decimal) ||
    // Generic type application compatibility:
    //  - Raw type (no args) is assignable to its parameterised form: Box ≤ Box[T]
    //  - Named(n, [Nothing, ...]) is assignable to Named(n, _): Option[Nothing] ≤ Option[T]
    //  - Type variable heuristic: if either side looks like a type parameter (all-uppercase,
    //    ≤3 chars, no type args) the check is skipped — the typer lacks full poly inference.
    genericCompatible(actual, expected) ||
    // Nominal subtyping: a declared `case class C extends T` (transitively
    // through trait/enum `extends` chains) makes `C <: T`. Before this the
    // only nominal "subtype" that passed was the accidental looksLikeTypeVar
    // match on single-uppercase names; real multi-character names errored.
    ((actual, expected) match
      case (SType.Named(an, _), SType.Named(en, _)) => nominalSubtype(an, en)
      case _                                        => false) ||
    // Union subtyping: `A <: A | B` — actual is assignable to a union if it
    // is assignable to at least one member of the union.
    (expected match
      case SType.Union(alts) => alts.exists(isCompatible(actual, _))
      case _                 => false) ||
    // Union on the actual side: `A | B <: C` — every alternative must be
    // assignable to `expected` (conservative; handles `Union <: Union`).
    (actual match
      case SType.Union(alts) => alts.forall(isCompatible(_, expected))
      case _                 => false) ||
    // Function compatibility (contravariant params, covariant return), lenient on
    // `Any`: a fully-dynamic `Any => Any` — the type inferred for lambdas the checker
    // can't pin down (e.g. typed-effect handler bodies) — is assignable to any
    // same-arity function type.
    ((actual, expected) match
      case (SType.Function(ap, ar, _), SType.Function(ep, er, _)) if ap.length == ep.length =>
        ap.zip(ep).forall((a, e) => a == SType.Any || e == SType.Any || isCompatible(e, a)) &&
          (ar == SType.Any || er == SType.Any || isCompatible(ar, er))
      case _ => false) ||
    // …and the same comparison on TOTAL arity, because both runtimes uncurry at a call.
    //
    // MEASURED, not assumed — and it is why the clause model needed this arm at all. Given
    // `def mk(a: Int)(b: Int): Int` and `def use(f: (Int, Int) => Int)`, `use(mk)` prints 3 on BOTH
    // `run --v1` and `bin/ssc run`. Once clauses became arrows, `mk` typed as `Int => Int => Int`,
    // the arity-wise arm above said 1 ≠ 2, and `check` rejected a program its own runtime runs —
    // exactly the defect this whole programme exists to remove, reintroduced by the fix for it.
    //
    // The reverse direction (a FLAT def where a curried type is declared) is accepted here too, and
    // that one is a LANE DIVERGENCE rather than a settled rule: `--v1` prints 3, native refuses with
    // `arity: 2 expected, 1 given` (tests/BUGS.md a-flat-def-passed-where-a-curried-type-is-declared).
    // `check` is the v1 tool and v1 runs it, so rejecting it here would be the same defect again.
    // Deciding which lane is right is a language question, not a typer question.
    ((actual, expected) match
      case (af @ SType.Function(_, _, _), ef @ SType.Function(_, _, _)) =>
        val (ap, ar) = uncurriedFn(af)
        val (ep, er) = uncurriedFn(ef)
        ap.length == ep.length &&
          ap.zip(ep).forall((a, e) => a == SType.Any || e == SType.Any || isCompatible(e, a)) &&
          (ar == SType.Any || er == SType.Any || isCompatible(ar, er))
      case _ => false) ||
    // Quote lifting: a value of type `T` is assignable where `Expr[T]` is expected
    // (quoted-macro `${ }` / `'{ }` context — the checker does not model splices).
    (expected match
      case SType.Named("Expr", List(inner)) => actual == inner || isCompatible(actual, inner)
      case _                                => false)

  private def isNullable(t: SType): Boolean = t match
    case SType.Named(_, _) => true
    case _                 => false

  private def looksLikeTypeVar(name: String): Boolean =
    name.length <= 3 && name.nonEmpty && name.forall(c => c.isLetter && c.isUpper || c.isDigit)

  private def genericCompatible(actual: SType, expected: SType): Boolean =
    (actual, expected) match
      case (SType.Named(an, Nil), SType.Named(en, _)) if an == en => true
      case (SType.Named(an, aArgs), SType.Named(en, eArgs))
          if an == en && aArgs.length == eArgs.length =>
        aArgs.zip(eArgs).forall { (a, e) =>
          a == SType.Nothing || a == SType.Any || e == SType.Any || isCompatible(a, e)
        }
      case (SType.Named(an, Nil), _) if looksLikeTypeVar(an) => true
      case (_, SType.Named(en, Nil)) if looksLikeTypeVar(en) => true
      case _ => false

  /** Direct parent type-names of a template's `extends` clause (the head
   *  name of each `init`, e.g. `Foo` for `extends Foo[Bar]`). */
  private def parentNamesOf(templ: scala.meta.Template): List[String] =
    templ.inits.flatMap { init =>
      typeAnnotToSType(init.tpe) match
        case SType.Named(n, _) => Some(n)
        case _                 => None
    }.toList

  /** Transitive nominal subtype over the declared `extends` graph: is `sup`
   *  a (reflexive) supertype of `sub`? Cycle-guarded against malformed input. */
  private def nominalSubtype(sub: String, sup: String): Boolean =
    sub == sup || {
      val seen = collection.mutable.Set.empty[String]
      def reaches(n: String): Boolean =
        classParents.getOrElse(n, Nil).exists { p =>
          p == sup || (seen.add(p) && reaches(p))
        }
      reaches(sub)
    }

  /** Basic type rules for infix operators. */
  private def checkBinaryOp(lhs: SType, op: String, rhs: SType, pos: scala.meta.Position): SType =
    (lhs, op, rhs) match
      // Numeric arithmetic — same type
      case (SType.Int,    op2, SType.Int)    if isArith(op2) => SType.Int
      case (SType.Long,   op2, SType.Long)   if isArith(op2) => SType.Long
      case (SType.Double, op2, SType.Double) if isArith(op2) => SType.Double
      // Widening
      case (SType.Int,    op2, SType.Double) if isArith(op2) => SType.Double
      case (SType.Double, op2, SType.Int)    if isArith(op2) => SType.Double
      case (SType.Long,   op2, SType.Int)    if isArith(op2) => SType.Long
      case (SType.Int,    op2, SType.Long)   if isArith(op2) => SType.Long
      // Exact numerics (v1.64): BigInt and Decimal arithmetic + widening tower.
      case (SType.BigInt,  op2, SType.BigInt)             if isArith(op2) => SType.BigInt
      case (SType.BigInt,  op2, SType.Int | SType.Long)   if isArith(op2) => SType.BigInt
      case (SType.Int | SType.Long, op2, SType.BigInt)    if isArith(op2) => SType.BigInt
      case (SType.Decimal, op2, SType.Decimal)            if isArith(op2) => SType.Decimal
      case (SType.Decimal, op2, SType.Int | SType.Long | SType.BigInt) if isArith(op2) => SType.Decimal
      case (SType.Int | SType.Long | SType.BigInt, op2, SType.Decimal)  if isArith(op2) => SType.Decimal
      // Decimal ⊕ Double / BigInt ⊕ Double are deliberate errors (§4.3) —
      // mixing exact and inexact silently loses precision. Convert explicitly.
      case (l, op2, r)
          if isArith(op2) &&
             ((l == SType.Decimal || l == SType.BigInt) && r == SType.Double ||
              (r == SType.Decimal || r == SType.BigInt) && l == SType.Double) =>
        errors += TypeError(
          s"cannot mix ${l.show} and ${r.show} in '$op2' — convert explicitly (.toDouble or .toDecimal)",
          posToSpan(pos)
        )
        SType.Error(s"${l.show} $op2 ${r.show}")
      // String concat
      case (SType.String, "+", _)  => SType.String
      case (_, "+", SType.String)  => SType.String
      // Comparison — always Boolean
      case (_, "==" | "!=" | "<" | ">" | "<=" | ">=", _) => SType.Boolean
      // Logical
      case (SType.Boolean, "&&" | "||", SType.Boolean) => SType.Boolean
      // A COLLECTION on the left of `+`/`-` is an ADD or a REMOVE, not arithmetic — `Set(1,2) + 3`
      // is a Set, and `m - k` is a Map. Without this the arithmetic rule below reads `Set[Int]` as
      // "not numeric or String" and reports `Operator '+' is not applicable to Set[Int]` on two
      // corpus cases that run correctly on every lane. It was invisible until argument inference
      // reached inside `println(...)` (tests/BUGS.md v1-check-never-looks-inside-a-println).
      case (l @ SType.Named("Set" | "List" | "Vector" | "Seq" | "Map", _), "+" | "-", _) => l
      // Type mismatch for arithmetic on known incompatible types
      case (l, op2, r)
          if isArith(op2) && l != SType.Any && r != SType.Any &&
             !isNumericOrString(l) =>
        errors += TypeError(
          s"Operator '$op2' is not applicable to ${l.show}",
          posToSpan(pos)
        )
        SType.Error(s"${l.show} $op2 ${r.show}")
      case _ => SType.Any

  /** Returns true if `name` appears directly as a `Named` inside `t` at any depth.
   *  Used to detect trivially recursive type aliases like `type A = List[A]`. */
  /** v1.12.1 — Parse a `def` return-type annotation, extracting the effect row
   *  when the annotation has the form `T ! Eff` or `T ! (Eff1, Eff2, ...)`.
   *  Returns `(retType, effectRow)`. */
  private def parseDeclReturnType(tpeOpt: Option[scala.meta.Type]): (Option[SType], SType.EffectRow) =
    tpeOpt match
      case Some(Type.ApplyInfix(retTyp, Type.Name("!"), effTyp)) =>
        val ret  = typeAnnotToSType(retTyp)
        val effs: SType.EffectRow = effTyp match
          case Type.Name(n) =>
            SType.EffectRow(-1, Set(EffectOp(n)))
          case Type.Apply.After_4_6_0(Type.Name(n), argClause) =>
            SType.EffectRow(-1, Set(EffectOp(n, argClause.values.map(typeAnnotToSType).toList)))
          case Type.Tuple(es) =>
            SType.EffectRow(-1, es.collect {
              case Type.Name(n) => EffectOp(n)
              case Type.Apply.After_4_6_0(Type.Name(n), argClause) =>
                EffectOp(n, argClause.values.map(typeAnnotToSType).toList)
            }.toSet)
          case _ => SType.EffectRow(-1, Set.empty[EffectOp])
        (Some(ret), effs)
      case other => (other.map(typeAnnotToSType), SType.EffectRow(-1, Set.empty))

  /** v1.12.1 — Remove `effName` from a body type's effect row.
   *  Works when `bodyType` is a zero-arity thunk `() => A ! (Eff ∪ E)`,
   *  returning `A ! E` (or `A` when no effects remain).
   *  For non-thunk bodies the body type is returned unchanged — the caller
   *  already has the return type of the call without effects propagated. */
  private def dischargeEffect(bodyType: SType, effName: String): SType = bodyType match
    case SType.Function(Nil, retType, SType.EffectRow(tail, ops)) =>
      val remaining = ops.filterNot(_.name == effName)
      if remaining.isEmpty then retType
      else SType.Function(Nil, retType, SType.EffectRow(tail, remaining))
    case other => other

  private def isDirectlyRecursive(name: String, t: SType): Boolean = t match
    case SType.Named(`name`, _) => true
    case SType.Named(_, args)   => args.exists(isDirectlyRecursive(name, _))
    case SType.Function(ps, r, _)  => ps.exists(isDirectlyRecursive(name, _)) || isDirectlyRecursive(name, r)
    case SType.Tuple(elems)     => elems.exists(isDirectlyRecursive(name, _))
    case SType.Union(ts)        => ts.exists(isDirectlyRecursive(name, _))
    case SType.Intersection(ts) => ts.exists(isDirectlyRecursive(name, _))
    case _                      => false

  private def isArith(op: String): Boolean = Set("+", "-", "*", "/", "%").contains(op)
  private def isNumericOrString(t: SType): Boolean =
    t == SType.Int || t == SType.Long || t == SType.Double ||
    t == SType.BigInt || t == SType.Decimal || t == SType.String

  /** Expand a type alias if `name` is registered in `typeAliases`.
   *  For a parameterized alias `type Opt[A] = Option[A]`, `args` holds the
   *  concrete type arguments and we substitute `A → args(0)` in the rhs.
   *  For a simple alias `type UserId = String`, `args` must be empty.
   *
   *  Type-lambda aliases (`type IntKey = [V] =>> Map[Int, V]`) have no own type
   *  params — the `args` belong to the lambda. After resolving the alias's own
   *  params (none here), a `TypeLambda` rhs with use-site `args` is β-reduced:
   *  `IntKey[Long]` → `Map[Int, Long]` (p3 semantics). Mirrors the rust backend's
   *  codegen-side reduction so `ssc check` agrees with the emitted type.
   *
   *  Pure expander — arity / kind diagnostics are owned by `checkTypeApplication`
   *  (the single source, called from the `Type.Apply` case). On an arity mismatch
   *  this just returns the rhs unexpanded (no substitution, no error). */
  private def expandAlias(name: String, args: List[SType]): Option[SType] =
    typeAliases.get(name).map { (params, rhs) =>
      val expanded =
        if params.isEmpty then rhs
        else if params.length != args.length then rhs       // mismatch → unexpanded (checker errors)
        else rhs.substNames(params.zip(args).toMap)          // substitute the alias's own params
      // β-reduce a type-lambda alias applied at the use site. Only the no-own-params
      // alias form leaves `args` for the lambda; a parameterised alias already
      // consumed all `args` via its own params above.
      expanded match
        case lam @ SType.TypeLambda(lamParams, _)
            if params.isEmpty && args.nonEmpty && lamParams.length == args.length =>
          lam.applyTo(args)
        case _ => expanded
    }

  /** Kinds of a scalameta type-parameter clause: for each param, the arity of
   *  its OWN type-parameter clause (`A` → 0 proper type; `F[_]` → 1; `G[_, _]`
   *  → 2). Drives the kind registry `typeCtorKinds`. */
  private def tparamKinds(tparams: scala.meta.Type.ParamClause): List[Int] =
    tparams.values.map(_.tparamClause.values.length).toList

  /** The kinds of a type constructor's parameters, or `None` for an unknown /
   *  imported name. Checks user-defined types (`typeCtorKinds`) first, then the
   *  built-in constructor table. */
  private def typeCtorKindsOf(name: String): Option[List[Int]] =
    typeCtorKinds.get(name).orElse(Typer.builtinTypeCtorKinds.get(name))

  /** Remaining arity (kind) of a type expression: an un-applied constructor
   *  `List` has kind 1, a fully-applied `List[Int]` has kind 0 (a proper type),
   *  an `F[_]` slot has its declared arity. Unknown names are treated as proper
   *  types (kind 0) — conservative, so we never flag an imported type. */
  private def kindOfSType(t: SType): Int = t match
    case SType.Named(n, args)        => typeCtorKindsOf(n).map(ks => math.max(0, ks.length - args.length)).getOrElse(0)
    case SType.HigherKinded(_, arity) => arity
    case _                            => 0

  /** True when we know a type expression's kind precisely enough to flag a
   *  mismatch against it. Conservative: an unknown bare name (an imported type
   *  constructor we have no kind for) is NOT known, so its use as a higher-kinded
   *  argument is never falsely flagged. */
  private def argKindKnown(t: SType): Boolean = t match
    case SType.Named(n, Nil)       => typeCtorKindsOf(n).isDefined || primitiveKnownName(n)
    case SType.Named(_, _)         => true   // applied → a proper type (kind 0)
    case SType.HigherKinded(_, _)  => true
    case _: SType.Tuple | _: SType.Function | _: SType.Union | _: SType.Intersection => true
    case _                         => false

  private def primitiveKnownName(n: String): Boolean =
    Set("Int", "Long", "Double", "Float", "BigInt", "Decimal", "BigDecimal", "String",
        "Boolean", "Char", "Unit", "Any", "Nothing", "Null", "Byte", "Short").contains(n)

  /** Kind-check a type application `name[argTypes]` for a KNOWN constructor:
   *  (1) arity — the param count must match the number of type arguments;
   *  (2) higher-kinded bound — each argument's kind must match the declared kind
   *      of the parameter it fills (so `Functor[Int]` / `Functor[Map]` where
   *      `trait Functor[F[_]]`, and `Fix[Map]` where `type Fix = [F[_]] =>> …`,
   *      are flagged). Unknown / imported names and arguments are skipped so no
   *      valid program is ever falsely flagged. */
  private def checkTypeApplication(name: String, argTypes: List[SType]): Unit =
    typeCtorKindsOf(name).foreach { kinds =>
      if kinds.length != argTypes.length then
        errors += TypeError(
          s"Type constructor $name expects ${kinds.length} type argument(s), got ${argTypes.length}",
          None
        )
      else
        kinds.zip(argTypes).zipWithIndex.foreach { case ((expected, arg), i) =>
          if argKindKnown(arg) && kindOfSType(arg) != expected then
            val want = if expected == 0 then "a proper type" else s"a type constructor of kind ${"_, " * (expected - 1)}_ (e.g. F[${List.fill(expected)("_").mkString(", ")}])"
            val got  = kindOfSType(arg) match
              case 0 => s"the proper type ${arg.show}"
              case k => s"${arg.show} (a type constructor of arity $k)"
            errors += TypeError(
              s"Type constructor $name: type parameter #${i + 1} expects $want, got $got",
              None
            )
        }
    }

  /** Convert a scalameta type annotation to our internal SType. */
  private def typeAnnotToSType(tpe: scala.meta.Type): SType = tpe match
    case Type.Name(name) =>
      // Check if this is a known type alias before falling through to primitives.
      expandAlias(name, Nil).getOrElse(primitiveOrNamed(name))
    // Generic application: handle the well-known constructors first so the
    // returned `SType.Named(name, args)` lines up with `SType.list/option/map`
    // — then fall through to a generic `Named(head, args)` for any other
    // user-defined parameterised type (`Set[Int]`, `Vector[A]`, etc.).
    case Type.Apply.After_4_6_0(Type.Name("List"),   argClause) if argClause.values.length == 1 =>
      SType.list(typeAnnotToSType(argClause.values.head))
    case Type.Apply.After_4_6_0(Type.Name("Option"), argClause) if argClause.values.length == 1 =>
      SType.option(typeAnnotToSType(argClause.values.head))
    case Type.Apply.After_4_6_0(Type.Name("Map"), argClause) if argClause.values.length == 2 =>
      SType.map(typeAnnotToSType(argClause.values.head), typeAnnotToSType(argClause.values(1)))
    case Type.Apply.After_4_6_0(Type.Name(other), argClause) =>
      // Generic parameterised type or alias (`Set[Int]`, `Vector[A]`, `Opt[A]`,
      // `IntKey[Long]`, a wrong-arity `List[Int, String]`, …). Kind-check the
      // application (arity + higher-kinded bounds) for known constructors, then
      // expand if `other` is an alias.
      val typeArgs = argClause.values.map(typeAnnotToSType).toList
      checkTypeApplication(other, typeArgs)
      expandAlias(other, typeArgs).getOrElse(SType.Named(other, typeArgs))
    case Type.Apply.After_4_6_0(sel: Type.Select, argClause) =>
      SType.Named(showTypePath(sel), argClause.values.map(typeAnnotToSType).toList)
    case Type.Function.After_4_6_0(params, ret) =>
      SType.Function(params.values.map(typeAnnotToSType).toList, typeAnnotToSType(ret))
    case Type.Tuple(elems) => SType.Tuple(elems.map(typeAnnotToSType))
    // Surface-level `A | B` / `A & B` — scalameta exposes them as a
    // `Type.ApplyInfix` whose op is the literal `|` / `&` token.  We
    // flatten chains so `A | B | C` becomes a single `SType.Union(A, B, C)`
    // matching the canonical form the parser produces from `.scim`
    // round-trips.
    // Tuple concatenation: `(A, B) ++ (C, D)` → flat `(A, B, C, D)`.
    // Right-associative, lowest precedence — resolved before `|` and `&`.
    case Type.ApplyInfix(lhs, Type.Name("++"), rhs) =>
      SType.tupleConcat(typeAnnotToSType(lhs), typeAnnotToSType(rhs))
    case Type.ApplyInfix(lhs, Type.Name("|"), rhs) =>
      val l = typeAnnotToSType(lhs)
      val r = typeAnnotToSType(rhs)
      val left  = l match { case SType.Union(xs) => xs; case other => List(other) }
      val right = r match { case SType.Union(xs) => xs; case other => List(other) }
      SType.Union(left ++ right)
    case Type.ApplyInfix(lhs, Type.Name("&"), rhs) =>
      val l = typeAnnotToSType(lhs)
      val r = typeAnnotToSType(rhs)
      val left  = l match { case SType.Intersection(xs) => xs; case other => List(other) }
      val right = r match { case SType.Intersection(xs) => xs; case other => List(other) }
      SType.Intersection(left ++ right)
    // Type lambda — `[X] =>> F[X]` (native; the parser also desugars the
    // placeholder form `Map[Int, _]` to this before the typer sees it). Surface-
    // only as a value type, but registered so a `type` alias bound to it can be
    // β-reduced at the use site (see `expandAlias`). Without this it fell through
    // to `Any` and `IntKey[Long]` lost all meaning.
    case Type.Lambda.After_4_6_0(tparams, body) =>
      SType.TypeLambda(tparams.values.map(_.name.value).toList, typeAnnotToSType(body))
    // Qualified type names: `scala.collection.Map`, `std.actors.Spec` etc.
    // Preserve the dotted path verbatim so `SType.show` / `parseSType` can
    // round-trip the interface entry.
    case sel: Type.Select => SType.Named(showTypePath(sel), Nil)
    case _                => SType.Any

  private def primitiveOrNamed(name: String): SType = name match
    case "Int"     => SType.Int
    case "Long"    => SType.Long
    case "Double"  => SType.Double
    case "Float"   => SType.Double
    case "BigInt"  => SType.BigInt
    case "Decimal" | "BigDecimal" => SType.Decimal
    case "String"  => SType.String
    case "Boolean" => SType.Boolean
    case "Char"    => SType.Char
    case "Unit"    => SType.Unit
    case "Any"     => SType.Any
    case "Nothing" => SType.Nothing
    case "Null"    => SType.Null
    case other     => SType.named0(other)

  /** Render a `Type.Select` / `Type.Name` chain as a dotted path string. */
  private def showTypePath(t: scala.meta.Type): String = t match
    case Type.Name(n)         => n
    case Type.Select(qual, n) => s"${showTermPath(qual)}.${n.value}"
    case _                    => t.toString

  private def showTermPath(t: scala.meta.Term): String = t match
    case Term.Name(n)         => n
    case Term.Select(qual, n) => s"${showTermPath(qual)}.${n.value}"
    case _                    => t.toString

  /** Should an unresolved identifier be flagged in strict mode?
   *
   *  Operators, placeholders and dotted names are intentionally skipped
   *  (the typer doesn't reliably know about them today).  The remaining
   *  identifiers — bare letters/underscore-led terms — are exactly the
   *  shape that should resolve via the prelude, the module's own defs,
   *  or an imported `.scim` interface.
   */
  private def isFlaggableName(name: String): Boolean =
    if name.isEmpty then false
    else if name == "_" then false
    else if name.contains('.') then false
    else
      val c = name.head
      c.isLetter || c == '_'

  // ── Deep Select-chain resolution for strict mode ────────────────────────
  //
  // Walks a qualifier chain `a.b.c.…` rooted (potentially) at an imported
  // module.  Returns a [[QualResult]] describing where the chain ended up:
  //
  //  - Module / SubNamespace — the chain resolved cleanly; the caller can
  //    use the resulting namespace to validate the final member name.
  //  - SubOpaque             — chain resolved cleanly so far, but the next
  //                            namespace level has no recorded members
  //                            (extractor didn't populate `ExportedSymbol.nested`).
  //                            Treat as permissive: silently accept further
  //                            members.  TODO: lift to a real diagnostic once
  //                            `InterfaceExtractor` records nested members.
  //  - BrokenAt              — chain broke at a known module / sub-namespace
  //                            because `member` is not in its export list.
  //                            The caller emits exactly one diagnostic
  //                            (no cascade for deeper selects).
  //  - UndefinedRoot         — root name is not in any scope and not an
  //                            imported module alias — equivalent to a bare
  //                            undefined-name reference.
  //  - NotAnalysable         — root is a local val or an unrecognised shape
  //                            (e.g. literal receiver); strict mode skips.
  private enum QualResult:
    case Module(name: String, iface: scalascript.ir.ModuleInterface)
    case Sub(path: String, members: List[scalascript.ir.ExportedSymbol])
    case SubOpaque(path: String)
    case BrokenAt(qualPath: String, missing: String, missingPos: scala.meta.Position)
    case UndefinedRoot(name: String, pos: scala.meta.Position)
    case NotAnalysable

  /** Step from one namespace level to the next.  `parentPath` is the
   *  dotted path covering the qualifier so far (for diagnostics).  Looks
   *  up `member` in `members`; on hit, branches on whether the hit
   *  carries `nested` (deeper analysis is possible) or is a leaf
   *  (deeper selects fall through to permissive). */
  private def stepNamespace(
      parentPath: String,
      members:    List[scalascript.ir.ExportedSymbol],
      member:     String,
      memberPos:  scala.meta.Position
  ): QualResult =
    val newPath = s"$parentPath.$member"
    members.find(_.name == member) match
      case None       => QualResult.BrokenAt(parentPath, member, memberPos)
      case Some(hit)  =>
        if hit.nested.nonEmpty then QualResult.Sub(newPath, hit.nested)
        else QualResult.SubOpaque(newPath)

  /** Resolve a qualifier term (the LHS of a Select) to a [[QualResult]]. */
  private def resolveQualifier(qual: scala.meta.Term, scope: Scope): QualResult =
    qual match
      case Term.Name(qname) =>
        importedInterfaces.get(qname) match
          case Some(iface) =>
            QualResult.Module(qname, iface)
          case None =>
            if scope.lookup(qname).isDefined then QualResult.NotAnalysable
            else if isFlaggableName(qname) then
              QualResult.UndefinedRoot(qname, qual.pos)
            else QualResult.NotAnalysable

      case Term.Select(inner, Term.Name(member)) =>
        resolveQualifier(inner, scope) match
          case QualResult.Module(name, iface) =>
            // Look up `member` in this module's exports.  The exports
            // are flat today, but a sub-namespace entry (kind == "object")
            // may carry a `nested` list for deeper analysis.
            val exports = iface.exports ++ iface.externDefs
            exports.find(_.name == member) match
              case None      => QualResult.BrokenAt(name, member, qual.pos)
              case Some(hit) =>
                val newPath = s"$name.$member"
                if hit.nested.nonEmpty then QualResult.Sub(newPath, hit.nested)
                else QualResult.SubOpaque(newPath)

          case QualResult.Sub(path, members) =>
            stepNamespace(path, members, member, qual.pos)

          // Opaque sub-namespace: we can't introspect deeper, so any
          // further name is permissively accepted (and the result stays
          // opaque so still-deeper selects also pass).
          case QualResult.SubOpaque(path) =>
            QualResult.SubOpaque(s"$path.$member")

          // Already broken — keep the original break point so we report
          // it exactly once at the top of the chain.  Don't cascade.
          case b: QualResult.BrokenAt    => b
          case u: QualResult.UndefinedRoot => u
          case QualResult.NotAnalysable  => QualResult.NotAnalysable

      case _ => QualResult.NotAnalysable

  /** Strict-mode entry point for a Select term.  Emits at most one
   *  diagnostic; never cascades.  Idempotent: re-checking the same term
   *  twice would record the same diagnostic twice, but the typer visits
   *  each tree node exactly once. */
  private def checkSelectStrict(t: Term.Select, scope: Scope): Unit =
    val memberName = t.name.value
    resolveQualifier(t.qual, scope) match
      case QualResult.Module(name, iface) =>
        // 2-level base case: q.m where q is an imported module.
        val exported = iface.exports.iterator.map(_.name).toSet ++
                       iface.externDefs.iterator.map(_.name).toSet
        if !exported.contains(memberName) then
          errors += TypeError(
            s"$name has no member $memberName",
            posToSpan(t.pos)
          )

      case QualResult.Sub(path, members) =>
        if !members.exists(_.name == memberName) then
          errors += TypeError(
            s"$path has no member $memberName",
            posToSpan(t.pos)
          )

      // Opaque sub-namespace: silently accept (permissive fallback for
      // the deep-Select case until `InterfaceExtractor` records nested
      // members).
      case QualResult.SubOpaque(_) => ()

      case QualResult.BrokenAt(qualPath, missing, pos) =>
        errors += TypeError(
          s"$qualPath has no member $missing",
          posToSpan(pos)
        )

      case QualResult.UndefinedRoot(name, pos) =>
        errors += TypeError(
          s"Reference to undefined name: $name",
          posToSpan(pos)
        )

      case QualResult.NotAnalysable => ()

  private def emitWarning(msg: String, span: Option[Span]): Unit =
    errors += TypeError(msg, span, isWarning = !fatalWarnings)

  private def declaredOrInferredEvidence(
      tpe: SType,
      declared: Boolean,
      pos: scala.meta.Position,
      reason: String
  ): TypeEvidence =
    if declared then TypeEvidence.declared(tpe, posToSpan(pos), Some(reason))
    else if tpe.containsAny then
      TypeEvidence.unknown(tpe, posToSpan(pos), Some(s"$reason contains unsupported Any"))
    else TypeEvidence.inferred(tpe, posToSpan(pos), Some(reason))

  private def declaredOrUnknownEvidence(
      tpe: SType,
      pos: scala.meta.Position,
      reason: String
  ): TypeEvidence =
    if tpe.containsAny then
      TypeEvidence.unknown(tpe, posToSpan(pos), Some(s"$reason contains unsupported Any"))
    else TypeEvidence.declared(tpe, posToSpan(pos), Some(reason))

  private def posToSpan(pos: scala.meta.Position): Option[Span] =
    if pos.isEmpty then None
    else Some(Span(
      scalascript.ast.Position(pos.startLine, pos.startColumn, pos.start),
      scalascript.ast.Position(pos.endLine, pos.endColumn, pos.end)
    ))

case class TypeError(msg: String, span: Option[Span], isWarning: Boolean = false):
  def show: String = span match
    case Some(s) => s"${if isWarning then "warning" else "error"} $s: $msg"
    case None    => s"${if isWarning then "warning" else "error"}: $msg"

// ─── Summary of a single definition found in a code block ────────

case class DefSummary(
    name: String,
    kind: SymbolKind,
    tpe: SType,
    paramTypes: List[SType],
    evidence: Option[TypeEvidence] = None
):
  def show: String =
    val kindStr = kind.toString.toLowerCase
    s"$kindStr $name: ${tpe.show}"

// ─── Typed IR ─────────────────────────────────────────────────────

case class TypedModule(
  name: String,
  version: String,
  sections: List[TypedSection],
  errors: List[TypeError]
):
  def hasErrors: Boolean = errors.exists(!_.isWarning)
  def warnings:  List[TypeError] = errors.filter(_.isWarning)
  def show: String =
    val sb = StringBuilder()
    sb ++= s"module $name v$version\n"
    val trueErrors = errors.filter(!_.isWarning)
    val warns      = errors.filter(_.isWarning)
    if trueErrors.nonEmpty then
      sb ++= s"Errors (${trueErrors.length}):\n"
      trueErrors.foreach(e => sb ++= s"  - ${e.show}\n")
      sb ++= "\n"
    if warns.nonEmpty then
      sb ++= s"Warnings (${warns.length}):\n"
      warns.foreach(w => sb ++= s"  - ${w.show}\n")
      sb ++= "\n"
    sections.foreach(s => sb ++= s.show(1))
    sb.toString

case class TypedSection(
  name: String,
  level: Int,
  definitions: List[TypedDef],
  subsections: List[TypedSection]
):
  def show(indent: Int): String =
    val prefix = "  " * indent
    val sb = StringBuilder()
    sb ++= s"$prefix${"#" * level} $name\n"
    definitions.foreach {
      case TypedDef.CodeBlock(lang, ok, defs) =>
        val status = if ok then "OK" else if lang.isEmpty then "untyped" else "PARSE ERROR"
        sb ++= s"$prefix  [$lang: $status]\n"
        defs.foreach(d => sb ++= s"$prefix    ${d.show}\n")
      case TypedDef.Import(path, bindings) =>
        sb ++= s"$prefix  [import $path -> ${bindings.mkString(", ")}]\n"
    }
    subsections.foreach(s => sb ++= s.show(indent + 1))
    sb.toString

enum TypedDef:
  case CodeBlock(lang: String, parsed: Boolean, defs: List[DefSummary])
  case Import(path: String, bindings: List[String])

/** Snapshot of the typer's mutable state after processing a single top-level
 *  section.  Stored alongside the [[TypedSection]] result so that
 *  [[Typer.typeCheckIncremental]] can skip re-typing unchanged sections on
 *  subsequent runs.
 *
 *  @param sectionHash  SHA-256 hex digest of the section's source content
 *                      (heading text + concatenated code-block sources).
 *                      Used to detect whether the section has changed between
 *                      two parse runs.
 *  @param typedSection the typed IR produced for this section on the previous run.
 *  @param typeAliases  snapshot of `Typer.typeAliases` immediately after typing
 *                      this section (i.e. accumulated up to and including it).
 *  @param opaqueTypes  snapshot of `Typer.opaqueTypes` after this section.
 *  @param errors       type errors emitted while typing this section.  Restored
 *                      into `TypedModule.errors` by [[Typer.typeCheckIncremental]]
 *                      when the section is reused from a previous snapshot, so
 *                      that error diagnostics from unchanged sections are not
 *                      silently dropped.
 */
case class SectionSnapshot(
    sectionHash:  String,
    typedSection: TypedSection,
    typeAliases:  Map[String, (List[String], SType)],
    opaqueTypes:  Set[String],
    errors:       List[TypeError] = Nil
)

object SectionSnapshot:
  /** Compute a stable SHA-256 hash for a [[Section]]'s source content.
   *
   *  The hash covers the section's heading text and, in order, the raw
   *  `source` of every [[Content.CodeBlock]] inside `section.content`.
   *  Subsections are intentionally excluded — a subsection change is
   *  detected via its own snapshot entry; the parent section's hash should
   *  not change when only a child changes.
   */
  def hashSection(section: scalascript.ast.Section): String =
    val md  = MessageDigest.getInstance("SHA-256")
    def feed(s: String): Unit = md.update(s.getBytes("UTF-8"))
    feed(section.heading.text)
    section.content.foreach {
      case cb: scalascript.ast.Content.CodeBlock => feed(cb.source)
      case _                                     => ()
    }
    toHex(md.digest())

  private def toHex(bytes: Array[Byte]): String =
    val out = new Array[Char](bytes.length * 2)
    val hex = "0123456789abcdef"
    var i = 0
    while i < bytes.length do
      val b = bytes(i) & 0xff
      out(i * 2) = hex.charAt(b >>> 4)
      out(i * 2 + 1) = hex.charAt(b & 0x0f)
      i += 1
    String(out)

object Typer:
  // Built once per JVM; immutable after construction.  typeCheck and
  // typeCheckIncremental always write module names into a child scope so
  // the shared prelude is never mutated.
  private[Typer] val sharedPrelude: Scope = new Typer().createPrelude()

  /** Kind registry for well-known built-in type constructors: name → its type
   *  parameters' kinds (all `0` — the std collections take proper-type args).
   *  The list length is the constructor's arity, used for arity + kind checking
   *  in `checkTypeApplication`. Proper types (Int/String/…) are absent (arity 0,
   *  treated as kind 0 by `kindOfSType`). */
  private[typer] val builtinTypeCtorKinds: Map[String, List[Int]] = Map(
    "List"     -> List(0), "Option"  -> List(0), "Set"      -> List(0),
    "Vector"   -> List(0), "Seq"     -> List(0), "Iterable" -> List(0),
    "Iterator" -> List(0), "Array"   -> List(0), "LazyList" -> List(0),
    "Stream"   -> List(0), "Try"     -> List(0), "Future"   -> List(0),
    "Map"      -> List(0, 0), "Either" -> List(0, 0)
  )

  def typeCheck(module: Module): TypedModule = Typer().typeCheck(module)

  /** Type-check a module with pre-compiled interface scopes for its imports.
   *
   *  `interfaces` is a map of import alias → `ModuleInterface` loaded from
   *  pre-compiled `.scim` artifacts.  Names exported by those interfaces are
   *  available in the module's type-checking scope without re-parsing source.
   *
   *  v2.0 / Stage 4.
   */
  def typeCheckWithInterfaces(
      module:     Module,
      interfaces: Map[String, scalascript.ir.ModuleInterface]
  ): TypedModule = Typer(interfaces).typeCheck(module)

  /** Strict variant of `typeCheckWithInterfaces`.
   *
   *  When `strict = true`, references to identifiers that resolve to
   *  nothing (not in the consumer's own defs, not in any imported `.scim`
   *  interface, and not in the builtin prelude) emit a `TypeError` rather
   *  than silently returning `SType.Any`.  Used by `ssc check-with-iface`.
   *
   *  v2.0 — typer strict mode (undefined-name diagnostics).
   */
  def typeCheckWithInterfaces(
      module:     Module,
      interfaces: Map[String, scalascript.ir.ModuleInterface],
      strict:     Boolean
  ): TypedModule = Typer(interfaces, strict).typeCheck(module)

  def typeCheckWithInterfaces(
      module:        Module,
      interfaces:    Map[String, scalascript.ir.ModuleInterface],
      strict:        Boolean,
      extraBuiltins: Set[String]
  ): TypedModule = Typer(interfaces, strict, extraBuiltins).typeCheck(module)

  /** Strict variant for callers that don't have any imported interfaces.
   *
   *  v2.0 — typer strict mode (undefined-name diagnostics).
   */
  def typeCheckStrict(module: Module): TypedModule =
    Typer(Map.empty, strict = true).typeCheck(module)

  /** Strict variant with additional builtin names injected into the prelude.
   *
   *  Used by `ssc check` to seed plugin-provided intrinsic names (from
   *  `BackendRegistry.inProcess`) so that `extern def route(...)` etc. do
   *  not produce false-positive "undefined name" errors.
   */
  def typeCheckStrict(module: Module, extraBuiltins: Set[String]): TypedModule =
    Typer(Map.empty, strict = true, extraBuiltins).typeCheck(module)

  /** Variant that treats all warnings as errors (`--fatal-warnings`).
   *
   *  `@deprecated` and `@experimental` call-site warnings become type errors
   *  so that `hasErrors` is true and the build fails.
   */
  def typeCheckFatalWarnings(module: Module): TypedModule =
    Typer(Map.empty, strict = false, Set.empty, fatalWarnings = true).typeCheck(module)

  /** Type-check with namespace-collision detection in strict mode (`--strict-namespaces`).
   *
   *  Collisions are hard errors; the build fails if two imported modules
   *  export the same name.  Pass `suppressedCollisions` to acknowledge
   *  known collisions (e.g. from `[Name from Alias]` qualified imports).
   */
  def typeCheckStrictNamespaces(
      module:               Module,
      interfaces:           Map[String, scalascript.ir.ModuleInterface],
      suppressedCollisions: Set[(String, String)] = Set.empty
  ): TypedModule =
    Typer(interfaces, suppressedCollisions = suppressedCollisions, strictNamespaces = true)
      .typeCheck(module)

  /** Type-check with namespace-collision warnings (non-strict, default).
   *
   *  Emits warnings for colliding export names across imports without
   *  failing the build.  Use [[typeCheckStrictNamespaces]] to error instead.
   */
  def typeCheckWithCollisionWarnings(
      module:               Module,
      interfaces:           Map[String, scalascript.ir.ModuleInterface],
      suppressedCollisions: Set[(String, String)] = Set.empty
  ): TypedModule =
    Typer(interfaces, suppressedCollisions = suppressedCollisions)
      .typeCheck(module)

  /** Incremental type-check — companion factory (no imported interfaces, non-strict).
   *
   *  Delegates to [[Typer.typeCheckIncremental]] on a fresh [[Typer]] instance.
   *  The caller is responsible for threading the returned snapshot list into
   *  the next call.
   *
   *  @param module        freshly-parsed [[Module]]
   *  @param prevSnapshots snapshots from the previous run; pass `Nil` to force
   *                       a full re-check (cold start).
   */
  def typeCheckIncrementalModule(
      module:        Module,
      prevSnapshots: List[SectionSnapshot]
  ): (TypedModule, List[SectionSnapshot]) =
    Typer().typeCheckIncremental(module, prevSnapshots)

  /** Incremental type-check with pre-compiled interface scopes (strict mode).
   *
   *  Use this overload in the LSP server so cross-module name resolution is
   *  preserved across incremental runs.
   */
  def typeCheckIncrementalModule(
      module:        Module,
      prevSnapshots: List[SectionSnapshot],
      interfaces:    Map[String, scalascript.ir.ModuleInterface],
      strict:        Boolean
  ): (TypedModule, List[SectionSnapshot]) =
    Typer(interfaces, strict).typeCheckIncremental(module, prevSnapshots)
