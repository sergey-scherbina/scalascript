package scalascript.wallet.vault.ledger

import scala.concurrent.Future

/** Test double for [[LedgerTransport]]: records every exchanged APDU
 *  and dispenses canned responses in FIFO order.
 *
 *  - `recorded` — every APDU sent to the transport, in order.
 *  - `responses` — queue of canned responses. Each `exchange` pops
 *    one. If the queue is empty, the call fails the Future.
 *
 *  Used by every wallet-vault-ledger* test; no real Ledger device
 *  is ever touched. */
class MockTransport(initialResponses: Array[Byte]*) extends LedgerTransport:
  private val recordedBuf = scala.collection.mutable.ArrayBuffer.empty[Array[Byte]]
  private val responseQ   = scala.collection.mutable.Queue.from(initialResponses)
  @volatile private var open_ = false

  def recorded: Vector[Array[Byte]] = recordedBuf.toVector

  def queueResponse(bytes: Array[Byte]): Unit = responseQ.enqueue(bytes)
  def queueOk(payload: Array[Byte] = Array.emptyByteArray): Unit =
    val resp = new Array[Byte](payload.length + 2)
    System.arraycopy(payload, 0, resp, 0, payload.length)
    resp(payload.length)     = 0x90.toByte
    resp(payload.length + 1) = 0x00
    responseQ.enqueue(resp)
  def queueStatus(sw: Int, payload: Array[Byte] = Array.emptyByteArray): Unit =
    val resp = new Array[Byte](payload.length + 2)
    System.arraycopy(payload, 0, resp, 0, payload.length)
    resp(payload.length)     = ((sw >> 8) & 0xff).toByte
    resp(payload.length + 1) = ( sw       & 0xff).toByte
    responseQ.enqueue(resp)

  def open(): Future[Unit]  = { open_ = true;  Future.successful(()) }
  def close(): Future[Unit] = { open_ = false; Future.successful(()) }
  def isOpen: Boolean       = open_

  def exchange(apdu: Array[Byte]): Future[Array[Byte]] =
    recordedBuf += apdu.clone()
    if responseQ.isEmpty then
      Future.failed(new IllegalStateException(
        s"MockTransport: no canned response for APDU #${recordedBuf.size}"))
    else
      Future.successful(responseQ.dequeue())
