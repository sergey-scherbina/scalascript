package scalascript.crypto

/** Portable COSE_Sign1 (RFC 8152 / RFC 9052, single-signer) with **EdDSA** (Ed25519), over the
 *  from-scratch [[Cbor]] + [[Ed25519]] — identical on JVM and Scala.js, no platform crypto.
 *
 *  A COSE_Sign1 is the CBOR `Tag(18, [protected, unprotected, payload, signature])`, where `protected`
 *  is a bstr wrapping the CBOR-encoded protected-header map — here `{1: -8}` (`alg: EdDSA`). The
 *  signature covers the `Sig_structure` (RFC 8152 §4.4):
 *  `["Signature1", protected, external_aad, payload]`, so header, external AAD and payload are all
 *  authenticated. This is the signing structure used by WebAuthn / FIDO2 and Cardano CIP-8.
 *
 *  Scope: EdDSA sign+verify. ES256 (COSE alg -7, P-256) and COSE_Encrypt are follow-ups. */
object CoseSign1:

  private val AlgEdDSA     = -8L
  private val AlgES256     = -7L
  private val AlgES256K    = -47L
  private val Signature1   = "Signature1"
  private val TagCoseSign1 = 18L
  private val empty        = Array.emptyByteArray

  /** The EdDSA protected-header content: CBOR `{1: -8}` (unwrapped — the raw map bytes). */
  def protectedHeaderEdDSA: Array[Byte] =
    Cbor.encode(Cbor.Map(IndexedSeq(Cbor.UInt(1) -> Cbor.int(AlgEdDSA))))

  /** The ES256K protected-header content: CBOR `{1: -47}` (ECDSA secp256k1 + SHA-256). */
  def protectedHeaderES256K: Array[Byte] =
    Cbor.encode(Cbor.Map(IndexedSeq(Cbor.UInt(1) -> Cbor.int(AlgES256K))))

  /** The ES256 protected-header content: CBOR `{1: -7}` (ECDSA P-256 + SHA-256). */
  def protectedHeaderES256: Array[Byte] =
    Cbor.encode(Cbor.Map(IndexedSeq(Cbor.UInt(1) -> Cbor.int(AlgES256))))

  private def toBeSigned(protectedContent: Array[Byte], externalAad: Array[Byte], payload: Array[Byte]): Array[Byte] =
    Cbor.encode(Cbor.Arr(IndexedSeq(
      Cbor.Text(Signature1),
      Cbor.Bytes(protectedContent),
      Cbor.Bytes(externalAad),
      Cbor.Bytes(payload),
    )))

  /** Sign `payload` into a tagged COSE_Sign1 message. `seed` is the 32-byte Ed25519 private key;
   *  `externalAad` is authenticated but not transmitted (empty by default). */
  def signEdDSA(seed: Array[Byte], payload: Array[Byte], externalAad: Array[Byte] = empty): Array[Byte] =
    require(seed.length == 32, s"Ed25519 seed must be 32 bytes, got ${seed.length}")
    val ph  = protectedHeaderEdDSA
    val sig = Ed25519.sign(seed, toBeSigned(ph, externalAad, payload))    // 64 bytes
    Cbor.encode(Cbor.Tagged(TagCoseSign1, Cbor.Arr(IndexedSeq(
      Cbor.Bytes(ph),
      Cbor.Map(IndexedSeq.empty),        // unprotected header (empty)
      Cbor.Bytes(payload),
      Cbor.Bytes(sig),
    ))))

  /** Verify a COSE_Sign1 EdDSA message (tag 18 optional). Returns the payload iff the protected
   *  header declares `alg: EdDSA` and the signature over `[header, external_aad, payload]` is valid;
   *  `None` for a bad signature, wrong algorithm, or malformed CBOR. */
  def verifyEdDSA(message: Array[Byte], pub: Array[Byte], externalAad: Array[Byte] = empty): Option[Array[Byte]] =
    try
      val items = Cbor.decode(message) match
        case Cbor.Tagged(TagCoseSign1, Cbor.Arr(xs)) => xs
        case Cbor.Arr(xs)                            => xs
        case _                                       => IndexedSeq.empty
      if items.length != 4 then None
      else (items(0), items(2), items(3)) match
        case (Cbor.Bytes(ph), Cbor.Bytes(payload), Cbor.Bytes(sig)) if algIsEdDSA(ph) =>
          if Ed25519.verify(pub, toBeSigned(ph, externalAad, payload), sig) then Some(payload) else None
        case _ => None
    catch case _: Exception => None

  /** True iff the protected-header map declares `1: -8` (alg: EdDSA).  -8 decodes as `NInt(7)`. */
  private def algIsEdDSA(protectedContent: Array[Byte]): Boolean = algIs(protectedContent, 7)

  /** True iff the protected-header map declares `1: -47` (alg: ES256K). -47 decodes as `NInt(46)`. */
  private def algIsES256K(protectedContent: Array[Byte]): Boolean = algIs(protectedContent, 46)

  private def algIs(protectedContent: Array[Byte], nintVal: Long): Boolean =
    try
      Cbor.decode(protectedContent) match
        case Cbor.Map(entries) => entries.exists {
          case (Cbor.UInt(1), Cbor.NInt(n)) => n == nintVal
          case _                            => false
        }
        case _ => false
    catch case _: Exception => false

  /** Sign `payload` into a tagged COSE_Sign1 with **ES256K** (ECDSA secp256k1 + SHA-256, fixed 64-byte
   *  R‖S). `privKey` is the 32-byte secp256k1 private key. */
  def signES256K(privKey: Array[Byte], payload: Array[Byte], externalAad: Array[Byte] = empty): Array[Byte] =
    val ph  = protectedHeaderES256K
    val sig = Secp256k1Ecdsa.derToRaw(Secp256k1Ecdsa.sign(privKey, Sha256.digest(toBeSigned(ph, externalAad, payload))))
    Cbor.encode(Cbor.Tagged(TagCoseSign1, Cbor.Arr(IndexedSeq(
      Cbor.Bytes(ph),
      Cbor.Map(IndexedSeq.empty),
      Cbor.Bytes(payload),
      Cbor.Bytes(sig),
    ))))

  /** Verify a COSE_Sign1 ES256K message against a secp256k1 public key. Returns the payload iff the
   *  protected header declares `alg: ES256K` and the R‖S signature is valid; `None` otherwise. */
  def verifyES256K(message: Array[Byte], pub: Array[Byte], externalAad: Array[Byte] = empty): Option[Array[Byte]] =
    try
      val items = Cbor.decode(message) match
        case Cbor.Tagged(TagCoseSign1, Cbor.Arr(xs)) => xs
        case Cbor.Arr(xs)                            => xs
        case _                                       => IndexedSeq.empty
      if items.length != 4 then None
      else (items(0), items(2), items(3)) match
        case (Cbor.Bytes(ph), Cbor.Bytes(payload), Cbor.Bytes(raw)) if algIsES256K(ph) && raw.length == 64 =>
          val hash = Sha256.digest(toBeSigned(ph, externalAad, payload))
          if Secp256k1Ecdsa.verify(pub, hash, Secp256k1Ecdsa.rawToDer(raw)) then Some(payload) else None
        case _ => None
    catch case _: Exception => None

  /** True iff the protected-header map declares `1: -7` (alg: ES256). -7 decodes as `NInt(6)`. */
  private def algIsES256(protectedContent: Array[Byte]): Boolean = algIs(protectedContent, 6)

  /** Sign `payload` into a tagged COSE_Sign1 with **ES256** (ECDSA P-256 + SHA-256, fixed 64-byte R‖S).
   *  `privKey` is the 32-byte P-256 private key. This is the common WebAuthn / FIDO2 assertion algorithm. */
  def signES256(privKey: Array[Byte], payload: Array[Byte], externalAad: Array[Byte] = empty): Array[Byte] =
    val ph  = protectedHeaderES256
    val sig = P256Ecdsa.derToRaw(P256Ecdsa.sign(privKey, Sha256.digest(toBeSigned(ph, externalAad, payload))))
    Cbor.encode(Cbor.Tagged(TagCoseSign1, Cbor.Arr(IndexedSeq(
      Cbor.Bytes(ph),
      Cbor.Map(IndexedSeq.empty),
      Cbor.Bytes(payload),
      Cbor.Bytes(sig),
    ))))

  /** Verify a COSE_Sign1 ES256 message against a P-256 public key. Returns the payload iff the protected
   *  header declares `alg: ES256` and the R‖S signature is valid; `None` otherwise. */
  def verifyES256(message: Array[Byte], pub: Array[Byte], externalAad: Array[Byte] = empty): Option[Array[Byte]] =
    try
      val items = Cbor.decode(message) match
        case Cbor.Tagged(TagCoseSign1, Cbor.Arr(xs)) => xs
        case Cbor.Arr(xs)                            => xs
        case _                                       => IndexedSeq.empty
      if items.length != 4 then None
      else (items(0), items(2), items(3)) match
        case (Cbor.Bytes(ph), Cbor.Bytes(payload), Cbor.Bytes(raw)) if algIsES256(ph) && raw.length == 64 =>
          val hash = Sha256.digest(toBeSigned(ph, externalAad, payload))
          if P256Ecdsa.verify(pub, hash, P256Ecdsa.rawToDer(raw)) then Some(payload) else None
        case _ => None
    catch case _: Exception => None
