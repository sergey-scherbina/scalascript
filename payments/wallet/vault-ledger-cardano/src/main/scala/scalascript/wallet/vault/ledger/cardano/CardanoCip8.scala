package scalascript.wallet.vault.ledger.cardano

/** Minimal CIP-8 COSE_Sign1 envelope builder for Cardano dApp signing.
 *
 *  Produces the two byte sequences needed to perform a CIP-8 sign:
 *
 *  1. `protectedHeader` — the CBOR-encoded COSE protected header with
 *     algorithm `{ 1: -8 }` (alg: EdDSA, COSE algorithm id -8).
 *  2. `sigStructure` — the CBOR-encoded Sig_Structure:
 *     `["Signature1", bstr(protected), bstr(""), bstr(payload)]`
 *     This is the byte sequence the device actually signs.
 *
 *  No external CBOR library is used; encoding is hand-rolled for these
 *  fixed, small structures.
 *
 *  Reference: CIP-0008 (Cardano Improvement Proposal 8),
 *             RFC 8152 §4.4 (COSE Sig_Structure). */
object CardanoCip8:

  /** Build COSE_Sign1 protected header bytes.
   *
   *  Encodes `{ 1: -8 }` (algorithm: EdDSA) as a CBOR map wrapped in
   *  a bstr (byte string), following the COSE spec §3.
   *
   *  CBOR encoding: `A1 01 27`
   *  - `A1` = map of 1 item
   *  - `01` = uint(1)     (key:   alg)
   *  - `27` = nint(-8)    (value: EdDSA algorithm id -8, encoded as `0x20 | 7 = 0x27`) */
  def protectedHeader: Array[Byte] =
    encode(CborMap(Seq(CborUInt(1) -> CborNInt(7))))

  /** Build the signing payload for SIGN_TX.
   *
   *  Encodes the COSE Sig_Structure:
   *  `["Signature1", bstr(protectedHeader), bstr(""), bstr(payload)]`
   *
   *  This is the byte sequence the Ledger Cardano app signs. */
  def sigStructure(payload: Array[Byte]): Array[Byte] =
    val ph = protectedHeader
    encode(CborArr(Seq(
      CborText("Signature1"),
      CborBytes(ph),
      CborBytes(Array.emptyByteArray),
      CborBytes(payload),
    )))

  // ── Minimal CBOR ADT ────────────────────────────────────────────────

  private sealed trait Cbor
  private final case class CborUInt(n: Long)                    extends Cbor
  private final case class CborNInt(encoded: Long)              extends Cbor  // encoded = abs(n)-1
  private final case class CborBytes(value: Array[Byte])        extends Cbor
  private final case class CborText(value: String)              extends Cbor
  private final case class CborArr(items: Seq[Cbor])            extends Cbor
  private final case class CborMap(items: Seq[(Cbor, Cbor)])    extends Cbor

  private def encode(v: Cbor): Array[Byte] =
    val buf = scala.collection.mutable.ArrayBuffer.empty[Byte]
    write(v, buf)
    buf.toArray

  private def write(value: Cbor, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
    value match
      case CborUInt(n)    => head(0, n, out)
      case CborNInt(n)    => head(1, n, out)
      case CborBytes(bs)  => head(2, bs.length, out); out ++= bs
      case CborText(s)    =>
        val bs = s.getBytes("UTF-8")
        head(3, bs.length, out); out ++= bs
      case CborArr(items) =>
        head(4, items.length, out)
        items.foreach(write(_, out))
      case CborMap(items) =>
        head(5, items.length, out)
        items.foreach { case (k, v) => write(k, out); write(v, out) }

  private def head(major: Int, arg: Long, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
    val mt = (major << 5).toByte
    if arg < 24 then
      out += (mt | arg.toByte).toByte
    else if arg < 256 then
      out += (mt | 24).toByte
      out += arg.toByte
    else if arg < 65536 then
      out += (mt | 25).toByte
      out += (arg >> 8).toByte
      out += arg.toByte
    else if arg < 0x100000000L then
      out += (mt | 26).toByte
      out += (arg >> 24).toByte
      out += (arg >> 16).toByte
      out += (arg >> 8).toByte
      out += arg.toByte
    else
      out += (mt | 27).toByte
      (7 to 0 by -1).foreach(i => out += ((arg >> (i * 8)) & 0xff).toByte)
