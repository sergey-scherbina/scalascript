package scalascript.wallet.connector.walletstd

/** Minimal subset of the Solana legacy-message wire protocol needed by
 *  the Wallet Standard connector for cross-compile.  Inlined here (vs.
 *  depending on `blockchain-solana`, which is JVM-only today) so the
 *  shared connector code compiles unmodified on both JVM and JS.
 *
 *  Bit-compatible with `scalascript.blockchain.solana.SolanaMessage` —
 *  the JVM-side platform extension translates back and forth as needed
 *  at the `ChainAdapter.Tx`/`SignedTx` boundary.  See
 *  `specs/wallet-spi-scalajs.md` § Stage 3 for the rationale. */

/** Solana legacy message body — the bytes that get ed25519-signed. */
case class SolanaMessage(
  numRequiredSignatures:       Int,
  numReadonlySignedAccounts:   Int,
  numReadonlyUnsignedAccounts: Int,
  /** Account keys ordered: writable signers first, then read-only
   *  signers, then writable non-signers, then read-only non-signers.
   *  The first one is the fee payer.  Each key is a 32-byte ed25519
   *  public key. */
  accountKeys:     Seq[Array[Byte]],
  /** 32-byte recent blockhash. */
  recentBlockhash: Array[Byte],
  instructions:    Seq[SolanaInstruction],
):
  /** Serialise to the canonical wire format the underlying chain
   *  adapter signs over.  Pure byte-pushing — uses an `Array[Byte]`
   *  builder so the same code links on Scala.js. */
  def serialize: Array[Byte] =
    val buf = scala.collection.mutable.ArrayBuffer.empty[Byte]
    buf += numRequiredSignatures.toByte
    buf += numReadonlySignedAccounts.toByte
    buf += numReadonlyUnsignedAccounts.toByte
    CompactU16.encode(accountKeys.size).foreach(buf += _)
    accountKeys.foreach { k =>
      require(k.length == 32, s"account key must be 32 bytes, got ${k.length}")
      k.foreach(buf += _)
    }
    require(recentBlockhash.length == 32, "recent blockhash must be 32 bytes")
    recentBlockhash.foreach(buf += _)
    CompactU16.encode(instructions.size).foreach(buf += _)
    instructions.foreach { ix =>
      buf += (ix.programIdIndex & 0xff).toByte
      CompactU16.encode(ix.accountIndexes.length).foreach(buf += _)
      ix.accountIndexes.foreach(buf += _)
      CompactU16.encode(ix.data.length).foreach(buf += _)
      ix.data.foreach(buf += _)
    }
    buf.toArray

case class SolanaInstruction(
  programIdIndex: Int,
  accountIndexes: Array[Byte],
  data:           Array[Byte],
)

/** Compact-u16 ("short_vec_length") varint: 1–3 bytes, top-bit
 *  continuation. */
object CompactU16:

  def encode(n: Int): Array[Byte] =
    require(n >= 0 && n <= 0xffff, s"compact-u16 out of range: $n")
    if n < 0x80 then Array(n.toByte)
    else if n < 0x4000 then
      Array(((n & 0x7f) | 0x80).toByte, ((n >> 7) & 0x7f).toByte)
    else
      Array(
        ((n & 0x7f)        | 0x80).toByte,
        (((n >> 7)  & 0x7f) | 0x80).toByte,
        ((n >> 14) & 0x3).toByte,
      )

  /** Decode the compact-u16 prefix from `bytes` starting at `offset`.
   *  Returns (value, bytesConsumed). */
  def decode(bytes: Array[Byte], offset: Int): (Int, Int) =
    val b0 = bytes(offset) & 0xff
    if (b0 & 0x80) == 0 then (b0, 1)
    else
      val b1 = bytes(offset + 1) & 0xff
      val v01 = (b0 & 0x7f) | ((b1 & 0x7f) << 7)
      if (b1 & 0x80) == 0 then (v01, 2)
      else
        val b2 = bytes(offset + 2) & 0xff
        (v01 | ((b2 & 0x03) << 14), 3)

/** Base58 (Bitcoin / Solana alphabet) — `BigInt` powers the big-integer
 *  arithmetic so the same code works on JVM and JS. */
object Base58:

  private val Alphabet =
    "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray
  private val Base = BigInt(58)
  private val DecodeTable: Array[Int] =
    val out = Array.fill(128)(-1)
    var i = 0
    while i < Alphabet.length do
      out(Alphabet(i).toInt) = i
      i += 1
    out

  def encode(bytes: Array[Byte]): String =
    if bytes.isEmpty then return ""
    var zeros = 0
    while zeros < bytes.length && bytes(zeros) == 0 do zeros += 1
    // Force positive interpretation: prepend a 0 byte so BigInt reads
    // the value as unsigned.
    val padded = 0.toByte +: bytes
    var num = BigInt(padded.toArray)
    val sb  = new StringBuilder
    while num.signum > 0 do
      val (q, r) = num /% Base
      num = q
      sb.append(Alphabet(r.toInt))
    var i = 0
    while i < zeros do { sb.append(Alphabet(0)); i += 1 }
    sb.reverse.toString

  def decode(s: String): Array[Byte] =
    if s.isEmpty then return Array.emptyByteArray
    var zeros = 0
    while zeros < s.length && s.charAt(zeros) == Alphabet(0) do zeros += 1
    var num = BigInt(0)
    var i = 0
    while i < s.length do
      val c = s.charAt(i).toInt
      val v = if c < 128 then DecodeTable(c) else -1
      if v < 0 then throw new IllegalArgumentException(s"Invalid base58 character '${s.charAt(i)}' at $i")
      num = num * Base + BigInt(v)
      i += 1
    val raw = num.toByteArray
    // BigInt.toByteArray prepends 0x00 for positive values whose top
    // bit would otherwise look negative — strip that byte.
    val body =
      if raw.length > 1 && raw(0) == 0 then raw.drop(1)
      else if num.signum == 0 then Array.emptyByteArray
      else raw
    val out = new Array[Byte](zeros + body.length)
    System.arraycopy(body, 0, out, zeros, body.length)
    out
