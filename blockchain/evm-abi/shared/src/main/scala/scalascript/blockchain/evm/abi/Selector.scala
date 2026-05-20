package scalascript.blockchain.evm.abi

import scalascript.crypto.{CryptoBackend, HashAlgo}

/** Function-selector + signature helpers.
 *
 *  Solidity ABI function selector = first 4 bytes of
 *  `keccak256(canonicalSignature)`, where `canonicalSignature` is
 *  `functionName(type1,type2,…)` with no spaces and types written
 *  in their canonical form (`uint256` rather than `uint`, etc.).
 *
 *  Event topic0 follows the same rule against the event's full
 *  canonical signature. */
object Selector:

  /** keccak256(signature)[0..4]. */
  def of(signature: String): Array[Byte] =
    val full = CryptoBackend.get().hash(HashAlgo.Keccak256, signature.getBytes("UTF-8"))
    java.util.Arrays.copyOfRange(full, 0, 4)

  /** Build a function signature: `name(t1,t2,…)`. */
  def signature(name: String, types: Seq[AbiType]): String =
    name + types.map(_.canonical).mkString("(", ",", ")")

  /** Compute the selector for `name(types)` in one step. */
  def forFunction(name: String, types: Seq[AbiType]): Array[Byte] =
    of(signature(name, types))

  /** keccak256 of an event signature — the value that lands in topic0
   *  for a non-anonymous event. */
  def eventTopic0(name: String, types: Seq[AbiType]): Array[Byte] =
    CryptoBackend.get().hash(HashAlgo.Keccak256, signature(name, types).getBytes("UTF-8"))
