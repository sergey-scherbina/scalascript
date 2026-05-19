package scalascript.blockchain.spi

import scala.concurrent.Future
import scalascript.crypto.HashAlgo

/** The exact bytes a `RawSigner` should sign for a chain-specific tx,
 *  paired with a hint about whether the signer should pre-hash. */
case class SigningPayload(bytes: Array[Byte], hash: HashAlgo)

/** Opaque transaction hash. Hex-prefixed `0x…` on EVM; base58 on
 *  Solana; hex on Bitcoin / Cardano. Adapters normalise per chain. */
case class TxHash(value: String):
  override def toString: String = value

/** A single event log emitted by a transaction. The shape mirrors
 *  the EVM `(address, topics, data)` triple — non-EVM chains
 *  approximate to the same shape (Solana / Cardano logs carry
 *  similar contents, the adapter normalises). `topics(0)` is the
 *  canonical event hash for non-anonymous events. */
case class Log(
  address: String,
  topics:  Seq[Array[Byte]],
  data:    Array[Byte],
)

/** Generic on-chain receipt. Chain adapters MAY surface richer
 *  per-chain receipts; this is the cross-chain shape callers can rely
 *  on. */
case class TxReceipt(
  hash:        TxHash,
  success:     Boolean,
  blockNumber: BigInt,
  gasUsed:     BigInt,
  logs:        Seq[Log] = Seq.empty,
)

/** Human-readable description of a transaction for UI / dry-run / log
 *  output. The adapter populates `fields` with chain-native data
 *  (decoded tokens, recipients, fee breakdown, …). */
case class TxDescription(summary: String, fields: Map[String, String])

/** Per-call context for chain operations: an RPC channel plus a clock
 *  (passed in so tests can fake it). */
trait ChainContext:
  /** Generic JSON-RPC escape hatch. Most callers should prefer the
   *  typed `ChainAdapter` methods; this is for adapter-internal use
   *  and for clients that need chain-specific RPC. */
  def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value]

  /** Current Unix time in seconds. Adapters that need a clock (for
   *  EIP-3009 `validBefore` checks etc.) read this rather than calling
   *  `System.currentTimeMillis` directly, so tests can inject. */
  def nowSeconds: Long
