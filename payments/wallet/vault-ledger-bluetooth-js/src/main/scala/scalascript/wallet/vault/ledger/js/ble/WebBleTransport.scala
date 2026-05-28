package scalascript.wallet.vault.ledger.js.ble

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.typedarray.{DataView, Uint8Array}
import scalascript.wallet.vault.ledger.LedgerTransport

/** Ledger BLE service and characteristic UUIDs (Nano X / Stax). */
object LedgerBleUuids:
  val Service  = "13d63400-2c97-0004-0000-4c6564676572"
  val Write    = "13d63400-2c97-0004-0001-4c6564676572"
  val Notify   = "13d63400-2c97-0004-0002-4c6564676572"

/** Abstraction over a BLE GATT peripheral for testability. */
trait WebBleDevice:
  def connect()(using ExecutionContext): Future[Unit]
  def disconnect()(using ExecutionContext): Future[Unit]
  def isConnected: Boolean
  def mtu: Int
  /** Write `data` to the device (host → device, write characteristic). */
  def write(data: Array[Byte])(using ExecutionContext): Future[Unit]
  /** Await the next notification from the device (device → host). */
  def receive()(using ExecutionContext): Future[Array[Byte]]

/** `LedgerTransport` backed by a Web Bluetooth GATT connection.
 *
 *  Exchange flow per APDU:
 *  1. Encode APDU into BLE frames via [[BleFraming]].
 *  2. Write each frame to the Write characteristic (host → device).
 *  3. Receive notification frames from the Notify characteristic.
 *  4. Decode the reassembled response. */
final class WebBleTransport(
  device:  WebBleDevice,
  framing: BleFraming = BleFraming.default,
)(using ec: ExecutionContext) extends LedgerTransport:

  def open(): Future[Unit]  = device.connect()
  def close(): Future[Unit] = device.disconnect()
  def isOpen: Boolean       = device.isConnected

  def exchange(apdu: Array[Byte]): Future[Array[Byte]] =
    if !device.isConnected then
      Future.failed(new IllegalStateException("WebBLE Ledger transport is not open"))
    else
      val frames = framing.encode(apdu)
      val sent = frames.foldLeft(Future.successful(())) { (acc, frame) =>
        acc.flatMap(_ => device.write(frame))
      }
      sent.flatMap { _ =>
        device.receive().flatMap { first =>
          val expected = framing.expectedFrameCount(first)
          def loop(n: Int, acc: Vector[Array[Byte]]): Future[Vector[Array[Byte]]] =
            if n <= 0 then Future.successful(acc)
            else device.receive().flatMap(next => loop(n - 1, acc :+ next))
          loop(expected - 1, Vector(first)).map(framing.decode)
        }
      }

object WebBleTransport:
  def requestLedger(mtu: Int = BleFraming.DefaultMtu)(using ExecutionContext): Future[WebBleTransport] =
    BrowserBluetoothDevice.request(mtu).map(dev => WebBleTransport(dev, BleFraming(mtu)))

/** Live implementation of [[WebBleDevice]] using the browser Web Bluetooth API. */
final class BrowserBluetoothDevice private (
  writeChar:  BrowserBluetoothDevice.GattCharacteristic,
  notifyChar: BrowserBluetoothDevice.GattCharacteristic,
  server:     BrowserBluetoothDevice.GattServer,
  override val mtu: Int,
) extends WebBleDevice:
  import BrowserBluetoothDevice.*

  private val pending = mutable.Queue.empty[Promise[Array[Byte]]]
  private val queued  = mutable.Queue.empty[Array[Byte]]

  notifyChar.oncharacteristicvaluechanged = (_: js.Any) =>
    val dv    = notifyChar.value
    val bytes = dvToArray(dv)
    if pending.nonEmpty then pending.dequeue().success(bytes)
    else queued.enqueue(bytes)

  def isConnected: Boolean = server.connected

  def connect()(using ExecutionContext): Future[Unit] =
    if isConnected then Future.successful(())
    else server.connect().toFuture.map(_ => ())

  def disconnect()(using ExecutionContext): Future[Unit] =
    if !isConnected then Future.successful(())
    else Future.successful(server.disconnect())

  def write(data: Array[Byte])(using ExecutionContext): Future[Unit] =
    writeChar.writeValueWithoutResponse(arrayToDv(data)).toFuture.map(_ => ())

  def receive()(using ExecutionContext): Future[Array[Byte]] =
    if queued.nonEmpty then Future.successful(queued.dequeue())
    else
      val p = Promise[Array[Byte]]()
      pending.enqueue(p)
      p.future

object BrowserBluetoothDevice:
  trait GattServer extends js.Object:
    def connected: Boolean
    def connect(): js.Promise[GattServer]
    def disconnect(): Unit
    def getPrimaryService(uuid: String): js.Promise[GattService]

  trait GattService extends js.Object:
    def getCharacteristic(uuid: String): js.Promise[GattCharacteristic]

  trait GattCharacteristic extends js.Object:
    var oncharacteristicvaluechanged: js.Function1[js.Any, Unit]
    def value: DataView
    def writeValueWithoutResponse(data: DataView): js.Promise[Unit]
    def startNotifications(): js.Promise[GattCharacteristic]

  trait BluetoothDevice extends js.Object:
    def gatt: GattServer

  def request(mtu: Int)(using ExecutionContext): Future[BrowserBluetoothDevice] =
    val bluetooth = js.Dynamic.global.navigator.bluetooth
    if js.isUndefined(bluetooth) || bluetooth == null then
      Future.failed(new UnsupportedOperationException("Web Bluetooth is not available"))
    else
      val opts = js.Dynamic.literal(
        filters = js.Array(js.Dynamic.literal(services = js.Array(LedgerBleUuids.Service)))
      )
      bluetooth.requestDevice(opts).asInstanceOf[js.Promise[BluetoothDevice]].toFuture.flatMap { dev =>
        dev.gatt.connect().toFuture.flatMap { server =>
          server.getPrimaryService(LedgerBleUuids.Service).toFuture.flatMap { svc =>
            for
              wc <- svc.getCharacteristic(LedgerBleUuids.Write).toFuture
              nc <- svc.getCharacteristic(LedgerBleUuids.Notify).toFuture
              _  <- nc.startNotifications().toFuture
            yield BrowserBluetoothDevice(wc, nc, server, mtu)
          }
        }
      }

  def arrayToDv(bytes: Array[Byte]): DataView =
    import scala.scalajs.js.typedarray.ArrayBuffer
    val buf  = new ArrayBuffer(bytes.length)
    val view = new Uint8Array(buf)
    var i = 0
    while i < bytes.length do
      view(i) = (bytes(i) & 0xff).toShort
      i += 1
    new DataView(buf)

  def dvToArray(dv: DataView): Array[Byte] =
    val view = new Uint8Array(dv.buffer)
    val out  = new Array[Byte](view.length)
    var i = 0
    while i < out.length do
      out(i) = view(i).toByte
      i += 1
    out
