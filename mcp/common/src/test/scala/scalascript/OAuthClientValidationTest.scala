package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — Iter LL: client SDK completeness — state CSRF helpers,
 *  JWKS-backed external JWT validation, OIDC id_token validation
 *  with iss + aud + nonce checks. */
class OAuthClientValidationTest extends AnyFunSuite with Matchers:

  // ─── State CSRF helpers ──────────────────────────────────────────

  test("freshState: returns a unique sufficiently-long token"):
    val a = OAuthClient.freshState()
    val b = OAuthClient.freshState()
    a should not be b
    a.length should be > 20

  test("verifyState: matching strings pass"):
    val s = OAuthClient.freshState()
    OAuthClient.verifyState(s, s) shouldBe true

  test("verifyState: mismatched / empty / wrong-length all fail"):
    OAuthClient.verifyState("abc", "abd") shouldBe false
    OAuthClient.verifyState("",    "abc") shouldBe false
    OAuthClient.verifyState("abc", "")    shouldBe false
    OAuthClient.verifyState("abc", "abcd") shouldBe false

  // ─── JWKS cache + external JWT validation ────────────────────────

  /** Stand up a minimal in-process AS so the JWKS cache has a real
   *  endpoint to fetch from.  Uses the existing OAuthHttp installer
   *  glue would be too heavy here — we just exercise the data path
   *  via direct route invocation. */
  private def asWithRsa: (AuthServer, OAuth.RsaTokenSigner) =
    val signer = OAuth.RsaTokenSigner.generate("k1")
    val as = new AuthServer(
      AuthServerConfig(
        issuer        = "https://auth.local",
        signingSecret = "unused",
        supportedScopes = Set("read")),
      customSigner = Some(signer))
    (as, signer)

  test("JwksCache rejects malformed tokens"):
    val (_, _) = asWithRsa
    val cache = new OAuthClient.JwksCache("http://localhost:0/no-such")
    // No network → empty cache → unknown kid → can't validate.
    OAuthClient.validateJwt("a.b.c", cache) shouldBe a[Left[?, ?]]

  test("validateJwt: 3-part token shape required"):
    val cache = new OAuthClient.JwksCache("http://localhost:0/x")
    OAuthClient.validateJwt("not-a-jwt", cache) match
      case Left(reason) => reason should include ("malformed")
      case Right(_)     => fail("should have failed")

  // ─── id_token validation (OIDC) ──────────────────────────────────

  test("validateIdToken: iss + aud + nonce match → Valid"):
    val (as, _) = asWithRsa
    // Hand-mint an id-token-style JWT via the signer.
    val payload = OAuth.buildAccessTokenPayload(
      subject = "alice", scopes = Set.empty, expiresInSeconds = 60L,
      issuer = Some("https://auth.local"),
      audience = Some("webapp"),
      extra = ujson.Obj("nonce" -> "n-xyz"))
    val idToken = as.signer.sign(payload)
    // Build a JWKS cache pre-loaded with the signer's public key
    // (bypass the HTTP fetch since we already have the key in hand).
    val cache = new OAuthClient.JwksCache("http://noop")
    seedCache(cache, as)
    OAuthClient.validateIdToken(idToken, cache,
      expectedIssuer   = "https://auth.local",
      expectedAudience = "webapp",
      expectedNonce    = Some("n-xyz")) match
      case OAuthClient.IdTokenResult.Valid(c) => c("sub").str shouldBe "alice"
      case other => fail(s"got $other")

  test("validateIdToken: iss mismatch → Invalid"):
    val (as, _) = asWithRsa
    val tok = as.signer.sign(OAuth.buildAccessTokenPayload(
      "alice", Set.empty, 60L,
      issuer = Some("https://auth.local"), audience = Some("webapp")))
    val cache = new OAuthClient.JwksCache("http://noop")
    seedCache(cache, as)
    OAuthClient.validateIdToken(tok, cache,
      expectedIssuer = "https://OTHER", expectedAudience = "webapp") match
      case OAuthClient.IdTokenResult.Invalid(reason) => reason should include ("iss")
      case other => fail(s"got $other")

  test("validateIdToken: aud mismatch → Invalid"):
    val (as, _) = asWithRsa
    val tok = as.signer.sign(OAuth.buildAccessTokenPayload(
      "alice", Set.empty, 60L,
      issuer = Some("https://auth.local"), audience = Some("webapp")))
    val cache = new OAuthClient.JwksCache("http://noop")
    seedCache(cache, as)
    OAuthClient.validateIdToken(tok, cache,
      expectedIssuer = "https://auth.local",
      expectedAudience = "different-app") match
      case OAuthClient.IdTokenResult.Invalid(reason) => reason should include ("aud")
      case other => fail(s"got $other")

  test("validateIdToken: nonce mismatch → Invalid"):
    val (as, _) = asWithRsa
    val tok = as.signer.sign(OAuth.buildAccessTokenPayload(
      "alice", Set.empty, 60L,
      issuer = Some("https://auth.local"), audience = Some("webapp"),
      extra = ujson.Obj("nonce" -> "actual")))
    val cache = new OAuthClient.JwksCache("http://noop")
    seedCache(cache, as)
    OAuthClient.validateIdToken(tok, cache,
      expectedIssuer = "https://auth.local",
      expectedAudience = "webapp",
      expectedNonce = Some("expected")) match
      case OAuthClient.IdTokenResult.Invalid(reason) => reason should include ("nonce")
      case other => fail(s"got $other")

  test("validateIdToken: aud as array — match if list contains expected"):
    val (as, _) = asWithRsa
    val payload = ujson.Obj(
      "sub" -> "alice",
      "iss" -> "https://auth.local",
      "iat" -> ujson.Num(java.time.Instant.now.getEpochSecond.toDouble),
      "exp" -> ujson.Num((java.time.Instant.now.getEpochSecond + 60).toDouble),
      "aud" -> ujson.Arr(ujson.Str("webapp"), ujson.Str("api-x")))
    val tok = as.signer.sign(payload)
    val cache = new OAuthClient.JwksCache("http://noop")
    seedCache(cache, as)
    OAuthClient.validateIdToken(tok, cache,
      expectedIssuer = "https://auth.local",
      expectedAudience = "api-x") shouldBe a[OAuthClient.IdTokenResult.Valid]

  // ─── Helper: shove the AS's public key into the JWKS cache ──────

  /** Reach into the cache's keys field via reflection — saves wiring
   *  the full HTTP fetch loop just to test the validation path.
   *  Production cache fetches via the real `/.well-known/jwks.json`. */
  private def seedCache(cache: OAuthClient.JwksCache, as: AuthServer): Unit =
    val signer = as.signer.asInstanceOf[OAuth.RsaTokenSigner]
    val kid = signer.kid.get
    // We can't directly access the private field; instead refresh to
    // populate the map via a server we mock locally.  Simpler: skip
    // refresh and use validateJwt() path that tolerates "force a
    // refresh".  Since refresh() with bad URI silently keeps stale,
    // we need a way to inject keys.  Workaround: call refresh() once
    // on a URI that returns the right JWKS — easiest is to hand-pickle
    // via reflection.
    val field = classOf[OAuthClient.JwksCache].getDeclaredField("keys")
    field.setAccessible(true)
    field.set(cache, Map(kid -> signer.publicKey))
    val ts = classOf[OAuthClient.JwksCache].getDeclaredField("fetchedAt")
    ts.setAccessible(true)
    ts.set(cache, java.lang.Long.valueOf(java.time.Instant.now.getEpochSecond))
