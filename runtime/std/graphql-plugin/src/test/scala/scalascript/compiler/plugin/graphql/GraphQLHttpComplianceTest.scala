package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Computation, Value}
import scalascript.ir.QualifiedName
import ujson.*

/** GraphQL-over-HTTP compliance tests (Phase 5).
 *
 *  Spec references: https://graphql.github.io/graphql-over-http/
 *  §6.2.2 — GET must reject mutations with 405.
 *  §7.1   — Media-type negotiation for application/graphql-response+json.
 *  §7.2   — Request parameters: query, operationName, variables, extensions.
 *  §8     — Response status codes under each content type.
 */
class GraphQLHttpComplianceTest extends AnyFunSuite:

  private val simpleSdl = "type Query { hello: String! greeting(name: String!): String! }"
  private val mutSdl    = "type Query { _dummy: String } type Mutation { ping: String! }"

  private def helloResolvers = GraphQLResolvers(
    query = Map(
      "hello"    -> Value.NativeFnV("hello", Computation.pureFn(_ => Value.StringV("world"))),
      "greeting" -> Value.NativeFnV("greeting", Computation.pureFn {
        case List(Value.MapV(args)) =>
          val name = args.get(Value.StringV("name")).collect { case Value.StringV(s) => s }.getOrElse("?")
          Value.StringV(s"Hello, $name!")
        case _ => Value.StringV("Hello!")
      }),
    ),
    mutation = Map.empty,
  )

  private def makeHandler(sdl: String, resolvers: GraphQLResolvers): Value.NativeFnV =
    val p = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl(sdl))

    val ctx = new scalascript.backend.spi.NativeContext:
      def out = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
      def err = out
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        fn match
          case f: Value.NativeFnV => Computation.run(f.f(args.collect { case v: Value => v }))
          case _                  => Value.NullV

    val impl = p.intrinsics(QualifiedName("graphqlHandler")).asInstanceOf[scalascript.backend.spi.NativeImpl]
    impl.eval(ctx, List(Value.Foreign("GraphQLSchema", sdl), Value.Foreign("GraphQLResolvers", resolvers)))
      .asInstanceOf[Value.NativeFnV]

  private def invoke(handler: Value.NativeFnV, req: Value): Value =
    Computation.run(handler.f(List(req)))

  private def postReq(query: String, accept: String = "application/json", extraHeaders: Map[String, String] = Map.empty): Value =
    val hdrs = (extraHeaders + ("Accept" -> accept)).map { (k, v) =>
      (Value.StringV(k): Value) -> (Value.StringV(v): Value)
    }
    Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(s"""{"query":"${query.replace("\"", "\\\"")}"}"""),
      "headers" -> Value.MapV(hdrs),
    ))

  private def postReqWithBody(body: String, accept: String = "application/json"): Value =
    Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(body),
      "headers" -> Value.MapV(Map(Value.StringV("Accept") -> Value.StringV(accept))),
    ))

  private def getReq(pathWithQs: String, accept: String = "application/json"): Value =
    Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("GET"),
      "path"    -> Value.StringV(pathWithQs),
      "body"    -> Value.StringV(""),
      "headers" -> Value.MapV(Map(Value.StringV("Accept") -> Value.StringV(accept))),
    ))

  private def responseFields(resp: Value): Map[String, Value] = resp match
    case Value.InstanceV("Response", f) => f
    case other                          => fail(s"expected Response InstanceV, got $other")

  private def status(resp: Value): Long =
    responseFields(resp).get("status").collect { case Value.IntV(n) => n }.getOrElse(-1L)

  private def contentType(resp: Value): String =
    responseFields(resp).get("headers").collect {
      case Value.MapV(h) => h.collectFirst {
        case (Value.StringV(k), Value.StringV(v)) if k.equalsIgnoreCase("Content-Type") => v
      }.getOrElse("")
    }.getOrElse("")

  private def body(resp: Value): ujson.Value =
    val raw = responseFields(resp).get("body").collect { case Value.StringV(s) => s }.getOrElse("{}")
    ujson.read(raw)

  // ── §7.1 Media-type negotiation ───────────────────────────────────────────

  test("POST with Accept: application/json returns Content-Type: application/json"):
    val h    = makeHandler(simpleSdl, helloResolvers)
    val resp = invoke(h, postReq("{ hello }", accept = "application/json"))
    assert(contentType(resp) == "application/json")
    assert(status(resp) == 200L)

  test("POST with Accept: application/graphql-response+json returns that content type"):
    val h    = makeHandler(simpleSdl, helloResolvers)
    val resp = invoke(h, postReq("{ hello }", accept = "application/graphql-response+json"))
    assert(contentType(resp) == "application/graphql-response+json")
    assert(status(resp) == 200L)

  test("POST without Accept defaults to application/json"):
    val h    = makeHandler(simpleSdl, helloResolvers)
    val req  = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV("""{"query":"{ hello }"}"""),
      "headers" -> Value.MapV(Map.empty),
    ))
    val resp = invoke(h, req)
    assert(contentType(resp) == "application/json")

  // ── §7.2 — operationName ──────────────────────────────────────────────────

  test("POST with operationName selects named operation"):
    val h   = makeHandler(simpleSdl, helloResolvers)
    val req = postReqWithBody(
      """{"query":"query A { hello } query B { greeting(name: \"X\") }","operationName":"A"}"""
    )
    val resp = invoke(h, req)
    assert(status(resp) == 200L)
    assert(body(resp)("data")("hello").str == "world")

  test("GET with operationName in query string selects named operation"):
    val h   = makeHandler(simpleSdl, helloResolvers)
    val enc = java.net.URLEncoder.encode("query A { hello } query B { hello }", "UTF-8")
    val req = getReq(s"/graphql?query=$enc&operationName=A")
    val resp = invoke(h, req)
    assert(status(resp) == 200L)
    assert(body(resp)("data")("hello").str == "world")

  // ── §7.2 — variables ─────────────────────────────────────────────────────

  test("POST passes variables to resolver"):
    val h   = makeHandler(simpleSdl, helloResolvers)
    val req = postReqWithBody(
      """{"query":"query G($n: String!) { greeting(name: $n) }","variables":{"n":"Alice"}}"""
    )
    val resp = invoke(h, req)
    assert(status(resp) == 200L)
    assert(body(resp)("data")("greeting").str == "Hello, Alice!")

  test("GET with JSON-encoded variables in query string"):
    val h      = makeHandler(simpleSdl, helloResolvers)
    val qEnc   = java.net.URLEncoder.encode("query G($n: String!) { greeting(name: $n) }", "UTF-8")
    val vEnc   = java.net.URLEncoder.encode("""{"n":"Bob"}""", "UTF-8")
    val req    = getReq(s"/graphql?query=$qEnc&variables=$vEnc")
    val resp   = invoke(h, req)
    assert(status(resp) == 200L)
    assert(body(resp)("data")("greeting").str == "Hello, Bob!")

  // ── §8 — status codes under application/graphql-response+json ────────────

  test("missing query returns 400 under application/graphql-response+json"):
    val h   = makeHandler(simpleSdl, helloResolvers)
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV("{}"),
      "headers" -> Value.MapV(Map(
        Value.StringV("Accept") -> Value.StringV("application/graphql-response+json")
      )),
    ))
    val resp = invoke(h, req)
    assert(status(resp) == 400L)
    assert(contentType(resp) == "application/graphql-response+json")

  test("missing query returns 200 with error under application/json"):
    val h   = makeHandler(simpleSdl, helloResolvers)
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV("{}"),
      "headers" -> Value.MapV(Map(
        Value.StringV("Accept") -> Value.StringV("application/json")
      )),
    ))
    val resp = invoke(h, req)
    assert(status(resp) == 200L)

  // ── errors field omission ─────────────────────────────────────────────────

  test("successful response omits errors key"):
    val h    = makeHandler(simpleSdl, helloResolvers)
    val resp = invoke(h, postReq("{ hello }"))
    val b    = body(resp)
    assert(!b.obj.contains("errors"), s"errors present in success response: $b")

  test("successful response under graphql-response+json omits errors key"):
    val h    = makeHandler(simpleSdl, helloResolvers)
    val resp = invoke(h, postReq("{ hello }", accept = "application/graphql-response+json"))
    val b    = body(resp)
    assert(!b.obj.contains("errors"), s"errors present in success response: $b")

  // ── §6.2.2 GET mutation rejection with media-type ─────────────────────────

  test("GET mutation returns 405 with application/graphql-response+json content type when requested"):
    val h   = makeHandler(mutSdl, GraphQLResolvers(Map.empty, Map.empty))
    val enc = java.net.URLEncoder.encode("mutation { ping }", "UTF-8")
    val req = getReq(s"/graphql?query=$enc", accept = "application/graphql-response+json")
    val resp = invoke(h, req)
    assert(status(resp) == 405L)
    assert(contentType(resp) == "application/graphql-response+json")
