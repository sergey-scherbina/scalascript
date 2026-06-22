package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** The Http effect, after extraction from interpreter core into the `http-plugin`'s `blockForms`
 *  (core-minimization §2d). Formerly in `StdEffectsTest`, now run with NO explicit `installPlugins` —
 *  `runHttpStub(routes) { … }` resolves via the lazy ServiceLoader path, exactly as in production.
 *  `Http.get/post/request` perform the `"Http"` effect; the handler replies with a `Response` record
 *  built via `BlockContext.makeRecord`, reading config via `BlockContext.featureLocal`. Only the stub
 *  path is exercised here (no real network). `runHttp { … }` (real I/O) + `httpClient(baseUrl)` are
 *  covered by the http-plugin's own tests / stay in core respectively. */
class HttpEffectPluginTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))   // no installPlugins — lazy ServiceLoader dispatch
    ps.flush()
    buf.toString.trim

  test("runHttpStub returns stubbed 200 response for known URL (via plugin)"):
    captured("""
      val routes = Map("http://example.com/hello" -> "world")
      runHttpStub(routes) {
        val resp = Http.get("http://example.com/hello")
        println(resp.status)
        println(resp.body)
      }
    """) shouldBe "200\nworld"

  test("runHttpStub returns 404 for unknown URL (via plugin)"):
    captured("""
      val routes = Map("http://example.com/hello" -> "world")
      runHttpStub(routes) {
        val resp = Http.get("http://example.com/missing")
        println(resp.status)
      }
    """) shouldBe "404"

  test("runHttpStub post returns stubbed response (via plugin)"):
    captured("""
      val routes = Map("http://api.test/submit" -> "ok")
      runHttpStub(routes) {
        val resp = Http.post("http://api.test/submit", "data")
        println(resp.status)
        println(resp.body)
      }
    """) shouldBe "200\nok"

  test("runHttpStub request with method returns stubbed response (via plugin)"):
    captured("""
      val routes = Map("http://api.test/item" -> "found")
      runHttpStub(routes) {
        val resp = Http.request("DELETE", "http://api.test/item", Map(), "")
        println(resp.status)
        println(resp.body)
      }
    """) shouldBe "200\nfound"
