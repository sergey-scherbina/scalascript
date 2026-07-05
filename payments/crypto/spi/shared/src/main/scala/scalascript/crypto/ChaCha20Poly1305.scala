package scalascript.crypto

/** Portable **ChaCha20-Poly1305** AEAD (RFC 8439) — the ChaCha20 stream cipher + Poly1305 one-time MAC +
 *  the AEAD construction, pure Scala, identical on JVM and Scala.js, no platform crypto. ChaCha20 uses
 *  32-bit `Int` arithmetic (wrapping); Poly1305 uses `BigInt` over the field `2^130 - 5` (a correctness-
 *  first reference, not constant-time). Unblocks PASETO v4.local, Noise, `age`, and COSE_Encrypt. */
object ChaCha20Poly1305:

  // ── ChaCha20 (RFC 8439 §2.1–2.4) ────────────────────────────────────────────────

  private inline def rotl(x: Int, n: Int): Int = (x << n) | (x >>> (32 - n))

  private def leWord(b: Array[Byte], off: Int): Int =
    (b(off) & 0xff) | ((b(off + 1) & 0xff) << 8) | ((b(off + 2) & 0xff) << 16) | ((b(off + 3) & 0xff) << 24)

  /** The 64-byte ChaCha20 keystream block for `key` (32B), `counter` (u32) and `nonce` (12B). */
  def block(key: Array[Byte], counter: Int, nonce: Array[Byte]): Array[Byte] =
    require(key.length == 32 && nonce.length == 12, "ChaCha20 needs a 32-byte key + 12-byte nonce")
    val s = new Array[Int](16)
    s(0) = 0x61707865; s(1) = 0x3320646e; s(2) = 0x79622d32; s(3) = 0x6b206574
    var i = 0
    while i < 8 do { s(4 + i) = leWord(key, i * 4); i += 1 }
    s(12) = counter
    s(13) = leWord(nonce, 0); s(14) = leWord(nonce, 4); s(15) = leWord(nonce, 8)
    val w = s.clone()
    permute(w)
    val out = new Array[Byte](64)
    i = 0
    while i < 16 do { putLe(out, i * 4, w(i) + s(i)); i += 1 }
    out

  /** The ChaCha20 permutation: 20 rounds (10 column/diagonal double-rounds) in place. */
  private def permute(w: Array[Int]): Unit =
    inline def qr(a: Int, b: Int, c: Int, d: Int): Unit =
      w(a) += w(b); w(d) = rotl(w(d) ^ w(a), 16)
      w(c) += w(d); w(b) = rotl(w(b) ^ w(c), 12)
      w(a) += w(b); w(d) = rotl(w(d) ^ w(a), 8)
      w(c) += w(d); w(b) = rotl(w(b) ^ w(c), 7)
    var r = 0
    while r < 10 do
      qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)     // columns
      qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)     // diagonals
      r += 1

  private def putLe(out: Array[Byte], off: Int, v: Int): Unit =
    out(off)     = (v & 0xff).toByte
    out(off + 1) = ((v >>> 8) & 0xff).toByte
    out(off + 2) = ((v >>> 16) & 0xff).toByte
    out(off + 3) = ((v >>> 24) & 0xff).toByte

  /** HChaCha20 (the XChaCha20 subkey function): 32-byte subkey from a 32-byte key + 16-byte nonce.
   *  Runs the ChaCha20 permutation but returns words 0-3 ‖ 12-15 with NO final addition. */
  def hchacha20(key: Array[Byte], nonce16: Array[Byte]): Array[Byte] =
    require(key.length == 32 && nonce16.length == 16, "HChaCha20 needs a 32-byte key + 16-byte nonce")
    val w = new Array[Int](16)
    w(0) = 0x61707865; w(1) = 0x3320646e; w(2) = 0x79622d32; w(3) = 0x6b206574
    var i = 0
    while i < 8 do { w(4 + i) = leWord(key, i * 4); i += 1 }
    w(12) = leWord(nonce16, 0); w(13) = leWord(nonce16, 4); w(14) = leWord(nonce16, 8); w(15) = leWord(nonce16, 12)
    permute(w)
    val out = new Array[Byte](32)
    i = 0
    while i < 4 do { putLe(out, i * 4, w(i)); putLe(out, 16 + i * 4, w(12 + i)); i += 1 }
    out

  /** ChaCha20 encrypt/decrypt `data` (XOR with the keystream) starting at block `counter`. */
  def chacha20(key: Array[Byte], counter: Int, nonce: Array[Byte], data: Array[Byte]): Array[Byte] =
    val out = new Array[Byte](data.length)
    var i = 0; var ctr = counter
    while i < data.length do
      val ks = block(key, ctr, nonce)
      val n  = math.min(64, data.length - i)
      var j = 0
      while j < n do { out(i + j) = (data(i + j) ^ ks(j)).toByte; j += 1 }
      i += 64; ctr += 1
    out

  // ── Poly1305 (RFC 8439 §2.5) ────────────────────────────────────────────────────

  private val P130   = (BigInt(1) << 130) - 5
  private val Mask128 = (BigInt(1) << 128) - 1
  private val ClampMask = BigInt("0ffffffc0ffffffc0ffffffc0fffffff", 16)

  private def leToBig(b: Array[Byte]): BigInt = if b.isEmpty then BigInt(0) else BigInt(1, b.reverse)

  private def bigToLe(n0: BigInt, len: Int): Array[Byte] =
    val out = new Array[Byte](len); var v = n0; var i = 0
    while i < len do { out(i) = (v & 0xff).toInt.toByte; v = v >> 8; i += 1 }
    out

  /** The 16-byte Poly1305 tag of `msg` under the 32-byte one-time key. */
  def poly1305(oneTimeKey: Array[Byte], msg: Array[Byte]): Array[Byte] =
    require(oneTimeKey.length == 32, "Poly1305 needs a 32-byte one-time key")
    val r = leToBig(oneTimeKey.slice(0, 16)) & ClampMask
    val s = leToBig(oneTimeKey.slice(16, 32))
    var acc = BigInt(0)
    var i = 0
    while i < msg.length do
      val len   = math.min(16, msg.length - i)
      val block = msg.slice(i, i + len) ++ Array[Byte](1)      // append the 0x01 high byte
      acc = ((acc + leToBig(block)) * r) % P130
      i += 16
    bigToLe((acc + s) & Mask128, 16)

  // ── AEAD (RFC 8439 §2.8) ────────────────────────────────────────────────────────

  private def pad16(len: Int): Array[Byte] = new Array[Byte]((16 - (len % 16)) % 16)
  private def le64(n: Long): Array[Byte] =
    val out = new Array[Byte](8); var v = n; var i = 0
    while i < 8 do { out(i) = (v & 0xff).toByte; v = v >>> 8; i += 1 }
    out

  private def macData(aad: Array[Byte], ct: Array[Byte]): Array[Byte] =
    aad ++ pad16(aad.length) ++ ct ++ pad16(ct.length) ++ le64(aad.length.toLong) ++ le64(ct.length.toLong)

  private def poly1305KeyGen(key: Array[Byte], nonce: Array[Byte]): Array[Byte] =
    block(key, 0, nonce).slice(0, 32)

  /** AEAD seal: returns `(ciphertext, 16-byte tag)`. `nonce` is 12 bytes. */
  def seal(key: Array[Byte], nonce: Array[Byte], plaintext: Array[Byte], aad: Array[Byte] = Array.emptyByteArray)
      : (Array[Byte], Array[Byte]) =
    val ct  = chacha20(key, 1, nonce, plaintext)
    val tag = poly1305(poly1305KeyGen(key, nonce), macData(aad, ct))
    (ct, tag)

  /** AEAD open: returns the plaintext iff the tag authenticates (constant-time compare); else `None`. */
  def open(key: Array[Byte], nonce: Array[Byte], ciphertext: Array[Byte], tag: Array[Byte],
           aad: Array[Byte] = Array.emptyByteArray): Option[Array[Byte]] =
    val expected = poly1305(poly1305KeyGen(key, nonce), macData(aad, ciphertext))
    if constEq(expected, tag) then Some(chacha20(key, 1, nonce, ciphertext)) else None

  // ── XChaCha20-Poly1305 (extended 24-byte nonce; draft-irtf-cfrg-xchacha20poly1305) ──

  /** The 12-byte ChaCha nonce derived from a 24-byte XChaCha nonce: `0x00000000 ‖ nonce24[16:24]`. */
  private def xChachaNonce(nonce24: Array[Byte]): Array[Byte] =
    val n = new Array[Byte](12); System.arraycopy(nonce24, 16, n, 4, 8); n

  /** XChaCha20-Poly1305 AEAD seal — 24-byte `nonce`. Returns `(ciphertext, 16-byte tag)`. */
  def xseal(key: Array[Byte], nonce: Array[Byte], plaintext: Array[Byte], aad: Array[Byte] = Array.emptyByteArray)
      : (Array[Byte], Array[Byte]) =
    require(nonce.length == 24, s"XChaCha20 nonce must be 24 bytes, got ${nonce.length}")
    seal(hchacha20(key, java.util.Arrays.copyOfRange(nonce, 0, 16)), xChachaNonce(nonce), plaintext, aad)

  /** XChaCha20-Poly1305 AEAD open — returns the plaintext iff the tag authenticates, else `None`. */
  def xopen(key: Array[Byte], nonce: Array[Byte], ciphertext: Array[Byte], tag: Array[Byte],
            aad: Array[Byte] = Array.emptyByteArray): Option[Array[Byte]] =
    if nonce.length != 24 then None
    else open(hchacha20(key, java.util.Arrays.copyOfRange(nonce, 0, 16)), xChachaNonce(nonce), ciphertext, tag, aad)

  private def constEq(a: Array[Byte], b: Array[Byte]): Boolean =
    if a.length != b.length then false
    else { var r = 0; var i = 0; while i < a.length do { r |= (a(i) ^ b(i)); i += 1 }; r == 0 }
