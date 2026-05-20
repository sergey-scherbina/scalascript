package scalascript.wallet.strategy.erc4337

import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockchain.evm.Hex
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** Tests for the EIP-4337 v0.7 PackedUserOperation userOpHash.
 *
 *  v0.7 compresses six u256 gas/fee fields into two bytes32 words —
 *  if we pack the wrong way, every signature we produce against a
 *  v0.7 EntryPoint is rejected. The tests cross-verify the packing
 *  layout (high/low halves) and the outer hash composition. */
class UserOpHashV07Test extends AnyFunSuite:

  private val sample = UserOperation(
    sender               = "0x9406Cc6185a346906296840746125a0E44976454",
    nonce                = BigInt(0),
    initCode             = Array.emptyByteArray,
    callData             = Hex.decode("0xdeadbeef"),
    callGasLimit         = BigInt(100_000),
    verificationGasLimit = BigInt(150_000),
    preVerificationGas   = BigInt(50_000),
    maxFeePerGas         = BigInt(2_000_000_000L),
    maxPriorityFeePerGas = BigInt(1_000_000_000L),
    paymasterAndData     = Array.emptyByteArray,
    signature            = Array.emptyByteArray,
  )

  test("pack128 puts hi in the upper 16 B, lo in the lower 16 B") {
    val p = UserOpHashV07.pack128(BigInt("ffffffffffffffffffffffffffffffff", 16), BigInt(1))
    assert(p.length == 32)
    assert(Hex.encode(p, withPrefix = false) ==
      "ffffffffffffffffffffffffffffffff00000000000000000000000000000001")
  }

  test("pack128 zero-pads short values on the left of each half") {
    val p = UserOpHashV07.pack128(BigInt(0x12), BigInt(0x34))
    assert(Hex.encode(p, withPrefix = false) ==
      "00000000000000000000000000000012" +
      "00000000000000000000000000000034")
  }

  test("pack128 rejects values that overflow 128 bits") {
    intercept[IllegalArgumentException] {
      UserOpHashV07.pack128(BigInt(1) << 128, BigInt(0))
    }
    intercept[IllegalArgumentException] {
      UserOpHashV07.pack128(BigInt(0), BigInt(1) << 128)
    }
  }

  test("accountGasLimits packs (verification | call) per v0.7 spec") {
    val gl = UserOpHashV07.accountGasLimits(sample)
    val hi = BigInt(1, java.util.Arrays.copyOfRange(gl, 0, 16))
    val lo = BigInt(1, java.util.Arrays.copyOfRange(gl, 16, 32))
    assert(hi == sample.verificationGasLimit)
    assert(lo == sample.callGasLimit)
  }

  test("gasFees packs (priority | max) per v0.7 spec") {
    val gf = UserOpHashV07.gasFees(sample)
    val hi = BigInt(1, java.util.Arrays.copyOfRange(gf, 0, 16))
    val lo = BigInt(1, java.util.Arrays.copyOfRange(gf, 16, 32))
    assert(hi == sample.maxPriorityFeePerGas)
    assert(lo == sample.maxFeePerGas)
  }

  test("packed is 8 × 32 B — every field is a static word") {
    // v0.7 inner pack drops the 4 redundant u256 gas/fee fields
    // (now compressed into 2 bytes32). 8 × 32 = 256B vs v0.6's 320B.
    val p = UserOpHashV07.packed(sample)
    assert(p.length == 256, s"expected 256B packed, got ${p.length}")
  }

  test("compute is deterministic + length 32") {
    val a = UserOpHashV07.compute(sample, EntryPoint.V07Address, BigInt(1))
    val b = UserOpHashV07.compute(sample, EntryPoint.V07Address, BigInt(1))
    assert(a.toSeq == b.toSeq)
    assert(a.length == 32)
  }

  test("v0.7 hash differs from v0.6 hash for the same UserOp") {
    val v6 = UserOpHash.compute(sample,    EntryPoint.V06Address, BigInt(1))
    val v7 = UserOpHashV07.compute(sample, EntryPoint.V07Address, BigInt(1))
    assert(v6.toSeq != v7.toSeq, "v0.6 and v0.7 must produce distinct signing payloads")
  }

  test("compute matches manually-derived hash: keccak(encode(keccak(packed), ep, cid))") {
    val be        = CryptoBackend.get()
    val packed    = UserOpHashV07.packed(sample)
    val innerHash = be.hash(HashAlgo.Keccak256, packed)
    val epBytes   = Hex.decode(EntryPoint.V07Address)
    val outer     = new Array[Byte](32 + 32 + 32)
    System.arraycopy(innerHash, 0, outer, 0, 32)
    System.arraycopy(epBytes,   0, outer, 32 + 12, 20)
    outer(64 + 31) = 1.toByte
    val expected = be.hash(HashAlgo.Keccak256, outer)
    val actual   = UserOpHashV07.compute(sample, EntryPoint.V07Address, BigInt(1))
    assert(expected.toSeq == actual.toSeq)
  }

  test("compute reacts to packed-field changes (callGasLimit only)") {
    // Changing callGasLimit alone (which goes into the lower half of
    // accountGasLimits) must move the hash — sanity-check that the
    // packing isn't truncating field data.
    val base    = UserOpHashV07.compute(sample, EntryPoint.V07Address, BigInt(1))
    val changed = UserOpHashV07.compute(sample.copy(callGasLimit = BigInt(100_001)),
                                        EntryPoint.V07Address, BigInt(1))
    assert(base.toSeq != changed.toSeq)
  }
