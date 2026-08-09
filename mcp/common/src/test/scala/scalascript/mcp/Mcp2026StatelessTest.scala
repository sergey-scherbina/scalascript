package scalascript.mcp

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** MCP 2026-07-28 P1 — the stateless era, served alongside the legacy one.
 *
 *  The revision deletes the `initialize` handshake and moves protocol
 *  version, client identity and client capabilities into the `_meta` of
 *  every request.  We serve both eras off one dispatcher, keyed on whether
 *  the request carries `io.modelcontextprotocol/protocolVersion`.
 *
 *  The load-bearing half of this suite is the LEGACY half.  A dual-era
 *  change is only safe if the old era is untouched, and "the old tests
 *  still pass" does not prove that — they assert fields, not frames.  So
 *  the legacy cases below freeze whole wire frames byte for byte.  Revert
 *  `stampFrame` to the identity function and the modern cases fail; make
 *  any builder emit `resultType` unconditionally and the legacy cases fail.
 *
 *  Design: `specs/mcp-2026-07-28.md`. */
class Mcp2026StatelessTest extends AnyFunSuite with Matchers:

  /** A well-formed modern `_meta`: version we support + the required
   *  client capabilities. */
  private def modernParams(fields: (String, ujson.Value)*): ujson.Obj =
    val p = ujson.Obj("_meta" -> ujson.Obj(
      McpProtocol.MetaKey.ProtocolVersion    -> McpProtocol.ModernProtocolVersion,
      McpProtocol.MetaKey.ClientInfo         -> ujson.Obj("name" -> "test-client", "version" -> "0.1.0"),
      McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj()
    ))
    fields.foreach((k, v) => p(k) = v)
    p

  private def twoToolServer(): McpServerBuilder =
    val b = new McpServerBuilder
    b.tool("alpha", Some("first"), ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    b.tool("beta",  None,          ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    b

  // ── LEGACY era — frozen frames, byte for byte ───────────────────────

  test("legacy: initialize frame is unchanged by the 2026-07-28 work"):
    val reply = McpServerCore.dispatch(
      new McpServerBuilder, McpProtocol.Method.Initialize, ujson.Obj(), ujson.Num(1),
      "srv", "9.9.9")
    reply shouldBe """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{"listChanged":true},"resources":{"subscribe":true,"listChanged":true},"prompts":{"listChanged":true},"logging":{},"completions":{}},"serverInfo":{"name":"srv","version":"9.9.9"}}}""" + "\n"

  test("legacy: tools/list frame is unchanged by the 2026-07-28 work"):
    val reply = McpServerCore.dispatch(
      twoToolServer(), McpProtocol.Method.ToolsList, ujson.Obj(), ujson.Num(2), "srv", "9.9.9")
    reply shouldBe """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"alpha","description":"first","inputSchema":{}},{"name":"beta","inputSchema":{}}]}}""" + "\n"

  test("legacy: no result carries resultType or a serverInfo _meta"):
    val b = twoToolServer()
    val methods = List(
      McpProtocol.Method.Initialize -> ujson.Obj(),
      McpProtocol.Method.ToolsList  -> ujson.Obj(),
      McpProtocol.Method.Ping       -> ujson.Obj(),
      McpProtocol.Method.ToolsCall  -> ujson.Obj("name" -> "alpha", "arguments" -> ujson.Obj())
    )
    for (m, params) <- methods do
      val result = ujson.read(McpServerCore.dispatch(b, m, params, ujson.Num(3), "srv", "9.9.9").trim)("result")
      withClue(s"$m: ") {
        result.obj.keySet should not contain "resultType"
        result.obj.keySet should not contain "_meta"
      }

  test("legacy: a malformed _meta is read as legacy, not as a broken modern request"):
    // No protocolVersion ⇒ legacy, whatever else the _meta contains.  A
    // dual-era server must not reject a client for omitting fields its own
    // revision never defined.
    for meta <- List[ujson.Value](ujson.Str("nonsense"), ujson.Arr(), ujson.Obj("unrelated" -> 1)) do
      val params = ujson.Obj("_meta" -> meta)
      val result = ujson.read(McpServerCore.dispatch(
        new McpServerBuilder, McpProtocol.Method.Ping, params, ujson.Num(4)).trim)("result")
      result.obj.keySet should not contain "resultType"

  // ── MODERN era — resultType + serverInfo ────────────────────────────

  test("modern: results are stamped with resultType and serverInfo"):
    val b = twoToolServer()
    for m <- List(McpProtocol.Method.ToolsList, McpProtocol.Method.ResourcesList, McpProtocol.Method.PromptsList) do
      val result = ujson.read(McpServerCore.dispatch(
        b, m, modernParams(), ujson.Num(5), "srv", "9.9.9").trim)("result")
      withClue(s"$m: ") {
        result("resultType").str shouldBe "complete"
        result("_meta")(McpProtocol.MetaKey.ServerInfo)("name").str shouldBe "srv"
        result("_meta")(McpProtocol.MetaKey.ServerInfo)("version").str shouldBe "9.9.9"
      }

  test("modern: tools/call keeps its payload alongside the stamp"):
    val b = new McpServerBuilder
    b.tool("echo", None, ujson.Obj(), args =>
      ToolHandlerResult(List(McpProtocol.textContent(args.getOrElse("msg", "").toString)), isError = false))
    val params = modernParams("name" -> ujson.Str("echo"), "arguments" -> ujson.Obj("msg" -> "hi"))
    val result = ujson.read(McpServerCore.dispatch(
      b, McpProtocol.Method.ToolsCall, params, ujson.Num(6)).trim)("result")
    result("resultType").str shouldBe "complete"
    result("content")(0)("text").str shouldBe "hi"
    result("isError").bool shouldBe false

  test("modern: error frames are not stamped — an error has no resultType"):
    val params = modernParams("name" -> ujson.Str("missing"), "arguments" -> ujson.Obj())
    val js = ujson.read(McpServerCore.dispatch(
      new McpServerBuilder, McpProtocol.Method.ToolsCall, params, ujson.Num(7)).trim)
    js.obj.keySet should not contain "result"
    js("error")("code").num shouldBe JsonRpc.ErrorCode.MethodNotFound

  test("stampComplete merges into an existing _meta rather than replacing it"):
    val stamped = McpProtocol.stampComplete(
      ujson.Obj("x" -> 1, "_meta" -> ujson.Obj("com.example/keep" -> "yes")), "srv", "9.9.9")
    stamped("_meta")("com.example/keep").str shouldBe "yes"
    stamped("_meta")(McpProtocol.MetaKey.ServerInfo)("name").str shouldBe "srv"
    stamped("resultType").str shouldBe "complete"

  // ── server/discover ─────────────────────────────────────────────────

  test("server/discover answers in both eras and advertises 2026-07-28"):
    for params <- List(ujson.Obj(), modernParams()) do
      val result = ujson.read(McpServerCore.dispatch(
        twoToolServer(), McpProtocol.Method.ServerDiscover, params, ujson.Num(8), "srv", "9.9.9").trim)("result")
      result("resultType").str shouldBe "complete"
      result("supportedVersions").arr.map(_.str).toList should contain (McpProtocol.ModernProtocolVersion)
      result("supportedVersions").arr.head.str shouldBe McpProtocol.ModernProtocolVersion
      result("capabilities").obj.keySet should contain allOf ("tools", "resources", "prompts")
      result("_meta")(McpProtocol.MetaKey.ServerInfo)("name").str shouldBe "srv"

  test("server/discover capabilities agree with what initialize advertises"):
    // One source of truth: the two must never disagree about what this
    // server can do, or a client that probes gets a different answer from
    // one that handshakes.
    val discover = ujson.read(McpServerCore.dispatch(
      new McpServerBuilder, McpProtocol.Method.ServerDiscover, ujson.Obj(), ujson.Num(9), "srv", "1.0")
      .trim)("result")("capabilities")
    val init = ujson.read(McpServerCore.dispatch(
      new McpServerBuilder, McpProtocol.Method.Initialize, ujson.Obj(), ujson.Num(10), "srv", "1.0")
      .trim)("result")("capabilities")
    discover shouldBe init

  // ── modern validation ───────────────────────────────────────────────

  test("modern: an unsupported protocol version returns -32022 with the intersection"):
    val params = ujson.Obj("_meta" -> ujson.Obj(
      McpProtocol.MetaKey.ProtocolVersion    -> "1900-01-01",
      McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj()
    ))
    val js = ujson.read(McpServerCore.dispatch(
      new McpServerBuilder, McpProtocol.Method.ToolsList, params, ujson.Num(11)).trim)
    js("error")("code").num shouldBe McpProtocol.ErrorCode.UnsupportedProtocolVersion
    js("error")("data")("requested").str shouldBe "1900-01-01"
    js("error")("data")("supported").arr.map(_.str).toList shouldBe McpProtocol.SupportedProtocolVersions

  test("modern: the version check runs before the method, so an unknown method still reports -32022"):
    val params = ujson.Obj("_meta" -> ujson.Obj(
      McpProtocol.MetaKey.ProtocolVersion    -> "1900-01-01",
      McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj()
    ))
    ujson.read(McpServerCore.dispatch(new McpServerBuilder, "no/such/method", params, ujson.Num(12)).trim)(
      "error")("code").num shouldBe McpProtocol.ErrorCode.UnsupportedProtocolVersion

  test("modern: a request without the required clientCapabilities is InvalidParams"):
    val params = ujson.Obj("_meta" -> ujson.Obj(
      McpProtocol.MetaKey.ProtocolVersion -> McpProtocol.ModernProtocolVersion
    ))
    val js = ujson.read(McpServerCore.dispatch(
      new McpServerBuilder, McpProtocol.Method.ToolsList, params, ujson.Num(13)).trim)
    js("error")("code").num shouldBe JsonRpc.ErrorCode.InvalidParams
    js("error")("message").str should include (McpProtocol.MetaKey.ClientCapabilities)

  test("modern: an older-but-supported revision is accepted, not rejected"):
    val params = ujson.Obj("_meta" -> ujson.Obj(
      McpProtocol.MetaKey.ProtocolVersion    -> "2025-03-26",
      McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj()
    ))
    val js = ujson.read(McpServerCore.dispatch(
      twoToolServer(), McpProtocol.Method.ToolsList, params, ujson.Num(14)).trim)
    js.obj.keySet should not contain "error"
    js("result")("tools").arr should have size 2

  test("isModernVersion sorts revisions by date"):
    McpProtocol.isModernVersion("2026-07-28") shouldBe true
    McpProtocol.isModernVersion("2027-01-01") shouldBe true
    McpProtocol.isModernVersion("2025-11-25") shouldBe false
    McpProtocol.isModernVersion(McpProtocol.ProtocolVersion) shouldBe false

  // ── P1b: the legacy handshake actually negotiates ───────────────────

  test("legacy initialize: a supported requested revision is echoed back"):
    for asked <- McpProtocol.LegacyProtocolVersions do
      val params = ujson.Obj("protocolVersion" -> asked)
      val reply = ujson.read(McpServerCore.dispatch(
        new McpServerBuilder, McpProtocol.Method.Initialize, params, ujson.Num(20)).trim)
      withClue(s"asked $asked: ") { reply("result")("protocolVersion").str shouldBe asked }

  test("legacy initialize: an unsupported requested revision falls back to our best"):
    for asked <- List("1900-01-01", "2025-06-18", "2025-11-25", "") do
      val params = ujson.Obj("protocolVersion" -> asked)
      val reply = ujson.read(McpServerCore.dispatch(
        new McpServerBuilder, McpProtocol.Method.Initialize, params, ujson.Num(21)).trim)
      withClue(s"asked $asked: ") {
        reply("result")("protocolVersion").str shouldBe McpProtocol.ProtocolVersion
      }

  test("legacy initialize: no requested revision at all still answers our best"):
    val reply = ujson.read(McpServerCore.dispatch(
      new McpServerBuilder, McpProtocol.Method.Initialize, ujson.Obj(), ujson.Num(22)).trim)
    reply("result")("protocolVersion").str shouldBe McpProtocol.ProtocolVersion

  test("the handshake can never settle on the modern revision"):
    // 2026-07-28 DELETED `initialize`. Echoing it back to a client that just
    // sent one would agree to speak a revision in which that message does not
    // exist — so the modern version must be unreachable through this path,
    // including when the client explicitly asks for it.
    McpProtocol.LegacyProtocolVersions should not contain McpProtocol.ModernProtocolVersion
    val params = ujson.Obj("protocolVersion" -> McpProtocol.ModernProtocolVersion)
    val reply = ujson.read(McpServerCore.dispatch(
      new McpServerBuilder, McpProtocol.Method.Initialize, params, ujson.Num(23)).trim)
    reply("result")("protocolVersion").str shouldBe McpProtocol.ProtocolVersion

  test("we advertise only revisions we implement"):
    // 2025-06-18 is deliberately absent: its `MCP-Protocol-Version` header and
    // `completion/complete` `context` field are not implemented (measured 0
    // occurrences, 2026-08-09). Advertising it would trade a four-revision
    // under-claim for an over-claim. P2 closes both, then this changes.
    McpProtocol.SupportedProtocolVersions should not contain "2025-06-18"
    McpProtocol.ProtocolVersion shouldBe "2025-03-26"
    McpProtocol.SupportedProtocolVersions.head shouldBe McpProtocol.ModernProtocolVersion
