package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** v1.17.x — MCP OAuth 2.1 Resource Server layer.  Tests cover the
 *  transport-agnostic core: token validator plumbing, HMAC issuer +
 *  validator, header parsing, WWW-Authenticate assembly, RFC 9728
 *  metadata, and the `authorizeHttp` decision tree. */
class McpAuthTest extends AnyFunSuite with Matchers:

  test("HMAC token round trip: issue → validate → matches claims"):
    val secret = "topsecret"
    val token  = McpAuth.issueHmacToken(secret, "alice", Set("read", "write"), 60L)
    McpAuth.hmacValidator(secret)(token) match
      case McpAuth.AuthResult.Valid(c) =>
        c.subject shouldBe "alice"
        c.scopes  shouldBe Set("read", "write")
        c.hasScope("read")  shouldBe true
        c.hasScope("admin") shouldBe false
      case other => fail(s"expected Valid, got $other")

  test("HMAC validator rejects expired tokens"):
    // -120s — well past the 60s clock-skew tolerance (Iter JJ)
    val token = McpAuth.issueHmacToken("s", "x", Set.empty, -120L)
    McpAuth.hmacValidator("s")(token) match
      case McpAuth.AuthResult.Invalid(code, _) => code shouldBe "invalid_token"
      case other => fail(s"expected Invalid, got $other")

  test("HMAC validator rejects tampered signature"):
    val token   = McpAuth.issueHmacToken("s", "x", Set.empty, 60L)
    val tampered = token.dropRight(3) + "XYZ"
    McpAuth.hmacValidator("s")(tampered) match
      case McpAuth.AuthResult.Invalid(code, _) => code shouldBe "invalid_token"
      case other => fail(s"expected Invalid, got $other")

  test("HMAC validator rejects wrong secret"):
    val token = McpAuth.issueHmacToken("right-secret", "x", Set.empty, 60L)
    McpAuth.hmacValidator("wrong-secret")(token) match
      case McpAuth.AuthResult.Invalid(_, _) => succeed
      case other => fail(s"expected Invalid, got $other")

  test("HMAC validator rejects malformed token"):
    McpAuth.hmacValidator("s")("garbage")             shouldBe a[McpAuth.AuthResult.Invalid]
    McpAuth.hmacValidator("s")("only.two")            shouldBe a[McpAuth.AuthResult.Invalid]
    McpAuth.hmacValidator("s")("")                    shouldBe a[McpAuth.AuthResult.Invalid]
    McpAuth.hmacValidator("s")("a.b.c.d")             shouldBe a[McpAuth.AuthResult.Invalid]

  test("extractBearer reads case-insensitive Authorization header"):
    McpAuth.extractBearer(Map("authorization" -> "Bearer abc")) shouldBe Right("abc")
    McpAuth.extractBearer(Map("AUTHORIZATION" -> "Bearer abc")) shouldBe Right("abc")
    McpAuth.extractBearer(Map("Authorization" -> "bearer xyz")) shouldBe Right("xyz")  // scheme lowercase too

  test("extractBearer rejects missing / wrong scheme / empty token"):
    McpAuth.extractBearer(Map.empty)                                shouldBe Left("invalid_request")
    McpAuth.extractBearer(Map("Authorization" -> "Basic abc"))      shouldBe Left("invalid_request")
    McpAuth.extractBearer(Map("Authorization" -> "Bearer "))        shouldBe Left("invalid_request")
    McpAuth.extractBearer(Map("Authorization" -> "Bearer  "))       shouldBe Left("invalid_request")

  test("wwwAuthenticate assembles the spec-shaped header"):
    val h = McpAuth.wwwAuthenticate("mcp", "invalid_token", Some("expired"), Some("read"))
    h should include ("""Bearer realm="mcp"""")
    h should include ("""error="invalid_token"""")
    h should include ("""error_description="expired"""")
    h should include ("""scope="read"""")

  test("authorizeHttp without validator → Allowed(None)"):
    val builder = new McpServerBuilder
    McpServerCore.authorizeHttp(builder, Right("any-token")) match
      case McpServerCore.AuthOutcome.Allowed(None) => succeed
      case other => fail(s"expected Allowed(None), got $other")

  test("authorizeHttp with validator: valid token → Allowed(Some(claims))"):
    val builder = new McpServerBuilder
    builder.setTokenValidator(Some(McpAuth.hmacValidator("sec")))
    val token = McpAuth.issueHmacToken("sec", "u", Set("a"), 60L)
    McpServerCore.authorizeHttp(builder, Right(token)) match
      case McpServerCore.AuthOutcome.Allowed(Some(c)) => c.subject shouldBe "u"
      case other => fail(s"expected Allowed(Some), got $other")

  test("authorizeHttp: invalid token → Reject(invalid_token)"):
    val builder = new McpServerBuilder
    builder.setTokenValidator(Some(McpAuth.hmacValidator("sec")))
    McpServerCore.authorizeHttp(builder, Right("garbage")) match
      case McpServerCore.AuthOutcome.Reject(code, _) => code shouldBe "invalid_token"
      case other => fail(s"expected Reject, got $other")

  test("authorizeHttp: missing token → Reject(invalid_request)"):
    val builder = new McpServerBuilder
    builder.setTokenValidator(Some(McpAuth.hmacValidator("sec")))
    McpServerCore.authorizeHttp(builder, Left("invalid_request")) match
      case McpServerCore.AuthOutcome.Reject(code, _) => code shouldBe "invalid_request"
      case other => fail(s"expected Reject, got $other")

  test("withAuth binds + restores currentAuth thread-local"):
    val builder = new McpServerBuilder
    builder.currentAuth shouldBe None
    val claims = McpAuth.AuthClaims("bob", Set("x"))
    builder.withAuth(Some(claims)) {
      builder.currentAuth shouldBe Some(claims)
    }
    builder.currentAuth shouldBe None  // restored

  test("withAuth(None) leaves currentAuth as None (no-op binding)"):
    val builder = new McpServerBuilder
    builder.withAuth(None) {
      builder.currentAuth shouldBe None
    }

  test("ProtectedResourceMetadata.toJson emits spec field names"):
    val m = McpAuth.ProtectedResourceMetadata(
      resource = "https://api.example.com/mcp",
      authorizationServers = List("https://auth.example.com"),
      scopesSupported = List("read", "write"),
      resourceDocumentation = Some("https://docs.example.com")
    )
    val js = m.toJson
    js("resource").str                            shouldBe "https://api.example.com/mcp"
    js("authorization_servers").arr.head.str      shouldBe "https://auth.example.com"
    js("scopes_supported").arr.map(_.str).toList  shouldBe List("read", "write")
    js("bearer_methods_supported").arr.head.str   shouldBe "header"
    js("resource_documentation").str              shouldBe "https://docs.example.com"

  test("ProtectedResourceMetadata.toJson omits empty optional lists"):
    val m = McpAuth.ProtectedResourceMetadata(resource = "https://r")
    val js = m.toJson
    js.obj.contains("authorization_servers") shouldBe false
    js.obj.contains("scopes_supported")      shouldBe false
    js.obj.contains("resource_documentation") shouldBe false
    js("bearer_methods_supported").arr.head.str shouldBe "header"  // default applied

  test("authEnabled flips when validator is registered"):
    val builder = new McpServerBuilder
    builder.authEnabled shouldBe false
    builder.setTokenValidator(Some(_ =>
      McpAuth.AuthResult.Invalid("invalid_token", "noop")))
    builder.authEnabled shouldBe true
    builder.setTokenValidator(None)
    builder.authEnabled shouldBe false
