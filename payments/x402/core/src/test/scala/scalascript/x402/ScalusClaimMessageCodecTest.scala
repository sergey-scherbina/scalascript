package scalascript.x402

import org.scalatest.funsuite.AnyFunSuite

class ScalusClaimMessageCodecTest extends AnyFunSuite:

  test("encode: domain || receiver bytes || amount uint64 || validBefore uint64") {
    val receiver = Array[Byte](1, 2, 3)
    val bytes    = ScalusClaimMessageCodec.encode(receiver, BigInt(258), BigInt(1024))
    val domain   = ScalusClaimMessageCodec.domain.getBytes("UTF-8")

    assert(bytes.take(domain.length).toSeq == domain.toSeq)
    assert(bytes.slice(domain.length, domain.length + receiver.length).toSeq == receiver.toSeq)
    assert(bytes.takeRight(16).toSeq == Seq[Byte](
      0, 0, 0, 0, 0, 0, 1, 2,
      0, 0, 0, 0, 0, 0, 4, 0,
    ))
  }

  test("uint64: rejects negative and out-of-range values") {
    intercept[IllegalArgumentException](ScalusClaimMessageCodec.uint64(BigInt(-1), "x"))
    intercept[IllegalArgumentException](ScalusClaimMessageCodec.uint64(BigInt(1) << 64, "x"))
  }
