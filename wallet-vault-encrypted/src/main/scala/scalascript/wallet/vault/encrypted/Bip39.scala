package scalascript.wallet.vault.encrypted

import java.security.{MessageDigest, SecureRandom}
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** BIP-39 mnemonic generation and seed derivation.
 *
 *  Entropy sizes supported: 16 B (12 words), 24 B (18 words), 32 B (24 words).
 *  Seed derivation uses PBKDF2-HMAC-SHA512 with 2048 iterations per spec. */
object Bip39:

  private lazy val wordlist: IndexedSeq[String] =
    val stream = getClass.getResourceAsStream("/bip39-english.txt")
    require(stream != null, "BIP-39 wordlist resource not found")
    val words = scala.io.Source.fromInputStream(stream, "UTF-8").getLines()
      .map(_.trim).filter(_.nonEmpty).toIndexedSeq
    require(words.size == 2048, s"BIP-39 wordlist must have 2048 words, got ${words.size}")
    words

  /** Generate a mnemonic from fresh random entropy (32 bytes = 24 words by default). */
  def generate(entropyBytes: Array[Byte] = randomEntropy(32)): Mnemonic =
    require(
      Seq(16, 24, 32).contains(entropyBytes.length),
      s"Entropy must be 16, 24, or 32 bytes; got ${entropyBytes.length}",
    )
    val csBitCount = entropyBytes.length / 4
    val hash       = sha256(entropyBytes)
    val entBits    = entropyBytes.flatMap(b => (7 to 0 by -1).map(i => (b >> i) & 1)).toSeq
    val csBits     = (7 to (8 - csBitCount) by -1).map(i => (hash(0) >> i) & 1).toSeq
    val indices    = (entBits ++ csBits).grouped(11).map(g => g.foldLeft(0)(_ * 2 + _)).toSeq
    Mnemonic(indices.map(wordlist))

  /** Parse a mnemonic phrase; validates checksum. */
  def fromPhrase(phrase: String): Either[String, Mnemonic] =
    fromWords(phrase.trim.split("\\s+").toSeq)

  def fromWords(words: Seq[String]): Either[String, Mnemonic] =
    val wl = wordlist
    val unknown = words.filterNot(wl.contains)
    if unknown.nonEmpty then
      return Left(s"Unknown BIP-39 words: ${unknown.take(3).mkString(", ")}")
    if !Seq(12, 15, 18, 21, 24).contains(words.size) then
      return Left(s"Invalid word count ${words.size}; expected 12/15/18/21/24")
    val indices = words.map(wl.indexOf)
    val allBits = indices.flatMap(i => (10 to 0 by -1).map(j => (i >> j) & 1)).toArray
    val csBits  = words.size / 3
    val entBits = allBits.length - csBits
    val entBytes = allBits.take(entBits).grouped(8)
      .map(g => g.foldLeft(0)(_ * 2 + _).toByte).toArray
    val hash     = sha256(entBytes)
    val expected = (0 until csBits).map(i => (hash(0) >> (7 - i)) & 1)
    val actual   = allBits.drop(entBits).toSeq
    if expected != actual then Left("BIP-39 checksum invalid")
    else Right(Mnemonic(words))

  def randomEntropy(lenBytes: Int = 32): Array[Byte] =
    val buf = new Array[Byte](lenBytes)
    new SecureRandom().nextBytes(buf)
    buf

  private def sha256(data: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(data)

/** An immutable BIP-39 mnemonic phrase.
 *  `toString` never leaks the words; use `phrase` explicitly. */
case class Mnemonic(words: Seq[String]):
  def phrase: String = words.mkString(" ")

  /** BIP-39 seed derivation: PBKDF2-HMAC-SHA512(phrase, "mnemonic"+passphrase, 2048, 64). */
  def toSeed(passphrase: String = ""): Array[Byte] =
    val salt = ("mnemonic" + passphrase).getBytes("UTF-8")
    val spec = new PBEKeySpec(phrase.toCharArray, salt, 2048, 512)
    val skf  = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    try skf.generateSecret(spec).getEncoded finally spec.clearPassword()

  override def toString: String    = "Mnemonic(***)"
  override def hashCode(): Int     = java.util.Arrays.hashCode(words.toArray[AnyRef])
  override def equals(o: Any): Boolean = o match
    case m: Mnemonic => words == m.words
    case _           => false
