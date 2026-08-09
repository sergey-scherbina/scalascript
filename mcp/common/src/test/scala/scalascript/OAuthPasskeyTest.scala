package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*
import java.security.{KeyPairGenerator, Signature}
import java.security.interfaces.RSAPublicKey

/** v1.17.x — passkey/WebAuthn assertion grant for the OAuth AS.
 *  Minimal flow: AS stores credentialId → (subject, publicKey); user
 *  fetches a challenge; browser signs it; AS verifies + mints tokens. */
class OAuthPasskeyTest extends AnyFunSuite with Matchers:

  /** Build a real RSA key pair and sign some bytes — simulates what a
   *  browser would do during a WebAuthn assertion ceremony. */
  private def freshRsaPair: (java.security.KeyPair, RSAPublicKey) =
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair()
    (kp, kp.getPublic.asInstanceOf[RSAPublicKey])

  private def signRs256(priv: java.security.PrivateKey, data: Array[Byte]): Array[Byte] =
    val s = Signature.getInstance("SHA256withRSA")
    s.initSign(priv)
    s.update(data)
    s.sign()

  private def newAs: AuthServer =
    val as = new AuthServer(AuthServerConfig(
      issuer        = "https://auth.local",
      signingSecret = "k",
      supportedScopes = Set("read")))
    as.clients.register(Client(
      id = "webapp", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("read"),
      grantTypes = Set("authorization_code",
                       TokenRequest.PasskeyGrantType),
      clientType = ClientType.Public))
    as

  // ─── PasskeyStore + ChallengeStore ───────────────────────────────

  test("InMemoryPasskeyStore: register / find / byUser / delete"):
    val store = new Passkey.InMemoryPasskeyStore
    val (_, pub) = freshRsaPair
    val cred = Passkey.PasskeyCredential("cred-1", "alice", pub, "RS256")
    store.register(cred)
    store.find("cred-1").map(_.subject) shouldBe Some("alice")
    store.byUser("alice").length        shouldBe 1
    store.byUser("ghost").isEmpty       shouldBe true
    store.delete("cred-1")
    store.find("cred-1") shouldBe None

  test("InMemoryChallengeStore: issue + consume once + replay refused"):
    val s = new Passkey.InMemoryChallengeStore(ttlSeconds = 60L)
    val c = s.issue()
    s.consume(c) shouldBe true   // first consumption OK
    s.consume(c) shouldBe false  // replay → false
    s.consume("never-issued") shouldBe false

  // ─── Signature verification ──────────────────────────────────────

  test("verifySignature: well-formed RS256 assertion passes"):
    val (kp, pub) = freshRsaPair
    val cred = Passkey.PasskeyCredential("c1", "alice", pub, "RS256")
    val data = "challenge:abc123".getBytes
    val sig  = signRs256(kp.getPrivate, data)
    Passkey.verifySignature(cred, data, sig) shouldBe true

  test("verifySignature: tampered data fails"):
    val (kp, pub) = freshRsaPair
    val cred = Passkey.PasskeyCredential("c1", "alice", pub, "RS256")
    val data = "challenge:abc123".getBytes
    val sig  = signRs256(kp.getPrivate, data)
    Passkey.verifySignature(cred, "different".getBytes, sig) shouldBe false

  test("verifySignature: tampered signature fails"):
    val (_, pub) = freshRsaPair
    val cred = Passkey.PasskeyCredential("c1", "alice", pub, "RS256")
    val data = "x".getBytes
    val bad  = new Array[Byte](256)  // random zeros
    Passkey.verifySignature(cred, data, bad) shouldBe false

  test("verifySignature: unknown algorithm returns false"):
    val (_, pub) = freshRsaPair
    val cred = Passkey.PasskeyCredential("c1", "alice", pub, "unknown-alg")
    Passkey.verifySignature(cred, "x".getBytes, "x".getBytes) shouldBe false

  // ─── decodeRsaJwk round trip ─────────────────────────────────────

  test("decodeRsaJwk: round-trip with OAuth.rsaPublicJwk shape"):
    val signer = OAuth.RsaTokenSigner.generate("k1")
    val jwk    = signer.publicJwk.get
    val n      = jwk("n").str
    val e      = jwk("e").str
    val decoded = Passkey.decodeRsaJwk(n, e).asInstanceOf[RSAPublicKey]
    decoded.getModulus        shouldBe signer.publicKey.getModulus
    decoded.getPublicExponent shouldBe signer.publicKey.getPublicExponent

  // ─── AS.exchangePasskey end-to-end ───────────────────────────────

  test("Passkey grant: full happy path issues an access token"):
    val as  = newAs
    val (kp, pub) = freshRsaPair
    as.passkeys.register(Passkey.PasskeyCredential("cred-alice", "alice", pub, "RS256"))
    val challenge = as.passkeyChallenge()
    val data      = challenge.getBytes
    val sig       = signRs256(kp.getPrivate, data)
    as.issueToken(TokenRequest.PasskeyAssertionGrant(
      credentialId = "cred-alice",
      challenge    = challenge,
      signedData   = data,
      signature    = sig,
      scope        = Set("read"),
      clientId     = "webapp"
    )) match
      case TokenOutcome.Issued(resp) =>
        as.tokenValidator(resp.accessToken) match
          case OAuth.AuthResult.Valid(claims) => claims.subject shouldBe "alice"
          case other => fail(s"validator: $other")
      case other => fail(s"expected Issued, got $other")

  test("Passkey grant: unknown credential → invalid_grant"):
    val as = newAs
    val (kp, _) = freshRsaPair
    val challenge = as.passkeyChallenge()
    val data = challenge.getBytes
    val sig  = signRs256(kp.getPrivate, data)
    as.issueToken(TokenRequest.PasskeyAssertionGrant(
      credentialId = "ghost",
      challenge    = challenge,
      signedData   = data,
      signature    = sig,
      clientId     = "webapp"
    )) match
      case TokenOutcome.Error(code, descr) =>
        code shouldBe "invalid_grant"
        descr should include ("unknown credential")
      case other => fail(s"got $other")

  test("Passkey grant: replayed challenge → invalid_grant"):
    val as = newAs
    val (kp, pub): (java.security.KeyPair, RSAPublicKey) = freshRsaPair
    as.passkeys.register(Passkey.PasskeyCredential("c", "alice", pub, "RS256"))
    val challenge = as.passkeyChallenge()
    val data = challenge.getBytes
    val sig  = signRs256(kp.getPrivate, data)
    val req  = TokenRequest.PasskeyAssertionGrant(
      "c", challenge, data, sig, Set.empty, "webapp")
    as.issueToken(req) shouldBe a[TokenOutcome.Issued]
    // Same challenge again — must fail
    as.issueToken(req) match
      case TokenOutcome.Error(code, descr) =>
        code shouldBe "invalid_grant"
        descr should (include ("challenge") and include ("replay") or include ("expired") or include ("unknown"))
      case other => fail(s"got $other")

  test("Passkey grant: bad signature → invalid_grant"):
    val as = newAs
    val (_, pub) = freshRsaPair
    as.passkeys.register(Passkey.PasskeyCredential("c", "alice", pub, "RS256"))
    val challenge = as.passkeyChallenge()
    as.issueToken(TokenRequest.PasskeyAssertionGrant(
      "c", challenge, "data".getBytes, new Array[Byte](256), Set.empty, "webapp"
    )) match
      case TokenOutcome.Error(code, descr) =>
        code shouldBe "invalid_grant"
        descr should include ("signature")
      case other => fail(s"got $other")

  test("Passkey grant: scope must be a subset of the client's scopes"):
    val as = newAs
    val (kp, pub) = freshRsaPair
    as.passkeys.register(Passkey.PasskeyCredential("c", "alice", pub, "RS256"))
    val challenge = as.passkeyChallenge()
    val data = challenge.getBytes
    val sig  = signRs256(kp.getPrivate, data)
    as.issueToken(TokenRequest.PasskeyAssertionGrant(
      "c", challenge, data, sig, Set("write"), "webapp"  // client only has read
    )) match
      case TokenOutcome.Error(code, _) => code shouldBe "invalid_scope"
      case other => fail(s"got $other")

  // ─── /passkey/challenge wire shape ───────────────────────────────

  test("handlePasskeyChallenge: returns 200 + JSON challenge"):
    val as = newAs
    OAuthRoutes.handlePasskeyChallenge(as) match
      case OAuthRoutes.RouteOutcome.Json(200, js, hdrs) =>
        js("challenge").str.length should be > 20
        hdrs("Cache-Control")      shouldBe "no-store"
      case other => fail(s"got $other")

  // ─── /token wire path for the passkey grant ──────────────────────

  test("/token: passkey assertion wired through handleToken"):
    val as = newAs
    val (kp, pub) = freshRsaPair
    as.passkeys.register(Passkey.PasskeyCredential("c", "alice", pub, "RS256"))
    val challenge = as.passkeyChallenge()
    val data      = challenge.getBytes
    val sig       = signRs256(kp.getPrivate, data)
    val b64u = (bs: Array[Byte]) =>
      java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bs)
    val body =
      s"grant_type=${java.net.URLEncoder.encode(TokenRequest.PasskeyGrantType, "UTF-8")}" +
      s"&client_id=webapp&credential_id=c" +
      s"&challenge=${java.net.URLEncoder.encode(challenge, "UTF-8")}" +
      s"&signed_data=${b64u(data)}&signature=${b64u(sig)}"
    OAuthRoutes.handleToken(as, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(200, js, _) =>
        js("access_token").str should not be empty
      case other => fail(s"got $other")

  // ─── Metadata advertises the new grant ──────────────────────────

  test("AS metadata advertises the passkey grant in grant_types_supported"):
    val md = newAs.metadataJson()
    md("grant_types_supported").arr.map(_.str).toSet should contain (
      "urn:ietf:params:oauth:grant-type:passkey")
