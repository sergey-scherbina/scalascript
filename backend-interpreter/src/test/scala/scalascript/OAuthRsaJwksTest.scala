package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — RS256 signer + JWKS endpoint.  Verifies the production-
 *  grade asymmetric signing path: AS holds the private key, publishes
 *  the public key as JWKS, resource servers validate locally. */
class OAuthRsaJwksTest extends AnyFunSuite with Matchers:

  // ─── HmacTokenSigner round-trip ──────────────────────────────────

  test("HmacTokenSigner round-trips a payload"):
    val s = new OAuth.HmacTokenSigner("secret")
    s.alg     shouldBe "HS256"
    s.publicJwk shouldBe None  // symmetric — no public key
    val token = s.sign(OAuth.buildAccessTokenPayload(
      "alice", Set("read"), 60L, issuer = Some("https://x")))
    s.verify(token) match
      case Right(p) =>
        p("sub").str   shouldBe "alice"
        p("scope").str shouldBe "read"
        p("iss").str   shouldBe "https://x"
      case other => fail(s"got $other")

  test("HmacTokenSigner with kid embeds kid in JWT header"):
    val s = new OAuth.HmacTokenSigner("k", kid = Some("hmac-1"))
    val token = s.sign(ujson.Obj("sub" -> "u"))
    val headerB64 = token.split('.')(0)
    val header    = ujson.read(java.util.Base64.getUrlDecoder.decode(headerB64))
    header("alg").str shouldBe "HS256"
    header("kid").str shouldBe "hmac-1"

  // ─── RsaTokenSigner ─────────────────────────────────────────────

  test("RsaTokenSigner.generate creates a working signer"):
    val s = OAuth.RsaTokenSigner.generate("rsa-test")
    s.alg shouldBe "RS256"
    s.kid shouldBe Some("rsa-test")
    s.publicJwk.isDefined shouldBe true

  test("RsaTokenSigner round-trips a payload"):
    val s = OAuth.RsaTokenSigner.generate("k1")
    val token = s.sign(OAuth.buildAccessTokenPayload(
      "alice", Set("read"), 60L, issuer = Some("https://x")))
    // Verify the JWT header advertises RS256 + kid
    val headerB64 = token.split('.')(0)
    val header    = ujson.read(java.util.Base64.getUrlDecoder.decode(headerB64))
    header("alg").str shouldBe "RS256"
    header("kid").str shouldBe "k1"
    s.verify(token) match
      case Right(p) => p("sub").str shouldBe "alice"
      case other    => fail(s"got $other")

  test("RsaTokenSigner rejects tampered signature"):
    val s     = OAuth.RsaTokenSigner.generate()
    val token = s.sign(ujson.Obj("sub" -> "u"))
    val parts = token.split('.')
    val tampered = parts(0) + "." + parts(1) + "." + parts(2).dropRight(2) + "XY"
    s.verify(tampered) shouldBe a[Left[?, ?]]

  test("RsaTokenSigner rejects expired tokens"):
    val s = OAuth.RsaTokenSigner.generate()
    val expired = s.sign(OAuth.buildAccessTokenPayload(
      "u", Set("x"), -1L))  // exp in the past
    s.verify(expired) match
      case Left(reason) => reason should include ("expired")
      case other        => fail(s"got $other")

  test("Cross-signer: RS256-signed token does NOT validate with HS256 signer"):
    val rsa   = OAuth.RsaTokenSigner.generate()
    val hmac  = new OAuth.HmacTokenSigner("guess")
    val token = rsa.sign(ujson.Obj("sub" -> "u", "exp" -> ujson.Num(java.time.Instant.now.getEpochSecond + 60.0)))
    hmac.verify(token) shouldBe a[Left[?, ?]]

  // ─── RSA public JWK shape (RFC 7517 / 7518) ──────────────────────

  test("RSA public JWK has kty/n/e and matching kid + use=sig + alg=RS256"):
    val s = OAuth.RsaTokenSigner.generate("k42")
    val jwk = s.publicJwk.get
    jwk("kty").str shouldBe "RSA"
    jwk("use").str shouldBe "sig"
    jwk("alg").str shouldBe "RS256"
    jwk("kid").str shouldBe "k42"
    jwk("n").str   should not be empty
    jwk("e").str   should not be empty

  test("RSA JWK n/e drop the high-bit zero pad that BigInteger.toByteArray adds"):
    val s = OAuth.RsaTokenSigner.generate()
    val jwk = s.publicJwk.get
    val n   = java.util.Base64.getUrlDecoder.decode(jwk("n").str)
    // Modulus is 2048 bits = 256 bytes; sign-bit-pad would make it 257 — must be 256
    n.length shouldBe 256
    n(0) should not be 0.toByte  // leading byte is not the padding zero

  // ─── jwksDocument ────────────────────────────────────────────────

  test("jwksDocument: HMAC-only signer list yields empty 'keys'"):
    val js = OAuth.jwksDocument(List(new OAuth.HmacTokenSigner("s")))
    js("keys").arr shouldBe empty

  test("jwksDocument: includes one entry per asymmetric signer"):
    val s1 = OAuth.RsaTokenSigner.generate("a")
    val s2 = OAuth.RsaTokenSigner.generate("b")
    val js = OAuth.jwksDocument(List(s1, s2))
    js("keys").arr.length shouldBe 2
    js("keys").arr.map(_("kid").str).toSet shouldBe Set("a", "b")

  // ─── AS with RS256 signer ────────────────────────────────────────

  private def newRsAs: AuthServer =
    val cfg    = AuthServerConfig(
      issuer        = "https://auth.local",
      signingSecret = "unused-hmac-fallback",
      supportedScopes = Set("read"))
    val signer = OAuth.RsaTokenSigner.generate("as-key")
    val as     = new AuthServer(cfg, customSigner = Some(signer))
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential))
    as

  test("AS with RsaTokenSigner issues RS256-signed access tokens"):
    val as    = newRsAs
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("read"))).asInstanceOf[TokenOutcome.Issued].response.accessToken
    val header = ujson.read(java.util.Base64.getUrlDecoder.decode(token.split('.')(0)))
    header("alg").str shouldBe "RS256"
    header("kid").str shouldBe "as-key"

  test("AS with RsaTokenSigner: tokenValidator accepts AS-issued tokens"):
    val as    = newRsAs
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("read"))).asInstanceOf[TokenOutcome.Issued].response.accessToken
    as.tokenValidator(token) match
      case OAuth.AuthResult.Valid(c) => c.subject shouldBe "svc"
      case other => fail(s"got $other")

  test("AS with RsaTokenSigner: introspect returns active=true for valid tokens"):
    val as    = newRsAs
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("read"))).asInstanceOf[TokenOutcome.Issued].response.accessToken
    as.introspect(token).active shouldBe true

  test("AS with RsaTokenSigner: metadata advertises RS256 + jwks_uri"):
    val as = newRsAs
    val md = as.metadataJson()
    md("token_endpoint_auth_signing_alg_values_supported").arr.head.str shouldBe "RS256"
    md("jwks_uri").str should endWith ("/.well-known/jwks.json")

  test("AS with HmacTokenSigner (default): metadata OMITS jwks_uri"):
    val as = new AuthServer(AuthServerConfig("https://auth.local", "s"))
    val md = as.metadataJson()
    md.obj.contains("jwks_uri") shouldBe false

  test("AS with RsaTokenSigner: jwksJson publishes the public key"):
    val as = newRsAs
    val jwks = as.jwksJson
    jwks("keys").arr.length shouldBe 1
    jwks("keys").arr.head("kid").str shouldBe "as-key"

  test("/.well-known/jwks.json route returns 200 + JWKS body"):
    val as = newRsAs
    OAuthRoutes.handleJwks(as) match
      case OAuthRoutes.RouteOutcome.Json(200, js, hdrs) =>
        js("keys").arr.length shouldBe 1
        hdrs("Cache-Control") should include ("max-age=")
      case other => fail(s"got $other")
