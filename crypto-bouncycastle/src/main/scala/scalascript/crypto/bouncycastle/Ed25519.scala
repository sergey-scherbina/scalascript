package scalascript.crypto.bouncycastle

import org.bouncycastle.crypto.params.{Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters}
import org.bouncycastle.crypto.signers.Ed25519Signer

import scalascript.crypto.HashAlgo

/** Ed25519 primitives. Signature encoding: 64 bytes per RFC 8032 (no
 *  recovery — Ed25519 doesn't support `ecrecover`). The internal hash
 *  is SHA-512 and is performed by the signer itself; callers must
 *  pass `HashAlgo.None` and the raw message. */
private[bouncycastle] object Ed25519:

  def derivePublic(privKey: Array[Byte]): Array[Byte] =
    val sk = new Ed25519PrivateKeyParameters(privKey, 0)
    sk.generatePublicKey().getEncoded

  def sign(privKey: Array[Byte], msg: Array[Byte], hash: HashAlgo): Array[Byte] =
    if hash != HashAlgo.None then
      throw new IllegalArgumentException(
        s"Ed25519 hashes internally; pass HashAlgo.None (got $hash)"
      )
    val signer = new Ed25519Signer()
    signer.init(true, new Ed25519PrivateKeyParameters(privKey, 0))
    signer.update(msg, 0, msg.length)
    signer.generateSignature()

  def verify(pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte], hash: HashAlgo): Boolean =
    if hash != HashAlgo.None then return false
    if sig.length != 64 then return false
    val signer = new Ed25519Signer()
    signer.init(false, new Ed25519PublicKeyParameters(pubKey, 0))
    signer.update(msg, 0, msg.length)
    signer.verifySignature(sig)
