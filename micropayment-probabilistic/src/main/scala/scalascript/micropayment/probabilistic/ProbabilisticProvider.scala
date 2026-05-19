package scalascript.micropayment.probabilistic

import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.{ChainAdapter, ChainContext}
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

class ProbabilisticProvider(
  chain:           ChainAdapter,
  ctx:             ChainContext,
  maxPayout:       BigInt,
  redeemBatchSize: Int          = 50,
  serverKey:       Array[Byte]  = ProbabilisticProvider.randomKey(),
  winStore:        WinningTicketStore = WinningTicketStore.inMemory(),
) extends ChannelProvider:

  def kind: ChannelKind = ChannelKind.Probabilistic

  def open(config: ChannelConfig)(using ec: ExecutionContext): Future[MicropaymentChannel] =
    Future.successful(mkChannel(UUID.randomUUID().toString, config))

  def restore(channelId: ChannelId)(using ec: ExecutionContext): Future[Option[MicropaymentChannel]] =
    Future.successful(None)

  def listOpen()(using ec: ExecutionContext): Future[Seq[ChannelState]] =
    Future.successful(Seq.empty)

  private[probabilistic] def openWith(
    channelId: ChannelId,
    config:    ChannelConfig,
  )(using ec: ExecutionContext): MicropaymentChannel =
    mkChannel(channelId, config)

  private def mkChannel(id: ChannelId, config: ChannelConfig)(using ec: ExecutionContext): ProbabilisticChannel =
    new ProbabilisticChannel(
      channelId        = id,
      payerAddress     = config.payee,   // placeholder; payer address not required for lottery
      payeeAddress     = config.payee,
      assetInfo        = config.asset,
      openedAt         = Instant.now(),
      expiryMillis     = Instant.now().toEpochMilli + config.timeout.toMillis,
      chain            = chain,
      ctx              = ctx,
      maxPayout        = maxPayout,
      redeemBatchSize  = redeemBatchSize,
      serverKey        = serverKey,
      winStore         = winStore,
      settlementPolicy = config.settlementPolicy,
    )

object ProbabilisticProvider:
  def randomKey(): Array[Byte] =
    val k = new Array[Byte](32)
    ThreadLocalRandom.current().nextBytes(k)
    k

  def apply(
    chain:           ChainAdapter,
    ctx:             ChainContext,
    maxPayout:       BigInt,
    redeemBatchSize: Int         = 50,
  ): ProbabilisticProvider =
    new ProbabilisticProvider(chain, ctx, maxPayout, redeemBatchSize)
