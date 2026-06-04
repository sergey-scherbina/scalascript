package scalascript.backend.spi

import scalascript.ir.{IrExpr, EmitContext, TargetCode}
import scala.util.control.NoStackTrace

/** How a backend implements a single platform intrinsic (`extern def`
 *  marker, see specs/backend-spi.md §8).
 *
 *  Backends populate `Backend.intrinsics: Map[QualifiedName, IntrinsicImpl]`
 *  to claim implementations.  `CapabilityCheck` (Stage 4) refuses to
 *  invoke `compile` on a program that calls an extern symbol the
 *  selected backend has no entry for. */
sealed trait IntrinsicImpl

/** Inline target-source generated at each call site.  `emit` receives
 *  the call's IR argument expressions and a context for accessing
 *  surrounding state. */
case class InlineCode(emit: (List[IrExpr], EmitContext) => TargetCode) extends IntrinsicImpl

/** Call a runtime function the backend ships with its emitted output
 *  (e.g. a `_http_serve(port, handler)` helper baked into the JS runtime
 *  preamble). */
case class RuntimeCall(targetSymbol: String) extends IntrinsicImpl

/** Out-of-process backends route platform calls back into core via a
 *  named host callback that core dispatches.  See §12.2 wire protocol. */
case class HostCallback(name: String) extends IntrinsicImpl

/** Interpretive intrinsic: an in-process function the runtime calls
 *  directly with the evaluated arguments.  Unlike `InlineCode` /
 *  `RuntimeCall` (which produce target source for compiled backends),
 *  `NativeImpl` is the variant interpretive backends consume —
 *  `InterpreterBackend` registers each one as a native function in
 *  the interpreter's global table at session start.
 *
 *  Arguments and return value are typed `Any` because the SPI sits
 *  in `backend-spi` while concrete value types live in the
 *  interpreter (which depends on `backend-spi`, not vice versa);
 *  the interpreter casts to its own `Value` ADT at the boundary.
 *
 *  The `NativeContext` parameter carries the runtime hooks an I/O
 *  intrinsic needs — e.g. `println` reads `ctx.out` so the
 *  interpreter's per-invocation `PrintStream` (which can be a
 *  null-stream during `ssc render` static generation) is honoured. */
case class NativeImpl(eval: (NativeContext, List[Any]) => Any) extends IntrinsicImpl

object NativeContextFeatureKeys:
  val HttpBaseUrl      = "scalascript.http.baseUrl"
  val HttpTimeoutMs    = "scalascript.http.timeoutMs"
  val HttpMaxRetries   = "scalascript.http.maxRetries"
  val HttpRetryDelayMs = "scalascript.http.retryDelayMs"
  val OpenApiPending         = "scalascript.openapi.pendingRouteMetadata"
  val OpenApiSecuritySchemes = "scalascript.openapi.securitySchemes"
  val OpenApiSchemaComponents = "scalascript.openapi.schemaComponents"
  val UuidFixed              = "scalascript.uuid.fixed"

object OpenApiDryRun:
  case object Sentinel extends RuntimeException("OpenAPI dry-run stopped at serve()") with NoStackTrace

/** Runtime hooks an in-process native intrinsic may consult.  The
 *  interpreter constructs one per session and passes it to every
 *  `NativeImpl.eval` call.  Stateless intrinsics (`nowMillis`,
 *  `hashSha256`, …) ignore it. */
