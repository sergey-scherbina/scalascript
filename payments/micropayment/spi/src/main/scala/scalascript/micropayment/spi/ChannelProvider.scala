package scalascript.micropayment.spi

import scala.concurrent.{Future, ExecutionContext}

enum ChannelKind:
  case ThresholdBatching
  case StateChannel
  case HydraHead
  case Probabilistic
  case L2Native
  case HashChain

trait ChannelProvider:
  def kind: ChannelKind
  def open(config: ChannelConfig)(using ec: ExecutionContext): Future[MicropaymentChannel]
  def restore(channelId: ChannelId)(using ec: ExecutionContext): Future[Option[MicropaymentChannel]]
  def listOpen()(using ec: ExecutionContext): Future[Seq[ChannelState]]
