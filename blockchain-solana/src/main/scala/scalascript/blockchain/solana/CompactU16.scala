package scalascript.blockchain.solana

/** Solana's compact-u16 ("short_vec_length") encoding. 1-3 bytes —
 *  used as the length prefix for every variable-length array in
 *  the transaction wire format. */
private[solana] object CompactU16:

  def encode(n: Int): Array[Byte] =
    require(n >= 0 && n <= 0xffff, s"compact-u16 out of range: $n")
    if n < 0x80 then Array(n.toByte)
    else if n < 0x4000 then
      Array(((n & 0x7f) | 0x80).toByte, ((n >> 7) & 0x7f).toByte)
    else
      Array(
        ((n & 0x7f)        | 0x80).toByte,
        (((n >> 7)  & 0x7f) | 0x80).toByte,
        ((n >> 14) & 0x3).toByte,
      )

  /** Decode the compact-u16 prefix from `bytes` starting at `offset`.
   *  Returns (value, bytesConsumed). */
  def decode(bytes: Array[Byte], offset: Int): (Int, Int) =
    val b0 = bytes(offset) & 0xff
    if (b0 & 0x80) == 0 then (b0, 1)
    else
      val b1 = bytes(offset + 1) & 0xff
      val v01 = (b0 & 0x7f) | ((b1 & 0x7f) << 7)
      if (b1 & 0x80) == 0 then (v01, 2)
      else
        val b2 = bytes(offset + 2) & 0xff
        (v01 | ((b2 & 0x03) << 14), 3)
