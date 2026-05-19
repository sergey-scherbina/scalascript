package scalascript.server.jvm.jetty

import java.util.ServiceLoader
import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.spi.HttpServerSpi

/** Confirms JettyServerBackend is registered via
 *  META-INF/services/scalascript.server.spi.HttpServerSpi and that
 *  the SPI picks it up.  Doesn't yet exercise `start` (S2 stub —
 *  throws NotImplementedError). */
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

  test("JettyServerBackend — start throws NotImplementedError (S2 TODO)") {
    val ex = intercept[NotImplementedError] {
      new JettyServerBackend().start(0, None, null)
    }
    assert(ex.getMessage.contains("S2"))
  }
