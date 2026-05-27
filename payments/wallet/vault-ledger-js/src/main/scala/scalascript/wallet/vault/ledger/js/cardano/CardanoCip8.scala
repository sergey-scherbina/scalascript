package scalascript.wallet.vault.ledger.js.cardano

object CardanoCip8:
  sealed trait Cbor
  final case class UInt(n: Long) extends Cbor
  final case class NInt(encoded: Long) extends Cbor
  final case class Bytes(value: Array[Byte]) extends Cbor
  final case class Text(value: String) extends Cbor
  final case class Arr(items: Seq[Cbor]) extends Cbor
  final case class Map(items: Seq[(Cbor, Cbor)]) extends Cbor
  final case class Tagged(tag: Long, value: Cbor) extends Cbor

  def protectedHeader(address: Array[Byte]): Array[Byte] =
    encode(Map(Seq(UInt(1) -> NInt(7), Text("address") -> Bytes(address))))

  def sigStructure(protectedHeader: Array[Byte], payload: Array[Byte]): Array[Byte] =
    encode(Arr(Seq(Text("Signature1"), Bytes(protectedHeader), Bytes(Array.emptyByteArray), Bytes(payload))))

  def coseSign1(protectedHeader: Array[Byte], payload: Array[Byte], signature: Array[Byte]): Array[Byte] =
    encode(Tagged(18, Arr(Seq(Bytes(protectedHeader), Map(Nil), Bytes(payload), Bytes(signature)))))

  def coseKeyEd25519(publicKey: Array[Byte]): Array[Byte] =
    encode(Map(Seq(UInt(1) -> UInt(1), UInt(3) -> NInt(7), UInt(4) -> UInt(1), NInt(1) -> UInt(6), NInt(2) -> Bytes(publicKey))))

  def encode(value: Cbor): Array[Byte] =
    val out = scala.collection.mutable.ArrayBuffer.empty[Byte]
    write(value, out)
    out.toArray

  private def write(value: Cbor, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit = value match
    case UInt(n) => head(0, n, out)
    case NInt(n) => head(1, n, out)
    case Bytes(bytes) => head(2, bytes.length, out); out ++= bytes
    case Text(s) =>
      val bytes = s.getBytes("UTF-8")
      head(3, bytes.length, out); out ++= bytes
    case Arr(items) => head(4, items.length, out); items.foreach(write(_, out))
    case Map(items) => head(5, items.length, out); items.foreach { case (k, v) => write(k, out); write(v, out) }
    case Tagged(tag, v) => head(6, tag, out); write(v, out)

  private def head(major: Int, arg: Long, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
    val mt = (major << 5).toByte
    if arg < 24 then out += (mt | arg.toByte).toByte
    else if arg < 256 then { out += (mt | 24).toByte; out += arg.toByte }
    else if arg < 65536 then { out += (mt | 25).toByte; out += (arg >> 8).toByte; out += arg.toByte }
    else if arg < 0x100000000L then
      out += (mt | 26).toByte
      out += (arg >> 24).toByte; out += (arg >> 16).toByte; out += (arg >> 8).toByte; out += arg.toByte
    else
      out += (mt | 27).toByte
      (7 to 0 by -1).foreach(i => out += ((arg >> (i * 8)) & 0xff).toByte)
