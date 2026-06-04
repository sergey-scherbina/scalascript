package scalascript.crypto.bouncycastle

import scalascript.crypto.*

/** Default JVM `CryptoBackend` implementation, backed by BouncyCastle
 *  for the curve / hash / KDF primitives and JCA (`AES/GCM/NoPadding`)
 *  for AEAD.
 *
 *  Registered via `META-INF/services/scalascript.crypto.CryptoBackend`,
 *  so just having `scalascript-crypto-bouncycastle` on the classpath
 *  is enough to make `CryptoBackend.get()` return an instance.
 *
 *  See docs/specs/blockchain-spi.md §4 for the SPI contract. */
final class BouncyCastleBackend extends CryptoBackend:

  def id: String = "bouncycastle-jvm"

  def supports(curve: Curve): Boolean = curve match
    case Curve.Secp256k1 | Curve.Ed25519 | Curve.P256 => true
    case _                                            => false

  // ── signing ──────────────────────────────────────────────────────────

  def sign(curve: Curve, privKey: Array[Byte], msg: Array[Byte], hash: HashAlgo): Array[Byte] =
    curve match
      case Curve.Secp256k1 => Secp256k1.sign(privKey, msg, hash)
      case Curve.Ed25519   => Ed25519.sign(privKey, msg, hash)
      case Curve.P256      => P256.sign(privKey, msg, hash)
      case other           => throw new UnsupportedOperationException(s"sign: curve not supported by $id: $other")

  def verify(curve: Curve, pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte], hash: HashAlgo): Boolean =
    curve match
      case Curve.Secp256k1 => Secp256k1.verify(pubKey, msg, sig, hash)
      case Curve.Ed25519   => Ed25519.verify(pubKey, msg, sig, hash)
      case Curve.P256      => P256.verify(pubKey, msg, sig, hash)
      case _               => false

  def derivePublic(curve: Curve, privKey: Array[Byte]): Array[Byte] =
    curve match
      case Curve.Secp256k1 => Secp256k1.derivePublic(privKey)
      case Curve.Ed25519   => Ed25519.derivePublic(privKey)
      case Curve.P256      => P256.derivePublic(privKey)
      case other           => throw new UnsupportedOperationException(s"derivePublic: curve not supported by $id: $other")

  def recoverPublic(curve: Curve, msgHash: Array[Byte], sig: Array[Byte], recId: Int): Array[Byte] =
    curve match
      case Curve.Secp256k1 => Secp256k1.recoverPublic(msgHash, sig, recId)
      case other           =>
        throw new UnsupportedOperationException(s"recoverPublic: not supported for $other (secp256k1 only)")

  // ── hashes ───────────────────────────────────────────────────────────

  def hash(algo: HashAlgo, data: Array[Byte]): Array[Byte] =
    Hashes.hash(algo, data)

  def hmac(algo: HashAlgo, key: Array[Byte], data: Array[Byte]): Array[Byte] =
    Hashes.hmac(algo, key, data)

  // ── HD derivation ────────────────────────────────────────────────────

  def deriveMaster(curve: Curve, seed: Array[Byte]): HdKey =
    HdDerivation.deriveMaster(curve, seed)

  def deriveChild(curve: Curve, parent: HdKey, index: Long, hardened: Boolean): HdKey =
    HdDerivation.deriveChild(curve, parent, index, hardened)

  // ── KDF ──────────────────────────────────────────────────────────────

  def pbkdf2(password: Array[Byte], salt: Array[Byte], iter: Int, len: Int, hash: HashAlgo): Array[Byte] =
    Kdf.pbkdf2(password, salt, iter, len, hash)

  def argon2id(password: Array[Byte], salt: Array[Byte], memKiB: Int, iter: Int, parallelism: Int, len: Int): Array[Byte] =
    Kdf.argon2id(password, salt, memKiB, iter, parallelism, len)

  def hkdf(ikm: Array[Byte], salt: Array[Byte], info: Array[Byte], len: Int, hash: HashAlgo): Array[Byte] =
    Kdf.hkdf(ikm, salt, info, len, hash)

  // ── AEAD ─────────────────────────────────────────────────────────────

  def aesGcmEncrypt(key: Array[Byte], iv: Array[Byte], plaintext: Array[Byte], aad: Array[Byte]): Array[Byte] =
    AesGcm.encrypt(key, iv, plaintext, aad)

  def aesGcmDecrypt(key: Array[Byte], iv: Array[Byte], ciphertext: Array[Byte], aad: Array[Byte]): Array[Byte] =
    AesGcm.decrypt(key, iv, ciphertext, aad)

  override def chacha20Poly1305Encrypt(key: Array[Byte], nonce: Array[Byte], plaintext: Array[Byte], aad: Array[Byte]): Array[Byte] =
    ChaCha20Poly1305.encrypt(key, nonce, plaintext, aad)

  override def chacha20Poly1305Decrypt(key: Array[Byte], nonce: Array[Byte], ciphertext: Array[Byte], aad: Array[Byte]): Array[Byte] =
    ChaCha20Poly1305.decrypt(key, nonce, ciphertext, aad)

  // ── X25519 ───────────────────────────────────────────────────────────

  override def x25519GenerateKeypair(): (Array[Byte], Array[Byte]) =
    X25519.generateKeypair(rng)

  override def x25519PublicKeyFromPrivate(priv32: Array[Byte]): Array[Byte] =
    X25519.publicKeyFromPrivate(priv32)

  override def x25519DeriveSharedSecret(selfPriv32: Array[Byte], peerPub32: Array[Byte]): Array[Byte] =
    X25519.deriveSharedSecret(selfPriv32, peerPub32)

  // ── RNG ──────────────────────────────────────────────────────────────

  private val rng = new java.security.SecureRandom()

  def randomBytes(len: Int): Array[Byte] =
    val out = new Array[Byte](len)
    rng.nextBytes(out)
    out
