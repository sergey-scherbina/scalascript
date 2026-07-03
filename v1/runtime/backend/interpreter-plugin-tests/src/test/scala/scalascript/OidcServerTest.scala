package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*
import scalascript.oidc.*

/** v1.17.x — OpenID Connect (OIDC) layer on top of the OAuth AS.
 *  Tests cover id_token issuance + claim filtering by scope + the
 *  /userinfo and discovery endpoints. */
class OidcServerTest extends AnyFunSuite with Matchers:

  private def cfg = AuthServerConfig(
    issuer        = "https://idp.example.com",
    signingSecret = "test-secret",
    supportedScopes = Set("openid", "profile", "email", "read")
  )

  private def newIdp: (OidcServer, AuthServer) =
    val as = new AuthServer(cfg)
    as.clients.register(Client(
      id           = "webapp",
      secret       = None,
      redirectUris = Set("http://localhost/cb"),
      scopes       = Set("openid", "profile", "email"),
      clientType   = ClientType.Public
    ))
    val info = new InMemoryUserInfoStore
    info.put(UserClaims(
      subject           = "alice",
      name              = Some("Alice A. Anderson"),
      email             = Some("alice@example.com"),
      emailVerified     = Some(true),
      picture           = Some("https://example.com/alice.png"),
      locale            = Some("en-US"),
      preferredUsername = Some("alice")
    ))
    (new OidcServer(as, info), as)

  private def issueWithOpenidScope(idp: OidcServer, scopes: Set[String]): TokenResponse =
    val as = idp.as
    val v  = "v" * 50
    val ch = OAuth.pkceS256(v)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "webapp", "http://localhost/cb",
        scope = scopes,
        codeChallenge = Some(ch), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    idp.issueToken(TokenRequest.AuthorizationCodeGrant(
      redir.code, "http://localhost/cb", "webapp", codeVerifier = Some(v)
    )).asInstanceOf[TokenOutcome.Issued].response

  // ─── id_token issuance ────────────────────────────────────────────

  test("issueToken: openid scope → response carries id_token"):
    val (idp, _) = newIdp
    val resp = issueWithOpenidScope(idp, Set("openid", "profile", "email"))
    resp.idToken.isDefined shouldBe true

  test("issueToken: no openid scope → response has no id_token (plain OAuth)"):
    val (idp, _) = newIdp
    val resp = issueWithOpenidScope(idp, Set("profile"))  // no 'openid'
    resp.idToken shouldBe None

  test("id_token carries iss + sub + aud + exp/iat claims"):
    val (idp, _) = newIdp
    val resp = issueWithOpenidScope(idp, Set("openid"))
    val payload = OAuth.decodeHmacToken("test-secret", resp.idToken.get).toOption.get
    payload("iss").str shouldBe "https://idp.example.com"
    payload("sub").str shouldBe "alice"
    payload("aud").str shouldBe "webapp"
    payload.obj.contains("exp") shouldBe true
    payload.obj.contains("iat") shouldBe true

  test("id_token embeds profile claims when profile scope is granted"):
    val (idp, _) = newIdp
    val resp    = issueWithOpenidScope(idp, Set("openid", "profile"))
    val payload = OAuth.decodeHmacToken("test-secret", resp.idToken.get).toOption.get
    payload("name").str               shouldBe "Alice A. Anderson"
    payload("picture").str            shouldBe "https://example.com/alice.png"
    payload("preferred_username").str shouldBe "alice"
    payload.obj.contains("email") shouldBe false  // 'email' scope wasn't granted

  test("id_token embeds email claims when email scope is granted"):
    val (idp, _) = newIdp
    val resp = issueWithOpenidScope(idp, Set("openid", "email"))
    val payload = OAuth.decodeHmacToken("test-secret", resp.idToken.get).toOption.get
    payload("email").str             shouldBe "alice@example.com"
    payload("email_verified").bool    shouldBe true
    payload.obj.contains("name") shouldBe false  // 'profile' scope wasn't granted

  test("id_token: client_credentials grant produces NO id_token (no user)"):
    val (idp, as) = newIdp
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("openid"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential
    ))
    val out = idp.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("openid")
    )).asInstanceOf[TokenOutcome.Issued].response
    out.idToken shouldBe None

  // ─── /userinfo ────────────────────────────────────────────────────

  test("/userinfo: bearer-validated, returns claims filtered by scope"):
    val (idp, _) = newIdp
    val resp = issueWithOpenidScope(idp, Set("openid", "profile"))
    idp.userInfoFor(resp.accessToken) match
      case UserInfoOutcome.Found(c) =>
        c("sub").str  shouldBe "alice"
        c("name").str shouldBe "Alice A. Anderson"
        c.obj.contains("email") shouldBe false
      case other => fail(s"expected Found, got $other")

  test("/userinfo: garbage token → Unauthorized"):
    val (idp, _) = newIdp
    idp.userInfoFor("garbage") shouldBe a[UserInfoOutcome.Unauthorized]

  test("/userinfo: revoked token → Unauthorized"):
    val (idp, _) = newIdp
    val resp = issueWithOpenidScope(idp, Set("openid"))
    idp.as.revokeToken(resp.accessToken)
    idp.userInfoFor(resp.accessToken) shouldBe a[UserInfoOutcome.Unauthorized]

  test("/userinfo: subject unknown to IdP → NotFound"):
    val (idp, _) = newIdp
    // Mint a token for a subject the userInfo store doesn't have.
    val tok = OAuth.issueHmacToken("test-secret", "ghost", Set("openid"), 60L,
      issuer = Some(cfg.issuer), clientId = Some("webapp"))
    idp.userInfoFor(tok) match
      case UserInfoOutcome.NotFound(s) => s shouldBe "ghost"
      case other => fail(s"got $other")

  // ─── /token route through OidcRoutes ──────────────────────────────

  test("OidcRoutes.handleToken returns id_token JSON field"):
    val (idp, as) = newIdp
    val v  = "v" * 50
    val ch = OAuth.pkceS256(v)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "webapp", "http://localhost/cb",
        Set("openid", "email"),
        codeChallenge = Some(ch), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    val body =
      s"grant_type=authorization_code&code=${redir.code}&redirect_uri=http%3A%2F%2Flocalhost%2Fcb" +
      s"&client_id=webapp&code_verifier=$v"
    OidcRoutes.handleToken(idp, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(200, js, _) =>
        js("access_token").str should not be empty
        js("id_token").str    should not be empty
      case other => fail(s"got $other")

  test("OidcRoutes.handleUserInfo: bearer header path"):
    val (idp, _) = newIdp
    val resp = issueWithOpenidScope(idp, Set("openid", "profile"))
    OidcRoutes.handleUserInfo(idp, "",
      Map("Authorization" -> s"Bearer ${resp.accessToken}")) match
      case OAuthRoutes.RouteOutcome.Json(200, js, _) =>
        js("sub").str shouldBe "alice"
        js("name").str shouldBe "Alice A. Anderson"
      case other => fail(s"got $other")

  test("OidcRoutes.handleUserInfo: missing token → 401 with WWW-Authenticate"):
    val (idp, _) = newIdp
    OidcRoutes.handleUserInfo(idp, "", Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(401, js, hdrs) =>
        js("error").str shouldBe "invalid_token"
        hdrs("WWW-Authenticate") should startWith ("Bearer ")
      case other => fail(s"got $other")

  test("OidcRoutes.handleUserInfo: access_token form field fallback"):
    val (idp, _) = newIdp
    val resp = issueWithOpenidScope(idp, Set("openid"))
    OidcRoutes.handleUserInfo(idp, s"access_token=${resp.accessToken}",
      Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(200, js, _) => js("sub").str shouldBe "alice"
      case other => fail(s"got $other")

  // ─── discovery doc ────────────────────────────────────────────────

  test("OidcServer.discoveryJson extends OAuth metadata with OIDC fields"):
    val (idp, _) = newIdp
    val js = idp.discoveryJson()
    js("issuer").str                                  shouldBe "https://idp.example.com"
    js("userinfo_endpoint").str                       should endWith ("/userinfo")
    js("subject_types_supported").arr.map(_.str).toList shouldBe List("public")
    js("id_token_signing_alg_values_supported").arr.map(_.str).toList shouldBe List("HS256")
    js("claims_supported").arr.map(_.str).toSet should contain ("email")
    js("scopes_supported").arr.map(_.str).toSet should contain allOf ("openid", "profile", "email")

  test("OidcRoutes.handleDiscovery returns 200 + Cache-Control"):
    val (idp, _) = newIdp
    OidcRoutes.handleDiscovery(idp) match
      case OAuthRoutes.RouteOutcome.Json(200, js, hdrs) =>
        js("issuer").str shouldBe "https://idp.example.com"
        hdrs("Cache-Control") should include ("max-age=")
      case other => fail(s"got $other")

  // ─── UserClaims.toClaims scope filter ────────────────────────────

  test("UserClaims.toClaims emits sub for openid alone"):
    val c = UserClaims("u", name = Some("N"), email = Some("e@x"),
      emailVerified = Some(true))
    val js = c.toClaims(Set("openid"))
    js("sub").str shouldBe "u"
    js.obj.contains("name")  shouldBe false
    js.obj.contains("email") shouldBe false

  test("UserClaims.toClaims emits extra unconditionally"):
    val c = UserClaims("u",
      extra = ujson.Obj("role" -> "admin", "groups" -> ujson.Arr("alpha", "beta")))
    val js = c.toClaims(Set("openid"))
    js("role").str          shouldBe "admin"
    js("groups").arr.length shouldBe 2
