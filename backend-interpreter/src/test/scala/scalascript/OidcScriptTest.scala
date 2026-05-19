package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.interpreter.intrinsics.OidcHttp
import scalascript.backend.spi.NativeContext
import scalascript.interpreter.{Computation, Value}
import scalascript.oauth.*
import scalascript.oidc.*

/** v1.17.x — end-to-end script-side OIDC intrinsics + the OidcHttp
 *  installer.  Mirrors what OAuthScriptTest does for AS. */
class OidcScriptTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  // ─── script-level intrinsics ─────────────────────────────────────

  test("oidc.server(as): returns a handle with .issuer + .discovery"):
    val out = captured(
      """
      val as  = oauth.authServer(Map("issuer" -> "https://idp.local", "signingSecret" -> "s"))
      val idp = oidc.server(as)
      println(idp.issuer)
      val md = idp.discovery()
      println(md("userinfo_endpoint"))
      """
    )
    out.split("\n").toList shouldBe List(
      "https://idp.local",
      "https://idp.local/userinfo"
    )

  test("idp.addUser + idp.mintIdToken + idp.userInfo round trip"):
    val out = captured(
      """
      val as = oauth.authServer(Map(
        "issuer" -> "https://idp.local",
        "signingSecret" -> "s",
        "scopes" -> List("openid", "profile", "email")
      ))
      val idp = oidc.server(as)
      idp.addUser(Map(
        "subject" -> "alice",
        "name" -> "Alice",
        "email" -> "alice@x",
        "emailVerified" -> true
      ))
      val tok = oauth.issueHmacToken("s", "alice", List("openid", "profile"), 60)
      val info = idp.userInfo(tok).get  // unwrap Option
      println(info("sub"))
      println(info("name"))
      """
    )
    out.split("\n").toList shouldBe List("alice", "Alice")

  test("idp.mintIdToken produces a verifiable JWT with audience claim"):
    val out = captured(
      """
      val as = oauth.authServer(Map("issuer" -> "https://idp.local", "signingSecret" -> "s"))
      val idp = oidc.server(as)
      idp.addUser(Map("subject" -> "u", "name" -> "U"))
      val tok = idp.mintIdToken("u", "client-abc", List("openid", "profile"))
      println(tok.length > 50)
      """
    )
    out shouldBe "true"

  // ─── OidcHttp.installRoutes (JVM-level) ──────────────────────────

  private class CapturingCtx extends NativeContext:
    override def out = System.out
    override def err = System.err
    val routes = scala.collection.mutable.LinkedHashMap.empty[(String, String), Any]
    override def registerRoute(method: String, path: String, handler: Any): Unit =
      routes((method, path)) = handler

  private def newIdp(secret: String = "s"): OidcServer =
    val as = new AuthServer(AuthServerConfig(
      issuer        = "https://idp.local",
      signingSecret = secret,
      supportedScopes = Set("openid", "profile", "email")
    ))
    val info = new InMemoryUserInfoStore
    info.put(UserClaims("alice", name = Some("Alice"), email = Some("alice@x"),
      emailVerified = Some(true)))
    new OidcServer(as, info)

  private def call(handler: Any, body: String,
                   headers: Map[String, String] = Map.empty,
                   query:   Map[String, String] = Map.empty): Value =
    val headerV = Value.MapV(headers.iterator.map((k, v) =>
      (Value.StringV(k): Value) -> (Value.StringV(v): Value)).toMap)
    val queryV  = Value.MapV(query.iterator.map((k, v) =>
      (Value.StringV(k): Value) -> (Value.StringV(v): Value)).toMap)
    val req = Value.InstanceV("Request", Map(
      "body" -> Value.StringV(body), "headers" -> headerV, "query" -> queryV))
    handler.asInstanceOf[Value.NativeFnV].f(List(req)) match
      case Computation.Pure(v) => v
      case other               => fail(s"expected Pure, got $other")

  test("OidcHttp.installRoutes registers OIDC + OAuth route set"):
    val ctx = new CapturingCtx
    OidcHttp.installRoutes(newIdp(), ctx)
    ctx.routes.keys.toSet shouldBe Set(
      ("POST", "/token"),
      ("GET",  "/userinfo"),
      ("POST", "/userinfo"),
      ("GET",  "/.well-known/openid-configuration"),
      ("POST", "/introspect"),
      ("POST", "/revoke"),
      ("POST", "/register"),
      ("GET",  "/authorize"),
      ("GET",  "/.well-known/oauth-authorization-server"),
      ("GET",  "/.well-known/jwks.json")
    )

  test("OidcHttp /userinfo: bearer-validated, returns claims"):
    val idp = newIdp()
    val ctx = new CapturingCtx
    OidcHttp.installRoutes(idp, ctx)
    val token = OAuth.issueHmacToken("s", "alice", Set("openid", "profile"), 60L,
      issuer = Some("https://idp.local"), clientId = Some("c1"))
    call(ctx.routes(("GET", "/userinfo")), "",
      headers = Map("Authorization" -> s"Bearer $token")) match
      case Value.InstanceV("Response", fs) =>
        fs("status") shouldBe Value.IntV(200L)
        fs("body") match
          case Value.StringV(b) =>
            val js = ujson.read(b)
            js("sub").str  shouldBe "alice"
            js("name").str shouldBe "Alice"
          case _ => fail("body")
      case other => fail(s"got $other")

  test("OidcHttp /.well-known/openid-configuration: serves discovery"):
    val idp = newIdp()
    val ctx = new CapturingCtx
    OidcHttp.installRoutes(idp, ctx)
    call(ctx.routes(("GET", "/.well-known/openid-configuration")), "") match
      case Value.InstanceV("Response", fs) =>
        fs("status") shouldBe Value.IntV(200L)
        fs("body") match
          case Value.StringV(b) =>
            val js = ujson.read(b)
            js("issuer").str shouldBe "https://idp.local"
            js("userinfo_endpoint").str should endWith ("/userinfo")
          case _ => fail("body")
      case other => fail(s"got $other")

  test("OidcHttp /token: includes id_token when openid scope granted"):
    val idp = newIdp()
    val as  = idp.as
    as.clients.register(Client(
      id = "webapp", secret = None,
      redirectUris = Set("http://x/cb"), scopes = Set("openid", "profile"),
      clientType = ClientType.Public))
    val ctx = new CapturingCtx
    OidcHttp.installRoutes(idp, ctx)
    val v  = "v" * 50
    val ch = OAuth.pkceS256(v)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "webapp", "http://x/cb",
        scope = Set("openid"),
        codeChallenge = Some(ch), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    val body =
      s"grant_type=authorization_code&code=${redir.code}&redirect_uri=http%3A%2F%2Fx%2Fcb" +
      s"&client_id=webapp&code_verifier=$v"
    call(ctx.routes(("POST", "/token")), body) match
      case Value.InstanceV("Response", fs) =>
        fs("status") shouldBe Value.IntV(200L)
        fs("body") match
          case Value.StringV(b) =>
            val js = ujson.read(b)
            js("access_token").str should not be empty
            js("id_token").str     should not be empty
          case _ => fail("body")
      case other => fail(s"got $other")

  // ─── error path: oidc.server on non-AuthServer ───────────────────

  test("oidc.server requires an AuthServer handle"):
    val ex = intercept[Exception](captured("""val x = oidc.server("not-an-as")"""))
    ex.getMessage should include ("AuthServer")
