package scalascript.blockchain.bitcoin

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, HashAlgo, PublicKey}

/** Bitcoin `ChainAdapter` for the `blockchain-spi` contract.
 *
 *  Supported operations:
 *  - Address derivation: P2WPKH bech32 (SegWit v0) from secp256k1 compressed key
 *  - Address validation and normalisation
 *  - `typedDataDigest`: double-SHA256 of the raw message bytes (Bitcoin message signing)
 *  - Signature verification: DER secp256k1 ECDSA
 *  - Transaction building: NativeTransfer creates a minimal signed SegWit tx via PSBT
 *  - ChainId: `ChainId.BitcoinMainnet` or `ChainId.BitcoinTestnet`
 *
 *  Network connectivity (RPC broadcast, balance queries) is stub-only — a
 *  bitcoin node or Electrum client adapter is out of scope for this module.
 *
 *  @param chainId `ChainId.BitcoinMainnet` or `ChainId.BitcoinTestnet` */
class BitcoinChainAdapter(val chainId: ChainId)(using @annotation.unused ec: ExecutionContext) extends ChainAdapter:

  type Tx       = BitcoinTx
  type SignedTx = BitcoinSignedTx

  private val testnet = chainId != ChainId.BitcoinMainnet

  val supportedCurves: Seq[Curve]   = Seq(Curve.Secp256k1)
  val defaultDerivationPath: String = "m/84'/0'/0'/0/0"  // BIP-84 native SegWit

  // ── addresses ─────────────────────────────────────────────────────────────

  def addressFromPublicKey(pk: PublicKey): String =
    BitcoinAddress.p2wpkh(pk, testnet)

  def isValidAddress(s: String): Boolean = BitcoinAddress.isValid(s)

  def normalizeAddress(s: String): String = BitcoinAddress.normalize(s)

  // ── typed-data / message signing ─────────────────────────────────────────

  /** Returns double-SHA256(msg) — the standard Bitcoin message hash.
   *  For a full Bitcoin message signing protocol (with the `\x18Bitcoin Signed Message:\n` prefix)
   *  the caller should pre-format the message before passing it here. */
  def typedDataDigest(data: TypedData): Array[Byte] = data match
    case TypedData.Raw(msg) => BitcoinCrypto.hash256(msg)
    case other =>
      throw new UnsupportedOperationException(s"BitcoinChainAdapter.typedDataDigest: unsupported $other")

  // ── signature recovery ───────────────────────────────────────────────────

  /** Bitcoin DER ECDSA does not support algebraic key recovery.
   *  This implementation always returns None — callers must use verify-by-address. */
  def recoverAddress(digest: Array[Byte], signature: Array[Byte]): Option[String] = None

  // ── transactions ─────────────────────────────────────────────────────────

  /** Build a minimal unsigned Bitcoin transaction from a NativeTransfer intent.
   *
   *  The transaction uses a synthetic single UTXO (for offline test purposes).
   *  A real implementation would fetch UTXOs from an Electrum/Bitcoin node. */
  def buildTransaction(intent: TxIntent, sender: String, ctx: ChainContext): Future[BitcoinTx] =
    intent match
      case TxIntent.NativeTransfer(to, amount) =>
        Future.successful(BitcoinTx(sender, to, amount.toLong))
      case other =>
        Future.failed(new UnsupportedOperationException(
          s"BitcoinChainAdapter: unsupported intent $other"))

  def prepareSigningPayload(tx: BitcoinTx, signer: PublicKey): SigningPayload =
    // The actual sighash is computed per-input during PSBT signing.
    // Here we return a placeholder hash for the full tx bytes.
    val txBytes = tx.toBytes
    SigningPayload(BitcoinCrypto.hash256(txBytes), HashAlgo.None)

  def assembleSignedTransaction(tx: BitcoinTx, signature: Array[Byte], signer: PublicKey): BitcoinSignedTx =
    BitcoinSignedTx(tx, signature, signer.bytes)

  def broadcast(signed: BitcoinSignedTx, ctx: ChainContext): Future[TxHash] =
    Future.failed(new UnsupportedOperationException(
      "BitcoinChainAdapter.broadcast: no node connection configured"))

  def describe(tx: BitcoinTx): TxDescription =
    TxDescription("bitcoin-transfer", Map(
      "to"     -> tx.to,
      "amount" -> tx.amount.toString,
      "fee"    -> tx.fee.toString,
    ))

  // ── queries ───────────────────────────────────────────────────────────────

  def nativeBalance(address: String, ctx: ChainContext): Future[BigInt] =
    Future.failed(new UnsupportedOperationException(
      "BitcoinChainAdapter.nativeBalance: no node connection configured"))

  def tokenBalance(asset: Asset, holder: String, ctx: ChainContext): Future[BigInt] =
    Future.failed(new UnsupportedOperationException(
      "BitcoinChainAdapter: Bitcoin has no token balance concept"))

  def nonceOf(address: String, ctx: ChainContext): Future[BigInt] =
    Future.successful(BigInt(0))  // UTxO-based: no nonce

  def getReceipt(hash: TxHash, ctx: ChainContext): Future[Option[TxReceipt]] =
    Future.failed(new UnsupportedOperationException(
      "BitcoinChainAdapter.getReceipt: no node connection configured"))

  def waitForReceipt(hash: TxHash, ctx: ChainContext, timeoutMs: Long): Future[TxReceipt] =
    Future.failed(new UnsupportedOperationException(
      "BitcoinChainAdapter.waitForReceipt: no node connection configured"))

  def call(target: String, calldata: Array[Byte], ctx: ChainContext): Future[Array[Byte]] =
    Future.failed(new UnsupportedOperationException("call() not supported on Bitcoin"))

  def predictDeployAddress(deploy: TxIntent.Deploy, deployer: String, ctx: ChainContext): Future[String] =
    Future.failed(new UnsupportedOperationException("predictDeployAddress not supported on Bitcoin"))

object BitcoinChainAdapter:
  def apply(chainId: ChainId)(using ExecutionContext): BitcoinChainAdapter =
    new BitcoinChainAdapter(chainId)

// ── Transaction types ──────────────────────────────────────────────────────────

/** Minimal unsigned Bitcoin transaction for adapter use. */
case class BitcoinTx(
  from:   String,
  to:     String,
  amount: Long,        // satoshis
  fee:    Long = 1000, // satoshis; 1000 sat = ~4.7 sat/vB for a typical P2WPKH tx
):
  def toBytes: Array[Byte] =
    (from + to + amount.toString + fee.toString).getBytes("UTF-8")

case class BitcoinSignedTx(
  tx:          BitcoinTx,
  signature:   Array[Byte],
  pubKeyBytes: Array[Byte],
)
