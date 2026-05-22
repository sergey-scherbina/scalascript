package scalascript.wallet.vault.ledger

import scala.concurrent.{ExecutionContext, Future}

/** APDU (Application Protocol Data Unit) encoding helpers for the
 *  Ledger T=0 short-format messages. Layout on the wire (command):
 *
 *  {{{
 *  +-----+-----+----+----+----+----------------+
 *  | CLA | INS | P1 | P2 | Lc | Data (Lc B)   |
 *  +-----+-----+----+----+----+----------------+
 *  }}}
 *
 *  And response: `payload | sw1 | sw2` (status word is the last 2 B).
 *
 *  `Lc` is a single byte; payloads larger than 255 B must be chunked
 *  by the *app* (not the transport) into a sequence of APDUs with a
 *  flag in `p1` distinguishing first / continuation / last frames.
 *  See [[chunkedSend]].
 *
 *  Status-word reference (ISO 7816 + Ledger-specific extensions):
 *   - 0x9000 — success
 *   - 0x6982 — security status not satisfied (locked device)
 *   - 0x6985 — user declined on device
 *   - 0x6A82 — application not found / app not open
 *   - 0x6B00 — invalid p1 / p2
 *   - 0x6D00 — unknown INS (often: wrong app open)
 *   - 0x6E00 — unknown CLA (wrong app open) */
object Apdu:

  /** Successful status word. */
  val Sw_Ok: Int = 0x9000

  /** Device returned to dashboard, or app not open. */
  val Sw_AppNotFound: Int = 0x6A82

  /** Wrong app open: most apps reject unknown INS / CLA with one of
   *  these — surface as an `AppSwitchRequired` upstream. */
  val Sw_UnknownIns: Int = 0x6D00
  val Sw_UnknownCla: Int = 0x6E00

  /** User declined the operation on-device. */
  val Sw_UserDeclined: Int = 0x6985

  /** Build a single-frame APDU command. `data` length must fit in one
   *  byte (`Lc`); larger payloads need [[chunkedSend]]. */
  def command(cla: Int, ins: Int, p1: Int, p2: Int, data: Array[Byte]): Array[Byte] =
    require(data.length <= 255, s"APDU data field max 255 B (got ${data.length}); use chunkedSend")
    require((cla & 0xff) == cla, s"cla must be 0..255 (got $cla)")
    require((ins & 0xff) == ins, s"ins must be 0..255 (got $ins)")
    require((p1  & 0xff) == p1,  s"p1 must be 0..255 (got $p1)")
    require((p2  & 0xff) == p2,  s"p2 must be 0..255 (got $p2)")
    val out = new Array[Byte](5 + data.length)
    out(0) = cla.toByte
    out(1) = ins.toByte
    out(2) = p1.toByte
    out(3) = p2.toByte
    out(4) = data.length.toByte
    System.arraycopy(data, 0, out, 5, data.length)
    out

  /** Parse a response: split into `(sw, payload)`. */
  def parseResponse(resp: Array[Byte]): (Int, Array[Byte]) =
    require(resp.length >= 2, s"Response too short (${resp.length} B); need at least sw1 sw2")
    val sw1     = resp(resp.length - 2) & 0xff
    val sw2     = resp(resp.length - 1) & 0xff
    val sw      = (sw1 << 8) | sw2
    val payload = java.util.Arrays.copyOfRange(resp, 0, resp.length - 2)
    (sw, payload)

  /** Send a long payload as a sequence of chunked APDUs.
   *
   *  Used by Ledger app commands that exceed the 255-byte `Lc` limit
   *  (most notably the Ethereum app's `signTransaction` with a long
   *  RLP-encoded tx, and `signEIP712`'s structured data flow). The
   *  Ledger convention is:
   *   - first chunk: `p1 = p1First` (usually `0x00`)
   *   - subsequent chunks: `p1 = p1Continue` (usually `0x80`)
   *
   *  Each chunk is sent in order and the response of the final chunk
   *  carries the actual result (signature, address, …). Intermediate
   *  responses must return `0x9000` or the flow aborts.
   *
   *  @param transport   transport to write to
   *  @param cla         APDU class byte
   *  @param ins         APDU instruction byte
   *  @param p1First     `p1` for the first chunk
   *  @param p1Continue  `p1` for subsequent chunks
   *  @param p2          `p2` byte (same for every chunk)
   *  @param payload     full payload to be split
   *  @param chunkSize   bytes per chunk (default 255 — full `Lc` range)
   *  @return            the parsed `(sw, payload)` of the final chunk
   *                     — that is the response the host actually wants
   */
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
    require(chunkSize >= 1 && chunkSize <= 255,
      s"chunkSize must be 1..255 (got $chunkSize)")

    val chunks: Vector[Array[Byte]] =
      if payload.isEmpty then Vector(Array.emptyByteArray)
      else
        val nChunks = (payload.length + chunkSize - 1) / chunkSize
        Vector.tabulate(nChunks) { i =>
          val off = i * chunkSize
          val end = math.min(off + chunkSize, payload.length)
          java.util.Arrays.copyOfRange(payload, off, end)
        }

    def loop(idx: Int, lastResp: (Int, Array[Byte])): Future[(Int, Array[Byte])] =
      if idx >= chunks.length then Future.successful(lastResp)
      else
        val p1   = if idx == 0 then p1First else p1Continue
        val cmd  = command(cla, ins, p1, p2, chunks(idx))
        transport.exchange(cmd).flatMap { resp =>
          val parsed = parseResponse(resp)
          if parsed._1 != Sw_Ok && idx < chunks.length - 1 then
            // intermediate failure — short-circuit with this status
            Future.successful(parsed)
          else
            loop(idx + 1, parsed)
        }

    loop(0, (Sw_Ok, Array.emptyByteArray))

  /** Render a status word as a 4-hex-digit string, e.g. "0x9000". */
  def swHex(sw: Int): String =
    "0x" + "%04X".format(sw & 0xffff)
