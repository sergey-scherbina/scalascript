package ssc.plugin

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import ssc.Value

/** Deterministic bootstrap codec for content values embedded in build-jvm JARs.
 *  This is an artifact-value format, not a user-facing data parser. */
object NativeContentCodec:
  private val Magic = "SSC-CONTENT-1"
  private val MaxCollection = 1_000_000
  private val MaxStringBytes = 16 * 1024 * 1024

  def encode(modules: List[NativeContentModule]): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val out = new DataOutputStream(bytes)
    writeString(out, Magic)
    out.writeInt(modules.length)
    modules.foreach { module =>
      writeString(out, module.source)
      out.writeBoolean(module.explicitRoot)
      out.writeInt(module.directImports.length)
      module.directImports.foreach(writeString(out, _))
      writeString(out, module.namespace)
      writeValue(out, module.document)
    }
    out.flush()
    bytes.toByteArray

  def decode(bytes: Array[Byte]): List[NativeContentModule] =
    val in = new DataInputStream(new ByteArrayInputStream(bytes))
    val magic = readString(in)
    if magic != Magic then throw new IllegalStateException(s"unsupported native content artifact format: $magic")
    val count = readCount(in, "content module")
    val modules = List.fill(count) {
      val source = readString(in)
      val explicitRoot = in.readBoolean()
      val importCount = readCount(in, "content import")
      val directImports = List.fill(importCount)(readString(in))
      val namespace = readString(in)
      NativeContentModule(source, explicitRoot, directImports, namespace, readValue(in))
    }
    if in.read() != -1 then throw new IllegalStateException("trailing bytes in native content artifact")
    modules

  private def writeString(out: DataOutputStream, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    out.writeInt(bytes.length)
    out.write(bytes)

  private def readString(in: DataInputStream): String =
    val length = in.readInt()
    if length < 0 || length > MaxStringBytes then
      throw new IllegalStateException(s"invalid native content string length: $length")
    new String(in.readNBytes(length), StandardCharsets.UTF_8)

  private def writeValue(out: DataOutputStream, value: Value): Unit = value match
    case Value.UnitV => out.writeByte(0)
    case Value.BoolV(boolean) => out.writeByte(1); out.writeBoolean(boolean)
    case Value.IntV(number) => out.writeByte(2); out.writeLong(number)
    case Value.BigV(number) => out.writeByte(3); writeString(out, number.toString)
    case Value.FloatV(number) => out.writeByte(4); out.writeDouble(number)
    case Value.StrV(text) => out.writeByte(5); writeString(out, text)
    case Value.DecimalV(text) => out.writeByte(6); writeString(out, text)
    case Value.DataV(tag, fields) =>
      out.writeByte(7); writeString(out, tag); out.writeInt(fields.length); fields.foreach(writeValue(out, _))
    case Value.MapV(entries) =>
      out.writeByte(8); out.writeInt(entries.size)
      entries.foreach { case (key, fieldValue) => writeValue(out, key); writeValue(out, fieldValue) }
    case other => throw new IllegalArgumentException(s"native content contains unsupported runtime value: ${other.getClass.getName}")

  private def readValue(in: DataInputStream): Value = in.readUnsignedByte() match
    case 0 => Value.UnitV
    case 1 => Value.BoolV(in.readBoolean())
    case 2 => Value.IntV(in.readLong())
    case 3 => Value.BigV(BigInt(readString(in)))
    case 4 => Value.FloatV(in.readDouble())
    case 5 => Value.StrV(readString(in))
    case 6 => Value.DecimalV(readString(in))
    case 7 =>
      val tag = readString(in)
      val count = readCount(in, s"$tag field")
      Value.DataV(tag, Vector.fill(count)(readValue(in)))
    case 8 =>
      val count = readCount(in, "content map entry")
      Value.MapV.from(List.fill(count)(readValue(in) -> readValue(in)))
    case tag => throw new IllegalStateException(s"unknown native content value tag: $tag")

  private def readCount(in: DataInputStream, label: String): Int =
    val count = in.readInt()
    if count < 0 || count > MaxCollection then
      throw new IllegalStateException(s"invalid $label count: $count")
    count
