package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — standalone OAuth 2.1 Authorization Server.  Verified end-
 *  to-end without any MCP coupling: the AS is reusable for any HTTP
 *  service. */
class OAuthAuthServerTest extends AnyFunSuite with Matchers:

  private def cfg(extra: AuthServerConfig => AuthServerConfig = identity): AuthServerConfig =
    extra(AuthServerConfig(
      issuer        = "https://auth.example.com",
      signingSecret = "test-secret",
      supportedScopes = Set("read", "write")
    ))

  // ─── Authorization endpoint ────────────────────────────────────────

  test("issueAuthorizationCode: happy path returns CodeRedirect with the issued code"):
    val as = new AuthServer(cfg())
    as.clients.register(Client(
      id = "c1", secret = None,
      redirectUris = Set("http://localhost/cb"),
      scopes = Set("read", "write"),
      clientType = ClientType.Public
    ))
    val out = as.issueAuthorizationCode(
      AuthorizationRequest(
        responseType  = "code",
        clientId      = "c1",
        redirectUri   = "http://localhost/cb",
        scope         = Set("read"),
        state         = Some("xyz"),
        codeChallenge = Some(OAuth.pkceS256("verifier-1234567890-abcdefghij-1234567890")),
        codeChallengeMethod = Some("S256")
      ),
      subject = "alice"
    )
    out match
      case AuthorizationOutcome.CodeRedirect(uri, code, state) =>
        uri              shouldBe "http://localhost/cb"
        code.length should be > 10
        state            shouldBe Some("xyz")
      case other => fail(s"expected CodeRedirect, got $other")

  test("issueAuthorizationCode: unknown client → ErrorResponse (cannot redirect)"):
    val as = new AuthServer(cfg())
    as.issueAuthorizationCode(
      AuthorizationRequest("code", "missing-client", "http://x/cb"),
      subject = "alice"
    ) match
      case AuthorizationOutcome.ErrorResponse(code, _) => code shouldBe "invalid_client"
      case other => fail(s"expected ErrorResponse, got $other")

  test("issueAuthorizationCode: unregistered redirect_uri → ErrorResponse"):
    val as = new AuthServer(cfg())
    as.clients.register(Client("c1", None, Set("http://ok/cb"), Set("read")))
    as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://evil/cb"),
      "alice"
    ) match
      case AuthorizationOutcome.ErrorResponse(code, _) => code shouldBe "invalid_request"
      case other => fail(s"expected ErrorResponse, got $other")

  test("issueAuthorizationCode: PKCE required by default — missing code_challenge → ErrorRedirect"):
    val as = new AuthServer(cfg())
    as.clients.register(Client("c1", None, Set("http://x/cb"), Set("read")))
    as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb"),
      "alice"
    ) match
      case AuthorizationOutcome.ErrorRedirect(_, error, _, _) => error shouldBe "invalid_request"
      case other => fail(s"expected ErrorRedirect, got $other")

  test("issueAuthorizationCode: requested scopes not in client → invalid_scope"):
    val as = new AuthServer(cfg())
    as.clients.register(Client("c1", None, Set("http://x/cb"), Set("read")))
    as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb",
        scope = Set("admin"),
        codeChallenge = Some("X"), codeChallengeMethod = Some("plain")),
      "alice"
    ) match
      case AuthorizationOutcome.ErrorRedirect(_, error, _, _) => error shouldBe "invalid_scope"
      case other => fail(s"expected ErrorRedirect, got $other")

  test("AuthorizationOutcome.redirectUrlFor builds spec-shaped URLs"):
    val ok  = AuthorizationOutcome.CodeRedirect("http://x/cb?keep=1", "abc", Some("st"))
    val err = AuthorizationOutcome.ErrorRedirect(
      "http://x/cb", "access_denied", Some("user said no"), Some("st"))
    val bad = AuthorizationOutcome.ErrorResponse("invalid_client", "x")
    AuthorizationOutcome.redirectUrlFor(ok) shouldBe Some("http://x/cb?keep=1&code=abc&state=st")
    AuthorizationOutcome.redirectUrlFor(err).get should startWith("http://x/cb?error=access_denied")
    AuthorizationOutcome.redirectUrlFor(bad) shouldBe None  // cannot redirect

  // ─── Token endpoint: authorization_code grant ──────────────────────

  test("authorization_code grant: full end-to-end PKCE S256 round trip"):
    val as = new AuthServer(cfg())
    val verifier = "this-is-a-pkce-verifier-1234567890-abcdef"
    val challenge = OAuth.pkceS256(verifier)
    as.clients.register(Client("c1", None, Set("http://x/cb"), Set("read"), clientType = ClientType.Public))
    val redirect = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb",
        scope = Set("read"),
        codeChallenge = Some(challenge), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    val tok = as.issueToken(TokenRequest.AuthorizationCodeGrant(
      code = redirect.code, redirectUri = "http://x/cb",
      clientId = "c1", codeVerifier = Some(verifier)
    ))
    tok match
      case TokenOutcome.Issued(resp) =>
        resp.accessToken should not be empty
        resp.refreshToken.isDefined shouldBe true
        resp.scope shouldBe Set("read")
        as.tokenValidator(resp.accessToken) match
          case OAuth.AuthResult.Valid(c) =>
            c.subject shouldBe "alice"
            c.hasScope("read") shouldBe true
          case other => fail(s"validator: expected Valid, got $other")
      case other => fail(s"expected Issued, got $other")

  test("authorization_code grant: PKCE verifier mismatch → invalid_grant"):
    val as = new AuthServer(cfg())
    as.clients.register(Client("c1", None, Set("http://x/cb"), Set("read"), clientType = ClientType.Public))
    val challenge = OAuth.pkceS256("correct-verifier")
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb",
        codeChallenge = Some(challenge), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    as.issueToken(TokenRequest.AuthorizationCodeGrant(
      code = redir.code, redirectUri = "http://x/cb", clientId = "c1",
      codeVerifier = Some("wrong-verifier")
    )) match
      case TokenOutcome.Error(code, _) => code shouldBe "invalid_grant"
      case other => fail(s"expected Error, got $other")

  test("authorization_code grant: code is single-use"):
    val as = new AuthServer(cfg())
    as.clients.register(Client("c1", None, Set("http://x/cb"), Set("read"), clientType = ClientType.Public))
    val ch = OAuth.pkceS256("v" * 50)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb",
        codeChallenge = Some(ch), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    val req = TokenRequest.AuthorizationCodeGrant(
      code = redir.code, redirectUri = "http://x/cb", clientId = "c1",
      codeVerifier = Some("v" * 50)
    )
    as.issueToken(req)               shouldBe a[TokenOutcome.Issued]
    as.issueToken(req) match  // second use of same code
      case TokenOutcome.Error(code, _) => code shouldBe "invalid_grant"
      case other                       => fail(s"expected Error on reuse, got $other")

  test("authorization_code grant: redirect_uri must match the original"):
    val as = new AuthServer(cfg())
    as.clients.register(Client("c1", None, Set("http://x/cb"), Set("read"), clientType = ClientType.Public))
    val ch = OAuth.pkceS256("v" * 50)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb",
        codeChallenge = Some(ch), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    as.issueToken(TokenRequest.AuthorizationCodeGrant(
      code = redir.code, redirectUri = "http://OTHER/cb", clientId = "c1",
      codeVerifier = Some("v" * 50)
    )) match
      case TokenOutcome.Error(code, _) => code shouldBe "invalid_grant"
      case other => fail(s"expected Error, got $other")

  // ─── Refresh-token grant ───────────────────────────────────────────

  test("refresh_token grant: rotates and reissues access token"):
    val as = new AuthServer(cfg())
    as.clients.register(Client("c1", None, Set("http://x/cb"), Set("read"), clientType = ClientType.Public))
    val ch = OAuth.pkceS256("v" * 50)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb",
        scope = Set("read"),
        codeChallenge = Some(ch), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    val first = as.issueToken(TokenRequest.AuthorizationCodeGrant(
      redir.code, "http://x/cb", "c1", codeVerifier = Some("v" * 50)
    )).asInstanceOf[TokenOutcome.Issued].response
    val refreshed = as.issueToken(TokenRequest.RefreshTokenGrant(
      refreshToken = first.refreshToken.get,
      clientId     = "c1"
    )).asInstanceOf[TokenOutcome.Issued].response
    refreshed.accessToken should not be first.accessToken
    // Original refresh token is now revoked (single-use rotation per OAuth 2.1).
    as.issueToken(TokenRequest.RefreshTokenGrant(
      refreshToken = first.refreshToken.get, clientId = "c1"
    )) match
      case TokenOutcome.Error(code, _) => code shouldBe "invalid_grant"
      case other => fail(s"expected Error on rotated refresh, got $other")

  test("refresh_token grant: narrower scope accepted, wider rejected"):
    val as = new AuthServer(cfg())
    as.clients.register(Client("c1", None, Set("http://x/cb"), Set("read", "write"), clientType = ClientType.Public))
    val ch = OAuth.pkceS256("v" * 50)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb",
        scope = Set("read", "write"),
        codeChallenge = Some(ch), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    val first = as.issueToken(TokenRequest.AuthorizationCodeGrant(
      redir.code, "http://x/cb", "c1", codeVerifier = Some("v" * 50)
    )).asInstanceOf[TokenOutcome.Issued].response
    // Narrower — OK.
    val narrowed = as.issueToken(TokenRequest.RefreshTokenGrant(
      refreshToken = first.refreshToken.get,
      scope        = Set("read"),
      clientId     = "c1"
    ))
    narrowed shouldBe a[TokenOutcome.Issued]
    // Wider — invalid_scope.  (Use the new refresh token from `narrowed`
    // since the original is now rotated.)
    val nextRefresh = narrowed.asInstanceOf[TokenOutcome.Issued].response.refreshToken.get
    as.issueToken(TokenRequest.RefreshTokenGrant(
      refreshToken = nextRefresh,
      scope        = Set("read", "admin"),  // 'admin' wasn't in the original
      clientId     = "c1"
    )) match
      case TokenOutcome.Error(code, _) => code shouldBe "invalid_scope"
      case other => fail(s"expected invalid_scope, got $other")

  // ─── Client-credentials grant ──────────────────────────────────────

  test("client_credentials grant: confidential client gets access token (no refresh)"):
    val as = new AuthServer(cfg())
    as.clients.register(Client(
      id           = "svc",
      secret       = Some("svc-secret"),
      redirectUris = Set.empty,
      scopes       = Set("read", "write"),
      grantTypes   = Set("client_credentials"),
      clientType   = ClientType.Confidential
    ))
    as.issueToken(TokenRequest.ClientCredentialsGrant(
      clientId = "svc", clientSecret = "svc-secret", scope = Set("read")
    )) match
      case TokenOutcome.Issued(resp) =>
        resp.accessToken should not be empty
        resp.refreshToken shouldBe None
        resp.scope        shouldBe Set("read")
      case other => fail(s"expected Issued, got $other")

  test("client_credentials grant: public client is rejected"):
    val as = new AuthServer(cfg())
    as.clients.register(Client("pub", None, Set.empty, Set("read"),
      grantTypes = Set("client_credentials"), clientType = ClientType.Public))
    as.issueToken(TokenRequest.ClientCredentialsGrant("pub", "", Set("read"))) match
      case TokenOutcome.Error(_, _) => succeed
      case other => fail(s"expected Error, got $other")

  test("client_credentials grant: wrong secret rejected"):
    val as = new AuthServer(cfg())
    as.clients.register(Client(
      id = "svc", secret = Some("right"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential
    ))
    as.issueToken(TokenRequest.ClientCredentialsGrant("svc", "wrong", Set("read"))) match
      case TokenOutcome.Error(code, _) => code shouldBe "invalid_client"
      case other => fail(s"expected invalid_client, got $other")

  // ─── Introspection (RFC 7662) ──────────────────────────────────────

  test("introspect: active token returns claims"):
    val as = new AuthServer(cfg())
    val token = OAuth.issueHmacToken(
      "test-secret", "alice", Set("read"), 3600L,
      issuer = Some("https://auth.example.com"), clientId = Some("c1")
    )
    val r = as.introspect(token)
    r.active   shouldBe true
    r.subject  shouldBe Some("alice")
    r.scope    shouldBe Some("read")
    r.clientId shouldBe Some("c1")

  test("introspect: bad token returns active=false"):
    val as = new AuthServer(cfg())
    as.introspect("garbage").active     shouldBe false
    as.introspect("").active            shouldBe false
    as.introspect("a.b.c").active       shouldBe false

  // ─── Dynamic Client Registration (RFC 7591) ────────────────────────

  test("registerClient: public client (auth_method=none)"):
    val as = new AuthServer(cfg())
    val md = ujson.Obj(
      "redirect_uris"             -> ujson.Arr("http://localhost/cb"),
      "token_endpoint_auth_method" -> "none",
      "scope"                     -> "read write"
    )
    as.registerClient(md) match
      case Right(c) =>
        c.id should startWith("client-")
        c.secret       shouldBe None
        c.clientType   shouldBe ClientType.Public
        c.scopes       shouldBe Set("read", "write")
        c.redirectUris shouldBe Set("http://localhost/cb")
      case Left(err) => fail(s"expected Right, got Left($err)")

  test("registerClient: confidential client gets a secret"):
    val as = new AuthServer(cfg())
    val md = ujson.Obj(
      "redirect_uris" -> ujson.Arr("http://x/cb"),
      "client_name"   -> "My App"
    )
    as.registerClient(md) match
      case Right(c) =>
        c.secret.isDefined shouldBe true
        c.clientType       shouldBe ClientType.Confidential
        c.name             shouldBe Some("My App")
      case Left(err) => fail(s"got Left($err)")

  test("registerClient: missing redirect_uris → invalid_redirect_uri"):
    val as = new AuthServer(cfg())
    as.registerClient(ujson.Obj("client_name" -> "X")) shouldBe Left("invalid_redirect_uri")
    as.registerClient(ujson.Obj("redirect_uris" -> ujson.Arr())) shouldBe Left("invalid_redirect_uri")

  test("registerClient: disabled via config"):
    val as = new AuthServer(cfg(_.copy(allowDynamicClientRegistration = false)))
    as.registerClient(ujson.Obj("redirect_uris" -> ujson.Arr("http://x"))) shouldBe Left("registration_disabled")

  test("registrationResponseJson contains spec field names"):
    val c = Client(
      id = "client-abc", secret = Some("s"), redirectUris = Set("http://x/cb"),
      scopes = Set("read"), name = Some("Demo"),
      clientType = ClientType.Confidential
    )
    val js = (new AuthServer(cfg())).registrationResponseJson(c)
    js("client_id").str               shouldBe "client-abc"
    js("client_secret").str           shouldBe "s"
    js("redirect_uris").arr.length    shouldBe 1
    js("scope").str                   shouldBe "read"
    js("token_endpoint_auth_method").str shouldBe "client_secret_basic"
    js("client_name").str              shouldBe "Demo"

  // ─── AS metadata (RFC 8414) ────────────────────────────────────────

  test("metadataJson advertises spec-required fields and endpoints"):
    val as = new AuthServer(cfg())
    val js = as.metadataJson()
    js("issuer").str                  shouldBe "https://auth.example.com"
    js("authorization_endpoint").str  shouldBe "https://auth.example.com/authorize"
    js("token_endpoint").str          shouldBe "https://auth.example.com/token"
    js("introspection_endpoint").str  shouldBe "https://auth.example.com/introspect"
    js("registration_endpoint").str   shouldBe "https://auth.example.com/register"
    js("response_types_supported").arr.map(_.str).toList shouldBe List("code")
    js("grant_types_supported").arr.map(_.str).toSet shouldBe
      Set("authorization_code", "refresh_token", "client_credentials",
          "urn:ietf:params:oauth:grant-type:passkey")
    js("code_challenge_methods_supported").arr.map(_.str).toSet shouldBe
      Set("S256", "plain")
    js("scopes_supported").arr.map(_.str).toSet shouldBe Set("read", "write")

  test("metadataJson omits registration_endpoint when DCR is disabled"):
    val as = new AuthServer(cfg(_.copy(allowDynamicClientRegistration = false)))
    val js = as.metadataJson()
    js.obj.contains("registration_endpoint") shouldBe false

  // ─── TokenResponse + IntrospectionResponse JSON shape ──────────────

  test("TokenResponse.toJson uses spec field names"):
    val r = TokenResponse(accessToken = "tok", expiresIn = 60L,
      refreshToken = Some("ref"), scope = Set("a", "b"))
    val js = r.toJson
    js("access_token").str  shouldBe "tok"
    js("token_type").str    shouldBe "Bearer"
    js("expires_in").num    shouldBe 60.0
    js("refresh_token").str shouldBe "ref"
    js("scope").str         shouldBe "a b"

  test("IntrospectionResponse.toJson — inactive token is bare {active: false}"):
    IntrospectionResponse(active = false).toJson shouldBe ujson.Obj("active" -> false)

  // ─── PKCE helpers ──────────────────────────────────────────────────

  test("pkceS256 matches RFC 7636 example vector"):
    // Verifier from RFC 7636 §4.4 example.
    val verifier  = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    OAuth.pkceS256(verifier) shouldBe challenge

  test("pkceMatches handles S256 and plain methods, rejects unknown"):
    val v = "verifier-1234567890"
    OAuth.pkceMatches(v, OAuth.pkceS256(v), "S256") shouldBe true
    OAuth.pkceMatches(v, v,                 "plain") shouldBe true
    OAuth.pkceMatches(v, "wrong",           "S256") shouldBe false
    OAuth.pkceMatches(v, v,                 "weird") shouldBe false

  // ─── McpAuth re-exports still work ─────────────────────────────────

  test("McpAuth re-exports the OAuth primitives untouched"):
    val token = scalascript.mcp.McpAuth.issueHmacToken("s", "u", Set("x"), 60L)
    scalascript.mcp.McpAuth.hmacValidator("s")(token) match
      case scalascript.mcp.McpAuth.AuthResult.Valid(c) => c.subject shouldBe "u"
      case other => fail(s"expected Valid, got $other")
