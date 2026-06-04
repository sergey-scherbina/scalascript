package scalascript.wallet.vault.ledger

import scala.concurrent.Future

/** Wire-level transport to a Ledger device. Differs by host platform:
 *  HID via `hid4java` on the JVM, WebHID via `navigator.hid` in the
 *  browser, Bluetooth via WebBLE on mobile / Nano X. All variants
 *  expose the same APDU round-trip primitive.
 *
 *  An APDU on the wire is a single command frame —
 *  `cla | ins | p1 | p2 | lc | cdata` — and a single response frame —
 *  `payload | sw1 | sw2`. Higher-level chunking (some app commands
 *  send payloads larger than `lc`'s 255 byte limit by sending a
 *  sequence of APDUs with a "first / continue / last" flag in `p1`)
 *  lives in [[Apdu.chunkedSend]] one layer up; transports only see
 *  individual APDUs.
 *
 *  See docs/specs/wallet-spi.md §5.1 for the architecture rationale and
 *  the transport-variant table. */
trait LedgerTransport:
  /** Open the underlying connection (HID device, BLE peripheral, …).
   *  Idempotent: opening an already-open transport is a no-op. */
  def open(): Future[Unit]

  /** Close the connection. Idempotent. */
  def close(): Future[Unit]

  /** Send a fully-formed APDU command and return the response bytes
   *  (payload followed by the two status-word bytes `sw1 sw2`). */
  def exchange(apdu: Array[Byte]): Future[Array[Byte]]

  /** Whether the transport currently holds an open connection. */
  def isOpen: Boolean
