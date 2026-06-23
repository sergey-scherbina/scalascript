package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Cross-platform (JVM + Scala.js) RFC vectors for the portable [[Sha1]] / [[Hmac]] and HOTP/TOTP ([[Totp]]):
 *  SHA-1 (FIPS 180), HMAC-SHA1 (RFC 2202), HOTP (RFC 4226 App. D), TOTP (RFC 6238 App. B). */
class OtpTest extends AnyFunSuite with Matchers:

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  private def utf8(s: String): Array[Byte] = s.getBytes("UTF-8")

  // ── SHA-1 (FIPS 180) ──────────────────────────────────────────────────────────

  test("SHA-1 vectors") {
    hex(Sha1.digest(utf8("abc")))    shouldBe "a9993e364706816aba3e25717850c26c9cd0d89d"
    hex(Sha1.digest(Array.empty))    shouldBe "da39a3ee5e6b4b0d3255bfef95601890afd80709"
    hex(Sha1.digest(utf8("The quick brown fox jumps over the lazy dog"))) shouldBe
      "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12"
    hex(Sha1.digest(utf8("a" * 1000000))) shouldBe "34aa973cd4c4daa4f61eeb2bdbad27316534016f"
  }

  // ── HMAC-SHA1 (RFC 2202) ──────────────────────────────────────────────────────

  test("HMAC-SHA1 RFC 2202 vectors") {
    hex(Hmac.sha1(Array.fill[Byte](20)(0x0b.toByte), utf8("Hi There"))) shouldBe
      "b617318655057264e28bc0b6fb378c8ef146be00"
    hex(Hmac.sha1(utf8("Jefe"), utf8("what do ya want for nothing?"))) shouldBe
      "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79"
  }

  // ── HOTP (RFC 4226 Appendix D) ────────────────────────────────────────────────

  test("HOTP RFC 4226 vectors (6 digits, SHA-1)") {
    val key = utf8("12345678901234567890")
    val expected = Seq("755224", "287082", "359152", "969429", "338314",
                       "254676", "287922", "162583", "399871", "520489")
    for (code, counter) <- expected.zipWithIndex do
      Totp.hotp(key, counter.toLong) shouldBe code
  }

  // ── TOTP (RFC 6238 Appendix B) ────────────────────────────────────────────────

  private val seed1 = utf8("12345678901234567890")                                                   // 20B
  private val seed256 = utf8("12345678901234567890123456789012")                                     // 32B
  private val seed512 = utf8("1234567890123456789012345678901234567890123456789012345678901234")     // 64B

  test("TOTP RFC 6238 vectors (8 digits) — SHA-1") {
    val cases = Seq(59L -> "94287082", 1111111109L -> "07081804", 1111111111L -> "14050471",
                    1234567890L -> "89005924", 2000000000L -> "69279037", 20000000000L -> "65353130")
    for (t, code) <- cases do Totp.totp(seed1, t, 30, 8, Totp.Algo.Sha1) shouldBe code
  }

  test("TOTP RFC 6238 vectors (8 digits) — SHA-256") {
    val cases = Seq(59L -> "46119246", 1111111109L -> "68084774", 2000000000L -> "90698825")
    for (t, code) <- cases do Totp.totp(seed256, t, 30, 8, Totp.Algo.Sha256) shouldBe code
  }

  test("TOTP RFC 6238 vectors (8 digits) — SHA-512") {
    val cases = Seq(59L -> "90693936", 1111111111L -> "99943326", 20000000000L -> "47863826")
    for (t, code) <- cases do Totp.totp(seed512, t, 30, 8, Totp.Algo.Sha512) shouldBe code
  }

  // ── validation window ─────────────────────────────────────────────────────────

  test("validate accepts the current code and ±1 step of skew, rejects others") {
    val key  = utf8("12345678901234567890")
    val tNow = 1111111111L
    val code = Totp.totp(key, tNow, 30, 8, Totp.Algo.Sha1)
    Totp.validate(key, code, tNow, 30, 8, Totp.Algo.Sha1)                shouldBe true
    Totp.validate(key, code, tNow + 30, 30, 8, Totp.Algo.Sha1)          shouldBe true   // 1 step late
    Totp.validate(key, code, tNow + 120, 30, 8, Totp.Algo.Sha1)         shouldBe false  // 4 steps off
    Totp.validate(key, "00000000", tNow, 30, 8, Totp.Algo.Sha1)         shouldBe false
  }

  test("hotp zero-pads short codes and rejects invalid digit counts") {
    Totp.hotp(utf8("key"), 0L).length shouldBe 6
    assertThrows[IllegalArgumentException](Totp.hotp(utf8("key"), 0L, 0))
    assertThrows[IllegalArgumentException](Totp.hotp(utf8("key"), 0L, 10))
  }
