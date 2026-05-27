package scalascript.wallet.ledger

import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.typedarray.{DataView, Uint8Array}

/** WebHID API Scala.js facades.
 *
 *  The W3C WebHID API is available in Chromium-based browsers (Chrome ≥ 89,
 *  Edge ≥ 89, Opera ≥ 75). `navigator.hid.requestDevice` prompts the user to
 *  select a device; `navigator.hid.getDevices` returns already-granted devices
 *  without a prompt. Both return a `Promise<HIDDevice[]>`.
 *
 *  Ledger devices all share vendor id `0x2C97`.
 *
 *  References:
 *   - https://developer.mozilla.org/en-US/docs/Web/API/WebHID_API
 *   - https://github.com/LedgerHQ/ledger-live/tree/develop/libs/ledgerjs */

/** A single HID device handle. */
trait HidDevice extends js.Object:
  val productName: String
  def open(): js.Promise[Unit]
  def close(): js.Promise[Unit]
  def sendReport(reportId: Int, data: Uint8Array): js.Promise[Unit]
  def addEventListener(event: String, handler: js.Function1[HidInputReportEvent, Unit]): Unit
  def removeEventListener(event: String, handler: js.Function1[HidInputReportEvent, Unit]): Unit

/** Event fired when the device sends an input report. */
trait HidInputReportEvent extends js.Object:
  val data:   DataView
  val device: HidDevice

/** Narrow type for the `filters` entry used in `requestDevice`. */
trait HidDeviceFilter extends js.Object

object HidDeviceFilter:
  def vendorId(id: Int): HidDeviceFilter =
    js.Dynamic.literal(vendorId = id).asInstanceOf[HidDeviceFilter]

/** `navigator.hid` — typed facade for the HID sub-navigator. */
trait HidNavigator extends js.Object:
  def requestDevice(options: js.Object): js.Promise[js.Array[HidDevice]]
  def getDevices(): js.Promise[js.Array[HidDevice]]

// ---------------------------------------------------------------------------
// APDU framing constants (mirrors HidFraming on the JVM side)
// ---------------------------------------------------------------------------

/** Ledger USB/WebHID frame constants.
 *
 *  Wire layout of each 64-byte frame:
 *  {{{
 *  +-------------+------+--------+------- payload --------+
 *  | CID  (2B BE)| 0x05 | seq(2B)| first-frame: len(2B) + up to 57 B
 *  |             |      |        | cont-frame:            up to 59 B
 *  +-------------+------+--------+------------------------+
 *  }}}
 *
 *  Trailing bytes of the last frame are zero-padded to `FrameSize`. */
