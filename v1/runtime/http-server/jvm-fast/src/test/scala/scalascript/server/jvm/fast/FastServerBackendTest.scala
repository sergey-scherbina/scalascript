package scalascript.server.jvm.fast

import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.{Request, Response}
import scalascript.server.spi.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}

/** Drives the backend through the `HttpServerSpi` contract exactly as the v1 WebServer does —
  * a mock `HttpHandler` + a real java.net.http client — to prove HTTP dispatch, streaming, and
  * the WS accept/reject path all work on the fast engine. */
class FastServerBackendTest extends AnyFunSuite:

  private def echoListener(sink: LinkedBlockingQueue[String]): WebSocket.Listener =
    new WebSocket.Listener:
      override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletableFuture[?] =
        sink.add(data.toString); ws.request(1); null

  private val handler = new HttpHandler:
    def onHttpRequest(req: Request): HttpResult =
      if req.path == "/pair" then
        if req.form.get("code").contains("123456") then
          HttpResult.PlainResp(Response(200,
            Map("Set-Cookie" -> "busi_device=fixture; Path=/; HttpOnly; SameSite=Lax"),
            "paired"))
        else HttpResult.PlainResp(Response(400, body = "bad code"))
      else if req.path == "/protected" then
        if req.cookies.get("busi_device").contains("fixture") then
          HttpResult.PlainResp(Response(200, body = "owner"))
        else HttpResult.PlainResp(Response(401, body = "unpaired"))
      else if req.path == "/signed-session" then
        HttpResult.PlainResp(Response(200, body = "session",
          setSession = Some(Map("user" -> "owner"))))
      else if req.path == "/session-view" then
        HttpResult.PlainResp(Response(if req.session.get("user").contains("owner") then 200 else 401,
          body = req.session.getOrElse("user", "missing")))
      else if req.path == "/bearer" then
        HttpResult.PlainResp(Response(if req.bearerToken.contains("owner-token") then 200 else 401,
          body = req.bearerToken.getOrElse("missing")))
      else if req.path == "/basic" then
        HttpResult.PlainResp(Response(if req.basicAuth.contains(("owner", "secret")) then 200 else 401,
          body = req.basicAuth.map((u, _) => u).getOrElse("missing")))
      else if req.path == "/stream" then
        HttpResult.StreamResp(scalascript.server.StreamResponse(200,
          Map("Content-Type" -> "text/plain"), emit => { emit("a"); emit("b"); () }))
      else
        HttpResult.PlainResp(Response(200, Map("Content-Type" -> "text/plain"),
          s"${req.method} ${req.path}"))
    def onWsUpgrade(req: Request): WsUpgradeResult =
      if req.path == "/ws" then
        WsUpgradeResult.Accept("", new WsListener:
          @volatile private var ctl: WsControls = null
          def onOpen(controls: WsControls): Unit = ctl = controls
          def onMessage(text: String): Unit = ctl.send("echo:" + text)
          def onBinary(bytes: Array[Byte]): Unit = ()
          def onPong(payload: Array[Byte]): Unit = ()
          def onClose(code: Int, reason: String): Unit = ()
          def onError(t: Throwable): Unit = ())
      else WsUpgradeResult.Reject(404, "Not Found")

  private def withBackend(body: (Int, HttpClient) => Unit): Unit =
    val backend = new FastServerBackend
    backend.start(0, None, handler)
    try body(backend.localPort, HttpClient.newHttpClient())
    finally backend.stop()

  test("HTTP request dispatches through the SPI handler") {
    withBackend { (port, client) =>
      val r = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/hello")).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(r.statusCode() == 200)
      assert(r.body() == "GET /hello")
      assert(backend_isRunning(port))
    }
  }

  test("streaming response drives the writer") {
    withBackend { (port, client) =>
      val r = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/stream")).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(r.body() == "ab")
    }
  }

  test("urlencoded pairing form sets a cookie that authenticates the next request") {
    withBackend { (port, client) =>
      val pair = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/pair"))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString("code=123456")).build(),
        HttpResponse.BodyHandlers.ofString())
      assert(pair.statusCode() == 200)
      val cookie = pair.headers().firstValue("set-cookie").orElse("")
      assert(cookie == "busi_device=fixture; Path=/; HttpOnly; SameSite=Lax")

      val protectedResponse = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/protected"))
          .header("Cookie", "busi_device=fixture").GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(protectedResponse.statusCode() == 200)
      assert(protectedResponse.body() == "owner")

      val session = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/signed-session")).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      val sessionCookie = session.headers().firstValue("set-cookie").orElse("")
      assert(sessionCookie.startsWith("session="))
      val sessionView = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/session-view"))
          .header("Cookie", sessionCookie.takeWhile(_ != ';')).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(sessionView.statusCode() == 200 && sessionView.body() == "owner")

      val bearer = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/bearer"))
          .header("Authorization", "Bearer owner-token").GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(bearer.statusCode() == 200 && bearer.body() == "owner-token")
      val basic = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/basic"))
          .header("Authorization", "Basic b3duZXI6c2VjcmV0").GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(basic.statusCode() == 200 && basic.body() == "owner")
    }
  }

  test("WebSocket upgrade is accepted and echoes") {
    withBackend { (port, client) =>
      val got = new LinkedBlockingQueue[String]()
      val ws = client.newWebSocketBuilder()
        .buildAsync(URI.create(s"ws://127.0.0.1:$port/ws"), echoListener(got)).join()
      ws.sendText("hi", true).join()
      assert(got.poll(5, TimeUnit.SECONDS) == "echo:hi")
      ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join()
    }
  }

  test("WebSocket upgrade to an unknown path is rejected") {
    withBackend { (port, client) =>
      val rejected =
        try { client.newWebSocketBuilder()
          .buildAsync(URI.create(s"ws://127.0.0.1:$port/nope"), new WebSocket.Listener {}).join(); false }
        catch case _: Throwable => true
      assert(rejected, "expected the WS upgrade to be rejected")
    }
  }

  private def backend_isRunning(port: Int): Boolean = port > 0
