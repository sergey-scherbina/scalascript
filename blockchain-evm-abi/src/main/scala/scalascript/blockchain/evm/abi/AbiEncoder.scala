package scalascript.blockchain.evm.abi

/** Solidity ABI v2 encoder. Walks a `(AbiType, AbiValue)` pair and
 *  emits the wire-format bytes per the spec's head/tail layout.
 *
 *  Algorithm (https://docs.soliditylang.org/en/latest/abi-spec.html):
 *  for a tuple of (T_1, …, T_n) with values (v_1, …, v_n):
 *
 *    enc(v) = head(v_1) || … || head(v_n) || tail(v_1) || … || tail(v_n)
 *
 *  where for each value:
 *    - if T_i is static: head(v_i) = enc(v_i), tail(v_i) = ""
 *    - if T_i is dynamic: head(v_i) = enc_u256(offset_i),
 *                         tail(v_i) = enc(v_i)
 *
 *  Offsets are byte distances from the start of the tuple's enclosing
 *  encoding to the start of `tail(v_i)`.
 */
object AbiEncoder:

  /** Encode a single (type, value) pair as if it were the sole
   *  parameter of an ABI call. For multi-arg calls (the common case)
   *  use `encodeTuple` directly. */
  def encode(typ: AbiType, value: AbiValue): Array[Byte] = typ match
    case t: AbiType.Tuple => encodeTuple(t.fields, asTupleValues(value))
    case _                => encodeStandalone(typ, value)

  /** Encode a list of values as the head/tail tuple representation
   *  used for function-call argument lists. */
  def encodeTuple(types: Seq[AbiType], values: Seq[AbiValue]): Array[Byte] =
    require(types.size == values.size, s"arity mismatch: ${types.size} types vs ${values.size} values")

    // Phase 1: emit each head — for static types it's the inline
    // encoding; for dynamic types it's a placeholder we'll back-fill
    // once tails are known.
    val tails = scala.collection.mutable.ArrayBuffer.empty[Array[Byte]]
    val heads = scala.collection.mutable.ArrayBuffer.empty[Array[Byte]]
    for (t, v) <- types.zip(values) do
      if t.isDynamic then
        tails += encodeStandalone(t, v)
        heads += null   // placeholder; back-filled below
      else
        heads += encodeStandalone(t, v)
        tails += Array.emptyByteArray

    val headBytes = heads.map(h => if h == null then 32 else h.length).sum

    // Phase 2: replace placeholders with the actual offsets.
    var runningOffset = headBytes
    for i <- heads.indices do
      if heads(i) == null then
        heads(i) = uint256(BigInt(runningOffset))
        runningOffset += tails(i).length

    val out = new java.io.ByteArrayOutputStream(headBytes + tails.map(_.length).sum)
    heads.foreach(out.write)
    tails.foreach(out.write)
    out.toByteArray

  /** Encode a single value standalone — top-level entry for non-tuple
   *  parameters; recursive entry for tuple fields. */
  private def encodeStandalone(typ: AbiType, value: AbiValue): Array[Byte] = (typ, value) match
    case (AbiType.UInt(w), AbiValue.UInt(_, v))          => uintN(v, w)
    case (AbiType.SInt(w), AbiValue.SInt(_, v))          => intN(v, w)
    case (AbiType.Address, AbiValue.Address(s))          => leftPad32(decodeHex(s))
    case (AbiType.Bool, AbiValue.Bool(b))                =>
      val out = new Array[Byte](32)
      if b then out(31) = 1
      out
    case (AbiType.FixedBytes(w), AbiValue.FixedBytes(_, b)) =>
      require(b.length == w, s"bytes$w expected $w bytes, got ${b.length}")
      val out = new Array[Byte](32)
      System.arraycopy(b, 0, out, 0, w)
      out
    case (AbiType.Bytes, AbiValue.Bytes(b)) =>
      uint256(BigInt(b.length)) ++ rightPadMul32(b)
    case (AbiType.Str, AbiValue.Str(s)) =>
      val utf = s.getBytes("UTF-8")
      uint256(BigInt(utf.length)) ++ rightPadMul32(utf)
    case (AbiType.DynArray(elem), AbiValue.Arr(items)) =>
      // length || encodeTuple([elem, elem, ...], values)
      uint256(BigInt(items.size)) ++ encodeTuple(Seq.fill(items.size)(elem), items)
    case (AbiType.FixedArray(elem, n), AbiValue.Arr(items)) =>
      require(items.size == n, s"fixed array of size $n got ${items.size} values")
      encodeTuple(Seq.fill(n)(elem), items)
    case (t: AbiType.Tuple, AbiValue.Tuple(values)) =>
      encodeTuple(t.fields, values)
    case (t, v) =>
      throw new IllegalArgumentException(s"Type/value mismatch: $t vs $v")

  // ── primitives ────────────────────────────────────────────────────────

  private[abi] def uint256(v: BigInt): Array[Byte] = uintN(v, 256)

  private def uintN(v: BigInt, width: Int): Array[Byte] =
    require(v.signum >= 0, s"uint$width: negative value $v")
    require(v.bitLength <= width, s"uint$width: value $v exceeds $width bits")
    leftPad32(v.toByteArray.dropWhile(_ == 0))

  private def intN(v: BigInt, width: Int): Array[Byte] =
    require(v.bitLength <= width - 1, s"int$width: value $v out of range")
    val encoded = if v.signum >= 0 then v else (BigInt(1) << 256) + v
    leftPad32(encoded.toByteArray.dropWhile(_ == 0))

  private def leftPad32(bytes: Array[Byte]): Array[Byte] =
    if bytes.length == 32 then bytes
    else if bytes.length < 32 then
      val out = new Array[Byte](32)
      System.arraycopy(bytes, 0, out, 32 - bytes.length, bytes.length)
      out
    else if bytes.length == 33 && bytes(0) == 0 then java.util.Arrays.copyOfRange(bytes, 1, 33)
    else throw new IllegalArgumentException(s"Cannot left-pad ${bytes.length} bytes to 32")

  private def rightPadMul32(b: Array[Byte]): Array[Byte] =
    val padded = ((b.length + 31) / 32) * 32
    if padded == b.length then b
    else
      val out = new Array[Byte](padded)
      System.arraycopy(b, 0, out, 0, b.length)
      out

  private def decodeHex(s: String): Array[Byte] =
    val clean = s.stripPrefix("0x").stripPrefix("0X")
    require(clean.length == 40, s"address must be 40 hex chars, got: $s")
    val out = new Array[Byte](20)
    var i = 0
    while i < 20 do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out

  private def asTupleValues(v: AbiValue): Seq[AbiValue] = v match
    case AbiValue.Tuple(xs) => xs
    case other              => Seq(other)
