package scalascript.wallet.vault.ledger.jvm

import org.hid4java.{HidDevice, HidManager, HidServices, HidServicesSpecification}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scalascript.wallet.vault.ledger.LedgerTransport

/** [[LedgerTransport]] backed by hid4java. Designed for the JVM:
 *  CLI tools, server-side automation, desktop wallets.
 *
 *  Selection: by default the transport claims the first attached
 *  Ledger device on the USB bus (`vendorId == 0x2C97`). Callers
 *  with multiple devices attached should pass an explicit
 *  `serialNumber` to disambiguate.
 *
 *  This class is *not* exercised by automated tests because tests
 *  cannot enumerate a real device. The framing logic it relies on
 *  ([[HidFraming]]) is unit-tested separately against the wire
 *  format — that's where the protocol risk lives. Integration with
 *  hid4java is exercised manually in dev environments. */
class Hid4JavaTransport(
  serialNumber: Option[String] = None,
  // For tests that want to inject a pre-built HidServices.
  hidServicesOpt: Option[HidServices] = None,
)(using ec: ExecutionContext) extends LedgerTransport:

  import Hid4JavaTransport.*

  @volatile private var services: HidServices = null
  @volatile private var device:   HidDevice   = null
  @volatile private var open_ :   Boolean     = false

  def isOpen: Boolean = open_

  def open(): Future[Unit] = Future {
    if open_ then ()
    else
      val svc = hidServicesOpt.getOrElse {
        val spec = new HidServicesSpecification()
        spec.setAutoStart(true)
        HidManager.getHidServices(spec)
      }
      services = svc
      val candidates = svc.getAttachedHidDevices.asScala.filter { d =>
        (d.getVendorId & 0xffff) == LedgerVendorId
      }.toSeq
      val picked = serialNumber match
        case Some(sn) => candidates.find(_.getSerialNumber == sn)
        case None     => candidates.headOption
      device = picked.getOrElse {
        throw new java.io.IOException(
          s"No Ledger device found on USB bus (vendorId=0x${"%04X".format(LedgerVendorId)}" +
          serialNumber.fold("")(sn => s", serial=$sn") + ")"
        )
      }
      if !device.isOpen then
        if !device.open() then
          throw new java.io.IOException("hid4java refused to open the Ledger device")
      open_ = true
  }

  def close(): Future[Unit] = Future {
    if open_ then
      try { if device != null && device.isOpen then device.close() }
      finally
        try { if services != null then services.shutdown() }
        finally { open_ = false }
  }

  def exchange(apdu: Array[Byte]): Future[Array[Byte]] = Future {
    if !open_ then throw new IllegalStateException("Hid4JavaTransport is not open")
    val frames = HidFraming.encode(apdu)
    frames.foreach { f =>
      // hid4java's `write(byte[],int,byte)` expects: data, packetLen, reportId.
      // Ledger uses reportId=0; data is the 64-byte frame.
      val n = device.write(f, f.length, 0.toByte)
      if n < 0 then throw new java.io.IOException(s"hid4java write failed: $n")
    }
    // Read frames until we have the full APDU response. The first
    // frame carries the total length; subsequent frames complete it.
    val buf = scala.collection.mutable.ArrayBuffer.empty[Array[Byte]]
    val tmp = new Array[Byte](HidFraming.FrameSize)
    var total = -1
    var collected = 0
    while total < 0 || collected < total do
      val n = device.read(tmp, ReadTimeoutMs)
      if n <= 0 then throw new java.io.IOException(s"hid4java read returned $n")
      // hid4java's `read` returns bytes read, but the buffer holds
      // a 64-byte HID frame; we always take the whole frame.
      val frame = java.util.Arrays.copyOf(tmp, HidFraming.FrameSize)
      buf += frame
      if total < 0 then
        total = ((frame(HidFraming.HeaderSize) & 0xff) << 8) |
                 (frame(HidFraming.HeaderSize + 1) & 0xff)
        collected += math.min(HidFraming.FirstFramePayloadSize, total)
      else
        collected += math.min(HidFraming.ContFramePayloadSize, total - collected)
    HidFraming.decode(buf.toSeq)
  }

object Hid4JavaTransport:
  /** Ledger USB vendor id (Nano S / Nano X / Nano S Plus / Stax all share). */
  val LedgerVendorId: Int = 0x2C97

  /** Read timeout in milliseconds. 30 s gives generous headroom for
   *  user interaction on device (PIN entry, signature confirmation). */
  val ReadTimeoutMs: Int = 30_000
