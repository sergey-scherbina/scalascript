package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — refresh-token family tracking + reuse-detection + rate
 *  limiting (Iter KK).  Production-grade hardening on top of the
 *  baseline OAuth 2.1 §6.1 single-use rotation. */
class OAuthRefreshFamilyTest extends AnyFunSuite with Matchers:

  private def newAs(rl: RateLimiter = RateLimiter.Disabled): AuthServer =
    val as = new AuthServer(
      AuthServerConfig(issuer = "https://auth.x", signingSecret = "k",
        supportedScopes = Set("read", "write")),
      rateLimiter = rl)
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("read", "write"), clientType = ClientType.Public))
    as

  private def authCodeRoundTrip(as: AuthServer, scope: Set[String] = Set("read")): TokenResponse =
    val v  = "v" * 50
    val ch = OAuth.pkceS256(v)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb", scope,
        codeChallenge = Some(ch), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    as.issueToken(TokenRequest.AuthorizationCodeGrant(
      redir.code, "http://x/cb", "c1", codeVerifier = Some(v)
    )).asInstanceOf[TokenOutcome.Issued].response

  // ─── Family ID inheritance ────────────────────────────────────────

  test("refresh tokens share a familyId across rotations"):
    val as = newAs()
    val first = authCodeRoundTrip(as)
    val rec1  = as.tokens.findRefreshToken(first.refreshToken.get).get
    val rotated = as.issueToken(TokenRequest.RefreshTokenGrant(
      refreshToken = first.refreshToken.get, clientId = "c1"
    )).asInstanceOf[TokenOutcome.Issued].response
    val rec2 = as.tokens.findRefreshToken(rotated.refreshToken.get).get
    rec1.familyId shouldBe rec2.familyId  // inherited across rotation
    rec1.token should not be rec2.token   // but different actual tokens

  test("fresh authorization grants get a brand-new family id"):
    val as = newAs()
    val r1 = authCodeRoundTrip(as)
    val r2 = authCodeRoundTrip(as)
    val f1 = as.tokens.findRefreshToken(r1.refreshToken.get).get.familyId
    val f2 = as.tokens.findRefreshToken(r2.refreshToken.get).get.familyId
    f1 should not be f2

  // ─── Reuse detection burns the family ────────────────────────────

  test("reusing a rotated refresh token burns the family"):
    val as = newAs()
    val initial = authCodeRoundTrip(as)
    val refreshed = as.issueToken(TokenRequest.RefreshTokenGrant(
      refreshToken = initial.refreshToken.get, clientId = "c1"
    )).asInstanceOf[TokenOutcome.Issued].response
    // Replay the initial (now-rotated) token — should burn the family.
    as.issueToken(TokenRequest.RefreshTokenGrant(
      refreshToken = initial.refreshToken.get, clientId = "c1"
    )) match
      case TokenOutcome.Error(code, descr) =>
        code shouldBe "invalid_grant"
        descr should include ("reuse")
      case other => fail(s"got $other")
    // The legitimate descendant should now ALSO be revoked.
    as.issueToken(TokenRequest.RefreshTokenGrant(
      refreshToken = refreshed.refreshToken.get, clientId = "c1"
    )) match
      case TokenOutcome.Error(code, descr) =>
        code shouldBe "invalid_grant"
        descr should (include ("family") or include ("not found"))
      case other => fail(s"got $other")

  test("graveyardLookup: rotated tokens recoverable until evicted"):
    val store = new InMemoryTokenStore()
    val rec = RefreshTokenRecord("tok-1", "c1", "alice", Set("read"),
      java.time.Instant.now.getEpochSecond + 3600L, "family-1")
    store.saveRefreshToken(rec)
    store.findRefreshToken("tok-1").isDefined shouldBe true
    // Simulate rotation: revoke + graveyard
    store.revokeRefreshToken("tok-1")
    store.graveyardAdd("tok-1", "family-1")
    store.findRefreshToken("tok-1") shouldBe None       // gone from active
    store.graveyardLookup("tok-1")  shouldBe Some("family-1")
    store.graveyardLookup("unknown") shouldBe None

  test("revokeRefreshFamily clears every member + flags isFamilyRevoked"):
    val store = new InMemoryTokenStore()
    val now = java.time.Instant.now.getEpochSecond + 3600L
    store.saveRefreshToken(RefreshTokenRecord("t1", "c", "alice", Set.empty, now, "fam"))
    store.saveRefreshToken(RefreshTokenRecord("t2", "c", "alice", Set.empty, now, "fam"))
    store.saveRefreshToken(RefreshTokenRecord("t3", "c", "alice", Set.empty, now, "other"))
    store.isFamilyRevoked("fam") shouldBe false
    val burned = store.revokeRefreshFamily("fam")
    burned shouldBe 2
    store.isFamilyRevoked("fam")   shouldBe true
    store.findRefreshToken("t1")   shouldBe None
    store.findRefreshToken("t2")   shouldBe None
    store.findRefreshToken("t3").isDefined shouldBe true  // other family unaffected

  test("burned family refuses subsequent refresh even if token record lingers"):
    val store = new InMemoryTokenStore()
    val now = java.time.Instant.now.getEpochSecond + 3600L
    store.saveRefreshToken(RefreshTokenRecord("t1", "c1", "alice", Set("read"), now, "fam-1"))
    store.revokeRefreshFamily("fam-1")
    // Burned family pre-empts a hypothetical token that snuck back in.
    store.saveRefreshToken(RefreshTokenRecord("t1", "c1", "alice", Set("read"), now, "fam-1"))
    // Manually wire the loaded store to a fresh AS to simulate restart
    // restoring a per-token entry without remembering the burn list —
    // family-deny is the failsafe in that case.
    val as2 = new AuthServer(
      AuthServerConfig(issuer = "https://auth.x", signingSecret = "k"),
      tokens = store)
    as2.clients.register(Client("c1", None, Set("http://x/cb"), Set("read"),
      clientType = ClientType.Public))
    as2.issueToken(TokenRequest.RefreshTokenGrant("t1", clientId = "c1")) match
      case TokenOutcome.Error(_, descr) => descr should include ("family")
      case other => fail(s"got $other")

  // ─── Rate limiting ───────────────────────────────────────────────

  test("RateLimiter.TokenBucket: capacity caps the burst"):
    val rl = new RateLimiter.TokenBucket(capacity = 3, refillRatePerSec = 0.0)
    (1 to 3).foreach(_ => rl.allow("key-a") shouldBe true)
    rl.allow("key-a") shouldBe false  // burst exhausted

  test("RateLimiter.TokenBucket: refill restores capacity over time"):
    // 100 tokens/sec → 10ms per token; we sleep 50ms after exhausting.
    val rl = new RateLimiter.TokenBucket(capacity = 2, refillRatePerSec = 100.0)
    rl.allow("k") shouldBe true
    rl.allow("k") shouldBe true
    rl.allow("k") shouldBe false  // burst spent
    Thread.sleep(50)
    rl.allow("k") shouldBe true   // refilled ~5 tokens → 2 max

  test("RateLimiter.TokenBucket: keys are independent buckets"):
    val rl = new RateLimiter.TokenBucket(capacity = 1, refillRatePerSec = 0.0)
    rl.allow("a") shouldBe true
    rl.allow("a") shouldBe false
    rl.allow("b") shouldBe true   // new key — fresh bucket

  test("RateLimiter.Disabled: always passes"):
    (1 to 1000).foreach(_ => RateLimiter.Disabled.allow("anything") shouldBe true)

  // ─── Wire-layer rate limit on /token ──────────────────────────────

  test("/token: rate-limited requests return 429 with Retry-After"):
    val rl = new RateLimiter.TokenBucket(capacity = 1, refillRatePerSec = 0.0)
    val as = newAs(rl)
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map.empty) shouldBe a[OAuthRoutes.RouteOutcome.Json]  // 1st passes
    // 2nd hits the burst cap
    OAuthRoutes.handleToken(as, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(429, js, hdrs) =>
        js("error").str shouldBe "slow_down"
        hdrs("Retry-After") shouldBe "5"
      case other => fail(s"got $other")
