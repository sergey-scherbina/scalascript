package scalascript.control

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Typed error raised when canonical durable-frame bytes cannot be decoded. */
final class DurableDecodeError(message: String) extends RuntimeException(message)

/**
 * Immutable canonical byte encoding of a durable frame value
 * (`specs/durable-frame-codec.md`). Value equality is by content; the raw array
 * is never shared.
 */
final class DurableBytes private[control] (source: Array[Byte]):
  private val stored: Array[Byte] = source.clone()

  def length: Int = stored.length

  /** A defensive copy of the canonical bytes for transport. */
  def toArray: Array[Byte] = stored.clone()

  private[control] def view: Array[Byte] = stored

  override def equals(other: Any): Boolean = other match
    case that: DurableBytes => java.util.Arrays.equals(stored, that.stored)
    case _                  => false

  override def hashCode(): Int = java.util.Arrays.hashCode(stored)

  override def toString: String =
    val builder = new java.lang.StringBuilder(stored.length * 2)
    var index = 0
    while index < stored.length do
      val value = stored(index) & 0xff
      builder.append(Character.forDigit(value >>> 4, 16))
      builder.append(Character.forDigit(value & 0x0f, 16))
      index += 1
    builder.toString

object DurableBytes:
  /** Reconstruct canonical bytes received from transport. */
  def fromArray(source: Array[Byte]): DurableBytes = new DurableBytes(source)

private[control] final class DurableWriter:
  private val buffer = new ByteArrayOutputStream()

  def writeByte(value: Int): Unit = buffer.write(value & 0xff)

  def writeInt(value: Int): Unit =
    writeByte(value >>> 24)
    writeByte(value >>> 16)
    writeByte(value >>> 8)
    writeByte(value)

  def writeLong(value: Long): Unit =
    var shift = 56
    while shift >= 0 do
      writeByte((value >>> shift).toInt)
      shift -= 8

  def writeBytes(data: Array[Byte]): Unit = buffer.write(data, 0, data.length)

  def toBytes: DurableBytes = new DurableBytes(buffer.toByteArray)

private[control] object DurableReader:
  // A durable frame element count is bounded; byte lengths are bounded by the
  // remaining input. This caps pathological allocation from a hostile capsule.
  val MaxElementCount: Int = 1 << 24

private[control] final class DurableReader(bytes: DurableBytes):
  private val data = bytes.view
  private var position = 0

  private def require(count: Int): Unit =
    if count < 0 || position + count > data.length then
      throw new DurableDecodeError(
        s"durable frame truncated: need $count byte(s) at offset $position of ${data.length}"
      )

  def readByte(): Int =
    require(1)
    val value = data(position) & 0xff
    position += 1
    value

  def readInt(): Int =
    (readByte() << 24) | (readByte() << 16) | (readByte() << 8) | readByte()

  def readLong(): Long =
    var result = 0L
    var index = 0
    while index < 8 do
      result = (result << 8) | (readByte().toLong & 0xff)
      index += 1
    result

  /** A byte length: bounded by the input that actually remains. */
  def readByteLength(label: String): Int =
    val value = readInt()
    if value < 0 then
      throw new DurableDecodeError(s"$label length out of range: $value")
    if value > data.length - position then
      throw new DurableDecodeError(
        s"$label length $value exceeds ${data.length - position} remaining byte(s)"
      )
    value

  /** A container element count: bounded by a sane ceiling. */
  def readElementCount(label: String): Int =
    val value = readInt()
    if value < 0 || value > DurableReader.MaxElementCount then
      throw new DurableDecodeError(s"$label element count out of range: $value")
    value

  def readBytes(count: Int): Array[Byte] =
    require(count)
    val out = new Array[Byte](count)
    System.arraycopy(data, position, out, 0, count)
    position += count
    out

  def requireExhausted(): Unit =
    if position != data.length then
      throw new DurableDecodeError(
        s"durable frame has ${data.length - position} trailing byte(s)"
      )

/**
 * A typed canonical byte codec for a durable frame value. Extends
 * [[DurableValue]] so any codec is usable directly as the `Continuation.savable`
 * snapshot evidence; `snapshot` is then the serialization round-trip. The
 * `write`/`read` primitives are package-private, so user code composes codecs from
 * the provided combinators rather than forging an arbitrary encoding.
 */
