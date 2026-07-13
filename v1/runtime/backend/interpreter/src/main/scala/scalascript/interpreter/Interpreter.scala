package scalascript.interpreter

import scalascript.ast.*
import scala.collection.mutable
import scala.collection.immutable.{Map => IMap}
import scala.meta.*

/** A parametric `given` definition that has type parameters and/or `using` dependencies.
 *  Stored in the interpreter's `givenFactories` registry so that `resolveGiven` can
 *  instantiate it on demand by matching the requested typeKey and recursively resolving
 *  the `using` dependencies.
 *
 *  @param name              explicit given name (or empty for anonymous)
 *  @param typeParams        type variable names, e.g. `List("A")`
 *  @param usingDeps         ordered list of (paramName, typeKeyTemplate), e.g.
 *                           `List(("ord", "Ordering[A]"))`
 *  @param returnTypeTemplate the canonical type-key template, e.g. `"Ordering[List[A]]"`
 *  @param givenNode         the original `Defn.Given` AST node (for body evaluation)
 *  @param capturedEnv       environment captured at definition time
 */
/** Monad tag for `direct[M] { ... }` do-notation blocks. */
private[interpreter] enum DirectMonadTag:
  case OptionM // direct[Option]
  case EitherM // direct[Either[E, *]]
  case AsyncM  // direct[Async]  — supports OptionT / EitherT lift
  case ListM   // direct[List]
  case OtherM  // direct[SomeUserMonad] — duck-typed only

private[interpreter] case class TcoInfo(
  tailTargets:   Set[String],
  isSelfTailRec: Boolean,
  noNonTailSelf: Boolean,
  hasSelfNameRef: Boolean
)

private[interpreter] case class RestartableHandle(
  errorQ:  java.util.concurrent.SynchronousQueue[Value],
  resumeQ: java.util.concurrent.SynchronousQueue[Either[Value, Value]]
)

private[interpreter] class RestartableRethrow(val value: Value)
  extends RuntimeException(null, null, true, false)

private case class ParametricGiven(
  name:               String,
  typeParams:         List[String],
  usingDeps:          List[(String, String)],   // (paramName, typeKeyTemplate)
  returnTypeTemplate: String,
  givenNode:          Defn.Given,
  capturedEnv:        Map[String, Value]
)

private[interpreter] case class TypeFieldSchema(
    fieldName:   String,
    storageName: String,
    aliases:     List[String],
    default:     Option[Value],
    key:         Boolean
):
  def storageNames: List[String] = storageName :: aliases

/** Shallow snapshot of all section-populated mutable state in [[Interpreter]].
 *  Used by `ssc watch` to restore the interpreter to its state just before a
 *  changed section, enabling incremental re-eval of only the changed suffix.
 *
 *  Closures in `globals` look up names from `interp.globals` at call time
 *  (see `EvalRuntime` name-lookup — line starting "Pure(env.getOrElse(name,
 *  interp.globals.getOrElse(...)))" ), so restoring the map is safe without
 *  deep-copying the values themselves. */
private[scalascript] case class InterpCheckpoint(
  globals:             Map[String, Value],
  extensions:          Map[(String, String), Value.FunV],
  parentTypes:         Map[String, String],
  typeMethods:         Map[String, Map[String, Value.FunV]],
  typeFieldOrder:      Map[String, List[String]],
  typeFieldTypes:      Map[String, List[String]],
  typeFieldSchemas:    Map[String, List[TypeFieldSchema]],
  rejectUnknownTypes:  Set[String],
  givenFactories:      IndexedSeq[ParametricGiven],
  givenCandidateCount: Map[String, Int],
  mainCalled:          Boolean,
  sqlBlockCounter:     Int,
  typeTagMap:          Map[String, Int] = Map.empty,
  typeTagCounter:      Int = 0,
  valNames:            Set[String] = Set.empty
)

/** Tree-walking interpreter for ScalaScript documents.
 *
 *  Execution model:
 *  - Sections are processed in document order.
 *  - Each scala/ssc code block is executed eagerly (defs bind, exprs run).
 *  - After all sections, `main()` is auto-called if defined and not already invoked.
 *
 *  Algebraic effects use a **Free Monad** representation: `eval` returns a
 *  `Computation` (Pure | Perform). Effect ops produce `Perform` nodes; handlers
 *  walk the tree and dispatch them. `resume(v)` invokes the captured Scala
 *  continuation directly — no replay; side effects in body run exactly once;
 *  multi-shot works by calling the continuation multiple times.
 */
