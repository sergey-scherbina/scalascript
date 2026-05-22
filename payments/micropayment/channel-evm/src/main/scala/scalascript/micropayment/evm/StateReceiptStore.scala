package scalascript.micropayment.evm

import scalascript.micropayment.spi.{ChannelId, PaymentReceipt}
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.concurrent.TrieMap

/** Stores the latest valid receipt per channel for the payee to use during settlement. */
trait StateReceiptStore:
  def upsert(receipt: PaymentReceipt)(using ec: ExecutionContext): Future[Unit]
  def latest(channelId: ChannelId)(using ec: ExecutionContext): Future[Option[PaymentReceipt]]
  def remove(channelId: ChannelId)(using ec: ExecutionContext): Future[Unit]

object StateReceiptStore:
  def inMemory(): StateReceiptStore = new InMemoryStateReceiptStore

private class InMemoryStateReceiptStore extends StateReceiptStore:
  private val store = TrieMap.empty[ChannelId, PaymentReceipt]

  def upsert(r: PaymentReceipt)(using ec: ExecutionContext): Future[Unit] =
    store.update(r.channelId, r)
    Future.unit

  def latest(channelId: ChannelId)(using ec: ExecutionContext): Future[Option[PaymentReceipt]] =
    Future.successful(store.get(channelId))

  def remove(channelId: ChannelId)(using ec: ExecutionContext): Future[Unit] =
    store.remove(channelId)
    Future.unit
