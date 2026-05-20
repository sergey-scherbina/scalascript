package scalascript.crypto.bouncycastle

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.bouncycastle.crypto.digests.{SHA256Digest, SHA512Digest}
import org.bouncycastle.crypto.generators.{Argon2BytesGenerator, HKDFBytesGenerator}
import org.bouncycastle.crypto.params.{Argon2Parameters, HKDFParameters}

import scalascript.crypto.HashAlgo

private[bouncycastle] object Kdf:

  def pbkdf2(password: Array[Byte], salt: Array[Byte], iter: Int, lenBytes: Int, hash: HashAlgo): Array[Byte] =
    val algo = hash match
      case HashAlgo.Sha256 => "PBKDF2WithHmacSHA256"
      case HashAlgo.Sha512 => "PBKDF2WithHmacSHA512"
      case other           => throw new IllegalArgumentException(s"PBKDF2 not supported with $other")
    // PBEKeySpec expects char[]; encode password bytes as a hex-like string to round-trip
    // without losing octets above 0x7f. Caller is expected to pass UTF-8-encoded bytes —
    // in that case we decode to char[] preserving exact code points via ISO-8859-1.
    val pwChars = new String(password, java.nio.charset.StandardCharsets.ISO_8859_1).toCharArray
    val spec    = new PBEKeySpec(pwChars, salt, iter, lenBytes * 8)
    val factory = SecretKeyFactory.getInstance(algo)
    try factory.generateSecret(spec).getEncoded
    finally spec.clearPassword()

  def argon2id(password: Array[Byte], salt: Array[Byte], memKiB: Int, iter: Int, parallelism: Int, lenBytes: Int): Array[Byte] =
    val params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withVersion(Argon2Parameters.ARGON2_VERSION_13)
      .withIterations(iter)
      .withMemoryAsKB(memKiB)
      .withParallelism(parallelism)
      .withSalt(salt)
      .build()
    val gen = new Argon2BytesGenerator()
    gen.init(params)
    val out = new Array[Byte](lenBytes)
    gen.generateBytes(password, out)
    out

  def hkdf(ikm: Array[Byte], salt: Array[Byte], info: Array[Byte], lenBytes: Int, hash: HashAlgo): Array[Byte] =
    val digest = hash match
      case HashAlgo.Sha256 => new SHA256Digest()
      case HashAlgo.Sha512 => new SHA512Digest()
      case other           => throw new IllegalArgumentException(s"HKDF not supported with $other")
    val gen = new HKDFBytesGenerator(digest)
    gen.init(new HKDFParameters(ikm, salt, info))
    val out = new Array[Byte](lenBytes)
    gen.generateBytes(out, 0, lenBytes)
    out
