package scalascript.blockchain.evm.abi

/** Solidity ABI v2 decoder. Inverse of `AbiEncoder` — given a type
 *  schema and a byte buffer, reconstructs the `AbiValue` tree.
 *
 *  Decoding is offset-based: each call interprets a "frame" starting
 *  at byte index `start` inside the full buffer; dynamic types
 *  follow the head/tail layout via 32-byte offset pointers relative
 *  to the same `start`.
 */
object AbiDecoder:

  /** Decode the ABI-encoded return value of a single-typed function. */
  def decode(typ: AbiType, bytes: Array[Byte]): AbiValue = typ match
    case t: AbiType.Tuple => AbiValue.Tuple(decodeTuple(t.fields, bytes))
    case _                => decodeAt(typ, bytes, 0)._1

  /** Decode a tuple of values — typical use case for a function-call
   *  argument list or return tuple. */
  def decodeTuple(types: Seq[AbiType], bytes: Array[Byte]): Seq[AbiValue] =
    val out = scala.collection.mutable.ArrayBuffer.empty[AbiValue]
    var headPos = 0
    for t <- types do
      if t.isDynamic then
        val offset = readUint256(bytes, headPos).toInt
        out += decodeAt(t, bytes, offset)._1
        headPos += 32
      else
        val (v, consumed) = decodeAt(t, bytes, headPos)
        out += v
        headPos += consumed
    out.toSeq

  /** Decode one value starting at byte offset `start`. Returns the
   *  value plus the number of head bytes consumed (used for static
   *  in-place sizing during tuple decoding). */
  private def decodeAt(typ: AbiType, bytes: Array[Byte], start: Int): (AbiValue, Int) = typ match
    case AbiType.UInt(w) =>
      (AbiValue.UInt(w, readUint256(bytes, start)), 32)

    case AbiType.SInt(w) =>
      val raw = readUint256(bytes, start)
      val signed =
        if (raw.bitLength == 256 && raw.testBit(255))
        then raw - (BigInt(1) << 256)
        else raw
      (AbiValue.SInt(w, signed), 32)

    case AbiType.Address =>
      // 20-byte address sits in the LOW 20 bytes of a 32-byte word.
      val word = java.util.Arrays.copyOfRange(bytes, start, start + 32)
      val addr = java.util.Arrays.copyOfRange(word, 12, 32)
      (AbiValue.Address("0x" + toHex(addr)), 32)

    case AbiType.Bool =>
      val v = readUint256(bytes, start)
      (AbiValue.Bool(v.signum > 0), 32)

    case AbiType.FixedBytes(w) =>
      val word = java.util.Arrays.copyOfRange(bytes, start, start + w)
      (AbiValue.FixedBytes(w, word), 32)

    case AbiType.Bytes =>
      val len  = readUint256(bytes, start).toInt
      val data = java.util.Arrays.copyOfRange(bytes, start + 32, start + 32 + len)
      (AbiValue.Bytes(data), -1)   // dynamic: head consumption tracked separately

    case AbiType.Str =>
      val len  = readUint256(bytes, start).toInt
      val data = java.util.Arrays.copyOfRange(bytes, start + 32, start + 32 + len)
      (AbiValue.Str(new String(data, "UTF-8")), -1)

    case AbiType.DynArray(elem) =>
      val len    = readUint256(bytes, start).toInt
      val frame  = java.util.Arrays.copyOfRange(bytes, start + 32, bytes.length)
      val values = decodeTuple(Seq.fill(len)(elem), frame)
      (AbiValue.Arr(values), -1)

    case AbiType.FixedArray(elem, n) =>
      val frame  = java.util.Arrays.copyOfRange(bytes, start, bytes.length)
      val values = decodeTuple(Seq.fill(n)(elem), frame)
      val consumed = if elem.isDynamic then -1 else n * 32   // static elements pack tightly
      (AbiValue.Arr(values), consumed)

    case t: AbiType.Tuple if !t.isDynamic =>
      val frame  = java.util.Arrays.copyOfRange(bytes, start, bytes.length)
      val values = decodeTuple(t.fields, frame)
      (AbiValue.Tuple(values), staticTupleSize(t))

    case t: AbiType.Tuple =>
      // Dynamic tuple: its position is treated like other dynamics
      // (offset pointer), and we decode its full frame from the
      // pointed-to position.
      val frame  = java.util.Arrays.copyOfRange(bytes, start, bytes.length)
      val values = decodeTuple(t.fields, frame)
      (AbiValue.Tuple(values), -1)

  // ── helpers ────────────────────────────────────────────────────────

  private def readUint256(bytes: Array[Byte], at: Int): BigInt =
    BigInt(1, java.util.Arrays.copyOfRange(bytes, at, at + 32))

  private def staticTupleSize(t: AbiType.Tuple): Int =
    t.fields.map {
      case AbiType.FixedArray(elem, n) if !elem.isDynamic => n * 32
      case st: AbiType.Tuple if !st.isDynamic             => staticTupleSize(st)
      case _                                              => 32
    }.sum

  private def toHex(b: Array[Byte]): String =
    val sb = new java.lang.StringBuilder(b.length * 2)
    var i = 0
    while i < b.length do
      sb.append(f"${b(i) & 0xff}%02x")
      i += 1
    sb.toString
