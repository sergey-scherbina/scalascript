package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*
import scalascript.interpreter.{Computation, Value}
import scalascript.compiler.plugin.oauth.OAuthHttp
import scalascript.backend.spi.NativeContext
import scalascript.plugin.api.{HttpCap, PluginContext}

/** v1.17.x — backend-interpreter adapter that wires `OAuthRoutes` pure
 *  handlers into the embedded WebServer.  Tests verify the route
 *  registration shape + RouteOutcome → Value translation. */
class OAuthHttpInstallerTest extends AnyFunSuite with Matchers:

  /** Lightweight `NativeContext` stub that captures registered routes
   *  so tests can invoke them directly. */
  private class CapturingCtx extends NativeContext:
    override def out = System.out
    override def err = System.err
    val routes = scala.collection.mutable.LinkedHashMap.empty[(String, String), Any]
    override def registerRoute(method: String, path: String, handler: Any): Unit =
      routes((method, path)) = handler
    def http: HttpCap = PluginContext.fromNative(this)

  private def newAs: AuthServer =
    val as = new AuthServer(AuthServerConfig(
      issuer        = "https://auth.local",
      signingSecret = "test-secret",
      supportedScopes = Set("read")
    ))
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential
    ))
    as

  /** Build a Request InstanceV with the supplied body / headers / query. */
  private def request(
    body:    String              = "",
    headers: Map[String, String] = Map.empty,
    query:   Map[String, String] = Map.empty
  ): Value.InstanceV =
    val headerV = Value.MapV(headers.iterator.map((k, v) =>
      (Value.StringV(k): Value) -> (Value.StringV(v): Value)).toMap)
    val queryV  = Value.MapV(query.iterator.map((k, v) =>
      (Value.StringV(k): Value) -> (Value.StringV(v): Value)).toMap)
    Value.InstanceV("Request", Map(
      "body"    -> Value.StringV(body),
      "headers" -> headerV,
      "query"   -> queryV
    ))

  /** Invoke a registered handler — they're all `Value.NativeFnV`. */
  private def call(handler: Any, req: Value): Value =
    handler.asInstanceOf[Value.NativeFnV].f(List(req)) match
      case Computation.Pure(v) => v
      case other               => fail(s"expected Pure, got $other")

  // ─── installRoutes ───────────────────────────────────────────────

  test("installRoutes registers all OAuth endpoints"):
    val ctx = new CapturingCtx
    OAuthHttp.installRoutes(newAs, ctx.http)
    ctx.routes.keys.toSet shouldBe Set(
      ("POST", "/token"),
      ("POST", "/introspect"),
      ("POST", "/revoke"),
      ("POST", "/register"),
      ("GET",  "/authorize"),
      ("GET",  "/.well-known/oauth-authorization-server"),
      ("GET",  "/.well-known/jwks.json"),
      ("GET",  "/passkey/challenge")
    )

  test("installRoutes honours the basePath prefix"):
    val ctx = new CapturingCtx
    OAuthHttp.installRoutes(newAs, ctx.http, basePath = "/oauth")
    ctx.routes.keys.toSet shouldBe Set(
      ("POST", "/oauth/token"),
      ("POST", "/oauth/introspect"),
      ("POST", "/oauth/revoke"),
      ("POST", "/oauth/register"),
      ("GET",  "/oauth/authorize"),
      ("GET",  "/oauth/.well-known/oauth-authorization-server"),
      ("GET",  "/oauth/.well-known/jwks.json"),
      ("GET",  "/oauth/passkey/challenge")
    )

  // ─── /token route through the adapter ───────────────────────────

  test("POST /token: returns Response with status 200 + JSON body"):
    val ctx = new CapturingCtx
    OAuthHttp.installRoutes(newAs, ctx.http)
    val req = request(body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read")
    call(ctx.routes(("POST", "/token")), req) match
      case Value.InstanceV("Response", fields) =>
        fields("status")  shouldBe Value.IntV(200L)
        fields("body") match
          case Value.StringV(b) =>
            val js = ujson.read(b)
            js("access_token").str should not be empty
            js("token_type").str   shouldBe "Bearer"
          case other => fail(s"body: got $other")
      case other => fail(s"got $other")

  test("POST /token: invalid_client maps to 401 in the Response"):
    val ctx = new CapturingCtx
    OAuthHttp.installRoutes(newAs, ctx.http)
    val req = request(body = "grant_type=client_credentials&client_id=svc&client_secret=WRONG")
    call(ctx.routes(("POST", "/token")), req) match
      case Value.InstanceV("Response", fs) => fs("status") shouldBe Value.IntV(401L)
      case other => fail(s"got $other")

  // ─── /revoke route ──────────────────────────────────────────────

  test("POST /revoke: returns 200 + empty body"):
    val as  = newAs
    val ctx = new CapturingCtx
    OAuthHttp.installRoutes(as, ctx.http)
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("read")
    )).asInstanceOf[TokenOutcome.Issued].response.accessToken
    val req = request(body = s"token=$token")
    call(ctx.routes(("POST", "/revoke")), req) match
      case Value.InstanceV("Response", fs) =>
        fs("status") shouldBe Value.IntV(200L)
        fs("body")   shouldBe Value.EmptyStr
      case other => fail(s"got $other")
    as.introspect(token).active shouldBe false

  // ─── metadata route ─────────────────────────────────────────────

  test("GET /.well-known/oauth-authorization-server: discovery document"):
    val ctx = new CapturingCtx
    OAuthHttp.installRoutes(newAs, ctx.http)
    val req = request()
    call(ctx.routes(("GET", "/.well-known/oauth-authorization-server")), req) match
      case Value.InstanceV("Response", fs) =>
        fs("status") shouldBe Value.IntV(200L)
        fs("body") match
          case Value.StringV(b) =>
            ujson.read(b)("issuer").str shouldBe "https://auth.local"
          case other => fail(s"body: got $other")
      case other => fail(s"got $other")

  // ─── /authorize route ──────────────────────────────────────────

  test("GET /authorize: authenticated user gets redirected to client redirect_uri with code"):
    val as = newAs
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("read"), clientType = ClientType.Public
    ))
    val ctx = new CapturingCtx
    OAuthHttp.installRoutes(as, ctx.http,
      subjectFor = _ => Some("alice"))
    val req = request(query = Map(
      "response_type" -> "code",
      "client_id"     -> "c1",
      "redirect_uri"  -> "http://x/cb",
      "scope"         -> "read",
      "code_challenge" -> OAuth.pkceS256("v" * 50),
      "code_challenge_method" -> "S256"
    ))
    call(ctx.routes(("GET", "/authorize")), req) match
      case Value.InstanceV("Response", fs) =>
        fs("status") shouldBe Value.IntV(302L)
        fs("headers") match
          case Value.MapV(m) =>
            val loc = m(Value.StringV("Location")).asInstanceOf[Value.StringV].v
            loc should startWith ("http://x/cb?code=")
          case other => fail(s"headers: got $other")
      case other => fail(s"got $other")

  test("GET /authorize: no subject + no loginUrl → 401"):
    val ctx = new CapturingCtx
    OAuthHttp.installRoutes(newAs, ctx.http)  // default subjectFor = None
    val req = request(query = Map("response_type" -> "code"))
    call(ctx.routes(("GET", "/authorize")), req) match
      case Value.InstanceV("Response", fs) =>
        fs("status") shouldBe Value.IntV(401L)
      case other => fail(s"got $other")

  // ─── routeOutcomeToValue translation ────────────────────────────

  test("routeOutcomeToValue: Json includes extra headers"):
    val o = OAuthRoutes.RouteOutcome.Json(200, ujson.Obj("x" -> 1),
      Map("Cache-Control" -> "no-store"))
    OAuthHttp.routeOutcomeToValue(o) match
      case Value.InstanceV("Response", fs) =>
        fs("status") shouldBe Value.IntV(200L)
        fs("headers") match
          case Value.MapV(m) =>
            m(Value.StringV("Content-Type")) shouldBe Value.StringV("application/json")
            m(Value.StringV("Cache-Control")) shouldBe Value.StringV("no-store")
          case other => fail(s"headers: got $other")
      case other => fail(s"got $other")

  test("routeOutcomeToValue: Redirect sets Location header"):
    val o = OAuthRoutes.RouteOutcome.Redirect(302, "https://elsewhere")
    OAuthHttp.routeOutcomeToValue(o) match
      case Value.InstanceV("Response", fs) =>
        fs("status") shouldBe Value.IntV(302L)
        fs("headers") match
          case Value.MapV(m) =>
            m(Value.StringV("Location")) shouldBe Value.StringV("https://elsewhere")
          case other => fail(s"headers: got $other")
      case other => fail(s"got $other")

  test("routeOutcomeToValue: Empty has empty body + no headers"):
    val o = OAuthRoutes.RouteOutcome.Empty(204)
    OAuthHttp.routeOutcomeToValue(o) match
      case Value.InstanceV("Response", fs) =>
        fs("status") shouldBe Value.IntV(204L)
        fs("body")   shouldBe Value.EmptyStr
      case other => fail(s"got $other")
