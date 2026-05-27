package scalascript.interpreter

import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.{RouteRegistry, Routes}

class OpenApiRuntimeTest extends AnyFunSuite:

  // ── test-local registry ───────────────────────────────────────────────────

  private class TestRegistry extends RouteRegistry:
    val entries = scala.collection.mutable.ArrayBuffer.empty[Routes.Entry]
    override def register(method: String, path: String, handler: Value,
                          interp: Interpreter, source: Option[String],
                          mountCtx: Map[String, Value], style: String): Unit =
      entries += Routes.Entry(method.toUpperCase, path, parsePath(path),
        handler, interp)
    private def parsePath(path: String): List[Routes.Segment] =
      path.split('/').toList.filter(_.nonEmpty).map { s =>
        if s.startsWith(":") then Routes.Segment.Capture(s.tail)
        else Routes.Segment.Literal(s)
      }
    override def remove(method: String, path: String): Boolean = false
    override def removeBySource(absPath: String): Unit = ()
    override def addMiddleware(fn: Value, interp: Interpreter): Unit = ()
    override def middlewares: List[(Value, Interpreter)] = Nil
    override def all: List[Routes.Entry] = entries.toList
    override def matchRequest(method: String, path: String) = None
    override def clear(): Unit = entries.clear()

  private def registryOf(es: Routes.Entry*): RouteRegistry =
    val r = new TestRegistry()
    r.entries ++= es
    r

  // ── entry builders ────────────────────────────────────────────────────────

  private def funEntry(
    method: String, path: String,
    params: List[String], paramTypes: List[String]
  ): Routes.Entry =
    Routes.Entry(
      method.toUpperCase, path, Nil,
      Value.FunV(params = params, body = scala.meta.Lit.Unit(),
        closure = Map.empty, paramTypes = paramTypes),
      null.asInstanceOf[Interpreter]
    )

  private def nativeEntry(method: String, path: String): Routes.Entry =
    Routes.Entry(method.toUpperCase, path, Nil,
      Value.NativeFnV("_test", _ => Computation.Pure(Value.UnitV)),
      null.asInstanceOf[Interpreter])

  private def gen(entries: Routes.Entry*): String =
    OpenApiRuntime.generateOpenApiJson(registryOf(entries*))

  // ── tests ─────────────────────────────────────────────────────────────────

  test("empty registry produces valid OpenAPI 3.1 skeleton with empty paths"):
    val json = gen()
    assert(json.contains("\"openapi\": \"3.1.0\""))
    assert(json.contains("\"paths\": {}"))

  test("GET route with native handler has no parameters section"):
    val json = gen(nativeEntry("GET", "/hello"))
    assert(json.contains("\"/hello\""))
    assert(!json.contains("\"parameters\""))

  test("path segment :param converts to {param} in OpenAPI path"):
    val json = gen(nativeEntry("GET", "/users/:id"))
    assert(json.contains("\"/users/{id}\""))
    assert(!json.contains(":id"))

  test("multiple :param segments each convert to {param}"):
    val json = gen(nativeEntry("GET", "/users/:uid/posts/:pid"))
    assert(json.contains("\"/users/{uid}/posts/{pid}\""))

  test("GET FunV typed params not in path become query parameters"):
    val json = gen(funEntry("GET", "/search", List("q", "limit"), List("String", "Int")))
    assert(json.contains("\"in\": \"query\""))
    assert(json.contains("\"q\""))
    assert(json.contains("\"limit\""))

  test("GET path param is in path parameters, other in query"):
    val json = gen(funEntry("GET", "/users/:id", List("id", "verbose"), List("String", "Boolean")))
    assert(json.contains("\"in\": \"path\""))
    assert(json.contains("\"in\": \"query\""))

  test("POST FunV typed params go to requestBody not query parameters"):
    val json = gen(funEntry("POST", "/items", List("name", "price"), List("String", "Double")))
    assert(json.contains("\"requestBody\""))
    assert(!json.contains("\"in\": \"query\""))

  test("PUT treated as body method like POST"):
    val json = gen(funEntry("PUT", "/items/:id", List("id", "name"), List("String", "String")))
    assert(json.contains("\"requestBody\""))

  test("PATCH treated as body method"):
    val json = gen(funEntry("PATCH", "/items/:id", List("id", "delta"), List("String", "String")))
    assert(json.contains("\"requestBody\""))

  test("internal /_health route excluded from generated spec"):
    val json = gen(nativeEntry("GET", "/_health"), nativeEntry("GET", "/api/users"))
    assert(!json.contains("\"/_health\""))
    assert(json.contains("\"/api/users\""))

  test("internal /_swagger and /_openapi.json excluded"):
    val json = gen(
      nativeEntry("GET", "/_swagger"),
      nativeEntry("GET", "/_openapi.json"),
      nativeEntry("GET", "/real")
    )
    assert(!json.contains("/_swagger"))
    assert(!json.contains("/_openapi.json"))
    assert(json.contains("\"/real\""))

  test("type mapping: String -> string"):
    val json = gen(funEntry("GET", "/x", List("s"), List("String")))
    assert(json.contains("\"type\":\"string\""))

  test("type mapping: Int -> integer"):
    val json = gen(funEntry("GET", "/x", List("n"), List("Int")))
    assert(json.contains("\"type\":\"integer\""))

  test("type mapping: Long -> integer"):
    val json = gen(funEntry("GET", "/x", List("n"), List("Long")))
    assert(json.contains("\"type\":\"integer\""))

  test("type mapping: Double -> number"):
    val json = gen(funEntry("GET", "/x", List("n"), List("Double")))
    assert(json.contains("\"type\":\"number\""))

  test("type mapping: Float -> number"):
    val json = gen(funEntry("GET", "/x", List("n"), List("Float")))
    assert(json.contains("\"type\":\"number\""))

  test("type mapping: Boolean -> boolean"):
    val json = gen(funEntry("GET", "/x", List("flag"), List("Boolean")))
    assert(json.contains("\"type\":\"boolean\""))

  test("unknown type maps to object"):
    val json = gen(funEntry("GET", "/x", List("p"), List("MyCustomType")))
    assert(json.contains("\"type\":\"object\""))

  test("Request param type excluded from parameters"):
    val json = gen(funEntry("GET", "/x", List("req"), List("Request")))
    assert(!json.contains("\"parameters\""))

  test("Map param type excluded from parameters"):
    val json = gen(funEntry("GET", "/x", List("ctx"), List("Map[String,Any]")))
    assert(!json.contains("\"parameters\""))

  test("routes on same path grouped into one path object"):
    val json = gen(nativeEntry("GET", "/items"), nativeEntry("POST", "/items"))
    val first = json.indexOf("\"/items\"")
    val last  = json.lastIndexOf("\"/items\"")
    assert(first == last, "'/items' should appear exactly once as a path key")
    assert(json.contains("\"get\""))
    assert(json.contains("\"post\""))

  test("NativeFnV handler on POST produces no requestBody"):
    val json = gen(nativeEntry("POST", "/hook"))
    assert(!json.contains("\"requestBody\""))

  test("summary field encodes method and path"):
    val json = gen(nativeEntry("GET", "/hello"))
    assert(json.contains("GET /hello"))

  test("swagger UI HTML references /_openapi.json and swagger-ui"):
    val html = OpenApiRuntime.swaggerUiHtml()
    assert(html.contains("/_openapi.json"))
    assert(html.contains("swagger-ui"))
    assert(html.contains("<!DOCTYPE html"))

  test("registerOpenApiDefaults adds /_openapi.json and /_swagger routes"):
    Routes.clear()
    try
      val interp = new Interpreter(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
      OpenApiRuntime.registerOpenApiDefaults(interp)
      val paths = Routes.all.map(_.path)
      assert(paths.contains("/_openapi.json"))
      assert(paths.contains("/_swagger"))
    finally
      Routes.clear()

  test("registerOpenApiDefaults is idempotent: second call does not duplicate routes"):
    Routes.clear()
    try
      val interp = new Interpreter(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
      OpenApiRuntime.registerOpenApiDefaults(interp)
      OpenApiRuntime.registerOpenApiDefaults(interp)
      assert(Routes.all.count(_.path == "/_openapi.json") == 1)
      assert(Routes.all.count(_.path == "/_swagger") == 1)
    finally
      Routes.clear()
