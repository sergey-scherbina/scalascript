package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.{NativeImpl, NativeContext}
import scalascript.interpreter.{Computation, Value}
import scalascript.ir.QualifiedName

/** Phase 13 — Server-Sent Events subscription delivery.
 *
 *  Covers: SSE content-type detection, body format (data:/double-newline),
 *  multi-event delivery, event ordering, non-subscription fallthrough, variable
 *  passthrough, blank-query error, Cache-Control header, and graphqlSse registration.
 */
class GraphQLSseTest extends AnyFunSuite:

  private val SDL_WITH_SUB =
    "type Query { _dummy: String } type Subscription { events: String! }"

  private val SDL_WITH_ARG_SUB =
    "type Query { _dummy: String } type Subscription { events(count: Int!): String! }"

  private val dummyFn: Value.NativeFnV = Value.NativeFnV("dummy", Computation.pureFn(_ => Value.NullV))
  private def nullStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  private def buildCtx(): NativeContext = new NativeContext:
    def out = nullStream
    def err = out
    override def invokeCallback(fn: Any, args: List[Any]): Any = fn match
      case f: Value.NativeFnV => Computation.run(f.f(args.collect { case v: Value => v }))
      case _                   => Value.NullV

  private def buildSseHandler(
    sdl:     String,
    subFn:   Value.NativeFnV = dummyFn,
    queryFn: Value.NativeFnV = dummyFn,
  ): Value.NativeFnV =
    val p   = new GraphQLInterpreterPlugin()
    val ctx = buildCtx()
    val res = GraphQLResolvers(
      query        = Map("_dummy" -> queryFn),
      mutation     = Map.empty,
      subscription = Map("events" -> subFn),
    )
    p.intrinsics(QualifiedName("graphqlHandler")).asInstanceOf[NativeImpl]
      .eval(ctx, List(Value.Foreign("GraphQLSchema", sdl), Value.Foreign("GraphQLResolvers", res)))
      .asInstanceOf[Value.NativeFnV]

  private def sseRequest(query: String): Value =
    Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(ujson.write(ujson.Obj("query" -> ujson.Str(query)))),
      "headers" -> Value.MapV(Map(
        Value.StringV("Accept")       -> Value.StringV("text/event-stream"),
        Value.StringV("Content-Type") -> Value.StringV("application/json"),
      )),
    ))

  private def sseRequestWithBody(body: String): Value =
    Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(body),
      "headers" -> Value.MapV(Map(
        Value.StringV("Accept")       -> Value.StringV("text/event-stream"),
        Value.StringV("Content-Type") -> Value.StringV("application/json"),
      )),
    ))

  private def invoke(handler: Value.NativeFnV, req: Value): Value.InstanceV =
    Computation.run(handler.f(List(req))).asInstanceOf[Value.InstanceV]

  private def responseBody(resp: Value.InstanceV): String =
    resp.fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("")

  private def responseHeader(resp: Value.InstanceV, name: String): Option[String] =
    resp.fields.get("headers").collect {
      case Value.MapV(m) =>
        m.collectFirst { case (Value.StringV(k), Value.StringV(v)) if k.equalsIgnoreCase(name) => v }
    }.flatten

  // ── Content-Type and Cache-Control ────────────────────────────────────────

  test("SSE subscription returns text/event-stream content type"):
    val subFn: Value.NativeFnV = Value.NativeFnV("events", Computation.pureFn {
      case List(Value.MapV(_)) => Value.ListV(List(Value.StringV("hello")))
      case _                   => Value.ListV(Nil)
    })
    val handler = buildSseHandler(SDL_WITH_SUB, subFn)
    val resp    = invoke(handler, sseRequest("subscription { events }"))
    assert(responseHeader(resp, "Content-Type").exists(_.contains("text/event-stream")),
      s"expected text/event-stream, got: ${responseHeader(resp, "Content-Type")}")

  test("SSE response has Cache-Control: no-cache header"):
    val subFn: Value.NativeFnV = Value.NativeFnV("events", Computation.pureFn {
      case List(Value.MapV(_)) => Value.ListV(List(Value.StringV("x")))
      case _                   => Value.ListV(Nil)
    })
    val handler = buildSseHandler(SDL_WITH_SUB, subFn)
    val resp    = invoke(handler, sseRequest("subscription { events }"))
    assert(responseHeader(resp, "Cache-Control").contains("no-cache"),
      s"expected no-cache, got: ${responseHeader(resp, "Cache-Control")}")

  // ── SSE body format ───────────────────────────────────────────────────────

  test("SSE body starts with data: prefix"):
    val subFn: Value.NativeFnV = Value.NativeFnV("events", Computation.pureFn {
      case List(Value.MapV(_)) => Value.ListV(List(Value.StringV("first")))
      case _                   => Value.ListV(Nil)
    })
    val handler = buildSseHandler(SDL_WITH_SUB, subFn)
    val body    = responseBody(invoke(handler, sseRequest("subscription { events }")))
    assert(body.startsWith("data: "), s"body should start with 'data: ', got: $body")

  test("SSE event blocks end with double newline"):
    val subFn: Value.NativeFnV = Value.NativeFnV("events", Computation.pureFn {
      case List(Value.MapV(_)) => Value.ListV(List(Value.StringV("e1")))
      case _                   => Value.ListV(Nil)
    })
    val handler = buildSseHandler(SDL_WITH_SUB, subFn)
    val body    = responseBody(invoke(handler, sseRequest("subscription { events }")))
    assert(body.endsWith("\n\n"),
      s"body should end with double newline, got: ${body.replace("\n", "\\n")}")

  test("SSE data line contains valid JSON with data key"):
    val subFn: Value.NativeFnV = Value.NativeFnV("events", Computation.pureFn {
      case List(Value.MapV(_)) => Value.ListV(List(Value.StringV("hello")))
      case _                   => Value.ListV(Nil)
    })
    val handler  = buildSseHandler(SDL_WITH_SUB, subFn)
    val body     = responseBody(invoke(handler, sseRequest("subscription { events }")))
    val dataLine = body.split("\n\n").head.stripPrefix("data: ")
    val parsed   = ujson.read(dataLine)
    assert(parsed.obj.contains("data"), s"expected JSON with 'data' key, got: $dataLine")

  // ── Multi-event delivery ──────────────────────────────────────────────────

  test("SSE delivers multiple events as separate data: blocks"):
    val items  = List("alpha", "beta", "gamma")
    val subFn: Value.NativeFnV  = Value.NativeFnV("events", Computation.pureFn {
      case List(Value.MapV(_)) => Value.ListV(items.map(Value.StringV(_)))
      case _                   => Value.ListV(Nil)
    })
    val handler = buildSseHandler(SDL_WITH_SUB, subFn)
    val body    = responseBody(invoke(handler, sseRequest("subscription { events }")))
    val blocks  = body.split("\n\n").filter(_.nonEmpty).toList
    assert(blocks.size == items.size,
      s"expected ${items.size} event blocks, got ${blocks.size}: $body")

  test("SSE events are delivered in order"):
    val items = List("first", "second", "third")
    val subFn: Value.NativeFnV = Value.NativeFnV("events", Computation.pureFn {
      case List(Value.MapV(_)) => Value.ListV(items.map(Value.StringV(_)))
      case _                   => Value.ListV(Nil)
    })
    val handler   = buildSseHandler(SDL_WITH_SUB, subFn)
    val body      = responseBody(invoke(handler, sseRequest("subscription { events }")))
    val received  = body.split("\n\n").filter(_.nonEmpty).toList
      .map { block => ujson.read(block.stripPrefix("data: "))("data")("events").str }
    assert(received == items, s"expected $items, got $received")

  // ── Non-subscription fallthrough ──────────────────────────────────────────

  test("SSE request for non-subscription query returns regular JSON response"):
    val queryFn: Value.NativeFnV = Value.NativeFnV("_dummy", Computation.pureFn(_ => Value.StringV("pong")))
    val handler  = buildSseHandler(SDL_WITH_SUB, dummyFn, queryFn)
    val resp     = invoke(handler, sseRequest("query { _dummy }"))
    val ct       = responseHeader(resp, "Content-Type").getOrElse("")
    assert(!ct.contains("text/event-stream"),
      s"non-subscription via SSE should not return text/event-stream, got: $ct")
    assert(responseBody(resp).contains("data"), "should return regular JSON with data key")

  // ── Blank query ───────────────────────────────────────────────────────────

  test("SSE request with blank query returns error"):
    val handler = buildSseHandler(SDL_WITH_SUB)
    val resp    = invoke(handler, sseRequestWithBody("""{"query":"   "}"""))
    val body    = responseBody(resp)
    assert(body.contains("error") || body.contains("Missing"),
      s"expected error for blank query, got: $body")

  // ── Variable passthrough ──────────────────────────────────────────────────

  test("SSE subscription with variables passes variables to resolver"):
    var receivedCount = -1
    val subFn: Value.NativeFnV = Value.NativeFnV("events", Computation.pureFn {
      case List(Value.MapV(args)) =>
        receivedCount = args.get(Value.StringV("count")).collect { case Value.IntV(n) => n.toInt }.getOrElse(-1)
        Value.ListV((1 to receivedCount).map(i => Value.StringV(s"e$i")).toList)
      case _ => Value.ListV(Nil)
    })
    val p   = new GraphQLInterpreterPlugin()
    val ctx = buildCtx()
    val res = GraphQLResolvers(
      query        = Map("_dummy" -> dummyFn),
      mutation     = Map.empty,
      subscription = Map("events" -> subFn),
    )
    val handler = p.intrinsics(QualifiedName("graphqlHandler")).asInstanceOf[NativeImpl]
      .eval(ctx, List(Value.Foreign("GraphQLSchema", SDL_WITH_ARG_SUB), Value.Foreign("GraphQLResolvers", res)))
      .asInstanceOf[Value.NativeFnV]
    val body = ujson.write(ujson.Obj(
      "query"     -> ujson.Str("subscription($count: Int!) { events(count: $count) }"),
      "variables" -> ujson.Obj("count" -> ujson.Num(3)),
    ))
    val resp = invoke(handler, sseRequestWithBody(body))
    assert(responseHeader(resp, "Content-Type").exists(_.contains("text/event-stream")),
      "expected SSE response")
    assert(receivedCount == 3, s"expected resolver to receive count=3, got $receivedCount")
    val blocks = responseBody(resp).split("\n\n").filter(_.nonEmpty)
    assert(blocks.length == 3, s"expected 3 event blocks, got ${blocks.length}")

  // ── Intrinsic registration ────────────────────────────────────────────────

  test("graphqlSse intrinsic is registered in the table"):
    val p = new GraphQLInterpreterPlugin()
    assert(p.intrinsics.contains(QualifiedName("graphqlSse")),
      "graphqlSse should be in the intrinsics table")
