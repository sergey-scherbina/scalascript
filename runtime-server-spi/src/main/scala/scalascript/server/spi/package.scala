package scalascript.server

/** SPI for pluggable HTTP/WS network-layer backends.  See
 *  `HttpServerSpi.scala` for the trait definitions and
 *  `docs/http-server-spi-plan.md` for the design rationale.
 *
 *  Three implementations live downstream of this module:
 *    - `scalascript.server.jvm.JdkServerBackend` in `runtime-server-jvm`
 *    - `scalascript.server.jvm.jetty.JettyServerBackend` in
 *      `runtime-server-jvm-jetty`
 *    - `scalascript.server.jvm.netty.NettyServerBackend` in
 *      `runtime-server-jvm-netty` */
package object spi
