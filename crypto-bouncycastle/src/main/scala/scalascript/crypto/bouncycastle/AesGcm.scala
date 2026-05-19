package scalascript.crypto.bouncycastle

import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

private[bouncycastle] object AesGcm:

  private val TagBits = 128

  def encrypt(key: Array[Byte], iv: Array[Byte], plaintext: Array[Byte], aad: Array[Byte]): Array[Byte] =
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TagBits, iv))
    if aad.nonEmpty then c.updateAAD(aad)
    c.doFinal(plaintext)

  def decrypt(key: Array[Byte], iv: Array[Byte], ciphertext: Array[Byte], aad: Array[Byte]): Array[Byte] =
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TagBits, iv))
    if aad.nonEmpty then c.updateAAD(aad)
    c.doFinal(ciphertext)