object HidFraming:

  val ChannelId: Int      = 0x0101
  val CmdTag: Int         = 0x05
  val FrameSize: Int      = 64
  val HeaderSize: Int     = 5    // [CID(2)][tag(1)][seq(2)]
  val LengthPrefixSize: Int = 2  // first-frame carries 16-bit APDU length
  val FirstFramePayload: Int = FrameSize - HeaderSize - LengthPrefixSize  // 57
  val ContFramePayload: Int  = FrameSize - HeaderSize                      // 59

  /** Encode an APDU into one or more 64-byte frames. */
  def encode(apdu: Array[Byte]): Array[Array[Byte]] =
    require(apdu.length <= 0xFFFF,
      s"APDU too long for 16-bit length prefix: ${apdu.length}")
    val nFrames =
      if apdu.length <= FirstFramePayload then 1
      else 1 + math.ceil((apdu.length - FirstFramePayload).toDouble / ContFramePayload).toInt
    val frames = Array.ofDim[Array[Byte]](nFrames)
    var offset = 0
    var seq    = 0
    while seq < nFrames do
      val frame = new Array[Byte](FrameSize)
      frame(0) = ((ChannelId >>> 8) & 0xff).toByte
      frame(1) = ( ChannelId        & 0xff).toByte
      frame(2) = CmdTag.toByte
      frame(3) = ((seq      >>> 8) & 0xff).toByte
      frame(4) = ( seq             & 0xff).toByte
      val payloadStart = if seq == 0 then HeaderSize + LengthPrefixSize else HeaderSize
      if seq == 0 then
        frame(HeaderSize)     = ((apdu.length >>> 8) & 0xff).toByte
        frame(HeaderSize + 1) = ( apdu.length        & 0xff).toByte
      val capacity = FrameSize - payloadStart
      val n        = math.min(capacity, apdu.length - offset)
      var i = 0
      while i < n do
        frame(payloadStart + i) = apdu(offset + i)
        i += 1
      frames(seq) = frame
      offset += n
      seq    += 1
    frames

  /** Reassemble response frames into the APDU payload. */
  def decode(frames: scala.collection.immutable.IndexedSeq[Array[Byte]]): Array[Byte] =
    require(frames.nonEmpty, "decode: empty frame sequence")
    val first = frames(0)
    require(first.length == FrameSize, s"frame size != $FrameSize (got ${first.length})")
    checkHeader(first, expectedSeq = 0)
    val total = ((first(HeaderSize) & 0xff) << 8) | (first(HeaderSize + 1) & 0xff)
    val out   = new Array[Byte](total)
    val n0    = math.min(FirstFramePayload, total)
    var i = 0
    while i < n0 do
      out(i) = first(HeaderSize + LengthPrefixSize + i)
      i += 1
    var offset = n0
    var expSeq = 1
    while offset < total do
      require(expSeq < frames.length,
        s"decode: payload truncated after $offset / $total B (missing frame $expSeq)")
      val f = frames(expSeq)
      require(f.length == FrameSize, s"frame $expSeq size != $FrameSize (got ${f.length})")
      checkHeader(f, expSeq)
      val n = math.min(ContFramePayload, total - offset)
      var j = 0
      while j < n do
        out(offset + j) = f(HeaderSize + j)
        j += 1
      offset += n
      expSeq += 1
    out

  private def checkHeader(frame: Array[Byte], expectedSeq: Int): Unit =
    val cid = ((frame(0) & 0xff) << 8) | (frame(1) & 0xff)
    require(cid == ChannelId,
      s"Unexpected HID channel id: 0x${"%04X".format(cid)} (want 0x${"%04X".format(ChannelId)})")
    require((frame(2) & 0xff) == CmdTag,
      s"Unexpected HID cmd tag: 0x${"%02X".format(frame(2) & 0xff)} (want 0x05)")
    val seq = ((frame(3) & 0xff) << 8) | (frame(4) & 0xff)
    require(seq == expectedSeq,
      s"Out-of-order HID frame: got seq=$seq, expected $expectedSeq")

// ---------------------------------------------------------------------------
// WebHID transport implementation
// ---------------------------------------------------------------------------

/** Ledger APDU transport over WebHID.
 *
 *  - Uses `navigator.hid.requestDevice` on the first `open()` to prompt the
 *    user to select a Ledger device (vendor id `0x2C97`).
 *  - Sends each APDU as a sequence of 64-byte HID output reports (report id 0)
 *    using the Ledger packet framing in [[HidFraming]].
 *  - Collects input reports via the `inputreport` event until the full APDU
 *    response is reassembled.
 *
 *  Implements [[scalascript.wallet.vault.ledger.LedgerTransport]] semantics
 *  (same `open` / `close` / `exchange` contract) but expressed as a pure
 *  Scala.js class; no Scala trait dependency needed. */
