package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Value, Computation}
import scalascript.ir.QualifiedName
import scalascript.backend.spi.{NativeImpl, NativeContext}

/** Phase 9 — DataLoader and per-request cache tests.
 *
 *  Covers: GraphQL.dataLoader intrinsic, _load / _batchLoad injected into resolver
 *  args, per-request key deduplication, request cache isolation, multiple loaders,
 *  backward compat with resolvers that have no loaders.
 */
class GraphQLDataLoaderTest extends AnyFunSuite:

  private val SDL = "type Query { user(id: String!): String }"

  private def makeCtx(): NativeContext =
    new NativeContext:
      def out = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
      def err = out
      override def invokeCallback(fn: Any, args: List[Any]): Any = fn match
        case f: Value.NativeFnV => Computation.run(f.f(args.collect { case v: Value => v }))
        case _                  => Value.NullV

  private def makeHandler(
    resolverFn: Value.NativeFnV,
    loaders:    Map[String, DataLoaderSpec] = Map.empty,
  ): Value.NativeFnV =
    val p   = new GraphQLInterpreterPlugin()
    val ctx = makeCtx()
    val res = GraphQLResolvers(
      query    = Map("user" -> resolverFn),
      mutation = Map.empty,
      loaders  = loaders,
    )
    p.intrinsics(QualifiedName("graphqlHandler")).asInstanceOf[NativeImpl]
      .eval(ctx, List(Value.Foreign("GraphQLSchema", SDL), Value.Foreign("GraphQLResolvers", res)))
      .asInstanceOf[Value.NativeFnV]

  private def post(handler: Value.NativeFnV, query: String): ujson.Value =
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/graphql"),
      "body"    -> Value.StringV(s"""{"query":"${query.replace("\"", "\\\"")}"}"""),
      "headers" -> Value.MapV(Map.empty),
    ))
    val resp = Computation.run(handler.f(List(req)))
    resp match
      case Value.InstanceV("Response", fields) =>
        ujson.read(fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("{}"))
      case other => fail(s"expected Response InstanceV, got $other")

  // ── GraphQL.dataLoader intrinsic ──────────────────────────────────────────

  test("GraphQL.dataLoader returns Foreign GraphQLDataLoader value"):
    val p      = new GraphQLInterpreterPlugin()
    val ctx    = makeCtx()
    val batchF = Value.NativeFnV("batch", Computation.pureFn(_ => Value.MapV(Map.empty)))
    val result = p.intrinsics(QualifiedName("GraphQL.dataLoader")).asInstanceOf[NativeImpl].eval(ctx, List("usersById", batchF))
    result match
      case Value.Foreign("GraphQLDataLoader", spec: DataLoaderSpec) =>
        assert(spec.name == "usersById")
        assert(spec.batchFn.isInstanceOf[Value.NativeFnV], "batchFn must be a NativeFnV")
      case other => fail(s"expected Foreign(GraphQLDataLoader), got $other")

  test("GraphQL.dataLoader with wrong arg count throws InterpretError"):
    val p   = new GraphQLInterpreterPlugin()
    val ctx = makeCtx()
    intercept[scalascript.interpreter.InterpretError] {
      p.intrinsics(QualifiedName("GraphQL.dataLoader")).asInstanceOf[NativeImpl].eval(ctx, List("onlyName"))
    }

  // ── Resolver _load injection ───────────────────────────────────────────────

  test("_load is injected into resolver args when loaders are registered"):
    var sawLoad = false
    val resolverFn: Value.NativeFnV = Value.NativeFnV("resolver", Computation.pureFn {
      case List(Value.MapV(m)) =>
        sawLoad = m.contains(Value.StringV("_load"))
        Value.StringV("ok")
      case _ => Value.StringV("ok")
    })
    val batchFn: Value.NativeFnV = Value.NativeFnV("batch", Computation.pureFn {
      case List(Value.ListV(keys)) =>
        Value.MapV(keys.collect { case k @ Value.StringV(_) => k -> Value.StringV("v") }.toMap)
      case _ => Value.MapV(Map.empty)
    })
    val handler = makeHandler(resolverFn, Map("usersById" -> DataLoaderSpec("usersById", batchFn)))
    post(handler, """{ user(id: "u1") }""")
    assert(sawLoad, "_load should be injected into resolver args when loaders are registered")

  test("_batchLoad is injected into resolver args when loaders are registered"):
    var sawBatchLoad = false
    val resolverFn: Value.NativeFnV = Value.NativeFnV("resolver", Computation.pureFn {
      case List(Value.MapV(m)) =>
        sawBatchLoad = m.contains(Value.StringV("_batchLoad"))
        Value.StringV("ok")
      case _ => Value.StringV("ok")
    })
    val batchFn: Value.NativeFnV = Value.NativeFnV("batch", Computation.pureFn(_ => Value.MapV(Map.empty)))
    val handler = makeHandler(resolverFn, Map("usersById" -> DataLoaderSpec("usersById", batchFn)))
    post(handler, """{ user(id: "u1") }""")
    assert(sawBatchLoad, "_batchLoad should be injected into resolver args")

  test("DataLoader batch fn is called when resolver invokes _load"):
    var batchCalled = false
    val batchFn: Value.NativeFnV = Value.NativeFnV("batch", Computation.pureFn {
      case List(Value.ListV(keys)) =>
        batchCalled = true
        Value.MapV(keys.collect { case k @ Value.StringV(_) => k -> Value.StringV("Alice") }.toMap)
      case _ => Value.MapV(Map.empty)
    })
    val resolverFn: Value.NativeFnV = Value.NativeFnV("resolver", Computation.pureFn {
      case List(Value.MapV(m)) =>
        val loadFn = m(Value.StringV("_load")).asInstanceOf[Value.NativeFnV]
        Computation.run(loadFn.f(List(Value.StringV("usersById"), Value.StringV("u1")))).asInstanceOf[Value]
      case _ => Value.NullV
    })
    val handler = makeHandler(resolverFn, Map("usersById" -> DataLoaderSpec("usersById", batchFn)))
    post(handler, """{ user(id: "u1") }""")
    assert(batchCalled, "batch function should have been called via _load")

  test("DataLoader caches key — batch fn called once for repeated key within one request"):
    var batchCallCount = 0
    val batchFn: Value.NativeFnV = Value.NativeFnV("batch", Computation.pureFn {
      case List(Value.ListV(_)) =>
        batchCallCount += 1
        Value.MapV(Map(Value.StringV("u1") -> Value.StringV("Alice")))
      case _ => Value.MapV(Map.empty)
    })
    val resolverFn: Value.NativeFnV = Value.NativeFnV("resolver", Computation.pureFn {
      case List(Value.MapV(m)) =>
        val loadFn = m(Value.StringV("_load")).asInstanceOf[Value.NativeFnV]
        Computation.run(loadFn.f(List(Value.StringV("usersById"), Value.StringV("u1"))))
        Computation.run(loadFn.f(List(Value.StringV("usersById"), Value.StringV("u1"))))  // same key again
        Value.StringV("done")
      case _ => Value.StringV("done")
    })
    val handler = makeHandler(resolverFn, Map("usersById" -> DataLoaderSpec("usersById", batchFn)))
    post(handler, """{ user(id: "u1") }""")
    assert(batchCallCount == 1, s"batch fn should be called once for repeated key, got $batchCallCount")

  test("DataLoader cache is isolated between requests — fresh cache per request"):
    var batchCallCount = 0
    val batchFn: Value.NativeFnV = Value.NativeFnV("batch", Computation.pureFn {
      case List(Value.ListV(_)) =>
        batchCallCount += 1
        Value.MapV(Map(Value.StringV("u1") -> Value.StringV("Alice")))
      case _ => Value.MapV(Map.empty)
    })
    val resolverFn: Value.NativeFnV = Value.NativeFnV("resolver", Computation.pureFn {
      case List(Value.MapV(m)) =>
        val loadFn = m(Value.StringV("_load")).asInstanceOf[Value.NativeFnV]
        Computation.run(loadFn.f(List(Value.StringV("usersById"), Value.StringV("u1")))).asInstanceOf[Value]
      case _ => Value.NullV
    })
    val handler = makeHandler(resolverFn, Map("usersById" -> DataLoaderSpec("usersById", batchFn)))
    post(handler, """{ user(id: "u1") }""")  // request 1 — fresh cache, batch fn called
    post(handler, """{ user(id: "u1") }""")  // request 2 — fresh cache, batch fn called again
    assert(batchCallCount == 2, s"each request should have its own cache; got $batchCallCount calls")

  test("DataLoader _load returns the correct value for the loaded key"):
    var capturedResult: Value = Value.NullV
    val batchFn: Value.NativeFnV = Value.NativeFnV("batch", Computation.pureFn {
      case List(Value.ListV(_)) =>
        Value.MapV(Map(Value.StringV("u42") -> Value.StringV("Bob")))
      case _ => Value.MapV(Map.empty)
    })
    val resolverFn: Value.NativeFnV = Value.NativeFnV("resolver", Computation.pureFn {
      case List(Value.MapV(m)) =>
        val loadFn = m(Value.StringV("_load")).asInstanceOf[Value.NativeFnV]
        capturedResult = Computation.run(loadFn.f(List(Value.StringV("usersById"), Value.StringV("u42")))).asInstanceOf[Value]
        capturedResult
      case _ => Value.NullV
    })
    val handler = makeHandler(resolverFn, Map("usersById" -> DataLoaderSpec("usersById", batchFn)))
    post(handler, """{ user(id: "u42") }""")
    assert(capturedResult == Value.StringV("Bob"), s"expected StringV(Bob), got $capturedResult")

  // ── _batchLoad ─────────────────────────────────────────────────────────────

  test("_batchLoad dispatches all keys in a single batch-fn call"):
    var batchCallCount = 0
    var receivedKeyCount = 0
    val batchFn: Value.NativeFnV = Value.NativeFnV("batch", Computation.pureFn {
      case List(Value.ListV(keys)) =>
        batchCallCount += 1
        receivedKeyCount = keys.size
        Value.MapV(keys.collect { case k @ Value.StringV(_) => k -> Value.StringV("v") }.toMap)
      case _ => Value.MapV(Map.empty)
    })
    val resolverFn: Value.NativeFnV = Value.NativeFnV("resolver", Computation.pureFn {
      case List(Value.MapV(m)) =>
        val batchLoadFn = m(Value.StringV("_batchLoad")).asInstanceOf[Value.NativeFnV]
        Computation.run(batchLoadFn.f(List(
          Value.StringV("usersById"),
          Value.ListV(List(Value.StringV("a"), Value.StringV("b"), Value.StringV("c"))),
        )))
        Value.StringV("ok")
      case _ => Value.StringV("ok")
    })
    val handler = makeHandler(resolverFn, Map("usersById" -> DataLoaderSpec("usersById", batchFn)))
    post(handler, """{ user(id: "x") }""")
    assert(batchCallCount == 1,   s"batchLoad should produce one batch-fn call, got $batchCallCount")
    assert(receivedKeyCount == 3, s"batch fn should receive 3 keys, got $receivedKeyCount")

  test("_batchLoad returns MapV with key→value entries for all requested keys"):
    var capturedMap: Value = Value.NullV
    val batchFn: Value.NativeFnV = Value.NativeFnV("batch", Computation.pureFn {
      case List(Value.ListV(_)) =>
        Value.MapV(Map(
          Value.StringV("a") -> Value.StringV("A"),
          Value.StringV("b") -> Value.StringV("B"),
        ))
      case _ => Value.MapV(Map.empty)
    })
    val resolverFn: Value.NativeFnV = Value.NativeFnV("resolver", Computation.pureFn {
      case List(Value.MapV(m)) =>
        val batchLoadFn = m(Value.StringV("_batchLoad")).asInstanceOf[Value.NativeFnV]
        capturedMap = Computation.run(batchLoadFn.f(List(
          Value.StringV("usersById"),
          Value.ListV(List(Value.StringV("a"), Value.StringV("b"))),
        ))).asInstanceOf[Value]
        Value.StringV("ok")
      case _ => Value.StringV("ok")
    })
    val handler = makeHandler(resolverFn, Map("usersById" -> DataLoaderSpec("usersById", batchFn)))
    post(handler, """{ user(id: "x") }""")
    capturedMap match
      case Value.MapV(m) =>
        assert(m.get(Value.StringV("a")).contains(Value.StringV("A")), s"missing key a: $m")
        assert(m.get(Value.StringV("b")).contains(Value.StringV("B")), s"missing key b: $m")
      case other => fail(s"expected MapV, got $other")

  // ── Multiple loaders ────────────────────────────────────────────────────────

  test("Multiple DataLoaders coexist and are called independently"):
    var callsA = 0; var callsB = 0
    val batchA: Value.NativeFnV = Value.NativeFnV("batchA", Computation.pureFn { _ => callsA += 1; Value.MapV(Map(Value.StringV("k") -> Value.StringV("A"))) })
    val batchB: Value.NativeFnV = Value.NativeFnV("batchB", Computation.pureFn { _ => callsB += 1; Value.MapV(Map(Value.StringV("k") -> Value.StringV("B"))) })
    val resolverFn: Value.NativeFnV = Value.NativeFnV("resolver", Computation.pureFn {
      case List(Value.MapV(m)) =>
        val loadFn = m(Value.StringV("_load")).asInstanceOf[Value.NativeFnV]
        Computation.run(loadFn.f(List(Value.StringV("loaderA"), Value.StringV("k"))))
        Computation.run(loadFn.f(List(Value.StringV("loaderB"), Value.StringV("k"))))
        Value.StringV("ok")
      case _ => Value.StringV("ok")
    })
    val handler = makeHandler(resolverFn, Map(
      "loaderA" -> DataLoaderSpec("loaderA", batchA),
      "loaderB" -> DataLoaderSpec("loaderB", batchB),
    ))
    post(handler, """{ user(id: "k") }""")
    assert(callsA == 1, s"loaderA batch fn should be called once; got $callsA")
    assert(callsB == 1, s"loaderB batch fn should be called once; got $callsB")

  // ── Backward compat ────────────────────────────────────────────────────────

  test("Resolver without _load works normally when no loaders registered"):
    val resolverFn: Value.NativeFnV = Value.NativeFnV("resolver", Computation.pureFn(_ => Value.StringV("hello")))
    val handler    = makeHandler(resolverFn)
    val json       = post(handler, """{ user(id: "x") }""")
    assert(json("data")("user").str == "hello", s"unexpected response: $json")

  test("GraphQL.resolvers with 5-arg form including loaders is backward compatible"):
    val p   = new GraphQLInterpreterPlugin()
    val ctx = makeCtx()
    val batchF: Value.NativeFnV = Value.NativeFnV("batch", Computation.pureFn(_ => Value.MapV(Map.empty)))
    val loaderVal = p.intrinsics(QualifiedName("GraphQL.dataLoader")).asInstanceOf[NativeImpl]
      .eval(ctx, List("myLoader", batchF)).asInstanceOf[Value]
    // 5-arg call: query, mutation, subscription, scalars, loaders
    val resolvers = p.intrinsics(QualifiedName("GraphQL.resolvers")).asInstanceOf[NativeImpl].eval(ctx, List(
      Value.MapV(Map.empty),
      Value.MapV(Map.empty),
      Value.MapV(Map.empty),
      Value.MapV(Map.empty),
      Value.MapV(Map(Value.StringV("myLoader") -> loaderVal)),
    ))
    resolvers match
      case Value.Foreign("GraphQLResolvers", res: GraphQLResolvers) =>
        assert(res.loaders.contains("myLoader"), "loaders map should contain the registered loader")
      case other => fail(s"expected Foreign(GraphQLResolvers), got $other")
