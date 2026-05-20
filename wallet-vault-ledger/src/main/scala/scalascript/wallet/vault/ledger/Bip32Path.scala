package scalascript.wallet.vault.ledger

/** Encoder for the BIP-32-style derivation path payload used by every
 *  Ledger app (Ethereum, Solana, Bitcoin, Cardano, …).
 *
 *  Wire format:
 *  {{{
 *  [ N | path[0] | path[1] | … | path[N-1] ]
 *  }}}
 *  - `N` is a single byte, the segment count.
 *  - Each `path[i]` is a 32-bit big-endian unsigned integer.
 *  - Hardened segments have bit `0x80000000` set.
 *
 *  Path notation: `m/44'/60'/0'/0/0` → 5 segments, the first three
 *  hardened. The leading `m/` is optional.
 *
 *  Common defaults:
 *  - Ethereum: `m/44'/60'/0'/0/0`
 *  - Solana:   `m/44'/501'/0'/0'`
 *  - Bitcoin:  `m/84'/0'/0'/0/0`  (BIP-84 segwit) */
object Bip32Path:

  val Hardened: Long = 0x80000000L

  /** Default derivation paths per Ledger app. */
  val DefaultEthereum: String = "m/44'/60'/0'/0/0"
  val DefaultSolana:   String = "m/44'/501'/0'/0'"
  val DefaultBitcoin:  String = "m/84'/0'/0'/0/0"

  /** Parse a path string into a sequence of unsigned 32-bit integers
   *  with the hardened bit already applied. */
  def parse(path: String): Array[Long] =
    val clean = path.stripPrefix("m/").stripPrefix("m")
    if clean.isEmpty then Array.empty[Long]
    else
      clean.split('/').map { seg =>
        if seg.isEmpty then
          throw new IllegalArgumentException(s"Empty segment in path '$path'")
        val hardened = seg.endsWith("'") || seg.endsWith("h") || seg.endsWith("H")
        val numStr   = if hardened then seg.dropRight(1) else seg
        val n        = java.lang.Long.parseLong(numStr)
        require(n >= 0 && n < (1L << 31), s"Path component out of range: $n in '$path'")
        if hardened then n | Hardened else n
      }

  /** Encode a path into the Ledger wire layout. */
  def encode(path: String): Array[Byte] =
    encode(parse(path))

  /** Encode a pre-parsed segment list. */
  def encode(segments: Array[Long]): Array[Byte] =
    require(segments.length <= 10,
      s"Ledger apps accept at most 10 path segments (got ${segments.length})")
    val out = new Array[Byte](1 + segments.length * 4)
    out(0) = segments.length.toByte
    var i = 0
    while i < segments.length do
      val v   = segments(i)
      val off = 1 + i * 4
      out(off)     = ((v >>> 24) & 0xff).toByte
      out(off + 1) = ((v >>> 16) & 0xff).toByte
      out(off + 2) = ((v >>>  8) & 0xff).toByte
      out(off + 3) = ( v         & 0xff).toByte
      i += 1
    out
