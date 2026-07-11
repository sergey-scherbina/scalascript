package scalascript.server.jvm.fast

import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.{Request, Response, StreamResponse}
import scalascript.server.spi.*
import scalascript.server.jvm.JdkServerBackend
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket => JdkWs}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

/** De-risks making `fast` the default transport: runs an identical full HTTP + WebSocket
  * contract battery against BOTH the trusted `JdkServerBackend` and the `FastServerBackend`
  * through the same `HttpServerSpi` / `HttpHandler`. The v1 WebServer framework sits unchanged
  * above this SPI, so proving the two backends behave identically proves the default swap is
  * safe. Covers method/body/form/multipart-files/query/bearer/cookie/session/custom-headers/
  * status, all three `HttpResult` shapes, and WS echo + reject. */
class BackendParityConformanceTest extends AnyFunSuite:

  private val backends: List[(String, () => HttpServerSpi)] =
    List("jdk" -> (() => new JdkServerBackend), "fast" -> (() => new FastServerBackend))

  private def text(s: String): HttpResult = HttpResult.PlainResp(Response(200, Map.empty, s))

  private val handler = new HttpHandler:
    def onHttpRequest(req: Request): HttpResult = req.path match
      case "/method"  => text(req.method)
      case "/body"    => text(req.body)
      case "/form"    => text(req.form.getOrElse("name", "-"))
      case "/file"    => text(req.files.get("f").map(f => s"${f.filename}:${f.bytes}").getOrElse("-"))
      case "/query"   => text(req.query.getOrElse("q", "-"))
      case "/auth"    => text(req.bearerToken.getOrElse("-"))
      case "/cookie"  => text(req.cookies.getOrElse("sid", "-"))
      case "/login"   => HttpResult.PlainResp(Response(200, Map.empty, "ok").withSession(Map("u" -> "ada")))
      case "/session" => text(req.session.getOrElse("u", "-"))
      case "/created" => HttpResult.PlainResp(Response(201, Map("X-Custom" -> "yes"), "created"))
      case "/reject"  => HttpResult.Reject(418, "teapot")
      case "/stream"  =>
        HttpResult.StreamResp(StreamResponse(200, Map("Content-Type" -> "text/plain"),
          emit => { emit("a"); emit("b"); () }))
      case _          => HttpResult.Reject(404, "not found")

    def onWsUpgrade(req: Request): WsUpgradeResult =
      if req.path == "/ws" then
        val ctl = new AtomicReference[WsControls]()
        WsUpgradeResult.Accept("", new WsListener:
          def onOpen(c: WsControls): Unit    = ctl.set(c)
          def onMessage(t: String): Unit     = { val c = ctl.get(); if c != null then c.send("echo:" + t) }
          def onBinary(b: Array[Byte]): Unit = ()
          def onPong(p: Array[Byte]): Unit   = ()
          def onClose(code: Int, reason: String): Unit = ()
          def onError(t: Throwable): Unit    = ())
      else WsUpgradeResult.Reject(404, "no ws")

  private def send(client: HttpClient, b: HttpRequest.Builder): HttpResponse[String] =
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  for (name, mk) <- backends do
    test(s"[$name] HTTP contract (method/body/form/multipart/query/auth/cookie/session/status/reject/stream/404)") {
      val backend = mk()
      backend.start(0, None, handler)
      val port = backend.localPort
      val base = s"http://127.0.0.1:$port"
      try
        val c = HttpClient.newHttpClient()
        def g(path: String) = send(c, HttpRequest.newBuilder(URI.create(base + path)).GET())

        assert(g("/method").body() == "GET")
        assert(send(c, HttpRequest.newBuilder(URI.create(s"$base/body"))
          .POST(HttpRequest.BodyPublishers.ofString("payload"))).body() == "payload")
        assert(send(c, HttpRequest.newBuilder(URI.create(s"$base/form"))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString("name=ada"))).body() == "ada")

        // multipart file upload → req.files
        val bnd  = "BND"
        val body =
          s"--$bnd\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a.txt\"\r\n" +
          s"Content-Type: text/plain\r\n\r\nDATA\r\n--$bnd--\r\n"
        assert(send(c, HttpRequest.newBuilder(URI.create(s"$base/file"))
          .header("Content-Type", s"multipart/form-data; boundary=$bnd")
          .POST(HttpRequest.BodyPublishers.ofString(body))).body() == "a.txt:DATA")

        assert(g("/query?q=hi").body() == "hi")
        assert(send(c, HttpRequest.newBuilder(URI.create(s"$base/auth"))
          .header("Authorization", "Bearer tok").GET()).body() == "tok")
        assert(send(c, HttpRequest.newBuilder(URI.create(s"$base/cookie"))
          .header("Cookie", "sid=xyz").GET()).body() == "xyz")

        // signed-session round-trip
        val login  = g("/login")
        val cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)(0)
        assert(send(c, HttpRequest.newBuilder(URI.create(s"$base/session"))
          .header("Cookie", cookie).GET()).body() == "ada")

        val created = g("/created")
        assert(created.statusCode() == 201 && created.headers().firstValue("x-custom").get == "yes")
        assert(g("/reject").statusCode() == 418)
        assert(g("/stream").body() == "ab")
        assert(g("/unknown").statusCode() == 404)
      finally backend.stop()
    }

    test(s"[$name] WebSocket echo + reject") {
      val backend = mk()
      backend.start(0, None, handler)
      val port = backend.localPort
      try
        val client = HttpClient.newHttpClient()
        val got    = new AtomicReference[String]()
        val latch  = new CountDownLatch(1)
        val ws = client.newWebSocketBuilder().buildAsync(URI.create(s"ws://127.0.0.1:$port/ws"),
          new JdkWs.Listener:
            override def onText(w: JdkWs, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage[?] =
              got.set(data.toString); latch.countDown(); w.request(1); null
        ).get(5, TimeUnit.SECONDS)
        ws.sendText("hi", true).get(5, TimeUnit.SECONDS)
        assert(latch.await(5, TimeUnit.SECONDS), "no echo")
        assert(got.get() == "echo:hi")
        ws.sendClose(JdkWs.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)

        val rejected =
          try { client.newWebSocketBuilder()
            .buildAsync(URI.create(s"ws://127.0.0.1:$port/nope"), new JdkWs.Listener {})
            .get(5, TimeUnit.SECONDS); false }
          catch case _: Throwable => true
        assert(rejected, "reject upgrade should fail the client handshake")
      finally backend.stop()
    }
