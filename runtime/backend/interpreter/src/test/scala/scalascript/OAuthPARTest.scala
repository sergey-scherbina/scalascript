package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** PAR (RFC 9126 Pushed Authorization Requests) test suite.
 *
 *  §1  AuthServer.pushAuthorizationRequest — core push logic
 *  §2  InMemoryPushedAuthRequestStore — store semantics
 *  §3  OAuthRoutes.handlePar — HTTP endpoint
 *  §4  OAuthRoutes.handleAuthorize with request_uri — PAR redemption
 *  §5  parRequired enforcement
 *  §6  AS metadata — discovery doc fields */
class OAuthPARTest extends AnyFunSuite with Matchers:

  // ─── helpers ───────────────────────────────────────────────────────────

  private val ParPrefix = "urn:ietf:params:oauth:request_uri:"

  private def makeAs(parRequired: Boolean = false): AuthServer =
    val cfg = AuthServerConfig(
      issuer        = "https://auth.example",
      signingSecret = "test-secret-at-least-32-bytes-!!!!",
      parRequired   = parRequired
    )
    val as = new AuthServer(cfg)
    as.clients.register(Client(
      id           = "app",
      secret       = Some(OAuth.hashSecret("secret")),
      redirectUris = Set("https://app.example/cb"),
      scopes       = Set("read", "write"),
      clientType   = ClientType.Confidential,
      grantTypes   = Set("authorization_code", "refresh_token")
    ))
    as.clients.register(Client(
      id           = "pub",
      secret       = None,
      redirectUris = Set("https://pub.example/cb"),
      scopes       = Set("read"),
      clientType   = ClientType.Public
    ))
    as

  private val baseParams = Map(
    "client_id"             -> "app",
    "client_secret"         -> "secret",
    "response_type"         -> "code",
    "redirect_uri"          -> "https://app.example/cb",
    "scope"                 -> "read",
    "state"                 -> "xyz",
    "code_challenge"        -> "abc",
    "code_challenge_method" -> "plain"
  )

  // params without credential fields — used when auth comes via Basic header
  private val baseParamsNoCreds = baseParams - "client_id" - "client_secret"

  private def subjectAlice: Map[String, String] => Option[String] = _ => Some("alice")

  // ─── §1  AuthServer.pushAuthorizationRequest ──────────────────────────

  test("push — valid request returns Pushed with urn prefix") {
    val as = makeAs()
    val out = as.pushAuthorizationRequest("app", Some("secret"), baseParams)
    out shouldBe a[PushOutcome.Pushed]
    val p = out.asInstanceOf[PushOutcome.Pushed]
    p.requestUri should startWith(ParPrefix)
    p.expiresIn shouldBe as.config.parRequestTtlSeconds
  }

  test("push — unknown client returns Error invalid_client") {
    val as  = makeAs()
    val out = as.pushAuthorizationRequest("unknown", None, baseParams)
    out shouldBe a[PushOutcome.Error]
    out.asInstanceOf[PushOutcome.Error].error shouldBe "invalid_client"
  }

  test("push — wrong secret returns Error invalid_client") {
    val as  = makeAs()
    val out = as.pushAuthorizationRequest("app", Some("wrong"), baseParams)
    out shouldBe a[PushOutcome.Error]
    out.asInstanceOf[PushOutcome.Error].error shouldBe "invalid_client"
  }

  test("push — unregistered redirect_uri returns Error invalid_request") {
    val as  = makeAs()
    val out = as.pushAuthorizationRequest("app", Some("secret"),
      baseParams + ("redirect_uri" -> "https://evil.example/cb"))
    out shouldBe a[PushOutcome.Error]
    out.asInstanceOf[PushOutcome.Error].error shouldBe "invalid_request"
  }

  test("push — missing redirect_uri returns Error invalid_request") {
    val as  = makeAs()
    val out = as.pushAuthorizationRequest("app", Some("secret"),
      baseParams - "redirect_uri")
    out shouldBe a[PushOutcome.Error]
    out.asInstanceOf[PushOutcome.Error].error shouldBe "invalid_request"
  }

  test("push — public client accepted without secret") {
    val as  = makeAs()
    val out = as.pushAuthorizationRequest("pub", None,
      Map(
        "client_id"    -> "pub",
        "response_type" -> "code",
        "redirect_uri" -> "https://pub.example/cb"
      ))
    out shouldBe a[PushOutcome.Pushed]
  }

  test("push — each call produces a unique request_uri") {
    val as = makeAs()
    val r1 = as.pushAuthorizationRequest("app", Some("secret"), baseParams)
      .asInstanceOf[PushOutcome.Pushed].requestUri
    val r2 = as.pushAuthorizationRequest("app", Some("secret"), baseParams)
      .asInstanceOf[PushOutcome.Pushed].requestUri
    r1 should not equal r2
  }

  // ─── §2  InMemoryPushedAuthRequestStore ──────────────────────────────

  test("store — save then consume returns the record") {
    val store = new InMemoryPushedAuthRequestStore
    val rec = PushedAuthRequest(
      requestUri = s"${ParPrefix}abc",
      clientId   = "app",
      params     = Map("k" -> "v"),
      expiresAt  = java.time.Instant.now.getEpochSecond + 90
    )
    store.save(rec)
    store.consume(rec.requestUri) shouldBe Some(rec)
  }

  test("store — consume is single-use") {
    val store = new InMemoryPushedAuthRequestStore
    val rec = PushedAuthRequest(
      requestUri = s"${ParPrefix}once",
      clientId   = "app",
      params     = Map.empty,
      expiresAt  = java.time.Instant.now.getEpochSecond + 90
    )
    store.save(rec)
    store.consume(rec.requestUri) shouldBe defined
    store.consume(rec.requestUri) shouldBe None
  }

  test("store — expired record is rejected on consume") {
    val store = new InMemoryPushedAuthRequestStore
    val rec = PushedAuthRequest(
      requestUri = s"${ParPrefix}expired",
      clientId   = "app",
      params     = Map.empty,
      expiresAt  = java.time.Instant.now.getEpochSecond - 1  // already expired
    )
    store.save(rec)
    store.consume(rec.requestUri) shouldBe None
  }

  test("store — unknown request_uri returns None") {
    val store = new InMemoryPushedAuthRequestStore
    store.consume(s"${ParPrefix}nosuchuri") shouldBe None
  }

  // ─── §3  OAuthRoutes.handlePar ────────────────────────────────────────

  private def formBody(params: Map[String, String]): String =
    params.map { (k, v) => s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}" }
      .mkString("&")

  test("handlePar — 201 with request_uri + expires_in on valid push") {
    val as   = makeAs()
    val body = formBody(baseParams)
    val out  = OAuthRoutes.handlePar(as, body, Map.empty)
    out shouldBe a[OAuthRoutes.RouteOutcome.Json]
    val j = out.asInstanceOf[OAuthRoutes.RouteOutcome.Json]
    j.status shouldBe 201
    j.body("request_uri").str should startWith(ParPrefix)
    j.body("expires_in").num shouldBe as.config.parRequestTtlSeconds.toDouble
  }

  test("handlePar — Cache-Control no-store on success") {
    val as   = makeAs()
    val out  = OAuthRoutes.handlePar(as, formBody(baseParams), Map.empty)
      .asInstanceOf[OAuthRoutes.RouteOutcome.Json]
    out.extraHeaders.get("Cache-Control") shouldBe Some("no-store")
  }

  test("handlePar — 401 on bad client credentials") {
    val as   = makeAs()
    val body = formBody(baseParams + ("client_secret" -> "wrong-secret"))
    val out  = OAuthRoutes.handlePar(as, body, Map.empty)
    out.asInstanceOf[OAuthRoutes.RouteOutcome.Json].status shouldBe 401
  }

  test("handlePar — 400 on missing client_id") {
    val as   = makeAs()
    val out  = OAuthRoutes.handlePar(as, "", Map.empty)
    out.asInstanceOf[OAuthRoutes.RouteOutcome.Json].status shouldBe 400
  }

  test("handlePar — 400 on unregistered redirect_uri") {
    val as   = makeAs()
    val body = formBody(baseParams + ("redirect_uri" -> "https://evil.example/cb"))
    val out  = OAuthRoutes.handlePar(as, body, Map.empty)
    val j    = out.asInstanceOf[OAuthRoutes.RouteOutcome.Json]
    j.status shouldBe 400
    j.body("error").str shouldBe "invalid_request"
  }

  test("handlePar — Basic-auth client_id accepted") {
    val as      = makeAs()
    val encoded = java.util.Base64.getEncoder.encodeToString("app:secret".getBytes)
    val hdrs    = Map("Authorization" -> s"Basic $encoded")
    val body    = formBody(baseParamsNoCreds)
    val out     = OAuthRoutes.handlePar(as, body, hdrs)
    out.asInstanceOf[OAuthRoutes.RouteOutcome.Json].status shouldBe 201
  }

  // ─── §4  handleAuthorize with request_uri ─────────────────────────────

  test("authorize with request_uri — retrieves stored params and issues code redirect") {
    val as   = makeAs()
    val push = as.pushAuthorizationRequest("app", Some("secret"), baseParams)
      .asInstanceOf[PushOutcome.Pushed]
    val query = Map(
      "client_id"   -> "app",
      "request_uri" -> push.requestUri
    )
    val out = OAuthRoutes.handleAuthorize(as, query, Map.empty, subjectAlice)
    out shouldBe a[OAuthRoutes.RouteOutcome.Redirect]
    val r = out.asInstanceOf[OAuthRoutes.RouteOutcome.Redirect]
    r.location should startWith("https://app.example/cb?code=")
    r.location should include("state=xyz")
  }

  test("authorize with request_uri — single-use (second attempt → 400)") {
    val as   = makeAs()
    val push = as.pushAuthorizationRequest("app", Some("secret"), baseParams)
      .asInstanceOf[PushOutcome.Pushed]
    val query = Map("client_id" -> "app", "request_uri" -> push.requestUri)
    OAuthRoutes.handleAuthorize(as, query, Map.empty, subjectAlice)  // consume
    val out2 = OAuthRoutes.handleAuthorize(as, query, Map.empty, subjectAlice)
    val j = out2.asInstanceOf[OAuthRoutes.RouteOutcome.Json]
    j.status shouldBe 400
    j.body("error").str shouldBe "invalid_request"
  }

  test("authorize with request_uri — unknown uri → 400") {
    val as    = makeAs()
    val query = Map("client_id" -> "app", "request_uri" -> s"${ParPrefix}nosuchone")
    val out   = OAuthRoutes.handleAuthorize(as, query, Map.empty, subjectAlice)
    out.asInstanceOf[OAuthRoutes.RouteOutcome.Json].status shouldBe 400
  }

  test("authorize with request_uri — client_id mismatch → 400") {
    val as   = makeAs()
    val push = as.pushAuthorizationRequest("app", Some("secret"), baseParams)
      .asInstanceOf[PushOutcome.Pushed]
    val query = Map(
      "client_id"   -> "pub",           // different from stored "app"
      "request_uri" -> push.requestUri
    )
    val out = OAuthRoutes.handleAuthorize(as, query, Map.empty, subjectAlice)
    val j   = out.asInstanceOf[OAuthRoutes.RouteOutcome.Json]
    j.status shouldBe 400
    j.body("error").str shouldBe "invalid_request"
  }

  test("authorize with request_uri — no client_id in authorize query is ok (use stored)") {
    val as   = makeAs()
    val push = as.pushAuthorizationRequest("app", Some("secret"), baseParams)
      .asInstanceOf[PushOutcome.Pushed]
    // no client_id in the /authorize query — should still work
    val query = Map("request_uri" -> push.requestUri)
    val out = OAuthRoutes.handleAuthorize(as, query, Map.empty, subjectAlice)
    out shouldBe a[OAuthRoutes.RouteOutcome.Redirect]
  }

  // ─── §5  parRequired enforcement ──────────────────────────────────────

  test("parRequired=true — direct params → 400") {
    val as  = makeAs(parRequired = true)
    val out = OAuthRoutes.handleAuthorize(as, baseParams, Map.empty, subjectAlice)
    val j   = out.asInstanceOf[OAuthRoutes.RouteOutcome.Json]
    j.status shouldBe 400
    j.body("error").str shouldBe "invalid_request"
    j.body("error_description").str should include("pushed authorization request required")
  }

  test("parRequired=true — request_uri flow still works") {
    val as   = makeAs(parRequired = true)
    val push = as.pushAuthorizationRequest("app", Some("secret"), baseParams)
      .asInstanceOf[PushOutcome.Pushed]
    val query = Map("client_id" -> "app", "request_uri" -> push.requestUri)
    val out   = OAuthRoutes.handleAuthorize(as, query, Map.empty, subjectAlice)
    out shouldBe a[OAuthRoutes.RouteOutcome.Redirect]
  }

  test("parRequired=false (default) — direct params are still accepted") {
    val as  = makeAs(parRequired = false)
    val out = OAuthRoutes.handleAuthorize(as, baseParams, Map.empty, subjectAlice)
    out shouldBe a[OAuthRoutes.RouteOutcome.Redirect]
  }

  // ─── §6  AS metadata ──────────────────────────────────────────────────

  test("metadata — pushed_authorization_request_endpoint always present") {
    val as  = makeAs()
    val doc = as.metadataJson()
    doc.obj.contains("pushed_authorization_request_endpoint") shouldBe true
    doc("pushed_authorization_request_endpoint").str should include("/par")
  }

  test("metadata — require_pushed_authorization_requests only when parRequired=true") {
    val asOpt = makeAs(parRequired = false)
    asOpt.metadataJson().obj.contains("require_pushed_authorization_requests") shouldBe false

    val asReq = makeAs(parRequired = true)
    asReq.metadataJson()("require_pushed_authorization_requests").bool shouldBe true
  }
