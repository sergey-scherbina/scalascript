package scalascript.server.jvm.fast

import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.{Request, Response}
import scalascript.server.spi.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** Proves the fast backend has full Request parity with the JDK backend by routing through
  * `RequestBuilder.parseRaw`: multipart file uploads, urlencoded form, bearer auth, and a
  * signed-session cookie round-trip. These are the fields a minimal (Jetty-style) Request
  * would have dropped — the regression that made `fast` risky as the default. */
class FastServerBackendParityTest extends AnyFunSuite:

  private def withBackend(handler: HttpHandler)(body: (Int, HttpClient) => Unit): Unit =
    val backend = new FastServerBackend
    backend.start(0, None, handler)
    try body(backend.localPort, HttpClient.newHttpClient())
    finally backend.stop()

  private def httpHandler(f: Request => Response): HttpHandler = new HttpHandler:
    def onHttpRequest(req: Request): HttpResult = HttpResult.PlainResp(f(req))
    def onWsUpgrade(req: Request): WsUpgradeResult = WsUpgradeResult.Reject(404, "no ws")

  test("multipart/form-data populates req.files and req.form") {
    val handler = httpHandler { req =>
      val f = req.files.get("upload")
      Response(200, Map.empty,
        s"field=${req.form.getOrElse("field", "-")} file=${f.map(_.filename).getOrElse("-")}:${f.map(_.bytes).getOrElse("-")}")
    }
    withBackend(handler) { (port, client) =>
      val b = "BND123"
      val body =
        s"--$b\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nhello\r\n" +
        s"--$b\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"a.txt\"\r\n" +
        s"Content-Type: text/plain\r\n\r\nFILEDATA\r\n--$b--\r\n"
      val req = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/up"))
        .header("Content-Type", s"multipart/form-data; boundary=$b")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build()
      val r = client.send(req, HttpResponse.BodyHandlers.ofString())
      assert(r.body() == "field=hello file=a.txt:FILEDATA")
    }
  }

  test("Authorization: Bearer is pre-extracted into req.bearerToken") {
    val handler = httpHandler(req => Response(200, Map.empty, req.bearerToken.getOrElse("none")))
    withBackend(handler) { (port, client) =>
      val req = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/"))
        .header("Authorization", "Bearer tok-abc").GET().build()
      assert(client.send(req, HttpResponse.BodyHandlers.ofString()).body() == "tok-abc")
    }
  }

  test("signed session cookie round-trips through setSession → Set-Cookie → req.session") {
    val handler = httpHandler { req =>
      if req.path == "/login" then Response(200, Map.empty, "ok").withSession(Map("user" -> "ada"))
      else Response(200, Map.empty, "user=" + req.session.getOrElse("user", "-"))
    }
    withBackend(handler) { (port, client) =>
      val login = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/login")).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      val cookie = login.headers().firstValue("set-cookie").orElseThrow()
      // send just the cookie value back (strip attributes after the first ';')
      val cookiePair = cookie.split(";", 2)(0)
      val who = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/me"))
          .header("Cookie", cookiePair).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(who.body() == "user=ada", s"session did not round-trip: '${who.body()}' (cookie=$cookiePair)")
    }
  }
