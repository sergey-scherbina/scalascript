package scalascript.crypto.noble

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

import scalascript.crypto.*

/** Scala.js `CryptoBackend` implementation backed by `@noble/curves`
 *  (secp256k1 / ed25519 / p256) and `@noble/hashes` (sha256 / sha512 /
 *  keccak_256 / ripemd160 / hmac / hkdf). Output shapes match the JVM
 *  reference [[scalascript.crypto.bouncycastle.BouncyCastleBackend]]
 *  byte-for-byte so the same SPI call produces an identical result on
 *  both platforms.
 *
 *  Encoding contracts mirrored from JVM:
 *
 *  - **secp256k1 / p256 `derivePublic`** — 64 bytes, uncompressed,
 *    no `0x04` prefix.
 *  - **secp256k1 `sign`** — 65 bytes: `r(32) || s(32) || recId(1)`.
 *    Low-S form (noble's default).
 *  - **ed25519 `sign`** — 64 bytes per RFC 8032.
 *  - **p256 `sign`** — same shape as secp256k1 (65B with recId).
 *  - **`recoverPublic`** — secp256k1 only, returns 64-byte uncompressed
 *    public key (no `0x04` prefix). p256 raises (not implemented).
 *
 *  Stage 5 (this slice) adds:
 *
 *  - **PBKDF2** via `@noble/hashes/pbkdf2` (sha256 / sha512).
 *  - **Argon2id** via `@noble/hashes/argon2` (RFC 9106 v0x13).
 *  - **AES-GCM encrypt / decrypt** via `@noble/ciphers/aes` (sync;
 *    SubtleCrypto is async and would force the SPI to fork — see
 *    `aesGcmEncrypt` comment + docs/wallet-spi-scalajs.md §5).
 *
 *  Not yet implemented on JS (raise `UnsupportedOperationException`):
 *
 *  - **HD key derivation** (`deriveMaster` / `deriveChild`) — BIP-32 /
 *    SLIP-0010. Deferred to a later stage once the strategy modules
 *    that need it cross-compile.
 *  - **Sr25519 / BLS12-381** — `supports` returns `false`.
 *
 *  Register the backend at app init: call
 *  [[Register.install]] from Scala, or invoke the exported
 *  `registerNobleCryptoBackend()` from host JS. */
