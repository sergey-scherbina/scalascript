package scalascript.micropayment.evm

import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.{ChainAdapter, Asset, ChainContext, TypedData, TxIntent}
import scalascript.wallet.spi.AccountStrategy
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

// ── StateChannel ──────────────────────────────────────────────────────────────
//
// Each instance is either a payer-side or payee-side view of the same channel.
// The same `contractAddress` and `receiptStore` are shared between the two sides
// (the store is where the payee persists the latest valid receipt).
//
// Settlement path:
//   payee.settle()          → submitFinalState on-chain → dispute window begins
//   payee.finalise()        → after dispute window, releases funds
//   payer.challenge(receipt)→ if payee submitted a stale state, payer wins
//   either.cooperativeClose()→ instant release, no dispute window
//
// PaymentReceipt.payerSig = 65-byte personal_sign signature over stateHash.

class StateChannel(
  val channelId:         ChannelId,
  val contractAddress:   String,
  val payerAddress:      String,
  val payeeAddress:      String,
  val assetInfo:         Asset,
  val openedAt:          Instant,
  val disputeWindowSecs: Long,
  chain:                 ChainAdapter,
  strategy:              AccountStrategy,
  ctx:                   ChainContext,
  receiptStore:          StateReceiptStore,
  settlementPolicy:      SettlementPolicy,
)(using ec: ExecutionContext) extends MicropaymentChannel:

  @volatile private var _seq:          Long   = 0L
  @volatile private var _offChain:     BigInt = BigInt(0)
  @volatile private var _onChain:      BigInt = BigInt(0)
  @volatile private var _lastActivity: Option[Instant] = None

  def state: ChannelState =
    ChannelState(channelId, _seq, _offChain, _onChain, openedAt, _lastActivity)

  def availableBalance: Future[BigInt] =
    chain.tokenBalance(assetInfo, payerAddress, ctx)

  // ── Payer side ──────────────────────────────────────────────────────────────

  def pay(amount: BigInt, memo: String = ""): Future[PaymentReceipt] =
    val seq  = _seq + 1
    val cum  = _offChain + amount
    val hash = PaymentChannelAbi.stateHash(contractAddress, seq, cum)
    strategy.signMessage(chain, hash).map { sig =>
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
      val hash     = PaymentChannelAbi.stateHash(contractAddress, receipt.sequence, receipt.cumulative)
      val prefixed = chain.typedDataDigest(TypedData.Raw(hash))
      chain.recoverAddress(prefixed, receipt.payerSig) match
        case None =>
          Future.failed(RuntimeException("Signature recovery failed"))
        case Some(signer) if !signer.equalsIgnoreCase(payerAddress) =>
          Future.failed(RuntimeException(s"Signer $signer != payer $payerAddress"))
        case Some(_) =>
          _seq          = receipt.sequence
          _offChain    += receipt.amount
          _lastActivity = Some(Instant.now())
          receiptStore.upsert(receipt).flatMap { _ =>
            val s = state
            if settlementPolicy.shouldSettle(s) then settle().map(_ => ()) else Future.unit
          }

  // ── Settlement ───────────────────────────────────────────────────────────────

  /** Payee submits final state on-chain, beginning the dispute window. */
  def settle(): Future[SettlementResult] =
    receiptStore.latest(channelId).flatMap {
      case None => Future.successful(SettlementResult.Fail("No receipt to settle"))
      case Some(r) =>
        val calldata = PaymentChannelAbi.submitFinalStateCalldata(r.sequence, r.cumulative, r.payerSig)
        val intent   = TxIntent.ContractCall(contractAddress, calldata)
        val settled  = _offChain
        chain.buildTransaction(intent, payeeAddress, ctx).flatMap { tx =>
          strategy.signTransaction(chain)(tx).flatMap { signed =>
            chain.broadcast(signed, ctx).map { hash =>
              _onChain  += settled
              _offChain  = BigInt(0)
              SettlementResult.Ok(hash.value, settled)
            }
          }
        }.recover { case ex => SettlementResult.Fail(ex.getMessage) }
    }

  /** After the dispute window has elapsed, release funds on-chain. */
  def finalise(): Future[SettlementResult] =
    val intent = TxIntent.ContractCall(contractAddress, PaymentChannelAbi.finaliseCalldata())
    chain.buildTransaction(intent, payeeAddress, ctx).flatMap { tx =>
      strategy.signTransaction(chain)(tx).flatMap { signed =>
        chain.broadcast(signed, ctx).map { hash =>
          SettlementResult.Ok(hash.value, _onChain)
        }
      }
    }.recover { case ex => SettlementResult.Fail(ex.getMessage) }

  /** Payer submits a receipt with higher sequence to invalidate a stale payee submission. */
  def challenge(receipt: PaymentReceipt): Future[SettlementResult] =
    val calldata = PaymentChannelAbi.challengeCalldata(receipt.sequence, receipt.cumulative, receipt.payerSig)
    val intent   = TxIntent.ContractCall(contractAddress, calldata)
    chain.buildTransaction(intent, payerAddress, ctx).flatMap { tx =>
      strategy.signTransaction(chain)(tx).flatMap { signed =>
        chain.broadcast(signed, ctx).map { hash =>
          SettlementResult.Ok(hash.value, receipt.cumulative)
        }
      }
    }.recover { case ex => SettlementResult.Fail(ex.getMessage) }

  /** Cooperative close: both parties sign the final cumulative — instant, no dispute window.
   *  Call on the payee side after obtaining both signatures. */
  def cooperativeClose(cumulative: BigInt, payerSig: Array[Byte], payeeSig: Array[Byte]): Future[SettlementResult] =
    val calldata = PaymentChannelAbi.cooperativeCloseCalldata(cumulative, payerSig, payeeSig)
    val intent   = TxIntent.ContractCall(contractAddress, calldata)
    chain.buildTransaction(intent, payeeAddress, ctx).flatMap { tx =>
      strategy.signTransaction(chain)(tx).flatMap { signed =>
        chain.broadcast(signed, ctx).map { hash =>
          _onChain  = cumulative
          _offChain = BigInt(0)
          SettlementResult.Ok(hash.value, cumulative)
        }
      }
    }.recover { case ex => SettlementResult.Fail(ex.getMessage) }

  /** Sign the cooperative-close message (call on each side; exchange sigs out of band). */
  def signCoopClose(cumulative: BigInt): Future[Array[Byte]] =
    strategy.signMessage(chain, PaymentChannelAbi.coopCloseHash(contractAddress, cumulative))

  def close(): Future[SettlementResult] = settle()
