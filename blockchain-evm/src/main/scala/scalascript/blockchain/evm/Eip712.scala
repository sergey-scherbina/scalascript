package scalascript.blockchain.evm

import scalascript.blockchain.spi.TypedData
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** EIP-712 typed-structured-data hashing.
 *
 *  Produces the 32-byte digest that an `EoaStrategy` signs via
 *  `RawSigner.sign(digest, HashAlgo.None)`:
 *
 *      digest = keccak256(0x19 0x01 || domainSeparator || structHash)
 *
 *  See `https://eips.ethereum.org/EIPS/eip-712` for the algorithm.
 *
 *  Supported types (sufficient for x402 EIP-3009, ERC-2612 Permit,
 *  Permit2, and the bulk of off-chain signed-message contracts):
 *
 *    - Primitives: uint8…uint256, int8…int256, address, bool, bytes1…bytes32
 *    - Dynamic:    string, bytes
 *    - Composite:  nested structs declared in `types`
 *
 *  Arrays (`T[]`, `T[n]`) are not supported in Phase 1 — the contracts
 *  we target don't use them in their typed-data shapes. Adding them is
 *  a small extension; deferred to keep the audit surface minimal here. */
private[evm] object Eip712:

  /** Compute the EIP-712 digest for the given typed-data value. The
   *  result is exactly the 32-byte value an EOA strategy should pass
   *  to `RawSigner.sign(..., HashAlgo.None)`. */
  def digest(td: TypedData.Eip712): Array[Byte] =
    val be              = CryptoBackend.get()
    val domainSeparator = structHash("EIP712Domain", td.domain, td.types, be)
    val msgHash         = structHash(td.primaryType, td.value, td.types, be)
    val buf             = new Array[Byte](2 + 32 + 32)
    buf(0)              = 0x19.toByte
    buf(1)              = 0x01.toByte
    System.arraycopy(domainSeparator, 0, buf, 2,  32)
    System.arraycopy(msgHash,         0, buf, 34, 32)
    be.hash(HashAlgo.Keccak256, buf)

  // ── canonical type encoding ────────────────────────────────────────────

  /** `encodeType(primaryType)` per EIP-712: the primary type's own
   *  declaration first, then *referenced* struct types in alphabetic
   *  order. */
  private def encodeType(primaryType: String, types: Map[String, Seq[(String, String)]]): String =
    val refs = collectReferences(primaryType, types) - primaryType
    val sorted = (primaryType +: refs.toSeq.sorted).distinct
    sorted.map(typeDecl(_, types)).mkString

  private def typeDecl(name: String, types: Map[String, Seq[(String, String)]]): String =
    val fields = types.getOrElse(name, throw new IllegalArgumentException(s"Unknown EIP-712 type: $name"))
    val body   = fields.map { case (typ, fName) => s"$typ $fName" }.mkString(",")
    s"$name($body)"

  private def collectReferences(root: String, types: Map[String, Seq[(String, String)]]): Set[String] =
    val seen = scala.collection.mutable.Set.empty[String]
    def walk(name: String): Unit =
      if seen.add(name) then
        types.getOrElse(name, Nil).foreach { case (typ, _) =>
          val baseType = stripArraySuffix(typ)
          if types.contains(baseType) then walk(baseType)
        }
    walk(root)
    seen.toSet

  private def stripArraySuffix(t: String): String =
    val idx = t.indexOf('[')
    if idx >= 0 then t.substring(0, idx) else t

  private def typeHash(name: String, types: Map[String, Seq[(String, String)]], be: CryptoBackend): Array[Byte] =
    be.hash(HashAlgo.Keccak256, encodeType(name, types).getBytes("UTF-8"))

  // ── struct + value encoding ────────────────────────────────────────────

  private def structHash(
    primaryType: String,
    value:       Map[String, ujson.Value],
    types:       Map[String, Seq[(String, String)]],
    be:          CryptoBackend,
  ): Array[Byte] =
    val th   = typeHash(primaryType, types, be)
    val data = new java.io.ByteArrayOutputStream(32 * (1 + types.getOrElse(primaryType, Nil).size))
    data.write(th)
    types.getOrElse(primaryType, Nil).foreach { case (typ, name) =>
      val v = value.getOrElse(
        name,
        throw new IllegalArgumentException(s"EIP-712 value missing field '$name' for type $primaryType"),
      )
      data.write(encodeValue(typ, v, types, be))
    }
    be.hash(HashAlgo.Keccak256, data.toByteArray)

  private def encodeValue(
    typ:   String,
    value: ujson.Value,
    types: Map[String, Seq[(String, String)]],
    be:    CryptoBackend,
  ): Array[Byte] =
    typ match
      case "string" =>
        be.hash(HashAlgo.Keccak256, value.str.getBytes("UTF-8"))

      case "bytes" =>
        be.hash(HashAlgo.Keccak256, Hex.decode(value.str))

      case "address" =>
        // 20-byte address left-padded to 32 bytes
        Hex.leftPad32(Hex.decode(value.str))

      case "bool" =>
        val b = value match
          case ujson.True  => true
          case ujson.False => false
          case ujson.Str(s) => s.equalsIgnoreCase("true")
          case other       => throw new IllegalArgumentException(s"Cannot encode bool from $other")
        val out = new Array[Byte](32)
        if b then out(31) = 1
        out

      case t if t.startsWith("uint") || t.startsWith("int") =>
        val bi: BigInt = value match
          case ujson.Num(d)   => BigInt(d.toLong)
          case ujson.Str(s)   => BigInt(s)
          case ujson.Bool(b)  => if b then BigInt(1) else BigInt(0)
          case other          => throw new IllegalArgumentException(s"Cannot encode $t from $other")
        Hex.bigIntTo32(if bi.signum >= 0 then bi else twosComplement256(bi))

      case t if t.startsWith("bytes") =>
        // Fixed-size bytesN (1..32); pad on the right with zeros to 32 bytes.
        val raw = Hex.decode(value.str)
        val n   = t.substring(5).toInt
        require(raw.length == n, s"$t expects $n bytes, got ${raw.length}")
        val out = new Array[Byte](32)
        System.arraycopy(raw, 0, out, 0, raw.length)
        out

      case nested if types.contains(nested) =>
        // Nested struct: encode as keccak256(structEncoding) per EIP-712.
        value match
          case ujson.Obj(o) => structHash(nested, o.toMap, types, be)
          case other        => throw new IllegalArgumentException(s"Expected struct '$nested', got $other")

      case other =>
        throw new IllegalArgumentException(s"EIP-712 type not supported in Phase 1: $other")

  private def twosComplement256(bi: BigInt): BigInt =
    // For signed negative values: encode as 2^256 + bi
    (BigInt(1) << 256) + bi
