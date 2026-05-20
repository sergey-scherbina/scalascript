package scalascript.micropayment.probabilistic

import scalascript.micropayment.spi.PaymentReceipt
import scala.concurrent.{ExecutionContext, Future}

case class WinEntry(receipt: PaymentReceipt, preimage: Array[Byte])

trait WinningTicketStore:
  def add(entry: WinEntry)(using ec: ExecutionContext): Future[Unit]
  def drain(maxCount: Int)(using ec: ExecutionContext): Future[Seq[WinEntry]]
  def pendingCount: Future[Int]
  def pendingValue: Future[BigInt]

object WinningTicketStore:
  def inMemory(): WinningTicketStore = new InMemoryWinningTicketStore

private class InMemoryWinningTicketStore extends WinningTicketStore:
  private val store = scala.collection.mutable.ArrayBuffer.empty[WinEntry]
  private val lock  = new Object

  def add(entry: WinEntry)(using ec: ExecutionContext): Future[Unit] =
    Future { lock.synchronized { store += entry; () } }

  def drain(maxCount: Int)(using ec: ExecutionContext): Future[Seq[WinEntry]] =
    Future {
      lock.synchronized {
        val n     = math.min(maxCount, store.size)
        val taken = store.take(n).toSeq
        store.remove(0, n)
        taken
      }
    }

  def pendingCount: Future[Int]   = Future.successful(lock.synchronized(store.size))
  def pendingValue: Future[BigInt] =
    Future.successful(lock.synchronized(store.map(_.receipt.amount).foldLeft(BigInt(0))(_ + _)))
