package scalascript.micropayment.evm

import scalascript.blockchain.evm.Hex
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** ABI helpers for the `PaymentChannel` Solidity contract.
 *
 *  Bytecode: compile `PaymentChannel.sol` (in src/main/resources) with
 *  `solc --optimize --bin` or via Hardhat/Foundry and paste the result
 *  into `PaymentChannelBytecode.hex`. The placeholder below must be
 *  replaced before deploying to a live network. */
object PaymentChannelAbi:

  // ── State-signing ──────────────────────────────────────────────────────────

  /** Compute the message that the payer signs per payment state update.
   *  Matches the Solidity: `keccak256(abi.encodePacked(address(this), seq, cum))`.
   *  The caller must apply `personal_sign` prefix before signing or verifying. */
  def stateHash(contractAddr: String, sequence: Long, cumulative: BigInt): Array[Byte] =
    val addrBytes = Hex.decode(contractAddr).takeRight(20)  // strip any 0x and ensure 20 bytes
    val addrPad   = new Array[Byte](20 - addrBytes.length) ++ addrBytes
    val seqBytes  = Hex.bigIntTo32(BigInt(sequence))
    val cumBytes  = Hex.bigIntTo32(cumulative)
    CryptoBackend.get().hash(HashAlgo.Keccak256, addrPad ++ seqBytes ++ cumBytes)

  /** Message for cooperative-close: both parties sign
   *  `keccak256(abi.encodePacked("coop", address(this), cumulative))`. */
  def coopCloseHash(contractAddr: String, cumulative: BigInt): Array[Byte] =
    val addrBytes = Hex.decode(contractAddr).takeRight(20)
    val addrPad   = new Array[Byte](20 - addrBytes.length) ++ addrBytes
    val cumBytes  = Hex.bigIntTo32(cumulative)
    val prefix    = "coop".getBytes("UTF-8")
    CryptoBackend.get().hash(HashAlgo.Keccak256, prefix ++ addrPad ++ cumBytes)

  // ── Contract calldata builders ─────────────────────────────────────────────

  /** `submitFinalState(uint256 sequence, uint256 cumulative, bytes sig)` */
  def submitFinalStateCalldata(sequence: Long, cumulative: BigInt, sig: Array[Byte]): Array[Byte] =
    selector("submitFinalState(uint256,uint256,bytes)") ++
      Hex.bigIntTo32(BigInt(sequence)) ++
      Hex.bigIntTo32(cumulative) ++
      Hex.bigIntTo32(BigInt(3 * 32)) ++   // offset to `sig` tail (3 static slots × 32)
      encodeDynBytes(sig)

  /** `challenge(uint256 sequence, uint256 cumulative, bytes sig)` */
  def challengeCalldata(sequence: Long, cumulative: BigInt, sig: Array[Byte]): Array[Byte] =
    selector("challenge(uint256,uint256,bytes)") ++
      Hex.bigIntTo32(BigInt(sequence)) ++
      Hex.bigIntTo32(cumulative) ++
      Hex.bigIntTo32(BigInt(3 * 32)) ++
      encodeDynBytes(sig)

  /** `finalise()` — no arguments */
  def finaliseCalldata(): Array[Byte] =
    selector("finalise()")

  /** `cooperativeClose(uint256 cumulative, bytes payerSig, bytes payeeSig)` */
  def cooperativeCloseCalldata(cumulative: BigInt, payerSig: Array[Byte], payeeSig: Array[Byte]): Array[Byte] =
    val staticLen = 3 * 32                            // cum + 2 offset slots
    val payerOff  = staticLen                         // 96
    val payeeOff  = payerOff + dynBytesSlotLen(payerSig)
    selector("cooperativeClose(uint256,bytes,bytes)") ++
      Hex.bigIntTo32(cumulative) ++
      Hex.bigIntTo32(BigInt(payerOff)) ++
      Hex.bigIntTo32(BigInt(payeeOff)) ++
      encodeDynBytes(payerSig) ++
      encodeDynBytes(payeeSig)

  /** ABI-encoded constructor arguments for deployment. */
  def constructorArgs(
    payee:             String,
    token:             String,
    deposit:           BigInt,
    expiryTime:        BigInt,
    disputeWindowSecs: BigInt,
  ): Array[Byte] =
    Hex.leftPad32(Hex.decode(payee).takeRight(20)) ++
      Hex.leftPad32(Hex.decode(token).takeRight(20)) ++
      Hex.bigIntTo32(deposit) ++
      Hex.bigIntTo32(expiryTime) ++
      Hex.bigIntTo32(disputeWindowSecs)

  // ── Utilities ──────────────────────────────────────────────────────────────

  private def selector(sig: String): Array[Byte] =
    val h = CryptoBackend.get().hash(HashAlgo.Keccak256, sig.getBytes("UTF-8"))
    java.util.Arrays.copyOfRange(h, 0, 4)

  /** Encode `bytes` as ABI dynamic type: length word (32 bytes) + data right-padded to 32. */
  private def encodeDynBytes(data: Array[Byte]): Array[Byte] =
    val padLen = ((data.length + 31) / 32) * 32
    val padded = new Array[Byte](padLen)
    System.arraycopy(data, 0, padded, 0, data.length)
    Hex.bigIntTo32(BigInt(data.length)) ++ padded

  /** Total byte length that `encodeDynBytes` produces: one length slot + padded data. */
  private def dynBytesSlotLen(data: Array[Byte]): Int =
    32 + ((data.length + 31) / 32) * 32


/** Compiled bytecode of `PaymentChannel.sol`.
 *
 *  Replace the placeholder with the actual `solc --optimize --bin` output
 *  before deploying to mainnet. Use `solc ^0.8.20 --optimize --bin
 *  src/main/resources/PaymentChannel.sol` or equivalent via Foundry/Hardhat. */
object PaymentChannelBytecode:
  // TODO: replace with `solc --optimize --bin PaymentChannel.sol` output.
  val hex: String = ""
  lazy val bytes: Array[Byte] = if hex.isEmpty then Array.emptyByteArray else Hex.decode(hex)
