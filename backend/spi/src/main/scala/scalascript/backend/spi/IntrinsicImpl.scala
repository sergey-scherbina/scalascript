package scalascript.backend.spi

import scalascript.ir.{IrExpr, EmitContext, TargetCode}

/** How a backend implements a single platform intrinsic (`extern def`
 *  marker, see docs/backend-spi.md §8).
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

/** Runtime hooks an in-process native intrinsic may consult.  The
 *  interpreter constructs one per session and passes it to every
 *  `NativeImpl.eval` call.  Stateless intrinsics (`nowMillis`,
 *  `hashSha256`, …) ignore it. */
trait NativeContext:
  def out: java.io.PrintStream
  def err: java.io.PrintStream
  // All methods below default to no-op / identity; the interpreter
  // overrides them in `installNativeIntrinsics` so HTTP intrinsics
  // can live in `InterpreterCapabilities` without a circular dependency.
  def headless: Boolean = false
  def registerRoute(method: String, path: String, handler: Any): Unit = ()
  def registerHealthDefaults(): Unit = ()
  // Invoke a user-supplied callback (Value closure/NativeFn) from native code.
  def invokeCallback(fn: Any, args: List[Any]): Any = ()
  // Outbound HTTP client state — scoped inside httpClient{} blocks.
  def httpBaseUrl: String = ""
  def httpTimeoutMs: Long = 30000L
  def httpMaxRetries: Int = 0
  def httpRetryDelayMs: Long = 1000L
  def setHttpTimeout(ms: Long): Unit = ()
  def setHttpRetry(maxAttempts: Int, delayMs: Long): Unit = ()
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
