package scalascript.blockchain.cosmos

import java.math.BigInteger
import scalascript.crypto.{Sha256, Ripemd160, Ed25519, Secp256k1Group, Secp256k1Ecdsa}

/** Cosmos cryptographic primitives — a thin facade over the portable, from-scratch crypto stack in
 *  `scalascript.crypto` ([[Secp256k1Ecdsa]] / [[Ed25519]] / [[Secp256k1Group]] / [[Sha256]] / [[Ripemd160]]).
 *  No `org.bouncycastle` dependency, so this module cross-compiles to Scala.js with byte-identical results.
 *
 *  - secp256k1 ECDSA, RFC-6979 deterministic k, BIP-62 low-S, DER output (sign + verify)
 *  - ed25519 RFC-8032 signing (64-byte raw signature)
 *  - SHA256, RIPEMD160, hash160 = ripemd160(sha256(x)) */
object CosmosCrypto:

  // ── secp256k1 sign / verify ────────────────────────────────────────────────

  /** Sign a 32-byte hash with secp256k1. Returns a DER-encoded low-S signature (RFC-6979 deterministic k). */
  def sign(privateKey: Array[Byte], hash: Array[Byte]): Array[Byte] =
    Secp256k1Ecdsa.sign(privateKey, hash)

  /** Verify a DER-encoded secp256k1 ECDSA signature. `pubKey` is 33-byte compressed or 65-byte uncompressed. */
  def verify(pubKey: Array[Byte], hash: Array[Byte], sig: Array[Byte]): Boolean =
    Secp256k1Ecdsa.verify(pubKey, hash, sig)

  /** Derive a 33-byte compressed secp256k1 public key from a 32-byte private key. */
  def deriveCompressedPublicKey(privateKey: Array[Byte]): Array[Byte] =
    Secp256k1Ecdsa.derivePublicCompressed(privateKey)

  // ── ed25519 sign / verify ──────────────────────────────────────────────────

  /** Sign `msg` with an Ed25519 private key. `privateKey` may be 32 bytes (seed) or 64 bytes (the low 32 are
   *  the seed). Returns a 64-byte raw RFC-8032 signature. */
  def signEd25519(privateKey: Array[Byte], msg: Array[Byte]): Array[Byte] =
    Ed25519.sign(privateKey.take(32), msg)

  /** Verify an Ed25519 signature. `pubKey` is 32 bytes, `sig` is 64 bytes. */
  def verifyEd25519(pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte]): Boolean =
    Ed25519.verify(pubKey, msg, sig)

  /** Derive the 32-byte Ed25519 public key from a 32-byte seed. */
  def deriveEd25519PublicKey(seed: Array[Byte]): Array[Byte] = Ed25519.derivePublicKey(seed.take(32))

  // ── Hash utilities ─────────────────────────────────────────────────────────

  def sha256(data: Array[Byte]): Array[Byte] = Sha256.digest(data)

  def ripemd160(data: Array[Byte]): Array[Byte] = Ripemd160.digest(data)

  /** RIPEMD160(SHA256(data)) — the standard Cosmos/Bitcoin key hash. */
  def hash160(data: Array[Byte]): Array[Byte] = Ripemd160.digest(Sha256.digest(data))

  // ── DER helpers ────────────────────────────────────────────────────────────

  def encodeDer(r: BigInteger, s: BigInteger): Array[Byte] = Secp256k1Ecdsa.encodeDer(r, s)

  def decodeDer(sig: Array[Byte]): (BigInteger, BigInteger) = Secp256k1Ecdsa.decodeDer(sig)

  private[cosmos] def toUnsigned32(n: BigInteger): Array[Byte] = Secp256k1Group.to32(n)
