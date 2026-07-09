package ssc.bridge

import scalascript.backend.spi.{Backend, BlockContext, BlockForm, EffectHandler, IntrinsicImpl, NativeImpl, NativeContext, RemoteHandlerInfo, SpiValue}
import scalascript.interpreter.{DataValue, Value as V1Value}
import scalascript.interpreter.DataValue.*
import scalascript.markup.{Dialect, JvmMarkupCodec, Markup, MarkupCodec, PureMarkupCodec, SerializeOpts, TransformError, XmlEscape}
import scalascript.payments.money.{Currency as PayCurrency, Money as PayMoney}
import scalascript.payments.pix.PixQrCode
import ssc.{Done, Runtime, Show, Value as V2Value, V2EffectContext, V2PluginRegistry}

/** Loads v1 Backend plugins from the classpath and registers:
 *   1. NativeImpl intrinsics — via V2PluginRegistry.register (existing).
 *   2. BlockForm effect runners (runLogger, runState, …) — as synthetic v2
 *      ClosV globals in V2PluginRegistry.registerGlobal. Each runner installs
 *      a V2EffectContext handler for the duration of its body, then pops it.
 *      Logger.log/State.get/etc. dispatch through __method__ → V2EffectContext.
 *   3. `handle` global — runs the Free-monad loop for typed `handle { case … }` effects.
 *
 *  Usage: call PluginBridge.loadAll() before running any v2 program that needs plugins. */
/** Preserves the tag from v1 Value.Foreign("tag", h) through the v2 ForeignV round-trip. */
private final case class TaggedForeign(tag: String, value: AnyRef)

/** `throw expr` in bridged code: carries the thrown v2 VALUE so try/catch
 *  handlers can pattern-match it (message-only InterpretError lost the value). */
final case class BridgeThrow(value: ssc.Value) extends RuntimeException:
  override def getMessage: String = value.toString

