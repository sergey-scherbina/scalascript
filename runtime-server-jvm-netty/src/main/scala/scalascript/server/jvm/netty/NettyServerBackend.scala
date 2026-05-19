package scalascript.server.jvm.netty

import scalascript.server.spi.*

/** Netty 4-based `HttpServerSpi` implementation.  Optional — pulls
 *  Netty (~4 MB) as a runtime dependency.  Highest throughput per
 *  core (event-loop model), HTTP/3 incubator support, and custom
 *  protocol extensibility.
 *
 *  **Phase S3 status — STUB.**  Module declaration + ServiceLoader
 *  registration in place; trait methods throw `NotImplementedError`.
 *  S3 fills these in:
 *
 *    - `ServerBootstrap` with `EventLoopGroup` + `ChannelInitializer`.
 *    - `HttpServerCodec` + `HttpObjectAggregator` + a custom
 *      `ChannelInboundHandler` that translates Netty's `FullHttpRequest`
 *      into our POJO shape and calls the supplied `HttpHandler`.
 *    - `WebSocketServerProtocolHandler` for the WS upgrade.
 *    - A `WsControls` adapter wrapping Netty's `Channel`. */
class NettyServerBackend extends HttpServerSpi:

  override val name: String = "netty"

  override def start(
      port:    Int,
      tls:     Option[TlsConfig],
      handler: HttpHandler
  ): Unit =
    throw new NotImplementedError(
      "S3 — NettyServerBackend.start: implement Netty 4 wrapper that " +
      "translates Netty's FullHttpRequest <-> scalascript.server.Request " +
      "and drives the supplied HttpHandler.")

  override def stop(): Unit =
    throw new NotImplementedError("S3 — NettyServerBackend.stop")

  override def isRunning: Boolean = false
  override def localPort: Int     = 0
