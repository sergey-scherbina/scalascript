package scalascript.crypto

/** Portable COSE_Encrypt0 (RFC 8152 §5.2, single recipient / direct key) with **ChaCha20-Poly1305**
 *  (COSE alg 24), over the from-scratch [[Cbor]] + [[ChaCha20Poly1305]] — identical on JVM and Scala.js,
 *  no platform crypto. The complement to [[CoseSign1]]: signed COSE for authenticity, encrypted COSE for
 *  confidentiality, sharing the same CBOR layer.
 *
 *  A COSE_Encrypt0 is `Tag(16, [protected, unprotected, ciphertext])`. The protected header is
 *  `{1: 24}` (alg), the 12-byte AEAD nonce travels in the unprotected header `{5: iv}`, and the AEAD's
 *  associated data is the CBOR `Enc_structure` `["Encrypt0", protected, external_aad]` (RFC 8152 §5.3),
 *  so the algorithm + any external AAD are authenticated. `ciphertext` is `AEAD-ct ‖ 16-byte tag`. */
object CoseEncrypt0:

  private val AlgChaCha20Poly1305 = 24L
  private val TagCoseEncrypt0     = 16L
  private val HdrAlg              = 1L
  private val HdrIv               = 5L
  private val empty              = Array.emptyByteArray

  /** The protected-header content: CBOR `{1: 24}` (alg ChaCha20/Poly1305). */
  def protectedHeader: Array[Byte] =
    Cbor.encode(Cbor.Map(IndexedSeq(Cbor.int(HdrAlg) -> Cbor.int(AlgChaCha20Poly1305))))

  private def encStructure(protectedContent: Array[Byte], externalAad: Array[Byte]): Array[Byte] =
    Cbor.encode(Cbor.Arr(IndexedSeq(
      Cbor.Text("Encrypt0"),
      Cbor.Bytes(protectedContent),
      Cbor.Bytes(externalAad),
    )))

  /** Encrypt `plaintext` into a tagged COSE_Encrypt0 message. `key` is 32 bytes, `nonce` is 12 bytes. */
  def encrypt(key: Array[Byte], nonce: Array[Byte], plaintext: Array[Byte], externalAad: Array[Byte] = empty)
      : Array[Byte] =
    require(nonce.length == 12, s"ChaCha20-Poly1305 nonce must be 12 bytes, got ${nonce.length}")
    val ph        = protectedHeader
    val (ct, tag) = ChaCha20Poly1305.seal(key, nonce, plaintext, encStructure(ph, externalAad))
    Cbor.encode(Cbor.Tagged(TagCoseEncrypt0, Cbor.Arr(IndexedSeq(
      Cbor.Bytes(ph),
      Cbor.Map(IndexedSeq(Cbor.int(HdrIv) -> Cbor.Bytes(nonce))),   // unprotected: {5: iv}
      Cbor.Bytes(ct ++ tag),                                        // COSE ciphertext = AEAD ct ‖ tag
    ))))

  /** Decrypt a COSE_Encrypt0 message. Returns the plaintext iff the protected header declares alg 24 and
   *  the AEAD tag authenticates; `None` for a wrong key, tamper, or malformed CBOR. */
  def decrypt(key: Array[Byte], message: Array[Byte], externalAad: Array[Byte] = empty): Option[Array[Byte]] =
    try
      val items = Cbor.decode(message) match
        case Cbor.Tagged(TagCoseEncrypt0, Cbor.Arr(xs)) => xs
        case Cbor.Arr(xs)                               => xs
        case _                                          => IndexedSeq.empty
      if items.length != 3 then None
      else (items(0), items(1), items(2)) match
        case (Cbor.Bytes(ph), Cbor.Map(unprotected), Cbor.Bytes(ciphertext))
            if algIsChaCha(ph) && ciphertext.length >= 16 =>
          ivOf(unprotected) match
            case Some(iv) if iv.length == 12 =>
              val ct  = java.util.Arrays.copyOfRange(ciphertext, 0, ciphertext.length - 16)
              val tag = java.util.Arrays.copyOfRange(ciphertext, ciphertext.length - 16, ciphertext.length)
              ChaCha20Poly1305.open(key, iv, ct, tag, encStructure(ph, externalAad))
            case _ => None
        case _ => None
    catch case _: Exception => None

  private def algIsChaCha(protectedContent: Array[Byte]): Boolean =
    try
      Cbor.decode(protectedContent) match
        case Cbor.Map(entries) => entries.exists {
          case (Cbor.UInt(1), Cbor.UInt(24)) => true
          case _                             => false
        }
        case _ => false
    catch case _: Exception => false

  private def ivOf(unprotected: IndexedSeq[(Cbor.Value, Cbor.Value)]): Option[Array[Byte]] =
    unprotected.collectFirst { case (k, Cbor.Bytes(iv)) if asInt(k).contains(HdrIv) => iv }

  private def asInt(v: Cbor.Value): Option[Long] = v match
    case Cbor.UInt(n) => Some(n)
    case Cbor.NInt(n) => Some(-(n + 1))
    case _            => None
