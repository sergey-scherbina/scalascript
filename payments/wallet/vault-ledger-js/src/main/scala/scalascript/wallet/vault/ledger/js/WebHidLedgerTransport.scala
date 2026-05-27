package scalascript.wallet.vault.ledger.js

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}
import org.scalajs.dom
import scalascript.wallet.vault.ledger.LedgerTransport

trait WebHidDevice:
  def open()(using ExecutionContext): Future[Unit]
  def close()(using ExecutionContext): Future[Unit]
  def sendReport(reportId: Int, data: Array[Byte])(using ExecutionContext): Future[Unit]
  def receiveReport()(using ExecutionContext): Future[Array[Byte]]
  def isOpen: Boolean

final class WebHidLedgerTransport(device: WebHidDevice)(using ec: ExecutionContext) extends LedgerTransport:
  def open(): Future[Unit] = device.open()
  def close(): Future[Unit] = device.close()
  def isOpen: Boolean = device.isOpen

  def exchange(apdu: Array[Byte]): Future[Array[Byte]] =
    if !device.isOpen then Future.failed(new IllegalStateException("WebHID Ledger transport is not open"))
    else
      val frames = WebHidFraming.encode(apdu)
      val sent = frames.foldLeft(Future.successful(())) { (acc, frame) =>
        acc.flatMap(_ => device.sendReport(0, frame))
      }
      sent.flatMap { _ =>
        device.receiveReport().flatMap { first =>
          val expected = WebHidFraming.expectedFrameCount(first)
          def loop(n: Int, acc: Vector[Array[Byte]]): Future[Vector[Array[Byte]]] =
            if n <= 0 then Future.successful(acc)
            else device.receiveReport().flatMap(next => loop(n - 1, acc :+ next))
          loop(expected - 1, Vector(first)).map(WebHidFraming.decode)
        }
      }

object WebHidLedgerTransport:
  def requestLedger(filters: js.Array[js.Dynamic] = defaultFilters)(using ExecutionContext): Future[WebHidLedgerTransport] =
    BrowserWebHidDevice.request(filters).map(device => WebHidLedgerTransport(device))

  val defaultFilters: js.Array[js.Dynamic] =
    js.Array(js.Dynamic.literal(vendorId = 0x2c97))

final class BrowserWebHidDevice private (raw: BrowserWebHidDevice.HidDevice) extends WebHidDevice:
  import BrowserWebHidDevice.*
  private val pending = mutable.Queue.empty[Promise[Array[Byte]]]
  private val queued = mutable.Queue.empty[Array[Byte]]

  raw.oninputreport = (event: InputReportEvent) =>
    val bytes = BrowserWebHidDevice.toArray(event.data.buffer)
    if pending.nonEmpty then pending.dequeue().success(bytes)
    else queued.enqueue(bytes)

  def open()(using ExecutionContext): Future[Unit] =
    if isOpen then Future.successful(())
    else raw.open().toFuture.map(_ => ())

  def close()(using ExecutionContext): Future[Unit] =
    if !isOpen then Future.successful(())
    else raw.close().toFuture.map(_ => ())

  def sendReport(reportId: Int, data: Array[Byte])(using ExecutionContext): Future[Unit] =
    raw.sendReport(reportId, BrowserWebHidDevice.toUint8Array(data)).toFuture.map(_ => ())

  def receiveReport()(using ExecutionContext): Future[Array[Byte]] =
    if queued.nonEmpty then Future.successful(queued.dequeue())
    else
      val p = Promise[Array[Byte]]()
      pending.enqueue(p)
      p.future

  def isOpen: Boolean = raw.opened

object BrowserWebHidDevice:
  trait HidDevice extends js.Object:
    def opened: Boolean
    var oninputreport: js.Function1[InputReportEvent, Unit]
    def open(): js.Promise[Unit]
    def close(): js.Promise[Unit]
    def sendReport(reportId: Int, data: Uint8Array): js.Promise[Unit]

  trait InputReportEvent extends js.Object:
    def data: DataView

  trait DataView extends js.Object:
    def buffer: ArrayBuffer

  def request(filters: js.Array[js.Dynamic])(using ExecutionContext): Future[BrowserWebHidDevice] =
    val hid = dom.window.navigator.asInstanceOf[js.Dynamic].hid
    if js.isUndefined(hid) || hid == null then
      Future.failed(new UnsupportedOperationException("WebHID is not available in this browser"))
    else
      val options = js.Dynamic.literal(filters = filters)
      hid.requestDevice(options).asInstanceOf[js.Promise[js.Array[HidDevice]]].toFuture.map { devices =>
        if devices.isEmpty then throw new java.util.NoSuchElementException("No Ledger HID device selected")
        BrowserWebHidDevice(devices(0))
      }

  def toUint8Array(bytes: Array[Byte]): Uint8Array =
    val out = new Uint8Array(bytes.length)
    var i = 0
    while i < bytes.length do
      out(i) = (bytes(i) & 0xff).toShort
      i += 1
    out

  def toArray(buffer: ArrayBuffer): Array[Byte] =
    val view = new Uint8Array(buffer)
    val out = new Array[Byte](view.length)
    var i = 0
    while i < out.length do
      out(i) = view(i).toByte
      i += 1
    out
