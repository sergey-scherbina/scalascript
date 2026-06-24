package scalascript.micropayment.hashchain

import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.{Asset, ChainAdapter, ChainContext}
import scalascript.crypto.Ed25519
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

/** The signed channel-open commitment binding a hash chain to a `(payer, payee, unit value)`. The payer signs
 *  these bytes once at open; the payee — and an on-chain settlement contract — verify the chain is authorized
 *  before accepting any preimage payment. */
final case class HashChainCommitment(
    channelId: ChannelId,
    payee:     String,
    tip:       Array[Byte],
    length:    Int,
    unitValue: BigInt,
):
  /** Canonical bytes for signing / verification. */
  def bytes: Array[Byte] =
    (s"hashchain-v1\n$channelId\n$payee\n" +
      s"${java.util.Base64.getEncoder.encodeToString(tip)}\n$length\n${unitValue.toString}").getBytes("UTF-8")

object HashChainCommitment:
  /** Sign the commitment with the payer's 32-byte Ed25519 seed. */
  def sign(c: HashChainCommitment, payerSeed: Array[Byte]): Array[Byte] = Ed25519.sign(payerSeed, c.bytes)
  /** Verify the open signature against the payer's 32-byte Ed25519 public key. */
  def verify(c: HashChainCommitment, sig: Array[Byte], payerPubKey: Array[Byte]): Boolean =
    Ed25519.verify(payerPubKey, c.bytes, sig)

/** A PayWord hash-chain micropayment channel ([[ChannelKind.HashChain]]).
 *
 *  Off-chain flow: the payer commits the chain tip at open (a signed [[HashChainCommitment]]); each `pay` reveals
 *  the next-deeper preimage, which the payee verifies with one (or a few) cheap SHA-256 hashes — no per-payment
 *  signature. The deepest revealed preimage proves the cumulative amount, and `settle` redeems it.
 *
 *  One class drives both roles: a payer-side instance carries the secret `seed`; a payee-side instance carries
 *  only the public `tip` and tracks the deepest reveal it has verified. */
final class HashChainChannel(
    val channelId:    ChannelId,
    payeeAddress:     String,
    assetInfo:        Asset,
    openedAt:         Instant,
    chain:            ChainAdapter,
    ctx:              ChainContext,
    val unitValue:    BigInt,
    val length:       Int,
    val tip:          Array[Byte],
    settlementPolicy: SettlementPolicy,
    seed:             Option[Array[Byte]],          // payer-side only; payee = None
)(using ec: ExecutionContext) extends MicropaymentChannel:

  require(unitValue > 0, "unitValue must be > 0")
  require(length >= 1, "chain length must be >= 1")

  private var _paidUnits:     Int                 = 0   // payer
  private var _receivedUnits: Int                 = 0   // payee
  private var _settledUnits:  Int                 = 0
  private var _lastReveal:    Option[Array[Byte]] = None
  private var _lastActivity:  Option[Instant]     = None

  def state: ChannelState =
    val units = math.max(_paidUnits, _receivedUnits)
    ChannelState(
      channelId    = channelId,
      sequence     = units.toLong,
      offChainPaid = BigInt(units) * unitValue,
      onChainPaid  = BigInt(_settledUnits) * unitValue,
      openSince    = openedAt,
      lastActivity = _lastActivity,
    )

  def availableBalance: Future[BigInt] = chain.tokenBalance(assetInfo, payeeAddress, ctx)

  // ── Payer side ──────────────────────────────────────────────────────────────

  def pay(amount: BigInt, memo: String = ""): Future[PaymentReceipt] = seed match
    case None => Future.failed(new IllegalStateException("payee-side channel cannot pay (no seed)"))
    case Some(s) =>
      if amount <= 0 || amount % unitValue != 0 then
        Future.failed(new IllegalArgumentException(s"amount must be a positive multiple of unitValue ($unitValue)"))
      else
        val newUnits = _paidUnits + (amount / unitValue).toInt
        if newUnits > length then
          Future.failed(new IllegalStateException(s"hash chain exhausted: $newUnits > length $length"))
        else
          val reveal = HashChain.preimage(s, length, newUnits)
          _paidUnits    = newUnits
          _lastActivity = Some(Instant.now())
          Future.successful(PaymentReceipt(
            channelId  = channelId,
            sequence   = newUnits.toLong,
            amount     = amount,
            cumulative = BigInt(newUnits) * unitValue,
            payerSig   = reveal,                          // the revealed hash-chain preimage
            timestamp  = System.currentTimeMillis(),
          ))

  // ── Payee side ──────────────────────────────────────────────────────────────

  def receive(receipt: PaymentReceipt): Future[Unit] =
    val units = receipt.sequence.toInt
    if units <= _receivedUnits then
      Future.failed(new RuntimeException(s"Replay/stale receipt: $units <= ${_receivedUnits}"))
    else if receipt.cumulative != BigInt(units) * unitValue then
      Future.failed(new RuntimeException("cumulative does not equal sequence * unitValue"))
    else
      val valid = _lastReveal match
        case Some(prev) => HashChain.verifyStep(receipt.payerSig, units - _receivedUnits, prev)
        case None       => HashChain.verify(receipt.payerSig, units, tip)
      if !valid then
        Future.failed(new RuntimeException("Invalid preimage: does not chain to the committed tip"))
      else
        _receivedUnits = units
        _lastReveal    = Some(receipt.payerSig)
        _lastActivity  = Some(Instant.now())
        if settlementPolicy.shouldSettle(state) then settle().map(_ => ()) else Future.unit

  // ── Settlement ──────────────────────────────────────────────────────────────
  //
  // The deepest revealed preimage + the signed open commitment are the on-chain redemption proof: a settlement
  // contract pays `amount` to the payee on presentation (no replay — a deeper reveal supersedes). Here we record
  // the redemption and surface the proof (parity with the probabilistic provider's off-chain accounting; the
  // injected chain adapter is used for balance queries and a future on-chain claim).

  def settle(): Future[SettlementResult] =
    val owedUnits = _receivedUnits - _settledUnits
    if owedUnits <= 0 then Future.successful(SettlementResult.Fail("nothing to settle"))
    else
      _settledUnits = _receivedUnits
      val proof = _lastReveal.map(java.util.Base64.getEncoder.encodeToString).getOrElse("")
      Future.successful(SettlementResult.Ok(s"hashchain:$proof", BigInt(owedUnits) * unitValue))

  def close(): Future[SettlementResult] = settle()
