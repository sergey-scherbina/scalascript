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
  // Phase 6: interpreter call stack for currentStackTrace().
  private[interpreter] val callStack = scala.collection.mutable.ArrayBuffer.empty[(String, Int)]
  // When true, currentStackTrace() includes anonymous (<anon>) and _-prefixed frames.
  private[interpreter] var traceVerbose: Boolean = false
  // Types declared with @noTrace — throw uses ScriptExceptionNoTrace to skip JVM fillInStackTrace.
  private[interpreter] val noTraceTypes = mutable.HashSet.empty[String]
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


  // Base URL and timeout for httpClient {} scopes — thread-local so nested calls restore correctly.
  private[interpreter] val _httpBaseUrl      = ThreadLocal.withInitial[String](() => "")
  private[interpreter] val _httpTimeoutMs    = ThreadLocal.withInitial[Long](() => 30_000L)
  private[interpreter] val _httpMaxRetries   = ThreadLocal.withInitial[Int](() => 0)
  private[interpreter] val _httpRetryDelayMs = ThreadLocal.withInitial[Long](() => 1_000L)

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

  /** v1.26 — JDBC connections declared in front-matter `databases:`,
   *  materialised lazily and cached.  `sql` fenced blocks resolve their
   *  connection through this registry unless a `given`-style override
   *  (a `Value.Foreign("Connection", _)` bound to the `Connection`
   *  global) is in scope.  Empty by default — modules without any
   *  `databases:` section pay no JDBC cost. */
  private[interpreter] var sqlRegistry: scalascript.sql.ConnectionRegistry =
    scalascript.sql.ConnectionRegistry.empty
  private[interpreter] var sqlBlockCounter: Int = 0

  def run(module: Module): Unit =
    BuiltinsRuntime.initBuiltins(this)
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    modulePkg  = module.manifest.flatMap(_.pkg).getOrElse(Nil)
    module.manifest.foreach(m => i18nTranslations = m.translations)
    module.manifest.foreach { m =>
      if m.databases.nonEmpty then
        sqlRegistry = scalascript.sql.ConnectionRegistry(
          m.databases.map { d =>
            scalascript.sql.DatabaseSpec(d.name, d.url, d.user, d.password, d.driver)
          }
        )
    }
    module.manifest.foreach { m =>
      m.frontendFramework.foreach(scalascript.frontend.FrontendFrameworks.setBackend)
    }
    registerFrontmatterRoutes(module)
    module.sections.foreach(SectionRuntime.runSection(_, this))
    if !mainCalled then
      globals.get("main").foreach {
        case f: Value.FunV if f.params.isEmpty => Computation.run(callFun(f, Nil)); mainCalled = true
        case _ => ()
      }

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
        scalascript.server.Routes.register(r.method, r.path, lazyHandler, this)
      }
    }

  // ─── Minimal NativeContext for Http effect ───────────────────────────
  //
  // httpRun needs a NativeContext to call doHttpRequest.  This lightweight
  // factory reads the same ThreadLocals used by httpClient{} scopes.

  private[interpreter] def mkHttpCtx(): scalascript.backend.spi.NativeContext =
    new scalascript.backend.spi.NativeContext:
      def out = Interpreter.this.out
      def err = System.err
      override def httpBaseUrl: String    = _httpBaseUrl.get()
      override def httpTimeoutMs: Long    = _httpTimeoutMs.get()
      override def httpMaxRetries: Int    = _httpMaxRetries.get()
      override def httpRetryDelayMs: Long = _httpRetryDelayMs.get()
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        Interpreter.this.invoke(fn.asInstanceOf[Value], args.map(wrapAnyAsValue))

  // ─── Health + cluster control routes — see ClusterRoutesRuntime.scala ──────

  private[interpreter] def registerHealthDefaults(): Unit =
    ClusterRoutesRuntime.registerHealthDefaults(this)

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
      override def headless = Interpreter.this.headless
      override def registerRoute(method: String, path: String, handler: Any): Unit =
        scalascript.server.Routes.register(method, path, handler.asInstanceOf[Value], Interpreter.this)
      override def registerHealthDefaults(): Unit = Interpreter.this.registerHealthDefaults()
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        Interpreter.this.invoke(fn.asInstanceOf[Value], args.map(wrapAnyAsValue))
      override def httpBaseUrl: String    = _httpBaseUrl.get()
      override def httpTimeoutMs: Long    = _httpTimeoutMs.get()
      override def httpMaxRetries: Int    = _httpMaxRetries.get()
      override def httpRetryDelayMs: Long = _httpRetryDelayMs.get()
      override def setHttpTimeout(ms: Long): Unit = _httpTimeoutMs.set(ms)
      override def setHttpRetry(maxAttempts: Int, delayMs: Long): Unit =
        _httpMaxRetries.set(maxAttempts); _httpRetryDelayMs.set(delayMs)
      override def startTlsServer(port: Int, dir: String, cert: String, key: String): Unit =
        if !Interpreter.this.headless then
          scalascript.server.WebServer.start(port, dir, Interpreter.this.out, cert, key,
            wsRoutes = Interpreter.this.wsRoutes)
      override def startServer(port: Int, dir: String): Unit =
        if !Interpreter.this.headless then
          scalascript.server.WebServer.start(port, dir, Interpreter.this.out,
            wsRoutes = Interpreter.this.wsRoutes)
      override def startServerAsync(port: Int, dir: String): Unit =
        if !Interpreter.this.headless then
          Thread.ofVirtual().start { () =>
            try scalascript.server.WebServer.start(port, dir, Interpreter.this.out,
              wsRoutes = Interpreter.this.wsRoutes)
            catch case _: Throwable => ()
          }
      override def stopServer(): Unit =
        if !Interpreter.this.headless then scalascript.server.WebServer.stop()
      override def setMaxWsConnections(n: Int): Unit =
        _root_.scalascript.server.jvm._wsMaxActive.set(n)
      override def registerWsRoute(path: String, origins: List[String], protocols: List[String],
                                    maxConn: Int, maxRate: Int, handler: Any): Unit =
        Interpreter.this.wsRoutes.register(
          path, handler.asInstanceOf[Value], Interpreter.this, origins, protocols, maxConn, maxRate)
      override def registerWsAuthRoute(path: String, authFn: Any, handler: Any): Unit =
        Interpreter.this.wsRoutes.register(
          path, handler.asInstanceOf[Value], Interpreter.this, auth = Some(authFn.asInstanceOf[Value]))
      override def wsConnectSync(url: String, headers: Map[String, String],
                                  protocols: List[String], handler: Any): Unit =
        val sess = scalascript.server.WsClientSession(url, headers, protocols, Interpreter.this, Interpreter.this.out)
        sess.connect()
        Interpreter.this.invoke(handler.asInstanceOf[Value], List(sess.wsObj))
        sess.awaitClose()
      override def registerMiddleware(fn: Any): Unit =
        scalascript.server.Routes.addMiddleware(fn.asInstanceOf[Value], Interpreter.this)
      override def configureCors(origins: List[String], methods: List[String],
                                  allowedHeaders: List[String]): Unit =
        scalascript.server.WebServer.configureCors(origins, methods, allowedHeaders)
      override def enableGzip(): Unit = scalascript.server.WebServer.enableGzip()
      override def setMaxBodySize(bytes: Long): Unit = scalascript.server.WebServer.setMaxBodySize(bytes)
      override def setSpoolThreshold(bytes: Long): Unit = scalascript.server.WebServer.setSpoolThreshold(bytes)
      override def setUploadDir(path: String): Unit = scalascript.server.WebServer.setUploadDir(path)
      override def validationRecord(name: String, msg: String, default: Any): Any =
        validationStack.headOption match
          case Some(buf) => buf.put(name, msg); default
          case None      => throw new scalascript.server.RestValidationError(msg)
      override def dbConnect(dbName: String): java.sql.Connection =
        Interpreter.this.sqlRegistry.connect(dbName)
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
    case n: Long    => Value.IntV(n)
    case i: Int     => Value.IntV(i.toLong)
    case d: Double  => Value.DoubleV(d)
    case s: String  => Value.StringV(s)
    case b: Boolean => Value.BoolV(b)
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
