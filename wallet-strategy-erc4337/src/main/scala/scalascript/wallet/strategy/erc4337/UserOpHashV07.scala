package scalascript.wallet.strategy.erc4337

import scalascript.blockchain.evm.abi.{AbiType, AbiValue, AbiEncoder}
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** EIP-4337 v0.7 userOpHash — the digest the smart account's EOA
 *  owner signs when the wallet targets EntryPoint v0.7.
 *
 *  v0.7 keeps the same outer composition as v0.6
 *  (`keccak256(abi.encode(keccak256(packed), entryPoint, chainId))`)
 *  but compresses the inner packing: gas limits and fee parameters
 *  are folded into two `bytes32` words rather than six `uint256`s.
 *
 *      accountGasLimits = (verificationGasLimit << 128) | callGasLimit
 *      gasFees          = (maxPriorityFeePerGas << 128) | maxFeePerGas
 *
 *      packed   = abi.encode(
 *        sender, nonce,
 *        keccak256(initCode), keccak256(callData),
 *        accountGasLimits,
 *        preVerificationGas,
 *        gasFees,
 *        keccak256(paymasterAndData),
 *      )
 *      userOpHash = keccak256(abi.encode(
 *        keccak256(packed), entryPoint, chainId,
 *      ))
 *
 *  Note `paymasterAndData` is kept as a single byte string for the
 *  on-chain hash — its v0.7 internal layout (paymaster ‖ verGas ‖
 *  postOpGas ‖ data) is a wire-side concern, not a hashing one. */
object UserOpHashV07:

  private def keccak(b: Array[Byte]): Array[Byte] =
    CryptoBackend.get().hash(HashAlgo.Keccak256, b)

  /** Pack two ≤128-bit unsigned ints into a 32-byte word — `hi`
   *  occupies the upper 128 bits, `lo` the lower 128. */
  def pack128(hi: BigInt, lo: BigInt): Array[Byte] =
    require(hi.signum >= 0 && hi.bitLength <= 128, s"hi out of range: $hi")
    require(lo.signum >= 0 && lo.bitLength <= 128, s"lo out of range: $lo")
    val out = new Array[Byte](32)
    def writeLow16(src: BigInt, off: Int): Unit =
      val raw = src.toByteArray
      val trimmed =
        if raw.length > 1 && raw(0) == 0 then raw.drop(1)
        else if raw.length == 1 && raw(0) == 0 then Array.emptyByteArray
        else raw
      val start = off + (16 - trimmed.length)
      System.arraycopy(trimmed, 0, out, start, trimmed.length)
    writeLow16(hi, 0)
    writeLow16(lo, 16)
    out

  def accountGasLimits(op: UserOperation): Array[Byte] =
    pack128(op.verificationGasLimit, op.callGasLimit)

  def gasFees(op: UserOperation): Array[Byte] =
    pack128(op.maxPriorityFeePerGas, op.maxFeePerGas)

  private val PackedTypes: Seq[AbiType] = Seq(
    AbiType.Address,         // sender
    AbiType.UInt(256),       // nonce
    AbiType.FixedBytes(32),  // keccak(initCode)
    AbiType.FixedBytes(32),  // keccak(callData)
    AbiType.FixedBytes(32),  // accountGasLimits
    AbiType.UInt(256),       // preVerificationGas
    AbiType.FixedBytes(32),  // gasFees
    AbiType.FixedBytes(32),  // keccak(paymasterAndData)
  )

  private val OuterTypes: Seq[AbiType] = Seq(
    AbiType.FixedBytes(32),  // keccak(packed)
    AbiType.Address,         // entryPoint
    AbiType.UInt(256),       // chainId
  )

  /** ABI-encoded inner "packed" form — input to the outer keccak.
   *  Exposed for test introspection / cross-verification against
   *  reference vectors. */
  def packed(op: UserOperation): Array[Byte] =
    AbiEncoder.encodeTuple(PackedTypes, Seq(
      AbiValue.Address(op.sender),
      AbiValue.UInt(256, op.nonce),
      AbiValue.FixedBytes(32, keccak(op.initCode)),
      AbiValue.FixedBytes(32, keccak(op.callData)),
      AbiValue.FixedBytes(32, accountGasLimits(op)),
      AbiValue.UInt(256, op.preVerificationGas),
      AbiValue.FixedBytes(32, gasFees(op)),
      AbiValue.FixedBytes(32, keccak(op.paymasterAndData)),
    ))

  /** Compute the v0.7 userOpHash. */
  def compute(op: UserOperation, entryPoint: String, chainId: BigInt): Array[Byte] =
    val innerHash = keccak(packed(op))
    val outer = AbiEncoder.encodeTuple(OuterTypes, Seq(
      AbiValue.FixedBytes(32, innerHash),
      AbiValue.Address(entryPoint),
      AbiValue.UInt(256, chainId),
    ))
    keccak(outer)
