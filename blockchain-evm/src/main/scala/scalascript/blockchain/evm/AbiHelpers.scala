package scalascript.blockchain.evm

import scalascript.crypto.{CryptoBackend, HashAlgo}

/** Minimal ABI helpers used by `blockchain-evm`'s Phase 1 read-side.
 *  A general-purpose Solidity ABI v2 codec lands in Phase 2 as a
 *  dedicated `blockchain-evm-abi` sub-module — see
 *  docs/blockchain-spi.md §6.1. */
private[evm] object AbiHelpers:

  /** keccak256(signature)[0..4] — function selector. */
  def selector(signature: String): Array[Byte] =
    val full = CryptoBackend.get().hash(HashAlgo.Keccak256, signature.getBytes("UTF-8"))
    java.util.Arrays.copyOfRange(full, 0, 4)

  /** ERC-20 `balanceOf(address)` selector (`0x70a08231`) — cached. */
  val Erc20BalanceOfSelector: Array[Byte] =
    selector("balanceOf(address)")

  /** Encode `balanceOf(address)` calldata. */
  def erc20BalanceOfCalldata(holder: String): Array[Byte] =
    val padded = Hex.leftPad32(Hex.decode(holder))
    Erc20BalanceOfSelector ++ padded

  /** Decode a single uint256 ABI-encoded return value. */
  def decodeUint256(returnBytes: Array[Byte]): BigInt =
    require(returnBytes.length == 32, s"Expected 32-byte uint256, got ${returnBytes.length}")
    BigInt(1, returnBytes)
