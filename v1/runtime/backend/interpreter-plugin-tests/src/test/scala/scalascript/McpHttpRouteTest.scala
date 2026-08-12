package scalascript.compiler.plugin.mcp

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.NativeContext
import scalascript.interpreter.{Computation, Value}
import scalascript.mcp.*
import scalascript.plugin.api.PluginContext

/** The MCP HTTP route, driven end to end.
 *
 *  WHY THIS FILE EXISTS. `McpServerCore.handleHttpRequest` validates the
 *  mirrored `Mcp-Method` / `Mcp-Name` / `MCP-Protocol-Version` headers against
 *  the body, and `Mcp.dispatchAuthorized` is what feeds it the real ones. Until
 *  now nothing drove `dispatchAuthorized`: the only reference to it anywhere in
 *  the repository was its own definition. So the validator was proven by unit
 *  tests calling `handleHttpRequest` directly, and the PLUMBING to it was not
 *  proven at all — a check is only as good as what reaches it.
 *
 *  I recorded that as an accepted gap on the grounds that a route test "needs a
 *  `PluginContext` double and none exists". **That was wrong.**
 *  `OAuthHttpInstallerTest` has had the pattern all along, and it is three
 *  lines: `NativeContext` declares only `out` and `err` abstract, everything
 *  else defaults. Checking before writing the code found the earlier claim to
 *  be false, which is the only reason this file exists. */