object PluginBridge:

  /** Field layout of the http `Request` VALUE the server hands a route handler
   *  (InterpreterHttpHandler.liftRequest). `params`/`query`/`bearerToken`/
   *  `jwtClaims`/`basicAuth` are runtime-INJECTED and are NOT in std/http.ssc's
   *  `Request` case class — so the v1→v2 DataV builder (v1ToV2) and
   *  FrontendBridge's field-access lowering must BOTH use THIS order, or
   *  `req.params(:name)` reads the wrong slot / a Stub (v2-route-params-stub).
   *  Single source of truth: FrontendBridge locks `Request` to this and
   *  registerCaseClass must not override it with the 9-field case class. */
  val requestFieldNames: Vector[String] =
    Vector("method", "path", "body", "headers", "params", "query", "json",
           "form", "files", "session", "cookies", "bearerToken", "jwtClaims", "basicAuth")

  // ── DB connection registry for `databases:` frontmatter ─────────────────
  // Programs with `databases: default: url: jdbc:h2:...` frontmatter register
  // connections here before running so `Db.query`/`Db.execute` work under v2.
  private val dbRegistry = new java.util.concurrent.ConcurrentHashMap[String, java.sql.Connection]()

  private final case class V2RemoteHandler(info: RemoteHandlerInfo, handler: V2Value)
  private val remoteRegistry = new java.util.concurrent.ConcurrentHashMap[String, V2RemoteHandler]()

  /** Register an in-process JDBC connection by name (called by FrontendBridge
   *  when it processes `databases:` YAML frontmatter). */
  /** Normalize a databases: url to a JDBC url. busi/v1 accept the bare
   *  scheme (`sqlite::memory:`, `h2:mem:x`, `postgres://…`) as first-class;
   *  JDBC wants a `jdbc:`-prefixed form. Already-jdbc urls pass through. */
  private def toJdbcUrl(url: String): String =
    if url.startsWith("jdbc:") then url
    else if url.startsWith("sqlite:") then "jdbc:" + url
    else if url.startsWith("h2:") then "jdbc:" + url
    else if url.startsWith("postgres://") then "jdbc:postgresql://" + url.stripPrefix("postgres://")
    else if url.startsWith("postgresql://") then "jdbc:postgresql://" + url.stripPrefix("postgresql://")
    else if url.startsWith("mysql://") then "jdbc:mysql://" + url.stripPrefix("mysql://")
    else url  // unknown scheme: let the driver decide (may still be a valid jdbc alias)

  def registerDb(name: String, url: String): Unit =
    val conn = java.sql.DriverManager.getConnection(toJdbcUrl(url))
    dbRegistry.put(name, conn)

  /** Clear all registered DB connections (call between batch runs). */
  def clearDbs(): Unit = dbRegistry.clear()

  /** Clear per-program in-process remote handlers (called by FrontendBridge). */
  def clearRemoteHandlers(): Unit = remoteRegistry.clear()

  private def routeHandlerToV1(handler: Any): V1Value = handler match
    case v: V1Value  => v
    case v: V2Value  => v2ToV1(v)
    case other       => rawToV1(other)

  /** Minimal NativeContext for stateless intrinsics (IO, hash, math, etc.). */
  private object MinimalCtx extends NativeContext:
    def out: java.io.PrintStream = Console.out
    def err: java.io.PrintStream = Console.err
    override def dbConnect(dbName: String): java.sql.Connection =
      Option(dbRegistry.get(dbName)).getOrElse(
        throw new RuntimeException(
          s"No database registered for '$dbName' — add a databases: section to front-matter"))
    override def featureGet(key: String): Option[Any] = Option(featureBag.get(key))
    override def resolveGlobal(name: String): Option[Any] =
      V2PluginRegistry.lookupGlobal(name)
    override def invokeCallback(fn: Any, args: List[Any]): Any =
      fn match
        case c: V2Value.ClosV =>
          // Builder/instance args (mcpServer's srv) must ride OPAQUE like the
          // BlockContext.applyFn path (spiToV2 wraps ForeignV(raw)): the deep
          // rawToV2 conversion turned them into named-method objects whose
          // variadic field wrappers broke the natives' CURRIED two-step
          // protocol (srv.tool(name, desc)(handler) got all three at once and
          // raised its usage error). Scalars still convert normally.
          val v2Args = args.map {
            case v1v: V1Value if v1v.isInstanceOf[scalascript.interpreter.Value.InstanceV] =>
              V2Value.ForeignV(v1v)
            case a => rawToV2(a)
          }.toArray
          val result = Runtime.run(c.code, if v2Args.isEmpty then c.env else Runtime.extend(c.env, v2Args))
          v2ToRaw(result)
        case nfv: scalascript.interpreter.Value.NativeFnV =>
          scalascript.interpreter.Computation.run(nfv.f(args.map(rawToV1)))
        case other =>
          throw new RuntimeException(s"invokeCallback: not callable: ${Option(other).fold("null")(_.getClass.getName)}")
    // serve()/serve(port, tls(...)) on the v2 lane: the frontend-plugin's serve
    // native calls ctx.startServer/startTlsServer — the NativeContext DEFAULTS
    // are silent no-ops (IntrinsicImpl.scala), so the busi hub "booted" (banner
    // printed) but never bound a listener (BUGS.md v2-serve-noop-minimalctx).
    // Route through the same real v1 WebServer the serveAsync bridge uses;
    // the sync variants BLOCK on the calling thread (v1 serve semantics — the
    // program stays alive serving).
    override def startServer(port: Int, dir: String): Unit =
      scalascript.server.WebServer.start(port, dir, Console.out)
    override def startTlsServer(port: Int, dir: String, cert: String, key: String): Unit =
      scalascript.server.WebServer.start(port, dir, Console.out, cert, key)
    override def startServerAsync(port: Int, dir: String): Unit =
      minimalStartAsync(port, dir, "", "")
    override def startTlsServerAsync(port: Int, dir: String, cert: String, key: String): Unit =
      minimalStartAsync(port, dir, cert, key)
    override def stopServer(): Unit =
      try scalascript.server.WebServer.stop() catch case _: Throwable => ()
    // v1 serve() registers /_health + /_ready before binding
    // (ClusterRoutesRuntime.registerHealthDefaults) — mirror it on the bridge
    // registry so health probes see the same surface on the v2 lane.
    override def registerHealthDefaults(): Unit =
      import scalascript.interpreter.{Value as V1V, Computation}
      def isRegistered(path: String): Boolean =
        scalascript.server.Routes.all.exists(e => e.method == "GET" && e.path == path)
      val okResponse = V1V.InstanceV("Response", Map(
        "status"  -> V1V.intV(200),
        "headers" -> V1V.MapV(Map(V1V.StringV("Content-Type") -> V1V.StringV("application/json"))),
        "body"    -> V1V.StringV("""{"status":"ok"}""")
      ))
      val handler = V1V.NativeFnV("_healthOk", Computation.pureFn(_ => okResponse))
      if !isRegistered("/_health") then scalascript.server.Routes.register("GET", "/_health", handler, routeInterp)
      if !isRegistered("/_ready")  then scalascript.server.Routes.register("GET", "/_ready",  handler, routeInterp)
    private def minimalStartAsync(port: Int, dir: String, cert: String, key: String): Unit =
      val bound = new java.util.concurrent.CountDownLatch(1)
      val th = new Thread(() => {
        try scalascript.server.WebServer.start(port, dir, Console.out, cert, key, onBound = () => bound.countDown())
        catch case e: Throwable =>
          System.err.println(s"startServerAsync: ${e.getMessage}"); bound.countDown()
      }, "ssc-v2-web-async")
      th.setDaemon(true)
      th.start()
      bound.await(10, java.util.concurrent.TimeUnit.SECONDS)
    override def registerRoute(method: String, path: String, handler: Any): Unit =
      scalascript.server.Routes.register(method, path, routeHandlerToV1(handler), routeInterp)
    override def registerRouteWithOpenApi(
        method: String,
        path: String,
        handler: Any,
        metadata: scalascript.backend.spi.OpenApiGenerator.OpenApiMetadata
    ): Unit =
      scalascript.server.Routes.register(method, path, routeHandlerToV1(handler), routeInterp, metadata = metadata)

  /** Feature bag mirrored from v1's nativeFeatureSet — the content plugin's
   *  document-introspection natives (contentBlock/contentData/…) read the
   *  PARSED DOCUMENT via featureGet(ContentDocument). */
  private val featureBag = new java.util.concurrent.ConcurrentHashMap[String, Any]()

  @volatile private var currentCodeIdentity: V2Value =
    codeIdentityFromSource("", None)

  private def codeIdentityFromSource(raw: String, moduleName: Option[String]): V2Value =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString
    V2Value.DataV("CodeIdentity", Vector(
      V2Value.StrV("sha256"),
      V2Value.StrV(digest),
      V2Value.StrV("ssc"),
      moduleName.map(n => V2Value.DataV("Some", Vector(V2Value.StrV(n))))
        .getOrElse(V2Value.DataV("None", Vector.empty))
    ))

  /** Quoted-macro PRE-PASS: run v1's MacroCodegen.expand over the parsed
   *  module and splice each expanded code block's source back into the raw
   *  text (fence contents replaced pairwise, in document order). The bridge
   *  itself has no conversion for Term.SplicedMacroExpr — without this,
   *  ${'{…}} macro call sites printed "Unsupported: TermSplicedMacroExprImpl".
   *  Fault-tolerant: any mismatch/parse failure returns the input unchanged. */
  def expandMacrosInSource(raw: String, fileDir: Option[java.io.File]): String =
    try
      val module = scalascript.parser.Parser.parse(raw)
      val base   = fileDir.map(f => os.Path(f.getAbsolutePath))
      val expanded = scalascript.artifact.MacroCodegen.expand(module, base)
      def codeBlocks(m: scalascript.ast.Module): List[scalascript.ast.Content.CodeBlock] =
        def go(s: scalascript.ast.Section): List[scalascript.ast.Content.CodeBlock] =
          s.content.collect { case cb: scalascript.ast.Content.CodeBlock => cb } ++ s.subsections.flatMap(go)
        m.sections.flatMap(go)
      val orig = codeBlocks(module); val exp = codeBlocks(expanded)
      if orig.length != exp.length then raw
      else
        var out = raw
        var cursor = 0
        orig.zip(exp).foreach { (o, e) =>
          if o.source != e.source then
            val idx = out.indexOf(o.source, cursor)
            if idx >= 0 then
              // Preserve the trailing-newline boundary: the expanded source is
              // newline-less and gluing it to the closing ``` broke the fence.
              val rep = if o.source.endsWith("\n") && !e.source.endsWith("\n") then e.source + "\n" else e.source
              out = out.substring(0, idx) + rep + out.substring(idx + o.source.length)
              cursor = idx + rep.length
          else
            val idx = out.indexOf(o.source, cursor)
            if idx >= 0 then cursor = idx + o.source.length
        }
        out
    catch case _: Throwable => raw

  /** Parse the raw .ssc source with the v1 parser and expose its document
   *  content to plugins — mirrors Interpreter's `module.document.foreach
   *  (nativeFeatureSet(ContentDocument, _))`. Call once per convertSource. */
  def setDocumentFromSource(raw: String): Unit =
    val docKey = scalascript.backend.spi.NativeContextFeatureKeys.ContentDocument
    val importedKey = scalascript.backend.spi.NativeContextFeatureKeys.ContentImportedModules
    val currentKey = scalascript.backend.spi.NativeContextFeatureKeys.ContentCurrentSection
    featureBag.remove(docKey)
    featureBag.remove(importedKey)
    featureBag.remove(currentKey)
    val parsed = scala.util.Try(scalascript.parser.Parser.parse(raw)).toOption
    val moduleName = parsed.flatMap(_.manifest.flatMap(_.name).map(_.trim).filter(_.nonEmpty))
    currentCodeIdentity = codeIdentityFromSource(raw, moduleName)
    try
      parsed.flatMap(_.document).foreach { doc =>
        featureBag.put(docKey, doc)
        currentExecutableSection(doc).foreach(section => featureBag.put(currentKey, section))
      }
    catch case _: Throwable => () // no document — introspection natives raise their own error

  /** Register a parsed imported document for std.content's contentModule* APIs.
   *  Mirrors SectionRuntime.registerImportedContent for the FrontendBridge path. */
  def registerImportedContent(rel: String, resolvedFile: java.io.File, raw: String): Unit =
    if isContentHelperImport(rel) then return
    try
      val module = scalascript.parser.Parser.parse(raw)
      module.document.foreach { doc =>
        val namespace = module.manifest.flatMap(_.name).map(_.trim).filter(_.nonEmpty).getOrElse {
          val last = resolvedFile.getName
          if last.endsWith(".ssc") then last.stripSuffix(".ssc") else last
        }
        val key = scalascript.backend.spi.NativeContextFeatureKeys.ContentImportedModules
        val current = Option(featureBag.get(key)).collect {
          case table: Map[?, ?] =>
            table.toList.collect {
              case (ns: String, docs: List[?]) =>
                ns -> docs.collect { case d: scalascript.ast.DocumentContent => d }
            }.toMap
        }.getOrElse(Map.empty[String, List[scalascript.ast.DocumentContent]])
        featureBag.put(key, current.updated(namespace, current.getOrElse(namespace, Nil) :+ doc))
      }
    catch case _: Throwable => ()

  private def isContentHelperImport(path: String): Boolean =
    path == "std/content.ssc" || path.endsWith("std/content.ssc") ||
      path == "std/ui/content.ssc" || path.endsWith("std/ui/content.ssc")

  private def currentExecutableSection(doc: scalascript.ast.DocumentContent): Option[scalascript.ast.SectionContent] =
    def hasExecutableBlock(section: scalascript.ast.SectionContent): Boolean =
      section.blocks.exists {
        case scalascript.ast.ContentBlock.Embedded(_, _, scalascript.ast.EmbeddedKind.Executable, _, _) => true
        case _ => false
      }
    def go(section: scalascript.ast.SectionContent): List[scalascript.ast.SectionContent] =
      val childHits = section.children.flatMap(go)
      val selfHit = if hasExecutableBlock(section) then List(section) else Nil
      selfHit ++ childHits
    doc.sections.flatMap(go).lastOption

  /** Load all Backend plugins via ServiceLoader; register NativeImpl prims AND
   *  BlockForm runners. Also registers the built-in `handle` global. */
  def loadAll(): Int =
    registerHandle()
    registerRunStream()
    registerYamlSection()
    registerRunAsync()
    // Render native v1 Values (DocV, MarkupV, …) that ride inside ForeignV via
    // v1's own show — println(doc) on the v2 side printed "<foreign>" otherwise.
    Show.foreignRenderer = {
      case v1v: scalascript.interpreter.Value => scalascript.interpreter.Value.show(v1v)
      case doc: scalascript.markup.Markup.Doc => scalascript.markup.PureMarkupCodec.serialize(doc)
      case _                                  => null
    }
    registerSys()
    registerInterpreterBuiltins()
    registerAmbientEffectOps()
    registerOptics()
    registerComputedCellDispatch()
    var count = 0
    val cl = Thread.currentThread().getContextClassLoader
    val loader = java.util.ServiceLoader.load(classOf[Backend], cl)
    val it = loader.iterator()
    while it.hasNext do
      scala.util.Try(it.next()).foreach { backend =>
        backend.intrinsics.foreach { case (qn, impl) =>
          val op = qn.toString
          impl match
            case NativeImpl(eval) =>
              val nativeFn: List[V2Value] => V2Value = args => {
                val rawArgs: List[Any] = args.map(v2ToRaw)
                val rawResult: Any = eval(MinimalCtx, rawArgs)
                rawToV2(rawResult)
              }
              // Register as prim (for Prim(op, args) IR nodes)
              V2PluginRegistry.register(op, args => nativeFn(args))
              // Register as global (for App(Global(name), args) IR nodes)
              // arity=-1 = variadic: env = all call args (no captured env), skip arity check.
              V2PluginRegistry.registerGlobal(op, V2Value.ClosV(Runtime.emptyEnv, -1, env => {
                Done(nativeFn(env.toList))
              }))
              count += 1
            case _ => // InlineCode / RuntimeCall: compile-time only, skip
        }
        backend.blockForms.foreach { case (runnerName, bf) =>
          registerBlockForm(runnerName, bf)
          count += 1
        }
      }
    // 0-arity OS globals must be pre-computed values (not closures), because callers write
    // `cwd` not `cwd()`. Register AFTER plugins so we override any ClosV they registered.
    V2PluginRegistry.registerGlobal("cwd", V2Value.StrV(System.getProperty("user.dir", ".")))
    V2PluginRegistry.registerGlobal("sep", V2Value.StrV(java.io.File.separator))
    V2PluginRegistry.registerGlobal("platform", V2Value.DataV("JVM", Vector.empty))
    // args — command-line args as a Cons/Nil VALUE list, same post-plugins
    // override: a plugin bridges a native FUNCTION under "args", and with the
    // native winning, `args.length` dispatched .length on a closure (the length
    // FastCode's tolerant `case _ => 0L` masked it on v2; the v1 lane has the
    // same gap on NativeFnV(<native:args>)). The list is the documented
    // semantics (dataset-word-count.ssc: `args.length`, `args(0)`). Runners set
    // Runtime.argv BEFORE loadAll; the scalascript.args prop is the embedder
    // fallback.
    V2PluginRegistry.registerGlobal("args",
      (if Runtime.argv.nonEmpty then Runtime.argv
       else sys.props.get("scalascript.args").map(_.split(",").toList).getOrElse(Nil))
        .foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (a, acc) =>
          V2Value.DataV("Cons", Vector(V2Value.StrV(a), acc))
        })
    // Pre-call known 0-arg plugin functions (declared as `extern def foo: T` with no parens in ssc).
    // These must be registered as plain values, not ClosV, so `fetchActionClear(..., emptyHeaders)`
    // works without `emptyHeaders()` at the call site (ssc no-parens def semantics).
    Seq("emptyHeaders", "hashSignal").foreach { name =>
      V2PluginRegistry.lookupGlobal(name).collect { case c: V2Value.ClosV =>
        val result = scala.util.Try(Runtime.run(c.code, c.env))
        result.foreach { v => V2PluginRegistry.registerGlobal(name, v) }
      }
    }
    // Mirror.Of[T] support: DataV("Mirror", [label, elemLabels, elemTypes]) dispatch
    V2PluginRegistry.registerFieldNames("Mirror", Vector("label", "elemLabels", "elemTypes"))

    // Register plugin-owned extern type field names so v2ToV1(DataV) produces named fields.
    // These are types created in v2 user code but consumed by v1 plugins by name.
    V2PluginRegistry.registerFieldNames("Response",
      Vector("status", "headers", "body"))
    V2PluginRegistry.registerFieldNames("StreamResponse",
      Vector("status", "headers", "callback"))
    V2PluginRegistry.registerFieldNames("Request", requestFieldNames)
    V2PluginRegistry.registerFieldNames("KV", Vector("key", "value"))
    V2PluginRegistry.registerFieldNames("Rate", Vector("elements", "perMillis"))
    registerMarkupBridge()
    registerPaymentsBridge()
    registerRemoteRegistry()
    // Override OIDC client prims BEFORE building namespace objects so the namespace
    // ForeignV picks up the overridden versions (buildNamespaceObjects reads from registry).
    overrideOidcClientStubs()
    // Build namespace objects for dotted globals: "oauth.authServer" → oauth = {authServer: ClosV}
    buildNamespaceObjects()
    // Override serve/emit/mount with no-op stubs: batch runner has no web server.
    // These are called at end of program; silently succeeding avoids plugin arg-type errors.
    import V2Value.*
    // NB: serve/emit/mount stubbing + batch-runner stubs are BATCH-ONLY —
    // `ssc run --v2` must run REAL servers (Phase-3: the switch can't ship a
    // runner that silently no-ops serve). BatchCli calls installBatchStubs()
    // explicitly after loadAll.
    registerFsBuiltins()
    registerDatasetNatives()
    // Web server bridge registers AFTER the plugin loop so route/serveAsync/
    // stop WIN over same-named http-plugin intrinsics (same discipline as
    // registerActors below).
    registerWebServer()
    // Actor runtime registers LAST so its spawn/receive/self/exit/runActors
    // globals win over same-named v1 plugin intrinsics (a bridged os 'exit'
    // used to shadow the actor exit and System.exit(0) killed the batch JVM).
    registerActors()

    count

  private def registerRemoteRegistry(): Unit =
    import V2Value.*

    V2PluginRegistry.registerFieldNames("RemoteFunction", Vector("name"))
    V2PluginRegistry.registerFieldNames("RemoteHandlerInfo",
      Vector("name", "function", "path", "requestType", "responseType", "transports"))
    V2PluginRegistry.registerFieldNames("HandlerNotFound", Vector("name"))
    V2PluginRegistry.registerFieldNames("RemoteFailed", Vector("code", "message"))

    def optionString(v: V2Value): Option[String] = v match
      case DataV("Some", IndexedSeq(StrV(s))) => Some(s)
      case DataV("None", _)                   => None
      case UnitV                              => None
      case StrV(s) if s.nonEmpty              => Some(s)
      case _                                  => None

    def listOf(values: Iterable[V2Value]): V2Value =
      values.toList.foldRight[V2Value](DataV("Nil", Vector.empty)) { (x, acc) =>
        DataV("Cons", Vector(x, acc))
      }

    def optionValue(value: Option[String]): V2Value =
      value.map(s => DataV("Some", Vector(StrV(s))): V2Value).getOrElse(DataV("None", Vector.empty))

    def handlerInfoValue(info: RemoteHandlerInfo): V2Value =
      DataV("RemoteHandlerInfo", Vector(
        StrV(info.name),
        StrV(info.function),
        optionValue(info.path),
        optionValue(info.requestType),
        optionValue(info.responseType),
        listOf(info.transports.toList.sorted.map(StrV(_)))
      ))

    def handlerNotFound(name: String): V2Value =
      DataV("HandlerNotFound", Vector(StrV(name)))

    def remoteFailed(message: String): V2Value =
      DataV("RemoteFailed", Vector(StrV("handler_failed"), StrV(message)))

    def remoteErrorMessage(error: V2Value): String = error match
      case DataV("HandlerNotFound", IndexedSeq(StrV(name))) => s"remote handler not found: $name"
      case DataV("RemoteFailed", IndexedSeq(StrV(code), StrV(message))) =>
        s"remote handler failed ($code): $message"
      case other => Show.show(other)

    def remoteFunctionName(value: V2Value): Option[String] = value match
      case DataV("RemoteFunction", IndexedSeq(StrV(name))) => Some(name)
      case _                                               => None

    def remoteMethodCall(args: List[V2Value]): Option[(String, V2Value)] = args match
      case List(remoteFn, payload) =>
        remoteFunctionName(remoteFn).map(_ -> payload)
      case List(StrV(_), remoteFn, payload) =>
        remoteFunctionName(remoteFn).map(_ -> payload)
      case _ => None

    def registerHandler(args: List[V2Value]): V2Value = args match
      case List(StrV(name), StrV(function), handler, pathV, requestV, responseV) =>
        val path = optionString(pathV)
        val transports =
          if path.isDefined then Set("in-process", "http-json")
          else Set("in-process")
        val info = RemoteHandlerInfo(
          name = name,
          function = function,
          path = path,
          requestType = optionString(requestV),
          responseType = optionString(responseV),
          transports = transports
        )
        remoteRegistry.put(name, V2RemoteHandler(info, handler))
        UnitV
      case _ => sys.error("remote.registerHandler(name, function, handler, path, request, response)")

    def invoke(name: String, payload: V2Value): Either[V2Value, V2Value] =
      Option(remoteRegistry.get(name)) match
        case None => Left(handlerNotFound(name))
        case Some(entry) =>
          try Right(callClosure(entry.handler, List(payload)))
          catch case e: Throwable =>
            Left(remoteFailed(Option(e.getMessage).getOrElse(e.getClass.getName)))

    def remoteCallValue(name: String, payload: V2Value): V2Value =
      invoke(name, payload) match
        case Right(value) => value
        case Left(error)  => sys.error(remoteErrorMessage(error))

    def remoteTryCallValue(name: String, payload: V2Value): V2Value =
      invoke(name, payload) match
        case Right(value) => DataV("Right", Vector(value))
        case Left(error)  => DataV("Left", Vector(error))

    V2PluginRegistry.register("remote.registerHandler", registerHandler)
    V2PluginRegistry.registerGlobal("remote.registerHandler", ClosV(Runtime.emptyEnv, -1, env =>
      Done(registerHandler(env.toList))))

    V2PluginRegistry.register("__method__.call", {
      case args => remoteMethodCall(args) match
        case Some((name, payload)) => remoteCallValue(name, payload)
        case None                  => DataV("Stub", Vector.empty)
    })

    V2PluginRegistry.register("__method__.tryCall", {
      case args => remoteMethodCall(args) match
        case Some((name, payload)) => remoteTryCallValue(name, payload)
        case None                  => DataV("Stub", Vector.empty)
    })

    V2PluginRegistry.registerGlobal("remoteFunction", ClosV(Runtime.emptyEnv, 1, env => env.toList match
      case List(StrV(name)) => Done(DataV("RemoteFunction", Vector(StrV(name))))
      case _                => sys.error("remoteFunction[A, B](name: String)")
    ))
    V2PluginRegistry.registerGlobal("remoteCall", ClosV(Runtime.emptyEnv, 2, env => env.toList match
      case List(StrV(name), payload) => Done(remoteCallValue(name, payload))
      case _ => sys.error("remoteCall[A, B](name: String, value: A)")
    ))
    V2PluginRegistry.registerGlobal("remoteTryCall", ClosV(Runtime.emptyEnv, 2, env => env.toList match
      case List(StrV(name), payload) => Done(remoteTryCallValue(name, payload))
      case _ => sys.error("remoteTryCall[A, B](name: String, value: A)")
    ))
    V2PluginRegistry.registerGlobal("remoteHandlers", ClosV(Runtime.emptyEnv, 0, _ =>
      Done(listOf(remoteRegistry.values().toArray.toList
        .collect { case entry: V2RemoteHandler => entry.info }
        .sortBy(_.name)
        .map(handlerInfoValue)))))

  private def registerMarkupBridge(): Unit =
    import V2Value.*

    MarkupCodec.setDefault(JvmMarkupCodec)
    V2PluginRegistry.registerFieldNames("SerializeOpts", Vector("pretty", "indent", "omitXmlDecl"))
    V2PluginRegistry.registerFieldNames("TransformError", Vector("message"))

    def strValue(v: V2Value): String = v match
      case StrV(s)   => s
      case IntV(n)   => n.toString
      case BoolV(b)  => b.toString
      case FloatV(d) => d.toString
      case other     => Show.show(other)

    def xmlPart(v: V2Value): String = v match
      case ForeignV(Markup.Raw(s)) => s
      case ForeignV(doc: Markup.Doc) =>
        MarkupCodec.default.serialize(doc, SerializeOpts(omitXmlDecl = true))
      case ForeignV(elem: Markup.Element) =>
        MarkupCodec.default.serialize(Markup.Doc(root = elem), SerializeOpts(omitXmlDecl = true))
      case other => XmlEscape.escape(strValue(other))

    def docValue(v: V2Value): Markup.Doc = v match
      case ForeignV(doc: Markup.Doc) => doc
      case ForeignV(V1Value.MarkupV(doc)) => doc
      case other => sys.error(s"expected Markup.Doc, got ${Show.show(other)}")

    def optsValue(v: V2Value): SerializeOpts = v match
      case ForeignV(opts: SerializeOpts) => opts
      case UnitV => SerializeOpts.default
      case DataV("SerializeOpts", fields) =>
        val d = SerializeOpts.default
        val pretty = fields.lift(0).collect { case BoolV(b) => b }.getOrElse(d.pretty)
        val indent = fields.lift(1).collect { case StrV(s) => s }.getOrElse(d.indent)
        val omit   = fields.lift(2).collect { case BoolV(b) => b }.getOrElse(d.omitXmlDecl)
        SerializeOpts(pretty = pretty, indent = indent, omitXmlDecl = omit)
      case other => sys.error(s"expected SerializeOpts, got ${Show.show(other)}")

    def paramsValue(v: V2Value): Map[String, String] = v match
      case DataV("Nil", _) | UnitV => Map.empty
      case ForeignV(m: collection.mutable.Map[?, ?]) =>
        m.asInstanceOf[collection.mutable.Map[V2Value, V2Value]].iterator.map {
          case (StrV(k), value) => k -> strValue(value)
          case (key, _) => sys.error(s"XSLT params require String keys, got ${Show.show(key)}")
        }.toMap
      case ForeignV(m: collection.immutable.Map[?, ?]) if m.keysIterator.forall(_.isInstanceOf[V2Value]) =>
        m.asInstanceOf[collection.immutable.Map[V2Value, V2Value]].iterator.map {
          case (StrV(k), value) => k -> strValue(value)
          case (key, _) => sys.error(s"XSLT params require String keys, got ${Show.show(key)}")
        }.toMap
      case other => sys.error(s"expected Map[String, String] params, got ${Show.show(other)}")

    def parseXml(raw: String): V2Value =
      JvmMarkupCodec.parse(raw, Dialect.Xml1_0) match
        case Right(doc) => ForeignV(doc)
        case Left(err)  => sys.error(err.getMessage)

    def eitherDoc(result: Either[TransformError, Markup.Doc]): V2Value = result match
      case Right(doc) => DataV("Right", Vector(ForeignV(doc)))
      case Left(err)  => DataV("Left", Vector(DataV("TransformError", Vector(StrV(err.message)))))

    def serializeWith(codec: MarkupCodec, args: List[V2Value]): V2Value = args match
      case List(doc)       => StrV(codec.serialize(docValue(doc), SerializeOpts.default))
      case List(doc, opts) => StrV(codec.serialize(docValue(doc), optsValue(opts)))
      case _ => sys.error("serialize(doc, opts?)")

    def transformWith(codec: MarkupCodec, args: List[V2Value]): V2Value = args match
      case List(doc, StrV(xslt)) =>
        eitherDoc(codec.transform(docValue(doc), xslt, Map.empty))
      case List(doc, StrV(xslt), params) =>
        eitherDoc(codec.transform(docValue(doc), xslt, paramsValue(params)))
      case _ => sys.error("transform(doc, xslt, params?)")

    def methodObject(fields: (String, V2Value)*): V2Value =
      ForeignV(collection.immutable.Map.from(fields).asInstanceOf[AnyRef])

    def codecObject(codec: MarkupCodec): V2Value =
      methodObject(
        "id" -> StrV(codec.id),
        "parse" -> ClosV(Runtime.emptyEnv, -1, env => env.toList match
          case List(StrV(src)) =>
            Done(codec.parse(src, Dialect.Xml1_0) match
              case Right(doc) => DataV("Right", Vector(ForeignV(doc)))
              case Left(err)  => DataV("Left", Vector(DataV("ParseError", Vector(StrV(err.message), IntV(err.line), IntV(err.column)))))
            )
          case _ => sys.error("parse(src: String)")
        ),
        "serialize" -> ClosV(Runtime.emptyEnv, -1, env => Done(serializeWith(codec, env.toList))),
        "transform" -> ClosV(Runtime.emptyEnv, -1, env => Done(transformWith(codec, env.toList)))
      )

    val defaultCodecObj = codecObject(JvmMarkupCodec)
    V2PluginRegistry.registerGlobal("__xmlPart", ClosV(Runtime.emptyEnv, 1, env => env.toList match
      case List(value) => Done(StrV(xmlPart(value)))
      case _ => sys.error("xml interpolator: expected one splice argument")
    ))
    V2PluginRegistry.registerGlobal("xml", ClosV(Runtime.emptyEnv, 1, env => env.toList match
      case List(StrV(raw)) => Done(parseXml(raw))
      case _ => sys.error("xml interpolator: expected one String argument")
    ))
    V2PluginRegistry.registerGlobal("MarkupCodec", methodObject(
      "default" -> defaultCodecObj,
      "named" -> ClosV(Runtime.emptyEnv, 1, env => env.toList match
        case List(StrV("pure")) => Done(codecObject(PureMarkupCodec))
        case List(StrV("jvm")) | List(StrV("jvm-sax")) => Done(defaultCodecObj)
        case List(StrV(other)) => sys.error(s"No MarkupCodec registered for id '$other'")
        case _ => sys.error("MarkupCodec.named(id)")
      )
    ))
    V2PluginRegistry.registerGlobal("PureMarkupCodec", codecObject(PureMarkupCodec))
    V2PluginRegistry.registerGlobal("SerializeOpts", methodObject(
      "default" -> ForeignV(SerializeOpts.default),
      "pretty" -> ForeignV(SerializeOpts.pretty)
    ))

  private def registerPaymentsBridge(): Unit =
    import V2Value.*

    def reg(tag: String, fields: String*): Unit =
      V2PluginRegistry.registerFieldNames(tag, fields.toVector)

    reg("Currency", "code")
    reg("Money", "minorUnits", "currency")
    Seq("IntentId", "CustomerId", "VaultId", "PlanId", "SubscriptionId", "RefundId",
      "DisputeId", "ChargeId", "MandateId", "TransferId", "RejectCode", "ReturnCode")
      .foreach(reg(_, "value"))
    reg("PaymentCapabilities", "supportsSubscriptions", "supportsSCA", "supports3DS2",
      "supportsACH", "supportsSEPA", "supportsApplePay", "supportsGooglePay",
      "supportsRefunds", "supportsPartialRefunds", "supportsDisputes",
      "supportsConnectedAccounts", "supportsMultiCurrency", "supportsMandates")
    reg("SCAChallenge", "provider", "redirectUrl", "returnUrl", "fingerprint")
    reg("Charge", "id", "intentId", "amount", "paid", "receiptUrl", "balanceTransactionId")
    reg("Card", "token")
    reg("ApplePayCard", "token")
    reg("GooglePayCard", "token")
    reg("Wallet", "provider", "externalId")
    reg("SavedMethod", "vaultId")
    reg("Fingerprint", "value")
    reg("RequiresPaymentMethod", "id", "amount", "metadata")
    reg("RequiresConfirmation", "id", "amount", "method")
    reg("RequiresAction", "id", "amount", "action")
    reg("Processing", "id", "amount")
    reg("Succeeded", "id", "amount", "charge")
    reg("Canceled", "id", "reason")
    reg("Failed", "id", "error", "retryable")
    reg("CreateIntentRequest", "amount", "method", "confirm", "customer", "captureMethod",
      "setupFutureUsage", "offSession", "mandateId", "scaExemptions", "metadata",
      "description", "returnUrl")
    reg("CreateCustomerRequest", "email", "name", "metadata")
    reg("Customer", "id", "email", "name", "metadata")
    reg("StoredMethod", "vaultId", "last4", "brand", "expMonth", "expYear", "funding",
      "isDefault", "networkToken", "mandateId")
    reg("Daily", "count"); reg("Weekly", "count"); reg("Monthly", "count"); reg("Yearly", "count")
    reg("CreatePlanRequest", "amount", "interval", "trialPeriodDays", "metadata")
    reg("Plan", "id", "amount", "interval", "trialPeriodDays", "metadata")
    reg("SubscribeOpts", "trialPeriodDays", "defaultMethod", "metadata")
    reg("Subscription", "id", "customerId", "planId", "status", "currentPeriodEnd",
      "cancelAtPeriodEnd", "trialEnd")
    reg("RefundRequest", "intentId", "amount", "reason")
    reg("Refund", "id", "intentId", "amount", "reason", "status")
    reg("DisputeEvidence", "customerCommunication", "receipt", "shippingDocumentation",
      "uncategorizedText", "serviceDocumentation")
    reg("Dispute", "id", "intentId", "amount", "reason", "status", "dueDate", "evidence")
    reg("BankAccount", "iban", "accountNumber", "routingNumber", "bankCode", "pixKey",
      "holderName", "countryCode", "bic", "sortCode", "upiVpa", "zenginBankCode",
      "zenginBranchCode", "paynowProxy", "payid", "bsbNumber", "transitNumber",
      "institutionNumber", "email", "phone", "clabe")
    reg("InitiateTransferRequest", "rail", "amount", "sender", "recipient", "reference",
      "idempotencyKey", "sameDay", "scheduledDate", "metadata", "chargeBearer", "uetr")
    reg("BankTransfer", "id", "rail", "amount", "sender", "recipient", "reference",
      "status", "createdAt", "settledAt", "returnedAt", "metadata", "uetr", "gpiTrail",
      "chargeBearer")
    reg("InitiateDirectDebitRequest", "rail", "amount", "mandateId", "creditorAccount",
      "debtorAccount", "creditorName", "reference", "idempotencyKey", "sameDay",
      "scheduledDate", "metadata")
    reg("StaticConfig", "pixKey", "merchantName", "merchantCity", "amount", "txid")
    reg("DynamicConfig", "cobvUrl", "merchantName", "merchantCity", "amount", "txid")
    reg("PixConfig", "pixApiUrl", "pixClientId", "pixClientSecret", "pixPixKey",
      "pixCertPath", "pixKeyPath")
    reg("FedNowConfig", "fednowApiUrl", "fednowCertPath", "fednowKeyPath",
      "fednowRoutingNumber", "fednowParticipantId")
    reg("FedNowLimitExceeded", "amount", "limit")

    val none: V2Value = DataV("None", Vector.empty)
    def some(v: V2Value): V2Value = DataV("Some", Vector(v))
    def listOf(xs: Iterable[V2Value]): V2Value =
      xs.toList.foldRight(DataV("Nil", Vector.empty): V2Value)((x, acc) => DataV("Cons", Vector(x, acc)))
    def unlist(v: V2Value): List[V2Value] =
      val b = List.newBuilder[V2Value]
      var cur = v
      var go = true
      while go do cur match
        case DataV("Cons", IndexedSeq(h, t)) => b += h; cur = t
        case DataV("Nil", _)                 => go = false
        case ForeignV(xs: collection.mutable.ArrayBuffer[?]) =>
          b ++= xs.asInstanceOf[collection.mutable.ArrayBuffer[V2Value]]
          go = false
        case _ => go = false
      b.result()
    def emptyMap: V2Value =
      ForeignV(collection.mutable.LinkedHashMap.empty[V2Value, V2Value].asInstanceOf[AnyRef])
    def fn(arity: Int = -1)(body: List[V2Value] => V2Value): V2Value =
      ClosV(Runtime.emptyEnv, arity, env => Done(body(env.toList)))
    def methodObject(show: String, underlying0: AnyRef = null)(fields: (String, V2Value)*): V2Value =
      val fieldMap = collection.immutable.Map.from(fields)
      ForeignV(new ssc.Value.NamedMethodObj {
        def getField(n: String): Option[ssc.Value] = fieldMap.get(n).orElse {
          if n == "_show" then Some(StrV(show)) else None
        }
        def underlying: AnyRef = if underlying0 == null then this else underlying0
        override def toString: String = show
      })
    def str(v: V2Value): String = v match
      case StrV(s) => s
      case IntV(n) => n.toString
      case DataV(tag, _) if tag.forall(c => c.isUpper || c == '_') => tag
      case DataV("Currency", IndexedSeq(StrV(code))) => code
      case other => Show.show(other).stripPrefix("\"").stripSuffix("\"")
    def bool(v: V2Value): Boolean = v match
      case BoolV(b) => b
      case _        => false
    def int(v: V2Value): Long = v match
      case IntV(n) => n
      case FloatV(d) => d.toLong
      case ForeignV(bd: java.math.BigDecimal) => bd.longValue()
      case StrV(s) => s.toLongOption.getOrElse(0L)
      case _ => 0L
    def decimal(v: V2Value): BigDecimal = v match
      case IntV(n) => BigDecimal(n)
      case FloatV(d) => BigDecimal(d)
      case StrV(s) => BigDecimal(s)
      case ForeignV(bd: java.math.BigDecimal) => BigDecimal(bd)
      case DataV("Money", _) =>
        val (minor, code) = moneyParts(v)
        BigDecimal(minor).bigDecimal.scaleByPowerOfTen(-PayCurrency.minorUnitsPower(PayCurrency(code)))
      case _ => BigDecimal(0)
    def field(v: V2Value, name: String, fallbackIdx: Int): V2Value = v match
      case DataV(tag, fs) =>
        V2PluginRegistry.lookupFieldNames(tag)
          .flatMap(names => Option(names.indexOf(name)).filter(_ >= 0).flatMap(i => fs.lift(i)))
          .orElse(fs.lift(fallbackIdx))
          .getOrElse(UnitV)
      case ForeignV(nmo: ssc.Value.NamedMethodObj) =>
        nmo.getField(name).getOrElse(UnitV)
      case _ => UnitV
    def opt(v: V2Value): Option[V2Value] = v match
      case DataV("Some", IndexedSeq(x)) => Some(x)
      case DataV("None", _) | UnitV     => None
      case other                        => Some(other)
    def currencyCode(v: V2Value): String = v match
      case DataV("Currency", IndexedSeq(StrV(code))) => code
      case DataV(tag, _) if tag.length == 3 && tag.forall(_.isUpper) => tag
      case StrV(code) => code
      case _ => "USD"
    def currencyV(code: String): V2Value =
      DataV("Currency", Vector(StrV(code.toUpperCase(java.util.Locale.ROOT))))
    def moneyV(minor: Long, currency: String): V2Value =
      DataV("Money", Vector(IntV(minor), currencyV(currency)))
    def moneyParts(v: V2Value): (Long, String) = v match
      case DataV("Money", fs) if fs.length >= 2 => int(fs(0)) -> currencyCode(fs(1))
      case ForeignV(m: PayMoney) => m.minorUnits -> m.currency.toString
      case _ => int(v) -> "USD"
    def payMoney(v: V2Value): PayMoney =
      val (minor, code) = moneyParts(v)
      PayMoney(minor, PayCurrency(code))
    def idV(tag: String, value: String): V2Value = DataV(tag, Vector(StrV(value)))
    def idString(v: V2Value): String = v match
      case DataV(_, IndexedSeq(StrV(s))) => s
      case StrV(s) => s
      case other => str(other)
    def nowString: V2Value = StrV(java.time.Instant.now().toString)

    def instantV(i: java.time.Instant): V2Value =
      methodObject(i.toString, i)(
        "plusSeconds" -> fn(1) {
          case List(IntV(seconds)) => instantV(i.plusSeconds(seconds))
          case _ => instantV(i)
        },
        "isAfter" -> fn(1) {
          case List(ForeignV(nmo: ssc.Value.NamedMethodObj)) if nmo.underlying.isInstanceOf[java.time.Instant] =>
            BoolV(i.isAfter(nmo.underlying.asInstanceOf[java.time.Instant]))
          case _ => BoolV(false)
        },
        "toString" -> fn(0)(_ => StrV(i.toString))
      )

    def capabilitiesV: V2Value =
      DataV("PaymentCapabilities", Vector(
        BoolV(true), BoolV(true), BoolV(true), BoolV(true), BoolV(true),
        BoolV(true), BoolV(true), BoolV(true), BoolV(true), BoolV(true),
        BoolV(false), BoolV(true), BoolV(true)))
    def chargeV(id: String, intent: V2Value, amount: V2Value): V2Value =
      DataV("Charge", Vector(idV("ChargeId", id), intent, amount, BoolV(true),
        some(StrV(s"https://payments.example.test/receipts/$id")), none))
    def succeededIntent(id: String, amount: V2Value): V2Value =
      val intentId = idV("IntentId", id)
      DataV("Succeeded", Vector(intentId, amount, chargeV(s"ch_$id", intentId, amount)))

    def paymentProvider(providerId: String): V2Value =
      var seq = 0
      def next(prefix: String): String =
        seq += 1
        s"${providerId}_demo_${prefix}_$seq"
      methodObject(s"PaymentProvider($providerId)")(
        "id" -> StrV(providerId),
        "displayName" -> StrV(providerId.capitalize + " deterministic test provider"),
        "spiVersion" -> StrV("v2-bridge"),
        "capabilities" -> capabilitiesV,
        "mode" -> DataV("Test", Vector.empty),
        "createIntent" -> fn(1) {
          case List(req) =>
            val amount = field(req, "amount", 0)
            val capture = field(req, "captureMethod", 4)
            val id = next("pi")
            capture match
              case DataV("Manual", _) => DataV("Processing", Vector(idV("IntentId", id), amount))
              case _                  => succeededIntent(id, amount)
          case _ => sys.error("createIntent(req)")
        },
        "confirmIntent" -> fn(2) {
          case List(id, _) => succeededIntent(idString(id), moneyV(0L, "USD"))
          case _ => sys.error("confirmIntent(id, method)")
        },
        "captureIntent" -> fn(-1) {
          case id :: amountOpt :: Nil => succeededIntent(idString(id), opt(amountOpt).getOrElse(moneyV(0L, "USD")))
          case id :: Nil              => succeededIntent(idString(id), moneyV(0L, "USD"))
          case _ => sys.error("captureIntent(id, amount?)")
        },
        "voidIntent" -> fn(1)(_ => UnitV),
        "createPlan" -> fn(1) {
          case List(req) =>
            DataV("Plan", Vector(idV("PlanId", next("plan")), field(req, "amount", 0),
              field(req, "interval", 1), field(req, "trialPeriodDays", 2), field(req, "metadata", 3)))
          case _ => sys.error("createPlan(req)")
        },
        "createCustomer" -> fn(1) {
          case List(req) =>
            DataV("Customer", Vector(idV("CustomerId", next("cus")), field(req, "email", 0),
              field(req, "name", 1), field(req, "metadata", 2)))
          case _ => sys.error("createCustomer(req)")
        },
        "attachMethod" -> fn(2) {
          case List(_, _) =>
            DataV("StoredMethod", Vector(idV("VaultId", next("vault")), StrV("4242"), StrV("visa"),
              StrV("12"), StrV("2030"), StrV("credit"), BoolV(true), none, none))
          case _ => sys.error("attachMethod(customerId, method)")
        },
        "detachMethod" -> fn(1)(_ => UnitV),
        "listMethods" -> fn(1)(_ => listOf(Nil)),
        "subscribe" -> fn(3) {
          case List(customerId, planId, opts) =>
            val trial = field(opts, "trialPeriodDays", 0)
            val status = if opt(trial).isDefined then DataV("Trialing", Vector.empty) else DataV("Active", Vector.empty)
            DataV("Subscription", Vector(idV("SubscriptionId", next("sub")), customerId, planId,
              status, StrV("2026-08-01T00:00:00Z"), BoolV(false),
              opt(trial).map(_ => StrV("2026-07-23T00:00:00Z")).fold(none)(some)))
          case _ => sys.error("subscribe(customerId, planId, opts)")
        },
        "changeSubscription" -> fn(3) {
          case List(subId, planId, _) =>
            DataV("Subscription", Vector(subId, idV("CustomerId", "cus_demo"), planId,
              DataV("Active", Vector.empty), StrV("2026-08-01T00:00:00Z"), BoolV(false), none))
          case _ => sys.error("changeSubscription(id, planId, mode)")
        },
        "cancelSubscription" -> fn(-1) {
          case subId :: _ =>
            DataV("Subscription", Vector(subId, idV("CustomerId", "cus_demo"), idV("PlanId", "plan_demo"),
              DataV("Canceled", Vector.empty), StrV("2026-08-01T00:00:00Z"), BoolV(true), none))
          case _ => sys.error("cancelSubscription(id, atPeriodEnd?)")
        },
        "refund" -> fn(1) {
          case List(req) =>
            DataV("Refund", Vector(idV("RefundId", next("re")), field(req, "intentId", 0),
              opt(field(req, "amount", 1)).getOrElse(moneyV(0L, "USD")), field(req, "reason", 2),
              DataV("Succeeded", Vector.empty)))
          case _ => sys.error("refund(req)")
        },
        "submitDisputeEvidence" -> fn(2) {
          case List(disputeId, evidence) =>
            DataV("Dispute", Vector(disputeId, idV("IntentId", "pi_disputed"), moneyV(0L, "USD"),
              DataV("General", Vector.empty), DataV("UnderReview", Vector.empty),
              StrV("2026-08-01T00:00:00Z"), some(evidence)))
          case _ => sys.error("submitDisputeEvidence(disputeId, evidence)")
        },
        "webhookReceiver" -> methodObject("WebhookReceiver")(
          "handle" -> fn(-1)(_ => UnitV)
        )
      )

    def bankTransfer(id: String, rail: V2Value, amount: V2Value, sender: V2Value,
        recipient: V2Value, ref: String, status: V2Value): V2Value =
      DataV("BankTransfer", Vector(idV("TransferId", id), rail, amount, sender, recipient,
        StrV(ref), status, nowString, if status == DataV("Settled", Vector.empty) then some(nowString) else none,
        none, emptyMap, none, DataV("Nil", Vector.empty), none))

    def bankProvider(providerId: String, rail: V2Value): V2Value =
      methodObject(s"BankRailsProvider($providerId)")(
        "id" -> StrV(providerId),
        "displayName" -> StrV(providerId.capitalize + " deterministic bank-rails provider"),
        "spiVersion" -> StrV("v2-bridge"),
        "supportedRails" -> listOf(List(rail)),
        "initiateTransfer" -> fn(1) {
          case List(req) =>
            val amount = field(req, "amount", 1)
            if providerId == "fednow" && moneyParts(amount)._1 > 50_000_000L then
              throw BridgeThrow(DataV("FedNowLimitExceeded", Vector(amount, moneyV(50_000_000L, "USD"))))
            bankTransfer(
              s"${providerId}_${idString(field(req, "idempotencyKey", 5)).replaceAll("[^A-Za-z0-9]", "").take(32)}",
              field(req, "rail", 0), amount, field(req, "sender", 2), field(req, "recipient", 3),
              str(field(req, "reference", 4)), DataV("Pending", Vector.empty))
          case _ => sys.error("initiateTransfer(req)")
        },
        "getTransfer" -> fn(1) {
          case List(id) =>
            val dummy = DataV("BankAccount", Vector.fill(5)(none) ++ Vector(StrV("Bridge"), StrV(if providerId == "pix" then "BR" else "US")) ++ Vector.fill(13)(none))
            bankTransfer(idString(id), rail, moneyV(0L, if providerId == "pix" then "BRL" else "USD"),
              dummy, dummy, "", DataV("Settled", Vector.empty))
          case _ => sys.error("getTransfer(id)")
        },
        "cancelTransfer" -> fn(1)(_ => UnitV),
        "initiateDirectDebit" -> fn(1) {
          case List(req) =>
            bankTransfer(s"${providerId}_dd_${idString(field(req, "idempotencyKey", 7))}", rail,
              field(req, "amount", 1), field(req, "debtorAccount", 4), field(req, "creditorAccount", 3),
              str(field(req, "reference", 6)), DataV("Pending", Vector.empty))
          case _ => sys.error("initiateDirectDebit(req)")
        },
        "getDirectDebit" -> fn(1) {
          case List(id) =>
            val dummy = DataV("BankAccount", Vector.fill(5)(none) ++ Vector(StrV("Bridge"), StrV("BR")) ++ Vector.fill(13)(none))
            bankTransfer(idString(id), rail, moneyV(0L, "BRL"), dummy, dummy, "", DataV("Settled", Vector.empty))
          case _ => sys.error("getDirectDebit(id)")
        },
        "webhookReceiver" -> methodObject("WebhookReceiver")("handle" -> fn(-1)(_ => UnitV))
      )

    def pixStaticConfig(v: V2Value): PixQrCode.StaticConfig =
      PixQrCode.StaticConfig(
        pixKey = str(field(v, "pixKey", 0)),
        merchantName = str(field(v, "merchantName", 1)),
        merchantCity = str(field(v, "merchantCity", 2)),
        amount = opt(field(v, "amount", 3)).map(payMoney),
        txid = str(field(v, "txid", 4))
      )
    def pixDynamicConfig(v: V2Value): PixQrCode.DynamicConfig =
      PixQrCode.DynamicConfig(
        cobvUrl = str(field(v, "cobvUrl", 0)),
        merchantName = str(field(v, "merchantName", 1)),
        merchantCity = str(field(v, "merchantCity", 2)),
        amount = opt(field(v, "amount", 3)).map(payMoney),
        txid = str(field(v, "txid", 4))
      )

    V2PluginRegistry.registerGlobal("Currency", methodObject("Currency")(
      "apply" -> fn(1) { case List(v) => currencyV(str(v)); case _ => currencyV("USD") },
      "USD" -> currencyV("USD"), "EUR" -> currencyV("EUR"), "GBP" -> currencyV("GBP"),
      "CHF" -> currencyV("CHF"), "AUD" -> currencyV("AUD"), "CAD" -> currencyV("CAD"),
      "JPY" -> currencyV("JPY"), "BRL" -> currencyV("BRL"),
      "minorUnitsPower" -> fn(1) { case List(v) => IntV(PayCurrency.minorUnitsPower(PayCurrency(currencyCode(v))).toLong); case _ => IntV(2) },
      "isFiat" -> fn(1) { case List(v) => BoolV(PayCurrency.isFiat(PayCurrency(currencyCode(v)))); case _ => BoolV(false) },
      "isCrypto" -> fn(1) { case List(v) => BoolV(PayCurrency.isCrypto(PayCurrency(currencyCode(v)))); case _ => BoolV(false) }
    ))
    V2PluginRegistry.registerGlobal("Money", methodObject("Money")(
      "apply" -> fn(-1) {
        case List(IntV(minor), c) => moneyV(minor, currencyCode(c))
        case List(amount, c) =>
          val code = currencyCode(c)
          val m = PayMoney(decimal(amount), PayCurrency(code))
          moneyV(m.minorUnits, code)
        case _ => moneyV(0L, "USD")
      },
      "zero" -> fn(1) { case List(c) => moneyV(0L, currencyCode(c)); case _ => moneyV(0L, "USD") },
      "allocate" -> fn(2) {
        case List(total, ratios) =>
          val (minor, code) = moneyParts(total)
          val allocated = PayMoney.allocate(PayMoney(minor, PayCurrency(code)), unlist(ratios).map(decimal))
          listOf(allocated.map(m => moneyV(m.minorUnits, code)))
        case _ => listOf(Nil)
      }
    ))
    V2PluginRegistry.registerGlobal("PaymentProvider", methodObject("PaymentProvider")(
      "named" -> fn(1) { case List(StrV(id)) => paymentProvider(id); case _ => paymentProvider("stripe") }
    ))
    V2PluginRegistry.registerGlobal("StripeProvider", fn(-1)(_ => paymentProvider("stripe")))
    V2PluginRegistry.registerGlobal("PixProvider", fn(1)(_ => bankProvider("pix", DataV("PIX", Vector.empty))))
    V2PluginRegistry.registerGlobal("FedNowProvider", fn(1)(_ => bankProvider("fednow", DataV("FEDNOW", Vector.empty))))
    V2PluginRegistry.registerGlobal("FedNowConfig", methodObject("FedNowConfig")(
      "fromEnv" -> DataV("FedNowConfig", Vector(StrV("https://fednow.example.test"),
        StrV(""), StrV(""), StrV("021000021"), StrV("DEMO"))),
      "apply" -> fn(-1) { args => DataV("FedNowConfig", args.padTo(5, StrV("")).take(5).toVector) }
    ))
    V2PluginRegistry.registerGlobal("PixQrCode", methodObject("PixQrCode")(
      "StaticConfig" -> fn(-1) { args => DataV("StaticConfig", args.padTo(5, none).toVector) },
      "DynamicConfig" -> fn(-1) { args => DataV("DynamicConfig", args.padTo(5, none).toVector) },
      "buildStatic" -> fn(1) { case List(cfg) => StrV(PixQrCode.buildStatic(pixStaticConfig(cfg))); case _ => StrV("") },
      "buildDynamic" -> fn(1) { case List(cfg) => StrV(PixQrCode.buildDynamic(pixDynamicConfig(cfg))); case _ => StrV("") },
      "formatAmount" -> fn(1) { case List(m) => StrV(PixQrCode.formatAmount(payMoney(m))); case _ => StrV("0.00") }
    ))
    V2PluginRegistry.registerGlobal("Instant", methodObject("Instant")(
      "now" -> fn(0)(_ => instantV(java.time.Instant.now()))
    ))
    V2PluginRegistry.registerGlobal("Thread", methodObject("Thread")(
      "sleep" -> fn(1) { case List(IntV(ms)) => Thread.sleep(ms); UnitV; case _ => UnitV }
    ))
    V2PluginRegistry.register("__method__.toDecimal", args => args.lift(1) match
      case Some(m @ DataV("Money", _)) =>
        val (minor, code) = moneyParts(m)
        ForeignV(BigDecimal(minor).bigDecimal.scaleByPowerOfTen(-PayCurrency.minorUnitsPower(PayCurrency(code))))
      case _ => DataV("Stub", Vector.empty)
    )
    V2PluginRegistry.register("__method__.format", args => args.lift(1) match
      case Some(m @ DataV("Money", _)) =>
        val (minor, code) = moneyParts(m)
        StrV(PayMoney(minor, PayCurrency(code)).format(java.util.Locale.US))
      case _ => DataV("Stub", Vector.empty)
    )
    V2PluginRegistry.register("__method__.getMessage", args => args.lift(1) match
      case Some(DataV("FedNowLimitExceeded", fs)) if fs.length >= 2 =>
        StrV(s"FedNow transfer amount ${Show.show(fs(0))} exceeds limit ${Show.show(fs(1))}")
      case Some(DataV("CurrencyMismatch", _)) => StrV("Cannot operate on amounts in different currencies")
      case _ => DataV("Stub", Vector.empty)
    )

  /** Override oauth.client.discoverAs/exchangeAuthorizationCode BEFORE buildNamespaceObjects()
   *  so the namespace ForeignV picks up the batch-mode stubs.
   *
   *  discoverAs: instead of making a real HTTP call to localhost, return the discovery
   *  document directly from the in-memory OidcServer registered by oidc.server(as).
   *
   *  exchangeAuthorizationCode: return stub token strings; idp.userInfo() is patched
   *  in OidcHelpers.scala to return the first registered user for any token. */
  private def overrideOidcClientStubs(): Unit =
    import V2Value.*
    import scalascript.compiler.plugin.oauth.{OidcIntrinsicHelpers, OAuthIntrinsicHelpers}

    // ujson.Value → v2 Value (Map → ForeignV(Map[StrV→v2]), Str→StrV, Num→FloatV, Bool→BoolV)
    def ujsonToV2(v: ujson.Value): V2Value = v match
      case ujson.Str(s)  => StrV(s)
      case ujson.Num(d)  => if d == d.toLong then IntV(d.toLong) else FloatV(d)
      case ujson.Bool(b) => BoolV(b)
      case ujson.Null    => DataV("None", Vector.empty)
      case ujson.Arr(xs) => xs.foldRight(DataV("Nil", Vector.empty): V2Value)((x, acc) =>
        DataV("Cons", Vector(ujsonToV2(x), acc)))
      case ujson.Obj(kvs) =>
        val hm = collection.mutable.LinkedHashMap.empty[V2Value, V2Value]
        kvs.foreach { case (k, v) => hm(StrV(k)) = ujsonToV2(v) }
        ForeignV(hm.asInstanceOf[AnyRef])

    V2PluginRegistry.registerGlobal("oauth.client.discoverAs",
      ClosV(Runtime.emptyEnv, -1, env => Done({
        val issuer = env.headOption.collect { case StrV(s) => s }.getOrElse("")
        OidcIntrinsicHelpers.findByIssuer(issuer) match
          case Some(idp) => ujsonToV2(idp.discoveryJson())
          case None =>
            // Minimal discovery doc for unknown localhost issuers
            val hm = collection.mutable.LinkedHashMap.empty[V2Value, V2Value]
            hm(StrV("issuer"))                = StrV(issuer)
            hm(StrV("authorization_endpoint"))= StrV(s"$issuer/authorize")
            hm(StrV("token_endpoint"))        = StrV(s"$issuer/token")
            hm(StrV("userinfo_endpoint"))     = StrV(s"$issuer/userinfo")
            hm(StrV("jwks_uri"))              = StrV(s"$issuer/.well-known/jwks.json")
            ForeignV(hm.asInstanceOf[AnyRef])
      })))

    V2PluginRegistry.registerGlobal("oauth.client.exchangeAuthorizationCode",
      ClosV(Runtime.emptyEnv, -1, _ => Done({
        val hm = collection.mutable.LinkedHashMap.empty[V2Value, V2Value]
        hm(StrV("accessToken"))  = StrV("batch-stub-access-token")
        hm(StrV("idToken"))      = StrV("batch-stub-id-token")
        hm(StrV("refreshToken")) = StrV("batch-stub-refresh-token")
        hm(StrV("tokenType"))    = StrV("Bearer")
        hm(StrV("expiresIn"))    = IntV(3600L)
        hm(StrV("scope"))        = StrV("openid profile email offline_access")
        ForeignV(hm.asInstanceOf[AnyRef])
      })))

  /** Batch-runner stubs for features that need a server / document context.
   *  These override plugin registrations so examples run without errors. */
  /** Filesystem builtins the v1 interpreter exposes via `BuiltinsRuntime` (not as ServiceLoader
   *  `NativeImpl` intrinsics), so the loadAll loop skips them and they surface as `unbound global` on v2
   *  (e.g. `mkdirs` in examples/fs-roundtrip.ssc). Register the gaps here — the `isEmpty` guard leaves any
   *  already-bound name (ServiceLoader / earlier registration) untouched. Bodies mirror BuiltinsRuntime;
   *  args arrive as raw values via `v2ToRaw`, results go back through `rawToV2`. */
  private def registerFsBuiltins(): Unit =
    import java.nio.file.{Files, Paths, StandardOpenOption}
    import java.nio.charset.StandardCharsets.UTF_8
    def reg(name: String, arity: Int)(fn: PartialFunction[List[Any], Any]): Unit =
      if V2PluginRegistry.lookupGlobal(name).isEmpty then
        V2PluginRegistry.registerGlobal(name, V2Value.ClosV(Runtime.emptyEnv, arity, env =>
          Done(rawToV2(fn.applyOrElse(env.toList.map(v2ToRaw),
            (_: List[Any]) => throw new RuntimeException(s"$name: bad arguments"))))))
    reg("mkdirs", 1)     { case List(p: String) => Files.createDirectories(Paths.get(p)); () }
    reg("mkdir", 1)      { case List(p: String) => val pp = Paths.get(p); if !Files.exists(pp) then Files.createDirectory(pp); () }
    reg("writeFile", 2)  { case List(p: String, c: String) => Files.write(Paths.get(p), c.getBytes(UTF_8)); () }
    reg("appendFile", 2) { case List(p: String, c: String) =>
      Files.write(Paths.get(p), c.getBytes(UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND); () }
    reg("readFile", 1)   { case List(p: String) => new String(Files.readAllBytes(Paths.get(p)), UTF_8) }
    reg("deleteFile", 1) { case List(p: String) => Files.deleteIfExists(Paths.get(p)); () }
    reg("exists", 1)     { case List(p: String) => Files.exists(Paths.get(p)) }
    reg("listDir", 1)    { case List(p: String) =>
      val f = new java.io.File(p)
      if f.isDirectory then f.listFiles().map(_.getName).toList.sorted else Nil }

  // ── Dataset natives (v1 DatasetRuntime mirror — p3-dataset-natives) ────────
  // v1 implements Dataset as interpreter-CORE intrinsics (DatasetRuntime.scala),
  // which the plugin ServiceLoader loop never sees — so on v2 `Dataset.fromFile`
  // leaked as a Free Op straight into list prims. A dataset here is a METHOD
  // OBJECT (ForeignV(immutable.Map[String, ClosV])) over a lazily-captured List —
  // the __method__ method-object arm dispatches `.map/.collect/...`; the
  // `Dataset.of/fromList/fromFile` factories arrive via the empty-fields DataV
  // plugin lookup ("Dataset.<name>").
  private def registerDatasetNatives(): Unit =
    import V2Value.*
    def listOf(xs: Seq[V2Value]): V2Value =
      xs.foldRight[V2Value](DataV("Nil", Vector.empty))((x, acc) => DataV("Cons", Vector(x, acc)))
    def clos(arity: Int)(f: List[V2Value] => V2Value): V2Value =
      ClosV(Runtime.emptyEnv, arity, env => Done(f(env.toList)))
    // Prims.anyStr is private — same rendering (unquoted strings, integral Doubles).
    def str(v: V2Value): String = v match
      case StrV(s)   => s
      case IntV(n)   => n.toString
      case BoolV(b)  => b.toString
      case FloatV(d) => ssc.Writer.floatStr(d)
      case _         => Show.show(v)
    def cmp(a: V2Value, b: V2Value): Int = (a, b) match
      case (IntV(x),   IntV(y))   => x.compareTo(y)
      case (FloatV(x), FloatV(y)) => x.compareTo(y)
      case (IntV(x),   FloatV(y)) => x.toDouble.compareTo(y)
      case (FloatV(x), IntV(y))   => x.compareTo(y.toDouble)
      case (StrV(x),   StrV(y))   => x.compareTo(y)
      case (BoolV(x),  BoolV(y))  => x.compareTo(y)
      case _                      => Show.show(a).compareTo(Show.show(b))
    def call1(f: V2Value, x: V2Value): V2Value = callClosure(f, List(x))
    def isTrue(v: V2Value): Boolean = v == BoolV(true)
    def otherItems(other: V2Value): List[V2Value] = other match
      case ForeignV(m: collection.immutable.Map[?, ?]) =>
        m.asInstanceOf[collection.immutable.Map[String, V2Value]].get("collect") match
          case Some(c: ClosV) => ssc.Prims.unlistPub(callClosure(c, Nil))
          case _ => Nil
      case l @ DataV("Cons" | "Nil", _) => ssc.Prims.unlistPub(l)
      case _ => Nil
    def mkDataset(run: () => List[V2Value]): V2Value =
      lazy val self: V2Value = ForeignV(collection.immutable.Map[String, V2Value](
        "map"      -> clos(1) { case List(f) => mkDataset(() => run().map(call1(f, _))) },
        "filter"   -> clos(1) { case List(f) => mkDataset(() => run().filter(x => isTrue(call1(f, x)))) },
        "flatMap"  -> clos(1) { case List(f) => mkDataset(() => run().flatMap(x => ssc.Prims.unlistPub(call1(f, x)))) },
        "take"     -> clos(1) { case List(IntV(n)) => mkDataset(() => run().take(n.toInt)) },
        "drop"     -> clos(1) { case List(IntV(n)) => mkDataset(() => run().drop(n.toInt)) },
        "distinct" -> clos(0) { _ => mkDataset(() => run().distinct) },
        "zipWithIndex" -> clos(0) { _ => mkDataset(() =>
          run().zipWithIndex.map((v, i) => DataV("Tuple2", Vector(v, IntV(i.toLong))))) },
        "groupBy"  -> clos(1) { case List(kf) => mkDataset(() =>
          run().groupBy(call1(kf, _)).toList.map((k, vs) => DataV("Tuple2", Vector(k, listOf(vs))))) },
        "sortBy"   -> clos(1) { case List(kf) => mkDataset(() =>
          run().map(x => x -> call1(kf, x)).sortWith((p, q) => cmp(p._2, q._2) < 0).map(_._1)) },
        "reduceByKey" -> clos(1) { case List(kf) => clos(1) { case List(cf) => mkDataset(() =>
          run().groupBy(call1(kf, _)).toList.map((k, vs) =>
            DataV("Tuple2", Vector(k, vs.reduce((a, b) => callClosure(cf, List(a, b))))))) } },
        "top"         -> clos(1) { case List(IntV(n)) => listOf(run().sortWith(cmp(_, _) > 0).take(n.toInt)) },
        "takeOrdered" -> clos(1) { case List(IntV(n)) => listOf(run().sortWith(cmp(_, _) < 0).take(n.toInt)) },
        "countByValue" -> clos(0) { _ =>
          val m = collection.mutable.HashMap.empty[V2Value, V2Value]
          run().groupBy(identity).foreach((k, vs) => m(k) = IntV(vs.length.toLong)); ForeignV(m) },
        "partition" -> clos(1) { case List(f) =>
          val (yes, no) = run().partition(x => isTrue(call1(f, x)))
          DataV("Tuple2", Vector(listOf(yes), listOf(no))) },
        "mkString" -> ClosV(Runtime.emptyEnv, -1, env => Done(StrV(env.toList match
          case Nil                                  => run().map(str).mkString
          case List(StrV(sep))                      => run().map(str).mkString(sep)
          case List(StrV(a), StrV(sep), StrV(b))    => run().map(str).mkString(a, sep, b)
          case _ => sys.error("Dataset.mkString[(sep)|(start, sep, end)]")))),
        "toMap" -> clos(0) { _ =>
          val m = collection.mutable.HashMap.empty[V2Value, V2Value]
          run().foreach {
            case DataV("Tuple2", IndexedSeq(k, v)) => m(k) = v
            case other => sys.error(s"Dataset.toMap: element is not a 2-tuple: ${Show.show(other)}")
          }; ForeignV(m) },
        "toSet"       -> clos(0) { _ => listOf(run().distinct) },
        "saveToFile"  -> clos(1) { case List(StrV(path)) =>
          java.nio.file.Files.write(java.nio.file.Paths.get(path),
            run().map(str).mkString("", "\n", "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
          UnitV },
        // Set-like binary ops: the other dataset is our own method-object —
        // pull its items through its "collect" closure.
        "union"     -> clos(1) { case List(other) => mkDataset(() => run() ++ otherItems(other)) },
        "intersect" -> clos(1) { case List(other) => mkDataset(() => run().intersect(otherItems(other))) },
        "zip"       -> clos(1) { case List(other) => mkDataset(() =>
          run().zip(otherItems(other)).map((a, b) => DataV("Tuple2", Vector(a, b)))) },
        "runLocal"    -> clos(0) { _ => mkDataset(run) },
        "runParallel" -> clos(0) { _ => mkDataset(run) },
        "collect"     -> clos(0) { _ => listOf(run()) },
        "count"       -> clos(0) { _ => IntV(run().length.toLong) },
        "sum"         -> clos(0) { _ => run().foldLeft[V2Value](IntV(0))((a, v) => ssc.Prims.arithOp("+", a, v)) },
        "min"         -> clos(0) { _ => val xs = run(); if xs.isEmpty then sys.error("Dataset.min: empty dataset") else xs.reduce((a, b) => if cmp(a, b) <= 0 then a else b) },
        "max"         -> clos(0) { _ => val xs = run(); if xs.isEmpty then sys.error("Dataset.max: empty dataset") else xs.reduce((a, b) => if cmp(a, b) >= 0 then a else b) },
        "avg"         -> clos(0) { _ =>
          val xs = run()
          if xs.isEmpty then sys.error("Dataset.avg: empty dataset")
          else ssc.Prims.arithOp("/",
            xs.foldLeft[V2Value](FloatV(0.0))((a, v) => ssc.Prims.arithOp("+", a, v)),
            FloatV(xs.length.toDouble)) },
        "reduce" -> clos(1) { case List(cf) =>
          val xs = run(); if xs.isEmpty then sys.error("Dataset.reduce: empty dataset")
          else xs.reduce((a, b) => callClosure(cf, List(a, b))) },
        "fold" -> clos(1) { case List(z) => clos(1) { case List(cf) =>
          run().foldLeft(z)((a, x) => callClosure(cf, List(a, x))) } },
        "foreach" -> clos(1) { case List(f) => run().foreach(call1(f, _)); UnitV },
        "first"   -> clos(0) { _ => run().headOption
          .map(v => DataV("Some", Vector(v))).getOrElse(DataV("None", Vector.empty)) },
      ).asInstanceOf[AnyRef])
      self
    def dsOf(items: List[V2Value]): V2Value = mkDataset(() => items)
    // FALLBACK keys: the spark plugin provides its OWN Dataset surface for the
    // spark/dataset example families — plain "Dataset.of" registration here
    // SHADOWED it (spark-* examples then hit this method-object and died on
    // .toDF/.write). The runtime consults "__fallback__.<Tag>.<m>" only after
    // the plugin lookup AND effect context both miss — exactly the case that
    // previously leaked a Free Op into list prims (distributed-*).
    V2PluginRegistry.register("__fallback__.Dataset.of",       args => dsOf(args))
    V2PluginRegistry.register("__fallback__.Dataset.fromList", {
      case List(l) => dsOf(ssc.Prims.unlistPub(l))
      case args    => sys.error("Dataset.fromList(list: List[T]): Dataset[T]") })
    V2PluginRegistry.register("__fallback__.Dataset.fromFile", {
      case List(StrV(path)) =>
        val src = scala.io.Source.fromFile(path)
        val lines = try src.getLines().toList.map(StrV(_): V2Value) finally src.close()
        dsOf(lines)
      case _ => sys.error("Dataset.fromFile(path: String): Dataset[String]") })

  /** BATCH-ONLY overrides: no web server in the corpus runner. Called by
   *  BatchCli after loadAll(); `ssc run --v2` keeps the real plugin natives. */
  def installBatchStubs(): Unit =
    import V2Value.*
    V2PluginRegistry.registerGlobal("serve",  ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))
    V2PluginRegistry.registerGlobal("emit",   ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))
    V2PluginRegistry.registerGlobal("mount",  ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))
    registerBatchRunnerStubs()

  private def registerBatchRunnerStubs(): Unit =
    import V2Value.*
    val stub = DataV("Stub", Vector.empty)
    val dividerNode = DataV("DividerNode", Vector.empty)
    def noopClos = ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV))
    def stubClos = ClosV(Runtime.emptyEnv, -1, _ => Done(stub))

    // contentToolkitSection still needs a v2 lowering pass for full toolkit parity.
    // Keep the historical batch stub only for section-level toolkit rendering while
    // letting content document/module/markdown and contentToolkitBlock use the real
    // content plugin implementations.
    val toolkitSectionStub = ClosV(Runtime.emptyEnv, -1, _ => Done(dividerNode))
    V2PluginRegistry.registerGlobal("contentToolkitSection", toolkitSectionStub)
    V2PluginRegistry.register("contentToolkitSection", _ => dividerNode)

    // ── Sync stub (browser IndexedDB sync helper) ─────────────────────────────────
    val syncStatusStub = DataV("SyncStatus", Vector(IntV(0), IntV(0), BoolV(false)))
    val syncMethods: collection.immutable.Map[String, V2Value] = collection.immutable.Map(
      "put"      -> noopClos,
      "status"   -> ClosV(Runtime.emptyEnv, -1, _ => Done(syncStatusStub)),
      "pending"  -> ClosV(Runtime.emptyEnv, -1, _ => Done(DataV("Nil", Vector.empty))),
      "isOnline" -> BoolV(false),
      "sync"     -> noopClos,
    )
    V2PluginRegistry.registerGlobal("Sync", ForeignV(syncMethods.asInstanceOf[AnyRef]))
    // awaitClient(expr) — run expr and return UnitV
    V2PluginRegistry.registerGlobal("awaitClient", ClosV(Runtime.emptyEnv, 1, env => Done(env(0))))

    // ── Duration extension methods on Int/Long (e.g. 30.seconds) ────────────────
    // Returns stub — callers like Await.result ignore the actual duration value.
    for durMethod <- Seq("seconds", "minutes", "milliseconds", "millis", "nanoseconds", "nanos", "hours", "days") do
      V2PluginRegistry.register(s"__method__.$durMethod", args => stub)

    // ── Await stub (Await.result ignores timeout, returns stub) ──────────────────
    if V2PluginRegistry.lookupGlobal("Await").isEmpty then
      val awaitMethods: collection.immutable.Map[String, V2Value] = collection.immutable.Map(
        "result" -> ClosV(Runtime.emptyEnv, -1, env => Done(if env.nonEmpty then env(0) else stub)),
        "ready"  -> ClosV(Runtime.emptyEnv, -1, env => Done(if env.nonEmpty then env(0) else stub)),
      )
      V2PluginRegistry.registerGlobal("Await", ForeignV(awaitMethods.asInstanceOf[AnyRef]))

    // ── spark stub (SparkSession skeleton) ────────────────────────────────────────
    // Any method on spark returns DataV("Stub", []) which propagates through Op stub dispatch.
    if V2PluginRegistry.lookupGlobal("spark").isEmpty then
      V2PluginRegistry.registerGlobal("spark", stub)
    // StructType stub for Spark streaming schemas
    if V2PluginRegistry.lookupGlobal("StructType").isEmpty then
      V2PluginRegistry.registerGlobal("StructType", stubClos)
    if V2PluginRegistry.lookupGlobal("IntegerType").isEmpty then
      V2PluginRegistry.registerGlobal("IntegerType", stub)
    if V2PluginRegistry.lookupGlobal("StringType").isEmpty then
      V2PluginRegistry.registerGlobal("StringType", stub)
    if V2PluginRegistry.lookupGlobal("LongType").isEmpty then
      V2PluginRegistry.registerGlobal("LongType", stub)
    if V2PluginRegistry.lookupGlobal("DoubleType").isEmpty then
      V2PluginRegistry.registerGlobal("DoubleType", stub)

    // ── HTTP server backend selection — no-op in batch runner ────────────────────
    // The batch runner never starts a real server; setHttpServerBackend("jetty") would
    // throw "No HttpServerSpi impl named 'jetty'". Force-override the plugin's registration.
    V2PluginRegistry.register("setHttpServerBackend", _ => V2Value.UnitV)
    V2PluginRegistry.registerGlobal("setHttpServerBackend", ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))

    // ── In-process HTTP dispatch for self-contained examples ─────────────────────
    // Examples like rozum-agent-* start a local mock server via serveAsync(port)
    // and then connect to it via runAgent(AgentEndpoint("http://localhost:PORT")).
    // Since serveAsync is a no-op stub, the real httpPost/httpPostStream would fail
    // with ConnectionRefused. We intercept localhost calls and return a stub OpenAI
    // response so runAgent exits immediately with finish_reason="stop".
    val origHttpPost = V2PluginRegistry.lookup("httpPost")
    val fakeStopBody = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Batch-mode stub response."}}]}"""
    val fakeStopResp = fakeHttpResponse(200, fakeStopBody, "application/json")
    V2PluginRegistry.register("httpPost", args => {
      val url = args.headOption.collect { case StrV(s) => s }.getOrElse("")
      if isLocalhostUrl(url) then fakeStopResp
      else origHttpPost.map(_(args)).getOrElse(stub)
    })
    V2PluginRegistry.registerGlobal("httpPost", ClosV(Runtime.emptyEnv, -1, env => Done(
      V2PluginRegistry.lookup("httpPost").map(_(env.toList)).getOrElse(stub)
    )))
    // httpPostStream: SSE streaming variant — curried: httpPostStream(url,body,hdrs){ line => }
    val origHttpPostStream = V2PluginRegistry.lookup("httpPostStream")
    val fakeStopResp200 = fakeHttpResponse(200, "done", "text/event-stream")
    V2PluginRegistry.register("httpPostStream", args => {
      val url = args.headOption.collect { case StrV(s) => s }.getOrElse("")
      if isLocalhostUrl(url) then {
        // Return a curried handler: when called with the SSE line callback, call it with
        // a single stop event line and then return the response.
        val stopLine = """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        ClosV(Runtime.emptyEnv, 1, handlerEnv => {
          val handler = handlerEnv(0)
          // Call the SSE handler closure with the stop event line
          handler match
            case c: ClosV =>
              val callArgs = Array[V2Value](StrV(s"data: $stopLine"))
              val callEnv  = if c.env.isEmpty then callArgs else c.env ++ callArgs
              scala.util.Try(Runtime.run(c.code, callEnv))  // ignore result/errors
            case _ => ()
          Done(fakeStopResp200)
        })
      } else origHttpPostStream.map(_(args)).getOrElse(stub)
    })
    V2PluginRegistry.registerGlobal("httpPostStream", ClosV(Runtime.emptyEnv, -1, env => Done(
      V2PluginRegistry.lookup("httpPostStream").map(_(env.toList)).getOrElse(stub)
    )))

    // ── httpGet localhost interceptor + http namespace (for oidc-login-flow.ssc) ─────────────
    // oidc-login-flow.ssc calls http.get(authorizationUrl, Map("followRedirects" -> false))
    // which would connect to localhost:8080 (not running in batch mode). We intercept
    // httpGet for localhost URLs and return a fake 302 redirect with the state echoed back.
    // We also register the `http` namespace so http.get/http.parseUrl resolve correctly.
    val origHttpGet = V2PluginRegistry.lookup("httpGet")
    V2PluginRegistry.register("httpGet", args => {
      val url = args.headOption.collect { case StrV(s) => s }.getOrElse("")
      if isLocalhostUrl(url) then makeLocalhostGetResp(url)
      else origHttpGet.map(_(args)).getOrElse(stub)
    })
    V2PluginRegistry.registerGlobal("httpGet", ClosV(Runtime.emptyEnv, -1, env => Done(
      V2PluginRegistry.lookup("httpGet").map(_(env.toList)).getOrElse(stub)
    )))

    // `http` namespace: http.get = httpGet wrapper; http.parseUrl = URL query parser
    val httpGetClos = ClosV(Runtime.emptyEnv, -1, env => Done(
      V2PluginRegistry.lookup("httpGet").map(_(env.toList)).getOrElse(stub)
    ))
    if V2PluginRegistry.lookupGlobal("http").isEmpty then
      V2PluginRegistry.registerGlobal("http", ForeignV(collection.immutable.Map[String, V2Value](
        "get"      -> httpGetClos,
        "parseUrl" -> ClosV(Runtime.emptyEnv, 1, env => Done(makeUrlParserV2(env(0))))
      ).asInstanceOf[AnyRef]))

    // ── mcpConnect stub — fake MCP client so MCP examples don't error with "client closed" ─────
    // mcp-client-discover.ssc and agent-mcp-toolsource.ssc spawn an external node process via
    // Transport.Spawn. In batch mode, stub mcpConnect to return a no-tools client.
    val emptyMcpList: V2Value = DataV("Nil", IndexedSeq.empty)
    val fakeMcpClient = ForeignV(new ssc.Value.NamedMethodObj {
      def getField(n: String): Option[ssc.Value] = n match
        case "listTools"     => Some(ClosV(Runtime.emptyEnv, 0, _ => Done(emptyMcpList)))
        case "listResources" => Some(ClosV(Runtime.emptyEnv, 0, _ => Done(emptyMcpList)))
        case "listPrompts"   => Some(ClosV(Runtime.emptyEnv, 0, _ => Done(emptyMcpList)))
        case "close"         => Some(ClosV(Runtime.emptyEnv, 0, _ => Done(UnitV)))
        case "callTool"      => Some(ClosV(Runtime.emptyEnv, 2, _ => Done(
          DataV("McpToolResult", IndexedSeq(BoolV(false), emptyMcpList)))))
        case _ => None
      def underlying: AnyRef = this
      override def toString = "McpClient(batch-stub)"
    })
    V2PluginRegistry.register("mcpConnect", _ => fakeMcpClient)
    V2PluginRegistry.registerGlobal("mcpConnect", ClosV(Runtime.emptyEnv, -1, _ => Done(fakeMcpClient)))

    // Register postChatCompletions as a global so the bridge skips compiling the ssc def
    // (bridge line 442: skip defs already registered as globals). This bypasses the field-
    // index mismatch: fieldIndex("body")=3 (from Request, registered first due to DFS import
    // order) causes runAgentLoop to read errorText instead of body, triggering mkString on ().
    // We put fakeBody at index 3 (what fieldIndex("body") resolves to) and also at index 1
    // (real AgentHttpAttempt.body).
    val fakeAgentBody = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Batch-mode stub response."}}]}"""
    V2PluginRegistry.registerGlobal("postChatCompletions", ClosV(Runtime.emptyEnv, -1, _ => Done(
      DataV("AgentHttpAttempt", Vector(
        IntV(200),               // 0: status
        StrV(fakeAgentBody),     // 1: body (real index in AgentHttpAttempt)
        BoolV(false),            // 2: transportError
        StrV(fakeAgentBody)      // 3: body (fieldIndex("body")=3 from Request, registered first)
      ))
    )))

    // Register postChatCompletionsStream as a global stub for the streaming variant.
    // fieldIndex("transportError")=2 (from AgentHttpAttempt, registered before AgentStreamAttempt),
    // but in AgentStreamAttempt transportError is at real index 1.
    // fieldIndex("events")=1 (from AgentStreamResult), but in AgentStreamAttempt events is at index 7.
    // We lay out the DataV to satisfy the WRONG but consistent fieldIndex accesses:
    //   index 0: status (IntV 200)
    //   index 1: events=Nil (fieldIndex("events")=1 from AgentStreamResult)
    //   index 2: transportError=false (fieldIndex("transportError")=2 from AgentHttpAttempt)
    //   index 3: errorText="" (fieldIndex("errorText")=3 from AgentHttpAttempt)
    //   index 4: toolCalls=Nil (fieldIndex("toolCalls")=4 from AgentStreamAttempt)
    //   index 5: finishReason="stop" (fieldIndex("finishReason")=5 from AgentStreamAttempt)
    //   index 6: streamError="" (fieldIndex("streamError")=6 from AgentStreamAttempt)
    //   index 7: events=Nil (real index in AgentStreamAttempt, not used via fieldIndex)
    // postChatCompletionsStream stub: take the early transportError branch so we avoid
    // `assistantTextMessage(attempt.text)` where attempt.text would be the status Int.
    // fieldIndex("events")=1, fieldIndex("transportError")=2, fieldIndex("errorText")=3.
    val emptyNil = DataV("Nil", Vector.empty)
    V2PluginRegistry.registerGlobal("postChatCompletionsStream", ClosV(Runtime.emptyEnv, -1, _ => Done(
      DataV("AgentStreamAttempt", Vector(
        IntV(0),             // 0: (fieldIndex("text")=0 from AgentResult — kept as Int to signal error)
        emptyNil,            // 1: events (fieldIndex("events")=1 from AgentStreamResult)
        BoolV(true),         // 2: transportError=true (fieldIndex("transportError")=2) → takes error branch
        StrV(""),            // 3: errorText="" (fieldIndex("errorText")=3)
        emptyNil,            // 4: toolCalls
        StrV("stop"),        // 5: finishReason
        StrV(""),            // 6: streamError
        emptyNil             // 7: real events index in AgentStreamAttempt (unused via fieldIndex)
      ))
    )))

  /** For each registered global of the form "a.b[.c...]", build nested namespace ForeignV(Map)
   *  objects so `oauth.authServer(...)` and `oauth.client.discoverAs(...)` work via __method__. */
  private def buildNamespaceObjects(): Unit =
    import V2Value.*
    // Collect all dotted names and group by their top-level prefix
    type NestedMap = scala.collection.mutable.Map[String, Any] // String→V2Value or String→NestedMap
    def treeOf(ns: NestedMap, parts: List[String], leaf: V2Value): Unit = parts match
      case Nil         => ()
      case key :: Nil  => ns(key) = leaf
      case key :: rest =>
        val child = ns.getOrElseUpdate(key, scala.collection.mutable.Map.empty[String, Any])
          .asInstanceOf[NestedMap]
        ns(key) = child
        treeOf(child, rest, leaf)

    val tree = scala.collection.mutable.Map.empty[String, NestedMap]
    V2PluginRegistry.allGlobalNames().foreach { name =>
      val parts = name.split("\\.").toList
      if parts.length >= 2 && parts.head.nonEmpty && !Character.isUpperCase(parts.head.charAt(0)) then
        val ns = tree.getOrElseUpdate(parts.head, scala.collection.mutable.Map.empty)
        V2PluginRegistry.lookupGlobal(name).foreach { fn => treeOf(ns, parts.tail, fn) }
    }

    def toForeignV(m: scala.collection.mutable.Map[String, Any]): V2Value =
      val im: scala.collection.immutable.Map[String, V2Value] =
        scala.collection.immutable.Map.from(m.map { case (k, v) =>
          k -> (v match
            case nested: scala.collection.mutable.Map[?, ?] =>
              toForeignV(nested.asInstanceOf[NestedMap])
            case v2: V2Value => v2
          )
        })
      ForeignV(im.asInstanceOf[AnyRef])

    tree.foreach { case (ns, methods) =>
      if V2PluginRegistry.lookupGlobal(ns).isEmpty then
        V2PluginRegistry.registerGlobal(ns, toForeignV(methods))
    }

  /** Load a specific Backend (e.g., for testing). */
  def loadBackend(backend: Backend): Int =
    registerHandle()
    var count = 0
    backend.intrinsics.foreach { case (qn, impl) =>
      val op = qn.toString
      impl match
        case NativeImpl(eval) =>
          val nativeFn: List[V2Value] => V2Value = args => {
            val rawArgs: List[Any] = args.map(v2ToRaw)
            rawToV2(eval(MinimalCtx, rawArgs))
          }
          V2PluginRegistry.register(op, args => nativeFn(args))
          V2PluginRegistry.registerGlobal(op, V2Value.ClosV(Runtime.emptyEnv, -1, env => Done(nativeFn(env.toList))))
          count += 1
        case _ => // compile-time variants; not bridgeable
    }
    backend.blockForms.foreach { case (runnerName, bf) =>
      registerBlockForm(runnerName, bf)
      count += 1
    }
    count

  // ── BlockForm registration ──────────────────────────────────────────────────

  /** Register a BlockForm runner (e.g. runLogger, runState(s0)) as a v2 global.
   *
   *  Block-form call shapes handled:
   *   - 1-arg:  runLogger { body }   → Global("runLogger")(thunk)
   *   - 2-arg:  runState(s0) { body } → Global("runState")(s0)(thunk)
   *
   *  We register a curried 1-arg v2 ClosV that collects config args until it receives
   *  a thunk (arity-0 closure), then executes. Simple heuristic: the body thunk is
   *  always arity-0 (Lam(0, body)).  For multi-arg runners we curry eagerly. */
  private def registerBlockForm(name: String, bf: BlockForm): Unit =
    // Build a Scala-native v2 ClosV that acts as the runner global.
    // We pre-build a stable ClosV and capture it by reference.
    val runnerClosure = makeRunnerClosure(bf, accArgs = Nil)
    V2PluginRegistry.registerGlobal(name, runnerClosure)

  /** Recursively build a curried closure that collects config args until it sees
   *  a thunk (arity-0 ClosV), then executes the block-form. */
  private def makeRunnerClosure(bf: BlockForm, accArgs: List[V2Value]): V2Value =
    V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val arg = env.last // arity-1 called with [arg]; Local(0) = env.last
      arg match
        case thunk: V2Value.ClosV if thunk.arity == 0 =>
          // Got the body thunk — run the block form
          val cfgSpi: List[SpiValue] = accArgs.reverse.map(v2ToSpi)
          val bctx = makeBCtx()
          val handler = bf.newHandler(bctx, cfgSpi)
          val effectTag = bf.effectName
          val v2Handler: V2EffectContext.EH = (op, args) =>
            val spiArgs = args.map(v2ToSpi)
            val spiResult = handler.reply(op, spiArgs)
            spiToV2(spiResult)
          V2EffectContext.push(effectTag, v2Handler)
          val bodyResult = try
            callThunk(thunk)
          finally
            V2EffectContext.pop(effectTag)
          val spiBody = v2ToSpi(bodyResult)
          Done(spiToV2(bf.result(spiBody, handler)))
        case _ =>
          // Config arg — accumulate and return next curried closure
          Done(makeRunnerClosure(bf, arg :: accArgs))
    })

  /** Build a BlockContext for v2 use. applyFn calls v2 closure via Runtime.run. */
  private def makeBCtx(): BlockContext = new BlockContext:
    def out: java.io.PrintStream = Console.out
    override def applyFn(fn: SpiValue, args: List[SpiValue]): SpiValue =
      val fnV = spiToV2(fn)
      val argVs = args.map(spiToV2)
      v2ToSpi(callClosure(fnV, argVs))

  /** Call an arity-0 v2 ClosV (a thunk). */
  private def callThunk(c: V2Value.ClosV): V2Value =
    Runtime.run(c.code, c.env)

  /** Call any v2 closure with a list of args. */
  private def callClosure(fn: V2Value, args: List[V2Value]): V2Value = fn match
    case c: V2Value.ClosV =>
      val argsArr = args.toArray
      Runtime.run(c.code, if args.isEmpty then c.env else Runtime.extend(c.env, argsArr))
    case _ => sys.error(s"PluginBridge.callClosure: expected closure, got $fn")

  // ── `handle` global — Free-monad interpreter for typed effects ─────────────

  /** Register `handle` as a v2 global.
   *
   *  Typed effects compile to:
   *    handle(bodyResult)(handlerFn)
   *  where bodyResult may be:
   *    - DataV("Op", [StrV(label), arg, k])  — an effectful step (Free monad node)
   *    - any other value                      — pure result (treated as Return)
   *
   *  The handler lambda has shape: `lam 1 (match x { case op(args, resume) => ...; case Return(_) => ... })`
   *  so we call it with DataV(opName, [arg, resumeFn]) or DataV("Return", [result]).
   */
  /** Register stubs for interpreter-internal globals that aren't in any SPI plugin.
   *  These are complex language features (async, generators, signals, storage, setArgs).
   *  Stubs allow programs to load/initialize without crashing on unbound; actual
   *  functionality requires proper implementation (open TODO). */
  /** Optics runtime — mirrors v1's OpticsRuntime for the bridged pipeline.
   *  An optic = ForeignV(OpticSteps) exposing get/getOption/set/modify/andThen
   *  as NamedMethodObj fields; steps walk v2 values (DataV records via the
   *  field-name registry, Some/None, Cons/Nil lists, map wrappers). */
  private val sqlConnCache = new java.util.concurrent.ConcurrentHashMap[String, java.sql.Connection]()

  private final class OpticSteps(val steps: List[V2Value])

  private def registerOptics(): Unit =
    import V2Value.*
    def unlist(v: V2Value): List[V2Value] =
      val b = List.newBuilder[V2Value]
      var cur = v
      var go = true
      while go do cur match
        case DataV("Cons", IndexedSeq(h, t)) => b += h; cur = t
        case _ => go = false
      b.result()
    def listIdx(v: V2Value, i: Long): Option[V2Value] =
      val xs = unlist(v); if i >= 0 && i < xs.length then Some(xs(i.toInt)) else None
    def listSet(v: V2Value, i: Long, nv: V2Value): V2Value =
      val xs = unlist(v)
      if i < 0 || i >= xs.length then v
      else xs.updated(i.toInt, nv).foldRight[V2Value](DataV("Nil", Vector.empty))((x, acc) => DataV("Cons", Vector(x, acc)))
    def mapOf(v: V2Value): Option[collection.mutable.Map[V2Value, V2Value]] = v match
      case ForeignV(m: collection.mutable.Map[?, ?]) => Some(m.asInstanceOf[collection.mutable.Map[V2Value, V2Value]])
      case _ => None

    def getOpt(target: V2Value, ss: List[V2Value]): Option[V2Value] = ss match
      case Nil => Some(target)
      case st :: rest => st match
        case DataV("OField", IndexedSeq(StrV(f))) => target match
          case DataV(tag, fields) =>
            ssc.V2PluginRegistry.lookupFieldNames(tag).flatMap { ns =>
              val i = ns.indexOf(f)
              if i >= 0 && i < fields.length then getOpt(fields(i), rest) else None
            }
          case _ => None
        case DataV("OSome", _) => target match
          case DataV("Some", IndexedSeq(v)) => getOpt(v, rest)
          case _ => None
        case DataV("OIndex", IndexedSeq(IntV(i))) => listIdx(target, i).flatMap(getOpt(_, rest))
        case DataV("OAt", IndexedSeq(k)) => mapOf(target).flatMap(_.get(k)).flatMap(getOpt(_, rest))
        case _ => None

    def setPath(target: V2Value, ss: List[V2Value], nv: V2Value): V2Value = ss match
      case Nil => nv
      case st :: rest => st match
        case DataV("OField", IndexedSeq(StrV(f))) => target match
          case DataV(tag, fields) =>
            ssc.V2PluginRegistry.lookupFieldNames(tag) match
              case Some(ns) =>
                val i = ns.indexOf(f)
                if i >= 0 && i < fields.length then DataV(tag, fields.updated(i, setPath(fields(i), rest, nv)))
                else target
              case None => target
          case _ => target
        case DataV("OSome", _) => target match
          case DataV("Some", IndexedSeq(v)) => DataV("Some", Vector(setPath(v, rest, nv)))
          case _ => target
        case DataV("OIndex", IndexedSeq(IntV(i))) =>
          listIdx(target, i).fold(target)(old => listSet(target, i, setPath(old, rest, nv)))
        case DataV("OAt", IndexedSeq(k)) =>
          mapOf(target) match
            case Some(m) if m.contains(k) =>
              val copy = m.clone()
              copy(k) = setPath(m(k), rest, nv)
              ForeignV(copy)
            case _ => target
        case _ => target

    def mkList(xs: List[V2Value]): V2Value =
      xs.foldRight[V2Value](DataV("Nil", Vector.empty))((x, acc) => DataV("Cons", Vector(x, acc)))

    /** Multi-foci walk: OEach fans out over list elements; narrow steps are 0-or-1. */
    def getAll(target: V2Value, ss: List[V2Value]): List[V2Value] = ss match
      case Nil => List(target)
      case st :: rest => st match
        case DataV("OEach", _) => unlist(target).flatMap(getAll(_, rest))
        case _ => getOpt(target, st :: Nil).toList.flatMap { _ =>
          // reuse the single-focus step then continue
          getOpt(target, List(st)).toList.flatMap(getAll(_, rest))
        }

    /** Apply f at EVERY focus (traversal modify / set-all). */
    def modifyAll(target: V2Value, ss: List[V2Value], f: V2Value => V2Value): V2Value = ss match
      case Nil => f(target)
      case st :: rest => st match
        case DataV("OEach", _) =>
          mkList(unlist(target).map(modifyAll(_, rest, f)))
        case _ =>
          getOpt(target, List(st)) match
            case Some(sub) => setPath(target, List(st), modifyAll(sub, rest, f))
            case None      => target

    def isPartial(ss: List[V2Value]): Boolean = ss.exists {
      case DataV("OField", _) => false
      case _ => true
    }

    def opticKind(ss: List[V2Value]): String =
      if ss.exists { case DataV("OEach", _) => true; case _ => false } then "Traversal"
      else if isPartial(ss) then "Optional"
      else "Lens"

    def mkOptic(ss: List[V2Value], path: String = ""): V2Value =
      ForeignV(new ssc.Value.NamedMethodObj {
        def underlying: AnyRef = new OpticSteps(ss)
        override def toString: String = s"${opticKind(ss)}($path)"
        def getField(name: String): Option[V2Value] = name match
          case "_show" => Some(StrV(s"${opticKind(ss)}($path)"))
          case "get" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(getOpt(env(0), ss).getOrElse(sys.error("optic.get: path missing")))))
          case "getOption" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(getOpt(env(0), ss).fold[V2Value](DataV("None", Vector.empty))(v => DataV("Some", Vector(v))))))
          case "set" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(
              if ss.exists { case DataV("OEach", _) => true; case _ => false }
              then modifyAll(env(0), ss, _ => env(1))
              else setPath(env(0), ss, env(1)))))
          case "modify" => Some(ClosV(Runtime.emptyEnv, -1, env => {
            val target = env(0)
            val f = env(1).asInstanceOf[ClosV]
            if ss.exists { case DataV("OEach", _) => true; case _ => false } then
              Done(modifyAll(target, ss, v => callClosure(f, List(v))))
            else getOpt(target, ss) match
              case Some(cur) => Done(setPath(target, ss, callClosure(f, List(cur))))
              case None      => Done(target)
          }))
          case "getAll" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(mkList(getAll(env(0), ss)))))
          case "modifyAll" => Some(ClosV(Runtime.emptyEnv, -1, env => {
            val f = env(1).asInstanceOf[ClosV]
            Done(modifyAll(env(0), ss, v => callClosure(f, List(v))))
          }))
          case "andThen" => Some(ClosV(Runtime.emptyEnv, -1, env => env(0) match
            case ForeignV(other: ssc.Value.NamedMethodObj) => other.underlying match
              case os: OpticSteps => Done(mkOptic(ss ++ os.steps, path))
              case _ => sys.error("optic.andThen: not an optic")
            case _ => sys.error("optic.andThen: not an optic")))
          case "isPartial" => Some(BoolV(isPartial(ss)))
          case _ => None
      })

    // Context-bound dictionary resolution: pick the given instance whose type
    // head matches the runtime WITNESS value. args = tc :: drill :: witness ::
    // (head, instance) pairs (table embedded at the call site by the bridge).
    V2PluginRegistry.register("__resolve_given__", args => args match
      case StrV("QuotedContext") :: _ =>
        V2PluginRegistry.lookupGlobal("QuotedContext")
          .getOrElse(DataV("QuotedContext", Vector.empty))
      case StrV(tc) :: StrV(drill) :: witness :: table =>
        def headOf(v: V2Value): String = v match
          case IntV(_)   => "Int"
          case StrV(_)   => "String"
          case BoolV(_)  => "Boolean"
          case FloatV(_) => "Double"
          case DataV("Cons" | "Nil", _)  => "List"
          case DataV("Some" | "None", _) => "Option"
          case DataV(t, _) => t
          case _ => "?"
        val w = drill match
          case "elem" => witness match
            case DataV("Cons", IndexedSeq(h, _)) => h
            case other => other
          case _ => witness
        val want = headOf(w)
        val pairs = table.grouped(2).collect { case List(StrV(h), inst) => h -> inst }.toList
        pairs.find(_._1 == want).orElse(pairs.headOption).map(_._2)
          .getOrElse(sys.error(s"__resolve_given__: no $tc instance for $want"))
      case _ => sys.error("__resolve_given__: bad args"))

    V2PluginRegistry.register("optics.focus", args => args match
      case List(steps, StrV(path)) => mkOptic(unlist(steps), path)
      case List(steps)             => mkOptic(unlist(steps))
      case _ => sys.error("optics.focus(steps[, path])"))

    // Prism[Outer, Variant] — v1 buildPrism mirror: getOption/set/modify hit only
    // when the target's tag equals the variant; reverseGet is identity.
    def mkPrism(variant: String): V2Value =
      def tagMatches(v: V2Value): Boolean = v match
        case DataV(t, _) => t == variant
        case _ => false
      ForeignV(new ssc.Value.NamedMethodObj {
        def underlying: AnyRef = ("Prism", variant)
        def getField(name: String): Option[V2Value] = name match
          case "getOption" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(if tagMatches(env(0)) then DataV("Some", Vector(env(0))) else DataV("None", Vector.empty))))
          case "reverseGet" => Some(ClosV(Runtime.emptyEnv, -1, env => Done(env(0))))
          case "set" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(if tagMatches(env(0)) then env(1) else env(0))))
          case "modify" => Some(ClosV(Runtime.emptyEnv, -1, env => {
            if tagMatches(env(0)) then Done(callClosure(env(1).asInstanceOf[ClosV], List(env(0))))
            else Done(env(0))
          }))
          case "_variant" => Some(StrV(variant))
          case "_show"    => Some(StrV(s"Prism[?, $variant]"))
          case _ => None
      })
    V2PluginRegistry.register("optics.prism", args => args match
      case List(StrV(v)) => mkPrism(v)
      case _ => sys.error("optics.prism(variant)"))

  /** Ambient effect ops: in v1 the core effects (Random/Clock) work WITHOUT an
   *  explicit handler block (ambient defaults). The bridge's dispatch tries plugin
   *  lookup before the free-monad Op fallback, so registering these here gives the
   *  same ambient semantics (mapreduce's jobId = Random.uuid() etc.). */
  private def registerAmbientEffectOps(): Unit =
    V2PluginRegistry.register("Random.uuid", _ => V2Value.StrV(java.util.UUID.randomUUID().toString))
    V2PluginRegistry.register("Random.int", {
      case List(V2Value.IntV(n)) => V2Value.IntV(scala.util.Random.nextLong(n))
      case _                     => V2Value.IntV(scala.util.Random.nextLong())
    })
    V2PluginRegistry.register("Random.double", _ => V2Value.FloatV(scala.util.Random.nextDouble()))
    V2PluginRegistry.register("Clock.now", _ => V2Value.IntV(System.currentTimeMillis()))
    V2PluginRegistry.register("Clock.nanos", _ => V2Value.IntV(System.nanoTime()))

  private def registerInterpreterBuiltins(): Unit =
    import V2Value.*
    // println / print — variadic so println() works (0-arg prints a blank line).
    // v1-INTERPRETER display parity (T4.4 output-equality): tuples render as
    // (a, b); Cons/Nil chains as List(...); strings are RAW even inside
    // containers; integral Doubles drop the trailing .0. The ssc0-native
    // pipeline keeps the kernel Show — this only applies to bridged programs.
    def v1Show(v: V2Value): String = v match
      case V2Value.StrV(s)   => s
      case V2Value.FloatV(d) =>
        if d == d.toLong.toDouble && math.abs(d) < 1e15 then d.toLong.toString else d.toString
      case V2Value.DataV(t, fs) if t.startsWith("Tuple") && t.length > 5 && t.drop(5).forall(_.isDigit) =>
        fs.map(v1Show).mkString("(", ", ", ")")
      case lv @ V2Value.DataV("Cons" | "Nil", _) =>
        val items = collection.mutable.ListBuffer.empty[String]
        var cur: V2Value = lv
        var listy = true
        while listy do cur match
          case V2Value.DataV("Cons", IndexedSeq(h, t)) => items += v1Show(h); cur = t
          case V2Value.DataV("Nil", _)                 => listy = false
          case other                                   => items += ("…" + v1Show(other)); listy = false
        items.mkString("List(", ", ", ")")
      case V2Value.DataV("_Raw", IndexedSeq(V2Value.StrV(html))) => html
      case V2Value.DataV("TableNode", IndexedSeq(columns, rows, V2Value.DataV("None", _))) =>
        s"TableNode(${v1Show(columns)}, ${v1Show(rows)}, null)"
      case V2Value.DataV(tag, fs) if fs.isEmpty => tag
      case V2Value.DataV(tag, fs) => tag + fs.map(v1Show).mkString("(", ", ", ")")
      case V2Value.ForeignV(m: collection.mutable.Map[?, ?]) =>
        m.asInstanceOf[collection.mutable.Map[V2Value, V2Value]]
          .map { case (k, vv) => s"${v1Show(k)} -> ${v1Show(vv)}" }.mkString("Map(", ", ", ")")
      case V2Value.ForeignV(nmo: ssc.Value.NamedMethodObj) =>
        nmo.getField("_show") match
          case Some(V2Value.StrV(s)) => s
          case _ =>
            nmo.underlying match
              case v1v: scalascript.interpreter.Value => scalascript.interpreter.Value.show(v1v)
              case _ =>
                // v1 facades (json wrapJson etc.): render the INNER value via the
                // 0-arg `raw` field, the way v1 prints the wrapped value itself.
                nmo.getField("raw") match
                  case Some(c: V2Value.ClosV) => v1Show(callClosure(c, Nil))
                  case _ => Show.show(v)
      case other => Show.show(other)
    def showForPrint(v: V2Value): String = v1Show(v)
    if V2PluginRegistry.lookupGlobal("println").isEmpty then
      V2PluginRegistry.registerGlobal("println", ClosV(Runtime.emptyEnv, -1, env => {
        if env.isEmpty then Console.out.println()
        else Console.out.println(showForPrint(env(0)))
        Done(UnitV)
      }))
    if V2PluginRegistry.lookupGlobal("print").isEmpty then
      V2PluginRegistry.registerGlobal("print", ClosV(Runtime.emptyEnv, -1, env => {
        if env.nonEmpty then Console.out.print(showForPrint(env(0)))
        Done(UnitV)
      }))
      // Predef `???` — a value that throws NotImplementedError when forced
      // (Scala semantics). It is a paren-less method, so the bridge references
      // it bare; without this it surfaced as a confusing "unbound global: ???".
      V2PluginRegistry.registerGlobal("???",
        ClosV(Runtime.emptyEnv, 0, _ => throw new scala.NotImplementedError()))
    // Restricted quoted macro helpers for interpreter/run-path parity.
    // `MacroCodegen` intentionally leaves interpreter-only macro bodies alone;
    // `ssc run --v2` still has to execute the helper form that the v1 parser
    // emits for `${ impl('x) }`, `Expr(v)`, and direct quote/splice helpers.
    val quotedNone = DataV("None", Vector.empty)
    def quotedExpr(name: String, value: V2Value): V2Value =
      DataV("Expr", Vector(StrV(name), value))
    def exprValue(v: V2Value): Option[V2Value] = v match
      case DataV("Expr", fields) => Some(fields.lift(1).getOrElse(UnitV))
      case _                     => None
    if V2PluginRegistry.lookupGlobal("__ssc_macro__").isEmpty then
      V2PluginRegistry.registerGlobal("__ssc_macro__", ClosV(Runtime.emptyEnv, -1, env => {
        if env.length != 1 then sys.error("__ssc_macro__(expr)")
        Done(exprValue(env(0)).getOrElse(env(0)))
      }))
    if V2PluginRegistry.lookupGlobal("__ssc_macro_error__").isEmpty then
      V2PluginRegistry.registerGlobal("__ssc_macro_error__", ClosV(Runtime.emptyEnv, -1, env => {
        val msg = env.headOption match
          case Some(StrV(s)) => s
          case Some(v)      => showForPrint(v)
          case None         => "unsupported restricted quoted macro form"
        sys.error(s"quoted macro error: $msg")
      }))
    if V2PluginRegistry.lookupGlobal("__ssc_quote__").isEmpty then
      V2PluginRegistry.registerGlobal("__ssc_quote__", ClosV(Runtime.emptyEnv, -1, env => {
        env.toList match
          case StrV(name) :: value :: Nil => Done(quotedExpr(name, value))
          case StrV(name) :: Nil          => Done(quotedExpr(name, quotedNone))
          case _                          => sys.error("__ssc_quote__(name, value)")
      }))
    if V2PluginRegistry.lookupGlobal("__ssc_quote_expr__").isEmpty then
      V2PluginRegistry.registerGlobal("__ssc_quote_expr__", ClosV(Runtime.emptyEnv, -1, env => {
        if env.length != 1 then sys.error("__ssc_quote_expr__(value)")
        Done(env(0))
      }))
    if V2PluginRegistry.lookupGlobal("__ssc_splice__").isEmpty then
      V2PluginRegistry.registerGlobal("__ssc_splice__", ClosV(Runtime.emptyEnv, -1, env => {
        env.toList match
          case StrV(_) :: expr :: Nil => Done(exprValue(expr).getOrElse(expr))
          case expr :: Nil            => Done(exprValue(expr).getOrElse(expr))
          case _                      => sys.error("__ssc_splice__(name, expr)")
      }))
    if V2PluginRegistry.lookupGlobal("Expr").isEmpty then
      V2PluginRegistry.registerGlobal("Expr", ClosV(Runtime.emptyEnv, -1, env => {
        if env.length != 1 then sys.error("Expr(value)")
        Done(quotedExpr("<literal>", env(0)))
      }))
    if V2PluginRegistry.lookupGlobal("QuotedContext").isEmpty then
      V2PluginRegistry.registerGlobal("QuotedContext", DataV("QuotedContext", Vector.empty))
    V2PluginRegistry.registerFieldNames("Expr", Vector("name", "value"))
    V2PluginRegistry.registerFieldNames("ScalaScriptTerm", Vector("name", "value"))
    // setHttpServerBackend(name) — already no-op'd in registerBatchRunnerStubs()
    // runAsync { body } — run the body immediately (no async in v2 yet)
    if V2PluginRegistry.lookupGlobal("runAsync").isEmpty then
      V2PluginRegistry.registerGlobal("runAsync", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        Done(thunk match
          case c: ClosV => callClosure(c, Nil)
          case v => v)
      }))
    // runAsyncParallel { body } — same stub as runAsync
    if V2PluginRegistry.lookupGlobal("runAsyncParallel").isEmpty then
      V2PluginRegistry.registerGlobal("runAsyncParallel", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        Done(thunk match
          case c: ClosV => callClosure(c, Nil)
          case v => v)
      }))
    // computed(f) — returns a thunk-based pseudo-signal cell that re-evaluates on .get
    if V2PluginRegistry.lookupGlobal("computed").isEmpty then
      V2PluginRegistry.registerGlobal("computed", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        // Wrap thunk in a special ComputedCell: ForeignV(Array) where Array holds the thunk
        // The .get dispatch will call the thunk to get the current value
        thunk match
          case c: ClosV => Done(ForeignV(new ComputedCell(c).asInstanceOf[AnyRef]))
          case v => Done(ForeignV(Array[V2Value](v).asInstanceOf[AnyRef]))
      }))
    // generator { body } — virtual-thread coroutine generator; suspend(v) yields values
    if V2PluginRegistry.lookupGlobal("generator").isEmpty then
      V2PluginRegistry.registerGlobal("generator", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.lastOption.getOrElse(UnitV)
        val queue = new java.util.concurrent.LinkedBlockingQueue[Option[V2Value]]()
        Thread.ofVirtual().start { () =>
          genQueueTL.set(queue)
          try callClosure(thunk, Nil)
          catch case _: Throwable => ()
          finally try queue.put(None) catch case _ => ()
        }
        Done(makeGenerator(queue))
      }))
    // suspend(v) — yield value from inside a generator body
    if V2PluginRegistry.lookupGlobal("suspend").isEmpty then
      V2PluginRegistry.registerGlobal("suspend", ClosV(Runtime.emptyEnv, 1, env => {
        val v   = env.last
        val q   = genQueueTL.get()
        if q != null then q.put(Some(v))
        Done(UnitV)
      }))
    def esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    def unesc(s: String): String = s.replace("\\\"", "\"").replace("\\\\", "\\")
    // runEphemeralStorage { body } — run body with an in-memory map store
    if V2PluginRegistry.lookupGlobal("runEphemeralStorage").isEmpty then
      V2PluginRegistry.registerGlobal("runEphemeralStorage", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        val map = scala.collection.mutable.LinkedHashMap.empty[String, String]
        val prev = storageLocal.get()
        storageLocal.set(map)
        val result = try thunk match
          case c: ClosV => callClosure(c, Nil)
          case v => v
        finally storageLocal.set(prev)
        Done(result)
      }))
    // runStorage { body } — like runEphemeralStorage but FILE-BACKED: loads
    // ./ssc-storage.json before the body, persists it after (v1 storage-plugin
    // semantics; storage-demo round-trips a value across two invocations).
    if V2PluginRegistry.lookupGlobal("runStorage").isEmpty then
      V2PluginRegistry.registerGlobal("runStorage", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        val file = new java.io.File("ssc-storage.json")
        val map = scala.collection.mutable.LinkedHashMap.empty[String, String]
        if file.exists then
          try
            val txt = scala.io.Source.fromFile(file).mkString.trim
            // minimal flat {"k":"v",…} parse — the storage plugin writes only this shape
            """"((?:[^"\\]|\\.)*)"\s*:\s*"((?:[^"\\]|\\.)*)"""".r
              .findAllMatchIn(txt).foreach(m => map(unesc(m.group(1))) = unesc(m.group(2)))
          catch case _: Throwable => ()
        val prev = storageLocal.get()
        storageLocal.set(map)
        val result = try thunk match
          case c: ClosV => callClosure(c, Nil)
          case v => v
        finally
          storageLocal.set(prev)
          try
            val json = map.map((k, v) => s"\"${esc(k)}\":\"${esc(v)}\"").mkString("{", ",", "}")
            java.nio.file.Files.write(file.toPath, json.getBytes("UTF-8"))
          catch case _: Throwable => ()
        Done(result)
      }))

    // (args registration moved to loadAll AFTER the plugin loop — a plugin
    // bridges a native FUNCTION under "args" that must not shadow the list.)
    // startNode — cluster node stub
    if V2PluginRegistry.lookupGlobal("startNode").isEmpty then
      V2PluginRegistry.registerGlobal("startNode", ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))
    // contentBlock — content rendering stub
    if V2PluginRegistry.lookupGlobal("contentBlock").isEmpty then
      V2PluginRegistry.registerGlobal("contentBlock", ClosV(Runtime.emptyEnv, -1, env =>
        Done(env.lastOption.getOrElse(UnitV))))
    if V2PluginRegistry.lookupGlobal("nanoTime").isEmpty then
      V2PluginRegistry.registerGlobal("nanoTime", ClosV(Runtime.emptyEnv, 0, _ => Done(IntV(System.nanoTime()))))
    // __throw__ — lowered from throw expressions in FrontendBridge
    if V2PluginRegistry.lookupGlobal("__throw__").isEmpty then
      V2PluginRegistry.registerGlobal("__throw__", ClosV(Runtime.emptyEnv, 1, env => {
        throw new BridgeThrow(env.last)
      }))
    // ```sql fenced blocks (v1.26): execute over JDBC. SELECT → List[Map[label,
    // value]] (rows applied by column label); DML/DDL → Int (affected rows).
    V2PluginRegistry.register("__sqlExec__", args => args match
      case StrV(url) :: StrV(sql) :: bindsV :: Nil =>
        val binds = bindsV match
          case DataV("Cons", _) | DataV("Nil", _) =>
            var out = List.empty[V2Value]; var cur = bindsV
            while cur.isInstanceOf[DataV] && cur.asInstanceOf[DataV].tag == "Cons" do
              val c = cur.asInstanceOf[DataV]; out = out :+ c.fields(0); cur = c.fields(1)
            out
          case _ => Nil
        // Fail-soft on engines this JVM lane can't serve (sqlite::memory:,
        // duckdb — browser/spark-lane examples): a Stub keeps the doc's other
        // fences running, matching the previous ignore-sql behavior.
        // Connections are CACHED per url: in-memory engines (sqlite::memory:)
        // lose their tables if every statement opens a fresh connection.
        val connOpt =
          try Some(sqlConnCache.computeIfAbsent(url, u => java.sql.DriverManager.getConnection(u)))
          catch { case _: java.sql.SQLException | _: RuntimeException => None }
        connOpt match
         case None => DataV("Stub", Vector(StrV(s"sql:no-driver:$url")))
         case Some(conn) =>
          try
            val ps = conn.prepareStatement(sql)
            binds.zipWithIndex.foreach { case (b, i) =>
              val raw: AnyRef = b match
                case IntV(n)   => java.lang.Long.valueOf(n)
                case FloatV(d) => java.lang.Double.valueOf(d)
                case BoolV(x)  => java.lang.Boolean.valueOf(x)
                case StrV(t)   => t
                case other     => other.toString
              ps.setObject(i + 1, raw)
            }
            if sql.contains(";") && !sql.trim.toUpperCase.startsWith("SELECT") then
              // ```transaction fence: multiple ;-separated statements, atomically;
              // binds distributed in order by each statement's ? count. Result =
              // the LAST statement's affected-row count.
              conn.setAutoCommit(false)
              var rest = binds
              var last = 0
              try
                sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { st =>
                  val n = st.count(_ == '?')
                  val (mine, more) = rest.splitAt(n)
                  rest = more
                  val p2 = conn.prepareStatement(st)
                  mine.zipWithIndex.foreach { case (b, i) =>
                    val raw: AnyRef = b match
                      case IntV(nn)  => java.lang.Long.valueOf(nn)
                      case FloatV(d) => java.lang.Double.valueOf(d)
                      case BoolV(x)  => java.lang.Boolean.valueOf(x)
                      case StrV(t)   => t
                      case other     => other.toString
                    p2.setObject(i + 1, raw)
                  }
                  last = p2.executeUpdate()
                }
                conn.commit()
              catch { case e: Throwable => conn.rollback(); throw e }
              IntV(last.toLong)
            else if sql.trim.toUpperCase.startsWith("SELECT") then
              val rs = ps.executeQuery()
              val md = rs.getMetaData
              val cols = (1 to md.getColumnCount).map(md.getColumnLabel).toList
              var rows = List.empty[V2Value]
              while rs.next() do
                val m = scala.collection.mutable.LinkedHashMap.empty[V2Value, V2Value]
                cols.foreach { c =>
                  val v: V2Value = rs.getObject(c) match
                    case null                     => DataV("None", Vector.empty)
                    case n: java.lang.Long        => IntV(n)
                    case n: java.lang.Integer     => IntV(n.longValue)
                    case d: java.lang.Double      => FloatV(d)
                    case b: java.lang.Boolean     => BoolV(b)
                    case s: String                => StrV(s)
                    case o                        => StrV(o.toString)
                  m(StrV(c)) = v
                }
                rows = rows :+ V2Value.ForeignV(m)
              rows.foldRight(DataV("Nil", Vector.empty): V2Value)((r, acc) => DataV("Cons", Vector(r, acc)))
            else IntV(ps.executeUpdate().toLong)
          catch { case e: Throwable => sqlConnCache.remove(url); throw e }
      case _ => sys.error("__sqlExec__(url, sql, binds)"))

    V2PluginRegistry.register("__try__", args => args match
      case List(thunk: ClosV, handler: ClosV) =>
        try callClosure(thunk, Nil)
        catch
          case BridgeThrow(v) => callClosure(handler, List(v))
          case e: scalascript.interpreter.InterpretError =>
            callClosure(handler, List(StrV(e.getMessage)))
      case _ => sys.error("__try__(thunk, handler)"))
    // getenv(key[, default]) — interpreter built-in not in any SPI plugin
    if V2PluginRegistry.lookupGlobal("getenv").isEmpty then
      V2PluginRegistry.registerGlobal("getenv", ClosV(Runtime.emptyEnv, -1, env => {
        val key = env.headOption match { case Some(StrV(s)) => s; case Some(v) => v.toString; case None => "" }
        val default_ = if env.length >= 2 then env(1) else StrV("")
        val v = System.getenv(key)
        Done(if v != null && v.nonEmpty then StrV(v) else default_)
      }))
    // Decimal / BigDecimal — exact numeric type backed by java.math.BigDecimal
    if V2PluginRegistry.lookupGlobal("Decimal").isEmpty then
      registerDecimalSupport()
    // BigInt factory: BigInt(n) / BigInt("123")
    if V2PluginRegistry.lookupGlobal("BigInt").isEmpty then
      V2PluginRegistry.registerGlobal("BigInt", ClosV(Runtime.emptyEnv, -1, env => env.toList match
        case List(IntV(n)) => Done(BigV(BigInt(n)))
        case List(StrV(s)) => Done(BigV(BigInt(s)))
        case List(BigV(n)) => Done(BigV(n))
        case _             => Done(BigV(BigInt(0)))
      ))
    // Exception factories used in throw expressions
    Seq("RuntimeException","Exception","IllegalArgumentException","IllegalStateException","UnsupportedOperationException").foreach { exName =>
      if V2PluginRegistry.lookupGlobal(exName).isEmpty then
        V2PluginRegistry.registerGlobal(exName, ClosV(Runtime.emptyEnv, -1, env => {
          val msg = env.headOption.map {
            case StrV(s) => s; case v => v.toString
          }.getOrElse(exName)
          Done(DataV(exName, Vector(StrV(msg))))
        }))
    }
    // scope(name) — CSS scoping helper: returns object with cls(n)→"n__name" and css(s)→scoped CSS
    if V2PluginRegistry.lookupGlobal("scope").isEmpty then
      V2PluginRegistry.registerGlobal("scope", ClosV(Runtime.emptyEnv, 1, env => {
        val name = env.last match { case StrV(s) => s; case v => v.toString }
        val clsFn = ClosV(Runtime.emptyEnv, 1, e => e.last match
          case StrV(n) => Done(StrV(s"${n}__${name}")); case v => Done(v))
        val cssFn = ClosV(Runtime.emptyEnv, 1, e => Done(e.lastOption.getOrElse(StrV(""))))
        Done(ForeignV(new ssc.Value.NamedMethodObj {
          def getField(field: String): Option[ssc.Value] = field match
            case "cls" => Some(clsFn)
            case "css" => Some(cssFn)
            case _     => None
          def underlying: AnyRef = name
        }))
      }))
    // HTML DSL: div/h1/p/ul/li/a/etc. tag builders + attr object + raw
    if V2PluginRegistry.lookupGlobal("div").isEmpty then registerHtmlDsl()
    // REST validation: validate { } + require* helpers
    if V2PluginRegistry.lookupGlobal("validate").isEmpty then registerValidateFns()

  private def registerHtmlDsl(): Unit =
    import V2Value.*
    def htmlEscape(s: String): String =
      s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    def valStr(v: V2Value): String = v match
      case StrV(s)   => s
      case IntV(n)   => n.toString
      case FloatV(d) => if d == d.toLong.toDouble && math.abs(d) < 1e15 then d.toLong.toString else d.toString
      case _         => Show.show(v)
    def renderChild(v: V2Value): String = v match
      case DataV("_Raw", IndexedSeq(StrV(html))) => html
      case DataV("Cons" | "Nil", _) =>
        val sb = new StringBuilder
        var cur: V2Value = v; var go = true
        while go do cur match
          case DataV("Cons", IndexedSeq(h, t)) => sb ++= renderChild(h); cur = t
          case DataV("Nil", _) => go = false
          case other => sb ++= htmlEscape(valStr(other)); go = false
        sb.toString
      case other => htmlEscape(valStr(other))
    def renderTag(name: String, args: Array[V2Value], voidTag: Boolean): V2Value =
      val attrs    = collection.mutable.LinkedHashMap.empty[String, String]
      val children = new StringBuilder
      def handle(v: V2Value): Unit = v match
        case DataV("Attr", IndexedSeq(StrV(k), attrVal)) =>
          attrs(k) = htmlEscape(valStr(attrVal))
        case DataV("Cons" | "Nil", _) =>
          var cur: V2Value = v; var go = true
          while go do cur match
            case DataV("Cons", IndexedSeq(h, t)) => handle(h); cur = t
            case DataV("Nil", _) => go = false
            case other => children ++= renderChild(other); go = false
        case other => children ++= renderChild(other)
      args.foreach(handle)
      val attrStr = attrs.map((k, v) => s""" $k="$v"""").mkString
      val html = if voidTag then s"<$name$attrStr>" else s"<$name$attrStr>${children.toString}</$name>"
      DataV("_Raw", collection.immutable.ArraySeq(StrV(html)))
    def makeAttrKey(htmlName: String): V2Value =
      ForeignV(new ssc.Value.NamedMethodObj {
        def getField(field: String): Option[ssc.Value] = field match
          case ":=" => Some(ClosV(Runtime.emptyEnv, 1, env =>
            Done(DataV("Attr", collection.immutable.ArraySeq(StrV(htmlName), env(0))))))
          case _ => None
        def underlying: AnyRef = htmlName
        override def toString = s"AttrKey($htmlName)"
      })
    val attrFields: Map[String, ssc.Value] = Map(
      "cls" -> makeAttrKey("class"), "id" -> makeAttrKey("id"),
      "href" -> makeAttrKey("href"), "src" -> makeAttrKey("src"),
      "alt" -> makeAttrKey("alt"), "name" -> makeAttrKey("name"),
      "title" -> makeAttrKey("title"), "style" -> makeAttrKey("style"),
      "type_" -> makeAttrKey("type"), "value_" -> makeAttrKey("value"),
      "placeholder" -> makeAttrKey("placeholder"), "method_" -> makeAttrKey("method"),
      "action" -> makeAttrKey("action"), "target" -> makeAttrKey("target"),
      "rel" -> makeAttrKey("rel"), "for_" -> makeAttrKey("for"),
      "role" -> makeAttrKey("role"), "colspan" -> makeAttrKey("colspan"),
      "rowspan" -> makeAttrKey("rowspan"), "disabled" -> makeAttrKey("disabled"),
    )
    V2PluginRegistry.registerGlobal("attr", ForeignV(new ssc.Value.NamedMethodObj {
      def getField(field: String): Option[ssc.Value] = attrFields.get(field)
      def underlying: AnyRef = "attr"
      override def toString = "attr"
    }))
    V2PluginRegistry.registerGlobal("raw", ClosV(Runtime.emptyEnv, 1, env => env(0) match
      case DataV("_Raw", _) => Done(env(0))
      case StrV(s)          => Done(DataV("_Raw", collection.immutable.ArraySeq(StrV(s))))
      case _                => Done(env(0))))
    val containerTags = List(
      "html", "head", "body", "title", "style", "script", "main",
      "section", "header", "footer", "nav", "article", "aside",
      "div", "span", "p", "a", "em", "strong", "small", "code", "pre",
      "h1", "h2", "h3", "h4", "h5", "h6",
      "ul", "ol", "li", "dl", "dt", "dd",
      "table", "thead", "tbody", "tfoot", "tr", "td", "th",
      "form", "button", "label", "select", "option", "textarea",
      "figure", "figcaption", "blockquote",
    )
    containerTags.foreach { tag =>
      V2PluginRegistry.registerGlobal(tag, ClosV(Runtime.emptyEnv, -1, env =>
        Done(renderTag(tag, env, voidTag = false))))
    }
    val voidTags = List("br", "hr", "img", "input", "link", "meta")
    voidTags.foreach { tag =>
      V2PluginRegistry.registerGlobal(tag, ClosV(Runtime.emptyEnv, -1, env =>
        Done(renderTag(tag, env, voidTag = true))))
    }

  // Thread-local accumulator for validate { } error collection.
  private val _validateStack =
    ThreadLocal.withInitial[collection.mutable.Stack[collection.mutable.LinkedHashMap[V2Value, V2Value]]](
      () => collection.mutable.Stack.empty)

  private def registerValidateFns(): Unit =
    import V2Value.*
    def currentErrors: Option[collection.mutable.LinkedHashMap[V2Value, V2Value]] =
      val s = _validateStack.get()
      if s.isEmpty then None else Some(s.top)
    def recordError(name: String, msg: String): Unit =
      currentErrors.foreach { m => m(StrV(name)) = StrV(msg) }
    def reqLookup(req: V2Value, name: String): Option[String] =
      def fromMap(mapV: V2Value): Option[String] = mapV match
        case ForeignV(m: collection.mutable.HashMap[V2Value, V2Value] @unchecked) =>
          m.get(StrV(name)).collect { case StrV(s) => s }
        case _ => None
      req match
        case DataV(tag, fields) =>
          V2PluginRegistry.lookupFieldNames(tag) match
            case Some(fnames) =>
              val fi = fnames.indexOf("form")
              val qi = fnames.indexOf("query")
              val formVal = if fi >= 0 && fi < fields.length then fromMap(fields(fi)) else None
              formVal.orElse(if qi >= 0 && qi < fields.length then fromMap(fields(qi)) else None)
            case None => None
        case _ => None
    V2PluginRegistry.registerGlobal("validate", ClosV(Runtime.emptyEnv, -1, env => {
      val thunk  = env.last
      val errors = collection.mutable.LinkedHashMap[V2Value, V2Value]()
      _validateStack.get().push(errors)
      val result = try thunk match
        case c: ClosV => callClosure(c, Nil)
        case v        => v
      finally _validateStack.get().pop()
      if errors.isEmpty then Done(DataV("Right", collection.immutable.ArraySeq(result)))
      else Done(DataV("Left", collection.immutable.ArraySeq(ForeignV(errors))))
    }))
    V2PluginRegistry.registerGlobal("requireString", ClosV(Runtime.emptyEnv, 2, env => {
      val n    = env.length
      val req  = env(n - 2)
      val name = env(n - 1) match { case StrV(s) => s; case v => v.toString }
      Done(reqLookup(req, name) match
        case Some(v) => StrV(v)
        case None => recordError(name, s"missing field: $name"); StrV(""))
    }))
    V2PluginRegistry.registerGlobal("requireRange", ClosV(Runtime.emptyEnv, 4, env => {
      val n    = env.length
      val req  = env(n - 4)
      val name = env(n - 3) match { case StrV(s) => s; case v => v.toString }
      val min  = env(n - 2) match { case IntV(i) => i; case FloatV(d) => d.toLong; case _ => 0L }
      val max  = env(n - 1) match { case IntV(i) => i; case FloatV(d) => d.toLong; case _ => 0L }
      Done(reqLookup(req, name) match
        case Some(v) =>
          v.toLongOption match
            case Some(r) if r >= min && r <= max => IntV(r)
            case _ => recordError(name, s"out of range [$min..$max] for field: $name"); IntV(min)
        case None => recordError(name, s"missing field: $name"); IntV(min))
    }))
    V2PluginRegistry.registerGlobal("requireRangeDouble", ClosV(Runtime.emptyEnv, 4, env => {
      val n    = env.length
      val req  = env(n - 4)
      val name = env(n - 3) match { case StrV(s) => s; case v => v.toString }
      val min  = env(n - 2) match { case FloatV(d) => d; case IntV(i) => i.toDouble; case _ => 0.0 }
      val max  = env(n - 1) match { case FloatV(d) => d; case IntV(i) => i.toDouble; case _ => 0.0 }
      Done(reqLookup(req, name) match
        case Some(v) =>
          v.toDoubleOption match
            case Some(r) if r >= min && r <= max => FloatV(r)
            case _ => recordError(name, s"out of range [$min..$max] for field: $name"); FloatV(min)
        case None => recordError(name, s"missing field: $name"); FloatV(min))
    }))
    V2PluginRegistry.registerGlobal("requireOneOf", ClosV(Runtime.emptyEnv, 3, env => {
      val n    = env.length
      val req  = env(n - 3)
      val name = env(n - 2) match { case StrV(s) => s; case v => v.toString }
      val opts = {
        val b = List.newBuilder[String]
        var cur = env(n - 1); var go = true
        while go do cur match
          case DataV("Cons", IndexedSeq(StrV(s), t)) => b += s; cur = t
          case _ => go = false
        b.result()
      }
      Done(reqLookup(req, name) match
        case Some(v) if opts.contains(v) => StrV(v)
        case Some(v) =>
          recordError(name, s"invalid value '$v' for field: $name, expected one of: ${opts.mkString(", ")}"); StrV("")
        case None =>
          recordError(name, s"missing field: $name"); StrV(""))
    }))

  private def registerDecimalSupport(): Unit =
    import java.math.{BigDecimal => JBD, RoundingMode => JRM, MathContext}
    import V2Value.*
    def toBD(v: V2Value): JBD = v match
      case StrV(s)           => new JBD(s.trim)
      case IntV(n)           => JBD.valueOf(n)
      case FloatV(d)         => JBD.valueOf(d)
      case ForeignV(bd: JBD) => bd
      case DataV("Decimal", fields) if fields.nonEmpty => toBD(fields.head)
      case _                 => throw scalascript.interpreter.InterpretError(s"Decimal: not a number: $v")
    def fromBD(bd: JBD): V2Value = ForeignV(bd)
    def toRM(v: V2Value): JRM = v match
      case DataV(name, _)    => try JRM.valueOf(name) catch case _: Throwable => JRM.HALF_UP
      case ForeignV(rm: JRM) => rm
      case _                 => JRM.HALF_UP
    val decimalCtor = ClosV(Runtime.emptyEnv, -1, env => env.toList match
      case List(v: V2Value)    => Done(fromBD(toBD(v)))
      case List(u: V2Value, s: V2Value) =>
        val unscaled = u match { case IntV(n) => BigInt(n); case BigV(n) => n; case _ => BigInt(toBD(u).unscaledValue()) }
        Done(fromBD(new JBD(unscaled.bigInteger, s match { case IntV(n) => n.toInt; case _ => 0 })))
      case _ => Done(fromBD(JBD.ZERO))
    )
    V2PluginRegistry.registerGlobal("Decimal", decimalCtor)
    V2PluginRegistry.registerGlobal("BigDecimal", decimalCtor)
    // RoundingMode object with fields
    val rmMap: Map[String, V2Value] = Seq("UP","DOWN","CEILING","FLOOR","HALF_UP","HALF_DOWN","HALF_EVEN","UNNECESSARY")
      .map(n => n -> ForeignV(JRM.valueOf(n))).toMap
    V2PluginRegistry.registerGlobal("RoundingMode", ForeignV(rmMap.asInstanceOf[AnyRef]))
    // Dispatch setScale/round/add/sub/mul/div/compareTo on ForeignV(JBD)
    V2PluginRegistry.register("__method__.setScale", args => args(1) match
      case ForeignV(bd: JBD) =>
        val scale = args(2) match { case IntV(n) => n.toInt; case _ => 2 }
        val rm    = if args.length >= 4 then toRM(args(3)) else JRM.HALF_UP
        fromBD(bd.setScale(scale, rm))
      case _ => sys.error(s"__method__: no dispatch for .setScale on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.round", args => args(1) match
      case ForeignV(bd: JBD) =>
        val scale = args(2) match { case IntV(n) => n.toInt; case FloatV(d) => d.toInt; case _ => 0 }
        fromBD(bd.setScale(scale, JRM.HALF_UP))
      case _ => sys.error(s"__method__: no dispatch for .round on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.toInt", args => args(1) match
      case ForeignV(bd: JBD) => IntV(bd.longValueExact())
      case _ => sys.error(s"__method__: no dispatch for .toInt on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.toLong", args => args(1) match
      case ForeignV(bd: JBD) => IntV(bd.longValueExact())
      case _ => sys.error(s"__method__: no dispatch for .toLong on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.toDouble", args => args(1) match
      case ForeignV(bd: JBD) => FloatV(bd.doubleValue())
      case _ => sys.error(s"__method__: no dispatch for .toDouble on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.scale", args => args(1) match
      case ForeignV(bd: JBD) => IntV(bd.scale())
      case _ => sys.error(s"__method__: no dispatch for .scale on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.unscaledValue", args => args(1) match
      case ForeignV(bd: JBD) => BigV(BigInt(bd.unscaledValue()))
      case _ => sys.error(s"__method__: no dispatch for .unscaledValue on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.compareTo", args => args(1) match
      case ForeignV(x: JBD) => args(2) match
        case ForeignV(y: JBD) => IntV(x.compareTo(y))
        case IntV(n)          => IntV(x.compareTo(JBD.valueOf(n)))
        case v                => IntV(x.compareTo(toBD(v)))
      case _ => sys.error(s"__method__: no dispatch for .compareTo on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.abs", args => args(1) match
      case ForeignV(bd: JBD) => fromBD(bd.abs())
      case _ => sys.error(s"__method__: no dispatch for .abs on ${args(1)}")
    )
    // Arithmetic on Decimal: __arith__ dispatches "+","-","*","/" etc.
    V2PluginRegistry.register("decimal.add", args => fromBD(toBD(args(0)).add(toBD(args(1)))))
    V2PluginRegistry.register("decimal.sub", args => fromBD(toBD(args(0)).subtract(toBD(args(1)))))
    V2PluginRegistry.register("decimal.mul", args => fromBD(toBD(args(0)).multiply(toBD(args(1)))))
    V2PluginRegistry.register("decimal.div", args => {
      val (a, b) = (toBD(args(0)), toBD(args(1)))
      fromBD(a.divide(b, a.scale().max(b.scale()).max(10), JRM.HALF_UP))
    })
    V2PluginRegistry.register("decimal.lt",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) < 0))
    V2PluginRegistry.register("decimal.le",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) <= 0))
    V2PluginRegistry.register("decimal.gt",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) > 0))
    V2PluginRegistry.register("decimal.ge",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) >= 0))
    V2PluginRegistry.register("decimal.eq",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) == 0))

  /** Register dispatch for ComputedCell.get and Storage.get. */
  private def registerComputedCellDispatch(): Unit =
    V2PluginRegistry.register("__method__.get", args => {
      // args = [StrV("get"), recv, ...margs]
      if args.length >= 2 then args(1) match
        case V2Value.ForeignV(cell: ComputedCell) => cell.get()
        case V2Value.DataV("Storage", _) =>
          val key = if args.length >= 3 then args(2) match { case V2Value.StrV(s) => s; case v => v.toString } else ""
          val map = storageLocal.get()
          if map == null then sys.error("Storage.get called outside runEphemeralStorage / runStorage")
          val sv = map.getOrElse(key, null)
          if sv != null then V2Value.DataV("Some", Vector(V2Value.StrV(sv)))
          else V2Value.DataV("None", Vector.empty)
        // Named-field instance objects (v1 InstanceV riding as NamedMethodObj —
        // e.g. std/agent's parsed chat message): .get(key) reads the FIELD.
        case V2Value.ForeignV(nmo: ssc.Value.NamedMethodObj) if args.length >= 3 =>
          val key = args(2) match { case V2Value.StrV(s) => s; case v => v.toString }
          val r = nmo.getField(key).getOrElse(V2Value.DataV("None", Vector.empty))
          if System.getenv("SSC_DEBUG_DISPATCH") != null then
            System.err.println(s"[nmo.get] key=$key -> ${r.getClass.getSimpleName} inner=${r match { case V2Value.ForeignV(h) => h.getClass.getName; case _ => "-" }}")
          r
        case other =>
          if System.getenv("SSC_DEBUG_DISPATCH") != null then
            System.err.println(s"[__method__.get] recv=${other.getClass.getSimpleName} inner=${other match { case V2Value.ForeignV(h) => h.getClass.getName; case _ => "-" }}")
          V2Value.DataV("Stub", Vector.empty)  // stub: .get on unknown/Op value
      else sys.error(s"__method__.get: too few args")
    })
    V2PluginRegistry.register("__method__.put", args => {
      if args.length >= 4 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val key = args(2) match { case V2Value.StrV(s) => s; case v => v.toString }
          val value = args(3) match { case V2Value.StrV(s) => s; case v => v.toString }
          val map = storageLocal.get()
          if map != null then map(key) = value
          V2Value.UnitV
        case _ => V2Value.UnitV  // stub: .put on unknown/Op value
      else if args.length >= 2 then V2Value.UnitV  // too few args for put: treat as no-op
      else sys.error(s"__method__.put: too few args")
    })
    V2PluginRegistry.register("__method__.remove", args => {
      if args.length >= 3 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val key = args(2) match { case V2Value.StrV(s) => s; case v => v.toString }
          val map = storageLocal.get()
          if map != null then map.remove(key)
          V2Value.UnitV
        case _ => V2Value.UnitV  // stub: .remove on unknown value
      else V2Value.UnitV
    })
    V2PluginRegistry.register("__method__.has", args => {
      if args.length >= 3 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val key = args(2) match { case V2Value.StrV(s) => s; case v => v.toString }
          val map = storageLocal.get()
          V2Value.BoolV(map != null && map.contains(key))
        case _ => V2Value.BoolV(false)  // stub: .has on unknown value
      else V2Value.BoolV(false)
    })
    V2PluginRegistry.register("__method__.keys", args => {
      if args.length >= 2 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val map = storageLocal.get()
          val keys = if map != null then map.keys.toList else Nil
          keys.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (k, acc) =>
            V2Value.DataV("Cons", Vector(V2Value.StrV(k), acc))
          }
        case _ => V2Value.DataV("Cons", Vector(V2Value.StrV("stub-key"), V2Value.DataV("Nil", Vector.empty)))
      else V2Value.DataV("Nil", Vector.empty)
    })
    V2PluginRegistry.register("__method__.apply", args => {
      if args.length >= 2 then args(1) match
        case V2Value.ForeignV(cell: ComputedCell) => cell.get()
        case _ => V2Value.DataV("Stub", Vector.empty)  // stub: .apply on unknown value
      else V2Value.DataV("Stub", Vector.empty)
    })

  /** Thread-local in-memory storage map for runEphemeralStorage / runStorage. */
  private val storageLocal: ThreadLocal[scala.collection.mutable.LinkedHashMap[String, String]] =
    new ThreadLocal()

  /** Computed signal: re-evaluates its thunk on every .get call (no reactive tracking). */
  private class ComputedCell(val thunk: V2Value.ClosV):
    def get(): V2Value = callClosure(thunk, Nil)

  private val genQueueTL: java.lang.ThreadLocal[java.util.concurrent.LinkedBlockingQueue[Option[V2Value]]] =
    java.lang.ThreadLocal.withInitial(() => null)

  private def makeGenerator(queue: java.util.concurrent.LinkedBlockingQueue[Option[V2Value]]): V2Value =
    import V2Value.*
    type Q = java.util.concurrent.LinkedBlockingQueue[Option[V2Value]]
    def chainedGen(body: Q => Unit): V2Value =
      val q2 = new Q()
      Thread.ofVirtual().start { () =>
        try body(q2)
        catch case _: Throwable => ()
        finally try q2.put(None) catch case _ => ()
      }
      makeGenerator(q2)
    def listFrom(q: Q): V2Value =
      val buf = collection.mutable.ArrayBuffer.empty[V2Value]
      var item = q.take()
      while item.isDefined do
        buf += item.get
        item = q.take()
      buf.foldRight[V2Value](DataV("Nil", Vector.empty))((v, acc) => DataV("Cons", Vector(v, acc)))
    ForeignV(scala.collection.immutable.Map[String, V2Value](
      "next" -> ClosV(Runtime.emptyEnv, 0, _ => Done(
        queue.take() match
          case Some(v) => DataV("Some", Vector(v))
          case None    => DataV("None", Vector.empty)
      )),
      "toList" -> ClosV(Runtime.emptyEnv, 0, _ => Done(listFrom(queue))),
      "foreach" -> ClosV(Runtime.emptyEnv, 1, env => {
        val f = env.last
        var item = queue.take()
        while item.isDefined do
          callClosure(f, List(item.get))
          item = queue.take()
        Done(UnitV)
      }),
      "map" -> ClosV(Runtime.emptyEnv, 1, env => {
        val f = env.last
        Done(chainedGen { q2 =>
          var item = queue.take()
          while item.isDefined do
            q2.put(Some(callClosure(f, List(item.get))))
            item = queue.take()
        })
      }),
      "filter" -> ClosV(Runtime.emptyEnv, 1, env => {
        val pred = env.last
        Done(chainedGen { q2 =>
          var item = queue.take()
          while item.isDefined do
            if callClosure(pred, List(item.get)) == BoolV(true) then q2.put(Some(item.get))
            item = queue.take()
        })
      }),
      "take" -> ClosV(Runtime.emptyEnv, 1, env => {
        val n = env.last match { case IntV(n) => n.toInt; case _ => 0 }
        Done(chainedGen { q2 =>
          var remaining = n
          var item = queue.take()
          while item.isDefined && remaining > 0 do
            q2.put(Some(item.get))
            remaining -= 1
            item = if remaining > 0 then queue.take() else None
        })
      }),
      "drop" -> ClosV(Runtime.emptyEnv, 1, env => {
        val n = env.last match { case IntV(n) => n.toInt; case _ => 0 }
        Done(chainedGen { q2 =>
          var toDrop = n
          var item = queue.take()
          while item.isDefined && toDrop > 0 do
            toDrop -= 1
            item = queue.take()
          while item.isDefined do
            q2.put(Some(item.get))
            item = queue.take()
        })
      }),
      "flatMap" -> ClosV(Runtime.emptyEnv, 1, env => {
        val f = env.last
        Done(chainedGen { q2 =>
          var item = queue.take()
          while item.isDefined do
            val subGen = callClosure(f, List(item.get))
            // drain the sub-generator into q2
            subGen match
              case ForeignV(m: collection.immutable.Map[?, ?]) =>
                val mm = m.asInstanceOf[collection.immutable.Map[String, V2Value]]
                mm.get("toList") match
                  case Some(fn: ClosV) =>
                    val lst = Runtime.run(fn.code, if fn.env.isEmpty then Array.empty else fn.env)
                    def drainList(v: V2Value): Unit = v match
                      case DataV("Cons", elems) => q2.put(Some(elems(0))); drainList(elems(1))
                      case _ => ()
                    drainList(lst)
                  case _ => ()
              case _ => ()
            item = queue.take()
        })
      }),
      "zip" -> ClosV(Runtime.emptyEnv, 1, env => {
        val other = env.last
        Done(chainedGen { q2 =>
          // get next from other generator via its next method
          def otherNext(): Option[V2Value] = other match
            case ForeignV(m: collection.immutable.Map[?, ?]) =>
              val mm = m.asInstanceOf[collection.immutable.Map[String, V2Value]]
              mm.get("next") match
                case Some(fn: ClosV) =>
                  Runtime.run(fn.code, if fn.env.isEmpty then Array.empty else fn.env) match
                    case DataV("Some", elems) => Some(elems(0))
                    case _ => None
                case _ => None
            case _ => None
          var item = queue.take()
          var running = true
          while item.isDefined && running do
            otherNext() match
              case Some(r) =>
                q2.put(Some(DataV("Tuple2", Vector(item.get, r))))
                item = queue.take()
              case None => running = false
        })
      }),
      "zipWithIndex" -> ClosV(Runtime.emptyEnv, 0, _ => {
        Done(chainedGen { q2 =>
          var idx = 0L
          var item = queue.take()
          while item.isDefined do
            q2.put(Some(DataV("Tuple2", Vector(item.get, IntV(idx)))))
            idx += 1
            item = queue.take()
        })
      }),
    ).asInstanceOf[AnyRef])

  /** Register `sys` global: sys.env(key) / sys.env.getOrElse(k,d) / sys.exit(code). */
  private def registerSys(): Unit =
    import V2Value.*
    if V2PluginRegistry.lookupGlobal("sys").isDefined then return
    // envGetOrElse: called when sys.env.getOrElse(key, default) is used
    val envGetOrElse = ClosV(Runtime.emptyEnv, 2, env => {
      val key = env(env.length - 2) match { case StrV(s) => s; case v => v.toString }
      val dflt = env.last
      Done(Option(System.getenv(key)).map(StrV.apply).getOrElse(dflt))
    })
    val envGet = ClosV(Runtime.emptyEnv, 1, env => {
      val key = env.last match { case StrV(s) => s; case v => v.toString }
      Done(Option(System.getenv(key)).map(s => DataV("Some", Vector(StrV(s)))).getOrElse(DataV("None", Vector.empty)))
    })
    // envObj: sys.env alone → ForeignV(Map) with getOrElse/get methods for chaining
    val envObj = ForeignV(scala.collection.immutable.Map[String, V2Value](
      "getOrElse" -> envGetOrElse,
      "get"       -> envGet,
    ).asInstanceOf[AnyRef])
    // Register __method__.env handler: called when sys.env("KEY") or sys.env is accessed
    // (Runtime.scala ForeignV(Map) dispatch falls through to plugin when value is a ForeignV)
    V2PluginRegistry.register("__method__.env", args => {
      val margs = args.drop(2)  // args = [name, recv, ...margs]
      margs match
        case Nil           => envObj  // sys.env alone → envObj for chaining
        case List(StrV(k)) => Option(System.getenv(k)).map(StrV.apply).getOrElse(DataV("None", Vector.empty))
        case _             => DataV("None", Vector.empty)
    })
    val sysObj = ForeignV(scala.collection.immutable.Map[String, V2Value](
      "env"  -> envObj,  // 0-arg: sys.env → envObj; "KEY"-arg: handled by __method__.env plugin
      "exit" -> ClosV(Runtime.emptyEnv, 1, env => {
        ssc.Runtime.exitHandler(env.last match { case IntV(n) => n.toInt; case _ => 0 })
        Done(UnitV)
      }),
    ).asInstanceOf[AnyRef])
    V2PluginRegistry.registerGlobal("sys", sysObj)

  /** `runStream { body }` — v1 keeps this runner in interpreter CORE (not a
   *  BlockForm), so the auto-bridge never picks it up. Semantics: run the body
   *  under a "Stream" effect context; `emit` collects, `complete()` aborts the
   *  rest of the body; result = (Source, bodyResult) where Source exposes
   *  runToList(). Relies on Op statement-threading (84503577e) for emits in
   *  statement position. */
  private final class StreamComplete extends RuntimeException
  /** route/serveAsync/stop — the REAL v1 web server driven from v2
   *  (Phase-3: `ssc run --v2` on a route() program must serve for real).
   *  Handlers are v2 closures wrapped as v1 NativeFnV (v2ToV1); the route
   *  registry + InterpreterHttpHandler invoke them via callValue, which is
   *  interpreter-state-free for NativeFnV — a minimal Interpreter instance
   *  satisfies the registry's interp parameter. serveAsync BLOCKS until the
   *  socket is bound (onBound latch) so a client in the next statement can
   *  connect immediately. */
  private lazy val routeInterp = new scalascript.interpreter.Interpreter()
  private def registerWebServer(): Unit =
    import V2Value.*
    def doRegisterRoute(method: V2Value, path: V2Value, h: V2Value): Unit =
      (method, path, h) match
        case (StrV(m), StrV(pth), c: ClosV) =>
          scalascript.server.Routes.register(m, pth, v2ToV1(c), routeInterp)
        case _ => sys.error("route(method, path, handler)")
    V2PluginRegistry.registerGlobal("route", ClosV(Runtime.emptyEnv, -1, env => {
      // Both call shapes: route(m, p, h) and CURRIED route(m, p) { h }
      if env.length >= 3 then { doRegisterRoute(env(0), env(1), env(2)); Done(UnitV) }
      else
        val m = env(0); val pth = env(1)
        Done(ClosV(Runtime.emptyEnv, 1, env2 => { doRegisterRoute(m, pth, env2.last); Done(UnitV) }))
    }))
    V2PluginRegistry.registerGlobal("serveAsync", ClosV(Runtime.emptyEnv, -1, env => {
      val port = env(0) match { case IntV(n) => n.toInt; case _ => 8080 }
      val bound = new java.util.concurrent.CountDownLatch(1)
      // onBound fires BEFORE the banner prints (WebServer.start:132 vs 135) —
      // waiting on the bind alone raced the banner with the caller's next
      // println. Count the three banner lines through a log wrapper so
      // serveAsync returns only after the banner is fully out (v1 ordering).
      val bannerDone = new java.util.concurrent.CountDownLatch(3)
      val logWrap = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true) {
        override def println(s: String): Unit = {
          Console.out.println(s)
          Console.out.flush()
          bannerDone.countDown()
        }
      }
      val th = new Thread(() => {
        try scalascript.server.WebServer.start(port, ".", logWrap, onBound = () => bound.countDown())
        catch case e: Throwable =>
          System.err.println(s"serveAsync: ${e.getMessage}")
          bound.countDown(); while bannerDone.getCount > 0 do bannerDone.countDown()
      }, "ssc-v2-web")
      th.setDaemon(true)
      th.start()
      bound.await(10, java.util.concurrent.TimeUnit.SECONDS)
      bannerDone.await(2, java.util.concurrent.TimeUnit.SECONDS)
      Done(UnitV)
    }))
    V2PluginRegistry.registerGlobal("stop", ClosV(Runtime.emptyEnv, -1, _ => {
      try scalascript.server.WebServer.stop() catch case _: Throwable => ()
      Done(UnitV)
    }))

  /** runAsync { body } / runAsyncParallel { body } — Async effect runners
   *  (async-demo, async-parallel-demo). Ops: async(thunk) → future handle,
   *  await(f) → join, delay(ms), parallel(List[thunk]) → List[result] — run
   *  SEQUENTIALLY under runAsync, CONCURRENTLY under runAsyncParallel (the
   *  demo measures exactly this difference). Child virtual threads re-push
   *  the handler: V2EffectContext is a ThreadLocal. */
  private def registerRunAsync(): Unit =
    import V2Value.*
    if V2PluginRegistry.lookupGlobal("runAsync").isDefined then return
    def unlist(v: V2Value): List[V2Value] =
      val b = List.newBuilder[V2Value]; var cur = v
      var go = true
      while go do cur match
        case DataV("Cons", IndexedSeq(h, tl)) => b += h; cur = tl
        case _ => go = false
      b.result()
    def listOf(xs: List[V2Value]): V2Value =
      xs.foldRight(DataV("Nil", Vector.empty): V2Value)((x, acc) => DataV("Cons", Vector(x, acc)))
    final class Fut(val thread: Thread, val cell: Array[V2Value])
    def mkRunner(par: Boolean): V2Value = ClosV(Runtime.emptyEnv, 1, env => {
      val thunk = env.last match
        case c: ClosV if c.arity == 0 => c
        case other => sys.error(s"runAsync: expected a block, got ${Show.show(other)}")
      lazy val handler: V2EffectContext.EH = (op, args) => op match
        case "delay" =>
          args.head match { case IntV(ms) => Thread.sleep(ms); case _ => () }
          UnitV
        case "async" =>
          val c = args.head.asInstanceOf[ClosV]
          val cell = new Array[V2Value](1)
          val th = Thread.ofVirtual().start { () =>
            V2EffectContext.push("Async", handler)
            try cell(0) = callClosure(c, Nil)
            finally V2EffectContext.pop("Async")
          }
          ForeignV(new Fut(th, cell))
        case "await" =>
          args.head match
            case ForeignV(f: Fut) => f.thread.join(); Option(f.cell(0)).getOrElse(UnitV)
            case other            => other // await on an already-plain value
        case "parallel" =>
          val thunks = unlist(args.head).collect { case c: ClosV => c }
          if par then
            val futs = thunks.map { c =>
              val cell = new Array[V2Value](1)
              val th = Thread.ofVirtual().start { () =>
                V2EffectContext.push("Async", handler)
                try cell(0) = callClosure(c, Nil)
                finally V2EffectContext.pop("Async")
              }
              (th, cell)
            }
            listOf(futs.map { (th, cell) => th.join(); Option(cell(0)).getOrElse(UnitV) })
          else listOf(thunks.map(c => callClosure(c, Nil)))
        case other => sys.error(s"runAsync: unsupported Async.$other")
      V2EffectContext.push("Async", handler)
      val result = try callThunk(thunk) finally V2EffectContext.pop("Async")
      Done(result)
    })
    V2PluginRegistry.registerGlobal("runAsync", mkRunner(par = false))
    V2PluginRegistry.registerGlobal("runAsyncParallel", mkRunner(par = true))

  /** ```yaml section fences → parsed value for `<SectionId>.yaml` (mirrors v1
   *  SectionRuntime.runYamlBlock: SimpleYaml + yamlAnyToValue; no plugin import
   *  needed). Registered as a prim; the frontend emits __yamlSection__("raw"). */
  private def registerYamlSection(): Unit =
    V2PluginRegistry.register("__yamlSection__", args => args match
      case List(V2Value.StrV(raw)) =>
        // Local replica of v1 SectionRuntime.yamlAnyToValue (it is private there).
        import scalascript.interpreter.Value as V1V
        def conv(a: Any): V1V =
          import scala.jdk.CollectionConverters.*
          a match
            case null                  => V1V.InstanceV("YNull", Map.empty)
            case b: java.lang.Boolean  => V1V.InstanceV("YBool", Map("value" -> V1V.boolV(b)))
            case i: java.lang.Integer  => V1V.InstanceV("YNum",  Map("value" -> V1V.DoubleV(i.toDouble)))
            case l: java.lang.Long     => V1V.InstanceV("YNum",  Map("value" -> V1V.DoubleV(l.toDouble)))
            case d: java.lang.Double   => V1V.InstanceV("YNum",  Map("value" -> V1V.DoubleV(d)))
            case f: java.lang.Float    => V1V.InstanceV("YNum",  Map("value" -> V1V.DoubleV(f.toDouble)))
            case s: String             => V1V.InstanceV("YStr",  Map("value" -> V1V.StringV(s)))
            case m: java.util.Map[?, ?] =>
              val fields = m.asScala.map { case (k, v) => V1V.StringV(k.toString).asInstanceOf[V1V] -> conv(v) }.toMap
              V1V.InstanceV("YObj", Map("fields" -> V1V.MapV(fields)))
            case l: java.util.List[?]  =>
              V1V.InstanceV("YArr", Map("items" -> V1V.ListV(l.asScala.toList.map(conv))))
            case other                 => V1V.InstanceV("YStr", Map("value" -> V1V.StringV(other.toString)))
        val v1parsed: V1Value =
          try conv(scalascript.parser.SimpleYaml.load[Any](raw))
          catch case _: Throwable => scalascript.interpreter.Value.InstanceV("YNull", Map.empty)
        V2Value.ForeignV(v1parsed)
      case _ => sys.error("__yamlSection__(raw)"))

  private def registerRunStream(): Unit =
    if V2PluginRegistry.lookupGlobal("runStream").isDefined then return
    V2PluginRegistry.registerGlobal("runStream", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val thunk = env.last match
        case t: V2Value.ClosV if t.arity == 0 => t
        case other => sys.error(s"runStream: expected a block, got ${Show.show(other)}")
      val buf = scala.collection.mutable.ListBuffer[V2Value]()
      val handler: V2EffectContext.EH = (op, args) => op match
        case "emit"     => buf += args.head; V2Value.UnitV
        case "complete" => throw new StreamComplete
        case other      => sys.error(s"runStream: unsupported Stream.$other")
      V2EffectContext.push("Stream", handler)
      val bodyResult =
        try callThunk(thunk)
        catch { case _: StreamComplete => V2Value.UnitV }
        finally V2EffectContext.pop("Stream")
      val items = buf.toList.foldRight(V2Value.DataV("Nil", Vector.empty): V2Value)(
        (x, acc) => V2Value.DataV("Cons", Vector(x, acc)))
      val source = V2Value.ForeignV(scala.collection.immutable.Map[String, V2Value](
        "runToList" -> V2Value.ClosV(Runtime.emptyEnv, 0, _ => Done(items)),
      ).asInstanceOf[AnyRef])
      Done(V2Value.DataV("Tuple2", Vector(source, bodyResult)))
    }))

  private def registerHandle(): Unit =
    if V2PluginRegistry.lookupGlobal("handle").isDefined then return // idempotent
    // handle(bodyResult)(handlerFn) — curried, 2 sequential arity-1 applications
    val handleClosure = V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val bodyResult = env.last  // Local(0) = most recent arg
      // Inner closure captures bodyResult; when called with handler:
      // extend([bodyResult], [handler]) = [bodyResult, handler]
      // Local(0) = handler = env2.last; Local(1) = bodyResult = env2(0)
      Done(V2Value.ClosV(Array(bodyResult), 1, env2 => {
        // `handle { expr }` may arrive as a 0-arg THUNK (block-arg convention) —
        // force it before entering the loop, else Return(thunk) leaks a closure.
        val body = env2(0) match
          case t: V2Value.ClosV if t.arity == 0 => callClosure(t, Nil)
          case v => v
        val handler = env2.last  // new arg = handlerFn
        Done(runEffectLoop(body, handler))
      }))
    })
    V2PluginRegistry.registerGlobal("handle", handleClosure)
    // "effect" is TWO things: `effect Foo:` declarations (no-op — FrontendBridge
    // emits a marker call) and `effect { … }` REACTIVE blocks (signals-demo):
    // run the thunk once while TRACKING signal reads, subscribe it to every
    // cell it read, and re-run it in ONE flush per write (LinkedHashSet — the
    // diamond of count+computed(count) must rerun the effect once, not twice).
    val subscribers = new java.util.IdentityHashMap[AnyRef, java.util.LinkedHashSet[V2Value.ClosV]]()
    def runTracked(thunk: V2Value.ClosV): Unit =
      val reads = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]())
      Runtime.signalReadHook.set(cell => { reads.add(cell); () })
      try callClosure(thunk, Nil)
      finally Runtime.signalReadHook.set(null)
      reads.forEach { cell =>
        subscribers.computeIfAbsent(cell, _ => new java.util.LinkedHashSet[V2Value.ClosV]()).add(thunk)
      }
    Runtime.signalWriteHook = cell => {
      val subs = subscribers.get(cell)
      if subs != null then
        // snapshot: re-tracking during the flush may re-subscribe
        val flush = new java.util.LinkedHashSet[V2Value.ClosV](subs)
        flush.forEach(th => runTracked(th))
    }
    V2PluginRegistry.registerGlobal("effect", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      env.last match
        case thunk: V2Value.ClosV if thunk.arity == 0 => runTracked(thunk)
        case _                                        => () // effect-declaration marker
      Done(V2Value.UnitV)
    }))

  /** Run the Free-monad interpreter loop.
   *  - Op("EffTag.opName", arg, k): call handler with DataV(opName, [args…, resumeFn])
   *    (a multi-arg op arrives packed as __EffArgs__ — see Runtime's effect dispatch —
   *    and is delivered unpacked so `case op(a, b, resume)` arms match)
   *  - anything else:                call handler with DataV("Return", [v]) */
  private def runEffectLoop(v: V2Value, handler: V2Value): V2Value = v match
    case V2Value.DataV("Op", IndexedSeq(V2Value.StrV(label), arg, k)) =>
      val opName = label.split("\\.").last  // "Logger.log" → "log"
      // resumeFn captures k and handler; when called with r:
      // extend([k, handler], [r]) = [k, handler, r] → Local(0)=r=env2.last, Local(1)=handler=env2(1), Local(2)=k=env2(0)
      val resumeFn = V2Value.ClosV(Array(k.asInstanceOf[V2Value], handler), 1, env2 => {
        val resumeArg = env2.last // Local(0) = new arg r
        val kk        = env2(0)   // captured k (first in base env)
        val h         = env2(1)   // captured handler (second in base env)
        val next = callClosure(kk, List(resumeArg))
        Done(runEffectLoop(next, h))
      })
      val margs = arg match
        case V2Value.UnitV                       => List(resumeFn)
        case V2Value.DataV("__EffArgs__", fs)    => fs.toList :+ resumeFn
        case _                                   => List(arg, resumeFn)
      callClosure(handler, List(V2Value.DataV(opName, margs.toVector)))
    case _ =>
      // Pure result — call handler with Return(v). A handler WITHOUT a Return
      // clause passes the value through unchanged (v1 semantics: the return
      // clause is optional).
      try callClosure(handler, List(V2Value.DataV("Return", Vector(v))))
      catch
        case e: RuntimeException if e.getMessage != null
            && e.getMessage.startsWith("match: no arm for Return") => v

  // ── Actor runtime — VirtualThread-per-actor model ───────────────────────────

  private class ActorRunState:
    private val lock = new Object
    private val actors = java.util.concurrent.ConcurrentHashMap.newKeySet[ActorMailbox]()
    private val timers = new java.util.concurrent.atomic.AtomicInteger(0)

    def addActor(mb: ActorMailbox): Unit =
      actors.add(mb)
      signal()

    def startTimer(): Unit =
      timers.incrementAndGet()
      signal()

    def finishTimer(): Unit =
      timers.decrementAndGet()
      signal()

    def signal(): Unit =
      lock.synchronized(lock.notifyAll())

    private def isQuiescent: Boolean =
      if timers.get() != 0 then false
      else
        val it = actors.iterator()
        var done = true
        while done && it.hasNext do
          val mb = it.next()
          done = mb.dead || (mb.blocked && mb.queue.isEmpty && mb.blockDeadlineMs < 0L)
        done

    def awaitQuiescent(): Unit =
      lock.synchronized:
        while !isQuiescent do lock.wait(50L)

  private final class ActorTimer(val id: Long, val scope: ActorRunState | Null):
    @volatile var cancelled: Boolean = false
    @volatile var thread: Thread | Null = null
    val finished = new java.util.concurrent.atomic.AtomicBoolean(false)

  private val nextActorTimerId = new java.util.concurrent.atomic.AtomicLong(1L)
  private val actorTimers = new java.util.concurrent.ConcurrentHashMap[Long, ActorTimer]()

  /** One actor's mailbox + thread state. */
  private class ActorMailbox:
    val queue = new java.util.concurrent.LinkedBlockingQueue[V2Value]()
    @volatile var thread: Thread | Null = null
    @volatile var dead: Boolean = false
    @volatile var trapExit: Boolean = false  // Erlang-style: exits become messages
    @volatile var blocked: Boolean = false
    @volatile var blockDeadlineMs: Long = -1L
    @volatile var scope: ActorRunState | Null = null
    // Supervision: links are notified with Exit(reason), monitors with Down(reason)
    val links    = java.util.concurrent.ConcurrentHashMap.newKeySet[ActorMailbox]()
    val monitors = java.util.concurrent.ConcurrentHashMap.newKeySet[ActorMailbox]()

  private def signalActor(mb: ActorMailbox): Unit =
    val scope = mb.scope
    if scope != null then scope.signal()

  private def markBlocked(mb: ActorMailbox, deadlineMs: Long): Unit =
    mb.blocked = true
    mb.blockDeadlineMs = deadlineMs
    signalActor(mb)

  private def clearBlocked(mb: ActorMailbox): Unit =
    mb.blocked = false
    mb.blockDeadlineMs = -1L
    signalActor(mb)

  private def takeActorMessage(mb: ActorMailbox): V2Value =
    val immediate = mb.queue.poll()
    if immediate != null then immediate
    else
      markBlocked(mb, -1L)
      try mb.queue.take()
      finally clearBlocked(mb)

  private def pollActorMessage(mb: ActorMailbox, timeoutMs: Long): V2Value | Null =
    val immediate = mb.queue.poll()
    if immediate != null then immediate
    else if timeoutMs < 0 then takeActorMessage(mb)
    else
      markBlocked(mb, System.currentTimeMillis() + timeoutMs)
      try mb.queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      finally clearBlocked(mb)

  private def deliverActorMessage(mb: ActorMailbox, msg: V2Value): Unit =
    if !mb.dead then
      mb.queue.put(msg)
      signalActor(mb)

  private def finishActorTimer(timer: ActorTimer): Unit =
    if timer.finished.compareAndSet(false, true) then
      actorTimers.remove(timer.id, timer)
      val scope = timer.scope
      if scope != null then scope.finishTimer()

  private def scheduleActorSend(delayMs: Long, target: ActorMailbox, msg: V2Value, repeat: Boolean): V2Value =
    val current = actorTL.get()
    val scope =
      if target.scope != null then target.scope
      else if current != null then current.scope
      else null
    val id = nextActorTimerId.getAndIncrement()
    val timer = new ActorTimer(id, scope)
    actorTimers.put(id, timer)
    if scope != null then scope.startTimer()
    val period = math.max(0L, delayMs)
    val t = Thread.ofVirtual().start(() => {
      try
        if repeat then
          while !timer.cancelled && !target.dead do
            Thread.sleep(period)
            if !timer.cancelled && !target.dead then deliverActorMessage(target, msg)
        else
          Thread.sleep(period)
          if !timer.cancelled && !target.dead then deliverActorMessage(target, msg)
      catch case _: InterruptedException => ()
      finally finishActorTimer(timer)
      ()
    })
    timer.thread = t
    V2Value.IntV(id)

  private def cancelActorTimer(id: Long): Unit =
    val timer = actorTimers.get(id)
    if timer != null then
      timer.cancelled = true
      val t = timer.thread
      if t != null then t.interrupt()
      finishActorTimer(timer)

  /** Notify links + monitors that `mb` terminated with `reason` (Erlang semantics:
   *  a trapping link gets Exit(reason) as a message; a non-trapping link is killed;
   *  monitors always get a Down(reason) message). */
  private def notifyDeath(mb: ActorMailbox, reason: V2Value): Unit =
    mb.links.forEach { l =>
      if !l.dead then
        if l.trapExit then deliverActorMessage(l, V2Value.DataV("Exit", Vector(reason)))
        else { l.dead = true; val t = l.thread; if t != null then t.interrupt(); signalActor(l) }
    }
    mb.monitors.forEach { m =>
      if !m.dead then deliverActorMessage(m, V2Value.DataV("Down", Vector(reason)))
    }
    mb.links.clear(); mb.monitors.clear()
    signalActor(mb)

  private val actorTL = new ThreadLocal[ActorMailbox | Null]:
    override def initialValue(): ActorMailbox | Null = null


  /** Register actor globals: spawn, receive, self, exit, runActors.
   *  `!` is dispatched via __arith__("!", actorRef, msg) in Runtime.scala's
   *  dispatch table (separate patch), so it is wired via V2PluginRegistry.register. */
  /** Build a receive-with-timeout closure. Returns a ClosV(arity=1) that takes a handler
   *  and either calls it with the message (returning Some(result)) or returns None on timeout. */
  private def receiveWithTimeout(ms: Long): V2Value =
    V2Value.ClosV(Array(V2Value.IntV(ms)), 1, env2 => {
      val handler = env2.last
      val timeoutMs = env2(0).asInstanceOf[V2Value.IntV].n
      val mb = actorTL.get()
      if mb == null then sys.error("receive(timeout) called outside an actor")
      val msg = pollActorMessage(mb, timeoutMs)
      if msg == null || mb.dead then Done(V2Value.DataV("None", Vector.empty))
      else Done(V2Value.DataV("Some", Vector(callClosure(handler.asInstanceOf[V2Value.ClosV], List(msg)))))
    })

  /** Read the @timeout cell registered by registerActors. Returns -1 if unset. */
  private def readTimeoutMs(): Long =
    V2PluginRegistry.lookupGlobal("@timeout") match
      case Some(V2Value.ForeignV(arr: Array[V2Value] @unchecked)) =>
        arr(0) match { case V2Value.IntV(n) => n; case _ => -1L }
      case _ => -1L

  private def registerActors(): Unit =
    if V2PluginRegistry.lookupGlobal("runActors").isDefined then return
    // Register @timeout cell (used by receive(timeout = n) named-arg pattern)
    val timeoutCell = Array[V2Value](V2Value.IntV(-1))
    V2PluginRegistry.registerGlobal("@timeout", V2Value.ForeignV(timeoutCell.asInstanceOf[AnyRef]))
    V2PluginRegistry.registerFieldNames("CodeIdentity", Vector("algorithm", "digest", "format", "module"))
    V2PluginRegistry.registerFieldNames("SeedResolver", Vector("kind", "urls", "serviceName", "namespace", "port", "scheme"))
    V2PluginRegistry.registerFieldNames("ClusterCapability", Vector("localNodeId", "peers", "authToken", "seedResolver", "codeIdentity"))
    val nodeIds   = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    val behaviors = new java.util.concurrent.ConcurrentHashMap[String, V2Value.ClosV]()

    def v2List(items: Iterable[V2Value]): V2Value =
      items.toList.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (x, acc) =>
        V2Value.DataV("Cons", Vector(x, acc))
      }

    def strField(fields: IndexedSeq[V2Value], idx: Int, default: String): String =
      fields.lift(idx) match
        case Some(V2Value.StrV(s)) => s
        case _                     => default

    def intField(fields: IndexedSeq[V2Value], idx: Int, default: Long): Long =
      fields.lift(idx) match
        case Some(V2Value.IntV(n)) => n
        case _                     => default

    def listStrings(v: V2Value): List[String] =
      val out = collection.mutable.ListBuffer.empty[String]
      var cur = v
      var done = false
      while !done do cur match
        case V2Value.DataV("Cons", IndexedSeq(V2Value.StrV(s), tail)) =>
          out += s
          cur = tail
        case V2Value.DataV("Nil", _) =>
          done = true
        case _ =>
          done = true
      out.toList

    def seedResolver(
        kind: String,
        urls: List[String],
        serviceName: String = "",
        namespace: String = "default",
        port: Long = 9100L,
        scheme: String = "ws"
    ): V2Value =
      V2Value.DataV("SeedResolver", Vector(
        V2Value.StrV(kind),
        v2List(urls.map(V2Value.StrV.apply)),
        V2Value.StrV(serviceName),
        V2Value.StrV(namespace),
        V2Value.IntV(port),
        V2Value.StrV(scheme)
      ))

    def defaultSeedResolver(): V2Value =
      seedResolver("static", Nil)

    def resolveSeedValue(seed: V2Value): V2Value =
      seed match
        case V2Value.DataV("SeedResolver", fields) =>
          val kind = strField(fields, 0, "")
          val urls = fields.lift(1).map(listStrings).getOrElse(Nil)
          def actorUrl(addr: java.net.InetAddress, port: Long, scheme: String): String =
            val host = addr.getHostAddress
            val bracketed = if host.contains(":") && !host.startsWith("[") then s"[$host]" else host
            s"$scheme://$bracketed:$port/_ssc-actors"
          kind match
            case "static" =>
              v2List(urls.map(V2Value.StrV.apply))
            case "dnsSrv" =>
              val serviceName = strField(fields, 2, "")
              val port = intField(fields, 4, 9100L)
              val scheme = strField(fields, 5, "ws")
              if serviceName.isEmpty then sys.error("resolveSeeds: dnsSrv serviceName is empty")
              val resolved =
                try java.net.InetAddress.getAllByName(serviceName).toList.map(actorUrl(_, port, scheme))
                catch case e: java.net.UnknownHostException => sys.error(s"resolveSeeds: DNS lookup failed for $serviceName: ${e.getMessage}")
              v2List(resolved.map(V2Value.StrV.apply))
            case "k8sHeadlessService" =>
              val serviceName = strField(fields, 2, "")
              val namespace = strField(fields, 3, "default")
              val host =
                if namespace.isEmpty || namespace == "." then serviceName
                else s"$serviceName.$namespace.svc"
              val port = intField(fields, 4, 9100L)
              val scheme = strField(fields, 5, "ws")
              if serviceName.isEmpty then sys.error("resolveSeeds: k8sHeadlessService serviceName is empty")
              val resolved =
                try java.net.InetAddress.getAllByName(host).toList.map(actorUrl(_, port, scheme))
                catch case e: java.net.UnknownHostException => sys.error(s"resolveSeeds: Kubernetes headless-service DNS lookup failed for $host: ${e.getMessage}")
              v2List(resolved.map(V2Value.StrV.apply))
            case "consulCatalog" =>
              sys.error("resolveSeeds: consulCatalog resolver is declared but not implemented in the interpreter runtime yet")
            case other =>
              sys.error(s"resolveSeeds: unsupported seed resolver: $other")
        case _ =>
          sys.error("resolveSeeds(seedResolver)")

    val localNodeId = new java.util.concurrent.atomic.AtomicReference[String]("local")
    def currentPeers(): V2Value =
      val peers = collection.mutable.ListBuffer.empty[V2Value]
      val local = localNodeId.get()
      val it = nodeIds.iterator()
      while it.hasNext do
        val id = it.next()
        if id != local then peers += V2Value.StrV(id)
      v2List(peers)

    def clusterCapability(seed: V2Value): V2Value =
      V2Value.DataV("ClusterCapability", Vector(
        V2Value.StrV(localNodeId.get()),
        currentPeers(),
        V2Value.DataV("None", Vector.empty),
        seed,
        currentCodeIdentity
      ))

    def sameCodeIdentity(left: V2Value, right: V2Value): Boolean =
      (left, right) match
        case (V2Value.DataV("CodeIdentity", a), V2Value.DataV("CodeIdentity", b)) =>
          a.lift(0) == b.lift(0) && a.lift(1) == b.lift(1) && a.lift(2) == b.lift(2)
        case _ => false

    V2PluginRegistry.registerGlobal("SeedResolver", V2Value.ForeignV(collection.immutable.Map[String, V2Value](
      "staticList" -> V2Value.ClosV(Runtime.emptyEnv, 1, env => Done(seedResolver("static", listStrings(env.last)))),
      "dnsSrv" -> V2Value.ClosV(Runtime.emptyEnv, -1, env => Done(seedResolver(
        "dnsSrv",
        Nil,
        env.headOption.collect { case V2Value.StrV(s) => s }.getOrElse(""),
        "default",
        env.lift(1).collect { case V2Value.IntV(n) => n }.getOrElse(9100L),
        env.lift(2).collect { case V2Value.StrV(s) => s }.getOrElse("ws")
      ))),
      "k8sHeadlessService" -> V2Value.ClosV(Runtime.emptyEnv, -1, env => Done(seedResolver(
        "k8sHeadlessService",
        Nil,
        env.headOption.collect { case V2Value.StrV(s) => s }.getOrElse(""),
        env.lift(1).collect { case V2Value.StrV(s) => s }.getOrElse("default"),
        env.lift(2).collect { case V2Value.IntV(n) => n }.getOrElse(9100L),
        env.lift(3).collect { case V2Value.StrV(s) => s }.getOrElse("ws")
      ))),
      "consulCatalog" -> V2Value.ClosV(Runtime.emptyEnv, -1, env => Done(seedResolver(
        "consulCatalog",
        env.lift(1).collect { case V2Value.StrV(s) => List(s) }.getOrElse(List("localhost:8500")),
        env.headOption.collect { case V2Value.StrV(s) => s }.getOrElse("")
      )))
    ).asInstanceOf[AnyRef]))

    V2PluginRegistry.registerGlobal("clusterOf", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      Done(clusterCapability(env.headOption.getOrElse(defaultSeedResolver())))
    }))
    V2PluginRegistry.registerGlobal("resolveSeeds", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      Done(resolveSeedValue(env.last))
    }))
    V2PluginRegistry.registerGlobal("codeIdentity", V2Value.ClosV(Runtime.emptyEnv, 0, _ => {
      Done(currentCodeIdentity)
    }))
    V2PluginRegistry.registerGlobal("assertCodeIdentity", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      if sameCodeIdentity(env.last, currentCodeIdentity) then Done(V2Value.UnitV)
      else sys.error("code identity mismatch")
    }))
    V2PluginRegistry.register("__method__.resolveSeeds", args =>
      if args.length >= 2 then args(1) match
        case V2Value.DataV("ClusterCapability", fields) =>
          fields.lift(3).map(resolveSeedValue).getOrElse(V2Value.DataV("Nil", Vector.empty))
        case seed @ V2Value.DataV("SeedResolver", _) =>
          resolveSeedValue(seed)
        case _ =>
          V2Value.DataV("Stub", Vector(V2Value.StrV("resolveSeeds")))
      else V2Value.DataV("Stub", Vector(V2Value.StrV("resolveSeeds")))
    )
    V2PluginRegistry.register("__method__.sameCodeAs", args =>
      if args.length >= 3 then V2Value.BoolV(sameCodeIdentity(args(1), args(2)))
      else V2Value.BoolV(false)
    )

    // spawn { () => body } — starts a VirtualThread, returns ForeignV(mailbox)
    V2PluginRegistry.registerGlobal("spawn", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val thunk = env.last
      val parent = actorTL.get()
      val scope = if parent != null then parent.scope else null
      val mb = new ActorMailbox()
      mb.scope = scope
      if scope != null then scope.addActor(mb)
      val t = Thread.ofVirtual().start(() => {
        actorTL.set(mb)
        mb.thread = Thread.currentThread()
        var reason: V2Value = V2Value.StrV("normal")
        try callThunk(thunk.asInstanceOf[V2Value.ClosV])
        catch
          case _: InterruptedException => reason = V2Value.StrV("killed")
          case e: Throwable =>
            reason = V2Value.StrV(Option(e.getMessage).getOrElse("error"))
            // An actor dying with an error is otherwise INVISIBLE (the reason
            // only travels to linked actors) — a dead worker leaves its
            // collector blocked in receive forever with no diagnostic.
            if sys.env.contains("SSC_DEBUG_ACTORS") then
              System.err.println(s"[actor-death] ${Option(e.getMessage).getOrElse(e.toString)}")
              e.getStackTrace.take(4).foreach(f => System.err.println(s"  at $f"))
        finally
          mb.dead = true
          notifyDeath(mb, reason)
          actorTL.remove()
        ()
      })
      mb.thread = t
      Done(V2Value.ForeignV(mb))
    }))

    // receive { case msg => ... } — blocks on mailbox, calls handler with msg
    // receive(timeout = n) { case msg => ... } — with timeout (returns Some/None)
    V2PluginRegistry.registerGlobal("receive", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val arg = env.last
      arg match
        case handler: V2Value.ClosV =>
          // Direct receive: receive { case msg => ... }
          val mb = actorTL.get()
          if mb == null then sys.error("receive called outside an actor")
          val msg = takeActorMessage(mb)
          if mb.dead then Done(V2Value.UnitV) else Done(callClosure(handler, List(msg)))
        case V2Value.UnitV =>
          // receive(timeout = n) {...}: named arg emits cell.set(@timeout, n) → UnitV
          // read the @timeout cell that was just set by cell.set
          val ms = readTimeoutMs()
          Done(receiveWithTimeout(ms))
        case V2Value.IntV(ms) =>
          // receive(ms)(handler) — directly passed Int timeout
          Done(receiveWithTimeout(ms))
        case _ =>
          sys.error(s"receive: unexpected arg $arg")
    }))

    // trapExit(flag) — Erlang-style: when true, exit(self, reason) becomes an
    // Exit(reason) MESSAGE in this actor's mailbox instead of a kill.
    // ── Distributed-node surface, LOCAL-LOOPBACK simulation ─────────────────
    // The corpus examples run all "nodes" in ONE process; v1 uses HTTP loopback.
    // Here: startNode registers an id, registerBehavior a named closure, and
    // spawnRemote spawns the behavior as a LOCAL actor — same visible semantics.
    V2PluginRegistry.registerGlobal("startNode", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      env.headOption.foreach {
        case V2Value.StrV(id) =>
          nodeIds.add(id)
          localNodeId.set(id)
        case _ => ()
      }
      Done(V2Value.UnitV)
    }))
    V2PluginRegistry.registerGlobal("connectNode", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      Done(env.headOption.getOrElse(V2Value.StrV("local")))
    }))
    V2PluginRegistry.registerGlobal("registerBehavior", V2Value.ClosV(Runtime.emptyEnv, 2, env => {
      (env(0), env(1)) match
        case (V2Value.StrV(name), fn: V2Value.ClosV) => behaviors.put(name, fn)
        case _ => ()
      Done(V2Value.UnitV)
    }))
    // Typed ActorRef surface over a loopback mailbox (actors-typed-remote-spawn):
    // address/isLocal/tryLocal as plain method-object fields, tell/publishAs as
    // closures. publishAs + globalWhereis share a process-local name registry —
    // everything is local in the loopback sim.
    val namedRefs = new java.util.concurrent.ConcurrentHashMap[String, V2Value]()
    def mkActorRef(nodeId: String, mb: ActorMailbox): V2Value =
      lazy val ref: V2Value = V2Value.ForeignV(collection.immutable.Map[String, V2Value](
        "address"   -> V2Value.DataV("Some", Vector(V2Value.StrV(nodeId))),
        "isLocal"   -> V2Value.BoolV(true),
        "tryLocal"  -> V2Value.DataV("Some", Vector(V2Value.ForeignV(mb))),
        "tell"      -> V2Value.ClosV(Runtime.emptyEnv, 1, env2 => { deliverActorMessage(mb, env2.last); Done(V2Value.UnitV) }),
        "publishAs" -> V2Value.ClosV(Runtime.emptyEnv, 1, env2 => {
          env2.last match { case V2Value.StrV(n) => namedRefs.put(n, ref); case _ => () }
          Done(V2Value.UnitV)
        }),
      ).asInstanceOf[AnyRef])
      ref
    V2PluginRegistry.registerGlobal("spawnRemote", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      // spawnRemote(nodeId, behaviorName, arg) — node id ignored locally
      val (name, arg) = env.toList match
        case _ :: V2Value.StrV(n) :: a :: _ => (n, a)
        case V2Value.StrV(n) :: a :: _      => (n, a)
        case V2Value.StrV(n) :: Nil         => (n, V2Value.UnitV)
        case _                      => ("?", V2Value.UnitV)
      val nodeId = env.toList match
        case V2Value.StrV(nid) :: V2Value.StrV(_) :: _ => nid
        case _                                          => "local"
      behaviors.get(name) match
        case null => sys.error(s"spawnRemote: no behavior '$name' registered")
        case fn =>
          val parent = actorTL.get()
          val scope = if parent != null then parent.scope else null
          val mb = new ActorMailbox()
          mb.scope = scope
          if scope != null then scope.addActor(mb)
          val t = Thread.ofVirtual().start(() => {
            actorTL.set(mb)
            try { callClosure(fn, List(arg)); () }
            catch { case _: InterruptedException => () ; case e: Throwable => notifyDeath(mb, V2Value.StrV(e.getMessage)) }
            finally { mb.dead = true; signalActor(mb); actorTL.remove() }
          })
          mb.thread = t
          Done(mkActorRef(nodeId, mb))
    }))
    V2PluginRegistry.registerGlobal("globalWhereis", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val res = env.last match
        case V2Value.StrV(n) => Option(namedRefs.get(n))
        case _               => None
      Done(res.map(r => V2Value.DataV("Some", Vector(r))).getOrElse(V2Value.DataV("None", Vector.empty)))
    }))
    V2PluginRegistry.registerGlobal("globalRegister", V2Value.ClosV(Runtime.emptyEnv, 2, env => {
      (env(0), env(1)) match
        case (V2Value.StrV(n), r) => namedRefs.put(n, r)
        case _ => ()
      Done(V2Value.UnitV)
    }))
    // Typed ActorRef helpers — locally an actor ref IS the mailbox
    V2PluginRegistry.registerGlobal("actorRef", V2Value.ClosV(Runtime.emptyEnv, -1, env =>
      Done(env.headOption.getOrElse(V2Value.UnitV))))
    V2PluginRegistry.registerGlobal("actorRefAddress", V2Value.ClosV(Runtime.emptyEnv, -1, _ =>
      Done(V2Value.StrV("local://"))))
    V2PluginRegistry.registerGlobal("actorRefIsLocal", V2Value.ClosV(Runtime.emptyEnv, -1, _ =>
      Done(V2Value.BoolV(true))))
    V2PluginRegistry.registerGlobal("actorRefTryLocal", V2Value.ClosV(Runtime.emptyEnv, -1, env =>
      Done(V2Value.DataV("Some", Vector(env.headOption.getOrElse(V2Value.UnitV))))))
    V2PluginRegistry.registerGlobal("actorRefPublish", V2Value.ClosV(Runtime.emptyEnv, -1, env =>
      Done(env.headOption.getOrElse(V2Value.UnitV))))

    V2PluginRegistry.registerGlobal("trapExit", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      val mb = actorTL.get()
      if mb != null then
        mb.trapExit = env.headOption match
          case Some(V2Value.BoolV(b)) => b
          case _ => true
      Done(V2Value.UnitV)
    }))

    // link(actorRef) — bidirectional supervision link with the CURRENT actor:
    // when either side dies, the other is killed (or messaged if it traps exits).
    V2PluginRegistry.registerGlobal("link", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val me = actorTL.get()
      env.last match
        case V2Value.ForeignV(other: ActorMailbox) if me != null =>
          other.links.add(me); me.links.add(other)
        case _ => ()
      Done(V2Value.UnitV)
    }))

    // monitor(actorRef) — unidirectional: current actor gets Down(reason) when it dies.
    V2PluginRegistry.registerGlobal("monitor", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val me = actorTL.get()
      env.last match
        case V2Value.ForeignV(other: ActorMailbox) if me != null =>
          other.monitors.add(me)
        case _ => ()
      Done(V2Value.UnitV)
    }))

    // self() — returns current actor's mailbox ref
    V2PluginRegistry.registerGlobal("self", V2Value.ClosV(Runtime.emptyEnv, 0, env => {
      var mb = actorTL.get()
      if mb == null then
        // v1 parity: the MAIN thread has an implicit root mailbox — mapreduce
        // drivers call self() outside any spawned actor to receive results.
        mb = new ActorMailbox()
        actorTL.set(mb)
      Done(V2Value.ForeignV(mb))
    }))

    // exit — POLYMORPHIC (variadic): exit(actorRef[, reason]) kills an actor;
    // exit([code]) is a process exit routed through Runtime.exitHandler so an
    // embedding batch runner can intercept it instead of dying.
    V2PluginRegistry.registerGlobal("exit", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      val first = if env.nonEmpty then env(0) else V2Value.UnitV
      first match
        case V2Value.ForeignV(mb: ActorMailbox) =>
          if mb.trapExit then
            // trapExit: deliver Exit(reason) as a message instead of killing
            val reason = if env.length >= 2 then env(1) else V2Value.StrV("normal")
            deliverActorMessage(mb, V2Value.DataV("Exit", Vector(reason)))
          else
            mb.dead = true
            val t = mb.thread
            if t != null then t.interrupt()
            val reason = if env.length >= 2 then env(1) else V2Value.StrV("killed")
            notifyDeath(mb, reason)
            signalActor(mb)
          Done(V2Value.UnitV)
        case V2Value.IntV(code) => Runtime.exitHandler(code.toInt)
        case _ => Runtime.exitHandler(0)
    }))

    // runActors { body } — BlockForm-like: runs body in a main actor VirtualThread
    // and waits for it to complete
    V2PluginRegistry.registerGlobal("runActors", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val thunk = env.last.asInstanceOf[V2Value.ClosV]
      val scope = new ActorRunState()
      val mb = new ActorMailbox()
      mb.scope = scope
      scope.addActor(mb)
      @volatile var result: V2Value = V2Value.UnitV
      @volatile var err: Throwable | Null = null
      val t = Thread.ofVirtual().start(() => {
        actorTL.set(mb)
        mb.thread = Thread.currentThread()
        var reason: V2Value = V2Value.StrV("normal")
        try
          result = callThunk(thunk)
        catch
          case e: InterruptedException =>
            reason = V2Value.StrV("killed")
          case e: Throwable =>
            err = e
            reason = V2Value.StrV(Option(e.getMessage).getOrElse("error"))
        finally
          mb.dead = true
          notifyDeath(mb, reason)
          actorTL.remove()
        ()
      })
      mb.thread = t
      scope.awaitQuiescent()
      val e = err
      if e != null then throw e
      Done(result)
    }))

    V2PluginRegistry.registerGlobal("sendAfter", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      env.toList match
        case V2Value.IntV(delayMs) :: V2Value.ForeignV(mb: ActorMailbox) :: msg :: _ =>
          Done(scheduleActorSend(delayMs, mb, msg, repeat = false))
        case _ => sys.error("sendAfter(delayMs, pid, msg)")
    }))
    V2PluginRegistry.registerGlobal("sendInterval", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      env.toList match
        case V2Value.IntV(periodMs) :: V2Value.ForeignV(mb: ActorMailbox) :: msg :: _ =>
          Done(scheduleActorSend(periodMs, mb, msg, repeat = true))
        case _ => sys.error("sendInterval(periodMs, pid, msg)")
    }))
    V2PluginRegistry.registerGlobal("cancelTimer", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      env.headOption.foreach {
        case V2Value.IntV(id) => cancelActorTimer(id)
        case _                => ()
      }
      Done(V2Value.UnitV)
    }))

    // Register "!" operator handler in __arith__ dispatch (ForeignV send)
    V2PluginRegistry.register("actor.send", args => args match
      case List(V2Value.ForeignV(mb: ActorMailbox), msg) =>
        deliverActorMessage(mb, msg)
        V2Value.UnitV
      case _ => V2Value.UnitV
    )

  // ── Batch-runner HTTP helpers ────────────────────────────────────────────────

  /** Build a fake GET response for localhost URLs (oidc-login-flow.ssc authorize step).
   *  For /authorize?... URLs: returns a 302 redirect echoing `redirect_uri?code=stub&state={state}`.
   *  For any other path: returns 200 OK with an empty body. */
  private def makeLocalhostGetResp(url: String): V2Value =
    import V2Value.*
    val parsed  = scala.util.Try(new java.net.URI(url)).toOption
    val path    = parsed.map(_.getPath).getOrElse("")
    val rawQ    = parsed.flatMap(u => Option(u.getRawQuery)).getOrElse("")
    val params  = rawQ.split("&").flatMap { kv =>
      kv.split("=", 2) match
        case Array(k, v) => Some(java.net.URLDecoder.decode(k, "UTF-8") ->
                                  java.net.URLDecoder.decode(v, "UTF-8"))
        case _ => None
    }.toMap
    if path.endsWith("/authorize") then
      val state       = params.getOrElse("state", "batch-state")
      val redirectUri = params.getOrElse("redirect_uri", "http://localhost:3000/callback")
      val location    = s"$redirectUri?code=batch_code&state=$state"
      // NamedMethodObj supports .header(name), .status, .body
      ForeignV(new ssc.Value.NamedMethodObj {
        def getField(n: String): Option[ssc.Value] = n match
          case "status" => Some(IntV(302))
          case "body"   => Some(StrV(""))
          case "header" => Some(ClosV(Runtime.emptyEnv, 1, env => Done(
            env(0) match
              case StrV("Location") => DataV("Some", Vector(StrV(location)))
              case _                => DataV("None", Vector.empty)
          )))
          case _ => None
        def underlying: AnyRef = this
        override def toString = s"Response(302, Location=$location)"
      })
    else
      ForeignV(new ssc.Value.NamedMethodObj {
        def getField(n: String): Option[ssc.Value] = n match
          case "status" => Some(IntV(200))
          case "body"   => Some(StrV(""))
          case "header" => Some(ClosV(Runtime.emptyEnv, 1, _ => Done(DataV("None", Vector.empty))))
          case _ => None
        def underlying: AnyRef = this
        override def toString = s"Response(200)"
      })

  /** Build a URL-parser object: http.parseUrl(url) returns an obj with query(name) method. */
  private def makeUrlParserV2(urlV: V2Value): V2Value =
    import V2Value.*
    val url    = urlV match { case StrV(s) => s; case _ => "" }
    val rawQ: String = scala.util.Try(new java.net.URI(url)).toOption
      .flatMap(u => Option(u.getRawQuery))
      .getOrElse { val qi = url.indexOf('?'); if qi >= 0 then url.substring(qi + 1) else "" }
    val params = rawQ.split("&").flatMap { kv =>
      kv.split("=", 2) match
        case Array(k, v) => Some(java.net.URLDecoder.decode(k, "UTF-8") ->
                                  java.net.URLDecoder.decode(v, "UTF-8"))
        case _ => None
    }.toMap
    ForeignV(new ssc.Value.NamedMethodObj {
      def getField(n: String): Option[ssc.Value] = n match
        case "query" => Some(ClosV(Runtime.emptyEnv, 1, env => Done(
          env(0) match
            case StrV(name) => params.get(name).map(StrV(_)).getOrElse(StrV(""))
            case _ => StrV("")
        )))
        case _ => None
      def underlying: AnyRef = this
      override def toString = s"ParsedUrl($url)"
    })

  private def isLocalhostUrl(url: String): Boolean =
    url.startsWith("http://localhost") || url.startsWith("https://localhost") ||
    url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1")

  /** Build a fake HTTP Response v2 value for runAgent/std/agent.ssc.
   *
   *  The FrontendBridge's fieldIndex("body") is non-deterministic: "body" is at index 1
   *  in AgentHttpAttempt but at index 2 in Response. To handle both orderings, we create
   *  a DataV("Response") that puts the body string at indices 0, 1, 2 and a safe fallback
   *  at index 3+. Status is embedded via the ForeignV NamedMethodObj so __method__ lookups
   *  also work, but fieldAt accesses go straight to the Vector.
   *
   *  Vector layout (by index):
   *   0 → IntV(status)    (status — only at 0 in all case classes, safe)
   *   1 → StrV(body)      (body when fieldIndex("body")=1 from AgentHttpAttempt ordering)
   *   2 → StrV(body)      (body when fieldIndex("body")=2 from Response ordering)
   *   3 → StrV("")        (errorText fallback for any index-3 access)
   *   4 → BoolV(false)    (transportError fallback for any index-4 access)
   */
  private def fakeHttpResponse(status: Int, body: String, contentType: String): V2Value =
    import V2Value.*
    DataV("Response", Vector(
      IntV(status.toLong),    // 0: status
      StrV(body),             // 1: body (AgentHttpAttempt field order)
      StrV(body),             // 2: body (Response field order)
      StrV(""),               // 3: errorText fallback
      BoolV(false)            // 4: transportError fallback
    ))

  // ── SpiValue ↔ V2Value conversion ─────────────────────────────────────────

  def v2ToSpi(v: V2Value): SpiValue = v match
    case V2Value.UnitV        => SpiValue.UnitV
    case V2Value.BoolV(b)     => SpiValue.BoolV(b)
    case V2Value.IntV(n)      => SpiValue.IntV(n)
    case V2Value.FloatV(d)    => SpiValue.DoubleV(d)
    case V2Value.StrV(s)      => SpiValue.StrV(s)
    case V2Value.BigV(n)      => SpiValue.BigIntV(n)
    case V2Value.DataV(tag, fields) =>
      SpiValue.ListV(fields.toList.map(v2ToSpi)) // best effort: fields as list
    case _                    => SpiValue.Opaque(v)

  def spiToV2(s: SpiValue): V2Value = s match
    case SpiValue.UnitV        => V2Value.UnitV
    case SpiValue.BoolV(b)     => V2Value.BoolV(b)
    case SpiValue.IntV(n)      => V2Value.IntV(n)
    case SpiValue.DoubleV(d)   => V2Value.FloatV(d)
    case SpiValue.StrV(s)      => V2Value.StrV(s)
    case SpiValue.BigIntV(n)   => V2Value.BigV(n)
    case SpiValue.ListV(items) =>
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (x, acc) =>
        V2Value.DataV("Cons", Vector(spiToV2(x), acc))
      }
    case SpiValue.TupleV(elems) =>
      V2Value.DataV(s"Tuple${elems.length}", elems.map(spiToV2).toVector)
    case SpiValue.Opaque(v: V2Value) => v
    case SpiValue.Opaque(v)          => V2Value.ForeignV(v.asInstanceOf[AnyRef])

  // ── v2 ↔ raw-Any for NativeImpl (mirrors Interpreter.unwrapValueAsAny) ────────

  /** Convert v2 value to the raw-Any type that NativeImpl.eval expects.
   *  Scalars become JVM primitives; complex values become v1 Values (for
   *  plugins that pattern-match on v1 InstanceV / ListV / etc.). */
  private def v2ToRaw(v: V2Value): Any = v match
    case V2Value.StrV(s)        => s
    case V2Value.IntV(n)        => n
    case V2Value.FloatV(d)      => d
    case V2Value.BoolV(b)       => b
    case V2Value.UnitV          => ()
    case V2Value.DataV("None", _) => null  // null works for isNullish(), optionalStringArg, PluginValue.Opt(None)
    case _                      => v2ToV1(v)  // complex → v1Value

  /** Convert the raw-Any returned by NativeImpl.eval back to a v2 value.
   *  Mirrors Interpreter.wrapAnyAsValue + the v1→v2 path. */
  private def rawToV2(a: Any): V2Value = a match
    case s: String  => V2Value.StrV(s)
    case n: Long    => V2Value.IntV(n)
    case i: Int     => V2Value.IntV(i.toLong)
    case d: Double  => V2Value.FloatV(d)
    case f: Float   => V2Value.FloatV(f.toDouble)
    case b: Boolean => V2Value.BoolV(b)
    case ()         => V2Value.UnitV
    case v1: V1Value => v1ToV2(v1)
    // v1 NativeFnV → variadic v2 ClosV (arity=-1, accepts any number of args)
    case nfv: scalascript.interpreter.Value.NativeFnV =>
      V2Value.ClosV(Runtime.emptyEnv, -1, env => {
        val v1Args = env.toList.map(v2ToV1)
        val computation = nfv.f(v1Args)
        import scalascript.interpreter.Computation
        Done(v1ToV2(Computation.run(computation)))
      })
    case lst: scala.collection.immutable.List[?] =>
      lst.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (x, acc) =>
        V2Value.DataV("Cons", Vector(rawToV2(x.asInstanceOf[Any]), acc))
      }
    case null       => V2Value.DataV("None", Vector.empty)
    case other      => V2Value.ForeignV(other.asInstanceOf[AnyRef])

  private def rawToV1(a: Any): V1Value = a match
    case n: Long    => DataValue.IntV(n)
    case i: Int     => DataValue.IntV(i.toLong)
    case d: Double  => DataValue.DoubleV(d)
    case f: Float   => DataValue.DoubleV(f.toDouble)
    case s: String  => DataValue.StringV(s)
    case b: Boolean => DataValue.BoolV(b)
    case ()         => DataValue.UnitV
    case null       => DataValue.NullV
    case v: V1Value => v
    case lst: scala.collection.immutable.List[?] =>
      scalascript.interpreter.Value.ListV(lst.map(x => rawToV1(x.asInstanceOf[Any])))
    case map: scala.collection.immutable.Map[?, ?] =>
      scalascript.interpreter.Value.MapV(map.map { case (k, v) => rawToV1(k.asInstanceOf[Any]) -> rawToV1(v.asInstanceOf[Any]) }.toMap)
    case other      => DataValue.StringV(other.toString)

  // ── Value translation: v2 Value → v1 Value ─────────────────────────────

  /** Convert a v2 VM value to a v1 interpreter Value (for plugin arguments). */
  def v2ToV1(v: V2Value): V1Value = v match
    case V2Value.UnitV        => DataValue.UnitV
    case V2Value.BoolV(b)     => DataValue.BoolV(b)
    case V2Value.IntV(n)      => DataValue.IntV(n)
    case V2Value.FloatV(d)    => DataValue.DoubleV(d)
    case V2Value.StrV(s)      => DataValue.StringV(s)
    case V2Value.BigV(n)      => DataValue.BigIntV(n)
    // Cons/Nil list chain → v1 ListV
    case V2Value.DataV("Nil", _) =>
      scalascript.interpreter.Value.ListV(Nil)
    case cons @ V2Value.DataV("Cons", Seq(_, _)) =>
      val items = scala.collection.mutable.ListBuffer.empty[V1Value]
      var cur: V2Value = cons
      var done = false
      while !done do
        cur match
          case V2Value.DataV("Nil", _) =>
            done = true
          case V2Value.DataV("Cons", Seq(h, t)) =>
            items += v2ToV1(h)
            cur = t
          case other =>
            items += v2ToV1(other)
            done = true
      scalascript.interpreter.Value.ListV(items.toList)
    // Option variants
    case V2Value.DataV("None", _) =>
      scalascript.interpreter.Value.OptionV(null)
    case V2Value.DataV("Some", Seq(inner)) =>
      scalascript.interpreter.Value.OptionV(v2ToV1(inner))
    case V2Value.BytesV(bs)   =>
      // Bytes: v1 has no BytesV; wrap as a list of IntV bytes
      val items = bs.toList.map(b => DataValue.IntV((b & 0xff).toLong): V1Value)
      scalascript.interpreter.Value.ListV(items)
    case V2Value.DataV(tag, fields) if tag.startsWith("Tuple") && tag.drop(5).forall(_.isDigit) =>
      // TupleN → v1 TupleV
      scalascript.interpreter.Value.TupleV(fields.toList.map(v2ToV1))
    case V2Value.DataV(tag, fields) =>
      // Use named fields from the bridge's field registry when available;
      // fall back to positional _0/_1/… so plugins can still use positional access.
      // Arity-matched so a same-named collision (http vs domain `Request`) names
      // the right layout for this value (v2-req-form-type-collision).
      val knownNames: Option[Vector[String]] = V2PluginRegistry.lookupFieldNames(tag, fields.length)
      val fieldMap: Map[String, V1Value] = knownNames match
        case Some(names) if names.length == fields.length =>
          // Named field map (both named and positional keys for compatibility)
          (names.zip(fields).map { case (n, fv) => n -> v2ToV1(fv) } ++
           fields.zipWithIndex.map { case (fv, i) => s"_$i" -> v2ToV1(fv) }).toMap
        case _ =>
          fields.zipWithIndex.map { case (fv, i) => s"_$i" -> v2ToV1(fv) }.toMap
      val inst = scalascript.interpreter.Value.InstanceV(tag, fieldMap)
      val arr: Array[V1Value] = fields.map(v2ToV1).toArray
      val names: Array[String] = knownNames match
        case Some(ns) if ns.length == fields.length => ns.toArray
        case _ => fields.indices.map(i => s"_$i").toArray
      inst.fieldsArr = arr
      inst.fieldNames = names
      inst
    case V2Value.ForeignV(m: scala.collection.mutable.Map[?, ?]) =>
      // v2 Map (from __mk_map__) → v1 MapV so plugins can MapVal.unapply
      val converted = m.asInstanceOf[scala.collection.mutable.Map[V2Value, V2Value]]
        .map { case (k, v2) => v2ToV1(k) -> v2ToV1(v2) }.toMap
      scalascript.interpreter.Value.MapV(converted)
    case V2Value.ForeignV(m: scala.collection.immutable.Map[?, ?]) if {
          // Named-field maps (from InstanceV conversion) have String keys — treat as InstanceV
          m.nonEmpty && m.keys.head.isInstanceOf[String]
        } =>
      val nm = m.asInstanceOf[scala.collection.immutable.Map[String, V2Value]]
      val fieldMap = nm.map { case (k, v2) => k -> v2ToV1(v2) }.toMap
      val inst = scalascript.interpreter.Value.InstanceV("record", fieldMap)
      val names = nm.keys.toArray
      inst.fieldNames = names
      inst.fieldsArr  = names.map(k => fieldMap(k))
      inst
    case V2Value.ForeignV(m: scala.collection.immutable.Map[?, ?]) =>
      val converted = m.asInstanceOf[scala.collection.immutable.Map[V2Value, V2Value]]
        .map { case (k, v2) => v2ToV1(k) -> v2ToV1(v2) }
      scalascript.interpreter.Value.MapV(converted)
    case V2Value.ForeignV(nmo: ssc.Value.NamedMethodObj) =>
      nmo.underlying match
        case inst: scalascript.interpreter.Value.InstanceV => inst
        case v1: V1Value                                   => v1
        case other => scalascript.interpreter.Value.Foreign(other.getClass.getSimpleName, other)
    case V2Value.ForeignV(tf: TaggedForeign) =>
      scalascript.interpreter.Value.Foreign(tf.tag, tf.value)
    // A ForeignV that already wraps a NATIVE v1 interpreter Value (a plugin
    // round-trip, e.g. the content builder's DocV) must pass through UNCHANGED —
    // re-wrapping as Foreign made v1's show print "<foreign:DocV (DocV)>"
    // instead of rendering the document.
    case V2Value.ForeignV(h: scalascript.interpreter.Value) => h
    case V2Value.ForeignV(h) =>
      scalascript.interpreter.Value.Foreign(h.getClass.getSimpleName, h)
    case c: V2Value.ClosV =>
      // Wrap v2 ClosV as a v1 NativeFnV so plugins can call it via invokeCallback.
      scalascript.interpreter.Value.NativeFnV("v2Closure", v1Args => {
        val v2Args = v1Args.map(v1ToV2)
        val env    = if v2Args.isEmpty then c.env else Runtime.extend(c.env, v2Args.toArray)
        scalascript.interpreter.Computation.Pure(v2ToV1(Runtime.run(c.code, env)))
      })
    case _ =>
      // LongCellV and other non-translatable values — wrap as opaque Foreign
      scalascript.interpreter.Value.Foreign("v2Value", v.asInstanceOf[AnyRef])

  // ── Value translation: v1 Value → v2 Value ─────────────────────────────

  /** Convert a v1 interpreter Value to a v2 VM value (for plugin return values). */
  def v1ToV2(v: Any): V2Value = v match
    case DataValue.UnitV        => V2Value.UnitV
    case DataValue.BoolV(b)     => V2Value.BoolV(b)
    case DataValue.IntV(n)      => V2Value.IntV(n)
    case DataValue.DoubleV(d)   => V2Value.FloatV(d)
    case DataValue.StringV(s)   => V2Value.StrV(s)
    case DataValue.BigIntV(n)   => V2Value.BigV(n)
    case DataValue.DecimalV(d)  => V2Value.FloatV(d.toDouble)
    case DataValue.CharV(c)     => V2Value.StrV(c.toString)
    case DataValue.NullV        => V2Value.DataV("None", Vector.empty) // closest v2 equivalent
    case scalascript.interpreter.Value.InstanceV(tag, _) =>
      val inst = v.asInstanceOf[scalascript.interpreter.Value.InstanceV]
      val effFields = inst.effectiveFields
      val arr = inst.fieldsArr
      if arr != null then
        // orderedInstance: positional array → DataV preserves fieldAt compatibility
        V2Value.DataV(tag, arr.toVector.map(v1ToV2))
      else
        // If the tag has registered field names (from case class in imported .ssc lib),
        // build DataV in declaration order so fieldAt(obj, i) works correctly.
        // Arity-matched: two case classes can share a tag NAME (std/http.ssc
        // `Request` vs a domain `Request`) — order by the layout whose arity ==
        // this instance's field count, not the last-registered one, else the http
        // Request comes out with the domain layout and drops form/params
        // (v2-req-form-type-collision).
        V2PluginRegistry.lookupFieldNames(tag, effFields.size) match
          case Some(orderedNames) if effFields.nonEmpty && orderedNames.length == effFields.size =>
            val orderedVals = orderedNames.map(n =>
              effFields.get(n).map(v1ToV2).getOrElse(V2Value.UnitV))
            V2Value.DataV(tag, orderedVals.toVector)
          case _ =>
            val hasRealNames = effFields.nonEmpty &&
              !effFields.keys.forall(k => k.matches("_\\d+"))
            if hasRealNames then
              V2Value.ForeignV(new ssc.Value.NamedMethodObj {
                def getField(n: String): Option[ssc.Value] = effFields.get(n).map(v1ToV2)
                def underlying: AnyRef = inst
                override def toString: String = s"$tag(${effFields.keys.mkString(", ")})"
              })
            else
              V2Value.DataV(tag, effFields.values.toVector.map(v1ToV2))
    case scalascript.interpreter.Value.ListV(items) =>
      // Encode as a Cons/Nil chain (v2 list encoding)
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (item, acc) =>
        V2Value.DataV("Cons", Vector(v1ToV2(item), acc))
      }
    case scalascript.interpreter.Value.VectorV(items) =>
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (item, acc) =>
        V2Value.DataV("Cons", Vector(v1ToV2(item), acc))
      }
    case scalascript.interpreter.Value.OptionV(null) =>
      V2Value.DataV("None", Vector.empty)
    case scalascript.interpreter.Value.OptionV(inner) =>
      V2Value.DataV("Some", Vector(v1ToV2(inner)))
    case scalascript.interpreter.Value.TupleV(elems) =>
      V2Value.DataV(s"Tuple${elems.length}", elems.map(v1ToV2).toVector)
    case scalascript.interpreter.Value.MapV(entries) =>
      val hm = scala.collection.mutable.HashMap[V2Value, V2Value]()
      entries.foreach { case (k, v) => hm(v1ToV2(k)) = v1ToV2(v) }
      V2Value.ForeignV(hm)
    case orig @ scalascript.interpreter.Value.Foreign(_, s: scalascript.frontend.Signal[?]) =>
      // Signal[T]: callable (0 args → get, 1 arg → set) AND round-trip-safe —
      // a bare ClosV here DESTROYED the Foreign("ReactiveSignal") identity, so
      // natives like fetchUrlSignal(_,_,tick) failed their Foreign match after
      // one v1→v2→v1 trip. NamedMethodObj keeps the original as `underlying`
      // (v2ToV1 restores it exactly) and stays callable via applyFallback.
      V2Value.ForeignV(new ssc.Value.NamedMethodObj {
        def underlying: AnyRef = orig
        def getField(name: String): Option[V2Value] = name match
          case "apply" => Some(V2Value.ClosV(Runtime.emptyEnv, -1, env =>
            if env.isEmpty then Done(rawToV2(s.apply().asInstanceOf[Any]))
            else { s.asInstanceOf[scalascript.frontend.Signal[Any]].set(v2ToRaw(env.last)); Done(V2Value.UnitV) }))
          case "get" => Some(V2Value.ClosV(Runtime.emptyEnv, 0, _ =>
            Done(rawToV2(s.apply().asInstanceOf[Any]))))
          case "set" => Some(V2Value.ClosV(Runtime.emptyEnv, 1, env =>
            { s.asInstanceOf[scalascript.frontend.Signal[Any]].set(v2ToRaw(env.last)); Done(V2Value.UnitV) }))
          case "bind" => Some(V2Value.ClosV(Runtime.emptyEnv, 1, env =>
            V2PluginRegistry.lookupGlobal("ReactiveSignal.bind").collect { case c: V2Value.ClosV => c } match
              case Some(bindFn) =>
                Done(Runtime.run(bindFn.code, Runtime.extend(bindFn.env, Array(v1ToV2(orig), env.last))))
              case None =>
                sys.error("ReactiveSignal.bind is not registered")
          ))
          case _ => None
      })
    case scalascript.interpreter.Value.Foreign(tag, h: AnyRef) =>
      V2Value.ForeignV(TaggedForeign(tag, h))
    // v1 NativeFnV → variadic v2 ClosV
    case nfv: scalascript.interpreter.Value.NativeFnV =>
      V2Value.ClosV(Runtime.emptyEnv, -1, env => {
        val v1Args = env.toList.map(v2ToV1)
        Done(v1ToV2(scalascript.interpreter.Computation.run(nfv.f(v1Args))))
      })
    case _ =>
      // Closures and other complex v1 values: wrap in ForeignV
      V2Value.ForeignV(v.asInstanceOf[AnyRef])
