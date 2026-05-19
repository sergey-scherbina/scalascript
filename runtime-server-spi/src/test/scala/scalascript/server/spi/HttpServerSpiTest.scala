package scalascript.server.spi

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Smoke test for the SPI module — verifies the trait definitions
 *  compile and the data types instantiate.  Does NOT exercise
 *  ServiceLoader discovery here (no impl module on this module's
 *  test classpath).  See `HttpServerSpiDiscoveryTest` (added with
 *  S1b once an impl module is wired) for the cross-module
 *  ServiceLoader smoke. */
class HttpServerSpiTest extends AnyFunSuite with Matchers:

  test("TlsConfig — case-class equality") {
    assert(TlsConfig("cert.pem", "key.pem") == TlsConfig("cert.pem", "key.pem"))
  }

  test("HttpResult — Reject default content-type") {
    HttpResult.Reject(413, "Too Large") match
      case HttpResult.Reject(s, b, ct) =>
        assert(s == 413); assert(b == "Too Large"); assert(ct == "text/plain; charset=utf-8")
      case _ => fail("expected Reject")
  }

  test("WsUpgradeResult — Reject carries status + reason") {
    WsUpgradeResult.Reject(403, "Forbidden") match
      case WsUpgradeResult.Reject(s, r) => assert(s == 403); assert(r == "Forbidden")
      case _ => fail("expected Reject")
  }

  test("HttpServerSpi — trait extension works") {
    // Anonymous impl just to confirm the trait shape compiles end-to-end.
    val backend = new HttpServerSpi:
      override val name: String = "test"
      override def start(p: Int, t: Option[TlsConfig], h: HttpHandler): Unit = ()
      override def stop(): Unit = ()
      override def isRunning: Boolean = false
      override def localPort: Int     = 0
    assert(backend.name == "test")
  }

  test("HttpHandler / WsListener / WsControls — trait extension works") {
    val controls = new WsControls:
      override def id: String = "test-id"
      override def remoteAddress: String = "127.0.0.1:0"
      override def subprotocol: String = ""
      override def send(text: String): Unit = ()
      override def sendBytes(bytes: Array[Byte]): Unit = ()
      override def ping(payload: Array[Byte]): Unit = ()
      override def close(code: Int, reason: String): Unit = ()
      override def isClosed: Boolean = false
    assert(controls.id == "test-id")

    val listener = new WsListener:
      override def onOpen(c: WsControls): Unit = ()
      override def onMessage(text: String): Unit = ()
      override def onBinary(bytes: Array[Byte]): Unit = ()
      override def onPong(payload: Array[Byte]): Unit = ()
      override def onClose(code: Int, reason: String): Unit = ()
      override def onError(t: Throwable): Unit = ()

    val handler = new HttpHandler:
      override def onHttpRequest(req: scalascript.server.Request) = HttpResult.Reject(404, "Not Found")
      override def onWsUpgrade(req: scalascript.server.Request) = WsUpgradeResult.Accept("v1", listener)

    handler.onHttpRequest(null) match
      case HttpResult.Reject(s, _, _) => assert(s == 404)
      case _ => fail("expected Reject")
    handler.onWsUpgrade(null) match
      case WsUpgradeResult.Accept(p, _) => assert(p == "v1")
      case _ => fail("expected Accept")
  }
