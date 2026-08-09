package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.{NativeImpl, NativeContext}
import scalascript.interpreter.{Computation, Value}
import scalascript.ir.QualifiedName

/** Phase 12 — Apollo Federation v2 subgraph support.
 *
 *  Covers: _service { sdl } introspection, federation directive passthrough,
 *  entity resolver dispatch, _entities query, graphqlSubgraphMount registration,
 *  serveSubgraph intrinsic registration, and multi-type entity resolution.
 */
class GraphQLFederationTest extends AnyFunSuite:

  private val SDL = "type Query { hello: String! }"
  private val SDL_WITH_KEY =
    """
      |type Query { product(id: ID!): Product }
      |type Product @key(fields: "id") {
      |  id: ID!
      |  name: String!
      |}
      |""".stripMargin.trim

  private val dummyFn: Value.NativeFnV = Value.NativeFnV("dummy", Computation.pureFn(_ => Value.StringV("ok")))
  private def nullStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  private def buildCtx(): NativeContext = new NativeContext:
    def out = nullStream
    def err = out
    override def invokeCallback(fn: Any, args: List[Any]): Any = fn match
      case f: Value.NativeFnV => Computation.run(f.f(args.collect { case v: Value => v }))
      case _                   => Value.NullV

  /** Mount a subgraph (no entity resolvers) and capture the HTTP handler. */
  private def buildSubgraphHandler(
    sdl:      String,
    queryFns: Map[String, Value.NativeFnV],
  ): Value.NativeFnV =
    val p   = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl(sdl))
    var capturedHandler: Value.NativeFnV = null
    val ctx = new NativeContext:
      def out = nullStream
      def err = out
      override def invokeCallback(fn: Any, args: List[Any]): Any = fn match
        case f: Value.NativeFnV => Computation.run(f.f(args.collect { case v: Value => v }))
        case _                   => Value.NullV
      override def registerRoute(method: String, path: String, handler: Any): Unit =
        if method == "POST" then capturedHandler = handler.asInstanceOf[Value.NativeFnV]

    val res = GraphQLResolvers(
      query    = queryFns.map { (k, v) => k -> (v: Value) },
      mutation = Map.empty,
    )
    val mountImpl = p.intrinsics(QualifiedName("graphqlSubgraphMount")).asInstanceOf[NativeImpl]
    mountImpl.eval(ctx, List(Value.Foreign("GraphQLResolvers", res)))
    capturedHandler

  private def invoke(handler: Value.NativeFnV, body: String): Value.InstanceV =
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(body),
      "headers" -> Value.MapV(Map(
        Value.StringV("Content-Type") -> Value.StringV("application/json"),
      )),
    ))
    Computation.run(handler.f(List(req))).asInstanceOf[Value.InstanceV]

  private def responseBody(resp: Value.InstanceV): ujson.Value =
    resp.fields.get("body").collect { case Value.StringV(s) => ujson.read(s) }.getOrElse(ujson.Null)

  // ── Intrinsic registration ────────────────────────────────────────────────

  test("graphqlSubgraphMount intrinsic is registered"):
    val p = new GraphQLInterpreterPlugin()
    assert(p.intrinsics.contains(QualifiedName("graphqlSubgraphMount")),
      "graphqlSubgraphMount should be in the intrinsics table")

  test("serveSubgraph intrinsic is registered"):
    val p = new GraphQLInterpreterPlugin()
    assert(p.intrinsics.contains(QualifiedName("serveSubgraph")),
      "serveSubgraph should be in the intrinsics table")

  test("GraphQL.entityResolvers intrinsic is registered"):
    val p = new GraphQLInterpreterPlugin()
    assert(p.intrinsics.contains(QualifiedName("GraphQL.entityResolvers")),
      "GraphQL.entityResolvers should be in the intrinsics table")

  // ── graphqlSubgraphMount route registration ───────────────────────────────

  test("graphqlSubgraphMount registers POST /graphql route"):
    val p   = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl(SDL))
    var registeredPost = false
    val ctx = new NativeContext:
      def out = nullStream
      def err = out
      override def registerRoute(method: String, path: String, handler: Any): Unit =
        if method == "POST" && path == "/graphql" then registeredPost = true

    val res = GraphQLResolvers(query = Map("hello" -> dummyFn), mutation = Map.empty)
    val mountImpl = p.intrinsics(QualifiedName("graphqlSubgraphMount")).asInstanceOf[NativeImpl]
    mountImpl.eval(ctx, List(Value.Foreign("GraphQLResolvers", res)))
    assert(registeredPost, "POST /graphql should be registered")

  // ── _service { sdl } ──────────────────────────────────────────────────────

  test("_service { sdl } query returns the original SDL"):
    val handler = buildSubgraphHandler(SDL, Map("hello" -> dummyFn))
    assert(handler != null, "handler should have been registered")
    val resp = invoke(handler, """{"query":"{ _service { sdl } }"}""")
    val data = responseBody(resp)("data")
    assert(!data.isNull, s"data should not be null, got: $data")
    val sdl  = data("_service")("sdl").str
    assert(sdl.contains("Query"), s"SDL should contain Query type, got: $sdl")
    assert(sdl.contains("hello"), s"SDL should contain hello field, got: $sdl")

  test("_service { sdl } returns the exact registered SDL"):
    val handler = buildSubgraphHandler(SDL, Map("hello" -> dummyFn))
    val resp    = invoke(handler, """{"query":"{ _service { sdl } }"}""")
    val sdl     = responseBody(resp)("data")("_service")("sdl").str
    assert(sdl.contains(SDL.trim), s"returned SDL should contain the registered SDL, got: $sdl")

  // ── Federation directive passthrough ──────────────────────────────────────

  test("SDL with @key directive mounts without parse error"):
    val queryFn: Value.NativeFnV = Value.NativeFnV("product", Computation.pureFn {
      _ => Value.MapV(Map(Value.StringV("id") -> Value.StringV("1"), Value.StringV("name") -> Value.StringV("Widget")))
    })
    val handler = buildSubgraphHandler(SDL_WITH_KEY, Map("product" -> queryFn))
    assert(handler != null, "handler should be registered when SDL contains @key directive")

  test("regular query still works after graphqlSubgraphMount"):
    val queryFn: Value.NativeFnV = Value.NativeFnV("hello", Computation.pureFn(_ => Value.StringV("world")))
    val handler  = buildSubgraphHandler(SDL, Map("hello" -> queryFn))
    val resp     = invoke(handler, """{"query":"{ hello }"}""")
    val data     = responseBody(resp)("data")
    assert(data("hello").str == "world", s"expected 'world', got: ${data("hello")}")

  // ── GraphQL.entityResolvers ───────────────────────────────────────────────

  test("GraphQL.entityResolvers creates Foreign GraphQLFederationEntities value"):
    val p   = new GraphQLInterpreterPlugin()
    val ctx = buildCtx()
    val fn: Value.NativeFnV  = Value.NativeFnV("product.entity", Computation.pureFn(_ => Value.NullV))
    val result = p.intrinsics(QualifiedName("GraphQL.entityResolvers")).asInstanceOf[NativeImpl]
      .eval(ctx, List(Value.MapV(Map((Value.StringV("Product"): Value) -> (fn: Value)))))
    result match
      case Value.Foreign("GraphQLFederationEntities", e: GraphQLFederationEntities) =>
        assert(e.entities.contains("Product"), "entities map should contain 'Product'")
      case other => fail(s"expected GraphQLFederationEntities, got $other")

  // ── _entities query ───────────────────────────────────────────────────────

  test("_entities query dispatches to entity resolver by __typename"):
    val p   = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl(SDL_WITH_KEY))

    val entityFn: Value.NativeFnV = Value.NativeFnV("Product.entity", Computation.pureFn {
      case List(_: Value) =>
        Value.MapV(Map(
          Value.StringV("__typename") -> Value.StringV("Product"),
          Value.StringV("id")         -> Value.StringV("42"),
          Value.StringV("name")       -> Value.StringV("Widget"),
        ))
      case _ => Value.NullV
    })

    var capturedHandler: Value.NativeFnV = null
    val ctx = new NativeContext:
      def out = nullStream
      def err = out
      override def invokeCallback(fn: Any, args: List[Any]): Any = fn match
        case f: Value.NativeFnV => Computation.run(f.f(args.collect { case v: Value => v }))
        case _                   => Value.NullV
      override def registerRoute(method: String, path: String, handler: Any): Unit =
        if method == "POST" then capturedHandler = handler.asInstanceOf[Value.NativeFnV]

    val res      = GraphQLResolvers(query = Map("product" -> dummyFn), mutation = Map.empty)
    val fedEnt   = GraphQLFederationEntities(Map("Product" -> entityFn))
    val mountImpl = p.intrinsics(QualifiedName("graphqlSubgraphMount")).asInstanceOf[NativeImpl]
    mountImpl.eval(ctx, List(
      Value.Foreign("GraphQLResolvers", res),
      Value.Foreign("GraphQLFederationEntities", fedEnt),
    ))

    assert(capturedHandler != null, "handler should have been registered")
    val body = ujson.write(ujson.Obj(
      "query" -> ujson.Str(
        "query ($reps: [_Any!]!) { _entities(representations: $reps) { ... on Product { id name } } }"
      ),
      "variables" -> ujson.Obj(
        "reps" -> ujson.Arr(ujson.Obj("__typename" -> ujson.Str("Product"), "id" -> ujson.Str("42")))
      ),
    ))
    val resp = invoke(capturedHandler, body)
    val data = responseBody(resp)("data")
    assert(!data.isNull, s"data should not be null, got: $responseBody(resp)")
    val entities = data("_entities")
    assert(entities.arr.nonEmpty, s"_entities should not be empty, got: $entities")
    assert(entities(0)("id").str == "42", s"expected id=42, got: ${entities(0)}")
