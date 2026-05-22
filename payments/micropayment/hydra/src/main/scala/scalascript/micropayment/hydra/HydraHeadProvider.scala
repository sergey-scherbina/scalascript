package scalascript.micropayment.hydra

import scalascript.micropayment.spi.*
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import java.util.UUID

/** Opens a `HydraChannel` against a running Hydra node.
 *
 *  @param headId        Hydra head identifier (returned by HeadIsInitializing / HeadIsOpen events)
 *  @param payerAddress  Cardano address of the payer party
 *  @param payeeAddress  Cardano address of the payee party
 *  @param node          Connected Hydra node WebSocket client */
class HydraHeadProvider(
  headId:       String,
  payerAddress: String,
  payeeAddress: String,
  node:         HydraNodeClient,
) extends ChannelProvider:

  def kind: ChannelKind = ChannelKind.HydraHead

  def open(config: ChannelConfig)(using ec: ExecutionContext): Future[MicropaymentChannel] =
    Future.successful(
      new HydraChannel(
        channelId        = UUID.randomUUID().toString,
        headId           = headId,
        payerAddress     = payerAddress,
        payeeAddress     = payeeAddress,
        assetInfo        = config.asset,
        openedAt         = Instant.now(),
        node             = node,
        settlementPolicy = config.settlementPolicy,
      )
    )

  def restore(channelId: ChannelId)(using ec: ExecutionContext): Future[Option[MicropaymentChannel]] =
    Future.successful(None)

  def listOpen()(using ec: ExecutionContext): Future[Seq[ChannelState]] =
    Future.successful(Seq.empty)

object HydraHeadProvider:
  /** Connect to a live Hydra node and create a provider for the given head. */
  def connect(wsUrl: String, headId: String, payerAddress: String, payeeAddress: String)(
    using ec: ExecutionContext
  ): Future[HydraHeadProvider] =
    HydraNodeClient.connect(wsUrl).map { node =>
      new HydraHeadProvider(headId, payerAddress, payeeAddress, node)
    }

  /** In-process stub for testing; inject Hydra messages via the returned stub node. */
  def stub(headId: String, payerAddress: String, payeeAddress: String): (HydraHeadProvider, StubHydraNodeClient) =
    val node = HydraNodeClient.stub()
    (new HydraHeadProvider(headId, payerAddress, payeeAddress, node), node)
