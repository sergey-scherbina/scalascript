package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Computation, Value}
import scalascript.ir.QualifiedName
import scalascript.backend.spi.NativeImpl
import ujson.*

/** Phase 6 — typed resolver mapping tests.
 *
 *  Covers: GraphQL.scalar codec registration, custom scalar serialize/coerce,
 *  scalars map threading through GraphQL.resolvers, end-to-end query execution
 *  with custom scalars as output and input types.
 */
class GraphQLTypedResolversTest extends AnyFunSuite:

  private def makeCtx(@annotation.unused p: GraphQLInterpreterPlugin): scalascript.backend.spi.NativeContext =
    new scalascript.backend.spi.NativeContext:
      def out = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
      def err = out
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        fn match
          case f: Value.NativeFnV => Computation.run(f.f(args.collect { case v: Value => v }))
          case _                  => Value.NullV

  private def makeHandler(
    sdl:       String,
    resolvers: GraphQLResolvers,
    p:         GraphQLInterpreterPlugin = new GraphQLInterpreterPlugin(),
  ): Value.NativeFnV =
    p.graphqlBlockRunner.foreach(_.registerSdl(sdl))
    val ctx  = makeCtx(p)
    val impl = p.intrinsics(QualifiedName("graphqlHandler")).asInstanceOf[NativeImpl]
    impl.eval(ctx, List(Value.Foreign("GraphQLSchema", sdl), Value.Foreign("GraphQLResolvers", resolvers)))
      .asInstanceOf[Value.NativeFnV]

  private def post(handler: Value.NativeFnV, query: String): ujson.Value =
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(s"""{"query":"${query.replace("\"", "\\\"")}"}"""),
      "headers" -> Value.MapV(Map.empty),
    ))
    val resp = Computation.run(handler.f(List(req)))
    val body = resp match
      case Value.InstanceV("Response", fields) =>
        fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("{}")
      case other => fail(s"expected Response, got $other")
    ujson.read(body)

  private def postWithVars(handler: Value.NativeFnV, query: String, vars: ujson.Obj): ujson.Value =
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(ujson.write(ujson.Obj("query" -> ujson.Str(query), "variables" -> vars))),
      "headers" -> Value.MapV(Map.empty),
    ))
    val resp = Computation.run(handler.f(List(req)))
    val body = resp match
      case Value.InstanceV("Response", fields) =>
        fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("{}")
      case other => fail(s"expected Response, got $other")
    ujson.read(body)

  // ── GraphQL.scalar intrinsic ───────────────────────────────────────────────

  test("GraphQL.scalar returns Foreign GraphQLScalar value"):
    val p    = new GraphQLInterpreterPlugin()
    val ctx  = makeCtx(p)
    val impl = p.intrinsics(QualifiedName("GraphQL.scalar")).asInstanceOf[NativeImpl]
    val serFn = Value.NativeFnV("ser", Computation.pureFn(args => args.head))
    val coerceFn = Value.NativeFnV("coerce", Computation.pureFn(args => args.head))
    val result = impl.eval(ctx, List("Date", serFn, coerceFn))
    result match
      case Value.Foreign("GraphQLScalar", (name: String, _: ScalarCodec)) =>
        assert(name == "Date")
      case other => fail(s"expected Foreign(GraphQLScalar), got $other")

  test("GraphQL.scalar codec serialize is called with resolver output"):
    val p    = new GraphQLInterpreterPlugin()
    val ctx  = makeCtx(p)
    val impl = p.intrinsics(QualifiedName("GraphQL.scalar")).asInstanceOf[NativeImpl]
    var serialized: Value = Value.NullV
    val serFn = Value.NativeFnV("ser", Computation.pureFn { args =>
      serialized = args.head
      Value.StringV("2024-01-01")
    })
    val coerceFn = Value.NativeFnV("coerce", Computation.pureFn(args => args.head))
    val result   = impl.eval(ctx, List("Date", serFn, coerceFn))
    result match
      case Value.Foreign("GraphQLScalar", (_, codec: ScalarCodec)) =>
        codec.serialize(Value.StringV("test-value"))
        assert(serialized == Value.StringV("test-value"))
      case other => fail(s"expected Foreign(GraphQLScalar), got $other")

  test("GraphQL.scalar codec coerce is called with input value"):
    val p    = new GraphQLInterpreterPlugin()
    val ctx  = makeCtx(p)
    val impl = p.intrinsics(QualifiedName("GraphQL.scalar")).asInstanceOf[NativeImpl]
    var coerced: Any = null
    val serFn    = Value.NativeFnV("ser",    Computation.pureFn(args => args.head))
    val coerceFn = Value.NativeFnV("coerce", Computation.pureFn { args =>
      coerced = args.head
      args.head
    })
    val result = impl.eval(ctx, List("Date", serFn, coerceFn))
    result match
      case Value.Foreign("GraphQLScalar", (_, codec: ScalarCodec)) =>
        codec.coerce("2024-12-31")
        assert(coerced == Value.StringV("2024-12-31"))
      case other => fail(s"expected Foreign(GraphQLScalar), got $other")

  // ── GraphQL.resolvers with scalars ────────────────────────────────────────

  test("GraphQL.resolvers accepts scalars map"):
    val p    = new GraphQLInterpreterPlugin()
    val ctx  = makeCtx(p)
    val scalarImpl  = p.intrinsics(QualifiedName("GraphQL.scalar")).asInstanceOf[NativeImpl]
    val resolverImpl = p.intrinsics(QualifiedName("GraphQL.resolvers")).asInstanceOf[NativeImpl]
    val identity     = Value.NativeFnV("id", Computation.pureFn(args => args.head))
    val scalar       = scalarImpl.eval(ctx, List("Date", identity, identity)).asInstanceOf[Value]
    val scalarsMap   = Value.MapV(Map(Value.StringV("Date") -> scalar))
    val result = resolverImpl.eval(ctx, List(
      Value.MapV(Map.empty),
      Value.MapV(Map.empty),
      Value.MapV(Map.empty),
      scalarsMap,
    ))
    result match
      case Value.Foreign("GraphQLResolvers", res: GraphQLResolvers) =>
        assert(res.scalars.contains("Date"))
      case other => fail(s"expected Foreign(GraphQLResolvers), got $other")

  // ── End-to-end custom scalar ──────────────────────────────────────────────

  test("custom scalar registered in resolvers — resolver output is serialized via codec"):
    val sdl =
      """|scalar Date
         |type Query { today: Date! }""".stripMargin

    val codec = ScalarCodec(
      serialize = v => v match { case Value.StringV(s) => s; case _ => v.toString },
      coerce    = raw => Value.StringV(String.valueOf(raw)),
    )
    val resolvers = GraphQLResolvers(
      query    = Map("today" -> Value.NativeFnV("today", Computation.pureFn(_ => Value.StringV("2024-06-15")))),
      mutation = Map.empty,
      scalars  = Map("Date" -> codec),
    )
    val h    = makeHandler(sdl, resolvers)
    val resp = post(h, "{ today }")
    assert(resp("data")("today").str == "2024-06-15")

  test("custom scalar as query argument — input value is coerced via codec"):
    val sdl =
      """|scalar Date
         |type Query { daysBefore(end: Date!): Int! }""".stripMargin

    var receivedArg: Value = Value.NullV
    val codec = ScalarCodec(
      serialize = v => v match { case Value.IntV(n) => n; case _ => 0 },
      coerce    = raw => Value.StringV(String.valueOf(raw)),
    )
    val resolvers = GraphQLResolvers(
      query = Map("daysBefore" -> Value.NativeFnV("daysBefore", Computation.pureFn {
        case List(Value.MapV(args)) =>
          receivedArg = args.getOrElse(Value.StringV("end"), Value.NullV)
          Value.IntV(42L)
        case _ => Value.IntV(0L)
      })),
      mutation = Map.empty,
      scalars  = Map("Date" -> codec),
    )
    val h    = makeHandler(sdl, resolvers)
    val resp = postWithVars(h, "query D($d: Date!) { daysBefore(end: $d) }",
                              ujson.Obj("d" -> ujson.Str("2024-12-31")))
    assert(resp("data")("daysBefore").num.toLong == 42L)
    assert(receivedArg == Value.StringV("2024-12-31"))

  test("multiple custom scalars can be registered simultaneously"):
    val sdl =
      """|scalar Date
         |scalar JSON
         |type Query { stamp: Date! meta: JSON! }""".stripMargin

    val dateCodec = ScalarCodec(
      serialize = { case Value.StringV(s) => s; case v => v.toString },
      coerce    = raw => Value.StringV(String.valueOf(raw)),
    )
    val jsonCodec = ScalarCodec(
      serialize = { case Value.StringV(s) => s; case v => v.toString },
      coerce    = raw => Value.StringV(String.valueOf(raw)),
    )
    val resolvers = GraphQLResolvers(
      query = Map(
        "stamp" -> Value.NativeFnV("stamp", Computation.pureFn(_ => Value.StringV("2024-01-01"))),
        "meta"  -> Value.NativeFnV("meta",  Computation.pureFn(_ => Value.StringV("""{"ok":true}"""))),
      ),
      mutation = Map.empty,
      scalars  = Map("Date" -> dateCodec, "JSON" -> jsonCodec),
    )
    val h    = makeHandler(sdl, resolvers)
    val resp = post(h, "{ stamp meta }")
    assert(resp("data")("stamp").str == "2024-01-01")
    assert(resp("data")("meta").str == """{"ok":true}""")

  test("resolvers without scalars field still works (backwards compat)"):
    val sdl = "type Query { hello: String! }"
    val resolvers = GraphQLResolvers(
      query    = Map("hello" -> Value.NativeFnV("hello", Computation.pureFn(_ => Value.StringV("world")))),
      mutation = Map.empty,
    )
    assert(resolvers.scalars.isEmpty)
    val h    = makeHandler(sdl, resolvers)
    val resp = post(h, "{ hello }")
    assert(resp("data")("hello").str == "world")

  // ── Object output types (records as resolver return) ──────────────────────

  test("resolver returns nested object as Value.MapV"):
    val sdl =
      """|type Query { user: User! }
         |type User { id: Int! name: String! }""".stripMargin

    val userResolver = Value.NativeFnV("user", Computation.pureFn(_ =>
      Value.MapV(Map(
        Value.StringV("id")   -> Value.IntV(1L),
        Value.StringV("name") -> Value.StringV("Alice"),
      ))
    ))
    val resolvers = GraphQLResolvers(
      query    = Map("Query.user" -> userResolver),
      mutation = Map.empty,
    )
    val h    = makeHandler(sdl, resolvers)
    val resp = post(h, "{ user { id name } }")
    assert(resp("data")("user")("id").num.toLong == 1L)
    assert(resp("data")("user")("name").str == "Alice")

  test("resolver returns list of objects"):
    val sdl =
      """|type Query { users: [User!]! }
         |type User { id: Int! }""".stripMargin

    val users = Value.ListV(List(
      Value.MapV(Map(Value.StringV("id") -> Value.IntV(1L))),
      Value.MapV(Map(Value.StringV("id") -> Value.IntV(2L))),
    ))
    val resolvers = GraphQLResolvers(
      query    = Map("Query.users" -> Value.NativeFnV("users", Computation.pureFn(_ => users))),
      mutation = Map.empty,
    )
    val h    = makeHandler(sdl, resolvers)
    val resp = post(h, "{ users { id } }")
    assert(resp("data")("users").arr.map(_(("id")).num.toLong).toList == List(1L, 2L))
