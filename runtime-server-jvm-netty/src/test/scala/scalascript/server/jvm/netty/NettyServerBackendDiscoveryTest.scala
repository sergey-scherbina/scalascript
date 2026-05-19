package scalascript.server.jvm.netty

import java.util.ServiceLoader
import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.spi.HttpServerSpi

class NettyServerBackendDiscoveryTest extends AnyFunSuite:

  test("ServiceLoader finds NettyServerBackend under name=\"netty\"") {
    val it = ServiceLoader.load(classOf[HttpServerSpi]).iterator()
    var found: Option[HttpServerSpi] = None
    while it.hasNext do
      val impl = it.next()
      if impl.name == "netty" then found = Some(impl)
    found match
      case Some(b) => assert(b.isInstanceOf[NettyServerBackend])
      case None    => fail("Netty SPI impl not discovered via ServiceLoader")
  }

  test("NettyServerBackend — start throws NotImplementedError (S3 TODO)") {
    val ex = intercept[NotImplementedError] {
      new NettyServerBackend().start(0, None, null)
    }
    assert(ex.getMessage.contains("S3"))
  }
