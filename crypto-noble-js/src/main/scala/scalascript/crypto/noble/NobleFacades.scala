package scalascript.crypto.noble

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

/** Scala.js facades for the `@noble/curves` + `@noble/hashes` JS
 *  libraries (v1.x). Only the subset of the noble API actually needed
 *  by [[NobleCryptoBackend]] is bound — sign / verify / recover for
 *  secp256k1 / ed25519 / p256, and the sha256 / sha512 / keccak_256 /
 *  hmac / hkdf hash primitives.
 *
 *  The npm packages must be installed (`npm install` inside
 *  `crypto-noble-js/`) before running `sbt cryptoNobleJs/test`. See the
 *  project `package.json` next to this source tree for the version
 *  pin. */
private[noble] object NobleFacades:

  // ── secp256k1 / p256 (shared Weierstrass shape in noble) ────────────────

  /** Recovered point exposed by `Signature.recoverPublicKey(...)`. */
  @js.native
  trait NobleSecpPoint extends js.Object:
    /** `toBytes(false)` → uncompressed (65 bytes, 0x04 prefix); `true` →
     *  compressed (33 bytes). Older noble versions also expose
     *  `toRawBytes` with the same semantics — `toBytes` is the
     *  canonical name in v1.9+. */
    def toBytes(isCompressed: Boolean): Uint8Array = js.native

  @js.native
  trait NobleSecpSignature extends js.Object:
    def toCompactRawBytes(): Uint8Array                  = js.native
    def addRecoveryBit(recId: Int): NobleSecpSignature   = js.native
    def recoverPublicKey(msgHash: Uint8Array): NobleSecpPoint = js.native
    val recovery: Int                                    = js.native

  @js.native
  trait NobleSecpSignatureCompanion extends js.Object:
    def fromCompact(bytes: Uint8Array): NobleSecpSignature = js.native

  @js.native
  trait NobleSecpCurve extends js.Object:
    def getPublicKey(privKey: Uint8Array, isCompressed: Boolean): Uint8Array      = js.native
    def sign(msgHash: Uint8Array, privKey: Uint8Array): NobleSecpSignature        = js.native
    def verify(sig: Uint8Array, msgHash: Uint8Array, pubKey: Uint8Array): Boolean = js.native
    val Signature: NobleSecpSignatureCompanion                                    = js.native

  @js.native
  @JSImport("@noble/curves/secp256k1", "secp256k1")
  object secp256k1 extends NobleSecpCurve

  @js.native
  @JSImport("@noble/curves/p256", "p256")
  object p256 extends NobleSecpCurve

  // ── ed25519 ─────────────────────────────────────────────────────────────

  @js.native
  trait NobleEd25519 extends js.Object:
    def getPublicKey(privKey: Uint8Array): Uint8Array                          = js.native
    def sign(msg: Uint8Array, privKey: Uint8Array): Uint8Array                 = js.native
    def verify(sig: Uint8Array, msg: Uint8Array, pubKey: Uint8Array): Boolean  = js.native

  @js.native
  @JSImport("@noble/curves/ed25519", "ed25519")
  object ed25519 extends NobleEd25519

  // ── hashes ──────────────────────────────────────────────────────────────

  /** noble's `CHash` shape — at runtime it's a JS function that also
   *  carries metadata members; we only need the call site, so model it
   *  as a `js.Function1`. Exposed as `val` so call sites can both
   *  invoke (`sha256(x)`) and pass it as a value (to `hmac` / `hkdf`). */
  type CHash = js.Function1[Uint8Array, Uint8Array]

  @JSImport("@noble/hashes/sha256", "sha256")
  @js.native
  val sha256: CHash = js.native

  @JSImport("@noble/hashes/sha512", "sha512")
  @js.native
  val sha512: CHash = js.native

  @JSImport("@noble/hashes/sha3", "keccak_256")
  @js.native
  val keccak_256: CHash = js.native

  @JSImport("@noble/hashes/ripemd160", "ripemd160")
  @js.native
  val ripemd160: CHash = js.native

  @JSImport("@noble/hashes/hmac", "hmac")
  @js.native
  def hmac(hash: CHash, key: Uint8Array, data: Uint8Array): Uint8Array = js.native

  @JSImport("@noble/hashes/hkdf", "hkdf")
  @js.native
  def hkdf(
    hash: CHash,
    ikm: Uint8Array,
    salt: Uint8Array,
    info: Uint8Array,
    length: Int,
  ): Uint8Array = js.native

  // ── PBKDF2 (@noble/hashes/pbkdf2) ───────────────────────────────────────

  /** PBKDF2 options object — `c` is iteration count, `dkLen` is the
   *  derived-key length in bytes.  See @noble/hashes/pbkdf2.d.ts. */
  trait Pbkdf2Opts extends js.Object:
    val c: Int
    val dkLen: Int

  object Pbkdf2Opts:
    def apply(c: Int, dkLen: Int): Pbkdf2Opts =
      js.Dynamic.literal(c = c, dkLen = dkLen).asInstanceOf[Pbkdf2Opts]

  @JSImport("@noble/hashes/pbkdf2", "pbkdf2")
  @js.native
  def pbkdf2(
    hash: CHash,
    password: Uint8Array,
    salt: Uint8Array,
    opts: Pbkdf2Opts,
  ): Uint8Array = js.native

  // ── Argon2id (@noble/hashes/argon2 ≥ 1.8) ───────────────────────────────

  /** Argon2 options.  `t` = iterations, `m` = memory cost in KiB,
   *  `p` = parallelism lanes, `dkLen` = output bytes.  See
   *  @noble/hashes/argon2.d.ts (RFC 9106). */
  trait ArgonOpts extends js.Object:
    val t: Int
    val m: Int
    val p: Int
    val dkLen: Int

  object ArgonOpts:
    def apply(t: Int, m: Int, p: Int, dkLen: Int): ArgonOpts =
      js.Dynamic.literal(t = t, m = m, p = p, dkLen = dkLen).asInstanceOf[ArgonOpts]

  @JSImport("@noble/hashes/argon2", "argon2id")
  @js.native
  def argon2id(
    password: Uint8Array,
    salt: Uint8Array,
    opts: ArgonOpts,
  ): Uint8Array = js.native

  // ── AES-GCM (@noble/ciphers/aes ≥ 1.x) ─────────────────────────────────

  /** Synchronous AEAD cipher exposed by `@noble/ciphers/aes`.  Both
   *  `encrypt` (plaintext → ciphertext||tag) and `decrypt`
   *  (ciphertext||tag → plaintext) are sync; they throw on
   *  authentication failure during decrypt. */
  @js.native
  trait NobleCipher extends js.Object:
    def encrypt(plaintext: Uint8Array): Uint8Array  = js.native
    def decrypt(ciphertext: Uint8Array): Uint8Array = js.native

  /** AES-GCM constructor: `gcm(key, nonce, aad?) → Cipher`.  Returns a
   *  fresh instance per call (do not reuse across encrypts).  Pinned
   *  via `@noble/ciphers ^1.x` — see crypto-noble-js/package.json. */
  @JSImport("@noble/ciphers/aes", "gcm")
  @js.native
  def gcm(
    key: Uint8Array,
    nonce: Uint8Array,
    aad: js.UndefOr[Uint8Array],
  ): NobleCipher = js.native
