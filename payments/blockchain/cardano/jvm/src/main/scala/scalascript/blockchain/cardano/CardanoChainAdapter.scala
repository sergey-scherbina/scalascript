package scalascript.blockchain.cardano

import scala.concurrent.{ExecutionContext, Future, Promise}
import scalascript.blockchain.spi.*
import scalascript.blockfrost.{BlockfrostClient, BlockfrostConfig, Blockfrost}
import scalascript.crypto.{Curve, HashAlgo, PublicKey}

/** Cardano `ChainAdapter` backed by a Blockfrost node.
 *
 *  Supported operations:
 *  - Address derivation from Ed25519 public key (CIP-19 enterprise address)
 *  - CIP-8 `typedDataDigest` (builds the COSE Sig_Structure to be signed)
 *  - CIP-8 `recoverAddress` (extracts address from a COSE_Sign1 signature)
 *  - ADA + native asset balance queries
 *  - Transaction building (NativeTransfer, TokenTransfer) via Blockfrost UTxOs
 *  - Transaction broadcast via Blockfrost `/tx/submit`
 *  - Receipt polling
 *
 *  Ed25519 does NOT support key recovery — `recoverAddress` parses the address
 *  from the COSE_Sign1 protected header instead.
 *
 *  @param chainId  `ChainId.CardanoMainnet` or `ChainId.CardanoPreprod`
 *  @param client   Blockfrost HTTP client */
