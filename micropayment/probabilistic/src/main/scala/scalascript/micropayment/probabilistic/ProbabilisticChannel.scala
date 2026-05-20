package scalascript.micropayment.probabilistic

import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.{ChainAdapter, Asset, ChainContext}
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

// ── ProbabilisticChannel ──────────────────────────────────────────────────────
//
// PaymentReceipt.payerSig = 32-byte random preimage (not a crypto signature).
// Win condition is checked server-side using a deterministic HMAC-based salt.
// Settlement (Phase 3): accumulates won amounts in memory; on-chain redemption
// via TxIntent.TokenTransfer is deferred to Phase 4 once a batch authorization
// scheme is specified.

class ProbabilisticChannel(
  val channelId:     ChannelId,
  val payerAddress:  String,
  val payeeAddress:  String,
  val assetInfo:     Asset,
  val openedAt:      Instant,
  val expiryMillis:  Long,          // unix millis
  chain:             ChainAdapter,
  ctx:               ChainContext,
  maxPayout:         BigInt,
  redeemBatchSize:   Int,
  serverKey:         Array[Byte],   // HMAC key for deterministic salt generation
  winStore:          WinningTicketStore,
  settlementPolicy:  SettlementPolicy,
)(using ec: ExecutionContext) extends MicropaymentChannel:

  @volatile private var _seq:          Long   = 0L
  @volatile private var _winPending:   BigInt = BigInt(0)  // wins not yet redeemed
  @volatile private var _redeemed:     BigInt = BigInt(0)  // redeemed on-chain
  @volatile private var _lastActivity: Option[Instant] = None

  def state: ChannelState =
    ChannelState(channelId, _seq, _winPending, _redeemed, openedAt, _lastActivity)

  def availableBalance: Future[BigInt] =
    chain.tokenBalance(assetInfo, payerAddress, ctx)

  // ── Payer side ──────────────────────────────────────────────────────────────

  // payerSig carries the raw preimage; the server derives the salt and evaluates
  // the win condition without a second round-trip.
  def pay(amount: BigInt, memo: String = ""): Future[PaymentReceipt] =
    require(amount > 0 && amount <= maxPayout, s"amount must be in (0, $maxPayout]")
    val preimage = new Array[Byte](32)
    ThreadLocalRandom.current().nextBytes(preimage)
    val seq     = _seq + 1
    val receipt = PaymentReceipt(channelId, seq, amount, BigInt(0), preimage, System.currentTimeMillis())
    _seq          = seq
    _lastActivity = Some(Instant.now())
    Future.successful(receipt)

  // ── Payee side ──────────────────────────────────────────────────────────────

  def receive(receipt: PaymentReceipt): Future[Unit] =
    if receipt.sequence <= _seq then
      Future.failed(RuntimeException(s"Replay: seq ${receipt.sequence} <= ${_seq}"))
    else if receipt.payerSig.length != 32 then
      Future.failed(RuntimeException("Invalid ticket: preimage must be 32 bytes"))
    else
      val preimage   = receipt.payerSig
      val commitment = LotteryMath.commitment(preimage)
      val salt       = LotteryMath.serverSalt(serverKey, commitment)
      val won        = LotteryMath.isWinner(preimage, salt, receipt.amount, maxPayout)
      _seq          = receipt.sequence
      _lastActivity = Some(Instant.now())
      val storeF = if won then
        _winPending += receipt.amount
        winStore.add(WinEntry(receipt, preimage))
      else
        Future.unit
      storeF.flatMap { _ =>
        val s = state
        if settlementPolicy.shouldSettle(s) then settle().map(_ => ()) else Future.unit
      }

  // ── Settlement ───────────────────────────────────────────────────────────────
  //
  // Phase 3: accumulates won amounts and returns Ok without submitting an
  // on-chain transaction. Full on-chain batch redemption (transferFrom or
  // per-ticket TokenTransferAuthorized) is deferred to Phase 4.

  def settle(): Future[SettlementResult] =
    winStore.drain(redeemBatchSize).map { entries =>
      if entries.isEmpty then
        SettlementResult.Fail("No winning tickets to settle")
      else
        val amount   = entries.map(_.receipt.amount).foldLeft(BigInt(0))(_ + _)
        _redeemed   += amount
        _winPending -= amount
        SettlementResult.Ok("pending", amount)
    }

  def close(): Future[SettlementResult] = settle()
