package scalascript.crypto.frost

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** `t`-of-`n` Shamir backup of arbitrary byte secrets: split→recover round-trips across sizes/thresholds,
 *  subset-independence, and that fewer than `t` shares reveal nothing. Runs on JVM + Scala.js. */
class ShamirSecretSharingTest extends AnyFunSuite with Matchers:

  import ShamirSecretSharing.{split, recover}

  private def secretOf(len: Int): Array[Byte] = Array.tabulate[Byte](len)(i => (i * 31 + 7).toByte)

  test("round-trip across secret sizes and thresholds") {
    for
      len      <- Seq(0, 1, 16, 31, 32, 33, 64, 100, 256)
      (t, n)   <- Seq((1, 1), (2, 3), (3, 5), (5, 5))
    do
      val secret = secretOf(len)
      val shares = split(secret, t, n)
      shares.size shouldBe n
      recover(shares.take(t)).toSeq shouldBe secret.toSeq
  }

  test("any t-of-n subset recovers the same secret") {
    val secret = secretOf(48)
    val shares = split(secret, 3, 5)
    for subset <- shares.combinations(3) do
      recover(subset).toSeq shouldBe secret.toSeq
  }

  test("a 32-byte private key splits and recovers (2-of-3)") {
    val key    = Array.tabulate[Byte](32)(i => (0xA0 + i).toByte)
    val shares = split(key, 2, 3)
    recover(List(shares(0), shares(2))).toSeq shouldBe key.toSeq
  }

  test("fewer than t shares do not recover the secret") {
    val secret = secretOf(40)
    val shares = split(secret, 3, 5)
    // 2 < 3 shares: recovery either throws or yields a different value — never the secret.
    val recovered =
      try Some(recover(shares.take(2))) catch case _: Throwable => None
    recovered.map(_.toSeq) should not be Some(secret.toSeq)
  }

  test("a corrupted share yields a wrong secret, not the original") {
    // Raw Shamir has no integrity check, and recover truncates each chunk to its low 31 bytes — so a
    // single-byte flip in a share's high (or padding) region can be masked. A substantial corruption diffuses
    // through the Lagrange combination and reliably changes the recovered secret.
    val secret = secretOf(48)
    val shares = split(secret, 2, 3)
    val bad    = shares.head
    var i = 0
    while i < bad.data.length do { bad.data(i) = (bad.data(i) ^ 0xFF).toByte; i += 1 }
    recover(List(bad, shares(1))).toSeq should not be secret.toSeq
  }

  test("split rejects invalid thresholds") {
    assertThrows[IllegalArgumentException](split(secretOf(10), 0, 3))
    assertThrows[IllegalArgumentException](split(secretOf(10), 4, 3))
  }

  test("recover rejects duplicate share ids") {
    val shares = split(secretOf(10), 2, 3)
    assertThrows[IllegalArgumentException](recover(List(shares.head, shares.head)))
  }
