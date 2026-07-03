package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Computation, Value}
import scalascript.ir.QualifiedName
import scalascript.backend.spi.NativeImpl
import ujson.*

/** Phase 10 — security, limits, and policy tests.
 *
 *  Covers: GraphQL.options intrinsic, maxDepth, maxComplexity, maxQueryLength,
 *  disableIntrospection, and integration with graphqlHandler.
 */
class GraphQLSecurityTest extends AnyFunSuite:

  private val simpleSdl = "type Query { hello: String! }"

  private def makeCtx(): scalascript.backend.spi.NativeContext =
    new scalascript.backend.spi.NativeContext:
      def out = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
      def err = out
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        fn match
          case f: Value.NativeFnV => Computation.run(f.f(args.collect { case v: Value => v }))
          case _                  => Value.NullV

  private def makeHandler(sdl: String, resolvers: GraphQLResolvers, opts: GraphQLOpts): Value.NativeFnV =
    val p   = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl(sdl))
    val ctx  = makeCtx()
    val impl = p.intrinsics(QualifiedName("graphqlHandler")).asInstanceOf[NativeImpl]
    impl.eval(ctx, List(
      Value.Foreign("GraphQLSchema", sdl),
      Value.Foreign("GraphQLResolvers", resolvers),
      Value.Foreign("GraphQLOptions", opts),
    )).asInstanceOf[Value.NativeFnV]

  private def defaultResolvers = GraphQLResolvers(
    query    = Map("hello" -> Value.NativeFnV("hello", Computation.pureFn(_ => Value.StringV("world")))),
    mutation = Map.empty,
  )

  private def invoke(handler: Value.NativeFnV, query: String): (Long, ujson.Value) =
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(s"""{"query":"${query.replace("\"", "\\\"")}"}"""),
      "headers" -> Value.MapV(Map.empty),
    ))
    val resp = Computation.run(handler.f(List(req)))
    resp match
      case Value.InstanceV("Response", fields) =>
        val status = fields.get("status").collect { case Value.IntV(n) => n }.getOrElse(-1L)
        val bodyStr = fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("{}")
        (status, ujson.read(bodyStr))
      case other => fail(s"expected Response, got $other")

  // ── GraphQL.options intrinsic ──────────────────────────────────────────────

  test("GraphQL.options returns Foreign GraphQLOptions value"):
    val p    = new GraphQLInterpreterPlugin()
    val impl = p.intrinsics(QualifiedName("GraphQL.options")).asInstanceOf[NativeImpl]
    val result = impl.eval(makeCtx(), List(Value.IntV(5L)))
    result match
      case Value.Foreign("GraphQLOptions", opts: GraphQLOpts) =>
        assert(opts.maxDepth.contains(5))
        assert(opts.maxComplexity.isEmpty)
        assert(!opts.disableIntrospection)
      case other => fail(s"expected Foreign(GraphQLOptions), got $other")

  test("GraphQL.options with all parameters"):
    val p    = new GraphQLInterpreterPlugin()
    val impl = p.intrinsics(QualifiedName("GraphQL.options")).asInstanceOf[NativeImpl]
    val result = impl.eval(makeCtx(), List(
      Value.IntV(5L),
      Value.IntV(100L),
      Value.IntV(4096L),
      Value.BoolV(true),
    ))
    result match
      case Value.Foreign("GraphQLOptions", opts: GraphQLOpts) =>
        assert(opts.maxDepth.contains(5))
        assert(opts.maxComplexity.contains(100))
        assert(opts.maxQueryLength.contains(4096))
        assert(opts.disableIntrospection)
      case other => fail(s"expected Foreign(GraphQLOptions), got $other")

  test("GraphQL.options with no parameters uses defaults"):
    val p    = new GraphQLInterpreterPlugin()
    val impl = p.intrinsics(QualifiedName("GraphQL.options")).asInstanceOf[NativeImpl]
    val result = impl.eval(makeCtx(), Nil)
    result match
      case Value.Foreign("GraphQLOptions", opts: GraphQLOpts) =>
        assert(opts == GraphQLOpts.default)
      case other => fail(s"expected Foreign(GraphQLOptions), got $other")

  // ── maxQueryLength ────────────────────────────────────────────────────────

  test("maxQueryLength rejects a body that exceeds the limit"):
    val h = makeHandler(simpleSdl, defaultResolvers, GraphQLOpts(maxQueryLength = Some(5)))
    // Build a POST request with a large body directly (bypass the invoke helper which URL-encodes)
    val bigBody = """{"query":"{ hello hello hello hello hello hello }"}"""
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(bigBody),
      "headers" -> Value.MapV(Map.empty),
    ))
    val resp = Computation.run(h.f(List(req)))
    resp match
      case Value.InstanceV("Response", fields) =>
        val status = fields.get("status").collect { case Value.IntV(n) => n }.getOrElse(-1L)
        assert(status == 200L)
        val body = ujson.read(fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("{}"))
        assert(body.obj.contains("errors"), s"expected errors key: $body")
      case other => fail(s"expected Response, got $other")

  test("maxQueryLength allows a body within the limit"):
    val h = makeHandler(simpleSdl, defaultResolvers, GraphQLOpts(maxQueryLength = Some(1000)))
    val (status, body) = invoke(h, "{ hello }")
    assert(status == 200L)
    assert(body("data")("hello").str == "world")

  // ── disableIntrospection ─────────────────────────────────────────────────

  test("disableIntrospection rejects __schema query"):
    val h = makeHandler(simpleSdl, defaultResolvers, GraphQLOpts(disableIntrospection = true))
    val (status, body) = invoke(h, "{ __schema { queryType { name } } }")
    assert(status == 200L)
    assert(body.obj.contains("errors"), s"expected errors in response: $body")
    val msg = body("errors").arr.head("message").str
    assert(msg.contains("Introspection"), s"expected introspection error, got: $msg")

  test("disableIntrospection rejects __type query"):
    val h = makeHandler(simpleSdl, defaultResolvers, GraphQLOpts(disableIntrospection = true))
    val (status, body) = invoke(h, "{ __type(name: \"Query\") { name } }")
    assert(status == 200L)
    assert(body.obj.contains("errors"))

  test("disableIntrospection does not affect normal queries"):
    val h = makeHandler(simpleSdl, defaultResolvers, GraphQLOpts(disableIntrospection = true))
    val (status, body) = invoke(h, "{ hello }")
    assert(status == 200L)
    assert(body("data")("hello").str == "world")

  test("introspection allowed by default"):
    val h = makeHandler(simpleSdl, defaultResolvers, GraphQLOpts.default)
    val (status, body) = invoke(h, "{ __schema { queryType { name } } }")
    assert(status == 200L)
    assert(!body.obj.contains("errors") || body("errors").arr.isEmpty)

  // ── maxDepth instrumentation ──────────────────────────────────────────────

  test("maxDepth=1 rejects a deeply nested query"):
    val sdl =
      """|type Query { a: A! }
         |type A { b: B! }
         |type B { value: String! }""".stripMargin
    val resolvers = GraphQLResolvers(
      query = Map(
        "Query.a" -> Value.NativeFnV("a", Computation.pureFn(_ =>
          Value.MapV(Map(Value.StringV("b") -> Value.MapV(Map(
            Value.StringV("value") -> Value.StringV("deep")
          ))))
        ))
      ),
      mutation = Map.empty,
    )
    val h = makeHandler(sdl, resolvers, GraphQLOpts(maxDepth = Some(1)))
    val (_, body) = invoke(h, "{ a { b { value } } }")
    assert(body.obj.contains("errors"), s"expected depth rejection: $body")

  test("maxDepth=5 allows a shallow query"):
    val h = makeHandler(simpleSdl, defaultResolvers, GraphQLOpts(maxDepth = Some(5)))
    val (status, body) = invoke(h, "{ hello }")
    assert(status == 200L)
    assert(body("data")("hello").str == "world")

  // ── graphqlHandler without opts still works (backwards compat) ────────────

  test("graphqlHandler without opts arg executes normally"):
    val p = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl(simpleSdl))
    val ctx  = makeCtx()
    val impl = p.intrinsics(QualifiedName("graphqlHandler")).asInstanceOf[NativeImpl]
    val handler = impl.eval(ctx, List(
      Value.Foreign("GraphQLSchema", simpleSdl),
      Value.Foreign("GraphQLResolvers", defaultResolvers),
    )).asInstanceOf[Value.NativeFnV]
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV("""{"query":"{ hello }"}"""),
      "headers" -> Value.MapV(Map.empty),
    ))
    val resp = Computation.run(handler.f(List(req)))
    resp match
      case Value.InstanceV("Response", fields) =>
        val body = ujson.read(fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("{}"))
        assert(body("data")("hello").str == "world")
      case other => fail(s"expected Response, got $other")
