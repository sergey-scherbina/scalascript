package scalascript.blockchain.cosmos

/** Cosmos bech32 address derivation.
 *
 *  Cosmos addresses are bech32-encoded with a chain-specific HRP:
 *  - `"cosmos"` — Cosmos Hub
 *  - `"osmo"`   — Osmosis
 *  - `"juno"`   — Juno
 *  - any other CAIP-compatible HRP for other Cosmos SDK chains
 *
 *  The payload is hash160(compressedPubKey) — 20 bytes — converted to
 *  5-bit groups and checksumed with Bech32 (not Bech32m; Cosmos predates BIP-350).
 *
 *  Encoding algorithm: standard Bech32 per BIP-173 but without the
 *  witness-version prefix used by SegWit — just the raw 20-byte key hash. */
object CosmosAddress:

  private val Charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
  private val CharsetRev: Array[Int] =
    val a = Array.fill(128)(-1)
    Charset.zipWithIndex.foreach { case (c, i) => a(c.toInt) = i }
    a

  private val Generator = Array(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

  // Bech32 constant (not Bech32m)
  private val Const = 1

  /** Derive a Cosmos bech32 address from a 33-byte compressed secp256k1 public key. */
  def deriveAddress(compressedPubKey: Array[Byte], hrp: String): String =
    require(compressedPubKey.length == 33,
      s"Cosmos address requires 33-byte compressed pubkey, got ${compressedPubKey.length}")
    val keyHash = CosmosCrypto.hash160(compressedPubKey)
    encode(hrp, keyHash)

  /** Bech32-encode raw bytes with the given HRP (no witness version prefix). */
  def encode(hrp: String, data: Array[Byte]): String =
    val conv    = convertBits(data.map(_ & 0xff), 8, 5, pad = true)
    val convInt = conv.map(_ & 0xff)
    val cs      = checksum(hrp, convInt)
    hrp + "1" + (convInt ++ cs).map(Charset).mkString

  /** Decode a bech32 address. Returns `(hrp, data)` or Left on error. */
  def decode(bech32: String): Either[String, (String, Array[Byte])] =
    val lower = bech32.toLowerCase
    val pos   = lower.lastIndexOf('1')
    if pos < 1 || pos + 7 > lower.length then return Left("Invalid separator position")
    val hrp   = lower.take(pos)
    val data  = lower.drop(pos + 1)
    if data.exists(_.toInt >= 128) then return Left("Non-ASCII character")
    val values = data.map(c =>
      if c.toInt < 128 then CharsetRev(c.toInt)
      else -1
    )
    if values.exists(_ == -1) then return Left("Invalid bech32 character")
    if polymod(hrpExpand(hrp) ++ values.toArray) != Const then return Left("Invalid checksum")
    val payload = convertBits(values.dropRight(6).toArray, 5, 8, pad = false)
    if payload == null then Left("Invalid padding")
    else Right((hrp, payload))

  // ── internals ──────────────────────────────────────────────────────────────

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

  private def checksum(hrp: String, data: Array[Int]): Array[Int] =
    val values = hrpExpand(hrp) ++ data ++ Array(0, 0, 0, 0, 0, 0)
    val poly   = polymod(values) ^ Const
    (0 until 6).map(i => (poly >> (5 * (5 - i))) & 31).toArray

  private def convertBits(data: Array[Int], fromBits: Int, toBits: Int, pad: Boolean): Array[Byte] =
    var acc  = 0
    var bits = 0
    val buf  = scala.collection.mutable.ArrayBuffer.empty[Byte]
    val maxv = (1 << toBits) - 1
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
