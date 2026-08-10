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
