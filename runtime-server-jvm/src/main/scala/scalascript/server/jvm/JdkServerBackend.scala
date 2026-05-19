package scalascript.server.jvm

import scalascript.server.spi.*

/** JDK-based `HttpServerSpi` implementation — the default backend
 *  with zero external dependencies.  Wraps the JDK
 *  `com.sun.net.httpserver.HttpServer` + our custom blocking
 *  `ServerSocket` + per-VT proxy + shared `WebSocket` class.
 *
 *  This is the same code path that has been the only impl since
 *  v1.17.4; this class is a thin façade so it can be discovered
 *  via `ServiceLoader[HttpServerSpi]` alongside the optional
 *  Jetty / Netty impls.
 *
 *  **Phase S1 status — STUB.**  The trait methods are wired but
 *  delegate-to-existing-runtime integration is deferred to a later
 *  session (S1b).  Calling `start` today throws
 *  `NotImplementedError` so accidental use is loud.  The shipped
 *  interpreter `WebServer.start` and codegen `serveRuntime` still
 *  construct `WsProxy` / `ProxyRuntime` directly — they will be
 *  switched to go through this SPI in S1b. */
class JdkServerBackend extends HttpServerSpi:

  override val name: String = "jdk"

  @volatile private var _running:   Boolean = false
  // _localPort will be set once `start` actually binds a socket in S1b.
  // `@scala.annotation.unused` so -Werror is happy until then.
  @scala.annotation.unused
  @volatile private var _localPort: Int     = 0

  override def start(
      port:    Int,
      tls:     Option[TlsConfig],
      handler: HttpHandler
  ): Unit =
    throw new NotImplementedError(
      "S1b — JdkServerBackend.start: integrate with ProxyRuntime + " +
      "WebSocketRuntime to drive accept loop + dispatch via the supplied " +
      "HttpHandler.  Until then, interpreter / codegen still construct " +
      "WsProxy directly.")

  override def stop(): Unit = _running = false

  override def isRunning: Boolean = _running

  override def localPort: Int = _localPort
