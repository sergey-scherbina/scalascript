package scalascript.plugin.api

import scalascript.backend.spi.{NativeContext, NativeImpl, OpenApiGenerator, RemoteCallError, RemoteHandlerInfo}

import scala.util.control.NonFatal

/** Stable surface for ScalaScript plugin authors.
 *
 *  Plugin authors depend only on `scalascript-plugin-api`; they never
 *  import `scalascript.interpreter.*` directly.
 *
 *  Phase 1 shipped the module with minimal opaque aliases.  Phase 2 adds
 *  capability traits and a typed `PluginNative.eval` bridge while keeping the
 *  legacy `NativeImpl` signature source-compatible for unported plugins.
 *  Phase 3 completes capability coverage and provides `PluginNative.evalLegacy`
 *  for mechanical migration of existing NativeImpl bodies. */

/** Opaque handle to a ScalaScript runtime value.
 *  Backed by `Any` in the interpreter; plugins receive it from `NativeImpl.eval`
 *  and pass it back — they never pattern-match on the concrete representation. */
opaque type PluginValue = Any

object PluginValue:
  def wrap(v: Any): PluginValue = v
  extension (pv: PluginValue)
    def unwrap: Any = pv

/** Opaque wrapper for an interpreter-level runtime error. */
opaque type PluginError = Throwable

object PluginError:
  def apply(msg: String): PluginError   = new RuntimeException(msg)
  def wrap(t: Throwable): PluginError   = t
  extension (pe: PluginError)
    def message: String   = pe.getMessage
    def unwrap:  Throwable = pe

/** Opaque alias for the interpreter's `Computation[A]` monad.
 *  Phase 1 treats it as `Any`; the interpreter unwraps it at the SPI boundary.
 *  Use `PluginComputation.pure` to lift a `PluginValue`. */
opaque type PluginComputation = Any

object PluginComputation:
  def pure(v: PluginValue): PluginComputation = v
  extension (pc: PluginComputation)
    def unwrap: Any = pc

/** Stable JSON surface backed by `ujson.Value`.
 *  Prefer this over `scalascript.interpreter.JsonParser` in new plugins so that
 *  parser internals can change without breaking the plugin ABI. */
object JsonCodec:

  def parseString(src: String): Either[String, ujson.Value] =
    try Right(ujson.read(src))
    catch case NonFatal(e) => Left(e.getMessage)

  def stringify(v: ujson.Value): String = ujson.write(v)

  def obj(fields: (String, ujson.Value)*): ujson.Obj =
    val out = ujson.Obj()
    fields.foreach { case (key, value) => out(key) = value }
    out
  def arr(elems: ujson.Value*):            ujson.Arr   = ujson.Arr(elems*)
  def str(s: String):                      ujson.Str   = ujson.Str(s)
  def num(n: Double):                      ujson.Num   = ujson.Num(n)
  val True:  ujson.Bool = ujson.True
  val False: ujson.Bool = ujson.False
  val Null:  ujson.Null.type = ujson.Null

/** Base capability backed by the legacy interpreter `NativeContext`.
 *
 *  Capability traits expose stable, focused subsets of `NativeContext` without
 *  making plugin authors depend on interpreter implementation classes.
 */
trait NativeContextCap:
  protected def nativeContext: NativeContext
  def out: java.io.PrintStream = nativeContext.out
  def err: java.io.PrintStream = nativeContext.err
  def headless: Boolean = nativeContext.headless
  def openApiDryRun: Boolean = nativeContext.openApiDryRun
  def abortOpenApiDryRun(): Nothing = nativeContext.abortOpenApiDryRun()

trait StorageCap extends NativeContextCap:
  def featureGet(key: String): Option[Any] = nativeContext.featureGet(key)
  def featureSet(key: String, value: Any): Unit = nativeContext.featureSet(key, value)
  def featureRemove(key: String): Option[Any] = nativeContext.featureRemove(key)
  def featureLocalGet(key: String): Option[Any] = nativeContext.featureLocalGet(key)
  def featureLocalSet(key: String, value: Any): Unit = nativeContext.featureLocalSet(key, value)
  def featureLocalRemove(key: String): Option[Any] = nativeContext.featureLocalRemove(key)
  def storageFieldName(typeName: String, fieldName: String): String =
    nativeContext.storageFieldName(typeName, fieldName)

trait HttpCap extends NativeContextCap:
  def httpBaseUrl: String = nativeContext.httpBaseUrl
  def httpTimeoutMs: Long = nativeContext.httpTimeoutMs
  def httpMaxRetries: Int = nativeContext.httpMaxRetries
  def httpRetryDelayMs: Long = nativeContext.httpRetryDelayMs
  def setHttpTimeout(ms: Long): Unit = nativeContext.setHttpTimeout(ms)
  def setHttpRetry(maxAttempts: Int, delayMs: Long): Unit =
    nativeContext.setHttpRetry(maxAttempts, delayMs)
  def startTlsServer(port: Int, dir: String, cert: String, key: String): Unit =
    nativeContext.startTlsServer(port, dir, cert, key)
  def startTlsServerAsync(port: Int, dir: String, cert: String, key: String): Unit =
    nativeContext.startTlsServerAsync(port, dir, cert, key)
  def startServer(port: Int, dir: String): Unit = nativeContext.startServer(port, dir)
  def startServerAsync(port: Int, dir: String): Unit = nativeContext.startServerAsync(port, dir)
  def stopServer(): Unit = nativeContext.stopServer()
  def registerRoute(method: String, path: String, handler: Any): Unit =
    nativeContext.registerRoute(method, path, handler)
  def registerRouteWithOpenApi(
      method:   String,
      path:     String,
      handler:  Any,
      metadata: OpenApiGenerator.OpenApiMetadata
  ): Unit =
    nativeContext.registerRouteWithOpenApi(method, path, handler, metadata)
  def registerHealthDefaults(): Unit = nativeContext.registerHealthDefaults()
  def registerOpenApiDefaults(): Unit = nativeContext.registerOpenApiDefaults()
  def configureCors(origins: List[String], methods: List[String], allowedHeaders: List[String]): Unit =
    nativeContext.configureCors(origins, methods, allowedHeaders)
  def enableGzip(): Unit = nativeContext.enableGzip()
  def setMaxBodySize(bytes: Long): Unit = nativeContext.setMaxBodySize(bytes)
  def setSpoolThreshold(bytes: Long): Unit = nativeContext.setSpoolThreshold(bytes)
  def setUploadDir(path: String): Unit = nativeContext.setUploadDir(path)
  def registerMiddleware(fn: Any): Unit = nativeContext.registerMiddleware(fn)

