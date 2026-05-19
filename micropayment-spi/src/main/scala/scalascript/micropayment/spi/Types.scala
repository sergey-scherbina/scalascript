package scalascript.micropayment.spi

import scalascript.blockchain.spi.{ChainId, Asset}
import scala.concurrent.duration.Duration

type ChannelId = String

case class ChannelConfig(
  chain:            ChainId,
  asset:            Asset,
  payee:            String,
  initialDeposit:   BigInt,
  settlementPolicy: SettlementPolicy,
  timeout:          Duration,
)

case class ChannelState(
  channelId:    ChannelId,
  sequence:     Long,
  offChainPaid: BigInt,
  onChainPaid:  BigInt,
  openSince:    java.time.Instant,
  lastActivity: Option[java.time.Instant],
)

case class PaymentReceipt(
  channelId:  ChannelId,
  sequence:   Long,
  amount:     BigInt,
  cumulative: BigInt,
  payerSig:   Array[Byte],
  timestamp:  Long,
)

enum SettlementResult:
  case Ok(txHash: String, settled: BigInt)
  case Partial(txHash: String, settled: BigInt, remaining: BigInt)
  case Fail(reason: String)
