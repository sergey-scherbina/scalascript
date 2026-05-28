package scalascript.interpreter

import scalascript.ast.*
import scala.collection.mutable
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
  noNonTailSelf: Boolean
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
  sqlBlockCounter:     Int
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
    private[interpreter] val lockPath: Option[os.Path]      = None) extends ActorInterp:
  /** Per-interpreter WebSocket route table.  Owning this here means each
   *  `Interpreter` instance has an isolated WS route set — no global lock
   *  or `WsTestLock` synchronization required in tests. */
  val wsRoutes: scalascript.server.WsRoutes = new scalascript.server.WsRoutes()
  val routeRegistry: scalascript.server.RouteRegistry = scalascript.server.Routes
  private[interpreter] val globals      = mutable.Map.empty[String, Value]
  private[interpreter] val extensions   = mutable.Map.empty[(String, String), Value.FunV]
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
  // Field type names for each case class, parallel to typeFieldOrder.
  // Populated in StatRuntime when a Defn.Class is processed; used by
  // TypedHandlerWrapper.deserializeCaseClass to coerce path/query/body values.
  private[interpreter] val typeFieldTypes = mutable.Map.empty[String, List[String]]
  private[interpreter] val typeFieldSchemas = mutable.Map.empty[String, List[TypeFieldSchema]]
  private[interpreter] val rejectUnknownTypes = mutable.Set.empty[String]
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
  // Effect object names detected as multi-shot by EffectAnalysis (populated in runInit).
  private[interpreter] var multiShotEffects: Set[String] = Set.empty
  private[interpreter] var sqlBlockRunner: Option[scalascript.backend.spi.SqlBlockRunner] = None

  /** Load plugin intrinsics on first use.  Called from EvalRuntime when a
   *  Term.Name lookup misses both env and globals — at that point we may have
   *  a plugin-provided name that hasn't been registered yet.  Idempotent. */
  private[interpreter] def ensurePluginsLoaded(): Unit =
    if _pluginsLoaded then return
    _pluginsLoaded = true
    import scalascript.backend.spi.NativeImpl
    val plugins = scalascript.compiler.plugin.BackendRegistry.inProcess.toList
    val pluginImpls: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] =
      plugins.iterator
        .flatMap(_.intrinsics)
        .collect { case entry @ (_, _: NativeImpl) => entry }
        .toMap
    installNativeIntrinsics(pluginImpls)
    installSqlBlockRunners(plugins)
    BuiltinsRuntime.setupPluginCompanions(this)

  /** Install exactly the supplied in-process interpreter plugins.
   *
   *  Test harnesses and embedded hosts use this to exercise one plugin (or a
   *  small explicit set) without falling back to ServiceLoader discovery of
   *  every plugin on the classpath. */
  def installPlugins(plugins: Iterable[scalascript.backend.spi.Backend]): Unit =
    import scalascript.backend.spi.NativeImpl
    val pluginImpls: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] =
      plugins.iterator
        .flatMap(_.intrinsics)
        .collect { case entry @ (_, _: NativeImpl) => entry }
        .toMap
    installNativeIntrinsics(pluginImpls)
    installSqlBlockRunners(plugins)
    BuiltinsRuntime.setupPluginCompanions(this)
    _pluginsLoaded = true

  private def installSqlBlockRunners(plugins: Iterable[scalascript.backend.spi.Backend]): Unit =
    plugins.iterator.flatMap(_.sqlBlockRunner).toList match
      case Nil => ()
      case runners =>
        sqlBlockRunner = Some(runners.last)

  // ThreadLocal so concurrent generator virtual threads each get their own counter.
  private[interpreter] val _phIdxTL: ThreadLocal[Int] = ThreadLocal.withInitial(() => 0)
  private inline def placeholderIdx: Int          = _phIdxTL.get()
  private inline def placeholderIdx_=(v: Int): Unit = _phIdxTL.set(v)
  // Tracks the last known source position for error messages (0-based line, 0-based column).
  private[interpreter] var currentSpan: Option[(Int, Int)] = None
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

  // Phase 5 DAP: call stack — (frameName, sourceFile, absDocLine).
  private[interpreter] val callStack = scala.collection.mutable.ArrayBuffer.empty[(String, String, Int)]
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

  /** Per-FunV cache of the TCO classification — see TcoRuntime.tcoInfoFor.
   *  Keyed by FunV identity. */
  private[interpreter] val tcoCache: java.util.IdentityHashMap[Value.FunV, TcoInfo] =
    java.util.IdentityHashMap()

  /** Intern table from `Lit` AST nodes to the `Computation` they evaluate
   *  to. The parsed AST is reused across all evaluations, so for hot
   *  loop literals (`0`, `1`, `2`, …) this saves a fresh `Pure(IntV(...))`
   *  allocation on every visit. */
  private[interpreter] val litCache: java.util.IdentityHashMap[Lit, Computation] =
    java.util.IdentityHashMap()


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
  private[interpreter] val coHandles    = new java.util.concurrent.ConcurrentHashMap[Long, CoHandle]()
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

  private val nativeFeatureState = new java.util.concurrent.ConcurrentHashMap[String, Any]()
  private val nativeFeatureLocalState = new java.util.concurrent.ConcurrentHashMap[String, ThreadLocal[Any]]()

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

  // ── v1.4 Cache effect — process-local memoization store + bypass flag ──
  private[interpreter] val _cacheStore  = new java.util.concurrent.ConcurrentHashMap[String, (Long, Value)]()
  private[interpreter] val _cacheBypass = ThreadLocal.withInitial[Boolean](() => false)

  // ── Async parallel driver — future table (see AsyncRuntime.scala) ─────
  private[interpreter] val parallelFutures =
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

  /** Cache of `closure.updated(name, f)` per FunV — the self-ref binding
   *  is identical on every invocation of the same closure, so we save
   *  one HashMap.updated allocation per call. */
  private[interpreter] val closureWithSelfCache: java.util.IdentityHashMap[Value.FunV, Env] =
    java.util.IdentityHashMap()

  private[interpreter] def closureWithSelfFor(f: Value.FunV): Env =
    if f.name.isEmpty then f.closure
    else
      val cached = closureWithSelfCache.get(f)
      if cached != null then cached
      else
        val w = f.closure.updated(f.name, f)
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
    if !p.isEmpty then currentSpan = Some((p.startLine - lineOffset, p.startColumn))

  // ─── Public API ──────────────────────────────────────────────────

  /** Module-level `dependencies:` from the front-matter, captured at the
   *  top of `run` so any `[Card](dep://card.ssc)` import in this module
   *  can rewrite its scheme through `ImportResolver`. */
  private[interpreter] var moduleDeps: Map[String, String] = Map.empty
  private[interpreter] var modulePkg: List[String] = Nil
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
  private val remoteHandlerRegistry =
    new java.util.concurrent.ConcurrentHashMap[String, RemoteHandlerEntry]()

  def run(module: Module): Unit =
    runInit(module)
    module.sections.foreach(SectionRuntime.runSection(_, this))
    autoCallMain()

  /** Builtins + manifest/config setup without running sections.
   *  Extracted so [[runWithCheckpoints]] can share it without duplicating code. */
  private def runInit(module: Module): Unit =
    BuiltinsRuntime.initBuiltins(this)
    currentCodeIdentity = computeCodeIdentity(module)
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    modulePkg  = module.manifest.flatMap(_.pkg).getOrElse(Nil)
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
        case List(Value.StringV(path)) => Value.OptionV(cfgAccessor.getString(path).map(Value.StringV(_)))
        case _ => throw InterpretError("config.getString(path: String)")
      }),
      "getInt" -> Value.NativeFnV("config.getInt", Computation.pureFn {
        case List(Value.StringV(path)) => Value.OptionV(cfgAccessor.getInt(path).map(n => Value.intV(n.toLong)))
        case _ => throw InterpretError("config.getInt(path: String)")
      }),
      "getDouble" -> Value.NativeFnV("config.getDouble", Computation.pureFn {
        case List(Value.StringV(path)) => Value.OptionV(cfgAccessor.getDouble(path).map(Value.doubleV(_)))
        case _ => throw InterpretError("config.getDouble(path: String)")
      }),
      "getBool" -> Value.NativeFnV("config.getBool", Computation.pureFn {
        case List(Value.StringV(path)) => Value.OptionV(cfgAccessor.getBool(path).map(Value.boolV(_)))
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
    }
    // Populate multiShotEffects for one-shot violation checks in evalHandle.
    val allTrees: List[scala.meta.Tree] = module.sections.flatMap { s =>
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten
    }
    multiShotEffects = scalascript.transform.EffectAnalysis.analyze(allTrees).multiShotEffects
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
      "module"    -> module.manifest.flatMap(_.name).map(name => Value.OptionV(Some(Value.StringV(name)))).getOrElse(Value.NoneV)
    ))

  private def autoCallMain(): Unit =
    if !mainCalled then
      globals.get("main").foreach {
        case f: Value.FunV if f.params.isEmpty => Computation.run(callFun(f, Nil)); mainCalled = true
        case _ => ()
      }

  /** Snapshot the section-populated mutable state for incremental re-eval.
   *  Called by [[runWithCheckpoints]] after each section. */
  private[interpreter] def takeCheckpoint(): InterpCheckpoint =
    InterpCheckpoint(
      globals             = globals.toMap,
      extensions          = extensions.toMap,
      parentTypes         = parentTypes.toMap,
      typeMethods         = typeMethods.toMap,
      typeFieldOrder      = typeFieldOrder.toMap,
      typeFieldTypes      = typeFieldTypes.toMap,
      typeFieldSchemas    = typeFieldSchemas.toMap,
      rejectUnknownTypes  = rejectUnknownTypes.toSet,
      givenFactories      = givenFactories.toIndexedSeq,
      givenCandidateCount = givenCandidateCount.toMap,
      mainCalled          = mainCalled,
      sqlBlockCounter     = sqlBlockCounter
    )

  /** Restore interpreter to an earlier checkpoint, undoing any state added
   *  after that checkpoint was taken. */
  private[interpreter] def restoreCheckpoint(cp: InterpCheckpoint): Unit =
    globals.clear();             globals             ++= cp.globals
    extensions.clear();          extensions          ++= cp.extensions
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
    module.sections.foreach { s =>
      SectionRuntime.runSection(s, this)
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
      prevCheckpoints: Vector[InterpCheckpoint]
  ): Vector[InterpCheckpoint] =
    // Find the last valid restore point we have from the previous run.
    // prevCheckpoints(k) = state before section k.
    val restoreIdx = firstChanged.min(prevCheckpoints.length - 1).max(0)
    restoreCheckpoint(prevCheckpoints(restoreIdx))
    // Carry forward unchanged checkpoints (indices 0..restoreIdx inclusive).
    val reused = prevCheckpoints.take(restoreIdx + 1)
    // Re-run changed sections, collecting fresh checkpoints.
    val newCps = sections.drop(restoreIdx).map { s =>
      SectionRuntime.runSection(s, this)
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
                  fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("")
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
    Value.InstanceV("Response", Map(
      "status"  -> Value.intV(status),
      "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV(contentType))),
      "body"    -> Value.StringV(body)
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
            try Right(Computation.run(callValue(handler, List(payload), Map.empty)))
            catch
              case e: InterpretError =>
                Left(scalascript.backend.spi.RemoteCallError.RemoteFailed("handler_failed", e.getMessage))
              case e: Throwable =>
                Left(scalascript.backend.spi.RemoteCallError.RemoteFailed("handler_failed", String.valueOf(e.getMessage)))

  // ─── Minimal NativeContext for Http effect ───────────────────────────
  //
  // httpRun needs a NativeContext to call doHttpRequest.  This lightweight
  // factory reads the same ThreadLocals used by httpClient{} scopes.

  private[interpreter] def mkHttpCtx(): scalascript.backend.spi.NativeContext =
    new scalascript.backend.spi.NativeContext:
      def out = Interpreter.this.out
      def err = System.err
      override def featureGet(key: String): Option[Any] = Interpreter.this.nativeFeatureGet(key)
      override def featureSet(key: String, value: Any): Unit = Interpreter.this.nativeFeatureSet(key, value)
      override def featureRemove(key: String): Option[Any] = Interpreter.this.nativeFeatureRemove(key)
      override def featureLocalGet(key: String): Option[Any] = Interpreter.this.nativeFeatureLocalGet(key)
      override def featureLocalSet(key: String, value: Any): Unit = Interpreter.this.nativeFeatureLocalSet(key, value)
      override def featureLocalRemove(key: String): Option[Any] = Interpreter.this.nativeFeatureLocalRemove(key)
      override def httpBaseUrl: String    = Interpreter.this.httpBaseUrlState
      override def httpTimeoutMs: Long    = Interpreter.this.httpTimeoutMsState
      override def httpMaxRetries: Int    = Interpreter.this.httpMaxRetriesState
      override def httpRetryDelayMs: Long = Interpreter.this.httpRetryDelayMsState
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        Interpreter.this.invoke(fn.asInstanceOf[Value], args.map(wrapAnyAsValue))

  // ─── Health + cluster control routes — see ClusterRoutesRuntime.scala ──────

  private[interpreter] def registerHealthDefaults(): Unit =
    ClusterRoutesRuntime.registerHealthDefaults(this)

  private[interpreter] def registerOpenApiDefaults(): Unit =
    OpenApiRuntime.registerOpenApiDefaults(this)

  private[interpreter] def registerClusterDrainRoute(): Unit =
    ClusterRoutesRuntime.registerClusterDrainRoute(this)

  private[interpreter] def registerClusterStepDownRoute(): Unit =
    ClusterRoutesRuntime.registerClusterStepDownRoute(this)

  private[interpreter] def registerClusterMetricsPromRoute(): Unit =
    ClusterRoutesRuntime.registerClusterMetricsPromRoute(this)

  private[interpreter] def registerClusterEventsRoute(): Unit =
    ClusterRoutesRuntime.registerClusterEventsRoute(this)

  private[interpreter] def registerClusterStatusRoute(): Unit =
    ClusterRoutesRuntime.registerClusterStatusRoute(this)

  // ─── Built-ins — see BuiltinsRuntime.scala ────────────────────────────

  def invoke(fn: Value, args: List[Value]): Value =
    Computation.run(callValue(fn, args, Map.empty))

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
      override def registerRoute(method: String, path: String, handler: Any): Unit =
        Interpreter.this.routeRegistry.register(
          method, path, handler.asInstanceOf[Value], Interpreter.this,
          source   = Interpreter.this.currentLoadingFile,
          style    = if Interpreter.this.currentLoadingFile.isDefined then "load" else "route"
        )
      override def registerHealthDefaults(): Unit = Interpreter.this.registerHealthDefaults()
      override def registerOpenApiDefaults(): Unit = Interpreter.this.registerOpenApiDefaults()
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        Interpreter.this.invoke(fn.asInstanceOf[Value], args.map(wrapAnyAsValue))
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
    intrinsics.foreach {
      case (qn, scalascript.backend.spi.NativeImpl(eval)) =>
        registerNative(qn.value, args =>
          val raw = args.map(unwrapValueAsAny)
          val ret = eval(ctx, raw)
          wrapAnyAsValue(ret)
        )
      case _ => ()
    }

  private def unwrapValueAsAny(v: Value): Any = v match
    case Value.IntV(n)    => n
    case Value.DoubleV(d) => d
    case Value.StringV(s) => s
    case Value.BoolV(b)   => b
    case Value.UnitV      => ()
    case other            => other  // pass complex Values through unchanged

  private def wrapAnyAsValue(a: Any): Value = a match
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
  def exportedExtensions:    Map[(String, String), Value.FunV] = extensions.toMap
  def exportedParentTypes:   Map[String, String]               = parentTypes.toMap
  def exportedTypeFieldOrder: Map[String, List[String]]        = typeFieldOrder.toMap
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

  // ─── Call helpers — see CallRuntime.scala ────────────────────────────────

  private[interpreter] def callFun(f: Value.FunV, args: List[Value]): Computation =
    CallRuntime.callFun(f, args, this)

  // ─── Given / using helpers — see CallRuntime.scala ───────────────────────

  private[interpreter] def typeToString(t: scala.meta.Type): String =
    CallRuntime.typeToString(t)

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
    module.sections.foreach(SectionRuntime.runSection(_, this))

  /** Run all sections of a pre-parsed [[scalascript.ast.Module]] in this
   *  interpreter's current context (globals, plugins).  Unlike [[run]], this
   *  does **not** reinitialise builtins — it continues the existing REPL
   *  session.  Used by `:load file.ssc` to execute a `.ssc` file that was
   *  already parsed with [[scalascript.parser.Parser.parse]]. */
  def runSections(module: scalascript.ast.Module): Unit =
    module.sections.foreach(SectionRuntime.runSection(_, this))

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

  // ── v1.4 effect handlers — see EffectHandlers.scala ────────────────────
  //
  // loggerRun / randomRun / clockRun / envRun / httpRun /
  // retryRun / cacheRun / stateRun are in EffectHandlers.scala.

object Interpreter:
  def run(
      module:   Module,
      out:      java.io.PrintStream = System.out,
      baseDir:  Option[os.Path]     = None,
      lockPath: Option[os.Path]     = None
  ): Unit =
    Interpreter(out, baseDir, lockPath = lockPath).run(module)
