package scalascript.micropayment.threshold

import scalascript.micropayment.spi.{ChannelId, PaymentReceipt}
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

trait ReceiptStore:
  def upsert(receipt: PaymentReceipt): Future[Unit]
  def latest(channelId: ChannelId): Future[Option[PaymentReceipt]]
  def remove(channelId: ChannelId): Future[Unit]

object ReceiptStore:
  def inMemory(): ReceiptStore = new InMemoryReceiptStore

private class InMemoryReceiptStore extends ReceiptStore:
  private val store = TrieMap.empty[ChannelId, PaymentReceipt]

  def upsert(receipt: PaymentReceipt): Future[Unit] =
    store.update(receipt.channelId, receipt)
    Future.unit

  def latest(channelId: ChannelId): Future[Option[PaymentReceipt]] =
    Future.successful(store.get(channelId))

  def remove(channelId: ChannelId): Future[Unit] =
    store.remove(channelId)
    Future.unit
