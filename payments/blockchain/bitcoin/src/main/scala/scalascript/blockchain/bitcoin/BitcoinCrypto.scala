package scalascript.blockchain.bitcoin

import java.math.BigInteger
import scalascript.crypto.{Sha256, Ripemd160, Secp256k1Group, Secp256k1Ecdsa, Secp256k1Schnorr}

/** Bitcoin cryptographic primitives — a thin facade over the portable, from-scratch secp256k1 stack in
 *  `scalascript.crypto` ([[Secp256k1Ecdsa]] / [[Secp256k1Schnorr]] / [[Secp256k1Group]] / [[Sha256]] /
 *  [[Ripemd160]]). No `org.bouncycastle` dependency, so this module cross-compiles to Scala.js and runs in a
 *  browser wallet with byte-identical results to the JVM.
 *
 *  - secp256k1 ECDSA, RFC-6979 deterministic k, BIP-62 low-S, DER output (sign + verify)
 *  - Sighash variants: SIGHASH_ALL / NONE / SINGLE + ANYONECANPAY; BIP-143 SegWit sighash for P2WPKH
 *  - Hash utilities: SHA256, hash160 (SHA256 then RIPEMD160), double-SHA256 (hash256), BIP-340 tagged hash
 *  - Taproot BIP-341 / BIP-340: Schnorr signing, tapTweakHash, tweakedKey */
