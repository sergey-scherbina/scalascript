package scalascript.blockchain.solana

/** Base58 encoding/decoding (Bitcoin / Solana alphabet — no `0`,
 *  `O`, `I`, `l` to avoid visual ambiguity).
 *
 *  Implementation uses `java.math.BigInteger` for the big-integer
 *  arithmetic — small + self-contained. */
object Base58:

  private val Alphabet =
    "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray
  private val Base = java.math.BigInteger.valueOf(58)
  private val DecodeTable: Array[Int] =
    val out = Array.fill(128)(-1)
    var i = 0
    while i < Alphabet.length do
      out(Alphabet(i).toInt) = i
      i += 1
    out

  def encode(bytes: Array[Byte]): String =
    if bytes.isEmpty then return ""
    // Leading zero bytes get rendered as '1' chars.
    var zeros = 0
    while zeros < bytes.length && bytes(zeros) == 0 do zeros += 1
    var num = new java.math.BigInteger(1, bytes)   // force positive sign
    val sb  = new java.lang.StringBuilder()
    while num.signum() > 0 do
      val dm = num.divideAndRemainder(Base)
      num = dm(0)
      sb.append(Alphabet(dm(1).intValue()))
    var i = 0
    while i < zeros do { sb.append(Alphabet(0)); i += 1 }
    sb.reverse.toString

  def decode(s: String): Array[Byte] =
    if s.isEmpty then return Array.emptyByteArray
    var zeros = 0
    while zeros < s.length && s.charAt(zeros) == Alphabet(0) do zeros += 1
    var num = java.math.BigInteger.ZERO
    var i = 0
    while i < s.length do
      val c = s.charAt(i).toInt
      val v = if c < 128 then DecodeTable(c) else -1
      if v < 0 then throw new IllegalArgumentException(s"Invalid base58 character '${s.charAt(i)}' at $i")
      num = num.multiply(Base).add(java.math.BigInteger.valueOf(v.toLong))
      i += 1
    val raw = num.toByteArray
    // BigInteger.toByteArray prepends 0x00 for positive values whose
    // top bit would otherwise look negative — strip that byte.
    val body =
      if raw.length > 1 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, raw.length)
      else if num.signum() == 0 then Array.emptyByteArray
      else raw
    val out = new Array[Byte](zeros + body.length)
    System.arraycopy(body, 0, out, zeros, body.length)
    out
