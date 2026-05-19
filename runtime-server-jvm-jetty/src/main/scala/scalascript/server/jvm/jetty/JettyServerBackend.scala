package scalascript.server.jvm.jetty

import scalascript.server.spi.*

/** Jetty 12-based `HttpServerSpi` implementation.  Optional —
 *  pulls Jetty (~3 MB) as a runtime dependency.  Enables HTTP/2,
 *  permessage-deflate WS compression, production-grade TLS
 *  (Conscrypt / BoringSSL integration), and other Jetty extensions.
 *
 *  **Phase S2 status — STUB.**  Module declaration + ServiceLoader
 *  registration in place so the SPI is discoverable when the module
 *  is on the classpath, but the trait methods throw
 *  `NotImplementedError`.  S2 fills these in:
 *
 *    - `Server` + `ServerConnector` for the HTTP listener.
 *    - `WebSocketUpgradeHandler` for the WS upgrade path.
 *    - A custom Jetty `Handler` that translates Jetty's `Request` /
 *      `Response` into our POJO shape and calls the supplied
 *      `HttpHandler`.
 *    - A `WsControls` adapter wrapping Jetty's `Session`. */
class JettyServerBackend extends HttpServerSpi:

  override val name: String = "jetty"

  override def start(
      port:    Int,
      tls:     Option[TlsConfig],
      handler: HttpHandler
  ): Unit =
    throw new NotImplementedError(
      "S2 — JettyServerBackend.start: implement Jetty 12 wrapper that " +
      "translates jetty.Request <-> scalascript.server.Request and " +
      "drives the supplied HttpHandler.")

  override def stop(): Unit =
    throw new NotImplementedError("S2 — JettyServerBackend.stop")

  override def isRunning: Boolean = false
  override def localPort: Int     = 0
