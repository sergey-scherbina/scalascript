package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Computation, Value}
import scalascript.ir.QualifiedName
import scalascript.backend.spi.NativeImpl
import ujson.*

/** Phase 8 — persisted operations / APQ tests.
 *
 *  APQ (Automatic Persisted Queries) flow:
 *   1. Client sends hash-only request → server returns PersistedQueryNotFound if hash unknown.
 *   2. Client resends with hash + query → server stores and executes.
 *   3. Server supports persistedOnly mode: reject any query not in the approved manifest.
 *
 *  Spec: docs/graphql.md §7 Phase 8.
 */
class GraphQLPersistedOpsTest extends AnyFunSuite:

  private val simpleSdl = "type Query { hello: String! greet(name: String!): String! }"

  private def makeCtx(): scalascript.backend.spi.NativeContext =
    new scalascript.backend.spi.NativeContext:
      def out = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
      def err = out
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        fn match
          case f: Value.NativeFnV => Computation.run(f.f(args.collect { case v: Value => v }))
          case _                  => Value.NullV

  private def defaultResolvers = GraphQLResolvers(
    query = Map(
      "hello" -> Value.NativeFnV("hello", Computation.pureFn(_ => Value.StringV("world"))),
      "greet" -> Value.NativeFnV("greet", Computation.pureFn {
        case List(Value.MapV(args)) =>
          val name = args.get(Value.StringV("name")).collect { case Value.StringV(s) => s }.getOrElse("?")
          Value.StringV(s"Hi, $name!")
        case _ => Value.StringV("Hi!")
      }),
    ),
    mutation = Map.empty,
  )

  private def sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
      .map(b => f"$b%02x").mkString

  private def makeHandler(opts: GraphQLOpts): Value.NativeFnV =
    val p   = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl(simpleSdl))
    val ctx  = makeCtx()
    val impl = p.intrinsics(QualifiedName("graphqlHandler")).asInstanceOf[NativeImpl]
    impl.eval(ctx, List(
      Value.Foreign("GraphQLSchema", simpleSdl),
      Value.Foreign("GraphQLResolvers", defaultResolvers),
      Value.Foreign("GraphQLOptions", opts),
    )).asInstanceOf[Value.NativeFnV]

  private def post(handler: Value.NativeFnV, bodyJson: String): (Long, ujson.Value) =
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(bodyJson),
      "headers" -> Value.MapV(Map.empty),
    ))
    val resp = Computation.run(handler.f(List(req)))
    resp match
      case Value.InstanceV("Response", fields) =>
        val status = fields.get("status").collect { case Value.IntV(n) => n }.getOrElse(-1L)
        val bodyStr = fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("{}")
        (status, ujson.read(bodyStr))
      case other => fail(s"expected Response, got $other")

  private def apqRequest(hash: String, query: Option[String] = None): String =
    val ext = ujson.Obj("persistedQuery" -> ujson.Obj("version" -> ujson.Num(1), "sha256Hash" -> ujson.Str(hash)))
    val obj = ujson.Obj("extensions" -> ext)
    query.foreach(q => obj("query") = ujson.Str(q))
    ujson.write(obj)

  // ── GraphQL.options — persistedOps/persistedOnly ──────────────────────────

  test("GraphQL.options accepts persistedOps map and persistedOnly flag"):
    val p    = new GraphQLInterpreterPlugin()
    val impl = p.intrinsics(QualifiedName("GraphQL.options")).asInstanceOf[NativeImpl]
    val manifest = Value.MapV(Map(
      Value.StringV("abc123") -> Value.StringV("{ hello }"),
    ))
    val result = impl.eval(makeCtx(), List(
      Value.NullV,  // maxDepth — unused
      Value.NullV,  // maxComplexity — unused
      Value.NullV,  // maxQueryLength — unused
      Value.BoolV(false),
      manifest,
      Value.BoolV(true),
    ))
    result match
      case Value.Foreign("GraphQLOptions", opts: GraphQLOpts) =>
        assert(opts.persistedOps == Map("abc123" -> "{ hello }"))
        assert(opts.persistedOnly)
      case other => fail(s"expected Foreign(GraphQLOptions), got $other")

  // ── APQ: hash-only request — PersistedQueryNotFound ──────────────────────

  test("APQ hash-only request returns PersistedQueryNotFound when hash is unknown"):
    val h = makeHandler(GraphQLOpts.default)
    val (status, body) = post(h, apqRequest(hash = "unknownhash"))
    assert(status == 200L)
    assert(body.obj.contains("errors"))
    val msg = body("errors").arr.head("message").str
    assert(msg.contains("PersistedQueryNotFound"), s"unexpected message: $msg")
    val code = body("errors").arr.head("extensions")("code").str
    assert(code == "PERSISTED_QUERY_NOT_FOUND")

  test("APQ hash-only request succeeds when hash is in persistedOps manifest"):
    val query = "{ hello }"
    val hash  = sha256(query)
    val opts  = GraphQLOpts(persistedOps = Map(hash -> query))
    val h     = makeHandler(opts)
    val (status, body) = post(h, apqRequest(hash))
    assert(status == 200L)
    assert(!body.obj.contains("errors") || body("errors").arr.isEmpty)
    assert(body("data")("hello").str == "world")

  test("APQ hash+query request executes the query regardless of persistedOps"):
    val query = "{ hello }"
    val hash  = sha256(query)
    val h     = makeHandler(GraphQLOpts.default)
    val (status, body) = post(h, apqRequest(hash, Some(query)))
    assert(status == 200L)
    assert(body("data")("hello").str == "world")

  test("APQ hash+query request works with persistedOps that contains the hash"):
    val query = "{ hello }"
    val hash  = sha256(query)
    val opts  = GraphQLOpts(persistedOps = Map(hash -> query))
    val h     = makeHandler(opts)
    val (status, body) = post(h, apqRequest(hash, Some(query)))
    assert(status == 200L)
    assert(body("data")("hello").str == "world")

  // ── persistedOnly mode ────────────────────────────────────────────────────

  test("persistedOnly rejects raw query not in manifest"):
    val allowedQuery = "{ hello }"
    val opts = GraphQLOpts(
      persistedOps  = Map(sha256(allowedQuery) -> allowedQuery),
      persistedOnly = true,
    )
    val h = makeHandler(opts)
    val (status, body) = post(h, """{"query":"{ greet(name: \"X\") }"}""")
    assert(status == 200L)
    assert(body.obj.contains("errors"), s"expected rejection: $body")
    val msg = body("errors").arr.head("message").str
    assert(msg.contains("persisted"), s"unexpected message: $msg")

  test("persistedOnly allows query whose hash is in the manifest (via APQ hash-only)"):
    val query = "{ hello }"
    val hash  = sha256(query)
    val opts  = GraphQLOpts(
      persistedOps  = Map(hash -> query),
      persistedOnly = true,
    )
    val h = makeHandler(opts)
    val (status, body) = post(h, apqRequest(hash))
    assert(status == 200L)
    assert(body("data")("hello").str == "world")

  test("persistedOnly allows plain query whose hash matches manifest"):
    val query = "{ hello }"
    val hash  = sha256(query)
    val opts  = GraphQLOpts(
      persistedOps  = Map(hash -> query),
      persistedOnly = true,
    )
    val h = makeHandler(opts)
    // Send the actual query text — server should compute its hash and check
    val (status, body) = post(h, s"""{"query":"$query"}""")
    assert(status == 200L)
    assert(body("data")("hello").str == "world")

  // ── APQ with variables ────────────────────────────────────────────────────

  test("APQ hash-only with variables — resolves from manifest and passes variables"):
    val query = """query G($n: String!) { greet(name: $n) }"""
    val hash  = sha256(query)
    val opts  = GraphQLOpts(persistedOps = Map(hash -> query))
    val h     = makeHandler(opts)
    val body  = ujson.Obj(
      "extensions" -> ujson.Obj("persistedQuery" ->
        ujson.Obj("version" -> ujson.Num(1), "sha256Hash" -> ujson.Str(hash))),
      "variables"  -> ujson.Obj("n" -> ujson.Str("Carol")),
    )
    val (status, resp) = post(h, ujson.write(body))
    assert(status == 200L)
    assert(resp("data")("greet").str == "Hi, Carol!")

  // ── Normal request with empty persistedOps (no restriction) ──────────────

  test("normal query works when persistedOps is empty (default)"):
    val h = makeHandler(GraphQLOpts.default)
    val (status, body) = post(h, """{"query":"{ hello }"}""")
    assert(status == 200L)
    assert(body("data")("hello").str == "world")
