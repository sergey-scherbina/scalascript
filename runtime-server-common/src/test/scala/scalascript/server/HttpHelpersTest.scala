package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class HttpHelpersTest extends AnyFunSuite with Matchers:

  // ── parsePath / matchPath ──────────────────────────────────────

  test("parsePath — literal and capture segments") {
    HttpHelpers.parsePath("/users/:id/posts") shouldBe List(
      HttpHelpers.Seg.Lit("users"),
      HttpHelpers.Seg.Cap("id"),
      HttpHelpers.Seg.Lit("posts")
    )
  }

  test("parsePath — root and trailing slash both yield empty list") {
    HttpHelpers.parsePath("/")  shouldBe Nil
    HttpHelpers.parsePath("")   shouldBe Nil
    HttpHelpers.parsePath("//") shouldBe Nil
  }

  test("matchPath — captures populate the result map") {
    val pat = HttpHelpers.parsePath("/users/:id/posts/:pid")
    HttpHelpers.matchPath(pat, List("users", "42", "posts", "abc")) shouldBe
      Some(Map("id" -> "42", "pid" -> "abc"))
  }

  test("matchPath — length mismatch returns None") {
    val pat = HttpHelpers.parsePath("/users/:id")
    HttpHelpers.matchPath(pat, List("users")) shouldBe None
    HttpHelpers.matchPath(pat, List("users", "1", "extra")) shouldBe None
  }

  test("matchPath — literal mismatch returns None") {
    val pat = HttpHelpers.parsePath("/users/:id")
    HttpHelpers.matchPath(pat, List("posts", "1")) shouldBe None
  }

  // ── parseQuery ─────────────────────────────────────────────────

  test("parseQuery — null and empty yield Map.empty") {
    HttpHelpers.parseQuery(null) shouldBe Map.empty
    HttpHelpers.parseQuery("")   shouldBe Map.empty
  }

  test("parseQuery — single and multiple kv pairs") {
    HttpHelpers.parseQuery("a=1")     shouldBe Map("a" -> "1")
    HttpHelpers.parseQuery("a=1&b=2") shouldBe Map("a" -> "1", "b" -> "2")
  }

  test("parseQuery — URL-encoded keys and values are decoded") {
    HttpHelpers.parseQuery("name=John%20Doe&q=a%2Bb") shouldBe
      Map("name" -> "John Doe", "q" -> "a+b")
  }

  test("parseQuery — value-less keys become key -> empty") {
    HttpHelpers.parseQuery("flag") shouldBe Map("flag" -> "")
  }

  // ── parseCookieHeader ──────────────────────────────────────────

  test("parseCookieHeader — empty / null yield Map.empty") {
    HttpHelpers.parseCookieHeader("")   shouldBe Map.empty
    HttpHelpers.parseCookieHeader(null) shouldBe Map.empty
  }

  test("parseCookieHeader — single, multiple, whitespace-tolerant") {
    HttpHelpers.parseCookieHeader("a=1")           shouldBe Map("a" -> "1")
    HttpHelpers.parseCookieHeader("a=1; b=2; c=3") shouldBe Map("a" -> "1", "b" -> "2", "c" -> "3")
    HttpHelpers.parseCookieHeader("  a = 1 ;  b=2") shouldBe Map("a" -> "1", "b" -> "2")
  }

  test("parseCookieHeader — pairs without `=` are dropped silently") {
    HttpHelpers.parseCookieHeader("a=1; bogus; b=2") shouldBe Map("a" -> "1", "b" -> "2")
  }

  // ── contentTypeFor ─────────────────────────────────────────────

  test("contentTypeFor — recognised extensions") {
    HttpHelpers.contentTypeFor("page.html")  shouldBe "text/html; charset=utf-8"
    HttpHelpers.contentTypeFor("style.css")  shouldBe "text/css; charset=utf-8"
    HttpHelpers.contentTypeFor("app.js")     shouldBe "application/javascript; charset=utf-8"
    HttpHelpers.contentTypeFor("data.json")  shouldBe "application/json; charset=utf-8"
    HttpHelpers.contentTypeFor("logo.svg")   shouldBe "image/svg+xml"
  }

  test("contentTypeFor — unknown extension falls back through probeContentType") {
    // Unknown garbage extension routes to JDK probe → final fallback.
    val r = HttpHelpers.contentTypeFor("file.xyzzz")
    r should not be empty
  }

  // ── readHttpHead ───────────────────────────────────────────────

  test("readHttpHead — stops at \\r\\n\\r\\n sentinel including the sentinel bytes") {
    val payload = "GET /x HTTP/1.1\r\nHost: a\r\n\r\nIGNORED-BODY".getBytes("ISO-8859-1")
    val in = new java.io.ByteArrayInputStream(payload)
    val head = HttpHelpers.readHttpHead(in)
    new String(head, "ISO-8859-1") shouldBe "GET /x HTTP/1.1\r\nHost: a\r\n\r\n"
  }

  test("readHttpHead — returns whatever was read on EOF before sentinel") {
    val payload = "GET /x HTTP/1.1\r\nHost: a\r\n".getBytes("ISO-8859-1") // no trailing \r\n
    val in = new java.io.ByteArrayInputStream(payload)
    val head = HttpHelpers.readHttpHead(in)
    new String(head, "ISO-8859-1") shouldBe "GET /x HTTP/1.1\r\nHost: a\r\n"
  }

  test("readHttpHead — empty input yields empty array") {
    val in = new java.io.ByteArrayInputStream(Array.emptyByteArray)
    HttpHelpers.readHttpHead(in).length shouldBe 0
  }

  // ── parseHttpHead ──────────────────────────────────────────────

  test("parseHttpHead — GET with query and headers") {
    val raw = "GET /users/42?lang=en HTTP/1.1\r\nHost: example.com\r\nAccept: */*\r\n\r\n"
    val p   = HttpHelpers.parseHttpHead(raw.getBytes("ISO-8859-1"))
    p.method   shouldBe "GET"
    p.path     shouldBe "/users/42"
    p.rawQuery shouldBe "lang=en"
    p.headers shouldBe Map("host" -> "example.com", "accept" -> "*/*")
    p.isUpgradeWebSocket shouldBe false
  }

  test("parseHttpHead — header keys are lowercased") {
    val raw = "GET / HTTP/1.1\r\nHost: a\r\nAuthorization: Bearer x\r\n\r\n"
    val p   = HttpHelpers.parseHttpHead(raw.getBytes("ISO-8859-1"))
    p.headers.keys should contain allOf ("host", "authorization")
  }

  test("parseHttpHead — Upgrade: websocket + Connection: Upgrade detected, case-insensitive") {
    val raw = "GET /ws HTTP/1.1\r\nHost: a\r\nUpgrade: WebSocket\r\nConnection: keep-alive, Upgrade\r\n\r\n"
    val p   = HttpHelpers.parseHttpHead(raw.getBytes("ISO-8859-1"))
    p.isUpgradeWebSocket shouldBe true
  }

  test("parseHttpHead — no query → empty rawQuery") {
    val raw = "POST /submit HTTP/1.1\r\nHost: a\r\n\r\n"
    val p   = HttpHelpers.parseHttpHead(raw.getBytes("ISO-8859-1"))
    p.path     shouldBe "/submit"
    p.rawQuery shouldBe ""
  }

  test("parseHttpHead — malformed header lines (no `:`) are dropped") {
    val raw = "GET / HTTP/1.1\r\nHost: a\r\nbogus-line\r\nX-Foo: bar\r\n\r\n"
    val p   = HttpHelpers.parseHttpHead(raw.getBytes("ISO-8859-1"))
    p.headers shouldBe Map("host" -> "a", "x-foo" -> "bar")
  }
