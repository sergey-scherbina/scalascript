package scalascript.blockchain.bitcoin

import scalascript.crypto.{Curve, PublicKey}

/** Bitcoin address derivation.
 *
 *  Supported address types:
 *  - **P2WPKH** (SegWit v0, BIP-84 `bc1q…` / `tb1q…`): SHA256 → RIPEMD160
 *    (hash160) of compressed public key → bech32-encoded SegWit v0 witness program.
 *  - **P2TR** (Taproot, BIP-86 `bc1p…` / `tb1p…`): tweaked x-only public key
 *    → bech32m-encoded SegWit v1 witness program.
 *
 *  Legacy P2PKH and P2SH address types (Base58Check) are not implemented — they
 *  are pre-SegWit and rarely needed in new wallet implementations. */
object BitcoinAddress:

  // ── P2WPKH ─────────────────────────────────────────────────────────────────

  /** Derive a P2WPKH bech32 address from a secp256k1 compressed public key.
   *
   *  Process: `hash160(compressedPubKey)` → SegWit v0 witness program → bech32.
   *  Result: `bc1q…` (mainnet) or `tb1q…` (testnet). */
  def p2wpkh(pubKey: PublicKey, testnet: Boolean = false): String =
    require(pubKey.curve == Curve.Secp256k1, s"Bitcoin address requires Secp256k1, got ${pubKey.curve}")
    val compressed = toCompressed(pubKey.bytes)
    val keyHash    = BitcoinCrypto.hash160(compressed)
    val hrp        = if testnet then "tb" else "bc"
    Bech32.encodeSegWit(hrp, 0, keyHash)

  /** Derive a P2WPKH address directly from a compressed public key (raw 33 bytes). */
  def p2wpkhFromCompressed(compressedPubKey: Array[Byte], testnet: Boolean = false): String =
    require(compressedPubKey.length == 33, s"Compressed pubkey must be 33 bytes, got ${compressedPubKey.length}")
    val keyHash = BitcoinCrypto.hash160(compressedPubKey)
    val hrp     = if testnet then "tb" else "bc"
    Bech32.encodeSegWit(hrp, 0, keyHash)

  // ── P2TR (Taproot) ─────────────────────────────────────────────────────────

  /** Derive a P2TR (Taproot) bech32m address from a secp256k1 internal public key.
   *
   *  Process: tweak internal key per BIP-341 (key-path, no scripts) →
   *  x-only 32-byte tweaked key → SegWit v1 witness program → bech32m.
   *  Result: `bc1p…` (mainnet) or `tb1p…` (testnet). */
  def p2tr(pubKey: PublicKey, testnet: Boolean = false): String =
    require(pubKey.curve == Curve.Secp256k1, s"Taproot address requires Secp256k1, got ${pubKey.curve}")
    val compressed = toCompressed(pubKey.bytes)
    p2trFromCompressed(compressed, testnet)

  /** Derive a P2TR address from a 33-byte compressed internal public key. */
  def p2trFromCompressed(compressedPubKey: Array[Byte], testnet: Boolean = false): String =
    require(compressedPubKey.length == 33, s"Compressed pubkey must be 33 bytes, got ${compressedPubKey.length}")
    val tweaked = BitcoinCrypto.tweakedKey(compressedPubKey)
    val xonly   = tweaked.drop(1)  // drop 0x02/0x03 prefix
    val hrp     = if testnet then "tb" else "bc"
    Bech32.encodeSegWit(hrp, 1, xonly)

  // ── Validation ─────────────────────────────────────────────────────────────

  def isValidP2wpkh(addr: String): Boolean =
    val lower = addr.toLowerCase
    (lower.startsWith("bc1q") || lower.startsWith("tb1q")) &&
    Bech32.decodeSegWit(lower).exists { case (ver, prog) => ver == 0 && prog.length == 20 }

  def isValidP2tr(addr: String): Boolean =
    val lower = addr.toLowerCase
    (lower.startsWith("bc1p") || lower.startsWith("tb1p")) &&
    Bech32.decodeSegWit(lower).exists { case (ver, prog) => ver == 1 && prog.length == 32 }

  def isValid(addr: String): Boolean = isValidP2wpkh(addr) || isValidP2tr(addr)

  def normalize(addr: String): String = addr.toLowerCase

  // ── witness program extraction ─────────────────────────────────────────────

  /** Extract the witness program (hash160 for P2WPKH, x-only key for P2TR) from an address. */
  def witnessProgram(addr: String): Either[String, Array[Byte]] =
    Bech32.decodeSegWit(addr.toLowerCase).map { case (_, prog) => prog }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Ensure the public key is 33-byte compressed format.
   *  Accepts: 33-byte compressed (0x02/0x03), 65-byte uncompressed (0x04), 64-byte raw (no prefix). */
  private def toCompressed(bytes: Array[Byte]): Array[Byte] =
    bytes.length match
      case 33 if bytes(0) == 0x02 || bytes(0) == 0x03 => bytes
      case 65 if bytes(0) == 0x04 =>
        // Re-compress from uncompressed point
        val xBytes = bytes.slice(1, 33)
        val yBytes = bytes.slice(33, 65)
        val yBit   = yBytes(31) & 1
        val prefix = if yBit == 1 then 0x03.toByte else 0x02.toByte
        Array(prefix) ++ xBytes
      case 64 =>
        val xBytes = bytes.slice(0, 32)
        val yBytes = bytes.slice(32, 64)
        val yBit   = yBytes(31) & 1
        val prefix = if yBit == 1 then 0x03.toByte else 0x02.toByte
        Array(prefix) ++ xBytes
      case _ =>
        throw new IllegalArgumentException(s"Cannot compress public key of length ${bytes.length}")
