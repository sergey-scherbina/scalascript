package scalascript.wire.cbor

import scalascript.wire.*
import java.io.ByteArrayOutputStream
import java.nio.{ByteBuffer, ByteOrder}

/** Minimal CBOR encoder/decoder for `WireValue`.
 *
 *  Implements a portable subset of RFC 7049 (CBOR) that covers all
 *  `WireValue` cases without pulling a library dependency. Works on JVM
 *  and can be ported to Scala.js (no platform APIs used in the codec).
 *
 *  CBOR major types used:
 *  - 0: unsigned integer (Int64 ≥ 0)
 *  - 1: negative integer (Int64 < 0)
 *  - 2: byte string (Bytes)
 *  - 3: text string (Str, field names)
 *  - 4: array (Lst, Tuple)
 *  - 5: map (Object, Enum, Pid, Error, Unit, WireValue.Map entries)
 *  - 6: tagged item (tag 1 = typed object, tag 2 = enum, tag 3 = pid, tag 4 = error, tag 5 = tuple)
 *  - 7: simple values + floats (Null → 0xF6, Bool, Float64)
 *
 *  Spec: specs/distributed-wire-protocol.md §Phase 1 */
object CborWireCodec:

  // ── CBOR tags for ScalaScript types ──────────────────────────────────────
  private val TAG_UNIT   = 55800L  // Self-Described CBOR tag from IANA, reused as Unit
  private val TAG_OBJECT = 1L      // ScalaScript tag: tagged array [typeName, [[field, val]...]]
  private val TAG_ENUM   = 2L      // ScalaScript tag: tagged array [typeName, caseName, val|null]
  private val TAG_PID    = 3L      // ScalaScript tag: tagged array [nodeId, localId]
  private val TAG_ERROR  = 4L      // ScalaScript tag: tagged array [code, message, details|null]
  private val TAG_TUPLE  = 5L      // ScalaScript tag: tagged array [vals...]

  // ── Encoder ───────────────────────────────────────────────────────────────

  def encodeToBytes(v: WireValue): Array[Byte] =
    val buf = ByteArrayOutputStream(256)
    writeValue(v, buf)
    buf.toByteArray

  private def writeValue(v: WireValue, out: ByteArrayOutputStream): Unit = v match
    case WireValue.Null       => out.write(0xF6)
    case WireValue.Unit       =>
      writeTag(TAG_UNIT, out)
      out.write(0xF6)           // tag(55800) followed by null
    case WireValue.Bool(true)  => out.write(0xF5)
    case WireValue.Bool(false) => out.write(0xF4)
    case WireValue.Int64(n) if n >= 0 => writeUInt(0, n, out)
    case WireValue.Int64(n)           => writeUInt(1, -1 - n, out)
    case WireValue.Float64(d)  =>
      out.write(0xFB)           // CBOR float64
      writeDouble(d, out)
    case WireValue.Str(s)      =>
      val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      writeUInt(3, bytes.length.toLong, out)
      out.write(bytes)
    case WireValue.Bytes(bs)   =>
      writeUInt(2, bs.length.toLong, out)
      out.write(bs)
    case WireValue.Lst(vs)     =>
      writeUInt(4, vs.length.toLong, out)
      vs.foreach(writeValue(_, out))
    case WireValue.Map(entries) =>
      writeUInt(5, entries.length.toLong, out)
      entries.foreach { case (k, v) => writeValue(k, out); writeValue(v, out) }
    case WireValue.Object(typeName, fields) =>
      // tag(1) [typeName, [[name, val]...]]
      writeTag(TAG_OBJECT, out)
      writeUInt(4, 2L, out)
      writeTextStr(typeName, out)
      writeUInt(4, fields.length.toLong, out)
      fields.foreach { case (name, value) =>
        writeUInt(4, 2L, out)
        writeTextStr(name, out)
        writeValue(value, out)
      }
    case WireValue.Tuple(values) =>
      writeTag(TAG_TUPLE, out)
      writeUInt(4, values.length.toLong, out)
      values.foreach(writeValue(_, out))
    case WireValue.Enum(typeName, caseName, value) =>
      // tag(2) [typeName, caseName, val|0xF6]
      writeTag(TAG_ENUM, out)
      writeUInt(4, 3L, out)
      writeTextStr(typeName, out)
      writeTextStr(caseName, out)
      value match
        case None    => out.write(0xF6)
        case Some(v) => writeValue(v, out)
    case WireValue.Pid(nodeId, localId) =>
      // tag(3) [nodeId, localId]
      writeTag(TAG_PID, out)
      writeUInt(4, 2L, out)
      writeTextStr(nodeId, out)
      if localId >= 0 then writeUInt(0, localId, out)
      else writeUInt(1, -1 - localId, out)
    case WireValue.Error(code, message, details) =>
      // tag(4) [code, message, details|0xF6]
      writeTag(TAG_ERROR, out)
      writeUInt(4, 3L, out)
      writeTextStr(code, out)
      writeTextStr(message, out)
      details match
        case None    => out.write(0xF6)
        case Some(d) => writeValue(d, out)

  private def writeUInt(major: Int, n: Long, out: ByteArrayOutputStream): Unit =
    val mt = major << 5
    if n <= 23 then out.write(mt | n.toInt)
    else if n <= 0xFF then { out.write(mt | 24); out.write(n.toInt) }
    else if n <= 0xFFFF then { out.write(mt | 25); out.write((n >> 8).toInt & 0xFF); out.write(n.toInt & 0xFF) }
    else if n <= 0xFFFFFFFFL then {
      out.write(mt | 26)
      out.write((n >> 24).toInt & 0xFF); out.write((n >> 16).toInt & 0xFF)
      out.write((n >>  8).toInt & 0xFF); out.write(n.toInt & 0xFF)
    } else {
      out.write(mt | 27)
      (7 to 0 by -1).foreach(i => out.write((n >> (i * 8)).toInt & 0xFF))
    }

  private def writeTag(tag: Long, out: ByteArrayOutputStream): Unit = writeUInt(6, tag, out)

  private def writeTextStr(s: String, out: ByteArrayOutputStream): Unit =
    val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    writeUInt(3, bytes.length.toLong, out)
    out.write(bytes)

  private def writeDouble(d: Double, out: ByteArrayOutputStream): Unit =
    val bits = java.lang.Double.doubleToRawLongBits(d)
    (7 to 0 by -1).foreach(i => out.write((bits >> (i * 8)).toInt & 0xFF))

  // ── Decoder ───────────────────────────────────────────────────────────────

  def decodeFromBytes(bytes: Array[Byte]): Either[WireDecodeError, WireValue] =
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    try readValue(buf).flatMap { (v, _) => Right(v) }
    catch case ex: Exception =>
      Left(WireDecodeError.MalformedInput(s"CBOR parse error: ${ex.getMessage}"))

  private type ReadResult[A] = Either[WireDecodeError, (A, ByteBuffer)]

  private def readValue(buf: ByteBuffer): ReadResult[WireValue] =
    if !buf.hasRemaining then return Left(WireDecodeError.MalformedInput("unexpected end of CBOR input"))
    val b = buf.get() & 0xFF
    val major = b >> 5
    val info  = b & 0x1F
    major match
      case 0 => readUInt(info, buf).map { (n, b2) => WireValue.Int64(n) -> b2 }
      case 1 => readUInt(info, buf).map { (n, b2) => WireValue.Int64(-1 - n) -> b2 }
      case 2 =>
        readUInt(info, buf).flatMap { (n, b2) =>
          val arr = Array.ofDim[Byte](n.toInt)
          b2.get(arr)
          Right(WireValue.Bytes(arr) -> b2)
        }
      case 3 =>
        readUInt(info, buf).flatMap { (n, b2) =>
          val arr = Array.ofDim[Byte](n.toInt)
          b2.get(arr)
          Right(WireValue.Str(new String(arr, java.nio.charset.StandardCharsets.UTF_8)) -> b2)
        }
      case 4 =>
        readUInt(info, buf).flatMap { (n, b2) =>
          readN(n.toInt, b2).map { (vs, b3) => WireValue.Lst(vs) -> b3 }
        }
      case 5 =>
        readUInt(info, buf).flatMap { (n, b2) =>
          readPairs(n.toInt, b2).map { (pairs, b3) =>
            WireValue.Map(pairs.map { case (k, v) => k -> v }) -> b3
          }
        }
      case 6 =>
        readUInt(info, buf).flatMap { (tag, b2) =>
          readTagged(tag, b2)
        }
      case 7 => info match
        case 20 => Right(WireValue.Bool(false) -> buf)
        case 21 => Right(WireValue.Bool(true)  -> buf)
        case 22 => Right(WireValue.Null        -> buf)
        case 23 => Right(WireValue.Null        -> buf)
        case 27 =>
          val bits = buf.getLong()
          Right(WireValue.Float64(java.lang.Double.longBitsToDouble(bits)) -> buf)
        case _ => Left(WireDecodeError.MalformedInput(s"unsupported CBOR simple value: $info"))
      case _ => Left(WireDecodeError.MalformedInput(s"unsupported CBOR major type: $major"))

  private def readTagged(tag: Long, buf: ByteBuffer): ReadResult[WireValue] =
    if tag == TAG_UNIT then
      readValue(buf).map { (_, b2) => WireValue.Unit -> b2 }
    else if tag == TAG_OBJECT then
      readValue(buf).flatMap {
        case (WireValue.Lst(items), b2) if items.length == 2 =>
          items(0) match
            case WireValue.Str(typeName) =>
              items(1) match
                case WireValue.Lst(fieldPairs) =>
                  val fields = fieldPairs.collect {
                    case WireValue.Lst(pair) if pair.length == 2 =>
                      pair(0) match
                        case WireValue.Str(name) => Some(name -> pair(1))
                        case _ => None
                    case _ => None
                  }.flatten
                  Right(WireValue.Object(typeName, fields) -> b2)
                case _ => Left(WireDecodeError.MalformedInput("tagged Object: field list must be array"))
            case _ => Left(WireDecodeError.MalformedInput("tagged Object: first element must be type name"))
        case _ => Left(WireDecodeError.MalformedInput("tagged Object: must be a 2-element array"))
      }
    else if tag == TAG_ENUM then
      readValue(buf).flatMap {
        case (WireValue.Lst(items), b2) if items.length == 3 =>
          (items(0), items(1)) match
            case (WireValue.Str(typeName), WireValue.Str(caseName)) =>
              val valOpt = items(2) match
                case WireValue.Null => None
                case v              => Some(v)
              Right(WireValue.Enum(typeName, caseName, valOpt) -> b2)
            case _ => Left(WireDecodeError.MalformedInput("tagged Enum: first two elements must be strings"))
        case _ => Left(WireDecodeError.MalformedInput("tagged Enum: must be a 3-element array"))
      }
    else if tag == TAG_PID then
      readValue(buf).flatMap {
        case (WireValue.Lst(items), b2) if items.length == 2 =>
          items(0) match
            case WireValue.Str(nodeId) =>
              items(1) match
                case WireValue.Int64(localId) => Right(WireValue.Pid(nodeId, localId) -> b2)
                case _ => Left(WireDecodeError.MalformedInput("tagged Pid: localId must be int64"))
            case _ => Left(WireDecodeError.MalformedInput("tagged Pid: nodeId must be string"))
        case _ => Left(WireDecodeError.MalformedInput("tagged Pid: must be a 2-element array"))
      }
    else if tag == TAG_ERROR then
      readValue(buf).flatMap {
        case (WireValue.Lst(items), b2) if items.length == 3 =>
          (items(0), items(1)) match
            case (WireValue.Str(code), WireValue.Str(msg)) =>
              val detailsOpt = items(2) match
                case WireValue.Null => None
                case v              => Some(v)
              Right(WireValue.Error(code, msg, detailsOpt) -> b2)
            case _ => Left(WireDecodeError.MalformedInput("tagged Error: first two must be strings"))
        case _ => Left(WireDecodeError.MalformedInput("tagged Error: must be a 3-element array"))
      }
    else if tag == TAG_TUPLE then
      readValue(buf).flatMap {
        case (WireValue.Lst(items), b2) => Right(WireValue.Tuple(items) -> b2)
        case _ => Left(WireDecodeError.MalformedInput("tagged Tuple: must be an array"))
      }
    else
      // Unknown tag — just decode the value as-is
      readValue(buf)

  private def readUInt(info: Int, buf: ByteBuffer): ReadResult[Long] =
    if info <= 23 then Right(info.toLong -> buf)
    else if info == 24 then Right((buf.get() & 0xFF).toLong -> buf)
    else if info == 25 then Right(((buf.get() & 0xFF).toLong << 8 | (buf.get() & 0xFF).toLong) -> buf)
    else if info == 26 then Right((buf.getInt().toLong & 0xFFFFFFFFL) -> buf)
    else if info == 27 then Right(buf.getLong() -> buf)
    else Left(WireDecodeError.MalformedInput(s"unsupported CBOR additional info: $info"))

  private def readN(n: Int, buf: ByteBuffer): ReadResult[Vector[WireValue]] =
    (0 until n).foldLeft[Either[WireDecodeError, (Vector[WireValue], ByteBuffer)]](Right(Vector.empty -> buf)) {
      (acc, _) => acc.flatMap { (vs, b) => readValue(b).map { (v, b2) => (vs :+ v) -> b2 } }
    }

  private def readPairs(n: Int, buf: ByteBuffer): ReadResult[Vector[(WireValue, WireValue)]] =
    (0 until n).foldLeft[Either[WireDecodeError, (Vector[(WireValue, WireValue)], ByteBuffer)]](Right(Vector.empty -> buf)) {
      (acc, _) => acc.flatMap { (pairs, b) =>
        readValue(b).flatMap { (k, b2) =>
          readValue(b2).map { (v, b3) => (pairs :+ (k -> v)) -> b3 }
        }
      }
    }

  // ── WireEnvelope CBOR encode/decode ──────────────────────────────────────

  def encodeEnvelope(env: WireEnvelope): Array[Byte] =
    // Encode as a CBOR map with string keys
    val payload = WireValue.Object("WireEnvelope", Vector(
      "protocol"    -> WireValue.Str(env.protocol),
      "protocolVer" -> WireValue.Int64(env.protocolVer.toLong),
      "format"      -> WireValue.Str(env.format),
      "kind"        -> WireValue.Str(env.kind),
      "correlationId" -> env.correlationId.map(WireValue.Str(_)).getOrElse(WireValue.Null),
      "schemaId"    -> env.schemaId.map(WireValue.Str(_)).getOrElse(WireValue.Null),
      "flags"       -> WireValue.Lst(env.flags.map(WireValue.Str(_)).toVector),
      "headers"     -> WireValue.Map(env.headers.map { case (k, v) => WireValue.Str(k) -> WireValue.Str(v) }.toVector),
      "payload"     -> env.payload,
    ))
    encodeToBytes(payload)

  def decodeEnvelope(bytes: Array[Byte]): Either[WireDecodeError, WireEnvelope] =
    decodeFromBytes(bytes).flatMap {
      case WireValue.Object(_, fields) =>
        val f = fields.toMap
        def str(name: String) = f.get(name).collect { case WireValue.Str(s) => s }.getOrElse("")
        def strOpt(name: String) = f.get(name).collect { case WireValue.Str(s) => s }
        val protocol    = str("protocol")
        val protocolVer = f.get("protocolVer").collect { case WireValue.Int64(n) => n.toInt }.getOrElse(1)
        val format      = str("format")
        val kind        = str("kind")
        val corrId      = strOpt("correlationId")
        val schemaId    = strOpt("schemaId")
        val flags       = f.get("flags") match
          case Some(WireValue.Lst(vs)) => vs.collect { case WireValue.Str(s) => s }.toSet
          case _ => Set.empty[String]
        val headers     = f.get("headers") match
          case Some(WireValue.Map(entries)) =>
            entries.collect { case (WireValue.Str(k), WireValue.Str(v)) => k -> v }.toMap
          case _ => Map.empty[String, String]
        val payload     = f.getOrElse("payload", WireValue.Null)
        Right(WireEnvelope(protocol, protocolVer, format, kind, corrId, schemaId, flags, headers, payload))
      case other => Left(WireDecodeError.TypeMismatch("WireEnvelope object", WireValue.kindOf(other)))
    }
