package scalascript.crypto

/** Pluggable cryptography backend. One JVM impl
 *  (`scalascript.crypto.bouncycastle.BouncyCastleBackend`) and one
 *  Scala.js impl (`crypto-noble-js`, planned) implement this trait;
 *  higher layers (`blockchain-spi`, `wallet-spi`) consume it abstractly.
 *
 *  Synchronous API: BouncyCastle is sync; @noble/curves on Scala.js is
 *  sync too. WebAuthn / passkey signing is async but does NOT go through
 *  this trait — it implements `RawSigner` directly.
 *
 *  Registry lives in this companion object's `register` / `all` / `get`
 *  surface. Platform-specific discovery (ServiceLoader on JVM, no-op on
 *  Scala.js) is delegated to a per-platform `CryptoBackendDiscovery`
 *  helper that lives in `crypto-spi/jvm/` and `crypto-spi/js/` source
 *  trees respectively.
 *
 *  See docs/blockchain-spi.md §4 for the full design rationale. */
trait CryptoBackend:

  /** Stable identifier for diagnostics / registry lookups.
   *  Examples: "bouncycastle-jvm", "noble-js". */
  def id: String

  /** Whether this backend implements operations for the given curve. */
  def supports(curve: Curve): Boolean

  // ── Signing primitives ─────────────────────────────────────────────────

  /** ECDSA / EdDSA / etc. signing. For curves that hash internally
   *  (Ed25519), `hash` is ignored (must be `None`). For ECDSA curves, if
   *  `hash != None` the backend hashes `msg` before signing; if `None`
   *  the caller is responsible for pre-hashing and `msg` must be exactly
   *  the curve's expected digest length. */
  def sign(curve: Curve, privKey: Array[Byte], msg: Array[Byte], hash: HashAlgo): Array[Byte]

  def verify(curve: Curve, pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte], hash: HashAlgo): Boolean

  /** Derive the public key from a private key for the given curve. */
  def derivePublic(curve: Curve, privKey: Array[Byte]): Array[Byte]

  /** ECDSA public key recovery from signature + recovery id. Required for
   *  Ethereum-style `ecrecover`. Returns the uncompressed public key (64B,
   *  no `0x04` prefix) for secp256k1; raises for curves that don't support
   *  recovery. */
  def recoverPublic(curve: Curve, msgHash: Array[Byte], sig: Array[Byte], recId: Int): Array[Byte]

  // ── Hash primitives ────────────────────────────────────────────────────

  def hash(algo: HashAlgo, data: Array[Byte]): Array[Byte]

  def hmac(algo: HashAlgo, key: Array[Byte], data: Array[Byte]): Array[Byte]

  // ── BIP-32 / SLIP-0010 HD derivation ───────────────────────────────────

  /** Compute the master HD node from a seed (typically the 64-byte output
   *  of BIP-39 `mnemonicToSeed`). */
  def deriveMaster(curve: Curve, seed: Array[Byte]): HdKey

  /** Derive a child HD node. `hardened = true` adds 2^31 to the index per
   *  BIP-32 conventions. */
  def deriveChild(curve: Curve, parent: HdKey, index: Long, hardened: Boolean): HdKey

  // ── KDF ────────────────────────────────────────────────────────────────

  def pbkdf2(password: Array[Byte], salt: Array[Byte], iter: Int, len: Int, hash: HashAlgo): Array[Byte]

  /** Argon2id for password-derived keys (vault encryption). */
  def argon2id(password: Array[Byte], salt: Array[Byte], memKiB: Int, iter: Int, parallelism: Int, len: Int): Array[Byte]

  def hkdf(ikm: Array[Byte], salt: Array[Byte], info: Array[Byte], len: Int, hash: HashAlgo): Array[Byte]

  // ── AEAD ───────────────────────────────────────────────────────────────

  def aesGcmEncrypt(key: Array[Byte], iv: Array[Byte], plaintext: Array[Byte], aad: Array[Byte]): Array[Byte]

  def aesGcmDecrypt(key: Array[Byte], iv: Array[Byte], ciphertext: Array[Byte], aad: Array[Byte]): Array[Byte]

  /** ChaCha20-Poly1305 AEAD encrypt. Returns ciphertext concatenated with
   *  the 16-byte Poly1305 tag (`ciphertext || tag` — same layout the JCE
   *  `ChaCha20-Poly1305` provider and `@noble/ciphers/chacha.chacha20poly1305`
   *  emit).  `key` is 32 bytes, `nonce` is 12 bytes.  Used by
   *  `wallet-connect` for WC v2 envelopes (Type 0 / Type 1). */
  def chacha20Poly1305Encrypt(
    key: Array[Byte],
    nonce: Array[Byte],
    plaintext: Array[Byte],
    aad: Array[Byte] = Array.emptyByteArray,
  ): Array[Byte] =
    throw new UnsupportedOperationException(s"$id: chacha20Poly1305Encrypt not implemented")

  /** ChaCha20-Poly1305 AEAD decrypt.  Returns the plaintext on success;
   *  throws [[CryptoIntegrityException]] when the Poly1305 tag fails to
   *  verify (the SPI-level wrapper exception that both backends raise so
   *  cross-platform callers can pattern-match without depending on
   *  `javax.crypto.AEADBadTagException`). */
  def chacha20Poly1305Decrypt(
    key: Array[Byte],
    nonce: Array[Byte],
    ciphertext: Array[Byte],
    aad: Array[Byte] = Array.emptyByteArray,
  ): Array[Byte] =
    throw new UnsupportedOperationException(s"$id: chacha20Poly1305Decrypt not implemented")

  // ── X25519 (ECDH) ───────────────────────────────────────────────────────

  /** Generate a fresh X25519 keypair via this backend's CSPRNG.  Returns
   *  `(priv32, pub32)`.  Used by `wallet-connect` for the WC v2
   *  session-key handshake. */
  def x25519GenerateKeypair(): (Array[Byte], Array[Byte]) =
    throw new UnsupportedOperationException(s"$id: x25519GenerateKeypair not implemented")

  /** Reconstruct the public half of an X25519 keypair from its 32-byte
   *  private bytes — needed when callers persist only the private key. */
  def x25519PublicKeyFromPrivate(priv32: Array[Byte]): Array[Byte] =
    throw new UnsupportedOperationException(s"$id: x25519PublicKeyFromPrivate not implemented")

  /** Compute the 32-byte raw X25519 shared secret.  Feed to HKDF to
   *  derive a symmetric key — do not use the raw output as key material
   *  directly. */
  def x25519DeriveSharedSecret(selfPriv32: Array[Byte], peerPub32: Array[Byte]): Array[Byte] =
    throw new UnsupportedOperationException(s"$id: x25519DeriveSharedSecret not implemented")

  // ── Secure RNG ─────────────────────────────────────────────────────────

  def randomBytes(len: Int): Array[Byte]

