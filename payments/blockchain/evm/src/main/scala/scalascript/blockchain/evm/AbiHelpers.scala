package scalascript.blockchain.evm

import scalascript.crypto.{CryptoBackend, HashAlgo}

/** Hand-coded calldata helpers for the well-known ERC-20 / ERC-3009
 *  signatures that `blockchain-evm` Phase 1+2 use directly. A general
 *  Solidity ABI v2 codec replaces this in Phase 2 Slice B as the
 *  `blockchain-evm-abi` sub-module — see specs/blockchain-spi.md §6.1.
 *
 *  Promoted from `private[evm]` to public in Slice B; callers that
 *  produce custom calldata reach for the codec module instead. */
private[evm] object AbiHelpers:

  /** keccak256(signature)[0..4] — function selector. */
  def selector(signature: String): Array[Byte] =
    val full = CryptoBackend.get().hash(HashAlgo.Keccak256, signature.getBytes("UTF-8"))
    java.util.Arrays.copyOfRange(full, 0, 4)

  // ── ERC-20 ───────────────────────────────────────────────────────────

  /** ERC-20 `balanceOf(address)` selector (`0x70a08231`) — cached. */
  val Erc20BalanceOfSelector: Array[Byte] =
    selector("balanceOf(address)")

  /** Encode `balanceOf(address)` calldata. */
  def erc20BalanceOfCalldata(holder: String): Array[Byte] =
    val padded = Hex.leftPad32(Hex.decode(holder))
    Erc20BalanceOfSelector ++ padded

  /** ERC-20 `transfer(address,uint256)` selector (`0xa9059cbb`). */
  val Erc20TransferSelector: Array[Byte] =
    selector("transfer(address,uint256)")

  def erc20TransferCalldata(to: String, amount: BigInt): Array[Byte] =
    Erc20TransferSelector ++
      Hex.leftPad32(Hex.decode(to)) ++
      Hex.bigIntTo32(amount)

  // ── ERC-3009 (used by x402 settle) ───────────────────────────────────

  /** ERC-3009 `transferWithAuthorization(address,address,uint256,
   *  uint256,uint256,bytes32,uint8,bytes32,bytes32)` selector
   *  (`0xe3ee160e`). */
  val Erc3009TransferWithAuthorizationSelector: Array[Byte] =
    selector(
      "transferWithAuthorization(address,address,uint256,uint256,uint256,bytes32,uint8,bytes32,bytes32)",
    )

  /** Encode an ERC-3009 transferWithAuthorization call. The 65-byte
   *  signature is split into r (32) || s (32) || v (1). */
  def erc3009TransferWithAuthorizationCalldata(
    from:        String,
    to:          String,
    value:       BigInt,
    validAfter:  BigInt,
    validBefore: BigInt,
    nonce:       Array[Byte],
    signature:   Array[Byte],
  ): Array[Byte] =
    require(nonce.length == 32, s"nonce must be 32 bytes, got ${nonce.length}")
    require(signature.length == 65, s"signature must be 65 bytes, got ${signature.length}")
    val r = java.util.Arrays.copyOfRange(signature, 0,  32)
    val s = java.util.Arrays.copyOfRange(signature, 32, 64)
    val v = signature(64) & 0xff
    Erc3009TransferWithAuthorizationSelector ++
      Hex.leftPad32(Hex.decode(from)) ++
      Hex.leftPad32(Hex.decode(to)) ++
      Hex.bigIntTo32(value) ++
      Hex.bigIntTo32(validAfter) ++
      Hex.bigIntTo32(validBefore) ++
      nonce ++
      Hex.bigIntTo32(BigInt(v)) ++
      r ++ s

  /** Decode a single uint256 ABI-encoded return value. */
  def decodeUint256(returnBytes: Array[Byte]): BigInt =
    require(returnBytes.length == 32, s"Expected 32-byte uint256, got ${returnBytes.length}")
    BigInt(1, returnBytes)