trait DurableCodec[S] extends DurableValue[S]:
  private[control] def write(writer: DurableWriter, value: S): Unit
  private[control] def read(reader: DurableReader): S

  final def encode(value: S): DurableBytes =
    val writer = new DurableWriter
    write(writer, value)
    writer.toBytes

  final def decode(bytes: DurableBytes): S =
    val reader = new DurableReader(bytes)
    val value = read(reader)
    reader.requireExhausted()
    value

  override final def snapshot(value: S): S = decode(encode(value))

object DurableCodec:
  val unit: DurableCodec[Unit] = new DurableCodec[Unit]:
    def write(writer: DurableWriter, value: Unit): Unit =
      val _ = writer
      val _ = value
    def read(reader: DurableReader): Unit =
      val _ = reader

  val boolean: DurableCodec[Boolean] = new DurableCodec[Boolean]:
    def write(writer: DurableWriter, value: Boolean): Unit =
      writer.writeByte(if value then 1 else 0)
    def read(reader: DurableReader): Boolean =
      reader.readByte() match
        case 0     => false
        case 1     => true
        case other => throw new DurableDecodeError(s"invalid boolean tag: $other")

  val int: DurableCodec[Int] = new DurableCodec[Int]:
    def write(writer: DurableWriter, value: Int): Unit = writer.writeInt(value)
    def read(reader: DurableReader): Int = reader.readInt()

  val long: DurableCodec[Long] = new DurableCodec[Long]:
    def write(writer: DurableWriter, value: Long): Unit = writer.writeLong(value)
    def read(reader: DurableReader): Long = reader.readLong()

  val bigInt: DurableCodec[BigInt] = new DurableCodec[BigInt]:
    def write(writer: DurableWriter, value: BigInt): Unit =
      val magnitude = value.bigInteger.toByteArray // minimal two's-complement BE
      writer.writeInt(magnitude.length)
      writer.writeBytes(magnitude)
    def read(reader: DurableReader): BigInt =
      val count = reader.readByteLength("bigint")
      if count == 0 then
        throw new DurableDecodeError("bigint magnitude must be non-empty")
      BigInt(new java.math.BigInteger(reader.readBytes(count)))

  // A single canonical NaN. Signed zero and every finite/infinite value keep
  // their exact bits; only NaN payloads are normalized, so the encoding is
  // byte-identical across lanes (a JS engine cannot preserve a NaN payload).
  private[control] val CanonicalNaNBits: Long = 0x7ff8000000000000L

  val double: DurableCodec[Double] = new DurableCodec[Double]:
    def write(writer: DurableWriter, value: Double): Unit =
      val bits =
        if java.lang.Double.isNaN(value) then CanonicalNaNBits
        else java.lang.Double.doubleToRawLongBits(value)
      writer.writeLong(bits)
    def read(reader: DurableReader): Double =
      java.lang.Double.longBitsToDouble(reader.readLong())

  val string: DurableCodec[String] = new DurableCodec[String]:
    def write(writer: DurableWriter, value: String): Unit =
      val utf8 = value.getBytes(StandardCharsets.UTF_8)
      writer.writeInt(utf8.length)
      writer.writeBytes(utf8)
    def read(reader: DurableReader): String =
      val count = reader.readByteLength("string")
      new String(reader.readBytes(count), StandardCharsets.UTF_8)

  val bytes: DurableCodec[DurableBytes] = new DurableCodec[DurableBytes]:
    def write(writer: DurableWriter, value: DurableBytes): Unit =
      writer.writeInt(value.length)
      writer.writeBytes(value.view)
    def read(reader: DurableReader): DurableBytes =
      val count = reader.readByteLength("bytes")
      new DurableBytes(reader.readBytes(count))

  def pair[A, B](
      left: DurableCodec[A],
      right: DurableCodec[B]
  ): DurableCodec[(A, B)] =
    new DurableCodec[(A, B)]:
      def write(writer: DurableWriter, value: (A, B)): Unit =
        left.write(writer, value._1)
        right.write(writer, value._2)
      def read(reader: DurableReader): (A, B) =
        val first = left.read(reader)
        val second = right.read(reader)
        (first, second)

  def either[A, B](
      left: DurableCodec[A],
      right: DurableCodec[B]
  ): DurableCodec[Either[A, B]] =
    new DurableCodec[Either[A, B]]:
      def write(writer: DurableWriter, value: Either[A, B]): Unit =
        value match
          case Left(a) =>
            writer.writeByte(0)
            left.write(writer, a)
          case Right(b) =>
            writer.writeByte(1)
            right.write(writer, b)
      def read(reader: DurableReader): Either[A, B] =
        reader.readByte() match
          case 0     => Left(left.read(reader))
          case 1     => Right(right.read(reader))
          case other => throw new DurableDecodeError(s"invalid either tag: $other")

  def list[A](element: DurableCodec[A]): DurableCodec[List[A]] =
    new DurableCodec[List[A]]:
      def write(writer: DurableWriter, value: List[A]): Unit =
        writer.writeInt(value.length)
        value.foreach(item => element.write(writer, item))
      def read(reader: DurableReader): List[A] =
        val count = reader.readElementCount("list")
        val builder = List.newBuilder[A]
        var index = 0
        while index < count do
          builder += element.read(reader)
          index += 1
        builder.result()

  /** Build a codec for a nominal type from a codec for its structural image. */
  def imap[A, B](codec: DurableCodec[A])(to: A => B)(from: B => A): DurableCodec[B] =
    new DurableCodec[B]:
      def write(writer: DurableWriter, value: B): Unit =
        codec.write(writer, from(value))
      def read(reader: DurableReader): B = to(codec.read(reader))

  /**
   * Stamp a value's canonical bytes with a nominal schema identity — a name and an
   * integer version — and reject, on decode, bytes written under a different name or
   * version (§9.1). The header is `string(schemaId) ++ int(version)`, reusing the
   * Part 1 scalar encodings, so evolving a schema (rename or version bump) makes an
   * older capsule fail loudly instead of mis-decoding. See
   * specs/durable-nominal-schema.md.
   */
  def schema[S](
      schemaId: String,
      version: Int,
      codec: DurableCodec[S]
  ): DurableCodec[S] =
    new DurableCodec[S]:
      def write(writer: DurableWriter, value: S): Unit =
        DurableCodec.string.write(writer, schemaId)
        DurableCodec.int.write(writer, version)
        codec.write(writer, value)
      def read(reader: DurableReader): S =
        val got = DurableCodec.string.read(reader)
        val decodedVersion = DurableCodec.int.read(reader)
        if got != schemaId then
          throw new DurableDecodeError(
            s"schema identity mismatch: expected '$schemaId', got '$got'"
          )
        if decodedVersion != version then
          throw new DurableDecodeError(
            s"schema version mismatch: expected $version, got $decodedVersion"
          )
        codec.read(reader)

  /**
   * A canonical map codec. Entries are written sorted by the unsigned lexicographic
   * order of each key's own encoding, so the bytes are independent of insertion or
   * iteration order (§9.1). Decoding rejects keys that are not in strictly ascending
   * canonical order (which also rejects duplicate keys).
   */
  def map[K, V](
      keyCodec: DurableCodec[K],
      valueCodec: DurableCodec[V]
  ): DurableCodec[Map[K, V]] =
    new DurableCodec[Map[K, V]]:
      def write(writer: DurableWriter, value: Map[K, V]): Unit =
        val sorted = value.toVector.sortWith { (left, right) =>
          java.util.Arrays.compareUnsigned(
            keyCodec.encode(left._1).view,
            keyCodec.encode(right._1).view
          ) < 0
        }
        writer.writeInt(sorted.length)
        sorted.foreach { entry =>
          keyCodec.write(writer, entry._1)
          valueCodec.write(writer, entry._2)
        }
      def read(reader: DurableReader): Map[K, V] =
        val count = reader.readElementCount("map")
        val builder = Map.newBuilder[K, V]
        var previousKey: Array[Byte] = null
        var index = 0
        while index < count do
          val key = keyCodec.read(reader)
          val keyBytes = keyCodec.encode(key).view
          if previousKey != null &&
            java.util.Arrays.compareUnsigned(previousKey, keyBytes) >= 0
          then
            throw new DurableDecodeError(
              "map keys are not in strictly ascending canonical order"
            )
          previousKey = keyBytes
          val value = valueCodec.read(reader)
          builder += (key -> value)
          index += 1
        builder.result()
