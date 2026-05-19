package scalascript.blockchain.spi

/** Off-chain typed-data envelope. Each variant is shaped to its chain
 *  family's signing convention; the adapter's `typedDataDigest` knows
 *  how to hash it for signing.
 *
 *  See docs/blockchain-spi.md §5 and §6 for usage. */
sealed trait TypedData

object TypedData:

  /** EIP-712 typed structured data (EVM family). The `domain`, `types`,
   *  and `value` mirror the JSON-RPC `eth_signTypedData_v4` shape. */
  case class Eip712(
    domain:      Map[String, ujson.Value],
    types:       Map[String, Seq[(String, String)]],
    value:       Map[String, ujson.Value],
    primaryType: String,
  ) extends TypedData

  /** Cosmos SDK sign_doc bytes (already serialized — the adapter hashes
   *  with SHA-256 per Cosmos conventions). */
  case class CosmosSignDoc(bytes: Array[Byte]) extends TypedData

  /** CIP-8 / CIP-30 COSE_Sign1 structure for Cardano. */
  case class Cip8(payload: Array[Byte], headers: Array[Byte]) extends TypedData

  /** Arbitrary pre-formatted bytes; the adapter hashes per its chain's
   *  message-signing convention (e.g. EVM `personal_sign` prefix). */
  case class Raw(bytes: Array[Byte]) extends TypedData
