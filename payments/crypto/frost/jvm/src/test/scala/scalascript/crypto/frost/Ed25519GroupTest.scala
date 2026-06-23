package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import java.math.BigInteger

class Ed25519GroupTest extends AnyFunSuite:
  import Ed25519Group.*

  private def hex(b: Array[Byte]): String = b.map("%02x".format(_)).mkString

  test("base point encodes to the RFC 8032 standard value"):
    assert(hex(encode(B)) == "5866666666666666666666666666666666666666666666666666666666666666")

  test("B has order L: L·B = identity"):
    assert(samePoint(mul(L, B), Identity))

  test("encode/decode round-trips for small multiples of B"):
    for k <- 1 to 6 do
      val p = mul(BigInteger.valueOf(k.toLong), B)
      assert(decode(encode(p)).exists(samePoint(_, p)), s"round-trip failed at k=$k")

  test("group homomorphism: B·(a+b) = B·a + B·b  and  B·(a·b) = (B·a)·b"):
    val a = new BigInteger("123456789012345678901234567890")
    val b = new BigInteger("987654321098765432109876543210")
    assert(samePoint(mulBase(a.add(b)), add(mulBase(a), mulBase(b))))
    assert(samePoint(mulBase(a.multiply(b)), mul(b, mulBase(a))))

  test("scalar field: inverse and reduce mod L"):
    val a = new BigInteger("424242424242424242424242")
    assert(scalarMul(a, scalarInv(a)) == BigInteger.ONE)
    assert(scalarReduce(L.add(BigInteger.valueOf(7))) == BigInteger.valueOf(7))

  // THE correctness gate: our from-scratch keygen must match a reference Ed25519 (BouncyCastle) bit-for-bit.
  test("public keys match reference BouncyCastle Ed25519 for random seeds"):
    val rnd = new java.util.Random(0xF1057L) // fixed → deterministic
    for _ <- 1 to 25 do
      val seed = new Array[Byte](32); rnd.nextBytes(seed)
      val mine = encode(mulBase(secretScalar(seed)))
      val ref  = new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        .generatePublicKey().getEncoded
      assert(hex(mine) == hex(ref), s"pubkey mismatch for seed ${hex(seed)}")
