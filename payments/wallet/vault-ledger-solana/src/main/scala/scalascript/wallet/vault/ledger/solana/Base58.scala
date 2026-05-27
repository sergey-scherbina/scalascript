package scalascript.wallet.vault.ledger.solana

/** Lightweight Base58 encoder using the Bitcoin / Solana alphabet.
 *
 *  Alphabet: `123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz`
 *  (58 chars; 0, O, I, l removed to avoid visual confusion).
 *
 *  No external dependencies — pure Scala big-integer arithmetic. */
object Base58:

  private val Alphabet: String =
    "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

  private val AlphabetChars: Array[Char] = Alphabet.toCharArray

  private val Base: BigInt = BigInt(58)

  /** Encode `bytes` to a Base58 string. Leading zero bytes are
   *  represented as leading `'1'` characters. */
  def encode(bytes: Array[Byte]): String =
    if bytes.isEmpty then return ""
    // Count leading zero bytes.
    val leadingZeros = bytes.takeWhile(_ == 0).length
    // Convert bytes to a non-negative big integer (treat as unsigned).
    // Prepend 0x00 to ensure BigInt treats it as positive.
    val padded = 0x00.toByte +: bytes
    var n      = BigInt(padded)
    // Encode to Base58.
    val sb = new StringBuilder
    while n > BigInt(0) do
      val (q, r) = n /% Base
      sb.append(AlphabetChars(r.toInt))
      n = q
    // Add '1' characters for each leading zero byte.
    (0 until leadingZeros).foreach(_ => sb.append('1'))
    sb.reverse.toString

  /** Decode a Base58 string back to bytes.
   *  Throws [[IllegalArgumentException]] for invalid characters. */
  def decode(s: String): Array[Byte] =
    if s.isEmpty then return Array.emptyByteArray
    // Count leading '1' characters (each represents a leading zero byte).
    val leadingOnes = s.takeWhile(_ == '1').length
    // Decode characters to a big integer.
    var n = BigInt(0)
    for ch <- s do
      val idx = Alphabet.indexOf(ch)
      require(idx >= 0, s"Invalid Base58 character: '$ch'")
      n = n * Base + BigInt(idx)
    // Convert big integer to bytes, stripping the sign byte.
    val raw = n.toByteArray.dropWhile(_ == 0)
    // Prepend leading zero bytes.
    Array.fill[Byte](leadingOnes)(0) ++ raw
