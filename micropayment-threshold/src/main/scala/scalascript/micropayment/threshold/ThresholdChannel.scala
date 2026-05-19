package scalascript.micropayment.threshold

import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.{ChainAdapter, Asset, ChainId, TypedData}
import scalascript.wallet.spi.AccountStrategy
import scalascript.x402.{Facilitator, NonceStore, Network, PaymentPayload, PaymentRequirements,
                          PaymentScheme, TransferAuthorization}
import scalascript.x402.{Asset => X402Asset}
import scala.concurrent.{ExecutionContext, Future}
import java.security.MessageDigest
import java.time.Instant

// ── ThresholdChannel ──────────────────────────────────────────────────────────

class ThresholdChannel(
  val channelId:    ChannelId,
  val payerAddress: String,
  val payeeAddress: String,
  val assetInfo:    Asset,
  val openedAt:     Instant,
  val expiry:       Long,           // unix seconds
  chain:            ChainAdapter,
  strategy:         AccountStrategy,
  facilitator:      Facilitator,
  nonceStore:       NonceStore,
  receiptStore:     ReceiptStore,
  settlementPolicy: SettlementPolicy,
)(using ec: ExecutionContext) extends MicropaymentChannel:

  @volatile private var _seq:          Long    = 0L
  @volatile private var _offChain:     BigInt  = BigInt(0)
  @volatile private var _onChain:      BigInt  = BigInt(0)
  @volatile private var _lastActivity: Option[Instant] = None

  def state: ChannelState =
    ChannelState(channelId, _seq, _offChain, _onChain, openedAt, _lastActivity)

  def availableBalance: Future[BigInt] =
    Future.successful(_onChain - _offChain - _onChain)

  // ── Payer side ──────────────────────────────────────────────────────────────

  def pay(amount: BigInt, memo: String = ""): Future[PaymentReceipt] =
    val seq = _seq + 1
    val cum = _offChain + amount
    val td  = typedData(seq, cum)
    strategy.signTypedData(chain, td).map { sig =>
      val receipt = PaymentReceipt(channelId, seq, amount, cum, sig, System.currentTimeMillis())
      _seq          = seq
      _offChain     = cum
      _lastActivity = Some(Instant.now())
      receipt
    }

  // ── Payee side ──────────────────────────────────────────────────────────────

  def receive(receipt: PaymentReceipt): Future[Unit] =
    if receipt.sequence <= _seq then
      Future.failed(RuntimeException(s"Replay: seq ${receipt.sequence} <= ${_seq}"))
    else
      val td     = typedData(receipt.sequence, receipt.cumulative)
      val digest = chain.typedDataDigest(td)
      chain.recoverAddress(digest, receipt.payerSig) match
        case None =>
          Future.failed(RuntimeException("Signature recovery failed"))
        case Some(signer) if !signer.equalsIgnoreCase(payerAddress) =>
          Future.failed(RuntimeException(s"Signer $signer != expected payer $payerAddress"))
        case Some(_) =>
          _seq          = receipt.sequence
          _offChain    += receipt.amount
          _lastActivity = Some(Instant.now())
          receiptStore.upsert(receipt).flatMap { _ =>
            val s = state
            if settlementPolicy.shouldSettle(s) then settle().map(_ => ()) else Future.unit
          }

  // ── Settlement ───────────────────────────────────────────────────────────────

  def settle(): Future[SettlementResult] =
    receiptStore.latest(channelId).flatMap {
      case None => Future.successful(SettlementResult.Fail("No receipt to settle"))
      case Some(r) =>
        val nonce   = receiptNonce(r.channelId, r.sequence)
        val payload = toPaymentPayload(r, nonce)
        val req     = toPaymentRequirements()
        nonceStore.claim(nonce, BigInt(expiry)).flatMap {
          case false => Future.successful(SettlementResult.Fail("Nonce already claimed"))
          case true  =>
            facilitator.settle(payload, req).map {
              case scalascript.x402.SettleResult.Ok(tx) =>
                val settled = _offChain
                _onChain   += settled
                _offChain   = BigInt(0)
                SettlementResult.Ok(tx, settled)
              case scalascript.x402.SettleResult.Fail(msg) =>
                SettlementResult.Fail(msg)
            }
        }
    }

  def close(): Future[SettlementResult] =
    settle().flatMap { r =>
      receiptStore.remove(channelId).map(_ => r)
    }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private def typedData(seq: Long, cumulative: BigInt): TypedData.Eip712 =
    val chainIdNum = chainIdToInt(assetInfo.chain)
    val nonce      = receiptNonce(channelId, seq)
    TypedData.Eip712(
      domain = Map(
        "name"              -> ujson.Str("USD Coin"),
        "version"           -> ujson.Str("2"),
        "chainId"           -> ujson.Num(chainIdNum.toDouble),
        "verifyingContract" -> ujson.Str(assetInfo.address),
      ),
      types = Map(
        "EIP712Domain" -> Seq(
          ("string",  "name"),
          ("string",  "version"),
          ("uint256", "chainId"),
          ("address", "verifyingContract"),
        ),
        "TransferWithAuthorization" -> Seq(
          ("address", "from"),
          ("address", "to"),
          ("uint256", "value"),
          ("uint256", "validAfter"),
          ("uint256", "validBefore"),
          ("bytes32", "nonce"),
        ),
      ),
      value = Map(
        "from"        -> ujson.Str(payerAddress),
        "to"          -> ujson.Str(payeeAddress),
        "value"       -> ujson.Str(cumulative.toString),
        "validAfter"  -> ujson.Str("0"),
        "validBefore" -> ujson.Str(expiry.toString),
        "nonce"       -> ujson.Str(nonce),
      ),
      primaryType = "TransferWithAuthorization",
    )

  private def toPaymentPayload(r: PaymentReceipt, nonce: String): PaymentPayload =
    PaymentPayload(
      scheme        = PaymentScheme.Exact(r.cumulative),
      network       = chainIdToNetwork(assetInfo.chain),
      authorization = TransferAuthorization(
        from        = payerAddress,
        to          = payeeAddress,
        value       = r.cumulative,
        validAfter  = BigInt(0),
        validBefore = BigInt(expiry),
        nonce       = nonce,
      ),
      signature = "0x" + r.payerSig.map(b => f"${b & 0xff}%02x").mkString,
    )

  private def toPaymentRequirements(): PaymentRequirements =
    PaymentRequirements(
      scheme      = PaymentScheme.Exact(_offChain),
      network     = chainIdToNetwork(assetInfo.chain),
      asset       = X402Asset(assetInfo.address, assetInfo.symbol, assetInfo.decimals, chainIdToNetwork(assetInfo.chain)),
      payTo       = payeeAddress,
      resource    = s"channel:$channelId",
      description = "ThresholdBatching settlement",
    )

private[threshold] def receiptNonce(channelId: ChannelId, seq: Long): String =
  val md    = MessageDigest.getInstance("SHA-256")
  val input = s"$channelId:$seq".getBytes("UTF-8")
  val hash  = md.digest(input)
  "0x" + hash.map(b => f"${b & 0xff}%02x").mkString

private[threshold] def chainIdToInt(id: ChainId): Long =
  id.reference.toLongOption.getOrElse(
    throw RuntimeException(s"Cannot convert ChainId to int: $id")
  )

private[threshold] def chainIdToNetwork(id: ChainId): Network = id match
  case ChainId("eip155:8453")   => Network.Base
  case ChainId("eip155:84532")  => Network.BaseSepolia
  case ChainId("eip155:1")      => Network.EthereumMainnet
  case ChainId("eip155:137")    => Network.Polygon
  case ChainId("eip155:42161")  => Network.Arbitrum
  case ChainId("eip155:10")     => Network.Optimism
  case other                    => throw RuntimeException(s"Unsupported chain for x402: $other")
