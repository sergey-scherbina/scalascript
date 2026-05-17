package scalascript.server

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** PBKDF2-HMAC-SHA256 password hashing.
 *
 *  Encoded format (Django-style, self-describing so we can swap the
 *  underlying KDF without rotating every stored hash at once):
 *
 *      pbkdf2$iter=<N>$<b64_salt>$<b64_hash>
 *
 *  Default work factor: 200_000 PBKDF2 iterations + 16-byte salt +
 *  32-byte output.  OWASP's 2023 recommendation for PBKDF2-SHA256 is
 *  600k iterations; we pick a lower default so the demo runs are fast
 *  but call sites can override via [[hash]]'s `iter` argument.
 *
 *  `verify` uses [[MessageDigest.isEqual]] for constant-time hash
 *  comparison.  Malformed encodings return `false` instead of raising
 *  so a corrupted database row can't crash the login path. */
object Password:
  private val DefaultIterations = 200_000
  private val SaltBytes         = 16
  private val HashBits          = 256
  private val rng               = SecureRandom()
  private val b64Enc            = Base64.getEncoder.withoutPadding
  private val b64Dec            = Base64.getDecoder

  def hash(password: String, iter: Int = DefaultIterations): String =
    val salt = new Array[Byte](SaltBytes)
    rng.nextBytes(salt)
    val key = pbkdf2(password, salt, iter, HashBits)
    s"pbkdf2$$iter=$iter$$${b64Enc.encodeToString(salt)}$$${b64Enc.encodeToString(key)}"

  def verify(password: String, encoded: String): Boolean =
    try
      val parts = encoded.split('$')
      if parts.length != 4 || parts(0) != "pbkdf2" then false
      else
        val iter = parts(1).stripPrefix("iter=").toInt
        val salt = b64Dec.decode(parts(2))
        val expected = b64Dec.decode(parts(3))
        val actual   = pbkdf2(password, salt, iter, expected.length * 8)
        MessageDigest.isEqual(expected, actual)
    catch case _: Throwable => false

  private def pbkdf2(password: String, salt: Array[Byte], iter: Int, bits: Int): Array[Byte] =
    val spec    = PBEKeySpec(password.toCharArray, salt, iter, bits)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    try factory.generateSecret(spec).getEncoded
    finally spec.clearPassword()
