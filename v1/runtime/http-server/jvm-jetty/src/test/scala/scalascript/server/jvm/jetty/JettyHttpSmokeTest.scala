package scalascript.server.jvm.jetty

import java.net.{HttpURLConnection, URI}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.{Request, Response}
import scalascript.server.spi.{HttpHandler, HttpResult, WsUpgradeResult}

/** End-to-end smoke test: the Jetty backend serves a plain HTTP request
 *  with the body our HttpHandler produced. */
class JettyHttpSmokeTest extends AnyFunSuite:

  test("Jetty backend — plain HTTP GET returns handler's PlainResp body") {
    val backend = new JettyServerBackend()
    val handler = new HttpHandler:
      override def onHttpRequest(req: Request): HttpResult =
        HttpResult.PlainResp(
          Response(200, Map("Content-Type" -> "text/plain; charset=utf-8"), "hello")
        )
      override def onWsUpgrade(req: Request): WsUpgradeResult =
        WsUpgradeResult.Reject(404, "no ws")
    try
      backend.start(0, None, handler)
      val port = backend.localPort
      val url  = new URI(s"http://127.0.0.1:$port/anything").toURL
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("GET")
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(5000)
      assert(conn.getResponseCode == 200)
      val body =
        try new String(conn.getInputStream.readAllBytes(), "UTF-8")
        finally conn.disconnect()
      assert(body == "hello", s"expected 'hello', got '$body'")
    finally backend.stop()
  }

  test("Jetty backend — Reject result yields the requested status code") {
    val backend = new JettyServerBackend()
    val handler = new HttpHandler:
      override def onHttpRequest(req: Request): HttpResult =
        HttpResult.Reject(413, "too big", "text/plain")
      override def onWsUpgrade(req: Request): WsUpgradeResult =
        WsUpgradeResult.Reject(404, "no ws")
    try
      backend.start(0, None, handler)
      val port = backend.localPort
      val url  = new URI(s"http://127.0.0.1:$port/").toURL
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("GET")
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(5000)
      assert(conn.getResponseCode == 413)
      conn.disconnect()
    finally backend.stop()
  }
