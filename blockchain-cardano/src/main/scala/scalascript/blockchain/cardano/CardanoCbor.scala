package scalascript.blockchain.cardano

/** Minimal CBOR encoder for Cardano transaction construction.
 *  Covers only the subset needed to build simple ADA / native-asset transfers.
 *
 *  Reuses the MiniCbor logic pattern from x402-facilitator-cardano but lives
 *  in blockchain-cardano so ChainAdapter can depend on it without pulling in
 *  x402 types. */
object CardanoCbor:

  sealed trait Value
  case class UInt(n: Long)                          extends Value
  case class Bytes(b: Array[Byte])                  extends Value
  case class Text(s: String)                        extends Value
  case class Arr(items: Seq[Value])                 extends Value
  case class Map(entries: Seq[(Value, Value)])      extends Value
  case class Tagged(tag: Long, value: Value)        extends Value

  def encode(v: Value): Array[Byte] =
    val buf = scala.collection.mutable.ArrayBuffer.empty[Byte]
    encodeInto(v, buf)
    buf.toArray

  private def encodeInto(v: Value, buf: scala.collection.mutable.ArrayBuffer[Byte]): Unit = v match
    case UInt(n) =>
      encodeHead(0, n, buf)
    case Bytes(b) =>
      encodeHead(2, b.length, buf)
      buf ++= b
    case Text(s) =>
      val utf8 = s.getBytes("UTF-8")
      encodeHead(3, utf8.length, buf)
      buf ++= utf8
    case Arr(items) =>
      encodeHead(4, items.length, buf)
      items.foreach(encodeInto(_, buf))
    case Map(entries) =>
      encodeHead(5, entries.length, buf)
      entries.foreach { case (k, v) => encodeInto(k, buf); encodeInto(v, buf) }
    case Tagged(tag, value) =>
      encodeHead(6, tag, buf)
      encodeInto(value, buf)

  private def encodeHead(major: Int, arg: Long, buf: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
    val mt = (major << 5).toByte
    if arg < 24 then
      buf += (mt | arg.toByte).toByte
    else if arg < 256 then
      buf += (mt | 24).toByte; buf += arg.toByte
    else if arg < 65536 then
      buf += (mt | 25).toByte; buf += (arg >> 8).toByte; buf += arg.toByte
    else if arg < 0x100000000L then
      buf += (mt | 26).toByte
      buf += (arg >> 24).toByte; buf += (arg >> 16).toByte
      buf += (arg >> 8).toByte;  buf += arg.toByte
    else
      buf += (mt | 27).toByte
      (7 to 0 by -1).foreach(i => buf += ((arg >> (i * 8)) & 0xff).toByte)

  // ── decoder ───────────────────────────────────────────────────────────────

  def decode(bytes: Array[Byte]): Value =
    val (v, _) = decodeFrom(bytes, 0)
    v

  private def decodeFrom(bytes: Array[Byte], pos: Int): (Value, Int) =
    val b    = bytes(pos) & 0xff
    val major = b >> 5
    val info  = b & 0x1f
    val (arg, nextPos) = readArg(bytes, pos + 1, info)
    major match
      case 0 => (UInt(arg), nextPos)
      case 1 => (UInt(-(arg + 1)), nextPos)   // negative int stored as UInt for simplicity
      case 2 =>
        val data = bytes.slice(nextPos, nextPos + arg.toInt)
        (Bytes(data), nextPos + arg.toInt)
      case 3 =>
        val data = bytes.slice(nextPos, nextPos + arg.toInt)
        (Text(new String(data, "UTF-8")), nextPos + arg.toInt)
      case 4 =>
        var p = nextPos
        val items = (0 until arg.toInt).map { _ => val (v, np) = decodeFrom(bytes, p); p = np; v }
        (Arr(items), p)
      case 5 =>
        var p = nextPos
        val entries = (0 until arg.toInt).map { _ =>
          val (k, p1) = decodeFrom(bytes, p)
          val (v, p2) = decodeFrom(bytes, p1)
          p = p2
          k -> v
        }
        (Map(entries), p)
      case 6 =>
        val (inner, p) = decodeFrom(bytes, nextPos)
        (Tagged(arg, inner), p)
      case _ => throw new IllegalArgumentException(s"Unsupported CBOR major type $major")

  private def readArg(bytes: Array[Byte], pos: Int, info: Int): (Long, Int) = info match
    case n if n < 24 => (n.toLong, pos)
    case 24          => ((bytes(pos) & 0xff).toLong, pos + 1)
    case 25          =>
      (((bytes(pos) & 0xff).toLong << 8) | (bytes(pos + 1) & 0xff), pos + 2)
    case 26          =>
      var v = 0L
      for i <- 0 until 4 do v = (v << 8) | (bytes(pos + i) & 0xff)
      (v, pos + 4)
    case 27          =>
      var v = 0L
      for i <- 0 until 8 do v = (v << 8) | (bytes(pos + i) & 0xff)
      (v, pos + 8)
    case _ => throw new IllegalArgumentException(s"Unsupported CBOR additional info $info")

  /** Build the CIP-8 Sig_Structure bytes that are signed by Ed25519.
   *  @param protectedHeader CBOR-encoded protected header bytes
   *  @param payload         message payload bytes */
  def cip8SigStructure(protectedHeader: Array[Byte], payload: Array[Byte]): Array[Byte] =
    encode(Arr(Seq(
      Text("Signature1"),
      Bytes(protectedHeader),
      Bytes(Array.empty),
      Bytes(payload),
    )))

  /** Encode the CIP-8 protected header {1: -8} (alg = EdDSA). */
  def cip8ProtectedHeader(address: Array[Byte]): Array[Byte] =
    encode(Map(Seq(
      UInt(1)       -> UInt(0x27),   // alg = -8 (EdDSA): CBOR int -8 = 0x27 in major-type-0 but actually...
      // alg = -8 in CBOR: negative integers use major type 1; -8 = -(8-1)-1 = NInt(7) = 0x27
      Text("address") -> Bytes(address),
    )))

  /** Build a COSE_Sign1 envelope:
   *  [protected_header_bstr, {}, payload, signature] */
  def cip8CoseSign1(
    protectedHeader: Array[Byte],
    payload:         Array[Byte],
    signature:       Array[Byte],
  ): Array[Byte] =
    encode(Tagged(18, Arr(Seq(
      Bytes(protectedHeader),
      Map(Seq.empty),
      Bytes(payload),
      Bytes(signature),
    ))))
