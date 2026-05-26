package scalascript

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.BackendRequest
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.server.{InProcessBackendTransport, Routes}

import java.nio.charset.StandardCharsets
import scala.concurrent.Await
import scala.concurrent.duration.*

class InProcessBackendTransportTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  override def beforeEach(): Unit =
    Routes.clear()

  override def afterEach(): Unit =
    Routes.clear()

  private def runRoutes(code: String): Unit =
    val src =
      s"""
         |# Test
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    Interpreter().run(Parser.parse(src))

  private def request(req: BackendRequest) =
    Await.result(InProcessBackendTransport().request(req), 2.seconds)

  private def body(resp: scalascript.backend.spi.BackendResponse): String =
    String(resp.body, StandardCharsets.UTF_8)

  test("dispatches registered route without opening HTTP socket"):
    runRoutes("""route("GET", "/ping") { _ => "pong" }""")

    val resp = request(BackendRequest("GET", "/ping"))

    resp.status shouldBe 200
    body(resp) shouldBe "pong"

  test("passes params query headers and body through interpreter route path"):
    runRoutes(
      """
        |route("POST", "/items/:id") { req =>
        |  Response.text(
        |    s"${req.params("id")}|${req.query("q")}|${req.headers("x-test")}|${req.body}",
        |    status = 202
        |  )
        |}
        |""".stripMargin)

    val resp = request(BackendRequest(
      method  = "POST",
      path    = "/items/42?q=abc",
      headers = Map("X-Test" -> "ok"),
      body    = "payload".getBytes(StandardCharsets.UTF_8)
    ))

    resp.status shouldBe 202
    body(resp) shouldBe "42|abc|ok|payload"

  test("returns 404 response for missing route"):
    val resp = request(BackendRequest("GET", "/missing"))

    resp.status shouldBe 404
    body(resp) should include ("/missing")

  test("Response.text sets text/plain content-type and body"):
    runRoutes("""route("GET", "/txt") { _ => Response.text("hello") }""")

    val resp = request(BackendRequest("GET", "/txt"))

    resp.status shouldBe 200
    resp.headers.getOrElse("Content-Type", "").should(include("text/plain"))
    body(resp) shouldBe "hello"

  test("Response.json sets application/json content-type and body"):
    runRoutes("""route("GET", "/data") { _ => Response.json("[1,2,3]") }""")

    val resp = request(BackendRequest("GET", "/data"))

    resp.status shouldBe 200
    resp.headers.getOrElse("Content-Type", "").should(include("application/json"))
    body(resp) shouldBe "[1,2,3]"

  test("Response with explicit status 204 returns empty body and correct status"):
    runRoutes("""route("DELETE", "/item") { _ => Response(204, Map(), "") }""")

    val resp = request(BackendRequest("DELETE", "/item"))

    resp.status shouldBe 204
    body(resp) shouldBe ""

  test("non-2xx status code passes through without modification"):
    runRoutes("""route("GET", "/err") { _ => Response(400, Map(), "bad request") }""")

    val resp = request(BackendRequest("GET", "/err"))

    resp.status shouldBe 400
    body(resp) shouldBe "bad request"

  test("500 from handler exception returns 500 response"):
    runRoutes("""route("GET", "/boom") { _ => throw new RuntimeException("kaboom") }""")

    val resp = request(BackendRequest("GET", "/boom"))

    resp.status shouldBe 500