class CardanoChainAdapter(
  val chainId: ChainId,
  client:      BlockfrostClient,
)(using ec: ExecutionContext) extends ChainAdapter:

  private val testnet = chainId != ChainId.CardanoMainnet

  type Tx       = CardanoTx
  type SignedTx = CardanoSignedTx

  val supportedCurves: Seq[Curve]  = Seq(Curve.Ed25519)
  val defaultDerivationPath: String = "m/1852'/1815'/0'/0'/0'"

  // ── addresses ─────────────────────────────────────────────────────────────

  def addressFromPublicKey(pk: PublicKey): String =
    CardanoAddress.fromPublicKey(pk, testnet)

  def isValidAddress(s: String): Boolean = CardanoAddress.isValid(s)

  def normalizeAddress(s: String): String = CardanoAddress.normalize(s)

  // ── typed-data / CIP-8 ─────────────────────────────────────────────────────

  /** Returns the CIP-8 Sig_Structure bytes that Ed25519 signs.
   *
   *  For `TypedData.Raw(msg)` the protected header encodes `{1: -8, "address": addr}`.
   *  The `addr` bytes are extracted from the Raw prefix `"addr:<bech32>\0"` if present,
   *  otherwise an empty address is used (caller must embed the address in the payload
   *  or use a Raw payload with the address field). */
  def typedDataDigest(data: TypedData): Array[Byte] = data match
    case TypedData.Raw(msg) =>
      // Decode optional "addr:<bech32>\0" prefix injected by the signing path
      val (addrBytes, payload) = extractAddrPrefix(msg)
      val protHeader = CardanoCbor.cip8ProtectedHeader(addrBytes)
      CardanoCbor.cip8SigStructure(protHeader, payload)
    case other =>
      throw new UnsupportedOperationException(s"CardanoChainAdapter.typedDataDigest: unsupported $other")

  /** Recover the Cardano address from a CIP-8 COSE_Sign1 blob.
   *
   *  Ed25519 has no algebraic recovery — instead we parse the "address" field
   *  from the COSE protected header and return it as the bech32 address. */
  def recoverAddress(digest: Array[Byte], signature: Array[Byte]): Option[String] =
    try
      import CardanoCbor.*
      val coseVal = decode(signature) match
        case Tagged(18, inner) => inner
        case v                 => v
      val protBytes = coseVal match
        case Arr(items) if items.nonEmpty =>
          items(0) match { case Bytes(b) => b; case _ => return None }
        case _ => return None
      val protMap = decode(protBytes)
      val addrOpt = protMap match
        case Map(entries) =>
          entries.collectFirst { case (Text("address"), Bytes(b)) => b }
        case _ => None
      addrOpt.map(bytes => Bech32.encode(if testnet then "addr_test" else "addr", bytes))
    catch case _: Exception => None

  // ── balances ──────────────────────────────────────────────────────────────

  def nativeBalance(address: String, ctx: ChainContext): Future[BigInt] =
    client.getAddressInfo(address).map(_.lovelaceBalance)

  def tokenBalance(asset: Asset, holder: String, ctx: ChainContext): Future[BigInt] =
    client.getAddressInfo(holder).map { info =>
      val unit = asset.address // for Cardano assets, address = policyId+assetName hex
      info.assets.getOrElse(unit, BigInt(0))
    }

  // ── transactions ─────────────────────────────────────────────────────────

  def buildTransaction(intent: TxIntent, sender: String, ctx: ChainContext): Future[CardanoTx] =
    client.getAddressInfo(sender).flatMap { _ =>
      val (outputAddress, lovelace, assetUnit) = intent match
        case TxIntent.NativeTransfer(to, amount) =>
          (to, amount, None)
        case TxIntent.TokenTransfer(asset, to, amount) =>
          (to, BigInt(2_000_000), Some(asset.address -> amount))
        case other =>
          throw new UnsupportedOperationException(
            s"CardanoChainAdapter: unsupported intent $other")

      client.getUtxos(sender).map { bfUtxos =>
        val utxos     = bfUtxos.map(u => CardanoUtxo(u.txHash, u.index, u.lovelace, u.assets))
        val fee       = BigInt(180_000)    // conservative fixed fee ~0.18 ADA
        val needed    = lovelace + fee
        val selected  = selectUtxos(utxos, needed)
        val totalIn   = selected.map(_.lovelace).sum
        val change    = totalIn - needed
        CardanoTx(sender, outputAddress, lovelace, assetUnit, fee, change, selected)
      }
    }

  def prepareSigningPayload(tx: CardanoTx, pk: PublicKey): SigningPayload =
    SigningPayload(tx.txBodyHash, HashAlgo.None)

  def assembleSignedTransaction(tx: CardanoTx, sig: Array[Byte], pk: PublicKey): CardanoSignedTx =
    CardanoSignedTx(tx, sig, pk.bytes)

  def broadcast(signed: CardanoSignedTx, ctx: ChainContext): Future[TxHash] =
    val cbor = buildSignedTxCbor(signed)
    client.submitTx(cbor).map(TxHash(_))

  def describe(tx: CardanoTx): TxDescription =
    TxDescription("cardano-transfer", Map(
      "to"       -> tx.outputAddress,
      "lovelace" -> tx.lovelace.toString,
      "fee"      -> tx.fee.toString,
    ))

  def getReceipt(hash: TxHash, ctx: ChainContext): Future[Option[TxReceipt]] =
    client.isTxConfirmed(hash.value).map {
      case true  => Some(TxReceipt(hash, success = true, gasUsed = BigInt(0), blockNumber = 0L))
      case false => None
    }

  def waitForReceipt(hash: TxHash, ctx: ChainContext, timeoutMs: Long): Future[TxReceipt] =
    val deadline = System.currentTimeMillis() + timeoutMs
    def poll(): Future[TxReceipt] =
      getReceipt(hash, ctx).flatMap {
        case Some(r) => Future.successful(r)
        case None =>
          if System.currentTimeMillis() >= deadline then
            Future.failed(new java.util.concurrent.TimeoutException(s"Tx ${hash.value} not confirmed in ${timeoutMs}ms"))
          else
            val delay = Promise[Unit]()
            java.util.concurrent.Executors.newScheduledThreadPool(1)
              .schedule(() => delay.success(()), 2, java.util.concurrent.TimeUnit.SECONDS)
            delay.future.flatMap(_ => poll())
      }
    poll()

  def nonceOf(address: String, ctx: ChainContext): Future[BigInt] =
    Future.successful(BigInt(0))   // Cardano is UTxO-based; no account nonce

  def call(target: String, calldata: Array[Byte], ctx: ChainContext): Future[Array[Byte]] =
    Future.failed(new UnsupportedOperationException("call() not supported on Cardano"))

  def predictDeployAddress(deploy: TxIntent.Deploy, deployer: String, ctx: ChainContext): Future[String] =
    Future.failed(new UnsupportedOperationException("predictDeployAddress not supported on Cardano"))

  // ── internals ─────────────────────────────────────────────────────────────

  private def selectUtxos(utxos: Seq[CardanoUtxo], needed: BigInt): Seq[CardanoUtxo] =
    val sorted = utxos.sortBy(-_.lovelace)
    val buf    = scala.collection.mutable.ArrayBuffer.empty[CardanoUtxo]
    var total  = BigInt(0)
    for u <- sorted if total < needed do
      buf += u
      total += u.lovelace
    if total < needed then throw new IllegalStateException(
      s"Insufficient UTxOs: have $total lovelace, need $needed")
    buf.toSeq

  private def buildSignedTxCbor(signed: CardanoSignedTx): Array[Byte] =
    // Minimal witness-set CBOR: {0: [[pubkey, sig]]}
    val witnessSet = CardanoCbor.encode(CardanoCbor.Map(Seq(
      CardanoCbor.UInt(0) -> CardanoCbor.Arr(Seq(
        CardanoCbor.Arr(Seq(
          CardanoCbor.Bytes(signed.pubKeyBytes),
          CardanoCbor.Bytes(signed.signature),
        ))
      ))
    )))
    // Full Cardano tx: [tx_body_cbor, witness_set, true, null]
    CardanoCbor.encode(CardanoCbor.Arr(Seq(
      CardanoCbor.Bytes(signed.tx.txBodyCbor),
      CardanoCbor.Bytes(witnessSet),
      CardanoCbor.UInt(1),   // valid = true
      CardanoCbor.Map(Seq.empty),
    )))

  private def extractAddrPrefix(msg: Array[Byte]): (Array[Byte], Array[Byte]) =
    val s = new String(msg, "UTF-8")
    if s.startsWith("addr:") then
      val nl = s.indexOf(' ')
      if nl > 5 then
        val bech32 = s.substring(5, nl)
        val rest   = msg.drop(nl + 1)
        (CardanoAddress.toBytes(bech32), rest)
      else (Array.empty, msg)
    else (Array.empty, msg)

object CardanoChainAdapter:
  def apply(chainId: ChainId, client: BlockfrostClient)(using ExecutionContext): CardanoChainAdapter =
    new CardanoChainAdapter(chainId, client)

  def blockfrost(chainId: ChainId, config: BlockfrostConfig)(using ExecutionContext): CardanoChainAdapter =
    new CardanoChainAdapter(chainId, Blockfrost.connect(config))

// The portable transaction types (`CardanoTx`, `CardanoUtxo`, `CardanoSignedTx`,
// `CardanoTxBody`) live in the cross-compiled `shared/` source set — see CardanoTx.scala.
