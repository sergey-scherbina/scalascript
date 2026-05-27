package scalascript.wallet.ledger

import scala.concurrent.{ExecutionContext, Future}

/** APDU builder/decoder helpers — JS-side mirror of the shared
 *  `scalascript.wallet.vault.ledger.Apdu` object.
 *
 *  This file is intentionally kept as a thin re-export / alias layer so
 *  that the Scala.js-only code in this package can use the same shared
 *  type without crossing the module boundary every time.
 *
 *  The shared `Apdu` object already does the heavy lifting; here we
 *  provide:
 *   - A `case class Apdu` value type to assemble before encoding
 *   - Convenience re-exports / aliases for the status word constants
 *   - JS-compatible typed wrappers for chunked send */

import scalascript.wallet.vault.ledger.{Apdu => SharedApdu, LedgerTransport}

/** Value type for a single APDU command before it is wire-encoded. */
final case class Apdu(
  cla:  Int,
  ins:  Int,
  p1:   Int,
  p2:   Int,
  data: Array[Byte],
)

object Apdu:
  /** Encode an [[Apdu]] value into the 5+N byte wire format:
   *  `[cla][ins][p1][p2][len][data…]`. */
  def encode(apdu: Apdu): Array[Byte] =
    SharedApdu.command(apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.data)

  /** Parse a raw response into `(data, statusWord)`.
   *  The last two bytes are the status word; the rest is data. */
  def decodeResponse(bytes: Array[Byte]): (Array[Byte], Int) =
    val (sw, payload) = SharedApdu.parseResponse(bytes)
    (payload, sw)

  /** Successful status word. */
  val SW_OK: Int = SharedApdu.Sw_Ok   // 0x9000

  /** User declined on device. */
  val SW_UserDeclined: Int = SharedApdu.Sw_UserDeclined  // 0x6985

  /** Wrong Lc / invalid length. */
  val SW_InvalidLength: Int = 0x6700

  /** App not found / wrong app open. */
  val SW_AppNotFound: Int = SharedApdu.Sw_AppNotFound  // 0x6A82

  /** Unknown INS — wrong app open. */
  val SW_UnknownIns: Int = SharedApdu.Sw_UnknownIns  // 0x6D00

  /** Unknown CLA — wrong app open. */
  val SW_UnknownCla: Int = SharedApdu.Sw_UnknownCla  // 0x6E00

  /** Render a status word as a hex string like "0x9000". */
  def swHex(sw: Int): String = SharedApdu.swHex(sw)

  /** Send a possibly-chunked payload via the shared `chunkedSend`. */
  def chunkedSend(
    transport:  LedgerTransport,
    cla:        Int,
    ins:        Int,
    p1First:    Int,
    p1Continue: Int,
    p2:         Int,
    payload:    Array[Byte],
    chunkSize:  Int = 255,
  )(using ec: ExecutionContext): Future[(Int, Array[Byte])] =
    SharedApdu.chunkedSend(transport, cla, ins, p1First, p1Continue, p2, payload, chunkSize)