class Interpreter(
    val out:  java.io.PrintStream = System.out,
    private[interpreter] val baseDir:  Option[os.Path]     = None,
    /** When true, `serve(port)` is a no-op: routes still register, but
     *  the HTTP server doesn't bind a port or block on `Thread.join`.
     *  Used by `ssc render` for static-site generation — the route
     *  table is filled in, then handlers are invoked off-band with
     *  synthetic requests. */
    headless: Boolean              = false,
    openApiDryRun: Boolean         = false,
    private[interpreter] val lockPath: Option[os.Path]      = None,
    /** Shared module-evaluation cache, keyed by resolved absolute module path,
     *  for one import-graph run.  `SectionRuntime.runImport` populates it via
     *  `getOrElseUpdate` and threads the *same* map into every child interpreter,
     *  so a module reachable through a diamond is evaluated exactly once instead
     *  of once per DAG path (busi seq-132: a diamond over a large module re-ran it
     *  exponentially → OOM/hang at load time).  Defaults to a fresh map, so a
     *  top-level `Interpreter(...)` owns one cache for its whole run. */
    private[interpreter] val moduleCache: mutable.Map[os.Path, Interpreter] =
      mutable.Map.empty,
    /** Modules **currently being loaded** (on the import-resolution stack), shared
     *  across the whole import-graph run and threaded into every child interpreter
     *  like `moduleCache`.  Insertion-ordered so a detected cycle can be rendered as
     *  a path.  `SectionRuntime.runImport` adds a path before running its module body
     *  and removes it after (in a `finally`); a re-entry on a path still in this set
     *  is a true import cycle (`A→B→A`) — caught and reported as a clear
     *  `InterpretError` instead of recursing into a `StackOverflowError`.  This is
     *  distinct from the `moduleCache` diamond dedup: a diamond is acyclic and never
     *  re-enters a still-loading path. */
    private[interpreter] val moduleLoading: mutable.LinkedHashSet[os.Path] =
      mutable.LinkedHashSet.empty) extends ActorInterp:
  /** Per-interpreter WebSocket route table.  Owning this here means each
   *  `Interpreter` instance has an isolated WS route set — no global lock
   *  or `WsTestLock` synchronization required in tests. */
  val wsRoutes: scalascript.server.WsRoutes = new scalascript.server.WsRoutes()
  val routeRegistry: scalascript.server.RouteRegistry = scalascript.server.Routes
  private[interpreter] val globals      = mutable.HashMap.empty[String, Value]
  /** Set of top-level names defined via `Defn.Val` (immutable). Populated by
   *  `StatRuntime.execStat` at module load. `BytecodeJit.walkLong` /
   *  `walkDouble` use this to inline an `IntV` / `DoubleV` global value as a
   *  Java literal in the compiled body instead of emitting a per-call
   *  `readGlobalLong("name")` HashMap lookup. Names *not* in this set are
   *  treated as potentially mutable (either declared `var`, or reassigned
   *  imperatively elsewhere — `def` rebindings also fall through here since
   *  they're not constants in the readGlobalLong sense). */
  private[interpreter] val valNames     = mutable.HashSet.empty[String]
  private[interpreter] val extensions   = mutable.HashMap.empty[String, mutable.HashMap[String, Value.FunV]]
  // Concrete type → declared parent type (from `extends` clause).  Used by
  // extensionDispatch to find extension methods registered on a sealed parent.
  private[interpreter] val parentTypes  = mutable.Map.empty[String, String]
  // Methods declared inside a `class` / `case class` body, keyed by type name.
  // Stored separately from instance fields so `show` and pattern matching see
  // only data fields.
  private[interpreter] val typeMethods  = mutable.Map.empty[String, Map[String, Value.FunV]]
  // Field declaration order per type — needed for positional `.copy(...)`
  // since `InstanceV.fields` is an unordered Map for instances with more
  // than four fields.
  private[interpreter] val typeFieldOrder = mutable.Map.empty[String, List[String]]
  // Opaque int tags for ADT constructors — enables switch(int) tableswitch in BytecodeJit
  // instead of switch(String) lookupswitch+equals. Tags start at 1 (0 = unregistered).
  private[interpreter] val typeTagMap = mutable.HashMap.empty[String, Int]
  private[interpreter] var typeTagCounter: Int = 0
  /** Return the int tag for `typeName`, allocating a new one if needed. Thread-unsafe
   *  but called only from single-threaded interpreter-init paths in StatRuntime. */
  private[interpreter] def typeTagFor(typeName: String): Int =
    typeTagMap.getOrElseUpdate(typeName, { typeTagCounter += 1; typeTagCounter })
  // Field type names for each case class, parallel to typeFieldOrder.
  // Populated in StatRuntime when a Defn.Class is processed; used by
  // TypedHandlerWrapper.deserializeCaseClass to coerce path/query/body values.
  private[interpreter] val typeFieldTypes = mutable.Map.empty[String, List[String]]
  private[interpreter] val typeFieldSchemas = mutable.Map.empty[String, List[TypeFieldSchema]]
  // Per-case-class constructor default terms + the env they evaluate in.  Used by
  // CallRuntime.callValueNamed to reorder named args into a full positional list
  // (filling omitted fields with their defaults) before the ctor's NativeFnV is
  // called — the ctor itself drops argument names.  Only populated when a class
  // actually declares a default (the common no-default ctor stays untouched).
  private[interpreter] val typeFieldDefaults =
    mutable.Map.empty[String, (List[Option[scala.meta.Term]], Env)]
  private[interpreter] val rejectUnknownTypes = mutable.Set.empty[String]
  // busi-p0-statusval-eventcase-collision — when two bindings with the same
  // name end up in the same scope (canonical case: a `val PeerLinkInvited =
  // PeerLinkStatus("invited")` and a `case PeerLinkInvited(...)` enum case),
  // record the one that gets displaced from `globals(name)` here so a typed
  // `val x: PeerLinkStatus = PeerLinkInvited` ascription can disambiguate
  // back to the val binding by matching `decltpe` against the stored
  // alternative's typeName.  Pattern-position and expression-call(args)
  // continue to use whatever sits in globals(name) — they're not affected
  // by the disambiguation logic.
  private[interpreter] val shadowedAlternatives = mutable.Map.empty[String, Value]
  // Parametric given factories — givens with type parameters and/or using clauses.
  // Stored separately because they can't be stored as plain Values until their type
  // variables are resolved at the call site.
  // Each entry: (name, typeParams, usingDeps[(paramName, typeKeyTemplate)],
  //              returnTypeTemplate, givenNode, capturedEnv)
  private[interpreter] val givenFactories = mutable.ArrayBuffer.empty[ParametricGiven]
  // Track how many `given` definitions are stored under each typeKey — used for
  // ambiguity detection.  Incremented both by concrete givens (in globals) and
  // by parametric factory registrations.
  private[interpreter] val givenCandidateCount = mutable.Map.empty[String, Int]
  private[interpreter] var mainCalled   = false
  private[interpreter] var currentCodeIdentity: Value =
    Value.InstanceV("CodeIdentity", Map(
      "algorithm" -> Value.StringV("sha256"),
      "digest"    -> Value.EmptyStr,
      "format"    -> Value.StringV("unknown"),
      "module"    -> Value.NoneV
    ))
  // Phase 2 lazy loading: set to true after ensurePluginsLoaded() has run.
  private[interpreter] var _pluginsLoaded = false
  private[interpreter] val pluginNativeNames = mutable.Set.empty[String]

  // Effect-runner block-forms (`runLogger { … }`) contributed by installed plugins, keyed by
  // keyword. Populated by installPlugins/ensurePluginsLoaded. Empty until a plugin loads, so a
  // plugin-free script never pays for the lookup. polyglot-libraries §2d.
  private val _blockForms = mutable.Map.empty[String, scalascript.backend.spi.BlockForm]
  private[interpreter] def blockForms: collection.Map[String, scalascript.backend.spi.BlockForm] = _blockForms
  private def installBlockForms(plugins: Iterable[scalascript.backend.spi.Backend]): Unit =
    plugins.foreach(p => _blockForms ++= p.blockForms)
  // busi-p3-ratelimit-intrinsic-shadow — names where a user top-level `def`
  // collides with a plugin intrinsic of the same bare name. Policy: user wins
  // (see specs/intrinsic-shadow-policy.md); we record each colliding name here
  // (once) and emit a `[warn]` to stderr so the shadow is never silent. Tests
  // assert on this set instead of scraping stderr.
  private[interpreter] val shadowedIntrinsicWarnings = mutable.LinkedHashSet.empty[String]

  /** Public read-only view of intrinsic-shadow warnings (names where a user
   *  top-level def took precedence over a plugin intrinsic). For tests/tools. */
  def intrinsicShadowWarnings: collection.Set[String] = shadowedIntrinsicWarnings

  /** Record + report that user definition `name` shadows a plugin intrinsic.
   *  Idempotent per name: warns to stderr only the first time. User wins. */
  private[interpreter] def warnIntrinsicShadow(name: String): Unit =
    if shadowedIntrinsicWarnings.add(name) then
      System.err.println(
        s"[warn] '$name' shadows plugin intrinsic '$name' — user definition wins")

  // busi-p3-module-fn-name-conflict — track which import path last bound each
  // function-like name, so a second import of the same name from a *different*
  // module can be reported. Policy: last import wins + warning (see
  // specs/import-name-conflict-policy.md). Without this the collision was silent
  // and surfaced later as a confusing downstream error in unrelated code.
  private[interpreter] val importedFnOrigin = mutable.Map.empty[String, String]
  private[interpreter] val importConflictWarnings = mutable.LinkedHashSet.empty[String]

  /** Public read-only view of cross-module import-conflict warnings (function
   *  names imported from two different modules). For tests/tools. */
  def importNameConflictWarnings: collection.Set[String] = importConflictWarnings

  /** Record + report that an imported `name` from `newPath` shadows a same-named
   *  function imported earlier from `prevPath`. Idempotent per name. Last wins. */
  private[interpreter] def warnImportConflict(name: String, prevPath: String, newPath: String): Unit =
    if importConflictWarnings.add(name) then
      System.err.println(
        s"[warn] '$name' imported from '$newPath' shadows the '$name' imported from " +
        s"'$prevPath' — last import wins")
  // Effect object names detected as multi-shot by EffectAnalysis (populated in runInit).
  private[interpreter] var multiShotEffects: Set[String] = Set.empty
  // Fully-qualified effect-op names (`Eff.op`) detected by EffectAnalysis (populated in runInit).
  // Used by the fast-while path to bail to the monadic trampoline when a loop performs an effect
  // op that has no active inline resolver (interp-returnclause-effect-in-while).
  private[interpreter] var effectOpNames: Set[String] = Set.empty
  private[interpreter] var sqlBlockRunner: Option[scalascript.backend.spi.SqlBlockRunner] = None
  private[interpreter] var graphqlBlockRunner: Option[scalascript.backend.spi.GraphQLBlockRunner] = None

  /** Load plugin intrinsics on first use.  Called from EvalRuntime when a
   *  Term.Name lookup misses both env and globals — at that point we may have
   *  a plugin-provided name that hasn't been registered yet.  Idempotent. */
  private[interpreter] def ensurePluginsLoaded(): Unit =
    if _pluginsLoaded then return
    _pluginsLoaded = true
    // Commit the bundled-but-opt-in `plugin-available/` packages before
    // collecting inProcess — the essential/advanced split keeps startup fast,
    // and this lazy path (first missing name/extern) is exactly where the
    // advanced set must become reachable (plugin-lazyload-extern-imports).
    scalascript.compiler.plugin.BackendRegistry.loadAvailableNow()
    import scalascript.backend.spi.NativeImpl
    val plugins = scalascript.compiler.plugin.BackendRegistry.inProcess.toList
    val pluginImpls: List[(scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl)] =
      plugins.iterator
        .flatMap(_.intrinsics)
        .collect { case entry @ (_, _: NativeImpl) => entry }
        .toList
    installNativeIntrinsicEntries(pluginImpls)
    installSqlBlockRunners(plugins)
    installBlockForms(plugins)
    installActorRuntimeProviders(plugins)
    installGraphqlBlockRunners(plugins)
    BuiltinsRuntime.setupPluginCompanions(this)

  /** Install exactly the supplied in-process interpreter plugins.
   *
   *  Test harnesses and embedded hosts use this to exercise one plugin (or a
   *  small explicit set) without falling back to ServiceLoader discovery of
   *  every plugin on the classpath. */
  def installPlugins(plugins: Iterable[scalascript.backend.spi.Backend]): Unit =
    import scalascript.backend.spi.NativeImpl
    val pluginImpls: List[(scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl)] =
      plugins.iterator
        .flatMap(_.intrinsics)
        .collect { case entry @ (_, _: NativeImpl) => entry }
        .toList
    installNativeIntrinsicEntries(pluginImpls)
    installSqlBlockRunners(plugins)
    installBlockForms(plugins)
    installActorRuntimeProviders(plugins)
    installGraphqlBlockRunners(plugins)
    BuiltinsRuntime.setupPluginCompanions(this)
    _pluginsLoaded = true

  private def installSqlBlockRunners(plugins: Iterable[scalascript.backend.spi.Backend]): Unit =
    plugins.iterator.flatMap(_.sqlBlockRunner).toList match
      case Nil => ()
      case runners =>
        sqlBlockRunner = Some(runners.last)

  private def installGraphqlBlockRunners(plugins: Iterable[scalascript.backend.spi.Backend]): Unit =
    plugins.iterator.flatMap(_.graphqlBlockRunner).toList match
      case Nil => ()
      case runners =>
        graphqlBlockRunner = Some(runners.last)

  private def installActorRuntimeProviders(plugins: Iterable[scalascript.backend.spi.Backend]): Unit =
    plugins.iterator.collect { case p: ActorRuntimeProviderBackend => p.actorRuntimeProvider }.toList match
      case Nil => ()
      case providers =>
        installActorRuntimeProvider(providers.last)

  // ThreadLocal so concurrent generator virtual threads each get their own counter.
  private[interpreter] val _phIdxTL: ThreadLocal[Int] = ThreadLocal.withInitial(() => 0)
  private inline def placeholderIdx: Int          = _phIdxTL.get()
  private inline def placeholderIdx_=(v: Int): Unit = _phIdxTL.set(v)
  // Tracks the last known source position for error messages (0-based line, 0-based column).
  // -1 = no position known (replaces Option[(Int, Int)] to avoid per-node allocation).
  private[interpreter] var currentSpanLine: Int = -1
  private[interpreter] var currentSpanCol:  Int = -1
  private[interpreter] inline def currentSpan: Option[(Int, Int)] =
    if currentSpanLine < 0 then None else Some((currentSpanLine, currentSpanCol))
  // Source of the code block currently being executed — used to print the
  // offending line under the error message with a caret.
  private[interpreter] var currentSource: String = ""
  // When the parser falls back to wrapping the block in `{ ... }` to accept
  // top-level expressions, every scalameta position is shifted down by one
  // line. `lineOffset` compensates so error messages report the user's line.
  private[interpreter] var lineOffset: Int = 0
  // Phase 2 DAP: source file being debugged (set by DebugCommand before run()).
  private[interpreter] var debugSourceFile: String = ""
  // Phase 5 REPL: when `:load file.ssc` is running, this holds the canonical
  // abs path of the file so that `route()` calls inside register with
  // `source = Some(currentLoadingFile)` and `style = "load"`.
  private[interpreter] var currentLoadingFile: Option[String] = None

  /** Set the `:load`-source hint so that `route()` calls inside the file
   *  record their origin file.  Call with `None` after the load completes. */
  def setLoadingFile(path: Option[String]): Unit = currentLoadingFile = path
  // Phase 2 DAP: debug hooks; None means no-op (normal run).
  private[interpreter] var debugHooks: Option[scalascript.interpreter.debug.DebugHooks] = None
  // Phase 2 DAP: document-level line offset of the current code block.
  // Set by SectionRuntime to cb.lineOffset (0-based line in the .ssc file
  // where the first code line starts), so EvalRuntime can translate
  // scalameta block-relative positions to document-level positions.
  private[interpreter] var debugBlockDocLine: Int = 0

  /** Set the source file path used for breakpoint matching.  Called by
   *  DebugCommand (and tests) before [[run]]. */
  def setDebugSourceFile(path: String): Unit = debugSourceFile = path

  /** Attach debug hooks (e.g. a DAP session's breakpoint handler).  None
   *  means no-op — normal (non-debug) execution.  Called before [[run]]. */
  def setDebugHooks(hooks: Option[scalascript.interpreter.debug.DebugHooks]): Unit =
    debugHooks = hooks

  /** Public read-only view of the REPL's live global bindings.
   *  Used by `:mount name` to look up a previously-defined function.
   *
   *  The returned map is ENRICHED with synthetic `Value.FunV` entries for every
   *  registered case class (keyed by type name).  These FunV entries carry only
   *  `params` / `paramTypes` metadata; their body is a placeholder `Lit.Unit()`
   *  that is never evaluated.  `TypedHandlerWrapper.deserializeCaseClass` reads
   *  the metadata from these entries when the actual constructor `NativeFnV`
   *  would otherwise cause a `case Some(ctor: Value.FunV)` match to fall through. */
  def globalsView: collection.Map[String, Value] =
    if typeFieldOrder.isEmpty then globals
    else
      val enriched = scala.collection.mutable.Map.from(globals)
      typeFieldOrder.foreach { (typeName, fieldNames) =>
        // Only add/replace if the constructor in globals is NOT already a FunV.
        // (After this PR it always will be NativeFnV, but guard for future changes.)
        globals.get(typeName) match
          case Some(_: Value.FunV) => () // already has FunV metadata — skip
          case _ =>
            val fieldTypes = typeFieldTypes.getOrElse(typeName, fieldNames.map(_ => "String"))
            enriched(typeName) = Value.FunV(
              params     = fieldNames,
              body       = scala.meta.Lit.Unit(),
              closure    = Map.empty,
              paramTypes = fieldTypes,
            )
      }
      enriched

  /** The value of the last top-level expression evaluated via [[runSnippet]]
   *  or [[run]].  Used by `:mount { expr }` to retrieve the handler value. */
  def lastResult: Value = lastExprResult

  /** Evaluate `absFilePath` once, detect handler shape, and register it in
   *  [[scalascript.server.Routes]] for `method` + `path`.  `ctx` key-value
   *  pairs are stored in the [[scalascript.server.Routes.Entry]] and passed
   *  as the second argument to 2-arg handler functions.
   *
   *  Called by the REPL `:mount METHOD /path file.ssc [k=v …]` command.
   *  Mirrors the logic in [[scalascript.compiler.plugin.http.HttpIntrinsics]]
   *  `mountFile` helper so both the language intrinsic and the REPL command
   *  share the same file-eval + shape-detection semantics. */
  def mountFileAsRoute(
      method:  String,
      path:    String,
      absFile: String,
      ctx:     Map[String, Value]
  ): Unit =
    import scalascript.parser.Parser
    // Parse optional #functionName suffix
    val (actualFile, fnNameOpt) = if absFile.contains("#") then
      val i = absFile.lastIndexOf('#')
      (absFile.substring(0, i), Some(absFile.substring(i + 1)))
    else (absFile, None)
    val filePath = os.Path(actualFile)
    val childDir = filePath / os.up
    val child    = Interpreter(this.out, Some(childDir), lockPath = this.lockPath)
    child.run(Parser.parse(os.read(filePath)))
    val rawResult = fnNameOpt match
      case Some(fn) =>
        child.globalsView.getOrElse(fn,
          throw InterpretError(s"function '$fn' not found in $actualFile"))
      case None => child.lastExprResult
    val baseHandler: Value = rawResult match
      case fn: Value.FunV if fn.params.length >= 1 => fn
      case other =>
        Value.NativeFnV("mount.static", scalascript.interpreter.Computation.pureFn(_ => other))
    // Wrap typed handlers: auto-deser/ser if the handler uses typed params.
    val handler = TypedHandlerWrapper.wrapIfTyped(
      baseHandler,
      invoke      = (fn, args) => child.invoke(fn, args),
      globalsView = child.globalsView,
      mountedPath = path,
    )
    routeRegistry.register(
      method.toUpperCase, path, handler, this,
      source   = Some(actualFile),
      mountCtx = ctx,
      style    = "mount")

  // Phase 5 DAP: call stack — thread-local because runAsyncParallel evaluates
  // thunks on virtual threads against the same Interpreter instance.
  private final class CallStackState:
    val names = scala.collection.mutable.ArrayBuffer.empty[String]
    val files = scala.collection.mutable.ArrayBuffer.empty[String]
    val lines = scala.collection.mutable.ArrayBuffer.empty[Int]
  private val callStackLocal = ThreadLocal.withInitial(() => new CallStackState)
  private def callStackState: CallStackState = callStackLocal.get()
  private[interpreter] def callStackNonEmpty: Boolean = callStackState.names.nonEmpty
  private[interpreter] def callStackLength: Int = callStackState.names.length
  private[interpreter] def callStackPush(name: String, file: String, line: Int): Unit =
    val s = callStackState
    s.names += name; s.files += file; s.lines += line
  private[interpreter] def callStackPop(): Unit =
    val s = callStackState
    val last = s.names.length - 1
    s.names.remove(last); s.files.remove(last); s.lines.remove(last)
  private[interpreter] def callStackToList: List[(String, String, Int)] =
    val s = callStackState
    s.names.indices.map(i => (s.names(i), s.files(i), s.lines(i))).toList
  private[interpreter] def callStackToIndexedSeq: scala.collection.immutable.IndexedSeq[(String, String, Int)] =
    val s = callStackState
    s.names.indices.map(i => (s.names(i), s.files(i), s.lines(i))).toIndexedSeq
  // When true, currentStackTrace() includes anonymous (<anon>) and _-prefixed frames.
  private[interpreter] var traceVerbose: Boolean = false
  // Types declared with @noTrace — throw uses ScriptExceptionNoTrace to skip JVM fillInStackTrace.
  private[interpreter] val noTraceTypes = mutable.HashSet.empty[String]
  // Last top-level Term result — updated by StatRuntime on every top-level Term eval.
  // Used by evalFileGetResult (mount() intrinsic) to retrieve the handler value from
  // a handler file without requiring the file to assign it to a named binding.
  private[interpreter] var lastExprResult: Value = Value.UnitV
  // Phase 3.2: flag indicating we are inside a direct[Either[...]] block so
  // throw expressions lower to Left(...) instead of raising a ScriptException.
  private[interpreter] val _insideDirectBlock = new java.lang.ThreadLocal[Boolean] {
    override def initialValue() = false
  }
  // DirectMonadTag defined at package level (see top of file / BlockRuntime.scala)

  // ─── Reactive signals (fine-grained reactivity) ──────────────────────
  //
  // Signals are mutable cells with subscriber tracking.  Reading a signal
  // inside an active `effect` block registers a mutual subscription so
  // the effect re-runs when the signal changes.  `computed` is an effect
  // whose body's return value feeds another signal — derived state.
  //
  // We store backing state in a process-local side-table keyed by a
  // monotone id; the user-visible value is an `InstanceV("Signal", {id})`
  // / `InstanceV("Effect", {id})`.  Cross-backend semantics line up
  // because all three backends use the same registry-based push model.

  private[interpreter] class SignalState(var value: Value, val subs: mutable.HashSet[Long])
  private[interpreter] class EffectState(val thunk: Value, val deps: mutable.HashSet[Long])

  private[interpreter] val signals = mutable.HashMap.empty[Long, SignalState]
  private[interpreter] val effects = mutable.HashMap.empty[Long, EffectState]
  private[interpreter] var reactiveCounter: Long = 0L
  // Stack of currently-tracking effect ids.  An effect-thunk that calls
  // another effect (rare but legal) pushes its own id while running.
  private[interpreter] val effectStack = mutable.Stack.empty[Long]
  // Pending effect reruns queued by `signalSet` while we're inside an
  // active flush.  A LinkedHashSet so each effect runs at most once per
  // synchronous transaction (deduplicates the diamond — derived signal
  // and final consumer both react to the same root change) and reruns
  // happen in registration order for determinism.
  private[interpreter] val pendingEffects = mutable.LinkedHashSet.empty[Long]

  // v1.5 Tier 5 #20 — validation collector stack.  Each `validate { … }`
  // block pushes a fresh ordered map; the `require*` natives check the
  // head of the stack: when present they record the error and return a
  // type-appropriate default so the body keeps running and accumulates
  // every problem in one pass.  When empty (handler called require*
  // outside a validate block) the call throws as before.
  private[interpreter] val validationStack: mutable.Stack[mutable.LinkedHashMap[String, String]] =
    mutable.Stack.empty
  private[interpreter] var reactiveFlushing = false

  // ── v1.16 Restartable errors — see EffectsRuntime.scala ─────────────
  // RestartableHandle / RestartableRethrow defined at package level above.
  private val _restartableTL =
    new ThreadLocal[java.util.ArrayDeque[RestartableHandle]]()
  private[interpreter] def restartableStack(): java.util.ArrayDeque[RestartableHandle] =
    var s = _restartableTL.get()
    if s == null then { s = new java.util.ArrayDeque(); _restartableTL.set(s) }
    s

  /** Per-function-body cache of the TCO classification — see TcoRuntime.tcoInfoFor.
   *  Keyed by body Term identity rather than FunV identity so that fn.copy(closure=…)
   *  (used in the typeMethods dispatch path) hits the cache instead of re-traversing
   *  the AST on every class method call. Safe because TcoInfo depends only on the body
   *  and the function name, both of which are preserved across closure-only copies. */
  private[interpreter] val tcoCache: java.util.IdentityHashMap[scala.meta.Term, TcoInfo] =
    java.util.IdentityHashMap()

  /** Reusable tail-call sentinel objects: avoids allocating a new TailCall /
   *  MutualTailCall on every tail-recursive iteration.  The trampoline sets
   *  the mutable `args`/`f` fields before throwing and reads them in the
   *  catch — single-threaded interpreter guarantees no data races. */
  private[interpreter] val tailCallSig:       TailCall       = new TailCall(Nil)
  private[interpreter] val mutualTailCallSig: MutualTailCall = new MutualTailCall(null, Nil)

  /** Intern table from `Lit` AST nodes to the `Computation` they evaluate
   *  to. The parsed AST is reused across all evaluations, so for hot
   *  loop literals (`0`, `1`, `2`, …) this saves a fresh `Pure(IntV(...))`
   *  allocation on every visit. */
  private[interpreter] val litCache: java.util.IdentityHashMap[Lit, Computation] =
    java.util.IdentityHashMap()

  /** Cache of fully-pure-literal compound expressions to their evaluated
   *  Value. A Term qualifies if its entire subtree is built from `Lit.*`,
   *  `Term.Tuple`, and `Term.ApplyInfix` with pure ops (`+`, `-`, `*`, `/`,
   *  `%`, `++`) — no `Term.Name` reads, no Term.Apply, no dispatch through
   *  env. Such a term evaluates to the same Value on every visit, so a
   *  per-AST-identity memo turns N evaluations into N–1 HashMap.gets.
   *
   *  Sentinel `NotPure` (a marker AnyRef) records "we checked purity and it
   *  bailed", so subsequent visits skip the purity walk. Populated lazily
   *  via `pureConstFor(t)` at the Term.Tuple and Term.ApplyInfix eval cases. */
  private[interpreter] val pureConstCache: java.util.IdentityHashMap[Term, AnyRef] =
    java.util.IdentityHashMap()
  private[interpreter] val NotPure: AnyRef = new AnyRef

  // ── v1.10 Generator — thread-per-generator, SynchronousQueue handshake ─
  // Each `generator { body }` spins a virtual thread that runs the body.
  // `suspend(v)` inside the body does queue.put(Some(v)), blocking until
  // the consumer calls .next() / .foreach() / .toList.
  // Combinators (map/filter/take/drop) chain virtual threads in a pipeline.
  private[interpreter] type GenQueue = java.util.concurrent.SynchronousQueue[Option[Value]]
  private[interpreter] val _genQueueTL = new ThreadLocal[GenQueue]()

  // ── v1.9 Coroutines — two-way suspend/resume via virtual threads ──────
  // Protocol (lazy-start):
  //   coroutineCreate: starts T but T immediately blocks on toBody.take()
  //   coroutineResume: toBody.put(in); result = fromBody.take()
  //   suspend(out):    fromBody.put(Yielded(out)); toBody.take()
  // fromBody is a capacity-1 LinkedBlockingQueue so the body can always put
  // without blocking — prevents deadlock when coroutineCancel removes the
  // handle before the body has a chance to drain.
  private[interpreter] case class CoHandle(
    fromBody:   java.util.concurrent.LinkedBlockingQueue[Value],
    toBody:     java.util.concurrent.SynchronousQueue[Value],
    bodyThread: java.util.concurrent.atomic.AtomicReference[Thread]
  )
  private[interpreter] val _coHandleTL = new ThreadLocal[CoHandle]()
  private[interpreter] lazy val coHandles = new java.util.concurrent.ConcurrentHashMap[Long, CoHandle]()
  private[interpreter] val nextCoId     = new java.util.concurrent.atomic.AtomicLong(0L)

  // ─── Generator combinator view — see CoroutineRuntime.scala ────────────

  private[interpreter] def makeGeneratorV(queue: GenQueue): Value =
    CoroutineRuntime.makeGeneratorV(queue, this)

  // ── v1.21 Dataset — see DatasetRuntime.scala ──────────────────────────


  private[interpreter] object NativeFeatureKeys:
    val HttpBaseUrl      = scalascript.backend.spi.NativeContextFeatureKeys.HttpBaseUrl
    val HttpTimeoutMs    = scalascript.backend.spi.NativeContextFeatureKeys.HttpTimeoutMs
    val HttpMaxRetries   = scalascript.backend.spi.NativeContextFeatureKeys.HttpMaxRetries
    val HttpRetryDelayMs = scalascript.backend.spi.NativeContextFeatureKeys.HttpRetryDelayMs

  private lazy val nativeFeatureState = new java.util.concurrent.ConcurrentHashMap[String, Any]()
  private lazy val nativeFeatureLocalState = new java.util.concurrent.ConcurrentHashMap[String, ThreadLocal[Any]]()

  private[interpreter] def nativeFeatureGet(key: String): Option[Any] =
    Option(nativeFeatureState.get(key))
  private[interpreter] def nativeFeatureSet(key: String, value: Any): Unit =
    nativeFeatureState.put(key, value); ()
  private[interpreter] def nativeFeatureRemove(key: String): Option[Any] =
    Option(nativeFeatureState.remove(key))

  private def nativeFeatureLocalSlot(key: String): ThreadLocal[Any] =
    nativeFeatureLocalState.computeIfAbsent(key, _ => new ThreadLocal[Any]())
  private[interpreter] def nativeFeatureLocalGet(key: String): Option[Any] =
    Option(nativeFeatureLocalSlot(key).get())
  private[interpreter] def nativeFeatureLocalSet(key: String, value: Any): Unit =
    nativeFeatureLocalSlot(key).set(value)
  private[interpreter] def nativeFeatureLocalRemove(key: String): Option[Any] =
    val slot = nativeFeatureLocalSlot(key)
    val old = Option(slot.get())
    slot.remove()
    old

  private[interpreter] def httpBaseUrlState: String =
    nativeFeatureLocalGet(NativeFeatureKeys.HttpBaseUrl).collect { case s: String => s }.getOrElse("")
  private[interpreter] def httpTimeoutMsState: Long =
    nativeFeatureLocalGet(NativeFeatureKeys.HttpTimeoutMs).collect { case n: Long => n }.getOrElse(30_000L)
  private[interpreter] def httpMaxRetriesState: Int =
    nativeFeatureLocalGet(NativeFeatureKeys.HttpMaxRetries).collect { case n: Int => n }.getOrElse(0)
  private[interpreter] def httpRetryDelayMsState: Long =
    nativeFeatureLocalGet(NativeFeatureKeys.HttpRetryDelayMs).collect { case n: Long => n }.getOrElse(1_000L)

  // ── v1.4 Cache effect — EXTRACTED to `cache-effect-plugin` (the TTL store + bypass moved
  //    into the plugin; the handler is reached via `Backend.blockForms`). ────────────────

  // ── Async parallel driver — future table (see AsyncRuntime.scala) ─────
  private[interpreter] lazy val parallelFutures =
    new java.util.concurrent.ConcurrentHashMap[Long, java.util.concurrent.Future[Value]]()
  private val parallelFutureSeq = new java.util.concurrent.atomic.AtomicLong(0L)
  private[interpreter] def freshFutureId(): Long = parallelFutureSeq.incrementAndGet()

  // ── v1.4 Auth effect — current user (thread-local) ────────────────────
  private[interpreter] val _authUser = ThreadLocal.withInitial[Option[Value]](() => None)
  // Receive-spec boxing: we can't squeeze AST cases into `Value`, so
  // `receive { case … }` stashes (cases, env) in a side map and the
  // Perform's args carry just the opaque integer token.
  private[interpreter] val receiveSpecs    = mutable.LongMap.empty[(List[Case], Env)]
  private[interpreter] var receiveSpecNext = 0L

  /** Cache of compiled `Term.Match` handlers, keyed by AST node identity.
   *  Populated lazily on the first evaluation of each match expression.
   *  Avoids per-call Option/List allocations in the matchPat hot path. */
  private[interpreter] val matchCache: java.util.IdentityHashMap[scala.meta.Term.Match, PatternRuntime.CompiledMatch] =
    java.util.IdentityHashMap()

  /** Cache of compiled `Term.PartialFunction` handlers, keyed by AST node identity.
   *  Reuses CompiledMatch machinery so partial function calls also skip matchPat Option
   *  allocations and the iterator.flatMap chain. */
  private[interpreter] val pfCache: java.util.IdentityHashMap[scala.meta.Term.PartialFunction, PatternRuntime.CompiledMatch] =
    java.util.IdentityHashMap()

  /** Cache of compiled pure function bodies (the A4 Tier-2b pure-call target).
   *  Value is either a `PatternRuntime.SlotBody` (the cached compiled body) or
   *  the `PatternRuntime.PureBodyMiss` sentinel (tried, body outside the
   *  fast-path subset). Keyed by AST node identity — function body terms are
   *  stable across `FunV` rebuilds since the AST is immutable. Lets
   *  `EvalRuntime.pureCallValue` fold pure non-`Match`-bodied 1- or 2-param
   *  function calls (e.g. `def f(x: Int): Int = x + 1`) directly to a `Value`,
   *  skipping the per-call `Pure` wrapper and the param `FrameMap` allocation. */
  private[interpreter] val pureBodyCache: java.util.IdentityHashMap[scala.meta.Term, AnyRef] =
    java.util.IdentityHashMap()

  /** Cache of `closure.updated(name, f)` per FunV — the self-ref binding
   *  is identical on every invocation of the same closure, so we save
   *  one HashMap.updated allocation per call. */
  private[interpreter] val closureWithSelfCache: java.util.IdentityHashMap[Value.FunV, Env] =
    java.util.IdentityHashMap()

  /** Cache of derived `summon[TC[T]]` lookup strings, keyed by the
   *  `Term.ApplyType` AST node. Each summon eval otherwise rebuilds the same
   *  strings from the immutable type argument every time: the `typeToString`
   *  key (`"Monoid[A]"`), and the synthetic context-bound param name
   *  (`"A$Monoid"`). Both are pure functions of the AST node, so they're
   *  computed once. Value layout: `Array(key, syntheticParamName)`, where
   *  `syntheticParamName` is `null` for non-generic keys. Only the env/global
   *  *lookups* using these strings remain per-call (they depend on runtime
   *  scope). JFR on typeclass-fold: this path was the dominant String/byte[]
   *  allocator after the curried-dispatch fix (`summon[Monoid[A]]` fires twice
   *  per `combineAll`). */
  private[interpreter] val summonKeyCache: java.util.IdentityHashMap[scala.meta.Term, Array[String]] =
    java.util.IdentityHashMap()

  /** jit-foldleft-tc: memoize the evaluated `(empty, combine)` of a typeclass fold
   *  `xs.foldLeft(summon[M].empty)(summon[M].combine)`, keyed per call-site by the
   *  resolved given identity. Repeat calls (same monoid) skip re-walking the
   *  `summon[M].empty` / `summon[M].combine` sub-expressions — which the JFR shows
   *  is the dominant `evalCore` dispatch cost of `combineAll`-style folds. A
   *  monomorphic inline cache: slot layout `[monoid: Value, zV: Value, gV: Value]`,
   *  keyed by the combine `Term` node; a different given identity re-resolves. */
  private[interpreter] val foldTcMemo: java.util.IdentityHashMap[scala.meta.Term, Array[AnyRef]] =
    java.util.IdentityHashMap()

  /** Cache params.toArray for 3+ param functions, keyed by body identity.
   *  Avoids re-allocating the Array[String] on every call to the same function. */
  private[interpreter] val paramsArrayCache: java.util.IdentityHashMap[scala.meta.Term, Array[String]] =
    java.util.IdentityHashMap()

  /** Cache "does this method body reference `this`?" keyed by body identity, so a
   *  type-method dispatch only pays the `this`-binding allocation for bodies that
   *  actually use it (busi seq-121). */
  private val methodUsesThisCache: java.util.IdentityHashMap[scala.meta.Tree, java.lang.Boolean] =
    java.util.IdentityHashMap()
  private[interpreter] def methodUsesThis(body: scala.meta.Tree): Boolean =
    val cached = methodUsesThisCache.get(body)
    if cached != null then cached.booleanValue
    else
      var found = false
      body.traverse { case _: scala.meta.Term.This => found = true }
      methodUsesThisCache.put(body, java.lang.Boolean.valueOf(found))
      found

  /** Cache (paramNames, paramTypes) for each lambda parameter clause.
   *  A lambda evaluated in a tight loop recreates these Lists on every iteration;
   *  caching by ParamClause identity saves O(n_params) allocations per pass. */
  private[interpreter] val paramInfoCache:
      java.util.IdentityHashMap[scala.meta.Term.ParamClause, (List[String], List[String])] =
    java.util.IdentityHashMap()

  /** Cache of `Pure(FunV)` for lambda literals that capture nothing — i.e. whose
   *  closure is empty because every free name resolves to `globals` at call time.
   *  Such a `FunV` is invariant across evaluations of the same AST node (lexical
   *  scoping fixes the free-var set, and an empty closure re-reads globals live),
   *  so a lambda re-evaluated in a tight loop (e.g. `xs.foreach(s => …)` inside a
   *  `while`) need not rebuild the FunV — or even walk the env — each iteration.
   *  Only empty-closure results are stored: a genuine capture differs per call. */
  private[interpreter] val emptyClosureFunCache: java.util.IdentityHashMap[scala.meta.Term.Function, Computation] =
    java.util.IdentityHashMap()

  /** Cache of the distinct `Term.Name` strings appearing anywhere in a lambda body
   *  (effect-cps-continuation env slice), keyed by the `Term.Function` AST node. Used
   *  to build a closure by capturing ONLY the names the body could reference, instead
   *  of iterating the whole env — a lambda created in a large env (e.g. a multi-shot
   *  handler's `opt => resume(opt)` re-evaluated per `perform`, where the env holds all
   *  the accumulated continuation vars) used to `foreachEntry` the entire env. The set
   *  is a SOUND over-approximation of the free vars (it includes locally-bound and
   *  method names too — harmless: a non-captured name simply isn't in the env, and a
   *  shadowed one is overridden at call time), so capture is never under-approximated. */
  private[interpreter] val lambdaFreeNamesCache: java.util.IdentityHashMap[scala.meta.Term, Array[String]] =
    java.util.IdentityHashMap()

  /** effect-cps-p41 (specs/effect-vm-continuations.md §5): per-block-AST compiled
   *  straight-line effectful-block plan. Keyed by the `stats` List object identity (stable
   *  for a given `Term.Block` node). A hit is an `Array[BlockRuntime.CStep]` (the
   *  pre-classified segments a multi-shot `resume` replays instead of re-walking `step`);
   *  the `EffBlockMiss` sentinel records a previous bail (non-straight-line block) so the
   *  one-time recognition runs once per block AST. The structural classification cached here
   *  is context-free (val-vs-expr, names, pure-arith closures); the perform-vs-pure runtime
   *  determination is NOT cached (it depends on the live handler/resolver scope). */
  private[interpreter] val effBlockCache: java.util.IdentityHashMap[AnyRef, AnyRef] =
    java.util.IdentityHashMap()
  private[interpreter] val EffBlockMiss: AnyRef = new AnyRef

  /** interp-var-scope-leak-across-calls: per-block-AST cache of the `var` names a
   *  block DECLARES via a single-`Pat.Var` `Defn.Var` — the only var shape that
   *  dual-writes `interp.globals` (BlockRuntime `step`). Keyed by the `stats` List
   *  object identity (stable for a given `Term.Block`). Used to snapshot+restore the
   *  shadowed OUTER globals binding around a block so a callee's `var X` cannot
   *  clobber a caller's live `var X`. An empty array means "declares no globals-
   *  writing var" (the common case) — cached so the one-time scan runs once per AST. */
  private[interpreter] val blockVarNamesCache: java.util.IdentityHashMap[AnyRef, Array[String]] =
    java.util.IdentityHashMap()

  /** Cache of `typeToString` results by AST identity.  Type AST nodes (the
   *  argument of `summon[…]` and friends) are immutable — recursing across
   *  `Type.Apply`/`Type.Name` and string-building takes O(n) on each visit,
   *  but the result is invariant for a given AST node.  Caching by identity
   *  turns the per-iter `summon[Monoid[A]]` cost from O(n_typeTokens) string
   *  concat into a single hash lookup. */
  private[interpreter] val typeStringCache: java.util.IdentityHashMap[scala.meta.Type, String] =
    java.util.IdentityHashMap()

  /** Cache of JIT-compiled while-loop runners, keyed by `Term.While` AST identity.
   *  Value is a `WhileJitEntry` (success) or `EvalRuntime.WhileJitMiss`
   *  (compilation failed — don't retry). Absent key = not yet attempted. */
  private[interpreter] val whileJitCache: java.util.IdentityHashMap[scala.meta.Term.While, AnyRef] =
    java.util.IdentityHashMap()

  /** Parallel cache for `tryWhileJitMixed` (fused while + foreach). Keyed by
   *  the foreach `Term.Apply` node (unique per while body), not the While node,
   *  to avoid collision with `whileJitCache`. */
  private[interpreter] val whileMixedJitCache: java.util.IdentityHashMap[scala.meta.Term, AnyRef] =
    java.util.IdentityHashMap()

  private[interpreter] def closureWithSelfFor(f: Value.FunV): Env =
    if f.name.isEmpty then f.closure
    else
      val cached = closureWithSelfCache.get(f)
      if cached != null then cached
      else
        // FrameMap1 instead of HashMap.updated: O(1) first-slot lookup for the self-ref,
        // and the cached value is reused on every call to the same named function.
        val w = FrameMap.one(f.name, f, f.closure)
        closureWithSelfCache.put(f, w)
        w

  /** Format a position prefix like "[line 5, col 3] " or "" if unknown. */
  private def posPrefix: String = currentSpan match
    case Some((line, col)) => s"[line ${line + 1}, col ${col + 1}] "
    case None              => ""

  /** Render the source line at `currentSpan` with a caret underneath, or
   *  empty string if no position / source is known. Two-line output, indented
   *  so it lines up cleanly under the error message. */
  private def sourceContext: String = currentSpan match
    case Some((line, col)) if currentSource.nonEmpty =>
      val lines = currentSource.split("\n", -1)
      if line < 0 || line >= lines.length then ""
      else
        val src    = lines(line).stripTrailing
        val gutter = s"${line + 1}"
        val pad    = " " * gutter.length
        val caret  = " " * col.max(0).min(src.length) + "^"
        s"\n  $gutter | $src\n  $pad | $caret"
    case _ => ""

  /** Prefix `msg` with position info and throw InterpretError with source
   *  context appended underneath. */
  private[interpreter] def located(msg: String): Nothing =
    throw InterpretError(s"$posPrefix$msg$sourceContext")

  /** Update currentSpan from a scalameta tree's position (no-op if position is empty). */
  private[interpreter] def trackPos(tree: scala.meta.Tree): Unit =
    val p = tree.pos
    if !p.isEmpty then
      currentSpanLine = p.startLine - lineOffset
      currentSpanCol  = p.startColumn

  // ─── Public API ──────────────────────────────────────────────────

  /** Module-level `dependencies:` from the front-matter, captured at the
   *  top of `run` so any `[Card](dep://card.ssc)` import in this module
   *  can rewrite its scheme through `ImportResolver`. */
  private[interpreter] var moduleDeps: Map[String, String] = Map.empty
  private[interpreter] var modulePkg: List[String] = Nil
  // The module's declared `exports:` surface (empty ⇒ none declared ⇒ permissive).
  // Gates explicit `[x](M)` import resolution, mirroring the JS/JVM backends; the
  // transitive call-time dump (childCtx → parent globals) is independent of this.
  private[interpreter] var moduleExports: Set[String] = Set.empty
  private[interpreter] var i18nTranslations: Map[String, Map[String, String]] = Map.empty
  private[interpreter] var i18nLocale: String = "en"
  private[interpreter] var frontmatterSchemas: Map[String, scalascript.ast.TypeSchemaDecl] = Map.empty

  /** v1.26 — JDBC connections declared in front-matter `databases:`,
   *  materialised lazily and cached.  `sql` fenced blocks resolve their
   *  connection through this registry unless a `given`-style override
   *  (a `Value.Foreign("Connection", _)` bound to the `Connection`
   *  global) is in scope.  Empty by default — modules without any
   *  `databases:` section pay no JDBC cost. */
  private[interpreter] var sqlRegistry: scalascript.sql.ConnectionRegistry =
    scalascript.sql.ConnectionRegistry.empty
  private[interpreter] var sqlBlockCounter: Int = 0
  private case class RemoteHandlerEntry(info: scalascript.backend.spi.RemoteHandlerInfo)
  private lazy val remoteHandlerRegistry =
    new java.util.concurrent.ConcurrentHashMap[String, RemoteHandlerEntry]()

  def run(module: Module): Unit =
    runInit(module)
    SectionRuntime.runModuleSections(module, this)
    autoCallMain()
    autoRunView(module)

  /** Builtins + manifest/config setup without running sections.
   *  Extracted so [[runWithCheckpoints]] can share it without duplicating code. */
  private def runInit(module: Module): Unit =
    BuiltinsRuntime.initBuiltins(this)
    currentCodeIdentity = computeCodeIdentity(module)
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    modulePkg  = module.manifest.flatMap(_.pkg).getOrElse(Nil)
    moduleExports = module.manifest.map(_.exports.toSet).getOrElse(Set.empty)
    module.manifest.foreach(m => i18nTranslations = m.translations)
    frontmatterSchemas = module.manifest.map(_.schemas.map(s => s.typeName -> s).toMap).getOrElse(Map.empty)
    module.manifest.foreach { m =>
      if m.databases.nonEmpty then
        sqlRegistry = scalascript.sql.ConnectionRegistry(
          m.databases.map { d =>
            scalascript.sql.DatabaseSpec(d.name, d.url, d.user, d.password, d.driver)
          }
        )
    }
    // Populate the global config registry from front-matter raw map and any
    // fenced config blocks (```yaml/json/hocon config ["name"] ... ```) in the source.
    // m.raw contains the full SimpleYaml-parsed front-matter; we lift it into a
    // ConfigValue tree so ${config:path} cross-refs work in databases: secrets,
    // and the `config` intrinsic is available in code.
    // Fenced blocks are extracted from the raw source text (Phase 4) and merged
    // on top: named blocks scope to config.<name>.*, unnamed blocks merge at root.
    // Later blocks win within their tier (document order).
    val fencedBlocks: List[scalascript.config.FencedConfigBlock] =
      module.sourceText match
        case Some(src) => scalascript.config.FencedConfigExtractor.extractAndParse(src)
        case None      => Nil
    val hasFrontmatter: Boolean =
      module.manifest.exists(_.raw.nonEmpty)
    if hasFrontmatter || fencedBlocks.nonEmpty then
      // Build a base ConfigValue from front-matter (direct lift, no re-serialization).
      val fmCv: scalascript.config.ConfigValue =
        module.manifest.filter(_.raw.nonEmpty).map { m =>
          scalascript.config.ConfigValue.Map(
            m.raw.map { case (k, v) => k -> scalascript.config.ConfigValue.from(v) }
          )
        }.getOrElse(scalascript.config.ConfigValue.empty)
      // Parse and scope fenced blocks, then merge on top of front-matter.
      // Blocks win over front-matter (Priority.Blocks > Priority.Frontmatter).
      val fencedCvs: List[scalascript.config.ConfigValue] =
        fencedBlocks.flatMap { block =>
          scalascript.config.ConfigParser.parse(block.content, block.format).toOption.map { parsed =>
            if block.name.isEmpty then parsed
            else scalascript.config.ConfigValue.empty.set(block.name, parsed)
          }
        }
      val merged = scalascript.config.MergeEngine.mergeAll(
        frontmatter   = fmCv,
        externalFiles = scalascript.config.ConfigRegistry.getSidecar.toList,
        blocks        = fencedCvs,
      )
      // Resolve substitutions (${env:X} and ${config:X}) on the merged tree.
      scalascript.config.SubstitutionEngine.resolveTree(merged, sys.env.get,
        configLookup = path => merged.get(path).flatMap(_.getString)
      ).foreach(scalascript.config.ConfigRegistry.set)
    // Register the `config` global: an InstanceV whose native methods delegate
    // to ConfigAccessor so ScalaScript code can call
    //   config.getString("server.port")
    //   config.getInt("server.port").getOrElse(8080)
    //   config.getBool("features.darkMode").getOrElse(false)
    val cfgAccessor = scalascript.config.ConfigAccessor.fromRegistry()
    globals("config") = Value.InstanceV("Config", Map(
      "getString" -> Value.NativeFnV("config.getString", Computation.pureFn {
        case List(Value.StringV(path)) => cfgAccessor.getString(path) match
          case Some(s) => Value.OptionV(Value.StringV(s))
          case None    => Value.NoneV
        case _ => throw InterpretError("config.getString(path: String)")
      }),
      "getInt" -> Value.NativeFnV("config.getInt", Computation.pureFn {
        case List(Value.StringV(path)) => cfgAccessor.getInt(path) match
          case Some(n) => Value.OptionV(Value.intV(n.toLong))
          case None    => Value.NoneV
        case _ => throw InterpretError("config.getInt(path: String)")
      }),
      "getDouble" -> Value.NativeFnV("config.getDouble", Computation.pureFn {
        case List(Value.StringV(path)) => cfgAccessor.getDouble(path) match
          case Some(d) => Value.OptionV(Value.doubleV(d))
          case None    => Value.NoneV
        case _ => throw InterpretError("config.getDouble(path: String)")
      }),
      "getBool" -> Value.NativeFnV("config.getBool", Computation.pureFn {
        case List(Value.StringV(path)) => cfgAccessor.getBool(path) match
          case Some(b) => Value.OptionV(Value.boolV(b))
          case None    => Value.NoneV
        case _ => throw InterpretError("config.getBool(path: String)")
      }),
      "requireString" -> Value.NativeFnV("config.requireString", Computation.pureFn {
        case List(Value.StringV(path)) => Value.StringV(cfgAccessor.requireString(path))
        case _ => throw InterpretError("config.requireString(path: String)")
      }),
      "requireInt" -> Value.NativeFnV("config.requireInt", Computation.pureFn {
        case List(Value.StringV(path)) => Value.intV(cfgAccessor.requireInt(path).toLong)
        case _ => throw InterpretError("config.requireInt(path: String)")
      }),
      "requireBool" -> Value.NativeFnV("config.requireBool", Computation.pureFn {
        case List(Value.StringV(path)) => Value.boolV(cfgAccessor.requireBool(path))
        case _ => throw InterpretError("config.requireBool(path: String)")
      }),
    ))
    module.manifest.foreach { m =>
      // Only apply frontmatter frontend: when --frontend was NOT passed on the CLI.
      // CLI selection takes precedence; frontmatter is the per-file default.
      if scalascript.frontend.FrontendFrameworks.selectedName.isEmpty then
        m.frontendFramework.foreach(scalascript.frontend.FrontendFrameworks.setBackend)
      // Thread @model declarations into the NativeContext feature bag so that
      // FrontendIntrinsics.uiBuildModule can populate FrontendModule.models.
      if m.models.nonEmpty then
        nativeFeatureSet("scalascript.frontend.models", m.models)
    }
    module.document.foreach { doc =>
      nativeFeatureSet(scalascript.backend.spi.NativeContextFeatureKeys.ContentDocument, doc)
    }
    // Populate multiShotEffects for one-shot violation checks in evalHandle.
    // Recurse through `subsections` — code blocks under `##`/`###` headings are nested,
    // not in the top-level section content. Without the recursion a `multi effect` declared
    // in a subsection was never seen, so its handler was wrongly treated as one-shot
    // ("One-shot violation" the second time `resume` was called).
    def collectScalaTrees(secs: List[Section]): List[scala.meta.Tree] =
      secs.flatMap { s =>
        s.content.collect {
          case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
            cb.tree.map(ScalaNode.fold(_)(identity))
        }.flatten ++ collectScalaTrees(s.subsections)
      }
    val allTrees: List[scala.meta.Tree] = collectScalaTrees(module.sections)
    val effectAnalysis = scalascript.transform.EffectAnalysis.analyze(allTrees)
    multiShotEffects = effectAnalysis.multiShotEffects
    effectOpNames = effectAnalysis.effectOps
    registerFrontmatterRoutes(module)
    registerFrontmatterRemoteHandlers(module)

  private def computeCodeIdentity(module: Module): Value =
    val (format, bytes) = module.sourceText match
      case Some(src) => ("ssc", src.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      case None      => ("sscc", scalascript.ast.SsccFormat.write(module))
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString
    Value.InstanceV("CodeIdentity", Map(
      "algorithm" -> Value.StringV("sha256"),
      "digest"    -> Value.StringV(digest),
      "format"    -> Value.StringV(format),
      "module"    -> module.manifest.flatMap(_.name).map(name => Value.OptionV(Value.StringV(name))).getOrElse(Value.NoneV)
    ))

  private def autoCallMain(): Unit =
    if !mainCalled then
      globals.get("main").foreach {
        case f: Value.FunV if f.params.isEmpty => Computation.run(callFun(f, Nil)); mainCalled = true
        case _ => ()
      }

  /** `def view()` convention: when a UI frontend backend is explicitly selected
   *  (front-matter `frontend:` / `--frontend` / inline) and the module defines a
   *  zero-arg top-level `view` (and no `main` ran), render it through the active
   *  backend — `serve(view(), 8080)` for a web backend (React/SSR SPA) or
   *  `emit(view(), "tui-out")` for a native backend (e.g. the ratatui `tui`
   *  crate). This lets one `.ssc` compile to web OR terminal with NO web-specific
   *  `serve(..., port)` in the source — selection is purely the frontend backend.
   *  Gated on an explicit frontend selection so non-UI modules with a stray
   *  `view` def are never auto-served. */
  private def autoRunView(module: Module): Unit =
    if mainCalled then return
    if scalascript.frontend.FrontendFrameworks.selectedName.isEmpty then return
    if SectionRuntime.moduleCallsUiEntry(module) then return // the module renders itself explicitly
    globals.get("view") match
      case Some(f: Value.FunV) if f.params.isEmpty =>
        val isWeb =
          try scalascript.frontend.FrontendFrameworks.current().supportedPlatforms
            .contains(scalascript.frontend.Platform.Web)
          catch case _: Throwable => true
        val viewCall = scala.meta.Term.Apply(scala.meta.Term.Name("view"), scala.meta.Term.ArgClause(Nil))
        val entry =
          if isWeb then
            scala.meta.Term.Apply(scala.meta.Term.Name("serve"),
              scala.meta.Term.ArgClause(List(viewCall, scala.meta.Lit.Int(8080))))
          else
            scala.meta.Term.Apply(scala.meta.Term.Name("emit"),
              scala.meta.Term.ArgClause(List(viewCall, scala.meta.Lit.String("tui-out"))))
        evalTerm(entry)
        mainCalled = true
      case _ => ()

  /** Snapshot the section-populated mutable state for incremental re-eval.
   *  Called by [[runWithCheckpoints]] after each section. */
  private[interpreter] def takeCheckpoint(): InterpCheckpoint =
    InterpCheckpoint(
      globals             = globals.toMap,
      extensions          = extensions.iterator.flatMap { case (t, mm) => mm.iterator.map { case (m, f) => (t, m) -> f } }.toMap,
      parentTypes         = parentTypes.toMap,
      typeMethods         = typeMethods.toMap,
      typeFieldOrder      = typeFieldOrder.toMap,
      typeFieldTypes      = typeFieldTypes.toMap,
      typeFieldSchemas    = typeFieldSchemas.toMap,
      rejectUnknownTypes  = rejectUnknownTypes.toSet,
      givenFactories      = givenFactories.toIndexedSeq,
      givenCandidateCount = givenCandidateCount.toMap,
      mainCalled          = mainCalled,
      sqlBlockCounter     = sqlBlockCounter,
      typeTagMap          = typeTagMap.toMap,
      typeTagCounter      = typeTagCounter,
      valNames            = valNames.toSet
    )

  /** Restore interpreter to an earlier checkpoint, undoing any state added
   *  after that checkpoint was taken. */
  private[interpreter] def restoreCheckpoint(cp: InterpCheckpoint): Unit =
    globals.clear();             globals             ++= cp.globals
    extensions.clear()
    cp.extensions.foreach { case ((typeName, method), fn) =>
      extensions.getOrElseUpdate(typeName, mutable.HashMap.empty)(method) = fn
    }
    parentTypes.clear();         parentTypes         ++= cp.parentTypes
    typeMethods.clear();         typeMethods         ++= cp.typeMethods
    typeFieldOrder.clear();      typeFieldOrder      ++= cp.typeFieldOrder
    typeFieldTypes.clear();      typeFieldTypes      ++= cp.typeFieldTypes
    typeFieldSchemas.clear();    typeFieldSchemas    ++= cp.typeFieldSchemas
    rejectUnknownTypes.clear();  rejectUnknownTypes  ++= cp.rejectUnknownTypes
    givenFactories.clear();      givenFactories      ++= cp.givenFactories
    givenCandidateCount.clear(); givenCandidateCount ++= cp.givenCandidateCount
    mainCalled      = cp.mainCalled
    sqlBlockCounter = cp.sqlBlockCounter
    typeTagMap.clear();          typeTagMap          ++= cp.typeTagMap
    typeTagCounter  = cp.typeTagCounter
    valNames.clear();            valNames            ++= cp.valNames

  /** Run `module` and record a checkpoint after every top-level section.
   *  Used by `ssc watch` on the first cycle so subsequent cycles can use
   *  [[runSectionsIncremental]] to skip unchanged sections.
   *
   *  Returns a vector of length `module.sections.length + 1`:
   *  - index 0 = post-init state (before any section runs)
   *  - index i+1 = state after running section i */
  def runWithCheckpoints(module: Module): Vector[InterpCheckpoint] =
    runInit(module)
    val cps = new mutable.ArrayBuffer[InterpCheckpoint](module.sections.length + 1)
    cps += takeCheckpoint()
    val contentSections = module.document.map(_.sections).getOrElse(Nil)
    module.sections.zipWithIndex.foreach { case (s, index) =>
      SectionRuntime.runSection(s, this, contentSections.lift(index))
      cps += takeCheckpoint()
    }
    autoCallMain()
    cps.toVector

  /** Re-evaluate from `firstChanged` onward, reusing state from `prevCheckpoints`
   *  for unchanged sections.  Used by `ssc watch` on cycles 2+.
   *
   *  `prevCheckpoints` must be the vector returned by the previous
   *  [[runWithCheckpoints]] or [[runSectionsIncremental]] call.
   *
   *  @param sections        module.sections (may have grown or shrunk)
   *  @param firstChanged    first section index whose content hash changed
   *  @param prevCheckpoints checkpoint vector from previous run
   *  @return new checkpoint vector, length = sections.length + 1
   */
  def runSectionsIncremental(
      sections:        List[Section],
      firstChanged:    Int,
      prevCheckpoints: Vector[InterpCheckpoint],
      contentSections: List[SectionContent] = Nil
  ): Vector[InterpCheckpoint] =
    // Find the last valid restore point we have from the previous run.
    // prevCheckpoints(k) = state before section k.
    val restoreIdx = firstChanged.min(prevCheckpoints.length - 1).max(0)
    restoreCheckpoint(prevCheckpoints(restoreIdx))
    // Carry forward unchanged checkpoints (indices 0..restoreIdx inclusive).
    val reused = prevCheckpoints.take(restoreIdx + 1)
    // Re-run changed sections, collecting fresh checkpoints.
    val newCps = sections.drop(restoreIdx).zipWithIndex.map { case (s, offset) =>
      val sectionIndex = restoreIdx + offset
      SectionRuntime.runSection(s, this, contentSections.lift(sectionIndex))
      takeCheckpoint()
    }
    autoCallMain()
    reused ++ newCps

  /** Register each `routes:` entry from front-matter as if the user had
   *  written `route(method, path) { req => handler(req) }` inline.  We
   *  register BEFORE evaluating sections (since the user's `serve(port)`
   *  call at the end of a section blocks forever) and bind a lazy
   *  wrapper that resolves the handler from `globals` at request time,
   *  once the section defs have run. */
  private def registerFrontmatterRoutes(module: Module): Unit =
    module.manifest.foreach { m =>
      m.routes.foreach { r =>
        val lazyHandler = Value.NativeFnV(
          s"frontmatter.route.${r.handler}",
          Computation.pureFn { args =>
            globals.get(r.handler) match
              case Some(h) => Computation.run(callValue(h, args, Map.empty))
              case None    =>
                throw InterpretError(
                  s"front-matter route ${r.method} ${r.path} references unknown handler '${r.handler}'"
                )
          }
        )
        routeRegistry.register(r.method, r.path, lazyHandler, this)
      }
    }

  /** Compile `remoteHandlers:` manifest entries into the interpreter's local
   *  registry.  The registered handler is lazy: the actual function is looked
   *  up in globals at call time, after sections have been evaluated.
   *
   *  If a handler declares `path`, also expose a POST JSON fallback route whose
   *  body is the current ValueSerializer JSON shape used by the actor wire.
   */
  private def registerFrontmatterRemoteHandlers(module: Module): Unit =
    module.manifest.foreach { m =>
      m.remoteHandlers.foreach { h =>
        val transports =
          if h.path.isDefined then Set("in-process", "http-json")
          else Set("in-process")
        val info = scalascript.backend.spi.RemoteHandlerInfo(
          name         = h.name,
          function     = h.function,
          path         = h.path,
          requestType  = h.requestType,
          responseType = h.responseType,
          transports   = transports
        )
        remoteHandlerRegistry.put(h.name, RemoteHandlerEntry(info))
        h.path.foreach { path =>
          val routeHandler = Value.NativeFnV(
            s"remote.handler.${h.name}",
            Computation.pureFn { args =>
              val req = args.headOption.getOrElse(Value.UnitV)
              val body = req match
                case Value.InstanceV("Request", fields) =>
                  fields.getOrElse("body", null) match { case Value.StringV(s) => s; case _ => "" }
                case _ => ""
              val payload =
                if body.trim.isEmpty then Value.UnitV
                else
                  try ValueSerializer.deserialize(body)
                  catch case e: Throwable =>
                    returnRemoteResponse(400, s"remote decode failed: ${e.getMessage}")
              invokeRemoteHandlerValue(h.name, payload) match
                case Right(value) =>
                  returnRemoteResponse(200, ValueSerializer.serialize(value), "application/scalascript-value+json")
                case Left(err) =>
                  returnRemoteResponse(remoteStatus(err), remoteErrorText(err))
            }
          )
          routeRegistry.register("POST", path, routeHandler, this)
        }
      }
    }

  private def returnRemoteResponse(
      status:      Int,
      body:        String,
      contentType: String = "text/plain; charset=utf-8"
  ): Value =
    Value.InstanceV("Response", new IMap.Map3(
      "status",  Value.intV(status),
      "headers", Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV(contentType))),
      "body",    Value.StringV(body)
    ))

  private def remoteStatus(err: scalascript.backend.spi.RemoteCallError): Int = err match
    case scalascript.backend.spi.RemoteCallError.HandlerNotFound(_) => 404
    case scalascript.backend.spi.RemoteCallError.Unauthorized       => 401
    case scalascript.backend.spi.RemoteCallError.Decode(_)          => 400
    case scalascript.backend.spi.RemoteCallError.Timeout(_, _)      => 504
    case scalascript.backend.spi.RemoteCallError.Unavailable(_)     => 503
    case _                                                          => 500

  private def remoteErrorText(err: scalascript.backend.spi.RemoteCallError): String = err match
    case scalascript.backend.spi.RemoteCallError.HandlerNotFound(name) =>
      s"remote handler not found: $name"
    case scalascript.backend.spi.RemoteCallError.Decode(message) =>
      s"remote decode failed: $message"
    case scalascript.backend.spi.RemoteCallError.RemoteFailed(code, message) =>
      s"remote handler failed ($code): $message"
    case other => other.toString

  private[interpreter] def remoteHandlerInfos: List[scalascript.backend.spi.RemoteHandlerInfo] =
    remoteHandlerRegistry.values().toArray.toList
      .collect { case e: RemoteHandlerEntry => e.info }
      .sortBy(_.name)

  private[interpreter] def invokeRemoteHandlerValue(
      name:    String,
      payload: Value
  ): Either[scalascript.backend.spi.RemoteCallError, Value] =
    Option(remoteHandlerRegistry.get(name)) match
      case None => Left(scalascript.backend.spi.RemoteCallError.HandlerNotFound(name))
      case Some(entry) =>
        globals.get(entry.info.function) match
          case None =>
            Left(scalascript.backend.spi.RemoteCallError.HandlerNotFound(name))
          case Some(handler) =>
            try Right(Computation.run(callValue1(handler, payload, Map.empty)))
            catch
              case e: InterpretError =>
                Left(scalascript.backend.spi.RemoteCallError.RemoteFailed("handler_failed", e.getMessage))
              case e: Throwable =>
                Left(scalascript.backend.spi.RemoteCallError.RemoteFailed("handler_failed", String.valueOf(e.getMessage)))

  // (mkHttpCtx removed — the Http effect runner moved to http-plugin; core-min §2d.)

  // ─── Health + cluster control routes — see ClusterRoutesRuntime.scala ──────

  private[interpreter] def registerHealthDefaults(): Unit =
    ClusterRoutesRuntime.registerHealthDefaults(this)

  private[interpreter] def registerOpenApiDefaults(): Unit =
    OpenApiRuntime.registerOpenApiDefaults(this)

  // ─── Built-ins — see BuiltinsRuntime.scala ────────────────────────────

  def invoke(fn: Value, args: List[Value]): Value =
    Computation.run(callValue(fn, args, Map.empty))

  /** Invoke a callback, driving any `Async` effects it performs to completion
   *  via the sequential async runtime, and unwrapping a resulting `Future`
   *  instance to its underlying value.  Used by native code (e.g. GraphQL
   *  resolvers) that may run user callbacks returning `Future[A]` or
   *  `A ! Async`.  A plain (non-async) callback behaves exactly like
   *  [[invoke]]. */
  def invokeAsync(fn: Value, args: List[Value]): Value =
    val driven = AsyncRuntime.asyncInterp(callValue(fn, args, Map.empty), this)
    unwrapFuture(Computation.run(driven))

  /** Unwrap the sequential-driver `Future` representation
   *  (`InstanceV("Future", { value })`) to its inner value; pass other values
   *  through unchanged. */
  private def unwrapFuture(v: Value): Value = v match
    case Value.InstanceV("Future", fields) => fields.getOrElse("value", Value.UnitV)
    case other                             => other

  /** Install a native function under `name` into the global table.
   *  Used by `InterpreterBackend` to surface `Backend.intrinsics`
   *  entries (`NativeImpl` variant) as callable globals before
   *  user code runs.  Symmetric with `initBuiltins`'s `nativeP`,
   *  but public so external SPI consumers can register their own
   *  intrinsics without touching the interpreter source. */
  def registerNative(name: String, fn: List[Value] => Value): Unit =
    globals(name) = Value.NativeFnV(name, Computation.pureFn(fn))

  /** Install every `NativeImpl` entry from a `Backend.intrinsics` map
   *  as a callable global.  Bridges `Value` ↔ `Any` at the boundary
   *  and provides a `NativeContext` whose `out` points at this
   *  interpreter's `out` (so renderCommand's null-PrintStream
   *  wrapping is respected).  Other intrinsic variants (`InlineCode`
   *  / `RuntimeCall` / `HostCallback`) are no-ops here — they target
   *  compiled or out-of-process backends. */
  def installNativeIntrinsics(
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl]
  ): Unit =
    installNativeIntrinsicEntries(intrinsics)

  private def installNativeIntrinsicEntries(
      intrinsics: Iterable[(scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl)]
  ): Unit =
    val ctx = new scalascript.backend.spi.NativeContext:
      def out = Interpreter.this.out
      def err = System.err
      override def featureGet(key: String): Option[Any] = Interpreter.this.nativeFeatureGet(key)
      override def featureSet(key: String, value: Any): Unit = Interpreter.this.nativeFeatureSet(key, value)
      override def featureRemove(key: String): Option[Any] = Interpreter.this.nativeFeatureRemove(key)
      override def featureLocalGet(key: String): Option[Any] = Interpreter.this.nativeFeatureLocalGet(key)
      override def featureLocalSet(key: String, value: Any): Unit = Interpreter.this.nativeFeatureLocalSet(key, value)
      override def featureLocalRemove(key: String): Option[Any] = Interpreter.this.nativeFeatureLocalRemove(key)
      override def headless = Interpreter.this.headless
      override def openApiDryRun: Boolean = Interpreter.this.openApiDryRun
      override def registerRoute(method: String, path: String, handler: Any): Unit =
        Interpreter.this.routeRegistry.register(
          method, path, handler.asInstanceOf[Value], Interpreter.this,
          source   = Interpreter.this.currentLoadingFile,
          style    = if Interpreter.this.currentLoadingFile.isDefined then "load" else "route"
        )
      override def registerRouteWithOpenApi(
          method:   String,
          path:     String,
          handler:  Any,
          metadata: scalascript.backend.spi.OpenApiGenerator.OpenApiMetadata
      ): Unit =
        Interpreter.this.routeRegistry.register(
          method, path, handler.asInstanceOf[Value], Interpreter.this,
          source   = Interpreter.this.currentLoadingFile,
          style    = if Interpreter.this.currentLoadingFile.isDefined then "load" else "route",
          metadata = metadata
        )
      override def registerHealthDefaults(): Unit = Interpreter.this.registerHealthDefaults()
      override def registerOpenApiDefaults(): Unit = Interpreter.this.registerOpenApiDefaults()
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        Interpreter.this.invoke(fn.asInstanceOf[Value], args.map(wrapAnyAsValue))
      override def invokeCallbackAsync(fn: Any, args: List[Any]): Any =
        Interpreter.this.invokeAsync(fn.asInstanceOf[Value], args.map(wrapAnyAsValue))
      override def resolveGlobal(name: String): Option[Any] =
        Interpreter.this.globals.get(name)
      override def httpBaseUrl: String    = Interpreter.this.httpBaseUrlState
      override def httpTimeoutMs: Long    = Interpreter.this.httpTimeoutMsState
      override def httpMaxRetries: Int    = Interpreter.this.httpMaxRetriesState
      override def httpRetryDelayMs: Long = Interpreter.this.httpRetryDelayMsState
      override def setHttpTimeout(ms: Long): Unit =
        featureLocalSet(NativeFeatureKeys.HttpTimeoutMs, ms)
      override def setHttpRetry(maxAttempts: Int, delayMs: Long): Unit =
        featureLocalSet(NativeFeatureKeys.HttpMaxRetries, maxAttempts)
        featureLocalSet(NativeFeatureKeys.HttpRetryDelayMs, delayMs)
      override def startTlsServer(port: Int, dir: String, cert: String, key: String): Unit =
        if !Interpreter.this.headless then
          InterpreterServerSupport.current.startServer(
            Interpreter.this, port, dir, Interpreter.this.out, cert, key, async = false)
      override def startServer(port: Int, dir: String): Unit =
        if !Interpreter.this.headless then
          InterpreterServerSupport.current.startServer(
            Interpreter.this, port, dir, Interpreter.this.out, "", "", async = false)
      override def startServerAsync(port: Int, dir: String): Unit =
        if !Interpreter.this.headless then
          InterpreterServerSupport.current.startServer(
            Interpreter.this, port, dir, Interpreter.this.out, "", "", async = true)
      override def startTlsServerAsync(port: Int, dir: String, cert: String, key: String): Unit =
        if !Interpreter.this.headless then
          InterpreterServerSupport.current.startServer(
            Interpreter.this, port, dir, Interpreter.this.out, cert, key, async = true)
      override def stopServer(): Unit =
        if !Interpreter.this.headless then InterpreterServerSupport.current.stopServer()
      override def setMaxWsConnections(n: Int): Unit =
        InterpreterServerSupport.current.setMaxWsConnections(n)
      override def registerWsRoute(path: String, origins: List[String], protocols: List[String],
                                    maxConn: Int, maxRate: Int, handler: Any): Unit =
        Interpreter.this.wsRoutes.register(
          path, handler.asInstanceOf[Value], Interpreter.this, origins, protocols, maxConn, maxRate)
      override def registerWsAuthRoute(path: String, authFn: Any, handler: Any): Unit =
        Interpreter.this.wsRoutes.register(
          path, handler.asInstanceOf[Value], Interpreter.this, auth = Some(authFn.asInstanceOf[Value]))
      override def wsConnectSync(url: String, headers: Map[String, String],
                                  protocols: List[String], handler: Any): Unit =
        InterpreterServerSupport.current.wsConnectSync(
          Interpreter.this, url, headers, protocols, handler.asInstanceOf[Value], Interpreter.this.out)
      override def registerMiddleware(fn: Any): Unit =
        Interpreter.this.routeRegistry.addMiddleware(fn.asInstanceOf[Value], Interpreter.this)
      override def configureCors(origins: List[String], methods: List[String],
                                  allowedHeaders: List[String]): Unit =
        InterpreterServerSupport.current.configureCors(origins, methods, allowedHeaders)
      override def enableGzip(): Unit = InterpreterServerSupport.current.enableGzip()
      override def setMaxBodySize(bytes: Long): Unit = InterpreterServerSupport.current.setMaxBodySize(bytes)
      override def setSpoolThreshold(bytes: Long): Unit = InterpreterServerSupport.current.setSpoolThreshold(bytes)
      override def setUploadDir(path: String): Unit = InterpreterServerSupport.current.setUploadDir(path)
      override def validationRecord(name: String, msg: String, default: Any): Any =
        validationStack.headOption match
          case Some(buf) => buf.put(name, msg); default
          case None      => throw new scalascript.server.RestValidationError(msg)
      override def dbConnect(dbName: String): java.sql.Connection =
        Interpreter.this.sqlRegistry.connect(dbName)
      override def remoteHandlers: List[scalascript.backend.spi.RemoteHandlerInfo] =
        Interpreter.this.remoteHandlerInfos
      override def invokeRemoteHandler(
          name:    String,
          payload: Any
      ): Either[scalascript.backend.spi.RemoteCallError, Any] =
        payload match
          case v: Value => Interpreter.this.invokeRemoteHandlerValue(name, v)
          case other    => Interpreter.this.invokeRemoteHandlerValue(name, wrapAnyAsValue(other))
      override def storageFieldName(typeName: String, fieldName: String): String =
        Interpreter.this.typeFieldSchemas
          .get(typeName)
          .flatMap(_.find(_.fieldName == fieldName))
          .map(_.storageName)
          .getOrElse(fieldName)
      override def baseDirPath: Option[String] =
        Interpreter.this.baseDir.map(_.toString)
      override def evalFileGetResult(absPath: String): Any =
        import scalascript.parser.Parser
        val path     = os.Path(absPath)
        val childDir = path / os.up
        val child    = Interpreter(Interpreter.this.out, Some(childDir), lockPath = Interpreter.this.lockPath)
        child.run(Parser.parse(os.read(path)))
        child.lastExprResult
      override def evalFileGetNamedResult(absPath: String, fnName: String): Any =
        import scalascript.parser.Parser
        val path     = os.Path(absPath)
        val childDir = path / os.up
        val child    = Interpreter(Interpreter.this.out, Some(childDir), lockPath = Interpreter.this.lockPath)
        child.run(Parser.parse(os.read(path)))
        child.globalsView.getOrElse(fnName,
          throw InterpretError(s"function '$fnName' not found in $absPath"))
      override def registerMountedRoute(
          method:   String,
          path:     String,
          handler:  Any,
          source:   Option[String],
          mountCtx: Map[String, Any]
      ): Unit =
        val ctx: Map[String, Value] = mountCtx.map {
          case (k, v: Value) => k -> v
          case (k, s: String) => k -> Value.StringV(s)
          case (k, n: Long)   => k -> Value.intV(n)
          case (k, d: Double) => k -> Value.doubleV(d)
          case (k, b: Boolean) => k -> Value.boolV(b)
          case (k, _)          => k -> Value.UnitV
        }
        Interpreter.this.routeRegistry.register(
          method, path, handler.asInstanceOf[Value], Interpreter.this,
          source = source, mountCtx = ctx)
    val nativeEntries =
      intrinsics.iterator.collect {
        case (qn, scalascript.backend.spi.NativeImpl(eval)) => qn -> eval
      }.toList
    nativeEntries.groupBy(_._1.value).foreach {
      // busi-p3 — user-wins: if a user top-level `def` already occupies this
      // name (a FunV that is not itself a previously-installed plugin native),
      // keep the user binding and warn instead of clobbering it. Covers the
      // lazy-load-after-user-def ordering; the common ordering is handled in
      // StatRuntime when the user `def` overwrites an installed native.
      case (name, _) if userDefShadowsIntrinsic(name) =>
        warnIntrinsicShadow(name)
      case (name, List((_, eval))) =>
        val fallback = nativeFallbackForPlugin(name)
        pluginNativeNames += name
        globals(name) = Value.NativeFnV(name, args =>
          dispatchNativeOverload(name, List(eval), ctx, args, fallback)
        )
      case (name, overloads) =>
        val fallback = nativeFallbackForPlugin(name)
        pluginNativeNames += name
        globals(name) = Value.NativeFnV(name, args =>
          dispatchNativeOverload(name, overloads.map(_._2), ctx, args, fallback)
        )
    }

  private def nativeFallbackForPlugin(name: String): Option[Value.NativeFnV] =
    if pluginNativeNames.contains(name) then None
    else globals.get(name).collect { case native: Value.NativeFnV => native }

  /** True when `globals(name)` currently holds a user-authored function (a
   *  `FunV`) that is not a plugin native — i.e. installing the intrinsic here
   *  would clobber a user definition. */
  private def userDefShadowsIntrinsic(name: String): Boolean =
    !pluginNativeNames.contains(name) && (globals.get(name) match
      case Some(_: Value.FunV) => true
      case _                   => false)

  private def dispatchNativeOverload(
      name:      String,
      overloads: List[(scalascript.backend.spi.NativeContext, List[Any]) => Any],
      ctx:       scalascript.backend.spi.NativeContext,
      args:      List[Value],
      fallback:  Option[Value.NativeFnV]
  ): Computation =
    val raw = args.map(unwrapValueAsAny)
    val usageErrors = scala.collection.mutable.ListBuffer.empty[InterpretError]
    var matched: Option[Computation] = None
    val it = overloads.iterator
    while matched.isEmpty && it.hasNext do
      val eval = it.next()
      try matched = Some(Computation.Pure(wrapAnyAsValue(eval(ctx, raw))))
      catch
        case e: InterpretError if isNativeUsageError(name, e) =>
          usageErrors += e
    matched.getOrElse:
      fallback match
        case Some(native) => native.f(args)
        case None =>
          throw usageErrors.headOption.getOrElse(InterpretError(s"$name: no matching native overload"))

  private def isNativeUsageError(name: String, e: InterpretError): Boolean =
    val msg = Option(e.getMessage).getOrElse("")
    msg == name || msg.startsWith(name + "(") || msg.startsWith(name + ": expected ")

  private[interpreter] def unwrapValueAsAny(v: Value): Any = v match
    case Value.IntV(n)    => n
    case Value.DoubleV(d) => d
    case Value.StringV(s) => s
    case Value.BoolV(b)   => b
    case Value.UnitV      => ()
    case other            => other  // pass complex Values through unchanged

  // ── Value ↔ SpiValue (typed block-form / effect-handler boundary) ─────
  // The host-neutral, closed value the block-form/effect-handler SPI speaks
  // (the spi module must not depend on `Value`). polyglot-libraries §2d.
  import scalascript.backend.spi.SpiValue
  // value-unification (scalars-only): the scalar leaves are the *shared* `DataValue` cases, so a scalar
  // Value already IS an SpiValue (both are `… | DataValue` unions) — convert it by identity, no rewrap.
  // Only the containers (which hold arbitrary Values) and the Opaque fallback need real conversion.
  private[interpreter] def valueToSpi(v: Value): SpiValue = v match
    case d: DataValue      => d  // scalar leaf — shared, identity
    case Value.ListV(xs)   => SpiValue.ListV(xs.map(valueToSpi))
    case Value.VectorV(xs) => SpiValue.VectorV(xs.toList.map(valueToSpi))
    case Value.TupleV(xs)  => SpiValue.TupleV(xs.map(valueToSpi))
    case Value.MapV(m)     => SpiValue.MapV(m.toList.map { (k, vv) => valueToSpi(k) -> valueToSpi(vv) })
    case Value.OptionV(o)  => SpiValue.OptV(Option(o).map(valueToSpi))
    case other             => SpiValue.Opaque(other)  // closure / case-class / etc. — round-trip unchanged

  private[interpreter] def spiToValue(s: SpiValue): Value = s match
    case d: DataValue         => d  // scalar leaf — shared, identity
    case SpiValue.ListV(xs)   => Value.ListV(xs.map(spiToValue))
    case SpiValue.VectorV(xs) => Value.VectorV(xs.map(spiToValue).toVector)
    case SpiValue.TupleV(xs)  => Value.TupleV(xs.map(spiToValue))
    case SpiValue.OptV(o)     => o.fold(Value.NoneV)(x => Value.someV(spiToValue(x)))
    case SpiValue.MapV(es)    => Value.MapV(es.map { (k, vv) => spiToValue(k) -> spiToValue(vv) }.toMap)
    case SpiValue.Opaque(h)   => h.asInstanceOf[Value]

  private[interpreter] def wrapAnyAsValue(a: Any): Value = a match
    case n: Long    => Value.intV(n)
    case i: Int     => Value.intV(i.toLong)
    case d: Double  => Value.doubleV(d)
    case s: String  => Value.StringV(s)
    case b: Boolean => Value.boolV(b)
    case ()         => Value.UnitV
    case v: Value   => v
    // Allow plugins to return native Scala List/Map without importing Value.
    case lst: scala.collection.immutable.List[?] =>
      Value.ListV(lst.map(wrapAnyAsValue))
    case map: scala.collection.immutable.Map[?, ?] =>
      Value.MapV(map.map { case (k, v) => wrapAnyAsValue(k) -> wrapAnyAsValue(v) })
    case other      => Value.StringV(other.toString)

  /** HTML-escape a string for safe interpolation in an html block. */
  private[interpreter] def htmlEscape(s: String): String =
    val sb = StringBuilder()
    s.foreach {
      case '&'  => sb ++= "&amp;"
      case '<'  => sb ++= "&lt;"
      case '>'  => sb ++= "&gt;"
      case '"'  => sb ++= "&quot;"
      case '\'' => sb ++= "&#39;"
      case c    => sb += c
    }
    sb.toString

  /** Escape `rendered` unless the underlying value is a `raw(...)` marker,
   *  in which case the marker's body is already trusted HTML. */
  private[interpreter] def htmlEscapeUnlessRaw(v: Value, rendered: String): String = v match
    case Value.InstanceV("_Raw", _) => rendered
    case _                          => htmlEscape(rendered)

  def exportedGlobals: Map[String, Value] = globals.toMap
  // The declared `exports:` names (empty ⇒ none declared ⇒ permissive). Gates explicit
  // `[x](M)` import resolution in runImport; `exportedGlobals` (full) still feeds childCtx.
  def exportedNames: Set[String]          = moduleExports
  def exportedPkg: List[String]           = modulePkg

  /** Inject a named value into the global scope before (or after) `run`.
   *  Used by external runners (e.g. `ssc test`) to seed builtins that the
   *  module can call freely, without subclassing the interpreter.
   *  Injecting before `run()` is safe: `initBuiltins()` (called inside `run`)
   *  only adds standard entries and never clears the map, so injected globals
   *  with non-standard names survive untouched. */
  def injectGlobal(name: String, value: Value): Unit = globals(name) = value
  /** Extension methods registered by this interpreter, exposed so that
   *  parents can re-register them when importing a child module — the
   *  JS and JVM backends inline imports wholesale and pick these up
   *  for free, but the interpreter only copies the values named in
   *  the import binding list and would otherwise drop extensions. */
  def exportedExtensions: Map[(String, String), Value.FunV] =
    extensions.iterator.flatMap { case (t, mm) => mm.iterator.map { case (m, f) => (t, m) -> f } }.toMap
  def exportedParentTypes:   Map[String, String]               = parentTypes.toMap
  /** Concrete class/enum/trait methods, so an instance of an *imported* type
   *  dispatches inherited methods identically to same-module (busi seq-121 x-mod). */
  def exportedTypeMethods:   Map[String, Map[String, Value.FunV]] = typeMethods.toMap
  def exportedTypeFieldOrder: Map[String, List[String]]        = typeFieldOrder.toMap
  def exportedTypeFieldDefaults: Map[String, (List[Option[scala.meta.Term]], Env)] = typeFieldDefaults.toMap
  def exportedTypeFieldTypes: Map[String, List[String]]        = typeFieldTypes.toMap
  def exportedTypeFieldSchemas: Map[String, List[TypeFieldSchema]] = typeFieldSchemas.toMap
  def exportedRejectUnknownTypes: Set[String]                  = rejectUnknownTypes.toSet

  // Deep-merge overlay into base so multiple code blocks sharing the same
  // package prefix (e.g. `object std { object lib { ... } }` appearing in
  // separate fenced blocks of the same .ssc file) accumulate rather than
  // overwrite each other.
  private[interpreter] def mergeDeep(base: Value.InstanceV, overlay: Value.InstanceV): Value.InstanceV =
    val merged = overlay.fields.foldLeft(base.fields) { case (acc, (k, v)) =>
      (acc.get(k), v) match
        case (Some(b: Value.InstanceV), o: Value.InstanceV) => acc.updated(k, mergeDeep(b, o))
        case _                                               => acc.updated(k, v)
    }
    Value.InstanceV(base.typeName, merged)

  // ─── Section / block execution — see SectionRuntime.scala ─────────────

  // ─── Statement execution ─────────────────────────────────────────

  // ─── Statement execution — see StatRuntime.scala ──────────────────────

  private[interpreter] def execStat(stat: Stat, env: mutable.Map[String, Value], printResult: Boolean = false): Unit =
    StatRuntime.execStat(stat, env, printResult, this)

  // ─── Expression evaluation ───────────────────────────────────────

  // ─── Expression evaluator — see EvalRuntime.scala ────────────────────────

  private[interpreter] def eval(term: Term, env: Env): Computation =
    EvalRuntime.eval(term, env, this)

  private[interpreter] def threadValues(comps: List[Computation])(k: List[Value] => Computation): Computation =
    EvalRuntime.threadValues(comps)(k)

  private[interpreter] def callValue(fn: Value, args: List[Value], env: Env): Computation =
    CallRuntime.callValue(fn, args, env, this)

  /** Zero-arg fast path — avoids Nil/env allocation for 0-param FunV calls. */
  private[interpreter] def callValue0(fn: Value, env: Env): Computation =
    CallRuntime.callValue0(fn, env, this)

  /** Single-arg fast path — avoids List(item) allocation in map/filter/forEach hot loops. */
  private[interpreter] def callValue1(fn: Value, arg: Value, env: Env): Computation =
    CallRuntime.callValue1(fn, arg, env, this)

  private[interpreter] def callValue2(fn: Value, a: Value, b: Value, env: Env): Computation =
    CallRuntime.callValue2(fn, a, b, env, this)

  private[interpreter] inline def callEntry(fn: Value, k: Value, v: Value, env: Env): Computation =
    CallRuntime.callEntry(fn, k, v, env, this)

  private[interpreter] inline def callValuePrepend(fn: Value, recv: Value, args: List[Value], env: Env): Computation =
    CallRuntime.callValuePrepend(fn, recv, args, env, this)

  // ─── Call helpers — see CallRuntime.scala ────────────────────────────────

  private[interpreter] def callFun(f: Value.FunV, args: List[Value]): Computation =
    CallRuntime.callFun(f, args, this)

  /** Dispatch a typeMethods class method without copying the FunV or merging
   *  the closure Map.  Instead, the instance `fields` are layered over `fn.closure`
   *  using FrameMap.fromMap (one FrameMapN allocation, no HashMap.++) and the
   *  original `fn` is used for tcoInfoFor so the body-keyed cache hits on
   *  subsequent calls to the same method. */
  private[interpreter] def callTypeMethod(
    fn: Value.FunV, fields: Map[String, Value], args: List[Value]
  ): Computation =
    CallRuntime.callTypeMethod(fn, fields, args, this)

  private[interpreter] def callTypeMethod1(
    fn: Value.FunV, fields: Map[String, Value], arg: Value
  ): Computation =
    CallRuntime.callTypeMethod1(fn, fields, arg, this)

  // ─── Given / using helpers — see CallRuntime.scala ───────────────────────

  private[interpreter] def typeToString(t: scala.meta.Type): String =
    val cached = typeStringCache.get(t)
    if cached != null then cached
    else
      val s = CallRuntime.typeToString(t)
      typeStringCache.put(t, s)
      s

  private[interpreter] def isThrowsType(t: scala.meta.Type): Boolean =
    CallRuntime.isThrowsType(t)

  // ─── Given / typeclass resolution — see GivenRuntime.scala ──────────────

  private[interpreter] def applyDefaults(
    params:   List[String],
    defaults: List[Option[Term]],
    args:     List[Value],
    baseEnv:  Env
  ): List[Value] =
    CallRuntime.applyDefaults(params, defaults, args, baseEnv, this)

  // ─── TCO trampoline — see TcoRuntime.scala ──────────────────────

  // ─── Algebraic effects (Free Monad interpreter) ──────────────────

  // ─── Effect handle + restartable — see EffectsRuntime.scala ─────────────

  // ─── Reactive primitives: implementation helpers ───────────────────

  // ── v1.x Signals — see SignalRuntime.scala ────────────────────────────

  // ── Async driver — see AsyncRuntime.scala ────────────────────────────

  // ── v1.6 Actors — see ActorInterp.scala ─────────────────────────────────


  // ── Storage handler — see StorageRuntime.scala ──────────────────────

    // ─── Infix operators ──────────────────────────────────────────────

  private[interpreter] def infix(lhs: Value, op: String, args: List[Value], env: Env): Computation =
    DispatchRuntime.infix(lhs, op, args, env, this)

  private[interpreter] def infix2(lhs: Value, op: String, rhs: Value, env: Env): Computation =
    DispatchRuntime.infix2(lhs, op, rhs, env, this)

  // ─── Dispatch — see DispatchRuntime.scala ────────────────────────────────

  // ─── Structural helpers for `derives` — see DerivesRuntime.scala ────────

  // ─── Pattern matching + for-comprehensions — see PatternRuntime.scala ───

  private[interpreter] def autoOutput(v: Value): Unit = v match
    case Value.UnitV => ()
    case _           => out.println(Value.show(v))

  private[interpreter] def stripIndent(s: String): String =
    val lines = s.split('\n').toList
    val body  = lines.dropWhile(_.isBlank).reverse.dropWhile(_.isBlank).reverse
    if body.isEmpty then ""
    else
      val minIndent = body.filter(_.exists(_ != ' ')).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
      body.map(l => if l.isBlank then "" else l.drop(minIndent)).mkString("\n")

  def runSnippet(code: String): Unit =
    import scalascript.parser.Parser
    val src    = s"# Snippet\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    SectionRuntime.runModuleSections(module, this)

  /** Run all sections of a pre-parsed [[scalascript.ast.Module]] in this
   *  interpreter's current context (globals, plugins).  Unlike [[run]], this
   *  does **not** reinitialise builtins — it continues the existing REPL
   *  session.  Used by `:load file.ssc` to execute a `.ssc` file that was
   *  already parsed with [[scalascript.parser.Parser.parse]]. */
  def runSections(module: scalascript.ast.Module): Unit =
    SectionRuntime.runModuleSections(module, this)

  /** Evaluate a single Scala 3 expression in the current globals + [[extraEnv]].
   *  Debug hooks are suppressed during evaluation so the REPL `:print` command
   *  does not trigger spurious breakpoints. */
  def evalExpr(exprSrc: String, extraEnv: Map[String, Value] = Map.empty): Value =
    val savedHooks = debugHooks
    debugHooks = None
    try
      val parsed = scala.meta.dialects.Scala3(exprSrc).parse[scala.meta.Term].get
      Computation.run(eval(parsed, extraEnv ++ globals.toMap))
    finally
      debugHooks = savedHooks

  /** Evaluate a pre-parsed term against the current globals. No parsing overhead.
   *  Uses `Map.empty` as the local env so name lookups fall through to `interp.globals`
   *  directly — avoids the `globals.toMap` copy on every call.
   *  Intended for microbenchmarks that pre-parse a call term and call it in a tight loop. */
  def evalTerm(term: scala.meta.Term): Value =
    Computation.run(eval(term, Map.empty))

  // ── v1.4 effect handlers — see EffectHandlers.scala ────────────────────
  //
  // httpRun / retryRun / cacheRun are in EffectHandlers.scala. Logger, Random,
  // Clock, Env and State were extracted to ServiceLoader plugins (logger-/random-/
  // clock-/env-/state-effect-plugin) — core-minimization, §2d.

object Interpreter:
  def run(
      module:   Module,
      out:      java.io.PrintStream = System.out,
      baseDir:  Option[os.Path]     = None,
      lockPath: Option[os.Path]     = None
  ): Unit =
    Interpreter(out, baseDir, lockPath = lockPath).run(module)
