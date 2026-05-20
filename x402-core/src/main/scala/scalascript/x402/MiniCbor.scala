package scalascript.x402

// Minimal CBOR codec covering only the subset needed for CIP-8 / COSE_Sign1.
// Handles major types 0-6; indefinite-length items are not supported.
object MiniCbor:

  sealed trait Value
  case class UInt(n: Long)                              extends Value
  case class NInt(n: Long)                              extends Value   // represents -(n+1)
  case class Bytes(b: Array[Byte])                      extends Value
  case class Text(s: String)                            extends Value
  case class Arr(items: IndexedSeq[Value])              extends Value
  case class Map(entries: IndexedSeq[(Value, Value)])   extends Value
  case class Tagged(tag: Long, v: Value)                extends Value

  // ── Decode ────────────────────────────────────────────────────────────────────

  def decode(bytes: Array[Byte]): Value = decodeAt(bytes, 0)._1

  private def decodeAt(bytes: Array[Byte], pos: Int): (Value, Int) =
    val b     = bytes(pos) & 0xFF
    val major = b >> 5
    val info  = b & 0x1F
    val (arg, next) = readArg(bytes, pos + 1, info)
    major match
      case 0 => (UInt(arg), next)
      case 1 => (NInt(arg), next)
      case 2 =>
        val data = bytes.slice(next, next + arg.toInt)
        (Bytes(data), next + arg.toInt)
      case 3 =>
        val data = bytes.slice(next, next + arg.toInt)
        (Text(java.lang.String(data, "UTF-8")), next + arg.toInt)
      case 4 =>
        var p = next
        val items = (0 until arg.toInt).map { _ =>
          val (v, np) = decodeAt(bytes, p); p = np; v
        }
        (Arr(items.toIndexedSeq), p)
      case 5 =>
        var p = next
        val entries = (0 until arg.toInt).map { _ =>
          val (k, p1) = decodeAt(bytes, p)
          val (v, p2) = decodeAt(bytes, p1)
          p = p2; k -> v
        }
        (Map(entries.toIndexedSeq), p)
      case 6 =>
        val (v, np) = decodeAt(bytes, next)
        (Tagged(arg, v), np)
      case _ =>
        throw RuntimeException(s"Unsupported CBOR major type $major at byte $pos")

  private def readArg(bytes: Array[Byte], pos: Int, info: Int): (Long, Int) =
    if info <= 23 then (info.toLong, pos)
    else if info == 24 then ((bytes(pos) & 0xFF).toLong, pos + 1)
    else if info == 25 then
      val n = ((bytes(pos) & 0xFF).toLong << 8) | (bytes(pos + 1) & 0xFF).toLong
      (n, pos + 2)
    else if info == 26 then
      val n = ((bytes(pos) & 0xFF).toLong << 24)  |
              ((bytes(pos + 1) & 0xFF).toLong << 16) |
              ((bytes(pos + 2) & 0xFF).toLong << 8)  |
              (bytes(pos + 3) & 0xFF).toLong
      (n, pos + 4)
    else if info == 27 then
      var n = 0L
      var i = 0
      while i < 8 do { n = (n << 8) | (bytes(pos + i) & 0xFF).toLong; i += 1 }
      (n, pos + 8)
    else throw RuntimeException(s"Unsupported CBOR additional info $info")

  // ── Encode ────────────────────────────────────────────────────────────────────

  def encode(v: Value): Array[Byte] = v match
    case UInt(n)      => header(0, n)
    case NInt(n)      => header(1, n)
    case Bytes(b)     => header(2, b.length.toLong) ++ b
    case Text(s)      =>
      val b = s.getBytes("UTF-8")
      header(3, b.length.toLong) ++ b
    case Arr(items)   =>
      val buf = new scala.collection.mutable.ArrayBuffer[Byte]
      buf ++= header(4, items.length.toLong)
      items.foreach(buf ++= encode(_))
      buf.toArray
    case Map(entries) =>
      val buf = new scala.collection.mutable.ArrayBuffer[Byte]
      buf ++= header(5, entries.length.toLong)
      entries.foreach { case (k, v) => buf ++= encode(k); buf ++= encode(v) }
      buf.toArray
    case Tagged(tag, v) =>
      header(6, tag) ++ encode(v)

  private def header(major: Int, arg: Long): Array[Byte] =
    val prefix = major << 5
    if arg <= 23 then
      Array(((prefix | arg) & 0xFF).toByte)
    else if arg <= 0xFF then
      Array(((prefix | 24) & 0xFF).toByte, (arg & 0xFF).toByte)
    else if arg <= 0xFFFF then
      Array(((prefix | 25) & 0xFF).toByte, ((arg >> 8) & 0xFF).toByte, (arg & 0xFF).toByte)
    else if arg <= 0xFFFFFFFFL then
      Array(
        ((prefix | 26) & 0xFF).toByte,
        ((arg >> 24) & 0xFF).toByte, ((arg >> 16) & 0xFF).toByte,
        ((arg >> 8)  & 0xFF).toByte, (arg        & 0xFF).toByte,
      )
    else
      Array(
        ((prefix | 27) & 0xFF).toByte,
        ((arg >> 56) & 0xFF).toByte, ((arg >> 48) & 0xFF).toByte,
        ((arg >> 40) & 0xFF).toByte, ((arg >> 32) & 0xFF).toByte,
        ((arg >> 24) & 0xFF).toByte, ((arg >> 16) & 0xFF).toByte,
        ((arg >> 8)  & 0xFF).toByte, (arg        & 0xFF).toByte,
      )
