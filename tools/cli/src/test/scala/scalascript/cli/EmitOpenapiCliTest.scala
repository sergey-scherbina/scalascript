package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.server.Routes

class EmitOpenapiCliTest extends AnyFunSuite with Matchers:

  private def writeApi(dir: os.Path): os.Path =
    val src =
      """---
        |name: emit-openapi-test
        |version: 0.1.0
        |apiClients:
        |  - name: Api
        |    endpoints:
        |      - name: getUser
        |        method: GET
        |        path: /users/:id
        |        requestType: Unit
        |        responseType: User
        |---
        |
        |# API
        |
        |[route, serve, Response, Request](std/http.ssc)
        |[openapi, openApiSecurity](std/openapi.ssc)
        |
        |```scalascript
        |case class User(id: String)
        |
        |openApiSecurity("bearerAuth", "bearer", "JWT")
        |
        |@openapi(
        |  summary = "Get user",
        |  description = "Returns a single user.",
        |  tags = List("users"),
        |  security = List("bearerAuth")
        |)
        |route("GET", "/users/:id") { req =>
        |  Response.json(User(req.params("id")))
        |}
        |
        |serve(0)
        |```
        |""".stripMargin
    val file = dir / "api.ssc"
    os.write(file, src)
    file

  private def captureStdout(body: => Unit): String =
    val baos = new java.io.ByteArrayOutputStream()
    val ps = new java.io.PrintStream(baos)
    try
      Console.withOut(ps)(body)
      ps.flush()
      baos.toString("UTF-8")
    finally
      Routes.clear()

  test("emit-openapi writes JSON to stdout"):
    val dir = os.temp.dir(prefix = "ssc-emit-openapi-json-")
    try
      val file = writeApi(dir)
      val out = captureStdout {
        CommandRegistry.dispatch("emit-openapi", List(file.toString))
      }
      val json = ujson.read(out)
      json("openapi").str shouldBe "3.1.0"
      json("info")("title").str shouldBe "ScalaScript API"
      json("paths")("/users/{id}")("get")("summary").str shouldBe "Get user"
      json("paths")("/users/{id}")("get")("security").arr.head.obj.contains("bearerAuth") shouldBe true
      json("components")("securitySchemes")("bearerAuth")("scheme").str shouldBe "bearer"
    finally os.remove.all(dir)

  test("emit-openapi applies title version and server overrides"):
    val dir = os.temp.dir(prefix = "ssc-emit-openapi-overrides-")
    try
      val file = writeApi(dir)
      val out = captureStdout {
        CommandRegistry.dispatch("emit-openapi", List(
          "--title", "Users API",
          "--version", "2.0.0",
          "--server", "https://api.example.test",
          file.toString
        ))
      }
      val json = ujson.read(out)
      json("info")("title").str shouldBe "Users API"
      json("info")("version").str shouldBe "2.0.0"
      json("servers").arr.head("url").str shouldBe "https://api.example.test"
    finally os.remove.all(dir)

  test("emit-openapi writes YAML to stdout"):
    val dir = os.temp.dir(prefix = "ssc-emit-openapi-yaml-")
    try
      val file = writeApi(dir)
      val out = captureStdout {
        CommandRegistry.dispatch("emit-openapi", List("--format", "yaml", file.toString))
      }
      out should include ("openapi: 3.1.0")
      out should include ("\"/users/{id}\":")
      out should include ("summary: \"Get user\"")
      out should include ("bearerAuth:")
      out should include ("scheme: bearer")
    finally os.remove.all(dir)

  test("emit-openapi infers YAML format from -o file extension"):
    val dir = os.temp.dir(prefix = "ssc-emit-openapi-output-")
    try
      val file = writeApi(dir)
      val outFile = dir / "openapi.yaml"
      val stdout = captureStdout {
        CommandRegistry.dispatch("emit-openapi", List("-o", outFile.toString, file.toString))
      }
      stdout shouldBe ""
      val yaml = os.read(outFile)
      yaml should include ("openapi: 3.1.0")
      yaml should include ("\"/users/{id}\":")
    finally os.remove.all(dir)

  // ── P4b evidence diagnostics ──────────────────────────────────────────────

  test("openApiEvidenceDiagnostics returns empty for module with declared evidence"):
    val src =
      """---
        |apiClients:
        |  Users:
        |    endpoints:
        |      - name: getUser
        |        method: GET
        |        path: /users/:id
        |        request: Unit
        |        response: User
        |---
        |
        |# Users
        |""".stripMargin
    val module = scalascript.parser.Parser.parse(src)
    val diags  = openApiEvidenceDiagnostics(module)
    assert(diags.isEmpty, s"expected no diagnostics but got: $diags")

  test("openApiEvidenceDiagnostics reports endpoints with unknown evidence"):
    val src =
      """---
        |apiClients:
        |  Orders:
        |    endpoints:
        |      - name: listOrders
        |        method: GET
        |        path: /orders
        |        request: Any
        |        response: Any
        |---
        |
        |# Orders
        |""".stripMargin
    val module = scalascript.parser.Parser.parse(src)
    val diags  = openApiEvidenceDiagnostics(module)
    assert(diags.nonEmpty, "expected at least one diagnostic for Any-typed endpoint")
    assert(diags.exists(_.contains("/orders")), s"expected path in diagnostic: $diags")

  test("emit-openapi --require-declared passes silently for declared-evidence module"):
    val dir = os.temp.dir(prefix = "ssc-emit-openapi-require-declared-")
    try
      val file = writeApi(dir)
      val out = captureStdout {
        CommandRegistry.dispatch("emit-openapi", List("--require-declared", file.toString))
      }
      val json = ujson.read(out)
      json("openapi").str shouldBe "3.1.0"
    finally os.remove.all(dir)
