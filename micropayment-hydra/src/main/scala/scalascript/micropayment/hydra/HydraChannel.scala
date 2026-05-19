package scalascript.micropayment.hydra

import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.Asset
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.collection.concurrent.TrieMap
import java.time.Instant
import java.util.UUID

/** Off-chain payment channel backed by a Cardano Hydra head.
 *
 *  Both payer and payee connect to their own Hydra nodes (or a shared one in tests).
 *  The payer calls `pay()` which submits a NewTx and resolves when the head confirms
 *  TxValid.  The payee calls `receive()` which waits for the same TxValid event.
 *
 *  Settlement is cooperative: `settle()` sends Close → waits HeadIsClosed → Fanout →
 *  waits HeadIsFinalized → SettlementResult.Ok.
 *
 *  `PaymentReceipt.payerSig` carries the UTF-8 bytes of the Hydra txId (not a
 *  cryptographic signature — the Hydra head itself guarantees finality). */
class HydraChannel(
  val channelId:       ChannelId,
  val headId:          String,
  val payerAddress:    String,
  val payeeAddress:    String,
  val assetInfo:       Asset,
  val openedAt:        Instant,
  node:                HydraNodeClient,
  settlementPolicy:    SettlementPolicy,
)(using ec: ExecutionContext) extends MicropaymentChannel:

  @volatile private var _seq:          Long             = 0L
  @volatile private var _offChain:     BigInt           = BigInt(0)
  @volatile private var _onChain:      BigInt           = BigInt(0)
  @volatile private var _lastActivity: Option[Instant]  = None

  private val pendingTx:      TrieMap[String, Promise[Unit]] = TrieMap.empty
  private val confirmedTxIds: TrieMap[String, Unit]          = TrieMap.empty
  private val closedPromise:  Promise[Unit]                  = Promise()
  private val finalPromise:   Promise[Unit]                  = Promise()

  node.subscribe {
    case HydraServerMsg.TxValid(_, txId) =>
      confirmedTxIds.put(txId, ())
      pendingTx.remove(txId).foreach(_.trySuccess(()))
    case HydraServerMsg.TxInvalid(_, txId, reason) =>
      pendingTx.remove(txId).foreach(_.tryFailure(RuntimeException(s"TxInvalid: $reason")))
    case HydraServerMsg.HeadIsClosed(_, _) =>
      closedPromise.trySuccess(())
    case HydraServerMsg.HeadIsFinalized(_) =>
      finalPromise.trySuccess(())
    case _ => ()
  }

  def state: ChannelState =
    ChannelState(channelId, _seq, _offChain, _onChain, openedAt, _lastActivity)

  def availableBalance: Future[BigInt] = Future.successful(_offChain)

  // ── Payer side ──────────────────────────────────────────────────────────────

  def pay(amount: BigInt, memo: String = ""): Future[PaymentReceipt] =
    val seq   = _seq + 1
    val cum   = _offChain + amount
    val txId  = UUID.randomUUID().toString
    val p     = Promise[Unit]()
    pendingTx.put(txId, p)
    val txCbor = HydraChannel.encodeTxCbor(txId, amount, payerAddress, payeeAddress)
    node.send(HydraClientMsg.NewTx(txCbor)).flatMap { _ =>
      p.future.map { _ =>
        _seq          = seq
        _offChain     = cum
        _lastActivity = Some(Instant.now())
        PaymentReceipt(channelId, seq, amount, cum, txId.getBytes("UTF-8"), System.currentTimeMillis())
      }
    }

  // ── Payee side ──────────────────────────────────────────────────────────────

  def receive(receipt: PaymentReceipt): Future[Unit] =
    val txId = new String(receipt.payerSig, "UTF-8")
    val waitConfirmed: Future[Unit] =
      if confirmedTxIds.contains(txId) then Future.unit
      else
        val p = Promise[Unit]()
        pendingTx.put(txId, p)
        p.future
    waitConfirmed.map { _ =>
      _seq          = receipt.sequence
      _offChain    += receipt.amount
      _lastActivity = Some(Instant.now())
    }.flatMap { _ =>
      val s = state
      if settlementPolicy.shouldSettle(s) then settle().map(_ => ()) else Future.unit
    }

  // ── Settlement ───────────────────────────────────────────────────────────────

  def settle(): Future[SettlementResult] =
    val settled = _offChain
    node.send(HydraClientMsg.Close).flatMap { _ =>
      closedPromise.future.flatMap { _ =>
        node.send(HydraClientMsg.Fanout).flatMap { _ =>
          finalPromise.future.map { _ =>
            _onChain  += settled
            _offChain  = BigInt(0)
            node.disconnect()
            SettlementResult.Ok(headId, settled)
          }
        }
      }
    }.recover { case ex => SettlementResult.Fail(ex.getMessage) }

  def close(): Future[SettlementResult] = settle()

object HydraChannel:
  /** Stub CBOR-hex encoding for testing.
   *  Production code would build a real Cardano transaction here. */
  private[hydra] def encodeTxCbor(txId: String, amount: BigInt, from: String, to: String): String =
    val bytes = s"$txId:$amount:$from:$to".getBytes("UTF-8")
    bytes.map(b => f"${b & 0xff}%02x").mkString
