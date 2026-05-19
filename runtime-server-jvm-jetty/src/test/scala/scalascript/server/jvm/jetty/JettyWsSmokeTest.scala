package scalascript.server.jvm.jetty

import java.net.URI
import java.net.http.{HttpClient, WebSocket as JdkWs}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.{Request, Response}
import scalascript.server.spi.{HttpHandler, HttpResult, WsControls, WsListener, WsUpgradeResult}

/** End-to-end smoke test: the Jetty backend upgrades a WS connection
 *  and echoes a single text frame back to the client. */
class JettyWsSmokeTest extends AnyFunSuite:

  test("Jetty backend — WS echo round-trip") {
    val backend = new JettyServerBackend()
    val controlsRef = new AtomicReference[WsControls]()

    val listener = new WsListener:
      override def onOpen(c: WsControls): Unit = controlsRef.set(c)
      override def onMessage(text: String): Unit =
        val c = controlsRef.get()
        if c != null then c.send(text) // echo
      override def onBinary(bytes: Array[Byte]): Unit = ()
      override def onPong(payload: Array[Byte]): Unit = ()
      override def onClose(code: Int, reason: String): Unit = ()
      override def onError(t: Throwable): Unit = ()

    val handler = new HttpHandler:
      override def onHttpRequest(req: Request): HttpResult =
        HttpResult.PlainResp(Response(200, Map.empty, "http-fallback"))
      override def onWsUpgrade(req: Request): WsUpgradeResult =
        WsUpgradeResult.Accept(subprotocol = "", listener = listener)

    try
      backend.start(0, None, handler)
      val port = backend.localPort

      val received = new AtomicReference[String]()
      val latch    = new CountDownLatch(1)

      val clientListener = new JdkWs.Listener:
        override def onText(ws: JdkWs, data: CharSequence, last: Boolean) =
          received.set(data.toString)
          latch.countDown()
          ws.request(1)
          null

      val client = HttpClient.newHttpClient()
      val uri    = new URI(s"ws://127.0.0.1:$port/ws")
      val ws     = client.newWebSocketBuilder()
        .buildAsync(uri, clientListener)
        .get(5, TimeUnit.SECONDS)

      ws.sendText("ping", true).get(5, TimeUnit.SECONDS)
      assert(latch.await(5, TimeUnit.SECONDS), "client never received echo within 5 s")
      assert(received.get() == "ping", s"expected 'ping', got '${received.get()}'")

      ws.sendClose(JdkWs.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
    finally backend.stop()
  }

  test("Jetty backend — WS upgrade Reject produces non-101 status") {
    val backend = new JettyServerBackend()
    val handler = new HttpHandler:
      override def onHttpRequest(req: Request): HttpResult =
        HttpResult.PlainResp(Response(200, Map.empty, ""))
      override def onWsUpgrade(req: Request): WsUpgradeResult =
        WsUpgradeResult.Reject(403, "no access")

    try
      backend.start(0, None, handler)
      val port = backend.localPort

      val client = HttpClient.newHttpClient()
      val uri    = new URI(s"ws://127.0.0.1:$port/ws")
      val opened =
        try
          client.newWebSocketBuilder()
            .buildAsync(uri, new JdkWs.Listener {})
            .get(5, TimeUnit.SECONDS)
          true
        catch case _: Throwable => false
      assert(!opened, "expected ws upgrade to fail with non-101")
    finally backend.stop()
  }
