package scalascript.server.jvm

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.server.Response

/** Tests for the `Response.html/text/json/redirect/notFound/status/
 *  basicAuthChallenge` extension methods.  After Phase 3b these are
 *  in `extension (r: Response.type)` (the case class lives in
 *  runtime-server-common, so a companion object can't re-open
 *  across compilation units). */
class ResponseFactoriesTest extends AnyFunSuite with Matchers:

  test("Response.html — sets 200 + text/html content-type") {
    val r = Response.html("<h1>hi</h1>")
    r.status shouldBe 200
    r.headers.get("Content-Type") shouldBe Some("text/html; charset=utf-8")
    r.body shouldBe "<h1>hi</h1>"
  }

  test("Response.text — sets 200 + text/plain content-type") {
    val r = Response.text("plain message")
    r.status shouldBe 200
    r.headers.get("Content-Type") shouldBe Some("text/plain; charset=utf-8")
    r.body shouldBe "plain message"
  }

  test("Response.json — sets 200 + application/json + JSON-encoded body") {
    val r = Response.json(Map("k" -> 1))
    r.status shouldBe 200
    r.headers.get("Content-Type") shouldBe Some("application/json")
    r.body should startWith ("{")
    r.body should endWith ("}")
  }

  test("Response.redirect — 302 + Location header") {
    val r = Response.redirect("/elsewhere")
    r.status shouldBe 302
    r.headers.get("Location") shouldBe Some("/elsewhere")
  }

  test("Response.notFound — 404 with default body") {
    Response.notFound().status shouldBe 404
    Response.notFound("missing").body shouldBe "missing"
  }

  test("Response.status(code, body) — arbitrary status code") {
    Response.status(418).status shouldBe 418
    Response.status(503, "down").body shouldBe "down"
  }

  test("Response.basicAuthChallenge — 401 + WWW-Authenticate") {
    val r = Response.basicAuthChallenge("Members Only")
    r.status shouldBe 401
    r.headers.get("WWW-Authenticate").get should include ("Basic")
    r.headers.get("WWW-Authenticate").get should include ("Members Only")
  }

  test("Response.basicAuthChallenge — realm quoting is escape-safe") {
    val r = Response.basicAuthChallenge("Has\"Quotes")
    r.headers.get("WWW-Authenticate").get should include ("Has\\\"Quotes")
  }