class HidTransport(
  hidNav: HidNavigator,
) extends scalascript.wallet.vault.ledger.LedgerTransport:

  import scala.concurrent.ExecutionContext.Implicits.global

  private var device: HidDevice = null
  private var open_ : Boolean   = false

  // Currently-pending response accumulator; set while an exchange is in flight
  private var pendingPromise: Promise[Array[Byte]] = null
  private var responseFrames: scala.collection.mutable.ArrayBuffer[Array[Byte]] = null
  private var expectedTotal: Int = -1
  private var collectedBytes: Int = 0

  private val inputHandler: js.Function1[HidInputReportEvent, Unit] = ev =>
    if pendingPromise != null then
      val frame = dataViewToArray(ev.data)
      if responseFrames == null then
        responseFrames  = scala.collection.mutable.ArrayBuffer.empty
        expectedTotal   = -1
        collectedBytes  = 0
      responseFrames += frame
      if expectedTotal < 0 then
        // Parse length from first frame
        expectedTotal  = ((frame(HidFraming.HeaderSize) & 0xff) << 8) |
                          (frame(HidFraming.HeaderSize + 1) & 0xff)
        collectedBytes += math.min(HidFraming.FirstFramePayload, expectedTotal)
      else
        collectedBytes += math.min(HidFraming.ContFramePayload, expectedTotal - collectedBytes)
      if collectedBytes >= expectedTotal then
        val p = pendingPromise
        pendingPromise = null
        val captured = responseFrames.toIndexedSeq
        responseFrames = null
        expectedTotal  = -1
        collectedBytes = 0
        try
          p.success(HidFraming.decode(captured))
        catch
          case e: Exception => p.failure(e)

  def isOpen: Boolean = open_

  def open(): Future[Unit] =
    if open_ then return Future.successful(())
    val filters = js.Array(HidDeviceFilter.vendorId(HidTransport.LedgerVendorId))
    val options = js.Dynamic.literal(filters = filters)
    hidNav.requestDevice(options).toFuture.map { devices =>
      if devices.isEmpty then
        throw new java.io.IOException(
          s"No Ledger device selected (vendor 0x${"%04X".format(HidTransport.LedgerVendorId)})")
      device = devices(0)
      // open() returns a Promise; we chain it but can't easily nest here,
      // so we accept the fire-and-forget: if open fails the next sendReport
      // will surface the error.
      device.open()
      device.addEventListener("inputreport", inputHandler)
      open_ = true
    }

  /** Connect to a device already granted in a previous session without
   *  prompting the user (uses `getDevices` instead of `requestDevice`). */
  def openExisting(): Future[Unit] =
    if open_ then return Future.successful(())
    hidNav.getDevices().toFuture.map { devices =>
      val ledgers = devices.filter { d =>
        // vendorId is typically available as a property on real HIDDevice objects
        val vid = d.asInstanceOf[js.Dynamic].vendorId
        if js.isUndefined(vid) then false
        else (vid.asInstanceOf[Double].toInt & 0xffff) == HidTransport.LedgerVendorId
      }
      if ledgers.isEmpty then
        throw new java.io.IOException("No previously-granted Ledger device found")
      device = ledgers(0)
      device.open()
      device.addEventListener("inputreport", inputHandler)
      open_ = true
    }

  def close(): Future[Unit] =
    if !open_ then return Future.successful(())
    open_ = false
    if device != null then
      device.removeEventListener("inputreport", inputHandler)
      device.close().toFuture
    else
      Future.successful(())

  def exchange(apdu: Array[Byte]): Future[Array[Byte]] =
    if !open_ then
      return Future.failed(new IllegalStateException("HidTransport is not open"))
    if pendingPromise != null then
      return Future.failed(new IllegalStateException(
        "HidTransport: another exchange is already in progress"))
    val p = Promise[Array[Byte]]()
    pendingPromise = p
    responseFrames = scala.collection.mutable.ArrayBuffer.empty
    expectedTotal  = -1
    collectedBytes = 0
    val frames = HidFraming.encode(apdu)
    // Chain the sendReport calls sequentially
    var sendFuture: Future[Unit] = Future.successful(())
    for frame <- frames do
      sendFuture = sendFuture.flatMap { _ =>
        val uint8 = new Uint8Array(frame.length)
        var i = 0
        while i < frame.length do
          uint8(i) = frame(i).toShort
          i += 1
        device.sendReport(0, uint8).toFuture
      }
    sendFuture.flatMap(_ => p.future)

  private def dataViewToArray(dv: DataView): Array[Byte] =
    val bytes = new Array[Byte](dv.byteLength)
    var i = 0
    while i < dv.byteLength do
      bytes(i) = dv.getInt8(i)
      i += 1
    bytes

object HidTransport:
  val LedgerVendorId: Int  = 0x2C97
  val ReadTimeoutMs:  Int  = 30_000
