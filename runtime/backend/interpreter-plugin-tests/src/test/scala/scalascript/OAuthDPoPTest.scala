package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.interfaces.{ECPublicKey, RSAPublicKey}
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPairGenerator, Signature}
import java.util.Base64

/** DPoP (RFC 9449) — sender-constrained tokens for the OAuth 2.1 AS.
 *
 *  Tests are organised by layer:
 *  §1  DPoP.verifyProof — proof JWT validation (RS256 + ES256)
 *  §2  JWK thumbprint + access-token hash helpers
 *  §3  AuthServer.issueToken — cnf.jkt injection + token_type DPoP
 *  §4  OAuthRoutes.handleToken — DPoP header extraction + proof validation
 *  §5  OAuthGuard.check — resource-server DPoP binding check
 *  §6  InMemoryJtiStore — replay prevention */
class OAuthDPoPTest extends AnyFunSuite with Matchers:

  // ─── Key-generation helpers ─────────────────────────────────────────────

  private def genRsaKeyPair() =
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    kpg.generateKeyPair()

  private def genEcKeyPair(crv: String = "P-256") =
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(ECGenParameterSpec(crv match
      case "P-256" => "secp256r1"
      case "P-384" => "secp384r1"
      case _       => crv))
    kpg.generateKeyPair()

  private def b64u(bs: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bs)
  private def b64u(s: String): String =
    b64u(s.getBytes(StandardCharsets.UTF_8))

  private def stripLeadingZero(bs: Array[Byte]): Array[Byte] =
    if bs.length > 1 && bs(0) == 0 then bs.drop(1) else bs

  private def rsaJwk(pub: RSAPublicKey, kid: Option[String] = None): ujson.Value =
    val obj = ujson.Obj(
      "kty" -> "RSA",
      "n"   -> b64u(stripLeadingZero(pub.getModulus.toByteArray)),
      "e"   -> b64u(stripLeadingZero(pub.getPublicExponent.toByteArray))
    )
    kid.foreach(k => obj("kid") = k)
    obj

  private def ecJwk(pub: ECPublicKey, crv: String = "P-256", kid: Option[String] = None): ujson.Value =
    val params  = pub.getW
    val byteLen = crv match { case "P-256" => 32; case "P-384" => 48; case _ => 32 }
    def toBytes(bi: BigInteger): Array[Byte] =
      val bs = bi.toByteArray.dropWhile(_ == 0)
      Array.fill(byteLen - bs.length)(0.toByte) ++ bs
    val obj = ujson.Obj(
      "kty" -> "EC",
      "crv" -> crv,
      "x"   -> b64u(toBytes(params.getAffineX)),
      "y"   -> b64u(toBytes(params.getAffineY))
    )
    kid.foreach(k => obj("kid") = k)
    obj

  // Converts fixed-size R||S (JWT) to DER SEQUENCE for signing via ECDSA
  // (we need the reverse when building test proofs — sign via Java, get DER, convert to R||S)
  private def ecDerToJwt(der: Array[Byte], coordLen: Int): Array[Byte] =
    // Simple parser: skip SEQUENCE tag+len, then read two INTEGERs
    var pos = 2  // skip 0x30, total-len
    def readInt(): Array[Byte] =
      pos += 1  // 0x02 tag
      val len = der(pos) & 0xff; pos += 1
      val bs  = der.slice(pos, pos + len); pos += len
      bs.dropWhile(_ == 0)  // strip leading zero padding
    val r = readInt()
    val s = readInt()
    def padLeft(bs: Array[Byte]): Array[Byte] =
      Array.fill(coordLen - bs.length)(0.toByte) ++ bs
    padLeft(r) ++ padLeft(s)

  /** Build a DPoP proof JWT using an RSA private key. */
  private def rsaProof(
    kp:    java.security.KeyPair,
    htm:   String,
    htu:   String,
    jti:   String = OAuth.randomOpaqueToken(16),
    iatOffset: Long = 0L,
    nonce: Option[String] = None,
    ath:   Option[String] = None
  ): String =
    val pub     = kp.getPublic.asInstanceOf[RSAPublicKey]
    val priv    = kp.getPrivate
    val jwk     = rsaJwk(pub)
    val header  = ujson.Obj("typ" -> "dpop+jwt", "alg" -> "RS256", "jwk" -> jwk)
    val now     = java.time.Instant.now.getEpochSecond + iatOffset
    val payload = ujson.Obj("jti" -> jti, "htm" -> htm, "htu" -> htu,
                            "iat" -> ujson.Num(now.toDouble))
    nonce.foreach(n => payload("nonce") = n)
    ath.foreach(a => payload("ath") = a)
    val si   = b64u(header.render()) + "." + b64u(payload.render())
    val sig  = Signature.getInstance("SHA256withRSA")
    sig.initSign(priv)
    sig.update(si.getBytes(StandardCharsets.UTF_8))
    si + "." + b64u(sig.sign())

  /** Build a DPoP proof JWT using an EC private key (ES256, P-256). */
  private def ecProof(
    kp:    java.security.KeyPair,
    htm:   String,
    htu:   String,
    jti:   String = OAuth.randomOpaqueToken(16),
    iatOffset: Long = 0L,
    nonce: Option[String] = None,
    ath:   Option[String] = None,
    crv:   String = "P-256"
  ): String =
    val pub     = kp.getPublic.asInstanceOf[ECPublicKey]
    val priv    = kp.getPrivate
    val jwk     = ecJwk(pub, crv)
    val header  = ujson.Obj("typ" -> "dpop+jwt", "alg" -> "ES256", "jwk" -> jwk)
    val now     = java.time.Instant.now.getEpochSecond + iatOffset
    val payload = ujson.Obj("jti" -> jti, "htm" -> htm, "htu" -> htu,
                            "iat" -> ujson.Num(now.toDouble))
    nonce.foreach(n => payload("nonce") = n)
    ath.foreach(a => payload("ath") = a)
    val si      = b64u(header.render()) + "." + b64u(payload.render())
    val sig     = Signature.getInstance("SHA256withECDSA")
    sig.initSign(priv)
    sig.update(si.getBytes(StandardCharsets.UTF_8))
    val der     = sig.sign()
    si + "." + b64u(ecDerToJwt(der, 32))

  // ─── §1 DPoP.verifyProof ────────────────────────────────────────────────

  test("verifyProof — valid RS256 proof passes"):
    val kp    = genRsaKeyPair()
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token")
    DPoP.verifyProof(proof, "POST", "https://auth.example.com/token") match
      case DPoP.ProofResult.Valid(jkt) => jkt should not be empty
      case DPoP.ProofResult.Invalid(r) => fail(s"expected Valid, got: $r")

  test("verifyProof — valid ES256 proof passes"):
    val kp    = genEcKeyPair()
    val proof = ecProof(kp, "GET", "https://rs.example.com/data")
    DPoP.verifyProof(proof, "GET", "https://rs.example.com/data") match
      case DPoP.ProofResult.Valid(jkt) => jkt should not be empty
      case DPoP.ProofResult.Invalid(r) => fail(s"expected Valid, got: $r")

  test("verifyProof — htm mismatch → Invalid"):
    val kp    = genRsaKeyPair()
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token")
    DPoP.verifyProof(proof, "GET", "https://auth.example.com/token") match
      case DPoP.ProofResult.Invalid(r) => r should include("htm")
      case DPoP.ProofResult.Valid(_)   => fail("expected Invalid")

  test("verifyProof — htm match is case-insensitive"):
    val kp    = genRsaKeyPair()
    val proof = rsaProof(kp, "post", "https://auth.example.com/token")
    DPoP.verifyProof(proof, "POST", "https://auth.example.com/token") match
      case DPoP.ProofResult.Valid(_)   => succeed
      case DPoP.ProofResult.Invalid(r) => fail(s"expected Valid, got: $r")

  test("verifyProof — htu mismatch → Invalid"):
    val kp    = genRsaKeyPair()
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token")
    DPoP.verifyProof(proof, "POST", "https://other.example.com/token") match
      case DPoP.ProofResult.Invalid(r) => r should include("htu")
      case DPoP.ProofResult.Valid(_)   => fail("expected Invalid")

  test("verifyProof — htu query-string is stripped before compare"):
    val kp    = genRsaKeyPair()
    // Client posts to the URL with a query string — proof contains it stripped
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token")
    DPoP.verifyProof(proof, "POST", "https://auth.example.com/token?foo=bar") match
      case DPoP.ProofResult.Valid(_)   => succeed
      case DPoP.ProofResult.Invalid(r) => fail(s"expected Valid after strip, got: $r")

  test("verifyProof — expired iat → Invalid"):
    val kp    = genRsaKeyPair()
    // iat 10 minutes ago, max age 5 min
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token", iatOffset = -600L)
    DPoP.verifyProof(proof, "POST", "https://auth.example.com/token",
      clockSkewSeconds = 0L, maxAgeSeconds = 300L) match
      case DPoP.ProofResult.Invalid(r) => r should include("expired")
      case DPoP.ProofResult.Valid(_)   => fail("expected Invalid")

  test("verifyProof — future iat → Invalid"):
    val kp    = genRsaKeyPair()
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token", iatOffset = 3600L)
    DPoP.verifyProof(proof, "POST", "https://auth.example.com/token",
      clockSkewSeconds = 0L) match
      case DPoP.ProofResult.Invalid(r) => r should include("future")
      case DPoP.ProofResult.Valid(_)   => fail("expected Invalid")

  test("verifyProof — missing jti → Invalid"):
    // Craft a proof JWT without jti by manually building header+payload
    val kp   = genRsaKeyPair()
    val priv = kp.getPrivate
    val pub  = kp.getPublic.asInstanceOf[RSAPublicKey]
    val jwk  = rsaJwk(pub)
    val header  = ujson.Obj("typ" -> "dpop+jwt", "alg" -> "RS256", "jwk" -> jwk)
    val now     = java.time.Instant.now.getEpochSecond
    val payload = ujson.Obj("htm" -> "POST", "htu" -> "https://a.example/token",
                            "iat" -> ujson.Num(now.toDouble))  // no jti
    val si   = b64u(header.render()) + "." + b64u(payload.render())
    val sig  = Signature.getInstance("SHA256withRSA")
    sig.initSign(priv)
    sig.update(si.getBytes(StandardCharsets.UTF_8))
    val proof = si + "." + b64u(sig.sign())
    DPoP.verifyProof(proof, "POST", "https://a.example/token") match
      case DPoP.ProofResult.Invalid(r) => r should include("jti")
      case DPoP.ProofResult.Valid(_)   => fail("expected Invalid")

  test("verifyProof — wrong typ → Invalid"):
    val kp   = genRsaKeyPair()
    val priv = kp.getPrivate
    val pub  = kp.getPublic.asInstanceOf[RSAPublicKey]
    val jwk  = rsaJwk(pub)
    val header  = ujson.Obj("typ" -> "JWT", "alg" -> "RS256", "jwk" -> jwk)
    val now     = java.time.Instant.now.getEpochSecond
    val payload = ujson.Obj("jti" -> "x", "htm" -> "POST", "htu" -> "https://a.example/token",
                            "iat" -> ujson.Num(now.toDouble))
    val si   = b64u(header.render()) + "." + b64u(payload.render())
    val sig  = Signature.getInstance("SHA256withRSA")
    sig.initSign(priv)
    sig.update(si.getBytes(StandardCharsets.UTF_8))
    val proof = si + "." + b64u(sig.sign())
    DPoP.verifyProof(proof, "POST", "https://a.example/token") match
      case DPoP.ProofResult.Invalid(r) => r should include("typ")
      case DPoP.ProofResult.Valid(_)   => fail("expected Invalid")

  test("verifyProof — tampered payload → Invalid"):
    val kp    = genRsaKeyPair()
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token")
    val parts = proof.split('.')
    // Replace payload with different content (different htm)
    val fakePayload = b64u(ujson.Obj(
      "jti" -> "x", "htm" -> "DELETE", "htu" -> "https://auth.example.com/token",
      "iat" -> ujson.Num(java.time.Instant.now.getEpochSecond.toDouble)
    ).render())
    val tampered = parts(0) + "." + fakePayload + "." + parts(2)
    DPoP.verifyProof(tampered, "DELETE", "https://auth.example.com/token") match
      case DPoP.ProofResult.Invalid(r) => r should include("signature")
      case DPoP.ProofResult.Valid(_)   => fail("expected Invalid — signature should not verify")

  test("verifyProof — nonce required but missing → Invalid"):
    val kp    = genRsaKeyPair()
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token")
    DPoP.verifyProof(proof, "POST", "https://auth.example.com/token",
      expectedNonce = Some("server-nonce-xyz")) match
      case DPoP.ProofResult.Invalid(r) => r should include("nonce")
      case DPoP.ProofResult.Valid(_)   => fail("expected Invalid")

  test("verifyProof — nonce present and correct → Valid"):
    val kp    = genRsaKeyPair()
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token",
      nonce = Some("server-nonce-xyz"))
    DPoP.verifyProof(proof, "POST", "https://auth.example.com/token",
      expectedNonce = Some("server-nonce-xyz")) match
      case DPoP.ProofResult.Valid(_)   => succeed
      case DPoP.ProofResult.Invalid(r) => fail(s"expected Valid, got: $r")

  test("verifyProof — ath mismatch → Invalid"):
    val kp    = genRsaKeyPair()
    val proof = rsaProof(kp, "GET", "https://rs.example.com/data",
      ath = Some(DPoP.accessTokenHash("token-abc")))
    DPoP.verifyProof(proof, "GET", "https://rs.example.com/data",
      expectedAth = Some(DPoP.accessTokenHash("token-xyz"))) match
      case DPoP.ProofResult.Invalid(r) => r should include("ath")
      case DPoP.ProofResult.Valid(_)   => fail("expected Invalid")

  test("verifyProof — ath correct → Valid"):
    val kp    = genRsaKeyPair()
    val tok   = "my-access-token-string"
    val proof = rsaProof(kp, "GET", "https://rs.example.com/data",
      ath = Some(DPoP.accessTokenHash(tok)))
    DPoP.verifyProof(proof, "GET", "https://rs.example.com/data",
      expectedAth = Some(DPoP.accessTokenHash(tok))) match
      case DPoP.ProofResult.Valid(_)   => succeed
      case DPoP.ProofResult.Invalid(r) => fail(s"expected Valid, got: $r")

  // ─── §2 JWK thumbprint + access-token hash ─────────────────────────────

  test("jwkThumbprint — RSA key produces non-empty, stable thumbprint"):
    val kp   = genRsaKeyPair()
    val jwk  = rsaJwk(kp.getPublic.asInstanceOf[RSAPublicKey])
    val jkt1 = DPoP.jwkThumbprint(jwk)
    val jkt2 = DPoP.jwkThumbprint(jwk)
    jkt1 should not be empty
    jkt1 shouldBe jkt2

  test("jwkThumbprint — EC key produces non-empty thumbprint"):
    val kp  = genEcKeyPair()
    val jwk = ecJwk(kp.getPublic.asInstanceOf[ECPublicKey])
    DPoP.jwkThumbprint(jwk) should not be empty

  test("jwkThumbprint — different RSA keys produce different thumbprints"):
    val kp1  = genRsaKeyPair()
    val kp2  = genRsaKeyPair()
    val jkt1 = DPoP.jwkThumbprint(rsaJwk(kp1.getPublic.asInstanceOf[RSAPublicKey]))
    val jkt2 = DPoP.jwkThumbprint(rsaJwk(kp2.getPublic.asInstanceOf[RSAPublicKey]))
    jkt1 should not equal jkt2

  test("accessTokenHash — deterministic for same token"):
    val h1 = DPoP.accessTokenHash("my-token")
    val h2 = DPoP.accessTokenHash("my-token")
    h1 shouldBe h2

  test("accessTokenHash — different tokens produce different hashes"):
    DPoP.accessTokenHash("abc") should not equal DPoP.accessTokenHash("xyz")

  // ─── §3 AuthServer.issueToken — cnf.jkt injection ───────────────────────

  private def testAs() =
    val as = new AuthServer(AuthServerConfig(
      issuer        = "https://auth.example.com",
      signingSecret = "test-secret-for-dpop-tests",
      supportedScopes = Set("read", "write")
    ))
    as.clients.register(Client(
      id = "client1", secret = Some(OAuth.hashSecret("secret1")),
      redirectUris = Set("https://app.example.com/cb"),
      scopes = Set("read", "write"),
      grantTypes = Set("authorization_code", "client_credentials", "refresh_token"),
      clientType = ClientType.Confidential
    ))
    as

  test("issueToken — DPoP thumbprint injects cnf.jkt into access token"):
    val as  = testAs()
    val kp  = genRsaKeyPair()
    val jkt = DPoP.jwkThumbprint(rsaJwk(kp.getPublic.asInstanceOf[RSAPublicKey]))
    val outcome = as.issueToken(
      TokenRequest.ClientCredentialsGrant("client1", "secret1", Set("read")),
      dpopJwkThumbprint = Some(jkt)
    )
    outcome match
      case TokenOutcome.Issued(resp) =>
        resp.tokenType shouldBe "DPoP"
        val payload = OAuth.decodeHmacToken("test-secret-for-dpop-tests", resp.accessToken)
        payload shouldBe a[Right[?, ?]]
        val claims = payload.toOption.get
        claims("cnf")("jkt").str shouldBe jkt
      case TokenOutcome.Error(c, d) => fail(s"issueToken failed: $c $d")

  test("issueToken — without DPoP thumbprint token_type is Bearer"):
    val as = testAs()
    val outcome = as.issueToken(
      TokenRequest.ClientCredentialsGrant("client1", "secret1", Set("read"))
    )
    outcome match
      case TokenOutcome.Issued(resp) =>
        resp.tokenType shouldBe "Bearer"
        val payload = OAuth.decodeHmacToken("test-secret-for-dpop-tests", resp.accessToken)
        payload.toOption.get.obj.get("cnf") shouldBe None
      case TokenOutcome.Error(c, d) => fail(s"issueToken failed: $c $d")

  // ─── §4 OAuthRoutes.handleToken — DPoP header ───────────────────────────

  private def clientCredForm(id: String, secret: String, scope: String = "read"): String =
    s"grant_type=client_credentials&client_id=$id&client_secret=$secret&scope=$scope"

  test("handleToken — DPoP header triggers cnf.jkt in issued token"):
    val as   = testAs()
    val kp   = genRsaKeyPair()
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token")
    val res  = OAuthRoutes.handleToken(as, clientCredForm("client1", "secret1"),
      Map("DPoP" -> proof),
      tokenEndpointUrl = Some("https://auth.example.com/token"))
    res match
      case OAuthRoutes.RouteOutcome.Json(200, body, _) =>
        body("token_type").str shouldBe "DPoP"
        // Validate cnf.jkt in the token
        val token   = body("access_token").str
        val payload = OAuth.decodeHmacToken("test-secret-for-dpop-tests", token)
        payload.isRight shouldBe true
        payload.toOption.get("cnf")("jkt").str should not be empty
      case other => fail(s"expected 200, got $other")

  test("handleToken — invalid DPoP proof → 400 invalid_dpop_proof"):
    val as   = testAs()
    val res  = OAuthRoutes.handleToken(as, clientCredForm("client1", "secret1"),
      Map("DPoP" -> "not.a.proof"),
      tokenEndpointUrl = Some("https://auth.example.com/token"))
    res match
      case OAuthRoutes.RouteOutcome.Json(400, body, _) =>
        body("error").str shouldBe "invalid_dpop_proof"
      case other => fail(s"expected 400, got $other")

  test("handleToken — DPoP proof with wrong htu → 400"):
    val as   = testAs()
    val kp   = genRsaKeyPair()
    val proof = rsaProof(kp, "POST", "https://other.example.com/token")
    val res  = OAuthRoutes.handleToken(as, clientCredForm("client1", "secret1"),
      Map("DPoP" -> proof),
      tokenEndpointUrl = Some("https://auth.example.com/token"))
    res match
      case OAuthRoutes.RouteOutcome.Json(400, body, _) =>
        body("error").str shouldBe "invalid_dpop_proof"
      case other => fail(s"expected 400, got $other")

  test("handleToken — no DPoP header → plain Bearer token"):
    val as  = testAs()
    val res = OAuthRoutes.handleToken(as, clientCredForm("client1", "secret1"), Map.empty)
    res match
      case OAuthRoutes.RouteOutcome.Json(200, body, _) =>
        body("token_type").str shouldBe "Bearer"
      case other => fail(s"expected 200, got $other")

  test("handleToken — htu derived from issuer when tokenEndpointUrl is None"):
    val as   = testAs()
    val kp   = genRsaKeyPair()
    // issuer is "https://auth.example.com", so derived htu = "https://auth.example.com/token"
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token")
    val res  = OAuthRoutes.handleToken(as, clientCredForm("client1", "secret1"),
      Map("DPoP" -> proof))
    res match
      case OAuthRoutes.RouteOutcome.Json(200, body, _) =>
        body("token_type").str shouldBe "DPoP"
      case other => fail(s"expected 200 DPoP, got $other")

  // ─── §5 OAuthGuard.check — resource-server DPoP binding ─────────────────

  private def dpopBoundToken(jkt: String, secret: String = "secret"): String =
    OAuth.issueHmacToken(secret, "alice", Set("read"), 3600L,
      extra = ujson.Obj("cnf" -> ujson.Obj("jkt" -> jkt)))

  test("guard check — DPoP-bound token without requestMethod/URL → Allow (backward-compat)"):
    val kp  = genRsaKeyPair()
    val jkt = DPoP.jwkThumbprint(rsaJwk(kp.getPublic.asInstanceOf[RSAPublicKey]))
    val tok = dpopBoundToken(jkt)
    val v   = OAuth.hmacValidator("secret")
    val kp2 = genRsaKeyPair()
    val proof = rsaProof(kp2, "GET", "https://rs.example.com/data",
      ath = Some(DPoP.accessTokenHash(tok)))
    // No requestMethod/URL → DPoP check skipped for backward compatibility
    OAuthGuard.check(
      headers = Map("Authorization" -> s"Bearer $tok", "DPoP" -> proof),
      validator = v
    ) match
      case OAuthGuard.GuardDecision.Allow(_) => succeed
      case other => fail(s"expected Allow, got $other")

  test("guard check — DPoP-bound token with valid proof → Allow"):
    val kp  = genRsaKeyPair()
    val jkt = DPoP.jwkThumbprint(rsaJwk(kp.getPublic.asInstanceOf[RSAPublicKey]))
    val tok = dpopBoundToken(jkt)
    val v   = OAuth.hmacValidator("secret")
    val proof = rsaProof(kp, "GET", "https://rs.example.com/data",
      ath = Some(DPoP.accessTokenHash(tok)))
    OAuthGuard.check(
      headers = Map("Authorization" -> s"Bearer $tok", "DPoP" -> proof),
      validator = v,
      requestMethod = Some("GET"),
      requestUrl    = Some("https://rs.example.com/data")
    ) match
      case OAuthGuard.GuardDecision.Allow(claims) =>
        claims.subject shouldBe "alice"
      case other => fail(s"expected Allow, got $other")

  test("guard check — DPoP-bound token without DPoP header → Deny"):
    val kp  = genRsaKeyPair()
    val jkt = DPoP.jwkThumbprint(rsaJwk(kp.getPublic.asInstanceOf[RSAPublicKey]))
    val tok = dpopBoundToken(jkt)
    val v   = OAuth.hmacValidator("secret")
    OAuthGuard.check(
      headers = Map("Authorization" -> s"Bearer $tok"),
      validator = v,
      requestMethod = Some("GET"),
      requestUrl    = Some("https://rs.example.com/data")
    ) match
      case OAuthGuard.GuardDecision.Deny(OAuthRoutes.RouteOutcome.Json(401, body, _)) =>
        body("error").str shouldBe "invalid_dpop_proof"
      case other => fail(s"expected Deny 401, got $other")

  test("guard check — DPoP-bound token with wrong key → Deny"):
    val kp1 = genRsaKeyPair()
    val kp2 = genRsaKeyPair()
    val jkt = DPoP.jwkThumbprint(rsaJwk(kp1.getPublic.asInstanceOf[RSAPublicKey]))
    val tok = dpopBoundToken(jkt)
    val v   = OAuth.hmacValidator("secret")
    // Proof signed with kp2 but token bound to kp1's thumbprint
    val proof = rsaProof(kp2, "GET", "https://rs.example.com/data",
      ath = Some(DPoP.accessTokenHash(tok)))
    OAuthGuard.check(
      headers = Map("Authorization" -> s"Bearer $tok", "DPoP" -> proof),
      validator = v,
      requestMethod = Some("GET"),
      requestUrl    = Some("https://rs.example.com/data")
    ) match
      case OAuthGuard.GuardDecision.Deny(OAuthRoutes.RouteOutcome.Json(401, body, _)) =>
        body("error").str shouldBe "invalid_dpop_proof"
      case other => fail(s"expected Deny 401, got $other")

  test("guard check — plain Bearer token (no cnf.jkt) passes without DPoP"):
    val tok = OAuth.issueHmacToken("s", "bob", Set("read"), 60L)
    val v   = OAuth.hmacValidator("s")
    OAuthGuard.check(
      headers = Map("Authorization" -> s"Bearer $tok"),
      validator = v,
      requestMethod = Some("GET"),
      requestUrl    = Some("https://rs.example.com/data")
    ) match
      case OAuthGuard.GuardDecision.Allow(claims) => claims.subject shouldBe "bob"
      case other => fail(s"expected Allow, got $other")

  // ─── §6 InMemoryJtiStore — replay prevention ────────────────────────────

  test("InMemoryJtiStore — first use returns true (fresh jti)"):
    val store = new DPoP.InMemoryJtiStore()
    store.checkAndRecord("jti-1", 300L) shouldBe true

  test("InMemoryJtiStore — second use of same jti returns false (replay)"):
    val store = new DPoP.InMemoryJtiStore()
    store.checkAndRecord("jti-2", 300L) shouldBe true
    store.checkAndRecord("jti-2", 300L) shouldBe false

  test("InMemoryJtiStore — different jtis each return true"):
    val store = new DPoP.InMemoryJtiStore()
    store.checkAndRecord("jti-a", 300L) shouldBe true
    store.checkAndRecord("jti-b", 300L) shouldBe true

  test("verifyProof — replay via JtiStore → Invalid"):
    val kp    = genRsaKeyPair()
    val store = new DPoP.InMemoryJtiStore()
    val jti   = "unique-jti-1234"
    val proof = rsaProof(kp, "POST", "https://auth.example.com/token", jti = jti)
    DPoP.verifyProof(proof, "POST", "https://auth.example.com/token",
      jtiStore = store) match
      case DPoP.ProofResult.Valid(_) => ()
      case DPoP.ProofResult.Invalid(r) => fail(s"first use should be Valid, got: $r")
    // Second use — replay
    DPoP.verifyProof(proof, "POST", "https://auth.example.com/token",
      jtiStore = store) match
      case DPoP.ProofResult.Invalid(r) => r should include("replayed")
      case DPoP.ProofResult.Valid(_)   => fail("replay should be Invalid")