/** Cross-platform registry for `CryptoBackend` implementations.
 *
 *  Two layers of registration:
 *
 *  1. **Explicit** — `CryptoBackend.register(impl)` adds an impl to the
 *     in-memory map. Used directly by Scala.js impl-module initialisers
 *     and by JVM tests that need to inject doubles.
 *  2. **Platform discovery** — on JVM, `CryptoBackendDiscovery.discover()`
 *     loads `META-INF/services/scalascript.crypto.CryptoBackend` via
 *     `ServiceLoader`. On Scala.js, `discover()` returns `Nil` (no
 *     ServiceLoader on JS — impls must call `register(...)` from a
 *     module-init block).
 *
 *  See docs/wallet-spi-scalajs.md §3.4 for the cross-platform pattern. */
object CryptoBackend:

  private val explicit = scala.collection.mutable.LinkedHashMap.empty[String, CryptoBackend]
  @volatile private var cached: Option[Seq[CryptoBackend]] = None

  /** Register a backend explicitly. Called by Scala.js init blocks; also
   *  usable for test-driven double-injection. Last registration wins on
   *  duplicate `id`. */
  def register(backend: CryptoBackend): Unit = synchronized:
    explicit(backend.id) = backend
    cached = None  // invalidate cache so the new backend shows up

  def all: Seq[CryptoBackend] = synchronized:
    cached match
      case Some(xs) => xs
      case None =>
        val byId = scala.collection.mutable.LinkedHashMap.empty[String, CryptoBackend]
        // Platform discovery first (ServiceLoader on JVM, no-op on JS),
        // then explicit overrides win on duplicate id.
        CryptoBackendDiscovery.discover().foreach(b => byId(b.id) = b)
        explicit.foreach { case (id, b) => byId(id) = b }
        val xs = byId.values.toSeq
        cached = Some(xs)
        xs

  /** First registered backend, or throw if none are available. */
  def get(): CryptoBackend =
    all.headOption.getOrElse(
      throw new IllegalStateException(
        "No CryptoBackend registered. Add scalascript-crypto-bouncycastle to your classpath (JVM) or call CryptoBackend.register(...) at startup (Scala.js)."
      )
    )

  /** Look up a backend by its `id`. */
  def get(id: String): CryptoBackend =
    all.find(_.id == id).getOrElse(
      throw new IllegalArgumentException(s"No CryptoBackend with id=$id registered.")
    )

  /** Test hook: reset all caches and explicit registrations. Intended
   *  for test setup / teardown. Not for runtime use. */
  def resetForTests(): Unit = synchronized:
    explicit.clear()
    cached = None
