package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** Integration tests for v1.5 Tier 2 — httpGet / httpPost / httpClient.
 *
 *  Starts a plain Java HttpServer (bypassing ScalaScript's serve()) so the
 *  tests are decoupled from server lifecycle changes.  All tests share one
 *  server instance started in beforeAll / stopped in afterAll. */
@org.scalatest.Ignore
class HttpClientTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val port   = 19601
  private var server: HttpServer = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    server = HttpServer.create(new InetSocketAddress(port), 0)

    server.createContext("/ping", exchange =>
      val body = "pong"
      exchange.sendResponseHeaders(200, body.length)
      val os = exchange.getResponseBody; os.write(body.getBytes); os.close()
    )
    server.createContext("/echo", exchange =>
      val body = new String(exchange.getRequestBody.readAllBytes())
      exchange.sendResponseHeaders(200, body.length)
      val os = exchange.getResponseBody; os.write(body.getBytes); os.close()
    )
    server.createContext("/echo-header", exchange =>
      val token = Option(exchange.getRequestHeaders.getFirst("x-token")).getOrElse("none")
      exchange.sendResponseHeaders(200, token.length)
      val os = exchange.getResponseBody; os.write(token.getBytes); os.close()
    )
    server.createContext("/items", exchange =>
      val body = "item-list"
      exchange.sendResponseHeaders(200, body.length)
      val os = exchange.getResponseBody; os.write(body.getBytes); os.close()
    )
    server.createContext("/hi", exchange =>
      val body = "created"
      exchange.getResponseHeaders.add("x-foo", "bar")
      exchange.sendResponseHeaders(201, body.length)
      val os = exchange.getResponseBody; os.write(body.getBytes); os.close()
    )
    server.setExecutor(null)
    server.start()

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  // ── httpGet ────────────────────────────────────────────────────────

  test("httpGet returns status 200 and body"):
    val out = run(s"""
      val r = httpGet("http://localhost:$port/ping")
      println(r.status)
      println(r.body)
    """)
    out shouldBe "200\npong"

  test("httpGet 404 for unregistered path"):
    val out = run(s"""
      val r = httpGet("http://localhost:$port/missing")
      println(r.status)
    """)
    out shouldBe "404"

  test("httpGet passes custom request headers"):
    val out = run(s"""
      val r = httpGet("http://localhost:$port/echo-header", Map("x-token" -> "abc"))
      println(r.body)
    """)
    out shouldBe "abc"

  // ── httpPost ───────────────────────────────────────────────────────

  test("httpPost sends body and receives it echoed"):
    val out = run(s"""
      val r = httpPost("http://localhost:$port/echo", "hello world")
      println(r.status)
      println(r.body)
    """)
    out shouldBe "200\nhello world"

  // ── httpClient ─────────────────────────────────────────────────────

  test("httpClient sets base URL for relative httpGet calls"):
    val out = run(s"""
      httpClient("http://localhost:$port") {
        val r = httpGet("/items")
        println(r.body)
      }
    """)
    out shouldBe "item-list"

  test("httpClient absolute URL ignores base"):
    val out = run(s"""
      httpClient("http://example.invalid") {
        val r = httpGet("http://localhost:$port/items")
        println(r.body)
      }
    """)
    out shouldBe "item-list"

  // ── Response fields ────────────────────────────────────────────────

  test("Response has body and status fields"):
    val out = run(s"""
      val r = httpGet("http://localhost:$port/hi")
      println(r.status)
      println(r.body)
    """)
    out shouldBe "201\ncreated"
