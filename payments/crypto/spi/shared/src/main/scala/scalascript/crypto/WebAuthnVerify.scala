package scalascript.crypto

/** Portable WebAuthn / FIDO2 **assertion signature verification** (the crypto core of a
 *  `navigator.credentials.get()` login), over the from-scratch [[P256Ecdsa]] / [[Ed25519]] + [[Cbor]] —
 *  identical on JVM and Scala.js, no platform crypto.
 *
 *  At registration the authenticator hands the server a **COSE_Key** public key; at each login it returns
 *  `authenticatorData`, `clientDataJSON`, and a `signature` over
 *  `authenticatorData ‖ SHA-256(clientDataJSON)` (WebAuthn §6.3.3 step 19-20). This object parses the
 *  COSE_Key and checks that signature.
 *
 *  Supported algorithms: **ES256** (COSE alg -7, ECDSA P-256, DER signature) and **EdDSA** (alg -8,
 *  Ed25519, raw 64-byte signature) — the two open passkey algorithms. This is the signature core only;
 *  the caller still enforces WebAuthn policy (challenge, origin, `rpIdHash`, UP/UV flags, signCount). */
object WebAuthnVerify:

  /** A parsed COSE_Key public key. */
  sealed trait CoseKey
  /** COSE EC2 key on P-256 (kty 2, crv 1) — ES256. */
  final case class Es256Key(x: Array[Byte], y: Array[Byte]) extends CoseKey
  /** COSE OKP key on Ed25519 (kty 1, crv 6) — EdDSA. */
  final case class EdDsaKey(x: Array[Byte]) extends CoseKey

  /** Parse a COSE_Key (RFC 8152 §7) public key. `None` if it is not a supported EC2/P-256 or OKP/Ed25519
   *  key, or the CBOR is malformed. */
  def parseCoseKey(cose: Array[Byte]): Option[CoseKey] =
    try
      Cbor.decode(cose) match
        case Cbor.Map(entries) =>
          def get(label: Long): Option[Cbor.Value] =
            entries.collectFirst { case (k, v) if asInt(k).contains(label) => v }
          get(1).flatMap(asInt) match                             // kty
            case Some(2) =>                                       // EC2
              val crvOk = get(-1).flatMap(asInt).contains(1L)     // crv P-256
              (get(-2).flatMap(asBytes), get(-3).flatMap(asBytes)) match
                case (Some(x), Some(y)) if crvOk && x.length == 32 && y.length == 32 => Some(Es256Key(x, y))
                case _ => None
            case Some(1) =>                                       // OKP
              val crvOk = get(-1).flatMap(asInt).contains(6L)     // crv Ed25519
              get(-2).flatMap(asBytes) match
                case Some(x) if crvOk && x.length == 32 => Some(EdDsaKey(x))
                case _ => None
            case _ => None
        case _ => None
    catch case _: Exception => None

  /** Verify a WebAuthn assertion signature. `coseKey` is the credential's stored COSE_Key public key;
   *  the signature covers `authenticatorData ‖ SHA-256(clientDataJSON)`. Returns false on any malformed
   *  input or unsupported algorithm. */
  def verifyAssertion(
    coseKey: Array[Byte],
    authenticatorData: Array[Byte],
    clientDataJSON: Array[Byte],
    signature: Array[Byte],
  ): Boolean =
    parseCoseKey(coseKey) match
      case Some(key) => verifyAssertion(key, authenticatorData, clientDataJSON, signature)
      case None      => false

  /** Verify against an already-parsed [[CoseKey]]. */
  def verifyAssertion(
    key: CoseKey,
    authenticatorData: Array[Byte],
    clientDataJSON: Array[Byte],
    signature: Array[Byte],
  ): Boolean =
    val signedData = authenticatorData ++ Sha256.digest(clientDataJSON)
    key match
      case Es256Key(x, y) =>
        // WebAuthn ES256 signatures are ASN.1/DER; the message is SHA-256(signedData).
        try P256Ecdsa.verify(Array[Byte](0x04) ++ x ++ y, Sha256.digest(signedData), signature)
        catch case _: Exception => false
      case EdDsaKey(x) =>
        // Ed25519 hashes internally; the raw 64-byte signature covers signedData directly.
        Ed25519.verify(x, signedData, signature)

  // ── COSE_Key CBOR helpers ───────────────────────────────────────────────────────

  private def asInt(v: Cbor.Value): Option[Long] = v match
    case Cbor.UInt(n) => Some(n)
    case Cbor.NInt(n) => Some(-(n + 1))
    case _            => None

  private def asBytes(v: Cbor.Value): Option[Array[Byte]] = v match
    case Cbor.Bytes(b) => Some(b)
    case _             => None
