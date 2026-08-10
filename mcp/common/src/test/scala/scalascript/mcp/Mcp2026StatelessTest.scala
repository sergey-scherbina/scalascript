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
    // 2025-06-18 is deliberately absent: its `MCP-Protocol-Version` header and
    // `completion/complete` `context` field are not implemented (measured 0
    // occurrences, 2026-08-09). Advertising it would trade a four-revision
    // under-claim for an over-claim. P2 closes both, then this changes.
    McpProtocol.SupportedProtocolVersions should not contain "2025-06-18"
    McpProtocol.ProtocolVersion shouldBe "2025-03-26"
    McpProtocol.SupportedProtocolVersions.head shouldBe McpProtocol.ModernProtocolVersion

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
