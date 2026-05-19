package scalascript.blockchain.cardano

/** Bech32 encoder/decoder (BIP-0173, without the 90-char length limit).
 *  Used for Cardano address encoding (CIP-19). */
object Bech32:

  private val Charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
  private val CharsetRev: Array[Int] =
    val a = Array.fill(128)(-1)
    Charset.zipWithIndex.foreach { case (c, i) => a(c.toInt) = i }
    a

  private val Generator = Array(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

  def encode(hrp: String, data: Array[Byte]): String =
    val conv    = convertBits(data, 8, 5, pad = true).map(_ & 0xff)
    val payload = conv ++ checksum(hrp, conv)
    hrp + "1" + payload.map(Charset).mkString

  def decode(bech32: String): Either[String, Array[Byte]] =
    val lower = bech32.toLowerCase
    val pos   = lower.lastIndexOf('1')
    if pos < 1 || pos + 7 > lower.length then return Left("Invalid separator position")
    val hrp  = lower.take(pos)
    val data = lower.drop(pos + 1)
    if data.exists(_.toInt >= 128) then return Left("Non-ASCII char")
    val values =
      try data.map(c => CharsetRev(c.toInt))
      catch case _: Exception => return Left("Invalid character")
    if values.exists(_ == -1) then return Left("Invalid bech32 character")
    if polymod(hrpExpand(hrp) ++ values) != 1 then return Left("Invalid checksum")
    convertBits(values.dropRight(6).toArray, 5, 8, pad = false) match
      case null => Left("Invalid data bits")
      case out  => Right(out)

  private def hrpExpand(hrp: String): Array[Int] =
    hrp.map(_.toInt >> 5).toArray ++ Array(0) ++ hrp.map(_.toInt & 31).toArray

  private def polymod(values: Array[Int]): Int =
    var c = 1
    for v <- values do
      val c0 = c >>> 25
      c = ((c & 0x1ffffff) << 5) ^ v
      for i <- 0 until 5 do
        if ((c0 >> i) & 1) != 0 then c ^= Generator(i)
    c

  private def checksum(hrp: String, data: Array[Int]): Array[Int] =
    val values = hrpExpand(hrp) ++ data ++ Array(0, 0, 0, 0, 0, 0)
    val poly   = polymod(values) ^ 1
    (0 until 6).map(i => (poly >> (5 * (5 - i))) & 31).toArray

  private def convertBits(data: Array[Byte], fromBits: Int, toBits: Int, pad: Boolean): Array[Byte] =
    convertBitsInt(data.map(_ & 0xff), fromBits, toBits, pad)

  private def convertBits(data: Array[Int], fromBits: Int, toBits: Int, pad: Boolean): Array[Byte] =
    convertBitsInt(data, fromBits, toBits, pad)

  private def convertBitsInt(data: Array[Int], fromBits: Int, toBits: Int, pad: Boolean): Array[Byte] =
    var acc   = 0
    var bits  = 0
    val buf   = scala.collection.mutable.ArrayBuffer.empty[Byte]
    val maxv  = (1 << toBits) - 1
    for v <- data do
      acc   = (acc << fromBits) | v
      bits += fromBits
      while bits >= toBits do
        bits -= toBits
        buf += ((acc >> bits) & maxv).toByte
    if pad then
      if bits > 0 then buf += ((acc << (toBits - bits)) & maxv).toByte
    else if bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0 then
      return null
    buf.toArray
