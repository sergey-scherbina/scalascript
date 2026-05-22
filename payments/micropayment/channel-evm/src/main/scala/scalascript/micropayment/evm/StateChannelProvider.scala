package scalascript.micropayment.evm

import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.{ChainAdapter, ChainContext, TxIntent}
import scalascript.wallet.spi.AccountStrategy
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import java.time.Instant
import java.util.UUID

/** Deploys a fresh `PaymentChannel` contract per `open()` call and returns
 *  a `StateChannel` backed by it.
 *
 *  @param chain             EVM chain adapter (blockchain-evm Phase 2+)
 *  @param strategy          Account strategy for signing deployment tx and settlement txs
 *  @param ctx               Chain context (RPC + clock)
 *  @param disputeWindow     How long after submitFinalState the payer can challenge
 *  @param receiptStore      Where to persist the latest valid receipt
 *  @param contractAddress   Optional pre-deployed contract address; if Some, open() skips
 *                           deployment and opens a channel against the existing contract. */
class StateChannelProvider(
  chain:           ChainAdapter,
  strategy:        AccountStrategy,
  ctx:             ChainContext,
  disputeWindow:   Duration,
  receiptStore:    StateReceiptStore           = StateReceiptStore.inMemory(),
  contractAddress: Option[String]              = None,
) extends ChannelProvider:

  def kind: ChannelKind = ChannelKind.StateChannel

  def open(config: ChannelConfig)(using ec: ExecutionContext): Future[MicropaymentChannel] =
    contractAddress match
      case Some(addr) =>
        strategy.getAddress(chain).map { payer =>
          mkChannel(UUID.randomUUID().toString, addr, payer, config)
        }
      case None =>
        deployAndOpen(config)

  def restore(channelId: ChannelId)(using ec: ExecutionContext): Future[Option[MicropaymentChannel]] =
    Future.successful(None)

  def listOpen()(using ec: ExecutionContext): Future[Seq[ChannelState]] =
    Future.successful(Seq.empty)

  private def deployAndOpen(config: ChannelConfig)(using ec: ExecutionContext): Future[MicropaymentChannel] =
    strategy.getAddress(chain).flatMap { payer =>
      val expiryTime  = BigInt(ctx.nowSeconds + config.timeout.toSeconds)
      val dispWindow  = BigInt(disputeWindow.toSeconds)
      val ctorArgs    = PaymentChannelAbi.constructorArgs(
        payee             = config.payee,
        token             = config.asset.address,
        deposit           = config.initialDeposit,
        expiryTime        = expiryTime,
        disputeWindowSecs = dispWindow,
      )
      val deployIntent = TxIntent.Deploy(PaymentChannelBytecode.bytes, ctorArgs)
      chain.predictDeployAddress(deployIntent, payer, ctx).flatMap { contractAddr =>
        chain.buildTransaction(deployIntent, payer, ctx).flatMap { tx =>
          strategy.signTransaction(chain)(tx).flatMap { signed =>
            chain.broadcast(signed, ctx).map { _ =>
              mkChannel(UUID.randomUUID().toString, contractAddr, payer, config)
            }
          }
        }
      }
    }

  private def mkChannel(
    id:           ChannelId,
    contractAddr: String,
    payer:        String,
    config:       ChannelConfig,
  )(using ec: ExecutionContext): StateChannel =
    new StateChannel(
      channelId         = id,
      contractAddress   = contractAddr,
      payerAddress      = payer,
      payeeAddress      = config.payee,
      assetInfo         = config.asset,
      openedAt          = Instant.now(),
      disputeWindowSecs = disputeWindow.toSeconds,
      chain             = chain,
      strategy          = strategy,
      ctx               = ctx,
      receiptStore      = receiptStore,
      settlementPolicy  = config.settlementPolicy,
    )

object StateChannelProvider:
  def apply(
    chain:         ChainAdapter,
    strategy:      AccountStrategy,
    ctx:           ChainContext,
    disputeWindow: Duration,
  ): StateChannelProvider =
    new StateChannelProvider(chain, strategy, ctx, disputeWindow)
