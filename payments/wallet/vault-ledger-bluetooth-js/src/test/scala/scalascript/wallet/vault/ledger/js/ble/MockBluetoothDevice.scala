package scalascript.wallet.vault.ledger.js.ble

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/** In-memory mock of [[WebBleDevice]] for unit tests.
 *
 *  Pre-queue raw BLE frames with [[queueFrame]], or convenience-queue
 *  a full APDU (auto-framed) with [[queueApdu]].  Frames written by the
 *  transport under test are captured in [[writtenFrames]]. */
final class MockBluetoothDevice(
  override val mtu: Int = BleFraming.DefaultMtu,
  initialFrames: Array[Byte]*,
) extends WebBleDevice:
  private val framing = BleFraming(mtu)
  private val inbound  = mutable.Queue.from(initialFrames)
  val writtenFrames    = mutable.ArrayBuffer.empty[Array[Byte]]
  private var connectedFlag = false

  def queueFrame(frame: Array[Byte]): Unit = inbound.enqueue(frame)
  def queueApdu(apdu: Array[Byte]): Unit   = framing.encode(apdu).foreach(queueFrame)

  def connect()(using ExecutionContext): Future[Unit]    = { connectedFlag = true;  Future.successful(()) }
  def disconnect()(using ExecutionContext): Future[Unit] = { connectedFlag = false; Future.successful(()) }
  def isConnected: Boolean = connectedFlag

  def write(data: Array[Byte])(using ExecutionContext): Future[Unit] =
    writtenFrames += data.clone()
    Future.successful(())

  def receive()(using ExecutionContext): Future[Array[Byte]] =
    if inbound.nonEmpty then Future.successful(inbound.dequeue())
    else Future.failed(new IllegalStateException("no queued BLE frame"))
