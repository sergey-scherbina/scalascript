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
    for m <- McpProtocol.RemovedInModern.toList.sorted do
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