trait WsCap extends NativeContextCap:
  def setMaxWsConnections(n: Int): Unit = nativeContext.setMaxWsConnections(n)
  def registerWsRoute(
      path: String,
      origins: List[String],
      protocols: List[String],
      maxConn: Int,
      maxRate: Int,
      handler: Any
  ): Unit = nativeContext.registerWsRoute(path, origins, protocols, maxConn, maxRate, handler)
  def registerWsAuthRoute(path: String, authFn: Any, handler: Any): Unit =
    nativeContext.registerWsAuthRoute(path, authFn, handler)
  def wsConnectSync(url: String, headers: Map[String, String], protocols: List[String], handler: Any): Unit =
    nativeContext.wsConnectSync(url, headers, protocols, handler)

trait DbCap extends NativeContextCap:
  def dbConnect(dbName: String): java.sql.Connection = nativeContext.dbConnect(dbName)

trait ValidateCap extends NativeContextCap:
  def validationRecord(name: String, msg: String, default: Any): Any =
    nativeContext.validationRecord(name, msg, default)

trait MountCap extends NativeContextCap:
  def baseDirPath: Option[String] = nativeContext.baseDirPath
  def evalFileGetResult(absPath: String): Any = nativeContext.evalFileGetResult(absPath)
  def evalFileGetNamedResult(absPath: String, fnName: String): Any =
    nativeContext.evalFileGetNamedResult(absPath, fnName)
  def registerMountedRoute(
      method: String,
      path: String,
      handler: Any,
      source: Option[String],
      mountCtx: Map[String, Any]
  ): Unit = nativeContext.registerMountedRoute(method, path, handler, source, mountCtx)
  def invokeCallback(fn: Any, args: List[Any]): Any = nativeContext.invokeCallback(fn, args)
  /** Resolve a top-level global binding by name (delegates to the backend), so a
   *  plugin native can reuse another plugin's registered intrinsic (e.g. the
   *  content toolkit invoking `fieldColumn`/`moneyColumn`).  None if unbound. */
  def resolveGlobal(name: String): Option[Any] = nativeContext.resolveGlobal(name)
  /** Like [[invokeCallback]] but drives `Async` effects to completion and
   *  unwraps a resulting `Future` to its value — for callbacks that may
   *  return `Future[A]` or `A ! Async` (e.g. GraphQL resolvers). */
  def invokeCallbackAsync(fn: Any, args: List[Any]): Any = nativeContext.invokeCallbackAsync(fn, args)

/** Capability for remote handler dispatch — used by `remote-plugin`. */
trait RemoteCap extends NativeContextCap:
  def remoteHandlers: List[RemoteHandlerInfo] = nativeContext.remoteHandlers
  def invokeRemoteHandler(name: String, payload: Any): Either[RemoteCallError, Any] =
    nativeContext.invokeRemoteHandler(name, payload)

type PluginContext = HttpCap & WsCap & DbCap & StorageCap & ValidateCap & MountCap & RemoteCap

object PluginContext:
  /** Wrap an interpreter `NativeContext` as a full `PluginContext`.
   *  Used internally by `PluginNative.eval` / `PluginNative.evalLegacy`.
   *  Plugin authors never call this directly. */
  def fromNative(ctx: NativeContext): PluginContext =
    new HttpCap with WsCap with DbCap with StorageCap with ValidateCap with MountCap with RemoteCap:
      protected val nativeContext: NativeContext = ctx

object PluginNative:
  def eval[C](select: PluginContext => C)(
      f: (C, List[PluginValue]) => PluginComputation
  ): NativeImpl =
    NativeImpl { (ctx, args) =>
      val pluginCtx = PluginContext.fromNative(ctx)
      val pluginArgs = args.map(PluginValue.wrap)
      f(select(pluginCtx), pluginArgs).unwrap
    }

  def eval(
      f: (PluginContext, List[PluginValue]) => PluginComputation
  ): NativeImpl =
    eval[PluginContext]((ctx: PluginContext) => ctx)(f)

  /** Migration helper for porting `NativeImpl` bodies to the capability-typed SPI.
   *
   *  Replace `NativeImpl { (ctx, args) => body }` with
   *  `PluginNative.evalLegacy { (ctx, args) => body }`, where `ctx` is now a
   *  `PluginContext` (capability-typed) instead of raw `NativeContext`.
   *  The body may still use interpreter-internal types such as `Value.*`;
   *  those imports are acceptable in built-in monorepo plugins for Phase 3.
   *
   *  Once `Value` is exposed through `PluginApi` (v2.x), `evalLegacy` will be
   *  removed and all bodies will migrate to the fully opaque `eval` form. */
  def evalLegacy(f: (PluginContext, List[Any]) => Any): NativeImpl =
    NativeImpl { (ctx, args) =>
      val pluginCtx = PluginContext.fromNative(ctx)
      f(pluginCtx, args)
    }