trait NativeContext:
  def out: java.io.PrintStream
  def err: java.io.PrintStream
  // Generic plugin state.  Use `feature*` for interpreter-scoped shared
  // values and `featureLocal*` for execution-scoped values that should be
  // restored around nested blocks such as httpClient { ... }.
  def featureGet(key: String): Option[Any] = None
  def featureSet(key: String, value: Any): Unit = ()
  def featureRemove(key: String): Option[Any] = None
  def featureUpdate(key: String)(f: Option[Any] => Any): Any =
    val next = f(featureGet(key))
    featureSet(key, next)
    next
  def featureLocalGet(key: String): Option[Any] = featureGet(key)
  def featureLocalSet(key: String, value: Any): Unit = featureSet(key, value)
  def featureLocalRemove(key: String): Option[Any] = featureRemove(key)
  def featureLocalUpdate(key: String)(f: Option[Any] => Any): Any =
    val next = f(featureLocalGet(key))
    featureLocalSet(key, next)
    next
  // All methods below default to no-op / identity; the interpreter
  // overrides them in `installNativeIntrinsics` so HTTP intrinsics
  // can live in `InterpreterCapabilities` without a circular dependency.
  def headless: Boolean = false
  def registerRoute(method: String, path: String, handler: Any): Unit = ()
  def registerRouteWithOpenApi(
      method:   String,
      path:     String,
      handler:  Any,
      @annotation.unused metadata: OpenApiGenerator.OpenApiMetadata
  ): Unit = registerRoute(method, path, handler)
  def registerHealthDefaults(): Unit = ()
  def registerOpenApiDefaults(): Unit = ()
  def openApiDryRun: Boolean = false
  def abortOpenApiDryRun(): Nothing = throw OpenApiDryRun.Sentinel
  // Invoke a user-supplied callback (Value closure/NativeFn) from native code.
  def invokeCallback(fn: Any, args: List[Any]): Any = ()
  /** Like [[invokeCallback]] but drives any `Async` effects the callback
   *  performs (`async`/`await`/`delay`/`parallel`) to completion, and unwraps
   *  a resulting `Future` to its underlying value.  Native code that may run
   *  user callbacks returning `Future[A]` or `A ! Async` (e.g. GraphQL
   *  resolvers) should use this instead of [[invokeCallback]].  Defaults to
   *  [[invokeCallback]] for backends without an async runtime. */
  def invokeCallbackAsync(fn: Any, args: List[Any]): Any = invokeCallback(fn, args)
  // Outbound HTTP client state — scoped inside httpClient{} blocks.
  def httpBaseUrl: String =
    featureLocalGet(NativeContextFeatureKeys.HttpBaseUrl).collect { case s: String => s }.getOrElse("")
  def httpTimeoutMs: Long =
    featureLocalGet(NativeContextFeatureKeys.HttpTimeoutMs).collect { case n: Long => n }.getOrElse(30000L)
  def httpMaxRetries: Int =
    featureLocalGet(NativeContextFeatureKeys.HttpMaxRetries).collect { case n: Int => n }.getOrElse(0)
  def httpRetryDelayMs: Long =
    featureLocalGet(NativeContextFeatureKeys.HttpRetryDelayMs).collect { case n: Long => n }.getOrElse(1000L)
  def setHttpTimeout(ms: Long): Unit = featureLocalSet(NativeContextFeatureKeys.HttpTimeoutMs, ms)
  def setHttpRetry(maxAttempts: Int, delayMs: Long): Unit =
    featureLocalSet(NativeContextFeatureKeys.HttpMaxRetries, maxAttempts)
    featureLocalSet(NativeContextFeatureKeys.HttpRetryDelayMs, delayMs)
  // TLS server startup.
  def startTlsServer(port: Int, dir: String, cert: String, key: String): Unit = ()
  // Plain HTTP server startup (no TLS).
  def startServer(port: Int, dir: String): Unit = ()
  def startServerAsync(port: Int, dir: String): Unit = ()
  def stopServer(): Unit = ()
  // WebSocket connection cap.
  def setMaxWsConnections(n: Int): Unit = ()
  // WebSocket server route registration.
  def registerWsRoute(path: String, origins: List[String], protocols: List[String],
                      maxConn: Int, maxRate: Int, handler: Any): Unit = ()
  def registerWsAuthRoute(path: String, authFn: Any, handler: Any): Unit = ()
  // WebSocket client — blocks until server closes the connection.
  def wsConnectSync(url: String, headers: Map[String, String],
                    protocols: List[String], handler: Any): Unit = ()
  // Middleware registration.
  def registerMiddleware(fn: Any): Unit = ()
  // CORS / response decoration.
  def configureCors(origins: List[String], methods: List[String],
                    allowedHeaders: List[String]): Unit = ()
  def enableGzip(): Unit = ()
  def setMaxBodySize(bytes: Long): Unit = ()
  def setSpoolThreshold(bytes: Long): Unit = ()
  def setUploadDir(path: String): Unit = ()
  // Request validation — inside validate{} records error+default; outside throws.
  def validationRecord(name: String, msg: String, default: Any): Any =
    throw new RuntimeException(s"Validation error: $msg")
  // Dynamic SQL for route handlers — delegates to the interpreter's sqlRegistry.
  // Default throws; the interpreter overrides this in installNativeIntrinsics.
  def dbConnect(dbName: String): java.sql.Connection =
    throw new UnsupportedOperationException(
      s"No database registry for '$dbName' — add a databases: section to front-matter"
    )
  // Remote handler registry — used by std/remote-plugin. The interpreter
  // populates it from front-matter `remoteHandlers:` entries and exposes
  // in-process calls plus HTTP JSON fallback routes.
  def remoteHandlers: List[RemoteHandlerInfo] = Nil
  def invokeRemoteHandler(name: String, @annotation.unused payload: Any): Either[RemoteCallError, Any] =
    Left(RemoteCallError.HandlerNotFound(name))
  // Storage-schema metadata for interpreter case-class instances. Native
  // storage intrinsics can ask for the canonical persisted name of a runtime
  // field without depending on interpreter internals.
  def storageFieldName(@annotation.unused typeName: String, fieldName: String): String = fieldName
  // mount() intrinsic — file evaluation hooks.
  // `baseDirPath` is the interpreter's current base directory (absolute string).
  // Returns None when the interpreter has no base dir (e.g. pure REPL snippets).
  def baseDirPath: Option[String] = None
  // Evaluate a `.ssc` file at `absPath` and return the last Value produced by
  // the run.  The returned `Any` is always a `scalascript.interpreter.Value`.
  // Default throws; the interpreter overrides in installNativeIntrinsics.
  def evalFileGetResult(absPath: String): Any =
    throw new UnsupportedOperationException(
      s"evalFileGetResult not available in this context"
    )
  // Evaluate a `.ssc` file at `absPath` and return the Value of the named
  // global `fnName` from the child interpreter's globals after the run.
  // Throws InterpretError if `fnName` is not found.
  // Default throws; the interpreter overrides in installNativeIntrinsics.
  def evalFileGetNamedResult(absPath: String, fnName: String): Any =
    throw new UnsupportedOperationException(
      s"evalFileGetNamedResult not available in this context"
    )
  // Register a route with full mount metadata (source + ctx).
  // Default delegates to `registerRoute` (no source/ctx) for backwards compat.
  def registerMountedRoute(
      method:   String,
      path:     String,
      handler:  Any,
      @annotation.unused source:   Option[String],
      @annotation.unused mountCtx: Map[String, Any]
  ): Unit = registerRoute(method, path, handler)
