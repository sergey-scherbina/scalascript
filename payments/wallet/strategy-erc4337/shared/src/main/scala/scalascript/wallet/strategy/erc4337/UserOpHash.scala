package scalascript.wallet.strategy.erc4337

import scalascript.blockchain.evm.abi.{AbiType, AbiValue, AbiEncoder}
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** EIP-4337 v0.6 userOpHash — the digest the smart account's EOA
 *  owner signs.
 *
 *  Formula (from the EntryPoint v0.6 reference impl):
 *
 *      packed   = abi.encode(
 *        sender, nonce,
 *        keccak256(initCode), keccak256(callData),
 *        callGasLimit, verificationGasLimit, preVerificationGas,
 *        maxFeePerGas, maxPriorityFeePerGas,
 *        keccak256(paymasterAndData),
 *      )
 *      userOpHash = keccak256(abi.encode(
 *        keccak256(packed), entryPoint, chainId
 *      ))
 *
 *  Signing `userOpHash` directly is fine because keccak256 already
 *  domain-separates by entryPoint + chainId, so the same signature
 *  can't replay on a different chain or against a different
 *  EntryPoint. */
object UserOpHash:

  private def keccak(b: Array[Byte]): Array[Byte] =
    CryptoBackend.get().hash(HashAlgo.Keccak256, b)

  /** Field-types for the inner pack, in the exact v0.6 order. */
  private val PackedTypes: Seq[AbiType] = Seq(
    AbiType.Address,      // sender
    AbiType.UInt(256),    // nonce
    AbiType.FixedBytes(32),  // keccak(initCode)
    AbiType.FixedBytes(32),  // keccak(callData)
    AbiType.UInt(256),    // callGasLimit
    AbiType.UInt(256),    // verificationGasLimit
    AbiType.UInt(256),    // preVerificationGas
    AbiType.UInt(256),    // maxFeePerGas
    AbiType.UInt(256),    // maxPriorityFeePerGas
    AbiType.FixedBytes(32),  // keccak(paymasterAndData)
  )

  private val OuterTypes: Seq[AbiType] = Seq(
    AbiType.FixedBytes(32),  // keccak(packed)
    AbiType.Address,         // entryPoint
    AbiType.UInt(256),       // chainId
  )

  /** Returns the ABI-encoded "packed" form of the UserOp — input to
   *  the outer keccak. Exposed for test introspection. */
  def packed(op: UserOperation): Array[Byte] =
    val values: Seq[AbiValue] = Seq(
      AbiValue.Address(op.sender),
      AbiValue.UInt(256, op.nonce),
      AbiValue.FixedBytes(32, keccak(op.initCode)),
      AbiValue.FixedBytes(32, keccak(op.callData)),
      AbiValue.UInt(256, op.callGasLimit),
      AbiValue.UInt(256, op.verificationGasLimit),
      AbiValue.UInt(256, op.preVerificationGas),
      AbiValue.UInt(256, op.maxFeePerGas),
      AbiValue.UInt(256, op.maxPriorityFeePerGas),
      AbiValue.FixedBytes(32, keccak(op.paymasterAndData)),
    )
    AbiEncoder.encodeTuple(PackedTypes, values)

  /** Compute the v0.6 userOpHash for a UserOp + EntryPoint + chainId.
   *  This is what the EOA owner signs (length-65 secp256k1 sig
   *  goes into op.signature). */
  def compute(op: UserOperation, entryPoint: String, chainId: BigInt): Array[Byte] =
    val innerHash = keccak(packed(op))
    val outer = AbiEncoder.encodeTuple(OuterTypes, Seq(
      AbiValue.FixedBytes(32, innerHash),
      AbiValue.Address(entryPoint),
      AbiValue.UInt(256, chainId),
    ))
    keccak(outer)
