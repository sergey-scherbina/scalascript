package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8

/** ES256K (ECDSA secp256k1 + SHA-256, RFC 8812) for JWS — the DER↔raw(R‖S) conversion and the
 *  JWS sign/verify, on JVM and Scala.js. The underlying secp256k1 ECDSA is already pinned
 *  byte-for-byte to BouncyCastle by the portable-stack tests; here we pin the fixed-length encoding
 *  and JWS framing (interop with BouncyCastle is asserted in the bouncycastle module). */
class Es256kTest extends AnyFunSuite with Matchers:

  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def utf8(s: String): Array[Byte]  = s.getBytes(UTF_8)

  private val priv = unhex("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721")
  private val pub  = Secp256k1Ecdsa.derivePublicCompressed(priv)

  test("derToRaw / rawToDer preserve the signature (64-byte R‖S ↔ DER)") {
    val hash = Sha256.digest(utf8("hello ES256K"))
    val der  = Secp256k1Ecdsa.sign(priv, hash)
    val raw  = Secp256k1Ecdsa.derToRaw(der)
    raw.length shouldBe 64
    Secp256k1Ecdsa.rawToDer(raw) shouldBe der                                   // back to the same minimal low-S DER
    Secp256k1Ecdsa.verify(pub, hash, Secp256k1Ecdsa.rawToDer(raw)) shouldBe true
  }

  test("JWS ES256K signs + verifies and rejects wrong key / tamper") {
    val header  = utf8("""{"alg":"ES256K"}""")
    val payload = utf8("""{"iss":"me","sub":"42"}""")
    val token   = Jws.signES256K(priv, header, payload)
    token.count(_ == '.') shouldBe 2
    Jws.verifyES256K(token, pub).map(new String(_, UTF_8)) shouldBe Some("""{"iss":"me","sub":"42"}""")
    // a different key must not verify
    val otherPub = Secp256k1Ecdsa.derivePublicCompressed(
      unhex("0000000000000000000000000000000000000000000000000000000000000002"))
    Jws.verifyES256K(token, otherPub) shouldBe None
    // flip a char inside the body → invalid
    Jws.verifyES256K(token.updated(5, if token(5) == 'A' then 'B' else 'A'), pub) shouldBe None
    Jws.verifyES256K("not.a.jwt", pub) shouldBe None
  }

  test("Jwt ES256K round-trips") {
    val claims = """{"iss":"issuer","aud":"you"}"""
    Jwt.verifyES256K(Jwt.es256k(priv, claims), pub) shouldBe Some(claims)
  }
