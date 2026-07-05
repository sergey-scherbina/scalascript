package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** ES256 (ECDSA P-256 + SHA-256) for JWS and COSE — pinned against the published RFC 7515 A.3 vector
 *  (verify a real JOSE ES256 signature) plus round-trips, on JVM and Scala.js. */
class JwsEs256Test extends AnyFunSuite with Matchers:

  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString
  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def ub(s: String): Array[Byte]    = Base64.getUrlDecoder.decode(s)

  test("RFC 7515 A.3 — the private key derives the published P-256 public key and its JWS verifies") {
    val x = ub("f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU")
    val y = ub("x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0")
    val d = ub("jpsQnnGQmL-YBIffH1136cspYG6-0iY7X1fCE9-E9LI")
    val pub = Array[Byte](0x04) ++ x ++ y
    P256Ecdsa.derivePublicUncompressed(d) shouldBe pub          // d → the RFC's (x, y)
    val token =
      "eyJhbGciOiJFUzI1NiJ9." +
      "eyJpc3MiOiJqb2UiLA0KICJleHAiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ." +
      "DtEhU3ljbEg8L38VWAfUAqOyKAM6-Xx-F4GawxaepmXFCgfTjDxw5djxLa8ISlSApmWQxfKTUJqPP3-Kg6NU1Q"
    Jws.verifyES256(token, pub).isDefined shouldBe true          // a real, published ES256 signature verifies
  }

  private val priv = unhex("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721")
  private val pub  = P256Ecdsa.derivePublicCompressed(priv)

  test("ES256 JWS / Jwt round-trip; wrong key and tamper rejected") {
    val claims = """{"iss":"me","exp":9999999999}"""
    val token  = Jwt.es256(priv, claims)
    Jwt.verifyES256(token, pub) shouldBe Some(claims)
    Jwt.verifyES256(token, P256Ecdsa.derivePublicCompressed(
      unhex("0000000000000000000000000000000000000000000000000000000000000002"))) shouldBe None
    Jws.verifyES256(token.updated(5, if token(5) == 'A' then 'B' else 'A'), pub) shouldBe None
  }

  test("COSE_Sign1 ES256 round-trips with protected {1:-7} and enforces the alg guard") {
    hex(CoseSign1.protectedHeaderES256) shouldBe "a10126"
    val payload = "webauthn assertion".getBytes(UTF_8)
    val msg     = CoseSign1.signES256(priv, payload)
    Cbor.decode(msg) match
      case Cbor.Tagged(18, Cbor.Arr(items)) =>
        (items(0): @unchecked) match { case Cbor.Bytes(b)   => hex(b) shouldBe "a10126" }
        (items(3): @unchecked) match { case Cbor.Bytes(sig) => sig.length shouldBe 64 }
      case other => fail(s"not a tagged COSE_Sign1: $other")
    CoseSign1.verifyES256(msg, pub).map(new String(_, UTF_8)) shouldBe Some("webauthn assertion")
    // an ES256 message must not verify under the other COSE algorithms (alg header is authenticated)
    CoseSign1.verifyES256K(msg, pub) shouldBe None
    CoseSign1.verifyEdDSA(msg, pub) shouldBe None
  }
