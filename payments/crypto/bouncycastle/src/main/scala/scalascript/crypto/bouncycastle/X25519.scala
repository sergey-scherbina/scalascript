package scalascript.crypto.bouncycastle

import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.{X25519KeyPairGenerator}
import org.bouncycastle.crypto.params.{X25519KeyGenerationParameters, X25519PrivateKeyParameters, X25519PublicKeyParameters}

/** JVM-side X25519 ECDH via BouncyCastle.  All three operations
 *  (`generateKeypair`, `publicKeyFromPrivate`, `deriveSharedSecret`)
 *  speak the 32-byte raw private / 32-byte raw public format that the
 *  cross-platform SPI promises. */
private[bouncycastle] object X25519:

  def generateKeypair(rng: SecureRandom = new SecureRandom()): (Array[Byte], Array[Byte]) =
    val gen = new X25519KeyPairGenerator()
    gen.init(new X25519KeyGenerationParameters(rng))
    val kp     = gen.generateKeyPair()
    val priv32 = kp.getPrivate.asInstanceOf[X25519PrivateKeyParameters].getEncoded
    val pub32  = kp.getPublic.asInstanceOf[X25519PublicKeyParameters].getEncoded
    (priv32, pub32)

  def publicKeyFromPrivate(priv32: Array[Byte]): Array[Byte] =
    require(priv32.length == 32, s"X25519 private key must be 32 B, got ${priv32.length}")
    new X25519PrivateKeyParameters(priv32, 0).generatePublicKey().getEncoded

  def deriveSharedSecret(selfPriv32: Array[Byte], peerPub32: Array[Byte]): Array[Byte] =
    require(selfPriv32.length == 32, s"X25519 priv must be 32 B, got ${selfPriv32.length}")
    require(peerPub32.length  == 32, s"X25519 pub must be 32 B, got ${peerPub32.length}")
    val agreement = new X25519Agreement()
    agreement.init(new X25519PrivateKeyParameters(selfPriv32, 0))
    val out = new Array[Byte](32)
    agreement.calculateAgreement(new X25519PublicKeyParameters(peerPub32, 0), out, 0)
    out
