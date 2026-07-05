package scalascript.crypto

/** Portable **did:key** (W3C DID Method: Key) — encode a public key as `did:key:z<mb>` and resolve it
 *  back, for **Ed25519** and **P-256**. Pure Scala, identical on JVM and Scala.js, no platform crypto.
 *
 *  A did:key is `did:key:` + a multibase `z` (base58btc) of `varint(multicodec) ‖ rawPublicKey`:
 *  Ed25519 uses multicodec `0xed` → varint `0xed 0x01` + the 32-byte key; P-256 uses `0x1200` → varint
 *  `0x80 0x24` + the 33-byte **compressed** SEC1 point (per the did:key registry). Pairs with the JWS /
 *  COSE / WebAuthn keys elsewhere in this package (an issuer identity for verifiable credentials). */
object DidKey:

  private val Prefix          = "did:key:z"
  private val MulticodecEd25519 = Array[Byte](0xed.toByte, 0x01.toByte)   // varint(0xed)
  private val MulticodecP256    = Array[Byte](0x80.toByte, 0x24.toByte)   // varint(0x1200)

  /** `did:key` for a 32-byte Ed25519 public key. */
  def fromEd25519(pub32: Array[Byte]): String =
    require(pub32.length == 32, s"Ed25519 public key must be 32 bytes, got ${pub32.length}")
    Prefix + Base58.encode(MulticodecEd25519 ++ pub32)

  /** `did:key` for a 33-byte **compressed** SEC1 P-256 public key. */
  def fromP256Compressed(pub33: Array[Byte]): String =
    require(pub33.length == 33, s"compressed P-256 key must be 33 bytes, got ${pub33.length}")
    Prefix + Base58.encode(MulticodecP256 ++ pub33)

  /** A resolved did:key public key. */
  sealed trait Resolved
  final case class Ed25519(pub: Array[Byte])           extends Resolved   // 32-byte raw
  final case class P256Compressed(pub: Array[Byte])    extends Resolved   // 33-byte compressed SEC1

  /** Resolve a `did:key:z…` to its public key. `None` if the DID is malformed or an unsupported codec. */
  def resolve(did: String): Option[Resolved] =
    if !did.startsWith(Prefix) then None
    else
      try
        val bytes = Base58.decode(did.substring(Prefix.length))
        if bytes.length >= 2 && bytes(0) == MulticodecEd25519(0) && bytes(1) == MulticodecEd25519(1) then
          val pub = bytes.drop(2)
          if pub.length == 32 then Some(Ed25519(pub)) else None
        else if bytes.length >= 2 && bytes(0) == MulticodecP256(0) && bytes(1) == MulticodecP256(1) then
          val pub = bytes.drop(2)
          if pub.length == 33 then Some(P256Compressed(pub)) else None
        else None
      catch case _: Exception => None

/** Bitcoin/IPFS **base58btc** (the multibase `z` alphabet). Pure big-integer arithmetic — no leading-zero
 *  loss (each leading `0x00` byte becomes a leading `1`). */
object Base58:

  private val Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray
  private val Base     = BigInt(58)

  def encode(bytes: Array[Byte]): String =
    if bytes.isEmpty then ""
    else
      val leadingZeros = bytes.takeWhile(_ == 0).length
      val sb = new StringBuilder
      var rem = BigInt(1, bytes)
      while rem > 0 do
        val (q, r) = rem /% Base
        sb.append(Alphabet(r.toInt))
        rem = q
      var i = 0
      while i < leadingZeros do { sb.append(Alphabet(0)); i += 1 }
      sb.reverse.toString

  def decode(s: String): Array[Byte] =
    var n = BigInt(0)
    var i = 0
    while i < s.length do
      val d = Alphabet.indexOf(s.charAt(i))
      require(d >= 0, s"invalid base58 character: '${s.charAt(i)}'")
      n = n * Base + d
      i += 1
    val leadingOnes = s.takeWhile(_ == '1').length
    val mag = if n == 0 then Array.emptyByteArray else n.toByteArray.dropWhile(_ == 0.toByte)
    Array.fill[Byte](leadingOnes)(0.toByte) ++ mag
