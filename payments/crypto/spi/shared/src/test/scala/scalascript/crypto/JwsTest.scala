package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** Portable JWS/JWT vectors — the published RFC test vectors, asserted on both JVM and Scala.js so
 *  the suite pins RFC conformance and cross-platform byte-identity of token generation at once. */
class JwsTest extends AnyFunSuite with Matchers:

  private def ub(s: String): Array[Byte] = Base64.getUrlDecoder.decode(s)

  // ── RFC 7515 Appendix A.1 — JWS using HMAC SHA-256 ─────────────────────────────
  test("JWS HS256 reproduces RFC 7515 A.1 byte-exact") {
    val key = ub("AyM1SysPpbyDfgZld3umj1qzKObwVMkoqQ-EstJQLr_T-1qS0gZH75aKtMN3Yj0iPS4hcgUuTwjAzZr1Z9CAow")
    // The A.1 protected header / payload octets, via their published base64url forms.
    val header  = ub("eyJ0eXAiOiJKV1QiLA0KICJhbGciOiJIUzI1NiJ9")
    val payload = ub("eyJpc3MiOiJqb2UiLA0KICJleHAiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ")

    val token = Jws.signHs256(key, header, payload)
    token shouldBe
      "eyJ0eXAiOiJKV1QiLA0KICJhbGciOiJIUzI1NiJ9." +
      "eyJpc3MiOiJqb2UiLA0KICJleHAiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ." +
      "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

    Jws.verifyHs256(token, key).map(new String(_, UTF_8)) shouldBe Some(new String(payload, UTF_8))
    Jws.verifyHs256(token, "wrong-key".getBytes(UTF_8)) shouldBe None
  }

  // ── RFC 8037 Appendix A.4 — Ed25519 signing ────────────────────────────────────
  test("JWS EdDSA reproduces RFC 8037 A.4 byte-exact") {
    val seed = ub("nWGxne_9WmC6hEr0kuwsxERJxWl7MmkZcDusAxyuf2A")  // OKP "d"
    val pub  = ub("11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo")  // OKP "x"
    val header  = ub("eyJhbGciOiJFZERTQSJ9")                      // {"alg":"EdDSA"}
    val payload = "Example of Ed25519 signing".getBytes(UTF_8)

    val token = Jws.signEdDSA(seed, header, payload)
    token shouldBe
      "eyJhbGciOiJFZERTQSJ9.RXhhbXBsZSBvZiBFZDI1NTE5IHNpZ25pbmc." +
      "hgyY0il_MGCjP0JzlnLWG1PPOt7-09PGcvMg3AIbQR6dWbhijcNR4ki4iylGjg5BhVsPt9g7sVvpAr_MuM0KAg"

    Jws.verifyEdDSA(token, pub).map(new String(_, UTF_8)) shouldBe Some("Example of Ed25519 signing")
  }

  // ── Jwt convenience: round-trip + tamper rejection ─────────────────────────────
  test("Jwt HS256 round-trips and rejects a corrupted signature / wrong key") {
    val claims = """{"iss":"me","sub":"42","exp":9999999999}"""
    val secret = "top-secret-hmac-key-0123456789ab".getBytes(UTF_8)
    val jwt    = Jwt.hs256(secret, claims)
    Jwt.verifyHs256(jwt, secret) shouldBe Some(claims)
    Jwt.verifyHs256(jwt, "another-secret".getBytes(UTF_8)) shouldBe None
    // Flip the last signature char (stays valid base64url, wrong MAC).
    val flipped = jwt.dropRight(1) + (if jwt.last == 'A' then "B" else "A")
    Jwt.verifyHs256(flipped, secret) shouldBe None
    Jwt.verifyHs256("not.a.jwt.at.all", secret) shouldBe None
  }

  test("Jwt EdDSA round-trips and rejects the wrong public key") {
    val claims = """{"iss":"issuer","aud":"you"}"""
    val seed   = ub("nWGxne_9WmC6hEr0kuwsxERJxWl7MmkZcDusAxyuf2A")
    val pub    = ub("11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo")
    val jwt    = Jwt.eddsa(seed, claims)
    Jwt.verifyEdDSA(jwt, pub) shouldBe Some(claims)
    // A different valid Ed25519 public key must not verify this token.
    val otherPub = Ed25519.derivePublicKey(Array.fill[Byte](32)(7.toByte))
    Jwt.verifyEdDSA(jwt, otherPub) shouldBe None
  }
