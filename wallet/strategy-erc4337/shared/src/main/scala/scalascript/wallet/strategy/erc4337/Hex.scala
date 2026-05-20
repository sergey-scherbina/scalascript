package scalascript.wallet.strategy.erc4337

/** Hex encoding / decoding utilities used by the cross-compiled
 *  ERC-4337 shared sources.
 *
 *  This is a small inlined copy of `scalascript.blockchain.evm.Hex` so
 *  the cross-compile slice does not depend on the JVM-only
 *  `blockchain-evm` module.  The JVM-only [[BundlerClient]] +
 *  [[SmartAccountAdapter]] continue to use `scalascript.blockchain.evm.Hex`
 *  directly; that copy stays in `blockchain-evm` (which provides the
 *  EVM HTTP RPC client + EIP-712 / RLP codec that are not part of this
 *  slice).
 *
 *  Trade-off recorded in `docs/wallet-spi-scalajs.md` § Stage 4: we
 *  duplicate ~20 LoC of hex codec to avoid cross-compiling all of
 *  `blockchain-evm` (with its `java.net.http` RPC client) in this
 *  slice.  When/if `blockchain-evm` itself cross-compiles in a future
 *  slice, this file collapses back into the canonical `Hex`. */
object Hex:

  private val HexChars = "0123456789abcdef".toCharArray

  def encode(bytes: Array[Byte], withPrefix: Boolean = true): String =
    val sb = new java.lang.StringBuilder(bytes.length * 2 + (if withPrefix then 2 else 0))
    if withPrefix then sb.append("0x")
    var i = 0
    while i < bytes.length do
      val b = bytes(i) & 0xff
      sb.append(HexChars(b >>> 4))
      sb.append(HexChars(b & 0x0f))
      i += 1
    sb.toString

  def decode(s: String): Array[Byte] =
    val clean =
      if s.startsWith("0x") || s.startsWith("0X") then s.substring(2)
      else s
    require(clean.length % 2 == 0, s"Hex string has odd length: ${clean.length}")
    val out = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out

  def leftPad32(bytes: Array[Byte]): Array[Byte] =
    require(bytes.length <= 32, s"Cannot left-pad ${bytes.length}-byte value to 32")
    if bytes.length == 32 then bytes
    else
      val out = new Array[Byte](32)
      System.arraycopy(bytes, 0, out, 32 - bytes.length, bytes.length)
      out

  def bigIntTo32(bi: BigInt): Array[Byte] =
    require(bi.signum >= 0, "BigInt must be non-negative for unsigned 32-byte encoding")
    val raw = bi.toByteArray
    if raw.length == 32 then raw
    else if raw.length == 33 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, 33)
    else if raw.length < 32 then leftPad32(raw)
    else throw new IllegalArgumentException(s"BigInt too large for 32 bytes: ${raw.length}")
