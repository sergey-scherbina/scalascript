package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*
import scalascript.oidc.*

/** v1.17.x — security correctness fixes (Iter JJ):
 *    - audience (`aud`) claim validation in OAuthGuard
 *    - OIDC `nonce` claim round-trip through the authorize / token flow
 *    - clock-skew tolerance on `exp` / `nbf` / `iat` JWT claims */
class OAuthSecurityCorrectnessTest extends AnyFunSuite with Matchers:

  // ─── Clock skew tolerance ───────────────────────────────────────

  test("decodeHmacToken: fresh token decodes cleanly"):
    val tok = OAuth.issueHmacToken("s", "alice", Set("read"), 60L)
    OAuth.decodeHmacToken("s", tok) shouldBe a[Right[?, ?]]

  test("decodeHmacToken: token expired beyond skew → Left"):
    val tok = OAuth.issueHmacToken("s", "alice", Set("read"), -120L)
    OAuth.decodeHmacToken("s", tok) match
      case Left(reason) => reason should include ("expired")
      case Right(_)     => fail("expected expired")

  test("decodeHmacToken: token just past exp but within skew → still accepted"):
    // exp = now-30s; skew = 60s by default → still valid
    val tok = OAuth.issueHmacToken("s", "alice", Set("read"), -30L)
    OAuth.decodeHmacToken("s", tok) shouldBe a[Right[?, ?]]

  test("decodeHmacToken: zero skew tolerance still works"):
    val tok = OAuth.issueHmacToken("s", "alice", Set("read"), 60L)
    OAuth.decodeHmacToken("s", tok, clockSkewSeconds = 0L) shouldBe a[Right[?, ?]]

  test("validateJwtTimestamps: nbf in the future beyond skew → not yet valid"):
    val payload = ujson.Obj(
      "sub" -> "u",
      "nbf" -> ujson.Num((java.time.Instant.now.getEpochSecond + 600).toDouble))
    OAuth.validateJwtTimestamps(payload, clockSkewSeconds = 60L) match
      case Some(reason) => reason should include ("nbf")
      case None         => fail("expected not yet valid")

  test("validateJwtTimestamps: iat far in the future → rejected"):
    val payload = ujson.Obj(
      "sub" -> "u",
      "iat" -> ujson.Num((java.time.Instant.now.getEpochSecond + 7200).toDouble))
    OAuth.validateJwtTimestamps(payload, clockSkewSeconds = 60L) match
      case Some(reason) => reason should include ("future")
      case None         => fail("expected future-iat rejection")

  test("RsaTokenSigner: expired tokens fail (with default skew applied)"):
    val s = OAuth.RsaTokenSigner.generate()
    val payload = OAuth.buildAccessTokenPayload("u", Set("x"), -300L)
    val tok = s.sign(payload)
    s.verify(tok) shouldBe a[Left[?, ?]]

  // ─── Audience validation ────────────────────────────────────────

  test("OAuthGuard.check: aud match passes"):
    val tok = OAuth.issueHmacToken("s", "alice", Set("read"), 60L,
      audience = Some("api-a"))
    val v = OAuth.hmacValidator("s")
    OAuthGuard.check(
      Map("Authorization" -> s"Bearer $tok"), v,
      expectedAudience = Some("api-a")) match
      case OAuthGuard.GuardDecision.Allow(c) => c.subject shouldBe "alice"
      case other => fail(s"got $other")

  test("OAuthGuard.check: aud mismatch → 401 invalid_token"):
    val tok = OAuth.issueHmacToken("s", "alice", Set("read"), 60L,
      audience = Some("api-other"))
    val v = OAuth.hmacValidator("s")
    OAuthGuard.check(
      Map("Authorization" -> s"Bearer $tok"), v,
      expectedAudience = Some("api-a")) match
      case OAuthGuard.GuardDecision.Deny(OAuthRoutes.RouteOutcome.Json(401, js, _)) =>
        js("error").str shouldBe "invalid_token"
        js("error_description").str should include ("audience")
      case other => fail(s"got $other")

  test("OAuthGuard.check: no expectedAudience → audience-less tokens pass"):
    val tok = OAuth.issueHmacToken("s", "alice", Set("read"), 60L)
    val v = OAuth.hmacValidator("s")
    // No expectedAudience supplied → no check
    OAuthGuard.check(Map("Authorization" -> s"Bearer $tok"), v) match
      case OAuthGuard.GuardDecision.Allow(_) => succeed
      case other => fail(s"got $other")

  test("OAuthGuard.check: expectedAudience set + token lacks aud → rejected"):
    val tok = OAuth.issueHmacToken("s", "alice", Set("read"), 60L)  // no audience
    val v = OAuth.hmacValidator("s")
    OAuthGuard.check(
      Map("Authorization" -> s"Bearer $tok"), v,
      expectedAudience = Some("api-a")) match
      case OAuthGuard.GuardDecision.Deny(_) => succeed
      case other => fail(s"got $other")

  test("aud as JSON array — match if list contains expected"):
    // Build a token where `aud` is an array claim (RFC 7519 §4.1.3 allows both)
    val payload = ujson.Obj(
      "sub"   -> "alice",
      "scope" -> "read",
      "iat"   -> ujson.Num(java.time.Instant.now.getEpochSecond.toDouble),
      "exp"   -> ujson.Num((java.time.Instant.now.getEpochSecond + 60).toDouble),
      "aud"   -> ujson.Arr(ujson.Str("api-a"), ujson.Str("api-b")))
    val signer = new OAuth.HmacTokenSigner("s")
    val tok    = signer.sign(payload)
    val v      = OAuth.hmacValidator("s")
    OAuthGuard.check(
      Map("Authorization" -> s"Bearer $tok"), v,
      expectedAudience = Some("api-b")) match
      case OAuthGuard.GuardDecision.Allow(_) => succeed
      case other => fail(s"got $other")

  // ─── OIDC nonce round-trip ──────────────────────────────────────

  test("OIDC nonce: stored at /authorize, echoed in id_token at /token"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://idp.x", signingSecret = "s",
      supportedScopes = Set("openid")))
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("openid"), clientType = ClientType.Public))
    val idp = new OidcServer(as)
    idp.userInfo.put(UserClaims("alice"))
    val verifier  = "v" * 50
    val challenge = OAuth.pkceS256(verifier)
    // /authorize step — supply the nonce
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb", Set("openid"),
        codeChallenge = Some(challenge), codeChallengeMethod = Some("S256"),
        nonce = Some("abc-nonce-123")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    // /token step — minted id_token must carry the nonce
    val resp = idp.issueToken(TokenRequest.AuthorizationCodeGrant(
      redir.code, "http://x/cb", "c1", codeVerifier = Some(verifier)
    )).asInstanceOf[TokenOutcome.Issued].response
    val idClaims = OAuth.decodeHmacToken("s", resp.idToken.get).toOption.get
    idClaims("nonce").str shouldBe "abc-nonce-123"

  test("OIDC: id_token has no nonce when client didn't supply one"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://idp.x", signingSecret = "s",
      supportedScopes = Set("openid")))
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("openid"), clientType = ClientType.Public))
    val idp = new OidcServer(as)
    idp.userInfo.put(UserClaims("alice"))
    val verifier  = "v" * 50
    val challenge = OAuth.pkceS256(verifier)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb", Set("openid"),
        codeChallenge = Some(challenge), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    val resp = idp.issueToken(TokenRequest.AuthorizationCodeGrant(
      redir.code, "http://x/cb", "c1", codeVerifier = Some(verifier)
    )).asInstanceOf[TokenOutcome.Issued].response
    val idClaims = OAuth.decodeHmacToken("s", resp.idToken.get).toOption.get
    idClaims.obj.contains("nonce") shouldBe false

  test("OIDC nonce: /authorize route accepts nonce query param"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://idp.x", signingSecret = "s",
      supportedScopes = Set("openid")))
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("openid"), clientType = ClientType.Public))
    val query = Map(
      "response_type"         -> "code",
      "client_id"             -> "c1",
      "redirect_uri"          -> "http://x/cb",
      "scope"                 -> "openid",
      "state"                 -> "s1",
      "nonce"                 -> "real-nonce-9",
      "code_challenge"        -> OAuth.pkceS256("v" * 50),
      "code_challenge_method" -> "S256")
    OAuthRoutes.handleAuthorize(as, query, Map.empty, _ => Some("alice")) match
      case OAuthRoutes.RouteOutcome.Redirect(302, _) => succeed
      case other => fail(s"got $other")
    // Nonce was stashed in the AS's pendingNonces — we can pull it
    // back with consumeNonceForSubject.
    as.consumeNonceForSubject("alice", Set("openid")) shouldBe Some("real-nonce-9")
