package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — Iter MM production hardening: TLS-only enforcement,
 *  CORS policy, AuthEvent audit hook. */
class OAuthProductionHardeningTest extends AnyFunSuite with Matchers:

  private def newAs(cfg: AuthServerConfig => AuthServerConfig = identity): AuthServer =
    val as = new AuthServer(cfg(AuthServerConfig(
      issuer        = "https://auth.x",
      signingSecret = "k",
      supportedScopes = Set("read"))))
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential))
    as

  // ─── TLS enforcement ────────────────────────────────────────────

  test("TLS check: disabled by default — plain HTTP accepted"):
    val as = newAs()
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map("Host" -> "auth.x")) match
      case OAuthRoutes.RouteOutcome.Json(200, _, _) => succeed
      case other => fail(s"got $other")

  test("TLS check: enabled + plain HTTP non-loopback → 400 invalid_request"):
    val as = newAs(_.copy(requireTls = true))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map("Host" -> "auth.example.com")) match
      case OAuthRoutes.RouteOutcome.Json(400, js, _) =>
        js("error").str shouldBe "invalid_request"
        js("error_description").str should include ("TLS")
      case other => fail(s"got $other")

  test("TLS check: enabled + X-Forwarded-Proto: https → accepted"):
    val as = newAs(_.copy(requireTls = true))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body,
      Map("Host" -> "auth.example.com", "X-Forwarded-Proto" -> "https")) match
      case OAuthRoutes.RouteOutcome.Json(200, _, _) => succeed
      case other => fail(s"got $other")

  test("TLS check: enabled + loopback host always allowed"):
    val as = newAs(_.copy(requireTls = true))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    for host <- List("localhost", "localhost:8080", "127.0.0.1", "127.0.0.1:8080") do
      OAuthRoutes.handleToken(as, body, Map("Host" -> host)) match
        case OAuthRoutes.RouteOutcome.Json(200, _, _) => ()
        case other => fail(s"host $host: $other")

  // ─── CORS ────────────────────────────────────────────────────────

  test("CORS disabled by default — no Access-Control-Allow-Origin"):
    val as = newAs()
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map("Origin" -> "https://app.example.com")) match
      case OAuthRoutes.RouteOutcome.Json(200, _, hdrs) =>
        hdrs.keys.find(_.equalsIgnoreCase("Access-Control-Allow-Origin")) shouldBe None
      case other => fail(s"got $other")

  test("CORS: matching origin → ACAO header added"):
    val as = newAs(_.copy(corsOrigins = Set("https://app.example.com")))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map("Origin" -> "https://app.example.com")) match
      case OAuthRoutes.RouteOutcome.Json(200, _, hdrs) =>
        hdrs("Access-Control-Allow-Origin") shouldBe "https://app.example.com"
        hdrs.keys should contain ("Access-Control-Allow-Methods")
        hdrs.keys should contain ("Access-Control-Allow-Headers")
        hdrs("Vary") shouldBe "Origin"
      case other => fail(s"got $other")

  test("CORS: wildcard '*' reflects any origin"):
    val as = newAs(_.copy(corsOrigins = Set("*")))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map("Origin" -> "https://anywhere.test")) match
      case OAuthRoutes.RouteOutcome.Json(200, _, hdrs) =>
        hdrs("Access-Control-Allow-Origin") shouldBe "*"
      case other => fail(s"got $other")

  test("CORS: non-allowed origin → no ACAO"):
    val as = newAs(_.copy(corsOrigins = Set("https://allowed.test")))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map("Origin" -> "https://blocked.test")) match
      case OAuthRoutes.RouteOutcome.Json(200, _, hdrs) =>
        hdrs.keys.find(_.equalsIgnoreCase("Access-Control-Allow-Origin")) shouldBe None
      case other => fail(s"got $other")

  // ─── AuthEvent audit hook ────────────────────────────────────────

  test("audit: TokenIssued fires on successful client_credentials"):
    val as = newAs()
    val events = scala.collection.mutable.ArrayBuffer.empty[AuthEvent]
    as.onAuthEvent = e => events += e
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map.empty)
    events.collect { case e: AuthEvent.TokenIssued => e }.size shouldBe 1
    events.find { case _: AuthEvent.TokenIssued => true; case _ => false } match
      case Some(AuthEvent.TokenIssued(cid, sub, scope, gt)) =>
        cid   shouldBe "svc"
        sub   shouldBe "svc"
        scope shouldBe Set("read")
        gt    shouldBe "client_credentials"
      case _ => fail("no TokenIssued event")

  test("audit: TokenRefused fires on wrong secret"):
    val as = newAs()
    val events = scala.collection.mutable.ArrayBuffer.empty[AuthEvent]
    as.onAuthEvent = e => events += e
    val body = "grant_type=client_credentials&client_id=svc&client_secret=WRONG&scope=read"
    OAuthRoutes.handleToken(as, body, Map.empty)
    events.find { case _: AuthEvent.TokenRefused => true; case _ => false } match
      case Some(AuthEvent.TokenRefused(_, _, error, _)) => error shouldBe "invalid_client"
      case _ => fail("no TokenRefused event")

  test("audit: ClientRegistered fires on DCR"):
    val as = newAs()
    val events = scala.collection.mutable.ArrayBuffer.empty[AuthEvent]
    as.onAuthEvent = e => events += e
    val md = ujson.Obj(
      "redirect_uris" -> ujson.Arr("http://x/cb"),
      "client_name"   -> "Demo")
    as.registerClient(md)
    events.collect { case e: AuthEvent.ClientRegistered => e }.size shouldBe 1

  test("audit: AuthorizationCodeIssued fires on issueAuthorizationCode"):
    val as = newAs()
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("read"), clientType = ClientType.Public))
    val events = scala.collection.mutable.ArrayBuffer.empty[AuthEvent]
    as.onAuthEvent = e => events += e
    as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb", Set("read"),
        codeChallenge = Some(OAuth.pkceS256("v" * 50)),
        codeChallengeMethod = Some("S256")),
      "alice")
    events.collect { case e: AuthEvent.AuthorizationCodeIssued => e }.size shouldBe 1

  test("audit: RefreshFamilyBurned fires on reuse detection"):
    val as = newAs()
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("read"), clientType = ClientType.Public))
    val v = "v" * 50
    val ch = OAuth.pkceS256(v)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb", Set("read"),
        codeChallenge = Some(ch), codeChallengeMethod = Some("S256")),
      "alice").asInstanceOf[AuthorizationOutcome.CodeRedirect]
    val first = as.issueToken(TokenRequest.AuthorizationCodeGrant(
      redir.code, "http://x/cb", "c1", codeVerifier = Some(v)
    )).asInstanceOf[TokenOutcome.Issued].response
    // Rotate once
    as.issueToken(TokenRequest.RefreshTokenGrant(first.refreshToken.get, clientId = "c1"))
    // Hook attaches AFTER successful rotation; only the burn event matters now
    val events = scala.collection.mutable.ArrayBuffer.empty[AuthEvent]
    as.onAuthEvent = e => events += e
    // Replay the original refresh → reuse detection burns the family
    as.issueToken(TokenRequest.RefreshTokenGrant(first.refreshToken.get, clientId = "c1"))
    events.collect { case e: AuthEvent.RefreshFamilyBurned => e }.size shouldBe 1
    events.find { case _: AuthEvent.RefreshFamilyBurned => true; case _ => false } match
      case Some(AuthEvent.RefreshFamilyBurned(_, reason, _)) =>
        reason shouldBe "reuse_detected"
      case _ => fail("no RefreshFamilyBurned event")

  test("audit hook exceptions don't break the hot path"):
    val as = newAs()
    as.onAuthEvent = _ => throw new RuntimeException("listener exploded")
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    // Must still succeed even with a poisoned listener
    OAuthRoutes.handleToken(as, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(200, _, _) => succeed
      case other => fail(s"got $other")