class McpHttpRouteTest extends AnyFunSuite with Matchers:

  /** Captures the routes a plugin registers so a test can invoke them. */
  private class CapturingCtx extends NativeContext:
    override def out = System.out
    override def err = System.err
    val routes = scala.collection.mutable.LinkedHashMap.empty[(String, String), Any]
    override def registerRoute(method: String, path: String, handler: Any): Unit =
      routes((method, path)) = handler

  private def request(body: String, headers: Map[String, String]): Value.InstanceV =
    Value.InstanceV("Request", Map(
      "body"    -> Value.StringV(body),
      "headers" -> Value.MapV(headers.iterator.map((k, v) =>
                     (Value.StringV(k): Value) -> (Value.StringV(v): Value)).toMap),
      "query"   -> Value.MapV(Map.empty)
    ))

  private def call(handler: Any, req: Value): Value =
    handler.asInstanceOf[Value.NativeFnV].f(List(req)) match
      case Computation.Pure(v) => v
      case other               => fail(s"expected Pure, got $other")

  /** Register the MCP route on a fresh server with one echo tool. */
  private def routed(): (CapturingCtx, McpServerBuilder) =
    val ctx = new CapturingCtx
    val b   = new McpServerBuilder
    b.tool("echo", None, ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    Mcp.installHttpRoute(b, "/mcp", PluginContext.fromNative(ctx))
    (ctx, b)

  private def post(ctx: CapturingCtx, body: String, headers: Map[String, String]): ujson.Value =
    call(ctx.routes(("POST", "/mcp")), request(body, headers)) match
      case Value.InstanceV("Response", f) =>
        val s = f.get("body").collect { case Value.StringV(s) => s }.getOrElse("")
        if s.trim.isEmpty then ujson.Obj() else ujson.read(s.trim)
      case other => fail(s"expected a Response, got $other")

  private def modernBody = ujson.Obj(
    "jsonrpc" -> "2.0", "id" -> 1, "method" -> McpProtocol.Method.ToolsCall,
    "params" -> ujson.Obj(
      "name" -> "echo", "arguments" -> ujson.Obj(),
      "_meta" -> ujson.Obj(
        McpProtocol.MetaKey.ProtocolVersion    -> McpProtocol.ModernProtocolVersion,
        McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj()))).render()

  private def goodHeaders = Map(
    McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
    McpProtocol.Header.Method          -> McpProtocol.Method.ToolsCall,
    McpProtocol.Header.Name            -> "echo")

  // THIS is the case that proves the plumbing, and the negative control is how I
  // found that out. Remove the header forwarding from the route and this fails:
  // headers that MATCH cannot be seen, so the request is rejected instead of
  // served. It is the positive case that discriminates, not the rejection one.
  test("the route reaches the header validator — matching headers are served"):
    val (ctx, _) = routed()
    val js = post(ctx, modernBody, goodHeaders)
    js.obj.keySet should not contain "error"
    js("result")("resultType").str shouldBe "complete"

  test("the route reaches the header validator — a mismatch is rejected through it"):
    // Kept for coverage of each rejection reason, but NOTE WHAT IT CANNOT DO:
    // this case passes whether or not the route forwards headers at all, because
    // a modern request with no headers is rejected for "missing required header"
    // with the same -32020. Measured — it stayed green in the negative control.
    // I had labelled it the case the gap was about; it is not.
    val (ctx, _) = routed()
    for (label, h) <- List(
      "wrong name"    -> (goodHeaders + (McpProtocol.Header.Name -> "not-echo")),
      "wrong method"  -> (goodHeaders + (McpProtocol.Header.Method -> McpProtocol.Method.ToolsList)),
      "wrong version" -> (goodHeaders + (McpProtocol.Header.ProtocolVersion -> "2025-03-26")),
      "no name"       -> (goodHeaders - McpProtocol.Header.Name)) do
      withClue(s"$label: ") {
        post(ctx, modernBody, h)("error")("code").num shouldBe McpProtocol.ErrorCode.HeaderMismatch
      }

  test("the ABSENT-state control: with no headers at all, a modern request is still rejected"):
    // The half that earns its place is the LEGACY one: the same body without
    // `_meta` must still be served, so widening header validation cannot have
    // broken the era we promised not to touch. The modern-with-no-headers half
    // is a boundary case, not a discriminator — see the note above.
    val (ctx, _) = routed()
    post(ctx, modernBody, Map.empty)("error")("code").num shouldBe McpProtocol.ErrorCode.HeaderMismatch
    val legacyBody = ujson.Obj("jsonrpc" -> "2.0", "id" -> 2,
      "method" -> McpProtocol.Method.ToolsCall,
      "params" -> ujson.Obj("name" -> "echo", "arguments" -> ujson.Obj())).render()
    val legacy = post(ctx, legacyBody, Map.empty)
    legacy.obj.keySet should not contain "error"
    legacy("result").obj.keySet should not contain "resultType"

  test("x-mcp-header params are validated through the route too"):
    val ctx = new CapturingCtx
    val b   = new McpServerBuilder
    b.tool("sql", None, ujson.Obj("type" -> "object", "properties" -> ujson.Obj(
      "region" -> ujson.Obj("type" -> "string", "x-mcp-header" -> "Region"))),
      _ => ToolHandlerResult(Nil, isError = false))
    Mcp.installHttpRoute(b, "/mcp", PluginContext.fromNative(ctx))
    val body = ujson.Obj("jsonrpc" -> "2.0", "id" -> 3, "method" -> McpProtocol.Method.ToolsCall,
      "params" -> ujson.Obj("name" -> "sql", "arguments" -> ujson.Obj("region" -> "us-west1"),
        "_meta" -> ujson.Obj(
          McpProtocol.MetaKey.ProtocolVersion    -> McpProtocol.ModernProtocolVersion,
          McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj()))).render()
    val base = Map(
      McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
      McpProtocol.Header.Method          -> McpProtocol.Method.ToolsCall,
      McpProtocol.Header.Name            -> "sql")
    post(ctx, body, base + ("Mcp-Param-Region" -> "us-west1")).obj.keySet should not contain "error"
    post(ctx, body, base + ("Mcp-Param-Region" -> "eu-west1"))("error")("code").num shouldBe
      McpProtocol.ErrorCode.HeaderMismatch
    post(ctx, body, base)("error")("code").num shouldBe McpProtocol.ErrorCode.HeaderMismatch

  // ── P4b-3: subscriptions/listen is answered by the stream itself ────

  /** A ctx whose `invokeCallback` really invokes, so the SSE callback runs. */
  private class InvokingCtx extends CapturingCtx:
    override def invokeCallback(fn: Any, args: List[Any]): Any = fn match
      case Value.NativeFnV(_, f) => f(args.map(_.asInstanceOf[Value])) match
        case Computation.Pure(v) => v
        case other               => other
      case other => fail(s"expected a NativeFnV writer, got $other")

  private def listenBody(id: Int, notifications: ujson.Obj) = ujson.Obj(
    "jsonrpc" -> "2.0", "id" -> id, "method" -> McpProtocol.Method.SubscriptionsListen,
    "params" -> ujson.Obj("notifications" -> notifications,
      "_meta" -> ujson.Obj(
        McpProtocol.MetaKey.ProtocolVersion    -> McpProtocol.ModernProtocolVersion,
        McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj()))).render()

  test("listenRequest recognises only a subscriptions/listen REQUEST"):
    Mcp.listenRequest(listenBody(1, ujson.Obj("toolsListChanged" -> true))).isDefined shouldBe true
    Mcp.listenRequest(modernBody) shouldBe None                 // tools/call
    Mcp.listenRequest("not json")  shouldBe None
    Mcp.listenRequest(JsonRpc.encodeNotification(
      McpProtocol.Method.SubscriptionsListen, ujson.Obj())) shouldBe None       // a notification, not a request

  test("a listen POST is answered with a stream, not a Response"):
    // The revision's shape: the RESPONSE is the stream. A plain Response here
    // would mean we had dispatched it like any other method and closed.
    val ctx = new InvokingCtx
    val b   = new McpServerBuilder
    Mcp.installHttpRoute(b, "/mcp", PluginContext.fromNative(ctx))
    val req = request(listenBody(1, ujson.Obj("toolsListChanged" -> true)),
      Map("Accept" -> "text/event-stream",
          McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
          McpProtocol.Header.Method          -> McpProtocol.Method.SubscriptionsListen))
    call(ctx.routes(("POST", "/mcp")), req) match
      case Value.InstanceV("StreamResponse", f) =>
        f.get("status").collect { case Value.IntV(n) => n } shouldBe Some(200L)
      case other => fail(s"expected a StreamResponse, got $other")

  test("the stream acknowledges first, delivers only what was asked, and ends on close"):
    val ctx = new InvokingCtx
    val b   = new McpServerBuilder
    Mcp.installHttpRoute(b, "/mcp", PluginContext.fromNative(ctx))
    val req = request(listenBody(9, ujson.Obj("toolsListChanged" -> true)),
      Map("Accept" -> "text/event-stream",
          McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
          McpProtocol.Header.Method          -> McpProtocol.Method.SubscriptionsListen))
    val resp = call(ctx.routes(("POST", "/mcp")), req).asInstanceOf[Value.InstanceV]
    val cb   = resp.fields("callback")

    val seen = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val writer = Value.NativeFnV("w", {
      case List(Value.StringV(s)) => seen.add(s); Computation.Pure(Value.UnitV)
      case other                  => fail(s"unexpected writer args: $other")
    })
    // The callback BLOCKS for the life of the stream — that is the point — so it
    // runs on its own thread and the test ends the subscription from outside.
    val t = new Thread(new Runnable { def run(): Unit = { call(cb, writer); () } })
    t.setDaemon(true); t.start()
    eventually(seen.size shouldBe 1)                     // the acknowledgement, first
    seen.peek should include (McpProtocol.Method.SubscriptionsAcknowledged)
    b.notifyToolsListChanged()
    b.notifyPromptsListChanged()                          // not requested — must not appear
    eventually(seen.size shouldBe 2)
    val frames = seen.toArray.map(_.toString).toList
    frames.exists(_.contains(McpProtocol.Method.ToolsListChanged))   shouldBe true
    frames.exists(_.contains(McpProtocol.Method.PromptsListChanged)) shouldBe false
    frames.foreach(_ should startWith ("data: "))
    t.interrupt()

  /** Poll briefly — the stream runs on another thread. */
  private def eventually(check: => Unit): Unit =
    var last: Throwable = null
    var i = 0
    while i < 100 do
      try { check; return } catch case e: Throwable => last = e
      Thread.sleep(20); i += 1
    throw last

  // ── P3b — MRTR through the SHIPPED entry point ─────────────────────────
  //
  // mcp/common tests call handleHttpRequest directly. A .ssc server does not:
  // it reaches it through this route. The layer between them shapes the
  // Response and forwards the headers, and a signal that survives one does not
  // automatically survive the other -- that is what this file was created to
  // catch, and MRTR is a second occasion for it.

  private def askRoute(): CapturingCtx =
    val ctx = new CapturingCtx
    val b   = new McpServerBuilder
    b.tool("ask", None, ujson.Obj(), _ =>
      b.elicit("confirm?", ujson.Obj("type" -> "object"))
      ToolHandlerResult(Nil, isError = false))
    Mcp.installHttpRoute(b, "/mcp", PluginContext.fromNative(ctx))
    ctx

  private def askBody(extra: (String, ujson.Value)*) = ujson.Obj(
    "jsonrpc" -> "2.0", "id" -> 9, "method" -> McpProtocol.Method.ToolsCall,
    "params" -> ujson.Obj.from(Seq[(String, ujson.Value)](
      "name" -> "ask", "arguments" -> ujson.Obj(),
      "_meta" -> ujson.Obj(
        McpProtocol.MetaKey.ProtocolVersion    -> McpProtocol.ModernProtocolVersion,
        McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj())) ++ extra)).render()

  private def askHeaders = Map(
    McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
    McpProtocol.Header.Method          -> McpProtocol.Method.ToolsCall,
    McpProtocol.Header.Name            -> "ask")

  test("MRTR survives the route: an unanswered elicit comes back as input-required"):
    val js = post(askRoute(), askBody(), askHeaders)
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeInputRequired
    js("result")("inputRequests").obj.keySet shouldBe Set("elicit-1")

  test("MRTR survives the route: the answered retry completes through it"):
    val js = post(askRoute(), askBody("inputResponses" -> ujson.Obj(
      "elicit-1" -> ujson.Obj("action" -> "accept", "content" -> ujson.Obj()))), askHeaders)
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    js("result").obj.keySet should not contain "inputRequests"

