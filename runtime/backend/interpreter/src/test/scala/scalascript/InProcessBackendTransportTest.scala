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
