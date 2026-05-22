package scalascript.server.jvm.jetty

import java.util.ServiceLoader
import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.{Request, Response}
import scalascript.server.spi.{HttpHandler, HttpResult, HttpServerSpi, WsUpgradeResult}

/** Confirms JettyServerBackend is registered via
 *  META-INF/services/scalascript.server.spi.HttpServerSpi and that
 *  the SPI picks it up.  After S2 also exercises `start` on an
 *  ephemeral port — no longer a stub. */
class JettyServerBackendDiscoveryTest extends AnyFunSuite:

  test("ServiceLoader finds JettyServerBackend under name=\"jetty\"") {
    val it = ServiceLoader.load(classOf[HttpServerSpi]).iterator()
    var found: Option[HttpServerSpi] = None
    while it.hasNext do
      val impl = it.next()
      if impl.name == "jetty" then found = Some(impl)
    found match
      case Some(b) => assert(b.isInstanceOf[JettyServerBackend])
      case None    => fail("Jetty SPI impl not discovered via ServiceLoader")
  }

  test("JettyServerBackend — start binds an ephemeral port and stop cleans up") {
    val backend = new JettyServerBackend()
    val handler = new HttpHandler:
      override def onHttpRequest(req: Request): HttpResult =
        HttpResult.PlainResp(Response(200, Map.empty, "ok"))
      override def onWsUpgrade(req: Request): WsUpgradeResult =
        WsUpgradeResult.Reject(404, "no ws here")
    try
      backend.start(0, None, handler)
      assert(backend.isRunning, "expected isRunning after start")
      assert(backend.localPort > 0, s"expected non-zero local port, got ${backend.localPort}")
    finally backend.stop()
    assert(!backend.isRunning, "expected !isRunning after stop")
  }
