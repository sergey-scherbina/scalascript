package scalascript.interpreter

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import scalascript.server.{Routes, RouteRegistry}
import scala.meta.Term as MetaTerm

/** Tests for `OpenApiRuntime` — `/_openapi.json` and `/_swagger` built-in routes. */
class OpenApiRuntimeTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  private var interp: Interpreter = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    Routes.clear()
    interp = Interpreter()

  override def afterEach(): Unit =
    Routes.clear()

  private def reg: RouteRegistry = interp.routeRegistry

  private def noop: Value = Value.NativeFnV("noop", Computation.pureFn(_ => Value.UnitV))

  private def funHandler(params: List[String], paramTypes: List[String]): Value =
    Value.FunV(
      params     = params,
      body       = MetaTerm.Block(Nil),
      closure    = Map.empty,
      paramTypes = paramTypes
    )

  private def parseJson(s: String): ujson.Value = ujson.read(s)

  // ── generateOpenApiJson — unit tests ──────────────────────────────────

  test("empty registry produces valid OpenAPI 3.1 document with no paths"):
    val json = parseJson(OpenApiRuntime.generateOpenApiJson(reg))
    json("openapi").str shouldBe "3.1.0"
    json("paths").obj should be(empty)

  test("single GET route appears under its path"):
    reg.register("GET", "/hello", noop, interp)
    val json = parseJson(OpenApiRuntime.generateOpenApiJson(reg))
    json("paths").obj.keys.toSet shouldBe Set("/hello")
    json("paths")("/hello").obj.keys.toSet shouldBe Set("get")

  test(":param segments are converted to {param} OpenAPI notation"):
    reg.register("GET", "/users/:id/posts/:postId", noop, interp)
    val json = parseJson(OpenApiRuntime.generateOpenApiJson(reg))
    json("paths").obj.keys.toSet shouldBe Set("/users/{id}/posts/{postId}")

  test("internal routes (/_*) are excluded from the document"):
    reg.register("GET", "/_health",       noop, interp)
    reg.register("GET", "/_ready",        noop, interp)
    reg.register("GET", "/_openapi.json", noop, interp)
    reg.register("GET", "/public",        noop, interp)
    val json = parseJson(OpenApiRuntime.generateOpenApiJson(reg))
    json("paths").obj.keys.toSet shouldBe Set("/public")

  test("multiple methods for same path are grouped under one path item"):
    reg.register("GET",  "/items", noop, interp)
    reg.register("POST", "/items", noop, interp)
    val json = parseJson(OpenApiRuntime.generateOpenApiJson(reg))
    json("paths").obj.keys.toSet shouldBe Set("/items")
    json("paths")("/items").obj.keys.toSet shouldBe Set("get", "post")

  test("paths are sorted alphabetically"):
    reg.register("GET", "/z", noop, interp)
    reg.register("GET", "/a", noop, interp)
    reg.register("GET", "/m", noop, interp)
    val json = parseJson(OpenApiRuntime.generateOpenApiJson(reg))
    json("paths").obj.keys.toList shouldBe List("/a", "/m", "/z")

  test("typed GET handler: non-path typed params become query parameters"):
    val handler = funHandler(
      List("req", "name", "age"),
      List("Request", "String", "Int")
    )
    reg.register("GET", "/search", handler, interp)
    val json   = parseJson(OpenApiRuntime.generateOpenApiJson(reg))
    val params = json("paths")("/search")("get")("parameters").arr
    val names  = params.map(_("name").str).toSet
    names shouldBe Set("name", "age")
    params.find(_("name").str == "name").get("schema")("type").str shouldBe "string"
    params.find(_("name").str == "age").get("schema")("type").str  shouldBe "integer"

  test("typed POST handler: non-path params become requestBody properties"):
    val handler = funHandler(
      List("req", "title", "count"),
      List("Request", "String", "Long")
    )
    reg.register("POST", "/items", handler, interp)
    val json  = parseJson(OpenApiRuntime.generateOpenApiJson(reg))
    val op    = json("paths")("/items")("post")
    op.obj.contains("requestBody") shouldBe true
    val props = op("requestBody")("content")("application/json")("schema")("properties")
    props("title")("type").str  shouldBe "string"
    props("count")("type").str  shouldBe "integer"

  test("path params are not duplicated as query params"):
    val handler = funHandler(List("req", "id"), List("Request", "String"))
    reg.register("GET", "/users/:id", handler, interp)
    val json   = parseJson(OpenApiRuntime.generateOpenApiJson(reg))
    val op     = json("paths")("/users/{id}")("get")
    val params = op("parameters").arr
    val inPath  = params.filter(_("in").str == "path").map(_("name").str)
    val inQuery = params.filter(_("in").str == "query").map(_("name").str)
    inPath  shouldBe List("id")
    inQuery shouldBe empty

  test("type map: Double→number, Boolean→boolean, unknown→object"):
    val handler = funHandler(
      List("req", "price", "active", "meta"),
      List("Request", "Double", "Boolean", "AnyRef")
    )
    reg.register("GET", "/items", handler, interp)
    val json   = parseJson(OpenApiRuntime.generateOpenApiJson(reg))
    val params = json("paths")("/items")("get")("parameters").arr
    def schemaType(name: String) = params.find(_("name").str == name).get("schema")("type").str
    schemaType("price")  shouldBe "number"
    schemaType("active") shouldBe "boolean"
    schemaType("meta")   shouldBe "object"

  // ── registerOpenApiDefaults ────────────────────────────────────────────

  test("registerOpenApiDefaults registers GET /_openapi.json"):
    OpenApiRuntime.registerOpenApiDefaults(interp)
    Routes.matchRequest("GET", "/_openapi.json") should not be None

  test("registerOpenApiDefaults registers GET /_swagger"):
    OpenApiRuntime.registerOpenApiDefaults(interp)
    Routes.matchRequest("GET", "/_swagger") should not be None

  test("registerOpenApiDefaults is idempotent — calling twice does not duplicate routes"):
    OpenApiRuntime.registerOpenApiDefaults(interp)
    OpenApiRuntime.registerOpenApiDefaults(interp)
    Routes.all.count(e => e.method == "GET" && e.path == "/_openapi.json") shouldBe 1
    Routes.all.count(e => e.method == "GET" && e.path == "/_swagger")      shouldBe 1

  test("/_openapi.json handler returns 200 with application/json Content-Type"):
    reg.register("GET", "/ping", noop, interp)
    OpenApiRuntime.registerOpenApiDefaults(interp)
    val Some((entry, _)) = Routes.matchRequest("GET", "/_openapi.json"): @unchecked
    val result = interp.invoke(entry.handler, List(Value.UnitV))
    result match
      case Value.InstanceV("Response", fields) =>
        fields("status") shouldBe Value.IntV(200)
        val headers = fields("headers").asInstanceOf[Value.MapV].entries
        val ct = headers.collectFirst {
          case (Value.StringV("Content-Type"), Value.StringV(v)) => v
        }
        ct shouldBe Some("application/json")
      case other => fail(s"Expected Response InstanceV, got $other")

  test("/_swagger handler returns 200 with text/html Content-Type and Swagger UI markup"):
    OpenApiRuntime.registerOpenApiDefaults(interp)
    val Some((entry, _)) = Routes.matchRequest("GET", "/_swagger"): @unchecked
    val result = interp.invoke(entry.handler, List(Value.UnitV))
    result match
      case Value.InstanceV("Response", fields) =>
        fields("status") shouldBe Value.IntV(200)
        val headers = fields("headers").asInstanceOf[Value.MapV].entries
        val ct = headers.collectFirst {
          case (Value.StringV("Content-Type"), Value.StringV(v)) => v
        }
        ct.getOrElse("") should startWith("text/html")
        val body = fields("body").asInstanceOf[Value.StringV].s
        body should include("swagger-ui")
        body should include("/_openapi.json")
      case other => fail(s"Expected Response InstanceV, got $other")

  test("/_openapi.json body is valid JSON containing registered user routes"):
    reg.register("GET",  "/api/users", noop, interp)
    reg.register("POST", "/api/users", noop, interp)
    OpenApiRuntime.registerOpenApiDefaults(interp)
    val Some((entry, _)) = Routes.matchRequest("GET", "/_openapi.json"): @unchecked
    val result = interp.invoke(entry.handler, List(Value.UnitV))
    val body   = result.asInstanceOf[Value.InstanceV].fields("body").asInstanceOf[Value.StringV].s
    val json   = parseJson(body)
    json("paths").obj.keys should contain("/api/users")
    json("paths")("/api/users").obj.keys.toSet shouldBe Set("get", "post")
