package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — framework-agnostic HTTP route handlers for the OAuth AS.
 *  These tests verify the JSON request/response shapes without spinning
 *  any actual HTTP server — the handlers are pure functions over body
 *  + headers. */
class OAuthRoutesTest extends AnyFunSuite with Matchers:

  private def newAs(extra: AuthServerConfig => AuthServerConfig = identity): AuthServer =
    new AuthServer(extra(AuthServerConfig(
      issuer        = "https://auth.example.com",
      signingSecret = "test-secret",
      supportedScopes = Set("read", "write")
    )))

  // ─── parseForm ─────────────────────────────────────────────────────

  test("parseForm: typical form-urlencoded body"):
    val m = OAuthRoutes.parseForm("grant_type=authorization_code&code=abc%20xyz&client_id=c1")
    m("grant_type") shouldBe "authorization_code"
    m("code")       shouldBe "abc xyz"  // URL-decoded
    m("client_id")  shouldBe "c1"

  test("parseForm: empty / malformed entries skipped, last write wins"):
    OAuthRoutes.parseForm("") shouldBe Map.empty
    OAuthRoutes.parseForm(null.asInstanceOf[String]) shouldBe Map.empty
    OAuthRoutes.parseForm("a=1&b&c=2&a=3") shouldBe Map("a" -> "3", "c" -> "2")

  test("extractBasicAuth round-trip"):
    val b64 = java.util.Base64.getEncoder.encodeToString("user:pass".getBytes)
    OAuthRoutes.extractBasicAuth(Map("Authorization" -> s"Basic $b64")) shouldBe Some(("user", "pass"))
    OAuthRoutes.extractBasicAuth(Map("authorization" -> s"basic $b64")) shouldBe Some(("user", "pass"))
    OAuthRoutes.extractBasicAuth(Map.empty)                              shouldBe None
    OAuthRoutes.extractBasicAuth(Map("Authorization" -> "Bearer xyz"))   shouldBe None
    OAuthRoutes.extractBasicAuth(Map("Authorization" -> "Basic !!bad")) shouldBe None

  // ─── /token endpoint ──────────────────────────────────────────────

  test("/token: client_credentials happy path returns access token"):
    val as = newAs()
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential
    ))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    val out  = OAuthRoutes.handleToken(as, body, Map.empty)
    out match
      case OAuthRoutes.RouteOutcome.Json(status, js, hdrs) =>
        status                shouldBe 200
        js("access_token").str should not be empty
        js("token_type").str  shouldBe "Bearer"
        js("scope").str       shouldBe "read"
        hdrs("Cache-Control") shouldBe "no-store"
      case other => fail(s"expected Json 200, got $other")

  test("/token: HTTP Basic auth alternative is accepted"):
    val as = newAs()
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential
    ))
    val b64     = java.util.Base64.getEncoder.encodeToString("svc:s".getBytes)
    val headers = Map("Authorization" -> s"Basic $b64")
    val body    = "grant_type=client_credentials&scope=read"
    OAuthRoutes.handleToken(as, body, headers) match
      case OAuthRoutes.RouteOutcome.Json(200, js, _) =>
        js("access_token").str should not be empty
      case other => fail(s"expected 200 Json, got $other")

  test("/token: unsupported grant returns 400 unsupported_grant_type"):
    val as = newAs()
    OAuthRoutes.handleToken(as, "grant_type=password&client_id=x", Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(400, js, _) =>
        js("error").str shouldBe "unsupported_grant_type"
      case other => fail(s"got $other")

  test("/token: invalid_client maps to 401"):
    val as = newAs()
    as.clients.register(Client(
      id = "svc", secret = Some("right"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential
    ))
    OAuthRoutes.handleToken(as,
      "grant_type=client_credentials&client_id=svc&client_secret=WRONG",
      Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(401, js, _) =>
        js("error").str shouldBe "invalid_client"
      case other => fail(s"expected 401, got $other")

  test("/token: missing grant_type → 400 invalid_request"):
    val as = newAs()
    OAuthRoutes.handleToken(as, "client_id=x", Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(400, js, _) =>
        js("error").str shouldBe "invalid_request"
      case other => fail(s"got $other")

  test("/token: full authorization_code + PKCE end-to-end"):
    val as = newAs()
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("read"), clientType = ClientType.Public
    ))
    val verifier  = "v" * 50
    val challenge = OAuth.pkceS256(verifier)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb", Set("read"),
        codeChallenge = Some(challenge), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    val body =
      s"grant_type=authorization_code&code=${redir.code}&redirect_uri=http%3A%2F%2Fx%2Fcb" +
      s"&client_id=c1&code_verifier=$verifier"
    OAuthRoutes.handleToken(as, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(200, js, _) =>
        js("access_token").str  should not be empty
        js("refresh_token").str should not be empty
      case other => fail(s"got $other")

  // ─── /introspect endpoint ─────────────────────────────────────────

  test("/introspect: active token returns claims"):
    val as = newAs()
    val token = OAuth.issueHmacToken("test-secret", "alice", Set("read"), 60L,
      issuer = Some("https://auth.example.com"), clientId = Some("c1"))
    OAuthRoutes.handleIntrospect(as, s"token=$token", Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(200, js, _) =>
        js("active").bool   shouldBe true
        js("sub").str       shouldBe "alice"
        js("scope").str     shouldBe "read"
        js("client_id").str shouldBe "c1"
      case other => fail(s"got $other")

  test("/introspect: bad token → active=false (still 200)"):
    val as = newAs()
    OAuthRoutes.handleIntrospect(as, "token=garbage", Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(200, js, _) =>
        js("active").bool shouldBe false
        js.obj.contains("sub") shouldBe false
      case other => fail(s"got $other")

  test("/introspect: missing 'token' → 400 invalid_request"):
    val as = newAs()
    OAuthRoutes.handleIntrospect(as, "", Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(400, js, _) =>
        js("error").str shouldBe "invalid_request"
      case other => fail(s"got $other")

  test("/introspect: validateCaller hook gates the endpoint"):
    val as = newAs()
    OAuthRoutes.handleIntrospect(as, "token=x", Map.empty,
      validateCaller = _ => false) match
      case OAuthRoutes.RouteOutcome.Json(401, js, _) =>
        js("error").str shouldBe "invalid_client"
      case other => fail(s"got $other")

  // ─── /register endpoint ───────────────────────────────────────────

  test("/register: well-formed JSON yields 201"):
    val as = newAs()
    val body = ujson.Obj(
      "redirect_uris" -> ujson.Arr("http://localhost/cb"),
      "scope"         -> "read",
      "client_name"   -> "Demo"
    ).render()
    OAuthRoutes.handleRegister(as, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(201, js, _) =>
        js("client_id").str       should startWith ("client-")
        js("client_secret").str   should not be empty
        js("client_name").str     shouldBe "Demo"
      case other => fail(s"got $other")

  test("/register: invalid JSON body → 400 invalid_client_metadata"):
    val as = newAs()
    OAuthRoutes.handleRegister(as, "not json", Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(400, js, _) =>
        js("error").str shouldBe "invalid_client_metadata"
      case other => fail(s"got $other")

  test("/register: missing redirect_uris → 400 invalid_redirect_uri"):
    val as = newAs()
    OAuthRoutes.handleRegister(as, ujson.Obj("scope" -> "x").render(), Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(400, js, _) =>
        js("error").str shouldBe "invalid_redirect_uri"
      case other => fail(s"got $other")

  test("/register: 403 when DCR is disabled"):
    val as = newAs(_.copy(allowDynamicClientRegistration = false))
    val body = ujson.Obj("redirect_uris" -> ujson.Arr("http://x/cb")).render()
    OAuthRoutes.handleRegister(as, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(403, js, _) =>
        js("error").str shouldBe "registration_disabled"
      case other => fail(s"got $other")

  // ─── /.well-known/oauth-authorization-server ──────────────────────

  test("metadata route returns spec-shaped discovery document"):
    val as = newAs()
    OAuthRoutes.handleMetadata(as) match
      case OAuthRoutes.RouteOutcome.Json(200, js, hdrs) =>
        js("issuer").str          shouldBe "https://auth.example.com"
        js("token_endpoint").str  should endWith ("/token")
        hdrs("Cache-Control")     should include ("max-age=")
      case other => fail(s"got $other")

  // ─── /authorize ────────────────────────────────────────────────────

  test("/authorize: subject + valid request → 302 to client redirect_uri with code"):
    val as = newAs()
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("read"), clientType = ClientType.Public
    ))
    val q = Map(
      "response_type" -> "code", "client_id" -> "c1",
      "redirect_uri"  -> "http://x/cb", "scope" -> "read",
      "state"         -> "s1",
      "code_challenge" -> OAuth.pkceS256("v" * 50),
      "code_challenge_method" -> "S256"
    )
    OAuthRoutes.handleAuthorize(as, q, Map.empty, _ => Some("alice")) match
      case OAuthRoutes.RouteOutcome.Redirect(302, loc) =>
        loc should startWith ("http://x/cb?")
        loc should include    ("code=")
        loc should include    ("state=s1")
      case other => fail(s"got $other")

  test("/authorize: no subject + loginUrl set → bounce to login (caller decides encoding)"):
    val as = newAs()
    val q  = Map("response_type" -> "code", "client_id" -> "c1", "redirect_uri" -> "http://x/cb")
    OAuthRoutes.handleAuthorize(
      as, q, Map.empty,
      subjectFor = _ => None,
      // Caller controls encoding — use java URLEncoder if you want the return param
      // safely embedded in a query parameter.
      loginUrl   = Some(self =>
        s"https://login.example.com?return=${java.net.URLEncoder.encode(self, "UTF-8")}"),
      selfUrl    = Some("https://auth.example.com/authorize")
    ) match
      case OAuthRoutes.RouteOutcome.Redirect(302, loc) =>
        loc should startWith ("https://login.example.com?return=https%3A%2F%2Fauth.example.com%2Fauthorize")
      case other => fail(s"got $other")

  test("/authorize: no subject + no loginUrl → 401 login_required"):
    val as = newAs()
    val q  = Map("response_type" -> "code", "client_id" -> "c1", "redirect_uri" -> "http://x/cb")
    OAuthRoutes.handleAuthorize(as, q, Map.empty, _ => None) match
      case OAuthRoutes.RouteOutcome.Json(401, js, _) =>
        js("error").str shouldBe "login_required"
      case other => fail(s"got $other")

  test("/authorize: unrecoverable error (bad client) returns 400 JSON, not redirect"):
    val as = newAs()
    val q  = Map("response_type" -> "code", "client_id" -> "unknown", "redirect_uri" -> "http://x/cb")
    OAuthRoutes.handleAuthorize(as, q, Map.empty, _ => Some("alice")) match
      case OAuthRoutes.RouteOutcome.Json(400, js, _) =>
        js("error").str shouldBe "invalid_client"
      case other => fail(s"got $other")

  test("/authorize: recoverable error redirects to client with error params"):
    val as = newAs()
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("read"), clientType = ClientType.Public
    ))
    // PKCE missing → recoverable error: redirect with `error=invalid_request`
    val q  = Map("response_type" -> "code", "client_id" -> "c1", "redirect_uri" -> "http://x/cb")
    OAuthRoutes.handleAuthorize(as, q, Map.empty, _ => Some("alice")) match
      case OAuthRoutes.RouteOutcome.Redirect(302, loc) =>
        loc should startWith ("http://x/cb?")
        loc should include    ("error=invalid_request")
      case other => fail(s"got $other")
