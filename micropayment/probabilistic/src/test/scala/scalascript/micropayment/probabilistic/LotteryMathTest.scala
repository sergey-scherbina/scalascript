package scalascript.micropayment.probabilistic

import org.scalatest.funsuite.AnyFunSuite
import java.security.MessageDigest

class LotteryMathTest extends AnyFunSuite:

  // ── Win-condition math ─────────────────────────────────────────────────────

  test("always wins when claimedAmount == maxPayout") {
    val preimage = Array.fill(32)(0x01.toByte)
    val salt     = Array.fill(32)(0xfe.toByte)
    assert(LotteryMath.isWinner(preimage, salt, claimedAmount = BigInt(1000), maxPayout = BigInt(1000)))
  }

  test("never wins when claimedAmount == 0 would be invalid: requirement enforced") {
    val preimage = Array.fill(32)(0.toByte)
    val salt     = Array.fill(32)(0.toByte)
    intercept[IllegalArgumentException] {
      LotteryMath.isWinner(preimage, salt, claimedAmount = BigInt(0), maxPayout = BigInt(1000))
    }
  }

  test("win probability approximates claimedAmount/maxPayout over many trials") {
    // Run 10 000 independent trials; each preimage is random.
    val rng         = new scala.util.Random(42L)
    val maxPayout   = BigInt(1_000_000)
    val claimed     = BigInt(200_000)      // 20 % win rate
    val salt        = Array.fill(32)(0xab.toByte)
    var wins        = 0
    val trials      = 10_000
    for _ <- 1 to trials do
      val preimage = new Array[Byte](32)
      rng.nextBytes(preimage)
      if LotteryMath.isWinner(preimage, salt, claimed, maxPayout) then wins += 1
    val empirical = wins.toDouble / trials
    val expected  = LotteryMath.winProbability(claimed, maxPayout)
    assert(math.abs(empirical - expected) < 0.02,
      s"Empirical win rate $empirical too far from expected $expected")
  }

  // ── Known test vectors ─────────────────────────────────────────────────────
  //
  // Computed offline: preimage = 0x00…00, salt = 0x00…00
  //   XOR = 0x00…00
  //   SHA-256(0x00…00 × 32) = 66687aad … (hex below)
  //   As uint256 ≈ 4.614e76
  //   threshold for claimed=500, max=1000: 2^255 ≈ 5.789e76
  //   4.614e76 < 5.789e76 → WINS

  test("known vector: zeros preimage + zeros salt, 50% rate → wins") {
    val preimage = Array.fill(32)(0.toByte)
    val salt     = Array.fill(32)(0.toByte)
    // sha256([0]*32) starts with 0x66... which is less than 0x80... (2^255 threshold)
    assert(LotteryMath.isWinner(preimage, salt, claimedAmount = BigInt(500), maxPayout = BigInt(1000)))
  }

  test("known vector: zeros preimage + zeros salt, 1% rate → loses") {
    // threshold = 1/100 * 2^256 ≈ 1.16e75; hashVal ≈ 4.6e76 → loses
    val preimage = Array.fill(32)(0.toByte)
    val salt     = Array.fill(32)(0.toByte)
    assert(!LotteryMath.isWinner(preimage, salt, claimedAmount = BigInt(10), maxPayout = BigInt(1000)))
  }

  test("known vector: ff…ff preimage + 00…00 salt → XOR = ff…ff, 50% rate → loses") {
    // sha256([0xff]*32) starts with 0xf4... which is > 0x80... → loses at 50%
    val preimage = Array.fill(32)(0xff.toByte)
    val salt     = Array.fill(32)(0.toByte)
    assert(!LotteryMath.isWinner(preimage, salt, claimedAmount = BigInt(500), maxPayout = BigInt(1000)))
  }

  // ── Commitment ─────────────────────────────────────────────────────────────

  test("commitment is SHA-256 of preimage") {
    val preimage    = "hello lottery".getBytes("UTF-8") ++ Array.fill(32 - 13)(0.toByte)
    val got         = LotteryMath.commitment(preimage)
    val expected    = MessageDigest.getInstance("SHA-256").digest(preimage)
    assert(got.toSeq == expected.toSeq)
  }

  // ── Server salt ─────────────────────────────────────────────────────────────

  test("serverSalt is deterministic for same key + commitment") {
    val key        = Array.fill(32)(0x42.toByte)
    val commitment = LotteryMath.commitment(Array.fill(32)(0x01.toByte))
    val s1 = LotteryMath.serverSalt(key, commitment)
    val s2 = LotteryMath.serverSalt(key, commitment)
    assert(s1.toSeq == s2.toSeq)
  }

  test("serverSalt differs for different commitments") {
    val key = Array.fill(32)(0x42.toByte)
    val c1  = LotteryMath.commitment(Array.fill(32)(0x01.toByte))
    val c2  = LotteryMath.commitment(Array.fill(32)(0x02.toByte))
    assert(LotteryMath.serverSalt(key, c1).toSeq != LotteryMath.serverSalt(key, c2).toSeq)
  }

  test("serverSalt returns 32 bytes") {
    val key        = Array.fill(32)(0.toByte)
    val commitment = Array.fill(32)(0.toByte)
    assert(LotteryMath.serverSalt(key, commitment).length == 32)
  }

  // ── requirenment guards ────────────────────────────────────────────────────

  test("isWinner requires preimage and salt to be 32 bytes") {
    intercept[IllegalArgumentException] {
      LotteryMath.isWinner(Array.fill(16)(0.toByte), Array.fill(32)(0.toByte), BigInt(1), BigInt(2))
    }
    intercept[IllegalArgumentException] {
      LotteryMath.isWinner(Array.fill(32)(0.toByte), Array.fill(16)(0.toByte), BigInt(1), BigInt(2))
    }
  }
