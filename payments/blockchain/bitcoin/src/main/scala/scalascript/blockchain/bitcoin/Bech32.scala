package scalascript.blockchain.bitcoin

/** Bech32 and Bech32m encoder/decoder per BIP-173 and BIP-350.
 *
 *  - SegWit v0 (P2WPKH, P2WSH) uses Bech32 (constant 1 in polymod).
 *  - SegWit v1+ (Taproot / P2TR) uses Bech32m (constant 0x2bc830a3 in polymod).
 *
 *  Bech32 has no length limit — addresses up to 90+ chars are allowed. */
object Bech32:

  private val Charset    = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
  private val CharsetRev: Array[Int] =
    val a = Array.fill(128)(-1)
    Charset.zipWithIndex.foreach { case (c, i) => a(c.toInt) = i }
    a

  private val Generator = Array(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

  private val Bech32Const  = 1
  private val Bech32mConst = 0x2bc830a3

  private def polymod(values: Array[Int]): Int =
    var c = 1
    for v <- values do
      val c0 = c >>> 25
      c = ((c & 0x1ffffff) << 5) ^ v
      for i <- 0 until 5 do
        if ((c0 >> i) & 1) != 0 then c ^= Generator(i)
    c

  private def hrpExpand(hrp: String): Array[Int] =
    hrp.map(_.toInt >> 5).toArray ++ Array(0) ++ hrp.map(_.toInt & 31).toArray

  private def checksum(hrp: String, data: Array[Int], const: Int): Array[Int] =
    val values = hrpExpand(hrp) ++ data ++ Array(0, 0, 0, 0, 0, 0)
    val poly   = polymod(values) ^ const
    (0 until 6).map(i => (poly >> (5 * (5 - i))) & 31).toArray

  // ── encode ─────────────────────────────────────────────────────────────────

  /** Encode `data` bytes with `hrp` using Bech32 (SegWit v0). */
  def encode(hrp: String, data: Array[Byte]): String =
    encodeVariant(hrp, data, Bech32Const)

  /** Encode `data` bytes with `hrp` using Bech32m (SegWit v1+/Taproot). */
  def encodem(hrp: String, data: Array[Byte]): String =
    encodeVariant(hrp, data, Bech32mConst)

  private def encodeVariant(hrp: String, data: Array[Byte], const: Int): String =
    val conv    = convertBits(data.map(_ & 0xff), 8, 5, pad = true).map(_ & 0xff)
    val payload = conv ++ checksum(hrp, conv, const)
    hrp + "1" + payload.map(Charset).mkString

  // ── SegWit address helpers ─────────────────────────────────────────────────

  /** Encode a SegWit address from witness version and program bytes.
   *  v0 uses Bech32; v1+ uses Bech32m. */
  def encodeSegWit(hrp: String, witnessVersion: Int, program: Array[Byte]): String =
    val conv    = convertBits(program.map(_ & 0xff), 8, 5, pad = true).map(_ & 0xff)
    val payload = Array(witnessVersion & 0x1f) ++ conv
    val const   = if witnessVersion == 0 then Bech32Const else Bech32mConst
    hrp + "1" + (payload ++ checksum(hrp, payload, const)).map(Charset).mkString

  /** Decode a SegWit address. Returns `(witnessVersion, program)` or Left on error. */
  def decodeSegWit(addr: String): Either[String, (Int, Array[Byte])] =
    val lower = addr.toLowerCase
    val pos   = lower.lastIndexOf('1')
    if pos < 1 || pos + 7 > lower.length then return Left("Invalid separator position")
    val hrp  = lower.take(pos)
    val data = lower.drop(pos + 1)
    if data.exists(_.toInt >= 128) then return Left("Non-ASCII char")
    val values =
      try data.map(c => CharsetRev(c.toInt))
      catch case _: Exception => return Left("Invalid character")
    if values.exists(_ == -1) then return Left("Invalid bech32 character")
    if values.length < 7 then return Left("Too short")
    val witnessVer = values(0)
    if witnessVer > 16 then return Left("Invalid witness version")
    val const = if witnessVer == 0 then Bech32Const else Bech32mConst
    if polymod(hrpExpand(hrp) ++ values) != const then return Left("Invalid checksum")
    val prog5  = values.drop(1).dropRight(6).toArray
    convertBits(prog5, 5, 8, pad = false) match
      case null => Left("Invalid padding in witness program")
      case prog =>
        if prog.length < 2 || prog.length > 40 then Left("Invalid witness program length")
        else if witnessVer == 0 && prog.length != 20 && prog.length != 32 then
          Left("Invalid v0 witness program length")
        else Right((witnessVer, prog))

  // ── plain Bech32 decode (non-SegWit) ──────────────────────────────────────

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
    if polymod(hrpExpand(hrp) ++ values) != Bech32Const then return Left("Invalid checksum")
    convertBits(values.dropRight(6).toArray, 5, 8, pad = false) match
      case null => Left("Invalid data bits")
      case out  => Right(out)

  // ── bit-conversion ─────────────────────────────────────────────────────────

  private def convertBits(data: Array[Int], fromBits: Int, toBits: Int, pad: Boolean): Array[Byte] =
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
