package scalascript.micropayment.threshold

import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.ChainAdapter
import scalascript.wallet.spi.AccountStrategy
import scalascript.x402.{Facilitator, NonceStore}
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import java.util.UUID

class ThresholdBatchingProvider(
  chain:        ChainAdapter,
  strategy:     AccountStrategy,
  facilitator:  Facilitator,
  nonceStore:   NonceStore,
  receiptStore: ReceiptStore,
) extends ChannelProvider:

  def kind: ChannelKind = ChannelKind.ThresholdBatching

  def open(config: ChannelConfig)(using ec: ExecutionContext): Future[MicropaymentChannel] =
    strategy.getAddress(chain).map { payer =>
      mkChannel(UUID.randomUUID().toString, payer, config)
    }

  def restore(channelId: ChannelId)(using ec: ExecutionContext): Future[Option[MicropaymentChannel]] =
    Future.successful(None)  // Phase 2: no persistent state across restarts

  def listOpen()(using ec: ExecutionContext): Future[Seq[ChannelState]] =
    Future.successful(Seq.empty)

  private[threshold] def openFor(
    channelId: ChannelId,
    payer:     String,
    config:    ChannelConfig,
  )(using ec: ExecutionContext): MicropaymentChannel =
    mkChannel(channelId, payer, config)

  private def mkChannel(id: ChannelId, payer: String, config: ChannelConfig)(using ec: ExecutionContext): ThresholdChannel =
    val expiry = Instant.now().toEpochMilli / 1000 + config.timeout.toSeconds
    new ThresholdChannel(
      channelId        = id,
      payerAddress     = payer,
      payeeAddress     = config.payee,
      assetInfo        = config.asset,
      openedAt         = Instant.now(),
      expiry           = expiry,
      chain            = chain,
      strategy         = strategy,
      facilitator      = facilitator,
      nonceStore       = nonceStore,
      receiptStore     = receiptStore,
      settlementPolicy = config.settlementPolicy,
    )

object ThresholdBatchingProvider:
  def apply(
    chain:        ChainAdapter,
    strategy:     AccountStrategy,
    facilitator:  Facilitator,
    nonceStore:   NonceStore    = NonceStore.inMemory(),
    receiptStore: ReceiptStore  = ReceiptStore.inMemory(),
  ): ThresholdBatchingProvider =
    new ThresholdBatchingProvider(chain, strategy, facilitator, nonceStore, receiptStore)