object BitcoinCrypto:

  // ── Sighash type constants ──────────────────────────────────────────────────

  val SIGHASH_ALL          = 0x01
  val SIGHASH_NONE         = 0x02
  val SIGHASH_SINGLE       = 0x03
  val SIGHASH_ANYONECANPAY = 0x80

  // ── ECDSA sign / verify ─────────────────────────────────────────────────────

  /** Sign a 32-byte hash with a secp256k1 private key. Returns a DER-encoded low-S signature (no sighash
   *  suffix), RFC-6979 deterministic k. */
  def sign(privateKey: Array[Byte], hash: Array[Byte]): Array[Byte] =
    Secp256k1Ecdsa.sign(privateKey, hash)

  /** Verify a DER-encoded secp256k1 ECDSA signature. `pubKey` is 33-byte compressed or 65-byte uncompressed. */
  def verify(pubKey: Array[Byte], hash: Array[Byte], sig: Array[Byte]): Boolean =
    Secp256k1Ecdsa.verify(pubKey, hash, sig)

  /** Encode `(r, s)` as DER `SEQUENCE { INTEGER r, INTEGER s }`. */
  def encodeDer(r: BigInteger, s: BigInteger): Array[Byte] = Secp256k1Ecdsa.encodeDer(r, s)

  def decodeDer(sig: Array[Byte]): (BigInteger, BigInteger) = Secp256k1Ecdsa.decodeDer(sig)

  // ── Public key derivation ───────────────────────────────────────────────────

  /** Derive a 33-byte compressed public key from a 32-byte private key. */
  def deriveCompressedPublicKey(privateKey: Array[Byte]): Array[Byte] =
    Secp256k1Ecdsa.derivePublicCompressed(privateKey)

  /** Derive the x-only (32-byte) public key for Taproot / Schnorr. */
  def deriveXonlyPublicKey(privateKey: Array[Byte]): Array[Byte] =
    Secp256k1Ecdsa.derivePublicXonly(privateKey)

  // ── Hash utilities ──────────────────────────────────────────────────────────

  /** SHA256(data) */
  def sha256(data: Array[Byte]): Array[Byte] = Sha256.digest(data)

  /** SHA256(SHA256(data)) — Bitcoin's hash256 / double-SHA256 */
  def hash256(data: Array[Byte]): Array[Byte] = Sha256.digest(Sha256.digest(data))

  /** RIPEMD160(SHA256(data)) — Bitcoin's hash160 */
  def hash160(data: Array[Byte]): Array[Byte] = Ripemd160.digest(Sha256.digest(data))

  /** BIP-340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || data) */
  def taggedHash(tag: String, data: Array[Byte]): Array[Byte] = Secp256k1Schnorr.taggedHash(tag, data)

  // ── BIP-143 SegWit sighash for P2WPKH ─────────────────────────────────────

  /** Compute the BIP-143 sighash for a P2WPKH input.
   *
   *  For P2WPKH the scriptCode is `OP_DUP OP_HASH160 <20-byte-hash> OP_EQUALVERIFY OP_CHECKSIG`
   *  = `76 a9 14 <hash160(pubkey)> 88 ac`. All arguments are little-endian where applicable. */
  def bip143Sighash(
    txVersion:    Array[Byte],
    hashPrevouts: Array[Byte],
    hashSequence: Array[Byte],
    outpointTxid: Array[Byte],
    outpointVout: Array[Byte],
    pubKeyHash:   Array[Byte],
    value:        Array[Byte],
    sequence:     Array[Byte],
    hashOutputs:  Array[Byte],
    locktime:     Array[Byte],
    sighashType:  Array[Byte],
  ): Array[Byte] =
    val scriptCode = p2wpkhScriptCode(pubKeyHash)
    val preimage =
      txVersion ++ hashPrevouts ++ hashSequence ++ outpointTxid ++ outpointVout ++
      scriptCode ++ value ++ sequence ++ hashOutputs ++ locktime ++ sighashType
    hash256(preimage)

  /** Build the BIP-143 scriptCode for P2WPKH: `OP_DUP OP_HASH160 <hash160> OP_EQUALVERIFY OP_CHECKSIG`. */
  def p2wpkhScriptCode(hash160Bytes: Array[Byte]): Array[Byte] =
    require(hash160Bytes.length == 20, "P2WPKH hash160 must be 20 bytes")
    val script = Array[Byte](0x76.toByte, 0xa9.toByte, 0x14.toByte) ++
                 hash160Bytes ++ Array[Byte](0x88.toByte, 0xac.toByte)
    Array[Byte](0x19.toByte) ++ script   // compact-size prefix (0x19 = 25)

  // ── Taproot BIP-341 / BIP-340 ──────────────────────────────────────────────

  /** `taggedHash("TapTweak", <x-only-pubkey> || <merkle-root>)` (empty root = key-path only). */
  def tapTweakHash(xonlyPubKey: Array[Byte], merkleRoot: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    Secp256k1Schnorr.tapTweakHash(xonlyPubKey, merkleRoot)

  /** Tweak an internal public key for Taproot key-path spending. Returns the 33-byte compressed tweaked key. */
  def tweakedKey(internalPubKey: Array[Byte], merkleRoot: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    Secp256k1Schnorr.tweakedKey(internalPubKey, merkleRoot)

  /** Tweaked private key for Taproot key-path spending. */
  def tweakedPrivateKey(privateKey: Array[Byte], merkleRoot: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    Secp256k1Schnorr.tweakedPrivateKey(privateKey, merkleRoot)

  /** BIP-340 Schnorr signing (Taproot key-path spends). `hash` and `auxRand` are 32 bytes; returns 64 bytes. */
  def schnorrSign(privateKey: Array[Byte], hash: Array[Byte], auxRand: Array[Byte] = new Array[Byte](32)): Array[Byte] =
    Secp256k1Schnorr.sign(privateKey, hash, auxRand)

  /** BIP-340 Schnorr verification. `pubKey` is a 32-byte x-only key. */
  def schnorrVerify(pubKey: Array[Byte], hash: Array[Byte], sig: Array[Byte]): Boolean =
    Secp256k1Schnorr.verify(pubKey, hash, sig)

  /** BIP-341 key-path/script-path sighash: `taggedHash("TapSighash", 0x00 || sigMsg)`. */
  def tapSighash(sigMsg: Array[Byte]): Array[Byte] =
    taggedHash("TapSighash", Array[Byte](0x00.toByte) ++ sigMsg)

  /** 32-byte big-endian unsigned encoding of a non-negative scalar. */
  private[bitcoin] def toUnsigned32(n: BigInteger): Array[Byte] = Secp256k1Group.to32(n)
