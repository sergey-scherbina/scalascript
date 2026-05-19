package scalascript.server.jvm

import java.util.ServiceLoader
import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.spi.HttpServerSpi

/** ServiceLoader discovery smoke — confirms that the
 *  `META-INF/services/scalascript.server.spi.HttpServerSpi` resource
 *  registers `JdkServerBackend` and that the SPI machinery picks it
 *  up at runtime.  Doesn't exercise `start` (still stubbed in S1a)
 *  — just the discovery + instantiation path. */
class JdkServerBackendDiscoveryTest extends AnyFunSuite:

  test("ServiceLoader[HttpServerSpi] finds JdkServerBackend") {
    val impls = ServiceLoader.load(classOf[HttpServerSpi]).iterator()
    var found: Option[HttpServerSpi] = None
    while impls.hasNext do
      val impl = impls.next()
      if impl.name == "jdk" then found = Some(impl)
    found match
      case Some(b) =>
        assert(b.isInstanceOf[JdkServerBackend])
        assert(b.name == "jdk")
      case None =>
        fail("no HttpServerSpi registered under name=\"jdk\" — " +
             "META-INF/services file missing or wrong class name?")
  }

  test("JdkServerBackend — start binds an ephemeral port and stop() is idempotent") {
    val backend = new JdkServerBackend
    val handler = new scalascript.server.spi.HttpHandler:
      override def onHttpRequest(req: scalascript.server.Request)
          : scalascript.server.spi.HttpResult =
        scalascript.server.spi.HttpResult.Reject(404, "no routes")
      override def onWsUpgrade(req: scalascript.server.Request)
          : scalascript.server.spi.WsUpgradeResult =
        scalascript.server.spi.WsUpgradeResult.Reject(404, "no routes")
    try
      backend.start(0, None, handler)
      assert(backend.isRunning)
      assert(backend.localPort > 0)
    finally
      backend.stop()
      // Idempotent — second stop is a no-op.
      backend.stop()
      assert(!backend.isRunning)
  }
