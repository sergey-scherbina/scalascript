package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TotpTest extends AnyFunSuite with Matchers:

  // RFC 6238 test vector: secret = 12345678901234567890 in ASCII, base32-encoded.
  // Counter step 0 → T=0 → code "755224" (HOTP reference, TOTP step=0).
  // We derive a base32 secret from known bytes and pin the counter explicitly.
  private val KnownSecretBytes = "12345678901234567890".getBytes("US-ASCII")
  private val KnownSecret: String =
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val sb = StringBuilder()
    var buf = 0L; var bits = 0
    for b <- KnownSecretBytes do
      buf = (buf << 8) | (b & 0xffL); bits += 8
      while bits >= 5 do { bits -= 5; sb.append(alphabet.charAt(((buf >> bits) & 0x1f).toInt)) }
    if bits > 0 then sb.append(alphabet.charAt(((buf << (5 - bits)) & 0x1f).toInt))
    sb.toString

  test("code — RFC 6238 step 0 (counter=0, T=0s)") {
    // At nowSeconds=0 → counter=0 → RFC 6238 Appendix B first vector "755224"
    Totp.code(KnownSecret, nowSeconds = 0L) shouldBe "755224"
  }

  test("code — RFC 6238 step 1 (counter=1, T=30s)") {
    // At nowSeconds=30 → counter=1 → RFC 6238 second vector "287082"
    Totp.code(KnownSecret, nowSeconds = 30L) shouldBe "287082"
  }

  test("code — result is always 6 digits") {
    val s = Totp.secret()
    Totp.code(s).length shouldBe 6
    Totp.code(s).forall(_.isDigit) shouldBe true
  }

  test("valid — code matches at same step") {
    val s   = Totp.secret()
    val now = java.lang.System.currentTimeMillis() / 1000L
    val c   = Totp.code(s, now)
    Totp.valid(s, c) shouldBe true
  }

  test("valid — wrong code rejected") {
    val s = Totp.secret()
    Totp.valid(s, "000000") shouldBe false
  }

  test("valid — null / wrong-length code rejected without exception") {
    val s = Totp.secret()
    Totp.valid(s, null)    shouldBe false
    Totp.valid(s, "12345") shouldBe false
    Totp.valid(s, "1234567") shouldBe false
    Totp.valid(s, "abc123") shouldBe false
  }

  test("valid — skew=0 accepts only current step") {
    val s     = Totp.secret()
    val nowMs = java.lang.System.currentTimeMillis()
    val step  = nowMs / 1000L / 30L
    // with a freshly generated secret the previous step's code won't match current step
    Totp.valid(s, Totp.code(s, (step - 1) * 30L), skew = 0) shouldBe false
  }

  test("secret — generates base32 string, 32 chars for 20 bytes") {
    val s = Totp.secret()
    s.length shouldBe 32
    s.forall(c => "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".contains(c)) shouldBe true
  }

  test("uri — contains required otpauth fields") {
    val s   = Totp.secret()
    val uri = Totp.uri(s, "alice@example.com", "MyApp")
    uri should startWith("otpauth://totp/")
    uri should include("secret=" + s)
    uri should include("digits=6")
    uri should include("period=30")
    uri should include("issuer=MyApp")
  }

  test("uri — no issuer omits issuer param") {
    val s   = Totp.secret()
    val uri = Totp.uri(s, "alice@example.com")
    uri should not include "issuer="
  }
