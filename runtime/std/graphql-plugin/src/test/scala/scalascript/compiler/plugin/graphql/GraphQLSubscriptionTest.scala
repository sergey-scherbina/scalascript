package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.{NativeImpl, NativeContext}
import scalascript.interpreter.{Computation, Value}
import scalascript.ir.QualifiedName

/** Phase 3 — WebSocket subscription tests (graphql-transport-ws protocol).
 *
 *  Covers: resolver map storage, WS route registration, protocol state machine
 *  (connection_init/ack, subscribe, ping/pong, complete, error, unknown messages,
 *  invalid JSON), and end-to-end subscription event delivery.
 */
class GraphQLSubscriptionTest extends AnyFunSuite:

  private val SDL_WITH_SUB =
    "type Query { _dummy: String } type Subscription { events: String! }"

  private val dummyFn: Value.NativeFnV = Value.NativeFnV("dummy", Computation.pureFn(_ => Value.NullV))

  private def nullStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  /** Build a plugin, register SDL, mount with subscription resolvers.
   *  Returns the captured WS handler and the list of messages sent over the WS. */
  private def setupWsHandler(
    subscriptionFn: Value.NativeFnV,
    query:          Map[String, Value] = Map("_dummy" -> dummyFn),
  ): (Value.NativeFnV, scala.collection.mutable.ListBuffer[String]) =
    val p = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl(SDL_WITH_SUB))

    var capturedWsHandler: Value.NativeFnV = null
    val sentMessages = scala.collection.mutable.ListBuffer[String]()

    val ctx = new NativeContext:
      def out = nullStream
      def err = out
      override def invokeCallback(fn: Any, args: List[Any]): Any = fn match
        case f: Value.NativeFnV =>
          Computation.run(f.f(args.collect { case v: Value => v }))
        case _ => Value.NullV
      override def registerRoute(method: String, path: String, handler: Any): Unit = ()
      override def registerWsRoute(
          path: String, origins: List[String], protocols: List[String],
          maxConn: Int, maxRate: Int, handler: Any): Unit =
        capturedWsHandler = handler.asInstanceOf[Value.NativeFnV]

    val res = GraphQLResolvers(
      query        = query,
      mutation     = Map.empty,
      subscription = Map("events" -> subscriptionFn),
    )
    val mountImpl = p.intrinsics(QualifiedName("graphqlMount")).asInstanceOf[NativeImpl]
    mountImpl.eval(ctx, List(Value.Foreign("GraphQLResolvers", res)))

    (capturedWsHandler, sentMessages)

  /** Open a WS connection against the handler; returns a function to push messages in. */
  private def openConnection(
    wsHandler:    Value.NativeFnV,
    sentMessages: scala.collection.mutable.ListBuffer[String],
  ): String => Unit =
    var capturedMsgHandler: Value.NativeFnV = null

    val sendFn: Value.NativeFnV = Value.NativeFnV("ws.send", Computation.pureFn {
      case List(Value.StringV(msg)) => sentMessages += msg; Value.UnitV
      case _                        => Value.UnitV
    })
    val onMsgFn: Value.NativeFnV = Value.NativeFnV("ws.onMessage", Computation.pureFn {
      case List(h: Value.NativeFnV) => capturedMsgHandler = h; Value.UnitV
      case _                         => Value.UnitV
    })
    val mockWs = Value.InstanceV("WebSocket", Map(
      "send"      -> sendFn,
      "onMessage" -> onMsgFn,
    ))

    Computation.run(wsHandler.f(List(mockWs)))

    (text: String) =>
      if capturedMsgHandler != null then
        Computation.run(capturedMsgHandler.f(List(Value.StringV(text))))

  // ── GraphQL.resolvers stores subscription map ─────────────────────────────

  test("GraphQL.resolvers with subscription map stores it in GraphQLResolvers"):
    val p   = new GraphQLInterpreterPlugin()
    val ctx = new NativeContext { def out = nullStream; def err = out }
    val fn: Value.NativeFnV = Value.NativeFnV("sub", Computation.pureFn(_ => Value.ListV(Nil)))
    val result = p.intrinsics(QualifiedName("GraphQL.resolvers")).asInstanceOf[NativeImpl]
      .eval(ctx, List(
        Value.MapV(Map.empty),                                  // query (positional arg 0)
        Value.MapV(Map.empty),                                  // mutation (positional arg 1)
        Value.MapV(Map((Value.StringV("events"): Value) -> (fn: Value))),  // subscription (positional arg 2)
      ))
    result match
      case Value.Foreign("GraphQLResolvers", res: GraphQLResolvers) =>
        assert(res.subscription.contains("events"), "subscription key missing")
        assert(res.subscription("events") eq fn)
      case other => fail(s"expected GraphQLResolvers, got $other")

  test("GraphQL.resolvers without subscription has empty subscription map"):
    val p      = new GraphQLInterpreterPlugin()
    val ctx    = new NativeContext { def out = nullStream; def err = out }
    val result = p.intrinsics(QualifiedName("GraphQL.resolvers")).asInstanceOf[NativeImpl]
      .eval(ctx, List("query", Value.MapV(Map(Value.StringV("hello") -> dummyFn))))
    result match
      case Value.Foreign("GraphQLResolvers", res: GraphQLResolvers) =>
        assert(res.subscription.isEmpty, "expected empty subscription map")
      case other => fail(s"expected GraphQLResolvers, got $other")

  // ── graphqlMount WS route registration ───────────────────────────────────

  test("graphqlMount registers WS route when subscription resolvers present"):
    val (wsHandler, _) = setupWsHandler(dummyFn)
    assert(wsHandler != null, "WS handler should have been registered")

  test("graphqlMount does not register WS route when subscription is empty"):
    val p = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl("type Query { hello: String! }"))

    var wsRouteRegistered = false
    val ctx = new NativeContext:
      def out = nullStream
      def err = out
      override def registerRoute(method: String, path: String, handler: Any): Unit = ()
      override def registerWsRoute(
          path: String, origins: List[String], protocols: List[String],
          maxConn: Int, maxRate: Int, handler: Any): Unit =
        wsRouteRegistered = true

    val res = GraphQLResolvers(
      query = Map("hello" -> dummyFn), mutation = Map.empty, subscription = Map.empty)
    val mountImpl = p.intrinsics(QualifiedName("graphqlMount")).asInstanceOf[NativeImpl]
    mountImpl.eval(ctx, List(Value.Foreign("GraphQLResolvers", res)))
    assert(!wsRouteRegistered, "WS route must not be registered when subscription is empty")

  test("WS route is registered at /graphql/ws with graphql-transport-ws protocol"):
    val p = new GraphQLInterpreterPlugin()
    p.graphqlBlockRunner.foreach(_.registerSdl(SDL_WITH_SUB))

    var capturedPath:     String       = ""
    var capturedProtocols: List[String] = Nil
    val ctx = new NativeContext:
      def out = nullStream
      def err = out
      override def registerRoute(method: String, path: String, handler: Any): Unit = ()
      override def registerWsRoute(
          path: String, origins: List[String], protocols: List[String],
          maxConn: Int, maxRate: Int, handler: Any): Unit =
        capturedPath      = path
        capturedProtocols = protocols

    val res = GraphQLResolvers(
      query = Map.empty, mutation = Map.empty, subscription = Map("events" -> dummyFn))
    val mountImpl = p.intrinsics(QualifiedName("graphqlMount")).asInstanceOf[NativeImpl]
    mountImpl.eval(ctx, List(Value.Foreign("GraphQLResolvers", res)))
    assert(capturedPath == "/graphql/ws", s"expected /graphql/ws, got $capturedPath")
    assert(capturedProtocols.contains("graphql-transport-ws"),
      s"expected graphql-transport-ws in protocols, got $capturedProtocols")

  // ── graphql-transport-ws protocol state machine ───────────────────────────

  test("connection_init message receives connection_ack"):
    val (wsHandler, sent) = setupWsHandler(dummyFn)
    val push = openConnection(wsHandler, sent)
    push("""{"type":"connection_init"}""")
    assert(sent.nonEmpty, "expected at least one message after connection_init")
    val ack = ujson.read(sent.last)
    assert(ack("type").str == "connection_ack", s"expected connection_ack, got: ${sent.last}")

  test("subscribe before connection_init receives connection_not_initialised error"):
    val (wsHandler, sent) = setupWsHandler(dummyFn)
    val push = openConnection(wsHandler, sent)
    push("""{"type":"subscribe","id":"1","payload":{"query":"subscription { events }"}}""")
    assert(sent.nonEmpty, "expected an error message")
    val msg = ujson.read(sent.last)
    assert(msg("type").str == "error", s"expected error type, got: ${sent.last}")

  test("ping message after init receives pong"):
    val (wsHandler, sent) = setupWsHandler(dummyFn)
    val push = openConnection(wsHandler, sent)
    push("""{"type":"connection_init"}""")
    sent.clear()
    push("""{"type":"ping"}""")
    assert(sent.nonEmpty, "expected pong response")
    val pong = ujson.read(sent.last)
    assert(pong("type").str == "pong", s"expected pong, got: ${sent.last}")

  test("pong message is silently ignored"):
    val (wsHandler, sent) = setupWsHandler(dummyFn)
    val push = openConnection(wsHandler, sent)
    push("""{"type":"connection_init"}""")
    sent.clear()
    push("""{"type":"pong"}""")
    assert(sent.isEmpty, "pong should produce no response")

  test("complete message is silently ignored"):
    val (wsHandler, sent) = setupWsHandler(dummyFn)
    val push = openConnection(wsHandler, sent)
    push("""{"type":"connection_init"}""")
    sent.clear()
    push("""{"type":"complete","id":"1"}""")
    assert(sent.isEmpty, "complete should produce no response")

  test("unknown message type is silently ignored"):
    val (wsHandler, sent) = setupWsHandler(dummyFn)
    val push = openConnection(wsHandler, sent)
    push("""{"type":"connection_init"}""")
    sent.clear()
    push("""{"type":"some_unknown_type","id":"1"}""")
    assert(sent.isEmpty, "unknown message type should produce no response")

  test("invalid JSON is silently ignored"):
    val (wsHandler, sent) = setupWsHandler(dummyFn)
    val push = openConnection(wsHandler, sent)
    push("""not valid json at all""")
    assert(sent.isEmpty, "invalid JSON should produce no response")

  test("subscribe with blank query receives error"):
    val (wsHandler, sent) = setupWsHandler(dummyFn)
    val push = openConnection(wsHandler, sent)
    push("""{"type":"connection_init"}""")
    sent.clear()
    push("""{"type":"subscribe","id":"2","payload":{"query":"   "}}""")
    assert(sent.nonEmpty, "expected error message for blank query")
    val msg = ujson.read(sent.last)
    assert(msg("type").str == "error", s"expected error, got: ${sent.last}")
    assert(msg("id").str == "2")

  // ── End-to-end subscription event delivery ────────────────────────────────

  test("subscription query delivers next events then complete"):
    val events = List("event1", "event2")
    val subFn: Value.NativeFnV = Value.NativeFnV("events", Computation.pureFn {
      case List(Value.MapV(_)) =>
        Value.ListV(events.map(Value.StringV(_)))
      case _ => Value.ListV(Nil)
    })

    val (wsHandler, sent) = setupWsHandler(subFn)
    val push = openConnection(wsHandler, sent)

    push("""{"type":"connection_init"}""")
    sent.clear()

    push("""{"type":"subscribe","id":"3","payload":{"query":"subscription { events }"}}""")

    val msgs = sent.toList.map(ujson.read(_))
    val nextMsgs    = msgs.filter(_("type").str == "next")
    val completeMsgs = msgs.filter(_("type").str == "complete")

    assert(nextMsgs.nonEmpty, s"expected next messages, got: $sent")
    assert(nextMsgs.forall(_("id").str == "3"), "id must match subscription id")
    val receivedEvents = nextMsgs.map(_("payload")("data")("events").str)
    assert(receivedEvents == events, s"expected $events but got $receivedEvents")
    assert(completeMsgs.nonEmpty, "expected complete message at end")
    assert(completeMsgs.head("id").str == "3")
