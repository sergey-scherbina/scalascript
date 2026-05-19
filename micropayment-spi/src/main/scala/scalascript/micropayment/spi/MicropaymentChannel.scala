package scalascript.micropayment.spi

import scala.concurrent.Future

trait MicropaymentChannel:
  def channelId: ChannelId
  def state: ChannelState
  def availableBalance: Future[BigInt]

  def pay(amount: BigInt, memo: String = ""): Future[PaymentReceipt]
  def receive(receipt: PaymentReceipt): Future[Unit]
  def settle(): Future[SettlementResult]
  def close(): Future[SettlementResult]
