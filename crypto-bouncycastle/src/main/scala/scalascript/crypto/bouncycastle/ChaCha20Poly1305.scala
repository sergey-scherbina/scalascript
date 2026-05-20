package scalascript.crypto.bouncycastle

import javax.crypto.{AEADBadTagException, Cipher}
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}

import scalascript.crypto.CryptoIntegrityException

/** JVM-side ChaCha20-Poly1305 AEAD via the JCE provider that ships with
 *  every modern JDK (≥ 11).  Layout: `encrypt` returns
 *  `ciphertext || 16B Poly1305 tag` (matching the JCE ChaCha20-Poly1305
 *  provider's default); `decrypt` strips the tag, verifies it, and
 *  rethrows tag mismatches as [[CryptoIntegrityException]] so the SPI
 *  is platform-independent. */
private[bouncycastle] object ChaCha20Poly1305:

  def encrypt(
    key: Array[Byte],
    nonce: Array[Byte],
    plaintext: Array[Byte],
    aad: Array[Byte],
  ): Array[Byte] =
    require(key.length == 32, s"ChaCha20-Poly1305 key must be 32 B, got ${key.length}")
    require(nonce.length == 12, s"ChaCha20-Poly1305 nonce must be 12 B, got ${nonce.length}")
    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce))
    if aad != null && aad.length > 0 then cipher.updateAAD(aad)
    cipher.doFinal(plaintext)

  def decrypt(
    key: Array[Byte],
    nonce: Array[Byte],
    ciphertext: Array[Byte],
    aad: Array[Byte],
  ): Array[Byte] =
    require(key.length == 32, s"ChaCha20-Poly1305 key must be 32 B, got ${key.length}")
    require(nonce.length == 12, s"ChaCha20-Poly1305 nonce must be 12 B, got ${nonce.length}")
    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce))
    if aad != null && aad.length > 0 then cipher.updateAAD(aad)
    try cipher.doFinal(ciphertext)
    catch
      case e: AEADBadTagException =>
        throw new CryptoIntegrityException("ChaCha20-Poly1305 tag mismatch", e)
