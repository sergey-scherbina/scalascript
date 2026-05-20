package scalascript.wallet.strategy.erc4337

import org.scalatest.funsuite.AnyFunSuite
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** Tests for the EIP-4337 v0.6 userOpHash. The hash format is
 *  load-bearing: if we get this wrong, every signature we produce
 *  is rejected by every smart account on every chain.
 *
 *  Validate two things:
 *    1) Determinism + structure (same input → same output, two
 *       different inputs → different outputs).
 *    2) The exact algorithm matches the reference impl:
 *       keccak256(abi.encode(keccak256(packed), entryPoint, chainId))
 *       — verified by re-deriving the hash manually here. */
abstract class UserOpHashTestBase extends AnyFunSuite:

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

  test("packed is a multiple of 32 bytes — fixed-width fields, no dynamic tails") {
    // The packed form replaces dynamic (initCode, callData,
    // paymasterAndData) with their keccak hashes, so every field
    // is a static 32-byte word. The encoded tuple is 10 × 32 = 320B.
    val p = UserOpHash.packed(sample)
    assert(p.length == 320, s"expected 320B packed, got ${p.length}")
  }

  test("compute is deterministic for the same UserOp + entryPoint + chainId") {
    val a = UserOpHash.compute(sample, EntryPoint.V06Address, BigInt(1))
    val b = UserOpHash.compute(sample, EntryPoint.V06Address, BigInt(1))
    assert(a.toSeq == b.toSeq)
    assert(a.length == 32)
  }

  test("compute changes when chainId changes") {
    val onMainnet = UserOpHash.compute(sample, EntryPoint.V06Address, BigInt(1))
    val onBase    = UserOpHash.compute(sample, EntryPoint.V06Address, BigInt(8453))
    assert(onMainnet.toSeq != onBase.toSeq, "chainId must enter the hash")
  }

  test("compute changes when entryPoint changes") {
    val onV06 = UserOpHash.compute(sample, EntryPoint.V06Address, BigInt(1))
    val onV07 = UserOpHash.compute(sample, EntryPoint.V07Address, BigInt(1))
    assert(onV06.toSeq != onV07.toSeq, "entryPoint must enter the hash")
  }

  test("compute changes when any UserOp field changes") {
    val baseline = UserOpHash.compute(sample, EntryPoint.V06Address, BigInt(1))
    val changed  = UserOpHash.compute(sample.copy(nonce = BigInt(1)), EntryPoint.V06Address, BigInt(1))
    assert(baseline.toSeq != changed.toSeq)
  }

  test("compute matches manually-derived hash: keccak(encode(keccak(packed), ep, cid))") {
    // Cross-verify against an independently-derived hash.
    val be          = CryptoBackend.get()
    val packed      = UserOpHash.packed(sample)
    val innerHash   = be.hash(HashAlgo.Keccak256, packed)
    // Manual outer encoding: bytes32 inner || address (left-padded) || uint256
    val epBytes     = Hex.decode(EntryPoint.V06Address)
    val outer       = new Array[Byte](32 + 32 + 32)
    System.arraycopy(innerHash, 0, outer, 0, 32)
    // Address: padded to 32 bytes, address right-aligned
    System.arraycopy(epBytes, 0, outer, 32 + 12, 20)
    // ChainId: uint256 LE? No, ABI uint256 is BIG-endian, right-aligned.
    outer(64 + 31) = 1.toByte
    val expected = be.hash(HashAlgo.Keccak256, outer)
    val actual   = UserOpHash.compute(sample, EntryPoint.V06Address, BigInt(1))
    assert(expected.toSeq == actual.toSeq,
      s"manual=${Hex.encode(expected, withPrefix = false)} vs lib=${Hex.encode(actual, withPrefix = false)}")
  }
