package scalascript.blockchain.evm

/** Recursive Length Prefix encoding (Ethereum Yellow Paper appendix B).
 *
 *  Used for serialising transaction envelopes (legacy + EIP-2930 + EIP-1559)
 *  and for the message-to-be-signed in each scheme. This module is
 *  encode-only for now — decode is straightforward but unused by the
 *  current write-side of `EvmChainAdapter`. */
object Rlp:

  /** RLP tree: a leaf is a byte string; a branch is a sequence of nodes. */
  sealed trait Node
  case class Bytes(value: Array[Byte]) extends Node
  case class Lst(items: Seq[Node])     extends Node

  /** Encode unsigned integer as the shortest big-endian byte string with no
   *  leading zero (per RLP convention: `0` → empty string). */
  def uint(v: BigInt): Bytes =
    require(v.signum >= 0, s"RLP unsigned: negative value $v")
    if v.signum == 0 then Bytes(Array.emptyByteArray)
    else
      val raw = v.toByteArray
      val trimmed = if raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, raw.length) else raw
      Bytes(trimmed)

  def bytes(b: Array[Byte]): Bytes = Bytes(b)

  def list(items: Node*): Lst = Lst(items)

  def encode(node: Node): Array[Byte] = node match
    case Bytes(value) => encodeString(value)
    case Lst(items)   =>
      val payload = items.foldLeft(new java.io.ByteArrayOutputStream()) { (out, it) =>
        out.write(encode(it))
        out
      }.toByteArray
      encodeListHeader(payload.length) ++ payload

  private def encodeString(b: Array[Byte]): Array[Byte] =
    if b.length == 1 && (b(0) & 0xff) < 0x80 then b
    else if b.length < 56 then (0x80 + b.length).toByte +: b
    else
      val lenBytes = bigIntToBytes(BigInt(b.length))
      ((0xb7 + lenBytes.length).toByte +: lenBytes) ++ b

  private def encodeListHeader(payloadLen: Int): Array[Byte] =
    if payloadLen < 56 then Array((0xc0 + payloadLen).toByte)
    else
      val lenBytes = bigIntToBytes(BigInt(payloadLen))
      Array((0xf7 + lenBytes.length).toByte) ++ lenBytes

  private def bigIntToBytes(v: BigInt): Array[Byte] =
    require(v.signum > 0, s"length must be positive, got $v")
    val raw = v.toByteArray
    if raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, raw.length) else raw

  /** Convenience: encode an integer directly. */
  def encodeUint(v: BigInt): Array[Byte] = encode(uint(v))

  /** Convenience: encode a byte string directly. */
  def encodeBytes(b: Array[Byte]): Array[Byte] = encode(bytes(b))
