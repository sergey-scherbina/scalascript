package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Computation, Interpreter, Value}
import scalascript.parser.Parser
import scalascript.testkit.TestInterpreter

class GraphQLIntrinsicsTest extends AnyFunSuite:

  private def plugin = new GraphQLInterpreterPlugin()

  private def interp: TestInterpreter = TestInterpreter(List(plugin))

  // ── GraphQL.schema ─────────────────────────────────────────────────────────

  test("GraphQL.schema parses valid SDL and returns opaque schema value"):
    val result = interp.eval("""GraphQL.schema("type Query { hello: String! }")""")
    result match
      case Value.Foreign("GraphQLSchema", _: String) => () // pass
      case other => fail(s"expected GraphQLSchema Foreign, got $other")

  test("GraphQL.schema rejects invalid SDL"):
    val e = intercept[Exception]:
      interp.eval("""GraphQL.schema("NOT VALID SDL !!!")""")
    assert(e.getMessage != null)

  // ── GraphQL.resolvers ──────────────────────────────────────────────────────

  test("GraphQL.resolvers with query map returns opaque resolvers value"):
    val result = interp.eval(
      """GraphQL.resolvers(query = Map("hello" -> ((_: Map[String, Any]) => "world")))""")
    result match
      case Value.Foreign("GraphQLResolvers", _: GraphQLResolvers) => () // pass
      case other => fail(s"expected GraphQLResolvers Foreign, got $other")

  test("GraphQL.resolvers with empty args returns resolvers value"):
    val result = interp.eval("GraphQL.resolvers()")
    result match
      case Value.Foreign("GraphQLResolvers", _: GraphQLResolvers) => () // pass
      case other => fail(s"expected GraphQLResolvers Foreign, got $other")

  // ── graphql fenced block runner ────────────────────────────────────────────

  test("graphqlBlockRunner stores and retrieves SDL"):
    val runner = new GraphQLJvmBlockRunner()
    assert(runner.registeredSdl.isEmpty)
    runner.registerSdl("type Query { hello: String! }")
    assert(runner.registeredSdl.contains("type Query { hello: String! }"))

  test("graphql fenced block in document populates the runner"):
    val p = new GraphQLInterpreterPlugin()
    val interpreter = new Interpreter(headless = true)
    interpreter.installPlugins(List(p))
    val src =
      """|# Schema
         |
         |```graphql
         |type Query { hello: String! }
         |```
         |""".stripMargin
    interpreter.run(Parser.parse(src))
    assert(p.graphqlBlockRunner.flatMap(_.registeredSdl).isDefined)
    assert(p.graphqlBlockRunner.flatMap(_.registeredSdl).exists(_.contains("hello")))

  // ── graphqlMount / route registration ─────────────────────────────────────

  test("graphqlMount registers POST and GET /graphql routes (headless)"):
    val p = new GraphQLInterpreterPlugin()
    val interpreter = new Interpreter(headless = true)
    interpreter.installPlugins(List(p))
    val src =
      """|# Schema
         |
         |```graphql
         |type Query { hello: String! }
         |```
         |
         |# Resolvers
         |
         |```scala
         |val resolvers = GraphQL.resolvers(
         |  query = Map("hello" -> ((_: Map[String, Any]) => "world"))
         |)
         |graphqlMount(resolvers)
         |```
         |""".stripMargin
    interpreter.run(Parser.parse(src))
    val routes = interpreter.routeRegistry.all
    assert(routes.exists(e => e.method == "POST" && e.path == "/graphql"))
    assert(routes.exists(e => e.method == "GET"  && e.path == "/graphql"))

  // ── End-to-end query execution via graphqlHandler ─────────────────────────

  test("graphqlHandler executes a simple query"):
    val json = executeQuery(
      sdl       = "type Query { hello: String! }",
      query     = "{ hello }",
      resolvers = GraphQLResolvers(
        query    = Map("hello" -> Value.NativeFnV("hello", Computation.pureFn(_ => Value.StringV("world")))),
        mutation = Map.empty,
      ),
    )
    assert(json("data")("hello").str == "world")

  test("graphqlHandler resolves query with string argument"):
    val json = executeQuery(
      sdl   = "type Query { greet(name: String!): String! }",
      query = """{ greet(name: "Alice") }""",
      resolvers = GraphQLResolvers(
        query = Map("greet" -> Value.NativeFnV("greet", Computation.pureFn {
          case List(Value.MapV(args)) =>
            val name = args.get(Value.StringV("name")).collect { case Value.StringV(s) => s }.getOrElse("")
            Value.StringV(s"Hello, $name!")
          case _ => Value.StringV("Hello!")
        })),
        mutation = Map.empty,
      ),
    )
    assert(json("data")("greet").str == "Hello, Alice!")

  test("graphqlHandler returns integer field"):
    val json = executeQuery(
      sdl       = "type Query { count: Int! }",
      query     = "{ count }",
      resolvers = GraphQLResolvers(
        query    = Map("count" -> Value.NativeFnV("count", Computation.pureFn(_ => Value.IntV(42L)))),
        mutation = Map.empty,
      ),
    )
    assert(json("data")("count").num.toLong == 42L)

  test("graphqlHandler returns boolean field"):
    val json = executeQuery(
      sdl       = "type Query { ok: Boolean! }",
      query     = "{ ok }",
      resolvers = GraphQLResolvers(
        query    = Map("ok" -> Value.NativeFnV("ok", Computation.pureFn(_ => Value.BoolV(true)))),
        mutation = Map.empty,
      ),
    )
    assert(json("data")("ok").bool)

  test("graphqlHandler returns list field"):
    val json = executeQuery(
      sdl   = "type Query { items: [String!]! }",
      query = "{ items }",
      resolvers = GraphQLResolvers(
        query = Map("items" -> Value.NativeFnV("items", Computation.pureFn { _ =>
          Value.ListV(List(Value.StringV("a"), Value.StringV("b"), Value.StringV("c")))
        })),
        mutation = Map.empty,
      ),
    )
    assert(json("data")("items").arr.map(_.str).toList == List("a", "b", "c"))

  test("graphqlHandler handles introspection __typename"):
    val json = executeQuery(
      sdl       = "type Query { _dummy: String }",
      query     = "{ __typename }",
      resolvers = GraphQLResolvers(Map.empty, Map.empty),
    )
    assert(json("data")("__typename").str == "Query")

  test("graphqlHandler response has Content-Type application/json and status 200"):
    val resp = buildAndInvokeHandler(
      sdl       = "type Query { ok: Boolean }",
      query     = "{ ok }",
      resolvers = GraphQLResolvers(Map.empty, Map.empty),
    )
    resp match
      case Value.InstanceV("Response", fields) =>
        assert(fields.get("status").contains(Value.IntV(200L)))
        fields.get("headers") match
          case Some(Value.MapV(h)) =>
            val ct = h.get(Value.StringV("Content-Type")).collect { case Value.StringV(s) => s }
            assert(ct.contains("application/json"), s"headers: $h")
          case _ => fail("no headers in response")
      case other => fail(s"expected Response InstanceV, got $other")

  test("graphqlHandler executes mutation with argument"):
    val json = executeQuery(
      sdl   = "type Query { _empty: String } type Mutation { createUser(name: String!): String! }",
      query = """mutation { createUser(name: "Bob") }""",
      resolvers = GraphQLResolvers(
        query    = Map.empty,
        mutation = Map("createUser" -> Value.NativeFnV("createUser", Computation.pureFn {
          case List(Value.MapV(args)) =>
            args.get(Value.StringV("name")).collect { case Value.StringV(s) => s }
              .map(Value.StringV.apply).getOrElse(Value.StringV(""))
          case _ => Value.StringV("")
        })),
      ),
    )
    assert(json("data")("createUser").str == "Bob")

  // ── Helpers ────────────────────────────────────────────────────────────────

  private def buildAndInvokeHandler(sdl: String, query: String, resolvers: GraphQLResolvers): Value =
    val p = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl(sdl))

    val ctx = new scalascript.backend.spi.NativeContext:
      def out: java.io.PrintStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
      def err: java.io.PrintStream = out
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        fn match
          case f: Value.NativeFnV =>
            Computation.run(f.f(args.collect { case v: Value => v }))
          case _ => Value.NullV

    val handlerImpl = p.intrinsics(scalascript.ir.QualifiedName("graphqlHandler"))
      .asInstanceOf[scalascript.backend.spi.NativeImpl]
    val schemaForeign   = Value.Foreign("GraphQLSchema", sdl)
    val resolverForeign = Value.Foreign("GraphQLResolvers", resolvers)
    val handler = handlerImpl.eval(ctx, List(schemaForeign, resolverForeign))
      .asInstanceOf[Value.NativeFnV]
    val body    = s"""{"query":"${query.replace("\"", "\\\"")}"}"""
    val request = Value.InstanceV("Request", Map(
      "method" -> Value.StringV("POST"),
      "path"   -> Value.StringV("/graphql"),
      "body"   -> Value.StringV(body),
    ))
    Computation.run(handler.f(List(request)))

  private def executeQuery(sdl: String, query: String, resolvers: GraphQLResolvers): ujson.Value =
    val resp = buildAndInvokeHandler(sdl, query, resolvers)
    val bodyStr = resp match
      case Value.InstanceV("Response", fields) =>
        fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("{}")
      case other => fail(s"expected Response, got $other")
    ujson.read(bodyStr)
