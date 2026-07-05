package scalascript.crypto

/** Minimal CBOR (RFC 8949) codec — major types 0-6, definite-length only. Pure Scala, identical on the
 *  JVM and Scala.js. Enough for COSE (RFC 8152/9052) structures; not a general-purpose CBOR library
 *  (no floats, no indefinite-length, no simple/bool values). Mirrors the `x402.MiniCbor` subset, kept
 *  here so the crypto layer has no upward dependency. */
object Cbor:

  sealed trait Value
  case class UInt(n: Long)                            extends Value   // major 0
  case class NInt(n: Long)                            extends Value   // major 1: the value is -(n+1)
  case class Bytes(b: Array[Byte])                    extends Value   // major 2
  case class Text(s: String)                          extends Value   // major 3
  case class Arr(items: IndexedSeq[Value])            extends Value   // major 4
  case class Map(entries: IndexedSeq[(Value, Value)]) extends Value   // major 5
  case class Tagged(tag: Long, v: Value)              extends Value   // major 6

  /** Encode a signed integer to the shortest CBOR uint/negint. */
  def int(i: Long): Value = if i >= 0 then UInt(i) else NInt(-(i + 1))

  // ── Encode ────────────────────────────────────────────────────────────────────

  def encode(v: Value): Array[Byte] = v match
    case UInt(n)  => header(0, n)
    case NInt(n)  => header(1, n)
    case Bytes(b) => header(2, b.length.toLong) ++ b
    case Text(s)  =>
      val b = s.getBytes("UTF-8"); header(3, b.length.toLong) ++ b
    case Arr(items) =>
      val buf = new scala.collection.mutable.ArrayBuffer[Byte]
      buf ++= header(4, items.length.toLong); items.foreach(buf ++= encode(_)); buf.toArray
    case Map(entries) =>
      val buf = new scala.collection.mutable.ArrayBuffer[Byte]
      buf ++= header(5, entries.length.toLong)
      entries.foreach { case (k, vv) => buf ++= encode(k); buf ++= encode(vv) }
      buf.toArray
    case Tagged(tag, vv) => header(6, tag) ++ encode(vv)

  private def header(major: Int, arg: Long): Array[Byte] =
    val p = major << 5
    if arg <= 23 then Array(((p | arg.toInt) & 0xff).toByte)
    else if arg <= 0xffL then Array(((p | 24) & 0xff).toByte, (arg & 0xff).toByte)
    else if arg <= 0xffffL then
      Array(((p | 25) & 0xff).toByte, ((arg >> 8) & 0xff).toByte, (arg & 0xff).toByte)
    else if arg <= 0xffffffffL then
      Array(((p | 26) & 0xff).toByte, ((arg >> 24) & 0xff).toByte, ((arg >> 16) & 0xff).toByte,
            ((arg >> 8) & 0xff).toByte, (arg & 0xff).toByte)
    else
      Array(((p | 27) & 0xff).toByte, ((arg >> 56) & 0xff).toByte, ((arg >> 48) & 0xff).toByte,
            ((arg >> 40) & 0xff).toByte, ((arg >> 32) & 0xff).toByte, ((arg >> 24) & 0xff).toByte,
            ((arg >> 16) & 0xff).toByte, ((arg >> 8) & 0xff).toByte, (arg & 0xff).toByte)

  // ── Decode ────────────────────────────────────────────────────────────────────

  def decode(bytes: Array[Byte]): Value = decodeAt(bytes, 0)._1

  private def decodeAt(bytes: Array[Byte], pos: Int): (Value, Int) =
    val b     = bytes(pos) & 0xff
    val major = b >> 5
    val info  = b & 0x1f
    val (arg, next) = readArg(bytes, pos + 1, info)
    major match
      case 0 => (UInt(arg), next)
      case 1 => (NInt(arg), next)
      case 2 => (Bytes(bytes.slice(next, next + arg.toInt)), next + arg.toInt)
      case 3 => (Text(java.lang.String(bytes.slice(next, next + arg.toInt), "UTF-8")), next + arg.toInt)
      case 4 =>
        var p = next
        val items = (0 until arg.toInt).map { _ => val (v, np) = decodeAt(bytes, p); p = np; v }
        (Arr(items.toIndexedSeq), p)
      case 5 =>
        var p = next
        val entries = (0 until arg.toInt).map { _ =>
          val (k, p1) = decodeAt(bytes, p); val (v, p2) = decodeAt(bytes, p1); p = p2; k -> v
        }
        (Map(entries.toIndexedSeq), p)
      case 6 => val (v, np) = decodeAt(bytes, next); (Tagged(arg, v), np)
      case _ => throw RuntimeException(s"Unsupported CBOR major type $major at byte $pos")

  private def readArg(bytes: Array[Byte], pos: Int, info: Int): (Long, Int) =
    if info <= 23 then (info.toLong, pos)
    else if info == 24 then ((bytes(pos) & 0xff).toLong, pos + 1)
    else if info == 25 then (((bytes(pos) & 0xff).toLong << 8) | (bytes(pos + 1) & 0xff).toLong, pos + 2)
    else if info == 26 then
      (((bytes(pos) & 0xff).toLong << 24) | ((bytes(pos + 1) & 0xff).toLong << 16) |
       ((bytes(pos + 2) & 0xff).toLong << 8) | (bytes(pos + 3) & 0xff).toLong, pos + 4)
    else if info == 27 then
      var n = 0L; var i = 0
      while i < 8 do { n = (n << 8) | (bytes(pos + i) & 0xff).toLong; i += 1 }
      (n, pos + 8)
    else throw RuntimeException(s"Unsupported CBOR additional info $info")
