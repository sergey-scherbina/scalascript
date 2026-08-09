package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — generic Resource Server SDK.  Pure RS decision logic
 *  surface that any HTTP service can plug into route handlers. */
class OAuthGuardTest extends AnyFunSuite with Matchers:

  private def validToken(secret: String, scopes: Set[String] = Set("read")): String =
    OAuth.issueHmacToken(secret, "alice", scopes, 60L)

  // ─── Allow path ──────────────────────────────────────────────────

  test("check: valid token with sufficient scopes → Allow(claims)"):
    val v = OAuth.hmacValidator("s")
    val tok = validToken("s", Set("read", "write"))
    OAuthGuard.check(
      headers = Map("Authorization" -> s"Bearer $tok"),
      validator = v,
      requiredScopes = Set("read")
    ) match
      case OAuthGuard.GuardDecision.Allow(claims) =>
        claims.subject     shouldBe "alice"
        claims.hasScope("read") shouldBe true
      case other => fail(s"expected Allow, got $other")

  test("check: empty requiredScopes always satisfied"):
    val v   = OAuth.hmacValidator("s")
    val tok = validToken("s", Set.empty)
    OAuthGuard.check(Map("Authorization" -> s"Bearer $tok"), v) match
      case OAuthGuard.GuardDecision.Allow(_) => succeed
      case other => fail(s"got $other")

  // ─── Reject paths ────────────────────────────────────────────────

  test("check: missing Authorization header → 401 invalid_request"):
    val v = OAuth.hmacValidator("s")
    OAuthGuard.check(Map.empty, v, Set("read"), realm = "myapi") match
      case OAuthGuard.GuardDecision.Deny(OAuthRoutes.RouteOutcome.Json(401, js, hdrs)) =>
        js("error").str shouldBe "invalid_request"
        hdrs("WWW-Authenticate") should include ("""realm="myapi"""")
      case other => fail(s"got $other")

  test("check: garbage token → 401 invalid_token"):
    val v = OAuth.hmacValidator("s")
    OAuthGuard.check(Map("Authorization" -> "Bearer garbage"), v) match
      case OAuthGuard.GuardDecision.Deny(OAuthRoutes.RouteOutcome.Json(401, js, _)) =>
        js("error").str shouldBe "invalid_token"
      case other => fail(s"got $other")

  test("check: missing required scopes → 403 insufficient_scope"):
    val v   = OAuth.hmacValidator("s")
    val tok = validToken("s", Set("read"))  // token has 'read' only
    OAuthGuard.check(
      headers        = Map("Authorization" -> s"Bearer $tok"),
      validator      = v,
      requiredScopes = Set("read", "admin"),
      realm          = "myapi"
    ) match
      case OAuthGuard.GuardDecision.Deny(OAuthRoutes.RouteOutcome.Json(403, js, hdrs)) =>
        js("error").str shouldBe "insufficient_scope"
        js("scope").str shouldBe "admin read"  // sorted advertisement
        js("error_description").str should include ("admin")
        hdrs("WWW-Authenticate") should include ("insufficient_scope")
        hdrs("WWW-Authenticate") should include ("admin read")
      case other => fail(s"got $other")

  test("check: bearer scheme is case-insensitive but other schemes rejected"):
    val v   = OAuth.hmacValidator("s")
    val tok = validToken("s")
    OAuthGuard.check(Map("authorization" -> s"bearer $tok"), v) match
      case OAuthGuard.GuardDecision.Allow(_) => succeed
      case other => fail(s"lowercase: got $other")
    OAuthGuard.check(Map("Authorization" -> s"Basic $tok"), v) match
      case OAuthGuard.GuardDecision.Deny(OAuthRoutes.RouteOutcome.Json(401, js, _)) =>
        js("error").str shouldBe "invalid_request"
      case other => fail(s"basic scheme: got $other")

  // ─── End-to-end against an AS-issued token ───────────────────────

  test("check: works with AuthServer-issued tokens"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://auth.x", signingSecret = "k",
      supportedScopes = Set("read")))
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential))
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("read"))).asInstanceOf[TokenOutcome.Issued].response.accessToken
    OAuthGuard.check(
      Map("Authorization" -> s"Bearer $token"),
      as.tokenValidator,
      Set("read")) match
      case OAuthGuard.GuardDecision.Allow(c) => c.subject shouldBe "svc"
      case other => fail(s"got $other")

  test("check: revoked AS tokens fail"):
    val as = new AuthServer(AuthServerConfig("https://auth.x", "k", supportedScopes = Set("read")))
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential))
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("read"))).asInstanceOf[TokenOutcome.Issued].response.accessToken
    as.revokeToken(token)
    OAuthGuard.check(
      Map("Authorization" -> s"Bearer $token"),
      as.tokenValidator) match
      case OAuthGuard.GuardDecision.Deny(OAuthRoutes.RouteOutcome.Json(401, _, _)) => succeed
      case other => fail(s"got $other")

  // ─── allows shortcut ─────────────────────────────────────────────

  test("allows: boolean shortcut"):
    val v   = OAuth.hmacValidator("s")
    val tok = validToken("s", Set("read"))
    OAuthGuard.allows(
      Map("Authorization" -> s"Bearer $tok"), v, Set("read")) shouldBe true
    OAuthGuard.allows(
      Map("Authorization" -> s"Bearer $tok"), v, Set("write")) shouldBe false
    OAuthGuard.allows(Map.empty, v) shouldBe false

  // ─── helper outcomes ─────────────────────────────────────────────

  test("unauthorized builds RFC 6750-shaped response"):
    OAuthGuard.unauthorized("myapi", "invalid_token", "expired") match
      case OAuthRoutes.RouteOutcome.Json(401, js, hdrs) =>
        js("error").str           shouldBe "invalid_token"
        js("error_description").str shouldBe "expired"
        hdrs("WWW-Authenticate") should include ("invalid_token")
      case other => fail(s"got $other")

  test("insufficientScope advertises required scopes in WWW-Authenticate"):
    OAuthGuard.insufficientScope("api", Set("read", "write"), Set("write")) match
      case OAuthRoutes.RouteOutcome.Json(403, js, hdrs) =>
        js("scope").str shouldBe "read write"
        hdrs("WWW-Authenticate") should include ("read write")
      case other => fail(s"got $other")
