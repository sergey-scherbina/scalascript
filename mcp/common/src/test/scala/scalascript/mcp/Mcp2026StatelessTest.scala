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
    // The version moved on 2026-08-13 and NOTHING ELSE in this frame did, which
    // is what the freeze is here to show: the legacy shape survived the raise.
    reply shouldBe """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":true},"resources":{"subscribe":true,"listChanged":true},"prompts":{"listChanged":true},"logging":{},"completions":{}},"serverInfo":{"name":"srv","version":"9.9.9"}}}""" + "\n"

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
    // InvalidParams since P1c — the tool name did not resolve, the method exists.
    // What this case is actually about is the absence of result/resultType above.
    js("error")("code").num shouldBe JsonRpc.ErrorCode.InvalidParams

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
    // Raised on 2026-08-13 once the census in specs 13 had no missing row:
    // `context` on completion/complete, the client's MCP-Protocol-Version
    // header, and RFC 8707 Resource Indicators all landed; batching needed
    // nothing, since the revision removes it and we never had it.
    McpProtocol.ProtocolVersion shouldBe "2025-06-18"
    McpProtocol.SupportedProtocolVersions.head shouldBe McpProtocol.ModernProtocolVersion

  test("raising what we PREFER did not drop what we ACCEPT"):
    // The failure this guards is quiet: a client pinned to the older revision
    // would start being answered with one it never asked for.
    McpProtocol.SupportedProtocolVersions should contain ("2025-03-26")
    McpProtocol.SupportedProtocolVersions should contain ("2024-11-05")
    McpProtocol.negotiateLegacyVersion(Some("2025-03-26")) shouldBe "2025-03-26"
    McpProtocol.negotiateLegacyVersion(Some("2024-11-05")) shouldBe "2024-11-05"
    // and a client asking for something we do not speak gets our best legacy
    // answer, not silence
    McpProtocol.negotiateLegacyVersion(Some("1999-01-01")) shouldBe McpProtocol.ProtocolVersion

  test("the raised version is still a LEGACY one — it must not enter the modern set"):
    // `isModernVersion` is a date compare, so this is the case that would
    // break if the raise ever crossed the modern boundary by accident.
    McpProtocol.isModernVersion(McpProtocol.ProtocolVersion) shouldBe false
    McpProtocol.LegacyProtocolVersions should contain (McpProtocol.ProtocolVersion)
    McpProtocol.LegacyProtocolVersions should not contain McpProtocol.ModernProtocolVersion

  // ── P2: CacheableResult — ttlMs + cacheScope on exactly six operations ──

  private def modernResult(b: McpServerBuilder, method: String, params: ujson.Obj = modernParams()) =
    ujson.read(McpServerCore.dispatch(b, method, params, ujson.Num(30), "srv", "9.9.9").trim)("result")

  test("the six cacheable operations carry ttlMs and cacheScope"):
    val b = twoToolServer()
    b.resource("mem://a", None, None, u => ResourceHandlerResult(u, Nil))
    for m <- McpProtocol.CacheableMethods.toList.sorted do
      val params = if m == McpProtocol.Method.ResourcesRead then modernParams("uri" -> ujson.Str("mem://a"))
                   else modernParams()
      val r = modernResult(b, m, params)
      withClue(s"$m: ") {
        r.obj.keySet should contain ("ttlMs")
        r.obj.keySet should contain ("cacheScope")
        r("ttlMs").num should be >= 0.0            // spec: servers MUST provide ttlMs >= 0
        r("cacheScope").str should (be ("public") or be ("private"))
      }

  test("a NON-cacheable operation carries no hints — the spec names exactly six"):
    // Over-hinting is not harmlessly generous: a client told a result is
    // cacheable will cache it, and tools/call is not cacheable at any TTL.
    val b = new McpServerBuilder
    b.tool("echo", None, ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    val r = modernResult(b, McpProtocol.Method.ToolsCall,
      modernParams("name" -> ujson.Str("echo"), "arguments" -> ujson.Obj()))
    r.obj.keySet should not contain "ttlMs"
    r.obj.keySet should not contain "cacheScope"
    McpProtocol.CacheableMethods should have size 6

  test("cacheScope follows whether the server authenticates, not a guess"):
    val open = twoToolServer()
    modernResult(open, McpProtocol.Method.ToolsList)("cacheScope").str shouldBe "public"
    val guarded = twoToolServer()
    guarded.setTokenValidator(Some(_ => McpAuth.AuthResult.Invalid("invalid_token", "no")))
    modernResult(guarded, McpProtocol.Method.ToolsList)("cacheScope").str shouldBe "private"

  test("legacy results carry no cache hints at all"):
    // Same reason the legacy path carries no resultType: these fields are part
    // of a revision the legacy client never agreed to speak.
    val b = twoToolServer()
    val r = ujson.read(McpServerCore.dispatch(
      b, McpProtocol.Method.ToolsList, ujson.Obj(), ujson.Num(31)).trim)("result")
    r.obj.keySet should not contain "ttlMs"
    r.obj.keySet should not contain "cacheScope"

  test("resources/read defaults to immediately stale, lists do not"):
    McpProtocol.DefaultReadTtlMs shouldBe 0L
    McpProtocol.DefaultListTtlMs should be > 0L
    McpProtocol.cacheHintsFor(McpProtocol.Method.ResourcesRead, authenticated = false).get.ttlMs shouldBe 0L
    McpProtocol.cacheHintsFor(McpProtocol.Method.ToolsList, authenticated = false).get.ttlMs shouldBe
      McpProtocol.DefaultListTtlMs
    McpProtocol.cacheHintsFor("tools/call", authenticated = false) shouldBe None

  test("CacheHints refuses a negative ttl and an unknown scope"):
    an [IllegalArgumentException] should be thrownBy McpProtocol.CacheHints(-1, "public")
    an [IllegalArgumentException] should be thrownBy McpProtocol.CacheHints(0, "shared")

  // ── P2b: mirrored request headers must agree with the body ──────────

  private def modernHeaders(extra: (String, String)*): Map[String, String] =
    Map(McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
        McpProtocol.Header.Method          -> McpProtocol.Method.ToolsCall,
        McpProtocol.Header.Name            -> "echo") ++ extra

  private def callBody = ujson.Obj(
    "jsonrpc" -> "2.0", "id" -> 40, "method" -> McpProtocol.Method.ToolsCall,
    "params"  -> modernParams("name" -> ujson.Str("echo"), "arguments" -> ujson.Obj())).render()

  private def echoServer(): McpServerBuilder =
    val b = new McpServerBuilder
    b.tool("echo", None, ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    b

  test("headers that agree with the body are accepted"):
    val js = ujson.read(McpServerCore.handleHttpRequest(
      echoServer(), callBody, "srv", "9.9.9", modernHeaders()).trim)
    js.obj.keySet should not contain "error"
    js("result")("resultType").str shouldBe "complete"

  test("every disagreeing or missing standard header is -32020"):
    val cases = List(
      "wrong version"  -> modernHeaders(McpProtocol.Header.ProtocolVersion -> "2025-03-26"),
      "wrong method"   -> modernHeaders(McpProtocol.Header.Method -> "tools/list"),
      "wrong name"     -> modernHeaders(McpProtocol.Header.Name -> "not-echo"),
      "no version"     -> modernHeaders().removed(McpProtocol.Header.ProtocolVersion),
      "no method"      -> modernHeaders().removed(McpProtocol.Header.Method),
      "no name"        -> modernHeaders().removed(McpProtocol.Header.Name)
    )
    for (label, h) <- cases do
      val js = ujson.read(McpServerCore.handleHttpRequest(echoServer(), callBody, "srv", "9.9.9", h).trim)
      withClue(s"$label: ") {
        js("error")("code").num shouldBe McpProtocol.ErrorCode.HeaderMismatch
        js("error")("message").str should startWith ("Header mismatch")
      }

  test("header NAMES compare case-insensitively, values do not"):
    // RFC 9110: field names are case-insensitive. Values — method names here — are not.
    val shouted = modernHeaders().map((k, v) => k.toUpperCase -> v)
    ujson.read(McpServerCore.handleHttpRequest(echoServer(), callBody, "srv", "9.9.9", shouted).trim)
      .obj.keySet should not contain "error"
    val wrongCaseValue = modernHeaders(McpProtocol.Header.Method -> "TOOLS/CALL")
    ujson.read(McpServerCore.handleHttpRequest(echoServer(), callBody, "srv", "9.9.9", wrongCaseValue).trim)(
      "error")("code").num shouldBe McpProtocol.ErrorCode.HeaderMismatch

  test("a LEGACY request is never rejected for headers its revision never had"):
    // The whole dual-era promise in one case: no _meta, no headers, still served.
    val legacyBody = ujson.Obj("jsonrpc" -> "2.0", "id" -> 41,
      "method" -> McpProtocol.Method.ToolsCall,
      "params" -> ujson.Obj("name" -> "echo", "arguments" -> ujson.Obj())).render()
    val js = ujson.read(McpServerCore.handleHttpRequest(echoServer(), legacyBody, "srv", "9.9.9", Map.empty).trim)
    js.obj.keySet should not contain "error"
    js("result").obj.keySet should not contain "resultType"

  test("the base64 sentinel round-trips, and is decoded before comparison"):
    for v <- List("us-west1", "Hello, 世界", " padded ", "line1\nline2", "=?base64?literal?=") do
      withClue(s"[$v]: ") { McpProtocol.decodeHeaderValue(McpProtocol.encodeHeaderValue(v)) shouldBe v }
    McpProtocol.encodeHeaderValue("us-west1") shouldBe "us-west1"          // plain ASCII rides as-is
    McpProtocol.encodeHeaderValue("Hello, 世界") should startWith ("=?base64?")
    // A value that merely LOOKS like the sentinel must still be encoded, or the
    // server would decode something the client never encoded.
    McpProtocol.encodeHeaderValue("=?base64?literal?=") should not be "=?base64?literal?="

  test("an encoded Mcp-Name is compared against the body decoded"):
    val b = new McpServerBuilder
    b.tool("Hello, 世界", None, ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    val body = ujson.Obj("jsonrpc" -> "2.0", "id" -> 42, "method" -> McpProtocol.Method.ToolsCall,
      "params" -> modernParams("name" -> ujson.Str("Hello, 世界"), "arguments" -> ujson.Obj())).render()
    val h = Map(McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
                McpProtocol.Header.Method          -> McpProtocol.Method.ToolsCall,
                McpProtocol.Header.Name            -> McpProtocol.encodeHeaderValue("Hello, 世界"))
    ujson.read(McpServerCore.handleHttpRequest(b, body, "srv", "9.9.9", h).trim)
      .obj.keySet should not contain "error"

  test("only the three name-mirroring methods require Mcp-Name"):
    McpProtocol.NameHeaderSource.keySet shouldBe
      Set(McpProtocol.Method.ToolsCall, McpProtocol.Method.PromptsGet, McpProtocol.Method.ResourcesRead)
    val listBody = ujson.Obj("jsonrpc" -> "2.0", "id" -> 43,
      "method" -> McpProtocol.Method.ToolsList, "params" -> modernParams()).render()
    val h = Map(McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
                McpProtocol.Header.Method          -> McpProtocol.Method.ToolsList)
    ujson.read(McpServerCore.handleHttpRequest(echoServer(), listBody, "srv", "9.9.9", h).trim)
      .obj.keySet should not contain "error"

  // ── P2c: x-mcp-header → Mcp-Param-{Name} ────────────────────────────

  /** The spec's own worked example: one annotated param, one plain one. */
  private val sqlSchema = ujson.Obj(
    "type" -> "object",
    "properties" -> ujson.Obj(
      "region" -> ujson.Obj("type" -> "string", "x-mcp-header" -> "Region"),
      "query"  -> ujson.Obj("type" -> "string")),
    "required" -> ujson.Arr("region", "query"))

  private def sqlServer(): McpServerBuilder =
    val b = new McpServerBuilder
    b.tool("execute_sql", None, sqlSchema, _ => ToolHandlerResult(Nil, isError = false))
    b

  private def sqlCall(args: ujson.Obj) = ujson.Obj(
    "jsonrpc" -> "2.0", "id" -> 50, "method" -> McpProtocol.Method.ToolsCall,
    "params" -> modernParams("name" -> ujson.Str("execute_sql"), "arguments" -> args)).render()

  private def sqlHeaders(extra: (String, String)*) =
    Map(McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
        McpProtocol.Header.Method          -> McpProtocol.Method.ToolsCall,
        McpProtocol.Header.Name            -> "execute_sql") ++ extra

  private def sqlPost(args: ujson.Obj, h: Map[String, String]) =
    ujson.read(McpServerCore.handleHttpRequest(sqlServer(), sqlCall(args), "srv", "9.9.9", h).trim)

  test("x-mcp-header: a matching Mcp-Param header is accepted"):
    val js = sqlPost(ujson.Obj("region" -> "us-west1", "query" -> "SELECT 1"),
                     sqlHeaders("Mcp-Param-Region" -> "us-west1"))
    js.obj.keySet should not contain "error"

  test("x-mcp-header: the four presence/absence combinations behave as the spec says"):
    val full = ujson.Obj("region" -> "us-west1", "query" -> "SELECT 1")
    val bare = ujson.Obj("query" -> "SELECT 1")
    // value present + header present + equal -> ok (above). The three failures:
    sqlPost(full, sqlHeaders())("error")("code").num shouldBe
      McpProtocol.ErrorCode.HeaderMismatch                                   // omitted while present
    sqlPost(full, sqlHeaders("Mcp-Param-Region" -> "eu-west1"))("error")("code").num shouldBe
      McpProtocol.ErrorCode.HeaderMismatch                                   // disagrees
    sqlPost(bare, sqlHeaders("Mcp-Param-Region" -> "us-west1"))("error")("code").num shouldBe
      McpProtocol.ErrorCode.HeaderMismatch                                   // sent while absent
    // argument absent AND header absent -> nothing to disagree about
    sqlPost(bare, sqlHeaders()).obj.keySet should not contain "error"

  test("x-mcp-header: a null argument is treated as absent, not as the string null"):
    sqlPost(ujson.Obj("region" -> ujson.Null, "query" -> "SELECT 1"), sqlHeaders())
      .obj.keySet should not contain "error"

  test("x-mcp-header: a non-ASCII value is compared after decoding the sentinel"):
    val js = sqlPost(ujson.Obj("region" -> "西部", "query" -> "SELECT 1"),
      sqlHeaders("Mcp-Param-Region" -> McpProtocol.encodeHeaderValue("西部")))
    js.obj.keySet should not contain "error"

  test("x-mcp-header: integers are decimal and booleans lowercase"):
    val b = new McpServerBuilder
    b.tool("t", None, ujson.Obj("type" -> "object", "properties" -> ujson.Obj(
      "n" -> ujson.Obj("type" -> "integer", "x-mcp-header" -> "N"),
      "f" -> ujson.Obj("type" -> "boolean", "x-mcp-header" -> "F"))),
      _ => ToolHandlerResult(Nil, isError = false))
    val body = ujson.Obj("jsonrpc" -> "2.0", "id" -> 51, "method" -> McpProtocol.Method.ToolsCall,
      "params" -> modernParams("name" -> ujson.Str("t"),
        "arguments" -> ujson.Obj("n" -> 42, "f" -> true))).render()
    val h = Map(McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
                McpProtocol.Header.Method -> McpProtocol.Method.ToolsCall,
                McpProtocol.Header.Name -> "t", "Mcp-Param-N" -> "42", "Mcp-Param-F" -> "true")
    ujson.read(McpServerCore.handleHttpRequest(b, body, "srv", "9.9.9", h).trim)
      .obj.keySet should not contain "error"

  test("x-mcp-header: nested properties are reachable, arrays and oneOf are not"):
    val nested = ujson.Obj("type" -> "object", "properties" -> ujson.Obj(
      "outer" -> ujson.Obj("type" -> "object", "properties" -> ujson.Obj(
        "inner" -> ujson.Obj("type" -> "string", "x-mcp-header" -> "Inner")))))
    McpProtocol.xMcpHeaderParams(nested) shouldBe
      Right(List(McpProtocol.ParamHeader("Inner", List("outer", "inner"))))
    // Under `items` the annotation is simply not collected — there is no single
    // path to read it from, which is the reason the spec forbids it.
    val inArray = ujson.Obj("type" -> "object", "properties" -> ujson.Obj(
      "xs" -> ujson.Obj("type" -> "array", "items" -> ujson.Obj("type" -> "object",
        "properties" -> ujson.Obj("k" -> ujson.Obj("type" -> "string", "x-mcp-header" -> "K"))))))
    McpProtocol.xMcpHeaderParams(inArray) shouldBe Right(Nil)

  test("x-mcp-header: the annotation constraints are enforced, each for its own reason"):
    def one(prop: ujson.Obj) =
      McpProtocol.xMcpHeaderParams(ujson.Obj("type" -> "object", "properties" -> ujson.Obj("p" -> prop)))
    one(ujson.Obj("type" -> "number",  "x-mcp-header" -> "N")).isLeft shouldBe true   // no canonical text
    one(ujson.Obj("type" -> "string",  "x-mcp-header" -> "")).isLeft shouldBe true    // empty
    one(ujson.Obj("type" -> "string",  "x-mcp-header" -> "bad name")).isLeft shouldBe true  // not a token
    one(ujson.Obj("type" -> "string",  "x-mcp-header" -> "a\rb")).isLeft shouldBe true      // CR
    one(ujson.Obj("type" -> "object",  "x-mcp-header" -> "O")).isLeft shouldBe true   // not primitive
    val dup = ujson.Obj("type" -> "object", "properties" -> ujson.Obj(
      "a" -> ujson.Obj("type" -> "string", "x-mcp-header" -> "Reg"),
      "b" -> ujson.Obj("type" -> "string", "x-mcp-header" -> "REG")))
    McpProtocol.xMcpHeaderParams(dup).isLeft shouldBe true                            // case-insensitive clash
    one(ujson.Obj("type" -> "string",  "x-mcp-header" -> "Ok")).isRight shouldBe true // control

  test("x-mcp-header: an invalid annotation makes the CALL fail, not silently pass"):
    val b = new McpServerBuilder
    b.tool("bad", None, ujson.Obj("type" -> "object", "properties" -> ujson.Obj(
      "p" -> ujson.Obj("type" -> "number", "x-mcp-header" -> "P"))),
      _ => ToolHandlerResult(Nil, isError = false))
    val body = ujson.Obj("jsonrpc" -> "2.0", "id" -> 52, "method" -> McpProtocol.Method.ToolsCall,
      "params" -> modernParams("name" -> ujson.Str("bad"), "arguments" -> ujson.Obj("p" -> 1))).render()
    val h = Map(McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
                McpProtocol.Header.Method -> McpProtocol.Method.ToolsCall, McpProtocol.Header.Name -> "bad")
    ujson.read(McpServerCore.handleHttpRequest(b, body, "srv", "9.9.9", h).trim)(
      "error")("message").str should include ("inputSchema is invalid")

  test("x-mcp-header: a legacy call is untouched by any of this"):
    val legacy = ujson.Obj("jsonrpc" -> "2.0", "id" -> 53, "method" -> McpProtocol.Method.ToolsCall,
      "params" -> ujson.Obj("name" -> "execute_sql",
        "arguments" -> ujson.Obj("region" -> "us-west1", "query" -> "SELECT 1"))).render()
    ujson.read(McpServerCore.handleHttpRequest(sqlServer(), legacy, "srv", "9.9.9", Map.empty).trim)
      .obj.keySet should not contain "error"

  // ── P3a: MRTR wire types and the modern-only removals ───────────────

  private val elicitReq = McpProtocol.InputRequest("elicitation/create",
    ujson.Obj("mode" -> "form", "message" -> "Your GitHub username",
              "requestedSchema" -> ujson.Obj("type" -> "object")))

  test("InputRequiredResult carries resultType, its requests and its state"):
    val r = McpProtocol.inputRequiredResult(Map("github_login" -> elicitReq), Some("opaque-blob"))
    r("resultType").str shouldBe McpProtocol.ResultTypeInputRequired
    r("inputRequests")("github_login")("method").str shouldBe "elicitation/create"
    r("inputRequests")("github_login")("params")("message").str shouldBe "Your GitHub username"
    r("requestState").str shouldBe "opaque-blob"

  test("either field alone is enough, but neither is not"):
    // The spec requires at least one. A result asking for nothing while saying
    // input is required leaves a conforming client retrying forever.
    McpProtocol.inputRequiredResult(Map("k" -> elicitReq)).obj.keySet should not contain "requestState"
    McpProtocol.inputRequiredResult(requestState = Some("s")).obj.keySet should not contain "inputRequests"
    an [IllegalArgumentException] should be thrownBy McpProtocol.inputRequiredResult()

  test("only three client requests may be answered with one"):
    McpProtocol.MrtrCapableMethods shouldBe
      Set(McpProtocol.Method.PromptsGet, McpProtocol.Method.ResourcesRead, McpProtocol.Method.ToolsCall)
    McpProtocol.MrtrCapableMethods should not contain McpProtocol.Method.ToolsList

  test("inputResponses and requestState are read off a retry, defensively"):
    val retry = ujson.Obj(
      "inputResponses" -> ujson.Obj("github_login" -> ujson.Obj("action" -> "accept")),
      "requestState"   -> "opaque-blob")
    McpProtocol.parseInputResponses(retry).keySet shouldBe Set("github_login")
    McpProtocol.parseRequestState(retry) shouldBe Some("opaque-blob")
    McpProtocol.isMrtrRetry(retry) shouldBe true
    for junk <- List[ujson.Value](ujson.Obj(), ujson.Str("x"), ujson.Arr(),
                                  ujson.Obj("inputResponses" -> ujson.Str("nope"))) do
      withClue(s"$junk: ") {
        McpProtocol.parseInputResponses(junk) shouldBe empty
        McpProtocol.isMrtrRetry(junk) shouldBe false
      }

  test("an input_required result keeps its type and gets NO cache hints"):
    // Stamping it "complete" would tell the client the request had finished
    // while handing it a body full of questions.
    val b = new McpServerBuilder
    b.resource("mem://ask", None, None, u => ResourceHandlerResult(u, Nil))
    val stamped = McpProtocol.stampComplete(
      McpProtocol.inputRequiredResult(Map("k" -> elicitReq)), "srv", "9.9.9",
      resultType = McpProtocol.ResultTypeInputRequired, cache = None)
    stamped("resultType").str shouldBe McpProtocol.ResultTypeInputRequired
    stamped.obj.keySet should not contain "ttlMs"
    stamped.obj.keySet should not contain "cacheScope"

  test("a result produced from an MRTR RETRY is not cacheable either"):
    // Different reason from the above and it needs its own case: the result may
    // be perfectly `complete`, but it depends on inputs outside the cache key.
    val b = twoToolServer()
    b.resource("mem://a", None, None, u => ResourceHandlerResult(u, Nil))
    val plain = modernResult(b, McpProtocol.Method.ResourcesRead,
      modernParams("uri" -> ujson.Str("mem://a")))
    plain.obj.keySet should contain ("ttlMs")                       // control: normally cacheable
    val onRetry = modernResult(b, McpProtocol.Method.ResourcesRead,
      modernParams("uri" -> ujson.Str("mem://a"), "requestState" -> ujson.Str("blob")))
    onRetry("resultType").str shouldBe McpProtocol.ResultTypeComplete
    onRetry.obj.keySet should not contain "ttlMs"
    onRetry.obj.keySet should not contain "cacheScope"

  test("ping and logging/setLevel are gone on the modern path, kept on the legacy one"):
    val b = new McpServerBuilder
    // Scoped to these two; the resources/subscribe pair has its own case in P4a,
    // and the set's exact membership is asserted below so a fifth member cannot
    // be added without a test naming it.
    for m <- List(McpProtocol.Method.Ping, McpProtocol.Method.LoggingSetLevel) do
      val params = if m == McpProtocol.Method.LoggingSetLevel then modernParams("level" -> ujson.Str("info"))
                   else modernParams()
      val modern = ujson.read(McpServerCore.dispatch(b, m, params, ujson.Num(60)).trim)
      withClue(s"modern $m: ") {
        modern("error")("code").num shouldBe JsonRpc.ErrorCode.MethodNotFound
        modern("error")("message").str should include ("removed in")
      }
      val legacyParams = if m == McpProtocol.Method.LoggingSetLevel then ujson.Obj("level" -> "info")
                         else ujson.Obj()
      val legacy = ujson.read(McpServerCore.dispatch(b, m, legacyParams, ujson.Num(61)).trim)
      withClue(s"legacy $m: ") { legacy.obj.keySet should not contain "error" }
    McpProtocol.RemovedInModern shouldBe Set(
      McpProtocol.Method.Ping, McpProtocol.Method.LoggingSetLevel,
      McpProtocol.Method.ResourcesSubscribe, McpProtocol.Method.ResourcesUnsubscribe)

  // ── P1c: an unknown NAME is not an unknown METHOD ───────────────────

  test("a name that does not resolve is InvalidParams; an unknown method is MethodNotFound"):
    // The whole point of the change, stated as one case so the distinction
    // cannot quietly collapse into a blanket code again. 2026-07-28 renumbered
    // resource-not-found to -32602 and forbids -32002; we emitted neither, we
    // emitted -32601, which tells a client the SERVER lacks the capability
    // rather than that its argument was wrong.
    val b = twoToolServer()
    b.prompt("p", None, Nil, _ => PromptHandlerResult(None, Nil))
    val cases = List(
      McpProtocol.Method.ToolsCall     -> ujson.Obj("name" -> "nope", "arguments" -> ujson.Obj()),
      McpProtocol.Method.ResourcesRead -> ujson.Obj("uri"  -> "mem://nope"),
      McpProtocol.Method.PromptsGet    -> ujson.Obj("name" -> "nope"))
    for (m, params) <- cases do
      val js = ujson.read(McpServerCore.dispatch(b, m, params, ujson.Num(70)).trim)
      withClue(s"$m: ") { js("error")("code").num shouldBe JsonRpc.ErrorCode.InvalidParams }
    // Control: a method that really does not exist still says so.
    ujson.read(McpServerCore.dispatch(b, "no/such/method", ujson.Obj(), ujson.Num(71)).trim)(
      "error")("code").num shouldBe JsonRpc.ErrorCode.MethodNotFound

  // ── P4a: subscriptions/listen, the protocol layer ───────────────────

  test("the filter is opt-in: an unrequested type is never admitted"):
    // The spec's MUST NOT. This predicate is the single place that decides what
    // may leave on a stream, so it is where the rule has to be true.
    val onlyTools = McpProtocol.NotificationFilter(toolsListChanged = true)
    onlyTools.admits(McpProtocol.Method.ToolsListChanged)     shouldBe true
    onlyTools.admits(McpProtocol.Method.PromptsListChanged)   shouldBe false
    onlyTools.admits(McpProtocol.Method.ResourcesListChanged) shouldBe false
    onlyTools.admits(McpProtocol.Method.ResourcesUpdated, Some("file:///a")) shouldBe false
    McpProtocol.NotificationFilter().isEmpty shouldBe true

  test("resource updates are admitted per-URI, not per-type"):
    val f = McpProtocol.NotificationFilter(resourceSubscriptions = List("file:///a", "file:///b"))
    f.admits(McpProtocol.Method.ResourcesUpdated, Some("file:///a")) shouldBe true
    f.admits(McpProtocol.Method.ResourcesUpdated, Some("file:///c")) shouldBe false
    f.admits(McpProtocol.Method.ResourcesUpdated, None)              shouldBe false
    f.admits(McpProtocol.Method.ToolsListChanged)                    shouldBe false

  test("the filter is parsed off the request, and malformed input subscribes to nothing"):
    val asked = ujson.Obj("notifications" -> ujson.Obj(
      "toolsListChanged" -> true,
      "resourceSubscriptions" -> ujson.Arr("file:///project/config.json")))
    val f = McpProtocol.parseNotificationFilter(asked)
    f.toolsListChanged shouldBe true
    f.promptsListChanged shouldBe false
    f.resourceSubscriptions shouldBe List("file:///project/config.json")
    // Guessing wrong here means pushing notifications nobody asked for, so every
    // unusable shape resolves to the empty filter.
    for junk <- List[ujson.Value](ujson.Obj(), ujson.Str("x"), ujson.Arr(),
                                  ujson.Obj("notifications" -> ujson.Str("all")),
                                  ujson.Obj("notifications" -> ujson.Obj("toolsListChanged" -> "yes"))) do
      withClue(s"$junk: ") { McpProtocol.parseNotificationFilter(junk).isEmpty shouldBe true }

  test("every message on a stream carries the subscription id"):
    // On stdio all subscriptions share one channel — without this a client
    // cannot tell which of its streams a notification belongs to.
    val n = ujson.read(JsonRpc.encodeNotification(
      McpProtocol.Method.ResourcesUpdated, ujson.Obj("uri" -> "file:///a")).trim)
    val tagged = McpProtocol.tagSubscription(n, ujson.Num(1))
    tagged("params")("_meta")(McpProtocol.MetaKey.SubscriptionId).num shouldBe 1
    tagged("params")("uri").str shouldBe "file:///a"      // payload preserved

  test("tagging merges into an existing _meta rather than replacing it"):
    val n = ujson.read(JsonRpc.encodeNotification(McpProtocol.Method.ResourcesUpdated,
      ujson.Obj("uri" -> "file:///a", "_meta" -> ujson.Obj("com.example/keep" -> "yes"))).trim)
    val tagged = McpProtocol.tagSubscription(n, ujson.Num(7))
    tagged("params")("_meta")("com.example/keep").str shouldBe "yes"
    tagged("params")("_meta")(McpProtocol.MetaKey.SubscriptionId).num shouldBe 7

  test("the acknowledgement echoes only what was honoured, and carries the id"):
    val honoured = McpProtocol.NotificationFilter(
      toolsListChanged = true, resourceSubscriptions = List("file:///project/config.json"))
    val ack = ujson.read(McpProtocol.subscriptionsAcknowledged(ujson.Num(1), honoured).trim)
    ack("method").str shouldBe McpProtocol.Method.SubscriptionsAcknowledged
    ack("params")("_meta")(McpProtocol.MetaKey.SubscriptionId).num shouldBe 1
    ack("params")("notifications")("toolsListChanged").bool shouldBe true
    // Omitted, not false: a client compares what it asked for against what it gets.
    ack("params")("notifications").obj.keySet should not contain "promptsListChanged"
    ack.obj.keySet should not contain "id"                 // a notification has no id

  test("graceful closure is an empty result carrying the id"):
    val r = McpProtocol.subscriptionClosedResult(ujson.Num(1))
    r("resultType").str shouldBe McpProtocol.ResultTypeComplete
    r("_meta")(McpProtocol.MetaKey.SubscriptionId).num shouldBe 1

  test("resources/subscribe and unsubscribe are gone on the modern path, kept on legacy"):
    val b = new McpServerBuilder
    for m <- List(McpProtocol.Method.ResourcesSubscribe, McpProtocol.Method.ResourcesUnsubscribe) do
      McpProtocol.RemovedInModern should contain (m)
      val modern = ujson.read(McpServerCore.dispatch(
        b, m, modernParams("uri" -> ujson.Str("file:///a")), ujson.Num(80)).trim)
      withClue(s"modern $m: ") { modern("error")("code").num shouldBe JsonRpc.ErrorCode.MethodNotFound }
      val legacy = ujson.read(McpServerCore.dispatch(
        b, m, ujson.Obj("uri" -> "file:///a"), ujson.Num(81)).trim)
      withClue(s"legacy $m: ") { legacy.obj.keySet should not contain "error" }

  // ── P4b: filtered delivery on a listen stream ───────────────────────

  private class Sink:
    val frames = scala.collection.mutable.ListBuffer.empty[ujson.Value]
    val write: String => Unit = s => frames += ujson.read(s.trim)
    def methods: List[String] = frames.toList.flatMap(_.obj.get("method")).flatMap(_.strOpt)

  test("a listen stream receives ONLY what its filter admits, each tagged"):
    val b = new McpServerBuilder
    val sink = new Sink
    McpServerCore.openSubscription(b,
      ujson.Obj("notifications" -> ujson.Obj("toolsListChanged" -> true)), ujson.Num(1), sink.write)
    b.notifyToolsListChanged()
    b.notifyPromptsListChanged()
    b.notifyResourcesListChanged()
    // ack first, then only the admitted one
    sink.methods shouldBe List(McpProtocol.Method.SubscriptionsAcknowledged,
                               McpProtocol.Method.ToolsListChanged)
    sink.frames.toList.foreach { f =>
      f("params")("_meta")(McpProtocol.MetaKey.SubscriptionId).num shouldBe 1
    }

  test("the acknowledgement is the FIRST message and precedes every notification"):
    // The spec forbids any notification on the subscription before the ack, and
    // openSubscription writes it before returning so a transport cannot forget.
    val b = new McpServerBuilder
    val sink = new Sink
    McpServerCore.openSubscription(b,
      ujson.Obj("notifications" -> ujson.Obj("toolsListChanged" -> true)), ujson.Num(2), sink.write)
    sink.methods.head shouldBe McpProtocol.Method.SubscriptionsAcknowledged

  test("resource updates reach only the streams that named that URI"):
    val b = new McpServerBuilder
    val wantsA, wantsB = new Sink
    McpServerCore.openSubscription(b, ujson.Obj("notifications" -> ujson.Obj(
      "resourceSubscriptions" -> ujson.Arr("file:///a"))), ujson.Num(3), wantsA.write)
    McpServerCore.openSubscription(b, ujson.Obj("notifications" -> ujson.Obj(
      "resourceSubscriptions" -> ujson.Arr("file:///b"))), ujson.Num(4), wantsB.write)
    b.notify(McpProtocol.Method.ResourcesUpdated, ujson.Obj("uri" -> "file:///a"))
    wantsA.methods should contain (McpProtocol.Method.ResourcesUpdated)
    wantsB.methods should not contain McpProtocol.Method.ResourcesUpdated

  test("a LEGACY subscriber still receives everything, untagged and byte-identical"):
    // The dual-era promise at the notification layer: a stdio client that never
    // sent subscriptions/listen has opted into nothing, so nothing changes for it.
    val b = new McpServerBuilder
    val legacy = new Sink
    b.addSubscriber(legacy.write)
    b.notifyToolsListChanged()
    b.notifyPromptsListChanged()
    legacy.methods shouldBe List(McpProtocol.Method.ToolsListChanged,
                                 McpProtocol.Method.PromptsListChanged)
    legacy.frames.toList.foreach { f =>
      f("params").obj.keySet should not contain "_meta"
    }

  test("two streams with different filters demultiplex by subscription id"):
    val b = new McpServerBuilder
    val tools, prompts = new Sink
    McpServerCore.openSubscription(b, ujson.Obj("notifications" -> ujson.Obj(
      "toolsListChanged" -> true)), ujson.Num(10), tools.write)
    McpServerCore.openSubscription(b, ujson.Obj("notifications" -> ujson.Obj(
      "promptsListChanged" -> true)), ujson.Num(11), prompts.write)
    b.notifyToolsListChanged()
    b.notifyPromptsListChanged()
    tools.methods.tail shouldBe List(McpProtocol.Method.ToolsListChanged)
    prompts.methods.tail shouldBe List(McpProtocol.Method.PromptsListChanged)
    tools.frames.last("params")("_meta")(McpProtocol.MetaKey.SubscriptionId).num shouldBe 10
    prompts.frames.last("params")("_meta")(McpProtocol.MetaKey.SubscriptionId).num shouldBe 11

  test("closing the stream emits the graceful-closure response, and unsubscribes"):
    val b = new McpServerBuilder
    val sink = new Sink
    val sub = McpServerCore.openSubscription(b,
      ujson.Obj("notifications" -> ujson.Obj("toolsListChanged" -> true)), ujson.Num(5), sink.write)
    sub.close()
    McpServerCore.closeSubscription(ujson.Num(5), sink.write)
    b.notifyToolsListChanged()                       // after close: nothing more
    val last = sink.frames.last
    last("id").num shouldBe 5
    last("result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    last("result")("_meta")(McpProtocol.MetaKey.SubscriptionId).num shouldBe 5
    sink.methods should not contain McpProtocol.Method.ToolsListChanged

  // ── P4b-2: a subscription has a lifetime, and one way to end ────────

  /** A sink whose writes start failing — a client that closed its connection. */
  private class DyingSink(failAfter: Int):
    val frames = scala.collection.mutable.ListBuffer.empty[String]
    val write: String => Unit = s =>
      if frames.size >= failAfter then throw java.io.IOException("connection reset")
      frames += s

  test("a failed write ends THAT stream and leaves the others alone"):
    // On HTTP this is how a client cancels: it closes the connection and the
    // next write fails. The other streams are untouched, exactly as a broadcast
    // would leave them — the difference is only that this one gets torn down.
    val b = new McpServerBuilder
    val dying = new DyingSink(failAfter = 1)      // survives the ack, dies on the first notification
    val healthy = new Sink
    val dead = McpServerCore.openSubscription(b, ujson.Obj("notifications" -> ujson.Obj(
      "toolsListChanged" -> true)), ujson.Num(20), dying.write)
    val alive = McpServerCore.openSubscription(b, ujson.Obj("notifications" -> ujson.Obj(
      "toolsListChanged" -> true)), ujson.Num(21), healthy.write)
    dead.isEnded shouldBe false
    b.notifyToolsListChanged()
    dead.isEnded shouldBe true                    // the write failure ended it
    alive.isEnded shouldBe false                  // and only it
    b.notifyToolsListChanged()
    healthy.methods.count(_ == McpProtocol.Method.ToolsListChanged) shouldBe 2

  test("await returns once the subscription ends, however it ends"):
    // The point of the latch: client close, cancellation and teardown are ONE
    // event for the transport, not three code paths.
    val b = new McpServerBuilder
    val sink = new Sink
    val sub = McpServerCore.openSubscription(b, ujson.Obj("notifications" -> ujson.Obj(
      "toolsListChanged" -> true)), ujson.Num(22), sink.write)
    sub.await(50) shouldBe false                  // still open
    sub.close()
    sub.await(50) shouldBe true                   // and now it returns

  test("close is idempotent, because the three sources may race"):
    val b = new McpServerBuilder
    val sink = new Sink
    val sub = McpServerCore.openSubscription(b, ujson.Obj("notifications" -> ujson.Obj(
      "toolsListChanged" -> true)), ujson.Num(23), sink.write)
    sub.close(); sub.close(); sub.close()
    sub.isEnded shouldBe true
    b.notifyToolsListChanged()
    sink.methods should not contain McpProtocol.Method.ToolsListChanged

  test("a BROADCAST subscriber still swallows its write error and stays"):
    // The other half of the decision. A legacy channel has no owner to notify
    // and no id to tear down, so one dead peer must not remove itself or
    // disturb the loop — unchanged from before this revision.
    val b = new McpServerBuilder
    val dying = new DyingSink(failAfter = 0)
    val healthy = new Sink
    b.addSubscriber(dying.write)
    b.addSubscriber(healthy.write)
    noException should be thrownBy b.notifyToolsListChanged()
    noException should be thrownBy b.notifyToolsListChanged()
    healthy.methods should have size 2

  // ── P4b-4: stdio — one channel, so the request id is the only handle ─

  private def listenFrame(id: Int, filter: ujson.Obj) = JsonRpc.encodeRequest(
    McpProtocol.Method.SubscriptionsListen,
    ujson.Obj("notifications" -> filter,
      "_meta" -> ujson.Obj(
        McpProtocol.MetaKey.ProtocolVersion    -> McpProtocol.ModernProtocolVersion,
        McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj())), id.toLong)

  test("stdio: a listen request opens a stream WITHOUT blocking the read loop"):
    // The load-bearing property. If serve() blocked the way the HTTP callback
    // does, the client could never send the cancellation that ends the
    // subscription — the loop would not be reading. So a SECOND request after
    // the listen must still be answered.
    val b = new McpServerBuilder
    b.tool("echo", None, ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    val out = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val inbox = java.util.concurrent.LinkedBlockingQueue[String]()
    inbox.put(listenFrame(1, ujson.Obj("toolsListChanged" -> true)))
    inbox.put(JsonRpc.encodeRequest(McpProtocol.Method.ToolsList, ujson.Obj(), 2L))
    val t = new Thread(new Runnable { def run(): Unit =
      McpServerCore.serve(b, () => Option(inbox.poll(1, java.util.concurrent.TimeUnit.SECONDS)),
        s => { out.add(s); () }, "srv", "9.9.9") })
    t.setDaemon(true); t.start()
    eventuallyQ(out, 2)
    val frames = out.toArray.map(_.toString).toList
    frames.head should include (McpProtocol.Method.SubscriptionsAcknowledged)
    frames(1) should include ("\"id\":2")          // the loop kept reading
    b.liveSubscriptions.size shouldBe 1
    t.interrupt()

  test("stdio: notifications/cancelled naming the listen id ends that subscription"):
    // WAIT FOR THE SUBSCRIPTION TO EXIST BEFORE CANCELLING IT. The first
    // version queued both frames up front and then waited for
    // `liveSubscriptions.isEmpty` — which is true at t=0, before the listen is
    // even read, so it passed instantly and every later assertion measured a
    // server that had not started. That is why the same case failed with three
    // different numbers on three runs: the test was green-by-accident at the
    // wrong moment, not racy in the code under test.
    val b = new McpServerBuilder
    val out = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val inbox = java.util.concurrent.LinkedBlockingQueue[String]()
    inbox.put(listenFrame(7, ujson.Obj("toolsListChanged" -> true)))
    val t = new Thread(new Runnable { def run(): Unit =
      // `take()`, not `poll(timeout)`: a timeout returns None, serve() reads that as
      // EOF, and the EOF teardown closes every subscription — which would empty the
      // registry whether or not cancellation works. Measured: with the cancellation
      // disabled these cases still passed. Blocking forever means the ONLY thing that
      // can empty the registry is the cancel.
      McpServerCore.serve(b, () => Some(inbox.take()),
        s => { out.add(s); () }, "srv", "9.9.9") })
    t.setDaemon(true); t.start()
    eventuallyCond(b.liveSubscriptions.size == 1)
    out.peek should include (McpProtocol.Method.SubscriptionsAcknowledged)
    inbox.put(JsonRpc.encodeNotification(McpProtocol.Method.Cancelled, ujson.Obj("requestId" -> 7)))
    eventuallyCond(b.liveSubscriptions.isEmpty)
    t.interrupt()

  test("stdio: a cancelled id may be a CALL or a SUBSCRIPTION, and both lookups run"):
    // A client does not distinguish them — it cancels a request id — so neither
    // does the server. An id that matches neither must be harmless.
    val b = new McpServerBuilder
    val out = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val inbox = java.util.concurrent.LinkedBlockingQueue[String]()
    inbox.put(listenFrame(3, ujson.Obj("toolsListChanged" -> true)))
    val t = new Thread(new Runnable { def run(): Unit =
      // `take()`, not `poll(timeout)`: a timeout returns None, serve() reads that as
      // EOF, and the EOF teardown closes every subscription — which would empty the
      // registry whether or not cancellation works. Measured: with the cancellation
      // disabled these cases still passed. Blocking forever means the ONLY thing that
      // can empty the registry is the cancel.
      McpServerCore.serve(b, () => Some(inbox.take()),
        s => { out.add(s); () }, "srv", "9.9.9") })
    t.setDaemon(true); t.start()
    eventuallyCond(b.liveSubscriptions.size == 1)
    inbox.put(JsonRpc.encodeNotification(McpProtocol.Method.Cancelled,
      ujson.Obj("requestId" -> 999)))              // matches nothing
    Thread.sleep(80)
    b.liveSubscriptions.size shouldBe 1            // untouched — and no exception
    inbox.put(JsonRpc.encodeNotification(McpProtocol.Method.Cancelled,
      ujson.Obj("requestId" -> 3)))
    eventuallyCond(b.liveSubscriptions.isEmpty)
    t.interrupt()

  test("stdio: EOF closes every stream on that channel"):
    // Without this a subscription outlives its transport and writes to a dead pipe.
    val b = new McpServerBuilder
    val out = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val inbox = java.util.concurrent.LinkedBlockingQueue[String]()
    inbox.put(listenFrame(4, ujson.Obj("toolsListChanged" -> true)))
    inbox.put(listenFrame(5, ujson.Obj("promptsListChanged" -> true)))
    val eof = java.util.concurrent.atomic.AtomicBoolean(false)
    val t = new Thread(new Runnable { def run(): Unit =
      McpServerCore.serve(b,
        () => if eof.get then None else Option(inbox.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)),
        s => { out.add(s); () }, "srv", "9.9.9") })
    t.setDaemon(true); t.start()
    eventuallyCond(b.liveSubscriptions.size == 2)
    eof.set(true)
    eventuallyCond(b.liveSubscriptions.isEmpty)

  private def eventuallyQ(q: java.util.concurrent.ConcurrentLinkedQueue[String], n: Int): Unit =
    eventuallyCond(q.size >= n)

  private def eventuallyCond(cond: => Boolean): Unit =
    var i = 0
    while i < 100 && !cond do { Thread.sleep(20); i += 1 }
    assert(cond, s"condition still false after ${i * 20}ms")

  // ── P2d-1: the client envelope, checked against OUR OWN server ──────

  /** The strongest available gate for this pair: whatever the client builds,
   *  the server's validator must accept. Two sides of one rule cannot drift
   *  apart while this holds, and neither side is graded by its own opinion. */
  private def serverAccepts(method: String, params: ujson.Value): Option[String] =
    val meta = McpProtocol.clientMeta("test-client", "0.1.0")
    val body = McpProtocol.withClientMeta(params, meta)
    // Run the request through the REAL server, not just the header validator.
    // The first version called validateRequestHeaders only, and a negative
    // control showed what that missed: dropping the REQUIRED clientCapabilities
    // left this gate green, because that field is checked in dispatch and not
    // in the header validator. A round-trip gate that exercises one of the two
    // checks is not a round trip.
    val frame = McpServerCore.handleHttpRequest(
      routableServer(), JsonRpc.encodeRequest(method, body, 1L), "srv", "9.9.9",
      McpProtocol.mirroredHeaders(method, body))
    val js = ujson.read(frame.trim)
    js.obj.get("error").map(e => s"${e("code").num.toInt}: ${e("message").str}")

  /** A server that can actually answer each method the round trip names. */
  private def routableServer(): McpServerBuilder =
    val b = new McpServerBuilder
    b.tool("echo", None, ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    // The non-ASCII case needs a real tool by that name: the strengthened round
    // trip goes all the way to the handler, so an unregistered name now fails
    // for a legitimate reason rather than passing on a header check alone.
    b.tool("Hello, 世界", None, ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    b.prompt("p", None, Nil, _ => PromptHandlerResult(None, Nil))
    b.resource("file:///a", None, None, u => ResourceHandlerResult(u, Nil))
    b

  test("what the client builds, the server accepts — for every method"):
    serverAccepts(McpProtocol.Method.ToolsList, ujson.Obj()) shouldBe None
    serverAccepts(McpProtocol.Method.ToolsCall,
      ujson.Obj("name" -> "echo", "arguments" -> ujson.Obj())) shouldBe None
    serverAccepts(McpProtocol.Method.PromptsGet, ujson.Obj("name" -> "p")) shouldBe None
    serverAccepts(McpProtocol.Method.ResourcesRead, ujson.Obj("uri" -> "file:///a")) shouldBe None
    serverAccepts(McpProtocol.Method.ServerDiscover, ujson.Obj()) shouldBe None

  test("a non-ASCII name survives the round trip, because both sides use the sentinel"):
    // The case that breaks if only one side knows about the encoding.
    serverAccepts(McpProtocol.Method.ToolsCall,
      ujson.Obj("name" -> "Hello, 世界", "arguments" -> ujson.Obj())) shouldBe None
    McpProtocol.mirroredHeaders(McpProtocol.Method.ToolsCall,
      ujson.Obj("name" -> "Hello, 世界"))(McpProtocol.Header.Name) should startWith ("=?base64?")

  test("clientMeta carries what the server REQUIRES, not merely what is polite"):
    val m = McpProtocol.clientMeta("c", "1.0")
    m(McpProtocol.MetaKey.ProtocolVersion).str shouldBe McpProtocol.ModernProtocolVersion
    m.value.keySet should contain (McpProtocol.MetaKey.ClientCapabilities)   // required, so present
    m(McpProtocol.MetaKey.ClientInfo)("name").str shouldBe "c"
    m.value.keySet should not contain McpProtocol.MetaKey.LogLevel           // optional, so absent
    McpProtocol.clientMeta("c", "1.0", logLevel = Some("debug"))(
      McpProtocol.MetaKey.LogLevel).str shouldBe "debug"

  test("merging _meta does not switch off a progressToken the caller set"):
    // `_meta` is a shared namespace; replacing it would silently drop an opt-in
    // the caller made, and nothing would report it.
    val params = ujson.Obj("name" -> "echo",
      "_meta" -> ujson.Obj("progressToken" -> "tok-1"))
    val merged = McpProtocol.withClientMeta(params, McpProtocol.clientMeta("c", "1.0"))
    merged("_meta")("progressToken").str shouldBe "tok-1"
    merged("_meta")(McpProtocol.MetaKey.ProtocolVersion).str shouldBe McpProtocol.ModernProtocolVersion

  test("Mcp-Name is sent for exactly the three methods that mirror a name"):
    for m <- McpProtocol.NameHeaderSource.keys do
      val field = McpProtocol.NameHeaderSource(m)
      McpProtocol.mirroredHeaders(m, ujson.Obj(field -> "x")).keySet should contain (McpProtocol.Header.Name)
    for m <- List(McpProtocol.Method.ToolsList, McpProtocol.Method.ServerDiscover,
                  McpProtocol.Method.SubscriptionsListen) do
      McpProtocol.mirroredHeaders(m, ujson.Obj()).keySet should not contain McpProtocol.Header.Name

  test("client and server agree about x-mcp-header, including the absent case"):
    val schema = ujson.Obj("type" -> "object", "properties" -> ujson.Obj(
      "region" -> ujson.Obj("type" -> "string", "x-mcp-header" -> "Region"),
      "n"      -> ujson.Obj("type" -> "integer", "x-mcp-header" -> "N")))
    val args = ujson.Obj("region" -> "西部", "n" -> 42)
    val sent = McpProtocol.mirroredParamHeaders(schema, args)
    McpProtocol.validateParamHeaders(sent, schema, args) shouldBe None
    // The rule both sides must share: a value that is absent carries NO header.
    val partial = ujson.Obj("n" -> 42)
    val sentPartial = McpProtocol.mirroredParamHeaders(schema, partial)
    sentPartial.keySet should not contain (McpProtocol.Header.ParamPrefix + "Region")
    McpProtocol.validateParamHeaders(sentPartial, schema, partial) shouldBe None

  test("an invalid x-mcp-header schema makes the client send nothing"):
    // A conforming client must exclude such a tool from tools/list, so it should
    // never call one; emitting no headers rather than guessing is the
    // conservative half of that, and the server rejects the call anyway.
    val bad = ujson.Obj("type" -> "object", "properties" -> ujson.Obj(
      "p" -> ujson.Obj("type" -> "number", "x-mcp-header" -> "P")))
    McpProtocol.mirroredParamHeaders(bad, ujson.Obj("p" -> 1)) shouldBe empty
    McpProtocol.validateParamHeaders(Map.empty, bad, ujson.Obj("p" -> 1)).isDefined shouldBe true

  // ── P2d-2: which era does this server speak? ────────────────────────

  test("a discover RESULT means modern; so does a modern ERROR"):
    // The subtle half. A modern server may refuse the probe — wrong version,
    // missing capability, header mismatch — and that refusal still proves it
    // speaks the revision. Reading every error as legacy would send us to
    // `initialize` against a server that has no such method.
    McpProtocol.eraFromProbe(Right(ujson.Obj("supportedVersions" -> ujson.Arr()))) shouldBe
      McpProtocol.Era.Modern
    for c <- McpProtocol.ModernErrorCodes do
      withClue(s"code $c: ") {
        McpProtocol.eraFromProbe(Left(JsonRpc.Error(c, "refused"))) shouldBe McpProtocol.Era.Modern
      }

  test("anything else means legacy — including the code a legacy server actually sends"):
    McpProtocol.eraFromProbe(Left(JsonRpc.Error(
      JsonRpc.ErrorCode.MethodNotFound, "method not found: server/discover"))) shouldBe
      McpProtocol.Era.Legacy
    for c <- List(JsonRpc.ErrorCode.ParseError, JsonRpc.ErrorCode.InvalidRequest,
                  JsonRpc.ErrorCode.InvalidParams, JsonRpc.ErrorCode.InternalError, -32002) do
      withClue(s"code $c: ") {
        McpProtocol.eraFromProbe(Left(JsonRpc.Error(c, "x"))) shouldBe McpProtocol.Era.Legacy
      }

  test("our OWN server is detected as modern by our own probe"):
    // The two sides checked against each other again: dispatch server/discover
    // for real and feed the answer to the client's decision.
    val b = twoToolServer()
    val frame = McpServerCore.dispatch(b, McpProtocol.Method.ServerDiscover,
      modernParams(), ujson.Num(1), "srv", "9.9.9")
    val js = ujson.read(frame.trim)
    val probe: Either[JsonRpc.Error, ujson.Value] =
      js.obj.get("error").map(e => Left(JsonRpc.Error(e("code").num.toInt, e("message").str)))
        .getOrElse(Right(js("result")))
    McpProtocol.eraFromProbe(probe) shouldBe McpProtocol.Era.Modern

  test("our own server's -32022 refusal is ALSO read as modern"):
    // The case that separates "refused me" from "does not speak this at all".
    val params = ujson.Obj("_meta" -> ujson.Obj(
      McpProtocol.MetaKey.ProtocolVersion    -> "1900-01-01",
      McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj()))
    val js = ujson.read(McpServerCore.dispatch(new McpServerBuilder,
      McpProtocol.Method.ServerDiscover, params, ujson.Num(2)).trim)
    js("error")("code").num shouldBe McpProtocol.ErrorCode.UnsupportedProtocolVersion
    McpProtocol.eraFromProbe(Left(JsonRpc.Error(js("error")("code").num.toInt, ""))) shouldBe
      McpProtocol.Era.Modern

  test("on HTTP the BODY decides a 400, because status alone cannot"):
    // A modern server uses 400 for its own errors, so 400 does not mean
    // "unknown endpoint". Only a recognised modern error in the body identifies
    // the era — that asymmetry is the reason this function exists at all.
    McpProtocol.eraFromHttp(200, "{}") shouldBe McpProtocol.Era.Modern
    val modern400 = JsonRpc.encodeError(ujson.Num(1),
      McpProtocol.ErrorCode.UnsupportedProtocolVersion, "Unsupported protocol version")
    McpProtocol.eraFromHttp(400, modern400) shouldBe McpProtocol.Era.Modern
    val legacy400 = JsonRpc.encodeError(ujson.Num(1),
      JsonRpc.ErrorCode.InvalidRequest, "Bad Request")
    McpProtocol.eraFromHttp(400, legacy400) shouldBe McpProtocol.Era.Legacy
    for junk <- List("", "not json", "<html>404</html>", "{}") do
      withClue(s"[$junk]: ") { McpProtocol.eraFromHttp(400, junk) shouldBe McpProtocol.Era.Legacy }
    McpProtocol.eraFromHttp(404, modern400) shouldBe McpProtocol.Era.Modern
    McpProtocol.eraFromHttp(405, "") shouldBe McpProtocol.Era.Legacy

  // ── P2d-3: connect() probes once and settles the era ────────────────

  /** A client wired to a peer that answers synchronously on the calling
   *  thread: `write` computes the reply and feeds it straight back, so the
   *  response is in the queue before `request` polls. No threads, no sleeps,
   *  no timing — the two cases that went green by accident yesterday were both
   *  timing, and this shape cannot have that fault. */
  private def sentMethods(sent: scala.collection.mutable.ListBuffer[String]) =
    sent.toList.flatMap(f => JsonRpc.parse(f).toOption).collect {
      case JsonRpc.Message.Request(m, _, _)      => m
      case JsonRpc.Message.Notification(m, _)    => m
    }

  test("connect against a MODERN server settles on Modern and sends no initialize"):
    val server = twoToolServer()
    val sent = scala.collection.mutable.ListBuffer.empty[String]
    var client: McpClientCore = null
    client = McpClientCore { frame =>
      sent += frame
      JsonRpc.parse(frame) match
        case Right(JsonRpc.Message.Request(m, p, id)) =>
          client.dispatchResponse(McpServerCore.dispatch(server, m, p, id, "srv", "9.9.9"))
        case _ => ()
    }
    client.connect("test-client", "0.1.0") shouldBe Right(McpProtocol.Era.Modern)
    client.currentEra shouldBe McpProtocol.Era.Modern
    val methods = sentMethods(sent)
    methods should contain (McpProtocol.Method.ServerDiscover)
    // The whole point: `initialize` does not exist in the modern revision.
    methods should not contain McpProtocol.Method.Initialize

  test("connect against a LEGACY-ONLY server falls back and completes the handshake"):
    // A real legacy peer: it does not implement server/discover and says so.
    val sent = scala.collection.mutable.ListBuffer.empty[String]
    var client: McpClientCore = null
    client = McpClientCore { frame =>
      sent += frame
      JsonRpc.parse(frame) match
        case Right(JsonRpc.Message.Request(m, _, id)) =>
          if m == McpProtocol.Method.ServerDiscover then
            client.dispatchResponse(JsonRpc.encodeError(id,
              JsonRpc.ErrorCode.MethodNotFound, s"method not found: $m"))
          else
            client.dispatchResponse(JsonRpc.encodeResult(id,
              ujson.Obj("protocolVersion" -> McpProtocol.ProtocolVersion)))
        case _ => ()
    }
    client.connect("test-client", "0.1.0") shouldBe Right(McpProtocol.Era.Legacy)
    val methods = sentMethods(sent)
    methods shouldBe List(McpProtocol.Method.ServerDiscover,
                          McpProtocol.Method.Initialize,
                          McpProtocol.Method.Initialized)

  test("a modern server REFUSING the probe is still modern, and gets no initialize"):
    // The case that separates "refused me" from "does not speak this". A client
    // that read this as legacy would send `initialize` to a server without one.
    val sent = scala.collection.mutable.ListBuffer.empty[String]
    var client: McpClientCore = null
    client = McpClientCore { frame =>
      sent += frame
      JsonRpc.parse(frame) match
        case Right(JsonRpc.Message.Request(_, _, id)) =>
          client.dispatchResponse(JsonRpc.encodeError(id,
            McpProtocol.ErrorCode.UnsupportedProtocolVersion, "Unsupported protocol version",
            Some(McpProtocol.unsupportedVersionData("2026-07-28"))))
        case _ => ()
    }
    client.connect("test-client", "0.1.0") shouldBe Right(McpProtocol.Era.Modern)
    sentMethods(sent) shouldBe List(McpProtocol.Method.ServerDiscover)

  test("connect is idempotent — a second call does not re-handshake"):
    // Re-probing a legacy server would send it a SECOND initialize, which the
    // lifecycle it negotiated does not allow.
    val sent = scala.collection.mutable.ListBuffer.empty[String]
    var client: McpClientCore = null
    client = McpClientCore { frame =>
      sent += frame
      JsonRpc.parse(frame) match
        case Right(JsonRpc.Message.Request(m, _, id)) =>
          if m == McpProtocol.Method.ServerDiscover then
            client.dispatchResponse(JsonRpc.encodeError(id, JsonRpc.ErrorCode.MethodNotFound, "no"))
          else client.dispatchResponse(JsonRpc.encodeResult(id, ujson.Obj()))
        case _ => ()
    }
    client.connect("c", "1.0") shouldBe Right(McpProtocol.Era.Legacy)
    val afterFirst = sent.size
    client.connect("c", "1.0") shouldBe Right(McpProtocol.Era.Legacy)
    sent.size shouldBe afterFirst

  test("the probe carries the required client _meta, so a strict server accepts it"):
    // Checked against our own server's validation rather than by inspection.
    val server = new McpServerBuilder
    var seen: ujson.Value = ujson.Null
    var client: McpClientCore = null
    client = McpClientCore { frame =>
      JsonRpc.parse(frame) match
        case Right(JsonRpc.Message.Request(m, p, id)) =>
          seen = p
          client.dispatchResponse(McpServerCore.dispatch(server, m, p, id, "srv", "9.9.9"))
        case _ => ()
    }
    client.connect("test-client", "0.1.0") shouldBe Right(McpProtocol.Era.Modern)
    val ctx = McpProtocol.parseRequestMeta(seen)
    ctx.isModern shouldBe true
    ctx.clientCapabilities.isDefined shouldBe true        // required by the spec
    ctx.clientInfo.isDefined shouldBe true

  // ── P2d-4a: the HTTP era probe, against a real endpoint ─────────────

  /** A real HTTP endpoint that records the JSON-RPC methods it is asked for
   *  and answers each with a scripted (status, body). Real, because the whole
   *  point of the HTTP rule is what the STATUS and BODY are, and a stub that
   *  returns an already-parsed value would skip exactly that. */
  private def httpEndpoint(reply: String => (Int, String)): (String, scala.collection.mutable.ListBuffer[String], () => Unit) =
    val seen = scala.collection.mutable.ListBuffer.empty[String]
    val s = new java.net.ServerSocket(0); val port = s.getLocalPort; s.close()
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/mcp", { ex =>
      val body = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
      val method = JsonRpc.parse(body) match
        case Right(JsonRpc.Message.Request(m, _, _))   => m
        case Right(JsonRpc.Message.Notification(m, _)) => m
        case _                                         => "?"
      seen += method
      val (status, out) = reply(method)
      val bytes = out.getBytes("UTF-8")
      ex.getResponseHeaders.set("Content-Type", "application/json")
      ex.sendResponseHeaders(status, bytes.length.toLong)
      ex.getResponseBody.write(bytes); ex.getResponseBody.close()
    })
    server.start()
    (s"http://127.0.0.1:$port/mcp", seen, () => server.stop(0))

  test("HTTP: a 200 discover result settles Modern and sends no initialize"):
    val discover = JsonRpc.encodeResult(ujson.Num(1),
      McpProtocol.discoverResult("srv", "9.9.9"))
    val (url, seen, stop) = httpEndpoint(_ => (200, discover))
    try
      val c = new McpHttpClient(url, 5000L)
      c.connect("test-client", "0.1.0") shouldBe Right(McpProtocol.Era.Modern)
      seen.toList shouldBe List(McpProtocol.Method.ServerDiscover)
    finally stop()

  test("HTTP: a 400 carrying a MODERN error is still Modern — the case request() would lose"):
    // THE ONE THIS SLICE EXISTS FOR. McpHttpClient.request collapses any non-2xx
    // into Left(InternalError, "HTTP 400: …"), discarding the JSON-RPC code —
    // and that code is the whole signal. Probing through request() would read
    // this modern refusal as legacy and then send `initialize` to a server that
    // has no such method.
    val refusal = JsonRpc.encodeError(ujson.Num(1),
      McpProtocol.ErrorCode.UnsupportedProtocolVersion, "Unsupported protocol version",
      Some(McpProtocol.unsupportedVersionData("2026-07-28")))
    val (url, seen, stop) = httpEndpoint(_ => (400, refusal))
    try
      val c = new McpHttpClient(url, 5000L)
      c.connect("test-client", "0.1.0") shouldBe Right(McpProtocol.Era.Modern)
      seen.toList should not contain McpProtocol.Method.Initialize
    finally stop()

  test("HTTP: a 400 with an ordinary error falls back and completes the handshake"):
    val legacyErr = JsonRpc.encodeError(ujson.Num(1), JsonRpc.ErrorCode.InvalidRequest, "Bad Request")
    val (url, seen, stop) = httpEndpoint {
      case McpProtocol.Method.ServerDiscover => (400, legacyErr)
      case _ => (200, JsonRpc.encodeResult(ujson.Num(2),
                   ujson.Obj("protocolVersion" -> McpProtocol.ProtocolVersion)))
    }
    try
      val c = new McpHttpClient(url, 5000L)
      c.connect("test-client", "0.1.0") shouldBe Right(McpProtocol.Era.Legacy)
      seen.toList shouldBe List(McpProtocol.Method.ServerDiscover,
                                McpProtocol.Method.Initialize,
                                McpProtocol.Method.Initialized)
    finally stop()

  test("HTTP: an endpoint that is not MCP at all falls back and then REPORTS the failure"):
    // A 404 with an HTML body is the wrong-URL / deprecated-transport case. The
    // era DECISION is legacy — the spec has the client fall back rather than
    // assume — but the fallback handshake then fails too, and connect says so.
    // Before connect returned an Either that failure was swallowed and this
    // endpoint looked connected.
    McpProtocol.eraFromHttp(404, "<html>Not Found</html>") shouldBe McpProtocol.Era.Legacy
    val (url, _, stop) = httpEndpoint(_ => (404, "<html>Not Found</html>"))
    try
      val c = new McpHttpClient(url, 5000L)
      c.connect("c", "1.0").isLeft shouldBe true
      c.currentEra shouldBe McpProtocol.Era.Unknown   // nothing was reached, so nothing is cached
    finally stop()

  test("HTTP: connect is idempotent — the endpoint is probed once"):
    val discover = JsonRpc.encodeResult(ujson.Num(1), McpProtocol.discoverResult("srv", "9.9.9"))
    val (url, seen, stop) = httpEndpoint(_ => (200, discover))
    try
      val c = new McpHttpClient(url, 5000L)
      c.connect("c", "1.0"); c.connect("c", "1.0"); c.connect("c", "1.0")
      seen.size shouldBe 1
    finally stop()

  test("connect REPORTS a failed legacy handshake instead of swallowing it"):
    // The defect this slice fixes, and it was visible only from the CONSUMERS:
    // every call site raises on a failed handshake, so a connect() returning
    // just an Era would have deleted that diagnostic — a server whose
    // initialize fails would have looked connected.
    var client: McpClientCore = null
    client = McpClientCore { frame =>
      JsonRpc.parse(frame) match
        case Right(JsonRpc.Message.Request(m, _, id)) =>
          if m == McpProtocol.Method.ServerDiscover then
            client.dispatchResponse(JsonRpc.encodeError(id, JsonRpc.ErrorCode.MethodNotFound, "no"))
          else
            client.dispatchResponse(JsonRpc.encodeError(id,
              JsonRpc.ErrorCode.InvalidRequest, "initialize refused"))
        case _ => ()
    }
    client.connect("c", "1.0") match
      case Left(e)  => e.message should include ("initialize refused")
      case Right(r) => fail(s"a failed handshake must be reported, got Right($r)")
    // And the era stays Unknown, so a later connect can try again rather than
    // caching a state that was never reached.
    client.currentEra shouldBe McpProtocol.Era.Unknown

  // ── the negotiation rule itself, tested once and without a socket ───

  /** Records what the negotiation asked the transport to do. */
  private class Calls:
    val log = scala.collection.mutable.ListBuffer.empty[String]
    var initParams: ujson.Value = ujson.Null

  private def negotiate(era: McpProtocol.Era,
                        initFails: Boolean = false): (Either[JsonRpc.Error, McpProtocol.Era], Calls) =
    val c = new Calls
    val r = McpProtocol.negotiateEra("c", "1.0",
      decideEra = () => { c.log += "decide"; era },
      sendInitialize = p =>
        c.log += "initialize"; c.initParams = p
        if initFails then Left(JsonRpc.Error(JsonRpc.ErrorCode.InvalidRequest, "refused"))
        else Right(ujson.Obj()),
      sendInitialized = () => { c.log += "initialized"; () })
    (r, c)

  test("negotiation: a MODERN peer is never sent an initialize"):
    // The rule the whole migration turns on. This is now asserted in ONE place
    // instead of three, and it holds for stdio, WS and HTTP by construction
    // because all three call this function.
    val (r, c) = negotiate(McpProtocol.Era.Modern)
    r shouldBe Right(McpProtocol.Era.Modern)
    c.log.toList shouldBe List("decide")

  test("negotiation: a LEGACY peer gets initialize THEN initialized, in that order"):
    val (r, c) = negotiate(McpProtocol.Era.Legacy)
    r shouldBe Right(McpProtocol.Era.Legacy)
    c.log.toList shouldBe List("decide", "initialize", "initialized")
    c.initParams("protocolVersion").str shouldBe McpProtocol.ProtocolVersion
    c.initParams("clientInfo")("name").str shouldBe "c"

  test("negotiation: a failed handshake is reported and `initialized` is NOT sent"):
    // Sending `initialized` after a refused `initialize` would tell the peer the
    // lifecycle completed when it did not.
    val (r, c) = negotiate(McpProtocol.Era.Legacy, initFails = true)
    r.isLeft shouldBe true
    r.swap.toOption.get.message should include ("refused")
    c.log.toList shouldBe List("decide", "initialize")

  test("negotiation: the transport is asked to decide exactly once"):
    val (_, c) = negotiate(McpProtocol.Era.Modern)
    c.log.count(_ == "decide") shouldBe 1

  test("all three clients share this rule — none carries its own copy"):
    // The reason this function exists. Two of the three copies were identical
    // apart from whitespace, and the WS one could not be reached by any test at
    // all: the JDK ships a WebSocket client and no server. Deduplicating removed
    // the unreachable logic rather than building a socket to reach it.
    // Walk up to the directory holding build.sbt rather than trusting the
    // forked JVM's working directory, which is not ours to assume.
    var root = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath
    while root != null && !java.nio.file.Files.exists(root.resolve("build.sbt")) do
      root = root.getParent
    assert(root != null, "could not locate the repository root from user.dir")
    val src = (f: String) => new String(java.nio.file.Files.readAllBytes(
      root.resolve(s"mcp/common/src/main/scala/scalascript/mcp/$f.scala")), "UTF-8")
    for f <- List("McpClientCore", "McpWsClient", "McpHttpClient") do
      withClue(s"$f: ") {
        src(f) should include ("McpProtocol.negotiateEra")
        // no local branching on the era, and no hand-rolled handshake ordering
        src(f) should not include "if decided == McpProtocol.Era.Legacy"
      }

  // ── removed NOTIFICATION: roots/list_changed ────────────────────────────
  //
  // Found by checking a comment against the code. `RemovedInModern` is read
  // only by the request path, so putting a notification in it would have been
  // a no-op that reads like a fix. These four cases pin both halves.

  private def rootsChangedBody(params: ujson.Value): String =
    ujson.write(ujson.Obj(
      "jsonrpc" -> "2.0",
      "method"  -> McpProtocol.Method.RootsListChanged,
      "params"  -> params))

  private def firesHook(params: ujson.Value): Boolean =
    val fired = java.util.concurrent.atomic.AtomicBoolean(false)
    val b = echoServer()
    b.setOnRootsListChanged(() => fired.set(true))
    McpServerCore.handleHttpRequest(b, rootsChangedBody(params), "srv", "9.9.9")
    fired.get

  test("legacy roots/list_changed still fires the hook"):
    firesHook(ujson.Obj()) shouldBe true

  test("modern roots/list_changed does NOT fire the hook — removed in 2026-07-28"):
    firesHook(modernParams()) shouldBe false

  test("the removed notification is NOT in RemovedInModern, which cannot enforce it"):
    McpProtocol.RemovedInModern should not contain McpProtocol.Method.RootsListChanged

  test("RemovedNotificationsInModern is frozen"):
    McpProtocol.RemovedNotificationsInModern shouldBe Set(McpProtocol.Method.RootsListChanged)

  // ── P3b — MRTR replay (specs/mcp-2026-07-28.md 8.5b) ────────────────────
  //
  // The decision was REPLAY: the handler stays an ordinary unsavable closure
  // and is RE-RUN on the retry. "It works" is not the thing to test — a parked
  // thread would also work. The thing that distinguishes the two is whether the
  // handler runs again, so the run counter is the load-bearing assertion.

  private val runs = java.util.concurrent.atomic.AtomicInteger(0)

  /** A tool that asks one question, counting how many times it is entered. */
  private def askingServer(questions: Int = 1): McpServerBuilder =
    val b = new McpServerBuilder
    b.setMrtrMode(MrtrMode.Replay)     // these cases are ABOUT replay; ask for it
    b.tool("ask", None, ujson.Obj(), _ =>
      runs.incrementAndGet()
      val answers = (1 to questions).map(i =>
        b.elicit(s"question $i", ujson.Obj("type" -> "object")) match
          case Right(McpProtocol.ElicitationResult.Accept(c)) => c.render()
          case Right(other)                                   => other.toString
          case Left(e)                                        => s"error:${e.message}")
      ToolHandlerResult(List(McpProtocol.textContent(answers.mkString(","))), isError = false))
    b

  private def callAsk(b: McpServerBuilder, params: ujson.Obj): ujson.Value =
    // P2b: a modern request over HTTP must carry headers that agree with the body.
    ujson.read(McpServerCore.handleHttpRequest(b, ujson.Obj(
      "jsonrpc" -> "2.0", "id" -> 77,
      "method"  -> McpProtocol.Method.ToolsCall,
      "params"  -> params).render(), "srv", "9.9.9",
      modernHeaders(McpProtocol.Header.Name -> "ask")).trim)

  private def askParams(fields: (String, ujson.Value)*): ujson.Obj =
    modernParams((Seq("name" -> (ujson.Str("ask"): ujson.Value),
                      "arguments" -> (ujson.Obj(): ujson.Value)) ++ fields)*)

  private def answer(text: String): ujson.Value =
    ujson.Obj("action" -> "accept", "content" -> ujson.Str(text))

  test("modern: an unanswered elicit yields input-required, not an error"):
    val js = callAsk(askingServer(), askParams())
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeInputRequired
    js("result")("inputRequests").obj.keySet shouldBe Set("elicit-1")
    js("result")("inputRequests")("elicit-1")("method").str shouldBe
      McpProtocol.Method.ElicitationCreate
    // the generic handler-error path must NOT have claimed it
    js("result").obj.keySet should not contain "isError"

  test("modern: the answered retry completes"):
    val js = callAsk(askingServer(), askParams(
      "inputResponses" -> ujson.Obj("elicit-1" -> answer("yes"))))
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    js("result")("content")(0)("text").str should include ("yes")

  test("REPLAY: the handler is re-run, it is not resumed"):
    // The whole decision in one assertion. Under a parked thread this reads 1.
    runs.set(0)
    val b = askingServer()
    callAsk(b, askParams())                                    // asks
    runs.get shouldBe 1
    callAsk(b, askParams("inputResponses" -> ujson.Obj("elicit-1" -> answer("yes"))))
    runs.get shouldBe 2                                        // ran AGAIN, from the top

  test("two questions are answered one pass at a time, in order"):
    val b = askingServer(questions = 2)
    val first = callAsk(b, askParams())
    first("result")("inputRequests").obj.keySet shouldBe Set("elicit-1")
    val second = callAsk(b, askParams(
      "inputResponses" -> ujson.Obj("elicit-1" -> answer("a"))))
    second("result")("inputRequests").obj.keySet shouldBe Set("elicit-2")
    val third = callAsk(b, askParams("inputResponses" -> ujson.Obj(
      "elicit-1" -> answer("a"), "elicit-2" -> answer("b"))))
    third("result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    third("result")("content")(0)("text").str shouldBe "\"a\",\"b\""

  test("an explicit key is used instead of the positional one"):
    val b = new McpServerBuilder
    b.setMrtrMode(MrtrMode.Replay)
    b.tool("ask", None, ujson.Obj(), _ =>
      b.elicit("q", ujson.Obj(), key = Some("confirm-delete"))
      ToolHandlerResult(Nil, isError = false))
    callAsk(b, askParams())("result")("inputRequests").obj.keySet shouldBe Set("confirm-delete")

  test("requestState is echoed back so a handler can record its own progress"):
    val js = callAsk(askingServer(), askParams("requestState" -> ujson.Str("step-2")))
    McpProtocol.authorState(Some(js("result")("requestState").str)) shouldBe Some("step-2")

  test("LEGACY is untouched: elicit still blocks on a server-initiated request"):
    // No _meta.protocolVersion. elicit must take the old path -- which, with no
    // client answering, times out and returns Left. What it must NOT do is
    // return input-required: that would change behaviour for existing servers.
    val b = new McpServerBuilder
    b.tool("ask", None, ujson.Obj(), _ =>
      val r = b.elicit("q", ujson.Obj(), timeoutMs = 120L)
      ToolHandlerResult(List(McpProtocol.textContent(
        if r.isLeft then "blocked-and-timed-out" else "answered")), isError = false))
    val js = ujson.read(McpServerCore.handleHttpRequest(b, ujson.Obj(
      "jsonrpc" -> "2.0", "id" -> 78,
      "method"  -> McpProtocol.Method.ToolsCall,
      "params"  -> ujson.Obj("name" -> "ask", "arguments" -> ujson.Obj())).render(),
      "srv", "9.9.9").trim)
    js("result")("content")(0)("text").str shouldBe "blocked-and-timed-out"
    js("result").obj.keySet should not contain "resultType"

  test("resources/read honours the signal too -- three paths, not one"):
    val b = new McpServerBuilder
    b.resource("mem://x", None, None, _ =>
      b.elicit("q", ujson.Obj())
      ResourceHandlerResult("mem://x", Nil))
    val js = ujson.read(McpServerCore.handleHttpRequest(b, ujson.Obj(
      "jsonrpc" -> "2.0", "id" -> 79,
      "method"  -> McpProtocol.Method.ResourcesRead,
      "params"  -> modernParams("uri" -> ujson.Str("mem://x"))).render(), "srv", "9.9.9",
      modernHeaders(McpProtocol.Header.Method -> McpProtocol.Method.ResourcesRead,
                    McpProtocol.Header.Name   -> "mem://x")).trim)
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeInputRequired

  test("prompts/get honours the signal too"):
    val b = new McpServerBuilder
    b.prompt("p", None, Nil, _ =>
      b.elicit("q", ujson.Obj())
      PromptHandlerResult(None, Nil))
    val js = ujson.read(McpServerCore.handleHttpRequest(b, ujson.Obj(
      "jsonrpc" -> "2.0", "id" -> 80,
      "method"  -> McpProtocol.Method.PromptsGet,
      "params"  -> modernParams("name" -> ujson.Str("p"))).render(), "srv", "9.9.9",
      modernHeaders(McpProtocol.Header.Method -> McpProtocol.Method.PromptsGet,
                    McpProtocol.Header.Name   -> "p")).trim)
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeInputRequired

  test("a handler records progress and reads it back on the next pass"):
    // Obligation 1 of 8.5b: without this an author cannot write an idempotent
    // handler even when they want to, because they cannot tell the passes apart.
    val seen = java.util.concurrent.atomic.AtomicReference[Option[String]](None)
    val b = new McpServerBuilder
    b.setMrtrMode(MrtrMode.Replay)
    b.tool("ask", None, ujson.Obj(), _ =>
      seen.set(b.requestState)
      b.setRequestState("row-written")
      b.elicit("confirm?", ujson.Obj())
      ToolHandlerResult(Nil, isError = false))
    val first = callAsk(b, askParams())
    seen.get shouldBe None                                  // first pass: nothing done yet
    McpProtocol.authorState(Some(first("result")("requestState").str)) shouldBe Some("row-written")
    callAsk(b, askParams("requestState" -> ujson.Str("row-written")))
    seen.get shouldBe Some("row-written")                   // second pass: it knows

  test("a handler that keeps no state still round-trips the client's"):
    val out = callAsk(askingServer(), askParams("requestState" -> ujson.Str("opaque")))(
      "result")("requestState").str
    McpProtocol.authorState(Some(out)) shouldBe Some("opaque")

  test("requestState is None on the legacy path, where nothing is ever re-run"):
    val got = java.util.concurrent.atomic.AtomicReference[Option[String]](Some("x"))
    val b = new McpServerBuilder
    b.setMrtrMode(MrtrMode.Replay)
    b.tool("ask", None, ujson.Obj(), _ =>
      got.set(b.requestState)
      b.setRequestState("ignored")                          // a no-op here
      ToolHandlerResult(Nil, isError = false))
    ujson.read(McpServerCore.handleHttpRequest(b, ujson.Obj(
      "jsonrpc" -> "2.0", "id" -> 81, "method" -> McpProtocol.Method.ToolsCall,
      "params"  -> ujson.Obj("name" -> "ask", "arguments" -> ujson.Obj(),
                             "requestState" -> ujson.Str("from-client"))).render(),
      "srv", "9.9.9").trim)
    got.get shouldBe None

  // ── P3c — parked virtual thread, the DEFAULT ───────────────────────────
  //
  // The mirror of the replay block above. There the load-bearing assertion is
  // that the handler runs TWICE; here it is that it runs ONCE. Everything else
  // about the two modes is the same, and that one number is the difference.

  private val parkRuns = java.util.concurrent.atomic.AtomicInteger(0)

  /** Counts entries, and counts how many times the code BEFORE the question
   *  ran — which is the effect an author would have duplicated. */
  private def parkingServer(questions: Int = 1): McpServerBuilder =
    val b = new McpServerBuilder                        // no setMrtrMode: Park is the default
    b.tool("ask", None, ujson.Obj(), _ =>
      parkRuns.incrementAndGet()
      val answers = (1 to questions).map(i =>
        b.elicit(s"question $i", ujson.Obj()) match
          case Right(McpProtocol.ElicitationResult.Accept(c)) => c.render()
          case Right(other)                                   => other.toString
          case Left(e)                                        => s"error:${e.message}")
      ToolHandlerResult(List(McpProtocol.textContent(answers.mkString(","))), isError = false))
    b

  /** Answer the question a previous pass asked, echoing its requestState back
   *  the way a conforming client would. */
  private def answerWith(js: ujson.Value, answers: (String, ujson.Value)*): ujson.Obj =
    askParams(
      "inputResponses" -> ujson.Obj.from(answers),
      "requestState"   -> ujson.Str(js("result")("requestState").str))

  test("Park is the default — a fresh builder parks rather than replays"):
    (new McpServerBuilder).mrtrMode shouldBe MrtrMode.Park

  test("PARK: the handler runs ONCE and continues where it stood"):
    // The whole decision, in one number. Under replay this reads 2.
    parkRuns.set(0)
    val b = parkingServer()
    val asked = callAsk(b, askParams())
    asked("result")("resultType").str shouldBe McpProtocol.ResultTypeInputRequired
    parkRuns.get shouldBe 1
    val done = callAsk(b, answerWith(asked, "elicit-1" -> answer("yes")))
    done("result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    done("result")("content")(0)("text").str should include ("yes")
    parkRuns.get shouldBe 1                       // NOT re-entered

  test("PARK: two questions, still one entry"):
    parkRuns.set(0)
    val b = parkingServer(questions = 2)
    val first  = callAsk(b, askParams())
    first("result")("inputRequests").obj.keySet shouldBe Set("elicit-1")
    val second = callAsk(b, answerWith(first, "elicit-1" -> answer("a")))
    second("result")("inputRequests").obj.keySet shouldBe Set("elicit-2")
    val third  = callAsk(b, answerWith(second, "elicit-1" -> answer("a"), "elicit-2" -> answer("b")))
    third("result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    parkRuns.get shouldBe 1

  test("PARK: a token this process does not hold fails LOUDLY"):
    // This error is why Park can be the default. Silently re-running here would
    // repeat whatever the first pass did before it asked.
    val js = callAsk(parkingServer(), askParams(
      "requestState" -> ujson.Str(ujson.write(ujson.Obj("park" -> "no-such-token")))))
    js("error")("code").num.toInt shouldBe McpProtocol.ErrorCode.InputSessionNotFound
    js("error")("message").str should include ("Start the operation again")

  test("ParkThenReplay: the same lost token re-runs instead, which is why it is opt-in"):
    parkRuns.set(0)
    val b = parkingServer()
    b.setMrtrMode(MrtrMode.ParkThenReplay)
    val js = callAsk(b, askParams(
      "inputResponses" -> ujson.Obj("elicit-1" -> answer("yes")),
      "requestState"   -> ujson.Str(ujson.write(ujson.Obj("park" -> "no-such-token")))))
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    parkRuns.get shouldBe 1                       // it RAN, from the top — a replay

  test("PARK: the author's own state survives the envelope the token rides in"):
    val b = new McpServerBuilder
    b.tool("ask", None, ujson.Obj(), _ =>
      b.setRequestState("row-written")
      b.elicit("q", ujson.Obj())
      ToolHandlerResult(Nil, isError = false))
    val rs = callAsk(b, askParams())("result")("requestState").str
    McpProtocol.parkToken(Some(rs))   should not be empty     // the server's slice
    McpProtocol.authorState(Some(rs)) shouldBe Some("row-written")   // and the author's

  test("PARK: an expired park is evicted, and the client is told so"):
    val b = parkingServer()
    b.setParkTtlMs(0L)                            // expire the moment it is created
    val asked = callAsk(b, askParams())
    asked("result")("resultType").str shouldBe McpProtocol.ResultTypeInputRequired
    Thread.sleep(5)
    callAsk(b, askParams())                       // any new call sweeps the expired ones
    val js = callAsk(b, answerWith(asked, "elicit-1" -> answer("yes")))
    js("error")("code").num.toInt shouldBe McpProtocol.ErrorCode.InputSessionNotFound

  test("PARK: the ceiling refuses a NEW call rather than dropping a half-done one"):
    val b = parkingServer()
    b.setMaxParks(1)
    callAsk(b, askParams())("result")("resultType").str shouldBe
      McpProtocol.ResultTypeInputRequired
    val js = callAsk(b, askParams())
    js("error")("message").str should include ("too many parked handlers")

  test("PARK: cancelling the request drops the park instead of waiting out its TTL"):
    val b = parkingServer()
    val asked = callAsk(b, askParams())
    asked("result")("resultType").str shouldBe McpProtocol.ResultTypeInputRequired
    b.parks.size shouldBe 1
    McpServerCore.handleHttpRequest(b, JsonRpc.encodeNotification(
      McpProtocol.Method.Cancelled, ujson.Obj("requestId" -> 77)), "srv", "9.9.9")
    b.parks.size shouldBe 0
    // and the client is then told plainly, rather than hanging
    callAsk(b, answerWith(asked, "elicit-1" -> answer("yes")))("error")("code").num.toInt shouldBe
      McpProtocol.ErrorCode.InputSessionNotFound

  // ── P5c-1 — Tasks extension, protocol layer ────────────────────────────

  private def ctxWithCaps(caps: ujson.Value): McpProtocol.RequestContext =
    McpProtocol.parseRequestMeta(ujson.Obj("_meta" -> ujson.Obj(
      McpProtocol.MetaKey.ProtocolVersion    -> McpProtocol.ModernProtocolVersion,
      McpProtocol.MetaKey.ClientCapabilities -> caps)))

  test("tasks support is read from THIS request's capabilities"):
    McpProtocol.clientSupportsTasks(ctxWithCaps(ujson.Obj("extensions" ->
      ujson.Obj(McpProtocol.TasksExtension -> ujson.Obj())))) shouldBe true

  test("a client with other extensions but not tasks does NOT support tasks"):
    McpProtocol.clientSupportsTasks(ctxWithCaps(ujson.Obj("extensions" ->
      ujson.Obj("com.example/other" -> ujson.Obj())))) shouldBe false

  test("no extensions block at all is not support"):
    McpProtocol.clientSupportsTasks(ctxWithCaps(ujson.Obj())) shouldBe false

  test("the missing-capability payload names the extension, at -32021 not -32003"):
    // The extension's own page shows -32003. That value is in the -32000..-32019
    // LEGACY sub-range the core spec forbids new implementations from using, and
    // the core spec is where the allocation policy lives, so it wins.
    McpProtocol.ErrorCode.MissingRequiredClientCapability shouldBe -32021
    McpProtocol.missingTasksCapability()("requiredCapabilities")("extensions")
      .obj.keySet shouldBe Set(McpProtocol.TasksExtension)

  test("a task handle is a resultType the CORE set does not contain"):
    val h = McpProtocol.createTaskResult("t-1", pollIntervalMs = Some(1000L))
    h("resultType").str shouldBe "task"
    h("taskId").str     shouldBe "t-1"
    h("status").str     shouldBe McpProtocol.TaskWorking
    h("pollIntervalMs").num.toLong shouldBe 1000L
    Set(McpProtocol.ResultTypeComplete,
        McpProtocol.ResultTypeInputRequired) should not contain McpProtocol.ResultTypeTask

  test("three statuses are terminal and two are not — the polling contract"):
    McpProtocol.TerminalTaskStatuses shouldBe
      Set(McpProtocol.TaskCompleted, McpProtocol.TaskFailed, McpProtocol.TaskCancelled)
    McpProtocol.isTerminalTaskStatus(McpProtocol.TaskWorking)       shouldBe false
    McpProtocol.isTerminalTaskStatus(McpProtocol.TaskInputRequired) shouldBe false

  private def task(status: String,
                   result: Option[ujson.Value] = None,
                   error: Option[JsonRpc.Error] = None,
                   inputs: Map[String, McpProtocol.InputRequest] = Map.empty) =
    McpProtocol.taskResult("t-1", status, "2026-08-13T00:00:00Z", "2026-08-13T00:00:01Z",
      result = result, error = error, inputRequests = inputs)

  test("a completed task carries its result, and is refused without one"):
    task(McpProtocol.TaskCompleted, result = Some(ujson.Obj("x" -> 1)))(
      "result")("x").num.toInt shouldBe 1
    an [IllegalArgumentException] should be thrownBy task(McpProtocol.TaskCompleted)

  test("a failed task carries its error, and is refused without one"):
    task(McpProtocol.TaskFailed,
      error = Some(JsonRpc.Error(-32000, "boom")))("error")("message").str shouldBe "boom"
    an [IllegalArgumentException] should be thrownBy task(McpProtocol.TaskFailed)

  test("an input_required task must say WHAT input it needs"):
    an [IllegalArgumentException] should be thrownBy task(McpProtocol.TaskInputRequired)
    task(McpProtocol.TaskInputRequired, inputs = Map("approval" ->
      McpProtocol.InputRequest(McpProtocol.Method.ElicitationCreate, ujson.Obj())))(
      "inputRequests")("approval")("method").str shouldBe McpProtocol.Method.ElicitationCreate

  test("a result and an error together are refused — no client could read it"):
    an [IllegalArgumentException] should be thrownBy task(McpProtocol.TaskWorking,
      result = Some(ujson.Obj()), error = Some(JsonRpc.Error(-32000, "boom")))

  test("tasks/update inputs are unwrapped from their {value: …} envelope"):
    McpProtocol.parseTaskInputs(ujson.Obj("inputs" -> ujson.Obj(
      "approval" -> ujson.Obj("value" -> ujson.Str("yes"))))) shouldBe
      Map("approval" -> (ujson.Str("yes"): ujson.Value))

  test("an input sent WITHOUT the envelope is taken as the value itself"):
    // Tolerant on the way in, because the wrapper is the one place the tasks
    // shape differs from MRTR's and a client is likely to send either.
    McpProtocol.parseTaskInputs(ujson.Obj("inputs" -> ujson.Obj(
      "approval" -> ujson.Str("yes")))) shouldBe
      Map("approval" -> (ujson.Str("yes"): ujson.Value))

  test("taskId and inputs survive absent or malformed params"):
    McpProtocol.parseTaskId(ujson.Obj("taskId" -> "t-9")) shouldBe Some("t-9")
    McpProtocol.parseTaskId(ujson.Str("nonsense"))        shouldBe None
    McpProtocol.parseTaskInputs(ujson.Str("nonsense"))    shouldBe Map.empty

  test("there is deliberately no tasks/list — a task id is a capability"):
    val methods = Set(McpProtocol.Method.TasksGet, McpProtocol.Method.TasksUpdate,
                      McpProtocol.Method.TasksCancel)
    methods should contain ("tasks/get")
    methods.exists(_.endsWith("/list")) shouldBe false

  // ── P5c-2 — Tasks wired to the server ──────────────────────────────────

  /** A client that HAS advertised the extension. Everything about tasks turns
   *  on this, so it is explicit in every case rather than hidden in a default. */
  private def taskParams(fields: (String, ujson.Value)*): ujson.Obj =
    val p = ujson.Obj("_meta" -> ujson.Obj(
      McpProtocol.MetaKey.ProtocolVersion    -> McpProtocol.ModernProtocolVersion,
      McpProtocol.MetaKey.ClientCapabilities -> ujson.Obj(
        "extensions" -> ujson.Obj(McpProtocol.TasksExtension -> ujson.Obj()))))
    fields.foreach((k, v) => p(k) = v)
    p

  private def taskHeaders(method: String, name: Option[String]) =
    Map(McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
        McpProtocol.Header.Method          -> method) ++
      name.map(McpProtocol.Header.Name -> _)

  private def post(b: McpServerBuilder, method: String, params: ujson.Obj,
                   name: Option[String] = None): ujson.Value =
    ujson.read(McpServerCore.handleHttpRequest(b, ujson.Obj(
      "jsonrpc" -> "2.0", "id" -> 90, "method" -> method, "params" -> params).render(),
      "srv", "9.9.9", taskHeaders(method, name)).trim)

  private def getTask(b: McpServerBuilder, taskId: String): ujson.Value =
    post(b, McpProtocol.Method.TasksGet, taskParams("taskId" -> ujson.Str(taskId)))

  private val taskRuns = java.util.concurrent.atomic.AtomicInteger(0)

  /** A tool that becomes a task, then finishes when the latch is released. */
  private def taskServer(gate: java.util.concurrent.CountDownLatch,
                         asks: Boolean = false): McpServerBuilder =
    val b = new McpServerBuilder
    b.tool("slow", None, ujson.Obj(), _ =>
      taskRuns.incrementAndGet()
      b.asTask()
      if asks then b.elicit("approve?", ujson.Obj())
      gate.await()
      ToolHandlerResult(List(McpProtocol.textContent("finished")), isError = false))
    b

  private def callSlow(b: McpServerBuilder): ujson.Value =
    post(b, McpProtocol.Method.ToolsCall,
      taskParams("name" -> ujson.Str("slow"), "arguments" -> ujson.Obj()), Some("slow"))

  test("asTask hands back a handle immediately and the call does not block"):
    val gate = java.util.concurrent.CountDownLatch(1)
    val b = taskServer(gate)
    val js = callSlow(b)                      // returns while the handler waits on the gate
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeTask
    js("result")("status").str     shouldBe McpProtocol.TaskWorking
    js("result")("taskId").str     should not be empty
    js("result")("pollIntervalMs").num.toLong shouldBe 1000L
    gate.countDown()

  test("a task is polled to a terminal status, and carries its result there"):
    val gate = java.util.concurrent.CountDownLatch(1)
    val b = taskServer(gate)
    val tid = callSlow(b)("result")("taskId").str
    getTask(b, tid)("result")("status").str shouldBe McpProtocol.TaskWorking
    gate.countDown()
    eventuallyCond(getTask(b, tid)("result")("status").str == McpProtocol.TaskCompleted)
    val done = getTask(b, tid)("result")
    done("result")("content")(0)("text").str shouldBe "finished"
    done("createdAt").str     should not be empty
    done("lastUpdatedAt").str should not be empty

  test("the handler runs ONCE for the whole life of a task"):
    taskRuns.set(0)
    val gate = java.util.concurrent.CountDownLatch(1)
    val b = taskServer(gate)
    val tid = callSlow(b)("result")("taskId").str
    getTask(b, tid); getTask(b, tid); getTask(b, tid)      // polling is not re-running
    gate.countDown()
    eventuallyCond(getTask(b, tid)("result")("status").str == McpProtocol.TaskCompleted)
    taskRuns.get shouldBe 1

  test("a task that needs input says so, and tasks/update feeds it"):
    val gate = java.util.concurrent.CountDownLatch(0)   // already open
    val b = taskServer(gate, asks = true)
    val tid = callSlow(b)("result")("taskId").str
    eventuallyCond(getTask(b, tid)("result")("status").str == McpProtocol.TaskInputRequired)
    getTask(b, tid)("result")("inputRequests")("elicit-1")("method").str shouldBe
      McpProtocol.Method.ElicitationCreate
    val ack = post(b, McpProtocol.Method.TasksUpdate, taskParams(
      "taskId" -> ujson.Str(tid),
      "inputs" -> ujson.Obj("elicit-1" -> ujson.Obj("value" ->
        ujson.Obj("action" -> "accept", "content" -> ujson.Str("ok"))))))
    ack("result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    eventuallyCond(getTask(b, tid)("result")("status").str == McpProtocol.TaskCompleted)

  test("answering a task that asked nothing is refused, not queued"):
    // Queued, it would be consumed by the NEXT question and pair an answer
    // with the wrong prompt.
    val gate = java.util.concurrent.CountDownLatch(1)
    val b = taskServer(gate)
    val tid = callSlow(b)("result")("taskId").str
    val js = post(b, McpProtocol.Method.TasksUpdate, taskParams(
      "taskId" -> ujson.Str(tid), "inputs" -> ujson.Obj("elicit-1" -> ujson.Obj())))
    js("error")("code").num.toInt shouldBe JsonRpc.ErrorCode.InvalidParams
    js("error")("message").str should include ("not awaiting input")
    gate.countDown()

  test("cancel marks the task, and a later poll reads 'cancelled' not 'unknown'"):
    val gate = java.util.concurrent.CountDownLatch(1)
    val b = taskServer(gate)
    val tid = callSlow(b)("result")("taskId").str
    post(b, McpProtocol.Method.TasksCancel, taskParams("taskId" -> ujson.Str(tid)))(
      "result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    getTask(b, tid)("result")("status").str shouldBe McpProtocol.TaskCancelled
    gate.countDown()

  test("an unknown task id is -32602, the code this revision uses for that"):
    val js = getTask(new McpServerBuilder, "no-such-task")
    js("error")("code").num.toInt shouldBe JsonRpc.ErrorCode.InvalidParams

  test("an MRTR park is NOT addressable as a task, and says nothing more"):
    // Confirming that the token exists would help someone who guessed it.
    val b = parkingServer()
    val asked = callAsk(b, askParams())
    val token = McpProtocol.parkToken(Some(asked("result")("requestState").str)).get
    val js = getTask(b, token)
    js("error")("code").num.toInt shouldBe JsonRpc.ErrorCode.InvalidParams
    js("error")("message").str should include ("unknown or expired task")

  test("tasks are not served on the legacy path — that revision has no such method"):
    val js = ujson.read(McpServerCore.handleHttpRequest(new McpServerBuilder, ujson.Obj(
      "jsonrpc" -> "2.0", "id" -> 91, "method" -> McpProtocol.Method.TasksGet,
      "params"  -> ujson.Obj("taskId" -> "t-1")).render(), "srv", "9.9.9").trim)
    js("error")("code").num.toInt shouldBe JsonRpc.ErrorCode.MethodNotFound

  test("a client that never advertised the extension gets a BLOCKING call, not a task"):
    // asTask() degrades rather than erroring: emitting resultType 'task' to a
    // client that never said it understands one is the protocol error.
    val gate = java.util.concurrent.CountDownLatch(0)
    val b = taskServer(gate)
    val js = callAsk2(b)
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    js("result")("content")(0)("text").str shouldBe "finished"

  private def callAsk2(b: McpServerBuilder): ujson.Value =
    ujson.read(McpServerCore.handleHttpRequest(b, ujson.Obj(
      "jsonrpc" -> "2.0", "id" -> 92, "method" -> McpProtocol.Method.ToolsCall,
      "params"  -> modernParams("name" -> ujson.Str("slow"), "arguments" -> ujson.Obj())
      ).render(), "srv", "9.9.9",
      modernHeaders(McpProtocol.Header.Name -> "slow")).trim)

  // ── notifications/tasks — the optional push ────────────────────────────

  test("the filter carries taskIds and round-trips them"):
    val f = McpProtocol.parseNotificationFilter(ujson.Obj("notifications" ->
      ujson.Obj("taskIds" -> ujson.Arr("t-1", "t-2"))))
    f.taskIds shouldBe List("t-1", "t-2")
    f.isEmpty shouldBe false
    McpProtocol.parseNotificationFilter(
      ujson.Obj("notifications" -> f.toJson)).taskIds shouldBe List("t-1", "t-2")

  test("the filter admits the task it was given and no other"):
    val f = McpProtocol.NotificationFilter(taskIds = List("t-1"))
    f.admits(McpProtocol.Method.TasksNotification, Some("t-1")) shouldBe true
    f.admits(McpProtocol.Method.TasksNotification, Some("t-2")) shouldBe false
    f.admits(McpProtocol.Method.TasksNotification, None)        shouldBe false

  test("a subscriber that named the task is pushed its state changes"):
    val gate = java.util.concurrent.CountDownLatch(1)
    val b    = taskServer(gate)
    val tid  = callSlow(b)("result")("taskId").str
    val sink = new Sink
    b.addListenSubscriber(sink.write,
      McpProtocol.NotificationFilter(taskIds = List(tid)), ujson.Num(5))
    gate.countDown()
    eventuallyCond(sink.methods.contains(McpProtocol.Method.TasksNotification))
    val pushed = sink.frames.toList.filter(f =>
      f.obj.get("method").flatMap(_.strOpt).contains(McpProtocol.Method.TasksNotification))
    // "identical to what tasks/get would have returned" — same renderer, so the
    // push carries the finished result and not merely a status word.
    pushed.last("params")("status").str shouldBe McpProtocol.TaskCompleted
    pushed.last("params")("result")("content")(0)("text").str shouldBe "finished"

  test("a subscriber that named a DIFFERENT task is pushed nothing"):
    val gate = java.util.concurrent.CountDownLatch(1)
    val b    = taskServer(gate)
    callSlow(b)
    val sink = new Sink
    b.addListenSubscriber(sink.write,
      McpProtocol.NotificationFilter(taskIds = List("someone-elses-task")), ujson.Num(6))
    gate.countDown()
    Thread.sleep(120)
    sink.methods should not contain McpProtocol.Method.TasksNotification

  test("a LEGACY subscriber is pushed nothing — it opted into no extension"):
    // A stdio client that never sent subscriptions/listen has a filter of None
    // and otherwise receives every notification byte-identically. An
    // extension's notifications are the exception, and this is why.
    val gate = java.util.concurrent.CountDownLatch(1)
    val b    = taskServer(gate)
    callSlow(b)
    val sink = new Sink
    b.addSubscriber(sink.write)                       // no filter: the legacy shape
    gate.countDown()
    Thread.sleep(120)
    sink.methods should not contain McpProtocol.Method.TasksNotification

  test("cancelling an ALREADY finished task does not rewrite it as cancelled"):
    // The other side of write-once, and deterministic where the race is not:
    // the task is terminal before tasks/cancel is ever sent. Reordering cancel
    // to claim the status first must not let it claim one already taken.
    val gate = java.util.concurrent.CountDownLatch(0)      // finishes at once
    val b    = taskServer(gate)
    val tid  = callSlow(b)("result")("taskId").str
    eventuallyCond(getTask(b, tid)("result")("status").str == McpProtocol.TaskCompleted)
    post(b, McpProtocol.Method.TasksCancel, taskParams("taskId" -> ujson.Str(tid)))
    getTask(b, tid)("result")("status").str shouldBe McpProtocol.TaskCompleted

  // ── 2025-06-18: `context` on completion/complete ───────────────────────

  test("completion context is parsed, and its absence is not an error"):
    McpProtocol.parseCompletionContext(ujson.Obj("context" ->
      ujson.Obj("arguments" -> ujson.Obj("owner" -> "acme")))) shouldBe Map("owner" -> "acme")
    // A 2025-03-26 client never sends it; that is a completion, not a fault.
    McpProtocol.parseCompletionContext(ujson.Obj())          shouldBe Map.empty
    McpProtocol.parseCompletionContext(ujson.Str("nonsense")) shouldBe Map.empty

  test("a completion handler can read what the client already resolved"):
    val b = new McpServerBuilder
    b.prompt("deploy", None, Nil, _ => PromptHandlerResult(None, Nil))
    b.completionForPrompt("deploy", "repo", partial =>
      // The whole point: the answer DEPENDS on the earlier argument.
      b.completionContext.get("owner") match
        case Some(o) => List(s"$o/$partial-one", s"$o/$partial-two")
        case None    => List("no-context"))
    def complete(ctx: Option[ujson.Value]): List[String] =
      val params = ujson.Obj(
        "ref"      -> ujson.Obj("type" -> "ref/prompt", "name" -> "deploy"),
        "argument" -> ujson.Obj("name" -> "repo", "value" -> "svc"))
      ctx.foreach(c => params("context") = c)
      ujson.read(McpServerCore.handleHttpRequest(b, ujson.Obj(
        "jsonrpc" -> "2.0", "id" -> 60,
        "method"  -> McpProtocol.Method.CompletionComplete,
        "params"  -> params).render(), "srv", "9.9.9").trim)(
        "result")("completion")("values").arr.toList.map(_.str)

    complete(Some(ujson.Obj("arguments" -> ujson.Obj("owner" -> "acme")))) shouldBe
      List("acme/svc-one", "acme/svc-two")
    // and the same handler, same server, with no context — so the case cannot
    // pass by always returning the same list.
    complete(None) shouldBe List("no-context")

  test("the context does not leak past the handler that was given it"):
    val b = new McpServerBuilder
    b.completionContext shouldBe Map.empty
    b.prompt("p", None, Nil, _ => PromptHandlerResult(None, Nil))
    b.completionForPrompt("p", "a", _ => Nil)
    McpServerCore.handleHttpRequest(b, ujson.Obj(
      "jsonrpc" -> "2.0", "id" -> 61,
      "method"  -> McpProtocol.Method.CompletionComplete,
      "params"  -> ujson.Obj(
        "ref"      -> ujson.Obj("type" -> "ref/prompt", "name" -> "p"),
        "argument" -> ujson.Obj("name" -> "a", "value" -> ""),
        "context"  -> ujson.Obj("arguments" -> ujson.Obj("x" -> "y")))).render(),
      "srv", "9.9.9")
    b.completionContext shouldBe Map.empty

  // ── RFC 8707 Resource Indicators (2025-06-18 makes these a client MUST) ──

  test("the canonical resource URI lowercases, drops the default port and the fragment"):
    import scalascript.oauth.OAuthClient.canonicalResourceUri as canon
    canon("HTTPS://MCP.Example.COM/mcp")      shouldBe "https://mcp.example.com/mcp"
    canon("https://h:443/mcp")                shouldBe "https://h/mcp"
    canon("http://h:80/mcp")                  shouldBe "http://h/mcp"
    canon("https://h:8443/mcp")               shouldBe "https://h:8443/mcp"
    // RFC 8707 forbids a fragment outright.
    canon("https://h/mcp#frag")               shouldBe "https://h/mcp"
    // `https://h/` and `https://h` name one resource; an AS comparing strings
    // would not know that, so we pick one.
    canon("https://h/")                       shouldBe "https://h"
    canon("https://h")                        shouldBe "https://h"

  test("the PATH is kept, because two servers can share a host"):
    import scalascript.oauth.OAuthClient.canonicalResourceUri as canon
    canon("https://h/team-a/mcp") should not be canon("https://h/team-b/mcp")

  test("unparseable input is passed through rather than failing a login"):
    import scalascript.oauth.OAuthClient.canonicalResourceUri as canon
    canon("not a uri at all") shouldBe "not a uri at all"
    canon("   ")              shouldBe ""

  test("the authorization URL carries `resource`, canonicalised, and omits it when unset"):
    def url(res: Option[String]) = scalascript.oauth.OAuthClient.authorizationUrl(
      "https://as.example/authorize", "cid", "https://app/cb", Set("mcp"), "st",
      scalascript.oauth.OAuthClient.PkcePair("v", "c", "S256"), res)
    url(Some("HTTPS://MCP.Example.COM/mcp:443".replace(":443", ""))) should include (
      "resource=https%3A%2F%2Fmcp.example.com%2Fmcp")
    url(None) should not include "resource="

  test("the token exchange and the refresh both carry it — the last step is where the swap lands"):
    // Driven through a recording endpoint rather than asserted on a string, so
    // this measures the form that is SENT.
    val seen = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val server = com.sun.net.httpserver.HttpServer.create(
      java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/token", { ex =>
      seen.add(new String(ex.getRequestBody.readAllBytes(), "UTF-8"))
      val body = ujson.write(ujson.Obj("access_token" -> "t", "token_type" -> "Bearer"))
                   .getBytes("UTF-8")
      ex.getResponseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(200, body.length.toLong)
      ex.getResponseBody.write(body); ex.getResponseBody.close()
    })
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
    server.start()
    try
      val ep = s"http://127.0.0.1:${server.getAddress.getPort}/token"
      scalascript.oauth.OAuthClient.exchangeAuthorizationCode(
        ep, "cid", "https://app/cb", "code", "verifier", None, 3000L, Some("https://H/mcp"))
      scalascript.oauth.OAuthClient.refresh(
        ep, "cid", "rt", Set.empty, None, 3000L, Some("https://H/mcp"))
      val forms = seen.toArray.map(_.toString).toList
      forms should have size 2
      // canonicalised on the way out: the host was upper-case going in.
      all (forms) should include ("resource=https%3A%2F%2Fh%2Fmcp")
    finally server.stop(0)

