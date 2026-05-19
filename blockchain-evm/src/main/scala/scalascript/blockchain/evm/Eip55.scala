package scalascript.blockchain.evm

import scalascript.crypto.{CryptoBackend, HashAlgo}

/** EIP-55 mixed-case address checksum.
 *
 *  Algorithm: lowercase the 40-hex address (no 0x prefix), compute
 *  keccak256 of the ASCII bytes, then uppercase the i-th hex character
 *  iff the i-th nibble of the hash is >= 8. */
private[evm] object Eip55:

  def checksum(address: String): String =
    val lower = address.stripPrefix("0x").stripPrefix("0X").toLowerCase
    require(lower.length == 40 && lower.forall(isHex), s"Not a 20-byte hex address: $address")
    val hash  = CryptoBackend.get().hash(HashAlgo.Keccak256, lower.getBytes("US-ASCII"))
    val sb    = new java.lang.StringBuilder(42)
    sb.append("0x")
    var i = 0
    while i < 40 do
      val c = lower.charAt(i)
      if c >= 'a' && c <= 'f' then
        // Nibble at position i within the 32-byte hash:
        val nibble = if (i % 2) == 0 then (hash(i / 2) >>> 4) & 0x0f else hash(i / 2) & 0x0f
        if nibble >= 8 then sb.append((c - 'a' + 'A').toChar)
        else sb.append(c)
      else
        sb.append(c)
      i += 1
    sb.toString

  private def isHex(c: Char): Boolean =
    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