final class NobleCryptoBackend extends CryptoBackend:

  def id: String = "noble-js"

  def supports(curve: Curve): Boolean = curve match
    case Curve.Secp256k1 | Curve.Ed25519 | Curve.P256 => true
    case _                                            => false

  // ── signing ─────────────────────────────────────────────────────────────

  def sign(curve: Curve, privKey: Array[Byte], msg: Array[Byte], hash: HashAlgo): Array[Byte] =
    curve match
      case Curve.Secp256k1 => signEcdsa(NobleFacades.secp256k1, privKey, msg, hash)
      case Curve.P256      => signEcdsa(NobleFacades.p256,      privKey, msg, hash)
      case Curve.Ed25519   =>
        if hash != HashAlgo.None then
          throw new IllegalArgumentException(
            s"Ed25519 hashes internally; pass HashAlgo.None (got $hash)"
          )
        u8ToBytes(NobleFacades.ed25519.sign(bytesToU8(msg), bytesToU8(privKey)))
      case other =>
        throw new UnsupportedOperationException(s"sign: curve not supported by $id: $other")

  def verify(curve: Curve, pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte], hash: HashAlgo): Boolean =
    curve match
      case Curve.Secp256k1 => verifyEcdsa(NobleFacades.secp256k1, pubKey, msg, sig, hash, prefixByte = 0x04)
      case Curve.P256      => verifyEcdsa(NobleFacades.p256,      pubKey, msg, sig, hash, prefixByte = 0x04)
      case Curve.Ed25519   =>
        if hash != HashAlgo.None then false
        else if sig.length != 64 then false
        else
          NobleFacades.ed25519.verify(bytesToU8(sig), bytesToU8(msg), bytesToU8(pubKey))
      case _ => false

  def derivePublic(curve: Curve, privKey: Array[Byte]): Array[Byte] =
    curve match
      case Curve.Secp256k1 => secpDerivePublicNoPrefix(NobleFacades.secp256k1, privKey)
      case Curve.P256      => secpDerivePublicNoPrefix(NobleFacades.p256,      privKey)
      case Curve.Ed25519   => u8ToBytes(NobleFacades.ed25519.getPublicKey(bytesToU8(privKey)))
      case other           =>
        throw new UnsupportedOperationException(s"derivePublic: curve not supported by $id: $other")

  def recoverPublic(curve: Curve, msgHash: Array[Byte], sig: Array[Byte], recId: Int): Array[Byte] =
    curve match
      case Curve.Secp256k1 =>
        if sig.length < 64 then
          throw new IllegalArgumentException("secp256k1 signature must be at least 64 bytes")
        val compact = bytesToU8(sig.slice(0, 64))
        val signature = NobleFacades.secp256k1.Signature
          .fromCompact(compact)
          .addRecoveryBit(recId)
        val recovered = signature.recoverPublicKey(bytesToU8(msgHash))
        // toBytes(false) → 65 bytes (0x04 || x || y); strip prefix per JVM shape.
        val uncompressed = u8ToBytes(recovered.toBytes(isCompressed = false))
        if uncompressed.length == 65 && uncompressed(0) == 0x04 then uncompressed.drop(1)
        else uncompressed
      case other =>
        throw new UnsupportedOperationException(s"recoverPublic: not supported for $other (secp256k1 only)")

  // ── hashes ──────────────────────────────────────────────────────────────

  def hash(algo: HashAlgo, data: Array[Byte]): Array[Byte] =
    val fn: NobleFacades.CHash = algo match
      case HashAlgo.Sha256     => NobleFacades.sha256
      case HashAlgo.Sha512     => NobleFacades.sha512
      case HashAlgo.Keccak256  => NobleFacades.keccak_256
      case HashAlgo.Ripemd160  => NobleFacades.ripemd160
      case HashAlgo.None       => throw new IllegalArgumentException("HashAlgo.None is not a real digest")
      case HashAlgo.HmacSha512 =>
        throw new IllegalArgumentException("HashAlgo.HmacSha512 is a MAC, not a Digest; use hmac()")
    u8ToBytes(fn(bytesToU8(data)))

  def hmac(algo: HashAlgo, key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val fn: NobleFacades.CHash = algo match
      case HashAlgo.HmacSha512 => NobleFacades.sha512
      case HashAlgo.Sha512     => NobleFacades.sha512
      case HashAlgo.Sha256     => NobleFacades.sha256
      case HashAlgo.Keccak256  => NobleFacades.keccak_256
      case HashAlgo.Ripemd160  => NobleFacades.ripemd160
      case HashAlgo.None       => throw new IllegalArgumentException("HMAC needs a digest, not HashAlgo.None")
    u8ToBytes(NobleFacades.hmac(fn, bytesToU8(key), bytesToU8(data)))

  // ── HD derivation (deferred) ────────────────────────────────────────────

  def deriveMaster(curve: Curve, seed: Array[Byte]): HdKey =
    throw new UnsupportedOperationException(
      s"$id: HD derivation not yet implemented on Scala.js (Stage TBD)."
    )

  def deriveChild(curve: Curve, parent: HdKey, index: Long, hardened: Boolean): HdKey =
    throw new UnsupportedOperationException(
      s"$id: HD derivation not yet implemented on Scala.js (Stage TBD)."
    )

  // ── KDF ─────────────────────────────────────────────────────────────────

  def pbkdf2(password: Array[Byte], salt: Array[Byte], iter: Int, len: Int, hash: HashAlgo): Array[Byte] =
    // Synchronous PBKDF2 via @noble/hashes/pbkdf2 (v1.8+).  noble's
    // pbkdf2(hashFn, password, salt, { c, dkLen }) signature matches
    // RFC 2898 exactly; output bytes are bit-identical to BouncyCastle's
    // PBKDF2WithHmacSHA{256,512} for the same (password, salt, c, dkLen).
    val fn: NobleFacades.CHash = hash match
      case HashAlgo.Sha256 => NobleFacades.sha256
      case HashAlgo.Sha512 => NobleFacades.sha512
      case other           => throw new IllegalArgumentException(s"PBKDF2 not supported with $other")
    val opts = NobleFacades.Pbkdf2Opts(c = iter, dkLen = len)
    u8ToBytes(NobleFacades.pbkdf2(fn, bytesToU8(password), bytesToU8(salt), opts))

  def argon2id(password: Array[Byte], salt: Array[Byte], memKiB: Int, iter: Int, parallelism: Int, len: Int): Array[Byte] =
    // Synchronous Argon2id via @noble/hashes/argon2 (v1.8+, RFC 9106
    // version 0x13).  noble defaults to v0x13 internally; opts map is
    // { t: iterations, m: memory in KiB, p: parallelism, dkLen: output
    // bytes }.  Output is byte-identical to BouncyCastle's
    // Argon2BytesGenerator with the same params.
    val opts = NobleFacades.ArgonOpts(t = iter, m = memKiB, p = parallelism, dkLen = len)
    u8ToBytes(NobleFacades.argon2id(bytesToU8(password), bytesToU8(salt), opts))

  def hkdf(ikm: Array[Byte], salt: Array[Byte], info: Array[Byte], len: Int, hash: HashAlgo): Array[Byte] =
    val fn: NobleFacades.CHash = hash match
      case HashAlgo.Sha256 => NobleFacades.sha256
      case HashAlgo.Sha512 => NobleFacades.sha512
      case other           => throw new IllegalArgumentException(s"HKDF not supported with $other")
    u8ToBytes(NobleFacades.hkdf(fn, bytesToU8(ikm), bytesToU8(salt), bytesToU8(info), len))

  // ── AEAD ────────────────────────────────────────────────────────────────

  // AES-GCM goes through @noble/ciphers (synchronous, pure-JS) rather
  // than WebCrypto SubtleCrypto.  The CryptoBackend SPI exposes
  // synchronous `aesGcmEncrypt` / `aesGcmDecrypt` (matching the JVM
  // BouncyCastle backend's blocking AES-GCM); SubtleCrypto's
  // `crypto.subtle.encrypt` is Promise-based and can't be awaited
  // inside the sync SPI on either browser or Node.  Routing through
  // noble keeps the API contract while still matching JVM ciphertext
  // bit-for-bit (verified by the Stage 5 cross-platform fixtures).
  // See docs/wallet-spi-scalajs.md §5 Stage 5 for the rationale.

  def aesGcmEncrypt(key: Array[Byte], iv: Array[Byte], plaintext: Array[Byte], aad: Array[Byte]): Array[Byte] =
    val cipher = NobleFacades.gcm(
      bytesToU8(key),
      bytesToU8(iv),
      if aad == null || aad.length == 0 then js.undefined else js.defined(bytesToU8(aad)),
    )
    u8ToBytes(cipher.encrypt(bytesToU8(plaintext)))

  def aesGcmDecrypt(key: Array[Byte], iv: Array[Byte], ciphertext: Array[Byte], aad: Array[Byte]): Array[Byte] =
    val cipher = NobleFacades.gcm(
      bytesToU8(key),
      bytesToU8(iv),
      if aad == null || aad.length == 0 then js.undefined else js.defined(bytesToU8(aad)),
    )
    u8ToBytes(cipher.decrypt(bytesToU8(ciphertext)))

  override def chacha20Poly1305Encrypt(key: Array[Byte], nonce: Array[Byte], plaintext: Array[Byte], aad: Array[Byte]): Array[Byte] =
    val cipher = NobleFacades.chacha20poly1305(
      bytesToU8(key),
      bytesToU8(nonce),
      if aad == null || aad.length == 0 then js.undefined else js.defined(bytesToU8(aad)),
    )
    u8ToBytes(cipher.encrypt(bytesToU8(plaintext)))

  override def chacha20Poly1305Decrypt(key: Array[Byte], nonce: Array[Byte], ciphertext: Array[Byte], aad: Array[Byte]): Array[Byte] =
    val cipher = NobleFacades.chacha20poly1305(
      bytesToU8(key),
      bytesToU8(nonce),
      if aad == null || aad.length == 0 then js.undefined else js.defined(bytesToU8(aad)),
    )
    try u8ToBytes(cipher.decrypt(bytesToU8(ciphertext)))
    catch
      case e: js.JavaScriptException =>
        // noble throws `Error("invalid tag")` (or similar) on Poly1305
        // mismatch — wrap so callers can pattern-match cross-platform.
        throw new CryptoIntegrityException(s"ChaCha20-Poly1305 tag mismatch: ${e.getMessage}", e)
      case e: RuntimeException =>
        throw new CryptoIntegrityException(s"ChaCha20-Poly1305 tag mismatch: ${e.getMessage}", e)

  // ── X25519 ─────────────────────────────────────────────────────────────

  override def x25519GenerateKeypair(): (Array[Byte], Array[Byte]) =
    val priv = NobleFacades.x25519.utils.randomSecretKey()
    val pub  = NobleFacades.x25519.getPublicKey(priv)
    (u8ToBytes(priv), u8ToBytes(pub))

  override def x25519PublicKeyFromPrivate(priv32: Array[Byte]): Array[Byte] =
    require(priv32.length == 32, s"X25519 private key must be 32 B, got ${priv32.length}")
    u8ToBytes(NobleFacades.x25519.getPublicKey(bytesToU8(priv32)))

  override def x25519DeriveSharedSecret(selfPriv32: Array[Byte], peerPub32: Array[Byte]): Array[Byte] =
    require(selfPriv32.length == 32, s"X25519 priv must be 32 B, got ${selfPriv32.length}")
    require(peerPub32.length  == 32, s"X25519 pub must be 32 B, got ${peerPub32.length}")
    u8ToBytes(NobleFacades.x25519.getSharedSecret(bytesToU8(selfPriv32), bytesToU8(peerPub32)))

  // ── RNG ─────────────────────────────────────────────────────────────────

  /** Cryptographically-secure RNG: prefers WebCrypto's
   *  `globalThis.crypto.getRandomValues` when present (browser, Node ≥
   *  19, Deno, Bun). Falls back to `require('crypto').randomBytes` on
   *  older Node (pre-19), which exposes `crypto` only via CommonJS. */
  def randomBytes(len: Int): Array[Byte] =
    val out = new Uint8Array(len)
    NobleRng.fill(out)
    u8ToBytes(out)

  // ── internals ───────────────────────────────────────────────────────────

  private def signEcdsa(curve: NobleFacades.NobleSecpCurve, privKey: Array[Byte], msg: Array[Byte], hash: HashAlgo): Array[Byte] =
    val digest = digestForSecp(msg, hash)
    val sig    = curve.sign(bytesToU8(digest), bytesToU8(privKey))
    val compact = u8ToBytes(sig.toCompactRawBytes())  // 64 bytes (r || s)
    val out = new Array[Byte](65)
    System.arraycopy(compact, 0, out, 0, 64)
    out(64) = sig.recovery.toByte
    out

  private def verifyEcdsa(
    curve: NobleFacades.NobleSecpCurve,
    pubKey: Array[Byte],
    msg: Array[Byte],
    sig: Array[Byte],
    hash: HashAlgo,
    prefixByte: Byte,
  ): Boolean =
    if sig.length < 64 then false
    else
      val digest  = digestForSecp(msg, hash)
      val compact = bytesToU8(sig.slice(0, 64))
      // noble's verify accepts a SEC1-prefixed (33 or 65 byte) public key.
      // The JVM API accepts a 64-byte un-prefixed uncompressed key — prepend 0x04.
      val pkBytes = pubKey.length match
        case 64 => Array[Byte](prefixByte) ++ pubKey
        case _  => pubKey
      curve.verify(compact, bytesToU8(digest), bytesToU8(pkBytes))

  private def secpDerivePublicNoPrefix(curve: NobleFacades.NobleSecpCurve, privKey: Array[Byte]): Array[Byte] =
    val withPrefix = u8ToBytes(curve.getPublicKey(bytesToU8(privKey), isCompressed = false))
    // 65 bytes: 0x04 || X(32) || Y(32) — strip prefix to match JVM 64-byte shape.
    if withPrefix.length == 65 && withPrefix(0) == 0x04 then withPrefix.drop(1)
    else withPrefix

  private def digestForSecp(msg: Array[Byte], hash: HashAlgo): Array[Byte] =
    hash match
      case HashAlgo.None      =>
        if msg.length == 32 then msg
        else throw new IllegalArgumentException(s"msg must be 32 bytes for HashAlgo.None, got ${msg.length}")
      case HashAlgo.Sha256    => this.hash(HashAlgo.Sha256, msg)
      case HashAlgo.Keccak256 => this.hash(HashAlgo.Keccak256, msg)
      case other              => throw new IllegalArgumentException(s"Unsupported hash for ECDSA: $other")

  // ── byte ↔ Uint8Array bridges ───────────────────────────────────────────

  private def bytesToU8(b: Array[Byte]): Uint8Array =
    val out = new Uint8Array(b.length)
    var i   = 0
    while i < b.length do
      // Uint8Array values are 0..255; bit-mask to keep the unsigned
      // representation regardless of JVM Byte sign extension.
      out(i) = (b(i) & 0xff).toShort
      i += 1
    out

  private def u8ToBytes(u: Uint8Array): Array[Byte] =
    val out = new Array[Byte](u.length)
    var i   = 0
    while i < u.length do
      out(i) = u(i).toByte
      i += 1
    out
