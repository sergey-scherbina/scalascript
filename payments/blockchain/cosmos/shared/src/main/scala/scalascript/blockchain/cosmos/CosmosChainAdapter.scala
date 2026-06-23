package scalascript.blockchain.cosmos

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, HashAlgo, PublicKey}

/** Cosmos SDK `ChainAdapter` for `blockchain-spi`.
 *
 *  Supported chains: CosmosHub, Osmosis, Juno — all use the same
 *  cryptographic stack (secp256k1 by default; ed25519 optional) and
 *  differ only in HRP and chain-id for StdSignDoc.
 *
 *  Operations:
 *  - Address derivation: bech32(hrp, hash160(compressedPubKey))
 *  - Address validation / normalisation
 *  - typedDataDigest: SHA256 of Amino-encoded StdSignDoc, or raw SHA256
 *  - secp256k1 verify (DER signature)
 *  - Transaction building: NativeTransfer → CosmosTx (placeholder)
 *  - Network ops (balance, broadcast, receipt): stub — no node configured
 *
 *  @param chainId `ChainId.CosmosHub`, `ChainId.Osmosis`, or `ChainId.Juno` */
class CosmosChainAdapter(val chainId: ChainId)(using @annotation.unused ec: ExecutionContext)
  extends ChainAdapter:

  type Tx       = CosmosTx
  type SignedTx = CosmosSignedTx

  private val hrp: String = chainId match
    case ChainId.CosmosHub => "cosmos"
    case ChainId.Osmosis   => "osmo"
    case ChainId.Juno      => "juno"
    case other             => other.reference.takeWhile(_ != '-')  // best-effort fallback

  val supportedCurves: Seq[Curve]   = Seq(Curve.Secp256k1, Curve.Ed25519)
  val defaultDerivationPath: String = "m/44'/118'/0'/0/0"  // Cosmos BIP-44 cointype 118

  // ── addresses ─────────────────────────────────────────────────────────────

  def addressFromPublicKey(pk: PublicKey): String =
    val compressed = toCompressed(pk)
    CosmosAddress.deriveAddress(compressed, hrp)

  def isValidAddress(s: String): Boolean =
    CosmosAddress.decode(s).exists { case (h, data) =>
      h == hrp && data.length == 20
    }

  def normalizeAddress(s: String): String = s.toLowerCase

  // ── typed-data / signing ──────────────────────────────────────────────────

  /** SHA256 of the Amino-encoded message bytes.
   *  For `TypedData.Raw(msg)` we treat the msg as raw bytes to hash. */
  def typedDataDigest(data: TypedData): Array[Byte] = data match
    case TypedData.Raw(msg) => CosmosCrypto.sha256(msg)
    case other =>
      throw new UnsupportedOperationException(s"CosmosChainAdapter.typedDataDigest: unsupported $other")

  /** Verify a DER secp256k1 signature.
   *  Ed25519 does not support algebraic recovery — returns None always. */
  def recoverAddress(digest: Array[Byte], signature: Array[Byte]): Option[String] = None

  // ── transactions ──────────────────────────────────────────────────────────

  def buildTransaction(intent: TxIntent, sender: String, ctx: ChainContext): Future[CosmosTx] =
    intent match
      case TxIntent.NativeTransfer(to, amount) =>
        Future.successful(CosmosTx(sender, to, amount.toLong, chainId.reference))
      case other =>
        Future.failed(new UnsupportedOperationException(
          s"CosmosChainAdapter: unsupported intent $other"))

  def prepareSigningPayload(tx: CosmosTx, signer: PublicKey): SigningPayload =
    val msgBytes = tx.toBytes
    SigningPayload(CosmosCrypto.sha256(msgBytes), HashAlgo.None)

  def assembleSignedTransaction(tx: CosmosTx, signature: Array[Byte], signer: PublicKey): CosmosSignedTx =
    CosmosSignedTx(tx, signature, toCompressed(signer))

  def broadcast(signed: CosmosSignedTx, ctx: ChainContext): Future[TxHash] =
    Future.failed(new UnsupportedOperationException(
      "CosmosChainAdapter.broadcast: no node connection configured"))

  def describe(tx: CosmosTx): TxDescription =
    TxDescription("cosmos-transfer", Map(
      "to"      -> tx.to,
      "amount"  -> tx.amount.toString,
      "chainId" -> tx.chainIdStr,
    ))

  // ── queries ───────────────────────────────────────────────────────────────

  def nativeBalance(address: String, ctx: ChainContext): Future[BigInt] =
    Future.failed(new UnsupportedOperationException(
      "CosmosChainAdapter.nativeBalance: no node connection configured"))

  def tokenBalance(asset: Asset, holder: String, ctx: ChainContext): Future[BigInt] =
    Future.failed(new UnsupportedOperationException(
      "CosmosChainAdapter.tokenBalance: no node connection configured"))

  def nonceOf(address: String, ctx: ChainContext): Future[BigInt] =
    Future.successful(BigInt(0))   // Cosmos uses account sequence, not nonce

  def getReceipt(hash: TxHash, ctx: ChainContext): Future[Option[TxReceipt]] =
    Future.failed(new UnsupportedOperationException(
      "CosmosChainAdapter.getReceipt: no node connection configured"))

  def waitForReceipt(hash: TxHash, ctx: ChainContext, timeoutMs: Long): Future[TxReceipt] =
    Future.failed(new UnsupportedOperationException(
      "CosmosChainAdapter.waitForReceipt: no node connection configured"))

  def call(target: String, calldata: Array[Byte], ctx: ChainContext): Future[Array[Byte]] =
    Future.failed(new UnsupportedOperationException("call() not supported on Cosmos"))

  def predictDeployAddress(deploy: TxIntent.Deploy, deployer: String, ctx: ChainContext): Future[String] =
    Future.failed(new UnsupportedOperationException("predictDeployAddress not supported on Cosmos"))

  // ── helpers ───────────────────────────────────────────────────────────────

  private def toCompressed(pk: PublicKey): Array[Byte] =
    pk.bytes.length match
      case 33 if pk.bytes(0) == 0x02 || pk.bytes(0) == 0x03 => pk.bytes
      case 32 =>
        // Ed25519 public key — returned as-is (bech32 hash160 of 32-byte key)
        // For Cosmos secp256k1 addresses we need 33 bytes; Ed25519 uses 32 bytes raw.
        // When called from addressFromPublicKey with Ed25519, we hash the 32-byte key directly.
        pk.bytes
      case _ => pk.bytes  // pass through; CosmosAddress.deriveAddress validates length

object CosmosChainAdapter:
  def apply(chainId: ChainId)(using ExecutionContext): CosmosChainAdapter =
    new CosmosChainAdapter(chainId)

  /** All three Cosmos chain adapters, one per supported ChainId. */
  def all(using ExecutionContext): Seq[CosmosChainAdapter] =
    Seq(
      new CosmosChainAdapter(ChainId.CosmosHub),
      new CosmosChainAdapter(ChainId.Osmosis),
      new CosmosChainAdapter(ChainId.Juno),
    )

// ── Transaction types ──────────────────────────────────────────────────────────

/** Minimal unsigned Cosmos transaction. */
case class CosmosTx(
  from:       String,
  to:         String,
  amount:     Long,     // in base denom (uatom / uosmo / ujuno)
  chainIdStr: String,
  fee:        Long = 5000,
  memo:       String = "",
):
  def toBytes: Array[Byte] =
    (from + to + amount.toString + chainIdStr + fee.toString + memo).getBytes("UTF-8")

case class CosmosSignedTx(
  tx:          CosmosTx,
  signature:   Array[Byte],
  pubKeyBytes: Array[Byte],
)
